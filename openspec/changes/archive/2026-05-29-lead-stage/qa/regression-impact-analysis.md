# Regression Impact Analysis

## Change Summary

- Requirement / change ID: `lead-stage`
- Change type: requirement + API
- Changed behavior: 新增 `PATCH /api/leads/{id}/stage`，在 4 个非结束阶段间任意跳转；ADMIN 改任意线索、SALES 仅改自己名下（其余 404）；已结束只读；`LEAD_STAGE_CHANGE` 日志含原+新阶段；不触 `lastTrackedAt`。
- Impacted modules / APIs / pages: `LeadController`(+1 端点)、新 `LeadStageService`、新 `UpdateStageRequest`、`LeadMapper`(+`updateStage`)。
- Author / owner: lead-stage change（mohiria）

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `LeadStageChangeTest` | Add | spec ADDED「线索阶段变更」13 Scenario | 新行为无既有覆盖 | API/集成层覆盖全部 13 Scenario（成功/角色/只读/校验/日志/lastTrackedAt） |

无既有测试被修改或删除（本 change 纯增量，不改既有归属/创建行为）。

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `LeadMapper` (+`updateStage`) | 仅新增 `UPDATE lead SET stage` 定向更新，不改 `selectByIdForUpdate`/`updateOwner` | `LeadClaimTest`/`LeadReleaseTest`/`LeadAssignTest`/`LeadRecallTest`/`LeadTransferTest` | `LeadStageChangeTest` | 新方法独立，对既有方法零侵入 |
| 新 `LeadStageService` | 自包含事务方法，**不**依赖 `LeadOwnershipService` 私有原语（design D8：选自包含以零侵入归属服务） | 全量 `lead` 套件 | `LeadStageChangeTest` | 不放宽/提取 `LeadOwnershipService` 任何成员可见性 → 归属服务字节级不变 |
| `LeadController` (+PATCH 端点) | 新增路由，不改既有 6 端点 | lead-core + lead-ownership controller 测试 | `LeadStageChangeTest`（MockMvc） | 端点继承全局认证门，无 `@PreAuthorize`（双角色可达） |

## Risk Level

- Risk: Low
- Rationale: 纯增量端点；零 schema 改动（`lead.stage`/`system_log` 已存在）；明确选择自包含 `LeadStageService` 不触碰 `LeadOwnershipService`，消除"提升私有可见性致归属测试回归"的风险；唯一共享面是 `lead` 表与 `system_log` 写入，已被 rollback 隔离覆盖。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| `LeadStageChangeTest` | API/integration | 覆盖本 change 全部新行为 | `mvn -pl backend test -Dtest=LeadStageChangeTest` | PENDING | apply 阶段回填 |
| `LeadClaimTest`/`LeadReleaseTest`/`LeadAssignTest`/`LeadRecallTest`/`LeadTransferTest` | API/integration | 共享 `lead` 表+`updateStage` 邻接，确认归属行为无回归 | `mvn -pl backend test -Dtest=Lead*Test` | PENDING | apply 阶段回填 |
| 全量后端套件 | all | 共享 `system_log` 写入路径 | `mvn -pl backend test` | PENDING | apply 阶段回填 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 无 | | | | |

## Runtime QA Validation

Runtime QA validation is availability smoke evidence only. It does not count as Unit/API/E2E business coverage.

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| No | 零配置/部署变更，无新依赖/迁移 | — | — | — |

## Regression Conclusion

- Overall result: PENDING（apply 阶段执行后回填）
- Changed behavior covered: 13 Scenario → `LeadStageChangeTest`
- Directly impacted old behavior covered: 5 归属端点套件 + 创建套件
- Historical defects considered: `lead` 保留字反引号（`updateStage` SQL 已加反引号）；rollback 测试内禁 raw TRUNCATE；按 `lead_id` 过滤查 `system_log`
- Uncovered test points: 无
- Unresolved prerequisite blockers: 无
- Remaining risks: 低（见 Risk Level）
