import { useState, useEffect } from 'react';
import { collection, onSnapshot, query, orderBy, deleteDoc, doc, setDoc } from 'firebase/firestore';
import { signInWithPopup, GoogleAuthProvider } from 'firebase/auth';
import { db, auth, googleProvider } from '../firebase';
import { WorkoutMenu } from '../types';
import { fetchGoogleSheetWithApi } from '../utils/googleSheetsApi';
import { parseSpreadsheetText } from '../utils/sheetParser';

const SPREADSHEET_URL = 'https://docs.google.com/spreadsheets/d/1_KRsxyj0mhkJC2i9AmUXwny3DlpnQbQjOyqVDjiKwBs/edit?gid=1167191985#gid=1167191985';
const TARGET_GID = '1167191985';

interface WorkoutMenusPageProps {
  onStartWorkout: (menu: WorkoutMenu) => void;
  onGoToImport: () => void;
}

export function WorkoutMenusPage({ onStartWorkout, onGoToImport }: WorkoutMenusPageProps) {
  const [menus, setMenus] = useState<WorkoutMenu[]>([]);
  const [loading, setLoading] = useState(true);
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncStatus, setSyncStatus] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  useEffect(() => {
    const q = query(collection(db, 'workout_menus'), orderBy('createdAt', 'desc'));
    const unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        const list = snapshot.docs.map((d) => ({
          id: d.id,
          ...d.data(),
        })) as WorkoutMenu[];
        // 曜日順にソート (火 -> 水 -> 木 -> 土 -> 日)
        list.sort((a, b) => (a.dayOrder || 99) - (b.dayOrder || 99));
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

  const handleSyncSpreadsheet = async () => {
    setIsSyncing(true);
    setSyncStatus(null);
    try {
      let token = sessionStorage.getItem('google_access_token');
      if (!token) {
        // Google認証ポップアップでトークン取得
        const res = await signInWithPopup(auth, googleProvider);
        const credential = GoogleAuthProvider.credentialFromResult(res);
        token = credential?.accessToken || null;
        if (token) {
          sessionStorage.setItem('google_access_token', token);
        }
      }

      if (!token) {
        throw new Error('Googleアクセストークンを取得できませんでした。Googleで再ログインしてください。');
      }

      // Sheets APIで最新の V4 シートを取得
      const sheetData = await fetchGoogleSheetWithApi(token, SPREADSHEET_URL, TARGET_GID);
      const tsvContent = sheetData.values.map((row) => row.join('\t')).join('\n');
      const parseResult = parseSpreadsheetText(tsvContent, '筋トレメニュー');

      if (parseResult.menus.length === 0) {
        throw new Error('スプレッドシートから有効なメニューを抽出できませんでした。');
      }

      // Firestoreに曜日ごとのメニューを登録・更新
      const now = new Date().toISOString();
      for (const menu of parseResult.menus) {
        const docId = menu.dayOfWeek ? `menu_${menu.dayOfWeek}` : `menu_${Date.now()}_${Math.random()}`;
        await setDoc(doc(db, 'workout_menus', docId), {
          ...menu,
          updatedAt: now,
          sourceUrl: SPREADSHEET_URL,
        }, { merge: true });
      }

      setSyncStatus({
        type: 'success',
        message: `✓ スプレッドシート（${sheetData.currentTab.title}）から ${parseResult.menus.length} 曜日分のメニューを同期しました！`,
      });
    } catch (err: any) {
      console.error(err);
      // トークン期限切れの可能性
      sessionStorage.removeItem('google_access_token');
      setSyncStatus({
        type: 'error',
        message: `同期エラー: ${err.message || 'データ取得に失敗しました'}`,
      });
    } finally {
      setIsSyncing(false);
    }
  };

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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* 上部ヘッダー & シンクボタン */}
      <div
        style={{
          background: '#ffffff',
          border: '1px solid #e2e8f0',
          borderRadius: 8,
          padding: '16px 20px',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <div>
          <h2 style={{ fontSize: 18, margin: 0, color: '#111827', display: 'flex', alignItems: 'center', gap: 8 }}>
            <span>📋</span> 曜日別 筋トレメニュー ({menus.length}曜日)
          </h2>
          <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
            スプレッドシート（トレーニングメニュー案 V4）の最新メニューを管理・同期できます
          </div>
        </div>

        <div style={{ display: 'flex', gap: 10 }}>
          <button
            onClick={handleSyncSpreadsheet}
            disabled={isSyncing}
            style={{
              background: isSyncing ? '#94a3b8' : '#059669',
              color: 'white',
              border: 'none',
              borderRadius: 6,
              padding: '8px 16px',
              fontSize: 13,
              fontWeight: 'bold',
              cursor: isSyncing ? 'not-allowed' : 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              boxShadow: '0 1px 2px rgba(0,0,0,0.1)',
            }}
          >
            <span>🔄</span>
            {isSyncing ? 'スプレッドシート同期中...' : 'スプレッドシート同期 (V4)'}
          </button>
          <button
            onClick={onGoToImport}
            style={{
              background: '#f8fafc',
              border: '1px solid #cbd5e1',
              color: '#334155',
              borderRadius: 6,
              padding: '8px 12px',
              fontSize: 13,
              fontWeight: 500,
              cursor: 'pointer',
            }}
          >
            ＋ 手動取込
          </button>
        </div>
      </div>

      {/* 同期ステータスバナー */}
      {syncStatus && (
        <div
          style={{
            background: syncStatus.type === 'success' ? '#ecfdf5' : '#fef2f2',
            border: `1px solid ${syncStatus.type === 'success' ? '#a7f3d0' : '#fecaca'}`,
            color: syncStatus.type === 'success' ? '#065f46' : '#b91c1c',
            borderRadius: 6,
            padding: '10px 16px',
            fontSize: 13,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span>{syncStatus.message}</span>
          <button
            onClick={() => setSyncStatus(null)}
            style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'inherit', fontSize: 14 }}
          >
            ✕
          </button>
        </div>
      )}

      {menus.length === 0 ? (
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
            上の「スプレッドシート同期」ボタンを押して最新のメニューを取り込みましょう。
          </p>
          <button
            onClick={handleSyncSpreadsheet}
            disabled={isSyncing}
            style={{
              background: '#059669',
              color: 'white',
              border: 'none',
              borderRadius: 6,
              padding: '10px 20px',
              fontSize: 14,
              fontWeight: 'bold',
              cursor: 'pointer',
            }}
          >
            🔄 スプレッドシートから同期する
          </button>
        </div>
      ) : (
        menus.map((menu) => {
          const dayLabel = menu.dayOfWeek ? `${menu.dayOfWeek}曜日` : menu.title;
          return (
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
                    <span
                      style={{
                        background: '#059669',
                        color: 'white',
                        fontWeight: 'bold',
                        fontSize: 13,
                        padding: '3px 10px',
                        borderRadius: 14,
                      }}
                    >
                      {dayLabel}
                    </span>
                    <h3 style={{ fontSize: 16, margin: 0, color: '#0f172a' }}>{menu.title}</h3>
                  </div>
                  <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
                    鍛える部位: <span style={{ fontWeight: 500, color: '#334155' }}>{menu.targetMuscle || '全身'}</span>（全{menu.exercises.length}種目）
                  </div>
                </div>

                <div style={{ display: 'flex', gap: 8 }}>
                  <button
                    onClick={() => onStartWorkout(menu)}
                    style={{
                      background: '#10b981',
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
                    <span>💪</span> この曜日で記録開始
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

              {/* 種目テーブル */}
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
                  gap: 8,
                }}
              >
                {menu.exercises.map((ex, i) => {
                  const isBilateral =
                    (ex.targetReps && ex.targetReps.includes('左右')) ||
                    ex.name.includes('ワンハンド') ||
                    ex.name.includes('片手');

                  return (
                    <div
                      key={ex.id || i}
                      style={{
                        background: '#f8fafc',
                        border: '1px solid #e2e8f0',
                        borderRadius: 6,
                        padding: '8px 12px',
                        fontSize: 12,
                        color: '#334155',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                      }}
                    >
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontWeight: 'bold', color: '#0f172a' }}>{ex.name}</span>
                          <span
                            style={{
                              background: '#e0e7ff',
                              color: '#3730a3',
                              fontSize: 10,
                              padding: '1px 5px',
                              borderRadius: 4,
                            }}
                          >
                            {ex.targetMuscle}
                          </span>
                          {isBilateral && (
                            <span
                              style={{
                                background: '#fef3c7',
                                color: '#b45309',
                                fontSize: 10,
                                fontWeight: 'bold',
                                padding: '1px 5px',
                                borderRadius: 4,
                              }}
                            >
                              左右各
                            </span>
                          )}
                        </div>
                      </div>

                      <div style={{ textAlign: 'right', fontWeight: 600, color: '#059669' }}>
                        {ex.targetWeight}kg × {isBilateral ? `左右各${ex.targetReps.replace('左右', '')}` : `${ex.targetReps}回`} × {ex.targetSets}set
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}
