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

**En az ikisi farklı göndericiden gelmeli.** Hepsini kendi adresinden kendine göndermek
kolay ve Primary sekmesine düşmeyi garantiler — ama bedeli ekranın kendi alt satırında
görünüyor: canlıda `today.lines` şunu yazdı → *"4 mail bir kişiden geldi (11 bülten ayrıldı)"*.
Jüri o satırı okur ve "bu bir test hesabı" der. **1.1'i ikinci bir gerçek adresten gönder**
(eş, iş arkadaşı, ikinci Google hesabı — `+destek` alt adresi yetmez, `From` yine sensin).
Kalanlar kendinden kendine gidebilir. Hedef satır: *"4 mail üç kişiden geldi"*.

**Hepsi Primary sekmesine düşmeli.** Tanıdık göndericiden gelen düz metin mail düşer;
şablonlu/HTML bir mail `Promosyonlar`a kayabilir — gönderdikten sonra gelen kutusunda
gözünle doğrula.

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

## 2. Jira (KAN projesi) — **ekranın en kırılgan yeri**

**Neden bu bölüm diğerlerinden önemli:** `KONUMLANDIRMA.md` §2 birincil kullanıcıyı "bir
yazılım/ürün ekibini yürüten kişi" diye tanımlıyor. O kişinin ana aracı Jira. 01 Ağu 02:29'da
canlı `GET /api/brief` şunu döndürdü:

```json
"work":{"status":"ok","reason":null,"count":0,"items":[],"provider":"jira","mode":"live"}
"today":{"counts":{"inbox":15,"work":0,"code":3,"calendar":1}}
```

