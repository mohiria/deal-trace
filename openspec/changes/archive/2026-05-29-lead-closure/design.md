## Context

lead capability 已分四片：lead-core、lead-ownership、lead-stage 已落地。lead-stage spec 在 `PATCH /stage` 显式拒绝终态目标，并注明进入 已赢单/已流失 推迟到本片。lead 表 V5 已预留 `lose_reason / lose_note / won_at / lost_at`（NULL 列）；`contract` 表尚不存在。

约束来源：PRD §7.11（赢单/流失闭环）、§9.6（合同记录对象）、§8.4（结束状态只读）、§8.5、§7.8.9-10（赢单/流失日志摘要）、tech-arch §9.2（金额精确类型）、§6.1.4（每线索≤1 合同）、§13.2（域拆分：`lead` 含结束状态、`contract` 独立）、§12（真 MySQL 测试）。CLAUDE.md：金额禁 float/double、时间戳服务端生成、schema 名不带环境标签。

现成可复用件：`LeadStage`（WON/LOST `isActive()==false`）；`LeadMapper.selectByIdForUpdate`（行锁读）；lead-stage / ownership 的 indistinguishable-404 模式与错误优先级阶梯；`SystemLogPort.record(action, "LEAD", leadId, operatorId, summary)`（targetType=LEAD 时自动填 lead_id）；`ErrorCode.{VALIDATION_ERROR, NOT_FOUND, LEAD_ENDED_READONLY}` 与 `GlobalExceptionHandler` 映射；`BusinessType`/`LeadStage` 的 `@EnumValue` 中文枚举范式。

## Goals / Non-Goals

**Goals:**
- 提供赢单 / 流失两个原子闭单动作，完成 lead 生命周期。
- 赢单联动生成合同记录（每线索≤1，DB 强约束）。
- 终态只读 / 不可重复标记 / 角色矩阵与 lead-stage 一致且可测。
- 金额精确类型、时间戳服务端生成。

**Non-Goals:**
- 合同读取端点 / 合同内联进线索详情（留 dashboard 切片）。
- 撤销赢单 / 撤销流失（PRD §7.11.1.12 / §7.11.2.7 / §7.7.10：MVP 不支持）。
- 修改合同金额 / 签订日期 / 流失原因 / 流失说明（MVP 不支持）。
- 金额千分位展示（前端关注点，不入后端契约）。
- 改动 lead-core 查重逻辑（闭环只设置 stage，dedup 自然读取）。

## Decisions

**D1 — 赢单/流失用专用 POST 动作端点，非 PATCH /stage。**
PRD §7.11.1.1 / §7.11.2.1 明确"标记赢单/流失是独立动作，不是单纯修改阶段"。`POST /api/leads/{id}/win`、`POST /api/leads/{id}/lose`，与 ownership 的 claim/release 动作端点同族；lead-stage 的 `PATCH /stage` 已正确拒绝终态目标，二者职责不重叠。

**D2 — 操作人/可见性沿用 lead-stage（D2/D3）。**
ADMIN 改任意线索（含公海 owner=NULL、他人名下）；SALES 仅 `owner_sales_id==自己`，其余统一 404 不泄漏存在性。沿用是因闭单与改阶段同属"对线索的写动作"，权限边界应一致；Admin 闭单权同为对 PRD §7.11（未点名操作人）的 gap-fill，非冲突。

**D3 — 错误优先级阶梯（与 lead-stage D4 同构）。**
```
1. 线索不存在                       → 404 NOT_FOUND        (selectByIdForUpdate 返回 null)
2. SALES 且 owner != caller         → 404 NOT_FOUND        (ADMIN 跳过)
3. 当前 stage 已结束 (WON/LOST)      → 400 LEAD_ENDED_READONLY  (覆盖"对已结束标记"与"重复标记")
4. 入参校验失败                      → 400 VALIDATION_ERROR
   ✅ 通过 → 更新终态 + 记日志（赢单另插合同）
```
关 3 先于关 4：线索终态是支配性事实。无前置阶段要求：任意非终态阶段均可闭单。

**D4 — 赢单原子事务与成交销售取值。**
单一 `@Transactional` 内：`selectByIdForUpdate` 锁行 → 角色/归属校验 → 终态只读校验 → 金额/日期校验 → `updateWon(leadId, WON, wonAt)` → `contractMapper.insert(...)` → `record("LEAD_WIN", ...)`。
- `won_at` = 服务端 `LocalDateTime.now()`（禁客户端时钟）。
- 合同 `deal_sales_id` = **赢单时刻 `lead.owner_sales_id`**（Sales 赢自己单=该 Sales；Admin 赢他人单=该 owner；Admin 赢公海单=NULL）。操作人 `operator_id` 仅入系统日志，与成交销售解耦——这样合同忠实反映"谁的业绩"，而非"谁点的按钮"。
- `signed_date`（用户填的业务日期，DATE）与 `won_at`（系统事件时间戳）是两个独立字段，不可混用。

