export interface SheetTabInfo {
  sheetId: number;
  title: string;
  index: number;
}

export interface FetchedSpreadsheetData {
  spreadsheetId: string;
  spreadsheetTitle: string;
  tabs: SheetTabInfo[];
  currentTab: SheetTabInfo;
  values: string[][];
}

/**
 * Extracts spreadsheetId and gid from a Google Sheets URL.
 */
export function extractSpreadsheetDetails(url: string): {
  spreadsheetId: string | null;
  gid: string | null;
} {
  const idMatch = url.match(/\/spreadsheets\/d\/([a-zA-Z0-9-_]+)/);
  const spreadsheetId = idMatch ? idMatch[1] : null;

  const gidMatch = url.match(/[#&?]gid=([0-9]+)/);
  const gid = gidMatch ? gidMatch[1] : null;

  return { spreadsheetId, gid };
}

/**
 * Fetches Google Sheet metadata and rows using Google Sheets API v4 with OAuth token.
 */
export async function fetchGoogleSheetWithApi(
  accessToken: string,
  spreadsheetUrlOrId: string,
  targetGid?: string | null
): Promise<FetchedSpreadsheetData> {
  const { spreadsheetId: parsedId, gid: parsedGid } = extractSpreadsheetDetails(spreadsheetUrlOrId);
  const spreadsheetId = parsedId || spreadsheetUrlOrId.trim();
  const gidToFind = targetGid || parsedGid;

  if (!spreadsheetId) {
    throw new Error('スプレッドシートIDが見つかりませんでした。URLを確認してください。');
  }

  const cleanToken = accessToken.trim();
  if (!cleanToken) {
    throw new Error('Googleアクセストークンがありません。Googleアカウント連携を行ってください。');
  }

  // 1. Fetch spreadsheet metadata (properties & sheet tabs)
  const metaUrl = `https://sheets.googleapis.com/v4/spreadsheets/${spreadsheetId}?fields=properties.title,sheets.properties`;
  const metaRes = await fetch(metaUrl, {
    headers: {
      Authorization: `Bearer ${cleanToken}`,
      Accept: 'application/json',
    },
  });

  if (!metaRes.ok) {
    const errorJson = await metaRes.json().catch(() => null);
    const message = errorJson?.error?.message || (await metaRes.text().catch(() => ''));
    if (metaRes.status === 401 || metaRes.status === 403) {
      throw new Error(
        `Google認証エラー (${metaRes.status}): スプレッドシートの閲覧権限がないか、トークンの有効期限が切れています。再連携してください。詳細: ${message}`
      );
    }
    throw new Error(`Google Sheets APIエラー (${metaRes.status}): ${message}`);
  }

  const metaData = await metaRes.json();
  const spreadsheetTitle = metaData.properties?.title || 'スプレッドシート';
  const sheets = metaData.sheets || [];

  if (sheets.length === 0) {
    throw new Error('スプレッドシート内にシートが見つかりませんでした。');
  }

  const tabs: SheetTabInfo[] = sheets.map((s: any) => ({
    sheetId: s.properties?.sheetId ?? 0,
    title: s.properties?.title || 'シート',
    index: s.properties?.index ?? 0,
  }));

  // Match target tab by gid, or default to the first tab
  let targetTab = tabs[0];
  if (gidToFind) {
    const matched = tabs.find((t) => t.sheetId.toString() === gidToFind.toString());
    if (matched) {
      targetTab = matched;
    }
  }

  // 2. Fetch cell values for the target tab
  // Range: 'SheetTitle'!A1:Z300
  const encodedTitle = encodeURIComponent(targetTab.title);
  const valuesUrl = `https://sheets.googleapis.com/v4/spreadsheets/${spreadsheetId}/values/'${encodedTitle}'?valueRenderOption=FORMATTED_VALUE`;

  const valuesRes = await fetch(valuesUrl, {
    headers: {
      Authorization: `Bearer ${cleanToken}`,
      Accept: 'application/json',
    },
  });

  if (!valuesRes.ok) {
    const errorJson = await valuesRes.json().catch(() => null);
    const message = errorJson?.error?.message || (await valuesRes.text().catch(() => ''));
    throw new Error(`シートデータの取得エラー (${valuesRes.status}): ${message}`);
  }

  const valuesData = await valuesRes.json();
  const values: string[][] = valuesData.values || [];

  return {
    spreadsheetId,
    spreadsheetTitle,
    tabs,
    currentTab: targetTab,
    values,
  };
}
