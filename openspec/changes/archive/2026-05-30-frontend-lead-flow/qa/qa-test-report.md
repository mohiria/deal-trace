# QA Test Report — frontend-lead-flow

## Conclusion

- Overall result: PASS（E2E 待真后端环境执行，已就绪并自动 skip 缺凭据场景）
- Requirement / change ID: `frontend-lead-flow`（capability `frontend-workbench` 线索旅程批次）
- QA owner: vibe-coding-qa（TDD 落地）
- Date: 2026-05-30
- Summary: 我的线索 / 公海认领 / 线索详情 + 进度跟踪 / 阶段推进 / 赢单 / 流失 / 退回公海 / 闭单只读 6 条 spec 需求以 TDD 落地。全量 77 个单元/组件测试全绿（既有 29 + 本批次 48），`vue-tsc` 类型检查 + vite 构建（743 模块）通过。E2E 关键旅程脚本就绪，依赖真后端 + 部署注入 Sales 凭据 + 公海备数据方可执行。

## Evidence Guide

| Evidence type | What to record |
| --- | --- |
| Execution | `pnpm test:unit` → 12 files / 77 tests PASS；`pnpm build` → vue-tsc + vite build PASS |
| Behavioral | 见下方 TDD Summary 各行"行为证据" |
| Coverage | `src/**/...spec.ts`（见 Coverage Summary） |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | API 封装 / 纯函数 / store |
| API/integration | Yes（前端侧）| 组件测试经 MSW 在 axios 边界拦截 `/leads/*` |
| E2E | Yes（就绪待执行）| Playwright 真后端栈，缺凭据自动 skip |
| Regression | Yes | frontend-shell 既有套件（拦截器/store/守卫/登录/外壳）随全量执行不回归 |
| Runtime QA validation | No | — |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 公海电话脱敏 | 后端 PhoneMasker（lead-ownership spec）| frontend-workbench spec R2 | extends | PRD §7.5 / tech-arch §9.4 | 组件断言展示后端返回值 | 前端不自掩码，如实展示 |
| 闭单只读 | 后端 LEAD_ENDED_READONLY（lead spec）| frontend-workbench spec R7 | extends | PRD §7.7 | 收起入口 + 后端拒绝回落两组用例 | `isClosed` 集中派生 + runWrite 回落 |
| 金额精确 | CLAUDE.md 金额禁 float/double | design D6 | constrains | CLAUDE.md / PRD §7.11.1 | 字符串校验/格式化纯函数用例 | 字符串采集/提交，不经 number 运算 |

无 `conflicts`。

## TDD Summary

| Test point | Source | Red evidence | Red failure reason | Green evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- |
| lead API 封装（11 函数 + 错误归一）| spec R1–R7 / D1 | `vitest run src/api/leads.spec.ts`（目标模块 `api/leads.ts` 尚不存在）| net-new 模块缺失（blocking 型）| 12 PASS | `src/api/leads.spec.ts` | PASS |
| 纯函数 `isClosed`/`isValidAmount`/`formatAmount` | D4/D6 | `vitest run src/utils/lead.spec.ts`（模块缺失）| net-new 模块缺失 | 8 PASS | `src/utils/lead.spec.ts` | PASS |
| leads store 加载 + 认领/退回/进度联动 | D2 | `vitest run src/stores/leads.spec.ts`（模块缺失）| net-new 模块缺失 | 5 PASS | `src/stores/leads.spec.ts` | PASS |
| 我的线索按角色/进入详情/空态 | spec R1 | `vitest run src/views/MyLeadsView.spec.ts`（组件缺失）| 组件缺失 | 4 PASS | `src/views/MyLeadsView.spec.ts` | PASS |
| 公海脱敏/明文/认领/冲突刷新 | spec R2 | 首跑 1 FAIL（`document.body` 取不到 Arco Message 文案）| 行为断言方式不当（Message 渲染到独立容器）| 改 spy `Message.warning`/`loadPool` 后 4 PASS | `src/views/PublicPoolView.spec.ts` | PASS |
| 详情/进度/追加/阶段/赢单/流失/退回/只读 | spec R3–R7 | 首跑 3 FAIL（进度提交未触发、流失原因未选中、只读回落未触发）| 行为缺陷型 Red：jsdom 下点击 submit 按钮不触发表单提交；`其他` 单选误命中进度跟踪方式同名项 | 改 form submit 触发 + radio 作用域限定后 15 PASS | `src/views/LeadDetailView.spec.ts` | PASS |

> 说明：net-new 模块（api/utils/store/视图）首个 Red 多表现为"目标模块/组件尚不存在"（blocking 型）。公海与详情两组在实现后产生了纯行为缺陷型 Red（符号已存在、断言因行为/测试驱动方式失败），经定位修复转 Green，证据最具行为意义；详见各 spec 文件 git 历史与上表失败原因列。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 视图静态布局/样式（`--dt-*` token、表格列宽、弹窗骨架）| 纯展示低风险 | build 通过 + 组件渲染冒烟 | 视觉偏差 |
| 并发认领真实竞态 | 仅真后端可复现 | 组件断"收到 `LEAD_ALREADY_CLAIMED` → 提示 + 刷新"；真并发交后端测试 + E2E | 前端仅验响应处理 |

