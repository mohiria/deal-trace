# Lightweight Test Design — contract-records-view

## Context

- Requirement / Spec：`openspec/changes/contract-records-view/specs/contract-view/spec.md`（R1 ADMIN 全量分页+筛选 / R2 SALES 收窄本人 / R3 展示组装 / R4 纯只读）；PRD §4 角色表、§11.10「Admin 可查看全部合同记录」。
- Change summary：新增合同记录只读浏览能力 `contract-view`，后端 `GET /contracts`（角色化可见范围）+ 前端将 `/contracts` 占位页替换为真实列表页。
- Target modules / APIs / pages：`ContractMapper.selectPage/countPage`、`ContractReadService`、`ContractController`（`GET /contracts`）；`frontend ContractsView.vue` + `api/contracts.ts`。
- Test environment / constraints：集成测试用真 MySQL 8.4（tech-arch §12，env `DB_HOST` 指向共享 `dealtrace` 实例）；`@Transactional @Rollback`、禁 TRUNCATE（[[no-truncate-in-rollback-tests]]）；与 dev smoke 不可并发（[[smoke-vs-mvn-verify-share-db]]）。前端 vitest + msw。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria
- [x] Existing behavior baseline：写侧 `contract` spec、`V6__contract.sql`、`SystemLogReadService`/`AdminSystemLogController` 读侧约定
- [x] Data model / field rules：contract（lead_id 唯一、deal_sales_id 可空）JOIN lead/customer/account
- [x] API contract / auth rules / error shape：`/contracts` 走 `anyRequest().authenticated()`，匿名 401 UNAUTHORIZED
- [x] UI states / user roles：ADMIN（全量 + 成交销售筛选）/ SALES（仅本人，无成交销售筛选）
- [x] 既有测试镜像：`AdminSystemLogListTest`（全局分页隔离哲学）、`LeadWinTest`（合同造数）
- [x] Test data / mocks：唯一客户名 keyword 隔离；前端 msw `*/api/contracts`、`*/api/admin/accounts`

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 合同记录可被浏览 | 写侧 `contract` 仅生成、无读端点 | PRD §4/§11.10 + 新建 `contract-view` spec | extends（新增读侧，不改写侧） | PRD | Proceed |
| Sales 可见自己成交合同 | PRD 仅明确 Admin | 用户确认（propose 阶段问答） | extends（范围扩张，按 deal_sales 事件归属收窄） | 用户 | Proceed |

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| JOIN 取客户名/业务类型/成交销售当前姓名，created_at 倒序 | R1/R3 | 场景 | API/集成 | 3 合同（同客户、不同 created_at/deal_sales） | 倒序、字段正确、金额 String 标度 | `ContractRecordsViewMapperTest#selectPage_byKeyword_descWithJoinedFields` | P0 | ✅ |
| 成交销售等值筛选 | R1 | 等价类 | API/集成 | dealSalesId=s7 | 仅 s7 的合同 | `…#selectPage_filterByDealSales` | P0 | ✅ |
| 签订日期闭区间（含端点、排除界外） | R1 | 边界值 | API/集成 | [05-01,05-31]，数据 04-30/05-10/06-01 | 仅 05-10 | `…#selectPage_filterBySignedDateClosedInterval` | P0 | ✅ |
| count 与筛选一致 | R1 | 场景 | API/集成 | 同上三组 | 3 / 1 / 1 | `…#countPage_matchesFilters` | P1 | ✅ |
| 匿名 401 | R1 | 反例 | API | 无 token | 401 UNAUTHORIZED、data 空 | `ContractControllerListTest#anonymous_unauthorized` | P0 | ✅ |
| ADMIN 全量倒序 + 公海赢单展示 + 金额精确 | R1/R3 | 场景 | API | admin token, keyword | 3 条，首条公海赢单，末条 120000.50 + s7 姓名 | `…#admin_listsAllByKeyword_descWithPoolDealLabel` | P0 | ✅ |
| ADMIN 成交销售/日期筛选 | R1 | 等价类/边界 | API | admin + dealSalesId / 日期区间 | 各 1 条 | `…#admin_filterByDealSales` / `…#admin_filterBySignedDateRange` | P0 | ✅ |
| SALES 仅本人（排除他人+公海） | R2 | 反例 | API | s7 token, keyword | 仅 s7 的 1 条 | `…#sales_seesOnlyOwn` | P0 | ✅ |
| SALES 传他人 dealSalesId 仍收窄本人 | R2 | 反例（越权） | API | s7 token + dealSalesId=s8 | 仍仅 s7 | `…#sales_otherDealSalesParamNarrowedToSelf` | P0 | ✅ |
| 前端列渲染 + 金额千分位 + 公海赢单 | R3 | 场景 | 前端单测 | msw 返回行 | 列文本/千分位/“公海赢单” | `ContractsView.spec.ts` 多用例 | P1 | ✅ |
| 前端筛选触发带参重载 | R1 | 场景 | 前端单测 | 驱动 keyword/日期 ref | URL 带 keyword/signedDate* | `ContractsView.spec.ts` | P1 | ✅ |
| 成交销售筛选仅 ADMIN 可见 | R1/R2 | 场景 | 前端单测 | admin vs sales 挂载 | filter 仅 admin 存在 | `ContractsView.spec.ts` | P1 | ✅ |
| 纯只读（无写入口） | R4 | 设计审查 | 审查 | controller 仅 GET | 无 POST/PUT/PATCH/DELETE | `ContractController` 代码审查 | P1 | ✅（代码层无写方法） |

## Test Data Plan

| Test point / scenario | Required data state | Business realism basis | Setup method | Isolation strategy | Cleanup method | Data blocker status |
| --- | --- | --- | --- | --- | --- | --- |
| Mapper/API 集成 | 同客户下 3 线索 3 合同（s7/s8/公海），不同 created_at/signed_date | 合同由赢单生成、deal_sales 为赢单时归属、公海赢单 deal_sales 为 NULL（contract spec） | mapper.insert 账号/客户/线索 + jdbc 插合同（控 created_at 与 NULL） | 唯一客户名 keyword（UUID 片）；唯一邮箱/USCI | `@Rollback` 回滚；不 DELETE/TRUNCATE | Ready |
| 前端单测 | msw 返回合同行/分页/账号列表 | 镜像后端 `ContractRowView`/`ContractPageView` | msw `server.use` 工厂 | 每用例独立 pinia + handler | msw resetHandlers | Ready |

## TDD Candidates

- Mapper：先 Red（`selectPage`/`countPage` 不存在 → 测试引用编译失败为阻塞；以"行为驱动 SQL"实现转 Green）。
- Service 权限收窄：以 API 集成用例「SALES 传他人 id 仍收窄」作为权威断言（端到端覆盖 service 分流）。
- 前端：先写渲染/筛选/可见性用例，再实现 `ContractsView`。
