import { afterEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import {
  SAMPLE_CUSTOMER,
  customerList,
  customerSearch,
  createCustomerSuccess,
  createCustomerDuplicate,
} from '../test/msw/handlers'
import { ApiError } from './client'
import { searchCustomers, createCustomer } from './customers'

describe('customers API（D1）', () => {
  afterEach(() => server.resetHandlers())

  it('searchCustomers 无关键词命中 GET /customers（分页信封，不带 keyword query）', async () => {
    let capturedUrl = ''
    server.use(
      http.get('*/api/customers', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: { items: [SAMPLE_CUSTOMER], total: 1, page: 1, size: 20 },
        })
      }),
    )
    const res = await searchCustomers()
    expect(res.items).toHaveLength(1)
    expect(res.items[0]?.id).toBe(SAMPLE_CUSTOMER.id)
    expect(res.total).toBe(1)
    expect(new URL(capturedUrl).searchParams.has('keyword')).toBe(false)
  })

  it('searchCustomers 带关键词与分页命中 GET /customers?keyword=&page=&size=', async () => {
    let capturedUrl = ''
    server.use(
      http.get('*/api/customers', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: { items: [SAMPLE_CUSTOMER], total: 1, page: 2, size: 20 },
        })
      }),
    )
    await searchCustomers({ keyword: '建筑', page: 2 })
    const params = new URL(capturedUrl).searchParams
    expect(params.get('keyword')).toBe('建筑')
    expect(params.get('page')).toBe('2')
    expect(params.get('size')).toBe('20')
  })

  it('createCustomer 命中 POST /customers 并原样提交 name/usci（不前端归一化）', async () => {
    let captured: unknown
    server.use(
      http.post('*/api/customers', async ({ request }) => {
        captured = await request.json()
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_CUSTOMER })
      }),
    )
    const created = await createCustomer('  中建院  ', ' 91110000ma0000000x ')
    // 原样提交：不 trim、不大写（归一化由后端权威完成）
    expect(captured).toEqual({ name: '  中建院  ', usci: ' 91110000ma0000000x ' })
    expect(created.id).toBe(SAMPLE_CUSTOMER.id)
  })

  it('createCustomer 重复时归一为 ApiError(DUPLICATE_CUSTOMER)', async () => {
    server.use(createCustomerDuplicate())
    await expect(createCustomer('中建院', '91110000MA0000000X')).rejects.toMatchObject({
      code: 'DUPLICATE_CUSTOMER',
    })
    await expect(createCustomer('中建院', '91110000MA0000000X')).rejects.toBeInstanceOf(ApiError)
  })

  // 引用工厂以避免未使用告警
  void [customerList, customerSearch, createCustomerSuccess]
})
