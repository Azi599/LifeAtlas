# Life Atlas｜全需求实现与持续自演进工程改造方案

> **用途**：直接交给 Android 开发 AI / 工程团队执行的工程实施规范。不是“下一版加几个功能”的计划；以全部既定产品能力实际可用为最终完成标准。  
> **基线**：用户上传的 `base.apk`、`LifeAtlas-v4.0-工程架构规范.md`、`ADR.md`、Life Atlas v3.0，以及累计六轮 144 道 GrillMe 决策和最新真机截图。  
> **审查方法**：解析 APK 清单、DEX 中的自有类/方法及操作标识，并对照截图。**没有取得完整 Kotlin 源码、运行数据库或真机调试会话，以下“已检出”是静态证据，运行表现以截图为证，未声称执行了 APK。**

## 0. 实际检查证据与当前结论

- 安装包：`base.apk`，约 60.6 MiB（63,553,019 字节）。SHA-256：`c44510270d009b76fb877041eb5d70d7de8d6a91a5c6bbc82a2f9ebdca5806c4`。
- Android 清单：`applicationId = com.lifeatlas.app`，`versionName = 4.0.0`，`versionCode = 400`，`minSdk = 31`（Android 12）、`targetSdk = 34`，`debuggable = true`。这不是面向正式发行的 release 验证结果。
- 已检出架构类：`com.lifeatlas.app.AppContainer`、`core.commands.CapturePipeline`、`PlanDispatcher`、`SystemActions`、`core.ai.RemotePlanProducer`、`LocalRulePlanner`、`LookupExecutor`、`AiContextTools`、`core.plugins.PluginRegistry`、`ManifestValidator`、`PluginUpgradeService`、`core.dynamicui.DynamicUiKt`、`core.storage.RoomAtlasStore`、`core.search.QueryService`、`core.statistics.DefaultStatisticsEngine`、`feature.home.HomeViewModel` 等。
- APK 中有明确的动作标识：`finance.addExpense / addIncome / transfer / monthlySummary`、`time.startTimer / stopTimer / pauseTimer / resumeTimer / logAllocation`、`system.queryRecords / editRecord / deleteRecord / restoreRecord`。在检出的应用自有类与动作名中，未发现完整的 `createFolder`、`createProject`、`generatePlugin`、`requestBuild` 等实现链；这不排除存在不同命名的泛型实现，须以源码检查为最终依据。
- 用户真机截图确认：输入“新建一个体重项目”，AI 回复只能处理记账、计时、记录管理；说明终端可用能力尚未达到“需求 → 新功能”的闭环。
- 截图确认的 UI/功能问题：分析页显示收入 0、支出 -25.80、净额 +25.80，存在净额口径/符号值得核实；分析图表存在大面积空白；设置页文字和开关密集；首页错误信息占用大卡片；淡粉色操作文案与白底对比不足；“开始工作计时”标签异常换行；语音入口在此设备上不可用，缺少更顺畅的替代路径。
- **总判断**：已有基础设施值得评估复用，但“动态创造领域、表单、图表、工作流、插件和新版 App”的核心能力缺失。不能靠再加固定的体重/阅读按钮解决。应把“能力创建”升级为平台一等公民。

## 1. 最终产品定义：不要停在任何一个中间版本

Life Atlas = **本地优先的个人数字生活数据平台 + AI 命令工作区 + 动态功能生成平台 + 持续演进应用**。

