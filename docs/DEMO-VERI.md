# Demo Verisi — Ekranın Ne Göstereceğini Önceden Bilmek

> Canlı veri sahnede en güçlü kanıt, ama **rastgele** canlı veri en büyük risk.
> Bu dosya, Bugün ekranının demo anında ne göstereceğini deterministik hale getirir:
> ne göndereceğin, nereye göndereceğin, ekranda ne çıkması gerektiği.

Kural: **hiçbir şey uydurulmuyor.** Bunlar gerçek maillere, gerçek Jira kayıtlarına ve
gerçek GitHub issue'larına dönüşüyor; yalnızca içeriğini biz seçiyoruz.

---

## 0. Zamanlama

- Mailleri ve kayıtları **demodan 30-60 dakika önce** oluştur. Çok erken olursa "1sa önce"
  yerine "5sa önce" yazar ve aciliyet hissi düşer.
- Bugün ekranı **3 dakika önbellekli**. Son değişiklikten sonra sahneye çıkmadan bir kez
  **Yenile**'ye bas, gördüğün ekranı demoda göreceksin.
- Takvim etkinliğini demo saatinden **sonraya** koy — "14:00 sprint planlama" geçmişte
  kalırsa hazırlık akışının anlamı kaybolur.

---

## 1. Mailler

Hepsini kendi adresinden kendine gönder. **Üçü de Primary sekmesine düşmeli**; gönderen
sen olduğun için düşer.

### 1.1 Müşteri talebi (demonun ana kartı)

| | |
|---|---|
| **Kime** | `samedbilgin322+destek@gmail.com` |
| **Konu** | `Ödeme adımında hata alıyoruz — sipariş tamamlanmıyor` |
| **Gövde** | `Merhaba, dün akşamdan beri ödeme ekranında "işlem tamamlanamadı" hatası alıyoruz. Üç farklı kartla denedik. Sipariş numarası R-44W-VG2. Acil bakabilir misiniz?` |

`+destek` alt adresi bilerek: aynı kutuya düşer ama `To` başlığında görünür. "Bu bir
müşteri talebi" kararı böylece konu satırındaki kelimeye değil, **adrese** dayanır.

**Beklenen ekran:** odak kartı · `hata bildirimi` · öneriler: *Jira kaydı aç* + *Ekibe bildir*.

### 1.2 Yanıt bekleyen iş maili

| | |
|---|---|
| **Kime** | kendine (düz adres) |
| **Konu** | `Sprint demosu için slaytları paylaşır mısın?` |
| **Gövde** | `Selam, yarınki demo öncesi slaytları görebilir miyim? Özellikle onay akışı kısmını konuşmak istiyorum.` |

**Beklenen:** `yanıt bekliyor` · öneri: *Taslak cevap yaz* (Google izni verildiyse).

### 1.3 Bülten (filtrenin kanıtı)

Yeni bir şey gönderme — gelen kutusunda zaten var (Duolingo, DEV Community, Atlassian
bildirimleri). Bunlar `Promosyonlar`/`Güncellemeler` sekmesine düştüğü için **iş
sayılmıyor**, öneri almıyor.

**Demoda söylenecek cümle:** *"On dört bülten var, hiçbiri karşınıza çıkmadı — Gmail'in
kendi kararını kullanıyoruz, konu satırındaki kelimeyi değil."*

---

## 2. Jira (KAN projesi)

Üç kayıt, **üçü de sana atanmış**, üç farklı durumda — öneriler duruma göre değiştiği için
üçü de farklı buton gösterir:

| Özet | Durum | Beklenen öneri |
|---|---|---|
| `Ödeme servisi staging'de 500 dönüyor` | Yapılacaklar | *Başla: In Progress yap* + *Ekibe başladığını bildir* |
| `Profil sayfası yeniden tasarımı` | Devam Ediyor | *İlerlemeyi kayda yaz* |
| `Kargo entegrasyonu — sağlayıcı yanıt vermiyor` | Engellendi/Blocked | *Engeli ekibe taşı* · aciliyet **yüksek** |

İkinci kayda (`Profil sayfası`) **iki yorum** ekle — "yorumları getir" eylemi boş dönmesin.

> Durum adları panonun dilinde: `Yapılacaklar / Devam Ediyor / Tamam`. Sistem
> İngilizce↔Türkçe eşleştirmesini kendi yapıyor, sen panonun kendi adlarını kullan.

---

## 3. GitHub

Kendi deponda (`issue-to-notion-demo` uygun) iki kayıt yeter:

- **Issue**, sana atanmış: `Login sonrası yönlendirme kayboluyor` → beklenen öneri: *Çözüm planını yaz*
- **Pull request**, senin açtığın: küçük bir README değişikliği yeter → beklenen öneri: *Review iste*

Elindeki eski issue'lar (`samed demo`, `weqweqw`, `dsadas`) ekranı dolduruyor ve sahnede
ciddiyeti düşürüyor. **Kapat.** Kalabalık, "bu ekran benim günüm" hissini bozuyor.

---

## 4. Takvim

Demo saatinden **2-3 saat sonrasına** tek etkinlik:

| | |
|---|---|
| **Başlık** | `Ödeme servisi — sprint planlama` |
| **Süre** | 30 dk |

Başlık bilerek Jira kayıtlarıyla aynı kelimeyi taşıyor (`Ödeme servisi`): **Toplantı öncesi
hazırlık** akışı başlıktaki ipucuyla arama yaptığı için ilgili kaydı ve maili gerçekten
bulur. "Sprint planlama" gibi jenerik bir başlık ipucu üretmez ve akış boş döner.

---

## 5. Sahneden önce doğrulama

```bash
curl -s -c /tmp/relay.jar -X POST https://relay.samedbilgin.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"...","password":"..."}' -o /dev/null

curl -s -b /tmp/relay.jar -X POST https://relay.samedbilgin.com/api/brief/refresh \
  -H 'Content-Type: application/json' -d '{}' | python3 -c "
import json,sys
d=json.load(sys.stdin)
t=d.get('today') or {}
print(t.get('headline'))
for line in t.get('lines', []): print(' ·', line)
for c in d.get('priority', []):
    print(' •', c.get('source'), c.get('urgency'), '|', (c.get('summary') or '')[:60])
    print('    →', [a.get('label') for a in c.get('suggestedActions', [])])
print('llm:', (d.get('llm') or {}).get('provider'), '| degraded:', (d.get('llm') or {}).get('degraded'))
"
```

Şunları gör:

- `degraded: false` — model ayakta. `true` ise öneriler sezgisel yola düşer, cümleler
  kabalaşır. Anahtar kotası bitmişse yeni anahtar ekle.
- Öncelik kartlarının önerileri **birbirinden farklı**. Hepsi aynıysa kayıtların durumları
  da aynı demektir; birini `Devam Ediyor`, birini `Engellendi` yap.
- Odak kartı müşteri maili olmalı — GitHub kaydı değil. Değilse mail çok eski kalmıştır,
  yeniden gönder.

---

## 6. Kurtarma

Demoda sağlayıcı düşerse: bölümler `bağla` kartına döner, ekran boşalmaz — bu zaten
anlatılacak bir özellik ("tek entegrasyon düşünce brifing çökmüyor").

Hepsi düşerse `TOOLS_MODE=replay` ile fixture'lardan çalışır. O modda **söyle**:
*"Şu an kayıtlı senaryodan gidiyorum"* — sahte veriyi canlı diye göstermek, demoyu
kaybetmekten kötüdür.
