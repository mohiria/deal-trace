import { expect, test } from '@playwright/test'

/**
 * view-system-log 关键旅程 E2E（场景优先）：
 *   Admin 登录 → 新建客户+线索（生成 LEAD_CREATE 日志）→ 进入详情看「系统日志」只读时间线
 *   → 推进阶段（生成 LEAD_STAGE_CHANGE）→ 刷新后时间线倒序（阶段变更在前）
 *   → 全局「系统日志」页可见行并可按动作筛选
 *   → 新建并登录 Sales：无「系统日志」导航入口，直达 /system-logs 被守卫挡回。
 *
 * 运行前置（同其它 e2e）：backend `mvn spring-boot:run`（注入初始 Admin）+ frontend dev +
 * 设 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD（与初始 Admin 一致）。
 */

const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? ''
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? ''

test.skip(!ADMIN_EMAIL || !ADMIN_PASSWORD, '需提供 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD（与后端初始 Admin 一致）')

/** 结构与校验位均合法的 18 位 USCI（GB 32100-2015）。 */
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

async function loginAs(page: import('@playwright/test').Page, email: string, password: string) {
  await page.goto('/login')
  const inputs = page.locator('form input')
  await inputs.nth(0).fill(email)
  await inputs.nth(1).fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login$/)
}

test('Admin：线索详情系统日志时间线（倒序）+ 全局日志页筛选', async ({ page }) => {
  const stamp = Date.now().toString().slice(-8)
  const customerName = `E2E日志客户${stamp}`
  const usci = buildValidUsci(stamp.padEnd(9, '0'))

  await loginAs(page, ADMIN_EMAIL, ADMIN_PASSWORD)

  // 新建客户 + 线索（Admin 创建默认入公海，生成 LEAD_CREATE 系统日志）
  await page.goto('/customers')
  await page.locator('.create-customer-open').click()
  await page.locator('.customer-name input').fill(customerName)
  await page.locator('.customer-usci input').fill(usci)
  await page.locator('.customer-confirm').click()
  await expect(page.getByText('客户创建成功')).toBeVisible()

  await page.locator('.create-lead-open').click()
  await page.locator('.cs-search:visible').fill(customerName)
  await page.locator('.cs-option:visible').first().click()
  await page.locator('.lead-type:visible').getByText('BIM咨询').click()
  await page.locator('.lead-contact-name:visible input').fill('王工')
  await page.locator('.lead-contact-phone:visible input').fill('13812345678')
  // 捕获创建响应拿 leadId（Admin 创建默认入公海，不在「我的线索」，直达详情更稳）
  const [createResp] = await Promise.all([
    page.waitForResponse((r) => /\/api\/leads$/.test(r.url()) && r.request().method() === 'POST'),
    page.locator('.lead-confirm:visible').click(),
  ])
  await expect(page.getByText('线索创建成功')).toBeVisible()
  const leadId = (await createResp.json()).data.id as number

  // 直达该线索详情
  await page.goto(`/leads/${leadId}`)
  await expect(page).toHaveURL(/\/leads\/\d+$/)

  // 系统日志只读时间线含「创建线索」
  const sysSection = page.locator('.detail-systemlog')
  await expect(sysSection).toBeVisible()
  await expect(sysSection.getByText('创建线索')).toBeVisible()

  // 推进阶段 → 生成 LEAD_STAGE_CHANGE（等待 PATCH 完成），刷新后时间线倒序（阶段变更在最前）
  await Promise.all([
    page.waitForResponse((r) => /\/leads\/\d+\/stage$/.test(r.url()) && r.request().method() === 'PATCH'),
    page.locator('.stage-btn').first().click(),
  ])
  await page.reload()
  await expect(page.locator('.detail-systemlog .event').first()).toContainText('阶段变更')

  // 全局系统日志页：表格可见，按动作筛选到「阶段变更」
  await page.goto('/system-logs')
  const table = page.locator('[data-test="systemlog-table"]')
  await expect(table).toBeVisible()
  await page.locator('.syslog-filter-action').click()
  await page.locator('.arco-select-option', { hasText: '阶段变更' }).click()
  await expect(table.getByText('阶段变更').first()).toBeVisible()
})

test('Sales：无「系统日志」导航入口，/system-logs 被守卫挡回', async ({ page }) => {
  const stamp = Date.now().toString().slice(-8)
  const salesEmail = `e2e.syslog.sales.${stamp}@dealtrace.local`
  const salesPassword = 'Pw123456!'

  // Admin 建一个 Sales
  await loginAs(page, ADMIN_EMAIL, ADMIN_PASSWORD)
  await page.goto('/users')
  await page.locator('.create-sales-open').click()
  await page.locator('.sales-email input').fill(salesEmail)
  await page.locator('.sales-name input').fill(`E2E日志销售${stamp}`)
  await page.locator('.sales-password input').fill(salesPassword)
  await page.locator('.sales-confirm').click()
  await expect(page.getByText('Sales 账号创建成功')).toBeVisible()

  // 退出登录 → 以 Sales 登录
  await page.evaluate(() => window.localStorage.clear())
  await loginAs(page, salesEmail, salesPassword)

  // 侧边导航无「系统日志」入口（ADMIN-only）
  await expect(page.locator('.shell-nav').getByText('系统日志')).toHaveCount(0)

  // 直达 /system-logs 被 requiresAdmin 守卫挡回（不停留在该路由）
  await page.goto('/system-logs')
  await expect(page).not.toHaveURL(/\/system-logs$/)
})
