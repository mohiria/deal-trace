# QA Test Report — frontend-customer

## Conclusion

- Overall result: PASS（单元/组件层）；E2E BLOCKED（缺真后端凭据，按既定 `test.skip` 守卫跳过）
- Requirement / change ID: `frontend-customer`（modifies `frontend-workbench`）
- QA owner: vibe-coding-qa（TDD 落地）
- Date: 2026-05-30
- Summary: 客户列表/搜索、创建客户、客户可搜索下拉选择器、新建线索+查重预检全部以 TDD 落地。单元/组件套件由 77 → 103（新增 26 个用例）全绿；`vue-tsc` 类型检查 + `vite build` 通过；`openspec validate --strict` 通过。后端零改动。

## Evidence Guide

| Evidence type | What to record | Example |
| --- | --- | --- |
| Execution evidence | 命令 + 结果 | `pnpm test:unit` PASS 103/103 |
| Behavioral evidence | 断言证明的行为 | 联系电话非法时不发 POST /leads |
| Coverage evidence | 测试文件#用例 | `src/views/CustomersView.spec.ts` |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | `customers.ts` / `leads.ts` 增量 / `isValidContactPhone` 契约与纯函数 |
| API/integration | No | 前端批次；后端契约由 backend 既有集成测试覆盖 |
| E2E | Yes（BLOCKED） | `tests/e2e/customer-flow.spec.ts`，缺凭据 `test.skip` |
| Regression | Yes | 既有 77 用例全绿，无回归 |
| Runtime QA validation | No | 未起 dev server；类型检查 + build 作为静态可用性证据 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| USCI 归一化/校验位 | customer spec R3（后端权威） | frontend-customer spec R2 | extends | customer spec | Add（前端只校验非空） | Implement（前端不复算，D8） |
| 新建线索归属（指定 Sales） | lead spec 归属规则 | proposal Non-Goals | extends | proposal | Add（仅归己/公海） | Implement（Admin 隐藏归属，默认公海） |
| 查重三态 + 历史流失提示 | lead spec 查重 + 预检端点 | frontend-customer spec R4 | extends | lead spec + CLAUDE.md | Add | Implement（预检闸门 + 历史流失展示） |

无 `conflicts`。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| customers API 契约 | spec R1/R2 | 实现前 `src/api/customers.ts` 未导出函数（测试先写） | 行为：函数未定义/请求形态未达成 | `pnpm test:unit src/api/customers.spec.ts` PASS 4/4 | 全套 103 PASS | `src/api/customers.spec.ts` | PASS |
| createLead/duplicateCheck 契约 | spec R4 / lead spec R6 | 增量用例先写，函数缺失 | 行为：query/body 未达成 | `leads.spec.ts` PASS 14/14 | 全套 PASS | `src/api/leads.spec.ts#createLead`,`#duplicateCheck` | PASS |
| isValidContactPhone | lead spec R3 | 增量用例先写，函数缺失 | 行为：手机号/座机/非法判定缺失 | `lead.spec.ts` PASS 12/12 | 全套 PASS | `src/utils/lead.spec.ts#isValidContactPhone` | PASS |
| CustomerSelect 选中/无捷径 | spec R3 | 组件未实现，断言先写 | 行为：候选渲染/emit/无新建捷径未达成 | `CustomerSelect.spec.ts` PASS 3/3 | 全套 PASS | `src/components/CustomerSelect.spec.ts` | PASS |
| 创建客户即时校验/错误分支 | spec R2 | 断言先写（含 `render-to-body` 缺陷暴露的真红） | 行为：空白未拦截/错误码未分支 | `CustomersView.spec.ts` PASS | 全套 PASS | `src/views/CustomersView.spec.ts` | PASS |
| 新建线索预检闸门/历史流失/归属 | spec R4 | 断言先写 | 行为：阻塞未拦截/历史未展示/assignToPool 未带 | `CustomersView.spec.ts` PASS 13/13 | 全套 PASS | `src/views/CustomersView.spec.ts` | PASS |

