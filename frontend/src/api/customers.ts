import { apiClient } from './client'
import type { PageQuery, PageResult } from './pagination'
import { pageParams } from './pagination'

/**
 * 客户 API 封装（design D1）。视图不直接调 `apiClient`，统一经本层。
 * 类型镜像后端 `CustomerView`（4 字段，spec R1：不含联系人 / 归属销售）。
 * `apiClient` 的响应拦截器已 unwrap 信封，故用双泛型 `<T, T>` 对齐实际返回类型。
 */

/** 客户视图（对应后端 `CustomerView`，4 字段）。 */
export interface CustomerView {
  id: number
  name: string
  usci: string
  createdAt: string | null
}

/**
 * 客户搜索 / 列表（`GET /customers`，所有已认证用户）。服务端分页：
 * 返回 `{ items, total, page, size }`；keyword 由后端对全表 name/usci 子串匹配后分页。
 */
export function searchCustomers(query: PageQuery = {}): Promise<PageResult<CustomerView>> {
  return apiClient.get<PageResult<CustomerView>, PageResult<CustomerView>>('/customers', {
    params: pageParams(query),
  })
}

/**
 * 创建客户（`POST /customers`）。name / usci 原样提交（design D8）：
 * trim + 大写归一化与 18 位校验位由后端 `CustomerService` 权威完成，前端不复算。
 */
export function createCustomer(name: string, usci: string): Promise<CustomerView> {
  return apiClient.post<CustomerView, CustomerView>('/customers', { name, usci })
}
