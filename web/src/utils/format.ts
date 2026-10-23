/**
 * 数据展示格式化工具
 * 参考 reference-projects/yudao-ui-admin-vben 的 formatNumber.ts 和 date.ts
 */
import dayjs from 'dayjs';

// ============ 时间格式化 ============

/**
 * 格式化日期时间
 * @param time 时间值（ISO 字符串 / 时间戳 / Date）
 * @param format 格式化模板，默认 'YYYY-MM-DD HH:mm:ss'
 * @returns 格式化后的字符串，空值返回 '-'
 */
export function formatDateTime(
  time: Date | number | string | undefined | null,
  format: string = 'YYYY-MM-DD HH:mm:ss',
): string {
  if (!time) return '-';
  const date = dayjs(time);
  return date.isValid() ? date.format(format) : '-';
}

/**
 * 格式化日期（仅日期部分）
 * @param time 时间值
 * @returns 格式化后的字符串，如 '2026-05-22'
 */
export function formatDate(
  time: Date | number | string | undefined | null,
): string {
  return formatDateTime(time, 'YYYY-MM-DD');
}

// ============ 金额格式化 ============

/**
 * 分转元，保留 2 位小数
 * @param price 金额（分）
 * @returns 格式化后的金额字符串（元）
 */
export function fenToYuan(price: number | string | undefined | null): string {
  if (price === null || price === undefined) return '0.00';
  const num = typeof price === 'string' ? Number.parseFloat(price) : price;
  if (Number.isNaN(num)) return '0.00';
  return (num / 100).toFixed(2);
}

/**
 * 元转分
 * @param amount 金额（元）
 * @returns 金额（分）
 */
export function yuanToFen(amount: number | string | undefined | null): number {
  if (amount === null || amount === undefined) return 0;
  const num = typeof amount === 'string' ? Number.parseFloat(amount) : amount;
  if (Number.isNaN(num)) return 0;
  return Math.round(num * 100);
}
