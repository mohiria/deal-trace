# Lightweight Test Design

## Context

- Requirement / Spec: `openspec/changes/customer/specs/customer/spec.md`（7 个 ADDED requirement：R1 字段集 / R2 name trim 查重 / R3 USCI 归一化先行 / R4 USCI 全局唯一含并发 / R5 Admin & Sales 同等 / R6 搜索统一端点 / R7 不可编辑删除 + 不生成系统日志）。权威依据回溯到 PRD §7.2 / §8.1 / §11.2 与 tech-arch §6.3.5 / §7.2.2-3 / §8.1。
- Change summary: `openspec/changes/customer/` —— 落地 `customer` 表 + 创建 / 搜索 API；DUPLICATE_CUSTOMER 加入 ErrorCode；不交付编辑/删除/系统日志。
- Target modules / APIs / pages:
  - `backend/src/main/resources/db/migration/V4__customer.sql`
  - `backend/src/main/java/com/dealtrace/customer/**`（entity / mapper / dto / service / controller）
  - `backend/src/main/java/com/dealtrace/customer/service/UsciValidator.java`（纯算法）
  - `backend/src/main/java/com/dealtrace/common/ErrorCode.java`（追加 DUPLICATE_CUSTOMER）
- Test environment / constraints:
  - 真 MySQL 8.4（tech-arch §12），database `dealtrace`，DB_HOST 等环境变量已配置
  - 测试 profile = `test`
  - 单事务用 `IntegrationTest`、多事务（并发竞态）用 `MultiTransactionalIntegrationTest`
  - 既有 48 项测试基线全 Green，本 change 不应让任一项退化

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria —— spec.md + PRD §7.2 / §8.1 / §11.2
- [x] Existing behavior baseline —— auth-account / system-log / platform-foundation 现状
- [x] Data model / field rules / CRUD matrix —— design D7 表结构 + 仅 INSERT + SELECT
- [x] API contract / auth rules / error shape —— design D8 + 路径放行（默认 authenticated 即可）
- [x] UI states —— 无前端
- [x] Code structure —— 沿用 auth-account 的 entity/mapper/controller/service 分层
- [x] Existing tests —— 检查 ErrorCode 修改对 `GlobalExceptionHandler` 路径的影响：仅追加枚举值，handler `switch` 已有 default 兜底
- [x] Test data —— USCI 真实合法样本由本 design 预计算

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 客户名称 "完全一致" 判定 | 无（首落地） | PRD §7.2.4 字面 "完全一致" | extends（按 utf8mb4_unicode_ci 等价放宽，design D1） | design D1 显式 trade-off | Proceed —— 已留档；中文为主无影响 |
| USCI 校验位算法 | 无（首落地） | PRD §7.2.7 / §8.1.4「GB 32100-2015 标准格式 + 校验位」 | extends | design D3 + UsciValidator | Proceed |
| 创建客户是否记系统日志 | PRD §7.8 系统日志触发事件清单不含创建客户 | spec R7 显式 NO | extends（明文确认） | design Non-Goals + spec R7 | Proceed |
| 客户编辑 / 删除 | PRD 未授予 | spec R6 negative scenario | extends（显式拒绝） | spec R6 + design Non-Goals | Proceed |
| ErrorCode 枚举追加 DUPLICATE_CUSTOMER | tech-arch §6.3.5 已占位、ErrorCode.java javadoc 已声明"由 capability 追加" | design D9 | extends（履行 javadoc 承诺） | tech-arch + 既有 javadoc | Proceed |

