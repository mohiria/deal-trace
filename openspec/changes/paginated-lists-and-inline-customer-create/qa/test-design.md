# 测试设计（轻量）— paginated-lists-and-inline-customer-create

> 依据 vibe-coding-qa：分层就近、API/集成用真 MySQL 8.4（禁 H2/mock）、前端单元/组件用 vitest+MSW。
> 本文件在该能力第一行生产代码之前存在；逐 task 的 Red 证据贴在对应 task 完成记录/qa-report。

## 1. 范围与风险

- 4 个只读端点（leads/mine、leads、leads/pool、customers）由"裸数组 + 50 硬上限"改为"分页信封 + keyword 全表下推"——**破坏式响应形状**，回归面 = 所有既有列表/搜索断言。
- `POST /leads` 接 `newCustomer` 走事务内 find-or-create——并发竞态、孤儿客户、USCI 仲裁是高风险点。
- 工作台提醒下推后端（长期未跟踪查询）+ tab 计数取 total。
- 前端 store/视图破坏式重构。

## 2. 后端测试分层（真 MySQL，@Transactional @Rollback；多事务并发用例避免，防偷 bootstrap admin）

### 2.1 分页信封契约（customer / leads.mine / leads / leads.pool）
- 无 keyword：`data.items` 为当页（≤size，created_at 倒序）、`data.total` = 全集数、`data.page/size` 回显。
- 翻页：N=25、page=2&size=20 → items=5、total=25；page=3 与 page=1 无交集。
- size clamp：size>100 → 实际裁剪为 100；page<1 → 视为 1；size 缺省=20。
- keyword 全表匹配后分页：命中项排在靠后分页位（如第 55 顺位）时，keyword 查询仍能在 page=1 命中，且 total=命中集合规模（非全表）。
- 空/空白 keyword 等价无 keyword；无命中 → items=[]、total=0、HTTP200 非 404/null。

### 2.2 权限/语义保持（回归）
- `GET /leads` 仅 ADMIN，SALES → 403 FORBIDDEN。
- `GET /leads/mine` 仅返回归属调用者的线索（分页信封下不变）。
- `GET /leads/pool` 仅未结束 + 无归属；SALES 脱敏电话 / ADMIN 明文（信封下不变）；keyword 不匹配 contact_phone。
- 所有列表查询无持久化副作用、不写 system_log。

### 2.3 keyword 字段
- leads：LEFT JOIN customer，匹配 customerName / customerUsci / lead.contact_name（**不**含 contact_phone）。
- customer：name / usci 子串。

### 2.4 find-or-create（POST /leads，newCustomer）
- 入参互斥：customerId 与 newCustomer 同缺/同提供 → VALIDATION_ERROR，无行新增。
- USCI 未命中 → 建客户(+1) + 建线索(+1)，线索 customer_id 指向新客户。
- USCI 命中且同名 → 复用，客户行数不变、线索 customer_id = 既有。
- USCI 命中异名 → DUPLICATE_CUSTOMER，客户/线索均不变。
- 线索阶段失败（查重拦截 / 电话非法）→ 事务回滚，客户与线索行数均与请求前一致（无孤儿客户）。
- USCI 非法 → VALIDATION_ERROR，无行新增。
- newCustomer 成功仍发 1 条 LEAD_CREATE；建客户本身不发 system_log。
- 并发同 USCI（谨慎设计，避免污染共享库）：唯一约束兜底，至多一个 INSERT，另一个回退 find；不返回 INTERNAL_ERROR。

### 2.5 长期未跟踪查询
- 名下 + 未结束 + (lastTrackedAt<阈值 或 NULL) 命中；阈值内 / 已结束排除；按 lastTrackedAt 升序取前 N；无持久化副作用。

## 3. 前端测试（vitest + MSW，信封 mock）

- `stores/leads` / `api/*`：load 接受 {page,size,keyword}，状态持信封。
- CustomersView / MyLeadsView / PublicPoolView：服务端分页（翻页发新请求）、keyword 下推后端（非客户端 filter）、换 keyword 回 page=1、total 驱动分页控件。
- CustomerSelect / CreateLeadModal：候选为空出现"录入新客户(name+USCI)"入口；提交携带 newCustomer；DUPLICATE_CUSTOMER/VALIDATION_ERROR 回显且不暴露校验位位置；新客户路径不发查重预检。
- DashboardView：工作区服务端分页、tab 计数取 total、提醒改调后端端点（公海首页 + 长期未跟踪）。
- 既有断言迁移：原断言"裸数组 data[]"/"客户端 filter"的用例改为信封 + 服务端分页（声明依据：本 change 的 spec MODIFIED，非削弱断言）。

## 4. 非 TDD 例外

- E2E 手动验收（>50 数据翻页/搜索、内联建客户、tab 计数/提醒跨全量）记为场景级验证，不强制 Red-Green。