**最终必须满足**：
1. 首页一个输入框尽可能管理整个软件：记录、查询、校正、删除、撤销、移动、分类、统计、图表、报告、计时、设置、布局、目录、项目、指标、插件、更新。
2. 用户可以随时创造从未预设的新数据领域，不依赖开发者提前编写页面；联网 AI 负责理解并生成配置，离线表单也能创建基本项目和指标。
3. 任意一条语义清楚的命令都应产出**真正的数据/界面/功能变化**，不是只有 AI 文字答复；无法直接执行时，说明缺失能力并启动“生成新能力”的流程。
4. 每条正式统计记录**恰好**属于 SNAPSHOT（基础数据）、CHANGE（真实变化）、ALLOCATION（时间分配）之一；一条现实事件可以关联多条记录，目录、项目、插件和纯文字事件并不强制归入三类。
5. 统计数字由本地确定性计算引擎生成；AI 负责任务规划、数据分类、说明、插件生成和报告叙述。
6. 所有本地数据能按时间线、指标、目录和项目检索；保留发生时间、录入时间、修改时间、时区、来源和版本历史。
7. 设计质量达到 Android 正式产品水平：中文清晰、操作反馈即时、布局可定制、表单/选项文字完整、低信息密度默认、可逐层深入分析。
8. 首阶段在至少 100 万条数据下验证可用；不能把全部数据放进模型上下文。
9. 支持自行配置 AI API；后续支持“支持作者”的月度 AI 订阅（先关闭，价格和额度实测确定）。
10. Android 12+；Google Play 与独立 APK 双渠道，更新方式可不同；不承诺 Play 版任意原生代码热加载。
11. 每个功能都有自动化测试和实际手机验收；测试通过不能只是“编译成功”。
12. **完成定义是以上能力全量跑通，不是某阶段完成、某个版本号发布或写出一份 ADR。**

## 2. 核心架构：把扩展能力放在最中央

```text
Input Workspace（文本 / 语音 / 图片 / 文件 / 手动表单）
    │
    ▼
Intent & Capability Planner（意图识别、引用上下文、发现现有功能或能力缺口）
    ├── 已有动作 → Plan Validator → Action Dispatcher → 领域插件 → 持久化
    ├── 缺少对象 → Universal Object Service（目录/项目/指标/仪表盘等）
    ├── 缺少功能 → Plugin Forge（生成定义→验证→预览→确认→安装）
    └── 需要原生代码 → Build Orchestrator（生成源码→云端 CI→测试→渠道更新）
    │
    ▼
Fact Store（LifeEvent + SNAPSHOT/CHANGE/ALLOCATION + 历史版本 + 组织树）
    │
    ├── Query & Statistical Engine（缓存、统计口径、时间并集、关联与趋势）
    ├── AI Data Tools（摘要索引 → 相关明细 → 精确计算）
    └── Declarative UI Engine（原生 Compose 动态表单/卡片/图表/页面）
    │
    ▼
Operation Stream（执行状态 / 结果卡 / 交互选项 / 撤销 / 失败恢复）
```

**原则**：软件不是“AI 能调用多少固定 Action”，而是**当 Action 不存在时，AI 能否在宿主能力范围内创建并注册新 Action**。宿主必须暴露基础创建工具，不依赖业务插件的人工硬编码。  
**版本策略**：另建清晰的新宿主或重构现有工程均可，但旧实现只能作为经过测试的可复用部件；先保持用户数据可迁移，再替换架构。不能机械保留有缺陷模块，也不必为了“从零”而丢弃经过测试的纯算法。

## 3. 代码级改造清单（按照 APK 已检出模块映射）

