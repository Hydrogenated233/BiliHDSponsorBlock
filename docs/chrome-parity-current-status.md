# Chrome 0.17.1 非投稿行为：当前源码状态

核对日期：2026-11-06。比较对象固定为 Chrome 0.17.1 发布源 `b3704f380c1614351f5effdc839b8f44e35d1269`，不随上游 HEAD 变化。上游路径以 `upstream-research/chrome-release-source/BilibiliSponsorBlock-b3704f380c1614351f5effdc839b8f44e35d1269/` 为根；Java 路径以 `src/top/local/bilihdskip/` 为根。下文用文件及函数作为定位锚点，避免并行改动使行号失效；检索被忽略的固定源需用 `rg --no-ignore`。

本文替换旧版将新实现列作缺码的判断。**已实现／静态核对**表示当前 Java 有逻辑及调用链；**待运行验证**表示尚缺对应版本的宿主证据；**实际差距**表示仍有可定位的行为差异。静态接线不等于真机通过，更不等于完整对齐。历史范围见 `chrome-parity-audit.md`，受限的测试与设备证据见 [playback-policy-verification.md](playback-policy-verification.md)。本次只读源码并更新文档，未运行测试、构建或设备检查。

## 已实现并静态核对

| 行为 | 当前 Java 实现及接线 | 固定上游对照／边界 |
|---|---|---|
| 有效播放策略 | `PlaybackPolicy` 从完整原始列表提取音乐／同分类 full 信息，再解析分类模式、最短时长、mute 开关及隐藏状态；`HookEntry.tick` 使用策略决定执行。音乐覆盖先于 full 转手动，合并目标只包含允许自动 skip 的段。加速门槛为单段至少 500ms。 | `src/content/skipRules/policy.ts`、`playback.ts`。不能再列音乐、full、minDuration、500ms 门槛为未消费配置；externalSource 仍缺，见后文。 |
| 每 UUID 的本次入场与意图 | `PlaybackVisits.update` 按 UUID（无 UUID 时有回退键）维护半开区间内的访问，处理入段 seek、段内 seek、离段重入、deny／undo／allow 和同动作重叠保护。草稿不进入自动执行；`HookEntry` 接入手动取消、撤销、重新允许及用户改速／改音量意图。 | `src/content/skipRules/{visits,intents,overlap}.ts`。不再是单个全局 blocked interval；实际宿主事件仍需回归。 |
| 暂停与恢复偏好 | `PlaybackResume.update` 区分暂停、等待和加速；消费 `skipResumeAction`、`speedUpResumeAction`，显式暂停中操作优先。`HookEntry` 每 tick 读取宿主 state，state 5 作为暂停；buffering 另由 observer 观察。 | `src/content/skipRules/resume.ts`。纯 buffering 不应代替用户暂停。 |
| 原生 seek 与成功确认 | `HookEntry.hookSeeks` 观察原生 `seekTo`，自身调用用 `OWN_SEEK_DEPTH` 排除；仅无原生 hook 时才回退位移判定。`SeekConfirmation` 用 generation 和目标邻近判定；`observePlayback` 注册原生完成回调，`requestSeek`／`completedSeek` 延后成功及合并成员统计，超时不记成功。 | `src/content/skipSeek.ts`、`src/content/skipRules/runtime.ts`、`src/content/skipScheduler.ts`。`reached` 可由回调或目标位置邻近满足，容差 750ms；不能写成必须收到回调或精确落点。最新回调／owner 组合仍待实机确认。 |
| 播放器 owner 与异步上下文 | `HookEntry.selectPlaybackOwner` 结合 resumed activity 的窗口 token、active core、可见面板及面积选 owner；切换时恢复旧效果并清 UI。`isCurrent` 以及请求发布／结束路径检查 owner、director 和 BV/CID，seek 回调带 generation。 | 这是 HD 适配的静态防护。预加载 core 也可能 active，不能据此宣称已解决真机抢占；最新 owner 修复未验证。 |
| 可见性与原始数据 | `Logic.parseAll` 保留负票记录并按精确 BV/CID 解析 skip／mute／full／poi。`SegmentVisibility` 区分默认、显式显示、负票、最短时长和主动隐藏；配置键 `_segmentVisibility`。`SettingsProvider` 合并会话／持久覆盖；持久化受 `trackDownvotes` 和私密会话设置约束。策略消费隐藏结果，列表保留记录与原因。 | 上游 popup、hideSegment／addHiddenSegment 对应行为。不能再写成负票段全部丢弃、没有 UUID 隐藏或始终持久化。 |
| 社区列表与投票 | `MainActivity.player/segmentActions/vote` 有状态／锁定／分类展示、UUID 复制、隐藏／显示／恢复默认、投票 1／0／20 和分类修正。快照捕获 BV/CID/UUID 并校验上下文；逐 UUID pending 防重复；接受的赞踩更新可见性，撤票清默认，分类票请求刷新；405／429 不计确认成功。full 无区间跳过，POI 用单点导航。 | 列表有实现；服务器写入、播放端同步及切分 P 竞态未由本次实测。通知内投票仍属差距。 |
| 设置导入、导出与设置重置 | `SettingsImport.merge` 在现有配置上合并补丁，先验证再生成候选，保留未提供的分类项和未知兼容字段；忽略 `userID/private_id/privateID`。校验已知布尔、枚举、有限数、HTTPS 地址和分类格式等。`MainActivity.onActivityResult` 已调用 merge 后保存；导出移除身份别名。倍率 UI 为 1.1–16；通知模式 UI 为 0–4。`resetToDefaults` 和确认入口已有设置重置。 | `src/options.ts: setTextOption/downloadConfig`；`src/config.ts: resetToDefault`。Android 设置重置保留身份、本地统计、草稿与可见性等数据；不能等同 Chrome 同时重置 localDefaults。OtherData 恢复和导入差异预览未实现。 |
| 提示策略与两类时钟 | `NoticePolicy.initialPlayback` 区分 mute 主操作及可选跳到结束；`presentation` 消费模式 0–4，预告不当成已跳过。`NoticeClock` 用单调时间计显示寿命，可组合暂停／悬停／交互等 hold；`secondsUntilSegment` 则按权威媒体位置及倍率计算，不用墙钟外推。 | `src/notices/{SkipNoticeModel,NoticeClock}.ts`。发布源以 0–4 为比较范围，不将后续模式当成缺失。 |
| 预告与多卡片提示 | `NoticeStack` 按 phase 与排序 UUID 集合维护卡片，相交替换、无关并存，最多 5 张；有分别关闭、hover／touch／manual hold、媒体区间及预告窗口边界。`HookEntry.showDock/showActiveNotices` 接入提前提示、本次不跳过／允许跳过、skip／mute 操作、撤销以及 mute 的跳到结束。paused／buffering／pendingSeek／停滞影响显示时钟。 | `src/notices/UpcomingSkipDecision.ts` 及通知组件。Java `UpcomingDecision` 是独立辅助逻辑，运行路径用 `PlaybackVisits`；不能把辅助类直接当成已接入调度器。owner 修复后预告取消、自然入段及手动卡片寿命已作有限复测，见验证记录。 |

