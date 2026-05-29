## 1. QA 测试设计（先于测试代码）

- [x] 1.1 在 `openspec/changes/permission/qa/` 用 vibe-coding-qa 模板写测试设计：按 spec 4 个 Requirement（矩阵 / 默认拒绝 / 角色专属 403 / 停用聚合 / 后端强校验）列测试矩阵与去重边界（对照 SecurityPathRuleTest、LeadNotInScopeTest、LeadAssign/TransferTest）
- [x] 1.2 记录冲突门姿态：permission 只做整面/聚合断言，不复制各 capability 单端点细节用例；列出 permitAll 白名单与框架端点排除策略

## 2. 默认拒绝扫描（INV-1，自动发现）

- [x] 2.1 `PermissionMatrixTest` 注入 `RequestMappingHandlerMapping`，枚举本项目（handler 声明类包名 `com.dealtrace`）全部已注册端点（method + path 模板）
- [x] 2.2 定义 permitAll 白名单：`GET /health`、`POST /auth/login`；框架端点（`/error` 等非本项目 controller）按包名过滤排除
- [x] 2.3 断言：每个非白名单端点匿名访问（无 Authorization 头）返回 401 / `code=UNAUTHORIZED`；白名单端点不被安全层提前 401
- [x] 2.4 路径模板含 `{id}` 的端点用占位真实 id 或任意数值构造请求（401 在认证层发生，先于业务处理，不需真实资源）

## 3. 角色专属矩阵（INV-2，显式表）

- [x] 3.1 显式参数化表：ADMIN 专属 7 端点（GET /leads、assign、recall、transfer、POST/GET /admin/accounts、PATCH /admin/accounts/{id}/status）→ Sales 令牌断言 403 / `FORBIDDEN`
- [x] 3.2 显式参数化表：SALES 专属 2 端点（claim、release）→ Admin 令牌断言 403 / `FORBIDDEN`

## 4. 停用 Sales 聚合不变量（INV-3）

- [x] 4.1 停用 Sales 不可登录：`POST /auth/login` 不签发有效令牌
- [x] 4.2 停用 Sales 不可认领：认领公海线索被拒，归属不变
- [x] 4.3 停用 Sales 不可被分配/转移：Admin assign/transfer 给停用 Sales 被拒，归属不落到停用 Sales

## 5. 后端强校验（INV-4）

- [x] 5.1 构造绕过前端的直达越权请求（错误角色 / 无认证）断言后端按矩阵拒绝（401 或 403），不因缺前端控制放行（可由 §2/§3 用例共同覆盖，必要时补 1 条显式声明性断言）

## 6. 执行与安全网

- [x] 6.1 跑 `PermissionMatrixTest`（真 MySQL 8.4，禁 H2 禁 mock）；遵守隔离策略，不与 dev backend smoke 并发，回滚测试内禁 raw TRUNCATE
- [x] 6.2 若 INV-1/INV-2 扫描暴露守卫缺失或越权：定位根因，按安全网补最小生产修复（SecurityConfig / @PreAuthorize），保留 Red→Green 证据；预期不触发（守卫已存在）

## 7. 收尾

- [x] 7.1 `PermissionMatrixTest` 全绿，记录通过数；全量回归 `mvn test` 绿，确认零既有测试期望改动
- [x] 7.2 产出 QA 报告入 `openspec/changes/permission/qa/`；`openspec validate permission --strict` 通过；确认无新增 Flyway 迁移、无新增业务端点
