export type Bounds = { x: number; y: number; width: number; height: number };

/** Keep the root panel beside the icon and reserve room for leftward cascades. */
export function trayPlacement(anchor: Bounds, area: Bounds) {
  const width = Math.min(832, area.width);
  const height = Math.min(480, area.height);
  const padding = Math.min(40, Math.max(0, (width - 260) / 2));
  const gap = 8;
  const rootWidth = Math.min(260, width - padding * 2);
  const centerY = anchor.y + anchor.height / 2;
  const clamp = (value: number, low: number, high: number) => Math.max(low, Math.min(high, value));
  // Prefer the left even when there is more unused space on the right. Only a
  // left-edge taskbar/icon needs a rightward fallback to keep the root visible.
  const side = anchor.x - gap - rootWidth >= area.x + padding ? 'left' : 'right';
  const desiredX = side === 'left'
    ? anchor.x - gap + padding - width
    : anchor.x + anchor.width + gap - padding;
  const align = centerY >= area.y + area.height - height / 2 ? 'bottom'
    : centerY <= area.y + height / 2 ? 'top' : 'center';
  return {
    x: Math.round(clamp(desiredX, area.x, area.x + area.width - width)),
    y: Math.round(clamp(centerY - height / 2, area.y, area.y + area.height - height)),
    width,
    height,
    side,
    align,
  };
}
