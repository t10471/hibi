# Hibi

[English](README.md) | 日本語

**Hibi** は、日々の習慣管理と中長期の株式投資シナリオ管理をまとめたパーソナルアプリです。**Android アプリ（Kotlin / Jetpack Compose）**、**Web ポータル（React / TypeScript）**、**Firebase（Auth / Firestore / Hosting / Cloud Functions / FCM）**、および **GitHub Actions + Gemini API 自動分析パイプライン** で構成されています。

---

## 主な機能

| 機能 | 対応環境 | 概要 |
| --- | --- | --- |
| **タイマー** | Android | タバタ式 HIIT タイマー（準備 → 運動 → 休憩）。円形プログレス表示と終了 3 秒前のカウントダウン音に対応。 |
| **筋トレ** | Android / Web | メニュー管理、Google スプレッドシート取込、1 タップ達成度チップ（`未達` / `規定どおり` / `+1回超` / `+2回超` / `+3回+`）、前回実績との差分を踏まえた Gemini AI コーチング。 |
| **くすり** | Android / Web | 薬・サプリの服用時刻リマインダーと当日分チェック（日付変更で自動リセット）。 |
| **記録（Since）** | Android / Web | 定期メンテナンスやイベントからの経過日数を記録・管理。 |
| **かぶ（投資シナリオ）** | Android / Web / Actions | 保有中（`HOLDING`）・注目中（`WATCH`）・見送り（`ARCHIVED`）の管理、分割購入ステップ（`notifyDate` による毎朝 08:30 Android 通知）、AI 自動定期分析レポート連携。 |

---

## 株 AI 自動定期分析パイプライン

詳細な処理フローは [docs/stock-analysis-flow.md](docs/stock-analysis-flow.md) を参照してください。

- **平日毎朝チェック（`08:17 JST` / `cron: '17 23 * * 0-4'`）**
  - `gemini-4-neon` + Google Search グラウンディングの **1 コール省エネ実行**。
  - 市場地合いの要点と、登録済みの保有銘柄（`ADD` / `HOLD` / `TRIM`）・Watch 銘柄（`BUY` / `WATCH` / `DROP`）の現在値比診断・未購入ステップを更新します。
  - `BUY` / `ADD` / `GO` シグナルが出た場合は FCM プッシュ通知を送信します。
- **毎週月曜の週次発掘（`07:43 JST` / `cron: '43 22 * * 0'`）**
  - `gemini-4-hydrogen` + Google Search グラウンディングの 1 コール実行。
  - 中長期（6 ヶ月〜2〜3 年）のセクター動向を整理し、既存銘柄と重複しない新規 Watch 候補（2〜3 銘柄・分割ステップと `notifyDate` 付き）を提案します。
  - 候補は Web / Android 上のチェックボックスでそのまま `WATCH` 登録／解除でき、未保有の `WATCH` 銘柄は一覧や判定チップから削除できます（保有中 `HOLDING` 銘柄は誤削除防止のため保護）。
- **コスト・セキュリティ制御**
  - `gemini_usage/{YYYY-MM}` で月間推計コストを集計し、`stock_settings/automation` の手動停止スイッチおよび月額上限（JPY）で自動停止できます。
  - Firebase Auth のオーナー限定アクセスと [firestore.rules](firestore.rules) でデータを保護しています。

---

## ディレクトリ構成

```text
app/                       # Kotlin / Jetpack Compose Android アプリ
web/                       # React + TypeScript + Vite Web ポータル (Firebase Hosting)
functions/                 # Firebase Cloud Functions
scripts/stock-analysis/    # Python 製 Gemini 株分析パイプライン＆単体テスト
.agents/skills/            # パイプラインが読み込む Agent Skills 定義
.github/workflows/         # GitHub Actions 定期実行設定 (stock-analysis.yml)
docs/                      # フロー・設計ドキュメント
```

---

## 技術スタック

- **Android**: Kotlin 2.0.21, Jetpack Compose, Material 3, DataStore, Firebase Auth / Firestore / Messaging
- **Web**: React 18, TypeScript, Vite, Firebase Web SDK / Hosting
- **自動分析**: Python 3.12, Gemini REST API (Google Search グラウンディング), Firebase Admin SDK, GitHub Actions
- **バックエンド**: Node.js 20, TypeScript, Firebase Cloud Functions v2

---

## よく使うコマンド

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

### 株分析パイプライン 単体テスト

```sh
python -m unittest discover -s scripts/stock-analysis -p "test_*.py" -v
```
