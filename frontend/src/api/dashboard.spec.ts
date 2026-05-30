import { afterEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import { ApiError } from './client'
import { fetchDashboard } from './dashboard'

/** 后端 DashboardView 的样例（数字 / null，对应 DashboardControllerTest 的断言形态）。 */
const SAMPLE_DASHBOARD = {
  todayNewLeadCount: 4,
  openSeaUnclaimedCount: 28,
  monthlyWonAmount: 150000.5,
  monthlyLossRate: 0.4,
  monthlyLostEventCount: 2,
  monthlyEndedEventCount: 5,
}

describe('dashboard API（D1）', () => {
  afterEach(() => server.resetHandlers())

  it('fetchDashboard 命中 GET /dashboard 且不携带任何视角/owner 参数', async () => {
    let capturedUrl = ''
    server.use(
      http.get('*/api/dashboard', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_DASHBOARD })
      }),
    )
    await fetchDashboard()
    const params = new URL(capturedUrl).searchParams
    expect([...params.keys()]).toHaveLength(0)
  })

  it('fetchDashboard 返回 unwrap 后的 DashboardView（6 字段，数字/ null 原样）', async () => {
    server.use(
      http.get('*/api/dashboard', () =>
        HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_DASHBOARD }),
      ),
    )
    const view = await fetchDashboard()
    expect(view).toEqual(SAMPLE_DASHBOARD)
  })

  it('monthlyLossRate 为 null 时原样透传（不在 API 层归一）', async () => {
    server.use(
      http.get('*/api/dashboard', () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: { ...SAMPLE_DASHBOARD, monthlyLossRate: null, monthlyEndedEventCount: 0 },
        }),
      ),
    )
    const view = await fetchDashboard()
    expect(view.monthlyLossRate).toBeNull()
  })

  it('非鉴权业务错误归一为 ApiError(code)', async () => {
    server.use(
      http.get('*/api/dashboard', () =>
        HttpResponse.json(
          { code: 'INTERNAL_ERROR', message: '服务异常', data: null },
          { status: 500 },
        ),
      ),
    )
    await expect(fetchDashboard()).rejects.toBeInstanceOf(ApiError)
    await expect(fetchDashboard()).rejects.toMatchObject({ code: 'INTERNAL_ERROR' })
  })
})
