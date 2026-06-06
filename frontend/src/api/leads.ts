import { apiClient } from './client'
import type { PageQuery, PageResult } from './pagination'
import { pageParams } from './pagination'

/**
 * 线索 API 封装（design D1）。视图 / store 不直接调 `apiClient`，统一经本层。
 * 类型镜像后端 record（`LeadView` / `PoolLeadView` / `ProgressLogView`）。
 * `apiClient` 的响应拦截器已 unwrap 信封，故用双泛型 `<T, T>` 对齐实际返回类型。
 */

/** 线索详情视图（对应后端 `LeadView`，18 字段）。 */
export interface LeadView {
  id: number
  customerId: number
  customerName: string | null
  customerUsci: string | null
  businessYear: number
  businessType: string | null
  contactName: string | null
  contactPhone: string | null
  leadSource: string | null
  ownerSalesId: number | null
  /** 归属销售姓名；公海/无归属或归属账号缺失时为 null（后端内联）。 */
  ownerSalesName: string | null
  stage: string | null
  lastTrackedAt: string | null
  loseReason: string | null
  loseNote: string | null
  createdAt: string | null
  wonAt: string | null
  lostAt: string | null
}

/** 公海线索列表项（对应后端 `PoolLeadView`）。`contactPhone` 由后端按角色脱敏 / 明文。 */
export interface PoolLeadView {
  id: number
  customerId: number
  customerName: string | null
  customerUsci: string | null
  businessYear: number
  businessType: string | null
  contactName: string | null
  contactPhone: string | null
  leadSource: string | null
  stage: string | null
  lastTrackedAt: string | null
  createdAt: string | null
  /** 该客户是否另有「不同业务类型、非已流失」线索（PRD §7.6.4）；SALES 浏览公海仅得此布尔，无详情。 */
  customerHasOtherLeads: boolean
}

/**
 * 客户其他业务线索提示项（对应后端 `LeadOtherLeadView`，PRD §7.6.3）。
 * 仅含摘要字段：业务类型 / 归属销售（公海为「公海」）/ 当前阶段；不含联系方式与进度。
 */
export interface LeadOtherLeadView {
  businessType: string | null
  ownerSalesName: string | null
  stage: string | null
}

/** 进度跟踪记录（对应后端 `ProgressLogView`）。 */
export interface ProgressLogView {
  id: number
  leadId: number
  method: string | null
  content: string
  trackerId: number
  trackerName: string | null
  trackTime: string
}

/** 内联新建客户入参（spec：内联建客户 / 后端 `CreateLeadRequest.NewCustomer`）。 */
export interface NewCustomerPayload {
  name: string
  usci: string
}

/**
 * 创建线索入参（spec R4 / 后端 `CreateLeadRequest`）。`businessYear` / `stage` 由服务端派生，前端不传。
 * 关联客户二选一（恰择其一）：`customerId`（选既有）或 `newCustomer`（内联录入，后端事务内 find-or-create）。
 */
export interface CreateLeadPayload {
  customerId?: number
  newCustomer?: NewCustomerPayload
  businessType: string
  contactName: string
  contactPhone: string
  /** 选填线索来源。 */
  leadSource?: string
  /** Sales 显式放入公海；省略表示按角色默认归属（Sales 归己 / Admin 公海）。 */
  assignToPool?: boolean
}

/** 一条历史流失记录（查重预检返回，按 lostAt 倒序）。 */
export interface HistoricalLost {
  lostAt: string | null
  loseReason: string | null
  loseNote: string | null
}

/** 查重预检结果（对应后端 `DuplicateCheckResponse`，spec R4 / lead spec R6）。 */
export interface DuplicateCheckResult {
  canCreate: boolean
  /** canCreate=false 时为 `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD`，否则 null。 */
  blockingReason: string | null
  historicalLost: HistoricalLost[]
}

/** Sales 名下线索（`GET /leads/mine`）。服务端分页 + keyword 全表下推。 */
export function fetchMyLeads(query: PageQuery = {}): Promise<PageResult<LeadView>> {
  return apiClient.get<PageResult<LeadView>, PageResult<LeadView>>('/leads/mine', { params: pageParams(query) })
}

/** 全部线索（`GET /leads`，仅 ADMIN）。服务端分页 + keyword 全表下推。 */
export function fetchAllLeads(query: PageQuery = {}): Promise<PageResult<LeadView>> {
  return apiClient.get<PageResult<LeadView>, PageResult<LeadView>>('/leads', { params: pageParams(query) })
}

