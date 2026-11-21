import { useEffect, useState } from 'react';
import {
  AI_STUDIO_USAGE_URL,
  CLOUD_BILLING_URL,
  emptyMonthlyUsage,
  formatCostJpy,
  formatCostUsd,
  GeminiMonthlyUsage,
  subscribeGeminiUsageHistory,
} from '../utils/geminiUsage';

export function GeminiCostBadge() {
  const [current, setCurrent] = useState<GeminiMonthlyUsage>(() => emptyMonthlyUsage());
  const [history, setHistory] = useState<GeminiMonthlyUsage[]>([]);
  const [showModal, setShowModal] = useState(false);

  useEffect(() => {
    const unsubscribe = subscribeGeminiUsageHistory((curr, hist) => {
      setCurrent(curr);
      setHistory(hist);
    });
    return () => unsubscribe();
  }, []);

  const [, monthNum] = current.month.split('-');
  const displayMonth = monthNum ? `${Number(monthNum)}月` : current.month;

  return (
    <>
      <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <button
          type="button"
          onClick={() => setShowModal(true)}
          title="今月のGemini利用コスト・トークン詳細を見る"
          style={{
            background: '#f5f3ff',
            border: '1px solid #ddd6fe',
            color: '#5b21b6',
            borderRadius: 999,
            padding: '5px 12px',
            fontSize: 12,
            fontWeight: 600,
            cursor: 'pointer',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            whiteSpace: 'nowrap',
          }}
        >
          <span>💎</span>
          <span>
            {displayMonth} Gemini: {formatCostJpy(current.estimatedCostUsd)}
          </span>
          <span style={{ color: '#7c3aed', fontWeight: 'normal' }}>
            ({formatCostUsd(current.estimatedCostUsd)} / {current.requestCount}回)
          </span>
        </button>
        <a
          href={AI_STUDIO_USAGE_URL}
          target="_blank"
          rel="noopener noreferrer"
          title="Google AI Studio で実際の使用量・コストを確認"
          style={{
            background: '#ffffff',
            border: '1px solid #cbd5e1',
            color: '#334155',
            borderRadius: 999,
            padding: '5px 10px',
            fontSize: 11,
            fontWeight: 600,
            textDecoration: 'none',
            whiteSpace: 'nowrap',
          }}
        >
          📊 AI Studio ↗
        </a>
      </div>

      {showModal && (
        <div
          onClick={() => setShowModal(false)}
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(15, 23, 42, 0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: 16,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              background: 'white',
              borderRadius: 12,
              padding: 24,
              width: '100%',
              maxWidth: 460,
              maxHeight: '85vh',
              overflowY: 'auto',
              boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.15)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
              <h3 style={{ margin: 0, fontSize: 18, color: '#1e1b4b', display: 'flex', alignItems: 'center', gap: 8 }}>
                <span>💎</span> Gemini 利用コスト ({current.month})
              </h3>
              <button
                type="button"
                onClick={() => setShowModal(false)}
                style={{
                  background: 'none',
                  border: 'none',
                  fontSize: 18,
                  color: '#64748b',
                  cursor: 'pointer',
                }}
              >
                ✕
              </button>
            </div>

            <div
              style={{
                background: 'linear-gradient(135deg, #f5f3ff 0%, #ede9fe 100%)',
                border: '1px solid #ddd6fe',
                borderRadius: 10,
                padding: 16,
                marginBottom: 16,
              }}
            >
              <div style={{ fontSize: 12, color: '#6d28d9', fontWeight: 600, marginBottom: 4 }}>
                今月の推定コスト（毎月1日 00:00 JST に自動リセット）
              </div>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
                <span style={{ fontSize: 28, fontWeight: 'bold', color: '#4c1d95' }}>
                  約 {formatCostJpy(current.estimatedCostUsd)}
                </span>
                <span style={{ fontSize: 14, color: '#6d28d9' }}>
                  ({formatCostUsd(current.estimatedCostUsd)})
                </span>
              </div>
              <div style={{ fontSize: 12, color: '#5b21b6', marginTop: 6 }}>
                利用回数: 合計 <strong>{current.requestCount}回</strong>（アプリ {current.appRequests}回 / Web{' '}
                {current.webRequests}回）
              </div>
            </div>

            {/* 用途別・トークン内訳 */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: '1fr 1fr',
                gap: 10,
                marginBottom: 16,
              }}
            >
              <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 8, padding: 12 }}>
                <div style={{ fontSize: 11, color: '#64748b', marginBottom: 4 }}>💪 筋トレ相談</div>
                <div style={{ fontSize: 15, fontWeight: 'bold', color: '#0f172a' }}>
                  {formatCostJpy(current.workoutCostUsd)} ({current.workoutRequests}回)
                </div>
                <div style={{ fontSize: 11, color: '#64748b' }}>{formatCostUsd(current.workoutCostUsd)}</div>
              </div>
              <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 8, padding: 12 }}>
                <div style={{ fontSize: 11, color: '#64748b', marginBottom: 4 }}>📈 かぶ相談</div>
                <div style={{ fontSize: 15, fontWeight: 'bold', color: '#0f172a' }}>
                  {formatCostJpy(current.stockCostUsd)} ({current.stockRequests}回)
                </div>
                <div style={{ fontSize: 11, color: '#64748b' }}>{formatCostUsd(current.stockCostUsd)}</div>
              </div>
            </div>

            <div
              style={{
                background: '#f8fafc',
                border: '1px solid #e2e8f0',
                borderRadius: 8,
                padding: 12,
                marginBottom: 16,
                fontSize: 12,
                color: '#334155',
              }}
            >
              <div style={{ fontWeight: 'bold', marginBottom: 6 }}>トークン利用量</div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
                <span>入力 (Prompt):</span>
                <strong>{current.promptTokens.toLocaleString()} tokens</strong>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
                <span>出力 (回答 + 思考):</span>
                <strong>
                  {current.outputTokens.toLocaleString()} tokens
                  {current.thinkingTokens > 0 && ` (うち思考 ${current.thinkingTokens.toLocaleString()})`}
                </strong>
              </div>
              {current.cachedTokens > 0 && (
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
                  <span>キャッシュ入力:</span>
                  <strong>{current.cachedTokens.toLocaleString()} tokens</strong>
                </div>
              )}
              {current.lastModel && (
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#64748b', marginTop: 4 }}>
                  <span>直近モデル:</span>
                  <span>{current.lastModel}</span>
                </div>
              )}
            </div>

            {/* 過去の月別履歴 */}
            {history.length > 0 && (
              <div style={{ marginBottom: 14 }}>
                <div style={{ fontSize: 12, fontWeight: 'bold', color: '#475569', marginBottom: 6 }}>
                  月別履歴
                </div>
                <div style={{ display: 'grid', gap: 6 }}>
                  {history.map((item) => (
                    <div
                      key={item.month}
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        fontSize: 12,
                        padding: '6px 10px',
                        background: item.month === current.month ? '#f5f3ff' : '#f8fafc',
                        borderRadius: 6,
                        border: '1px solid #e2e8f0',
                      }}
                    >
                      <span style={{ fontWeight: item.month === current.month ? 'bold' : 'normal' }}>
                        {item.month}
                      </span>
                      <span>
                        {item.requestCount}回 ・ {formatCostJpy(item.estimatedCostUsd)} ({formatCostUsd(item.estimatedCostUsd)})
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14 }}>
              <a
                href={AI_STUDIO_USAGE_URL}
                target="_blank"
                rel="noopener noreferrer"
                style={{
                  flex: '1 1 180px',
                  textAlign: 'center',
                  background: '#4f46e5',
                  color: 'white',
                  borderRadius: 8,
                  padding: '8px 12px',
                  fontSize: 12,
                  fontWeight: 'bold',
                  textDecoration: 'none',
                }}
              >
                📊 Google AI Studio で実利用量を見る ↗
              </a>
              <a
                href={CLOUD_BILLING_URL}
                target="_blank"
                rel="noopener noreferrer"
                style={{
                  flex: '1 1 160px',
                  textAlign: 'center',
                  background: '#f1f5f9',
                  color: '#334155',
                  border: '1px solid #cbd5e1',
                  borderRadius: 8,
                  padding: '8px 12px',
                  fontSize: 12,
                  fontWeight: 600,
                  textDecoration: 'none',
                }}
              >
                💳 Cloud Billing ↗
              </a>
            </div>

            <div style={{ fontSize: 11, color: '#64748b', lineHeight: 1.5 }}>
              ※ 1ドル=150円・各モデル標準従量課金レート（Gemini 4 Hydrogen: 入力 \$1.50 / 出力 \$7.50 per 1M tokens 等）で算出した概算です。
              無料枠キーをご利用中の場合、実際の請求額は ¥0 です。株相談のGoogle検索グラウンディング料金は含みません。
            </div>
          </div>
        </div>
      )}
    </>
  );
}
