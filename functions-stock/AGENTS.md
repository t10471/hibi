# Hibi Python Stock Analysis & Local YouTube Sync (`functions-stock/` & `scripts/stock-analysis/`)

English | [日本語](AGENTS.ja.md)

Python 3.13 Cloud Functions v2 pipeline (`asia-northeast1`) plus local macOS `launchd` YouTube summarization scripts.

> Pipeline architecture & sequence details: [../docs/stock-analysis-flow.md](../docs/stock-analysis-flow.md)

---

## 1. Module Structure

- `functions-stock/`
  - `main.py` — Cloud Scheduler v2 triggers:
    - `dailyStockAnalysis` (`17 8 * * 1-5` `Asia/Tokyo`, 08:17 JST)
    - `weeklyStockDiscovery` (`43 8 * * 1` `Asia/Tokyo`, Mondays 08:43 JST)
  - `stock_pipeline.py` — Daily/weekly prompt orchestration (`run_daily()`, `run_weekly()`) and public symbol re-exports
  - `gemini_client.py` — `GeminiClient`, model pricing tables, and thought-part filtering
  - `scenario_parser.py` — Ticker normalization, split-step backfill, and trailing JSON recovery
  - `firestore_store.py` — Firestore read/write helpers, knowledge pruning, budget guard, and FCM push alerts
  - `youtube_channel.py` — YouTube Atom RSS parser, `stock_channel_briefs` loader, and RSS chapter fallback
- `scripts/stock-analysis/`
  - `run_pipeline.py` — Local/CLI wrapper delegating to `functions-stock/stock_pipeline.py`
  - `youtube_local_sync.py` — Local Mac summarizer (`yt-dlp` VTT captions + `gemini` CLI -> `stock_channel_briefs`)
  - `run_youtube_local.sh` — `launchd` runner wrapper with `mise` / Homebrew `PATH`
  - `install_launchd.sh` — Installs `com.hibi.youtube-sync` (`07:50`, `19:30` weekdays; `11:30` Saturday JST)
  - `test_pipeline.py` — Stock pipeline unit tests
  - `test_youtube_channel.py` — YouTube RSS, VTT cleanup, and brief loader tests

---

## 2. Python-Specific Design & Implementation Rules

- **Respect the execution dependency chain**:
  - Maintain the order:
    - 1. YouTube brief sync (`07:50 JST`)
    - 2. `dailyStockAnalysis` (`08:17 JST`)
    - 3. `weeklyStockDiscovery` (Mondays `08:43 JST`)
- **Keep Cloud Functions dependency-light**:
  - Do NOT import `yt-dlp` or CLI subprocess helpers inside `functions-stock/`; keep local CLI dependencies isolated to `scripts/stock-analysis/`.
- **Preserve `stock_pipeline.py` re-exports**:
  - Keep `__all__` and re-exported public symbols intact in `stock_pipeline.py` so `main.py`, `run_pipeline.py`, and tests never break.
- **Protect Human-on-the-loop data**:
  - In `save_learned_knowledge()`, never auto-delete manually created entries or `isPinned: true` entries.
- **Zero sensitive output in logs**:
  - Never print ticker symbols, scenario contents, API keys, or raw Gemini responses to stdout/stderr.

---

## 3. Commands

```zsh
# Unit tests (Cloud Functions venv & local sync venv)
functions-stock/venv/bin/python -m unittest discover -s scripts/stock-analysis -p "test_*.py" -v
scripts/stock-analysis/.venv/bin/python -m unittest discover -s scripts/stock-analysis -p "test_*.py" -v

# Local YouTube sync test & launchd install
./scripts/stock-analysis/run_youtube_local.sh --dry-run --skip-llm --limit 1
./scripts/stock-analysis/run_youtube_local.sh --limit 1
./scripts/stock-analysis/install_launchd.sh

# Deploy Cloud Functions & Firestore rules
firebase deploy --only functions:stock,firestore:rules
```
