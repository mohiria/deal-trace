## 1. QA 设计与测试边界

- [x] 1.1 用 `.claude/skills/vibe-coding-qa/templates/` 在 `openspec/changes/frontend-customer/qa/lightweight-test-design.md` 写轻量测试设计：按 spec 4 个需求列单元 / 组件 / E2E 分层与 Red 预期，标注前端即时校验与后端裁决双轨，并标明"USCI 归一化 / 校验位"权威在后端、前端只校验非空。
- [x] 1.2 在 `src/test/msw/handlers.ts` 追加客户 / 线索创建 handler 工厂：`customerList`、`customerSearch`、`createCustomerSuccess`、`createCustomerDuplicate`(`DUPLICATE_CUSTOMER`)、`createCustomerValidation`(`VALIDATION_ERROR`)、`duplicateCheckCanCreate`、`duplicateCheckBlockedActive`(`DUPLICATE_ACTIVE_LEAD`)、`duplicateCheckBlockedWon`(`DUPLICATE_WON_LEAD`)、`duplicateCheckWithHistoricalLost`、`createLeadSuccess`、`createLeadValidation`。

## 2. API 封装与类型（D1）

- [x] 2.1 写 `src/api/customers.spec.ts`（Red）：`searchCustomers(keyword?)` 命中 `GET /customers`（无关键词不带 query、有关键词带 `keyword=`）、`createCustomer(name, usci)` 命中 `POST /customers` 并原样提交输入；unwrap 后返回 `CustomerView`；错误归一为带 `code` 的 `ApiError`。
- [x] 2.2 实现 `src/api/customers.ts`：导出 `CustomerView` 类型（`id` / `name` / `usci` / `createdAt`，镜像后端 record）与 `searchCustomers` / `createCustomer`，用 `apiClient.get<T,T>` 双泛型，复用既有 `apiClient` / `ApiError`。
- [x] 2.3 写 `src/api/leads.spec.ts` 增量（Red）：`createLead(payload)` 命中 `POST /leads`、`duplicateCheck(customerId, businessType)` 命中 `GET /leads/duplicate-check?customerId=&businessType=` 并 unwrap 返回 `{ canCreate, blockingReason, historicalLost }`。
- [x] 2.4 在 `src/api/leads.ts` 追加 `CreateLeadPayload`（`customerId` / `businessType` / `contactName` / `contactPhone` / `leadSource?` / `assignToPool?`）、`DuplicateCheckResult`（`canCreate` / `blockingReason` / `historicalLost[]{ lostAt, loseReason, loseNote }`）类型与 `createLead` / `duplicateCheck` 函数。

## 3. 纯函数工具（D7）

- [x] 3.1 写 `src/utils/lead.spec.ts` 增量（Red）：`isValidContactPhone` 覆盖 11 位手机号（合法 / 首位非 1 / 第二位非 3-9 / 长度错）、座机（含区号 / 含分机 / 无区号）、非法（`abc` / `123` / `+1-555-1234`）。
- [x] 3.2 在 `src/utils/lead.ts` 追加 `isValidContactPhone`（手机号 `/^1[3-9]\d{9}$/` 或座机区号?-号码-分机? 正则，按 lead spec R3 文字对齐）；若已有业务类型枚举常量则复用，否则补 `BUSINESS_TYPE` 常量（`BIM咨询` / `BIM培训` / `定制开发`）。

## 4. 客户管理页：列表与搜索（spec R1）

- [x] 4.1 写 `src/views/CustomersView.spec.ts` 列表 / 搜索部分（Red）：无关键词渲染后端返回列表（name / usci / createdAt）；输入关键词（去抖后）调 `searchCustomers` 并渲染匹配；无命中渲染空态；不渲染后端未返回的客户。
- [x] 4.2 实现 `src/views/CustomersView.vue` 列表 / 搜索区：组件内 `ref` 持 `customers` / `keyword` / `loading`（D3），去抖（约 300ms）调 `searchCustomers`（D2），Arco `a-table` 展示；`--dt-*` token，禁 Tailwind。

