# Lightweight Test Design — view-system-log

Purpose: decide what to test, which layer covers it, what data is needed, what may regress, and what must exist before production code changes.

## Context

- Requirement / Spec: `specs/system-log-view/spec.md`（新读能力）、`specs/system-log/spec.md`（写侧结构化 detail 增补）；PRD §7.8 / §5.5 / 验收 §11.7。
- Change summary: 写侧增 `detail` JSON（12 写点结构化）+ 两个读端点（`GET /leads/{id}/logs`、`GET /admin/system-logs`）+ 前端详情时间线与全局浏览页。
- Target modules / APIs / pages: `systemlog/*`、`LeadController`、新增 `AdminSystemLogController`、`SystemLogReadService`、`SystemLogMapper`；前端 `LeadDetailPanel.vue`、新增 `SystemLogsView.vue`、`api/systemLogs.ts`、`router`、`navigation`。
- Test environment / constraints: 真 MySQL 8.4（`test` profile，禁 H2/mock）；按 `lead_id` 过滤断言、禁 raw TRUNCATE（`@Rollback` 集成测试）；`mvn verify` 勿与 dev smoke 并发；保留字 `lead` 反引号。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria
- [x] Existing behavior baseline: tests / code（`JdbcSystemLogPortTest`、`ProgressLogService`、`LeadProgressListTest`）
- [x] Data model / field rules（`V3__system_log.sql` 三索引、多态 target）
- [x] API contract / auth rules / error shape（`ApiResponse`/`ErrorCode`、`SecurityConfig` `/admin/**`→ADMIN、`JsonAccessDeniedHandler` 403）
- [x] UI states / user roles / user paths（ADMIN 全局页 / SALES 仅详情时间线）
- [x] Code structure / changed code（12 写点、`SystemLogPort` 签名）
- [x] Existing tests / historical defects（memory：TRUNCATE 泄漏、保留字 lead、Arco modal render-to-body）
- [x] Test data / credentials / mocks / CI constraints

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 归属在日志中的标识 | 现状写 email（`ownerLabel`） | spec `system-log` ADDED（存 ownerId） | extends（detail 加结构化，summary freetext 保留） | Active Spec + PRD §7.8.8 | Proceed |
| 系统日志查看 | 无（spec system-log 只写） | spec `system-log-view` ADDED + PRD §7.8 | extends | PRD | Proceed |
| `detail` 可空 | 旧行无 detail | spec `system-log` ADDED（非破坏） | extends | Active Spec | Proceed |

无冲突触发 Conflict Gate。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 归属事件 detail 存 ownerId（非 email） | system-log spec S1 | 等价类 | API/integration | 触发 LEAD_TRANSFER | detail 含 fromOwnerId/toOwnerId | system_log.detail JSON 字段 | P0 | `SystemLogDetailWriteTest` |
| STAGE/WIN/LOSE/ACCOUNT detail 形状 | system-log spec S1 | 等价类 | API/integration | 触发各事件 | detail 字段与触发参数一致；金额精确 | detail JSON | P0 | `SystemLogDetailWriteTest` |
| 旧行 detail=NULL 读取不抛错 | system-log spec S2 | 边界 | API/integration | 插入 detail=NULL 行后读 | 正常返回、回退 summary | 读 DTO summaryFallback | P0 | `SystemLogReadFallbackTest` |
| 写失败不阻塞业务（detail 路径） | system-log spec S2 | 异常注入 | unit | JdbcTemplate 抛异常 | 业务不回滚、SLF4J 留痕 | 无异常上抛 | P1 | `JdbcSystemLogPortExceptionTest`（扩展） |
| `GET /leads/{id}/logs` ADMIN 任意倒序 | view spec R1 | 状态序列 | API/integration | 多事件线索 | 倒序、仅该 lead_id、无 account 事件 | HTTP 200 + 顺序 | P0 | `LeadSystemLogListTest` |
| SALES 自己名下成功 | view spec R1 | 权限 | API/integration | SALES 持有线索 | 200 倒序 | HTTP 200 | P0 | `LeadSystemLogListTest` |
| SALES 他人/公海 → NOT_FOUND 不泄漏 | view spec R1 | 负例 | API/integration | SALES 请求他人线索 | NOT_FOUND、无数据、与不存在不可区分 | code/HTTP 一致 | P0 | `LeadSystemLogListTest` |
| 全局页 ADMIN 分页倒序含 account 事件 | view spec R2 | 状态 | API/integration | 混合事件 | 分页倒序、含 ACCOUNT | HTTP 200 + 分页 | P0 | `AdminSystemLogListTest` |
| 全局页 action/target_type 筛选 | view spec R2 | 等价类 | API/integration | filter=LEAD_WIN | 仅匹配项 | 结果集 | P1 | `AdminSystemLogListTest` |
| 全局页 SALES → 403 无数据 | view spec R2 | 负例 | API/integration | SALES 调 /admin/system-logs | 403 FORBIDDEN | HTTP 403 | P0 | `AdminSystemLogListTest` |
| operator NULL→"系统"、归属 id→当前姓名、公海→"公海" | view spec R3 | 等价类 | API/integration | 各类 detail | 姓名解析正确 | DTO operatorName/owner 展示 | P0 | `SystemLogReadAssembleTest` |
| 金额千分位、action→标签 | view spec R3 | 展示 | API/integration + 前端 | LEAD_WIN | 千分位、中文标签 | DTO actionLabel + 前端渲染 | P1 | `SystemLogReadAssembleTest` + 组件测试 |
| 结构化/freetext 混排倒序 | view spec R4 | 边界 | API/integration | 新旧条目并存 | 统一倒序 | 顺序 | P1 | `SystemLogReadFallbackTest` |
| 详情时间线渲染倒序 | view spec R1/R4 | 组件 | 前端组件 | mock logs | 倒序、姓名/金额展示 | DOM | P1 | `LeadDetailPanel.spec.ts`（扩展） |
| 全局页分页/筛选、ADMIN-only 入口 | view spec R2 | 组件 | 前端组件 | mock + 角色 | 分页/筛选/入口可见性 | DOM + nav | P1 | `SystemLogsView.spec.ts` |

