# QA Test Report

## Conclusion

- Overall result: PASS
- Requirement / change ID: `lead-stage`
- QA owner: mohiria
- Date: 2026-05-29
- Summary: `PATCH /api/leads/{id}/stage` 阶段变更（13 Scenario）以严格 TDD 落地：先得 13/13 Red（端点未实现 → 500），实现后 13/13 Green；全量后端套件 177/177 通过（基线 164 + 新增 13），归属/创建套件零回归。

## Evidence Guide

| Evidence type | What to record | Example |
| --- | --- | --- |
| Execution evidence | mvn 命令 + surefire 报告路径 | `mvn -q test -Dtest=LeadStageChangeTest` 13/13 |
| Behavioral evidence | 断言证明的具体行为 | 已结束线索改阶段 → `LEAD_ENDED_READONLY` |
| Coverage evidence | 测试文件#方法 | `LeadStageChangeTest#endedLead_isReadOnly` |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | No | 本 change 行为均在 service/controller 编排层，无独立纯函数新增 |
| API/integration | Yes | `LeadStageChangeTest`（MockMvc + 真 MySQL 8.4 / Testcontainers） |
| E2E | No | 阶段选择 UI 属 frontend-workbench，非本切片 |
| Regression | Yes | 全量 `lead` 套件 + 全量后端套件 |
| Runtime QA validation | No | 零配置/部署/迁移变更，无需可用性 smoke |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 阶段变更操作人含 ADMIN | PRD §7.7.6 仅显式授予 Sales（自己名下） | 本 change spec + explore 阶段用户确认（design D3） | extends（PRD 未表态 Admin，§7.8 把「阶段变更」列为受审计操作未限定操作人） | PRD §7.7 + 用户确认 | Add（ADMIN 改任意线索用例） | Implement（service 角色分支） |
| 进入结束阶段（赢单/流失） | 无 | 本 change 明确不处理 | extends（边界外） | design Non-Goals | Add（目标=结束阶段 → VALIDATION_ERROR 反例） | 不实现进入终态 |

