## Context

scaffold-monorepo 已落地：

- `JwtAuthenticationFilter`：仅读 `Authorization` 头并放行，**未做 token 解析**
- `JwtAuthenticationEntryPoint`：能写 401 + UNAUTHORIZED 信封
- `SecurityConfig`：放行 `/health`，其余 `authenticated()`，无 `UserDetailsService` / `PasswordEncoder` / `AuthenticationManager` 配置
- 依赖：`jjwt-api/impl/jackson 0.12.x`、`spring-security 7.x`、`spring-boot 4.0.6`、`mybatis-plus-spring-boot4-starter 3.5.16`、Flyway、真 MySQL 8.4（tech-arch §12 钉死）

主 spec `platform-foundation` 已经定义统一 ApiResponse 信封 / GlobalExceptionHandler / `/health` 行为契约——本 change 必须沿用，不重新定义。

`com.dealtrace.common.ApiResponse<T>` / `ErrorCode` 已就位（SUCCESS / VALIDATION_ERROR / UNAUTHORIZED / FORBIDDEN / NOT_FOUND / INTERNAL_ERROR）。

PRD §7.1 / §11.1 与 tech-arch §5.1 / §5.2 / §9.4 是本 change 的权威需求来源。

## Goals / Non-Goals

**Goals:**

- 提供 PRD §7.1 全部行为：登录、JWT 鉴权、初始 Admin 注入、Admin 账号 CRUD、启停语义。
- 满足"停用账号下一次 API 请求即 401"语义。
- 引入 `SystemLogPort` 端口接口，让账号状态变更可记录系统日志，但**不**在本 change 落 SystemLog 表（属 `progress-log` capability）。
- 让所有后续 capability 可通过 `AccountPrincipal` 拿到当前用户 id / email / role。
- 保持 spec.md **不**包含实现细节（DB schema、类名、文件路径），design.md 承载这些。

**Non-Goals:**

- 多 Admin 支持（PRD §5.2.3 钉死 MVP 仅 1 个 Admin，但**不**在 DB 层强约束——靠创建端点拒绝 ADMIN 角色实现）。
- Sales 自助找回密码 / 邀请激活 / 邮件通知（PRD §5.1.5 明确排除）。
- Token refresh、token blacklist、长连接 push 登出（MVP 过度设计）。
- 密码强度策略（PRD 未要求；密码非空即可）。
- 停用 Sales 名下线索的回收 / 转移（属 `lead-management` capability；本 change 仅在 account 上落状态）。
- 权限注解粒度（如 `@PreAuthorize("hasRole('ADMIN')")` 与 `@AuthenticationPrincipal` 组合的复杂场景）——本 change 仅做 path-level（`SecurityConfig.authorizeHttpRequests`）+ "当前用户 = 路径用户" 两种粗粒度。
- 系统日志的实际 DB 落盘（NoOp 实现，写 SLF4J）；JDBC 实现延后到 `progress-log` change。
- 前端登录页 UI、axios 401 拦截清 token 跳转（属 `frontend-workbench` capability）。

## Decisions

### D1：JWT 算法 / 有效期 / Secret 来源 — HS256 + 16h + 环境变量

| 维度 | 选择 | 备选 | 理由 |
| :--- | :--- | :--- | :--- |
| 算法 | HS256 | RS256 | 单体后端，无第三方验签需求；HS256 配置简单、性能更优 |
| 有效期 | 16h | 1h / 8h / 24h | 用户答复钉死；覆盖一个工作日 + 加班缓冲，避免频繁重登 |
| Secret 来源 | 部署配置（环境变量 `DEALTRACE_JWT_SECRET`）→ `application.yml` 占位 `${DEALTRACE_JWT_SECRET}` | application.yml 硬编码 / 启动随机生成 | 与初始 Admin 密码同源；启动随机生成会让重启全员失效，运维不可控 |
| Secret 长度 | ≥ 32 字节（JJWT HS256 要求 256 bit） | — | 本地启动通过随机字符串生成一次记入个人环境变量 |
| Claims | `sub = account.id`、`email`、`role`、`iat`、`exp` | 含 `status` | `status` 不放——按 D2 实时查 DB；放进 token 会导致停用后必须等过期 |

