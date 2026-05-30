## Context

frontend-shell（已归档）交付了登录 / 会话 / `axios` 拦截器（token 注入 + `UNAUTHORIZED` 清退 + `ApiError` 按 `code` 分支）、路由守卫与角色导航；frontend-lead-flow（已归档）交付了线索列表 / 公海 / 详情 / 闭单旅程，并把 `src/api/leads.ts` + `src/stores/leads.ts` 立为线索数据访问范式。本批次把 `customers` 占位路由落成真实页面，并补齐"新建线索"——后者依赖一个客户可搜索下拉选择器。后端零改动，消费既有 `POST /customers`、`GET /customers?keyword=`、`POST /leads`、`GET /leads/duplicate-check`。

约束来源：PRD §7.2（客户管理）、§7.3 / §8.2（新建线索与查重三元组）、customer spec（4 字段 / trim 查重 / USCI 归一化先行 / 全量搜索上限 50）、lead spec（创建必填校验 / 归属规则 / 查重三态 / 查重预检端点）、tech-arch §10（Arco Design Vue，禁 Tailwind）、tech-arch §4.1（Vue3 + TS + Vite + Pinia + Vue Router）。CLAUDE.md：USCI 先归一化再校验由后端权威完成；客户按 trim 后查重；线索查重键 = (自然年度, 客户, 业务类型)，仅剩"已流失"允许新建但 UI 必须提示历史流失原因和时间。

## Goals / Non-Goals

**Goals:**

- 客户管理页：无关键词列表 + 按名称 / USCI 关键词搜索（上限 50 由后端裁决），所有已认证用户可见。
- 创建客户：名称 / USCI 必填即时校验，原样提交后端，按 `DUPLICATE_CUSTOMER` / `VALIDATION_ERROR` 回落，成功后入列表。
- 客户可搜索下拉选择器：远程搜索既有客户，供新建线索选中关联客户；不提供"边搜边建"。
- 新建线索：业务类型枚举 + 联系人 + 联系电话（前端即时格式校验）+ 线索来源选填；不采集业务年度 / 初始阶段。
- 创建前查重预检：选中客户 + 业务类型 → `duplicate-check`，按 `blockingReason` 阻断，历史流失记录展示原因与时间。
- 归属：Sales 默认归己 / 可选放入公海；Admin 默认进入公海。
- 复用 frontend-shell 的拦截器 / `ApiError` 分支 / MSW 边界 / 设计 token。

**Non-Goals:**

- Admin 把新线索分配给指定启用 Sales（`ownerSalesId` 指向具体 Sales）——依赖"用户管理"提供启用 Sales 列表，随 frontend-admin 落地。
- 客户改名 / 删除——customer spec 明文不提供端点，本 capability 不绕道实现。
- PRD §7.6 跨业务类型"该客户已有其他业务线索"协同提示——后端无对应端点支撑，不在本批次伪造。
- 任何后端代码、迁移、错误码变更。

## Decisions

### D1：客户端点封装为独立 `src/api/customers.ts`，线索创建 / 预检追加进既有 `src/api/leads.ts`

新增 `src/api/customers.ts` 导出 `CustomerView` 类型（镜像后端 4 字段 record：`id` / `name` / `usci` / `createdAt`）与函数 `searchCustomers(keyword?)`、`createCustomer(name, usci)`；在既有 `src/api/leads.ts` 追加 `createLead(payload)` 与 `duplicateCheck(customerId, businessType)`，并新增 `CreateLeadPayload`、`DuplicateCheckResult`（含 `canCreate` / `blockingReason` / `historicalLost[]`，镜像后端 `DuplicateCheckResponse`）。统一用 `apiClient.get<T,T>` 双泛型（拦截器已 unwrap 信封）。**Why**：客户是独立资源，单独成文件与 leads.ts 对称；线索创建 / 预检属于线索资源，追加进 leads.ts 与既有线索函数同源。**Alternative**：把创建 / 预检也塞 customers.ts——被否，资源边界错位。

### D2：客户搜索带去抖，避免每次按键打一次后端

