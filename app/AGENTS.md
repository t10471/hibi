# Hibi Android App (`app/`)

English | [日本語](AGENTS.ja.md)

Kotlin 2.0.21 + Jetpack Compose (Material 3) Android app backed by DataStore, Firebase (Auth / Firestore / FCM), and Health Connect.

---

## 1. Key Entrypoints & Architecture Seams

- `MainActivity.kt` & `HibiApp.kt` — Notification permission prompt, bottom tab navigation, and owner-only auth gate (`AuthManager.isOwnerSignedIn()`).
- `data/` — Persistence and remote data boundaries:
  - `HibiRepository.kt` — Local Preferences DataStore cache (single source of truth for offline/local state and `geminiApiKey`).
  - `StockFirestoreDataSource.kt` — Firestore snapshot listeners and CRUD mutations for stock collections.
  - `StockScenarioMapper.kt` — Merges remote Firestore `stock_scenarios` with local `isDone` / `isNotified` flags.
  - `GeminiApiClient.kt` — Shared REST client targeting `gemini-4-hydrogen` with automatic `gemini-flash-latest` fallback and usage tracking via `GeminiUsageTracker.kt`.
- `feature/` — Screen + ViewModel per feature (`timer`, `workout`, `meds`, `since`, `stock`):
  - Keep `stock/` UI split across `StockScreen.kt` (shell/tabs), `StockScenarioCard.kt`, `StockConsultationView.kt`, `StockReportsView.kt`, `StockKnowledgeView.kt`, and shared `StockVerdictMeta.kt`.
- `notify/` — Local `AlarmManager` schedulers (`MedScheduler`, `StockScheduler` at 08:30) and `HibiFirebaseMessagingService` (`fcm_tokens` registration + push routing).

---

## 2. Android Rules & Gotchas

- **Keep Firestore I/O out of ViewModels & Screens**:
  - Encapsulate `Firebase.firestore` listeners and serialization in `data/*DataSource` classes (`StockFirestoreDataSource`), not inside ViewModels.
  - Always persist merged Firestore `stock_scenarios` snapshots directly into `HibiRepository` inside the ViewModel listener rather than relying on background `StockScheduler` runs.
- **Unambiguous buy/wait UX**:
  - Use `verdictUiMeta()` in `StockVerdictMeta.kt` across both scenario cards and report chips so `WATCH`/`WAIT` always renders clearly as "do not buy yet" (`✋ 様子見 (WATCH)`).
- **Keep Screens modular**:
  - Extract new cards, dialogs, or chat sub-views into dedicated files under `feature/<name>/` instead of adding to large `*Screen.kt` files.

---

## 3. Commands

```zsh
# Unit tests & debug APK build
JAVA_HOME="$HOME/.local/share/mise/installs/java/global" ./gradlew testDebugUnitTest assembleDebug

# Install onto connected Pixel / emulator
JAVA_HOME="$HOME/.local/share/mise/installs/java/global" ./gradlew installDebug
```
