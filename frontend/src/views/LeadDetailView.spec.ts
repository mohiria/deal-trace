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
  accountsList,
  assignSuccess,
  assignAlreadyOwned,
  recallSuccess,
  recallAlreadyPool,
  transferSuccess,
  transferSameOwner,
  ownershipEndedReadonly,
} from '../test/msw/handlers'
import type { LeadView, ProgressLogView } from '../api/leads'
import type { AccountView } from '../api/accounts'
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

/** 归属候选样例：两个启用 Sales（id2 林雨 / id3 赵磊）+ 一个停用 Sales + Admin。enabledSales = [林雨, 赵磊]。 */
const ACCT_ADMIN: AccountView = { id: 1, email: 'admin@dealtrace.local', name: '系统管理员', role: 'ADMIN', status: 'ENABLED', createdAt: '2026-04-01T09:00:00' }
const ACCT_SALES_A: AccountView = { id: 2, email: 'a@dealtrace.local', name: '林雨', role: 'SALES', status: 'ENABLED', createdAt: '2026-05-01T09:00:00' }
const ACCT_SALES_B: AccountView = { id: 3, email: 'b@dealtrace.local', name: '赵磊', role: 'SALES', status: 'ENABLED', createdAt: '2026-05-02T09:00:00' }
const ACCT_SALES_OFF: AccountView = { id: 4, email: 'c@dealtrace.local', name: '停用员', role: 'SALES', status: 'DISABLED', createdAt: '2026-05-03T09:00:00' }

