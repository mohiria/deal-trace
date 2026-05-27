# Lightweight Test Design — scaffold-monorepo

## Context

- **Requirement / Spec**：`openspec/changes/scaffold-monorepo/specs/platform-foundation/spec.md`（3 个 Requirement、5 个 Scenario）
- **Change summary**：仅建仓库脚手架，不引入任何业务行为；提供后端 Spring Boot 4.0.6 + 前端 Vite + Vue 3 骨架；落实统一响应信封 / 全局异常处理 / JWT 过滤骨架 / 健康检查端点 / 单事务与多事务两套测试基类。
- **Target modules / APIs / pages**：
  - 后端：`backend/src/main/java/com/dealtrace/{common,security,controller}/*`、`backend/src/main/resources/db/migration/V1__init.sql`、Flyway 管道
  - 前端：`frontend/src/{api,router,stores,views}/*`、Vite dev proxy、`tsconfig` strict、Vitest、Playwright
  - API：仅 `/api/health` 一个端点 + 测试 profile 的 `/api/test/*`
- **Test environment / constraints**：
  - 云端 MySQL 8.4 `dealtrace` 库（用户已创建并授权账号）
  - 环境变量 `DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` 已配置（用户确认）
  - 本机 Java 24.0.1 / Maven 3.9.16 / Node 22.15.0 / pnpm 11.3.0 均已装
  - 测试默认 `@Transactional` rollback；多事务测试用 `MultiTransactionalIntegrationTest` 基类 + `@AfterEach TRUNCATE`
  - 不依赖 Docker（用户明确反对）

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria / issue：`platform-foundation/spec.md`（3 R / 5 S）；PRD v3.3 §6（核心业务流程）；tech-arch §6.2 / §6.3（响应信封 / 错误码）
- [x] Existing behavior baseline：仓库 pre-implementation 无现存代码，无 baseline；OpenSpec `openspec/specs/` 为空
- [x] Data model / field rules / CRUD matrix：本 change 不涉及业务数据模型
- [x] API contract / auth rules / error shape：tech-arch §6.2 信封；§6.3 错误码类目；§5.3 权限模型
- [x] UI states / user roles / user paths：本 change 不涉及业务页面；仅 `/api/health` smoke + `src/views/Home.vue` 占位
- [x] Code structure / changed code / dependency graph：design.md D13 后端目录骨架；D11 前端目录骨架
- [x] Existing tests / historical defects / flaky areas：无（项目为初次实现）
- [x] Test data / credentials / mocks / CI constraints：DataSource 凭证走环境变量；scaffold 阶段不接入 CI

## Requirement Authority / Conflict Gate

