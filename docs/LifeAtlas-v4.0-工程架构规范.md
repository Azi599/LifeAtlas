# Life Atlas v4.0｜Android 工程架构与交付规范

**状态：开发基线候选版（Architecture Baseline Candidate）**  
**日期：2026-11-04**  
**依据：Life Atlas 需求文档 v3.0 + 六轮 GrillMe（题号 1–144）**  
**目的：交付其他开发 AI 或 Android 工程团队，作为重新建项、开发、测试与验收的统一依据。**

> 本文优先采用用户在最近轮次作出的明确选择。旧 v3.0 属于参考草案，并非不可更改的硬约束。文中以 **[已确认]、[实施建议]、[待验证]、[暂缓]** 标记决策性质。**不得把本文件等同于已有的软件或已经完成的测试。** 本版只定义工程方案，没有对用户 Windows 电脑上的旧工程作源码审计，也没有构建 APK。

---

## 0. 产品目标、范围与基本原则

Life Atlas 是 **Android 12 及以上（minSdk 31）** 的原生、本地优先、插件宿主式个人生活数据平台。默认无需注册即可记录、查询、修改、导出、统计；联网并开启 AI 后，首页统一输入框成为主要操作入口。每一项功能仍需有可靠的可视化操作路径。

目标：
1. 将生活事实统一纳入**基础数据快照（SNAPSHOT）/基础数据变化（CHANGE）/时间分配（ALLOCATION）**三种互斥记录类型，同时用 `LifeEvent` 容纳文字事件和关联多条记录。
2. 建立可动态安装、停用、升级、回滚的插件系统；AI 可以生成指标、规则、组件和完整功能代码，但交付机制按插件级别分别设计。
3. **AI 理解并发出结构化操作；业务模块暴露受控操作接口；统计引擎确定性计算；可视化层渲染。** AI 不作为数据库或计算结果的事实来源。
4. 用户可自行配置 OpenAI 兼容的云端 API；将来可选用“支持作者”的月订阅 AI 服务（有明确额度上限，定价待实测），首版不启用该商业服务。
5. 第一阶段就准备并完成 **100 万条以上历史记录**的基准测试；动态界面保持原生中文排版、流畅响应、兼容屏幕尺寸和字体放大。
6. 从零创建新工程；旧 APK/工程仅作为产品行为与失败案例参考，不继承未经审计的实现。

**硬边界：** 目前只支持人民币；Health Connect 暂不接入；多设备同步后置；默认数据库不加密，设置可选择开启加密；无需账号即可本地使用，外部 AI 发送的数据范围必须可见且经用户授权；用户上传图片默认只保留结构化结果，用户可选保留原图；删除记录进入回收站，默认保留 30 天；恢复旧备份前自动创建本地恢复点。

---

## 1. ADR：架构决策记录

| ADR | 决策 | 状态 | 解释/拒绝的替代方案 |
|---|---|---|---|
| ADR-001 | 另起原生 Android 项目；Kotlin、Compose、Material 3、Room/SQLite、Coroutines、Flow | 已确认+实施建议 | 不在旧工程堆补丁；不将网页简单套壳 |
| ADR-002 | `app` 宿主 + `core` 基础设施 + 独立领域插件 + 可选 domain/use cases | 已确认+实施建议 | 不把所有功能强塞一个 Activity 或一个表 |
| ADR-003 | 所有正式记录恰属 SNAPSHOT / CHANGE / ALLOCATION 之一；事件容器不属于三类 | 已确认 | 记录互斥与事件多关联并不冲突 |
| ADR-004 | 时间字段分别记录发生时间、录入时间、修改时间、时区、本地日期，追踪修订史 | 已确认 | 不用录入时刻覆盖过去发生的时刻 |
| ADR-005 | 首页唯一共享输入组件，AI 模式默认云端理解，本地模式覆盖基础增删改查、计时、导航和简单统计 | 已确认 | 云端不可用不应导致数据系统不可用 |
| ADR-006 | AI 用插件公开的 Action 接口生成并执行操作；宿主提供注册、参数校验、排序、回执、重试和历史 | 已确认+实施建议 | AI 不直接执行原始 SQL 或写 Room DAO |
| ADR-007 | 插件三级：声明式、受限解释式、原生构建式 | 实施建议 | 不能把云端编译的任意 DEX 当作 Play 版本中的热更新包 |
| ADR-008 | AI 生成新插件后先预览、用户确认再启用；插件可以有丰富布局但遵循宿主设计系统 | 已确认 | 不能只生成一套不兼容手机的随意布局 |
| ADR-009 | 数据通过统一时间索引、分层摘要、按需细节检索提供给 AI，不将整库送进上下文 | 已确认 | 周/月/年是查询视图和聚合表，不必物理拆为很多数据库 |
| ADR-010 | 流程采用命令计划 + 状态机 + 幂等写入 + 可回溯操作日志 | 实施建议 | 防止重试重复记账与部分执行误标成功 |
| ADR-011 | Android 12+；Google Play + APK 双渠道，渠道适配不同分发方案 | 已确认 | 原生插件发布必须遵守渠道适用规则 |
| ADR-012 | 本地纯算法生成事实和统计；云端 AI 负责理解、描述、报告组织 | 已确认 | 云端 AI 不代替金额、时长和相关性计算 |
| ADR-013 | 用户明确补录历史后先显示影响范围，再确认变更；提交后相关派生结果必须重算 | 已确认+实施建议 | 不能让历史事实改变却选择保留错误的当前统计 |
| ADR-014 | 双 AI 提供方式：自带密钥启用；作者订阅待商业服务上线 | 已确认 | 暂不推出收费或承诺额度 |
| ADR-015 | 安装包交付必须有实际运行、自动测试、截图、性能测量证据 | 已确认 | “生成代码/编译成功”都不等于交付合格 |
| ADR-016 | 首轮开发优先统一输入到保存/查询/修改/统计的闭环；插件协议与数据底座同步做到最小可用 | 已确认 | 不先做大量静态页面或大量空插件 |

