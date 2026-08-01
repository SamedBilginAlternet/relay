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
  ToolAgent  ── parametreleri kesinleştir → 6 koruma kapısı → aracı çağır
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
| `application/orchestrator/Planner.java` | Hedef → sıralı adımlar. Şema dışına çıkan yanıt tek bir "düşünme" adımına düşer, akış patlamaz. Uydurulmuş araç adı `toolName = null` yapılır. |
| `Coordinator.java` | Döngüyü yürütür. **Yeniden girilebilir**: insana ihtiyaç duyan ilk adımda durur, `approve`/`reject` geldiğinde tam oradan devam eder. Run başına `ReentrantLock` ile aynı akışın iki kez sürülmesi engellenir. |
| `ToolAgent.java` | Uzman. Adımın aracının şemasına göre parametreleri kesinleştirir (önceki adımların sonuçlarını okuyarak), koruma kapılarından geçirir, çağırır. Araçsız adımı LLM ile yazar. |
| `Verifier.java` | Denetçi. Sonucu hedefe karşı yargılar. `pass:false` ise adım ajanına geri gider (`Step.MAX_RETRIES`). Parse edilemeyen yargı **kabul** sayılır — doğrulayıcı akışı kilitlemez. |
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
onaylar ve koordinatörü yeniden sürer. Bütçe yüzünden durulmuşsa onay ayrıca
`run.budgetOverridden(true)` yapar — o akış için tavanı kaldırır.

Red farklı çalışır. `RunService.reject` adımı `decision=REJECTED`, `rejectReason=<gerekçe>`
yapar ama **`PENDING` bırakır**. Sonlandırmayı koordinatör yapar: `Coordinator.rejectStep`
adımı terminal `rejected` durumuna alır, gerekçeyi `AgentJournal` üzerinden adımın kendi
ajanına yazar ve `step.finished` olayı olarak yayınlar. Yani gerekçe kaybolmaz; iz kaydında
"kim reddetti, neden" satırı kalır ve sonraki adımların prompt'una giren geçmişin parçası olur.

---

## 3. Bugün ekranı

`GET /api/brief` → `application/brief/BriefService.java`. Üç özellik bilinçli:

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

**4 · Uydurulan adresin varsayılana çevrilmesi.** `ToolAgent.groundAddresses` + `Tool.withDefaults`.
Bir akış sırayla `#genel`, `C046F7R6UE9`, `#general` kanallarına yazmayı denedi — üç makul
uydurma, üç `channel_not_found` — bağlantıda `defaultChannel = #all-samed` yazılı dururken.
Kayıt anahtarının aksine adresin güvenli bir cevabı var. `channel`/`channelId` alanı hedefte
veya sonuçlarda geçmiyorsa boşaltılır ve `SlackTool.PostMessage.withDefaults` bağlantıdaki
varsayılanı koyar. Çözüm **onaydan önce** yapılır: onaylanan parametre ile gönderilen
parametre aynı olmalı, onaydan sonra sessizce düzeltilen bir kanal başka bir mesaj olurdu.
Ayrıca `ToolAgent.settings` bağlantıdaki *ayar* alanlarını (`defaultChannel`, `projectKey`,
`baseUrl`, `login`, `repo`) prompt'a koyar — sıkı beyaz liste, token asla prompt'a girmez.

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

Bu altısı `ToolAgent.execute` içinde şu sırayla ve sağlayıcı çağrısından **önce** geçilir:
şema doğrulama → uydurulmuş anahtar → boş içerik → çözülmemiş yer tutucu → çağrı.

---

## 5. Model katmanı

```
RoutingLlmClient
   ├─ GroqLlmClient  (birincil)
   │     ├─ büyük model  (GROQ_MODEL, vars. llama-3.3-70b-versatile) — ApiKeyPool
   │     └─ küçük model  (GROQ_SMALL_MODEL, vars. llama-3.1-8b-instant) — AYRI ApiKeyPool
   └─ StubLlmClient  (çevrimdışı, deterministik)
```

`ApiKeyPool` `GROQ_API_KEYS`'teki virgülle ayrılmış anahtarlar üzerinde round-robin yapar.
Ayrım önemli:

- **429 / kota** → `penalize()`: anahtar soğumaya alınır (varsayılan 60 sn; sağlayıcının
  `Retry-After` değeri varsa o kullanılır ama 60 sn ile **sınırlanır** — düşmanca bir
  `Retry-After` anahtarı saatlerce devre dışı bırakamasın).
- **401/403/402 (reddedilmiş anahtar)** → `retire()`: kalıcı. Beklemek iptal edilmiş bir
  anahtarı düzeltmez; soğutmaya alsaydık `/api/health` her 60 saniyede yeşile döner, her
  çağrı yine patlardı.
- **400 (şema hatası)** → rotasyon yok, doğrudan hata. Başka anahtar bunu düzeltmez.

Soğuma **model başına** tutulur (`keys` ve `smallKeys` ayrı havuzlar), çünkü Groq her modeli
ayrı sınırlar. Büyük model tükendiğinde aynı anahtar küçük modelde hâlâ cevap verebilir — tek
havuz bu kapasiteyi çöpe atardı. Büyük tükendi → küçük denenir → o da tükendiyse
`LlmUnavailableException` → `StubLlmClient`.

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
varsayılan politikayı atar. Orkestratör değişmez. Şu an 15 araç kayıtlı: `jira.*` (6),
`github.*` (3), `gmail.*` (3), `calendar.listToday`, `slack.*` (2).

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

`AuthFilter` `/api/**` altındaki her şeyi çerezle korur. Muaf olanlar: `/api/health`,
`/api/auth/**`, `/api/oauth/google/callback`. Oturumsuz istek **401 + JSON** alır, HTML
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
- **Yeniden başlatma akışı kurtarmaz.** `awaiting_approval`'da bekleyen bir akış API yeniden
  başlayınca kendiliğinden sürülmez; bir `approve`/`reject` çağrısı gerekir. Yarıda kalmış
  `running` akış orada kalır.
- **Doğrulayıcı LLM'dir.** Parse edilemeyen yargı **geçti** sayılır — akışın kilitlenmemesi
  için bilinçli, ama denetimin garantisi değil, en iyi çabası olduğu anlamına gelir.
- **Koruma kapıları desen eşlemesidir.** `Filler.MARKERS` ve `Placeholder.MARKERS` sabit
  listeler; yeni bir şablon kalıbı elle eklenene kadar geçer.
- **Yazma araçları dar.** Gmail gönderme, takvim yazma, Jira silme yok (PRD'de bilinçli
  kapsam dışı). `DESTRUCTIVE` riskli tek bir araç bile kayıtlı değil — politika modu
  test edilmiş ama pratikte kullanılmıyor.
- **`/api/ask` en fazla üç kaynağa bakar.** `SourceRouter` soruyu kayıtlı READ araçlarına
  yönlendirir (Gmail, Jira, GitHub, Takvim), ama tek turda en çok üç arama yapar ve
  hangisinin seçildiğine karar veren tur, sorgunun yazıldığı turla aynıdır — ayrı bir
  yönlendirme çağrısı yok, çünkü dakikalık token bütçesi en kıt kaynak.
- **Onay kapısı adım bazlıdır**, akış bazlı değil: dört yazma adımı olan bir akış dört kez
  sorar. "Hepsini onayla" yok.
- **Bugün önbelleği global ve 60 saniyelik.** Bağlantı değiştirdikten sonra ekran bir dakika
  eski kalabilir; `POST /api/brief/refresh` bunu atlar.
