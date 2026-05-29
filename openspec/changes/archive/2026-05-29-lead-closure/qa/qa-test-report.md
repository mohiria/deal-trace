# QA Test Report

## Conclusion

- Overall result: PASS
- Requirement / change ID: `lead-closure`
- QA owner: mohiria
- Date: 2026-05-29
- Summary: `POST /leads/{id}/win` + `/lose` 闭环（赢单 9 + 流失 8 + contract 5 Scenario）以严格 TDD 落地：先得 18/18 Red（端点未实现 → 500，contract 表经 V6 已建），实现后 18/18 Green；全量后端套件 195/195（基线 177 + 新增 18），lead 既有套件零回归。

## Evidence Guide

| Evidence type | What to record | Example |
| --- | --- | --- |
| Execution evidence | mvn 命令 + surefire 报告 | `mvn -q test -Dtest=LeadWinTest,LeadLoseTest` 18/18 |
| Behavioral evidence | 断言证明的行为 | Admin 赢公海单 → 合同 deal_sales_id=NULL |
| Coverage evidence | 测试文件#方法 | `LeadWinTest#admin_winsPoolLead_dealSalesIsNull` |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | No | 行为均在 service/controller 编排层 |
| API/integration | Yes | `LeadWinTest`/`LeadLoseTest`（MockMvc + 真 MySQL 8.4 / Testcontainers） |
| E2E | No | 留 frontend-workbench |
| Regression | Yes | 全量 lead 套件 + 全量后端套件 |
| Runtime QA validation | No | 迁移由集成测试启动执行；无独立部署变更 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 闭单操作人含 ADMIN | PRD §7.11 未点名操作人 | 本 change + explore 用户确认（沿用 lead-stage） | extends（gap-fill） | PRD §7.11 + 用户确认 | Add | Implement |
| Admin 赢公海单 → 成交销售 NULL | 无 | 用户确认（design D4） | extends | 用户确认 | Add | Implement |
| DUPLICATE_WON_LEAD 触发 | lead-core 查重已实现但无终态可达 | 本 change 设置终态 | extends（使既有规则生效） | lead-core spec | 不改 dedup | 仅设置 stage |

