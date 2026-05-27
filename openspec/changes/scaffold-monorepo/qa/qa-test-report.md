# QA Test Report — scaffold-monorepo

> 本报告随 apply 阶段每个里程碑追加；当前覆盖到 **里程碑 1**（Task §1、§2、§3）。

## 里程碑 1：QA 设计 + 后端骨架 + 数据库连通

### Task §1.1 — Lightweight test design

- ✅ 完成
- Artifact：`openspec/changes/scaffold-monorepo/qa/lightweight-test-design.md`
- 覆盖 platform-foundation 全部 3 个 Requirement 的测试点；标注 §2 后端骨架与 §8 前端骨架为 documented non-TDD exception；记录 prerequisite blockers。

### Task §2 — 后端 Maven 骨架（documented non-TDD exception）

- ✅ 完成
- Alternative validation：`mvn -B -DskipTests compile` BUILD SUCCESS（1.747 s）
- 依赖解析无冲突：`mvn dependency:list` 显示 `spring-boot 4.0.6` / `spring-jdbc 7.0.7` / `flyway-core 11.14.1` / `flyway-mysql 11.14.1` 均 resolved
- Residual risk：Spring Boot 4 + MyBatis Plus 3.5.16 仍是较新组合（实际在里程碑 1 测试中发现 Spring Boot 4 模块化问题，见下方修正记录）

### Task §3 — 数据库连通性 Red-Green-Refactor

#### Red 阶段（实际经历两次伪 Red + 一次合法 Red）

**伪 Red 1：`spring.jackson.serialization` 配置绑定失败**

- 失败原因：Spring Boot 4 升级到 Jackson 3（`tools.jackson.databind.SerializationFeature` 包路径），`LenientObjectToEnumConverter` 没把 kebab-case `write-dates-as-timestamps` 转为 enum 常量 `WRITE_DATES_AS_TIMESTAMPS`
- 失败摘要：`Failed to bind properties under 'spring.jackson.serialization' ... No enum constant tools.jackson.databind.SerializationFeature.write-dates-as-timestamps`
- 性质：**setup/configuration failure**，**不计**合法 Red 证据（按 vibe-coding-qa 规则）
- 处置：从 `application.yml` 删除 `spring.jackson.serialization` 段（scaffold 阶段使用 Spring Boot 默认 ISO-8601 行为足够）

**伪 Red 2：Flyway autoconfig 未启用**

- 失败原因：Spring Boot 4 模块化重构——`FlywayAutoConfiguration` 从 `spring-boot-autoconfigure` 拆到独立 `spring-boot-starter-flyway` 模块；仅有 `flyway-core` + `flyway-mysql` 不足以触发自动配置
- 失败摘要：`ConnectivitySmokeTest.flywayHistoryContainsV1Baseline: Expecting actual: 0 to be greater than or equal to: 1`（`flyway_schema_history` 表存在但 V1 计数为 0；Conditions Evaluation Report 中完全无 FlywayAutoConfiguration mention）
- 性质：**边界情况**——表面是业务断言失败，根因是 build 依赖配置不完整；按 vibe-coding-qa 严格定义不算合法 Red（属于 dependency / autoconfig setup 问题）
- 处置：把 `pom.xml` 的 `<groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId>` 换为 `<groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId>`，保留 `flyway-mysql`

> **工程教训**：升级到 Spring Boot 4 时，`flyway-core` / `liquibase-core` 等直接依赖**必须**换为对应 starter（`spring-boot-starter-flyway` / `spring-boot-starter-liquibase`），否则 autoconfig 不触发。这条教训写入 design.md Risks 的更新计划中（参见下方"剩余风险"）。

#### Green 阶段（合法 Green）

- 命令：`cd backend && mvn -B test`
- 结果：
  ```
  2026-05-27T14:57:11 ... Started ConnectivitySmokeTest in 8.473 seconds
  2026-05-27T14:57:16 ... HikariPool-1 - Added connection com.mysql.cj.jdbc.ConnectionImpl@3e39f08c
  2026-05-27T14:57:16 ... FlywayExecutor : Database: jdbc:mysql://<host>:3606/dealtrace (MySQL 8.4)
  2026-05-27T14:57:18 ... DbMigrate : Current version of schema `dealtrace`: << Empty Schema >>
  2026-05-27T14:57:18 ... DbMigrate : Migrating schema `dealtrace` to version "1 - init"
  2026-05-27T14:57:19 ... DbMigrate : Successfully applied 1 migration to schema `dealtrace`, now at version v1 (execution time 00:00.174s)
  [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  [INFO] Total time:  21.234 s
  ```
- 行为证据：
  - `selectOneReturnsOne`：`JdbcTemplate.queryForObject("SELECT 1", Integer.class)` 返回 `1` ✓
  - `flywayHistoryContainsV1Baseline`：`flyway_schema_history` 表中存在 1 条 `version='1' AND success=TRUE` 记录 ✓
- 覆盖证据：
  - `backend/src/test/java/com/dealtrace/ConnectivitySmokeTest.java#selectOneReturnsOne`
  - `backend/src/test/java/com/dealtrace/ConnectivitySmokeTest.java#flywayHistoryContainsV1Baseline`
- Side effect：云端 `dealtrace` 库现已 baseline 至 V1（`flyway_schema_history` 永久存在 1 条 V1 success 记录）

#### Warning（非阻塞）

- Flyway 11.14.1 警告 `Using MySQL 8.4 which is newer than the version Flyway has been verified with. The latest verified version of MySQL is 8.1.`——Flyway 商业版的 MySQL 8.4 验证状态滞后；Community 版本目前仍能在 8.4 上正确工作（验证 ✓）
- Mockito self-attaching JVM 警告——Mockito 4 / Java 24 兼容性提示，不影响功能；后续可作为 follow-up 在 pom.xml 注册 byte-buddy-agent

## 剩余风险与后续工作（持续追加）

- design.md §R3 需要扩充一条：**Spring Boot 4 模块化导致 `flyway-core` / `liquibase-core` 等直接依赖不再触发 autoconfig，必须用对应 starter**——这条教训应反向写入 design.md 给 bootstrap-dealtrace-mvp 参考
- Mockito self-attaching → 未来里程碑可能涉及，到时按 follow-up 处理

（里程碑 2-6 的证据将在对应阶段追加。）
