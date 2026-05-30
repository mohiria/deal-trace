import { expect, test } from '@playwright/test'

/**
 * 关键用户旅程 E2E（场景优先，不要求严格 Red-Green）：
 *   登录 → 新建客户 → 客户管理页搜索到该客户 → 经选择器选中 → 新建线索 → 在我的线索可见。
 *
 * 运行前置（prerequisite blocker，见 qa/lightweight-test-design.md）：
 *   1) backend：`mvn spring-boot:run`，并通过 DEALTRACE_ADMIN_EMAIL / DEALTRACE_ADMIN_PASSWORD
 *      注入初始 Admin（PRD §7.1）。
 *   2) 备数据：存在一个启用 Sales 账号。
 *   3) frontend：`pnpm dev`。
 *   4) 设置 E2E_SALES_EMAIL / E2E_SALES_PASSWORD 与后端 Sales 一致，再 `pnpm test:e2e`。
 *
 * 使用唯一时间戳生成客户名称与 USCI，避免与既有数据查重冲突（DUPLICATE_CUSTOMER）。
 */

const SALES_EMAIL = process.env.E2E_SALES_EMAIL ?? ''
const SALES_PASSWORD = process.env.E2E_SALES_PASSWORD ?? ''

test.skip(
  !SALES_EMAIL || !SALES_PASSWORD,
  '需提供 E2E_SALES_EMAIL / E2E_SALES_PASSWORD（与后端启用 Sales 一致）',
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

test('新建客户 → 搜索 → 选择器选中 → 新建线索 → 我的线索可见', async ({ page }) => {
  const stamp = Date.now().toString().slice(-8)
  const customerName = `E2E自动化客户${stamp}`
  const usci = buildValidUsci(stamp.padEnd(9, '0'))

  // 登录
  await page.goto('/login')
  const inputs = page.locator('form input')
  await inputs.nth(0).fill(SALES_EMAIL)
  await inputs.nth(1).fill(SALES_PASSWORD)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)

  // 新建客户
  await page.goto('/customers')
  await page.locator('.create-customer-open').click()
  await page.locator('.customer-name input').fill(customerName)
  await page.locator('.customer-usci input').fill(usci)
  await page.locator('.customer-confirm').click()
  await expect(page.getByText('客户创建成功')).toBeVisible()

  // 搜索到该客户
  await page.locator('.customer-search').fill(customerName)
  await expect(page.getByText(customerName)).toBeVisible()

  // 新建线索：经可搜索选择器选中该客户
  await page.locator('.create-lead-open').click()
  await page.locator('.cs-search').fill(customerName)
  await page.locator('.cs-option').first().click()
  // 业务类型 + 联系人 + 电话
  await page.locator('.lead-type').getByText('BIM咨询').click()
  await page.locator('.lead-contact-name input').fill('王工')
  await page.locator('.lead-contact-phone input').fill('13812345678')
  await page.locator('.lead-confirm').click()
  await expect(page.getByText('线索创建成功')).toBeVisible()

  // 我的线索可见（Sales 默认归己）
  await page.goto('/my-leads')
  await expect(page.getByText(customerName).first()).toBeVisible()
})
