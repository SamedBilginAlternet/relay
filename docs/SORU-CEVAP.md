# Jüri Soru-Cevap Hazırlığı

Teknik bir jürinin soracağı en zor sorular ve sahnede verilecek cevaplar. Her cevap
koda dayanır; ayrıntı ve `dosya:satır` referansları [ARCHITECTURE.md](ARCHITECTURE.md)'de.

---

**1. "Tek bir master prompt mu var?"**

Hayır, sekiz ayrı iş var ve her birinin kendi sistem istemi, kendi JSON şeması, kendi
model katmanı ve kendi token tavanı var (`LlmPurpose`). Tek dev istem olsaydı hangi işin
ne kadar tuttuğunu ölçemez, ucuz işi ucuz modele veremez ve bir iş için yazılmış kuralın
başka bir işi bozmasını engelleyemezdik. Ayrıntı: ARCHITECTURE.md §2.

**2. "Modeli neden mocklamadınız? Bu gerçek bir LLM mi?"**

Gerçek: canlı kutu bir günde 627 bin token harcayıp sağlayıcının günlük duvarına çarptı —
mock bu faturayı ödemez. Mock olan taraf bilinçli seçilmiş: testler ve demo sigortası
için deterministik bir `StubLlmClient` var, araçlar için de `TOOLS_MODE=replay` fixture
modu var. İkisi de aynı arayüzün arkasında durur, yani ürün kodu hangisiyle konuştuğunu
bilmez; stub devredeyken ekran bunu açıkça söyler ve özet/digest gibi yorum
cümleleri hiç üretilmez — şablon metin içgörü gibi sunulmaz.

**3. "Model kendine güven skoru veriyor mu? Çıktıya nasıl güveniyorsunuz?"**

Güven skoru istemiyoruz çünkü modelin kendi güveni bir ölçüm değil, bir cümledir. Bunun
yerine her çıktı deterministik kapılardan geçer: şema doğrulama, uydurulmuş kimlik
kontrolü, yer tutucu taraması, şablon metin taraması, kapanış özetinin kanıt kontrolü.
Ayrıca sonucu ayrı bir denetçi (Verifier) yargılar; denetçi yargı veremezse bu
"doğrulandı" diye değil "denetlenemedi — adım geçti, doğrulanmadı" diye yazılır.
Sessizlik onay sayılmaz.

**4. "Bir onay kaç yazma yetkisi verir?"**

Tam olarak bir deneme. Bunu canlıda ölçerek öğrendik: bir `jira.createIssue` onayı,
denetçi reddedip adım yeniden denenince aynı onayla üç Jira kaydı açtı (KAN-24/25/26).
Artık adım herhangi bir nedenle geri döndüğünde karar temizlenir (`decision=null`),
parametreler o anda yeniden türetilir ve adım kapıya geri gelir. Onaylanan parametre ile
gönderilen parametre her zaman aynıdır — kapıdan sonra hiçbir şey sessizce değişmez.

**5. "Groq kotanız bitince ne oluyor?"**

Üç sağlayıcılık bir zincir var, çünkü 2026-08-01'de iki sağlayıcı aynı saat içinde düştü:
yedi Groq anahtarı günlük duvara çarptı, arkadaki ücretli sağlayıcı HTTP 599 verdi.
Zincir sırayla denenir (varsayılan groq → deepseek → üçüncü katman → stub); her katman
OpenAI-uyumlu aynı istemcidir, yani dördüncü sağlayıcı eklemek kod değil ortam değişkeni.
429'da anahtar sağlayıcının `Retry-After` süresi kadar (en çok 1 saat) park edilir,
401/403 anahtarı emekliye ayırır. Hepsi biterse deterministik stub cevap verir ve arayüz
bunu saklamaz.

**6. "Model olmayan bir kayıt anahtarı uydurursa ne oluyor?"**

Yazma adımındaki `*key`/`*id`/`*number` alanları hedef metninde, önceki adım sonuçlarında
veya bağlantı ayarlarında geçmiyorsa adım sağlayıcıya hiç gitmez. Koordinatör bunu ölüm
değil onarım sayar: plana aynı sağlayıcının en ucuz arama adımını görünür şekilde ekler,
uydurulan anahtarı siler, yazma adımını kayıt bulunduktan sonra yeniden onaya getirir.
Onarım gizli bir retry değildir — iz kaydında okunur.

**7. "Onay ekranında gördüğüm şeyin gönderilen şey olduğunu nereden bileyim?"**

Bu ürünün tek vaadi bu, o yüzden üç değişmez kuralı var. Parametreler kapıya gelmeden
*önce* kesinleştirilir — insan taslak değil, gönderilecek metni okur. Gönderilemez bir
taslak (şemayı geçmeyen, yer tutuculu, şablon metinli) kapıya hiç gelmez; önce onarılır,
onarılamazsa alan adlarıyla başarısız olur. Ve insanın kapıda düzelttiği parametre
kilitlenir (`paramsLocked`) — ne model ne varsayılan doldurma üzerine yazamaz.

**8. "Neden ag-grid'i ekleyip aynı gün çıkardınız?"**

