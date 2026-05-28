## 1. QA 设计先行

- [x] 1.1 在 `openspec/changes/auth-account/qa/lightweight-test-design.md` 中规划全部测试：标注哪些走严格 Red-Green（§3 登录 / §4 Filter 实时校验 / §5 改密 / §6 Admin CRUD / §7 启停 / §8 初始 Admin 注入），哪些为 documented non-TDD exception（如 BCryptPasswordEncoder 注入这种 framework wiring，验证方式 = 集成测试间接覆盖）；逐条 spec.md Requirement 列出其覆盖测试入口。

## 2. DB Migration 与 Entity

- [x] 2.1 写 `backend/src/main/resources/db/migration/V2__account.sql`：CREATE TABLE account（id / email UNIQUE / password_hash CHAR(60) / name / role VARCHAR(16) / status VARCHAR(16) / created_at / updated_at / disabled_at NULL）；charset utf8mb4 collate utf8mb4_unicode_ci。
- [x] 2.2 写一个继承 `IntegrationTest` 的 `AccountSchemaMigrationTest`：通过 JdbcTemplate 查询 `information_schema.tables` 与 `information_schema.statistics`，断言 `account` 表存在、`uk_account_email` 唯一索引存在。先 Red（未写 V2 时），再 Green。
- [x] 2.3 实现 `com.dealtrace.account.entity.Account`（MyBatis-Plus 实体，`@TableName("account")`）；`Role` / `AccountStatus` 枚举（带 `@EnumValue`）；`com.dealtrace.account.repository.AccountMapper extends BaseMapper<Account>`。
- [x] 2.4 在 `DealTraceApplication` 添加 `@MapperScan("com.dealtrace.**.repository")`（若 scaffold 未配）。

## 3. 登录端点（spec §邮箱与密码登录）

- [x] 3.1 写 `LoginControllerTest`（继承 `IntegrationTest`，@AutoConfigureMockMvc）：先用 `AccountMapper` 插入一条 ENABLED 账号（密码哈希用 BCryptPasswordEncoder.encode("p@ssw0rd")），断言 POST `/api/auth/login` 正确密码返回 200 + code=SUCCESS + data.token 非空 + data.email/name/role 与账号一致 + 响应体不含 passwordHash。Red。
- [x] 3.2 在 §3.1 中添加场景：邮箱不存在 → 401 + UNAUTHORIZED + message 不暴露"邮箱不存在"；密码错 → 401 + UNAUTHORIZED + message 与"邮箱不存在"场景一致；停用账号 → 401 + UNAUTHORIZED + message="账号已停用"。Red。
- [x] 3.3 实现 `com.dealtrace.auth.JwtService`：`generateToken(Account)` 返回 HS256 签发的 JWT，claims = sub(id) / email / role / iat / exp(now + 16h)；`parseToken(String)` 解析返回 claims map。注入 `@Value("${dealtrace.jwt.secret}")` secret + `@Value("${dealtrace.jwt.ttl-hours:16}")` ttl。
- [x] 3.4 在 `application.yml` / `application-local.yml` / `application-test.yml` 添加 `dealtrace.jwt.secret`（占位 `${DEALTRACE_JWT_SECRET}`）与 `dealtrace.jwt.ttl-hours: 16`。
- [x] 3.5 实现 `com.dealtrace.security.config.PasswordConfig`：`@Bean PasswordEncoder` → `new BCryptPasswordEncoder()`。
- [x] 3.6 实现 `com.dealtrace.auth.LoginController`：`POST /api/auth/login` 接收 `LoginRequest{email, password}`，查账号 → 校状态（DISABLED 返回 401 + "账号已停用"）→ 校密码（不匹配返回 401 + "邮箱或密码错误"，与邮箱不存在文本相同）→ 签发 token → 返回 `LoginResponse{token, email, name, role}`。使 §3.1 §3.2 全部 Green。

## 4. JwtAuthenticationFilter 实时校验（spec §访问令牌携带身份）

