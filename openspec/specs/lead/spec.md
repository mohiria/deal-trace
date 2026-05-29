# lead Specification

## Purpose
TBD - created by archiving change lead-core. Update Purpose after archive.
## Requirements
### Requirement: 业务线索字段集

业务线索（lead）SHALL 维护以下字段：关联客户 `customerId`、业务年度 `businessYear`、业务类型 `businessType`、联系人 `contactName`、联系电话 `contactPhone`、线索来源 `leadSource`（选填）、归属销售 `ownerSalesId`（可为空表示公海）、线索阶段 `stage`、最后跟踪时间 `lastTrackedAt`（由进度跟踪 capability 维护）、流失原因 `loseReason` 与流失说明 `loseNote`（由结束闭环维护）、创建时间 `createdAt`、赢单时间 `wonAt`、流失时间 `lostAt`（由结束闭环维护）。响应视图 SHALL 内联展示关联客户的 `customerName` 与 `customerUsci` 便于前端无需二次查询；视图 SHALL **不**直接返回进度跟踪列表与系统日志列表（由独立端点提供）。

业务类型 SHALL 限定为以下枚举之一：`BIM咨询` / `BIM培训` / `定制开发`。线索阶段 SHALL 限定为以下枚举之一：`未触达` / `初步沟通` / `方案报价` / `商务谈判` / `已赢单` / `已流失`；其中前 4 项为非结束阶段，后 2 项为结束阶段。

#### Scenario: 详情视图字段构成

- **WHEN** 已认证用户成功获取一条线索详情
- **THEN** 响应 `data` 包含上述全部字段（含 customerName / customerUsci 内联）
- **AND** 响应**不**包含 `progressLogs` 或 `systemLogs` 数组字段

### Requirement: 业务年度由服务端在创建时生成

线索的 `businessYear` SHALL 由后端在线索持久化时刻取服务端时钟的年份生成；系统 SHALL **不**接受调用方（HTTP 客户端 / 测试夹具 / 业务代码）传入或覆盖 `businessYear`。本字段一经写入 SHALL 保持不变（MVP 阶段无任何路径修改既有线索的 businessYear）。

#### Scenario: 创建线索时 businessYear 取服务端当前年份

- **WHEN** 已认证用户在 2026 年提交创建线索请求
- **THEN** 持久化记录的 `businessYear` 字段值为 `2026`
- **AND** 请求体即使携带 `businessYear` 字段，该值**不**生效

### Requirement: 创建线索必填字段与格式校验

创建线索 SHALL 校验以下字段必填：`customerId`（必须指向既有客户）、`businessType`（必须为合法枚举值）、`contactName`（trim 后非空）、`contactPhone`（trim 后非空且符合中国大陆 11 位手机号或常见座机格式）。`leadSource` 为选填，空字符串与 null 视为未填。任一必填项缺失或格式错误 SHALL 返回 `VALIDATION_ERROR`，`message` 指明字段名或语义；数据库 SHALL **不**新增行。

联系电话校验规则：
- 11 位手机号：第 1 位为 `1`、第 2 位为 `3-9`、其余 9 位为数字
- 座机：可选区号（`0` 开头 3-4 位数字）+ 7-8 位号码 + 可选分机号；如 `010-12345678`、`0571-12345678`、`0571-12345678-123`
- 海外号码格式 MVP 阶段不支持

#### Scenario: 缺失 customerId 或客户不存在拒绝创建

- **WHEN** 已认证用户提交缺失 `customerId` 或 `customerId` 对应客户不存在的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 数据库**未**新增线索行

#### Scenario: 非法 businessType 枚举值拒绝创建

- **WHEN** 已认证用户提交 `businessType` 非 `BIM咨询` / `BIM培训` / `定制开发` 三者之一的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 数据库**未**新增线索行

#### Scenario: 联系电话格式非法拒绝创建

