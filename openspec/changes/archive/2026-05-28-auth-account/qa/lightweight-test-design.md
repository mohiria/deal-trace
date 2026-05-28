# Lightweight Test Design — auth-account

## Context

- **Requirement / Spec**：`openspec/changes/auth-account/specs/auth-account/spec.md`（10 个 ADDED Requirement、约 30 个 Scenario）；权威源 PRD §7.1、tech-arch §5.1 / §5.2 / §9.4
- **Change summary**：填实 scaffold 留下的 `JwtAuthenticationFilter` token 解析逻辑；新增 `account` 表与 `V2__account.sql`；登录 / `/me` / 改密三个用户端点；Admin 创建 Sales / 列表 / 启停三个管理端点；启动注入初始 Admin；`SystemLogPort` NoOp 端口；SecurityConfig 收紧。
- **Target modules / APIs / pages**：
  - 后端 main：`com.dealtrace.account.*`、`com.dealtrace.auth.*`、`com.dealtrace.security.*`（补全）、`com.dealtrace.systemlog.*`、`com.dealtrace.bootstrap.*`、`db/migration/V2__account.sql`、`application*.yml`
  - 后端 test：`backend/src/test/java/com/dealtrace/account/**`、`com/dealtrace/auth/**`、`com/dealtrace/security/**`、`com/dealtrace/bootstrap/**`
  - 前端：本 change 不实现登录页；前端 401 处理延后到 frontend-workbench
- **Test environment / constraints**：
  - 云端 MySQL 8.4 `dealtrace` 库（同 scaffold；`DB_HOST/DB_PORT/DB_USER/DB_PASSWORD` 已配置）
  - 测试默认 `@Transactional + @Rollback`；涉及"状态变更后跨事务可见"的场景必须用 `MultiTransactionalIntegrationTest` 并显式 `tablesToTruncate`
  - 真 MySQL 8.4（tech-arch §12 钉死）：禁 H2、禁 mock DataSource、禁 Mock BCrypt
  - JWT secret 与初始 Admin 邮箱 / 密码在 `application-test.yml` 给出测试默认值，无需依赖用户级环境变量

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria：`auth-account/spec.md`（10R/30S）；PRD v3.3 §7.1（账号管理与登录）、§11.1（账号 API）；tech-arch §5.1 鉴权机制 / §5.2 角色矩阵 / §9.4 脱敏（本 change 不直接产出脱敏字段，但需保持响应**不**含敏感字段）
- [x] Existing behavior baseline：scaffold 留下的 `JwtAuthenticationFilter`（仅读 header、不解析）、`JwtAuthenticationEntryPoint`（生成 UNAUTHORIZED 信封）、`SecurityConfig`（permitAll `/health`，其余 authenticated）；既有测试 `SecurityScaffoldTest` / `HealthControllerTest` / `GlobalExceptionHandlerTest` / `ConnectivitySmokeTest` / `MultiTransactionalBasePoCTest`
- [x] Data model / field rules：design D3（account 表 schema）；password BCrypt 60 字符；role / status VARCHAR(16) 字符串枚举
- [x] API contract / auth rules / error shape：design D6（API 路径）、D7（错误码 message 矩阵）、`ApiResponse{code, message, data}` 信封
- [x] UI states / user roles / user paths：本 change 仅 API；前端 401 处理延后
- [x] Code structure / changed code：design D2（Filter 流程）、D4（AdminBootstrap）、D5（SystemLogPort）、D9（`@AuthenticationPrincipal AccountPrincipal`）
- [x] Existing tests / historical defects：scaffold 8 个 case 全 PASS；本 change 改动 SecurityConfig / JwtAuthenticationFilter / JwtAuthenticationEntryPoint，需要保证既有 8 case 无回归
- [x] Test data / credentials / mocks：测试在每个测试方法用 `AccountMapper` 直接插入账号（含 BCrypt hash），不用 mock；JWT secret 用 `application-test.yml` 提供的稳定字符串

## Requirement Authority / Conflict Gate