| 已有类 / 模块 | 应做的改造 | 不能只做什么 |
|---|---|---|
| `AppContainer` | 组装 `ObjectRegistry`、`CapabilityCatalog`、`PluginForge`、`BuildOrchestrator`、`AiConversationOrchestrator`、`CommandExecutionService`、`UiSchemaRepository`；将既有业务动作注册进新能力目录 | 继续只注册 `finance/time/system` 三种固定插件 |
| `core.contracts` | 定义通用对象、插件 Manifest v2、动态 UI DSL、Command Plan、工具调用结果、Capabilities、Schema Revision | 只添加一个 `createMetric` 按钮 |
| `core.plugins.PluginRegistry` | 从仅注册现成编译类，升级为读取持久化插件清单、Schema、UI、动作的动态注册系统；支持加载、停用、依赖解析、版本回滚、原生扩展桥 | “设置里显示三张插件卡片”就宣称动态插件完成 |
| `ManifestValidator` | 校验输入 Schema、输出 Schema、指标语义、单位、统计方法、UI DSL、支持的组件、宿主合约版本及升级兼容 | 只检查插件名和版本 |
| `PluginUpgradeService` | 比较旧/新 Schema；预览迁移影响；持久化插件版本与迁移计划；失败时插件+记录一起回滚 | 只更换 Manifest 版本字符串 |
| `core.dynamicui.DynamicUiKt` | 变成运行时原生渲染器：从数据库/插件资源读取 UI Schema，渲染表单/图表/导航/步骤/仪表盘，动态绑定 Action、数据源和错误状态 | 只能绘制源码里预先存在的页面 |
| `CapturePipeline` | 加入 `CAPABILITY_MISSING` 分支，交给 `PluginForge` / `ObjectService`；会话上下文支持“刚才那个项目”“再增加字段” | 遇到未知任务就返回“不支持” |
| `PlanDispatcher` | 支持 `CREATE_OBJECT`、`INSTALL_PLUGIN`、`UPDATE_LAYOUT`、`BUILD_REQUEST` 等系统级通用 Action；原子组、非原子链、引用依赖、确认及重试 | 将所有修改都直接交给 DAO 或让 AI 返回说明文字 |
| `core.ai.PlanPrompt` / `RemotePlanProducer` | 动态注入相关能力目录，提供工具发现与插件创建能力，要求输出结构化计划；多轮工具查询与结果反查；明确不以回复文字代替执行 | 在提示词里硬写几十句“怎么识别体重项目” |
| `LocalRulePlanner` | 保留离线增删改查/导航/计时；补充本地创建目录、项目、指标和查询功能；离线复杂任务保留草稿 | 云端 AI 关掉就失去基本使用能力 |
| `RoomAtlasStore` | 增加组织树、用户生成插件、动态字段、UI Schema、插件版本、构建任务等表与迁移；保留专业三类型值表与历史 | 将新功能塞进一个无法查询的大 JSON 字符串 |
| `QueryService` | 扩展项目/目录和插件指标查询、跨时间范围查询、统计口径元数据与精确失效 | 任意新记录都让所有页面重扫百万数据 |
| `DefaultStatisticsEngine` | 增加聚合方法注册：SUM、WEIGHTED_MEAN、LAST_SNAPSHOT、UNION_DURATION、MEDIAN 等，保证现金流符号、未知区间不双算 | 所有指标简单按日求和或平均 |
| `HomeViewModel`、`feature.home` | 统一命令工作区、步骤流、互动选择卡、插件预览、结果折叠、执行进度、撤销/跳转 | 只在首页贴一个“聊天框” |
| `feature.settings` | 插件中心、API 配置、外观定制、构建任务、更新管理、功能说明书；把技术状态收纳到次级页 | 再堆大量密集开关与小字号说明 |
| `feature.analytics/history/timeline` | 按项目/领域/指标/时间切换，链接真实记录，修正图表空白和净额符号，支持动态插件图表 | 各页面继续只显示财务和计时固定指标 |
| 新增 `server/build-orchestrator` | 云端源码生成、构建、测试、状态回传、APK/AAB 制品及渠道更新流程 | 在客户端假装“自动更新”只是下载 JSON |

> 实际源码包路径以项目 Gradle 设置为准；这里用 APK 中确认存在的类定位改造责任。需要取得源码后再逐个核对真实声明、签名和依赖关系。

## 4. 通用对象模型：目录和项目必须独立于三种数据类型

### 4.1 组织层

定义 `AtlasObject(id, objectType, parentId?, title, createdAt, updatedAt, schemaVersion, metadata)`。`objectType` 至少包括：`WORKSPACE / FOLDER / PROJECT / MODULE / METRIC / DASHBOARD / VIEW / PLUGIN / SAVED_QUERY / WORKFLOW`；允许插件注册更多类型的定义，实际可查询字段必须有索引。

- 支持多级目录与项目；可重命名、移动、复制模板、归档、删除及撤销。
- 一个对象可以属于目录，并同时通过关系边关联多个指标与插件；避免为“放进目录”复制记录本身。
- `ObjectRelation` 用 `relationKind` 表示“包含/关联/展示/依赖”等；一个数据事实可以在多个项目中展示，但原始记录只有一份。
- 首次说“新建体重项目”时，展示**创建体重指标 / 建立体重项目 / 生成完整体重模块**三张原生选项卡；用户选择后真实创建，而不是拒绝。

### 4.2 三种统计记录层

`LifeEvent(eventId, rawInput, source, inputTime, timezone, revision)` 为来源容器；关联 `TypedRecord(recordId, eventId, metricId, recordKind, occurredDate, occurredAt?, createdAt, updatedAt, status)`。

