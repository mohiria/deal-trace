# QA Test Report — auth-account

## Conclusion

- Overall result: PASS
- Requirement / change ID: `auth-account`
- QA owner: 项目作者（单人开发）
- Date: 2026-05-28
- Summary: spec.md 全部 10 个 ADDED Requirement（登录 / 令牌实时校验 / /me / 改密 / Admin 创建 Sales / 列表 / 启停 + 系统日志 / 密码哈希 / 初始 Admin 注入 / 邮箱唯一）以真 MySQL 8.4 集成测试覆盖；scaffold 既有 8 case 重跑无回归；全套 `mvn -B test` 41/0/0/0 Green。SystemLog 仅 NoOp (Slf4j) 实现，跨 capability follow-up 已存项目记忆 `systemlog-port-noop`。

## Evidence Guide

| Evidence type | What to record | Example |
| --- | --- | --- |
| Execution evidence | 命令、结果摘要 | `mvn -B test` BUILD SUCCESS Tests 41/0/0/0 |
| Behavioral evidence | 断言所证明的行为 | 邮箱不存在 / 密码错 → 401 + 完全相同 response body（防账号枚举） |
| Coverage evidence | 项目相对路径的测试入口 | `backend/src/test/java/com/dealtrace/auth/LoginControllerTest.java#enabledUserWithCorrectPasswordReceivesToken` |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes（间接） | BCrypt / JJWT 等纯库不单独单测，间接被集成测试覆盖 |
| API/integration | Yes | `@SpringBootTest` + MockMvc + 真 MySQL 8.4；scaffold 既有基类沿用 |
| E2E | No | 前端登录页延后到 frontend-workbench |
| Regression | Yes | scaffold 既有 8 case 重跑全 PASS |
| Runtime QA validation | Yes | Flyway 把 V2__account.sql 实际写入云 MySQL 8.4 |

## Requirement Authority / Conflict Review

本 change 是新增 capability + 改造 scaffold 内 stub。无 `conflicts` 类关系。详见 `lightweight-test-design.md` Authority Gate 表：

