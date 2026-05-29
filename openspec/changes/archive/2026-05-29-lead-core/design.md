## Context

`lead` 是 dealtrace 主业务链路的核心对象。PRD §7.3-7.11 共 9 节描述其行为，范围远超单一 change 可控规模。本 change `lead-core` 是 lead capability 的第一站，只交付最小可独立运行的创建 + 查询能力，为后续 lead-ownership / lead-stage / lead-closure 三个 change 铺路。

PRD / tech-arch 锚点：

- PRD §7.3 业务线索管理 / §7.4 创建规则 / §7.7 阶段定义 / §7.8 详情 / §8.2 查重规则 / §8.3 联系电话校验 / §8.4 权限隔离 / §9.3 字段集 / §11.3-11.4 创建与查重验收
- CLAUDE.md「自然年度由创建时间派生不可人工改」「查重三元组 = (年度, 客户, 业务类型)」「客户主体不带联系信息」「停用 Sales 不能认领 / 被分配 / 转入」
- tech-arch §6.3.6-7 已为 `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD` 错误码占位
- tech-arch §7.2.4「每条业务线索最多一条合同记录」—— 与本 change 无关，合同在 lead-closure 处理

已有基础设施（本 change 直接复用）：

- `ApiResponse<T>` / `ErrorCode`（platform-foundation）
- `BusinessException` / `GlobalExceptionHandler`（platform-foundation）
- `JwtAuthenticationFilter` + `AccountPrincipal`（auth-account；停用账号已在过滤层被拦）
- `SystemLogPort`（system-log；本 change 通过它写 LEAD_CREATE 行）
- `customer` 表与服务（customer change；本 change 通过 FK 引用）
- `MultiTransactionalIntegrationTest` 基类（scaffold；用于并发竞态 / 跨事务测试）

后续 lead 系列 change 命名与范围预告（写入本 design 便于团队对齐）：

| Change | 范围 |
| :--- | :--- |
| **lead-ownership** | 公海视图 + 脱敏 + Sales 认领 / 退回 + Admin 分配 / 回收 / 转移 |
| **lead-stage** | PATCH /stage + 阶段流转规则 |
| **lead-closure** | POST /win / /lose + 合同记录端口 + 闭单只读拦截 |
| **dashboard**（独立 capability） | PRD §7.12 全局 / 个人指标卡 |
| **progress-log**（独立 capability） | 进度跟踪 + last_tracked_at 维护 + 已结束态拦截 |

## Goals / Non-Goals

**Goals:**

- 建表（含 14 字段 + 双 FK + 索引）
- 提供 POST /api/leads 创建端点，含完整字段校验、归属规则（Admin / Sales 分支）、business_year 服务端生成、查重三元组三态判断
- 提供 GET /api/leads/duplicate-check 预检端点，让前端在保存前看到阻塞原因 / 历史流失
- 提供 GET /api/leads/{id} 详情、GET /api/leads/mine 个人视图、GET /api/leads 全局视图（仅 Admin）
- 触发 LEAD_CREATE 系统日志（通过 SystemLogPort）
- 追加 `DUPLICATE_ACTIVE_LEAD` / `DUPLICATE_WON_LEAD` 到 ErrorCode 与 GlobalExceptionHandler
- spec.md 仅描述行为契约，design.md / tasks.md 承载实现细节（与既有 customer / system-log 一致）

**Non-Goals:**

- 公海视图 / 联系电话脱敏 / Sales 认领 / Sales 退回 / Admin 三剑客（属 lead-ownership）
- 阶段变更 PATCH /stage（属 lead-stage）
- 标记赢单 / 标记流失 + 合同记录生成（属 lead-closure）
- 进度跟踪写入 / lead.last_tracked_at 维护（属未来 progress-log capability；本 change 仅建出 last_tracked_at 列，写入路径不存在）
- 已结束态对所有写路径的拦截（lead-core 阶段没有"已结束"创建路径——新建线索 stage 固定为"未触达"——故无需拦截；其余写路径由后续 change 各自实现拦截）
- Dashboard 指标（属 dashboard capability）
- 编辑 / 删除既有线索（MVP 整体不支持）
- 分页 `page` / `size`（用 LIMIT 50 desc by created_at；MVP 流量级别下足够）
- 关键词过滤 / 高级搜索（与 customer 一致，留作未来 change）
- 客户其他业务线索协同提示（PRD §7.6）—— 跨 capability 关联展示，留作 frontend-workbench 设计阶段拍

