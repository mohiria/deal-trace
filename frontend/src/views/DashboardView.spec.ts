import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import { http, HttpResponse, delay } from 'msw'
import { server } from '../test/msw/server'
import {
  ADMIN_USER,
  SALES_USER,
  SAMPLE_LEAD,
  SAMPLE_POOL_LEAD,
  SAMPLE_PROGRESS,
  allLeads,
  mineLeads,
  poolList,
  leadDetail,
  progressList,
  claimSuccess,
  claimAlreadyClaimed,
} from '../test/msw/handlers'
import type { LeadView, PoolLeadView } from '../api/leads'
import { useAuthStore } from '../stores/auth'
import DashboardView from './DashboardView.vue'

const SAMPLE_DASHBOARD = {
  todayNewLeadCount: 4,
  openSeaUnclaimedCount: 28,
  monthlyWonAmount: 150000.5,
  monthlyLossRate: 0.4,
  monthlyLostEventCount: 2,
  monthlyEndedEventCount: 5,
}

function success(data: unknown) {
  return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data })
}

const Stub = defineComponent({ render: () => h('div', 'stub') })

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'workbench', component: DashboardView },
      { path: '/login', name: 'login', component: Stub },
      { path: '/customers', name: 'customers', component: Stub },
    ],
  })
}

interface MountOpts {
  admin?: boolean
  mine?: LeadView[]
  all?: LeadView[]
  pool?: PoolLeadView[]
}

async function mountView(opts: MountOpts = {}): Promise<{ wrapper: VueWrapper; router: Router }> {
  server.use(mineLeads(opts.mine ?? []), allLeads(opts.all ?? []), poolList(opts.pool ?? []))
  useAuthStore().currentUser = opts.admin ? ADMIN_USER : SALES_USER
  const router = buildRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(DashboardView, { global: { plugins: [router, ArcoVue] } })
  return { wrapper, router }
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

describe('原型化指标区', () => {
  it('挂载即拉取并同屏渲染四项指标', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView()
    await flushPromises()

    const text = wrapper.find('[data-test="dashboard-metrics"]').text()
    expect(text).toContain('今日新增线索')
    expect(text).toContain('公海待认领')
    expect(text).toContain('本月赢单金额')
    expect(text).toContain('本月流失率')
    expect(text).toContain('4')
    expect(text).toContain('28')
    expect(text).toContain('¥150,000.5')
    expect(text).toContain('40%')
  })

  it('查询进行中呈现加载态，不把未返回指标渲染为零值', async () => {
    server.use(
      http.get('*/api/dashboard', async () => {
        await delay(50)
        return success(SAMPLE_DASHBOARD)
      }),
    )
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-test="dashboard-loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="dashboard-metrics"]').exists()).toBe(false)
    await flushPromises()
  })

  it('失败态可重试', async () => {
    let call = 0
    server.use(
      http.get('*/api/dashboard', () => {
        call += 1
        if (call === 1) {
          return HttpResponse.json({ code: 'INTERNAL_ERROR', message: '服务异常', data: null }, { status: 500 })
        }
        return success(SAMPLE_DASHBOARD)
      }),
    )
    const { wrapper } = await mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="dashboard-error"]').exists()).toBe(true)

    await wrapper.find('[data-test="dashboard-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="dashboard-metrics"]').exists()).toBe(true)
  })

  it('流失率与金额特殊值格式正确', async () => {
    server.use(
      http.get('*/api/dashboard', () =>
        success({ ...SAMPLE_DASHBOARD, monthlyLossRate: null, monthlyEndedEventCount: 0, monthlyWonAmount: 0 }),
      ),
    )
    const { wrapper } = await mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="metric-loss-rate"]').text()).toContain('--')
    expect(wrapper.find('[data-test="metric-won-amount"]').text()).toContain('¥0')
  })
})

