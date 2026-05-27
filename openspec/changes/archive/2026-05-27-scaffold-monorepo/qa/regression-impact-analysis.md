# Regression Impact Analysis — scaffold-monorepo

## Change Summary

- Requirement / change ID: `scaffold-monorepo`
- Change type: requirement + 新建工程骨架（无既存生产代码）
- Changed behavior: 新增 platform-foundation capability（统一 ApiResponse 信封 / GlobalExceptionHandler / SecurityConfig 401 信封 / `/health` 端点 / Flyway 管道 / 测试基础设施）
- Impacted modules / APIs / pages: 后端 `com.dealtrace.common`、`com.dealtrace.security`、`com.dealtrace.controller`；前端 `src/api/client.ts`、Vitest 单测、Playwright 配置
- Author / owner: 项目作者（单人开发）

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `ConnectivitySmokeTest` | 新增 | platform-foundation Flyway 管道契约（design.md D5） | 验证云 MySQL 8.4 通路与 V1 baseline 写入 | 单测 + Flyway 实际执行（真库） |
| `GlobalExceptionHandlerTest` | 新增 | platform-foundation R1 / R2 | 覆盖参数校验 400 与兜底 500，断言响应体不泄漏 stack | API 层验证 + Mock controller fixture |
| `SecurityScaffoldTest` | 新增 | platform-foundation R1 UNAUTHORIZED 分支 | 覆盖未认证访问 → 401 + 统一信封 | API 层验证 |
| `HealthControllerTest` | 新增 | platform-foundation R3 | 覆盖匿名 GET `/health` → 200 + `status=UP` | API 层验证 |
| `MultiTransactionalBasePoCTest` | 新增 | 测试基础设施 PoC（design.md D8） | 验证跨事务清理逻辑可用，为 bootstrap 阶段 Outbox / 认领并发等场景铺路 | 集成测试 + 真库副作用回收 |
| `client.spec.ts#unwrapEnvelope` | 新增 | platform-foundation R1 在前端的对偶（自动 unwrap data） | 覆盖 SUCCESS 路径与非 SUCCESS 抛 ApiError | 前端单测 |

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `ApiResponse<T>` / `ErrorCode` | 所有 controller 出参形态 | `GlobalExceptionHandlerTest`、`HealthControllerTest` | 已新增（见上） | bootstrap 各 capability spec 必须沿用 |
| `GlobalExceptionHandler` | 所有未捕获异常的对外响应 | `GlobalExceptionHandlerTest` | 已新增 | 新业务异常应通过本 advice 扩展，不要另起 advice |
| `SecurityConfig` + filter / entrypoint | 全部请求的认证链路 | `SecurityScaffoldTest`、`HealthControllerTest`（匿名链路） | 已新增 | auth-account spec 需替换 inMemoryUserDetailsManager 与填充 JWT 解析逻辑 |
| Flyway 管道 | 所有后续 `V*__*.sql` 上线节奏 | `ConnectivitySmokeTest` | 已新增 | bootstrap 引入业务表 migration 时复用同一管道 |
| `frontend/src/api/client.ts` | 所有调用后端 API 的代码 | `client.spec.ts` | 已新增 | bootstrap 前端模块直接 import `apiClient` |
| `MultiTransactionalIntegrationTest` | 跨事务测试可用性 | `MultiTransactionalBasePoCTest` | 已新增 | bootstrap 中的认领并发等场景沿用 |

## Risk Level

- Risk: Low
- Rationale: 本 change 为**新增工程基线**，无既存代码可回归——所有下游冲击都来自"未来 capability 必须沿用"的契约面，而非"既有行为是否被打破"。所有新增契约都已被前述 8 个集成测试 + 2 个前端单测覆盖，且对真 MySQL 8.4 落 Flyway 与 SQL，环境真实度足够。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| backend 全套 | API/integration | 验证所有 platform-foundation 行为契约 + Flyway 管道 | `cd backend && mvn -B test` | PASS（8 / 8） | qa-test-report.md 各里程碑 Green 段 |
| frontend 单测 | Unit | 验证响应信封 unwrap 与错误抛出 | `cd frontend && ./node_modules/.bin/vitest run` | PASS（2 / 2） | qa-test-report.md 里程碑 5 §9.1 |
| frontend 构建 | Build | 验证骨架 + 严格 tsconfig 下可编译 | `./node_modules/.bin/vue-tsc -b` + `./node_modules/.bin/vite build` | PASS | qa-test-report.md 里程碑 5 §8 |
| frontend smoke E2E | E2E | 验证 dev proxy + 后端 `/health` 连通 | `pnpm test:e2e`（需双 dev server + chromium） | DEFERRED | qa-test-report.md 里程碑 5 §9.2 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| `tests/e2e/health.spec.ts` | DEFERRED | 需要并行启动 `mvn spring-boot:run` + `pnpm dev` 与 `pnpm exec playwright install chromium` | 本地联调阶段触发一次 smoke；CI 接入由 §11.1 决策推后 | dev proxy 配置 + chromium 网络栈未在自动化中验证；后端 / 前端单边契约已在 API 层覆盖，连通性是配置而非业务逻辑 |

## Runtime QA Validation

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| Yes | 后端 Flyway 是首次对云 MySQL 8.4 写入；需确认管道实际执行 | `mvn -B test` 命令日志中 Flyway 输出 | PASS | qa-test-report.md 里程碑 1 §3 Green 段 |
| No | 前端 dev server 单跑可观察性低，无业务页面 | — | — | — |

## Regression Conclusion

- Overall result: PASS
- Changed behavior covered: platform-foundation R1（统一信封 + 401）/ R2（兜底 500 不泄漏 stack）/ R3（`/health` 匿名 + UP） 三项行为契约全部以集成测试覆盖；Flyway 管道与跨事务测试基类以 PoC 覆盖；前端响应信封 unwrap 以单测覆盖
- Directly impacted old behavior covered: 无既存代码
- Historical defects considered: 无（首次 change）
- Uncovered test points: Playwright smoke E2E（DEFERRED）
- Unresolved prerequisite blockers: 无（云 MySQL 已就绪、JDK / Maven / Node / pnpm 全部就位）
- Remaining risks: 见 `qa-test-report.md` 「剩余风险与后续工作」段（Mockito self-attaching 警告、Flyway 对 8.4 的"未验证"提示，均非阻塞）