### ADR-007 的渠道约束

**Play 发行版：** 下载并执行非 Play 来源的 DEX/JAR/SO 原生代码存在政策限制；原生功能通过受控应用版本更新或构建时打包的 Play Feature Delivery 交付。配置型插件可以即时启用；解释型扩展需限定在允许的运行与 API 能力范围内，发布前单独做政策审查。**直装 APK 发行版：** 可以设计自己的签名和版本更新机制，但原生模块安装/运行仍受 Android 平台限制；不能假定 APK 安装后就能无障碍热插拔任意 Kotlin 代码。

云端构建服务是 P2 功能：接收 AI 生成的源码→使用锁定依赖环境编译→自动测试→签名发布→宿主确认兼容并更新/安装。**不能把它与实时生效的声明式插件混为一个能力。**

---

## 2. 模块与依赖

```text
LifeAtlas/
  app/                         # Activity, Navigation, Hilt 组装, 构建渠道
  core/
    model/                     # Event, RecordKind, Value, Time, ID
    contracts/                 # Action, Plugin, UI Schema, Statistics 接口与版本
    storage/                   # Room/SQLite, 文件, Migration, DataStore
    clock/                     # 时间/时区、Clock 抽象、计时恢复
    commands/                  # Planner, Dispatcher, Validator, Policy, Status
    ai/                        # Provider Adapter, Prompt/Schema, Context Tools
    statistics/                # 纯 Kotlin 统计、聚合、派生结果与缓存
    search/                    # 时段筛选、聚合摘要、分页、检索工具
    plugins/                   # Plugin Registry, Manifest, Resolver, Lifecycle
    dynamic-ui/                # DSL/Schema Renderer, 动态布局
    designsystem/              # 字体、颜色、密度、卡片、图表基础组件
    import-export/             # JSON/CSV, 备份, 恢复点, 批处理
    testing/                   # 工具、夹具、契约测试与样例生成
  plugins/
    finance/                   # 账户与人民币交易
    body/                      # 快照指标
    sleep/                     # 睡眠时段
    learning/                  # 学习时段与成绩
    time/                      # 统一计时、重叠处理
    habits/                    # 次数变化/规则
    reading/                   # 页数/进度/时段
    custom/                    # 动态指标与插件扩展
  feature/
    home/ capture/ history/ analytics/ timeline/ settings/
  benchmark/                   # 宏基准测试
  plugin-builder-client/       # 可选云端构建请求（P2）
```

**依赖单向：** `feature → domain/公开 contract → repository/service`；领域插件只能通过合同调用宿主或其他插件，不直接访问其他插件的 DAO；UI 仅观察 `UiState` 并发出意图；核心统计算法不依赖 Android UI、AI 或网络。`app` 完成运行时组装与插件注册。

**演进策略：** 不要求从第一天就制造几十个 Gradle 模块；可按上面的边界先建立 `core` + `app` + `plugins` 等少量可测试模块，复杂度增长时再拆。插件的运行时边界比 Gradle 模块数量更重要。

---

## 3. 数据概念与不可变事实

### 3.1 模型