async function mountView(opts: MountOpts = {}): Promise<VueWrapper> {
  const role = opts.role ?? SALES_USER
  const lead = opts.lead ?? SAMPLE_LEAD
  const rows = opts.progressRows ?? [SAMPLE_PROGRESS]
  // Admin 挂载会拉账号列表作归属候选（D4）；始终注册避免 onUnhandledRequest:'error'。
  server.use(leadDetail(lead), progressList(rows), accountsList([ACCT_ADMIN, ACCT_SALES_A, ACCT_SALES_B, ACCT_SALES_OFF]))

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

describe('Admin 归属操作区（spec：分配/回收/转移）', () => {
  const POOL_LEAD: LeadView = { ...SAMPLE_LEAD, ownerSalesId: null }
  const OWNED_LEAD: LeadView = { ...SAMPLE_LEAD, ownerSalesId: 2 }
  const WON_LEAD: LeadView = { ...SAMPLE_LEAD, stage: '已赢单', wonAt: '2026-05-10T09:00:00' }

  it('归属区仅对 Admin 呈现（SALES 不呈现）', async () => {
    const wrapper = await mountView({ role: SALES_USER, lead: OWNED_LEAD })
    expect(wrapper.find('.ownership-actions').exists()).toBe(false)
  })

  it('归属区不对已结束线索呈现', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: WON_LEAD })
    expect(wrapper.find('.ownership-actions').exists()).toBe(false)
  })

  it('公海线索（无归属）仅呈现分配入口', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: POOL_LEAD })
    expect(wrapper.find('.ownership-actions').exists()).toBe(true)
    expect(wrapper.find('.assign-open').exists()).toBe(true)
    expect(wrapper.find('.recall-btn').exists()).toBe(false)
    expect(wrapper.find('.transfer-open').exists()).toBe(false)
  })

  it('有归属线索呈现回收与转移入口', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: OWNED_LEAD })
    expect(wrapper.find('.recall-btn').exists()).toBe(true)
    expect(wrapper.find('.transfer-open').exists()).toBe(true)
    expect(wrapper.find('.assign-open').exists()).toBe(false)
  })

  it('分配候选仅含启用 Sales（排除 Admin 与停用）', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: POOL_LEAD })
    await wrapper.find('.assign-open').trigger('click')
    await flushPromises()
    const candidates = wrapper.find('.assign-target').findAll('.arco-radio')
    expect(candidates).toHaveLength(2) // 林雨 + 赵磊
    const text = wrapper.find('.assign-target').text()
    expect(text).toContain('林雨')
    expect(text).toContain('赵磊')
    expect(text).not.toContain('停用员')
    expect(text).not.toContain('系统管理员')
  })

  it('转移候选排除当前归属', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: OWNED_LEAD })
    await wrapper.find('.transfer-open').trigger('click')
    await flushPromises()
    const candidates = wrapper.find('.transfer-target').findAll('.arco-radio')
    expect(candidates).toHaveLength(1) // 排除当前归属 id2 林雨，仅余赵磊
    expect(wrapper.find('.transfer-target').text()).toContain('赵磊')
    expect(wrapper.find('.transfer-target').text()).not.toContain('林雨')
  })

  it('分配成功后归属更新', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: POOL_LEAD })
    server.use(assignSuccess({ ...SAMPLE_LEAD, ownerSalesId: 2 }))

    await wrapper.find('.assign-open').trigger('click')
    await flushPromises()
    await clickRadio(wrapper, '.assign-target', '林雨')
    await wrapper.find('.assign-confirm').trigger('click')
    await flushPromises()

    expect(useLeadsStore().currentLead?.ownerSalesId).toBe(2)
  })

  it('分配已有归属被 VALIDATION_ERROR 拒绝时提示并据后端刷新', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: POOL_LEAD })
    const store = useLeadsStore()
    const errSpy = vi.spyOn(Message, 'error')
    const reloadSpy = vi.spyOn(store, 'loadLead')
    server.use(assignAlreadyOwned('线索已有归属，请使用转移'))

    await wrapper.find('.assign-open').trigger('click')
    await flushPromises()
    await clickRadio(wrapper, '.assign-target', '林雨')
    await wrapper.find('.assign-confirm').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('请使用转移'))).toBe(true)
    expect(reloadSpy).toHaveBeenCalledWith(100)
  })

  it('回收成功后线索进入公海', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: OWNED_LEAD })
    server.use(recallSuccess({ ...SAMPLE_LEAD, ownerSalesId: null }))

    await wrapper.find('.recall-btn').trigger('click')
    await flushPromises()

    expect(useLeadsStore().currentLead?.ownerSalesId).toBeNull()
  })

  it('回收已在公海被 VALIDATION_ERROR 拒绝时提示并刷新', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: OWNED_LEAD })
    const store = useLeadsStore()
    const errSpy = vi.spyOn(Message, 'error')
    const reloadSpy = vi.spyOn(store, 'loadLead')
    server.use(recallAlreadyPool('线索已在公海，无需回收'))

    await wrapper.find('.recall-btn').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('无需回收'))).toBe(true)
    expect(reloadSpy).toHaveBeenCalledWith(100)
  })

  it('转移成功后归属更新为新 Sales', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: OWNED_LEAD })
    server.use(transferSuccess({ ...SAMPLE_LEAD, ownerSalesId: 3 }))

    await wrapper.find('.transfer-open').trigger('click')
    await flushPromises()
    await clickRadio(wrapper, '.transfer-target', '赵磊')
    await wrapper.find('.transfer-confirm').trigger('click')
    await flushPromises()

    expect(useLeadsStore().currentLead?.ownerSalesId).toBe(3)
  })

  it('转移目标相同被 VALIDATION_ERROR 拒绝时提示并刷新', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: OWNED_LEAD })
    const store = useLeadsStore()
    const errSpy = vi.spyOn(Message, 'error')
    const reloadSpy = vi.spyOn(store, 'loadLead')
    server.use(transferSameOwner('目标销售与当前归属相同'))

    await wrapper.find('.transfer-open').trigger('click')
    await flushPromises()
    await clickRadio(wrapper, '.transfer-target', '赵磊')
    await wrapper.find('.transfer-confirm').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('当前归属相同'))).toBe(true)
    expect(reloadSpy).toHaveBeenCalledWith(100)
  })

  it('归属操作遇 LEAD_ENDED_READONLY 提示并刷新只读态', async () => {
    const wrapper = await mountView({ role: ADMIN_USER, lead: POOL_LEAD })
    const store = useLeadsStore()
    const errSpy = vi.spyOn(Message, 'error')
    const reloadSpy = vi.spyOn(store, 'loadLead')
    server.use(ownershipEndedReadonly('assign'))

    await wrapper.find('.assign-open').trigger('click')
    await flushPromises()
    await clickRadio(wrapper, '.assign-target', '林雨')
    await wrapper.find('.assign-confirm').trigger('click')
    await flushPromises()

    expect(errSpy).toHaveBeenCalled()
    expect(reloadSpy).toHaveBeenCalledWith(100)
  })
})
