# Lightweight Test Design

## Context

- Requirement / Spec: `openspec/changes/lead-core/specs/lead/spec.md`（9 个 ADDED requirement：R1 字段集 / R2 business_year 服务端生成 / R3 必填+格式校验 / R4 Admin&Sales 归属分支 / R5 三元组三态 / R6 预检 / R7 详情+列表权限 / R8 创建触发系统日志 / R9 不在 lead-core 范围内的能力）。权威依据回溯到 PRD §7.3-7.11 + §8.2-8.4 + §11.3-11.4。
- Change summary: lead capability 第一站；建表 + 5 端点（POST 创建 / GET 详情 / GET mine / GET 全局 Admin only / GET duplicate-check）；扩展 SystemLogPort 加 summary 入参；不交付公海视图 / 认领 / 阶段 / 闭单。
- Target modules / APIs / pages:
  - 后端新增 `com.dealtrace.lead.**`（entity / mapper / dto / service / controller）+ `PhoneValidator` + `LeadDuplicateService`
  - 后端修改：`SystemLogPort` 接口 + `JdbcSystemLogPort` + `Slf4jSystemLogPort` + `ErrorCode` + `GlobalExceptionHandler` + `SecurityConfig`（加 `@EnableMethodSecurity`）
  - 数据库：V5__lead.sql（14 字段 + 3 索引 + 双 FK）
- Test environment / constraints:
  - 真 MySQL 8.4；DB_HOST 等环境变量已配置
  - 测试 profile = `test`
  - 既有 79 项测试基线全 Green
  - FK 对 `MultiTransactionalIntegrationTest.truncateAfterEach` 无影响（已用 `SET FOREIGN_KEY_CHECKS = 0` 包裹）

## Input Sources Checked

- [x] PRD §7.3-7.11 + §8.2-8.4 + §11.3-11.4
- [x] Existing baseline: customer / auth-account / system-log / platform-foundation 现状
- [x] Data model / 字段规则：design D3 表结构
- [x] API contract：design D4 5 个端点；GlobalExceptionHandler 已 mapping VALIDATION_ERROR + DUPLICATE_CUSTOMER；本 change 追加 DUPLICATE_ACTIVE_LEAD / DUPLICATE_WON_LEAD
- [x] Code structure：沿用 customer 同款 entity/mapper/dto/service/controller 分层
- [x] Existing tests：`JdbcSystemLogPortExceptionTest` matcher 受 5 参扩展影响；`AdminAccountStatusControllerTest` 的 @MockitoSpyBean 走 4 参 default 自动委派
- [x] Test data：合法 USCI / 合法手机号 / 真实 customer id 必须先建好（fixture）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| SystemLogPort 4 参签名 | system-log change 已落 | lead-core design D13 需要 summary 入参 | extends（4 参 default 委派、5 参为主） | system-log change D7 预告 + 本 change design D13 | Proceed —— spec R1 已说 summary MAY persist，本扩展兑现 |
| 系统日志写入 summary | system-log spec R1 列 summary 为可选 | lead-core spec R8 要求 LEAD_CREATE 触发并写 summary | extends | spec 一致 | Proceed |
| 并发同三元组创建 | 无 | lead-core spec R5 显式接受罕见双行 | extends（显式权衡） | spec R5 + design D6 | Proceed |
| 无权访问详情返回 | 无 | lead-core spec R7 统一 404 | extends（不泄漏存在性） | design D9 | Proceed |
| ErrorCode 追加 | tech-arch §6.3.6/7 占位 | lead-core design D11 | extends | tech-arch | Proceed |
| SecurityConfig 启用方法级权限 | 当前未启用 `@EnableMethodSecurity` | lead-core design D12 需 `@PreAuthorize` 区分 `/leads`（Admin only）与 `/leads/mine`（all） | extends（首次启用） | design D12 | Proceed —— 不破坏既有 path-based |

无 conflicts；gate 放行。

## Test Points

