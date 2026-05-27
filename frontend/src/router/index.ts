import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 路由表占位：业务路由由后续 capability spec 注册。
 */
const routes: RouteRecordRaw[] = []

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
