import { beforeEach, describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'
import type { AuthUser } from '../stores/auth'
import { authGuard } from './guards'

/**
 * 路由守卫行为：spec R2（未登录拦截 / 已登录回落）、R6（角色守卫回落）。
 * 用 memory 路由 + stub 组件 + 真实 authGuard，隔离守卫逻辑与真实页面组件。
 */

const Stub = defineComponent({ render: () => h('div') })

function buildRouter(): Router {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', meta: { public: true }, component: Stub },
      { path: '/', name: 'workbench', component: Stub },
      { path: '/admin', name: 'admin', meta: { requiresAdmin: true }, component: Stub },
    ],
  })
  router.beforeEach(authGuard)
  return router
}

const ADMIN: AuthUser = { id: 1, email: 'a@d.local', name: 'A', role: 'ADMIN', status: 'ENABLED' }
const SALES: AuthUser = { id: 2, email: 's@d.local', name: 'S', role: 'SALES', status: 'ENABLED' }

function authenticateAs(user: AuthUser) {
  const auth = useAuthStore()
  auth.token = 'tok'
  auth.currentUser = user
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('登录态守卫', () => {
  it('未登录访问受保护路由 → 重定向登录页', async () => {
    const router = buildRouter()
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('已登录访问登录页 → 重定向工作台', async () => {
    authenticateAs(ADMIN)
    const router = buildRouter()
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('workbench')
  })

  it('已登录访问受保护路由 → 放行', async () => {
    authenticateAs(ADMIN)
    const router = buildRouter()
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('workbench')
  })
})

describe('角色守卫', () => {
  it('SALES 访问 ADMIN 专属路由 → 回落工作台（不渲染该路由）', async () => {
    authenticateAs(SALES)
    const router = buildRouter()
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('workbench')
  })

  it('ADMIN 访问 ADMIN 专属路由 → 放行', async () => {
    authenticateAs(ADMIN)
    const router = buildRouter()
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('admin')
  })
})
