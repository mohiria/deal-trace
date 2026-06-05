# Lightweight Test Design — add-customer-other-leads-hint

## Context

- Requirement / Spec：`openspec/changes/add-customer-other-leads-hint/specs/customer-other-leads-hint/spec.md`（5 条 Requirement：范围限定 / Admin 见详情 / Sales 公海仅布尔 / Sales 自有仅本人可见 / 不阻断写操作）；PRD §7.6 + 验收 §11.6。
- Change summary：新增「同客户、不同业务类型、非已流失」线索的协同提示能力 `customer-other-leads-hint`，按角色裁剪。后端新增只读端点 `GET /leads/customer-other-leads` + 公海响应附布尔字段；前端三处呈现。
- Target modules / APIs / pages：`LeadOtherLeadsService`、`LeadOtherLeadView`、`LeadController#customerOtherLeads`（`GET /leads/customer-other-leads`）、`PoolLeadView.customerHasOtherLeads` + `LeadOwnershipService.listPool`；`frontend CreateLeadModal.vue` / 公海列表视图 / `LeadDetailPanel.vue` + `api/leads.ts`。
- Test environment / constraints：集成测试用真 MySQL 8.4（tech-arch §12，env `DB_HOST`/`DB_PORT` 指向共享 `dealtrace` 实例）；`@Transactional @Rollback`、禁 raw TRUNCATE（[[no-truncate-in-rollback-tests]]，按 `delete(null)` DML 清表）；与 dev smoke 不可并发（[[smoke-vs-mvn-verify-share-db]]）。前端 vitest + msw，a-modal 须 `:render-to-body="false"`（[[arco-modal-render-to-body-test]]）。

## Input Sources Checked

- [x] Active Spec / PRD §7.6 / §11.6 acceptance criteria
- [x] Existing behavior baseline：`LeadOwnershipService.listPool`（角色脱敏分支 + 批量装载骨架）、`LeadService.ownerName/loadOwnerNames`、`LeadDuplicateService`（不改）
- [x] Data model / field rules：`lead(customer_id, business_type, stage, owner_sales_id)`；stage 枚举含 LOST「已流失」；business_type 三枚举
- [x] API contract / auth rules / error shape：新端点走 `anyRequest().authenticated()`，匿名 401；角色裁剪在 service（非 403）
- [x] UI states / user roles：ADMIN（详情列表：类型+归属+阶段）/ SALES（公海仅布尔；自有详情仅本人其他线索）
- [x] 既有测试镜像：`LeadPoolListTest`（公海角色/造数）、`LeadAssignTest`/`LeadControllerDetailListTest`（端点+token 模式）、`LeadServiceOwnerNameTest`（service 单测）
- [x] Test data / mocks：唯一客户名/邮箱/USCI 隔离；前端 msw `*/api/leads/customer-other-leads`、`*/api/leads/pool`

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 同客户其他业务线索提示 | 无（仅同类查重 `LeadDuplicateService`） | PRD §7.6/§11.6 + 新建 spec | adds（独立读侧能力，不改查重） | PRD | Proceed |
| 提示排除已流失、保留进行中+已赢单、跨年度 | PRD §7.6.1 未提年度/终态 | 用户确认（apply 前问答） | narrows（在 PRD 字面上收窄降噪） | 用户 | Proceed |
| Admin 提示字段=类型+归属+阶段（不含电话） | PRD §7.6.3 列举 | PRD §7.6.3 + 用户确认 | conforms（摘要范围，非限制 Admin 总访问权） | PRD/用户 | Proceed |
| Sales 不可借提示窥探他人私海 | `release` 等的 NOT_FOUND 不泄漏约定 | PRD §7.6.5 | conforms | PRD | Proceed |

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ADMIN 得他人/公海名下其他业务类型线索的 {类型,归属,阶段} | R「Admin 见详情」 | 场景 | 单元(service) | 同客户：BIM咨询(自身,排除) + 定制开发(张三,方案报价) + BIM培训(公海,未触达) | 返回 2 项含 定制开发/张三/方案报价 与 BIM培训/公海/未触达 | `LeadOtherLeadsServiceTest#admin_seesOtherTypesWithOwnerAndStage` | P0 | ✅ |
| 排除当前业务类型自身 | R「范围限定」 | 反例 | 单元 | excludeBusinessType=BIM咨询 | 结果不含 BIM咨询 | `…#excludesGivenBusinessType` | P0 | ✅ |
| 排除已流失、保留已赢单、跨年度 | R「范围限定」 | 边界/等价类 | 单元 | 其他类型含 LOST + WON + 去年进行中 | 含 WON 与去年进行中，不含 LOST | `…#excludesLostKeepsWonAndCrossYear` | P0 | ✅ |
| SALES 仅见本人名下其他线索 | R「Sales 自有」§7.6.6 | 反例(越权) | 单元 | 同客户：本人 BIM培训 + 他人 定制开发 + 公海 | 仅返回本人 BIM培训；无他人/公海 | `…#sales_seesOnlyOwnOtherLeads` | P0 | ✅ |
| 端点存在并按角色裁剪（ADMIN） | R「Admin 见详情」 | 场景 | API/集成 | admin token, customerId+excludeType | 200, data 含其他线索详情 | `LeadOtherLeadsControllerTest#admin_returnsDetails` | P0 | ✅ |
| 端点 SALES 收窄本人 | §7.6.5/§7.6.6 | 反例(越权) | API/集成 | sales token | data 仅本人；响应不含他人归属/电话 | `…#sales_narrowedToOwn_noLeak` | P0 | ✅ |
| 端点匿名 401 | permission | 反例 | API | 无 token | 401 UNAUTHORIZED | `…#anonymous_unauthorized` | P1 | ✅ |
| 公海行 customerHasOtherLeads：有则 true | R「Sales 公海仅布尔」 | 场景 | API/集成 | 公海线索客户另有其他类型进行中线索 | 该行 customerHasOtherLeads=true | `LeadPoolOtherLeadsHintTest#poolRow_trueWhenCustomerHasOtherActive` | P0 | ✅ |
| 公海行布尔：无则 false（同类不算、已流失不算） | R「范围限定」 | 反例/边界 | API/集成 | 客户其他类型仅 LOST / 仅同类 | false | `…#poolRow_falseWhenOnlyLostOrSameType` | P0 | ✅ |
| 公海响应不泄漏其他线索详情 | §7.6.4 | 反例 | API/集成 | 同上 true 场景 | 响应仅布尔，不含其他线索类型/归属/阶段/电话 | `…#poolRow_noOtherLeadDetailLeak` | P0 | ✅ |
| 存在其他业务线索时创建仍成功 | R「不阻断」 | 场景 | API/集成 | 客户已有 BIM咨询，创建 BIM培训 | 201/SUCCESS 创建成功 | `LeadOtherLeadsNonBlockingTest#create_succeedsWithOtherLeads` | P0 | ✅ |
| 存在其他业务线索时认领仍成功 | R「不阻断」 | 场景 | API/集成 | 公海线索客户有其他线索，SALES 认领 | SUCCESS 认领成功 | `…#claim_succeedsWithOtherLeads` | P1 | ✅ |
| CreateLeadModal 选客户后渲染其他线索区块 | R「Admin 见详情」 | 场景 | 前端单测 | msw 返回其他线索行 | 渲染类型/归属/阶段 | `CreateLeadModal.spec.ts` | P1 | ✅ |
| 公海列表对 true 行显示「该客户已有其他业务线索」 | §7.6.4 | 场景 | 前端单测 | msw 公海行 customerHasOtherLeads=true | 文案出现、不展开详情 | 公海视图 `.spec.ts` | P1 | ✅ |
| LeadDetailPanel 自有线索显示本人其他线索 | §7.6.6 | 场景 | 前端单测 | msw 返回本人其他线索 | 渲染本人其他线索 | `LeadDetailPanel` 关联 `.spec.ts` | P1 | ✅ |