**库选择**：`jjwt-api/impl/jackson 0.12.x` 已在 pom（与 Jackson 3 / `tools.jackson` 包路径兼容）。

### D2：停用账号"下一次 API 请求"语义实现 — JwtAuthenticationFilter 实时查 DB

`JwtAuthenticationFilter.doFilterInternal` 流程：

```
1. 读 Authorization 头
2. 无头 / 非 Bearer → filterChain.doFilter，由 SecurityConfig 决定是否需要认证
3. 解析 JWT（签名 / 过期）→ 失败抛 AuthenticationException（被 EntryPoint 转 401）
4. 取 sub = accountId
5. accountRepository.findById(accountId)
   ├─ 不存在 → AuthenticationException("账号不存在")
   ├─ status = DISABLED → AuthenticationException("账号已停用")
   └─ status = ENABLED → 构造 AccountPrincipal + Authentication 放入 SecurityContext
6. filterChain.doFilter
```

- **每请求一次 DB 查询**：MVP QPS 极低（个位数 / 分钟），完全可接受；不引入 caffeine / redis 缓存（缓存失效语义 vs 实时性是 tradeoff，MVP 选实时）。
- `AccountPrincipal`（record）：`id` / `email` / `role`；不存密码哈希。
- `Authentication`：Spring Security `UsernamePasswordAuthenticationToken`，authorities = `List.of(new SimpleGrantedAuthority("ROLE_" + role))`。

### D3：账号实体与 DB schema — 单表 account

```sql
-- V2__account.sql
CREATE TABLE account (
  id              BIGINT NOT NULL AUTO_INCREMENT,
  email           VARCHAR(255) NOT NULL,
  password_hash   CHAR(60)     NOT NULL,            -- BCrypt 固定 60 字符
  name            VARCHAR(64)  NOT NULL,
  role            VARCHAR(16)  NOT NULL,            -- 'ADMIN' / 'SALES'，字符串存
  status          VARCHAR(16)  NOT NULL,            -- 'ENABLED' / 'DISABLED'
  created_at      DATETIME(3)  NOT NULL,
  updated_at      DATETIME(3)  NOT NULL,
  disabled_at     DATETIME(3)  NULL,                -- 停用时间戳，启用时清空
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- **表名 `account`** 而非 `user`：避开 SQL 标准保留字与 MyBatis 反向工程歧义；与 PRD §9.1"用户"对应（语义同义但实现选 account 词形）。
- **role / status 用 VARCHAR(16) 而非 ENUM**：tech-arch §9.3 未细化，但字符串枚举的代价（多几个字节）远小于 ALTER ENUM 的代价；Java 侧用 enum + MyBatis-Plus 的 `@EnumValue`。
- **password_hash CHAR(60)**：BCrypt 输出恒为 60 字节。
- **uk_account_email**：唯一索引兜底 spec "邮箱全局唯一"要求（防应用层竞态写入冲突）。
- **disabled_at**：审计字段，spec.md **不**强制其暴露给 API；保留为后续 Admin 可见的脱敏审计字段（本 change 不通过 API 暴露，列表响应仅含 status）。

### D4：初始 Admin 注入 — ApplicationReadyEvent 监听器幂等

```
@Component AdminBootstrapListener implements ApplicationListener<ApplicationReadyEvent>
  ├─ @Value("${dealtrace.bootstrap.admin-email}") email
  ├─ @Value("${dealtrace.bootstrap.admin-password}") password
  ├─ if (accountRepository.existsByEmail(email)) → log.info("admin exists, skip") return
  └─ 插入 account row { email, BCrypt.hash(password), name="系统管理员", role=ADMIN, status=ENABLED, createdAt=now, updatedAt=now }
