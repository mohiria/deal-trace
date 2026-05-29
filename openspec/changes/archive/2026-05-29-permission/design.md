## Context

`permission` 是 scaffold 路线图列出但从未单独固化的 capability。/opsx:explore 已确认：tech-arch §5.3 / PRD §7.1.12-13 的每条授权规则都已被资源所属 capability 完整固化（lead 195/261/299/395/481、auth-account 172、customer 90/112、dashboard 6、progress-log 131 等），permission 在**细节层无物可拥有**。因此本 capability 定位为聚合/矩阵级契约 + 横切回归，而非重述细节。

授权现状为三层守卫：
1. `SecurityConfig` 路径级——`permitAll`（`/health`、`POST /auth/login`）+ `/admin/**`→ROLE_ADMIN + `anyRequest().authenticated()`。
2. 方法级 `@PreAuthorize`（6 处）——claim/release=SALES，assign/recall/transfer/listAll=ADMIN。
3. 服务层 owner 校验——Sales 访问他人线索返回 404 防泄漏。

## Goals / Non-Goals

**Goals:**
- 固化单一权威授权矩阵（role×resource×action×期望码）。
- 补「默认拒绝」整面回归：自动发现端点 → 漏配守卫可被捕获。
- 在聚合高度断言角色专属矩阵、停用 Sales 全面失能、后端强校验。

**Non-Goals:**
- 重述/取代既有 capability 的细节权限 scenario（404 防泄漏、单端点业务码）。
- 新增业务端点或权限模型（角色仍只有 Admin/Sales）。
- 引入权限框架/RBAC 表/动态权限（MVP 静态矩阵足够）。
- 前端权限入口（不在本 capability，且非可信边界）。

## Decisions

**D1. 冲突门姿态：只拥有聚合不变量，不重述细节。**
permission spec 显式声明「does not supersede / 不重述既有断言」。它在更高聚合高度断言整面一致性（如「全部非白名单端点匿名→401」），而非复制 lead/auth-account 的单端点 scenario。这样两份契约处于不同altitude，不构成 Requirement Conflict Gate 意义上的「同一行为两份契约」。
*备选*：把权限 scenario 从各 capability 挖出搬进 permission，令其反向引用。否决——需大改 7 份已归档 spec，削弱风险高。

**D2. INV-1 默认拒绝用自动发现。**
`PermissionMatrixTest` 注入 `RequestMappingHandlerMapping`，枚举所有 `HandlerMethod` 的 `PatternsRequestCondition`/`PathPatternsRequestCondition`，逐个对不在 permitAll 白名单者发匿名请求断言 401。白名单 = {`GET /health`、`POST /auth/login`} + 框架内置（`/error` 等，按路径前缀或 handler 包名排除）。核心价值：未来新增端点漏配守卫被自动捕获。
*备选*：硬编码端点表。否决（作为 INV-1 主体）——漏加端点不会被抓，丧失回归价值；但用作 INV-2 的期望表（见 D3）。

**D3. INV-2 角色期望用显式表。**
角色×端点的期望码（Sales 对 ADMIN 专属端点=403，Admin 对 SALES 专属端点=403）无法从注册信息推导，用显式参数化表断言。authenticated 级端点的「自己 200/他人 404」属各 capability 细节，permission 不在此断言。

**D4. 代码范围：预期零生产代码 + 安全网。**
所有非 permitAll 端点已落 `anyRequest().authenticated()`，6 个角色专属端点已配 `@PreAuthorize`，故 INV-1/INV-2 预期直接 Green。若自动发现扫描暴露某端点匿名可达（守卫缺失）或角色越权，按安全网补最小修复并附 Red→Green 证据。

**D5. 测试归属与去重。**
新增 `permission/PermissionMatrixTest`，测：整面默认拒绝（自动发现）、角色专属矩阵（显式表）、停用 Sales 聚合（登录+认领+分配+转移四断言）、后端强校验（构造越权直达请求）。与既有分散测试去重：`SecurityPathRuleTest`（仅 /admin、/auth）、`LeadNotInScopeTest`（私海 404）、`LeadAssignTest`/`LeadTransferTest`（停用单点）——permission 不复制其单端点细节，只做整面/聚合断言。

## Risks / Trade-offs

- [自动发现误把框架端点（`/error`、可能的 actuator）当业务端点导致误报] → 白名单显式排除框架 handler（按 handler 声明类包名 `com.dealtrace` 过滤，仅扫描本项目 controller），并显式列 permitAll 业务白名单。
- [INV-2 显式表与各 capability 单端点测试重叠] → 接受少量重叠：permission 表是「矩阵整体一致性」断言，capability 测试是「单端点行为」断言；altitude 不同，且 D1 已声明不取代。重叠是回归冗余而非契约冲突。
- [停用 Sales 聚合断言与 LeadAssign/TransferTest 重复] → permission 在「一处把四类动作一次性断言」提供矩阵视图价值；细节场景仍归原测试。
- [未来新增 permitAll 端点需同步白名单，否则 INV-1 误报] → 这正是设计意图：新增豁免须显式登记在白名单，强制审查。

## Migration Plan

无 schema、无端点变更。纯新增测试（可能含安全网级最小修复）。回滚即移除 permission capability 与 PermissionMatrixTest，不影响任何既有行为。

## Open Questions

无。矩阵、扫描方式、角色期望、代码范围、去重边界均在 explore 阶段敲定。
