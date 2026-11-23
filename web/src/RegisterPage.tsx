import { useState } from 'react';
import { collection, addDoc } from 'firebase/firestore';
import { db } from './firebase';
import { StockScenario } from './types';

export const GEMINI_PROMPT_TEMPLATE = `これまでの議論をふまえてアプリ（hibi）に登録したいので、以下のJSONコードブロックの形式で出力してください。
※notifyDate（通知日）は各フェーズの目安開始日（YYYY-MM-DD）に設定してください。

\`\`\`json
{
  "symbol": "銘柄コード（例: NFLX や 7203）",
  "market": "市場（例: NASDAQ, TYO, NYSE）",
  "name": "企業名（例: Netflix）",
  "targetHorizon": "目標株価や保有期間（例: 85〜100ドル（2〜3年中長期））",
  "summary": "シナリオの要約（2〜3行程度）",
  "steps": [
    {
      "step": 1,
      "period": "時期（例: 2026年11月〜12月中旬）",
      "notifyDate": "YYYY-MM-DD",
      "ratio": "資金割合（例: 30%）",
      "aim": "狙い（例: 年末の節税売りで60ドル前後の底固め）",
      "condition": "判断基準（何を確認して買うか）"
    }
  ],
  "fullText": "先ほどの分析・議論の全文（そのまま残す）"
}
\`\`\``;

interface RegisterPageProps {
  onSuccess: () => void;
}

