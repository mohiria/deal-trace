# Lightweight Test Design — frontend-lead-flow

## Context

- Requirement / Spec: `openspec/changes/frontend-lead-flow/specs/frontend-workbench/spec.md`（6 条 ADDED 需求）
- Change summary: 销售线索旅程——我的线索列表、公海浏览与认领、线索详情与进度跟踪、阶段推进与赢单/流失、主动退回公海、闭单只读
- Target modules / pages: `src/api/leads.ts`、`src/utils/lead.ts`、`src/stores/leads.ts`、`src/views/{MyLeadsView,PublicPoolView,LeadDetailView}.vue`、`src/router`
- Test environment / constraints: vitest + jsdom + @vue/test-utils，MSW 在 axios 边界拦截（复用 `src/test/setup.ts` + `src/test/msw/*`）；E2E 用 Playwright 打真后端。tsconfig 严格模式。金额走字符串精确处理（CLAUDE.md 禁 float/double）。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria（PRD §7.3–§7.11、frontend-workbench delta spec）
- [x] Existing behavior baseline: tests / code / API contract（`api/client.ts` 拦截器与 `ApiError`、frontend-shell 已验证模式）
- [x] Data model / field rules（`LeadView` 17 字段、`PoolLeadView` 12 字段、`ProgressLogView` 7 字段；stage/businessType/method/loseReason 中文枚举值）
- [x] API contract / auth rules / error shape（`/leads/*` 端点；`LEAD_ALREADY_CLAIMED`/`LEAD_ENDED_READONLY`/`VALIDATION_ERROR`/`FORBIDDEN`）
- [x] UI states / user roles / user paths（ADMIN/SALES，列表/公海/详情/闭单只读）
- [x] Code structure / changed code / dependency graph（D1 API 层、D2 store、D4 isClosed 派生、D5 错误分支、D6 金额精确）
- [x] Existing tests / historical defects（不回归 frontend-shell 的拦截器/store/守卫测试）
- [x] Test data / credentials / mocks / CI constraints（MSW handler 工厂；E2E 用部署注入 Admin/Sales）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 公海电话脱敏 | 后端 PhoneMasker（lead-ownership spec） | frontend-workbench spec R2 | extends（前端如实展示后端返回值，不自掩码） | PRD §7.5 / tech-arch §9.4 | Proceed |
| 闭单只读 | 后端 LEAD_ENDED_READONLY（lead spec） | frontend-workbench spec R7 | extends（前端收起入口 + 后端兜底回落） | PRD §7.7 | Proceed |
| 金额精确 | CLAUDE.md 金额禁 float/double | design D6 | constrains（前端字符串采集/提交，不经 number 运算） | CLAUDE.md / PRD §7.11.1 | Proceed |

