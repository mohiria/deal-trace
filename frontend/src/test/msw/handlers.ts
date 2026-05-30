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

// ---- frontend-lead-flow：/leads/* handler 工厂 ----

import type { LeadView, PoolLeadView, ProgressLogView } from '../../api/leads'

/** 一条样例名下线索（未触达，非结束）。 */
export const SAMPLE_LEAD: LeadView = {
  id: 100,
  customerId: 10,
  customerName: '示例建筑设计院',
  customerUsci: '91110000MA0000000X',
  businessYear: 2026,
  businessType: 'BIM咨询',
  contactName: '王工',
  contactPhone: '138****5678',
  leadSource: '官网咨询',
  ownerSalesId: 2,
  stage: '未触达',
  lastTrackedAt: null,
  loseReason: null,
  loseNote: null,
  createdAt: '2026-05-01T09:00:00',
  wonAt: null,
  lostAt: null,
}

/** 一条公海线索（Sales 视角电话脱敏）。 */
export const SAMPLE_POOL_LEAD: PoolLeadView = {
  id: 200,
  customerId: 20,
  customerName: '公海客户甲',
  customerUsci: '91110000MA1111111Y',
  businessYear: 2026,
  businessType: 'BIM培训',
  contactName: '李经理',
  contactPhone: '139****4321',
  leadSource: '展会',
  stage: '初步沟通',
  lastTrackedAt: null,
  createdAt: '2026-04-20T14:00:00',
}

export const SAMPLE_PROGRESS: ProgressLogView = {
  id: 500,
  leadId: 100,
  method: '电话',
  content: '首次电话沟通，客户有意向。',
  trackerId: 2,
  trackerName: '林雨',
  trackTime: '2026-05-02T10:30:00',
}

export function mineLeads(rows: LeadView[] = [SAMPLE_LEAD]) {
  return http.get('*/api/leads/mine', () => HttpResponse.json(success(rows)))
}

export function allLeads(rows: LeadView[] = [SAMPLE_LEAD]) {
  return http.get('*/api/leads', () => HttpResponse.json(success(rows)))
}

export function leadDetail(lead: LeadView = SAMPLE_LEAD) {
  return http.get('*/api/leads/:id', () => HttpResponse.json(success(lead)))
}

export function poolList(rows: PoolLeadView[] = [SAMPLE_POOL_LEAD]) {
  return http.get('*/api/leads/pool', () => HttpResponse.json(success(rows)))
}

export function claimSuccess(lead: LeadView = { ...SAMPLE_LEAD, id: 200, ownerSalesId: 2, contactPhone: '13912344321' }) {
  return http.post('*/api/leads/:id/claim', () => HttpResponse.json(success(lead)))
}

export function claimAlreadyClaimed(message = '该线索已被认领') {
  return http.post('*/api/leads/:id/claim', () =>
    HttpResponse.json(failure('LEAD_ALREADY_CLAIMED', message), { status: 409 }),
  )
}

export function progressList(rows: ProgressLogView[] = [SAMPLE_PROGRESS]) {
  return http.get('*/api/leads/:id/progress', () => HttpResponse.json(success(rows)))
}

export function addProgressSuccess(entry: ProgressLogView = SAMPLE_PROGRESS) {
  return http.post('*/api/leads/:id/progress', () => HttpResponse.json(success(entry)))
}

export function stageSuccess(lead: LeadView = { ...SAMPLE_LEAD, stage: '方案报价' }) {
  return http.patch('*/api/leads/:id/stage', () => HttpResponse.json(success(lead)))
}

export function winSuccess(lead: LeadView = { ...SAMPLE_LEAD, stage: '已赢单', wonAt: '2026-05-10T09:00:00' }) {
  return http.post('*/api/leads/:id/win', () => HttpResponse.json(success(lead)))
}

export function loseSuccess(lead: LeadView = { ...SAMPLE_LEAD, stage: '已流失', loseReason: '其他', loseNote: '客户预算取消', lostAt: '2026-05-10T09:00:00' }) {
  return http.post('*/api/leads/:id/lose', () => HttpResponse.json(success(lead)))
}

export function releaseSuccess(lead: LeadView = { ...SAMPLE_LEAD, ownerSalesId: null }) {
  return http.post('*/api/leads/:id/release', () => HttpResponse.json(success(lead)))
}

/** 闭单只读：POST 写操作（claim/win/lose/release/progress）被后端以 LEAD_ENDED_READONLY 拒绝。 */
export function endedReadonly(path: string, message = '线索已结束，不可操作') {
  return http.post(`*/api/leads/:id/${path}`, () =>
    HttpResponse.json(failure('LEAD_ENDED_READONLY', message), { status: 409 }),
  )
}