## Decisions

### D1：business_year 物理列 STORED（SMALLINT NOT NULL）

```
A. STORED SMALLINT business_year   ← 本设计选
B. DERIVED：不存列，每次 WHERE YEAR(created_at) = ?
```

| 维度 | STORED | DERIVED |
| :--- | :--- | :--- |
| 查重 SQL | `WHERE business_year = ? AND customer_id = ? AND business_type = ?` 走 idx_lead_customer_year_type | `WHERE YEAR(created_at) = ? AND ...` 函数表达式不走该索引 |
| 跨年度 query | 直接等值 | 函数计算 |
| 一致性 | INSERT 时 `setBusinessYear(LocalDate.now().getYear())` 一次写入 | 自动与 created_at 保持一致 |
| schema 冗余 | 多 2 字节 | 无 |

MVP 查重操作高频（每次创建都查），SMALLINT 列开销可忽略。**选 STORED**。

### D2：双 FK（customer + account）

```sql
CONSTRAINT fk_lead_customer FOREIGN KEY (customer_id)     REFERENCES customer(id),
CONSTRAINT fk_lead_owner    FOREIGN KEY (owner_sales_id)  REFERENCES account(id)
```

| 维度 | 加 FK | 不加 |
| :--- | :--- | :--- |
| 写入兜底 | DB 拒不存在 customer_id / owner_sales_id | 应用层每次 selectById 验证 |
| 删除策略 | customer / account 在 MVP 都没有删除路径，FK 不会绊脚；默认 RESTRICT 即可 | —— |
| 风格一致性 | customer 表自己未加 FK（因它是被引用方） | lead 是引用方，惯例可加 |

**加 FK**。fk_lead_owner 允许 NULL（公海场景）。

### D3：完整表 schema

```sql
-- V5__lead.sql
CREATE TABLE lead (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id       BIGINT       NOT NULL,
    business_year     SMALLINT     NOT NULL,
    business_type     VARCHAR(16)  NOT NULL,
    contact_name      VARCHAR(64)  NOT NULL,
    contact_phone     VARCHAR(32)  NOT NULL,
    lead_source       VARCHAR(128) NULL,
    owner_sales_id    BIGINT       NULL,
    stage             VARCHAR(16)  NOT NULL,
    last_tracked_at   DATETIME(3)  NULL,
    lose_reason       VARCHAR(16)  NULL,
    lose_note         VARCHAR(512) NULL,
    created_at        DATETIME(3)  NOT NULL,
    won_at            DATETIME(3)  NULL,
    lost_at           DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_lead_customer_year_type (customer_id, business_year, business_type),
    KEY idx_lead_owner_created      (owner_sales_id, created_at),
    KEY idx_lead_stage_created      (stage, created_at),
    CONSTRAINT fk_lead_customer FOREIGN KEY (customer_id)    REFERENCES customer(id),
    CONSTRAINT fk_lead_owner    FOREIGN KEY (owner_sales_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

| 字段 | 类型选择 | 理由 |
| :--- | :--- | :--- |
| `id` | BIGINT AUTO_INCREMENT | 与 account / customer / system_log 一致 |
| `business_year` | SMALLINT | 0-65535 容纳年份；2 字节 |
| `business_type` / `stage` / `lose_reason` | VARCHAR(16) | 字符串枚举，避免 ALTER ENUM 之痛；与 account.role / account.status 一致 |
| `contact_name` | VARCHAR(64) | 中文姓名足够 |
| `contact_phone` | VARCHAR(32) | 容纳 `0571-12345678-12345` 等带分机号的座机 |
| `lead_source` | VARCHAR(128) NULL | 用户选填的自由文本 |
| `lose_note` | VARCHAR(512) NULL | PRD §11.9.9 "其他" 原因时填的说明 |
| `last_tracked_at` / `won_at` / `lost_at` | DATETIME(3) NULL | 由后续 capability 填写 |
| 三个 KEY 索引 | (customer,year,type) 查重 + (owner,created) 个人列表 + (stage,created) 公海与按阶段过滤 | 覆盖 lead-core / ownership / closure 共用查询模式 |

**为什么 `last_tracked_at` / 闭单字段在 lead-core 就建出来**：避免后续 change 频繁 ALTER 大表。这些列在本 change 阶段一直 NULL，由对应 change 填写。

### D4：API 路径与权限

| Method | Path | 鉴权 | 角色限制 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/leads` | 已认证 | Admin + Sales 均可 |
| `GET` | `/api/leads/{id}` | 已认证 | Admin 任意 / Sales 仅自己（无权 → 404） |
| `GET` | `/api/leads/mine` | 已认证 | Sales 看自己；Admin 看自己（通常空） |
| `GET` | `/api/leads` | 已认证 | **仅 Admin**（Sales → 403） |
| `GET` | `/api/leads/duplicate-check` | 已认证 | Admin + Sales 均可 |

