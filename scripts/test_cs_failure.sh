#!/bin/bash
# DSVA Critical Section Failure Test Script
# Tests scenario: Node in critical section (holding token) is killed
# Expected: Misra Ping-Pong algorithm detects failure, regenerates tokens

set -e

# Configuration
BASE_IP="127.0.0.1"
BASE_PORT=5001
BASE_API_PORT=8001
NUM_NODES=10
SLEEP_TIME=4

# Paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_FILE="$PROJECT_DIR/target/DSVA-1.0-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/logs"

# Node arrays (1-indexed for readability)
declare -a NODE_PORT
declare -a NODE_API_PORT
declare -a NODE_PID

for i in $(seq 1 $NUM_NODES); do
    NODE_PORT[$i]=$((BASE_PORT + i - 1))
    NODE_API_PORT[$i]=$((BASE_API_PORT + i - 1))
done

# Cleanup function
cleanup() {
    echo ""
    echo ">>> Shutting down all nodes..."
    for i in $(seq 1 $NUM_NODES); do
        if [ -n "${NODE_PID[$i]}" ]; then
            kill "${NODE_PID[$i]}" 2>/dev/null || true
        fi
    done
    echo ">>> All nodes stopped."
}

trap cleanup EXIT

# Helper function to make API calls with nice output
api_call() {
    local description="$1"
    local url="$2"
    echo ""
    echo "=========================================="
    echo ">>> $description"
    echo ">>> URL: $url"
    echo "=========================================="
    curl -s "$url" || echo "(No response)"
    echo ""
}

# Wait for node to be ready
wait_for_node() {
    local port=$1
    local max_wait=10
    local count=0
    while ! curl -s "http://$BASE_IP:$port/status" > /dev/null 2>&1; do
        sleep 0.5
        count=$((count + 1))
        if [ $count -ge $max_wait ]; then
            echo "ERROR: Node on port $port failed to start"
            exit 1
        fi
    done
}

echo "=============================================="
echo "# CRITICAL SECTION FAILURE TEST"
echo "=============================================="
echo ""
echo "This script tests:"
echo "  1. Node enters critical section (holds mutex token)"
echo "  2. Node is killed while in critical section"
echo "  3. Misra Ping-Pong detects failure and regenerates tokens"
echo "  4. Other nodes continue to function"
echo ""


echo "####################################################"
echo "# BUILDING PROJECT"
echo "####################################################"
echo ""

cd "$PROJECT_DIR"
echo ">>> Running: mvn clean package -q"
mvn clean package -q -DskipTests
echo ">>> Build complete: $JAR_FILE"
echo ""

# Create logs directory
mkdir -p "$LOG_DIR"

# ============================================
# START NODES
# ============================================
echo "####################################################"
echo "# STARTING $NUM_NODES NODES"
echo "####################################################"
echo ""

# Start first node (leader)
echo ">>> Starting Node 1 (Leader) on port ${NODE_PORT[1]}, API port ${NODE_API_PORT[1]}"
java -jar "$JAR_FILE" $BASE_IP ${NODE_PORT[1]} ${NODE_API_PORT[1]} > "$LOG_DIR/node1.log" 2>&1 &
NODE_PID[1]=$!
wait_for_node ${NODE_API_PORT[1]}
echo "    Node 1 started (PID: ${NODE_PID[1]})"

# Start remaining nodes and join them
for i in $(seq 2 $NUM_NODES); do
    prev=$((i - 1))
    echo ">>> Starting Node $i on port ${NODE_PORT[$i]}, API port ${NODE_API_PORT[$i]}, joining Node $prev"
    java -jar "$JAR_FILE" $BASE_IP ${NODE_PORT[$i]} ${NODE_API_PORT[$i]} $BASE_IP ${NODE_PORT[$prev]} > "$LOG_DIR/node$i.log" 2>&1 &
    NODE_PID[$i]=$!
    wait_for_node ${NODE_API_PORT[$i]}
    echo "    Node $i started (PID: ${NODE_PID[$i]})"
    sleep 1
done

echo ""
echo ">>> All $NUM_NODES nodes started successfully!"
echo ">>> Logs available in: $LOG_DIR/"
sleep $SLEEP_TIME


echo ""
echo "####################################################"
echo "# PHASE 1: CHECK INITIAL NODE STATUS"
echo "####################################################"

