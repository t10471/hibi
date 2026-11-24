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

Details are documented in [docs/stock-analysis-flow.md](docs/stock-analysis-flow.md) (Japanese).

- **Weekday Daily Check (`08:17 JST`, `cron: '17 23 * * 0-4'`)**
  - Single-call review via `gemini-4-neon` with Google Search grounding.
  - Summarizes macro market conditions and evaluates every active `HOLDING` (`ADD` / `HOLD` / `TRIM`) and `WATCH` (`BUY` / `WATCH` / `DROP`) stock with current-vs-target price diagnosis and pending schedule updates.
  - Sends an FCM push notification when `BUY` / `ADD` / `GO` signals appear.
- **Monday Weekly Discovery (`07:43 JST`, `cron: '43 22 * * 0'`)**
  - Single-call review via `gemini-4-hydrogen` with Google Search grounding.
  - Evaluates medium/long-term sector trends (`6 months – 3 years`) and proposes 2–3 non-duplicate Watch candidates with split buy steps and `notifyDate` reminders.
  - Candidates can be toggled into or out of `WATCH` directly via checkboxes in both Web and Android (with `HOLDING` protection against accidental deletion).
- **Cost & Safety Controls**
  - Tracks estimated monthly Gemini usage in `gemini_usage/{YYYY-MM}` and enforces an automation kill switch and monthly JPY cap via `stock_settings/automation`.
  - Protected by owner-only Firebase Auth and [firestore.rules](firestore.rules).

---

## Repository Structure

```text
app/                       # Kotlin / Jetpack Compose Android app
web/                       # React + TypeScript + Vite Web portal (Firebase Hosting)
functions/                 # Firebase Cloud Functions
scripts/stock-analysis/    # Python Gemini stock analysis pipeline & unit tests
.agents/skills/            # Agent Skills loaded by the stock analysis pipeline
.github/workflows/         # GitHub Actions automation (stock-analysis.yml)
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
