## Context

frontend-shell（已归档）交付了登录、会话、`axios` 拦截器（token 注入 + `UNAUTHORIZED` 清退 + `ApiError` 按 `code` 分支）、路由守卫与角色导航骨架，业务子路由当前为 `PlaceholderView`。本批次把销售线索旅程落成真实页面，消费既有 `/leads/*` 端点（见 `LeadController`），后端零改动。

约束来源：PRD §7.3–§7.11（线索 / 公海 / 详情 / 进度 / 闭单 / 退回）、tech-arch §10（Arco Design Vue，禁 Tailwind）、tech-arch §4.1（Vue3 + TS + Vite + Pinia + Vue Router）。金额精确数值（CLAUDE.md：不用 float/double）。手机号脱敏由后端 `PhoneMasker` 完成，前端只展示后端返回值。

## Goals / Non-Goals

**Goals:**

- 我的线索列表（Sales 名下 / Admin 全部）→ 线索详情。
- 公海列表（电话脱敏 / 明文由后端裁决）+ Sales 认领（含 `LEAD_ALREADY_CLAIMED` 并发冲突）。
- 线索详情：客户 / 业务 / 归属 / 阶段 / 流失信息 + 倒序进度跟踪流。
- 详情内写操作：追加进度、变更非结束阶段、标记赢单 / 流失、主动退回公海。
- 闭单只读：已结束线索收起所有写入口，越权 / 失效以后端裁决回落。
- 复用 frontend-shell 的拦截器 / `ApiError` 分支 / MSW 边界 / 设计 token。

**Non-Goals:**

- Admin 分配 / 回收 / 转移（`/{id}/assign`、`/recall`、`/transfer`）——依赖"用户管理"提供启用 Sales 列表，随 frontend-admin 落地。
- 线索详情的系统日志面板——system-log 能力随 frontend-admin 落地。
- 新建线索（`POST /leads` + `/duplicate-check`）——依赖 frontend-customer 的客户可搜索选择器；用户本批次未点名。
- 任何后端代码、迁移、错误码变更。

## Decisions

### D1：每个线索端点封装为一个 typed API 函数，集中在 `src/api/leads.ts`

视图不直接调 `apiClient`，统一经 `src/api/leads.ts` 的函数（`fetchMyLeads`、`fetchAllLeads`、`fetchLead`、`fetchPool`、`claimLead`、`releaseLead`、`changeStage`、`winLead`、`loseLead`、`fetchProgress`、`addProgress`）。返回类型用 `apiClient.get<T, T>(...)` 双泛型（拦截器已 unwrap，第二个泛型对齐实际返回）。

DTO 类型镜像后端 record（`LeadView` 17 字段、`PoolLeadView` 12 字段、`ProgressLogView`），放 `src/api/leads.ts` 同文件导出。**Why**：与 frontend-shell 把 `AuthUser`/`Role` 放 store 同文件一致；集中封装让 MSW handler 与组件测试只针对一层 mock。**Alternative**：每视图内联 axios 调用——被否，重复且难测。

### D2：列表 / 详情数据用 Pinia store（`leads` store）承载，视图保持薄

新增 `src/stores/leads.ts`：state 持 `myLeads` / `pool` / `currentLead` / `progress`，actions 调 D1 的 API 函数并维护 loading/error。**Why**：认领成功需"从公海移除 + 进入我的线索"、退回需"从我的线索移除"、追加进度需"刷新进度流 + 更新 currentLead.lastTrackedAt"——这些跨视图状态联动放 store 才内聚，且 store 是纯 TS 便于单测（frontend-shell 已验证 auth store 测试模式）。**Alternative**：每视图各自 `ref` + 重新拉取——被否，认领 / 退回的列表联动会散落且易漏。

### D3：角色裁决用 `auth` store 的 `isAdmin` / `role`，但只用于显隐，不作授权依据

认领入口仅 `SALES` 可见（`!auth.isAdmin`）；公海电话脱敏 / 明文完全由后端返回值决定，前端不自行掩码。写操作入口的显隐叠加"线索是否结束"（见 D4）。**Why**：复用 frontend-shell 既定的"前端显隐 + 后端裁决为权威"语义（spec R6）。所有写操作仍可能收到 `FORBIDDEN`/`LEAD_ENDED_READONLY`/`UNAUTHORIZED`，按 D5 回落。

### D4：闭单只读由 `LeadView.stage` 派生，单一判定函数

