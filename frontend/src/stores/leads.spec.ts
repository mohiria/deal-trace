import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { server } from '../test/msw/server'
import { ApiError } from '../api/client'
import {
  SAMPLE_LEAD,
  SAMPLE_POOL_LEAD,
  SAMPLE_PROGRESS,
  addProgressSuccess,
  allLeads,
  claimAlreadyClaimed,
  claimSuccess,
  leadDetail,
  mineLeads,
  poolList,
  progressList,
  releaseSuccess,
  assignSuccess,
  assignAlreadyOwned,
  recallSuccess,
  transferSuccess,
} from '../test/msw/handlers'
import { useLeadsStore } from './leads'

/**
 * leads store 行为（design D2）：加载写态 + 认领/退回/追加进度的跨视图联动 + 错误透传。
 */

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('加载', () => {
  it('loadMyLeads / loadAllLeads / loadPool / loadLead / loadProgress 写入对应 state', async () => {
    server.use(
      mineLeads([SAMPLE_LEAD]),
      allLeads([SAMPLE_LEAD]),
      poolList([SAMPLE_POOL_LEAD]),
      leadDetail(SAMPLE_LEAD),
      progressList([SAMPLE_PROGRESS]),
    )
    const store = useLeadsStore()

    await store.loadMyLeads()
    await store.loadAllLeads()
    await store.loadPool()
    await store.loadLead(100)
    await store.loadProgress(100)

    expect(store.myLeads).toHaveLength(1)
    expect(store.allLeads).toHaveLength(1)
    expect(store.pool[0]?.id).toBe(SAMPLE_POOL_LEAD.id)
    expect(store.currentLead?.id).toBe(SAMPLE_LEAD.id)
    expect(store.progress[0]?.id).toBe(SAMPLE_PROGRESS.id)
  })
})

describe('claim（认领联动）', () => {
  it('成功后从 pool 移除该线索', async () => {
    server.use(poolList([SAMPLE_POOL_LEAD]), claimSuccess())
    const store = useLeadsStore()
    await store.loadPool()
    expect(store.pool).toHaveLength(1)

    await store.claim(SAMPLE_POOL_LEAD.id)

    expect(store.pool.find((l) => l.id === SAMPLE_POOL_LEAD.id)).toBeUndefined()
  })

  it('遇 LEAD_ALREADY_CLAIMED 透传 ApiError 且不动 pool', async () => {
    server.use(poolList([SAMPLE_POOL_LEAD]), claimAlreadyClaimed())
    const store = useLeadsStore()
    await store.loadPool()

    await expect(store.claim(SAMPLE_POOL_LEAD.id)).rejects.toBeInstanceOf(ApiError)
    expect(store.pool).toHaveLength(1)
  })
})

describe('release（退回联动）', () => {
  it('成功后从 myLeads 移除', async () => {
    server.use(mineLeads([SAMPLE_LEAD]), releaseSuccess())
    const store = useLeadsStore()
    await store.loadMyLeads()
    expect(store.myLeads).toHaveLength(1)

    await store.release(SAMPLE_LEAD.id, '客户暂无预算')

    expect(store.myLeads.find((l) => l.id === SAMPLE_LEAD.id)).toBeUndefined()
  })
})

describe('归属动作（assign/recall/transfer 刷新 currentLead）', () => {
  it('assign 成功后刷新 currentLead 归属', async () => {
    const pool = { ...SAMPLE_LEAD, ownerSalesId: null }
    server.use(leadDetail(pool), assignSuccess({ ...SAMPLE_LEAD, ownerSalesId: 2 }))
    const store = useLeadsStore()
    await store.loadLead(100)
    expect(store.currentLead?.ownerSalesId).toBeNull()

    await store.assign(100, 2)

    expect(store.currentLead?.ownerSalesId).toBe(2)
  })

  it('recall 成功后 currentLead 归属清空', async () => {
    server.use(leadDetail(SAMPLE_LEAD), recallSuccess({ ...SAMPLE_LEAD, ownerSalesId: null }))
    const store = useLeadsStore()
    await store.loadLead(100)

    await store.recall(100)

    expect(store.currentLead?.ownerSalesId).toBeNull()
  })

  it('transfer 成功后 currentLead 归属更新为新 Sales', async () => {
    server.use(leadDetail(SAMPLE_LEAD), transferSuccess({ ...SAMPLE_LEAD, ownerSalesId: 3 }))
    const store = useLeadsStore()
    await store.loadLead(100)

    await store.transfer(100, 3)

    expect(store.currentLead?.ownerSalesId).toBe(3)
  })

  it('归属动作收 ApiError 透传不吞', async () => {
    server.use(leadDetail({ ...SAMPLE_LEAD, ownerSalesId: 2 }), assignAlreadyOwned())
    const store = useLeadsStore()
    await store.loadLead(100)

    await expect(store.assign(100, 5)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('addLeadProgress（进度联动）', () => {
  it('成功后新记录置顶并刷新 currentLead.lastTrackedAt', async () => {
    const newer = { ...SAMPLE_PROGRESS, id: 999, content: '二次跟进', trackTime: '2026-05-03T08:00:00' }
    server.use(leadDetail(SAMPLE_LEAD), progressList([SAMPLE_PROGRESS]), addProgressSuccess(newer))
    const store = useLeadsStore()
    await store.loadLead(100)
    await store.loadProgress(100)

    await store.addLeadProgress(100, '电话', '二次跟进')

    expect(store.progress[0]?.id).toBe(999)
    expect(store.currentLead?.lastTrackedAt).toBe(newer.trackTime)
  })
})
