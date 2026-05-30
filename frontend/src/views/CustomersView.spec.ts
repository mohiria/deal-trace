import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import {
  ADMIN_USER,
  SALES_USER,
  SAMPLE_CUSTOMER,
  customerList,
  customerSearch,
  createCustomerSuccess,
  createCustomerDuplicate,
  createCustomerValidation,
  duplicateCheckCanCreate,
  duplicateCheckBlockedActive,
  duplicateCheckWithHistoricalLost,
  createLeadSuccess,
  createLeadValidation,
} from '../test/msw/handlers'
import type { CustomerView } from '../api/customers'
import { useAuthStore } from '../stores/auth'
import CustomersView from './CustomersView.vue'

/**
 * 客户管理（spec R1 / R2 / R4）：列表/搜索、创建客户、新建线索+查重预检。
 * debounceMs=0 + tick() 让搜索去抖在测试中即时触发。
 */

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

async function mountView(role: typeof ADMIN_USER = ADMIN_USER): Promise<VueWrapper> {
  const store = useAuthStore()
  store.currentUser = role
  const wrapper = mount(CustomersView, {
    props: { debounceMs: 0 },
    global: { plugins: [ArcoVue] },
  })
  await flushPromises()
  return wrapper
}

/** 在新建线索弹窗中选中样例客户。 */
async function selectCustomer(wrapper: VueWrapper) {
  await wrapper.find('.cs-search').setValue('建筑')
  await tick()
  await flushPromises()
  await wrapper.find('.cs-option').trigger('click')
  await flushPromises()
}

/** 选中业务类型单选。 */
async function chooseType(wrapper: VueWrapper, text = 'BIM咨询') {
  const radios = wrapper.find('.lead-type').findAll('.arco-radio')
  const target = radios.find((r) => r.text() === text)
  if (!target) {
    throw new Error(`business type radio not found: ${text}`)
  }
  await target.find('input').setValue()
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

describe('客户列表与搜索（spec R1）', () => {
  it('无关键词渲染后端返回的客户列表', async () => {
    server.use(customerList([SAMPLE_CUSTOMER]))
    const wrapper = await mountView()
    expect(wrapper.text()).toContain(SAMPLE_CUSTOMER.name)
    expect(wrapper.text()).toContain(SAMPLE_CUSTOMER.usci)
  })

  it('输入关键词去抖后调 searchCustomers 并渲染匹配', async () => {
    const matched: CustomerView = { ...SAMPLE_CUSTOMER, id: 11, name: '中国建筑设计研究院' }
    server.use(customerSearch([matched], []))
    const wrapper = await mountView()

    await wrapper.find('.customer-search').setValue('建筑')
    await tick()
    await flushPromises()

    expect(wrapper.text()).toContain('中国建筑设计研究院')
  })

  it('关键词无命中展示空态', async () => {
    server.use(customerSearch([], []))
    const wrapper = await mountView()

    await wrapper.find('.customer-search').setValue('不存在xyz999')
    await tick()
    await flushPromises()

    expect(wrapper.find('.customers-empty').exists()).toBe(true)
  })

  it('不展示无意义的 Table/Modal 标签并支持分页', async () => {
    const rows = Array.from({ length: 12 }, (_, index) => ({
      ...SAMPLE_CUSTOMER,
      id: 1000 + index,
      name: index === 11 ? '星河客户集团' : `分页客户${index + 1}`,
    }))
    server.use(customerList(rows))
    const wrapper = await mountView()

    expect(wrapper.find('.customers-toolbar').text()).not.toContain('Table')
    expect(wrapper.find('.customers-toolbar').text()).not.toContain('Modal')
    expect(wrapper.find('[data-test="list-pagination"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('星河客户集团')
  })
})

describe('创建客户（spec R2）', () => {
  it('name 或 usci 为空时即时拦截不发请求', async () => {
    let posted = false
    server.use(
      customerList([]),
      http.post('*/api/customers', () => {
        posted = true
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_CUSTOMER })
      }),
    )
    const wrapper = await mountView()

    await wrapper.find('.create-customer-open').trigger('click')
    await flushPromises()
    await wrapper.find('.customer-confirm').trigger('click')
    await flushPromises()

    expect(posted).toBe(false)
  })

  it('合法提交成功后新客户进入列表', async () => {
    const created: CustomerView = { id: 99, name: '新客户公司', usci: '91110000MA9999999X', createdAt: '2026-05-30T10:00:00' }
    server.use(customerList([]), createCustomerSuccess(created))
    const wrapper = await mountView()

    await wrapper.find('.create-customer-open').trigger('click')
    await flushPromises()
    await wrapper.find('.customer-name input').setValue('新客户公司')
    await wrapper.find('.customer-usci input').setValue('91110000MA9999999X')
    await wrapper.find('.customer-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('新客户公司')
  })

  it('DUPLICATE_CUSTOMER 时提示已存在且不伪造成功', async () => {
    server.use(customerList([]), createCustomerDuplicate())
    const errSpy = vi.spyOn(Message, 'error')
    const wrapper = await mountView()

    await wrapper.find('.create-customer-open').trigger('click')
    await flushPromises()
    await wrapper.find('.customer-name input').setValue('重复公司')
    await wrapper.find('.customer-usci input').setValue('91110000MA0000000X')
    await wrapper.find('.customer-confirm').trigger('click')
    await flushPromises()

    expect(errSpy).toHaveBeenCalled()
    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('已存在'))).toBe(true)
  })

  it('VALIDATION_ERROR 透传后端 message', async () => {
    server.use(customerList([]), createCustomerValidation('统一社会信用代码校验未通过'))
    const errSpy = vi.spyOn(Message, 'error')
    const wrapper = await mountView()

    await wrapper.find('.create-customer-open').trigger('click')
    await flushPromises()
    await wrapper.find('.customer-name input').setValue('某公司')
    await wrapper.find('.customer-usci input').setValue('91110000MA000000XX')
    await wrapper.find('.customer-confirm').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('校验未通过'))).toBe(true)
  })
})

