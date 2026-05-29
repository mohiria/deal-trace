import { expect, test } from '@playwright/test'

/**
 * 关键用户旅程 E2E（场景优先，不要求严格 Red-Green）：
 *   登录 → 进入工作台 → 登出 → 受保护区被拦截回登录。
 *
 * 运行前置（prerequisite blocker，见 qa/lightweight-test-design.md）：
 *   1) backend：`mvn spring-boot:run`，并通过 DEALTRACE_ADMIN_EMAIL / DEALTRACE_ADMIN_PASSWORD
 *      注入初始 Admin（PRD §7.1：认证由部署配置注入）。
 *   2) frontend：`pnpm dev`。
 *   3) 设置 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD 与后端初始 Admin 一致，再 `pnpm test:e2e`。
 */

const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? process.env.DEALTRACE_ADMIN_EMAIL ?? ''
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? process.env.DEALTRACE_ADMIN_PASSWORD ?? ''

test.skip(
  !ADMIN_EMAIL || !ADMIN_PASSWORD,
  '需提供 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD（与后端初始 Admin 一致）',
)

test('登录 → 工作台 → 登出 → 受保护区被拦截回登录', async ({ page }) => {
  // 未登录访问根路径 → 被守卫拦截到登录页
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)

  // 输入凭据登录
  const inputs = page.locator('form input')
  await inputs.nth(0).fill(ADMIN_EMAIL)
  await inputs.nth(1).fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: '登录' }).click()

  // 进入工作台：URL 离开 /login，且工作台外壳导航出现
  await expect(page).not.toHaveURL(/\/login$/)
  await expect(page.getByText('销售工作台')).toBeVisible()

  // 登出 → 回到登录页
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login$/)

  // 登出后直访受保护区 → 仍被拦截回登录
  await page.goto('/customers')
  await expect(page).toHaveURL(/\/login$/)
})
