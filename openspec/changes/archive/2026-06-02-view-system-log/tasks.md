## 1. QA 测试设计（先于生产代码）

- [x] 1.1 在 `openspec/changes/view-system-log/qa/` 用 vibe-coding-qa 模板产出测试设计：覆盖线索维度读权限隔离、全局页 ADMIN-only、姓名/标签/千分位组装、结构化 vs freetext 双路径、写侧 12 事件 detail 形状
- [x] 1.2 标注分层：写侧 detail 形状走单元/服务测试；两个读端点权限与排序走 API/集成测试（真 MySQL 8.4）；前端时间线与全局页走组件测试
- [x] 1.3 记录回归影响范围（12 个写点改造、`SystemLogPort` 签名扩展）与非 TDD 例外（若有）

## 2. 数据迁移与写侧结构化 detail

- [x] 2.1 新增 `V8` 迁移：`ALTER TABLE system_log ADD COLUMN detail JSON NULL`（纯加列、无回填）；保留字 `lead` 相关查询反引号
- [x] 2.2 `SystemLogPort` 增承载结构化 `detail` 的 `record(...)` 重载；既有 4/5 参方法委派为 `detail=null`，account 调用零修改
- [x] 2.3 `JdbcSystemLogPort` 序列化 `detail` 为 JSON 入库；`Slf4jSystemLogPort` fallback 行为不变；**先写** detail 形状的失败测试（Red）再实现
- [x] 2.4 为每类 action 定义 detail 形状（倾向类型化 record，见 design Open Question）：归属事件存 `fromOwnerId`/`toOwnerId`、STAGE 存原/新阶段码、WIN 存精确金额+`signedDate`、LOSE 存原因码+说明、ACCOUNT_* 结构化
- [x] 2.5 改写 5 个归属写点（CLAIM/RELEASE/ASSIGN/RECALL/TRANSFER）：删除 `email`/`ownerLabel` 入参、改传 `ownerId`；`summary` 仍写作为 fallback
- [x] 2.6 改写 STAGE_CHANGE / WIN / LOSE / LEAD_CREATE / ACCOUNT_CREATE/ENABLE/DISABLE 写点填结构化 detail
- [x] 2.7 验证写侧"不阻塞业务主流程"与"不可变""服务端时间戳"等既有要求未回归（既有测试为回归网）

## 3. 后端读出能力

- [x] 3.1 新增 `SystemLogMapper`：`selectByLeadIdOrderByCreatedAtDesc(leadId)`、`selectGlobalPaged(action, targetType, offset, limit)`、`countGlobal(action, targetType)`
- [x] 3.2 新增 action→中文标签集中映射（后端枚举/常量，单点维护）
- [x] 3.3 新增日志读 DTO：`action`/`actionLabel`/`operatorName`/`createdAt`/结构化展示字段/`summaryFallback`
- [x] 3.4 新增 `SystemLogReadService`：批量解析账号 id→当前姓名（复用 `selectBatchIds` 套路），`operator_id`=NULL→"系统"，归属空→"公海"；`detail` 非空走结构化、NULL 走 freetext fallback
- [x] 3.5 `LeadController` 增 `GET /{id}/logs`：授权镜像 `ProgressLogService.list`（SALES 非自己名下/不存在统一 `NOT_FOUND` 不泄漏），倒序
- [x] 3.6 新增 `AdminSystemLogController` 挂 `/admin/system-logs`：分页+倒序+`action`/`target_type` 筛选；依赖 `SecurityConfig` 现有 `hasRole(ADMIN)`，SALES 自动 `403 FORBIDDEN`；服务端强制分页 size 上限

## 4. 后端集成/单元测试（真 MySQL 8.4）

- [x] 4.1 `GET /leads/{id}/logs`：ADMIN 任意线索倒序；SALES 自己名下成功；SALES 他人/公海 → `NOT_FOUND`；不存在 → `NOT_FOUND`（按 `lead_id` 过滤断言，**禁 raw TRUNCATE**）
- [x] 4.2 仅返回该线索条目、不含 account 事件；新旧条目（结构化+freetext）混排倒序
- [x] 4.3 `GET /admin/system-logs`：ADMIN 分页倒序含 account 事件；`action`/`target_type` 筛选；SALES → `403 FORBIDDEN` 且无数据
- [x] 4.4 组装断言：operator NULL→"系统"、归属 id→当前姓名、公海→"公海"、`action`→标签、金额精确不丢精度
- [x] 4.5 写侧 detail 形状单测：每类 action 落库 detail 字段与触发参数一致；`detail=NULL` 旧行读取不抛错

## 5. 前端

- [x] 5.1 新增 `api/systemLogs.ts`：`listLeadLogs(leadId)` 与 `listGlobalLogs(params)`（镜像 `api/leads.ts` 的 `listProgress`）
- [x] 5.2 store 扩展 `loadSystemLog(leadId)` + 状态（镜像 `loadProgress`）
- [x] 5.3 `LeadDetailPanel.vue` 新增系统日志时间线 section：复用 `.progress-list`/`.event` 样式，倒序；金额千分位用 `formatAmount`；归属/操作人展示姓名
- [x] 5.4 恢复 `router/index.ts` 的 `system-logs` 路由（去占位）+ `navigation.ts` 恢复 ADMIN 导航入口
- [x] 5.5 新增全局日志浏览页：Arco Table（时间/操作人/动作/目标/摘要），分页 + `action`/`target_type` 筛选，LEAD 目标可跳详情；**禁 Tailwind**，用 Arco 主题 token
- [x] 5.6 组件测试：详情时间线渲染与倒序、全局页分页/筛选、ADMIN-only 入口可见性；Arco `a-modal`/`a-select` 测试设 `:render-to-body="false"`

## 6. 验证与归档准备

- [x] 6.1 `cd backend && mvn verify`（勿与 dev backend smoke 并发——共用 dealtrace 实例）
- [x] 6.2 `cd frontend && npm run test` 全绿
- [x] 6.3 端到端手验：建线索→阶段变更→赢单，详情页系统日志时间线倒序、姓名/金额正确；ADMIN 全局页可见 account 事件，SALES 无入口且端点 403
- [x] 6.4 反作弊自检：无削弱断言/删负例/跳过测试；`openspec validate view-system-log` 通过后方可 `/opsx:apply`
