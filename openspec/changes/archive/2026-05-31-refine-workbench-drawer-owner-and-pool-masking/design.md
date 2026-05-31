## Context

销售工作台首屏内嵌线索工作区 + 详情抽屉已交付。联调发现三处缺陷：

- 归属列/抽屉显示 `销售 #${ownerSalesId}`（`DashboardView.vue:203`、`LeadDetailPanel.vue:273`），因后端 `LeadView` 仅返回 `ownerSalesId`，spec 只内联 `customerName`/`customerUsci`。
- 抽屉走 `GET /api/leads/{id}` → `LeadService.detailFor`；该方法对 `SALES` 看非自己名下线索（公海 `ownerSalesId=null` 亦属此列）抛 `NOT_FOUND`。前端 `stores/leads.ts` 的 `loadLead` 在请求失败时不清空 `currentLead`，于是抽屉残留上一条线索（可能是名下明文线索）的数据。
- 抽屉模板为 `<LeadDetailPanel :key="activeLeadId" :lead-id="activeLeadId" />`，切换 id 会重挂面板并重新 `loadLead`；但当目标是公海线索（SALES）时 `loadLead` 失败、`currentLead` 不更新，表现为"切换不刷新"。

约束：PRD §7.5「认领前看脱敏号、认领成功后才看明文」；tech-arch §9.4 手机号脱敏分级；脱敏由后端裁决（frontend-workbench spec：联系电话按后端裁决的脱敏/明文原样展示）；不引入 Tailwind；金额/契约不在本次范围。

## Goals / Non-Goals

**Goals:**
- 线索归属在工作台与抽屉显示销售姓名，公海/无归属显示「公海」。
- SALES 对未认领公海线索在抽屉中只见脱敏电话（与公海列表一致），不泄漏明文。
- 抽屉打开时点选任意其他线索即时刷新展示该线索，名下↔公海互切均可，无需手动关闭重开。

**Non-Goals:**
- 不改 `detailFor` 对**其他 SALES 私海**线索的 `NOT_FOUND` 裁决。
- 不改其他端点/页面的脱敏策略，不新增分页/筛选。
- 不为公海线索向 SALES 暴露进度跟踪明细（认领后再看完整详情）。

## Decisions

### D1：归属姓名由后端 `LeadView` 内联 `ownerSalesName`
后端在构造 `LeadView` 时按 `ownerSalesId` join account 取 `name`，公海/无归属为 `null`，与既有 `customerName` 内联（原 design D8）同构。前端直接显示 `ownerSalesName`，为 `null` 时显示「公海」。
- **备选**：前端用 accounts store 把 id→name 映射。否决——accounts 列表是 Admin 专属，SALES 无权拉取；且违反"视图自洽、前端免二次查询"的既有约定。
- 影响面：`LeadView` 记录新增字段；`mine`/`listAll`/`detail` 及各写动作返回视图处统一注入姓名（`toViews` 已批量 load 客户，可同法批量 load 归属姓名避免 N+1）。

### D2：公海抽屉对 SALES 用脱敏公海数据渲染只读摘要，不取明文详情
SALES 在工作台点选公海（无归属）行时，前端用**已加载的 `PoolLeadView`（脱敏）数据**填充抽屉，呈现只读摘要 + 认领入口，**不**调用 `GET /api/leads/{id}`。认领成功后线索转入名下，再按名下线索走明文详情。
- **备选**：放宽后端 `detailFor` 让 SALES 读公海线索并返回脱敏 `LeadView`。否决（本次）——会扩大端点可见性裁决面、牵出公海线索进度可见性等新问题，超出"修缺陷"范围；脱敏权威仍在后端（公海列表已由 `PhoneMasker` 脱敏），前端只是复用后端已脱敏的数据，不自行脱敏。
- 写入口：公海未认领线索对 SALES 只读，仅认领可用（沿用既有 frontend-workbench 闭单/归属显隐规则）。

### D3：`loadLead` 失败清空 `currentLead`，抽屉切换即时刷新
`stores/leads.ts` 的 `loadLead` 在请求失败时将 `currentLead` 置空（而非保留旧值），杜绝跨线索残留。工作台选择线索的逻辑按归属分流：名下/Admin 可见线索走 `loadLead`（明文详情）；SALES 公海线索走 D2 的脱敏数据填充。任一路径都保证"当前抽屉数据 == 当前选中线索"。
- 名下↔名下切换：`:key` 重挂 + `loadLead` 成功即刷新（已工作）。
- 切到公海（SALES）：走 D2，不再失败残留。
- 切到无权线索（异常）：`loadLead` 失败 → 清空 → 抽屉呈现空/错误态，不残留他人数据。

## Risks / Trade-offs

- [后端姓名 join 引入 N+1] → `toViews` 批量场景用一次性 `IN` 查询 account 名称映射；单条 `detail`/写动作各 1 次额外查询，可接受。
- [停用/已删 sales 的 `ownerSalesId` 取不到姓名] → `ownerSalesName` 返回 `null`，前端回退显示（如「公海」或「—」按归属是否为 null 区分）；归属 id 非空但姓名为空属异常数据，前端不崩。
- [前端公海抽屉与名下抽屉两条数据来源] → 在面板内按"是否公海 + 角色"集中分流，避免散落判断；组件测试覆盖两条路径。
- [后端集成测试与运行中的 dev 后端共用远程 dealtrace 实例冲突] → apply 跑后端测试前停掉 8080 dev 后端（项目记忆已记录该约束），测后按需重启。
