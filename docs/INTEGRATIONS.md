# Entegrasyon Kurulumu

> Bu dosya **kullanıcı tarafını** anlatır: her sağlayıcıda hangi ekrandan ne alınacak,
> Relay'e nereye girilecek. Kod tarafı için bkz. `ARCHITECTURE.md` §6, ürün için `BRIEF.md`.

Relay hiçbir entegrasyon olmadan da çalışır: bağlantısı olmayan sağlayıcı otomatik
**replay** moduna düşer (`AbstractTool`), `/api/brief` de o bölümü `unavailable` işaretleyip
diğerlerini döndürür. Yani eksik entegrasyon ekranı boşaltmaz — sadece o kutuyu "bağla"ya çevirir.

Zorluk sırası: **GitHub → Jira → Slack → Google**. İlk üçü tek token, sonuncusu OAuth.

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

## 4. Google — Gmail + Calendar (OAuth)

**Nereden:** [console.cloud.google.com](https://console.cloud.google.com)

1. Yeni proje: `relay-hackathon`
2. **APIs & Services → Library** → etkinleştir: **Gmail API**, **Google Calendar API**
3. **OAuth consent screen** → *External* → uygulama adı `Relay`, destek e-postası kendi adresin
   → **Test users**'a `samedbilgin322@gmail.com` ekle. (Test modu yeterli; yayın onayı beklemiyoruz.)
4. **Scopes** — üçü de Google'ın **restricted** sınıfında:
   - `https://www.googleapis.com/auth/gmail.readonly`
   - `https://www.googleapis.com/auth/calendar.readonly`
   - `https://www.googleapis.com/auth/gmail.compose` — `gmail.createDraft` için.

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

> Kapsam genişlediğinde (`gmail.compose` eklendi) **eski bağlantı bozulmaz**: okuma işleri
> aynen çalışmaya devam eder, yalnız taslak yazma çalışmaz ve araç Türkçe bir cümleyle
> "Bağlantılar'dan yeniden bağlan" der. `/api/oauth/google/status` bunu `canCompose` ile
> söyler — `connected: true, canCompose: false` = yeniden bağlanma gerekiyor.

> İzin ekranında "Google bu uygulamayı doğrulamadı" uyarısı normaldir — test kullanıcısı olduğun
> için *Advanced → Go to Relay (unsafe)* ile devam edilir.

---

### 5.1 Groq kotası: neden beş anahtar bir anahtar kadar

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

### 5.2 İkinci sağlayıcı: bedava biterse ücretli devralsın

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

## 5. Ortam değişkenleri (Coolify)

| Değişken | Zorunlu | Not |
|---|---|---|
| `GROQ_API_KEYS` | evet | virgülle ayrılmış; biri 429 alınca sıradakine geçilir. **Aynı hesabın anahtarlarını çoğaltmak kota kazandırmaz** — Groq token limitini _kuruluş_ başına sayar, anahtar başına değil. Gerçek kazanç için anahtarların farklı Groq hesaplarından gelmesi gerekir (§5.1) |
| `POSTGRES_PASSWORD` | evet | boş bırakılırsa postgres imajı hiç açılmaz |
| `ENCRYPTION_KEY` | evet | bağlantı config'lerinin AES-GCM anahtarı |
| `TOOLS_MODE` | hayır | `live` (varsayılan) / `replay` — demo sigortası |
| `GOOGLE_CLIENT_ID` | Google için | yoksa uygulama yine açılır, Google bölümü `unavailable` olur |
| `GOOGLE_CLIENT_SECRET` | Google için | — |
| `GOOGLE_REDIRECT_URI` | Google için | `https://relay.samedbilgin.com/api/oauth/google/callback` |

Jira/Slack/GitHub kimlik bilgileri **ortam değişkeni değildir** — veritabanında şifreli
tutulur, Bağlantılar ekranından girilir. Böylece demo sırasında yeniden deploy gerekmez.

---

## 6. Hackathon sonrası

Bu belgede geçen tüm token'lar (Groq, Jira, Slack, GitHub PAT, Google secret) **iptal edilip
yeniden üretilmeli**. Hiçbiri repoya yazılmadı; yalnız Coolify ve veritabanında duruyorlar.
