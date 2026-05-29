# Lightweight Test Design — lead-stage

## Context

- Requirement / Spec: `openspec/changes/lead-stage/specs/lead/spec.md`（1 ADDED Requirement「线索阶段变更」，13 Scenario）
- Change summary: 新增 `PATCH /api/leads/{id}/stage`，在 4 个非结束阶段间任意跳转；ADMIN 改任意线索、SALES 仅改自己名下；`LEAD_STAGE_CHANGE` 日志含原+新阶段；零 schema 改动。
- Target modules / APIs: `LeadController`（+1 端点）、新 `LeadStageService`、新 `UpdateStageRequest` DTO、`LeadMapper`（+`updateStage`）。复用 `LeadStage.isActive()` / `ErrorCode` / `SystemLogPort`。
- Test environment / constraints: Testcontainers + 真 MySQL 8.4（tech-arch §12，禁 H2 / mock）；与 dev backend smoke 不可并发（共用 dealtrace 实例，memory `smoke-vs-mvn-verify-share-db`）；查 `system_log` 按 `lead_id` 过滤（memory `no-truncate-in-rollback-tests`）；表名 `lead` 反引号（memory `mysql-reserved-word-lead`）。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria（PRD §7.7.1-7.7.10 / §7.3.5 / §7.8.6-7.8.7 / §8.4；tech-arch §12 / §13.2）
- [x] Existing behavior baseline: lead-core + lead-ownership 测试（164 Green）；`LeadOwnershipService` 事务骨架与 indistinguishable-404 模式
- [x] Data model / field rules: `lead.stage`（`LeadStage` 6 枚举，前 4 active）；`lead.lastTrackedAt`（progress-log 维护，本 change 不碰）
- [x] API contract / auth rules / error shape: `ApiResponse` 信封；`GlobalExceptionHandler` 已映射 `NOT_FOUND`/`VALIDATION_ERROR`/`LEAD_ENDED_READONLY`
- [x] UI states / user roles: ADMIN 任意 / SALES 自己名下；公海对 SALES → 404
- [x] Code structure / changed code: 见 proposal Impact + design D1-D8
- [x] Existing tests / historical defects: `lead` 保留字反引号；rollback 测试内禁 raw TRUNCATE
- [x] Test data / credentials / mocks / CI constraints: `JwtService.generateToken` + `IntegrationTest`（@Transactional @Rollback）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 阶段变更操作人 | PRD §7.7.6 仅显式授予 Sales（自己名下） | 本 change：ADMIN 亦可改任意线索 | extends（PRD 未表态 Admin，§7.8 把「阶段变更」列为受审计操作未限定操作人） | PRD §7.7 + 用户在 explore 阶段确认（design D3） | Proceed（gap-fill，非 conflict，不触发 Conflict Gate） |
| 进入结束阶段 | 无 | 本 change 明确**不**处理赢单/流失 | 无冲突（留给 lead-closure/contract） | design Non-Goals | Proceed |

无 `conflicts` 关系，无 BLOCKED。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SALES 改自己名下到另一非结束阶段成功 | spec S1 | 正例 | API | owner=self, stage=初步沟通 → 方案报价 | 200 SUCCESS, stage 变更 | `$.data.stage` + DB 行 | P0 | `LeadStageChangeTest` |
| 非结束阶段任意方向跳转（回退） | spec S2 | 等价类 | API | stage=商务谈判 → 未触达 | 200, stage=未触达 | DB 行 | P0 | `LeadStageChangeTest` |
| ADMIN 改他人名下成功 | spec S3 | 正例 | API | owner=别人, ADMIN 调用 | 200, stage 变更 | DB 行 | P0 | `LeadStageChangeTest` |
| ADMIN 改公海线索成功 | spec S4 | 正例 | API | owner=null, ADMIN 调用 | 200, stage 变更 | DB 行 | P0 | `LeadStageChangeTest` |
| SALES 改他人名下 → 404 不可区分 | spec S5 | 反例 | API | owner=别人, SALES 调用 | 404 NOT_FOUND, message 同「不存在」 | HTTP404+code | P0 | `LeadStageChangeTest` |
| SALES 改公海 → 404 | spec S6 | 反例 | API | owner=null, SALES 调用 | 404 NOT_FOUND | HTTP404+code | P0 | `LeadStageChangeTest` |
| 已结束线索 → LEAD_ENDED_READONLY | spec S7 | 反例 | API | stage=已赢单 | 400 LEAD_ENDED_READONLY | HTTP400+code | P0 | `LeadStageChangeTest` |
| 已结束只读优先于目标非法 | spec S8 | 优先级/决策表 | API | stage=已流失, 目标=非法 | 400 LEAD_ENDED_READONLY（非 VALIDATION_ERROR） | HTTP400+code | P0 | `LeadStageChangeTest` |
| 目标为结束阶段 → VALIDATION_ERROR | spec S9 | 反例 | API | 目标=已赢单/已流失 | 400 VALIDATION_ERROR | HTTP400+code | P0 | `LeadStageChangeTest` |
| 目标为非法枚举 → VALIDATION_ERROR | spec S10 | 反例 | API | 目标=foo | 400 VALIDATION_ERROR | HTTP400+code | P0 | `LeadStageChangeTest` |
| 目标==当前阶段(no-op) → VALIDATION_ERROR 且不写日志 | spec S11 | 边界/不变量 | API | 目标=当前 stage | 400 VALIDATION_ERROR, system_log 不增 | HTTP+code + COUNT | P0 | `LeadStageChangeTest` |
| 成功生成 LEAD_STAGE_CHANGE 含原+新阶段 | spec S12 | 副作用 | API | 成功变更 | system_log+1, summary 含原+新 | 日志行 | P0 | `LeadStageChangeTest` |
| 阶段变更不更新 lastTrackedAt | spec S13 | 不变量 | API | lastTrackedAt=T0, 成功变更 | lastTrackedAt 仍 T0 | DB 行 | P0 | `LeadStageChangeTest` |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| 全部 13 Scenario | `LeadStageChangeTest` | `PATCH /{id}/stage` 端点未暴露 | 期望 200/具体 code，实得 404/405 | 实现 `LeadStageService` + controller + `updateStage` mapper | lead-ownership（共享 `lead` 表 / system_log 写） |

> 说明：端点未暴露导致请求落 404/405 属预期行为 Red（非编译/fixture 阻塞）。DTO `UpdateStageRequest` 与 mapper 方法先建立编译骨架，再让断言因行为不符转 Red。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| PATCH 端点认证门 | 复用全局 `anyRequest authenticated`；ADMIN/SALES 均可访问，角色细分在 service 层 | API 测试中以 SALES/ADMIN 双角色断言行为差异 | 低 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 无 | | | RESOLVED |

## Coverage Closure

- [x] 每个 in-scope 测试点映射到 `LeadStageChangeTest`
- [ ] 测试执行并记录结果（apply 阶段 `mvn verify` 后回填）
- [x] Red 因预期行为原因失败（端点未暴露）
- [x] 语法 / import / fixture 失败不计为 Red
- [x] 需求冲突已解决（Admin 权为 gap-fill，见 Conflict Gate）

## Notes

- Uncovered test points: 无（13 Scenario 全覆盖）。
- Remaining risks: 复用 `LeadOwnershipService` 的判定模式但**不**共享其私有方法（自包含 `LeadStageService`），故对归属套件零侵入；仍跑全量 `lead` 套件回归确认。
- Execution evidence: apply 阶段 `mvn -pl backend test` 输出回填到 `qa-test-report.md`。
