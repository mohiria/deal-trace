## Context

dealtrace 主业务链路的第一个领域对象。auth-account + platform-foundation + system-log 三个 capability 已落地为基础设施，customer 是首个真正的「业务领域 capability」。

PRD / tech-arch 锚点：

- PRD §7.2（客户管理）、§8.1（客户唯一性规则）、§9.2（客户字段）、§11.2（客户创建与搜索）
- tech-arch §6.3.5 已为 `DUPLICATE_CUSTOMER` 错误码占位
- tech-arch §7.2.2-3 钉死「USCI 全局唯一」「客户名称按规范化结果不可完全重复」必须 DB 兜底
- tech-arch §8.1 钉死归一化先行（trim → upper → 18 位校验位）
- CLAUDE.md 反直觉规则前两条全部命中本 capability

已有基础设施（本 change 直接复用，零修改）：

- `ApiResponse<T>` / `ErrorCode`（platform-foundation）
- `BusinessException` / `GlobalExceptionHandler`（auth-account 沿用 platform-foundation 信封）
- `JwtAuthenticationFilter` + `AccountPrincipal`（auth-account；停用账号已在过滤层被拦）
- `MultiTransactionalIntegrationTest` 基类（scaffold；用于并发竞态测试）
- MyBatis-Plus `BaseMapper` 模式（auth-account 的 `AccountMapper` 即范例）

## Goals / Non-Goals

**Goals:**

- 提供 PRD §7.2 完整行为：3 字段客户主体、归一化先行的 USCI 校验、name trim 后查重、Admin/Sales 同等可见的搜索/列表。
- 唯一性双层保障：应用层 check-then-insert（人类可读的 `DUPLICATE_CUSTOMER`）+ DB UNIQUE 索引（并发竞态兜底）。
- `DUPLICATE_CUSTOMER` 错误码加入 `ErrorCode` 枚举，与 tech-arch §6.3.5 一致。
- 后续 capability（lead）能通过 `customer.id` 做 FK 引用。
- spec.md 仅描述行为契约（不含表名 / SQL / 字段类型 / 类名），design.md / tasks.md 承载实现细节。

**Non-Goals:**

- 客户编辑 / 删除（MVP 未授予；spec 已显式拒绝任何 PUT/PATCH/DELETE 端点）
- 客户级审计日志（PRD §7.8 系统日志触发清单不含"创建客户"——见 spec R7）
- 分页参数 `page` / `size`（MVP 用硬上限 50 替代）
- 别名 / 简称 / 模糊合并 / 拼音同音匹配
- USCI 之外的客户主体识别字段（如纳税人识别号、组织机构代码——这些已被 USCI 统一标准化）
- 无 USCI 客户支持（PRD §8.1.8 显式排除）
- 客户主体的归属 / owner / 公海概念（属 lead capability）
- 错误码细化（USCI 格式 / 长度 / 校验位错误统一用 `VALIDATION_ERROR + message` 区分）

## Decisions

### D1：客户名称 collation —— 使用默认 `utf8mb4_unicode_ci`

| 维度 | 选择 | 备选 | 理由 |
| :--- | :--- | :--- | :--- |
| 列 collation | `utf8mb4_unicode_ci`（库默认） | `utf8mb4_bin`（字节相等） | 与 account.email 等既有列一致；朴素直觉「ABC Corp 与 abc corp 是同一家」更符合用户预期；中文字符两种 collation 行为相同；本 change 不引入混合 collation 复杂度 |

**含义**：PRD §7.2.4 字面「完全一致」在本 design 下被放宽为「按 utf8mb4_unicode_ci 等价」，即大小写不敏感、accent 不敏感。这是显式扩展解读，已在 design 留档；如未来业务发现误判（如英文/法文公司名），可独立 change 切到 `_bin` 并加 collation 迁移。

### D2：客户搜索 / 列表 —— 单端点 + LIKE OR + 硬上限 50

```
GET /api/customers
GET /api/customers?keyword=<string>
```