**D5 — 金额精确类型 DECIMAL(15,2) / BigDecimal。**
tech-arch §9.2 + CLAUDE.md：禁 float/double。校验：必填、`> 0`、小数位 ≤ 2（用 `BigDecimal.scale()` 或 stripTrailingZeros 判定）。`DECIMAL(15,2)` 上限约 9999 亿元，覆盖中小 B2B；超精度/负数/零/缺失 → `VALIDATION_ERROR`。
_备选_：更大精度——放弃，MVP 无超大额场景，过宽无收益。

**D6 — 每线索≤1 合同：DB UNIQUE + 应用层双保险。**
`contract` 表 `UNIQUE(lead_id)` 为最终强约束（tech-arch §6.1.4）。应用层"二次赢单"已被关 3（LEAD_ENDED_READONLY）拦住——首次赢单后 stage=WON，再次赢单到不了 insert。故 UNIQUE 是并发/异常路径的兜底，正常路径不依赖其报错。
_理由_：行锁 + 终态只读已序列化同一 lead 的闭单；UNIQUE 防御极端并发。

**D7 — 流失原子事务与 LoseReason 枚举。**
新增 `LoseReason` 枚举（中文 dbValue：价格过高/选择竞品/无明确需求/联系不上/其他），仿 `BusinessType` 的 `@EnumValue`。流失校验：`loseReason` 必填且合法枚举；`loseReason==其他` 时 `loseNote` trim 后必填非空（非"其他"时 loseNote 可空，存与否由 design 取宽松——存原值或忽略）。成功：`updateLost(leadId, LOST, lostAt, loseReason, loseNote)` + `record("LEAD_LOSE", ...)`。

**D8 — 系统日志摘要格式（沿用 key=value | 风格）。**
- 赢单：`record("LEAD_WIN", "LEAD", leadId, operatorId, "标记赢单 | 合同金额=" + amount + " | 签订日期=" + signedDate)`（§7.8.9）。
- 流失：`record("LEAD_LOSE", "LEAD", leadId, operatorId, "标记流失 | 流失原因=" + reason + " | 流失说明=" + note)`（§7.8.10）。

**D9 — 合同只写不读。**
PRD §7.8 线索详情字段表不含合同；本片不提供合同读取端点，也不把合同内联进 `GET /leads/{id}`。读取（如 dashboard 本月赢单金额聚合）留给 dashboard 切片。

**D10 — 服务归属留给 apply。**
新建 `LeadClosureService` + `ContractMapper`，自包含（仿 lead-stage D8 的零侵入策略），不放宽 `LeadOwnershipService` 私有成员可见性。最终类名/复用由 apply 阶段按最小重复定夺，spec 不约束。

## Risks / Trade-offs

- **[关 3 与关 4 顺序若反，已结束线索 + 非法入参会误报 VALIDATION_ERROR]** → 测试显式覆盖"已结束线索赢单/流失 → LEAD_ENDED_READONLY"锁定顺序。
- **[Admin 赢公海单致 deal_sales_id=NULL，dashboard 归属统计需处理空成交销售]** → 本片如实落 NULL 并记测试；dashboard 切片需在归属语义里显式处理（design 留痕，供后续 dashboard 参考）。
- **[赢单事务跨 lead + contract 两表，任一失败须整体回滚]** → 单一 @Transactional 包裹；contract UNIQUE 冲突触发回滚不留半成品。
- **[BigDecimal 小数位校验：用户传 100.000 与 100.00 语义同但 scale 不同]** → 用 `stripTrailingZeros().scale() <= 2` 判定，避免把 100.000 误判超精度。
- **[non-其他 流失原因仍传了 loseNote]** → 取宽松：照存或忽略，不报错（PRD 仅要求"其他"必填说明，未禁止其他原因带说明）；测试不强约束该分支。

## Migration Plan

新增 V6 Flyway 迁移建 `contract` 表（向后兼容、纯增量）。lead 表无 ALTER（列已预留）。回滚：删除两端点 + 服务 + V6（开发期未上生产；contract 表可 DROP，lead 终态列回到全 NULL）。敏感迁移按 tech-arch §12 在真 MySQL 8.4 跑。
