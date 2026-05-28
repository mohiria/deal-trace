# Regression Impact Analysis

## Change Summary

- Requirement / change ID: `openspec/changes/customer/`
- Change type: requirement + code（doc 无修改，与 customer 直接相关的 tech-arch 表格无需调整）
- Changed behavior:
  - 新增 customer capability：3 字段（name / usci / createdAt）+ 创建 API + 搜索 / 列表 API
  - USCI 归一化先行 → GB 32100-2015 校验位算法（自实现）→ 应用层 + DB 双层唯一性
  - `ErrorCode` 枚举追加 `DUPLICATE_CUSTOMER`，`GlobalExceptionHandler.handleBusiness` switch 同步追加 → 400 映射
- Impacted modules / APIs / pages:
  - 后端新增 `com.dealtrace.customer.*`（entity / mapper / dto / service / controller）+ `UsciValidator`
  - 数据库新增 `customer` 表（含 `uk_customer_usci` / `uk_customer_name`）
  - `ErrorCode` 枚举追加 1 个常量；`GlobalExceptionHandler` switch case 修改 1 处
  - SecurityConfig 无需修改（`/customers/**` 落在 `anyRequest().authenticated()` 分支）
- Author / owner: customer change

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `UsciValidatorTest` | Add（新文件，10 cases，纯单元） | spec R3 + design D3 + GB 32100-2015 | 校验位算法是 capability 核心，且为纯函数最适合 TDD；首先写测试再实现 | normalize 6 + isValid 5 = 11 case 覆盖归一化 / 长度 / 字符集 / 校验位 |
| `CustomerControllerCreateTest` | Add（新文件，10 cases） | spec R1 / R2 / R3 / R4（单事务）/ R5 | 覆盖完整创建路径：字段集 / Admin & Sales / 匿名 / 空 name / 空 USCI / 长度错 / 校验位错 / 重复 USCI / 重复 name / 不同公司名 | 10 个 API 集成 case |
| `CustomerControllerSearchTest` | Add（新文件，6 cases） | spec R6 | 覆盖搜索：无 keyword 返 50 行倒序 / name 子串 / USCI 子串 / 无命中返 [] / 空白 keyword 等价 / 匿名 401 | 6 个 API 集成 case |
| `CustomerConcurrentInsertTest` | Add（新文件，1 case，多事务） | spec R4 场景 2 | 并发竞态：两线程同 USCI 一胜一负，不外泄 INTERNAL_ERROR | 1 个跨事务集成 case |
| `CustomerSystemLogQuietTest` | Add（新文件，1 case，多事务 + spy） | spec R7 | 验证创建客户不触发 systemLogPort.record + system_log 行数不变 | 1 个集成 case，含 spy.verify(never) |
| `CustomerImmutabilityTest` | Add（新文件，3 cases） | spec R6 negative | 验证 PUT/PATCH/DELETE 未映射成功路径 | 3 个反向 case，确保 MVP 未暴露编辑/删除端点 |
| `ErrorCode.java` | Modify | tech-arch §6.3.5 + design D9 | 追加 `DUPLICATE_CUSTOMER` 常量；javadoc 同步更新 | 无新增测试；编译期保证 + 业务测试 TP13/TP14/TP16 用到 |
| `GlobalExceptionHandler.handleBusiness` | Modify | spec R4 / design D9 | switch case 追加 `DUPLICATE_CUSTOMER → HttpStatus.BAD_REQUEST`；否则 default 兜底会返 500 不符合 spec | 已被 TP13 / TP14 / TP16 实际请求覆盖 HTTP 400 期望 |

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `V4__customer.sql` | Flyway 启动期建表；所有 @SpringBootTest 加载新 schema | 全套 mvn test | `CustomerControllerSearchTest` 60 行批插间接验证表结构与 UNIQUE 索引 | 真 MySQL 8.4，无 H2 |
| `UsciValidator`（新增） | 归一化 + 校验位 | —— | `UsciValidatorTest` 10 case；TDD Red→Green | 纯函数无副作用 |
| `CustomerService` / Controller（新增） | 创建 + 搜索路径 | —— | 5 个集成测试类共 21 case | 全新文件，无既有行为可破坏 |
| `ErrorCode` 追加 DUPLICATE_CUSTOMER | enum 多一项 + handleBusiness switch 多 1 case | `GlobalExceptionHandlerTest` 既有 2 case（无业务专属错误码） | TP13/TP14/TP16 隐式覆盖 400 映射 | 既有 ApiResponse / BusinessException 流程不变 |
| `SecurityConfig` 检查 | 无代码变更：`/customers/**` 默认 `authenticated()` | `SecurityPathRuleTest` 既有 5 case | TP9（匿名 POST 401）+ TP22（匿名 GET 401）实际证明 | 零代码修改 |
| `GlobalExceptionHandler` switch case | DUPLICATE_CUSTOMER → 400 | `GlobalExceptionHandlerTest` 既有 2 case | TP13 / TP14 / TP16 显式断言 status 400 + code DUPLICATE_CUSTOMER | 修改最小 |

