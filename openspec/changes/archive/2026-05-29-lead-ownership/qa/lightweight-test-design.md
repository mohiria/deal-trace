# Lightweight Test Design — lead-ownership

## Context

- Requirement / Spec: `openspec/changes/lead-ownership/specs/lead/spec.md`（6 ADDED + 1 MODIFIED）
- Change summary: 公海列表 + 认领 / 退回 / 分配 / 回收 / 转移 6 端点；5 类系统日志；手机号脱敏；零 schema 改动。
- Target modules / APIs: `LeadController`（+6 端点）、`LeadOwnershipService`、`LeadMapper`、`PhoneMasker`、`ErrorCode` / `GlobalExceptionHandler`。
- Test environment / constraints: Testcontainers + 真 MySQL 8.4（tech-arch §12，禁 H2 / mock）；与 dev backend smoke 不可并发（共用 dealtrace 实例，见 memory `smoke-vs-mvn-verify-share-db`）。并发认领测试需真实多事务，用 `MultiTransactionalIntegrationTest` 基类。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria（PRD §7.5/§7.9/§7.10/§8.4/§8.5/§11.5/§11.8；tech-arch §6.3/§8.3/§9.4）
- [x] Existing behavior baseline: lead-core 测试（130 Green）、`LeadService` / `LeadController` / `LeadMapper`
- [x] Data model / field rules: `lead` 表 `owner_sales_id` / `stage`；`LeadStage.isActive()`
- [x] API contract / auth rules / error shape: `ApiResponse` 信封；`GlobalExceptionHandler` switch
- [x] UI states / user roles: ADMIN / SALES 角色门；脱敏分级
- [x] Code structure / changed code: 见 proposal Impact
- [x] Existing tests / historical defects: MySQL 保留字 `lead` 反引号（memory）；bootstrap admin 不可被 TRUNCATE 偷
- [x] Test data / credentials / mocks / CI constraints: `JwtService.generateToken` + `IntegrationTest` rollback

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| lead-core R9「认领端点未暴露」 | lead-core spec R9 scenario | lead-ownership ADDED 认领 requirement | supersedes（认领现已实现） | PRD §7.5 + 本 change spec | Proceed（MODIFY 删该 scenario） |
| `GET /api/leads` Sales→403 | lead-core spec R7 | 本 change 不改，改用新端点 `/pool` | 无冲突（独立路径，design D4） | design D4 | Proceed |

