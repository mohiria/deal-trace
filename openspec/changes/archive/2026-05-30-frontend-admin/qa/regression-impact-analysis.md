# Regression Impact Analysis — frontend-admin

## Changed Surface

| 文件 | 改动性质 | 回归风险面 |
| --- | --- | --- |
| `src/api/leads.ts` | 追加 assign/recall/transfer（纯新增导出） | 低：未触碰既有函数 |
| `src/stores/leads.ts` | 追加 assign/recall/transfer（纯新增 action） | 低：既有 claim/release/changeStage/win/lose 链路未改 |
| `src/views/LeadDetailView.vue` | 新增归属操作区 + 两个弹窗 + onMounted 追加 `loadAccounts`(仅 Admin) | 中：与既有详情/进度/闭单/退回同页共存 |
| `src/components/navigation.ts` | 移除「系统日志」条目 | 低：声明式表，仅少一条目 |
| `src/router/index.ts` | `/users` 占位 → `UsersView`；`/system-logs` 保持占位 | 低：路由 meta 不变 |
| 新增文件 | `api/accounts.ts`、`stores/accounts.ts`、`views/UsersView.vue` 及各 spec | 无既有耦合 |

## Regression Checks（证据）

| 风险 | 检查 | 结果 |
| --- | --- | --- |
| LeadDetailView 既有行为（详情/进度/阶段/赢单/流失/退回/闭单只读） | `LeadDetailView.spec.ts` 既有 16 用例 | 全 Green（未改动既有断言） |
| Admin 详情页因新增 `loadAccounts` 触发未 mock 请求 | mountView 注册 `accountsList`；`onUnhandledRequest:'error'` 仍生效 | 全 Green，无未处理请求 |
| `isClosed` 闭单收起逻辑 | 「已结束线索不呈现任何写入口」+ 新增「归属区不对已结束呈现」 | Green |
| `currentLead` 刷新链路 | leads store 既有 8 用例 + 新增 4 归属用例 | Green |
| 导航显隐（用户管理仍可见，系统日志移除） | AppShell.spec 既有 + 新增「ADMIN 不呈现系统日志」 | Green |
| 路由守卫 requiresAdmin（/users 沿用同机制） | guards.spec 既有 requiresAdmin 用例（机制与路由名无关） | Green |
| 全量套件 | `vitest run` 全量 | **167/167 passed, 77 files, 0 failed** |
| 类型 | `vue-tsc -b` | 0 error |

## Conclusion

无回归。本批次以纯新增为主，对既有 `LeadDetailView` 的扩展通过既有 16 用例 + 新增 12 用例共同守护；归属入口的显隐与既有 `isClosed` 收起、`currentLead` 刷新模式同构，未改写任何既有断言或期望行为。
