# Regression Impact Analysis

## Change Summary

- Requirement / change ID: `openspec/changes/lead-core/`
- Change type: requirement + code（含 DB schema + 接口扩展 + 安全配置启用）
- Changed behavior:
  - 新增 lead capability 第一站：14 字段表、5 端点（POST 创建 / GET 详情 / mine / 全局 / duplicate-check）、三元组三态查重、归属规则分支、联系电话校验、LEAD_CREATE 系统日志
  - **扩展 `SystemLogPort` 接口**：5 参方法（含 summary）为主，4 参 default 委派为 summary=null；JdbcSystemLogPort / Slf4jSystemLogPort 同步实现；既有 account 事件调用方零代码修改
  - `ErrorCode` 追加 `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD`；`GlobalExceptionHandler.handleBusiness` switch 同步加 → BAD_REQUEST 映射
  - `SecurityConfig` 加 `@EnableMethodSecurity`；`LeadController.listAll()` 加 `@PreAuthorize("hasRole('ADMIN')")`
- Impacted modules / APIs / pages:
  - 后端新增 `com.dealtrace.lead.**` + `PhoneValidator` + `LeadDuplicateService`
  - 后端修改 `SystemLogPort` / `JdbcSystemLogPort` / `Slf4jSystemLogPort` / `ErrorCode` / `GlobalExceptionHandler` / `SecurityConfig`
  - 数据库新增 `lead` 表（含双 FK 到 customer / account）
- Author / owner: lead-core change

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `PhoneValidatorTest` | Add（11 cases，纯单元） | spec R3 + design D7 | TDD Red→Green：stub → 5 个行为失败 → 实现 → 11/11 PASS | 手机号 + 座机正负样本 + 边界 |
| `LeadDuplicateServiceTest` | Add（7 cases） | spec R5 | 三态决策表 5 case + 跨年度 / 跨业务类型不算重复 | IntegrationTest 单事务 |
| `LeadControllerCreateTest` | Add（15 cases） | spec R1-R5 + R8 | 创建路径完整覆盖：归属分支 / 校验失败 / 查重三态 / LEAD_CREATE 日志 + summary | IntegrationTest |
| `LeadControllerDetailListTest` | Add（8 cases） | spec R7 | 详情 / mine / listAll 权限隔离 + 404 不泄漏 + 内联 customerName | IntegrationTest |
| `LeadDuplicateCheckTest` | Add（4 cases） | spec R6 | 预检：空 / active 阻塞 / 仅 LOST 提示历史 / 不写持久化 | IntegrationTest |
| `LeadNotInScopeTest` | Add（6 cases） | spec R9 | 反向断言：claim / release / assign / stage / win / lose 端点未映射 | IntegrationTest |
| `JdbcSystemLogPortExceptionTest` | Modify | design D13 | Mockito.when matcher 从 7 args 扩到 8 args（多 summary 占位） | 1/1 PASS |
| `AdminBootstrapListenerTest.clearAccountTable` | Modify | 新 FK | TRUNCATE account 因 lead.owner_sales_id FK 被拒 → 加 SET FOREIGN_KEY_CHECKS=0 包裹 | 3/3 PASS |
| `CustomerConcurrentInsertTest` | Modify | 跨 session 数据隔离 | 旧 smoke 留下的 USCI Q 客户使两线程 app-level 都拦截 → @BeforeEach 加 TRUNCATE customer（FK_CHECKS=0） | 1/1 PASS |
| `ErrorCode.java` | Modify | tech-arch §6.3.6/7 占位 | 追加 DUPLICATE_ACTIVE_LEAD / DUPLICATE_WON_LEAD | 编译期保证 + TP16/TP17 显式验证 |
| `GlobalExceptionHandler.java` switch | Modify | spec R5 | switch case 追加 → BAD_REQUEST；既有 5 个映射不变 | TP16/TP17 实际请求 400 验证 |
| `SecurityConfig.java` | Modify | spec R7 + design D12 | 加 `@EnableMethodSecurity`；既有 path-based 不变 | TP28（Sales→listAll 403）+ TP29（Admin→listAll 200） |
| `SystemLogPort.java` | Modify | design D13 | 5 参主方法 + 4 参 default 委派；既有 4 参调用方零修改 | `AdminAccountStatusControllerTest` 8/8 Green 证明 |

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `V5__lead.sql` | Flyway 启动期建表（含双 FK） | 全套 mvn test | LeadControllerCreateTest 等间接验证表结构 + UNIQUE / FK | **首次尝试 V5 失败**：表名 `lead` 是 MySQL 8 保留字（LEAD() 窗口函数），CREATE TABLE 失败 → flyway_schema_history 留 success=FALSE 行；修复：表名加反引号 `` `lead` ``；删除失败历史行后重 apply 成功 |
| `lead.owner_sales_id` FK 到 account | TRUNCATE account 被拒 | `AdminBootstrapListenerTest.clearAccountTable` | 已修复（FK_CHECKS=0 包裹） | 影响所有直接 TRUNCATE account 的测试；目前仅 AdminBootstrapListenerTest 一例 |
| `lead.customer_id` FK 到 customer | TRUNCATE customer 被拒 | `CustomerConcurrentInsertTest` 新增 @BeforeEach | 已修复（FK_CHECKS=0 包裹） | 同上模式 |
| `SystemLogPort` 5 参扩展 | account 事件调用方通过 default 委派 | system-log 5 + auth-account 8 共 13 项 | 全 Green | Mockito @MockitoSpyBean 对 default method 透传正常（Mockito 5） |
| `ErrorCode` 追加 | switch / 映射变化 | `GlobalExceptionHandlerTest` 2 项 | 全 Green | 现有 5 映射不变 |
| `SecurityConfig` 启用 `@EnableMethodSecurity` | 方法级权限注解生效 | `SecurityPathRuleTest` 5 项 | 全 Green | path-based 与 method-level 共存 |

