# Regression Impact Analysis

## Change Summary

- Requirement / change ID: `progress-log`
- Change type: requirement + API + schema（V7 迁移）
- Changed behavior: 新增 `POST /leads/{id}/progress`（仅 SALES 自己名下，新增进度 + 原子更新 last_tracked_at）与 `GET /leads/{id}/progress`（ADMIN 任意 / SALES 自己名下，按 track_time 倒序）。
- Impacted modules / APIs / pages: `LeadController`(+2)、新 `ProgressLogService`、新 `com.dealtrace.progresslog`（`ProgressLog` + `ProgressLogMapper`）、`TrackMethod`、`AddProgressRequest`/`ProgressLogView` DTO、`LeadMapper`(+`updateLastTrackedAt`)、V7 progress_log 表。
- Author / owner: progress-log（mohiria）

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `LeadProgressAddTest` | Add | progress-log spec R1-R8 | 新行为无既有覆盖 | API 覆盖新增进度全部 Scenario |
| `LeadProgressListTest` | Add | progress-log spec R9 | 新行为无既有覆盖 | API 覆盖读取可见性 + 倒序 |

无既有测试被修改或删除（纯增量）。

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| V7 progress_log 表迁移 | Flyway 启动迁移链 | 全量后端套件（每个集成测试启动跑迁移） | `LeadProgressAddTest`（验证 FK） | 真 MySQL 8.4，反引号 `lead` FK |
| `LeadMapper` (+`updateLastTrackedAt`) | 仅新增定向更新，不改既有方法 | 全量 `lead` 套件 | `LeadProgressAddTest` | 对 `selectByIdForUpdate`/`updateOwner`/`updateStage`/`updateWon`/`updateLost` 零侵入 |
| `last_tracked_at` 写路径首次启用 | 仅新增进度可写该列 | `LeadStageChangeTest`（阶段变更不碰 last_tracked_at） | `LeadProgressAddTest` | 必须确认 lead-stage 反向契约仍绿 |
| 新 `ProgressLogService` | 自包含，不依赖既有 lead 服务私有成员 | 全量 `lead` 套件 | `LeadProgressAddTest`/`LeadProgressListTest` | 仿 closure D10 零侵入策略 |
| `LeadController` (+2 端点) | 新增路由，不改既有 | lead-core/ownership/stage/closure controller 测试 | 同上（MockMvc） | 无 `@PreAuthorize`，权限在 service（读写不对称） |
| 共享 `system_log` | 进度路径不写 system_log | `*SystemLogQuiet`/closure 日志测试 | `LeadProgressAddTest`（断言不增） | 负向断言锁定 §11.7.9 |

## Risk Level

- Risk: Medium
- Rationale: 涉及新 schema 迁移（V7）与首次启用 lead.last_tracked_at 写路径（与 lead-stage 反向契约相邻），且写/读权限不对称——比纯端点切片风险略高；但复用既有错误码与 404 模式、自包含 service 不侵入既有服务，回归面收敛到共享 lead 表/system_log/迁移链。需 API/集成 + 全量回归。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| `LeadProgressAddTest` / `LeadProgressListTest` | API/integration | 覆盖本 change 全部新行为 | `mvn -pl backend test -Dtest=LeadProgress*Test` | PENDING | apply 回填 |
| `LeadStageChangeTest` | API/integration | last_tracked_at 反向契约相邻 | `mvn -pl backend test -Dtest=LeadStageChangeTest` | PENDING | apply 回填 |
| 全量 `lead` 套件 | API/integration | 共享 lead 表 + mapper 邻接 | `mvn -pl backend test -Dtest=Lead*Test` | PENDING | apply 回填 |
| 全量后端套件 | all | 共享 system_log + V7 迁移链 | `mvn -pl backend test` | PENDING | apply 回填 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 无 | | | | |

## Runtime QA Validation

Runtime QA validation is availability smoke evidence only. It does not count as Unit/API/E2E business coverage.

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| No | 迁移由集成测试启动时执行并验证；无独立部署变更 | — | — | — |

## Regression Conclusion

- Overall result: PENDING（apply 阶段执行后回填）
- Changed behavior covered: 新增进度 + 读取共 19 Scenario
- Directly impacted old behavior covered: 全量 lead 套件 + lead-stage last_tracked_at 反向契约
- Historical defects considered: `lead` 反引号（V7 FK + updateLastTrackedAt SQL）；rollback 测试禁 raw TRUNCATE；按 lead_id 查 system_log
- Uncovered test points: 无
- Unresolved prerequisite blockers: 无
- Remaining risks: V7 迁移链顺序；last_tracked_at 与 track_time 单次 now 复用；读写权限不对称