- `LifeEvent`: 事件容器，`eventId, sourceInputId?, title?, description?, occurredAt?, createdAt, updatedAt, zoneId, attachmentsRef[]`。允许只有文本而没有正式三类记录。
- `Record`: `recordId, eventId, kind (SNAPSHOT | CHANGE | ALLOCATION), metricId, pluginId, schemaVersion, occurredAtUtc, localDate, zoneId, createdAtUtc, updatedAtUtc, source, status, qualityFlags, version`。
- `SNAPSHOT`: 某一时刻的测量或状态，例如体重、账户余额、评分、进度；不同快照可计算差异，但不是新发生的变化事实。
- `CHANGE`: 真实发生的差量、次数增加、收入、支出、转账、人工调整；允许只知道变化量而不知道绝对值。包含 `direction`, `delta`, `unit`, `accountRef?`, `relationGroupId?`。
- `ALLOCATION`: 活动开始/结束时间、暂停区间、有效时长、主类别、辅助标签、计时状态；可以只知道时长而不知道确切起止时间。
- `MetricDefinition`: 类型、规范单位、允许的聚合、可视化规则、版本、字段约束。AI 创建插件时必须有稳定的 `metricId`。
- `Operation`: 一切创建、修改、删除、恢复、分类重设等变更的来源、版本、影响集和逆向操作信息。

### 3.2 数据实例

1. 「今天体重 65kg」→ 一个事件 + 一个 SNAPSHOT，后续与其他体重快照比较，生成派生趋势而非额外事实。
2. 「比昨天轻 1kg」但无基准→用户此前选择要求补充至少一个绝对体重后才能作为可计算体重变化；无回答则保留未完成对话/原文。
3. 「存款增加 1000元」→ 追问来源；若未回答，建立未分类 CHANGE，标注财务归因待核对。是否可归集到具体账户取决于是否已知账户，否则不得凭空选择账户。
4. 「银行卡转 500元到微信零钱」→ 一个事件、两条 CHANGE（转出 -500、转入 +500），共享 `transferGroupId`，总资产净变化为 0，不当作消费/收入。
5. 「学习 3小时，其中阅读 1小时」→ 一条主 ALLOCATION（学习）及关联的子时间区间/标签（阅读），两个领域视图可呈现自己的数值；全天占用时间按区间并集或主类别去重，不做 3+1=4 的错误累加。
6. 「今天心情不错」→ 建议建立主观状态指标，第一次出现由 AI 显示选项卡；可选择仅保存原始事件文本。
7. 「锻炼一次」→ 计数型 CHANGE +1，不假装有准确锻炼时长。
8. 「读了20页」→ 依据已有阅读进度按页数增量或进度快照处理；不清楚时由 AI 给选项卡。
9. 「图片像消费但金额不明」→ 用户允许保存带空金额的正式记录，但金额统计排除，UI 标记字段待补全；**不能以0代替缺失**。

### 3.3 快照与流水的财务投影

**已确认规则：** 在时点 `t_snapshot` 记录余额后，当前推算账户余额 = 该有效余额快照 + 在该快照之后发生、明确属于该账户且尚未包含于快照的已确认流水。不能把快照前的收入再次累加。对于补录快照前的交易，展示潜在影响并确认；接受后重建受影响时间段的派生投影。

原始记录和派生视图应分离：
- `ledger_transactions` 和 `balance_snapshots` 是数据事实。
- `account_balance_projection` 是可以重建的缓存。
- 当推算余额与最新实测不符，保留快照与差额、显示核对卡，不自动编造支出。
- 未指定付款账户的消费可计入总体消费，不能未经确认直接扣减具体账户。
- 不明来源资金变化可在待归因视图中计入声明的总变化，但必须避免在账户合计中无依据地重复记账。

### 3.4 时间语义

- 操作发生时使用设备 `Clock` 获取“现在”，不需要持续高频轮询系统墙上时钟。
- 存 `occurredAtUtc`（事实发生）、`createdAtUtc`（录入）、`updatedAtUtc`（修改）、`zoneId`、当时 `localDate`；区分事件时间与操作时间。
- 计时实时显示可使用单调时间源辅助刷新；跨重启恢复依据持久化时刻重新计算；检测墙上时钟/时区变化并提示异常。
- 历史补记和撤销是合法的过去事实修订，需要记录版本及变更时间，不得把今天录入的事件错误记成今天发生。
- ALLOCATION 跨午夜存一条事实；日占用时间统计按本地午夜切分；睡眠报告归属入睡日期，日生活时间分配按实际午夜切分。
- 计时状态：`RUNNING, PAUSED, COMPLETED, ABANDONED`；保存启动时刻和暂停区间，退出 App 或重启不丢。
- 首次启动某类计时需设置**达到多少分钟提醒**，用户第四轮选择先设置后启动；中途暂停只统计有效运行时长；多个暂停活动说“继续”时列候选。

### 3.5 操作历史

每次修改创建可追溯版本记录；支持查看每次修改、选择某版本恢复。删除进入30天回收站；自动清理期限基于删除时间。备份覆盖前生成本地恢复点；恢复流程须可校验回滚。

---

## 4. 推荐数据库与索引