本 change 是新增 capability + 改造 scaffold 内 stub，**无与既有契约的冲突**：

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| `JwtAuthenticationFilter` token 解析逻辑 | scaffold "仅读 header 不解析" 占位 | auth-account spec R2 + design D2 | extends | PRD §7.1 + design | Proceed |
| `JwtAuthenticationEntryPoint` message 内容 | scaffold 写死 `"未认证或认证已过期"` | auth-account spec R1/R5（要 message 区分"账号已停用"/"原密码错误"） | amends | spec + design D7 | Proceed（保留 code=UNAUTHORIZED 信封，仅扩展 message 取值） |
| `SecurityConfig` 放行规则 | scaffold 仅 `/health` permitAll、其余 authenticated | design D6（新增 `/auth/login` permitAll、`/admin/**` hasRole ADMIN） | extends | spec + design | Proceed |
| `ApiResponse` 信封形状 | scaffold `{code, message, data}` | 沿用 | 无变化 | platform-foundation | Proceed |
| `ErrorCode` 枚举项 | scaffold 6 个通用项 | 沿用（不新增；用 D7 message 区分子原因） | 无变化 | design D7 | Proceed |
| 测试 fixture `/test/protected` 端点 | scaffold `TestThrowController @Profile("test")` | 本 change Filter 测试复用其 GET `/test/protected` | extends | scaffold + tasks.md §4.1/§11.3 | Proceed |
| `account` 表 schema | scaffold 无业务表（V1 仅 `SELECT 1`） | design D3 | extends | spec R10 + design | Proceed |

无 `conflicts` 关系——继续。

## Test Points

