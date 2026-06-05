import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import { success } from '../test/msw/handlers'
import type { SystemLogView } from '../api/systemLogs'
import SystemLogsView from './SystemLogsView.vue'

/**
 * 系统日志全局浏览页（view-system-log spec R2）：渲染、筛选触发重载、分页。
 * ADMIN-only 导航可见性由 AppShell.spec（visibleSections）覆盖；此处聚焦页面行为。
 */

function logRow(over: Partial<SystemLogView> = {}): SystemLogView {
  return {
    id: 1,
    action: 'LEAD_TRANSFER',
    actionLabel: '转移',
    operatorName: '管理员',
    createdAt: '2026-05-31T10:00:00',
    targetType: 'LEAD',
    targetId: 7,
    leadId: 7,
    detail: { fromOwnerName: '销售乙', toOwnerName: '销售甲' },
    summaryFallback: null,
    ...over,
  }
}

/** 记录收到的请求 URL，供筛选断言。 */
let lastUrl: URL | null = null
function systemLogsPage(items: SystemLogView[], total = items.length, size = 20) {
  return http.get('*/api/admin/system-logs', ({ request }) => {
    lastUrl = new URL(request.url)
    return HttpResponse.json(success({ items, total, page: 1, size }))
  })
}

async function mountView(items: SystemLogView[] = [logRow()], total?: number, size?: number): Promise<VueWrapper> {
  server.use(systemLogsPage(items, total ?? items.length, size ?? 20))
  const wrapper = mount(SystemLogsView, {
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

describe('系统日志全局浏览（spec R2）', () => {
  it('渲染条目：动作标签、操作人、结构化摘要（当前姓名）', async () => {
    const wrapper = await mountView([logRow()])
    const text = wrapper.find('[data-test="systemlog-table"]').text()
    expect(text).toContain('转移')
    expect(text).toContain('管理员')
    // 归属按当前姓名组装展示
    expect(text).toContain('销售乙')
    expect(text).toContain('销售甲')
  })

  it('时间列统一渲染为秒级（去 T、到秒）', async () => {
    const wrapper = await mountView([logRow({ createdAt: '2026-05-31T10:00:00' })])
    const text = wrapper.find('[data-test="systemlog-table"]').text()
    expect(text).toContain('2026-05-31 10:00:00')
    expect(text).not.toContain('2026-05-31T10:00:00')
  })

  it('赢单金额千分位展示（金额为后端精确字符串）', async () => {
    const wrapper = await mountView([
      logRow({ action: 'LEAD_WIN', actionLabel: '标记赢单', detail: { contractAmount: '1234567.50', signedDate: '2026-05-31' } }),
    ])
    expect(wrapper.find('[data-test="systemlog-table"]').text()).toContain('1,234,567.50')
  })

  it('detail 为空（旧行）回退展示 summaryFallback', async () => {
    const wrapper = await mountView([
      logRow({ detail: null, summaryFallback: '历史 freetext 摘要' }),
    ])
    expect(wrapper.find('[data-test="systemlog-table"]').text()).toContain('历史 freetext 摘要')
  })

  it('选择动作筛选后以 action 参数重载', async () => {
    const wrapper = await mountView([logRow()])
    // 直接驱动响应式 ref（a-select 下拉渲染到 body，避免 teleport 取值脆弱）
    const vm = wrapper.vm as unknown as { action: string }
    vm.action = 'LEAD_WIN'
    await flushPromises()
    expect(lastUrl?.searchParams.get('action')).toBe('LEAD_WIN')
  })

  it('total 超过 size 时呈现分页', async () => {
    const wrapper = await mountView([logRow()], 50, 20)
    expect(wrapper.find('[data-test="list-pagination"]').exists()).toBe(true)
  })
})
