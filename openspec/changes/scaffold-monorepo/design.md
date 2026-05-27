## Context

商迹 DealTrace 仓库当前 pre-implementation：仅有 PRD v3.3、技术架构文档、HTML prototype 和空 OpenSpec 脚手架。`CLAUDE.md` 锁定了 OpenSpec → vibe-coding-qa 两阶段工作流——任何业务行为变更前都必须存在可工作的 backend / frontend 骨架、Flyway 迁移管道、能连接真 MySQL 的集成测试通道，以及统一的 API 响应信封。

约束清单：

- 由**单人**长期开发，无团队并发使用同一测试库的撞车场景。
- 已有一台云端服务器用于部署 MySQL 8.4 LTS；本地机器**不安装** Docker（用户明确反对，理由是 Windows 资源占用）。
- 后续测试环境、生产环境是其他服务器；schema 必须跨环境一致。
- 技术栈版本已在 explore 阶段查证并同步至 `docs/技术架构与工程约束.md` §4.2（Java 24.0.1 / Maven 3.9.16 / Spring Boot 4.0.6 / MyBatis Plus 3.5.16）。
- 用户已在本地安装 Java 24.0.1、Maven 3.9.16、Node 22.15.0、pnpm 11.3.0；MySQL 由云端服务器提供。

## Goals / Non-Goals

**Goals:**

- `backend/`：Spring Boot 4.0.6 项目可 `mvn compile` / `mvn test` / `mvn spring-boot:run` 通过，连接云端 `dealtrace` 库成功。
- `frontend/`：Vite + Vue 3 + TS 项目可 `pnpm install` / `pnpm dev` / `pnpm build` / `pnpm test` 通过。
- 集成测试可基于云端 MySQL 8.4 跑通最小 Red-Green（`SELECT 1`）。
- 全局 API 响应严格遵守 tech-arch §6.2 信封；未处理异常被统一捕获。
- 健康检查 `/api/health` 可达，无需认证。
- 命名约定固定（database / 表 / 列 / 索引 / 外键 / Flyway 文件），跨 dev/test/prod 一致，绝无环境标签。
- 测试基类两套（默认 rollback、多事务 commit + 手动清理），供后续 8 个业务域复用。
- 后续 `bootstrap-dealtrace-mvp` 不需要再触碰任何基础设施层决策。

**Non-Goals:**

- **不实现任何业务行为**：auth-account / customer / lead / permission / progress-log / contract / dashboard / frontend-workbench 全部留给 `bootstrap-dealtrace-mvp`。
- **不创建任何业务表**（含 `users`）。Flyway 只验证管道，业务表 migration 由各 capability spec 在下个 change 中落定。
- **不接入 CI**——作为 follow-up task 占位。
- **不实现完整 JWT 验证流程**——只放骨架过滤器，token 解析、签发、刷新等留给 auth-account。
- **不实现 Sales 自助注册 / 找回密码 / 邮件激活**（PRD §10 已明确出域）。
- **不引入任何环境标签到 schema 命名**（database / table / column / index / FK / migration 文件）。

## Decisions

### D1. 后端栈与版本固定

| 组件 | 版本 |
| :--- | :--- |
| Java | 24.0.1 |
| Maven | 3.9.16 |
| Spring Boot | 4.0.6 |
| Spring Security | Spring Boot 4.0.6 BOM 内置版本 |
| MyBatis Plus | 3.5.16（`com.baomidou:mybatis-plus-spring-boot4-starter`） |
| Flyway | Spring Boot 4.0.6 BOM 内置版本 |
| BCrypt | Spring Security 内置 `BCryptPasswordEncoder` |
| JWT | `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson`（apply 阶段 pin 最新 0.12.x） |

