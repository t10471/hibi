# Hibi Python 株分析＆ローカル YouTube 連携（`functions-stock/`・`scripts/stock-analysis/`）

[English](AGENTS.md) | 日本語

Python 3.13 で動作する Cloud Functions v2（`asia-northeast1`）および macOS `launchd` 用ローカル YouTube 要約スクリプト群です。

> パイプライン全体フローとスケジュール詳細：[../docs/stock-analysis-flow.ja.md](../docs/stock-analysis-flow.ja.md)

---

## 1. モジュール構成

- `functions-stock/`
  - `main.py` — Cloud Scheduler v2 トリガー定義
    - `dailyStockAnalysis`（`17 8 * * 1-5` `Asia/Tokyo` / 平日 08:17 JST）
    - `weeklyStockDiscovery`（`43 8 * * 1` `Asia/Tokyo` / 月曜 08:43 JST）
  - `stock_pipeline.py` — 日次・週次プロンプト実行（`run_daily()`, `run_weekly()`）と公開シンボル再エクスポート
  - `gemini_client.py` — `GeminiClient`・モデル単価計算・思考パート除外
  - `scenario_parser.py` — 銘柄キー正規化・分割ステップ補完・破損 JSON リカバリ
  - `firestore_store.py` — Firestore 読み書き・ナレッジ自動整理・予算ガード・FCM 通知
  - `youtube_channel.py` — YouTube Atom RSS 解析・`stock_channel_briefs` 読込・RSS 目次フォールバック
- `scripts/stock-analysis/`
  - `run_pipeline.py` — `functions-stock/stock_pipeline.py` を呼び出す CLI ラッパー
  - `youtube_local_sync.py` — ローカル Mac 用（`yt-dlp` 字幕抽出＋`gemini` CLI 要約 → `stock_channel_briefs` 保存）
  - `run_youtube_local.sh` — `mise` / Homebrew `PATH` を設定して起動するラッパー
  - `install_launchd.sh` — `com.hibi.youtube-sync`（平日 07:50・19:30 / 土曜 11:30 JST）の `launchd` 登録スクリプト
  - `test_pipeline.py` — 株分析パイプライン単体テスト
  - `test_youtube_channel.py` — YouTube RSS・VTT 整形・要約読込単体テスト

---

## 2. Python 固有の設計・実装ルール

- **ジョブ実行順と依存関係を守る**
  - 必ず次の順番で動くように維持します：
    - 1. YouTube 要約同期（07:50 JST）
    - 2. `dailyStockAnalysis`（08:17 JST）
    - 3. `weeklyStockDiscovery`（月曜 08:43 JST）
- **Cloud Functions にローカル CLI 依存を持ち込まない**
  - `functions-stock/` 側では `yt-dlp` やサブプロセス CLI を呼ばず、`scripts/stock-analysis/` 側に隔離します。
- **`stock_pipeline.py` の再エクスポートを維持する**
  - `main.py`、`run_pipeline.py`、`test_pipeline.py` との互換性を保つため、`stock_pipeline.py` の `__all__` と公開インポートを維持します。
- **Human-on-the-loop の既存ナレッジを保護する**
  - `save_learned_knowledge()` は手動作成項目と `isPinned: true` の項目を絶対に自動削除しません。
- **ログに機密情報・銘柄情報を出さない**
  - 標準出力・標準エラー出力には銘柄ティッカー・シナリオ本文・API キー・生の Gemini 応答を出力しません。

---

## 3. コマンド

```zsh
# 単体テスト（Cloud Functions 用 venv ＆ ローカル同期用 venv）
functions-stock/venv/bin/python -m unittest discover -s scripts/stock-analysis -p "test_*.py" -v
scripts/stock-analysis/.venv/bin/python -m unittest discover -s scripts/stock-analysis -p "test_*.py" -v

# ローカル YouTube 同期の確認と launchd 登録
./scripts/stock-analysis/run_youtube_local.sh --dry-run --skip-llm --limit 1
./scripts/stock-analysis/run_youtube_local.sh --limit 1
./scripts/stock-analysis/install_launchd.sh

# Cloud Functions と Firestore ルールのデプロイ
firebase deploy --only functions:stock,firestore:rules
```
