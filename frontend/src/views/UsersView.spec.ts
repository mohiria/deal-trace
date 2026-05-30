import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { http, HttpResponse } from 'msw'
import { server } from '../test/msw/server'
import {
  ADMIN_USER,
  accountsList,
  createSalesSuccess,
  createSalesDuplicate,
  statusToggleSuccess,
} from '../test/msw/handlers'
import type { AccountView } from '../api/accounts'
import { useAuthStore } from '../stores/auth'
import UsersView from './UsersView.vue'

/**
 * 用户管理（spec：查看账号列表 / 创建 Sales / 启用停用）。ADMIN_USER.id===1。
 */

const ADMIN: AccountView = { id: 1, email: 'admin@dealtrace.local', name: '系统管理员', role: 'ADMIN', status: 'ENABLED', createdAt: '2026-04-01T09:00:00' }
const SALES_ON: AccountView = { id: 2, email: 'sales@dealtrace.local', name: '林雨', role: 'SALES', status: 'ENABLED', createdAt: '2026-05-01T09:00:00' }
const SALES_OFF: AccountView = { id: 3, email: 'off@dealtrace.local', name: '停用销售', role: 'SALES', status: 'DISABLED', createdAt: '2026-05-02T09:00:00' }

async function mountView(rows: AccountView[] = [ADMIN, SALES_ON, SALES_OFF]): Promise<VueWrapper> {
  server.use(accountsList(rows))
  const auth = useAuthStore()
  auth.currentUser = ADMIN_USER
  const wrapper = mount(UsersView, { global: { plugins: [ArcoVue] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  document.body.innerHTML = ''
})

describe('账号列表（spec：查看账号列表）', () => {
  it('渲染全部账号（含启用与停用）及字段，不含密码', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain(ADMIN.email)
    expect(wrapper.text()).toContain(SALES_ON.name)
    expect(wrapper.text()).toContain(SALES_OFF.email)
    expect(wrapper.text()).toContain(SALES_OFF.createdAt!)
    // 列表数据不含密码哈希等敏感字段（账号行仅 email/name/role/status/createdAt）
    expect(wrapper.find('.users-table').html()).not.toContain('passwordHash')
    expect(wrapper.find('.users-table').html()).not.toContain('password')
  })

  it('按后端返回顺序稳定排列', async () => {
    const wrapper = await mountView([SALES_ON, SALES_OFF])
    const text = wrapper.text()
    expect(text.indexOf(SALES_ON.email)).toBeLessThan(text.indexOf(SALES_OFF.email))
  })

  it('支持账号搜索和标准分页', async () => {
    const rows = Array.from({ length: 12 }, (_, index) => ({
      ...SALES_ON,
      id: 20 + index,
      email: index === 11 ? 'xinghe@dealtrace.local' : `sales${index + 1}@dealtrace.local`,
      name: index === 11 ? '星河销售' : `销售${index + 1}`,
    }))
    const wrapper = await mountView([ADMIN, ...rows])

    expect(wrapper.find('[data-test="list-pagination"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('xinghe@dealtrace.local')

    await wrapper.find('.list-search').setValue('星河')
    await flushPromises()

    expect(wrapper.text()).toContain('xinghe@dealtrace.local')
  })
})

describe('创建 Sales（spec：创建 Sales 账号）', () => {
  it('邮箱格式非法时即时拦截不发请求', async () => {
    let posted = false
    const wrapper = await mountView([ADMIN])
    server.use(
      http.post('*/api/admin/accounts', () => {
        posted = true
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SALES_ON })
      }),
    )
    await wrapper.find('.create-sales-open').trigger('click')
    await flushPromises()
    await wrapper.find('.sales-email input').setValue('not-an-email')
    await wrapper.find('.sales-name input').setValue('某销售')
    await wrapper.find('.sales-password input').setValue('pw123456')
    await wrapper.find('.sales-confirm').trigger('click')
    await flushPromises()
    expect(posted).toBe(false)
  })

  it('name 或 password 为空时即时拦截不发请求', async () => {
    let posted = false
    const wrapper = await mountView([ADMIN])
    server.use(
      http.post('*/api/admin/accounts', () => {
        posted = true
        return HttpResponse.json({ code: 'SUCCESS', message: 'OK', data: SALES_ON })
      }),
    )
    await wrapper.find('.create-sales-open').trigger('click')
    await flushPromises()
    await wrapper.find('.sales-email input').setValue('ok@dealtrace.local')
    // name/password 留空
    await wrapper.find('.sales-confirm').trigger('click')
    await flushPromises()
    expect(posted).toBe(false)
  })

  it('合法提交成功后新账号以启用进入列表', async () => {
    const created: AccountView = { id: 99, email: 'new@dealtrace.local', name: '新销售', role: 'SALES', status: 'ENABLED', createdAt: '2026-05-30T10:00:00' }
    const wrapper = await mountView([ADMIN])
    server.use(createSalesSuccess(created))

    await wrapper.find('.create-sales-open').trigger('click')
    await flushPromises()
    await wrapper.find('.sales-email input').setValue('new@dealtrace.local')
    await wrapper.find('.sales-name input').setValue('新销售')
    await wrapper.find('.sales-password input').setValue('pw123456')
    await wrapper.find('.sales-confirm').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('new@dealtrace.local')
    expect(wrapper.text()).toContain('新销售')
  })

  it('邮箱重复 VALIDATION_ERROR 提示且不计入列表', async () => {
    const wrapper = await mountView([ADMIN])
    const errSpy = vi.spyOn(Message, 'error')
    server.use(createSalesDuplicate('邮箱已存在'))

    await wrapper.find('.create-sales-open').trigger('click')
    await flushPromises()
    await wrapper.find('.sales-email input').setValue('dup@dealtrace.local')
    await wrapper.find('.sales-name input').setValue('重复销售')
    await wrapper.find('.sales-password input').setValue('pw123456')
    await wrapper.find('.sales-confirm').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('已存在'))).toBe(true)
    expect(wrapper.text()).not.toContain('dup@dealtrace.local')
  })
})

