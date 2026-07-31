# Relay — Ürün Gereksinim Dokümanı

> 48 saatlik hackathon. Kategori: **Productivity & Work** (ikincil: Customer Experience)
> Referans: [Mindra](https://mindra.co) — "delegate edebileceğin ajan ekipleri"

---

## 1. Tek cümle

**Beyaz yakalı işini sohbette anlatırsın; Relay bir iş akışı kurar, araçların arasında yürütür ve her adımı gözünün önünde yapar.**

---

## 2. Problem

Bir beyaz yakalının günü tek bir işten değil, **araçlar arası koşuşturmadan** oluşuyor:

> "Sprint'teki blocker'ları çıkar → Jira'da durumlarını güncelle → ekibe Slack'ten özet at → toplantı notunu takvime iliştir."

Dört araç, on beş tıklama, yirmi dakika. Ve bu her gün tekrar ediyor.

Mevcut AI asistanlarının ikisinden biri oluyor:
- **Sohbet asistanı** — metin üretiyor ama hiçbir şey *yapmıyor*. Kopyala-yapıştır sende.
- **Otomasyon aracı** (Zapier tarzı) — yapıyor ama önceden kurman gerekiyor; doğaçlama iş için işe yaramıyor.

---

## 3. Ürün tezi

Üç iddia:

1. **İş, sohbetle tarif edilir — akışla yürütülür.** Kullanıcı otomasyon kurmuyor, işini anlatıyor. Akışı sistem çıkarıyor.
2. **Her adım görünür.** Hangi araç, hangi parametre, hangi sonuç. Kara kutu yok. *(Mindra'nın da ayırt edici tarafı bu.)*
3. **Riskli adım onay ister.** Okuma otomatik; yazma, gönderme, silme insana sorulur.

---

## 3b. Tek asistan değil — **ekip**

Mindra'nın asıl fikri bu ve biz de onu alıyoruz: hedefi tarif edersin, sistem **doğru uzman ajanlardan bir kadro kurar**, rolleri dağıtır, aralarında koordine eder ve işi teslim eder.

| Rol | Sorumluluk |
|---|---|
| **Koordinatör** | Hedefi okur, kadroyu kurar, adımları dağıtır, sonucu birleştirir |
| **Araç uzmanı** (araç başına bir tane) | Yalnızca kendi aracını bilir: Jira uzmanı Jira'yı, Slack uzmanı Slack'i |
| **Doğrulayıcı** | Teslimden önce sonucu hedefe karşı denetler; tutmuyorsa geri gönderir |

Ajanlar arası her mesaj zaman çizelgesinde görünür — kim kime ne dedi, hangi işi devretti.

## 3c. Maliyet ve yönetişim

**Gerçek zamanlı maliyet:** her adımda harcanan token ve tahmini ücret anlık görünür; akışın toplamı üstte durur. Bir ajan bütçeyi aşarsa durur ve sorar.

**Yönetişim:** her araç için politika — `otomatik` / `onay iste` / `yasak`. Okuma otomatik, yazma onaylı, silme yasak varsayılanı. Politika ihlali denenirse adım reddedilir ve iz kaydına yazılır.

## 4. Çekirdek döngü

```
1. TARİF ET    Sohbete yazarsın
2. PLANLA      Relay numaralı bir iş akışı çıkarır — her adımda hangi araç kullanılacak
3. ONAYLA      Yazma/gönderme adımları onay bekler, okuma adımları otomatik akar
4. YÜRÜT       Ajanlar adımları işler; her araç çağrısı parametreleriyle görünür
5. DENETLE     Zaman çizelgesi kalır — ne yapıldı, neden yapıldı, sonuç ne oldu
```

---

## 5. Entegrasyonlar — maliyetine göre sıralı

| Araç | Kimlik doğrulama | Maliyet | Durum |
|---|---|---|---|
| **Jira** | API token (e-posta + token) — OAuth yok | ~1 saat | ✅ **MVP** |
| **Slack** | Bot token (`xoxb-`) | ~1 saat | ✅ **MVP** |
| **Gmail** (okuma) | Google OAuth + consent screen | 4-8 saat | ⚠️ zaman kalırsa |
| **Calendar** (okuma) | Aynı OAuth, Gmail bitince +1 saat | +1 saat | ⚠️ zaman kalırsa |
| **Ödemeler** | Stripe + uyum yüzeyi | 4-6 saat | ❌ yol haritası |

**Karar gerekçesi:** Jira ve Slack token tabanlı — OAuth dansı yok, aynı gün çalışır. Google OAuth'un onay süreci bizim kontrolümüzde değil ve hassas scope'larda test kullanıcısıyla sınırlı kalırız. Demo bu ikisiyle zaten güçlü:

> *"Sprint blocker'larını topla, Jira'da 3 ticket güncelle, ekibe Slack'ten özet at."*

---

## 6. Kapsam

### MVP — olmazsa olmaz

| ID | Özellik | Kabul kriteri |
|---|---|---|
| F-01 | Sohbet arayüzü | Serbest metin girilir, akış üretilir |
| F-02 | **İş akışı planlayıcı** | Numaralı adımlar, her adımda araç + parametre önizlemesi |
| F-03 | **Şeffaflık zaman çizelgesi** | Her araç çağrısı: ad, parametreler, süre, sonuç, hata |
| F-04 | **Onay kapısı** | Yazma/gönderme adımı onaysız çalışmaz; reddedilirse gerekçe ajana gider |
| F-05 | Jira aracı | Issue ara, oku, durum güncelle, yorum ekle |
| F-06 | Slack aracı | Kanala mesaj at, thread'e cevap ver |
| F-07 | Akış yeniden çalıştırma | Aynı akış tek tıkla tekrar koşar |
| F-08 | Bağlantı ayarları | Token'lar arayüzden girilir, şifrelenmiş saklanır |
| F-09 | **Ajan kadrosu** | Koordinatör + araç uzmanları + doğrulayıcı; ajanlar arası mesajlar görünür |
| F-10 | **Gerçek zamanlı maliyet** | Adım başına token + tahmini ücret, akış toplamı, bütçe sınırı |
| F-11 | **Araç politikası** | Araç başına otomatik/onaylı/yasak; ihlal iz kaydına yazılır |
| F-12 | **Araç kayıt defteri** | Yeni araç eklemek tek bir sınıf — "3000+ entegrasyon" iddiasının gerçek uzantı noktası |

### Zaman kalırsa

| ID | Özellik |
|---|---|
| F-20 | Gmail okuma + günün özeti |
| F-21 | Calendar okuma — bugünün toplantıları |
| F-22 | Akış şablonu kaydetme ("her sabah bunu yap") |
| F-23 | Çoklu ajan — adımları paralel dağıtma |

### Kapsam dışı — bilerek

Ödemeler · Gmail gönderme · takvim yazma · çoklu kullanıcı/takım · rol yönetimi · mobil uygulama

---

## 7. Ekranlar

| # | Ekran | İçerik |
|---|---|---|
| 1 | **Sohbet** | Ana ekran. Sol: konuşma. Sağ: canlı akış paneli |
| 2 | **Akış paneli** | Adımlar, durumları, onay butonları |
| 3 | **Adım detayı** | Araç çağrısının parametreleri ve ham sonucu |
| 4 | **Geçmiş** | Çalışmış akışlar, denetim izi |
| 5 | **Bağlantılar** | Jira/Slack token girişi, bağlantı testi |

---

## 8. Farklılaşma

| Rakip | Onlarda | Bizde |
|---|---|---|
| **Mindra** | Ajan ekibi, şeffaflık, çok kanal | Aynı şeffaflık ilkesi; biz **tek kişinin günlük işine** odaklanıyoruz, departman simülasyonuna değil |
| **Zapier / n8n** | Önceden kurulmuş otomasyon | Doğaçlama iş — kurulum yok, sohbetle tarif |
| **ChatGPT + eklentiler** | Yapıyor ama izlenemiyor | Her adım onaylanabilir ve denetlenebilir |
| **Jira/Slack'in kendi AI'ı** | Tek araç içinde kalıyor | **Araçlar arası** — asıl acı orada |

**Tek cümlelik ayrım:** Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun.

---

## 9. Başarı metrikleri

**Kuzey yıldızı:** Tarif edilen işin **insan müdahalesi olmadan tamamlanma oranı** (onay tıklamaları hariç).

| Metrik | Hedef |
|---|---|
| Sohbetten ilk akış planına | < 5 sn |
| Akış adımlarının doğru araç seçme oranı | > %85 |
| Manuel yapılsa geçecek süre / Relay ile geçen süre | ≥ 5× |

---

## 10. Riskler

| Risk | Panzehir |
|---|---|
| Jira/Slack hesabı demo günü erişilemez | Kayıtlı yanıtlarla çalışan `replay` modu; sahnede ağ ölse bile akış oynar |
| LLM yanlış araç/parametre seçiyor | Onay kapısı zaten arada; ayrıca araç şemaları katı doğrulamadan geçer |
| Token sızıntısı | Token'lar şifreli saklanır, log'a asla yazılmaz, arayüzde maskelenir |
| "Bu Zapier değil mi?" | Kurulum yok — sohbetle doğaçlama iş. Demoda bunu göster |
| Kapsam şişmesi | Ödeme ve Gmail gönderme kapsam dışı, yol haritası slaytında |
