# 回归影响分析：refine-lead-list-and-drawer-experience

## 影响范围

- 前端页面：`DashboardView`、`MyLeadsView`、`PublicPoolView`、`CustomersView`、`UsersView`。
- 共享组件：`AppShell`、`LeadDetailPanel`、新增线索弹窗组件。
- API/store：复用现有 `createLead`、`duplicateCheck`、`searchCustomers`、`leads` store、`accounts` store，不改变接口契约。

## 需要保留的旧行为

- 登录、路由守卫、按角色显示导航和后端权限兜底不变。
- Sales 我的线索只展示后端返回的名下线索；Admin 仍按现有接口展示可访问线索。
- 公海线索认领成功移出公海；并发认领失败提示并刷新。
- 客户创建重复反馈、新建线索查重阻断、历史流失提示和字段校验不变。
- 结束线索只读、系统日志不展示、线索写入动作授权仍以后端裁决为准。
- 用户管理不展示密码/哈希，自身停用入口不展示。

## 回归测试范围

- 直接受影响组件测试：
  - `pnpm exec vitest run src/views/DashboardView.spec.ts`
  - `pnpm exec vitest run src/views/MyLeadsView.spec.ts`
  - `pnpm exec vitest run src/views/PublicPoolView.spec.ts`
  - `pnpm exec vitest run src/views/CustomersView.spec.ts`
  - `pnpm exec vitest run src/views/UsersView.spec.ts`
  - `pnpm exec vitest run src/views/LeadDetailView.spec.ts`
  - `pnpm exec vitest run src/components/AppShell.spec.ts`
  - `pnpm exec vitest run src/components/CreateLeadModal.spec.ts`
- 静态与构建：
  - `pnpm exec vue-tsc -b`
  - `pnpm build`
- OpenSpec：
  - `openspec validate refine-lead-list-and-drawer-experience --strict`

## 风险与缓解

- 多页面共享弹窗引入耦合：通过独立组件测试覆盖校验、查重、提交和关闭。
- 前端分页可能与服务端未来分页冲突：本轮不改接口，后续单独变更。
- 样式细节自动化断言有限：以结构断言、响应式 smoke 和截图作为补充证据。

## 执行记录

- Red 证据：
  - 已先补充/更新 `CreateLeadModal`、`DashboardView`、`AppShell`、`LeadDetailView`、`MyLeadsView`、`PublicPoolView`、`CustomersView`、`UsersView` 测试，再修改生产代码。
  - 命令：`pnpm exec vitest run src/components/CreateLeadModal.spec.ts src/views/DashboardView.spec.ts src/views/MyLeadsView.spec.ts src/views/PublicPoolView.spec.ts src/views/CustomersView.spec.ts src/views/UsersView.spec.ts src/views/LeadDetailView.spec.ts src/components/AppShell.spec.ts`
  - 失败原因与预期差距一致：统一弹窗组件缺失、AppShell 仍显示角色/提醒入口未统一、工作台仍含旧 tab 和 spark、列表页缺少搜索分页/入口、抽屉阶段轨道仍展示未来阶段。`CreateLeadModal.vue` 缺失属于组件抽取前置缺口，随后由新组件测试覆盖。
- Green 证据：
  - 受影响 Vitest：同上命令，结果 `8 passed / 97 passed`。
  - OpenSpec：`openspec validate refine-lead-list-and-drawer-experience --strict`，结果 `Change 'refine-lead-list-and-drawer-experience' is valid`。
  - 类型检查：`pnpm exec vue-tsc -b`，通过。
  - 构建：`pnpm build`，通过；仅存在 Vite chunk size 警告，不影响构建产物。
  - 非无头浏览器 smoke：启动 `http://127.0.0.1:5173/`，执行 `node .codex_run/refine-visible-smoke.mjs`，结果 `REFINE_VISIBLE_SMOKE_PASS`，截图 `.codex_run/refine-visible-smoke.png`。覆盖工作台 tab 顺序、分页、统一新建线索弹窗、抽屉阶段截断、抽屉关闭、今日提醒新建线索入口和中文渲染。
- 未覆盖项：
  - 指标卡视觉克制、抽屉宽度、关闭按钮不遮挡等纯视觉细节不做像素级自动断言，保留为非 TDD 例外；已通过结构断言和可见浏览器 smoke 补充。
