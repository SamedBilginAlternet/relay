# Relay — Demo Senaryosu ve Sunum Kılavuzu

> 48 saatlik hackathon · Kategori: **Productivity & Work**
> Süre: **3 dakika demo + soru-cevap**
> Bu doküman bir sahne senaryosudur: gece 3'te, uykusuz halde, kelimesi kelimesine takip edilebilecek şekilde yazıldı. Doğaçlama yapma — burada yazanı yap.

---

## 0. Tek cümle (ezberle)

> **"Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun."**

Bu cümle açılışta bir kez, kapanışta bir kez geçer. Başka hiçbir yerde geçmez; iki kez duyulunca akılda kalır.

---

## 0.5. v2 açılışı — Bugün ekranı

> **Durum:** Bugün ekranı (`BRIEF.md`) canlıya alınıyor. Aşağıdaki ilk 30 saniye, §1'in
> 0:00–0:30 aralığının **yerine** geçer; gerisi (onay kapısı, red gerekçesi, Slack, denetim izi)
> aynen kalır. §1'in saniye tablosu ekran canlıda QA'dan geçtikten sonra yeniden zamanlanacak.

Neden değişiyor: boş bir sohbet kutusuyla açılmak "peki ne yazacağım" sorusunu jüriye de sordurur.
Bugün ekranı ürünün iddiasını ilk saniyede gösterir — **sistem işi getiriyor, kullanıcı "yap" diyor.**

> **Demo öncesi:** giriş yapılmış ve tanıtım turu bitirilmiş olsun. Uygulama artık oturum ister (`#/giris`); oturum çerezi 30 gün yaşar, ama sahneye çıkmadan bir kez `#/` açıp Bugün ekranının geldiğini gör. Turu tekrar göstermek istersen sağ üstteki hesap menüsünden "Tanıtım turunu tekrar aç".

| Zaman | Sen ne yapıyorsun | Ekranda ne oluyor |
|---|---|---|
| **0:00–0:12** | Açılış cümlesinin ilk yarısı. Hiçbir şeye dokunma. | Uygulama **Bugün** ekranında açık: üstte ÖNCELİKLİ kartları, altta Gelen kutusu · Üstümdeki işler · Kod · Takvim. |
| **0:12–0:22** | *"Bunları ben yazmadım — Relay sabah bunları topladı ve okudu."* Bir insight kartını göster. | Kart: gelen mail + **"Bu bir hata bildirimi gibi görünüyor"** + eylem pilleri: `Jira ticket aç` · `Slack'e bildir` · `Yoksay`. |
| **0:22–0:30** | *"Ve öneri, eylem değil. Ben basmadan hiçbir şey çalışmıyor."* **Jira ticket aç**'a bas. | Akış başlar; ekran §1'deki plan görünümüne geçer — aynı plan, aynı onay kapısı. |

Bu üç satırın tek işi, §1'in **1:05'teki onay anına** kullanıcıyı 30 saniye erken getirmek.
Kartların içeriği anlatılmaz, gösterilir; okumaya kalkarsan zaman gider.

**Sigorta:** Bir entegrasyon (Gmail/GitHub) o an cevap vermezse ekran boşalmaz — o kutu
"bağla" haline döner, diğer bölümler gelir. Jüri sorarsa: *"Kısmi başarı döndürüyoruz;
tek bir sağlayıcı düşünce brifing çökmüyor."* Hiçbiri gelmezse `TOOLS_MODE=replay` ile aç (§4).

---

## 1. Üç dakikalık demo akışı — saniye saniye

**Sahne kurulumu (demo başlamadan):** Tarayıcıda Relay Sohbet ekranı açık, konuşma boş, sağdaki akış paneli boş. İkinci sekmede Slack `#genel` kanalı açık (samedco workspace). Üçüncü sekmede Jira KAN panosu açık. Zoom %110, tam ekran (F11). Demo hedef cümlesi panoya kopyalanmış durumda (yazarken heyecandan yazım hatası yapmamak için — ama mümkünse canlı yaz, daha samimi durur).