| 表/集合 | 核心字段 | 关键索引与说明 |
|---|---|---|
| `life_events` | `event_id, occurred_at, created_at, zone_id, description` | `(occurred_at, event_id)`；保留纯文字事件 |
| `records` | `record_id, event_id, kind, metric_id, plugin_id, status, occurred_at, local_day, version` | `(metric_id, occurred_at)`, `(kind, local_day)`, `(event_id)` |
| `snapshot_values` | `record_id, value_type, scaled_value, scale, text_value, unit` | 主键 `record_id`，类型互斥约束 |
| `change_values` | `record_id, signed_delta_minor?, scaled_value?, account_id?, group_id?, reason` | `(account_id, occurred_at)`, `(group_id)` |
| `allocations` | `record_id, start_at?, end_at?, stated_duration?, primary_category, state` | `(state)`, `(start_at)`, `(primary_category, start_at)` |
| `allocation_pauses` | `allocation_id, pause_at, resume_at?` | `(allocation_id, pause_at)` |
| `metric_definitions` | `metric_id, plugin_id, schema_version, unit, semantics, agg_rules` | 唯一插件内指标编号 |
| `plugin_registry` | `plugin_id, manifest_version, status, source, compatibility` | 版本/兼容性 |
| `input_captures` | `capture_id, raw_text, captured_at, modality, source_refs` | 原始输入与解析尝试 |
| `command_plans` | `plan_id, capture_id, context_id, status, schema_version` | 记录 AI 操作计划 |
| `command_steps` | `step_id, plan_id, action_id, args, status, dependency_ids, idempotency_key` | 单步追踪与去重 |
| `operation_history` | `operation_id, target_id, before, after, actor, occurred_at` | 记录版本变化 |
| `trash` | `record_ref, deleted_at, purge_after` | 30天清理 |
| `aggregate_daily/monthly/yearly` | `metric_id, period, value, source_revision, coverage` | 增量统计缓存，按需失效 |
| `search_index` | `event_id, text/keywords, time, metric_refs` | 文本搜索可用 FTS5 等合适实现 |
| `attachment_refs` | `attachment_id, type, local_path?, retained` | 默认不永久保存原图 |
| `backup_restore_points` | `restore_id, time, checksum, path, status` | 覆盖前恢复点 |

统一可扩展存储并不意味着所有不同性质的值都放进一个无约束 JSON 列；这会损害金额、时间和跨领域查询。建议**公共索引 + 三类专用值表 + 插件附加属性**，后者可通过版本化 JSON/扩展表承载；重要可检索字段需有索引或物化视图。

金额存人民币最小货币单位 `Long`；其他测量数值可使用整数缩放值和明确单位；统计计算保存算法版本、样本量、缺失处理与来源覆盖。所有大批量写入、转账双腿、版本迁移必须具备事务边界；批量导入成功项与异常隔离遵循用户确认的规则。

---

## 5. 插件协议 v1

### 5.1 三级运行形态

- **Tier 1：声明式插件**：指标、字段、校验规则、统计映射、UI schema、图表、操作定义。AI 生成→结构校验→原生预览→用户确认→即时启用。首版主力。
- **Tier 2：受限解释式插件**：自定义表达式/脚本的业务规则和计算，通过宿主能力代理访问数据，具备 CPU/内存/时限约束；具体解释引擎在原型验证后选定。
- **Tier 3：原生代码插件**：AI 生成 Kotlin/Compose、依赖构建工具链，云端隔离编译与测试，通过与发行渠道兼容的应用更新/交付机制部署。P2 实施。

**公共宿主能力：** 插件安装、更新、停用、版本迁移、卸载、恢复前版本、依赖解析、错误隔离、兼容性检查、UI 渲染、统计注册、Action 注册、操作历史接入、基准测试。

### 5.2 清单 schema 示例（仅接口样例）

```json
{
  "pluginId": "lifeatlas.custom.reading-v2",
  "version": "1.0.0",
  "contractVersion": 1,
  "tier": "declarative",
  "minHostVersion": "4.0.0",
  "metrics": [{"id":"reading.pages","kind":"CHANGE","unit":"page","aggregation":"sum"}],
  "actions": [{"id":"reading.addPages","inputSchemaRef":"schemas/addPages-v1"}],
  "screens": [{"id":"reading.dashboard","layoutRef":"ui/reading-dashboard-v1"}],
  "capabilities": ["records.read:reading", "records.write:reading"],
  "migrations": []
}
```

`contractVersion`、`minHostVersion`、`version` 必须单独管理。插件 UI 由宿主原生 Compose 组件与插件 UI Schema 渲染，允许自定义布局/图表但有明确的组件能力白名单、字体和间距约束。不能假设插件随意返回任意 Compose 源码就能在商店版即时运行。

### 5.3 动态 UI 与 Action 接口

宿主渲染器最少支持：`Text, Card, Metric, Form, Choice, Tabs, List, Chart, Timeline, Dialog, DetailView, Error, Loading`，并允许有版本化的新组件注册。所有选项文本必须完整换行；原生组件统一提供边距、状态栏/键盘适配、无障碍语义。

