## Why

后端 9 个 capability 已全部归档稳定，对外暴露了统一响应信封与 `ErrorCode`，但前端仍是脚手架：`router` 路由表为空、`auth` store 只有 token 占位、`App.vue` 还在渲染 `HelloWorld`。任何业务页面都无法在"未登录拦截、登录态承载、按角色显隐入口"这套地基缺失的情况下落地。本 change 建立这套地基，是 `frontend-workbench` capability 的第一个实现批次，后续业务页面（我的线索 / 公海 / 客户 / 详情 / dashboard / 用户管理 / 合同）都挂在它之上。

## What Changes

- **登录页**：邮箱 + 密码登录（部署注入的初始 Admin，无自助注册 / 找回密码 / 邀请激活）；登录成功承载后端签发的访问令牌并进入工作台；登录失败按后端统一 `UNAUTHORIZED` 信封展示，不区分"邮箱不存在 / 密码错 / 账号停用"以外的细节（防账号枚举，语义由后端 `message` 给出）。
- **路由守卫**：未登录访问受保护路由回落登录页；已登录访问登录页回落工作台首页。
- **axios 拦截器接管登录态**：请求注入 `Authorization: Bearer <token>`；响应 `UNAUTHORIZED`（含令牌缺失 / 非法 / 过期 / 账号被停用后失效）清除登录态并踢回登录页；其余 `ErrorCode` 透传为可分支的 `ApiError`（已有 `unwrapEnvelope` 基础上扩展）。
- **角色驱动的导航骨架**：登录后按当前用户 `role`（`ADMIN` / `SALES`）显隐功能入口；Admin 专属入口对 Sales 不可见。前端显隐仅为减少无效入口与提升体验，**所有越权最终以后端返回为准**——前端遇到后端拒绝（`FORBIDDEN` / `UNAUTHORIZED`）必须优雅回落，不得假设前端显隐等同于授权。
- **登录态 store**：持有当前用户脱敏信息（`id` / `email` / `name` / `role` / `status`）与令牌；提供登录、登出、会话失效清理；刷新后能凭持久化令牌恢复登录态（向"当前用户信息"端点核实）。
- **工作台外壳布局**：侧边导航 + 顶栏 + 内容区的工作台骨架，从原型抽取设计 token（不复刻其单屏 kitchen-sink 结构）；业务页面区域为占位，由后续 change 填充。

不含任何业务页面与业务数据交互（客户 / 线索 / 公海 / 详情 / dashboard / 用户管理 / 合同 CRUD 均属后续 4 个 change）。

## Capabilities

### New Capabilities
- `frontend-workbench`: 工作台式前端体验的行为契约。本 change 引入其地基部分——登录与登出、未登录拦截、登录态承载与会话失效处理、按角色显隐功能入口（且以后端裁决为准）。后续 change 在同一 capability 下追加业务页面的可观察行为。

### Modified Capabilities
<!-- 无：frontend-workbench 为全新 capability，本 change 首次引入 -->

## Impact

- **前端代码**：`frontend/src/router`（路由表 + 守卫）、`frontend/src/stores/auth.ts`（从占位升级为真实登录态）、`frontend/src/api/client.ts`（token 注入 + 401 处理 + ErrorCode 分支）、`frontend/src/App.vue` + 新增工作台外壳与登录页组件、`frontend/src/styles`（从原型抽取的设计 token）。
- **依赖**：新增 `msw`（开发依赖，组件测试在 axios 边界 mock 后端）。沿用既有 `@arco-design/web-vue` / `pinia` / `vue-router` / `axios`，**不**引入 Tailwind（tech-arch §10.7）。
- **后端**：无改动。消费既有 auth-account 契约（登录端点、"当前用户信息"端点、`UNAUTHORIZED` / `FORBIDDEN` 语义）。
- **测试**：vitest 组件测试（MSW 拦截）严格 Red-Green；Playwright E2E（真后端栈）覆盖"登录 → 进入工作台 → 登出"关键旅程，场景优先。