describe('启用 / 停用（spec：启用与停用账号）', () => {
  it('停用启用中的 Sales 后该行 status 变停用', async () => {
    const wrapper = await mountView([ADMIN, SALES_ON])
    server.use(statusToggleSuccess({ ...SALES_ON, status: 'DISABLED' }))

    // 自身（ADMIN）行无停用入口，列表中可切换的只有 SALES 行
    expect(wrapper.findAll('.account-status-disabled')).toHaveLength(0)
    const toggles = wrapper.findAll('.status-toggle')
    expect(toggles).toHaveLength(1)
    await toggles[0]!.trigger('click')
    await flushPromises()

    // SALES 行状态变为停用
    expect(wrapper.findAll('.account-status-disabled')).toHaveLength(1)
  })

  it('启用已停用的 Sales 后该行 status 变启用', async () => {
    const wrapper = await mountView([ADMIN, SALES_OFF])
    server.use(statusToggleSuccess({ ...SALES_OFF, status: 'ENABLED' }))

    const toggles = wrapper.findAll('.status-toggle')
    expect(toggles).toHaveLength(1)
    await toggles[0]!.trigger('click')
    await flushPromises()

    // 该 SALES 行启用后该行不再含「停用」状态标签文本（变为启用）
    const store = useAuthStore()
    void store
    expect(wrapper.findAll('.account-status-disabled')).toHaveLength(0)
  })

  it('自身账号行不呈现停用入口', async () => {
    const wrapper = await mountView([ADMIN, SALES_ON])
    // 仅 1 个可切换入口（SALES 行），ADMIN 自身行无入口
    expect(wrapper.findAll('.status-toggle')).toHaveLength(1)
  })

  it('状态切换被 VALIDATION_ERROR 拒绝时提示且状态不变', async () => {
    const wrapper = await mountView([ADMIN, SALES_ON])
    const errSpy = vi.spyOn(Message, 'error')
    server.use(
      http.patch('*/api/admin/accounts/:id/status', () =>
        HttpResponse.json({ code: 'VALIDATION_ERROR', message: '不可停用自己', data: null }, { status: 400 }),
      ),
    )

    await wrapper.find('.status-toggle').trigger('click')
    await flushPromises()

    expect(errSpy.mock.calls.some((c) => String(c[0]).includes('不可停用自己'))).toBe(true)
    // SALES 行仍为启用
    expect(wrapper.findAll('.account-status-disabled')).toHaveLength(0)
  })
})
