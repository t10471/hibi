# Hibi

English | [日本語](README.ja.md)

**Hibi** is a personal daily habit and investment assistant composed of a **Kotlin/Jetpack Compose Android app**, a **React/TypeScript Web portal**, **Firebase (Auth, Firestore, Hosting, Cloud Functions, FCM)**, and a **GitHub Actions Gemini AI pipeline**.

---

## Features

| Module | Platforms | Highlights |
| --- | --- | --- |
| **Timer** | Android | Tabata-style HIIT timer (`prepare -> work -> rest`) with circular progress indicator and 3-second countdown tones. |
| **Workout** | Android / Web | Training menu management, Google Sheets import, 1-tap target-delta chips (`missed` / `on target` / `+1` / `+2` / `+3+` reps), and Gemini coaching based on session-to-session diffs. |
| **Meds** | Android / Web | Medication and supplement reminder schedule with daily checkoff (auto-resets per day). |
| **Since** | Android / Web | Elapsed-day tracker for recurring events and maintenance tasks. |
| **Stocks** | Android / Web / Actions | Long/medium-term investment scenario manager (`HOLDING` / `WATCH` / `ARCHIVED`), split purchase steps (`BuyStep` with `notifyDate` 08:30 Android reminders), and automated AI analysis reports. |

---

## Automated Stock Analysis Pipeline

Details are documented in [docs/stock-analysis-flow.md](docs/stock-analysis-flow.md) (日本語: [docs/stock-analysis-flow.ja.md](docs/stock-analysis-flow.ja.md)). For coding agent rules, see [AGENTS.md](AGENTS.md).

- **Local YouTube Summarizer (Mac mini `launchd`, weekdays `07:50` & `19:30`, Saturdays `11:30 JST`)**
  - Extracts captions from `@yasutakaushiroda` via `yt-dlp` and summarizes them with `gemini` CLI under a Google AI Pro account ($0 API billing) into Firestore `stock_channel_briefs` (Cloud Functions falls back to Atom RSS titles/chapters if missing).
- **Weekday Daily Check (`08:17 JST`, Cloud Scheduler `17 8 * * 1-5` `Asia/Tokyo`)**
  - Single-call review (`dailyStockAnalysis`) via `gemini-4-neon` with Google Search grounding, `stock_knowledge`, and `stock_channel_briefs`.
  - Summarizes macro conditions and evaluates every active `HOLDING` (`ADD` / `HOLD` / `TRIM`) and `WATCH` (`BUY` / `WATCH` / `DROP`) stock with current-vs-target price diagnosis and pending schedule updates.
  - Sends an FCM push notification when `BUY` / `ADD` / `GO` signals appear.
- **Monday Weekly Discovery & Self-Reflection (`08:43 JST`, Cloud Scheduler `43 8 * * 1` `Asia/Tokyo`)**
  - Runs after `07:50` YouTube sync and `08:17` daily check as a single-call review (`weeklyStockDiscovery`) via `gemini-4-hydrogen` with Google Search grounding.
  - Evaluates medium/long-term sector trends (`6 months – 3 years`), reviews the past 1–4 weeks of picks/verdicts, proposes 2–3 non-duplicate Watch candidates with split buy steps and `notifyDate` reminders, and saves up to 2 generalized `AI自動振り返り` lessons into `stock_knowledge` (Human-on-the-loop: manual and pinned entries are never auto-deleted).
  - Candidates can be toggled into or out of `WATCH` directly via checkboxes in both Web and Android (with `HOLDING` protection against accidental deletion).
- **Dual Cost & Safety Controls**
  - **Gemini cap**: Tracks estimated monthly Gemini usage in `gemini_usage/{YYYY-MM}` and enforces an automation kill switch and monthly JPY cap via `stock_settings/automation`.
  - **Infrastructure cap (Blaze)**: `stopBillingOnBudget` listens to Cloud Billing Budget Alerts on Pub/Sub topic `budget-alerts` and disables project billing when `costAmount >= budgetAmount`.
  - Protected by owner-only Firebase Auth and [firestore.rules](firestore.rules).

---

## Repository Structure

```text
app/                       # Kotlin / Jetpack Compose Android app
web/                       # React + TypeScript + Vite Web portal (Firebase Hosting)
functions/                 # Firebase Cloud Functions (Node.js: reminders & budget stop)
functions-stock/           # Firebase Cloud Functions (Python 3.12: daily/weekly Gemini pipeline)
scripts/stock-analysis/    # CLI wrapper & unit tests sharing functions-stock/stock_pipeline.py
.agents/skills/            # Agent Skills loaded by the stock analysis pipeline
.github/workflows/         # GitHub Actions manual/backup workflow (stock-analysis.yml)
docs/                      # Architecture and workflow documentation
```

---

## Tech Stack

- **Android**: Kotlin 2.0.21, Jetpack Compose, Material 3, DataStore, Firebase Auth / Firestore / Messaging
- **Web**: React 18, TypeScript, Vite, Firebase Web SDK / Hosting
- **Automation**: Python 3.12, Gemini REST API (Google Search grounding), Firebase Admin SDK, GitHub Actions
- **Backend**: Node.js 20, TypeScript, Firebase Cloud Functions v2

---

## Common Commands

### Android

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew installDebug
```

### Web

```sh
npm --prefix web run build
npx firebase deploy --only hosting
```

### Stock Analysis Pipeline Tests

```sh
python -m unittest discover -s scripts/stock-analysis -p "test_*.py" -v
```
