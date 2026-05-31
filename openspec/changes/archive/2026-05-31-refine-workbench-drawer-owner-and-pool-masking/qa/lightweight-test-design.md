# 轻量测试设计：refine-workbench-drawer-owner-and-pool-masking

## 需求来源与冲突审查

- 权威来源：用户联调反馈（三问题）、本 change 的 proposal/design/spec、PRD §7.5（认领前脱敏/认领后明文）、tech-arch §9.4（脱敏分级）、`openspec/specs/lead/spec.md`「业务线索字段集」。
- 既有基线：后端 LeadView 仅返回 ownerSalesId；detailFor 对 SALES 看非名下线索 NOT_FOUND；公海列表经 PhoneMasker 脱敏；前端抽屉走明文详情端点。
- 关系分类：lead 视图为 `amends`（响应额外内联 ownerSalesName，不改既有字段语义）；frontend-workbench 为 `extends`（新增抽屉脱敏只读 + 切换刷新 + 归属姓名展示）。
- 冲突结论：无阻塞冲突。不改 detailFor 私海 NOT_FOUND 裁决、不改脱敏端点划分。

## 测试点设计

| 测试点 | 来源 / 权威 | 方法 | 层级 | 输入 / 前置 | 预期结果 | 断言目标 | 优先级 | 覆盖产物 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 有归属线索详情内联 ownerSalesName | lead spec | 黑盒 | API/集成（真 MySQL） | admin GET /leads/{ownedById} | data.ownerSalesName=归属姓名 | jsonPath ownerSalesName | P0 | `LeadControllerDetailListTest` |
| mine 列表内联 ownerSalesName | lead spec | 黑盒 | API/集成 | salesA GET /leads/mine | data[0].ownerSalesName="Sales A" | jsonPath | P0 | `LeadControllerDetailListTest` |
| 公海线索 ownerSalesName 为 null | lead spec | 等价类 | API/集成 | admin GET /leads/{poolId} | ownerSalesName=null | jsonPath nullValue | P0 | `LeadControllerDetailListTest` |
| 归属姓名缺失（停用/删号）回退 null | design 风险项 | 边界 | 单元 | ownerSalesId 指向不存在账号 | ownerSalesName=null，不抛错 | 视图构造 | P1 | LeadService 单元（如设） |
| 工作台归属列显示姓名非 id | fe-workbench spec | 黑盒 | 组件 | 行 ownerSalesName 有值 | 显示姓名，无 `销售 #id` | 文本断言 | P0 | `DashboardView.spec.ts` |
| 公海归属显示「公海」 | fe-workbench spec | 等价类 | 组件 | ownerSalesName=null | 显示「公海」 | 文本断言 | P1 | `DashboardView.spec.ts` |
| SALES 公海抽屉电话脱敏、不调明文详情 | fe-workbench spec / PRD §7.5 | 路径覆盖 | 组件 | SALES 点公海行 | 脱敏电话 + 不请求 /leads/{id} | mock 调用次数 + 文本 | P0 | `DashboardView.spec.ts` |
| SALES 公海抽屉仅认领写入口 | fe-workbench spec | 黑盒 | 组件 | SALES 公海抽屉 | 仅认领、无进度/阶段/归属等 | 入口存在性 | P0 | `LeadDetailPanel.spec.ts`/`DashboardView.spec.ts` |
| 抽屉打开切换其他线索即时刷新 | fe-workbench spec | 状态转换 | 组件 | 打开 A 后选 B | 抽屉显示 B | 抽屉内容 | P0 | `DashboardView.spec.ts` |
| 名下↔公海互切刷新 | fe-workbench spec | 状态转换 | 组件 | 名下→公海→名下 | 各自正确呈现 | 抽屉内容/脱敏 | P1 | `DashboardView.spec.ts` |
| loadLead 失败清空 currentLead | fe-workbench spec | 路径覆盖 | 组件/单元 | fetchLead 抛错 | currentLead=null，不残留 | store 状态 | P0 | `leads` store / 组件 |

## 覆盖闭环与例外

- 已覆盖：上述 P0/P1 测试点。
- 非 TDD 例外：抽屉只读摘要的纯视觉排版（卡片/间距）——结构断言 + 浏览器 smoke 验证，不做像素断言。
- 剩余风险：后端集成测试与运行中的 dev 后端共用远程 dealtrace，运行前须停 8080；前端公海抽屉两条数据来源（脱敏摘要 vs 明文详情）需在组件内集中分流，避免散落判断。
