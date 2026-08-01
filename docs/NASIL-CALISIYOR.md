# Relay — Arka planda ne oluyor

Bu doküman kodun ne yaptığını anlatır. Pazarlama değil: her akış için gerçek sınıf ve dosya
adı verilir, koda inmek isteyen oradan devam edebilir. Zayıf yerler de yazılıdır — jüri
oradan sorar.

---

## 1. Bir cümleden bir işe

Kullanıcı `POST /api/runs` ile bir hedef yazar ("blocker kayıtlarını bul ve ekibe özet geç").
`RunService.start` hedefi doğrular (boş değil, ≤ 2000 karakter — hedef her prompt'a olduğu
gibi girdiği için sınırsız bir metin bütçeyi tek başına yakar), `Run` kaydını açar ve
**hemen döner**. İş, dört iş parçacıklı `orchestratorExecutor` üzerinde arka planda yürür
(`ApplicationConfig`). İstemci `GET /api/runs/{id}/stream` ile SSE'ye bağlanır.

```
   hedef
     │
     ▼
  Planner ──── LLM (JSON şema) ──► adım listesi (≤ 8, ≤ 6 önerilir)
     │
     ▼
  Coordinator ── her adım için ──► PolicyEngine  (auto | ask | forbidden)
     │                                  │
     │                                  ├─ ask  → dur, kullanıcıya sor
     │                                  └─ auto → devam
     ▼
  ToolAgent  ── parametreleri kesinleştir → koruma kapıları → aracı çağır
     │
     ▼
  Verifier   ── sonuç hedefi karşılıyor mu?  hayır → adımı geri gönder (en fazla 2)
     │
     ▼
  Summarizer ── "ne oldu" cümlesi          →  run.finished
```

Rol dağılımı:

| Sınıf | İşi |
|---|---|
| `application/orchestrator/Planner.java` | Hedef → sıralı adımlar. **Ayrıştırılamayan yanıt** (düz yazı, düşünce zinciri — hiç adım dizisi yok) `PlanUnreadableException` ile koşuyu Türkçe gerekçeyle `failed` kapatır; canlıda bu yanıtlar tek adımlık "Hedefi özetle"ye düşüp yeşil kapanıyordu ve hiçbir kaynağa dokunmamış koşu "Tamamlandı" diyordu. Ayrışan ama **boş** plan (`[]`) gerçek bir cevaptır ve tek düşünme adımına iner. Uydurulmuş araç adı `toolName = null` yapılır. |
| `Coordinator.java` | Döngüyü yürütür. **Yeniden girilebilir**: insana ihtiyaç duyan ilk adımda durur, `approve`/`reject` geldiğinde tam oradan devam eder. Run başına `ReentrantLock` ile aynı akışın iki kez sürülmesi engellenir. |
| `ToolAgent.java` | Uzman. Adımın aracının şemasına göre parametreleri kesinleştirir (önceki adımların sonuçlarını okuyarak), koruma kapılarından geçirir, çağırır. Araçsız adımı LLM ile yazar. |
| `Verifier.java` | Denetçi. Sonucu hedefe karşı yargılar. `pass:false` ise adım ajanına geri gider (`Step.MAX_RETRIES`); yazma adımında bu geri dönüş **onayı da tüketir** (§2). Parse edilemeyen yargı adımı geçirir ama artık **"denetlenemedi … adım geçti, doğrulanmadı"** diye yazılır — "doğrulandı" kimsenin denetlemediği adıma harcanmaz (`Verdict.judged`). `pass` alanı eksik ama şikâyet taşıyan JSON ise adımı düşürür: alanı eksik olumsuz yargıdır. |
| `Summarizer.java` | Kapanış cümlesi. Başarısız olabilir; akış zaten bitmiştir, özet yoksa sadece kapanış satırı değişir. |
| `AgentJournal.java` | Ajanlar arası her cümle hem `Run`'a yazılır hem `agent.message` olayı olarak akar. Zaman çizelgesinde "kim kime ne dedi" bundan gelir. |

Planlayıcının atlanabildiği iki giriş noktası var, ama motor aynı:
`RunService.startFromSuggestion` (Bugün ekranındaki bir karta tıklanınca — tek adım tohumlanır)
ve `RunService.startFromPlaybook` (`application/playbook/Playbooks.java` içindeki yazılı
akışlar). İkisinde de politika motoru, onay kapısı ve koruma katmanları birebir aynı çalışır;
kapının etrafından dolaşan bir hızlı yol **yoktur**.

---

## 2. Onay kapısı ve politika motoru

Risk seviyesi araca gömülüdür — `Tool.risk()`: `READ`, `WRITE`, `DESTRUCTIVE`.
Varsayılan politika `RiskLevel.defaultMode()` ile buradan türetilir:

```
READ        → auto        (okuma serbest)
WRITE       → ask         (yazma insana sorar)
DESTRUCTIVE → forbidden   (hiç çalışmaz)
```

`PolicyEngine.evaluate(toolName)` bu varsayılanı verir; operatörün `PUT /api/policies` ile
kaydettiği `ToolPolicy` satırı varsa **o kazanır**. Kayıtlı olmayan bir araç adı `forbidden`
döner — yani halüsinasyon araç adı otomatik reddedilir.

