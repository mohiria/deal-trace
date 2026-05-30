## Context

frontend-shell（已归档）交付了登录、会话、`axios` 拦截器（token 注入 + `UNAUTHORIZED` 清退 + `ApiError` 按 `code` 分支）、路由守卫与角色导航骨架，`/users` 与 `/system-logs` 当前为 `requiresAdmin` 的 `PlaceholderView`。frontend-lead-flow（已归档）交付了线索列表 / 公海 / 详情 / 进度 / 阶段 / 闭单 / 退回，并把 Admin 分配 / 回收 / 转移与系统日志面板明确推迟到本批次；其 `src/utils/lead.ts` 已有 `isClosed`、`LEAD_STAGE`，`src/stores/leads.ts` 已有 `currentLead` / `loadLead`，`src/api/leads.ts` 已有线索端点封装。

本批次把"用户管理"与"Admin 线索归属调度"落成真实界面，消费既有 `/admin/accounts/*`（见 `AdminAccountController`）与 `/leads/{id}/{assign,recall,transfer}`（见 `LeadController` + `LeadOwnershipService`）端点，后端零改动。系统日志查看本批次不做（后端无读端点，已与用户确认推迟）。

约束来源：PRD §7.1（认证 / 账号由部署配置注入、停用 Sales 不自动转交）、§7.6（分配 / 回收 / 转移）、§7.7（闭单只读）；tech-arch §10（Arco Design Vue，禁 Tailwind）、§4.1（Vue3 + TS + Vite + Pinia + Vue Router）。手机号脱敏由后端完成，前端只展示返回值。

## Goals / Non-Goals

**Goals:**

- 用户管理页（`/users`，仅 Admin）：账号列表（含启用 / 停用、按创建时间）、创建 Sales、启用 / 停用（幂等、不可停用自己）。
- 线索详情内的 Admin 归属操作：公海线索分配、名下线索回收 / 转移，目标候选限定启用 Sales。
- 归属入口受"未结束 + 当前归属态 + Admin 角色"三重约束派生；闭单只读复用 `isClosed`。
- 写操作错误按 `ApiError.code` 精细分支（`VALIDATION_ERROR` / `LEAD_ENDED_READONLY` / `FORBIDDEN` / `UNAUTHORIZED`），前端校验前置、后端裁决兜底。
- 复用 frontend-shell 的拦截器 / `ApiError` / MSW 边界 / 设计 token，与 frontend-lead-flow 的 `leads` store / `isClosed`。

**Non-Goals:**

- 系统日志查看（独立页 + 详情面板）——后端无读端点，整体推迟；`/system-logs` 占位页与详情日志面板保持现状。
- Admin 修改账号 `name` / 重置 Sales 密码——PRD MVP 未点名，不做。
- 任何后端代码、迁移、错误码、端点变更。
- 停用 Sales 后名下线索的自动转交——PRD §7.1 明确需 Admin 手动回收 / 转移，本批次只提供手动入口，不做自动逻辑。

## Decisions

### D1：账号端点封装为 `src/api/accounts.ts`，归属端点追加进既有 `src/api/leads.ts`

新建 `src/api/accounts.ts`：导出 `AccountView` 类型（镜像后端 record：`id` / `email` / `name` / `role` / `status` / `createdAt`）与函数 `fetchAccounts()`、`createSales({email,name,password})`、`updateAccountStatus(id, status)`，用 `apiClient.get<T,T>` 双泛型。归属三动作 `assignLead(id, salesId)`、`recallLead(id)`、`transferLead(id, salesId)` 追加进 `src/api/leads.ts`（与既有 `claimLead` / `releaseLead` 同属线索写动作，类型复用 `LeadView`）。**Why**：账号是独立资源域，单列一个 api 模块；归属动作与既有线索动作同域，就近放 `leads.ts` 避免割裂。**Alternative**：归属动作另起 `ownership.ts`——被否，与既有 claim/release 同语义、同返回类型，分裂无收益。

### D2：账号状态用新 `accounts` store，归属动作复用既有 `leads` store

新建 `src/stores/accounts.ts`：state `accounts` / `loading` / `error`，actions `loadAccounts` / `createSales` / `setStatus`；`createSales` 成功后把新账号并入 `accounts`，`setStatus` 成功后就地更新对应账号 `status`。派生 `enabledSales`（computed：`role==='SALES' && status==='ENABLED'`）供分配 / 转移的目标候选。归属三动作加入既有 `src/stores/leads.ts`：`assign` / `recall` / `transfer` 调 D1 函数后用返回的 `LeadView` 覆盖 `currentLead`（与 `changeStage` / `winLead` 等既有写动作刷新 `currentLead` 的模式一致）。**Why**：账号列表是 Admin 专属、与线索状态无耦合，独立 store 内聚且纯 TS 便于单测；归属动作产出新 `LeadView`，复用 `leads` store 既有 `currentLead` 刷新链路。**Alternative**：把账号塞进 auth store——被否，auth 管会话身份，账号 CRUD 是另一关注点。

### D3：归属入口由"角色 + 未结束 + `ownerSalesId` 归属态"三重派生，单处判定

线索详情新增"归属操作区"，仅当 `auth.isAdmin && !isClosed(lead)` 时呈现，并按 `lead.ownerSalesId` 分叉：

- `ownerSalesId == null`（公海）→ 仅呈现**分配**（目标选 `enabledSales`）。
- `ownerSalesId != null`（有归属）→ 呈现**回收**（直接动作）与**转移**（目标选 `enabledSales`，排除当前归属）。