无 `conflicts`，未触发 Requirement Conflict Gate。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 赢单 9 + contract 4 Scenario | lead spec 赢单 + contract spec | `mvn -q test -Dtest=LeadWinTest` 10 Failures（status 500） | 端点 `POST /{id}/win` 未实现（contract 表经 V6 已建，seed 正常） | 同命令 10/10 PASS | 全量后端 195/195 | `LeadWinTest` | PASS |
| 流失 8 Scenario | lead spec 流失 | `mvn -q test -Dtest=LeadLoseTest` 8 Failures（status 500） | 端点 `POST /{id}/lose` 未实现 | 同命令 8/8 PASS | 全量后端 195/195 | `LeadLoseTest` | PASS |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 两端点认证门 | 复用全局 `anyRequest authenticated` | API 测试双角色断言行为差异 | 低 |
| V6 contract 表 DDL | Flyway 迁移在真 MySQL 跑，无独立单测 | 集成测试启动跑迁移；赢单测试验证 FK/UNIQUE 生效 | 低 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| API/integration | `LeadWinTest` | `mvn -q test -Dtest=LeadWinTest` | PASS | 10/10 |
| API/integration | `LeadLoseTest` | `mvn -q test -Dtest=LeadLoseTest` | PASS | 8/8 |
| Regression | 全量 lead 套件 | `mvn -q test -Dtest=Lead*Test` | PASS | 各 `com.dealtrace.lead.*.txt` 0 失败 |
| Regression | 全量后端套件 | `mvn -q test` | PASS | 42 类聚合 Total=195 Failures=0 Errors=0 Skipped=0 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 无 | | | | |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| SALES 赢自己单成功 + 生成合同 | API | stage=WON, wonAt 非空, 合同+1 | `LeadWinTest#sales_winsOwnLead_succeeds_contractGenerated` | COVERED |
| 成交销售=赢单时 owner | API | Admin 赢 owner=7 单 → deal_sales_id=7 | `LeadWinTest#admin_winsOtherSalesLead_dealSalesIsOwner` | COVERED |
| 赢公海单 deal_sales=NULL | API | deal_sales_id=NULL | `LeadWinTest#admin_winsPoolLead_dealSalesIsNull` | COVERED |
| SALES 赢他人/公海 → 404 无合同 | API | NOT_FOUND, 合同数 0 | `LeadWinTest#sales_winsOtherSalesLead_returns404_noContract` | COVERED |
| 赢已结束 → 只读 | API | LEAD_ENDED_READONLY | `LeadWinTest#winEndedLead_returnsReadonly` | COVERED |
| 二次赢单 → 只读, 合同仍 1 | API | LEAD_ENDED_READONLY, count=1 | `LeadWinTest#winAlreadyWonLead_returnsReadonly_contractStillOne` | COVERED |
| 金额≤0/超2位/缺失 → 400 无合同 | API | VALIDATION_ERROR, 合同 0, stage 不变 | `LeadWinTest#winInvalidAmount_returnsValidationError_noContract` / `#winMissingAmount_returnsValidationError` | COVERED |
| 签订日非法/缺失 → 400 | API | VALIDATION_ERROR | `LeadWinTest#winInvalidOrMissingSignedDate_returnsValidationError` | COVERED |
| LEAD_WIN 日志含金额+签订日 | API | summary 含 88888.88 与 2026-05-20 | `LeadWinTest#win_writesLeadWinSystemLog_withAmountAndSignedDate` | COVERED |
| SALES 流失自己单成功 | API | stage=LOST, lostAt, reason | `LeadLoseTest#sales_losesOwnLead_succeeds` | COVERED |
| 原因=其他缺说明 → 400 | API | VALIDATION_ERROR | `LeadLoseTest#loseOtherWithoutNote_returnsValidationError` | COVERED |
| 原因=其他带说明成功 | API | stage=LOST, loseNote 落值 | `LeadLoseTest#loseOtherWithNote_succeeds` | COVERED |
| 原因非法枚举 → 400 | API | VALIDATION_ERROR | `LeadLoseTest#loseInvalidReason_returnsValidationError` | COVERED |
| SALES 流失他人 → 404 | API | NOT_FOUND | `LeadLoseTest#sales_losesOtherSalesLead_returns404` | COVERED |
| ADMIN 流失公海单成功 | API | stage=LOST | `LeadLoseTest#admin_losesPoolLead_succeeds` | COVERED |
| 流失已结束 → 只读 | API | LEAD_ENDED_READONLY | `LeadLoseTest#loseEndedLead_returnsReadonly` | COVERED |
| LEAD_LOSE 日志含原因+说明 | API | summary 含 其他 与 客户内部重组 | `LeadLoseTest#lose_writesLeadLoseSystemLog_withReasonAndNote` | COVERED |

## Regression Scope

- Changed behavior: 新增赢单/流失两个原子闭单动作 + 合同记录生成。
- Directly impacted old behavior: 全量 lead 套件（claim/release/assign/recall/transfer/stage/create/dup）；共享 lead 表、system_log 写入、V6 迁移链。
- Historical defects considered: `lead` 反引号（V6 FK + updateWon/Lost SQL 已加）；rollback 测试禁 raw TRUNCATE（seed 用 delete + DELETE FROM contract）；按 lead_id 查 system_log。
- Requirement-driven test additions / modifications / deletions: 仅新增 `LeadWinTest`/`LeadLoseTest`，无修改/删除既有测试。
- Regression risk level: Medium（新 schema + 跨表事务）→ 实测零回归。
- Selected regression tests and why: 全量 lead 套件（邻接）+ 全量后端套件（共享 system_log + V6 迁移链）；均全绿。

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
- Summary: 无新缺陷。`signedDate` 选择以 String 接收 + service 端 LocalDate.parse，规避了 GlobalExceptionHandler 无 HttpMessageNotReadableException 映射时非法日期落 500 的风险——保证"签订日非法 → VALIDATION_ERROR"确定可控。

## Remaining Risks

- Uncovered test points: 无（22 Scenario 全覆盖）。
- Unresolved prerequisite blockers: 无。
- Requirement authority conflicts: 无（Admin 闭单权 + 赢公海单 NULL 成交销售均为 gap-fill，已记录）。
- Known flaky areas: 无。
- Manual follow-up: Admin 赢公海单 → 合同 deal_sales_id=NULL，dashboard 切片做归属统计时须显式处理空成交销售。

## Final Statement

`lead-closure` 以严格 Red→Green TDD 落地：18/18 Scenario 先 Red（端点缺失致 500）后 Green；全量后端 195/195 通过，基线 177 无回归；自包含 `LeadClosureService` 不触碰既有服务；新增 V6 contract 表（lead_id UNIQUE + 双 FK）。无阻塞、无未覆盖点、无需求冲突。`openspec validate lead-closure` 通过。lead 生命周期闭环完成。
