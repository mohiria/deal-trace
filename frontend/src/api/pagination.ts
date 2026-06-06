/**
 * 通用分页类型（镜像后端 `PageView<T>` 信封与列表端点 query 参数）。
 * 与 systemLogs 的 `SystemLogPageView` 同形，泛化供 customer / lead 列表复用。
 */

/** 分页信封：`{ items, total, page, size }`，`total` 为含 keyword 过滤的命中总数。 */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 列表端点统一 query：页码、页大小、可选关键词。 */
export interface PageQuery {
  page?: number
  size?: number
  keyword?: string
}

/** 把 PageQuery 转为请求 params（page 默认 1、size 默认 20、keyword 非空才带）。 */
export function pageParams(query: PageQuery = {}): Record<string, unknown> {
  const k = query.keyword?.trim()
  return {
    page: query.page ?? 1,
    size: query.size ?? 20,
    ...(k ? { keyword: k } : {}),
  }
}
