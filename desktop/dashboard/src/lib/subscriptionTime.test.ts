import { describe, expect, it } from 'vitest';
import { formatSubscriptionExpiry, formatSubscriptionUpdated, validSubscriptionTimestamp } from './subscriptionTime';

const now = new Date(2026, 10, 14, 12, 0, 0).getTime();
const day = 86_400_000;

describe('subscription expiry display', () => {
  it('uses the local date and rounds partial remaining days upward', () => {
    expect(formatSubscriptionExpiry((now + day + 1000) / 1000, false, now)).toBe('2026/11/15 到期');
    expect(formatSubscriptionExpiry((now + day + 1000) / 1000, true, now)).toBe('2 天后到期');
    expect(formatSubscriptionExpiry((now + 1000) / 1000, true, now)).toBe('1 天后到期');
  });
  it('handles expiration without negative days', () => {
    expect(formatSubscriptionExpiry(now / 1000, true, now)).toBe('已到期');
    expect(formatSubscriptionExpiry((now - day) / 1000, false, now)).toBe('2026/11/13 到期');
    expect(formatSubscriptionExpiry((now - day) / 1000, true, now)).toBe('已到期');
  });
  it('keeps permanent and missing dates readable in either mode', () => {
    for (const relative of [false, true]) {
      expect(formatSubscriptionExpiry(0, relative, now)).toBe('长期有效');
      for (const expire of [null, undefined, NaN, Infinity, -1, 1e20]) {
        expect(formatSubscriptionExpiry(expire, relative, now)).toBe('到期时间未提供');
      }
    }
  });
});

describe('subscription update display', () => {
  it('uses seconds in the complete date and relative minute/hour/day boundaries', () => {
    expect(formatSubscriptionUpdated(now, false, now)).toBe('更新于 2026/11/14 12:00:00');
    expect(formatSubscriptionUpdated(now - 59_999, true, now)).toBe('更新于刚刚');
    expect(formatSubscriptionUpdated(now - 60_000, true, now)).toBe('更新于 1 分钟前');
    expect(formatSubscriptionUpdated(now - 3_599_999, true, now)).toBe('更新于 59 分钟前');
    expect(formatSubscriptionUpdated(now - 3_600_000, true, now)).toBe('更新于 1 小时前');
    expect(formatSubscriptionUpdated(now - day, true, now)).toBe('更新于 1 天前');
    expect(formatSubscriptionUpdated(now - 366 * day, true, now)).toBe('更新于 366 天前');
  });
  it('handles future clock differences and missing timestamps', () => {
    expect(formatSubscriptionUpdated(now + 60_000, true, now)).toBe('更新于 1 分钟后');
    for (const timestamp of [0, NaN, Infinity, 1e20, Date.parse('0001-01-01T00:00:00Z')]) {
      expect(validSubscriptionTimestamp(timestamp)).toBe(false);
      expect(formatSubscriptionUpdated(timestamp, true, now)).toBe('更新时间未提供');
      expect(formatSubscriptionUpdated(timestamp, false, now)).toBe('更新时间未提供');
    }
  });
});
