## Why

PRD §7.6 / §11.6「客户其他业务线索提示」是 MVP in-scope 功能，但全仓尚无任何实现（源码零命中）。现有 `LeadDuplicateService` 只处理「同一客户 + 同业务类型」三元组的查重阻断，无法回答「同一客户在**其他业务类型**下是否已有线索」这一协同问题——销售与管理员在创建、分配、浏览公海时缺少跨业务线的协同视野。

## What Changes

- 新增「同一客户下不同业务类型」线索的提示能力，按调用者角色裁剪可见内容，**不阻断**新建 / 认领 / 分配（§7.6.1-2 / §11.6.4）。
- **Admin 创建 / 分配线索时**：可看到该客户其他业务线索的业务类型、归属销售、当前阶段（明文，§7.6.3 / §11.6.1）。
- **Sales 浏览公海线索时**：若该客户存在其他业务线索，仅得到布尔提示「该客户已有其他业务线索」，不含任何详情（§7.6.4 / §11.6.2）。
- **Sales 查看自己名下线索时**：可看到同一客户下**自己有权限访问**的其他业务线索（§7.6.6）。
- 权限红线：Sales 不可借此提示获取他人私海线索的归属人 / 联系方式 / 进度跟踪 / 具体阶段（§7.6.5 / §11.6.3）。
- 前端在新建/分配弹窗、公海列表、线索详情三处呈现对应提示。

不改现有 `LeadDuplicateService` 查重三态逻辑，不在 customer 主体上新增联系信息，不引入复杂商机协作（PRD §10 out-of-scope）。

## Capabilities

### New Capabilities
- `customer-other-leads-hint`: 同一客户下不同业务类型线索的协同提示——按角色（ADMIN 全量详情 / SALES 仅布尔或仅自有可见线索）裁剪返回内容；纯读侧、非阻断。

### Modified Capabilities
<!-- 无既有 capability 的需求被改写：现有 lead 查重/归属/公海行为契约保持不变，本能力为附加的读侧提示。承载提示信号的端点/响应字段属实现细节，记入 design.md。 -->

## Impact

- **后端**：新增读侧查询（按 `customerId` 排除当前业务类型、排除已流失，查同客户其他业务类型「进行中+已赢单」线索），按角色裁剪 DTO；复用 `LeadMapper`、`AccountMapper`（归属销售名）与 `LeadOwnershipService` 的 `Role.ADMIN` 分支模式。承载形式（专用端点 vs 扩展公海响应）在 design.md 决策。
- **前端**：`CreateLeadModal.vue`（Admin 新建/分配区块）、公海列表视图（Sales 布尔提示）、`LeadDetailPanel.vue`（自有线索同客户其他线索）。
- **权限**：复用既有 ADMIN / SALES 角色裁决与「不泄漏存在性」约定（参考 `LeadOwnershipService.release` 的 NOT_FOUND 处理）。
- **数据**：无新表 / 无迁移；纯查询既有 `lead` / `account` 表。
- **不影响**：`LeadDuplicateService` 查重逻辑、customer schema、闭单只读规则。
