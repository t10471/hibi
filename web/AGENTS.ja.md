# Hibi Web コンソール（`web/`）

[English](AGENTS.md) | 日本語

React 18 + TypeScript + Vite で構築され、Firebase Hosting（`https://kabu-99c48.web.app`）へデプロイされる Web コンソールです。

---

## 1. 主要モジュールと責務境界

- `src/App.tsx` — オーナー認証シェル（`src/config/auth.ts` の `ALLOWED_EMAIL` を参照）とナビゲーション制御。
- `src/hooks/` — 複数画面で共有する Firestore 購読フック
  - `useStockScenarios.ts` — `stock_scenarios` のリアルタイム購読と保有／Watch 銘柄セット算出を一元化。
- `src/utils/` — ページをまたいで使う純粋ヘルパー
  - `verdictMeta.ts` — `effectiveStatus()`・`statusMeta()`・`verdictBadgeMeta()` の共通定義。
  - `dateUtils.ts` — 日付・曜日ラベル整形（服薬管理などで利用）。
  - `geminiUsage.ts` / `geminiWorkout.ts` — 月間利用コスト購読と筋トレ AI 相談ロジック。
  - `googleSheetsApi.ts` / `sheetParser.ts` — Google スプレッドシートからのメニュー取込処理。
- `src/pages/`・`src/ListPage.tsx`・`src/RegisterPage.tsx` — 各機能の画面コンポーネント（`ListPage.tsx` と `RegisterPage.tsx` は既存構成互換のため `src/` 直下に配置）。

---

## 2. Web 固有の設計ルール・注意点

- **ページ間の相互インポートを禁止する**
  - `ListPage.tsx` や `pages/*` から別ページへ関数や定数をインポートしません。
  - 共通ロジックは `src/utils/`、共通購読は `src/hooks/`、共通定数は `src/config/` へ切り出します。
- **判定バッジ表現を Android と揃える**
  - 売買判断の表示には必ず `src/utils/verdictMeta.ts` の `verdictBadgeMeta()` を使い、`WATCH` / `WAIT` が「✋ 様子見・まだ買わない」として明確に伝わる状態を保ちます。

---

## 3. コマンド

```zsh
# 型チェックと本番ビルド
npm --prefix web run build

# Firebase Hosting へデプロイ
firebase deploy --only hosting
```
