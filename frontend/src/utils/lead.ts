/**
 * 线索纯函数工具（design D4 / D6）。无副作用，便于单测。
 */

/** 线索阶段中文常量（镜像后端 `LeadStage.dbValue`）。集中一处，避免散落字面量。 */
export const LEAD_STAGE = {
  UNTOUCHED: '未触达',
  CONTACTED: '初步沟通',
  QUOTED: '方案报价',
  NEGOTIATING: '商务谈判',
  WON: '已赢单',
  LOST: '已流失',
} as const

/** 非结束阶段（可在其间任意跳转，PRD §7.7.2）。 */
export const ACTIVE_STAGES: readonly string[] = [
  LEAD_STAGE.UNTOUCHED,
  LEAD_STAGE.CONTACTED,
  LEAD_STAGE.QUOTED,
  LEAD_STAGE.NEGOTIATING,
]

/** 跟踪方式（PRD §7.8）。 */
export const TRACK_METHODS: readonly string[] = ['电话', '微信', '拜访', '其他']

/** 流失原因（PRD §7.11.2）。 */
export const LOSE_REASONS: readonly string[] = ['价格过高', '选择竞品', '无明确需求', '联系不上', '其他']

/** 流失说明必填的触发原因。 */
export const LOSE_REASON_OTHER = '其他'

/** 已结束（已赢单 / 已流失）线索为只读（PRD §7.7.7–§7.7.9）。集中派生，避免各入口各判。 */
export function isClosed(stage: string | null | undefined): boolean {
  return stage === LEAD_STAGE.WON || stage === LEAD_STAGE.LOST
}

/**
 * 合同金额校验（PRD §7.11.1.3）：必填、大于 0、至多两位小数。
 * 以字符串校验，不经 JS number 运算（CLAUDE.md：金额禁 float/double，避免精度风险）。
 */
export function isValidAmount(raw: string | null | undefined): boolean {
  if (raw == null) {
    return false
  }
  const trimmed = raw.trim()
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return false
  }
  // 大于 0：排除 "0"、"0.00" 等
  return Number(trimmed) > 0
}

/**
 * 金额千分位展示（PRD §7.11.1.4）。仅用于显示，不回写、不参与提交。
 * 非法输入原样返回（展示层兜底）。
 */
export function formatAmount(raw: string | null | undefined): string {
  if (raw == null || raw.trim() === '') {
    return ''
  }
  const trimmed = raw.trim()
  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    return trimmed
  }
  const [intPart = '', decPart] = trimmed.split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return decPart === undefined ? grouped : `${grouped}.${decPart}`
}
