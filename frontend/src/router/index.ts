import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AppShell from '../components/AppShell.vue'
import PlaceholderView from '../views/PlaceholderView.vue'
import DashboardView from '../views/DashboardView.vue'
import MyLeadsView from '../views/MyLeadsView.vue'
import PublicPoolView from '../views/PublicPoolView.vue'
import LeadDetailView from '../views/LeadDetailView.vue'
import CustomersView from '../views/CustomersView.vue'
import UsersView from '../views/UsersView.vue'
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
      { path: '', name: 'workbench', component: DashboardView, meta: { title: '销售工作台' } },
      { path: 'my-leads', name: 'my-leads', component: MyLeadsView, meta: { title: '我的线索' } },
      { path: 'public-pool', name: 'public-pool', component: PublicPoolView, meta: { title: '公海线索' } },
      { path: 'leads/:id', name: 'lead-detail', component: LeadDetailView, meta: { title: '线索详情' } },
      { path: 'customers', name: 'customers', component: CustomersView, meta: { title: '客户管理' } },
      { path: 'contracts', name: 'contracts', component: PlaceholderView, meta: { title: '合同记录' } },
      {
        path: 'users',
        name: 'users',
        component: UsersView,
        meta: { title: '用户管理', requiresAdmin: true },
      },
      {
        // 系统日志查看能力尚无后端读端点：路由保留占位、导航入口已移除（frontend-admin）。
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
