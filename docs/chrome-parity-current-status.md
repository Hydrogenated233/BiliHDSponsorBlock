# Chrome 0.17.1 非投稿行为：当前源码差距

核对日期：2026-11-05。目标是发布版 `b3704f380c1614351f5effdc839b8f44e35d1269`，不是随后 HEAD。上游路径以下统一以 `upstream-research/chrome-release-source/BilibiliSponsorBlock-b3704f380c1614351f5effdc839b8f44e35d1269/` 为根；Java 路径以 `src/top/local/bilihdskip/` 为根。函数名是定位锚点，行号随并行开发可能变化。

依据 `docs/chrome-parity-audit.md` 的发布版范围，重新检查当前生产 Java 与本地固定上游。**缺码**表示已有明确源码差异；**待运行验证**表示有实现但本次未确认宿主行为。本文不评审正在并行实现的原生标记、编辑、预览、投稿关键路径；不把历史基线表当当前状态。仅静态核对，没有网络、ADB、构建或身份数据操作。

## 已有基础，不能再列为整体缺失

- `Preferences.CATEGORIES/defaults/actions` 已有 12 类、四种模式及动作约束；`Logic.Segment/parseAll` 已有 UUID、action、votes、locked，并按精确 BV/CID 选分 P。不能再写“只有八类/没有动作类型”。
- `HookEntry.tick` 已有自动连续区间合并、基础 seek/静音/加速、撤销、身份切换复位及播放器值所有权恢复；加速倍率计算包含上游的等基准倍率分支。
- `MainActivity.player/segmentActions/vote` 已有 MD3 列表、重新查询、赞踩及分类票；`ApiClient.vote` 已有通用整数 type 的传输能力。`PlayerDock.update` 已显示全片分类文本；不是完全没有 full 支持。
- `HookEntry.load/finish` 已有单服务查询、404 空结果、一小时缓存与退避；`SettingsProvider.skipEvent` 已有本地统计和可选 viewed 上报。设置导出有意排除私人 ID。
- 原生时间轴、播放器控制及预览已有代码；这里不重复审查其实现，也不把“有代码”提升为真机通过。

## 按影响排序的剩余行为

### 1. Rules / engine

| 优先级 / 状态 | 当前具体差异 | Java 与固定上游证据 |
|---|---|---|
| 高 / 缺码 | `minDuration` 没有进入普通播放决策；时间轴会隐藏短段，自动跳过仍可执行。`autoSkipOnMusicVideos`、`manualSkipOnFullVideo` 只保存默认值，没有消费方。 | `Preferences.defaults`；`Logic.target`；`HookEntry.updateConfig/tick`。上游 `src/content/skipRules/policy.ts: prepareSegmentPolicies/resolveSegmentPolicy/belowMinimumDuration`：音乐覆盖 → 同类 full 转手动 → 短段忽略 → 禁用静音 → hidden/external 忽略。 |
| 高 / 缺码 | seek 仅用位移阈值识别（后退 >500ms / 前进 >8000ms）；拖动少于 8 秒可被当正常播放。`skipOnSeekToSegment=false` 的保护只从 skip 合并目标产生，自动 mute 不具备同等逐段入场规则。 | `HookEntry.tick` 的 `advancing/sought/blockedStart/blockedUntil`；上游 `src/content/skipRules/visits.ts: updateVisits`、`src/content/skipSeek.ts: seekForSkip/isSkipSeek`、`src/content/skipRules/runtime.ts` 的 seeking/seeked 事件。 |
| 高 / 缺码 | 撤销只记录一个返回点和全局阻止区间；不能表达每 UUID、本次 visit、同动作重叠保护或某段显式重新允许。暂停与 buffering 没有独立状态，缺少暂停后继续/改手动偏好和用户改速/解除静音后的本次 visit 意图。 | `HookEntry.Session/tick/undo/restoreSpeed/restoreVolume`；上游 `src/content/skipRules/{visits,intents,overlap,resume,playback}.ts` 的 `updateVisits/applyUserIntent/resolveOverlap/applyResumePreferences/transitionPlayback`。已有“仅恢复自己设置的值”，不等于完整意图处理。 |
| 高 / 缺码 | POI 虽可解析、列表可普通 seek，但不按模式提供独立的未来高能导航；手动 mute 没有“静音/解除静音 + 跳到结束”双操作。 | `Logic.target` 仅 skip；`HookEntry.tick/showDock` 与 `PlayerDock.update` 的 active/manual 仅 skip。上游 `src/content/skipRules/engine.ts: evaluateRules` 的独立 highlights/poi 路径；`src/notices/SkipNoticeModel.ts: initialPlayback`。 |
| 中 / 缺码 | 加速没有上游单段至少 0.5 秒的门槛；设置 UI 只允许 1–8，而运行代码/上游使用 1.1–16 计算。 | `HookEntry.tick`；`MainActivity.settings`；上游 `src/content/skipRules/playback.ts: playbackMethod/speedUpTarget`。 |
| 中 / 缺码 | seek 调用后直接记成功和统计，没有目标到达确认；合并跳过只携带 active UUID；没有加速完成的同等统计路径。 | `HookEntry.tick` 的 `seekTo` 后 `skipEvent`；`SettingsProvider.skipEvent`。上游 `src/content/skipSeek.ts`、`src/content/skipRules/runtime.ts`、`src/content/skipScheduler.ts: recordSkippedSegments`、`src/content/speedUpManager.ts`。这是识别/完成/去重边界差异；不能据此宣称上游全部统计都延后到 seeked。 |
| 中 / 缺码 | 白名单数组没有 UP ID 获取、管理 UI 或播放抑制；`showTimeWithSkips` 有开关但未投影扣除后的时长。 | `Preferences.defaults`、`MainActivity.settings`、`HookEntry.tick`、`PlayerDock.update`；上游 `src/components/options/WhitelistManagerComponent.tsx`、`src/components/options/WhitelistListComponent.tsx` 及历史审计 F/K 的稳定 UP ID 合约。 |

