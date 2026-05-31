# 回归影响分析：refine-workbench-drawer-owner-and-pool-masking

## 影响范围

- 后端 DTO/服务：`lead/dto/LeadView.java`（新增 ownerSalesName）、`LeadService`（归属姓名解析 + 批量映射）、`LeadController`（create/detail/toView/toViews 注入姓名）。
- 后端只读端点：`GET /leads/{id}`、`/leads/mine`、`/leads`，及各写动作返回的 LeadView（claim/release/assign/recall/transfer/win/lose/stage）。
- 前端：`api/leads.ts`（类型补 ownerSalesName）、`stores/leads.ts`（loadLead 失败清空）、`views/DashboardView.vue`（归属姓名、公海抽屉脱敏分流、切换刷新）、`components/LeadDetailPanel.vue`（当前归属姓名、公海只读摘要）。

## 需要保留的旧行为

- `detailFor` 对 SALES 看其他 SALES 私海线索仍返回 `NOT_FOUND`（404），本次不放宽。
- 公海列表 `/leads/pool` 经 PhoneMasker 对 SALES 脱敏、ADMIN 明文，端点划分不变。
- ADMIN 全局可见、明文；名下线索明文详情、写入口与闭单只读规则不变。
- LeadView 既有 14 字段 + customerName/customerUsci 内联语义不变；不返回 progressLogs/systemLogs。
- 认领事务语义（LEAD_ALREADY_CLAIMED）、归属写动作、阶段/赢单/流失校验不变。

## 回归测试范围

- 后端：`LeadControllerDetailListTest`（详情/列表，补 ownerSalesName 断言）、`LeadClaimTest`/`LeadAssignTest`/`LeadRecallTest`/`LeadTransferTest`/`LeadReleaseTest`/`LeadWinTest`/`LeadLoseTest`/`LeadStageChangeTest`（返回 LeadView 不回归）、`LeadPoolListTest`/`PhoneMaskerTest`（脱敏不变）。
- 前端：`DashboardView.spec.ts`、`LeadDetailView.spec.ts`、`LeadDetailPanel.spec.ts`（如有）、`leads` store 测试，及全量 `pnpm test:unit` 回归。

## 执行约束

- 后端集成测试用真 MySQL 8.4（test profile 复用同一远程 dealtrace 实例，无 datasource override）。
- 运行后端测试前 **停掉 8080 的 dev 后端**（项目记忆：dev backend smoke 与 mvn verify 不可并发，多事务测试 delete/TRUNCATE 会偷 bootstrap admin）。测后按需重启。

## 执行记录

- Red 证据：
  - 前端 5 条新测试先红：归属显示 `销售 #7`（非姓名）；公海抽屉显示明文 `13912344321` 且调用了详情端点；公海抽屉无认领入口；名下→公海切换残留 `名下客户甲`；loadLead 失败后 currentLead 未清空。失败原因均为预期行为差距。
  - 后端集成 Red 被环境阻断：`mvn -Dtest=LeadControllerDetailListTest test` 11/11 ERROR，根因 `accountMapper.delete(null)` 撞 `fk_contract_sales`（远程库存在 contract 行引用 account）——属环境/数据污染失败，非干净行为 Red。改以单元测试建立 Red→Green。
- Green 证据：
  - 前端：受影响 33/33；全量 `pnpm test:unit` 23 files / 210 tests PASS；`vue-tsc -b` 通过。
  - 后端：`LeadServiceOwnerNameTest` 5/5 PASS；`mvn ... test` BUILD SUCCESS（全模块编译通过）。
- 未覆盖项：
  - 后端 detail/mine/list 端到端 ownerSalesName 断言（集成层）——因远程库污染阻断，待清 contract/lead 联调数据后执行；解析逻辑已由单元测试覆盖。
  - 抽屉只读摘要纯视觉排版（非 TDD 例外，浏览器 smoke 验证）。