`Coverage artifact` 在 Red/Green 完成后由 apply 阶段在 qa-test-report 中钉死 `#testName` 与路径；本表先列出**预期路径**与**测试入口名**，apply 时回填。

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| account 表 + uk_account_email 已被 Flyway V2 应用 | spec R10 + design D3 | smoke | API/integration | 启动后查 information_schema | `account` 表存在、`uk_account_email` UNIQUE 索引存在 | `JdbcTemplate` 查询结果 | P0 | `backend/src/test/java/com/dealtrace/account/AccountSchemaMigrationTest.java#tableAndUniqueIndexExist` |
| 登录成功签发 token + 返回脱敏字段 | spec R1 / scenario 1 | 等价类 | API/integration | DB 中存在 ENABLED 账号 + BCrypt hash | 200 + `code=SUCCESS` + data.token 非空 + data.email/name/role 一致 + 响应不含 passwordHash | 响应体字段穷举 | P0 | `LoginControllerTest#enabledUserWithCorrectPasswordReceivesToken` |
| 邮箱不存在 / 密码错统一 401 + message 不可区分 | spec R1 / scenario 2,3 | 等价类 + 边界 | API/integration | 不存在的邮箱；存在邮箱+错密码 | 401 + `UNAUTHORIZED` + message 在两场景中**相同文本**（"邮箱或密码错误"） | 响应体 message 字符串相等比较 | P0 | `LoginControllerTest#nonExistentEmailAndWrongPasswordShareSameMessage` |
| 停用账号登录 401 + message="账号已停用" | spec R1 / scenario 4 | 等价类 | API/integration | DB 中 DISABLED 账号 + 正确密码 | 401 + `UNAUTHORIZED` + message="账号已停用" + 响应无 token | 响应体 message + 字段缺失 | P0 | `LoginControllerTest#disabledAccountLoginRejectedWithStatusMessage` |
| 有效 token 携带访问受保护端点放行 | spec R2 / scenario 1 | 等价类 | API/integration | 已认证的 ENABLED 账号 token | 200 + 业务端点结果 | HTTP 200 + body 内容 | P0 | `JwtAuthenticationFilterTest#validTokenForEnabledAccountIsAdmitted` |
| 缺失 Authorization 头访问受保护端点 401 | spec R2 / scenario 2 | 等价类 | API/integration | 不带 Authorization | 401 + `UNAUTHORIZED` | HTTP + 响应体 | P0 | `JwtAuthenticationFilterTest#missingAuthorizationHeaderRejected`（既有 `SecurityScaffoldTest` 已覆盖，本 change 保留回归） |
| 非法 / 过期 token → 401 不泄漏细节 | spec R2 / scenario 3 | 异常分支 | API/integration | 携带乱码 token | 401 + `UNAUTHORIZED` + message 不含 "signature" / "expired" 文本 | 响应体字段穷举 | P0 | `JwtAuthenticationFilterTest#malformedTokenRejectedWithoutLeakingDetail` |
| 停用账号有效 token 在下次请求被拒 | spec R2 / scenario 4 | 状态依赖 + 时序 | API/integration（多事务） | ENABLED 签发 token → 改 DISABLED → 携原 token 请求 | 401 + `UNAUTHORIZED` + message="账号已停用" | 响应体 message | P0 | `JwtAuthenticationFilterDisabledTest#disabledAfterIssueDeniesNextRequest`（`MultiTransactionalIntegrationTest`） |
| /me 返回当前用户脱敏字段 | spec R3 / scenario 1 | 等价类 | API/integration | 已登录 token | 200 + data 含 id/email/name/role/status，无 passwordHash/token | 响应体字段穷举 | P0 | `MeControllerTest#authenticatedUserSeesOwnProfileWithoutSensitiveFields` |
| 改密旧密码正确 + 新密码非空 → 成功 + DB hash 变化 | spec R4 / scenario 1 | 等价类 | API/integration（多事务） | 登录 → POST change-password 旧正确新非空 | 200 + DB hash != old + 旧密码登录失败 + 新密码登录成功 | DB 哈希 + 端到端登录链 | P0 | `ChangePasswordControllerTest#successfulChangeInvalidatesOldPasswordAndAcceptsNew` |
| 改密旧密码错 → 401 + hash 不变 | spec R4 / scenario 2 | 异常分支 | API/integration | 旧密码错 | 401 + `UNAUTHORIZED` + message="原密码错误" + DB hash 不变 | 响应体 + DB 哈希 | P0 | `ChangePasswordControllerTest#wrongOldPasswordRejectedAndHashUnchanged` |
| 改密新密码空白 → 400 + hash 不变 | spec R4 / scenario 3 | 边界 | API/integration | newPassword 空字符串 / 全空白 | 400 + `VALIDATION_ERROR` + DB hash 不变 | 响应体 + DB 哈希 | P0 | `ChangePasswordControllerTest#blankNewPasswordRejectedAndHashUnchanged` |
| Admin 合法字段创建 Sales 成功 | spec R5 / scenario 1 | 等价类 | API/integration | Admin token + 合法 body | 200 + 返回 id/email/name/role=SALES/status=ENABLED + 响应不含 password/passwordHash | 响应体字段穷举 | P0 | `AdminAccountControllerCreateTest#adminCreatesSalesSuccessfully` |
| 非 Admin 创建账号被拒 | spec R5 / scenario 2 | 权限 | API/integration | Sales token / 匿名 | 403（Sales）或 401（匿名）+ 账号未被创建 | 响应体 code + DB 计数 | P0 | `AdminAccountControllerCreateTest#salesAndAnonymousCannotCreateAccount` |
| 邮箱重复创建被拒 | spec R5 / scenario 3 | 边界 | API/integration | 邮箱已存在 | 400 + `VALIDATION_ERROR` + message="邮箱已存在" + DB 计数不变 | 响应体 + DB 计数 | P0 | `AdminAccountControllerCreateTest#duplicateEmailRejected` |
| role 非 SALES 创建被拒 | spec R5 / scenario 4 | 边界 | API/integration | role=ADMIN / 非法值 | 400 + `VALIDATION_ERROR` + DB 计数不变 | 响应体 + DB 计数 | P0 | `AdminAccountControllerCreateTest#nonSalesRoleRejected` |
| 创建账号必填字段缺失被拒 | spec R5 隐含 + design D7 | 边界 | API/integration | email/name/password 任一空 | 400 + `VALIDATION_ERROR` | 响应体 | P1 | `AdminAccountControllerCreateTest#blankRequiredFieldRejected` |
| Admin 列表返回所有账号脱密 | spec R6 / scenario 1 | 等价类 | API/integration | DB 含 Admin + 2 个 Sales（1 启 1 停）| 200 + data 数组含 3 条按 createdAt 排序 + 每条含 id/email/name/role/status/createdAt + 不含 passwordHash | 响应体字段穷举 | P0 | `AdminAccountControllerListTest#adminListsAllAccountsWithoutPasswordHash` |
| Sales 不能查列表 | spec R6 / scenario 2 | 权限 | API/integration | Sales token | 403 + `FORBIDDEN` | 响应体 | P0 | `AdminAccountControllerListTest#salesCannotListAccounts` |
| Admin 停用 ENABLED Sales 成功 + 写系统日志 | spec R7 / scenario 1 | 等价类 + spy | API/integration（多事务） | Admin token + 目标 ENABLED Sales | 200 + DB status=DISABLED + disabled_at != null + SystemLogPort.record 被调用一次 | DB + spy 断言 | P0 | `AdminAccountStatusControllerTest#disableEnabledSalesUpdatesStateAndLogs` |
| Admin 启用 DISABLED Sales | spec R7 / scenario 2 | 等价类 | API/integration（多事务） | DISABLED Sales | 200 + DB status=ENABLED + disabled_at=null + 写日志 | DB + spy 断言 | P0 | `AdminAccountStatusControllerTest#enableDisabledSalesUpdatesStateAndLogs` |
| 重复停用幂等 | spec R7 / scenario 3 | 边界 | API/integration（多事务） | DISABLED Sales 再停用 | 200 + 状态仍 DISABLED | DB | P1 | `AdminAccountStatusControllerTest#disablingAlreadyDisabledIsIdempotent` |
| Admin 不能停用自己 | spec R7 / scenario 4 | 边界 + 安全 | API/integration（多事务） | Admin 调 PATCH 自己 id status=DISABLED | 400 + `VALIDATION_ERROR` + message="不可停用自己" + 状态不变 | 响应体 + DB | P0 | `AdminAccountStatusControllerTest#adminCannotDisableSelf` |
| Sales 不能调状态接口 | spec R7 / scenario 5 | 权限 | API/integration（多事务） | Sales token | 403 + `FORBIDDEN` + 状态不变 | 响应体 + DB | P0 | `AdminAccountStatusControllerTest#salesCannotChangeStatus` |
| 目标账号不存在 → 404 | spec R7 隐含 + design D7 | 异常分支 | API/integration（多事务） | targetId 不存在 | 404 + `NOT_FOUND` | 响应体 | P1 | `AdminAccountStatusControllerTest#nonExistentTargetReturnsNotFound` |
| 密码以 BCrypt 哈希存储（创建路径） | spec R8 / scenario 1 | 状态校验 | API/integration | 创建账号 | DB password_hash 长度 60 + BCrypt.matches(明文, hash)=true + hash != 明文 | DB + BCrypt | P0 | 间接覆盖在 `AdminAccountControllerCreateTest` 与 `AdminBootstrapListenerTest` 中（已显式断言哈希长度 60 + matches） |
| 密码以 BCrypt 哈希存储（改密路径） | spec R8 / scenario 2 | 状态校验 | API/integration | 改密 | DB password_hash 长度 60 + BCrypt.matches(newPassword, hash)=true | DB | P0 | 间接覆盖在 `ChangePasswordControllerTest#successfulChangeInvalidatesOldPasswordAndAcceptsNew` |
| 首次启动注入初始 Admin | spec R9 / scenario 1 | 等价类 | API/integration（多事务，特殊） | 清空 account 表 → publish ApplicationReadyEvent | DB 新增 1 行 role=ADMIN/status=ENABLED/email=配置邮箱 + BCrypt.matches(配置密码, hash)=true | DB | P0 | `AdminBootstrapListenerTest#firstStartInjectsConfiguredAdmin` |
| 同邮箱已存在跳过 | spec R9 / scenario 2 | 边界 | API/integration（多事务） | DB 已有同邮箱 Admin（不同 name+hash）| 该行所有字段保持不变 + 不新增其他行 | DB | P0 | `AdminBootstrapListenerTest#existingAdminUntouchedOnReinjection` |
| 已存在任何 ADMIN 行 → 不注入配置邮箱（防多 Admin 漂移） | design D4 风险缓解 + spec R9 / scenario 2 隐含 | 边界 + 安全 | API/integration（多事务） | DB 有**其他**邮箱的 ADMIN | DB 不新增配置邮箱行 | DB | P0 | `AdminBootstrapListenerTest#otherAdminPreventsInjection` |
| 邮箱全局唯一（DB 兜底） | spec R10 / scenario 1 | 异常分支 | API/integration | 应用层校验旁路插入重复邮箱 | DB 抛 DuplicateKeyException | JDBC 异常类型 | P1 | 间接覆盖：`AccountSchemaMigrationTest#uniqueIndexExists`（schema 约束证明）+ `AdminAccountControllerCreateTest#duplicateEmailRejected`（应用层映射）；无需单独写 raw INSERT test，索引存在 + 应用层链路已是足够证据 |
| SecurityConfig path-level 规则 | design D6 + tasks §11.1 | 决策表 | API/integration | 不同身份 × 不同路径 | 401/403/200 矩阵 | HTTP 状态 | P0 | `SecurityPathRuleTest#anonymousLoginPermitted` `#anonymousAdminListReturns401` `#salesAdminListReturns403` `#salesMeReturns200` `#adminAdminListReturns200` |
| GlobalException 既有 5 case 无回归 | scaffold 既有契约 | regression | API/integration | 既有测试集 | 全 PASS | mvn test 汇总 | P0 | 既有 `GlobalExceptionHandlerTest` / `HealthControllerTest` / `SecurityScaffoldTest` / `ConnectivitySmokeTest` / `MultiTransactionalBasePoCTest` 重跑 |