原有分类与动作模型、播放器值所有权恢复、全片分类文本、单服务器查询、404 空结果、基础缓存及本地统计仍在。原生时间轴、编辑、预览、投稿路径由父任务继续验证，本文不替它们提供验收结论。

## 待运行验证

0.3.1 完整 Android 检查为 124 项测试、release 构建及 lint 通过。可见 owner、预告取消后入段、超过显示时长的手动卡片，以及最终包跳过／撤销／重新跳过已作有限真机复测，具体版本与位置见 [验证记录](playback-policy-verification.md)。

其余预加载组合、旧 director 异步结果、全部通知模式、重叠 mute／speed、高倍速 seek 和恢复偏好仍需扩展设备覆盖。真实投稿／投票写操作未用于测试，不将静态调用链当成网络成功。

## 实际剩余差距

以下是仍存在的源码差异，不包含上表已实现的行为。

| 范围 | 可定位差距 | 比较锚点 |
|---|---|---|
| 播放策略和展示 | `Logic.Segment` 无 externalSource 元数据及对应过滤；没有上游规则 trace。POI 可解析／列表跳转，但缺独立的未来高能手动提示及自动模式导航。加速完成无与跳过完成对应的统计路径。`showTimeWithSkips` 有设置入口但无时长显示消费方；频道白名单缺稳定 UP ID 获取、管理及执行抑制闭环。 | Java `Logic.Segment`、`PlaybackPolicy`、`HookEntry.tick/showDock/completedSeek`、`MainActivity.settings`；上游 `src/content/skipRules/{policy,engine,playback}.ts`，`engine` 的独立 highlights／poi 路径。 |
| 提示语义和交互 | 关闭卡片目前只删除 UI，不映射本次 visit 的 deny／undo；没有 Chrome 通知内投票／反馈闭环、声音及音量设置。多卡片与 mute 双操作已有，但不能据此宣称所有通知状态转换一致。 | Java `NoticePolicy/NoticeStack`、`HookEntry.showDock/showActiveNotices`；上游 `src/notices/SkipNoticeModel.ts` 及 `SkipNoticeComponent.tsx`。 |
| 设置与服务基础设施 | OtherData 全量恢复尚缺及与 Chrome localDefaults 对应的数据重置。片段缓存仍是内存、一小时／最多 1000 项，单响应字符长度限制不等于持久缓存总计 500KiB；无标签缓存与相应管理界面。服务器官方 OTA、镜像健康／路由及请求合并入口仍缺。 | Java `SettingsImport`、`MainActivity`、`HookEntry.load/finish`；上游 `src/options.ts`、`src/config.ts`、`src/requests/background/backgroundCache.ts`：片段／标签各有持久 TTL 缓存、总量约束与 clearAll。 |
| 播放器之外的页面 | 无信息流视频卡片全片标签／过滤、动态／评论赞助识别、用户正则与 OTA、弹幕时间导航／空降。原生 seek 预览缩略图不能替代视频卡片标签。需要映射 HD 页面承载点，不能仅因上游用 DOM 就判为浏览器专属 N/A。 | 上游 `src/thumbnail-utils/thumbnailManagement.ts: setupThumbnailListener/checkPageForNewThumbnails`、`src/render/DynamicAndCommentSponsorBlock.tsx: DynamicListener/CommentListener`、`src/utils/sponsorRegex.ts`、`src/config/sponsorRegexOTA.ts`、`src/content/danmakuSkip.ts`、`src/utils/danmakusUtils.ts`。 |
| 账号与跨站附加能力 | 无账号远端统计／VIP／权限／警告的完整 UI 与请求闭环；`ApiClient.publicId` 只是工具。无 YouTube 对应视频读取／预览和分 P/CID 元数据恢复。绑定提交／绑定投票属于另一个写操作范围。 | 上游 `src/popup/UserWork.tsx`、`src/popup/PortVideoSection.tsx`、`src/components/YouTubeLogoButton.tsx`、`src/requests/portVideo.ts: getPortVideoByHash/updatePortedSegments`。 |

