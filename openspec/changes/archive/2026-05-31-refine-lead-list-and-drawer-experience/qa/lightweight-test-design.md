# 轻量测试设计：refine-lead-list-and-drawer-experience

## 需求来源与冲突审查

- 权威来源：用户明确需求、本 change 的 proposal/design/spec、已归档的 `restore-prototype-frontend-experience` 主规格。
- 既有基线：当前前端实现、现有 Vitest 组件测试、现有 API 封装和 store 行为。
- 关系分类：本轮对既有前端体验为 `amends` 和 `extends`；不改变后端接口、权限、线索状态机和字段契约。
- 冲突结论：无阻塞冲突。原主规格中“工作台本轮不要求分页或筛选”的旧描述由本轮显式需求修订。

## 测试点设计

| 测试点 | 来源 / 权威 | 方法 | 层级 | 输入 / 前置条件 | 预期结果 | 断言目标 | 优先级 | 覆盖产物 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 工作台 tab 顺序为我的/公海/全部 | Spec | 黑盒场景 | 组件 | 工作台加载线索数据 | tab 顺序正确 | tab 文本顺序 | P0 | `frontend/src/views/DashboardView.spec.ts` |
| 工作台全部线索合并当前可访问数据 | Spec | 决策表 | 组件 | my/all + pool 均有数据 | 全部线索展示合并结果 | 表格行文本 | P0 | `frontend/src/views/DashboardView.spec.ts` |
| 工作台搜索后分页重置 | Spec | 状态转换 | 组件 | 多条线索、当前页非 1 | 搜索后回第一页且总数更新 | 分页状态、行数 | P1 | `frontend/src/views/DashboardView.spec.ts` |
| 新增线索入口统一弹窗 | Spec | 用例 | 组件 | 点击工作台/今日提醒/线索页入口 | 打开同一弹窗 | 弹窗 data-test 和字段 | P0 | `frontend/src/views/DashboardView.spec.ts`, `frontend/src/components/AppShell.spec.ts`, lead list specs |
| 查重阻断不提交创建 | 既有客户页行为 + Spec | 路径覆盖 | 组件 | duplicateCheck 返回 canCreate=false | 提示阻断且不调 createLead | API mock 调用次数 | P0 | `frontend/src/components/CreateLeadModal.spec.ts` |
| 抽屉阶段轨道只到当前 | Spec | 等价类 | 组件 | 当前阶段为中间阶段 | 不展示未来阶段 | stage rail 文本 | P0 | `frontend/src/views/LeadDetailView.spec.ts` |
| 顶部不显示角色 | Spec | 黑盒 | 组件 | Admin/Sales 登录 | 只显示姓名 | `.shell-user-role` 不存在 | P1 | `frontend/src/components/AppShell.spec.ts` |
| 我的线索搜索分页与入口 | Spec | 用例 | 组件 | 多条线索 | 可搜索分页；有新增客户/新增线索 | 行文本、分页、按钮 | P0 | `frontend/src/views/MyLeadsView.spec.ts` |
| 公海线索搜索分页与认领保留 | Spec + 既有行为 | 回归 | 组件 | 公海数据、Sales | 搜索分页；认领仍调用 claim | API mock 调用 | P0 | `frontend/src/views/PublicPoolView.spec.ts` |
| 客户管理去掉无意义标签并分页 | Spec | 黑盒 | 组件 | 客户列表 | 不显示 Table/Modal 标签；分页有效 | 文本不存在、分页 | P1 | `frontend/src/views/CustomersView.spec.ts` |
| 用户管理搜索分页 | Spec | 等价类 | 组件 | 多账号 | 可按邮箱/姓名/角色/状态搜索分页 | 行文本、分页 | P1 | `frontend/src/views/UsersView.spec.ts` |

## TDD 计划

- 严格 Red/Green：列表搜索分页、统一新建线索入口、查重阻断、tab 顺序、阶段截断、顶部角色隐藏。
- 非 TDD 例外：纯样式细节（指标卡视觉克制、抽屉宽度、按钮间距）不适合精确 Red 测试；通过组件结构断言和浏览器 smoke 截图补充验证。

## E2E / Smoke 场景

- Persona：Sales。
- 前置：使用前端 API route mock 或测试后端数据，含我的线索、公海线索、客户、查重结果。
- 路径：登录 -> 工作台 -> 搜索/分页 -> 打开抽屉 -> 新增线索 -> 我的线索 -> 公海线索 -> 客户管理。
- 断言：中文可读、抽屉阶段不显示未来阶段、入口统一弹窗、分页显示、关闭按钮不遮挡核心内容。

## 覆盖闭环

- 已覆盖：工作台 tab 顺序、全部线索合并、搜索分页、统一新增线索入口、指标卡旧 spark 移除；抽屉阶段截断和顶部阶段标签移除；AppShell 右上角角色隐藏与今日提醒入口；我的线索/公海/客户/用户列表搜索分页；客户页查重阻断与历史流失提示通过 `CreateLeadModal` 组件测试复用覆盖。
- 非 TDD 例外：抽屉宽度、按钮间距、指标卡弱视觉标记等纯视觉表现，用组件结构断言和可见浏览器 smoke 辅助验证，不做像素级自动化断言。
- 剩余风险：前端分页基于当前接口返回的已加载数组；若后续接口改为服务端分页，需要另起 OpenSpec change 调整契约。
