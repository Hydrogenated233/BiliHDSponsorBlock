# Chrome 0.17.1 当前对齐状态

核对日期：2026-11-06。唯一比较基线为发布源 `b3704f380c1614351f5effdc839b8f44e35d1269`，不跟随 HEAD。上游锚点相对该快照；Java 锚点相对 `src/top/local/bilihdskip/`。默认 legacy 与独立 rules 路径存在差异，不能混用两者来宣称默认行为一致。

完整行为对齐仍未完成。历史逐项范围见 `chrome-parity-audit.md`；运行证据分别见 [控制栏](native-controls-verification.md)、[标记与投稿](native-marking-verification.md)、[播放状态](playback-policy-verification.md) 和 [0.3.2 导航／缓存／白名单](navigation-cache-channel-verification.md)。

## 已实现的调用链

| 范围 | 源码与边界 |
|---|---|
| 原生控件与标记 | `PlayerDock/ChromeIcons/HostEditor/DraftStore/SubmissionActivity` 接入原生自动隐藏控件、原始 SVG、开始／结束标记、取消、编辑、预览、清除与独立投稿确认。原生时间轴与缩略图分类由 `NativeTimeline/NativePreview/PreviewSegments` 绘制。草稿只进入显示投影，不进入自动执行；私人身份留在模块进程。 |
| 播放策略 | `PlaybackPolicy` 处理分类模式、音乐覆盖、full 转手动、最短时长、mute 开关、可见性及 500ms 加速门槛。`PlaybackVisits` 按 UUID 维护 seek 入场、半开区间、撤销、重新允许和重叠保护；`PlaybackResume` 消费暂停恢复偏好。上游锚点：`src/content/skipRules/{policy,visits,intents,resume,overlap,playback}.ts`。 |
| 原生成功确认和 owner | `HookEntry` 只由可见 owner 发布上下文，异步结果受 BV/CID／generation 保护。`SeekConfirmation` 接收原生完成回调并保留 750ms 位置邻近回退；实际成功后才记跳过。有限预加载、原生短 seek、暂停和撤销复测已通过，不能扩展成所有宿主组合均已验证。 |
| 提示 | `NoticePolicy/NoticeClock/NoticeStack` 实现发布版模式 0–4、单调显示计时、媒体位置预告、多卡片、暂停／悬停／交互 hold、手动与效果卡片、撤销及重新跳过。显式关闭通知映射首 UUID 本次 visit dismiss；超时不映射关闭意图。上游锚点：`src/notices/*`、`src/components/{SkipNoticeComponent,StackNoticeComponent}.tsx`、`src/content/skipRules/intents.ts`。 |
| 列表与隐藏 | `Logic.parseAll` 保留负票；`SegmentVisibility/SettingsProvider/MainActivity` 区分负票、短段、主动隐藏与显式显示，支持会话覆盖／受隐私设置约束的持久化、UUID 复制和逐 UUID pending。投票 1／0／20 与分类修正有请求路径；405／429 不计成功。真实社区写入未作为验收测试。 |
| 设置 | `SettingsImport` 先验证再合并，保留未导入项及未知兼容字段，处理分类合并和数字／枚举边界；三个身份别名不进入导入／导出。设置重置保留身份、统计、草稿及其他数据，不能等同完整 OtherData 重置。 |
| POI 与扣除后时长 | `LegacyPoiNavigation` 按默认发布路径的事件选择未来精彩时刻，原生入口接入 seek 完成／失败与上下文。POI 撤销不反向 seek，省时按原始跨度而非跳跃距离。`DurationAfterSkips/NativeDuration` 对可见 skip 并集显示时长，普通及全屏使用各自真实资源。上游：`src/content/skipScheduler.ts`、`previewBarManager.ts`。 |
| UP 白名单 | `ChannelPolicy/ChannelConfig` 和 `HookEntry` 从同一当前 playable 的 aid/cid 相关 DisplayParams 读取 UID；名称仅展示。默认路径抑制计划区间效果与提示，保留查询、标记、POI、手动入口和草稿。真实 UI 加入／移除及恢复自动跳过已验证。effect 立即恢复属于 Android 所有权适配。 |
| 持久缓存 | `SegmentCache/VideoLabelCache/CacheBridge` 将片段与标签分别持久化：一小时绝对 TTL、1000／5000 项、各自 512000 JSON UTF-16 单元总预算。命中不续期；清除及禁用受持久代次保护，旧请求不能写回。`LabelBlocks/LabelRequests` 精确匹配 BVID，首标签语义和碰撞隔离独立于分类设置。上游：`src/requests/background/{backgroundCache,segmentRequest,videoLabelRequest}.ts`、`src/requests/apiCache.ts`。 |
| 信息流标签 | `NativeCards/FullVideoLabels` 有严格宿主类型、typed aid、弱绑定与 generation／复用保护；当前只支持模式 0／1。旧 V5 与当前首页 `gk1.p.I1(card.p)` 独立接入；当前首页实际绑定和真实服务器正标签绘制已核对，临时扫描已移除。更多卡片与模式仍有缺口。 |

