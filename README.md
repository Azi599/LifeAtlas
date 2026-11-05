# Life Atlas

本地优先的 Android 个人生活数据平台。目录、项目、指标和页面是组织对象；数据事实严格分为基础数据、真实变化、时间分配。自然语言规划通过统一 Action Registry 执行，声明式功能包可以在应用内创建和持久化。

Android 12+；Kotlin、Compose、SQLite/WAL、Coroutines/Flow。单个应用模块承载首个完整纵向链路，专业规则复用通用数据底座。

## 构建与测试

```sh
./gradlew :app:testStandaloneDebugUnitTest :app:assembleStandaloneDebug :app:assembleStandaloneDebugAndroidTest :app:bundlePlayRelease
./gradlew :app:connectedStandaloneDebugAndroidTest
python3 -m unittest discover -s server/build_orchestrator -v
```

GitHub Actions 配置 JDK 17、Android SDK 和已入库 Gradle Wrapper。`android.yml` 构建独立 APK 和 Play AAB，执行确定性统计测试与 Android 15 仪器测试，采集屏幕适配截图。产物位于该次 Actions 运行的 Artifacts。

## 应用入口

首页输入框支持联网 AI、结构化操作计划和离线显式命令；空间、记录、分析和我的提供可视化操作。通用功能包包含字段、表单、查询、统计、图表、动作和有界表达式工作流。创建与升级显示预览，确认后通过事务保存；修改和主题配置支持撤销与恢复。

我的 → 外观提供淡紫色默认主题、颜色调色盘、HEX、浅深色独立配置、2–5 个渐变色标、多主题保存与实时预览。内置和动态组件使用统一 Compose 设计系统。图标源图保存在 assets/launcher-source.jpg，打包脚本保留原图内容。

## AI 与原生扩展

我的 → AI 与模型配置 HTTPS Chat Completions 地址、模型和密钥。密钥由 Android Keystore 加密保存。AI 读取有限上下文和按需查询，统计数字由本地引擎产生。

原生扩展提供 GitHub Actions 和自托管构建服务两条路径。GitHub 模式需配置有仓库 Actions/Contents 权限的令牌，并在仓库配置 `LIFEATLAS_AI_KEY`。`forge.yml` 生成受范围约束的源码，`native-build.yml` 运行编译与测试后进入 `native-release` 签名环境。签名密钥、商店账号与发布批准由分发方管理。自托管服务说明见 server/build_orchestrator/README.md。状态来自实际任务，失败保留真实错误。

独立渠道更新验证包名、版本、哈希与签名，备份后交由 Android 确认安装；Play 渠道不申请安装 APK 权限。

## 数据与验证

JSONL 备份包含组织结构、记录、插件、操作日志、主题和版本历史。导入前建立恢复点；CSV 支持逐行校验、异常报告与重复导入识别。

已通过的历史构建与测量见 docs/build-verification.md。新增改动只有在对应 CI 报告通过后计入验证；模拟器成绩和界面截图不替代真实手机验收。完整需求见 docs/requirements.md，视觉约束见 docs/design-system.md。
