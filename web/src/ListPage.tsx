import { useState, useEffect } from 'react';
import {
  collection,
  query,
  orderBy,
  onSnapshot,
  deleteDoc,
  doc
} from 'firebase/firestore';
import { db } from './firebase';
import { StockScenario } from './types';

export function ListPage() {
  const [scenarios, setScenarios] = useState<StockScenario[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const q = query(collection(db, 'stock_scenarios'), orderBy('createdAt', 'desc'));
    const unsubscribe = onSnapshot(q, (snapshot) => {
      const items: StockScenario[] = [];
      snapshot.forEach((docSnap) => {
        items.push({
          id: docSnap.id,
          ...docSnap.data(),
        } as StockScenario);
      });
      setScenarios(items);
      setLoading(false);
    }, (error) => {
      console.error('Error fetching scenarios:', error);
      setLoading(false);
    });

    return () => unsubscribe();
  }, []);

  const handleDelete = async (id?: string, name?: string) => {
    if (!id) return;
    if (!window.confirm(`「${name || 'この銘柄'}」のシナリオを削除してもよろしいですか？`)) return;
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <h2 style={{ fontSize: 18, margin: 0 }}>📊 登録中シナリオ ({scenarios.length}件)</h2>
        <span style={{ fontSize: 12, color: '#6b7280' }}>※ Firestoreとリアルタイム同期中</span>
      </div>

      {scenarios.length === 0 ? (
        <div style={{ padding: 48, textAlign: 'center', background: '#f9fafb', borderRadius: 8, color: '#6b7280', border: '1px dashed #d1d5db' }}>
          <p style={{ margin: '0 0 8px 0', fontSize: 15, fontWeight: 'bold' }}>登録されているシナリオはありません</p>
          <p style={{ margin: 0, fontSize: 13 }}>「新規登録」タブからGeminiとの壁打ち結果を登録してください。</p>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {scenarios.map((sc) => (
            <div key={sc.id} style={{ background: 'white', border: '1px solid #e5e7eb', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 }}>
                <div>
                  <h3 style={{ margin: '0 0 4px 0', fontSize: 19 }}>
                    {sc.name} <span style={{ fontSize: 14, color: '#6b7280', fontWeight: 'normal' }}>({sc.symbol}:{sc.market})</span>
                  </h3>
                  <div style={{ fontSize: 13, color: '#1d4ed8', fontWeight: 'bold' }}>{sc.targetHorizon}</div>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <a
                    href={`https://www.google.com/finance/quote/${sc.symbol}:${sc.market}`}
                    target="_blank"
                    rel="noreferrer"
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
                  {sc.geminiShareUrl && (
                    <a
                      href={sc.geminiShareUrl}
                      target="_blank"
                      rel="noreferrer"
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
                    onClick={() => handleDelete(sc.id, sc.name)}
                    style={{
                      background: 'none',
                      border: '1px solid #fca5a5',
                      color: '#dc2626',
                      borderRadius: 4,
                      padding: '5px 12px',
                      fontSize: 12,
                      cursor: 'pointer',
                    }}
                  >
                    削除
                  </button>
                </div>
              </div>

              <p style={{ fontSize: 14, color: '#4b5563', margin: '8px 0 14px 0', lineHeight: 1.5 }}>{sc.summary}</p>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 10 }}>
                {sc.steps?.map((step) => (
                  <div key={step.step} style={{ background: '#f8fafc', padding: 12, borderRadius: 6, border: '1px solid #e2e8f0', fontSize: 13 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                      <strong style={{ color: '#2563eb' }}>第{step.step}回 ({step.ratio})</strong>
                      <span style={{ color: '#64748b', fontSize: 12 }}>通知: <b>{step.notifyDate}</b></span>
                    </div>
                    <div style={{ color: '#475569', fontSize: 12, marginBottom: 4 }}>時期: {step.period}</div>
                    <div style={{ color: '#1e293b' }}><b>狙い:</b> {step.aim}</div>
                    <div style={{ color: '#475569', marginTop: 2 }}><b>条件:</b> {step.condition}</div>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
