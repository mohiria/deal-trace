## ADDED Requirements

### Requirement: 公海线索列表

系统 SHALL 提供公海线索列表端点 `GET /api/leads/pool`，对 **ADMIN 与 SALES** 角色均开放，返回当前 `owner_sales_id IS NULL` 且未结束（stage ∉ {`已赢单`, `已流失`}）的线索集合，按 `created_at` 倒序，硬上限 50 行（不引入 `page` / `size` 参数）。

每个列表项 SHALL 内联 customerName / customerUsci，并包含 businessYear / businessType / contactName / leadSource / stage / createdAt / lastTrackedAt 字段。联系电话字段按调用者角色分级：

- 调用者为 **ADMIN** 时 SHALL 返回明文 `contactPhone`。
- 调用者为 **SALES** 时 SHALL 返回脱敏后的联系电话（按 tech-arch §9.4：11 位手机号 → 前 3 + `****` + 后 4；≥8 位其他号码 → 前 3 + `****` + 后 4；<8 位 → 仅显示末 2 位），且 SHALL **不**返回明文电话。

公海线索的 `owner_sales_id` 恒为 NULL，列表 SHALL **不**返回归属销售信息。调用本端点 SHALL **不**产生任何持久化副作用、**不**生成系统日志。

#### Scenario: Sales 查看公海列表电话脱敏

- **GIVEN** 数据库存在一条公海线索（owner_sales_id 为 NULL、stage=`未触达`、contact_phone=`13812345678`）
- **WHEN** 持有 SALES 令牌的客户端请求 `GET /api/leads/pool`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 该项联系电话字段值为 `138****5678`
- **AND** 响应**不**包含该线索的明文 `13812345678`

#### Scenario: Admin 查看公海列表电话明文

- **GIVEN** 数据库存在一条公海线索（owner_sales_id 为 NULL、stage=`未触达`、contact_phone=`13812345678`）
- **WHEN** 持有 ADMIN 令牌的客户端请求 `GET /api/leads/pool`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 该项联系电话字段值为明文 `13812345678`

#### Scenario: 公海列表仅含未结束且无归属线索

- **GIVEN** 数据库存在 4 条线索：L1（公海 / 未触达）、L2（归 Sales A / 方案报价）、L3（公海 / 已流失）、L4（公海 / 已赢单）
- **WHEN** 已认证用户请求 `GET /api/leads/pool`
- **THEN** 响应 `data` 数组仅含 L1
- **AND** L2（有归属）、L3（已流失）、L4（已赢单）均**不**出现

#### Scenario: 公海列表查询不产生持久化副作用

- **WHEN** 已认证用户调用 `GET /api/leads/pool` 任意次
- **THEN** `lead` 表与 `system_log` 表的行数与调用前完全一致

### Requirement: Sales 认领公海线索

系统 SHALL 提供认领端点 `POST /api/leads/{id}/claim`，仅 **SALES** 角色可调用。认领 SHALL 在后端单一事务内原子完成（先对目标行加锁再校验后更新），成功条件为：目标线索存在、`owner_sales_id IS NULL`（在公海）、未结束、调用者为 `ENABLED` 状态的 SALES。

认领成功 SHALL 把 `owner_sales_id` 置为调用者账号 id，stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束（已赢单 / 已流失）→ `LEAD_ENDED_READONLY`。
- 目标线索存在、未结束、但 `owner_sales_id IS NOT NULL`（被他人抢先认领或本就非公海）→ `LEAD_ALREADY_CLAIMED`，`message` 表达"该线索已被认领"语义。
- 调用者账号处于 `DISABLED` 状态 → `FORBIDDEN`。

并发场景下，多个 SALES 同时认领同一公海线索时 SHALL 仅一人成功置为其归属，其余 SHALL 返回 `LEAD_ALREADY_CLAIMED`，且 lead 行最终 `owner_sales_id` 恒等于成功者 id。

#### Scenario: Sales 认领公海线索成功

- **GIVEN** 数据库存在一条公海线索 L（owner_sales_id 为 NULL、stage=`初步沟通`）
- **WHEN** 持有 SALES 令牌（账号 id=7、ENABLED）的客户端请求 `POST /api/leads/L/claim`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 `7`
- **AND** L 的 `stage` 仍为 `初步沟通`

#### Scenario: 认领已被认领的线索返回 LEAD_ALREADY_CLAIMED

- **GIVEN** 数据库存在一条线索 L，其 `owner_sales_id` 已为 Sales B 的 id（非公海）、未结束
- **WHEN** Sales C 请求 `POST /api/leads/L/claim`
- **THEN** 响应 HTTP `409`，`code="LEAD_ALREADY_CLAIMED"`
- **AND** L 的 `owner_sales_id` 仍为 Sales B 的 id

#### Scenario: 并发认领仅一人成功

