import { initializeApp } from 'firebase/app';
import { getFirestore } from 'firebase/firestore';
import { getAuth, GoogleAuthProvider } from 'firebase/auth';

// Firebaseプロジェクト (kabu-99c48: hibi-web) の設定
// 環境変数 (.env.local) より読み込み
const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY || '',
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN || 'kabu-99c48.firebaseapp.com',
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID || 'kabu-99c48',
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET || 'kabu-99c48.firebasestorage.app',
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID || '792457595371',
  appId: import.meta.env.VITE_FIREBASE_APP_ID || '1:792457595371:web:d629a52908eebb0d7471ae',
};

export const app = initializeApp(firebaseConfig);
export const db = getFirestore(app);
export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();
// Google Sheets APIの閲覧権限スコープを追加
googleProvider.addScope('https://www.googleapis.com/auth/spreadsheets.readonly');