| Zaman | Sen ne yapıyorsun / söylüyorsun | Ekranda ne oluyor | Jüri neye bakmalı |
|---|---|---|---|
| **0:00–0:15** | Açılış cümlesi (bkz. §2). Ekranda boş sohbet. | Beyaz, sade Sohbet ekranı. Sol konuşma, sağ boş akış paneli. | Ürünün "boş hali" bile temiz — güven veriyor. |
| **0:15–0:30** | *"Bugün her PM'in yaşadığı bir işi vereceğim."* Sohbet kutusuna yaz: **"Jira'daki blocker etiketli işleri bul, durumlarını güncelle, ekibe Slack'ten özet at."** → **Gönder**'e bas. | Kullanıcı balonu (mor `--accent-soft`) belirir. Sağ panelde "Planlanıyor…" durumu. | Kurulum yok, form yok, node bağlamak yok. Sadece cümle. |
| **0:30–0:45** | *"Relay bu cümleden numaralı bir iş akışı çıkardı — hangi ajan, hangi araç, hangi parametre."* Parmağınla (imleçle) plan adımlarını yukarıdan aşağı gez. | Plan 5 sn içinde oluşur: numaralı adımlar, her satırda rol + araç adı (mono: `jira.searchIssues`, `jira.updateIssue`, `slack.postMessage`) + OKUMA/YAZMA rozeti. | **Şeffaflık #1:** daha hiçbir şey çalışmadan tüm plan parametre önizlemesiyle ortada. |
| **0:45–1:05** | *"Okuma adımları güvenli — otomatik akıyor."* Hiçbir şeye basma; sadece akan olayları anlat. 1. adıma tıkla, parametre/sonuç JSON'unu 2 sn göster, kapat. | `jira.searchIssues` kendiliğinden koşar. Zaman çizelgesinde ajan mesajı: **Koordinatör → Jira Uzmanı:** "Blocker etiketli açık işleri getir." Sonuç: KAN projesinden gerçek ticket'lar. Üstteki maliyet şeridinde token + USD saymaya başlar. | **Ekip çalışıyor:** kim kime ne dedi, satır satır. Ve maliyet şeridi — her adımın token ve dolar karşılığı canlı. |
| **1:05–1:25** | Ses tonunu düşür, yavaşla: *"Ve işte fark burada. Sıradaki adım Jira'ya **yazacak**. Relay yazma adımını onaysız ÇALIŞTIRMAZ. Durdu, bana soruyor."* | `jira.updateIssue` adımı **amber** renge döner, satırda **Onayla** / **Reddet** pilleri belirir. Parametreler açıkta: hangi ticket, hangi yeni durum. | **FARKLILAŞTIRICI AN.** Jüri şunu görmeli: sistem güçlü ama zincirli. Yazma adımı onaysız çalışmaz — bu cümleyi aynen söyle. |
| **1:25–1:45** | *"Katılmıyorum diyelim — ama iptal etmeyeceğim, düzelteceğim."* Kanal alanını ekranda değiştir: `#all-samed` → `#dev-sprint`. Sonra **Düzelt ve onayla**'ya bas. | Alan düzenlenebilir; değiştirdiğin an buton "Düzelt ve onayla"ya döner. İz kaydına düşer: **Parametre kullanıcı tarafından düzenlendi (sen) — channel: "#all-samed" → "#dev-sprint"**. Adım düzeltilmiş değerle koşar. | **Gönderilen değer, senin gördüğün değer.** Kapı ikili değil: "olmasın" ile "şöyle olsun" arasında fark var. Düzenleme de iz kaydında, kim yaptığıyla birlikte. |
| **1:45–2:05** | *"Bir de reddetmek var — o zaman adım hiç çalışmıyor."* Sıradaki yazma adımında **Reddet**'e bas, gerekçe: **"KAN-3 bilerek açık kalacak."** Jira sekmesine geç, az önce düzeltilmiş adımın sonucunu panoda göster, dön. | Adım kırmızı "reddedildi" olur ve **orada biter** — gerekçe iz kaydına, ajana giden mesaja düşer. Jira panosunda önceki adımın değişikliği gerçekten duruyor. | **Bu mock değil** — gerçek Jira. Ve red, adımı bitiren bir karar: sistem onu "yeniden dene" diye yorumlamıyor. (Replay modundaysan sekme geçişini ATLA, bkz. §4.) |
| **2:05–2:25** | *"Son adım: ekibe özet. Bu da bir yazma — yine soruyor."* Mesaj önizlemesini yüksek sesle yarım cümle oku, **Onayla**'ya bas. Slack sekmesine geç, gelen mesajı göster, geri dön. | `slack.postMessage` onay kapısında bekler; onayla birlikte koşar. Slack `#genel` kanalına "relay" botundan özet düşer: güncellenen ticket'lar + linkler. | Araçlar **arası** iş bitti: Jira'dan okudu, Jira'ya yazdı, Slack'e raporladı — tek cümleden. |
| **2:25–2:40** | *"Teslimden önce bir ajan daha var: Doğrulayıcı."* İmleçle Doğrulayıcı mesajını göster. | **Doğrulayıcı → Koordinatör:** "Hedef karşılandı: N iş güncellendi, özet gönderildi." Akış `tamamlandı` olur. Maliyet şeridinde toplam: **~X.XXX token · $0.0X**. | İşi yapan da denetleyen de ayrı ajan. Ve toplam maliyet kuruşu kuruşuna ekranda. |
| **2:40–3:00** | **Geçmiş** sekmesine tıkla, biten akışı aç. *"Her şey burada: her araç çağrısı, parametresi, süresi, reddim ve gerekçem dahil. Denetlenebilir iş."* Kapanış cümlesi (bkz. §2). | Denetim izi: adımlar, kararlar (`otomatik`/`onaylandı`/`reddedildi` + gerekçe), token/USD kırılımı, ajan mesajları. | Kara kutu yok. Yarın müdürün "kim, neyi, neden değiştirdi" derse cevap bir tık uzakta. |

