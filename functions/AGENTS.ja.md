# Hibi TypeScript Cloud Functions（`functions/`）

[English](AGENTS.md) | 日本語

Node.js 24 + TypeScript で動作する Cloud Functions v2（`asia-northeast1`）です。購入予定日のリマインド通知と GCP 課金自動停止を担当します。

---

## 1. ディレクトリ構成

- `src/`
  - `index.ts` — Cloud Functions エントリーポイント
    - `checkStockReminders`（`30 8 * * *` `Asia/Tokyo` / 毎朝 08:30 JST）
    - `stopBillingOnBudget`（Pub/Sub `budget-alerts` トリガー）
  - `budgetGuard.ts` — 純粋関数ヘルパー
    - `parseBudgetAlertData()`
    - `shouldDisableBilling()`
- `test/`
  - `budgetGuard.test.js` — Node 標準テストランナーによる単体テスト

---

## 2. TypeScript Functions 固有のルール

- **判定ロジックを純粋関数として分離する**
  - Pub/Sub ペイロード解析としきい値判定は `budgetGuard.ts` に閉じ込め、Firebase / GCP SDK 呼び出しなしに `node --test` で検証できる状態を保ちます。
- **課金無効化の安全条件を守る**
  - 有効な `budgetAmount > 0` に対して `costAmount >= budgetAmount` が成立したときのみ請求リンクを解除し、ログに機密情報を含めないようにします。

---

## 3. コマンド

```zsh
# TypeScript ビルドと単体テスト実行
npm --prefix functions test

# TypeScript Functions のデプロイ
firebase deploy --only functions:default
```
