## 0. 准备与测试设计

- [x] 0.1 在 `openspec/changes/paginated-lists-and-inline-customer-create/qa/` 用 vibe-coding-qa 模板建 `test-design.md`，覆盖：分页信封契约、keyword 全表匹配、find-or-create USCI 仲裁、stale 提醒、前端服务端分页与内联建客户的轻量测试设计（须在该能力第一行生产代码之前存在）
- [x] 0.2 定位可复用的分页信封 DTO 与范式（`SystemLogReadService` / `ContractMapper` 的 `{items,total,page,size}` + `LIMIT/OFFSET` + count），记录复用点；确认无需引入 MyBatis-Plus 分页拦截器 —— 复用为泛型 `common/PageView<T>`；clamp 工具 `common/PageQuery`
- [x] 0.3 约定分页参数解析与 clamp 工具（page≥1、size 默认 20 且 clamp 到 [1,100]、keyword trim 后空视为无），后端各端点共用 —— `common/PageQuery` + `PageQueryTest`（5/5 绿）

## 1. 阶段一：后端分页 + keyword 下推（lead mine/all/pool + customer）

- [x] 1.1 【Red】写 `CustomerControllerSearchTest` 新断言：`GET /api/customers?page/size` 返回 `{items,total,page,size}`、60 行下 total=60、翻页可取首页外、keyword 跨全表命中靠后客户、空 keyword 与无命中（total=0）——Red 证据：6/7 断言级失败 `No value at JSON path "$.data.items"`（data 为裸数组）
- [x] 1.2 改 `CustomerService` / `CustomerController`：移除 `SEARCH_LIMIT=50`，按 page/size 切页 + count，keyword `name LIKE OR usci LIKE` 全表匹配后分页，返回信封；跑绿 1.1（7/7 绿）
- [x] 1.3 【Red】写 `LeadMineListTest` / `LeadAdminListTest`（或扩展既有）：`GET /api/leads/mine`、`GET /api/leads` 返回信封、25 行 page=2&size=20 余 5、keyword 跨全量命中（含 join customer 名/USCI、联系人）、Admin/Sales 403 边界保持——Red：7 断言级失败（扩 `LeadControllerDetailListTest` 3 处 + 新 `LeadListPaginationTest` 4 处，data 仍裸数组）
- [x] 1.4 改 `LeadService` / `LeadController`：mine/all 移除 50 上限，加 page/size/keyword，keyword 跨表匹配（客户名/USCI 参数化 IN + 联系人 LIKE，不含 contact_phone）+ count，返回信封；保持仅 Admin、Sales 403；跑绿（5/5 + 11/11）
- [x] 1.5 【Red】写/扩 `LeadPool*Test`：`GET /api/leads/pool` 返回信封、仅未结束无归属、SALES 脱敏/ADMIN 明文保持、keyword 跨全量公海命中、无持久化副作用——Red：4 断言级失败（data 仍裸数组）
- [x] 1.6 改 `LeadOwnershipService` 公海查询：移除 `POOL_LIMIT=50`，加 page/size/keyword（不匹配 contact_phone）+ count，返回信封；跑绿（LeadPoolListTest 5/5，并迁移 LeadPoolOtherLeadsHintTest 断言到 items）
- [x] 1.7 跑后端 `mvn verify`（真 MySQL 8.4，注意与 dev smoke 不并发），确认阶段一全绿、无既有回归 —— BUILD SUCCESS（exit 0），全量无回归

## 2. 阶段二：前端列表页改服务端分页 + 搜索下推

- [x] 2.1 改 `api/customers.ts` / `api/leads.ts`：`searchCustomers` / `fetchMyLeads` / `fetchAllLeads` / `fetchPool` 入参加 `{page,size,keyword}`、返回类型改分页信封；新增 `api/pagination.ts`（`PageResult`/`PageQuery`/`pageParams`）
- [x] 2.2 改 `stores/leads.ts`：`myLeads/allLeads/pool` 保持 items 数组 + 新增 `myLeadsTotal/allLeadsTotal/poolTotal`；load 动作接受 `PageQuery` 并写 items+total
- [x] 2.3 更新 `CustomersView.spec`：服务端分页（翻页发新请求）、keyword 下推后端、换 keyword 回第 1 页、MSW 返回信封
- [x] 2.4 改 `CustomersView.vue`：移除客户端 `slice`，改服务端分页 + 后端搜索（debounce keyword 发后端、换 keyword 重置 page=1），用 `total` 渲染分页；跑绿 2.3
- [x] 2.5 更新 `MyLeadsView.spec` / `PublicPoolView.spec`：移除"客户端 filter"断言，改服务端分页 + keyword 下推；MSW 信封
- [x] 2.6 改 `MyLeadsView.vue` / `PublicPoolView.vue`：移除客户端 `filteredRows`/`slice`，keyword + page 下推后端，`total` 驱动分页；跑绿 2.5
- [x] 2.7 跑前端 `npm run test:unit`（237/237 绿）+ `vue-tsc -b` 类型检查（0 错误），确认阶段二全绿

