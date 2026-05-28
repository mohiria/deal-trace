## Why

dealtrace 主业务链路（客户 → 业务线索 → 进度跟踪 → 赢单/流失）的第一站「客户主体」目前完全空白：没有表、没有 API、没有 spec。所有后续 capability（lead / contract / dashboard）都需要 customer 作为 FK 锚点，无法在此之前推进。本 change 把 PRD §7.2 / §8.1 / §11.2 钉死的客户主体能力落地为可独立运行的最小切片。

## What Changes

- 新增 `customer` 表：3 业务字段（name / usci / created_at）+ id + 两条 UNIQUE 索引（usci、name），DB 约束兜底唯一性（tech-arch §7.2.2/3）。
- 新增创建客户 API：`POST /api/customers`，归一化（USCI trim+upper、name trim）先行 → 18 位 GB 32100-2015 格式 + 校验位校验 → 唯一性检查；返回新建客户 view（id / name / usci / createdAt）。
- 新增搜索/列表客户 API：`GET /api/customers?keyword=<可选>`，无 keyword 时返回全部（按 created_at 倒序，硬上限 50 行），有 keyword 时按 `name LIKE %k%` OR `usci LIKE %k%` 过滤。
- 在 `ErrorCode` 枚举新增 `DUPLICATE_CUSTOMER`（tech-arch §6.3.5 已列项位）；USCI 格式 / 校验位失败、name 空、USCI 空等字段层错误沿用 `VALIDATION_ERROR + message`。
- 创建客户**不**生成系统日志（PRD §7.8 系统日志触发事件清单不含创建客户）。
- Admin 与 Sales 均可创建、均可搜全部客户；停用账号被 JWT 过滤器拦在认证层（auth-account R2 已保证），本 change 零额外代码。
- USCI 校验位算法（GB 32100-2015）自实现于后端，约 30 行 Java，不引第三方依赖。

## Capabilities

### New Capabilities

- `customer`: 客户主体的创建、查询、字段规则与全局唯一性契约——包含 USCI 归一化先行、name trim 后等价查重、Admin/Sales 同等可见的搜索语义。**不**覆盖联系信息（归 lead）、归属（无 owner 概念）、编辑 / 删除（MVP 未授予）、客户级审计日志（PRD 未要求）。

### Modified Capabilities

无。auth-account 与 platform-foundation 行为契约不变；本 change 在底层使用 `ApiResponse` 信封、`GlobalExceptionHandler`、`JwtAuthenticationFilter` 等既有基础设施，但未引起任何 spec-level 行为修改。

## Impact

- **新增代码**：
  - `backend/src/main/resources/db/migration/V4__customer.sql`
  - `backend/src/main/java/com/dealtrace/customer/entity/Customer.java`
  - `backend/src/main/java/com/dealtrace/customer/repository/CustomerMapper.java`
  - `backend/src/main/java/com/dealtrace/customer/dto/CreateCustomerRequest.java`
  - `backend/src/main/java/com/dealtrace/customer/dto/CustomerView.java`
  - `backend/src/main/java/com/dealtrace/customer/service/UsciValidator.java`（含归一化 + GB32100-2015 校验位算法）
  - `backend/src/main/java/com/dealtrace/customer/service/CustomerService.java`（归一化 → 校验 → 唯一性 → 持久化的事务流）
  - `backend/src/main/java/com/dealtrace/customer/CustomerController.java`
- **修改代码**：
  - `backend/src/main/java/com/dealtrace/common/ErrorCode.java`：追加 `DUPLICATE_CUSTOMER`，删除 javadoc 中将该枚举值列为"待加入"的注释片段
  - `backend/src/main/java/com/dealtrace/security/SecurityConfig.java`：放行 `POST /api/customers` 与 `GET /api/customers` 给所有已认证用户（不区分角色）
- **API**：新增 2 个端点
- **前端**：无影响
- **数据库**：新增 1 张表 `customer`，无既有 schema 修改
- **依赖 / 库**：无新增
- **回滚**：删除 V4 migration + 移除新增代码 + 还原 ErrorCode 与 SecurityConfig 即可；下游尚无依赖
