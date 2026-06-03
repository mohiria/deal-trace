import { apiClient } from './client'

/**
 * 合同记录浏览 API（contract-view）。视图 / store 经本层调用，不直接用 `apiClient`。
 * 类型镜像后端 `ContractRowView` / `ContractPageView`；响应拦截器已 unwrap 信封，故用双泛型 `<T, T>`。
 */

/** 合同记录展示行（对应后端 `ContractRowView`）。 */
export interface ContractRowView {
  leadId: number
  customerName: string
  businessType: string
  /** 合同金额精确字符串（如 "120000.50"）；千分位在前端格式化，断言按数值。 */
  contractAmount: string
  signedDate: string
  /** 赢单时间（合同记录 createdAt）。 */
  createdAt: string
  /** 成交销售 id；公海赢单为 null。 */
  dealSalesId: number | null
  /** 成交销售当前姓名；公海赢单为"公海赢单"（后端已组装）。 */
  dealSalesName: string
}

/** 合同记录分页结果（对应后端 `ContractPageView`）。 */
export interface ContractPageView {
  items: ContractRowView[]
  total: number
  page: number
  size: number
}

/** 合同记录查询参数。`dealSalesId` 仅 ADMIN 有意义；SALES 由后端强制收窄为本人。 */
export interface ContractQuery {
  dealSalesId?: number
  signedDateFrom?: string
  signedDateTo?: string
  keyword?: string
  page?: number
  size?: number
}

/**
 * 合同记录分页浏览（`GET /contracts`）。ADMIN 全量、SALES 仅本人成交，由后端依角色裁决。
 */
export function fetchContracts(query: ContractQuery = {}): Promise<ContractPageView> {
  return apiClient.get<ContractPageView, ContractPageView>('/contracts', {
    params: {
      ...(query.dealSalesId != null ? { dealSalesId: query.dealSalesId } : {}),
      ...(query.signedDateFrom ? { signedDateFrom: query.signedDateFrom } : {}),
      ...(query.signedDateTo ? { signedDateTo: query.signedDateTo } : {}),
      ...(query.keyword ? { keyword: query.keyword } : {}),
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
}
