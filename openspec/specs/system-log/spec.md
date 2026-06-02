# system-log Specification

## Purpose
TBD - created by archiving change system-log. Update Purpose after archive.
## Requirements
### Requirement: 系统日志的最小字段集

系统日志条目 SHALL 至少持久化以下字段：操作动作标识 `action`、操作目标类型 `target_type`、操作目标主键 `target_id`、操作人账号 id `operator_id`（系统自动操作无具体操作人时为 NULL）、记录时间戳 `created_at`；并 MAY 持久化可选字段：关联业务线索 id `lead_id`、操作摘要 `summary`。

系统日志 SHALL **不**持久化以下字段：原始密码、密码哈希、访问令牌、HTTP 请求头明文。

#### Scenario: account 类事件持久化后字段完整

- **WHEN** 后端业务代码触发记录一条 `action="ACCOUNT_DISABLE"`、`target_type="ACCOUNT"`、`target_id=<accountId>`、`operator_id=<adminId>` 的系统日志
- **THEN** 系统日志存储区新增**恰好一条**记录
- **AND** 新记录的 `action` / `target_type` / `target_id` / `operator_id` 与触发参数完全一致
- **AND** 新记录的 `created_at` 为服务端在持久化时刻的时钟值
- **AND** 新记录**不**包含密码、密码哈希、令牌或类似敏感字段

#### Scenario: operator_id 可为 NULL 用于系统自动操作

- **WHEN** 后端业务代码触发记录 `operator_id=null` 的系统日志（例如初始 Admin 注入、定时任务等系统主动行为）
- **THEN** 持久化记录的 `operator_id` 字段为 NULL
- **AND** 其余必填字段（action / target_type / target_id / created_at）仍按规则填充

### Requirement: 系统日志一经持久化不可变

系统日志条目 SHALL 一经成功持久化即为只读。系统 SHALL **不**对外暴露任何修改或删除已存在系统日志条目的业务端点；Admin 与 Sales 均无修改 / 删除权限；如需纠正只能通过追加新条目实现。MVP 阶段无归档与清理动作。

#### Scenario: 不存在修改或删除系统日志的业务端点

- **WHEN** 任意请求者（Admin / Sales / 匿名）通过 HTTP API 试图修改或删除任一已存在的系统日志条目
- **THEN** 系统**未**提供能够 UPDATE 或 DELETE system log 行的业务端点
- **AND** 已持久化的系统日志条目的全部字段在其生命周期内保持不变

### Requirement: 系统日志时间戳由服务端生成

系统日志条目的 `created_at` SHALL 由后端在持久化时刻基于服务端时钟生成。系统 SHALL **不**接受调用方（业务代码、HTTP 客户端、测试夹具）传入或覆盖 `created_at`。

#### Scenario: 调用方无法注入 created_at

- **WHEN** 任意业务路径触发记录系统日志
- **THEN** 持久化记录的 `created_at` 值由后端在 INSERT 时刻产生，**不**等于任何调用方传入值
- **AND** 系统日志的记录接口签名上**不**接受 `created_at` 入参

### Requirement: 系统日志仅由系统自动生成

系统日志 SHALL 由后端代码在业务事件处理流程内部触发；系统 SHALL **不**对外暴露任何让外部调用者（API 客户端、Admin、Sales）主动写入系统日志的端点或参数。

#### Scenario: 不存在外部写入系统日志的端点

- **WHEN** 任意 HTTP 客户端以任意角色、任意方式尝试直接向系统日志存储写入条目
- **THEN** 系统**未**对外暴露此类端点
- **AND** 系统日志只能由后端业务事件处理代码在内部触发

### Requirement: 系统日志支持多态 target

系统日志条目的 `target_type` SHALL 支持区分账号事件与业务线索事件：
- 当事件作用于账号时（如停用、启用、创建账号），`target_type="ACCOUNT"` 且 `lead_id` SHALL 为 NULL；
- 当事件作用于业务线索时（如创建线索、认领、阶段变更等，由未来 lead capability 触发），`target_type="LEAD"` 且 `lead_id` SHALL 等于 `target_id`。

未来扩展新的 target 类型 SHALL 沿用相同模式：`target_type` 取该类型的字符串标识，`target_id` 取该类型主键，`lead_id` 仅在事件确与某条线索关联时填充。

#### Scenario: account 事件不关联 lead

- **WHEN** 后端业务代码触发记录 `target_type="ACCOUNT"` 的系统日志
- **THEN** 持久化记录的 `lead_id` 为 NULL
- **AND** 持久化记录的 `target_id` 等于该账号 id