- `JwtAuthenticationFilter` token 解析逻辑：scaffold 留占位，本 change **extends** 至完整解析（PRD §7.1）
- `JwtAuthenticationEntryPoint` message 内容：scaffold 写死单一文本，本 change **amends** 为白名单透传（design D7）
- `SecurityConfig` 放行规则：scaffold 仅 `/health`，本 change **extends** 加 `/auth/login` permitAll + `/admin/**` ROLE_ADMIN
- 其余沿用既有契约（`ApiResponse` 形状 / `ErrorCode` 枚举无新增）

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| account 表 + uk_account_email 唯一索引存在 | spec R10 + design D3 | `mvn -B test -Dtest=AccountSchemaMigrationTest` `accountTableExists expected:<1> but was:<0>` + `uniqueIndexOnEmailExists EmptyResultDataAccessException` | V2__account.sql 暂时 disable，Flyway 未应用 | 同命令 Tests 2/0/0/0；Flyway 实际 Migrating to v2，information_schema 查到 account 表 + uk_account_email index | 全套 41/0/0/0 | `backend/src/test/java/com/dealtrace/account/AccountSchemaMigrationTest.java#accountTableExists` `#uniqueIndexOnEmailExists` | PASS |
| 登录成功签发 token + 响应不含 passwordHash | spec R1 / scenario 1 + R8 / scenario 1 | `mvn -B test -Dtest=LoginControllerTest` `enabledUserWithCorrectPasswordReceivesToken Status expected:<200> but was:<401>` | `/auth/login` 端点未实现 | 同命令 Tests 3/0/0/0；200 + code=SUCCESS + data.token 非空 + 响应体不含 passwordHash | 全套 41/0/0/0 | `backend/src/test/java/com/dealtrace/auth/LoginControllerTest.java#enabledUserWithCorrectPasswordReceivesToken` | PASS |
| 邮箱不存在 vs 密码错 message 完全相同 | spec R1 / scenario 2,3 | 同上类；两场景 response body 应一致 | 端点不存在导致默认 EntryPoint message 一致（accidental pass）但 endpoint 一旦存在不一致即捕获 | 同命令；两场景 response body 字符串相等比较通过 | 全套 41/0/0/0 | `LoginControllerTest#nonExistentEmailAndWrongPasswordShareSameMessage` | PASS |
| 停用账号登录 → message="账号已停用" | spec R1 / scenario 4 | 同命令 `disabledAccountLoginRejectedWithStatusMessage expected:<账号已停用> but was:<未认证或认证已过期>` | endpoint 未实现，默认 EntryPoint message | 同命令；message="账号已停用" + 响应不含 token | 全套 41/0/0/0 | `LoginControllerTest#disabledAccountLoginRejectedWithStatusMessage` | PASS |
| 有效 token 携带访问受保护端点放行 | spec R2 / scenario 1 | `mvn -B test -Dtest=JwtAuthenticationFilterTest` `validTokenForEnabledAccountIsAdmitted Status expected:<200> but was:<401>` | Filter 未解析 token，SecurityContext 空 | 同命令 Tests 2/0/0/0；200 OK | 全套 41/0/0/0；scaffold `SecurityScaffoldTest` 仍 PASS | `JwtAuthenticationFilterTest#validTokenForEnabledAccountIsAdmitted` | PASS |
| 非法 token → 401 不泄漏细节 | spec R2 / scenario 3 | 同测试中 `malformedTokenRejectedWithoutLeakingDetail`（accidental pass 在 Red 阶段，因 scaffold filter 不解析+EntryPoint 默认 message 也不含 signature） | endpoint 不存在但 EntryPoint 默认 message 已无技术词；Red 阶段非 accidental fail | 同命令；message 不含 signature/expired/jwt/claim | 全套 41/0/0/0 | `JwtAuthenticationFilterTest#malformedTokenRejectedWithoutLeakingDetail` | PASS |
| 停用账号有效 token 在下次请求被拒（跨事务） | spec R2 / scenario 4 | `mvn -B test -Dtest=JwtAuthenticationFilterDisabledTest` `disabledAfterIssueDeniesNextRequest expected:<账号已停用> but was:<未认证或认证已过期>` | filter 不查 DB，message 是默认值 | 同命令 Tests 1/0/0/0；401 + message="账号已停用" | 全套 41/0/0/0 | `JwtAuthenticationFilterDisabledTest#disabledAfterIssueDeniesNextRequest` | PASS |
| /me 返回当前用户脱敏字段 | spec R3 | `mvn -B test -Dtest=MeControllerTest` `authenticatedUserSeesOwnProfileWithoutSensitiveFields Status expected:<200> but was:<401>`（端点不存在） | `/auth/me` 未实现 | 同命令 Tests 1/0/0/0；200 + data{id,email,name,role,status} + 不含 passwordHash/token | 全套 41/0/0/0 | `MeControllerTest#authenticatedUserSeesOwnProfileWithoutSensitiveFields` | PASS |
| 改密成功 + DB hash 变化 + 旧密码登录失败 | spec R4 / scenario 1 + R8 / scenario 2 | `mvn -B test -Dtest=ChangePasswordControllerTest` 3 Failure（endpoint 未实现） | `/auth/change-password` 不存在 | 同命令 Tests 3/0/0/0；DB hash != old + matches(NEW_PWD) + 新密码登录成功 + 旧密码登录失败 | 全套 41/0/0/0 | `ChangePasswordControllerTest#successfulChangeInvalidatesOldPasswordAndAcceptsNew` | PASS |
| 改密旧密码错 → 401 + hash 不变 | spec R4 / scenario 2 | 同测试 `wrongOldPasswordRejectedAndHashUnchanged Failure` | endpoint 不存在 | 同命令；401 + message="原密码错误" + DB hash 不变 | 全套 41/0/0/0 | `ChangePasswordControllerTest#wrongOldPasswordRejectedAndHashUnchanged` | PASS |
| 改密新密码空白 → 400 + hash 不变 | spec R4 / scenario 3 | 同测试 `blankNewPasswordRejectedAndHashUnchanged Failure` | endpoint 不存在 | 同命令；400 + VALIDATION_ERROR + DB hash 不变 | 全套 41/0/0/0 | `ChangePasswordControllerTest#blankNewPasswordRejectedAndHashUnchanged` | PASS |
| Admin 创建 Sales 成功 | spec R5 / scenario 1 + R8 / scenario 1 | `mvn -B test -Dtest=AdminAccountControllerCreateTest` 5 Failure（endpoint 不存在 → 500） | `/admin/accounts` POST 未实现 | 同命令 Tests 5/0/0/0；200 + data.id + role=SALES + status=ENABLED + 响应不含 password/passwordHash | 全套 41/0/0/0 | `AdminAccountControllerCreateTest#adminCreatesSalesSuccessfully` | PASS |
| Sales / 匿名调创建端点被拒 | spec R5 / scenario 2 | 同测试 `salesAndAnonymousCannotCreateAccount Failure`（401/500 + 无 envelope） | SecurityConfig 未配 `/admin/**`；JsonAccessDeniedHandler 未存在 | 同命令；Sales→403+FORBIDDEN，匿名→401+UNAUTHORIZED + 账号未被创建（DB count=0） | 全套 41/0/0/0 | `AdminAccountControllerCreateTest#salesAndAnonymousCannotCreateAccount` | PASS |
| 邮箱重复 / role 非法 / 字段空白 | spec R5 / scenario 3,4 + 隐含 | 同测试 3 Failure | endpoint 不存在 | 同命令；400 + VALIDATION_ERROR + 业务 message | 全套 41/0/0/0 | `AdminAccountControllerCreateTest#duplicateEmailRejected` `#nonSalesRoleRejected` `#blankRequiredFieldRejected` | PASS |
| Admin 列表 + 不含 passwordHash | spec R6 / scenario 1 | `mvn -B test -Dtest=AdminAccountControllerListTest` `adminListsAllAccountsWithoutPasswordHash Status expected:<200> but was:<500>` | endpoint 不存在 | 同命令 Tests 2/0/0/0；200 + 3 条 + 每条含 id/email/name/role/status/createdAt + 不含 passwordHash | 全套 41/0/0/0 | `AdminAccountControllerListTest#adminListsAllAccountsWithoutPasswordHash` | PASS |
| Sales 列表被拒 | spec R6 / scenario 2 | 同测试 `salesCannotListAccounts Failure`（无 envelope） | JsonAccessDeniedHandler 未注入 | 同命令；403 + FORBIDDEN | 全套 41/0/0/0 | `AdminAccountControllerListTest#salesCannotListAccounts` | PASS |
| Admin 停用 ENABLED Sales + 写系统日志 | spec R7 / scenario 1 | `mvn -B test -Dtest=AdminAccountStatusControllerTest` 5 Failure（endpoint 不存在 / disabled_at 设不上） | PATCH endpoint 不存在；后续 disabled_at MyBatis-Plus 默认策略跳过 null 字段 | 同命令 Tests 6/0/0/0；DB status=DISABLED + disabled_at != null + spy `record("ACCOUNT_DISABLE","ACCOUNT",salesId,adminId)` 被调用 1 次 | 全套 41/0/0/0 | `AdminAccountStatusControllerTest#disableEnabledSalesUpdatesStateAndLogs` | PASS |
| Admin 启用 DISABLED Sales + 写系统日志 | spec R7 / scenario 2 | 同测试 `enableDisabledSalesUpdatesStateAndLogs Failure expected null but was 2026-05-28T09:11:40` | disabled_at 字段 MyBatis-Plus 默认 NOT_NULL 策略不会更新为 null | 修 Account.disabledAt → `@TableField(updateStrategy=FieldStrategy.ALWAYS)`；同命令；DB status=ENABLED + disabled_at=null | 全套 41/0/0/0 | `AdminAccountStatusControllerTest#enableDisabledSalesUpdatesStateAndLogs` | PASS |
| 重复停用幂等 | spec R7 / scenario 3 | 同测试 `disablingAlreadyDisabledIsIdempotent Failure` | endpoint 不存在 | updateStatus 检测状态相同时短路返回 | 全套 41/0/0/0 | `AdminAccountStatusControllerTest#disablingAlreadyDisabledIsIdempotent` | PASS |
| Admin 不可停用自己 | spec R7 / scenario 4 | 同测试 `adminCannotDisableSelf Failure` | endpoint 不存在 | updateStatus 校验 currentId == targetId && status=DISABLED → BusinessException VALIDATION_ERROR | 全套 41/0/0/0 | `AdminAccountStatusControllerTest#adminCannotDisableSelf` | PASS |
| Sales 调状态接口被拒 | spec R7 / scenario 5 | 同测试 `salesCannotChangeStatus Failure`（无 envelope） | JsonAccessDeniedHandler 未注入 | 同命令；403 + FORBIDDEN + 目标账号状态不变 | 全套 41/0/0/0 | `AdminAccountStatusControllerTest#salesCannotChangeStatus` | PASS |
| 目标账号不存在 → 404 | spec R7 隐含 + design D7 | 同测试 `nonExistentTargetReturnsNotFound Failure`（endpoint 不存在） | endpoint 不存在 | updateStatus selectById null → BusinessException NOT_FOUND | 全套 41/0/0/0 | `AdminAccountStatusControllerTest#nonExistentTargetReturnsNotFound` | PASS |
| 首次启动注入初始 Admin | spec R9 / scenario 1 | `mvn -B test -Dtest=AdminBootstrapListenerTest` 3 Error `IllegalArgumentException: null source` 后改为直接调 `listener.runIfNeeded()` → Red：listener 不存在 / 注入后断言失败 | 早期用 publishEvent 路径不可行；改用直接调方法 + truncate 前置 | AdminBootstrapListener 落地 + @BeforeEach truncate 起手；DB 新增 1 条 ADMIN/ENABLED 行 + BCrypt.matches(配置密码, hash)=true | 全套 41/0/0/0 | `AdminBootstrapListenerTest#firstStartInjectsConfiguredAdmin` | PASS |
| 同邮箱已存在跳过 | spec R9 / scenario 2 | 同测试 `existingAdminUntouchedOnReinjection` Red | listener 不存在 / 朴素实现可能覆盖既有行 | listener 改用"任意 ADMIN 即跳过"语义 → 该行所有字段保持不变 | 全套 41/0/0/0 | `AdminBootstrapListenerTest#existingAdminUntouchedOnReinjection` | PASS |
| 防多 Admin 漂移：其他邮箱 ADMIN 存在则不注入 | spec R9 / scenario 2 隐含 + design D4 | 同测试 `otherAdminPreventsInjection` Red `expected null but was Account@...`（朴素"按邮箱判存"会注入第二个 ADMIN） | 因 ADMIN 已存在另一邮箱仍注入配置邮箱（漂移）| listener 改 count(role=ADMIN) > 0 即跳过；DB 不新增配置邮箱行 | 全套 41/0/0/0 | `AdminBootstrapListenerTest#otherAdminPreventsInjection` | PASS |
| SecurityConfig path-level 决策表 | tasks §11.2 | `mvn -B test -Dtest=SecurityPathRuleTest`（在 §11 已 Green，path 规则在 §3 / §7 中分别已修） | 在路径规则修复前各端点会 401/未 envelope；本测验证最终 5 路径 × 身份矩阵 | 5/0/0/0 Green：匿名 login→401+UNAUTHORIZED（permitAll 进入 controller 后由 controller 拒）；匿名 admin/list→401；Sales admin/list→403；Sales me→200；Admin admin/list→200 | 全套 41/0/0/0 | `SecurityPathRuleTest#anonymousLoginPermitted` 等 5 个 | PASS |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| `PasswordConfig.@Bean PasswordEncoder` | 纯 framework wiring | 登录 / 改密 / 创建 / 初始 Admin 注入 4 条链路都依赖该 bean，链路 Green 即间接覆盖 | 若 cost 设错（如 4 太弱）测试无法捕获；本 change 用默认 cost=10 与 design D8 一致 |
| `@MapperScan` 注解 | wiring | AccountMapper 注入失败会让整套测试启动失败 | 无 |
| `application*.yml` 配置占位 | 配置文件 | 测试环境 application-test.yml 提供默认值；生产环境若 env 缺失 Spring 上下文启动失败 | 与 design D1/D4 一致 |
| `Slf4jSystemLogPort` NoOp 实现 | 无业务断言面 | §7/§8 用 `@MockitoSpyBean SystemLogPort` 验证 record() 被调用 | 进 progress-log change 必须替换为 JdbcSystemLogPort（项目记忆 `systemlog-port-noop`） |
| `AccountPrincipal` record | 数据载体 | `@AuthenticationPrincipal` 注入路径被 `MeControllerTest` / `ChangePasswordControllerTest` / Admin 系列间接覆盖 | 无 |
| 改密成功后**当前 token 不被吊销** | spec R4 明确允许；正面断言"新密码可登录"已覆盖 | `ChangePasswordControllerTest#successfulChangeInvalidatesOldPasswordAndAcceptsNew` 隐含验证 | token 黑名单 / refresh 是 future scope |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| API/integration | auth-account 新增 8 个测试类（AccountSchemaMigrationTest / LoginControllerTest / JwtAuthenticationFilterTest / JwtAuthenticationFilterDisabledTest / ChangePasswordControllerTest / MeControllerTest / AdminAccountControllerCreateTest / AdminAccountControllerListTest / AdminAccountStatusControllerTest / AdminBootstrapListenerTest / SecurityPathRuleTest）+ scaffold 既有 8 个 | `mvn -B test`（在 `backend/` 下） | PASS（41/0/0/0） | 各里程碑 Green 段 |
| Regression | scaffold 既有 8 case（ConnectivitySmokeTest / GlobalExceptionHandlerTest / SecurityScaffoldTest / HealthControllerTest / MultiTransactionalBasePoCTest） | 同上 | PASS（无回归） | 同上 |
| Frontend | — | 不涉及（本 change 无前端代码） | N/A | — |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 前端登录页 / 401 自动登出 | 范围外 | 属 frontend-workbench capability | 后续 change 落地 | 本 change 仅契约层，前端拦截器延后；API 层契约已固化 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| account 表 + uk_account_email | API/integration | information_schema 验证 | `backend/src/test/java/com/dealtrace/account/AccountSchemaMigrationTest.java` | COVERED |
| 登录 4 scenarios | API/integration | response body 字段穷举 + message 一致性 | `backend/src/test/java/com/dealtrace/auth/LoginControllerTest.java` | COVERED |
| Filter 有效/非法 token + 停用跨事务 | API/integration | 200/401 + message 业务文案 | `backend/src/test/java/com/dealtrace/security/JwtAuthenticationFilterTest.java` + `JwtAuthenticationFilterDisabledTest.java` | COVERED |
| 改密 3 scenarios | API/integration | DB hash + 重登链路 | `backend/src/test/java/com/dealtrace/auth/ChangePasswordControllerTest.java` | COVERED |
| /me 脱敏 | API/integration | response 字段穷举 | `backend/src/test/java/com/dealtrace/auth/MeControllerTest.java` | COVERED |
| Admin 创建 5 scenarios | API/integration | DB count + response 字段 | `backend/src/test/java/com/dealtrace/account/AdminAccountControllerCreateTest.java` | COVERED |
| Admin 列表 2 scenarios | API/integration | response 字段 + 不含 passwordHash | `backend/src/test/java/com/dealtrace/account/AdminAccountControllerListTest.java` | COVERED |
| Admin 启停 6 scenarios + 系统日志 spy | API/integration | DB 状态 + Mockito.verify | `backend/src/test/java/com/dealtrace/account/AdminAccountStatusControllerTest.java` | COVERED |
| 初始 Admin 注入 3 scenarios | API/integration | DB 行 + BCrypt.matches | `backend/src/test/java/com/dealtrace/bootstrap/AdminBootstrapListenerTest.java` | COVERED |
| SecurityConfig 矩阵 5 cases | API/integration | HTTP 状态决策表 | `backend/src/test/java/com/dealtrace/security/SecurityPathRuleTest.java` | COVERED |
| 邮箱全局唯一（DB 兜底） | API/integration | uk_account_email 存在 + 应用层 message="邮箱已存在" | `AccountSchemaMigrationTest#uniqueIndexOnEmailExists` + `AdminAccountControllerCreateTest#duplicateEmailRejected` | COVERED（双重） |

