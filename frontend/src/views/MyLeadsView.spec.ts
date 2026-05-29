import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import { server } from '../test/msw/server'
import { ADMIN_USER, SALES_USER, SAMPLE_LEAD, allLeads, meSuccess, mineLeads } from '../test/msw/handlers'
import { useAuthStore } from '../stores/auth'
import MyLeadsView from './MyLeadsView.vue'

/**
 * 我的线索（spec R1）：Sales 名下 / Admin 全部 / 进入详情 / 空态。
 */

const Stub = defineComponent({ render: () => h('div', 'detail') })

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/my-leads', name: 'my-leads', component: MyLeadsView },
      { path: '/leads/:id', name: 'lead-detail', component: Stub },
    ],
  })
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = buildRouter()
  await router.push('/my-leads')
  await router.isReady()
  const wrapper = mount(MyLeadsView, { global: { plugins: [router, ArcoVue] } })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('数据集按角色', () => {
  it('Sales 渲染名下线索（GET /leads/mine）', async () => {
    server.use(meSuccess(SALES_USER), mineLeads([SAMPLE_LEAD]))
    const store = useAuthStore()
    store.currentUser = SALES_USER
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain(SAMPLE_LEAD.customerName!)
  })

  it('Admin 渲染全部线索（GET /leads）', async () => {
    server.use(allLeads([{ ...SAMPLE_LEAD, customerName: '管理员可见客户' }]))
    const store = useAuthStore()
    store.currentUser = ADMIN_USER
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('管理员可见客户')
  })
})

describe('进入详情', () => {
  it('点击客户链接路由到 lead-detail', async () => {
    server.use(mineLeads([SAMPLE_LEAD]))
    const store = useAuthStore()
    store.currentUser = SALES_USER
    const { wrapper, router } = await mountView()

    await wrapper.find('.lead-link').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('lead-detail')
    expect(router.currentRoute.value.params.id).toBe(String(SAMPLE_LEAD.id))
  })
})

describe('空态', () => {
  it('无线索时展示空态', async () => {
    server.use(mineLeads([]))
    const store = useAuthStore()
    store.currentUser = SALES_USER
    const { wrapper } = await mountView()
    expect(wrapper.text()).toContain('暂无线索')
  })
})