| 维度 | 选择 | 备选 | 理由 |
| :--- | :--- | :--- | :--- |
| 端点形态 | 单端点 + 可选 `keyword` | 双端点 `GET /customers` + `GET /customers/search` | 前端两个场景（客户列表页、线索创建页下拉）共用；URL 模式更少 |
| 匹配语义 | `name LIKE %k%` OR `usci LIKE %k%` | name 子串 + USCI 前缀 / 全文索引 / 多字段 keyword 切分 | 用户输入「中国建筑」希望命中 name 含该子串的客户；输入「9111」希望命中 USCI 含该子串的客户；OR + 子串覆盖两种意图；MVP 数据量下 LIKE 性能可接受 |
| 上限 | 固定 `LIMIT 50` | `page` / `size` 分页 | MVP 客户数量预期百级以内；超 50 行的极端情况下用户应细化关键词；分页 UI / 总数查询的复杂度延后 |
| 排序 | `ORDER BY created_at DESC` | `ORDER BY name ASC` / 按相关性 | 新增客户优先可见，符合日常运营场景；name 排序对中文意义不大（拼音 vs 笔画 vs UTF 字节序歧义） |
| 关键词 trim | 是 | 否 | 与 customer.name trim 查重对称；空格 keyword 等价于无 keyword |
| 空数组 vs 404 | 返回 `data=[]` + `SUCCESS` | 返回 `NOT_FOUND` | 列表查询命中 0 行不是错误，是正常状态；与 ApiResponse 信封语义一致 |

代码骨架（design 示意，spec.md 不绑定）：

```java
@GetMapping("/api/customers")
public ApiResponse<List<CustomerView>> search(@RequestParam(required = false) String keyword) {
    String k = keyword == null ? null : keyword.strip();
    List<Customer> rows = (k == null || k.isEmpty())
        ? customerMapper.listLatest(50)
        : customerMapper.searchByKeyword(k, 50);
    return ApiResponse.ok(rows.stream().map(CustomerView::from).toList());
}
```

### D3：USCI 校验算法 —— 自实现 GB 32100-2015，30 行 Java

```java
public final class UsciValidator {
    // GB 32100-2015 第 18 位校验位字符集（31 个字符，排除 I/O/S/V/Z）
    private static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] WEIGHTS = {
        1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28
    };

    /** 归一化：trim + 字母转大写。不在此处校验长度，留给 isValid。 */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip().toUpperCase(Locale.ROOT);
    }

    /** 输入需已归一化。校验长度=18、字符集合法、第 18 位与算法计算一致。 */
    public static boolean isValid(String normalized) {
        if (normalized == null || normalized.length() != 18) return false;
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int v = CHARSET.indexOf(normalized.charAt(i));
            if (v < 0) return false;
            sum += v * WEIGHTS[i];
        }
        int expected = (31 - sum % 31) % 31;
        int actual = CHARSET.indexOf(normalized.charAt(17));
        return expected == actual;
    }
}
```

| 维度 | 选择 | 备选 | 理由 |
| :--- | :--- | :--- | :--- |
| 实现 | 自实现，单文件 | 引 hutool / 专门库 | 算法 ~30 行；标准 2015 后未变；零依赖收益 > 维护成本 |
| 字符集 | 31 字符（去 I/O/S/V/Z） | 36 字符 | GB 标准排除易混淆字符 |
| 入参契约 | `isValid` 要求**已归一化** | 内部再归一化 | 归一化 + 校验拆离；service 层显式调用顺序，防 CLAUDE.md 反直觉规则的"顺序反了" |
| 大小写处理 | normalize 用 `Locale.ROOT`（避免 Turkish locale 下 i/I 互换的怪事） | `toUpperCase()` 默认 locale | 业务字符为 ASCII A-Z，需稳定行为 |

### D4：归一化位置 —— Service 层第一步

```
Controller (薄)：DTO 解析、@AuthenticationPrincipal、调用 service
   ↓
Service (厚)：第 1 步 normalize → 第 2 步 校验位 → 第 3 步 查重 → 第 4 步 INSERT
   ↓
Repository (薄)：MyBatis-Plus mapper
```