### 2. Notices

- **高 / 缺码：** `PlayerDock.notice/upcoming/showNotice` 复用单个临时 TextView。没有 UUID 集合去重/选择性关闭、并存堆栈、逐段操作/投票状态。参照上游 `src/render/SkipNoticeStack.ts`、`src/notices/StackLayout.ts`、`src/utils/noticeUtils.ts: noticeSegmentsContain/noticeSegmentsIntersect`、`src/components/SkipNoticeComponent.tsx`。
- **高 / 缺码：** 预告仅固定显示 750ms，无允许/取消本次跳过按钮，无随媒体时间/倍率更新的倒计时；`HookEntry.showDock` 的预告条件也未检查总启用状态。上游 `src/notices/UpcomingSkipDecision.ts: set/consume` 按视频/分 P 与 UUID 保存一次性选择；`src/notices/NoticeClock.ts: secondsUntilSegment` 不跨暂停、缓冲或 seek 外推。
- **中 / 缺码：** `noticeVisibilityMode` 无消费方；提示按 wall clock 到期，没有暂停/交互停钟机制、声音或自定义音量。上游 `src/notices/SkipNoticeModel.ts: noticePresentation`、`src/notices/NoticeClock.ts: read/setPaused`、`src/components/NoticeComponent.tsx: startCountdown/pauseCountdown`。发布版仅模式 **0–4**；模式 5–8 与独立淡化参数属于未发布 HEAD，不能算 0.17.1 缺口。

### 3. Lists / voting

- **高 / 缺码：** `Logic.parseAll` 直接丢弃负票且未锁定记录，模型不保留 hidden reason；列表无法展示/切换“被差评、过短、手动隐藏”。缺少本地 UUID 隐藏及其播放投影和隐私条件下的持久化。上游 `src/popup/VideoInfo/PopupSegment.tsx: extraInfo/showHideButton`、`src/content/messageHandler.ts: hideSegment`、`src/content/segmentVisibility.ts: refreshMinimumDuration`、`src/utils.ts: addHiddenSegment`。
- **中 / 缺码：** `MainActivity.segmentActions` 对 full/mute/poi 一律给“跳到开始/结束”，缺少 full 专用标签、POI 单时间显示、按动作限制操作、UUID 复制、当前/已过状态、锁定提示及分类颜色。上游 `PopupSegment.tsx: segmentFromToTime/showSkipButton/copyUUID/render`。
- **中 / 缺码：** 赞踩/分类票已能发送，但无逐行 pending、撤票 type=20 UI、成功后的本地隐藏/状态联动或明确刷新。`ApiClient.vote` 已能传 20，缺的是调用/状态层。参照上游 `src/components/SkipNoticeComponent.tsx` 的投票反馈与 `case 20`、`src/requests/background/voteRequest.ts: submitVote`。Android 当前把 429 显示为失败；不要复制上游列表将其提示成功的瑕疵。
- **低 / 缺码：** 下载片段列表的专用导出/诊断说明入口不完整；现有草稿导出属于另一数据集合。参照上游 `src/popup/VideoInfo/VideoInfo.tsx`、`src/popup/PopupFooter.tsx`。导入涉及投稿数据的部分留给投稿专项。

