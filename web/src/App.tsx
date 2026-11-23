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
import { StockKnowledgePage } from './pages/StockKnowledgePage';
import { StockReportsPage } from './pages/StockReportsPage';
import { GeminiCostBadge } from './components/GeminiCostBadge';
import { WorkoutMenu } from './types';

const ALLOWED_EMAIL = 't104711202@gmail.com';

type MainSection = 'workout' | 'meds' | 'since' | 'stock';
type StockSubTab = 'list' | 'reports' | 'register' | 'knowledge';
type WorkoutSubTab = 'menus' | 'import' | 'log' | 'history';

const STOCK_SUB_ITEMS: { id: StockSubTab; icon: string; label: string }[] = [
  { id: 'list', icon: '📊', label: '監視・保有シナリオ' },
  { id: 'reports', icon: '🤖', label: 'AI分析レポート' },
  { id: 'register', icon: '✏️', label: '新規シナリオ登録' },
  { id: 'knowledge', icon: '📚', label: '投資ナレッジ' },
];

const WORKOUT_SUB_ITEMS: { id: WorkoutSubTab; icon: string; label: string }[] = [
  { id: 'menus', icon: '📋', label: 'メニュー一覧' },
  { id: 'import', icon: '📥', label: 'スプレッドシート取込' },
  { id: 'log', icon: '📝', label: '実績記録 & AI分析' },
  { id: 'history', icon: '📜', label: '実施履歴' },
];

