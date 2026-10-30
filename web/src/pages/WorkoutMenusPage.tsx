import { useState, useEffect } from 'react';
import { collection, onSnapshot, query, orderBy, deleteDoc, doc } from 'firebase/firestore';
import { db } from '../firebase';
import { WorkoutMenu } from '../types';

interface WorkoutMenusPageProps {
  onStartWorkout: (menu: WorkoutMenu) => void;
  onGoToImport: () => void;
}

export function WorkoutMenusPage({ onStartWorkout, onGoToImport }: WorkoutMenusPageProps) {
  const [menus, setMenus] = useState<WorkoutMenu[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const q = query(collection(db, 'workout_menus'), orderBy('createdAt', 'desc'));
    const unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        const list = snapshot.docs.map((doc) => ({
          id: doc.id,
          ...doc.data(),
        })) as WorkoutMenu[];
        setMenus(list);
        setLoading(false);
      },
      (err) => {
        console.error('Failed to load workout menus:', err);
        setLoading(false);
      }
    );
    return () => unsubscribe();
  }, []);

  const handleDelete = async (id?: string) => {
    if (!id) return;
    if (!window.confirm('この筋トレメニューを削除してもよろしいですか？')) return;
    try {
      await deleteDoc(doc(db, 'workout_menus', id));
    } catch (err: any) {
      alert(`削除に失敗しました: ${err.message}`);
    }
  };

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 40, color: '#6b7280' }}>読み込み中...</div>;
  }

  if (menus.length === 0) {
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
        <div style={{ fontSize: 32, marginBottom: 12 }}>🏋️‍♂️</div>
        <h3 style={{ fontSize: 18, margin: '0 0 8px 0', color: '#1e293b' }}>
          筋トレメニューがまだ登録されていません
        </h3>
        <p style={{ color: '#64748b', fontSize: 14, margin: '0 0 20px 0' }}>
          スプレッドシートからメニューを取り込むか、新規メニューを作成しましょう。
        </p>
        <button
          onClick={onGoToImport}
          style={{
            background: '#2563eb',
            color: 'white',
            border: 'none',
            borderRadius: 6,
            padding: '10px 20px',
            fontSize: 14,
            fontWeight: 'bold',
            cursor: 'pointer',
          }}
        >
          📥 スプレッドシートから取り込む
        </button>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
        <h2 style={{ fontSize: 18, margin: 0, color: '#111827' }}>
          📋 登録中の筋トレメニュー ({menus.length}件)
        </h2>
        <button
          onClick={onGoToImport}
          style={{
            background: '#2563eb',
            color: 'white',
            border: 'none',
            borderRadius: 6,
            padding: '7px 14px',
            fontSize: 13,
            fontWeight: 'bold',
            cursor: 'pointer',
          }}
        >
          ＋ 新規取り込み
        </button>
      </div>

      {menus.map((menu) => (
        <div
          key={menu.id}
          style={{
            background: '#ffffff',
            border: '1px solid #e2e8f0',
            borderRadius: 8,
            padding: 18,
            boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <h3 style={{ fontSize: 16, margin: 0, color: '#0f172a' }}>{menu.title}</h3>
                <span
                  style={{
                    background: '#e0f2fe',
                    color: '#0369a1',
                    fontSize: 12,
                    padding: '2px 8px',
                    borderRadius: 12,
                    fontWeight: 'bold',
                  }}
                >
                  {menu.targetMuscle || '全身'}
                </span>
              </div>
              <div style={{ fontSize: 12, color: '#94a3b8', marginTop: 4 }}>
                種目数: {menu.exercises.length}種目
              </div>
            </div>

            <div style={{ display: 'flex', gap: 8 }}>
              <button
                onClick={() => onStartWorkout(menu)}
                style={{
                  background: '#16a34a',
                  color: 'white',
                  border: 'none',
                  borderRadius: 6,
                  padding: '7px 14px',
                  fontSize: 13,
                  fontWeight: 'bold',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 4,
                }}
              >
                <span>💪</span> このメニューで記録 & Gemini相談
              </button>
              <button
                onClick={() => handleDelete(menu.id)}
                style={{
                  background: '#fef2f2',
                  border: '1px solid #fecaca',
                  color: '#dc2626',
                  borderRadius: 6,
                  padding: '7px 12px',
                  fontSize: 12,
                  cursor: 'pointer',
                }}
              >
                削除
              </button>
            </div>
          </div>

          {/* 種目一覧ミニバッジ */}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {menu.exercises.map((ex, i) => (
              <div
                key={ex.id || i}
                style={{
                  background: '#f8fafc',
                  border: '1px solid #e2e8f0',
                  borderRadius: 4,
                  padding: '4px 8px',
                  fontSize: 12,
                  color: '#334155',
                }}
              >
                <span style={{ fontWeight: 'bold' }}>{ex.name}</span>: {ex.targetWeight}kg × {ex.targetReps}回 × {ex.targetSets}セット
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
