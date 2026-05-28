# auth-account Specification

## Purpose
TBD - created by archiving change auth-account. Update Purpose after archive.
## Requirements
### Requirement: 邮箱与密码登录

系统 SHALL 提供基于邮箱与密码的登录接口，登录成功后签发用于后续请求鉴权的访问令牌；登录失败时统一返回 `UNAUTHORIZED` 信封，错误细节通过 `message` 区分，**不**通过 `code` 字段或 HTTP 状态泄漏账号是否存在、是否被停用。

#### Scenario: 登录成功签发访问令牌

- **WHEN** 客户端以正确的邮箱与密码组合调用登录端点
- **AND** 对应账号状态为 `ENABLED`
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体 `code` 字段值为 `"SUCCESS"`
- **AND** 响应体 `data` 包含一个非空字符串字段 `token`（访问令牌）
- **AND** 响应体 `data` 包含 `email`、`name`、`role` 三个字段，与该账号一致
- **AND** 响应体**不**包含密码、密码哈希或任何敏感字段

#### Scenario: 邮箱不存在时拒绝登录

- **WHEN** 客户端以不存在的邮箱调用登录端点
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体 `code` 字段值为 `"UNAUTHORIZED"`
- **AND** 响应体 `data` 字段值为 `null`
- **AND** 响应体 `message` 不暴露"该邮箱不存在"或等价语义（防止账号枚举）

#### Scenario: 密码不匹配时拒绝登录

- **WHEN** 客户端以存在但密码错误的邮箱密码组合调用登录端点
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体 `code` 字段值为 `"UNAUTHORIZED"`
- **AND** 响应体 `message` 与"邮箱不存在"场景保持一致或语义不可区分

#### Scenario: 停用账号尝试登录被拒

- **WHEN** 客户端以正确邮箱与密码登录
- **AND** 对应账号状态为 `DISABLED`
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体 `code` 字段值为 `"UNAUTHORIZED"`
- **AND** 响应体 `message` 表达"账号已停用"语义
- **AND** 响应体**不**返回 `token` 字段

### Requirement: 访问令牌携带身份用于后续请求

系统 SHALL 以访问令牌承载请求者身份；后端 SHALL 在每个需要认证的请求处理时校验令牌有效性，并根据令牌所属账号的**实时状态**（而非签发时刻状态）决定是否放行。

#### Scenario: 有效令牌请求受保护端点返回业务结果

- **WHEN** 客户端持有由系统签发、未过期、对应账号状态为 `ENABLED` 的令牌
- **AND** 通过 `Authorization: Bearer <token>` 携带访问任一需要认证的端点
- **THEN** 后端识别请求者身份为该账号
- **AND** 端点按其业务逻辑返回结果（不因鉴权而被拦截）

#### Scenario: 缺失令牌访问受保护端点返回 401

- **WHEN** 客户端不携带 `Authorization` 头访问任一需要认证的端点
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体 `code` 字段值为 `"UNAUTHORIZED"`

#### Scenario: 非法或过期令牌访问受保护端点返回 401

- **WHEN** 客户端携带格式非法、签名不匹配或已过期的令牌访问受保护端点
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体 `code` 字段值为 `"UNAUTHORIZED"`
- **AND** 响应体**不**泄漏令牌解析细节（如"签名错误"/"已过期"分别提示）

#### Scenario: 停用账号的有效令牌在下次请求被拒

- **GIVEN** 某 Sales 账号已成功登录并持有未过期访问令牌
- **WHEN** Admin 将该账号停用
- **AND** 该 Sales 的客户端使用同一令牌再次请求任一受保护端点
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体 `code` 字段值为 `"UNAUTHORIZED"`
- **AND** 响应体 `message` 表达"账号已停用"语义

### Requirement: 当前用户查询自身信息

系统 SHALL 提供端点让已认证用户查询自身的脱敏信息，用于前端工作台显示登录态；响应体**不**包含密码哈希、令牌或其他敏感字段。

#### Scenario: 已认证用户查询自身信息

- **WHEN** 客户端携带有效令牌请求"当前用户信息"端点
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体 `code` 字段值为 `"SUCCESS"`
- **AND** 响应体 `data` 包含 `id`、`email`、`name`、`role`、`status` 五个字段
- **AND** 响应体**不**包含 `passwordHash`、`token` 或类似敏感字段

### Requirement: 当前用户修改自身密码

系统 SHALL 允许已认证用户通过提供旧密码与新密码修改自身密码；旧密码校验失败时拒绝修改并返回 `UNAUTHORIZED`。修改成功后，旧密码立即失效，但**当前会话**令牌不被强制吊销（用户可凭旧令牌继续访问至其自然过期）。

