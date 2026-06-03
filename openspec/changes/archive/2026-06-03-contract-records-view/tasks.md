## 1. QA 设计（先于生产代码）

- [x] 1.1 用 `.claude/skills/vibe-coding-qa/templates/` 在 `openspec/changes/contract-records-view/qa/` 起草测试设计：覆盖 Admin 全量倒序分页、三类筛选（成交销售 / 签订日期闭区间 / 客户名·业务类型关键词）、Sales 收窄为本人、Sales 传他人 dealSalesId 仍收窄、公海赢单展示、金额千分位与精度、纯只读六组行为。
- [x] 1.2 标注分层归属：Mapper/Service 业务规则 → API/集成（Testcontainers + 真 MySQL 8.4）；权限收窄与不泄漏 → API 集成；前端列表渲染/筛选交互 → 前端单测。

## 2. 后端查询层（Mapper，TDD）

- [x] 2.1 写 Red：`ContractMapper` 分页查询集成测试（真 MySQL 8.4），覆盖 JOIN customer/lead 取客户名与业务类型、`created_at DESC` 排序、`signed_date` 闭区间、`deal_sales_id` 等值、关键词 LIKE、count 查询；含落在日期端点的边界行与 `deal_sales_id=NULL` 行。
- [x] 2.2 在 `ContractMapper` 增加分页列表查询与计数查询（JOIN `lead`、`customer`、LEFT JOIN `account` 取成交销售当前姓名；`lead` 反引号），令 2.1 转 Green。
- [x] 2.3 定义查询条件载体（dealSalesId、signedDateFrom、signedDateTo、keyword、page、size）与行投影 DTO（含 leadId、customerName、businessType、contractAmount、signedDate、createdAt、dealSalesId、dealSalesName）。

## 3. 后端服务与权限收窄（TDD）

- [x] 3.1 写 Red：`ContractReadService` 测试——Admin 透传 dealSalesId 过滤；Sales 强制以 principal.id 覆盖 dealSalesId（传他人 id 仍只返回本人，排除 NULL 公海单）；分页元信息正确。
- [x] 3.2 实现 `ContractReadService`：按 `AccountPrincipal` 角色分流（Admin 用传入条件；Sales 覆盖 dealSalesId=principal.id），组装 `ContractPageView` / `ContractRowView`，令 3.1 转 Green。
- [x] 3.3 展示组装：成交销售按 account 当前姓名解析，`deal_sales_id=NULL` 展示"公海赢单"；金额以精确数值序列化（不浮点）。

## 4. 后端端点（TDD）

- [x] 4.1 写 Red：`/contracts` GET API 集成测试——匿名 `UNAUTHORIZED`；Admin 分页倒序全量 + 三类筛选；Sales 仅本人、传他人 id 被收窄、响应不含他人/公海记录。
- [x] 4.2 新增 `ContractController`（`@RequestMapping("/contracts")`，`@AuthenticationPrincipal AccountPrincipal`，可选参数 dealSalesId/signedDateFrom/signedDateTo/keyword/page 默认 1/size 默认 20），委托 `ContractReadService`，令 4.1 转 Green。
- [x] 4.3 确认端点**不**落在 `/admin/**`（否则 Sales 被 SecurityConfig 拦截），且无任何写方法。

## 5. 前端合同记录页（TDD）

- [x] 5.1 写 Red：合同记录视图前端单测——渲染列（客户/业务类型/合同金额千分位/签订日期/成交销售/赢单时间）、公海赢单显示"公海赢单"、分页交互、筛选项（成交销售仅 Admin 可见、签订日期区间、关键词）。Arco 弹窗/下拉测试注意 `:render-to-body="false"`（项目记忆 arco-modal-render-to-body-test）。
- [x] 5.2 新增合同记录视图组件（复用 `SystemLogsView` 全局浏览布局与分页），接 `/contracts` API；金额前端千分位格式化、断言按数值；令 5.1 转 Green。
- [x] 5.3 在 `frontend/src/router/index.ts` 将 `/contracts` 的 `PlaceholderView` 替换为新组件；成交销售筛选项依 `auth.isAdmin` 条件渲染。

## 6. 验证与归档

- [x] 6.1 全量后端 `mvn -o test` **278 通过 / 0 失败 / 0 错误 BUILD SUCCESS**；前端全量 223 + `vue-tsc` 0 error。修复期间发现的 2 个既有账号测试 ERROR（共享库 lead/contract 残留撞 `DELETE FROM account` 外键，`git stash` 验证与本 change 无关）已通过加固账号测试 seed（删账号前按外键序清子表）一并解决。详见 `qa/qa-report.md`。
- [x] 6.2 对照 spec 四条 Requirement 逐条核验 Scenario 全部有对应通过用例；对照 PRD §11.10「Admin 可查看全部合同记录」验收点（见 `qa/qa-report.md` 覆盖核对表）。
- [x] 6.3 在 `qa/` 落 QA 报告（`qa/test-design.md` + `qa/qa-report.md`）；归档命令 `/opsx:archive contract-records-view` 待全量后端套件确认后由用户触发。
