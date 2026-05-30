## ADDED Requirements

### Requirement: Admin 在用户管理中查看账号列表

前端 SHALL 仅向 `ADMIN` 提供"用户管理"入口，并在其中展示后端返回的全部账号（含启用与停用），每个账号呈现 `email`、`name`、`role`、`status` 与创建时间，并按创建时间稳定排列。前端 SHALL NOT 展示任何密码或密码哈希字段。非 `ADMIN` 用户 SHALL NOT 看到用户管理入口；其直接访问该区域时以后端 `FORBIDDEN` / `UNAUTHORIZED` 裁决回落。

#### Scenario: Admin 打开用户管理看到全部账号

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 用户打开"用户管理"
- **THEN** 前端展示后端返回的全部账号，含启用与停用账号
- **AND** 每个账号呈现 `email`、`name`、`role`、`status` 与创建时间
- **AND** 列表按创建时间稳定排列
- **AND** 不呈现任何密码或密码哈希字段

#### Scenario: 用户管理入口不对 Sales 呈现

- **GIVEN** 当前登录用户角色为 `SALES`
- **WHEN** 工作台导航渲染
- **THEN** "用户管理"入口不对该用户呈现

### Requirement: 系统日志入口在其查看能力交付前不呈现

在系统日志查看能力（含后端读端点）交付之前，前端 SHALL NOT 在工作台导航中呈现"系统日志"入口。该入口对包括 `ADMIN` 在内的所有角色均 SHALL NOT 呈现，以避免指向无数据来源的死链。占位路由可保留，但 SHALL 无任何导航入口指向。

#### Scenario: 系统日志入口不对 Admin 呈现

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 工作台导航渲染
- **THEN** "系统日志"入口不对该用户呈现

#### Scenario: 系统日志入口不对 Sales 呈现

- **GIVEN** 当前登录用户角色为 `SALES`
- **WHEN** 工作台导航渲染
- **THEN** "系统日志"入口不对该用户呈现

### Requirement: Admin 创建 Sales 账号

前端 SHALL 允许 `ADMIN` 在用户管理中创建新账号，采集 `email`、`name`、`password`，角色固定为 `SALES`（不提供创建其他角色的入口）。前端 SHALL 对邮箱格式与 `name`、`password` 必填做即时校验，非法时拦截不发请求。当后端以 `VALIDATION_ERROR` 拒绝（如邮箱已存在）时，前端 SHALL 展示后端 `message` 并保留表单，SHALL NOT 伪造创建成功。创建成功后该账号 SHALL 以 `status=ENABLED` 出现在账号列表。

#### Scenario: Admin 提交合法字段创建 Sales 成功

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 用户提交 `email`、`name`、`password` 均合法且邮箱未被占用的创建请求
- **THEN** 后端判定成功
- **AND** 新账号以 `role=SALES`、`status=ENABLED` 出现在账号列表

#### Scenario: 邮箱格式非法或必填项缺失时即时拦截

- **GIVEN** 当前登录用户角色为 `ADMIN` 且处于创建账号表单
- **WHEN** 用户提交的 `email` 格式非法，或 `name`、`password` 为空
- **THEN** 前端即时提示对应校验错误
- **AND** 前端不向后端发送创建请求

#### Scenario: 邮箱已存在时按后端裁决回落

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 用户提交的邮箱已存在且后端以 `VALIDATION_ERROR` 拒绝
- **THEN** 前端展示后端返回的"邮箱已存在"语义提示
- **AND** 前端保留表单，不将该账号计入列表

### Requirement: Admin 启用与停用账号

前端 SHALL 允许 `ADMIN` 对账号在启用与停用之间切换，切换成功后该账号在列表中的 `status` SHALL 同步更新。前端 SHALL NOT 为 `ADMIN` 自身账号呈现停用入口（防自锁）；若仍触发停用自身并被后端以 `VALIDATION_ERROR` 拒绝，前端 SHALL 展示"不可停用自己"语义并保持该账号状态不变。对同一目标重复执行同一状态切换 SHALL 不报错（与后端幂等一致）。

#### Scenario: Admin 停用启用中的 Sales

- **GIVEN** 当前登录用户角色为 `ADMIN` 且目标 Sales 账号 `status=ENABLED`
- **WHEN** 用户对该账号执行停用且后端判定成功
- **THEN** 该账号在列表中的 `status` 更新为 `DISABLED`

#### Scenario: Admin 启用已停用的 Sales

- **GIVEN** 当前登录用户角色为 `ADMIN` 且目标 Sales 账号 `status=DISABLED`
- **WHEN** 用户对该账号执行启用且后端判定成功
- **THEN** 该账号在列表中的 `status` 更新为 `ENABLED`

#### Scenario: 自身账号不呈现停用入口

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 账号列表渲染到该 Admin 自身所在行
- **THEN** 该行不呈现停用入口

#### Scenario: 停用自身被后端拒绝时回落

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 对自身账号的停用被后端以 `VALIDATION_ERROR` 拒绝
- **THEN** 前端展示"不可停用自己"语义提示
- **AND** 该账号状态保持不变

