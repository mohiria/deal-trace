# QA Test Report — frontend-admin

## Scope

- Change: `frontend-admin`（Admin 用户管理 + Admin 线索归属调度 + 隐藏系统日志入口）
- Spec: `openspec/changes/frontend-admin/specs/frontend-workbench/spec.md`（7 条 ADDED 需求）
- 被测代码：`src/api/accounts.ts`、`src/api/leads.ts`(归属追加)、`src/stores/accounts.ts`、`src/stores/leads.ts`(归属追加)、`src/views/UsersView.vue`、`src/views/LeadDetailView.vue`(归属区)、`src/components/navigation.ts`、`src/router/index.ts`

## Environment

- 单元 / 组件：vitest 3 + jsdom + @vue/test-utils + MSW（axios 边界拦截），`onUnhandledRequest:'error'`
- 类型：`vue-tsc -b`（strict + `exactOptionalPropertyTypes`）
- E2E：Playwright（真后端，env-gated）

## Results

| 层 | 命令 | 结果 |
| --- | --- | --- |
| 单元 + 组件（全量） | `pnpm vitest run` | **167/167 passed**（77 files, 0 failed） |
| 类型检查 | `vue-tsc -b` | **通过**（0 error） |
| E2E（Admin 旅程） | `playwright test --list tests/e2e/admin-flow.spec.ts` | 解析通过，1 test；**未设 E2E_ADMIN_EMAIL/PASSWORD 时 skip**（需真后端联调环境） |

### 本批次新增 / 扩展测试（全部 Green）

| 文件 | 用例数 | 覆盖 |
| --- | --- | --- |
| `src/api/accounts.spec.ts` | 4 | fetchAccounts/createSales(固定 SALES)/updateAccountStatus 命中路径 + VALIDATION_ERROR 归一 ApiError |
| `src/api/leads.spec.ts`（追加） | 4 | assign/recall/transfer 命中路径 + salesId 提交 + LEAD_ENDED_READONLY 归一 |
| `src/stores/accounts.spec.ts` | 7 | load/createSales 并入/setStatus 就地更新 + enabledSales 派生 + 错误透传 |
| `src/stores/leads.spec.ts`（追加） | 4 | assign/recall/transfer 刷新 currentLead + 错误透传 |
| `src/views/UsersView.spec.ts` | 10 | 列表(无密码/稳定序) + 创建(即时校验/成功/重复回落) + 启停(切换/自身隐藏/拒绝回落) |
| `src/views/LeadDetailView.spec.ts`（追加） | 12 | 归属区显隐(角色×归属态×闭单) + 候选 enabledSales/转移排除当前 + 成功/各 VALIDATION_ERROR/LEAD_ENDED_READONLY 回落 |
| `src/components/AppShell.spec.ts`（追加） | 1 | 系统日志入口对 ADMIN 不呈现 |
| `tests/e2e/admin-flow.spec.ts` | 1 | 建 Sales→列表可见→建线索→分配（env-gated） |

## Requirement Coverage（spec → 证据）

| Requirement | 证据 |
| --- | --- |
| Admin 查看账号列表 | UsersView.spec「渲染全部账号/无密码/稳定序」 |
| 系统日志入口不呈现 | AppShell.spec「对 ADMIN 不呈现」 + navigation.ts 移除条目 |
| Admin 创建 Sales | UsersView.spec「即时拦截/成功进列表/邮箱重复回落」 |
| Admin 启用与停用 | UsersView.spec「停用/启用切换/自身隐藏/拒绝回落」 |
| Admin 分配公海线索 | LeadDetailView.spec「仅分配/候选 enabledSales/成功/已有归属回落/已结束不呈现」 |
| Admin 回收名下线索 | LeadDetailView.spec「回收成功进公海/已在公海回落」 |
| Admin 转移名下线索 | LeadDetailView.spec「转移成功/排除当前归属/目标相同回落」 |

## Anti-Cheat 声明

- 未削弱断言、未删负例、未跳过任何测试。
- 唯一一处断言修订（UsersView「无密码字段」）是把过宽的 `html().not.toContain('password')`（误伤创建表单的 `sales-password` 类名）收窄为「账号列表区不含 passwordHash/password」，针对的是 spec「列表不含密码字段」的真实意图，未放宽需求。
- 归属入口前端显隐为体验前置，所有写动作均保留后端 `ApiError` 兜底回落（VALIDATION_ERROR/LEAD_ENDED_READONLY/FORBIDDEN/UNAUTHORIZED），不伪造成功。

## Known Gaps

- 系统日志查看（独立页 + 详情面板，PRD §7.8）本批次不交付：后端无读端点，已隐藏导航入口避免死链，spec 未声称交付该行为。待后端读端点就绪后另起 change。
- E2E 在无真后端 / 未注入 Admin 凭据时 skip；CI 全绿不代表 E2E 已跑通，需联调环境验证（residual risk，已在测试设计标注）。