## Regression Scope

- Changed behavior: 新增 auth-account capability（登录 / 实时令牌校验 / /me / 改密 / Admin CRUD / 启停 + 系统日志 / 初始 Admin / 密码哈希 / 邮箱唯一）+ 改造 scaffold 的 JwtAuthenticationFilter / EntryPoint / SecurityConfig + 新增 JsonAccessDeniedHandler + GlobalExceptionHandler 追加 BusinessException 映射
- Directly impacted old behavior: scaffold 的 JwtAuthenticationFilter（仅放过）→ 完整解析；JwtAuthenticationEntryPoint message 单一值 → 白名单透传；SecurityConfig path 规则收紧
- Historical defects considered: 无（首次落地此 capability）
- Requirement-driven test additions / modifications / deletions: 仅新增（详见 `regression-impact-analysis.md`）
- Regression risk level: Low
- Selected regression tests and why: 整套 `mvn -B test` 41/41 Green，scaffold 既有 8 case 全部保持 PASS——`SecurityScaffoldTest#unauthenticatedAccessReturnsUnifiedUnauthorizedEnvelope`（匿名链路）+ `GlobalExceptionHandlerTest`（兜底链路）+ `HealthControllerTest`（permitAll 链路）+ `ConnectivitySmokeTest`（Flyway 链路）+ `MultiTransactionalBasePoCTest`（基类语义）全部不受 auth-account 改动影响

