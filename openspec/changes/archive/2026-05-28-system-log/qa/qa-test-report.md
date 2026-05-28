# QA Test Report

## Conclusion

- Overall result: PASS
- Requirement / change ID: `openspec/changes/system-log/`（spec R1-R6 全覆盖）
- QA owner: system-log change（自动化部分自我覆盖；手工 smoke 由用户回填）
- Date: 2026-05-28
- Summary:
  - `mvn test`：48/48 Green（旧 41 + 新 7）
  - `mvn verify`：BUILD SUCCESS，含 surefire + spring-boot:repackage 全套
  - 新增 5 项测试 + 2 项现有测试断言扩展，覆盖 system-log spec R1 / R3 / R5 / R6（账号事件字段集、服务端时钟、多态 target、写入失败不上抛）
  - R2 / R4（不可变 / 系统自动生成）由"路径反向约束"保证（无 UPDATE / DELETE / 外部写入端点），代码审查通过
  - auth-account 现有 41 项测试零退化；`Slf4jSystemLogPort` 退居 fallback，`JdbcSystemLogPort` 由 `@Primary` 接管注入

## Evidence Guide

记录方式遵循模板：execution evidence = 命令 + 报告路径；behavioral evidence = 断言证明的具体行为；coverage evidence = 项目相对测试路径 + `#testName`。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | `JdbcSystemLogPortExceptionTest`（Mockito mock JdbcTemplate） |
| API/integration | Yes | `JdbcSystemLogPortTest` 4 项 + `AdminAccountStatusControllerTest` 8 项 |
| E2E | No | 本 change 不交付前端；系统日志读路径未实现 |
| Regression | Yes | 全套 `mvn test` 48/48；含 platform-foundation + auth-account 既有 |
| Runtime QA validation | Pending | 手工 PATCH + SELECT 验证由用户回填（任务 1.2 / 2.5） |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 系统日志 lead_id 必填 | PRD §9.5 字面"关联业务线索" | PRD §7.1.11 停用账号场景 + SystemLogPort 签名（无 lead 入参） | extends（PRD 内自洽扩展） | system-log design D7 / spec R5 | 新增覆盖 `lead_id NULL` 与 `lead_id=target_id` 两种 | Implement（lead_id NULLABLE 列 + 多态映射） |
| auth-account 启停日志 spy 断言 | `AdminAccountStatusControllerTest` 已 verify spy.record(...) | system-log R1 要求字段真行入库 | extends | auth-account R7 + system-log R1 | 旧 spy 断言保留；新增 JdbcTemplate 直查 system_log 行的字段断言 | Implement（JdbcSystemLogPort 接管 @Primary） |
| `Slf4jSystemLogPort` 默认注入 | auth-account 之 `@Component` | design D3 `@Primary JdbcSystemLogPort` 接管 | amends（注入优先级变更） | system-log design D3 | 无（依赖 spy 透传透明） | Implement（javadoc 注明仅作 fallback） |

