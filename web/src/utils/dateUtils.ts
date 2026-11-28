export function padZero(n: number): string {
  return n.toString().padStart(2, '0');
}

export function formatDateIso(d: Date): string {
  const year = d.getFullYear();
  const month = padZero(d.getMonth() + 1);
  const day = padZero(d.getDate());
  return `${year}-${month}-${day}`;
}

export function getRecentDays(count: number = 7): string[] {
  const days: string[] = [];
  const today = new Date();
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(today.getDate() - i);
    days.push(formatDateIso(d));
  }
  return days;
}

export function formatDayLabel(iso: string): { monthDay: string; weekday: string; isToday: boolean } {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  const weekdays = ['日', '月', '火', '水', '木', '金', '土'];
  const todayIso = formatDateIso(new Date());
  return {
    monthDay: `${m}/${d}`,
    weekday: weekdays[date.getDay()],
    isToday: iso === todayIso,
  };
}