```

- 触发时机：`ApplicationReadyEvent`（在 Flyway / 所有 bean 初始化完后），确保 V2 表已就绪。
- 幂等：仅按邮箱判断存在；存在即跳过（密码 / 姓名 / 状态都**不**重置——避免覆盖 Admin 通过 change-password 自行修改的密码）。
- 配置缺失：`application.yml` 提供占位 `${DEALTRACE_ADMIN_EMAIL}` / `${DEALTRACE_ADMIN_PASSWORD}`；缺失环境变量时启动失败（@Value 找不到属性 → 上下文启动失败，与 PRD "由部署配置提供"语义一致）。

### D5：SystemLog 端口模式 — 本 change 留 NoOp 实现

```
package com.dealtrace.systemlog

public interface SystemLogPort {
    void record(String action, String targetType, Long targetId, Long operatorId);
}

@Component
class Slf4jSystemLogPort implements SystemLogPort {
    private static final Logger log = LoggerFactory.getLogger(Slf4jSystemLogPort.class);
    @Override public void record(String action, String targetType, Long targetId, Long operatorId) {
        log.info("[systemlog] action={} targetType={} targetId={} operatorId={}", action, targetType, targetId, operatorId);
    }
}
```

- AccountService 在创建账号 / 启用 / 停用时调用 `systemLogPort.record(...)`。
- `progress-log` change 落地时，引入 `JdbcSystemLogPort` + `@Primary` 注解（或将本 NoOp 实现添 `@ConditionalOnMissingBean(SystemLogPort.class)` 让 JdbcSystemLogPort 优先注册）。
- spec.md 仅要求"系统记录一条系统日志"，不绑定实现——本设计满足契约。

### D6：API 路径与方法

| Method | Path | 用途 | 鉴权 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/login` | 邮箱密码登录，返回 token | 匿名 |
| `GET` | `/api/auth/me` | 当前用户信息 | 已认证 |
| `POST` | `/api/auth/change-password` | 当前用户改自己密码 | 已认证 |
| `POST` | `/api/admin/accounts` | Admin 创建 Sales | ADMIN |
| `GET` | `/api/admin/accounts` | Admin 列出账号 | ADMIN |
| `PATCH` | `/api/admin/accounts/{id}/status` | Admin 启停账号 | ADMIN |

- 启停用一个 PATCH 端点带 `{status: "ENABLED" / "DISABLED"}` body，而非两个 `/enable` `/disable` 动词路径。理由：RESTful + 简化路由，"对方状态"语义清晰。
- SecurityConfig 配置：
  - `POST /api/auth/login` → permitAll
  - `POST /api/admin/**` / `GET /api/admin/**` / `PATCH /api/admin/**` → `hasRole("ADMIN")`
  - 其余继承 scaffold 的 `authenticated()`
  - `/api/health` 已 permitAll（platform-foundation）

### D7：错误码策略 — 统一用既有 ErrorCode + message 区分

用户在 explore 阶段答复"统一"。本 change**不**新增 ErrorCode 枚举项。

| 场景 | HTTP | code | message |
| :--- | :--- | :--- | :--- |
| 登录成功 | 200 | SUCCESS | "登录成功" |
| 邮箱不存在 / 密码错 | 401 | UNAUTHORIZED | "邮箱或密码错误"（同一文本，防账号枚举） |
| 停用账号尝试登录 | 401 | UNAUTHORIZED | "账号已停用" |
| 缺失 / 非法 token | 401 | UNAUTHORIZED | "未认证或认证已过期"（沿用 scaffold EntryPoint） |
| 改密旧密码错 | 401 | UNAUTHORIZED | "原密码错误" |
| 改密新密码为空 | 400 | VALIDATION_ERROR | "新密码不可为空" |
| 创建账号邮箱重复 | 400 | VALIDATION_ERROR | "邮箱已存在" |
| 创建账号 role 非法 | 400 | VALIDATION_ERROR | "角色非法，仅支持 SALES" |
| Admin 试图停用自己 | 400 | VALIDATION_ERROR | "不可停用自己" |
| 非 Admin 访问 admin 接口 | 403 | FORBIDDEN | "无权访问" |
| 启停目标账号不存在 | 404 | NOT_FOUND | "账号不存在" |

