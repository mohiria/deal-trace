import { expect, test } from '@playwright/test'

/**
 * 关键用户旅程 E2E（场景优先，不要求严格 Red-Green）：
 *   登录 → 公海认领 → 进入我的线索 → 打开详情 → 追加进度 → 标记赢单后只读。
 *
 * 运行前置（prerequisite blocker，见 qa/lightweight-test-design.md）：
 *   1) backend：`mvn spring-boot:run`，并通过 DEALTRACE_ADMIN_EMAIL / DEALTRACE_ADMIN_PASSWORD
 *      注入初始 Admin（PRD §7.1）。
 *   2) 备数据：存在一个启用 Sales 账号，且公海中至少有一条可认领线索。
 *   3) frontend：`pnpm dev`。
 *   4) 设置 E2E_SALES_EMAIL / E2E_SALES_PASSWORD 与后端 Sales 一致，再 `pnpm test:e2e`。
 */

const SALES_EMAIL = process.env.E2E_SALES_EMAIL ?? ''
const SALES_PASSWORD = process.env.E2E_SALES_PASSWORD ?? ''

test.skip(
  !SALES_EMAIL || !SALES_PASSWORD,
  '需提供 E2E_SALES_EMAIL / E2E_SALES_PASSWORD（与后端启用 Sales 一致）且公海有可认领线索',
)

test('公海认领 → 我的线索 → 详情追加进度 → 赢单后只读', async ({ page }) => {
  // 登录
  await page.goto('/login')
  const inputs = page.locator('form input')
  await inputs.nth(0).fill(SALES_EMAIL)
  await inputs.nth(1).fill(SALES_PASSWORD)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)

  // 公海认领第一条
  await page.goto('/public-pool')
  const firstClaim = page.locator('.claim-btn').first()
  await expect(firstClaim).toBeVisible()
  await firstClaim.click()
  await expect(page.getByText('认领成功，已移入我的线索')).toBeVisible()

  // 我的线索 → 进入详情
  await page.goto('/my-leads')
  const firstLead = page.locator('.lead-link').first()
  await expect(firstLead).toBeVisible()
  await firstLead.click()
  await expect(page).toHaveURL(/\/leads\/\d+$/)

  // 追加进度
  await page.locator('.progress-content textarea').fill('E2E 自动化跟进记录')
  await page.locator('.progress-submit').click()
  await expect(page.getByText('E2E 自动化跟进记录')).toBeVisible()

  // 标记赢单
  await page.locator('.win-open').click()
  await page.locator('.win-amount input').fill('100000.00')
  await page.locator('.win-date input').fill('2026-05-30')
  await page.locator('.win-confirm').click()

  // 赢单后只读：写入口收起
  await expect(page.locator('.win-open')).toHaveCount(0)
  await expect(page.locator('.progress-form')).toHaveCount(0)
})
