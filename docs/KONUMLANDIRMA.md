# Relay — Konumlandırma Kararı

> Bu bir seçenek listesi değil, **gerekçeli bir öneri**. Kod okunarak yazıldı; iddialar
> `Playbooks.java`, `infrastructure/tools/**`, `application/policy/**`, `application/brief/**`
> ve `frontend/src/**` üzerinden doğrulandı.
> İlgili: [PRD](PRD.md) · [Mimari](ARCHITECTURE.md) · [Bugün ekranı](BRIEF.md) · [Demo](DEMO.md)

**Karar özeti:** Ürün konumlandırması değişmiyor, **kullanıcının adı** değişiyor. Relay bir
geliştirici aracı değil; **gününü araçlar arasında koordine ederek geçiren beyaz yakalının**
aracı. Bu kişi bugünkü entegrasyon setinde en net şekilde **bir yazılım/ürün ekibinin lideri**
olarak duruyor. "Herkes için beyaz yaka asistanı" demek, elimizdeki araç setinin
kaldıramayacağı bir cümle — ve jüri sorusunda kırılır. "Developer ürünü" demek ise
ürünün ne yaptığını yanlış anlatmak: Relay kod yazmıyor, **kod yazan ekibin gününü** yürütüyor.

---

## 0. Önce envanter — ürün gerçekte ne yapıyor

Konumlandırma tartışmasının tamamı bu tabloya bakarak çözülüyor.

### Kayıtlı araçlar (18) ve risk seviyeleri

> Kaynak: `GET /api/health/details` → `"tools":{"count":18}` ve `GET /api/policies`.
> Tablo canlıdan üretildi; sunumda bu tablodan konuşulur.

| Sağlayıcı | OKUMA (12) | YAZMA (6) | SİLME (0) |
|---|---|---|---|
| **Jira** | `searchIssues` · `getIssue` · `listMyIssues` · `getComments` | `createIssue` · `updateIssue` · `addComment` | yok |
| **Slack** | `listChannels` | `postMessage` | yok |
| **GitHub** | `listMyPullRequests` · `listMyIssues` | `addComment` | yok |
| **Gmail** | `listToday` · `getMessage` · `search` | `createDraft` | yok |
| **Takvim** | `listToday` · `listUpcoming` | yok | yok |

Sağlayıcı sayısı dört, beş değil: Gmail ve Takvim tek bir `google` bağlantısında oturuyor
(`GoogleTool.provider()`).

İki satır §0'ın eski hâline göre değişti ve ikisi de sunumda söylenecek:
**`gmail.createDraft` artık kayıtlı ve `write` riskli** (§3 C1 kararı uygulandı — taslak
yazılıyor, `gmail.send` hâlâ **yok**, ayrım korunuyor), **`calendar.listUpcoming` eklendi**
(Toplantı öncesi hazırlık akışının kaynağı).

Kritik gözlem: **eylem yüzeyi Jira + Slack + GitHub'dan ibaret.** Gmail ve Takvim
salt-okunur. Yani Relay "beyaz yakalının maili" konusunda **taslak yazabiliyor ama
gönderemiyor**, "takvimi" konusunda **görebiliyor ama toplantı kuramıyor**. Bir araca
gerçekten *yazdığı* yer ekip araçları.

### Hazır akışlar (`Playbooks.java` — 5 adet, raftaki sırayla)

> Kaynak: `GET /api/playbooks`. Sıra ekrandaki sıradır (`Playbooks.ALL`).

| # | Akış (ekrandaki adı) | Zinciri | Kim için? |
|---|---|---|---|
| 1 | **Maili işe çevir** | Gmail oku → Jira kaydı aç → Slack'e bildir | Gelen talebi işe çeviren herkes |
| 2 | **Günün özeti** | Jira + GitHub + Takvim oku → Slack'e durum mesajı | Ekibi olan biri |
| 3 | **Toplantı öncesi hazırlık** | Takvim + Jira + Gmail oku → **hiç yazmaz** | Toplantıya giren herkes |
| 4 | **Takılan işler** | `labels = blocker` JQL → Slack özeti | Yazılım ekibi lideri |
| 5 | **Bekleyen incelemeler** | Review bekleyen PR'lar → Slack hatırlatma | Yazılım ekibi lideri |

