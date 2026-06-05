/**
 * 展示用时间格式化（全站统一到秒）。
 *
 * 后端时间戳为不带时区的 ISO 串（如 `2026-05-31T10:00:00`）。**禁止**用 `new Date(raw)`
 * 解析——无时区会被浏览器按本地/UTC 偏移，跨环境显示漂移。这里只做字符串处理，
 * 原样保留墙钟值，统一呈现为 `YYYY-MM-DD HH:mm:ss`（秒级）。
 *
 * @param raw 后端时间串；空值（null/undefined/空串）返回 `fallback`。
 * @param fallback 空值占位，默认 `—`。
 */
export function formatDateTime(raw: string | null | undefined, fallback = '—'): string {
  if (!raw) return fallback
  return raw.replace('T', ' ').slice(0, 19)
}
