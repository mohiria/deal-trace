# QA Test Report — lead-ownership

## Conclusion

- Overall result: **PASS**
- Requirement / change ID: `lead-ownership`（lead capability 第二站：公海 + 认领 / 退回 / 分配 / 回收 / 转移）
- QA owner: Claude（executor + verifier 同会话，作者与评审分 pass）
- Date: 2026-05-29
- Summary: 6 个新端点 + 5 类系统日志 + 手机号脱敏全部按 spec ADDED/MODIFIED 落地。全量 `mvn test` **164 通过、0 失败、0 错误**（lead-core 既有用例无回归）。并发认领以真实多事务 + `SELECT ... FOR UPDATE` 验证仅一人成功。

## Evidence Guide

| Evidence type | Record |
| --- | --- |
| Execution | `mvn -o test`（backend）→ `Tests run: 164, Failures: 0, Errors: 0`，BUILD SUCCESS |
| Behavioral | 见下方 Coverage Summary 每行 |
| Coverage | 见下方 Coverage artifact 列 |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | `PhoneMaskerTest`（§9.4 三档脱敏） |
| API/integration | Yes | 6 端点 × 失败矩阵 + 5 类日志（真 MySQL 8.4 Testcontainers 连接） |
| E2E | No | MVP 关键旅程 E2E 由后续整合阶段统筹，不在本 change |
| Regression | Yes | 全量套件，确认 lead-core 130 用例无回归 |
| Runtime QA validation | No | 未做运行时冒烟 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 认领 / 退回 / 分配端点「未暴露」 | lead-core `LeadNotInScopeTest`（claim/release/assign isNotMapped）+ lead spec R9 | lead-ownership spec ADDED 认领/退回/分配 + MODIFIED R9 | supersedes | 本 change spec delta | 删除 3 个 reverse-assertion 方法（claim/release/assign），保留 stage/win/lose | 实现 6 端点 |
| `GET /api/leads` Sales→403 | lead spec R7 | 本 change 不改，新增独立 `/pool` | extends（无冲突，design D4） | design D4 | 保留 R7 测试 | 新增 pool 端点 |

无 `conflicts` 项。删除既有测试的权威依据已记录（spec ADDED + MODIFIED），且原测试 javadoc 显式预告「后续 change 落地时移除对应 reverse assertion」。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 手机号脱敏三档 | tech-arch §9.4 | `PhoneMaskerTest` 先于 `PhoneMasker` 落地，断言失败 | 行为缺失（方法未实现） | `mvn test -Dtest=PhoneMaskerTest` 6/6 PASS | 全量套件 PASS | `lead/service/PhoneMaskerTest` | PASS |
| 两新错误码 HTTP 映射 | design D3 | switch 无分支落 default → 500，与期望 409/400 不符 | 行为缺失（映射未加） | `GlobalExceptionHandlerNewCodeTest` 2/2 PASS | 全量 PASS | `common/GlobalExceptionHandlerNewCodeTest` | PASS |
| 6 端点契约 | spec ADDED | 端点未暴露时各 `Lead*Test` 期望 SUCCESS/具体 code 而得 404 | 行为缺失（端点未实现） | 7 个集成测试类全 PASS | 全量 PASS | 见 Coverage Summary | PASS |

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| `SecurityConfig` 角色门 | 复用既有 `@PreAuthorize` + `anyRequest().authenticated()`，无新配置 | 各 API 测试以错误角色断言 403（如 ADMIN 调 SALES-only 端点） | 低 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit | PhoneMaskerTest | `mvn -o test -Dtest=PhoneMaskerTest` | PASS | 6/6 |
| API | GlobalExceptionHandlerNewCodeTest | `mvn -o test -Dtest=GlobalExceptionHandlerNewCodeTest` | PASS | 2/2 |
| API | Lead{PoolList,Claim,Release,Assign,Recall,Transfer}Test | `mvn -o test -Dtest=...` | PASS | 4+4+5+5+4+6 |
| API(多事务) | LeadClaimConcurrencyTest | `mvn -o test -Dtest=LeadClaimConcurrencyTest` | PASS | 1/1，日志可见线程 2 阻塞至线程持锁提交后得 ALREADY_CLAIMED |
| Regression | 全量 backend | `mvn -o test` | PASS | Tests run: 164, Failures: 0, Errors: 0 |

