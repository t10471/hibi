export interface BuyStep {
  step: number;
  period: string;       // 例: "2026年11月〜12月中旬"
  notifyDate: string;   // 例: "2026-11-01" (YYYY-MM-DD)
  ratio: string;        // 例: "30%"
  aim: string;          // 例: "年末の節税売りで60ドル前後の底固め"
  condition: string;    // 例: "数週間連続で60〜62ドルを割り込まない底固めの動きを確認"
  isNotified?: boolean;
  isDone?: boolean;
}

export interface StockScenario {
  id?: string;
  symbol: string;         // 例: "NFLX", "7203"
  market: string;         // 例: "NASDAQ", "TYO"
  name: string;           // 例: "Netflix"
  targetHorizon: string;  // 例: "85〜100ドル（2〜3年中長期）"
  summary: string;        // 概要
  geminiShareUrl?: string; // Geminiの会話共有リンク
  steps: BuyStep[];
  fullText: string;       // 壁打ち全文
  createdAt?: string;
}

// 筋トレメニュー・種目
export interface WorkoutExercise {
  id: string;
  name: string;          // 例: "ベンチプレス"
  targetMuscle: string;  // 例: "胸"
  targetSets: number;    // 例: 3
  targetReps: string;    // 例: "8-10" または "10"
  targetWeight: number;  // 例: 60.0 (kg)
  restSeconds: number;   // 例: 90 (秒)
  notes?: string;        // 例: "フォーム重視、肩甲骨寄せる"
}

export interface WorkoutMenu {
  id?: string;
  title: string;         // 例: "胸・三頭の日 (Day A)"
  targetMuscle: string;  // 例: "胸・肩・三頭"
  dayOfWeek?: string;    // 例: "火", "水", "木", "土", "日"
  dayOrder?: number;
  exercises: WorkoutExercise[];
  sourceUrl?: string;    // スプレッドシートURL (あれば)
  createdAt?: string;
  updatedAt?: string;
}

// 筋トレ実績セット
export interface WorkoutSetResult {
  setNum: number;
  weight: number;        // kg
  reps: number;          // 回数
  rpe?: number;          // 疲労度 (1〜10)
  isCompleted: boolean;
}

// 筋トレ実績種目
export interface ExerciseLogItem {
  exerciseName: string;
  targetMuscle: string;
  targetWeight: number;
  targetReps: string;
  targetSets: number;
  sets: WorkoutSetResult[];
  notes?: string;
}

// 筋トレ実施ログ
export interface WorkoutLog {
  id?: string;
  menuId?: string;
  menuTitle: string;
  date: string;          // YYYY-MM-DD
  exercises: ExerciseLogItem[];
  durationMinutes?: number;
  caloriesBurned?: number;
  avgHeartRate?: number;
  maxHeartRate?: number;
  userNotes?: string;
  geminiFeedback?: string;
  createdAt?: string;
}

// くすり (服薬管理)
export interface Medication {
  id: string;
  name: string;
  hour: number;        // 0-23
  minute: number;      // 0-59
  takenDates: string[]; // YYYY-MM-DD
  createdAt?: string;
  updatedAt?: string;
}

// きろく (やったこと・経過日数記録)
export interface TrackedEvent {
  id: string;
  name: string;
  dates: string[];     // YYYY-MM-DD (新しい順)
  lastDoneDate?: string | null;
  createdAt?: string;
  updatedAt?: string;
}
