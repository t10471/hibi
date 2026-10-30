import { useState } from 'react';
import { collection, addDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { WorkoutMenu, WorkoutExercise } from '../types';
import { parseSpreadsheetText, convertGoogleSheetUrlToCsv } from '../utils/sheetParser';

interface WorkoutImportPageProps {
  onImportSuccess: () => void;
}

export function WorkoutImportPage({ onImportSuccess }: WorkoutImportPageProps) {
  const [tsvText, setTsvText] = useState('');
  const [sheetUrl, setSheetUrl] = useState('');
  const [defaultTitle, setDefaultTitle] = useState('胸・三頭の日 (Day A)');
  const [isLoadingUrl, setIsLoadingUrl] = useState(false);
  const [parsedMenus, setParsedMenus] = useState<WorkoutMenu[]>([]);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [isSaving, setIsSaving] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');

  const handleParseText = (text: string, title: string) => {
    setTsvText(text);
    setStatusMessage('');
    if (!text.trim()) {
      setParsedMenus([]);
      setWarnings([]);
      return;
    }
    const result = parseSpreadsheetText(text, title);
    setParsedMenus(result.menus);
    setWarnings(result.warnings);
  };

  const handleFetchSheetUrl = async () => {
    if (!sheetUrl.trim()) return;
    setIsLoadingUrl(true);
    setStatusMessage('');
    try {
      const csvUrl = convertGoogleSheetUrlToCsv(sheetUrl);
      if (!csvUrl) {
        throw new Error('GoogleスプレッドシートのURL形式（/spreadsheets/d/...）が認識できませんでした。');
      }

      const res = await fetch(csvUrl);
      if (!res.ok) {
        throw new Error(
          `URLの読み込みに失敗しました (${res.status})。スプレッドシートが「ウェブに公開」または共有設定されているか確認してください。`
        );
      }
      const csvData = await res.text();
      handleParseText(csvData, defaultTitle);
      setStatusMessage('✓ スプレッドシートからデータを取得しました');
    } catch (err: any) {
      console.error(err);
      setStatusMessage(`❌ エラー: ${err.message || 'データ取得に失敗しました'}`);
    } finally {
      setIsLoadingUrl(false);
    }
  };

  const handleExerciseChange = (
    menuIndex: number,
    exIndex: number,
    field: keyof WorkoutExercise,
    value: any
  ) => {
    const nextMenus = [...parsedMenus];
    const targetEx = { ...nextMenus[menuIndex].exercises[exIndex], [field]: value };
    nextMenus[menuIndex].exercises[exIndex] = targetEx;
    setParsedMenus(nextMenus);
  };

  const handleAddExercise = (menuIndex: number) => {
    const nextMenus = [...parsedMenus];
    const newEx: WorkoutExercise = {
      id: `ex_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      name: '新しい種目',
      targetMuscle: nextMenus[menuIndex].targetMuscle || '胸',
      targetWeight: 50,
      targetReps: '10',
      targetSets: 3,
      restSeconds: 90,
      notes: '',
    };
    nextMenus[menuIndex].exercises.push(newEx);
    setParsedMenus(nextMenus);
  };

  const handleDeleteExercise = (menuIndex: number, exIndex: number) => {
    const nextMenus = [...parsedMenus];
    nextMenus[menuIndex].exercises.splice(exIndex, 1);
    setParsedMenus(nextMenus);
  };

  const handleSaveToFirestore = async () => {
    if (parsedMenus.length === 0 || isSaving) return;
    setIsSaving(true);
    setStatusMessage('保存中...');
    try {
      const menusCollection = collection(db, 'workout_menus');
      for (const menu of parsedMenus) {
        await addDoc(menusCollection, {
          title: menu.title,
          targetMuscle: menu.targetMuscle,
          exercises: menu.exercises,
          sourceUrl: sheetUrl || null,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        });
      }
      setStatusMessage(`✓ ${parsedMenus.length}件のメニューをFirestoreに保存しました！`);
      setTimeout(() => {
        onImportSuccess();
      }, 1200);
    } catch (err: any) {
      console.error(err);
      setStatusMessage(`❌ 保存エラー: ${err.message || err}`);
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div>
      <div
        style={{
          background: '#f0fdf4',
          border: '1px solid #bbf7d0',
          borderRadius: 8,
          padding: 16,
          marginBottom: 20,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <span style={{ fontSize: 18 }}>💡</span>
          <strong style={{ color: '#166534', fontSize: 15 }}>
            スプレッドシート取り込みの使い方
          </strong>
        </div>
        <p style={{ margin: '0 0 6px 0', fontSize: 13, color: '#166534', lineHeight: 1.5 }}>
          Googleスプレッドシートの表セルを選択して <b>Ctrl+C (Cmd+C) でコピー</b> し、下のテキストエリアに
          <b>Ctrl+V (Cmd+V) で貼り付けるだけ</b> で自動解析されます。
        </p>
        <p style={{ margin: 0, fontSize: 12, color: '#15803d' }}>
          ※ 列の順番は「種目名, 目標重量, レップ数, セット数, 休憩秒, メモ」またはヘッダー行（種目・重量・回数・セット）に対応しています。
        </p>
      </div>

      <div
        style={{
          background: '#ffffff',
          border: '1px solid #e5e7eb',
          borderRadius: 8,
          padding: 20,
          boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
          marginBottom: 20,
        }}
      >
        <h2 style={{ fontSize: 17, marginTop: 0, marginBottom: 16 }}>
          📥 スプレッドシート データの取り込み
        </h2>

        {/* メニュー名（デフォルト） */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>
            メニュー名 (未指定時のデフォルト)
          </label>
          <input
            type="text"
            value={defaultTitle}
            onChange={(e) => {
              setDefaultTitle(e.target.value);
              if (tsvText) handleParseText(tsvText, e.target.value);
            }}
            placeholder="例: 胸・三頭の日 (Day A)"
            style={{
              width: '100%',
              padding: '8px 12px',
              borderRadius: 6,
              border: '1px solid #d1d5db',
              fontSize: 14,
              boxSizing: 'border-box',
            }}
          />
        </div>

        {/* コピペ入力エリア */}
        <div style={{ marginBottom: 18 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <label style={{ fontWeight: 'bold', fontSize: 13 }}>
              1. 表をコピーして貼り付け (TSV / CSV)
            </label>
            <button
              onClick={() => {
                const sampleTsv = `種目名\t部位\t目標重量(kg)\tレップ数\tセット数\t休憩(秒)\tメモ
ベンチプレス\t胸\t60\t8-10\t3\t90\tフォーム重視、肩甲骨寄せる
インクラインダンベルプレス\t胸\t20\t10\t3\t60\tトップで収縮
ディップス\t三頭\t0\t12\t3\t60\t自重、前傾深め
ケーブルプッシュダウン\t三頭\t25\t12-15\t3\t45\t肘固定`;
                handleParseText(sampleTsv, defaultTitle);
              }}
              style={{
                background: '#f3f4f6',
                border: '1px solid #d1d5db',
                borderRadius: 4,
                padding: '4px 8px',
                fontSize: 12,
                cursor: 'pointer',
                color: '#374151',
              }}
            >
              📋 サンプルデータを挿入
            </button>
          </div>
          <textarea
            rows={7}
            placeholder="Googleスプレッドシートでセルを選択してコピーし、ここに貼り付けてください..."
            value={tsvText}
            onChange={(e) => handleParseText(e.target.value, defaultTitle)}
            style={{
              width: '100%',
              padding: 10,
              borderRadius: 6,
              border: '1px solid #d1d5db',
              fontFamily: 'monospace',
              fontSize: 13,
              boxSizing: 'border-box',
            }}
          />
        </div>

        {/* または公開URLから取得 */}
        <div style={{ borderTop: '1px dashed #e5e7eb', paddingTop: 16 }}>
          <label style={{ display: 'block', fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>
            または: GoogleスプレッドシートのURLから取得
          </label>
          <div style={{ display: 'flex', gap: 8 }}>
            <input
              type="url"
              placeholder="https://docs.google.com/spreadsheets/d/.../edit#gid=0"
              value={sheetUrl}
              onChange={(e) => setSheetUrl(e.target.value)}
              style={{
                flex: 1,
                padding: '8px 12px',
                borderRadius: 6,
                border: '1px solid #d1d5db',
                fontSize: 14,
                boxSizing: 'border-box',
              }}
            />
            <button
              onClick={handleFetchSheetUrl}
              disabled={isLoadingUrl || !sheetUrl.trim()}
              style={{
                background: isLoadingUrl ? '#93c5fd' : '#2563eb',
                color: 'white',
                border: 'none',
                borderRadius: 6,
                padding: '8px 16px',
                fontSize: 13,
                fontWeight: 'bold',
                cursor: isLoadingUrl ? 'default' : 'pointer',
              }}
            >
              {isLoadingUrl ? '取得中...' : 'URLから取込'}
            </button>
          </div>
        </div>

        {warnings.length > 0 && (
          <div style={{ marginTop: 12, padding: 8, background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 6, fontSize: 13, color: '#92400e' }}>
            {warnings.map((w, i) => (
              <div key={i}>⚠️ {w}</div>
            ))}
          </div>
        )}
      </div>

      {/* 解析結果プレビュー & 編集テーブル */}
      {parsedMenus.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          {parsedMenus.map((menu, menuIdx) => (
            <div
              key={menuIdx}
              style={{
                background: '#ffffff',
                border: '1px solid #cbd5e1',
                borderRadius: 8,
                padding: 20,
                boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
                <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                  <input
                    type="text"
                    value={menu.title}
                    onChange={(e) => {
                      const next = [...parsedMenus];
                      next[menuIdx].title = e.target.value;
                      setParsedMenus(next);
                    }}
                    style={{
                      fontSize: 16,
                      fontWeight: 'bold',
                      border: '1px solid #cbd5e1',
                      borderRadius: 4,
                      padding: '4px 8px',
                    }}
                  />
                  <input
                    type="text"
                    placeholder="部位 (例: 胸・三頭)"
                    value={menu.targetMuscle}
                    onChange={(e) => {
                      const next = [...parsedMenus];
                      next[menuIdx].targetMuscle = e.target.value;
                      setParsedMenus(next);
                    }}
                    style={{
                      fontSize: 13,
                      border: '1px solid #cbd5e1',
                      borderRadius: 4,
                      padding: '4px 8px',
                      color: '#475569',
                    }}
                  />
                </div>
                <button
                  onClick={() => handleAddExercise(menuIdx)}
                  style={{
                    background: '#f8fafc',
                    border: '1px solid #94a3b8',
                    borderRadius: 4,
                    padding: '4px 10px',
                    fontSize: 12,
                    cursor: 'pointer',
                    fontWeight: 'bold',
                  }}
                >
                  ＋ 種目を追加
                </button>
              </div>

              {/* テーブル表示 */}
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, textAlign: 'left' }}>
                  <thead>
                    <tr style={{ background: '#f1f5f9', borderBottom: '2px solid #cbd5e1', color: '#334155' }}>
                      <th style={{ padding: '8px 10px' }}>種目名</th>
                      <th style={{ padding: '8px 10px' }}>部位</th>
                      <th style={{ padding: '8px 10px', width: 90 }}>目標重量</th>
                      <th style={{ padding: '8px 10px', width: 80 }}>レップ数</th>
                      <th style={{ padding: '8px 10px', width: 70 }}>セット</th>
                      <th style={{ padding: '8px 10px', width: 75 }}>休憩(秒)</th>
                      <th style={{ padding: '8px 10px' }}>メモ</th>
                      <th style={{ padding: '8px 10px', width: 50 }}>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {menu.exercises.map((ex, exIdx) => (
                      <tr key={ex.id || exIdx} style={{ borderBottom: '1px solid #e2e8f0' }}>
                        <td style={{ padding: '6px 8px' }}>
                          <input
                            type="text"
                            value={ex.name}
                            onChange={(e) =>
                              handleExerciseChange(menuIdx, exIdx, 'name', e.target.value)
                            }
                            style={{ width: '100%', padding: '4px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                          />
                        </td>
                        <td style={{ padding: '6px 8px' }}>
                          <input
                            type="text"
                            value={ex.targetMuscle}
                            onChange={(e) =>
                              handleExerciseChange(menuIdx, exIdx, 'targetMuscle', e.target.value)
                            }
                            style={{ width: '100%', padding: '4px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                          />
                        </td>
                        <td style={{ padding: '6px 8px' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                            <input
                              type="number"
                              step="0.5"
                              value={ex.targetWeight}
                              onChange={(e) =>
                                handleExerciseChange(
                                  menuIdx,
                                  exIdx,
                                  'targetWeight',
                                  parseFloat(e.target.value) || 0
                                )
                              }
                              style={{ width: 55, padding: '4px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                            />
                            <span style={{ fontSize: 11, color: '#64748b' }}>kg</span>
                          </div>
                        </td>
                        <td style={{ padding: '6px 8px' }}>
                          <input
                            type="text"
                            value={ex.targetReps}
                            onChange={(e) =>
                              handleExerciseChange(menuIdx, exIdx, 'targetReps', e.target.value)
                            }
                            style={{ width: 60, padding: '4px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                          />
                        </td>
                        <td style={{ padding: '6px 8px' }}>
                          <input
                            type="number"
                            min="1"
                            max="20"
                            value={ex.targetSets}
                            onChange={(e) =>
                              handleExerciseChange(
                                menuIdx,
                                exIdx,
                                'targetSets',
                                parseInt(e.target.value, 10) || 1
                              )
                            }
                            style={{ width: 50, padding: '4px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                          />
                        </td>
                        <td style={{ padding: '6px 8px' }}>
                          <input
                            type="number"
                            step="5"
                            value={ex.restSeconds}
                            onChange={(e) =>
                              handleExerciseChange(
                                menuIdx,
                                exIdx,
                                'restSeconds',
                                parseInt(e.target.value, 10) || 60
                              )
                            }
                            style={{ width: 55, padding: '4px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 13 }}
                          />
                        </td>
                        <td style={{ padding: '6px 8px' }}>
                          <input
                            type="text"
                            value={ex.notes || ''}
                            onChange={(e) =>
                              handleExerciseChange(menuIdx, exIdx, 'notes', e.target.value)
                            }
                            placeholder="フォーム等"
                            style={{ width: '100%', padding: '4px 6px', borderRadius: 4, border: '1px solid #d1d5db', fontSize: 12 }}
                          />
                        </td>
                        <td style={{ padding: '6px 8px', textAlign: 'center' }}>
                          <button
                            onClick={() => handleDeleteExercise(menuIdx, exIdx)}
                            title="削除"
                            style={{
                              background: 'none',
                              border: 'none',
                              color: '#ef4444',
                              cursor: 'pointer',
                              fontWeight: 'bold',
                              fontSize: 14,
                            }}
                          >
                            ×
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))}

          {/* 保存ボタン */}
          <div style={{ textAlign: 'right', marginTop: 10 }}>
            <button
              onClick={handleSaveToFirestore}
              disabled={isSaving}
              style={{
                background: isSaving ? '#93c5fd' : '#16a34a',
                color: 'white',
                border: 'none',
                borderRadius: 6,
                padding: '12px 28px',
                fontSize: 16,
                fontWeight: 'bold',
                cursor: isSaving ? 'default' : 'pointer',
                boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
              }}
            >
              {isSaving ? 'Firestoreに保存中...' : '💾 このメニューをFirestoreに保存する'}
            </button>
          </div>
        </div>
      )}

      {statusMessage && (
        <div
          style={{
            marginTop: 14,
            padding: 12,
            background: statusMessage.startsWith('✓') ? '#ecfdf5' : '#fef2f2',
            color: statusMessage.startsWith('✓') ? '#065f46' : '#991b1b',
            borderRadius: 6,
            fontWeight: 'bold',
            fontSize: 14,
          }}
        >
          {statusMessage}
        </div>
      )}
    </div>
  );
}
