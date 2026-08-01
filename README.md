# Relay

**Gün içinde çıkan işi araçların içinde yürüten bir ajan ekibi — ve hiçbir yazma adımını sana sormadan yapmaz.**

> Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun.

🎥 [**3 dakikalık demo videosu**](docs/assets/relay-b2b-demo.mp4) · 🌐 [relay.samedbilgin.com](https://relay.samedbilgin.com) · 📊 [Yatırımcı sunumu](docs/pitch/index.html)

> **Jüriye giriş bilgisi verilmiyor — bilinçli bir karar.** Relay tek bir ortak çalışma
> alanı üzerinde çalışıyor; verilecek her hesap kurucunun gerçek Jira'sını, Slack'ini,
> GitHub'ını ve gelen kutusunu açar. Ürünü çalışırken görmenin yolu demo videosu —
> canlı sahnede kesilmemiş, gerçek onay kapısından geçen gerçek bir akış.

---

## 60 saniyede Relay

Bir yazılım/ürün ekibini yürüten kişinin günü dört araç arasında koşuşturmakla geçiyor:
*"Sprint'teki blocker'ları çıkar → Jira'da güncelle → Slack'e özet at → takvime iliştir."*
Dört araç, on beş tık, yirmi dakika — her gün.

Bugünün iki seçeneği de yarım: **sohbet asistanları** üretir ama yapmaz; **otomasyon
araçları** (n8n, Zapier) yapar ama önceden kurulman gerekir, doğaçlama işe yaramaz.

Relay hedefi anlıyor, çalışma anında planlıyor ve **araçların içinde gerçekten
yürütüyor** — Jira'da kayıt açıyor, Slack'e yazıyor, GitHub'da yorum bırakıyor.
Tek gerçek savunulabilir farkı, tek bir cümlede:

> **Okuma otomatik çalışır. Yazma, silme ve gönderme her zaman insana sorar — bu bir
> ayar değil, ürünün varsayılanı.**

## Neden gerçek

Bu bir demo kurgusu değil, üç ay değil **48 saatte** çalışan bir ürün:

- **18 kayıtlı araç**, gerçek kimlik bilgileriyle — Jira, Slack, GitHub, Gmail, Takvim.
  Kayıtlı olmayan bir aracı model asla çağıramaz (`PolicyEngine`, bilinmeyen araç adı →
  `FORBIDDEN`).
- **Onay kapısı** — yazma riskli her araç varsayılan olarak insana sorar; parametre
  ekranda düzeltilip onaylanabilir, giden değer görülen değerdir.
- **Politika motoru** — araç başına oku/yaz/yasak, `GET/PUT /api/policies` ile açık.
- **İz kaydı** — hangi araç, hangi parametre, kim onayladı, kim neden reddetti, kaç
  token ve kaç dolara mal oldu; `Geçmiş` ekranında tek tek açılabilir.
- **Maliyet ölçümü** — adım başına token + USD; bütçe aşılınca akış durur ve sorar.
- **Üç katmanlı dil modeli zinciri** — birincil sağlayıcı günlük kotaya takılırsa
  ikinci, o da düşerse üçüncü sağlayıcı devreye giriyor; hiçbiri yoksa deterministik,
  çevrimdışı bir stub'a düşüyor — ürün asla "hiç çalışmıyor" durumuna gelmiyor.

## Neyin yok olduğunu de biliyoruz

Kurumsal-hazır **değiliz**, ve bunu sahnede söylüyoruz: SSO/SAML yok, çok kiracılılık
yok (tek ortak çalışma alanı), RBAC yok, denetim raporu ihracı yok, zamanlanmış
çalıştırma yok. 48 saatte kurumsal bir ürünün *hangi* kısmının kanıtlanmaya değer
olduğuna karar verdik: kimlik altyapısı çözülmüş bir problem, **ajana güven** değil —
onay kapısını, politika motorunu ve iz kaydını o yüzden yaptık. Ayrıntı ve gerekçe:
[docs/KONUMLANDIRMA.md](docs/KONUMLANDIRMA.md) §4.

## Dokümanlar

| Doküman | İçerik |
|---|---|
| [docs/KONUMLANDIRMA.md](docs/KONUMLANDIRMA.md) | Konumlandırma kararı, birincil kullanıcı, rakip haritası, jüri soru-cevabı |
| [docs/PRD.md](docs/PRD.md) | Problem, çekirdek döngü, entegrasyon kararları, kapsam, metrikler, riskler |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Mimari referans — jürinin soracağı sırayla, her iddia `dosya:satır` ile |
| [docs/NASIL-CALISIYOR.md](docs/NASIL-CALISIYOR.md) | Akış akış: bir hedeften bir işe kod ne yapıyor, zayıf yerleriyle birlikte |
| [docs/SORU-CEVAP.md](docs/SORU-CEVAP.md) | Jüri soru-cevap hazırlığı — en zor sorular, sahnede verilecek cevaplar |
| [docs/EKIP.md](docs/EKIP.md) | Ajan ekibi: kim, hangi yetkiyle, hangi model kademesinde |
| [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) | Her sağlayıcının kurulumu — kullanıcı tarafı |
| [docs/DEMO.md](docs/DEMO.md) | Demo senaryosu, saniye saniye |
| [docs/DESIGN.md](docs/DESIGN.md) | Tasarım dili, renk, tipografi, bileşenler |

## Stack

- **Backend:** Java 21 + Spring Boot 3, PostgreSQL (Flyway)
- **Frontend:** React + Vite + TypeScript
- **Dil modeli:** OpenAI-uyumlu üç katmanlı zincir (Gemini → DeepSeek → Groq → stub)
- **Entegrasyon:** Jira, Slack, GitHub (PAT) · Gmail, Takvim (Google OAuth)
- **Deploy:** Docker + Coolify, n11 paylaşımlı Caddy edge'i arkasında — bkz. [deploy/DEPLOY.md](deploy/DEPLOY.md)

## Kategori & durum

**Productivity & Work.** 48 saatlik hackathon, kapsam kilitli — bkz. [PRD §6](docs/PRD.md).
