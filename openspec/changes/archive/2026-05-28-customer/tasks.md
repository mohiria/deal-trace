## 1. 数据库迁移（design D7）

- [x] 1.1 新增 `backend/src/main/resources/db/migration/V4__customer.sql`：customer 表（id / name VARCHAR(128) / usci CHAR(18) / created_at DATETIME(3) + UNIQUE `uk_customer_usci` / `uk_customer_name`，utf8mb4_unicode_ci）
- [x] 1.2 本地 dev profile 启动后端 + JDBC `SHOW CREATE TABLE customer` —— **PASS**：Flyway 报 `Current version 4 / up to date`（V4 在 mvn test 时已 apply），JDBC 输出 7 字段 + 3 索引 + datetime(3) + utf8mb4_unicode_ci，与 design D7 完全一致

## 2. 错误码与安全配置（design D9 / D11）

- [x] 2.1 修改 `backend/src/main/java/com/dealtrace/common/ErrorCode.java`：追加 `DUPLICATE_CUSTOMER`；javadoc 更新；并同步在 `GlobalExceptionHandler.handleBusiness` switch 加 `case DUPLICATE_CUSTOMER -> HttpStatus.BAD_REQUEST`（否则 default 兜底返 500 不符 spec R4）
- [x] 2.2 核查 `SecurityConfig`：`/customers/**` 无 admin 误归类，落在「其余 authenticated()」分支 —— **零代码修改**；TP9（POST 匿名 401）+ TP22（GET 匿名 401）证明

## 3. 领域代码（design D2 / D3 / D4 / D5 / D6）

- [x] 3.1 `backend/src/main/java/com/dealtrace/customer/entity/Customer.java`：MyBatis-Plus 实体
- [x] 3.2 `backend/src/main/java/com/dealtrace/customer/repository/CustomerMapper.java`：继承 BaseMapper
- [x] 3.3 `backend/src/main/java/com/dealtrace/customer/dto/CreateCustomerRequest.java`：record；**不**用 @NotBlank（service 权威校验 trim 后空白）
- [x] 3.4 `backend/src/main/java/com/dealtrace/customer/dto/CustomerView.java`：record 4 字段 + `from(Customer)`
- [x] 3.5 `backend/src/main/java/com/dealtrace/customer/service/UsciValidator.java`：normalize（strip + toUpperCase(Locale.ROOT)）+ isValid（charset 索引 + 加权和 + mod 31）—— **TDD Red→Green**（stub 阶段 3/10 失败，实现后 10/10）
- [x] 3.6 `backend/src/main/java/com/dealtrace/customer/service/CustomerService.java`：@Transactional create 六步（D5）+ search（D2 LIKE OR + LIMIT 50 desc）+ DuplicateKeyException 翻译（D6）
- [x] 3.7 `backend/src/main/java/com/dealtrace/customer/CustomerController.java`：POST + GET 两端点，@AuthenticationPrincipal AccountPrincipal
- [x] 3.8 启动后端 curl smoke：admin 登录 → POST 创建（USCI 含空格小写）→ GET 搜索 → POST 重复 → POST 校验位错 → GET 全列表 —— **5/5 PASS**（ASCII 路径；中文路径由 shell CP936 编码限制，已由 MockMvc 集成测试覆盖）

## 4. 测试 —— 覆盖 spec R1-R7（design D10）

- [x] 4.1 `UsciValidatorTest`：10 cases 覆盖归一化（含 null/blank）+ 合法 USCI 3 组真实样本 + 长度错 + 字符集（IOSVZ）+ 校验位错 + isValid 不容忍未归一化输入 —— Red→Green 10/10
- [x] 4.2 `CustomerControllerCreateTest` 10 cases：spec R1（字段集 4 项 + 不含 lead 字段）、R2（空 name / trim 重复 / 不同名）、R3（归一化后落库 / 长度错 / 校验位错 / 空 USCI）、R4 单事务路径（重复 USCI 含小写覆盖）、R5（Admin + Sales 成功 + 匿名 401） —— 10/10 PASS
- [x] 4.3 `CustomerControllerSearchTest` 6 cases：spec R6 五个场景 + blank keyword 等价无 keyword —— 6/6 PASS
- [x] 4.4 `CustomerConcurrentInsertTest` 1 case：spec R4 场景 2 并发竞态；CountDownLatch + ExecutorService(2)；AtomicInteger 验证恰一胜一负 + 无 INTERNAL_ERROR —— 1/1 PASS
- [x] 4.5 `CustomerSystemLogQuietTest` 1 case：spec R7 spy.verify(systemLogPort, never()).record + COUNT(*) 前后相等 —— 1/1 PASS
- [x] 4.6 `CustomerImmutabilityTest` 3 cases：spec R6 negative；PUT/PATCH/DELETE /customers/{id} 响应非 SUCCESS —— 3/3 PASS
- [x] 4.7 `mvn test`：79/79 Green（旧 48 + 新 31）—— 既有 auth-account / platform-foundation / system-log 零退化
- [x] 4.8 `mvn verify`：BUILD SUCCESS（含 surefire + spring-boot:repackage，1m24s）

## 5. QA 产物（vibe-coding-qa）

- [x] 5.1 `openspec/changes/customer/qa/lightweight-test-design.md`：24 测试点 + TDD 候选（UsciValidator + 并发）+ Non-TDD Exceptions 备案
- [x] 5.2 `qa/regression-impact-analysis.md`：测试变更 / 影响分析 / 风险 Low / 选中回归测试与结果（全 PASS）
- [x] 5.3 `qa/qa-test-report.md`：Conclusion PASS、TDD Summary（UsciValidator Red→Green）、Failure Analysis（中文 smoke shell 编码 + mvn verify 并发误抢 admin 已记 learning）、剩余风险

## 6. 归档准备（不在 apply 内执行，留待 `/opsx:archive`）

- [ ] 6.1 用 `/opsx:archive customer` 将本 change 移入 `openspec/changes/archive/<date>-customer/` 并把 delta spec 升级到 `openspec/specs/customer/spec.md`
- [ ] 6.2 archive 完成后，更新项目记忆：可选追加 `smoke-vs-mvn-verify-share-db`（dev profile 与 test profile 共用 dealtrace 实例，不可并发跑 smoke 与 mvn verify）；无其他记忆需求
