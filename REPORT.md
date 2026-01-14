# Závěrečná zpráva - Distribuovaná chatovací aplikace (DSVA)

## 1. Základní přehled (Basic Overview)
Tato aplikace implementuje distribuovaný chatovací systém založený na logické kruhové topologii (Token Ring). Zajišťuje vzájemné vyloučení (Mutual Exclusion) pro přístup ke sdíleným zdrojům (v tomto případě demonstrativní Kritická Sekce) pomocí putovního tokenu. Aplikace je robustní vůči výpadkům uzlů a ztrátě tokenu.

### Použité technologie
- **Jazyk:** Java 21
- **Build tool:** Maven
- **Komunikace:** TCP Sockets (přenos zpráv), HTTP REST API (ovládání a monitoring)
- **Architektura:** Peer-to-Peer (P2P) bez centrálního serveru (pouze dočasný leader při bootstrapu).

---

## 2. Stav uzlu (Node State)
Každý uzel v síti si udržuje následující informace:

1.  **Identita:** Svoje vlastní `NodeInfo` (IP adresa a Port) a unikátní hashované ID.
2.  **Topologie (`RingTopology`):**
    *   `NextNode`: Přímý následník v kruhu (kam se posílá token).
    *   `PrevNode`: Přímý předchůdce (odkud se očekává token).
    *   `AllNodes`: Seznam všech známých živých uzlů v síti. Tento seznam slouží k opravě kruhu v případě výpadku bezprostředního souseda (uzel ví, koho kontaktovat jako dalšího v řadě).
3.  **Stav Mutexu (`TokenBasedMutex`):**
    *   Zda uzel aktuálně drží Token.
    *   Zda uzel žádá o vstup do Kritické Sekce (`wantCriticalSection`).
    *   `Token Generation ID`: Pro rozlišení starých a nově vygenerovaných tokenů.
4.  **Logický čas (`LogicalClock`):** Lamportovy hodiny pro částečné uspořádání událostí v distribuovaném systému.
5.  **Historie chatu:** Seznam přijatých zpráv.

---

## 3. Propojování uzlů a formování kruhu
Proces připojení (`join`) nového uzlu do sítě probíhá následovně:

1.  **Bootstrap:** První uzel se spustí jako leader a vytvoří kruh sám se sebou (`Next` = `Prev` = `Self`).
2.  **Žádost o připojení:** Nový uzel pošle zprávu typu `JOIN` na známou IP:Port existujícího uzlu.
3.  **Začlenění do kruhu:**
    *   Oslovený uzel přijme nováčka jako svého nového `Next`.
    *   Původní `Next` osloveného uzlu se stane `Next`em nováčka.
    *   Systém posílá zprávy `UPDATE_NEIGHBORS` a `UPDATE_PREV`, aby všichni dotčení sousedé (Nový, Starý, Soused) aktualizovali své ukazatele.
4.  **Synchronizace topologie:** Po úspěšném připojení se rozešle `TOPOLOGY_UPDATE` (broadcast), aby všechny uzly v síti věděly o novém členovi. To je klíčové pro pozdější opravy sítě.

---

## 4. Algoritmus Token Ring a Vzájemné vyloučení
Algoritmus běží nepřetržitě na pozadí v každém uzlu.

### Oběh Tokenu
*   V síti existuje právě jeden unikátní objekt `Token`.
*   Token neustále obíhá po kruhu (`Self` -> `Next` -> ...).
*   Když uzel přijme Token:
    1.  Aktualizuje svůj `updateGenerationCounter`.
    2.  Odešle všechny čekající chatové zprávy (broadcast po kruhu).
    3.  Zkontroluje, zda chce vstoupit do Kritické Sekce (`enterCS`).
        *   **ANO:** Ponechá si Token a pozastaví jeho posílání, dokud sekci neopustí (`leaveCS`).
        *   **NE:** Po krátké prodlevě (simulace práce/sítě) předá Token dál uzlu `Next`.

### Kritická Sekce (CS)
*   **Vstup:** API zavolá `enterCS()`. Uzel nastaví příznak `wantCS = true`. Až uzel obdrží Token, vstoupí do CS a drží Token.
*   **Výstup:** API zavolá `leaveCS()`. Uzel nastaví `wantCS = false` a v příštím cyklu smyčky Token odešle dál.

---

## 5. Odolnost proti chybám a Obnova topologie

### Detekce výpadku
Aplikace detekuje výpadek uzlu dvěma způsoby:
1.  **Aktivní detekce:** Při pokusu odeslat zprávu (např. Token) sousedovi dojde k `IOException` (connection refused/timed out).
2.  **Pasivní detekce (Timeout):** Uzel očekává, že uvidí Token v pravidelných intervalech. Pokud Token nedorazí po dobu `3 * TokenTimeout`, uzel předpokládá problém (ztráta tokenu nebo rozpad kruhu).

### Oprava kruhu (Ring Repair)
Pokud uzel zjistí, že jeho `Next` je nedostupný:
1.  Spustí proceduru `handleNeighborFailure`.
2.  Odstraní mrtvý uzel ze svého seznamu topologie.
3.  Hledá v seznamu `AllNodes` dalšího nejbližšího následníka (kandidáta).
4.  Zkouší kontaktovat kandidáty (`PING`). První živý kandidát se stane novým `Next`.
5.  Odešle `TOPOLOGY_UPDATE` všem ostatním uzlům, aby také vyřadily mrtvý uzel ze svých tabulek.

### Regenerace Tokenu
Pokud uzel, který držel Token, zhavaruje, Token je ztracen.
1.  Soused, který detekoval výpadek a opravil kruh, automaticky zjistí, zda se tím mohl ztratit Token.
2.  Jako součást opravy kruhu zavolá `mutex.regenerateToken()`.
3.  Nový Token dostane vyšší `generationId`, aby se předešlo konfliktům, pokud by se "ztracený" token náhodou vrátil (např. při falešné detekci způsobené jen zpožděním sítě).

### Re-join sirotků (Orphaned Nodes)
Vzácně se může stát, že uzel zůstane "sirotkem" (jeho sousedé změnili topologii, ale on o tom neví).
*   Uzel sleduje, zda dostává zprávy.
*   Pokud vyprší timeouty a uzel má podezření, že je mimo kruh, pokusí se aktivně znovu připojit (`attemptRejoin`) ke známým uzlům v historii.

---

## 6. Implementované API body
Ovládání probíhá přes REST API na portech `8001`-`8005`:
*   `GET /status`: Vypíše stav uzlu, sousedy, přítomnost tokenu a historii chatu.
*   `POST /join`: Připojení k jinému uzlu.
*   `POST /chat`: Odeslání zprávy.
*   `POST /enterCS` / `leaveCS`: Vstup a výstup z KS.
*   `POST /kill` / `revive`: Simulace výpadku a obnovy uzlu.
*   `POST /setDelayMs`: Simulace zpoždění sítě (pro testování timeoutů).