**Zamanlama kuralları:**
- 1:05'teki onay anı demonun kalbi — orada **acele etme**, gerekirse başka yerden kıs.
- Plan 5 sn'de gelmezse doldurma cümlen hazır: *"Groq'ta Llama koşuyor — normalde bu iki saniye."* 10 sn'yi geçerse §4'teki B planına geç, özür dileme, akıcı devam et.
- Sekme geçişleri (Jira, Slack) toplam 8 saniyeyi geçmesin; oralarda konuşma, göster ve dön.

---

## 2. Açılış konuşması (30 sn) ve kapanış

### Açılış — kelimesi kelimesine

> "Bir beyaz yakalının günü tek bir işten değil, araçlar arası koşuşturmadan oluşuyor: Jira'dan çıkar, Jira'da güncelle, Slack'te raporla. Dört araç, on beş tıklama, yirmi dakika — her gün. Sohbet asistanları metin üretiyor ama hiçbir şey *yapmıyor*; otomasyon araçları yapıyor ama önce saatlerce kurman gerekiyor. Relay üçüncü yol: işini bir cümleyle anlatıyorsun, Relay uzman ajanlardan bir ekip kuruyor — gün içinde çıkan işi araçların içinde yürüten bir ajan ekibi, ve hiçbir yazma adımını sana sormadan yapmaz. **Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun.** Gösterelim."

(≈30 sn. "Gösterelim" dediğin an yazmaya başla — boşluk bırakma.)

### Kapanış — kelimesi kelimesine

> "Üç dakikada: bir cümle verdim, bir ekip kuruldu, Jira güncellendi, ekip haberdar edildi — ve her adım, reddim dahil, denetim izinde. **Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun.** Biz Relay'iz. Teşekkürler."

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

