import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { server } from '../test/msw/server'
import {
  ADMIN_USER,
  SALES_USER,
  SAMPLE_LEAD,
  SAMPLE_PROGRESS,
  addProgressSuccess,
  endedReadonly,
  leadDetail,
  loseSuccess,
  mineLeads,
  progressList,
  releaseSuccess,
  stageSuccess,
  winSuccess,
} from '../test/msw/handlers'
import type { LeadView, ProgressLogView } from '../api/leads'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import LeadDetailView from './LeadDetailView.vue'

/**
 * 线索详情（spec R3–R7）。SALES_USER.id===2 与 SAMPLE_LEAD.ownerSalesId===2 → 名下。
 */

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/leads/:id', name: 'lead-detail', component: LeadDetailView }],
  })
}

interface MountOpts {
  role?: typeof ADMIN_USER
  lead?: LeadView
  progressRows?: ProgressLogView[]
}

async function mountView(opts: MountOpts = {}): Promise<VueWrapper> {
  const role = opts.role ?? SALES_USER
  const lead = opts.lead ?? SAMPLE_LEAD
  const rows = opts.progressRows ?? [SAMPLE_PROGRESS]
  server.use(leadDetail(lead), progressList(rows))

  const store = useAuthStore()
  store.currentUser = role
  const router = buildRouter()
  await router.push('/leads/100')
  await router.isReady()
  const wrapper = mount(LeadDetailView, { global: { plugins: [router, ArcoVue] } })
  await flushPromises()
  return wrapper
}

async function clickRadio(wrapper: VueWrapper, scope: string, text: string) {
  // 限定作用域：进度跟踪方式与流失原因都含「其他」单选项，须按容器区分。
  const labels = wrapper.find(scope).findAll('.arco-radio')
  const target = labels.find((l) => l.text() === text)
  if (!target) {
    throw new Error(`radio not found in ${scope}: ${text}`)
  }
  // Arco radio 通过内部 input 的 change 驱动 v-model
  await target.find('input').setValue()
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

describe('详情与进度展示（R3）', () => {
  it('渲染客户/业务/归属/阶段', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain(SAMPLE_LEAD.customerName!)
    expect(wrapper.text()).toContain(SAMPLE_LEAD.customerUsci!)
    expect(wrapper.text()).toContain(SAMPLE_LEAD.contactName!)
    expect(wrapper.text()).toContain('未触达')
  })

  it('已流失线索展示流失原因与说明', async () => {
    const lost: LeadView = {
      ...SAMPLE_LEAD,
      stage: '已流失',
      loseReason: '价格过高',
      loseNote: '预算不足',
      lostAt: '2026-05-10T09:00:00',
    }
    const wrapper = await mountView({ lead: lost })
    expect(wrapper.text()).toContain('价格过高')
    expect(wrapper.text()).toContain('预算不足')
  })

  it('进度按数组顺序（后端倒序）展示且无编辑/删除入口', async () => {
    const newer: ProgressLogView = { ...SAMPLE_PROGRESS, id: 999, content: '二次跟进', trackTime: '2026-05-03T08:00:00' }
    const wrapper = await mountView({ progressRows: [newer, SAMPLE_PROGRESS] })
    const items = wrapper.findAll('.progress-item-content')
    expect(items[0]?.text()).toBe('二次跟进')
    expect(items[1]?.text()).toBe(SAMPLE_PROGRESS.content)
    expect(wrapper.html()).not.toContain('删除')
    expect(wrapper.html()).not.toContain('编辑')
  })
})

describe('追加进度（R4）', () => {
  it('空跟踪内容拦截且不发请求', async () => {
    const wrapper = await mountView()
    const store = useLeadsStore()
    const spy = vi.spyOn(store, 'addLeadProgress')

    await wrapper.find('.progress-form').trigger('submit')
    await flushPromises()

    expect(spy).not.toHaveBeenCalled()
  })

  it('合法追加成功后新记录置顶且 lastTrackedAt 刷新', async () => {
    const newer: ProgressLogView = { ...SAMPLE_PROGRESS, id: 999, content: '电话已接通', trackTime: '2026-05-09T11:00:00' }
    const wrapper = await mountView()
    server.use(addProgressSuccess(newer))

    await wrapper.find('.progress-content textarea').setValue('电话已接通')
    await wrapper.find('.progress-form').trigger('submit')
    await flushPromises()

    const store = useLeadsStore()
    expect(store.progress[0]?.id).toBe(999)
    expect(store.currentLead?.lastTrackedAt).toBe(newer.trackTime)
  })
})

