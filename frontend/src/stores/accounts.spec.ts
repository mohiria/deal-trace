import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { server } from '../test/msw/server'
import { ApiError } from '../api/client'
import {
  SAMPLE_ACCOUNT,
  accountsList,
  createSalesSuccess,
  createSalesDuplicate,
  statusToggleSuccess,
  disableSelfRejected,
} from '../test/msw/handlers'
import type { AccountView } from '../api/accounts'
import { useAccountsStore } from './accounts'

/**
 * accounts store 行为（design D2 / D4）：加载 / 创建并入 / 状态就地更新 + enabledSales 派生 + 错误透传。
 */

const ADMIN: AccountView = { id: 1, email: 'admin@dealtrace.local', name: '系统管理员', role: 'ADMIN', status: 'ENABLED', createdAt: '2026-04-01T09:00:00' }
const SALES_ON: AccountView = { ...SAMPLE_ACCOUNT, id: 2, status: 'ENABLED' }
const SALES_OFF: AccountView = { ...SAMPLE_ACCOUNT, id: 3, email: 'off@dealtrace.local', name: '停用销售', status: 'DISABLED' }

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('加载', () => {
  it('loadAccounts 写入 accounts', async () => {
    server.use(accountsList([ADMIN, SALES_ON, SALES_OFF]))
    const store = useAccountsStore()
    await store.loadAccounts()
    expect(store.accounts).toHaveLength(3)
  })
})

describe('enabledSales 派生（D4）', () => {
  it('仅含启用且角色为 SALES 的账号', async () => {
    server.use(accountsList([ADMIN, SALES_ON, SALES_OFF]))
    const store = useAccountsStore()
    await store.loadAccounts()

    const ids = store.enabledSales.map((a) => a.id)
    expect(ids).toContain(SALES_ON.id)
    expect(ids).not.toContain(ADMIN.id) // ADMIN 排除
    expect(ids).not.toContain(SALES_OFF.id) // 停用排除
  })
})

describe('createSales（创建并入）', () => {
  it('成功后新账号并入 accounts', async () => {
    const created: AccountView = { ...SAMPLE_ACCOUNT, id: 99, email: 'new@dealtrace.local', name: '新销售' }
    server.use(accountsList([ADMIN]), createSalesSuccess(created))
    const store = useAccountsStore()
    await store.loadAccounts()
    expect(store.accounts).toHaveLength(1)

    await store.createSales({ email: 'new@dealtrace.local', name: '新销售', password: 'pw123456' })

    expect(store.accounts.find((a) => a.id === 99)).toBeDefined()
  })

  it('邮箱重复透传 ApiError 且不改 accounts', async () => {
    server.use(accountsList([ADMIN]), createSalesDuplicate('邮箱已存在'))
    const store = useAccountsStore()
    await store.loadAccounts()

    await expect(
      store.createSales({ email: 'dup@dealtrace.local', name: '重复', password: 'pw123456' }),
    ).rejects.toBeInstanceOf(ApiError)
    expect(store.accounts).toHaveLength(1)
  })
})

describe('setStatus（状态就地更新）', () => {
  it('成功后就地更新对应账号 status', async () => {
    server.use(accountsList([ADMIN, SALES_ON]), statusToggleSuccess({ ...SALES_ON, status: 'DISABLED' }))
    const store = useAccountsStore()
    await store.loadAccounts()

    await store.setStatus(SALES_ON.id, 'DISABLED')

    expect(store.accounts.find((a) => a.id === SALES_ON.id)?.status).toBe('DISABLED')
  })

  it('停用自身被拒透传 ApiError 且状态不变', async () => {
    server.use(accountsList([ADMIN]), disableSelfRejected('不可停用自己'))
    const store = useAccountsStore()
    await store.loadAccounts()

    await expect(store.setStatus(ADMIN.id, 'DISABLED')).rejects.toBeInstanceOf(ApiError)
    expect(store.accounts.find((a) => a.id === ADMIN.id)?.status).toBe('ENABLED')
  })
})