- **WHEN** 已认证用户提交 `contactPhone` 既不符合 11 位手机号也不符合座机格式的创建请求（如 `"abc"` / `"123"` / `"+1-555-1234"`）
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 数据库**未**新增线索行

#### Scenario: 合法手机号或座机通过校验

- **WHEN** 已认证用户提交 `contactPhone="13812345678"`、`contactPhone="010-12345678"`、`contactPhone="0571-12345678-123"` 之一的创建请求（其他字段合法）
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库新增一行，contact_phone 字段值与提交值一致

#### Scenario: 缺失 contactName 或全空白拒绝创建

- **WHEN** 已认证用户提交 `contactName=""` 或 `contactName="   "` 的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`

### Requirement: Admin 与 Sales 的创建归属规则

系统 SHALL 根据创建线索调用者的角色应用不同的归属规则：

- 调用者为 **ADMIN** 时：请求体 `ownerSalesId` SHALL 可为：(a) 任意 `ENABLED` 状态 Sales 账号的 id（指定归属）、(b) `null` 或字段缺失（进入公海）。若 `ownerSalesId` 非 null 且对应账号不存在、非 SALES 角色或处于 `DISABLED` 状态 SHALL 返回 `VALIDATION_ERROR`。
- 调用者为 **SALES** 时：请求体 `ownerSalesId` SHALL 被忽略或拒绝指定为「其他 Sales」；调用者可显式传 `null` 或携带特定字段（如 `assignToPool: true`）让线索进入公海；不显式传公海标识则默认归属当前调用 Sales。

新建线索的初始 `stage` SHALL 固定为 `未触达`，无论调用者是 Admin 还是 Sales。

#### Scenario: Sales 创建线索默认归自己

- **WHEN** 持有 SALES 角色令牌的客户端提交不含 `ownerSalesId` 字段（也不含公海标识）的合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 持久化记录的 `owner_sales_id` 等于调用 Sales 的账号 id
- **AND** 持久化记录的 `stage` 为 `未触达`

#### Scenario: Sales 显式放入公海

- **WHEN** 持有 SALES 角色令牌的客户端提交携带 `assignToPool: true`（或等价"公海"语义参数）的合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 持久化记录的 `owner_sales_id` 为 NULL

#### Scenario: Sales 试图指定其他 Sales 被拒或被忽略

- **WHEN** 持有 SALES 角色令牌的客户端提交 `ownerSalesId=<其他 Sales 的 id>` 的创建请求
- **THEN** 系统**不**让线索归属于该其他 Sales：要么响应 `VALIDATION_ERROR`，要么忽略该字段并按"默认归自己"处理（design 可二选其一）；任一情况下**不**得让 lead.owner_sales_id 等于该其他 Sales 的 id

#### Scenario: Admin 指定停用 Sales 为归属被拒

- **WHEN** 持有 ADMIN 角色令牌的客户端提交 `ownerSalesId=<某个 DISABLED 状态 Sales 的 id>` 的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** `message` 表达"归属销售已停用或不可用"语义
- **AND** 数据库**未**新增行

#### Scenario: Admin 不指定归属进入公海

- **WHEN** 持有 ADMIN 角色令牌的客户端提交不含 `ownerSalesId` 字段的合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 持久化记录的 `owner_sales_id` 为 NULL

### Requirement: 业务线索查重三元组三态判断

创建线索时，系统 SHALL 在持久化前按三元组 `(businessYear, customerId, businessType)` 查询既有线索集合，并按以下状态决策表判定是否允许新建：

| 既有同三元组线索的 stage 集合 | 决策 | 错误码 |
| :--- | :--- | :--- |
| ∅(无既有线索) | 允许 | —— |
| 全部为 `已流失` | 允许 | —— |
| 含任一 `未触达` / `初步沟通` / `方案报价` / `商务谈判` | 拒 | `DUPLICATE_ACTIVE_LEAD` |
| 含任一 `已赢单` | 拒 | `DUPLICATE_WON_LEAD` |
| 同时含进行中与已赢单 | 拒 | `DUPLICATE_ACTIVE_LEAD` 或 `DUPLICATE_WON_LEAD` 任一即可 |

「联系人」字段 SHALL **不**参与查重判断（PRD §8.2.8）。

MVP 阶段 SHALL **不**用数据库 UNIQUE 索引兜底该规则（stage 状态组合无法表达为简单 UNIQUE）。并发场景下，两个调用者同时为同一三元组创建线索时，可能罕见出现「双行进行中」结果——本 spec 显式接受此 trade-off：系统 SHALL **不**返回 `INTERNAL_ERROR`，两次 INSERT 都可能成功；运维事后人工合并。

#### Scenario: 同三元组进行中线索存在时拒绝新建

- **GIVEN** 数据库中存在一条线索 L1，其 `businessYear=2026 / customerId=42 / businessType="BIM咨询" / stage="方案报价"`
- **WHEN** 已认证用户提交 `customerId=42 / businessType="BIM咨询"` 的创建请求（落在 2026 年）
- **THEN** 响应 HTTP `400`，`code="DUPLICATE_ACTIVE_LEAD"`
- **AND** 数据库**未**新增线索行

#### Scenario: 同三元组已赢单线索存在时拒绝新建

- **GIVEN** 数据库中存在一条线索 L2，其 `businessYear=2026 / customerId=42 / businessType="BIM咨询" / stage="已赢单"`
- **WHEN** 已认证用户提交 `customerId=42 / businessType="BIM咨询"` 的创建请求(落在 2026 年)
- **THEN** 响应 HTTP `400`，`code="DUPLICATE_WON_LEAD"`
- **AND** 数据库**未**新增线索行

#### Scenario: 同三元组仅已流失记录时允许新建

- **GIVEN** 数据库中存在两条线索 L3 / L4，均 `businessYear=2026 / customerId=42 / businessType="BIM咨询" / stage="已流失"`
- **WHEN** 已认证用户提交 `customerId=42 / businessType="BIM咨询"` 的合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库新增一行，stage 为 `未触达`

#### Scenario: 同客户不同业务类型不算重复

- **GIVEN** 数据库中存在一条线索 L5，其 `businessYear=2026 / customerId=42 / businessType="BIM咨询" / stage="方案报价"`
- **WHEN** 已认证用户提交 `customerId=42 / businessType="BIM培训"` 的合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`

