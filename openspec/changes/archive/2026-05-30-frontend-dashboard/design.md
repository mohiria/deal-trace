## Context

工作台首屏（路由 `/`，name `workbench`，meta.title "销售工作台"）当前指向 `PlaceholderView`（`frontend/src/router/index.ts`）。后端只读看板端点 `GET /api/dashboard` 已就绪（spec `dashboard` / PRD §7.12），其响应 DTO `DashboardView` 字段固定为：

```
todayNewLeadCount: long          // 今日新增（存量类，后端按角色裁决：Admin 全局 / Sales 当前归属本人）
openSeaUnclaimedCount: long      // 公海待认领（两视角同值，全局）
monthlyWonAmount: BigDecimal     // 本月赢单总金额（事件归属；空集归一为 0，非 null）
monthlyLossRate: BigDecimal|null // 本月流失率（结束事件数为 0 时为 null；分母>0 分子=0 时为 0）
monthlyLostEventCount: long      // 流失率分子（调试/测试用）
monthlyEndedEventCount: long     // 流失率分母（调试/测试用）
```

口径由后端依 `principal.role()` 裁决，端点不接受 owner / 视角参数。`apiClient`（`frontend/src/api/client.ts`，baseURL `/api`）的响应拦截器已 unwrap `ApiResponse` 信封并将 `UNAUTHORIZED` 统一清退到登录入口；其余业务错误码以可分支错误抛给调用方。这与既有 frontend-shell / frontend-customer 的 API 封装约定一致。

本批次是纯前端消费，沿用 frontend-customer 已建立的分层：视图不直接调 `apiClient`，统一经 `src/api/*.ts`；组件测试在 axios 边界用 MSW，E2E 用 Playwright 打真后端。

## Goals / Non-Goals

**Goals:**
- 把 `workbench` 占位路由替换为真实看板视图，首屏拉取 `GET /api/dashboard` 并渲染四项指标。
- 流失率空值 / 零值、金额空集归零的呈现规则严格对齐 spec 与 PRD §7.12。
- 加载态 / 非鉴权失败可重试态可观察；鉴权失效复用既有拦截器清退，不在视图内重复处理。

**Non-Goals:**
- 指标下钻 / 跳转、趋势 spark 图、原型装饰性副文案（"比昨日 +3" 等，后端无字段）。
- 任何后端、端点、错误码、DTO 改动。
- 自定义时间区间 / 视角切换（后端按角色裁决，无切换语义）。

## Decisions

### D1 — 新增 `src/api/dashboard.ts`，类型镜像 `DashboardView`
新增 `fetchDashboard(): Promise<DashboardView>`，`DashboardView` interface 镜像后端 6 字段。**类型契约（apply 期查证后确定，见 Open Questions 结论）**：后端无自定义 Jackson 配置，`BigDecimal` 默认序列化为 JSON 数字，故前端经 axios 收到的是 `number`——`monthlyWonAmount: number`、`monthlyLossRate: number | null`（本月结束事件数为 0 时为 `null`），计数字段为 `number`。金额百万级、比率 0~1，均在 JS `number` 安全精度内，不失真。理由：与 `src/api/customers.ts` 同构（双泛型 `<T, T>` 对齐 unwrap 后类型），保持视图与传输边界解耦。
- 备选：按 `string` 接收 — 否决，后端实发 JSON 数字，`JSON.parse` 已转 `number`，无法按字符串接收。
- 备选：视图内直接 `apiClient.get` — 否决，破坏既有分层约定，组件测试难以在 API 边界 mock。

### D2 — 新增 `DashboardView.vue`，替换 `workbench` 路由组件
路由表把 `name: 'workbench'` 的 `component` 从 `PlaceholderView` 改为新视图；其余路由不动。视图 `onMounted` 调 `fetchDashboard`，用三态（`loading` / `error` / `loaded`）ref 驱动渲染。
- 备选：复用 `PlaceholderView` 传 props — 否决，看板有独立数据流与三态，不是静态占位。

