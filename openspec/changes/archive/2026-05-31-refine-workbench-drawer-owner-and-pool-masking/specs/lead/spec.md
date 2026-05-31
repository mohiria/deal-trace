## MODIFIED Requirements

### Requirement: 业务线索字段集

业务线索（lead）SHALL 维护以下字段：关联客户 `customerId`、业务年度 `businessYear`、业务类型 `businessType`、联系人 `contactName`、联系电话 `contactPhone`、线索来源 `leadSource`（选填）、归属销售 `ownerSalesId`（可为空表示公海）、线索阶段 `stage`、最后跟踪时间 `lastTrackedAt`（由进度跟踪 capability 维护）、流失原因 `loseReason` 与流失说明 `loseNote`（由结束闭环维护）、创建时间 `createdAt`、赢单时间 `wonAt`、流失时间 `lostAt`（由结束闭环维护）。响应视图 SHALL 内联展示关联客户的 `customerName` 与 `customerUsci`，并 SHALL 内联展示归属销售姓名 `ownerSalesName`（当 `ownerSalesId` 为空即公海/无归属时，`ownerSalesName` 为 `null`；当归属销售存在时为该账号姓名），便于前端无需二次查询；视图 SHALL **不**直接返回进度跟踪列表与系统日志列表（由独立端点提供）。

业务类型 SHALL 限定为以下枚举之一：`BIM咨询` / `BIM培训` / `定制开发`。线索阶段 SHALL 限定为以下枚举之一：`未触达` / `初步沟通` / `方案报价` / `商务谈判` / `已赢单` / `已流失`；其中前 4 项为非结束阶段，后 2 项为结束阶段。

#### Scenario: 详情视图字段构成

- **WHEN** 已认证用户成功获取一条线索详情
- **THEN** 响应 `data` 包含上述全部字段（含 customerName / customerUsci 内联）
- **AND** 响应**不**包含 `progressLogs` 或 `systemLogs` 数组字段

#### Scenario: 有归属线索内联归属销售姓名

- **WHEN** 已认证用户获取一条 `ownerSalesId` 指向某存在销售账号的线索（详情或列表视图）
- **THEN** 响应该线索的 `ownerSalesName` 等于该销售账号的姓名
- **AND** `ownerSalesId` 与 `ownerSalesName` 同时呈现，前端无需再次查询账号即可显示姓名

#### Scenario: 公海/无归属线索归属姓名为空

- **WHEN** 已认证用户获取一条 `ownerSalesId` 为空（公海/无归属）的线索视图
- **THEN** 响应该线索的 `ownerSalesName` 为 `null`
- **AND** 前端据此呈现「公海」而非销售姓名