## Test Data Plan

| Test point / scenario | Required data state | Business realism basis | Setup method | Isolation strategy | Cleanup method | Data blocker status |
| --- | --- | --- | --- | --- | --- | --- |
| service 单测 | 单客户下多业务类型线索（本人/他人/公海，含 LOST/WON/跨年度） | 同客户可在不同业务线并行推进（PRD §7.6） | mapper.insert 账号/客户/线索，手控 business_year 与 stage | 唯一客户名/邮箱/USCI | `@Rollback` 回滚；`delete(null)` 清表，禁 TRUNCATE | Ready |
| API/集成 | 同上 + JWT token（admin/salesA/salesB） | 镜像 `LeadPoolListTest` | mapper.insert + `jwtService.generateToken` | 唯一标识 + 每类独立断言 | `@Rollback` | Ready |
| 前端单测 | msw 返回其他线索行 / 公海行布尔 | 镜像后端 `LeadOtherLeadView` / `PoolLeadView` | msw `server.use` 工厂 | 每用例独立 pinia + handler | msw resetHandlers | Ready |

## TDD Candidates（顺序 = 先 Red 后 Green）

- Service：先建 `LeadOtherLeadsService` 返回 `List.of()` 的最小可编译桩 → 写 ADMIN/SALES 断言 → 跑出断言级 Red（expected 非空 but empty / SALES 看到他人）→ 实现查询+角色裁剪转 Green。静态语言真 Red 技法见 [[openspec-skill-is-upstream-dont-modify]] 记录的 §Mandatory TDD Rule。
- Controller：先加返回空 list 的端点桩 → 写 ADMIN/SALES/匿名断言 → 断言级 Red → 接 service 转 Green。
- Pool 布尔：先给 `PoolLeadView` 加 `customerHasOtherLeads` 恒 false 桩 → 写 true/false/不泄漏断言 → Red → 在 `listPool` 批量聚合填充转 Green。
- 不阻断：API 集成断言创建/认领在有其他线索时仍 SUCCESS（防回归阻断）。
- 前端：先写渲染/文案/可见性用例（msw）→ 实现组件转 Green。

## 非 TDD 例外 / 剩余风险

- 无非 TDD 例外：所有生产代码均有先行失败测试。
- 剩余风险：跨年度不限可能随历史增长产生噪声 —— 已按用户决策排除 LOST 降噪；若仍偏吵，后续可增量加阶段/年度过滤（不破坏现 spec）。
