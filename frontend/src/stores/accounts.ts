import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AccountStatus, AccountView, CreateSalesPayload } from '../api/accounts'
import {
  createSales as apiCreateSales,
  fetchAccounts,
  updateAccountStatus as apiUpdateStatus,
} from '../api/accounts'

/**
 * 账号 store（design D2 / D4）：承载 Admin 账号列表，并维护创建并入与状态就地更新。
 * 派生 `enabledSales` 供线索分配 / 转移的目标候选。
 * 写动作把 ApiError 透传给调用方按 code 分支（design D5），不在此吞掉。
 */
export const useAccountsStore = defineStore('accounts', () => {
  const accounts = ref<AccountView[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 启用且角色为 SALES 的账号——分配 / 转移目标候选（D4）。 */
  const enabledSales = computed(() =>
    accounts.value.filter((a) => a.role === 'SALES' && a.status === 'ENABLED'),
  )

  async function loadAccounts() {
    loading.value = true
    error.value = null
    try {
      accounts.value = await fetchAccounts()
    } finally {
      loading.value = false
    }
  }

  /** 创建成功：新账号并入列表（置顶，便于即时可见）。 */
  async function createSales(payload: CreateSalesPayload): Promise<AccountView> {
    const created = await apiCreateSales(payload)
    accounts.value = [created, ...accounts.value]
    return created
  }

  /** 状态切换成功：就地更新对应账号（后端返回切换后的账号）。 */
  async function setStatus(id: number, status: AccountStatus): Promise<AccountView> {
    const updated = await apiUpdateStatus(id, status)
    accounts.value = accounts.value.map((a) => (a.id === id ? updated : a))
    return updated
  }

  return { accounts, loading, error, enabledSales, loadAccounts, createSales, setStatus }
})
