## 1. QA 设计与测试边界

- [x] 1.1 用 `.claude/skills/vibe-coding-qa/templates/` 在 `openspec/changes/frontend-lead-flow/qa/lightweight-test-design.md` 写轻量测试设计：按 spec 6 个需求列单元 / 组件 / E2E 分层与 Red 预期，标注前端校验（即时）与后端裁决（兜底）双轨。
- [x] 1.2 在 `src/test/msw/handlers.ts` 追加 `/leads/*` handler 工厂：`mineLeads`、`allLeads`、`leadDetail`、`pool`(脱敏/明文两态)、`claimSuccess`、`claimAlreadyClaimed`(`LEAD_ALREADY_CLAIMED`)、`progressList`、`addProgressSuccess`、`stageSuccess`、`winSuccess`、`loseSuccess`、`releaseSuccess`、`endedReadonly`(`LEAD_ENDED_READONLY`)、`validationError`(`VALIDATION_ERROR`)。

## 2. API 封装与类型（D1）

- [x] 2.1 写 `src/api/leads.spec.ts`（Red）：断言各函数命中正确方法 / 路径、unwrap 后返回业务负载、错误归一为带 `code` 的 `ApiError`。
- [x] 2.2 实现 `src/api/leads.ts`：导出 `LeadView`/`PoolLeadView`/`ProgressLogView`/`LeadStage` 类型（镜像后端 record）与函数 `fetchMyLeads`/`fetchAllLeads`/`fetchLead`/`fetchPool`/`claimLead`/`releaseLead`/`changeStage`/`winLead`/`loseLead`/`fetchProgress`/`addProgress`，用 `apiClient.get<T,T>` 双泛型，复用既有 `apiClient`/`ApiError`。

## 3. 纯函数工具（D4 / D6）

- [x] 3.1 写 `src/utils/lead.spec.ts`（Red）：`isClosed(stage)` 对已赢单 / 已流失为真、其余为假；金额 `isValidAmount` 覆盖 0 / 负 / 三位小数 / 合法两位小数；`formatAmount` 千分位（含边界与小数）；`LEAD_STAGE` 常量映射。
- [x] 3.2 实现 `src/utils/lead.ts`：`LEAD_STAGE` 常量、`isClosed`、`isValidAmount`（`/^\d+(\.\d{1,2})?$/` 且数值 > 0）、`formatAmount`（千分位，仅展示）。

## 4. leads store（D2）

- [x] 4.1 写 `src/stores/leads.spec.ts`（Red）：`loadMyLeads`/`loadAllLeads`/`loadPool`/`loadLead`/`loadProgress` 写入对应 state 并维护 loading/error；`claim` 成功后从 `pool` 移除并使其归入名下视图；`release` 成功后从 `myLeads` 移除；`addProgress` 成功后把新记录置顶 `progress` 并刷新 `currentLead.lastTrackedAt`；写动作收到 `ApiError` 时透传不吞。
- [x] 4.2 实现 `src/stores/leads.ts`：state `myLeads`/`allLeads`/`pool`/`currentLead`/`progress`/`loading`/`error`，actions 调 §2 API 并维护跨视图联动（claim / release / addProgress）。

## 5. 我的线索列表（spec R1）

- [x] 5.1 写 `src/views/MyLeadsView.spec.ts`（Red）：Sales 渲染名下线索及阶段；Admin 渲染全部线索；点击一行进入 `leads/:id`；空列表有空态。
- [x] 5.2 实现 `src/views/MyLeadsView.vue`：按 `auth.isAdmin` 选 `loadAllLeads`/`loadMyLeads`，Arco `a-table` 展示，行点击路由到详情；`--dt-*` token，禁 Tailwind。

## 6. 公海浏览与认领（spec R2）

