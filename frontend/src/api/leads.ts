import { apiClient } from './client'

/**
 * 线索 API 封装（design D1）。视图 / store 不直接调 `apiClient`，统一经本层。
 * 类型镜像后端 record（`LeadView` / `PoolLeadView` / `ProgressLogView`）。
 * `apiClient` 的响应拦截器已 unwrap 信封，故用双泛型 `<T, T>` 对齐实际返回类型。
 */

/** 线索详情视图（对应后端 `LeadView`，17 字段）。 */
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

/** Sales 名下线索（`GET /leads/mine`）。 */
export function fetchMyLeads(): Promise<LeadView[]> {
  return apiClient.get<LeadView[], LeadView[]>('/leads/mine')
}

/** 全部线索（`GET /leads`，仅 ADMIN）。 */
export function fetchAllLeads(): Promise<LeadView[]> {
  return apiClient.get<LeadView[], LeadView[]>('/leads')
}

/** 线索详情（`GET /leads/{id}`）。 */
export function fetchLead(id: number): Promise<LeadView> {
  return apiClient.get<LeadView, LeadView>(`/leads/${id}`)
}

/** 公海线索列表（`GET /leads/pool`）。 */
export function fetchPool(): Promise<PoolLeadView[]> {
  return apiClient.get<PoolLeadView[], PoolLeadView[]>('/leads/pool')
}

/** 认领公海线索（`POST /leads/{id}/claim`，仅 SALES）。 */
export function claimLead(id: number): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/claim`)
}

/** 退回公海（`POST /leads/{id}/release`，仅 SALES 名下）。 */
export function releaseLead(id: number, releaseNote: string): Promise<LeadView> {
  return apiClient.post<LeadView, LeadView>(`/leads/${id}/release`, { releaseNote })
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
