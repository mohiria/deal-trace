## 1. 数据库迁移（design D1）

- [x] 1.1 新增 `backend/src/main/resources/db/migration/V3__system_log.sql`：按 design D1 表结构建 `system_log` 表（含 `idx_system_log_lead_created_at` / `idx_system_log_target` / `idx_system_log_operator_created_at` 三个索引）
- [x] 1.2 本地 dev profile 启动一次后端，确认 Flyway V3 顺利 apply 且无报错；`SHOW CREATE TABLE system_log` 字段顺序、类型、索引与 design D1 完全一致 —— **PASS**：`mvn spring-boot:run -Dspring-boot.run.profiles=local` 启动后 Flyway 报 `Current version of schema dealtrace: 3 / up to date`（V3 早在 mvn verify 时已 apply）；JDBC `SHOW CREATE TABLE system_log` 返回 7 列 + 3 索引（`idx_system_log_lead_created_at` / `idx_system_log_target` / `idx_system_log_operator_created_at`）、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`、`datetime(3)` 精度与 design D1 完全一致

## 2. JDBC 实现（design D2 / D3）

- [x] 2.1 新增 `backend/src/main/java/com/dealtrace/systemlog/JdbcSystemLogPort.java`：构造注入 `JdbcTemplate`，实现 `SystemLogPort.record(...)`；当 `targetType=="LEAD"` 时 `lead_id=targetId`，其余时刻 `lead_id=NULL`；`summary` 列固定 `NULL`（本 change 不消费）
- [x] 2.2 `JdbcSystemLogPort` 加 `@Primary @Component`；`created_at` 用 `LocalDateTime.now()` 在持久化时刻取值
- [x] 2.3 `JdbcSystemLogPort.record(...)` 内部 try / catch `RuntimeException`，捕获后调用 SLF4J `log.error(..., ex)` 记录 action / targetType / targetId / operatorId + 完整堆栈，**不**向上抛（满足 spec R6）
- [x] 2.4 `Slf4jSystemLogPort` 保留不删（仅去掉竞争干扰：如果运行时两个 bean 都存在但 NoOp 无 `@Primary` 则不会被注入；保留 `@Component` 即可作为 fallback）；在类 javadoc 注明 "JdbcSystemLogPort `@Primary` 后本类不会被注入，保留作单元测试轻量替身与回滚兜底"
- [x] 2.5 启动后端，调用 `PATCH /api/admin/accounts/{id}/status` 真实停用一个 Sales，`SELECT * FROM system_log` 应出现一行 `action='ACCOUNT_DISABLE'` —— **PASS**：admin@dealtrace.local 登录 → 创建 smoke-sales@dealtrace.local（sales.id=2）→ PATCH disable 返回 SUCCESS。JDBC SELECT 实际看到 2 行：`id=1 ACCOUNT_CREATE target=ACCOUNT/2 operator=1 lead_id=NULL summary=NULL` 与 `id=2 ACCOUNT_DISABLE target=ACCOUNT/2 operator=1 lead_id=NULL summary=NULL`，全部字段与 spec R1 / R5 完全吻合

## 3. 测试 —— 覆盖 spec R1/R3/R5/R6（design D4）

- [x] 3.1 新增 `backend/src/test/java/com/dealtrace/systemlog/JdbcSystemLogPortTest.java`，继承 `MultiTransactionalIntegrationTest`，`tablesToTruncate() = Set.of("system_log", "account")`
- [x] 3.2 测试 `record_account_event_persistsAllRequiredFields`（spec R1 场景 1）—— PASS
- [x] 3.3 测试 `record_systemAutoOperation_operatorIdIsNull`（spec R1 场景 2）—— PASS
- [x] 3.4 测试 `record_createdAt_isServerGenerated`（spec R3）—— PASS
- [x] 3.5 测试 `record_leadTarget_leadIdEqualsTargetId`（spec R5）—— PASS
- [x] 3.6 测试 `record_jdbcThrows_doesNotPropagate`（spec R6）—— PASS。**实施变更**：拆到 `JdbcSystemLogPortExceptionTest` 独立单元测试类，避免在 `@SpringBootTest` 里 mock `JdbcTemplate` 干扰基类 `MultiTransactionalIntegrationTest.truncateAfterEach` 的 TRUNCATE 操作；详见 QA 报告 Failure Analysis
- [x] 3.7 修改 `AdminAccountStatusControllerTest`：`tablesToTruncate()` 改为 `Set.of("account", "system_log")`；追加 `disable_persistsSystemLogRow` + `enable_persistsSystemLogRow` —— PASS
- [x] 3.8 跑 `mvn test`：48/48 Green（旧 41 + 新 7），`AdminAccountStatusControllerTest.@MockitoSpyBean SystemLogPort` 仍能监听调用（spy 包装 @Primary JdbcSystemLogPort，调用透传后落 DB）
- [x] 3.9 跑全量 `mvn verify`：BUILD SUCCESS（含 surefire + spring-boot:repackage），耗时 1m14s

## 4. tech-arch §13.2 修订（design D5）

- [x] 4.1 修改 `docs/技术架构与工程约束.md` §13.2 capability 表：把原 `| progress-log | 进度跟踪和系统日志行为。 |` 一行替换为 `| system-log | 系统自动产生的审计事件持久化、不可变契约、多态 target。 |` 与 `| progress-log | 进度跟踪（销售手动新增）的字段、规则与可见性。 |`，目录树同步加 `system-log/spec.md`
- [x] 4.2 在 capability 表尾部增加 "说明" 段落，注明拆分理由并引用 `openspec/changes/system-log/` 路径（archive 完成后该路径迁至 `archive/<date>-system-log/`，由 archive change 同步更新）

## 5. QA 产物（vibe-coding-qa）

- [x] 5.1 `openspec/changes/system-log/qa/lightweight-test-design.md`：覆盖 spec R1-R6、9 个测试点、TDD 候选 + Non-TDD 例外、prerequisite gate 全 RESOLVED
- [x] 5.2 `qa/regression-impact-analysis.md`：列出测试变更、影响分析、风险等级（Low）、选中回归测试与执行结果（全 PASS）
- [x] 5.3 `qa/qa-test-report.md`：Conclusion PASS，含 TDD 摘要、Failure Analysis（matcher 错配 + DATETIME 类型 cast 两笔小错误已修复并沉淀建议）、剩余风险（无）、Final Statement

## 6. 归档准备（不在 apply 内执行，留待 `/opsx:archive`）

- [ ] 6.1 用 `/opsx:archive system-log` 将本 change 移入 `openspec/changes/archive/<date>-system-log/` 并把 delta spec 升级到 `openspec/specs/system-log/spec.md`
- [ ] 6.2 archive 完成后，更新项目记忆：删除 `systemlog-port-noop.md`（债已偿），新增 `capability-system-log-progress-log-split.md`（拆 capability 决策）；可选追加 `mysql-jdbc-datetime-as-localdatetime` 与 `mockito-varargs-explicit-matchers` 两条踩坑沉淀
