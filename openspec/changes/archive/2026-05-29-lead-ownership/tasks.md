## 1. QA 设计与测试基线（先行）

- [x] 1.1 在 `openspec/changes/lead-ownership/qa/` 用 vibe-coding-qa 模板写测试设计：6 端点 × 失败矩阵 + 并发认领 + 脱敏分级 + 5 类系统日志断言，标注分层（单元 / 集成）与 Red 预期
- [x] 1.2 确认集成测试基类复用既有 `IntegrationTest`（Testcontainers + 真 MySQL 8.4），并记录与 dev backend smoke 不可并发（见 [[smoke-vs-mvn-verify-share-db]]）

## 2. 错误码与异常映射

- [x] 2.1 `ErrorCode` 追加 `LEAD_ALREADY_CLAIMED`、`LEAD_ENDED_READONLY`（tech-arch §6.3.8/9 已占位）
- [x] 2.2 `GlobalExceptionHandler.handleBusiness` switch 追加映射：`LEAD_ALREADY_CLAIMED` → HTTP 409、`LEAD_ENDED_READONLY` → HTTP 400（design D3）
- [x] 2.3 写 `GlobalExceptionHandlerTest` 红线：两个新错误码经信封返回正确 HTTP 码与 `code` 字段

## 3. 手机号脱敏工具（单元层）

- [x] 3.1 写 `PhoneMaskerTest` 覆盖三档边界（11 位手机 `13812345678→138****5678`、≥8 位座机、<8 位仅末 2 位）——先 Red
- [x] 3.2 实现 `PhoneMasker`（纯函数，tech-arch §9.4）使测试转 Green

## 4. 数据访问层（LeadMapper）

- [x] 4.1 `LeadMapper` 追加 `selectByIdForUpdate(id)`（`SELECT ... FOR UPDATE`，表名 `lead` 反引号，见 [[mysql-reserved-word-lead]]）
- [x] 4.2 `LeadMapper` 追加 `updateOwner(id, ownerSalesId)`（ownerSalesId 可为 NULL）
- [x] 4.3 `LeadMapper` 追加公海列表查询 `selectPool()`：`owner_sales_id IS NULL AND stage NOT IN ('已赢单','已流失')`，按 created_at 倒序 LIMIT 50，内联 customerName / customerUsci

## 5. DTO 与视图

- [x] 5.1 新增 `PoolLeadView`（含 customerName / customerUsci / contactPhone 字段，电话为脱敏 / 明文二态由 service 决定）
- [x] 5.2 新增 `ReleaseLeadRequest`（`releaseNote`，trim 非空校验）、`AssignLeadRequest`（`salesId`）、`TransferLeadRequest`（`salesId`）

## 6. 业务编排（LeadOwnershipService）

- [x] 6.1 抽出 5 写动作共用事务骨架：selectForUpdate → null 判 NOT_FOUND → isEnded 判 LEAD_ENDED_READONLY → 动作专属校验 → updateOwner → SystemLogPort.record（design D1）
- [x] 6.2 实现 `claim`：公海 + ENABLED SALES 调用者校验；非公海 → `LEAD_ALREADY_CLAIMED`；DISABLED 调用者 → `FORBIDDEN`（design D2）
- [x] 6.3 实现 `release`：owner=self 否则 NOT_FOUND；releaseNote 进 `LEAD_RELEASE` summary（design D5）；owner 置 NULL、stage 不变
- [x] 6.4 实现 `assign`：仅公海线索；目标须 ENABLED SALES 否则 VALIDATION_ERROR；非公海 → VALIDATION_ERROR
- [x] 6.5 实现 `recall`：仅私海线索；owner 置 NULL；已在公海 → VALIDATION_ERROR
- [x] 6.6 实现 `transfer`：仅私海线索；目标须 ENABLED SALES 且 ≠ 现归属；公海 / 同人 / 无效目标 → VALIDATION_ERROR
- [x] 6.7 实现公海列表 `listPool`：按调用者角色，ADMIN 明文电话、SALES 走 `PhoneMasker`（design D7）
- [x] 6.8 5 个 summary 用自然语言承载原 / 新归属（design D6），统一组装方法

## 7. 控制器（LeadController）

- [x] 7.1 追加 `GET /api/leads/pool`（ADMIN + SALES）
- [x] 7.2 追加 `POST /api/leads/{id}/claim`（SALES）
- [x] 7.3 追加 `POST /api/leads/{id}/release`（SALES）
- [x] 7.4 追加 `POST /api/leads/{id}/assign`、`/recall`、`/transfer`（ADMIN）
- [x] 7.5 在 `SecurityConfig` / 方法级注解确认各端点角色门：pool 双角色、claim/release SALES、assign/recall/transfer ADMIN

## 8. 集成测试（真 MySQL，覆盖 spec scenario）

- [x] 8.1 公海列表：Sales 脱敏 / Admin 明文 / 仅含未结束无归属 / 无副作用（4 scenario）
- [x] 8.2 认领：成功 / 已被认领 409 / 已结束 400 / LEAD_CLAIM 日志（4 scenario）
- [x] 8.3 认领并发：两线程抢同一公海线索仅一人成功，最终 owner = 成功者（design D1 验证）
- [x] 8.4 退回：成功 stage 不变 / 缺备注 400 / 非自己名下 404 / 已结束 400 / LEAD_RELEASE 日志含备注（5 scenario）
- [x] 8.5 分配：成功 / 停用 Sales 拒 / 已有归属拒 / 已结束 400 / LEAD_ASSIGN 日志（5 scenario）
- [x] 8.6 回收：成功 stage 不变 / 已在公海拒 / 已结束 400 / LEAD_RECALL 日志（4 scenario）
- [x] 8.7 转移：成功 / 停用拒 / 公海拒 / 同人拒 / 已结束 400 / LEAD_TRANSFER 日志（6 scenario）

## 9. 收尾与回归

- [x] 9.1 跑全量 `mvn verify`（不与 dev backend smoke 并发），确认 lead-core 既有 130 测试不回归
- [x] 9.2 复核 R9 MODIFY：lead-core 的「认领端点未暴露」scenario 已删，stage / win-lose 两条仍真实未暴露
- [x] 9.3 `openspec validate lead-ownership --strict` 通过；QA 报告归档至 `qa/`
- [ ] 9.4 准备归档：`/opsx:archive lead-ownership` 把 delta 升级到 `openspec/specs/lead/`