无 `conflicts`。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 各 lead API 命中正确方法/路径并 unwrap | design D1 | 等价类 | Unit | 各端点 MSW 成功 | 返回业务负载 | 请求方法/路径 + 返回值 | P0 | `src/api/leads.spec.ts` |
| API 错误归一为 ApiError 带 code | design D1/D5 | 等价类 | Unit | MSW 错误信封 | 抛 ApiError(code) | 异常字段 | P0 | `src/api/leads.spec.ts` |
| `isClosed` 对结束/非结束阶段判定 | design D4 / PRD §7.7 | 决策表 | Unit | 6 个阶段值 | 已赢单/已流失=true 其余 false | 返回值 | P0 | `src/utils/lead.spec.ts` |
| 金额校验（0/负/三位小数/合法两位） | design D6 / PRD §7.11.1 | 边界值 | Unit | 多组金额串 | 仅 >0 且 ≤2 位为真 | 返回布尔 | P0 | `src/utils/lead.spec.ts` |
| 金额千分位格式化 | PRD §7.11.1.4 | 边界值 | Unit | 含小数/大数 | 千分位字符串 | 返回串 | P1 | `src/utils/lead.spec.ts` |
| store 列表/详情/进度加载写态 | design D2 | 状态迁移 | Unit(store) | MSW 成功 | 对应 state 填充 | store 状态 | P0 | `src/stores/leads.spec.ts` |
| claim 成功移出 pool；release 移出 myLeads；addProgress 置顶+刷新 lastTrackedAt | design D2 | 状态迁移 | Unit(store) | MSW 成功 | 跨视图联动正确 | store 状态 | P0 | `src/stores/leads.spec.ts` |
| 写动作收 ApiError 透传不吞 | design D5 | 等价类 | Unit(store) | MSW 错误 | 抛 ApiError | 抛出 | P0 | `src/stores/leads.spec.ts` |
| Sales 见名下/Admin 见全部+进入详情+空态 | spec R1 | 决策表 | Unit(组件) | role×数据 | 正确列表+路由 | DOM+路由 | P0 | `src/views/MyLeadsView.spec.ts` |
| 公海 Sales 脱敏+认领入口；Admin 明文+无认领 | spec R2 | 决策表 | Unit(组件) | role 两态 | 电话/入口正确 | DOM | P0 | `src/views/PublicPoolView.spec.ts` |
| 认领成功离开公海；`LEAD_ALREADY_CLAIMED` 提示+刷新 | spec R2 | 场景 | Unit(组件) | MSW 成功/冲突 | 正确反馈 | DOM+store | P0 | `src/views/PublicPoolView.spec.ts` |
| 详情渲染字段+流失原因/说明+进度倒序+无编辑删除 | spec R3 | 场景 | Unit(组件) | 详情/进度 fixture | 正确渲染 | DOM | P0 | `src/views/LeadDetailView.spec.ts` |
| 空跟踪内容拦截不发请求；合法追加置顶+刷新 lastTrackedAt | spec R4 | 边界值 | Unit(组件) | 空/合法 | 拦截/成功 | 无请求/DOM | P0 | `src/views/LeadDetailView.spec.ts` |
| 非结束阶段变更；赢单金额非法拦截；合法赢单千分位；流失"其他"必填说明；闭单无重复入口 | spec R5 | 决策表+边界 | Unit(组件) | 多组 | 正确校验/状态 | DOM/无请求 | P0 | `src/views/LeadDetailView.spec.ts` |
| Sales 名下未结束可退回；备注空拦截；退回成功离开名下 | spec R6 | 场景+边界 | Unit(组件) | 空/合法 | 拦截/成功 | DOM/store | P0 | `src/views/LeadDetailView.spec.ts` |
| 已结束线索收起全部写入口；写操作遇 `LEAD_ENDED_READONLY` 提示+刷新只读 | spec R7 | 决策表 | Unit(组件) | 闭单 fixture | 无写入口/回落 | DOM | P0 | `src/views/LeadDetailView.spec.ts` |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| lead API 封装 | `leads.spec.ts` | 模块/函数未实现 | 函数缺失/返回不符 | 实现 `api/leads.ts` | 不回归 `client.ts` |
| 纯函数工具 | `lead.spec.ts` | 函数未实现 | 断言失败 | 实现 `utils/lead.ts` | — |
| leads store | `leads.spec.ts`(store) | store 未实现 | 态未填充/联动缺失 | 实现 `stores/leads.ts` | — |
| 我的线索/公海/详情视图 | 各 `*.spec.ts` | 组件不存在 | 渲染/校验/分支断言失败 | 实现各视图 | — |

## E2E Scenarios

| Scenario | Persona | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| 认领→我的线索→详情→追加进度→赢单只读 | 部署注入 Sales | 后端 + dev server 运行；公海有可认领线索 | 公海认领 → 我的线索见线索 → 进详情 → 追加进度 → 赢单后只读 | 认领后入名下；进度置顶；赢单后写入口消失 | 数据残留交后端测试隔离 | screenshot / trace |

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 视图静态布局/样式（token/表格列宽/弹窗骨架） | 纯展示 | 渲染冒烟 + build 通过 | 视觉偏差，不影响行为 |
| 并发认领真实竞态 | 仅真后端可复现 | 组件测断"收到 `LEAD_ALREADY_CLAIMED`→提示+刷新"；真并发交后端测试 + E2E | 前端只能验响应处理，不验竞态本身 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 真后端 + 初始 Admin/Sales 凭据 + 公海线索 | E2E 场景 | 部署配置注入凭据并起后端、备数据 | 仅 E2E 执行时需要（组件测试用 MSW 不阻塞） |

## Coverage Closure

- [x] 每个 in-scope 可执行 test point 规划了 coverage artifact 路径
- [ ] 新增/修改测试已执行并记录（随各 TDD 组推进更新）
- [ ] 严格 TDD 项的 Red 因预期行为原因失败（随推进记录）
- [x] 语法/导入/fixture/环境失败不计为 Red 证据
- [ ] 命令/报告/日志作为执行证据记录（QA 报告阶段补）

## Notes

- Uncovered test points: Admin 分配/回收/转移、系统日志面板、新建线索（Non-Goal，随后续 change）
- Remaining risks: 详情页跨 change 拼装（frontend-admin 追加归属/日志区块）；金额字符串边界
- Execution evidence: 见 `qa/qa-test-report.md`（实现完成后补）
