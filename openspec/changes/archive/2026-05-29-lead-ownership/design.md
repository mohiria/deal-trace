## Context

lead-core 已落地 `lead` 表（含预留的 `owner_sales_id NULL` 列、`idx_lead_owner_created` 索引）、`LeadService` / `LeadController` / `LeadMapper`，以及详情 / 列表 / 创建 / 查重预检端点与角色隔离骨架。归属流转（公海 / 认领 / 退回 / 分配 / 回收 / 转移）在 lead-core 被显式排除（lead spec 最后一条 requirement）。

本 change 在已有结构上增量加入 6 个端点，不改 schema。约束来自：
- PRD §7.5（公海池）、§7.9（退回）、§7.10（Admin 分配 / 回收 / 转移）、§8.4（权限隔离）、§8.5（结束只读）、§11.5 / §11.8。
- tech-arch §6.3.8/9（`LEAD_ALREADY_CLAIMED` / `LEAD_ENDED_READONLY` 已占位）、§8.3（认领并发后端兜底）、§9.4（手机号脱敏分级）、§12（集成测试用真 MySQL 8.4）。
- system-log capability：`SystemLogPort.record(...)` 已存在，action 为开放字符串，可直接新增 5 个值。

## Goals / Non-Goals

**Goals:**
- 让 Sales 看见并认领公海线索，认领并发由后端原子兜底（只一人成功）。
- 让 Sales 主动退回自己名下未结束线索，必填备注。
- 让 Admin 对未结束线索分配 / 回收 / 转移。
- 公海列表对 Sales 脱敏联系电话、对 Admin 明文。
- 5 个写动作各生成一条系统日志，记录原 / 新归属。
- 零 schema 改动。

**Non-Goals:**
- 阶段变更（lead-stage）、赢单 / 流失 + 合同（lead-closure）、进度跟踪（progress-log）——各自独立 change。
- Sales 通过 `GET /api/leads/{id}` 只读访问公海线索详情——本 change 不动 lead-core 的"非自己名下 → 404"契约；Sales 经公海列表认领即可。
- 「客户其他业务线索提示」（PRD §7.6）——独立关注点，不在本 change。
- 撤销 / 历史归属链查询（MVP 不支持）。

## Decisions

### D1：认领并发用 `SELECT … FOR UPDATE` + 应用层校验 + `UPDATE`，5 动作共用骨架

5 个写动作统一为一个事务模板：
```
BEGIN
  Lead lead = mapper.selectByIdForUpdate(id)   -- 行锁
  if (lead == null) → NOT_FOUND
  if (lead.isEnded()) → LEAD_ENDED_READONLY
  <动作专属前置校验>
  mapper.updateOwner(id, newOwnerId)
  systemLogPort.record(action, "LEAD", id, operatorId, leadId=id, summary)
COMMIT
```

**理由**：release / assign / recall / transfer 本就要先读出原 owner（系统日志 summary 需要"原归属 → 新归属"）。让 claim 也走"读后写"，5 动作就是同一份骨架，只换校验条件与日志 action。

**备选**：条件 `UPDATE … WHERE owner_sales_id IS NULL AND stage NOT IN (...)`，靠 affected_rows 判成败。被否——affected_rows=0 时仍要再 SELECT 定位失败原因（不存在 / 被抢 / 已结束），把失败路径拆成额外查询，代码反而分裂；这点 round-trip 节省在本项目体量下不值。行锁竞争仅在同一 lead 行、并发量低，性能非瓶颈。

### D2：claim 失败码——"不在公海"一律 `LEAD_ALREADY_CLAIMED`

claim 的失败映射：
- 线索不存在 → `NOT_FOUND`
- 已结束 → `LEAD_ENDED_READONLY`
- 调用者账号 DISABLED → `FORBIDDEN`（但 DISABLED 账号通常已无法通过登录拿到有效 token；此为纵深防御）
- 线索存在、未结束、但 `owner_sales_id IS NOT NULL`（被人抢先 or 本就非公海）→ `LEAD_ALREADY_CLAIMED`

**理由**：PRD §7.5.7 原文"先成功者获得归属，后续认领失败，并提示『该线索已被认领』"——不区分 race 与"早就有主"。把两种情形合并到 `LEAD_ALREADY_CLAIMED`，贴合用户原文，且不暴露"该线索其实归属另一位 Sales"（与 §8.4 不泄漏私海一致）。

**备选**：用 `NOT_FOUND` 统一非公海情形（与 lead-core 详情口吻一致）。被否——PRD 对认领失败明确要"该线索已被认领"提示，`NOT_FOUND` 会让前端无法给出正确文案。

### D3：HTTP 状态码映射

- `LEAD_ALREADY_CLAIMED` → HTTP **409 Conflict**（并发抢占语义，RESTful 惯例）。
- `LEAD_ENDED_READONLY` → HTTP **400 Bad Request**（与既有 `DUPLICATE_*` 一族对齐，属"请求与当前状态冲突的业务拒绝"）。
- 其余非法状态（目标 Sales 不存在 / 停用 / 非 SALES / 线索归属态不匹配动作 / releaseNote 空 / transfer 目标同现归属）→ `VALIDATION_ERROR` → HTTP **400**。

