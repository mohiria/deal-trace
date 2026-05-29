## 1. QA 测试设计（TDD 前置）

- [x] 1.1 用 `.claude/skills/vibe-coding-qa/templates/` 在 `openspec/changes/lead-stage/qa/lightweight-test-design.md` 写阶段变更的轻量测试设计，逐条映射 spec 的 13 个 Scenario 到 API/集成用例
- [x] 1.2 在 `qa/regression-impact-analysis.md` 记录复用 `LeadOwnershipService` 私有原语（`lockOrThrow`/`ensureActive`/`record`）可能牵动的既有归属测试，列出回归范围（claim/release/assign/recall/transfer 套件）

## 2. DTO 与契约

- [x] 2.1 新增 `UpdateStageRequest` DTO（字段 `stage`，接收中文阶段名；不加 `@NotBlank`，空/非法值由服务层判 `VALIDATION_ERROR`，与 `CreateLeadRequest`/`ReleaseLeadRequest` 模式一致）

## 3. 服务层（Red → Green）

- [x] 3.1 写失败测试：SALES 改自己名下非结束阶段成功且 `stage` 确实变更（Scenario 1）
- [x] 3.2 写失败测试：非结束阶段任意方向跳转（回退，Scenario 2）
- [x] 3.3 写失败测试：ADMIN 改他人名下 / 公海线索成功（Scenario 3、4）
- [x] 3.4 写失败测试：SALES 改他人名下 / 公海 → 404 且 message 不可区分（Scenario 5、6）
- [x] 3.5 写失败测试：已结束线索 → `LEAD_ENDED_READONLY`，且只读优先于目标非法（Scenario 7、8）
- [x] 3.6 写失败测试：目标为结束阶段 / 非法枚举 / no-op → `VALIDATION_ERROR`（Scenario 9、10、11），其中 no-op 不写 `system_log`
- [x] 3.7 写失败测试：成功路径生成 `LEAD_STAGE_CHANGE` 日志，`summary` 含原+新阶段（Scenario 12）
- [x] 3.8 写失败测试：阶段变更不更新 `lastTrackedAt`（Scenario 13）
- [x] 3.9 实现阶段变更服务方法：单一 `@Transactional` 内 `lockOrThrow` → SALES 归属校验（404）→ `ensureActive`（只读）→ `LeadStage.fromDbValue` 解析目标（非法/结束阶段/等于当前 → `VALIDATION_ERROR`）→ 更新 stage → `record("LEAD_STAGE_CHANGE", ...)`，使 3.1–3.8 全绿
- [x] 3.10 按 design D8 决定服务归属（新建 `LeadStageService` 或并入 `LeadOwnershipService`）；若复用私有原语，提取/放宽可见性为共享 helper，保持行为不变

## 4. 控制器层

- [x] 4.1 在 `LeadController` 新增 `@PatchMapping("/{id}/stage")`，绑定 `UpdateStageRequest` 与当前 `AccountPrincipal`，委派服务层
- [x] 4.2 写/补 API 层测试（MockMvc + 真 MySQL 8.4，禁 H2/mock）覆盖 HTTP 状态码与 `code` 字段，确认与 spec Scenario 的 HTTP 断言一致

## 5. 回归与验证

- [x] 5.1 跑全量 `lead` 测试套件（含 claim/release/assign/recall/transfer），确认私有原语可见性调整无回归
- [x] 5.2 `openspec validate lead-stage` 通过；按 `qa/qa-test-report.md` 模板记录最终 Green 证据与剩余风险