## TDD Candidates

每条都先写 Red 测试（强制为预期行为原因失败），再实现生产代码 Green。`@MockitoSpyBean` 仅在 §7 / §8 用于验证 SystemLogPort.record 调用——不替换业务逻辑，不算 mock 数据库。

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| account schema | `AccountSchemaMigrationTest#tableAndUniqueIndexExist` | V2__account.sql 尚未写入 | `SELECT 1 FROM information_schema.tables WHERE table_name='account'` 返回 0 → 断言 `>=1` 失败 | 写 V2__account.sql + Flyway 应用 | `ConnectivitySmokeTest#flywayHistoryContainsV1Baseline` 保持 PASS |
| 登录成功签发 token | `LoginControllerTest#enabledUserWithCorrectPasswordReceivesToken` | 无 `/auth/login` controller → 404 | MockMvc 期望 200，实际 401 / 404 | 实现 LoginController + JwtService + PasswordEncoder | scaffold `SecurityScaffoldTest` 保持 PASS |
| 邮箱不存在 vs 密码错 message 一致 | `LoginControllerTest#nonExistentEmailAndWrongPasswordShareSameMessage` | 同上，且 message 一致性策略未实现 | 两次响应 message 不同（或都 401 但 endpoint 不存在） | LoginController 用同一文本"邮箱或密码错误"返回 | — |
| 停用账号登录 | `LoginControllerTest#disabledAccountLoginRejectedWithStatusMessage` | 同上 | 期望 message="账号已停用"，实际为通用文本 | LoginController 状态分支 | — |
| Filter 解析有效 token | `JwtAuthenticationFilterTest#validTokenForEnabledAccountIsAdmitted` | scaffold filter 不解析 token；`/test/protected` 因 SecurityContext 空 → 401 | 期望 200，实际 401 | Filter 解析 token + 查 DB + 构造 Authentication | scaffold `SecurityScaffoldTest#unauthenticatedAccessReturnsUnifiedUnauthorizedEnvelope` 仍 PASS（场景不冲突） |
| Filter 非法 token | `JwtAuthenticationFilterTest#malformedTokenRejectedWithoutLeakingDetail` | scaffold filter 直接放行 → 后续 SecurityContext 空 → 401 但 message 仍 "未认证或认证已过期"；OK 但缺断言"不泄漏 expired/signature" | message 含 "signature" 或 "expired"，断言失败（若 filter 实现不当） | Filter 抛 BadCredentialsException("token 非法")；EntryPoint 写出该 message 但不含技术词 | — |
| 停用账号有效 token 跨事务 | `JwtAuthenticationFilterDisabledTest#disabledAfterIssueDeniesNextRequest` | scaffold filter 不查 DB → 即使 status DISABLED 也会放行（实际上因 SecurityContext 仍空走 401，但断言 message 不会是"账号已停用"） | message 不含"账号已停用" | Filter 查 DB + 抛 DisabledException("账号已停用") + EntryPoint 传透 message | — |
| /me 脱敏返回 | `MeControllerTest#authenticatedUserSeesOwnProfileWithoutSensitiveFields` | 无 `/auth/me` controller → 404 / 401 | 期望 200 + 包含 status 字段 | MeController + AccountMapper.selectById + MeResponse | — |
| 改密成功 + DB 哈希变 | `ChangePasswordControllerTest#successfulChangeInvalidatesOldPasswordAndAcceptsNew` | 无 controller | 期望 200，实际 404；旧密码登录无端可调 | ChangePasswordController + BCrypt matches/encode + AccountMapper.updateById | — |
| 改密旧密码错 401 + hash 不变 | `ChangePasswordControllerTest#wrongOldPasswordRejectedAndHashUnchanged` | 同上 | 期望 401，实际 404 | 同上 + 不匹配抛业务异常 | — |
| 改密新密码空白 400 | `ChangePasswordControllerTest#blankNewPasswordRejectedAndHashUnchanged` | 同上 | 期望 400，实际 404 | 同上 + trim 后空抛 VALIDATION_ERROR | — |
| Admin 创建 Sales 成功 | `AdminAccountControllerCreateTest#adminCreatesSalesSuccessfully` | 无 `/admin/accounts` POST | 期望 200，实际 404 / 401 | AdminAccountController.createSales + SystemLogPort.record | — |
| Admin 创建权限 | `AdminAccountControllerCreateTest#salesAndAnonymousCannotCreateAccount` | 同上；SecurityConfig 未配 `/admin/**` ROLE_ADMIN | 期望 403 / 401，实际 404 / 401 | SecurityConfig + Controller | — |
| 邮箱重复 | `AdminAccountControllerCreateTest#duplicateEmailRejected` | 同上 | 期望 400，实际 404 | Controller 重复邮箱预检 + 转 VALIDATION_ERROR | — |
| role 非法 | `AdminAccountControllerCreateTest#nonSalesRoleRejected` | 同上 | 期望 400，实际 404 | Controller 校验 role=SALES | — |
| 必填字段 | `AdminAccountControllerCreateTest#blankRequiredFieldRejected` | 同上 | 期望 400，实际 404 | @Valid + @NotBlank + GlobalExceptionHandler 既有逻辑 | — |
| Admin 列表 | `AdminAccountControllerListTest#adminListsAllAccountsWithoutPasswordHash` | 无 `/admin/accounts` GET | 期望 200，实际 404 / 401 | AdminAccountController.list | — |
| Sales 列表被拒 | `AdminAccountControllerListTest#salesCannotListAccounts` | 同上 | 期望 403，实际 404 / 401 | SecurityConfig | — |
| 停用启用 + 系统日志 | `AdminAccountStatusControllerTest#disableEnabledSalesUpdatesStateAndLogs` 与 `#enableDisabledSalesUpdatesStateAndLogs` | 无 PATCH 端点；SystemLogPort 不存在 | 期望 200 + DB 状态变更 + spy.record 被调用，实际 404 | AdminAccountController.updateStatus + SystemLogPort 接口 + Slf4jSystemLogPort + spy 注入 | — |
| 重复停用幂等 | `AdminAccountStatusControllerTest#disablingAlreadyDisabledIsIdempotent` | 同上 | 期望 200 + status 不变，实际 404 | updateStatus 状态相同时短路 | — |
| 不可停用自己 | `AdminAccountStatusControllerTest#adminCannotDisableSelf` | 同上 | 期望 400 + message="不可停用自己"，实际 404 | updateStatus 校验 currentId vs targetId | — |
| 非 Admin 调状态接口 | `AdminAccountStatusControllerTest#salesCannotChangeStatus` | 同上 | 期望 403，实际 404 / 401 | SecurityConfig | — |
| 目标不存在 | `AdminAccountStatusControllerTest#nonExistentTargetReturnsNotFound` | 同上 | 期望 404，实际 404（但不是业务 NOT_FOUND）| updateStatus 抛业务 NOT_FOUND → handler 映射 | — |
| 首次注入 Admin | `AdminBootstrapListenerTest#firstStartInjectsConfiguredAdmin` | listener 不存在 → ApplicationReadyEvent 后表仍空 | 断言"含 1 条 role=ADMIN"失败 | AdminBootstrapListener.onApplicationEvent | — |
| 已存在跳过 | `AdminBootstrapListenerTest#existingAdminUntouchedOnReinjection` | listener 不存在；若实现错可能 INSERT 重复 | 行被改写 / 唯一约束抛错 | listener 先查 count(role=ADMIN) | — |
| 防多 Admin 漂移 | `AdminBootstrapListenerTest#otherAdminPreventsInjection` | 朴素实现 "按邮箱判存"会注入第二个 ADMIN 行 | DB 出现两条 ADMIN | listener 改为 "存在任意 ADMIN 即跳过" | — |
| SecurityConfig 矩阵 | `SecurityPathRuleTest` 五条 | scaffold 仅 `/health` permitAll + 其余 authenticated；无 `/auth/login` permitAll、无 `/admin/**` ROLE_ADMIN | 匿名 POST `/auth/login` 返回 401；Sales 调 `/admin/**` 返回 401（应 403）| SecurityConfig 路径规则收紧 | scaffold `SecurityScaffoldTest` 保持 PASS |

