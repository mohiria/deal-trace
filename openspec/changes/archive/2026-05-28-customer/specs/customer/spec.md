## ADDED Requirements

### Requirement: 客户字段构成

系统 SHALL 在客户主体上**仅**维护 3 个业务字段：客户名称 `name`、统一社会信用代码 `usci`、创建时间 `created_at`（服务端持久化时刻生成）；并附带一个由数据库生成的主键 `id`。客户主体 SHALL **不**维护联系人、联系电话、线索来源、归属销售或任何 lead 相关字段（这些字段归业务线索 capability 所有）。

#### Scenario: 创建成功的客户视图字段集

- **WHEN** 任一已认证用户成功创建一个客户
- **THEN** 响应 `data` 字段包含且**仅**包含 `id`、`name`、`usci`、`createdAt` 四个字段
- **AND** 响应**不**包含 `contactName`、`contactPhone`、`leadSource`、`ownerSalesId` 或任何 lead 相关字段

### Requirement: 客户名称必填且 trim 后查重

客户名称 `name` SHALL 必填，且在保存与查重前 SHALL 去除首尾空白字符；trim 后为空字符串 SHALL 视为未填，按必填校验失败返回 `VALIDATION_ERROR`。trim 后的客户名称在全表范围内 SHALL 唯一：当用户提交的 name（trim 后）与既有任一客户的 name 完全相等时，系统 SHALL 拒绝创建并返回 `DUPLICATE_CUSTOMER`。MVP 阶段**不**支持任何形式的别名 / 简称 / 模糊合并 / 大小写以外的等价判断。

#### Scenario: name 字段缺失或全空白拒绝创建

- **WHEN** 已认证用户提交 `name=""` 或 `name="   "`（全空白）或缺失 `name` 字段的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`，`message` 表达"客户名称不可为空"语义
- **AND** 数据库**未**新增客户行

#### Scenario: trim 后客户名称重复拒绝创建

- **GIVEN** 数据库已存在一个客户，其 name 等于 `"中国建筑设计研究院"`
- **WHEN** 已认证用户提交 `name="  中国建筑设计研究院  "`（首尾带空格）的创建请求
- **THEN** 响应 HTTP `400`，`code="DUPLICATE_CUSTOMER"`
- **AND** `message` 表达"客户名称已存在"语义
- **AND** 数据库**未**新增客户行

#### Scenario: 不同公司名不视为重复

- **GIVEN** 数据库已存在 name 为 `"中国建筑设计研究院"` 的客户
- **WHEN** 已认证用户提交 `name="中建院"` 的创建请求（其他字段合法）
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库新增一行 name 为 `"中建院"` 的客户

### Requirement: 统一社会信用代码归一化先行再校验

统一社会信用代码 `usci` SHALL 必填。系统 SHALL 在保存与查重前对 `usci` 按以下顺序归一化：(1) 去除首尾空白字符，(2) 将英文字母统一转为大写。归一化**完成后**的字符串 SHALL 通过 18 位 GB 32100-2015 统一社会信用代码标准格式校验（结构 + 字符集 + 第 18 位校验位）；归一化前直接校验 SHALL **不**被接受。校验失败时 SHALL 返回 `VALIDATION_ERROR`，`message` 表达校验失败语义但**不**暴露具体字符位置（防止枚举猜测）。

#### Scenario: 带空白与小写字母的合法 USCI 经归一化后通过校验

- **WHEN** 已认证用户提交合法 USCI（含大小写混合 + 首尾空格，归一化后是合法 18 位含正确校验位）的创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库中持久化的 `usci` 字段为归一化后的结果（去空白 + 全大写）
- **AND** 响应 `data.usci` 字段为归一化后的结果

#### Scenario: USCI 长度不是 18 位拒绝创建

- **WHEN** 已认证用户提交 USCI 归一化后长度 ≠ 18 的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 数据库**未**新增客户行

#### Scenario: USCI 校验位错误拒绝创建

- **WHEN** 已认证用户提交 USCI 归一化后长度为 18、结构合法、但第 18 位校验位与算法计算结果不符的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** 数据库**未**新增客户行

#### Scenario: USCI 字段缺失或全空白拒绝创建

- **WHEN** 已认证用户提交 `usci=""` 或 `usci="   "` 或缺失 `usci` 字段的创建请求
- **THEN** 响应 HTTP `400`，`code="VALIDATION_ERROR"`
- **AND** `message` 表达"USCI 不可为空"语义
- **AND** 数据库**未**新增客户行

### Requirement: 统一社会信用代码全局唯一

归一化后的统一社会信用代码 SHALL 在客户表中全局唯一。当用户提交的 USCI（归一化后）与既有任一客户的 USCI 完全相等时，系统 SHALL 拒绝创建并返回 `DUPLICATE_CUSTOMER`。唯一性 SHALL 由数据库约束兜底（不仅依赖应用层 check-then-insert）；并发写入同一 USCI 时，至多一个 INSERT 成功，其余 SHALL 收到 `DUPLICATE_CUSTOMER` 业务错误而非内部错误。

#### Scenario: 重复 USCI 拒绝创建

- **GIVEN** 数据库已存在客户 A，其归一化后的 USCI 为 `"91110000123456789X"`
- **WHEN** 已认证用户提交 `usci="91110000123456789x"`（小写 x，归一化后等于 A 的 USCI）的创建请求
- **THEN** 响应 HTTP `400`，`code="DUPLICATE_CUSTOMER"`
- **AND** `message` 表达"USCI 已存在"语义
- **AND** 数据库客户行数**未**变化

#### Scenario: 并发提交相同 USCI 时只允许一个成功

- **WHEN** 两个已认证用户在毫秒级时间窗内同时提交相同归一化 USCI 的创建请求
- **THEN** 恰有一个请求响应 `code="SUCCESS"` 且数据库新增一行
- **AND** 另一个请求响应 HTTP `400`，`code="DUPLICATE_CUSTOMER"`
- **AND** 失败的请求**不**返回 `INTERNAL_ERROR` 或 SQL 异常细节

### Requirement: Admin 与 Sales 均可创建客户

任一处于 `ENABLED` 状态的账号（无论 `ADMIN` 还是 `SALES` 角色）SHALL 可调用创建客户端点。停用账号已在 JWT 过滤层被拦截，**不**到达本端点。系统 SHALL **不**在本端点上做角色级权限区分（无 `@PreAuthorize("hasRole(...)")`）。

#### Scenario: Sales 创建客户成功

- **WHEN** 持有 SALES 角色令牌的客户端提交合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库新增一行

#### Scenario: Admin 创建客户成功

- **WHEN** 持有 ADMIN 角色令牌的客户端提交合法创建请求
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** 数据库新增一行

#### Scenario: 匿名访问创建端点被拒

- **WHEN** 未携带 `Authorization` 头的客户端调用创建客户端点
- **THEN** 响应 HTTP `401`，`code="UNAUTHORIZED"`
- **AND** 数据库**未**新增行

### Requirement: 客户搜索 / 列表统一端点

系统 SHALL 提供一个统一的客户查询端点，对所有已认证用户开放（不区分角色）；同时支持「无关键词的列表」与「按关键词搜索」两种使用形态：

- 当请求**不**携带或携带空白 `keyword` 时，端点 SHALL 返回全部客户中按 `created_at` 倒序的前 50 行；
- 当请求携带非空白 `keyword` 时，端点 SHALL 返回 `name` 包含该 keyword（子串匹配）**或** `usci` 包含该 keyword（子串匹配，匹配前先按客户表的 USCI 存储形态进行）的客户中，按 `created_at` 倒序的前 50 行。

返回上限固定为 50 行（不引入 `page` / `size` 参数）；超出 50 行的剩余记录 SHALL **不**被返回，前端可据此引导用户细化关键词。每个返回元素包含 `id` / `name` / `usci` / `createdAt` 四字段，与创建响应同形。

#### Scenario: 无关键词请求返回最近 50 行

- **GIVEN** 数据库中有 60 条客户记录
- **WHEN** 已认证用户请求 `GET /api/customers`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data` 为一个数组，长度为 50
- **AND** 数组按 `createdAt` 倒序排列
- **AND** 数组不包含 createdAt 最早的 10 条记录