`SecurityConfig`：
- `/api/leads` 的 Admin 限定**不能**用 path-based `requestMatchers` 简单匹配（因为 `/api/leads/mine` 等 Sales 也能调）；需要在 controller 方法上加 `@PreAuthorize("hasRole('ADMIN')")`，前提是 `SecurityConfig` 启用 `@EnableMethodSecurity`。
- 检查现有 `SecurityConfig` 是否已启用 method security；若未启用则本 change 加 `@EnableMethodSecurity`。

### D5：归属规则的实现编码

```java
// in LeadService.create
if (principal.role() == Role.ADMIN) {
    if (request.ownerSalesId() != null) {
        Account target = accountMapper.selectById(request.ownerSalesId());
        if (target == null || target.getRole() != Role.SALES
            || target.getStatus() != AccountStatus.ENABLED) {
            throw new BusinessException(VALIDATION_ERROR, "归属销售已停用或不可用");
        }
        lead.setOwnerSalesId(target.getId());
    }
    // 否则 ownerSalesId 为 null → 公海
} else { // SALES
    if (Boolean.TRUE.equals(request.assignToPool())) {
        // 显式放公海
    } else {
        lead.setOwnerSalesId(principal.id());
    }
    // 忽略请求中的 ownerSalesId（即使 Sales 试图指定为他人或自己，统一按上述规则处理）
}
```

`CreateLeadRequest` 含三字段：`ownerSalesId`（仅 Admin 使用）、`assignToPool`（Sales 显式入池标识）、其他业务字段。spec R4 允许 Sales 试图指定他人时「拒或忽略二选一」——本设计选**忽略**（更宽容、不产生额外错误码）。

### D6：查重三元组实现 —— 单 SELECT 分组

```java
List<Lead> existing = leadMapper.selectList(new QueryWrapper<Lead>()
    .eq("business_year", year)
    .eq("customer_id", customerId)
    .eq("business_type", businessType));

EnumSet<LeadStage> stages = existing.stream()
    .map(Lead::getStage)
    .collect(toCollection(() -> EnumSet.noneOf(LeadStage.class)));

if (stages.stream().anyMatch(LeadStage::isActive)) {
    throw new BusinessException(DUPLICATE_ACTIVE_LEAD, "已有进行中的同类业务线索");
}
if (stages.contains(LeadStage.WON)) {
    throw new BusinessException(DUPLICATE_WON_LEAD, "已有已赢单的同类业务线索");
}
// 否则：∅ 或 全已流失 → 允许
```

`LeadStage.isActive()` 返回 `stage in {未触达, 初步沟通, 方案报价, 商务谈判}`。

并发场景的 trade-off 已在 spec R5 显式记录：MVP 接受罕见双行进行中，不引入 SELECT FOR UPDATE / 唯一索引。

### D7：联系电话校验

```java
public final class PhoneValidator {
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern LANDLINE = Pattern.compile(
        "^(0\\d{2,3}-)?\\d{7,8}(-\\d{1,5})?$");

    public static boolean isValid(String phone) {
        if (phone == null) return false;
        String s = phone.strip();
        return MOBILE.matcher(s).matches() || LANDLINE.matcher(s).matches();
    }
}
```

trim 在校验内部做；service 层不再单独 trim phone 字段（与 USCI 一致——归一化在校验时完成）。

`contact_phone` 落库存 **trim 后的原始字符串**（保留用户输入的连字符、区号），不做"格式标准化"——PRD §8.3.4 明确 MVP 不支持复杂电话号码格式标准化。

### D8：详情 / 列表的 customerName 内联

详情视图与列表元素都含 `customerName` / `customerUsci`，避免前端二次请求 customer。实现选择：

| 选 | 利弊 |
| :--- | :--- |
| **A. 应用层 JOIN：service 拿 lead 后再调 customerMapper.selectByIds(...)** | MyBatis-Plus 风格；2 次 SELECT，但 MVP 流量低；缓存友好 |
| **B. SQL JOIN：自定义 XML 或 MP 联表插件** | 1 次 SELECT；但 MyBatis-Plus 联表能力弱，需 XML |

