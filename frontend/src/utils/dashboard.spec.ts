import { describe, expect, it } from 'vitest'
import { formatLossRate, formatWonAmount } from './dashboard'

describe('formatLossRate（D3：比率 → 百分比，区分空值/零值）', () => {
  it('null（本月无结束事件）→ "--"，而非 "0%"', () => {
    expect(formatLossRate(null)).toBe('--')
  })

  it('0（有结束事件无流失）→ "0%"，而非 "--"', () => {
    expect(formatLossRate(0)).toBe('0%')
  })

  it('比率 0.4 → "40%"（×100）', () => {
    expect(formatLossRate(0.4)).toBe('40%')
  })

  it('比率 0.25 → "25%"', () => {
    expect(formatLossRate(0.25)).toBe('25%')
  })

  it('比率 0.182 → "18.2%"（保留 1 位小数）', () => {
    expect(formatLossRate(0.182)).toBe('18.2%')
  })

  it('整数百分比不带 .0（0.4 不渲染为 "40.0%"）', () => {
    expect(formatLossRate(0.4)).not.toContain('.0')
  })
})

describe('formatWonAmount（D3：人民币千分位，空集归零）', () => {
  it('0 → "¥0"，而非空白/"--"', () => {
    expect(formatWonAmount(0)).toBe('¥0')
  })

  it('150000.5 → 千分位人民币且不浮点失真', () => {
    expect(formatWonAmount(150000.5)).toBe('¥150,000.5')
  })

  it('386000 → "¥386,000"', () => {
    expect(formatWonAmount(386000)).toBe('¥386,000')
  })

  it('整数金额不带多余小数位', () => {
    expect(formatWonAmount(80000)).toBe('¥80,000')
  })
})
