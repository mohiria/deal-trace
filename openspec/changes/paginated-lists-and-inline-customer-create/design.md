## Context

线索（mine/all/pool）与客户列表端点当前在 service 层写死 `LIMIT 50`（`LeadService.SEARCH_LIMIT`、`LeadOwnershipService.POOL_LIMIT`、`CustomerService.SEARCH_LIMIT`），且线索列表端点无 `keyword` 参数——前端 `MyLeadsView` 仅对已加载的 ≤50 行做客户端 filter。数据量超过 50 时既无法浏览也无法被搜索命中。同时 `CustomerSelect` 只选既有客户，新建客户须跳转独立页。

仓库内已有可复用的服务端分页范式：`SystemLogReadService` 用 `qw.last("LIMIT " + safeSize + " OFFSET " + offset)` + 独立 count，返回 `{ items, total, page, size }`；`ContractMapper` 用 `LIMIT #{limit} OFFSET #{offset}`。技术栈为 MyBatis-Plus + 真 MySQL 8.4（集成测试用 Testcontainers，禁 H2/mock）。

## Goals / Non-Goals

**Goals:**
- 线索（mine/all/pool）与客户列表改服务端分页，返回 `{ items, total, page, size }`，移除 50 硬上限。
- 列表"搜索"下推后端：keyword 对全表匹配后分页（非客户端过滤当前页）。
- `POST /api/leads` 支持 `newCustomer{name,usci}`，事务内按 USCI find-or-create 客户再建线索。
- 工作台今日提醒下推后端：新增"我的长期未跟踪线索"查询；建议认领取公海端点；tab 计数取 `total`。

**Non-Goals:**
- 不引入虚拟滚动、游标分页；用经典 `page`/`size` + `total`。
- 不新增按阶段/业务类型的下拉筛选（仅 keyword 全文式子串）。
- 不改客户主体可编辑/可删除（沿用 customer spec：不可改不可删）。
- 不改四项看板指标（dashboard capability）口径。
- 不引入 MyBatis-Plus 分页拦截器（保持与 system-log/contract 一致的手写 LIMIT/OFFSET + count）。

## Decisions

### D1 — 分页信封与契约：复用 `{ items, total, page, size }`
沿用 system-log/contract 已落地的信封，前端 `a-pagination` 服务端驱动。参数：`page` 默认 1、最小 1；`size` 默认 20、clamp 到 `[1,100]`（超界裁剪，防止 `size=100000` 拖垮库）；`keyword` 选填、trim 后空视为无。`total` 为**含 keyword 过滤后**的命中总数。
- 备选：MyBatis-Plus `IPage`/分页插件——更省样板，但需引入全局拦截器、与既有两处手写范式不一致，增加认知与回归面。否决。

### D2 — keyword 下推：service 层 QueryWrapper + 全表匹配
- 客户：`name LIKE %kw% OR usci LIKE %kw%`（沿用 customer spec 子串语义）。
- 线索：keyword 需匹配**关联客户名/USCI**，而 lead 表只有 `customer_id`——需 join customer。决策：在 lead 列表查询里 LEFT JOIN customer，`customerName LIKE %kw% OR customerUsci LIKE %kw% OR lead.contact_name LIKE %kw%`。**不**匹配 `contact_phone`（公海对 SALES 脱敏，按明文搜索会泄漏 + 口径不一致）。
- count 与 data 用同一套 where 条件，避免 total 与 items 不一致。

### D3 — find-or-create：单事务 + USCI 强键仲裁
`POST /api/leads` 的 `newCustomer` 路径在 `@Transactional` 内：归一化+校验 USCI → 按归一化 USCI 查既有客户 →（命中且同名=复用 / 命中异名=`DUPLICATE_CUSTOMER` / 未命中=建客户）→ 继续既有查重三元组 + 建线索。任一步失败整事务回滚，**无孤儿客户**。
- 并发同 USCI：先查后插存在竞态窗口，依赖 customer 表 USCI 唯一约束兜底——捕获唯一约束冲突后回退为"按 USCI 重查并复用"（catch-then-find），对外仍返回成功或一致的业务码，不漏 SQL 异常。
- 入参契约：`customerId` 与 `newCustomer` 恰择其一；同缺/同提供 → `VALIDATION_ERROR`。
- 备选：前端两步提交（先 POST /customers 再建线索）——非原子、易留孤儿客户、错误回显割裂。否决（已与用户确认走后端合并端点）。

### D4 — 提醒下推：新增"我的长期未跟踪线索"端点
"长期未跟踪"需扫全量名下线索，分页页内算不准 → 新增只读端点：名下 + 未结束 + (`lastTrackedAt < now-阈值` 或 NULL)，按 `lastTrackedAt` 升序、取前 N。阈值天数后端集中常量（与现 `utils/workbench.ts` 客户端阈值对齐迁移）。"建议认领"复用 `GET /api/leads/pool` 首页即可，不另起端点。

### D5 — 前端 BREAKING 重构：store 持分页态
`api/leads.ts`/`api/customers.ts` 返回类型由数组改信封；`stores/leads.ts` 的 `myLeads/allLeads/pool` 由 `LeadView[]` 改为 `{ items, total, page, size }` 形态（或拆出 page/total ref）。`MyLeadsView`/`PublicPoolView`/`CustomersView`/`DashboardView` 改服务端分页：翻页/改 keyword（debounce）→ 重新请求；移除客户端 `slice`/`filter`；换 keyword 重置 page=1。`DashboardView` tab 计数取 `total`，提醒改调后端端点。

### D6 — 分阶段实施（tasks 顺序）
① 后端分页+keyword（4 端点 + 信封 + 移除 50 上限）→ ② 三个列表页改服务端分页+搜索 → ③ find-or-create 合并端点 + 前端内联建客户 → ④ Dashboard 全量改造（分页表 + total 计数 + 提醒端点）。每阶段独立 Red-Green、独立可验证。

## Risks / Trade-offs

- **响应形状破坏式变更** → 一次性改齐所有消费方（store/视图/测试），并用 TS 类型把改造点逼出来（编译失败即未改完）；MSW handler 与组件测试同步更新。
- **lead 列表 keyword 需 join customer** → 控制在 service 查询层，复用既有 LeftJoin（lead 视图本就内联 customerName/usci），避免 N+1。
- **count 查询成本** → MVP 数据量可接受；分页 where 与 count 共用条件，必要时加索引（customer.name / lead.customer_id 已有 FK 索引）。
- **find-or-create 并发竞态** → 唯一约束兜底 + catch-then-find，不靠纯应用层 check-then-insert。
- **提醒阈值双处定义漂移** → 阈值迁到后端单一来源，前端不再持有该常量。
- **Dashboard 与列表页共用 store** → store 状态结构调整需同时满足两处；以 store 单测 + 两处视图测试共同把关。

## Migration Plan

- 无 DB schema 变更（纯查询层 LIMIT/OFFSET + COUNT）。
- 后端先上分页端点（兼容旧前端？否——响应形状变了，须前后端同 change 落地，按 D6 阶段在同一分支内推进，不分期发布）。
- 回滚：本 change 为单一逻辑单元，回滚即整体 revert 该分支提交。

## Open Questions

- 默认 `size` 取 20（与 system-log/contract 一致）还是 10（与现前端表格密度一致）？倾向 20，前端表格可请求自身期望的 size。
- "长期未跟踪"阈值天数取值（现 `utils/workbench.ts` 的既有阈值）在 apply 时以代码现值为准迁移。
- 建议认领提醒取公海前几条的 N 值（展示用小常量，apply 时定）。
