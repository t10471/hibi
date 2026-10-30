import { useState, useEffect } from 'react';
import { collection, onSnapshot, query, orderBy, deleteDoc, doc } from 'firebase/firestore';
import { db } from '../firebase';
import { WorkoutLog } from '../types';
import { MarkdownView } from '../components/MarkdownView';

export function WorkoutHistoryPage() {
  const [logs, setLogs] = useState<WorkoutLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedLogId, setExpandedLogId] = useState<string | null>(null);

  useEffect(() => {
    const q = query(collection(db, 'workout_logs'), orderBy('createdAt', 'desc'));
    const unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        const list = snapshot.docs.map((d) => ({
          id: d.id,
          ...d.data(),
        })) as WorkoutLog[];
        setLogs(list);
        setLoading(false);
      },
      (err) => {
        console.error('Failed to load logs:', err);
        setLoading(false);
      }
    );
    return () => unsubscribe();
  }, []);

  const handleDelete = async (id?: string) => {
    if (!id) return;
    if (!window.confirm('この実績ログを削除してもよろしいですか？')) return;
    try {
      await deleteDoc(doc(db, 'workout_logs', id));
    } catch (err: any) {
      alert(`削除エラー: ${err.message}`);
    }
  };

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 40, color: '#6b7280' }}>読み込み中...</div>;
  }

  if (logs.length === 0) {
    return (
      <div
        style={{
          background: '#ffffff',
          border: '1px dashed #cbd5e1',
          borderRadius: 8,
          padding: 40,
          textAlign: 'center',
        }}
      >
        <div style={{ fontSize: 32, marginBottom: 12 }}>📜</div>
        <h3 style={{ fontSize: 18, margin: '0 0 8px 0', color: '#1e293b' }}>
          実施ログがまだありません
        </h3>
        <p style={{ color: '#64748b', fontSize: 14 }}>
          メニューを選択して実績を記録し、Geminiに分析を依頼するとここに蓄積されます。
        </p>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <h2 style={{ fontSize: 18, margin: '0 0 4px 0', color: '#111827' }}>
        📜 筋トレ実績・分析履歴 ({logs.length}件)
      </h2>

      {logs.map((log) => {
        const isExpanded = expandedLogId === log.id;
        const totalVolume = log.exercises.reduce((acc, ex) => {
          return (
            acc +
            ex.sets.reduce((sAcc, s) => (s.isCompleted ? sAcc + s.weight * s.reps : sAcc), 0)
          );
        }, 0);

        return (
          <div
            key={log.id}
            style={{
              background: '#ffffff',
              border: '1px solid #e2e8f0',
              borderRadius: 8,
              padding: 18,
              boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <span style={{ fontSize: 15, fontWeight: 'bold', color: '#0f172a' }}>
                    {log.date}
                  </span>
                  <span style={{ fontSize: 15, fontWeight: 'bold', color: '#2563eb' }}>
                    {log.menuTitle}
                  </span>
                  <span
                    style={{
                      background: '#f1f5f9',
                      color: '#475569',
                      fontSize: 12,
                      padding: '2px 8px',
                      borderRadius: 12,
                    }}
                  >
                    総ボリューム: {totalVolume.toLocaleString()} kg
                  </span>
                </div>
                {log.userNotes && (
                  <div style={{ fontSize: 13, color: '#64748b', marginTop: 6 }}>
                    💬 {log.userNotes}
                  </div>
                )}
              </div>

              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <button
                  onClick={() => setExpandedLogId(isExpanded ? null : log.id!)}
                  style={{
                    background: '#f8fafc',
                    border: '1px solid #cbd5e1',
                    borderRadius: 6,
                    padding: '6px 12px',
                    fontSize: 13,
                    cursor: 'pointer',
                    color: '#334155',
                  }}
                >
                  {isExpanded ? '詳細を閉じる ▲' : '詳細 & Gemini分析 ▼'}
                </button>
                <button
                  onClick={() => handleDelete(log.id)}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: '#ef4444',
                    fontSize: 12,
                    cursor: 'pointer',
                  }}
                >
                  削除
                </button>
              </div>
            </div>

            {/* 詳細展開 */}
            {isExpanded && (
              <div style={{ marginTop: 16, borderTop: '1px solid #f1f5f9', paddingTop: 14 }}>
                {/* 各種目のセット実績 */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 16 }}>
                  <div style={{ fontWeight: 'bold', fontSize: 13, color: '#334155' }}>
                    実施セット内訳:
                  </div>
                  {log.exercises.map((ex, exIdx) => (
                    <div
                      key={exIdx}
                      style={{
                        background: '#f8fafc',
                        padding: '8px 12px',
                        borderRadius: 6,
                        border: '1px solid #e2e8f0',
                        fontSize: 13,
                      }}
                    >
                      <div style={{ fontWeight: 'bold', marginBottom: 4 }}>
                        {ex.exerciseName} ({ex.targetMuscle})
                      </div>
                      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        {ex.sets.map((s, sIdx) => (
                          <span
                            key={sIdx}
                            style={{
                              background: s.isCompleted ? '#ecfdf5' : '#fef2f2',
                              border: `1px solid ${s.isCompleted ? '#a7f3d0' : '#fecaca'}`,
                              color: s.isCompleted ? '#065f46' : '#991b1b',
                              padding: '2px 6px',
                              borderRadius: 4,
                              fontSize: 12,
                            }}
                          >
                            Set {s.setNum}: {s.weight}kg × {s.reps}回 (RPE {s.rpe ?? '-'})
                          </span>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>

                {/* Geminiのアドバイス */}
                {log.geminiFeedback && (
                  <div
                    style={{
                      background: '#f0f9ff',
                      border: '1px solid #bae6fd',
                      borderRadius: 8,
                      padding: 16,
                    }}
                  >
                    <div style={{ fontWeight: 'bold', fontSize: 14, color: '#0369a1', marginBottom: 8 }}>
                      🤖 Geminiコーチの当時のフィードバック:
                    </div>
                    <MarkdownView content={log.geminiFeedback} />
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
