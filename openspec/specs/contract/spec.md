# contract Specification

## Purpose
TBD - created by archiving change lead-closure. Update Purpose after archive.
## Requirements
### Requirement: 合同记录由赢单原子生成

合同记录 SHALL 维护字段：关联线索 `leadId`、合同金额 `contractAmount`、签订日期 `signedDate`、成交销售 `dealSalesId`、创建时间 `createdAt`。合同记录 SHALL 仅由"标记赢单"动作在其事务内原子生成，系统 SHALL **不**提供独立创建合同的入口。`contractAmount` SHALL 以精确数值类型持久化（不使用浮点类型），保留 2 位小数。`signedDate` SHALL 持久化为用户提交的业务日期，与服务端事件时间戳 `createdAt`（及线索 `wonAt`）相互独立。

#### Scenario: 赢单生成合同记录并落盘正确字段

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`）
- **WHEN** 已认证用户以 `contractAmount="120000.50"`、`signedDate="2026-05-20"` 成功赢单 L
- **THEN** 数据库新增 1 条合同记录，`lead_id=L.id`、`contract_amount` 为 `120000.50`、`signed_date` 为 `2026-05-20`、`created_at` 非空

#### Scenario: 成交销售取赢单时刻线索归属

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`）
- **WHEN** ADMIN 成功赢单 L
- **THEN** 新增合同记录的 `deal_sales_id` 为 `7`（线索归属销售，而非操作的 ADMIN）

#### Scenario: 赢单公海线索时成交销售为空

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`）
- **WHEN** ADMIN 成功赢单 L
- **THEN** 新增合同记录的 `deal_sales_id` 为 NULL

### Requirement: 每条线索最多一条合同记录

系统 SHALL 保证每条线索最多对应 1 条合同记录，并以数据库层唯一约束兜底（`lead_id` 唯一）。在应用层，已赢单线索因结束状态只读而无法再次赢单，故正常路径不会产生第二条合同；唯一约束为并发与异常路径的最终防线。

#### Scenario: 同一线索不可生成第二条合同

- **GIVEN** 数据库存在已赢单线索 L（stage=`已赢单`，已有 1 条合同记录）
- **WHEN** 任意角色尝试再次对 L 赢单
- **THEN** 该尝试被拒（结束状态只读），数据库中关联 L 的合同记录数仍为 1

#### Scenario: 赢单事务失败不残留合同记录

- **GIVEN** 数据库存在线索 L（stage=`商务谈判`、关联 L 的合同记录数为 0）
- **WHEN** 一次赢单请求因校验失败而未成功（如金额非法）
- **THEN** 数据库中关联 L 的合同记录数仍为 0，线索 `stage` 保持不变

