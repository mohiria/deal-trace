# system-log-view Specification

## Purpose
TBD - created by archiving change view-system-log. Update Purpose after archive.
## Requirements
### Requirement: 线索维度系统日志读取与权限隔离

系统 SHALL 提供按线索读取其系统日志的端点，仅返回 `lead_id` 等于该线索的日志条目，并按 `created_at` 倒序排列。权限 SHALL 与进度跟踪读取对称：`ADMIN` 可读任意线索的日志；`SALES` 仅可读当前归属为自己的线索的日志。当线索不存在、或请求者为 `SALES` 且该线索不归属于自己（含公海与他人名下）时，系统 SHALL 统一返回 `NOT_FOUND`，**不**通过 `code` 或 HTTP 状态泄漏线索是否存在。

#### Scenario: ADMIN 读取任意线索日志按时间倒序

- **WHEN** `ADMIN` 请求某条已发生过多次事件的线索的系统日志
- **THEN** 系统返回该线索全部系统日志条目
- **AND** 条目按 `created_at` 严格倒序排列（最新在前）
- **AND** 仅包含 `lead_id` 等于该线索的条目，不含其他线索或 account 事件

#### Scenario: SALES 读取自己名下线索日志成功

- **WHEN** `SALES` 请求当前归属为自己的线索的系统日志
- **THEN** 系统返回该线索的系统日志条目，按 `created_at` 倒序

#### Scenario: SALES 读取非自己名下线索时不泄漏存在性

- **WHEN** `SALES` 请求一条归属为他人、或处于公海的线索的系统日志
- **THEN** 系统返回 `NOT_FOUND`
- **AND** 响应**不**包含任何日志条目
- **AND** 该响应与"线索不存在"在 `code` 与 HTTP 状态上不可区分

#### Scenario: 线索不存在返回 NOT_FOUND

- **WHEN** 任意角色请求一个不存在的线索 id 的系统日志
- **THEN** 系统返回 `NOT_FOUND`

### Requirement: 全局系统日志浏览仅限 ADMIN

系统 SHALL 提供全局系统日志浏览端点，挂载在受 `ROLE_ADMIN` 强制保护的路径下；该端点 SHALL 返回跨 `target_type`（含 `ACCOUNT` 与 `LEAD`）的系统日志，按 `created_at` 倒序分页，并支持按 `action` 与 `target_type` 可选筛选。`SALES` 或匿名请求者访问该端点时 SHALL 被拒绝（`FORBIDDEN`），且响应体**不**包含任何日志数据。

#### Scenario: ADMIN 分页倒序浏览全局日志含 account 事件

- **WHEN** `ADMIN` 请求全局系统日志的第一页
- **THEN** 系统返回按 `created_at` 倒序的一页日志条目
- **AND** 结果可包含 `target_type="ACCOUNT"`（`lead_id=NULL`）的账号事件与 `target_type="LEAD"` 的线索事件
- **AND** 响应包含分页信息以获取后续页

#### Scenario: 按 action 与 target_type 筛选

- **WHEN** `ADMIN` 以 `target_type="LEAD"` 且 `action="LEAD_WIN"` 请求全局系统日志
- **THEN** 系统仅返回满足该筛选条件的日志条目，仍按 `created_at` 倒序

#### Scenario: SALES 访问全局日志端点被拒

- **WHEN** `SALES` 访问全局系统日志端点
- **THEN** 系统返回 `FORBIDDEN`
- **AND** 响应体**不**包含任何系统日志数据

### Requirement: 日志展示信息按当前状态组装

系统在返回系统日志用于展示时 SHALL 组装如下派生信息，**不**依赖写侧 freetext 摘要的格式：操作人 `operator_id` 解析为该账号的**当前姓名**，`operator_id` 为 NULL 时展示为"系统"；归属相关引用（如 `fromOwnerId` / `toOwnerId`）解析为对应账号的**当前姓名**，空归属展示为"公海"；`action` 映射为人类可读的中文动作标签；合同金额按千分位格式组装为展示字符串。姓名 SHALL 按**当前**账号信息解析（非事件时刻冻结快照）。

#### Scenario: operator_id 为空时展示为系统

- **WHEN** 返回一条 `operator_id=NULL` 的系统日志（系统自动操作）
- **THEN** 该条目的操作人展示值为"系统"

#### Scenario: 归属变更日志解析为当前姓名

- **WHEN** 返回一条归属变更日志（如 `LEAD_TRANSFER`），其结构化 `detail` 含 `fromOwnerId` 与 `toOwnerId`
- **THEN** 原归属与新归属分别展示为对应账号的当前姓名
- **AND** 当某一侧为公海（无归属 id）时展示为"公海"

#### Scenario: 赢单金额按千分位展示

- **WHEN** 返回一条 `LEAD_WIN` 日志，其结构化 `detail` 含精确数值的合同金额
- **THEN** 金额展示字符串为千分位格式
- **AND** 金额数值不因展示丢失精度

#### Scenario: action 映射为中文标签

- **WHEN** 返回任一系统日志条目
- **THEN** 其 `action` 被映射为对应的中文动作标签（如 `LEAD_STAGE_CHANGE`→"阶段变更"）

### Requirement: 结构化 detail 与 freetext 摘要双路径渲染

系统读取系统日志时 SHALL 支持两类历史数据：当条目存在结构化 `detail` 时，按结构化字段组装展示；当条目 `detail` 为 NULL（结构化增补前写入的旧行）时，回退使用 freetext `summary` 作为展示内容。两类条目 SHALL 能在同一结果集中共存并按 `created_at` 倒序混排，读取不因旧行缺少 `detail` 而失败。

#### Scenario: 结构化条目按 detail 渲染

- **WHEN** 读取一条 `detail` 非空的系统日志
- **THEN** 展示信息由结构化 `detail` 字段组装（如阶段码、归属 id、金额、原因码）

#### Scenario: 旧行回退按 summary 渲染

- **WHEN** 读取一条 `detail=NULL` 的旧系统日志
- **THEN** 展示信息回退为该条目的 freetext `summary`
- **AND** 读取不抛错、不因缺少 `detail` 而丢弃该条目

#### Scenario: 新旧条目混排倒序

- **WHEN** 同一线索既有结构化新条目又有 freetext 旧条目
- **THEN** 两类条目在同一结果集中按 `created_at` 倒序统一排列

