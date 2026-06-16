# Hibi — 日々のちいさな習慣サポートアプリ

3つの機能を1つにまとめた Android アプリ（Jetpack Compose）。

## 機能
1. **タイマー**（タバタ式 HIIT）：運動/休憩の秒数とセット数を設定。スタートで「準備(3-2-1)→運動→休憩」を繰り返す。各フェーズ終了の **3秒前からカウントダウン音**。残り時間を **円（リング）でビジュアル表示**＋大きな数字。
2. **くすり**：飲む薬と時刻を複数登録 → 設定時刻に **通知でお知らせ**。飲んだら丸をタップして **チェック**（当日分）。チェック済みは色・取り消し線で区別。日付が変わるとリセット。
3. **記録**（経過日数）：「髪を切った」等を日付付きで登録 → **何日前か**を表示。やり直したら「今日」ボタンで更新。

## スタック / 構成
- Kotlin 2.0.21 / Compose (BOM 2024.10.01) / Material 3
- AGP 8.7.3 / Gradle 8.11.1、compileSdk/targetSdk 35、minSdk 24（java.time は core library desugaring で 24 でも動作）
- 永続化：Preferences DataStore に JSON（kotlinx.serialization）で保存。Roomなし。
- 通知：AlarmManager（日次 inexact repeating）＋ BroadcastReceiver ＋ NotificationCompat。再起動時は BootReceiver が再登録。
- package: `com.example.hibi`

```
app/src/main/java/com/example/hibi/
  MainActivity.kt          # 通知権限リクエスト＋setContent
  HibiApp.kt               # Scaffold + 下タブ(タイマー/くすり/記録)
  data/                    # Models（@Serializable）, HibiRepository（DataStore）
  feature/timer/           # TimerViewModel（コルーチンタイマー＋Tone）, TimerScreen（リング描画）
  feature/meds/            # MedsViewModel, MedsScreen（TimePicker, チェック）
  feature/since/           # SinceViewModel, SinceScreen（DatePicker, 経過日数）
  notify/                  # 通知チャンネル/投稿, MedScheduler(AlarmManager), Receiver 2種
  util/                    # DateUtils（java.time）, Tone（ToneGenerator）
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
