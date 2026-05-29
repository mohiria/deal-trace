## Why

scaffold 路线图（`2026-05-27-scaffold-monorepo/design.md:28`）把 `permission` 列为待实现 capability，但从未单独建 change。授权规则（tech-arch §5.3 / PRD §7.1.12-13）的**行为**已分散实现并受测，却没有一份单一权威的 role×resource×action 矩阵契约，也没有任何测试保证**整个 API 面**都没有漏配守卫——每个 capability 只测自己的端点，新增端点忘加 `@PreAuthorize` 时无横切回归能抓到。本 change 把矩阵固化为单一契约，并补上「默认拒绝」整面回归。

## What Changes

- 新增 `permission` capability：把分散在 tech-arch §5.3 文字里的授权规则固化为**单一权威授权矩阵**（role×resource×action×期望码）+ 4 条聚合不变量。
- 新增横切回归 `PermissionMatrixTest`：
  - **INV-1 默认拒绝扫描**：用 Spring `RequestMappingHandlerMapping` 自动发现所有已注册端点，断言不在 permitAll 白名单（`GET /health`、`POST /auth/login` + 框架端点）者匿名访问一律 401——未来新增端点漏配守卫会被自动抓到。
  - **INV-2 角色专属矩阵**：ADMIN 专属 7 端点 Sales→403；SALES 专属 2 端点 Admin→403（显式期望表）。
  - **INV-3 停用 Sales 聚合不变量**：停用 Sales 不可登录/认领/被分配/被转移，四类动作一次性聚合断言。
  - **INV-4 后端强校验原则**：声明级——关键权限由后端三层守卫保证，不可仅靠前端。
- **不重述、不取代**既有 capability 的细节权限 scenario（lead 私海 404 防泄漏、各端点单点用例仍归原 capability）；permission 只在更高聚合高度断言整面一致性。
- 预期**零生产代码**（守卫已存在）；仅当扫描暴露守卫缺失/越权时，按「安全网」允许补最小修复（附 Red 证据）。

## Capabilities

### New Capabilities

- `permission`: 授权矩阵固化能力——拥有单一权威 role×resource×action 矩阵与 4 条聚合/矩阵级不变量（默认拒绝扫描、角色专属矩阵、停用 Sales 聚合、后端强校验声明），通过横切回归保证整个 API 面与矩阵一致。细节行为仍归各资源所属 capability，本 capability 不重述、不取代。

### Modified Capabilities

<!-- 无既有 capability 的行为契约发生变化；permission 仅在更高聚合高度新增不变量，不改写任何既有权限断言 -->

## Impact

- **新增测试**：`backend/src/test/.../permission/PermissionMatrixTest`（横切权限回归，真 MySQL 8.4）。
- **读取/依赖**：现有 `SecurityConfig`（permitAll + `/admin/**`→ADMIN + `anyRequest().authenticated()`）、6 处 `@PreAuthorize`、各 service 的 owner 校验——三层守卫现状。
- **去重边界**：与 `SecurityPathRuleTest`（仅 `/admin`、`/auth`）、`LeadNotInScopeTest`（私海隔离）、`LeadAssignTest`/`LeadTransferTest`（停用限制）不重复——permission 测整面默认拒绝 + 角色矩阵 + 停用聚合，不复制单端点细节用例。
- **可能的生产代码**：仅在扫描发现真实守卫缺失时，按安全网补最小修复（预期不触发）。
- **无 schema 变更、无新增端点、无既有接口/测试期望变更**。