- `SNAPSHOT`：余额、体重、评分等某时刻状态，不把两个时点数值相加。
- `CHANGE`：收入/支出/转账腿/阅读页数增加/完成次数等真实变化；两次快照的差异动态计算，不重复制造变化事实。
- `ALLOCATION`：起止时间、暂停区间、声明时长、主类别和附加标签；未知时段不冒充可确定的区间占用。
- 事件容器可只有描述文本，不必强塞一种记录；金额未知为 `NULL` 而非0。
- 时间记录同时保存**发生日期、具体时刻（若有）、录入时刻、最后修改、时区**；无具体时刻的历史输入必须按说出的日期入库，不能以录入日代替。
- 余额以最新有效实测快照为锚点，仅累计其后关联账户的有效流水；转账为关联的双腿变化，净资产不变；实测差额列出但不编造支出。

### 4.3 具体数据库迁移

新增或扩展以下表（名称建议，最终以源码 schema 对照）：

```sql
atlas_objects(id PRIMARY KEY, parent_id, type, title, metadata_json, revision, created_at, updated_at)
object_relations(id PRIMARY KEY, from_id, to_id, relation_type, UNIQUE(from_id,to_id,relation_type))
plugin_definitions(plugin_id, version, tier, manifest_json, status, installed_at, PRIMARY KEY(plugin_id, version))
plugin_schema_fields(plugin_id, version, metric_id, field_id, type, unit, rules_json, ordering)
plugin_ui_schemas(plugin_id, version, screen_id, ui_json, schema_revision)
plugin_action_schemas(plugin_id, version, action_id, input_schema, output_schema, execution_kind)
plugin_migrations(plugin_id, from_version, to_version, migration_json, status)
build_requests(build_id PRIMARY KEY, request_json, status, artifact_ref, failure_reason, timestamps)
object_versions(object_id, revision, snapshot_json, created_at)
```

沿用现有 `events/records/metrics/values/operations/aggregates`，给动态指标增加所有者 `pluginId` 和数据 Schema 版本。**迁移一律显式、可回滚**；已有记录不应因重构或新插件启用而丢失。动态字段可以在扩展表保存；可检索字段做独立索引/物化视图，避免全库 JSON 扫描。

## 5. AI 操作协议：从“工具调用”变成“能力创造”

### 5.1 每条输入的执行逻辑

1. 收集用户自然语言、图片/语音、当前目录/最近项目、时区、需要的历史上下文。
2. 本地或云端 `IntentPlanner` 判断记录、查询、编辑、报告、导航、布局、插件生成、软件更新等意图；读取相关能力 Schema，不要求把所有插件文档都塞进上下文。
3. `CapabilityResolver` 在已安装动作库查找实现；找不到则**进入能力创建**，而不是返回“暂不支持”。
4. 能通过通用对象/动态插件定义表达的：AI 生成定义，宿主进行 Schema/统计语义/界面校验，展示真实预览；用户确认安装后即时可用。
5. 需要可扩展计算的：交给受限解释型执行器，仍绑定同一数据和 Action 合约。
6. 需要新 Android 代码的：生成明确的原生构建任务，启动云端构建/测试/制品更新流程，绝不把“计划创建”显示成“已经安装”。
7. `PlanDispatcher` 执行动作，记录每步真实状态、影响数据与撤销链接；错误时可重试同一幂等键，不重复记账。
8. 首页展示极简结果，必要时附带选择卡/编辑器/报告链接，支持继续说“再加一个字段”。

### 5.2 建议的行动计划协议（示意）

```json
{
  "requestId": "req-001",
  "intent": "create_custom_feature",
  "steps": [
    {
      "stepId": "s1", "actionId": "atlas.createProject",
      "args": {"title": "体重记录", "parentId": null},
      "dependsOn": []
    },
    {
      "stepId": "s2", "actionId": "atlas.createMetric",
      "args": {"projectRef":"s1", "title":"体重", "kind":"SNAPSHOT", "unit":"kg", "valueType":"decimal"},
      "dependsOn": ["s1"]
    },
    {
      "stepId": "s3", "actionId": "atlas.generateView",
      "args": {"projectRef":"s1", "layout":"record-form-and-trend", "metricRef":"s2"},
      "dependsOn": ["s1","s2"]
    }
  ]
}
```