**"Maliyet kontrolü nasıl?"**
> Her adım token ve tahmini USD döndürür, akışın toplamı maliyet şeridinde canlı sayar — demoda gördünüz. Akışa bütçe sınırı koyarsınız; ajan sınırı aşarsa **durur ve sorar**, sessizce harcamaz. Adım bazlı kırılım denetim izinde kalır — hangi adım kaça mal oldu, sonradan da görürsünüz.

**"Yeni entegrasyon eklemek ne kadar iş?"**
> Tek sınıf. `Tool` arayüzü beş üyeli: ad, açıklama, JSON şeması, risk seviyesi, execute. Sınıfı yazınca Spring otomatik toplar, LLM araç listesinde görür, politika motoru risk seviyesine göre varsayılanı atar — okuma otomatik, yazma onaylı. Orkestratöre tek satır dokunulmaz. "3000+ entegrasyon" iddiasının gerçek uzantı noktası bu.

**"Ajan döngüsü nerede koşuyor?"**
> Kendi backend'imizde — Java 21 + Spring Boot, hazır ajan framework'ü yok. Döngü dört rol: **Planner** hedefi adımlara çevirir, **Koordinatör** her adımı ilgili araç uzmanına devreder ve politikayı uygular, **ToolAgent** parametreleri kesinleştirip aracı çağırır, **Verifier** sonucu hedefe karşı denetler. Her geçiş bir olay üretir ve SSE ile arayüze akar — o yüzden her şeyi canlı gördünüz.

**"İnternet ya da hesaplarınız ölürse?"**
> Çift sigorta. Birinci kademe: `TOOLS_MODE=replay` — araç çağrıları kayıtlı gerçek yanıtlarla oynar, ağ gerekmez; onay kapısı ve akış birebir aynı çalışır. İkinci kademe: frontend mock modu — backend bile ölse arayüz senaryoyu uçtan uca oynatır. Groq tarafında da çoklu anahtar rotasyonu var; hepsi tükenirse deterministik stub'a düşer ve arayüz bunu açıkça söyler. Demo hiçbir tekil noktaya bağlı değil.

### Panel ekranından okunan sorular

`#/panel` jüriye açık bir sekme ve rakamları biz koyduk. Bu iki soru **gelecek**; hazırlıksız
yakalanmak, kötü rakamdan daha pahalı.

**"Panelinizde 106 akışın 50'si tamamlanmış. Akışlarınızın yarısı bitmiyor mu?"**
> Doğru okudunuz, ve rakamın çoğu bizim tanımımız: 29 akış **onay bekliyor** — çünkü kapıyı geçmek için insan gerekiyor, ve o insan o gün geri dönmediyse akış orada durur. Bu bizim yarım kalmış işimiz değil, ürünün varsayılanı; sessizce tamamlanan bir akış bizim için başarısızlık olurdu. Geriye 23 hata kalıyor ve onları savunmuyoruz: çoğu 48 saatlik entegrasyon işi — yanlış proje anahtarı, kanala davet edilmemiş bot. Ölçtüğümüz şey de bu zaten; ölçmeseydik bu ekran olmazdı.

**Söylenmeyecek:** *"o rakam test verisi"* — 106 akışın içinde jüriye gösterdiğimiz akış da var.
Rakamı sahiplen; ölçtüğün bir sayıyı reddetmek, ölçüm iddiasını da düşürür.

**Yedek sorular (gelirse):**
- *"Neden Groq?"* → Hız: plan 5 saniyenin altında gelmeli, Groq'un çıkarımı bunu sağlıyor. `LlmClient` arayüz — sağlayıcı tek sınıfla değişir.
- *"Silme işlemleri?"* → **Bugün silen tek bir aracımız yok** — 18 aracın 12'si okuma, 6'sı yazma, sıfırı silme. Motorda `DESTRUCTIVE` → `yasak` kuralı duruyor ve `Politikalar` ekranı bunu açıkça yazıyor: *"şu an kayıtlı hiçbir aracın riski silme değil."* Kuralı önce yazdık, öznesini bilerek eklemedik: 48 saatte geri alınamayan bir işlem eklemek, onay kapısının test edilmediği tek yerdir. **Üçüncü ayağı slogana çevirme** — jüri "hangi silme aracınız var?" der ve cevap "hiçbiri" olur; o an cümle bir slogan gibi duyulur. İkili kur: okuma otomatik, yazma onaylı.
- *"İş modeli?"* → Koltuk başına abonelik + kullanım (LLM maliyeti zaten adım başına ölçülüyor — faturalama altyapısı bedavaya çıktı). Kurumsal katman: politika ve denetim izi zorunlulukları.

