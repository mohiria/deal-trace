## Why

销售工作台联调暴露三个相互关联的缺陷：①线索归属只显示 `销售 #7` 这类 id 拼串而非销售姓名；②SALES 在公海抽屉看到的是上一条名下线索的明文残留，违反 PRD §7.5「认领前看脱敏号、认领成功后才看明文」；③抽屉打开时点选其他线索不刷新、需手动关闭再打开。②③同源：抽屉走明文详情端点而后端对 SALES 看公海线索抛 NOT_FOUND，前端加载失败又不清空当前线索导致数据残留。

## What Changes

- 后端线索响应视图额外内联归属销售姓名 `ownerSalesName`（无归属/公海为 `null`），与既有 `customerName` 内联一致；前端归属列与抽屉显示姓名，`null` 回退「公海」。
- SALES 在工作台点选公海（无归属）线索时，抽屉以前端已加载的**脱敏公海数据**渲染只读摘要并提供认领入口，**不**调用明文详情端点；电话保持脱敏直到认领成功。
- 抽屉打开状态下选择任意其他线索（名下↔公海互切）即时刷新展示该线索，无需手动关闭重开；线索详情加载失败时清空当前选中线索，杜绝跨线索数据残留。
- 不改后端脱敏策略与 `detailFor` 的私海访问裁决（其他 SALES 私海仍 NOT_FOUND）；范围限这三点。

## Capabilities

### New Capabilities

（无新增能力）

### Modified Capabilities

- `lead`: 线索对外响应视图在既有内联 `customerName`/`customerUsci` 基础上，额外内联归属销售姓名 `ownerSalesName`（公海/无归属为 `null`）；不改变 `detailFor` 的可见性裁决与脱敏端点划分。
- `frontend-workbench`: 工作台抽屉对 SALES 的公海（无归属）线索以脱敏只读摘要 + 认领入口呈现、不取明文详情；抽屉在选择其他线索时即时刷新、加载失败清空选中；归属展示销售姓名而非 id 拼串。

## Impact

- 后端：`lead/dto/LeadView.java`（新增字段 `ownerSalesName`）、`LeadService`/`LeadController` 构造视图处的姓名 join（按 `ownerSalesId` 查 account name，公海为 null）、相关单元/集成测试。
- 前端：`stores/leads.ts`（`loadLead` 失败清空 `currentLead`）、`components/LeadDetailPanel.vue` 与 `views/DashboardView.vue`（公海只读脱敏摘要、归属姓名、抽屉切换刷新）、`api/leads.ts`（`LeadView`/`PoolLeadView` 类型补 `ownerSalesName`）、相关 Vitest 组件测试。
- 契约/规格：`openspec/specs/lead/spec.md` 视图内联要求、`frontend-workbench` 抽屉与归属展示要求。
- 测试编排：后端集成测试用真 MySQL 8.4；运行前需停掉占用 8080 的本地 dev 后端（与 `mvn verify` 共用 dealtrace 实例不可并发）。
