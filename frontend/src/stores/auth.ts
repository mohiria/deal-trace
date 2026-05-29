import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiClient, TOKEN_STORAGE_KEY } from '../api/client'

export type Role = 'ADMIN' | 'SALES'

/** 当前用户脱敏信息（对应后端 `/auth/me` 的 data）。 */
export interface AuthUser {
  id: number
  email: string
  name: string
  role: Role
  status: 'ENABLED' | 'DISABLED'
}

/** 登录端点返回（对应后端 `/auth/login` 的 data）。 */
interface LoginResult {
  token: string
  email: string
  name: string
  role: Role
}

/**
 * 登录态 store：令牌（持久化于 localStorage，单一来源）+ 当前用户脱敏信息。
 * 提供登录 / 登出 / 刷新恢复（向 `/auth/me` 核实）/ 角色派生。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_STORAGE_KEY))
  const currentUser = ref<AuthUser | null>(null)

  const isAuthenticated = computed(() => token.value !== null && currentUser.value !== null)
  const isAdmin = computed(() => currentUser.value?.role === 'ADMIN')
  const role = computed<Role | null>(() => currentUser.value?.role ?? null)

  function setToken(value: string | null) {
    token.value = value
    if (value) {
      localStorage.setItem(TOKEN_STORAGE_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  }

  /** 清空本地登录态（用户登出与会话失效共用的原语）。 */
  function clearSession() {
    setToken(null)
    currentUser.value = null
  }

  async function fetchMe() {
    currentUser.value = await apiClient.get<AuthUser, AuthUser>('/auth/me')
  }

  async function login(email: string, password: string) {
    const result = await apiClient.post<LoginResult, LoginResult>('/auth/login', { email, password })
    setToken(result.token)
    await fetchMe()
  }

  /** 应用启动 / 刷新时凭持久化令牌恢复登录态，并向后端核实；失效则清退。 */
  async function restore() {
    const stored = localStorage.getItem(TOKEN_STORAGE_KEY)
    if (!stored) {
      clearSession()
      return
    }
    token.value = stored
    try {
      await fetchMe()
    } catch {
      clearSession()
    }
  }

  function logout() {
    clearSession()
  }

  return {
    token,
    currentUser,
    isAuthenticated,
    isAdmin,
    role,
    login,
    restore,
    logout,
    clearSession,
  }
})
