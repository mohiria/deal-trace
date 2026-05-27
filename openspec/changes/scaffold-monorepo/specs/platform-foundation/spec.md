## ADDED Requirements

### Requirement: 统一 API 响应信封

系统 SHALL 让所有 JSON 类型的 HTTP 响应使用统一的三段式信封结构：`code`（字符串错误码）、`message`（人类可读消息）、`data`（业务数据负载，可为 `null`）。无论请求成功或失败，响应体形状一致，便于客户端统一解析。

#### Scenario: 成功请求返回 SUCCESS 信封

- **WHEN** 客户端调用一个无业务异常、无参数校验失败的 API 端点
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体顶层包含 `code` 字段，其值为字符串 `"SUCCESS"`
- **AND** 响应体顶层包含 `message` 字段，其值为非空字符串
- **AND** 响应体顶层包含 `data` 字段，其值为该端点定义的业务负载（若该端点无返回数据则为 `null`）

#### Scenario: 参数校验失败返回 VALIDATION_ERROR 信封

- **WHEN** 客户端提交一个请求体或查询参数不满足后端校验规则的请求
- **THEN** 响应 HTTP 状态码为 `400`
- **AND** 响应体的 `code` 字段值为字符串 `"VALIDATION_ERROR"`
- **AND** 响应体的 `message` 字段值为描述具体字段错误的人类可读消息
- **AND** 响应体的 `data` 字段值为 `null`

#### Scenario: 未认证请求返回 UNAUTHORIZED 信封

- **WHEN** 客户端访问需要认证的端点但未提供有效的认证凭证（缺失或非法 `Authorization` 头）
- **THEN** 响应 HTTP 状态码为 `401`
- **AND** 响应体的 `code` 字段值为字符串 `"UNAUTHORIZED"`
- **AND** 响应体的 `data` 字段值为 `null`

### Requirement: 未处理异常的统一兜底

系统 SHALL 捕获所有未被业务代码显式处理的服务端异常，返回统一的内部错误信封；响应体中**不得**泄漏堆栈跟踪、内部类名、SQL 语句或其他实现细节。

#### Scenario: 未捕获异常返回 INTERNAL_ERROR

- **WHEN** 服务端代码在处理请求过程中抛出未被业务层捕获的运行时异常
- **THEN** 响应 HTTP 状态码为 `500`
- **AND** 响应体的 `code` 字段值为字符串 `"INTERNAL_ERROR"`
- **AND** 响应体的 `message` 字段值为通用的、与具体异常无关的人类可读消息（如 `"服务器内部错误"`）
- **AND** 响应体的 `data` 字段值为 `null`
- **AND** 响应体中**不含**任何 stack trace、异常类名、SQL 片段或文件路径
- **AND** 服务端日志中记录完整异常信息（含 stack trace），但日志不通过响应体外泄

### Requirement: 健康检查端点可达

系统 SHALL 提供一个公开的健康检查端点，用于运维探活与部署验证；该端点**不需要**认证，对所有调用者返回成功信封。

#### Scenario: 匿名调用健康检查端点

- **WHEN** 任意调用者（无 `Authorization` 头）向 `/api/health` 发起 `GET` 请求
- **THEN** 响应 HTTP 状态码为 `200`
- **AND** 响应体的 `code` 字段值为字符串 `"SUCCESS"`
- **AND** 系统不返回 `401 UNAUTHORIZED`
- **AND** 响应体的 `data` 字段值为非 `null` 的对象，至少包含一个表示服务存活的字段（如 `status: "UP"` 或等价语义）