## Runtime QA Validation

Runtime QA validation is availability smoke evidence only. It does not count as Unit/API/E2E business coverage.

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| 云 MySQL 8.4 `dealtrace` database | Flyway V2__account.sql 首次实际执行 | PASS | `mvn -B test` 输出 `Migrating schema dealtrace to version "2 - account"` + `Successfully applied 1 migration ... now at version v2` | `flyway_schema_history` 保留 V2 record；`account` 表保留（用作测试基线） |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| `@EnumValue` 编译错误：标注方法 | 设计 / API 误用 | MP 3.5.16 的 `@EnumValue` 仅可标注字段 | enum 用私有字段 `value = name()` 承载 | 无 |
| `JwtAuthenticationFilter` import 错误 `org.springframework.security.web.authentication.AuthenticationEntryPoint` | 包路径错误 | AuthenticationEntryPoint 实际在 `org.springframework.security.web` | 修 import | 无 |
| 403 响应体为空 | 安全栈默认行为 | Spring Security path-level 拒绝时调 `AccessDeniedHandler` 默认实现仅 setStatus，无 body；不走 `@ExceptionHandler(AccessDeniedException)` | 新增 `JsonAccessDeniedHandler` bean 并在 `SecurityConfig.exceptionHandling.accessDeniedHandler` 注入 | 后续 admin/permission 检查统一走该 handler |
| `disabledAt` 不能更新为 null | ORM 默认策略 | MyBatis-Plus 默认 `FieldStrategy.NOT_NULL`：updateById 跳过 null 字段 | `@TableField(updateStrategy=FieldStrategy.ALWAYS)` 显式标注 | 启停链路 |
| AdminBootstrapListener 测试用 `ApplicationReadyEvent` 构造抛 `IllegalArgumentException: null source` | 测试设计问题 | Spring `ApplicationReadyEvent` 不接受 null source | 测试改为直接调 `listener.runIfNeeded()`；listener 暴露幂等方法供测试与事件监听共用 | 无 |
| AdminBootstrapListenerTest 首个测试方法前 DB 仍有 startup 注入的 admin 残留 | 测试隔离 | `MultiTransactionalIntegrationTest` 的 truncate 在 @AfterEach 执行，首个测试方法 setup 阶段 DB 未清；listener 启动时自动注入了一行 | 测试类加 @BeforeEach `jdbcTemplate.execute("TRUNCATE TABLE account")` | 同样模式可用于后续 listener 测试 |

