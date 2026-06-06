import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import { http, HttpResponse } from 'msw'
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
    { path: '/customers', name: 'customers', component: Stub },
  ],
})
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

async function mountView(
  props: Record<string, unknown> = {},
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = buildRouter()
  await router.push('/my-leads')
  await router.isReady()
  const wrapper = mount(MyLeadsView, { props, global: { plugins: [router, ArcoVue] } })
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

describe('refine my leads list iteration', () => {
  it('提供新增客户和新增线索入口', async () => {
    server.use(mineLeads([SAMPLE_LEAD]))
    const store = useAuthStore()
    store.currentUser = SALES_USER
    const { wrapper } = await mountView()

    expect(wrapper.find('.create-customer-open').exists()).toBe(true)
    expect(wrapper.find('.create-lead-open').exists()).toBe(true)
  })

  it('服务端分页：翻页向后端请求对应页，换关键词回第 1 页且下推后端', async () => {
    const all = Array.from({ length: 23 }, (_, index) => ({
      ...SAMPLE_LEAD,
      id: 800 + index,
      customerName: index === 22 ? '星河我的线索' : `我的分页客户${String(index + 1).padStart(2, '0')}`,
    }))
    let lastQuery = new URLSearchParams()
    server.use(
      meSuccess(SALES_USER),
      http.get('*/api/leads/mine', ({ request }) => {
        const url = new URL(request.url)
        lastQuery = url.searchParams
        const keyword = url.searchParams.get('keyword')?.trim()
        const pageNo = Number(url.searchParams.get('page') ?? '1')
        const size = Number(url.searchParams.get('size') ?? '10')
        const filtered = keyword ? all.filter((r) => r.customerName!.includes(keyword)) : all
        const start = (pageNo - 1) * size
        return HttpResponse.json({
          code: 'SUCCESS',
          message: 'OK',
          data: { items: filtered.slice(start, start + size), total: filtered.length, page: pageNo, size },
        })
      }),
    )
    const store = useAuthStore()
    store.currentUser = SALES_USER
    const { wrapper } = await mountView({ debounceMs: 0 })

    // 第 1 页：分页控件存在，仅当页内容
    expect(wrapper.find('[data-test="list-pagination"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('我的分页客户01')
    expect(wrapper.text()).not.toContain('星河我的线索')

    // 翻到第 2 页 → 后端 page=2（直接驱动分页状态，避免 teleport 取值脆弱）
    ;(wrapper.vm as unknown as { currentPage: number }).currentPage = 2
    await flushPromises()
    expect(lastQuery.get('page')).toBe('2')
    expect(wrapper.text()).toContain('我的分页客户11')

    // 换关键词 → 回第 1 页且 keyword 下推后端
    await wrapper.find('.list-search').setValue('星河')
    await tick()
    await flushPromises()
    expect(lastQuery.get('keyword')).toBe('星河')
    expect(lastQuery.get('page')).toBe('1')
    expect(wrapper.text()).toContain('星河我的线索')
  })
})
