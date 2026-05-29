import { setupServer } from 'msw/node'
import { handlers } from './handlers'

/** 组件 / 集成测试共享的 MSW server，在 axios 边界拦截后端请求。 */
export const server = setupServer(...handlers)
