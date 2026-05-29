## 1. QA 测试设计（TDD 前置）

- [x] 1.1 用 `.claude/skills/vibe-coding-qa/templates/` 在 `openspec/changes/lead-closure/qa/lightweight-test-design.md` 写闭环测试设计，逐条映射 lead spec（赢单 9 + 流失 8 Scenario）与 contract spec（5 Scenario）到 API/集成用例
- [x] 1.2 在 `qa/regression-impact-analysis.md` 记录共享 `lead` 表 / `system_log` / 与 lead-stage·ownership 邻接的回归范围，标注新增 V6 contract 表的迁移风险

## 2. 数据层（迁移 + 实体 + DTO + 枚举）

- [x] 2.1 新增 V6 Flyway 迁移建 `contract` 表：`id` / `lead_id`(FK→`lead`, UNIQUE) / `contract_amount DECIMAL(15,2)` / `signed_date DATE` / `deal_sales_id`(FK→`account`, NULL) / `created_at DATETIME(3)`；反引号注意 `lead` 保留字；charset/collation 沿用库默认
- [x] 2.2 新增 `Contract` 实体（`@TableName("contract")`）+ `ContractMapper`（BaseMapper，按需加 `countByLeadId`）
- [x] 2.3 新增 `LoseReason` 枚举（中文 dbValue：价格过高/选择竞品/无明确需求/联系不上/其他，仿 `BusinessType` 的 `@EnumValue`）+ `fromDbValue`
- [x] 2.4 新增 `WinLeadRequest`(contractAmount: BigDecimal, signedDate: LocalDate) 与 `LoseLeadRequest`(loseReason: String, loseNote: String) DTO；不加 `@NotBlank`，校验在 service
- [x] 2.5 在 `LeadMapper` 加 `updateWon`(id, stage, wonAt) 与 `updateLost`(id, stage, lostAt, loseReason, loseNote) 定向更新，反引号保留字

## 3. 赢单服务（Red → Green）

- [x] 3.1 写失败测试：SALES 赢自己单成功（stage=已赢单、wonAt 非空、生成 1 条合同）
- [x] 3.2 写失败测试：ADMIN 赢他人名下 / 公海单成功（公海单 deal_sales_id=NULL）
- [x] 3.3 写失败测试：成交销售取赢单时刻 owner（Admin 赢他人单 → deal_sales_id=owner 而非 Admin）
- [x] 3.4 写失败测试：SALES 赢他人/公海 → 404；赢已结束 / 二次赢单 → LEAD_ENDED_READONLY（合同数仍 1）
- [x] 3.5 写失败测试：金额≤0/超 2 位小数/缺失 → VALIDATION_ERROR；签订日非法/缺失 → VALIDATION_ERROR（均不残留合同、stage 不变）
- [x] 3.6 写失败测试：LEAD_WIN 日志含金额+签订日摘要
- [x] 3.7 实现赢单服务方法：单一 @Transactional 内 `selectByIdForUpdate` → 角色/归属校验(404) → 终态只读(LEAD_ENDED_READONLY) → 金额(BigDecimal: >0 且 stripTrailingZeros().scale()≤2)/日期校验 → `updateWon` → `contractMapper.insert`(deal_sales_id=赢单时 owner) → `record("LEAD_WIN", ...)`，使 3.1–3.6 全绿

## 4. 流失服务（Red → Green）

- [x] 4.1 写失败测试：SALES 流失自己单成功（stage=已流失、lostAt、lose_reason/note）
- [x] 4.2 写失败测试：原因=其他缺说明 → VALIDATION_ERROR；原因=其他带说明成功
- [x] 4.3 写失败测试：原因非法枚举 → VALIDATION_ERROR；Sales 流失他人 → 404；流失已结束 → LEAD_ENDED_READONLY；Admin 流失公海单成功
- [x] 4.4 写失败测试：LEAD_LOSE 日志含原因+说明摘要
- [x] 4.5 实现流失服务方法：单一 @Transactional 内锁行 → 角色/归属校验 → 终态只读 → loseReason 枚举校验（其他→loseNote 必填）→ `updateLost` → `record("LEAD_LOSE", ...)`，使 4.1–4.4 全绿

## 5. 控制器层

- [x] 5.1 在 `LeadController` 新增 `@PostMapping("/{id}/win")` 与 `@PostMapping("/{id}/lose")`（无 `@PreAuthorize`，双角色可达），绑定 DTO 与 `AccountPrincipal`，委派闭单服务
- [x] 5.2 补 API 层测试（MockMvc + 真 MySQL 8.4）覆盖 HTTP 状态码与 `code`，与 spec Scenario 的 HTTP 断言一致

## 6. 回归与验证

- [x] 6.1 跑全量 `lead` 套件（claim/release/assign/recall/transfer/stage）确认共享 `lead` 表/`updateWon`/`updateLost` 无回归
- [x] 6.2 跑全量后端套件确认 V6 迁移与共享 `system_log` 写入无回归
- [x] 6.3 `openspec validate lead-closure` 通过；按 `qa/qa-test-report.md` 模板记录 Red→Green 证据与剩余风险
