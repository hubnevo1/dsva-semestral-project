#!/bin/bash
# DSVA Distributed Chat Demonstration Script
# This script demonstrates the functionality of the token-ring based distributed chat application

set -e

# Configuration
BASE_IP="127.0.0.1"
BASE_PORT=5001
BASE_API_PORT=8001
NUM_NODES=5
SLEEP_TIME=3

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
echo "# DSVA DISTRIBUTED CHAT DEMONSTRATION"
echo "=============================================="
echo ""
echo "This script demonstrates:"
echo "  1. Node joining and ring formation"
echo "  2. Token circulation"
echo "  3. Chat message broadcasting"
echo "  4. Node failure and ring repair"
echo "  5. Token regeneration"
echo "  6. Critical section (mutex) operations"
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
echo "# PHASE 2: CHAT MESSAGE BROADCASTING"
echo "####################################################"

api_call "Node 1 sends a chat message" "http://$BASE_IP:${NODE_API_PORT[1]}/chat?msg=Hello%20from%20Node%201!"
sleep $SLEEP_TIME

api_call "Node 3 sends a chat message" "http://$BASE_IP:${NODE_API_PORT[3]}/chat?msg=Hello%20from%20Node%203!"
sleep $SLEEP_TIME

api_call "Check status on Node 5 to see received messages" "http://$BASE_IP:${NODE_API_PORT[5]}/status"
sleep $SLEEP_TIME


echo ""
echo "####################################################"
echo "# PHASE 3: CRITICAL SECTION (MUTEX) OPERATIONS"
echo "####################################################"

api_call "Node 2 enters critical section (requests token)" "http://$BASE_IP:${NODE_API_PORT[2]}/enterCS"

echo ""
echo ">>> Waiting for token to arrive at Node 2..."
sleep 5

api_call "Check status of Node 2 (should show InCS=true, Token=true)" "http://$BASE_IP:${NODE_API_PORT[2]}/status"
sleep 2

api_call "Node 2 leaves critical section (releases token)" "http://$BASE_IP:${NODE_API_PORT[2]}/leaveCS"
sleep $SLEEP_TIME


echo ""
echo "####################################################"
echo "# PHASE 4: NETWORK DELAY SIMULATION"
echo "####################################################"

api_call "Set 5s delay on Node 3" "http://$BASE_IP:${NODE_API_PORT[3]}/setDelayMs?ms=5000"
sleep 1

api_call "Node 1 sends message (will be delayed passing through Node 3)" "http://$BASE_IP:${NODE_API_PORT[1]}/chat?msg=Testing%20with%20delay"
sleep $SLEEP_TIME

api_call "Status of Node 5 before message arrives" "http://$BASE_IP:${NODE_API_PORT[5]}/status"
sleep 1

api_call "Reset delay on Node 3" "http://$BASE_IP:${NODE_API_PORT[3]}/setDelayMs?ms=0"
sleep $SLEEP_TIME

echo ""
echo "####################################################"
echo "# PHASE 5: NODE FAILURE AND RING REPAIR"
echo "####################################################"

api_call "Status before failure - Node 1" "http://$BASE_IP:${NODE_API_PORT[1]}/status"
sleep 1

api_call "KILL Node 3 (simulate crash)" "http://$BASE_IP:${NODE_API_PORT[3]}/kill"

echo ""
echo ">>> Waiting for token timeout detection and ring repair (~15 seconds)..."
echo ">>> Token timeout is 10s, plus time for 3 consecutive timeouts and repair."
sleep 15

api_call "Status of Node 1 after repair" "http://$BASE_IP:${NODE_API_PORT[1]}/status"
sleep 1

api_call "Status of Node 4 after repair" "http://$BASE_IP:${NODE_API_PORT[4]}/status"
sleep 1

api_call "Send chat message after failure" "http://$BASE_IP:${NODE_API_PORT[1]}/chat?msg=Message%20after%20Node%203%20failure"
sleep $SLEEP_TIME


echo ""
echo "####################################################"
echo "# PHASE 6: NODE RECOVERY (REVIVE)"
echo "####################################################"

api_call "REVIVE Node 3" "http://$BASE_IP:${NODE_API_PORT[3]}/revive"
sleep $SLEEP_TIME

api_call "Node 3 re-joins the network via Node 2" "http://$BASE_IP:${NODE_API_PORT[3]}/join?ip=$BASE_IP&port=${NODE_PORT[2]}"
sleep $SLEEP_TIME

api_call "Status of Node 3 after rejoin" "http://$BASE_IP:${NODE_API_PORT[3]}/status"
sleep 1

api_call "Send chat from recovered Node 3" "http://$BASE_IP:${NODE_API_PORT[3]}/chat?msg=Node%203%20is%20back!"
sleep $SLEEP_TIME


echo ""
echo "####################################################"
echo "# PHASE 7: FINAL STATUS CHECK"
echo "####################################################"

for i in $(seq 1 $NUM_NODES); do
    api_call "Final status of Node $i" "http://$BASE_IP:${NODE_API_PORT[$i]}/status"
done

echo ""
echo "=============================================="
echo "# SUMMARY"
echo "=============================================="
echo ""
echo "This demonstration showed:"
echo "  ✓ Ring topology formation with $NUM_NODES nodes"
echo "  ✓ Token-based mutual exclusion"
echo "  ✓ Chat message broadcasting around the ring"
echo "  ✓ Critical section entry/exit"
echo "  ✓ Network delay simulation"
echo "  ✓ Node failure detection and ring repair"
echo "  ✓ Token regeneration after failure"
echo "  ✓ Node recovery and re-join"
echo ""
echo "Log files are available in: $LOG_DIR/"