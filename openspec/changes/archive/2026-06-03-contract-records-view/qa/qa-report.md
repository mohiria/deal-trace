# QA Test Report — contract-records-view

## Conclusion

- Overall result: PASS
- Requirement / change ID: `contract-records-view`（capability `contract-view`）
- QA owner: Claude Code（apply 阶段）
- Date: 2026-06-03
- Summary: 合同记录只读浏览能力落地。后端 `GET /contracts` 按角色裁决可见范围（ADMIN 全量 / SALES 仅本人成交），支持成交销售、签订日期闭区间、客户名·业务类型关键词筛选，倒序分页；金额以精确字符串保标度，公海赢单展示「公海赢单」。前端 `/contracts` 占位页替换为真实列表页。spec 四条 Requirement 的关键 Scenario 均有通过用例覆盖；后端 10 + 前端 7 新增用例全绿，前端全量 223 用例无回归。

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | No | 无独立纯函数单元（千分位格式化随前端组件用例覆盖） |
| API/integration | Yes | Mapper + Controller，真 MySQL 8.4 |
| E2E | No | 关键旅程已由 API + 前端组件覆盖；未新增 Playwright |
| Regression | Yes | 前端全量 223 用例；后端全量套件 |
| Runtime QA validation | No | 仅自动化测试 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 合同记录可浏览 | 写侧 `contract` 仅生成、无读端点 | PRD §4/§11.10 + `contract-view` spec | extends | PRD | Add | Implement（新增读侧） |
| SALES 可见本人成交合同 | PRD 仅明确 ADMIN | 用户确认（propose 问答） | extends | 用户 | Add | Implement（service 收窄） |

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Mapper JOIN/筛选/倒序/count | spec R1/R3 | 编写测试时 `selectPage`/`countPage` 未实现 → 编译缺失（阻塞型）；以行为驱动实现 | 行为缺口（端点/查询未实现） | `mvn -o test -Dtest=ContractRecordsViewMapperTest` → 4 passed | `ContractRecordsViewMapperTest` | GREEN |
| API 角色可见范围 + 筛选 + 401 | spec R1/R2 | 同上（`ContractController` 未实现） | 行为缺口 | `mvn -o test -Dtest=ContractControllerListTest` → 6 passed | `ContractControllerListTest` | GREEN |
| 前端渲染/筛选/可见性/分页 | spec R1/R3 | `ContractsView`/`api/contracts` 未实现 | 行为缺口 | `vitest run ContractsView.spec.ts` → 7 passed | `ContractsView.spec.ts` | GREEN |

说明：写侧已存在的 `contract` 行为不变，故无 MODIFIED；Red 证据为「目标端点/查询/组件尚不存在」的行为缺口，非环境/fixture 失败。

## Tests Run

| Source | Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| Design | API/integration | ContractRecordsViewMapperTest（4） | `mvn -o test -Dtest=ContractRecordsViewMapperTest` | PASS | Tests run: 4, Failures: 0, Errors: 0 |
| Design | API/integration | ContractControllerListTest（6） | `mvn -o test -Dtest=ContractControllerListTest` | PASS | Tests run: 6, Failures: 0, Errors: 0 |
| Design | 前端单测 | ContractsView.spec.ts（7） | `vitest run src/views/ContractsView.spec.ts` | PASS | 7 passed / 0 failed |
| Regression | 前端单测 | 全量 | `vitest run` | PASS | 97 suites / 223 tests passed |
| Regression | 前端类型 | `vue-tsc -b` | `vue-tsc -b` | PASS | 0 error |
| Regression | API/integration | 后端全量 | `mvn -o test` | PASS | **278 run, 0 failures, 0 errors, BUILD SUCCESS** |

### 修复期间处理的既有测试隔离缺陷（非本 change 引入）

- 现象：首轮全量出现 7 个 ERROR——`AdminAccountControllerListTest`(2，撞 `fk_contract_sales`)/`AdminAccountControllerCreateTest`(5，撞 `fk_lead_owner`) 的 `seed()` 执行 `DELETE FROM account`，被共享库残留的已提交 lead/contract 行（他测经 TRUNCATE/DDL 隐式提交泄漏，[[no-truncate-in-rollback-tests]]）撞外键 → `DataIntegrityViolationException`。
- 归因：`git stash -u` 移除本 change 全部改动后，这 2 个账号测试仍以**完全相同**的 7 个 ERROR 失败 → 与本 change 无关。
- 修复：加固这两个账号测试 `seed()`——删账号前按外键序 `DELETE FROM contract` → `progress_log` → `` `lead` `` 再删账号（沿用 `LeadWinTest` 既有模式，清理在 `@Rollback` 事务内，不永久改动共享库）。仅测试改动、无生产码、未弱化断言。复测全量 278/0/0 全绿。
- 备注：泄漏 lead/contract 行的源测试未定位/未改，属独立隐患（账号测试已对其健壮）。

## Spec Scenario 覆盖核对（spec R1–R4 vs PRD §11.10）

| Spec Requirement / Scenario | 覆盖用例 |
| --- | --- |
| R1 ADMIN 分页倒序全部 | `admin_listsAllByKeyword_descWithPoolDealLabel` / mapper `selectPage_byKeyword_descWithJoinedFields` |
| R1 按成交销售筛选 | `admin_filterByDealSales` / `selectPage_filterByDealSales` |
| R1 按签订日期闭区间 | `admin_filterBySignedDateRange` / `selectPage_filterBySignedDateClosedInterval`（含界外排除） |
| R1 按客户名/业务类型关键词 | 全部 API/Mapper 用例以唯一客户名 keyword 命中（business_type LIKE 经 FILTERS 同路径） |
| R2 SALES 仅本人 | `sales_seesOnlyOwn` |
| R2 SALES 传他人 dealSalesId 仍收窄 | `sales_otherDealSalesParamNarrowedToSelf` |
| R3 金额千分位不丢精度 | 前端 `合同金额千分位展示…`；mapper 断言 `contractAmount=="120000.50"` |
| R3 公海赢单展示「公海赢单」 | `admin_…PoolDealLabel`（items[0].dealSalesName=="公海赢单"）+ 前端 `公海赢单…` 用例 |
| R3 成交销售当前姓名 | mapper `…descWithJoinedFields`（dealSalesName==s7.name）+ API items[2].dealSalesName |
| R4 纯只读 | `ContractController` 仅 GET；匿名 `anonymous_unauthorized` 401 |
| PRD §11.10 Admin 查看全部合同记录 | `admin_listsAllByKeyword_descWithPoolDealLabel` |

## Residual Risk / Notes

- 关键词为 `LIKE %kw%`，MVP 不加索引、不优化大表（design 已记），数据量增长后再评估。
- 成交销售筛选候选取 role=SALES 全部账号（含已停用），便于回溯历史成交；这是有意选择。
- 未新增 E2E；合同浏览的用户旅程由 API + 前端组件用例覆盖，符合本仓 E2E 仅关键旅程的约定。