#### Scenario: 不同年度同三元组不算重复

- **GIVEN** 数据库中存在一条线索 L6，其 `businessYear=2025 / customerId=42 / businessType="BIM咨询" / stage="方案报价"`
- **WHEN** 已认证用户在 2026 年提交 `customerId=42 / businessType="BIM咨询"` 的合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 持久化记录的 `business_year` 为 `2026`

### Requirement: 查重预检端点

系统 SHALL 提供一个查重预检端点 `GET /api/leads/duplicate-check?customerId=&businessType=`，允许已认证用户在提交创建请求**之前**预判该三元组的新建可行性。端点 SHALL 返回：

- `canCreate`（boolean）：是否允许新建
- `blockingReason`（string，nullable）：若 canCreate=false，给出阻塞原因码（`DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD`）
- `historicalLost`（数组）：若该三元组下存在已流失线索，列出每条的 `{ lostAt, loseReason, loseNote }`（按 lostAt 倒序）；与 canCreate 取值无关，便于提示历史

端点 SHALL 对所有已认证用户开放（不区分角色，与列表 / 创建一致）；调用此端点 SHALL **不**生成任何系统日志、**不**产生任何持久化副作用。

#### Scenario: 三元组无任何既有线索

- **GIVEN** 数据库中不存在任何 `customerId=42 / businessYear=2026 / businessType="BIM咨询"` 的线索
- **WHEN** 已认证用户请求 `GET /api/leads/duplicate-check?customerId=42&businessType=BIM咨询`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data.canCreate` 为 `true`
- **AND** `data.blockingReason` 为 `null`
- **AND** `data.historicalLost` 为 `[]`

#### Scenario: 三元组仅含已流失记录时提示历史

- **GIVEN** 数据库中存在两条已流失线索（同三元组、不同 lostAt）
- **WHEN** 已认证用户请求该三元组的预检
- **THEN** 响应 `data.canCreate` 为 `true`
- **AND** `data.blockingReason` 为 `null`
- **AND** `data.historicalLost` 数组长度为 2，按 `lostAt` 倒序排列，每项含 `lostAt` / `loseReason` / `loseNote`

#### Scenario: 三元组含进行中线索时阻塞

- **GIVEN** 三元组下存在一条进行中线索
- **WHEN** 已认证用户请求该三元组的预检
- **THEN** 响应 `data.canCreate` 为 `false`
- **AND** `data.blockingReason` 为 `"DUPLICATE_ACTIVE_LEAD"`

#### Scenario: 预检端点不写任何持久化数据

- **WHEN** 已认证用户调用预检端点任意次
- **THEN** 数据库的 `lead` 表与 `system_log` 表的行数与调用前完全一致

### Requirement: 线索详情与列表的权限隔离

线索详情与列表端点 SHALL 对所有已认证用户开放（含未结束阶段判断推迟到 lead-stage / lead-closure），但可见性按角色分层：

- `GET /api/leads/{id}`：
  - **Admin** SHALL 可访问任意线索详情。
  - **Sales** SHALL 只能访问 `owner_sales_id = 调用者 id` 的线索详情；访问其他 Sales 名下或公海线索（公海视图属 lead-ownership capability，本 change 不处理）的详情 SHALL 统一返回 HTTP `404`（**不**返回 `403`，避免泄漏线索是否存在）。
  - 线索不存在 SHALL 同样返回 HTTP `404`，与"无权访问"语义不可区分。

- `GET /api/leads/mine`：Sales 个人视图，返回 `owner_sales_id = 调用者 id` 的线索；按 `created_at` 倒序，硬上限 50 行（不引入 `page` / `size`）；Admin 调用本端点 SHALL 返回 `owner_sales_id = 调用 Admin id` 的线索集合（通常为空，因 Admin 通常不被指定为归属——但语义上一致）。

- `GET /api/leads`：Admin 全局视图，返回所有线索（含公海 + 全部私海）；按 `created_at` 倒序，硬上限 50 行。**仅 Admin** 可访问，Sales 调用 SHALL 返回 HTTP `403`，`code="FORBIDDEN"`。

详情视图 SHALL 内联展示 customerName / customerUsci，使前端无需额外请求 customer 端点。

#### Scenario: Admin 获取任意线索详情成功

- **WHEN** 持有 ADMIN 令牌的客户端请求 `GET /api/leads/{id}`，id 对应一条任意归属（含公海）的既存线索
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data` 包含全部 14 个业务字段 + customerName + customerUsci

