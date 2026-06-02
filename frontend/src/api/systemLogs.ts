import { apiClient } from './client'

/**
 * 系统日志读取 API（view-system-log）。视图 / store 经本层调用，不直接用 `apiClient`。
 * 类型镜像后端 `SystemLogView` / `SystemLogPageView`；响应拦截器已 unwrap 信封，故用双泛型 `<T, T>`。
 */

/** 系统日志条目（对应后端 `SystemLogView`）。 */
export interface SystemLogView {
  id: number
  action: string
  actionLabel: string
  operatorName: string
  createdAt: string
  targetType: string
  targetId: number
  leadId: number | null
  /** 结构化引用（归属 id 旁附 *Name 当前姓名、公海为"公海"；金额为精确字符串）；旧行为 null。 */
  detail: Record<string, unknown> | null
  /** detail 为 null（旧行）时的人读回退摘要。 */
  summaryFallback: string | null
}

/** 全局系统日志分页结果（对应后端 `SystemLogPageView`）。 */
export interface SystemLogPageView {
  items: SystemLogView[]
  total: number
  page: number
  size: number
}

/** 全局系统日志查询参数。 */
export interface SystemLogQuery {
  action?: string
  targetType?: string
  page?: number
  size?: number
}

/** 线索维度系统日志（倒序）。ADMIN 任意 / SALES 自己名下，否则后端返回 404。 */
export function fetchLeadLogs(id: number): Promise<SystemLogView[]> {
  return apiClient.get<SystemLogView[], SystemLogView[]>(`/leads/${id}/logs`)
}

/** 全局系统日志分页浏览（仅 ADMIN，路径级守卫）。 */
export function fetchSystemLogs(query: SystemLogQuery = {}): Promise<SystemLogPageView> {
  return apiClient.get<SystemLogPageView, SystemLogPageView>('/admin/system-logs', {
    params: {
      ...(query.action ? { action: query.action } : {}),
      ...(query.targetType ? { targetType: query.targetType } : {}),
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
}
