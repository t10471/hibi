import { ActionVerdict, ScenarioStatus, StockScenario } from '../types';

export function effectiveStatus(sc: Pick<StockScenario, 'status' | 'steps'>): ScenarioStatus {
  if (sc.status === 'HOLDING' || sc.status === 'WATCH' || sc.status === 'ARCHIVED') {
    return sc.status;
  }
  return sc.steps?.some((s) => s.isDone) ? 'HOLDING' : 'WATCH';
}

export function statusMeta(status: ScenarioStatus): { label: string; bg: string; color: string } {
  switch (status) {
    case 'HOLDING':
      return { label: '💼 保有中', bg: '#dcfce7', color: '#166534' };
    case 'ARCHIVED':
      return { label: '📦 見送り・完了', bg: '#f1f5f9', color: '#64748b' };
    default:
      return { label: '👀 注目・様子見', bg: '#dbeafe', color: '#1e40af' };
  }
}

export function verdictBadgeMeta(verdict: ActionVerdict | string): {
  label: string;
  bg: string;
  color: string;
  border: string;
} {
  switch (verdict.toUpperCase()) {
    case 'ADD':
      return { label: '➕ 買い増し好機 (ADD)', bg: '#dcfce7', color: '#166534', border: '#86efac' };
    case 'BUY':
    case 'GO':
      return { label: `🚀 購入好機 (${verdict.toUpperCase()})`, bg: '#dcfce7', color: '#166534', border: '#86efac' };
    case 'HOLD':
      return { label: '💼 保有継続 (HOLD)', bg: '#e0f2fe', color: '#0369a1', border: '#7dd3fc' };
    case 'TRIM':
    case 'ABORT':
      return { label: `⛔ 買いNG・縮小/撤退警戒 (${verdict.toUpperCase()})`, bg: '#fee2e2', color: '#991b1b', border: '#fca5a5' };
    case 'DROP':
      return { label: '🗑️ 候補削除検討 (DROP)', bg: '#fee2e2', color: '#991b1b', border: '#fca5a5' };
    default:
      return { label: `✋ 様子見・まだ買わない (${verdict.toUpperCase()})`, bg: '#fef3c7', color: '#92400e', border: '#fde68a' };
  }
}
