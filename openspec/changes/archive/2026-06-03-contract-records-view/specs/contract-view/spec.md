## ADDED Requirements

### Requirement: 全局合同记录浏览仅限 ADMIN 全量

系统 SHALL 提供合同记录浏览端点，挂载在受 `ROLE_ADMIN` 强制保护的全局浏览路径下，返回**全部**合同记录，按合同记录创建时间 `created_at`（即赢单事件时刻）倒序分页。该端点 SHALL 支持以下可选筛选：成交销售 `dealSalesId`、签订日期区间 `signedDateFrom` / `signedDateTo`（闭区间，按业务日期 `signed_date` 过滤）、关键词 `keyword`（对客户名称或业务线索业务类型做包含匹配）。匿名请求者访问该端点时 SHALL 被拒绝（`UNAUTHORIZED`）。

#### Scenario: ADMIN 分页倒序浏览全部合同记录

- **WHEN** `ADMIN` 请求合同记录浏览端点的第一页
- **THEN** 系统返回按 `created_at` 严格倒序的一页合同记录（最新赢单在前）
- **AND** 结果覆盖全部成交销售名下及公海赢单（`deal_sales_id=NULL`）的合同记录
- **AND** 响应包含分页信息以获取后续页

#### Scenario: ADMIN 按成交销售筛选

- **WHEN** `ADMIN` 以 `dealSalesId=7` 请求合同记录
- **THEN** 系统仅返回 `deal_sales_id=7` 的合同记录，仍按 `created_at` 倒序

#### Scenario: ADMIN 按签订日期区间筛选

- **GIVEN** 存在 `signed_date` 分别为 `2026-04-30`、`2026-05-10`、`2026-06-01` 的合同记录
- **WHEN** `ADMIN` 以 `signedDateFrom=2026-05-01`、`signedDateTo=2026-05-31` 请求
- **THEN** 系统仅返回 `signed_date` 落在 `[2026-05-01, 2026-05-31]` 闭区间内的合同记录（即 `2026-05-10` 一条）

#### Scenario: ADMIN 按客户名称或业务类型关键词筛选

- **WHEN** `ADMIN` 以 `keyword` 请求合同记录
- **THEN** 系统仅返回其关联客户名称或关联线索业务类型包含该关键词的合同记录，仍按 `created_at` 倒序

### Requirement: SALES 仅可浏览自己成交的合同记录

系统 SHALL 为 `SALES` 提供合同记录浏览能力，但可见范围 SHALL 被强制收窄为 `deal_sales_id` 等于当前请求者本人的合同记录，与请求中传入的 `dealSalesId` 参数无关。`SALES` SHALL **不**能通过任何参数读取到他人成交或公海赢单（`deal_sales_id=NULL`）的合同记录。可见范围内的签订日期区间与关键词筛选 SHALL 同样生效。

#### Scenario: SALES 只看到自己成交的合同

- **GIVEN** 数据库存在 `deal_sales_id=7` 与 `deal_sales_id=8` 的合同记录，以及一条公海赢单（`deal_sales_id=NULL`）的合同记录
- **WHEN** `SALES`（账号 id=7）请求合同记录浏览端点
- **THEN** 系统仅返回 `deal_sales_id=7` 的合同记录
- **AND** 结果**不**包含 `deal_sales_id=8` 或 `deal_sales_id=NULL` 的任何合同记录

#### Scenario: SALES 传入他人 dealSalesId 仍被收窄为本人

- **WHEN** `SALES`（账号 id=7）以 `dealSalesId=8` 请求合同记录
- **THEN** 系统忽略该越权参数，仅返回 `deal_sales_id=7` 的合同记录
- **AND** 响应**不**包含 `deal_sales_id=8` 的任何合同记录

#### Scenario: SALES 在本人范围内按日期与关键词筛选

- **WHEN** `SALES`（账号 id=7）以 `signedDateFrom` / `signedDateTo` 或 `keyword` 请求
- **THEN** 系统在 `deal_sales_id=7` 的范围内再应用该筛选条件，按 `created_at` 倒序返回

### Requirement: 合同记录展示信息按当前状态组装

系统返回合同记录用于展示时 SHALL 组装如下派生信息：关联客户的**当前**名称、关联业务线索的业务类型、合同金额按千分位格式组装为展示字符串且数值不丢精度、签订日期 `signed_date`、赢单时间（合同记录 `created_at`）、成交销售解析为对应账号的**当前姓名**。成交销售 `deal_sales_id` 为 NULL（公海赢单）时 SHALL 展示为"公海赢单"而非空白或某个账号。姓名与客户名称 SHALL 按**当前**信息解析，非事件时刻冻结快照。

#### Scenario: 金额按千分位展示且不丢精度

- **WHEN** 返回一条 `contract_amount=120000.50` 的合同记录
- **THEN** 金额展示字符串为千分位格式（如 `120,000.50`）
- **AND** 金额数值不因展示丢失精度

#### Scenario: 公海赢单成交销售展示为公海赢单

- **WHEN** 返回一条 `deal_sales_id=NULL` 的合同记录
- **THEN** 该条目的成交销售展示为"公海赢单"

#### Scenario: 成交销售解析为当前姓名

- **WHEN** 返回一条 `deal_sales_id=7` 的合同记录，且账号 7 的姓名其后被修改
- **THEN** 成交销售展示为账号 7 的**当前**姓名

### Requirement: 合同记录浏览为纯只读

合同记录浏览能力 SHALL 仅提供读取，**不**暴露任何创建、编辑、删除合同记录的入口。合同记录仍仅由写侧赢单事务原子生成，浏览端点 SHALL 不改变任何合同记录、线索或账号数据。

#### Scenario: 浏览端点不提供写入

- **WHEN** 任意角色访问合同记录浏览能力
- **THEN** 仅能读取合同记录列表与分页，无任何创建/编辑/删除合同的操作
- **AND** 读取动作不改变数据库中任何合同记录、线索或账号
