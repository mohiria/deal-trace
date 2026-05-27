## 1. QA 设计先行

- [x] 1.1 在 `openspec/changes/scaffold-monorepo/qa/lightweight-test-design.md` 中规划本 change 的全部测试设计：标注哪些任务走严格 Red-Green-Refactor（§3–§7、§9），哪些任务作为 documented non-TDD exceptions（§2 后端骨架、§8 前端骨架——验证方式 = build / compile 通过，无可断言的运行时行为）；记录每条 platform-foundation Requirement 的覆盖测试入口。

## 2. 后端项目骨架（documented non-TDD exception）

- [x] 2.1 在 `backend/` 创建 Maven 项目：`pom.xml` 含 Spring Boot 4.0.6 + Spring Security + `mybatis-plus-spring-boot4-starter` 3.5.16 + Flyway + MySQL Connector + `jjwt-api/impl/jackson` 0.12.x + JUnit Jupiter + Spring Boot Test 依赖；`groupId = com.dealtrace`，`artifactId = dealtrace-backend`，`java.version = 24`，`spring-boot-maven-plugin` 配 main class。
- [x] 2.2 创建 `backend/src/main/java/com/dealtrace/DealTraceApplication.java`（含 `@SpringBootApplication`）并建立 `common/` `security/` `controller/` 子包目录骨架（空目录用 `.gitkeep` 占位）。
- [x] 2.3 创建 `application.yml`（共享 DataSource URL `jdbc:mysql://${DB_HOST}:${DB_PORT:3306}/dealtrace`、`serverTimezone=Asia/Shanghai`、`server.servlet.context-path=/api`、Flyway 启用、`baseline-on-migrate=false`）、`application-local.yml`、`src/test/resources/application-test.yml`（test profile 复用同一 DataSource）。
- [x] 2.4 创建 `backend/src/main/resources/db/migration/V1__init.sql`，内容仅含一句 `SELECT 1;` 与一行注释说明"业务表 migration 由 bootstrap-dealtrace-mvp 落定"。

## 3. 数据库连通性 + 单事务测试基类（首次 Red-Green）

- [x] 3.1 实现抽象基类 `backend/src/test/java/com/dealtrace/common/IntegrationTest.java`：标 `@SpringBootTest`、`@Transactional`、`@Rollback`，无构造逻辑，仅供子类继承。
- [x] 3.2 写测试 `ConnectivitySmokeTest`（继承 `IntegrationTest`）：注入 `JdbcTemplate`，断言 `SELECT 1` 返回 `1`，并断言 `flyway_schema_history` 表存在且至少含一条 `V1` 记录。先验证 Red（未配置环境变量 / DataSource 时 Spring Context 启动失败属于 setup blocker，需先注入正确环境变量后 Red 应为业务断言失败）。
- [x] 3.3 调通 DataSource 环境变量注入（`DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` 通过用户级环境变量或 IDE run config），跑通 ConnectivitySmokeTest（Green）；在 `qa/qa-test-report.md` 中记录 Red 输出原文与 Green 后的 `mvn test` 摘要。

## 4. API 响应信封 + 全局异常处理（platform-foundation 需求 1 & 2）

- [x] 4.1 写 `GlobalExceptionHandlerTest`：构造一个仅在测试 profile 加载的 `TestThrowController`，分别提供端点 `/api/test/validation-error`（抛 `MethodArgumentNotValidException` 等价语义异常）和 `/api/test/internal-error`（抛 `RuntimeException`）；通过 `MockMvc` 断言前者返回 HTTP 400 + `code=VALIDATION_ERROR` + `data=null` + 响应体不含 stack trace 字段；后者返回 HTTP 500 + `code=INTERNAL_ERROR` + 响应体不泄漏异常类名 / 堆栈 / SQL（Red）。
- [x] 4.2 实现 `com.dealtrace.common.ApiResponse<T>` record（字段 `code` `message` `data`，提供 `ok(...)` / `error(...)` 静态工厂）和 `ErrorCode` 枚举（含 `SUCCESS` / `VALIDATION_ERROR` / `UNAUTHORIZED` / `FORBIDDEN` / `NOT_FOUND` / `INTERNAL_ERROR` 六项）。
- [x] 4.3 实现 `com.dealtrace.common.GlobalExceptionHandler`（`@RestControllerAdvice`）：分别处理参数校验异常（→ 400 + VALIDATION_ERROR）、`AccessDeniedException`（→ 403 + FORBIDDEN）、`NoHandlerFoundException`（→ 404 + NOT_FOUND）、兜底 `Exception.class`（→ 500 + INTERNAL_ERROR，日志记录完整 stack、响应体不含 stack）使 §4.1 全部测试 Green。

