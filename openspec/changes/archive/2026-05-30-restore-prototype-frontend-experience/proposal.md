## Why

当前前端只部分体现了 `prototype/dealtrace-workbench.html` 的工作台体验：已有指标和线索行，但整体外壳、表格信息密度、抽屉生命周期、关键交互状态、客户/用户页面的一致性还没有对齐原型。这个变更用于在不改变业务规则、接口契约、权限模型和数据语义的前提下，还原原型中的前端视觉与交互效果。

## What Changes

- 还原受保护工作区外壳的原型风格：侧边栏品牌区、导航状态、辅助提醒卡片、主区间距和工作台表面质感。
- 重构销售工作台首屏结构：四个指标卡、`我的线索` / `公海线索` / `本月结束` 三个线索视图、筛选区、紧凑表格、选中态和原型风格标签/操作。
- 调整工作台线索详情为真实抽屉生命周期：默认隐藏，点击线索后打开，用户可关闭，关闭后清空当前选中线索。
- 按原型强化线索抽屉内容：标题区、阶段轨道、基础字段、操作分组、进度时间线、结束线索只读态；不新增后端系统日志读取能力。
- 将原型中的关键交互状态落到真实页面流程：客户唯一性反馈、线索查重反馈、结束线索只读、赢单/流失弹窗、角色操作差异。
- 让客户管理和用户管理页面使用与工作台一致的视觉和交互语言，但保持既有 API 行为不变。
- 保持前端源码 UTF-8 编码，并验证中文界面文本在浏览器中正常渲染。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `frontend-workbench`：更新前端体验契约，覆盖原型风格外壳、工作台三类线索视图、抽屉生命周期、真实交互状态映射、客户/用户页面一致性。

## Impact

- 预计影响 `frontend/src/components/AppShell.vue`、`frontend/src/views/DashboardView.vue`、`frontend/src/components/LeadDetailPanel.vue`、`frontend/src/views/CustomersView.vue`、`frontend/src/views/UsersView.vue`、相关样式/token 使用和聚焦测试。
- 不修改后端接口、数据库 schema、权限模型或业务流程。
- 不包含 Docker 或部署变更。
- 现有路由名、store、API client 和后端授权仍是权威来源。