#### Scenario: Sales 获取自己名下线索详情成功

- **WHEN** 持有 SALES 令牌的客户端请求 `GET /api/leads/{id}`，id 对应一条 `owner_sales_id = 调用者 id` 的既存线索
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`

#### Scenario: Sales 获取其他 Sales 名下线索详情返 404

- **WHEN** 持有 SALES 令牌的客户端请求 `GET /api/leads/{id}`，id 对应一条 `owner_sales_id != 调用者 id` 的既存线索（可能归其他 Sales 也可能在公海）
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`
- **AND** `message` **不**表达"无权访问"等暗示该线索存在的语义；与"线索不存在"返回的 message 不可区分

#### Scenario: Sales 调用 Admin 全局列表端点被拒

- **WHEN** 持有 SALES 令牌的客户端调用 `GET /api/leads`
- **THEN** 响应 HTTP `403`，`code="FORBIDDEN"`

#### Scenario: GET /api/leads/mine 仅返回归属当前用户的线索

- **GIVEN** 数据库存在 3 条线索：L1 归 Sales A、L2 归 Sales B、L3 在公海
- **WHEN** Sales A 请求 `GET /api/leads/mine`
- **THEN** 响应 `data` 数组长度为 1
- **AND** 唯一元素的 `id` 等于 L1.id

