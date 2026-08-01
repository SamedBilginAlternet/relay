# Relay — Demo Senaryosu ve Sunum Kılavuzu

> 48 saatlik hackathon · Kategori: **Productivity & Work**
> Süre: **3 dakika demo + soru-cevap**
> Bu doküman bir sahne senaryosudur: gece 3'te, uykusuz halde, kelimesi kelimesine takip edilebilecek şekilde yazıldı. Doğaçlama yapma — burada yazanı yap.

---

## 0. Tek cümle (ezberle)

> **"Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun."**

Bu cümle açılışta bir kez, kapanışta bir kez geçer. Başka hiçbir yerde geçmez; iki kez duyulunca akılda kalır.

---

## 1. Üç dakikalık demo akışı — saniye saniye

> **Bu tablo son 24 saatte baştan yazıldı.** Ürün değişti: `Bugün` ekranı bir eylem akışına
> döndü, hazır akış rafı geldi, `Postana sor` üst bardan çıktı, `Panel` ve `Politikalar`
> ekranları eklendi, onayla-ve-düzelt canlıya girdi. Aşağıdaki her satır **1 Ağustos'taki
> ekranla** karşılaştırıldı. Olmayan bir şeyi tarif eden satır bir tuzaktır; bulursan
> senaryoyu değil, satırı düzelt.

### Sahne kurulumu (demo başlamadan)

- **Sekme 1 — Relay**, `#/` (Bugün) açık, giriş yapılmış, tanıtım turu bitmiş. Oturum çerezi
  30 gün yaşıyor ama sahneye çıkmadan bir kez aç ve ekranın **dolu** geldiğini gör.
- **Sekme 2 — Slack**, bağlantıdaki varsayılan kanal açık (`#all-samed`, samedco).
- **Sekme 3 — Jira**, KAN panosu açık.
- **Sekme 4 — yedek frontend**, `VITE_RUN_SOURCE=mock` ile açılmış ve çalıştığı görülmüş.
- Başka sekme **yok**. Zoom %110, tam ekran (F11), bildirimler kapalı.
- Hedef cümlesi panoda: *"Bugünkü maillerime bak, iş talebi gibi görünenleri KAN panosuna
  kayıt olarak aç ve ilgili kanaldan ekibe haber ver."*
  (Sohbet ekranı boşken bu cümle zaten **ilk örnek** olarak duruyor — tıklaman yeter.
  `Landing.tsx` → `SUGGESTIONS[0]`. Panodaki kopya sigorta.)

**Neden Bugün'de açılıyoruz:** boş bir sohbet kutusuyla açılmak "peki ne yazacağım" sorusunu
jüriye de sordurur. `Bugün` ekranı ürünün iddiasını ilk saniyede gösterir — **sistem işi
getiriyor, kullanıcı "yap" diyor.**

**Neden akışı Bugün'deki karttan değil, cümleden başlatıyoruz:** karttaki eylem düğmesi
`POST /api/runs/from-suggestion` çağırıyor ve o uç **planlayıcıyı atlayıp tek adım
tohumluyor** (`RunService.startFromSuggestion`). Yani karttan başlatılan akışta numaralı bir
plan yok — demonun 0:42'deki "plan cümleden çıktı" anı da yok. Kart yolu ürünün en hızlı
yolu ama sahnede en zayıfı; **gösterilir, basılmaz.**

### Saniye tablosu

