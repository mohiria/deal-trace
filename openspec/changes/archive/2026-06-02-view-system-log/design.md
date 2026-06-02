## Context

写侧已落 12 种事件到 `system_log`（表含 `action / target_type / target_id / operator_id / lead_id / summary / created_at` + 三个含 `created_at` 的读索引）。读侧空白：无查询、无 Controller、详情页无时间线、`/system-logs` 占位。现有 `summary` 为 `|` 分隔 freetext，归属冻结存**邮箱**（`ownerLabel()` 返回 `account.getEmail()`，assign/transfer 直接存 `target.getEmail()`）。PRD §7.8 要求详情展示系统日志（验收 §11.7）。约束见 CLAUDE.md（金额精确数值、保留字 `lead` 反引号、真 MySQL 集成测试）与项目 memory（禁 raw TRUNCATE、smoke 与 verify 不并发）。tech-arch §232 将字段类型/索引委派给 change 的 design，未禁 JSON 列。

## Goals / Non-Goals

**Goals:**
- 交付 PRD §7.8 强制的线索详情系统日志时间线。
- 提供 ADMIN 全局日志浏览（分页、倒序、跨 ACCOUNT+LEAD、轻筛选）。
- 写侧增补结构化 `detail`，使读层能以稳定引用组装展示（姓名/标签/千分位），不依赖 freetext 格式。
- 归属、操作人按**当前姓名**展示，与列表页/工作台口径一致。
- 非破坏：旧 `detail=NULL` 行经 fallback 正常渲染，联调/测试库免重灌。

**Non-Goals:**
- 复杂筛选（时间范围、全文检索、导出）——MVP 不做。
- 事件时刻姓名冻结快照——明确不做，按当前解析。
- 任何修改/删除日志的入口——既有"不可变"要求不变。
- 移除 freetext `summary`——保留为人读 fallback，不做硬切换。

## Decisions

### D1：`detail` 用 JSON 列，存稳定引用而非展示串
新增 `V8` 迁移：`ALTER TABLE system_log ADD COLUMN detail JSON NULL`。`detail` 存引用——归属事件存 `fromOwnerId`/`toOwnerId`（公海为 null）、阶段存原/新枚举码、赢单存金额（decimal 字符串，禁 float）+ `signedDate`、流失存原因码+说明、account 事件存必要标识。
- **为何不用类型列**：from_stage/to_stage/from_owner/to_owner/amount/... 会列爆炸且大多 NULL；这些字段无查询/索引需求（筛选只按 `action`/`target_type`，已有索引）。JSON 单列承载异构 payload 最省。
- **为何存 id 不存姓名/邮箱**：读层按当前解析姓名（D4），存展示串就丧失重解析能力且与现状（存 email）一样会过时。

### D2：写契约演进——`SystemLogPort` 增结构化重载
`SystemLogPort` 增一个承载 `detail`（如 `Map<String,Object>` 或类型化 record）的 `record(...)` 重载；既有 4/5 参方法保留（`detail=null` 委派），account 等无修改即兼容。`JdbcSystemLogPort` 序列化 `detail` 为 JSON 入库；`Slf4jSystemLogPort` fallback 仍仅写应用日志。12 个写点改为传结构化 detail，其中 5 个归属写点删除 `email`/`ownerLabel` 入参、改传 ownerId。`summary` 继续写入作为 fallback。

### D3：端点与安全
- **线索维度**：`GET /leads/{id}/logs`，挂已认证 `/leads/**`，授权在 `SystemLogReadService` 内做（镜像 `ProgressLogService.list`：`selectById` → SALES 非自己名下/不存在统一 `NOT_FOUND`）。按 `lead_id` 过滤 + `created_at` 倒序（命中 `idx_system_log_lead_created_at`）。
- **全局**：`GET /admin/system-logs`，挂 `/admin/**`——`SecurityConfig` 已 `hasRole("ADMIN")`，SALES 自动得 `403 FORBIDDEN`（`JsonAccessDeniedHandler`），无需新增 matcher 或方法级注解。分页 + 倒序 + `action`/`target_type` 可选筛选。
- **新增** `SystemLogMapper`：`selectByLeadIdOrderByCreatedAtDesc`、`selectGlobalPaged(filters, offset, limit)`、`countGlobal(filters)`。

### D4：展示组装按当前姓名解析
读层批量解析涉及的账号 id → 当前姓名（复用 `ProgressLogService.loadTrackers` 的批量 `selectBatchIds` 套路）：`operator_id` NULL→"系统"；`fromOwnerId`/`toOwnerId` 空→"公海"，否则当前姓名。`action`→中文标签用集中映射（见 D5）。金额千分位复用前端 `formatAmount`（展示在前端做，后端回传精确数值字符串）。MVP 无硬删除（停用是软状态），id 必可解析。

### D5：action 标签登记表放后端
后端集中维护 `action`→中文标签映射（枚举或常量表），日志读 DTO 直接回传 `actionLabel`，避免 12 个 magic string 散落前端。新增 action 时单点维护。

### D6：读 DTO 与双路径渲染
日志读 DTO 字段：`action`、`actionLabel`、`operatorName`、`createdAt`、结构化展示字段（按 action 类型）、以及 `summaryFallback`。读层：`detail` 非空→解析结构化字段；`detail=NULL`→置 `summaryFallback` 走 freetext 渲染。前端 `LeadDetailPanel` 新增系统日志 section 复用 `.progress-list`/`.event` 样式；全局页用 Arco Table（tech-arch §列表用 Table）。

## Risks / Trade-offs

- **回改 12 个写点引入回归** → 写侧改动配单测覆盖每个 action 的 detail 形状；既有写行为（落库、不阻塞）的现有测试作为回归网。
- **JSON 列查询能力弱** → 本期筛选只按已索引的 `action`/`target_type`，不查 `detail` 内部，规避。
- **新旧行混排展示不一致**（旧行只有 freetext，新行结构化）→ 可接受；联调库可选重灌获得全结构化；spec 明确双路径合法。
- **归属由 email 改 id 改变 summary 文案** → freetext `summary` 仅作 fallback，新行展示走结构化（姓名），文案差异不影响读出语义。
- **全局页无分页上限滥用** → 服务端强制分页 size 上限。
- **测试踩 @Rollback 泄漏**（memory）→ 集成测试按 `lead_id` 过滤断言，禁 raw TRUNCATE；`mvn verify` 不与 dev smoke 并发。

## Migration Plan

1. `V8` 加 `detail JSON NULL`（纯加列，无回填，旧行 NULL）。
2. 扩 `SystemLogPort` + 改 `JdbcSystemLogPort` 序列化；12 写点逐个改传 detail（保 `summary`）。
3. 加 `SystemLogMapper` 读查询 + `SystemLogReadService` + 两个端点 + 读 DTO + action 标签表。
4. 前端：详情时间线 section、恢复 `/system-logs` 路由+导航、全局页、`api/systemLogs.ts`、store。
5. **回滚**：端点/前端可直接回退；`detail` 列为加列，保留无害（写点回退为不填 detail 即可）。

## Open Questions

- 全局页是否需要操作人/客户名等冗余展示列以便 ADMIN 扫读，还是仅 action+目标+时间+摘要起步？（倾向后者起步，design 不锁死，apply 时按 UI 简洁度定）
- `detail` 的 JSON 形状是否要为每类 action 定义后端类型化 record（更强约束）还是用通用 Map（更省）？（倾向类型化 record 提升写侧正确性，tasks 中确认）