### D8：密码哈希 — BCrypt cost 10

`@Bean PasswordEncoder` → `new BCryptPasswordEncoder()`，默认 cost = 10。

- 10 在现代硬件上约 ~70ms / 次 hash，登录响应在 100ms 内可接受。
- 不引入 Argon2 / scrypt（依赖 + 配置成本对 MVP 单 Admin + 个位数 Sales 不划算）。

### D9：AccountPrincipal 注入下游 controller 的方式

```java
@GetMapping("/me")
public ApiResponse<MeResponse> me(@AuthenticationPrincipal AccountPrincipal principal) {
    return ApiResponse.ok(MeResponse.from(principal /* + Repository 查询 */));
}
```

- 下游 capability（customer、lead 等）可直接通过 `@AuthenticationPrincipal AccountPrincipal` 拿当前用户。
- 这是本 change 对下游的核心契约面。

### D10：测试基类沿用 scaffold

- 单事务集成测试（最常见）：继承 `IntegrationTest`（@SpringBootTest + @Transactional + @Rollback）。
- 涉及登录后再次发请求（多事务边界）：继承 `MultiTransactionalIntegrationTest`，`tablesToTruncate() = Set.of("account")`。
- 真 MySQL 8.4（tech-arch §12 钉死）；database 名 `dealtrace`（无环境后缀，CLAUDE.md 钉死）。

## Risks / Trade-offs

- **每请求 DB 查 account.status**：MVP 流量极低无忧；将来 QPS 起来需要引入缓存。**缓解**：在 design 层留出 `AccountStatusCache` 抽象空位，后续可无侵入插入。本 change **不**实现缓存。
- **JWT 16h 偏长 + 无 refresh**：用户操作中途 token 自然过期会被强制重登。**缓解**：16h ≈ 一个工作日，PRD 也未要求"工作日内不中断"；过期重登成本可接受。
- **新密码非空但无强度规则**：弱密码风险。**缓解**：spec 已禁空白；MVP 单 Admin 知情；future change 可加 `password-policy`。
- **初始 Admin 邮箱漂移**：若部署配置改了 `admin-email`，旧 Admin 邮箱仍在 DB，**新**邮箱被注入为新 Admin（违反 PRD §5.2.3 "MVP 不支持多 Admin"）。**缓解**：design 层不在数据库强约束 Admin 数量，靠**创建端点**拒绝 role=ADMIN（spec D5.2.3）；初始注入路径走 listener 不经过创建端点。若运维改邮箱，listener 会插入第二个 ADMIN 行——需在 D4 的 listener 中加防御：若数据库已存在任何 role=ADMIN 行，则**不**注入。**已加入 tasks**。
- **SystemLog 用 SLF4J 暂落地**：停用 / 启用产生的"日志"目前只进应用日志，不进 system_log 表。**缓解**：已记入项目记忆 `systemlog-port-noop`；进 `progress-log` change 时必须替换。
- **`Admin 不可停用自己`是应用层校验**：DB 无约束。MVP 单 Admin 场景下若被绕过会造成系统无 ADMIN 可登录。**缓解**：spec 强约束 + 测试覆盖；如绕过仅可能通过直接改 DB，运维责任。
- **Bearer token 携带在 Authorization 头**：标准做法，但 token 可能进入应用日志（如 access log）。**缓解**：Spring Boot 默认 access log **不**包含请求头；不引入第三方 access log filter；error 响应不回显 Authorization 头。
- **MVP 不限制登录尝试次数**：暴力破解风险。**缓解**：MVP 单 Admin + 内网部署，可接受；future change 可加 `login-rate-limit`。
