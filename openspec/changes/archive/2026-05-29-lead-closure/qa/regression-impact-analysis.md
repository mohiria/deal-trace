# Regression Impact Analysis

## Change Summary

- Requirement / change ID: `lead-closure`
- Change type: requirement + API + schema（V6 迁移）
- Changed behavior: 新增 `POST /leads/{id}/win` 与 `POST /leads/{id}/lose` 原子闭单动作；赢单联动生成合同记录；终态写入 won_at/lost_at/lose_reason/lose_note；LEAD_WIN/LEAD_LOSE 日志。
- Impacted modules / APIs / pages: `LeadController`(+2)、新 `LeadClosureService`、新 `com.dealtrace.contract`、`LoseReason`、DTO、`LeadMapper`(+`updateWon`/`updateLost`)、V6 contract 表。
- Author / owner: lead-closure（mohiria）

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `LeadWinTest` | Add | lead spec 标记赢单 + contract spec | 新行为无既有覆盖 | API 覆盖赢单 9 + contract 5 Scenario |
| `LeadLoseTest` | Add | lead spec 标记流失 | 新行为无既有覆盖 | API 覆盖流失 8 Scenario |

无既有测试被修改或删除（纯增量）。

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| V6 contract 表迁移 | Flyway 启动迁移链 | 全量后端套件（每个集成测试启动跑迁移） | `LeadWinTest`（验证 FK/UNIQUE） | 真 MySQL 8.4，反引号 `lead` FK |
| `LeadMapper` (+`updateWon`/`updateLost`) | 仅新增定向更新，不改既有方法 | 全量 `lead` 套件 | `LeadWinTest`/`LeadLoseTest` | 对 `selectByIdForUpdate`/`updateOwner`/`updateStage` 零侵入 |
| 新 `LeadClosureService` | 自包含，不依赖 `LeadOwnershipService`/`LeadStageService` 私有成员 | 全量 `lead` 套件 | `LeadWinTest`/`LeadLoseTest` | 仿 lead-stage D8 零侵入策略 |
| `LeadController` (+2 端点) | 新增路由，不改既有 | lead-core/ownership/stage controller 测试 | 同上（MockMvc） | 无 `@PreAuthorize`，双角色可达 |
| lead-core 查重 | WON/LOST 后 dedup 规则生效 | `LeadDuplicateCheckTest`/`LeadControllerCreateTest` | 不新增（既有覆盖足够） | dedup 代码不改，仅闭环设置 stage |

## Risk Level

- Risk: Medium
- Rationale: 涉及新 schema 迁移（V6）、跨 lead+contract 两表的原子事务、金额精确类型——比纯端点切片风险高；但复用既有错误码与 404 模式、自包含 service 不侵入既有服务，回归面收敛到共享 lead 表/system_log/迁移链。需 API/集成 + 全量回归。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| `LeadWinTest` / `LeadLoseTest` | API/integration | 覆盖本 change 全部新行为 | `mvn -pl backend test -Dtest=LeadWinTest,LeadLoseTest` | PENDING | apply 回填 |
| 全量 `lead` 套件 | API/integration | 共享 lead 表 + mapper 邻接 | `mvn -pl backend test -Dtest=Lead*Test` | PENDING | apply 回填 |
| 全量后端套件 | all | 共享 system_log + V6 迁移链 | `mvn -pl backend test` | PENDING | apply 回填 |

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
- Changed behavior covered: 赢单 9 + 流失 8 + contract 5 Scenario
- Directly impacted old behavior covered: 全量 lead 套件 + 查重套件
- Historical defects considered: `lead` 反引号（V6 FK + updateWon/Lost SQL）；rollback 测试禁 raw TRUNCATE；按 lead_id 查 system_log
- Uncovered test points: 无
- Unresolved prerequisite blockers: 无
- Remaining risks: V6 迁移链顺序；BigDecimal 小数位边界；Admin 赢公海单成交销售 NULL 留待 dashboard
