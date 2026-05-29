import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, TOKEN_STORAGE_KEY } from '../api/client'
import { server } from '../test/msw/server'
import {
  ADMIN_USER,
  SALES_USER,
  loginSuccess,
  loginUnauthorized,
  meSuccess,
  meUnauthorized,
} from '../test/msw/handlers'
import { useAuthStore } from './auth'

/**
 * auth store 行为：spec R1（登录）/ R4（刷新恢复）/ R5（登出）/ R6（角色派生）。
 */

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('login', () => {
  it('成功后承载令牌与当前用户，并把令牌写入 localStorage', async () => {
    server.use(loginSuccess(ADMIN_USER), meSuccess(ADMIN_USER))
    const store = useAuthStore()

    await store.login('admin@dealtrace.local', 'secret')

    expect(store.token).toBeTruthy()
    expect(store.currentUser).toMatchObject({
      email: ADMIN_USER.email,
      name: ADMIN_USER.name,
      role: 'ADMIN',
    })
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe(store.token)
    expect(store.isAuthenticated).toBe(true)
  })

  it('失败(401)不写登录态并抛出携带后端 message 的 ApiError', async () => {
    server.use(loginUnauthorized('邮箱或密码不正确'))
    const store = useAuthStore()

    await expect(store.login('x@y.com', 'bad')).rejects.toBeInstanceOf(ApiError)
    expect(store.token).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })
})

describe('restore', () => {
  it('本地有令牌且 /me 成功 → 用完整用户填充 store', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'persisted-token')
    server.use(meSuccess(SALES_USER))
    const store = useAuthStore()

    await store.restore()

    expect(store.token).toBe('persisted-token')
    expect(store.currentUser).toMatchObject({
      id: SALES_USER.id,
      role: 'SALES',
      status: 'ENABLED',
    })
  })

  it('本地令牌已失效（/me 401）→ 清除令牌且登录态为空', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'stale-token')
    server.use(meUnauthorized())
    const store = useAuthStore()

    await store.restore()

    expect(store.token).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })

  it('本地无令牌 → 不发请求、登录态为空', async () => {
    const store = useAuthStore()

    await store.restore()

    expect(store.currentUser).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })
})

describe('logout', () => {
  it('清除令牌、当前用户与 localStorage', async () => {
    server.use(loginSuccess(ADMIN_USER), meSuccess(ADMIN_USER))
    const store = useAuthStore()
    await store.login('admin@dealtrace.local', 'secret')

    store.logout()

    expect(store.token).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })
})

describe('角色派生', () => {
  it('isAdmin 随 role 变化', async () => {
    server.use(loginSuccess(ADMIN_USER), meSuccess(ADMIN_USER))
    const store = useAuthStore()
    await store.login('admin@dealtrace.local', 'secret')
    expect(store.isAdmin).toBe(true)

    store.logout()
    server.use(meSuccess(SALES_USER))
    localStorage.setItem(TOKEN_STORAGE_KEY, 't')
    await store.restore()
    expect(store.isAdmin).toBe(false)
  })
})
