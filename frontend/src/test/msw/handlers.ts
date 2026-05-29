import { http, HttpResponse } from 'msw'
import type { ApiEnvelope } from '../../api/client'

/**
 * 复用的 auth MSW handler 工具。
 *
 * 用通配前缀（星号 + `/api/...`）匹配，避免依赖 jsdom 的 location origin。
 * 默认 handler 走"已登录 Admin"的成功路径；需要 401 / SALES / 停用等场景的测试，
 * 用 `server.use(...)` 以本文件的工厂函数覆盖。
 */

export interface AuthUser {
  id: number
  email: string
  name: string
  role: 'ADMIN' | 'SALES'
  status: 'ENABLED' | 'DISABLED'
}

export const ADMIN_USER: AuthUser = {
  id: 1,
  email: 'admin@dealtrace.local',
  name: '系统管理员',
  role: 'ADMIN',
  status: 'ENABLED',
}

export const SALES_USER: AuthUser = {
  id: 2,
  email: 'sales@dealtrace.local',
  name: '林雨',
  role: 'SALES',
  status: 'ENABLED',
}

export const FAKE_TOKEN = 'fake-jwt-token'

function success<T>(data: T): ApiEnvelope<T> {
  return { code: 'SUCCESS', message: 'OK', data }
}

function failure(code: string, message: string): ApiEnvelope<null> {
  return { code, message, data: null }
}

export function loginSuccess(user: AuthUser = ADMIN_USER, token = FAKE_TOKEN) {
  return http.post('*/api/auth/login', () =>
    HttpResponse.json(success({ token, email: user.email, name: user.name, role: user.role })),
  )
}

export function loginUnauthorized(message = '邮箱或密码不正确') {
  return http.post('*/api/auth/login', () =>
    HttpResponse.json(failure('UNAUTHORIZED', message), { status: 401 }),
  )
}

export function meSuccess(user: AuthUser = ADMIN_USER) {
  return http.get('*/api/auth/me', () => HttpResponse.json(success(user)))
}

export function meUnauthorized(message = '登录状态已失效') {
  return http.get('*/api/auth/me', () =>
    HttpResponse.json(failure('UNAUTHORIZED', message), { status: 401 }),
  )
}

/** 默认 handler 集：登录成功 + me 成功（Admin）。 */
export const handlers = [loginSuccess(), meSuccess()]
