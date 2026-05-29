## 1. 数据库迁移（design D3）

- [x] 1.1 新增 `backend/src/main/resources/db/migration/V5__lead.sql`：14 字段 + 3 索引 + 双 FK；`lead` 为 MySQL 8 保留字（LEAD() 窗口函数），表名 / FK 引用全部反引号
- [x] 1.2 本地 dev profile 启动后端，确认 Flyway V5 顺利 apply；JDBC `SHOW CREATE TABLE lead` 字段顺序 / 类型 / 索引 / 双 FK 与 design D3 完全一致 —— **PASS**：Flyway 报 `Current version 5`（success=true @ 23:58:55）；JDBC SHOW CREATE TABLE 输出 14 字段 + 3 索引 + 2 FK + utf8mb4_unicode_ci 完全一致

## 2. 错误码与安全配置（design D4 / D11 / D12）

- [x] 2.1 修改 `ErrorCode.java`：追加 `DUPLICATE_ACTIVE_LEAD` 与 `DUPLICATE_WON_LEAD`；同步在 `GlobalExceptionHandler.handleBusiness` switch 加 → BAD_REQUEST 映射；javadoc 同步更新
- [x] 2.2 `SecurityConfig` 加 `@EnableMethodSecurity`；`LeadController.listAll()` 加 `@PreAuthorize("hasRole('ADMIN')")`；既有 path-based 限定不变；TP28（Sales 调 listAll 403）+ TP29（Admin 调 listAll 200）实际验证

## 3. SystemLogPort 5 参扩展（design D13）

- [x] 3.1 `SystemLogPort.java`：5 参 record 主方法；4 参 default 委派 summary=null
- [x] 3.2 `JdbcSystemLogPort.java`：实现 5 参；INSERT SQL 把 `summary` 列绑定为 `?`
- [x] 3.3 `Slf4jSystemLogPort.java`：实现 5 参；log.info 追加 `summary={}` 占位
- [x] 3.4 `JdbcSystemLogPortExceptionTest.java` Mockito.when matcher 扩到 8 args（SQL + 7 vararg）；verify 同步
- [x] 3.5 system-log + auth-account 既有测试 26/26 Green（5 系统日志 + 8 status + 5 create + 2 list + 2 schema + 2 globalEx + 2 multiTxPoC）—— 5 参扩展无回归

## 4. 领域代码 —— Entity / Enum / Mapper / DTO

- [x] 4.1 `BusinessType.java`：enum + @EnumValue + fromDbValue
- [x] 4.2 `LeadStage.java`：enum + isActive() + fromDbValue
- [x] 4.3 `Lead.java`：14 字段 + `@TableName("`lead`")` 反引号
- [x] 4.4 `LeadMapper.java`：BaseMapper
- [x] 4.5 `CreateLeadRequest.java`：record，含 assignToPool 可选标识
- [x] 4.6 `LeadView.java`：14 字段 + customerName / customerUsci 内联
- [x] 4.7 `DuplicateCheckResponse.java`：record + 嵌套 HistoricalLost

## 5. 领域代码 —— Service / Controller

- [x] 5.1 `PhoneValidator.java`：TDD Red→Green（11/10 stub 3 项行为失败 → 实现 → 11/11）；MOBILE / LANDLINE 双正则
- [x] 5.2 `LeadDuplicateService.java`：三态决策；7/7 集成测试 Green
- [x] 5.3 `LeadService.java`：@Transactional create 7 步；调用 SystemLogPort 5 参写 summary
- [x] 5.4 `LeadController.java`：5 个端点；listAll @PreAuthorize；详情统一 404
- [x] 5.5 启动后端 curl smoke —— **PASS**：admin 登录 → POST customer "Lead Smoke Customer" (id=35) → POST /api/leads (admin no owner → pool, businessYear=2026 服务端生成, stage=未触达, id=263) → GET /api/leads 返 1 行 → POST 同三元组拒 DUPLICATE_ACTIVE_LEAD → GET duplicate-check 返符合预期；JDBC SELECT system_log 验证 LEAD_CREATE 行 summary="客户=Lead Smoke Customer \| 类型=BIM咨询 \| 归属=公海"（中文 body 用 --data-binary @utf8.json 解决 Git Bash CP936 编码问题）

## 6. 测试 —— 覆盖 spec R1-R9

- [x] 6.1 `PhoneValidatorTest` 11 case Green
- [x] 6.2 `LeadDuplicateServiceTest` 7 case Green
- [x] 6.3 `LeadControllerCreateTest` 15 case Green
- [x] 6.4 `LeadControllerDetailListTest` 8 case Green
- [x] 6.5 `LeadDuplicateCheckTest` 4 case Green
- [x] 6.6 `LeadNotInScopeTest` 6 case Green
- [x] 6.7 `mvn test`：130/130 Green（旧 79 + 新 51）。修了两处隐性回归：(a) `AdminBootstrapListenerTest.clearAccountTable` TRUNCATE account 因 lead.owner_sales_id FK 失败 → 包 SET FOREIGN_KEY_CHECKS=0；(b) `CustomerConcurrentInsertTest` 因旧 session smoke 数据已占用 USCI Q → @BeforeEach 加 TRUNCATE customer
- [x] 6.8 `mvn verify`：BUILD SUCCESS（1m39s）

## 7. QA 产物（vibe-coding-qa）

- [x] 7.1 `qa/lightweight-test-design.md`：36 测试点 + TDD 候选 + Non-TDD Exceptions + prerequisite gate（含 SystemLogPort 5 参扩展影响核查）
- [x] 7.2 `qa/regression-impact-analysis.md`：完整测试变更 + 影响分析 + Selected Regression Tests 全 PASS；风险等级 Medium；含 V5 保留字踩坑沉淀
- [x] 7.3 `qa/qa-test-report.md`：Conclusion PASS；TDD Summary（PhoneValidator Red→Green 留证）；Failure Analysis 4 条；Coverage Summary；剩余风险

## 8. 归档准备（不在 apply 内执行，留待 `/opsx:archive`）

- [ ] 8.1 用 `/opsx:archive lead-core` 将本 change 移入 `openspec/changes/archive/<date>-lead-core/` 并把 delta spec 升级到 `openspec/specs/lead/spec.md`（新建 capability 主 spec）
- [ ] 8.2 archive 完成后，更新项目记忆：**建议追加 `mysql-reserved-word-lead`**（lead 是 MySQL 8 保留字 LEAD()，表名 / FK / Java @TableName 必须反引号；首次失败 apply 需手动清 flyway_schema_history success=FALSE 行 + DROP IF EXISTS 表 + 重 apply）
