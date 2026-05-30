import { apiClient } from './client'

/**
 * 账号 API 封装（design D1）。视图 / store 不直接调 `apiClient`，统一经本层。
 * 类型镜像后端 `AccountView`（6 字段，剥离 passwordHash 等敏感字段）。
 * `apiClient` 的响应拦截器已 unwrap 信封，故用双泛型 `<T, T>` 对齐实际返回类型。
 */

/** 账号状态。 */
export type AccountStatus = 'ENABLED' | 'DISABLED'

/** 账号视图（对应后端 `AccountView`，6 字段，无密码哈希）。 */
export interface AccountView {
  id: number
  email: string
  name: string
  role: 'ADMIN' | 'SALES'
  status: AccountStatus
  createdAt: string | null
}

/** 创建 Sales 入参（角色固定 SALES，由调用方在 payload 中带上）。 */
export interface CreateSalesPayload {
  email: string
  name: string
  password: string
}

/** 账号列表（`GET /admin/accounts`，仅 ADMIN）。后端按创建时间排序。 */
export function fetchAccounts(): Promise<AccountView[]> {
  return apiClient.get<AccountView[], AccountView[]>('/admin/accounts')
}

/**
 * 创建 Sales（`POST /admin/accounts`）。`role` 固定 `SALES`（后端对非 SALES 以 VALIDATION_ERROR 拒绝）。
 * email/name/password 原样提交；邮箱唯一性与格式由后端权威校验。
 */
export function createSales(payload: CreateSalesPayload): Promise<AccountView> {
  return apiClient.post<AccountView, AccountView>('/admin/accounts', { ...payload, role: 'SALES' })
}

/**
 * 启用 / 停用账号（`PATCH /admin/accounts/{id}/status`）。
 * 幂等由后端保证；停用自身由后端以 VALIDATION_ERROR 拒绝（前端另行收起入口，design D6）。
 */
export function updateAccountStatus(id: number, status: AccountStatus): Promise<AccountView> {
  return apiClient.patch<AccountView, AccountView>(`/admin/accounts/${id}/status`, { status })
}
