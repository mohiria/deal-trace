## Why

frontend-shell 把 `/users`（用户管理）与 `/system-logs`（系统日志）留成了 `requiresAdmin` 占位页，frontend-lead-flow 又把"Admin 分配 / 回收 / 转移"明确推迟到本批次（依赖一份启用 Sales 列表）。账号管理与线索归属调度是 Admin 唯一的运营抓手（PRD §7.1 / §7.6），后端 `/admin/accounts/*` 与 `/leads/{id}/{assign,recall,transfer}` 端点已全部就绪，前端需把这两块落成可用界面。

系统日志查看（独立页 + 线索详情面板，PRD §7.8 / §9.5）本批次**不做**：后端 `systemlog` 仅实现写入侧（`SystemLogPort.record`），无任何读取 / 查询端点，无法按 frontend-\* 的"零后端、纯消费既有端点"模式交付，留给未来含后端读端点的 change。为避免 Admin 看到指向无数据来源的死链，本批次**隐藏导航中的"系统日志"入口**（占位路由保留，仅去掉入口），待该能力交付时再恢复。

## What Changes

- **用户管理页**（`/users`，仅 Admin）：
  - 账号列表展示全部账号（含启用 / 停用）的 `email` / `name` / `role` / `status` / `createdAt`，按创建时间稳定排列；不展示任何密码字段。
  - 创建 Sales：填 `email` / `name` / `password`，角色固定 `SALES`；邮箱格式与必填项前端即时校验，邮箱重复以后端 `VALIDATION_ERROR`（"邮箱已存在"）兜底回落；成功后新账号以 `ENABLED` 进入列表。
  - 启用 / 停用：对目标账号切换状态，停用与启用各自幂等；Admin 不可停用自己（前端收起自身停用入口，后端 `VALIDATION_ERROR`"不可停用自己"兜底）。
- **Admin 线索归属操作**（线索详情内，仅 Admin、仅未结束线索）：
  - 公海线索（无归属）→ **分配**给一个启用 Sales；线索已有归属时后端以 `VALIDATION_ERROR`（"请使用转移"）回落。
  - 名下线索（有归属）→ **回收**至公海，或**转移**给另一个启用 Sales；线索已在公海 / 目标与当前归属相同时按后端 `VALIDATION_ERROR` 回落。
  - 分配 / 转移的目标候选 = 启用且角色为 `SALES` 的账号（来自账号列表）；目标非法（不存在 / 非销售 / 已停用）以后端 `VALIDATION_ERROR` 回落。
  - 已结束线索（已赢单 / 已流失）不呈现任何归属入口（闭单只读，复用 frontend-lead-flow 的 `isClosed`）；越权 / 失效以后端 `LEAD_ENDED_READONLY` / `FORBIDDEN` 裁决回落，不伪造成功。
- **角色显隐 + 后端裁决**：用户管理入口与线索归属入口仅对 `ADMIN` 呈现；显隐只为减少无效入口，真正授权仍以后端 `/admin/**` 与 `@PreAuthorize("hasRole('ADMIN')")` 为权威。
- **隐藏系统日志入口**：导航中的"系统日志"入口对包括 `ADMIN` 在内的所有角色不再呈现（其查看能力尚无后端读端点）；`/system-logs` 占位路由保留但无入口指向。

**不在本批次**：系统日志查看（独立页 + 详情面板，依赖尚不存在的后端读端点）；任何后端代码、迁移、错误码变更。

## Capabilities

### New Capabilities
<!-- 无新增能力；本批次向既有 frontend-workbench 追加需求。 -->

### Modified Capabilities
- `frontend-workbench`: 在登录 / 会话 / 角色导航 / 线索旅程骨架之上，追加"Admin 用户管理（列表、创建 Sales、启用停用）"与"Admin 线索归属调度（分配 / 回收 / 转移，受未结束与启用 Sales 约束）"等可观察行为需求。

## Impact

- **前端代码**：新增用户管理页（`UsersView`，替换占位路由）及其创建 / 状态切换交互；线索详情新增 Admin 归属操作区（分配 / 回收 / 转移弹窗）；新增账号 API 封装（`src/api/accounts.ts`：`fetchAccounts` / `createSales` / `updateAccountStatus`）与归属 API（在既有 `src/api/leads.ts` 追加 `assignLead` / `recallLead` / `transferLead`）；复用 frontend-shell 已验证的 axios 拦截器、`ApiError` 分支、token 注入与设计 token。
- **后端**：零改动，仅消费既有端点 `GET /admin/accounts`、`POST /admin/accounts`、`PATCH /admin/accounts/{id}/status`、`POST /leads/{id}/assign`、`POST /leads/{id}/recall`、`POST /leads/{id}/transfer` 与既有错误码（`VALIDATION_ERROR`、`LEAD_ENDED_READONLY`、`FORBIDDEN`、`UNAUTHORIZED`）。
- **依赖**：无新增运行时依赖；组件测试复用 frontend-shell 引入的 MSW 边界与设计 token，E2E 复用 Playwright 真后端约定。
- **设计**：沿用 Arco Design Vue 组件体系与从 `prototype/dealtrace-workbench.html` 抽取的设计 token，禁止引入 Tailwind（tech-arch §10）。
