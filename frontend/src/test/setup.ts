import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'

// jsdom 不实现 matchMedia；Arco 的响应式栅格在挂载时会调用它。提供惰性 stub。
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

/**
 * 全局测试 setup：MSW server 生命周期。
 *
 * `onUnhandledRequest: 'error'` 强制每个被测请求都有显式 handler，
 * 避免漏 mock 的请求悄悄打到真实网络。
 */
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())
