# Regression Impact Analysis

## Change Summary

- Requirement / change ID: `openspec/changes/system-log/`
- Change type: requirement + code + config（doc 同步修订 tech-arch §13.2）
- Changed behavior:
  - 引入 `system_log` 表与 `JdbcSystemLogPort`，{@code SystemLogPort.record(...)} 默认实现从 NoOp(SLF4J) 切换为 JDBC 真行落盘
  - account 事件（创建 / 停用 / 启用）的系统日志现在真实持久化，可被 Admin 审计回溯
  - 系统日志写入失败不阻塞业务主流程（spec R6）
- Impacted modules / APIs / pages:
  - 后端 `com.dealtrace.systemlog.*`、`AdminAccountController`（注入对象切换，无源码改动）
  - 数据库 `dealtrace.system_log` 表新增
  - 测试基线：`AdminAccountStatusControllerTest`（tablesToTruncate 扩展 + 2 项新断言）
  - 文档 `docs/技术架构与工程约束.md` §13.2（capability 表拆行 + 说明）
- Author / owner: system-log change（本 change）

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `JdbcSystemLogPortTest` | Add (new file) | system-log spec R1 / R3 / R5 | 覆盖字段集 / 服务端时钟 / 多态 target / NULL operator | 4 个集成测试，直接 INSERT + 直查表断言 |
| `JdbcSystemLogPortExceptionTest` | Add (new file) | system-log spec R6 | 覆盖异常路径不上抛 + ERROR 日志留痕 | 1 个单元测试，mock JdbcTemplate 注入异常 |
| `AdminAccountStatusControllerTest#disable_persistsSystemLogRow` | Add | auth-account R7 ∩ system-log R1 | 验证 spy.verify 之上的真行入库（旧 NoOp 时无法验证） | 新增 1 项断言；旧 `Mockito.verify(systemLogPort).record(...)` 保留 |
| `AdminAccountStatusControllerTest#enable_persistsSystemLogRow` | Add | 同上 | ENABLE 路径对称覆盖 | 同上 |
| `AdminAccountStatusControllerTest#tablesToTruncate()` | Modify | 跨测试隔离 | 由 `Set.of("account")` 扩为 `Set.of("account", "system_log")`，避免本类内 6 个 PATCH 测试残留 system_log 行 | 不影响旧断言；多事务测试隔离层 |
| `Slf4jSystemLogPort` javadoc | Modify | design D3 | 注明本类在 @Primary 启用后仅作回滚兜底 | 行为零变化；不需新增测试 |

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `JdbcSystemLogPort`（新增） | INSERT system_log 行；异常路径不上抛 | —— | `JdbcSystemLogPortTest` + `JdbcSystemLogPortExceptionTest` | 全新类，无既有行为可破坏 |
| `Slf4jSystemLogPort`（javadoc only） | 仍在 Spring 上下文（@Component），但 @Primary 让其不被注入 | 启动期所有 @SpringBootTest 加载该 bean | 无 | 行为零变化 |
| `V3__system_log.sql` | Flyway 启动期建表 | 所有 @SpringBootTest（共用 schema） | `JdbcSystemLogPortTest` 间接验证（INSERT 成功即表存在） | 单独 schema 校验测试可选，但与 auth-account 的 `AccountSchemaMigrationTest` 同模式可在未来补 |
| `AdminAccountController`（注入对象变化，源码不改） | 三处 `systemLogPort.record(...)` 调用现在真实落 DB；行为契约不变 | 全部 `AdminAccountControllerCreateTest` / `AdminAccountStatusControllerTest` / `AdminAccountControllerListTest` | 已包含在 status 测试新增项 | spy bean 透传到 @Primary JdbcSystemLogPort，原 spy 断言仍成立 |
| `MultiTransactionalIntegrationTest` 子类 `tablesToTruncate` | 仅 `AdminAccountStatusControllerTest` 需扩列；其他子类（`AdminBootstrapListenerTest`）的业务路径不调用 `record(...)`，无需扩列 | `AdminBootstrapListenerTest` 已确认不写 system_log | 无 | grep 验证：`backend/src/test/**/*Test.java` 仅 `AdminAccountStatusControllerTest` 触发 systemLogPort.record |
| `docs/技术架构与工程约束.md` §13.2 | capability 命名表新增 system-log 行、拆 progress-log | —— | 无（doc 修订） | 已在表后加说明，便于后续读者回溯拆分决策 |