每个候选项的 Red 失败输出 / Green 后 mvn test 摘要在 apply 阶段写入 `qa-test-report.md`。失败原因若是 setup / import / fixture / 环境（如 DataSource bean 缺失、Flyway 校验失败导致 context 启动不起来），**不计入 Red**，按 prerequisite blocker 处理。

## E2E Scenarios

本 change 不引入新的 E2E（前端登录页延后到 frontend-workbench）。仅通过 API/集成层完整覆盖。

| Scenario | Persona | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| —（无） | — | — | — | — | — | — |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| `PasswordConfig` `@Bean PasswordEncoder` 注入 | 纯 framework wiring，无业务行为 | 集成测试通过登录链路（`LoginControllerTest`）+ 改密链路（`ChangePasswordControllerTest`）+ 初始 Admin 注入（`AdminBootstrapListenerTest`）间接覆盖 | 若 cost 设错（如 cost=4 太弱）测试无法捕获——但 `new BCryptPasswordEncoder()` 默认 cost=10，与 design D8 一致 |
| `@MapperScan` 注解 | 纯 wiring | 集成测试只要 AccountMapper 注入失败即整套测试启动失败 | 无 |
| `application*.yml` 配置项（jwt secret/ttl-hours、admin email/password）占位 | 配置文件无业务行为 | JwtService 集成测试若 secret 缺失会 startup 失败；listener 测试用 application-test.yml 默认值 | 生产部署如未注入环境变量启动会失败，符合 design D1 / D4 期望 |
| `SystemLogPort` NoOp 实现 (`Slf4jSystemLogPort`) | 仅写 SLF4J 日志，无业务断言面 | §7 / §8 用 `@MockitoSpyBean SystemLogPort` 验证调用次数 / 参数；不验证 SLF4J 是否真打了行（实现细节） | 进 progress-log change 时必须替换为 JdbcSystemLogPort（已存项目记忆 `systemlog-port-noop`） |
| `AccountPrincipal` record | 数据载体，无行为 | 通过 `@AuthenticationPrincipal` 在 controller 注入并使用，间接被 `MeControllerTest` / `ChangePasswordControllerTest` / 其他 admin test 覆盖 | 无 |
| scaffold 既有 `/test/protected` 端点保留 | 测试 fixture，本 change 不改其契约 | 既有 `SecurityScaffoldTest` + 新增 `JwtAuthenticationFilterTest` 共用 | 若 `TestThrowController` 被改坏会被既有测试立即捕获 |
| 改密成功后**当前 token 不被吊销** | spec R4 明确允许"凭旧 token 继续访问至自然过期"——属于设计选择，不需 TDD 反面断言；正面断言"新密码可登录"已覆盖 | `ChangePasswordControllerTest#successfulChangeInvalidatesOldPasswordAndAcceptsNew` 隐含验证（新登录得新 token；旧 token 在 ttl 内仍可用，但本 change 不主动测） | 与 design D2 决策一致；如需强吊销需 token blacklist（PRD 不要求） |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 云端 MySQL `dealtrace` 库就绪 + DB_HOST/PORT/USER/PASSWORD env 已注入 | 全部 | scaffold-monorepo 已 RESOLVED | RESOLVED |
| `DEALTRACE_JWT_SECRET` 在 test profile 有默认值（避免依赖用户环境变量） | 登录 / Filter / 改密 / 列表 等 | 本 change §3.4 任务在 application-test.yml 提供测试默认 secret | RESOLVED（任务内闭环） |
| `DEALTRACE_ADMIN_EMAIL` / `DEALTRACE_ADMIN_PASSWORD` 在 test profile 有默认值 | listener 测试 | 本 change §9.4 任务在 application-test.yml 提供默认值 | RESOLVED（任务内闭环） |
| Maven / JJWT / MyBatis-Plus / spring-security-test 依赖已在 pom | 全部 | scaffold pom 已含 jjwt 0.12.6 + mybatis-plus + spring-security-test | RESOLVED |

