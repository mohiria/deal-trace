import { describe, expect, it } from 'vitest'
import {
  ACTIVE_STAGES,
  LEAD_STAGE,
  formatAmount,
  isClosed,
  isValidAmount,
  isValidContactPhone,
} from './lead'

describe('isClosed（D4）', () => {
  it('已赢单 / 已流失为只读', () => {
    expect(isClosed(LEAD_STAGE.WON)).toBe(true)
    expect(isClosed(LEAD_STAGE.LOST)).toBe(true)
  })

  it('非结束阶段非只读', () => {
    for (const stage of ACTIVE_STAGES) {
      expect(isClosed(stage)).toBe(false)
    }
  })

  it('null / undefined 视为非只读', () => {
    expect(isClosed(null)).toBe(false)
    expect(isClosed(undefined)).toBe(false)
  })
})

describe('isValidAmount（D6 / PRD §7.11.1.3）', () => {
  it('合法：正数、至多两位小数', () => {
    expect(isValidAmount('1')).toBe(true)
    expect(isValidAmount('1000')).toBe(true)
    expect(isValidAmount('1000.5')).toBe(true)
    expect(isValidAmount('1000.50')).toBe(true)
    expect(isValidAmount(' 99.99 ')).toBe(true)
  })

  it('非法：0 / 负 / 三位小数 / 空 / 非数字', () => {
    expect(isValidAmount('0')).toBe(false)
    expect(isValidAmount('0.00')).toBe(false)
    expect(isValidAmount('-5')).toBe(false)
    expect(isValidAmount('1000.555')).toBe(false)
    expect(isValidAmount('')).toBe(false)
    expect(isValidAmount('   ')).toBe(false)
    expect(isValidAmount('abc')).toBe(false)
    expect(isValidAmount(null)).toBe(false)
    expect(isValidAmount('1,000')).toBe(false)
  })
})

describe('formatAmount（PRD §7.11.1.4 千分位）', () => {
  it('整数千分位', () => {
    expect(formatAmount('1000')).toBe('1,000')
    expect(formatAmount('1234567')).toBe('1,234,567')
    expect(formatAmount('999')).toBe('999')
  })

  it('保留小数位', () => {
    expect(formatAmount('1000.5')).toBe('1,000.5')
    expect(formatAmount('1234567.89')).toBe('1,234,567.89')
  })

  it('空返回空；非法原样返回', () => {
    expect(formatAmount('')).toBe('')
    expect(formatAmount(null)).toBe('')
    expect(formatAmount('abc')).toBe('abc')
  })
})

describe('isValidContactPhone（D7 / lead spec R3）', () => {
  it('合法 11 位手机号', () => {
    expect(isValidContactPhone('13812345678')).toBe(true)
    expect(isValidContactPhone('19987654321')).toBe(true)
    expect(isValidContactPhone(' 13812345678 ')).toBe(true)
  })

  it('非法手机号：首位非 1 / 第二位非 3-9 / 长度错', () => {
    expect(isValidContactPhone('23812345678')).toBe(false)
    expect(isValidContactPhone('12812345678')).toBe(false)
    expect(isValidContactPhone('1381234567')).toBe(false)
    expect(isValidContactPhone('138123456789')).toBe(false)
  })

  it('合法座机：含区号 / 含分机 / 无区号', () => {
    expect(isValidContactPhone('010-12345678')).toBe(true)
    expect(isValidContactPhone('0571-12345678')).toBe(true)
    expect(isValidContactPhone('0571-12345678-123')).toBe(true)
    expect(isValidContactPhone('12345678')).toBe(true)
  })

  it('非法：空 / 字母 / 太短 / 海外格式', () => {
    expect(isValidContactPhone('')).toBe(false)
    expect(isValidContactPhone('   ')).toBe(false)
    expect(isValidContactPhone('abc')).toBe(false)
    expect(isValidContactPhone('123')).toBe(false)
    expect(isValidContactPhone('+1-555-1234')).toBe(false)
    expect(isValidContactPhone(null)).toBe(false)
  })
})
