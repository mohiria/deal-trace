# Regression Impact Analysis — auth-account

## Change Summary

- Requirement / change ID: `auth-account`
- Change type: 新增 capability + 改造 scaffold 内 stub
- Changed behavior: 新增 auth-account capability（登录 / 实时令牌校验 / /me / 改密 / Admin CRUD / 启停 + 系统日志 / 初始 Admin 注入 / 密码哈希 / 邮箱唯一）+ 改造 scaffold 的 `JwtAuthenticationFilter` / `JwtAuthenticationEntryPoint` / `SecurityConfig` + 新增 `JsonAccessDeniedHandler` + `GlobalExceptionHandler` 追加 `BusinessException` 映射 + Account 实体的 `disabledAt` 字段策略
- Impacted modules / APIs / pages: 后端 `com.dealtrace.account.*`、`com.dealtrace.auth.*`、`com.dealtrace.security.*`、`com.dealtrace.systemlog.*`、`com.dealtrace.bootstrap.*`、`com.dealtrace.common.{BusinessException,GlobalExceptionHandler}`、`db/migration/V2__account.sql`、`application*.yml`；前端不涉及
- Author / owner: 项目作者（单人开发）

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `AccountSchemaMigrationTest` | 新增 | spec R10 + design D3 | 验证 V2__account.sql 表 + uk_account_email 索引 | API/integration + 真 MySQL information_schema |
| `LoginControllerTest`（3 case） | 新增 | spec R1 | 登录成功 + 防账号枚举 + 停用账号 message | API/integration |
| `JwtAuthenticationFilterTest`（2 case）+ `JwtAuthenticationFilterDisabledTest`（1 case） | 新增 | spec R2 | Filter 解析有效/非法 token + 跨事务停用语义 | API/integration（单事务 + 多事务双轨） |
| `MeControllerTest`（1 case） | 新增 | spec R3 | 当前用户脱敏返回 | API/integration |
| `ChangePasswordControllerTest`（3 case） | 新增 | spec R4 + R8 / scenario 2 | 改密 + 旧密码错 + 新密码空白 + 改密后登录链路 | API/integration（多事务） |
| `AdminAccountControllerCreateTest`（5 case） | 新增 | spec R5 + R8 / scenario 1 | 创建成功 + 权限矩阵 + 邮箱重复 + role 非法 + 字段空白 | API/integration |
| `AdminAccountControllerListTest`（2 case） | 新增 | spec R6 | 列表脱敏 + 权限矩阵 | API/integration |
| `AdminAccountStatusControllerTest`（6 case） | 新增 | spec R7 | 停用 / 启用 / 幂等 / 自停 / 权限 / 不存在 + 系统日志 spy | API/integration（多事务） |
| `AdminBootstrapListenerTest`（3 case） | 新增 | spec R9 + design D4 | 首次注入 / 同邮箱跳过 / 多 Admin 漂移防护 | API/integration（多事务） |
| `SecurityPathRuleTest`（5 case） | 新增 | tasks §11.2 | path × 身份决策表 | API/integration |

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `JwtAuthenticationFilter`（scaffold 占位 → 完整解析） | 全部受保护端点的鉴权链路 | scaffold `SecurityScaffoldTest`（匿名 401 链路） + 本 change 全部测试 | 已新增 `JwtAuthenticationFilterTest` / `JwtAuthenticationFilterDisabledTest` | 实时查 DB 是 design D2 决策；MVP 流量下无忧 |
| `JwtAuthenticationEntryPoint` message 单一 → 白名单透传 | 401 响应体 message 取值 | scaffold `SecurityScaffoldTest`（仍 PASS：默认 message 维持） | 已新增 Filter/Login/ChangePassword 系列 | 白名单显式控制，杜绝框架内部 message 泄漏 |
| `SecurityConfig` 路径规则 | 全部 HTTP 请求的认证 / 授权决策 | scaffold `SecurityScaffoldTest` + `HealthControllerTest`（匿名 link） | 已新增 `SecurityPathRuleTest` | path matchers 不带 `/api` 前缀（context-path 已剥离） |
| `JsonAccessDeniedHandler`（新 bean） | 所有 403 响应的 body | 无既有覆盖 | 已被 Admin 系列测试间接覆盖（Sales token 调 /admin/**） | 与 EntryPoint 行为对偶 |
| `GlobalExceptionHandler` 追加 `BusinessException` 映射 | 业务异常的 HTTP 状态 + envelope 映射 | scaffold `GlobalExceptionHandlerTest`（仍 PASS：原有 case 不依赖 BusinessException） | 已被 Admin 创建（重复邮箱 / role 非法 / 停用自己）、状态变更（不存在）等测试间接覆盖 | 新业务异常统一通过 BusinessException + 既有 advice 扩展 |
| `Account.disabledAt` 字段策略 | MyBatis-Plus updateById 是否能把 disabledAt 设为 null | 无既有 | `AdminAccountStatusControllerTest#enableDisabledSalesUpdatesStateAndLogs` | `@TableField(updateStrategy=FieldStrategy.ALWAYS)` |
| Flyway V2__account.sql | 后续 capability 表 migration 节奏 | `ConnectivitySmokeTest` | `AccountSchemaMigrationTest` | bootstrap-dealtrace-mvp 后续业务表沿用同管道 |
| `SystemLogPort` 接口 + Slf4j NoOp | 系统日志契约面 | 无 | Admin 创建 / 启停的 `@MockitoSpyBean` 调用断言 | progress-log change 必须替换 NoOp 为 JdbcSystemLogPort（项目记忆 `systemlog-port-noop`） |
| `application*.yml` 新增 `dealtrace.jwt.*` / `dealtrace.bootstrap.*` | 启动期配置加载 | 全套测试（缺失会 context 启动失败） | application-test.yml 提供默认值 | 生产环境必须从环境变量注入 |

## Risk Level

- Risk: Low
- Rationale: 改动集中在新增 capability 自身代码 + 改造 scaffold 留下的明确占位（filter / entrypoint / SecurityConfig）。所有 scaffold 既有 8 case 保持 PASS，对外契约（ApiResponse 信封、ErrorCode 枚举、`/health` 行为）零改动。`disabledAt` 字段策略改动只影响 Account 实体自身，无跨实体外溢。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| backend 全套 | API/integration | 验证 spec 10 R 全部行为 + scaffold 既有 8 case 无回归 | `cd backend && mvn -B test` | PASS（41/41） | `qa-test-report.md` TDD Summary + Final Statement |
| scaffold 既有 8 case | API/integration | 重点回归改造点周边 | 同上命令（含在 41 中） | PASS | 见 backend 全套 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 前端登录页 / axios 401 自动登出 | 范围外（属 frontend-workbench） | 该 capability 尚未启动 | 后续 change 落地 | API 层契约已固化；前端只是 UI 层包装 |

## Runtime QA Validation

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| Yes | Flyway V2__account.sql 首次对云 MySQL 8.4 实际写入 | `mvn -B test` 命令日志中的 Flyway 输出 | PASS | `qa-test-report.md` Runtime QA Validation 段 |

## Regression Conclusion

- Overall result: PASS
- Changed behavior covered: spec R1–R10 全部 10 个 Requirement 在 API/integration 层用真 MySQL 8.4 覆盖
- Directly impacted old behavior covered: scaffold 的 JwtAuthenticationFilter / JwtAuthenticationEntryPoint / SecurityConfig 改造点周边既有测试全部 PASS（`SecurityScaffoldTest` / `HealthControllerTest` / `GlobalExceptionHandlerTest` / `ConnectivitySmokeTest` / `MultiTransactionalBasePoCTest`）
- Historical defects considered: 无
- Uncovered test points: 无
- Unresolved prerequisite blockers: 无
- Remaining risks:
  - SystemLogPort 仅 Slf4j NoOp，DB 落盘延后到 progress-log change（项目记忆 `systemlog-port-noop`）
  - 每请求 DB 查 `account.status` 在 MVP 流量下无忧；将来 QPS 上来需引入缓存（design D2 已记录）
  - 改密成功后旧 token 在 ttl 自然过期前仍可用（spec R4 明确允许）
