## Why

frontend-shell 已交付登录、会话、路由守卫与角色导航骨架，但工作台的核心销售旅程——查看名下线索、从公海认领、在详情中推进并闭单——仍是占位页。这是 MVP 的主价值路径（PRD §7.3–§7.11），后端线索端点已全部就绪，前端需把它落成可用界面。

## What Changes

- **我的线索**：列表展示当前用户有权访问的线索（Sales 看名下、Admin 看全部）及其阶段，点击进入详情。
- **公海认领**：列表展示公海线索，联系电话按角色脱敏（Sales 脱敏、Admin 明文）；Sales 可认领，认领成功后线索移入"我的线索"且电话转明文；并发认领仅一人成功，其余以 `LEAD_ALREADY_CLAIMED` 提示"该线索已被认领"。
- **线索详情**：展示客户与线索字段、当前归属、当前阶段、流失原因/说明；进度跟踪按时间倒序成流。
- **进度跟踪追加**：在未结束线索上追加进度（跟踪方式 + 跟踪内容必填，时间由服务端生成），追加后线索"最后跟踪时间"同步刷新；进度为追加流，不可编辑删除。
- **阶段推进与闭单**：在未结束线索上于非结束阶段间跳转；标记赢单（合同金额 > 0、至多两位小数、千分位展示、签订日期必填）与标记流失（流失原因枚举，选"其他"须填说明）均为独立动作，不可对已闭单线索重复标记。
- **主动退回公海**：Sales 可将名下未结束线索退回公海（须填退回备注），退回后该 Sales 再看时电话重新脱敏。
- **闭单只读**：已赢单 / 已流失线索在详情中为只读，进度追加、阶段变更、赢单、流失、退回等写入口收起；任何越权或失效操作以后端 `FORBIDDEN` / `LEAD_ENDED_READONLY` 裁决为准，前端优雅回落不伪造成功。

不在本批次：Admin 分配 / 回收 / 转移（依赖"用户管理"提供的启用 Sales 列表，随 frontend-admin 落地）、线索详情的系统日志面板（system-log 能力随 frontend-admin 落地）、新建线索（依赖 frontend-customer 的客户可搜索选择器）。

## Capabilities

### New Capabilities
<!-- 无新增能力；本批次向既有 frontend-workbench 追加需求。 -->

### Modified Capabilities
- `frontend-workbench`: 在登录 / 会话 / 角色导航骨架之上，追加"我的线索列表、公海浏览与认领、线索详情与进度跟踪、阶段推进与赢单 / 流失闭单、主动退回公海、闭单只读"等可观察行为需求。

## Impact

- **前端代码**：新增线索列表页、公海页、线索详情页及其进度跟踪 / 阶段 / 闭单 / 退回交互；新增线索 API 封装（复用 frontend-shell 已验证的 axios 拦截器、ApiError 分支、token 注入）；占位路由替换为真实视图。
- **后端**：零改动，仅消费既有 `/leads/*` 端点（`/mine`、`/`、`/{id}`、`/pool`、`/{id}/claim`、`/{id}/release`、`/{id}/stage`、`/{id}/win`、`/{id}/lose`、`/{id}/progress`）与既有错误码（`LEAD_ALREADY_CLAIMED`、`LEAD_ENDED_READONLY`、`VALIDATION_ERROR`、`FORBIDDEN`）。
- **依赖**：无新增运行时依赖；组件测试复用 frontend-shell 引入的 MSW 边界与设计 token，E2E 复用 Playwright 真后端约定。
- **设计**：沿用 Arco Design Vue 组件体系与从 `prototype/dealtrace-workbench.html` 抽取的设计 token，禁止引入 Tailwind（tech-arch §10）。