### Requirement: 创建线索触发系统日志

创建线索的成功路径 SHALL 触发一条 `SystemLogPort.record(...)` 调用，参数：`action="LEAD_CREATE"`、`targetType="LEAD"`、`targetId=<新建线索 id>`、`operatorId=<调用者 account id>`。底层 `system_log` 表对应行 SHALL 由 `system-log` capability 的 `JdbcSystemLogPort` 持久化（lead-core 不直接写 system_log 表）。

系统日志摘要 `summary` 字段 SHALL 包含足够辨识该次创建的自然语言信息（如客户名、业务类型、归属销售邮箱或"公海"标识），格式不强制约定但 SHALL 在 12 个月内保持稳定可读。

线索创建失败的路径（任一校验失败、查重拦截、未授权等）SHALL **不**触发系统日志。

#### Scenario: 创建线索成功后产生一条 LEAD_CREATE 日志

- **GIVEN** `system_log` 表创建前行数为 N
- **WHEN** 已认证用户成功创建一条线索
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_CREATE"`、`target_type="LEAD"`、`target_id` 等于新建线索 id、`operator_id` 等于调用者账号 id、`lead_id` 等于新建线索 id

#### Scenario: 校验失败不触发系统日志

- **GIVEN** `system_log` 表创建前行数为 N
- **WHEN** 已认证用户提交一个因联系电话格式错误而失败的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** `system_log` 表行数仍为 N

### Requirement: 公海线索列表

系统 SHALL 提供公海线索列表端点 `GET /api/leads/pool`，对 **ADMIN 与 SALES** 角色均开放，返回当前 `owner_sales_id IS NULL` 且未结束（stage ∉ {`已赢单`, `已流失`}）的线索集合，按 `created_at` 倒序，硬上限 50 行（不引入 `page` / `size` 参数）。

每个列表项 SHALL 内联 customerName / customerUsci，并包含 businessYear / businessType / contactName / leadSource / stage / createdAt / lastTrackedAt 字段。联系电话字段按调用者角色分级：

- 调用者为 **ADMIN** 时 SHALL 返回明文 `contactPhone`。
- 调用者为 **SALES** 时 SHALL 返回脱敏后的联系电话（按 tech-arch §9.4：11 位手机号 → 前 3 + `****` + 后 4；≥8 位其他号码 → 前 3 + `****` + 后 4；<8 位 → 仅显示末 2 位），且 SHALL **不**返回明文电话。

公海线索的 `owner_sales_id` 恒为 NULL，列表 SHALL **不**返回归属销售信息。调用本端点 SHALL **不**产生任何持久化副作用、**不**生成系统日志。

#### Scenario: Sales 查看公海列表电话脱敏

- **GIVEN** 数据库存在一条公海线索（owner_sales_id 为 NULL、stage=`未触达`、contact_phone=`13812345678`）
- **WHEN** 持有 SALES 令牌的客户端请求 `GET /api/leads/pool`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 该项联系电话字段值为 `138****5678`
- **AND** 响应**不**包含该线索的明文 `13812345678`

#### Scenario: Admin 查看公海列表电话明文

- **GIVEN** 数据库存在一条公海线索（owner_sales_id 为 NULL、stage=`未触达`、contact_phone=`13812345678`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `GET /api/leads/pool`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 该项联系电话字段值为明文 `13812345678`

#### Scenario: 公海列表仅含未结束且无归属线索