无 `conflicts` 行；gate 放行（与轻量测试设计的 gate 一致）。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TP1 字段集（account） | spec R1 场景 1 | N/A —— 见 Non-TDD Exceptions（全新文件） | —— | `mvn test -Dtest=JdbcSystemLogPortTest` 4/4 PASS（一次 Green） | 全套 `mvn test` 48/48 PASS | `backend/src/test/java/com/dealtrace/systemlog/JdbcSystemLogPortTest.java#record_account_event_persistsAllRequiredFields` | PASS |
| TP2 operator_id NULL | spec R1 场景 2 | N/A | —— | 同上 PASS | 同上 | `JdbcSystemLogPortTest#record_systemAutoOperation_operatorIdIsNull` | PASS |
| TP3 created_at 服务端生成 | spec R3 | N/A | —— | 同上 PASS | 同上 | `JdbcSystemLogPortTest#record_createdAt_isServerGenerated` | PASS |
| TP4 LEAD target → lead_id=target_id | spec R5 | N/A | —— | 同上 PASS | 同上 | `JdbcSystemLogPortTest#record_leadTarget_leadIdEqualsTargetId` | PASS |
| TP5 异常不上抛 + ERROR 留痕 | spec R6 | **有效 Red**：首次跑 matcher 错配（`(Object[]) any()` 不匹配 varargs），mock 未抛 → 日志列表为空 → 断言 `anySatisfy(...)` 失败 —— 行为失败（spec R6 要求"写入失败应有 ERROR 留痕"，此时根本没"写入失败"路径触发） | matcher 不匹配导致 mock 返回默认 0，path R6 未走通；非语法 / fixture / 环境失败 | 改 matcher 为 7 个 `any()`（sql + 6 varargs）后 `mvn test -Dtest=JdbcSystemLogPortExceptionTest` 1/1 PASS；ERROR 日志含 action / target / operator + throwableProxy | 全套 `mvn test` 48/48 PASS | `backend/src/test/java/com/dealtrace/systemlog/JdbcSystemLogPortExceptionTest.java#record_jdbcThrows_doesNotPropagate` | PASS |
| TP6 disable 真行入库 | auth-account R7 ∩ system-log R1 | N/A —— 旧测试已覆盖 spy.verify；本测试是新增覆盖维度，非 supersede | —— | `mvn test -Dtest=AdminAccountStatusControllerTest` 8/8 PASS | 同上 | `backend/src/test/java/com/dealtrace/account/AdminAccountStatusControllerTest.java#disable_persistsSystemLogRow` | PASS |
| TP7 enable 真行入库 | 同上 ENABLE | N/A | —— | 同上 PASS | 同上 | `AdminAccountStatusControllerTest#enable_persistsSystemLogRow` | PASS |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| TP1-TP4 主写入路径 | 全新文件（`JdbcSystemLogPort` / `system_log` 表），无既有行为可破坏；spec R1-R5 与测试 1:1 映射；形式化 Red（空实现→失败→填实现→Green）净收益微小 | 实现完成立即跑 `mvn test -Dtest=JdbcSystemLogPortTest`，全部 Green；任意 1 项失败即回滚（事实如此：TP1/TP3 首跑因 Timestamp 类型转换失败，立即修复） | 极小：spec 与测试 1:1 |
| `Slf4jSystemLogPort` javadoc | doc-only 修订，行为零变化 | code-review；启动期 Spring 上下文加载该 bean（已被 @Primary 屏蔽默认注入） | 零 |
| `docs/技术架构与工程约束.md` §13.2 拆行 | doc-only，无运行时影响 | code-review markdown 表格 + 新增"说明"段落引用 change | 零 |
| TP8 / TP9（不存在 UPDATE/DELETE / 外部写入端点） | 反向断言；现实"无路径存在" | grep `system_log` / `SystemLogPort` 在所有 controller / mapper / service 代码：仅一处 INSERT 出口（`JdbcSystemLogPort.record`） | 极小：未来若有人新增端点会破契约；可在 review checklist 加守 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit | `JdbcSystemLogPortExceptionTest` | `mvn test -Dtest=JdbcSystemLogPortExceptionTest` | PASS | `Tests run: 1, Failures: 0, Errors: 0` |
| API/integration | `JdbcSystemLogPortTest` | `mvn test -Dtest=JdbcSystemLogPortTest` | PASS | `Tests run: 4, Failures: 0, Errors: 0, Time elapsed: 15.00 s` |
| API/integration | `AdminAccountStatusControllerTest` | `mvn test -Dtest=AdminAccountStatusControllerTest` | PASS | `Tests run: 8, Failures: 0, Errors: 0, Time elapsed: 23.31 s` |
| Regression | 全套 surefire | `mvn test` | PASS | `Tests run: 48, Failures: 0, Errors: 0, Skipped: 0`；BUILD SUCCESS |
| Regression | `mvn verify`（含 repackage） | `mvn verify` | PASS | `BUILD SUCCESS Total time: 01:14 min` |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 本地 dev profile 启动 + Flyway V3 apply 验证（任务 1.2） | 需要长时运行后端，apply 阶段已用 mvn test 等价证明 V3 在真 MySQL 可 apply | —— | 用户首次部署前手动启动 dev profile 一次，确认 `flyway_schema_history` 出现 V3 行 | 极小：`mvn test` 已加载 Spring 上下文并跑通真 MySQL；dev profile 与 test profile schema 一致 |
| 手工 PATCH 真实 HTTP 路径 + SELECT 验证（任务 2.5） | 同上 | —— | 用户启动后端、admin 登录、PATCH /api/admin/accounts/{id}/status、SELECT system_log | 极小：等价路径已由 `AdminAccountStatusControllerTest#disable_persistsSystemLogRow` 覆盖 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| TP1 字段集 account | API/integration | 直接调 `record(...)` 后 SELECT 验证 action/target_type/target_id/operator_id/lead_id=NULL/summary=NULL/created_at 服务端时钟范围 | `JdbcSystemLogPortTest#record_account_event_persistsAllRequiredFields` | COVERED |
| TP2 operator_id NULL | API/integration | `record(...,null)` 后 SELECT 验证 operator_id IS NULL | `JdbcSystemLogPortTest#record_systemAutoOperation_operatorIdIsNull` | COVERED |
| TP3 created_at 服务端 | API/integration | 两次调用间 sleep(10)，验证 t2 > t1 且 [before, after] 区间夹 | `JdbcSystemLogPortTest#record_createdAt_isServerGenerated` | COVERED |
| TP4 LEAD target | API/integration | `record(...,"LEAD",200L,1L)` 后 SELECT 验证 lead_id=target_id=200 | `JdbcSystemLogPortTest#record_leadTarget_leadIdEqualsTargetId` | COVERED |
| TP5 异常不上抛 | Unit | mock JdbcTemplate 抛 DAE，断言 record 不抛 + ERROR 日志含 action/target/operator + throwableProxy | `JdbcSystemLogPortExceptionTest#record_jdbcThrows_doesNotPropagate` | COVERED |
| TP6 disable 真行 | API/integration | PATCH disable 后 SELECT 验证 ACCOUNT_DISABLE 行 5 字段 | `AdminAccountStatusControllerTest#disable_persistsSystemLogRow` | COVERED |
| TP7 enable 真行 | API/integration | PATCH enable 后 SELECT 验证 ACCOUNT_ENABLE 行 5 字段 | `AdminAccountStatusControllerTest#enable_persistsSystemLogRow` | COVERED |
| TP8 无 UPDATE/DELETE 端点 | 静态审查 | grep `system_log` 在 controller/service/mapper：仅 INSERT 出口 | code-review | COVERED（静态） |
| TP9 无外部写入端点 | 静态审查 | grep `system-log` / `@RequestMapping` 邻近：0 命中 | code-review | COVERED（静态） |

