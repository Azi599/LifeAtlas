# Life Atlas — 构建链验证

全新工程，不使用旧 APK 源码，当前只包含用于验证构建链的空白启动界面；没有产品功能。

GitHub Actions 流程在 runner 安装 JDK 17、Android SDK 与 Gradle 8.9，生成 Gradle Wrapper，编译 debug APK、运行单元测试任务并上传产物。当前环境未运行 Actions，也未验证配置是否成功。

当前未内置 Gradle Wrapper 二进制文件；工作流会在构建环境中生成。建议构建成功后把验证过的 wrapper 加入版本控制，并为 Gradle 发行包增加 SHA-256 校验。暂未配置发布签名。应用 ID 与旧版不同，不能作为旧版原位升级或读取旧数据。
