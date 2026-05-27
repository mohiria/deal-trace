import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 认证状态占位 store；token / 当前用户字段由 auth-account capability spec 实现。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(null)

  function setToken(value: string | null) {
    token.value = value
  }

  return { token, setToken }
})
