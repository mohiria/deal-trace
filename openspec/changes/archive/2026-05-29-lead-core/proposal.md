## Why

业务线索（lead）是 dealtrace 主业务链路的核心对象，但范围极大（PRD §7.3-7.11 共 9 节、14 字段、12+ 端点、9 类 system_log 事件、并发认领、闭单只读、脱敏分级、查重三元组）。一次 change 不可控。本 change `lead-core` 是 lead capability 的第一站，只交付**最小可独立运行的线索创建与查询能力**——足够让 Sales 和 Admin 创建线索、看自己 / 全局列表、看详情、做查重预检。后续 3 个 change（lead-ownership / lead-stage / lead-closure）按职能切片，向同一个 `lead` capability 增量加 requirement。

## What Changes

- 新增 `lead` 表：14 字段 + 3 索引 + 双 FK（customer / account），按 design D1 把 business_year 存为 SMALLINT；nullable 列覆盖未来 lead-ownership / closure 写入区。
- 新增创建线索 API `POST /api/leads`：
  - 必填字段校验（customer / business_type / contact_name / contact_phone）
  - 联系电话格式校验（手机号 11 位或常见座机，PRD §8.3）
  - customer 存在性校验（FK 兜底 + 应用层友好错误）
  - **Admin** 可指定 `ownerSalesId` 为任意 `ENABLED` Sales 或 null（公海）
  - **Sales** 默认归自己；可显式传 null 放公海；**不**可指定其他 Sales
  - business_year 由服务端取 `YEAR(now())`，禁客户端时钟
  - 查重三元组（business_year + customer_id + business_type）三态判断：进行中 / 已赢单存在 → 拒；仅已流失 → 允许（response 不再带历史流失，由预检端点暴露）
  - stage 初始为 `未触达`
  - 触发 `LEAD_CREATE` 系统日志（summary 含客户 / 业务类型 / 归属）
- 新增预检端点 `GET /api/leads/duplicate-check?customerId=&businessType=`：返回 `{ canCreate, blockingReason?, historicalLost: [...] }`；让前端在保存前看到"该客户在本年度该业务类型已有进行中 / 已赢单线索"或"仅历史流失记录"信息。
- 新增查询端点：
  - `GET /api/leads/{id}`：线索详情（14 字段 + customer 客户名 / USCI 内联）；Admin 看全部，Sales 仅看 `owner_sales_id = 自己` 的线索；无权 / 不存在统一返 **404**（不泄漏存在性）；详情**不**含 progress logs / system logs（归未来端点）
  - `GET /api/leads/mine`：Sales 私海视图（`owner_sales_id = 自己`），按 `created_at desc` LIMIT 50
  - `GET /api/leads`：Admin 全局视图（所有线索，含公海 + 全部私海），按 `created_at desc` LIMIT 50
- 在 `ErrorCode` 枚举追加 `DUPLICATE_ACTIVE_LEAD` 与 `DUPLICATE_WON_LEAD`（tech-arch §6.3.6/7 已占位），并在 `GlobalExceptionHandler.handleBusiness` switch 同步追加 → BAD_REQUEST 映射。
- **不**生成任何 lead 编辑 / 删除端点（与 customer 一致，MVP 不支持）。

## Capabilities

### New Capabilities

- `lead`: 业务线索的核心数据模型与创建 / 查询 / 查重预检契约。本 change 加入 R1-R8（字段集 / 创建规则 / business_year 服务端生成 / 查重三元组三态 / 联系电话校验 / 详情 + 列表 + 权限 / 预检端点 / system_log 触发）。后续 change（lead-ownership / lead-stage / lead-closure）继续向本 capability 增量加 requirement，不另立 capability。

### Modified Capabilities

无 spec-level 行为修改。`platform-foundation`（ApiResponse / GlobalExceptionHandler 信封）、`auth-account`（JWT filter + AccountPrincipal）、`system-log`（SystemLogPort）、`customer`（customer 表与服务）均被复用但行为契约不变。

## Impact

- **新增代码**：
  - `backend/src/main/resources/db/migration/V5__lead.sql`
  - `backend/src/main/java/com/dealtrace/lead/entity/Lead.java`
  - `backend/src/main/java/com/dealtrace/lead/entity/BusinessType.java`（enum）
  - `backend/src/main/java/com/dealtrace/lead/entity/LeadStage.java`（enum）
  - `backend/src/main/java/com/dealtrace/lead/repository/LeadMapper.java`
  - `backend/src/main/java/com/dealtrace/lead/dto/CreateLeadRequest.java`
  - `backend/src/main/java/com/dealtrace/lead/dto/LeadView.java`（基础视图，含 customer 名 / USCI）
  - `backend/src/main/java/com/dealtrace/lead/dto/DuplicateCheckResponse.java`
  - `backend/src/main/java/com/dealtrace/lead/service/PhoneValidator.java`（11 位手机 + 座机正则）
  - `backend/src/main/java/com/dealtrace/lead/service/LeadDuplicateService.java`（三元组三态判断）
  - `backend/src/main/java/com/dealtrace/lead/service/LeadService.java`（创建 + 查询编排）
  - `backend/src/main/java/com/dealtrace/lead/LeadController.java`
- **修改代码**：
  - `backend/src/main/java/com/dealtrace/common/ErrorCode.java`：追加 `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD`
  - `backend/src/main/java/com/dealtrace/common/GlobalExceptionHandler.java`：switch 追加两个 → BAD_REQUEST
- **API**：新增 5 个端点（POST 创建 / GET 详情 / GET mine / GET 全局 / GET 预检）
- **前端**：无影响
- **数据库**：新增 1 张表 `lead`（含两条 FK 到 customer / account）；无既有 schema 修改
- **依赖 / 库**：无新增
- **回滚**：删 V5 migration + 删新代码 + 还原 ErrorCode / GlobalExceptionHandler。下游 lead-ownership / lead-stage / lead-closure 尚未开始，无依赖。
