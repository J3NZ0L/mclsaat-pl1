## Feladatok

### Legacy rendszer

**Domain (frissítve 2026-09-23):** közüzemi szolgáltató — internetszolgáltató (internet és mobil szolgáltatások). Két opció, egyszerűbbtől a bonyolultabb felé:

1. **Tiszta B2C előfizetéses szolgáltatás** — csomagok/tarifák katalógusa → előfizetés/aktiválás → havi számlázás → fizetés (ide tartozhat csomagváltás, extra adatkeret/roaming hozzáadása is). A heterogenitás kizárólag a belső alrendszerek protokoll-/interakció-keverékéből jön. Kevesebb narratív komplexitás, az UCP közvetlenül ráül az előfizetés-vásárlásra mint "checkout"-jellegű tranzakcióra.

#### Szolgáltatások:
**Konkrét szolgáltatások (1. domain-opcióhoz, döntés 2026-09-23):**

1. **Csomagok/tarifák böngészése** — katalógus-lekérdezés (mindkét kliens-csatorna: chat és UCP discovery használja)
2. **Előfizetés indítása/aktiválás** — a fő happy-path tranzakció (BPMN-folyamat indítása), ide köt be az UCP checkout
3. **Aktiválás/rendelés státuszának lekérdezése** — az async folyamat (2. alrendszer) miatt szükséges polling-tool, enélkül az ágens nem tud mit kezdeni a várakozó instance-szal
4. **Csomagváltás / extra adatkeret-roaming hozzáadása** — ugyanannak a BPMN-folyamatnak egy variánsa, olcsó kiegészítés, és jó demó egy UCP-s add-on-vásárlásra
5. **Számla lekérdezése és fizetés indítása** — billing/Stripe, ez zárja a happy path-et
6. **Elakadt aktiválás vagy számlázási batch-visszaigazolás felderítése/feloldása** — a tudatos hibaág, kizárólag a belső ops-kliens tool-ja

*Tudatosan kihagyva:* hálózati hibabejelentés/support-ticketing (pl. "nincs net" bejelentés) — ez már network-ops domain, nem az agentic-commerce/modernizációs sztori része, feleslegesen nagyítaná a scope-ot.

#### Heterogenitás (döntés, 2026-09-22):
- Minimum heterogenitás: 3 alrendszer, ebből legalább 1 aszinkron interakciós modellű, legalább 1 szemantikailag eltérő adatmodellű (nem csak eltérő protokoll), **legalább 3 fajta API (REST, SOAP, ...)**.
- Konkrét felosztás:
	1. Csomagok/előfizetések nyilvántartása — direkt DB-hozzáférés, szemantikai eltérés (pl. előfizetés-/ügyfélazonosító formátumok, adatkeret-mértékegységek GB/MB, tarifacsomag-kódok)
	2. Előfizetés-aktiválás/folyamat — beágyazott BPMN motor (Flowable vagy Camunda 7, library-ként, nem önálló deployment), aszinkron, correlation (pl. SIM-aktiválás, szolgáltatóváltás/portálás)
	3. Számlázás/fizetés — batch/EDI-szerű (2. opciónál: roaming-elszámolás jellegű) vagy SOAP alrendszer + Stripe, itt köt be az UCP checkout

#### Kliens:
- külső előfizető mint kliens, két csatornán — natural-language chat a cég saját asszisztensével (előfizetés/csomagváltás, státuszlekérdezés, hibakezelés) és UCP-n keresztüli vásárló-ágens (ugyanaz a szerepkör, más protokoll, közös backend/MCP toolok) — kiegészítve egy belső ügyfélszolgálati/ops munkatárssal, aki ugyanazt a chat-infrastruktúrát használja, bővebb jogosultsággal a hibaág (pl. elakadt aktiválás vagy számlázási batch-visszaigazolás) feloldására

### Ágens rendszer architektúrája

#### Architektúra-vázlat (2026-09-23)

```
              Belső subsystem-kliens réteg
        (közös logika — ezt hívja majd mindegyik MCP szerver, illetve közvetlenül az UCP adapter)
                             |
        +--------------------+--------------------+
        v                    v                     v
   Legacy DB            BPMN motor            Batch + Stripe
  (Csomagok/             (Aktiválás/            (Számlázás/
   Előfizetések)          Folyamat)              Fizetés)
```

**Kulcsdöntések:**

- **6 szolgáltatás → kb. 6+1 tool** (a 6 korábban rögzített szolgáltatás, plusz a fenti diagnosztikai tool), egyelőre 1 tool/szolgáltatás granularitással.

#### Scope-plafon (döntés, 2026-09-22):
- 3 alrendszer, kb. 4 tábla összesen, egy happy-path folyamat + egy tudatos hibaág (a hibaág az, ahol az ops-ágens ténylegesen bizonyítja a létjogosultságát).