## Tests Not Run / Blockers

| Test / scope | Reason | Residual risk |
| --- | --- | --- |
| E2E | 不在本 change 范围 | 低（关键旅程后续整合统筹） |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| Sales 公海电话脱敏 | API | 返回 `138****5678`，响应无明文 | `LeadPoolListTest#sales_seesMaskedPhone` | COVERED |
| Admin 公海电话明文 | API | 返回明文 | `LeadPoolListTest#admin_seesPlaintextPhone` | COVERED |
| 公海仅未结束无归属 | API | 4 条种子仅 1 条出现 | `LeadPoolListTest#poolOnlyContainsUnendedUnownedLeads` | COVERED |
| 公海无副作用 | API | 调用 3 次行数不变 | `LeadPoolListTest#poolQueryHasNoSideEffects` | COVERED |
| 认领成功/stage 不变 | API | owner=self，stage 保持 | `LeadClaimTest#claim_poolLead_succeeds...` | COVERED |
| 认领非公海 →409 | API | LEAD_ALREADY_CLAIMED | `LeadClaimTest#claim_alreadyClaimedLead_returns409` | COVERED |
| 认领已结束 →400 | API | LEAD_ENDED_READONLY | `LeadClaimTest#claim_endedLead_returns400` | COVERED |
| LEAD_CLAIM 日志 | API | action/target/operator/lead_id 正确 | `LeadClaimTest#claim_writesLeadClaimSystemLog` | COVERED |
| 并发仅一人成功 | API(多事务) | 1 SUCCESS / 1 ALREADY_CLAIMED，终态 owner=成功者 | `LeadClaimConcurrencyTest#twoSalesClaimingSameLead_onlyOneSucceeds` | COVERED |
| 退回成功/stage 不变 | API | owner=null，stage 保持 | `LeadReleaseTest#release_ownLead_succeeds...` | COVERED |
| 退回缺备注 →400 | API | VALIDATION_ERROR | `LeadReleaseTest#release_missingNote_returns400` | COVERED |
| 退回他人线索 →404 | API | NOT_FOUND（不泄漏） | `LeadReleaseTest#release_otherSalesLead_returns404` | COVERED |
| 退回已结束 →400 | API | LEAD_ENDED_READONLY | `LeadReleaseTest#release_endedLead_returns400` | COVERED |
| LEAD_RELEASE 日志含备注 | API | summary 含 releaseNote | `LeadReleaseTest#release_writesLeadReleaseSystemLogWithNote` | COVERED |
| 分配成功 | API | owner=target | `LeadAssignTest#assign_poolLeadToEnabledSales_succeeds` | COVERED |
| 分配停用 Sales 拒 | API | VALIDATION_ERROR | `LeadAssignTest#assign_toDisabledSales_rejected` | COVERED |
| 分配已有归属拒 | API | VALIDATION_ERROR | `LeadAssignTest#assign_alreadyOwnedLead_rejected` | COVERED |
| 分配已结束 →400 | API | LEAD_ENDED_READONLY | `LeadAssignTest#assign_endedLead_returns400` | COVERED |
| LEAD_ASSIGN 日志含原/新 | API | summary 含公海+目标 email | `LeadAssignTest#assign_writesLeadAssignSystemLogWithOwners` | COVERED |
| 回收成功/stage 不变 | API | owner=null | `LeadRecallTest#recall_ownedLead_succeeds...` | COVERED |
| 回收已在公海拒 | API | VALIDATION_ERROR | `LeadRecallTest#recall_poolLead_rejected` | COVERED |
| 回收已结束 →400 | API | LEAD_ENDED_READONLY | `LeadRecallTest#recall_endedLead_returns400` | COVERED |
| LEAD_RECALL 日志含原/新 | API | summary 含原 email+公海 | `LeadRecallTest#recall_writesLeadRecallSystemLogWithOwners` | COVERED |
| 转移成功 | API | owner=new | `LeadTransferTest#transfer_toAnotherEnabledSales_succeeds` | COVERED |
| 转移停用 Sales 拒 | API | VALIDATION_ERROR | `LeadTransferTest#transfer_toDisabledSales_rejected` | COVERED |
| 转移公海线索拒 | API | VALIDATION_ERROR | `LeadTransferTest#transfer_poolLead_rejected` | COVERED |
| 转移给本人拒 | API | VALIDATION_ERROR | `LeadTransferTest#transfer_toCurrentOwner_rejected` | COVERED |
| 转移已结束 →400 | API | LEAD_ENDED_READONLY | `LeadTransferTest#transfer_endedLead_returns400` | COVERED |
| LEAD_TRANSFER 日志含原/新 | API | summary 含两 email | `LeadTransferTest#transfer_writesLeadTransferSystemLogWithOwners` | COVERED |

