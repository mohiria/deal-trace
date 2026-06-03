## Context

合同记录写侧已落地（`V6__contract.sql` 建表，`Contract` 实体 + `ContractMapper`，由赢单事务原子生成；spec 见 `openspec/specs/contract/`）。表字段：`id / lead_id(唯一) / contract_amount DECIMAL(15,2) / signed_date DATE / deal_sales_id(可空,FK account) / created_at DATETIME(3)`。`deal_sales_id` 为赢单时刻线索归属，公海单由 Admin 赢单时为 NULL。

读侧目前缺失：无 `ContractController`、无查询接口；前端 `/contracts` 路由指向 `PlaceholderView`。

本 change 新增读侧能力 `contract-view`，与写侧 `contract` 分离（对齐 `system-log` / `system-log-view` 拆分约定，见归档 `2026-06-02-view-system-log`）。展示需要 `客户名称`（join customer）与 `业务类型`（join lead），合同表本身不存这些字段。

## Goals / Non-Goals

**Goals:**
- 提供一个对 Admin 与 Sales 都可用的合同记录浏览端点，按 `created_at` 倒序分页。
- 角色化可见范围：Admin 全量；Sales 强制收窄为 `deal_sales_id = 本人`（与传入参数无关）。
- 支持筛选：成交销售 `dealSalesId`（仅 Admin 有意义）、签订日期闭区间、客户名称/业务类型关键词。
- 前端将 `/contracts` 占位页替换为真实列表页，复用 `SystemLogsView` 的全局浏览布局与分页交互。

**Non-Goals:**
- 不新增任何合同创建/编辑/删除入口；合同仍仅由赢单事务生成。
- 不改 `contract` 表结构、不做迁移、不动赢单写路径。
- 不引入 Tailwind（tech-arch §10）；金额不使用浮点（tech-arch §9.2）。
- 不做导出（CSV 在 PRD §10 非本期）。

## Decisions

**D1：端点放在 `/contracts` 而非 `/admin/contracts`。**
该能力同时服务 Sales（看自己成交），而 `/admin/**` 在 `SecurityConfig` 被 `hasRole("ADMIN")` 整段拦截，Sales 无法访问。故采用 `@RequestMapping("/contracts")` 控制器（认证即可访问），由 service 层依 `AccountPrincipal` 做角色收窄——沿用 `LeadController#mine` 已有的"principal 驱动可见范围"模式。
*备选：拆 `/admin/contracts`（全量）+ `/contracts/mine`（本人）两个端点。* 否决：两端返回结构与筛选完全一致，单端点 + 服务内分流更省，且与 spec 的"同一浏览能力、按角色收窄"一致。

**D2：Sales 收窄在 service 层强制覆盖 `dealSalesId` 参数。**
当 `principal.role == SALES` 时，忽略请求传入的 `dealSalesId`，强制以 `principal.id` 作为查询条件，杜绝越权按他人 id 拉数据（spec「SALES 传入他人 dealSalesId 仍被收窄为本人」）。Admin 时 `dealSalesId` 作为可选过滤透传。

**D3：查询用一条带 JOIN 的分页 SQL（ContractMapper），而非应用层多次取数再拼。**
`contract` JOIN `lead`（取 business_type、customer_id）JOIN `customer`（取 name）；关键词对 `customer.name` 与 `lead.business_type` 做 `LIKE` 包含匹配；日期区间对 `signed_date` 闭区间过滤；`deal_sales_id` 可选等值过滤。排序 `ORDER BY contract.created_at DESC`，分页 `LIMIT/OFFSET`。配套 count 查询取总数供分页。
*备选：先查 contract 再逐条解析客户/线索。* 否决：N+1，且关键词/排序难下推。

**D4：成交销售姓名"当前值"解析，不冻结快照。**
展示用 `deal_sales_id` 关联 `account` 取**当前**姓名；`deal_sales_id=NULL` 展示"公海赢单"。与 `system-log-view`「姓名按当前账号信息解析」一致。可在同一 JOIN 链路 LEFT JOIN account 取姓名。

**D5：金额展示在前端做千分位格式化，后端原样返回精确数值（字符串化的 DECIMAL）。**
后端以精确数值序列化（沿用既有 Jackson 配置），前端格式化为千分位展示；断言金额时按数值比较不按展示串（见项目记忆 jackson3-objectmapper-bean 的数值规范化注意）。

**D6：分页参数沿用 `AdminSystemLogController` 约定（`page` 默认 1、`size` 默认 20）。**
返回结构对齐 `SystemLogPageView`（条目列表 + 分页元信息），新建 `ContractPageView` / `ContractRowView`。

## Risks / Trade-offs

- **[Sales 越权读他人合同]** → service 层强制以 principal.id 覆盖 `dealSalesId`，并在 API/集成测试中以「Sales 传他人 id 仍只返回本人」用例钉死（spec 已含该 Scenario）。
- **[关键词 LIKE 全表扫]** → MVP 数据量小可接受；business_type / customer.name 已有业务索引，必要时后续加索引。本期不优化。
- **[日期区间边界]** → 明确闭区间 `[from, to]`，含端点；缺省任一端表示该侧不设限。测试覆盖恰好落在端点的记录。
- **[集成测试需真 MySQL 8.4]**（tech-arch §12）→ ContractMapper 的 JOIN/LIKE/日期过滤须跑真库验证；勿用 H2。注意与 dev smoke 不可并发（项目记忆 smoke-vs-mvn-verify-share-db）。
- **[公海赢单 deal_sales_id=NULL]** → Sales 范围查询必须排除 NULL 行（`deal_sales_id = :id` 自然排除 NULL）；Admin 全量须包含 NULL 行并展示"公海赢单"。

## Open Questions

- 列表是否需要展示并跳转到关联线索详情？倾向「客户名/业务类型可点击跳 `/leads/:id`」，但非硬需求，留待 tasks 落地时按 `SystemLogsView` 现状决定，不阻塞。