无 `conflicts` 关系，无 BLOCKED。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 11 位手机脱敏 `138****5678` | tech-arch §9.4 | 边界值 | Unit | `13812345678` | `138****5678` | 返回串 | P0 | `lead/service/PhoneMaskerTest` |
| ≥8 位座机脱敏 | tech-arch §9.4 | 边界值 | Unit | `01012345678` | 前3+`****`+后4 | 返回串 | P0 | `PhoneMaskerTest` |
| <8 位仅末 2 位 | tech-arch §9.4 | 边界值 | Unit | `1234567` / `12` | 仅末 2 位可见 | 返回串 | P1 | `PhoneMaskerTest` |
| 新错误码 → HTTP 码 | design D3 | 决策表 | API | 抛 `LEAD_ALREADY_CLAIMED`/`LEAD_ENDED_READONLY` | 409 / 400 + code | HTTP + `$.code` | P0 | `GlobalExceptionHandlerNewCodeTest` |
| Sales 看公海电话脱敏 | spec ADDED-pool | 等价类 | API | 公海线索 phone=`13812345678` | `138****5678`，无明文 | `$.data[*].contactPhone` | P0 | `LeadPoolListTest` |
| Admin 看公海电话明文 | spec ADDED-pool | 等价类 | API | 同上 | 明文 | `$.data[*].contactPhone` | P0 | `LeadPoolListTest` |
| 公海仅含未结束无归属 | spec ADDED-pool | 决策表 | API | 公海/私海/已流失/已赢单各 1 | 仅公海未结束 | 数组内容 | P0 | `LeadPoolListTest` |
| 公海查询无副作用 | spec ADDED-pool | 不变量 | API | 调用 N 次 | lead/system_log 行数不变 | COUNT | P1 | `LeadPoolListTest` |
| 认领成功 owner=self/stage 不变 | spec ADDED-claim | 正例 | API | 公海未结束线索 | owner=caller, stage 不变 | DB 行 | P0 | `LeadClaimTest` |
| 认领非公海 →409 | spec ADDED-claim | 反例 | API | owner!=null | `LEAD_ALREADY_CLAIMED` | HTTP409+code | P0 | `LeadClaimTest` |
| 认领已结束 →400 | spec ADDED-claim | 反例 | API | stage=已流失 | `LEAD_ENDED_READONLY` | HTTP400+code | P0 | `LeadClaimTest` |
| 认领触发 LEAD_CLAIM 日志 | spec ADDED-claim | 副作用 | API | 成功认领 | system_log+1，字段正确 | 日志行 | P0 | `LeadClaimTest` |
| 并发认领仅一人成功 | spec ADDED-claim + design D1 | 并发 | API(多事务) | 2 线程抢同一线索 | 恰 1 SUCCESS，1 ALREADY_CLAIMED | 结果计数 + 终态 owner | P0 | `LeadClaimConcurrencyTest` |
| 退回成功 owner=null/stage 不变 | spec ADDED-release | 正例 | API | owner=self,未结束 | owner=null,stage 不变 | DB 行 | P0 | `LeadReleaseTest` |
| 退回缺备注 →400 | spec ADDED-release | 反例 | API | releaseNote 空 | `VALIDATION_ERROR` | HTTP400+code | P0 | `LeadReleaseTest` |
| 退回非自己名下 →404 | spec ADDED-release | 反例 | API | owner!=caller | `NOT_FOUND` | HTTP404+code | P0 | `LeadReleaseTest` |
| 退回已结束 →400 | spec ADDED-release | 反例 | API | stage=已赢单 | `LEAD_ENDED_READONLY` | HTTP400+code | P0 | `LeadReleaseTest` |
| 退回 LEAD_RELEASE 日志含备注 | spec ADDED-release | 副作用 | API | 成功退回 | summary 含 releaseNote | 日志行 | P0 | `LeadReleaseTest` |
| 分配成功 | spec ADDED-assign | 正例 | API | 公海+ENABLED Sales | owner=target | DB 行 | P0 | `LeadAssignTest` |
| 分配停用 Sales →400 | spec ADDED-assign | 反例 | API | 目标 DISABLED | `VALIDATION_ERROR` | HTTP400+code | P0 | `LeadAssignTest` |
| 分配已有归属线索 →400 | spec ADDED-assign | 反例 | API | owner!=null | `VALIDATION_ERROR` | HTTP400+code | P0 | `LeadAssignTest` |
| 分配已结束 →400 | spec ADDED-assign | 反例 | API | stage 结束 | `LEAD_ENDED_READONLY` | HTTP400+code | P1 | `LeadAssignTest` |
| 分配 LEAD_ASSIGN 日志含原/新归属 | spec ADDED-assign | 副作用 | API | 成功分配 | summary 含公海+目标 | 日志行 | P0 | `LeadAssignTest` |
| 回收成功 owner=null/stage 不变 | spec ADDED-recall | 正例 | API | 私海未结束 | owner=null | DB 行 | P0 | `LeadRecallTest` |
| 回收已在公海 →400 | spec ADDED-recall | 反例 | API | owner=null | `VALIDATION_ERROR` | HTTP400+code | P0 | `LeadRecallTest` |
| 回收已结束 →400 | spec ADDED-recall | 反例 | API | stage 结束 | `LEAD_ENDED_READONLY` | HTTP400+code | P1 | `LeadRecallTest` |
| 回收 LEAD_RECALL 日志 | spec ADDED-recall | 副作用 | API | 成功回收 | summary 含原归属+公海 | 日志行 | P0 | `LeadRecallTest` |
| 转移成功 | spec ADDED-transfer | 正例 | API | 私海+ENABLED 新 Sales | owner=new | DB 行 | P0 | `LeadTransferTest` |
| 转移停用 Sales →400 | spec ADDED-transfer | 反例 | API | 目标 DISABLED | `VALIDATION_ERROR` | HTTP400+code | P0 | `LeadTransferTest` |
| 转移公海线索 →400 | spec ADDED-transfer | 反例 | API | owner=null | `VALIDATION_ERROR` | HTTP400+code | P0 | `LeadTransferTest` |
| 转移给本人 →400 | spec ADDED-transfer | 反例 | API | salesId==owner | `VALIDATION_ERROR` | HTTP400+code | P0 | `LeadTransferTest` |
| 转移已结束 →400 | spec ADDED-transfer | 反例 | API | stage 结束 | `LEAD_ENDED_READONLY` | HTTP400+code | P1 | `LeadTransferTest` |
| 转移 LEAD_TRANSFER 日志含原/新 | spec ADDED-transfer | 副作用 | API | 成功转移 | summary 含两归属 | 日志行 | P0 | `LeadTransferTest` |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| PhoneMasker 三档 | `PhoneMaskerTest` | 类不存在 | 编译失败→实现后断言失败前先有方法 | 实现 §9.4 三分支 | 无 |
| 新错误码映射 | `GlobalExceptionHandlerNewCodeTest` | switch 无分支→落 default 500 | 期望 409/400，实得 500 | switch 追加两分支 | platform-foundation |
| 6 端点行为 | 各 `Lead*Test` | 端点未暴露→404/405 | 期望 SUCCESS/具体 code，实得 404 | 实现 service+controller | lead-core R7/R9 |

> 说明：PhoneMasker 的"类不存在→编译失败"属阻塞而非有效 Red；故先写**方法签名空实现返回 null**，再让断言因行为不符转 Red（符合 qa-constitution 对 Red 的定义）。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| `SecurityConfig` 角色门复核（7.5） | 现有 `@PreAuthorize` + anyRequest authenticated 已覆盖；新增端点继承 | 在各 API 测试中以错误角色断言 403 | 低 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 无 | | | RESOLVED |

## Coverage Closure

- [x] 每个 in-scope 可执行测试点映射到测试路径（见上表 Coverage artifact）
- [ ] 测试执行并记录结果（apply 阶段 `mvn verify` 后回填）
- [x] Red 因预期行为原因失败（端点未暴露 / switch 落 default）
- [x] 语法 / import / fixture 失败不计为 Red
- [x] 需求冲突已解决（见 Conflict Gate）

## Notes

- Uncovered test points: 无（MVP 范围内全覆盖）。
- Remaining risks: 并发测试对 MySQL 行锁时序敏感，用 `CountDownLatch` 同步起跑降低偶发；若 CI 偶发再加重试隔离。
- Execution evidence: apply 阶段 `mvn -pl backend test` / `verify` 输出回填。