/** 通用校验失败（金额/日期/必填等后端兜底）。 */
export function validationError(path: string, message = '校验未通过') {
  return http.post(`*/api/leads/:id/${path}`, () =>
    HttpResponse.json(failure('VALIDATION_ERROR', message), { status: 400 }),
  )
}

// ---- frontend-customer：/customers 与 /leads 创建 / 查重预检 handler 工厂 ----

import type { CustomerView } from '../../api/customers'
import type { DuplicateCheckResult } from '../../api/leads'

/** 一条样例客户（4 字段，镜像后端 CustomerView）。 */
export const SAMPLE_CUSTOMER: CustomerView = {
  id: 10,
  name: '示例建筑设计院',
  usci: '91110000MA0000000X',
  createdAt: '2026-05-01T09:00:00',
}

/** 无关键词列表：始终返回给定行（默认一条）。 */
export function customerList(rows: CustomerView[] = [SAMPLE_CUSTOMER]) {
  return http.get('*/api/customers', () => HttpResponse.json(success(rows)))
}

/**
 * 关键词搜索：仅当请求带非空 `keyword` 时返回 `rows`，否则返回 `listRows`。
 * 便于在同一测试中区分"无关键词列表"与"有关键词搜索"两态。
 */
export function customerSearch(rows: CustomerView[] = [SAMPLE_CUSTOMER], listRows: CustomerView[] = []) {
  return http.get('*/api/customers', ({ request }) => {
    const keyword = new URL(request.url).searchParams.get('keyword')
    const hit = keyword && keyword.trim() !== '' ? rows : listRows
    return HttpResponse.json(success(hit))
  })
}

export function createCustomerSuccess(customer: CustomerView = SAMPLE_CUSTOMER) {
  return http.post('*/api/customers', () => HttpResponse.json(success(customer)))
}

export function createCustomerDuplicate(message = '客户已存在') {
  return http.post('*/api/customers', () =>
    HttpResponse.json(failure('DUPLICATE_CUSTOMER', message), { status: 400 }),
  )
}

export function createCustomerValidation(message = '统一社会信用代码校验未通过') {
  return http.post('*/api/customers', () =>
    HttpResponse.json(failure('VALIDATION_ERROR', message), { status: 400 }),
  )
}

/** 查重预检：允许新建、无历史流失。 */
export function duplicateCheckCanCreate() {
  const data: DuplicateCheckResult = { canCreate: true, blockingReason: null, historicalLost: [] }
  return http.get('*/api/leads/duplicate-check', () => HttpResponse.json(success(data)))
}

/** 查重预检：进行中线索阻塞。 */
export function duplicateCheckBlockedActive() {
  const data: DuplicateCheckResult = {
    canCreate: false,
    blockingReason: 'DUPLICATE_ACTIVE_LEAD',
    historicalLost: [],
  }
  return http.get('*/api/leads/duplicate-check', () => HttpResponse.json(success(data)))
}

/** 查重预检：已赢单线索阻塞。 */
export function duplicateCheckBlockedWon() {
  const data: DuplicateCheckResult = {
    canCreate: false,
    blockingReason: 'DUPLICATE_WON_LEAD',
    historicalLost: [],
  }
  return http.get('*/api/leads/duplicate-check', () => HttpResponse.json(success(data)))
}

/** 查重预检：允许新建但带历史流失记录（倒序）。 */
export function duplicateCheckWithHistoricalLost() {
  const data: DuplicateCheckResult = {
    canCreate: true,
    blockingReason: null,
    historicalLost: [
      { lostAt: '2026-03-10T09:00:00', loseReason: '价格过高', loseNote: '预算不足' },
      { lostAt: '2025-11-02T09:00:00', loseReason: '选择竞品', loseNote: null },
    ],
  }
  return http.get('*/api/leads/duplicate-check', () => HttpResponse.json(success(data)))
}

export function createLeadSuccess(lead: LeadView = { ...SAMPLE_LEAD, id: 300 }) {
  return http.post('*/api/leads', () => HttpResponse.json(success(lead)))
}

export function createLeadValidation(message = '联系电话格式非法') {
  return http.post('*/api/leads', () =>
    HttpResponse.json(failure('VALIDATION_ERROR', message), { status: 400 }),
  )
}

/** 默认 handler 集：登录成功 + me 成功（Admin）。 */
export const handlers = [loginSuccess(), meSuccess()]
