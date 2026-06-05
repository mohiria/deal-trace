## Why

当前线索（我的 / 全部 / 公海）与客户列表在后端被硬截 50 行，且线索"搜索"只是对已加载前 50 行的客户端 filter——超过 50 条的数据既无法浏览也无法被搜索命中，"全量展示 / 全量查询"在实际数据量下失效。同时新建线索只能选既有客户（`CustomerSelect` 不提供内联建客户），当目标客户尚不存在时销售必须中断流程、跳去客户管理页创建后再回来，体验割裂。这两件事相互成全：只有全量搜索就位，"搜不到即新建"的判断才可靠。

## What Changes

- **服务端分页 + 关键词下推**：`GET /api/leads/mine`、`GET /api/leads`、`GET /api/leads/pool`、`GET /api/customers` 接受 `page` / `size`（线索端点新增 `keyword`），对**全表**做匹配后再切页，返回 `{ items, total, page, size }` 信封（复用 system-log / contract 既有范式）。`total` 为当前查询（含关键词过滤）的命中总数。
  - **BREAKING**：上述端点响应由"裸数组"变为分页信封；所有前端消费方（store / 列表页 / 工作台）须从客户端分页改为服务端分页。
- **新建线索内联创建客户（find-or-create 合并端点）**：`POST /api/leads` 接受 `customerId` **或** `newCustomer { name, usci }`（二选一）。提供 `newCustomer` 时，后端在同一事务内按 USCI 仲裁：命中且 name 一致则复用、命中但 name 冲突则报错、未命中则创建客户，随后创建线索。前端 `CustomerSelect` 在搜不到时提供"录入新客户（name + USCI）"入口。
- **列表页改服务端分页 + 搜索**：客户管理、我的线索 / 全部线索、公海三页改为服务端驱动分页，关键词随分页请求下推后端；移除客户端 `slice` 与客户端 `filter`。换关键词回到第 1 页。
- **Dashboard 全量改造**：工作台内嵌线索表改服务端分页；tab 计数改用 `total`（不再用已加载数组 `.length`）；"今日提醒"（长期未跟踪 / 建议认领）由客户端派生改为调用后端查询，使其跨全量数据准确。
- 移除各端点写死的 `SEARCH_LIMIT = 50` / `POOL_LIMIT = 50` 硬上限，代之以 `size` 上限护栏（clamp）。

## Capabilities

### New Capabilities
<!-- 无新增能力：全部为既有能力的 requirement 修改 -->

### Modified Capabilities
- `lead`: `GET /leads/mine`、`GET /leads`、`GET /leads/pool` 由"硬上限 50、不引入 page/size"改为服务端分页 + `keyword` 全表搜索并返回 `{items,total,page,size}`；新增"我的长期未跟踪线索"查询能力（供工作台提醒下推）；创建线索端点接受 `newCustomer{name,usci}` 走 find-or-create。
- `customer`: `GET /customers` 由"固定 50、不引入 page/size"改为服务端分页（`keyword` 全表搜索后分页，返回信封）。
- `frontend-workbench`: 客户管理 / 我的线索 / 公海列表改服务端分页 + 搜索下推（改"前端不扩上限 / 工作台全量加载"）；可搜索下拉选择器允许内联建客户（改"SHALL NOT 边搜边建"）；今日提醒由"仅客户端派生"改为后端查询下推。

## Impact

- **后端**：`LeadService` / `LeadOwnershipService`（分页 + keyword + stale 查询 + 移除 50 上限）、`LeadController`（query 参数、create 接 `newCustomer`）、`CustomerService` / `CustomerController`（分页）、复用 system-log/contract 的分页信封与 `LIMIT/OFFSET + count` 范式；create-lead 事务内 find-or-create 客户（USCI 唯一约束兜底并发）。
- **前端**：`api/leads.ts` / `api/customers.ts` 返回类型改信封；`stores/leads.ts` 列表状态改 `{items,total,page,size}`；`MyLeadsView` / `PublicPoolView` / `CustomersView` / `DashboardView` 改服务端分页 + 搜索；`CreateLeadModal` / `CustomerSelect` 内联建客户；`utils/workbench.ts` 的客户端提醒派生改为消费后端结果。
- **DB**：无 schema 变更（仅查询层 LIMIT/OFFSET + COUNT）。
- **测试**：分页 / 搜索 / find-or-create / 并发 USCI 的 API 集成测试（真 MySQL 8.4）；前端 store / 视图 / 选择器组件测试更新。