## Risk Level

- Risk: Low
- Rationale:
  - 写路径全部新增，对既有 41 项测试零行为入侵（spy 透传 + 多事务 truncate 列扩展）
  - 系统日志写失败不传染业务主流程（spec R6 + design D2），是显式权衡而非疏忽
  - 无 API 新增、无前端、无依赖升级
  - 已在 design 显式列出"Bootstrap 注入 Admin 未记日志"为 Non-Goal，避免越权扩展 auth-account spec
  - 真 MySQL 8.4 测试 + Flyway V3 自动 apply 已在本地 verified

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| `JdbcSystemLogPortTest` | API/integration | 本 change 主写入路径（spec R1 / R3 / R5） | `mvn test -Dtest=JdbcSystemLogPortTest` | PASS | 4/4 Green，耗时 ~13s |
| `JdbcSystemLogPortExceptionTest` | Unit | 异常路径（spec R6） | `mvn test -Dtest=JdbcSystemLogPortExceptionTest` | PASS | 1/1 Green |
| `AdminAccountStatusControllerTest` | API/integration | 验证 spy 透传 + system_log 真行入库 | `mvn test -Dtest=AdminAccountStatusControllerTest` | PASS | 8/8 Green（6 旧 + 2 新） |
| 全套 `mvn test` | Unit + API/integration | auth-account 与 platform-foundation 既有覆盖不退化 | `mvn test` | PASS | 48/48 Green（旧 41 + 新 7） |
| `mvn verify`（CI 对齐） | 完整 lifecycle | 启动期 Flyway V3 在 CI 真 MySQL 上 apply | `mvn verify` | 见 QA 报告（后台运行） | 待 mvn verify 完成 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 手工 PATCH 真实运行后 `SELECT * FROM system_log`（任务 2.5） | 需用户启动后端并发 HTTP PATCH | —— | 用户在 dev profile 启动后端、用 admin token PATCH 任一 Sales 停用，SELECT 验证（仅作 smoke evidence） | 极小：自动化集成测试已覆盖等价路径 |
| 本地 dev profile Flyway V3 启动 apply（任务 1.2） | 同上 | —— | 用户启动后端确认 Flyway 历史表出现 V3 行 | 极小：`mvn test` 已经在真 MySQL 启动 Spring 上下文，等价证明 V3 在该环境可 apply |

## Runtime QA Validation

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| 推荐（非必需） | dev profile 与 test profile 共用 schema，但 `application-local.yml` 可能有差异；首次部署前手工 smoke 一次便于发现配置漂移 | 启动后端 → admin 登录 → PATCH 任一 Sales /status DISABLED → `SELECT * FROM system_log WHERE target_id = <sales.id>` 应有 1 行 ACCOUNT_DISABLE | 待用户执行 | 用户回填 |

## Regression Conclusion

- Overall result: PASS（自动化部分；手工 smoke 待用户回填）
- Changed behavior covered:
  - system_log 字段集（R1）、不可变（R2 通过反向静态约束 + 缺失 UPDATE/DELETE 端点）、服务端时钟（R3）、自动生成（R4 通过反向）、多态 target（R5）、写入失败不上抛（R6）—— 全部由 5 项新测试 + 2 项新断言覆盖
- Directly impacted old behavior covered:
  - auth-account R7（启停日志契约）：8/8 status 测试 Green，原 spy verify + 新真行断言双重保证
  - auth-account R5（创建账号）：5/5 create 测试 Green（@Transactional 自动回滚，system_log row 随之回滚，无残留）
  - platform-foundation 全部基础设施：14 项相关测试全 Green
- Historical defects considered: 无历史缺陷涉及 SystemLog 路径（NoOp 阶段无业务行为可破坏）
- Uncovered test points:
  - TP8 / TP9（不存在 UPDATE/DELETE 端点、不存在外部写入端点）由静态约束 + code-review 保证，无自动化覆盖；当前实现路径仅一处 INSERT，反向风险极低
- Unresolved prerequisite blockers: 无
- Remaining risks:
  - spec R6 的"业务先 commit、日志写失败时日志可丢" trade-off：MVP 单 DB 实例 + 同事务，发生概率极低；如发生，SLF4J ERROR 行 + 运维人工补对账
  - `MultiTransactionalIntegrationTest` 子类未来新增者忘了 truncate system_log 时，跨测试可能残留行：依赖测试失败暴露