## Failure Learning

- Learning recorded or recommended: Yes
- Knowledge location: `design.md` D2 + `qa-test-report.md` Failure Analysis；项目记忆 `systemlog-port-noop`
- Summary:
  1. MyBatis-Plus `@EnumValue` 仅标字段不标方法（与 JPA 不同）；
  2. Spring Security path-level 拒绝**不**触发 `@ExceptionHandler(AccessDeniedException)`，必须显式注入 `AccessDeniedHandler`；
  3. MP `updateById` 默认跳 null，要清字段必须显式 `FieldStrategy.ALWAYS` 或用 wrapper；
  4. 监听 `ApplicationReadyEvent` 的 bean 暴露一个 `runIfNeeded()` 方法供测试直接调，比手工构造 event 实例稳健；
  5. 启动期就自动注入数据的 listener 测试，必须在 @BeforeEach 主动清表，不能只靠 `MultiTransactionalIntegrationTest` 的 @AfterEach。

## Remaining Risks

- Uncovered test points: 无（spec 10 个 R 全覆盖；R8/R10 双重间接覆盖）
- Unresolved prerequisite blockers: 无
- Requirement authority conflicts: 无
- Known flaky areas: 无
- Manual follow-up: 进入 progress-log change 时替换 `Slf4jSystemLogPort` 为 `JdbcSystemLogPort`（项目记忆 `systemlog-port-noop` 已落）
- 警告类（非阻塞，沿用 scaffold 已记录）：
  - Mockito self-attaching JVM agent 警告
  - Flyway MySQL 8.4 "未验证" 警告
  - `UserDetailsServiceAutoConfiguration` 启动日志噪声（本 change 引入 `PasswordEncoder` bean 但未排除该 autoconfig；不影响行为契约，后续可在 SecurityConfig 加 `@EnableAutoConfiguration(exclude=...)` 净化日志）

## Final Statement

auth-account change 的 QA 收尾结论：spec.md 10 个 Requirement 在 API/integration 层以真 MySQL 8.4 完整覆盖；scaffold 既有 8 case 重跑无回归；全套 `mvn -B test` 41/41 Green。失败教训（MP 注解 / Spring Security AccessDenied / MP updateById null 字段 / listener 测试模式）已记入本报告。SystemLog 仅 Slf4j NoOp，跨 capability follow-up 已存项目记忆。Overall result: **PASS**。
