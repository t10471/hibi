import { collection, doc, increment, onSnapshot, setDoc } from 'firebase/firestore';
import { db } from '../firebase';

export const USD_TO_JPY = 159;
export const AI_STUDIO_USAGE_URL =
  'https://aistudio.google.com/rate-limit?timeRange=last-28-days&project=kabu-99c48';
export const CLOUD_BILLING_URL = 'https://console.cloud.google.com/billing';

export interface GeminiMonthlyUsage {
  month: string; // YYYY-MM (JST)
  requestCount: number;
  promptTokens: number;
  outputTokens: number;
  thinkingTokens: number;
  cachedTokens: number;
  estimatedCostUsd: number;
  workoutRequests: number;
  workoutCostUsd: number;
  stockRequests: number;
  stockCostUsd: number;
  appRequests: number;
  webRequests: number;
  batchRequests?: number;
  batchCostUsd?: number;
  usdToJpyRate?: number;
  lastModel: string;
  updatedAt?: string;
}

export function getCurrentMonthKeyJst(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Tokyo',
    year: 'numeric',
    month: '2-digit',
  }).format(new Date());
}

export function emptyMonthlyUsage(month: string = getCurrentMonthKeyJst()): GeminiMonthlyUsage {
  return {
    month,
    requestCount: 0,
    promptTokens: 0,
    outputTokens: 0,
    thinkingTokens: 0,
    cachedTokens: 0,
    estimatedCostUsd: 0,
    workoutRequests: 0,
    workoutCostUsd: 0,
    stockRequests: 0,
    stockCostUsd: 0,
    appRequests: 0,
    webRequests: 0,
    lastModel: '',
  };
}

/**
 * Gemini API モデルの100万トークンあたり料金 (USD) を算出する
 */
function getModelRatesUsdPerMillion(model: string): {
  input: number;
  cachedInput: number;
  output: number;
} {
  const m = (model || '').toLowerCase();
  if (m.includes('neon')) {
    return { input: 3.0, cachedInput: 0.15, output: 15.0 };
  }
  if (m.includes('hydrogen')) {
    return { input: 1.5, cachedInput: 0.075, output: 7.5 };
  }
  if (m.includes('pro')) {
    return { input: 1.25, cachedInput: 0.3125, output: 10.0 };
  }
  if (m.includes('3.8-flash')) {
    return { input: 0.75, cachedInput: 0.075, output: 3.75 };
  }
  if (m.includes('3.5-flash-lite')) {
    return { input: 0.3, cachedInput: 0.03, output: 2.5 };
  }
  if (m.includes('2.0-flash') || m.includes('flash-lite')) {
    return { input: 0.1, cachedInput: 0.025, output: 0.4 };
  }
  // Default: gemini-2.5-flash / gemini-flash-latest
  return { input: 0.3, cachedInput: 0.03, output: 2.5 };
}

export function calculateCostUsd(
  model: string,
  promptTokens: number,
  candidatesTokens: number,
  thinkingTokens: number,
  cachedTokens: number = 0
): number {
  const rates = getModelRatesUsdPerMillion(model);
  const nonCachedInput = Math.max(0, promptTokens - cachedTokens);
  const totalOutput = Math.max(0, candidatesTokens) + Math.max(0, thinkingTokens);
  return (
    (nonCachedInput * rates.input +
      Math.max(0, cachedTokens) * rates.cachedInput +
      totalOutput * rates.output) /
    1_000_000
  );
}

/**
 * Gemini REST API レスポンスから usageMetadata を読み取り、当月 (JST) の Firestore 集計へ加算する
 */
