# Lightweight Test Design — lead-closure

## Context

- Requirement / Spec: `openspec/changes/lead-closure/specs/lead/spec.md`（2 ADDED：标记赢单 9 + 标记流失 8 Scenario）+ `specs/contract/spec.md`（2 ADDED：合同由赢单原子生成 + 每线索≤1，共 5 Scenario）
- Change summary: `POST /leads/{id}/win` + `POST /leads/{id}/lose` 两个原子闭单动作；赢单联动生成合同记录（每线索≤1，DB UNIQUE）；终态只读；LEAD_WIN/LEAD_LOSE 日志；新增 V6 contract 表。
- Target modules / APIs: `LeadController`(+2)、新 `LeadClosureService`、新 `com.dealtrace.contract`（`Contract` + `ContractMapper`）、`LoseReason` 枚举、`WinLeadRequest`/`LoseLeadRequest` DTO、`LeadMapper`(+`updateWon`/`updateLost`)。
- Test environment / constraints: Testcontainers + 真 MySQL 8.4（tech-arch §12，禁 H2/mock）；与 dev backend smoke 不可并发（memory `smoke-vs-mvn-verify-share-db`）；查 `system_log` 按 `lead_id` 过滤（memory `no-truncate-in-rollback-tests`）；表名 `lead` 反引号（memory `mysql-reserved-word-lead`）。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria（PRD §7.11.1-2 / §9.6 / §8.4 / §7.8.9-10；tech-arch §9.2 / §6.1.4 / §13.2）
- [x] Existing behavior baseline: lead-core/ownership/stage（177 Green）；`LeadStage.isActive()`（WON/LOST=false）；indistinguishable-404 模式
- [x] Data model / field rules: lead 表 `won_at/lost_at/lose_reason/lose_note` 已预留；contract 表待建（V6）
- [x] API contract / auth rules / error shape: `ApiResponse` 信封；`GlobalExceptionHandler` 已映射 `NOT_FOUND`/`VALIDATION_ERROR`/`LEAD_ENDED_READONLY`
- [x] UI states / user roles: ADMIN 任意 / SALES 自己名下；公海对 SALES → 404
- [x] Code structure / changed code: 见 proposal Impact + design D1-D10
- [x] Existing tests / historical defects: `lead` 反引号；rollback 测试禁 raw TRUNCATE
- [x] Test data / credentials / mocks / CI constraints: `JwtService.generateToken` + `IntegrationTest`（@Transactional @Rollback）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 闭单操作人含 ADMIN | PRD §7.11 未点名操作人 | 本 change + explore 用户确认（沿用 lead-stage） | extends（gap-fill） | PRD §7.11 + 用户确认 | Proceed（非 conflict） |
| Admin 赢公海单 → 成交销售 NULL | 无 | 用户确认 | extends | 用户确认（design D4） | Proceed |
| DUPLICATE_WON_LEAD 触发 | lead-core 查重已实现但无终态可达 | 本 change 设置终态 stage | extends（使既有规则生效，不改 dedup 代码） | lead-core spec | Proceed（不改 dedup） |

