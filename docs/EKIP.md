# Relay — Ajan Ekibi

> Bu bir vizyon metni değil, **sınırları çizilmiş bir tasarım**. Kod okunarak yazıldı; her iddia
> `application/orchestrator/**`, `domain/AgentRole.java`, `application/policy/PolicyEngine.java`,
> `infrastructure/llm/**` ve `frontend/src/lib/agents.ts` üzerinden doğrulanabilir.
> İlgili: [Konumlandırma](KONUMLANDIRMA.md) · [Nasıl çalışıyor](NASIL-CALISIYOR.md) ·
> [Sıradaki fikirler](SIRADAKI-FIKIRLER.md) · [Çalışma düzeni](KATKI.md)

**Karar özeti.** "Ajan ekibi" bir isim listesi değil, **yetki listesi** olarak inşa edilir. Ekip
üyeleri birbirinden **unvanla değil**, üç ölçülebilir şeyle ayrılır: *hangi araca ne yetkiyle
dokunabildiği, hangi model kademesinde düşündüğü, ne kadar harcayabildiği.* Üye elle yazılmaz,
**bağlı araçtan türer**. Ve ekip üyeleri birbiriyle konuşur — ama bu konuşmanın ürünü sohbet
değil, **üç sonuçtan biri**: adım geri döner, insanın okuduğu onay kartına bir itiraz satırı
eklenir, ya da hiçbir şey olmaz ve sessizlik kayda geçer.

---

## 1. Önce dürüst kısım: "ajan ekibi" tek başına bir iddia değil

Bu alandaki en kalabalık cümle bu. Her ürün bir ekip vaat ediyor; çoğu, isimlendirilmiş
persona'lardan ibaret. Ve unvanlı persona, **arkasında sistem yoksa kostümdür**: bağlı bir
muhasebe sistemi yokken "muhasebe ajanı" demek, bir LLM'e muhasebeci gibi konuşmasını
söylemektir. Jüri de müşteri de bunu ilk soruda ayırır: *"peki bu ajan neye erişiyor?"*

Relay'in savunulabilir farkı ekip değil, **yönetişim**: yetki, iz kaydı, maliyet
([KONUMLANDIRMA](KONUMLANDIRMA.md) §4). O yüzden "ekip" iddiası ancak yönetişimin üzerine
oturursa taşır:

> **Bir ekip üyesi, bir isim değil; bir yetki + bir model kademesi + bir bütçedir.**

Bu cümlenin güzelliği, ürünün zaten böyle çalışıyor olması. Bugün kodda:

- Uzman, **araçtan türetiliyor**: `AgentRole.toolAgent("jira.updateIssue")` → `jira-agent`
  (`domain/AgentRole.java:17`).
