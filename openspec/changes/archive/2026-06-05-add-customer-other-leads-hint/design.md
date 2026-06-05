## Context

PRD §7.6 / §11.6 要求在三个场景给出「同客户、不同业务类型」线索的协同提示，且按角色裁剪可见内容。现状：后端 `LeadDuplicateService` 仅按三元组 `(business_year, customer_id, business_type)` 查重，无跨业务类型查询；公海列表 `PoolLeadView` 无「其他业务线索」信号；前端 `CreateLeadModal.vue` / 公海列表 / `LeadDetailPanel.vue` 均无对应区块。

约束：
- spec 权威定义见 `specs/customer-other-leads-hint/spec.md`；冲突时以 PRD §7.6 / §11.6 为准。
- 角色裁剪须复用既有约定：`Role.ADMIN` 分支模式见 `LeadOwnershipService.listPool`；「不泄漏存在性」见 `LeadOwnershipService.release`（他人/公海统一 NOT_FOUND）。
- 纯读侧，无新表、无 Flyway 迁移。
- 不改 `LeadDuplicateService`。

## Goals / Non-Goals

**Goals:**
- 后端按角色返回「同客户其他业务类型、非已流失」线索：ADMIN 得业务类型+归属销售+阶段；SALES 仅得本人名下的其他线索（同样的轻量字段）。
- 公海列表为每条线索附「该客户是否有其他业务线索」布尔信号，供 SALES 浏览时提示。
- 前端三处呈现：Admin 新建/分配弹窗（详情列表）、公海列表（布尔提示）、自有线索详情（本人名下其他线索）。

**Non-Goals:**
- 提示载荷不含联系方式 / 进度跟踪内容（§7.6.3 只列举类型+归属+阶段）；这是摘要范围，**非对 Admin 的访问限制**——Admin 仍可经线索详情看全部内容（见 D2）。
- 不提示已流失的其他业务线索（D3）。
- 不阻断任何写操作。
- 不改查重三态、不动 customer schema。
- 不做跨业务线的合并/聚合分析（PRD §10 复杂商机协作 out-of-scope）。

## Decisions

### D1：两种承载形式——专用详情端点 + 公海列表布尔字段
- **详情视图（Admin 新建/分配 + Sales 自有线索详情）**：新增只读端点 `GET /leads/customer-other-leads?customerId={id}&excludeBusinessType={type}`（type 选填；详情页场景传当前线索的 business_type 以排除自身业务线）。返回**按角色裁剪**的轻量列表项 `{ businessType, ownerSalesName, stage }`。
  - ADMIN：返回该客户所有其他业务类型线索。
  - SALES：仅返回 `owner_sales_id == 调用者` 的其他业务类型线索（§7.6.6 / §7.6.5）。
- **公海布尔（Sales 浏览公海）**：扩展 `PoolLeadView`，新增布尔 `customerHasOtherLeads`。在 `LeadOwnershipService.listPool` 现有批量装载里一并计算（按 customerId 聚合判断是否存在不同业务类型线索），避免每行额外查询。
  - 备选：为公海每行单独调用详情端点 → 否决（N+1，且会向 SALES 暴露过多信息，违反 §7.6.4 仅布尔）。

### D2：提示载荷 = §7.6.3 列举的轻量摘要；角色差异在「返回哪些行」，非「Admin 能否看电话」
新增 `LeadOtherLeadView { businessType, ownerSalesName, stage }`——这是**提示卡片的内容范围**，由 PRD §7.6.3 列举（业务类型/归属销售/当前阶段），对所有角色一致，不含 contactPhone / 进度内容。
- **这不是对 Admin 的权限上限**：Admin 对任意线索的完整访问（含联系方式）由既有 `GET /leads/{id}` 能力保留，不受本改动影响；提示只是摘要，Admin 需要电话时点进该线索详情即可。
- **真正的脱敏控制只针对 SALES**（§7.6.5）：差异体现在「service 返回哪些行」——ADMIN 返回该客户全部其他业务线索；SALES 仅返回 `owner_sales_id == caller` 的行（即本人名下、本就有权看全的线索）。
- ownerSalesName 经 `AccountMapper` 解析（公海显示「公海」，复用 `LeadOwnershipService.ownerLabel` 同款逻辑）。
- 备选：在 Admin 提示里也塞联系方式 → 否决（超出 §7.6.3 列举字段，属对 PRD 的扩展；产品确认按 PRD 字段，电话经线索详情查看）。

### D3：「其他业务线索」范围 = 同客户 + 不同业务类型 + 排除已流失，跨年度
查询条件为 `customer_id = ? AND business_type <> ? AND stage <> '已流失'`（不加 `business_year`；排除 LOST，保留进行中四阶段 + WON）。即跨年度统计该客户在其他业务类型下「进行中或已赢单」的线索。
- 与查重（§7.3，year+customer+type）刻意不同：查重是年度内防重，本提示是客户级协同视野，故不加 `business_year`。
- 排除 LOST 的理由（产品确认）：已流失的异业务线不构成有效协同上下文，跨年度的旧流失记录会造成提示噪声；已赢单代表既有客户关系，仍有协同价值故保留。
- 当前阶段（§7.6.3）因此只会是进行中四阶段或「已赢单」，不会出现「已流失」。

### D4：复用现有权限矩阵，无新角色规则
新端点对 ADMIN 与 SALES 均开放（authenticated），可见内容差异由 D2 的 service 裁剪实现，而非端点级 403。与 `permission` capability 既有「authenticated 端点 + service 内数据裁剪」一致。

### D5：前端落点
- `CreateLeadModal.vue`：选定客户（且 ADMIN 选定归属/Admin 创建）后调用详情端点，渲染其他业务线索列表（类型/归属/阶段）。复用现有 `watch(selectedCustomerId)` 节流模式。
- 公海列表视图：读取 `PoolLeadView.customerHasOtherLeads`，为 true 的行显示「该客户已有其他业务线索」文案，不展开详情。
- `LeadDetailPanel.vue`：自有线索详情调用详情端点（excludeBusinessType=当前类型），SALES 仅得本人其他线索。

## Risks / Trade-offs

- **[跨年度无过滤导致提示噪声]** 老旧/已流失的不同业务线也会触发提示 → 缓解：详情端点按阶段排序、UI 可折叠；若运营反馈噪声大，后续可加阶段/年度过滤（属增量，不破坏 spec）。
- **[公海布尔的额外聚合开销]** `listPool` 每页 ≤50 行，需按这些行的 customerId 批量查"是否存在其他业务类型线索" → 缓解：一次 `IN (customerIds)` 聚合查询，复用既有批量装载骨架，单次 SQL。
- **[SALES 越权泄漏]** 若裁剪遗漏，SALES 可能看到他人线索详情 → 缓解：D2 类型隔离 + 集成测试用「他人名下线索」负例断言不出现在 SALES 响应（spec 的负例场景直接对应测试）。
- **[与查重语义混淆]** 开发者可能误用 `LeadDuplicateService` → 缓解：本能力独立 service，design/spec 明确二者范围差异。

## Open Questions

- ~~「其他业务线索」是否排除 LOST / 限定年度？~~ 已定（产品确认）：排除已流失、保留进行中+已赢单、跨年度（见 D3、spec「协同提示的范围限定」）。
- 详情端点是否需要分页？预期同客户业务类型至多 3 种（BIM咨询/BIM培训/定制开发），其他业务线索条数极少，**暂不分页**；若未来业务类型枚举扩展再议。