无 `conflicts`，无 BLOCKED。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SALES 赢自己单成功 | lead spec 赢单 | 正例 | API | owner=self, stage=商务谈判 | 200, stage=WON, wonAt 非空, 合同+1 | DB 行 | P0 | `LeadWinTest` |
| ADMIN 赢他人/公海单成功 | lead spec 赢单 | 正例 | API | owner=别人/NULL | 200, stage=WON | DB 行 | P0 | `LeadWinTest` |
| 成交销售=赢单时 owner | contract spec | 决策 | API | ADMIN 赢 owner=7 单 | deal_sales_id=7 | 合同行 | P0 | `LeadWinTest` |
| 赢公海单 deal_sales=NULL | contract spec | 边界 | API | ADMIN 赢 owner=NULL | deal_sales_id=NULL | 合同行 | P0 | `LeadWinTest` |
| SALES 赢他人/公海 → 404 | lead spec 赢单 | 反例 | API | owner!=caller | NOT_FOUND, 无合同 | HTTP404+code | P0 | `LeadWinTest` |
| 赢已结束/二次赢单 → 只读 | lead spec 赢单 + §8.4 | 反例/不变量 | API | stage 终态 | LEAD_ENDED_READONLY, 合同数仍 1 | HTTP400+code+COUNT | P0 | `LeadWinTest` |
| 金额≤0/超2位/缺失 → 400 | tech-arch §9.2 | 边界值 | API | 0 / -5 / 100.123 / null | VALIDATION_ERROR, stage 不变, 无合同 | HTTP400+code | P0 | `LeadWinTest` |
| 签订日非法/缺失 → 400 | PRD §7.11.1.5 | 反例 | API | 2026-13-40 / null | VALIDATION_ERROR | HTTP400+code | P0 | `LeadWinTest` |
| LEAD_WIN 日志含金额+签订日 | PRD §7.8.9 | 副作用 | API | 成功赢单 | summary 含金额+日期 | 日志行 | P0 | `LeadWinTest` |
| 赢单事务失败不残留合同 | contract spec | 不变量 | API | 金额非法 | 合同数=0, stage 不变 | COUNT | P0 | `LeadWinTest` |
| SALES 流失自己单成功 | lead spec 流失 | 正例 | API | owner=self | 200, stage=LOST, lostAt, reason | DB 行 | P0 | `LeadLoseTest` |
| 原因=其他缺说明 → 400 | PRD §7.11.2.3 | 反例 | API | 其他 + 空 note | VALIDATION_ERROR | HTTP400+code | P0 | `LeadLoseTest` |
| 原因=其他带说明成功 | lead spec 流失 | 正例 | API | 其他 + note | 200, loseNote 落值 | DB 行 | P0 | `LeadLoseTest` |
| 原因非法枚举 → 400 | lead spec 流失 | 反例 | API | foo | VALIDATION_ERROR | HTTP400+code | P0 | `LeadLoseTest` |
| SALES 流失他人 → 404 | lead spec 流失 | 反例 | API | owner!=caller | NOT_FOUND | HTTP404+code | P0 | `LeadLoseTest` |
| ADMIN 流失公海单成功 | lead spec 流失 | 正例 | API | owner=NULL | 200, stage=LOST | DB 行 | P0 | `LeadLoseTest` |
| 流失已结束 → 只读 | lead spec 流失 + §8.4 | 反例 | API | stage 终态 | LEAD_ENDED_READONLY | HTTP400+code | P0 | `LeadLoseTest` |
| LEAD_LOSE 日志含原因+说明 | PRD §7.8.10 | 副作用 | API | 成功流失（其他+说明） | summary 含原因+说明 | 日志行 | P0 | `LeadLoseTest` |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| 赢单全部 Scenario | `LeadWinTest` | `POST /{id}/win` 未实现 → 500 | 端点缺失（非编译/fixture） | V6 + Contract + service + controller | lead-stage/ownership（共享 lead 表/system_log） |
| 流失全部 Scenario | `LeadLoseTest` | `POST /{id}/lose` 未实现 → 500 | 端点缺失 | LoseReason + service + controller | 同上 |

> 说明：端点未实现致 500 属预期行为 Red。V6 迁移、`Contract`/`ContractMapper`、DTO、枚举先建立编译骨架，再让断言因行为不符转 Red。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 两端点认证门 | 复用全局 `anyRequest authenticated`；ADMIN/SALES 均可达，角色细分在 service | API 测试以双角色断言行为差异 | 低 |
| V6 contract 表 DDL | 迁移 DDL 由 Flyway 在真 MySQL 跑，无独立单测 | 集成测试启动即跑迁移；赢单测试验证 UNIQUE/FK 生效 | 低 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 无 | | | RESOLVED |

## Coverage Closure

- [x] 每个 in-scope 测试点映射到 `LeadWinTest` / `LeadLoseTest`
- [ ] 测试执行并记录结果（apply 阶段 `mvn verify` 后回填）
- [x] Red 因预期行为原因失败（端点未实现）
- [x] 语法 / import / fixture 失败不计为 Red
- [x] 需求冲突已解决（Admin 闭单权为 gap-fill）

## Notes

- Uncovered test points: 无（22 Scenario 全覆盖）。
- Remaining risks: BigDecimal 小数位用 `stripTrailingZeros().scale()<=2` 防 100.000 误判；Admin 赢公海单 deal_sales=NULL 需 dashboard 切片后续处理。
- Execution evidence: apply 阶段 `mvn -pl backend test` 输出回填 `qa-test-report.md`。
