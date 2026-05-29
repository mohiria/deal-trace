# Lightweight Test Design — permission 授权矩阵固化

## Context

- Requirement / Spec: tech-arch §5.3 / PRD §7.1.12-13；`openspec/changes/permission/specs/permission/spec.md`（5 Requirement / 10 Scenario）
- Change summary: 固化权威 role×resource×action 授权矩阵 + 4 条聚合不变量；新增横切 `PermissionMatrixTest`。聚合高度断言，不重述各 capability 单端点细节。
- Target modules: `PermissionMatrixTest`（横切）；读取 `RequestMappingHandlerMapping` 自动发现端点；依赖既有三层守卫（SecurityConfig / @PreAuthorize / service owner 校验）。
- Test environment: 真 MySQL 8.4（Testcontainers，禁 H2 禁 mock）；不与 dev backend smoke 并发；回滚测试内禁 raw TRUNCATE。context-path `/api`，但 MockMvc 与 RequestMappingInfo 均不含 `/api` 前缀，一致。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance（tech-arch §5.3、PRD §7.1.12-13、§11.1）
- [x] Existing behavior baseline: SecurityConfig 三层守卫、ErrorCode（UNAUTHORIZED/FORBIDDEN/NOT_FOUND）、JwtAuthenticationFilter 每请求校验状态
- [x] API contract / auth rules / error shape（ApiResponse 信封；EntryPoint 401；AccessDeniedHandler 403）
- [x] 既有测试：SecurityPathRuleTest（/admin、/auth）、LeadNotInScopeTest（私海 404）、LeadAssignTest/LeadTransferTest（停用单点 VALIDATION_ERROR）
- [x] Code structure / 端点清单（25 业务端点 + /health）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 整面默认拒绝 401 | 无横切测试（仅 anyRequest authenticated 配置） | spec INV-1 | extends（新增聚合断言） | tech-arch §5.3 | Proceed |
| 角色专属 403 | SecurityPathRuleTest 仅 /admin；@PreAuthorize 各端点 | spec INV-2 | extends（矩阵级聚合，不取代单端点） | tech-arch §5.3 | Proceed |
| 停用 Sales 失能 | auth-account 172 / LeadAssignTest 单点 | spec INV-3 | extends（四类一次性聚合） | PRD §7.1 | Proceed |

无 conflicts。permission 在聚合 altitude 断言，显式声明不取代既有 capability 细节断言（design D1）。

## Test Points

| Test point | Source | Design method | Test layer | Input / precondition | Expected result | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 整面匿名→401（自动发现） | INV-1/S1 | 全枚举扫描 | API/integration | 自动发现全部本项目端点，匿名逐个访问非白名单者 | 每个 401 / UNAUTHORIZED | P0 | `PermissionMatrixTest#anon_allNonWhitelistedEndpointsReturn401` |
| /health 匿名可达 | INV-1/S2 | 边界 | API/integration | 匿名 GET /health | 200 | P0 | `PermissionMatrixTest#whitelist_healthReachable` |
| /auth/login 匿名进 controller | INV-1/S2 | 边界 | API/integration | 匿名有效凭证 POST /auth/login | 200 + 令牌（证明 permitAll） | P0 | `PermissionMatrixTest#whitelist_loginReachable` |
| 漏配守卫被回归捕获 | INV-1/S3 | 设计性 | API/integration | 自动发现机制 | 新端点不在白名单且无守卫→断言失败 | P0 | 由扫描机制本身保证（同 S1） |
| Sales→ADMIN 专属 7 端点 403 | INV-2/S1 | 显式决策表 | API/integration | Sales 令牌访问 7 端点 | 各 403 / FORBIDDEN | P0 | `PermissionMatrixTest#sales_adminOnlyEndpointsReturn403` |
| Admin→SALES 专属 2 端点 403 | INV-2/S2 | 显式决策表 | API/integration | Admin 令牌访问 claim/release | 各 403 / FORBIDDEN | P0 | `PermissionMatrixTest#admin_salesOnlyEndpointsReturn403` |
| 停用 Sales 不可登录 | INV-3/S1 | 边界 | API/integration | 停用 Sales 凭证登录 | 401 / 无有效令牌 | P0 | `PermissionMatrixTest#disabledSales_cannotLogin` |
| 停用 Sales 不可认领 | INV-3/S2 | 边界 | API/integration | 停用 Sales 令牌认领公海 | 401（filter 拒）/ 归属不变 | P0 | `PermissionMatrixTest#disabledSales_cannotClaim` |
| 停用 Sales 不可被分配/转移 | INV-3/S3 | 边界 | API/integration | Admin 分配/转移给停用 Sales | 拒绝 / 归属不落停用 Sales | P0 | `PermissionMatrixTest#disabledSales_cannotBeAssignedOrTransferred` |
| 后端强校验（绕前端直达） | INV-4/S1 | 设计性 | API/integration | 匿名/错角色直达后端 | 后端按矩阵拒绝（401/403） | P1 | `PermissionMatrixTest#backendEnforcesWithoutFrontend` |

## TDD Candidates

| Test point | Initial failing test | Why fail before | Expected Red reason | Minimal pass | 
| --- | --- | --- | --- | --- |
| 整面 401 / 角色 403 | `PermissionMatrixTest` | 守卫已存在，预期直接 Green（非典型 Red） | — | 守卫现状已满足 |

> 说明（design D4）：本 capability 是对既有守卫的**横切回归固化**，守卫已存在，INV-1/INV-2 预期直接 Green，非典型 Red-Green。真正价值是回归保护——若扫描暴露某端点匿名可达或越权，则按安全网补最小修复并附 Red→Green 证据。该「扫描即 Red 探测器」本身就是 TDD 的横切形态。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| INV-4 后端强校验声明 | 原则性声明，非单一可执行行为 | 由 INV-1/INV-2 的直达请求用例共同覆盖 + 1 条显式断言 | 低 |

## Prerequisite Blockers

无（守卫与端点均已就绪，无需迁移）。

## 测试数据与构造策略

沿用 SecurityPathRuleTest seed 模式：
1. account：admin（ENABLED/ADMIN）、salesA（ENABLED/SALES）、disabledSales（DISABLED/SALES），密码均 `p@ssw0rd`。
2. customer + 两条 lead：一条公海（owner=null，用于认领/分配）、一条 salesA 名下（用于转移）。
3. 令牌：`JwtService.generateToken(account)`——对 disabledSales 也能生成（filter 在请求时才查状态拒绝），用于验证「停用令牌认领→401」。
4. 自动发现：注入 `RequestMappingHandlerMapping`，`getHandlerMethods()` 枚举；按 handler 声明类包名 `com.dealtrace` 过滤排除框架端点（/error 等）；path 模板 `{id}` 替换为 `1`（401/403 发生在认证/授权层，先于业务，占位值无妨）。
5. permitAll 白名单：`GET /health`、`POST /auth/login`（从 401 扫描中排除）。
6. 归属不变验证：分配/转移后 `leadMapper.selectById` 断言 owner_sales_id 未变为 disabledSales.id。

## Coverage Closure

- [ ] 每个 in-scope test point 有 coverage artifact（实现后回填）。
- [ ] 测试已执行并记录结果。
- [ ] 去重确认：不复制 SecurityPathRuleTest/LeadNotInScopeTest/LeadAssignTest 单端点用例，只做整面/聚合断言。

## Notes

- Uncovered: authenticated 级端点的「自己 200/他人 404」属各 capability 细节，permission 不断言。
- Remaining risks: 自动发现误纳框架端点 → 包名过滤兜底。
- Execution evidence: 实现后回填。
