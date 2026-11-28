# Hibi — リポジトリ全体ガイド（コーディングエージェント向け）

[English](AGENTS.md) | 日本語

Hibi はマルチプラットフォーム対応の習慣・投資シナリオ管理アプリです。このルートファイルではリポジトリ全体の共通ルールのみを定め、各アプリの詳細構成・個別実装ルール・コマンドはそれぞれのディレクトリ配下の `AGENTS.ja.md`（英語は `AGENTS.md`）に分離しています。

> 利用者向け概要：[README.ja.md](README.ja.md) ／ 株分析フロー：[docs/stock-analysis-flow.ja.md](docs/stock-analysis-flow.ja.md)

---

## 1. リポジトリ構成（トップディレクトリ）

- `app/` — Android アプリ（Kotlin / Jetpack Compose）→ [app/AGENTS.ja.md](app/AGENTS.ja.md)
- `web/` — Web コンソール（React / TypeScript / Vite）→ [web/AGENTS.ja.md](web/AGENTS.ja.md)
- `functions-stock/` — Python 3.13 株分析 Cloud Functions v2 → [functions-stock/AGENTS.ja.md](functions-stock/AGENTS.ja.md)
- `functions/` — TypeScript リマインダー＆予算停止 Cloud Functions v2 → [functions/AGENTS.ja.md](functions/AGENTS.ja.md)
- `scripts/stock-analysis/` — ローカル CLI・macOS `launchd` YouTube 同期・Python 単体テスト（詳細は [functions-stock/AGENTS.ja.md](functions-stock/AGENTS.ja.md)）
- `.agents/skills/` — デプロイ時に `functions-stock/skills/` へ同梱される Agent Skills
- `docs/` — アーキテクチャ・フロー設計ドキュメント

---

## 2. リポジトリ共通ルール

- **単一責務の維持**
  - 既存ファイルの肥大化を避け、新機能は UI 部品・フック・パーサー・データソースへ切り出します。
  - アプリ固有のルールは、各アプリ配下の `AGENTS.md` / `AGENTS.ja.md` に記載します。
- **Firestore の権限とスキーマ互換性**
  - [firestore.rules](firestore.rules) の `isOwner()` によるオーナー限定アクセスを維持し、権限を緩めません。
  - Android / Web / Python 間で共有する以下コレクションの互換性を保ちます：
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
- **機密情報・公開リポジトリ衛生管理**
  - 以下のファイルや情報はリポジトリへコミットしません：
    - `.env*`
    - `*firebase-adminsdk*.json`
    - `service-account*.json`
    - `google-services.json`
    - API キーや個人環境固有のパス・個人情報
