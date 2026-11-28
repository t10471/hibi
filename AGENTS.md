# Hibi — Repository Guide for Coding Agents

English | [日本語](AGENTS.ja.md)

Hibi is a multi-platform personal habit and investment assistant. This root guide defines repository-wide conventions; each application directory contains its own `AGENTS.md` with module details, local rules, and commands.

> End-user overview: [README.md](README.md) · Stock analysis flow: [docs/stock-analysis-flow.md](docs/stock-analysis-flow.md)

---

## 1. Top-Level Repository Layout

- `app/` — Android app (Kotlin / Jetpack Compose) → [app/AGENTS.md](app/AGENTS.md)
- `web/` — Web console (React / TypeScript / Vite) → [web/AGENTS.md](web/AGENTS.md)
- `functions-stock/` — Python 3.13 Cloud Functions v2 for stock analysis → [functions-stock/AGENTS.md](functions-stock/AGENTS.md)
- `functions/` — TypeScript Cloud Functions v2 for reminders & billing guard → [functions/AGENTS.md](functions/AGENTS.md)
- `scripts/stock-analysis/` — Local CLI, macOS `launchd` YouTube sync, and Python unit tests (see [functions-stock/AGENTS.md](functions-stock/AGENTS.md))
- `.agents/skills/` — Domain Agent Skills bundled into `functions-stock/skills/` on deploy
- `docs/` — Architecture and pipeline workflow docs

---

## 2. Repository-Wide Rules

- **Single-responsibility modularity**:
  - Keep modules small and focused; extract new UI components, hooks, parsers, or data sources instead of growing existing mega-files.
  - Keep app-specific rules inside each app's `AGENTS.md`.
- **Cross-platform Firestore compatibility**:
  - Enforce owner-only access in [firestore.rules](firestore.rules) (`isOwner()`); never relax rules.
  - Preserve backwards-compatible document schemas across Android, Web, and Python for shared collections:
    - `stock_scenarios`
    - `stock_knowledge`
    - `stock_reports`
    - `stock_channel_briefs`
    - `stock_settings`
    - `gemini_usage`
    - `fcm_tokens`
    - `workout_menus`
    - `workout_logs`
    - `medications`
    - `tracked_events`
- **Security & public repository hygiene**:
  - Never commit secrets or local credential files:
    - `.env*`
    - `*firebase-adminsdk*.json`
    - `service-account*.json`
    - `google-services.json`
    - Personal emails or local machine usernames/paths in templates
