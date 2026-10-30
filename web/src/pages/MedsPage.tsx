import { useState, useEffect } from 'react';
import { collection, onSnapshot, doc, setDoc, deleteDoc, updateDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { Medication } from '../types';

function padZero(n: number): string {
  return n.toString().padStart(2, '0');
}

function formatDateIso(d: Date): string {
  const year = d.getFullYear();
  const month = padZero(d.getMonth() + 1);
  const day = padZero(d.getDate());
  return `${year}-${month}-${day}`;
}

function getRecentDays(count: number = 7): string[] {
  const days: string[] = [];
  const today = new Date();
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(today.getDate() - i);
    days.push(formatDateIso(d));
  }
  return days;
}

function formatDayLabel(iso: string): { monthDay: string; weekday: string; isToday: boolean } {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  const weekdays = ['日', '月', '火', '水', '木', '金', '土'];
  const todayIso = formatDateIso(new Date());
  return {
    monthDay: `${m}/${d}`,
    weekday: weekdays[date.getDay()],
    isToday: iso === todayIso,
  };
}

export function MedsPage() {
  const [meds, setMeds] = useState<Medication[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedDate, setSelectedDate] = useState<string>(() => formatDateIso(new Date()));
  const [showAddModal, setShowAddModal] = useState(false);
  const [newMedName, setNewMedName] = useState('');
  const [newMedHour, setNewMedHour] = useState(8);
  const [newMedMinute, setNewMedMinute] = useState(0);

  const days = getRecentDays(7);

  useEffect(() => {
    const unsubscribe = onSnapshot(
      collection(db, 'medications'),
      (snapshot) => {
        const list: Medication[] = snapshot.docs.map((d) => {
          const data = d.data();
          return {
            id: d.id,
            name: data.name || '',
            hour: typeof data.hour === 'number' ? data.hour : 8,
            minute: typeof data.minute === 'number' ? data.minute : 0,
            takenDates: Array.isArray(data.takenDates) ? data.takenDates : [],
            createdAt: data.createdAt,
            updatedAt: data.updatedAt,
          };
        });
        list.sort((a, b) => a.hour * 60 + a.minute - (b.hour * 60 + b.minute));
        setMeds(list);
        setLoading(false);
      },
      (err) => {
        console.error('Failed to load medications:', err);
        setLoading(false);
      }
    );
    return () => unsubscribe();
  }, []);

  const handleToggleTaken = async (med: Medication, date: string) => {
    try {
      const dates = new Set(med.takenDates);
      if (dates.has(date)) {
        dates.delete(date);
      } else {
        dates.add(date);
      }
      const sortedDates = Array.from(dates).sort();
      await updateDoc(doc(db, 'medications', med.id), {
        takenDates: sortedDates,
        updatedAt: new Date().toISOString(),
      });
    } catch (err: any) {
      alert(`更新に失敗しました: ${err.message}`);
    }
  };

  const handleAddMed = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = newMedName.trim();
    if (!trimmed) {
      alert('お薬名を入力してください。');
      return;
    }
    try {
      const id = `med_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`;
      const now = new Date().toISOString();
      await setDoc(doc(db, 'medications', id), {
        id,
        name: trimmed,
        hour: Number(newMedHour),
        minute: Number(newMedMinute),
        takenDates: [],
        createdAt: now,
        updatedAt: now,
      });
      setNewMedName('');
      setNewMedHour(8);
      setNewMedMinute(0);
      setShowAddModal(false);
    } catch (err: any) {
      alert(`追加に失敗しました: ${err.message}`);
    }
  };

  const handleDeleteMed = async (med: Medication) => {
    if (!window.confirm(`「${med.name}」を削除してもよろしいですか？`)) return;
    try {
      await deleteDoc(doc(db, 'medications', med.id));
    } catch (err: any) {
      alert(`削除に失敗しました: ${err.message}`);
    }
  };

  const selectedDateInfo = formatDayLabel(selectedDate);

  return (
    <div>
      {/* 画面ヘッダー */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <div>
          <h2 style={{ margin: '0 0 4px 0', fontSize: 20, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span>💊</span> くすり管理
          </h2>
          <p style={{ margin: 0, fontSize: 13, color: '#64748b' }}>
            毎日の服薬スケジュールと記録（スマホアプリとリアルタイム同期）
          </p>
        </div>
        <button
          onClick={() => setShowAddModal(true)}
          style={{
            background: '#10b981',
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
            boxShadow: '0 2px 4px rgba(16,185,129,0.2)',
          }}
        >
          <span>＋</span> お薬を追加
        </button>
      </div>

      {/* 日付選択ストリップ */}
      <div
        style={{
          background: 'white',
          borderRadius: 12,
          padding: '12px 16px',
          boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
          border: '1px solid #e2e8f0',
          marginBottom: 20,
        }}
      >
        <div style={{ fontSize: 12, color: '#64748b', marginBottom: 8, fontWeight: 500 }}>
          日付を選択:
        </div>
        <div style={{ display: 'flex', gap: 8, overflowX: 'auto', paddingBottom: 4 }}>
          {days.map((iso) => {
            const info = formatDayLabel(iso);
            const isSelected = iso === selectedDate;
            return (
              <button
                key={iso}
                onClick={() => setSelectedDate(iso)}
                style={{
                  flex: '0 0 auto',
                  minWidth: 70,
                  padding: '8px 10px',
                  borderRadius: 8,
                  border: isSelected ? '2px solid #10b981' : '1px solid #cbd5e1',
                  background: isSelected ? '#ecfdf5' : 'white',
                  cursor: 'pointer',
                  textAlign: 'center',
                  transition: 'all 0.15s ease',
                }}
              >
                <div style={{ fontSize: 11, color: isSelected ? '#059669' : '#64748b' }}>
                  {info.isToday ? '今日' : `${info.weekday}曜`}
                </div>
                <div
                  style={{
                    fontSize: 15,
                    fontWeight: isSelected ? 'bold' : 'normal',
                    color: isSelected ? '#065f46' : '#1e293b',
                    marginTop: 2,
                  }}
                >
                  {info.monthDay}
                </div>
              </button>
            );
          })}
        </div>
        <div style={{ marginTop: 10, fontSize: 13, color: '#334155', fontWeight: 500 }}>
          {selectedDateInfo.monthDay}（{selectedDateInfo.weekday}）の服用状況
        </div>
      </div>

      {/* お薬リスト */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#64748b' }}>読み込み中...</div>
      ) : meds.length === 0 ? (
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
          <div style={{ fontSize: 40, marginBottom: 12 }}>💊</div>
          <div style={{ fontWeight: 'bold', fontSize: 16, color: '#334155', marginBottom: 6 }}>
            お薬が登録されていません
          </div>
          <p style={{ margin: '0 0 16px 0', fontSize: 13 }}>
            右上の「＋ お薬を追加」ボタンから、毎日飲むお薬やサプリを登録しましょう。
          </p>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {meds.map((med) => {
            const isTaken = med.takenDates.includes(selectedDate);
            // 過去7日間の服用達成数
            const recentTakenCount = days.filter((d) => med.takenDates.includes(d)).length;

            return (
              <div
                key={med.id}
                style={{
                  background: 'white',
                  borderRadius: 10,
                  border: isTaken ? '1px solid #a7f3d0' : '1px solid #e2e8f0',
                  boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
                  padding: '14px 18px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  transition: 'border-color 0.2s ease',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                  {/* チェック切り替えボタン */}
                  <button
                    onClick={() => handleToggleTaken(med, selectedDate)}
                    style={{
                      width: 38,
                      height: 38,
                      borderRadius: 19,
                      border: isTaken ? '2px solid #10b981' : '2px solid #cbd5e1',
                      background: isTaken ? '#10b981' : '#f8fafc',
                      color: isTaken ? 'white' : 'transparent',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 18,
                      fontWeight: 'bold',
                      cursor: 'pointer',
                      transition: 'all 0.2s ease',
                      flexShrink: 0,
                    }}
                    title={isTaken ? '服用済み（タップで未服用に戻す）' : 'タップして服用済みにする'}
                  >
                    ✓
                  </button>

                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
                      <span
                        style={{
                          fontSize: 11,
                          padding: '2px 6px',
                          borderRadius: 4,
                          background: '#f1f5f9',
                          color: '#475569',
                          fontWeight: 'bold',
                          letterSpacing: 0.5,
                        }}
                      >
                        {padZero(med.hour)}:{padZero(med.minute)}
                      </span>
                      <span
                        style={{
                          fontSize: 16,
                          fontWeight: 600,
                          color: isTaken ? '#64748b' : '#0f172a',
                          textDecoration: isTaken ? 'line-through' : 'none',
                        }}
                      >
                        {med.name}
                      </span>
                    </div>

                    {/* 直近7日間の服用ドットインジケーター */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 4 }}>
                      <span style={{ fontSize: 11, color: '#94a3b8', marginRight: 4 }}>
                        直近7日 ({recentTakenCount}/7日):
                      </span>
                      {days.map((iso) => {
                        const taken = med.takenDates.includes(iso);
                        const day = formatDayLabel(iso);
                        return (
                          <span
                            key={iso}
                            title={`${day.monthDay} (${day.weekday}): ${taken ? '服用済み' : '未服用'}`}
                            style={{
                              display: 'inline-block',
                              width: 8,
                              height: 8,
                              borderRadius: 4,
                              background: taken ? '#10b981' : '#e2e8f0',
                            }}
                          />
                        );
                      })}
                    </div>
                  </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <span
                    style={{
                      fontSize: 12,
                      fontWeight: 500,
                      color: isTaken ? '#059669' : '#64748b',
                      background: isTaken ? '#ecfdf5' : '#f8fafc',
                      padding: '4px 10px',
                      borderRadius: 6,
                      border: isTaken ? '1px solid #a7f3d0' : '1px solid #e2e8f0',
                    }}
                  >
                    {isTaken ? '✓ 服用済み' : '未服用'}
                  </span>
                  <button
                    onClick={() => handleDeleteMed(med)}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: '#94a3b8',
                      cursor: 'pointer',
                      fontSize: 16,
                      padding: '6px',
                      borderRadius: 4,
                    }}
                    title="お薬を削除"
                  >
                    🗑️
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* お薬追加モーダル */}
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
            <h3 style={{ margin: '0 0 16px 0', fontSize: 18, color: '#0f172a' }}>新しいお薬を追加</h3>
            <form onSubmit={handleAddMed}>
              <div style={{ marginBottom: 16 }}>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 500, marginBottom: 6, color: '#334155' }}>
                  お薬名 / サプリ名:
                </label>
                <input
                  type="text"
                  required
                  placeholder="例: ビタミンC、降圧薬、目薬"
                  value={newMedName}
                  onChange={(e) => setNewMedName(e.target.value)}
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
                  飲む予定時刻:
                </label>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <select
                    value={newMedHour}
                    onChange={(e) => setNewMedHour(Number(e.target.value))}
                    style={{
                      padding: '8px 12px',
                      borderRadius: 6,
                      border: '1px solid #cbd5e1',
                      fontSize: 14,
                      flex: 1,
                    }}
                  >
                    {Array.from({ length: 24 }, (_, i) => (
                      <option key={i} value={i}>
                        {padZero(i)} 時
                      </option>
                    ))}
                  </select>
                  <span>:</span>
                  <select
                    value={newMedMinute}
                    onChange={(e) => setNewMedMinute(Number(e.target.value))}
                    style={{
                      padding: '8px 12px',
                      borderRadius: 6,
                      border: '1px solid #cbd5e1',
                      fontSize: 14,
                      flex: 1,
                    }}
                  >
                    {[0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55].map((m) => (
                      <option key={m} value={m}>
                        {padZero(m)} 分
                      </option>
                    ))}
                  </select>
                </div>
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
                    background: '#10b981',
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
    </div>
  );
}
