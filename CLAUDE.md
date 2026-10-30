# Hibi — 日々のちいさな習慣サポートアプリ

4つの機能（タイマー、くすり、記録、かぶ）をまとめた Android アプリ ＋ Web入稿 ＋ Firebase定期通知バッチ。

## 機能
1. **タイマー**（タバタ式 HIIT）：運動/休憩の秒数とセット数を設定。スタートで「準備(3-2-1)→運動→休憩」を繰り返す。各フェーズ終了の **3秒前からカウントダウン音**。残り時間を **円（リング）でビジュアル表示**＋大きな数字。
2. **くすり**：飲む薬と時刻を複数登録 → 設定時刻に **通知でお知らせ**。飲んだら丸をタップして **チェック**（当日分）。チェック済みは色・取り消し線で区別。日付が変わるとリセット。
3. **記録**（経過日数）：「髪を切った」等を日付付きで登録 → **何日前か**を表示。やり直したら「今日」ボタンで更新。
4. **かぶ**（投資シナリオ＆買い時管理）：Geminiと壁打ちしたシナリオ・分割買いスケジュール・Google Finance/Gemini会話リンクを保持。買い時時期に通知でお知らせ。

## スタック / 構成
- **Android**: Kotlin 2.0.21 / Compose (BOM 2024.10.01) / Material 3 / DataStore
- **Web (入稿)**: React 18 / TypeScript / Vite / Firebase Hosting
- **Backend (通知)**: Node 20 / TypeScript / Firebase Cloud Functions v2 (Cloud Scheduler 毎朝8:30)

```
app/src/main/java/com/example/hibi/
  MainActivity.kt          # 通知権限リクエスト＋setContent
  HibiApp.kt               # Scaffold + 下タブ(タイマー/くすり/記録/かぶ)
  data/                    # Models（@Serializable）, StockModels, HibiRepository
  feature/stock/           # StockViewModel, StockScreen (Finance/Geminiリンク, スケジュールチェック)
  feature/timer/           # TimerViewModel, TimerScreen
  feature/meds/            # MedsViewModel, MedsScreen
  feature/since/           # SinceViewModel, SinceScreen
  notify/                  # Notifications (med_reminders, stock_reminders)
web/                       # Web入稿フロントエンド (Vite + React)
functions/                 # Firebase Cloud Functions (定期通知バッチ)
```

## よく使うコマンド（プロジェクト直下）
```sh
./gradlew assembleDebug             # ビルド
./gradlew installDebug              # エミュレータ/実機へ
adb shell am start -n com.example.hibi/.MainActivity
./gradlew testDebugUnitTest         # 単体テスト（DateUtils）
emulator -avd Medium_Phone_API_36.0 &
```
※ 環境変数（ANDROID_HOME 等）は `~/.zshrc` に設定済み（新規ターミナルで有効）。

## 既知の割り切り（v1）
- 通知は inexact（時刻が数分前後し得る）。厳密化するなら SCHEDULE_EXACT_ALARM ＋ setExactAndAllowWhileIdle へ。
- 薬の「飲んだ」記録は当日分のみ（履歴は持たない）。
- タイマーは画面を離れても ViewModel 側で継続。アプリ終了中の動作はフォアグラウンドサービス化が必要。
