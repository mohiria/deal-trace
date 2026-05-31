## 1. QA 先行与基线确认

- [x] 1.1 建立 `qa/lightweight-test-design.md`，覆盖：后端 LeadView 内联 ownerSalesName（有归属/公海/归属姓名缺失）、前端归属显示姓名、SALES 公海抽屉脱敏只读+认领、抽屉切换即时刷新、加载失败清空。
- [x] 1.2 建立 `qa/regression-impact-analysis.md`，明确受影响后端端点（mine/listAll/detail/各写动作返回视图）与前端组件，旧行为保留点（detailFor 私海 NOT_FOUND 不变、脱敏端点划分不变），回归命令。
- [x] 1.3 审核现有基线测试：后端 LeadView/LeadService/LeadController 相关测试；前端 DashboardView、LeadDetailView、LeadDetailPanel、leads store 测试。

## 2. Red 测试（先红后绿）

- [x] 2.1 后端：为 LeadView 内联 ownerSalesName 写测试——集成测试（LeadControllerDetailListTest）覆盖有归属/公海/mine 列表；**集成执行被远程库 contract 污染数据阻断**（FK fk_contract_sales），改以 LeadServiceOwnerNameTest 单元测试覆盖解析逻辑作备选验证。
- [x] 2.2 前端：DashboardView/LeadDetailPanel 测试断言归属显示姓名而非 `销售 #id`，公海显示「公海」。Red 已确认（显示 `销售 #7`）。
- [x] 2.3 前端：DashboardView 抽屉测试——SALES 点公海线索时抽屉展示脱敏电话、提供认领入口、不调用明文详情端点、不呈现写入口。Red 已确认（显示明文 + 调详情端点）。
- [x] 2.4 前端：抽屉切换测试——名下→公海即时刷新为脱敏摘要不残留；loadLead 失败后 currentLead 清空。Red 已确认（残留前一条）。
- [x] 2.5 已记录 Red：前端 5 条因预期行为差距失败；后端集成 Red 被环境阻断（非干净 Red，已书面记录例外）。

## 3. 后端实现（ownerSalesName 内联）

- [x] 3.1 LeadView 记录新增 `ownerSalesName` 字段及三参构造；2 参兼容构造委托为 null；公海/无归属为 null。
- [x] 3.2 LeadService 新增 ownerName(单条)/loadOwnerNames(批量 IN，避免 N+1)，停用/缺失账号回退 null；LeadController create/detail/toView/toViews 注入归属姓名。
- [x] 3.3 后端单元测试 LeadServiceOwnerNameTest 5/5 Green，BUILD SUCCESS（全模块编译通过）；集成测试受环境阻断未执行（见 5.1）。

## 4. 前端实现（脱敏抽屉 + 切换刷新 + 归属姓名）

- [x] 4.1 `api/leads.ts`：`LeadView` 补 `ownerSalesName`（PoolLeadView 无归属字段，映射时置 null）。
- [x] 4.2 归属展示：DashboardView.ownerText 与 LeadDetailPanel 当前归属改用 `ownerSalesName`，null 回退「公海」，去除 `销售 #${ownerSalesId}` 拼串。
- [x] 4.3 `stores/leads.ts`：`loadLead` catch 清空 `currentLead` 并重抛；新增 `setCurrentFromPool` 以脱敏公海数据填充。
- [x] 4.4 DashboardView.openLead 按归属分流：SALES 公海行 → setCurrentFromPool + skipFetch（不调详情端点）；其余走 loadLead。`:key=activeLeadId` 重挂保证切换即时刷新。
- [x] 4.5 LeadDetailPanel：skipFetch 跳过 loadLead/loadProgress；showClaim（SALES 公海未结束）呈现认领入口并 emit claim；写入口按既有归属规则自然隐藏。

## 5. 验证与闭环

- [x] 5.1 后端：LeadServiceOwnerNameTest 5/5 Green（单元，无 DB）；集成测试 LeadControllerDetailListTest 因远程库 contract 污染数据 FK 阻断未执行（整套含既有测试均受阻），记为环境阻塞，待清库后跑。
- [x] 5.2 前端：受影响 DashboardView/leads store 测试 33/33 Green；全量 23 files / 210 tests Green。
- [x] 5.3 `openspec validate ... --strict` 通过。
- [x] 5.4 `vue-tsc -b` 通过；后端 mvn BUILD SUCCESS（编译通过）。
- [x] 5.5 已重启本地前后端（8080/5173）；非无头浏览器以 SALES 的端到端验收交由用户在浏览器确认（前端组件测试已覆盖三项行为）。
- [x] 5.6 建立 `qa/qa-test-report.md` 汇总覆盖、TDD 证据、环境阻塞与剩余风险。
