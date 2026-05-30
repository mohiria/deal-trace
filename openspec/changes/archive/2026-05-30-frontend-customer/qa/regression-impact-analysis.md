# Regression Impact Analysis — frontend-customer

## Change Summary

- Requirement / change ID: `frontend-customer`（modifies `frontend-workbench`）
- Change type: code（前端）+ test + config（路由）
- Changed behavior: 新增客户管理页（列表/搜索/创建客户）、客户可搜索下拉选择器、新建线索（含查重预检与历史流失提示）；`customers` 占位路由替换为真实视图。
- Impacted modules / APIs / pages: `src/api/customers.ts`（新）、`src/api/leads.ts`（增量 `createLead`/`duplicateCheck`/类型）、`src/utils/lead.ts`（增量 `isValidContactPhone`/`BUSINESS_TYPES`）、`src/components/CustomerSelect.vue`（新）、`src/views/CustomersView.vue`（新）、`src/router/index.ts`、`src/test/msw/handlers.ts`（增量）。后端零改动。
- Author / owner: vibe-coding-qa

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `src/api/customers.spec.ts` | Add | spec R1/R2 | 覆盖客户搜索/创建契约与错误归一 | searchCustomers query 形态、createCustomer 原样提交、DUPLICATE_CUSTOMER |
| `src/api/leads.spec.ts` | Add（增量，未改既有） | spec R4 / lead spec R6 | 覆盖 createLead/duplicateCheck 契约 | POST /leads body 透传、duplicate-check query+返回形状 |
| `src/utils/lead.spec.ts` | Add（增量，未改既有） | lead spec R3 | 覆盖联系电话校验 | 手机号/座机/分机/非法边界 |
| `src/components/CustomerSelect.spec.ts` | Add | spec R3 | 覆盖远程搜索/选中/无新建捷径 | emit customerId+客户对象、空匹配无捷径 |
| `src/views/CustomersView.spec.ts` | Add | spec R1/R2/R4 | 覆盖列表/搜索/创建/新建线索+预检 | 13 用例分支 |
| `tests/e2e/customer-flow.spec.ts` | Add focused E2E smoke | spec R1–R4 | 客户→线索端到端闭环 | 仅关键旅程；缺凭据 `test.skip` |

未修改或删除任何既有测试。

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `src/api/leads.ts` 增量 | 既有 leads 函数的导入/类型 | `src/api/leads.spec.ts`、`src/stores/leads.spec.ts` | 增量用例（已加） | 仅追加导出，既有函数签名不变；类型由 `vue-tsc` 兜底 |
| `src/utils/lead.ts` 增量 | 既有 `isValidAmount`/`isClosed`/常量 | `src/utils/lead.spec.ts` | 增量用例（已加） | 纯追加，无改动既有函数 |
| `src/test/msw/handlers.ts` 增量 | 所有消费 handlers 的测试 | 全套 `pnpm test:unit` | — | 仅追加工厂；`handlers` 默认集不变；新增 import 客户/线索类型 |
| `src/router/index.ts` | `customers` 路由目标 | `src/router/guards.spec.ts` | — | 仅换 component，守卫与其它子路由不变 |
| `src/components/navigation.ts` | 客户管理入口可见性 | `src/components/AppShell.spec.ts` | — | 未改动（入口已存在，所有角色可见） |

## Risk Level

- Risk: Low
- Rationale: 全部为追加式新增，不触及迁移、金额运算、租户/权限边界；既有导出签名不变，唯一配置改动是单条路由的 component 替换。回归面靠全量单元套件 + 类型构建即可覆盖。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| 全量单元/组件（15 文件） | Unit/组件 | 确认 leads.ts/utils 增量与 handlers 改动未破坏既有消费方 | `pnpm test:unit` | PASS | 103 passed（既有 77 全绿 + 新增 26） |
| vue-tsc + vite build | 类型/构建 | 确认 leads.ts 类型增量未破坏既有 import | `pnpm build` | PASS | `dist/` 产出，无类型错误 |
| openspec strict | 规格 | 确认 delta spec 合法 | `openspec validate frontend-customer --strict` | PASS | "is valid" |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| `tests/e2e/customer-flow.spec.ts` | BLOCKED | 缺真后端 + `E2E_SALES_EMAIL`/`E2E_SALES_PASSWORD` | 起 backend+frontend 并注入 Sales 凭据 | 端到端闭环未自动化；组件层已覆盖各分支 |

## Runtime QA Validation

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| No | 本批次为前端追加，配置改动仅单条路由；类型检查 + build 已作静态可用性证据 | — | N/A | — |

## Regression Conclusion

- Overall result: PASS（单元/组件 + 类型构建 + 规格校验）；E2E BLOCKED（缺真后端凭据）
- Changed behavior covered: 客户列表/搜索、创建客户、可搜索选择器、新建线索+查重预检——组件/单元层全覆盖。
- Directly impacted old behavior covered: 既有 77 用例全绿，leads.ts/utils 增量与路由替换无回归。
- Historical defects considered: 无相关前端历史缺陷。
- Uncovered test points: 预检通过但提交并发冲突（交后端既有测试）；E2E 闭环（缺凭据）。
- Unresolved prerequisite blockers: E2E 真后端凭据。
- Remaining risks: Low——前端电话正则与后端规则潜在漂移由后端 `VALIDATION_ERROR` 兜底；客户搜索上限 50 由后端裁决。