插件公开的 Action 合同必须声明：`actionId, pluginId, inputSchema, outputSchema, readSet, writeSet, reversible, confirmationPolicy, idempotencyPolicy, schemaVersion`。跨插件操作由宿主 Dispatcher 调度，而不是插件自行解析自然语言然后偷偷修改其他领域的数据。

### 5.4 插件生成、更新、失败回退

用户输入“加一个家庭植物养护插件”→AI 提出该插件数据结构/页面/指标/统计→生成声明式插件→宿主验证与渲染预览→用户确认→启用→提供示例数据测试→可停用/更新/回滚。更新 schema 时，先展示迁移影响范围，用户选迁移或保留旧版本。任何失败都不能破坏宿主和旧数据。

---

## 6. 首页统一输入与操作协议

### 6.1 交互形态

首页顶部**唯一输入组件**（首页与记录页共用同一实例/逻辑），原地展开；键盘弹起时输入区保持可见，结果在下方可滚动。全部操作记录表现为可搜索、可折叠的时间对话流；单次成功只给紧凑结果，复杂任务展开步骤详情或详情页。所有功能保留可视化操作路径；首次仅有一次性引导，应用中提供完整操作说明书，常态页面极简。

### 6.2 输入与规划

```text
Capture(文字/语音/图片/文件)
 -> AI Provider (开启云端时全部自然语言优先发送；断网本地规则兜底)
 -> Structured Plan JSON
 -> Schema Validator + Plugin Registry Resolver + Temporal Resolver
 -> Confirmation/Choice Cards (只在需要时)
 -> Dispatcher (按依赖顺序执行)
 -> Plugin Action Interfaces -> Repository Transactions
 -> Event/Record/History
 -> Aggregate Invalidation + UI Result Stream
```

图片完整识别且无冲突时可自动记录；如果金额缺失，提示填写或直接保存（值为 null）；如果文字与图片出现不同数字，展示两个候选供选择。AI 首次发现新指标时，生成选项卡或预览，不未经确认直接安装新插件。

### 6.3 Command Plan 标准（草案）

```json
{
  "protocolVersion": 1,
  "requestId": "uuid",
  "capturedAt": "2026-11-04T12:34:00+09:00",
  "contextRef": "task-session-id",
  "steps": [
    {
      "stepId": "s1",
      "pluginId": "finance",
      "actionId": "finance.addExpense",
      "args": {"amountMinor":2500,"currency":"CNY","category":"food","accountId":null},
      "dependsOn":[],
      "idempotencyKey":"request-uuid:s1",
      "confirmation":"auto_if_unambiguous"
    },
    {
      "stepId":"s2",
      "pluginId":"finance",
      "actionId":"finance.monthlySummary",
      "args":{"period":"2026-11"},
      "dependsOn":["s1"],
      "idempotencyKey":"request-uuid:s2",
      "confirmation":"none"
    }
  ],
  "atomicGroups":[]
}
```

**金额解释：** `2500` 代表人民币 25.00 元。上例仅演示协议，不代表既有数据；任何不完整字段不能用虚构默认值补齐。

### 6.4 操作状态机与事务

`RECEIVED → ANALYZING → PLANNED → WAITING_INPUT? → EXECUTING → COMPLETED / PARTIAL / FAILED / CANCELLED`。

每步均记 `attempt, startedAt, finishedAt, resultRef, errorCode, idempotencyKey`。**默认顺序独立执行**，后续步骤看到前面已提交的结果；用户指定需整体执行的步骤组成原子组，组内数据库写入单事务。不同操作失败时，不得误将未执行步骤显示为成功；AI 延迟时立即给加载反馈，UI 不阻塞，用户可取消尚未执行的步骤。

**确认策略：** 普通明确操作尽可能自动；多个候选时展示选项；批量删除展示范围并确认；需要历史重算时先显示影响摘要；图片冲突、资金冲突通过选择卡处理；用户可设置是否对明确“纠错上一条”跳过额外确认。每次变更可在操作历史中找到并恢复。

**离线：** 支持本地常见记录/查询/修改/删除/计时/导航/简单统计。联网前暂存复杂指令时标记 `PENDING_REMOTE`；恢复网络后先解析，再按各 Action 的确认策略执行，大批量删除不会未经确认自动执行。不可用的云端 AI 不能阻碍传统页面工作。

---

## 7. AI Provider、上下文与工具查询

### 7.1 两种 AI 配置

- **BYO API**：用户可配置 OpenAI 兼容 endpoint、模型与自己的 API Key；设置中展示模型、连接结果、调用次数、预估 token、费用（若定价规则可得）。
- **作者 AI（暂缓）**：可选月订阅，含清晰用量上限；明示加价用于支持作者；等实际成本测试和服务端计费完成后再上线。订阅购买和计费的应用商店规则需在商业化时另行确认。

### 7.2 上下文不能等于数据仓库

AI 提问时先使用当前意图、当前任务上下文、插件 Action 元数据和**本地工具调用**。所有历史记录留在 SQLite。工具返回符合限制的结构化摘要，而不是整库文本。

