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

- [ ] 2.1 改 `api/customers.ts` / `api/leads.ts`：`searchCustomers` / `fetchMyLeads` / `fetchAllLeads` / `fetchPool` 入参加 `{page,size,keyword}`、返回类型改分页信封；TS 编译失败点即待改消费方清单
- [ ] 2.2 改 `stores/leads.ts`：`myLeads/allLeads/pool` 改为持 `{items,total,page,size}`（或 items+total+page ref）；load 动作接受分页参数；更新 `stores/leads.spec.ts`
- [ ] 2.3 【Red】更新/新增 `CustomersView.spec`：服务端分页（翻页发新请求）、keyword 下推后端、换 keyword 回第 1 页、MSW 返回信封——运行并贴出失败输出
- [ ] 2.4 改 `CustomersView.vue`：移除客户端 `slice`，改服务端分页 + 后端搜索（debounce keyword 发后端、`watch(keyword)` 重置 page=1），用 `total` 渲染分页；跑绿 2.3
- [ ] 2.5 【Red】更新 `MyLeadsView.spec` / `PublicPoolView.spec`：移除"客户端 filter"断言，改服务端分页 + keyword 下推；MSW 信封——运行并贴出失败输出
- [ ] 2.6 改 `MyLeadsView.vue` / `PublicPoolView.vue`：移除客户端 `filteredRows`/`slice`，keyword + page 下推后端，`total` 驱动分页；跑绿 2.5
- [ ] 2.7 跑前端 `npm run test:unit` + `npm run type-check`，确认阶段二全绿

## 3. 阶段三：find-or-create 合并端点 + 前端内联建客户

- [ ] 3.1 【Red】写 `LeadCreateInlineCustomerTest`（真 MySQL）：`newCustomer` USCI 未命中→建客户+线索；命中同名→复用、客户行数不增；命中异名→`DUPLICATE_CUSTOMER`；线索阶段失败→事务回滚无孤儿客户；`customerId` 与 `newCustomer` 同缺/同提供→`VALIDATION_ERROR`；并发同 USCI 唯一约束兜底——运行并贴出失败输出
- [ ] 3.2 改建线索入参 DTO + `LeadService`：接受 `customerId | newCustomer{name,usci}`（恰择其一）；`@Transactional` 内归一化+校验 USCI、按 USCI find-or-create（同名复用/异名 DUPLICATE_CUSTOMER/未命中建）、catch 唯一约束冲突回退 find，再走既有查重+建线索；成功仍发 `LEAD_CREATE`、建客户不发日志；跑绿 3.1
- [ ] 3.3 【Red】更新 `CustomerSelect.spec` / `CreateLeadModal.spec`：候选为空时出现"录入新客户（name+USCI）"入口；提交携带 `newCustomer`；后端 `DUPLICATE_CUSTOMER`/`VALIDATION_ERROR` 回显且不暴露校验位位置——运行并贴出失败输出
- [ ] 3.4 改 `CustomerSelect.vue` / `CreateLeadModal.vue` / `api/leads.ts`：搜不到给内联建客户表单（name+USCI），新客户路径不发查重预检，提交 `newCustomer`，错误语义回显；跑绿 3.3
- [ ] 3.5 后端 `mvn verify` + 前端 test:unit/type-check，确认阶段三全绿

## 4. 阶段四：Dashboard 全量改造（分页表 + total 计数 + 提醒下推）

- [ ] 4.1 【Red】写"我的长期未跟踪线索"端点测试（真 MySQL）：名下+未结束+(lastTrackedAt<阈值 或 NULL) 命中、阈值内/已结束排除、按 lastTrackedAt 升序取前 N、无持久化副作用——运行并贴出失败输出
- [ ] 4.2 实现该只读端点（阈值后端常量，迁移自现 `utils/workbench.ts` 阈值）+ `LeadController` 暴露；跑绿 4.1
- [ ] 4.3 【Red】更新 `DashboardView.spec`：工作区表服务端分页、tab 计数取 `total`、今日提醒改调后端（公海首页 + stale 端点）、长期未跟踪不受当前页码限制——运行并贴出失败输出
- [ ] 4.4 改 `DashboardView.vue` + `utils/workbench.ts` + `api/leads.ts`：内嵌表服务端分页、tab 计数用 `total`、"建议认领"取公海首页、"长期未跟踪"调新端点；移除客户端阈值派生；跑绿 4.3
- [ ] 4.5 前端 test:unit + type-check 全绿

## 5. 收尾与验证

- [ ] 5.1 后端 `mvn verify` 全量 + 前端 `npm run test:unit` + `npm run type-check` 全绿，无既有契约回归
- [ ] 5.2 手动端到端：>50 数据下列表翻页可达全部、搜索命中靠后记录；新建线索搜不到→录入新客户成功建单；同 USCI 异名被拒；工作台 tab 计数与提醒跨全量准确
- [ ] 5.3 写 `qa/qa-report.md`（vibe-coding-qa 模板），记录分层测试结果、Red 证据、剩余风险
- [ ] 5.4 `openspec validate paginated-lists-and-inline-customer-create --strict` 通过，准备归档