无 `conflicts`，gate 放行。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TP1 USCI 归一化（trim+upper） | spec R3 + design D3 | 等价类 + 边界 | Unit | `UsciValidator.normalize("  91110000123456789q  ")` | `"91110000123456789Q"` | 返回字符串完全相等 | P0 | `UsciValidatorTest#normalize_trimsAndUppers` |
| TP2 USCI normalize(null) 不抛 | spec R3 边界 | 边界 | Unit | `UsciValidator.normalize(null)` | 返回 `null`，不抛 | 无异常 + 返回 null | P1 | `UsciValidatorTest#normalize_null_returnsNull` |
| TP3 USCI 合法样本通过 | spec R3 + GB 32100 | 真实数据等价 | Unit | 至少 3 组真实合法 USCI（含本 design 预计算） | `isValid` 返回 true | true | P0 | `UsciValidatorTest#isValid_realSamples_true` |
| TP4 USCI 长度 != 18 不通过 | spec R3 场景 2 | 边界 | Unit | "9111000012345678"(17) / "91110000123456789QX"(19) | false | false | P0 | `UsciValidatorTest#isValid_wrongLength_false` |
| TP5 USCI 字符集含禁用字符 | spec R3 + GB 32100 字符集（去 IOSVZ） | 边界 | Unit | "9111000012345678IQ"（含 'I'） | false | false | P0 | `UsciValidatorTest#isValid_invalidCharset_false` |
| TP6 USCI 校验位错 | spec R3 场景 3 | 决策表 | Unit | 合法样本末位改成另一个合法字符（破坏校验位） | false | false | P0 | `UsciValidatorTest#isValid_wrongCheckDigit_false` |
| TP7 创建成功字段集 | spec R1 + R5（SALES）/ R3 | 等价 | API/integration | Sales token + 合法 name + 带空格小写 USCI | 200 + 4 字段视图 + DB 持久化为归一化形态 | response.data 字段集；DB 行 usci 已大写无空格 | P0 | `CustomerControllerCreateTest#sales_createsCustomer_returnsView` |
| TP8 Admin 创建成功 | spec R5 | 等价 | API/integration | Admin token | 200 | success | P1 | `CustomerControllerCreateTest#admin_createsCustomer` |
| TP9 匿名创建被拒 | spec R5 场景 3 | 反向 | API/integration | 无 Authorization 头 | 401 + UNAUTHORIZED | response.code | P0 | `CustomerControllerCreateTest#anonymous_rejected` |
| TP10 空 name 拒绝 | spec R2 场景 1 | 边界 | API/integration | name="   "（全空格） | 400 + VALIDATION_ERROR | response.code + DB 行未增 | P0 | `CustomerControllerCreateTest#blankName_rejected` |
| TP11 空 USCI 拒绝 | spec R3 场景 4 | 边界 | API/integration | usci="" | 400 + VALIDATION_ERROR | 同上 | P0 | `CustomerControllerCreateTest#blankUsci_rejected` |
| TP12 USCI 长度错 / 校验位错拒绝 | spec R3 场景 2/3 | 边界 | API/integration | 长度 17 / 校验位错 | 400 + VALIDATION_ERROR | 同上 | P0 | `CustomerControllerCreateTest#invalidUsci_rejected` |
| TP13 USCI 重复（含归一化等价）拒绝 | spec R4 场景 1 | 等价 | API/integration | 先插一个；再以大小写不同的同 USCI 提交 | 400 + DUPLICATE_CUSTOMER | response.code + 行数=1 | P0 | `CustomerControllerCreateTest#duplicateUsci_rejected` |
| TP14 name trim 后重复拒绝 | spec R2 场景 2 | 边界 | API/integration | 先插一个；再提交 "  同名  " | 400 + DUPLICATE_CUSTOMER | response.code + 行数=1 | P0 | `CustomerControllerCreateTest#duplicateNameAfterTrim_rejected` |
| TP15 不同公司名不算重复 | spec R2 场景 3 | 等价 | API/integration | 两个不同 name + 两个不同 USCI | 都 200 | 行数=2 | P1 | `CustomerControllerCreateTest#differentNames_allowed` |
| TP16 并发同 USCI 一胜一负 | spec R4 场景 2 | 并发 | API/integration | 两线程同时 service.create 同 USCI | 1 success + 1 DUPLICATE_CUSTOMER | 不出现 INTERNAL_ERROR | P0 | `CustomerConcurrentInsertTest#sameUsci_oneWinsOneFails` |
| TP17 搜索：无 keyword 返 50 行倒序 | spec R6 场景 1 | 边界 | API/integration | 插 60 行 → GET 无 keyword | data.length=50 + 倒序 + 不含最早 10 条 | size + ordering | P0 | `CustomerControllerSearchTest#noKeyword_returnsLatest50` |
| TP18 搜索：name 子串命中 | spec R6 场景 2 | 等价 | API/integration | keyword 命中 name | 包含该客户 | data 含命中 | P0 | `CustomerControllerSearchTest#nameSubstring_matches` |
| TP19 搜索：USCI 子串命中 | spec R6 场景 3 | 等价 | API/integration | keyword 命中 USCI | 包含该客户 | data 含命中 | P0 | `CustomerControllerSearchTest#usciSubstring_matches` |
| TP20 搜索：无命中返 [] | spec R6 场景 4 | 边界 | API/integration | keyword 不存在 | 200 + data=[] | 非 404、非 null | P1 | `CustomerControllerSearchTest#noMatch_returnsEmptyArray` |
| TP21 搜索：keyword 全空白等价无 keyword | spec R6 + design D2 (trim) | 边界 | API/integration | keyword="   " | 同无 keyword 行为 | data.length=full | P1 | `CustomerControllerSearchTest#blankKeyword_equalsNoKeyword` |
| TP22 搜索：匿名 401 | spec R6 场景 5 | 反向 | API/integration | 无 token | 401 | response.code | P0 | `CustomerControllerSearchTest#anonymous_rejected` |
| TP23 不存在 PUT/PATCH/DELETE 端点 | spec R6 negative | 反向 | API/integration | PUT/PATCH/DELETE /customers/{id} | code != SUCCESS（具体 404/405/FORBIDDEN 任一）+ DB 行不变 | response.code + DB 行未变 | P1 | `CustomerImmutabilityTest#noEditOrDelete` |
| TP24 创建客户不写系统日志 | spec R7 | 反向 | API/integration | POST 成功创建后查 system_log | 行数与创建前相同 + spy.record() 0 次 | count + spy | P0 | `CustomerSystemLogQuietTest#create_doesNotEmitSystemLog` |