- [x] 4.1 在 `SecurityScaffoldTest` 同目录新增 `JwtAuthenticationFilterTest`：通过测试 profile 注入一条 ENABLED 账号 + 签发 token，请求 `/api/test/protected`（已存在的测试端点）携带 token → 200；不带 Authorization → 401（已通过 scaffold 覆盖，仅回归即可）；携带非法 token → 401。Red。
- [x] 4.2 新增"停用导致下次请求 401"场景：插入 ENABLED 账号 → 签发 token → 改 status=DISABLED → 携带原 token 请求 `/api/test/protected` → 401 + message="账号已停用"。此场景必须用 `MultiTransactionalIntegrationTest`（账号状态变更要 commit 才能被 Filter 看见）。Red。
- [x] 4.3 改造 `JwtAuthenticationFilter.doFilterInternal`：读 Authorization → 解 token（非法抛 `BadCredentialsException`）→ 取 sub → `AccountMapper.selectById` → null 抛 `BadCredentialsException("账号不存在")` → status=DISABLED 抛 `DisabledException("账号已停用")` → 构造 `AccountPrincipal(id, email, role)` + `UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))` 放 SecurityContext。
- [x] 4.4 改造 `JwtAuthenticationEntryPoint`：能从 `AuthenticationException.getMessage()` 取出 "账号已停用" / "原密码错误" 等业务 message，写入 ApiResponse.message；保持 code=UNAUTHORIZED。
- [x] 4.5 实现 `com.dealtrace.security.AccountPrincipal`（record，含 id/email/role），使 §4.1 §4.2 全部 Green。

## 5. 当前用户改密（spec §当前用户修改自身密码）

- [x] 5.1 写 `ChangePasswordControllerTest`（继承 `MultiTransactionalIntegrationTest`，因要登录 → 改密 → 重新登录验证）：插入 ENABLED 账号 → 登录拿 token → POST `/api/auth/change-password` `{oldPassword, newPassword}` 旧密码正确 → 200 + code=SUCCESS + 重新查 DB 密码哈希已变 + 旧密码登录失败 + 新密码登录成功。Red。
- [x] 5.2 在 §5.1 中新增场景：旧密码错 → 401 + message="原密码错误" + 哈希不变；newPassword 为空白 → 400 + VALIDATION_ERROR + 哈希不变。Red。
- [x] 5.3 实现 `com.dealtrace.auth.ChangePasswordController`：`POST /api/auth/change-password`，`@AuthenticationPrincipal AccountPrincipal` 拿当前用户 → 查 Account → BCryptPasswordEncoder.matches(oldPassword, hash) 失败抛业务异常映射 401 → newPassword trim 后空抛 VALIDATION_ERROR → 更新 hash + updated_at。使 §5.1 §5.2 Green。

## 6. 当前用户信息 /me（spec §当前用户查询自身信息）

- [x] 6.1 写 `MeControllerTest`：登录拿 token → GET `/api/auth/me` 携带 token → 200 + code=SUCCESS + data 含 id/email/name/role/status，**不**含 passwordHash/token。Red。
- [x] 6.2 实现 `com.dealtrace.auth.MeController`：`GET /api/auth/me`，`@AuthenticationPrincipal AccountPrincipal` + AccountMapper.selectById 拿完整字段 → 返回 `MeResponse`。Green。

## 7. Admin 账号 CRUD