选 **A**。列表 50 行 + JOIN 一次 customer 表，性能完全可接受。代码示意：

```java
List<Lead> leads = leadMapper.selectList(...);
Set<Long> customerIds = leads.stream().map(Lead::getCustomerId).collect(toSet());
Map<Long, Customer> customers = customerIds.isEmpty()
    ? Map.of()
    : customerMapper.selectBatchIds(customerIds).stream()
        .collect(toMap(Customer::getId, c -> c));
return leads.stream().map(l -> LeadView.of(l, customers.get(l.getCustomerId()))).toList();
```

### D9：详情权限 —— 404 优先

```java
@GetMapping("/{id}")
public ApiResponse<LeadView> detail(@AuthenticationPrincipal AccountPrincipal principal,
                                    @PathVariable Long id) {
    Lead lead = leadMapper.selectById(id);
    if (lead == null) {
        throw new BusinessException(NOT_FOUND, "线索不存在");
    }
    if (principal.role() == Role.SALES
        && !Objects.equals(lead.getOwnerSalesId(), principal.id())) {
        throw new BusinessException(NOT_FOUND, "线索不存在"); // 同一 message，不泄漏
    }
    return ApiResponse.ok(LeadView.of(lead, customerMapper.selectById(lead.getCustomerId())));
}
```

两种 NOT_FOUND 的 message 一致（"线索不存在"），保证攻击者无法通过 message 差异判断 id 是否真实存在。

### D10：预检端点

```java
@GetMapping("/duplicate-check")
public ApiResponse<DuplicateCheckResponse> duplicateCheck(
        @RequestParam Long customerId,
        @RequestParam String businessType) {
    int year = LocalDate.now().getYear();
    List<Lead> existing = leadMapper.selectList(new QueryWrapper<Lead>()
        .eq("business_year", year)
        .eq("customer_id", customerId)
        .eq("business_type", businessType));
    // ... 同 D6 三态判断 ...
    List<HistoricalLost> hist = existing.stream()
        .filter(l -> l.getStage() == LeadStage.LOST)
        .sorted(Comparator.comparing(Lead::getLostAt, nullsLast(reverseOrder())))
        .map(HistoricalLost::from)
        .toList();
    return ApiResponse.ok(new DuplicateCheckResponse(canCreate, blockingReason, hist));
}
```

注意 `lostAt` 字段在 lead-core 阶段一直 NULL（lead-closure 才填写），故本 change 阶段返回的 `historicalLost` 数组总是空。spec R6 仍要求字段存在并按 lostAt 倒序——这是契约面，lead-closure 落地后自然满足。

### D11：ErrorCode 与 GlobalExceptionHandler

`ErrorCode.java`：
```java
public enum ErrorCode {
    SUCCESS, VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, INTERNAL_ERROR,
    DUPLICATE_CUSTOMER,
    DUPLICATE_ACTIVE_LEAD,    // 新增
    DUPLICATE_WON_LEAD        // 新增
}
```

`GlobalExceptionHandler.handleBusiness` switch：
```java
case VALIDATION_ERROR, DUPLICATE_CUSTOMER,
     DUPLICATE_ACTIVE_LEAD, DUPLICATE_WON_LEAD -> HttpStatus.BAD_REQUEST;
```

### D12：方法级权限 `@PreAuthorize`

检查现有 `SecurityConfig` 是否启用 `@EnableMethodSecurity`：
- 若已启用：直接在 `LeadController.listAll()` 加 `@PreAuthorize("hasRole('ADMIN')")`
- 若未启用：本 change 加 `@EnableMethodSecurity` 注解到 `SecurityConfig`，并验证既有 `auth-account` 端点不受影响（既有端点用 path-based 限定，方法级注解只是额外通道）

实际查 SecurityConfig 后再定（任务 2.x 拍）。

### D13：LEAD_CREATE 系统日志 —— 扩展 SystemLogPort 加 summary 入参

system-log change 的 design D7 已显式预告："port 签名不加 summary，lead change 落地时**才**扩展"。本 change 履行该预告，**Option A：在 `SystemLogPort` 接口加 5 参重载，4 参方法通过 default 委派**。

