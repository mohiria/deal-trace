## Context

auth-account change 已落地完整的 SystemLogPort 抽象：

- `com.dealtrace.systemlog.SystemLogPort` 接口：`record(String action, String targetType, Long targetId, Long operatorId)`
- `com.dealtrace.systemlog.Slf4jSystemLogPort` NoOp 实现（`@Component`），仅写应用 SLF4J、不落盘
- `AdminAccountController` 三处调用：`ACCOUNT_CREATE` / `ACCOUNT_DISABLE` / `ACCOUNT_ENABLE`，target_type 统一为 `"ACCOUNT"`
- `AdminBootstrapListener` 注入初始 Admin 时**未**调用 `record()`（auth-account spec 未要求；本 change 不补，避免越权扩展上一个 change 的契约）
- `AdminAccountStatusControllerTest` 用 `@MockitoSpyBean SystemLogPort` 监听调用次数与参数

PRD 与 tech-arch 的相关锚点：

- PRD §7.1.11「停用账号操作生成系统日志」、PRD §7.8 / §9.5（系统日志字段、规则）、PRD §8.5（系统日志与进度跟踪是两个独立对象）
- tech-arch §13.2 将 `progress-log` 一个 capability 同时覆盖进度跟踪与系统日志——本 change 的 explore 阶段决议拆开，命名诚实化

项目记忆 `systemlog-port-noop` 已记录"NoOp 必须由 progress-log change 替换"的债务，本 change 是其偿付方。

## Goals / Non-Goals

**Goals:**

- 落地 `system_log` 表，schema 一次性兼容 account 与未来 lead 两类事件
- 引入 `JdbcSystemLogPort` 作为 `SystemLogPort` 的 `@Primary` 实现，替换 NoOp 默认 bean
- 保持 `SystemLogPort` 接口签名**不变**，auth-account 业务代码零修改
- 持久化失败时业务主流程不回滚（spec R6 行为契约）
- 同步修订 tech-arch §13.2 capability 表，把合并的 `progress-log` 行拆为 `system-log` + `progress-log` 两行
- 扩展 `AdminAccountStatusControllerTest` 等多事务测试基类的 `tablesToTruncate()`，避免 system_log 行跨测试残留

**Non-Goals:**

- 任何系统日志的读 API（无消费者，YAGNI；将来 lead 详情 / Admin 审计页落地时再随该 change 一起补）
- 进度跟踪（ProgressLog）的任何代码、表、spec（属未来 `progress-log` capability，强依赖 lead）
- Lead 事件的系统日志触发点（如 LEAD_CREATE / LEAD_CLAIM / ...）——属未来 lead capability，本 change 仅把"表能装"做好
- 系统日志归档 / 分区 / 清理（MVP 流量级别下没必要）
- AdminBootstrapListener 补记 SYSTEM_BOOTSTRAP_ADMIN_CREATE（auth-account spec 未要求；不在本 change 越权扩展）
- 异步化系统日志写入（同步 INSERT 足够；MVP 流量极低）

## Decisions

### D1：system_log 表 schema —— 一次性兼容 account / 未来 lead 事件