本 change 为新增，**无现存 baseline 冲突**。下列潜在跨边界点已与权威源对齐：

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| API 响应信封 `{code, message, data}` 形状 | 无 | tech-arch §6.2 + platform-foundation R1 | extends | PRD v3.3 / tech-arch | Proceed |
| ErrorCode 类目 | 无 | tech-arch §6.3（取 6 项通用种子） | extends | tech-arch | Proceed |
| `/api/health` 端点契约 | 无 | platform-foundation R3 | extends | spec | Proceed |
| 未认证响应信封 | 无 | platform-foundation R1 scenario 3 + tech-arch §5 | extends | spec | Proceed |
| JWT 完整解析（含签名验证 / token 颁发） | 无 | **留给 auth-account spec（不在本 change 范围）** | extends | bootstrap-dealtrace-mvp | Proceed（本 change 仅放过滤骨架） |
| `users` 等业务表 DDL | 无 | **留给 bootstrap-dealtrace-mvp 各 capability spec** | extends | 下一 change | Proceed（本 change Flyway baseline 仅 `SELECT 1`） |

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 数据库连通 + Flyway baseline 已应用 | design D9 + 集成测试基础 | smoke | API/integration | DataSource 已配置 | `SELECT 1` 返回 1；`flyway_schema_history` 含一条 `V1` 记录 | `JdbcTemplate` 查询结果 | P0 | `backend/src/test/java/com/dealtrace/ConnectivitySmokeTest.java`（apply 阶段填充 `#testName`） |
| 成功请求返回 SUCCESS 信封 | platform-foundation R1 / scenario 1 | 等价类 | API/integration | 调用正常端点（用 `/api/health` 充当） | 200 + `code=SUCCESS` + `message` 非空 + `data` 为业务对象 | 响应体三段结构 | P0 | `HealthControllerTest.java` |
| 参数校验失败返回 VALIDATION_ERROR 信封 | R1 / scenario 2 | 等价类 + 边界 | API/integration | 测试 profile 端点 `/api/test/validation-error` 触发参数校验异常 | 400 + `code=VALIDATION_ERROR` + `data=null` | 响应体 + HTTP 状态 | P0 | `GlobalExceptionHandlerTest.java`（`#validationErrorReturnsBadRequest`） |
| 未认证请求返回 UNAUTHORIZED 信封 | R1 / scenario 3 | 等价类 | API/integration | 测试 profile 端点 `/api/test/protected` 不带 `Authorization` 头 | 401 + `code=UNAUTHORIZED` + `data=null` | 响应体 + HTTP 状态 | P0 | `SecurityScaffoldTest.java`（`#unauthorizedRequestReturnsEnvelope`） |
| 未处理异常返回 INTERNAL_ERROR 不泄漏堆栈 | R2 / scenario | 异常分支 | API/integration | 测试 profile 端点 `/api/test/internal-error` 抛 `RuntimeException` | 500 + `code=INTERNAL_ERROR` + 响应体不含 stack / 类名 / SQL / 文件路径 | 响应体字段穷举 + HTTP 状态 | P0 | `GlobalExceptionHandlerTest.java`（`#internalErrorHidesStack`） |
| 健康检查匿名可达 | R3 / scenario | 等价类 | API/integration | 无 `Authorization` 头 GET `/api/health` | 200 + `code=SUCCESS` + `data.status=UP` 或语义等价 | 响应体 + HTTP 状态 + 无 401 | P0 | `HealthControllerTest.java`（`#anonymousHealthReturnsSuccessEnvelope`） |
| 多事务测试基类 `@AfterEach` 真正清空表 | design D6 | 状态校验 | API/integration | 方法 A `INSERT` + commit；方法 B 紧随查询 | 方法 B 见空表 | 行数断言 | P1 | `MultiTransactionalBasePoCTest.java`（`#truncatesBetweenMethods`） |
| 前端 Axios 拦截器自动 unwrap data | design D11 + 前端响应处理 | 等价类 + 异常分支 | Unit (frontend) | mock 响应 `code='SUCCESS'` / `code='VALIDATION_ERROR'` | unwrap `data` / 抛业务错误 | 拦截器返回值 + 异常类型 | P1 | `frontend/src/api/client.spec.ts`（`#unwrapsSuccessAndThrowsBusinessError`） |
| E2E smoke：浏览器到 /api/health | design D10 + 前后端联调 | 场景优先（不强制 Red-Green） | E2E | backend `:8080` + frontend dev server `:5173` 均启动 | `request.get('/api/health')` 经 Vite proxy 后响应 `code='SUCCESS'` | 响应体 + HTTP 状态 | P1 | `frontend/tests/e2e/health.spec.ts`（`#healthEndpointReachable`） |

## TDD Candidates

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| 数据库连通 | `ConnectivitySmokeTest#selectOneAndFlywayHistory` | Flyway 未启用 / `flyway_schema_history` 不存在 / DataSource 未注入 | `JdbcTemplate` 查询 `flyway_schema_history` 抛 `BadSqlGrammarException`（表不存在） | 配置 `application.yml` 启用 Flyway + 注入 DataSource；写 `V1__init.sql` | 无（首个测试） |
| 参数校验信封 | `GlobalExceptionHandlerTest#validationErrorReturnsBadRequest` | `GlobalExceptionHandler` 不存在 → Spring 默认 400 响应体不是统一信封 | 期望 `code='VALIDATION_ERROR'`，实得 Spring 默认 `{"timestamp":...,"status":400,...}` | 实现 `ApiResponse` + `ErrorCode` + `GlobalExceptionHandler` 的 `MethodArgumentNotValidException` 处理 | `ConnectivitySmokeTest`（确保 Spring 上下文仍能启动） |
| 异常兜底 | `GlobalExceptionHandlerTest#internalErrorHidesStack` | 默认 Spring 错误响应可能泄漏异常类名 / message | 响应体含 `"exception":"java.lang.RuntimeException"` 或 stack 字段 | `GlobalExceptionHandler` 兜底 `Exception.class` → `INTERNAL_ERROR` + 不写出 stack 字段 | 同上 |
| 未认证 | `SecurityScaffoldTest#unauthorizedRequestReturnsEnvelope` | 默认 Spring Security 401 响应是 HTML 或空 body，不是信封 | 期望 `code='UNAUTHORIZED'`，实得 401 + 空 body 或 HTML | 实现 `SecurityConfig` + `JwtAuthenticationEntryPoint` 写出 JSON 信封 | `GlobalExceptionHandlerTest` |
| 健康检查 | `HealthControllerTest#anonymousHealthReturnsSuccessEnvelope` | `HealthController` 不存在 → 404 | 期望 200 + `code='SUCCESS'`，实得 404 | 实现 `HealthController`；`SecurityConfig` 放行 `/api/health` | `SecurityScaffoldTest`（确保受保护端点未被同时放行） |
| 多事务基类 | `MultiTransactionalBasePoCTest#truncatesBetweenMethods` | `MultiTransactionalIntegrationTest` 不存在，方法 B 看到方法 A 残留 | 方法 B 查询返回 1 行（方法 A 的 INSERT），断言期望 0 行 | 实现 `@AfterEach TRUNCATE` | 无 |
| 前端拦截器 | `client.spec.ts#unwrapsSuccessAndThrowsBusinessError` | `src/api/client.ts` 不存在 | 测试无法 import / Axios 实例未配置 | 实现 `src/api/client.ts` + 响应拦截器 | 无 |