计划执行后必须真实保存项目、指标和页面配置。`stepRef` 由执行器解析，绝不让模型自行编造数据库 ID。依赖动作按拓扑顺序执行；按用户要求组合原子组；删除100条等高影响动作展示匹配范围后确认。所有操作通过接口，AI 不直接拼接和运行 SQL。

### 5.3 通用 Action 清单（必须真正实现）

`atlas.createFolder / createProject / moveObject / renameObject / deleteObject / createMetric / updateMetric / createForm / createView / createDashboard / addWidget / configureVisualization / rearrangeHome / registerWorkflow / requestPlugin / previewPlugin / installPlugin / updatePlugin / rollbackPlugin / listCapabilities / explainCapability / requestBuild / getBuildStatus / checkForUpdate / navigate / report / query / editRecord / deleteRecord / restoreRecord`。

业务插件仍公开专业动作，如 `finance.transfer`、`time.startTimer`。**目录/项目/指标/UI/插件生命周期动作由宿主提供，不属于某个专业插件**。

### 5.4 AI 不理解时的交互

- 把歧义变成完整可阅读的原生选项卡（例如“项目/指标/完整模块”），每卡标注将实际创建什么。
- 缺少参数时可以“补充字段 / 保存不完整 / 稍后处理”；不能无限追问直到符合原有有限数据类型。
- 正常执行不反复弹确认；批量删除、插件替换、数据库覆盖等显示影响范围。
- 支持持续任务上下文、历史操作恢复、撤销、结果搜索和分支式继续操作。

## 6. 动态插件工厂：一切皆插件的最短可信闭环

### L1 声明式（必须优先完整跑通）

- `PluginForge` 接收自然语言 Feature Spec，生成 `Manifest + MetricSchema + ActionSpec + UiSchema + ChartSpec + SeedExamples`。
- 宿主验证：字段类型与单位、三记录语义、统计计算器是否可实现、图表轴是否正确、UI 是否可渲染、动作引用是否有效。
- 原生 Compose 预览真实渲染，不用 AI 生成“假截图”代替。
- 用户确认 → `PluginInstaller.install()` 写入本地版本化资源并通过 `PluginRegistry` 注册 Action 与 screen → 即刻导航到可操作的新功能。
- 退出应用重启仍能加载；数据按自定义领域可查询、可导出、可统计。
- 从“创建体重项目”“我的植物浇水”“跟踪论文阅读”“自定义摄影器材保养”四种未知领域进行测试，不允许只靠固定词表演示。

### L2 可执行逻辑扩展

- 引入可声明、版本化的表达式 DSL：单位换算、条件计算、区间判断、累计窗口、触发规则、图表变换；复杂表达才使用受限解释器。
- 宿主提供能力 API（读数据、写经过校验的领域操作、执行统计），控制资源与超时，保证失败可回退。不要直接把 AI 生成的 Kotlin 文本当运行时代码导入。

### L3 原生代码生成与应用更新

完整的**能实际执行的**流水线：

`User Feature Request → AI Requirements → Repo Branch → Code Generation → Gradle Build → Unit/UI Tests → Static Checks → Emulator Smoke Test → Signed Artifact → Release Channel → App Update UI → Installation / Rollback`。

- 云端构建服务要有真实仓库、构建环境、制品存储、任务状态、测试日志、版本号管理和签名/发布机制。
- Google Play 版本：原生更新走 Google Play 正规应用更新/功能交付渠道；不要从外部服务器下载并执行 DEX/JAR/SO。Google Play 的动态功能模块是发布打包时定义的机制，不代表任意运行时生成原生代码都能即插即用。
- 独立 APK 版本：提供受 Android 系统约束的更新下载、版本核对、安装请求和用户可见的确认流程，不声称无条件静默安装替换。
- L3 没有云端基础设施时必须诚实显示“待配置构建服务”，但 L1/L2 的功能创造不能因此停工。

官方依据：Google Play Device and Network Abuse https://support.google.com/googleplay/android-developer/answer/16559646 ；Play Feature Delivery https://developer.android.com/guide/playcore/feature-delivery 。

## 7. 统计、时间和财务的科学约束