客户管理页与下拉选择器的关键词搜索 SHALL 去抖（约 300ms）后再调 `searchCustomers`；空 / 全空白关键词等价"无关键词列表"。**Why**：customer spec 的搜索端点是子串匹配 + 取最近 50，逐键请求既浪费又抖动；去抖是体验前置，最终结果仍以后端返回为准。**Alternative**：每次输入即请求——被否，请求风暴。

### D3：客户列表 / 创建用页面局部状态，不进 Pinia store

`CustomersView` 用组件内 `ref` 持有 `customers` / `keyword` / `loading`，创建成功后就地刷新列表。**Why**：与 leads 不同，客户列表无跨视图状态联动（无认领 / 退回式的列表搬移），落 store 反而增加无收益的样板；frontend-shell 已确立"无联动则视图局部状态"的边界。**Alternative**：新增 `customers` store——被否，当前无跨视图联动需求，YAGNI。

### D4：客户可搜索下拉抽为独立组件 `CustomerSelect.vue`

用 Arco `a-select` 的远程搜索模式（`:filter-option="false"` + `@search` 去抖回调 + `:loading`）封装为 `CustomerSelect.vue`，`v-model` 暴露选中的 `customerId`，并向上抛出选中的 `CustomerView`（业务类型预检需要 id，展示需要 name/usci）。**Why**：选择器是"新建线索"的前置零件，独立组件便于单测（输入关键词 → 调 `searchCustomers` → 渲染候选 → 选中抛值）且未来其它入口可复用；spec 明确"仅选既有、不边搜边建"，组件内不放新建捷径。**Alternative**：把选择逻辑内联进新建线索表单——被否，难独立测且不可复用。

### D5：新建线索前必过查重预检，预检结果驱动提交闸门

新建线索表单在「客户已选 + 业务类型已选」后触发 `duplicateCheck`；以预检返回派生一个提交闸门：`canCreate=false` → 禁用提交并按 `blockingReason` 展示 `DUPLICATE_ACTIVE_LEAD`（"该客户该年度该业务类型已有进行中线索"）/ `DUPLICATE_WON_LEAD`（"…已有已赢单线索"）语义；`historicalLost` 非空 → 始终展示每条 `{ lostAt, loseReason, loseNote }`（按后端给定的倒序原样渲染），即便 `canCreate=true`。提交仍可能被 `POST /leads` 以同类错误码兜底拒绝（并发窗口），按 D6 回落。**Why**：CLAUDE.md / PRD §7.3 要求"仅剩已流失允许新建但必须提示历史流失原因和时间"，预检端点正是为此设计（lead spec R6）；前端预检是体验前置，后端创建是权威兜底。**Alternative**：不预检直接提交靠后端报错——被否，丢失"历史流失提示"这一硬性 UI 要求。

### D6：创建错误按 `ApiError.code` 精细分支，复用既有 `ApiError`

- 客户创建：`DUPLICATE_CUSTOMER` → "客户已存在"；`VALIDATION_ERROR` → 透传后端 `message`（USCI 校验失败语义，前端不补充字符位置）。
- 线索创建：`VALIDATION_ERROR` → 展示后端 `message`（联系电话 / 必填 / 归属销售不可用等）；`DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD` → 展示对应阻塞语义并据后端刷新预检态；`UNAUTHORIZED` → 既有拦截器清退。

**Why**：frontend-shell 拦截器已把响应归一为带 `code` 的 `ApiError`，本批次直接 `catch (e) { if (e instanceof ApiError) switch(e.code) }`，与 lead-flow D5 同范式。

### D7：联系电话 / 必填即时校验为纯函数，业务年度 / 阶段前端不采集

联系电话校验抽为纯函数 `isValidContactPhone`（11 位手机号 `/^1[3-9]\d{9}$/` 或座机 `区号?-号码-分机?`，与 lead spec R3 规则对齐），与"联系人非空 / 业务类型已选"共同构成前端即时闸门；表单 SHALL NOT 渲染业务年度或初始阶段字段（lead spec：businessYear 服务端派生、stage 固定未触达）。**Why**：前端即时校验是体验前置，后端为权威兜底（两者都要）；纯函数便于覆盖手机号 / 座机 / 非法各边界单测。**Alternative**：仅靠后端校验——被否，体验差且违背 spec "即时拦截不发请求"场景。

