## ADDED Requirements

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
