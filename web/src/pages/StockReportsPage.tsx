import { useEffect, useMemo, useState } from 'react';
import { addDoc, collection, deleteDoc, doc, getDocs, onSnapshot, query, setDoc, updateDoc, where } from 'firebase/firestore';
import { db } from '../firebase';
import { NewWatchCandidate, StockReport } from '../types';
import { MarkdownView } from '../components/MarkdownView';
import { verdictBadgeMeta } from '../ListPage';
import {
  emptyMonthlyUsage,
  formatCostJpy,
  formatCostUsd,
  GeminiMonthlyUsage,
  subscribeGeminiUsageHistory,
  USD_TO_JPY,
} from '../utils/geminiUsage';

type ReportFilter = 'all' | 'daily_action' | 'weekly_screener' | 'daily_market' | 'daily_gatekeeper';

function verdictStyle(verdict: string): { bg: string; color: string; border: string } {
  const m = verdictBadgeMeta(verdict);
  return { bg: m.bg, color: m.color, border: m.border };
}

export function StockReportsPage() {
  const [reports, setReports] = useState<StockReport[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<ReportFilter>('all');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [automationEnabled, setAutomationEnabled] = useState(true);
  const [monthlyBudgetJpy, setMonthlyBudgetJpy] = useState(1000);
  const [currentUsage, setCurrentUsage] = useState<GeminiMonthlyUsage>(() => emptyMonthlyUsage());
  const [activeSymbols, setActiveSymbols] = useState<Set<string>>(() => new Set());
  const [holdingSymbols, setHoldingSymbols] = useState<Set<string>>(() => new Set());
  const [watchSymbols, setWatchSymbols] = useState<Set<string>>(() => new Set());

  useEffect(() => {
    const unsubUsage = subscribeGeminiUsageHistory((current) => {
      setCurrentUsage(current);
    });
    const unsubScenarios = onSnapshot(
      collection(db, 'stock_scenarios'),
      (snap) => {
        const syms = new Set<string>();
        const holdings = new Set<string>();
        const watches = new Set<string>();
        snap.docs.forEach((d) => {
          const data = d.data();
          if (!data.symbol) return;
          const sym = String(data.symbol).toUpperCase();
          const st =
            data.status === 'HOLDING' || data.status === 'WATCH' || data.status === 'ARCHIVED'
              ? data.status
              : Array.isArray(data.steps) && data.steps.some((s: any) => s?.isDone)
              ? 'HOLDING'
              : 'WATCH';
          if (st !== 'ARCHIVED') {
            syms.add(sym);
          }
          if (st === 'HOLDING') {
            holdings.add(sym);
          } else if (st === 'WATCH') {
            watches.add(sym);
          }
        });
        setActiveSymbols(syms);
        setHoldingSymbols(holdings);
        setWatchSymbols(watches);
      },
      (err) => console.warn('Failed to listen active scenarios:', err)
    );
    const unsubSettings = onSnapshot(
      doc(db, 'stock_settings', 'automation'),
      (snap) => {
        if (snap.exists()) {
          const data = snap.data();
          setAutomationEnabled(data.enabled !== false);
          setMonthlyBudgetJpy(
            typeof data.monthlyBudgetJpy === 'number' ? data.monthlyBudgetJpy : 1000
          );
        } else {
          setAutomationEnabled(true);
          setMonthlyBudgetJpy(1000);
        }
      },
      (err) => console.warn('Failed to listen stock_settings/automation:', err)
    );
    return () => {
      unsubUsage();
      unsubScenarios();
      unsubSettings();
    };
  }, []);

  const defaultNotifyDate = (offsetDays: number): string => {
    const d = new Date(Date.now() + offsetDays * 86400000);
    return d.toISOString().slice(0, 10);
  };

  const handleAddCandidateToWatch = async (cand: NewWatchCandidate) => {
    const sym = cand.symbol.trim().toUpperCase();
    if (!sym) return;
    const steps =
      Array.isArray(cand.steps) && cand.steps.length > 0
        ? cand.steps.map((s, idx) => ({
            step: idx + 1,
            period: s.period || '直近チェック時期未定',
            notifyDate: s.notifyDate && /^\d{4}-\d{2}-\d{2}$/.test(s.notifyDate) ? s.notifyDate : defaultNotifyDate((idx + 1) * 14 - 7),
            ratio: s.ratio || '33%',
            aim: s.aim || cand.reason,
            condition: s.condition || cand.targetHorizon,
            isNotified: false,
            isDone: false,
          }))
        : [
            {
              step: 1,
              period: '1週間後チェック（初回打診）',
              notifyDate: defaultNotifyDate(7),
              ratio: '40%',
              aim: cand.reason,
              condition: cand.targetHorizon || '支持帯での下げ止まり確認',
              isNotified: false,
              isDone: false,
            },
            {
              step: 2,
              period: '1ヶ月後チェック（本玉追加）',
              notifyDate: defaultNotifyDate(30),
              ratio: '60%',
              aim: '業績モメンタム確認後の本玉追加',
              condition: cand.targetHorizon || '25日線回復・トレンド確認',
              isNotified: false,
              isDone: false,
            },
          ];
    await addDoc(collection(db, 'stock_scenarios'), {
      symbol: sym,
      market: cand.market || 'NASDAQ',
      name: cand.name || sym,
      status: 'WATCH',
      holdingNote: '',
      targetHorizon: cand.targetHorizon || '中期監視',
      summary: cand.reason,
      steps,
      fullText: `【AI地合い連動提案】\n${cand.reason}\n\n目安・想定期間: ${cand.targetHorizon}`,
      createdAt: new Date().toISOString(),
    });
  };

  const handleToggleCandidateWatch = async (cand: NewWatchCandidate, nextChecked: boolean) => {
    const sym = cand.symbol.trim().toUpperCase();
    if (!sym) return;
    if (nextChecked) {
      if (activeSymbols.has(sym)) return;
      await handleAddCandidateToWatch(cand);
      return;
    }
    if (holdingSymbols.has(sym)) return;
    const snap = await getDocs(
      query(collection(db, 'stock_scenarios'), where('symbol', '==', sym))
    );
    for (const d of snap.docs) {
      const data = d.data();
      const st =
        data.status === 'HOLDING' || data.status === 'WATCH' || data.status === 'ARCHIVED'
          ? data.status
          : Array.isArray(data.steps) && data.steps.some((s: any) => s?.isDone)
          ? 'HOLDING'
          : 'WATCH';
      if (st !== 'HOLDING') {
        await deleteDoc(doc(db, 'stock_scenarios', d.id));
      }
    }
  };

  const handleArchiveBySymbol = async (symbol: string) => {
    const snap = await getDocs(
      query(collection(db, 'stock_scenarios'), where('symbol', '==', symbol))
    );
    for (const d of snap.docs) {
      await updateDoc(doc(db, 'stock_scenarios', d.id), { status: 'ARCHIVED' });
    }
  };

  const handleDeleteBySymbol = async (symbol: string) => {
    if (!window.confirm(`「${symbol}」のシナリオを完全に削除しますか？（履歴を残す場合は「見送りにする」を選べます）`)) {
      return;
    }
    const snap = await getDocs(
      query(collection(db, 'stock_scenarios'), where('symbol', '==', symbol.trim().toUpperCase()))
    );
    for (const d of snap.docs) {
      const data = d.data();
      const st =
        data.status === 'HOLDING' || data.status === 'WATCH' || data.status === 'ARCHIVED'
          ? data.status
          : Array.isArray(data.steps) && data.steps.some((s: any) => s?.isDone)
          ? 'HOLDING'
          : 'WATCH';
      if (st !== 'HOLDING') {
        await deleteDoc(doc(db, 'stock_scenarios', d.id));
      }
    }
  };

  const updateAutomationSettings = async (patch: { enabled?: boolean; monthlyBudgetJpy?: number }) => {
    await setDoc(
      doc(db, 'stock_settings', 'automation'),
      { ...patch, updatedAt: new Date().toISOString() },
      { merge: true }
    );
  };

  useEffect(() => {
    const unsubscribe = onSnapshot(
      collection(db, 'stock_reports'),
      (snapshot) => {
        const list: StockReport[] = snapshot.docs.map((d) => {
          const data = d.data();
          return {
            id: d.id,
            type: (data.type as StockReport['type']) || 'daily_market',
            date: String(data.date || ''),
            title: String(data.title || d.id),
            content: String(data.content || ''),
            verdicts: Array.isArray(data.verdicts) ? data.verdicts : [],
            newWatchCandidates: Array.isArray(data.newWatchCandidates) ? data.newWatchCandidates : [],
            goCount: typeof data.goCount === 'number' ? data.goCount : undefined,
            scenarioCount: typeof data.scenarioCount === 'number' ? data.scenarioCount : undefined,
            model: data.model ? String(data.model) : undefined,
            createdAt: data.createdAt ? String(data.createdAt) : '',
            updatedAt: data.updatedAt ? String(data.updatedAt) : '',
          };
        });
        list.sort((a, b) => {
          const dateCmp = b.date.localeCompare(a.date);
          if (dateCmp !== 0) return dateCmp;
          return (b.updatedAt || b.createdAt || '').localeCompare(a.updatedAt || a.createdAt || '');
        });
        setReports(list);
        if (list.length > 0 && !expandedId) {
          setExpandedId(list[0].id || null);
        }
        setLoading(false);
      },
      (err) => {
        console.error('Failed to load stock reports:', err);
        setLoading(false);
      }
    );
    return () => unsubscribe();
  }, []);

  const filtered = useMemo(() => {
    if (filter === 'all') return reports;
    return reports.filter((r) => r.type === filter);
  }, [reports, filter]);

  const handleDelete = async (report: StockReport) => {
    if (!report.id) return;
    if (!window.confirm(`「${report.title}」を削除しますか？`)) return;
    await deleteDoc(doc(db, 'stock_reports', report.id));
  };

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h2 style={{ margin: '0 0 4px 0', fontSize: 20, display: 'flex', alignItems: 'center', gap: 8 }}>
          <span>🤖</span> AI 自動定期分析レポート
        </h2>
        <p style={{ margin: 0, fontSize: 13, color: '#64748b' }}>
          GitHub Actions（平日朝 08:17 JST: 保有・Watch判定 / 毎週月曜 07:43 JST: セクター＆新規Watch候補）で自動生成
        </p>
      </div>

      {/* 即停止スイッチ・月額予算コントロール */}
      {(() => {
        const fxRate = currentUsage.usdToJpyRate || USD_TO_JPY;
        const currentJpy = Math.round(currentUsage.estimatedCostUsd * fxRate);
        const isOverBudget = monthlyBudgetJpy > 0 && currentJpy >= monthlyBudgetJpy;
        const active = automationEnabled && !isOverBudget;
        const pct = monthlyBudgetJpy > 0 ? Math.min(100, (currentJpy / monthlyBudgetJpy) * 100) : 0;

        return (
          <div
            style={{
              background: active ? '#f0fdf4' : '#fef2f2',
              border: `1px solid ${active ? '#bbf7d0' : '#fecaca'}`,
              borderRadius: 12,
              padding: '14px 16px',
              marginBottom: 16,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <div>
                <div style={{ fontSize: 14, fontWeight: 'bold', color: active ? '#166534' : '#991b1b' }}>
                  {!automationEnabled
                    ? '⏸️ 自動分析：停止中（手動スイッチOFF）'
                    : isOverBudget
                    ? '🛑 自動分析：月額上限到達のため停止中'
                    : '🤖 自動分析：稼働中'}
                </div>
                <div style={{ fontSize: 12, color: '#475569', marginTop: 2 }}>
                  今月 ({currentUsage.month}) 推計: <strong>{formatCostJpy(currentUsage.estimatedCostUsd, fxRate)}</strong> ({formatCostUsd(currentUsage.estimatedCostUsd)})
                  {monthlyBudgetJpy > 0 ? ` / 上限 ¥${monthlyBudgetJpy.toLocaleString()}` : ' / 上限なし'}
                  {currentUsage.batchRequests ? `（うちバッチ: ${formatCostJpy(currentUsage.batchCostUsd || 0, fxRate)}・${currentUsage.batchRequests}コール）` : ''}
                </div>
              </div>
              <button
                onClick={() => updateAutomationSettings({ enabled: !automationEnabled })}
                style={{
                  background: automationEnabled ? '#dc2626' : '#16a34a',
                  color: 'white',
                  border: 'none',
                  borderRadius: 8,
                  padding: '8px 14px',
                  fontSize: 12,
                  fontWeight: 'bold',
                  cursor: 'pointer',
                }}
              >
                {automationEnabled ? '⏸️ 今すぐ停止する' : '▶️ 自動分析を再開'}
              </button>
            </div>

            {monthlyBudgetJpy > 0 && (
              <div style={{ background: '#e2e8f0', borderRadius: 999, height: 6, marginTop: 10, overflow: 'hidden' }}>
                <div
                  style={{
                    width: `${pct}%`,
                    height: '100%',
                    background: pct >= 90 ? '#dc2626' : '#2563eb',
                    transition: 'width 0.2s',
                  }}
                />
              </div>
            )}

            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', marginTop: 10 }}>
              <span style={{ fontSize: 11, color: '#64748b' }}>自動停止上限:</span>
              {[
                { value: 500, label: '¥500' },
                { value: 1000, label: '¥1,000' },
                { value: 2000, label: '¥2,000' },
                { value: 0, label: '解除' },
              ].map((opt) => {
                const selected = monthlyBudgetJpy === opt.value;
                return (
                  <button
                    key={opt.value}
                    onClick={() => updateAutomationSettings({ monthlyBudgetJpy: opt.value })}
                    style={{
                      background: selected ? '#1e293b' : 'white',
                      color: selected ? 'white' : '#475569',
                      border: '1px solid #cbd5e1',
                      borderRadius: 999,
                      padding: '3px 10px',
                      fontSize: 11,
                      cursor: 'pointer',
                    }}
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>
        );
      })()}

      {/* フィルタ */}
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 18 }}>
        {[
          { id: 'all', label: 'すべて' },
          { id: 'daily_action', label: '🎯 毎朝 売買・Watch判断' },
          { id: 'weekly_screener', label: '🔭 月曜 週次セクター＆候補' },
          { id: 'daily_market', label: '🌍 過去 地合い' },
          { id: 'daily_gatekeeper', label: '🛡️ 旧門番' },
        ].map((tab) => {
          const active = filter === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setFilter(tab.id as ReportFilter)}
              style={{
                background: active ? '#2563eb' : '#f1f5f9',
                color: active ? 'white' : '#334155',
                border: 'none',
                borderRadius: 999,
                padding: '6px 14px',
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

      {loading ? (
        <div style={{ textAlign: 'center', padding: 40, color: '#64748b' }}>読み込み中...</div>
      ) : filtered.length === 0 ? (
        <div
          style={{
            textAlign: 'center',
            padding: '48px 20px',
            background: 'white',
            borderRadius: 12,
            border: '1px dashed #cbd5e1',
            color: '#64748b',
          }}
        >
          <div style={{ fontSize: 36, marginBottom: 10 }}>🤖</div>
          <div style={{ fontWeight: 'bold', fontSize: 15, color: '#334155', marginBottom: 6 }}>
            まだ AI 分析レポートがありません
          </div>
          <p style={{ margin: 0, fontSize: 13 }}>
            GitHub Actions の `Stock Analysis Pipeline` を手動実行するか、次回の定期実行後にここにレポートが自動表示されます。
          </p>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 14 }}>
          {filtered.map((report) => {
            const isExpanded = expandedId === report.id;
            return (
              <div
                key={report.id}
                style={{
                  background: 'white',
                  borderRadius: 10,
                  border: '1px solid #e2e8f0',
                  padding: 18,
                  boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 12,
                    cursor: 'pointer',
                  }}
                  onClick={() => setExpandedId(isExpanded ? null : report.id || null)}
                >
                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 4 }}>
                      <span
                        style={{
                          fontSize: 11,
                          background: '#eff6ff',
                          color: '#1d4ed8',
                          padding: '2px 8px',
                          borderRadius: 4,
                          fontWeight: 'bold',
                        }}
                      >
                        {report.date}
                      </span>
                      {report.model && (
                        <span
                          style={{
                            fontSize: 11,
                            background: '#f5f3ff',
                            color: '#6d28d9',
                            padding: '2px 8px',
                            borderRadius: 4,
                          }}
                        >
                          {report.model}
                        </span>
                      )}
                      {typeof report.goCount === 'number' && report.goCount > 0 && (
                        <span
                          style={{
                            fontSize: 11,
                            background: '#dcfce7',
                            color: '#166534',
                            padding: '2px 8px',
                            borderRadius: 4,
                            fontWeight: 'bold',
                          }}
                        >
                          GO {report.goCount}件
                        </span>
                      )}
                    </div>
                    <h3 style={{ margin: 0, fontSize: 17, color: '#0f172a' }}>{report.title}</h3>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        void handleDelete(report);
                      }}
                      style={{
                        background: 'none',
                        border: 'none',
                        color: '#94a3b8',
                        cursor: 'pointer',
                        fontSize: 14,
                      }}
                      title="レポートを削除"
                    >
                      🗑️
                    </button>
                    <span style={{ fontSize: 13, color: '#2563eb', fontWeight: 600 }}>
                      {isExpanded ? '▲ 閉じる' : '▼ 開く'}
                    </span>
                  </div>
                </div>

                {/* Action / verdict chips */}
                {report.verdicts && report.verdicts.length > 0 && (
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10 }}>
                    {report.verdicts.map((v, i) => {
                      const st = verdictStyle(v.verdict);
                      const symUpper = v.symbol.trim().toUpperCase();
                      const isUnownedWatch = watchSymbols.has(symUpper) && !holdingSymbols.has(symUpper);
                      return (
                        <span
                          key={`${v.symbol}_${i}`}
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 4,
                            fontSize: 11,
                            background: st.bg,
                            color: st.color,
                            border: `1px solid ${st.border}`,
                            padding: '3px 8px',
                            borderRadius: 6,
                            fontWeight: 'bold',
                          }}
                          title={v.oneLiner}
                        >
                          <span>{v.symbol}: {v.verdict}</span>
                          {isUnownedWatch && (
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                void handleDeleteBySymbol(v.symbol);
                              }}
                              title="保有していないWatch銘柄を削除"
                              style={{
                                background: 'rgba(255,255,255,0.75)',
                                border: `1px solid ${st.border}`,
                                color: '#dc2626',
                                borderRadius: 4,
                                padding: '0 4px',
                                fontSize: 10,
                                lineHeight: '14px',
                                cursor: 'pointer',
                              }}
                            >
                              🗑️
                            </button>
                          )}
                        </span>
                      );
                    })}
                  </div>
                )}

                {/* AI Watch deletion proposals (human decides) */}
                {(() => {
                  const dropProposals = (report.verdicts || []).filter(
                    (v) => v.verdict === 'DROP' && activeSymbols.has(v.symbol.toUpperCase())
                  );
                  if (dropProposals.length === 0) return null;
                  return (
                    <div
                      style={{
                        marginTop: 12,
                        padding: 12,
                        background: '#fef2f2',
                        border: '1px solid #fecaca',
                        borderRadius: 8,
                      }}
                    >
                      <div style={{ fontSize: 12, fontWeight: 'bold', color: '#991b1b', marginBottom: 8 }}>
                        🗑️ AI からの Watch 削除提案（最終判断は人間が行います）
                      </div>
                      <div style={{ display: 'grid', gap: 8 }}>
                        {dropProposals.map((p, idx) => (
                          <div
                            key={`${p.symbol}_${idx}`}
                            style={{
                              display: 'flex',
                              justifyContent: 'space-between',
                              alignItems: 'center',
                              gap: 10,
                              background: 'white',
                              padding: '8px 10px',
                              borderRadius: 6,
                              border: '1px solid #fca5a5',
                              flexWrap: 'wrap',
                            }}
                          >
                            <div style={{ flex: '1 1 220px' }}>
                              <div style={{ fontSize: 13, fontWeight: 'bold', color: '#991b1b' }}>{p.symbol}</div>
                              <div style={{ fontSize: 12, color: '#475569', marginTop: 2 }}>{p.oneLiner}</div>
                            </div>
                            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                              <button
                                type="button"
                                onClick={() => void handleArchiveBySymbol(p.symbol)}
                                style={{
                                  background: '#f1f5f9',
                                  color: '#334155',
                                  border: '1px solid #cbd5e1',
                                  borderRadius: 6,
                                  padding: '5px 10px',
                                  fontSize: 11,
                                  fontWeight: 'bold',
                                  cursor: 'pointer',
                                }}
                              >
                                📦 見送りにする
                              </button>
                              <button
                                type="button"
                                onClick={() => void handleDeleteBySymbol(p.symbol)}
                                style={{
                                  background: '#dc2626',
                                  color: 'white',
                                  border: 'none',
                                  borderRadius: 6,
                                  padding: '5px 10px',
                                  fontSize: 11,
                                  fontWeight: 'bold',
                                  cursor: 'pointer',
                                }}
                              >
                                🗑️ 削除する
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  );
                })()}

                {/* New Watch candidates proposed from daily market */}
                {report.newWatchCandidates && report.newWatchCandidates.length > 0 && (
                  <div
                    style={{
                      marginTop: 12,
                      padding: 12,
                      background: '#f8fafc',
                      border: '1px solid #cbd5e1',
                      borderRadius: 8,
                    }}
                  >
                    <div style={{ fontSize: 12, fontWeight: 'bold', color: '#1e3a8a', marginBottom: 8 }}>
                      🔭 地合い＆セクター動向からの次期 Watch 候補（チェックで Watch 登録／解除）
                    </div>
                    <div style={{ display: 'grid', gap: 8 }}>
                      {report.newWatchCandidates.map((cand, idx) => {
                        const symUpper = cand.symbol.trim().toUpperCase();
                        const isHolding = holdingSymbols.has(symUpper);
                        const isActive = activeSymbols.has(symUpper);
                        return (
                          <label
                            key={`${cand.symbol}_${idx}`}
                            onClick={(e) => e.stopPropagation()}
                            style={{
                              display: 'flex',
                              alignItems: 'flex-start',
                              gap: 10,
                              background: isActive ? '#eff6ff' : 'white',
                              padding: '10px 12px',
                              borderRadius: 8,
                              border: `1px solid ${isActive ? '#93c5fd' : '#e2e8f0'}`,
                              cursor: isHolding ? 'default' : 'pointer',
                            }}
                          >
                            <input
                              type="checkbox"
                              checked={isActive}
                              disabled={isHolding}
                              onChange={(e) => void handleToggleCandidateWatch(cand, e.target.checked)}
                              style={{
                                width: 18,
                                height: 18,
                                marginTop: 2,
                                accentColor: '#2563eb',
                                cursor: isHolding ? 'not-allowed' : 'pointer',
                                flexShrink: 0,
                              }}
                            />
                            <div style={{ flex: 1, minWidth: 0 }}>
                              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                                <div style={{ fontSize: 13, fontWeight: 'bold', color: '#0f172a' }}>
                                  {cand.name} ({cand.symbol}:{cand.market})
                                  {cand.targetHorizon ? (
                                    <span style={{ fontSize: 11, color: '#2563eb', marginLeft: 6 }}>
                                      {cand.targetHorizon}
                                    </span>
                                  ) : null}
                                </div>
                                <span
                                  style={{
                                    fontSize: 11,
                                    fontWeight: 'bold',
                                    padding: '2px 8px',
                                    borderRadius: 999,
                                    background: isHolding ? '#dcfce7' : isActive ? '#dbeafe' : '#f1f5f9',
                                    color: isHolding ? '#166534' : isActive ? '#1d4ed8' : '#64748b',
                                  }}
                                >
                                  {isHolding ? '💼 保有中' : isActive ? '👀 Watch中' : '未登録'}
                                </span>
                              </div>
                              <div style={{ fontSize: 12, color: '#475569', marginTop: 3 }}>{cand.reason}</div>
                            </div>
                          </label>
                        );
                      })}
                    </div>
                  </div>
                )}

                {isExpanded && (
                  <div
                    style={{
                      marginTop: 14,
                      paddingTop: 14,
                      borderTop: '1px solid #e2e8f0',
                    }}
                  >
                    <MarkdownView content={report.content} />
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
