import { WorkoutExercise, WorkoutMenu } from '../types';

export interface ParsedSheetResult {
  menus: WorkoutMenu[];
  warnings: string[];
}

export function parseSpreadsheetText(
  text: string,
  defaultTitle: string = '筋トレメニュー'
): ParsedSheetResult {
  const warnings: string[] = [];
  if (!text || !text.trim()) {
    return { menus: [], warnings: ['入力データが空です。'] };
  }

  // Split into lines
  const lines = text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);

  if (lines.length === 0) {
    return { menus: [], warnings: ['有効な行が見つかりませんでした。'] };
  }

  // Determine separator: tab or comma
  const sample = lines[0];
  const isTab = sample.includes('\t');
  const separator = isTab ? '\t' : ',';

  // Parse rows
  const rows = lines.map((line) => {
    if (separator === '\t') {
      return line.split('\t').map((c) => c.trim().replace(/^["']|["']$/g, ''));
    } else {
      // Basic CSV split
      const tokens: string[] = [];
      let inQuotes = false;
      let token = '';
      for (let i = 0; i < line.length; i++) {
        const char = line[i];
        if (char === '"' || char === "'") {
          inQuotes = !inQuotes;
        } else if (char === ',' && !inQuotes) {
          tokens.push(token.trim().replace(/^["']|["']$/g, ''));
          token = '';
        } else {
          token += char;
        }
      }
      tokens.push(token.trim().replace(/^["']|["']$/g, ''));
      return tokens;
    }
  });

  // Check if first row is header
  const headerCandidates = rows[0].map((c) => c.toLowerCase());
  const headerKeywords = [
    '種目',
    'メニュー',
    '部位',
    '重量',
    '回数',
    'レップ',
    'セット',
    '休憩',
    'メモ',
    'exercise',
    'muscle',
    'weight',
    'reps',
    'sets',
    'rest',
    'day',
    '曜日',
  ];

  const isHeaderRow = headerCandidates.some((c) =>
    headerKeywords.some((kw) => c.includes(kw))
  );

  let colMap = {
    menu: -1,
    muscle: -1,
    name: -1,
    weight: -1,
    reps: -1,
    sets: -1,
    rest: -1,
    notes: -1,
  };

  let dataRows = rows;

  if (isHeaderRow) {
    headerCandidates.forEach((c, idx) => {
      if (c.includes('メニュー') || c.includes('day') || c.includes('曜日') || c.includes('グループ')) {
        colMap.menu = idx;
      } else if (c.includes('種目') || c.includes('エクササイズ') || c.includes('name') || c.includes('exercise')) {
        colMap.name = idx;
      } else if (c.includes('部位') || c.includes('ターゲット') || c.includes('muscle') || c.includes('target')) {
        colMap.muscle = idx;
      } else if (c.includes('重量') || c.includes('負荷') || c.includes('weight') || c.includes('kg')) {
        colMap.weight = idx;
      } else if (c.includes('回数') || c.includes('レップ') || c.includes('reps') || c.includes('rep')) {
        colMap.reps = idx;
      } else if (c.includes('セット') || c.includes('sets') || c.includes('set')) {
        colMap.sets = idx;
      } else if (c.includes('休憩') || c.includes('インターバル') || c.includes('rest')) {
        colMap.rest = idx;
      } else if (c.includes('メモ') || c.includes('備考') || c.includes('note')) {
        colMap.notes = idx;
      }
    });
    dataRows = rows.slice(1);
  } else {
    // Default column order heuristic:
    // [種目名, 重量, レップ数, セット数, 休憩秒, メモ] or [部位, 種目名, 重量, レップ数, セット数]
    colMap = {
      menu: -1,
      muscle: rows[0].length >= 5 ? 0 : -1,
      name: rows[0].length >= 5 ? 1 : 0,
      weight: rows[0].length >= 5 ? 2 : 1,
      reps: rows[0].length >= 5 ? 3 : 2,
      sets: rows[0].length >= 5 ? 4 : 3,
      rest: rows[0].length >= 6 ? 5 : -1,
      notes: rows[0].length >= 7 ? 6 : -1,
    };
  }

  // If no name column found, fallback to first non-empty text column
  if (colMap.name === -1) {
    colMap.name = 0;
  }

  // Group by Menu Name
  const menuMap = new Map<string, WorkoutExercise[]>();

  dataRows.forEach((r, rowIdx) => {
    const rawName = colMap.name >= 0 && r[colMap.name] ? r[colMap.name] : '';
    if (!rawName) return; // Skip empty rows

    const menuTitle =
      colMap.menu >= 0 && r[colMap.menu] ? r[colMap.menu].trim() : defaultTitle;

    const muscle =
      colMap.muscle >= 0 && r[colMap.muscle] ? r[colMap.muscle].trim() : '全身';

    // Parse weight
    let weight = 0;
    if (colMap.weight >= 0 && r[colMap.weight]) {
      const match = r[colMap.weight].replace(/,/g, '').match(/[\d.]+/);
      if (match) weight = parseFloat(match[0]) || 0;
    }

    // Parse reps
    let reps = '10';
    if (colMap.reps >= 0 && r[colMap.reps]) {
      reps = r[colMap.reps].replace(/[回reps]/gi, '').trim() || '10';
    }

    // Parse sets
    let sets = 3;
    if (colMap.sets >= 0 && r[colMap.sets]) {
      const match = r[colMap.sets].match(/\d+/);
      if (match) sets = parseInt(match[0], 10) || 3;
    }

    // Parse rest
    let rest = 90;
    if (colMap.rest >= 0 && r[colMap.rest]) {
      const match = r[colMap.rest].match(/\d+/);
      if (match) rest = parseInt(match[0], 10) || 90;
    }

    // Parse notes
    const notes =
      colMap.notes >= 0 && r[colMap.notes] ? r[colMap.notes].trim() : '';

    const exercise: WorkoutExercise = {
      id: `ex_${Date.now()}_${rowIdx}_${Math.random().toString(36).substring(2, 6)}`,
      name: rawName,
      targetMuscle: muscle,
      targetWeight: weight,
      targetReps: reps,
      targetSets: sets,
      restSeconds: rest,
      notes,
    };

    if (!menuMap.has(menuTitle)) {
      menuMap.set(menuTitle, []);
    }
    menuMap.get(menuTitle)!.push(exercise);
  });

  const menus: WorkoutMenu[] = [];
  menuMap.forEach((exercises, title) => {
    // Determine overall target muscle from exercises
    const muscles = Array.from(new Set(exercises.map((e) => e.targetMuscle).filter(Boolean)));
    const targetMuscle = muscles.length > 0 ? muscles.join('・') : '全身';

    menus.push({
      title,
      targetMuscle,
      exercises,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    });
  });

  if (menus.length === 0) {
    warnings.push('種目データを認識できませんでした。行や列の区切りを確認してください。');
  }

  return { menus, warnings };
}

/**
 * Converts a Google Sheets URL into its CSV export URL.
 * e.g. https://docs.google.com/spreadsheets/d/SPREADSHEET_ID/edit#gid=GID
 *  -> https://docs.google.com/spreadsheets/d/SPREADSHEET_ID/export?format=csv&gid=GID
 */
export function convertGoogleSheetUrlToCsv(url: string): string | null {
  const match = url.match(/\/spreadsheets\/d\/([a-zA-Z0-9-_]+)/);
  if (!match) return null;

  const sheetId = match[1];
  let gid = '0';
  const gidMatch = url.match(/[#&?]gid=([0-9]+)/);
  if (gidMatch) {
    gid = gidMatch[1];
  }

  return `https://docs.google.com/spreadsheets/d/${sheetId}/export?format=csv&gid=${gid}`;
}