```sql
-- V3__system_log.sql
CREATE TABLE system_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    action      VARCHAR(64)  NOT NULL,           -- 如 'ACCOUNT_DISABLE' / 'LEAD_CLAIM'
    target_type VARCHAR(16)  NOT NULL,           -- 'ACCOUNT' / 'LEAD' / ...
    target_id   BIGINT       NOT NULL,           -- 多态：account.id 或 lead.id
    operator_id BIGINT       NULL,               -- 操作 account.id，系统行为为 NULL
    lead_id     BIGINT       NULL,               -- 仅 lead 事件填，account 事件 NULL
    summary     VARCHAR(512) NULL,               -- PRD §7.8.6 「关键变更摘要」预留
    created_at  DATETIME(3)  NOT NULL,           -- 服务端时钟生成
    PRIMARY KEY (id),
    KEY idx_system_log_lead_created_at (lead_id, created_at),
    KEY idx_system_log_target (target_type, target_id, created_at),
    KEY idx_system_log_operator_created_at (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

| 设计点 | 选择 | 理由 |
| :--- | :--- | :--- |
| `id` | BIGINT AUTO_INCREMENT | 与 account 一致；MVP 无分布式写入，无需 UUID |
| `action` | VARCHAR(64) | 字符串枚举；新增 action 不用 ALTER；64 字符足够最长动作标识 |
| `target_type` | VARCHAR(16) | 同 role / status 模式 |
| `target_id` | BIGINT NOT NULL | 必填；polymorphic FK 不通过 DB 约束（多态不能 FK） |
| `operator_id` | BIGINT NULL **无 FK** | 不加 FK 是为了 account 被删后日志仍可追溯（MVP 不删账号，但留余地）；语义上是逻辑外键 |
| `lead_id` | BIGINT NULL **无 FK** | 同上理由；lead capability 落地后查询用 `idx_system_log_lead_created_at` 走线索时间线 |
| `summary` | VARCHAR(512) NULL | 容纳 PRD §7.8.6/8/9/10 要求的"关键变更摘要"；account 事件不填 |
| `created_at` | DATETIME(3) NOT NULL | 毫秒精度，与 account.created_at 一致 |
| **三个索引** | lead 时间线 / target 维度查询 / operator 维度查询 | 覆盖 PRD §7.8.4「按操作时间倒序展示」与 Admin 审计的最常见查询轴 |

**关于 FK**：MySQL 不支持 polymorphic FK，且本表写多读少（写在每个业务事件，读只在 lead 详情或 Admin 审计），加 FK 反而会让删账号 / 删线索（MVP 都没有）需要级联策略；干脆不加，靠应用层保证。

### D2：JDBC 实现方式 —— 裸 JdbcTemplate，不引 MyBatis-Plus mapper

| 维度 | 选择 | 备选 | 理由 |
| :--- | :--- | :--- | :--- |
| 实现 | `JdbcTemplate` 直接 INSERT | MP `BaseMapper<SystemLog>` + Entity 类 | 写路径单一（仅 INSERT，无 UPDATE / DELETE / 复杂查询），MP 提供的 CRUD 大部分用不上；少一个 Entity + Mapper 文件 + MP 配置面 |
| 事务参与 | 不显式 `@Transactional`，让调用方事务传播 | 独立事务 `REQUIRES_NEW` | 系统日志要与业务事件**同事务**——业务回滚时日志也回滚，避免出现"业务失败但日志说成功了"的对账难题；与 D3 失败处理协同 |
| 异常处理 | 在 `JdbcSystemLogPort` 内 catch + SLF4J 记录 + 不抛出 | 抛出让上层处理 | 满足 spec R6「写入失败不阻塞业务」契约——业务 controller 不感知 |

**取舍**：catch + 不抛 的代价是失去事务原子性的优势——业务先 commit、日志写失败时业务已不可回。但 spec R6 已明确选择"业务优先"，且 MVP 流量下 system_log 不应是 hotspot，DB 异常概率极低。

代码骨架（示意，spec 已禁实现细节）：

```java
@Primary
@Component
public class JdbcSystemLogPort implements SystemLogPort {
    private final JdbcTemplate jdbc;
    private static final Logger log = LoggerFactory.getLogger(JdbcSystemLogPort.class);