查询层级：
1. `listMetrics()/listPlugins()`：可用领域、指标、动作，不带私人记录明细。
2. `querySummary(metricIds, period, aggregation)`：周/月/年聚合、覆盖率、质量。
3. `querySeries(metricIds, start, end, bucket)`：时间序列及样本量。
4. `queryRecords(filters, cursor, limit)`：必要时深入详细记录；有分页上限。
5. `runStatistics(spec)`：由本地统计引擎计算，返回版本化结果和可视化数据。
6. `proposeActions()/executeApprovedPlan()`：只通过公开的 Action 合同写入。

所有查询带 `queryId, sourceRevision, range, count, missingPolicy`；变更后仅使相关时间段/指标摘要失效，再增量或按需重建。对跨年/月查询应使用范围索引定位数据，不能靠把所有月份文件逐个塞进上下文。

### 7.3 AI 报告

本地统一计算和渲染日、周、月、季、年报告的结构化数值与图表。云端 AI 将报告组织成可读文字，引用原始统计指标、样本量与缺失范围；断网时仍可生成本地报告。相关性分析不在样本极小或零方差时伪造可靠结论；样本只有2个时用户明确要求可展示定义允许的值，但不自动推荐“强相关洞察”。

---

## 8. Android UI/UX 规范

- **形态**：原生 Compose + Material 3；默认白底，钴蓝主色 `#2A5C9A`，用户可改主题色；领域识别色只作为少量点缀；浅/深色均可。
- **页面**：首页、记录/操作历史、分析、时间轴、我的；首页区块支持隐藏/排序，信息密度可调（默认简洁）。
- **交互**：输入区原地展开；记录结果按对话流排列且可折叠、搜索；在首页生成摘要卡，完整分析在详情页；图片、歧义、新插件均用原生选项卡/预览处理；首次简短引导+随时查看的操作说明书。
- **字形**：主标题24–28sp，模块17–20sp，正文15–16sp，说明13–14sp；中文自然换行，合理行高、字体缩放不遮挡。不能用固定高度强塞多行文字，也不能用下拉菜单裁切关键选项。
- **间距**：4dp 系统，页面水平约20dp，卡内16dp，卡间12dp，区块间28dp；关键可触控区域 ≥48dp。
- **尺寸**：320dp 可用；<360 主要单列；360–599 统计卡片按可用空间调整；600–839 内容宽度限制；≥840 可双栏/左导航。按窗口宽度适配，不按手机型号。
- **输入适配**：支持 edge-to-edge、安全区域、手势条、键盘/IME Insets、横屏、分屏、长文字、150%系统字体。输入框和提交按钮不得被键盘遮挡。
- **可视化**：图表最小可用高度180dp（复杂图表可更高），图例不遮挡，单位、时间范围、样本量、统计口径可见；散点/时序/分布图按合适语义选型。
- **动效**：只用于反馈和过渡，避免装饰性循环动画；低功耗和减少动态效果设置下可关闭。
- **无障碍**：TalkBack 语义、触控焦点次序、颜色对比、文字大小可用；未读通知/异常不靠颜色单独传达。

插件提供的自定义 UI 必须通过统一设计约束校验；不允许 AI 无限制生成挤满文字的布局。

---

## 9. 可靠性、导入、备份与版本升级

1. **数据更正**：修改原始事实产生版本；受影响的统计缓存必须作废并重建，历史恢复同理。
2. **重复记录**：文字与图片疑似同笔消费时提示候选，由用户决定；导入冲突逐条显示卡片。
3. **批量导入**：校验、检测重复；可成功的自动导入，异常项单独报告；不允许异常导致整批静默丢失。
4. **图片附件**：识别提取后默认不永久留原图，但允许用户指定保留；记录来源和必要的提取摘要。
5. **备份**：本地完整备份包 + CSV/JSON 导出；覆盖导入前自动保留应用内恢复点；恢复后校验行数、关键表关联和摘要可重建性。
6. **删除**：回收站30天；恢复记录时同样刷新统计。回收站过期清理不在用户使用界面时执行重型工作。
7. **插件升级**：先做 schema 差异分析与迁移预览；选择迁移或保留旧版本；失败回滚插件状态和相关迁移事务。
8. **时间一致性**：设备时间/时区变化、跨午夜和补录需有测试；定时提醒使用系统调度，不靠永久高频轮询。
9. **可用性**：AI 服务失败、插件故障或正在更新时，其余插件和基础数据操作保持可用。
10. **基本保护**：即使本轮重点是体验，也必须保留必要的本地备份完整性、密钥不硬编码、插件隔离和操作审计；这些是避免数据丢失及版本无法发布的基础约束，而不是本轮优化主线。

---

## 10. 性能预算与压力测试