`isClosed(lead)` 为真时整个归属操作区收起。**Why**：后端 `LeadOwnershipService` 对三动作的前置互斥（assign 仅公海、recall/transfer 仅有归属、已结束一律 `LEAD_ENDED_READONLY`）已是硬约束，前端按同一状态机显隐可避免大量必然失败的请求；与 frontend-lead-flow 用 `isClosed` 统一收起写入口的策略一致。**Alternative**：三个入口恒显、靠后端报错引导——被否，体验差且与既有闭单只读派生不一致。

### D4：分配 / 转移目标候选 = `enabledSales`，进入归属区时按需加载账号

分配 / 转移弹窗的 Sales 下拉数据源为 `accounts` store 的 `enabledSales`。线索详情属 Admin 场景才需要账号列表，故在 Admin 打开归属操作（或详情挂载且 `isAdmin`）时触发 `loadAccounts`，避免 Sales 详情页无谓拉账号。转移候选额外排除 `lead.ownerSalesId` 对应账号（后端"目标与当前归属相同"会 `VALIDATION_ERROR`，前端先行排除）。**Why**：目标合法性（启用 + SALES）后端兜底，前端用同一 `enabledSales` 口径先行过滤，减少必然失败项；账号列表与用户管理页共用同一 store，天然复用。

### D5：写操作错误按 `ApiError.code` 精细分支，复用既有 `ApiError`

- 账号：`VALIDATION_ERROR` → 展示后端 `message`（邮箱已存在 / 不可停用自己 / role 非法），保留表单或保持状态不变。
- 归属：`VALIDATION_ERROR` → 展示后端 `message`（已有归属请用转移 / 已在公海无需回收 / 目标相同 / 目标非法）；`LEAD_ENDED_READONLY` → 提示已闭单 + 据后端刷新该线索只读态；`FORBIDDEN` → 展示被拒提示不伪造成功；`UNAUTHORIZED` → 既有拦截器清退。

**Why**：frontend-shell 拦截器已把响应归一为带 `code` 的 `ApiError`，本批次直接 `catch (e) { if (e instanceof ApiError) switch(e.code) }`，与 frontend-lead-flow 的 D5 同构。前端即时校验（邮箱格式 / 必填）是体验前置，后端校验是权威兜底。

### D6：自身停用入口前端先行收起，后端兜底

账号列表渲染到 `id === auth.currentUser.id` 的行时不呈现停用入口（PRD §7.1 防自锁）；即便被绕过，后端 `VALIDATION_ERROR`"不可停用自己"兜底，前端展示并保持状态。启用 / 停用对同一目标幂等（后端对相同状态直接返回当前态），前端不特判幂等，按返回的 `status` 同步即可。

### D7：占位路由替换为真实 `UsersView`，系统日志路由保持占位

`/users` 的 `PlaceholderView` 替换为 `src/views/UsersView.vue`（`requiresAdmin` meta 不变）。`/system-logs` 路由保持 `PlaceholderView`（本批次不做），但从声明式导航表 `src/components/navigation.ts` 中**移除"系统日志"条目**，使其对所有角色不呈现（占位路由保留、无入口指向）。归属操作区并入既有 `src/views/LeadDetailView.vue`，不新增路由。**Why**：路由骨架与守卫已就绪，本批次只替换占位视图、扩展详情页、并摘除指向无数据来源的死链入口，最小面积改动。`navigation.ts` 的 `visibleSections` 已有单测，移除条目顺带更新其断言。

### D8：组件测试在 MSW（axios 边界）mock，E2E 打真后端

沿用 frontend-lead-flow 约定：在 `src/test/msw/handlers.ts` 追加 `/admin/accounts/*` 与 `/leads/{id}/{assign,recall,transfer}` 的 handler 工厂（成功态 + `VALIDATION_ERROR` + `LEAD_ENDED_READONLY` + `FORBIDDEN`）；组件 / store 单测针对 api 层 mock；E2E 仅覆盖关键 Admin 旅程（创建 Sales→列表可见→分配线索）打真后端。Arco `a-modal` / `a-select` 组件测试须 `:render-to-body="false"`（见项目记忆，否则 teleport 出 wrapper 找不到）。

## Risks / Trade-offs

- **前端归属状态机与后端漂移**：D3 按 `ownerSalesId` 与 `isClosed` 派生入口，若后端归属前置规则演进，前端显隐可能与后端裁决不一致。缓解：所有写动作仍以后端 `ApiError` 兜底回落，显隐只为减少无效请求，不作授权依据；spec 场景覆盖各回落分支。
- **`enabledSales` 时效**：分配 / 转移用本地 `accounts` 快照，若期间某 Sales 被他处停用，候选可能含已失效目标。缓解：后端"目标非法"`VALIDATION_ERROR` 兜底，前端展示并据后端刷新。
- **账号列表无分页**：MVP 账号量小，`fetchAccounts` 一次性拉全量（与后端 `list()` 全量返回一致）。规模增长后需分页，本批次不预造。
- **系统日志缺口外显**：详情日志面板仍缺失，PRD §7.8 的"详情展示系统日志"本批次未满足；导航"系统日志"入口本批次已隐藏（占位路由保留），避免死链。已与用户确认推迟，待后端读端点就绪后另起 change 恢复入口并补面板，本批次 spec 不声称交付该查看行为。
