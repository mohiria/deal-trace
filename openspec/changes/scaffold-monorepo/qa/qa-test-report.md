# QA Test Report — scaffold-monorepo

> 本报告随 apply 阶段每个里程碑追加；当前覆盖到 **里程碑 1**（Task §1、§2、§3）。

## 里程碑 1：QA 设计 + 后端骨架 + 数据库连通

### Task §1.1 — Lightweight test design

- ✅ 完成
- Artifact：`openspec/changes/scaffold-monorepo/qa/lightweight-test-design.md`
- 覆盖 platform-foundation 全部 3 个 Requirement 的测试点；标注 §2 后端骨架与 §8 前端骨架为 documented non-TDD exception；记录 prerequisite blockers。

### Task §2 — 后端 Maven 骨架（documented non-TDD exception）

- ✅ 完成
- Alternative validation：`mvn -B -DskipTests compile` BUILD SUCCESS（1.747 s）
- 依赖解析无冲突：`mvn dependency:list` 显示 `spring-boot 4.0.6` / `spring-jdbc 7.0.7` / `flyway-core 11.14.1` / `flyway-mysql 11.14.1` 均 resolved
- Residual risk：Spring Boot 4 + MyBatis Plus 3.5.16 仍是较新组合（实际在里程碑 1 测试中发现 Spring Boot 4 模块化问题，见下方修正记录）

### Task §3 — 数据库连通性 Red-Green-Refactor

#### Red 阶段（实际经历两次伪 Red + 一次合法 Red）

**伪 Red 1：`spring.jackson.serialization` 配置绑定失败**

- 失败原因：Spring Boot 4 升级到 Jackson 3（`tools.jackson.databind.SerializationFeature` 包路径），`LenientObjectToEnumConverter` 没把 kebab-case `write-dates-as-timestamps` 转为 enum 常量 `WRITE_DATES_AS_TIMESTAMPS`
- 失败摘要：`Failed to bind properties under 'spring.jackson.serialization' ... No enum constant tools.jackson.databind.SerializationFeature.write-dates-as-timestamps`
- 性质：**setup/configuration failure**，**不计**合法 Red 证据（按 vibe-coding-qa 规则）
- 处置：从 `application.yml` 删除 `spring.jackson.serialization` 段（scaffold 阶段使用 Spring Boot 默认 ISO-8601 行为足够）

**伪 Red 2：Flyway autoconfig 未启用**

- 失败原因：Spring Boot 4 模块化重构——`FlywayAutoConfiguration` 从 `spring-boot-autoconfigure` 拆到独立 `spring-boot-starter-flyway` 模块；仅有 `flyway-core` + `flyway-mysql` 不足以触发自动配置
- 失败摘要：`ConnectivitySmokeTest.flywayHistoryContainsV1Baseline: Expecting actual: 0 to be greater than or equal to: 1`（`flyway_schema_history` 表存在但 V1 计数为 0；Conditions Evaluation Report 中完全无 FlywayAutoConfiguration mention）
- 性质：**边界情况**——表面是业务断言失败，根因是 build 依赖配置不完整；按 vibe-coding-qa 严格定义不算合法 Red（属于 dependency / autoconfig setup 问题）
- 处置：把 `pom.xml` 的 `<groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId>` 换为 `<groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId>`，保留 `flyway-mysql`

> **工程教训**：升级到 Spring Boot 4 时，`flyway-core` / `liquibase-core` 等直接依赖**必须**换为对应 starter（`spring-boot-starter-flyway` / `spring-boot-starter-liquibase`），否则 autoconfig 不触发。这条教训写入 design.md Risks 的更新计划中（参见下方"剩余风险"）。

#### Green 阶段（合法 Green）