§0'ın eski hâline göre üç değişiklik: **`toplanti-hazirligi` eklendi** (beşinci akış),
**`Maili işe çevir` başa alındı** (§3 A4), **`Blocker taraması`/`PR durumu` başlıkları
jargondan çıkarıldı** (§3 A3 — `id`'ler sabit kaldı, API kırılmadı).

Beşin dördünün **Slack'te bitmesi** tesadüf değil: `Playbooks.java` sınıf yorumunda yazdığı
gibi her akış "ends by telling someone what changed". Bu bir bilgi işleme aracı değil,
**bir koordinasyon aracı**. Fark önemli — konumlandırmanın çıkış noktası bu. Tek istisna
**Toplantı öncesi hazırlık**: hiçbir şey yazmaz, onay istemez — okunacak bir şey toplar.

### Ekranlar (üst barda 6 sekme)

`Bugün` (`#/`, eylem akışı) · `Sohbet` (`#/sohbet`, serbest hedef + akış paneli) ·
`Geçmiş` (`#/history`, denetim izi) · `Bağlantılar` (`#/connections`) ·
`Politikalar` (`#/politikalar`) · `Panel` (`#/panel`, akış istatistikleri).

Üst barda **olmayan**, ama var olan ekranlar: `#/sor` (Postana sor — hesap menüsünden,
karar #59), `#/onboarding` (tanıtım turu — hesap menüsünden tekrar açılır), `#/giris`,
`#/kayit`.

§0'ın eski hâline göre iki ekran eklendi (**Politikalar**, **Panel**) ve bir ekran üst
bardan çıktı (**Postana sor**). Sunum bu bardan okunur — sekme sayısı yediyken üç
dakikada gezilemiyordu.

### Kodda var, arayüzde bir tık uzakta

`POST /api/ask` — "kargolarım gelmiş mi?", "KAN-4 ne durumda?" gibi soruları **kullanıcının
kendi hesaplarından, kaynak göstererek** yanıtlıyor (`AskService` + `SourceRouter`,
salt-okunur, akış başlatmıyor). Ekranı yazıldı (`#/sor`) ama üst bardan çekildi; gerekçesi
§3 B1'de. Demoda açılmaz.

### Dürüst cevap: bu kimin gününü kurtarıyor?

**Bir yazılım/ürün ekibinin gününü.** Ama dikkat: bir **geliştiricinin** gününü değil.
Geliştirici Jira'yı okumaz, IDE'de yaşar; Copilot ona zaten hizmet ediyor. Relay'in çözdüğü
acı — "dört araç, on beş tıklama, yirmi dakika" — **kodu yazanın değil, kod yazan ekibi
yürütenin** acısı. Ve o kişi tanımı gereği beyaz yakalı.

---

## 1. Tek cümlelik konumlandırma

> **Relay, gün içinde çıkan işi araçların içinde yürüten bir ajan ekibidir — ve hiçbir
> yazma adımını sana sormadan yapmaz.**

**Neden bu cümle:**

1. **"Gün içinde çıkan iş"** — n8n/Zapier'den ayrımı ilk üç kelimede kuruyor. Onlar tasarım
   zamanı, biz çalışma zamanı. Bu ayrım kodla destekli: `Planner` hedefi çalışma anında adıma
   çeviriyor, önceden kurulmuş bir graf yok.
2. **"Araçların içinde yürüten"** — sohbet asistanlarından ayrımı. Metin üretmiyoruz, Jira'da
   kaydı gerçekten güncelliyoruz. **"Bitiren" demiyoruz, ve bu bilinçli:** "bitirmek"
   ölçülebilir bir sözdür ve ürünün kendi paneli onu tutmuyor — son 7 günde 106 akışın 50'si
   tamamlandı (%47), 29'u onay bekliyor, 23'ü hata verdi. "Yürüten" gösterdiğimiz şeyin tam
   karşılığı: iş araca giriyor, adımlar gerçekten koşuyor, ve duran her adımın nerede
   durduğu görünüyor. Ekranıyla çelişen bir cümle, jürinin tıklayacağı ilk sekmede kırılır.
3. **"Sana sormadan yapmaz"** — tek gerçek savunulabilir farkımız ve kodda somut:
   `PolicyEngine` WRITE riskli her aracı varsayılan olarak `ask` moduna alıyor, DESTRUCTIVE
   olanı `forbidden`. Bu bir slayt vaadi değil, çalışan bir kapı.
4. **Rol adı geçmiyor.** "Beyaz yaka", "PM", "developer" demiyoruz. Rol adı söylediğimiz an
   jüri o rolün *tam* iş akışını sorar ve elimizdeki araçlarla eşleşmeyen ilk soruda düşeriz.
   İş tanımı ("araçlar arası koordinasyon") araç setimizle birebir örtüşüyor; rol adı örtüşmüyor.

**PRD §1'deki mevcut cümleden farkı:** PRD "Beyaz yakalı işini sohbette anlatırsın…" diyor.
Bu cümlede iki sorun var: (a) "beyaz yakalı" sıfatı ürünün kapsayamadığı bir genişlik vaat
ediyor, (b) "sohbette anlatırsın" artık ilk deneyim değil — `Bugün` ekranı ve hazır akışlar
girişi devraldı ([BRIEF.md](BRIEF.md) §1). Yeni cümle her ikisini de düzeltiyor.

---

## 2. Birincil kullanıcı

**Kim:** 5–15 kişilik bir yazılım/ürün ekibini yürüten kişi. Unvanı takım lideri, ürün
sahibi, teknik proje yöneticisi ya da engineering manager olabilir; ortak yanı **işi kendisi
yapmıyor, işin akmasını sağlıyor** olması.

**Hangi işi:** Gelen talebi işe çevirmek, işin nerede takıldığını görmek, ve ekibi/paydaşı
haberdar etmek. Üç fiil: **çevir, gör, haber ver.** Bugünkü dört hazır akış birebir bunlar.

**Hangi sıklıkta:** Günde 2–4 kez. Sabah (ne var), öğle (ne takıldı), akşam (ne oldu). Bu
ritim `Bugün` ekranının varlık sebebi ve `MORNING` playbook'unun adı.

### Somut bir gün — Deniz, ürün ekibi lideri

| Saat | Ne oluyor | Relay'de ne çalışıyor |
|---|---|---|
| **09:10** | Laptop açılır, `Bugün` ekranı zaten dolu: günün özeti + 4 öncelikli kart. | `BriefService` — Jira/GitHub/Gmail/Takvim paralel okundu, `DigestService` özeti yazdı |
| **09:12** | Kart: *"Ayşe: Ödeme akışında müşteri şikâyeti var"* → **"Bu bir iş talebi gibi görünüyor"** + `Jira kaydı aç` pili. Basar. | `InsightService` sınıflandırdı; `POST /api/runs/from-suggestion` akışı başlattı |
| **09:13** | Plan çıkar, `jira.createIssue` **onay kapısında bekler**. Parametreleri görür, proje anahtarını düzeltmek için reddeder, gerekçe yazar. | `PolicyEngine`: WRITE → `ask`. Red gerekçesi `AgentJournal` üzerinden Koordinatör'e gider, adım revize edilir |
| **09:15** | Revize adımı onaylar. Kayıt açılır, `#urun` kanalına bildirim düşer. | `jira.createIssue` → `slack.postMessage`, ikisi de ayrı onaydan geçti |
| **11:40** | "Ne takıldı?" — **Takılan işler** akışını çalıştırır. | `Playbooks.BLOCKERS`: JQL taraması otomatik akar, Slack özeti onay bekler |
| **14:30** | 2 gündür bekleyen PR'ları hatırlatır. | `Playbooks.PR_REVIEW` — raftaki adı **Bekleyen incelemeler** |
| **17:50** | **Günün özeti** akışı: Jira + PR + toplantılar → ekibe kapanış mesajı. | `Playbooks.MORNING` |
| **Ertesi gün** | Müdür sorar: "bu kaydı kim, neden açtı?" `Geçmiş` → akış → adımlar, kararlar, red gerekçesi, token, USD. | `Step.decision` + `rejectReason` + `AgentMessage` + `CostMeter` |

Bu günün hiçbir adımında kod yazılmıyor. Hepsi koordinasyon.

---

## 3. "Developer ürünü gibi duruyor" — gerçekten öyle mi?

**Kısmen. Ve düzeltilebilir kısmı kodda değil, metinde.**

### Ne gerçek, ne değil

**Entegrasyon seti geliştirici ağırlıklı değil, ekip-ağırlıklı.** Jira, Slack, Gmail, Takvim
— dördü de her beyaz yakalının stack'i. Tek istisna **GitHub**. Ve GitHub'da bile yaptığımız
şey kod değil, yönetim sorusu: "review bekleyen PR'ım var mı, kaç gündür bekliyor?"

**Ama sunum yüzeyi geliştirici ağırlıklıydı. Somut olarak — ve tablo artık bir tarihçe:**

| Nerede | Ne yazıyordu | Bugün |
|---|---|---|
| `frontend/src/components/Landing.tsx` → `SUGGESTIONS` | Üç örneğin üçü de Jira/Slack mühendislik cümlesi | **A1 uygulandı** — ilk örnek *"Bugünkü maillerime bak…"* |
| `frontend/src/components/Composer.tsx` → `PLACEHOLDER_LONG` | *"Blocker etiketli Jira işlerini bul…"* | **A2 uygulandı** — aynı mail cümlesi |
| `Playbooks.java` → `BLOCKERS`, `PR_REVIEW` | "Blocker taraması", "PR durumu" | **A3 uygulandı** — "Takılan işler", "Bekleyen incelemeler" |
| `Playbooks.ALL` sırası | Mail akışı sonda | **A4 uygulandı** — "Maili işe çevir" rafın ilk sırasında |
| `DEMO.md` §0.5 | Açılış insight kartı: *"Ödeme servisi staging'de patlıyor"* | **Ç1 uygulandı** — kart bir müşteri talebi (`DEMO-VERI.md` §1.1) |

Yani: **ürün beyaz yakalı işi yapıyordu, arayüz geliştirici diliyle konuşuyordu.** A grubu
bittiği için bu satırlar artık bir kontrol listesi değil, "ne değişti" kaydı — sunumda
"jargonu neden temizlediniz" sorusunun cevabı burada.

### Ne değişirse ürün beyaz yaka asistanına döner — maliyetiyle

Öneriler ucuzdan pahalıya. Üç ajan kod üzerinde çalıştığı için **A ve B grubu tek başına
kararı çözer**; C grubu yol haritası konuşmasıdır.

#### A. Metin değişiklikleri — yeni sınıf yok, migration yok, ekran yok

| # | Dosya | Değişiklik | Maliyet |
|---|---|---|---|
| A1 | `Landing.tsx` → `SUGGESTIONS` | İlk örneği değiştir: *"Bugünkü maillerime bak, iş talebi olanlar için kayıt aç ve ilgili kanaldan haber ver."* Mono araç etiketini `gmail.listToday · jira.createIssue · slack.postMessage` yap. | 3 satır. Sıfır risk |
| A2 | `Composer.tsx` → `PLACEHOLDER_LONG` | Aynı cümle. | 1 satır |
| A3 | `Playbooks.java` | `BLOCKERS.title` → **"Takılan işler"**, `PR_REVIEW.title` → **"Bekleyen incelemeler"**. `goal` ve JQL **değişmez** — sadece kullanıcıya görünen başlık. | 2 string. `id` sabit kaldığı için API kırılmaz |
| A4 | `Playbooks.ALL` sırası | `MAIL_TO_TICKET`'ı **başa** al. Onboarding ve `/api/playbooks` bu sırayı gösteriyor. | 1 satır |
| A5 | `OnboardingScreen.tsx` → `Welcome` | *"Jira · GitHub · Slack · Google üzerindeki günlük işleri…"* → araç adları yerine işi anlat: *"Mailden gelen talebi kayda çevir, işin nerede takıldığını gör, ekibi haberdar et."* | 1 paragraf |

**A grubunun toplam etkisi:** ürünün kod davranışı hiç değişmeden, jürinin gördüğü ilk üç
ekran mühendislik jargonundan çıkar. Bu, sorunun **%70'i**.

#### B. Var olanı görünür kılmak — backend yazılmış, arayüz yok

| # | Ne | Neden | Maliyet |
|---|---|---|---|
| B1 | **`/api/ask` için bir ekran** — "Posta kutuna sor". | Elimizdeki tek gerçekten rol-bağımsız özellik. "Kargolarım gelmiş mi", "Ahmet'ten teklif geldi mi", "faturalar geldi mi" — bunlar hiçbir yazılım ekibi işi değil, **herkesin işi**. Ve zaten çalışıyor: sorguyu gösteriyor, kaynak mailleri listeliyor, sonuç yoksa uydurmuyor. | Yeni `AskScreen.tsx` (~120 satır) + `data/AskSource.ts` (~30 satır) + `AppHeader.tsx`'e bir nav girdisi. **Yeni `Tool` yok, yeni bağlantı yok, yeni scope yok, migration yok.** |

**B1 yapıldı, sonra üst bardan geri çekildi — ve bu değerlendirme yanlıştı.**

Ekran yazıldı ve çalışıyor (`#/sor`, rota duruyor). Ama nav'a koymanın gerekçesi bir
kullanıcı ihtiyacı değil, bir jüri itirazına verilmiş savunmaydı — ve ekranın kendi metni
bunu itiraf ediyor: *"Okur, yazmaz: buradan hiçbir mail gitmez, hiçbir akış başlamaz."*
Ürünün üç fiili **bitirir · yazar · sorar**; o ekran ilk ikisini yapmıyor, üstelik 3
dakikalık sunumda yedi sekmeden biri olarak yer kaplıyordu ve demoda hiç açılmıyordu.
Yeni bir soru da açıyor: "yani bu bir Gmail arama kutusu mu?"

**Karar (#59):** `#/sor` hesap menüsünden erişilebilir kalsın, üst bardan çıksın.
"Bu herkes için mi?" sorusuna cevap bir **ekran** değil, bir **akış** olsun: hazır akış
rafının ilk sırasında artık *"Maili işe çevir"* duruyor — mail okur, kayıt açar, ekibe
haber verir. Ekran gösterip "bakın herkes için" demek yerine, herkesin yaptığı işi yapıp
göstermek.

#### C. Yeni yetenek — hackathon içinde YAPMA, yol haritasında söyle

| # | Ne | Mimari maliyet | Karar |
|---|---|---|---|
| C1 | `gmail.createDraft` / `gmail.sendMessage` (WRITE) | `GmailTool.java`'ya bir iç sınıf (~60 satır, `Search` sınıfı birebir şablon). **Ama** `GoogleOAuth.SCOPES` değişmek zorunda → `gmail.send` hassas scope, Google doğrulaması **bizim kontrolümüzde değil** ve `include_granted_scopes=true` olduğu için **bağlı her kullanıcı yeniden onay vermek zorunda**. | **Yarısı yapıldı.** `gmail.createDraft` kayıtlı ve canlı (`gmail.compose` scope'u verildi, `#/politikalar`'da `yazma` riskiyle görünüyor). `gmail.send` **yapılmadı ve yapılmayacak** — ayrım korunuyor: taslak geri alınabilir, gönderilen mail geri alınamaz. Sunumda söylenecek cümle: *"Mail yazıyoruz, göndermiyoruz — taslağı siz gönderiyorsunuz."* |
| C2 | `calendar.createEvent` (WRITE) | Aynı yapı, aynı scope sorunu (`calendar.events`). | **Hayır.** Yol haritası |
| C3 | Notion / Outlook / Drive aracı | Yeni `Tool` sınıfı **+ yeni `Connection` sağlayıcısı + `ConnectionsScreen`'e kart + muhtemelen yeni OAuth akışı**. `Tool` arayüzü tek sınıfla eklenebiliyor ama bağlantı yüzeyi eklenmiyor. | **Hayır.** 48 saatte olmaz, jüri sorarsa "uzantı noktası hazır" de |

**C grubunun sunumdaki karşılığı:** *"Gmail'de taslak yazıyoruz, göndermiyoruz; Takvim
salt-okunur — ikisi de bilinçli, çünkü gönderme ve takvim yazma scope'ları Google'ın
doğrulama sürecine bağlı. `Tool` arayüzü hazır; mail göndermek bizim için bir sınıf,
Google için bir onay süreci."* Bu cümle dürüst, teknik ve zayıflığı güce çeviriyor.

---

## 4. "Kurumsal çözüm" iddiası nereye kadar dürüst

Bu bölüm sunumda **kelimesi kelimesine** uyulacak sınırı çiziyor.

### Elimizde OLAN — hepsi kodda, hepsi gösterilebilir

| Yetenek | Kanıt |
|---|---|
| **Onay kapısı** | `PolicyEngine.evaluate()` — WRITE riskli araç onaysız çalışmaz |
| **Politika motoru** | Araç başına `auto` / `ask` / `forbidden` override; varsayılanlar risk seviyesinden türetilir (READ→auto, WRITE→ask, DESTRUCTIVE→forbidden). `GET/PUT /api/policies` |
| **Bilinmeyen aracın reddi** | Kayıtlı olmayan araç adı doğrudan `FORBIDDEN` — model uydurduğu bir aracı çağıramaz |
| **Denetim izi** | `Step`: araç, parametre, sonuç, hata, süre, `decision`, `rejectReason`, token, USD + `AgentMessage` günlüğü. `Geçmiş` ekranında açılabilir |
| **Maliyet ölçümü** | Adım başına token + USD, akış toplamı canlı; `CostMeter.budgetExceeded()` bütçe aşınca **durdurup sorar** |
| **Reddin plana dönmesi** | Red gerekçesi `AgentJournal` üzerinden Koordinatör'e gider, adım revize edilip tekrar onaya gelir — iptal butonu değil |
| **Kimlik bilgisi güvenliği** | `Connection.config` AES-GCM ile şifreli; log'a yazılmaz; API/arayüzde maskeli (`xoxb-****1234`) |
| **İptal edilebilir oturum** | Opak token, DB'de yalnız SHA-256'sı; çıkışta satır silinir, token o an ölür |
| **Giriş ≠ veri erişimi** | Google ile giriş yalnızca `openid email profile`; posta kutusu erişimi **ayrı** bir onay akışı (`/api/oauth/google/*`) |

### Elimizde OLMAYAN — sunumda asla iddia edilmeyecek

| Eksik | Gerçek durum |
|---|---|
| **Çok kiracılılık** | Yok. Tek ortak çalışma alanı |
| **Kullanıcı başına izolasyon** | Yok, ve bu **bilinçli**: `ARCHITECTURE.md` §4 — `runs` ve `connections` üzerinde `user_id` bilerek yok. Giriş yapan herkes aynı bağlantıları ve aynı koşuları görür. Şema değişikliği gerektiren ayrı bir iş |
| **SSO / SAML / SCIM** | Yok. Yalnız e-posta+parola ve Google OIDC ile **giriş**. Kurumsal kimlik sağlayıcı entegrasyonu, kullanıcı sağlama, otomatik kapatma — hiçbiri yok |
| **Rol yönetimi (RBAC)** | Yok. Kullanıcı rolü diye bir kavram yok; politikayı herkes değiştirebilir |
| **Denetim raporu ihracı** | Yok. CSV/PDF/rapor ucu **sıfır** (kodda tek satır yok). Denetim izi ekranda var, dışa aktarılamaz |
| **Zamanlanmış çalıştırma** | Yok. `@Scheduled` / cron **sıfır**. "Her sabah bunu yap" PRD'de F-22, yapılmadı. Hazır akışlar **elle** tetiklenir |
| **Kullanıcı başına kota / hız sınırı** | Yok. Bütçe **akış başına**, kişi başına değil |
| **Veri saklama / silme politikası** | Yok. Koşular süresiz durur |

### Sunumda hangi cümle kurulur, hangisi kurulmaz

**KURULUR:**
- *"Yazma adımı onaysız çalışmaz — bu bir ayar değil, ürünün varsayılanı."*
- *"Her araç risk seviyesiyle kayıtlı: oku otomatik, yaz onaylı."* — **üçlü değil ikili.**
  Motorun üçüncü ayağı (`DESTRUCTIVE` → `forbidden`) kodda var ama bugün **öznesi yok**:
  kayıtlı 18 aracın hiçbirinin riski `destructive` değil. Cümleyi üçlü kurma; sorulursa
  §7 cevap 10'daki hâliyle anlat.
- *"Kim onayladı, kim neyi neden reddetti, hangi adım kaça mal oldu — hepsi kayıtta."*
- *"Bütçeyi aşan ajan sessizce harcamaz; durur ve sorar."*
- *"Kurumsal yönetişimin **çekirdeği** burada: politika, onay, iz kaydı, maliyet."*
- *"Bu bir takım aracı — tek kişi kurar, ekibin yönetişimini getirir."*

**KURULMAZ:**
- ❌ *"Kurumsal hazırız"* / *"enterprise-ready"* — SSO yok, çok kiracılılık yok, RBAC yok.
- ❌ *"Her ekibin kendi çalışma alanı var"* — tek ortak alan var, açıkça yanlış.
- ❌ *"Uyumluluk raporlarınızı çıkarırsınız"* — ihraç ucu yok.
- ❌ *"Her sabah otomatik çalışır"* — zamanlayıcı yok. `Playbooks.MORNING`'in adı "Günün özeti",
  kendisi sabah **çalışmıyor**; kullanıcı basıyor.
- ❌ *"Koltuk başına satarız"* — **cümleyi kurma, ya da §7'deki kayıtlı haliyle kur.**
  Koltuk başına faturalama kullanıcı başına izolasyon gerektirir; o şema bilerek yok.

**Altın kural:** "kurumsal" kelimesini **yetenek** olarak değil, **yönetişim** olarak kullan.
"Kurumsal çözümüz" değil, **"kurumsal bir ekibin ajana güvenebilmesi için gereken kapıyı
kurduk"**. İkincisi elimizdekinin tam olarak karşılığı ve tek bir jüri sorusunda kırılmıyor.

---

## 5. Kategori kararı

### **Productivity & Work — değişmiyor.** İkincil kategori iddiası **kaldırılıyor.**

**Gerekçe:**

1. **Ürün bir çalışma günü aracı.** Ölçtüğümüz metrik ("manuel süre / Relay süresi ≥ 5×",
   PRD §9) doğrudan verimlilik metriği. Kategori ile ürün tezi birebir örtüşüyor.
2. **Rakip haritamız bu kategoride.** n8n, Zapier, Copilot — jürinin karşılaştıracağı her
   ürün burada. Başka kategoride bu karşılaştırmaya girmemiz gerekir ve hazırlıksız gireriz.
3. **PRD §3'teki "ikincil: Customer Experience" iddiası desteksiz.** Kodda müşteriye
   dokunan tek bir yüzey yok: müşteri portalı yok, ticket'a müşteri cevabı yok, SLA yok.
   Bu satırın PRD'den çıkarılması gerekiyor — ikinci bir kategori iddiası, jüri onu ciddiye
   alırsa net kayıptır.
4. **"Developer Tools" tipi bir kategori varsa bile oraya gitme.** Ürün kod üretmiyor,
   derlemiyor, test etmiyor, deploy etmiyor. O kategoride bizden beklenen şeyin hiçbirini
   yapmıyoruz ve ilk soruda yakalanırız.

---

## 6. Rakip haritası

Her biri **tek cümle**, ve her cümle savunulabilir. Abartılan bir ayrım, jüride uzman
varsa geri teper.

| Rakip | Tek cümlelik ayrım | Abartma sınırı — **asla deme** |
|---|---|---|
| **n8n / Zapier** | Onlarda akış **tasarım zamanında** kurulur ve kurulmayan iş yapılamaz; Relay'de plan **çalışma zamanında** cümleden çıkar, ve her yazma adımında durur. | ❌ "Onları değiştiririz" — 400+ entegrasyon, zamanlayıcı, retry, hata kuyruğu altyapıları var, bizde zamanlayıcı bile yok. ✅ Tamamlayıcı ol: *"n8n gece boru hattını koşturur, Relay gün içinde çıkan işi yapar."* |
| **n8n AI Agent node** | O node araçlarıyla sonuna kadar koşar; bizim iddiamız ajanı **çalıştırmak** değil, **durdurabilmek** — ve durduğu her yerde kimin onayladığı kayıtta. | ❌ "Onlarda onay yok" — kabaca yanlış, manuel node eklenebilir. ✅ Fark **varsayılan** ve **kayıt**: bizde kapı bir tasarım seçimi değil, ürünün varsayılanı |
| **ChatGPT / Claude (+ konektörler)** | Onlar cevabı sohbet balonunda verir; Relay'de karar bir **kayıt** — hangi araç, hangi parametre, kim onayladı, ne kadara mal oldu — ve reddedince plan revize olur. | ❌ "Onlar eylem yapamaz" — artık yapıyorlar, bu cümle 2024'te kalmış. ✅ Fark: **yönetişimin ürün nesnesi olması**. Politika ekranı, iz kaydı ekranı, maliyet şeridi |
| **Microsoft 365 Copilot** | Office'in içinde bizden çok daha derin; bizim iddiamız **tek satıcıya bağlı olmamak** — Atlassian, Slack, Google ve GitHub tek bir akışta. | ❌ "Copilot'tan iyiyiz" — Office içinde değiliz ve olmayacağız. ✅ Dürüst boşluk: *"Word'de onlarla yarışmayız; Jira'dan Slack'e giden işte yarışırız"* |
| **Jira / Slack'in kendi AI'ı** | Her biri **kendi ürününün içinde** kalıyor; acı zaten araçların *arasında*. | ❌ Yok, bu ayrım güvenli |
| **Mindra ve benzeri ajan ürünleri** | Onlar departman ölçeğinde ajan ekipleri **simüle ediyor**; bizde Jira ve Slack **gerçek kimlik bilgileriyle, gerçek API'lara** yazıyor ve her yazma insana soruyor. | ❌ "Onlar gerçek değil" — bilmiyoruz, iddia etme. ✅ Kendi kanıtına dayan: *"bu masadaki Jira gerçek"*. Referans olduğunu saklama, jüri bunu dürüstlük sayar |

**Haritanın özeti tek cümlede:** *Otomasyon araçları **ne zaman** kurulduğuyla, sohbet
asistanları **nerede** durduğuyla ayrılıyor bizden. Relay çalışma zamanında planlıyor ve her
yazma adımında duruyor.*

---

## 7. Jüri soruları ve cevapları

Her cevap **15 saniyenin altında**. İlk cümle daima doğrudan cevap; gerekçe sonra.
[DEMO.md](DEMO.md) §3'teki teknik sorular (n8n, LLM parametresi, token güvenliği, entegrasyon
maliyeti) geçerliliğini koruyor — buradakiler **konumlandırma** soruları, onların üstüne eklenir.

**1. "Bu kişisel bir araç mı, kurumsal bir ürün mü?"**
> Kurulumu kişisel, yönetişimi kurumsal. Tek kişi beş dakikada token girip kullanmaya başlar —
> ama ilk yazma adımında karşısına onay kapısı, politika ve iz kaydı çıkar. Ekipler böyle
> benimsiyor: bir kişi kurar, ekip yönetişimi hazır bulur.

**2. "Bu bir developer ürünü değil mi? Jira, GitHub…"**
> Hayır — developer'ın değil, developer'ları **yürüten** kişinin ürünü. Geliştirici IDE'de
> yaşar; bizim çözdüğümüz acı dört araç arasında koşuşturmak, ve o koşuşturma ekip lideri,
> ürün sahibi, proje yöneticisinin işi. Zaten en çok kullanılan akışımız "gelen maili işe
> çevir" — bunun kodla ilgisi yok.

**3. "Neden ajanı serbest bırakmıyorsunuz?"**
> Çünkü serbest ajan kurumda **kullanılamıyor**. Teknik olarak bırakabiliriz — politikayı
> `otomatik`e almak bir ayar. Ama bir ekip lideri, adına Jira'ya yazan ve Slack'e mesaj
> atan bir sisteme ancak durdurabildiğinde izin verir. Biz otonomi satmıyoruz, **onay
> verilebilir otonomi** satıyoruz.

**4. "İş modeli ne?"**
> Ekip başına abonelik, üstüne kullanım. LLM maliyeti zaten adım başına ölçülüyor —
> faturalama altyapısı ürünün içinden çıktı. Ücretli katmanın içeriği net: politika
> yönetimi, denetim izi ve maliyet raporlaması. Bugün tek çalışma alanı olarak
> koşuyoruz; koltuk başına faturalama kullanıcı bazlı izolasyon gerektiriyor ve o bizim
> bilinçli olarak sonraya bıraktığımız iş.

**5. "Verilerimiz güvende mi?"**
> Üç katman. Token'lar veritabanında AES-GCM ile şifreli, anahtar ortam değişkeninde;
> log'a asla yazılmaz; API ve arayüzde maskeli döner. Ayrıca Google'da **giriş** ile
> **posta kutusu erişimi** iki ayrı onay — bize giriş yapmak posta kutunuzu vermek değil.
> Modele giden veri de sadece o adımın ihtiyacı kadarı.

**6. "Bir kişi bunu neden kurar? Zaten Jira'ya da Slack'e de bakıyor."**
> Çünkü bakmak iş değil, **taşımak** iş. Sabah dört araç açıp kimin neyi beklediğini
> çıkarmak yirmi dakika; Relay onu tek ekrana getiriyor ve bir tıkla işe çeviriyor.
> Kazandığı şey bilgi değil, o yirmi dakika ve unuttuğu iş sayısı.

**7. "Kurumsalsanız SSO ve çok kiracılılık nerede?"**
> Yok, ve bunu bilerek erteledik. 48 saatte kurumsal bir ürünün **hangi** kısmının
> kanıtlanmaya değer olduğuna karar vermek zorundaydık: kimlik altyapısı çözülmüş bir
> problem, ajana güven değil. O yüzden onay kapısını, politika motorunu ve iz kaydını
> yaptık. SSO bir sprint; ajanın neden durduğunu anlatabilmek bir ürün.

**8. "Kullanıcı her adımı onaylayacaksa zaman kazancı nerede?"**
> Onaylanan adım bir avuç, yapılan iş onlarca. Okuma adımları — arama, listeleme, mail
> okuma — kendiliğinden akıyor; sadece yazma duruyor. Arama, listeleme, mail okuma, eşleştirme,
> özet yazma — hepsi kullanıcı hiç dokunmadan koşuyor. Kaybettiğiniz birkaç tık, kazandığınız
> yirmi dakika.

**9. "Yarın Atlassian aynısını yaparsa?"**
> Yapar, ve Jira'nın içinde bizden iyi yapar. Ama acı Jira'nın içinde değil, Jira ile
> Slack'in **arasında** — ve orada hiçbir satıcı diğerinin aracına yazmak istemiyor.
> Bağımsız olmak burada bir dezavantaj değil, tek uygulanabilir konum.

---

## 8. İki cümlelik karar

> **Diyoruz ki:** Relay, gün içinde çıkan işi araçların içinde yürüten bir ajan ekibi — ve
> onay kapısı, politika motoru, maliyet ölçümü ve iz kaydıyla, bir ekibin bir ajana
> güvenebilmesi için gereken her şey ürünün varsayılanı.
>
> **Demiyoruz ki:** "kurumsal hazırız" (SSO, çok kiracılılık, rapor ihracı ve zamanlayıcı
> yok), "her beyaz yakalı için" (Gmail ve Takvim yazma yüzeyi taslakla sınırlı; bugün
> araca **yazdığımız** yer ekip araçları) ve "işi bitiriyoruz" (akışların yarısı onay
> bekliyor ya da hata veriyor — bu ölçüm ürünün kendi panelinde duruyor).

---

## 9. Bu doküman `DEMO.md` ile nerede çelişiyor

Aşağıdakiler **çelişki** ve karara bağlanması gerekiyor. Demo betiği bu dokümandan sonra
güncellenmeli.

**Ç1 — `DEMO.md` §0.5 açılış kartı ürünü mühendislik ürünü gibi gösteriyor.**
Kart metni: *"Ödeme servisi staging'de patlıyor"*. Demonun 12. saniyesinde jürinin okuduğu
tek cümle bir staging hatası. **Öneri:** kart bir **iş talebi** olsun — ör. *"Ayşe:
Ödeme akışında müşteri şikâyeti var, kayıt açar mısın?"*. Aynı `InsightService` sınıfı
(`request`), aynı `jira.createIssue` eylemi, aynı demo akışı; değişen tek şey fikstür metni.
**Sunumun tamamındaki en ucuz ve en yüksek getirili konumlandırma değişikliği bu.**

**Ç2 — §1'in demo cümlesi ile §2'nin açılış konuşması aynı kişiyi tarif etmiyor.**
§2 "bir beyaz yakalının günü" diyor, §1 (0:15) "her PM'in yaşadığı bir iş" diyor, PRD §1
"beyaz yakalı" diyor. Üç farklı genişlik. **Öneri:** hepsi §1'deki dar ve doğru olanda
birleşsin. Açılış konuşmasının ilk cümlesi: *"Bir ekibi yürüten kişinin günü tek bir işten
değil, araçlar arası koşuşturmadan oluşuyor."* Gerisi aynen kalır.

**Ç3 — §3'teki Mindra cevabı "kurumsal" iddiasıyla çelişiyor.**
Mevcut cevap: *"biz aynı şeyi **tek kişinin günlük işine** indiriyoruz"* — bu net bir
**kişisel** konumlandırma. Ürün sahibi kurumsal istiyorsa bu cümle ters çalışıyor.
**Öneri:** *"…tek kişinin gününe indiriyoruz"* → *"…tek bir ekibin gününe indiriyoruz;
kurulum tek kişilik, yönetişim ekip ölçeğinde."* §7'deki 1 numaralı cevapla aynı hizaya gelir.

**Ç4 — §3'teki "n8n'de de AI Agent node var" cevabı, demoda olmayan bir anı gösteriyor.**
Cevap şöyle bitiyor: *"Demoda gördünüz: sistem uydurduğu bir kayıt anahtarıyla Jira'ya
yazmaya kalktı, kendi kendini durdurdu ve önce aramaya gitti."* §1'in saniye tablosunda
böyle bir an **yok** — orada `jira.updateIssue` politikayla duruyor, uydurma anahtar
yüzünden değil. Jüri "hangi anda?" derse cevap veremeyiz. **Öneri:** cümle
*"Demoda gördünüz: yazma adımı politikayla durdu ve bana sordu — kayıtlı olmayan bir araç
adı da aynı şekilde reddedilir"* olarak düzeltilsin. İkinci yarısı `PolicyEngine`'de gerçekten
uygulanıyor (bilinmeyen araç → `FORBIDDEN`), yani iddia korunuyor ve doğru oluyor.

**Ç5 — §3'teki iş modeli cevabı yedek soru listesinde ve eksik.**
"Koltuk başına abonelik" cümlesi, kullanıcı başına izolasyonun olmadığı bir üründe
savunulamaz. **Öneri:** yedekten çıkarılıp ana listeye alınsın ve §7'deki 4 numaralı
cevapla değiştirilsin ("ekip başına + kullanım", izolasyon kısıtı açıkça söylenerek).

**Çelişki olmayan, teyit edilen kısımlar:** onay kapısı anı (1:05), red gerekçesinin plana
dönmesi (1:25), maliyet şeridi, `Geçmiş` denetim izi ve replay sigortası — dördü de kodda
karşılığı olan, olduğu gibi anlatılabilecek iddialar. Demonun kalbi doğru yerde duruyor;
değişmesi gereken şey **çerçeve metni**, akış değil.