```java
public interface SystemLogPort {

    /** 主方法：5 参，含 summary。 */
    void record(String action, String targetType, Long targetId, Long operatorId, String summary);

    /** 兼容既有 4 参调用方（account 事件无 summary 场景），default 委派为 summary=null。 */
    default void record(String action, String targetType, Long targetId, Long operatorId) {
        record(action, targetType, targetId, operatorId, null);
    }
}
```

| 影响 | 处理 |
| :--- | :--- |
| `JdbcSystemLogPort` 当前实现 4 参 | 改为实现 5 参；INSERT SQL 把 `summary` 列从 `NULL` 占位改为 `?` 参数绑定 |
| `Slf4jSystemLogPort` 当前实现 4 参 | 改为实现 5 参；log.info 行加 `summary={}` 格式占位 |
| `AdminAccountController` 三处 4 参调用 | **零代码修改**：通过 interface default 自动委派到 5 参（summary=null） |
| 既有 `AdminAccountStatusControllerTest.@MockitoSpyBean SystemLogPort` 断言 `verify(systemLogPort).record("ACCOUNT_DISABLE", "ACCOUNT", id, opId)` | **保留**：MockitoSpyBean 包装的是 5 参实现，4 参 default 仍可被 verify（Mockito 5 支持 default method spy） |
| 既有 `JdbcSystemLogPortTest` 4 个测试 | 4 参调用不变，断言 `summary IS NULL` 仍成立 |
| 既有 `JdbcSystemLogPortExceptionTest` mock matcher | Mockito.when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())) 7 参匹配——SQL + 6 varargs。新签名是 SQL + 7 varargs（多了 summary 参数）。matcher 需要追加一个 any()，否则 mock 不再触发抛 → 测试失败 |

**这是 system-log capability 的实现层扩展，spec 不变**：system-log spec R1 已明文「`summary` 字段为可选（MAY persist）」；新签名让该可选字段对 lead 事件生效，对 account 事件保持 null —— 完全符合既有契约。**不需要修订 system-log spec**。

实际 lead 调用：

```java
String summary = String.format("客户=%s | 类型=%s | 归属=%s",
    customer.getName(),
    lead.getBusinessType(),
    lead.getOwnerSalesId() == null
        ? "公海"
        : ownerAccount.getEmail());
systemLogPort.record("LEAD_CREATE", "LEAD", lead.getId(), principal.id(), summary);
```

## Risks / Trade-offs

- **并发查重双行**：spec R5 接受。MVP 流量极低，可忽略；运维事后人工处理。
- **lead-core 阶段 historicalLost 永远空**：lead-closure 落地后才填；不是 bug，是 capability 增量节奏。spec R6 仍约束契约面。
- **方法级权限 vs path-based**：本 change 在 `/api/leads/mine` 与 `/api/leads` 走不同权限分支，需要方法级注解。若 SecurityConfig 未启用，会增加全局配置面，需要回归既有路径权限。
- **SystemLogPort 5 参扩展对既有测试 mock matcher 的连锁影响**：`JdbcSystemLogPortExceptionTest` 的 Mockito.when matcher 需要从 7 参（SQL + 6 args）扩到 8 参（SQL + 7 args）；否则 mock 不匹配 → 抛不出测试期望的异常。已在 tasks 里显式列出（任务 3.x）。属一处性修改，发生在本 change 内，不留下隐性回归。
- **FK 对测试基类的影响**：`MultiTransactionalIntegrationTest.truncateAfterEach()` 已 `SET FOREIGN_KEY_CHECKS = 0` 包裹 TRUNCATE，FK 不会阻塞 truncate；新 lead 表加入 tablesToTruncate 后无额外问题。
- **`@PreAuthorize` 与 `JwtAuthenticationFilter` 的 Authorities 装配**：当前 filter 把 role 装为 `SimpleGrantedAuthority("ROLE_" + role)`，`hasRole('ADMIN')` 自动匹配 `ROLE_ADMIN`，无需额外配置。

## Migration Plan

1. Flyway 启动期自动执行 `V5__lead.sql`，建表 + 索引 + FK。
2. 无数据迁移（lead 是新表）。
3. 部署 backend 后，5 个新端点可达。
4. 前端在 lead-ownership 落地前已可以集成「我的线索」+「创建」UI（前提是不需要公海视图）。
5. **回滚**：删 V5 migration + 删新代码 + 还原 ErrorCode / GlobalExceptionHandler；下游 lead-ownership / lead-stage / lead-closure 尚未开始，无依赖。
