# Çalışma Düzeni

Bu dosya iki şeyi sabitler: bir işin nasıl başladığı ve bir commit'in nasıl göründüğü.
Amaç tören değil — üç hafta sonra "bu satır neden böyle" sorusunun cevabının bulunabilmesi.

## 1. Önce sorun, sonra kod

Sıra: **anla → issue aç → geliştir → commit → push**.

Issue, yapılacak işin listesi değil, **sorunun kaydı**. Şu dört başlığı taşır:

```markdown
## Sorun          ne bozuk / ne eksik, kullanıcı açısından
## Kanıt          dosya, satır, hata mesajı, canlı çıktı — iddia değil kanıt
## Yapılacak      önerilen çözüm; alternatif elendiyse nedeni
## Kabul kriterleri  nasıl doğrulanacağı; "çalışıyor" değil, ölçülebilir cümle
```

Tahmini süre ve kaynak (`docs/SIRADAKI-FIKIRLER.md` F-2 gibi) sona bir satır olarak eklenir.

Issue açmadan geçilebilecek tek durum: yazım hatası, ölü kod temizliği, dokümantasyon
düzeltmesi. Davranış değişiyorsa issue vardır.

## 2. Commit

**Bir commit bir fikirdir.** Beş dosyada tek bir düzeltme tek commit'tir; bir dosyada üç
ayrı düzeltme üç commit'tir. `git add -A` alışkanlığı bu kuralı sessizce bozar — dosyaları
adıyla ekle.

Başlık satırı: **72 karakteri geçmez, emir kipi, nokta yok.** İngilizce.

```
Record who approved, not just that someone did
```

Ne yaptığını değil, **neyi değiştirdiğini** söyler. "Fix bug", "update code", "changes"
gibi başlıklar bir şey anlatmaz.

Gövde: boş satırdan sonra, 72 sütunda sarılı. Şu soruya cevap verir: **neden?** Kod ne
yaptığını zaten söylüyor; gövde, o değişikliği gerektiren gerçeği yazar.

```
The demo closes on "kim, neyi, neden değiştirdi", and the trail could not
answer the first third: every decision was written as a generic user. On a
workspace several people share, that is the whole question.

Refs #19
```

Son satır issue'ya bağlar: `Refs #19` (üzerinde çalışılıyor) veya `Closes #19` (bitti,
merge ile kapanır).

Kural olarak:

- Yazar kimliği `samed.bilgin@alternet.com.tr`. Ortam varsayılanı yanlış hesaba düşüyor.
- Co-author trailer eklenmez.
- Yeşil olmayan ağaç push edilmez: `cd backend && JAVA_HOME=~/jdk21 ./gradlew build` ve
  `cd frontend && npm run build` ikisi de temiz olmalı. `tsc` tek başına yetmez, ESLint
  hataları yalnız `npm run build`'de görünür.

## 3. Push

`git pull --rebase origin main` sonrası `git push origin HEAD`. Rebase, paralel çalışan
başka bir agent'ın commit'lerinin altına düzgün oturmayı sağlar.

Paralel çalışırken indeks paylaşılır: `git commit` **staged olan her şeyi** alır, yalnız
senin dosyalarını değil. Başkasının dosyası indekste duruyorsa commit'ine karışır — bu
bir kez oldu. Emin değilsen `git commit -- <yol>` ile yalnız istediğin yolları commit'le.

## 4. Test

Davranış değişikliği testle gelir. Testin adı iddiayı söyler:

```java
void an_invented_issue_key_never_reaches_the_provider()
void the_human_sees_the_message_before_approving_it()
```

Test sınıfının Javadoc'u **testin neden var olduğunu** yazar — hangi gerçek olay onu
gerektirdi. Bir yıl sonra o testi silmeye kalkan kişi, neyi kaybedeceğini bilsin.
