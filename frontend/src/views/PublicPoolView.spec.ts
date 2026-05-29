import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { server } from '../test/msw/server'
import {
  ADMIN_USER,
  SALES_USER,
  SAMPLE_POOL_LEAD,
  claimAlreadyClaimed,
  claimSuccess,
  poolList,
} from '../test/msw/handlers'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import PublicPoolView from './PublicPoolView.vue'

/**
 * 公海（spec R2）：脱敏/明文按角色、认领入口显隐、认领成功移出、并发冲突提示+刷新。
 */

async function mountView(role: typeof ADMIN_USER): Promise<VueWrapper> {
  const store = useAuthStore()
  store.currentUser = role
  const wrapper = mount(PublicPoolView, { global: { plugins: [ArcoVue] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

describe('按角色展示', () => {
  it('Sales 看到脱敏电话且有认领入口', async () => {
    server.use(poolList([SAMPLE_POOL_LEAD]))
    const wrapper = await mountView(SALES_USER)

    expect(wrapper.text()).toContain('139****4321')
    expect(wrapper.find('.claim-btn').exists()).toBe(true)
  })

  it('Admin 看到明文电话且无认领入口', async () => {
    server.use(poolList([{ ...SAMPLE_POOL_LEAD, contactPhone: '13912344321' }]))
    const wrapper = await mountView(ADMIN_USER)

    expect(wrapper.text()).toContain('13912344321')
    expect(wrapper.find('.claim-btn').exists()).toBe(false)
  })
})

describe('认领', () => {
  it('认领成功后线索移出公海', async () => {
    server.use(poolList([SAMPLE_POOL_LEAD]), claimSuccess())
    const wrapper = await mountView(SALES_USER)
    const leads = useLeadsStore()
    expect(leads.pool).toHaveLength(1)

    await wrapper.find('.claim-btn').trigger('click')
    await flushPromises()

    expect(leads.pool.find((l) => l.id === SAMPLE_POOL_LEAD.id)).toBeUndefined()
  })

  it('并发冲突 LEAD_ALREADY_CLAIMED 提示并刷新公海', async () => {
    server.use(poolList([SAMPLE_POOL_LEAD]), claimAlreadyClaimed())
    const warnSpy = vi.spyOn(Message, 'warning')
    const wrapper = await mountView(SALES_USER)
    const leads = useLeadsStore()
    const refreshSpy = vi.spyOn(leads, 'loadPool')

    await wrapper.find('.claim-btn').trigger('click')
    await flushPromises()

    expect(warnSpy).toHaveBeenCalledWith('该线索已被认领')
    // 冲突后刷新公海，且该线索仍在（未被计入名下）
    expect(refreshSpy).toHaveBeenCalled()
    expect(leads.pool.find((l) => l.id === SAMPLE_POOL_LEAD.id)).toBeDefined()
  })
})
