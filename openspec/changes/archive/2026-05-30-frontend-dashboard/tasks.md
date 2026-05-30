## 1. 确认后端响应契约（解除 Open Questions）

- [x] 1.1 已查证：`monthlyLossRate` 为**比率**（`DashboardService.lossRate` = lost/ended, scale 4；`DashboardControllerTest` 断言 `0.4`/`0.25`/`0.0`/`null`）；后端无自定义 Jackson 配置，`BigDecimal` 序列化为 **JSON 数字**（测试断言 `monthlyWonAmount` = `150000.50`/`0`）→ 前端类型为 `number` / `number | null`
- [x] 1.2 已固定契约：`formatLossRate(rate: number|null)` —— `null→'--'`、`0→'0%'`、比率 ×100 百分比（至多 1 位小数、整数不带 `.0`）；`formatWonAmount(amount: number)` —— `0→'¥0'`、其余千分位人民币（写入 design D1/D3 与 qa 设计）

## 2. QA 测试设计（先于生产代码）

- [x] 2.1 已产出 `qa/lightweight-test-design.md`，覆盖 spec 五项需求与边界（含 Conflict Gate、Test Points 表）
- [x] 2.2 已在 qa 设计标注分层依据（Layering Rationale：纯函数/契约→Unit，三态/只读→Component(MSW)，关键旅程→E2E）

## 3. API 封装（TDD）

- [x] 3.1 Red：`src/api/dashboard.spec.ts` 已写（命中 `/dashboard`、无视角参数、unwrap 形状、null 透传、业务错误 ApiError）；模块缺失时 Red
- [x] 3.2 Green：`src/api/dashboard.ts` 已实现（`DashboardView` 6 字段 `number`/`number|null` + `fetchDashboard`），4/4 测试转绿

## 4. 格式化纯函数（TDD）

- [x] 4.1 Red：`src/utils/dashboard.spec.ts` 已写 `formatLossRate` 6 例（`--`/`0%`/`40%`/`25%`/`18.2%`/无 `.0`）
- [x] 4.2 Red：同文件 `formatWonAmount` 4 例（`¥0`/`¥150,000.5`/`¥386,000`/`¥80,000`）
- [x] 4.3 Green：`src/utils/dashboard.ts` 实现两纯函数（`Intl.NumberFormat`），10/10 转绿

## 5. 看板视图（TDD）

- [x] 5.1 Red：`src/views/DashboardView.spec.ts` 已写（8 例：四指标渲染、只读 GET、加载态、失败+重试、重试成功、流失率 `--`/`0%`、金额 `¥0`）；组件缺失时 Red
- [x] 5.2 Green：`src/views/DashboardView.vue` 已实现（三态 ref、四卡栅格 `--dt-*` token + Arco `a-spin`/`a-button`、格式化函数、重试），8/8 转绿
- [x] 5.3 已验证：catch 不按 `UNAUTHORIZED` 分支路由（交拦截器清退，D4）；"只读 GET" 测试断言请求方法仅 `['GET']`，无写请求

## 6. 路由接线

- [x] 6.1 已把 `workbench` 路由组件由 `PlaceholderView` 改为 `DashboardView`（`router/index.ts`），其余路由不动；`PlaceholderView` 仍被 contracts/users/system-logs 复用
- [x] 6.2 已验证：`authGuard` 基于 route meta（未改），未登录访问 `/` 仍被拦截到登录入口（既有 `guards.spec.ts` 全绿）；全量 125 测试通过、`vue-tsc -b` EXIT=0

## 7. E2E 与回归

- [x] 7.1 已写 `tests/e2e/dashboard.spec.ts`（Admin / Sales 各一条旅程，场景优先），并配 `testIdAttribute: 'data-test'`；`playwright test --list` 通过。本环境无运行后端 + 凭据，按 QA constitution `test.skip`，未在本环境执行（与既有 E2E 一致）
- [x] 7.2 全量 `npx vitest run` 18 文件 / 125 测试 PASS、`vue-tsc -b` EXIT=0，无回归；已补 `qa/regression-impact-analysis.md` 与 `qa/qa-test-report.md`，两者 qa_artifacts check PASS
- [x] 7.3 证据已收集：四指标渲染（`DashboardView.spec.ts#renderFour`）、流失率 `--`/`0%`/`40%`（`utils/dashboard.spec.ts` + 视图）、加载/失败+重试三态、只读仅 `['GET']`、无 Tailwind（视图仅用 `--dt-*` token + Arco `a-spin`/`a-button`）；鉴权清退交既有拦截器
