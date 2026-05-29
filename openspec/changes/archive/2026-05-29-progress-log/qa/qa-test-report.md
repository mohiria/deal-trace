# QA Test Report

## Conclusion

- Overall result: PASS
- Requirement / change ID: `progress-log`
- QA owner: mohiria
- Date: 2026-05-29
- Summary: `POST /leads/{id}/progress`（新增进度，仅 SALES 自己名下）+ `GET /leads/{id}/progress`（读取，ADMIN 任意 / SALES 自己名下）以严格 TDD 落地：先得 18/18 Red（端点未实现 → 路由缺失致 500，V7 progress_log 表已建、seed 正常），实现后 18/18 Green；全量后端套件 213/213（基线 195 + 新增 18），lead 既有套件零回归（含 lead-stage「阶段变更不碰 last_tracked_at」反向契约 13/13 仍绿）。

## Evidence Guide

| Evidence type | What to record | Example |
| --- | --- | --- |
| Execution evidence | mvn 命令 + surefire 报告 | `mvn -q test -Dtest=LeadProgressAddTest,LeadProgressListTest` 18/18 |
| Behavioral evidence | 断言证明的行为 | ADMIN 写进度 → 404（读写权限不对称） |
| Coverage evidence | 测试文件#方法 | `LeadProgressAddTest#admin_addsProgress_returns404_noResidue` |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | No | 行为均在 service/controller 编排层 |
| API/integration | Yes | `LeadProgressAddTest`/`LeadProgressListTest`（MockMvc + 真 MySQL 8.4 / Testcontainers） |
| E2E | No | 留 frontend-workbench |
| Regression | Yes | 全量 lead 套件 + 全量后端套件 |
| Runtime QA validation | No | 迁移由集成测试启动执行；无独立部署变更 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 新增进度仅 SALES，ADMIN 不可 | closure/stage 为 ADMIN 任意 | PRD §3 角色表 + §11.7.3 + explore 用户确认 | narrows（与其它写动作相反） | PRD §3 + §11.7.3 | Add | Implement（写 404 拒 ADMIN） |
| method 必填 | PRD §7.8 仅显式列 content 必填 | explore 用户确认 | extends（补全未写死项） | 用户确认（design D8） | Add | Implement（枚举校验） |
| 结束线索不对所有 Sales 公开 | 无 PRD 依据的扩张提议 | PRD §7.6.5 | rejected（砍掉扩张） | PRD §7.6.5 | 不加测试 | 可见性=线索可见性 |
| 新增进度不写系统日志 | §11.7.9 事件清单 | PRD §11.7.9 + tech-arch §8.5 | extends（负向断言） | PRD §11.7.9 | Add（断言不增） | 不调 SystemLogPort |

无 `conflicts`，未触发 Requirement Conflict Gate。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 新增进度 12 Scenario | progress-log spec R1-R8 | `mvn -q test -Dtest=LeadProgressAddTest,LeadProgressListTest` 18 Failures（status 500） | 端点 `POST /{id}/progress` 未实现（V7 表已建，seed 正常） | 同命令 18/18 PASS | 全量后端 213/213 | `LeadProgressAddTest` | PASS |
| 读取进度 6 Scenario | progress-log spec R9 | 同上（含 `GET /{id}/progress` 未实现） | 端点未实现 | 同命令 18/18 PASS | 全量后端 213/213 | `LeadProgressListTest` | PASS |