**指标是目标而非已测得结果。** 固定主力设备和一台中低端 Android 12+ 真机，记录芯片、RAM、系统版本、数据状态、重复次数、缓存冷热、网络情况。使用 Macrobenchmark + FrameTimingMetric + StartupTimingMetric、Room 查询基准与 Perfetto Trace。模拟器主要用于功能适配；真实性能以真机为准。

| 场景 | 候选验收目标 | 采样方式 |
|---|---|---|
| 冷启动至首页可交互 | 中位数 ≤2.5 s | 真机 ≥20 次 |
| 输入框点击、键盘显示反馈 | P95 ≤100 ms 的 UI 反馈 | 真机交互追踪 |
| 本地新增单条记录 | P95 ≤300 ms（不计云端等待） | ≥1000次操作 |
| 100万条下查询一个月记录 | P95 ≤800 ms | 多月、多指标、冷热缓存 |
| 首页摘要缓存读取 | P95 ≤500 ms | 多指标混合 |
| 百万条滚动列表 | 无长时间冻结，卡顿帧比例 <5% | Macrobenchmark + 实机 |
| 复杂统计 | UI 线程无长查询阻塞 | Trace 检查 |
| API 请求与插件构建 | 立即呈现状态，可取消/返回，完成后更新 | 故障网络与长请求 |
| 新插件安装/升级失败 | 不影响原版使用，数据与历史可恢复 | 故障注入 |
| 手机适配 | 320/360/393/432dp；100/130/150%字体全可用 | 截图与 Compose UI Test |

生成至少100万条**有真实分布特征**的测试记录：跨三类型、不同年份、热区时间段、大量同一指标、重叠分配、异常值、空值、版本历史、图像识别来源。不要只填相同的测试行，否则索引与统计性能失真。系统要有 Paging、按需读取、合适复合索引、摘要缓存、分片式 UI 渲染/虚拟化列表，避免 O(N) 首页全表扫描。

---

## 11. 开发里程碑与每阶段交付证据

### P0-A 工程底座（第一阶段）
- Kotlin/Compose/Room/依赖注入与模块骨架；统一事件 + 三类记录 + 修改历史、指标注册、时间服务。
- 两个最小插件 `finance` 与 `time` 有同一套宿主接口；手工按钮可增删改查并产生统计。
- **验收**：数据库迁移测试、两插件契约测试、跨午夜/转账/历史恢复测试全部通过。

### P0-B 统一输入闭环（最高体验优先级）
- 首页输入控件 + 文字输入；云端 BYO API；本地离线基本语法；结构化计划、操作调度、反馈流、歧义选择、操作历史。
- **垂直切片**：`“午餐25元，学习2小时” → AI 两动作 → 财务/时间插件 → 正式入库 → 本地统计 → 首页两项结果 → 可撤销`。
- **验收**：连续20条记录无重复；禁网正常手工和常用命令；复杂指令不导致 UI 卡死；每一步都有精确执行状态。

### P1-A 插件与原生 UI
- AI 按 Schema 生成 Tier 1 插件，原生预览与确认安装；动态指标、组件、统计规则，插件版本迁移。
- **验收**：从一句“增加植物养护指标”生成插件、预览、启用、录入、分析、升级、回滚，数据仍完整。

### P1-B 八大领域与专业统计
- 财务、身体、睡眠、学习、时间、习惯、阅读、自定义；支持本地报告与图表。先扩展质量可靠的专业领域，再提供自由规则。
- **验收**：财务转账不双算；睡眠按入睡日归属；时间重叠去重；指标变化可追溯；缺失值不当0。

### P1-C 百万级性能与成熟体验
- 摘要索引、搜索、增量聚合、复杂统计、插件渲染性能；手机适配、完整备份、回收站。
- **验收**：百万数据性能预算和 320dp/150% 字体兼容测试达标；有截图与 Trace 证据。

### P2 原生插件构建与商业化预备
- 云端原生插件源码生成、隔离编译、测试、渠道适配发布；可选更强 Tier 2 执行器；作者订阅 AI 服务及额度系统（根据实测成本再启用）。
- **验收**：云端构建/失败回退、Play 与直装两渠道安装升级路径分别测试；定价/额度/使用说明先完成审查才开放。

**每阶段必须交付：** 项目提交版本、可安装 APK（Play 发行阶段另交 AAB）、迁移脚本、自动测试与覆盖范围、实际操作截图、真机性能结果、缺陷列表、已知限制与下一阶段计划。不能以 Mock UI/演示数据替代真实链路。

---

## 12. 验收样例（Given / When / Then）