## 3. 阶段三：find-or-create 合并端点 + 前端内联建客户

- [x] 3.1 写 `LeadCreateInlineCustomerTest`（真 MySQL，8 用例）：Red 证据 = 5 断言级失败（status 200↔400 / `$.code` DUPLICATE_CUSTOMER|DUPLICATE_ACTIVE_LEAD 实得 VALIDATION_ERROR）；覆盖未命中建、同名复用、异名拒、非法 USCI、电话非法无孤儿、二者同缺/同提供拒、复用后查重拦截
- [x] 3.2 改建线索入参 DTO（加 `NewCustomer{name,usci}`）+ `LeadService`：`resolveCustomer` 互斥校验 + `CustomerService.findOrCreate`（同事务内 USCI 归一化+校验、同名复用/异名 DUPLICATE_CUSTOMER/未命中建、catch 唯一约束回退 find）；字段校验先于建客户保证无孤儿；跑绿 3.1（8/8），LeadControllerCreateTest 15/15 无回归
- [x] 3.3 更新 `CustomerSelect.spec`（反转旧"无捷径"负例，声明本 change spec MODIFIED 依据）+ `CreateLeadModal.spec`：候选为空出现 `.cs-create-new` 入口、名称+USCI 录入框、提交携带 `newCustomer` 且不发查重预检、DUPLICATE_CUSTOMER 展示语义且不暴露校验位位置
- [x] 3.4 改 `CustomerSelect.vue`（加 `v-model:newCustomer` + 录入态）/ `CreateLeadModal.vue`（newCustomer 路径跳过预检、DUPLICATE_CUSTOMER 语义回显）/ `api/leads.ts`（`customerId` 改选填 + `newCustomer`）；跑绿 3.3
- [x] 3.5 前端 `npx vitest run`（241/241 绿，含新增 4 例）+ `vue-tsc -b`（0 错误）；后端验证并入 5.1 全量 mvn verify（Phase 3 后端 8/8 + 15/15 已绿）

## 4. 阶段四：Dashboard 全量改造（分页表 + total 计数 + 提醒下推）

- [x] 4.1 写 `LeadStaleListTest`（真 MySQL，2 用例）：Red 证据 = status 200 vs 500（端点未实现）；覆盖名下+未结束+(lastTrackedAt<阈值 或 NULL) 命中、阈值内/已结束/他人名下排除、NULL 视为最久升序在前、无持久化副作用
- [x] 4.2 实现 `GET /leads/mine/stale`（`LeadService.staleOwned`，阈值后端常量 STALE_TRACK_DAYS=7、上限 STALE_LIMIT=5、notIn 已结束、isNull OR lt cutoff、orderByAsc NULL 先）+ `LeadController.mineStale`；跑绿 4.1（2/2）
- [x] 4.3 更新 `DashboardView.spec`（spec MODIFIED：归属/公海双 Tab、无 SALES 合并全部 Tab、移除业务类型/阶段筛选）：服务端分页、tab 计数取 total、keyword 下推、提醒区块（建议认领=公海首页/长期未跟踪=stale 端点）、ADMIN 无认领入口
- [x] 4.4 改 `DashboardView.vue`（双 Tab 服务端分页 + keyword 下推 + total 计数 + 提醒区块）/ `api/leads.ts`（`fetchStaleLeads`）/ `stores/leads.ts`（`staleLeads`/`loadStaleLeads`）；删除已不再使用且与 spec「前端不重算阈值」相悖的 `utils/workbench.ts`(+test)；跑绿 4.3（24/24）
- [x] 4.5 前端 `npx vitest run`（231/231 绿）+ `vue-tsc -b`（0 错误）全绿

## 5. 收尾与验证

- [x] 5.1 后端 `mvn verify` 全量（311 tests / 0 失败 / 0 错误，BUILD SUCCESS）+ 前端 `npx vitest run`（231/231）+ `vue-tsc -b`（0 错误）全绿，无既有契约回归
- [x] 5.2 端到端（Playwright 真后端当前构建 + 真 MySQL，admin+sales）：10 passed / 0 failed / 0 skipped。E2E 暴露并修复 1 个代码缺陷（整页全公海线索列表 NPE→500，已加回归用例）+ 修正 4 处陈旧/脆弱 E2E 断言（`:visible`、归属姓名、`workbench-reminders` 常驻）。详见 qa-report「缺陷与失败分析」
- [x] 5.3 写 `qa/qa-report.md`（vibe-coding-qa 模板）：记录需求权威/冲突评审、各能力 Red 证据、分层测试结果、回归范围、剩余风险
- [x] 5.4 `openspec validate paginated-lists-and-inline-customer-create --strict` 通过（"Change ... is valid"）
