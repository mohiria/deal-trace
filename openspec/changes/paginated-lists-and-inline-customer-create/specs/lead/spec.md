## MODIFIED Requirements

### Requirement: 线索详情与列表的权限隔离

线索详情与列表端点 SHALL 对所有已认证用户开放（含未结束阶段判断推迟到 lead-stage / lead-closure），但可见性按角色分层：

- `GET /api/leads/{id}`：
  - **Admin** SHALL 可访问任意线索详情。
  - **Sales** SHALL 只能访问 `owner_sales_id = 调用者 id` 的线索详情；访问其他 Sales 名下或公海线索（公海视图属 lead-ownership capability，本 change 不处理）的详情 SHALL 统一返回 HTTP `404`（**不**返回 `403`，避免泄漏线索是否存在）。
  - 线索不存在 SHALL 同样返回 HTTP `404`，与"无权访问"语义不可区分。

- `GET /api/leads/mine`：Sales 个人视图，返回 `owner_sales_id = 调用者 id` 的线索；Admin 调用本端点 SHALL 返回 `owner_sales_id = 调用 Admin id` 的线索集合（通常为空，因 Admin 通常不被指定为归属——但语义上一致）。本端点 SHALL 服务端分页，接受 `page`（默认 1、最小 1）、`size`（默认 20、范围 1..100、超界裁剪为 100）、`keyword`（选填）查询参数，并 SHALL 返回分页信封 `{ items, total, page, size }`：`items` 为当页线索（≤ size），按 `created_at` 倒序；`total` 为当前查询（含 keyword 过滤）命中的总条数；SHALL **不**再施加固定 50 行硬上限。当 `keyword` 非空（trim 后）时，匹配 SHALL 对调用者**有权访问的全部线索**按客户名称 / 统一社会信用代码 / 联系人子串匹配后再分页，SHALL **不**仅在当前页内过滤。

- `GET /api/leads`：Admin 全局视图，返回所有线索（含公海 + 全部私海）。**仅 Admin** 可访问，Sales 调用 SHALL 返回 HTTP `403`，`code="FORBIDDEN"`。本端点 SHALL 服务端分页，接受 `page` / `size` / `keyword` 参数并返回与 `GET /api/leads/mine` 同形的 `{ items, total, page, size }` 信封；`items` 按 `created_at` 倒序、`total` 为含 keyword 过滤的命中总数；SHALL **不**再施加固定 50 行硬上限。

详情视图 SHALL 内联展示 customerName / customerUsci，使前端无需额外请求 customer 端点。列表项 SHALL 内联 customerName / customerUsci 与归属销售姓名 ownerSalesName（公海/无归属为 null）。

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

#### Scenario: GET /api/leads/mine 仅返回归属当前用户的线索并以分页信封呈现

- **GIVEN** 数据库存在 3 条线索：L1 归 Sales A、L2 归 Sales B、L3 在公海
- **WHEN** Sales A 请求 `GET /api/leads/mine`（不带 keyword）
- **THEN** 响应 `data` 为分页信封：`data.items` 长度为 1、唯一元素 `id` 等于 L1.id
- **AND** `data.total` 等于 1，`data.page` 等于 1，`data.size` 等于默认页大小

#### Scenario: mine 列表按 page/size 分页

- **GIVEN** Sales A 名下存在 25 条线索
- **WHEN** Sales A 请求 `GET /api/leads/mine?page=2&size=20`
- **THEN** `data.items` 长度为 5（第 2 页余量）
- **AND** `data.total` 等于 25、`data.page` 等于 2、`data.size` 等于 20

#### Scenario: keyword 对全量数据匹配而非仅当前页

- **GIVEN** Sales A 名下存在 60 条线索，其中仅第 55 条创建顺位的线索关联客户名称含 `"星河"`
- **WHEN** Sales A 请求 `GET /api/leads/mine?keyword=星河&page=1&size=20`
- **THEN** `data.items` 包含该条线索
- **AND** `data.total` 等于 1（命中集合规模，而非全表 60）

### Requirement: 公海线索列表

系统 SHALL 提供公海线索列表端点 `GET /api/leads/pool`，对 **ADMIN 与 SALES** 角色均开放，返回当前 `owner_sales_id IS NULL` 且未结束（stage ∉ {`已赢单`, `已流失`}）的线索集合，按 `created_at` 倒序。本端点 SHALL 服务端分页：接受 `page`（默认 1、最小 1）、`size`（默认 20、范围 1..100、超界裁剪为 100）、`keyword`（选填）查询参数，并返回分页信封 `{ items, total, page, size }`，`items` 为当页公海线索（≤ size、created_at 倒序）、`total` 为当前查询（含 keyword 过滤）命中的公海线索总数；SHALL **不**再施加固定 50 行硬上限、SHALL **不**保留 `page` / `size` 缺省即返回全部的旧语义。当 `keyword` 非空（trim 后）时，匹配 SHALL 对**全部符合公海条件的线索**按客户名称 / 统一社会信用代码 / 联系人子串匹配后再分页，SHALL **不**对联系电话做关键词匹配（避免脱敏与明文口径不一致）。