export async function recordGeminiUsage(
  responseJson: any,
  category: 'workout' | 'stock',
  fallbackModel: string
): Promise<void> {
  try {
    const usage = responseJson?.usageMetadata;
    if (!usage) return;

    const modelVersion = String(responseJson?.modelVersion || fallbackModel || 'gemini-2.5-flash');
    const promptTokens = Number(usage.promptTokenCount || 0);
    const candidatesTokens = Number(usage.candidatesTokenCount || 0);
    const thinkingTokens = Number(usage.thoughtsTokenCount || 0);
    const cachedTokens = Number(usage.cachedContentTokenCount || 0);
    const outputTokens = candidatesTokens + thinkingTokens;
    const costUsd = calculateCostUsd(modelVersion, promptTokens, candidatesTokens, thinkingTokens, cachedTokens);

    const month = getCurrentMonthKeyJst();
    const ref = doc(db, 'gemini_usage', month);
    const nowIso = new Date().toISOString();

    await setDoc(
      ref,
      {
        month,
        requestCount: increment(1),
        promptTokens: increment(promptTokens),
        outputTokens: increment(outputTokens),
        thinkingTokens: increment(thinkingTokens),
        cachedTokens: increment(cachedTokens),
        estimatedCostUsd: increment(costUsd),
        workoutRequests: increment(category === 'workout' ? 1 : 0),
        workoutCostUsd: increment(category === 'workout' ? costUsd : 0),
        stockRequests: increment(category === 'stock' ? 1 : 0),
        stockCostUsd: increment(category === 'stock' ? costUsd : 0),
        webRequests: increment(1),
        lastModel: modelVersion,
        updatedAt: nowIso,
      },
      { merge: true }
    );
  } catch (err) {
    console.warn('Failed to record Gemini usage:', err);
  }
}

export function subscribeGeminiUsageHistory(
  onUpdate: (current: GeminiMonthlyUsage, history: GeminiMonthlyUsage[]) => void
): () => void {
  return onSnapshot(
    collection(db, 'gemini_usage'),
    (snapshot) => {
      const currentMonth = getCurrentMonthKeyJst();
      const map = new Map<string, GeminiMonthlyUsage>();

      snapshot.docs.forEach((d) => {
        const data = d.data();
        const month = String(data.month || d.id);
        map.set(month, {
          month,
          requestCount: Number(data.requestCount || 0),
          promptTokens: Number(data.promptTokens || 0),
          outputTokens: Number(data.outputTokens || 0),
          thinkingTokens: Number(data.thinkingTokens || 0),
          cachedTokens: Number(data.cachedTokens || 0),
          estimatedCostUsd: Number(data.estimatedCostUsd || 0),
          workoutRequests: Number(data.workoutRequests || 0),
          workoutCostUsd: Number(data.workoutCostUsd || 0),
          stockRequests: Number(data.stockRequests || 0),
          stockCostUsd: Number(data.stockCostUsd || 0),
          appRequests: Number(data.appRequests || 0),
          webRequests: Number(data.webRequests || 0),
          batchRequests: Number(data.batchRequests || 0),
          batchCostUsd: Number(data.batchCostUsd || 0),
          usdToJpyRate:
            typeof data.usdToJpyRate === 'number' && data.usdToJpyRate >= 80 && data.usdToJpyRate <= 300
              ? data.usdToJpyRate
              : USD_TO_JPY,
          lastModel: String(data.lastModel || ''),
          updatedAt: data.updatedAt ? String(data.updatedAt) : undefined,
        });
      });

      const current = map.get(currentMonth) || emptyMonthlyUsage(currentMonth);
      const history = Array.from(map.values()).sort((a, b) => b.month.localeCompare(a.month));
      onUpdate(current, history);
    },
    (err) => {
      console.warn('Failed to listen to gemini_usage:', err);
    }
  );
}

export function formatCostJpy(costUsd: number, usdToJpy: number = USD_TO_JPY): string {
  const jpy = costUsd * usdToJpy;
  if (jpy === 0) return '¥0';
  if (jpy < 1) return `¥${jpy.toFixed(2)}`;
  if (jpy < 10) return `¥${jpy.toFixed(1)}`;
  return `¥${Math.round(jpy).toLocaleString()}`;
}

export function formatCostUsd(costUsd: number): string {
  if (costUsd === 0) return '$0.00';
  if (costUsd < 0.01) return `$${costUsd.toFixed(4)}`;
  return `$${costUsd.toFixed(3)}`;
}
