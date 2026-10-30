import { useState, useEffect } from 'react';
import { collection, onSnapshot, doc, setDoc, deleteDoc, updateDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { TrackedEvent } from '../types';

function padZero(n: number): string {
  return n.toString().padStart(2, '0');
}

function formatDateIso(d: Date): string {
  const year = d.getFullYear();
  const month = padZero(d.getMonth() + 1);
  const day = padZero(d.getDate());
  return `${year}-${month}-${day}`;
}

function parseIso(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function formatDisplayDate(iso: string): string {
  const d = parseIso(iso);
  const weekdays = ['日', '月', '火', '水', '木', '金', '土'];
  return `${d.getFullYear()}/${padZero(d.getMonth() + 1)}/${padZero(d.getDate())} (${weekdays[d.getDay()]})`;
}

function getDaysAgo(iso: string): number {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = parseIso(iso);
  target.setHours(0, 0, 0, 0);
  const diffTime = today.getTime() - target.getTime();
  return Math.floor(diffTime / (1000 * 60 * 60 * 24));
}

function getDaysAgoBadge(daysAgo: number | null): { text: string; bg: string; color: string } {
  if (daysAgo === null) {
    return { text: '未記録', bg: '#f1f5f9', color: '#64748b' };
  }
  if (daysAgo === 0) {
    return { text: '今日', bg: '#ecfdf5', color: '#059669' };
  }
  if (daysAgo === 1) {
    return { text: '昨日', bg: '#ecfdf5', color: '#047857' };
  }
  if (daysAgo <= 7) {
    return { text: `${daysAgo}日前`, bg: '#eff6ff', color: '#2563eb' };
  }
  if (daysAgo <= 30) {
    return { text: `${daysAgo}日経過`, bg: '#fffbeb', color: '#b45309' };
  }
  return { text: `${daysAgo}日経過`, bg: '#fef2f2', color: '#dc2626' };
}

export function SincePage() {
  const [events, setEvents] = useState<TrackedEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [newEventName, setNewEventName] = useState('');
  const [newEventDate, setNewEventDate] = useState(() => formatDateIso(new Date()));

  // 履歴モーダル用
  const [historyEvent, setHistoryEvent] = useState<TrackedEvent | null>(null);
  const [addPastDate, setAddPastDate] = useState(() => formatDateIso(new Date()));

  useEffect(() => {
    const unsubscribe = onSnapshot(
      collection(db, 'tracked_events'),
      (snapshot) => {
        const list: TrackedEvent[] = snapshot.docs.map((d) => {
          const data = d.data();
          const dates: string[] = Array.isArray(data.dates) ? data.dates : [];
          if (data.lastDoneDate && !dates.includes(data.lastDoneDate)) {
            dates.push(data.lastDoneDate);
          }
          dates.sort().reverse();
          return {
            id: d.id,
            name: data.name || '',
            dates,
            createdAt: data.createdAt,
            updatedAt: data.updatedAt,
          };
        });

        // 経過日数が長い順（または未記録が上）にソート
        list.sort((a, b) => {
          const aLatest = a.dates[0] ? getDaysAgo(a.dates[0]) : 999999;
          const bLatest = b.dates[0] ? getDaysAgo(b.dates[0]) : 999999;
          return bLatest - aLatest;
        });

        setEvents(list);
        setLoading(false);

        // 開いている履歴モーダルのデータもリアルタイム更新
        setHistoryEvent((prev) => (prev ? list.find((e) => e.id === prev.id) || null : null));
      },
      (err) => {
        console.error('Failed to load tracked events:', err);
        setLoading(false);
      }
    );
    return () => unsubscribe();
  }, []);

  const handleMarkToday = async (event: TrackedEvent) => {
    const todayIso = formatDateIso(new Date());
    const newDates = Array.from(new Set([todayIso, ...event.dates])).sort().reverse();
    try {
      await updateDoc(doc(db, 'tracked_events', event.id), {
        dates: newDates,
        updatedAt: new Date().toISOString(),
      });
    } catch (err: any) {
      alert(`更新に失敗しました: ${err.message}`);
    }
  };

  const handleAddEvent = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = newEventName.trim();
    if (!trimmed) {
      alert('記録名を入力してください。');
      return;
    }
    try {
      const id = `event_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`;
      const now = new Date().toISOString();
      await setDoc(doc(db, 'tracked_events', id), {
        id,
        name: trimmed,
        dates: [newEventDate],
        createdAt: now,
        updatedAt: now,
      });
      setNewEventName('');
      setNewEventDate(formatDateIso(new Date()));
      setShowAddModal(false);
    } catch (err: any) {
      alert(`追加に失敗しました: ${err.message}`);
    }
  };

  const handleDeleteEvent = async (event: TrackedEvent) => {
    if (!window.confirm(`「${event.name}」の記録を削除してもよろしいですか？`)) return;
    try {
      await deleteDoc(doc(db, 'tracked_events', event.id));
      if (historyEvent?.id === event.id) {
        setHistoryEvent(null);
      }
    } catch (err: any) {
      alert(`削除に失敗しました: ${err.message}`);
    }
  };

  const handleAddDateToHistory = async () => {
    if (!historyEvent) return;
    if (historyEvent.dates.includes(addPastDate)) {
      alert('その日付は既に記録されています。');
      return;
    }
    const newDates = Array.from(new Set([...historyEvent.dates, addPastDate])).sort().reverse();
    try {
      await updateDoc(doc(db, 'tracked_events', historyEvent.id), {
        dates: newDates,
        updatedAt: new Date().toISOString(),
      });
    } catch (err: any) {
      alert(`日付の追加に失敗しました: ${err.message}`);
    }
  };

  const handleDeleteDateFromHistory = async (iso: string) => {
    if (!historyEvent) return;
    if (!window.confirm(`${formatDisplayDate(iso)} の記録を削除しますか？`)) return;
    const newDates = historyEvent.dates.filter((d) => d !== iso);
    try {
      await updateDoc(doc(db, 'tracked_events', historyEvent.id), {
        dates: newDates,
        updatedAt: new Date().toISOString(),
      });
    } catch (err: any) {
      alert(`削除に失敗しました: ${err.message}`);
    }
  };

  return (
    <div>
      {/* 画面ヘッダー */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <div>
          <h2 style={{ margin: '0 0 4px 0', fontSize: 20, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span>⏱️</span> きろく管理 (Since)
          </h2>
          <p style={{ margin: 0, fontSize: 13, color: '#64748b' }}>
            「前回いつやったか」を可視化・履歴管理（スマホアプリとリアルタイム同期）
          </p>
        </div>
        <button
          onClick={() => setShowAddModal(true)}
          style={{
            background: '#3b82f6',
            color: 'white',
            border: 'none',
            borderRadius: 6,
            padding: '8px 16px',
            fontWeight: 'bold',
            fontSize: 14,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            boxShadow: '0 2px 4px rgba(59,130,246,0.2)',
          }}
        >
          <span>＋</span> 記録を追加
        </button>
      </div>

      {/* リスト表示 */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#64748b' }}>読み込み中...</div>
      ) : events.length === 0 ? (
        <div
          style={{
            textAlign: 'center',
            padding: '50px 20px',
            background: 'white',
            borderRadius: 12,
            border: '1px dashed #cbd5e1',
            color: '#64748b',
          }}
        >
          <div style={{ fontSize: 40, marginBottom: 12 }}>⏱️</div>
          <div style={{ fontWeight: 'bold', fontSize: 16, color: '#334155', marginBottom: 6 }}>
            記録がありません
          </div>
          <p style={{ margin: '0 0 16px 0', fontSize: 13 }}>
            右上の「＋ 記録を追加」ボタンから、「シーツ洗濯」「爪切り」「散髪」「エアコン掃除」などの定期タスクを登録しましょう。
          </p>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {events.map((event) => {
            const latest = event.dates[0] || null;
            const daysAgo = latest ? getDaysAgo(latest) : null;
            const badge = getDaysAgoBadge(daysAgo);

            return (
              <div
                key={event.id}
                style={{
                  background: 'white',
                  borderRadius: 10,
                  border: '1px solid #e2e8f0',
                  boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
                  padding: '16px 20px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  flexWrap: 'wrap',
                  gap: 12,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 16, minWidth: 260 }}>
                  {/* 経過日数バッジ */}
                  <div
                    style={{
                      minWidth: 80,
                      padding: '8px 12px',
                      borderRadius: 8,
                      background: badge.bg,
                      color: badge.color,
                      textAlign: 'center',
                      fontWeight: 'bold',
                      fontSize: 15,
                      border: `1px solid ${badge.color}33`,
                    }}
                  >
                    {badge.text}
                  </div>

                  <div>
                    <div style={{ fontSize: 17, fontWeight: 'bold', color: '#0f172a', marginBottom: 4 }}>
                      {event.name}
                    </div>
                    <div style={{ fontSize: 12, color: '#64748b' }}>
                      {latest ? (
                        <>最終: {formatDisplayDate(latest)}</>
                      ) : (
                        '実施履歴がありません'
                      )}
                      <span style={{ marginLeft: 8, color: '#94a3b8' }}>
                        (履歴 {event.dates.length}件)
                      </span>
                    </div>
                  </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <button
                    onClick={() => handleMarkToday(event)}
                    style={{
                      background: '#10b981',
                      color: 'white',
                      border: 'none',
                      borderRadius: 6,
                      padding: '7px 14px',
                      fontWeight: 'bold',
                      fontSize: 13,
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 4,
                    }}
                    title="今日やったとして記録を追加"
                  >
                    <span>🔄</span> 今日やった
                  </button>

                  <button
                    onClick={() => setHistoryEvent(event)}
                    style={{
                      background: '#f1f5f9',
                      color: '#334155',
                      border: '1px solid #cbd5e1',
                      borderRadius: 6,
                      padding: '7px 12px',
                      fontSize: 13,
                      cursor: 'pointer',
                    }}
                  >
                    履歴詳細 ({event.dates.length})
                  </button>

                  <button
                    onClick={() => handleDeleteEvent(event)}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: '#94a3b8',
                      cursor: 'pointer',
                      fontSize: 16,
                      padding: '6px',
                      borderRadius: 4,
                    }}
                    title="記録を削除"
                  >
                    🗑️
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 新規記録追加モーダル */}
      {showAddModal && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0,0,0,0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
        >
          <div
            style={{
              background: 'white',
              borderRadius: 12,
              padding: 24,
              width: '90%',
              maxWidth: 420,
              boxShadow: '0 10px 25px rgba(0,0,0,0.15)',
            }}
          >
            <h3 style={{ margin: '0 0 16px 0', fontSize: 18, color: '#0f172a' }}>新しい記録を追加</h3>
            <form onSubmit={handleAddEvent}>
              <div style={{ marginBottom: 16 }}>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 500, marginBottom: 6, color: '#334155' }}>
                  記録するイベント名:
                </label>
                <input
                  type="text"
                  required
                  placeholder="例: シーツ洗濯、爪切り、散髪、エアコン掃除"
                  value={newEventName}
                  onChange={(e) => setNewEventName(e.target.value)}
                  style={{
                    width: '100%',
                    padding: '8px 12px',
                    borderRadius: 6,
                    border: '1px solid #cbd5e1',
                    fontSize: 14,
                    boxSizing: 'border-box',
                  }}
                />
              </div>

              <div style={{ marginBottom: 20 }}>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 500, marginBottom: 6, color: '#334155' }}>
                  初回実施日:
                </label>
                <input
                  type="date"
                  required
                  value={newEventDate}
                  onChange={(e) => setNewEventDate(e.target.value)}
                  style={{
                    width: '100%',
                    padding: '8px 12px',
                    borderRadius: 6,
                    border: '1px solid #cbd5e1',
                    fontSize: 14,
                    boxSizing: 'border-box',
                  }}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  style={{
                    background: '#f1f5f9',
                    border: 'none',
                    borderRadius: 6,
                    padding: '8px 16px',
                    color: '#475569',
                    fontWeight: 500,
                    cursor: 'pointer',
                  }}
                >
                  キャンセル
                </button>
                <button
                  type="submit"
                  style={{
                    background: '#3b82f6',
                    border: 'none',
                    borderRadius: 6,
                    padding: '8px 18px',
                    color: 'white',
                    fontWeight: 'bold',
                    cursor: 'pointer',
                  }}
                >
                  登録する
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* 履歴詳細モーダル */}
      {historyEvent && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0,0,0,0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
        >
          <div
            style={{
              background: 'white',
              borderRadius: 12,
              padding: 24,
              width: '90%',
              maxWidth: 480,
              maxHeight: '85vh',
              overflowY: 'auto',
              boxShadow: '0 10px 25px rgba(0,0,0,0.15)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h3 style={{ margin: 0, fontSize: 18, color: '#0f172a' }}>{historyEvent.name} の実施履歴</h3>
              <button
                onClick={() => setHistoryEvent(null)}
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

            {/* 日付追加フォーム */}
            <div
              style={{
                background: '#f8fafc',
                padding: '12px 14px',
                borderRadius: 8,
                border: '1px solid #e2e8f0',
                marginBottom: 16,
              }}
            >
              <div style={{ fontSize: 12, fontWeight: 600, color: '#334155', marginBottom: 6 }}>
                ＋ 過去の日付を追加する:
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <input
                  type="date"
                  value={addPastDate}
                  onChange={(e) => setAddPastDate(e.target.value)}
                  style={{
                    flex: 1,
                    padding: '6px 10px',
                    borderRadius: 6,
                    border: '1px solid #cbd5e1',
                    fontSize: 13,
                  }}
                />
                <button
                  type="button"
                  onClick={handleAddDateToHistory}
                  style={{
                    background: '#3b82f6',
                    color: 'white',
                    border: 'none',
                    borderRadius: 6,
                    padding: '6px 12px',
                    fontSize: 13,
                    fontWeight: 'bold',
                    cursor: 'pointer',
                  }}
                >
                  追加
                </button>
              </div>
            </div>

            {/* 履歴リスト */}
            <div style={{ fontSize: 12, color: '#64748b', marginBottom: 8, fontWeight: 500 }}>
              履歴（全 {historyEvent.dates.length} 回）:
            </div>
            {historyEvent.dates.length === 0 ? (
              <div style={{ color: '#94a3b8', fontSize: 13, textAlign: 'center', padding: '20px 0' }}>
                記録された日付がありません
              </div>
            ) : (
              <div style={{ display: 'grid', gap: 8 }}>
                {historyEvent.dates.map((iso) => {
                  const daysAgo = getDaysAgo(iso);
                  const badge = getDaysAgoBadge(daysAgo);
                  return (
                    <div
                      key={iso}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: '10px 12px',
                        background: '#ffffff',
                        border: '1px solid #e2e8f0',
                        borderRadius: 6,
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <span
                          style={{
                            fontSize: 11,
                            padding: '2px 8px',
                            borderRadius: 4,
                            background: badge.bg,
                            color: badge.color,
                            fontWeight: 'bold',
                          }}
                        >
                          {badge.text}
                        </span>
                        <span style={{ fontSize: 14, fontWeight: 500, color: '#1e293b' }}>
                          {formatDisplayDate(iso)}
                        </span>
                      </div>
                      <button
                        onClick={() => handleDeleteDateFromHistory(iso)}
                        style={{
                          background: 'none',
                          border: 'none',
                          color: '#ef4444',
                          cursor: 'pointer',
                          fontSize: 14,
                          padding: '4px 6px',
                        }}
                        title="この日を履歴から削除"
                      >
                        ✕
                      </button>
                    </div>
                  );
                })}
              </div>
            )}

            <div style={{ marginTop: 20, textAlign: 'right' }}>
              <button
                type="button"
                onClick={() => setHistoryEvent(null)}
                style={{
                  background: '#f1f5f9',
                  border: 'none',
                  borderRadius: 6,
                  padding: '8px 18px',
                  color: '#475569',
                  fontWeight: 500,
                  cursor: 'pointer',
                }}
              >
                閉じる
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
