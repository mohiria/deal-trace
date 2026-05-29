## ADDED Requirements

### Requirement: 标记赢单

系统 SHALL 提供赢单端点 `POST /api/leads/{id}/win`，请求体携带 `contractAmount` 与 `signedDate`。标记赢单是独立动作（非修改阶段）。`contractAmount` SHALL 必填、大于 0、最多 2 位小数（精确数值类型）；`signedDate` SHALL 必填且为合法日期（不限范围）。变更 SHALL 在后端单一事务内先对目标行加锁再校验后执行，成功时 SHALL 原子地：将 `stage` 更新为 `已赢单`、写入服务端时钟生成的 `wonAt`、生成 1 条合同记录、记一条 `LEAD_WIN` 系统日志。每条线索 SHALL 最多对应 1 条合同记录。

权限：**ADMIN** SHALL 可对任意线索标记赢单（含公海 `owner_sales_id IS NULL` 与他人名下）；**SALES** SHALL 仅可对 `owner_sales_id` 等于自己的线索标记赢单，对其余线索 SHALL 返回 `404 NOT_FOUND` 且 `message` SHALL **不**暗示该线索是否存在。已结束线索（`已赢单`/`已流失`）SHALL 只读：对其标记赢单 SHALL 返回 `LEAD_ENDED_READONLY`（此规则同时覆盖"重复标记赢单"）。

#### Scenario: SALES 对自己名下线索标记赢单成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/win`，body `{ "contractAmount": "120000.00", "signedDate": "2026-05-20" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `已赢单`，`wonAt` 非空
- **AND** 数据库新增 1 条关联 L 的合同记录

#### Scenario: ADMIN 对他人名下线索标记赢单成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `POST /api/leads/L/win`，body 含合法金额与签订日
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `已赢单`

#### Scenario: ADMIN 对公海线索标记赢单成功

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `POST /api/leads/L/win`，body 含合法金额与签订日
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `已赢单`
- **AND** 生成的合同记录成交销售为空（NULL）

#### Scenario: SALES 对非自己名下线索标记赢单返回 404

- **GIVEN** 数据库存在线索 L（owner_sales_id=99，归他人或公海）
- **WHEN** SALES（账号 id=7）请求 `POST /api/leads/L/win`，body 含合法金额与签订日
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`
- **AND** L 的 `stage` 保持不变，数据库未新增合同记录

#### Scenario: 对已结束线索标记赢单被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已流失`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/win`，body 含合法金额与签订日
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** L 的 `stage` 仍为 `已流失`，数据库未新增合同记录

#### Scenario: 重复标记赢单被拒（每线索最多一条合同）

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`，已有 1 条合同），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 再次请求 `POST /api/leads/L/win`，body 含合法金额与签订日
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** L 关联的合同记录数仍为 1

#### Scenario: 金额非法被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/win`，body 的 `contractAmount` 为 `"0"` / `"-5"` / `"100.123"`（超 2 位小数）/ 缺失 之一
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `stage` 仍为 `商务谈判`，数据库未新增合同记录

#### Scenario: 签订日期非法被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/win`，body 的 `signedDate` 为 `"2026-13-40"` / 空 之一（金额合法）
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `stage` 仍为 `商务谈判`

#### Scenario: 赢单生成含金额与签订日的系统日志

- **GIVEN** `system_log` 表当前关联 L 行数为 N，线索 L（owner_sales_id=7、stage=`商务谈判`）
- **WHEN** SALES（账号 id=7）以 `contractAmount="88888.88"`、`signedDate="2026-05-20"` 成功赢单 L
- **THEN** `system_log` 表关联 L 行数为 N+1
- **AND** 新增行 `action="LEAD_WIN"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=7`、`lead_id=L.id`
- **AND** 该行 `summary` 同时包含金额文本 `88888.88` 与签订日文本 `2026-05-20`

### Requirement: 标记流失

系统 SHALL 提供流失端点 `POST /api/leads/{id}/lose`，请求体携带 `loseReason` 与可选 `loseNote`。标记流失是独立动作（非修改阶段）。`loseReason` SHALL 必填且为枚举之一（`价格过高`/`选择竞品`/`无明确需求`/`联系不上`/`其他`）；当 `loseReason` 为 `其他` 时 `loseNote` SHALL trim 后必填非空。变更 SHALL 在后端单一事务内先对目标行加锁再校验后执行，成功时 SHALL 原子地：将 `stage` 更新为 `已流失`、写入服务端时钟生成的 `lostAt`、写入 `loseReason`/`loseNote`、记一条 `LEAD_LOSE` 系统日志。

权限与只读规则同标记赢单：**ADMIN** 任意线索、**SALES** 仅自己名下（其余 `404 NOT_FOUND` 不泄漏存在性）；已结束线索标记流失 SHALL 返回 `LEAD_ENDED_READONLY`（覆盖"重复标记流失"）。

#### Scenario: SALES 对自己名下线索标记流失成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/lose`，body `{ "loseReason": "价格过高" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `已流失`，`lostAt` 非空，`loseReason` 为 `价格过高`

#### Scenario: 流失原因为其他但缺说明被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`初步沟通`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/lose`，body `{ "loseReason": "其他" }` 或 `{ "loseReason": "其他", "loseNote": "   " }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `stage` 仍为 `初步沟通`

#### Scenario: 流失原因为其他且带说明成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`初步沟通`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/lose`，body `{ "loseReason": "其他", "loseNote": "客户内部重组" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `已流失`，`loseReason` 为 `其他`，`loseNote` 为 `客户内部重组`

#### Scenario: 流失原因非法枚举被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/lose`，body `{ "loseReason": "foo" }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `stage` 仍为 `方案报价`

#### Scenario: SALES 对非自己名下线索标记流失返回 404

- **GIVEN** 数据库存在线索 L（owner_sales_id=99，归他人或公海）
- **WHEN** SALES（账号 id=7）请求 `POST /api/leads/L/lose`，body 含合法原因
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`
- **AND** L 的 `stage` 保持不变

#### Scenario: ADMIN 对公海线索标记流失成功

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `POST /api/leads/L/lose`，body `{ "loseReason": "联系不上" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `stage` 变为 `已流失`

#### Scenario: 对已结束线索标记流失被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/lose`，body 含合法原因
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** L 的 `stage` 仍为 `已赢单`

#### Scenario: 流失生成含原因与说明的系统日志

- **GIVEN** `system_log` 表当前关联 L 行数为 N，线索 L（owner_sales_id=7、stage=`方案报价`）
- **WHEN** SALES（账号 id=7）以 `loseReason="其他"`、`loseNote="客户内部重组"` 成功流失 L
- **THEN** `system_log` 表关联 L 行数为 N+1
- **AND** 新增行 `action="LEAD_LOSE"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=7`、`lead_id=L.id`
- **AND** 该行 `summary` 同时包含原因文本 `其他` 与说明文本 `客户内部重组`
