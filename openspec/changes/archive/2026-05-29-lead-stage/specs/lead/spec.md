## ADDED Requirements

### Requirement: 线索阶段变更

系统 SHALL 提供阶段变更端点 `PATCH /api/leads/{id}/stage`，请求体携带目标 `stage`（中文阶段名）。目标阶段 SHALL 为四个非结束阶段之一（`未触达` / `初步沟通` / `方案报价` / `商务谈判`），允许在它们之间任意方向跳转（无强制线性顺序），且 SHALL **不**等于线索当前阶段。变更 SHALL 在后端单一事务内先对目标行加锁再校验后更新。本端点 SHALL **不**用于进入结束阶段（`已赢单` / `已流失`），也 SHALL **不**修改线索的 `lastTrackedAt`。

权限：**ADMIN** SHALL 可变更任意线索的阶段（含公海 `owner_sales_id IS NULL` 与他人名下）；**SALES** SHALL 仅可变更 `owner_sales_id` 等于自己的线索，对其余线索（公海、他人名下、不存在）SHALL 返回 `404 NOT_FOUND`，且 `message` SHALL **不**暗示该线索是否存在。

#### Scenario: SALES 推进自己名下线索到另一非结束阶段

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`初步沟通`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `PATCH /api/leads/L/stage`，body `{ "stage": "方案报价" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `方案报价`

#### Scenario: 非结束阶段间可任意方向跳转（回退）

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `PATCH /api/leads/L/stage`，body `{ "stage": "未触达" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `未触达`

#### Scenario: ADMIN 变更他人名下线索的阶段

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `PATCH /api/leads/L/stage`，body `{ "stage": "商务谈判" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `商务谈判`

#### Scenario: ADMIN 变更公海线索的阶段

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `PATCH /api/leads/L/stage`，body `{ "stage": "初步沟通" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `初步沟通`

#### Scenario: SALES 变更他人名下线索返回不可区分的 404

- **GIVEN** 数据库存在线索 L（owner_sales_id=99，归他人、未结束）
- **WHEN** SALES（账号 id=7）请求 `PATCH /api/leads/L/stage`，body `{ "stage": "方案报价" }`
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`
- **AND** `message` 与"线索不存在"返回的 message 不可区分
- **AND** L 的 `stage` 保持不变

#### Scenario: SALES 变更公海线索返回 404

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`）
- **WHEN** SALES（账号 id=7）请求 `PATCH /api/leads/L/stage`，body `{ "stage": "初步沟通" }`
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`
- **AND** L 的 `stage` 保持不变

#### Scenario: 已结束线索阶段只读

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `PATCH /api/leads/L/stage`，body `{ "stage": "方案报价" }`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** L 的 `stage` 仍为 `已赢单`

#### Scenario: 已结束线索的只读优先于目标非法

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已流失`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `PATCH /api/leads/L/stage`，body `{ "stage": "不存在的阶段" }`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** L 的 `stage` 仍为 `已流失`

#### Scenario: 目标为结束阶段被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `PATCH /api/leads/L/stage`，body `{ "stage": "已赢单" }`（或 `已流失`）
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `stage` 仍为 `商务谈判`

#### Scenario: 目标为非法枚举被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`初步沟通`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `PATCH /api/leads/L/stage`，body `{ "stage": "foo" }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `stage` 仍为 `初步沟通`

#### Scenario: 目标等于当前阶段（no-op）被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `PATCH /api/leads/L/stage`，body `{ "stage": "方案报价" }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `stage` 仍为 `方案报价`
- **AND** `system_log` 表行数与请求前一致

#### Scenario: 阶段变更生成含原阶段与新阶段的系统日志

- **GIVEN** `system_log` 表当前行数为 N，线索 L（owner_sales_id=7、stage=`初步沟通`）
- **WHEN** SALES（账号 id=7）成功将 L 变更为 `方案报价`
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_STAGE_CHANGE"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=7`、`lead_id=L.id`
- **AND** 该行 `summary` 同时包含原阶段文本 `初步沟通` 与新阶段文本 `方案报价`

#### Scenario: 阶段变更不更新最后跟踪时间

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`未触达`、`lastTrackedAt` 为某固定值 T0），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 成功将 L 变更为 `初步沟通`
- **THEN** L 的 `lastTrackedAt` 仍为 T0（未被阶段变更触碰）