### Requirement: 系统日志写入失败不阻塞业务主流程

系统日志的持久化失败 SHALL **不**导致触发它的业务事务回滚或对外返回业务错误。当系统日志写入异常时，系统 SHALL 在应用日志（SLF4J）中记录该异常的完整上下文（含 action / target / operator），便于运维事后追溯，但**不**通过 API 响应外泄异常细节。

#### Scenario: 系统日志写入异常时业务返回成功

- **GIVEN** 系统日志存储层抛出运行时异常（如 DB 临时不可达）
- **WHEN** 后端业务代码完成核心业务操作（如成功停用账号）后触发记录系统日志
- **THEN** 该业务 API 仍返回成功信封（`code=SUCCESS`）
- **AND** 业务事务**不**因系统日志写入失败而回滚
- **AND** 服务端 SLF4J 日志中记录系统日志写入失败的完整上下文（action / target_type / target_id / operator_id + 异常堆栈）
- **AND** API 响应体**不**包含任何系统日志相关异常细节

### Requirement: 系统日志关键变更以结构化 detail 持久化

系统日志条目 SHALL 在最小字段集与可选 `summary` 之外，MAY 持久化一个结构化载荷 `detail`，用于承载该事件的关键变更引用，供读出侧组装展示。`detail` SHALL 以**稳定引用**而非展示字符串持久化：归属相关变更 SHALL 存账号主键 id（如 `fromOwnerId` / `toOwnerId`，公海以空表示），**不**得存邮箱或姓名等可变展示值；阶段变更 SHALL 存原/新阶段枚举码；赢单 SHALL 存精确数值类型的合同金额（**不**使用浮点）与签订日期；流失 SHALL 存流失原因枚举码与流失说明。`detail` 中 SHALL **不**包含密码、密码哈希、令牌等敏感字段。

各业务事件（`LEAD_CREATE` / `LEAD_CLAIM` / `LEAD_RELEASE` / `LEAD_ASSIGN` / `LEAD_RECALL` / `LEAD_TRANSFER` / `LEAD_STAGE_CHANGE` / `LEAD_WIN` / `LEAD_LOSE` / `ACCOUNT_CREATE` / `ACCOUNT_ENABLE` / `ACCOUNT_DISABLE`）在触发系统日志时 SHALL 填充与其语义对应的结构化 `detail`。

#### Scenario: 归属变更事件以账号 id 持久化归属引用

- **WHEN** 后端触发记录一条 `LEAD_TRANSFER` 系统日志，原归属与新归属均为具体 `SALES`
- **THEN** 持久化记录的 `detail` 含原归属与新归属的**账号主键 id**
- **AND** `detail` **不**以邮箱或姓名等可变展示值持久化归属

#### Scenario: 阶段变更事件以枚举码持久化原/新阶段

- **WHEN** 后端触发记录一条 `LEAD_STAGE_CHANGE` 系统日志
- **THEN** 持久化记录的 `detail` 含原阶段与新阶段的枚举码

#### Scenario: 赢单事件以精确数值持久化金额

- **WHEN** 后端触发记录一条 `LEAD_WIN` 系统日志
- **THEN** 持久化记录的 `detail` 含合同金额（精确数值类型，非浮点）与签订日期
- **AND** `detail` 中金额保留两位小数不丢精度

### Requirement: 系统日志 detail 可空且非破坏演进

系统日志的结构化 `detail` SHALL 为可空字段：在结构化增补之前写入的历史条目 `detail` 为 NULL 属合法状态，系统读出侧 SHALL 能正常处理并回退到 freetext `summary`。`detail` 的引入 SHALL **不**破坏既有写入路径——既有最小字段集、不可变、服务端时间戳、仅系统生成、多态 target、写失败不阻塞业务主流程等要求均保持不变。

#### Scenario: 历史条目 detail 为空属合法

- **WHEN** 系统存在结构化增补之前写入的系统日志条目
- **THEN** 该条目的 `detail` 为 NULL 不视为数据损坏
- **AND** 该条目的最小字段集（action / target_type / target_id / created_at）仍完整有效

#### Scenario: detail 写入不改变写失败不阻塞语义

- **WHEN** 记录含 `detail` 的系统日志时持久化层抛出异常
- **THEN** 触发该日志的业务事务**不**回滚，业务 API 仍返回成功信封
- **AND** 服务端 SLF4J 记录写入失败的完整上下文

