## ADDED Requirements

### Requirement: 进度跟踪的最小字段集与跟踪时间服务端生成

进度跟踪条目 SHALL 持久化以下字段：关联业务线索 `leadId`、跟踪方式 `method`、跟踪内容 `content`、跟踪人 `trackerId`、跟踪时间 `trackTime`。`trackTime` SHALL 由后端在持久化时刻基于服务端时钟生成；系统 SHALL **不**接受调用方（HTTP 客户端、业务代码、测试夹具）传入或覆盖 `trackTime`。`trackerId` SHALL 由后端从当前认证用户派生；系统 SHALL **不**接受调用方传入 `trackerId`。

#### Scenario: 新增进度持久化完整字段且时间由服务端生成

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`初步沟通`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/progress`，body `{ "method": "电话", "content": "已电话沟通需求，客户计划下周内部评审" }`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库新增**恰好一条**关联 L 的进度记录，`method` 为 `电话`、`content` 与请求一致、`tracker_id` 为 `7`
- **AND** 该记录 `track_time` 为服务端在持久化时刻的时钟值，不等于任何调用方传入值

#### Scenario: 调用方无法注入跟踪时间与跟踪人

- **GIVEN** 数据库存在线索 L（owner_sales_id=7），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 在 body 中额外携带 `trackTime` 与 `trackerId` 字段尝试新增进度
- **THEN** 持久化记录的 `track_time` 由后端生成、`tracker_id` 为认证用户 `7`，均**不**采用 body 传入值

### Requirement: 跟踪方式与跟踪内容校验

新增进度 SHALL 校验：`content` trim 后必填非空；`method` 必填且为合法枚举值（电话 / 微信 / 拜访 / 其他）。任一校验失败 SHALL 返回 HTTP `400`、`code="VALIDATION_ERROR"`，且**不**产生任何持久化副作用。

#### Scenario: 跟踪内容为空被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7），调用者为 SALES（账号 id=7），关联 L 的进度记录数为 N
- **WHEN** 该 SALES 请求新增进度，`content` 为空白字符串、`method="电话"`
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 关联 L 的进度记录数仍为 N，L 的 `last_tracked_at` 不变

#### Scenario: 跟踪方式缺失或非法被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求新增进度，`content` 合法但 `method` 缺失，或 `method="邮件"`（非枚举值）
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 不新增任何进度记录

### Requirement: 新增进度仅 SALES 且仅限自己名下线索

新增进度端点 `POST /api/leads/{id}/progress` SHALL 仅允许 **SALES** 对 `owner_sales_id` 等于自己的线索新增进度。调用者为 **ADMIN**、或为 SALES 但线索 `owner_sales_id` 不等于自己（含公海 `owner_sales_id IS NULL` 与他人名下）时，SHALL 返回 `404 NOT_FOUND` 且 `message` SHALL **不**暗示该线索是否存在或其归属。

#### Scenario: SALES 对自己名下线索新增进度成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`方案报价`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求新增进度，`method="拜访"`、`content` 合法
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`，数据库新增 1 条关联 L 的进度记录

#### Scenario: SALES 对他人名下线索新增进度返回 404

- **GIVEN** 数据库存在线索 L（owner_sales_id=9、stage=`初步沟通`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `POST /api/leads/L/progress` 携带合法 body
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`，`message` 不暗示 L 是否存在
- **AND** 不新增任何进度记录

#### Scenario: SALES 对公海线索新增进度返回 404

- **GIVEN** 数据库存在公海线索 L（owner_sales_id 为 NULL、stage=`未触达`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求新增进度携带合法 body
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`，不新增任何进度记录

#### Scenario: ADMIN 新增进度被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`初步沟通`），调用者为 ADMIN
- **WHEN** 该 ADMIN 请求 `POST /api/leads/L/progress` 携带合法 body
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`
- **AND** 不新增任何进度记录

### Requirement: 已结束线索不可新增进度

当目标线索 `stage` 为结束状态（`已赢单` / `已流失`）时，新增进度 SHALL 返回 HTTP `400`、`code="LEAD_ENDED_READONLY"`，且**不**产生任何持久化副作用。该结束只读校验 SHALL 先于入参校验：已结束线索即使携带非法 `method` / `content` 也 SHALL 返回 `LEAD_ENDED_READONLY` 而非 `VALIDATION_ERROR`。

#### Scenario: 对已赢单线索新增进度被拒

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`），调用者为 SALES（账号 id=7），关联 L 的进度记录数为 N
- **WHEN** 该 SALES 请求新增进度携带合法 body
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`
- **AND** 关联 L 的进度记录数仍为 N，L 的 `last_tracked_at` 不变

#### Scenario: 已结束线索结束只读校验先于入参校验

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已流失`），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求新增进度，`content` 为空且 `method` 非法
- **THEN** 响应 HTTP `400`，`code="LEAD_ENDED_READONLY"`（非 `VALIDATION_ERROR`）