1. **净现金流修复**：统一声明入账+、出账-、转账对全资产净流量0。UI 可以显示“支出 ¥25.80”而不是“-¥25.80”，但计算不能因此再次取反。例：收入0、支出25.80，净现金流应为 -25.80。依据截图，这一场景必须成为回归测试。
2. **快照与变化**：体重按快照变化计算，余额按时间锚点+交易推算；无法归属账户的消费不擅自改变具体账户余额。
3. **时间不双算**：活动子类可嵌套，显示类别累计与当天物理占用两种口径；无起止时刻但有声明时长，只能算“已记录时长”，不能在不确定重叠时武断算进24小时占用并集。
4. **时间维度**：本地日期与精确 UTC 分离；补记“昨天”发生日期必须为昨天；不使用人工 UTC 正午冒充实际事件时间。跨午夜睡眠按入睡日进入睡眠报告，24小时分配按真实时段切分。
5. **聚合代数**：SUM 可加总；均值必须携带 sum+count；余额取最新有效快照+之后变化；中位数/分位数无法用日均值直接推算；时长并集需处理边界上的重叠。所有聚合缓存按修订号/指标/时期失效与重建。
6. **AI 上下文**：按“元数据→年/月/周摘要→必要日数据→选定原始记录”检索；绝不把整库交给模型。相关性受样本量和缺失影响，零方差不可输出 Pearson 系数。
7. **统计图表**：无数据时用紧凑空状态，而不是留占屏幕三分之一的白框；图表按指标类型选用，提供单位、范围和原始记录链接。

## 8. UI/UX 重做：让 AI 工作区成为真正的首页

### 8.1 具体对照截图修复

- **首页**：长篇“我目前只能记账……”应被功能发现与生成流程替代；永久错误提示不占整张卡。输入框展开后保持可见，结果流在下方滚动；语音不可用时清楚给出输入法语音等替代方式。
- **记录**：不要再次放一整套重复输入组件；改为工作区历史与可搜索记录。输入框复用同一个组件与命令状态。
- **分析**：修复净额符号；月度图表自动选择可用数据和合适高度；无数据时不留巨大空白，数字与标签对比清晰。
- **时间轴**：按日期分组、多来源事件标记、展开详情；未指定时刻的记录显示“仅日期”，不要输出假时刻。
- **设置**：目前整屏密集开关+字太小；拆为 `AI与模型 / 外观 / 插件中心 / 数据管理 / 应用更新 / 使用说明` 子页面。普通用户不直接看大量诊断文本，诊断单独放开发者选项。
- **视觉**：保持淡紫/米白、简洁二次元品牌身份，但**正文采用高对比深色**，低饱和粉色仅用于强调。统一正文16sp左右与充分行高；解决“开始工作计时”等按钮多行乱换行，必要时按钮改为单列或短标签；不能因文字长而缩至不可读。
- **引导形象**：使用既有简洁紫发少女立绘作为欢迎/空状态/提示插画，透明背景资源单独加载，不要用拼图或大图压住核心内容。装饰不能妨碍数据与操作。

### 8.2 新的首页信息架构

**固定核心只有两个：顶部可展开统一输入+底部主导航。** 其他区域允许用户与 AI 调整：

1. 输入工作区：文字、语音、图片、文件、操作建议、执行状态。
2. 当前任务流：结果卡、待选择问题、插件生成预览、生成任务状态、可撤销操作。
3. 今日摘要：不超过2～4张关键卡，内容可定制。
4. “我的空间”：可折叠项目/目录/插件快捷入口。
5. 发现与洞察：统计报告、图表、近期变化，默认少量展示。

用户说“把睡眠卡放第一位”“隐藏财务卡”“创建一个我的大学生活目录”“把阅读指标放到项目中”，必须立即改变实际布局/组织结构并可撤销。

### 8.3 响应与界面状态

- UI 立即显示 `已接收` → `正在理解` → `准备执行` → `执行中` → `已完成/需选择/失败`，仅在动作真实提交后显示“已完成”。
- 多动作按依赖顺序渐进展示；正在等待网络时用户可以继续浏览旧数据。
- 任何选项必须整行可点、自然换行；不采用把关键内容截断的 dropdown。
- 不滥用全屏加载和无效确认弹窗；有歧义时展示候选卡。
- 操作历史不是普通聊天记录，应支持时间、对象、动作和状态检索；一个结果可关联实际生成的项目或图表。

