import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import '@arco-design/web-vue/dist/arco.css'
import './styles/arco-theme.css'
import './style.css'

import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './api/client'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ArcoVue)

const auth = useAuthStore(pinia)

// D3：把"清登录态 + 跳登录页"回调注入 client，避免 client 直接 import store/router。
setUnauthorizedHandler(() => {
  auth.clearSession()
  void router.push({ name: 'login' })
})

// 先凭持久化令牌恢复登录态（向 /auth/me 核实）再挂载，避免首屏在已登录时闪登录页。
void auth.restore().finally(() => {
  app.mount('#app')
})