> 备注：首次 Green 跑出 4 个 Error，根因为测试侧 `((Timestamp) row.get("track_time"))` 强转——MySQL 驱动对 DATETIME(3) 返回 `LocalDateTime` 而非 `java.sql.Timestamp`。属测试代码瑕疵（非产品 bug，行为断言 0 Failures），改用 `jdbcTemplate.queryForObject(..., LocalDateTime.class)` 后 18/18 全绿。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 两端点认证门 | 复用全局 `anyRequest authenticated`；角色/归属细分在 service | API 测试以双角色断言行为差异（含读写不对称） | 低 |
| V7 progress_log 表 DDL | Flyway 迁移在真 MySQL 跑，无独立单测 | 集成测试启动跑迁移；新增测试验证 FK 生效 | 低 |
| 进度不可变（无改/删端点） | 通过"不提供端点"实现，无运行时分支可测 | 控制器审查：lead 仅 POST(新增)+GET(读) progress，无 PUT/PATCH/DELETE | 低 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| API/integration | `LeadProgressAddTest` | `mvn test -Dtest=LeadProgressAddTest` | PASS | 12/12 |
| API/integration | `LeadProgressListTest` | `mvn test -Dtest=LeadProgressListTest` | PASS | 6/6 |
| Regression | lead-stage 反向契约 | `mvn test`（含 `LeadStageChangeTest`） | PASS | 13/13（last_tracked_at 不被阶段变更触碰） |
| Regression | 全量后端套件 | `mvn test` | PASS | 39 类聚合 Total=213 Failures=0 Errors=0 Skipped=0 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 无 | | | | |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| SALES 新增自己名下成功，字段正确、track_time 服务端 | API | 新增 1 条, method/content/tracker_id 正确, track_time 服务端 | `LeadProgressAddTest#sales_addsOwnLead_succeeds_fieldsCorrect` | COVERED |
| track_time/tracker_id 不受 body 注入 | API | 以服务端值为准 | `LeadProgressAddTest#addProgress_ignoresClientSuppliedTrackTimeAndTracker` | COVERED |
| last_tracked_at = 新进度 track_time（T0 与 NULL 起点） | API | last_tracked_at 严格等于 track_time | `LeadProgressAddTest#addProgress_syncsLastTrackedAt_equalToTrackTime_fromT0` / `#...fromNull` | COVERED |
| SALES 对他人/公海 → 404 无残留 | API | NOT_FOUND, count=0, last_tracked_at 不变 | `LeadProgressAddTest#sales_addsOtherSalesLead_returns404_noResidue` / `#sales_addsPoolLead_returns404_noResidue` | COVERED |
| ADMIN 新增 → 404（读写不对称） | API | NOT_FOUND, 无残留 | `LeadProgressAddTest#admin_addsProgress_returns404_noResidue` | COVERED |
| 已结束线索新增 → 只读，无残留 | API | LEAD_ENDED_READONLY, count=0, last_tracked_at 不变 | `LeadProgressAddTest#addProgress_toEndedLead_returnsReadonly_noResidue` | COVERED |
| 结束只读先于入参校验 | API | 非法 body 仍 LEAD_ENDED_READONLY | `LeadProgressAddTest#addProgress_endedLead_readonlyPrecedesValidation` | COVERED |
| content 空白 → 400 无残留 | API | VALIDATION_ERROR, count=0, last_tracked_at 不变 | `LeadProgressAddTest#addProgress_blankContent_returnsValidationError_noResidue` | COVERED |
| method 缺失/非枚举 → 400 | API | VALIDATION_ERROR, 无残留 | `LeadProgressAddTest#addProgress_missingOrInvalidMethod_returnsValidationError` | COVERED |
| 新增后 system_log 不增 | API | COUNT 不变 | `LeadProgressAddTest#addProgress_doesNotWriteSystemLog` | COVERED |
| ADMIN 读任意线索倒序 | API | 顺序 t3,t2,t1 | `LeadProgressListTest#admin_readsAnyLead_descByTrackTime` | COVERED |
| SALES 读自己名下成功（active 与 ended） | API | 200, 全部返回 | `LeadProgressListTest#sales_readsOwnActiveLead_succeeds` / `#sales_readsOwnEndedLead_succeeds` | COVERED |
| SALES 读他人/公海 → 404 | API | NOT_FOUND | `LeadProgressListTest#sales_readsOtherSalesLead_returns404` / `#sales_readsPoolLead_returns404` | COVERED |
| 读端点无副作用/不写 system_log | API | progress_log/system_log COUNT 不变 | `LeadProgressListTest#read_hasNoSideEffects` | COVERED |

## Regression Scope

- Changed behavior: 新增进度跟踪写动作 + 读取动作 + 原子同步 lead.last_tracked_at。
- Directly impacted old behavior: 全量 lead 套件（create/dup/claim/release/assign/recall/transfer/stage/win/lose/pool/detail）；共享 lead 表、system_log 写入、V7 迁移链；lead-stage last_tracked_at 反向契约。
- Historical defects considered: `lead` 反引号（V7 FK + updateLastTrackedAt SQL 已加）；rollback 测试禁 raw TRUNCATE（seed 用 mapper.delete + DELETE FROM progress_log）；按 lead_id 查 system_log。
- Requirement-driven test additions / modifications / deletions: 仅新增 `LeadProgressAddTest`/`LeadProgressListTest`，无修改/删除既有测试。
- Regression risk level: Medium（新 schema + 首次启用 last_tracked_at 写路径 + 读写不对称）→ 实测零回归。
- Selected regression tests and why: 全量 lead 套件（邻接 + last_tracked_at 反向契约）+ 全量后端套件（共享 system_log + V7 迁移链）；均全绿。

## Runtime QA Validation

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| 不适用 | — | — | — | — |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| 首次 Green 4 Error：track_time ClassCast | 测试代码缺陷（非产品） | MySQL 驱动 DATETIME(3) 返回 LocalDateTime，测试错误强转 java.sql.Timestamp | 改用 `queryForObject(..., LocalDateTime.class)` 辅助方法 `trackTimeOf` | 4 项随之转 Green，行为断言始终 0 Failures |

## Failure Learning

- Learning recorded or recommended: No
- Knowledge location: —
- Summary: 单次 now 复用是 last_tracked_at 同值同源断言通过的关键——service 内 `LocalDateTime now = LocalDateTime.now()` 取一次同时喂给 progress.track_time 与 lead.last_tracked_at，测试以 `assertThat(lastTrackedAt(id)).isEqualTo(trackTimeOf(id))` 严格相等锁定。

## Remaining Risks

- Uncovered test points: 无（19 Scenario 全覆盖；R7 不可变以控制器审查 + Non-TDD 例外覆盖）。
- Unresolved prerequisite blockers: 无。
- Requirement authority conflicts: 无（仅 SALES 写为刻意收窄，依 PRD §3 + §11.7.3；结束公开提议已砍，依 §7.6.5；均已记录）。
- Known flaky areas: 无。
- Manual follow-up: 新增进度 add 响应的 trackerName 当前返回 null（仅写动作回显，trackerId 已带），列表端点解析名称；如前端需 add 即回显名称，后续可在 service 注入 principal 名称。

## Final Statement

`progress-log` 以严格 Red→Green TDD 落地：18/18 Scenario 先 Red（端点缺失致 500）后 Green；全量后端 213/213 通过，基线 195 无回归（lead-stage last_tracked_at 反向契约 13/13 仍绿）；自包含 `ProgressLogService` 不触碰既有服务；新增 V7 progress_log 表（双 FK + (lead_id, track_time) 索引）。读写权限不对称（ADMIN 能读不能写）双向覆盖；新增进度不写系统日志以负向断言锁定。无阻塞、无未覆盖点、无需求冲突。`openspec validate progress-log` 通过。这是 tech-arch §13.2 MVP 变更包最后一个 capability，至此 lead 进度时间线闭环。