- **GIVEN** 数据库存在一条公海线索 L（owner_sales_id 为 NULL、未结束）
- **WHEN** 两个不同 SALES 近乎同时请求 `POST /api/leads/L/claim`
- **THEN** 恰有一个请求响应 `code="SUCCESS"`，另一个响应 `code="LEAD_ALREADY_CLAIMED"`
- **AND** L 的最终 `owner_sales_id` 等于那个成功请求的调用者 id

#### Scenario: 认领已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在一条公海线索 L（owner_sales_id 为 NULL、stage=`已流失`）
- **WHEN** SALES 请求 `POST /api/leads/L/claim`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** L 的 `owner_sales_id` 仍为 NULL

#### Scenario: 认领成功触发 LEAD_CLAIM 系统日志

- **GIVEN** `system_log` 表当前行数为 N，存在公海线索 L
- **WHEN** SALES（账号 id=7）成功认领 L
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_CLAIM"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=7`、`lead_id=L.id`

### Requirement: Sales 主动退回公海

系统 SHALL 提供退回端点 `POST /api/leads/{id}/release`，仅 **SALES** 角色可调用，请求体 SHALL 携带 `releaseNote`（trim 后非空）。退回成功条件为：目标线索 `owner_sales_id = 调用者 id`（自己名下）且未结束。

退回成功 SHALL 把 `owner_sales_id` 置为 NULL（重新进入公海），stage SHALL 保持不变（**不**重置为 `未触达`）。`releaseNote` SHALL 仅写入 `LEAD_RELEASE` 系统日志的 `summary`，**不**在 lead 表新增列存储。

失败映射：
- 目标线索不存在，或 `owner_sales_id != 调用者 id`（非自己名下，含公海与他人名下）→ `NOT_FOUND`（与 lead-core 详情口吻一致，不泄漏存在性）。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- `releaseNote` 缺失或 trim 后为空 → `VALIDATION_ERROR`。

#### Scenario: Sales 退回自己名下线索成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`），调用者为 Sales（账号 id=7）
- **WHEN** 该 Sales 请求 `POST /api/leads/L/release`，body `{ "releaseNote": "客户暂无预算" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 NULL
- **AND** L 的 `stage` 仍为 `方案报价`（未重置）

#### Scenario: 退回缺失备注被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、未结束），调用者为 Sales（账号 id=7）
- **WHEN** 该 Sales 请求 `POST /api/leads/L/release`，body 不含 `releaseNote` 或 `releaseNote="   "`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 7

#### Scenario: 退回非自己名下线索返 404

- **GIVEN** 数据库存在线索 L（owner_sales_id=99，归他人）
- **WHEN** Sales（账号 id=7）请求 `POST /api/leads/L/release`，body 含合法 `releaseNote`
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`

#### Scenario: 退回已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`），调用者为 Sales（账号 id=7）
- **WHEN** 该 Sales 请求 `POST /api/leads/L/release`，body 含合法 `releaseNote`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 退回成功触发 LEAD_RELEASE 系统日志含备注

- **GIVEN** `system_log` 表当前行数为 N，线索 L 归 Sales（账号 id=7）
- **WHEN** 该 Sales 以 `releaseNote="客户暂无预算"` 成功退回 L
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_RELEASE"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=7`、`lead_id=L.id`
- **AND** 该行 `summary` 包含退回备注文本 `客户暂无预算`

### Requirement: Admin 分配公海线索

系统 SHALL 提供分配端点 `POST /api/leads/{id}/assign`，仅 **ADMIN** 角色可调用，请求体 SHALL 携带目标 `salesId`。分配成功条件为：目标线索存在、`owner_sales_id IS NULL`（在公海）、未结束，且 `salesId` 指向一个 `ENABLED` 状态的 SALES 账号。

分配成功 SHALL 把 `owner_sales_id` 置为 `salesId`，stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- 目标线索 `owner_sales_id IS NOT NULL`（不在公海，应改用转移）→ `VALIDATION_ERROR`。
- `salesId` 缺失、对应账号不存在、非 SALES 角色或处于 `DISABLED` 状态 → `VALIDATION_ERROR`，`message` 表达"目标销售不可用"语义。

#### Scenario: Admin 分配公海线索给启用 Sales 成功

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`）与 ENABLED 的 Sales（id=7）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 7 }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 `7`

#### Scenario: 分配给停用 Sales 被拒

- **GIVEN** 数据库存在公海线索 L 与 DISABLED 的 Sales（id=8）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 8 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 NULL

#### Scenario: 分配已有归属的线索被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7，已有归属、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 9 }`（9 为 ENABLED Sales）
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 7

#### Scenario: 分配已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`已流失`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/assign`，body `{ "salesId": 7 }`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 分配成功触发 LEAD_ASSIGN 系统日志含原 / 新归属

