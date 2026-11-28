import { useEffect, useMemo, useState } from 'react';
import { collection, onSnapshot, orderBy, query } from 'firebase/firestore';
import { db } from '../firebase';
import { StockScenario } from '../types';
import { effectiveStatus } from '../utils/verdictMeta';

/**
 * Real-time subscription to Firestore `stock_scenarios` with derived symbol sets
 * shared across Scenario list and AI Reports pages.
 */
export function useStockScenarios() {
  const [scenarios, setScenarios] = useState<StockScenario[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const q = query(collection(db, 'stock_scenarios'), orderBy('createdAt', 'desc'));
    const unsubscribe = onSnapshot(
      q,
      (snapshot) => {
        const items: StockScenario[] = snapshot.docs.map((docSnap) => ({
          ...(docSnap.data() as StockScenario),
          id: docSnap.id,
        }));
        setScenarios(items);
        setLoading(false);
      },
      (error) => {
        console.error('Error fetching scenarios:', error);
        setLoading(false);
      }
    );
    return () => unsubscribe();
  }, []);

  const symbolSets = useMemo(() => {
    const activeSymbols = new Set<string>();
    const holdingSymbols = new Set<string>();
    const watchSymbols = new Set<string>();
    for (const sc of scenarios) {
      if (!sc.symbol) continue;
      const sym = sc.symbol.toUpperCase();
      const st = effectiveStatus(sc);
      if (st !== 'ARCHIVED') activeSymbols.add(sym);
      if (st === 'HOLDING') holdingSymbols.add(sym);
      else if (st === 'WATCH') watchSymbols.add(sym);
    }
    return { activeSymbols, holdingSymbols, watchSymbols };
  }, [scenarios]);

  return { scenarios, loading, ...symbolSets };
}