### Requirement: 新增进度同步线索最后跟踪时间

新增进度成功时，系统 SHALL 在同一事务内原子地将该线索 `last_tracked_at` 更新为本次新增进度的 `trackTime`（同值同源——`last_tracked_at` 与新进度 `trackTime` 为同一次服务端时钟取值，而非各自独立取值）。

#### Scenario: 新增进度后线索最后跟踪时间等于该进度跟踪时间

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`初步沟通`、`last_tracked_at` 为某固定值 T0 或 NULL），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 成功新增一条进度
- **THEN** L 的 `last_tracked_at` 被更新为该新增进度记录的 `track_time`
- **AND** L 的 `last_tracked_at` 与该进度记录的 `track_time` 严格相等

### Requirement: 进度新增失败不残留且不更新最后跟踪时间

进度跟踪是用户主动发起的主操作；其持久化失败 SHALL 导致整个事务回滚并对外返回错误，系统 SHALL **不**吞异常、**不**返回伪成功。任何未成功的新增进度请求 SHALL **不**留下进度记录，且 SHALL **不**改动线索 `last_tracked_at`。

#### Scenario: 新增进度失败时无残留且最后跟踪时间不变

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、`last_tracked_at` 为 T0），调用者为 SALES（账号 id=7），关联 L 的进度记录数为 N
- **WHEN** 一次新增进度请求因校验失败而未成功
- **THEN** 关联 L 的进度记录数仍为 N
- **AND** L 的 `last_tracked_at` 仍为 T0

### Requirement: 进度跟踪一经持久化不可变

进度跟踪条目 SHALL 一经成功持久化即为只读。系统 SHALL **不**对外暴露任何修改或删除已存在进度条目的业务端点；ADMIN 与 SALES 均无修改 / 删除权限；如需更正只能通过追加新进度条目实现。

#### Scenario: 不存在修改或删除进度的业务端点

- **WHEN** 任意请求者（ADMIN / SALES / 匿名）试图通过 HTTP API 修改或删除任一已存在的进度条目
- **THEN** 系统**未**提供能够 UPDATE 或 DELETE 进度行的业务端点
- **AND** 已持久化的进度条目的全部字段在其生命周期内保持不变

### Requirement: 新增进度不生成系统日志

新增进度 SHALL **不**写入系统日志（PRD §11.7.9 产生系统日志的事件清单不含"新增进度跟踪"）。进度跟踪与系统日志为两个独立对象。

#### Scenario: 新增进度后系统日志行数不变

- **GIVEN** `system_log` 表当前行数为 M，数据库存在线索 L（owner_sales_id=7），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 成功新增一条进度
- **THEN** `system_log` 表行数仍为 M

### Requirement: 进度列表读取与可见性

系统 SHALL 提供进度列表端点 `GET /api/leads/{id}/progress`，返回该线索的全部进度条目，按 `trackTime` 倒序，不分页。可见性：**ADMIN** SHALL 可读任意线索的进度；**SALES** SHALL 仅可读 `owner_sales_id` 等于自己的线索进度，对其余线索（含公海与他人名下）SHALL 返回 `404 NOT_FOUND` 且 `message` 不暗示线索是否存在。线索结束与否 SHALL **不**改变可见性。本端点 SHALL **不**产生任何持久化副作用、**不**生成系统日志。每个列表项 SHALL 包含 `method` / `content` / `trackTime`，并内联跟踪人标识。

#### Scenario: ADMIN 读取任意线索进度按时间倒序

- **GIVEN** 数据库存在线索 L（owner_sales_id=9），关联 L 有 3 条进度（track_time 分别为 t1 < t2 < t3），调用者为 ADMIN
- **WHEN** 该 ADMIN 请求 `GET /api/leads/L/progress`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 返回 3 条进度，顺序为 t3、t2、t1（按 `track_time` 倒序）

#### Scenario: SALES 读取自己名下线索进度成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7），关联 L 有若干进度，调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `GET /api/leads/L/progress`
- **THEN** 响应 HTTP `200`，返回该线索全部进度按 `track_time` 倒序

#### Scenario: SALES 读取他人名下线索进度返回 404

- **GIVEN** 数据库存在线索 L（owner_sales_id=9），调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `GET /api/leads/L/progress`
- **THEN** 响应 HTTP `404`，`code="NOT_FOUND"`，`message` 不暗示 L 是否存在

#### Scenario: SALES 读取自己名下已结束线索进度成功

- **GIVEN** 数据库存在线索 L（owner_sales_id=7、stage=`已赢单`），关联 L 有进度，调用者为 SALES（账号 id=7）
- **WHEN** 该 SALES 请求 `GET /api/leads/L/progress`
- **THEN** 响应 HTTP `200`，返回该线索全部进度（结束状态不改变可读性）
