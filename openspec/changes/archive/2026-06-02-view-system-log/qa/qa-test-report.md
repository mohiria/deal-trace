# QA Test Report — view-system-log

Purpose: 汇总本次实际执行了什么、证明了哪些行为、数据如何准备、剩余风险。完整 pre-code 设计见 `lightweight-test-design.md`。

## Conclusion

- Overall result: **PASS**
- Requirement / change ID: view-system-log（specs：system-log-view ADDED、system-log ADDED）
- QA owner: Claude（vibe-coding-qa）
- Date: 2026-06-02
- Summary: 系统日志「查看」能力（写侧结构化 detail + 两个读端点 + 前端时间线/全局页）端到端交付。后端 `mvn verify` 268 全绿、前端 vitest 216 全绿、system-log e2e 2 全绿、`openspec validate` 通过。无失败归因于本改动；过程中暴露的 2 个预存在脏数据测试已修隔离。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | detail 形状、port 异常不阻塞 |
| API/integration | Yes | 真 MySQL 8.4，读端点权限/排序/组装/双路径 |
| E2E | Yes | 活体全栈 Playwright（详情时间线 + 全局页 + Sales 守卫） |
| Regression | Yes | 12 写点改造 + SystemLogPort 签名 + 既有套件全量 |
| Runtime QA validation | Yes | spring-boot:run 起栈可用性（e2e 前置） |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 归属在日志中的标识 | 写 email（ownerLabel） | system-log spec ADDED（存 ownerId） | extends | Active Spec + PRD §7.8.8 | modify（AdminAccountStatusControllerTest 改验结构化 detail） | implement |
| 系统日志查看 | 无（system-log 只写） | system-log-view ADDED + PRD §7.8 | extends | PRD | add | implement |
| 「系统日志未交付」前端断言 | AppShell/Dashboard/LeadDetail 旧断言 | 本 change 交付查看 | supersedes | 本 change | modify（翻正断言、保留写入口缺席校验） | implement |

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 归属 detail 存 ownerId 非 email | system-log spec | 初版断言裸字符串 contains 失败（MySQL JSON 规范化） | 预期行为可读但断言形态需改解析比值（非环境失败） | `mvn test -Dtest=SystemLogDetailWriteTest` PASS | 写侧回归集 PASS | `systemlog/SystemLogDetailWriteTest#ownerChange_persists_owner_ids_not_email` | PASS |
| lead 维度读隔离+倒序 | system-log-view R1 | 端点未实现期 404/无路由 | 行为缺失 | `mvn test -Dtest=LeadSystemLogListTest` PASS(6) | progress-log 读对称未回归 | `systemlog/LeadSystemLogListTest` | PASS |
| 全局页 ADMIN-only | system-log-view R2 | 端点未实现 | 行为缺失 | `mvn test -Dtest=AdminSystemLogListTest` PASS(4) | SecurityPathRuleTest PASS | `systemlog/AdminSystemLogListTest#sales_forbidden` | PASS |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 全局页 Arco Table 布局/样式 | 低风险展示 | 组件测试断言列/分页 + e2e 渲染 | 视觉细节 |
| action→中文标签文案 | 文案 | 单点映射 + 组件/e2e 断言标签文本 | 措辞 |

## Tests Run

| Source | Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| Design | Unit/integration | SystemLogDetailWriteTest(4)、JdbcSystemLogPort*(5) | `mvn -o test -Dtest=...` | PASS | 真 MySQL，detail JSON 字段断言 |
| Design | API/integration | LeadSystemLogListTest(6)、AdminSystemLogListTest(4) | `mvn -o test -Dtest=...` | PASS | 权限404/403、倒序、姓名/金额组装、双路径 |
| Regression | Regression | AdminAccountStatusControllerTest 等写点回归 | `mvn -o test -Dtest=...` | PASS | 验证结构化 detail |
| Both | 全量后端 | 整套 | `mvn -o verify` | PASS | **268 tests, 0 failures, 0 errors, BUILD SUCCESS** |
| Design+Regression | 前端组件 | SystemLogsView.spec(5)、LeadDetailView 时间线、AppShell nav 等 | `vitest run` | PASS | **216 passed** |
| Design | 类型 | 全前端 | `vue-tsc -b` | PASS | 0 错误 |
| Design | E2E | system-log-flow(2) | `playwright test system-log-flow.spec.ts` | PASS | **2 passed**（活体全栈） |

## User Scenario Coverage

| Scenario | Persona / role | Workflow covered | E2E artifact | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| 详情系统日志只读时间线（倒序） | Admin | 登录→建客户/线索→详情见「创建线索」→推进阶段→刷新后「阶段变更」在前 | `tests/e2e/system-log-flow.spec.ts#Admin：线索详情系统日志时间线（倒序）+ 全局日志页筛选` | COVERED | 直达 /leads/{id}（公海单不在我的线索） |
| 全局日志页筛选 | Admin | /system-logs 表格可见 + 按「阶段变更」筛选 | 同上 | COVERED | |
| Sales 无入口 + 守卫 | Sales | 无「系统日志」导航；直达 /system-logs 被挡回 | `tests/e2e/system-log-flow.spec.ts#Sales：无「系统日志」导航入口，/system-logs 被守卫挡回` | COVERED | API 403 由 AdminSystemLogListTest 低层覆盖 |

## Test Data Setup Evidence

