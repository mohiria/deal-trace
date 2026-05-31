import { describe, expect, it } from 'vitest'
import type { LeadView, PoolLeadView } from '../api/leads'
import {
  SUGGESTED_CLAIM_LIMIT,
  STALE_TRACK_DAYS,
  staleOwnedLeads,
  suggestedClaims,
} from './workbench'

/**
 * 工作台今日提醒派生纯函数（spec frontend-workbench R「今日提醒/待办」/ design D3）。
 * 仅从既有只读数据派生，无副作用、不改写入参；独立于四项看板指标口径。
 */

function poolLead(id: number): PoolLeadView {
  return {
    id,
    customerId: id,
    customerName: `公海客户${id}`,
    customerUsci: null,
    businessYear: 2026,
    businessType: 'BIM咨询',
    contactName: null,
    contactPhone: '139****0000',
    leadSource: null,
    stage: '初步沟通',
    lastTrackedAt: null,
    createdAt: '2026-05-01T09:00:00',
  }
}

function ownedLead(id: number, lastTrackedAt: string | null, stage = '初步沟通'): LeadView {
  return {
    id,
    customerId: id,
    customerName: `名下客户${id}`,
    customerUsci: null,
    businessYear: 2026,
    businessType: 'BIM咨询',
    contactName: null,
    contactPhone: null,
    leadSource: null,
    ownerSalesId: 2,
    ownerSalesName: '林雨',
    stage,
    lastTrackedAt,
    loseReason: null,
    loseNote: null,
    createdAt: '2026-04-01T09:00:00',
    wonAt: null,
    lostAt: null,
  }
}

const NOW = new Date('2026-05-30T12:00:00')

describe('suggestedClaims（建议认领：取公海前 N 条）', () => {
  it('多于 N 条时仅取前 N 条', () => {
    const pool = [1, 2, 3, 4, 5, 6, 7].map(poolLead)
    expect(suggestedClaims(pool, 5).map((l) => l.id)).toEqual([1, 2, 3, 4, 5])
  })

  it('少于 N 条时全取', () => {
    const pool = [1, 2].map(poolLead)
    expect(suggestedClaims(pool, 5).map((l) => l.id)).toEqual([1, 2])
  })

  it('limit 为 0 时返回空', () => {
    expect(suggestedClaims([1, 2].map(poolLead), 0)).toEqual([])
  })

  it('默认 limit 为 SUGGESTED_CLAIM_LIMIT', () => {
    const pool = Array.from({ length: SUGGESTED_CLAIM_LIMIT + 3 }, (_, i) => poolLead(i + 1))
    expect(suggestedClaims(pool)).toHaveLength(SUGGESTED_CLAIM_LIMIT)
  })

  it('不改写入参数组（无副作用）', () => {
    const pool = [1, 2, 3].map(poolLead)
    const before = pool.slice()
    suggestedClaims(pool, 2)
    expect(pool).toEqual(before)
  })
})

describe('staleOwnedLeads（长期未跟踪：空或早于阈值）', () => {
  it('lastTrackedAt 为空（尚未跟踪）计入', () => {
    const rows = [ownedLead(1, null)]
    expect(staleOwnedLeads(rows, NOW, STALE_TRACK_DAYS).map((l) => l.id)).toEqual([1])
  })

  it('最后跟踪早于阈值天数计入', () => {
    // NOW=05-30，阈值 7 天 → 早于 05-23 的计入
    const rows = [ownedLead(1, '2026-05-10T09:00:00')]
    expect(staleOwnedLeads(rows, NOW, STALE_TRACK_DAYS).map((l) => l.id)).toEqual([1])
  })

  it('最后跟踪在阈值内不计入', () => {
    const rows = [ownedLead(1, '2026-05-29T09:00:00')]
    expect(staleOwnedLeads(rows, NOW, STALE_TRACK_DAYS)).toEqual([])
  })

  it('已结束线索即使久未跟踪也排除', () => {
    const rows = [ownedLead(1, null, '已赢单'), ownedLead(2, null, '已流失')]
    expect(staleOwnedLeads(rows, NOW, STALE_TRACK_DAYS)).toEqual([])
  })

  it('混合集合仅返回未结束且超阈值/未跟踪的', () => {
    const rows = [
      ownedLead(1, null), // 未跟踪 → 计入
      ownedLead(2, '2026-05-10T09:00:00'), // 超阈值 → 计入
      ownedLead(3, '2026-05-29T09:00:00'), // 阈值内 → 否
      ownedLead(4, null, '已赢单'), // 已结束 → 否
    ]
    expect(staleOwnedLeads(rows, NOW, STALE_TRACK_DAYS).map((l) => l.id)).toEqual([1, 2])
  })

  it('不改写入参数组（无副作用）', () => {
    const rows = [ownedLead(1, null), ownedLead(2, '2026-05-29T09:00:00')]
    const before = rows.slice()
    staleOwnedLeads(rows, NOW, STALE_TRACK_DAYS)
    expect(rows).toEqual(before)
  })
})
