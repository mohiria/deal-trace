import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { server } from '../test/msw/server'
import { SAMPLE_CUSTOMER, customerSearch } from '../test/msw/handlers'
import type { CustomerView } from '../api/customers'
import CustomerSelect from './CustomerSelect.vue'

/**
 * 客户可搜索下拉选择器（spec R3 / D4）：关键词远程搜索既有客户、选中抛值、无"边搜边建"捷径。
 * debounceMs=0 + tick() 让去抖在测试中立即触发，避免 fake timer 与 axios 交互的脆弱性。
 */

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

function mountSelect() {
  return mount(CustomerSelect, {
    props: { modelValue: null, debounceMs: 0 },
    global: { plugins: [ArcoVue] },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

describe('客户可搜索下拉（spec R3）', () => {
  it('输入关键词去抖后调 searchCustomers 并渲染候选', async () => {
    server.use(customerSearch([SAMPLE_CUSTOMER]))
    const wrapper = mountSelect()

    await wrapper.find('.cs-search').setValue('建筑')
    await tick()
    await flushPromises()

    const options = wrapper.findAll('.cs-option')
    expect(options.length).toBe(1)
    expect(options[0]?.text()).toContain(SAMPLE_CUSTOMER.name)
  })

  it('选中候选后 emit update:modelValue 与 select 客户对象', async () => {
    server.use(customerSearch([SAMPLE_CUSTOMER]))
    const wrapper = mountSelect()

    await wrapper.find('.cs-search').setValue('建筑')
    await tick()
    await flushPromises()
    await wrapper.find('.cs-option').trigger('click')

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([SAMPLE_CUSTOMER.id])
    const selectEvt = wrapper.emitted('select')?.[0]?.[0] as CustomerView
    expect(selectEvt.id).toBe(SAMPLE_CUSTOMER.id)
    expect(selectEvt.name).toBe(SAMPLE_CUSTOMER.name)
  })

  it('无匹配时不提供"以关键词新建客户"的捷径', async () => {
    server.use(customerSearch([], []))
    const wrapper = mountSelect()

    await wrapper.find('.cs-search').setValue('不存在xyz')
    await tick()
    await flushPromises()

    expect(wrapper.findAll('.cs-option')).toHaveLength(0)
    expect(wrapper.html()).not.toContain('新建客户')
    expect(wrapper.find('.cs-create-shortcut').exists()).toBe(false)
  })

  // 引用以避免未使用告警
  void vi
})
