import { expect, test } from '@playwright/test'

/**
 * 关键 Admin 旅程 E2E（场景优先，不要求严格 Red-Green）：
 *   登录 Admin → 用户管理创建 Sales → 列表可见（启用）
 *   → 新建客户 + 线索（Admin 创建默认入公海）→ 我的线索进入该线索详情
 *   → 分配给新建的 Sales → 当前归属更新。
 *
 * 运行前置（prerequisite blocker，见 qa/lightweight-test-design.md）：
 *   1) backend：`mvn spring-boot:run`，DEALTRACE_ADMIN_EMAIL / DEALTRACE_ADMIN_PASSWORD 注入初始 Admin（PRD §7.1）。
 *   2) frontend：`pnpm dev`。
 *   3) 设置 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD 与后端初始 Admin 一致，再 `pnpm test:e2e`。
 *
 * 用唯一时间戳生成 Sales 邮箱 / 客户名称 / USCI，避免与既有数据冲突。
 */

const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? ''
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? ''

test.skip(
  !ADMIN_EMAIL || !ADMIN_PASSWORD,
  '需提供 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD（与后端初始 Admin 一致）',
)

/** 计算一个结构与校验位均合法的 18 位 USCI（GB 32100-2015）。 */
function buildValidUsci(serial: string): string {
  const chars = '0123456789ABCDEFGHJKLMNPQRTUWXY'
  const weights = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28]
  const body = `91110000${serial}`.slice(0, 17)
  let sum = 0
  for (let i = 0; i < 17; i++) {
    sum += chars.indexOf(body[i]!) * weights[i]!
  }
  const check = (31 - (sum % 31)) % 31
  return body + chars[check]
}

test('Admin 创建 Sales → 列表可见 → 新建线索 → 分配给该 Sales', async ({ page }) => {
  const stamp = Date.now().toString().slice(-8)
  const salesEmail = `e2e.sales.${stamp}@dealtrace.local`
  const salesName = `E2E销售${stamp}`
  const customerName = `E2E管理客户${stamp}`
  const usci = buildValidUsci(stamp.padEnd(9, '0'))

  // 登录 Admin
  await page.goto('/login')
  const inputs = page.locator('form input')
  await inputs.nth(0).fill(ADMIN_EMAIL)
  await inputs.nth(1).fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)

  // 用户管理创建 Sales
  await page.goto('/users')
  await page.locator('.create-sales-open').click()
  await page.locator('.sales-email input').fill(salesEmail)
  await page.locator('.sales-name input').fill(salesName)
  await page.locator('.sales-password input').fill('Pw123456!')
  await page.locator('.sales-confirm').click()
  await expect(page.getByText('Sales 账号创建成功')).toBeVisible()

  // 列表可见新 Sales（启用）
  await expect(page.locator('.users-table').getByText(salesEmail)).toBeVisible()

  // 新建客户 + 线索（Admin 创建默认入公海）
  await page.goto('/customers')
  await page.locator('.create-customer-open').click()
  await page.locator('.customer-name input').fill(customerName)
  await page.locator('.customer-usci input').fill(usci)
  await page.locator('.customer-confirm').click()
  await expect(page.getByText('客户创建成功')).toBeVisible()

  await page.locator('.create-lead-open').click()
  await page.locator('.cs-search').fill(customerName)
  await page.locator('.cs-option').first().click()
  await page.locator('.lead-type').getByText('BIM咨询').click()
  await page.locator('.lead-contact-name input').fill('王工')
  await page.locator('.lead-contact-phone input').fill('13812345678')
  await page.locator('.lead-confirm').click()
  await expect(page.getByText('线索创建成功')).toBeVisible()

  // 进入该线索详情（Admin 在我的线索看到全部线索）
  await page.goto('/my-leads')
  await page.locator('.lead-link', { hasText: customerName }).first().click()
  await expect(page).toHaveURL(/\/leads\/\d+$/)

  // 公海线索 → 分配给新建 Sales
  await page.locator('.assign-open').click()
  await page.locator('.assign-target').getByText(salesName).click()
  await page.locator('.assign-confirm').click()
  await expect(page.getByText('已分配')).toBeVisible()

  // 当前归属更新（不再为公海）
  await expect(page.getByText('销售 #')).toBeVisible()
})
