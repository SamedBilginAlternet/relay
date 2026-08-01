# Relay — Sıradaki Fikirler

> Bu bir dilek listesi değil, **karar dokümanı**. Her fikir koda bağlı: hangi dosyada ne
> değişir, kaç saat, demoya ne katar, nerede tuzağı var. Sonunda tek bir öneri var ve
> savunuluyor.
>
> Ürünün tezi sabit: **yürütme + görünür yönetişim.** Tezle çelişen fikirler de yazıldı —
> ama "tez ihlali" diye işaretlendi. Ürün, tezini ihlal ederek büyümez.

Okuma sırası: §1 (sınır) → §5 (tek öneri) → gerekirse §2 (tüm fikirler).

---

## 1. Ürünün şu anki sınırı

Kod okumasından çıkan somut boşluklar. Her biri "kullanıcı şunu yapmak istiyor, yapamıyor"
şeklinde yazıldı; hiçbiri tahmin değil.

### 1.1 Yönetişim yarısı ekranda yok

`api/PolicyController.java` `GET`/`PUT /api/policies` sunuyor, `PolicyEngine.effectivePolicies()`
her araç için risk + mod + "override edilmiş mi" bilgisini veriyor. **Frontend'de bu ucu tüketen
tek satır yok** (`frontend/src/` içinde `policy` yalnızca `types/api.ts` tipinde ve mock
yorumunda geçiyor). Yani:

- Kullanıcı hangi aracın otomatik, hangisinin onaylı, hangisinin yasak olduğunu göremiyor.
- `DEMO.md` §5 madde 3 "politikaları kontrol et" diyor — kontrol edecek ekran yok, veritabanına
  bakmak gerekiyor. **Altın kural, doğrulanamayan bir kural.**
- Ürünün iki iddiasından biri (yönetişim) API'de doğru, arayüzde görünmez.

### 1.2 Onay kapısı ikili: onayla ya da reddet

`RunController.approve` gövde almıyor, `StepRow.tsx` parametreleri `ParamBlock` ile yalnızca
**gösteriyor**. Kullanıcı ekranda `channel: "#random"` görüyor ve yapabileceği tek şey reddedip
gerekçe yazmak. Gerekçe `Coordinator.retryWithProviderFeedback()` üzerinden modele dönüyor,
`ToolAgent.refreshParams()` bir model çağrısı daha harcıyor ve **doğru geleceğinin garantisi yok**.
Üç saniyelik bir düzeltme, bir tur model çağrısına ve şansa bağlı.

### 1.3 Bekleyen onay sessiz

Akış arka planda koşuyor (`RunService.start` → `orchestratorExecutor`), olaylar yalnızca açık
sekmedeki `EventSource`'a akıyor. `EventPublisher` portunun tek uygulaması
`infrastructure/sse/SseEventPublisher`. Sekmeyi kapatan kullanıcı `awaiting_approval`'da asılı
kalan akıştan **hiçbir şekilde haberdar olmuyor**. Onay kapısı ürünün kalbi; kapıyı çalan bir zil yok.

### 1.4 Akışı durdurmanın yolu yok

`RunStatus.CANCELLED` tanımlı (`domain/RunStatus.java:10`) ve `terminal()` içinde sayılıyor —
ama **hiçbir kod bu durumu set etmiyor**, `RunController`'da iptal ucu yok. Yanlış giden 6 adımlık
bir akışı durdurmak için her adımı tek tek reddetmek gerekiyor.

### 1.5 Hiçbir şey kendi kendine çalışmıyor

`@Scheduled` kullanımı yok (tek eşleşme `SseEventPublisher`'ın heartbeat'i). `Playbooks.MORNING`
adı "Günün özeti" ama sabah kendiliğinden koşmuyor; kullanıcı uygulamayı açmadıkça Relay uyanmıyor.
`BriefService` de yalnız istek geldiğinde çalışıyor (60 sn cache).

### 1.6 Hazır akışlar tek kullanımlık bir yere gömülü

`PlaybookService` + `PlaybookController` tam, dört playbook yazılı, "runnable/missing" hesabı
bağlantılara göre yapılıyor. Ama `getPlaybookSource()` frontend'de **yalnız `OnboardingScreen.tsx`**
içinde çağrılıyor. Turu bir kez bitiren kullanıcı hazır akışlara bir daha ulaşamıyor. Backend hazır,
giriş noktası yok.

### 1.7 Sistem hiçbir şey öğrenmiyor

`steps.reject_reason` veritabanında duruyor (`V1__init.sql`) ama **hiçbir sorgu geri okumuyor**.
`Planner` ve `ToolAgent` prompt'ları yalnızca o koşunun içeriğini görüyor. Dün "KAN-3'e dokunma"
diyen kullanıcı, bugün aynı işi verdiğinde aynı adımı yine reddetmek zorunda. Red, bir kere
işe yarayıp çöpe gidiyor.

### 1.8 Maliyet koşu başına; hesap düzeyinde kör

`CostMeter.budgetExceeded(run)` yalnız tek koşuya bakıyor. `runs.cost_usd` toplamını alan tek bir
sorgu yok. "Bu hafta Relay bana kaça mal oldu" sorusunun cevabı üründe yok — jürinin maliyet
sorusuna verilen cevap koşu düzeyinde doğru, hesap düzeyinde boş.

### 1.9 Denetim izi "kim onayladı" demiyor

`AgentJournal.say(run, stepId, AgentRole.USER, ...)` — onaylayan **"USER"**. Oturumda kimin
olduğunu `AuthFilter` biliyor, `users` tablosu var, ama karar satırında kimlik yok. "Yarın müdürün
kim, neyi, neden değiştirdi derse cevap bir tık uzakta" cümlesi (DEMO §1, 2:40) bugün **"neyi" ve
"neden"i** karşılıyor, **"kim"i** karşılamıyor.

