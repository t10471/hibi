export interface BudgetAlertPayload {
  budgetDisplayName?: string;
  alertThresholdExceeded?: number;
  forecastThresholdExceeded?: number;
  costAmount?: number;
  budgetAmount?: number;
  currencyCode?: string;
}

/**
 * Decode a base64-encoded Pub/Sub payload emitted by Google Cloud Billing Budgets.
 */
export function parseBudgetAlertData(rawBase64?: string): BudgetAlertPayload | null {
  if (!rawBase64 || typeof rawBase64 !== 'string') {
    return null;
  }
  try {
    const decoded = Buffer.from(rawBase64, 'base64').toString('utf8').trim();
    if (!decoded) return null;
    const parsed = JSON.parse(decoded);
    return parsed && typeof parsed === 'object' ? (parsed as BudgetAlertPayload) : null;
  } catch {
    return null;
  }
}

/**
 * Return true only when actual accrued cost has reached or exceeded the configured budget.
 * Forecast-only alerts (where costAmount < budgetAmount) intentionally do NOT disable billing.
 */
export function shouldDisableBilling(payload: BudgetAlertPayload | null): boolean {
  if (!payload) return false;
  const cost = Number(payload.costAmount);
  const budget = Number(payload.budgetAmount);
  if (!Number.isFinite(cost) || !Number.isFinite(budget) || budget <= 0) {
    return false;
  }
  return cost >= budget;
}