#### Scenario: 旧密码正确且新密码非空时修改成功

- **WHEN** 客户端携带有效令牌，提交 `{oldPassword, newPassword}` 其中 `oldPassword` 与当前账号密码哈希一致、`newPassword` 为非空字符串
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体 `code` 字段值为 `"SUCCESS"`
- **AND** 账号密码哈希被更新为基于 `newPassword` 计算的新哈希
- **AND** 此后使用旧密码再次登录将被拒绝
- **AND** 此后使用新密码可成功登录

#### Scenario: 旧密码错误时拒绝修改

- **WHEN** 客户端携带有效令牌，提交的 `oldPassword` 与当前账号密码不匹配
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体 `code` 字段值为 `"UNAUTHORIZED"`
- **AND** 响应体 `message` 表达"原密码错误"语义
- **AND** 账号密码哈希**保持不变**

#### Scenario: 新密码为空时拒绝修改

- **WHEN** 客户端携带有效令牌，提交的 `newPassword` 为空字符串或仅含空白字符
- **THEN** 响应 HTTP 状态码为 `400`
- **AND** 响应体 `code` 字段值为 `"VALIDATION_ERROR"`
- **AND** 账号密码哈希**保持不变**

### Requirement: Admin 创建 Sales 账号

系统 SHALL 仅允许 `ADMIN` 角色创建新账号；新账号角色 SHALL 被限定为 `SALES`（MVP 阶段不支持创建多个 Admin）；新账号邮箱在全系统范围内必须唯一；创建成功后账号初始状态为 `ENABLED`。

#### Scenario: Admin 提交合法字段创建 Sales 成功

- **WHEN** 持有 ADMIN 角色令牌的客户端提交 `{email, name, password, role=SALES}` 调用创建账号端点
- **AND** 该邮箱不存在于系统中
- **AND** `email` 符合标准邮箱格式、`name` 非空、`password` 非空、`role=SALES`
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体 `code` 字段值为 `"SUCCESS"`
- **AND** 响应体 `data` 包含新建账号的 `id`、`email`、`name`、`role=SALES`、`status=ENABLED`
- **AND** 响应体**不**回传密码或密码哈希

#### Scenario: 非 Admin 调用创建账号端点被拒

- **WHEN** 持有 SALES 角色令牌或未认证的客户端调用创建账号端点
- **THEN** 响应 HTTP 状态码为 `403`（若已认证为非 ADMIN）或 `401`（若未认证）
- **AND** 响应体 `code` 字段值为 `"FORBIDDEN"` 或 `"UNAUTHORIZED"`
- **AND** 账号**未被创建**

#### Scenario: 邮箱重复时拒绝创建

- **WHEN** Admin 提交的 `email` 已存在于系统中（不区分对方账号状态）
- **THEN** 响应 HTTP 状态码为 `400`
- **AND** 响应体 `code` 字段值为 `"VALIDATION_ERROR"`
- **AND** 响应体 `message` 表达"邮箱已存在"语义
- **AND** 账号**未被创建**

#### Scenario: role 非 SALES 时拒绝创建

- **WHEN** Admin 提交的 `role` 字段值不是 `SALES`（例如 `ADMIN` 或非法值）
- **THEN** 响应 HTTP 状态码为 `400`
- **AND** 响应体 `code` 字段值为 `"VALIDATION_ERROR"`
- **AND** 账号**未被创建**

### Requirement: Admin 查询账号列表

系统 SHALL 仅允许 `ADMIN` 角色查询全部账号；列表 SHALL 返回所有账号（含启用与停用），按创建时间排序；响应体**不**包含密码哈希。

#### Scenario: Admin 查询账号列表返回全部账号

- **WHEN** 持有 ADMIN 角色令牌的客户端调用账号列表端点
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体 `data` 为一个数组
- **AND** 每个数组元素包含 `id`、`email`、`name`、`role`、`status`、`createdAt` 字段
- **AND** 数组元素**不**包含 `passwordHash` 字段
- **AND** 数组顺序按 `createdAt` 升序或降序中的一种稳定排列

#### Scenario: 非 Admin 调用账号列表端点被拒

- **WHEN** 持有 SALES 角色令牌的客户端调用账号列表端点
- **THEN** 响应 HTTP 状态码为 `403`
- **AND** 响应体 `code` 字段值为 `"FORBIDDEN"`

### Requirement: Admin 启用与停用账号