## Regression Scope

- Changed behavior: lead 归属流转端点从「未暴露」变为已实现；lead-core `LeadNotInScopeTest` 移除 claim/release/assign 三条 reverse-assertion。
- Directly impacted old behavior: 无（lead-core 创建/详情/列表/查重契约未改；`GET /api/leads` Sales→403 保持）。
- Historical defects considered: ① MySQL 保留字 `lead` 反引号（mapper 原生 SQL 与多事务 TRUNCATE 均已加）；② 多事务 TRUNCATE 偷 bootstrap admin（并发测试 truncate account，与 `AdminBootstrapListenerTest` 既有实践一致，其他类各自 reseed）。
- Requirement-driven test changes: 新增 9 个测试类；删除 `LeadNotInScopeTest` 3 个方法（权威依据见 Conflict Review）。
- Regression risk level: **Low**
- Selected regression tests: 全量 `mvn test`（164）——零失败。

## Failure Analysis

| Failure / issue | Type | Root cause | Action taken | Follow-up |
| --- | --- | --- | --- | --- |
| seed 删除 FK 冲突 | test design | 删除顺序父在子前（customer 先于 lead） | 改为 lead→customer→account 子先删 | 无 |
| 并发测试 `TRUNCATE TABLE lead` 语法错 | test design | `lead` 为保留字未反引号 | tablesToTruncate 与 truncateAll 均反引号 | 无 |
| 全量套件 lead-core 用例 FK 错 | test design | `@Transactional` 测试内 raw `TRUNCATE system_log`（DDL 隐式提交）导致种子 lead 泄漏，lead-core 删 account 未先删 lead → FK 失败 | 去掉 TRUNCATE，改为同事务内按 `lead_id` 过滤断言 system_log（@Rollback 撤销，零泄漏） | 无 |

## Failure Learning

- Learning recorded: Yes（建议沉淀至 memory）
- Summary: `@Transactional`+`@Rollback` 集成测试中**禁用 raw TRUNCATE**（DDL 隐式提交会击穿回滚、污染共享 dealtrace 实例）；需要"清零再断言"时改为按业务键过滤查询。

## Remaining Risks

- Uncovered test points: 无（spec 全部 scenario 已覆盖）。
- Unresolved blockers: 无。
- Requirement authority conflicts: 无。
- Known flaky areas: 并发认领对 MySQL 行锁时序敏感，已用 `CountDownLatch` 同步起跑 + 15s 超时降低偶发；当前稳定通过。
- Manual follow-up: HTTP 409 vs 400（`LEAD_ALREADY_CLAIMED`）的团队最终偏好——spec 仅锁 `code` 字段，如需统一全 400 仅改 `GlobalExceptionHandler`。

## Final Statement

lead-ownership 6 端点、5 类系统日志、手机号脱敏按 spec 全部落地并通过分层测试（单元 + API/集成 + 并发多事务）。全量 backend 套件 164 通过、0 失败，lead-core 无回归。删除的 3 个既有 reverse-assertion 有明确 spec 权威依据且不留覆盖缺口（stage/win/lose 仍受保护）。无未决阻塞；唯一开放项为 409/400 团队偏好，不影响契约。结论 **PASS**。
