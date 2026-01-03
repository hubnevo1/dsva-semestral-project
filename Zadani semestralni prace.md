**Zadání - Chatovací program**

*   Program umožnující zasílání zpráv mezi jednotlivými uzly. Zprávy bude vždy možné v rámci celého systému úplně uspořádat (k synchronizaci použijte buď 'vůdce' nebo výlučný přístup v rámci celého systému). Jednotlivé uzly budou implementovat minimálně tyto funkce: pošli/přijmi zprávu, odhlaš se ze systému, skonči bez odhlášení, přihlaš se do systému.
*   Token bude putovat od uzlu k uzlu a uzel bude moci přidávat zprávy pouze při držení tokenu, čímž zachovám uspořádání zpráv. Pokud vypadne uzel, který zrovna držel token, tak uzel, který detekuje pád uzlu vygeneruje nový token.
    

**Požadavky**

*   Programy musí podporovat interaktivní i dávkové řízení (např. přidání a odebrání procesu). Je vhodné implementovat nějakou základní verzi CLI (není povinné, pokud je možné využívat API). Je nutné implementovat REST API, pomocí kterého lze následně ovládat celý node (operace join, leave, kill, ...)
*   Kromě správnosti algoritmu se zaměřte i na prezentaci výsledků. Cílem je aby bylo poznat co Váš program právě dělá.
*   Srozumitelné výpisy logujte na konzoli i do souboru/ů. Výpisy budou opatřeny časovým razítkem logického času.
*   Každý uzel bude mít jednoznačnou identifikaci. Doporučená je kombinace IP adresy a portu.
*   Je doporučeno mít implementovanou možnost zpožďovat odeslání/příjem zprávy. Vhodné pro generování souběžných situací.
    

**Specifikace zadání**

*   V rámci specifikace je nutné splnit následující 5 parametry:
    *   typ problému, který bude úloha řešit - regenerace tokenu (Token bude putovat od uzlu k uzlu a uzel bude moci přidávat zprávy pouze při držení tokenu, čímž zachovám uspořádání zpráv. Pokud vypadne uzel, který zrovna držel token, tak uzel, který detekuje pád uzlu vygeneruje nový token.)
    *   implementovaný symetrický algoritmus - Ping-Pong
    *   programovací jazyk a co se bude používat k transportu zpráv - Java Sockets
    *   funkcionalita práce - chatovací program
    *   topologie - kruh s tokenem
        

**Odevzdávání a prezentace**

*   Součástí odevzdávaných materiálů bude:
    *   Zdrojové kódy s potřebnými knihovnami a návodem/skriptem jak práci zprovoznit.
    *   Krátká zpráva (1-3 stránky A4) popisující základní funkci (co si pamatují uzly, jak se propojují, jak probíhá obnova topologie, kdy a jak se spouští implementovaný algoritmus, ...) + reaguje na odpovídající dotazy k daným typům úloh (viz kapitola Dotazy). Cílem zprávy je usnadnit a urychlit prezentaci a kontrolu práce.

*   Minimální prezentační konfigurace
    *   5 uzlů/procesů na 5 fyzických strojích (příp. virtuálech) v případě sdíleného uživatelského disku v různých adresářích.
        *   v případě omezených zdrojů - 5 uzlů/procesů a 3 fyzické stroje (virtuály - např. hostovací systém + 2x VM)
            

———————————————

**Obecné**

Pro testování a odevzdání je potřeba mít implementován mechanismus pro opožďování zpráv (aby se dala simulovat pomalejší linka).

Co testujeme a co je potřeba mít funkční:

*   Zpoždění zpráv (simulace pomalého komunikačního kanálu – nějaký sleep před posláním zprávy).
    
*   REST API funkce:
    *   setDelay - nastavení zpoždění komunikace (použití je, že v nastavím zpoždění při odesílání zprávy a poté pošlu zprávu, kterou chci opozdit; pak nastavím zpoždění zpět na 0)
        

**Topologie**

Jednou z částí je tvorba a udržování konzistentní topologie. Je nutné si uvědomit, že topologie vyžadovaná pro implementaci jednoho algoritmu, nemusí být ta stejná, která je pak používaná pro funkci celého systému. Jedná se často o překryvnou/virtuální (overlay) topologii. Je nutné mít vymyšlený mechanismus, který se postará o opravu topologie v případě výpadku 1 uzlu (v rámci testování semestrální práce nebude docházet k výpadkům více než jednoho uzlu zároveň).

Co testujeme a co je potřeba mít funkční:

