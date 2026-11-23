import { useEffect, useMemo, useState } from 'react';
import { collection, deleteDoc, doc, onSnapshot, setDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { StockReport } from '../types';
import { MarkdownView } from '../components/MarkdownView';
import {
  emptyMonthlyUsage,
  formatCostJpy,
  formatCostUsd,
  GeminiMonthlyUsage,
  subscribeGeminiUsageHistory,
  USD_TO_JPY,
} from '../utils/geminiUsage';

type ReportFilter = 'all' | 'daily_market' | 'daily_gatekeeper' | 'weekly_screener';

function verdictStyle(verdict: string): { bg: string; color: string; border: string } {
  switch (verdict) {
    case 'GO':
      return { bg: '#dcfce7', color: '#166534', border: '#86efac' };
    case 'ABORT':
      return { bg: '#fee2e2', color: '#991b1b', border: '#fca5a5' };
    default:
      return { bg: '#fef3c7', color: '#92400e', border: '#fde68a' };
  }
}

export function StockReportsPage() {
  const [reports, setReports] = useState<StockReport[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<ReportFilter>('all');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [automationEnabled, setAutomationEnabled] = useState(true);
  const [monthlyBudgetJpy, setMonthlyBudgetJpy] = useState(1000);
  const [currentUsage, setCurrentUsage] = useState<GeminiMonthlyUsage>(() => emptyMonthlyUsage());

  useEffect(() => {
    const unsubUsage = subscribeGeminiUsageHistory((current) => {
      setCurrentUsage(current);
    });
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
      unsubSettings();
    };
  }, []);

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
          GitHub Actions（平日 07:30 JST: Hydrogen / 毎週日曜 08:00 JST: Neon）で自動生成された市場地合い・門番判定・銘柄発掘レポート
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
          { id: 'daily_market', label: '🌍 市場概況' },
          { id: 'daily_gatekeeper', label: '🛡️ 門番判定' },
          { id: 'weekly_screener', label: '🔭 週次発掘' },
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

                {/* Gatekeeper verdict chips */}
                {report.verdicts && report.verdicts.length > 0 && (
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10 }}>
                    {report.verdicts.map((v, i) => {
                      const st = verdictStyle(v.verdict);
                      return (
                        <span
                          key={`${v.symbol}_${i}`}
                          title={v.oneLiner}
                          style={{
                            fontSize: 11,
                            background: st.bg,
                            color: st.color,
                            border: `1px solid ${st.border}`,
                            padding: '3px 8px',
                            borderRadius: 6,
                            fontWeight: 'bold',
                          }}
                        >
                          {v.symbol}: {v.verdict}
                        </span>
                      );
                    })}
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