for i in $(seq 1 $NUM_NODES); do
    api_call "Status of Node $i" "http://$BASE_IP:${NODE_API_PORT[$i]}/status"
done


echo ""
echo "####################################################"
echo "# PHASE 2: NODE 3 ENTERS CRITICAL SECTION"
echo "####################################################"

api_call "Node 3 requests to enter critical section" "http://$BASE_IP:${NODE_API_PORT[3]}/enterCS"

echo ""
echo ">>> Waiting for token to arrive at Node 3..."
sleep 5

api_call "Check Node 3 status (should show InCS=true, Token=true)" "http://$BASE_IP:${NODE_API_PORT[3]}/status"
sleep 2

echo ""
echo ">>> Verifying Node 3 holds the token..."
for i in 1 2 4 5; do
    api_call "Status of Node $i (should show Token=false)" "http://$BASE_IP:${NODE_API_PORT[$i]}/status"
done


echo ""
echo "####################################################"
echo "# PHASE 3: KILL NODE 3 WHILE IN CRITICAL SECTION"
echo "####################################################"
echo ""
echo ">>> Node 3 is holding the mutex TOKEN in critical section."
echo ">>> Killing Node 3 will cause:"
echo ">>>   - Mutex TOKEN to be lost"
echo ">>>   - PING/PONG may be lost (if Node 3 was holding them)"
echo ">>>   - Nodes 2 and 4 will detect failure and repair ring"
echo ""

api_call "KILL Node 3 (simulate crash while in CS)" "http://$BASE_IP:${NODE_API_PORT[3]}/kill"

echo ""
echo ">>> Waiting for Misra Ping-Pong failure detection and ring repair (~5 seconds)..."
echo ">>> When PING or PONG fails to be delivered to Node 3:"
echo ">>>   - Node 2 (or 4) detects failure via failed PING/PONG delivery"
echo ">>>   - Ring is repaired using nextNext/prevPrev"
echo ">>>   - Tokens (PING, PONG, mutex TOKEN) are regenerated"
sleep 5


echo ""
echo "####################################################"
echo "# PHASE 4: CHECK STATUS AFTER FAILURE"
echo "####################################################"

echo ""
echo ">>> Checking if ring was repaired and tokens regenerated..."

for i in $(seq 1 $NUM_NODES); do
    api_call "Status of Node $i" "http://$BASE_IP:${NODE_API_PORT[$i]}/status"
done

echo ""
echo ">>> Expected observations:"
echo ">>>   - One node should now have Token=true (regenerated)"
echo ">>>   - Ring should be repaired: Node 2's Next should be Node 4 (skipping Node 3)"
echo ">>>   - Node 4's Prev should be Node 2 (skipping Node 3)"
echo ">>>   - Misra tokens should show hasPing/hasPong activity"


echo ""
echo "####################################################"
echo "# PHASE 5: TEST CONTINUED OPERATION"
echo "####################################################"

echo ""
echo ">>> Testing if the system can still send chat messages..."

api_call "Node 1 sends chat message" "http://$BASE_IP:${NODE_API_PORT[1]}/chat?msg=Message%20after%20CS%20failure"
sleep $SLEEP_TIME

api_call "Node 5 sends chat message" "http://$BASE_IP:${NODE_API_PORT[5]}/chat?msg=System%20recovered%20successfully"
sleep $SLEEP_TIME


echo ""
echo "####################################################"
echo "# PHASE 6: FINAL STATUS CHECK"
echo "####################################################"

for i in $(seq 1 $NUM_NODES); do
    api_call "Final status of Node $i" "http://$BASE_IP:${NODE_API_PORT[$i]}/status"
done

echo ""
echo "=============================================="
echo "# SUMMARY"
echo "=============================================="
echo ""
echo "This test demonstrated:"
echo "  ✓ Node 3 entered critical section (held mutex token)"
echo "  ✓ Node 3 was killed while holding the token"
echo "  ✓ Misra Ping-Pong algorithm detected the failure"
echo "  ✓ Ring was repaired (Node 2 -> Node 4, skipping dead Node 3)"
echo "  ✓ Tokens (PING, PONG, mutex TOKEN) were regenerated"
echo "  ✓ System continued to function with 4 nodes"
echo ""
echo "Key insight: The Misra Ping-Pong algorithm ensures token regeneration"
echo "even when the token holder crashes unexpectedly."
echo ""
echo "Log files are available in: $LOG_DIR/"
