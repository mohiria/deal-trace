import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import { useAuthStore } from '../stores/auth'
import type { AuthUser } from '../stores/auth'
import { visibleSections } from './navigation'
import AppShell from './AppShell.vue'

const Stub = defineComponent({ render: () => h('div') })
const ADMIN: AuthUser = { id: 1, email: 'a@d.local', name: '管理员', role: 'ADMIN', status: 'ENABLED' }
const SALES: AuthUser = { id: 2, email: 's@d.local', name: '林雨', role: 'SALES', status: 'ENABLED' }

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: Stub },
      { path: '/', name: 'workbench', component: Stub },
      { path: '/my-leads', name: 'my-leads', component: Stub },
      { path: '/public-pool', name: 'public-pool', component: Stub },
      { path: '/customers', name: 'customers', component: Stub },
      { path: '/contracts', name: 'contracts', component: Stub },
      { path: '/users', name: 'users', component: Stub },
      { path: '/system-logs', name: 'system-logs', component: Stub },
    ],
  })
}

async function mountShell(user: AuthUser): Promise<{ wrapper: VueWrapper; router: Router }> {
  const auth = useAuthStore()
  auth.token = 'tok'
  auth.currentUser = user
  const router = buildRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(AppShell, { global: { plugins: [router, ArcoVue] } })
  return { wrapper, router }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('visibleSections（纯函数）', () => {
  it('ADMIN 可见 Admin 专属入口', () => {
    const labels = visibleSections('ADMIN').flatMap((s) => s.entries.map((e) => e.label))
    expect(labels).toContain('用户管理')
    expect(labels).toContain('我的线索')
  })

  it('SALES 不含 Admin 专属入口', () => {
    const labels = visibleSections('SALES').flatMap((s) => s.entries.map((e) => e.label))
    expect(labels).not.toContain('用户管理')
    expect(labels).not.toContain('系统日志')
    expect(labels).toContain('我的线索')
  })
})

describe('AppShell 导航显隐', () => {
  it('ADMIN 登录后导航含 Admin 专属入口', async () => {
    const { wrapper } = await mountShell(ADMIN)
    expect(wrapper.text()).toContain('用户管理')
  })

  it('SALES 登录后导航不含 Admin 专属入口', async () => {
    const { wrapper } = await mountShell(SALES)
    expect(wrapper.text()).not.toContain('用户管理')
    expect(wrapper.text()).toContain('我的线索')
  })
})

describe('AppShell 登出', () => {
  it('点击登出 → 清登录态并回落登录页', async () => {
    const { wrapper, router } = await mountShell(ADMIN)
    const auth = useAuthStore()

    await wrapper.find('.shell-logout').trigger('click')
    await flushPromises()

    expect(auth.isAuthenticated).toBe(false)
    expect(router.currentRoute.value.name).toBe('login')
  })
})