系统 SHALL 仅允许 `ADMIN` 角色修改其他账号的状态；停用与启用 SHALL 各自幂等（对已停用账号再次停用或已启用账号再次启用，不视为错误）；状态变更 SHALL 触发系统日志记录；Admin **不可**停用自己的账号（防止自锁）。

#### Scenario: Admin 停用启用中的 Sales

- **WHEN** 持有 ADMIN 角色令牌的客户端对一个 `status=ENABLED` 的 Sales 账号调用停用端点
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体 `code` 字段值为 `"SUCCESS"`
- **AND** 该账号 `status` 变更为 `DISABLED`
- **AND** 系统记录一条停用操作的系统日志（含操作者 Admin、目标账号、时间戳）

#### Scenario: Admin 启用已停用的 Sales

- **WHEN** 持有 ADMIN 角色令牌的客户端对一个 `status=DISABLED` 的 Sales 账号调用启用端点
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 该账号 `status` 变更为 `ENABLED`
- **AND** 系统记录一条启用操作的系统日志

#### Scenario: Admin 重复停用同一账号保持幂等

- **WHEN** 持有 ADMIN 角色令牌的客户端对一个 `status=DISABLED` 的账号再次调用停用端点
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 该账号 `status` 保持 `DISABLED`
- **AND** 不强制要求重复生成系统日志（可记录也可省略）

#### Scenario: Admin 试图停用自己被拒

- **WHEN** Admin 调用停用端点的目标账号 ID 与令牌所属账号 ID 相同
- **THEN** 响应 HTTP 状态码为 `400`
- **AND** 响应体 `code` 字段值为 `"VALIDATION_ERROR"`
- **AND** 响应体 `message` 表达"不可停用自己"语义
- **AND** 该 Admin 账号 `status` 保持不变

#### Scenario: 非 Admin 调用状态变更端点被拒

- **WHEN** 持有 SALES 角色令牌的客户端调用任一账号的启用或停用端点
- **THEN** 响应 HTTP 状态码为 `403`
- **AND** 响应体 `code` 字段值为 `"FORBIDDEN"`
- **AND** 目标账号状态**保持不变**

### Requirement: 密码以单向哈希存储

系统 SHALL 以单向密码哈希方式存储所有账号密码；任何 API 响应、日志输出、错误信息 SHALL **不**包含原始密码或密码哈希；密码校验 SHALL 通过对提交密码计算哈希并与存储哈希比对完成，**不**通过明文比对。

#### Scenario: 创建账号时密码被哈希存储

- **WHEN** Admin 创建账号或系统注入初始 Admin
- **THEN** 数据库中存储的密码字段为单向哈希值
- **AND** 哈希值与原始密码字符串不相等

#### Scenario: 修改密码时新密码被哈希存储

- **WHEN** 当前用户成功修改自身密码
- **THEN** 数据库中存储的密码哈希为基于新密码计算的新哈希
- **AND** 新哈希与新密码字符串不相等

### Requirement: 初始 Admin 由部署配置注入

系统 SHALL 在启动时根据部署配置（邮箱 + 初始密码）幂等创建一个初始 Admin 账号；若配置邮箱对应账号已存在，则跳过创建（**不**重置其密码、姓名或状态）；初始 Admin 角色固定为 `ADMIN`、状态固定为 `ENABLED`。

#### Scenario: 首次启动注入初始 Admin

- **GIVEN** 部署配置提供 `admin-email` 与 `admin-password`
- **AND** 数据库中**不**存在该邮箱对应的账号
- **WHEN** 系统启动完成
- **THEN** 数据库中新增一条账号记录，`email = admin-email`、`role = ADMIN`、`status = ENABLED`
- **AND** 该账号的密码哈希基于 `admin-password` 计算

#### Scenario: 启动时账号已存在则跳过

- **GIVEN** 部署配置提供 `admin-email` 与 `admin-password`
- **AND** 数据库中已存在 `email = admin-email` 的账号
- **WHEN** 系统启动完成
- **THEN** 数据库中该邮箱对应账号的所有字段**保持不变**
- **AND** 数据库中**不**新增其他 ADMIN 账号

### Requirement: 邮箱全局唯一

系统 SHALL 保证 `email` 字段在所有账号（无论状态、无论角色）中全局唯一；唯一性 SHALL 由数据库约束兜底，**不**仅依赖应用层校验。

#### Scenario: 数据库层面拒绝重复邮箱写入

- **WHEN** 任何代码路径试图向账号表插入一条 `email` 与既有记录冲突的新行
- **THEN** 数据库返回唯一约束冲突错误
- **AND** 应用层将其转换为业务可读的 `VALIDATION_ERROR` 响应（创建场景）或 `INTERNAL_ERROR`（非预期路径）

