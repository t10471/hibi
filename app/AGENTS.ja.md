# Hibi Android アプリ（`app/`）

[English](AGENTS.md) | 日本語

Kotlin 2.0.21 と Jetpack Compose（Material 3）で構成された Android アプリです。DataStore・Firebase（Auth / Firestore / FCM）・Health Connect を組み合わせて動作します。

---

## 1. 主要エントリーポイントと層構造

- `MainActivity.kt` / `HibiApp.kt` — 通知権限リクエスト、下タブナビゲーション、オーナー認証ガード（`AuthManager.isOwnerSignedIn()`）。
- `data/` — ローカル永続化と外部通信の境界層
  - `HibiRepository.kt` — DataStore による端末内キャッシュ（オフライン状態や `geminiApiKey` を保持）。
  - `StockFirestoreDataSource.kt` — 株関連コレクションの Firestore 購読・更新処理。
  - `StockScenarioMapper.kt` — Firestore 側のシナリオと端末内のローカル状態（`isDone` / `isNotified`）をマージ。
  - `GeminiApiClient.kt` — 共通 Gemini REST クライアント（`gemini-4-hydrogen`＋`gemini-flash-latest` フォールバック）および `GeminiUsageTracker.kt` による利用額集計。
- `feature/` — `timer`・`workout`・`meds`・`since`・`stock` の各機能モジュール（`Screen` + `ViewModel`）
  - 肥大化しやすい `stock/` は次の責務単位へ分割しています：
    - `StockScreen.kt`（タブ枠と一覧）
    - `StockScenarioCard.kt`（銘柄カード・ステップ・各種ダイアログ）
    - `StockConsultationView.kt`（AI 個別相談チャット）
    - `StockReportsView.kt` / `StockKnowledgeView.kt`（レポート・ナレッジ画面）
    - `StockVerdictMeta.kt`（判定バッジの文言・配色共通定義）
- `notify/` — 毎朝 08:30 等のローカル通知スケジューラ（`MedScheduler` / `StockScheduler`）と FCM 受信処理（`HibiFirebaseMessagingService`）。

---

## 2. Android 固有の設計ルール・注意点

- **Firestore アクセスを ViewModel から分離する**
  - ViewModel に `Firebase.firestore` の生パースを書かず、`data/*DataSource` クラスへ閉じ込めます。
  - Firestore 受信時は通知スケジューラ任せにせず、直ちに `HibiRepository` へマージ保存して UI 遅延を防ぎます。
- **「買わない（様子見）」判断を一目で伝える**
  - シナリオカードとレポート画面の両方で `StockVerdictMeta.kt` の `verdictUiMeta()` を共用し、`WATCH` / `WAIT` が `✋ 様子見 (WATCH)` として明確に表示される状態を保ちます。
- **Screen ファイルを肥大化させない**
  - カードやダイアログ、全画面サブビューを追加するときは `feature/<name>/` 配下の別ファイルへ切り出します。

---

## 3. コマンド

```zsh
# 単体テストとデバッグ APK ビルド
JAVA_HOME="$HOME/.local/share/mise/installs/java/global" ./gradlew testDebugUnitTest assembleDebug

# 接続中の Pixel 実機／エミュレータへインストール
JAVA_HOME="$HOME/.local/share/mise/installs/java/global" ./gradlew installDebug
```
