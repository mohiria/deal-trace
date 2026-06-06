## MODIFIED Requirements

### Requirement: 客户搜索 / 列表统一端点

系统 SHALL 提供一个统一的客户查询端点，对所有已认证用户开放（不区分角色）；同时支持「无关键词的列表」与「按关键词搜索」两种使用形态，并 SHALL 服务端分页：

端点 SHALL 接受查询参数 `page`（默认 1、最小 1）、`size`（默认 20、范围 1..100、超界裁剪为 100）、`keyword`（选填）：

- 当请求**不**携带或携带空白 `keyword` 时，匹配集合为**全部客户**；
- 当请求携带非空白 `keyword` 时，匹配集合为 `name` 包含该 keyword（子串匹配）**或** `usci` 包含该 keyword（子串匹配，匹配前先按客户表的 USCI 存储形态进行）的**全部客户**。

匹配集合 SHALL 按 `created_at` 倒序排序后，依据 `page` / `size` 切出当页返回；SHALL **不**再施加固定 50 行硬上限、SHALL **不**保留「无 page/size、固定返回前 50 行」的旧语义。响应 `data` SHALL 为分页信封 `{ items, total, page, size }`：`items` 为当页客户（≤ size），每个元素包含 `id` / `name` / `usci` / `createdAt` 四字段，与创建响应同形；`total` 为当前查询（含 keyword 过滤）命中的客户总数；`page` / `size` 回显本次生效的分页参数。关键词匹配 SHALL 作用于**全表**而非仅当前页，使超出单页范围的匹配客户仍可被搜索命中并经翻页访问。

#### Scenario: 无关键词请求分页返回且 total 为全表数

- **GIVEN** 数据库中有 60 条客户记录
- **WHEN** 已认证用户请求 `GET /api/customers?page=1&size=20`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data.items` 为长度 20 的数组，按 `createdAt` 倒序排列
- **AND** `data.total` 等于 60、`data.page` 等于 1、`data.size` 等于 20

#### Scenario: 翻页可访问首页之外的客户

- **GIVEN** 数据库中有 60 条客户记录
- **WHEN** 已认证用户请求 `GET /api/customers?page=3&size=20`
- **THEN** `data.items` 为长度 20 的数组（第 3 页）
- **AND** 该页元素与第 1 页元素无交集

#### Scenario: 关键词命中 name 子串并跨全表匹配

- **GIVEN** 数据库中存在 60 条客户，其中仅 1 条 name 为 `"中国建筑设计研究院"` 含 `"建筑"`，且其 createdAt 排在第 55 顺位
- **WHEN** 已认证用户请求 `GET /api/customers?keyword=建筑&page=1&size=20`
- **THEN** `data.items` 包含该客户
- **AND** `data.total` 等于 1（命中集合规模，而非全表 60）

#### Scenario: 关键词命中 USCI 子串

- **GIVEN** 数据库中存在 USCI 为 `"91110000123456789X"` 的客户 Y
- **WHEN** 已认证用户请求 `GET /api/customers?keyword=91110000`
- **THEN** 响应 `data.items` 包含客户 Y

#### Scenario: 关键词无命中返回空 items 与 total 0

- **WHEN** 已认证用户请求 `GET /api/customers?keyword=不存在的关键词xyz999`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data.items` 为空数组 `[]`、`data.total` 为 `0`
- **AND** 响应**不**为 `null` 或 HTTP `404`

#### Scenario: 匿名访问搜索端点被拒

- **WHEN** 未携带 `Authorization` 头的客户端调用客户搜索端点
- **THEN** 响应 HTTP `401`，`code="UNAUTHORIZED"`
