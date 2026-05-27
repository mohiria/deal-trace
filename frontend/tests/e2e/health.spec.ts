import { expect, test } from '@playwright/test'

/**
 * Smoke E2E：通过 dev server 的 /api 代理打到后端 /health。
 *
 * 运行前置：另起两个进程
 *   1) backend：`mvn spring-boot:run`（DB_HOST/PORT/USER/PASSWORD 已配）
 *   2) frontend：`pnpm dev`
 * 然后 `pnpm test:e2e`。场景优先，不要求严格 Red-Green。
 */
test('frontend dev proxy reaches backend /health and returns SUCCESS envelope', async ({ request }) => {
  const response = await request.get('/api/health')

  expect(response.ok()).toBe(true)
  const body = await response.json()
  expect(body.code).toBe('SUCCESS')
  expect(body.data?.status).toBe('UP')
})
