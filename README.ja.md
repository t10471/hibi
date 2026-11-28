# Hibi

[English](README.md) | 日本語

Hibi は、日々の習慣管理と中長期の株式投資シナリオ管理をまとめたパーソナルアプリです。Android アプリ（Kotlin / Jetpack Compose）、Web コンソール（React / TypeScript）、Firebase（Auth / Firestore / Hosting / Cloud Functions v2 / FCM）、およびローカル Mac 連携＋Gemini API 自動分析パイプラインで構成されています。

> 開発・エージェント向けの規約やテストコマンドは [AGENTS.ja.md](AGENTS.ja.md)（英語版: [AGENTS.md](AGENTS.md)）を参照してください。

---

## 主な機能

| 機能 | 対応環境 | 概要 |
| --- | --- | --- |
| **タイマー** | Android | タバタ式 HIIT タイマー（準備 → 運動 → 休憩）。円形プログレス表示と終了 3 秒前のカウントダウン音に対応。 |
| **筋トレ** | Android / Web | メニュー管理、Google スプレッドシート取込、1 タップ達成度チップ（`未達` / `規定どおり` / `+1回超` / `+2回超` / `+3回+`）、前回実績との差分を踏まえた Gemini AI コーチング。 |
| **くすり** | Android / Web | 薬・サプリの服用時刻リマインダーと当日分チェック（日付変更で自動リセット）。 |
| **記録（Since）** | Android / Web | 定期メンテナンスやイベントからの経過日数を記録・管理。 |
| **かぶ（投資シナリオ）** | Android / Web / Cloud Functions | 保有中（`HOLDING`）・注目/様子見（`WATCH`）・見送り（`ARCHIVED`）の管理、分割購入ステップ（`notifyDate` による毎朝 08:30 Android 通知）、売買判断バッジ（`✋ 様子見・まだ買わない (WATCH)` / `🚀 買い好機` / `⛔ 買いNG・警戒` 等）、AI 自動分析レポート連携。 |

---

## 株 AI 自動定期分析パイプライン

詳細な処理フローや Blaze 移行・予算自動停止・YouTube 参考チャンネル連携の仕組みは [docs/stock-analysis-flow.ja.md](docs/stock-analysis-flow.ja.md)（英語版: [docs/stock-analysis-flow.md](docs/stock-analysis-flow.md)）を参照してください。

- **ローカル YouTube 要約連携（Mac mini `launchd` / 平日 07:50・19:30、土曜 11:30 JST）**
  - 参考チャンネル（`@yasutakaushiroda`「後田泰孝のマーケット解説」）の公式 RSS と `yt-dlp` の日本語字幕を取得し、Google AI Pro 枠の `gemini` CLI で要点・言及銘柄・リスクを要約して Firestore `stock_channel_briefs` へ保存します（API 従量課金なし）。
  - Mac がスリープ中等で未要約の場合は、Cloud Functions 側が公式 RSS のタイトルと概要欄の目次へ自動フォールバックします。
- **平日毎朝チェック（`08:17 JST` / Cloud Scheduler `17 8 * * 1-5` `Asia/Tokyo`）**
  - `gemini-4-neon` + Google Search グラウンディングの 1 コール実行（`dailyStockAnalysis`）。
  - 蓄積済み `stock_knowledge` と直近の `stock_channel_briefs` を参考にしつつ、一次情報・最新株価で裏取りを行い、市場地合いの要点と保有銘柄（`ADD` / `HOLD` / `TRIM`）・Watch 銘柄（`BUY` / `WATCH` / `DROP`）の診断・未購入ステップを更新します。
  - `BUY` / `ADD` / `GO` シグナルが出た場合は FCM プッシュ通知を送信します。
- **毎週月曜の週次発掘＆振り返り（`08:43 JST` / Cloud Scheduler `43 8 * * 1` `Asia/Tokyo`）**
  - YouTube 要約（`07:50`）と月曜の日次判定（`08:17`）の後に実行される、`gemini-4-hydrogen` + Google Search グラウンディングの 1 コール実行（`weeklyStockDiscovery`）。
  - 中長期（6 ヶ月〜2〜3 年）のセクター動向、過去 1〜4 週間の答え合わせ、重複しない新規 Watch 候補（2〜3 銘柄・`notifyDate` 付き）を生成し、新しい学び（0〜2 件）を `stock_knowledge` へ自動保存します（Human-on-the-loop：手動登録項目やピン留め項目は保護）。
- **二重のコスト・セキュリティ制御**
  - **Gemini 上限停止**：`gemini_usage/{YYYY-MM}` で月間推計コストを集計し、`stock_settings/automation` の手動停止スイッチおよび月額上限（JPY）で自動停止します。
  - **インフラ課金自動停止（Blaze）**：`stopBillingOnBudget` が GCP 予算アラート（Pub/Sub `budget-alerts`）を受信し、`costAmount >= budgetAmount` 到達時にプロジェクト課金を無効化します。
  - Firebase Auth のオーナー限定アクセスと [firestore.rules](firestore.rules) でデータを保護しています。

---

## ディレクトリ構成

```text
AGENTS.md / AGENTS.ja.md    # エージェント向け設計ルール・開発手順
app/                        # Kotlin / Jetpack Compose Android アプリ（data / feature / notify）
web/                        # React + TypeScript + Vite Web コンソール（config / hooks / utils / pages）
functions/                  # Firebase Cloud Functions (Node.js: リマインダー通知・予算自動停止)
functions-stock/            # Firebase Cloud Functions (Python 3.13: 日次/週次 Gemini 分析・分割モジュール)
scripts/stock-analysis/     # ローカル CLI・YouTube 要約同期（launchd）・Python 単体テスト
.agents/skills/             # パイプラインが読み込む Agent Skills 定義
.github/workflows/          # GitHub Actions 手動補助ワークフロー (stock-analysis.yml)
docs/                       # フロー・設計ドキュメント
```

---

## 技術スタック

- **Android**: Kotlin 2.0.21, Jetpack Compose, Material 3, DataStore, Firebase Auth / Firestore / Messaging, Health Connect
- **Web**: React 18, TypeScript, Vite, Firebase Web SDK / Hosting
- **自動分析**: Python 3.13, Gemini REST API (Google Search グラウンディング), Firebase Admin SDK
- **ローカル YouTube 同期**: Python, `yt-dlp`, `@google/gemini-cli`, macOS `launchd`
- **バックエンド**: Node.js 24, TypeScript, Firebase Cloud Functions v2

---

## よく使うコマンド

### Android
```zsh
JAVA_HOME="$HOME/.local/share/mise/installs/java/global" ./gradlew testDebugUnitTest
JAVA_HOME="$HOME/.local/share/mise/installs/java/global" ./gradlew assembleDebug
JAVA_HOME="$HOME/.local/share/mise/installs/java/global" ./gradlew installDebug
```

### Web
```zsh
npm --prefix web run build
firebase deploy --only hosting
```

### Python 株分析・YouTube 同期 単体テスト
```zsh
functions-stock/venv/bin/python -m unittest discover -s scripts/stock-analysis -p "test_*.py" -v
```
