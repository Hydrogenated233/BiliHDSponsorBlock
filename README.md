# HD 空降助手

为哔哩哔哩 HD **2.2.0（versionCode 2020100）**适配的 Android legacy Xposed 模块。模块包名为 `top.local.bilihdskip`，仅作用于 `tv.danmaku.bilibilihd`；其他宿主版本不在支持范围内。

使用环境：NeoZygisk + Vector，启用模块的 legacy Xposed 支持，将作用域设为 `tv.danmaku.bilibilihd`，然后彻底退出并重启 HD。

当前源码包含 MD3 设置界面、社区片段获取与跳过、原生时间轴片段标记，以及原生倍速按钮旁的控制按钮和原生拖动预览中的分类提示。播放器使用 Chrome 扩展 0.17.1 原始 SVG 图标，跟随原生控制栏自动隐藏。普通/全屏入口、点击面板、自动隐藏、拖动类别与区间外清理已在 NP03J 上实测，详见 [验证记录](docs/native-controls-verification.md)。

在 HD 内点开始标记，再点结束即保存区间；投稿按钮打开本视频 / 分 P 的草稿菜单。可以编辑时间、分类和动作，预览片段、预览结束位置、检查开始位置、删除和确认投稿。取消按钮仅取消当前未完成的标记，清除按钮经确认删除当前视频的全部标记。草稿同时显示在原生时间轴和拖动预览中。详见 [标记与投稿验证](docs/native-marking-verification.md)。

投稿前须在模块设置中保存私人 ID；它不会传给 HD 进程或包含在公开源码中。首次使用不附带身份。投稿确认会展示视频、分 P、服务器和全部片段；取消或失败保留草稿，成功后仅清除与确认快照仍一致的草稿。

0.3.1 补充了统一播放策略、逐片段入段与撤销状态、原生 seek 完成确认、可见播放器归属、提示模式与暂停计时、列表隐藏和投票状态，以及保留未导入字段的设置合并。124 项单元测试、release 构建和 lint 已通过；原生入段、预告取消、手动跳过／撤销／重新跳过的实际验证范围见 [播放验证](docs/playback-policy-verification.md)。

0.3.2 接入精彩时刻跳转、扣除片段后的时长、提示关闭的本次入场意图、独立持久片段／标签缓存、按 UID 匹配的 UP 主白名单和当前首页封面全片标签。普通／全屏计时、精彩时刻跳转与撤销、缓存重启命中、白名单加入与移除、真实标签显示／关闭及滚动已经在 NP03J 验证；详见 [验证记录](docs/navigation-cache-channel-verification.md)。封面模式目前支持 0／1。

Chrome 扩展 0.17.1 是行为对齐目标。封面隐藏／模糊、更多卡片类型、动态／评论、正则 OTA、弹幕导航和账号附加能力等仍有差距；当前版本不宣称完整复刻该扩展，详见 [当前差距](docs/chrome-parity-current-status.md)。

构建需要 JDK 17+、Android SDK 35、Python 3，项目使用 Gradle Wrapper 8.10.2 / Android Gradle Plugin 8.7.3。运行 `python prepare_deps.py`，从官方 Maven 下载并校验 legacy Xposed API 82；然后执行 `./gradlew testReleaseUnitTest assembleRelease lintRelease`（Windows 使用 `.\gradlew.bat`）。该依赖为 `compileOnly`，不打包进 APK。

存在维护者本地签名时 release 使用该签名；公开源码在没有签名文件时使用构建者自己的 debug key。自行构建的 APK 无法覆盖使用另一签名的已安装版本。公开源码不包含签名材料。文件范围见 [发布检查清单](docs/publication-checklist.md)。

社区服务会接收相关视频标识；投票和投稿会使用设置中的私人 ID。请勿将真实设置、运行日志或带个人信息的截图加入公开仓库。

许可证：GNU GPL v3，完整文本见 [LICENSE](LICENSE)，来源和第三方依赖说明见 [NOTICE](NOTICE)。