---

## 4. Risk matrisi ve sahne sigortaları

### ALTIN KURAL

> **Asla canlı yazma adımını onaysız gösterme.** Politikayı demo öncesi kontrol et: `jira.updateIssue`, `jira.addComment`, `slack.postMessage` → **onay iste**. Biri `otomatik`e alınmışsa demoya başlama, önce düzelt. Onay kapısı ürünün tezi; kapısız bir yazma adımı tezle çelişir ve tek karede tüm hikâyeyi çökertir.

### Risk matrisi

| Risk | Nasıl anlarsın | Anında yapılacak | Söylenecek cümle |
|---|---|---|---|
| **Salon ağı ölür / Wi-Fi yok** | Plan gelmiyor, araç çağrıları timeout | Yedek terminalde backend'i `TOOLS_MODE=replay` ile yeniden başlat (hazır komut, bkz. §5 madde 12). Telefon hotspot'u yalnızca Groq için yeter. | *"Kayıt modundan devam ediyorum — akış, onay kapısı, her şey birebir aynı; yalnızca araç yanıtları önceden kaydedilmiş gerçek yanıtlar."* |
| **Groq anahtarları tükenir (429)** | Plan gecikir; rotasyon otomatik devreye girer | Hiçbir şey — rotasyon sıradaki anahtara kendisi geçer. Tüm anahtarlar biterse stub'a düşer ve arayüzde **görünür "sınırlı mod" rozeti** çıkar. | Rozeti sakla(ma)! Göster: *"Bakın, sistem degradasyonu bile şeffaf — Relay hiçbir şeyi gizlemez, kendi arızasını bile."* |
| **Jira/Slack auth patlar** (token iptal, rate limit) | Bağlantı testi kırmızı / araç adımı hata | Replay moduna geç (ağ ölümüyle aynı prosedür). Jira/Slack sekme geçişlerini demodan çıkar. | Aynı replay cümlesi. Sekmeleri özleme — kimse yokluğunu fark etmez. |
| **Backend tümden çöker** | SSE kopuk, sayfa boş | Son çare: `VITE_RUN_SOURCE=mock` ile açılmış yedek frontend sekmesine geç (önceden açık ve test edilmiş duracak). | Mock olduğunu **söyle**: *"Bu kayıttan oynayan arayüz — canlısını sunum sonrası masamda gösteririm."* Dürüstlük puan kaybettirmez, yakalanmak kaybettirir. |
| **Projektör/HDMI arızası** | Görüntü yok | 1) Yedek USB-C→HDMI adaptörü. 2) Çözünürlüğü 1920×1080'e sabitle (yansıtma modunda kayan çözünürlük ekranı bozar). 3) Hiçbiri olmazsa telefondaki demo videosu jüriye elden. | *"Ekranı toparlarken ürünü anlatayım…"* — açılış konuşması ekransız da ayakta durur. |
| **Onay anında yanlış tıklama** (Reddet yerine Onayla vb.) | Adım beklenmedik ilerler | Panikleme. Akış zaten doğru şeyi yapıyor; red anını Slack adımında telafi et (o da yazma — onay kapısı orada da var). | *"Onayladım — reddi bir sonraki yazma adımında göstereyim."* |
| **KAN'da blocker ticket kalmamış** (önceki prova tüketmiş) | `searchIssues` boş döner | Demo ÖNCESİ sıfırlama scripti bunu engeller (bkz. §5 madde 4). Sahnede yakalanırsan replay'e geç. | — |
| **Slack mesajı yanlış kanala düşer** | `#genel`'de mesaj yok | Parametre önizlemesi onay kapısında zaten görünüyor — onaylamadan önce kanalı GÖZÜNLE kontrol et. Bu, kapının değerini bir kez daha kanıtlar. | *"Onaylamadan önce kanala baktım — kapı tam bunun için var."* |