## 验证层级

0.3.1 的 124 项测试、release、lint 和干净源码构建已通过。0.3.2 当前源码的 225 项测试、release 与 lint 已通过；最后公开前仍检查最终源码导出。普通／全屏控件、标记与预览、精彩时刻、通知关闭重入、片段持久命中／清除和 UP 白名单有有限真机证据。全部通知模式、高倍速组合、重叠效果、更多预加载组合及所有异常路径仍需扩展覆盖。

测试保持 viewed 上报关闭，未发送真实社区投稿或投票。服务器接受写入、远端账号状态和真实身份的结果均未冒充通过。

## 实际剩余差距

| 范围 | 仍缺行为及固定上游锚点 |
|---|---|
| 播放与统计 | `Logic.Segment` 尚无 externalSource 过滤元数据／规则 trace；加速完成统计未与跳过完整统一。浏览器 loop／vendor 事实无通用原生对应，不应凭空设置。对照 `src/content/skipRules/{policy,engine,playback}.ts`、`skipScheduler.ts`。 |
| 提示 | 未实现通知内投票／反馈、提示声音与音量设置；所有状态转换仍不能宣称逐项一致。对照 `src/components/SkipNoticeComponent.tsx` 和通知模型。 |
| 服务与恢复 | 缺 OtherData 全量恢复及完整 localDefaults 重置，缺服务官方 OTA／镜像健康与路由闭环。当前已有两类缓存和请求合并，不能再记为内存缓存缺口。对照 `src/options.ts`、`src/config.ts`、后台请求模块。 |
| 卡片与其他页面 | 封面模式 2 Hide／3 BlurRevealOnHover／4 BlurAlways／5 SolidCover、更多卡片类型未完成。动态／评论赞助识别、用户正则及 OTA、弹幕时间导航仍缺。对照 `src/thumbnail-utils/thumbnailManagement.ts`、`src/render/DynamicAndCommentSponsorBlock.tsx`、`src/utils/sponsorRegex.ts`、`src/config/sponsorRegexOTA.ts`、`src/content/danmakuSkip.ts`。原生宿主需要定位承载点，不能直接标 N/A。 |
| 账号与跨站 | 缺账号统计／VIP／权限／警告完整闭环，缺 YouTube 对应视频读取／预览、分 P/CID 元数据恢复与绑定操作。对照 `src/popup/{UserWork,PortVideoSection}.tsx`、`src/components/YouTubeLogoButton.tsx`、`src/requests/portVideo.ts`。 |

## 下一批有界工作

1. 在当前首页模式 0／1 基础上扩展其余封面模式及更多卡片类型，并增加回收、清除和异常组合覆盖。
2. 定位 HD 动态、评论和弹幕的具体模型及渲染点，保持 ID／上下文关联。
3. 补齐通知反馈／音频和效果完成统计，再覆盖多模式与重叠回归。
4. 补服务 OTA、OtherData 恢复和账号只读能力；远端写操作单独验证。
5. 实现 YouTube 对应／分 P 恢复的原生承载界面，保留未完成范围，持续核对原始 126 项清单。