### 4. Settings / backup / service

- **高 / 缺码：** `MainActivity.onActivityResult` 的设置导入直接执行 `config=Preferences.parse(parsed.toString())`，以默认值为底；只导入一个键会重置未提供的现有偏好，部分 `modes` 对象还会替换整张模式表。上游 `src/options.ts: setTextOption` 对导入对象逐键写入当前配置；`downloadConfig` 分 Config/OtherData。当前有文件格式/大小检查，但无导入差异预览、语义校验或独立本地数据备份。
- **中 / 缺码：** 仅有“恢复分类默认值”，没有完整 reset 合约（保留身份、minutesSaved/skipCount/sponsorTimesContributed，重置已知 local 默认项）。参照上游 `src/config.ts: resetToDefault`。普通脱敏配置备份可继续存在；要另设计完整恢复边界，不能误称当前备份已包含账号/本地历史。
- **中 / 缺码：** 白名单、最短时长、音乐/full 策略、通知样式、隐私策略、缓存管理、非投稿快捷操作、颜色/音效等可配置入口不齐。`Preferences.defaults` 有键不代表 UI/执行已接通。上游 `src/options.ts`、`src/options/rules/{SettingsPanel,NativeOptions,settingsLayout}.tsx/ts` 和 `src/components/options/*` 提供入口依据。
- **中 / 缺码：** 缓存的一小时/1000条上限已接近片段缓存配置，但 Android 的 512000 限制是**每响应字符数**，不是上游 500KiB 的**缓存总量**；没有持久化、标签缓存、总量清理/统计。参照 `HookEntry.load/finish` 与上游 `src/requests/apiCache.ts: PersistentTTLCache`、`src/requests/background/backgroundCache.ts: rawSegmentsCache/rawVideoLabelCache/clearAllCacheBackground`（片段1000条、标签5000条，各一小时/500KiB）。
- **中 / 缺码：** 单服务器与普通指数退避没有官方服务列表/OTA、镜像健康切换、请求合并及路由状态 UI。上游 `src/requests/serverRouter.ts: ServerRouter.request/routeRequest`、`src/config/{serverPreferences,serverConfig,officialServers}.ts`。读重试与写入不重放边界需逐接口保留，不能把所有 POST 接入通用镜像重试。

### 5. Extra surfaces

- **中 / 缺码，宿主映射待研究：** 首页/推荐缩略图 full 标签与过滤，动态/评论广告识别、用户正则及 OTA，弹幕时间导航/空降。生产 Java 没有这些功能入口；原生 seek 预览缩略图不等于信息流视频卡片标签。上游 `src/thumbnail-utils/thumbnailManagement.ts: setupThumbnailListener/checkPageForNewThumbnails`、`src/render/DynamicAndCommentSponsorBlock.tsx: DynamicListener/CommentListener`、`src/utils/sponsorRegex.ts`、`src/config/sponsorRegexOTA.ts`、`src/content/danmakuSkip.ts: danmakuForSkip`、`src/utils/danmakusUtils.ts: parseTargetTimeFromDanmaku`。DOM 实现需改适配层，行为不能据此直接判浏览器专属 N/A。
- **中 / 缺码：** 账号远端统计、VIP/权限/警告展示没有 UI/请求闭环；`ApiClient.publicId` 只是哈希工具。上游 `src/popup/UserWork.tsx`。当前本地跳过统计不能替代远端账号信息。
- **后续 / 缺码，范围需单列：** YouTube 对应视频读取、预览和分 P/CID 元数据恢复没有本地实现；上游 `src/popup/PortVideoSection.tsx`、`src/components/YouTubeLogoButton.tsx`、`src/requests/portVideo.ts: getPortVideoByHash/updatePortedSegments`。绑定提交/绑定投票属于写操作专项，不纳入这五个补丁。

