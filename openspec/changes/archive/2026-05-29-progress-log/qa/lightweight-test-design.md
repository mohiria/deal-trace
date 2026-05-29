# Lightweight Test Design — progress-log

## Context

- Requirement / Spec: `openspec/changes/progress-log/specs/progress-log/spec.md`（9 ADDED Requirement / 19 Scenario：字段+时间服务端生成、method/content 校验、仅 SALES 自己名下、ADMIN 写被拒、结束只读、last_tracked_at 同值同源、失败无残留、不可变、不写系统日志、读取可见性与倒序）
- Change summary: `POST /leads/{id}/progress`（仅 SALES 自己名下，新增进度 + 原子更新 last_tracked_at）+ `GET /leads/{id}/progress`（ADMIN 任意 / SALES 自己名下，按 track_time 倒序）；新增 V7 progress_log 表。
- Target modules / APIs: `LeadController`(+2)、新 `ProgressLogService`、新 `com.dealtrace.progresslog`（`ProgressLog` + `ProgressLogMapper`）、`TrackMethod` 枚举、`AddProgressRequest`/`ProgressLogView` DTO、`LeadMapper`(+`updateLastTrackedAt`)。
- Test environment / constraints: Testcontainers + 真 MySQL 8.4（tech-arch §12，禁 H2/mock）；与 dev backend smoke 不可并发（memory `smoke-vs-mvn-verify-share-db`）；查 `system_log` 按 `lead_id` 过滤，回滚测试禁 raw TRUNCATE（memory `no-truncate-in-rollback-tests`）；表名 `lead` 反引号（memory `mysql-reserved-word-lead`）。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria（PRD §5.4 / §7.8 / §9.4 / §8.5.6 / §11.7.3-9 / §7.6.5；tech-arch §8.5 / §9.1 / §13.2）
- [x] Existing behavior baseline: lead-core/ownership/stage/closure（195 Green）；`LeadStage.isActive()`（WON/LOST=false）；indistinguishable-404 模式；lead-stage「阶段变更不碰 last_tracked_at」反向契约
- [x] Data model / field rules: lead 表 `last_tracked_at` V5 已预留（NULL 列）；progress_log 表待建（V7）
- [x] API contract / auth rules / error shape: `ApiResponse` 信封；`GlobalExceptionHandler` 已映射 `NOT_FOUND`/`VALIDATION_ERROR`/`LEAD_ENDED_READONLY`
- [x] UI states / user roles: 写——仅 SALES 自己名下（ADMIN/他人/公海 → 404）；读——ADMIN 任意 / SALES 自己名下（他人/公海 → 404）
- [x] Code structure / changed code: 见 proposal Impact + design D1-D10
- [x] Existing tests / historical defects: `lead` 反引号；rollback 测试禁 raw TRUNCATE；按 lead_id 查 system_log
- [x] Test data / credentials / mocks / CI constraints: `JwtService.generateToken` + `IntegrationTest`（@Transactional @Rollback）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 新增进度仅 SALES，ADMIN 不可 | closure/stage 为 ADMIN 任意 | PRD §3 角色表 + §11.7.3（仅授权 Sales）+ explore 用户确认 | narrows（与其它写动作相反） | PRD §3 + §11.7.3 | Proceed（非 conflict，刻意收窄） |
| method 必填 | PRD §7.8 仅显式列 content 必填 | explore 用户确认 method 必填 | extends（补全未写死项） | 用户确认（design D8） | Proceed |
| 结束线索不对所有 Sales 公开 | 无 PRD 依据的扩张提议 | PRD §7.6.5（Sales 不可见他人私海进度内容） | rejected（砍掉扩张） | PRD §7.6.5 | 不实现，可见性=线索可见性 |
| 新增进度不写系统日志 | §11.7.9 事件清单 | PRD §11.7.9 不含"新增进度" + tech-arch §8.5 | extends（负向断言） | PRD §11.7.9 | Proceed |

