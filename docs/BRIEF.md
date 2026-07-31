# Bugün Ekranı — Günlük Brifing (v2 ürün yönü)

> Sohbet motor olarak kalıyor; **giriş noktası** değişiyor.
> Kullanıcı artık boş bir kutuya bakmıyor — güne, kendisini bekleyen işlerle başlıyor.

## 1. Neden

Bugünkü ilk ekran boş bir sohbet kutusu. Kullanıcının "ne yazsam" diye düşünmesi gerekiyor.
Oysa cevabı sistem zaten biliyor: gelen kutusunda 3 mail bekliyor, üstünde 4 Jira issue var,
2 PR review istiyor, 14:00'te toplantısı var.

**Yeni ilk ekran = Bugün.** Sistem işi getiriyor, kullanıcı sadece "yap" diyor.

## 2. Ekran yapısı

```
┌──────────────────────────────────────────────────────────┐
│  Bugün · 31 Temmuz                        [Yenile] [⚙]   │
├──────────────────────────────────────────────────────────┤
│  ÖNCELİKLİ  — AI katmanının öne çıkardıkları             │
│  ┌────────────────────────────────────────────────────┐  │
│  │ ✉ Ayşe: "Ödeme servisi staging'de patlıyor"        │  │
│  │   → Bu bir hata bildirimi gibi görünüyor.          │  │
│  │   [Jira ticket aç]  [Slack'e bildir]  [Yoksay]     │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  GELEN KUTUSU (7)          ÜSTÜMDEKİ İŞLER (4)          │
│  · 3 yanıt bekliyor        · KAN-42 Blocked             │
│  · 2 bilgilendirme         · KAN-51 In Progress         │
│                                                          │
│  KOD (3)                   TAKVİM (2)                    │
│  · 2 PR review bekliyor    · 14:00 Sprint planlama      │
│  · 1 issue atandı          · 16:30 1:1                  │
└──────────────────────────────────────────────────────────┘
```

## 3. AI katmanı — asıl fark burada

Her mail/PR/issue için tek bir LLM çağrısı **sınıflandırma + eylem önerisi** üretiyor:

```json
{
  "kind": "bug_report | request | fyi | needs_reply | scheduling",
  "urgency": "high | normal | low",
  "summary": "Tek cümle — ne isteniyor",
  "suggestedActions": [
    { "tool": "jira.createIssue", "label": "Jira ticket aç", "params": {...} },
    { "tool": "slack.postMessage", "label": "#dev'e bildir", "params": {...} }
  ]
}
```

Kullanıcı bir eyleme tıkladığında **normal bir Relay akışı başlıyor** — aynı plan, aynı
onay kapısı, aynı şeffaflık. Yani Bugün ekranı yeni bir motor değil, motora **hazır girdi**.

**Kritik kural:** öneri ≠ eylem. Hiçbir şey tıklanmadan çalışmaz. Yazma adımı hâlâ onay ister.

## 4. Yeni araçlar

| Araç | Risk | Not |
|---|---|---|
| `gmail.listToday` | READ | Bugünün mailleri, `q=newer_than:1d` |
| `gmail.getMessage` | READ | Tek mail gövdesi |
| `calendar.listToday` | READ | Bugünün etkinlikleri |
| `github.listMyPullRequests` | READ | `review-requested` + `author` |
| `github.listMyIssues` | READ | `assignee:@me` |
| `github.addComment` | WRITE | Onay ister |
| `jira.createIssue` | WRITE | Onay ister — Bugün ekranının ana eylemi |
| `jira.listMyIssues` | READ | `assignee = currentUser()` |

## 5. Yeni uçlar

| Metot | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/brief` | Tüm kartlar tek çağrıda (paralel araç çağrıları) |
| `POST` | `/api/brief/refresh` | Önbelleği atla, yeniden çek |
| `POST` | `/api/runs/from-suggestion` | Öneriden akış başlat |

`/api/brief` **kısmi başarı** döner: Gmail bağlı değilse o bölüm `unavailable` işaretlenir,
diğerleri yine gelir. Tek bir entegrasyon eksikse ekran boş kalmaz.

## 6. Kapsam sırası

1. **İskelet + Jira/Slack** (bağlı olanlar) — Bugün ekranı ayakta
2. **GitHub** (PAT ile, OAuth yok — en ucuz entegrasyon)
3. **Gmail** (OAuth gerekiyor)
4. **Calendar** (aynı OAuth, +30 dk)

Her adım tek başına deploy edilebilir. Sıradaki gelmezse önceki çalışmaya devam eder.