## 9. 性能、工程验证与交付标准

### 9.1 性能预算（目标，不是已测成绩）

| 指标 | 建议门槛 | 测试条件 |
|---|---|---|
| 首页常用交互反馈 | P95 ≤100ms | 中端 Android12+ 真机，稳定运行 |
| 常规本地写入 | P95 ≤300ms | 真实 Room 和文件写入 |
| 100万记录下指定月检索 | P95 ≤800ms | 数据按真实分布，冷热缓存分开 |
| 首页缓存指标显示 | P95 ≤500ms | 含动态指标及组合查询 |
| 首页冷启动至可交互 | 中位数≤2.5s | 真机 Macrobenchmark |
| 320dp + 150%字体 | 0遮挡/截断/溢出 | 模拟器+真机截图复核 |
| 插件生成 | 各阶段显示状态和失败原因 | 云端网络状态可变，具体秒级 SLA 实测后定 |
| 大量历史记录滑动 | 无主线程数据库全表扫描 | trace/帧时间报告 |

### 9.2 关键端到端验收（必须真执行）

**F01 创建目录**：说“新建大学生活目录”，生成可见目录；重启仍在。  
**F02 创建项目**：说“新建体重项目”，选择项目/指标/完整模块之一，真实对象被创建。  
**F03 自动生成功能**：说“记录我的游戏时间并生成月度热力图”，AI生成指标、表单、统计、图表；不改宿主源码即可用。  
**F04 全新陌生领域**：随机命名“家里植物的施肥周期”，完成 L1 插件生成与使用，不能只适配已有关键词。  
**F05 首页调整**：说“把睡眠卡移到最前、隐藏财务卡”，布局改变并持久化。  
**F06 复杂多步**：“建阅读项目、添加每天页数、生成本月趋势图、放首页”，顺序依赖正确，少一个动作不能谎报全成功。  
**F07 跨领域**：同时记餐饮、学习、睡眠，三种记录类型和项目归属正确。  
**F08 AI 不可用**：断网状态仍能手工建目录/指标，查看报告和计时；无法理解的复杂命令被保留而非丢失。  
**F09 自更新**：产生一个确实需要 Kotlin 代码的新功能，在配置好云端构建环境后，生成源码、测试、制品，App 可看到构建状态和更新入口；失败有日志。  
**F10 插件持久化**：生成插件后强杀、重启，功能/数据/图表仍存在。  
**F11 插件升级**：改变指标字段单位或计算规则，展示历史影响，用户决定迁移/保留旧版，失败自动恢复。  
**F12 统计校验**：收入0、支出25.80 → 净现金流-25.80。  
**F13 时间校验**：学习3h含阅读1h，总占用3h；仅声明时长不伪造具体时段。  
**F14 财务校验**：银行转零钱500，净资产变化0；快照前收入不被重复累加。  
**F15 备份校验**：100万条记录可完整分页导出，覆盖前保留恢复点，导回后校验记录/关系/历史一致。  
**F16 原生 UI**：320/360/393/432dp 与100/130/150%字体比例下没有截字、遮挡、失效按钮；页面都截图验收。  
**F17 语音**：设备无原生服务时有替代路径，不能出现“按钮看起来可以用，点了没反应”。  
**F18 多轮会话**：“把刚创建的项目改名，再新增一个字段”按正确对象执行，不重复创建。  
**F19 数据修正**：改3天前交易只产生合法影响，操作历史和统计被同步修订。  
**F20 自定义统计**：插件使用加权平均/区间并集/末值等合法聚合，不以简单 SUM 代替全部。

### 9.3 完成定义与持续执行规则

开发不以版本号结束。每次交付必须提交 **“完整需求覆盖矩阵”**：

`需求编号 | 是否可操作 | 实现入口 | 自动化测试 | 真机截图/录屏 | 代码提交 | 未完成原因 | 依赖 | 下一步`。

- 架构和底座与统一输入并行打通，**动态 L1 插件生成必须紧接着成为下一项可演示主链路**，不得拖到功能开发结束后。
- 之后继续完成专业领域、L2 工作流、L3 云端构建与分发；任何阶段不能宣称“功能都完成了”却缺少插件生成、首页控制和更新的真实证明。
- 每轮先按 F01～F20 回归功能，再补充更多复杂场景；发现新 bug 需要有失败测试并提供修复证据。
- 交付本地项目、可安装 APK、AAB（需发行时）、代码变更、数据库迁移、测试日志、真机截图/录屏及未完成矩阵。
- 任何尚不可用的能力必须明确显示为未接通，而不是假按钮/模拟结果。