**理由**：tech-arch §4.2 已 pin。MyBatis Plus 对 Spring Boot 4 有官方 `spring-boot4-starter`（[Maven Central](https://central.sonatype.com/artifact/com.baomidou/mybatis-plus-spring-boot4-starter)），不存在兼容性阻塞。

**替代方案**：Gradle 已被用户拒（理由：Maven 生态对 Spring Boot 模板更友好、3.9.16 已装）；Spring Boot 3.x 倒退无技术必要——Spring Boot 4.0.x 是当前 GA 稳定版，且需求 Java 17+（Java 24 满足）。

### D2. 前端栈与版本固定

| 组件 | 版本系列 |
| :--- | :--- |
| Vue | 3.x（apply 阶段 pin 最新 stable patch） |
| TypeScript | 5.x（`strict: true` + `noUncheckedIndexedAccess: true`） |
| Vite | 5.x |
| Arco Design Vue | `@arco-design/web-vue` 2.x |
| Vue Router | 4.x |
| Pinia | 2.x |
| Axios | 1.x |
| Vitest | 同 Vite 主版本兼容的最新 GA |
| Playwright | `@playwright/test` 最新 GA |
| Node | 22.15.0（已装；`engines.node` 在 `package.json` 中声明 `>=22`） |
| pnpm | 11.3.0（已装） |

**理由**：tech-arch §4.1 选型确定，本 design 进一步把主版本系列固化。具体 patch 版本由 apply 阶段写入 `package.json` 时 `npm view <pkg> version` 查最新 stable——`design.md` 不锁 patch 是为避免文档随上游 patch 发布过期。

**禁止引入**：Tailwind CSS（tech-arch §10 与 Arco Design Vue 主题体系冲突）。

### D3. 数据库部署：单一云端 `dealtrace` 实例

云端 MySQL 8.4 LTS 实例上创建一个 database：

```sql
CREATE DATABASE dealtrace
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
CREATE USER 'dealtrace_app'@'%' IDENTIFIED BY '<password>';
GRANT ALL PRIVILEGES ON dealtrace.* TO 'dealtrace_app'@'%';
FLUSH PRIVILEGES;
```

**所有环境共用同一份 schema 设计**：local 开发、`mvn test` 自动化测试、未来测试环境、未来生产环境——database 都叫 `dealtrace`，表 / 列 / 索引 / 外键完全一致；环境差异**仅**通过 `spring.profiles.active` + `application-{profile}.yml` 注入不同的 host / username / password。

**理由**：
- 跨环境 schema 一致避免 Flyway migration、ORM 映射、SQL 语句出现"测试库/生产库"分支，降低部署事故面。
- 单人单环境共用一个 `dealtrace` 库既承载本地联调又承载 `mvn test`：99% 测试 `@Transactional` rollback 不留痕；少数多事务测试 `@AfterEach TRUNCATE`；单人项目残留风险可控。
- 不引入 Docker Desktop（用户明确反对）。
- 不引入"一人一库"或"测试用 dealtrace_test"——前者对单人项目过度工程、后者把环境标签耦合进 schema 设计。

**替代方案**：
- Testcontainers + 本地 Docker → 被用户拒（资源占用）。
- 一人一库（`dealtrace_alice` / `dealtrace_bob`）→ 单人项目无需要。
- 带环境标签的 schema（`dealtrace_test`）→ 违反"环境靠配置切换"原则，未来生产部署会增加分支。

**风险**见 §Risks。

### D4. 命名约定（生产规范，禁带环境标签）

| 对象 | 规则 | 示例 |
| :--- | :--- | :--- |
| Database | 固定 `dealtrace` | `dealtrace` |
| 表名 | 业务实体复数形式蛇形 | `users`, `customers`, `leads`, `progress_logs`, `system_logs`, `contracts` |
| 列名 | 蛇形；时间戳后缀 `_at`；布尔前缀 `is_`；外键后缀 `_id` | `created_at`, `unified_social_credit_code`, `is_active`, `customer_id` |
| 主键 | 固定 `id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY` | `id` |
| 时间戳 | 每个业务表必含 `created_at` / `updated_at`，类型 `DATETIME(3)`，由后端写入（tech-arch §9.1） | `created_at DATETIME(3) NOT NULL` |
| 普通索引 | `idx_<table>_<col1>[_<col2>...]` | `idx_leads_customer_id` |
| 唯一索引 | `uk_<table>_<col1>[_<col2>...]` | `uk_customers_unified_social_credit_code` |
| 外键 | `fk_<table>_<referenced>` | `fk_leads_customer` |
| Flyway 文件 | `V<version>__<snake_case_description>.sql`；versioned，单调递增 | `V1__create_users_table.sql` |
| 金额列 | `DECIMAL(15, 2)`（tech-arch §9.2） | `contract_amount DECIMAL(15, 2)` |
| 枚举列 | MySQL `VARCHAR` + 应用层枚举校验（**不**用 MySQL ENUM 类型，便于未来增删值） | `business_type VARCHAR(32)` |

**硬约束**：database / 表 / 列 / 索引 / 外键名称中**禁止**出现 `test` / `dev` / `prod` / `staging` / `qa` 等任何环境标签。违反者 CR 直接拒。

**理由**：这条约束在 CLAUDE.md 的反直觉规则中已 enshrined。未来生产部署直接复用同一份 SQL，零差异迁移。

### D5. Spring profile 与连接配置

profile 设计：

| Profile | 用途 | 激活方式 |
| :--- | :--- | :--- |
| `local` | 本地开发 + 本地 `mvn test` | `application-local.yml`；默认 `spring.profiles.active=local` |
| `test` | Spring `@SpringBootTest` 测试上下文（自动激活） | `application-test.yml`；Spring 在 `src/test` 类路径加载 |
| `prod` | 未来生产部署 | 部署时 `SPRING_PROFILES_ACTIVE=prod` |

`local` 与 `test` profile 共用同一连接（云端 `dealtrace`），凭证从环境变量读取：

```yaml
# application.yml（默认 + 共享）
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT:3306}/dealtrace?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    enabled: true
    baseline-on-migrate: false
```

**环境变量**：`DB_HOST` / `DB_PORT` / `DB_USERNAME` / `DB_PASSWORD`。本地开发者用 `.env`（不入 git）或 IDE run config；CI 用 secrets。

`serverTimezone=Asia/Shanghai` 在 D3 的 URL 中显式声明——为 dashboard `今日`/`本月` 语义打底（tech-arch §9.1 推迟的时区策略，scaffold 阶段先固定服务端时区为东八区；最终业务规则仍由 `bootstrap-dealtrace-mvp` 的 dashboard spec 决定）。

### D6. 测试隔离策略：P1 朴素版

提供两套测试基类，集成测试类按场景选择继承哪一套。

**默认 — 单事务自动 rollback**：

```java
@SpringBootTest
@Transactional
@Rollback
public abstract class IntegrationTest { ... }
```

每个测试方法包在事务里，结束自动 rollback；不留任何数据；99% 集成测试用此基类。

**多事务测试基类 — 手动清理**：

```java
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class MultiTransactionalIntegrationTest {
    @Autowired private JdbcTemplate jdbc;

    protected abstract Set<String> tablesToTruncate();

    @AfterEach
    void cleanUp() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        tablesToTruncate().forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
```

子类声明用到了哪些表，`@AfterEach` 按表清空。专用于公海认领并发等需要真实 commit 的场景（PRD §7.5 的 marquee Red 测试）。

**测试数据生成**：模拟真实业务数据（如 `customer.name = '上海某某科技有限公司'`、`unified_social_credit_code = '91310000XXXXXXXXX1'`），不加 fixture 前缀——便于联调时回看、贴近生产数据格式。具体 fixture factory 实现由各 capability spec 在 apply 时落定。

**JUnit 5 配置**：默认串行（`junit.jupiter.execution.parallel.enabled=false`），避免多事务测试基类的 TRUNCATE 与其他测试并发互踩。

### D7. 错误响应信封 + ErrorCode 枚举（tech-arch §6.2 / §6.3）

**统一响应封装**：

```java
public record ApiResponse<T>(String code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("SUCCESS", "OK", data);
    }
    public static ApiResponse<Void> error(ErrorCode code, String message) {
        return new ApiResponse<>(code.name(), message, null);
    }
}
```

**ErrorCode 枚举（scaffold 阶段种子项）**——仅落定 tech-arch §6.3 中 scaffold 阶段需要的通用类目：

```java
public enum ErrorCode {
    SUCCESS,
    VALIDATION_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    INTERNAL_ERROR
}
```

业务专属错误码（`DUPLICATE_CUSTOMER` / `DUPLICATE_ACTIVE_LEAD` / `LEAD_ALREADY_CLAIMED` / `LEAD_ENDED_READONLY` / `ACCOUNT_DISABLED` 等）由 `bootstrap-dealtrace-mvp` 的对应 capability spec 在 apply 阶段追加进同一枚举。

**全局异常处理器**：`@RestControllerAdvice` 标注的 `GlobalExceptionHandler`，捕获 `Exception.class` 兜底返回 `INTERNAL_ERROR` + HTTP 500，**不暴露堆栈**；针对 Spring 内建异常（`MethodArgumentNotValidException` / `AccessDeniedException` 等）逐个映射到具体 ErrorCode + 对应 HTTP 状态码。

### D8. JWT 过滤骨架（无业务逻辑）

`JwtAuthenticationFilter extends OncePerRequestFilter`：

- 解析 `Authorization: Bearer <token>` 头；若缺失或格式非法，不抛错（放行给下游 controller / Spring Security）。
- 本 change 中 token 解析逻辑**留空**（不验证签名、不查用户）；future auth-account spec 实现完整解析。
- Spring Security 配置允许 `/api/health` 无认证访问；其他路径默认要求认证（无 token 时返回 `code: UNAUTHORIZED` + HTTP 401，由 `AuthenticationEntryPoint` 处理）。

**理由**：scaffold 把 filter 链接好、SecurityFilterChain bean 注册好，让后续 auth-account 只需要填充 token 解析逻辑，不需要重新做 Spring Security 集成。

### D9. Flyway baseline（不含任何业务表）

`backend/src/main/resources/db/migration/V1__init.sql` 内容：

```sql
-- scaffold-monorepo baseline migration
-- 本文件仅验证 Flyway 管道。业务表由 bootstrap-dealtrace-mvp 中各 capability spec 落定。
SELECT 1;
```

**理由**：scaffold change 不属于任何业务 capability，不应创建业务表（违反 spec 边界）。`SELECT 1` 是合法 SQL，让 Flyway 完成首次 migration 记录写入 `flyway_schema_history`，集成测试能验证管道。后续 `bootstrap-dealtrace-mvp` 在 `V2__create_users_table.sql` 等 migration 中落定真业务表。

### D10. API 路径前缀与前后端联调

- 后端所有 controller 挂在 `/api/**` 前缀下（Spring 全局 `server.servlet.context-path=/api`，或每个 controller 单独前缀——design 选择 context-path 全局方案，更简洁）。
- Vite dev server 配置 proxy 将 `/api/**` 转发到 `localhost:8080`：

```ts
// vite.config.ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    }
  }
}
```

- 前端 Axios 实例 `baseURL = '/api'`；开发与生产 URL 无差异。

### D11. TypeScript 严格模式 + 前端目录规范

`tsconfig.json` 关键配置：

```json
{
  "compilerOptions": {
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "exactOptionalPropertyTypes": true,
    "verbatimModuleSyntax": true
  }
}
```

前端目录骨架：

```
frontend/src/
  main.ts              # 入口
  App.vue              # 根组件
  router/              # Vue Router 配置
  stores/              # Pinia stores
  api/                 # Axios 实例 + 错误信封解析
  views/               # 页面级组件
  components/          # 通用组件
  styles/              # Arco theme overrides
  types/               # 共享类型
```

### D12. CI 推迟

scaffold 阶段不接入 CI。`tasks.md` 中留一条 placeholder 任务，记录"未来接入时使用 Testcontainers（CI runner 自带 Docker），database 名仍叫 `dealtrace`，不引入环境标签命名"。

### D13. 后端目录骨架（tech-arch §11）

```
backend/src/main/java/com/dealtrace/
  DealTraceApplication.java
  common/
    ApiResponse.java
    ErrorCode.java
    GlobalExceptionHandler.java
  security/
    SecurityConfig.java
    JwtAuthenticationFilter.java
    JwtAuthenticationEntryPoint.java
  controller/
    HealthController.java     # /api/health
backend/src/main/resources/
  application.yml
  application-local.yml
  application-test.yml        # src/test/resources/
  db/migration/
    V1__init.sql
backend/src/test/java/com/dealtrace/
  common/
    IntegrationTest.java                       # 基类
    MultiTransactionalIntegrationTest.java     # 基类
  ConnectivitySmokeTest.java                   # SELECT 1
  GlobalExceptionHandlerTest.java
  HealthControllerTest.java
```

Domain / Service / Mapper 子包在 scaffold 留空，由 `bootstrap-dealtrace-mvp` 落定。

## Risks / Trade-offs

- **[R1] 测试和联调共用单一 `dealtrace` 库，多事务测试残留可能污染联调视野** → 缓解：默认 rollback；多事务测试基类强制 `@AfterEach TRUNCATE`；单人项目能识别残留数据；若长期扰心，加一台独立 MySQL 实例专给联调（同名 `dealtrace`、不同 host）。
- **[R2] 测试连云端 MySQL 网络延迟显著拉慢 TDD Red-Green 反馈环** → 缓解：本 change 不解决；接受 MVP 期慢一些；若反馈环长到难忍，未来加 Docker 本地 mysql 容器作为可选 profile（`application-local-fast.yml`），不进 scaffold 范围。
- **[R3] Spring Boot 4.0 + MyBatis Plus 3.5.16 是较新组合，外围库（如 `springdoc-openapi`、监控）可能尚未官方支持 SB4** → 缓解：scaffold 不引入这些外围库；后续按需逐项验证；遇到不兼容 Conflict Gate 升级解决。
- **[R4] `SELECT 1` 作为 Flyway baseline 不优雅**，但在不创建业务表的约束下是唯一合法 SQL → 缓解：可改为创建一个临时表再删，但收益不大；保持简单。
- **[R5] JWT 骨架放在 scaffold、实际验证逻辑放在 auth-account，跨 change 协作可能造成"骨架与实现脱节"** → 缓解：scaffold 的 platform-foundation spec 只规定"未认证返回 UNAUTHORIZED"行为契约；auth-account spec 实现 token 解析时由后者负责完整测试覆盖。

## Migration Plan

- **部署前置**：运维在云端 MySQL 上执行 D3 的 SQL（CREATE DATABASE + 授权账号），并通过环境变量提供给应用层。
- **部署步骤**：`mvn package` → 启动 jar。Flyway 自动应用 `V1__init.sql`。
- **回滚**：
  - 代码层：`git revert` 本 change 即可（scaffold 是新增、无现有代码迁移）。
  - 数据库层：`DROP DATABASE dealtrace`（scaffold 阶段无业务数据损失）；后续 change 落业务表后，回滚需谨慎，按各业务 capability 的 migration plan 处理。

## Open Questions

无 scaffold 阶段未决问题。以下推迟到 `bootstrap-dealtrace-mvp` 解决：

- 初始 Admin 邮箱 / 密码注入机制（env var / Flyway seed of BCrypt hash / `application.yml`）—— `auth-account` spec
- 时区策略最终决议（scaffold 暂用 `Asia/Shanghai` 兜底）—— `dashboard` spec
- 手机号验证正则目录（手机号 + 座机号格式）—— `lead` spec
- `BusinessType` 等业务枚举的存储格式 —— `lead` spec
- 业务专属 ErrorCode（`DUPLICATE_CUSTOMER` 等）—— 各 capability spec