describe('阶段/赢单/流失（R5）', () => {
  it('点击阶段按钮变更阶段', async () => {
    const wrapper = await mountView()
    server.use(stageSuccess())

    await wrapper.find('.stage-btn').trigger('click')
    await flushPromises()

    expect(useLeadsStore().currentLead?.stage).toBe('方案报价')
  })

  it('赢单金额非法时拦截不发请求', async () => {
    const wrapper = await mountView()
    const store = useLeadsStore()
    const spy = vi.spyOn(store, 'winLead')

    await wrapper.find('.win-open').trigger('click')
    await wrapper.find('.win-amount input').setValue('1000.555')
    await wrapper.find('.win-date input').setValue('')
    await wrapper.find('.win-confirm').trigger('click')
    await flushPromises()

    expect(spy).not.toHaveBeenCalled()
  })

  it('合法赢单后阶段=已赢单且预览千分位', async () => {
    const wrapper = await mountView()
    server.use(winSuccess())

    await wrapper.find('.win-open').trigger('click')
    await wrapper.find('.win-amount input').setValue('1234567.5')
    await flushPromises()
    expect(wrapper.find('.win-preview').text()).toContain('1,234,567.5')

    await wrapper.find('.win-date input').setValue('2026-05-10')
    await wrapper.find('.win-confirm').trigger('click')
    await flushPromises()

    expect(useLeadsStore().currentLead?.stage).toBe('已赢单')
  })

  it('流失原因为其他且说明为空时拦截', async () => {
    const wrapper = await mountView()
    const store = useLeadsStore()
    const spy = vi.spyOn(store, 'loseLead')

    await wrapper.find('.lose-open').trigger('click')
    await flushPromises()
    await clickRadio(wrapper, '.lose-reason', '其他')
    await wrapper.find('.lose-confirm').trigger('click')
    await flushPromises()

    expect(spy).not.toHaveBeenCalled()
  })

  it('流失原因+说明齐备后成功', async () => {
    const wrapper = await mountView()
    server.use(loseSuccess())

    await wrapper.find('.lose-open').trigger('click')
    await flushPromises()
    await clickRadio(wrapper, '.lose-reason', '其他')
    await wrapper.find('.lose-note textarea').setValue('客户预算取消')
    await wrapper.find('.lose-confirm').trigger('click')
    await flushPromises()

    expect(useLeadsStore().currentLead?.stage).toBe('已流失')
  })
})

describe('主动退回公海（R6）', () => {
  it('退回备注为空时拦截', async () => {
    const wrapper = await mountView()
    const store = useLeadsStore()
    const spy = vi.spyOn(store, 'release')

    await wrapper.find('.release-open').trigger('click')
    await wrapper.find('.release-confirm').trigger('click')
    await flushPromises()

    expect(spy).not.toHaveBeenCalled()
  })

  it('退回成功后线索离开名下', async () => {
    const wrapper = await mountView()
    const store = useLeadsStore()
    server.use(mineLeads([SAMPLE_LEAD]), releaseSuccess({ ...SAMPLE_LEAD, ownerSalesId: null }))
    await store.loadMyLeads()

    await wrapper.find('.release-open').trigger('click')
    await wrapper.find('.release-note textarea').setValue('客户暂无预算')
    await wrapper.find('.release-confirm').trigger('click')
    await flushPromises()

    expect(store.myLeads.find((l) => l.id === SAMPLE_LEAD.id)).toBeUndefined()
  })
})

describe('闭单只读（R7）', () => {
  it('已结束线索不呈现任何写入口', async () => {
    const won: LeadView = { ...SAMPLE_LEAD, stage: '已赢单', wonAt: '2026-05-10T09:00:00' }
    const wrapper = await mountView({ lead: won })

    expect(wrapper.find('.progress-form').exists()).toBe(false)
    expect(wrapper.find('.stage-btn').exists()).toBe(false)
    expect(wrapper.find('.win-open').exists()).toBe(false)
    expect(wrapper.find('.lose-open').exists()).toBe(false)
    expect(wrapper.find('.release-open').exists()).toBe(false)
  })

  it('写操作遇 LEAD_ENDED_READONLY 提示并刷新只读态', async () => {
    const wrapper = await mountView()
    const store = useLeadsStore()
    const errSpy = vi.spyOn(Message, 'error')
    const reloadSpy = vi.spyOn(store, 'loadLead')
    server.use(endedReadonly('progress'))

    await wrapper.find('.progress-content textarea').setValue('尝试追加')
    await wrapper.find('.progress-form').trigger('submit')
    await flushPromises()

    expect(errSpy).toHaveBeenCalled()
    expect(reloadSpy).toHaveBeenCalledWith(100)
  })

  it('Admin 不呈现进度追加与退回（SALES 名下专属）但可标记赢单/流失', async () => {
    const wrapper = await mountView({ role: ADMIN_USER })
    expect(wrapper.find('.progress-form').exists()).toBe(false)
    expect(wrapper.find('.release-open').exists()).toBe(false)
    expect(wrapper.find('.win-open').exists()).toBe(true)
  })
})
