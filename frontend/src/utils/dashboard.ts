/**
 * Dashboard 指标格式化纯函数（design D3）。
 *
 * 抽出为纯函数便于单测穷举边界（流失率空值/零值、金额空集归零），不依赖渲染。
 * 一律用 `Intl.NumberFormat`，不手算浮点，避免重算失真。
 */

/** 流失率百分比格式化器：比率 ×100，至多 1 位小数，整数不带 `.0`（如 40% / 18.2%）。 */
const lossRateFormat = new Intl.NumberFormat('en-US', {
  style: 'percent',
  maximumFractionDigits: 1,
})

/** 金额格式化器：千分位、至多 2 位小数（后端金额 scale 2），整数不带小数。 */
const amountFormat = new Intl.NumberFormat('en-US', {
  maximumFractionDigits: 2,
})

/**
 * 本月流失率（design D3 / spec R4）。后端为**比率**（如 `0.4`=40%）：
 * - `null`（本月结束事件数为 0）→ `'--'`（PRD §7.12，不渲染为 `0%`）。
 * - `0`（有结束事件无流失）→ `'0%'`（非空值）。
 * - 其余比率 → 百分比，至多 1 位小数（`0.182` → `'18.2%'`）。
 */
export function formatLossRate(rate: number | null): string {
  if (rate === null) {
    return '--'
  }
  return lossRateFormat.format(rate)
}

/**
 * 本月赢单总金额（design D3 / spec R3）。后端为精确数值（空集已归一为 `0`）：
 * 千分位人民币，前缀 `¥`（`0` → `'¥0'`，`150000.5` → `'¥150,000.5'`）。
 */
export function formatWonAmount(amount: number): string {
  return `¥${amountFormat.format(amount)}`
}