    public JdbcSystemLogPort(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(String action, String targetType, Long targetId, Long operatorId) {
        Long leadId = "LEAD".equals(targetType) ? targetId : null;
        try {
            jdbc.update(
                "INSERT INTO system_log (action, target_type, target_id, operator_id, lead_id, summary, created_at) " +
                "VALUES (?, ?, ?, ?, ?, NULL, ?)",
                action, targetType, targetId, operatorId, leadId, LocalDateTime.now()
            );
        } catch (RuntimeException ex) {
            log.error("[systemlog] persist failed action={} targetType={} targetId={} operatorId={}",
                action, targetType, targetId, operatorId, ex);
        }
    }
}
```

### D3：bean 替换策略 —— JdbcSystemLogPort 加 @Primary，NoOp 不动

| 选择 | 备选 | 理由 |
| :--- | :--- | :--- |
| `JdbcSystemLogPort` 加 `@Primary @Component` | NoOp 加 `@ConditionalOnMissingBean(SystemLogPort.class)` | `@Primary` 更显式：上下文里两个 bean 都在，由 `@Primary` 决定注入哪个；阅读代码时一眼可见 |
| **不**删 `Slf4jSystemLogPort` | 删了 | 保留作 fallback / 单元测试的轻量替身；删了等于丢一个有用的开发工具 |
| 在 `JdbcSystemLogPort` 内**不**调用 NoOp 做双写 | 同时写 DB 与 SLF4J 双轨 | 重复噪声；DB 已是权威，SLF4J 留给写失败兜底 |

**回滚路径**：如生产事故需暂时退回 NoOp，删 `JdbcSystemLogPort` 的 `@Primary` 即可（NoOp 自动顶上）。

### D4：测试基类与现有 auth-account 测试的兼容

- `MultiTransactionalIntegrationTest` 是抽象基类，`tablesToTruncate()` 由子类返回——本 change**不**修改基类。
- `AdminAccountStatusControllerTest` 当前 `tablesToTruncate()` 仅含 `account`；本 change 任务里**必须**改为 `Set.of("account", "system_log")`，否则集成测试残留行污染后续测试。
- `AdminAccountStatusControllerTest` 用 `@MockitoSpyBean SystemLogPort` 监听调用——替换 bean 后，spy 包装的是 `JdbcSystemLogPort`，每次 PATCH 会**真实写入** system_log 表。原 spy 仅验证调用次数与参数，不验证 DB 行——本 change 任务里**追加**一个测试方法直接查 system_log 表（用 `JdbcTemplate.queryForList`），验证持久化字段（spec R1 的 account 场景）。
- 新建 `JdbcSystemLogPortTest`（继承 `MultiTransactionalIntegrationTest`，`tablesToTruncate() = Set.of("system_log")`）覆盖：
  1. account 事件写入字段完整（R1）
  2. operator_id=null 系统行为（R1 第二场景）
  3. created_at 服务端生成（R3）
  4. 多态 target_type="LEAD" 时 lead_id 与 target_id 一致（R5）
  5. 写入抛 RuntimeException 时不上抛、业务事务不回滚（R6）—— 用 Mock JdbcTemplate spy 触发异常

### D5：tech-arch §13.2 修订

将原表内 `| progress-log | 进度跟踪和系统日志行为。 |` 一行替换为：

```
| `system-log` | 系统自动产生的审计事件持久化、不可变契约、多态 target。 |
| `progress-log` | 进度跟踪（销售手动新增）的字段、规则与可见性。 |
```

修订时机：在本 change 的 tasks 中作为一项独立任务执行，与代码改动同批提交，避免 doc 与 spec 脱节。

### D6：spec.md 的契约视角 —— 锚定 SystemLogPort 接口语义，不锁实现

spec.md 行为契约描述的"系统日志条目"是逻辑实体，**不**绑定特定表名或字段名（虽然 design 选择了 `system_log` 表 + 七列）。这样：
- 未来若拆 lead-only / account-only 双表（不太可能但保留余地），spec 不必改
- 测试可以同时验证"port 调用参数 → 持久化字段"的映射，而不只验证"INSERT 了一行"

### D7：summary 字段先建空，account 事件不填

PRD §7.8.6 要求"阶段变更、归属变更、赢单、流失等操作需记录关键变更摘要"——这些都是 lead 事件，account 事件不需要摘要。

| 选择 | 备选 | 理由 |
| :--- | :--- | :--- |
| 现在就建 summary VARCHAR(512) NULL | lead change 时再 ALTER | ALTER 大表代价远高于多一列 NULL；MVP 表小，列闲置成本可忽略 |
| 不在 port 签名加 summary 参数 | 加 `record(..., String summary)` 重载 | account 事件用不到；lead change 落地时**才**扩展 port 签名 + lead-specific 实现，保持本 change 接口稳定 |

### D8：不补 Bootstrap admin 创建的系统日志

auth-account spec 的「Requirement: 初始 Admin 由部署配置注入」未要求记日志；本 change 仅做底层持久化，**不**越权扩展上一个 change 的行为契约。如未来 audit 合规要求"启动注入要留痕"，再开独立 change 修订 auth-account spec。

## Risks / Trade-offs

- **业务先 commit、日志写失败时日志缺失**：spec R6 已明确"业务优先"。**缓解**：失败上下文进 SLF4J（含 action / target / operator）；运维可基于应用日志补对账；MVP 数据量小，发生时人工补录可行。
- **多态 target 无 FK 约束**：`target_id` 指向 account 或 lead 但 DB 不验证。**缓解**：写入路径只在后端业务代码内部触发（spec R4），调用方必然在持有合法 id 的上下文中；后续如需强约束可加触发器，但 MVP 不必。
- **`lead_id` 列在本 change 仅 NULL**：未来 lead capability 落地前，`idx_system_log_lead_created_at` 索引全是 NULL 值——浪费一点空间。**缓解**：MyISAM / InnoDB 的 NULL 不占索引行（实际叶节点不存 NULL），影响可忽略。
- **替换 NoOp 后 auth-account 集成测试需更新 truncate 列表**：忘了改会导致测试间脏数据。**缓解**：已列为本 change 必做任务（D4）；CI 会因 `count(*) from system_log` 断言失败而暴露遗漏。
- **`@Primary` 在生产与测试上下文行为一致**：未引入 profile 切分。**缓解**：测试用真 MySQL（tech-arch §12），与生产同行为；spy bean 需 `@MockitoSpyBean` 包装 `@Primary` bean，已验证 Spring Boot 4.0.6 + spring-security 7 上下文支持此组合。
- **spec R6 与"完整审计"的张力**：业务成功但日志缺失，违反"任何操作都可审计"的直觉。**缓解**：spec 明文采纳"业务优先"，文档化此 trade-off；若未来合规要求强一致，可改为同事务 + 失败回滚 + 引入幂等重试。

## Migration Plan

1. Flyway V3 自动执行 `V3__system_log.sql`（应用启动时）。
2. `JdbcSystemLogPort` 加 `@Primary`，下次部署生效；NoOp 退居 fallback。
3. 无数据迁移（system_log 是新表）。
4. **回滚**：删 `@Primary`、删 `JdbcSystemLogPort` / 删 V3 migration 行（实际不会回滚 schema，只回滚 bean 即可让 NoOp 顶上）。auth-account 业务零感知。
