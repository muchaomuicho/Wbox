import { describe, expect, it } from 'vitest';
import { libraryDropAt } from './libraryDrag';

const cards = [
  { id: 'a', left: 0, right: 100, top: 0, bottom: 100 },
  { id: 'b', left: 116, right: 216, top: 0, bottom: 100 },
  { id: 'c', left: 0, right: 100, top: 116, bottom: 216 },
  { id: 'd', left: 116, right: 216, top: 116, bottom: 216 }
];
describe('library grid drop positions', () => {
  it('chooses the insertion side in a two-column row', () => {
    expect(libraryDropAt(cards, 'a', 130, 50, 2)).toEqual({ id: 'b', after: false, axis: 'horizontal' });
    expect(libraryDropAt(cards, 'a', 200, 50, 2)).toEqual({ id: 'b', after: true, axis: 'horizontal' });
  });
  it('finds cards across rows and empty grid gaps', () => {
    expect(libraryDropAt(cards, 'a', 200, 170, 2)).toEqual({ id: 'd', after: true, axis: 'horizontal' });
    expect(libraryDropAt(cards, 'a', 112, 170, 2)).toEqual({ id: 'd', after: false, axis: 'horizontal' });
    expect(libraryDropAt(cards, 'a', 40, 230, 2)).toEqual({ id: 'c', after: false, axis: 'horizontal' });
  });
  it('uses top and bottom when the window contains one column', () => {
    expect(libraryDropAt(cards.filter(card => card.id === 'a' || card.id === 'c'), 'a', 40, 130, 1)).toEqual({ id: 'c', after: false, axis: 'vertical' });
    expect(libraryDropAt(cards.filter(card => card.id === 'a' || card.id === 'c'), 'a', 40, 200, 1)).toEqual({ id: 'c', after: true, axis: 'vertical' });
  });
  it('ignores a self drop and an empty grid', () => {
    expect(libraryDropAt(cards, 'a', 40, 40, 2)).toBeNull();
    expect(libraryDropAt([], 'a', 40, 40, 2)).toBeNull();
  });
});