| Test point | Source | Design method | Test layer | Input | Expected | Assertion | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TP1 PhoneValidator 11 位手机号 | spec R3 + design D7 | 等价 + 边界 | Unit | `13812345678` / 首位非 1 / 第 2 位非 3-9 / 含字母 | true / false / false / false | bool | P0 | `PhoneValidatorTest` |
| TP2 PhoneValidator 座机 | spec R3 + design D7 | 等价 | Unit | `010-12345678` / `0571-12345678-123` / `12345678`（无区号） | true / true / true | bool | P0 | 同上 |
| TP3 PhoneValidator 边界 | spec R3 | 边界 | Unit | null / 全空白 / trim 前后等价 | false / false / 一致 | bool | P0 | 同上 |
| TP4 三元组三态决策 | spec R5 | 决策表 | API/integration | 5 种状态组合 | 对应错误码 / 允许 | code | P0 | `LeadDuplicateServiceTest` |
| TP5 创建：Sales 默认归自己 | spec R4 | 等价 | API/integration | Sales token + 不含 ownerSalesId | owner_sales_id = sales.id, stage = UNTOUCHED | DB | P0 | `LeadControllerCreateTest` |
| TP6 创建：Sales 入池 | spec R4 | 等价 | API/integration | Sales token + assignToPool=true | owner_sales_id = NULL | DB | P0 | 同上 |
| TP7 创建：Sales 试图指定他人被忽略 | spec R4 | 反向 | API/integration | Sales token + ownerSalesId=otherSalesId | owner = self（忽略 design D5） | DB | P0 | 同上 |
| TP8 创建：Admin 指定 ENABLED Sales | spec R4 | 等价 | API/integration | Admin + ownerSalesId=enabledSalesId | owner_sales_id = enabledSalesId | DB | P0 | 同上 |
| TP9 创建：Admin 指定 DISABLED 被拒 | spec R4 | 反向 | API/integration | Admin + ownerSalesId=disabledSalesId | VALIDATION_ERROR | code | P0 | 同上 |
| TP10 创建：Admin 不指定入池 | spec R4 | 等价 | API/integration | Admin + 不含 ownerSalesId | owner_sales_id = NULL | DB | P0 | 同上 |
| TP11 创建：business_year 服务端 | spec R2 | 等价 | API/integration | 创建任意线索 | business_year = YEAR(now()) | DB | P0 | 同上 |
| TP12 创建：必填校验 | spec R3 | 边界 | API/integration | 缺 customerId / businessType / contactName / contactPhone | 400 VALIDATION_ERROR | code | P0 | 同上 |
| TP13 创建：电话格式失败 | spec R3 | 反向 | API/integration | `"abc"` / `"123"` | 400 VALIDATION_ERROR | code | P0 | 同上 |
| TP14 创建：customerId 不存在 | spec R3 | 反向 | API/integration | customerId = 99999 | 400 VALIDATION_ERROR | code | P0 | 同上 |
| TP15 创建：非法 businessType | spec R3 | 反向 | API/integration | businessType="WRONG" | 400 VALIDATION_ERROR | code | P0 | 同上 |
| TP16 创建：三元组进行中拒 | spec R5 | 决策表 | API/integration | 先 INSERT 进行中 → 再创建 | 400 DUPLICATE_ACTIVE_LEAD | code | P0 | 同上 |
| TP17 创建：三元组已赢单拒 | spec R5 | 决策表 | API/integration | 先 INSERT WON → 再创建 | 400 DUPLICATE_WON_LEAD | code | P0 | 同上 |
| TP18 创建：仅已流失允许 | spec R5 | 决策表 | API/integration | 先 INSERT LOST → 再创建 | 200 SUCCESS + stage=UNTOUCHED | DB | P0 | 同上 |
| TP19 创建：不同类型不算重复 | spec R5 | 等价 | API/integration | 同客户/年度 不同 businessType | 200 SUCCESS | DB | P1 | 同上 |
| TP20 创建：跨年度同三元组允许 | spec R5 | 等价 | API/integration | 手动设 business_year=2025 + 创建 2026 | 200 SUCCESS | DB | P1 | 同上 |
| TP21 创建后 LEAD_CREATE 日志 + summary | spec R8 | 等价 | API/integration | 创建一条线索 | system_log 多一行，含 summary 含客户/类型/归属 | DB + jdbc 查 system_log.summary | P0 | 同上 |
| TP22 校验失败不写日志 | spec R8 | 反向 | API/integration | 提交校验失败请求 | system_log 行数不变 | DB count | P1 | 同上 |
| TP23 详情：Admin 任意 | spec R7 | 等价 | API/integration | Admin 请求他人线索 | 200 + 完整 14 字段 + customerName/USCI | view | P0 | `LeadControllerDetailListTest` |
| TP24 详情：Sales 自己 | spec R7 | 等价 | API/integration | Sales 请求自己的 | 200 | view | P0 | 同上 |
| TP25 详情：Sales 他人 → 404 | spec R7 | 反向 | API/integration | Sales 请求他人线索 | 404 NOT_FOUND（message 与"不存在"一致） | code + message | P0 | 同上 |
| TP26 详情：不存在 → 404 | spec R7 | 反向 | API/integration | 任意角色请求 id=999999 | 404 NOT_FOUND | code | P0 | 同上 |
| TP27 mine：仅返自己 | spec R7 | 等价 | API/integration | 3 行（2 给 sales A / 1 给 B），sales A 查 mine | 2 行 + 都属 A | array | P0 | 同上 |
| TP28 全局：仅 Admin | spec R7 | 反向 | API/integration | Sales 调 GET /leads | 403 FORBIDDEN | code | P0 | 同上 |
| TP29 全局：Admin 任意 | spec R7 | 等价 | API/integration | Admin 调 GET /leads | 200 + 所有线索 | array | P1 | 同上 |
| TP30 列表 LIMIT 50 + desc | spec R7 | 边界 | API/integration | 插入 60 行 | length=50 + 不含最早 10 条 | array | P1 | 同上 |
| TP31 预检：空集 | spec R6 | 边界 | API/integration | 三元组无既有 | canCreate=true + hist=[] | response | P0 | `LeadDuplicateCheckTest` |
| TP32 预检：进行中阻塞 | spec R6 | 决策表 | API/integration | 三元组有进行中 | canCreate=false + blockingReason="DUPLICATE_ACTIVE_LEAD" | response | P0 | 同上 |
| TP33 预检：仅已流失 → 历史展示 | spec R6 | 等价 | API/integration | 三元组有 LOST × 2 | canCreate=true + hist.length=2 倒序 | response | P0 | 同上 |
| TP34 预检不写持久化 | spec R6 | 反向 | API/integration | 预检任意次 | lead 行数 + system_log 行数不变 | DB count | P0 | 同上 |
| TP35 R9 反向：claim/stage/win/lose/release/assign 端点未映射 | spec R9 | 反向 | API/integration | 各 HTTP 方法访问 | code != SUCCESS | code | P1 | `LeadNotInScopeTest` |
| TP36 SystemLogPort 5 参签名扩展回归 | design D13 | 等价 | Unit / API | JdbcSystemLogPortExceptionTest matcher 扩到 8 args；既有 4 项 Test + AdminAccountStatusControllerTest 8 项全 Green | 通过 | mvn test | P0 | system-log 既有测试 |

