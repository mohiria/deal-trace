## 1. QA 设计与测试边界

- [x] 1.1 用 `.claude/skills/vibe-coding-qa/templates/` 在 `openspec/changes/frontend-admin/qa/lightweight-test-design.md` 写轻量测试设计：按 spec 6 个需求列单元 / 组件 / E2E 分层与 Red 预期，标注前端即时校验（邮箱格式 / 必填 / 自身停用收起 / 归属态显隐）与后端裁决（`VALIDATION_ERROR` / `LEAD_ENDED_READONLY` / `FORBIDDEN`）双轨。
- [x] 1.2 在 `src/test/msw/handlers.ts` 追加 handler 工厂：账号 `accountsList`、`createSalesSuccess`、`createSalesDuplicate`(`VALIDATION_ERROR` 邮箱已存在)、`statusToggleSuccess`、`disableSelfRejected`(`VALIDATION_ERROR` 不可停用自己)；归属 `assignSuccess`、`assignAlreadyOwned`(`VALIDATION_ERROR`)、`recallSuccess`、`recallAlreadyPool`(`VALIDATION_ERROR`)、`transferSuccess`、`transferSameOwner`(`VALIDATION_ERROR`)、`ownershipEndedReadonly`(`LEAD_ENDED_READONLY`)、`ownershipForbidden`(`FORBIDDEN`)。

## 2. 账号 API 封装与类型（D1）

- [x] 2.1 写 `src/api/accounts.spec.ts`（Red）：断言 `fetchAccounts` / `createSales` / `updateAccountStatus` 命中正确方法 / 路径、unwrap 后返回业务负载、错误归一为带 `code` 的 `ApiError`。
- [x] 2.2 实现 `src/api/accounts.ts`：导出 `AccountView` 类型（`id` / `email` / `name` / `role` / `status` / `createdAt`，镜像后端 record）与 `fetchAccounts()`、`createSales({email,name,password})`、`updateAccountStatus(id, status)`，用 `apiClient.get<T,T>` 双泛型，复用既有 `apiClient` / `ApiError`。

## 3. 归属 API 封装（D1）

- [x] 3.1 在 `src/api/leads.spec.ts` 追加（Red）：`assignLead`/`recallLead`/`transferLead` 命中正确方法 / 路径、返回 `LeadView`、错误归一为 `ApiError`。
- [x] 3.2 在 `src/api/leads.ts` 追加 `assignLead(id, salesId)`、`recallLead(id)`、`transferLead(id, salesId)`，复用 `LeadView` 与既有写动作模式。

## 4. accounts store（D2 / D4）

- [x] 4.1 写 `src/stores/accounts.spec.ts`（Red）：`loadAccounts` 写入 `accounts` 并维护 loading/error；`createSales` 成功后把新账号并入 `accounts`；`setStatus` 成功后就地更新对应账号 `status`；派生 `enabledSales` 仅含 `role==='SALES' && status==='ENABLED'`；写动作收到 `ApiError` 时透传不吞。
- [x] 4.2 实现 `src/stores/accounts.ts`：state `accounts`/`loading`/`error`，computed `enabledSales`，actions `loadAccounts`/`createSales`/`setStatus` 调 §2 API。

## 5. leads store 归属动作（D2）

- [x] 5.1 在 `src/stores/leads.spec.ts` 追加（Red）：`assign`/`recall`/`transfer` 调 §3 API 后用返回的 `LeadView` 覆盖 `currentLead`；收到 `ApiError` 时透传不吞。
- [x] 5.2 在 `src/stores/leads.ts` 追加 `assign(id, salesId)`/`recall(id)`/`transfer(id, salesId)`，刷新 `currentLead`（与既有 `changeStage`/`winLead` 模式一致）。

## 6. 用户管理页 — 列表（spec：查看账号列表）

- [x] 6.1 写 `src/views/UsersView.spec.ts` 列表部分（Red）：Admin 渲染后端返回全部账号（含启用 / 停用）及 `email`/`name`/`role`/`status`/创建时间；不渲染任何密码字段；按创建时间稳定排列。
- [x] 6.2 实现 `src/views/UsersView.vue` 列表区：`loadAccounts` + Arco `a-table`；`--dt-*` token，禁 Tailwind。

