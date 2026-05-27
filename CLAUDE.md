# CLAUDE.md

为 Claude Code（claude.ai/code）在本仓库工作时提供项目级元指令。

## 协作约定

- **据实回应**：陈述以事实和证据为准，不使用社交性赞美（"很好""干得漂亮"等），不复述用户已说过的内容。
- **不迎合**：发现用户的判断、前提或假设有误时直接指出，附引用依据（PRD/tech-arch 出处、代码引用、可复现错误、官方文档）；不为顺从而附和。
- **中文回复**：对话回复使用中文；规格 / 设计 / 任务文档沿用 PRD 与 tech-arch 的中文惯例；代码标识符保持英文。

## 权威优先级与冲突门

文档冲突时按以下顺序仲裁：

1. `docs/产品需求文档 (PRD).md`（PRD v3.3）
2. `docs/技术架构与工程约束.md`
3. `openspec/specs/`（已归档稳定行为）
4. `openspec/changes/<change>/`（在途变更）
5. `openspec/changes/<change>/qa/`（QA 产物）

OpenSpec 输出或测试与 (1)(2) 冲突时**停下来触发 Requirement Conflict Gate**——不要私自放宽断言或改写期望行为。规则定义见 tech-arch §13.6 与 `.claude/skills/vibe-coding-qa/CLAUDE.md`。

## 两阶段工作流（硬约束）

行为变更必须走两阶段，不允许"直接写代码"。

**阶段 1：OpenSpec 把 PRD 转成行为契约**

| 命令 | 作用 |
| :--- | :--- |
| `/opsx:explore <topic>` | 思考、研究，不实现 |
| `/opsx:propose <change>` | 生成 `proposal.md` / `design.md` / `tasks.md` / `specs/<capability>/spec.md` |
| `/opsx:apply <change>` | 按 `tasks.md` 实现 |
| `/opsx:archive <change>` | 完成后归档，把 delta spec 升级到 `openspec/specs/` |

`spec.md` **只写 Given-When-Then 行为契约**——禁止包含 DB schema、类名、文件路径或实现步骤。那些归 `design.md` 和 `tasks.md`。

**阶段 2：vibe-coding-qa 以 TDD 落地**

改任何生产代码前，必须存在以下之一（详见 `.claude/skills/vibe-coding-qa/SKILL.md` 和 `references/qa-constitution.md`）：

1. 当前行为对应的轻量测试设计
2. 有效的 Red 证据——测试因**预期行为原因**失败（语法 / 导入 / fixture / 环境失败属于阻塞，不是 Red）
3. 已有的可复用失败测试
4. 书面记录的非 TDD 例外 + 备选验证 + 剩余风险
5. 明确写下的、阻止写 Red 测试的具体阻塞

测试分层就近：单元（业务规则、纯函数）→ API/集成（Testcontainers + 真 MySQL 8.4，禁 H2、禁 mock）→ E2E（仅关键用户旅程，场景优先，不要求严格 Red-Green）。

**反作弊**：禁止削弱断言、删负例、跳过测试、为让套件通过而改期望行为；改 / 删既有测试必须先声明需求权威依据。

QA 设计 / 报告产物入 `openspec/changes/<change>/qa/`（用 `.claude/skills/vibe-coding-qa/templates/` 做骨架）；自动化测试代码入 `backend/src/test/...` 或 `frontend/src/**/*.test.ts` 或 `frontend/tests/e2e/...`，不入 `qa/`。

## 反直觉规则（不读会写错）

- **目录不存在就是没做**：`backend/` / `frontend/` 不存在意味着对应阶段未开始；不要假装代码已存在，也不要"顺手"先建空目录绕过 OpenSpec 流程。
- **USCI 先归一化再校验**（PRD §7.2 / §8.1）：trim → 字母转大写 → 校验 18 位标准格式含校验位。顺序反了会把 `91110000abc...` 当不同记录。
- **客户名称按 trim 后查重**，不做别名 / 简称 / 模糊合并（MVP 范围）。
- **客户主体不带联系信息**：联系人、联系电话、线索来源、归属销售都在 lead 上，不在 customer 上。
- **线索查重键 = (自然年度, 客户, 业务类型)**（PRD §7.3 / §8.2）：年度由创建时间派生不可人工改；"进行中"或"已赢单"同类存在则拦截；**只剩"已流失"时允许新建，但 UI 必须提示历史流失原因和时间**；联系人不参与判重。
- **公海认领是事务操作**（PRD §7.5）：并发认领只一个成功，其余返回 `LEAD_ALREADY_CLAIMED`。停用 Sales 不能认领、不能被分配、不能被转入。认领前看脱敏号，认领成功后才看明文。
- **闭单只读**（PRD §7.7 / §8.5）：已赢单 / 已流失线索不能加进度跟踪、不能改阶段、不能退回公海、不能再分配 / 回收 / 转移、不能重复标记；MVP 无撤销。每条线索最多 1 条合同记录（DB 强约束）。
- **进度跟踪与系统日志均为追加流**：包括 Admin 都不能编辑或删除任何一条；纠正只能再追加一条。时间戳由服务端生成，禁用客户端时钟。
- **手机号脱敏分级**（tech-arch §9.4）：11 位手机号 → `138****5678`（3+4）；≥8 位其他号码 → 前 3 后 4 + 中间 `****`；<8 位 → 仅显示末 2 位。
- **Dashboard 归属语义双轨**（PRD §7.12）：存量指标（"今日新增"）用**当前归属**；事件指标（"本月赢单金额""本月流失率"）用**事件发生时归属**。本月流失率分母为 0 显示 `--`，不是 `0%`。
- **认证由部署配置注入**（PRD §7.1）：无自助注册；初始 Admin 邮箱 + 密码由部署配置提供。停用 Sales 不会自动转交线索——Admin 必须手动回收或转移。
- **禁止引入 Tailwind**：与 Arco Design Vue 主题体系冲突（tech-arch §10 明文禁止）。
- **集成测试用真 MySQL 8.4**（tech-arch §12）：Flyway 迁移敏感路径必须跑真库，不准用 H2 也不准 mock。具体编排方式（连接配置、测试基类隔离策略）在 scaffold 的 design.md 中固定。
- **schema 命名禁带环境标签**：database 永远叫 `dealtrace`，表 / 列 / 索引 / 外键按业务命名；所有环境（联调、自动化测试、未来生产）共用同一份 schema 设计，仅靠 `spring.profiles.active` + 连接配置切换实例。
- **金额用精确数值类型**：不用 float / double。

## 找东西去哪里

| 你要做 | 看哪里 |
| :--- | :--- |
| 找业务规则源头 | `docs/产品需求文档 (PRD).md`（PRD v3.3） |
| 找技术约束 / 技术栈 | `docs/技术架构与工程约束.md`（栈见 §4） |
| 找 MVP out-of-scope 清单 | PRD §10（提议这些功能前先查） |
| 找 OpenSpec 命令行为细节 | `.claude/commands/opsx/{propose,explore,apply,archive}.md` |
| 找 TDD/QA 强制规则 | `.claude/skills/vibe-coding-qa/SKILL.md` + `references/qa-constitution.md` |
| 找 OpenSpec artifact 模板（proposal / spec / design / tasks） | `openspec instructions <artifact> --change <name> --json` 的 `template` 字段；或 `openspec templates --json` 查文件路径 |
| 找 QA 模板（QA 报告 / 测试设计 / 回归影响 / 缺陷） | `.claude/skills/vibe-coding-qa/templates/` |
| 找前端视觉参考 | `prototype/dealtrace-workbench.html`（提取设计 token，不要 1:1 复刻） |