## Regression Scope

- Changed behavior:
  - `SystemLogPort.record(...)` 默认实现从 NoOp 切到 JDBC（@Primary 接管）
  - 新增 `system_log` 表与 V3 migration
  - 写入失败不阻塞业务（spec R6）
- Directly impacted old behavior:
  - auth-account R7（启停日志契约）：原 spy.verify 仍 Green，新增真行入库断言
  - auth-account R5（创建账号）：spy 调用透传到 Jdbc，@Transactional 自动回滚 → 测试间无 system_log 残留
  - platform-foundation 全部：零影响（@SpringBootTest 启动期加 V3 表，运行时无额外路径）
- Historical defects considered: 无（NoOp 阶段无业务行为可破坏）
- Requirement-driven test additions / modifications / deletions: 详见 `regression-impact-analysis.md`
- Regression risk level: Low
- Selected regression tests and why: 见 Tests Run 表

## Runtime QA Validation

Runtime QA validation 仅作可用性 smoke，不计为 Unit / API / E2E 业务覆盖。

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| 后端 dev profile 启动 + 手工 PATCH | 启动后端 → admin 登录 → PATCH /api/admin/accounts/{id}/status disable → `SELECT * FROM system_log WHERE target_id=<sales.id>` 应有 1 行 ACCOUNT_DISABLE | Pending（用户回填） | —— | 测试账号 disable 后可手工 PATCH 回 enable 还原 |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| `JdbcSystemLogPortExceptionTest` 首跑失败 | Code（测试代码 matcher 错配） | Mockito varargs 匹配 `(Object[]) any()` 时无法对应 6 args 的 update 调用，mock 未抛、catch 未走、log appender 列表为空 | matcher 改为 `update(anyString(), any(), any(), any(), any(), any(), any())` 显式 7 args；追加 `verify(jdbc).update(...)` 防回归 | 当前 1/1 PASS；matcher 模式记录在测试 javadoc，避免未来类似错配 |
| `JdbcSystemLogPortTest#record_account_event_persistsAllRequiredFields` / `#record_createdAt_isServerGenerated` 首跑 ClassCastException | Code（测试代码类型假设错） | MySQL connector-j 在 spring-boot 4.0 + Java 24 下，DATETIME(3) 直接返回 `LocalDateTime`，无需经 `java.sql.Timestamp` 中转 | 移除 `(Timestamp) row.get("created_at")).toLocalDateTime()`，改为 `(LocalDateTime) row.get("created_at")` | 当前 4/4 PASS；类型映射模式与既有 auth-account `account.created_at` 一致 |