| Test / scenario | Required data | Business realism evidence | Setup method | Cleanup | Evidence | Status |
| --- | --- | --- | --- | --- | --- | --- |
| 后端读 IT | 客户+线索+多条 system_log（含 account 事件干扰项） | 真实线索生命周期事件 | mapper/JdbcTemplate 插入，唯一 USCI/邮箱 | `@Rollback` 回滚，**不删他人数据** | LeadSystemLogListTest/AdminSystemLogListTest | READY |
| 写侧 detail IT | 各 action 触发 record | 真实事件 payload | JdbcSystemLogPort.record + MultiTransactional 仅 truncate system_log | FK-off truncate（仅自表） | SystemLogDetailWriteTest | READY |
| e2e | 唯一时间戳客户/线索/Sales | 真实管理员/销售旅程 | UI + API（活体共享库，无回滚） | 唯一残留数据，不删他人 | 时间戳化 stamp | READY |

## Tests Not Run / Blockers

| Source | Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- | --- |
| — | 无 | — | 全部已执行 | — | — |

（历史阻塞「MySQL 在线」已解除：用本机环境变量 `DB_HOST=114.132.164.71:3606` 连真库。）

## Coverage Summary

| Source | Test point / regression item | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- |
| Design | 归属/阶段/金额/原因 detail 结构化 | Integration | detail JSON 字段=触发参数 | SystemLogDetailWriteTest | COVERED |
| Design | lead 读权限隔离(404) | Integration | ADMIN任意/SALES自己/他人公海404/不存在404 | LeadSystemLogListTest | COVERED |
| Design | 全局 ADMIN-only(403)+分页+筛选+含account事件 | Integration | 403、分页元信息、唯一action筛选、lead_id=NULL 可见 | AdminSystemLogListTest | COVERED |
| Design | 组装：系统/当前姓名/公海/标签/千分位 | Integration+组件 | operatorName、fromOwnerName、contractAmount | LeadSystemLogListTest + SystemLogsView.spec | COVERED |
| Design | 结构化/freetext 双路径 | Integration+组件 | detail 非空结构化、NULL 回退 summaryFallback | LeadSystemLogListTest + SystemLogsView.spec | COVERED |
| Regression | 12 写点未回归 | Integration | win/lose/stage/claim/release/recall/transfer/create/account | 全量 verify | COVERED |

## Regression Scope

- Changed behavior: 12 写点增结构化 detail；SystemLogPort 6 参；前端 detail 列 + 详情时间线 + 路由/导航恢复。
- Directly impacted old behavior: 账号停用/启用日志参数（4→6 参）；前端「系统日志未交付」假设。
- Regression impact source: Lightweight design。
- Historical defects considered: 共享库 TRUNCATE/delete(null) 泄漏（memory）；MySQL JSON 规范化；保留字 lead；Jackson 3 注入。
- Requirement-driven test additions/modifications: 新增 5 个后端 + 2 个前端 spec；修 AdminAccountStatusControllerTest（强化）、AppShell/Dashboard/LeadDetail（翻正）、JdbcSystemLogPortExceptionTest（签名适配）；隔离加固 DashboardControllerTest/LeadAssignTest seed。
- Regression risk level: Medium → 经全量 verify 落为 Low。
- Selected regression tests and why: 全量 `mvn verify`（跨 capability 写点改造，全跑最稳）。

## Runtime QA Validation

Availability smoke only. It does not count as Unit/API/E2E business coverage.（仅可用性烟测，不计入业务覆盖。）

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| backend :8080 | spring-boot:run + `POST /api/auth/login` | PASS | 返回 ADMIN token | TaskStop + Stop-Process，端口释放 |
| frontend :5173 | vite dev | PASS | e2e 经 :5173 通过 | TaskStop，端口释放 |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| 启动期 NoSuchBeanDefinitionException: ObjectMapper | dependency | 项目用 Jackson 3（tools.jackson），误注 com.fasterxml | 改注 `tools.jackson.databind.ObjectMapper` | 写侧 IT 全绿 |
| detail 断言 contains 失败 | test design | MySQL JSON 规范化（冒号空格、键序） | 改解析 JSON 比值 | SystemLogDetailWriteTest |
| 全量 verify 21 errors（seed） | environment/test design | 共享库残留子行 + 旧测试 delete(null) 不清子表 | 两 seed 先清 progress_log/contract（@Rollback 内，无持久删除） | verify 268 全绿 |
| e2e `.cs-search` 严格冲突 / 公海单不在我的线索 | test design | 隐藏 modal 重复元素；公海归属 | `:visible` + 捕获创建响应直达 /leads/{id} | e2e 2 passed |

## Failure Learning

- Learning recorded: 是。
- Knowledge location: 项目 memory `jackson3-objectmapper-bean.md`（+ MEMORY.md 索引）。
- Summary: backend 是 Jackson 3，注入 ObjectMapper 用 tools.jackson；MySQL JSON 列读回会规范化，断言要解析比值。

## Remaining Risks

- Uncovered test points: 无。
- Uncovered user workflow scenarios: 无（关键旅程 e2e 覆盖）。
- Unresolved prerequisite blockers: 无。
- Requirement authority conflicts: 无。
- Known flaky areas: 全局页/详情若与他人并发写共享库，e2e 用唯一 stamp + 唯一 action 规避；CI 建议独占库。
- Manual follow-up: 无。

## Final Statement

view-system-log 的写侧结构化、读端点（线索维度 + 全局）、前端时间线/全局页全部交付并验证：后端全量 `mvn verify` 268/0/0 BUILD SUCCESS（真 MySQL 8.4）、前端 vitest 216 全绿 + 类型检查通过、system-log e2e 2 全绿（活体全栈）、`openspec validate` 通过。TDD：核心写/读点具备 Red→Green 证据；展示文案/布局走非 TDD 例外 + 组件/e2e 兜底。回归：经全量套件确认无本改动引入的失败；过程暴露的 2 个预存在脏数据测试已做隔离加固（@Rollback 内清子表，零持久删除，未触碰他人数据）。无未跑用例、无未决阻塞。结论 **PASS**，可归档。
