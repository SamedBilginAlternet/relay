# Relay — Tasarım Dili v2

> Referans: **n11 projesi** — beyaz zemin, tek güçlü vurgu rengi, siyaha yakın metin,
> ince çizgiler, yumuşak gölge. Sade ve kurumsal; koyu tema ve gradyan YOK.

## 1. Renk

```
--bg           #FFFFFF   sayfa zemini
--bg-subtle    #FAFAFB   panel arka planı, satır hover
--surface      #FFFFFF   kart — zemin farkı gölgeyle, renkle değil
--border       #E5E7EB   ince çizgi (n11 line)
--fg           #111111   birincil metin (n11 black)
--fg-muted     #444444   ikincil metin (n11 gray)
--fg-faint     #9CA3AF   üçüncül / placeholder

--accent       #6D28D9   Relay moru — koyu, beyaz üstünde AA (6.9:1)
--accent-soft  #F3EEFD   seçili satır, rozet zemini
--accent-line  #DDD0F7   vurgu kenarlığı

--info         #2563EB   okuma adımı · seçim (seçili sekme çizgisi/etiketi, sekme sayaçları — #151)
--warn         #B45309   sana takılan iş: onay bekleyen durum, kapı, bekleyen kart (amber-700, beyaz üstünde AA)
--success      #15803D   tamamlanan
--danger       #DC2626   hata / red
```

Kurallar:
- **Gradyan yok. Koyu tema yok.** Landing dahil her yer beyaz.
- Vurgu yalnızca: birincil buton, aktif adım, ilerleme, logo.
- Durum renkleri her zaman ikon + metinle (renk tek başına anlam taşımaz).
- Gölge tek tanım: `0 1px 2px rgba(0,0,0,.04), 0 1px 12px rgba(0,0,0,.04)` (n11 soft).

## 2. Tipografi

Tek aile **Inter** (variable, self-host). Parametre/JSON için **JetBrains Mono**.

| Rol | Boyut | Ağırlık |
|---|---|---|
| display | `clamp(36px, 6vw, 56px)` | 700, -0.02em |
| title | 20px | 600 |
| body | 15px | 400, satır 1.55 |
| caption | 13px | 400 |
| label | 12px | 600, +0.06em, UPPERCASE, `--fg-muted` |
| mono | 13px | 400 |

## 3. Bileşenler

**Buton** — pill kalır (`border-radius: 999px`, 10px/18px):
- Primary: `--accent` dolgu, beyaz metin
- Secondary: beyaz zemin, `1px --border`, `--fg` metin
- Ghost: metin + alt çizgi, `--fg-muted`
- `:active { transform: scale(0.97) }`

**Kart/panel** — beyaz, `1px --border`, `12px` yarıçap, soft gölge. Koyu zemin farkı yerine çizgi + gölge.

**Adım satırı** — 56px, solda durum ikonu (renkli), başlık `--fg`, araç adı mono `--fg-muted`. Seçili: `--accent-soft` zemin + `--accent-line` sol kenar 3px.

**Onay kapısı** — satır içinde `Onayla` (primary) / `Reddet` (secondary) pilleri; red gerekçesi tek satır input.

**Maliyet çubuğu** — panel üstünde ince şerit: `--bg-subtle` zemin, mono rakamlar, bütçe aşımında `--warn`.

**Sohbet** — kullanıcı balonu `--accent-soft`; ajan yanıtı balonsuz düz metin.

## 4. Hareket

Değişmedi: 250ms durum, 300ms+40ms stagger giriş, 200ms genişleme, çalışan adımda 1.6s nabız (opacity 1↔0.55). Yalnızca transform/opacity. `prefers-reduced-motion` desteklenir.

## 5. Logo

Wordmark: "relay" küçük harf, Inter 700, `--fg`; başındaki "r" `--accent`. İkon: mor yuvarlak zeminde beyaz bayrak-devri (relay baton) işareti — Higgsfield ile üretilen asset `frontend/public/` altında (`logo.svg` / `logo-mark.png`, favicon).

## 6. Üretici kural

> Kullanıcı "şu an ne oluyor ve neden" sorusunu ekrana bakarak cevaplayamıyorsa, o ekran yanlıştır.

Şeffaflık üründür. İkinci kural: **beyaza güven** — bir öğeyi ayırt etmek için önce boşluk ve çizgi dene, renk son çare.
