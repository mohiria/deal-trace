# permission Specification

## Purpose
TBD - created by archiving change permission. Update Purpose after archive.
## Requirements
### Requirement: 权威授权矩阵

系统 SHALL 以本矩阵为单一权威的 role×resource×action 授权契约。矩阵覆盖全部已注册业务端点（context-path `/api`）。本 capability 在聚合/矩阵级断言整面一致性；每个端点的细节行为（如 Sales 访问他人线索的 404 防泄漏语义、各动作的业务错误码）由资源所属 capability 拥有，本 capability MUST NOT 重述或取代既有 capability 已固化的权限断言。

矩阵分级（期望码：匿名未登录 / Sales / Admin）：

- **permitAll**：`GET /health`（匿名可达）、`POST /auth/login`（匿名进入凭证校验）。
- **authenticated（任意登录角色）**（匿名→401，Sales→可达，Admin→可达）：`GET /auth/me`、`POST /auth/change-password`、`POST /customers`、`GET /customers`、`GET /dashboard`、`POST /leads`、`GET /leads/duplicate-check`、`GET /leads/mine`、`GET /leads/pool`、`GET /leads/{id}`、`PATCH /leads/{id}/stage`、`POST /leads/{id}/win`、`POST /leads/{id}/lose`、`POST /leads/{id}/progress`、`GET /leads/{id}/progress`。
- **ADMIN 专属**（匿名→401，Sales→403，Admin→可达）：`GET /leads`、`POST /leads/{id}/assign`、`POST /leads/{id}/recall`、`POST /leads/{id}/transfer`、`POST /admin/accounts`、`GET /admin/accounts`、`PATCH /admin/accounts/{id}/status`。
- **SALES 专属**（匿名→401，Sales→可达，Admin→403）：`POST /leads/{id}/claim`、`POST /leads/{id}/release`。

#### Scenario: 矩阵作为权威授权契约被引用

- **WHEN** 需要判定某端点对某角色的访问期望
- **THEN** 以本矩阵为权威来源
- **AND** 端点细节行为（如他人线索 404、业务错误码）以资源所属 capability 的 spec 为准，本矩阵不与之冲突

### Requirement: 默认拒绝——未认证访问整面返回 401

除 permitAll 白名单（`GET /health`、`POST /auth/login`，以及框架内置端点如 `/error`）外，系统中**任意**已注册端点在未携带有效认证凭证时 SHALL 返回 HTTP `401`，`code="UNAUTHORIZED"`。该不变量 SHALL 以自动发现全部已注册端点的方式整面校验，使未来新增端点若漏配守卫能被回归捕获。

#### Scenario: 匿名访问任意非白名单端点返回 401

- **GIVEN** 系统已注册的全部业务端点
- **WHEN** 以匿名（无凭证）身份逐一访问其中不属于 permitAll 白名单的端点
- **THEN** 每个端点的响应均为 HTTP `401`，`code="UNAUTHORIZED"`

#### Scenario: 白名单端点匿名可达

- **WHEN** 匿名访问 `GET /health`
- **THEN** 响应 HTTP `200`
- **AND** 匿名 `POST /auth/login` 进入凭证校验流程（不被安全层提前 401 拦截）

#### Scenario: 新增端点漏配守卫被回归捕获

- **GIVEN** 默认拒绝扫描自动发现全部已注册端点
- **WHEN** 某个新端点未被加入 permitAll 白名单且未配置认证守卫
- **THEN** 该端点匿名访问不会返回 401，扫描断言失败，暴露漏配

### Requirement: 角色专属端点的越权访问返回 403

系统 SHALL 对角色专属端点拒绝错误角色的已认证访问，返回 HTTP `403`，`code="FORBIDDEN"`（区别于未认证的 401 与不泄漏存在性的 404）。

#### Scenario: Sales 访问 ADMIN 专属端点返回 403

- **GIVEN** 持有 SALES 令牌的已认证用户
- **WHEN** 访问任一 ADMIN 专属端点（`GET /leads`、`POST /leads/{id}/assign`、`POST /leads/{id}/recall`、`POST /leads/{id}/transfer`、`POST /admin/accounts`、`GET /admin/accounts`、`PATCH /admin/accounts/{id}/status`）
- **THEN** 响应 HTTP `403`，`code="FORBIDDEN"`

#### Scenario: Admin 访问 SALES 专属端点返回 403

- **GIVEN** 持有 ADMIN 令牌的已认证用户
- **WHEN** 访问任一 SALES 专属端点（`POST /leads/{id}/claim`、`POST /leads/{id}/release`）
- **THEN** 响应 HTTP `403`，`code="FORBIDDEN"`

### Requirement: 停用 Sales 的聚合权限剥夺

停用状态的 Sales 账号 SHALL 被一次性剥夺四类动作能力：不可登录、不可认领公海线索、不可被 Admin 分配新线索、不可被 Admin 转移线索。各动作的具体错误码与流程归资源所属 capability，本不变量在聚合高度断言「停用即全面失能」。

#### Scenario: 停用 Sales 不可登录

- **GIVEN** 一个状态为停用的 Sales 账号
- **WHEN** 以其凭证请求 `POST /auth/login`
- **THEN** 登录被拒绝，不签发有效访问令牌

#### Scenario: 停用 Sales 不可认领公海线索

- **GIVEN** 一个停用 Sales 与一条公海线索
- **WHEN** 尝试以该 Sales 身份认领该线索
- **THEN** 认领被拒绝，线索归属不变

#### Scenario: 停用 Sales 不可被分配或转移线索

- **GIVEN** 一个停用 Sales
- **WHEN** Admin 尝试将线索分配或转移给该 Sales
- **THEN** 操作被拒绝，线索归属不落到该停用 Sales 名下

### Requirement: 后端强校验原则

所有关键权限、状态流转与归属限制 SHALL 由后端最终校验；前端权限控制仅用于减少无效入口与提升体验，MUST NOT 作为权限的可信边界。本原则由三层守卫共同保证：安全层路径级规则、方法级角色校验、服务层归属校验。

#### Scenario: 越过前端直达后端的越权请求仍被拒绝

- **GIVEN** 一个绕过任何前端入口、直接构造的越权 HTTP 请求（错误角色或未认证）
- **WHEN** 该请求到达后端端点
- **THEN** 后端依据矩阵拒绝该请求（401 或 403），不因缺少前端控制而放行