- **GIVEN** 数据库存在 4 条线索：L1（公海 / 未触达）、L2（归 Sales A / 方案报价）、L3（公海 / 已流失）、L4（公海 / 已赢单）
- **WHEN** 已认证用户请求 `GET /api/leads/pool`
- **THEN** 响应 `data` 数组仅含 L1
- **AND** L2（有归属）、L3（已流失）、L4（已赢单）均**不**出现

#### Scenario: 公海列表查询不产生持久化副作用

- **WHEN** 已认证用户调用 `GET /api/leads/pool` 任意次
- **THEN** `lead` 表与 `system_log` 表的行数与调用前完全一致

### Requirement: Sales 认领公海线索

系统 SHALL 提供认领端点 `POST /api/leads/{id}/claim`，仅 **SALES** 角色可调用。认领 SHALL 在后端单一事务内原子完成（先对目标行加锁再校验后更新），成功条件为：目标线索存在、`owner_sales_id IS NULL`（在公海）、未结束、调用者为 `ENABLED` 状态的 SALES。

认领成功 SHALL 把 `owner_sales_id` 置为调用者账号 id，stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束（已赢单 / 已流失）→ `LEAD_ENDED_READONLY`。
- 目标线索存在、未结束、但 `owner_sales_id IS NOT NULL`（被他人抢先认领或本就非公海）→ `LEAD_ALREADY_CLAIMED`，`message` 表达"该线索已被认领"语义。
- 调用者账号处于 `DISABLED` 状态 → `FORBIDDEN`。

并发场景下，多个 SALES 同时认领同一公海线索时 SHALL 仅一人成功置为其归属，其余 SHALL 返回 `LEAD_ALREADY_CLAIMED`，且 lead 行最终 `owner_sales_id` 恒等于成功者 id。

#### Scenario: Sales 认领公海线索成功

- **GIVEN** 数据库存在一条公海线索 L（owner_sales_id 为 NULL、stage=`初步沟通`）
- **WHEN** 持有 SALES 令牌（账号 id=7、ENABLED）的客户端请求 `POST /api/leads/L/claim`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 `7`
- **AND** L 的 `stage` 仍为 `初步沟通`

#### Scenario: 认领已被认领的线索返回 LEAD_ALREADY_CLAIMED

- **GIVEN** 数据库存在一条线索 L，其 `owner_sales_id` 已为 Sales B 的 id（非公海）、未结束
- **WHEN** Sales C 请求 `POST /api/leads/L/claim`
- **THEN** 响应 HTTP `409`，`code="LEAD_ALREADY_CLAIMED"`
- **AND** L 的 `owner_sales_id` 仍为 Sales B 的 id

#### Scenario: 并发认领仅一人成功

- **GIVEN** 数据库存在一条公海线索 L（owner_sales_id 为 NULL、未结束）
- **WHEN** 两个不同 SALES 近乎同时请求 `POST /api/leads/L/claim`
- **THEN** 恰有一个请求响应 `code="SUCCESS"`，另一个响应 `code="LEAD_ALREADY_CLAIMED"`
- **AND** L 的最终 `owner_sales_id` 等于那个成功请求的调用者 id

#### Scenario: 认领已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在一条公海线索 L（owner_sales_id 为 NULL、stage=`已流失`）
- **WHEN** SALES 请求 `POST /api/leads/L/claim`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** L 的 `owner_sales_id` 仍为 NULL

#### Scenario: 认领成功触发 LEAD_CLAIM 系统日志

- **GIVEN** `system_log` 表当前行数为 N，存在公海线索 L
- **WHEN** SALES（账号 id=7）成功认领 L
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_CLAIM"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=7`、`lead_id=L.id`

### Requirement: Sales 主动退回公海

系统 SHALL 提供退回端点 `POST /api/leads/{id}/release`，仅 **SALES** 角色可调用，请求体 SHALL 携带 `releaseNote`（trim 后非空）。退回成功条件为：目标线索 `owner_sales_id = 调用者 id`（自己名下）且未结束。