| 编号 | Given | When | Then |
|---|---|---|---|
| AT-001 | 已有体重快照 | 新增另一时点体重 | 变化由算法计算，无多余真实 CHANGE |
| AT-002 | 银行卡余额5000元 | 之后收入1000元且指定该账户 | 当前推算6000元 |
| AT-003 | 09:00收入1000，12:00实测5000 | 查询12:00之后余额 | 不将此前收入重复加为6000 |
| AT-004 | 有银行卡和零钱两个账户 | 转账500元 | 两条 CHANGE 关联，总资产净变化0 |
| AT-005 | 学习3h包含阅读1h | 查询全天占用时间 | 3h，分类可查看学习3h、阅读1h |
| AT-006 | 计时1h、暂停20m、继续1h | 结束 | 有效时长2h，暂停区间保留 |
| AT-007 | 有运行中的计时 | 强杀进程再重开 | 能正确恢复状态与有效时间 |
| AT-008 | 图像消费金额缺失 | 用户选择直接保存 | 保留待补全字段、金额不进入合计 |
| AT-009 | 只有两个有效散点 | 用户主动请求 Pearson | 在数学有定义时可显示并明确不作可靠推断；自动洞察不推荐 |
| AT-010 | 某一变量零方差 | 请求 Pearson | 不输出无意义系数，仅呈现描述/散点 |
| AT-011 | 一条指令包含写入和生成报告 | 执行 | 报告读取前面已提交的正确数据 |
| AT-012 | 暂存离线批量删除命令 | 恢复联网 | 先展示删除范围，待用户确认 |
| AT-013 | 旧备份与当前数据不同 | 选择覆盖恢复 | 先保留恢复点；覆盖和回滚均可验证 |
| AT-014 | 旧插件已有大量历史 | AI 生成新版 schema | 展示迁移影响，用户选择后执行，失败可回滚 |
| AT-015 | 有100万条跨年记录 | AI问某月统计 | 只查询相关时期与指标摘要/细节，不上传整库 |
| AT-016 | 320dp、150%系统字体 | 展开输入、选项卡、键盘 | 文字不截断，能选择、提交、滚动 |
| AT-017 | 无网且云端AI已启用 | 输入本地可识别简单命令 | 仍可完成操作，无法识别则保留待处理 |
| AT-018 | 帐号和数据正常 | 插件出现异常 | 宿主不崩溃，其他插件继续使用 |
| AT-019 | 记录被删除 | 30天内申请恢复 | 原记录及派生统计恢复，历史仍可查询 |
| AT-020 | AI 创建一个新阅读插件 | 用户确认安装预览 | 宿主注册指标、Action、UI，无需手工修改现有模块 |

---

## 13. 待验证/非阻塞架构细节

这些问题**不阻止 P0 开工**，但必须在实施相应阶段前通过设计验证或真实原型决策：
1. Tier 2 解释执行环境的候选技术与 API 白名单、运行时配额。
2. Play 与直装渠道是否采用相同应用 ID/签名及跨渠道升级策略；不能假定两条路径的原生插件交付一致。
3. 作者 AI 的实际服务成本、月订阅定价、包含上限及渠道计费方案（当前关闭）。
4. 云端插件构建的服务端技术栈、可用配额、签名密钥管理、构建排队和故障恢复。
5. 百万级性能预算在目标设备上实测后可以基于证据调整，但不得无测试地宣称达标。
6. 首版是否纳入跨年高阶统计、复杂脚本插件，以及完整原生插件商业分发，需要按里程碑决定。

---

## 14. 给开发 AI 的执行契约

你是 Life Atlas v4.0 的 Android 首席工程师，必须按本文建新工程。**不要继续无限追问场景；用文中明确的选择实现，用标记为“待验证”的内容建立可替换接口与测试原型。**

1. 先输出工程目录、插件合同、数据库 schema、命令 JSON schema、5 个最关键的端到端测试，不直接生成大量空页面。
2. 首先跑通 `输入→结构化计划→多插件 Action→事务写入→版本历史→统计→结果 UI` 的真实纵向切片。
3. 每个新增功能必须补充单元测试、插件契约测试、数据库迁移测试、UI 测试与适配截图。
4. 不得用 Mock 数据冒充已经实现；不得把 AI 自由生成的文本当作精确统计事实；不得在未测试情况下宣称性能合格。
5. 完成一个里程碑，生成可安装 APK 和报告再进入下一阶段。遇到真正影响构建路径、渠道合法性或数据不可逆破坏的阻塞决策，再向用户提出简明选项；其余合理默认应记录在 ADR 中并继续推进。

---

## 15. 外部工程依据（供实施时核查）

- Android 架构和 UI/Domain/Data 分层：https://developer.android.com/topic/architecture/recommendations
- Android 12 API 31：https://developer.android.com/guide/topics/manifest/uses-sdk-element
- Play 动态代码分发政策：https://support.google.com/googleplay/android-developer/answer/18265346
- Play Feature Delivery：https://developer.android.com/guide/playcore/feature-delivery
- 动态代码加载约束：https://developer.android.com/privacy-and-security/risks/dynamic-code-loading
- Adaptive Material 3：https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- Edge-to-edge：https://developer.android.com/develop/ui/compose/system/setup-e2e
- Macrobenchmark：https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview

**文件结束。**
