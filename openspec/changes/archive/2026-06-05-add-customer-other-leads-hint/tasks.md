## 1. QA 设计（第一行生产代码之前）

- [x] 1.1 用 `vibe-coding-qa` 模板产出 `qa/test-design.md`：按 spec 五条 Requirement 列轻量测试点，明确分层（service 单元裁剪逻辑 / API 集成 Admin·SALES·他人三视角 / 公海布尔聚合 / 前端组件呈现），并标注 §7.6.5 越权负例为必测项。
- [x] 1.2 确认测试编排沿用 scaffold `design.md` 的真 MySQL 8.4 基类与隔离策略；记录「dev smoke 与 mvn verify 不可并发」前置约束。

## 2. 后端：其他业务线索详情查询（service + DTO + 端点）

- [x] 2.1 写 Red：新增 `LeadOtherLeadsServiceTest`，断言 ADMIN 入参得到他人名下其他业务类型线索的 `{businessType, ownerSalesName, stage}`；先建最小可编译桩（service 方法返回空）拿到断言级失败，贴出 `expected ... but got ...` 运行输出。【Red：Tests run 2, Failures 2，AssertionFailedError containsExactly 期望非空 but empty】
- [x] 2.2 实现 `LeadOtherLeadView { businessType, ownerSalesName, stage }` DTO（结构上不含 contactPhone / 进度字段）与查询：`customer_id = ? AND business_type <> ? AND stage <> '已流失'`，跨年度、保留进行中+已赢单（design D3）；ownerSalesName 经 `AccountMapper` 解析，公海显示「公海」（复用 `ownerLabel` 同款逻辑）。转绿。【Green：Tests run 2, Failures 0】
- [x] 2.3 写 Red：断言 SALES 调用时仅返回 `owner_sales_id == 调用者` 的其他线索，他人/公海线索不出现（§7.6.5/§7.6.6）；运行得失败输出。【与 2.1 同批 Red：sales_seesOnlyOwnOtherLeads 失败】
- [x] 2.4 实现 service 内按角色裁剪（SALES 先按 owner 过滤再映射）。转绿。【Green】
- [x] 2.5 写 Red：`LeadOtherLeadsControllerTest`（API/集成，真 MySQL）断言 `GET /leads/customer-other-leads?customerId=&excludeBusinessType=` 端点存在且返回上述裁剪结果；缺端点应 404 → 作为 Red 前提，建桩后转断言级 Red。【Red：端点桩返回空，expected:<4> but was:<0> / expected:<2> but was:<0>，匿名 401 已通过】
- [x] 2.6 在 `LeadController` 暴露端点（authenticated，角色裁剪在 service，依 design D4），接入 service。转绿。【Green：Tests run 3, Failures 0；含 SALES 不泄漏负例 + 排除已流失/保留已赢单跨年度谓词】

## 3. 后端：公海列表「其他业务线索」布尔

- [x] 3.1 写 Red：扩展 `LeadOwnershipServiceTest`/公海集成测试，断言 `PoolLeadView.customerHasOtherLeads` 对「客户有其他业务类型线索」的行为 true、无则 false；先加字段桩（恒 false）拿断言级 Red。【新增 `LeadPoolOtherLeadsHintTest`，Red：true 用例 Expected iterable containing [<true>] but [<false>]，false/不泄漏用例对桩已通过】
- [x] 3.2 在 `listPool` 现有批量装载里以一次 `IN (customerIds)` 聚合判断是否存在不同业务类型线索，填充 `customerHasOtherLeads`（design D1 备选已否决 N+1）。转绿。【Green：Tests run 3, Failures 0】
- [x] 3.3 写 Red+转绿：断言公海响应**不含**任何其他业务线索的类型/归属/阶段/联系方式（仅布尔，§7.6.4）。【`poolRow_noOtherLeadDetailLeak` 断言响应不含异业务持有者姓名，Green】

## 4. 前端呈现

- [x] 4.1 写 Red：`CreateLeadModal.spec.ts` 断言选定客户后渲染其他业务线索区块（类型/归属/阶段）；注意 a-modal `:render-to-body="false"`。先得失败。【Red：expected false to be true（.other-leads-hint 不存在），其余 5 用例仍过】
- [x] 4.2 在 `CreateLeadModal.vue` 接入详情端点（复用 `watch(selectedCustomerId)` 节流），渲染列表。转绿。【Green：6/6】
- [x] 4.3 写 Red+转绿：公海列表视图对 `customerHasOtherLeads===true` 的行显示「该客户已有其他业务线索」文案，不展开详情。【Red：true 用例 0 flags（false 用例已过）→ Green：PublicPoolView 8/8】
- [x] 4.4 写 Red+转绿：`LeadDetailPanel.vue` 自有线索详情调用端点（excludeBusinessType=当前类型），SALES 仅见本人其他线索。【Red：.detail-other-leads expected false→ Green：LeadDetailView 32/32。MSW `/leads/:id` 与 `/leads/customer-other-leads` 路径冲突，在 mountView 优先注册精确 handler 解决】
- [x] 4.5 新增 `api/leads.ts` 客户端方法与类型，复用现有 `ApiError` 处理。【`fetchCustomerOtherLeads` + `LeadOtherLeadView` + `PoolLeadView.customerHasOtherLeads`】

## 5. 不阻断与回归验证

- [x] 5.1 写 Red+转绿：断言「存在其他业务线索时」创建 / 认领仍成功（spec「协同提示不阻断写操作」两场景）。【`LeadOtherLeadsNonBlockingTest` 2/2；作为行为保持回归守卫（写路径本就不查提示，无 Red，验证新代码未引入阻断）】
- [x] 5.2 回归：运行既有 `LeadDuplicateService`、`LeadOwnershipService`、`LeadController` 测试套件，确认未被破坏；前端跑相关 `.spec.ts`。【后端 lead 包 + PermissionMatrix 159/0；前端全量 228/0；`vue-tsc -b` 0 error】
- [x] 5.3 产出 `qa/qa-report.md`：汇总各层 Red→Green 证据、§7.6.5 越权负例结论、不阻断验证与回归结果。