## 最多五项有界跟进

这是下一批可交付范围；完成这五项仍不能宣称完整对齐，范围外差异继续保留在上表。

| 顺序 | 有界交付 | 完成判据 |
|---|---|---|
| 1 | **扩展 owner／seek／预告回归。** 现有有限复测已登记；继续覆盖更多预加载 core、旧回调、高倍速和重叠动作，不重复消费受污染旧结果。 | 记录构建与新测试计数、owner／BV/CID、操作和结果；受污染旧结论不复用，失败保留为未解决。 |
| 2 | **补 POI 独立调度。** 限未来 POI 的手动入口、自动导航、分类模式及视频／分 P 上下文。 | 固定样例验证手动不自动 seek、自动只导航一次、切分 P 不串操作；不将 full 当区间或 POI。 |
| 3 | **闭合通知关闭的 visit 意图。** 按固定上游逐 phase／action 明确关闭行为，接入对应 deny／undo 并验证重入，不在此项扩展声音或通知投票。 | 卡片关闭后的本次行为、其他 UUID 卡片、离段再入与显式重新允许都有确定结果；列表可见性不被误改。 |
| 4 | **补片段持久缓存边界。** 限片段缓存的一小时 TTL、最多 1000 项、500KiB 总量、服务地址隔离与清除入口；标签缓存／服务 OTA 另保留差距。 | 重启、过期、总量淘汰、切服务器和清除的样例可复现；不再以单响应字符数冒充总字节预算。 |
| 5 | **建立外部页面附加能力适配清单，并定位一个 HD 视频卡片入口。** 清单必须包含标签／过滤、动态／评论与正则 OTA、弹幕导航、账号信息、YouTube 只读对应／CID 恢复；每项写明宿主承载点或尚缺证据。此次先交付视频卡片承载点与标签展示方案。 | 上述范围逐项有状态和上游锚点，视频卡片与 seek 预览明确区分；无依据不标 N/A。远端写操作另行验收。 |
