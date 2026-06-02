## Why

系统日志的「写」侧齐全（12 种业务事件均落 `system_log`），但「读」侧完全空白：无查询、无 Controller，线索详情页缺系统日志时间线，`/system-logs` 路由仅占位、导航被隐藏。PRD §7.8 明确要求「线索详情展示……进度跟踪**和系统日志**」（验收 §11.7），当前这条 PRD 强制项未交付。同时现有写侧摘要为 `|` 分隔 freetext、归属冻结存邮箱，无法支撑结构化渲染与姓名展示。

## What Changes

- **新增系统日志读能力**：
  - `GET /leads/{id}/logs`——线索维度倒序日志，权限镜像 progress-log（ADMIN 任意线索 / SALES 仅自己名下 / 否则 `404` 不泄漏存在性）。
  - `GET /system-logs`——全局日志浏览（ADMIN only），分页、倒序、跨 `ACCOUNT`+`LEAD` 事件，支持按 `action` / `target_type` 轻筛选。
  - 读层组装展示，**不依赖** freetext 格式：`operator_id`→当前姓名（NULL→"系统"）、归属 `ownerId`→当前姓名（按当前解析）、`action`→中文标签、金额→千分位。
- **增补系统日志写侧为结构化**（支撑上述读）：
  - `system_log` 新增 `detail` JSON 列（非破坏：旧行 `detail=NULL`）。
  - 重写全部 12 个写点填结构化引用：5 个归属事件（CLAIM/RELEASE/ASSIGN/RECALL/TRANSFER）由存 email 改存 `fromOwnerId`/`toOwnerId`；STAGE_CHANGE 存原/新阶段码；WIN 存合同金额（精确 decimal 字符串，禁 float）+ 签订日期；LOSE 存流失原因码 + 说明；ACCOUNT_CREATE/ENABLE/DISABLE 结构化。
  - 保留 freetext `summary` 作为人读 fallback；读层双路径：`detail` 有则结构化渲染，`detail=NULL`（旧行）则回退渲染 `summary`。
- **前端**：
  - `LeadDetailPanel` 新增系统日志时间线 section（复用 `.progress-list`/`.event` 样式，新增 store `loadSystemLog` + api `listLogs`，镜像 `loadProgress`/`listProgress`）。
  - 恢复 `/system-logs` 路由 + ADMIN 导航入口，新增全局日志浏览页。

## Capabilities

### New Capabilities
- `system-log-view`: 系统日志读出行为——线索维度与全局两个读面、权限隔离与不泄漏、倒序、姓名/标签/千分位组装、结构化与 freetext fallback 双路径渲染。

### Modified Capabilities
- `system-log`: 写侧增补结构化 `detail` 载荷要求——既有「最小字段集」「不可变」「服务端时间戳」「仅系统生成」「多态 target」「写失败不阻塞」要求不变，新增「关键变更以结构化引用持久化（归属存 id 而非展示串）」与「`detail` 可空、非破坏演进」要求。

## Impact

- **DB**：新增 Flyway 迁移 `V8`（`system_log` 加 `detail JSON NULL`）；现有读索引（`idx_system_log_lead_created_at` 等）已就位，无需新增。
- **后端**：新增 `SystemLogReadService` + `SystemLogController`（全局）+ `LeadController` 增 `GET /{id}/logs`；`SystemLogPort` 写签名扩展承载结构化 detail；改写 `AdminAccountController`、`LeadService`、`LeadStageService`、`LeadOwnershipService`、`LeadClosureService` 共 12 个写点；新增 `SystemLogMapper` 读查询、action→标签映射、日志读 DTO。
- **前端**：`router/index.ts`、`components/navigation.ts`、`LeadDetailPanel.vue`、新增全局日志页与 `api/systemLogs.ts`、`stores` 扩展。
- **测试**：真 MySQL 8.4 集成测试（禁 H2/mock）；按 `lead_id` 过滤、禁 raw TRUNCATE；`mvn verify` 勿与 dev smoke 并发；保留字 `lead` 反引号。
- **非破坏**：旧 `detail=NULL` 行经 fallback 渲染，联调/测试库无需重灌。