Override'ın tek sınırı var: **`DESTRUCTIVE` bir araç `auto` yapılamaz.** `PolicyEngine.set`
bu isteği 400 ile reddeder, `evaluate` de elle yazılmış bir `auto` satırını `ask`'e çeker —
kural yalnız API kullanıldığında geçerliyse kural değildir. Gevşetme yasak değil, insanı
atlamak yasak: `forbidden → ask` serbest (birinin silmeyi hiç çalıştırabilmesi gerekir),
`→ auto` değil. Tek bir yanlış politika isteği ile geri alınamaz bir silme arasında bir
insan kalır.

```
adım → PolicyEngine
        ├─ forbidden → journal'a "YASAK: <gerekçe>" + adım reddedildi, akış devam
        ├─ ask       → Coordinator.park(): step = awaiting_approval,
        │              run  = awaiting_approval, step.awaiting olayı yayınlanır
        │              (params dahil — ekranda gönderilecek metin görünür)
        └─ auto      → CostMeter.budgetExceeded? → evetse yine park et (bütçe kapısı)
                       → ToolAgent.execute
```

Kullanıcı `POST /api/runs/{id}/steps/{stepId}/approve` derse `RunService.approve` adımı
onaylar ve koordinatörü yeniden sürer. Park nedeni adımda saklanır (`Step.pausedBy`:
`policy | budget`) çünkü onay ayrı bir HTTP isteğidir; bütçe parkının onayı yalnız
`run.budgetOverridden(true)` yapar — tavan o akış için kalkar, **karar boş kalır** ve
yazma adımı yine kendi onay kapısına gelir. Bütçe kapısı politika kapısından *önce*
denetlenir: ters sıra, kullanıcıya para mesajı okutup yazma izni imzalatıyordu.

İki değişmez kural daha var, ikisi de canlı bir olaydan sonra kondu:

- **Bir onay bir deneme alır.** Tek `jira.createIssue` onayı, denetçi reddedip adım
  yeniden denenince üç Jira kaydı açtı (KAN-24/25/26). Artık yazma adımı hangi nedenle
  geri dönerse dönsün (`retryWithProviderFeedback`, plan onarımı, denetçi reddi)
  `decision` temizlenir, parametreler o anda yeniden türetilir ve adım kapıya geri gelir.
- **Gönderilemez taslak kapıya gelmez.** Canlıda `projectKey`'siz ve `summary`'siz bir
  `jira.createIssue` onaylatıldı, araç tam o alanlar için reddetti ve aynı taslak aynı
  insana geri geldi (onay kartında `params={}`). Şemayı geçmeyen ya da okunmaz (yer
  tutuculu/şablonlu) taslak artık `Coordinator.rewriteBeforeAsking` ile kapıya çıkmadan
  onarılır; `Step.MAX_RETRIES` içinde düzelmezse adım eksik alanları sayarak başarısız
  olur — insana ikinci kez, kimsenin gönderemeyeceği bir şey sorulmaz.

Red farklı çalışır. `RunService.reject` adımı `decision=REJECTED`, `rejectReason=<gerekçe>`
yapar ama **`PENDING` bırakır**. Sonlandırmayı koordinatör yapar: `Coordinator.rejectStep`
adımı terminal `rejected` durumuna alır, gerekçeyi `AgentJournal` üzerinden adımın kendi
ajanına yazar ve `step.finished` olayı olarak yayınlar. Yani gerekçe kaybolmaz; iz kaydında
"kim reddetti, neden" satırı kalır ve sonraki adımların prompt'una giren geçmişin parçası olur.

---

## 3. Bugün ekranı

`GET /api/brief` → `application/brief/BriefService.java`. Üç özellik bilinçli:

**Bayat özet anında, yenisi arkada (stale-while-revalidate).** Ölçüm: önbellek sıcakken
67 ms, soğuk üretim 3.6 s, tüm Groq anahtarları duvardayken ücretli katmana düşünce
14.3 s — ve o saniyeler boyunca ekran, beklerken değişmeyen bir günün özetini bekliyordu.
TTL'i geçmiş brief artık hemen döner (`stale: true` + `cachedAt` ile, kimseye sessizce
dün servis edilmez), yenisi aynı tek-uçuş kapısından arkada kurulur. **Yenile** yine
bekler: o buton "bunun güncel olduğuna inanmıyorum" demektir. Kalan tek bekleme yeniden
başlatma sonrası ilk üretimdi — o da boot'a alındı: `BriefWarmup` ilk brief'i
`ApplicationReadyEvent`'te kurar (canlı ölçüm: deploy sonrası ilk istek 28.6 s idi,
ısıtmadan sonra 71 ms), başarısızlığı açılışı asla düşürmez, `BRIEF_WARM_ON_START=false`
kapatır.

```
             ┌── gmail.listToday ──┐
             ├── jira.listMyIssues ┤
GET /api/brief ── github.listMyPullRequests ─┤  hepsi AYNI ANDA,
             ├── github.listMyIssues ────────┤  sanal thread + adım başı timeout (8 sn)
             └── calendar.listToday ─────────┘
                          │
              interleave() → her kaynaktan sırayla (round-robin)
                          │
                   InsightService  ── tek LLM çağrısı, ilk 14 öğe
                          │
                   DigestService   ── günün tek paragraflık özeti
                          │
              { digest, priority[], inbox, work, code, calendar }
```

**Paralel.** Beş READ aracı `briefExecutor` (virtual thread per task) üzerinde aynı anda
koşar. Ekran en yavaş sağlayıcı kadar bekler, toplamları kadar değil.

