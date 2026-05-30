## Why

frontend-shell 交付了登录 / 会话 / 角色导航骨架，frontend-lead-flow 交付了线索浏览 / 认领 / 详情 / 闭单旅程，但两者都把"客户管理"留作占位页，并显式将"新建线索"划出范围——因为新建线索必须先通过可搜索下拉选中既有客户（PRD §7.3.1 / §8.2.1-2）。客户沉淀是 MVP 的入口环节（PRD §3 目标 1、§4.1），没有它销售无法录入新客户、也无法创建任何线索。后端客户端点（`POST /customers`、`GET /customers`）与线索创建 / 查重预检端点（`POST /leads`、`GET /leads/duplicate-check`）均已就绪，前端需把它们落成可用界面，补齐"客户 → 线索"录入闭环。

## What Changes

- **客户列表 / 搜索**：客户管理页展示客户（无关键词取最近 50 行，有关键词按客户名称或 USCI 子串匹配取最近 50 行，上限 50 由后端裁决），所有已认证用户（Admin / Sales）均可搜索；展示 `name` / `usci` / `createdAt`。
- **创建客户**：提供创建入口，客户名称与 USCI 必填且即时校验非空；提交后按后端裁决回显成功或失败（`DUPLICATE_CUSTOMER` 提示"客户已存在"、`VALIDATION_ERROR` 透传后端 `message`，前端不暴露 USCI 校验位的具体字符位置）；创建成功后新客户进入列表。
- **客户可搜索下拉选择器**：抽出一个复用组件，按客户名称 / USCI 关键词远程搜索客户，供新建线索时选中既有客户（PRD §8.2.2）；不提供在选择器内"边搜边建客户"的捷径（创建客户走独立入口）。
- **新建线索**：在客户管理页 / 客户选定后提供新建线索入口；必填客户（经选择器）、业务类型（`BIM咨询` / `BIM培训` / `定制开发` 枚举）、联系人、联系电话（11 位手机号或座机格式即时校验），线索来源选填；`businessYear` 由服务端派生、阶段固定 `未触达`，前端 SHALL NOT 采集这两项。
- **创建前查重预检**：选中客户 + 业务类型后调用 `GET /leads/duplicate-check` 预判可行性——`canCreate=false` 时按 `blockingReason`（`DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD`）提示并阻止提交；仅剩历史流失记录时允许新建，但 UI 必须展示每条历史流失的原因与流失时间（CLAUDE.md 线索查重键规则 / PRD §7.3）。
- **创建归属（本批次范围内）**：Sales 创建默认归自己，或显式选择"放入公海"（`assignToPool`）；Admin 创建默认进入公海（不携带 `ownerSalesId`）。指定具体启用 Sales 为归属依赖"用户管理"提供的启用 Sales 列表，划出本批次（随 frontend-admin 落地）。
- **路由接线**：把 `customers` 占位路由替换为真实客户管理视图。

不在本批次：Admin 把新线索分配给指定 Sales（依赖启用 Sales 列表，随 frontend-admin）、客户改名 / 删除（后端 MVP 未提供端点，customer spec 明文排除）、PRD §7.6 跨业务类型"该客户已有其他业务线索"协同提示（后端无对应端点支撑）、任何后端代码 / 迁移 / 错误码变更。

## Capabilities

### New Capabilities
<!-- 无新增能力；本批次向既有 frontend-workbench 追加需求。 -->

### Modified Capabilities
- `frontend-workbench`: 在登录 / 会话 / 角色导航 / 线索旅程之上，追加"客户列表与搜索、创建客户、客户可搜索下拉选择器、带查重预检与历史流失提示的新建线索"等可观察前端行为需求。

## Impact

- **前端代码**：新增客户列表 / 搜索 + 创建客户视图（`CustomersView`）、客户可搜索下拉选择器组件、新建线索表单（含查重预检与历史流失提示）；新增客户 API 封装（`src/api/customers.ts`：`searchCustomers` / `createCustomer`）与线索创建 / 预检封装（在既有 `src/api/leads.ts` 追加 `createLead` / `duplicateCheck`）；占位 `customers` 路由替换为真实视图。
- **后端**：零改动，仅消费既有 `POST /customers`、`GET /customers?keyword=`、`POST /leads`、`GET /leads/duplicate-check?customerId=&businessType=` 端点与既有错误码（`DUPLICATE_CUSTOMER`、`VALIDATION_ERROR`、`DUPLICATE_ACTIVE_LEAD`、`DUPLICATE_WON_LEAD`、`UNAUTHORIZED`）。
- **依赖**：无新增运行时依赖；组件测试复用 frontend-shell 引入的 MSW（axios 边界）与设计 token，E2E 复用 Playwright 真后端约定。
- **设计**：沿用 Arco Design Vue 组件体系（`a-table` / `a-form` / `a-select` 远程搜索 / `a-modal`）与从 `prototype/dealtrace-workbench.html` 抽取的 `--dt-*` 设计 token，禁止引入 Tailwind（tech-arch §10）。
