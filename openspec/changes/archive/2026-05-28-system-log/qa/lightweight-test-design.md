# Lightweight Test Design

## Context

- Requirement / Spec: `openspec/changes/system-log/specs/system-log/spec.md`（6 个 ADDED requirement：R1 字段集、R2 不可变、R3 服务端时钟、R4 系统自动生成、R5 多态 target、R6 写入失败不阻塞）。权威依据回溯到 PRD §7.1.11 / §7.8 / §9.5 / §8.5。
- Change summary: `openspec/changes/system-log/` —— 落地 `system_log` 表 + `JdbcSystemLogPort` 替换 auth-account 留下的 NoOp，account 事件日志真实落盘；不交付读 API 与 ProgressLog。
- Target modules / APIs / pages:
  - `backend/src/main/resources/db/migration/V3__system_log.sql`（新增表）
  - `backend/src/main/java/com/dealtrace/systemlog/JdbcSystemLogPort.java`（新增 `@Primary` 实现）
  - `backend/src/main/java/com/dealtrace/systemlog/Slf4jSystemLogPort.java`（保留作 fallback，javadoc 更新）
  - `backend/src/main/java/com/dealtrace/account/AdminAccountController.java`（不修改，但 `SystemLogPort` 注入对象从 NoOp 切到 Jdbc）
- Test environment / constraints:
  - 真 MySQL 8.4（tech-arch §12 钉死），database 名 `dealtrace`，通过 `DB_HOST/DB_USER/DB_PASSWORD` 环境变量连接
  - 测试 profile = `test`（`application-test.yml`），共用同一 schema
  - 多事务测试用 `MultiTransactionalIntegrationTest`，每 test 后 `TRUNCATE` 指定表
  - 现有 auth-account 集成测试基线全 Green（41/41）

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria / issue —— `specs/system-log/spec.md` + PRD §7.1.11 / §7.8 / §9.5
- [x] Existing behavior baseline: tests / code / old Spec / API contract —— auth-account spec R7（停用日志契约）、`AdminAccountStatusControllerTest`、`SystemLogPort` 接口签名
- [x] Data model / field rules / CRUD matrix —— design D1 的 7 列 + 3 索引；仅 INSERT，无 UPDATE / DELETE
- [x] API contract / auth rules / error shape —— 无新增 API；写入失败不外泄（spec R6）
- [x] UI states / user roles / user paths —— 无前端
- [x] Code structure / changed code / dependency graph —— 见 design D2 (裸 JdbcTemplate) / D3 (bean 优先级)
- [x] Existing tests / historical defects / flaky areas —— `AdminAccountStatusControllerTest.@MockitoSpyBean SystemLogPort` 透传到新 @Primary bean；现有断言（调用次数 / 参数）仍然成立
- [x] Test data / credentials / mocks / CI constraints —— `application-test.yml` 已提供 admin bootstrap；CI 用真 MySQL

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 系统日志 lead_id 必填 | PRD §9.5「关联业务线索」字面 | PRD §7.1.11「停用账号生成系统日志」+ 已实现的 `SystemLogPort.record` 签名（无 lead 入参） | extends | 本 change explore 阶段决议（design D7 / D1） | Proceed —— 表设计 `lead_id NULL`，spec R5 显式承认 account 事件 `lead_id=NULL`、lead 事件 `lead_id=target_id`，已在 design.md / spec.md 留档 |
| auth-account 停用产出 1 条系统日志 | `AdminAccountStatusControllerTest.disableEnabledSalesUpdatesStateAndLogs` 断言 `Mockito.verify(systemLogPort, times(1)).record(...)` | spec R1 + R2（fields + immutability） | extends | auth-account spec R7 + 本 change spec R1 | Proceed —— spy bean 透传到 @Primary JdbcSystemLogPort，原断言不变；追加直查 `system_log` 表的断言 |
| `Slf4jSystemLogPort` 默认注入 | auth-account 之 `@Component` 注解 | 本 change design D3：保留 `@Component`，由 JdbcSystemLogPort 的 `@Primary` 接管注入 | amends | 本 change design D3 | Proceed —— Slf4jSystemLogPort 类不删，javadoc 注明其在 @Primary 启用后仅作回滚兜底 |

