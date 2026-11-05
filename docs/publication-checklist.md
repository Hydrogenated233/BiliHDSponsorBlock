# 发布核对记录

2026-11-06，0.3.3-hd2.2.0。目标宿主仅 HD 2.2.0（2020100）。

## 内容边界

`.gitignore` 默认排除所有文件，逐个放行源码、测试、构建入口、许可证、已审核文档及七个 Chrome 播放器 SVG。准确的公开集合以 Git 跟踪清单为准。研究目录、宿主 APK、反编译产物、设备数据、通知截图、缓存、签名材料和本机 SDK 配置不在源码集合中。

发行 APK 单独上传为 release 附件；对应源码由同一标签提供。发布前检查跟踪清单及归档内容，不直接压缩整个工作目录。私人 ID 只能在安装后的设置中配置，不附带用户身份。

## 来源和构建

- 固定上游 `hanydd/BilibiliSponsorBlock` 的 `b3704f380c1614351f5effdc839b8f44e35d1269`，扩展版本 0.17.1。GPL v3 原文和来源见 LICENSE / NOTICE。
- `assets/chrome-icons/` 的七个 SVG 与该快照 `public/icons/` 同名文件逐字节相同，Android 使用原始 viewBox、path 和 even-odd 填充规则绘制。
- 官方 Xposed API 82 经 `prepare_deps.py` 下载及 SHA-256 校验；compileOnly，不分发其 JAR。
- Gradle Wrapper JAR 已核对 SHA-256：`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`。
- Material Components / AndroidX 的 Apache 2.0 声明与许可证放在 assets，依赖自带声明合并保留。GPL v3 文本同时包含在 APK。
- 本地 release 签名保留在忽略目录；公开源码没有该文件时使用构建者自己的 debug key。不同签名无法覆盖安装。
- 当前源码的 231 项单元测试、release 构建和 lint 已通过。95 个已审核公开文件从 Git 索引导出到全新目录，排除本机 SDK 配置和维护者签名；重新校验 Xposed API 后，231 项测试、release 与 lint 再次通过，52 项任务全部执行。设备已安装 APK 与本机待上传发行 APK 的 SHA-256 相同，附件上传后另查服务端摘要。

## 真机验收与限制

原生控制栏、自动隐藏、全屏切换、社区时间轴与拖动预览见 `native-controls-verification.md`；标记、编辑、预览、取消、清除与缺少身份时的投稿提示见 `native-marking-verification.md`。0.3.1 的播放器归属、预告及撤销复测范围见 `playback-policy-verification.md`；0.3.2 的范围见 `navigation-cache-channel-verification.md`；本批六种封面模式的实际样本、鼠标输入与恢复检查见 `cover-mode-verification.md`。

测试期间禁用 viewed 上报，未发送社区投稿或投票。真实投稿成功与全部错误路径没有冒充真机验收通过。Chrome 完整行为对齐仍在进行，当前静态缺口列于 `chrome-parity-current-status.md`。
