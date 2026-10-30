import { initializeApp } from 'firebase/app';
import { getFirestore } from 'firebase/firestore';
import { getAuth, GoogleAuthProvider } from 'firebase/auth';

// Firebaseプロジェクト (kabu-99c48) の設定
const firebaseConfig = {
  apiKey: "AIzaSyBMWvcYkiPzrtNTLWAizHKCNi8W9Euvkro",
  authDomain: "kabu-99c48.firebaseapp.com",
  projectId: "kabu-99c48",
  storageBucket: "kabu-99c48.firebasestorage.app",
  messagingSenderId: "792457595371",
  appId: "1:792457595371:web:placeholder"
};

export const app = initializeApp(firebaseConfig);
export const db = getFirestore(app);
export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();
// Google Sheets APIの閲覧権限スコープを追加
googleProvider.addScope('https://www.googleapis.com/auth/spreadsheets.readonly');
