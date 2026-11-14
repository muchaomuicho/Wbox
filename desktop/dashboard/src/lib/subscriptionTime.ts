import { pad2 } from '../api/format';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export function validSubscriptionTimestamp(timestampMs: number): boolean {
  return timestampMs > 0 && Number.isFinite(new Date(timestampMs).getTime());
}

export function formatSubscriptionDate(timestampMs: number, withTime = false): string {
  const date = new Date(timestampMs);
  const day = `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`;
  return withTime ? `${day} ${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}` : day;
}

export function formatSubscriptionExpiry(expire: number | null | undefined, relative: boolean, nowMs: number): string {
  if (expire === 0) return '长期有效';
  if (expire == null || !validSubscriptionTimestamp(expire * 1000)) return '到期时间未提供';
  const timestampMs = expire * 1000;
  if (!relative) return `${formatSubscriptionDate(timestampMs)} 到期`;
  return timestampMs <= nowMs ? '已到期' : `${Math.ceil((timestampMs - nowMs) / DAY)} 天后到期`;
}

export function formatSubscriptionUpdated(timestampMs: number, relative: boolean, nowMs: number): string {
  if (!validSubscriptionTimestamp(timestampMs)) return '更新时间未提供';
  if (!relative) return `更新于 ${formatSubscriptionDate(timestampMs, true)}`;
  const distance = Math.abs(nowMs - timestampMs);
  if (distance < MINUTE) return '更新于刚刚';
  const suffix = timestampMs > nowMs ? '后' : '前';
  if (distance < HOUR) return `更新于 ${Math.floor(distance / MINUTE)} 分钟${suffix}`;
  if (distance < DAY) return `更新于 ${Math.floor(distance / HOUR)} 小时${suffix}`;
  return `更新于 ${Math.floor(distance / DAY)} 天${suffix}`;
}