无 `conflicts` 关系，未触发 Requirement Conflict Gate。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 阶段变更全部 13 Scenario | spec ADDED 线索阶段变更 | `mvn -q test -Dtest=LeadStageChangeTest` 13 Failures（status 500） | 端点 `PATCH /{id}/stage` 未实现 → 全部请求 500（行为缺失，非编译/fixture 失败；Testcontainers MySQL 正常起、seed 正常） | 同命令 13/13 PASS（surefire: `com.dealtrace.lead.LeadStageChangeTest.txt`） | `mvn -q test -Dtest=Lead*Test` 15 类全绿；`mvn -q test` 177/177 | `LeadStageChangeTest`（13 方法逐一映射 S1–S13） | PASS |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| PATCH 端点认证门 | 复用全局 `anyRequest authenticated`，无新鉴权代码 | API 测试以 SALES/ADMIN 双角色断言行为差异（S3/S4 vs S5/S6） | 低 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| API/integration | `LeadStageChangeTest` | `mvn -q test -Dtest=LeadStageChangeTest` | PASS | 13/13，`target/surefire-reports/com.dealtrace.lead.LeadStageChangeTest.txt` |
| Regression | 全量 `lead` 套件（15 类，含 claim/release/assign/recall/transfer/concurrency） | `mvn -q test -Dtest=Lead*Test` | PASS | 各 `com.dealtrace.lead.*.txt` 全 0 失败 |
| Regression | 全量后端套件 | `mvn -q test` | PASS | 40 类聚合 Total=177 Failures=0 Errors=0 Skipped=0 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 无 | | | | |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| SALES 改自己名下非结束阶段成功 | API | 200 + stage=方案报价 | `LeadStageChangeTest#sales_changesOwnLead_toAnotherActiveStage_succeeds` | COVERED |
| 非结束阶段任意方向跳转 | API | 商务谈判→未触达 200 | `LeadStageChangeTest#sales_canJumpBackwardsBetweenActiveStages` | COVERED |
| ADMIN 改他人名下 | API | 200 stage 变更 | `LeadStageChangeTest#admin_changesOtherSalesLead_succeeds` | COVERED |
| ADMIN 改公海 | API | owner=null 仍 200 | `LeadStageChangeTest#admin_changesPoolLead_succeeds` | COVERED |
| SALES 改他人名下 → 404 | API | NOT_FOUND，stage 不变 | `LeadStageChangeTest#sales_changesOtherSalesLead_returns404` | COVERED |
| SALES 改公海 → 404 | API | NOT_FOUND | `LeadStageChangeTest#sales_changesPoolLead_returns404` | COVERED |
| 已结束只读 | API | LEAD_ENDED_READONLY | `LeadStageChangeTest#endedLead_isReadOnly` | COVERED |
| 只读优先于目标非法 | API | 已流失+非法目标→LEAD_ENDED_READONLY | `LeadStageChangeTest#endedLead_readOnlyTakesPrecedenceOverInvalidTarget` | COVERED |
| 目标结束阶段 → VALIDATION_ERROR | API | 目标=已赢单→400 | `LeadStageChangeTest#targetEndingStage_returnsValidationError` | COVERED |
| 目标非法枚举 → VALIDATION_ERROR | API | foo→400 | `LeadStageChangeTest#targetInvalidEnum_returnsValidationError` | COVERED |
| no-op → VALIDATION_ERROR 且不写日志 | API | 400 + system_log 行数不变 | `LeadStageChangeTest#targetEqualsCurrent_noOp_returnsValidationError_andWritesNoLog` | COVERED |
| LEAD_STAGE_CHANGE 日志含原+新 | API | summary 含 初步沟通 与 方案报价 | `LeadStageChangeTest#success_writesStageChangeSystemLog_withOldAndNewStage` | COVERED |
| 不触 lastTrackedAt | API | lastTrackedAt 仍 T0 | `LeadStageChangeTest#stageChange_doesNotTouchLastTrackedAt` | COVERED |

## Regression Scope

- Changed behavior: 新增非结束阶段变更端点。
- Directly impacted old behavior: 5 归属端点 + 创建（共享 `lead` 表、`updateStage` 与 `selectByIdForUpdate`/`updateOwner` 邻接、共享 `system_log` 写入）。
- Historical defects considered: `lead` 保留字反引号（`updateStage` SQL 已加反引号）；rollback 测试内禁 raw TRUNCATE（seed 用 `delete(null)`）；按 `lead_id` 过滤查 `system_log`。
- Requirement-driven test additions / modifications / deletions: 仅新增 `LeadStageChangeTest`，无修改/删除既有测试。
- Regression risk level: Low
- Selected regression tests and why: 全量 `lead` 套件（直接邻接）+ 全量后端套件（共享 system_log 写路径）；均全绿。

## Runtime QA Validation

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| 不适用 | — | — | — | — |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| 无（Green 一次通过，Red 为预期） | | | | |

## Failure Learning

- Learning recorded or recommended: No
- Knowledge location: —
- Summary: 无新缺陷；自包含 `LeadStageService`（design D8）使归属服务零侵入，回归面收敛到共享表/日志。

## Remaining Risks

- Uncovered test points: 无（13 Scenario 全覆盖）。
- Unresolved prerequisite blockers: 无。
- Requirement authority conflicts: 无（ADMIN 阶段权为 gap-fill 扩展，已记录）。
- Known flaky areas: 无（本 change 无并发用例）。
- Manual follow-up: 阶段选择 UI（候选项排除当前阶段）留待 frontend-workbench 切片。

## Final Statement

`lead-stage` 以严格 Red→Green TDD 落地：13/13 Scenario 先 Red（端点缺失致 500）后 Green；全量后端 177/177 通过，基线 164 无回归；自包含 `LeadStageService` 不触碰 `LeadOwnershipService`。无阻塞、无未覆盖点、无需求冲突。`openspec validate lead-stage` 通过。