无 `conflicts`，无 BLOCKED。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SALES 新增自己名下成功 | spec R1/R3 | 正例 | API | owner=self, 合法 body | 200, 新增 1 条, method/content/tracker_id 正确, track_time 服务端 | DB 行 | P0 | `LeadProgressAddTest` |
| track_time/tracker_id 不受 body 注入 | spec R1 | 反例/安全 | API | body 携带 trackTime/trackerId | 以服务端值为准 | DB 行 | P0 | `LeadProgressAddTest` |
| last_tracked_at = 新进度 track_time（同值） | spec R5 | 不变量 | API | 前值 T0 与 NULL 两起点 | last_tracked_at 严格等于 track_time | DB 行 | P0 | `LeadProgressAddTest` |
| SALES 对他人/公海 → 404 | spec R3 | 反例 | API | owner!=caller / NULL | NOT_FOUND, 无残留, last_tracked_at 不变 | HTTP404+code+COUNT | P0 | `LeadProgressAddTest` |
| ADMIN 新增 → 404 | spec R3 | 反例（不对称） | API | ADMIN 调写端点 | NOT_FOUND, 无残留 | HTTP404+code | P0 | `LeadProgressAddTest` |
| 已结束线索新增 → 只读 | spec R4 | 反例 | API | stage 终态 | LEAD_ENDED_READONLY, 无残留, last_tracked_at 不变 | HTTP400+code+COUNT | P0 | `LeadProgressAddTest` |
| 结束只读先于入参校验 | spec R4 | 优先级 | API | stage 终态 + 非法 body | LEAD_ENDED_READONLY（非 VALIDATION_ERROR） | HTTP400+code | P0 | `LeadProgressAddTest` |
| content 空白 → 400 | spec R2 | 边界 | API | content 空白串 | VALIDATION_ERROR, 无残留, last_tracked_at 不变 | HTTP400+code+COUNT | P0 | `LeadProgressAddTest` |
| method 缺失/非枚举 → 400 | spec R2 | 反例 | API | method 缺失 / "邮件" | VALIDATION_ERROR, 无残留 | HTTP400+code | P0 | `LeadProgressAddTest` |
| 新增后 system_log 不增 | spec R8 | 副作用（负向） | API | 成功新增 | system_log 行数不变 | COUNT | P0 | `LeadProgressAddTest` |
| 不存在 UPDATE/DELETE 端点 | spec R7 | 不变量 | 设计 | — | 无改/删进度业务端点 | 控制器审查 | P1 | design D10 + controller |
| ADMIN 读任意线索倒序 | spec R9 | 正例 | API | owner=别人, 3 条 t1<t2<t3 | 200, 顺序 t3,t2,t1 | 响应数组序 | P0 | `LeadProgressListTest` |
| SALES 读自己名下成功（含已结束） | spec R9 | 正例 | API | owner=self（active 与 WON） | 200, 全部倒序 | 响应数组 | P0 | `LeadProgressListTest` |
| SALES 读他人/公海 → 404 | spec R9 / §7.6.5 | 反例 | API | owner!=caller / NULL | NOT_FOUND | HTTP404+code | P0 | `LeadProgressListTest` |
| 读端点无副作用/不写 system_log | spec R9 | 副作用（负向） | API | 成功读取 | system_log/progress_log COUNT 不变 | COUNT | P1 | `LeadProgressListTest` |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| 新增进度全部 Scenario | `LeadProgressAddTest` | `POST /{id}/progress` 未实现 → 500/404 路由缺失 | 端点缺失（非编译/fixture） | V7 + ProgressLog + TrackMethod + service + controller + updateLastTrackedAt | lead-stage（last_tracked_at 反向契约）/closure（共享 lead 表/system_log） |
| 读取进度全部 Scenario | `LeadProgressListTest` | `GET /{id}/progress` 未实现 | 端点缺失 | 同上 + 读服务 | 同上 |

> 说明：端点未实现致路由缺失/500 属预期行为 Red。V7 迁移、`ProgressLog`/`ProgressLogMapper`、DTO、枚举先建立编译骨架，再让断言因行为不符转 Red。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| 两端点认证门 | 复用全局 `anyRequest authenticated`；角色/归属细分在 service | API 测试以双角色断言行为差异（含读写不对称） | 低 |
| V7 progress_log 表 DDL | 迁移 DDL 由 Flyway 在真 MySQL 跑，无独立单测 | 集成测试启动即跑迁移；新增测试验证 FK 生效 | 低 |
| 进度不可变（无改/删端点） | 通过"不提供端点"实现，无运行时分支可测 | 控制器审查：仅 POST(新增)+GET(读)，无 PUT/PATCH/DELETE | 低 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 无 | | | RESOLVED |

## Coverage Closure

- [x] 每个 in-scope 测试点映射到 `LeadProgressAddTest` / `LeadProgressListTest`
- [ ] 测试执行并记录结果（apply 阶段 `mvn verify` 后回填）
- [x] Red 因预期行为原因失败（端点未实现）
- [x] 语法 / import / fixture 失败不计为 Red
- [x] 需求冲突已解决（仅 SALES 写为刻意收窄；结束公开提议已砍）

## Notes

- Uncovered test points: 无（19 Scenario 全覆盖；R7 不可变以控制器审查 + Non-TDD 例外覆盖）。
- Remaining risks: 读写权限不对称（ADMIN 能读不能写）易写错——测试显式双向覆盖；last_tracked_at 与 track_time 必须单次 now 复用，否则毫秒级偏差致断言失败。
- Execution evidence: apply 阶段 `mvn -pl backend test` 输出回填 `qa-test-report.md`。