### D3 — 流失率与金额格式化为纯函数，单测覆盖边界
抽 `formatLossRate(rate: number | null)` 与 `formatWonAmount(amount: number)` 两个纯函数（放 `src/utils/` 或视图同目录）：
- `formatLossRate`: `null → '--'`；其余为**比率值**（后端 scale 4，如 `0.4` / `0.25` / `0`），渲染时 ×100 为百分比，保留至多 1 位小数、整数不带 `.0`（`0 → '0%'`、`0.4 → '40%'`、`0.182 → '18.2%'`），用 `Intl.NumberFormat(style:'percent', maximumFractionDigits:1)`。
- `formatWonAmount`: 按人民币千分位呈现（如 `¥386,000`），`0 → '¥0'`，用 `Intl.NumberFormat` 避免浮点重算。
理由：spec 的空值/零值分支是最易错点（CLAUDE.md 流失率分母为 0 显示 `--` 不是 `0%`），纯函数便于单测穷举边界，不依赖渲染。
- 备选：模板内三元表达式 — 否决，边界逻辑散落模板难测、易回归。

### D4 — 加载/失败态与鉴权清退的分工
视图只处理 `loading` 与「非鉴权失败」可重试态；`UNAUTHORIZED` 不在视图内捕获处理——既有响应拦截器已清退到登录入口（frontend-workbench 既有需求）。视图 `catch` 中对非鉴权错误置 `error=true` 并提供"重试"按钮重新调用 `fetchDashboard`。
- 备选：视图内自行判 401 — 否决，重复既有拦截器职责，违反单一清退路径。

### D5 — 设计 token 与布局
复用 `prototype/dealtrace-workbench.html` `metric-grid` 区抽出的 `--dt-*` token：四卡栅格、`metric-head`（指标名）/`metric-value`（大号数值）结构。用 Arco `a-spin`/`a-result` 或等价表达加载/失败，卡片用原生结构 + `--dt-*` token（禁 Tailwind，tech-arch §10）。不复刻原型的 `spark` 走势与副文案。

## Risks / Trade-offs

- **[BigDecimal 经 JSON 变 number 仍可能失真]** → API 层按字符串接收（若后端序列化为 number，axios 已转 JS number，金额精度在百万级人民币、流失率比率范围内安全；仍优先用 `Intl.NumberFormat` 而非手算）。如后端实际以 number 下发，`DashboardView` 字段类型在实现期对齐真实响应（见 Open Questions）。
- **[流失率比率的小数位约定]** → 后端 `monthlyLossRate` 为比率（如 `0.182`）还是百分数（`18.2`）影响格式化；实现期以真实响应为准校准 `formatLossRate`，单测固定预期。
- **[首屏多请求竞态]** → 看板仅一个只读请求，无并发写，竞态风险低；快速来回切路由时用组件卸载守卫忽略过期响应。

## Open Questions（apply 期已确认，留痕）

- **流失率数值约定** → 已确认为**比率**（非百分数）：`DashboardService.lossRate` 以 `lost/ended` 计算、scale 4、HALF_UP；`DashboardControllerTest` 断言 `monthlyLossRate` = `0.4`（2/5）、`0.25`（1/4）、`0.0`、`null`。前端 `formatLossRate` ×100 渲染为百分比。
- **JSON 序列化形态** → 已确认为 **JSON 数字**：后端无自定义 Jackson 配置，`BigDecimal` 默认序列化为数字；测试断言 `monthlyWonAmount` = `150000.50` / `0`（数字字面量）。前端字段类型定为 `number` / `number | null`，非 `string`。

## Migration Plan

纯前端增量：新增 `src/api/dashboard.ts` 与 `DashboardView.vue`，改一处路由组件引用。无迁移、无后端部署依赖。回滚 = 把 `workbench` 路由组件改回 `PlaceholderView` 并移除新增文件。