- 命令：`cd backend && mvn -B test`
- 结果：
  ```
  2026-05-27T14:57:11 ... Started ConnectivitySmokeTest in 8.473 seconds
  2026-05-27T14:57:16 ... HikariPool-1 - Added connection com.mysql.cj.jdbc.ConnectionImpl@3e39f08c
  2026-05-27T14:57:16 ... FlywayExecutor : Database: jdbc:mysql://<host>:3606/dealtrace (MySQL 8.4)
  2026-05-27T14:57:18 ... DbMigrate : Current version of schema `dealtrace`: << Empty Schema >>
  2026-05-27T14:57:18 ... DbMigrate : Migrating schema `dealtrace` to version "1 - init"
  2026-05-27T14:57:19 ... DbMigrate : Successfully applied 1 migration to schema `dealtrace`, now at version v1 (execution time 00:00.174s)
  [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  [INFO] Total time:  21.234 s
  ```
- 行为证据：
  - `selectOneReturnsOne`：`JdbcTemplate.queryForObject("SELECT 1", Integer.class)` 返回 `1` ✓
  - `flywayHistoryContainsV1Baseline`：`flyway_schema_history` 表中存在 1 条 `version='1' AND success=TRUE` 记录 ✓
- 覆盖证据：
  - `backend/src/test/java/com/dealtrace/ConnectivitySmokeTest.java#selectOneReturnsOne`
  - `backend/src/test/java/com/dealtrace/ConnectivitySmokeTest.java#flywayHistoryContainsV1Baseline`
- Side effect：云端 `dealtrace` 库现已 baseline 至 V1（`flyway_schema_history` 永久存在 1 条 V1 success 记录）

#### Warning（非阻塞）

- Flyway 11.14.1 警告 `Using MySQL 8.4 which is newer than the version Flyway has been verified with. The latest verified version of MySQL is 8.1.`——Flyway 商业版的 MySQL 8.4 验证状态滞后；Community 版本目前仍能在 8.4 上正确工作（验证 ✓）
- Mockito self-attaching JVM 警告——Mockito 4 / Java 24 兼容性提示，不影响功能；后续可作为 follow-up 在 pom.xml 注册 byte-buddy-agent

## 里程碑 2：API 响应信封 + 全局异常处理

### Task §4.1 — `GlobalExceptionHandlerTest` + `TestThrowController`（Red）

**伪 Red（setup failure）：`@AutoConfigureMockMvc` 编译不通过**

- 失败原因：Spring Boot 4 把测试 autoconfig 进一步拆分，`@AutoConfigureMockMvc` 从 `org.springframework.boot.test.autoconfigure.web.servlet` 迁到 `org.springframework.boot.webmvc.test.autoconfigure`；且需要新 starter `spring-boot-starter-webmvc-test`（test scope）
- 失败摘要：`cannot find symbol: class AutoConfigureMockMvc`
- 性质：**setup / compilation failure**，**不计**合法 Red
- 处置：pom.xml 加 `spring-boot-starter-webmvc-test` test scope；GlobalExceptionHandlerTest 把 import 改为新包路径

**合法 Red（业务行为缺失）**

- 命令：`mvn -B -Dtest=GlobalExceptionHandlerTest test`
- 结果：
  - `internalErrorReturnsEnvelopeWithoutLeakingDetails` Error：`Servlet Request processing failed: java.lang.RuntimeException: intentional runtime failure for INTERNAL_ERROR test`——`RuntimeException` 未被任何 handler 捕获，传播到 MockMvc 测试代码
  - `validationErrorReturnsBadRequestEnvelope` Failure：`No value at JSON path "$.code"`（响应体为空，无 GlobalExceptionHandler 注册）
- 性质：业务行为缺失（无 `GlobalExceptionHandler`），断言失败属预期，**合法 Red**

### Task §4.2 / §4.3 — 实现 ApiResponse + ErrorCode + GlobalExceptionHandler（Green）

- 命令：`mvn -B -Dtest=GlobalExceptionHandlerTest test` 后跟全套 `mvn -B test`（regression check）
- 结果：
  ```
  Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- GlobalExceptionHandlerTest
  Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- 全套
  BUILD SUCCESS
  ```