*   Z plně funkčního systému (5+ uzlů), zkuste odebírat postupně jednotlivé uzly, až Vám zbyde jen jediný (po každém ustálení topologické konfigurace vyzkoušejte funkčnost). Poté zase postupně přidávejte uzly až jich budete mít 5 (po každém ustálení topologické konfigurace vyzkoušejte funkčnost).
    
*   REST API funkce:
    *   join - připojení uzlu do topologie
    *   leave - korektní odpojení uzlu z topologie (informuje ostatní o odpojení a korektně opustí/opraví topologii)
    *   kill - nekorektní odpojení (odstřižení komunikace - efekt úmrtí uzlu - implementováno by mělo být jako odpojení komunikačního rozhraní užívaného pro komunikaci mezi uzly)
    *   revive - oživení komunikace (obnovení komunikace s ostatními uzly - obnovení komunikace s ostatními uzly)
        *   _kill_ a _revive_ se používá pro simulaci vypadnutí uzlu (API by mělo stále běžet a fungovat aby bylo možné po _kill_ zavolat _revive_)
            

**Výlučný přístup**

Cílem je kritická sekce a v ní nějaká aktivita (chat – poslání zprávy).

*   Lamport
*   Ricart – Agrawala
*   Carvalho – Roucairol
*   Suzuki – Kasami
    

Co testujeme a co je potřeba mít funkční:

*   Zastavte jeden z uzlů v kritické sekci. Nechte zažádat další 3 uzly a zkontrolujte jak proběhnou následující vstupy do kritické sekce. Jsou uspořádané dle vzniku požadavku? Je to správně?
*   Nastavte u jednoho z uzlů pomalejší odesílání zpráv a zkontrolujte funkci předchozího bodu.

*   REST API funkce:
    *   enterCS - vstoupí do kritické sekce (může být vázáno na potřebnou operaci vázající se k funkci práce - změň hodnotu proměnné, pošli zprávu, ...)
    *   leaveCS - opustí kritickou sekci (viz výše)
        

**Leader Election**

Cílem je zvolení vůdce a následné využívání jeho služeb (chat – poslání zprávy).

*   Basic heartbeat
*   Chang – Roberts
*   Hirschberg – Sinclair
*   Peterson / Dolev – Klave – Rodeh
    

Co testujeme a co je potřeba mít funkční:

*   Zahajte volby na 3 uzlech zároveň (nejspíš bude potřeba zpožďovat zprávy, aby se vám povedlo toho dosáhnout). Zkontrolujte funkčnost a správnost volby vůdce.

*   REST API funkce:
    *   startElection - funkce na spuštění voleb (i když to není indikováno z důvodu ztráty leadera)
        

**Deadlocks**

V rámci přípravy na implementaci algoritmu je potřeba nachystat funkce, které dokáží ovládat nastavení požadavků (u zdrojů – předběžná žádost, žádost, přidělení, uvolnění; u komunikace – čekání na zprávu, příjem zprávy, změna aktivní/pasivní). Samotná žádost musí být prováděna uvnitř kritické sekce (takže součástí je implementace algoritmu kritické sekce, dle vašeho uvážení). Samotná funkce je detekce deadlocku. Když využíváte pro komunikaci některý z messaging systémů, lze jej použít jako koordinátora přístupu ke kritické sekci (jen je potřeba se dobře zamyslet nad tím, kdy uzel pozná, kdy může vstoupit do kritické sekce).

*   Lomet – global
    *   Graf závislostí se dá chápat jako sdílená proměnná, kterou upravujete v rámci kritické sekce.

*   Lomet – local
    *   Pro zjednodušení lze využít každý uzel jako vlastníka odpovídajícího zdroje (jen je pak potřeba vyřešit, co se stane, když uzel vypadne).

*   Chandy – Misra - Hass
    

Co testujeme a co je potřeba mít funkční:

*   Postupné vytvoření cyklu v grafu závislostí (alespoň se 4 uzly).
    
*   REST API funkce:
    *   preliminaryRequest / request / acquire / release - ovládání přístupu ke zdroji
    *   waitForMessage / receiveMessage / setActive / setPassive - ovládání závislostí na zprávách
    *   startDetection - explicitní start detekce uváznutí (pokud není implementováno přímo v rámci ovládání přístupu ke zdroji).
        

**DHT**

Jako funkce stačí jen základní operace nad hashovací tabulkou (put/get).

*   Chord
*   Kademlia
    

Co testujeme a co je potřeba mít funkční:
*   Postupné ukládání hodnot a kontrola jejich distribuce na jednotlivé nody.
*   REST API funkce:
*   get / set - práce s hashovací tabulku - úložiště (key, value)