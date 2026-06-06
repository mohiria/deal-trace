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

  it('候选为空时提供"录入新客户"入口（本 change MODIFIED：反转旧"仅选既有"决策）', async () => {
    server.use(customerSearch([], []))
    const wrapper = mountSelect()

    await wrapper.find('.cs-search').setValue('不存在xyz')
    await tick()
    await flushPromises()

    expect(wrapper.findAll('.cs-option')).toHaveLength(0)
    expect(wrapper.find('.cs-create-new').exists()).toBe(true)
    expect(wrapper.find('.cs-create-new').text()).toContain('不存在xyz')
  })

  it('点击录入入口后出现名称+USCI 录入框，输入即 emit update:newCustomer（不在前端复算校验）', async () => {
    server.use(customerSearch([], []))
    const wrapper = mountSelect()

    await wrapper.find('.cs-search').setValue('星河设计院')
    await tick()
    await flushPromises()
    await wrapper.find('.cs-create-new').trigger('click')

    // 进入录入态：名称以关键词预填，USCI 留空；既有客户选择被清空
    expect(wrapper.find('.cs-new-name').exists()).toBe(true)
    expect(wrapper.find('.cs-new-usci').exists()).toBe(true)
    const lastModel = wrapper.emitted('update:modelValue')?.at(-1)
    expect(lastModel).toEqual([null])

    await wrapper.find('.cs-new-usci').setValue('91310000MA1234567N')
    const lastNew = wrapper.emitted('update:newCustomer')?.at(-1)?.[0]
    expect(lastNew).toEqual({ name: '星河设计院', usci: '91310000MA1234567N' })
  })

  it('父层把 newCustomer 重置为 null 时退出录入态回到搜索框', async () => {
    server.use(customerSearch([], []))
    const wrapper = mountSelect()

    await wrapper.find('.cs-search').setValue('星河')
    await tick()
    await flushPromises()
    await wrapper.find('.cs-create-new').trigger('click')
    expect(wrapper.find('.cs-new-name').exists()).toBe(true)
    // 模拟父层 v-model:newCustomer 回填录入态的非空值（choose/enterNewMode 后父更新 prop）
    await wrapper.setProps({ newCustomer: { name: '星河', usci: '' } })

    await wrapper.setProps({ newCustomer: null })
    expect(wrapper.find('.cs-new-name').exists()).toBe(false)
    expect(wrapper.find('.cs-search').exists()).toBe(true)
  })

  it('父层把 modelValue 外部重置为 null 时清掉已选残留（修复跨次打开"显示已选 A 却提示未选"）', async () => {
    server.use(customerSearch([SAMPLE_CUSTOMER]))
    const wrapper = mountSelect()

    await wrapper.find('.cs-search').setValue('建筑')
    await tick()
    await flushPromises()
    await wrapper.find('.cs-option').trigger('click')
    // 模拟父层 v-model 回填选中 id（choose emit 后 selectedCustomerId 变为该 id）
    await wrapper.setProps({ modelValue: SAMPLE_CUSTOMER.id })

    // 选中后展示"已选客户：…"
    expect(wrapper.find('.cs-selected').exists()).toBe(true)
    expect(wrapper.find('.cs-selected').text()).toContain(SAMPLE_CUSTOMER.name)

    // 父层重置（弹窗重开 resetForm → selectedCustomerId 置 null）
    await wrapper.setProps({ modelValue: null })

    expect(wrapper.find('.cs-selected').exists()).toBe(false)
    expect(wrapper.find('.cs-search').element).toHaveProperty('value', '')
  })

  // 引用以避免未使用告警
  void vi
})
