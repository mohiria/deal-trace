import { apiClient } from './client'

/**
 * Dashboard API 封装（design D1）。视图不直接调 `apiClient`，统一经本层。
 *
 * 类型镜像后端 `DashboardView`（6 字段）。后端无自定义 Jackson 配置，`BigDecimal`
 * 默认序列化为 JSON 数字，故金额与流失率经 axios 收到的是 `number`（非字符串）：
 * - `monthlyWonAmount`：本月赢单总金额，空集后端已归一为 `0`（非 null）。
 * - `monthlyLossRate`：本月流失率**比率**（如 `0.4` 即 40%）；本月结束事件数为 0 时为 `null`。
 *
 * `apiClient` 的响应拦截器已 unwrap 信封，故用双泛型 `<T, T>` 对齐实际返回类型。
 */

/** 看板视图（对应后端 `DashboardView`，6 字段）。 */
export interface DashboardView {
  todayNewLeadCount: number
  openSeaUnclaimedCount: number
  monthlyWonAmount: number
  monthlyLossRate: number | null
  monthlyLostEventCount: number
  monthlyEndedEventCount: number
}

/**
 * 拉取看板指标（`GET /dashboard`，所有已认证用户）。
 * 不携带任何视角 / 归属 / owner 参数——口径由后端依登录角色裁决（spec R2）。
 */
export function fetchDashboard(): Promise<DashboardView> {
  return apiClient.get<DashboardView, DashboardView>('/dashboard')
}
