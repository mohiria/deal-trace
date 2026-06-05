import { describe, expect, it } from 'vitest'
import { formatDateTime } from './datetime'

describe('formatDateTime（全站展示时间统一到秒）', () => {
  it('null / undefined / 空串 → 默认占位 "—"', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime(undefined)).toBe('—')
    expect(formatDateTime('')).toBe('—')
  })

  it('空值可用自定义 fallback（如 "暂无" / "尚未跟踪"）', () => {
    expect(formatDateTime(null, '暂无')).toBe('暂无')
    expect(formatDateTime(undefined, '尚未跟踪')).toBe('尚未跟踪')
  })

  it('ISO 串去掉 T、保留到秒', () => {
    expect(formatDateTime('2026-05-31T10:00:00')).toBe('2026-05-31 10:00:00')
  })

  it('带毫秒/时区尾巴被截到秒（不做 Date 解析，原样墙钟值）', () => {
    expect(formatDateTime('2026-05-31T10:00:00.123')).toBe('2026-05-31 10:00:00')
    expect(formatDateTime('2026-05-31T10:00:00Z')).toBe('2026-05-31 10:00:00')
  })

  it('已是空格分隔的串原样到秒', () => {
    expect(formatDateTime('2026-05-31 10:00:00')).toBe('2026-05-31 10:00:00')
  })

  it('仅日期（无时间部分）原样返回', () => {
    expect(formatDateTime('2026-05-31')).toBe('2026-05-31')
  })
})
