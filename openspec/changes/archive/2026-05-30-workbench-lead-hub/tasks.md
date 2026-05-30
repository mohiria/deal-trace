## 1. 抽取可复用详情面板（D1，回归优先）

- [x] 1.1 新建 `frontend/src/components/LeadDetailPanel.vue`：将 `LeadDetailView.vue` 的脚本逻辑与 `<section class="detail-page">…</section>` 主体整体搬入，`leadId` 改为 `defineProps<{ leadId: number }>()`（替代 `route.params.id`），其余逻辑（runWrite/写动作/闭单只读派生/onMounted 拉取，Admin 才拉 accounts）保持不变
- [x] 1.2 改写 `LeadDetailView.vue` 为薄壳：`const leadId = Number(route.params.id)` → `<LeadDetailPanel :lead-id="leadId" />`；样式归属随主体迁入面板
- [x] 1.3 跑 `LeadDetailView.spec.ts` 保持全绿（纯搬迁、行为零改动的回归闸）；如选择器因 DOM 包裹层变化失配，仅调测试选择器，不改写断言语义 —— 28/28 通过

## 2. 今日提醒派生纯函数（D3，先 Red）

- [x] 2.1 新建 `frontend/src/utils/workbench.test.ts`：为 `suggestedClaims(pool, limit)` 与 `staleOwnedLeads(myLeads, now, thresholdDays)` 写失败单测——含"取前 N 条""lastTrackedAt 为空计入未跟踪""早于阈值计入""已结束线索排除""派生不改写看板指标值"（Red：import 未解析）
- [x] 2.2 新建 `frontend/src/utils/workbench.ts`：实现上述纯函数与常量 `SUGGESTED_CLAIM_LIMIT = 5`、`STALE_TRACK_DAYS = 7`；复用 `utils/lead.ts` 的 `isClosed` 判终态；令 2.1 转绿 —— 11/11 通过

## 3. 工作台线索工作区 + 抽屉（spec R「内嵌线索工作区」，先 Red）

- [x] 3.1 在 `DashboardView.spec.ts` 追加失败用例：SALES 见名下表格、ADMIN 见全部表格、点击行打开抽屉并渲染 `LeadDetailPanel`、已结束线索写入口不呈现、抽屉不含系统日志（Arco `a-drawer` 须 `:render-to-body="false"`）—— Red 4 例
- [x] 3.2 在 `DashboardView.vue` 指标看板下方新增线索工作区表格：列对齐 `MyLeadsView`（客户/业务类型/年度/阶段/联系人/最后跟踪），SALES 用 `leads.myLeads`、ADMIN 用 `leads.allLeads`；行点击设置 `activeLeadId`
- [x] 3.3 在 `DashboardView.vue` 新增 `a-drawer`（`:render-to-body="false"`），内嵌 `<LeadDetailPanel :key="activeLeadId" :lead-id="activeLeadId" v-if="activeLeadId != null" />`；关闭抽屉清空 `activeLeadId`
- [x] 3.4 扩展 `DashboardView` `onMounted` 并发只读装载：保留 `load()`（dashboard），追加 `leads.loadMyLeads()`（ADMIN 走 `loadAllLeads()`）+ `loadPool()`；令 3.1 转绿

## 4. 今日提醒/待办区块（spec R「今日提醒/待办」，先 Red）

- [x] 4.1 在 `DashboardView.spec.ts` 追加失败用例：公海非空时 SALES 见"建议认领"条目、名下超阈值未跟踪时见"待跟踪"条目、认领入口不对 ADMIN 呈现、`LEAD_ALREADY_CLAIMED` 提示"该线索已被认领"并刷新公海（spy `loadPool`）、认领成功提示 —— Red 5 例
- [x] 4.2 在 `DashboardView.vue` 新增今日提醒区块：用 `utils/workbench.ts` 从 `leads.pool`/`leads.myLeads` 派生；"建议认领"内联认领复用 `PublicPoolView` 流程（`leads.claim(id)` + `LEAD_ALREADY_CLAIMED` 回落 + `loadPool()`，成功后 `loadMyLeads()`），ADMIN 不渲染认领入口；"待跟踪"条目点击打开对应线索抽屉；令 4.1 转绿

## 5. 枢纽元素与指标看板契约收窄（spec MODIFIED）

- [x] 5.1 `DashboardView.vue` 标题区加副文案 +「新建线索」「新建客户」快捷入口（纯路由导航至 `customers`，无新行为）
- [x] 5.2 在 `DashboardView.spec.ts` 断言：首屏加载仅命中只读 GET 端点（dashboard + leads/mine + pool），不向任何写端点发请求；指标值仍仅取 `GET /api/dashboard` 返回、前端不重算

## 6. 验证与回归

- [x] 6.1 跑前端全量单测（Vitest）：187/187 通过（83 suites），既有 `DashboardView/MyLeadsView/PublicPoolView/LeadDetailView` 测试保持绿
- [~] 6.2 已**编写** E2E 关键旅程（`tests/e2e/dashboard.spec.ts`：SALES 登录→工作台见线索表+今日提醒→点行开抽屉→详情，含"抽屉无系统日志"断言），skip-guard 同既有约定；`playwright --list` 编译通过。**未在本环境运行**——E2E 需真实后端（Spring Boot + MySQL 8.4）+ dev server + 注入 admin/sales 凭据（playwright.config 无 webServer），本环境未起该栈，剩余验证待联调环境执行
- [x] 6.3 前端 `npm run build`（vue-tsc + vite）通过；`openspec validate workbench-lead-hub --strict` 通过
- [x] 6.4 确认延后项未被偷偷实现：抽屉无系统日志（grep 0 + 测试断言）、表格 `:pagination="false"` 无分页/筛选、首屏无导出按钮（grep 0）；剩余风险已在 proposal/design 记录
