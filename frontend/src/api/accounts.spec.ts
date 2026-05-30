import { afterEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import {
  SAMPLE_ACCOUNT,
  accountsList,
  createSalesSuccess,
  createSalesDuplicate,
  statusToggleSuccess,
} from '../test/msw/handlers'
import { ApiError } from './client'
import { fetchAccounts, createSales, updateAccountStatus } from './accounts'

describe('accounts API（D1）', () => {
  afterEach(() => server.resetHandlers())

  it('fetchAccounts 命中 GET /admin/accounts 并 unwrap 列表', async () => {
    server.use(accountsList())
    const rows = await fetchAccounts()
    expect(rows.length).toBeGreaterThanOrEqual(1)
    expect(rows.some((a) => a.role === 'SALES')).toBe(true)
    // 不含密码字段
    expect(rows.every((a) => !('passwordHash' in a))).toBe(true)
  })

  it('createSales 命中 POST /admin/accounts 并固定 role=SALES', async () => {
    let captured: unknown
    server.use(
      http.post('*/api/admin/accounts', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_ACCOUNT })
      }),
    )
    const created = await createSales({ email: 'new@dealtrace.local', name: '新销售', password: 'pw123456' })
    expect(captured).toEqual({
      email: 'new@dealtrace.local',
      name: '新销售',
      password: 'pw123456',
      role: 'SALES',
    })
    expect(created.id).toBe(SAMPLE_ACCOUNT.id)
  })

  it('createSales 邮箱重复时归一为 ApiError(VALIDATION_ERROR)', async () => {
    server.use(createSalesDuplicate('邮箱已存在'))
    await expect(
      createSales({ email: 'dup@dealtrace.local', name: '重复', password: 'pw123456' }),
    ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' })
    await expect(
      createSales({ email: 'dup@dealtrace.local', name: '重复', password: 'pw123456' }),
    ).rejects.toBeInstanceOf(ApiError)
  })

  it('updateAccountStatus 命中 PATCH /admin/accounts/{id}/status 并带 status', async () => {
    let captured: unknown
    server.use(
      http.patch('*/api/admin/accounts/:id/status', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: { ...SAMPLE_ACCOUNT, status: 'DISABLED' } })
      }),
    )
    const updated = await updateAccountStatus(2, 'DISABLED')
    expect(captured).toEqual({ status: 'DISABLED' })
    expect(updated.status).toBe('DISABLED')
  })

  // 引用工厂以避免未使用告警
  void [statusToggleSuccess, createSalesSuccess]
})