理由：归一化是业务不变量；service 是业务规则锚点；controller 只做协议层翻译。这样无论调用方是 HTTP / 未来的 CLI / 批量导入，都走同一个归一化路径。

DTO `CreateCustomerRequest` **不**在反序列化时归一化——保留原始输入便于错误回显（如显示用户提交的原始 USCI 让其自检）；归一化在 service 入口立即发生。

### D5：唯一性双层保障

```
Service 流程：
1. normalize(name) → trimmedName
2. normalize(usci) → normalizedUsci
3. UsciValidator.isValid(normalizedUsci) → 否则抛 VALIDATION_ERROR
4. selectCount WHERE usci=?  → > 0 抛 DUPLICATE_CUSTOMER("USCI 已存在")
5. selectCount WHERE name=? → > 0 抛 DUPLICATE_CUSTOMER("客户名称已存在")
6. INSERT → catch DuplicateKeyException → 抛 DUPLICATE_CUSTOMER（兜底并发竞态）
```

| 维度 | 选择 | 理由 |
| :--- | :--- | :--- |
| 检查顺序 | USCI 先于 name | USCI 是全局主体识别字段；先报 USCI 重复优先级最高 |
| 检查 vs 兜底 | 应用层 check + DB UNIQUE 兜底 | 应用层先给人类可读的错误，DB 兜底防并发；spec R4 第二个场景显式要求 |
| 异常翻译 | `DuplicateKeyException` → `DUPLICATE_CUSTOMER` | 不让 SQL 异常细节外泄；message 区分是 USCI 还是 name 冲突需要按异常 message 提取索引名（`uk_customer_usci` vs `uk_customer_name`），见 D6 |

### D6：DuplicateKeyException 翻译策略

应用层 check 已过 → INSERT 仍抛 `DuplicateKeyException` = 并发竞态。从异常 message 提取索引名判断冲突字段：

```java
catch (DuplicateKeyException ex) {
    String msg = ex.getMessage() == null ? "" : ex.getMessage();
    if (msg.contains("uk_customer_usci")) {
        throw new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "USCI 已存在");
    }
    if (msg.contains("uk_customer_name")) {
        throw new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "客户名称已存在");
    }
    // 兜底：未知索引冲突，仍报 DUPLICATE_CUSTOMER 通用 message
    throw new BusinessException(ErrorCode.DUPLICATE_CUSTOMER, "客户已存在");
}
```

风险：MySQL 驱动版本变化可能改 message 格式。**缓解**：测试用例显式覆盖两种并发竞态路径，CI 会暴露 message 模式变化；如未来 driver 换格式，集中改一处。

### D7：表 schema

```sql
-- V4__customer.sql
CREATE TABLE customer (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    usci        CHAR(18)     NOT NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_usci (usci),
    UNIQUE KEY uk_customer_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

| 字段 | 类型选择 | 理由 |
| :--- | :--- | :--- |
| `id` | BIGINT AUTO_INCREMENT | 与 account / system_log 一致 |
| `name` | VARCHAR(128) | 中文公司名 50 字以内为主，128 留余量；不用 TEXT 避免索引限制 |
| `usci` | CHAR(18) | 固定 18 位；CHAR 比 VARCHAR 在固定长度下更紧凑、索引更稳 |
| `created_at` | DATETIME(3) | 毫秒精度，与 account / system_log 一致 |
| `uk_customer_usci` / `uk_customer_name` | UNIQUE | tech-arch §7.2.2/3 要求 DB 兜底 |

**无 FK**：customer 是被引用方；lead capability 落地时由 lead 表加 `customer_id BIGINT NOT NULL` + FK 到 customer.id（届时再决策 ON DELETE 策略，本 change 无需提前布局）。

### D8：API 路径与权限

| Method | Path | 用途 | 鉴权 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/customers` | 创建客户 | 已认证（不区分角色） |
| `GET` | `/api/customers` | 搜索 / 列表客户 | 已认证（不区分角色） |