#### Scenario: 关键词命中 name 子串

- **GIVEN** 数据库中存在 name 为 `"中国建筑设计研究院"` 的客户 X，以及不含 `"建筑"` 二字的若干客户
- **WHEN** 已认证用户请求 `GET /api/customers?keyword=建筑`
- **THEN** 响应 `data` 数组包含客户 X
- **AND** `data` 数组中每个元素的 `name` 或 `usci` 至少有一个包含字符串 `"建筑"`

#### Scenario: 关键词命中 USCI 子串

- **GIVEN** 数据库中存在 USCI 为 `"91110000123456789X"` 的客户 Y
- **WHEN** 已认证用户请求 `GET /api/customers?keyword=91110000`
- **THEN** 响应 `data` 数组包含客户 Y

#### Scenario: 关键词无命中返回空数组

- **WHEN** 已认证用户请求 `GET /api/customers?keyword=不存在的关键词xyz999`
- **THEN** 响应 HTTP `200`，`code="SUCCESS"`
- **AND** `data` 为空数组 `[]`
- **AND** 响应**不**为 `null` 或 HTTP `404`

#### Scenario: 匿名访问搜索端点被拒

- **WHEN** 未携带 `Authorization` 头的客户端调用客户搜索端点
- **THEN** 响应 HTTP `401`，`code="UNAUTHORIZED"`

### Requirement: 客户主体不可编辑、不可删除

MVP 阶段系统 SHALL **不**提供修改或删除已存在客户主体的端点。已持久化的客户行字段（id / name / usci / created_at）在其生命周期内 SHALL 保持不变。如未来业务出现客户改名 / 注销等需求，应通过新的 OpenSpec change 显式扩展，**不**得在本 capability 中绕道实现。

#### Scenario: 不存在 PUT / PATCH / DELETE customer 端点

- **WHEN** 任意已认证用户以 `PUT` / `PATCH` / `DELETE` 方法访问任何 `/api/customers/*` 路径
- **THEN** 系统**未**对外暴露对应端点
- **AND** 已存在的客户行字段保持不变

### Requirement: 创建客户不生成系统日志

创建客户的成功路径 SHALL **不**触发 `SystemLogPort.record(...)` 调用，也 SHALL **不**写入 `system_log` 表任何行。PRD §7.8 系统日志触发事件清单（创建/认领/退回/分配/回收/转移/阶段变更/标记赢单/标记流失/停用账号）**不**包含"创建客户"，因此本 capability 不在系统日志面留任何条目。

#### Scenario: 成功创建客户后 system_log 表无新增行

- **GIVEN** `system_log` 表在创建客户前的行数为 N
- **WHEN** 已认证用户成功创建一个客户
- **THEN** `system_log` 表的行数仍为 N
- **AND** `SystemLogPort.record(...)` **未**被业务路径调用