退回成功 SHALL 把 `owner_sales_id` 置为 NULL（重新进入公海），stage SHALL 保持不变（**不**重置为 `未触达`）。`releaseNote` SHALL 仅写入 `LEAD_RELEASE` 系统日志的 `summary`，**不**在 lead 表新增列存储。

失败映射：
- 目标线索不存在，或 `owner_sales_id != 调用者 id`（非自己名下，含公海与他人名下）→ `NOT_FOUND`（与 lead-core 详情口吻一致，不泄漏存在性）。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- `releaseNote` 缺失或 trim 后为空 → `VALIDATION_ERROR`。

#### Scenario: Sales 退回自己名下线索成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`），调用者为 Sales（账号 id=7）
- **WHEN** 该 Sales 请求 `POST /api/leads/L/release`，body `{ "releaseNote": "客户暂无预算" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 NULL
- **AND** L 的 `stage` 仍为 `方案报价`（未重置）

#### Scenario: 退回缺失备注被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、未结束），调用者为 Sales（账号 id=7）
- **WHEN** 该 Sales 请求 `POST /api/leads/L/release`，body 不含 `releaseNote` 或 `releaseNote="   "`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 7

#### Scenario: 退回非自己名下线索返 404

- **GIVEN** 数据库存在线索 L（owner_sales_id=99，归他人）
- **WHEN** Sales（账号 id=7）请求 `POST /api/leads/L/release`，body 含合法 `releaseNote`
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`

#### Scenario: 退回已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`），调用者为 Sales（账号 id=7）
- **WHEN** 该 Sales 请求 `POST /api/leads/L/release`，body 含合法 `releaseNote`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 退回成功触发 LEAD_RELEASE 系统日志含备注

- **GIVEN** `system_log` 表当前行数为 N，线索 L 归 Sales（账号 id=7）
- **WHEN** 该 Sales 以 `releaseNote="客户暂无预算"` 成功退回 L
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_RELEASE"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=7`、`lead_id=L.id`
- **AND** 该行 `summary` 包含退回备注文本 `客户暂无预算`

### Requirement: Admin 分配公海线索

系统 SHALL 提供分配端点 `POST /api/leads/{id}/assign`，仅 **ADMIN** 角色可调用，请求体 SHALL 携带目标 `salesId`。分配成功条件为：目标线索存在、`owner_sales_id IS NULL`（在公海）、未结束，且 `salesId` 指向一个 `ENABLED` 状态的 SALES 账号。

分配成功 SHALL 把 `owner_sales_id` 置为 `salesId`，stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- 目标线索 `owner_sales_id IS NOT NULL`（不在公海，应改用转移）→ `VALIDATION_ERROR`。
- `salesId` 缺失、对应账号不存在、非 SALES 角色或处于 `DISABLED` 状态 → `VALIDATION_ERROR`，`message` 表达"目标销售不可用"语义。

#### Scenario: Admin 分配公海线索给启用 Sales 成功

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`）与 ENABLED 的 Sales（id=7）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 7 }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 `7`

#### Scenario: 分配给停用 Sales 被拒

- **GIVEN** 数据库存在公海线索 L 与 DISABLED 的 Sales（id=8）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 8 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 NULL

#### Scenario: 分配已有归属的线索被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7，已有归属、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 9 }`（9 为 ENABLED Sales）
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 7

#### Scenario: 分配已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`已流失`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 7 }`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 分配成功触发 LEAD_ASSIGN 系统日志含原 / 新归属

- **GIVEN** `system_log` 表当前行数为 N，公海线索 L，ADMIN 账号 id=1
- **WHEN** ADMIN 把 L 分配给 Sales（id=7）成功
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_ASSIGN"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=1`、`lead_id=L.id`
- **AND** 该行 `summary` 包含原归属（公海）与新归属（Sales id=7 的可读标识）信息

### Requirement: Admin 回收线索到公海