`SecurityConfig` 修改：在既有 `/api/admin/**` 限定 ADMIN 之外，**不**为 `/api/customers/**` 添加额外角色约束——默认 `authenticated()` 即满足。停用账号已在 `JwtAuthenticationFilter` 实时查 status 时被拦。

### D9：ErrorCode 枚举追加 `DUPLICATE_CUSTOMER`

`ErrorCode.java` 当前 javadoc 注明了「业务专属错误码由对应 capability spec 在 apply 时追加进本枚举」，明确允许本 change 修改。修改两处：

1. 枚举常量列表追加 `DUPLICATE_CUSTOMER`
2. javadoc 中相关说明同步更新（移除"DUPLICATE_CUSTOMER 待加入"措辞）

`GlobalExceptionHandler` 已有 `BusinessException` 处理路径，自动透传 `code + message`；无需在 handler 改动。

### D10：测试基类与并发竞态

| 测试场景 | 基类 | 理由 |
| :--- | :--- | :--- |
| 创建成功 / 字段校验失败 / 单线程查重 | `IntegrationTest`（@Transactional + @Rollback） | 自动回滚，无需 truncate |
| **并发竞态**（spec R4 第二场景） | `MultiTransactionalIntegrationTest`，`tablesToTruncate() = Set.of("customer")` | 需要真实 commit 才能让两个事务"看见"彼此 |
| USCI 校验位算法 | 纯 JUnit（无 Spring） | 算法是纯函数，最快反馈层 |

并发竞态测试用 `CountDownLatch` + `ExecutorService` 两线程同时 INSERT 同一 USCI；断言：一行 INSERT 成功 / 一个 BusinessException(DUPLICATE_CUSTOMER) / system_log 表无新增行（spec R7）。

### D11：SecurityConfig 路径放行

当前 `SecurityConfig` 中 `/api/admin/**` 限定 ADMIN，`/api/health` 与 `/api/auth/login` permitAll，其余 `authenticated()`。本 change `/api/customers/**` 落在「其余 `authenticated()`」分支，**无需**添加新规则——但需 review 时确认 SecurityConfig 未误把 customer 路径归类到 admin。

## Risks / Trade-offs

- **`_ci` collation 放宽"完全一致"**：英文公司名 "ABC Corp" 与 "abc corp" 被视为同名。**缓解**：design 留档；中文为主的数据集影响小；如未来误判可独立 change 切到 `_bin`。
- **`LIKE '%k%'` 不走索引**：MVP 客户数量预期百级以内，全表扫描可接受；超千级时需要引入全文索引或 ES。**缓解**：本 change 不优化；性能拐点观察到时再开 change。
- **`DuplicateKeyException.getMessage()` 解析依赖 driver 行为**：MySQL connector-j 升级可能改 message 格式。**缓解**：D6 集中翻译 + 测试用例显式覆盖两种竞态；CI 暴露格式变化。
- **`utf8mb4_unicode_ci` 对部分 emoji / 罕用字的等价判断**：极小概率边界。**缓解**：MVP 业务字符为中文 / ASCII，不引入；如出现可针对性处理。
- **`name` UNIQUE 索引在 collation `_ci` 下不区分大小写**：与 D1 决策一致，**不**是风险，是设计内的语义；spec 已显式接受。
- **创建客户不写系统日志**：未来 audit 要求"客户主体创建也要留痕"需独立 change 修订 auth-account spec 或本 spec。**缓解**：spec R7 已显式留 trace。
- **无 FK 提前布局**：customer 表不引用任何外部 FK，lead capability 落地时增量加 FK 即可。零风险。

## Migration Plan

1. Flyway 自动应用 `V4__customer.sql`，启动期完成建表。
2. 无数据迁移（customer 是新表）。
3. 部署 backend 后，POST/GET `/api/customers` 即可。
4. 前端在 lead 模块落地前不消费本 API；本 change 是后端独立增量。
5. **回滚**：删除 `JdbcSystemLogPort` 之类做法不适用——本 change 改的是 ErrorCode + SecurityConfig + 新文件，回滚 = 删 V4 + 删新文件 + 还原 ErrorCode/SecurityConfig 两文件。下游尚无依赖，零兼容性顾虑。