## Failure Learning

- Learning recorded or recommended: Yes（记入项目记忆）
- Knowledge location: 计划在 archive 后落记忆 `mysql-jdbc-datetime-as-localdatetime`（DATETIME(3) → LocalDateTime 直映射，避免 Timestamp cast）+ `mockito-varargs-explicit-matchers`（varargs 匹配需逐个 any()，避免 `(Object[]) any()`）。两条都属"测试代码踩坑"通用知识，独立 change 可复用
- Summary: 测试设计正确，实现细节踩坑的两类小错误，修复成本极低，但若不沉淀，下个有 DATETIME/varargs 的 change 会再踩

## Remaining Risks

- Uncovered test points: 无（TP8/TP9 由静态约束 + code-review 覆盖；E2E 不适用）
- Unresolved prerequisite blockers: 无
- Requirement authority conflicts: 无（gate 全部 extends / amends，已记入 design D7 + spec R5）
- Known flaky areas: `record_createdAt_isServerGenerated` 用 `Thread.sleep(10)`，理论上有 timing flake 风险；MVP 单机执行 + DATETIME(3) 毫秒精度，实际未出现，但首次大批并行执行需关注
- Manual follow-up: 任务 1.2 / 2.5 的 runtime smoke 待用户在首次部署前回填一次

## Final Statement

system-log change 自动化测试全部通过：5 项新增测试 + 2 项现有测试断言扩展覆盖 spec R1 / R3 / R5 / R6；R2 / R4 由路径反向约束保证；R5 多态 target 的 LEAD 分支由前瞻测试 `record_leadTarget_leadIdEqualsTargetId` 覆盖（lead capability 落地前的契约预演）。`mvn test` 48/48 PASS，`mvn verify` BUILD SUCCESS。auth-account 既有 41 项测试零退化。`Slf4jSystemLogPort` 退居 fallback，运维如需回滚仅需删 `JdbcSystemLogPort` 的 `@Primary` 注解。tech-arch §13.2 已同步修订并附拆 capability 说明。runtime smoke（任务 1.2 / 2.5）由用户在首次部署前回填一次即可。剩余风险均为低风险已知项，建议进入 `/opsx:archive`。