## 5. JWT 骨架 + Spring Security（platform-foundation 需求 1 的 UNAUTHORIZED 分支）

- [x] 5.1 写测试 `SecurityScaffoldTest`：在测试 profile 中注册一个仅在测试加载的 `/api/test/protected` 端点（默认要求认证）；通过 `MockMvc` 不带 `Authorization` 头请求该端点，断言返回 HTTP 401 + `code=UNAUTHORIZED` + `data=null` + 响应体不含 stack（Red）。
- [x] 5.2 实现 `com.dealtrace.security.JwtAuthenticationFilter`（继承 `OncePerRequestFilter`）：从 `Authorization` 头读取 `Bearer <token>`，本 change 中 token 解析逻辑留空（不抛错、不验证签名），调用 `filterChain.doFilter()` 放行；在 java doc 中明确标注"完整 token 解析由 auth-account spec 实现"。
- [x] 5.3 实现 `com.dealtrace.security.JwtAuthenticationEntryPoint`（实现 `AuthenticationEntryPoint`）：在 commence 时写出 `ApiResponse.error(UNAUTHORIZED, "未认证或认证已过期")` 的 JSON 响应，HTTP 状态码 401。
- [x] 5.4 实现 `com.dealtrace.security.SecurityConfig`（`@EnableWebSecurity`）：禁用 CSRF；`SessionCreationPolicy.STATELESS`；放行 `/api/health`；其余路径要求认证；注册 `JwtAuthenticationFilter` 于 `UsernamePasswordAuthenticationFilter` 之前；配置 `AuthenticationEntryPoint` 为 §5.3 的 bean。使 §5.1 测试 Green。

## 6. 健康检查端点（platform-foundation 需求 3）

- [x] 6.1 写 `HealthControllerTest`：匿名（无 `Authorization` 头）GET `/api/health`，断言 HTTP 200 + `code=SUCCESS` + `data` 为非 null 对象且至少含一个表示存活的字段（如 `status=UP`）（Red）。
- [x] 6.2 实现 `com.dealtrace.controller.HealthController`：`@RestController` + `@GetMapping("/health")` 返回 `ApiResponse.ok(Map.of("status", "UP"))`。使 §6.1 测试 Green。

## 7. 多事务测试基类（供 bootstrap-dealtrace-mvp 复用）

- [x] 7.1 实现抽象基类 `backend/src/test/java/com/dealtrace/common/MultiTransactionalIntegrationTest.java`：标 `@SpringBootTest`、`@Transactional(propagation = NOT_SUPPORTED)`；提供 `abstract Set<String> tablesToTruncate()`；`@AfterEach` 实现关 FK 检查 → 按集合 TRUNCATE 各表 → 开 FK 检查。
- [x] 7.2 写 `MultiTransactionalBasePoCTest`（继承 §7.1 基类）：在 V1 baseline 之上临时建一张 `truncate_poc` 表（migration 走 `db/migration/test/` 路径或测试代码 `JdbcTemplate.execute("CREATE TABLE ... IF NOT EXISTS")`），方法 A 真实 INSERT + commit，方法 B 立即查询断言表为空；目的：验证 `@AfterEach` 真的清掉了方法 A 留下的数据。测试结束后 DROP 临时表。