系统 SHALL 提供回收端点 `POST /api/leads/{id}/recall`，仅 **ADMIN** 角色可调用。回收成功条件为：目标线索存在、`owner_sales_id IS NOT NULL`（在某 Sales 名下）、未结束。

回收成功 SHALL 把 `owner_sales_id` 置为 NULL（移回公海），stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- 目标线索 `owner_sales_id IS NULL`（已在公海）→ `VALIDATION_ERROR`。

#### Scenario: Admin 回收私海线索成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/recall`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 NULL
- **AND** L 的 `stage` 仍为 `方案报价`

#### Scenario: 回收已在公海的线索被拒

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/recall`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`

#### Scenario: 回收已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/recall`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 回收成功触发 LEAD_RECALL 系统日志含原 / 新归属

- **GIVEN** `system_log` 表当前行数为 N，线索 L 归 Sales（id=7），ADMIN 账号 id=1
- **WHEN** ADMIN 成功回收 L
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_RECALL"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=1`、`lead_id=L.id`
- **AND** 该行 `summary` 包含原归属（Sales id=7）与新归属（公海）信息

### Requirement: Admin 转移线索归属

系统 SHALL 提供转移端点 `POST /api/leads/{id}/transfer`，仅 **ADMIN** 角色可调用，请求体 SHALL 携带目标 `salesId`。转移成功条件为：目标线索存在、`owner_sales_id IS NOT NULL`（在某 Sales 名下）、未结束，`salesId` 指向一个 `ENABLED` 状态的 SALES 账号，且 `salesId` 不等于当前 `owner_sales_id`。

转移成功 SHALL 把 `owner_sales_id` 置为 `salesId`，stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- 目标线索 `owner_sales_id IS NULL`（在公海，应改用分配）→ `VALIDATION_ERROR`。
- `salesId` 缺失、对应账号不存在、非 SALES、`DISABLED` 状态，或等于当前归属 → `VALIDATION_ERROR`。

#### Scenario: Admin 转移线索给另一启用 Sales 成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`）与 ENABLED 的 Sales（id=9）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 9 }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 `9`
- **AND** L 的 `stage` 仍为 `商务谈判`

#### Scenario: 转移给停用 Sales 被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、未结束）与 DISABLED 的 Sales（id=8）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 8 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 7

#### Scenario: 转移公海线索被拒

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 9 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`

#### Scenario: 转移给当前归属本人被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 7 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`

#### Scenario: 转移已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已流失`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 9 }`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 转移成功触发 LEAD_TRANSFER 系统日志含原 / 新归属

- **GIVEN** `system_log` 表当前行数为 N，线索 L 归 Sales（id=7），ADMIN 账号 id=1
- **WHEN** ADMIN 把 L 从 Sales 7 转移给 Sales 9 成功
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_TRANSFER"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=1`、`lead_id=L.id`
- **AND** 该行 `summary` 包含原归属（Sales id=7）与新归属（Sales id=9）的可读标识

### Requirement: 业务线索不在 lead-core 范围内的能力

本 capability SHALL **不**提供以下能力（这些属于后续 lead-stage / lead-closure change，将向本 capability 增量加入对应 requirement）：

- 阶段变更（PATCH /stage）
- 标记赢单 / 标记流失 + 合同记录生成
- 编辑 / 删除既有线索（MVP 整体不支持）

公海视图、认领、退回、分配、回收、转移已由 lead-ownership change 实现，**不**再列于本未覆盖清单。

#### Scenario: 当前阶段不存在阶段变更端点

- **WHEN** 任意已认证用户对 `PATCH /api/leads/{id}/stage` 发起请求
- **THEN** 系统**未**对外暴露此端点（响应**不**为 `SUCCESS`）

#### Scenario: 当前阶段不存在赢单 / 流失端点

- **WHEN** 任意已认证用户对 `POST /api/leads/{id}/win` 或 `POST /api/leads/{id}/lose` 发起请求
- **THEN** 系统**未**对外暴露此端点