### D8：USCI 归一化与权威校验全在后端，前端只做"非空"即时校验

前端创建客户仅即时校验 name / usci trim 后非空，**不**在前端做 USCI 归一化（trim + 大写）或 18 位校验位计算；原始输入原样提交，归一化先行与校验位由后端 `CustomerService` 权威完成（customer spec R3）。错误回显时前端只透传后端 `message`，不暴露具体出错字符位置（防枚举猜测）。**Why**：CLAUDE.md "USCI 先归一化再校验"是后端权威规则，前端复算既重复又易与后端不一致；spec R3 明确不暴露字符位置。**Alternative**：前端预校验 USCI 校验位——被否，重复实现 + 易漂移。

### D9：组件测试在 MSW（axios 边界）mock，E2E 打真后端

复用 frontend-shell 的 `src/test/setup.ts` + `src/test/msw/`。本批次新增 MSW handler 工厂：`customerList`、`customerSearch`、`createCustomerSuccess`、`createCustomerDuplicate`(`DUPLICATE_CUSTOMER`)、`createCustomerValidation`(`VALIDATION_ERROR`)、`duplicateCheckCanCreate`、`duplicateCheckBlockedActive`(`DUPLICATE_ACTIVE_LEAD`)、`duplicateCheckBlockedWon`(`DUPLICATE_WON_LEAD`)、`duplicateCheckWithHistoricalLost`、`createLeadSuccess`、`createLeadValidation`。E2E 仅覆盖关键旅程（创建客户 → 经选择器选中 → 新建线索成功 → 出现在我的线索），缺凭据时 `test.skip`。**Why**：tech-arch 测试分层；MSW 已是既定边界，禁止 mock axios 内部实现。

### D10：路由接线——替换 `customers` 占位为真实视图

`src/router/index.ts` 把 `customers` 占位路由替换为 `CustomersView`（受保护子路由，复用既有守卫，不动守卫逻辑）；新建线索作为客户管理页内的弹窗 / 子区域呈现（不单列顶层路由，避免与无客户上下文的入口割裂）。导航表 `navigation.ts` 的"客户管理"入口已存在，无需改角色元数据（所有已认证用户可见）。**Why**：沿用 frontend-shell 的 `AppShell` 子路由结构与 `meta.title`；新建线索语义上从客户出发，内嵌客户管理页比独立顶层路由更贴合 PRD §8.2.1"新建必须选客户"。

## Risks / Trade-offs

- **查重预检与创建之间存在并发窗口**（预检通过但提交时三元组已被他人占用）→ 缓解：D5 预检为体验前置，D6 对 `POST /leads` 返回的 `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD` 兜底回落；lead spec R5 已显式接受"双行进行中"的罕见 trade-off，前端不需也不应在前端消解。
- **客户搜索上限 50 可能让目标客户落在截断之外** → 缓解：上限由后端裁决，前端在结果达上限时引导用户细化关键词（与 customer spec 的设计意图一致），不在前端自行分页。
- **联系电话前端正则与后端规则可能漂移** → 缓解：D7 前端校验仅为即时体验，后端为权威兜底（D6 透传 `VALIDATION_ERROR`）；正则按 lead spec R3 文字对齐并以纯函数单测覆盖手机号 / 座机 / 分机 / 非法边界。
- **Admin 本批次只能把新线索放公海、不能指定 Sales** → 这是 frontend-admin 依赖（启用 Sales 列表）的显式切分，非缺陷；spec 已把"指定具体 Sales 归属"划出本批次。
- **历史流失提示依赖后端 `historicalLost` 在 lead-closure 落地后才非空**（lead-core 阶段恒空）→ 缓解：前端按数组渲染，空则不显示历史区块；当前已具备后端能力（lead-closure 已归档），数组可非空。