每个候选项的 Red 证据将在 apply 跑 `mvn test` / `pnpm test` 时记录到 `qa-test-report.md`。失败原因若是 setup / import / fixture / 环境错误（如 DataSource bean 缺失阻塞 Spring 上下文启动），**不计入 Red**，按 prerequisite blocker 处理。

## E2E Scenarios

| Scenario | Persona | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| 健康检查端点经 Vite proxy 可达 | 匿名调用方（运维探活语义） | `mvn spring-boot:run` 启动 backend `:8080`；`pnpm dev` 启动 frontend `:5173` 含 proxy `/api`→`localhost:8080` | Playwright `request.get('/api/health')` | 响应 `code='SUCCESS'` + `data.status='UP'` + HTTP 200 | Playwright 自动关闭 context；后端 / 前端 dev server 由测试外脚手架管理 | Playwright trace + 控制台截图（若 UI 触发） |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 任务 §2（后端 Maven 骨架 / `DealTraceApplication` / `application.yml` / `V1__init.sql`） | 纯依赖装配 + Spring Boot 自动配置 + 配置文件；无业务行为可断言 | `mvn compile` 成功；`mvn dependency:resolve` 无版本冲突；首次 `mvn test` 时 Spring 上下文启动日志显示 Flyway 已应用 `V1` | Spring Boot 4 + MyBatis Plus 3.5.16 间接依赖冲突；将在 §3 的集成测试触发可见错误 |
| 任务 §8（前端 Vite + Vue 3 + TS 骨架 / Arco 集成 / 目录骨架 / Vite proxy） | 纯依赖装配 + 工具链配置 | `pnpm install` 成功；`pnpm build` 成功；TS strict 模式编译无报错 | TS strict + Arco 类型可能边缘冲突，发生时在 §8 内消解 |
| 任务 §11.1（CI placeholder） | 仅记录决策、无代码改动 | 文档审查 | 无 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 云端 MySQL `dealtrace` 库已创建 + 应用账号已授权 + 网络可达 | §3 onwards | 用户在云端 MySQL 执行 `CREATE DATABASE dealtrace` + `GRANT ALL PRIVILEGES ON dealtrace.* TO 'app_user'@'%'` | RESOLVED（用户已确认） |
| 本机环境变量 `DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` 已注入到 `mvn test` 的执行上下文 | §3 onwards | 用户在 shell / IDE run config 注入 | RESOLVED（用户已确认） |
| `mvn` / `pnpm` 在 PATH | §2 onwards / §8 onwards | 用户重启终端确认 | RESOLVED（`mvn 3.9.16` 验证通过；`pnpm 11.3.0` 在 PowerShell 验证通过） |
| Maven Central / npm registry 网络可达 | 全部 | 用户网络环境 | 假设 RESOLVED；apply 时若超时按 BLOCKED 处理 |

## Coverage Closure

- [ ] 每个 in-scope 可执行测试点在前置条件就绪后都有 coverage artifact（apply 阶段在 `qa-test-report.md` 填充 `#testName` 与项目相对路径）
- [ ] 新增 / 修改的测试已执行并记录结果（每个里程碑跑 `mvn test` / `pnpm test`）
- [ ] Red 测试因预期行为原因失败（apply 阶段贴入失败输出）
- [ ] setup / import / fixture / 环境失败**不**计为 Red（明确区分为 prerequisite blocker）
- [ ] 执行证据：`mvn test` / `pnpm test` 输出摘要、CI 链接（暂无 CI）、Playwright trace
- [ ] 行为证据：每条断言证明了 spec 的哪个 Given-When-Then 分支
- [ ] 覆盖证据：每个测试点 → 项目根相对路径 + `#testName`
- [ ] 未覆盖测试点 / 未解决前置阻塞列入 Notes
- [ ] 需求冲突：本 change 无（见上方 Authority Gate 表）
- [ ] 运行时 QA 验证（若有）仅作可用性 smoke，不计入业务覆盖

## Notes

- **未覆盖测试点**：本 change 不实现任何业务行为；业务行为覆盖留给 `bootstrap-dealtrace-mvp` 各 capability spec
- **剩余风险**：见 `design.md` Risks 小节（R1–R5），重点是 R1（联调与测试共用 `dealtrace` 库的残留风险）、R3（Spring Boot 4 + 三方库兼容性边缘问题）
- **执行证据 / 行为证据 / 覆盖证据**：apply 阶段每个里程碑结束后在 `qa-test-report.md` 中追加，本设计文档不重复
