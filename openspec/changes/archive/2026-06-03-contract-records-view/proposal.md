## Why

PRD §4 角色表与验收 §11.10 均要求 Admin 可查看「全部合同记录」，但当前 `/contracts` 路由仅为 `PlaceholderView` 占位，后端也无任何合同查询接口——合同数据由赢单事务原子写入后，只能在单条线索详情里看到摘要，缺一个独立的合同记录浏览入口。该缺口不在 PRD §10 非本期清单内（§10 仅排除合同**审批**与金额/日期**修改**，未排除**查看**）。

## What Changes

- 新增后端只读查询接口：分页返回合同记录，支持按成交销售、签订日期区间、客户名称/业务类型关键词过滤。
- 新增权限隔离：Admin 看全量；Sales 仅看 `dealSalesId = 本人` 的合同记录（PRD 未要求 Sales 视角，但本期决定提供，按当前归属无关、以成交事件归属 `dealSalesId` 判定）。
- 前端将 `/contracts` 占位页替换为真实合同记录浏览页：全量倒序列表 + 分页 + 上述筛选；列固定为客户、业务类型、合同金额（千分位）、签订日期、成交销售、赢单时间。
- 合同记录维持只读追加流语义：本页**不**提供任何创建/编辑/删除合同入口。

## Capabilities

### New Capabilities
- `contract-view`: 合同记录的只读浏览与查询能力（分页列表、筛选、按角色的可见范围隔离与脱敏边界），与写侧 `contract` 能力分离，对齐 `system-log` / `system-log-view` 的拆分约定。

### Modified Capabilities
<!-- 无：写侧 contract 能力的需求不变，本 change 仅新增读侧能力 -->

## Impact

- 后端：新增 `ContractController`（只读查询端点）+ 对应 service/repository 查询；不改赢单写路径与 `contract` 表结构。
- 前端：替换 `frontend/src/router/index.ts` 中 `/contracts` 的 `PlaceholderView` 为新页面组件；新增合同记录视图与 API 调用；可能复用 `system-logs` 全局浏览页的布局与分页交互。
- 权限：沿用 `permission` 能力的隔离断言风格——Sales 越权访问他人合同记录须被拒或自动收窄到本人范围。
- 数据：纯读，无迁移；金额展示沿用精确数值类型，禁用浮点。