/** 线索详情（`GET /leads/{id}`）。 */
export function fetchLead(id: number): Promise<LeadView> {
  return apiClient.get<LeadView, LeadView>(`/leads/${id}`)
}

/**
 * 我的长期未跟踪线索（`GET /leads/mine/stale`）。供工作台今日提醒下推：
 * 调用者名下、未结束、超后端阈值（或从未跟踪）的线索，升序取前 N。阈值由后端裁决，前端不重算。
 */
export function fetchStaleLeads(): Promise<LeadView[]> {
  return apiClient.get<LeadView[], LeadView[]>('/leads/mine/stale')
}

/** 公海线索列表（`GET /leads/pool`）。服务端分页 + keyword 全表下推。 */
export function fetchPool(query: PageQuery = {}): Promise<PageResult<PoolLeadView>> {
  return apiClient.get<PageResult<PoolLeadView>, PageResult<PoolLeadView>>('/leads/pool', { params: pageParams(query) })
}

/** 认领公海线索（`POST /leads/{id}/claim`，仅 SALES）。 */
export function claimLead(id: number): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/claim`)
}

/** 退回公海（`POST /leads/{id}/release`，仅 SALES 名下）。 */
export function releaseLead(id: number, releaseNote: string): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/release`, { releaseNote })
}

/** 分配公海线索给指定 Sales（`POST /leads/{id}/assign`，仅 ADMIN）。 */
export function assignLead(id: number, salesId: number): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/assign`, { salesId })
}

/** 回收名下线索至公海（`POST /leads/{id}/recall`，仅 ADMIN）。 */
export function recallLead(id: number): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/recall`)
}

/** 转移名下线索给另一 Sales（`POST /leads/{id}/transfer`，仅 ADMIN）。 */
export function transferLead(id: number, salesId: number): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/transfer`, { salesId })
}

/** 变更非结束阶段（`PATCH /leads/{id}/stage`）。 */
export function changeStage(id: number, stage: string): Promise<LeadView> {
  return apiClient.patch<LeadView, LeadView>(`/leads/${id}/stage`, { stage })
}

/** 标记赢单（`POST /leads/{id}/win`）。金额以字符串传递以保精确（design D6）。 */
export function winLead(id: number, contractAmount: string, signedDate: string): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/win`, { contractAmount, signedDate })
}

/** 标记流失（`POST /leads/{id}/lose`）。原因为「其他」时须带 `loseNote`。 */
export function loseLead(id: number, loseReason: string, loseNote?: string): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/lose`, { loseReason, loseNote })
}

/** 读取进度跟踪（`GET /leads/{id}/progress`，倒序由后端保证）。 */
export function fetchProgress(id: number): Promise<ProgressLogView[]> {
  return apiClient.get<ProgressLogView[], ProgressLogView[]>(`/leads/${id}/progress`)
}

/** 追加进度（`POST /leads/{id}/progress`，仅 SALES 名下）。 */
export function addProgress(id: number, method: string, content: string): Promise<ProgressLogView> {
  return apiClient.post<ProgressLogView, ProgressLogView>(`/leads/${id}/progress`, { method, content })
}

/** 创建线索（`POST /leads`）。payload 透传，归属由后端按角色裁决（design D6）。 */
export function createLead(payload: CreateLeadPayload): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>('/leads', payload)
}

/** 查重预检（`GET /leads/duplicate-check?customerId=&businessType=`），无持久化副作用。 */
export function duplicateCheck(customerId: number, businessType: string): Promise<DuplicateCheckResult> {
  return apiClient.get<DuplicateCheckResult, DuplicateCheckResult>('/leads/duplicate-check', {
    params: { customerId, businessType },
  })
}

/**
 * 客户其他业务线索提示（`GET /leads/customer-other-leads?customerId=&excludeBusinessType=`，PRD §7.6）。
 * 按角色由后端裁剪：ADMIN 得全部其他业务线索摘要，SALES 仅得本人名下。`excludeBusinessType` 选填
 * （详情页传当前业务类型以排除自身业务线）。无持久化副作用。
 */
export function fetchCustomerOtherLeads(
  customerId: number,
  excludeBusinessType?: string,
): Promise<LeadOtherLeadView[]> {
  return apiClient.get<LeadOtherLeadView[], LeadOtherLeadView[]>('/leads/customer-other-leads', {
    params: excludeBusinessType ? { customerId, excludeBusinessType } : { customerId },
  })
}
