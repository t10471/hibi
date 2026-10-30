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
        geminiShareUrl,
      });
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
      geminiShareUrl,
      createdAt: new Date().toISOString(),
    };
    try {
      setStatusMessage('登録中...');
      await addDoc(collection(db, 'stock_scenarios'), finalData);
      setStatusMessage(`✓ 「${finalData.name} (${finalData.symbol})」のシナリオを登録しました！`);
      setJsonInput('');
      setGeminiShareUrl('');
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

  return (
    <div>
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
                href={`https://www.google.com/finance/quote/${parsedScenario.symbol}:${parsedScenario.market}`}
                target="_blank"
                rel="noreferrer"
                style={{ color: '#2563eb', fontSize: 13, textDecoration: 'none' }}
              >
                📈 Google Finance ↗
              </a>
            </div>

            <div style={{ fontSize: 13, color: '#475569', marginBottom: 12 }}>{parsedScenario.summary}</div>

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
    </div>
  );
}