- 行为证据：
  - `internalErrorReturnsEnvelopeWithoutLeakingDetails`：500 + `code=INTERNAL_ERROR` + `message` 存在 + 响应体不含 `RuntimeException` / `java.lang` / `at com.dealtrace` / `stacktrace` / `"trace"` 任一子串 ✓
  - `validationErrorReturnsBadRequestEnvelope`：400 + `code=VALIDATION_ERROR` + `message` 存在 ✓
  - Regression：ConnectivitySmokeTest 2/2 仍 Green，无回归
- 覆盖证据：
  - `backend/src/main/java/com/dealtrace/common/ApiResponse.java`
  - `backend/src/main/java/com/dealtrace/common/ErrorCode.java`
  - `backend/src/main/java/com/dealtrace/common/GlobalExceptionHandler.java`
  - `backend/src/test/java/com/dealtrace/common/GlobalExceptionHandlerTest.java#internalErrorReturnsEnvelopeWithoutLeakingDetails`
  - `backend/src/test/java/com/dealtrace/common/GlobalExceptionHandlerTest.java#validationErrorReturnsBadRequestEnvelope`
  - `backend/src/test/java/com/dealtrace/common/testsupport/TestThrowController.java`（test fixture）

### 派生工程教训

继里程碑 1 的 Flyway 模块化教训之后，本里程碑再次撞到 Spring Boot 4 测试 autoconfig 拆分：**`spring-boot-starter-test` 不再包含 MockMvc 相关自动配置，需要按 main starter 对应加 test starter**（如 `spring-boot-starter-webmvc-test`）。这条同样适用于后续 §5 SecurityScaffoldTest / §6 HealthControllerTest——同一 pom 已加 starter，后续不会再撞。

## 里程碑 3：JWT 骨架 + Spring Security + Health 端点

### Task §5 — JWT 骨架 + Spring Security（platform-foundation R1 UNAUTHORIZED 分支）

#### 合法 Red

- 命令：`mvn -B -Dtest=SecurityScaffoldTest test`
- 结果：HTTP 401（spring-boot-starter-security 默认 Basic Auth 给的），但响应体为空（无 GlobalExceptionHandler 注册、无自定义 AuthenticationEntryPoint）
- 断言失败：`No value at JSON path "$.code"`（响应体 `Body =` 为空）
- 性质：业务行为缺失（无统一信封），断言失败属预期，**合法 Red**

#### Green

- 命令：`mvn -B -Dtest=SecurityScaffoldTest test` → 全套 `mvn -B test`（regression）
- 结果：`Tests run: 1, Failures: 0` → 全套 `Tests run: 5, Failures: 0`，BUILD SUCCESS
- 行为证据：未带 `Authorization` 头访问 `/test/protected` → HTTP 401 + `code=UNAUTHORIZED` + `message=未认证或认证已过期` + `data=null` + 响应体不含 `AuthenticationException` / `at com.dealtrace` / `stacktrace` / `"trace"`
- 覆盖证据：
  - `backend/src/main/java/com/dealtrace/security/JwtAuthenticationFilter.java`（OncePerRequestFilter，token 解析占位）
  - `backend/src/main/java/com/dealtrace/security/JwtAuthenticationEntryPoint.java`（写出 ApiResponse 信封 JSON）
  - `backend/src/main/java/com/dealtrace/security/SecurityConfig.java`（CSRF disable / STATELESS / `/health` permitAll / 其余 authenticated / Filter 注册）
  - `backend/src/test/java/com/dealtrace/security/SecurityScaffoldTest.java#unauthenticatedAccessReturnsUnifiedUnauthorizedEnvelope`
  - `backend/src/test/java/com/dealtrace/common/testsupport/TestThrowController.java`（追加 `/protected` 端点）

#### Warning（非阻塞）

- `UserDetailsServiceAutoConfiguration` 在启动时生成 `Using generated security password: ...`：因 `spring-boot-starter-security` 默认装配 inMemoryUserDetailsManager，SecurityConfig 未显式排除该 autoconfig。本 change 不接入真实用户加载，安全行为已由自定义 `JwtAuthenticationFilter` + `EntryPoint` 接管，启动日志噪声不影响行为契约；auth-account spec 引入真实 `UserDetailsService` bean 后自动消失，无需现在处置。