## TDD Candidates

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| TP1-TP6 UsciValidator | `UsciValidatorTest` 全部用例 | 第一步先建 stub UsciValidator（normalize 直接返回入参、isValid 直接 return false） | 行为失败：normalize 不去空白不大写、isValid 对合法样本返 false | 实现 normalize（trim+upper Locale.ROOT）+ isValid（charset 索引 + 加权和 + mod 31） | 无 |
| TP16 并发竞态 | `CustomerConcurrentInsertTest#sameUsci_oneWinsOneFails` | 先建空 CustomerService.create（不 catch DuplicateKeyException、不查重） | 行为失败：两线程都 INSERT 成功（无 unique 约束兜底前）或都 throw 未翻译异常 | 加 UNIQUE 索引 + try/catch 翻译 DuplicateKeyException → DUPLICATE_CUSTOMER | 单事务 TP13 验证 app-level check 路径 |

其余 API 测试（TP7-TP15, TP17-TP24）走 Non-TDD：spec → controller/service → test 一次 Green，理由见下节。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| TP7-TP15 / TP17-TP24 API 路径 | 全新 controller/service，无既有行为可破坏；spec 与测试 1:1；形式化 Red（空 controller → 失败 → 实现）净收益微小 | 实现完成立即跑全套，任一失败即修复并 commit；mvn test 48+24 全 Green 才进 archive | 极小：spec 与测试 1:1 映射 |
| ErrorCode 枚举追加 DUPLICATE_CUSTOMER | 单行枚举追加 | 编译期保证 + `GlobalExceptionHandler.handleBusiness` switch 已有 default → INTERNAL_SERVER_ERROR 兜底；DUPLICATE_CUSTOMER 需走 VALIDATION_ERROR 等同 400 路径，故 handler 也要追加 DUPLICATE_CUSTOMER → BAD_REQUEST 映射 | 测试 TP13/TP14/TP16 实际请求覆盖 HTTP 400 期望 |
| SecurityConfig 路径核查 | 无代码变更（默认 authenticated() 已覆盖） | 通过 TP9 / TP22（匿名 401）证明；通过 TP7-TP8（Admin/Sales 200）证明无角色误限 | 零 |
| V4 migration | 一次性建表 | TP17 在 60 行插入后行为正确 = 表结构正确 + 索引正确（写入不冲突）；JDBC `SHOW CREATE TABLE` smoke 二次验证 | 极小 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 真 MySQL 8.4 可达（DB_HOST 等环境变量） | TP7-TP24 全部 | 已配置 | RESOLVED |
| 真实合法 USCI 样本（GB32100 校验位计算正确） | TP3 / TP7 / TP13 / TP14 / TP16 | 本 design 预计算 3 组：`91110000123456789Q`、`91110108551385082Q`、`91440300083000123J` | RESOLVED |

## Coverage Closure

- [x] Each in-scope executable test point has a coverage artifact after prerequisites are available.
- [ ] New or modified tests were executed and results were recorded —— apply 阶段 `mvn test` 后回填
- [ ] Red tests failed for the expected behavior reason when strict TDD applies —— UsciValidator + 并发竞态两处见 TDD Candidates；其余 Non-TDD Exceptions 已备案
- [x] Syntax, import, fixture, setup, or environment failures were not counted as valid Red evidence.
- [ ] Commands, reports, CI links, logs, screenshots, traces, or responses are recorded as execution evidence when relevant —— QA 报告回填
- [x] Behavioral evidence describes what assertion proved.
- [x] Coverage evidence maps each covered test point to a project-relative test path and optional `#testName`.
- [x] Uncovered test points and unresolved prerequisite blockers are listed explicitly.
- [x] Requirement conflicts are resolved or explicitly listed as BLOCKED.
- [x] Runtime QA validation, if performed, is treated only as availability smoke evidence and not counted as Unit/API/E2E business coverage. —— 1.2 / 3.8 smoke 显式标注。

## Notes

- USCI 真实合法样本（GB 32100-2015 校验位已校验）：
  - `91110000123456789Q`（前 17 位 `91110000123456789`，校验位计算结果 'Q'）
  - `91110108551385082Q`（前 17 位 `91110108551385082`，校验位计算结果 'Q'）
  - `91440300083000123J`（前 17 位 `91440300083000123`，校验位计算结果 'J'）
- Remaining risks:
  - utf8mb4_unicode_ci 对英文公司名大小写不敏感（design D1 接受）
  - `DuplicateKeyException.getMessage()` 内含索引名依赖 MySQL connector-j 行为，未来 driver 升级需回归测试
- Execution evidence / Behavioral evidence / Coverage evidence: 见 Test Points 表，apply 阶段回填执行结果。
