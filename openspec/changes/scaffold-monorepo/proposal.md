## Why

商迹 DealTrace 当前处于零代码状态，仅有 PRD v3.3 与技术架构文档。后续所有业务变更都要走 OpenSpec → vibe-coding-qa 两阶段流程，并依赖 TDD 的 Red-Green 证据；但在任何业务行为可以测试之前，必须先存在可工作的 backend / frontend 骨架、Flyway 迁移管道、连接云端 MySQL 的集成测试通道，以及统一的 API 响应信封。把这些基础设施和业务行为分到两个 change 里——本 change 只建脚手架、不引入任何业务规则——避免基础设施的工程权衡污染后续 8 个业务域 spec 的 Given-When-Then 行为契约。

## What Changes

- 新建 `backend/` 子项目：Spring Boot 4.0.6 + Spring Security + MyBatis Plus 3.5.16（`mybatis-plus-spring-boot4-starter`）+ Flyway + JWT 过滤骨架（无业务逻辑）；分层目录按 tech-arch §11（Controller / Service / Domain / Mapper / Security / Common）。
- 新建 `frontend/` 子项目：Vite + Vue 3 + TypeScript（strict）+ Arco Design Vue + Vue Router + Pinia + Axios + Vitest + Playwright；Arco 主题初始化与 Axios 拦截器骨架。
- 落定后端版本 pin（已同步至 `docs/技术架构与工程约束.md` §4.2），并在 design.md 中固定前端各组件最新 GA 版本。
- 配置云端 MySQL 8.4 LTS 连接到单一 `dealtrace` 数据库；database / 表 / 列 / 索引 / 外键 / Flyway 文件命名约定按生产规范落定，**禁止**任何环境标签。
- 配置 Flyway 迁移管道并验证可对 `dealtrace` database 正常工作；本 change 不创建任何业务表（含 `users`），业务表的 migration 由 `bootstrap-dealtrace-mvp` 在各 capability spec 中落定。
- 提供 Spring profile 设计（`local` / `ci` / `prod`），所有环境通过 `application-{profile}.yml` + 环境变量切换连接，schema 设计一致。
- 实现全局异常处理器与 `{code, message, data}` 响应信封（tech-arch §6.2），`ErrorCode` 枚举按 §6.3 类目种子。
- 实现 `/api/health` 端点：无需认证、返回标准信封。
- 提供测试基类：默认 `@Transactional` rollback；专用多事务集成测试基类显式禁 rollback + `@AfterEach` 手动 `TRUNCATE`。
- CI 接入作为 follow-up task 留在 `tasks.md` 中占位，本 change 不实现。

## Capabilities

### New Capabilities
- `platform-foundation`: 平台基础行为契约——API 响应信封、未处理异常的统一响应、健康检查端点的可达性。auth/permission 等业务行为不在此范围。

### Modified Capabilities
（无——仓库当前无 `openspec/specs/` 内容。）

## Impact

- **新增代码**：`backend/`、`frontend/` 完整目录树及构建配置（Maven `pom.xml`、`package.json` 等）。
- **新增数据库对象**：云端 MySQL 实例上的 `dealtrace` database（仅含 Flyway 自身的 `flyway_schema_history` 元数据表，无业务表）；运维需提前 `CREATE DATABASE dealtrace` 并授权一个应用账号。
- **新增环境变量约定**：数据库连接凭证（host / port / username / password）走环境变量注入，不入 git。
- **API 表面**：仅新增 `/api/health` 一个端点。
- **后续 change 的依赖**：`bootstrap-dealtrace-mvp` 直接复用本 change 落定的目录结构、命名约定、信封、JWT 骨架与测试基础设施。
- **文档更新**：`docs/技术架构与工程约束.md` §4.2 已在 explore 阶段同步过具体版本号，本 change 实施时无需再改架构文档。
