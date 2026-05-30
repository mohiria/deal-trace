import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { createPinia, setActivePinia } from 'pinia'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import {
  SALES_USER,
  SAMPLE_CUSTOMER,
  customerSearch,
  createLeadValidation,
  duplicateCheckBlockedActive,
  duplicateCheckCanCreate,
  duplicateCheckWithHistoricalLost,
} from '../test/msw/handlers'
import { useAuthStore } from '../stores/auth'
import CreateLeadModal from './CreateLeadModal.vue'

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

async function mountModal() {
  useAuthStore().currentUser = SALES_USER
  const wrapper = mount(CreateLeadModal, {
    props: { visible: true, debounceMs: 0 },
    global: { plugins: [ArcoVue] },
  })
  await flushPromises()
  return wrapper
}

async function selectCustomer(wrapper: Awaited<ReturnType<typeof mountModal>>) {
  await wrapper.find('.cs-search').setValue('建筑')
  await tick()
  await flushPromises()
  await wrapper.find('.cs-option').trigger('click')
  await flushPromises()
}

async function chooseType(wrapper: Awaited<ReturnType<typeof mountModal>>) {
  const target = wrapper.find('.lead-type').findAll('.arco-radio').find((r) => r.text() === 'BIM咨询')
  if (!target) throw new Error('business type radio not found')
  await target.find('input').setValue()
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

describe('CreateLeadModal', () => {
  it('展示统一的新建线索表单且不展示业务年度和初始阶段字段', async () => {
    server.use(customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]))
    const wrapper = await mountModal()

    expect(wrapper.find('[data-test="create-lead-modal"]').exists()).toBe(true)
    expect(wrapper.find('.lead-year').exists()).toBe(false)
    expect(wrapper.find('.lead-stage').exists()).toBe(false)
  })

  it('查重阻断时展示阻断提示并禁止提交 createLead', async () => {
    let posted = false
    server.use(
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      duplicateCheckBlockedActive(),
      http.post('*/api/leads', () => {
        posted = true
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: {} })
      }),
    )
    const wrapper = await mountModal()

    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('13812345678')
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.find('.lead-block').exists()).toBe(true)
    expect(posted).toBe(false)
  })

  it('仅有历史流失时展示提示且允许提交', async () => {
    let captured: Record<string, unknown> | null = null
    server.use(
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      duplicateCheckWithHistoricalLost(),
      http.post('*/api/leads', async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: {} })
      }),
    )
    const wrapper = await mountModal()

    await selectCustomer(wrapper)
    await chooseType(wrapper)
    expect(wrapper.find('.historical-lost').text()).toContain('价格过高')

    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('13812345678')
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(captured).not.toBeNull()
    expect(captured!['contactName']).toBe('王工')
  })

  it('Sales 选择放入公海后提交 assignToPool', async () => {
    let captured: Record<string, unknown> | null = null
    server.use(
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      duplicateCheckCanCreate(),
      http.post('*/api/leads', async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: {} })
      }),
    )
    const wrapper = await mountModal()

    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('13812345678')
    const poolRadio = wrapper.find('.lead-owner').findAll('.arco-radio').find((r) => r.text().includes('公海'))
    await poolRadio!.find('input').setValue()
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(captured).not.toBeNull()
    expect(captured!['assignToPool']).toBe(true)
  })

  it('后端业务错误透传错误提示', async () => {
    server.use(customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]), duplicateCheckCanCreate(), createLeadValidation('联系电话格式非法'))
    const errSpy = vi.spyOn(Message, 'error')
    const wrapper = await mountModal()

    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('13812345678')
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('联系电话'))).toBe(true)
  })
})