describe('新建线索 + 查重预检（spec R4）', () => {
  it('表单不渲染业务年度与初始阶段字段', async () => {
    server.use(customerList([]))
    const wrapper = await mountView()
    await wrapper.find('.create-lead-open').trigger('click')
    await flushPromises()

    expect(wrapper.find('.lead-year').exists()).toBe(false)
    expect(wrapper.find('.lead-stage').exists()).toBe(false)
  })

  it('联系电话非法时即时拦截不发创建请求', async () => {
    let posted = false
    server.use(
      customerList([SAMPLE_CUSTOMER]),
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      duplicateCheckCanCreate(),
      http.post('*/api/leads', () => {
        posted = true
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_CUSTOMER })
      }),
    )
    const wrapper = await mountView(SALES_USER)

    await wrapper.find('.create-lead-open').trigger('click')
    await flushPromises()
    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('abc')
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(posted).toBe(false)
  })

  it('预检阻塞（DUPLICATE_ACTIVE_LEAD）时提示并禁止提交', async () => {
    let posted = false
    server.use(
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      customerList([SAMPLE_CUSTOMER]),
      duplicateCheckBlockedActive(),
      http.post('*/api/leads', () => {
        posted = true
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_CUSTOMER })
      }),
    )
    const wrapper = await mountView(SALES_USER)

    await wrapper.find('.create-lead-open').trigger('click')
    await flushPromises()
    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await flushPromises()

    expect(wrapper.find('.lead-block').exists()).toBe(true)

    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('13812345678')
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(posted).toBe(false)
  })

  it('仅历史流失记录时允许新建并展示流失原因与时间', async () => {
    server.use(
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      customerList([SAMPLE_CUSTOMER]),
      duplicateCheckWithHistoricalLost(),
    )
    const wrapper = await mountView(SALES_USER)

    await wrapper.find('.create-lead-open').trigger('click')
    await flushPromises()
    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await flushPromises()

    const block = wrapper.find('.historical-lost')
    expect(block.exists()).toBe(true)
    expect(block.text()).toContain('价格过高')
    expect(block.text()).toContain('2026-03-10')
    expect(wrapper.find('.lead-block').exists()).toBe(false)
  })

  it('Sales 选择放入公海后提交带 assignToPool', async () => {
    let captured: Record<string, unknown> | null = null
    server.use(
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      customerList([SAMPLE_CUSTOMER]),
      duplicateCheckCanCreate(),
      http.post('*/api/leads', async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SAMPLE_CUSTOMER })
      }),
    )
    const wrapper = await mountView(SALES_USER)

    await wrapper.find('.create-lead-open').trigger('click')
    await flushPromises()
    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('13812345678')
    const poolRadio = wrapper.find('.lead-owner').findAll('.arco-radio').find((r) => r.text().includes('公海'))
    await poolRadio!.find('input').setValue()
    await flushPromises()
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(captured).not.toBeNull()
    expect(captured!.assignToPool).toBe(true)
  })

  it('创建被后端业务错误拒绝时展示语义不伪造成功', async () => {
    server.use(
      customerSearch([SAMPLE_CUSTOMER], [SAMPLE_CUSTOMER]),
      customerList([SAMPLE_CUSTOMER]),
      duplicateCheckCanCreate(),
      createLeadValidation('联系电话格式非法'),
    )
    const errSpy = vi.spyOn(Message, 'error')
    const wrapper = await mountView(SALES_USER)

    await wrapper.find('.create-lead-open').trigger('click')
    await flushPromises()
    await selectCustomer(wrapper)
    await chooseType(wrapper)
    await wrapper.find('.lead-contact-name input').setValue('王工')
    await wrapper.find('.lead-contact-phone input').setValue('13812345678')
    await wrapper.find('.lead-confirm').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('联系电话'))).toBe(true)
  })

  // 引用以避免未使用告警
  void [createLeadSuccess]
})