## 8. 前端项目骨架（documented non-TDD exception）

- [ ] 8.1 在 `frontend/` 用 pnpm 初始化 Vite + Vue 3 + TypeScript 项目（`pnpm create vite frontend -- --template vue-ts`），`package.json` 的 `engines.node = ">=22"`、`packageManager = "pnpm@11.3.0"`。
- [ ] 8.2 编辑 `tsconfig.json` 启用严格模式：`strict: true` / `noUncheckedIndexedAccess: true` / `noImplicitOverride: true` / `exactOptionalPropertyTypes: true` / `verbatimModuleSyntax: true`。
- [ ] 8.3 安装并集成 `@arco-design/web-vue` 2.x、`vue-router` 4.x、`pinia` 2.x、`axios` 1.x；在 `main.ts` 中注册 Arco、Router、Pinia；创建 `src/styles/arco-theme.css` 占位（无 Tailwind）。
- [ ] 8.4 创建前端目录骨架：`src/router/index.ts`（初始空路由数组）、`src/stores/auth.ts`（占位 store）、`src/api/client.ts`（Axios 实例 `baseURL='/api'` + 响应信封解析拦截器：自动 unwrap `data`，遇 `code !== 'SUCCESS'` 时抛业务错误）、`src/views/Home.vue`（最简占位组件）、`src/components/` `src/types/` 空目录 + `.gitkeep`。
- [ ] 8.5 配置 `vite.config.ts` 的 dev `server.proxy`：`/api` → `http://localhost:8080`，`changeOrigin: true`。

## 9. 前端测试基础设施

- [ ] 9.1 配置 Vitest（`vitest.config.ts` 或合并入 `vite.config.ts`）；写一个最简单元测试 `src/api/client.spec.ts`：测试 Axios 拦截器对 `code='SUCCESS'` 响应正确 unwrap `data`、对 `code='VALIDATION_ERROR'` 响应抛错（Red → Green）。
- [ ] 9.2 配置 `@playwright/test`（`playwright.config.ts`），写一个 smoke E2E `tests/e2e/health.spec.ts`：启动 backend + frontend dev server，浏览器访问任意触发健康检查的页面或直接 `request.get('/api/health')`，断言响应 `code='SUCCESS'`；场景优先，不要求严格 Red-Green。

## 10. QA 收尾产物

- [ ] 10.1 在 `openspec/changes/scaffold-monorepo/qa/regression-impact-analysis.md` 中说明：本 change 为新增、无现有代码回归范围；下游影响 = `bootstrap-dealtrace-mvp` 的所有 capability 都依赖本 change 落定的 ApiResponse / ErrorCode / SecurityConfig / 测试基类 / Flyway 管道。
- [ ] 10.2 在 `openspec/changes/scaffold-monorepo/qa/qa-test-report.md` 中汇总：每个 §3–§9 任务的 Red 命令输出原文 + Green 后的 `mvn test` / `pnpm test` 摘要、覆盖证据；§2、§8 的 documented non-TDD exception 记录"验证方式 = `mvn compile` / `pnpm build` 通过"。
- [ ] 10.3 运行 `node .claude/skills/vibe-coding-qa/scripts/qa_artifacts.mjs check lightweight-test-design openspec/changes/scaffold-monorepo/qa/lightweight-test-design.md`，同样对 `regression-impact-analysis` 和 `qa-test-report` 各跑一次；解决所有 FAIL / WARN。

## 11. CI 接入（follow-up placeholder，本 change 不实施）

- [ ] 11.1 在 `openspec/changes/scaffold-monorepo/qa/qa-test-report.md` 的"剩余风险与后续工作"小节中记录 CI 接入决策：未来用 Testcontainers + CI runner 内置 Docker，database 名仍叫 `dealtrace`，不引入环境标签命名；接入时机延后到 `bootstrap-dealtrace-mvp` 完成或本人主动需要 CI 时；本 change **不**实施 CI 接入。