**理由**：tech-arch §6.3 未规定 HTTP 码与错误码的精确映射，仅列错误码类别。`LEAD_ALREADY_CLAIMED` 唯一带"并发竞争"语义，用 409 让前端区分"重试无意义"（409）与"参数错"（400）。若团队倾向全用 400，spec 不锁死 HTTP 码、只锁 `code` 字段——见 spec scenario 写法（断言 `code`，HTTP 码用 409/400 标注）。

### D4：6 个端点形态

| 动作 | 方法 + 路径 | 角色 | body |
| :--- | :--- | :--- | :--- |
| 公海列表 | `GET /api/leads/pool` | ADMIN + SALES | —— |
| 认领 | `POST /api/leads/{id}/claim` | SALES | —— |
| 退回 | `POST /api/leads/{id}/release` | SALES（owner=self） | `{ releaseNote }` |
| 分配 | `POST /api/leads/{id}/assign` | ADMIN | `{ salesId }` |
| 回收 | `POST /api/leads/{id}/recall` | ADMIN | —— |
| 转移 | `POST /api/leads/{id}/transfer` | ADMIN | `{ salesId }` |

**公海列表用独立端点**（不复用 `GET /api/leads`）：
- lead-core spec 钉死"Sales 调 `GET /api/leads` → 403"，复用会跨 capability 改既有 requirement。
- 公海是 PRD 单独成节的一等概念，独立路径贴语义、脱敏逻辑只活在一个 controller。
- `GET /api/leads/pool` 返回 `owner_sales_id IS NULL AND stage NOT IN ('已赢单','已流失')` 的行，按 `created_at` 倒序，硬上限 50（与 mine / 全局列表一致，不引分页）。

### D5：releaseNote 只进 system_log.summary

退回备注（PRD §7.9.1 必填、§7.9.5"作为退回操作内容正常展示"）直接写入 `LEAD_RELEASE` 日志的 `summary`，lead 表不加列。

**理由**：退回事件即历史，多次退回天然成多条日志；若在 lead 表加 `last_release_note` 列则每次退回覆盖，与"展示退回操作内容"语义对不上，且违背"零 schema 改动"。

### D6：系统日志 summary 用自然语言承载原 / 新归属

PRD §7.8.8 / §11.7.11 要求归属变更日志记录原归属与新归属。summary 存可读文本（如 `"由公海认领"` / `"由公海分配给 zhang@x.com"` / `"由 li@x.com 转移给 wang@x.com"` / `"退回公海：客户暂无预算"`）。

**理由**：MVP 展示场景是线索详情时间线（PRD §7.8），自然语言零前端解析成本。system-log spec 只约束 summary 为可选 VARCHAR，本 change 不发明结构化字段。spec 只断言 summary 包含原 / 新归属可读信息，不锁字符串格式。

### D7：手机号脱敏工具 `PhoneMasker`

按 tech-arch §9.4：
- 11 位手机号（`1[3-9]` 开头）→ 前 3 + `****` + 后 4（`138****5678`）。
- ≥8 位其他号码（座机等）→ 前 3 + `****` + 后 4。
- <8 位 → 仅显示末 2 位（前面用 `****` 占位）。

纯函数 + 单元测试覆盖三档边界。公海列表 Sales 视角调用；Admin 视角直返明文。

## Risks / Trade-offs

- **[409 vs 400 团队约定未定]** → spec 断言 `code` 字段而非 HTTP 码硬绑；若团队后续统一全 400，仅改 GlobalExceptionHandler 映射，不动 spec。
- **[FOR UPDATE 行锁在高并发下排队]** → 仅锁单 lead 行、认领并发量低（一条公海线索同时被多人抢的窗口极短），非瓶颈；真出现热点可后续改乐观锁。
- **[Admin 调 `GET /api/leads/pool` 与 `GET /api/leads` 数据重叠]** → 可接受：pool 是"只看公海"的便捷视图，全局列表含公海，二者语义不冲突。
- **[DISABLED Sales 持旧 token 认领]** → claim 路径显式查调用者 account 状态，DISABLED → FORBIDDEN，纵深防御。
- **[查询 account 状态 / 角色的额外往返]** → assign / transfer 需校验目标 Sales 为 ENABLED SALES；走 account 主键查询，已有索引覆盖，开销可忽略。

## Migration Plan

无 DB migration。部署即新增代码 + 两个 ErrorCode 枚举值 + GlobalExceptionHandler switch 分支。回滚 = 删新代码并还原 ErrorCode / GlobalExceptionHandler / LeadController / LeadMapper；无 schema 变更需回退。

## Open Questions

- 无阻塞性未决项。HTTP 409 vs 400 的团队偏好可在 apply 阶段最终确认，不影响 spec 与契约。
