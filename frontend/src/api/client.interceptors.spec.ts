import { afterEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { apiClient, ApiError, setUnauthorizedHandler, TOKEN_STORAGE_KEY } from './client'
import { server } from '../test/msw/server'

/**
 * 针对真实 `apiClient` 实例的拦截器行为（MSW 在 axios 边界拦截）。
 * 覆盖 spec R3：受保护请求注入 Bearer、错误归一为 ApiError、UNAUTHORIZED 触发会话失效处理器。
 */

afterEach(() => setUnauthorizedHandler(null))

function okEnvelope() {
  return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: { ok: true } })
}

describe('request interceptor — Bearer 注入', () => {
  it('有 token 时注入 Authorization: Bearer <token>', async () => {
    let received: string | null = 'UNSET'
    server.use(
      http.get('*/api/ping', ({ request }) => {
        received = request.headers.get('authorization')
        return okEnvelope()
      }),
    )
    localStorage.setItem(TOKEN_STORAGE_KEY, 'tok-123')

    await apiClient.get('/ping')

    expect(received).toBe('Bearer tok-123')
  })

  it('无 token 时不带 Authorization 头', async () => {
    let received: string | null = 'UNSET'
    server.use(
      http.get('*/api/ping', ({ request }) => {
        received = request.headers.get('authorization')
        return okEnvelope()
      }),
    )

    await apiClient.get('/ping')

    expect(received).toBeNull()
  })
})

describe('错误归一化为 ApiError', () => {
  it('非 2xx 且响应体为错误信封 → 抛 ApiError 带 code/message', async () => {
    server.use(
      http.get('*/api/boom', () =>
        HttpResponse.json({ code: 'VALIDATION_ERROR', message: '参数错误', data: null }, { status: 400 }),
      ),
    )

    await expect(apiClient.get('/boom')).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      message: '参数错误',
    })
  })

  it('HTTP 200 但 code != SUCCESS → 抛 ApiError（不回归 unwrapEnvelope）', async () => {
    server.use(
      http.get('*/api/biz', () =>
        HttpResponse.json({ code: 'CONFLICT', message: '重复', data: null }),
      ),
    )

    await expect(apiClient.get('/biz')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('UNAUTHORIZED 分流到会话失效处理器', () => {
  it('code === UNAUTHORIZED 触发已注册处理器并抛出', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    server.use(
      http.get('*/api/secure', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED', message: '登录已失效', data: null }, { status: 401 }),
      ),
    )

    await expect(apiClient.get('/secure')).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('非 UNAUTHORIZED 业务错误不触发处理器、原样透传', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    server.use(
      http.get('*/api/forbidden', () =>
        HttpResponse.json({ code: 'FORBIDDEN', message: '无权限', data: null }, { status: 403 }),
      ),
    )

    await expect(apiClient.get('/forbidden')).rejects.toMatchObject({ code: 'FORBIDDEN' })
    expect(handler).not.toHaveBeenCalled()
  })
})
