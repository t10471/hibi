# Hibi Web Console (`web/`)

English | [日本語](AGENTS.ja.md)

React 18 + TypeScript + Vite web portal deployed to Firebase Hosting (`https://kabu-99c48.web.app`).

---

## 1. Key Modules & Boundaries

- `src/App.tsx` — Owner auth shell (`ALLOWED_EMAIL` from `src/config/auth.ts`) and section/tab routing.
- `src/hooks/` — Reusable Firestore subscriptions and derived state:
  - `useStockScenarios.ts` — Shared real-time `stock_scenarios` stream and active/holding/watch symbol sets.
- `src/utils/` — Pure helpers shared across pages:
  - `verdictMeta.ts` — Single source of truth for `effectiveStatus()`, `statusMeta()`, and `verdictBadgeMeta()`.
  - `dateUtils.ts` — ISO date and recent-day formatting (`MedsPage.tsx`).
  - `geminiUsage.ts` / `geminiWorkout.ts` — Monthly Gemini usage subscription and workout consultation client.
  - `googleSheetsApi.ts` / `sheetParser.ts` — Google Sheets training import pipeline.
- `src/pages/` & `src/ListPage.tsx` / `src/RegisterPage.tsx` — Feature pages (`ListPage.tsx` and `RegisterPage.tsx` remain at `src/` root for historical routing compatibility).

---

## 2. Web Rules & Gotchas

- **Never cross-import between pages**:
  - Do not import functions or components from `ListPage.tsx`, `RegisterPage.tsx`, or `pages/*` into another page.
  - Put shared helpers in `src/utils/`, shared subscriptions in `src/hooks/`, and constants in `src/config/`.
- **Keep verdict copy consistent with Android**:
  - Always format action verdicts via `verdictBadgeMeta()` in `src/utils/verdictMeta.ts` so `WATCH`/`WAIT` consistently shows "do not buy yet" (`✋ 様子見・まだ買わない`).

---

## 3. Commands

```zsh
# Type-check & production bundle
npm --prefix web run build

# Deploy to Firebase Hosting
firebase deploy --only hosting
```
