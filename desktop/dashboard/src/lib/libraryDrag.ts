export interface LibraryCardRect { id: string; left: number; right: number; top: number; bottom: number }
export interface LibraryDrop { id: string; after: boolean; axis: 'horizontal' | 'vertical' }

export function libraryDropAt(cards: LibraryCardRect[], source: string, x: number, y: number, columns: number): LibraryDrop | null {
  let nearest: LibraryCardRect | undefined;
  let distance = Infinity;
  for (const card of cards) {
    const dx = Math.max(card.left - x, 0, x - card.right);
    const dy = Math.max(card.top - y, 0, y - card.bottom);
    const score = dx * dx + dy * dy;
    if (score < distance) { nearest = card; distance = score; }
  }
  if (!nearest || nearest.id === source) return null;
  const axis = columns > 1 ? 'horizontal' : 'vertical';
  return { id: nearest.id, axis, after: axis === 'horizontal' ? x > (nearest.left + nearest.right) / 2 : y > (nearest.top + nearest.bottom) / 2 };
}
