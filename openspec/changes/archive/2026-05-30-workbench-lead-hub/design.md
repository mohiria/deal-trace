## Context

工作台首屏 `DashboardView.vue` 现仅渲染四张只读指标卡（`GET /api/dashboard`）。线索的列表与操作已分散在 `MyLeadsView.vue`（`GET /leads/mine`、Admin `GET /leads`）、`PublicPoolView.vue`（`GET /leads/pool` + `POST /leads/{id}/claim`）、`LeadDetailView.vue`（`GET /leads/{id}` + 进度/阶段/赢单/流失/退回/分配/回收/转移写端点）三页。

关键既有资产（实现复用的基础）：
- `frontend/src/stores/leads.ts`：单例 Pinia store，已封装 `myLeads/allLeads/pool/currentLead/progress` 状态与 `loadMyLeads/loadAllLeads/loadPool/loadLead/loadProgress/claim/release/addLeadProgress/changeStage/winLead/loseLead/assign/recall/transfer` 全部方法。**本变更不新增 store 方法、不新增 API 端点。**
- `LeadDetailView.vue`：自 `route.params.id` 取 `leadId`，承载全部详情读取 + 写动作 + 闭单只读派生（`runWrite` 统一回落 `LEAD_ENDED_READONLY`），其弹窗已遵循 `:render-to-body="false"`。
- `LeadDetailView` 现有动作集**不含认领**——认领仅在 `PublicPoolView.onClaim`（含 `LEAD_ALREADY_CLAIMED` 并发处理）。

约束：纯前端变更；禁止 Tailwind（用 Arco + 既有 `--dt-*` token）；阶段 2 TDD 先 Red（Vitest）；Arco 浮层组件（`a-drawer`/`a-modal`/`a-select`）测试须 `:render-to-body="false"`，否则 teleport 出 wrapper 致 `find` 失败（见项目记忆 arco-modal-render-to-body-test）。

## Goals / Non-Goals

**Goals:**
- 工作台首屏内嵌线索表格 + 右侧抽屉详情，复用既有读/写能力，不重写详情逻辑。
- 新增今日提醒/待办区块，仅由现有只读数据客户端派生。
- 保持 `MyLeadsView`/`PublicPoolView`/`LeadDetailView` 路由与对外行为不变，既有测试保持绿。

**Non-Goals:**
- 不做表格筛选/分页（无后端分页查询 API）。
- 不做抽屉内系统日志（后端读能力未交付）。
- 不做导出（无 PRD 需求来源）。
- 不新增后端端点、不改 DB schema、不改 `leads` store 公共 API。

## Decisions

### D1：抽取 `LeadDetailPanel.vue`，详情页与工作台抽屉共用，杜绝逻辑重写

把 `LeadDetailView.vue` 的脚本逻辑与 `<section class="detail-page">…</section>` 主体整体抽到新组件 `frontend/src/components/LeadDetailPanel.vue`，以 **`leadId: number` 作为 prop**（替代当前 `route.params.id` 读取）。

- `LeadDetailView.vue` 退化为薄壳：`const leadId = Number(route.params.id)` → `<LeadDetailPanel :lead-id="leadId" />`。详情页对外行为、URL、动作集完全不变。
- 工作台抽屉内渲染 `<LeadDetailPanel :key="activeLeadId" :lead-id="activeLeadId" />`，`v-if="activeLeadId != null"`。`:key` 绑 leadId 使每次选行重新挂载 → `onMounted` 重新 `loadLead/loadProgress`，避免跨线索状态串台。
- 面板内 `onMounted` 保留"Admin 才拉 `accounts.loadAccounts()`"逻辑不动。

**备选**：用 `watch(leadId)` 代替 `:key` 重挂载——否决，`onMounted` 既有逻辑会漏触发，`:key` 重挂载语义最简且与现有 onMounted 契合。

**备选**：抽屉里塞一份独立详情实现——否决，违背"不重写详情逻辑"，且会与 `LeadDetailView` 行为漂移。

