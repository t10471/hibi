import { useState, useEffect } from 'react';
import { onAuthStateChanged, signInWithPopup, signOut, GoogleAuthProvider, User } from 'firebase/auth';
import { auth, googleProvider } from './firebase';
import { RegisterPage } from './RegisterPage';
import { ListPage } from './ListPage';
import { WorkoutImportPage } from './pages/WorkoutImportPage';
import { WorkoutMenusPage } from './pages/WorkoutMenusPage';
import { WorkoutLogConsultPage } from './pages/WorkoutLogConsultPage';
import { WorkoutHistoryPage } from './pages/WorkoutHistoryPage';
import { MedsPage } from './pages/MedsPage';
import { SincePage } from './pages/SincePage';
import { WorkoutMenu } from './types';

const ALLOWED_EMAIL = 't104711202@gmail.com';

type MainSection = 'workout' | 'meds' | 'since' | 'stock';
type StockSubTab = 'list' | 'register';
type WorkoutSubTab = 'menus' | 'import' | 'log' | 'history';

export function App() {
  const [user, setUser] = useState<User | null>(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [section, setSection] = useState<MainSection>('workout');
  const [stockTab, setStockTab] = useState<StockSubTab>('list');
  const [workoutTab, setWorkoutTab] = useState<WorkoutSubTab>('menus');
  const [activeMenuForLog, setActiveMenuForLog] = useState<WorkoutMenu | null>(null);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, (currentUser) => {
      setUser(currentUser);
      setAuthLoading(false);
    });
    return () => unsubscribe();
  }, []);

  const handleLogin = async () => {
    try {
      const res = await signInWithPopup(auth, googleProvider);
      const credential = GoogleAuthProvider.credentialFromResult(res);
      if (credential?.accessToken) {
        sessionStorage.setItem('google_access_token', credential.accessToken);
      }
    } catch (err: any) {
      console.error(err);
      alert(`ログイン失敗: ${err.message}`);
    }
  };

  const handleLogout = async () => {
    await signOut(auth);
  };

  const isOwner = user?.email === ALLOWED_EMAIL;

  return (
    <div
      style={{
        maxWidth: 960,
        margin: '30px auto',
        padding: '0 20px',
        fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        color: '#1f2937',
      }}
    >
      {/* ヘッダー */}
      <header
        style={{
          borderBottom: '2px solid #e5e7eb',
          paddingBottom: 16,
          marginBottom: 20,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <div>
          <h1 style={{ fontSize: 22, margin: '0 0 4px 0', display: 'flex', alignItems: 'center', gap: 8 }}>
            <span>🌱</span> Hibi Web ポータル
          </h1>
          <p style={{ margin: 0, color: '#6b7280', fontSize: 13 }}>
            筋トレ（Sheets/Gemini）・くすり・日常記録（Since）・かぶ投資シナリオ
          </p>
        </div>
        <div>
          {authLoading ? (
            <span style={{ fontSize: 13, color: '#9ca3af' }}>確認中...</span>
          ) : user ? (
            <div style={{ textAlign: 'right' }}>
              <div style={{ fontSize: 13, fontWeight: 'bold' }}>{user.displayName || user.email}</div>
              <button
                onClick={handleLogout}
                style={{
                  background: 'none',
                  border: 'none',
                  color: '#6b7280',
                  textDecoration: 'underline',
                  cursor: 'pointer',
                  fontSize: 12,
                  padding: 0,
                }}
              >
                ログアウト
              </button>
            </div>
          ) : (
            <button
              onClick={handleLogin}
              style={{
                background: '#2563eb',
                color: 'white',
                border: 'none',
                borderRadius: 6,
                padding: '8px 16px',
                fontWeight: 'bold',
                cursor: 'pointer',
              }}
            >
              Google でログイン
            </button>
          )}
        </div>
      </header>

      {/* トップレベル機能切り替え（筋トレ / くすり / 記録 / かぶ） */}
      <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
        <button
          onClick={() => setSection('workout')}
          style={{
            background: section === 'workout' ? '#16a34a' : '#f1f5f9',
            color: section === 'workout' ? 'white' : '#334155',
            border: 'none',
            borderRadius: 8,
            padding: '10px 18px',
            fontWeight: 'bold',
            fontSize: 14,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            boxShadow: section === 'workout' ? '0 2px 4px rgba(22,163,74,0.2)' : 'none',
          }}
        >
          <span>💪</span> 筋トレ & 運動
        </button>
        <button
          onClick={() => setSection('meds')}
          style={{
            background: section === 'meds' ? '#10b981' : '#f1f5f9',
            color: section === 'meds' ? 'white' : '#334155',
            border: 'none',
            borderRadius: 8,
            padding: '10px 18px',
            fontWeight: 'bold',
            fontSize: 14,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            boxShadow: section === 'meds' ? '0 2px 4px rgba(16,185,129,0.2)' : 'none',
          }}
        >
          <span>💊</span> くすり
        </button>
        <button
          onClick={() => setSection('since')}
          style={{
            background: section === 'since' ? '#0284c7' : '#f1f5f9',
            color: section === 'since' ? 'white' : '#334155',
            border: 'none',
            borderRadius: 8,
            padding: '10px 18px',
            fontWeight: 'bold',
            fontSize: 14,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            boxShadow: section === 'since' ? '0 2px 4px rgba(2,132,199,0.2)' : 'none',
          }}
        >
          <span>⏱️</span> 記録 (Since)
        </button>
        <button
          onClick={() => setSection('stock')}
          style={{
            background: section === 'stock' ? '#2563eb' : '#f1f5f9',
            color: section === 'stock' ? 'white' : '#334155',
            border: 'none',
            borderRadius: 8,
            padding: '10px 18px',
            fontWeight: 'bold',
            fontSize: 14,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            boxShadow: section === 'stock' ? '0 2px 4px rgba(37,99,235,0.2)' : 'none',
          }}
        >
          <span>📈</span> かぶシナリオ
        </button>
      </div>

      {/* サブナビゲーションタブ */}
      {user && isOwner && (section === 'workout' || section === 'stock') && (
        <div
          style={{
            display: 'flex',
            gap: 8,
            marginBottom: 24,
            borderBottom: '1px solid #e5e7eb',
            paddingBottom: 8,
          }}
        >
          {section === 'workout' ? (
            <>
              <button
                onClick={() => setWorkoutTab('menus')}
                style={{
                  background: workoutTab === 'menus' ? '#16a34a' : '#f3f4f6',
                  color: workoutTab === 'menus' ? 'white' : '#4b5563',
                  border: 'none',
                  borderRadius: 6,
                  padding: '7px 16px',
                  fontWeight: 'bold',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                📋 メニュー一覧
              </button>
              <button
                onClick={() => setWorkoutTab('import')}
                style={{
                  background: workoutTab === 'import' ? '#16a34a' : '#f3f4f6',
                  color: workoutTab === 'import' ? 'white' : '#4b5563',
                  border: 'none',
                  borderRadius: 6,
                  padding: '7px 16px',
                  fontWeight: 'bold',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                📥 スプレッドシート取込
              </button>
              <button
                onClick={() => setWorkoutTab('log')}
                style={{
                  background: workoutTab === 'log' ? '#16a34a' : '#f3f4f6',
                  color: workoutTab === 'log' ? 'white' : '#4b5563',
                  border: 'none',
                  borderRadius: 6,
                  padding: '7px 16px',
                  fontWeight: 'bold',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                📝 実績記録 & Gemini分析
              </button>
              <button
                onClick={() => setWorkoutTab('history')}
                style={{
                  background: workoutTab === 'history' ? '#16a34a' : '#f3f4f6',
                  color: workoutTab === 'history' ? 'white' : '#4b5563',
                  border: 'none',
                  borderRadius: 6,
                  padding: '7px 16px',
                  fontWeight: 'bold',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                📜 実施履歴
              </button>
            </>
          ) : (
            <>
              <button
                onClick={() => setStockTab('list')}
                style={{
                  background: stockTab === 'list' ? '#2563eb' : '#f3f4f6',
                  color: stockTab === 'list' ? 'white' : '#4b5563',
                  border: 'none',
                  borderRadius: 6,
                  padding: '7px 16px',
                  fontWeight: 'bold',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                📊 登録シナリオ一覧
              </button>
              <button
                onClick={() => setStockTab('register')}
                style={{
                  background: stockTab === 'register' ? '#2563eb' : '#f3f4f6',
                  color: stockTab === 'register' ? 'white' : '#4b5563',
                  border: 'none',
                  borderRadius: 6,
                  padding: '7px 16px',
                  fontWeight: 'bold',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                ✏️ 新規シナリオ登録
              </button>
            </>
          )}
        </div>
      )}

      {/* コンテンツエリア */}
      {!authLoading && (!user || !isOwner) ? (
        <div
          style={{
            background: '#fef2f2',
            border: '1px solid #fecaca',
            borderRadius: 8,
            padding: 32,
            textAlign: 'center',
          }}
        >
          <h2 style={{ fontSize: 18, color: '#991b1b', marginTop: 0 }}>🔒 認証が必要です</h2>
          <p style={{ color: '#7f1d1d', fontSize: 14 }}>
            {!user
              ? 'メニューの登録や実績の記録を行うには、右上のボタンから Google アカウントでログインしてください。'
              : `アカウント (${user.email}) には権限がありません。管理者 (${ALLOWED_EMAIL}) でログインしてください。`}
          </p>
        </div>
      ) : (
        <>
          {section === 'workout' ? (
            <>
              {workoutTab === 'menus' && (
                <WorkoutMenusPage
                  onStartWorkout={(m) => {
                    setActiveMenuForLog(m);
                    setWorkoutTab('log');
                  }}
                  onGoToImport={() => setWorkoutTab('import')}
                />
              )}
              {workoutTab === 'import' && (
                <WorkoutImportPage onImportSuccess={() => setWorkoutTab('menus')} />
              )}
              {workoutTab === 'log' && (
                <WorkoutLogConsultPage
                  initialMenu={activeMenuForLog}
                  onSavedSuccess={() => setWorkoutTab('history')}
                />
              )}
              {workoutTab === 'history' && <WorkoutHistoryPage />}
            </>
          ) : section === 'meds' ? (
            <MedsPage />
          ) : section === 'since' ? (
            <SincePage />
          ) : (
            <>
              {stockTab === 'list' ? (
                <ListPage />
              ) : (
                <RegisterPage onSuccess={() => setStockTab('list')} />
              )}
            </>
          )}
        </>
      )}
    </div>
  );
}

export default App;
