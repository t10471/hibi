import { useState, useEffect, useMemo } from 'react';
import {
  collection,
  query,
  orderBy,
  onSnapshot,
  deleteDoc,
  doc,
  updateDoc,
} from 'firebase/firestore';
import { db } from './firebase';
import { ActionVerdict, ScenarioStatus, StockScenario } from './types';

function effectiveStatus(sc: StockScenario): ScenarioStatus {
  if (sc.status === 'HOLDING' || sc.status === 'WATCH' || sc.status === 'ARCHIVED') {
    return sc.status;
  }
  return sc.steps?.some((s) => s.isDone) ? 'HOLDING' : 'WATCH';
}

function statusMeta(status: ScenarioStatus): { label: string; bg: string; color: string } {
  switch (status) {
    case 'HOLDING':
      return { label: '💼 保有中', bg: '#dcfce7', color: '#166534' };
    case 'ARCHIVED':
      return { label: '📦 見送り・完了', bg: '#f1f5f9', color: '#64748b' };
    default:
      return { label: '👀 注目中', bg: '#dbeafe', color: '#1e40af' };
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
      return { label: `⚠️ 縮小/撤退警戒 (${verdict.toUpperCase()})`, bg: '#fee2e2', color: '#991b1b', border: '#fca5a5' };
    case 'DROP':
      return { label: '🗑️ 候補削除検討 (DROP)', bg: '#fee2e2', color: '#991b1b', border: '#fca5a5' };
    default:
      return { label: `👀 監視/様子見 (${verdict.toUpperCase()})`, bg: '#fef3c7', color: '#92400e', border: '#fde68a' };
  }
}

