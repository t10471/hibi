# Hibi TypeScript Cloud Functions (`functions/`)

English | [日本語](AGENTS.ja.md)

Node.js 24 + TypeScript Cloud Functions v2 (`asia-northeast1`) handling daily stock reminder FCM pushes and GCP billing auto-stop protection.

---

## 1. Directory Structure

- `src/`
  - `index.ts` — Exported Cloud Functions:
    - `checkStockReminders` (`30 8 * * *` `Asia/Tokyo`, 08:30 JST daily)
    - `stopBillingOnBudget` (Pub/Sub `budget-alerts` trigger)
  - `budgetGuard.ts` — Pure helper functions:
    - `parseBudgetAlertData()`
    - `shouldDisableBilling()`
- `test/`
  - `budgetGuard.test.js` — Node built-in test runner unit tests for `budgetGuard.ts`

---

## 2. TypeScript Functions Rules

- **Keep pure guard logic testable**:
  - Keep payload parsing and threshold checks inside `budgetGuard.ts` decoupled from Firebase/GCP SDK calls so unit tests run offline via `node --test`.
- **Fail safely on budget alerts**:
  - Only unlink billing when `costAmount >= budgetAmount` (with valid positive `budgetAmount`) and log only non-sensitive billing status metadata.

---

## 3. Commands

```zsh
# Compile TypeScript & run unit tests
npm --prefix functions test

# Deploy default TypeScript codebase
firebase deploy --only functions:default
```