## 10. 建议的实际工作顺序：按依赖推进，但不断推进到最终状态

1. **接管工程与证据**：取得原项目 `D:/workboddy/2026-11-03-19-20-21/lifeatlas/`（路径以用户实际位置为准），运行完整测试和构建，记录当前 `base.apk` 可复现的行为。建立数据升级/迁移测试、保留原包以供对比。
2. **恢复失去的自由度**：创建 `AtlasObject` + 目录/项目/指标通用 CRUD + `ObjectRepository` + 原生项目导航；首页和可视化路径共用动作，首先让“新建体重项目”可真实完成。
3. **动态能力闭环**：扩充 `PluginRegistry`、`PluginForge`、UI Schema、Action 模板、Schema 版本与迁移，使陌生指标和页面不改源码也能创建。
4. **AI 统一操作中心**：重构 `CapturePipeline/RemotePlanProducer/PlanPrompt`，加工具发现、未实现能力自动创建规划、结果流和会话引用；离线命令作为可靠兜底。
5. **UI 全面重排**：根据截图逐页做手机适配和交互一致性，不再通过长说明卡遮盖不可用能力。
6. **纠正统计与一致性**：净额符号、历史补录、真实变化与快照、时间未知时段、跨午夜和聚合代数；纳入自动化数据集。
7. **L2 可执行扩展**：动态工作流/统计表达式与跨插件协作，具备可回滚的运行机制。
8. **L3 构建及更新**：云端源代码生产、编译、测试、制品发布、双渠道更新流程；提供实际可用的构建任务与产品内状态展示。
9. **百万级与完整交付**：性能优化、完整备份恢复、所有领域完善、真实设备验收、最终需求矩阵清零。第9项不是停止条件——仅当全部需求的实际功能链都合格，才可称完成。

上述步骤可以迭代或并行，但不允许利用分阶段安排长期搁置目标能力。对必须有外部基础设施才能闭环的 L3 构建，创建可执行的部署脚本、环境配置文档和端到端测试；不能用假服务“完成”验收。

## 11. 交给开发 AI 的直接执行契约（可原样复制）

> 你已经拿到 Life Atlas 当前 Android 源码、APK、v3/v4 规范、ADR 和这份《全需求实现与持续自演进工程改造方案》。请将“用户通过首页一个输入框创造、使用、组织并更新软件全部功能”设为唯一核心目标。先逐项验证当前代码真实能力，修复设计瓶颈，不要继续围绕有限的财务/计时硬编码功能打补丁。首个硬验收是“新建体重项目”，下一个硬验收是“任意陌生领域的 L1 插件能生成、预览、启用、记录并生成图表”，然后必须继续实现全部跨领域记录、专业统计、百万级查询、L2 插件与 L3 云端原生构建更新。所有操作走统一 Action/领域规则/数据库链；动态界面必须使用适配手机的原生组件。每一项需求交付真实 APK、自动化测试与手机截图。不要以一个版本号或阶段完成作为结束；持续推进到完整需求覆盖矩阵所有项目验收合格，并真实列出外部环境尚未满足的阻塞项。不得假称已经完成实际编译、安装或云端构建。

## 12. 现实依赖与审查边界

- 当前仅收到 APK，无原始源码、Gradle 构建环境、云端构建账户、发布证书或设备调试连接；因此这是一份**基于 APK 结构与截图的定向实施方案**，而不是已修改 APK 的交付。
- APK 静态发现的类名与操作标识，不足以还原完整 UI 代码、方法内部逻辑、运行状态或测试结果；应在取得源码后核对每个建议修改点。
- 仍需真实完成 Play 发行账户/签名配置、云端 CI 工具链及兼容性测试；这些限制不应阻碍本地 L1 动态功能先完整可用。
- 公开发布之前，必须确认双渠道版本号、包名、签名一致性及更新策略。Android 和 Play 官方规则遵守事实，不能靠文字要求越过系统机制。