describe('三 Tab 线索工作区', () => {
  it('SALES 默认展示我的线索，ADMIN 默认展示全部线索', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView({ mine: [SAMPLE_LEAD] })
    await flushPromises()
    expect(wrapper.find('[data-test="workbench-leads"]').text()).toContain(SAMPLE_LEAD.customerName!)

    const allRow: LeadView = { ...SAMPLE_LEAD, id: 777, customerName: '全量线索客户' }
    const mounted = await mountView({ admin: true, all: [allRow] })
    await flushPromises()
    expect(mounted.wrapper.find('[data-test="workbench-leads"]').text()).toContain('全量线索客户')
  })

  it('切换到公海线索 Tab 展示公海列表并支持认领', async () => {
    server.use(
      http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)),
      claimSuccess({ ...SAMPLE_LEAD, id: SAMPLE_POOL_LEAD.id, ownerSalesId: 2 }),
      leadDetail({ ...SAMPLE_LEAD, id: SAMPLE_POOL_LEAD.id, ownerSalesId: 2 }),
      progressList([SAMPLE_PROGRESS]),
    )
    const successSpy = vi.spyOn(Message, 'success')
    const { wrapper } = await mountView({ pool: [SAMPLE_POOL_LEAD] })
    await flushPromises()

    await wrapper.findAll('.tab').find((t) => t.text().includes('公海线索'))!.trigger('click')
    await flushPromises()

    const ws = wrapper.find('[data-test="workbench-leads"]')
    expect(ws.text()).toContain(SAMPLE_POOL_LEAD.customerName!)
    await ws.find('.claim-btn').trigger('click')
    await flushPromises()
    expect(successSpy).toHaveBeenCalled()
  })

  it('公海认领被抢先时提示并刷新公海', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)), claimAlreadyClaimed())
    const warnSpy = vi.spyOn(Message, 'warning')
    const { wrapper } = await mountView({ pool: [SAMPLE_POOL_LEAD] })
    await flushPromises()

    await wrapper.findAll('.tab').find((t) => t.text().includes('公海线索'))!.trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="workbench-leads"] .claim-btn').trigger('click')
    await flushPromises()

    expect(warnSpy.mock.calls.some((c) => String(c[0]).includes('已被认领'))).toBe(true)
  })

  it('本月结束 Tab 只展示当前月份赢单或流失线索', async () => {
    const now = new Date()
    const current = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-10T09:00:00`
    const won: LeadView = { ...SAMPLE_LEAD, id: 401, customerName: '本月赢单客户', stage: '已赢单', wonAt: current }
    const lost: LeadView = { ...SAMPLE_LEAD, id: 402, customerName: '本月流失客户', stage: '已流失', lostAt: current }
    const old: LeadView = { ...SAMPLE_LEAD, id: 403, customerName: '历史结束客户', stage: '已赢单', wonAt: '2025-01-01T09:00:00' }
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView({ mine: [won, lost, old] })
    await flushPromises()

    await wrapper.findAll('.tab').find((t) => t.text().includes('本月结束'))!.trigger('click')
    await flushPromises()

    const text = wrapper.find('[data-test="workbench-leads"]').text()
    expect(text).toContain('本月赢单客户')
    expect(text).toContain('本月流失客户')
    expect(text).not.toContain('历史结束客户')
  })

  it('过滤器按关键词、业务类型和阶段筛选当前 Tab', async () => {
    const a: LeadView = { ...SAMPLE_LEAD, id: 501, customerName: '星河建设集团', businessType: 'BIM咨询', stage: '方案报价' }
    const b: LeadView = { ...SAMPLE_LEAD, id: 502, customerName: '远景产业园', businessType: '定制开发', stage: '商务谈判' }
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView({ mine: [a, b] })
    await flushPromises()

    await wrapper.find('.search').setValue('星河')
    await wrapper.findAll('select')[0]!.setValue('BIM咨询')
    await wrapper.findAll('select')[1]!.setValue('方案报价')
    await flushPromises()

    const text = wrapper.find('[data-test="workbench-leads"]').text()
    expect(text).toContain('星河建设集团')
    expect(text).not.toContain('远景产业园')
  })
})

describe('右侧详情面板', () => {
  it('点击表格线索在右侧面板渲染 LeadDetailPanel', async () => {
    server.use(
      http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)),
      leadDetail(SAMPLE_LEAD),
      progressList([SAMPLE_PROGRESS]),
    )
    const { wrapper } = await mountView({ mine: [SAMPLE_LEAD] })
    await flushPromises()

    expect(wrapper.find('[data-test="lead-drawer"]').exists()).toBe(false)
    await wrapper.find('[data-test="workbench-leads"] .lead-link').trigger('click')
    await flushPromises()

    const drawer = wrapper.find('[data-test="lead-drawer"]')
    expect(drawer.find('.detail-desc').exists()).toBe(true)
    expect(drawer.text()).toContain(SAMPLE_LEAD.customerUsci!)
  })

  it('打开已结束线索不呈现写入口且不含系统日志', async () => {
    const won: LeadView = { ...SAMPLE_LEAD, stage: '已赢单', wonAt: '2026-05-10T09:00:00' }
    server.use(
      http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)),
      leadDetail(won),
      progressList([SAMPLE_PROGRESS]),
    )
    const { wrapper } = await mountView({ mine: [won] })
    await flushPromises()
    await wrapper.find('[data-test="workbench-leads"] .lead-link').trigger('click')
    await flushPromises()

    const drawer = wrapper.find('[data-test="lead-drawer"]')
    expect(drawer.find('.stage-btn').exists()).toBe(false)
    expect(drawer.find('.win-open').exists()).toBe(false)
    expect(drawer.find('.release-open').exists()).toBe(false)
    expect(drawer.html()).not.toContain('系统日志')
  })
})

describe('首屏加载仅发只读查询', () => {
  it('首屏加载只命中只读 GET 端点，不向任何写端点发请求', async () => {
    const calls: { method: string; path: string }[] = []
    const record = (path: string) =>
      http.all(`*/api${path}`, ({ request }) => {
        calls.push({ method: request.method, path })
        if (path === '/dashboard') return success(SAMPLE_DASHBOARD)
        if (path === '/leads/mine') return success([SAMPLE_LEAD])
        if (path === '/leads/pool') return success([SAMPLE_POOL_LEAD])
        return success([])
      })
    server.use(record('/dashboard'), record('/leads/mine'), record('/leads/pool'))

    await mountView()
    await flushPromises()

    expect(calls.length).toBeGreaterThan(0)
    expect(calls.every((c) => c.method === 'GET')).toBe(true)
  })
})

describe('lead drawer close behavior', () => {
  it('closes the lead drawer and clears the selected row', async () => {
    server.use(
      http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)),
      leadDetail(SAMPLE_LEAD),
      progressList([SAMPLE_PROGRESS]),
    )
    const { wrapper } = await mountView({ mine: [SAMPLE_LEAD] })
    await flushPromises()

    await wrapper.find('[data-test="workbench-leads"] .lead-link').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="lead-drawer"]').exists()).toBe(true)
    expect(wrapper.find('tr.selected').exists()).toBe(true)

    await wrapper.find('[data-test="lead-drawer-close"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="lead-drawer"]').exists()).toBe(false)
    expect(wrapper.find('tr.selected').exists()).toBe(false)
  })
})