export function App() {
  const [user, setUser] = useState<User | null>(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [section, setSection] = useState<MainSection>('stock');
  const [stockTab, setStockTab] = useState<StockSubTab>('list');
  const [workoutTab, setWorkoutTab] = useState<WorkoutSubTab>('menus');
  const [activeMenuForLog, setActiveMenuForLog] = useState<WorkoutMenu | null>(null);
  const [isMobile, setIsMobile] = useState<boolean>(() =>
    typeof window !== 'undefined' ? window.innerWidth < 860 : false
  );
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, (currentUser) => {
      setUser(currentUser);
      setAuthLoading(false);
    });
    return () => unsubscribe();
  }, []);

  useEffect(() => {
    const mq = window.matchMedia('(max-width: 859px)');
    const onChange = () => setIsMobile(mq.matches);
    onChange();
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
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

  const currentBreadcrumb = (() => {
    if (section === 'stock') {
      const sub = STOCK_SUB_ITEMS.find((i) => i.id === stockTab);
      return `📈 かぶシナリオ / ${sub?.label || ''}`;
    }
    if (section === 'workout') {
      const sub = WORKOUT_SUB_ITEMS.find((i) => i.id === workoutTab);
      return `💪 筋トレ & 運動 / ${sub?.label || ''}`;
    }
    if (section === 'meds') return '💊 くすり';
    return '⏱️ 記録 (Since)';
  })();

  const renderSidebarNav = () => (
    <aside
      style={{
        width: sidebarCollapsed ? 68 : 228,
        flexShrink: 0,
        position: 'sticky',
        top: 16,
        background: 'white',
        border: '1px solid #e2e8f0',
        borderRadius: 12,
        padding: sidebarCollapsed ? '12px 8px' : '14px 12px',
        boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
        transition: 'width 0.15s ease',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: sidebarCollapsed ? 'center' : 'space-between',
          marginBottom: 10,
          paddingBottom: 8,
          borderBottom: '1px solid #f1f5f9',
        }}
      >
        {!sidebarCollapsed && (
          <span style={{ fontSize: 11, fontWeight: 'bold', color: '#64748b', letterSpacing: '0.04em' }}>
            MENU
          </span>
        )}
        <button
          type="button"
          onClick={() => setSidebarCollapsed((v) => !v)}
          title={sidebarCollapsed ? 'サイドバーを開く' : 'サイドバーを折りたたむ'}
          style={{
            background: '#f8fafc',
            border: '1px solid #e2e8f0',
            borderRadius: 6,
            padding: '2px 7px',
            fontSize: 11,
            color: '#475569',
            cursor: 'pointer',
          }}
        >
          {sidebarCollapsed ? '▶' : '◀'}
        </button>
      </div>

      <nav style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {/* かぶシナリオ */}
        <div>
          <button
            type="button"
            onClick={() => setSection('stock')}
            title="かぶシナリオ"
            style={{
              width: '100%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: sidebarCollapsed ? 'center' : 'flex-start',
              gap: 8,
              padding: '9px 10px',
              borderRadius: 8,
              border: 'none',
              background: section === 'stock' ? '#eff6ff' : 'transparent',
              color: section === 'stock' ? '#1d4ed8' : '#334155',
              fontWeight: section === 'stock' ? 'bold' : 600,
              fontSize: 13,
              cursor: 'pointer',
              textAlign: 'left',
            }}
          >
            <span>📈</span>
            {!sidebarCollapsed && <span>かぶシナリオ</span>}
          </button>
          {!sidebarCollapsed && section === 'stock' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginTop: 4, paddingLeft: 12, borderLeft: '2px solid #dbeafe', marginLeft: 12 }}>
              {STOCK_SUB_ITEMS.map((item) => {
                const active = stockTab === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => {
                      setSection('stock');
                      setStockTab(item.id);
                    }}
                    style={{
                      width: '100%',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 7,
                      padding: '6px 10px',
                      borderRadius: 6,
                      border: 'none',
                      background: active ? '#2563eb' : 'transparent',
                      color: active ? 'white' : '#475569',
                      fontWeight: active ? 'bold' : 500,
                      fontSize: 12,
                      cursor: 'pointer',
                      textAlign: 'left',
                    }}
                  >
                    <span>{item.icon}</span>
                    <span>{item.label}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* 筋トレ & 運動 */}
        <div>
          <button
            type="button"
            onClick={() => setSection('workout')}
            title="筋トレ & 運動"
            style={{
              width: '100%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: sidebarCollapsed ? 'center' : 'flex-start',
              gap: 8,
              padding: '9px 10px',
              borderRadius: 8,
              border: 'none',
              background: section === 'workout' ? '#f0fdf4' : 'transparent',
              color: section === 'workout' ? '#15803d' : '#334155',
              fontWeight: section === 'workout' ? 'bold' : 600,
              fontSize: 13,
              cursor: 'pointer',
              textAlign: 'left',
            }}
          >
            <span>💪</span>
            {!sidebarCollapsed && <span>筋トレ & 運動</span>}
          </button>
          {!sidebarCollapsed && section === 'workout' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginTop: 4, paddingLeft: 12, borderLeft: '2px solid #bbf7d0', marginLeft: 12 }}>
              {WORKOUT_SUB_ITEMS.map((item) => {
                const active = workoutTab === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => {
                      setSection('workout');
                      setWorkoutTab(item.id);
                    }}
                    style={{
                      width: '100%',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 7,
                      padding: '6px 10px',
                      borderRadius: 6,
                      border: 'none',
                      background: active ? '#16a34a' : 'transparent',
                      color: active ? 'white' : '#475569',
                      fontWeight: active ? 'bold' : 500,
                      fontSize: 12,
                      cursor: 'pointer',
                      textAlign: 'left',
                    }}
                  >
                    <span>{item.icon}</span>
                    <span>{item.label}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* くすり */}
        <button
          type="button"
          onClick={() => setSection('meds')}
          title="くすり"
          style={{
            width: '100%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: sidebarCollapsed ? 'center' : 'flex-start',
            gap: 8,
            padding: '9px 10px',
            borderRadius: 8,
            border: 'none',
            background: section === 'meds' ? '#ecfdf5' : 'transparent',
            color: section === 'meds' ? '#047857' : '#334155',
            fontWeight: section === 'meds' ? 'bold' : 600,
            fontSize: 13,
            cursor: 'pointer',
            textAlign: 'left',
          }}
        >
          <span>💊</span>
          {!sidebarCollapsed && <span>くすり</span>}
        </button>

        {/* 記録 (Since) */}
        <button
          type="button"
          onClick={() => setSection('since')}
          title="記録 (Since)"
          style={{
            width: '100%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: sidebarCollapsed ? 'center' : 'flex-start',
            gap: 8,
            padding: '9px 10px',
            borderRadius: 8,
            border: 'none',
            background: section === 'since' ? '#e0f2fe' : 'transparent',
            color: section === 'since' ? '#0369a1' : '#334155',
            fontWeight: section === 'since' ? 'bold' : 600,
            fontSize: 13,
            cursor: 'pointer',
            textAlign: 'left',
          }}
        >
          <span>⏱️</span>
          {!sidebarCollapsed && <span>記録 (Since)</span>}
        </button>
      </nav>
    </aside>
  );

  const renderMobileNav = () => (
    <div style={{ marginBottom: 14 }}>
      <div style={{ display: 'flex', gap: 6, overflowX: 'auto', paddingBottom: 6 }}>
        {(
          [
            { id: 'stock', label: '📈 かぶ', color: '#2563eb' },
            { id: 'workout', label: '💪 筋トレ', color: '#16a34a' },
            { id: 'meds', label: '💊 くすり', color: '#10b981' },
            { id: 'since', label: '⏱️ 記録', color: '#0284c7' },
          ] as const
        ).map((m) => (
          <button
            key={m.id}
            type="button"
            onClick={() => setSection(m.id)}
            style={{
              background: section === m.id ? m.color : 'white',
              color: section === m.id ? 'white' : '#334155',
              border: '1px solid #e2e8f0',
              borderRadius: 8,
              padding: '7px 12px',
              fontSize: 13,
              fontWeight: 'bold',
              whiteSpace: 'nowrap',
              cursor: 'pointer',
            }}
          >
            {m.label}
          </button>
        ))}
      </div>
      {user && isOwner && (section === 'stock' || section === 'workout') && (
        <div style={{ display: 'flex', gap: 6, overflowX: 'auto', paddingTop: 4 }}>
          {section === 'stock'
            ? STOCK_SUB_ITEMS.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setStockTab(item.id)}
                  style={{
                    background: stockTab === item.id ? '#1e293b' : '#f1f5f9',
                    color: stockTab === item.id ? 'white' : '#475569',
                    border: 'none',
                    borderRadius: 999,
                    padding: '5px 12px',
                    fontSize: 12,
                    fontWeight: stockTab === item.id ? 'bold' : 500,
                    whiteSpace: 'nowrap',
                    cursor: 'pointer',
                  }}
                >
                  {item.icon} {item.label}
                </button>
              ))
            : WORKOUT_SUB_ITEMS.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setWorkoutTab(item.id)}
                  style={{
                    background: workoutTab === item.id ? '#1e293b' : '#f1f5f9',
                    color: workoutTab === item.id ? 'white' : '#475569',
                    border: 'none',
                    borderRadius: 999,
                    padding: '5px 12px',
                    fontSize: 12,
                    fontWeight: workoutTab === item.id ? 'bold' : 500,
                    whiteSpace: 'nowrap',
                    cursor: 'pointer',
                  }}
                >
                  {item.icon} {item.label}
                </button>
              ))}
        </div>
      )}
    </div>
  );

  return (
    <div
      style={{
        minHeight: '100vh',
        background: '#f8fafc',
        fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        color: '#1f2937',
      }}
    >
      {/* コンパクトトップバー */}
      <header
        style={{
          background: 'white',
          borderBottom: '1px solid #e2e8f0',
          padding: '10px 20px',
        }}
      >
        <div
          style={{
            maxWidth: 1360,
            margin: '0 auto',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
            flexWrap: 'wrap',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <h1 style={{ fontSize: 18, margin: 0, display: 'flex', alignItems: 'center', gap: 6 }}>
              <span>🌱</span> Hibi Web ポータル
            </h1>
            {user && isOwner && (
              <span
                style={{
                  fontSize: 12,
                  color: '#475569',
                  background: '#f1f5f9',
                  padding: '3px 10px',
                  borderRadius: 999,
                  fontWeight: 500,
                }}
              >
                {currentBreadcrumb}
              </span>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            {user && isOwner && <GeminiCostBadge />}
            {authLoading ? (
              <span style={{ fontSize: 13, color: '#9ca3af' }}>確認中...</span>
            ) : user ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: 13, fontWeight: 'bold' }}>{user.displayName || user.email}</span>
                <button
                  onClick={handleLogout}
                  style={{
                    background: 'none',
                    border: '1px solid #cbd5e1',
                    borderRadius: 6,
                    color: '#475569',
                    cursor: 'pointer',
                    fontSize: 12,
                    padding: '4px 10px',
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
                  padding: '7px 14px',
                  fontWeight: 'bold',
                  fontSize: 13,
                  cursor: 'pointer',
                }}
              >
                Google でログイン
              </button>
            )}
          </div>
        </div>
      </header>

      {/* メインシェル（左サイドナビ ＋ 広めのコンテンツ領域） */}
      <div
        style={{
          maxWidth: 1360,
          margin: '0 auto',
          padding: '18px 20px 40px',
          display: isMobile ? 'block' : 'flex',
          gap: 20,
          alignItems: 'flex-start',
        }}
      >
        {isMobile ? renderMobileNav() : renderSidebarNav()}

        <main style={{ flex: 1, minWidth: 0 }}>
          {authLoading ? (
            <div
              style={{
                padding: 48,
                textAlign: 'center',
                color: '#6b7280',
                fontSize: 14,
              }}
            >
              認証状態を確認中...
            </div>
          ) : !user || !isOwner ? (
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
                  ) : stockTab === 'reports' ? (
                    <StockReportsPage />
                  ) : stockTab === 'register' ? (
                    <RegisterPage onSuccess={() => setStockTab('list')} />
                  ) : (
                    <StockKnowledgePage />
                  )}
                </>
              )}
            </>
          )}
        </main>
      </div>
    </div>
  );
}

export default App;