- [x] 7.1 写 `AdminAccountControllerCreateTest`：以 Admin token 调 POST `/api/admin/accounts` `{email, name, password, role=SALES}` → 200 + 返回 id/email/name/role=SALES/status=ENABLED + 响应不含 password/passwordHash。Red。
- [x] 7.2 §7.1 新增场景：以 Sales token 调 → 403 + FORBIDDEN；以匿名调 → 401；邮箱重复 → 400 + VALIDATION_ERROR + message="邮箱已存在"；role=ADMIN → 400 + VALIDATION_ERROR + message 含"角色非法"；email/name/password 为空 → 400 + VALIDATION_ERROR。Red。
- [x] 7.3 实现 `com.dealtrace.account.AdminAccountController.createSales(...)`：`@PreAuthorize("hasRole('ADMIN')")` 或在 SecurityConfig 配 path-level（design 选 path-level，但 controller 方法仍可用 `@PreAuthorize` 双保险——本 change 选 path-level，避免引入注解扫描）；校验字段 → BCryptPasswordEncoder.encode(password) → insert account（status=ENABLED）→ 调 `SystemLogPort.record("ACCOUNT_CREATE", "ACCOUNT", newId, adminId)`。Green。
- [x] 7.4 写 `AdminAccountControllerListTest`：插入 1 个 Admin + 2 个 Sales（1 启 1 停），Admin token 调 GET `/api/admin/accounts` → 200 + data 数组含 3 条，按 createdAt 排序，每条含 id/email/name/role/status/createdAt，**不**含 passwordHash；Sales token 调 → 403。Red。
- [x] 7.5 实现 `AdminAccountController.list()`：SELECT 全部账号映射为 DTO 列表（剥离 passwordHash）。Green。

## 8. Admin 启停账号（spec §Admin 启用与停用账号）

- [x] 8.1 写 `AdminAccountStatusControllerTest`（继承 `MultiTransactionalIntegrationTest`，因要验证状态变更后再用 token 验证 §4.2 已覆盖的语义；本测仅验证状态本身 + 系统日志 + 不可停用自己）：插入 1 Admin + 1 Sales(ENABLED) → Admin token 调 PATCH `/api/admin/accounts/{salesId}/status` body `{status: "DISABLED"}` → 200 + DB account.status=DISABLED + disabled_at != null + 系统日志被调用（用 `@MockitoSpyBean SystemLogPort` 断言 .record 被调用一次，action="ACCOUNT_DISABLE", targetId=salesId, operatorId=adminId）。Red。
- [x] 8.2 §8.1 新增场景：再次停用同一账号 → 200 + status 仍 DISABLED（幂等）；启用 DISABLED 账号 → 200 + status=ENABLED + disabled_at=null；Admin 调 PATCH 自己 id `{status: "DISABLED"}` → 400 + VALIDATION_ERROR + message="不可停用自己" + 账号状态不变；Sales token 调 → 403；目标 id 不存在 → 404 + NOT_FOUND。Red。
- [x] 8.3 实现 `AdminAccountController.updateStatus(id, request)`：校 id != current admin id（否则 VALIDATION_ERROR）→ AccountMapper.selectById 不存在抛 NOT_FOUND → 状态相同则跳过更新仅返回 SUCCESS（幂等，可不重复 record systemlog）→ 状态不同则 update status + updated_at + (disabled_at = now / null) + 调 SystemLogPort.record。Green。

## 9. 初始 Admin 注入（spec §初始 Admin 由部署配置注入）

- [x] 9.1 写 `AdminBootstrapListenerTest`（@SpringBootTest @ActiveProfiles("test")，不继承 IntegrationTest，因要让 listener 真执行；用 @Transactional(propagation=NOT_SUPPORTED) + @AfterEach truncate）：场景一——清空 account 表 → 重新触发 ApplicationReadyEvent（context publish）→ 断言 account 表新增一条 role=ADMIN/status=ENABLED/email=配置邮箱 + 密码哈希可被 BCryptPasswordEncoder.matches(配置密码, hash) 校验通过。Red。
- [x] 9.2 §9.1 新增场景二：account 表已有同邮箱 ADMIN（不同 name / password_hash）→ publish event → 断言该行所有字段保持不变 + 不新增其他行。Red。
- [x] 9.3 §9.1 新增场景三：account 表已有**其他邮箱**的 ADMIN 行 → publish event → 断言**不**注入配置邮箱（防多 Admin 漂移；与 design D4 风险缓解一致）。Red。
- [x] 9.4 在 `application.yml` 添加 `dealtrace.bootstrap.admin-email: ${DEALTRACE_ADMIN_EMAIL}` 与 `dealtrace.bootstrap.admin-password: ${DEALTRACE_ADMIN_PASSWORD}`；在 `application-test.yml` 给出测试默认值（如 `admin@dealtrace.test` / `Adm1n!Test`），保证测试环境无需用户级环境变量。
- [x] 9.5 实现 `com.dealtrace.bootstrap.AdminBootstrapListener implements ApplicationListener<ApplicationReadyEvent>`：在 onApplicationEvent 中读 email/password → 查 `count(*) where role='ADMIN'`，>0 则 log.info 跳过（防多 Admin 漂移）→ 否则 insert 一条 admin。Green。

