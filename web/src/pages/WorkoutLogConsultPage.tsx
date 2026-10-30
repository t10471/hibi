import { useState, useEffect } from 'react';
import { collection, onSnapshot, query, orderBy, addDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { WorkoutMenu, ExerciseLogItem, WorkoutLog } from '../types';
import { askGeminiWorkoutCoach, GeminiCoachResponse } from '../utils/geminiWorkout';
import { MarkdownView } from '../components/MarkdownView';

interface WorkoutLogConsultPageProps {
  initialMenu?: WorkoutMenu | null;
  onSavedSuccess?: () => void;
}

export function WorkoutLogConsultPage({ initialMenu, onSavedSuccess }: WorkoutLogConsultPageProps) {
  const [menus, setMenus] = useState<WorkoutMenu[]>([]);
  const [selectedMenu, setSelectedMenu] = useState<WorkoutMenu | null>(initialMenu || null);
  const [exerciseLogs, setExerciseLogs] = useState<ExerciseLogItem[]>([]);
  const [userNotes, setUserNotes] = useState('');

  // Gemini state
  const [apiKey, setApiKey] = useState(() => localStorage.getItem('hibi_gemini_api_key') || '');
  const [showKey, setShowKey] = useState(false);
  const [isConsulting, setIsConsulting] = useState(false);
  const [coachResponse, setCoachResponse] = useState<GeminiCoachResponse | null>(null);
  const [conversationHistory, setConversationHistory] = useState<{ role: 'user' | 'model'; text: string }[]>([]);
  const [followUpInput, setFollowUpInput] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const [isSavingLog, setIsSavingLog] = useState(false);
  const [saveSuccessMsg, setSaveSuccessMsg] = useState('');

  useEffect(() => {
    const q = query(collection(db, 'workout_menus'), orderBy('createdAt', 'desc'));
    const unsubscribe = onSnapshot(q, (snapshot) => {
      const list = snapshot.docs.map((d) => ({ id: d.id, ...d.data() })) as WorkoutMenu[];
      setMenus(list);
      if (!selectedMenu && list.length > 0) {
        handleSelectMenu(list[0]);
      }
    });
    return () => unsubscribe();
  }, []);

  useEffect(() => {
    if (initialMenu) {
      handleSelectMenu(initialMenu);
    }
  }, [initialMenu]);

  const handleSelectMenu = (menu: WorkoutMenu) => {
    setSelectedMenu(menu);
    // Initialize exercise logs with planned values
    const logs: ExerciseLogItem[] = menu.exercises.map((ex) => {
      const setsCount = ex.targetSets || 3;
      const initialSets = Array.from({ length: setsCount }, (_, i) => ({
        setNum: i + 1,
        weight: ex.targetWeight,
        reps: parseInt(ex.targetReps.toString().match(/\d+/)?.[0] || '10', 10),
        rpe: 8,
        isCompleted: true,
      }));

      return {
        exerciseName: ex.name,
        targetMuscle: ex.targetMuscle,
        targetWeight: ex.targetWeight,
        targetReps: ex.targetReps,
        targetSets: ex.targetSets,
        sets: initialSets,
        notes: ex.notes || '',
      };
    });
    setExerciseLogs(logs);
    setCoachResponse(null);
    setConversationHistory([]);
  };

  const handleApiKeyChange = (val: string) => {
    setApiKey(val);
    localStorage.setItem('hibi_gemini_api_key', val.trim());
  };

  const handleSetChange = (exIdx: number, setIdx: number, field: string, val: any) => {
    const nextLogs = [...exerciseLogs];
    const targetSet = { ...nextLogs[exIdx].sets[setIdx], [field]: val };
    nextLogs[exIdx].sets[setIdx] = targetSet;
    setExerciseLogs(nextLogs);
  };

  const handleAddSet = (exIdx: number) => {
    const nextLogs = [...exerciseLogs];
    const ex = nextLogs[exIdx];
    const lastSet = ex.sets[ex.sets.length - 1];
    ex.sets.push({
      setNum: ex.sets.length + 1,
      weight: lastSet ? lastSet.weight : ex.targetWeight,
      reps: lastSet ? lastSet.reps : 10,
      rpe: lastSet ? lastSet.rpe : 8,
      isCompleted: true,
    });
    setExerciseLogs(nextLogs);
  };

  const handleRemoveSet = (exIdx: number, setIdx: number) => {
    const nextLogs = [...exerciseLogs];
    nextLogs[exIdx].sets.splice(setIdx, 1);
    // Re-index
    nextLogs[exIdx].sets.forEach((s, idx) => {
      s.setNum = idx + 1;
    });
    setExerciseLogs(nextLogs);
  };

  const handleConsultGemini = async (customQuestion?: string) => {
    if (!apiKey.trim()) {
      setErrorMsg('Gemini APIキーを入力してください（右上で設定）。');
      return;
    }
    if (!selectedMenu || exerciseLogs.length === 0) {
      setErrorMsg('メニューと実績が選択されていません。');
      return;
    }

    setIsConsulting(true);
    setErrorMsg('');
    try {
      const res = await askGeminiWorkoutCoach(
        apiKey,
        selectedMenu.title,
        selectedMenu.targetMuscle,
        exerciseLogs,
        userNotes,
        customQuestion,
        conversationHistory
      );
      setCoachResponse(res);

      // Append to history
      const newHistory = [...conversationHistory];
      if (customQuestion) {
        newHistory.push({ role: 'user', text: customQuestion });
      } else {
        newHistory.push({ role: 'user', text: `メニュー「${selectedMenu.title}」の実績分析依頼` });
      }
      newHistory.push({ role: 'model', text: res.answer });
      setConversationHistory(newHistory);
      setFollowUpInput('');
    } catch (err: any) {
      console.error(err);
      setErrorMsg(`Gemini相談エラー: ${err.message || err}`);
    } finally {
      setIsConsulting(false);
    }
  };

  const handleSaveWorkoutLog = async () => {
    if (!selectedMenu || isSavingLog) return;
    setIsSavingLog(true);
    setSaveSuccessMsg('');
    try {
      const logData: WorkoutLog = {
        menuId: selectedMenu.id || '',
        menuTitle: selectedMenu.title,
        date: new Date().toISOString().split('T')[0],
        exercises: exerciseLogs,
        userNotes,
        geminiFeedback: coachResponse ? coachResponse.answer : undefined,
        createdAt: new Date().toISOString(),
      };
      await addDoc(collection(db, 'workout_logs'), logData);
      setSaveSuccessMsg('✓ 本日の筋トレ実績をFirestoreに保存しました！');
      if (onSavedSuccess) {
        setTimeout(onSavedSuccess, 1000);
      }
    } catch (err: any) {
      console.error(err);
      setErrorMsg(`保存エラー: ${err.message || err}`);
    } finally {
      setIsSavingLog(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* APIキー設定バナー */}
      <div
        style={{
          background: '#f8fafc',
          border: '1px solid #e2e8f0',
          borderRadius: 8,
          padding: '10px 16px',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          fontSize: 13,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 16 }}>🔑</span>
          <span style={{ fontWeight: 'bold', color: '#334155' }}>Gemini APIキー:</span>
          <input
            type={showKey ? 'text' : 'password'}
            placeholder="AIzaSy... または AQ..."
            value={apiKey}
            onChange={(e) => handleApiKeyChange(e.target.value)}
            style={{
              padding: '4px 8px',
              borderRadius: 4,
              border: '1px solid #cbd5e1',
              width: 240,
              fontSize: 12,
            }}
          />
          <button
            onClick={() => setShowKey(!showKey)}
            style={{
              background: 'none',
              border: 'none',
              color: '#2563eb',
              cursor: 'pointer',
              fontSize: 12,
              padding: 0,
            }}
          >
            {showKey ? '隠す' : '表示'}
          </button>
        </div>
        <span style={{ color: '#64748b', fontSize: 11 }}>
          ※ キーはお使いのブラウザ（LocalStorage）にのみ安全に保持されます
        </span>
      </div>

      {/* メニュー選択 */}
      <div
        style={{
          background: '#ffffff',
          border: '1px solid #e5e7eb',
          borderRadius: 8,
          padding: 16,
        }}
      >
        <label style={{ display: 'block', fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>
          実施するメニューを選択:
        </label>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          {menus.map((m) => {
            const isSelected = selectedMenu?.id === m.id;
            return (
              <button
                key={m.id}
                onClick={() => handleSelectMenu(m)}
                style={{
                  background: isSelected ? '#2563eb' : '#f1f5f9',
                  color: isSelected ? 'white' : '#334155',
                  border: isSelected ? 'none' : '1px solid #cbd5e1',
                  borderRadius: 6,
                  padding: '8px 14px',
                  fontWeight: isSelected ? 'bold' : 'normal',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                {m.title} ({m.targetMuscle})
              </button>
            );
          })}
        </div>
      </div>

      {/* 実績入力フォーム */}
      {selectedMenu && (
        <div
          style={{
            background: '#ffffff',
            border: '1px solid #e5e7eb',
            borderRadius: 8,
            padding: 20,
            boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <h2 style={{ fontSize: 17, margin: 0 }}>
              📝 実績記録: {selectedMenu.title}
            </h2>
            <span style={{ fontSize: 13, color: '#64748b' }}>
              実施日: {new Date().toLocaleDateString('ja-JP')}
            </span>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {exerciseLogs.map((ex, exIdx) => (
              <div
                key={exIdx}
                style={{
                  background: '#f8fafc',
                  border: '1px solid #e2e8f0',
                  borderRadius: 6,
                  padding: 14,
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                  <div>
                    <span style={{ fontSize: 15, fontWeight: 'bold', color: '#0f172a' }}>
                      {exIdx + 1}. {ex.exerciseName}
                    </span>
                    <span style={{ fontSize: 12, color: '#64748b', marginLeft: 8 }}>
                      (目標: {ex.targetWeight}kg × {ex.targetReps}回 × {ex.targetSets}セット)
                    </span>
                  </div>
                  <button
                    onClick={() => handleAddSet(exIdx)}
                    style={{
                      background: 'none',
                      border: '1px solid #cbd5e1',
                      borderRadius: 4,
                      padding: '3px 8px',
                      fontSize: 11,
                      cursor: 'pointer',
                      color: '#2563eb',
                      fontWeight: 'bold',
                    }}
                  >
                    ＋ セット追加
                  </button>
                </div>

                {/* セット一覧 */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {ex.sets.map((set, setIdx) => (
                    <div
                      key={setIdx}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 12,
                        background: 'white',
                        padding: '6px 12px',
                        borderRadius: 4,
                        border: '1px solid #e2e8f0',
                        fontSize: 13,
                      }}
                    >
                      <span style={{ fontWeight: 'bold', minWidth: 50, color: '#475569' }}>
                        Set {set.setNum}
                      </span>

                      {/* 重量 */}
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <span style={{ fontSize: 11, color: '#64748b' }}>重量:</span>
                        <input
                          type="number"
                          step="0.5"
                          value={set.weight}
                          onChange={(e) =>
                            handleSetChange(exIdx, setIdx, 'weight', parseFloat(e.target.value) || 0)
                          }
                          style={{ width: 60, padding: '3px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                        />
                        <span style={{ fontSize: 11, color: '#64748b' }}>kg</span>
                      </div>

                      {/* レップ数 */}
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <span style={{ fontSize: 11, color: '#64748b' }}>回数:</span>
                        <input
                          type="number"
                          value={set.reps}
                          onChange={(e) =>
                            handleSetChange(exIdx, setIdx, 'reps', parseInt(e.target.value, 10) || 0)
                          }
                          style={{ width: 50, padding: '3px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                        />
                        <span style={{ fontSize: 11, color: '#64748b' }}>回</span>
                      </div>

                      {/* RPE */}
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <span style={{ fontSize: 11, color: '#64748b' }}>RPE:</span>
                        <select
                          value={set.rpe ?? 8}
                          onChange={(e) =>
                            handleSetChange(exIdx, setIdx, 'rpe', parseFloat(e.target.value))
                          }
                          style={{ padding: '3px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 12 }}
                        >
                          <option value="6">6 (かなり余裕あり)</option>
                          <option value="7">7 (あと3回挙げられた)</option>
                          <option value="8">8 (あと2回挙げられた)</option>
                          <option value="8.5">8.5 (あと1〜2回)</option>
                          <option value="9">9 (あと1回限界)</option>
                          <option value="9.5">9.5 (限界寸前)</option>
                          <option value="10">10 (完全限界/補助)</option>
                        </select>
                      </div>

                      {/* 完遂チェック */}
                      <label style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer', fontSize: 12 }}>
                        <input
                          type="checkbox"
                          checked={set.isCompleted}
                          onChange={(e) => handleSetChange(exIdx, setIdx, 'isCompleted', e.target.checked)}
                        />
                        <span>完遂</span>
                      </label>

                      {ex.sets.length > 1 && (
                        <button
                          onClick={() => handleRemoveSet(exIdx, setIdx)}
                          style={{
                            background: 'none',
                            border: 'none',
                            color: '#94a3b8',
                            cursor: 'pointer',
                            fontSize: 12,
                            marginLeft: 'auto',
                          }}
                        >
                          ✕
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ))}

            {/* 体感メモ */}
            <div>
              <label style={{ display: 'block', fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>
                💭 体感メモ・反省点・違和感 (Geminiの分析材料になります)
              </label>
              <textarea
                rows={3}
                placeholder="例: ベンチプレスは3セット目も挙上速度が落ちず余裕があった。肩甲骨の下制を意識できた。次回重量UPしたい。"
                value={userNotes}
                onChange={(e) => setUserNotes(e.target.value)}
                style={{
                  width: '100%',
                  padding: 8,
                  borderRadius: 6,
                  border: '1px solid #d1d5db',
                  fontSize: 13,
                  boxSizing: 'border-box',
                }}
              />
            </div>

            {/* Gemini相談ボタン & 保存ボタン */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 10 }}>
              <button
                onClick={() => handleConsultGemini()}
                disabled={isConsulting}
                style={{
                  background: isConsulting ? '#93c5fd' : '#2563eb',
                  color: 'white',
                  border: 'none',
                  borderRadius: 6,
                  padding: '10px 22px',
                  fontSize: 15,
                  fontWeight: 'bold',
                  cursor: isConsulting ? 'default' : 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  boxShadow: '0 2px 4px rgba(37,99,235,0.2)',
                }}
              >
                <span>🤖</span> {isConsulting ? 'Gemini分析中...' : 'Geminiコーチに分析を依頼する'}
              </button>

              <button
                onClick={handleSaveWorkoutLog}
                disabled={isSavingLog}
                style={{
                  background: isSavingLog ? '#cbd5e1' : '#f1f5f9',
                  color: '#1e293b',
                  border: '1px solid #cbd5e1',
                  borderRadius: 6,
                  padding: '10px 18px',
                  fontSize: 14,
                  fontWeight: 'bold',
                  cursor: isSavingLog ? 'default' : 'pointer',
                }}
              >
                {isSavingLog ? '保存中...' : '💾 実績をFirestoreに保存'}
              </button>
            </div>
          </div>
        </div>
      )}

      {errorMsg && (
        <div style={{ padding: 12, background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 6, color: '#991b1b', fontSize: 13 }}>
          {errorMsg}
        </div>
      )}

      {saveSuccessMsg && (
        <div style={{ padding: 12, background: '#ecfdf5', border: '1px solid #a7f3d0', borderRadius: 6, color: '#065f46', fontSize: 13, fontWeight: 'bold' }}>
          {saveSuccessMsg}
        </div>
      )}

      {/* Geminiからのアドバイス表示 */}
      {coachResponse && (
        <div
          style={{
            background: '#ffffff',
            border: '2px solid #3b82f6',
            borderRadius: 10,
            padding: 22,
            boxShadow: '0 4px 6px rgba(59,130,246,0.08)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14, borderBottom: '1px solid #e2e8f0', paddingBottom: 10 }}>
            <span style={{ fontSize: 22 }}>🏋️‍♂️</span>
            <div>
              <h3 style={{ fontSize: 17, margin: 0, color: '#1e3a8a' }}>
                Gemini パーソナルコーチの分析結果
              </h3>
              <span style={{ fontSize: 12, color: '#64748b' }}>
                漸進性過負荷・ボリューム・RPE・リカバリー推奨
              </span>
            </div>
          </div>

          <MarkdownView content={coachResponse.answer} />

          {/* 次の質問サジェスト (垂直カード) */}
          {coachResponse.suggestions.length > 0 && (
            <div style={{ marginTop: 22, borderTop: '1px dashed #cbd5e1', paddingTop: 14 }}>
              <div style={{ fontSize: 13, fontWeight: 'bold', color: '#475569', marginBottom: 8 }}>
                💡 続けてGeminiに質問する:
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {coachResponse.suggestions.map((sug, i) => (
                  <button
                    key={i}
                    onClick={() => handleConsultGemini(sug)}
                    disabled={isConsulting}
                    style={{
                      textAlign: 'left',
                      background: '#f8fafc',
                      border: '1px solid #cbd5e1',
                      borderRadius: 6,
                      padding: '8px 12px',
                      fontSize: 13,
                      color: '#1d4ed8',
                      cursor: isConsulting ? 'default' : 'pointer',
                      lineHeight: 1.4,
                      transition: 'background 0.2s',
                    }}
                    onMouseOver={(e) => (e.currentTarget.style.background = '#eff6ff')}
                    onMouseOut={(e) => (e.currentTarget.style.background = '#f8fafc')}
                  >
                    💬 {sug}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* 自由入力での追質問 */}
          <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
            <input
              type="text"
              placeholder="質問を追加（例: 次回のベンチプレスのウォーミングアップ重量配分は？）"
              value={followUpInput}
              onChange={(e) => setFollowUpInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && followUpInput.trim() && !isConsulting) {
                  handleConsultGemini(followUpInput);
                }
              }}
              style={{
                flex: 1,
                padding: '8px 12px',
                borderRadius: 6,
                border: '1px solid #cbd5e1',
                fontSize: 13,
              }}
            />
            <button
              onClick={() => handleConsultGemini(followUpInput)}
              disabled={isConsulting || !followUpInput.trim()}
              style={{
                background: isConsulting || !followUpInput.trim() ? '#93c5fd' : '#2563eb',
                color: 'white',
                border: 'none',
                borderRadius: 6,
                padding: '8px 16px',
                fontSize: 13,
                fontWeight: 'bold',
                cursor: isConsulting || !followUpInput.trim() ? 'default' : 'pointer',
              }}
            >
              送信
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