每个列表项 SHALL 内联 customerName / customerUsci，并包含 businessYear / businessType / contactName / leadSource / stage / createdAt / lastTrackedAt 字段。联系电话字段按调用者角色分级：

- 调用者为 **ADMIN** 时 SHALL 返回明文 `contactPhone`。
- 调用者为 **SALES** 时 SHALL 返回脱敏后的联系电话（按 tech-arch §9.4：11 位手机号 → 前 3 + `****` + 后 4；≥8 位其他号码 → 前 3 + `****` + 后 4；<8 位 → 仅显示末 2 位），且 SHALL **不**返回明文电话。

公海线索的 `owner_sales_id` 恒为 NULL，列表 SHALL **不**返回归属销售信息。调用本端点 SHALL **不**产生任何持久化副作用、**不**生成系统日志。

#### Scenario: Sales 查看公海列表电话脱敏

- **GIVEN** 数据库存在一条公海线索（owner_sales_id 为 NULL、stage=`未触达`、contact_phone=`13812345678`）
- **WHEN** 持有 SALES 令牌的客户端请求 `GET /api/leads/pool`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data.items` 中该项联系电话字段值为 `138****5678`
- **AND** 响应**不**包含该线索的明文 `13812345678`

#### Scenario: Admin 查看公海列表电话明文

- **GIVEN** 数据库存在一条公海线索（owner_sales_id 为 NULL、stage=`未触达`、contact_phone=`13812345678`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `GET /api/leads/pool`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data.items` 中该项联系电话字段值为明文 `13812345678`

#### Scenario: 公海列表以分页信封返回且仅含未结束无归属线索

- **GIVEN** 数据库存在 4 条线索：L1（公海 / 未触达）、L2（归 Sales A / 方案报价）、L3（公海 / 已流失）、L4（公海 / 已赢单）
- **WHEN** 已认证用户请求 `GET /api/leads/pool`（不带 keyword）
- **THEN** 响应 `data` 为分页信封，`data.items` 仅含 L1
- **AND** `data.total` 等于 1；L2（有归属）、L3（已流失）、L4（已赢单）均**不**出现

#### Scenario: 公海 keyword 对全量公海集合匹配

- **GIVEN** 公海中存在 60 条线索，其中仅 1 条关联客户名称含 `"星河"`
- **WHEN** 已认证用户请求 `GET /api/leads/pool?keyword=星河`
- **THEN** `data.items` 包含该条线索、`data.total` 等于 1

#### Scenario: 公海列表查询不产生持久化副作用

- **WHEN** 已认证用户调用 `GET /api/leads/pool` 任意次
- **THEN** `lead` 表与 `system_log` 表的行数与调用前完全一致

### Requirement: 创建线索必填字段与格式校验

创建线索 SHALL 校验关联客户与以下字段：关联客户 SHALL 以 `customerId`（必须指向既有客户）**或** `newCustomer { name, usci }`（内联新建客户，二者**恰择其一**）之一给出；二者同时缺失或同时提供 SHALL 返回 `VALIDATION_ERROR`。其余必填：`businessType`（必须为合法枚举值）、`contactName`（trim 后非空）、`contactPhone`（trim 后非空且符合中国大陆 11 位手机号或常见座机格式）。`leadSource` 为选填，空字符串与 null 视为未填。任一必填项缺失或格式错误 SHALL 返回 `VALIDATION_ERROR`，`message` 指明字段名或语义；数据库 SHALL **不**新增行（含 customer 行与 lead 行）。

联系电话校验规则：
- 11 位手机号：第 1 位为 `1`、第 2 位为 `3-9`、其余 9 位为数字
- 座机：可选区号（`0` 开头 3-4 位数字）+ 7-8 位号码 + 可选分机号；如 `010-12345678`、`0571-12345678`、`0571-12345678-123`
- 海外号码格式 MVP 阶段不支持

#### Scenario: customerId 与 newCustomer 同时缺失拒绝创建

- **WHEN** 已认证用户提交既无 `customerId` 也无 `newCustomer` 的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 数据库**未**新增线索行

#### Scenario: customerId 指向不存在客户拒绝创建

- **WHEN** 已认证用户提交 `customerId` 对应客户不存在的创建请求
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

## ADDED Requirements

### Requirement: 新建线索内联创建客户（find-or-create）

当创建线索请求以 `newCustomer { name, usci }` 给出关联客户时，系统 SHALL 在**同一数据库事务**内先确定客户身份再创建线索，按统一社会信用代码（USCI，强键）仲裁：

