import { afterEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import {
  SAMPLE_LEAD,
  SAMPLE_POOL_LEAD,
  SAMPLE_PROGRESS,
  mineLeads,
  allLeads,
  leadDetail,
  poolList,
  claimSuccess,
  claimAlreadyClaimed,
  progressList,
  addProgressSuccess,
  stageSuccess,
  winSuccess,
  loseSuccess,
  releaseSuccess,
} from '../test/msw/handlers'
import { ApiError } from './client'
import {
  fetchMyLeads,
  fetchAllLeads,
  fetchLead,
  fetchPool,
  claimLead,
  releaseLead,
  changeStage,
  winLead,
  loseLead,
  fetchProgress,
  addProgress,
  createLead,
  duplicateCheck,
} from './leads'

describe('leads API（D1）', () => {
  afterEach(() => server.resetHandlers())

  it('fetchMyLeads 命中 GET /leads/mine 并 unwrap 列表', async () => {
    server.use(mineLeads([SAMPLE_LEAD]))
    const rows = await fetchMyLeads()
    expect(rows).toHaveLength(1)
    expect(rows[0]?.id).toBe(SAMPLE_LEAD.id)
  })

  it('fetchAllLeads 命中 GET /leads', async () => {
    server.use(allLeads([SAMPLE_LEAD]))
    const rows = await fetchAllLeads()
    expect(rows[0]?.id).toBe(SAMPLE_LEAD.id)
  })

  it('fetchLead 命中 GET /leads/{id}', async () => {
    server.use(leadDetail(SAMPLE_LEAD))
    const lead = await fetchLead(100)
    expect(lead.customerName).toBe(SAMPLE_LEAD.customerName)
  })

  it('fetchPool 命中 GET /leads/pool', async () => {
    server.use(poolList([SAMPLE_POOL_LEAD]))
    const rows = await fetchPool()
    expect(rows[0]?.contactPhone).toBe(SAMPLE_POOL_LEAD.contactPhone)
  })

  it('claimLead 命中 POST /leads/{id}/claim', async () => {
    server.use(claimSuccess())
    const lead = await claimLead(200)
    expect(lead.ownerSalesId).toBe(2)
  })

  it('claimLead 遇冲突归一为 ApiError(LEAD_ALREADY_CLAIMED)', async () => {
    server.use(claimAlreadyClaimed())
    await expect(claimLead(200)).rejects.toMatchObject({
      code: 'LEAD_ALREADY_CLAIMED',
    })
    await expect(claimLead(200)).rejects.toBeInstanceOf(ApiError)
  })

  it('releaseLead 命中 POST /leads/{id}/release 并带 releaseNote', async () => {
    let captured: unknown
    server.use(
      http.post('*/api/leads/:id/release', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_LEAD })
      }),
    )
    await releaseLead(100, '客户暂无预算')
    expect(captured).toEqual({ releaseNote: '客户暂无预算' })
  })

  it('changeStage 命中 PATCH /leads/{id}/stage 并带 stage', async () => {
    let captured: unknown
    server.use(
      http.patch('*/api/leads/:id/stage', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_LEAD })
      }),
    )
    await changeStage(100, '方案报价')
    expect(captured).toEqual({ stage: '方案报价' })
  })

  it('winLead 以字符串金额提交（保精确）', async () => {
    let captured: unknown
    server.use(
      http.post('*/api/leads/:id/win', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_LEAD })
      }),
    )
    await winLead(100, '1000.50', '2026-05-10')
    expect(captured).toEqual({ contractAmount: '1000.50', signedDate: '2026-05-10' })
  })

  it('loseLead 提交原因与说明', async () => {
    let captured: unknown
    server.use(
      http.post('*/api/leads/:id/lose', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_LEAD })
      }),
    )
    await loseLead(100, '其他', '客户取消预算')
    expect(captured).toEqual({ loseReason: '其他', loseNote: '客户取消预算' })
  })

  it('fetchProgress 命中 GET /leads/{id}/progress', async () => {
    server.use(progressList([SAMPLE_PROGRESS]))
    const rows = await fetchProgress(100)
    expect(rows[0]?.content).toBe(SAMPLE_PROGRESS.content)
  })

  it('addProgress 命中 POST /leads/{id}/progress 并带 method/content', async () => {
    let captured: unknown
    server.use(
      http.post('*/api/leads/:id/progress', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_PROGRESS })
      }),
    )
    await addProgress(100, '电话', '已沟通')
    expect(captured).toEqual({ method: '电话', content: '已沟通' })
  })

  it('createLead 命中 POST /leads 并透传 payload', async () => {
    let captured: unknown
    server.use(
      http.post('*/api/leads', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_LEAD })
      }),
    )
    await createLead({
      customerId: 10,
      businessType: 'BIM咨询',
      contactName: '王工',
      contactPhone: '13812345678',
      leadSource: '官网',
      assignToPool: true,
    })
    expect(captured).toEqual({
      customerId: 10,
      businessType: 'BIM咨询',
      contactName: '王工',
      contactPhone: '13812345678',
      leadSource: '官网',
      assignToPool: true,
    })
  })

  it('duplicateCheck 命中 GET /leads/duplicate-check?customerId=&businessType= 并 unwrap', async () => {
    let capturedUrl = ''
    server.use(
      http.get('*/api/leads/duplicate-check', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: { canCreate: true, blockingReason: null, historicalLost: [] },
        })
      }),
    )
    const result = await duplicateCheck(42, 'BIM咨询')
    const params = new URL(capturedUrl).searchParams
    expect(params.get('customerId')).toBe('42')
    expect(params.get('businessType')).toBe('BIM咨询')
    expect(result.canCreate).toBe(true)
    expect(result.blockingReason).toBeNull()
    expect(result.historicalLost).toEqual([])
  })

  // 引用工厂以避免未使用告警（部分用例用内联 handler 断言请求体）
  void [addProgressSuccess, stageSuccess, winSuccess, loseSuccess, releaseSuccess]
})