补充 Red 证据说明：组件测试首跑出现 9 红，根因为 `a-modal` 误写 `render-to-body="false"`（字符串 truthy → 内容渲染到 body 外，`.cs-search`/`.customer-name input` 找不到）。修正为 `:render-to-body="false"`（布尔）后转绿——这是一次"测试先行捕获实现缺陷"的有效 Red→Green，而非环境性失败。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| `--dt-*` token / scoped CSS | 纯样式低风险展示 | 沿用既有视图样式；build 通过 | 低 |
| 路由占位替换（router/index.ts） | 配置改动 | 既有 guards 测试 + build 通过 | 低 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit/组件 | 全套 15 文件 | `pnpm test:unit` | PASS | 103 passed (was 77, +26) |
| 类型/构建 | vue-tsc + vite build | `pnpm build` | PASS | `dist/` 产出，无类型错误 |
| 规格校验 | openspec | `openspec validate frontend-customer --strict` | PASS | "is valid" |
| QA 产物 | qa_artifacts check | `node .claude/skills/vibe-coding-qa/scripts/qa_artifacts.mjs check lightweight-test-design ...` | PASS | structure check completed |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| `tests/e2e/customer-flow.spec.ts` | BLOCKED | 缺 `E2E_SALES_EMAIL`/`E2E_SALES_PASSWORD` 与运行中的真后端 | 启动 backend+frontend 并注入 Sales 凭据 | 端到端"客户→线索"闭环未自动化验证；组件层已覆盖各分支 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| 无关键词列表/关键词搜索/空态 | 组件 | 列表渲染 name/usci；去抖后搜索；空态 | `src/views/CustomersView.spec.ts` | COVERED |
| 创建客户即时校验 + 成功入列表 + 重复/校验错误 | 组件 | 空白不发请求；成功入列表；DUPLICATE/VALIDATION 分支 | `src/views/CustomersView.spec.ts` | COVERED |
| 选择器搜索/选中/无新建捷径 | 组件 | emit customerId + 客户对象；无捷径 | `src/components/CustomerSelect.spec.ts` | COVERED |
| 新建线索即时校验/不渲染年度阶段 | 组件 | 电话非法不发请求；无 `.lead-year`/`.lead-stage` | `src/views/CustomersView.spec.ts` | COVERED |
| 预检阻塞/历史流失/归属/错误回落 | 组件 | 阻塞拦截；历史展示原因+时间；assignToPool；错误透传 | `src/views/CustomersView.spec.ts` | COVERED |
| 契约：customers/leads 增量 + 电话校验 | Unit | 请求方法/路径/query/body + 正则判定 | `src/api/*.spec.ts`,`src/utils/lead.spec.ts` | COVERED |
| 客户→线索端到端闭环 | E2E | — | `tests/e2e/customer-flow.spec.ts` | BLOCKED |

## Regression Scope

- Changed behavior: 新增客户管理页 + 新建线索；`customers` 占位路由替换为真实视图；`leads.ts`/`utils/lead.ts`/MSW handlers 追加（不改既有导出）。
- Directly impacted old behavior: 无——所有新增均为追加；既有 `leads.ts` 函数、`isValidAmount`/`isClosed`、路由其它子路由不变。
- Historical defects considered: 无前端历史缺陷记录涉及本面。
- Requirement-driven test additions/modifications/deletions: 仅新增；未修改或删除既有用例。
- Regression risk level: Low
- Selected regression tests and why: 全量 `pnpm test:unit`（既有 77 全绿确认无回归）+ `pnpm build`（类型层确认 leads.ts 增量未破坏既有消费方）。

## Runtime QA Validation

Runtime QA validation is availability smoke evidence only. It does not count as Unit/API/E2E business coverage.

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| — | 未起 dev server（静态校验已足够覆盖本批次行为） | N/A | — | — |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| 组件测试首跑 9 红 | Code | `a-modal` 写成 `render-to-body="false"`（字符串）使内容渲染到 body 外 | 改为 `:render-to-body="false"` 布尔绑定 | 现有组件用例已覆盖弹窗内字段交互 |

## Failure Learning

- Learning recorded or recommended: Yes
- Knowledge location: 本报告 Failure Analysis；可考虑写入项目记忆"Arco a-modal 测试须 `:render-to-body=\"false\"` 布尔绑定"。
- Summary: Arco Modal 的 `renderToBody` 是 Boolean prop，静态属性 `="false"` 传字符串 truthy；测试中弹窗内容须内联渲染才可被 `wrapper.find` 命中。

## Remaining Risks

- Uncovered test points: 预检通过但提交时三元组被并发占用——组件层只断"收到 DUPLICATE_* code → 提示不伪造"，真并发交后端既有测试。
- Unresolved prerequisite blockers: E2E 需真后端 + Sales 凭据。
- Requirement authority conflicts: 无。
- Known flaky areas: 去抖搜索测试用 `debounceMs:0` + `setTimeout(0)` tick，确定性触发，无 fake-timer 脆弱性。
- Manual follow-up: 真后端联调时跑一次 `customer-flow.spec.ts`。

## Final Statement

frontend-customer 的 4 条行为契约全部以 TDD 落地：API/纯函数走严格契约 Red→Green，组件层经"测试先行→暴露 `render-to-body` 实现缺陷→修正转绿"的有效 Red→Green。单元/组件 103/103 全绿、类型检查与构建通过、规格 `--strict` 校验通过、QA 产物结构校验通过；既有 77 用例无回归。唯一未运行项为依赖真后端凭据的 E2E（已按既定 `test.skip` 守卫跳过，组件层已覆盖其各分支）。无需求冲突，后端零改动。
