# Relay — Frontend

React + Vite + TypeScript (strict). Tasarım dili: [`../docs/DESIGN.md`](../docs/DESIGN.md).
API sözleşmesi: [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) §5 — `src/types/api.ts` bu
sözleşmenin birebir karşılığıdır.

## Çalıştırma

```bash
npm install
npm run dev        # http://localhost:5173  (varsayılan: mock veri kaynağı)
npm run build      # tsc -b && vite build -> dist/
npm run preview    # üretim çıktısını yerelde sun
```

## Veri kaynağını değiştirmek: mock ↔ api

Arayüz asla doğrudan `fetch` çağırmaz; her şey `RunSource` arayüzünün arkasındadır
(`src/data/RunSource.ts`).

| Uygulama | Ne yapar |
|---|---|
| `MockRunSource` | Senaryolu bir akışı zamanlayıcıyla oynatır: 7 adım, 2 onay kapısı, ajanlar arası mesajlar, canlı maliyet. Backend gerekmez. Geçmiş ekranı için 3 hazır akış içerir. |
| `ApiRunSource` | Gerçek REST uçları + `EventSource` ile SSE. Bağlantı koptuğunda geri çekilmeli (backoff) yeniden bağlanır ve **her yeniden bağlanmada `GET /api/runs/{id}` ile boşluğu doldurur** (SSE'de replay yok). |

Seçim ortam değişkenleriyle yapılır (`.env.example` dosyasını `.env.local` olarak kopyala):

```bash
VITE_RUN_SOURCE=mock          # mock | api   (varsayılan: mock)
VITE_API_BASE_URL=/api        # varsayılan: /api
VITE_DEV_API_TARGET=http://localhost:8080   # sadece `npm run dev` proxy hedefi
```

Gerçek backend'e geçmek:

```bash
VITE_RUN_SOURCE=api VITE_API_BASE_URL=/api npm run dev
# /api istekleri VITE_DEV_API_TARGET adresine proxy'lenir (varsayılan :8080)
```

Docker/Coolify build'inde bu değerler **build arg** olarak geçer:

```dockerfile
ARG VITE_RUN_SOURCE
ARG VITE_API_BASE_URL
```

> ⚠️ Ortam değişkenleri kodda `||` ile okunur, `??` ile değil. Docker build arg'ları
> tanımsızken **boş string** olarak gelir; `??` boş string'i geçerli değer sayıp uygulamayı
> hiçbir yere bakmayan bir API adresine kilitler.

## Ekranlar

| Yol | Ekran |
|---|---|
| `#/` | Sohbet + canlı iş akışı (masaüstü %55/%45 iki sütun, mobilde alttan açılan sheet) |
| `#/history` | Geçmiş akışlar |
| `#/history/{runId}` | Salt-okunur denetim izi (adımlar, parametreler, ajan mesajları) |
| `#/connections` | Jira / Slack token girişi (maskeli) + sağlayıcı başına `Test et` |

Yönlendirme hash tabanlıdır (router kütüphanesi yok) — statik sunucuda rewrite kuralı gerekmez.

## Mimari

```
src/
  types/api.ts          ARCHITECTURE.md §5 tipleri (tek doğru kaynak)
  data/
    RunSource.ts        arayüz — UI yalnızca bunu bilir
    ApiRunSource.ts     REST + SSE (yeniden bağlanma + boşluk doldurma)
    MockRunSource.ts    senaryolu akış (onay kapıları burada gerçekten bekler)
    mockScript.ts       demo senaryosu + hazır geçmiş
    applyEvent.ts       saf reducer: (run, event) -> run   [store ve mock ortak kullanır]
    index.ts            ortam değişkenine göre kaynak seçimi
  store/runStore.ts     zustand — aktif akış, SSE durumu, onay/ret aksiyonları
  components/           CostBar, StepRow, ParamBlock, ChatPanel, WorkflowPanel, BottomSheet…
  screens/              ChatScreen, HistoryScreen, RunDetailScreen, ConnectionsScreen
  styles/global.css     DESIGN.md token'ları ve bileşen stilleri
```

## Kurallar

- **Boş ekran yok.** Yükleniyor, boş, hata ve çevrimdışı durumlarının hepsi bir şey söyler.
  Akışın adımı yoksa "Henüz adım yok" açıkça yazar.
- **Renk tek başına sinyal değil.** Adım durumu renk **ve** Lucide ikonuyla gösterilir
  (DESIGN.md §1 eşlemesi birebir uygulanır).
- **Hareket** yalnızca `transform`/`opacity`; `prefers-reduced-motion` açıkken nabız durur,
  stagger tek fade'e düşer.
- **Erişilebilirlik:** dokunma hedefleri ≥44px, görünür odak halkası, adım zaman çizelgesinde
  `role="status"` + `aria-live="polite"`.
