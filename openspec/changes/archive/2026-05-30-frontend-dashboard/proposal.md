## Why

frontend-shell 交付了登录 / 会话 / 角色导航骨架，frontend-lead-flow 与 frontend-customer 落地了线索旅程与客户录入闭环，但工作台首页（路由 `/`，名 `workbench`，标题"销售工作台"）至今仍是占位页。Dashboard 是 PRD §7.12 定义的、销售与管理者每日进入系统的第一屏与决策入口（PRD §3 目标），后端只读看板端点 `GET /api/dashboard` 已就绪（spec `dashboard`，按登录角色自动分流 Admin 全局 / Sales 个人口径），前端需把它落成真实指标看板，补齐工作台首屏。

## What Changes

- **工作台首屏指标看板**：把 `workbench` 占位路由替换为真实看板视图，进入工作台首屏即拉取 `GET /api/dashboard` 并渲染四项指标卡片：今日新增线索数、当前公海待认领数、本月赢单总金额、本月流失率。
- **角色口径透明**：看板不向后端传任何视角 / owner 参数，口径由后端按登录角色裁决（Admin 全局 / Sales 个人）；前端 SHALL NOT 自行按本人过滤或重算口径，仅原样渲染后端返回的数值。
- **指标渲染规则**：
  - 今日新增、公海待认领为整数计数，直接渲染。
  - 本月赢单总金额为精确数值（后端空集归一为 `0`），按人民币金额格式化展示，前端不做浮点重算。
  - 本月流失率：后端为 `null`（本月结束事件数为 0）时渲染 `--` 而非 `0%`（CLAUDE.md / PRD §7.12）；为数值 `0` 时渲染 `0%`；其余按百分比展示。
- **加载 / 失败态**：首屏拉取期间展示加载态；非鉴权类失败展示可重试的错误态，`UNAUTHORIZED` 由既有 axios 拦截器统一清退（不在本视图内重复处理）。
- **看板为只读**：视图 SHALL NOT 触发任何写操作，进入 / 刷新看板 SHALL NOT 产生系统日志（与后端只读契约一致）。

不在本批次：指标下钻 / 跳转到对应线索列表、原型中的同比文案（"比昨日 +3""6 条高价值"等装饰性副文案，后端无对应字段）、趋势 spark 走势图、自定义时间区间、任何后端代码 / 端点 / 错误码变更。

## Capabilities

### New Capabilities
<!-- 无新增能力；本批次向既有 frontend-workbench 追加需求。 -->

### Modified Capabilities
- `frontend-workbench`: 在登录 / 会话 / 角色导航与线索 / 客户旅程之上，追加"工作台首屏只读指标看板（四项指标、按登录角色裁决口径、流失率空值渲染 `--`、加载 / 失败态）"的可观察前端行为需求。

## Impact

- **前端代码**：新增工作台看板视图（替换 `workbench` 占位路由指向的 `PlaceholderView`），新增 dashboard API 封装（`src/api/dashboard.ts`：`fetchDashboard`，类型镜像后端 `DashboardView`）。
- **后端**：零改动，仅消费既有 `GET /api/dashboard` 端点（spec `dashboard`）与既有错误码（`UNAUTHORIZED`）。
- **依赖**：无新增运行时依赖；组件测试复用 frontend-shell 引入的 MSW（axios 边界）与 `--dt-*` 设计 token，E2E 复用 Playwright 真后端约定。
- **设计**：沿用 Arco Design Vue 组件体系与从 `prototype/dealtrace-workbench.html` `metric-grid` 区抽取的 `--dt-*` 设计 token（四卡栅格、`metric-value` / `metric-foot` 结构），禁止引入 Tailwind（tech-arch §10）。