### Requirement: Admin 将公海线索分配给启用 Sales

前端 SHALL 仅向 `ADMIN` 在未结束的公海线索（无归属）详情中提供"分配"入口，目标候选 SHALL 限定为启用且角色为 `SALES` 的账号。分配成功后该线索 SHALL 归属选定 Sales。当后端以 `VALIDATION_ERROR` 拒绝（线索已有归属、目标未指定、目标不存在 / 非销售 / 已停用）时，前端 SHALL 展示后端 `message` 并据后端刷新该线索，SHALL NOT 伪造成功。分配入口 SHALL NOT 对非 `ADMIN` 呈现，也 SHALL NOT 对已结束线索呈现。

#### Scenario: Admin 分配公海线索给启用 Sales 成功

- **GIVEN** 当前登录用户角色为 `ADMIN` 且目标线索未结束、当前无归属
- **WHEN** 用户选择一个启用 Sales 执行分配且后端判定成功
- **THEN** 该线索归属更新为选定的 Sales

#### Scenario: 分配目标候选仅含启用 Sales

- **GIVEN** 当前登录用户角色为 `ADMIN` 且处于公海线索的分配入口
- **WHEN** 前端呈现可选目标
- **THEN** 候选仅包含启用且角色为 `SALES` 的账号
- **AND** 不包含停用账号与 `ADMIN` 账号

#### Scenario: 分配已有归属线索被后端拒绝时回落

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 对一条已有归属的线索执行分配并被后端以 `VALIDATION_ERROR` 拒绝
- **THEN** 前端展示后端返回的"请使用转移"语义提示
- **AND** 前端据后端刷新该线索，不伪造分配成功

#### Scenario: 分配入口不对已结束线索呈现

- **GIVEN** 当前登录用户角色为 `ADMIN` 且线索已赢单或已流失
- **WHEN** 线索详情渲染
- **THEN** 分配入口不对该线索呈现

### Requirement: Admin 将名下线索回收至公海

前端 SHALL 仅向 `ADMIN` 在未结束且有归属的线索详情中提供"回收至公海"入口。回收成功后该线索 SHALL 进入公海（无归属）。当后端以 `VALIDATION_ERROR` 拒绝（线索已在公海）时，前端 SHALL 展示后端 `message` 并据后端刷新该线索。回收入口 SHALL NOT 对非 `ADMIN` 呈现，也 SHALL NOT 对已结束线索呈现；已结束线索的回收以后端 `LEAD_ENDED_READONLY` 裁决回落。

#### Scenario: Admin 回收名下线索成功后进入公海

- **GIVEN** 当前登录用户角色为 `ADMIN` 且目标线索未结束、当前有归属
- **WHEN** 用户执行回收且后端判定成功
- **THEN** 该线索进入公海，归属被清空

#### Scenario: 回收已在公海线索被后端拒绝时回落

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 对一条已在公海的线索执行回收并被后端以 `VALIDATION_ERROR` 拒绝
- **THEN** 前端展示后端返回的"无需回收"语义提示
- **AND** 前端据后端刷新该线索，不伪造回收成功

#### Scenario: 回收入口不对已结束线索呈现

- **GIVEN** 当前登录用户角色为 `ADMIN` 且线索已赢单或已流失
- **WHEN** 线索详情渲染
- **THEN** 回收入口不对该线索呈现

### Requirement: Admin 将名下线索转移给另一启用 Sales

前端 SHALL 仅向 `ADMIN` 在未结束且有归属的线索详情中提供"转移"入口，目标候选 SHALL 限定为启用且角色为 `SALES` 的账号。转移成功后该线索 SHALL 归属选定的新 Sales。当后端以 `VALIDATION_ERROR` 拒绝（线索在公海应改用分配、目标与当前归属相同、目标不存在 / 非销售 / 已停用）时，前端 SHALL 展示后端 `message` 并据后端刷新该线索，SHALL NOT 伪造成功。转移入口 SHALL NOT 对非 `ADMIN` 呈现，也 SHALL NOT 对已结束线索呈现；已结束线索的转移以后端 `LEAD_ENDED_READONLY` 裁决回落。

#### Scenario: Admin 转移名下线索给另一启用 Sales 成功

- **GIVEN** 当前登录用户角色为 `ADMIN` 且目标线索未结束、当前归属某 Sales
- **WHEN** 用户选择另一个启用 Sales 执行转移且后端判定成功
- **THEN** 该线索归属更新为选定的新 Sales

#### Scenario: 转移目标与当前归属相同被后端拒绝时回落

- **GIVEN** 当前登录用户角色为 `ADMIN`
- **WHEN** 转移目标与线索当前归属相同并被后端以 `VALIDATION_ERROR` 拒绝
- **THEN** 前端展示后端返回的"目标销售与当前归属相同"语义提示
- **AND** 前端据后端刷新该线索，不伪造转移成功

#### Scenario: 转移入口不对已结束线索呈现

- **GIVEN** 当前登录用户角色为 `ADMIN` 且线索已赢单或已流失
- **WHEN** 线索详情渲染
- **THEN** 转移入口不对该线索呈现
