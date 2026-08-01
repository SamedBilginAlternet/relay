# Entegrasyon Kurulumu

> Bu dosya **kullanıcı tarafını** anlatır: her sağlayıcıda hangi ekrandan ne alınacak,
> Relay'e nereye girilecek. Kod tarafı için bkz. `ARCHITECTURE.md` §6, ürün için `BRIEF.md`.

Relay hiçbir entegrasyon olmadan da çalışır: bağlantısı olmayan sağlayıcı otomatik
**replay** moduna düşer (`AbstractTool`), `/api/brief` de o bölümü `unavailable` işaretleyip
diğerlerini döndürür. Yani eksik entegrasyon ekranı boşaltmaz — sadece o kutuyu "bağla"ya çevirir.

Zorluk sırası: **Notion → GitHub → Jira → Slack → Google**. İlk dördü tek token, sonuncusu OAuth.

---

## 1. GitHub (PAT — OAuth yok)

**Nereden:** [github.com/settings/personal-access-tokens/new](https://github.com/settings/personal-access-tokens/new) → *Fine-grained token*

| Ayar | Değer |
|---|---|
| Repository access | All repositories (veya demoda kullanılacak repolar) |
| Permissions → Pull requests | Read and write |
| Permissions → Issues | Read and write |
| Permissions → Metadata | Read (otomatik eklenir) |
| Expiration | 30 gün yeterli |

Yazma izni yalnızca `github.addComment` için gerekiyor ve o araç WRITE riskli → onay kapısından geçer.
Sadece okuma isteniyorsa iki izni Read yapmak yeterli; o durumda yorum eylemi reddedilir.

**Relay'e girilecek** (Bağlantılar ekranı → `github`):

| Anahtar | Değer |
|---|---|
| `token` | `github_pat_...` |
| `login` | GitHub kullanıcı adı — boş bırakılırsa aramalar `@me` ile yapılır |

---

## 2. Jira ✅ (kurulu)

**Nereden:** [id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens) → *Create API token*

| Anahtar | Değer | Örnek |
|---|---|---|
| `baseUrl` | Site adresi | `https://samedbilgin322.atlassian.net` |
| `email` | Atlassian hesabı | `samedbilgin322@gmail.com` |
| `apiToken` | `ATATT3x...` | — |

Notlar:
- Aramalar `/rest/api/3/search/jql` kullanır; eski `/rest/api/3/search` Atlassian tarafından
  kaldırıldı (CHANGE-2046, HTTP 410).
- Site var ama Jira **ürünü** kurulu değilse token geçerli olsa bile 404 alınır.
- KAN panosu Kanban olduğu için sprint API'si 400 döner; demo senaryosu `labels = blocker` üzerinden yürür.

---

## 3. Slack ⚠️ (eksik scope)

**Nereden:** [api.slack.com/apps](https://api.slack.com/apps) → uygulaman → *OAuth & Permissions* → Bot Token Scopes

Şu an verili: `commands`, `chat:write`, `app_mentions:read`. Eklenecek:

| Scope | Neden |
|---|---|
| `channels:read` | Kanal listesi / kanal adını id'ye çevirmek |
| `channels:join` | Bota davet edilmemiş public kanala mesaj atabilmek |
| `chat:write.public` | (opsiyonel) katılmadan yazmak |

Sonra **Reinstall to Workspace** → yeni `xoxb-...` token. Kanal tarafında bir kez `/invite @relay`.

**Relay'e girilecek** (`slack`): `botToken` = `xoxb-...`

---

## 4. Notion (integration token — OAuth yok)

Relay'in tek yazma aracı olan sağlayıcı: `notion.createPage`. Okuma aracı **yok** ve
bilerek yok — okuyan bir araç her sabah briefte iki model turu daha demektir, yazan bir
araç ise yalnız kullanıldığı akışta ~100 token. Notion'dan okuma isteniyorsa bu bir ürün
kararıdır, eksik değil.

**Nereden:** [notion.so/my-integrations](https://www.notion.so/my-integrations) →
*New integration* → tipi **Internal**, yetenekleri **Insert content** (+ istenirse *Read
content*) → **Internal Integration Secret** kopyalanır (`ntn_...`).

**Relay'e girilecek** (Bağlantılar ekranı → `notion`):

| Anahtar | Zorunlu | Değer |
|---|---|---|
| `token` | evet | `ntn_...` — Internal Integration Secret |
| `parentDatabaseId` | hayır | Sayfaların açılacağı varsayılan veritabanının 32 karakterlik kimliği |

`parentDatabaseId` girilmezse her adımda hedef veritabanını modelin bulması gerekir ve
bulamaz — bu alanı bir kez doldurmak, akışın her seferinde çalışması demektir. Kimlik,
veritabanını Notion'da açtığınızda adres çubuğunda durur:

```
https://www.notion.so/workspace/2f0a1b9c4d5e4f60a1b2c3d4e5f60718?v=...
                               └──────── parentDatabaseId ────────┘
```

> ### ⚠️ ÖNCE BUNU YAPIN: sayfayı integration ile paylaşın
>
> **Bir Notion integration'ı, kendisiyle açıkça paylaşılmamış hiçbir sayfayı göremez.**
> Token doğru, kimlik doğru, yetkiler doğru olsa bile paylaşım yoksa her çağrı
> `object_not_found` döner — Notion, izin verilmemiş sayfayı "yok" diye cevaplar,
> "yetkiniz yok" diye değil. Kurulumda atlanan tek adım budur ve canlı demoyu bozan bir
> numaralı hata budur.
>
> **Yapılacak:** hedef sayfayı/veritabanını Notion'da açın → sağ üstteki **•••** →
> **Connections** (Türkçe arayüzde *Bağlantılar*) → **Connect to** → oluşturduğunuz
> integration'ı seçin. Bir üst sayfada yapılırsa alt sayfalar da devralır.
>
> Bu, Slack'teki `/invite @relay` ile birebir aynı sınıftan bir adımdır: kimlik bilgisi
> erişim değildir, erişim ayrıca verilir.
>
> Relay bu hatayı ham haliyle göstermez. `object_not_found` geldiğinde adım şu Türkçe
> cümleyle durur: *"Notion hedef sayfayı ya da veritabanını göremiyor. Bu neredeyse her
> zaman izin sorunudur, id hatası değil… ••• menüsünden Connections → Relay
> integration'ını ekleyin."* Yani ekranda id'yi değil paylaşımı kontrol etmeniz yazar.

Notlar:

- Her istek `Notion-Version: 2022-06-28` başlığıyla gider. Bu başlık **zorunludur**;
  olmadan Notion gövdeye bakmadan HTTP 400 döner. Kodda sabitlenmiştir
  (`NotionTool.API_VERSION`), ortam değişkeni değildir.
- Sayfa başlığı, veritabanının ilk sütununun *adına* göre değil `title` kimliğine göre
  yazılır — sütunun adı "Name", "Ad" ya da "Başlık" olabilir, hiçbiri fark etmez.
- `content` düz metin ya da hafif markdown olabilir: `# `, `## `, `### ` başlıklar ve
  `- ` maddeler bloklara çevrilir, gerisi paragraf olur.
- `notion.createPage` **WRITE** risklidir → politika motoru onay kapısını kendiliğinden
  açar, ayrıca kural yazmak gerekmez.
- Bağlantı yoksa araç replay moduna düşer ve demo yine baştan sona çalışır.

---

## 5. Google — Gmail + Calendar + Sheets (OAuth)

**Nereden:** [console.cloud.google.com](https://console.cloud.google.com)

1. Yeni proje: `relay-hackathon`
2. **APIs & Services → Library** → etkinleştir: **Gmail API**, **Google Calendar API**,
   **Google Sheets API**
3. **OAuth consent screen** → *External* → uygulama adı `Relay`, destek e-postası kendi adresin
   → **Test users**'a `samedbilgin322@gmail.com` ekle. (Test modu yeterli; yayın onayı beklemiyoruz.)
4. **Scopes**:
   - `https://www.googleapis.com/auth/gmail.readonly` *(restricted)*
   - `https://www.googleapis.com/auth/calendar.readonly` *(sensitive)*
   - `https://www.googleapis.com/auth/gmail.compose` *(restricted)* — `gmail.createDraft` için.
   - `https://www.googleapis.com/auth/calendar.events` *(sensitive)* — `calendar.createEvent` için.
   - `https://www.googleapis.com/auth/spreadsheets` *(sensitive)* — `sheets.appendRow` için.

   > **Bu izin gönderebilir.** Google'ın izin ekranında görünen metni "Manage drafts and
   > send emails" ve `messages.send`'i de kapsıyor. Gmail'de **yalnız taslak** diye bir
   > scope yok: taslak oluşturabilen üç scope (`gmail.compose`, `gmail.modify`,
   > `mail.google.com`) da göndermeye izin veriyor; `gmail.compose` bunların en darı.
   > Yani "Relay senin adına mail göndermez" garantisini **kod** veriyor
   > (`GmailTool.CreateDraft` tek uca gidiyor, testle kilitli), grant değil. Kullanıcıya
   > izin ekranından farklı bir şey vaat etme.
5. **Credentials → Create credentials → OAuth client ID → Web application**
   Authorized redirect URIs:
   ```
   https://relay.samedbilgin.com/api/oauth/google/callback
   http://localhost:8080/api/oauth/google/callback
   ```
6. Çıkan **Client ID** + **Client Secret** ortam değişkeni olarak girilir (aşağıya bak).

Sonrası uygulamada: Bağlantılar → Google → **Bağlan** → Google izin ekranı → geri dönüş.
Refresh token bağlantı config'ine şifreli yazılır; ilk izinden sonra bir daha sorulmaz.

> **Restricted scope, yayın planı:** test modunda (test users listesi) sorun yok — `gmail.readonly`
> zaten restricted'dı ve öyle çalışıyor. Uygulama *Production*'a alınacaksa restricted scope'lar
> Google doğrulaması **ve** yıllık CASA güvenlik değerlendirmesi istiyor; bu bir ürün kararı,
> kod değişikliği değil.

> Kapsam genişlediğinde (`gmail.compose`, `calendar.events`, `spreadsheets`) **eski bağlantı
> bozulmaz**: okuma işleri aynen çalışmaya devam eder, yalnız o yazma adımı çalışmaz ve araç
> Türkçe bir cümleyle "Bağlantılar'dan yeniden bağlan" der. `/api/oauth/google/status` bunu
> ayrı ayrı söyler — `connected: true` yanında `canCompose`, `canCreateEvent` veya
> `canAppendRow` `false` ise yeniden bağlanma gerekiyor.

### 5.1 `calendar.createEvent` — takvime toplantı koymak

| Ne | Değer |
|---|---|
| Eklenecek scope | `https://www.googleapis.com/auth/calendar.events` |
| Nereye | Google Cloud Console → **OAuth consent screen → Data access (Scopes)** → *Add or remove scopes* |
| Sonra | **Bağlantılar → Google → Yeniden bağlan.** Scope'u konsola eklemek tek başına yetmez |

**Yeniden onay vermezsen ne olur:** hiçbir şey bozulmaz. Brifing, takvim okuması, Jira, Slack,
GitHub, hatta `gmail.createDraft` aynen çalışır. Yalnız `calendar.createEvent` adımı şu cümleyle
durur: *"Google izni yalnız okuma; takvime kayıt açmak için Bağlantılar'dan Google'a yeniden
bağlan."* Google'ın `insufficient authentication scopes` 403'ü ekrana hiç çıkmaz.

Neden `calendar.events`, `calendar` değil: `calendar` takvim listesini ve paylaşım kurallarını
da açar; `calendar.events` yalnız etkinliklere ulaşır. İkisi de *sensitive*, yani `gmail.compose`
gibi restricted değil — yayına alma sürecinde CASA değerlendirmesi getirmez.

Bilmen gereken iki davranış:

- **Davetler onaydan sonra gerçekten gider** (`sendUpdates=all`). Google'ın varsayılanı katılımcıyı
  etkinliğe yazıp haber vermemek; onay ekranında üç kişi gördükten sonra üç kişinin haberi olmaması
  daha kötü bir sonuç. Onay kapısı bu yüzden bu araçta özellikle önemli.
- **Katılımcı e-posta adresidir, isim değil.** `calendar.listToday` katılımcıları görünen adla
  ("Deniz Arslan") döndürüyor; adım isimden adres türetmez, adımı hata ile durdurur. Onay
  ekranında adresi kendin yazabilir ya da katılımcısız onaylayıp kişiyi Takvim'den ekleyebilirsin.

### 5.2 `sheets.appendRow` — takip tablosuna satır eklemek

| Ne | Değer |
|---|---|
| Eklenecek scope | `https://www.googleapis.com/auth/spreadsheets` |
| Ayrıca | **APIs & Services → Library → Google Sheets API** etkinleştirilmeli (scope tek başına yetmez, API kapalıysa 403 gelir) |
| Nereye | Google Cloud Console → **OAuth consent screen → Data access (Scopes)** |
| Sonra | **Bağlantılar → Google → Yeniden bağlan** |

**Yeniden onay vermezsen ne olur:** hiçbir şey bozulmaz. Brifing, mail, takvim ve takvim kaydı
aynen çalışır; yalnız `sheets.appendRow` adımı *"Google izni tabloya yazmayı kapsamıyor;
Bağlantılar'dan Google'a yeniden bağlan"* diyerek durur.

**İki bağlantı ayarı (Bağlantılar → Google → form):**

| Anahtar | Değer | Not |
|---|---|---|
| `defaultSpreadsheetId` | Tablonun kimliği ya da adresi | Adresi olduğu gibi yapıştırabilirsin: `docs.google.com/spreadsheets/d/<kimlik>/edit` içinden kimlik okunur |
| `defaultSheetName` | Sekme adı | Boş bırakılırsa `Sayfa1`. İngilizce arayüzlü Sheets'te ilk sekmenin adı `Sheet1`'dir — bu ayarı düzeltmezsen Google `Unable to parse range` döner |

Bunlar süs değil. `SlackTool.PostMessage` bağlantıdaki `defaultChannel`'ı onay ekranından
**önce** çözüyor; aynı yol burada da işliyor (`withDefaults` + `ToolAgent.CONTAINER_DEFAULTS`),
yani onay ekranı "hangi tabloya" sorusuna boş değil gerçek cevabı gösteriyor. Ayarları boş
bırakırsan onay ekranında hedef boş görünür ve adım büyük ihtimalle 404 ile düşer.

**Tablonun bu Google hesabıyla paylaşılmış olması gerekir.** Scope, hesabın açabildiği
tablolara ulaşır; hesabın erişimi olmayan bir tabloya scope da yetmez.

Bilmen gereken iki davranış:

- **Yalnız ekler.** `values.append` + `insertDataOption=INSERT_ROWS`: satır sonuncunun altına
  yazılır, var olan hiçbir hücrenin üstüne yazılmaz. Araç hücre **okuyamaz** da — tablodaki
  veri hiçbir zaman akış geçmişine düşmez.
- **Hücre metindir** (`valueInputOption=RAW`). `USER_ENTERED` olsaydı modelin yazdığı
  `=IMPORTXML(...)` gibi bir hücre paylaşılan dosyada canlı formüle dönerdi. Bedeli: `1.500`
  sayı değil metin olarak durur. Ucuz olan bu.

> İzin ekranında "Google bu uygulamayı doğrulamadı" uyarısı normaldir — test kullanıcısı olduğun
> için *Advanced → Go to Relay (unsafe)* ile devam edilir.

---

### 6.1 Groq kotası: neden beş anahtar bir anahtar kadar

Canlıda beş anahtarın beşi bir saniye içinde 429 aldı ve ürün stub'a düştü. Sebep
anahtar sayısı değil:

```
Rate limit reached for model `llama-3.3-70b-versatile`
in organization `org_…` service tier `on_demand`
on tokens per day (TPD): Limit 100000, Used 99134
```

Limit **kuruluşa** yazılı. Bir Groq hesabında ürettiğin beş anahtar aynı günlük
100.000 token'ı paylaşır; altıncısını üretmek hiçbir şey eklemez. Rotasyon yalnız
iki durumda işe yarar: dakikalık bir sıçramayı yaymak, ve **farklı hesaplardan**
gelen anahtarlar arasında geçiş yapmak.

Kota bittiğinde ne yapılır, ucuzdan pahalıya:

1. Beklemek. TPD kayan bir penceredir; Groq cevabında kaç dakika kaldığını yazar ve
   `/api/health/details` bunu olduğu gibi gösterir.
2. Farklı e-posta ile ikinci (üçüncü) bir Groq hesabı açıp anahtarlarını
   `GROQ_API_KEYS`'e eklemek. Her hesap kendi 100k'sıyla gelir ve havuz artık
   gerçekten rotasyon yapar: tükenen kuruluşun anahtarı sağlayıcının söylediği süre
   boyunca beklemede kalır (`ApiKeyPool.MAX_PARK`, en fazla 1 saat), trafik sağlam
   olana gider.
3. Dev Tier'a geçmek. Aynı anahtarlar, çok daha yüksek limit.

Bir demo günü ölçüsü: bir brifing yenilemesi ~2.300 token. 100k/gün ≈ 40 yenileme
artı akışlar. QA yükünü demo gününde canlıya bindirmeyin.

### 6.2 İkinci sağlayıcı: bedava biterse ücretli devralsın

Groq'un bedava planında duvar **günlük token** (TPD). Duvarı olmayan bir sağlayıcı
arkaya konursa, bedava kota bittiği anda ürün stub'a düşmek yerine ondan devam eder.

```
LLM_FALLBACK_API_KEYS=sk-...          # boşsa ikinci sağlayıcı yok, hiçbir şey değişmez
LLM_FALLBACK_BASE_URL=https://api.deepseek.com
LLM_FALLBACK_MODEL=deepseek-v4-flash
LLM_FALLBACK_PROVIDER=deepseek
LLM_FALLBACK_PRICE_INPUT=0.14
LLM_FALLBACK_PRICE_OUTPUT=0.28
```

Sıra: **Groq → ikinci sağlayıcı → stub.** Groq çalışırken ikinciye tek istek gitmez,
yani fatura da oluşmaz. `/api/health/details` hangi katmanın cevapladığını (`provider`)
ve ikincinin anahtar durumunu (`fallback`) ayrı ayrı gösterir.

Neden DeepSeek varsayılan: OpenAI uyumlu (`{base}/chat/completions`), `response_format:
json_object` destekliyor — planlayıcı ve parametre üreticisi buna bağlı — ve **günlük
token tavanı yok**; sınır eşzamanlılıkta (v4-flash için 2500). Fiyat 0,14$/1M giriş,
0,28$/1M çıkış. Ölçü: bir günlük tüm QA yükümüz 373.132 token, yani ~**0,07$**.

Aynı üç değişkenle başka sağlayıcılar da çalışır:

| Sağlayıcı | base-url | not |
|---|---|---|
| DeepSeek | `https://api.deepseek.com` | ücretli, günlük tavan yok, kayıtta tek seferlik 5M token |
| Cerebras | `https://api.cerebras.ai/v1` | günde 1M token bedava, ama bedava katmanda **8K bağlam sınırı** var — brifing istemleri buna sığmayabilir |
| Together / OpenRouter | kendi base-url'leri | çok model, kredi bazlı |

JSON kipi uyarısı: DeepSeek `json_object` için istemde "json" kelimesinin geçmesini
şart koşuyor. Bizim şema eklendiğinde sistem istemine yazdığımız cümle
("Answer with JSON only, matching this schema") bunu zaten karşılıyor — o cümle
kaldırılırsa DeepSeek'te JSON kipi bozulur.

## 6. Ortam değişkenleri (Coolify)

| Değişken | Zorunlu | Not |
|---|---|---|
| `GROQ_API_KEYS` | evet | virgülle ayrılmış; biri 429 alınca sıradakine geçilir. **Aynı hesabın anahtarlarını çoğaltmak kota kazandırmaz** — Groq token limitini _kuruluş_ başına sayar, anahtar başına değil. Gerçek kazanç için anahtarların farklı Groq hesaplarından gelmesi gerekir (§5.1) |
| `POSTGRES_PASSWORD` | evet | boş bırakılırsa postgres imajı hiç açılmaz |
| `ENCRYPTION_KEY` | evet | bağlantı config'lerinin AES-GCM anahtarı |
| `TOOLS_MODE` | hayır | `live` (varsayılan) / `replay` — demo sigortası |
| `GOOGLE_CLIENT_ID` | Google için | yoksa uygulama yine açılır, Google bölümü `unavailable` olur |
| `GOOGLE_CLIENT_SECRET` | Google için | — |
| `GOOGLE_REDIRECT_URI` | Google için | `https://relay.samedbilgin.com/api/oauth/google/callback` |

Jira/Slack/GitHub/**Notion** kimlik bilgileri **ortam değişkeni değildir** — veritabanında
şifreli (AES-GCM) tutulur, Bağlantılar ekranından girilir. Böylece demo sırasında yeniden
deploy gerekmez. Notion için Coolify'a girilecek **hiçbir şey yoktur**: `ntn_...` token'ı
doğrudan Bağlantılar → Notion formuna yapıştırılır.

---

## 7. Hackathon sonrası

Bu belgede geçen tüm token'lar (Groq, Jira, Slack, GitHub PAT, Google secret, Notion
integration secret) **iptal edilip yeniden üretilmeli**. Hiçbiri repoya yazılmadı; yalnız
Coolify ve veritabanında duruyorlar.