**Kısmi başarı.** Her bölüm `status: ok | unavailable | error` ve kullanıcıya doğrudan
gösterilecek Türkçe bir `reason` taşır. Gmail bağlı değilse o kart griye düşer, çağrı
başarısız olmaz. `BriefService.failureReason` sağlayıcı hatasını **çevirir**: ham mesaj asla
geçirilmez, çünkü içinde URL, istek gövdesi veya token olabilir. Ayrıca canlı kurulumda
bağlantısı olmayan bir araç fixture döndürürse (`mode = "replay (no connection)"`) bu `ok`
değil `unavailable` sayılır — demo verisi kullanıcının gerçek gelen kutusu diye sunulmaz.

**Başarılı yanıt da çevrilir.** Aynı kural araç *sonucu* için de geçerli: her araç
`AbstractTool.project` ile ne taşıdığını söylemek zorunda (soyut metot — yeni entegrasyon
unutamaz). Ölçü "kullanıcı ne okuyor": bir Jira kaydından `key`, `fields.summary`,
`status.name`, `priority.name`, `issuetype.name`, `assignee.displayName`, `updated` kalır;
`self`, `id`, `expand`, `iconUrl`, `avatarUrls`, `statusCategory`, `accountId` ve
`nextPageToken` kalmaz. Seçilen biçim fixture'ların biçimi: hem Bugün ekranı hem asistan
zaten `fields.status.name` okuyor, ve canlı ile replay'in farklı cevap vermesi ayrı bir
hatadır. Fixture'lar da projeksiyondan geçer, böylece bu doğru kalır. Ölçüm (canlı,
2026-08-01): tek okuma adımının `step.finished` olayı 21.424 → 2.219 bayt.

**Insight katmanı.** `InsightService` bütün öğeleri **tek** şema kısıtlı LLM çağrısına
sokar; 12 öğelik brief 12 tur değil 1 tur eder. Yanıt güvenilmez kabul edilir: önerilen her
`tool` `ToolRegistry`'ye karşı doğrulanır, olmayan araç düşürülür. Model ulaşılamazsa
deterministik anahtar-kelime sınıflandırıcısı devreye girer (`source: heuristic`), ekran boş
kalmaz. `demoteBulk`: `List-Unsubscribe` başlığı taşıyan bülten, model ne derse desin
`fyi/low`'a düşürülür — canlıda "bugs" kelimesi geçen bir DEV Community bülteni yüksek
öncelikli hata bildirimi diye Jira kaydı önermişti.

**Digest.** `DigestService` günü bir bütün olarak yargılar: bir paragraf, sıralı öncelik
listesi ve tek bir tavsiye. Model `degraded` ise **hiç üretilmez** ve `digest` alanı
yanıttan çıkar. Şablon metin üretmek yerine yokluk tercih edilmiştir; boş bir paragraf
içgörü gibi görünür ama değildir.

Aynı tercih **bozuk metin** için de geçerli (#48). Kapı yalnız `degraded` durumunu
kapsamıyordu; canlıda sağlıklı modelden çıkan paragraf "…birlikte beberapaNeeds_reply
mailleri bekliyor. id=gmail:19fbb199a0786906…" ve "…önemli bir vấn" diye okunuyordu.
Artık her alan gösterilmeden önce deterministik olarak denetlenir: iç kimlik dizesi
(`id=`, `gmail:…`), prompt'un kendi alan adları (`aciliyet=`), ham enum (`needs_reply`,
`bug_report`), Türkçe/İngilizce dışı harf (Vietnamca, Kiril, CJK) ya da adı konmuş
Endonezce kelime taşıyan alan **düşürülür** — yeniden üretilmez, şablonla doldurulmaz.
`summary` düşerse `digest` hiç dönmez; ekranın üst satırı (`today`) modelsiz sayıldığı
için boş kalmaz. Türkçe harfler (`çğıöşü`, `âîû`) beyaz listededir: eleme kuralı onları
hiçbir koşulda yakalamaz.