1. SHALL 先按 customer capability 既有规则对 `usci` 归一化（trim + 字母大写）并通过 18 位 GB 32100-2015 校验；校验失败 SHALL 返回 `VALIDATION_ERROR`，**不**新增任何行。
2. 以归一化 USCI 查既有客户：
   - **命中且既有客户 `name`（trim 后）与提交 `name`（trim 后）相等**：SHALL 复用该既有客户的 id 作为关联客户（find，不新增客户行）。
   - **命中但既有客户 `name` 与提交 `name` 不相等**：SHALL 返回业务错误（`code="DUPLICATE_CUSTOMER"`），message 表达"该统一社会信用代码已属于其他客户名称，请改用搜索选择既有客户"语义，**不**新增任何行、**不**静默改名或改建。
   - **未命中**：SHALL 创建新客户（客户 name trim 唯一约束与 USCI 全局唯一约束由 customer capability 兜底）后以其 id 作为关联客户（create）。
3. 客户身份确定后 SHALL 继续既有的查重三元组与必填校验创建线索；线索创建失败 SHALL 使整个事务回滚——`newCustomer` 路径下若线索因任何原因未成功创建，先前 find-or-create 的客户行 SHALL **不**遗留（不产生孤儿客户）。
4. 并发提交相同 USCI 的两个 `newCustomer` 请求 SHALL 由数据库唯一约束兜底：至多一个 INSERT 成功，另一个 SHALL 回退为 find 复用同一客户，二者均 SHALL **不**返回 `INTERNAL_ERROR` 或 SQL 异常细节。

`newCustomer` 路径下成功创建线索 SHALL 与 `customerId` 路径一致触发一条 `LEAD_CREATE` 系统日志；新建客户本身 SHALL **不**触发系统日志（沿用 customer capability「创建客户不生成系统日志」）。

#### Scenario: newCustomer USCI 未命中则建客户再建线索

- **GIVEN** 数据库不存在归一化 USCI 等于提交值的客户
- **WHEN** 已认证用户提交 `newCustomer={name:"星河设计院", usci:<合法且未占用>}` 且其余线索字段合法的创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库新增一行客户（name/usci 为归一化结果）与一行线索，线索 `customer_id` 指向该新客户

#### Scenario: newCustomer USCI 命中且同名则复用既有客户

- **GIVEN** 数据库已存在客户 C（name=`"星河设计院"`、归一化 USCI=U）
- **WHEN** 已认证用户提交 `newCustomer={name:"星河设计院", usci:<归一化后等于 U>}` 的创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库客户行数**不**增加，新建线索 `customer_id` 等于 C.id

#### Scenario: newCustomer USCI 命中但异名拒绝

- **GIVEN** 数据库已存在客户 C（name=`"星河设计院"`、归一化 USCI=U）
- **WHEN** 已认证用户提交 `newCustomer={name:"另一个名字", usci:<归一化后等于 U>}` 的创建请求
- **THEN** 响应 HTTP `400`，`code="DUPLICATE_CUSTOMER"`
- **AND** 数据库客户行数与线索行数均**不**变化

#### Scenario: newCustomer 线索创建失败不遗留孤儿客户

- **GIVEN** 数据库不存在该 USCI 客户，但该（businessYear, 该客户, businessType）三元组将命中查重拦截或联系电话非法
- **WHEN** 已认证用户以 `newCustomer` 提交一个会在线索创建阶段失败的请求
- **THEN** 响应为对应业务错误码（如 `DUPLICATE_ACTIVE_LEAD` / `VALIDATION_ERROR`）
- **AND** 数据库客户行数与线索行数均与请求前一致（事务回滚，无孤儿客户）

### Requirement: 我的长期未跟踪线索查询

系统 SHALL 提供"我的长期未跟踪线索"查询端点，供工作台今日提醒下推：返回**调用者名下、未结束（stage ∉ {已赢单, 已流失}）、且最后跟踪时间早于服务端阈值（或从未跟踪，lastTrackedAt 为 NULL）**的线索集合，按 `lastTrackedAt` 升序（最久未跟踪在前、NULL 视为最久），并施加合理数量上限（如前 N 条）以服务于提醒展示而非全量浏览。阈值天数 SHALL 由后端集中定义，前端 SHALL **不**自行重算或推导该集合。该端点 SHALL **不**产生任何持久化副作用、**不**生成系统日志。

#### Scenario: 返回名下超阈值未跟踪的未结束线索

- **GIVEN** Sales A 名下有线索 L1（最后跟踪时间早于阈值、未结束）、L2（最近跟踪、未结束）、L3（从未跟踪、未结束）、L4（已赢单）
- **WHEN** Sales A 请求"我的长期未跟踪线索"端点
- **THEN** 响应集合包含 L1 与 L3
- **AND** **不**包含 L2（阈值内）与 L4（已结束）

#### Scenario: 查询不产生持久化副作用

- **WHEN** 已认证用户调用"我的长期未跟踪线索"端点任意次
- **THEN** `lead` 表与 `system_log` 表的行数与调用前完全一致