- **GIVEN** `system_log` 表当前行数为 N，公海线索 L，ADMIN 账号 id=1
- **WHEN** ADMIN 把 L 分配给 Sales（id=7）成功
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_ASSIGN"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=1`、`lead_id=L.id`
- **AND** 该行 `summary` 包含原归属（公海）与新归属（Sales id=7 的可读标识）信息

### Requirement: Admin 回收线索到公海

系统 SHALL 提供回收端点 `POST /api/leads/{id}/recall`，仅 **ADMIN** 角色可调用。回收成功条件为：目标线索存在、`owner_sales_id IS NOT NULL`（在某 Sales 名下）、未结束。

回收成功 SHALL 把 `owner_sales_id` 置为 NULL（移回公海），stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- 目标线索 `owner_sales_id IS NULL`（已在公海）→ `VALIDATION_ERROR`。

#### Scenario: Admin 回收私海线索成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/recall`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 NULL
- **AND** L 的 `stage` 仍为 `方案报价`

#### Scenario: 回收已在公海的线索被拒

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/recall`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`

#### Scenario: 回收已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/recall`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 回收成功触发 LEAD_RECALL 系统日志含原 / 新归属

- **GIVEN** `system_log` 表当前行数为 N，线索 L 归 Sales（id=7），ADMIN 账号 id=1
- **WHEN** ADMIN 成功回收 L
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_RECALL"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=1`、`lead_id=L.id`
- **AND** 该行 `summary` 包含原归属（Sales id=7）与新归属（公海）信息

### Requirement: Admin 转移线索归属

系统 SHALL 提供转移端点 `POST /api/leads/{id}/transfer`，仅 **ADMIN** 角色可调用，请求体 SHALL 携带目标 `salesId`。转移成功条件为：目标线索存在、`owner_sales_id IS NOT NULL`（在某 Sales 名下）、未结束，`salesId` 指向一个 `ENABLED` 状态的 SALES 账号，且 `salesId` 不等于当前 `owner_sales_id`。

转移成功 SHALL 把 `owner_sales_id` 置为 `salesId`，stage 保持不变。

失败映射：
- 目标线索不存在 → `NOT_FOUND`。
- 目标线索已结束 → `LEAD_ENDED_READONLY`。
- 目标线索 `owner_sales_id IS NULL`（在公海，应改用分配）→ `VALIDATION_ERROR`。
- `salesId` 缺失、对应账号不存在、非 SALES、`DISABLED` 状态，或等于当前归属 → `VALIDATION_ERROR`。

#### Scenario: Admin 转移线索给另一启用 Sales 成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`商务谈判`）与 ENABLED 的 Sales（id=9）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 9 }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** L 的 `owner_sales_id` 变为 `9`
- **AND** L 的 `stage` 仍为 `商务谈判`

#### Scenario: 转移给停用 Sales 被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、未结束）与 DISABLED 的 Sales（id=8）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 8 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** L 的 `owner_sales_id` 仍为 7

#### Scenario: 转移公海线索被拒

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 9 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`

#### Scenario: 转移给当前归属本人被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、未结束）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 7 }`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`

#### Scenario: 转移已结束线索返回 LEAD_ENDED_READONLY

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已流失`）
- **WHEN** ADMIN 请求 `POST /api/leads/L/transfer`，body `{ "salesId": 9 }`
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`

#### Scenario: 转移成功触发 LEAD_TRANSFER 系统日志含原 / 新归属

- **GIVEN** `system_log` 表当前行数为 N，线索 L 归 Sales（id=7），ADMIN 账号 id=1
- **WHEN** ADMIN 把 L 从 Sales 7 转移给 Sales 9 成功
- **THEN** `system_log` 表行数为 N+1
- **AND** 新增行的 `action="LEAD_TRANSFER"`、`target_type="LEAD"`、`target_id=L.id`、`operator_id=1`、`lead_id=L.id`
- **AND** 该行 `summary` 包含原归属（Sales id=7）与新归属（Sales id=9）的可读标识

## MODIFIED Requirements

### Requirement: 业务线索不在 lead-core 范围内的能力

本 capability SHALL **不**提供以下能力（这些属于后续 lead-stage / lead-closure change，将向本 capability 增量加入对应 requirement）：

- 阶段变更（PATCH /stage）
- 标记赢单 / 标记流失 + 合同记录生成
- 编辑 / 删除既有线索（MVP 整体不支持）

公海视图、认领、退回、分配、回收、转移已由 lead-ownership change 实现，**不**再列于本未覆盖清单。

#### Scenario: 当前阶段不存在阶段变更端点

- **WHEN** 任意已认证用户对 `PATCH /api/leads/{id}/stage` 发起请求
- **THEN** 系统**未**对外暴露此端点（响应**不**为 `SUCCESS`）

#### Scenario: 当前阶段不存在赢单 / 流失端点

- **WHEN** 任意已认证用户对 `POST /api/leads/{id}/win` 或 `POST /api/leads/{id}/lose` 发起请求
- **THEN** 系统**未**对外暴露此端点
