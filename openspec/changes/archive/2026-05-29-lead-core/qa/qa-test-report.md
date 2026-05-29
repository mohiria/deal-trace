# QA Test Report

## Conclusion

- Overall result: **PASS**
- Requirement / change ID: `openspec/changes/lead-core/`（spec R1-R9 全覆盖）
- QA owner: lead-core change
- Date: 2026-05-29
- Summary:
  - `mvn test` **130/130 Green**（旧 79 + 新 51）
  - `mvn verify` **BUILD SUCCESS**（1m39s）
  - 新增 6 个测试类共 51 项；2 处 TDD Red→Green（PhoneValidator 严格走完；LeadDuplicateService 接近 Red 但走 Non-TDD 路径）
  - 修了 V5 保留字踩坑（`lead` 是 MySQL 8 LEAD() 窗口函数保留字）+ 两处 FK 引发的回归（AdminBootstrapListenerTest TRUNCATE / CustomerConcurrentInsertTest 跨 session 数据）
  - 手工 smoke 4/4 真实业务路径 PASS（创建 / 列表 / 重复拦截 / 预检）；JDBC 验证 V5 schema 与 design D3 完全一致，LEAD_CREATE 系统日志含 summary 落库
  - SystemLogPort 4 参 → 5 参扩展通过 26 项既有测试无退化证明

## Evidence Guide

execution = 命令 + 结果摘要；behavioral = 断言证明的具体行为；coverage = 项目相对测试路径 + `#testName`。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | `PhoneValidatorTest` 11 case（TDD Red→Green） |
| API/integration | Yes | `LeadDuplicateServiceTest` 7 + `LeadControllerCreateTest` 15 + `LeadControllerDetailListTest` 8 + `LeadDuplicateCheckTest` 4 + `LeadNotInScopeTest` 6 |
| E2E | No | 无前端 |
| Regression | Yes | 全套 130/130；含 customer / system-log / auth-account / platform-foundation 既有 |
| Runtime QA validation | Yes | 手工 smoke 4 路径 + JDBC 验证 V5 schema / LEAD_CREATE 日志 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| business_year 服务端生成 | 无 | spec R2 + design D1 | extends（首落地） | design | Add | Implement |
| 查重三元组三态接受罕见双行 | 无 | spec R5 + design D6 显式 trade-off | extends | design D6 | Add 7 case 覆盖 5 状态 + 跨年度 / 跨类型 | Implement |
| 无权访问详情统一 404 | 无 | spec R7 + design D9 | extends | design D9 | TP25/TP26 同 message 验证 | Implement |
| LIMIT 50 desc by created_at | customer change 同款 | spec R7 | extends（沿用 customer 模式） | design D4 | TP30 60 行插入验证 | Implement |
| SystemLogPort 5 参扩展 | system-log change D7 已预告 | design D13 履行 | extends（4 参 default 委派） | design D13 | TP36 既有 26 项无退化 | Implement |
| ErrorCode 追加 | tech-arch §6.3.6/7 占位 | design D11 | extends | tech-arch | TP16/TP17 实际 400 验证 | Implement |
| SecurityConfig 启用方法级权限 | 当前未启用 | design D12 | extends | design D12 | TP28（Sales 403）+ TP29（Admin 200） | Implement |

无 conflicts，gate 全部 extends；已留档。

## TDD Summary

