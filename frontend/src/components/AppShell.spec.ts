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
  // 镜像真实嵌套结构（父 `/` + 子 `path: ''` 工作台），否则无法复现导航高亮匹配行为。
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: Stub },
      {
        path: '/',
        component: Stub,
        children: [
          { path: '', name: 'workbench', component: Stub },
          { path: 'my-leads', name: 'my-leads', component: Stub },
          { path: 'public-pool', name: 'public-pool', component: Stub },
          { path: 'customers', name: 'customers', component: Stub },
          { path: 'contracts', name: 'contracts', component: Stub },
          { path: 'users', name: 'users', component: Stub },
          { path: 'system-logs', name: 'system-logs', component: Stub },
        ],
      },
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

  it('系统日志入口对 ADMIN 不呈现（查看能力尚未交付，frontend-admin 隐藏）', () => {
    const labels = visibleSections('ADMIN').flatMap((s) => s.entries.map((e) => e.label))
    expect(labels).not.toContain('系统日志')
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

describe('refine shell iteration', () => {
  it('右上角只显示姓名不显示角色', async () => {
    const { wrapper } = await mountShell(SALES)
    expect(wrapper.find('.shell-user-name').text()).toContain(SALES.name)
    expect(wrapper.find('.shell-user-role').exists()).toBe(false)
    expect(wrapper.find('.shell-topbar').text()).not.toContain('销售')
  })

  it('今日提醒新增线索触发统一入口事件', async () => {
    const { wrapper } = await mountShell(SALES)
    await wrapper.find('[data-test="reminder-create-lead"]').trigger('click')

    expect(wrapper.emitted('open-create-lead')).toBeTruthy()
  })
})

describe('refine nav active state', () => {
  function navItem(wrapper: VueWrapper, label: string) {
    return wrapper.findAll('.shell-nav-item').find((i) => i.text().includes(label))!
  }

  it('停留在销售工作台时该入口高亮', async () => {
    const { wrapper } = await mountShell(ADMIN)
    expect(navItem(wrapper, '销售工作台').classes()).toContain('is-active')
  })

  it('切换到其他菜单时销售工作台不再高亮，仅当前菜单高亮', async () => {
    const { wrapper, router } = await mountShell(ADMIN)
    await router.push('/my-leads')
    await flushPromises()

    expect(navItem(wrapper, '销售工作台').classes()).not.toContain('is-active')
    expect(navItem(wrapper, '我的线索').classes()).toContain('is-active')
  })
})
