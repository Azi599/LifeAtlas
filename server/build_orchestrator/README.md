# 原生能力构建服务

运行 `python -m unittest -v test_service.py` 可验证路径范围、持久化恢复和错误状态。真实构建需配置 AI 与 GitHub 凭据。

部署：把本目录放到支持 Docker Compose 的服务器，复制 `.env.example` 为 `.env`，填写域名、随机服务令牌、AI API 密钥与 GitHub token。域名解析到服务器后执行 `docker compose up -d --build`。GitHub token 仅需目标仓库的 Contents 和 Actions 写权限。将 HTTPS 域名与服务令牌填入 App 的“云端构建服务”。

`POST /builds` 接收 `{title,requirements}`。服务生成源码与测试，校验路径，创建分支与提交，并调用 native-build.yml 的 workflow_dispatch。`GET /builds/{id}` 从 GitHub 获取该提交的真实运行状态与产物页面。服务重启时保留任务，未完成生成明确标记 INTERRUPTED，避免重复提交。

更新签名：GitHub Environment `native-release` 中配置 `ATLAS_KEYSTORE_BASE64`、`ATLAS_KEYSTORE_PASSWORD`、`ATLAS_KEY_ALIAS`、`ATLAS_KEY_PASSWORD`；设置环境审批人。编译和测试作业只持有只读仓库 token；签名作业下载编译产物，使用 SDK 签名，不执行生成源码或 Gradle。发行版本号由 run_number 生成，APK 下载后通过 Android 系统安装流程更新。保留前一个安装包和完整数据备份；Android 原位降级由系统限制，回退源码后需要构建更高 versionCode 的修复版。

Google Play 分发需要 Play Console 和签名/上传配置；仓库提供的直装 APK 流程使用独立包名，应用原生更新走完整 APK。Play 发行应通过 Play Console 更新，不使用此下载入口分发原生代码。
