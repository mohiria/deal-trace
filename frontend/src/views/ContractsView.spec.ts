import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import { success, accountsList } from '../test/msw/handlers'
import { useAuthStore } from '../stores/auth'
import type { AuthUser } from '../stores/auth'
import type { ContractRowView } from '../api/contracts'
import ContractsView from './ContractsView.vue'

/**
 * 合同记录浏览页（contract-view spec R1/R2/R3）：渲染列、金额千分位、公海赢单、筛选触发重载、
 * 成交销售筛选仅 ADMIN 可见、分页。可见范围隔离的权威断言在后端 ContractControllerListTest。
 */

const ADMIN: AuthUser = { id: 1, email: 'admin@dealtrace.local', name: '系统管理员', role: 'ADMIN', status: 'ENABLED' }
const SALES: AuthUser = { id: 2, email: 'sales@dealtrace.local', name: '林雨', role: 'SALES', status: 'ENABLED' }

function contractRow(over: Partial<ContractRowView> = {}): ContractRowView {
  return {
    leadId: 100,
    customerName: '示例建筑设计院',
    businessType: 'BIM咨询',
    contractAmount: '120000.50',
    signedDate: '2026-05-10',
    createdAt: '2026-05-10T10:00:00',
    dealSalesId: 2,
    dealSalesName: '林雨',
    ...over,
  }
}

/** 记录收到的请求 URL，供筛选断言。 */
let lastUrl: URL | null = null
function contractsPage(items: ContractRowView[], total = items.length, size = 20) {
  return http.get('*/api/contracts', ({ request }) => {
    lastUrl = new URL(request.url)
    return HttpResponse.json(success({ items, total, page: 1, size }))
  })
}

async function mountView(
  user: AuthUser,
  items: ContractRowView[] = [contractRow()],
  total?: number,
  size?: number,
): Promise<VueWrapper> {
  server.use(contractsPage(items, total ?? items.length, size ?? 20))
  if (user.role === 'ADMIN') {
    server.use(accountsList()) // 视图挂载时（仅 ADMIN）拉账号供成交销售筛选
  }
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.currentUser = user
  const wrapper = mount(ContractsView, {
    global: { plugins: [ArcoVue], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  lastUrl = null
})

describe('合同记录浏览（contract-view spec R1/R3）', () => {
  it('渲染列：客户、业务类型、金额、签订日期、成交销售、赢单时间', async () => {
    const wrapper = await mountView(ADMIN, [contractRow()])
    const text = wrapper.find('[data-test="contracts-table"]').text()
    expect(text).toContain('示例建筑设计院')
    expect(text).toContain('BIM咨询')
    expect(text).toContain('2026-05-10')
    expect(text).toContain('林雨')
  })

  it('合同金额千分位展示（金额为后端精确字符串，不丢精度）', async () => {
    const wrapper = await mountView(ADMIN, [contractRow({ contractAmount: '1234567.50' })])
    expect(wrapper.find('[data-test="contracts-table"]').text()).toContain('1,234,567.50')
  })

  it('公海赢单：成交销售展示"公海赢单"（后端组装）', async () => {
    const wrapper = await mountView(ADMIN, [contractRow({ dealSalesId: null, dealSalesName: '公海赢单' })])
    expect(wrapper.find('[data-test="contracts-table"]').text()).toContain('公海赢单')
  })

  it('关键词筛选后以 keyword 参数重载', async () => {
    const wrapper = await mountView(ADMIN, [contractRow()])
    const vm = wrapper.vm as unknown as { keyword: string }
    vm.keyword = '建筑'
    await flushPromises()
    expect(lastUrl?.searchParams.get('keyword')).toBe('建筑')
  })

  it('签订日期区间筛选后以 signedDateFrom/To 参数重载', async () => {
    const wrapper = await mountView(ADMIN, [contractRow()])
    const vm = wrapper.vm as unknown as { signedDateFrom?: string; signedDateTo?: string }
    vm.signedDateFrom = '2026-05-01'
    vm.signedDateTo = '2026-05-31'
    await flushPromises()
    expect(lastUrl?.searchParams.get('signedDateFrom')).toBe('2026-05-01')
    expect(lastUrl?.searchParams.get('signedDateTo')).toBe('2026-05-31')
  })

  it('成交销售筛选仅 ADMIN 可见', async () => {
    const adminWrapper = await mountView(ADMIN, [contractRow()])
    expect(adminWrapper.find('.contracts-filter-dealsales').exists()).toBe(true)

    const salesWrapper = await mountView(SALES, [contractRow()])
    expect(salesWrapper.find('.contracts-filter-dealsales').exists()).toBe(false)
  })

  it('total 超过 size 时呈现分页', async () => {
    const wrapper = await mountView(ADMIN, [contractRow()], 50, 20)
    expect(wrapper.find('[data-test="list-pagination"]').exists()).toBe(true)
  })
})
