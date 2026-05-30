/**
 * 工作台今日提醒派生纯函数（spec frontend-workbench R「今日提醒/待办」/ design D3）。
 *
 * 仅从既有只读数据（公海列表、名下线索）客户端派生提醒，无副作用、不改写入参，
 * 且独立于 PRD §7.12 四项看板指标口径——提醒不是那四个指标，不套用"存量按当前归属 /
 * 事件按发生时归属"的双轨规则，只反映当前 `pool` / `myLeads` 只读快照。
 */
import type { LeadView, PoolLeadView } from '../api/leads'
import { isClosed } from './lead'

/** 建议认领条目上限。 */
export const SUGGESTED_CLAIM_LIMIT = 5

/** "长期未跟踪"阈值（天）。 */
export const STALE_TRACK_DAYS = 7

/** 建议优先认领：取公海列表前 `limit` 条（公海顺序由后端给定）。 */
export function suggestedClaims(pool: PoolLeadView[], limit: number = SUGGESTED_CLAIM_LIMIT): PoolLeadView[] {
  return pool.slice(0, Math.max(0, limit))
}

/**
 * 长期未跟踪：名下未结束线索中，最后跟踪时间为空（尚未跟踪）或早于 `now - thresholdDays`。
 * 不改变入参顺序与内容（filter 返回新数组）。
 */
export function staleOwnedLeads(
  myLeads: LeadView[],
  now: Date = new Date(),
  thresholdDays: number = STALE_TRACK_DAYS,
): LeadView[] {
  const cutoffMs = now.getTime() - thresholdDays * 24 * 60 * 60 * 1000
  return myLeads.filter((lead) => {
    if (isClosed(lead.stage)) {
      return false
    }
    if (lead.lastTrackedAt == null) {
      return true
    }
    const trackedMs = Date.parse(lead.lastTrackedAt)
    if (Number.isNaN(trackedMs)) {
      // 无法解析的时间戳不臆断为超期，交由后端权威呈现。
      return false
    }
    return trackedMs < cutoffMs
  })
}