## 5. 客户管理页：创建客户（spec R2）

- [x] 5.1 写 CustomersView 创建部分（Red）：name 或 usci 为空 / 全空白时即时拦截不发请求；合法提交成功后新客户进入列表；`DUPLICATE_CUSTOMER` 提示"客户已存在"；`VALIDATION_ERROR` 透传后端 `message` 且提示不含字符位置（D8）。
- [x] 5.2 实现创建客户弹窗 / 表单（Arco `a-modal` + `a-form`）：name / usci 必填即时校验，原样提交 `createCustomer`，成功后就地刷新列表，按 D6 分支 `DUPLICATE_CUSTOMER` / `VALIDATION_ERROR`。

## 6. 客户可搜索下拉选择器（spec R3，D4）

- [x] 6.1 写 `src/components/CustomerSelect.spec.ts`（Red）：输入关键词（去抖后）调 `searchCustomers` 渲染候选；选中后 `v-model` 暴露 `customerId` 并向上抛出选中的 `CustomerView`；无匹配时不提供"以关键词新建客户"入口。
- [x] 6.2 实现 `src/components/CustomerSelect.vue`：Arco `a-select` 远程搜索（`:filter-option="false"` + `@search` 去抖 + `:loading`），`v-model` 选中 `customerId`，emit 选中客户对象；组件内无新建捷径。

## 7. 新建线索表单 + 查重预检（spec R4，D5 / D7）

- [x] 7.1 写 `src/views/CustomersView.spec.ts` 新建线索部分（Red）：未选业务类型 / 联系人空 / 联系电话非法时即时拦截不发请求；表单不渲染业务年度与初始阶段字段；选客户+业务类型后调 `duplicateCheck`；`canCreate=false` 按 `blockingReason` 提示并禁用提交；`historicalLost` 非空时展示每条流失原因与流失时间。
- [x] 7.2 写新建线索归属 / 提交部分（Red）：Sales 可在"归己 / 放入公海"间选并提交成功；提交遇 `VALIDATION_ERROR` / `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD` 展示语义不伪造成功；Admin 默认进入公海。
- [x] 7.3 实现新建线索弹窗（Arco `a-modal` + `a-form`）：嵌入 §6 `CustomerSelect`，业务类型 `a-select`（枚举），联系人 / 联系电话即时校验（`isValidContactPhone`），线索来源选填，无业务年度 / 阶段字段；客户+业务类型齐备触发 `duplicateCheck`，按 D5 派生提交闸门与历史流失提示区。
- [x] 7.4 实现归属选择：`auth.isAdmin` 为真隐藏归属选择（默认公海，不传 `ownerSalesId`）；Sales 呈现"归己 / 放入公海"开关（放入公海传 `assignToPool: true`）；提交调 `createLead`，按 D6 分支错误码。

## 8. 路由接线（D10）

- [x] 8.1 在 `src/router/index.ts` 把 `customers` 占位路由替换为 `CustomersView`（沿用既有守卫与 `meta.title`，不改守卫逻辑）；确认 `navigation.ts` 的"客户管理"入口角色元数据无需变更。

## 9. E2E 与全量验证

- [x] 9.1 写 `tests/e2e/customer-flow.spec.ts`：创建客户 → 客户管理页搜索到该客户 → 经选择器选中 → 新建线索成功 → 在"我的线索"出现，缺 E2E 凭据时 `test.skip`。
- [x] 9.2 跑 `pnpm test:unit` 全绿、`pnpm build`（vue-tsc + vite）通过；用 `node .claude/skills/vibe-coding-qa/scripts/qa_artifacts.mjs check` 校验 qa 产物；`openspec validate frontend-customer --strict` 通过。
- [x] 9.3 在 `openspec/changes/frontend-customer/qa/` 补 `qa-test-report.md` 与 `regression-impact-analysis.md`（含 Red 证据与对 leads.ts / utils/lead.ts / MSW handler / 路由的回归影响）。
