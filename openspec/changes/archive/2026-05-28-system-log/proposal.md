## Why

auth-account change 已经通过 `SystemLogPort` 在创建账号、停用账号、启用账号三个时机调用了系统日志记录，但当前实现只是 `Slf4jSystemLogPort` NoOp——日志只进应用 SLF4J，不落 DB。这违反了 PRD §7.1.11「停用账号操作生成系统日志」的可审计意图，也让 auth-account 的"系统记录一条停用操作的系统日志"行为契约无法被真正验证。本 change 还掉这笔在 explore 阶段被记为 `systemlog-port-noop` 的技术债，让账号事件的系统日志真正落盘可查。

## What Changes

- 新增 `system_log` 表（V3 migration），结构同时兼容 account 事件与未来 lead 事件：`action / target_type / target_id / operator_id / lead_id (NULL) / summary (NULL) / created_at`。
- 新增 `JdbcSystemLogPort`，注册为 `SystemLogPort` 的 `@Primary` 实现，替换 auth-account 留下的 `Slf4jSystemLogPort` NoOp（NoOp 类保留作 fallback / 应用日志兜底，不强删）。
- 系统日志一经持久化即不可变：不暴露 UPDATE / DELETE API；时间戳由服务端生成，禁用客户端时钟。
- 不交付任何读 API、不交付任何 UI、不交付 ProgressLog 相关任何代码与 spec——这两块分别由未来的 lead capability 与 progress-log change 各自负责。
- 同步修订 `docs/技术架构与工程约束.md` §13.2 capability 表：把原合并的 `progress-log` 行拆为 `system-log` 与 `progress-log` 两行，命名诚实化。
- auth-account `AdminAccountStatusControllerTest` 等多事务集成测试的 `tablesToTruncate()` 需把 `system_log` 加进去，否则跨测试残留行。

## Capabilities

### New Capabilities

- `system-log`: 系统自动产生的审计事件持久化契约——append-only、服务端时钟、多态 target（account / 未来 lead）。**不**覆盖进度跟踪（ProgressLog 是销售手动写的、绑定 lead，归未来 `progress-log` capability）。

### Modified Capabilities

无。auth-account 的「状态变更触发系统日志记录」行为契约不变，仅其底层实现从 NoOp 切到 JDBC——这是 implementation detail，不引起 spec-level 行为变化。

## Impact

- **新增代码**：
  - `backend/src/main/resources/db/migration/V3__system_log.sql`
  - `backend/src/main/java/com/dealtrace/systemlog/JdbcSystemLogPort.java`
  - `backend/src/main/java/com/dealtrace/systemlog/SystemLogMapper.java`（或同等 MyBatis-Plus mapper）
  - `backend/src/main/java/com/dealtrace/systemlog/SystemLogEntity.java`
- **修改代码**：
  - `backend/src/main/java/com/dealtrace/systemlog/Slf4jSystemLogPort.java`：去掉 `@Component`，或加 `@ConditionalOnMissingBean(SystemLogPort.class)`，让 JdbcSystemLogPort 优先
  - `backend/src/test/java/com/dealtrace/account/AdminAccountStatusControllerTest.java` 等多事务测试基类的 `tablesToTruncate()`：补 `system_log`
- **文档**：
  - `docs/技术架构与工程约束.md` §13.2 capability 表拆行
- **依赖 / 库**：无新增（MyBatis-Plus、Flyway、真 MySQL 8.4 已就位）
- **API**：无新增端点
- **前端**：无影响
- **数据库**：新增 1 张表 `system_log`，无 schema 修改
- **回滚**：删除 V3 migration + 回滚 JdbcSystemLogPort + 恢复 `@Component` 在 Slf4jSystemLogPort 即可，auth-account 行为不受影响（系统日志退化为只进 SLF4J）