- [x] 6.1 写 `src/views/PublicPoolView.spec.ts`（Red）：Sales 展示脱敏电话 + 可见认领入口；Admin 明文 + 无认领入口；认领成功后该线索离开公海视图；认领遇 `LEAD_ALREADY_CLAIMED` 提示"该线索已被认领"且刷新公海。
- [x] 6.2 实现 `src/views/PublicPoolView.vue`：`loadPool` + `a-table`，认领按钮仅 `!auth.isAdmin` 呈现，调 `store.claim`，按 D5 分支 `LEAD_ALREADY_CLAIMED`/`FORBIDDEN`。

## 7. 线索详情与进度跟踪（spec R3 / R4）

- [x] 7.1 写 `src/views/LeadDetailView.spec.ts` 详情+进度部分（Red）：渲染客户 / 业务 / 归属 / 阶段；已流失展示流失原因与说明；进度按时间倒序；进度记录无编辑 / 删除入口。
- [x] 7.2 写 LeadDetailView 进度追加部分（Red）：空跟踪内容即时拦截不发请求；合法提交成功后新记录置顶且 `lastTrackedAt` 刷新。
- [x] 7.3 实现 `src/views/LeadDetailView.vue` 详情信息区 + 进度区（Arco `a-descriptions` + `a-timeline`），进度追加表单（跟踪方式 + 内容必填，无时间字段），调 `store.addProgress`。

## 8. 阶段推进与赢单 / 流失（spec R5）

- [x] 8.1 写 LeadDetailView 阶段 / 赢单 / 流失部分（Red）：非结束阶段间变更生效；赢单金额非法（0 / 负 / 三位小数 / 缺日期）拦截不发请求；合法赢单后阶段=已赢单且金额千分位展示；流失原因"其他"未填说明拦截；已闭单无重复标记入口。
- [x] 8.2 实现阶段选择（仅非结束阶段）→ `store.changeStage`；赢单弹窗（金额 `isValidAmount` + 签订日期校验）→ `store.winLead`；流失弹窗（原因枚举 + "其他"必填说明）→ `store.loseLead`；按 D5 分支 `VALIDATION_ERROR`/`LEAD_ENDED_READONLY`。

## 9. 主动退回公海（spec R6）

- [x] 9.1 写 LeadDetailView 退回部分（Red）：Sales 名下未结束线索可见退回入口；退回备注空拦截不发请求；退回成功后线索离开名下视图。
- [x] 9.2 实现退回弹窗（备注必填）→ `store.release`；入口仅 `SALES` 名下且非结束线索呈现。

## 10. 闭单只读（spec R7）

- [x] 10.1 写 LeadDetailView 只读部分（Red）：已结束线索不呈现进度追加 / 阶段 / 赢单 / 流失 / 退回任一写入口；写操作遇 `LEAD_ENDED_READONLY` 提示并据后端刷新只读态。
- [x] 10.2 用 §3 `isClosed` 集中派生，统一 `v-if` 收起所有写入口；写动作 catch `LEAD_ENDED_READONLY` 后刷新 `currentLead`。

## 11. 路由接线

- [x] 11.1 在 `src/router/index.ts` 把 `my-leads`/`public-pool` 占位替换为真实视图，新增受保护子路由 `leads/:id` → `LeadDetailView`（沿用既有守卫，不改守卫逻辑）。

## 12. E2E 与全量验证

- [x] 12.1 写 `tests/e2e/lead-flow.spec.ts`：认领 → 进入我的线索 → 打开详情 → 追加进度 → 标记赢单后只读，缺 E2E 凭据时 `test.skip`。
- [x] 12.2 跑 `pnpm test:unit` 全绿、`pnpm build`（vue-tsc + vite）通过；用 `node .claude/skills/vibe-coding-qa/scripts/qa_artifacts.mjs check` 校验 qa 产物；`openspec validate frontend-lead-flow --strict` 通过。
- [x] 12.3 在 `openspec/changes/frontend-lead-flow/qa/` 补 `qa-test-report.md` 与 `regression-impact-analysis.md`（含 Red 证据与回归影响）。