## 10. SystemLog 端口（NoOp 实现）

- [x] 10.1 实现 `com.dealtrace.systemlog.SystemLogPort` 接口与 `Slf4jSystemLogPort` 实现（@Component，写 SLF4J INFO 日志，含 action/targetType/targetId/operatorId）。
- [x] 10.2 通过 §7.3 与 §8.3 中的 spy 断言间接覆盖（已在 §7.3 / §8.1 中调用 `SystemLogPort.record`）；本任务**不**单独写 PortTest（无业务行为可断）。
- [x] 10.3 在 design.md follow-up 段（已有）与项目记忆 `systemlog-port-noop`（已存）双重提醒：进 progress-log change 必须替换为 JdbcSystemLogPort。

## 11. SecurityConfig 收紧

- [x] 11.1 改造 `SecurityConfig.securityFilterChain`：
  - `POST /api/auth/login` → permitAll
  - `/api/auth/me` / `/api/auth/change-password` → authenticated
  - `/api/admin/**`（任何 method）→ `hasRole("ADMIN")`
  - 其余沿用 authenticated()
  - `/api/health` 已 permitAll
- [x] 11.2 写 `SecurityPathRuleTest`：
  - 匿名 POST `/api/auth/login` 不被 401（虽然 body 校验会 400，但**不**是 401）
  - 匿名 GET `/api/admin/accounts` → 401
  - Sales token GET `/api/admin/accounts` → 403
  - Sales token GET `/api/auth/me` → 200
  - Admin token GET `/api/admin/accounts` → 200
  Red → Green。
- [x] 11.3 删除 / 重构 scaffold 留下的 `/api/test/protected` 端点：保留在测试 profile 用于 SecurityScaffoldTest 与 JwtAuthenticationFilterTest（已存在）；本 change 不引入新的 `/test/**` 路径。

## 12. QA 收尾产物

- [x] 12.1 在 `qa/qa-test-report.md` 中按 canonical 模板（参考 scaffold-monorepo 归档版本）汇总：Conclusion / Scope / TDD Summary / Tests Run / Coverage / Regression Scope / Runtime QA Validation / Failure Analysis / Failure Learning / Remaining Risks / Final Statement；逐任务（§3–§11.2）记录 Red 命令输出原文 + Green 后 `mvn -B test` 摘要。
- [x] 12.2 在 `qa/regression-impact-analysis.md` 中说明：本 change 影响 scaffold 留下的 SecurityConfig / JwtAuthenticationFilter / JwtAuthenticationEntryPoint，回归测试覆盖 = 全部新增 8 个 controller/filter test + scaffold 既有 8 个测试无回归（重跑 `mvn -B test` 全 PASS）。
- [x] 12.3 运行 `node .claude/skills/vibe-coding-qa/scripts/qa_artifacts.mjs check lightweight-test-design openspec/changes/auth-account/qa/lightweight-test-design.md` 与同样命令对 `qa-test-report`；解决所有 FAIL / WARN。
- [x] 12.4 运行 `openspec validate auth-account --strict`，确认 "Change 'auth-account' is valid"。