`isClosed(lead)` = stage ∈ {已赢单, 已流失}（用后端 `LeadStage.getDbValue()` 的字符串值，集中为常量，不散落字面量）。`isClosed` 为真时收起进度追加 / 阶段变更 / 赢单 / 流失 / 退回所有写入口。**Why**：PRD §7.7.7–§7.7.9 闭单只读是横切规则，集中一处派生避免各入口各判。**Alternative**：每入口各写 stage 比较——被否，易漏致只读被绕过。

### D5：写操作错误按 `ApiError.code` 精细分支，复用既有 `ApiError`

- `LEAD_ALREADY_CLAIMED` → 提示"该线索已被认领" + 刷新公海。
- `LEAD_ENDED_READONLY` → 提示已闭单 + 据后端刷新该线索只读态。
- `VALIDATION_ERROR` → 展示后端 `message`（金额 / 日期 / 必填等后端兜底校验）。
- `FORBIDDEN` → 展示被拒提示，不伪造成功。
- `UNAUTHORIZED` → 由既有拦截器清退（视图无需特判）。

**Why**：frontend-shell 拦截器已把响应归一为带 `code` 的 `ApiError`（success-envelope 与 non-2xx 双路径），本批次直接 `catch (e) { if (e instanceof ApiError) switch(e.code) }`。前端校验是体验前置（即时拦空 / 金额 / 日期），后端校验是权威兜底，两者都要。

### D6：金额用字符串 + 精确校验，不经 JavaScript number 运算

合同金额前端以字符串采集，正则校验 `> 0 且至多两位小数`（`/^\d+(\.\d{1,2})?$/` 且数值 > 0），原样字符串提交后端（后端为精确数值类型）。展示用千分位格式化（`Intl.NumberFormat`，仅用于显示不回写）。**Why**：CLAUDE.md 金额禁 float/double；JS `number` 是 IEEE754，对金额做运算 / 往返会引入精度风险。**Alternative**：`parseFloat` 后提交——被否，违背精确数值约束。

### D7：组件测试在 MSW（axios 边界）mock，E2E 打真后端

复用 frontend-shell 的 `src/test/setup.ts` + `src/test/msw/`。本批次为 `/leads/*` 端点新增 MSW handler 工厂（成功 / `LEAD_ALREADY_CLAIMED` / `LEAD_ENDED_READONLY` / `VALIDATION_ERROR` / 空列表）。E2E 仅覆盖关键旅程（认领 → 进入我的线索 → 追加进度 → 闭单只读），`test.skip` 守卫缺凭据时跳过。**Why**：tech-arch 测试分层；MSW 已是 frontend-shell 既定边界，禁止 mock axios 内部实现。

### D8：视图与路由——替换占位路由为真实视图，详情走子路由参数

`my-leads` → `MyLeadsView`、`public-pool` → `PublicPoolView`、新增 `leads/:id` → `LeadDetailView`（受保护子路由，复用既有守卫）。**Why**：沿用 frontend-shell 的 `AppShell` 子路由结构与 `meta.title`，不动守卫逻辑。设计 token 复用 `--dt-*`，组件用 Arco（`a-table`/`a-modal`/`a-form`/`a-timeline` 等），禁 Tailwind。

## Risks / Trade-offs

- **详情页跨两个 change 拼装**（本批次缺 Admin 归属操作 + 系统日志面板）→ 缓解：D2 store 与详情视图按"区块"组织，frontend-admin 追加区块时不重构既有区块；spec 已把归属操作 / 系统日志显式划出本批次。
- **金额字符串处理易写错**（千分位 / 校验边界）→ 缓解：校验与格式化抽为纯函数单测覆盖（0、负、三位小数、千分位边界）。
- **认领并发冲突仅能在真后端复现**，MSW 只能模拟 `LEAD_ALREADY_CLAIMED` 响应 → 缓解：组件测试断"收到该 code → 提示 + 刷新"，真并发交由后端既有测试与 E2E 旅程。
- **闭单只读若仅靠前端显隐会被绕过** → 缓解：D4 集中派生 + D5 后端 `LEAD_ENDED_READONLY` 兜底回落，spec R7 双场景（入口收起 + 后端拒绝回落）均覆盖。
- **MVP 无撤销赢单 / 流失**（PRD §7.7.10）→ 前端不提供撤销入口，符合既定范围，非缺陷。
