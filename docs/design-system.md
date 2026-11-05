# 视觉系统实施与验收

用户确认：淡紫色主调，柔和薰衣草紫、浅粉紫和奶白色。原始美少女图作为正式图标，保留人物、构图、紫色几何形状和星形。禁止在功能旁放功能介绍；必要标签、状态、错误和数据保留，使用说明集中在帮助页。

已读取 LearnPrompt/vibecoding-humanizer 的 README、SKILL.md、references/mode-ui.md、aesthetic-reference.md、anthropic-prompts-en-zh.md 和 prompt-library.md。按 UI 模式实施；原生中文字体、品牌紫色及必要渐变按用户选定方向保留。

首屏主任务是输入与执行；项目采用列表行和分隔，减少重复大白卡。数字、操作、分组标题使用统一 Typography；表单和按钮保证至少48dp触控高度。Shape、Spacing、Motion、ChartTheme 与动态组件由 DesignSystem.kt 统一供应。ThemeRepository 持久化多主题与外观，Action Registry 暴露查询、保存、应用、重置与删除接口，支持 AI 操作与撤销。

主题编辑器支持任意六位 HEX、调色盘、浅深色、纯色/渐变、2–5色标、位置、角度、实时预览、另存多主题与恢复默认。可读色基于相对亮度对比自动调整；强调色和按钮文字分别计算。图表标签跟随字体缩放和当前背景。

图标打包脚本只去除与边界连接的黑色外部底片，人物与背景内部像素不重绘。提供512px基础图、各密度Launcher、Adaptive前背景及Android13单色层。IconGallery用Android Canvas渲染不同遮罩，验收截图来自运行中的应用。

验收材料：默认首页、记录、时间轴、分析、插件生成预览、主题编辑器、调色盘、自定义渐变、深色、图标遮罩；320/360/393/432dp和字体100%/150%，横屏与键盘。每张截图需检查后记录具体问题。编译通过只代表构建成功，不代表视觉验收通过。
