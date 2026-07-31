# Relay — Tasarım Dili

> Referans: Mindra. Kategori kodlarını **bilerek** benimsiyoruz — bu pazarda mor/koyu
> gradyan "ajan ürünü" demek. Anında tanınmak bir avantaj.

## 0. Ölçülen referans: Mindra

mindra.co'nun gerçek değerleri incelendi:

| Ne | Mindra'da |
|---|---|
| Zemin | Koyu mor gradyan (indigo → mor → siyah), üstünde ince grid overlay |
| Başlık | Kalın geometrik sans, beyaz; ikinci satır açık mor vurgu |
| Buton | Tam yuvarlak (pill), 8px 12px iç boşluk |
| Ton | Kurumsal ama enerjik; kara kutu karşıtı, "her şeyi görürsün" vurgusu |

**Aldıklarımız:** koyu zemin, mor-indigo ailesi, pill butonlar, grid dokusu, kalın sans başlık.
**Ayrıştığımız:** Mindra bir *pazarlama sayfası*; biz bir *çalışma aracıyız*. Gradyan yalnızca giriş ekranında; uygulamanın içi sakin ve yoğun bilgi taşıyabilir olmalı.

---

## 1. Renk

```
--bg          #0B0A12   koyu indigo-siyah (uygulama zemini)
--surface     #14131F   panel, kart
--surface-hi  #1E1C2E   seçili satır, hover
--border      #2A2740
--fg          #F2F0FA
--fg-muted    #9A95B8

--accent      #8B5CF6   mor — birincil eylem, aktif adım
--accent-soft #8B5CF61A
--info        #6EA8FE   okuma adımı
--warn        #F5A524   onay bekleyen adım
--success     #3FD68C   tamamlanan adım
--danger      #FF5C6C   hata / reddedilen adım
```

**Giriş ekranı gradyanı:** `radial-gradient(120% 80% at 50% 0%, #3B1E7A 0%, #1A1030 45%, #0B0A12 100%)` + %4 opaklıkta 32px grid.
Uygulamanın içinde gradyan **yok** — düz `--bg`. Bilgi yoğun ekranda gradyan okumayı bozar.

### Adım durumu = renk + ikon (renk tek başına anlam taşımaz)

| Durum | Renk | Lucide ikon |
|---|---|---|
| Bekliyor | `--fg-muted` | `circle-dashed` |
| Onay bekliyor | `--warn` | `shield-question` |
| Çalışıyor | `--accent` + nabız | `loader` |
| Tamamlandı | `--success` | `check` |
| Hata | `--danger` | `x` |
| Reddedildi | `--fg-muted` | `slash` |

---

## 2. Tipografi

Tek aile: **Inter** (variable). Gerekçe: bilgi yoğun bir çalışma aracı; karakterden çok okunabilirlik ve sıkı satır aralığı lazım. Kod/parametre için **JetBrains Mono**.

| Rol | Boyut | Ağırlık | Satır |
|---|---|---|---|
| `display` (giriş ekranı) | `clamp(40px, 7vw, 72px)` | 700 | 1.05 |
| `title` | 20px | 600 | 1.3 |
| `body` | 15px | 400 | 1.55 |
| `caption` | 13px | 400 | 1.4 |
| `label` | 12px | 600 | 1.2, +0.06em, UPPERCASE |
| `mono` | 13px | 400 | 1.5 — JetBrains Mono, parametre ve JSON |

---

## 3. Düzen — iki sütun

```
┌──────────────────────────┬─────────────────────────┐
│  SOHBET                  │  AKIŞ                   │
│                          │                         │
│  kullanıcı mesajı        │  1 ✓ Jira'da ara        │
│  ajan yanıtı             │  2 ✓ Blocker'ları süz   │
│                          │  3 ⚠ 3 ticket güncelle  │
│                          │      [Onayla] [Reddet]  │
│  ┌────────────────────┐  │  4 ○ Slack'e özet at    │
│  │ ne yapmamı istersin│  │                         │
│  └────────────────────┘  │  ── adım detayı ──      │
│                          │  tool: jira.updateIssue │
│                          │  { "key": "RUN-42", … } │
└──────────────────────────┴─────────────────────────┘
        %55                          %45
```

- Mobilde tek sütun; akış paneli alttan açılan sheet.
- Boşluk ölçeği `4 · 8 · 12 · 16 · 24 · 32 · 48`
- Yarıçap: `8px` kart, `10px` buton, `999px` pill/çip

---

## 4. Bileşenler

**Buton — pill (Mindra'dan)**
```css
border-radius: 999px;
padding: 10px 18px;
background: var(--accent);
color: #fff;
font: 600 14px/1 Inter;
/* secondary: transparent + 1px var(--border) */
/* :active: transform: scale(0.97) */
```

**Akış adımı satırı** — 56px yükseklik, solda durum ikonu, ortada başlık + araç adı, sağda süre. Tıklanınca altında parametre bloğu açılır.

**Parametre bloğu** — `--surface-hi` zemin, JetBrains Mono 13px, JSON renklendirmesi yok (sade), kopyala butonu.

**Onay kapısı** — adım satırının içinde iki pill: `Onayla` (accent) ve `Reddet` (outline). Reddederken tek satır gerekçe alanı; gerekçe ajana geri gider.

**Sohbet balonu** — kullanıcı sağda `--surface-hi`, ajan solda zeminde (balon yok, sadece metin) — okunabilirlik için.

---

## 5. Hareket

| Olay | Süre | Easing |
|---|---|---|
| Adım durum değişimi | 250ms | ease-out |
| Yeni adım girişi | 300ms + 40ms stagger | `cubic-bezier(0.16,1,0.3,1)` |
| Parametre bloğu açılma | 200ms | ease-out |
| Çalışan adım nabzı | 1.6s döngü | ease-in-out |

Yalnızca `transform`/`opacity`. `prefers-reduced-motion`'da nabız durur, stagger tek fade olur.

---

## 6. Üretici kural

> **Kullanıcı "şu an ne oluyor ve neden" sorusunu ekrana bakarak cevaplayamıyorsa, o ekran yanlıştır.**

Şeffaflık ürünün kendisi. Gizlenen her şey — spinner arkasındaki iş, özetlenen parametre, sessizce atlanan adım — bu kurala aykırı.