| Test point | Source | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TP1-TP3 PhoneValidator | spec R3 + design D7 | 先建 stub `return false` → `mvn test -Dtest=PhoneValidatorTest` 报 `Tests run: 11, Failures: 5`：`isValid_mobileWithValidPrefix_true` / `isValid_landlineWithAreaCode_true` / `isValid_landlineWithExtension_true` / `isValid_landlineWithoutAreaCode_true` / `isValid_trimsBeforeMatching` 全为合法样本被 stub 误判 false | 行为失败：spec 期望合法手机号 / 座机 / trim 后字符串通过校验；不属语法 / fixture / 环境 | 实现 MOBILE / LANDLINE 双 Pattern + strip → 11/11 PASS | 全套 `mvn test` 130/130 PASS | `backend/src/test/java/com/dealtrace/lead/service/PhoneValidatorTest.java` | PASS |
| TP4 LeadDuplicateService | spec R5 | Non-TDD（spec ↔ 测试 1:1，spec.md → 实现 → 7/7 PASS 一次 Green） | —— | `mvn test -Dtest=LeadDuplicateServiceTest` 7/7 PASS | 同上 | `LeadDuplicateServiceTest` | PASS |
| TP5-TP35 API 路径 | spec R1-R9 | Non-TDD（全新 controller/service，spec ↔ 测试 1:1） | —— | `mvn test -Dtest=com.dealtrace.lead.*Test` 51/51 PASS | 全套 `mvn test` 130/130 PASS | 见 lightweight-test-design Test Points 表 | PASS |
| TP36 SystemLogPort 5 参扩展回归 | design D13 | 影响面：system-log 5 + auth-account 13 + common 2 = 26 项；扩展后跑该子集 `mvn test -Dtest="com.dealtrace.systemlog.*Test,com.dealtrace.account.*Test,com.dealtrace.common.*Test"` 一次 26/26 PASS | —— | 同上 | 全套 `mvn test` 130/130 PASS | 既有 26 项 | PASS |

## Non-TDD Exceptions