**Öneri parametreleri karttan tohumlanır** (#47). Onay ekranında insanın okuduğu alanlar
modelin tahmini değil, kartın kendi verisidir: `jira.createIssue` önerisinde `projectKey`
bağlantıdan gelir (`BriefService.projectKeyFrom`), `summary` kartın metninden bir kelime
paylaşmıyorsa mailin konusuyla değiştirilir, gövdesiz kayıt önerilmez. Canlıda kapıya
`{"summary":"Yeni iş talebi","projectKey":"RELAY"}` gelmiş, bağlantıda yazan `KAN` ise
ikinci turda kullanılmıştı — bilgi baştan elde vardı.

Kritik kural: **öneri ≠ eylem.** Bu katman hiçbir şey çalıştırmaz. Karta tıklanınca
`POST /api/runs/from-suggestion` normal akışı başlatır ve yazma adımı yine onay ister.

---

## 4. Koruma katmanları

Ürünün asıl farkı burada. Her biri canlıda yaşanan bir olaydan sonra eklendi.

**1 · Uydurulmuş kayıt anahtarı + plan onarımı.** `ToolAgent.ungroundedIdentifier`.
"Bunu kapat" denince planlayıcı `jira.updateIssue {issueKey: RELAY-1}` üretti — anahtarı
uydurmuştu. Jira 404 verdi; şanslı sonuç buydu, o anahtar var olan bir tenant'ta yabancının
kaydı kapanırdı. Artık yazma adımındaki `*key`/`*id`/`*number` alanları hedef metninde,
önceki adım sonuçlarında veya bağlantı ayarlarında **geçmiyorsa** adım durur.
Koordinatör bunu tanır (`ToolAgent.UNGROUNDED` işareti) ve akışı öldürmek yerine onarır:
`Coordinator.insertLookupBefore` aynı sağlayıcının en ucuz `search`/`list` aracını plana
**yeni bir adım olarak** ekler, sonraki adımların sırasını kaydırır, yazma adımını geri
gönderir ve `ToolAgent.withoutIdentifiers` ile uydurulan anahtarı parametrelerden siler
(silinmezse taslak şemayı yine geçer, model hiç çağrılmaz ve eklenen arama adımı hiçbir şeyi
değiştirmez). Yeni adım plana görünür şekilde eklenir — onarım gizli bir retry değil, iz
kaydının parçası. `projectKey`, `repo`, `channel` gibi *kap* alanları bu kontrolün dışında
(`CONTAINER_FIELDS`): onları hedefin anmasını şart koşmak sıradan işi bloke ederdi.

**2 · Şablon/boş içerik kapısı.** `application/text/Filler.java` + `ToolAgent.emptyContent`.
Canlıda Slack'e şu düştü: *"Relay özeti — … Adımlar Relay tarafından yürütüldü; ayrıntılar
zaman çizelgesinde."* Groq anahtarı hız sınırına takılmıştı, yedek model kendi şablonunu
yazdı, onay ekranı bunu gösterdi ve mesaj gitti. Okuyan hiçbir şey öğrenmedi. Artık yazma
araçlarının insan okuyacağı alanları (`text`, `message`, `body`, `comment`, `description`)
bilinen şablon kalıplarına karşı taranır; eşleşirse mesaj sağlayıcıya **gönderilmez**. Kapı
kasıtlı olarak aptal: kaliteyi yargılamaz, bilinen kalıpları eşler.

**3 · Çözülmemiş yer tutucu kapısı.** `application/text/Placeholder.java`.
Slack'e kanal olarak `{{steps[3].channel}}` gönderildi, cevap `channel_not_found` oldu — var
olan bir kanal için kafa karıştırıcı bir hata, çünkü gönderilen değer hiç kanal adı değildi.
Relay'de şablon motoru yok; değerler adımlar arasında uzmanın önceki sonuçları okuyup gerçek
değeri yazmasıyla taşınır. Yani `{{`, `}}`, `${`, `steps[` içeren bir parametre, modelin
doldurmayı reddettiği bir alandır. Okuma araçlarında da geçerli: içinde `{{…}}` olan bir JQL
de aynı derecede bozuk, ama sağlayıcının hatası bunu söylemez. İşaretçiler dar tutuldu —
açılı parantez listede yok, çünkü Slack'in kendi sözdizimi `<@U123>` kullanıyor.

**4 · Uydurulan kabın varsayılana çevrilmesi — ve eksik zorunlu kabın doldurulması.**
`ToolAgent.groundContainers` + `ToolAgent.withConfiguredContainers` + `Tool.withDefaults`.
Bir akış sırayla `#genel`, `C046F7R6UE9`, `#general` kanallarına yazmayı denedi — üç makul
uydurma, üç `channel_not_found` — bağlantıda `defaultChannel = #all-samed` yazılı dururken.
Kayıt anahtarının aksine kabın güvenli bir cevabı var: hedefte, sonuçlarda ve bağlantıda
doğrulanamayan kap alanı (`projectKey`, `channel`, `repo`, `parentDatabaseId`,
`spreadsheetId`, `sheetName` — `CONTAINER_FIELDS`) bağlantıdaki varsayılana çevrilir.
İkinci yarı sonradan yaşandı: kap *yanlış* değil **yok** ise kimse bakmıyordu — bağlantıda
`projectKey = KAN` dururken koşu `$.projectKey is required` ile öldü ve bir insana bu
hatayla ölecek taslak onaylatıldı. Artık şemanın **required** saydığı boş kap bağlantıdan
doldurulur; isteğe bağlı kap doldurulmaz (yokluğu "her yerde ara" demektir) ve içerik
alanları asla doldurulmaz — `summary` üretilemediyse bu bir başarısızlıktır, hedef metni
başlık diye ödünç alınmaz. Çözüm **onaydan önce** yapılır: onaylanan parametre ile
gönderilen parametre aynı olmalı, onaydan sonra sessizce düzeltilen bir kanal başka bir
mesaj olurdu. Ayrıca `ToolAgent.settings` bağlantıdaki *ayar* alanlarını
(`defaultChannel`, `projectKey`, `baseUrl`, `login`, `repo`) prompt'a koyar — sıkı beyaz
liste, token asla prompt'a girmez.

**5 · Sınırsız JQL'i sınırlama.** `JiraTool.bound`. Jira'nın `/rest/api/3/search/jql` ucu
kısıtlayıcı yan tümcesi olmayan sorguya HTTP 400 ("Unbounded JQL queries are not allowed
here") verir; planlayıcının `ORDER BY updated DESC` üretmesi hem makul hem de garanti
başarısızlık. `bound()` sorguyu ayrıştırır, sondaki `ORDER BY`'ı ayırır ve önüne
`project is not EMPTY AND (...)` ekler — pratikte hiçbir şeyi kısıtlamaz ama doğrulayıcıyı
her zaman geçer.

**6 · Sağlayıcı hatasını uzmana geri verme.** `Coordinator.retryWithProviderFeedback`.
Sağlayıcılar düzeltmeyi hatanın içinde söyler: *"'Blocked' geçişi yok, mümkün olanlar:
Yapılacaklar, Devam Ediyor, İncelemede, Tamam"*. Orada başarısız olmak, uzmanın
kullanabileceği bir cevabı çöpe atmaktır. Hata `step.lastProviderError()`'a yazılır, adım geri
gönderilir ve parametreler bu bilgiyle yeniden üretilir. **Yazma adımında ek kural var:**
`decision` temizlenir ve adım onay kapısına geri döner — çünkü parametreler değişecek ve insan
eskilerini onaylamıştı. Onay ekranının eski değerleri göstermemesi için parametreler
`ToolAgent.refreshParams` ile daha o anda yeniden türetilir. Söz şu: **kimsenin görmediği
parametrelerle yazma çalışmaz.**

Çağrı anındaki sıra (`ToolAgent.execute`, sağlayıcı çağrısından **önce**):
şema doğrulama → uydurulmuş anahtar → boş içerik → çözülmemiş yer tutucu → çağrı.
Bu bölüm çekirdek kapıları anlatır; kapanış özetinin kanıt kuralı, digest kusur kapısı,
okunamayan plan, denetçinin "denetlenemedi" yolu ve sır karartma dahil **14 kapılık tam
katalog** [ARCHITECTURE.md §4](ARCHITECTURE.md)'te, her biri kendisini doğuran canlı
olayla birlikte.

---

## 5. Model katmanı

```
RoutingLlmClient — sıralı bir LİSTE, ikinci bir alan değil
   ├─ 1. katman  birincil   (vars. Groq; etiket LLM_PRIMARY_PROVIDER)
   │     ├─ büyük model  (GROQ_MODEL, vars. llama-3.3-70b-versatile) — ApiKeyPool
   │     └─ küçük model  (GROQ_SMALL_MODEL, vars. llama-3.1-8b-instant) — AYRI ApiKeyPool
   ├─ 2. katman  fallback   (app.llm.fallback.*, vars. DeepSeek — günlük tavansız)
   ├─ 3. katman  third      (app.llm.third.*, vars. boş; öneri: Gemini'nin OpenAI-uyumlu ucu)
   └─ StubLlmClient  (çevrimdışı, deterministik, $0)
```

Üç sağlayıcı, çünkü 2026-08-01'de iki sağlayıcı aynı saat içinde düştü: yedi Groq
anahtarı günlük token duvarına çarptı (bir günde 627 bin token), ücretli sağlayıcı aynı
saat HTTP 599 verdi. Her katman aynı istemcidir — `{base}/chat/completions` + bearer
anahtar — yani dördüncü sağlayıcı kod değil ortam değişkenidir; yapılandırılmamış katman
hiç kurulmaz. Katman katman düşerken her katmanın hatası `lastError`'a **eklenir**:
yalnız ilk hata okunsa operatör yanlış sağlayıcıya para yatırırdı. Birincilin etiketi de
yapılandırılır (`LLM_PRIMARY_PROVIDER`, vars. `groq`): birincil başka sağlayıcıya
çevrildiğinde her adım ekranda `groq:deepseek-v4-flash` yazacaktı — var olmayan bir
sağlayıcı, maliyet karşılaştırmasının yanında.

Çıktı-token tavanı amaca göredir: yapı üreten cevaplar (`PLAN`, `DIGEST`, `INSIGHT`,
`ASK_ANSWER`) 3600, tek cümlelik işler 1400 (`LlmRequest.ROOM`/`LONG_ROOM`). Ölçülen
neden: düşünen model çıktı bütçesini yazmadan önce akıl yürütmeye harcar —
`max_tokens=1400`'de digest `finish=length` ile kırpık geldi (1095 token düşünce, 301
yazı), 3600'de geçerli JSON döndü. Tavan harcama değildir; yükseltmenin tek bedeli
doldurmaya karar veren bir model.

`ApiKeyPool` `GROQ_API_KEYS`'teki virgülle ayrılmış anahtarlar üzerinde round-robin yapar.
Ayrım önemli:

- **429 / kota** → `penalize()`: anahtar sağlayıcının `Retry-After`'ı kadar park edilir,
  **en çok 1 saat** (`ApiKeyPool.MAX_PARK`). Eskiden 60 saniyeye kırpılıyordu — günlük
  duvara çarpmış anahtar her dakika sıraya girip sağlam anahtarı da aynı 429'a sokuyordu.
- **401/403 (reddedilmiş anahtar)** → `retire()`: kalıcı. Beklemek iptal edilmiş bir
  anahtarı düzeltmez; soğutmaya alsaydık sağlık her turda yeşile döner, her çağrı yine
  patlardı.
- **402 (bakiye yok)** → emekliye **ayrılmaz**, 1 saat park edilir: bakiye öbür taraftan
  düzelen bir şeydir, emekli anahtar bir sonraki deploy'a kadar ölü kalırdı.
- **400 (şema hatası)** → rotasyon yok, doğrudan hata. Başka anahtar bunu düzeltmez.
- **599** → taşıma katmanının kesinti/IO hatası için sentezlediği kod; ≥ 500 gibi
  rotasyona girer — ağ hatası anahtarın suçu değil ama o an cevap da değil.

Soğuma **model başına** tutulur (`keys` ve `smallKeys` ayrı havuzlar), çünkü Groq her modeli
ayrı sınırlar — kota da **kuruluş başına** sayılır, anahtar başına değil: aynı hesabın beş
anahtarı tek bütçeyi paylaşır. Büyük model tükendiğinde aynı anahtar küçük modelde hâlâ
cevap verebilir; ayrım iki yönlü çalışır (küçük tükenirse VERIFY büyüğe çıkar). İkisi de
tükendiyse `LlmUnavailableException` → sıradaki katman → en sonda `StubLlmClient`.

`degraded` = "**bir sonraki çağrı** birincil sağlayıcıya gitmeyecek". İki kaynağı var: kalıcı
hata (`hardFailure`) veya kullanılabilir anahtar sayısının sıfır olması. Bir 429 serisi eskiden
bunu `true`'ya kilitliyordu ve soğuma bittikten sonra bile sağlık kırmızı kalıyordu; artık
serbest kalan bir anahtar yeterli. `degraded` arayüzde açıkça gösterilir ve iki yerde davranış
değiştirir: `Summarizer` ve `DigestService` hiç çalışmaz (şablon metin üretmektense sessiz
kalırlar).

---

## 6. Araçlar ve replay modu

`application/port/Tool.java` tek genişleme noktası: `name()`, `description()`, `schema()`,
`risk()`, `execute()`, `withDefaults()`. Yeni entegrasyon = bu arayüzü uygulayan bir
`@Component`; Spring toplar, `ToolRegistryImpl` LLM'e sunar, `PolicyEngine` riske göre
varsayılan politikayı atar. Orkestratör değişmez. Şu an **21 araç** kayıtlı (`GET /api/health/details`
→ `tools.count`): `jira.*` (7), `gmail.*` (4), `calendar.*` (3), `github.*` (3),
`slack.*` (2), `sheets.appendRow`, `notion.createPage`. Risk dağılımı: 12 `read`,
9 `write`, **0 `destructive`** — §10'daki sınır bu sayıdan geliyor. Yazma araçlarının
üçü aynı gün girdi ve üçü de bilerek brief'e **eklenmedi**: brief'teki bir READ aracı her
tazelemede iki model turu öder, bir WRITE aracı yalnız kullanıldığı koşuda ~100 token —
bu ürün bir günde 627 bin token harcayıp günlük duvara çarptı ve harcayan okuma
araçlarıydı. `sheets.appendRow` yalnız `values.append/INSERT_ROWS` ucuna gider (üzerine
yazamaz, okuyamaz) ve hücreleri `RAW` yazar — model yazımı `=` ile başlayan hücre
`USER_ENTERED`'da canlı formül olurdu. `calendar.createEvent` davetleri gerçekten yollar
(`sendUpdates=all` — Google'ın varsayılanı davetlileri sessizce yazar) ve katılımcı
yalnız adrestir: "Deniz Arslan"dan `deniz.arslan@` türetmek bir yabancıya postalanmış
tahmindir, adım onun yerine kapının önünde düşer.

`AbstractTool.execute` ortak zincir: süre ölçümü → **şema kapısı** (parametreler geçersizse
sağlayıcıya hiç gidilmez, `mode: rejected`) → live/replay kararı → hata eşleme (istisna mesajı
sınıf adı + mesaja indirgenir, token log'a sızmasın).

Replay kararı iki koşullu:

```
replay = (TOOLS_MODE == replay)  VEYA  bağlantı yok / kullanılamaz
mode   = "replay"  |  "replay (no connection)"  |  "live"
```

`TOOLS_MODE` varsayılanı `replay`'dir. `FixtureStore` `src/main/resources/fixtures/<araç>.json`
dosyasını okur ve içindeki `{{param}}` yer tutucularını **gerçek çağrı parametreleriyle**
JSON değeri olarak doldurur — böylece kayıtlı cevap ajanın sorduğu şeyi yankılar. Sonuç:
hiçbir hesap kurulmadan tüm akış uçtan uca çalışır (demo sigortası ve testlerin varsayılanı).
Sağlayıcının bağlantısı yoksa aynı fixture döner ama `mode` bunu söyler ve `BriefService`
bunu `unavailable` sayar — demo verisi gerçek veri gibi sunulmaz.

---

## 7. Kimlik ve oturum

`AuthFilter` `/api/**` altındaki her şeyi çerezle korur. Muaf olanlar: tam olarak `/api/health`,
`/api/auth/**`, `/api/oauth/google/callback`. `/api/health/details` (operatör görünümü) muaf
değildir — kimliksiz sağlık ucu yalnız `{status, version}` döner, sağlayıcı ve anahtar
sayısı oturum ister. Oturumsuz istek **401 + JSON** alır, HTML
yönlendirme değil — SPA'nın `fetch`'i giriş sayfasını okuyamaz, okursa "parse error" gibi
görünür.

Oturum: 32 baytlık rastgele opak token çereze yazılır (`relay_session`, HttpOnly · Secure ·
SameSite=Lax · 30 gün), veritabanında yalnızca SHA-256'sı durur. İmzalı çerez yerine bunun
seçilme nedeni **iptal edilebilirlik**: çıkışta satır silinir, token o an ölür. Ayrıca çereze
taşınması zorunlu, çünkü `EventSource` özel başlık gönderemez — SSE ucu yalnızca çerezle
çalışır. Parola BCrypt; giriş hatası "yok" ile "yanlış parola" için **aynı cümleyi** döner.
E-posta `Locale.ROOT` ile küçültülür (Türkçe locale'de `"I".toLowerCase()` = `"ı"` olurdu ve
`Ihsan@` ile `ihsan@` iki ayrı hesaba dönerdi).

Google iki ayrı onaydır: `/api/auth/google/*` yalnızca kimliktir (`openid email profile`,
hiçbir token saklanmaz); Gmail/Takvim erişimi `/api/oauth/google/*` altındaki ayrı akıştır ve
refresh token'ı şifreli `google` bağlantısında tutar. Giriş yapmak posta kutusunu vermek değildir.

**Açıkça yazıyorum: kullanıcı başına veri izolasyonu YOK.** Relay tek bir ortak çalışma
alanıdır. `runs`, `connections`, `tool_policies` tablolarında bilinçli olarak `user_id`
yoktur (`V2__auth.sql` başlığında da yazılı). Giriş yapan herkes aynı bağlantıları, aynı
koşuları, aynı politikaları görür; `users` yalnızca kimin klavyede olduğunu söyler.
`/api/brief` önbelleği de tek bir global `AtomicReference`'tır. Çok kiracılılık şema
değişikliği gerektiren ayrı bir iştir.

---

## 8. Veri modeli ve iz kaydı

`V1__init.sql` + `V2__auth.sql` (Flyway, API açılışında migrate eder):

| Tablo | Ne tutar |
|---|---|
| `runs` | hedef, durum, `cost_tokens`, `cost_usd`, `budget_usd`, `budget_overridden` |
| `steps` | sıra, başlık, rol, `tool_name`, `params` (jsonb), durum, `decision`, `reject_reason`, `result` (jsonb), `error`, token, maliyet, `attempts` |
| `agent_messages` | `from_agent` → `to_agent`, içerik, zaman — zaman çizelgesinin kaynağı |
| `connections` | sağlayıcı + **AES-GCM ile şifrelenmiş** config (`AesGcmCipher`, anahtar `APP_ENCRYPTION_KEY`) |
| `tool_policies` | araç başına `auto/ask/forbidden` operatör kararı |
| `users` / `sessions` | hesap ve SHA-256'lı oturum token'ı |

Maliyet: `CostMeter.record` her LLM çağrısını **iki yere** yazar — adıma ve akış toplamına.
Fiyat `GROQ_PRICE_INPUT` / `GROQ_PRICE_OUTPUT` üzerinden token sayısından hesaplanır, altı
haneye yuvarlanır. Toplam `budgetUsd`'yi geçerse koordinatör bir sonraki adımda durur ve
sorar. Her adımdan sonra `run.cost` olayı yayınlanır. Bütçesiz akış mümkün (`budgetUsd = null`
→ sonsuz).

Token'lar hiçbir yerde log'lanmaz; API yanıtlarında maskelenir (`ConnectionService.MASK_MARKER`)
ve maskeli değer geri gönderilirse **kaydedilmez** — form yeniden gönderildiğinde gerçek token
yıldızlarla ezilmez.

SSE (`SseEventPublisher`) run başına 400 olayı hafızada tutar; sonradan bağlanan veya
yeniden bağlanan istemci hikâyeyi baştan alır. 20 saniyede bir heartbeat gider.

---

## 9. Deploy

Docker + Coolify + n11'in **paylaşılan Caddy kenarı**. Ayrıntılı runbook `deploy/DEPLOY.md`,
Caddy bloğu `deploy/Caddyfile.snippet`.

| Bileşen | Konteyner | Host | Yol |
|---|---|---|---|
| `web` (nginx + SPA) | 80 | `0.0.0.0:8086` | `https://APP_DOMAIN/` |
| `api` (Spring Boot) | 8080 | `0.0.0.0:8087` | `https://APP_DOMAIN/api/*` |
| `db` (postgres 16) | 5432 | yayımlanmaz | — |

Kutudaki tek 80/443 sahibi başka bir projenin Caddy'si. Bu yüzden: Coolify proxy **kapalı**,
Coolify'da FQDN alanı **boş**, `SERVICE_FQDN_*` tanımlanmaz. Portlar `127.0.0.1`'e değil
**tüm arayüzlere** yayımlanır — Caddy konteyner içinde ve host'a `host.docker.internal` ile
ulaşıyor, loopback oradan görünmez (sonuç 502 olurdu). Kabul edilen ödünç: portlar
`http://IP:8086` üzerinden TLS'siz de erişilebilir. SSE yolu Caddy'de **ayrı `handle` bloğu**
ve `flush_interval -1` ile geçer, `encode` yok — sıkıştırma frame'leri tamponlar ve arayüz
donmuş görünür.

Önemli ortam değişkenleri (`.env.example`): `GROQ_API_KEYS`, `GROQ_MODEL`, `GROQ_SMALL_MODEL`,
`TOOLS_MODE`, `APP_ENCRYPTION_KEY`, `AUTH_ENABLED`, `AUTH_COOKIE_SECURE`, `DEFAULT_BUDGET_USD`,
`BRIEF_TOOL_TIMEOUT_SECONDS`, `BRIEF_CACHE_SECONDS`, `BRIEF_TIMEZONE`, `CORS_ALLOWED_ORIGINS`,
`GOOGLE_CLIENT_ID/SECRET/REDIRECT_URI`.

---

## 10. Bilinen sınırlar

Jüri buradan sorar; hazırlıklı olmak lazım.

- **Çok kiracılılık yok.** §7'de yazıldığı gibi tek ortak çalışma alanı. Bir kullanıcı
  diğerinin akışlarını, bağlantılarını ve token'larını görür.
- **Ödeme yok**, **zamanlanmış çalıştırma yok.** `@Scheduled` bir iş yok; "her sabah bunu
  yap" (PRD F-22) yapılmadı. Playbook'lar hazır *şablonlar*, otomatik tetikleyici değil.
- **Tek örnek varsayımı.** SSE emitter'ları, brief önbelleği, `ApiKeyPool` soğumaları ve
  koordinatör kilitleri **süreç içi hafızada**. İki kopya çalıştırırsanız akış olayları
  yanlış kopyaya düşer ve kilitler işe yaramaz. Yatay ölçekleme için Redis/pub-sub gerekir.
- **Kuyruk yok.** Akışlar 4 iş parçacıklı sabit havuzda koşar. Beşinci eşzamanlı akış
  sıradakinin bitmesini bekler, kullanıcıya bunu söyleyen bir şey yok.
- **Yeniden başlatma akışı kurtarmaz — ve `running` kalanı yanlış raporlar.**
  `awaiting_approval`'da bekleyen akış sorun değil: duruş veritabanında durur, `approve`
  geldiğinde kaldığı yerden devam eder (`RestartRecoveryTest`). Yarıda kalmış `running` akış
  ise ne sürülüyor ne de bulunabiliyor: `backend/src/main` içinde tek bir açılış kancası yok
  (`@PostConstruct`/`ApplicationRunner`/`@Scheduled` yok) ve `RunRepository` sonlanmamış
  akışları soramıyor — `findAll(page, size)` sayfalı ve durum filtresiz. Sonuç: Geçmiş ekranı
  o akışı sonsuza kadar "çalışıyor" gösterir, iz kaydında yeniden başlatmayı söyleyen bir
  satır yoktur, tek çıkış `POST /api/runs/{id}/cancel`'dır ve kullanıcıya bunu yapması
  gerektiğini söyleyen bir arayüz yoktur. Açılışta `running` akışları otomatik `drive` etmek
  **bilinçli olarak elendi**: yarıda kalmış bir araç çağrısının sağlayıcıda gerçekleşip
  gerçekleşmediği bilinmiyor, yeniden sürmek aynı yazmayı ikinci kez yapabilir —
  `Coordinator.cancel` uçuştaki çağrıyı aynı gerekçeyle kesmiyor. Doğru olan en küçük adım
  kurtarma değil **dürüst rapor**: açılışta bu akışları gerekçesiyle `failed` yazmak (issue
  #45).
- **Doğrulayıcı LLM'dir.** Parse edilemeyen yargı adımı **geçirir** — akışın
  kilitlenmemesi için bilinçli — ama artık bunu söyleyerek geçirir: iz kaydında
  "denetlenemedi … adım geçti, doğrulanmadı" yazar, "doğrulandı" değil. Denetim bir
  garanti değil, en iyi çabadır; en azından sessizliği onay diye satmaz.
- **Koruma kapıları desen eşlemesidir.** `Filler.MARKERS` ve `Placeholder.MARKERS` sabit
  listeler; yeni bir şablon kalıbı elle eklenene kadar geçer.
- **Yazma araçları dar.** Gmail *gönderme* yok (yalnız taslak: `gmail.createDraft`),
  Jira silme yok, hiçbir araçta üzerine-yazma/silme yok (PRD'de bilinçli kapsam dışı);
  takvim yazma, tabloya satır ekleme ve Notion sayfası bugün eklendi ve üçü de yalnız
  *ekleyen* uçlara gider. `DESTRUCTIVE` riskli tek bir araç bile kayıtlı değil — yani mod üründe hiç
  çalışmıyor. Modun kendisi test tarafında kayıtlı bir araçla (`TestDoubles.DestructiveTool`)
  yürütülüyor: varsayılan `forbidden`, adım reddediliyor, iz kaydına "YASAK" satırı düşüyor,
  araç hiç çağrılmıyor ve override `auto`'ya çekemiyor (`PolicyEngineTest`,
  `DestructiveStepTest`).
- **`/api/ask` en fazla üç kaynağa bakar.** `SourceRouter` soruyu kayıtlı READ araçlarına
  yönlendirir (Gmail, Jira, GitHub, Takvim), ama tek turda en çok üç arama yapar ve
  hangisinin seçildiğine karar veren tur, sorgunun yazıldığı turla aynıdır — ayrı bir
  yönlendirme çağrısı yok, çünkü dakikalık token bütçesi en kıt kaynak.
- **Onay kapısı adım bazlıdır**, akış bazlı değil: dört yazma adımı olan bir akış dört kez
  sorar. "Hepsini onayla" yok.
- **Bugün önbelleği global ve 180 saniyelik** (`BRIEF_CACHE_SECONDS`, varsayılan 180 sn — üç
  dakika). Her önbellek ıskası iki model turu demek, süre bu yüzden uzun. TTL'i geçen
  özet artık isteği bekletmez: bayat hâli `stale: true` ile anında döner, yenisi arkada
  kurulur (§3). Bağlantı değiştirdikten sonra ekran yine de bir tur eski kalabilir;
  `POST /api/brief/refresh` bunu atlar.
  Aynı anda gelen ikinci `refresh` **yeni üretim başlatmaz**, sürene katılır (tek uçuş): iki
  yanıt aynı `generatedAt`'i taşır ve tur bir kez ödenir.