### 1.10 Yazma yüzeyi dar, okuma yüzeyi geniş

15 kayıtlı aracın yalnız 5'i WRITE: `jira.updateIssue`, `jira.addComment`, `jira.createIssue`,
`slack.postMessage`, `github.addComment`. Google tarafı **salt okunur** scope'la bağlı
(`GoogleOAuth.SCOPES:42-43` → `gmail.readonly`, `calendar.readonly`). Slack'te thread cevabı yok
(PRD F-06 "thread'e cevap ver" diyor, `SlackTool`'da karşılığı yok). Relay bugün mükemmel okuyor,
az yazıyor — ve ürünün vaadi yazmak.

### 1.11 "Sor" yalnızca posta kutusuna soruyor

`AskService.SEARCH_TOOL = "gmail.search"` sabit. "KAN-42 nerede kalmıştı", "bu PR'ı kim bekletiyor"
soruları cevapsız — oysa aynı veri `jira.*` ve `github.*` araçlarında hazır duruyor.

### 1.12 Sağlayıcı başına tek bağlantı, çalışma alanı tek

`connections.provider ... unique` (`V1__init.sql`) → iki Jira sitesi, iki Slack workspace mümkün
değil. Ve `V2__auth.sql`'in kendi yorumu net: runs/connections/policies **global**, `user_id` yok.
Bu bilinçli bir karar; ama ekip kullanımının önündeki duvar da bu.

---

## 2. Fikirler

Her fikir: **ne · kimin hangi anını kurtarır · mimaride nereye oturur · kaba maliyet · demoya
katkı · risk.**

---

### F-1 · Politika ekranı (`#/politikalar`)

- **Ne:** `GET /api/policies` sonucunu sağlayıcıya göre gruplanmış bir tabloya döken ekran; her
  satırda araç adı, risk rozeti (OKUMA/YAZMA/SİLME), üç durumlu seçici (otomatik · onay iste ·
  yasak) ve "varsayılan mı, elle değiştirilmiş mi" işareti.
- **Kimin anını kurtarır:** Demoyu veren kişinin, sahneye çıkmadan 30 saniye önce "Slack yazması
  gerçekten onaylı mı" diye emin olma anını. Sonra: bir ekip lideri, ajanın neye izinli olduğunu
  ilk kez tek ekranda görür.
- **Nereye oturur:** Yalnız frontend. `frontend/src/screens/PolicyScreen.tsx` +
  `frontend/src/data/PolicySource.ts` (`ApiRunSource.ts` deseni), `lib/router.ts`'e route,
  `AppHeader.tsx`'e sekme. Backend'de **tek satır değişiklik yok** — `PolicyController` ve
  `PolicyEngine.set()` hazır.
- **Maliyet:** 3–4 saat.
- **Demoya katkı:** **Yüksek.** Kapanışta "işi yapan sistem, neye izinli olduğunu da gösteriyor"
  diye 10 saniyelik bir sekme; jürinin yönetişim sorusuna ekran cevabı olur.
- **Risk:** Ekranın kendisi altın kuralı kırabilir — yanlış tıkla `slack.postMessage` otomatiğe
  düşerse demo çöker. Panzehir: WRITE/DESTRUCTIVE bir aracı `otomatik`e çekerken ikinci bir onay
  (`ConfirmDialog`) ve satırda kalıcı uyarı.

---

### F-2 · Onayla-ve-düzelt: parametreyi ekranda değiştirerek onaylama

- **Ne:** Onay kapısındaki adımın parametreleri salt okunur JSON olmaktan çıkıp düzenlenebilir
  alanlar olur. Kullanıcı `channel`'ı `#random`'dan `#genel`'e çevirir, **Onayla**'ya basar; giden
  değer onun gördüğü değerdir. Değişiklik iz kaydına ayrı bir satır olarak düşer.
- **Kimin anını kurtarır:** Onay kapısındaki herkesin en sık yaşadığı anı: "neredeyse doğru".
  Bugün bunun bedeli bir red + bir gerekçe + bir model turu; sonrası şans.
- **Nereye oturur:**
  - `api/RunController.approve` → `@RequestBody(required=false) ApproveRequest(Map params)`.
  - `application/orchestrator/RunService.approve(runId, stepId, params)`: `SchemaValidator.validate`
    (zaten var, `application/json/SchemaValidator.java`) → `step.params(params)` → `journal.say(...)`
    "kullanıcı parametreleri değiştirdi: channel #random → #genel".
  - `domain/Step`'e `paramsLocked` bayrağı + `V3` migration. **Kritik detay:**
    `ToolAgent.finaliseParams` (satır 378–395) şema-geçerli bir draft'ı modele hiç sormadan aynen
    kullanıyor — yani düzenlenen parametre bugün de olduğu gibi gider. Tek tuzak `lastProviderError`
    dolu olan adım: orada model draft'ı yeniden üretiyor ve insanın düzenlemesini ezer. `paramsLocked`
    tam olarak bunu engellemek için.
  - Frontend: `components/StepRow.tsx` + `components/ParamBlock.tsx` düzenlenebilir hale gelir,
    `data/RunSource.ts` `approveStep(runId, stepId, params?)`.
- **Maliyet:** 5–6 saat (backend 2, frontend 3, test 1).
- **Demoya katkı:** **Yüksek.** Demonun kalbi olan 1:05–1:45 aralığını ikiye böler: önce düzelt-onayla
  (hızlı, kesin), sonra reddet-gerekçe (ajanla pazarlık). İki farklı yönetişim jesti, tek sahnede.
- **Risk:** Şema doğrulaması atlanırsa onay kapısı bir enjeksiyon yüzeyine döner — düzenlenen
  parametre de `SchemaValidator`'dan **ve** `ToolAgent`'ın grounding/placeholder/filler kapılarından
  geçmeli. Bir de kavram riski: "düzenleyebiliyorsam neden model üretsin" — cevap, düzenleme istisna
  olmalı, alan varsayılan olarak dolu gelmeli.

---

### F-3 · Denetim izinde kimlik: "kim onayladı"

- **Ne:** Her onay/red kararı, kararı veren kullanıcıyla birlikte kaydedilir. Adım satırında
  "Onaylandı · Samed Bilgin · 14:32", geçmişte de aynısı.
- **Kimin anını kurtarır:** Üç ay sonra "bu Slack mesajını kim onaylattı" diye soran kişinin anını.
  Ve satış anını: kurumsal alıcının ilk sorusu budur.
- **Nereye oturur:** `V3` migration → `steps.decided_by_user_id`, `agent_messages.actor_user_id`.
  `infrastructure/auth/AuthFilter` oturumdaki kullanıcıyı zaten çözüyor; `RunService.approve/reject`
  imzasına `User actor` eklenir, `AgentJournal.say`'e aktarılır, `Views.step`'e `decidedBy` alanı
  düşer, `StepRow`/`RunDetailScreen` gösterir.
- **Maliyet:** 3–4 saat.
- **Demoya katkı:** **Orta-yüksek.** Kapanıştaki denetim izi cümlesini tamamlar; şu an eksik olan
  kelime tam olarak "kim".
- **Risk:** Düşük. Tek çalışma alanı kararını bozmaz — kullanıcı veriyi bölmüyor, sadece kararı
  imzalıyor. Yalnızca `AgentRole.USER` sabitine bağlı testler güncellenir.

---

### F-4 · Bekleyen onayı Slack'ten sor, Slack'ten cevapla

- **Ne:** Bir adım onaya düştüğünde Relay, ilgili kişiye Slack'ten mesaj atar: hedef, araç,
  parametre önizlemesi ve iki buton — **Onayla** / **Reddet**. Cevap Slack'ten gelir, akış devam eder.
- **Kimin anını kurtarır:** Sekmeyi kapatıp toplantıya giren kullanıcının anını (§1.3). Relay'in
  "arka planda çalışan" olma iddiası ancak böyle gerçek olur; bugün uygulama açık değilse Relay yok.
- **Nereye oturur:**
  - `EventPublisher` portunun ikinci uygulaması: `infrastructure/notify/SlackNotifyingPublisher`,
    `STEP_AWAITING` olaylarını dinler, `SlackTool.PostMessage`'ı Block Kit ile çağırır.
  - `ApplicationConfig`'de iki publisher'ı birleştiren `CompositeEventPublisher`.
  - Yeni uç: `api/SlackInteractionController` → `POST /api/slack/interactions` →
    `RunService.approve/reject`. `infrastructure/auth/AuthFilter`'da muaf yol + **Slack imza
    doğrulaması** (`x-slack-signature`, `signing secret`).
- **Maliyet:** 8–12 saat (imza doğrulama ve Slack app ayarları maliyetin yarısı).
- **Demoya katkı:** **Yüksek ama kırılgan.** "Telefonumdan onayladım, akış devam etti" tek karede
  ürünü satar; ama sahnede public URL + Slack interactivity + ağ zinciri demektir.
- **Risk:** **Güvenlik riski birinci sınıf.** İmza doğrulanmazsa uca gelen herkes onay verebilir —
  bu, ürünün tezini tersine çevirir. Ayrıca "hangi kullanıcı" sorunu: Slack kullanıcı id'sini
  `users`'a eşlemeden F-3 anlamsızlaşır. Bu yüzden F-3'ten **sonra** yapılmalı.

---

### F-5 · Zamanlanmış playbook (sabah brifingi kendi koşsun)

- **Ne:** Bir playbook'a takvim verilir: "hafta içi 08:30, `gunun-ozeti`". Relay sabah kendi koşar,
  okuma adımları akar, **yazma adımı onayda bekler** ve F-4 üzerinden haber verir.
- **Kimin anını kurtarır:** Güne başlama anını. Bugün Bugün ekranı "sistem işi getiriyor" diyor ama
  getirmesi için kullanıcının gelmesi gerekiyor.
- **Nereye oturur:** `V3` → `schedules(id, playbook_id, cron, enabled, last_run_at)`. Yeni
  `application/schedule/ScheduleService` + `infrastructure/schedule/SchedulerRunner`
  (`@Scheduled(fixedDelay=60s)` tarayıcı) → `PlaybookService.start(id, budget)`. Frontend'de
  playbook rafında (F-6) satır içi "her sabah çalıştır" anahtarı.
- **Maliyet:** 6–8 saat.
- **Demoya katkı:** **Orta.** Sahnede bekleyemezsin; ama Geçmiş'te "bu sabah 08:30'da kimse
  başlatmadan koştu, işte iz kaydı" satırını göstermek — ve yazma adımının hâlâ onayda beklediğini
  göstermek — güçlü bir karedir.
- **Risk:** Tek node varsayımı; iki replica'da aynı iş iki kez koşar. `V3`'te
  `last_run_at` üzerinden koşullu `UPDATE` ile kilit şart. İkinci risk: onaysız biriken akışlar —
  bekleyen onay sayısına tavan koy, aksi halde bir haftalık tatil 5 asılı akış demek.

---

### F-6 · Hazır akış rafı (playbook'lara kalıcı giriş)

- **Ne:** Bugün ekranının altına ve boş Sohbet ekranına dört playbook kartı: başlık, tek satır
  açıklama, "çalıştır". Bağlantısı eksik olan kart soluk ve "Jira bağla" der (`describeAll()` zaten
  `runnable` + `missing` döndürüyor).
- **Kimin anını kurtarır:** İkinci gün açan kullanıcının "ne yazsam" anını — `BRIEF.md`'nin çözmek
  için yazıldığı sorun, tur bittiği anda geri geliyor.
- **Nereye oturur:** Salt frontend: `data/PlaybookSource.ts` (var), yeni
  `components/PlaybookShelf.tsx`, `screens/TodayScreen.tsx` + `components/Landing.tsx`'e yerleşim.
- **Maliyet:** 2 saat.
- **Demoya katkı:** **Orta-yüksek.** Açılışın ilk 12 saniyesinde ekranda "sistem ne yapabilir"in
  cevabı görünür olur; boş kutu korkusunu tamamen bitirir.
- **Risk:** Neredeyse yok. Tek dikkat: raf, Öncelikli kartlarla görsel olarak yarışmamalı — playbook
  ikincil bir şerit, kahraman değil.

---

### F-7 · Reddetme hafızası (öğrenen davranış)

- **Ne:** Kullanıcının reddettiği şeyler kalıcı bir tercih defterine yazılır: hangi araç, hangi
  parametre, hangi gerekçe. Sonraki koşularda planlayıcı ve uzman ajan bu defteri görür; ilgili
  öneri ya hiç üretilmez ya da "geçen sefer reddettin, yine mi" notuyla gelir.
- **Kimin anını kurtarır:** Aynı düzeltmeyi üçüncü kez yapan kullanıcının anını (§1.7). Ürünü
  "her gün sıfırdan tanışılan asistan" olmaktan çıkarır.
- **Nereye oturur:** `V3` → `preferences(id, tool_name, param_path, value, reason, times_seen,
  last_seen_at)`. Yazan taraf: `RunService.reject` → yeni `application/memory/PreferenceService`.
  Okuyan taraf: `Planner.plan()` ve `ToolAgent.finaliseParams()` prompt'una "BU KULLANICININ
  DAHA ÖNCE REDDETTİKLERİ" bloğu; `InsightService.actions()` filtresine ek eleme.
- **Maliyet:** 8–10 saat (yazmak kolay, doğru okumak zor).
- **Demoya katkı:** **Yüksek — eğer iki koşu gösterilebilirse.** "Aynı işi ikinci kez verdim; KAN-3'ü
  bu sefer kendisi çıkardı, çünkü dün reddetmiştim" cümlesi demonun en güçlü ikinci anı olur.
  Tek koşuluk demoda katkısı sıfır.
- **Risk:** **Tezle sürtünme riski.** Görünmez bir kural ürünü kara kutuya çevirir. Zorunlu panzehir:
  hafızanın etkilediği her adım iz kaydında gerekçesini yazsın ("KAN-3 çıkarıldı — 30 Temmuz'daki
  reddin gereği") ve tercih defteri kullanıcı tarafından görülüp silinebilsin. Görünmeyen öğrenme
  bu üründe bir hata değil, ihlaldir.

---

### F-8 · Hesap düzeyinde bütçe ve harcama ekranı

- **Ne:** Koşu bütçesinin üstünde günlük/aylık tavan. Ayrı bir küçük ekran: bugün/bu hafta ne kadar
  harcandı, hangi playbook pahalı, hangi model çağrısı hangi adımda.
- **Kimin anını kurtarır:** Aracı kendi kartıyla çalıştıran kişinin ay sonu anını; ve satın alma
  kararını verecek yöneticinin ilk sorusunu.
- **Nereye oturur:** `application/cost/SpendService` (yeni) → `RunRepository`'ye
  `sumCostSince(Instant)`. `RunService.start` günlük tavan aşıldıysa koşuyu **park ederek** başlatır
  (Coordinator'daki bütçe parkı deseni birebir var: `Coordinator.walk` içinde
  `costMeter.budgetExceeded`). Frontend: `screens/SpendScreen.tsx`, `components/CostBar.tsx` yeniden
  kullanılır.
- **Maliyet:** 4–5 saat.
- **Demoya katkı:** **Orta.** Maliyet şeridi zaten demoda; bu, o hikâyeyi "koşu" ölçeğinden "hesap"
  ölçeğine taşır ve iş modeli sorusuna bağlanır.
- **Risk:** Düşük — ama demo günü günlük tavan dolarsa sahne ölür. Prova ve demo için ayrı tavan,
  ve tavan aşımı **durdurma değil, park + onay** olmalı (mevcut desenle aynı).

---

### F-9 · Gmail taslak cevabı (`gmail.createDraft`)

- **Ne:** Bugün ekranındaki "yanıt bekliyor" kartı tek tıkla **taslak** cevaba dönüşür. Gönderme yok:
  Relay taslağı yazar, kullanıcı Gmail'de okur ve gönderir.
- **Kimin anını kurtarır:** Günde 6 kez "kısa bir cevap yazmam lazım" diyen herkesin anını. Bugün
  Relay maili okuyup sınıflandırıyor ama cevaba tek adım kalırken duruyor.
- **Nereye oturur:** `infrastructure/tools/GmailTool` içine yeni `@Component CreateDraft`
  (`RiskLevel.WRITE`), `GoogleOAuth.SCOPES`'a `gmail.compose`. Öneri tarafı bedava:
  `InsightService`, kayıtlı her non-DESTRUCTIVE aracı modele zaten sunuyor (`actionableTools()`),
  yani araç kaydedildiği an Bugün ekranında öneri olarak belirir.
- **Maliyet:** 4 saat + OAuth'un yeniden onaylanması.
- **Demoya katkı:** **Yüksek.** Bugün ekranının vaadini kapatır: mail geldi → sınıflandı → cevap
  hazır, onayında.
- **Risk:** Scope genişletmek Google onay ekranını ağırlaştırır ve test kullanıcı sınırına takılır
  (`INTEGRATIONS.md` §4). Ürün riski daha önemli: kullanıcı "taslak" ile "gönderildi"yi karıştırırsa
  güven biter — araç adı, etiket ve onay metni **taslak** demeli, hiçbir yerde "gönder" geçmemeli.

---

### F-10 · Akışı iptal et

- **Ne:** `POST /api/runs/{id}/cancel`. Koşan adım biter, sıradaki tüm adımlar `rejected` olur,
  koşu `cancelled` kapanır ve neden iptal edildiği iz kaydına yazılır.
- **Kimin anını kurtarır:** "Yanlış anlamış, hepsini durdurayım" anını. Bugün bu ancak 6 kez
  Reddet'e basarak yapılabiliyor.
- **Nereye oturur:** `RunService.cancel(runId, reason)`; `RunStatus.CANCELLED` ve `terminal()`
  zaten var, `Coordinator.drive` terminal koşuyu zaten atlıyor. Frontend: `WorkflowPanel.tsx`'e
  "Akışı durdur".
- **Maliyet:** 2 saat.
- **Demoya katkı:** **Orta.** "Sistem güçlü ama zincirli" cümlesine ikinci bir kanıt: yalnız adım
  değil, akış da durdurulabiliyor.
- **Risk:** Dürüstlük riski. Başlamış araç çağrısı geri alınamaz; arayüz "başlayan adım tamamlanır,
  sonrakiler iptal edilir" demeli. Yanlış vaat, iptal düğmesini bir yalana çevirir.

---

### F-11 · Prova modu (dry-run)

- **Ne:** Koşuyu "hiçbir şey yazma" kipinde başlat. Plan çıkar, okumalar koşar, yazma adımlarının
  **kesinleşmiş parametreleri** üretilir ve gösterilir — ama sağlayıcıya gitmez. Beğenirsen aynı
  koşuyu gerçek kipte tekrarla.
- **Kimin anını kurtarır:** Relay'e ilk kez üretim Jira'sını bağlayan kişinin anını. Bugün güvenmenin
  tek yolu, gerçek yazmayı onaylamak.
- **Nereye oturur:** `Run`'a `dryRun` bayrağı (`V3`), `PolicyEngine.evaluate` bu koşuda WRITE
  araçlarını "çalıştırma, atla" olarak işaretler; `ToolAgent` parametreleri sonuna kadar üretir ama
  `tool.execute` yerine `skipped` sonucu döner. Frontend: Composer'da bir anahtar, adım satırında
  "prova" rozeti.
- **Maliyet:** 4–5 saat.
- **Demoya katkı:** **Orta-yüksek.** "Önce provasını izleyelim, sonra gerçeğini" demo için de bir
  sigortadır — replay modundan farklı olarak plan ve parametreler gerçek veriden çıkar.
- **Risk:** Kavram kalabalığı. Zaten `live` / `replay` (`ToolsMode`) var; üçüncü bir kip kullanıcı
  kafasını karıştırabilir. Adlandırma tek kelimeyle ayrışmalı: replay = veri sahte, prova = yazma yok.

---

### F-12 · "Sor"u tüm kaynaklara aç

- **Ne:** `/api/ask` yalnız Gmail'e değil, bağlı tüm READ araçlarına sorar. "KAN-42 nerede kalmıştı",
  "bu hafta hangi PR'lar bende" cevaplanır; kaynaklar yine listelenir.
- **Kimin anını kurtarır:** "Nerede kalmıştık" anını — koşu başlatmaya değmeyen, ama dört sekme
  gezmeyi gerektiren soruları.
- **Nereye oturur:** `AskService.SEARCH_TOOL` sabiti kalkar; `MailQueryTranslator` yanına
  `SourceRouter` (soru → hangi READ araçları + parametreleri) gelir; `BriefService`'in paralel fan-out
  deseni (`briefExecutor`, sanal thread'ler) aynen kullanılır.
- **Maliyet:** 6–8 saat.
- **Demoya katkı:** **Orta.** Sohbeti "iş verme" dışında ikinci bir işe sokar; ama demo akışını
  uzatır.
- **Risk:** `AskService`'in en değerli kuralı — **sonuç yoksa cevap yok** — çok kaynakta bulanıklaşır.
  Kural kaynak başına ayrı uygulanmalı; "Jira'da bulamadım ama mailde şunu buldum" demek dürüst,
  ikisini karıştırıp tek paragraf yazmak değil.

---

### F-13 · Ekip akışı görünümü

- **Ne:** "Ekip" sekmesi: kimin hangi işi verdiği, hangi adımın kimde onay beklediği, günün toplam
  yazma sayısı. Tek çalışma alanı kararını (`V2__auth.sql`) gizlemek yerine **özellik** haline getirir.
- **Kimin anını kurtarır:** Takım liderinin "ajanlar bugün ne yaptı" anını; ve nöbetteki kişinin
  "bende bekleyen onay var mı" anını.
- **Nereye oturur:** `GET /api/runs` zaten global — veri hazır. Frontend
  `screens/TeamScreen.tsx` + `Views.runSummary`'ye `awaitingCount` ve (F-3'ten) `startedBy`.
  Gerçek değeri F-3'e bağlı; kimliksiz bir ekip görünümü "birisi bir şey yaptı" listesidir.
- **Maliyet:** 3–4 saat (F-3'ten sonra).
- **Demoya katkı:** **Orta.**
- **Risk:** Bunu "çok kullanıcı desteği" diye sunmak yalan olur — izolasyon yok, herkes her şeyi
  görüyor. Ekranın kendisi bunu açıkça yazmalı: *"Bu bir ortak çalışma alanı; burada herkes her şeyi
  görür."*

---

### F-14 · Mobil: PWA + bekleyen onay bildirimi

- **Ne:** Uygulamayı yüklenebilir hale getirmek, bekleyen onay için cihaz bildirimi.
- **Kimin anını kurtarır:** Masasında olmayan kullanıcının anını — F-4 ile aynı sorun.
- **Nereye oturur:** `frontend/` manifest + service worker; backend'de Web Push için abonelik
  tablosu ve VAPID anahtarları.
- **Maliyet:** 8–10 saat.
- **Demoya katkı:** **Düşük.**
- **Risk:** iOS'ta web push kısıtlı ve kurulum şartlı. **Aynı problemi F-4 (Slack) hem daha ucuza
  hem daha doğru çözüyor** — kullanıcı zaten Slack'te. Bu fikir listede, çünkü akla geliyor; ama
  F-4 varken sırası yok.

---

### F-15 · "Güvendiklerini otomatiğe al" — ⚠️ **TEZ İHLALİ, bilerek yazıldı**

- **Ne:** Üst üste N kez onaylanan bir araç/parametre deseni için Relay'in onay sormayı bırakması
  ("bu Slack kanalına yazmayı 5 kez onayladın, artık sormuyorum").
- **Neden cazip:** Onay yorgunluğu gerçek. Günde 10 kez aynı şeyi onaylayan kullanıcı, kapıyı
  değerli değil sinir bozucu bulmaya başlar.
- **Neden ihlal:** Ürünün tek cümlesi "ne yaptığını satır satır görüyorsun". Sessizce genişleyen
  bir otomatik alan, tam olarak Relay'in n8n'e karşı savunduğu şeyin karşıtıdır (DEMO §3:
  *"bizim iddiamız ajanı durdurabilmek"*). Ayrıca `PolicyEngine` zaten bunu **kullanıcı eliyle**
  yapabiliyor: politikayı `otomatik`e almak bir tık (F-1 ile görünür hale gelince).
- **Yapılabilir hâli:** Sistem otomatiğe **almaz**, sadece **önerir**: "bu aracı 5 kez onayladın,
  politikasını otomatik yapmak ister misin?" → kullanıcı F-1 ekranında karar verir. Kararın sahibi
  insan kalır, ürün öğrenmiş olur.

---

## 3. Üç kademe

### (a) Demoya kadar — 2-3 iş, toplam ~9 saat

| Sıra | İş | Saat | Neden bu |
|---|---|---|---|
| 1 | **F-6 · Hazır akış rafı** | 2 | En ucuz demo kazancı; açılışın ilk 12 saniyesini doldurur, backend'i hiç riske atmaz |
| 2 | **F-1 · Politika ekranı** | 3–4 | Yönetişim iddiasının ekran karşılığı; altın kuralı sahneden doğrulanabilir yapar |
| 3 | **F-10 · İptal** | 2 | İki saatte "durdurabiliyoruz" hikâyesini tamamlar; risk yüzeyi minik |

Bu üçünün ortak özelliği: **hiçbiri orkestratöre dokunmuyor.** Demo öncesi son 24 saatte
`Coordinator`/`ToolAgent` değiştirilmemeli — orası ürünün çalışan kalbi ve regresyonu sahnede görülür.

F-2 (onayla-ve-düzelt) demo değeri en yüksek iş ama 5–6 saat ve `Step`/migration'a dokunuyor.
**Demoya 24 saatten fazla varsa** F-10 yerine F-2 alınmalı; azsa alınmamalı.

### (b) Hackathon sonrası ilk hafta

1. **F-2 · Onayla-ve-düzelt** (5–6 s) — kapıyı ikili olmaktan çıkarır, ürünün en çok kullanılan anı.
2. **F-3 · Denetim izinde kimlik** (3–4 s) — F-4 ve F-13'ün ön koşulu, tek başına da satış argümanı.
3. **F-4 · Slack'ten onay** (8–12 s) — Relay'i "sekme" olmaktan çıkarıp arka plan servisi yapar.
4. **F-8 · Hesap bütçesi** (4–5 s) — ilk gerçek kullanıcıdan önce takılması gereken emniyet.
5. **F-9 · Gmail taslağı** (4 s) — Bugün ekranının kapanmamış vaadi.

Sıra tesadüf değil: F-3 → F-4 zorunlu bağımlılık (kimliksiz uzaktan onay güvenlik açığıdır),
F-8 ise dış kullanıcı gelmeden önce.

### (c) Ürünleşme yolu

- **F-5 · Zamanlama** + **F-7 · Reddetme hafızası**: ikisi birlikte Relay'i "çağrılan araç"tan
  "çalışan meslektaş"a taşır. F-7'nin görünürlük şartı pazarlık konusu değil.
- **Gerçek çok kullanıcılılık**: `runs`/`connections`/`policies`'e `workspace_id`, kullanıcı–çalışma
  alanı üyeliği, rol (izleyici / onaylayıcı / yönetici). **Hepsi birden, tek migration'da** — yarısı
  §4'ün ilk maddesi.
- **Bağlantı çoğullaması**: `connections.provider unique` kısıtı kalkar, bağlantıya ad verilir
  ("Jira · üretim", "Jira · müşteri X"); araç seçiminde bağlantı da parametre olur.
- **Yönetişim ihracı**: denetim izinin CSV/JSON dışa aktarımı, saklama süresi, SSO. Kurumsal
  katmanın ürünü budur (PRD §10 "iş modeli" cevabıyla birebir örtüşür).
- **Entegrasyon derinliği**: her sağlayıcıda 2 aracı 6'ya çıkarmak (Jira geçişleri, Slack thread,
  GitHub review), çünkü "3000+ entegrasyon" iddiasının gerçek testi genişlik değil, bir sağlayıcıda
  işi bitirebilmek.

---

## 4. Yapmamamız gerekenler

### 4.1 Çok kiracılılığı yarım yapmak

**Cazip görünüyor:** `runs` tablosuna bir `user_id` eklemek bir saatlik iş, "artık çok kullanıcılıyız"
denir.

**Neden tuzak:** `V2__auth.sql`'in kendi yorumu bunu zaten yazmış. Yarım izolasyon, izolasyonsuzluktan
**daha tehlikelidir**: kullanıcı koşularını kendine ait sanar, ama `connections` ve `tool_policies`
global kaldığı için başkasının Jira token'ıyla, başkasının politikasıyla çalışır. Kendi verisi sandığı
şey ortaktır ve bunu hiçbir ekran söylemez. Ya hepsi (workspace + üyelik + rol + bağlantı sahipliği,
tek seferde), ya da §2 F-13'teki gibi ortaklığı **açıkça yazan** bir ekip görünümü. Arası yok.

### 4.2 Ödeme / fatura entegrasyonu eklemek

**Cazip görünüyor:** "Ajan faturayı da ödesin" demoda alkış alır ve PRD'de zaten "yol haritası"
diye duruyor.

**Neden tuzak:** Relay'in tüm güvenlik modeli **onay + geri alınabilirlik varsayımı** üzerine kurulu.
Yanlış Slack mesajı silinir, yanlış Jira geçişi geri alınır. Yanlış ödeme geri alınmaz. Bu, yeni bir
`Tool` değil yeni bir sorumluluk sınıfıdır: idempotans anahtarları, mutabakat, PCI yüzeyi, saklama
yükümlülükleri. `RiskLevel`'a dördüncü bir seviye eklemek bunu çözmez. 48 saatlik bir ürünün en hızlı
itibar kaybı yolu budur.

### 4.3 Node/akış editörü eklemek

**Cazip görünüyor:** "Kullanıcı planı sürükle-bırak düzenlesin" her demoda istenir.

**Neden tuzak:** `DEMO.md` §3 bunu zaten "asla deme" listesine koymuş ve haklı. Node editörü,
ürünün tek cümlesinin tersidir: *otomasyon kurmuyorsun, iş veriyorsun.* Editör eklendiği an Relay,
n8n'in 400+ entegrasyonu, zamanlayıcısı ve retry altyapısıyla aynı sahaya çıkar ve kaybeder.
Kullanıcının plana müdahale ihtiyacı gerçek — ama cevabı editör değil, **F-2 (parametreyi düzelt
ve onayla)**: müdahale, tasarım zamanında değil çalışma anında ve tek satırda.

### 4.4 (bonus) Ajanın kendi aracını yazması

"LLM eksik entegrasyonu kendi kodlasın" fikri düzenli olarak gündeme gelir. `Tool` arayüzü
gerçekten beş üyeli ve gerçekten kolay — ama üretilen kodun risk seviyesini, şemasını ve politikasını
yine üreten belirler. `PolicyEngine`, kendi izin seviyesini kendi yazan bir araç karşısında bir
tiyatro dekorudur.

### 4.5 Reddi bir revizyon döngüsüne çevirmek — **karar: hayır** (#42)

**Cazip görünüyor:** Kullanıcı bir yazma adımını gerekçeyle reddediyor, koordinatör gerekçeyi
okuyup adımı revize ediyor, revize adım **tekrar onaya geliyor**. "İptal butonu değil, ajanla
pazarlık." Üç doküman bunu zaten vaat etmişti (`DEMO.md` §1 1:25, `KONUMLANDIRMA.md` §2 09:13
ve §4) ve kod hiç yapmadı. Yapılması da pahalı değil: `Coordinator.rejectStep`, adımı terminal
yapmadan önce `ToolAgent.refreshParams` çağırsın, `decision = null` ile kapıya geri dönsün —
sağlayıcı hatası yolunun (`retryWithProviderFeedback`) birebir aynısı, 3–4 saat.

**Neden yapmıyoruz.**

1. **Aynı anı zaten çözen bir şey var, ve deterministik.** F-2 (onayla-ve-düzelt) canlıda
   çalışıyor: kullanıcı alanı ekranda değiştirir, `Düzelt ve onayla`'ya basar, `paramsLocked`
   sayesinde giden değer gördüğü değerdir. Revizyon döngüsü aynı ihtiyacı **bir model turu,
   bir gecikme ve bir belirsizlikle** çözer — "belki bu sefer doğru gelir". İki mekanizmadan
   biri kesin, diğeri şans. İkisini birden sunmak kullanıcıya "hangisi?" sorusunu sordurur.
2. **Döngünün doğal bir sonu yok.** Red → revize → red → revize. `Step.MAX_RETRIES` bir tavan
   verir, ama o tavana çarptığında kullanıcı ne görecek? Ürünün en hassas anı — insanın hayır
   dediği an — bir "deneme hakkın bitti" mesajıyla kapanır. Bu, kapıyı savunulabilir olmaktan
   çıkarır: bugün red **kesin**, ve kesinlik onay kapısının en güçlü özelliği.
3. **Reddin anlamı bulanıklaşıyor.** Bugün red tek bir şey demek: *bu adım çalışmayacak.*
   Revizyon eklendiğinde red "bunu başka türlü yap" demeye başlar, ve "hiç yapma" demenin
   yolu kalmaz. Üçüncü bir jest (iptal) eklemeden bu ayrım kurulamaz — yani iş 3-4 saat değil,
   yeni bir kavram.
4. **Kapsam.** 48 saatlik bir üründe demonun kalbindeki mekanizmayı sunumdan önce değiştirmek,
   kazanılacak şeyden fazlasını riske atar. Değişen kod `Coordinator`'ın döngüsü — yani her
   akışın geçtiği yer.

**Bunun yerine yapılan:** vaat metinden çıkarıldı. `DEMO.md` §1 1:25 artık gerçekten olan şeyi
gösteriyor (*düzelt ve onayla*), `KONUMLANDIRMA.md` §4'teki "Reddin plana dönmesi" satırı
"Red gerekçesi iz kaydına ve sonraki adımların bağlamına girer" olarak daraltıldı. Gerekçe
kaybolmuyor: `Coordinator.rejectStep` onu adımın kendi ajanına yazıyor ve sonraki adımların
prompt bağlamına giriyor — sadece **o adımı** geri getirmiyor.

**Açık kalan ve gerçekten sorun olan kısım — ayrı iş:** reddedilen bir adımın çıktısına
bağımlı sonraki adım bugün yine onay kapısına geliyor. Canlıda `jira.createIssue` reddedildi,
ardından `slack.postMessage` *"Yeni iş talebi var"* metniyle onay istedi — hiç açılmamış bir
kaydı ekibe duyurmaya hazırdı. Bu revizyon döngüsünden bağımsız bir davranış hatası ve ayrı
bir issue olarak açıldı; çözümü ya bağımlı adımı düşürmek ya da onay kartında "3. adım
reddedildi, bu adım onun sonucunu bekliyordu" uyarısı göstermek.

---

## 5. Tek öneri: **F-2 — Onayla-ve-düzelt**

Bir tanesi seçilecekse bu.

**Gerekçe.** Relay'in tüm hikâyesi tek bir ana bakıyor: adım durur, parametreler ekrandadır, insan
karar verir. Demo bu anı 40 saniye boyunca büyütüyor. Ama bugün o anda kullanıcının verebileceği
cevap sayısı iki: **evet** ve **hayır**. Gerçek hayatta cevapların çoğu üçüncü şık: *"evet ama kanal
yanlış"*.

Bugün o üçüncü şıkkın bedeli şu: reddet → gerekçe yaz → `Coordinator.retryWithProviderFeedback` →
`ToolAgent.refreshParams` bir model çağrısı daha harcar → parametreler yeniden onaya gelir → **belki**
doğrudur. Üç saniyelik bir düzeltme için bir tur gecikme, bir tur token ve bir tur belirsizlik.
Düzenlenebilir tek bir alan bunu deterministik hale getirir.

**Bunu yaparsak fark şu olur:**

1. **Ekrandaki parametreler ilk kez yük taşır.** Değiştiremediğin bir değeri okumazsın; göz gezdirir,
   onayla'ya basarsın. Değiştirebildiğin anda okursun. Onay kapısının asıl işlevi — insanın *gerçekten
   bakması* — ancak böyle çalışır. Bugün kapı var, dikkat yok.
2. **Denetim izi ürünün en kurumsal cümlesini kazanır.** "Samed, `slack.postMessage` adımında
   `channel` alanını `#random` → `#genel` olarak değiştirdi ve onayladı — 14:32." Bu tek satır,
   yönetişim iddiasını bir slayttan bir kayda çevirir. Kimse bunu bir sohbet asistanından çıkaramaz.
3. **Modelin hatası ucuzlar.** Bugün yanlış bir parametre bir turluk kayıp; sonra ucuzlar: insan
   düzeltir, iş devam eder. Bu, LLM tabanlı bir ürünün en pahalı sorununu — "neredeyse doğru" —
   ürün özelliğine çevirir.
4. **Tezle tam hizalı.** Ne otomasyon kurulumu ekliyor, ne kapıyı gevşetiyor. Kapıyı **kullanılabilir**
   yapıyor. Tersine, F-15 gibi fikirler kapıyı zamanla kaldırmayı önerir; bu fikir kapıyı sahiplendirir.

**İşin gerçek boyutu küçük — kodu okudum.** `ToolAgent.finaliseParams` (satır 378–395) şema-geçerli
bir draft'ı **modele hiç sormadan** aynen kullanıyor; yani insanın yazdığı değer bugün de olduğu gibi
sağlayıcıya gider. Yapılacak iş üç parça: (1) `approve` ucunun gövde alması ve
`SchemaValidator`'dan geçirmesi, (2) `Step.paramsLocked` — tek tuzak, `lastProviderError` dolu bir
adımda modelin insanın düzenlemesini ezmesi, (3) `ParamBlock`'un düzenlenebilir hâli. Toplam 5–6 saat.

**Kabul kriteri (bunu ölçelim):** onay kapısındaki bir adımda tek alan değiştirilip onaylandığında,
(a) sağlayıcıya giden değer ekrandaki değerdir, (b) hiçbir ek model çağrısı yapılmaz, (c) iz kaydında
eski ve yeni değeri gösteren bir satır vardır, (d) şemaya uymayan bir düzenleme çalıştırılmaz —
adım onayda kalır ve hata alanın yanında görünür.

---

*İlgili dokümanlar: [PRD](PRD.md) · [Mimari](ARCHITECTURE.md) · [Bugün ekranı](BRIEF.md) ·
[Demo](DEMO.md) · [Entegrasyonlar](INTEGRATIONS.md)*