### Task §6 — Health 端点（platform-foundation R3）

#### 合法 Red

- 命令：`mvn -B -Dtest=HealthControllerTest test`
- 结果：HTTP 500（无 `/health` controller → DispatcherServlet 抛 NoHandlerFoundException 等 → GlobalExceptionHandler 兜底为 `INTERNAL_ERROR`）
- 断言失败：`Status expected:<200> but was:<500>`，响应体 `{"code":"INTERNAL_ERROR","message":"服务器内部错误","data":null}`
- 性质：业务行为缺失（无 Health 控制器），断言失败属预期，**合法 Red**（兜底链路已生效，但具体业务行为未落地）

#### Green

- 命令：全套 `mvn -B test`
- 结果：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
- 行为证据：匿名 GET `/health` → HTTP 200 + `code=SUCCESS` + `data.status=UP` + `message=OK`
- 覆盖证据：
  - `backend/src/main/java/com/dealtrace/controller/HealthController.java`
  - `backend/src/test/java/com/dealtrace/controller/HealthControllerTest.java#anonymousGetHealthReturnsSuccessEnvelopeWithStatusUp`

## 里程碑 4：多事务测试基类

### Task §7 — MultiTransactionalIntegrationTest + PoC

#### 设计取舍

- 不属于 platform-foundation 业务行为 spec，但是后续 capability（认领并发、Outbox、流水插入与外部状态依赖）必须依赖的"跨事务测试支撑"，因此基类本体走"实现先行 + PoC 验证清理逻辑"路线，而非业务行为 TDD。
- `@Transactional(propagation = NOT_SUPPORTED)` 让测试方法不被外层事务包裹，子类可以多次真实 commit；代价是必须显式声明 `tablesToTruncate()` 让基类在 @AfterEach 中清理。
- TRUNCATE 之间临时关 FK_CHECKS 避免父子表清理顺序敏感，try/finally 保证清理失败也会恢复 FK 检查。

#### Green 证据

- 命令：`mvn -B -Dtest=MultiTransactionalBasePoCTest test` → 全套 `mvn -B test`
- 结果：`Tests run: 2, Failures: 0` → 全套 `Tests run: 8, Failures: 0`，BUILD SUCCESS
- 验证语义：`MultiTransactionalBasePoCTest` 用 `@TestMethodOrder(OrderAnnotation.class)` 强制 A→B 顺序：
  - `methodAInsertsAndCommits` 在 `truncate_poc` 真实 INSERT 一行，自检 `COUNT(*) = 1`（说明 NOT_SUPPORTED 下 JdbcTemplate 自动提交生效）
  - `methodBSeesEmptyTableProvingAfterEachTruncated` 起手即查 `COUNT(*) = 0`（说明 A 之后的 @AfterEach 真的 TRUNCATE 掉了那行）
- 覆盖证据：
  - `backend/src/test/java/com/dealtrace/common/MultiTransactionalIntegrationTest.java`
  - `backend/src/test/java/com/dealtrace/common/MultiTransactionalBasePoCTest.java#methodAInsertsAndCommits`
  - `backend/src/test/java/com/dealtrace/common/MultiTransactionalBasePoCTest.java#methodBSeesEmptyTableProvingAfterEachTruncated`
- Side effect：测试结束后 `@AfterAll` DROP `truncate_poc`，不污染 dealtrace schema。

## 里程碑 5：前端骨架 + 测试基础设施

### Task §8 — Vite + Vue 3 + Arco 骨架（documented non-TDD exception）

- 命令：`pnpm create vite frontend --template vue-ts` → `pnpm install` → `pnpm add @arco-design/web-vue@^2 vue-router@^4 pinia@^2 axios@^1` → `pnpm add -D vitest@^3 @vitest/coverage-v8@^3 jsdom@^25 @vue/test-utils@^2 @playwright/test@^1`
- Alternative validation：
  - `./node_modules/.bin/vue-tsc -b` → 退出 0（严格模式下 src 全量类型 OK）
  - `./node_modules/.bin/vite build` → BUILD SUCCESS（生成 `dist/index.html` 与 chunked assets；warning 仅为 chunk 大小提示，未来按业务路由 code-split）