export function RegisterPage({ onSuccess }: RegisterPageProps) {
  const [jsonInput, setJsonInput] = useState('');
  const [geminiShareUrl, setGeminiShareUrl] = useState('');
  const [parsedScenario, setParsedScenario] = useState<StockScenario | null>(null);
  const [parseError, setParseError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [regMode, setRegMode] = useState<'quick' | 'json'>('quick');
  const [scenarioStatus, setScenarioStatus] = useState<'WATCH' | 'HOLDING'>('HOLDING');
  const [holdingQuantity, setHoldingQuantity] = useState('');
  const [averageCost, setAverageCost] = useState('');
  const [holdingNote, setHoldingNote] = useState('');
  // かんたん登録用フィールド
  const [quickSymbol, setQuickSymbol] = useState('');
  const [quickMarket, setQuickMarket] = useState('NASDAQ');
  const [quickName, setQuickName] = useState('');
  const [quickHorizon, setQuickHorizon] = useState('');
  const [quickSummary, setQuickSummary] = useState('');

  const copyPromptTemplate = () => {
    navigator.clipboard.writeText(GEMINI_PROMPT_TEMPLATE);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleJsonChange = (text: string) => {
    setJsonInput(text);
    setStatusMessage('');
    if (!text.trim()) {
      setParsedScenario(null);
      setParseError(null);
      return;
    }

    try {
      let jsonStr = text.trim();
      const match = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/);
      if (match) {
        jsonStr = match[1];
      }
      const data = JSON.parse(jsonStr);
      if (!data.symbol || !data.steps) {
        throw new Error('symbol または steps が見つかりません');
      }
      setParsedScenario({
        ...data,
        status: data.status === 'HOLDING' ? 'HOLDING' : scenarioStatus,
        holdingNote: data.holdingNote || holdingNote,
        geminiShareUrl,
      });
      if (data.status === 'HOLDING' || data.status === 'WATCH') {
        setScenarioStatus(data.status);
      }
      if (typeof data.holdingNote === 'string') {
        setHoldingNote(data.holdingNote);
      }
      setParseError(null);
    } catch (e: any) {
      setParsedScenario(null);
      setParseError(`JSON解析エラー: ${e.message}`);
    }
  };

  const handleSave = async () => {
    if (!parsedScenario || isSubmitting) return;
    setIsSubmitting(true);
    const finalData = {
      ...parsedScenario,
      status: scenarioStatus,
      holdingQuantity: holdingQuantity.trim(),
      averageCost: averageCost.trim(),
      holdingNote: holdingNote.trim(),
      geminiShareUrl,
      createdAt: new Date().toISOString(),
    };
    try {
      setStatusMessage('登録中...');
      await addDoc(collection(db, 'stock_scenarios'), finalData);
      setStatusMessage(`✓ 「${finalData.name} (${finalData.symbol})」のシナリオを登録しました！`);
      setJsonInput('');
      setGeminiShareUrl('');
      setHoldingQuantity('');
      setAverageCost('');
      setHoldingNote('');
      setScenarioStatus('HOLDING');
      setParsedScenario(null);
      setParseError(null);
      setTimeout(() => {
        onSuccess();
      }, 1000);
    } catch (e: any) {
      console.error(e);
      setStatusMessage(`❌ 登録エラー: ${e.message || e}`);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleQuickSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting) return;
    const sym = quickSymbol.trim().toUpperCase();
    const nm = quickName.trim() || sym;
    if (!sym && !nm) {
      setStatusMessage('❌ 銘柄コードまたは企業名を入力してください');
      return;
    }
    setIsSubmitting(true);
    try {
      setStatusMessage('登録中...');
      const steps =
        scenarioStatus === 'HOLDING'
          ? [
              {
                step: 1,
                period: '初回購入済み',
                notifyDate: '',
                ratio: '初回',
                aim: '初期ポジション保有',
                condition: '保有中',
                isNotified: true,
                isDone: true,
              },
              {
                step: 2,
                period: '押し目・買い増し検討',
                notifyDate: '',
                ratio: '追加',
                aim: quickSummary.trim() || '地合い・業績が良好な押し目で買い増し',
                condition: quickHorizon.trim() || 'AIの日次判定で ADD 条件合致時',
                isNotified: false,
                isDone: false,
              },
            ]
          : [
              {
                step: 1,
                period: '条件合致時',
                notifyDate: '',
                ratio: '初回',
                aim: quickSummary.trim() || 'エントリーチャンス待ち',
                condition: quickHorizon.trim() || 'AIの日次判定で BUY 条件合致時',
                isNotified: false,
                isDone: false,
              },
            ];

      await addDoc(collection(db, 'stock_scenarios'), {
        symbol: sym || nm,
        market: quickMarket.trim() || 'NASDAQ',
        name: nm,
        status: scenarioStatus,
        holdingQuantity: holdingQuantity.trim(),
        averageCost: averageCost.trim(),
        holdingNote: holdingNote.trim(),
        targetHorizon: quickHorizon.trim() || '中長期（2〜3年）',
        summary: quickSummary.trim() || (scenarioStatus === 'HOLDING' ? '保有中（買い増し判定対象）' : '注目中（購入タイミング待ち）'),
        geminiShareUrl: geminiShareUrl.trim(),
        steps,
        fullText: quickSummary.trim(),
        createdAt: new Date().toISOString(),
      });

      setStatusMessage(`✓ 「${nm} (${sym || nm})」を登録しました！`);
      setQuickSymbol('');
      setQuickName('');
      setQuickHorizon('');
      setQuickSummary('');
      setHoldingQuantity('');
      setAverageCost('');
      setHoldingNote('');
      setGeminiShareUrl('');
      setTimeout(() => {
        onSuccess();
      }, 900);
    } catch (err: any) {
      setStatusMessage(`❌ 登録エラー: ${err.message || err}`);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div>
      {/* モード切替 */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        <button
          type="button"
          onClick={() => setRegMode('quick')}
          style={{
            background: regMode === 'quick' ? '#1e293b' : '#f1f5f9',
            color: regMode === 'quick' ? 'white' : '#334155',
            border: 'none',
            borderRadius: 8,
            padding: '8px 16px',
            fontSize: 13,
            fontWeight: 'bold',
            cursor: 'pointer',
          }}
        >
          ⚡ かんたん登録（購入済み・注目銘柄）
        </button>
        <button
          type="button"
          onClick={() => setRegMode('json')}
          style={{
            background: regMode === 'json' ? '#1e293b' : '#f1f5f9',
            color: regMode === 'json' ? 'white' : '#334155',
            border: 'none',
            borderRadius: 8,
            padding: '8px 16px',
            fontSize: 13,
            fontWeight: 'bold',
            cursor: 'pointer',
          }}
        >
          📋 Gemini JSON からシナリオ登録
        </button>
      </div>

      {regMode === 'quick' ? (
        <form
          onSubmit={handleQuickSave}
          style={{ background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}
        >
          <h2 style={{ fontSize: 17, marginTop: 0, marginBottom: 14 }}>⚡ 保有中・注目銘柄のかんたん登録</h2>

          <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' }}>
            <span style={{ fontSize: 13, fontWeight: 'bold' }}>現在の状況:</span>
            <button
              type="button"
              onClick={() => setScenarioStatus('HOLDING')}
              style={{
                background: scenarioStatus === 'HOLDING' ? '#059669' : '#f1f5f9',
                color: scenarioStatus === 'HOLDING' ? 'white' : '#334155',
                border: 'none',
                borderRadius: 999,
                padding: '6px 14px',
                fontSize: 12,
                fontWeight: 'bold',
                cursor: 'pointer',
              }}
            >
              💼 すでに買っている（保有中・買い増し判定）
            </button>
            <button
              type="button"
              onClick={() => setScenarioStatus('WATCH')}
              style={{
                background: scenarioStatus === 'WATCH' ? '#2563eb' : '#f1f5f9',
                color: scenarioStatus === 'WATCH' ? 'white' : '#334155',
                border: 'none',
                borderRadius: 999,
                padding: '6px 14px',
                fontSize: 12,
                fontWeight: 'bold',
                cursor: 'pointer',
              }}
            >
              👀 これから狙う（注目中・Watch）
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 12 }}>
            <div>
              <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>企業名 / テーマ *</label>
              <input
                type="text"
                placeholder="例: Google / SpaceX関連"
                value={quickName}
                onChange={(e) => setQuickName(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>ティッカー / コード *</label>
              <input
                type="text"
                placeholder="例: GOOGL / GO / PRIVATE"
                value={quickSymbol}
                onChange={(e) => setQuickSymbol(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>市場</label>
              <select
                value={quickMarket}
                onChange={(e) => setQuickMarket(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box', background: 'white' }}
              >
                <option value="NASDAQ">NASDAQ</option>
                <option value="NYSE">NYSE</option>
                <option value="TYO">TYO (日本株)</option>
                <option value="OTHER">OTHER (ファンド・非上場等)</option>
              </select>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 12 }}>
            <div>
              <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>保有数量（任意）</label>
              <input
                type="text"
                placeholder="例: 10株 / 100万円分"
                value={holdingQuantity}
                onChange={(e) => setHoldingQuantity(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>平均取得単価（任意）</label>
              <input
                type="text"
                placeholder="例: $280 / ¥3,500"
                value={averageCost}
                onChange={(e) => setAverageCost(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>目標・想定期間（任意）</label>
              <input
                type="text"
                placeholder="例: 2〜3年 / $350目標"
                value={quickHorizon}
                onChange={(e) => setQuickHorizon(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
          </div>

          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>保有・買い増しメモ（任意）</label>
            <input
              type="text"
              placeholder="例: DXYZや投資信託経由で保有中 / AIクラウド成長に期待し押し目で買い増し"
              value={holdingNote}
              onChange={(e) => setHoldingNote(e.target.value)}
              style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
            />
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={{ display: 'block', fontSize: 12, fontWeight: 'bold', marginBottom: 4 }}>投資理由・シナリオ要約（任意）</label>
            <textarea
              rows={3}
              placeholder="AI に踏まえてほしい投資前提・撤退基準など"
              value={quickSummary}
              onChange={(e) => setQuickSummary(e.target.value)}
              style={{ width: '100%', padding: '8px 10px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
            />
          </div>

          <div style={{ textAlign: 'right' }}>
            <button
              type="submit"
              disabled={isSubmitting}
              style={{
                background: isSubmitting ? '#93c5fd' : '#2563eb',
                color: 'white',
                border: 'none',
                borderRadius: 6,
                padding: '10px 24px',
                fontSize: 14,
                fontWeight: 'bold',
                cursor: isSubmitting ? 'default' : 'pointer',
              }}
            >
              {isSubmitting ? '登録中...' : '✓ この状況で登録する'}
            </button>
          </div>

          {statusMessage && (
            <div style={{ marginTop: 14, padding: 10, background: statusMessage.startsWith('✓') ? '#ecfdf5' : '#fef2f2', color: statusMessage.startsWith('✓') ? '#065f46' : '#991b1b', borderRadius: 6, fontWeight: 'bold', fontSize: 14 }}>
              {statusMessage}
            </div>
          )}
        </form>
      ) : (
        <>
      {/* プロンプトコピーエリア */}
      <div style={{ background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: 8, padding: 14, marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <strong style={{ color: '#166534', fontSize: 14 }}>💡 Geminiへの出力指示プロンプト</strong>
          <button
            onClick={copyPromptTemplate}
            style={{
              background: copied ? '#15803d' : '#22c55e',
              color: 'white',
              border: 'none',
              borderRadius: 6,
              padding: '6px 14px',
              cursor: 'pointer',
              fontWeight: 'bold',
              fontSize: 13,
            }}
          >
            {copied ? '✓ コピーしました！' : '📋 指示プロンプトをコピー'}
          </button>
        </div>
        <p style={{ margin: '6px 0 0 0', fontSize: 13, color: '#15803d' }}>
          Geminiとの壁打ちの最後にこの指示を送ることで、貼り付け用のJSONコードブロックが出力されます。
        </p>
      </div>

      {/* 入力フォーム */}
      <div style={{ background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 8, padding: 20, boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}>
        <h2 style={{ fontSize: 17, marginTop: 0, marginBottom: 16 }}>新規シナリオ登録</h2>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div>
            <label style={{ display: 'block', fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>
              1. 当時のGemini会話リンク (共有URL)
            </label>
            <input
              type="url"
              placeholder="https://gemini.google.com/share/..."
              value={geminiShareUrl}
              onChange={(e) => {
                setGeminiShareUrl(e.target.value);
                if (parsedScenario) {
                  setParsedScenario({ ...parsedScenario, geminiShareUrl: e.target.value });
                }
              }}
              style={{ width: '100%', padding: '9px 12px', borderRadius: 6, border: '1px solid #d1d5db', fontSize: 14, boxSizing: 'border-box' }}
            />
          </div>

          <div>
            <label style={{ display: 'block', fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>
              2. Geminiの出力JSONを貼り付け
            </label>
            <textarea
              rows={8}
              placeholder={`\`\`\`json\n{\n  "symbol": "NFLX",\n  "market": "NASDAQ",\n  ...\n}\n\`\`\``}
              value={jsonInput}
              onChange={(e) => handleJsonChange(e.target.value)}
              style={{ width: '100%', padding: 10, borderRadius: 6, border: '1px solid #d1d5db', fontFamily: 'monospace', fontSize: 13, boxSizing: 'border-box' }}
            />
            {parseError && (
              <p style={{ margin: '6px 0 0 0', color: '#dc2626', fontSize: 13 }}>⚠ {parseError}</p>
            )}
          </div>
        </div>

        {/* プレビュー表示 */}
        {parsedScenario && (
          <div style={{ marginTop: 20, background: '#f8fafc', border: '1px solid #cbd5e1', borderRadius: 6, padding: 16 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
              <div style={{ fontSize: 16, fontWeight: 'bold' }}>
                {parsedScenario.name} ({parsedScenario.symbol}:{parsedScenario.market}) — {parsedScenario.targetHorizon}
              </div>
              <a
                href={`https://www.google.com/finance/quote/${encodeURIComponent(parsedScenario.symbol)}:${encodeURIComponent(parsedScenario.market)}`}
                target="_blank"
                rel="noopener noreferrer"
                style={{ color: '#2563eb', fontSize: 13, textDecoration: 'none' }}
              >
                📈 Google Finance ↗
              </a>
            </div>

            <div style={{ fontSize: 13, color: '#475569', marginBottom: 12 }}>{parsedScenario.summary}</div>

            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center', marginBottom: 12, padding: 10, background: 'white', borderRadius: 6, border: '1px solid #e2e8f0' }}>
              <span style={{ fontSize: 12, fontWeight: 'bold', color: '#334155' }}>現在の状況:</span>
              <button
                type="button"
                onClick={() => setScenarioStatus('WATCH')}
                style={{
                  background: scenarioStatus === 'WATCH' ? '#2563eb' : '#f1f5f9',
                  color: scenarioStatus === 'WATCH' ? 'white' : '#334155',
                  border: 'none',
                  borderRadius: 999,
                  padding: '4px 12px',
                  fontSize: 12,
                  fontWeight: 'bold',
                  cursor: 'pointer',
                }}
              >
                👀 注目中（未購入）
              </button>
              <button
                type="button"
                onClick={() => setScenarioStatus('HOLDING')}
                style={{
                  background: scenarioStatus === 'HOLDING' ? '#059669' : '#f1f5f9',
                  color: scenarioStatus === 'HOLDING' ? 'white' : '#334155',
                  border: 'none',
                  borderRadius: 999,
                  padding: '4px 12px',
                  fontSize: 12,
                  fontWeight: 'bold',
                  cursor: 'pointer',
                }}
              >
                💼 保有中（買い増し検討）
              </button>
              <input
                type="text"
                placeholder="保有メモ（例: 第1回購入済 / 取得単価$280 など）"
                value={holdingNote}
                onChange={(e) => setHoldingNote(e.target.value)}
                style={{ flex: '1 1 220px', padding: '5px 10px', borderRadius: 6, border: '1px solid #cbd5e1', fontSize: 12 }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {parsedScenario.steps?.map((step) => (
                <div key={step.step} style={{ background: 'white', padding: 10, borderRadius: 4, border: '1px solid #e2e8f0', fontSize: 13 }}>
                  <strong style={{ color: '#1d4ed8' }}>第{step.step}回 ({step.ratio})</strong> [{step.period} / 通知予定: <b>{step.notifyDate}</b>]
                  <div style={{ color: '#334155', marginTop: 2 }}>狙い: {step.aim}</div>
                </div>
              ))}
            </div>

            <div style={{ marginTop: 16, textAlign: 'right' }}>
              <button
                onClick={handleSave}
                disabled={isSubmitting}
                style={{
                  background: isSubmitting ? '#93c5fd' : '#2563eb',
                  color: 'white',
                  border: 'none',
                  borderRadius: 6,
                  padding: '10px 24px',
                  fontSize: 15,
                  fontWeight: 'bold',
                  cursor: isSubmitting ? 'default' : 'pointer',
                }}
              >
                {isSubmitting ? '登録中...' : '🚀 このシナリオを登録する'}
              </button>
            </div>
          </div>
        )}

        {statusMessage && (
          <div style={{ marginTop: 14, padding: 10, background: statusMessage.startsWith('✓') ? '#ecfdf5' : '#fef2f2', color: statusMessage.startsWith('✓') ? '#065f46' : '#991b1b', borderRadius: 6, fontWeight: 'bold', fontSize: 14 }}>
            {statusMessage}
          </div>
        )}
      </div>
        </>
      )}
    </div>
  );
}