## User Scenario Matrix

| Scenario | Persona / role | Entry point | Data state | Operation path | Outcome type | E2E coverage decision |
| --- | --- | --- | --- | --- | --- | --- |
| 查看自己线索的系统日志时间线 | SALES | 线索详情抽屉/页 | existing（有归属+多事件） | search/view | Success | Lower-layer only（组件 + API/integration 覆盖；非关键独立旅程） |
| 试图看他人线索日志 | SALES | 直接访问他人 leadId | existing（他人名下） | view | denial | Lower-layer only（API/integration 负例覆盖 NOT_FOUND） |
| 全局审计浏览并筛选 | ADMIN | 系统日志菜单 | existing（混合 account+lead 事件） | search/view | Success | Cover with E2E（tasks 6.3 手验关键旅程） |
| 非管理员访问全局日志页 | SALES | 直接访问 /system-logs | existing | view | denial | Lower-layer only（API 403 + 导航入口不可见组件断言） |

## Test Data Plan

| Test point / scenario | Required data state | Business realism basis | Setup method | Isolation strategy | Cleanup method | Data blocker status |
| --- | --- | --- | --- | --- | --- | --- |
| lead 维度读 | 1 客户 + 1 线索 + 多条事件日志 | 真实销售线索生命周期 | API/服务触发写 + 直插 | 按 `lead_id` 过滤断言 | `@Rollback` | Ready |
| 旧行 fallback | detail=NULL 直插行 | 模拟历史数据 | JdbcTemplate 直插 | 按 lead_id 过滤 | `@Rollback` | Ready |
| 全局页含 account 事件 | account 事件 + lead 事件 | Admin 跨实体审计 | 多事务基类触发 | `tablesToTruncate` 仅本测试用 | TRUNCATE（非 @Rollback 基类） | Ready |
| 前端 | mock 日志列表（msw） | 展示真实字段 | msw handlers | 组件级 | 无 | Ready |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| 归属 detail 存 ownerId | `SystemLogDetailWriteTest#transfer_persists_owner_ids` | detail 列/写入尚未实现 | 列不存在 / detail NULL 断言失败 | V8 + port overload + transfer 写点 | LeadTransferTest |
| lead 维度读倒序+隔离 | `LeadSystemLogListTest` | 端点未实现 | 404/无路由 | mapper + service + 端点 | LeadProgressListTest |
| 全局页 ADMIN-only | `AdminSystemLogListTest#sales_forbidden` | 端点未实现 | 404 | controller 挂 /admin/** | SecurityPathRuleTest |

## Regression Impact

| Changed / planned item | Impacted existing behavior | Existing tests to rerun | Historical defects | Regression risk | Separate analysis? |
| --- | --- | --- | --- | --- | --- |
| 12 写点加 detail | 各写动作落库/返回 | LeadClaim/Release/Assign/Recall/Transfer/StageChange/Win/Lose/Create、AdminAccountController* | TRUNCATE 泄漏、保留字 lead | Medium | No |
| `SystemLogPort` 签名扩展 | 所有写点 + JdbcSystemLogPortTest | `JdbcSystemLogPortTest`、`JdbcSystemLogPortExceptionTest`、`CustomerSystemLogQuietTest` | — | Medium | No |
| V8 迁移 | Flyway 迁移链 | `AccountSchemaMigrationTest` 等迁移敏感测试 | V5 lead 保留字失败留 history 行 | Low | No |

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 全局页 Arco Table 纯样式/布局 | 低风险展示 | 组件测试断言关键列/分页存在 + 手验 | 视觉细节偏差 |
| action→中文标签文案 | 文案 | 单测断言映射键值 | 文案措辞 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| MySQL 8.4 `test` 实例需在线 | 全部 API/integration + `mvn verify` | 启动 dealtrace 测试库、勿与 dev smoke 并发 | OPEN（执行阶段确认） |

## Coverage Closure

- Ready for code change: Yes（写侧 TDD 候选有明确 Red）
- Red evidence / reusable failing test / non-TDD exception / blocker exists: Yes
- E2E workflows enumerated: 手验路径见 tasks 6.3（建线索→阶段→赢单→详情时间线 + ADMIN 全局页 + SALES 403）
- Test data plan includes realism + setup path: Yes
- Regression impact recorded with tests to rerun: Yes
- Uncovered points / blockers: MySQL 在线性（执行阶段解除）