无 `conflicts` 行，gate 放行。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TP1 account 事件持久化字段完整 | spec R1 场景 1 | 等价类（典型 account 事件） | API/integration | `record("ACCOUNT_DISABLE","ACCOUNT",100L,1L)` | system_log 行存在且 7 列符合预期 | action / target_type / target_id / operator_id / lead_id / summary / created_at 全部断言 | P0 | `backend/src/test/java/com/dealtrace/systemlog/JdbcSystemLogPortTest.java#record_account_event_persistsAllRequiredFields` |
| TP2 operator_id 可为 NULL（系统自动） | spec R1 场景 2 | 边界（null operator） | API/integration | `record("SYSTEM_AUTO","ACCOUNT",100L,null)` | system_log 行的 operator_id 列为 NULL | operator_id IS NULL；其余必填字段仍非空 | P0 | `JdbcSystemLogPortTest#record_systemAutoOperation_operatorIdIsNull` |
| TP3 created_at 服务端生成且单调递增 | spec R3 | 等价类 + 时序边界 | API/integration | 同一测试方法内两次连续 `record(...)`，中间 `Thread.sleep(10)` | 两行 created_at 严格递增；均落在测试执行窗口 `[before, after]` | created_at 顺序 + 区间夹断言 | P1 | `JdbcSystemLogPortTest#record_createdAt_isServerGenerated` |
| TP4 LEAD target 时 lead_id 等于 target_id | spec R5 | 等价类（未来 lead 事件预演） | API/integration | `record("LEAD_CLAIM","LEAD",200L,1L)` | system_log 行 lead_id=200, target_id=200, target_type='LEAD' | 三字段一致性 | P0 | `JdbcSystemLogPortTest#record_leadTarget_leadIdEqualsTargetId` |
| TP5 写入异常不上抛、业务事务不回滚 | spec R6 | 异常注入（错误处理路径） | Unit | mock `JdbcTemplate.update(...)` 抛 `DataAccessException` | `record(...)` 不抛、SLF4J ERROR 行包含 action / target / operator | 无异常 + 日志断言 | P0 | `JdbcSystemLogPortTest#record_jdbcThrows_doesNotPropagate` |
| TP6 auth-account 停用 → system_log 真行入库 | auth-account R7 + 本 change R1 | 回归（跨 capability 透传） | API/integration | PATCH `/admin/accounts/{id}/status` body `{"status":"DISABLED"}` | system_log 新增 1 行 action='ACCOUNT_DISABLE' / target_type='ACCOUNT' / target_id=sales.id / operator_id=admin.id | spy verify（保留）+ JdbcTemplate 直查 system_log 断言 | P0 | `backend/src/test/java/com/dealtrace/account/AdminAccountStatusControllerTest.java#disable_persistsSystemLogRow` |
| TP7 auth-account 启用 → system_log 真行入库 | 同上 ENABLE 路径 | 回归 | API/integration | PATCH body `{"status":"ENABLED"}`（前置先停用） | system_log 新增 1 行 action='ACCOUNT_ENABLE'，其余字段同 TP6 | 同 TP6 | P0 | `AdminAccountStatusControllerTest#enable_persistsSystemLogRow` |
| TP8 不存在任何 UPDATE/DELETE system_log 端点 | spec R2 | 反向（搜索代码 + 路由） | 静态审查 | grep `system_log` 在所有 `*Controller*.java`、`*Mapper*.java`、`*Service*.java` | 仅 INSERT 路径存在 | 0 UPDATE / 0 DELETE 路径 | P1 | 通过 code-review；非自动化断言（design 已限定写路径只在 JdbcSystemLogPort.record 内） |
| TP9 不存在外部写入 system_log 的 HTTP 端点 | spec R4 | 反向 | 静态审查 | grep `system_log` / `system-log` 在 `@RequestMapping` 注解附近 | 0 命中 | 0 外部端点 | P1 | 通过 code-review |

## TDD Candidates

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| TP1 | `JdbcSystemLogPortTest#record_account_event_persistsAllRequiredFields` | NoOp 不写 DB；JdbcSystemLogPort 类尚不存在 | 编译失败（class missing）—— **非有效 Red**。改为：先建 V3 schema + JdbcSystemLogPort 空实现（方法体留空），断言"system_log 行数 = 1"会 0≠1 失败，属行为失败 | INSERT 1 行 + 字段映射 | TP6 (跨 capability) |
| TP5 | `JdbcSystemLogPortTest#record_jdbcThrows_doesNotPropagate` | 空实现抛 NullPointerException（未 catch） | 行为失败（异常类型不符 R6） | try/catch + SLF4J error log | 无 |

