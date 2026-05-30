# QA Test Report

## Conclusion

- Overall result: PASS
- Requirement / change ID: refine-lead-list-and-drawer-experience（frontend-workbench delta，6 条 ADDED 需求）
- QA owner: change 执行期 QA + 归档前补录复跑
- Date: 2026-05-31
- Summary: 本轮为既有前端工作台体验的 `amends`/`extends`：列表搜索与标准分页、统一新增线索入口（抽取 `CreateLeadModal`）、工作台三 tab（我的/公海/全部）、抽屉阶段截断与宽度、顶部去角色与指标卡视觉简化。不改后端接口、权限、线索状态机与字段契约。归档前复跑全量前端单元测试 **23 files / 202 tests 全部 PASS**，含本 change 全部受影响文件；类型检查、构建、可见浏览器 smoke 由 change 执行期记录通过。

> 补录说明：本报告在归档环节据实补齐。`tasks.md` 5.1/5.5 当时已勾选但未落地独立 QA 报告产物；Red/Green/smoke/build 证据原记录于 `regression-impact-analysis.md` 的「执行记录」段。下方「Tests Run」中的全量 202 passed 为本次归档前亲自复跑验证；smoke/build/vue-tsc 行明确标注引自 change 执行期记录，未在归档时重跑。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | Vitest 组件/视图/store/api 单元与组件级 |
| API/integration | No | 本轮不触后端接口契约，无新增后端集成测试 |
| E2E | No | 以可见浏览器 smoke 辅助，未新增 Playwright 旅程 |
| Regression | Yes | 全量前端单元测试套件复跑作回归 |
| Runtime QA validation | Yes | 可见浏览器 smoke（可用性），非业务覆盖 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 工作台第三 tab 语义 | 既有 spec「销售工作台按原型呈现指标与三类线索视图」= 我的/公海/**本月结束** | 本 change spec「销售工作台线索视图调整为我的公海全部」= 我的/公海/**全部线索** | supersedes（实质替换第三 tab）但 delta 作者标为 ADDED | 用户明确需求 + 本 change spec | 新增「全部线索」tab 测试；旧「本月结束」tab 测试未删 | 实现「全部线索」合并视图 |
| 列表搜索/分页 | 既有列表无标准分页 | 本 change spec ADDED | extends | 本 change spec | 新增搜索/分页测试，保留既有数据集与认领/详情断言 | 前端派生分页，不改接口 |
| 统一新增线索入口 | 各页分散入口 | 本 change spec ADDED | amends | 本 change spec | 抽取 `CreateLeadModal` 测试，复用查重/历史流失/阻断断言 | 抽取共享组件并接入各入口 |

> ⚠️ 遗留契约冲突：sync 后主 spec 同时存在「本月结束」与「全部线索」两条矛盾需求（delta 用 ADDED 而非 MODIFIED）。归档未私自删旧条款；建议后续 change 以 MODIFIED 显式收敛。详见归档摘要。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 工作台 tab 顺序我的/公海/全部 | spec | change 执行期 vitest 子集 FAIL | 工作台仍为旧 tab，无「全部线索」 | 复跑全量 PASS | 全量 202 passed | `src/views/DashboardView.spec.ts#三 Tab 线索工作区` | PASS |
| 全部线索合并当前可访问数据 | spec | 同上 FAIL | 无合并视图 | 复跑 PASS | 同上 | `src/views/DashboardView.spec.ts` | PASS |
| 列表搜索后分页重置第一页 | spec | 同上 FAIL | 列表缺搜索/分页 | 复跑 PASS | 同上 | `src/views/MyLeadsView.spec.ts#支持搜索和标准分页` | PASS |
| 统一新增线索弹窗（多入口） | spec | 同上 FAIL（`CreateLeadModal.vue` 缺失） | 共享组件未抽取 | 复跑 PASS | 同上 | `src/components/CreateLeadModal.spec.ts` | PASS |
| 查重阻断不提交 createLead | 既有客户页行为 + spec | 同上 FAIL | 阻断逻辑未进共享组件 | 复跑 PASS | 同上 | `src/components/CreateLeadModal.spec.ts#查重阻断时展示阻断提示并禁止提交 createLead` | PASS |
| 仅历史流失允许提交并提示 | spec | 同上 FAIL | 历史流失提示缺失 | 复跑 PASS | 同上 | `src/components/CreateLeadModal.spec.ts#仅有历史流失时展示提示且允许提交` | PASS |
| 抽屉阶段轨道只到当前阶段 | spec | 同上 FAIL | 仍展示未来阶段 | 复跑 PASS | 同上 | `src/views/LeadDetailView.spec.ts` | PASS |
| 顶部不显示角色 | spec | 同上 FAIL | 仍显示角色文案 | 复跑 PASS | 同上 | `src/components/AppShell.spec.ts` | PASS |
| 今日提醒新增线索触发统一入口 | spec | 同上 FAIL | 入口未统一 | 复跑 PASS | 同上 | `src/components/AppShell.spec.ts` | PASS |
| 客户页去 Table/Modal 标签 + 分页 | spec | 同上 FAIL | 无意义标签存在、无分页 | 复跑 PASS | 同上 | `src/views/CustomersView.spec.ts#不展示无意义的 Table/Modal 标签并支持分页` | PASS |
| 用户管理搜索 + 分页 | spec | 同上 FAIL | 列表无搜索分页 | 复跑 PASS | 同上 | `src/views/UsersView.spec.ts#支持账号搜索和标准分页` | PASS |
| 公海搜索分页且保留认领 | spec | 同上 FAIL | 列表无搜索分页 | 复跑 PASS | 同上 | `src/views/PublicPoolView.spec.ts#refine public pool list iteration` | PASS |

Red failure reason 详情引自 `regression-impact-analysis.md` 执行记录段（统一弹窗缺失、AppShell 仍显角色/入口未统一、工作台旧 tab+spark、列表缺搜索分页、抽屉仍展示未来阶段），均为预期行为差距，非环境/导入失败。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 抽屉宽度、关闭按钮不遮挡阶段、按钮间距 | 纯视觉表现，无像素级业务断言价值 | 结构断言 + 可见浏览器 smoke + 人工查看 | 窄屏回落单列等响应式细节依赖人工 |
| 指标卡弱视觉/移除 spark 图形 | 纯视觉简化，语义值不变（仍来自 dashboard） | DashboardView 指标值断言 + smoke | 视觉观感主观，不做自动断言 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit + Regression（全量复跑） | 全部 23 个测试文件 | `pnpm -C frontend test:unit` | PASS | 23 files / **202 tests passed**, duration 23.45s（2026-05-31 归档前亲自复跑） |
| Unit（change 执行期受影响子集） | CreateLeadModal/DashboardView/MyLeadsView/PublicPoolView/CustomersView/UsersView/LeadDetailView/AppShell | `pnpm exec vitest run <8 files>` | PASS | 8 files / 97 passed（引自执行记录） |
| 类型检查 | vue-tsc | `pnpm exec vue-tsc -b` | PASS | 引自执行记录，归档时未重跑 |
| 构建 | vite build | `pnpm build` | PASS | 仅 chunk size 警告，引自执行记录，归档时未重跑 |
| Runtime smoke | 可见浏览器 | `node .codex_run/refine-visible-smoke.mjs` | PASS | `REFINE_VISIBLE_SMOKE_PASS`，截图 `.codex_run/refine-visible-smoke.png`（引自执行记录，归档时未重跑） |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 后端 API/集成测试 | Not applicable | 本轮不触后端接口契约 | 无 | 低 |
| Playwright E2E 新旅程 | Not applicable | 本轮以可见浏览器 smoke 替代关键路径 | 无 | 中：跨页统一弹窗端到端仅 smoke 覆盖 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| 工作台三 tab + 全部线索合并 | Unit | tab 顺序与合并行展示正确 | `src/views/DashboardView.spec.ts` | COVERED |
| 统一新增线索 + 查重阻断 + 历史流失 | Unit | 阻断不调 createLead；历史流失允许提交 | `src/components/CreateLeadModal.spec.ts` | COVERED |
| 列表搜索分页（my/pool/customers/users） | Unit | 搜索后回第一页、总数更新、入口保留 | `MyLeadsView/PublicPoolView/CustomersView/UsersView.spec.ts` | COVERED |
| 抽屉阶段截断 + 顶部去角色 + 今日提醒入口 | Unit | 阶段轨道仅到当前；无角色文案；提醒触发统一弹窗 | `LeadDetailView.spec.ts` / `AppShell.spec.ts` | COVERED |
| 抽屉宽度/按钮位置/指标卡视觉 | Runtime smoke | 可见浏览器无遮挡、中文渲染正常 | `.codex_run/refine-visible-smoke.*` | COVERED（非 TDD） |

## Regression Scope

- Changed behavior: 列表搜索分页、新增线索入口统一、工作台第三 tab、抽屉阶段展示与宽度、顶部信息与指标卡视觉。
- Directly impacted old behavior: DashboardView/MyLeadsView/PublicPoolView/CustomersView/UsersView/AppShell/LeadDetailPanel；保留登录、路由守卫、角色显隐、认领、客户重复反馈、闭单只读、用户管理不展示密码等既有行为。
- Historical defects considered: Arco a-modal 测试须 `:render-to-body="false"`（见项目记忆）——CreateLeadModal 测试已规避 teleport 找不到节点问题。
- Requirement-driven test additions / modifications / deletions: 新增 `CreateLeadModal.spec.ts`；修改 7 个视图/组件 spec 增补搜索分页/tab/抽屉/入口断言；未删除既有负例或弱化断言。
- Regression risk level: Low
- Selected regression tests and why: 复跑全量前端单元套件（202 tests），确认本轮改动未破坏既有登录/权限/认领/闭单/客户/用户行为。

## Runtime QA Validation

Runtime QA validation is availability smoke evidence only.

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| `http://127.0.0.1:5173/` | 工作台 tab/分页/统一新建线索/抽屉阶段截断/抽屉关闭/今日提醒入口/中文渲染 | PASS | `REFINE_VISIBLE_SMOKE_PASS` + 截图（引自执行记录） | dev server 已停 |

## Failure Analysis

无失败项。归档前全量复跑 202/202 PASS。

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| （无） | — | — | — | — |

## Failure Learning

- Learning recorded or recommended: No（本轮无失败）
- Knowledge location: 项目记忆 `arco-modal-render-to-body-test`（已规避，非本轮新增）
- Summary: 无新增失败学习。

## Remaining Risks

- Uncovered test points: 抽屉响应式窄屏回落单列、按钮间距等纯视觉，仅 smoke/人工覆盖，无自动断言。
- Unresolved prerequisite blockers: 无。
- Requirement authority conflicts: ⚠️ 主 spec「本月结束」与「全部线索」两条 tab 需求并存矛盾（delta 用 ADDED 未 MODIFIED），需后续 change 以 MODIFIED 收敛。
- Known flaky areas: 无；environment 阶段耗时较长（约 137s）但稳定通过。
- Manual follow-up: 后续若接口改为服务端分页，需另起 OpenSpec change 调整契约（前端当前为已加载数组派生分页）。

## Final Statement

本 change 的前端体验改动以 TDD 落地（先红后绿，Red 失败原因均为预期行为差距），归档前亲自复跑全量前端单元测试 23 files / 202 tests 全部 PASS，覆盖本轮全部受影响视图与新增 `CreateLeadModal` 组件；纯视觉项按非 TDD 例外以结构断言 + 可见浏览器 smoke 验证。类型检查、构建、浏览器 smoke 由 change 执行期记录通过（归档时未重跑，已标注来源）。回归风险低。唯一遗留为主 spec 中「本月结束 vs 全部线索」的契约并存冲突，需后续 change 显式收敛——本报告与归档均未私自删改旧条款。Overall result: PASS。