## Risk Level

- Risk: **Medium**
- Rationale:
  - **MySQL 保留字陷阱**（lead）触发了一次 V5 失败 apply → 留下脏 flyway_schema_history → 阻塞所有后续 mvn test。修复需手动清表 + 删失败历史行。已沉淀为项目记忆。
  - **跨表 FK 改变 truncate 语义**：新增 FK 影响 2 处既有测试的 TRUNCATE 路径，已修复并核查无遗漏（grep `TRUNCATE.*account` / `delete.*null` 已 audit）
  - **SystemLogPort 接口扩展**：default method 兜底设计避免破坏既有 4 参调用方；通过 13 项 system-log + auth-account 测试验证无回归
  - 新增 5 端点 + 9 个 spec requirement 实现：51 项测试一次性 Green（除 PhoneValidator 走 TDD Red→Green）
  - 风险中等是因为这是单 change 内迄今最大改动面（新表 + 双 FK + 接口扩展 + 配置启用 + 9 个 requirement），但全部由自动化覆盖

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| `PhoneValidatorTest` | Unit | TDD Red→Green | `mvn test -Dtest=PhoneValidatorTest` | PASS | 11/11，~0.3s |
| `com.dealtrace.lead.*Test` + `com.dealtrace.lead.service.*Test` | Unit + Integration | lead-core 全部 51 case | `mvn test -Dtest=com.dealtrace.lead.*Test,com.dealtrace.lead.service.*Test` | PASS | 51/51 |
| system-log 5 + auth-account 13 | Regression (SystemLogPort 5 参影响面) | 验证 default 委派对既有调用方零退化 | `mvn test -Dtest=com.dealtrace.systemlog.*Test,com.dealtrace.account.*Test,com.dealtrace.common.*Test` | PASS | 26/26（第一轮回归即通过） |
| 全套 `mvn test` | Regression | 旧 79 + 新 51 全 Green | `mvn test` | PASS | `Tests run: 130, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn verify` | 完整 lifecycle + repackage | CI 对齐 | `mvn verify` | （待回填） | （待回填） |
| 手工 smoke | Runtime availability | 真实 HTTP + JDBC | （待回填） | （待回填） | （待回填） |

## Tests Not Run / Blockers

| Test / scope | Reason | Required owner action | Residual risk |
| --- | --- | --- | --- |
| `mvn verify` 结果回填 | 后台运行中 | 等任务完成 | 极小：mvn test 130/130 已通过，verify 主要追加 repackage |
| 中文 contactName / contactPhone 走 HTTP 路径手工 smoke | Git Bash on Windows CP936 编码 → 非 UTF-8 body → Jackson 拒（与 customer change 同款问题） | 用户用 Postman / UTF-8 文件 | 极小：MockMvc 集成测试已用中文 contactName 覆盖 |

## Runtime QA Validation

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| Yes | V5 schema + 5 端点的真实可达性；JDBC 验证 SHOW CREATE TABLE 与 design D3 一致 | 待 mvn verify 完成后启动 dev backend → admin 登录 → create customer → POST /api/leads → GET /api/leads/{id} → GET /api/leads/mine（admin 视角通常空）→ GET /api/leads → GET /api/leads/duplicate-check → POST 重复三元组 → JDBC SHOW CREATE TABLE lead | （待回填） | （待回填） |

## Regression Conclusion

- Overall result: **PASS（自动化部分）**；smoke 待回填
- Changed behavior covered:
  - spec R1-R9 全部由 51 项 lead-core 测试覆盖；PhoneValidator + LeadDuplicateService 两处走 TDD Red→Green
  - SystemLogPort 5 参扩展由 system-log 既有 5 项 + auth-account 13 项无退化证明
  - ErrorCode / GlobalExceptionHandler / SecurityConfig 修改由 lead-core 创建测试 + 既有 GlobalExceptionHandlerTest / SecurityPathRuleTest 覆盖
- Directly impacted old behavior:
  - auth-account 38/38 Green（含 status / create / list / bootstrap / login / changePassword / me）
  - customer 31/31 Green（含 concurrent + immutability + search + create + systemLogQuiet + UsciValidator）—— CustomerConcurrentInsertTest 修了一处旧 smoke 数据隔离问题
  - system-log 5/5 Green
  - platform-foundation 全部 Green
- Historical defects considered: 一次 V5 保留字踩坑，已修复并沉淀
- Unresolved prerequisite blockers: 无
- Remaining risks:
  - 并发查重双行（spec R5 显式接受）
  - utf8mb4_unicode_ci collation 对英文公司名 / 联系人名大小写不敏感（沿袭 customer D1 trade-off）
  - 跨 session smoke 数据可能持续累积，未来如有更多 USCI / lead 持久化测试需注意