## Risk Level

- Risk: Low
- Rationale:
  - 新增 capability，无既有 capability 行为入侵
  - 数据库 V4 是新表，无 schema 兼容性
  - ErrorCode + GlobalExceptionHandler 单点修改（追加 case），既有错误码映射 100% 不变
  - SecurityConfig 零修改
  - 并发竞态由 UNIQUE 索引 + try/catch 双层兜底，已显式测试
  - 自实现 USCI 算法用 3 组真实合法样本 + 多组边界 case 验证

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| `UsciValidatorTest` | Unit | 算法正确性（TDD Red→Green） | `mvn test -Dtest=UsciValidatorTest` | PASS | 10/10 Green，耗时 ~0.2s |
| `com.dealtrace.customer.*Test` | Unit + API/integration | customer 全部 21 case | `mvn test -Dtest=com.dealtrace.customer.*Test` | PASS | 21/21 Green |
| 全套 `mvn test` | Regression | 既有 48 + 新增 31 不退化 | `mvn test` | PASS | `Tests run: 79, Failures: 0, Errors: 0, Skipped: 0`；BUILD SUCCESS |
| `mvn verify` | Full lifecycle + spring-boot:repackage | CI 对齐 | `mvn verify` | PASS | `BUILD SUCCESS Total time: 01:24 min` |
| 手工 smoke | Runtime availability | 真实 HTTP + JDBC 验证 | `curl POST /api/customers` + JDBC `SHOW CREATE TABLE` | PASS | 5/5 路径符合预期；`SHOW CREATE` 字段顺序 / 类型 / 索引 / collation 与 design D7 完全一致 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| 中文公司名走 HTTP 路径的手工 smoke | Git Bash on Windows CP936 编码 → 请求体非 UTF-8 → Jackson 解析失败 500 | shell-level encoding（与 customer 代码无关） | 用户用 Postman / IDE HTTP client / UTF-8 编码的 curl 文件验证 | 极小：`CustomerControllerCreateTest#sales_createsCustomer_returnsViewAndPersistsNormalized` 已用「中国建筑设计研究院」中文 name 走 MockMvc 通过；UTF-8 编码正确时端点工作正常（已由集成测试证明） |

## Runtime QA Validation

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| Yes | 验证 V4 在真 MySQL 实例可 apply、HTTP 端点真实可达 | 启动后端 → admin 登录 → POST 创建（USCI 含空格小写）→ GET 搜索 → POST 重复 → POST 校验位错 → JDBC `SHOW CREATE TABLE` | PASS | curl 输出 5/5 期望；JDBC 输出 schema 完全匹配；customer 行 USCI 已归一化为 `91110000123456789Q` |

## Regression Conclusion

- Overall result: PASS
- Changed behavior covered:
  - spec R1 字段集 / R2 name trim 查重 / R3 USCI 归一化先行 + 校验位 / R4 全局唯一含并发 / R5 Admin & Sales 同等 + 匿名 401 / R6 搜索统一端点 5 个场景 / R7 不可编辑删除 + 不生成系统日志 —— 全部由 21 项自动化测试 + smoke 覆盖
- Directly impacted old behavior:
  - platform-foundation ApiResponse 信封 / GlobalExceptionHandler 流程：handleBusiness switch 多 1 case，既有 5 个映射不变；`GlobalExceptionHandlerTest` 2 项继续 Green
  - auth-account：零行为修改；38 项继续 Green
  - system-log：零行为修改；5 项继续 Green；spec R7 反向证明 customer 不触发 record(...)
- Historical defects considered: 无（capability 首次落地）
- Uncovered test points: 无
- Unresolved prerequisite blockers: 无
- Remaining risks:
  - utf8mb4_unicode_ci 对英文公司名大小写不敏感（design D1 已留档 trade-off）
  - `DuplicateKeyException.getMessage()` 含索引名依赖 MySQL connector-j 9.7.0 行为；driver 升级需回归 TP13/TP14/TP16
  - HTTP 客户端发送非 UTF-8 body 时返 500 而非 400（既有 `GlobalExceptionHandler.handleAny` 行为，非本 change 引入；属 platform-foundation 范畴）
