import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AppShell from '../components/AppShell.vue'
import PlaceholderView from '../views/PlaceholderView.vue'
import { authGuard } from './guards'

/**
 * 路由表：登录页（公开）+ 工作台外壳及其受保护子路由。
 * shell 阶段业务子路由用占位页；后续 change 替换为真实页面。
 */
const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', name: 'workbench', component: PlaceholderView, meta: { title: '销售工作台' } },
      { path: 'my-leads', name: 'my-leads', component: PlaceholderView, meta: { title: '我的线索' } },
      { path: 'public-pool', name: 'public-pool', component: PlaceholderView, meta: { title: '公海线索' } },
      { path: 'customers', name: 'customers', component: PlaceholderView, meta: { title: '客户管理' } },
      { path: 'contracts', name: 'contracts', component: PlaceholderView, meta: { title: '合同记录' } },
      {
        path: 'users',
        name: 'users',
        component: PlaceholderView,
        meta: { title: '用户管理', requiresAdmin: true },
      },
      {
        path: 'system-logs',
        name: 'system-logs',
        component: PlaceholderView,
        meta: { title: '系统日志', requiresAdmin: true },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'workbench' } },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(authGuard)