Çünkü ölçtük ve yanılmıştık. Sabah Akışlar tablosuna sütun filtresi için ag-grid girdi:
lazy chunk olarak 232.12 KB gzip — ürünün geri kalan her şeyinin toplamından büyük. Akşam
liste karta dönünce sütun diye bir şey kalmadı ve grid, bir diziyi dilimlemek için ödenen
232 KB'a dönüştü. Kazandırdığı iki filtre ve sıralama ~120 satırla listenin üstünde duruyor;
Akışlar ziyareti 389.65 KB'dan 157.65 KB'a indi. Yanlış karar değil, ucuz düzeltilen bir
karar — ölçüm olmasaydı fark etmezdik.

**9. "Maliyet rakamlarınıza neden güveneyim?"**

Çünkü tahmin değil, sayım: her LLM yanıtındaki `usage` token'ları hem adıma hem akış
toplamına yazılır ve cevaplayan modelin kendi fiyat listesiyle çarpılır. Panodaki
karşılaştırma da aritmetiktir: aynı ölçülmüş token'lar güçlü modelin fiyatıyla ikinci kez
çarpılır (`premiumCostUsd`). Fiyatlanamayan çağrıda değer `null` olur, asla sıfır —
sıfır bir ölçümdür ve "0 token harcadı" diyen başarısız bir koşu bir kez yaşandı,
bir daha yaşanmayacak şekilde kapatıldı.

**10. "Plan üretilemezse yeşil mi kapanıyor?"**

Artık hayır — bu, bugün kapattığımız gerçek bir hataydı. Yedek sağlayıcı plan biçimini
tutturamayınca akış tek adımlık "Hedefi özetle"ye düşüyor ve "Tamamlandı" yazıyordu;
posta kutusu okunmamış, kayıt açılmamışken. Şimdi ayrıştırılamayan plan
`PlanUnreadableException` ile koşuyu Türkçe gerekçeyle `failed` kapatır. Başarısızlık bir
rerun'a mal olur; sahte yeşil ise "yeşil koşu bir şey ifade eder" inancına mal oluyordu
ve bu ürünün ikinci bir iddiası yok.

**11. "Sunucu yeniden başlarsa yarım kalan akışa ne olur?"**

Onay bekleyen akış sorunsuz: duruş veritabanında, `approve` gelince kaldığı yerden devam
eder. `running` ortasında kesilen akışı ise bilinçli olarak otomatik sürmüyoruz — uçuştaki
yazma çağrısının sağlayıcıda gerçekleşip gerçekleşmediği bilinemez, yeniden sürmek aynı
yazmayı ikinci kez yapabilir. Bilinen sınır: o akış geçmişte "çalışıyor" görünür, tek
çıkış `cancel`. Doğru asgari adım kurtarma değil dürüst rapor; kayıtlı bir issue.

**12. "Neden WebSocket değil SSE?"**

Akış tek yönlü: sunucu olay yayınlar, istemci karar verdiğinde zaten REST çağırır.
WebSocket'in çift yönlü kanalı burada kullanılmayan karmaşıklık olurdu. SSE otomatik
yeniden bağlanır, sunucu koşu başına olayları hafızada tutar ve geç bağlanan istemci
hikâyeyi baştan alır. Bedeli de biliyoruz: `EventSource` özel başlık gönderemediği için
kimlik çerezde taşınmak zorunda — oturum tasarımı buna göre yapıldı.

**13. "Çok kullanıcılı mı? Verilerim kimden izole?"**

Relay tek bir ortak çalışma alanıdır ve bunu saklamıyoruz: `runs`/`connections`
tablolarında bilinçli olarak `user_id` yok, giriş yapan herkes aynı bağlantıları ve
koşuları görür; `users` yalnızca kimin klavyede olduğunu söyler. Çok kiracılılık şema
değişikliği gerektiren ayrı bir iştir. Sırlar ise izole: token'lar AES-GCM ile şifreli
durur, API'de maskeli döner, hata gövdelerinde `SECRET_SHAPES` desenleriyle karartılır.

**14. "Bugün ekranı ilk açılışta neden yavaştı, ne yaptınız?"**

Ölçtük: deploy sonrası ilk brief 28.6 saniye sürdü — tüm Groq anahtarları duvardayken
ücretli katmana düşen bir soğuk üretim. İki şey değişti: brief artık stale-while-revalidate
ile servis edilir (süresi geçmiş özet anında verilir, yenisi arkada kurulur ve yanıt
`stale: true` ile bunu söyler) ve ilk üretim ilk ziyaretçiye değil boot anına alındı
(`ApplicationReadyEvent`, `BRIEF_WARM_ON_START` ile kapatılabilir). Sonraki istek 71 ms.

**15. "Silme gibi yıkıcı işlemleri model tetikleyebilir mi?"**

Hayır, iki katmanla. Birincisi: `DESTRUCTIVE` riskli tek bir araç bile kayıtlı değil —
mod üründe fiilen çalışmıyor. İkincisi: kayıtlı olsaydı bile varsayılan politikası
`forbidden` olurdu ve `PolicyEngine` yıkıcı bir aracın `auto` yapılmasını API
seviyesinde reddeder; gevşetmenin sınırı `ask`tır, yani araya her zaman bir insan girer.
Kayıtlı olmayan araç adları da (halüsinasyon dahil) otomatik `forbidden` döner.
