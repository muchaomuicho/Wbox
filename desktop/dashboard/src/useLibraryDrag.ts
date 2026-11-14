import { useEffect, useRef, useState, type DragEvent, type PointerEvent, type MouseEvent } from 'react';
import { call } from '../../wbox/transport';
import { showError } from './app/errorStore';
import { libraryDropAt, type LibraryDrop } from './lib/libraryDrag';

interface ActiveDrag { id: string; grid: HTMLElement; scroll: HTMLElement | null; x: number; y: number }

function scrollParent(element: HTMLElement): HTMLElement | null {
  for (let parent = element.parentElement; parent; parent = parent.parentElement)
    if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY) && parent.scrollHeight > parent.clientHeight) return parent;
  return null;
}

export function useLibraryDrag(kind: 'config' | 'subscription') {
  const active = useRef<ActiveDrag | null>(null);
  const pointer = useRef<{ id: string; allowed: boolean } | null>(null);
  const target = useRef<LibraryDrop | null>(null);
  const suppressClick = useRef(0);
  const saving = useRef(false);
  const [dragging, setDragging] = useState<string | null>(null);
  const [drop, setDrop] = useState<LibraryDrop | null>(null);

  useEffect(() => {
    let frame = 0;
    let last = 0;
    const finish = () => {
      if (!active.current) return;
      active.current = null; target.current = null; pointer.current = null;
      suppressClick.current = Date.now() + 300;
      setDragging(null); setDrop(null);
      cancelAnimationFrame(frame); frame = 0; last = 0;
    };
    const locate = () => {
      const drag = active.current;
      if (!drag) return;
      const bounds = (drag.scroll || drag.grid).getBoundingClientRect();
      let next: LibraryDrop | null = null;
      if (drag.x >= bounds.left && drag.x <= bounds.right && drag.y >= bounds.top && drag.y <= bounds.bottom) {
        const cards = Array.from(drag.grid.querySelectorAll<HTMLElement>(':scope > [data-library-id]')).map(card => ({ id: card.dataset.libraryId!, ...card.getBoundingClientRect().toJSON() }));
        const columns = getComputedStyle(drag.grid).gridTemplateColumns.split(/\s+/).length;
        next = libraryDropAt(cards, drag.id, drag.x, drag.y, columns);
      }
      target.current = next;
      setDrop(previous => previous?.id === next?.id && previous?.after === next?.after && previous?.axis === next?.axis ? previous : next);
    };
    const scrollFrame = (time: number) => {
      frame = 0;
      const drag = active.current;
      if (!drag) return;
      const container = drag.scroll;
      if (container) {
        const bounds = container.getBoundingClientRect(),edge = 64;
        let velocity = 0;
        if (drag.x >= bounds.left && drag.x <= bounds.right) {
          if (drag.y < bounds.top + edge) velocity = -Math.min(1, Math.max(0, (bounds.top + edge - drag.y) / edge));
          else if (drag.y > bounds.bottom - edge) velocity = Math.min(1, Math.max(0, (drag.y - bounds.bottom + edge) / edge));
        }
        if (velocity) { container.scrollTop += velocity * 900 * Math.min(32, last ? time - last : 16) / 1000; locate(); }
      }
      last = time;
      frame = requestAnimationFrame(scrollFrame);
    };
    const over = (event: globalThis.DragEvent) => {
      const drag = active.current;
      if (!drag) return;
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
      drag.x = event.clientX; drag.y = event.clientY;
      locate();
      if (!frame) frame = requestAnimationFrame(scrollFrame);
    };
    const dropped = (event: globalThis.DragEvent) => {
      const drag = active.current, destination = target.current;
      if (!drag) return;
      event.preventDefault();
      finish();
      if (!destination || saving.current) return;
      saving.current = true;
      void call('reorderLibrary', { kind, id: drag.id, anchor: destination.id, after: destination.after }).catch(showError).finally(() => { saving.current = false; });
    };
    const cancel = (event: KeyboardEvent) => { if (event.key === 'Escape') finish(); };
    document.addEventListener('dragover', over);
    document.addEventListener('drop', dropped);
    document.addEventListener('dragend', finish);
    document.addEventListener('keydown', cancel);
    return () => {
      finish();
      document.removeEventListener('dragover', over);
      document.removeEventListener('drop', dropped);
      document.removeEventListener('dragend', finish);
      document.removeEventListener('keydown', cancel);
    };
  }, [kind]);

  const onPointerDownCapture = (event: PointerEvent<HTMLElement>) => {
    const element = event.target as Element;
    const card = element.closest<HTMLElement>('[data-library-id]');
    const control = element.closest('button,input,textarea,select,a,[contenteditable="true"]');
    pointer.current = card ? { id: card.dataset.libraryId!, allowed: event.button === 0 && (!control || control.classList.contains('wbox-row-main')) } : null;
  };
  const onDragStart = (event: DragEvent<HTMLElement>) => {
    const card = (event.target as Element).closest<HTMLElement>('[data-library-id]');
    if (!card || saving.current || pointer.current?.id !== card.dataset.libraryId || !pointer.current?.allowed) { event.preventDefault(); return; }
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('application/x-wbox-library', card.dataset.libraryId!);
    active.current = { id: card.dataset.libraryId!, grid: event.currentTarget, scroll: scrollParent(event.currentTarget), x: event.clientX, y: event.clientY };
    setDragging(card.dataset.libraryId!);
  };
  const onClickCapture = (event: MouseEvent<HTMLElement>) => {
    if (event.detail > 0 && (active.current || Date.now() < suppressClick.current)) { event.preventDefault(); event.stopPropagation(); }
  };
  return {
    gridProps: { onPointerDownCapture, onDragStart, onClickCapture },
    cardProps: (id: string) => ({ draggable: true, 'data-library-id': id, 'data-dragging': dragging === id || undefined, 'data-drop-position': drop?.id === id ? (drop.after ? 'after' : 'before') : undefined, 'data-drop-axis': drop?.id === id ? drop.axis : undefined })
  };
}