**说明**：本 change 选择"先 design 满足 gate option 1（lightweight-test-design.md），再实现完整 JdbcSystemLogPort，最后跑测试一次 Green"的策略。理由：
- JdbcSystemLogPort 是全新文件，无既有行为可破坏；
- spec R1-R6 已提供完整行为契约，测试方法的 assertion 不会因实现而漂移；
- "空实现 → Red → 填实现 → Green" 的形式化 Red 在本场景对收益微小（净增的"空实现 + 1 次提交 + 删空实现"步骤），但严格 TDD 形式无价值；
- 已在「Non-TDD Exceptions」备案理由 + 替代验证。

## E2E Scenarios

| Scenario | Persona / role | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| —— | —— | —— | —— | —— | —— | —— |

无 E2E。本 change 不交付前端，且 PRD §7.8 系统日志的"按操作时间倒序展示"在 MVP 阶段只在 lead 详情消费——本 change 范围外。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| JdbcSystemLogPort 主写入路径（TP1-TP4） | 全新文件无既有行为；spec R1-R5 提供 1:1 行为契约；形式化 Red（空实现 → 失败）净收益微小 | 写完 production 代码后立即跑 TP1-TP4，全部 Green；任意 1 项失败即回滚 | 极小：spec 与测试 1:1 映射，覆盖完整 |
| `Slf4jSystemLogPort` 类（保留作回滚兜底） | 行为零变化，仅 javadoc 更新 | code-review 确认 `@Component` 仍在；启动期 Spring 上下文加载该 bean（已被 @Primary 屏蔽默认注入） | 零 |
| tech-arch §13.2 表行替换 | doc-only 修订；无运行时影响 | code-review 检查 markdown 表格语法 + 引用 | 零 |
| QA 报告产物（5.2/5.3） | 文档型产物，覆盖证据汇总 | 自我审查 + 跑 `node scripts/qa_artifacts.mjs check` 校验模板（如脚本存在） | 零 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 本地 / CI 真 MySQL 8.4 可达（DB_HOST/DB_USER/DB_PASSWORD 环境变量） | TP1-TP7 全部 | 用户提供运行环境 / CI 已配置 | RESOLVED（auth-account 41/41 已证明环境可用） |
| 项目脚本 `scripts/qa_artifacts.mjs` | QA 模板校验（非阻塞） | —— | 项目当前无此脚本，跳过校验步骤 |

## Coverage Closure

- [x] Each in-scope executable test point has a coverage artifact after prerequisites are available.
- [ ] New or modified tests were executed and results were recorded. ——  待 apply 阶段 `mvn test` 后回填
- [ ] Red tests failed for the expected behavior reason when strict TDD applies. —— 见 Non-TDD Exceptions
- [x] Syntax, import, fixture, setup, or environment failures were not counted as valid Red evidence.
- [ ] Commands, reports, CI links, logs, screenshots, traces, or responses are recorded as execution evidence when relevant. —— QA 报告中回填
- [x] Behavioral evidence describes what assertion proved.
- [x] Coverage evidence maps each covered test point to a project-relative test path and optional `#testName`.
- [x] Uncovered test points and unresolved prerequisite blockers are listed explicitly. —— TP8 / TP9 静态审查、无 E2E
- [x] Requirement conflicts are resolved or explicitly listed as BLOCKED. —— 见 gate 表
- [x] Runtime QA validation, if performed, is treated only as availability smoke evidence and not counted as Unit/API/E2E business coverage. —— 任务 2.5（手工 PATCH 后 SELECT 验证）显式标注为 smoke

## Notes

- Uncovered test points: 无（TP1-TP7 由自动化覆盖；TP8/TP9 由 code-review + spec 反向约束）
- Remaining risks:
  - spec R6 的"业务先 commit、日志写失败时日志可丢" trade-off：design.md 已明文承认；生产可观测性依赖 SLF4J ERROR 行 + 运维补对账
  - `MultiTransactionalIntegrationTest` 的 `tablesToTruncate` 列表与各子测试耦合：本 change 需修改 `AdminAccountStatusControllerTest`，遗漏会导致跨测试脏数据（依赖测试失败暴露）
- Execution evidence: 待 apply 阶段回填 `mvn test` / `mvn verify` 输出
- Behavioral evidence: 见 Test Points 表 "Assertion target" 列
- Coverage evidence: 见 Test Points 表 "Coverage artifact" 列
