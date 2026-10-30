import { ExerciseLogItem } from '../types';

export interface GeminiCoachResponse {
  answer: string;
  suggestions: string[];
}

export async function askGeminiWorkoutCoach(
  apiKey: string,
  menuTitle: string,
  targetMuscle: string,
  exerciseLogs: ExerciseLogItem[],
  userNotes: string,
  followUpQuestion?: string,
  history: { role: 'user' | 'model'; text: string }[] = []
): Promise<GeminiCoachResponse> {
  const cleanKey = apiKey.trim();
  if (!cleanKey) {
    throw new Error('Gemini APIキーが設定されていません。');
  }

  // Construct workout summary text
  const exercisesText = exerciseLogs
    .map((ex, i) => {
      const targetStr = `【目標】 ${ex.targetWeight}kg × ${ex.targetReps}回 × ${ex.targetSets}セット`;
      const setsStr = ex.sets
        .map(
          (s) =>
            `  セット${s.setNum}: ${s.weight}kg × ${s.reps}回 (RPE: ${s.rpe ?? '未指定'})${
              s.isCompleted ? ' [完遂]' : ' [未達]'
            }`
        )
        .join('\n');
      return `${i + 1}. ${ex.exerciseName} (${ex.targetMuscle})\n${targetStr}\n${setsStr}${
        ex.notes ? `\n  メモ: ${ex.notes}` : ''
      }`;
    })
    .join('\n\n');

  const systemInstruction = `あなたはプロの筋トレコーチ兼スポーツ科学エキスパートです。
ユーザーが実施した筋トレの実績データを計画メニューと比較分析し、効果的なフィードバックとアドバイスを提供してください。

【出力構成】
1. **本日の総評・達成度評価**:
   - 計画に対する総ボリューム・強度の達成度を評価（ポジティブかつ客観的に）。
2. **漸進性過負荷（プログレッシブ・オーバーロード）診断**:
   - RPE（主観的運動強度: 1-10）と完遂状況を踏まえ、次回トレーニング時の推奨重量・レップ数を種目ごとに具体的に提示。
   - 例: 「ベンチプレスはRPE 8で完遂できているため、次回は+2.5kg（62.5kg）で6〜8レップに挑戦推奨」
3. **疲労度・フォーム・リカバリーアドバイス**:
   - 休息期間の目安、推奨する栄養補給（プロテイン/タンパク質量目安、炭水化物、水分）、疲労部位のケアや怪我予防。

【必須ルール】
- 親しみやすく、モチベーションが湧くプロのコーチ口調で回答してください。
- 回答の末尾には必ず区切り線 \`---SUGGESTIONS---\` を配置し、ユーザーが次に尋ねたいと予想される質問文を2〜3個、改行区切りで出力してください。
例:
---SUGGESTIONS---
次回ベンチプレスで重量を上げる際のウォーミングアップ重量配分を教えて
今回の筋肉痛を早く回復させるストレッチや食事メニューは？
背中の種目でラットプルダウンの代わりに効果的な種目はある？`;

  let userPrompt = '';
  if (followUpQuestion) {
    userPrompt = followUpQuestion;
  } else {
    userPrompt = `【本日のトレーニング実績】
■ メニュー名: ${menuTitle} (${targetMuscle})
■ 実施日: ${new Date().toLocaleDateString('ja-JP')}
■ 実績詳細:
${exercisesText}

■ 実施時の体感メモ・自己評価:
${userNotes ? userNotes : '特になし'}

上記の実績をもとに、本日のトレーニングの達成度評価、次回への漸進性過負荷の推奨設定、疲労回復・栄養アドバイスをお願いします。`;
  }

  const contents = [
    ...history.map((h) => ({
      role: h.role,
      parts: [{ text: h.text }],
    })),
    {
      role: 'user',
      parts: [{ text: userPrompt }],
    },
  ];

  const payload = {
    contents,
    systemInstruction: {
      parts: [{ text: systemInstruction }],
    },
    generationConfig: {
      temperature: 0.3,
      maxOutputTokens: 3000,
    },
  };

  const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${cleanKey}`;

  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!res.ok) {
    const errText = await res.text();
    // If 2.5-flash not supported on older API keys, fallback to gemini-flash-latest
    if (res.status === 404) {
      const fallbackUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=${cleanKey}`;
      const fallbackRes = await fetch(fallbackUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!fallbackRes.ok) {
        throw new Error(`Gemini APIエラー (${fallbackRes.status}): ${await fallbackRes.text()}`);
      }
      const data = await fallbackRes.json();
      return parseGeminiResponse(data);
    }
    throw new Error(`Gemini APIエラー (${res.status}): ${errText}`);
  }

  const data = await res.json();
  return parseGeminiResponse(data);
}

function parseGeminiResponse(data: any): GeminiCoachResponse {
  const candidate = data.candidates?.[0];
  const parts = candidate?.content?.parts;
  const rawText = parts?.map((p: any) => p.text || '').join('') || '';

  if (!rawText) {
    return {
      answer: 'Geminiからの回答を取得できませんでした。',
      suggestions: [],
    };
  }

  const separator = '---SUGGESTIONS---';
  if (rawText.includes(separator)) {
    const [answerPart, suggestionPart] = rawText.split(separator);
    const suggestions = (suggestionPart || '')
      .split(/\r?\n/)
      .map((s: string) => s.trim().replace(/^[-*•0-9.]+\s*/, ''))
      .filter((s: string) => s.length > 0);
    return {
      answer: answerPart.trim(),
      suggestions,
    };
  }

  return {
    answer: rawText.trim(),
    suggestions: [],
  };
}