### D2：认领不进抽屉，留在今日提醒/公海的内联流

`LeadDetailPanel`（继承自 `LeadDetailView`）不含认领。工作台表格喂的是"我的线索/全部线索"（SALES 名下无公海线索；Admin 看到的公海线索用既有 assign/recall/transfer）。认领是 SALES-on-公海 动作，仅在今日提醒"建议认领"条目内联触发，复用 `PublicPoolView` 既有流程：`leads.claim(id)` + `LEAD_ALREADY_CLAIMED` → 提示"该线索已被认领"并 `leads.loadPool()` 刷新。`ADMIN` 不呈现认领入口。

### D3：今日提醒纯客户端派生，独立于四项看板指标

新增 `frontend/src/utils/workbench.ts` 承载派生纯函数（便于单测）：
- `suggestedClaims(pool, limit)`：从 `leads.pool`（`GET /leads/pool` 已加载）取前 `limit` 条作"建议认领"。常量 `SUGGESTED_CLAIM_LIMIT = 5`。
- `staleOwnedLeads(myLeads, now, thresholdDays)`：名下未结束线索中 `lastTrackedAt` 为空（尚未跟踪）**或**早于 `now - thresholdDays` 的，作"长期未跟踪"。常量 `STALE_TRACK_DAYS = 7`。`isClosed`（`utils/lead.ts`）复用判终态。
- 提醒区块**不参与任何看板指标口径**（指标仍只取 `GET /api/dashboard` 返回值）；纯函数无副作用、不触发写。

**口径澄清（写进测试断言）**：提醒不是 PRD §7.12 那四个指标，不套用"存量按当前归属 / 事件按发生时归属"双轨规则——它只是基于当前 `pool`/`myLeads` 只读快照的客户端提示。

### D4：首屏数据装载只发只读查询，写仅由用户主动触发

`DashboardView` `onMounted` 并发触发只读装载：`loadDashboard()`（既有）+ `loadMyLeads()`（Admin 追加 `loadAllLeads()`）+ `loadPool()`。全部 GET。抽屉写动作、提醒认领由用户点击触发，符合收窄后的"指标看板只读 + 首屏加载/刷新不自动写"契约。表格 loading/空态复用既有三态写法。

### D5：表格列对齐 `MyLeadsView`，Arco `a-drawer` 用 `:render-to-body="false"`

表格列：客户名称 / 业务类型 / 年度 / 阶段 / 联系人 / 最后跟踪（与 `MyLeadsView` 一致），行可点击打开抽屉。SALES 用 `myLeads`、ADMIN 用 `allLeads` 作数据源。抽屉 `a-drawer` 显式 `:render-to-body="false"` 保证 `wrapper.find` 可测；面板内既有 `a-modal` 已是 false，不动。

## Risks / Trade-offs

- **抽取 `LeadDetailPanel` 触动详情页** → 缓解：抽取为纯搬迁（仅把 `leadId` 来源从 route 改 prop），保持 `LeadDetailView.spec.ts` 不变并须保持绿作为回归闸；行为零改动。
- **store `currentLead`/`progress` 为单例，抽屉与详情页共用** → 同一时刻仅一个详情上下文（抽屉或独立页，不同屏），`:key` 重挂载每次重载，无并发串台；今日提醒/表格用的是 `pool`/`myLeads`/`allLeads`，与 `currentLead` 不交叉。
- **今日提醒派生口径被误当指标** → 缓解：派生函数独立文件 + 单测显式断言"不改写任一看板指标值"，spec 亦有对应 scenario。
- **首屏多发三个列表请求** → 数据量为 MVP 级、全量加载可接受；失败态各自可观察，不阻塞指标看板呈现。
- **今日提醒为新发明 UX（无 PRD 来源）** → 已记录为产品决策；若后续 PRD 收口或要服务端排序，另开变更，不在本轮扩散。
