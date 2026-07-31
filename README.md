# Relay

**Beyaz yakalı işini sohbette anlatırsın; Relay bir iş akışı kurar, araçların arasında yürütür ve her adımı gözünün önünde yapar.**

> Otomasyon kurmuyorsun, iş veriyorsun — ve ne yaptığını satır satır görüyorsun.

| Doküman | İçerik |
|---|---|
| [docs/PRD.md](docs/PRD.md) | Problem, çekirdek döngü, entegrasyon kararları, kapsam, metrikler, riskler |
| [docs/DESIGN.md](docs/DESIGN.md) | Tasarım dili (Mindra referanslı), renk, tipografi, bileşenler |

## Stack

- **Backend:** Java 21 + Spring Boot 3, PostgreSQL
- **Frontend:** React + Vite + TypeScript
- **Entegrasyon:** Jira (API token) · Slack (bot token)
- **Deploy:** Docker + Coolify, n11 paylaşımlı Caddy edge'i arkasında

## Durum

48 saatlik hackathon. Kapsam kilitli — bkz. PRD §6.
