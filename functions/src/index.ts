import { onSchedule } from 'firebase-functions/v2/scheduler';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';

initializeApp();
const db = getFirestore();

interface BuyStep {
  step: number;
  period: string;
  notifyDate: string; // YYYY-MM-DD
  ratio: string;
  aim: string;
  condition: string;
  isNotified?: boolean;
  isDone?: boolean;
}

interface StockScenario {
  symbol: string;
  market: string;
  name: string;
  targetHorizon: string;
  summary: string;
  geminiShareUrl?: string;
  steps: BuyStep[];
  fullText: string;
}

/**
 * 毎朝 8:30 (JST) に実行される定期バッチ
 * 当日以降が notifyDate で、まだ通知していないステップを検知して FCM 通知を送信する
 */
export const checkStockReminders = onSchedule({
  schedule: '30 8 * * *',
  timeZone: 'Asia/Tokyo',
}, async () => {
  const now = new Date();
  // JST の YYYY-MM-DD を取得
  const jstFormatter = new Intl.DateTimeFormat('ja-JP', {
    timeZone: 'Asia/Tokyo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  const todayStr = jstFormatter.format(now).replace(/\//g, '-');

  console.log(`[checkStockReminders] Running for date: ${todayStr}`);

  // 登録されているデバイストークンを取得
  const tokensSnapshot = await db.collection('fcm_tokens').get();
  const tokens = tokensSnapshot.docs.map(doc => doc.id);

  if (tokens.length === 0) {
    console.log('[checkStockReminders] No FCM tokens registered. Skipping push.');
    return;
  }

  // シナリオ一覧を走査
  const scenariosSnapshot = await db.collection('stock_scenarios').get();

  for (const doc of scenariosSnapshot.docs) {
    const data = doc.data() as StockScenario;
    let hasUpdated = false;

    for (const step of data.steps) {
      if (!step.isNotified && step.notifyDate <= todayStr) {
        // 通知対象！
        const title = `【${data.name}】第${step.step}回 買い場のお知らせ (${step.ratio})`;
        const body = `${step.period} / 狙い: ${step.aim}`;
        const googleFinanceUrl = `https://www.google.com/finance/quote/${data.symbol}:${data.market}`;

        console.log(`Sending notification for ${data.name} step ${step.step}`);

        try {
          await getMessaging().sendEachForMulticast({
            tokens,
            notification: {
              title,
              body,
            },
            data: {
              scenarioId: doc.id,
              symbol: data.symbol,
              market: data.market,
              name: data.name,
              step: step.step.toString(),
              period: step.period,
              aim: step.aim,
              condition: step.condition,
              googleFinanceUrl,
              geminiShareUrl: data.geminiShareUrl || '',
            },
          });

          step.isNotified = true;
          hasUpdated = true;
        } catch (err) {
          console.error(`Failed to send notification for ${doc.id}:`, err);
        }
      }
    }

    if (hasUpdated) {
      await doc.ref.update({ steps: data.steps });
    }
  }

  console.log('[checkStockReminders] Completed successfully.');
});
