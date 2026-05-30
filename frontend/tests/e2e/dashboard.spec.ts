import { expect, test } from '@playwright/test'

/**
 * 工作台首屏看板 E2E（spec frontend-workbench：首屏只读看板 / 口径后端裁决）。
 * 场景优先、真后端冒烟（QA constitution：E2E 不强求 Red-Green）。
 *
 * 运行前置（prerequisite blocker，见 qa/lightweight-test-design.md）：
 *   1) backend：`mvn spring-boot:run`，并经 DEALTRACE_ADMIN_EMAIL / DEALTRACE_ADMIN_PASSWORD
 *      注入初始 Admin（PRD §7.1：认证由部署配置注入）。
 *   2) frontend：`pnpm dev`。
 *   3) 设置 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD 与后端初始 Admin 一致，再 `pnpm test:e2e`。
 *      Sales 旅程另需 E2E_SALES_EMAIL / E2E_SALES_PASSWORD（缺则跳过）。
 */

const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? process.env.DEALTRACE_ADMIN_EMAIL ?? ''
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? process.env.DEALTRACE_ADMIN_PASSWORD ?? ''
const SALES_EMAIL = process.env.E2E_SALES_EMAIL ?? ''
const SALES_PASSWORD = process.env.E2E_SALES_PASSWORD ?? ''

/** 四项指标卡片标题——首屏可见即视为看板渲染成功。 */
const METRIC_TITLES = ['今日新增线索', '公海待认领', '本月赢单金额', '本月流失率']

async function login(page: import('@playwright/test').Page, email: string, password: string) {
  await page.goto('/login')
  const inputs = page.locator('form input')
  await inputs.nth(0).fill(email)
  await inputs.nth(1).fill(password)
  await page.getByRole('button', { name: '登录' }).click()
}

test.describe('工作台首屏指标看板', () => {
  test.skip(
    !ADMIN_EMAIL || !ADMIN_PASSWORD,
    '需提供 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD（与后端初始 Admin 一致）',
  )

  test('Admin 登录后首屏可见四项指标（全局口径，值来自后端）', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD)
    // 进入工作台首屏：URL 离开 /login
    await expect(page).not.toHaveURL(/\/login$/)

    const metrics = page.getByTestId('dashboard-metrics')
    await expect(metrics).toBeVisible()
    for (const title of METRIC_TITLES) {
      await expect(metrics.getByText(title)).toBeVisible()
    }
    // 流失率渲染为 `--` 或百分比，绝不空白；金额带 `¥` 前缀
    await expect(page.getByTestId('metric-loss-rate')).not.toBeEmpty()
    await expect(page.getByTestId('metric-won-amount')).toContainText('¥')
  })

  test('Sales 登录后首屏可见四项指标（个人口径）', async ({ page }) => {
    test.skip(!SALES_EMAIL || !SALES_PASSWORD, '需提供 E2E_SALES_EMAIL / E2E_SALES_PASSWORD')

    await login(page, SALES_EMAIL, SALES_PASSWORD)
    await expect(page).not.toHaveURL(/\/login$/)

    const metrics = page.getByTestId('dashboard-metrics')
    await expect(metrics).toBeVisible()
    for (const title of METRIC_TITLES) {
      await expect(metrics.getByText(title)).toBeVisible()
    }
  })
})