无未解决阻塞。

## Coverage Closure

- [ ] 每个 in-scope 可执行测试点在前置条件就绪后都有 coverage artifact（apply 阶段在 `qa-test-report.md` 填充 `#testName` 与项目相对路径）
- [ ] 新增 / 修改的测试已执行并记录结果（每个里程碑跑 `mvn -B test` 全套）
- [ ] Red 测试因预期行为原因失败（apply 阶段贴入失败输出）
- [ ] setup / import / fixture / 环境失败**不**计为 Red
- [ ] 执行证据：`mvn -B test` 输出摘要（无 CI）
- [ ] 行为证据：每条断言证明了 spec 的哪个 Given-When-Then 分支
- [ ] 覆盖证据：每个测试点 → 项目根相对路径 + `#testName`
- [ ] 未覆盖测试点 / 未解决前置阻塞列入 Notes（当前无未覆盖；spec R10 数据库唯一约束兜底通过 schema 测试 + 应用层映射测试间接覆盖）
- [ ] 需求冲突：本 change 无（见上方 Authority Gate 表）
- [ ] 运行时 QA 验证（若有）仅作可用性 smoke，不计入业务覆盖

## Notes

- **未覆盖测试点**：无（spec 10 个 Requirement 全部映射到至少一条强 TDD 入口；R8 / R10 通过其他测试间接覆盖如表中说明）
- **剩余风险**：
  - 每请求 DB 查 `account.status` 在 MVP 流量下无忧；将来 QPS 起来需要引入缓存（design 已记录）
  - JWT 16h + 无 refresh：过期重登成本可接受
  - 密码强度 / 登录限流 / token 黑名单：MVP out-of-scope
  - SystemLog 仅 SLF4J（NoOp），DB 落盘延后到 progress-log change（项目记忆 `systemlog-port-noop`）
- **执行证据 / 行为证据 / 覆盖证据**：apply 阶段每个里程碑结束后在 `qa-test-report.md` 中追加，本设计文档不重复

## Example Rows

略——上方所有行均为项目实测点，无示例占位。