Bağlantı çalışıyordu, hata yoktu — **kayıt yoktu.** `Üstümdeki işler` bölümü boş açıldı, yani
iddia edilen kullanıcının günü ekranda görünmedi. Bu bölüm bunu engellemek için var (#55).

`Bugün` ekranı bu bölümü `jira.listMyIssues` ile dolduruyor. O araç **`assignee = currentUser()`**
sorguluyor: panoda var olan bir kayıt yetmez, **sana atanmış** olması gerekir.

### 2.1 Kayıtlar — dördü de `assignee = demo hesabı`

| Anahtar | Özet | Durum | Etiket | Ne kanıtlıyor |
|---|---|---|---|---|
| `KAN-1` | `Ödeme servisi staging'de 500 dönüyor` | `Yapılacaklar` | `blocker` | Yeni iş → *Başla: In Progress yap* + *Ekibe başladığını bildir* |
| `KAN-2` | `Profil sayfası yeniden tasarımı` | `Devam Ediyor` | — | Süren iş → *İlerlemeyi kayda yaz* · **yorumları olan kayıt bu** |
| `KAN-3` | `Kargo entegrasyonu — sağlayıcı yanıt vermiyor` | `Engellendi` | `blocker` | Takılan iş → *Engeli ekibe taşı* · aciliyet **yüksek** |
| `KAN-4` | `Fatura PDF'i Türkçe karakterleri bozuyor` | `Yapılacaklar` | `blocker` | **Takılan işler** akışının JQL'i (`labels = blocker AND statusCategory != Done`) en az üç satır dönsün diye |

Anahtarlar sabit tutulur — `DEMO.md` §1'in red gerekçesi (`"KAN-3 bilerek açık kalacak"`) bu
numaraya bağlı. Kayıtları silip yeniden açarsan numaralar kayar; **durumları geri al, kaydı
silme.**

### 2.2 Yorumlar — `KAN-2`'ye tam iki tane

`Yorumları getir` eylemi (`jira.getComments`) Bugün ekranında **Jira kartlarına otomatik
ekleniyor**; boş dönerse sahnede "hiç yorum yok" yazar. `KAN-2` altına, bu sırayla:

1. `Tasarım onayı geldi, geliştirmeye başlıyorum.`
2. `Mobil görünümde başlık taşıyor — ayrı kayıt açmak yerine burada çözeceğim.`

İkinci yorum bilerek bir **karar** cümlesi: demoda "yorumları getir" tıklandığında ekranda
okunacak bir şey olsun, tarih damgası değil.

### 2.3 Durum adları

Durum adları panonun dilinde yazılır: `Yapılacaklar / Devam Ediyor / Engellendi / Tamam`.
Sistem İngilizce↔Türkçe eşleştirmesini kendi yapıyor; sen panonun kendi adlarını kullan.

### 2.4 Proje anahtarı — sessiz tuzak

`Bağlantılar` ekranındaki Jira bağlantısında **`projectKey = KAN` dolu olmalı.** Boşsa
`BRIEF_DEFAULT_PROJECT_KEY` devreye girer ve varsayılanı **`RELAY`** — canlıda tam olarak bu
oldu: `jira.createIssue` adımı *"'RELAY' anahtarlı bir proje yok"* hatasıyla düştü ve akış
hiçbir araçta iş bitirmeden kapandı. Kontrolü §5'teki `curl` çıktısında yap: öneri
parametrelerinde `"projectKey":"KAN"` görünmeli.

### 2.5 Sıfırlama (her provadan sonra)

Prova akışları `KAN-1`'i `Devam Ediyor`'a, `KAN-3`'ü `Tamam`'a taşır ve bir sonraki demoda
liste bozulur. Demodan önce:

- `KAN-1` → `Yapılacaklar`
- `KAN-3` → `Engellendi`
- Provada açılmış `KAN-6`, `KAN-7`… kayıtlarını **kapat** (silme — anahtarlar kaysın istemezsin)

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
print('counts:', t.get('counts'))
print('work:', (d.get('work') or {}).get('status'), (d.get('work') or {}).get('count'))
for c in d.get('priority', []):
    print(' •', c.get('source'), c.get('urgency'), '|', (c.get('summary') or '')[:60])
    print('    →', [a.get('label') for a in c.get('suggestedActions', [])])
print('llm:', (d.get('llm') or {}).get('provider'), '| degraded:', (d.get('llm') or {}).get('degraded'))
"
```

Şunları gör — **beşi de tutmadan sahneye çıkma:**

- **`work: ok 4`** ve `counts` içinde `"work": 4`. `work` sıfırsa Jira kayıtları sana atanmamış
  demektir (§2.1) — bu, ekranın en sık boş kalan bölümü ve iddia edilen kullanıcının
  görünmediği yer.
- **`lines` içinde "bir kişiden geldi" ifadesi geçmiyor.** Geçiyorsa §1'deki ikinci gönderici
  atlanmış demektir.
- **İlk toplantı saati demo saatinden sonra.** `lines` içindeki *"1 toplantı — ilki HH:MM"*
  satırını oku; geçmişte kalmış bir toplantı hazırlık akışını anlamsızlaştırır (§4).
- `degraded: false` — model ayakta. `true` ise öneriler sezgisel yola düşer, cümleler
  kabalaşır. Anahtar kotası bitmişse yeni anahtar ekle.
- Öncelik kartlarının önerileri **birbirinden farklı**. Hepsi aynıysa kayıtların durumları
  da aynı demektir; birini `Devam Ediyor`, birini `Engellendi` yap.
- Odak kartı müşteri maili olmalı — GitHub kaydı değil. Değilse mail çok eski kalmıştır,
  yeniden gönder.

Bir de **gözle** bakılacak tek şey: kartların *"Neden şimdi"* satırı başlığı tekrar ediyorsa
(başlık *"Kurulum notunu README'ye ekle"* → neden *"Kurulum notunun README'ye eklenmesi
gerekiyor"*) o kartı demoda açma. Model bazen boş bilgi üretiyor; düzeltmesi kod işi (#55),
sahnede yapılacak şey o kartı seçmemek.

---

## 6. Kurtarma

Demoda sağlayıcı düşerse: bölümler `bağla` kartına döner, ekran boşalmaz — bu zaten
anlatılacak bir özellik ("tek entegrasyon düşünce brifing çökmüyor").

Hepsi düşerse `TOOLS_MODE=replay` ile fixture'lardan çalışır. O modda **söyle**:
*"Şu an kayıtlı senaryodan gidiyorum"* — sahte veriyi canlı diye göstermek, demoyu
kaybetmekten kötüdür.