export function ListPage() {
  const [scenarios, setScenarios] = useState<StockScenario[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<'ALL' | ScenarioStatus>('ALL');
  const [editingNoteId, setEditingNoteId] = useState<string | null>(null);
  const [qtyDraft, setQtyDraft] = useState('');
  const [avgCostDraft, setAvgCostDraft] = useState('');
  const [noteDraft, setNoteDraft] = useState('');

  useEffect(() => {
    const q = query(collection(db, 'stock_scenarios'), orderBy('createdAt', 'desc'));
    const unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        const items: StockScenario[] = [];
        snapshot.forEach((docSnap) => {
          items.push({
            ...(docSnap.data() as StockScenario),
            id: docSnap.id,
          });
        });
        setScenarios(items);
        setLoading(false);
      },
      (error) => {
        console.error('Error fetching scenarios:', error);
        setLoading(false);
      }
    );

    return () => unsubscribe();
  }, []);

  const counts = useMemo(() => {
    const c = { ALL: scenarios.length, HOLDING: 0, WATCH: 0, ARCHIVED: 0 };
    scenarios.forEach((sc) => {
      c[effectiveStatus(sc)]++;
    });
    return c;
  }, [scenarios]);

  const filtered = useMemo(() => {
    if (statusFilter === 'ALL') {
      // Show HOLDING first, then WATCH, then ARCHIVED
      const order: Record<ScenarioStatus, number> = { HOLDING: 0, WATCH: 1, ARCHIVED: 2 };
      return [...scenarios].sort((a, b) => order[effectiveStatus(a)] - order[effectiveStatus(b)]);
    }
    return scenarios.filter((sc) => effectiveStatus(sc) === statusFilter);
  }, [scenarios, statusFilter]);

  const handleStatusChange = async (sc: StockScenario, nextStatus: ScenarioStatus) => {
    if (!sc.id) return;
    try {
      await updateDoc(doc(db, 'stock_scenarios', sc.id), { status: nextStatus });
    } catch (err: any) {
      alert(`状況切替エラー: ${err?.message || err}`);
    }
  };

  const handleSaveHoldingDetails = async (sc: StockScenario) => {
    if (!sc.id) return;
    const trimmedQty = qtyDraft.trim();
    const trimmedCost = avgCostDraft.trim();
    const trimmedNote = noteDraft.trim();
    const updates: Record<string, any> = {
      holdingQuantity: trimmedQty,
      averageCost: trimmedCost,
      holdingNote: trimmedNote,
    };
    if ((trimmedQty || trimmedCost) && effectiveStatus(sc) === 'WATCH') {
      updates.status = 'HOLDING';
    }
    await updateDoc(doc(db, 'stock_scenarios', sc.id), updates);
    setEditingNoteId(null);
  };

  const handleToggleStepDone = async (sc: StockScenario, stepNumber: number) => {
    if (!sc.id) return;
    const updatedSteps = (sc.steps || []).map((s) =>
      s.step === stepNumber ? { ...s, isDone: !s.isDone } : s
    );
    const updates: Record<string, any> = { steps: updatedSteps };
    const anyDone = updatedSteps.some((s) => s.isDone);
    const curStatus = effectiveStatus(sc);
    if (anyDone && curStatus === 'WATCH') {
      updates.status = 'HOLDING';
    } else if (
      !anyDone &&
      curStatus === 'HOLDING' &&
      !sc.holdingQuantity?.trim() &&
      !sc.averageCost?.trim()
    ) {
      updates.status = 'WATCH';
    }
    await updateDoc(doc(db, 'stock_scenarios', sc.id), updates);
  };

  const handleDelete = async (id?: string, name?: string, isHolding?: boolean) => {
    if (!id) return;
    const msg = isHolding
      ? `💼 保有中の銘柄「${name || 'この銘柄'}」のシナリオを削除してもよろしいですか？`
      : `「${name || 'この銘柄'}」のWatchシナリオを削除してもよろしいですか？`;
    if (!window.confirm(msg)) return;
    try {
      await deleteDoc(doc(db, 'stock_scenarios', id));
    } catch (err: any) {
      alert(`削除エラー: ${err.message}`);
    }
  };

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 40, color: '#6b7280' }}>読み込み中...</div>;
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <h2 style={{ fontSize: 18, margin: 0 }}>📊 監視・保有シナリオ ({scenarios.length}件)</h2>
          <span style={{ fontSize: 11, color: '#64748b' }}>※ 状況・購入済はAI分析へ即時反映</span>
        </div>

        {/* ステータスフィルタ（ヘッダー同段にコンパクト配置） */}
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {(
            [
              { id: 'ALL', label: `すべて (${counts.ALL})` },
              { id: 'HOLDING', label: `💼 保有中 (${counts.HOLDING})` },
              { id: 'WATCH', label: `👀 注目中 (${counts.WATCH})` },
              { id: 'ARCHIVED', label: `📦 見送り (${counts.ARCHIVED})` },
            ] as const
          ).map((tab) => {
            const active = statusFilter === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setStatusFilter(tab.id)}
                style={{
                  background: active ? '#1e293b' : '#e2e8f0',
                  color: active ? 'white' : '#334155',
                  border: 'none',
                  borderRadius: 999,
                  padding: '5px 12px',
                  fontSize: 12,
                  fontWeight: active ? 'bold' : 500,
                  cursor: 'pointer',
                }}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
      </div>

      {filtered.length === 0 ? (
        <div style={{ padding: 48, textAlign: 'center', background: '#f9fafb', borderRadius: 8, color: '#6b7280', border: '1px dashed #d1d5db' }}>
          <p style={{ margin: '0 0 8px 0', fontSize: 15, fontWeight: 'bold' }}>該当するシナリオはありません</p>
          <p style={{ margin: 0, fontSize: 13 }}>「新規登録」または「AI分析レポート」の候補から追加してください。</p>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {filtered.map((sc) => {
            const st = effectiveStatus(sc);
            const stMeta = statusMeta(st);
            const badge = sc.latestVerdict ? verdictBadgeMeta(sc.latestVerdict) : null;
            const isEditingNote = editingNoteId === sc.id;

            return (
              <div
                key={sc.id}
                style={{
                  background: 'white',
                  border: `1px solid ${st === 'HOLDING' ? '#bbf7d0' : '#e5e7eb'}`,
                  borderRadius: 10,
                  padding: 20,
                  boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
                  opacity: st === 'ARCHIVED' ? 0.72 : 1,
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10, gap: 12, flexWrap: 'wrap' }}>
                  <div>
                    <h3 style={{ margin: '0 0 6px 0', fontSize: 19, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span>{sc.name}</span>
                      <span style={{ fontSize: 14, color: '#6b7280', fontWeight: 'normal' }}>({sc.symbol}:{sc.market})</span>
                      <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 999, fontWeight: 'bold', background: stMeta.bg, color: stMeta.color }}>
                        {stMeta.label}
                      </span>
                      {badge && (
                        <span
                          title={sc.latestVerdictReason || ''}
                          style={{
                            fontSize: 11,
                            padding: '2px 8px',
                            borderRadius: 6,
                            fontWeight: 'bold',
                            background: badge.bg,
                            color: badge.color,
                            border: `1px solid ${badge.border}`,
                          }}
                        >
                          {badge.label} {sc.latestCheckedAt ? `(${sc.latestCheckedAt})` : ''}
                        </span>
                      )}
                    </h3>
                    <div style={{ fontSize: 13, color: '#1d4ed8', fontWeight: 'bold' }}>{sc.targetHorizon}</div>
                    {sc.latestVerdictReason && (
                      <div style={{ fontSize: 12, color: '#475569', marginTop: 4 }}>{sc.latestVerdictReason}</div>
                    )}
                  </div>

                  <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                    <a
                      href={`https://www.google.com/finance/quote/${encodeURIComponent(sc.symbol)}:${encodeURIComponent(sc.market)}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{
                        background: '#eff6ff',
                        color: '#1d4ed8',
                        border: '1px solid #bfdbfe',
                        borderRadius: 4,
                        padding: '5px 12px',
                        fontSize: 12,
                        textDecoration: 'none',
                        fontWeight: 'bold',
                      }}
                    >
                      📈 Google Finance ↗
                    </a>
                    {sc.geminiShareUrl && sc.geminiShareUrl.startsWith('https://') && (
                      <a
                        href={sc.geminiShareUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        style={{
                          background: '#f5f3ff',
                          color: '#6d28d9',
                          border: '1px solid #ddd6fe',
                          borderRadius: 4,
                          padding: '5px 12px',
                          fontSize: 12,
                          textDecoration: 'none',
                          fontWeight: 'bold',
                        }}
                      >
                        💬 Gemini会話 ↗
                      </a>
                    )}
                    <button
                      onClick={() => handleDelete(sc.id, sc.name, st === 'HOLDING')}
                      style={{
                        background: st === 'WATCH' ? '#fef2f2' : 'none',
                        border: '1px solid #fca5a5',
                        color: '#dc2626',
                        borderRadius: 4,
                        padding: '5px 12px',
                        fontSize: 12,
                        fontWeight: st === 'WATCH' ? 'bold' : 'normal',
                        cursor: 'pointer',
                      }}
                    >
                      {st === 'WATCH' ? '🗑️ Watch削除' : '削除'}
                    </button>
                  </div>
                </div>

                {/* ステータス切替 & 保有メモ */}
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12, padding: '8px 10px', background: '#f8fafc', borderRadius: 8, border: '1px solid #e2e8f0' }}>
                  <span style={{ fontSize: 11, color: '#64748b', fontWeight: 600 }}>状況切替:</span>
                  {(
                    [
                      { id: 'WATCH', label: '👀 注目中' },
                      { id: 'HOLDING', label: '💼 保有中' },
                      { id: 'ARCHIVED', label: '📦 見送り' },
                    ] as const
                  ).map((opt) => (
                    <button
                      key={opt.id}
                      type="button"
                      onClick={() => void handleStatusChange(sc, opt.id)}
                      style={{
                        background: st === opt.id ? '#1e293b' : 'white',
                        color: st === opt.id ? 'white' : '#475569',
                        border: '1px solid #cbd5e1',
                        borderRadius: 999,
                        padding: '3px 10px',
                        fontSize: 11,
                        fontWeight: st === opt.id ? 'bold' : 'normal',
                        cursor: 'pointer',
                      }}
                    >
                      {opt.label}
                    </button>
                  ))}

                  <span style={{ color: '#cbd5e1' }}>|</span>

                  {isEditingNote ? (
                    <div style={{ display: 'flex', gap: 6, flex: 1, minWidth: 260, flexWrap: 'wrap', alignItems: 'center' }}>
                      <input
                        type="text"
                        value={qtyDraft}
                        onChange={(e) => setQtyDraft(e.target.value)}
                        placeholder="保有数 (例: 10株)"
                        style={{ width: 110, fontSize: 12, padding: '4px 8px', borderRadius: 4, border: '1px solid #94a3b8' }}
                      />
                      <input
                        type="text"
                        value={avgCostDraft}
                        onChange={(e) => setAvgCostDraft(e.target.value)}
                        placeholder="平均取得 (例: $280)"
                        style={{ width: 125, fontSize: 12, padding: '4px 8px', borderRadius: 4, border: '1px solid #94a3b8' }}
                      />
                      <input
                        type="text"
                        value={noteDraft}
                        onChange={(e) => setNoteDraft(e.target.value)}
                        placeholder="買い増し方針・メモ"
                        style={{ flex: '1 1 160px', fontSize: 12, padding: '4px 8px', borderRadius: 4, border: '1px solid #94a3b8' }}
                      />
                      <button
                        onClick={() => handleSaveHoldingDetails(sc)}
                        style={{ background: '#2563eb', color: 'white', border: 'none', borderRadius: 4, padding: '4px 10px', fontSize: 11, fontWeight: 'bold', cursor: 'pointer' }}
                      >
                        保存
                      </button>
                      <button
                        onClick={() => setEditingNoteId(null)}
                        style={{ background: 'white', color: '#475569', border: '1px solid #cbd5e1', borderRadius: 4, padding: '4px 8px', fontSize: 11, cursor: 'pointer' }}
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => {
                        setEditingNoteId(sc.id || null);
                        setQtyDraft(sc.holdingQuantity || '');
                        setAvgCostDraft(sc.averageCost || '');
                        setNoteDraft(sc.holdingNote || '');
                      }}
                      style={{
                        background: 'none',
                        border: 'none',
                        padding: 0,
                        fontSize: 12,
                        color: sc.holdingQuantity || sc.averageCost || sc.holdingNote ? '#0f172a' : '#64748b',
                        cursor: 'pointer',
                        textAlign: 'left',
                      }}
                    >
                      💼{' '}
                      {[
                        sc.holdingQuantity ? `保有: ${sc.holdingQuantity}` : '',
                        sc.averageCost ? `取得: ${sc.averageCost}` : '',
                        sc.holdingNote ? sc.holdingNote : '',
                      ]
                        .filter(Boolean)
                        .join(' ／ ') || '保有数・取得単価・メモを設定...'}
                    </button>
                  )}
                </div>

                <p style={{ fontSize: 14, color: '#4b5563', margin: '8px 0 14px 0', lineHeight: 1.5 }}>{sc.summary}</p>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 10 }}>
                  {sc.steps?.map((step) => (
                    <div
                      key={step.step}
                      onClick={() => handleToggleStepDone(sc, step.step)}
                      title="クリックで購入済み / 未購入を切り替え"
                      style={{
                        background: step.isDone ? '#ecfdf5' : '#f8fafc',
                        padding: 12,
                        borderRadius: 6,
                        border: `1px solid ${step.isDone ? '#86efac' : '#e2e8f0'}`,
                        fontSize: 13,
                        cursor: 'pointer',
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4, alignItems: 'center' }}>
                        <strong style={{ color: step.isDone ? '#166534' : '#2563eb' }}>
                          {step.isDone ? '✅' : '⬜'} 第{step.step}回 ({step.ratio}) {step.isDone ? '購入済' : ''}
                        </strong>
                        <span style={{ color: '#64748b', fontSize: 12 }}>通知: <b>{step.notifyDate || '-'}</b></span>
                      </div>
                      <div style={{ color: '#475569', fontSize: 12, marginBottom: 4 }}>時期: {step.period}</div>
                      <div style={{ color: '#1e293b' }}><b>狙い:</b> {step.aim}</div>
                      <div style={{ color: '#475569', marginTop: 2 }}><b>条件:</b> {step.condition}</div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