## TDD Candidates

| Test point | Initial failing test | Why it should fail | Expected Red failure reason | Minimal behavior to pass |
| --- | --- | --- | --- | --- |
| TP1-TP3 PhoneValidator | `PhoneValidatorTest` | 先建 stub PhoneValidator.isValid → return false | 行为失败：合法手机号 / 座机 / trim 返 false | 实现 MOBILE / LANDLINE 两正则 + strip |
| TP4 三元组三态 | `LeadDuplicateServiceTest` | 先建 stub LeadDuplicateService.check → 总返 `allow` | 行为失败：进行中 / 已赢单也被允许 | 实现三态决策 |

API 路径（TP5-TP35）按 Non-TDD Exception：全新文件 + spec 1:1，按 customer 套路一次性 Green。

## Non-TDD Exceptions

| Scope | Reason | Alternative validation |
| :--- | :--- | :--- |
| TP5-TP35（除 TDD 候选外的所有 API） | 全新 controller/service/dto；spec ↔ 测试 1:1；形式化 Red 净收益微小 | 实现完跑全套；任一失败即修复 |
| SystemLogPort 5 参扩展 | 单方法改签名 + 两 impl 同步 + 一处 mock matcher 扩 args | TP36：现有 system-log + auth-account 测试全 Green |
| ErrorCode + GlobalExceptionHandler 追加 | 单 case switch 追加 | TP16/TP17 实际请求 HTTP 400 验证 |
| SecurityConfig 启用方法级权限 | 单注解 + listAll() 加 @PreAuthorize | TP28（Sales 403）+ TP23/TP29（Admin 200）实际请求验证 |

## Prerequisite Blockers

| Blocker | Affected TP | Status |
| --- | --- | --- |
| 真 MySQL 8.4 + DB env vars | TP5+ | RESOLVED |
| 已有合法 customer（USCI 通过校验位）作 fixture | TP5+ | 测试 fixture 自建（VALID_USCI = `91110000123456789Q` 等三组） |
| sales / admin accounts fixture | TP5+ | 测试 fixture 自建 |

## Coverage Closure

- [x] In-scope TP 都有 coverage artifact
- [ ] 执行结果待 apply 阶段回填
- [ ] Red 证据：PhoneValidator + LeadDuplicateService 走 RED→GREEN
- [x] 不把 syntax / fixture / env failure 当 Red
- [x] Coverage 映射到 project-relative 测试路径
- [x] requirement conflicts 已 resolve（gate 表）
- [x] runtime QA 仅作 smoke

## Notes

- Uncovered: 无
- Remaining risks:
  - 并发查重双行（spec R5 显式接受）
  - utf8mb4_unicode_ci 对英文 customer 名大小写不敏感（沿袭 customer change D1 trade-off）
  - SystemLogPort 4 参 default 委派对 mockito spy 的兼容性（Mockito 5 支持 default method spy，但若有版本差异需关注）