- tsconfig.app.json 启用：`strict` / `noUncheckedIndexedAccess` / `noImplicitOverride` / `exactOptionalPropertyTypes` / `verbatimModuleSyntax`
- main.ts 注册顺序：Pinia → Router → ArcoVue；样式按 `arco.css` + 占位 `arco-theme.css` 加载，未引入 Tailwind（符合 tech-arch §10 禁令）
- vite.config.ts dev proxy `/api → http://localhost:8080`，`changeOrigin: true`
- 骨架占位：`src/router/index.ts`（空 routes）、`src/stores/auth.ts`（仅 `token` ref + setter）、`src/views/Home.vue`、`src/components/.gitkeep`、`src/types/.gitkeep`

### Task §9.1 — Vitest 单元测试 Red-Green-Refactor

#### 合法 Red

- 临时把 `unwrapEnvelope` 短路为 `return response.data.data`（跳过 SUCCESS 校验）
- 命令：`./node_modules/.bin/vitest run src/api/client.spec.ts`
- 结果：
  ```
  ❯ src/api/client.spec.ts (2 tests | 1 failed) 10ms
    ✓ unwrapEnvelope > returns inner data when code is SUCCESS
    × unwrapEnvelope > throws ApiError carrying code and message when envelope is not SUCCESS 6ms
      → expected function to throw an error, but it didn't
  ```
- 性质：业务行为缺失（短路实现不抛 ApiError），**合法 Red**

#### Green

- 恢复 `unwrapEnvelope`（`code !== 'SUCCESS'` 抛 `ApiError`，否则返回 `envelope.data`）
- 命令：`./node_modules/.bin/vitest run`
- 结果：`Test Files 1 passed (1) | Tests 2 passed (2)`
- 覆盖证据：
  - `frontend/src/api/client.ts#unwrapEnvelope`
  - `frontend/src/api/client.spec.ts#unwrapEnvelope > returns inner data when code is SUCCESS`
  - `frontend/src/api/client.spec.ts#unwrapEnvelope > throws ApiError carrying code and message when envelope is not SUCCESS`

#### 配置说明

- `vitest.config.ts` 与 `vite.config.ts` 分开（前者 `defineConfig from 'vitest/config'`，后者 `from 'vite'`）。原因：vitest 3 / vite 8 / `@vitejs/plugin-vue` 6 在 Plugin 类型上有 HMR hook 签名差异，合并后 `vue-tsc -b` 会报 TS2769。拆分后类型检查与运行时都干净。

### Task §9.2 — Playwright smoke E2E（配置 + 用例就绪，执行延后）

- `playwright.config.ts`：testDir `./tests/e2e`，baseURL `http://localhost:5173`，chromium project
- `tests/e2e/health.spec.ts`：用 `request.get('/api/health')` 经 Vite dev proxy 打到后端 `/health`，断言 `code='SUCCESS'` + `data.status='UP'`
- 执行延后：需要并行启动 backend (`mvn spring-boot:run`) + frontend (`pnpm dev`) + 首次需 `pnpm exec playwright install chromium` 装浏览器；本 change 仅落地"基础设施 + 用例文件"，实际首次 smoke 由人在 §11.1 CI 接入决策前的本地联调阶段触发。

## 剩余风险与后续工作（持续追加）

- Mockito self-attaching JVM agent 警告：Mockito / Java 24 兼容性提示，目前不影响功能；后续里程碑如需更精细的 mock 行为，再按 follow-up 在 pom.xml 注册 `byte-buddy-agent`。
- Flyway 11.14.1 对 MySQL 8.4 的"未验证"警告：Community 版本验证可正常工作，Community 升级到正式支持 8.4 前持续观察，无需现在处置。

（里程碑 3-6 的证据将在对应阶段追加。）