- Modelin yazdığı rol adı **kabul edilmiyor**: `Planner.crewName` modelin `"role": "assistant"`
  yanıtını atıyor, rolü araçtan hesaplıyor (#104'ün düzeltmesi, `Planner.java:86`).
- Yetki **araç başına** kayıtlı: `PolicyEngine.evaluate(toolName)` → `auto | ask | forbidden`.

Yani ekip zaten bir kayıt defterinden doğuyor. Yapılacak iş, ekibi **uydurmak** değil,
**görünür kılmak** ve üyeler arasındaki farkı ölçülebilir hale getirmek.

---

## 2. Bir üye nasıl var olur — yazılmaz, türer

Ekip iki katmandan oluşuyor ve ikisinin doğuş biçimi farklı.

### 2.1 Sabit çekirdek (6 üye, koda gömülü)

`domain/AgentRole.java` içindeki altı ad; `Planner.CREW` bunları beyaz liste olarak tutuyor:

| Üye | Ne yapar | Nerede |
|---|---|---|
| `user` — **Sen** | Hedefi verir, onaylar, reddeder, düzeltir | — |
| `planner` — **Planlayıcı** | Hedef → ≤ 8 adım, yalnız kayıtlı araçlarla | `Planner.java` |
| `coordinator` — **Koordinatör** | Döngüyü yürütür, kapıda durur, planı onarır | `Coordinator.java` |
| `verifier` — **Doğrulayıcı** | Sonucu hedefe karşı yargılar, ≤ 2 kez geri gönderir | `Verifier.java` |
| `policy` — **Politika** | Yasak adımı reddeder, gerekçesini iz kaydına yazar | `PolicyEngine.java` |
| `cost` — **Maliyet** | Bütçe aşımında akışı durdurup insana sorar | `CostMeter.java` |

Bu altısı **çoğalmaz**. Yeni entegrasyon eklemek yeni bir koordinatör gerektirmez.

### 2.2 Türeyen uzmanlar (araç kayıt defterinden)

Uzman sayısı = bağlı sağlayıcı sayısı. Bugün `jira-agent`, `slack-agent`, `gmail-agent`,
`github-agent`, `calendar-agent` ve araçsız adımlar için `generalist-agent`. Kaynak zinciri:

```
Tool @Component  →  ToolRegistryImpl  →  Planner (araç listesi prompt'a girer)
                                      →  AgentRole.toolAgent(toolName)  →  "<sağlayıcı>-agent"
                                      →  PolicyEngine (risk → varsayılan yetki)
```

**Sonuç:** Notion aracı yazıldığı gün, kimse "Notion uzmanı" diye bir sınıf yazmadan
`notion-agent` doğar; yetkisi `NotionTool.risk()`'ten, adı `Tool.provider()`'dan gelir.
Arayüz de buna hazırlanmış: `frontend/src/lib/agents.ts` tanımadığı bir id'yi **çevirmez, olduğu
gibi basar** — "bir gün yeni bir ajan eklenecek ve `notion-agent` yazan ekran doğruyu söyler,
Türkçe bir kelime uyduran ekran söylemez" (dosyanın kendi yorumu).

**Dürüst sınır — sunumda bu haliyle söylenir.** Yeni bir uzmanın doğması için gereken şey bir
`Tool` sınıfı; ama **bağlantı yüzeyi otomatik gelmiyor**: yeni sağlayıcı = `Connection`
sağlayıcısı + `ConnectionsScreen` kartı + muhtemelen yeni OAuth akışı
([KONUMLANDIRMA](KONUMLANDIRMA.md) §3 C3). Yani doğru cümle *"araç bağlayınca uzman kendiliğinden
doğar"*, yanlış cümle *"herhangi bir SaaS'ı beş dakikada bağlarsınız"*.

---

## 3. Üyeleri ayıran üç şey

Persona yerine bunlar. Üçü de ölçülebilir, üçü de ekranda gösterilebilir.

### 3.1 Yetki — adının yanına yazılmaz, elindeki araçlardan hesaplanır

Bugün politika **araç başına** tutuluyor (`tool_policies`), ajan başına değil. Bu bir eksik
değil, doğru tasarım: yetkiyi ajana yazmak, aynı yetkinin iki yerde tanımlanması demek olurdu.
Bir üyenin yetkisi, elindeki araçların etkin politikalarının birleşimidir:

```
gmail-agent  = gmail.listToday(auto) + gmail.getMessage(auto) + gmail.search(auto)
             + gmail.createDraft(ask)
             → "üç şeyi kendiliğinden okur, tek yazma işini sana sorar"
```

Bu hesap `PolicyEngine.effectivePolicies()` ile zaten tek çağrıda çıkıyor. Eksik olan tek şey,
**sağlayıcıya göre gruplanmış** olarak sunulması — yani "ekip" ekranı (§8, E-1).

Değişmez kural: **hiçbir ajan kendi yetkisini değiştiremez.** `PUT /api/policies` bir insan
ucudur; ajanın bu uca erişimi yoktur ve olmayacaktır (§7).

### 3.2 Model kademesi — bu eksen **az önce gerçek oldu**

Bu doküman yazılırken küçük model bir *kaza planı*ydı: `GroqLlmClient` büyük modeli dener, hız
sınırına takılırsa küçüğe düşerdi; kademe bir rol tercihi değildi. Paralel çalışan bir ajan bunu
aynı gün değiştirdi ve **kademe artık işin cinsinden türüyor** (`LlmPurpose.DEFAULT_SMALL`,
`app.llm.small-purposes`, `GroqLlmClient.complete` içindeki `smallFirst`, `V5` migration'ı ile
adım başına `model` + `premium_cost_usd`).

Yürürlükteki dağılım:

| Rol / amaç | Kademe | Gerekçe |
|---|---|---|
| `PLAN` (Planlayıcı) | **büyük** | Tek çağrı, bütün akışı belirler |
| `TOOL_PARAMS` (Uzman) | **büyük** | Sağlayıcıya giden değer burada üretiliyor |
| `INSIGHT` / `DIGEST` / `ASK_ANSWER` | **büyük** | Kullanıcıya "doğru" diye sunulan cümleler |
| `VERIFY` (Doğrulayıcı) | **küçük** | İkili yargı + kısa gerekçe; şemayla kısıtlı |
| `SUMMARIZE` (Özetleyici) | **küçük** | Kapanış cümlesi; başarısız olması akışı bozmaz |
| `ASK_ROUTE` | **küçük** | Tek sağlayıcı sorgusu |
| `CHALLENGE` (İkinci görüş, §4) | **küçük** — *eklenecek* | Var olan bir şeye itiraz; ≤ 300 token |

Kuralın en iyi tarafı, adı konmamış amacın **pahalı** sayılması: `DEFAULT_SMALL` dışındaki her
şey güçlü modele gidiyor. Yani yarın eklenen bir rol, kimse ayar yapmadığı için ucuz ve kötü
cevap vermiyor.

Ekip tasarımı açısından sonuç: **bu eksen için yeni iş yok, yalnızca `CHALLENGE` amacının
`small-purposes` listesine eklenmesi var** — ve o, §4'teki issue'nun içinde. §8'deki E-3 satırı
bu yüzden kapatıldı.

### 3.3 Bütçe — bugün akış başına, üye başına kör

`CostMeter.budgetExceeded(run)` yalnız akış toplamına bakıyor. Adımın rolü ve maliyeti **zaten
kayıtlı** (`steps.role`, `steps.tokens`, `steps.cost_usd`), `JpaPanelStatsRepository` de zaten
`group by s.tool_name` yapıyor — yani "bu akışın parasını kim harcadı" sorusu **tek bir SQL
cümlesi** uzakta (E-4).

Dürüst kayıt: doğrulayıcının token'ları, doğruladığı **adımın** üzerine yazılıyor
(`Coordinator.runStep` → `costMeter.record(run, step, verdict.tokens(), …)`). Yani rol kırılımı
bugün "adımı yürüten uzman + onu doğrulayan" toplamıdır. Ekran bunu **böyle yazmalı**; ayrıştırmak
maliyet kaydına rol kolonu eklemek demek ve o ayrı bir iş. Yarım doğru bir sayı, yanlış sayıdan
beterdir.

### 3.4 Ekip kartı — üç şeyin tek satırda görünmesi

```
Jira Uzmanı            7 araç · 4 okuma otomatik · 3 yazma onaylı · 0 yasak
                       büyük model · son 7 günde $0.031 · 12 adım
Slack Uzmanı           2 araç · 1 okuma otomatik · 1 yazma onaylı
                       büyük model · son 7 günde $0.008 · 5 adım
Notion Uzmanı          — bağlantı yok, araçları kayıtlı değil
```

Bu ekranın tamamı **türetilmiş**: hiçbir satırı elle yazılmıyor, hiçbiri bir persona metni değil.
"Ajan ekibi" iddiasının kanıtı bu ekran; slayt değil.

---

## 4. Aralarındaki konuşma ne işe yarar

### 4.1 Bugün var olan: kayıt, karar değil

`AgentJournal.say(run, stepId, from, to, content)` her cümleyi hem `Run`'a yazıyor hem
`agent.message` olayı olarak yayınlıyor; Sohbet ekranı bunu sol sütunda basıyor. Yani
**"kim kime ne dedi" zaten var**. Bugün akan tipik trafik:

```
Sen        → Planlayıcı    : hedef
Planlayıcı → Koordinatör   : 4 adımlık plan hazır …
Koordinatör→ Jira Uzmanı   : Adım 2 sende: …
Jira Uzmanı→ Doğrulayıcı   : jira.searchIssues tamam (312 ms) …
Doğrulayıcı→ Koordinatör   : Adım 2 doğrulandı: …
Politika   → Koordinatör   : YASAK — …
Maliyet    → Sen           : Adım 3 — bütçe aşıldı …
```

Bunların hepsi **bildirim**. Eksik olan tek şey: **itiraz**. Bugün hiçbir ajan başka bir ajanın
işine "bu yanlış" diyemiyor. Doğrulayıcı yalnızca *iş bittikten sonra* konuşuyor — yani en
kritik anda, yani **yazma sağlayıcıya gitmeden önce** ekipte ikinci bir göz yok.

### 4.2 Tasarlanan: İkinci Görüş (Challenger)

**Nerede duruyor:** yalnız `ask` modundaki (yazma) bir adımda, parametreler üretildikten sonra,
onay kartı insana gösterilmeden **önce**. Yani `Coordinator.walk` içinde, `refreshParams` ile
`park` arasındaki boşlukta — `unpresentable` kapısıyla aynı yerde.

**Ne soruyor:** tek bir soru, küçük modele, şema kısıtlı:

> Bu adım *hedefi* ve *önceki adımların bulgularını* doğru aktarıyor mu? Yanlışsa tek cümleyle
> söyle. `{"objection": true|false, "reason": "…"}`

**Üç meşru sonucu var, dördüncüsü yok:**

| Sonuç | Ne olur | Sınır |
|---|---|---|
| **İtiraz yok** | Kart insana olduğu gibi gider; iz kaydına *"İkinci görüş: itiraz yok"* düşer | — |
| **İtiraz var → karta yazılır** | Onay kartında `Slack Uzmanı'na itiraz: bu mesaj 3. adımda bulunan sayıyı yanlış aktarıyor` satırı görünür. **Kararı yine insan verir** | Metni değiştirmez, adımı iptal etmez |
| **İtiraz var → yeniden yazım** | Adım uzmanına geri döner (`step.sendBack()` + `lastProviderError`), parametreler yeniden üretilir, kapıya döner | `Step.MAX_RETRIES` (2). Zaten var olan `rewriteBeforeAsking` yolu |

**Dördüncü sonuç yok:** ikinci görüş **onaylayamaz**, **çalıştıramaz**, **politikayı
değiştiremez**, **parametreyi kendisi düzenleyemez** ve **kendi itirazına cevap alamaz**.
Münazara turu yoktur: bir soru, bir cevap, biter.

**Neden bu, "ajanlar tartışsın"dan daha iyi:** çünkü çıktısı bir sohbet değil, **insanın
okuduğu satırın değişmesi**. Ürünün tek cümlesi *"her yazma adımı sana sorar"*; ikinci görüş o
soruyu **daha bilgili** hale getiriyor. Süsleme değil, kararın girdisi.

### 4.3 Konuşmanın nerede durduğu — sayılarla

Bu liste tasarımın en önemli kısmı. Sınırsız bir müzakere mekanizması, bu ürünün bütçesini de
demosunu da yakar.

1. **Adım başına en fazla bir ikinci görüş.** Deneme başına değil, adım başına. Kontrol
   `AgentJournal` üzerinden türetilir (o `stepId` için challenger'dan bir mesaj var mı) — yeni
   kolon, yeni migration yok.
2. **Yalnız yazma adımlarında.** Okuma adımına itiraz etmenin bedeli var, karşılığı yok.
3. **Yalnız küçük model, ≤ 300 token, şema kısıtlı.** Serbest metin yok.
4. **`degraded` iken hiç çalışmaz.** `DigestService` ve `Summarizer` ile aynı kural: model
   sağlıksızken şablon üretmektense susmak.
5. **Bütçe aşıldıysa hiç çalışmaz.** Maliyet kapısı zaten `park` etmiş durumda; ikinci görüş
   bütçe kapısının *arkasına* geçemez.
6. **Karar verilmişse hiç çalışmaz.** `step.decision() != null` ise ikinci görüş atlanır — §5.4.
7. **Yanıt ayrıştırılamazsa itiraz yok sayılır ama sessizlik kayda geçer:** *"İkinci görüş
   alınamadı"*. Doğrulayıcının "parse edilemeyen yargı = geçti" kuralının aynısı, ama bu kez
   sessizlik **görünür** — çünkü sessizliği onay gibi göstermek §5.2'deki tuzağın ta kendisi.

---

## 5. Başarısızlık biçimleri

### 5.1 Sonsuz müzakere

**Nasıl olur:** A ajanı B'ye itiraz eder, B cevap verir, A yeniden itiraz eder. LLM'ler
uzlaşmaya *programlı değildir*; iki modelin birbirini ikna etmesi için hiçbir sonlanma garantisi
yoktur.

**Bu tasarımda neden olamaz:** konuşma **tek yönlü ve tek turlu**. İkinci görüş cevap alamaz.
Geri gönderme sayısı zaten `Step.MAX_RETRIES = 2` ile sınırlı ve bu tavan yeni bir mekanizmaya
değil, var olan sayaca bağlanıyor. Yeni bir sayaç eklemek yeni bir sonsuzluk kaynağı olurdu.

### 5.2 Hemfikirlik tiyatrosu

**Nasıl olur:** İkinci göz her seferinde "katılıyorum" der. Onay kartında bir satır daha çıkar,
kullanıcı iki hafta içinde o satırı okumayı bırakır, ürün bir güvence *hissi* satmış olur —
gerçek bir güvence değil. Bu, onay kapısının kendisinden daha tehlikelidir: kapı boş bir kapıya
dönüşür.

**Kanıtlanmış risk, uydurma değil:** `Verifier` bugün ayrıştıramadığı yargıyı **geçti** sayıyor
(`Verifier.java:53`, bilinçli karar) — yani üründe zaten "sessizliği onay saymak" örneği var ve
belgelenmiş durumda ([NASIL-CALISIYOR](NASIL-CALISIYOR.md) §10).

**Karşı önlem — ölçülebilir olması:**
- İtiraz oranı **panelde sayılır**. `objection: true` oranı iki hafta boyunca ~%0 ise özellik
  değer üretmiyordur ve **kapatılır**. Ölçmeden savunulmaz.
- Sessizlik ("görüş alınamadı") **itiraz yokluğundan ayrı** sayılır.
- İkinci görüş **hiçbir zaman "onaylandı" gibi görünmez**: kartta yeşil bir rozet çıkmaz. Var
  olan tek görsel çıktısı bir **itiraz satırı**dır; itiraz yoksa kart bugünkü haliyle kalır.
  Böylece özellik, kullanıcıyı rahatlatmak için değil, yalnızca uyarmak için ekranda görünür.

### 5.3 Maliyet patlaması

**Nasıl olur:** her ajan her adımda konuşursa, N adımlık bir akış N² mesaja gider. Groq günlük
kotası bu ürünün en kırılgan kaynağı ([NASIL-CALISIYOR](NASIL-CALISIYOR.md) §5) ve demo günü
tükenmiş bir kota, ekranda "stub" demek.

**Karşı önlem:** ikinci görüş **yalnız yazma adımlarında, küçük modelde, adım başına bir kez**.
Tipik bir akışta 1–2 yazma adımı var → akış başına +1–2 küçük çağrı. Aynı işte E-3 (rol başına
kademe) doğrulayıcı ve özetleyiciyi küçük modele indiriyor → **net token kullanımı düşer.**
İkinci görüş, kendi bedelini ödemeden gelmez.

### 5.4 İnsan onayladıktan sonra değişen plan — **bu gerçek bir hataydı (#93)**

**Ne olmuştu:** `Coordinator.insertLookupBefore` uydurulmuş kayıt anahtarını adımdan siliyor,
plana bir arama adımı ekliyor ve yazma adımını geri gönderiyordu — ama `step.decision()` hâlâ
`APPROVED` kalıyordu. Yani insan *"KAN-42'yi kapat"* diye onayladıysa, onarımdan sonra adım
**bambaşka bir kayıt anahtarıyla, yeniden sorulmadan** çalışıyordu. Bugün (1 Ağustos) kapatıldı:
onarımda da karar temizleniyor ve adım kapıya geri dönüyor.

**Neden bu bölümün en önemli maddesi bu:** ekip mekanizmaları tam olarak bu hatayı üretmeye
meyillidir. "Ajanlar aralarında konuşup planı iyileştirsin" cümlesinin doğal sonucu, **insanın
okuduğu şey ile çalışan şeyin ayrışmasıdır.** Ürünün tek sözü bunun tersi:

> **Kimsenin görmediği parametrelerle yazma çalışmaz.**

Bugün bu sözü iki yol koruyor (`retryWithProviderFeedback` ve `insertLookupBefore`), ama koruyan
şey **her yolun kendi içinde hatırlaması**. Üçüncü bir yol (ikinci görüş) eklenmeden önce bu,
tek bir değişmezliğe bağlanmalı: *bir adım `APPROVED` iken parametreleri değişiyorsa, karar
temizlenmek zorundadır.* Bu, mekanizma sayısından bağımsız tek bir testtir (E-6) ve ikinci
görüşten **önce** yazılmalıdır.

### 5.5 Kostüm kayması (persona drift)

**Nasıl olur:** Bir gün birisi "Muhasebe Ajanı" ekler; arkasında araç yoktur, prompt'ta bir
cümledir. Ekip ekranı o günden sonra yalan söylemeye başlar.

**Karşı önlem — yapısal:** ekip listesi **türetilir**, elle yazılamaz. `GET /api/crew`'in kaynağı
`ToolRegistry`'dir; bir üyenin var olabilmesi için en az bir kayıtlı aracı olmak zorundadır.
Persona eklemenin tek yolu araç eklemektir — yani kostüm giymek için önce gerçekten çalışmak
gerekir.

---

## 6. "Ajanlar issue açsın" — ne, nereye, hangi politikayla

Bu, founder yönünün en somut parçası ve **yapılabilir** — ama kayıt açmak bir **yazma**dır, yani
kapıda durur. Üç seviyeye ayırıyorum; ikisini öneriyorum, birini reddediyorum.

### 6.1 Seviye 1 — Kullanıcının kendi takipçisine takip kaydı (**öneriliyor**, E-5)

**Tetikleyici (deterministik, model kararı değil):** bir akış **istenen işi yapmadan** kapandı.
Somut olarak `RunStatus.FAILED`, ya da bir yazma adımı politika/insan tarafından reddedildi
(`Coordinator` bu durumları zaten ayırt ediyor — #94/#95).

**Ne açılır:** tek bir kayıt. `jira.createIssue` — **zaten kayıtlı, zaten `write`, zaten `ask`.**

**Gövdesi nereden gelir:** **modelden değil, iz kaydından.** Şablon deterministik:

```
Başlık : <akışın hedefi, 120 karaktere kırpılmış>
Gövde  : Relay akışı tamamlanamadı.
         Hedef      : …
         Duran adım : 3 · slack.postMessage
         Gerekçe    : politika izin vermiyor: yazma riski varsayılanı: onay ister
         Maliyet    : 2.140 token · $0.0031
         Akış       : /#/history/<runId>
```

Bunun bir model çağrısı olmaması tasarımın kendisidir: `Filler` (şablon metin) kapısını
**yapısı gereği** geçer, halüsinasyon üretemez, ve bedeli sıfırdır.

**Hedef nereden gelir:** `projectKey` **bağlantı ayarlarından** (`ToolAgent.settings` beyaz
listesi zaten `projectKey`'i taşıyor). Modelden asla. Jira bağlantısı yoksa öneri hiç
üretilmez. Bu, `ungroundedIdentifier` kuralının aynısı: kap alanı bağlantıdan, kimlik alanı
hiçbir yerden.

**Hangi kapıdan geçer — ve burası kritik:** kayıt **kendiliğinden açılmaz**. Biten akışın
altında bir **öneri** belirir: *"Bu akış kapanmadı — takip kaydı açılsın mı?"*. Tıklanınca
`POST /api/runs/from-suggestion` normal akışı başlatır ve `jira.createIssue` **yine onay
kapısında durur.** Yani iki kapı: insan öneriyi başlatır, insan yazmayı onaylar. Kural
değişmiyor: **öneri ≠ eylem** ([NASIL-CALISIYOR](NASIL-CALISIYOR.md) §3).

Biten bir akışa adım **eklenmez** — o, §5.4'ün tam olarak yasakladığı şeydir.

**Sınırlar:** akış başına en fazla bir öneri; aynı hedef için 24 saat içinde ikinci öneri
üretilmez (tekrar eden başarısızlık kayıt spam'ine dönmesin); öneri kalıcı değildir, biten
akışın görünümünden türetilir (yeni tablo yok).

### 6.2 Seviye 2 — GitHub tarafı (**sonraya**, E-5'in notu)

`github.createIssue` **bugün yok**; kayıtlı GitHub araçları `listMyPullRequests`,
`listMyIssues`, `addComment`. Yeni bir `Tool` sınıfı (~60 satır, `AbstractTool` şablonu) ve
`repo` alanı bağlantıdan gelir. Yapılabilir ama demo öncesi gerekmiyor: aynı hikâyeyi Jira
tarafı zaten anlatıyor. Demo sonrası.

### 6.3 Seviye 3 — Ajanın **Relay'in kendi deposuna** issue açması (**hayır**)

Cazip: "ajanlar kendi ürünlerini geliştiriyor" demoda alkış alır. Reddediliyor, üç gerekçeyle:

1. **Hedef kullanıcının deposu değil, bizim depomuz.** Kullanıcının kimlik bilgisiyle bizim
   depomuza yazmak, ürünün tüm yetki modelinin dışına çıkar.
2. **İz kaydının anlamı bozulur.** Bugün her yazma, kullanıcının bir hedefinin sonucudur.
   "Ürünü geliştirmek için" açılan bir kayıt hiçbir hedefe bağlı değildir.
3. **Bunun bir adım ötesi §7'deki yasak:** ajanın kendi davranışını değiştirmesi.

---

## 7. Yapmayacaklarımız — ve nedeni

Bu bölüm, tasarımın geri kalanı kadar önemli.

**7.1 Unvanlı persona'lar ("Muhasebe Ajanı", "Analist", "Asistan Ayşe").**
Bağlı sistem yokken unvan kostümdür (§1). Ayrıca `Planner.crewName` bugün modelin yazdığı rol
adını **bilerek atıyor** (#104); persona eklemek o düzeltmeyi geri almak olur. Ekip adı araçtan
gelir, prompt'tan değil.

**7.2 Serbest ajan-ajan sohbeti / münazara turu.**
Sonlanma garantisi yok, bütçe garantisi yok, ve çıktısı ekrana bir şey eklemiyor. §4.3'teki
tek-tur sınırı bilinçli. "Ajanlar 3 tur tartışsın" demo için değil, kota tükenmesi için bir
reçetedir.

**7.3 Ajanın politikayı / kendi yetkisini değiştirmesi.**
`SIRADAKI-FIKIRLER` §4.4'ün cümlesi burada da geçerli: *"kendi izin seviyesini kendi yazan bir
araç karşısında `PolicyEngine` bir tiyatro dekorudur."* `PUT /api/policies` insan ucudur.

**7.4 Ajanların birbirini onaylaması (ikinci ajan "onaylayıcı" olsun).**
Ürünün tek cümlesi bu değil. İkinci bir ajanın onayı, insanın onayının yerine geçemez; geçerse
"onay verilebilir otonomi" iddiası biter. İkinci görüş **yalnız itiraz edebilir**, onaylayamaz —
asimetri kasıtlı.

**7.5 Ajan başına ayrı bir yetki tablosu (RBAC benzeri `agent_permissions`).**
Yetki zaten araç başına kayıtlı. İkinci bir yerde tanımlamak, iki kaynağın çelişmesi demektir ve
çeliştiği gün hangisinin kazandığını kimse bilemez. Üyenin yetkisi **hesaplanır**, saklanmaz.

**7.6 Reddi bir müzakereye çevirmek.**
Zaten karara bağlandı: `SIRADAKI-FIKIRLER` §4.5 (#42). Red **kesindir** ve kesinlik kapının en
güçlü özelliğidir. İkinci görüş bu kararı **ihlal etmiyor**: insan "hayır" dedikten sonra hiçbir
ajan konuşmuyor; ikinci görüş yalnız insan konuşmadan *önce* çalışıyor.

**7.7 Ajanın kendi aracını yazması.** `SIRADAKI-FIKIRLER` §4.4, aynen geçerli.

---

## 8. Sıra ve issue'lar

Sıra rastgele değil: **önce görünürlük** (risksiz, demoyu doğrudan besler), **sonra değişmezlik**
(yeni mekanizmadan önce kural), **sonra itiraz** (asıl özellik), sonra ölçüm ve yazma.

| # | Issue | İş | Boy | Demo günü riski |
|---|---|---|---|---|
| 1 | [#113](https://github.com/SamedBilginAlternet/relay/issues/113) | `GET /api/crew` + **Ekip** ekranı — üyeler kayıt defterinden türer | S/M (4–5 sa) | **Düşük.** Salt okunur, orkestratöre dokunmuyor. En kötü ihtimalle ekran açılmaz, akış etkilenmez |
| 2 | [#114](https://github.com/SamedBilginAlternet/relay/issues/114) | Onay değişmezliği: parametresi değişen `APPROVED` adım kapıya döner + tek test | S (2 sa) | **Düşük.** Var olan iki yolu tek kurala bağlar; #93'ün nöbetçisi |
| — | ~~[#115](https://github.com/SamedBilginAlternet/relay/issues/115)~~ | Rol başına model kademesi — **paralel bir ajan aynı gün yaptı**, issue kapatıldı (§3.2) | — | — |
| 3 | [#116](https://github.com/SamedBilginAlternet/relay/issues/116) | **İkinci görüş**: yazma kapısından önce tek turluk itiraz | M (5–6 sa) | **Orta-yüksek.** Demonun kalbindeki ana dokunuyor; bayrakla kapatılabilir olmalı |
| 4 | [#117](https://github.com/SamedBilginAlternet/relay/issues/117) | Rol başına harcama kırılımı (akış + panel) | S (2–3 sa) | **Düşük.** Tek SQL + görünüm |
| 5 | [#118](https://github.com/SamedBilginAlternet/relay/issues/118) | Kapanmayan akış için **takip kaydı önerisi** (ajan issue açar) | M (4–5 sa) | **Orta.** Yazma; iki kapıdan geçiyor, gövde deterministik |

**Demoya kadar yalnız bir tanesi yapılacaksa: #113.** Gerekçe §9'da.

Bağımlılık: #116 (ikinci görüş) **#114'ten sonra** yapılır — yeni bir mekanizma, koruduğu kural
yazılmadan eklenmez (§5.4). #116'nın maliyetini karşılayan kademe işi ise zaten yapıldı (§3.2);
geriye yalnız `CHALLENGE` amacını `app.llm.small-purposes` listesine eklemek kalıyor.

---

## 9. Demoda kurulacak ve kurulmayacak cümleler

**KURULUR:**
- *"Ekip listesi bizim yazdığımız bir liste değil — bağladığınız araçlardan çıkıyor. Notion
  bağlarsanız Notion uzmanı ekipte belirir."*
- *"Üyeler isimle değil yetkiyle ayrılıyor: Gmail uzmanı üç şeyi okur, tek yazma işini size
  sorar. Bu satır bir tanıtım metni değil, politika motorundan hesaplanıyor."*
- *"Ajanlar birbirine bilgi veriyor, ve bir tanesi itiraz edebiliyor — ama itiraz eden ajan
  onaylayamıyor. Onay her zaman sizde."*
- *"Bir akış kapanmadıysa Relay kendi kendine kayıt açmıyor; kayıt açmayı **öneriyor**, siz
  başlatıyorsunuz, ve kaydın kendisi yine onayınızda duruyor."*

**KURULMAZ:**
- ❌ *"Ajanlarımız kendi aralarında tartışıp karar veriyor"* — tek tur, tek yön, ve son karar
  insanda. Abartıldığı an ilk soruda kırılır.
- ❌ *"Muhasebe ajanı, satış ajanı…"* — bağlı sistem yok (§7.1).
- ❌ *"Ajanlar kendi işlerini kendileri açıp kapatıyor"* — açmıyor, öneriyor.
- ❌ *"Ekip kendi kendini yönetiyor / kendi yetkisini ayarlıyor"* — yasak (§7.3).