### Prova kuralı

Tam prova en az **3 kez**: bir kez canlı modda, bir kez `TOOLS_MODE=replay` ile, bir kez mock frontend ile. Üç modda da 3 dakikayı tutturduysan hazırsın. Replay provası yapmadan sahneye çıkma — sigortayı ilk kez yangında test etmezsin.

---

## 5. Demo öncesi kontrol listesi (15 madde)

Demodan **30 dakika önce** başla; her maddeyi sırayla işaretle.

1. ☐ Backend ayakta: `GET /api/health` → `{status: ok}` dönüyor; sağlayıcı için oturumlu `GET /api/health/details` → `llm.provider: groq`.
2. ☐ **Bağlantılar** ekranında Jira ve Slack token'ları girili; her ikisinde **Bağlantıyı Test Et** yeşil.
3. ☐ Politikalar doğru: `jira.search/get` → otomatik; `jira.updateIssue`, `jira.addComment`, `slack.postMessage` → **onay iste**. (ALTIN KURAL — §4.)
4. ☐ Jira KAN projesinde blocker etiketli ticket'lar **In Progress**'e sıfırlandı (önceki provaların Done'ları geri alındı); en az 3 blocker listede.
5. ☐ Slack: "relay" botu `#genel` kanalına **davetli** (`/invite @relay`); kanala test mesajı atıp silindi.
6. ☐ Groq: `GROQ_API_KEYS` en az 3 anahtar içeriyor; her biri bugün tek istekle doğrulandı.
7. ☐ Akış bütçesi ayarlı (ör. $0.50) — maliyet şeridi ve bütçe davranışı anlatılabilir durumda.
8. ☐ **Geçmiş** temiz: eski deneme akışları silindi ya da en üstte düzgün bir prova akışı duruyor (jüri Geçmiş'te çöp görmesin).
9. ☐ Tarayıcı: tam ekran (F11), zoom **%110**, yer imi çubuğu gizli, bildirimler kapalı (OS düzeyinde Rahatsız Etmeyin açık).
10. ☐ Sekme düzeni soldan sağa: **1** Relay · **2** Slack `#genel` · **3** Jira KAN panosu · **4** mock frontend (`VITE_RUN_SOURCE=mock`, açık ve çalıştığı görülmüş). Başka sekme YOK.
11. ☐ Ekran çözünürlüğü 1920×1080'e sabit; projektöre bağlanıp bir kez gerçek yansıtmada kontrol edildi.
12. ☐ Replay sigortası hazır: `TOOLS_MODE=replay` ile başlatma komutu terminalde yazılı bekliyor (Enter'a basmak yeter); replay kayıtları bugünkü senaryoyla eşleşiyor (bir kez oynatıldı).
13. ☐ Hedef cümle panoda kopyalı: *"Jira'daki blocker etiketli işleri bul, durumlarını güncelle, ekibe Slack'ten özet at."* Red gerekçesi ezberde: *"KAN-3'ü güncelleme, o bilerek açık kalacak."*
14. ☐ Donanım: laptop şarjda + %100, yedek USB-C→HDMI adaptörü cepte, telefon hotspot'u açılabilir durumda ve laptop ona bir kez bağlanıp test edildi.
15. ☐ Son tam prova bugün yapıldı ve **3:00'ın altında** bitti; kapanış cümlesi yüksek sesle iki kez söylendi.

---

*İlgili dokümanlar: [PRD](PRD.md) · [Mimari](ARCHITECTURE.md) · [Tasarım](DESIGN.md)*
