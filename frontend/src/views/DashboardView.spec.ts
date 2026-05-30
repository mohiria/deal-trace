import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import { http, HttpResponse, delay } from 'msw'
import { server } from '../test/msw/server'
import DashboardView from './DashboardView.vue'

/**
 * 工作台首屏看板（spec frontend-workbench：首屏只读看板 / 口径后端裁决 /
 * 金额精确呈现 / 流失率空值零值区分 / 加载失败态）。
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

const Stub = defineComponent({ render: () => h('div', 'login') })

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'workbench', component: DashboardView },
      { path: '/login', name: 'login', component: Stub },
    ],
  })
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = buildRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(DashboardView, { global: { plugins: [router, ArcoVue] } })
  return { wrapper, router }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('首屏渲染四项指标', () => {
  it('挂载即拉取并同屏渲染四项指标（值来自后端）', async () => {
    server.use(http.get('*/api/dashboard', () => success(SAMPLE_DASHBOARD)))
    const { wrapper } = await mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('今日新增线索')
    expect(text).toContain('公海待认领')
    expect(text).toContain('本月赢单金额')
    expect(text).toContain('本月流失率')
    // 值原样来自后端
    expect(text).toContain('4')
    expect(text).toContain('28')
    expect(text).toContain('¥150,000.5')
    expect(text).toContain('40%')
  })

  it('看板仅发起只读 GET，不发起任何写请求', async () => {
    const methods: string[] = []
    server.use(
      http.get('*/api/dashboard', ({ request }) => {
        methods.push(request.method)
        return success(SAMPLE_DASHBOARD)
      }),
    )
    await mountView()
    await flushPromises()
    expect(methods).toEqual(['GET'])
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
    // 尚未 flush：处于加载态
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
    expect(wrapper.text()).toContain('今日新增线索')
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
