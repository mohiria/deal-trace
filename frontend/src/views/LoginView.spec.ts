import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import { server } from '../test/msw/server'
import { ADMIN_USER, loginSuccess, loginUnauthorized, meSuccess } from '../test/msw/handlers'
import { useAuthStore } from '../stores/auth'
import LoginView from './LoginView.vue'

/**
 * 登录页行为：spec R1（即时校验阻止空提交 / 成功跳转 / 失败展示后端 message 不附加枚举语义）。
 */

const Stub = defineComponent({ render: () => h('div', 'workbench') })

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/', name: 'workbench', component: Stub },
    ],
  })
}

async function mountLogin(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = buildRouter()
  await router.push('/login')
  await router.isReady()
  const wrapper = mount(LoginView, {
    global: { plugins: [router, ArcoVue] },
  })
  return { wrapper, router }
}

async function fillCredentials(wrapper: VueWrapper, email: string, password: string) {
  const inputs = wrapper.findAll('input')
  await inputs[0]!.setValue(email)
  await inputs[1]!.setValue(password)
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('即时校验', () => {
  it('空邮箱 / 空密码时阻止提交且不发登录请求', async () => {
    const { wrapper } = await mountLogin()
    const store = useAuthStore()
    const loginSpy = vi.spyOn(store, 'login')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(loginSpy).not.toHaveBeenCalled()
  })
})

describe('登录提交', () => {
  it('正确凭据 → 承载令牌并跳转工作台', async () => {
    server.use(loginSuccess(ADMIN_USER), meSuccess(ADMIN_USER))
    const { wrapper, router } = await mountLogin()
    const store = useAuthStore()

    await fillCredentials(wrapper, 'admin@dealtrace.local', 'secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(store.token).toBeTruthy()
    expect(router.currentRoute.value.name).toBe('workbench')
  })

  it('401 → 展示后端 message、停留登录页', async () => {
    server.use(loginUnauthorized('邮箱或密码不正确'))
    const { wrapper, router } = await mountLogin()

    await fillCredentials(wrapper, 'admin@dealtrace.local', 'wrong')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('邮箱或密码不正确')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('账号停用语义如实展示', async () => {
    server.use(loginUnauthorized('账号已停用'))
    const { wrapper } = await mountLogin()

    await fillCredentials(wrapper, 'disabled@dealtrace.local', 'secret')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('账号已停用')
  })
})