## 建议接下来的五个有界补丁

这五项按影响/可分割性排序，不代表完成后即完整对齐；每项完成后仍需验证宿主执行。

| 顺序 | 有界交付与验收 | Java 落点 | 上游函数/文件 |
|---|---|---|---|
| 1 | **有效策略统一。** 从原始列表计算分类策略、音乐/full 覆盖、最短时长与 mute 开关；列表/时间轴/执行共用结果，保留原始记录。验收：导入 minDuration 后短段不自动处理；音乐覆盖与同类 full 冲突时按上游顺序转手动；499ms 段启用加速仍按 seek 方法处理。 | `Preferences`、`Logic.target`、`HookEntry.updateConfig/tick`，向现有显示投影传结果。 | `policy.ts: prepareSegmentPolicies/resolveSegmentPolicy`；`playback.ts: playbackMethod`。 |
| 2 | **seek 入场与撤销按 UUID 建模。** 增加视频/分 P 内 visit 状态，区分自身 seek 与用户 seek；替换全局 blocked interval，落实同动作重叠保护与显式重启。验收：短拖入段也遵守 skipOnSeek；段内 seek 保留本次选择；撤销 skip 不压制独立 mute；离段后再入恢复策略。暂停/恢复偏好可作为该状态模型后的独立补丁。 | `HookEntry.Session/tick/undo/consumeCommand` 与 `Logic.target` 的可执行集合。 | `visits.ts: updateVisits`；`overlap.ts: resolveOverlap`；`skipSeek.ts: seekForSkip/isSkipSeek`。需要确认 HD 可用的 seek 事件或等效确认信号。 |
| 3 | **列表隐藏与投票闭环。** 保留 hidden reason，UUID 复制/本地隐藏/取消隐藏；逐 UUID pending 与撤票20；成功后刷新/投影，持久化遵守 trackDownvotes/private 设置。验收：隐藏后立即不再自动处理，换分 P 不串段，405/429 不伪成功；full 不出现区间 skip 操作。 | `Logic.Segment/parseAll/json`、`MainActivity.player/segmentActions/vote`、`SettingsProvider` 的独立数据/命令接口；复用 `ApiClient.vote`。 | `PopupSegment.tsx: extraInfo/showHideButton/copyUUID`；`messageHandler.ts: hideSegment`；`utils.ts: addHiddenSegment`；`SkipNoticeComponent.tsx` 投票反馈。 |
| 4 | **预告与显示时钟。** 先实现单段预告允许/取消，不提前 seek；按媒体时间/倍率显示剩余时间、暂停/缓冲停钟；消费模式0–4并遵守总启用/隐藏设置。验收：取消仅影响当前 BV/CID/UUID 一次，到边界后可恢复允许；暂停不消失或倒计时漂移。多卡堆栈/投票/声音另排。 | `PlayerDock.upcoming/showNotice/notice`、`HookEntry.showDock/tick` 的通知状态与一次性选择。 | `UpcomingSkipDecision.ts: set/consume`；`NoticeClock.ts: read/setPaused/secondsUntilSegment`；`SkipNoticeModel.ts: noticePresentation`。 |
| 5 | **设置导入事务。** 在当前配置上逐键合并，明确模式表合并策略，校验类型/范围，展示差异后一次提交；继续支持脱敏导出，明确其数据范围。验收：只导入 skipNoticeDuration 不改变既有分类/服务器；无效输入不产生部分写入；身份不被普通设置文件覆盖。完整 OtherData 与 reset 随后独立实现。 | `Preferences.parse` 的加载默认值逻辑与新增导入合并逻辑分开；`MainActivity.settings/onActivityResult`；`SettingsProvider` 配置写入。 | `options.ts: setTextOption/downloadConfig`；`config.ts: resetToDefault` 仅作后续恢复边界依据。 |

## 运行时验证边界

已有实现但本次未验证：HD 实际 seek 到达/回调可用性、静音/倍率恢复与用户操作竞争、播放器切换/分 P/重建生命周期、原生时间轴和控制区的可见性/触摸/横竖屏、MD3 列表与文件选择器、真实服务器投票响应。上列**缺码**是独立的静态结论，不能用这些待验项掩盖；也不能从静态阅读宣布上述宿主体验已经通过。投稿与编辑专项验收由并行工作维护。
