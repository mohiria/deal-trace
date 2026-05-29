# QA Test Report — permission 授权矩阵固化

## Conclusion

- Overall result: PASS
- Requirement / change ID: `permission`（tech-arch §5.3 / PRD §7.1.12-13 / §11.1；`specs/permission/spec.md`）
- QA owner: vibe-coding-qa（apply 阶段）
- Date: 2026-05-29
- Summary: 把分散在 tech-arch §5.3 的授权规则固化为单一权威矩阵 + 4 条聚合不变量，新增横切 `PermissionMatrixTest`（9 测试）全绿；全量回归 246/246 绿，零回归。自动发现扫描确认整个 API 面默认拒绝、角色专属矩阵成立，未发现守卫缺口（安全网未触发）。零生产代码、无新增 Flyway 迁移、无新增业务端点。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | No | 本 capability 为横切集成断言，无纯函数单元 |
| API/integration | Yes | `PermissionMatrixTest`（真 MySQL 8.4） |
| E2E | No | 前端不在范围 |
| Regression | Yes | 全量 246 |
| Runtime QA validation | No | — |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Test action | Code action |
| --- | --- | --- | --- | --- | --- |
| 整面默认拒绝 401 | 仅 `anyRequest().authenticated()` 配置，无横切测试 | INV-1 | extends | Add（自动发现扫描） | Keep |
| 角色专属 403 | SecurityPathRuleTest 仅 /admin；@PreAuthorize 各端点 | INV-2 | extends（矩阵级聚合） | Add（显式表） | Keep |
| 停用 Sales 失能 | auth-account 172 / LeadAssignTest 单点 | INV-3 | extends（四类聚合） | Add | Keep |

无 conflicts；permission 在聚合 altitude 断言，spec 显式声明不取代既有 capability 细节（design D1）。未修改/删除任何既有测试或既有 spec。

## TDD Summary

| Test point | Source | Red/Green | Evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- |
| 整面匿名 401（自动发现） | INV-1 | Green（守卫已存在；扫描即 Red 探测器） | `mvn test -Dtest=PermissionMatrixTest` PASS | `PermissionMatrixTest#anon_allNonWhitelistedEndpointsReturn401` | PASS |
| 角色专属 403 | INV-2 | Green | 同上 | `#sales_adminOnlyEndpointsReturn403` / `#admin_salesOnlyEndpointsReturn403` | PASS |
| 停用 Sales 聚合 | INV-3 | Green | 同上 | `#disabledSales_*` | PASS |

> design D4：本 capability 是对既有三层守卫的横切回归固化，守卫已存在故 INV-1/INV-2 直接 Green（非典型 Red-Green）。自动发现扫描充当「未来漏配守卫的 Red 探测器」——这是其核心回归价值。安全网（task 6.2）未触发，因扫描确认当前无守卫缺口。

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| API/integration | `PermissionMatrixTest`（9） | `mvn test -Dtest=PermissionMatrixTest` | PASS | Tests run: 9, Failures: 0, Errors: 0（真 MySQL，~27s） |
| Regression | 全量 | `mvn test` | PASS | Tests run: 246, Failures: 0, Errors: 0 |

## Coverage Summary

| Test point | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- |
| 整面匿名→401（≥20 端点自动发现，防真空） | 全部非白名单端点匿名返回 401/UNAUTHORIZED | `PermissionMatrixTest#anon_allNonWhitelistedEndpointsReturn401` | COVERED |
| /health 匿名可达 | 200 | `#whitelist_healthReachableAnonymously` | COVERED |
| /auth/login 匿名进 controller | 有效凭证 200 + token（permitAll 生效） | `#whitelist_loginReachesControllerAnonymously` | COVERED |
| Sales→ADMIN 专属 7 端点 403 | 各 403/FORBIDDEN | `#sales_adminOnlyEndpointsReturn403` | COVERED |
| Admin→SALES 专属 2 端点 403 | 各 403/FORBIDDEN | `#admin_salesOnlyEndpointsReturn403` | COVERED |
| 停用 Sales 不可登录 | 401，无 token | `#disabledSales_cannotLogin` | COVERED |
| 停用 Sales 不可认领 | token 被 filter 401，归属不变 | `#disabledSales_cannotClaim_tokenRejectedOwnershipUnchanged` | COVERED |
| 停用 Sales 不可被分配/转移 | assign/transfer 400，归属不落停用 Sales | `#disabledSales_cannotBeAssignedOrTransferred_ownershipNeverLands` | COVERED |
| 后端强校验（绕前端直达） | 匿名写端点 401 + 错角色专属端点 403 | `#backendEnforcesWithoutFrontend_directRequestsRejected` | COVERED |

## Regression Scope

- Changed behavior: 无（纯新增横切测试）。
- Directly impacted old behavior: 无。
- Requirement-driven additions: +9（无修改/删除既有测试）。
- Regression risk level: Low。
- Selected regression: 全量 246 一次跑通。

## Remaining Risks

- Uncovered: authenticated 级端点「自己 200/他人 404」属各 capability 细节，permission 不断言（design D1 去重）。
- 设计性保护：自动发现 ≥20 端点的最小计数断言防止发现机制失效导致真空通过。
- 注意：新增 permitAll 端点须同步白名单，否则 INV-1 会失败——这是设计意图（强制审查豁免）。
- 集成测试不可与 dev backend smoke 并发共用 dealtrace 实例（项目 memory）。

## Final Statement

permission capability 按 spec 全部落地：9 个横切测试全绿，全量回归 246/246 绿。INV-1（整面默认拒绝，自动发现 ≥20 端点）、INV-2（角色专属 403）、INV-3（停用 Sales 四类聚合失能）、INV-4（后端强校验）均有覆盖证据。零生产代码、无新增迁移、无新增端点；未改动任何既有测试或 spec。安全网未触发（当前 API 面无守卫缺口）。结论 PASS。
