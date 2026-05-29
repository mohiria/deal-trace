import type { NavigationGuardReturn, RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 公开路由（无需登录，如登录页）。 */
    public?: boolean
    /** 仅 Admin 可达。Sales 访问时回落工作台。 */
    requiresAdmin?: boolean
    /** 页面标题（占位页与后续业务页展示用）。 */
    title?: string
  }
}

/**
 * 全局前置守卫（spec R2 / R6）：
 * - 公开路由：已登录访问登录页 → 回落工作台；否则放行。
 * - 受保护路由：未登录 → 重定向登录页。
 * - Admin 专属路由：非 Admin → 回落工作台（仅减少无效入口，真正授权仍以后端为准）。
 */
export function authGuard(to: RouteLocationNormalized): NavigationGuardReturn {
  const auth = useAuthStore()

  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) {
      return { name: 'workbench' }
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login' }
  }

  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'workbench' }
  }

  return true
}