## 7. 用户管理页 — 创建 Sales（spec：创建 Sales 账号）

- [x] 7.1 写 UsersView 创建部分（Red）：邮箱格式非法 / `name` 或 `password` 空 → 即时拦截不发请求；合法提交成功后新账号以 `ENABLED` 进入列表；邮箱重复遇 `VALIDATION_ERROR` 展示后端 `message` 且保留表单不计入列表。
- [x] 7.2 实现创建弹窗（Arco `a-modal` + `a-form`，`:render-to-body="false"`）：采集 `email`/`name`/`password`，role 固定 `SALES`，前端即时校验，调 `store.createSales`，按 D5 分支 `VALIDATION_ERROR`。

## 8. 用户管理页 — 启用 / 停用（spec：启用与停用账号）

- [x] 8.1 写 UsersView 状态切换部分（Red）：停用 `ENABLED` 账号成功后该行 `status` 变 `DISABLED`、启用 `DISABLED` 成功后变 `ENABLED`；自身行不呈现停用入口；停用自身遇 `VALIDATION_ERROR` 展示"不可停用自己"且状态不变。
- [x] 8.2 实现状态切换：按 `auth.currentUser.id` 收起自身停用入口（D6），调 `store.setStatus`，按 D5 分支 `VALIDATION_ERROR`。

## 9. 线索详情 — Admin 归属操作区（spec：分配 / 回收 / 转移）

- [x] 9.1 写 `src/views/LeadDetailView.spec.ts` 归属部分（Red）：仅 `isAdmin && !isClosed` 呈现归属区；`ownerSalesId==null` 仅显分配、`!=null` 显回收 + 转移；已结束线索归属区收起；分配 / 转移候选仅含 `enabledSales`、转移排除当前归属。
- [x] 9.2 写归属回落部分（Red）：分配已有归属 / 回收已在公海 / 转移目标相同遇 `VALIDATION_ERROR` 展示后端 `message` 并据后端刷新；已结束遇 `LEAD_ENDED_READONLY` 提示并刷新只读态；`FORBIDDEN` 不伪造成功。
- [x] 9.3 实现 `src/views/LeadDetailView.vue` 归属操作区（D3）：按 `auth.isAdmin`/`isClosed(lead)`/`lead.ownerSalesId` 派生入口；Admin 挂载时 `loadAccounts`（D4）；分配 / 转移弹窗（`a-select` 选 `enabledSales`，`:render-to-body="false"`）调 `store.assign`/`store.transfer`，回收直接调 `store.recall`；按 D5 分支错误码。

## 10. 路由接线（D7）

- [x] 10.1 将 `src/router/index.ts` 的 `/users` 由 `PlaceholderView` 改为 `UsersView`（保留 `requiresAdmin` meta）；`/system-logs` 保持 `PlaceholderView`。
- [x] 10.2 写 / 复用守卫测试断言：`SALES` 访问 `/users` 回落工作台、`ADMIN` 放行（复用既有 `authGuard` 测试模式，必要时补用例）。
- [x] 10.3 隐藏系统日志入口（spec：系统日志入口在其查看能力交付前不呈现）：先更新 `src/components/navigation.spec.ts`（或 `AppShell.spec.ts`）Red——断言 `ADMIN` 与 `SALES` 的 `visibleSections` 均不含"系统日志"条目；再从 `src/components/navigation.ts` 的 `navSections` 移除该条目（`/system-logs` 路由占位保留）。

## 11. E2E（关键 Admin 旅程，打真后端）

- [x] 11.1 在 `frontend/tests/e2e/` 写 Admin 旅程：登录 Admin → 用户管理创建 Sales → 列表可见新 Sales（`ENABLED`）→ 进入一条公海线索详情 → 分配给该 Sales → 线索归属更新。场景优先，不要求严格 Red-Green。

## 12. 验证与回归

- [x] 12.1 跑 `pnpm test`（单元 + 组件）与 `pnpm test:e2e`（真后端），全绿；记录在 `openspec/changes/frontend-admin/qa/` 的 QA 报告。
- [x] 12.2 回归确认：frontend-lead-flow 既有详情 / 进度 / 闭单行为不被归属区改动破坏（`isClosed` 收起逻辑、`currentLead` 刷新链路无回归）。