| Zaman | Sen ne yapıyorsun / söylüyorsun | Ekranda ne oluyor | Jüri neye bakmalı |
|---|---|---|---|
| **0:00–0:18** | Açılış konuşmasının ilk yarısı (§2). Hiçbir şeye dokunma. | **Bugün** ekranı: başlık `Bugün · <gün ay>`, altında günün sayımı (*"Bugün 7 iş seni bekliyor, 1 tanesi acil · 1 toplantı."*) ve günün özeti. Ortada **`Yapılacak işler`** listesi: her satırda kaynak rozeti (`E-posta` / `Jira` / `GitHub`), acil olanlarda `Acil` çipi, başlık, "neden şimdi" satırı ve **tek bir eylem düğmesi**. En altta `Hazır akışlar` rafı. | Ürün açılır açılmaz dolu. Kurulum ekranı yok, boş kutu yok. |
| **0:18–0:32** | *"Bunları ben yazmadım — Relay sabah dört aracı okudu ve günü buraya çıkardı."* İmleçle bir satırı göster; düğmesini oku, **BASMA**. Sonra: *"Ve bunlar öneri, eylem değil — ben basmadan hiçbir şey çalışmıyor."* | Satırın düğmesinde işin adı yazıyor: ör. `Jira kaydı aç`, `Engeli ekibe taşı`, `Taslak cevap yaz`. Ekranın kendi satırı da bunu söylüyor: *"Öneriye basmadan hiçbir şey çalışmaz."* | **Sistem işi getiriyor.** Ve getirdiği şey bir buton, bir otomasyon değil. |
| **0:32–0:42** | *"Ama bugün size listeden değil, sıfırdan bir iş vereceğim."* Üst bardan **Sohbet**'e geç, duran ilk örnek cümleye bas (ya da panodan yapıştır) → **Gönder**. | Sohbet ekranı ikiye bölünür: **solda konuşma** (senin cümlen mor balonda), **sağda akış paneli** — üstte maliyet şeridi (`TOKEN` · `TAHMİNİ ÜCRET` · `BÜTÇE $0.50`), altında `Akış` durumu. | Kurulum yok, form yok, node bağlamak yok. Sadece bir cümle. |
| **0:42–0:57** | *"Relay bu cümleden numaralı bir iş akışı çıkardı — hangi ajan, hangi araç, hangi parametre."* İmleçle plan adımlarını yukarıdan aşağı gez. | Plan ~5 sn'de gelir: numaralı adımlar, her satırda araç adı (mono: `gmail.listToday`, `jira.createIssue`, `slack.postMessage`) ve **OKUMA/YAZMA** rozeti. | **Şeffaflık #1:** hiçbir şey çalışmadan önce tüm plan ortada. Ve plan **şimdi** çıktı — tasarım zamanında değil. |
| **0:57–1:12** | *"Okuma adımları güvenli — otomatik akıyor."* Hiçbir şeye basma. 1. adıma tıkla, parametre/sonuç bloğunu 2 sn göster, kapat. | `gmail.listToday` kendiliğinden koşar. Sol sütunda ajanlar arası satırlar akar (`coordinator → gmail-agent` gibi). Maliyet şeridi token ve USD saymaya başlar. | **Ekip çalışıyor:** kim kime ne dedi, satır satır. Ve her adımın parası ekranda. |
| **1:12–1:30** | Ses tonunu düşür, yavaşla: *"Ve fark burada. Sıradaki adım Jira'ya **yazacak**. Relay yazma adımını onaysız ÇALIŞTIRMAZ. Durdu, bana soruyor."* | `jira.createIssue` adımı bekleme rengine döner; satırda **Onayla** / **Reddet** düğmeleri ve şu not: *"Yazma adımı — çalışması için onayın gerekiyor."* Parametreler açık ve **düzenlenebilir alanlar** hâlinde: `projectKey`, `summary`, `description`. | **FARKLILAŞTIRICI AN.** Sistem güçlü ama zincirli. Bu cümleyi aynen söyle: *yazma adımı onaysız çalışmaz.* |
| **1:30–1:52** | *"Katılmıyorum diyelim — ama iptal etmeyeceğim, düzelteceğim."* `summary` alanını ekranda değiştir (ör. *"Yeni iş talebi"* → *"Ödeme hatası: sipariş tamamlanmıyor"*). Sonra **Düzelt ve onayla**'ya bas. | Alanı değiştirdiğin an düğme **`Düzelt ve onayla`**'ya döner ve altta uyarı çıkar: *"Değiştirdiğin değer olduğu gibi gönderilir — iz kaydına eski ve yeni hâliyle düşer."* Sol sütunda satır belirir: **Parametre kullanıcı tarafından düzenlendi (sen) — summary: "…" → "…"**. Adım düzeltilmiş değerle koşar. | **Gönderilen değer, senin gördüğün değer.** Kapı ikili değil: "olmasın" ile "şöyle olsun" arasında fark var — ve fark iz kaydında, kim yaptığıyla birlikte. |
| **1:52–2:12** | Jira sekmesine geç, az açılan kaydı panoda göster, dön. *"Bu mock değil."* | KAN panosunda kayıt gerçekten duruyor, senin yazdığın başlıkla. | **Gerçek Jira.** Sekmede 8 saniyeden fazla kalma. |
| **2:12–2:32** | *"Son adım ekibe haber vermek. Bu da bir yazma — yine soruyor. Ve bu kez reddedeceğim."* Mesaj önizlemesini yarım cümle oku, **Reddet**'e bas, gerekçe: **"Kanal yanlış, bunu müşteri ekibi görmemeli."** | `slack.postMessage` onay kapısında bekliyordu; **Reddet**'e basınca gerekçe kutusu açılır (*"Neden reddediyorsun? (ajana geri gider)"*), `Gönder` ile adım kırmızı **reddedildi** olur ve **orada biter**. Sol sütunda: *Reddedildi (sen): Kanal yanlış…* | **Red kesin bir karar.** Sistem onu "yeniden dene" diye yorumlamıyor — ve bu bilinçli (bkz. §3, "revizyon" sorusu). |
| **2:32–2:45** | *"İki yazma adımı, iki farklı cevap: birini düzelttim, birini durdurdum. İkisi de kayıtta."* Maliyet şeridini göster. | Akış kapanır. Maliyet şeridinde toplam: **~X.XXX token · $0.0X**, `%N kullanıldı`. | Kapı ikili değil üçlü: onayla · düzelt ve onayla · reddet. Ve toplam maliyet kuruşu kuruşuna ekranda. |
| **2:45–3:00** | **Geçmiş** sekmesine tıkla, biten akışı aç. *"Her şey burada: her araç çağrısı, parametresi, süresi, düzeltmem ve reddim gerekçesiyle. Denetlenebilir iş."* Kapanış cümlesi (§2). | `Geçmiş` → akış → `Denetim izi · <tarih>`. Adımı aç: `Durum`, `Karar: kullanıcı onayladı` / `kullanıcı reddetti` / `otomatik (politika: auto)`, `Parametreler`, `Sonuç`, `Reddetme gerekçesi`, token/USD. Solda kimin karar verdiği yazılı. | Kara kutu yok. "Kim, neyi, neden değiştirdi" bir tık uzakta. |

### Bu tabloda bilerek OLMAYAN şeyler

Eskiden buradaydılar; ekranda karşılıkları olmadığı için çıkarıldılar:

- **"Doğrulayıcı → Koordinatör" satırını gösterme.** Doğrulayıcı gerçekten çalışıyor ve
  mesajını yazıyor (*"Adım N doğrulandı: …"*), ama arayüz ajan adlarını **çevirmiyor**:
  canlı modda ekranda `verifier → coordinator`, `coordinator → jira-agent` yazıyor. Türkçe
  roller yalnız mock modda var (`mockScript.ts`). Doğrulayıcıdan **söz et**, satırı işaret
  etme; jüri ekrandaki İngilizce id'yi okur ve soru sorar.
- **Kanal alanını düzenleme.** Eski betik `#all-samed` → `#dev-sprint` diyordu. `paramsLocked`
  sayesinde senin yazdığın kanal aynen gider — **var olmayan bir kanala da gider** ve Slack
  `channel_not_found` döndürür; adım hata verir, sahnede toparlanmaz. Düzeltme sahnesi bu
  yüzden `summary` alanına taşındı. Kanalı değiştireceksen §5 madde 5'teki **ikinci gerçek
  kanal** kurulu olmalı.
- **`Postana sor` ekranı.** Üst bardan çıktı (karar #59), hesap menüsünde. Demoda açılmaz.
- **`Panel` ve `Politikalar` sekmeleri.** Üç dakikaya sığmıyorlar ve soru-cevapta daha
  değerliler: politika sorusu gelirse `#/politikalar`, ölçüm sorusu gelirse `#/panel` açılır
  (§3). Demoda tıklanmaz.
- **"Akışı durdur".** Var, ama yalnız `Geçmiş` → akış detayında (`Durdur`). Sohbet ekranında
  iptal düğmesi **yok**; "istersem durdururum" deyip Sohbet ekranını gösterme.

### Zamanlama kuralları

- 1:12'deki onay anı demonun kalbi — orada **acele etme**, gerekirse 1:52'deki Jira
  sekmesinden kıs.
- Plan 5 sn'de gelmezse doldurma cümlen hazır: *"Groq'ta Llama koşuyor — normalde bu iki
  saniye."* 10 sn'yi geçerse §4'teki B planına geç, özür dileme.
- Sekme geçişleri (Jira, Slack) toplam 8 saniyeyi geçmesin; orada konuşma, göster ve dön.
- **Sigorta:** bir entegrasyon (Gmail/GitHub) cevap vermezse `Bugün` ekranı boşalmaz — o
  bölüm "bağla" kartına döner, diğerleri gelir. Jüri sorarsa: *"Kısmi başarı döndürüyoruz;
  tek bir sağlayıcı düşünce brifing çökmüyor."* Hiçbiri gelmezse `TOOLS_MODE=replay` (§4).

---

## 2. Açılış konuşması (30 sn) ve kapanış

### Açılış — kelimesi kelimesine

> "Bir ekibi yürüten kişinin günü tek bir işten değil, araçlar arası koşuşturmadan oluşuyor: mailden çıkar, Jira'ya gir, Slack'te raporla. Dört araç, on beş tıklama, yirmi dakika — her gün. Sohbet asistanları metin üretiyor ama hiçbir şey *yapmıyor*; otomasyon araçları yapıyor ama önce saatlerce kurman gerekiyor. Relay üçüncü yol: işini bir cümleyle anlatıyorsun, Relay uzman ajanlardan bir ekip kuruyor — gün içinde çıkan işi araçların içinde yürüten bir ajan ekibi, ve hiçbir yazma adımını sana sormadan yapmaz. **Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun.** Gösterelim."

(≈30 sn. "Gösterelim" dediğin an yazmaya başla — boşluk bırakma.)

### Kapanış — kelimesi kelimesine

> "Üç dakikada: bir cümle verdim, bir ekip kuruldu, Jira'da kayıt açıldı — başlığını ben düzelttim, ekibe gidecek mesajı ise durdurdum. İkisi de gerekçesiyle denetim izinde. **Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun.** Biz Relay'iz. Teşekkürler."

---

## 3. Jüri soruları ve cevapları

Cevaplar 15 saniyeyi geçmeyecek şekilde yazıldı. İlk cümle her zaman doğrudan cevap; gerekçe sonra.

**"Zapier'den farkı ne?"**
> Zapier'de işi *önceden* kurarsın; kurmadığın iş yapılamaz. Relay'de işi *o an* tarif edersin — plan cümlenden çıkarılır. Zapier tekrarlayan boru hatları için; Relay bugünün doğaçlama işi için. Ayrıca Zapier zap'i kara kutuda koşturur; bizde her araç çağrısı parametresiyle görünür ve yazma adımı sana sorar.

**"Bu herkes için mi, yoksa yazılım ekipleri için mi?"**
> Ekranda gördüğünüz üç kaynak bizim demo hesabımızın verisi — ürünün kısıtı değil. Hazır
> akışların ilki *"Maili işe çevir"*: gelen bir talebi okur, kayda çevirir, ilgili ekibe
> haber verir. Bu akışta Jira'nın yerinde ne varsa o çalışır. Yazma yüzeyimiz bugün Jira,
> Slack ve GitHub — çünkü işin **bittiği** yer orası; mail ve takvim okunuyor. Bir ekibi
> yürüten kişinin günü, o kişi yazılımcı olsun ya da olmasın.

**"n8n'den farkı ne?"** (Zapier'den ayrı bir soru — n8n'i bilen jüri üyesi teknik cevap bekler)
> n8n'de akışı önceden kurarsın; kurmadığın iş yapılamaz. Relay'de işi o an tarif edersin, plan cümleden çıkar. Asıl fark şu: n8n bir akışı baştan sona koşturur, biz her **yazma** adımında duruyoruz — kim onayladı, neden reddetti, ne kadara mal oldu, hepsi iz kaydında. n8n tekrarlayan boru hatları için; Relay bugünün doğaçlama işi ve onun denetimi için.

Üstelerse üç ayrım: **ne zaman tasarlanıyor** (n8n tasarım zamanı, biz çalışma zamanı) · **kimin izni var** (n8n'de kimlik akışa verilir ve akış onunla ne yaparsa yapar; bizde her araç risk seviyesiyle kayıtlı — okuma otomatik, yazma onaylı) · **sonra ne kanıtlayabiliyorsun** (araç, parametre, süre, onaylayan, token, dolar).

**"n8n'de de AI Agent node var"** — en keskin versiyonu, hazırlıklı ol:
> Doğru, ve o node araçlarıyla sonuna kadar koşar. Bizim iddiamız ajan çalıştırmak değil, **ajanı durdurabilmek**. Az önce gördüğünüz onay kapısı bunun bir örneği; ikincisi ekranda görünmedi ama sistemin içinde: model bir kayıt anahtarını uydurduğunda yazma adımı sağlayıcıya hiç gitmiyor, plana önce o kaydı bulan bir arama adımı ekleniyor. İsterseniz canlı gösterebilirim.

> **Not:** Bu cevap "demoda gördünüz" diye başlıyordu; §1'in akışında öyle bir an yok. Söylemediğin şeyi gösterdim demek, jürinin sorabileceği tek soruyla çöker. Göstermek istersen §1'e 20 saniyelik bir sahne eklemek gerekir: hedef cümlesinde kayıt anahtarı geçmeyen bir yazma isteği ver ("bu hatayı kapat"), plan onarımı ekranda olsun.

Bitirirken tamamlayıcı ol, jüri bunu sever: *"n8n kullanan bir ekip Relay'i onun üstüne koyar — n8n gecelik boru hattını çalıştırır, Relay gün içinde çıkan işi yapar ve kimin neyi neden onayladığını kaydeder."*
**Asla deme:** "n8n'i değiştiririz" (400+ entegrasyon, zamanlayıcı, retry altyapısı — o savaş kaybedilir) · "bizde de node editörü olacak" (ürünün tezinin tersi).

**"Mindra zaten var, siz ne katıyorsunuz?"**
> Mindra'nın tezini doğru buluyoruz ve saklamıyoruz — referansımız o. Fark odak: Mindra departman ölçeğinde ajan ekipleri simüle ediyor; biz aynı şeffaflık ve ekip modelini **tek kişinin günlük işine** indiriyoruz. Kurulum beş dakika: iki token gir, çalış. 48 saatte de bunu uçtan uca canlı çalışır hale getirdik — bu masada Jira ve Slack gerçek.

**"LLM yanlış parametre üretirse?"**
> Üç kademe var. Bir: her aracın JSON Şeması var, parametreler çalıştırılmadan önce katı doğrulamadan geçer — şemaya uymayan çağrı hiç koşmaz. İki: yazma adımları zaten onay kapısında, parametreleri gözünle görüyorsun. Üç: Doğrulayıcı ajan sonucu hedefe karşı denetler, tutmuyorsa adımı geri gönderir. Yanlış parametrenin canlıya değme yolu yok.

**"Token'larımız güvende mi?"**
> Evet, üç katman: token'lar veritabanında **AES-GCM ile şifreli** durur, anahtar ortam değişkeninde; **log'a asla yazılmaz**; API yanıtlarında ve arayüzde **maskeli** döner — `xoxb-****1234` gibi. Düz metin token yalnızca araç çağrısının kendisinde, bellek içinde var olur.

**"Biz de deneyebilir miyiz? Bir hesap verir misiniz?"** — **karar verildi, ezberle**
> Şu an veremem, ve nedeni ürünün kendi kısıtı: Relay bugün **tek bir ortak çalışma alanı** — giriş yapan herkes aynı bağlantıları ve aynı koşuları görür. Yani size vereceğim hesap, benim gerçek gelen kutumu ve bağlantılarımı açar. Kullanıcı başına izolasyonu bilerek sonraya bıraktık (48 saatte yarım yapılırsa izolasyonsuzluktan tehlikeli olur). Ekranda merak ettiğiniz **her şeyi buradan, benim ekranımdan** açarım — hangi ekranı isterseniz. Değerlendirme için hesap gerekiyorsa, boş bağlantılarla ve kayıtlı yanıtlarla koşan ayrı bir adres kurarız.

**Bu cevabın kuralı:** jüriye giriş bilgisi **verilmez** — Slack'ten, e-postadan, sunum sonrası
sohbetten de verilmez. "Bir bakayım" diyen değerlendirici, senin makinende bakar. Bu bir
güvenlik tercihi değil, bir **veri sızıntısının** kapatılması: `qa+relay@` hesabı ile girildiğinde
`GET /api/connections` kayıtlı sağlayıcıların tamamını, `GET /api/brief` kurucunun gerçek gelen kutusunu
döndürüyor (#53). Ayrı bir değerlendirme ortamı isteniyorsa `deploy/DEPLOY.md` §13'teki
`TOOLS_MODE=replay` taslağı izlenir — sunumdan sonra, sunumdan önce değil.

**"Maliyet kontrolü nasıl?"**
> Her adım token ve tahmini USD döndürür, akışın toplamı maliyet şeridinde canlı sayar — demoda gördünüz. Akışa bütçe sınırı koyarsınız; ajan sınırı aşarsa **durur ve sorar**, sessizce harcamaz. Adım bazlı kırılım denetim izinde kalır — hangi adım kaça mal oldu, sonradan da görürsünüz.

**"Yeni entegrasyon eklemek ne kadar iş?"**
> Tek sınıf. `Tool` arayüzü beş üyeli: ad, açıklama, JSON şeması, risk seviyesi, execute. Sınıfı yazınca Spring otomatik toplar, LLM araç listesinde görür, politika motoru risk seviyesine göre varsayılanı atar — okuma otomatik, yazma onaylı. Orkestratöre tek satır dokunulmaz. "3000+ entegrasyon" iddiasının gerçek uzantı noktası bu.

**"Ajan döngüsü nerede koşuyor?"**
> Kendi backend'imizde — Java 21 + Spring Boot, hazır ajan framework'ü yok. Döngü dört rol: **Planner** hedefi adımlara çevirir, **Koordinatör** her adımı ilgili araç uzmanına devreder ve politikayı uygular, **ToolAgent** parametreleri kesinleştirip aracı çağırır, **Verifier** sonucu hedefe karşı denetler. Her geçiş bir olay üretir ve SSE ile arayüze akar — o yüzden her şeyi canlı gördünüz.

**"İnternet ya da hesaplarınız ölürse?"**
> Çift sigorta. Birinci kademe: `TOOLS_MODE=replay` — araç çağrıları kayıtlı gerçek yanıtlarla oynar, ağ gerekmez; onay kapısı ve akış birebir aynı çalışır. İkinci kademe: frontend mock modu — backend bile ölse arayüz senaryoyu uçtan uca oynatır. Groq tarafında da çoklu anahtar rotasyonu var; hepsi tükenirse deterministik stub'a düşer — akış koşmaya devam eder, yalnız günün özeti ve öneri cümleleri kabalaşır. Demo hiçbir tekil noktaya bağlı değil.

> **Bu cevapta bir zamanlar fazlası vardı:** *"…ve arayüz bunu açıkça söyler."* Söylemiyor.
> `degraded` bilgisi `/api/health/details` ve brief yanıtında var ama **hiçbir ekranda
> gösterilmiyor** — "sınırlı mod rozeti" diye bir şey yok. Olmayan bir rozeti göstermeyi
> vaat etme.

**"Reddettiğinizde ajan adımı düzeltip tekrar sormuyor mu?"**
> Hayır, ve bu bilinçli. Red bizde **kesin** bir karar: adım orada biter, gerekçe iz kaydına ve sonraki adımların bağlamına girer. "Şöyle olsun" demek isteyen kullanıcı reddetmiyor zaten — alanı ekranda düzeltip onaylıyor, az önce gördünüz. Reddi bir revizyon döngüsüne çevirmek "hiç yapma" demenin yolunu kapatırdı; kararın gerekçesi `SIRADAKI-FIKIRLER.md` §4.5'te yazılı.

### Panel ekranından okunan sorular

`#/panel` jüriye açık bir sekme ve rakamları biz koyduk. Bu iki soru **gelecek**; hazırlıksız
yakalanmak, kötü rakamdan daha pahalı.

**"Panelinizde 106 akışın 50'si tamamlanmış. Akışlarınızın yarısı bitmiyor mu?"**
> Doğru okudunuz, ve rakamın çoğu bizim tanımımız: 29 akış **onay bekliyor** — çünkü kapıyı geçmek için insan gerekiyor, ve o insan o gün geri dönmediyse akış orada durur. Bu bizim yarım kalmış işimiz değil, ürünün varsayılanı; sessizce tamamlanan bir akış bizim için başarısızlık olurdu. Geriye 23 hata kalıyor ve onları savunmuyoruz: çoğu 48 saatlik entegrasyon işi — yanlış proje anahtarı, kanala davet edilmemiş bot. Ölçtüğümüz şey de bu zaten; ölçmeseydik bu ekran olmazdı.

**"Onay oranınız %85. Adımların yarısında duruyorsunuz ve insan on kararın sekizinde 'tamam' diyor — bu kapı lastik damga değil mi?"**
> Kapının değeri ortalamada değil, kuyrukta. Yüz kararın seksen beşinde insan "evet" diyor, evet — ama kalanında durduğu şey **yanlış projeye açılacak bir kayıt ya da yanlış kanala gidecek bir mesaj**, ve o mesaj gönderildikten sonra geri alınmıyor. Sigortayı yılda kaç kez attığıyla değerlendirmezsiniz. Ayrıca cevap ikili de değil, üçlü: panel **onaylandı / düzeltilip onaylandı / reddedildi** diye ayırıyor — kapının asıl işi durdurmak değil, **gönderilen değeri insanın gördüğü değere eşitlemek**.

> **Oranın kendisi hakkında:** rakam ekrandan okunur, ezberden değil — QA akışları her gün değiştiriyor. Ve payda #54'ten sonra daha dar: durdurulan akışların adımları artık "red" sayılmıyor, yani oran **yükseldi** (%68 → %85). Bunu kendimiz söyleyin; jüri farkı bulursa savunma olur, biz söylersek ölçüm olur.

**Üstelerse — "peki kaç kere düzelttiniz?"**
> Panelde yazıyor, ekrandan oku. Bugünkü hafta için rakam küçük — 62 kararın 1'i düzeltilerek onaylandı — ve **büyütme**. Doğru cümle şu: ayrımı artık ölçüyoruz, hacmi henüz küçük; üstelik bu haftaki düzeltmelerin çoğu sonradan **durdurulan** akışlarda kaldı, o yüzden karar olarak sayılmıyor. "İz kaydında satır satır duruyor, panelde de toplanıyor, ama bu hafta bir tane" cevabı dürüst ve yeterli.

**"$0.05 için bütçe motoru mu yazdınız?"**
> Bugünkü ölçekte evet, gereksiz. Bütçe kapısı LLM maliyeti için değil, **ölçüm için** var: adım başına token ve dolar zaten hesaplanıyor, tavan onun bir satırlık sonucu. Ve tavanın anlamı fiyat değil, davranış: sınıra gelen ajan sessizce harcamaya devam etmiyor, durup soruyor — bunu bir kuruşta kanıtlamak, bin dolarda kanıtlamaktan ucuz.

**Söylenmeyecek:** *"o rakam test verisi"* — 106 akışın içinde jüriye gösterdiğimiz akış da var.
Rakamı sahiplen; ölçtüğün bir sayıyı reddetmek, ölçüm iddiasını da düşürür.

**Yedek sorular (gelirse):**
- *"Neden Groq?"* → Hız: plan 5 saniyenin altında gelmeli, Groq'un çıkarımı bunu sağlıyor. `LlmClient` arayüz — sağlayıcı tek sınıfla değişir.
- *"Silme işlemleri?"* → **Bugün silen tek bir aracımız yok** — 18 aracın 12'si okuma, 6'sı yazma, sıfırı silme. Motorda `DESTRUCTIVE` → `yasak` kuralı duruyor ve `Politikalar` ekranı bunu açıkça yazıyor: *"şu an kayıtlı hiçbir aracın riski silme değil."* Kuralı önce yazdık, öznesini bilerek eklemedik: 48 saatte geri alınamayan bir işlem eklemek, onay kapısının test edilmediği tek yerdir. **Üçüncü ayağı slogana çevirme** — jüri "hangi silme aracınız var?" der ve cevap "hiçbiri" olur; o an cümle bir slogan gibi duyulur. İkili kur: okuma otomatik, yazma onaylı.
- *"İş modeli?"* → Koltuk başına abonelik + kullanım (LLM maliyeti zaten adım başına ölçülüyor — faturalama altyapısı bedavaya çıktı). Kurumsal katman: politika ve denetim izi zorunlulukları.

---

## 4. Risk matrisi ve sahne sigortaları

### ALTIN KURAL

> **Asla canlı yazma adımını onaysız gösterme.** Demo öncesi `#/politikalar` ekranını aç ve **`yazma` riskli araçların hepsinde** `Onay ister` yazdığını gör — bugün `jira.createIssue`, `jira.updateIssue`, `jira.addComment`, `slack.postMessage`, `github.addComment`, `github.createIssue`, `gmail.createDraft`, `calendar.createEvent`, `sheets.appendRow`, `docs.createDocument`, `notion.createPage`, `notion.appendToPage`, `confluence.createPage`; sayıyı elle sayma, ekran filtreliyor. Biri `Otomatik`e alınmışsa demoya başlama, önce düzelt — ekran bunu "N araç varsayılanından farklı çalışıyor" satırıyla zaten söylüyor. Onay kapısı ürünün tezi; kapısız bir yazma adımı tezle çelişir ve tek karede tüm hikâyeyi çökertir.

### Risk matrisi

| Risk | Nasıl anlarsın | Anında yapılacak | Söylenecek cümle |
|---|---|---|---|
| **Salon ağı ölür / Wi-Fi yok** | Plan gelmiyor, araç çağrıları timeout | Yedek terminalde backend'i `TOOLS_MODE=replay` ile yeniden başlat (hazır komut, bkz. §5 madde 12). Telefon hotspot'u yalnızca Groq için yeter. | *"Kayıt modundan devam ediyorum — akış, onay kapısı, her şey birebir aynı; yalnızca araç yanıtları önceden kaydedilmiş gerçek yanıtlar."* |
| **Groq anahtarları tükenir (429)** | Plan gecikir; rotasyon otomatik devreye girer | Hiçbir şey — rotasyon sıradaki anahtara kendisi geçer. Tüm anahtarlar biterse stub'a düşer: akış koşmaya devam eder, günün özeti ve öneri cümleleri kabalaşır. **Arayüzde bunu söyleyen bir rozet YOK** — fark ederse sen söyle. | *"Model kotası doldu, deterministik yedeğe düştük — akış ve onay kapısı aynı çalışıyor, yalnız cümleler artık modelden gelmiyor."* |
| **Jira/Slack auth patlar** (token iptal, rate limit) | Bağlantı testi kırmızı / araç adımı hata | Replay moduna geç (ağ ölümüyle aynı prosedür). Jira/Slack sekme geçişlerini demodan çıkar. | Aynı replay cümlesi. Sekmeleri özleme — kimse yokluğunu fark etmez. |
| **Backend tümden çöker** | SSE kopuk, sayfa boş | Son çare: `VITE_RUN_SOURCE=mock` ile açılmış yedek frontend sekmesine geç (önceden açık ve test edilmiş duracak). | Mock olduğunu **söyle**: *"Bu kayıttan oynayan arayüz — canlısını sunum sonrası masamda gösteririm."* Dürüstlük puan kaybettirmez, yakalanmak kaybettirir. |
| **Projektör/HDMI arızası** | Görüntü yok | 1) Yedek USB-C→HDMI adaptörü. 2) Çözünürlüğü 1920×1080'e sabitle (yansıtma modunda kayan çözünürlük ekranı bozar). 3) Hiçbiri olmazsa telefondaki demo videosu jüriye elden. | *"Ekranı toparlarken ürünü anlatayım…"* — açılış konuşması ekransız da ayakta durur. |
| **Onay anında yanlış tıklama** (Reddet yerine Onayla vb.) | Adım beklenmedik ilerler | Panikleme. Akış zaten doğru şeyi yapıyor. Demoda iki yazma adımı var: birini kaçırdıysan diğerinde telafi et. Akışı tümden durdurmak istersen tek yol `Geçmiş` → akış → **Durdur**; Sohbet ekranında iptal düğmesi yok. | *"Onayladım — reddi bir sonraki yazma adımında göstereyim."* |
| **`Bugün` ekranı boş açılır** (Jira kayıtları sana atanmamış, mail eski) | Açılışta `Yapılacak işler` listesi boş ya da tek satır | Sahnede kurtarılmaz — bu madde **demo öncesi** çözülür (§5 madde 4, `DEMO-VERI.md` §2). Yakalanırsan Bugün'de oyalanma, doğrudan Sohbet'e geç ve cümleyi yaz. | *"Bugün ekranını geçiyorum, işi doğrudan tarif edeyim."* Boş ekranı anlatmaya çalışma. |
| **Slack mesajı yanlış kanala düşer** | Beklenen kanalda mesaj yok | Kapı zaten parametreyi gösteriyor — onaylamadan önce kanalı GÖZÜNLE oku. Ajan da bir kez düzeltiyor: hedef cümlede geçmeyen bir kanal adı, bağlantıdaki varsayılana çevriliyor ve bunu sol sütuna yazıyor (*"Adres doğrulanamadı (#…), bağlantıdaki varsayılana çevrildi: #all-samed"*). **Ama sen alanı elle düzenlediysen o düzeltme devre dışı** (`paramsLocked`) — yazdığın kanal aynen gider, yoksa adım hata verir. | *"Onaylamadan önce kanala baktım — kapı tam bunun için var."* |

### Prova kuralı

Tam prova en az **3 kez**: bir kez canlı modda, bir kez `TOOLS_MODE=replay` ile, bir kez mock frontend ile. Üç modda da 3 dakikayı tutturduysan hazırsın. Replay provası yapmadan sahneye çıkma — sigortayı ilk kez yangında test etmezsin.

---

## 5. Demo öncesi kontrol listesi

Demodan **30 dakika önce** başla; her maddeyi sırayla işaretle.

0. ☐ **Jüriye giriş bilgisi verilmeyecek** — karar verildi (#53), tartışmaya açık değil.
   Slaytta, README'de, teslim formunda, Slack mesajında **hiçbir hesap ve parola geçmiyor**;
   `qa+relay@samedbilgin.com` dahil. Kontrol: teslim metnini aç, `@` ve "parola/password"
   ara, çıkan her satırı sil. Gerekçe: Relay tek ortak çalışma alanı — verilen her hesap
   kurucunun gerçek gelen kutusunu, Jira'sını, Slack'ini ve GitHub'ını açar
   (`GET /api/connections` kayıtlı sağlayıcıların tamamını döner). "Biz deneyebilir miyiz" cevabı §3'te
   yazılı ve ezberde. Değerlendirme ortamı istenirse `deploy/DEPLOY.md` §13.
1. ☐ Backend ayakta: `GET /api/health` → `{status: ok}` dönüyor; sağlayıcı için oturumlu `GET /api/health/details` → `llm.provider: groq`.
2. ☐ **Bağlantılar** ekranında Jira ve Slack token'ları girili; her ikisinde **Bağlantıyı Test Et** yeşil.
3. ☐ **`#/politikalar`** açıldı ve `yazma` riskli araçların hepsi `Onay ister`: `jira.createIssue`, `jira.updateIssue`, `jira.addComment`, `slack.postMessage`, `github.addComment`, `github.createIssue`, `gmail.createDraft`, `calendar.createEvent`, `sheets.appendRow`, `docs.createDocument`, `notion.createPage`, `notion.appendToPage`, `confluence.createPage`. Okuma araçları `Otomatik`. Ekranın üstündeki "N araç varsayılanından farklı çalışıyor" uyarısı **görünmüyor**. (ALTIN KURAL — §4.)
4. ☐ **Demo verisi kurulu ve doğrulandı: [`DEMO-VERI.md`](DEMO-VERI.md).** Tek maddede özeti:
   `KAN-1..4` **sana atanmış** ve durumları sıfırlanmış (§2.1, §2.5), `KAN-2`'de iki yorum var
   (§2.2), Jira bağlantısında `projectKey = KAN` (§2.4), mailler en az **iki ayrı
   göndericiden** (§1), takvim etkinliği **demo saatinden sonra** (§4).
   Doğrulama tek komut — `DEMO-VERI.md` §5'teki `curl`. Şu üçü tutmadan devam etme:
   `work: ok 4` · `lines` içinde "bir kişiden geldi" **yok** · ilk toplantı saati demodan sonra.
   Bu madde atlanırsa `Bugün` ekranında **Üstümdeki işler boş açılır** — canlıda bir kez oldu (#55).
5. ☐ Slack: `relay` botu **bağlantıdaki varsayılan kanala** davetli (`/invite @relay`) ve `Bağlantılar` ekranındaki `defaultChannel` o kanal — bugün `#all-samed`. Kanala test mesajı atıp silindi.
5b. ☐ **Kanal alanını sahnede düzenleyeceksen** ikinci bir kanal gerçekten var ve bot ona da davetli. Sebep: parametreyi elle düzenlediğin an ajanın adres düzeltmesi devre dışı kalıyor (`paramsLocked`) ve yazdığın kanal aynen Slack'e gidiyor — olmayan bir kanal `channel_not_found` ile adımı düşürür. Düzenleme sahnesi bu yüzden varsayılan olarak `summary` alanında (§1, 1:30).
6. ☐ Groq: `GROQ_API_KEYS` en az 3 anahtar içeriyor; her biri bugün tek istekle doğrulandı.
7. ☐ Akış bütçesi ayarlı (ör. $0.50) — maliyet şeridi ve bütçe davranışı anlatılabilir durumda.
8. ☐ **Geçmiş** temiz: eski deneme akışları silindi ya da en üstte düzgün bir prova akışı duruyor (jüri Geçmiş'te çöp görmesin).
8b. ☐ **Panel** temiz: `#/panel` → `Son 7 gün` açıldığında **Red gerekçeleri** listesindeki satırlar okunabilir ve iş anlamı taşıyor. `akış iptal edildi (…)` satırları artık bu listede değil — kendi bloğunda, "Durdurulan akışlarda kapanan adımlar" (#54). Geriye kalan tek sorun QA test dizeleri (`"QA testi — mesaj gonderme…"`): hâlâ oradaysa jüri kapının değerini onlardan okuyacak. Pratikte: demodan önce **2–3 gerçek red üret** — bir kanal düzeltmesi, bir başlık düzeltmesi — ki listenin en üstünde gerçek gerekçeler dursun. Onay oranı ve tamamlanma yüzdesi ezberde (§3 "Panel ekranından okunan sorular").
9. ☐ Tarayıcı: tam ekran (F11), zoom **%110**, yer imi çubuğu gizli, bildirimler kapalı (OS düzeyinde Rahatsız Etmeyin açık).
10. ☐ Sekme düzeni soldan sağa: **1** Relay (`#/` Bugün ekranında) · **2** Slack `#all-samed` · **3** Jira KAN panosu · **4** mock frontend (`VITE_RUN_SOURCE=mock`, açık ve çalıştığı görülmüş). Başka sekme YOK.
11. ☐ Ekran çözünürlüğü 1920×1080'e sabit; projektöre bağlanıp bir kez gerçek yansıtmada kontrol edildi.
12. ☐ Replay sigortası hazır: `TOOLS_MODE=replay` ile başlatma komutu terminalde yazılı bekliyor (Enter'a basmak yeter); replay kayıtları bugünkü senaryoyla eşleşiyor (bir kez oynatıldı).
13. ☐ Hedef cümle panoda kopyalı: *"Bugünkü maillerime bak, iş talebi gibi görünenleri KAN panosuna kayıt olarak aç ve ilgili kanaldan ekibe haber ver."* (Sohbet ekranında ilk örnek olarak da duruyor.) Düzeltme ezberde: `summary` → *"Ödeme hatası: sipariş tamamlanmıyor"*. Red gerekçesi ezberde: *"Kanal yanlış, bunu müşteri ekibi görmemeli."*
14. ☐ Donanım: laptop şarjda + %100, yedek USB-C→HDMI adaptörü cepte, telefon hotspot'u açılabilir durumda ve laptop ona bir kez bağlanıp test edildi.
15. ☐ Son tam prova bugün yapıldı ve **3:00'ın altında** bitti; kapanış cümlesi yüksek sesle iki kez söylendi.

---

*İlgili dokümanlar: [PRD](PRD.md) · [Mimari](ARCHITECTURE.md) · [Tasarım](DESIGN.md)*

## 6. Canlı kanıt — 1 Ağustos uçtan uca koşusu

Demo anlatısı slayta değil, o gün gerçekten koşmuş akışa dayanır. "Günü kapat"
(`gunu-kapat`, #172) canlıda uçtan uca geçti; izler sahnede "daha önce yapılmış işin
kanıtı" olarak açılabilir:

| yüzey | iz |
|---|---|
| Jira | `KAN-32` — "Ödeme adımında 'işlem tamamlanamadı' hatası — Sipariş R-44W-VG2" |
| Slack | `#all-samed`: "Müşteri şikayeti için Jira kaydı açıldı: KAN-32 (…R-44W-VG2)" |
| Notion | Relay Kayıtlar → "Karar: Ödeme hatası şikayeti için Jira kaydı açıldı (KAN-32)" |
| Sheets | Relay Rapor `Sayfa1!A1:D1`: `2026-08-01 · konu · KAN-32 · Açık` |

Bir okuma, dört kapı, dört onay — **23.166 token, $0,0041**. Ardından "Tablo özeti"
akışı aynı satırı geri okuyup Slack'e doğru özetledi (3.743 token, $0,0008): yazdığını
okuyabilen sistem. İzin akışı da aynı akşam boş ön koşulu üç gerekçeli atlamayla,
hiçbir şey yazmadan kapattı ($0,0012) — "boş gün" sorusunun canlı cevabı.

Projektör/HDMI arızasında §4'ün önerdiği yedek: [`assets/relay-b2b-demo.mp4`](assets/relay-b2b-demo.mp4)
— 56 saniye, ürünün kendi canlı ekranlarından, ücretli hiçbir araç kullanılmadan kurgulandı.
