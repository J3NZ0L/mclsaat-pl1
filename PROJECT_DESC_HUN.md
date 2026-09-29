## Téma leírása

A vállalati szoftverarchitektúrák napjainkban paradigmaváltáson mennek keresztül: a merev, determinisztikus SaaS és ERP integrációk helyét a dinamikus, ágens-alapú (Agentic) orkesztrációs munkafolyamatok veszik át. A modern LLM-ek és ágensek már nem csupán asszisztensi funkciókat látnak el, hanem teljes üzleti folyamatokat képesek önállóan koordinálni.

A vállalatok legnagyobb kihívása azonban a meglévő, heterogén **legacy rendszereik** (adatbázisok, REST/SOAP API-k, hagyományos BPEL/BPMN folyamatmotorok) biztonságos és hatékony bevonása ebbe az új ökoszisztémába. A modernizáció kulcsa a **Model Context Protocol (MCP)**, amely standard interfészként („USB-portként”) elrejti az alrendszerek komplexitását az ágensek elől, valamint a digitális tranzakciókat és kereskedelmi folyamatokat szabványosító **Google Universal Commerce Protocol (UCP)**.

### **A hallgató feladatai lépésről lépésre:**

1. **A mock legacy vállalati infrastruktúra gyors prototipizálása („vibe coding”):**
    
    - Egy működő, realisztikus vállalati mikrokörnyezet felépítése generatív AI eszközökkel (pl. Cursor, Claude Code, GitHub Copilot).
        
    - Minél heterogénebb alrendszerek létrehozása:
        
        - Adatbázisok és alapvető entitások (termékkatalógus, raktárkészlet, rendelések, partnerek).
            
        - API végpontok és hagyományos üzleti logikát / folyamatokat leíró engine (pl. BPEL/BPMN munkafolyamat motor).
            
        - Modulok lefedése: raktárkezelés/logisztika, számlázás és könyvelési modulok, valamint külső fizetési integráció szimulációja (pl. Stripe API).
            
2. **Ágens-alapú refaktorálás és MCP Server réteg kialakítása:**
    
    - Standardizált **MCP (Model Context Protocol) szerverek** megtervezése és implementálása a legacy komponensek fölé, amelyek elrejtik a nyers API-kat és közvetlen DB hozzáféréseket.
        
    - Specializált képességekkel (Skills) felruházott ágensek konfigurálása, amelyek természetes nyelven (pl. chatbottal történő rendelésleadás, státuszlekérdezés, hibakezelés) vezérlik a vállalati folyamatokat.
        
    - AI Tokenomics mérés és optimalizáció: token-költség és válaszidő monitorozása a végrehajtási ciklusok során.
        
3. **Google Universal Commerce Protocol (UCP) integráció:**
    
    - A rendszer felkészítése és összekötése az UCP szabvánnyal, megvalósítva az autonóm, platformfüggetlen kereskedelmi és tranzakciós folyamatokat.
        
4. **Transzformációs metodológia kidolgozása és dokumentálása:**
    
    - Egy reprodukálható módszertani útmutató összeállítása arról, hogy hagyományos monolit / mikroszerviz alapú vállalati rendszereket milyen lépések mentén érdemes autonóm, MCP-alapú ágens architektúrára átállítani.
        

### **A projekt fő kimenetei (Deliverables):**

1. **Működő Proof-of-Concept (PoC) szoftverrendszer:**
    
    - Legacy backend + folyamatmotor.
        
    - Rétegzett MCP szerver integráció és funkcionális AI ágensek (chat-alapú folyamatvezérléssel és Stripe integrációval).
        
    - Működő Google UCP prototípus.
        
2. **Modernizációs Módszertani Útmutató (Methodology Document):**
    
    - Részletes technológiai és architektúrális esettanulmány a legacy rendszerek ágens-alapú transzformációjának lépéseiről, kockázatairól és Tokenomics megfontolásairól.

## Saját pontosítások / hangsúlyok

*(Ez a saját kiegészítésem és értelmezésem, a fenti hivatalos feladatleírást nem módosítva.)*

- **Heterogenitás mint fő "legacy" jellemző:** a legacy rendszer heterogenitása (eltérő protokollok, szinkron/aszinkron interakciós modellek, szemantikai eltérések az alrendszerek adatmodelljei között) a kritikus szempont — nem az elavult kódstílus vagy egyéb technikai adósság-jelek.
- **Heterogenitási minimum:** 3 alrendszer, ebből legalább 1 aszinkron interakciós modellű (pl. beágyazott BPMN folyamatmotor), és legalább 1 szemantikailag eltérő adatmodellű (pl. eltérő ID-terek, mértékegységek, pénznem-ábrázolás) — pusztán protokollbeli különbség (REST vs. SOAP) önmagában nem elég.
- **Folyamatmotor:** Flowable vagy Camunda 7, beágyazott library-ként, nem önálló deployment — ez egyben az egyik heterogén (aszinkron) alrendszer is.
- **Scope-plafon:** 3 alrendszer, kb. 4 tábla összesen, egy happy-path folyamat + egy tudatos hibaág.
- **Tokenomics-kísérlet (későbbi fázis, egyelőre csak jegyzet):** token-költség és válaszidő összehasonlítása nyers heterogén interfészekkel (séma a promptban) vs. MCP rétegen keresztül végzett fordítással, ugyanarra az üzleti tranzakcióra.
- **Domain-döntés:** ld. `C01.md` Domain szekció (két opció mérlegelve).