| Scope | Reason | Alternative validation |
| :--- | :--- | :--- |
| TP4 (LeadDuplicateService) | 全新 service，spec 决策表 ↔ 测试方法 1:1；形式化 stub Red 净收益微小 | 实现完跑 7/7 一次 Green；任一失败即修复 |
| TP5-TP35 API 路径 | 全新 controller/dto/service；spec ↔ 测试 1:1 | 实现完跑 51/51；任一失败即修复 |
| SystemLogPort 5 参扩展 | 单接口签名 + 两 impl 同步 + 一处 matcher 扩 args | TP36：既有 26 项无退化 |
| `lead` 表反引号修复 | MySQL 保留字踩坑（已修） | 修后 V5 apply 成功（success=TRUE @ 23:58:55）；表结构与 design D3 完全一致（JDBC SHOW CREATE TABLE 输出留证） |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit | `PhoneValidatorTest` | `mvn test -Dtest=PhoneValidatorTest` | PASS | `Tests run: 11, Failures: 0, Time elapsed: 0.300 s` |
| API/integration | `LeadDuplicateServiceTest` | `mvn test -Dtest=LeadDuplicateServiceTest` | PASS | `Tests run: 7, Failures: 0, Time elapsed: 6.406 s` |
| API/integration | `LeadControllerCreateTest` | `mvn test -Dtest=LeadControllerCreateTest` | PASS | `Tests run: 15, Failures: 0, Time elapsed: 27.29 s` |
| API/integration | `LeadControllerDetailListTest` | `mvn test -Dtest=LeadControllerDetailListTest` | PASS | `Tests run: 8, Failures: 0, Time elapsed: 7.844 s` |
| API/integration | `LeadDuplicateCheckTest` | `mvn test -Dtest=LeadDuplicateCheckTest` | PASS | `Tests run: 4, Failures: 0, Time elapsed: 2.229 s` |
| API/integration | `LeadNotInScopeTest` | `mvn test -Dtest=LeadNotInScopeTest` | PASS | `Tests run: 6, Failures: 0, Time elapsed: 1.507 s` |
| Regression（SystemLogPort 扩展子集） | system-log + auth-account + common | `mvn test -Dtest=com.dealtrace.systemlog.*Test,com.dealtrace.account.*Test,com.dealtrace.common.*Test` | PASS | `Tests run: 26, Failures: 0` |
| Regression 全套 | 全部 130 项 | `mvn test` | PASS | `Tests run: 130, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS |
| Full lifecycle | mvn verify（含 surefire + spring-boot:repackage） | `mvn verify` | PASS | `BUILD SUCCESS Total time: 01:39 min` |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Required owner action | Residual risk |
| --- | --- | --- | --- |
| 含中文 body 的手工 smoke 走 curl 直接传 | Git Bash on Windows CP936 编码 → 非 UTF-8 body → Jackson 拒（与 customer change 同款问题） | 用 `--data-binary @utf8-file.json` 或 Postman | 零：中文 contactName / customerName 已由 MockMvc 集成测试覆盖；本次手工 smoke 改用 UTF-8 文件 body 也 PASS |

## Coverage Summary

见 lightweight-test-design.md Test Points 表，所有 in-scope 测试点状态 = COVERED：

- TP1-TP3（PhoneValidator）：COVERED by `PhoneValidatorTest`
- TP4（LeadDuplicateService 三态）：COVERED by `LeadDuplicateServiceTest`
- TP5-TP22（创建路径）：COVERED by `LeadControllerCreateTest`
- TP23-TP30（详情 + 列表权限）：COVERED by `LeadControllerDetailListTest`
- TP31-TP34（预检）：COVERED by `LeadDuplicateCheckTest`
- TP35（R9 反向）：COVERED by `LeadNotInScopeTest`
- TP36（SystemLogPort 扩展回归）：COVERED by 既有 system-log + auth-account 测试集

## Regression Scope

- Changed behavior:
  - 新增 lead capability 第一站（5 端点 + 14 字段 + 9 spec requirements）
  - 扩展 `SystemLogPort` 接口 5 参方法（含 summary）
  - 追加 `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD` 错误码 + GlobalExceptionHandler 映射
  - `SecurityConfig` 启用 `@EnableMethodSecurity`
- Directly impacted old behavior:
  - auth-account（含 bootstrap）：38/38 Green；`AdminBootstrapListenerTest.clearAccountTable` 修了一处 FK 兼容
  - customer：31/31 Green；`CustomerConcurrentInsertTest` 修了一处跨 session 数据隔离
  - system-log：5/5 Green；SystemLogPort 扩展通过 default 委派对既有调用方零修改
  - platform-foundation（GlobalExceptionHandler / SecurityConfig）：相关测试全 Green
- Historical defects considered: V5 `lead` 保留字踩坑（已修，建议记入项目记忆）
- Regression risk level: Medium（接口扩展 + 双 FK + 新表 + 9 spec requirement，但全部自动化覆盖）
- Selected regression tests and why: 见 `regression-impact-analysis.md` Selected Regression Tests 表

## Runtime QA Validation

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| 真实 MySQL 8.4 + dev profile | 启动 backend → Flyway V5 apply (success=true @ 23:58:55, current version 5) → admin 登录 → POST /api/customers 创建 customer "Lead Smoke Customer"（id=35）→ POST /api/leads 创建（businessYear=2026, owner=null=POOL, stage=未触达, id=263）→ GET /api/leads 返 1 行 → POST 同三元组拒 DUPLICATE_ACTIVE_LEAD → GET /api/leads/duplicate-check 返 canCreate=true（首次创建前）/ false（创建后）→ JDBC `SHOW CREATE TABLE lead` 字段顺序 / 类型 / 索引 / 双 FK / utf8mb4_unicode_ci 与 design D3 完全一致 → JDBC `SELECT * FROM system_log WHERE action='LEAD_CREATE'` 返一行，summary=`"客户=Lead Smoke Customer | 类型=BIM咨询 | 归属=公海"` | PASS（4/4 API + 1/1 JDBC schema + 1/1 LEAD_CREATE 日志） | curl 输出 + 后端 Flyway 日志 + JDBC SHOW CREATE TABLE / SELECT 输出 | dev DB 残留：1 customer + 1 lead + 1 system_log 行；下次集成测试 TRUNCATE 时自然清除 |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| V5 首次 apply 失败 | Code（DB schema） | `lead` 是 MySQL 8 保留字（LEAD() 窗口函数），CREATE TABLE 失败 → flyway_schema_history 留 success=FALSE 阻塞后续启动 | (a) V5 SQL 表名 / FK 引用全部反引号 `` `lead` ``（含 `@TableName("`lead`")` Java 注解）；(b) 删 flyway_schema_history.version='5' AND success=FALSE 一行；(c) DROP TABLE IF EXISTS `lead`；用户授权后由 Claude 直接执行 cleanup | 修后 V5 success=TRUE；mvn test 130/130；建议项目记忆补 `mysql-reserved-word-lead`（参考 [[smoke-vs-mvn-verify-share-db]] 同款记忆模式） |
| `AdminBootstrapListenerTest.clearAccountTable` TRUNCATE TABLE account 被拒 | Test（FK 跨表影响） | `lead.owner_sales_id` FK 引用 account；MySQL 拒 TRUNCATE 被 FK 引用的表 | clearAccountTable() 包 `SET FOREIGN_KEY_CHECKS = 0` / `SET FOREIGN_KEY_CHECKS = 1` 与 `MultiTransactionalIntegrationTest.truncateAfterEach` 同款模式 | 修后 3/3 PASS；audit 所有直接 TRUNCATE / accountMapper.delete(null) 位置无其他遗漏 |
| `CustomerConcurrentInsertTest` 一胜一负断言失败（实测两线程都得 DUPLICATE_CUSTOMER） | Test（跨 session 数据隔离） | 旧 customer-change smoke 留下 "Smoke ASCII Co" 客户 USCI=Q；两线程 app-level selectCount 都 > 0 → 都翻译为 DUPLICATE_CUSTOMER → successCount=0 | @BeforeEach 加 `TRUNCATE TABLE customer`（FK_CHECKS=0 包裹） | 修后 1/1 PASS；项目记忆 [[smoke-vs-mvn-verify-share-db]] 强化此模式 |
| 中文 contactName 走 curl --data 触发 500 | Environment（shell 编码） | Git Bash on Windows CP936；curl --data 把中文 body 编为 GBK 字节流 → Jackson `Invalid UTF-8 middle byte` | 改用 `--data-binary @utf8-file.json`；MockMvc 集成测试中文路径已经覆盖；smoke 验证成功 | 与 customer change Failure Analysis 同款，不重复沉淀 |

## Failure Learning

- Learning recorded or recommended: **Yes**
- Knowledge location: 建议在 archive 后追加项目记忆 `mysql-reserved-word-lead`（保留字表名必须反引号；首次 apply 失败需清 flyway_schema_history 失败行 + DROP IF EXISTS 表 + 重 apply）
- Summary: 主业务链路的关键命名（lead / order / select / order 等）务必先验证是否为目标 DB 版本的保留字；MySQL 8 新增了一批窗口函数保留字（LEAD/LAG/RANK 等）。表名反引号是必修，FK / 索引 / Java @TableName 注解全部要同步反引号

## Remaining Risks

- Uncovered test points: 无
- Unresolved prerequisite blockers: 无
- Requirement authority conflicts: 无
- Known flaky areas:
  - 并发查重双行（spec R5 显式接受 trade-off）
  - utf8mb4_unicode_ci 对英文 contact_name / customer_name 大小写不敏感（沿袭 customer D1 trade-off）
  - `DuplicateKeyException.getMessage()` 解析依赖 MySQL connector-j 行为（lead-core 暂未触发此路径；lead 表无应用层翻译，未来如有需 audit）
- Manual follow-up:
  - 后续 lead-ownership 落地时，公海视图 + 脱敏 + 认领 + 三剑客 + Admin assign/recall/transfer 各路径需要新一轮 smoke；并发认领是关键 TP

## Final Statement

lead-core change 自动化覆盖完整：spec R1-R9 全部 9 个 requirement 由 PhoneValidator 11 unit + 41 API/integration 共 52 项测试覆盖（去掉 LeadNotInScopeTest 6 项反向断言剩 45）。`mvn test` 130/130 PASS、`mvn verify` BUILD SUCCESS。auth-account / customer / system-log / platform-foundation 既有 79 项测试零退化（其中 2 项受 FK 影响被修复）。`PhoneValidator` 走完 Red→Green TDD 循环留下有效 Red 证据；`LeadDuplicateService` 走 Non-TDD（已备案）。`SystemLogPort` 5 参扩展通过 default 委派 + 26 项既有测试无退化证明对调用方零修改影响。手工 smoke 4/4 API 路径 + 1/1 JDBC schema 验证 + 1/1 LEAD_CREATE 日志 summary 验证 PASS。V5 `lead` 保留字踩坑已修复并建议沉淀项目记忆。剩余风险均为低 / 中等已知项。建议进入 `/opsx:archive lead-core`。