## Tests Run

| Layer | Suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit/组件 | 全部 12 文件 | `pnpm test:unit` | PASS | 77/77 passed |
| 类型/构建 | — | `pnpm build` | PASS | vue-tsc + vite build 成功（743 模块）|
| E2E | lead-flow | `pnpm test:e2e` | 待执行 | 缺凭据自动 skip |

## Tests Not Run / Blockers

| Test / scope | Reason | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| E2E lead-flow | BLOCKED（环境）| 需真后端运行 + `E2E_SALES_EMAIL`/`E2E_SALES_PASSWORD` 与启用 Sales 一致 + 公海有可认领线索 | 起后端 + dev server + 设凭据 + 备数据 | 低：核心行为已由组件层 MSW 覆盖 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| lead API 命中/unwrap/错误归一 | Unit | 各端点经 MSW 返回业务负载；冲突归一 ApiError | `src/api/leads.spec.ts` | COVERED |
| isClosed / 金额校验 / 千分位 | Unit | 6 阶段判定；0/负/三位小数边界；千分位格式 | `src/utils/lead.spec.ts` | COVERED |
| store 加载与认领/退回/进度联动 | Unit | claim 移出 pool；release 移出 myLeads；进度置顶 + lastTrackedAt 刷新 | `src/stores/leads.spec.ts` | COVERED |
| 我的线索按角色/进入详情/空态 | 组件 | Sales 名下、Admin 全部、客户链接路由、空态文案 | `src/views/MyLeadsView.spec.ts` | COVERED |
| 公海脱敏/明文/认领入口/冲突 | 组件 | Sales 脱敏 + 认领按钮；Admin 明文 + 无按钮；冲突提示 + 刷新 | `src/views/PublicPoolView.spec.ts` | COVERED |
| 详情字段/流失信息/进度倒序/无编辑删除 | 组件 | 字段渲染；流失原因+说明；倒序首项；无编辑删除入口 | `src/views/LeadDetailView.spec.ts` | COVERED |
| 追加进度即时校验/成功联动 | 组件 | 空内容不发请求；成功置顶 + lastTrackedAt | 同上 | COVERED |
| 阶段/赢单（金额校验+千分位）/流失（其他必填）/无重复入口 | 组件 | 非法拦截；合法赢单已赢单+千分位预览；其他空说明拦截；闭单无重复入口 | 同上 | COVERED |
| 退回公海备注必填/成功离开名下 | 组件 | 空备注拦截；成功移出 myLeads | 同上 | COVERED |
| 闭单只读收起入口 + 后端回落 | 组件 | 已结束无任一写入口；`LEAD_ENDED_READONLY` 提示 + 刷新 | 同上 | COVERED |
| 认领→我的线索→详情→进度→赢单只读 | E2E | 关键旅程 | `tests/e2e/lead-flow.spec.ts` | BLOCKED（环境）|

## Regression Scope

- Changed behavior: 新增 `api/leads.ts`、`utils/lead.ts`、`stores/leads.ts`、`views/{MyLeadsView,PublicPoolView,LeadDetailView}.vue`；`router/index.ts` 占位路由替换为真实视图 + 新增 `leads/:id`；`test/msw/handlers.ts` 追加 `/leads/*` 工厂。
- Directly impacted old behavior: 无既有业务逻辑被改写；`api/client.ts`/`stores/auth.ts`/守卫/登录/外壳均未改动，其测试随全量套件 PASS。
- Historical defects considered: 见 memory——金额精确（不用 float/double，本批次以字符串处理规避）。
- Regression risk level: Low（后端零改动；新增为增量文件，路由仅占位替换）。
- Selected regression tests: frontend-shell 全部 29 用例随 `pnpm test:unit` 执行，PASS。

## Remaining Risks

- Uncovered: Admin 分配/回收/转移、系统日志面板、新建线索（Non-Goal，随后续 change）。
- Unresolved blockers: E2E 需真后端 + Sales 凭据 + 公海备数据（已文档化）。
- Conflicts: 无。
- Manual follow-up: 真环境跑一次 E2E 关键旅程；详情页跨 change 拼装时不重构既有区块。

## Final Statement

frontend-lead-flow 的 6 条 spec 行为以 TDD 落地：API/纯函数/store 净新模块经测试转 Green，公海与详情两组产生并修复了行为缺陷型 Red，77 个单元/组件测试全绿，类型检查与构建通过，frontend-shell 既有行为无回归。唯一未执行项为依赖真后端 + Sales 凭据 + 公海备数据的 E2E 关键旅程，已记录为环境型阻塞，核心行为已被组件层 MSW 覆盖，剩余风险低。
