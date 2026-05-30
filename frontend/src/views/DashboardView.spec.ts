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
import { useLeadsStore } from '../stores/leads'
import DashboardView from './DashboardView.vue'

/**
 * 工作台首屏（spec frontend-workbench）：
 * - 指标看板（只读、口径后端裁决、金额/流失率呈现、加载失败态）；
 * - 内嵌线索工作区（表格 + 抽屉详情，复用 LeadDetailPanel）；
 * - 今日提醒/待办（建议认领 + 长期未跟踪，客户端派生）；
 * - 首屏加载仅发只读查询。
 */

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
      { path: '/leads/:id', name: 'lead-detail', component: Stub },
    ],
  })
}

interface MountOpts {
  admin?: boolean
  mine?: LeadView[]
  all?: LeadView[]
  pool?: PoolLeadView[]
}

/**
 * 默认注册工作区/提醒所需的只读列表 handler（默认空，避免 onUnhandledRequest:'error'）。
 * dashboard handler 由各测试用例在调用前以 server.use 单独注册。
 */
async function mountView(opts: MountOpts = {}): Promise<{ wrapper: VueWrapper; router: Router }> {
  server.use(mineLeads(opts.mine ?? []), allLeads(opts.all ?? []), poolList(opts.pool ?? []))
  if (opts.admin) {
    useAuthStore().currentUser = ADMIN_USER
  } else {
    useAuthStore().currentUser = SALES_USER
  }
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

describe('首屏渲染四项指标', () => {
  it('挂载即拉取并同屏渲染四项指标（值来自后端）', async () => {
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
})

describe('加载态', () => {
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
})

describe('失败态', () => {
  it('非鉴权失败呈现可重试失败态，不渲染零值指标', async () => {
    server.use(
      http.get('*/api/dashboard', () =>
        HttpResponse.json({ code: 'INTERNAL_ERROR', message: '服务异常', data: null }, { status: 500 }),
      ),
    )
    const { wrapper } = await mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="dashboard-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="dashboard-retry"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="dashboard-metrics"]').exists()).toBe(false)
  })

  it('点重试重新拉取并渲染指标', async () => {
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
    expect(wrapper.find('[data-test="dashboard-metrics"]').text()).toContain('今日新增线索')
  })
})

describe('流失率与金额呈现分支', () => {
  it('流失率 null → "--"（非 "0%"）', async () => {
    server.use(
      http.get('*/api/dashboard', () =>
        success({ ...SAMPLE_DASHBOARD, monthlyLossRate: null, monthlyEndedEventCount: 0, monthlyLostEventCount: 0 }),
      ),
    )
    const { wrapper } = await mountView()
    await flushPromises()
    const lossText = wrapper.find('[data-test="metric-loss-rate"]').text()
    expect(lossText).toContain('--')
    expect(lossText).not.toContain('0%')
  })

  it('流失率 0 → "0%"（非 "--"）', async () => {
    server.use(
      http.get('*/api/dashboard', () =>
        success({ ...SAMPLE_DASHBOARD, monthlyLossRate: 0, monthlyLostEventCount: 0, monthlyEndedEventCount: 2 }),
      ),
    )
    const { wrapper } = await mountView()
    await flushPromises()
    const lossText = wrapper.find('[data-test="metric-loss-rate"]').text()
    expect(lossText).toContain('0%')
    expect(lossText).not.toContain('--')
  })

  it('赢单金额 0 → "¥0"', async () => {
    server.use(http.get('*/api/dashboard', () => success({ ...SAMPLE_DASHBOARD, monthlyWonAmount: 0 })))
    const { wrapper } = await mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="metric-won-amount"]').text()).toContain('¥0')
  })
})

describe('内嵌线索工作区表格（spec R 内嵌线索工作区）', () => {
  it('SALES 工作区表格列出名下线索', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView({ mine: [SAMPLE_LEAD] })
    await flushPromises()

    const ws = wrapper.find('[data-test="workbench-leads"]')
    expect(ws.exists()).toBe(true)
    expect(ws.text()).toContain(SAMPLE_LEAD.customerName!)
  })

  it('ADMIN 工作区表格列出全部线索', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const allRow: LeadView = { ...SAMPLE_LEAD, id: 777, customerName: '全量线索客户' }
    const { wrapper } = await mountView({ admin: true, all: [allRow] })
    await flushPromises()

    const ws = wrapper.find('[data-test="workbench-leads"]')
    expect(ws.text()).toContain('全量线索客户')
  })

  it('点击表格行在抽屉内进入详情（渲染 LeadDetailPanel）', async () => {
    server.use(
      http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)),
      leadDetail(SAMPLE_LEAD),
      progressList([SAMPLE_PROGRESS]),
    )
    const { wrapper } = await mountView({ mine: [SAMPLE_LEAD] })
    await flushPromises()

    expect(wrapper.find('[data-test="lead-drawer"] .detail-desc').exists()).toBe(false)
    await wrapper.find('[data-test="workbench-leads"] .lead-link').trigger('click')
    await flushPromises()

    const drawer = wrapper.find('[data-test="lead-drawer"]')
    expect(drawer.find('.detail-desc').exists()).toBe(true)
    expect(drawer.text()).toContain(SAMPLE_LEAD.customerUsci!)
  })

  it('抽屉打开已结束线索不呈现写入口，且不含系统日志', async () => {
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

describe('今日提醒/待办（spec R 今日提醒/待办）', () => {
  it('SALES 公海非空时呈现建议认领条目', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView({ pool: [SAMPLE_POOL_LEAD] })
    await flushPromises()

    const reminders = wrapper.find('[data-test="workbench-reminders"]')
    expect(reminders.exists()).toBe(true)
    expect(reminders.text()).toContain(SAMPLE_POOL_LEAD.customerName!)
    expect(reminders.find('.reminder-claim').exists()).toBe(true)
  })

  it('SALES 名下存在长期未跟踪线索时呈现待跟踪条目', async () => {
    const stale: LeadView = { ...SAMPLE_LEAD, id: 321, customerName: '久未跟踪客户', lastTrackedAt: null }
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView({ mine: [stale] })
    await flushPromises()

    const reminders = wrapper.find('[data-test="workbench-reminders"]')
    expect(reminders.text()).toContain('久未跟踪客户')
  })

  it('认领入口不对 ADMIN 呈现', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView({ admin: true, pool: [SAMPLE_POOL_LEAD] })
    await flushPromises()

    const reminders = wrapper.find('[data-test="workbench-reminders"]')
    expect(reminders.find('.reminder-claim').exists()).toBe(false)
  })

  it('SALES 内联认领被抢先时提示"该线索已被认领"并刷新公海', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)), claimAlreadyClaimed())
    const warnSpy = vi.spyOn(Message, 'warning')
    const { wrapper } = await mountView({ pool: [SAMPLE_POOL_LEAD] })
    await flushPromises()

    // 抢先冲突后须重新拉取公海纠正本地态（spy store action，避免 msw runtime handler 覆盖顺序干扰）。
    const store = useLeadsStore()
    const poolSpy = vi.spyOn(store, 'loadPool')
    await wrapper.find('[data-test="workbench-reminders"] .reminder-claim').trigger('click')
    await flushPromises()

    expect(warnSpy.mock.calls.some((c) => String(c[0]).includes('已被认领'))).toBe(true)
    expect(poolSpy).toHaveBeenCalled()
  })

  it('SALES 内联认领成功后提示并刷新名下线索', async () => {
    server.use(
      http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)),
      claimSuccess({ ...SAMPLE_LEAD, id: SAMPLE_POOL_LEAD.id, ownerSalesId: 2 }),
    )
    const successSpy = vi.spyOn(Message, 'success')
    const { wrapper } = await mountView({ pool: [SAMPLE_POOL_LEAD] })
    await flushPromises()

    await wrapper.find('[data-test="workbench-reminders"] .reminder-claim').trigger('click')
    await flushPromises()

    expect(successSpy).toHaveBeenCalled()
  })
})

describe('首屏加载仅发只读查询（spec MODIFIED 只读看板）', () => {
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
