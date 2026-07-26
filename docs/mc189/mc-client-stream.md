---
area: net/minecraft/client/stream
slug: mc-client-stream
files: 10
lines: 3811
tier: B
---

# net/minecraft/client/stream — Twitch 推流子系统

## 定位

这是 1.8.9 原版内置的 Twitch 直播集成层：视频广播（`BroadcastController`）、Twitch 聊天（`ChatController`）、带宽测速（`IngestServerTester`）、时间线元数据（`Metadata` 系列），全部由门面类 `TwitchStream` 聚合，对外只暴露 `IStream` 接口。

**关键移植事实：本仓库中该包在运行时是死代码。** `Minecraft.initStream()`（`Minecraft.java:611-617`）直接 `this.stream = new NullStream(null);`，注释明确说明 tv.twitch SDK 已死、natives 已移除，`TwitchStream` 永远不会被构造。`tv.twitch.*` 依赖仅为编译保留（`client/pom.xml:214-219`，`tv.twitch:twitch:6.5`）。

调用方：`Minecraft`（每帧调用 `func_152935_j()`/`func_152922_k()`、按键分发、关闭钩子）、`NetHandlerPlayClient`（成就/战斗/死亡元数据）、`GuiStreamIndicator`、`GuiScreen`、`gui/stream/` 下的各个 GUI、`GameSettings`、`GuiOptions`。它调用：`tv.twitch.*`（外部 SDK stub）、`GlStateManager`/`Tessellator`/`Framebuffer`（帧捕获）、`GuiNewChat`（把 Twitch 聊天打进游戏聊天框）。

如果整包消失：`Minecraft`、`NetHandlerPlayClient`、`gui/stream/*`、`GuiStreamIndicator`、`GuiScreen`、`GameSettings` 等大量引用处编译失败；但由于运行时只用 `NullStream`，删掉 `TwitchStream` 及其三个 controller 只影响编译，不影响运行行为。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| BroadcastController | 1218 | （无，含内部 interface BroadcastListener、enum BroadcastState） | 包装 tv.twitch 广播 SDK 的状态机：认证→登录→取 ingest 服务器→推帧 |
| ChatController | 921 | （含内部类 ChatChannelListener implements IChatChannelListener） | 包装 tv.twitch 聊天 SDK：频道连接、消息收发、emoticon/badge 数据 |
| IStream | 86 | interface | 推流子系统对外唯一接口，含 enum AuthFailureReason |
| IngestServerTester | 502 | （含内部 interface IngestTestListener、enum IngestTestState） | 逐个 ingest 服务器做带宽测试（提交随机帧测 RTMP 发送速率） |
| Metadata | 83 | （无） | 流时间线元数据：name + description + ≤50 条 key/value payload，Gson 序列化 |
| MetadataAchievement | 15 | extends Metadata | "achievement" 元数据：成就 id/名称/描述 |
| MetadataCombat | 26 | extends Metadata | "player_combat" 元数据：玩家与主要对手 |
| MetadataPlayerDeath | 21 | extends Metadata | "player_death" 元数据：死者与击杀者 |
| NullStream | 168 | implements IStream | 全空实现；本移植版运行时唯一实例，持有初始化失败的 Throwable |
| TwitchStream | 771 | implements BroadcastController.BroadcastListener, ChatController.ChatListener, IngestServerTester.IngestTestListener, IStream | 门面：聚合三个 controller，做帧捕获、聊天转发、音量/参数管理 |

## 核心类详解

### TwitchStream（TwitchStream.java）

关键字段（`TwitchStream.java:54-75`）：
- `public static final Marker STREAM_MARKER = MarkerManager.getMarker("STREAM");`（:55），被 Broadcast/Chat controller 的日志共用
- `private final BroadcastController broadcastController;` / `private final ChatController chatController;`（:56-57）
- `private final Minecraft mc;`（:61）；`private Framebuffer framebuffer;`（:64）— 用于缩放拷贝主帧缓冲的离屏 FBO
- `private int targetFPS = 30;`（:68）；`private static boolean field_152965_q;`（:75）— 静态块中 Windows 原生库加载是否成功

关键方法：
- `public TwitchStream(Minecraft mcIn, final Property streamProperty)`（:77）— 构造时写死 client id `"nmt37qblda36pvonovdkbopzfzw3wlq"`（:84-85）；若 `streamProperty` 有值且 `OpenGlHelper.framebufferSupported`，起守护线程 `"Twitch authenticator"` 请求 `https://api.twitch.tv/kraken?oauth_token=...` 验证 token（:96），成功后注册 JVM shutdown hook `"Twitch shutdown hook"`（:110）并调用 `broadcastController.func_152817_A()` / `chatController.func_175984_n()` 初始化 SDK。**本移植版此构造器无人调用。**
- `public void func_152935_j()`（:148）— 每帧更新：按 `gameSettings.streamChatEnabled`（0=streaming 时,1=always,2=never）连接/断开聊天频道，然后 `broadcastController.func_152821_H()`（SDK 任务泵 + 状态机推进）和 `chatController.func_152997_n()`（flushEvents）。由 `Minecraft.runGameLoop` 在 `Minecraft.java:1177` 调用。
- `public void func_152922_k()`（:208）— 每帧提交视频帧：广播中且未暂停时，按 `targetFPS` 节流（:212-215），把 `mc.getFramebuffer()` 的纹理画进私有 `framebuffer`（固定管线 ortho 全屏 quad，:221-254），再 `captureFramebuffer` + `submitStreamFrame`（:255-257）。由 `Minecraft.java:1179` 调用。
- `public void func_152930_t()`（:367）— 开始广播：由 `GameSettings` 的 streamKbps/streamFps/streamBytesPerPixel/streamCompression 组装 `VideoParams`，创建或 resize 离屏 `Framebuffer`（:386-393），选 preferred ingest server，`broadcastController.func_152836_a(videoparams)` 启动，并 `func_152828_a((String)null, "Minecraft", (String)null)` 把游戏名设为 "Minecraft"（:411）。由 `Minecraft.dispatchKeypresses` 的开始推流按键触发。
- `public void func_152911_a(Metadata p_152911_1_, long p_152911_2_)`（:278）— 单点元数据（achievement/death）；`public void func_176026_a(Metadata p_176026_1_, long p_176026_2_, long p_176026_4_)`（:295）— 区间元数据（combat span）。都要求 `isBroadcasting() && this.field_152957_i`（streamSendMetadata 设置）。
- `public void func_180605_a(String p_180605_1_, ChatRawMessage[] p_180605_2_)`（:583）— Twitch 聊天进入游戏聊天框：先 `func_176027_a` 缓存用户信息，`func_176028_a`（:635）按 banned/admin/mod/staff/订阅过滤，构造带 HoverEvent + `ClickEvent.Action.TWITCH_USER_INFO` 的 `IChatComponent`，`mc.ingameGUI.getChatGUI().printChatMessage(...)`（:610）。
- 静态块（:744-770）：仅 Windows 下 `System.loadLibrary("avutil-ttv-51")` 等 4 个原生库（3 个固定 + libmfxsw64/32 按架构二选一），失败则 `field_152965_q = false`；非 Windows 平台不加载任何库，直接 `field_152965_q = true`（`func_152928_D()` 在 mac/linux 上因此只取决于 `broadcastController.func_152858_b()`）。

### BroadcastController（BroadcastController.java）

关键字段：
- `protected BroadcastController.BroadcastState broadcastState = BroadcastController.BroadcastState.Uninitialized;`（:64）— 14 态状态机（:1201-1217）
- `protected Core streamCore;`（:55）/ `protected Stream theStream;`（:58）— tv.twitch SDK 句柄
- `protected List<FrameBuffer> field_152874_j` / `field_152875_k`（:59-60）— 全部/空闲帧缓冲池（固定 3 个，`func_152823_L()` :1032 分配）
- `private static final ThreadSafeBoundList<String> field_152862_C = new ThreadSafeBoundList(String.class, 50);`（:44）— 最近 50 条错误/警告，崩溃报告用（:1091）
- `protected IStreamCallbacks streamCallback = new IStreamCallbacks() {...}`（:86-354）— SDK 异步回调总入口，驱动状态迁移并转发给 `broadcastListener`

关键方法：
- `public boolean func_152817_A()`（:496）— 初始化 SDK：`streamCore.initialize(this.field_152868_d, System.getProperty("java.library.path"))`（:505），成功进入 `Initialized`。
- `public void func_152821_H()`（:911）— 每帧泵：`theStream.pollTasks()` 派发 SDK 回调；ingest 测试期间推进 tester；switch 状态机：`Authenticated`→`login`，`LoggedIn`→`getIngestServers`，`ReceivedIngestServers`→`ReadyToBroadcast`+`getUserInfo`+`getArchivingState`，`Broadcasting/Paused`→`func_152835_I()`（每 30 秒拉一次 StreamInfo，:992-1008）。
- `public boolean func_152836_a(VideoParams p_152836_1_)`（:734）— 启动广播：克隆 VideoParams、组装 AudioParams、分配 3 个帧缓冲、`theStream.start(...)`，进入 `Starting`（异步 `startCallback` :254 决定成败）。
- `public ErrorCode submitStreamFrame(FrameBuffer frame)`（:1104）— 提交视频帧；失败会 `stopBroadcasting()` 并通知 `broadcastListener.func_152893_b(errorcode)`（:1133）。
- `public void captureFramebuffer(FrameBuffer p_152846_1_)`（:1081）— `theStream.captureFrameBuffer_ReadPixels`（glReadPixels 路径），异常包成 `ReportedException` 带缓冲池状态。
- `protected void func_152827_a(BroadcastController.BroadcastState p_152827_1_)`（:891）— 唯一的状态迁移入口，去重后回调 `broadcastListener.func_152891_a`。
- `public void statCallback()`（:565）— 名字有误导：其实是同步关停（等待 ingest 测试取消后 `func_152851_B()`），shutdown 路径调用。
- `public IngestServerTester func_152838_J()`（:1010）— 仅在 `ReadyToBroadcast` 时创建并启动 tester，状态进 `IngestTesting`。

### ChatController（ChatController.java）

关键字段：
- `protected HashMap<String, ChatController.ChatChannelListener> field_175998_i = new HashMap();`（:39）— 频道名 → 监听器
- `protected int field_153015_m = 128;`（:40）— 每频道 raw/tokenized 消息队列上限
- `protected ChatController.EnumEmoticonMode field_175997_k = ChatController.EnumEmoticonMode.None;`（:41）— None 时不下载 emoticon/badge 数据
- `protected int field_175993_n = 500;` / `protected int field_175994_o = 2000;`（:44-45）— messageFlushInterval / userChangeEventInterval（毫秒）

关键方法：
- `public boolean func_175984_n()`（:178）— 初始化 Core+Chat SDK，按 emoticon 模式选 `ChatTokenizationOption`。
- `public void func_152997_n()`（:336）— 每帧 `field_153008_f.flushEvents()`，SDK 回调（消息、成员变更）在此派发。
- `protected boolean func_175987_a(String p_175987_1_, boolean p_175987_2_)`（:239）— 加入频道（第二参 anonymous）；`public boolean func_175986_a(String p_175986_1_, String p_175986_2_)`（:350）— 发消息。
- `public void func_175988_p()`（:312）— 同步 shutdown：循环 `Thread.sleep(200L)` + `func_152997_n()` 直到 `Uninitialized`。
- 内部类 `ChatChannelListener`（:467）：`chatChannelRawMessageCallback`（:805）入队并转发 `field_153003_a.func_180605_a`，超过 128 条丢最旧；`chatChannelMembershipCallback`（:749）处理 JOINED/LEFT；`chatClearCallback`（:855）按用户名清消息。

### IngestServerTester（IngestServerTester.java）

- 构造 `public IngestServerTester(Stream p_i1019_1_, IngestList p_i1019_2_)`（:170）。
- `public void func_176004_j()`（:176）— 启动：**换走** Stream 上原有的 stream/stat callbacks 存到 `field_153057_o`/`field_153058_p`（:186-189），配 1280x720@60fps 3500kbps `TTV_PF_BGRA` 参数，分配 3 个随机化帧缓冲。
- `public void func_153041_j()`（:229）— 每帧推进状态机（Starting→ConnectingToServer→TestingServer→DoneTestingServer→下一台→Finished）；`func_153029_c(IngestServer)`（:399）在每台服务器上限 `field_153048_f = 8000L` 毫秒内反复 `submitVideoFrame` + `pollStats`，按 `TTV_ST_RTMPDATASENT` 统计算 `bitrateKbps = (float)(this.field_153050_h * 8L) / (float)this.func_153037_m()`（:425）。
- `public void func_153039_l()`（:291）— 请求取消；`public boolean func_153032_e()`（:160）— Finished/Cancelled/Failed 之一即为完成。
- `protected void func_153031_o()`（:445）— 清理：释放帧缓冲并**还原**原 callbacks（:459-469）。
- 进度查询：`func_153030_h()`（:165，单服务器进度）、`field_153065_w`（总进度，:394 计算）、`func_153028_p()`（:155，当前服务器下标）。

### Metadata 系列（Metadata.java）

- `public Metadata(String p_i46345_1_, String p_i46345_2_)`（:15）— name + description。
- `public void func_152808_a(String p_152808_1_, String p_152808_2_)`（:36）— 加 payload 项；payload > 50 条、key/value 为 null 或长度 > 255 时抛 `IllegalArgumentException`。
- `public String func_152806_b()`（:69）— Gson 序列化 payload 为 JSON，空则 null。
- 三个子类只是预填构造器：`MetadataAchievement(Achievement)`（MetadataAchievement.java:7，name="achievement"）、`MetadataCombat(EntityLivingBase, EntityLivingBase)`（MetadataCombat.java:7，name="player_combat"）、`MetadataPlayerDeath(EntityLivingBase, EntityLivingBase)`（MetadataPlayerDeath.java:7，name="player_death"）。

### NullStream（NullStream.java）

`public NullStream(Throwable p_i1006_1_)`（:11）保存失败原因（本移植版传 null），所有 `IStream` 方法返回 false/0/null/空数组；`public Throwable func_152937_a()`（:164）取出该 Throwable。这是运行时唯一存在的 `IStream` 实现。

## 时序与生命周期

全部在**客户端主线程**，两个例外：
- `TwitchStream` 构造器里的 `"Twitch authenticator"` 守护线程（TwitchStream.java:90-134，一次性 HTTP 验证后调 SDK 初始化）；
- JVM `"Twitch shutdown hook"` 线程（:110-116，调 `shutdownStream()`）。

原版时序（本移植版因 NullStream 全部为 no-op，但调用点仍在）：
1. 启动：`Minecraft.startGame` → `initStream()`（Minecraft.java:501/611）。原版会 `new TwitchStream(this, twitchProperty)`；本仓库直接 `new NullStream(null)`。
2. 每帧（`runGameLoop`，非 tick）：`this.stream.func_152935_j()`（Minecraft.java:1177，聊天连接管理 + `pollTasks` 状态机泵 + `flushEvents`）→ `this.stream.func_152922_k()`（Minecraft.java:1179，节流后捕获并提交一帧）。SDK 的所有异步回调（IStreamCallbacks / IChatChannelListener）都在 `pollTasks()`/`flushEvents()` 内于主线程同步派发。
3. 每 30 秒（广播中）：`BroadcastController.func_152835_I()` 拉 StreamInfo（BroadcastController.java:997 `if (j >= 30L)`）。
4. 关闭：`shutdownMinecraftApplet()` → `this.stream.shutdownStream()`（Minecraft.java:1048）→ `broadcastController.statCallback()`（同步等 ingest 测试结束再 shutdown SDK）+ `chatController.func_175988_p()`（200ms 轮询直到 Uninitialized）。
5. 无 per-tick 逻辑；元数据发送由 `NetHandlerPlayClient` 在收包时事件式触发。

## 挂钩点（Hook Points）

注意：本移植版运行时实例是 `NullStream`，下列钩子除标注外都只在换回真实 `TwitchStream`（或自定义 `IStream` 实现）时才有活代码路径。**接管整个子系统的最佳位置是 `Minecraft.initStream()`（Minecraft.java:611）：塞入自己的 `IStream` 实现，即可免费获得每帧回调、按键绑定、GUI 与元数据事件。**

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `void func_152935_j()` | IStream.java:14 / TwitchStream.java:148 | 每帧，`Minecraft.java:1177` | 通用每帧回调（自定义 IStream 时是免费的 per-frame hook） | 在 `updateDisplay()` 之后执行；不要做重活 |
| `void func_152922_k()` | IStream.java:16 / TwitchStream.java:208 | 每帧，`Minecraft.java:1179` | 截取/录制主帧缓冲（现成的帧捕获管线可参考 :219-257） | 原实现改动 GL 矩阵/视口/纹理状态，必须成对恢复 |
| `void shutdownStream()` | IStream.java:12 / TwitchStream.java:141 | `Minecraft.java:1048` 及 JVM shutdown hook | 客户端退出清理钩子 | shutdown hook 线程 ≠ 主线程，勿碰 GL |
| `void func_152911_a(Metadata p_152911_1_, long p_152911_2_)` | IStream.java:24 | `NetHandlerPlayClient.java:1474`（成就包）、`:1535`（死亡） | 观察"玩家获得成就 / 玩家死亡"事件（含击杀者） | 死亡事件仅在 `streamOnDeath`… 实际条件见 NetHandlerPlayClient；Metadata payload 有 50 条/255 字符限制 |
| `void func_176026_a(Metadata p_176026_1_, long p_176026_2_, long p_176026_4_)` | IStream.java:26 | `NetHandlerPlayClient.java:1525`（战斗结束包） | 观察战斗区间事件（MetadataCombat） | 时间参数是相对流时间的偏移 |
| `void func_152930_t()` | IStream.java:44 / TwitchStream.java:367 | 开始推流按键（`Minecraft.java:3139`，keyBindStreamStartStop 经 GuiYesNo 确认） | 接管"开始录制/推流"动作 | 依赖 GameSettings 的 stream* 字段换算参数 |
| `void stopBroadcasting()` / `void pause()` / `void unpause()` / `void requestCommercial()` / `void muteMicrophone(boolean p_152910_1_)` | IStream.java:46/35/40/30/75 | `Minecraft.dispatchKeypresses`（:3129/:3164/:3168/:3176 及 toggle-mic 分支） | 接管推流控制按键（keyBindStreamStartStop/PauseUnpause/Commercials/ToggleMic） | 这些键绑定至今仍在 dispatchKeypresses 中活跃分发 |
| `void updateStreamVolume()` | IStream.java:42 | `GameSettings.java:342,348`、`GuiStreamOptions.java:103,107`、pause/unpause | 音量设置变更通知 | — |
| `ErrorCode submitStreamFrame(FrameBuffer frame)` | BroadcastController.java:1104 | `TwitchStream.func_152922_k` :257 | 帧提交处：改写/丢帧、旁路编码 | 失败路径会自动 `stopBroadcasting()` |
| `void captureFramebuffer(FrameBuffer p_152846_1_)` | BroadcastController.java:1081 | `TwitchStream.func_152922_k` :255 | glReadPixels 捕获点，可替换为 PBO 异步读回 | 抛 `ReportedException` 会带崩溃报告 |
| `protected void func_152827_a(BroadcastController.BroadcastState p_152827_1_)` | BroadcastController.java:891 | 所有广播状态迁移 | 单点观察 14 态状态机；`BroadcastListener.func_152891_a` 即其外部回调 | `TwitchStream.func_152891_a`（TwitchStream.java:448）在 `Initialized` 时会立刻把状态推到 `Authenticated`，形成自动登录链 |
| `void func_180605_a(String p_180605_1_, ChatRawMessage[] p_180605_2_)` | ChatController.ChatListener（ChatController.java:883）/ TwitchStream.java:583 | `flushEvents()` 派发 raw 聊天消息时 | 拦截/改写 Twitch 聊天进游戏聊天框（过滤逻辑在 `func_176028_a` :635） | 每频道队列上限 128，超出丢最旧 |
| `boolean func_175986_a(String p_175986_1_, String p_175986_2_)` | ChatController.java:350 | `TwitchStream.func_152917_b`（:704）→ GUI 发消息 | 出站聊天消息钩子 | 需频道 Connected |
| `void func_152907_a(IngestServerTester p_152907_1_, IngestServerTester.IngestTestState p_152907_2_)` | IngestServerTester.IngestTestListener（IngestServerTester.java:487）/ TwitchStream.java:508 | 测速状态每次变更 | 观察带宽测试进度（`GuiIngestServers` 就靠它刷新） | 测试期间 Stream 的 callbacks 被 tester 换走 |
| `IStream.AuthFailureReason func_152918_H()` | IStream.java:79 | `GuiStreamUnavailable.java:210`（不可用界面的 switch） | 决定推流不可用界面显示的失败原因文案 | NullStream 恒返回 `ERROR`；`GuiOptions.java:218-226` 只调 `func_152936_l`/`func_152928_D` 决定进 GuiStreamOptions 还是 GuiStreamUnavailable |

## 数据与协议

不涉及 Minecraft 封包/NBT/注册表。两处外部数据格式：

**Twitch Kraken 认证响应**（TwitchStream.java:96-106，HTTPS GET `https://api.twitch.tv/kraken?oauth_token=<token>`，Gson 解析）：

| 字段 | 类型 | 读取方法 | 含义 |
|---|---|---|---|
| `token` | JsonObject | `JsonUtils.getJsonObject(jsonobject, "token")` | 认证信息容器 |
| `token.valid` | boolean | `JsonUtils.getBoolean(jsonobject1, "valid")` | token 是否有效；false → `AuthFailureReason.INVALID_TOKEN` |
| `token.user_name` | String | `JsonUtils.getString(jsonobject1, "user_name")` | Twitch 用户名，用于登录与聊天频道名 |

**Metadata payload**（Metadata.java）：

| 字段 | 类型 | 读写方法 | 约束/含义 |
|---|---|---|---|
| `name` | `String`（final） | 构造器 / `func_152810_c()` :74 | 事件类型名："achievement" / "player_combat" / "player_death" |
| `description` | `String` | `func_152807_a(String)` :26 / `func_152809_a()` :31 | 人类可读描述；null 时回退为 name |
| `payload` | `Map<String, String>` | `func_152808_a(String, String)` :36 / `func_152806_b()` :69（Gson→JSON） | ≤50 条；key/value 非 null 且 ≤255 字符，违规抛 `IllegalArgumentException` |

payload 键：achievement → `achievement_id`/`achievement_name`/`achievement_description`；combat → `player`/`primary_opponent`；death → `player`/`killer`。

## 不变量与陷阱

- **运行时恒为 NullStream。** 任何"为什么推流功能没反应"的问题先看 `Minecraft.java:611-617`。想复活或替换功能，改 `initStream()`，不要试图让 `TwitchStream` 自然工作——原生库已从发行版移除，Kraken API 也早已下线。
- `TwitchStream` 静态块（TwitchStream.java:744-770）在**类加载时**尝试 `System.loadLibrary`；仅引用 `TwitchStream.STREAM_MARKER`（Broadcast/ChatController 的日志都引用了）就会触发加载尝试，失败被吞进 `field_152965_q = false`，不会抛。
- SDK 回调全部在主线程的 `pollTasks()`/`flushEvents()` 内同步派发——`BroadcastController`/`ChatController` 无锁的字段访问依赖这一点。唯一跨线程的是 authenticator 线程调用 `func_152818_a`/`func_152998_c`/`func_152994_a`/`func_152817_A`/`func_175984_n`（TwitchStream.java:107-118），以及错误列表用了 `ThreadSafeBoundList`（BroadcastController.java:44）。自定义实现若引入真异步，必须自行回到主线程再碰 GL 或 GUI。
- `BroadcastController.statCallback()`（:565）和 `ChatController.func_175988_p()`（:312）都是**主线程忙等**（`Thread.sleep(200L)` 循环），shutdown 时可能卡住主线程；`ChatController.func_175988_p` 的循环退出依赖 SDK 回调把状态推回 `Uninitialized`。
- `IngestServerTester.func_176004_j()` 会替换 `Stream` 上的 stream/stat callbacks（IngestServerTester.java:186-189），`func_153031_o()`（:445）负责还原；测试进行中 `BroadcastController.func_152851_B()`/`func_152845_C()`/`func_152838_J()` 都以 `isIngestTesting()` 拒绝操作。若测试异常中断而未走 `func_153031_o()`，广播回调将永久丢失。
- `TwitchStream.func_152891_a`（:448）在收到 `Initialized` 状态时直接调用 `broadcastController.func_152827_a(BroadcastState.Authenticated)`——监听器反向驱动状态机，改动状态枚举或迁移逻辑时注意这条隐藏边。
- `func_152922_k()` 的帧捕获（TwitchStream.java:221-254）是**固定管线**代码（`GlStateManager.matrixMode(5889)`、`GlStateManager.ortho`、`GL11.glTexParameterf`），依赖 lwjgl2-shim 的兼容层；在 core profile 下不可用。魔数 5889/5888 即 `GL_PROJECTION`/`GL_MODELVIEW`。
- 帧缓冲池固定 3 个（BroadcastController.java:1034 `for (int i = 0; i < 3; ++i)`），`func_152822_N()` 池空时返回 null 并打日志"Out of free buffers"——`func_152922_k` 不判空，理论上可 NPE（原版即如此）。
- `Metadata.func_152808_a` 的上限判断是 `size() > 50`，即实际最多可放 51 条后才抛——边界为原版 off-by-one，文档如实记录。
- client id `"nmt37qblda36pvonovdkbopzfzw3wlq"` 硬编码于 TwitchStream.java:84-85（原版即硬编码，非本仓库引入的秘密）。

## 交叉引用

- `net.minecraft.client` → `Minecraft#initStream`（:611，构造 NullStream）、`Minecraft#getTwitchStream`（:3110）、`Minecraft#runGameLoop`（:1177/:1179 每帧调 `IStream#func_152935_j`/`func_152922_k`）、`Minecraft#shutdownMinecraftApplet`（:1048 调 `IStream#shutdownStream`）、`Minecraft#dispatchKeypresses`（:3125-3180 推流四个按键 → `IStream` 控制方法）
- `net.minecraft.client.network` → `NetHandlerPlayClient#handleStatistics`（:1474，`new MetadataAchievement` → `IStream#func_152911_a`）、`NetHandlerPlayClient#handleCombatEvent`（:1525 `MetadataCombat` → `func_176026_a`；:1535 `MetadataPlayerDeath` → `func_152911_a`）
- `net.minecraft.client.settings` → `GameSettings#setOptionFloatValue`（:342/:348 → `IStream#updateStreamVolume`）；`TwitchStream#func_152930_t` 反向读 `GameSettings` 的 `streamKbps/streamFps/streamBytesPerPixel/streamCompression/streamPreferredServer/streamSendMetadata`
- `net.minecraft.client.gui` → `GuiStreamIndicator`（:24/:78/:83 读 `isBroadcasting/isPaused/func_152929_G` 画 HUD 指示器）、`GuiScreen#handleComponentClick`（:455-459，`TWITCH_USER_INFO` 点击 → `func_152926_a` → 打开 `GuiTwitchUserMode`）、`GuiOptions`（:218 Broadcast 按钮状态）
- `net.minecraft.client.gui.stream` → `GuiStreamOptions`（:77/:103/:109 广播选项界面）、`GuiIngestServers`（:35 `func_152909_x` 启动测速；:58 `func_152932_y().func_153039_l()` 取消）、`GuiTwitchUserMode#func_152328_a`（TwitchStream.java:602 引用，渲染用户模式 tooltip）
- `net.minecraft.client.renderer` → `TwitchStream#func_152922_k` 使用 `GlStateManager`/`Tessellator`/`WorldRenderer`/`DefaultVertexFormats`/`OpenGlHelper.framebufferSupported`；`net.minecraft.client.shader` → `Framebuffer`（离屏捕获目标）
- `net.minecraft.util` → `HttpUtil#get`（认证请求）、`JsonUtils`、`ThreadSafeBoundList`（错误环形列表）、`Util#getOSType`（原生库加载分支）、`MathHelper`
- `net.minecraft.crash` → `CrashReport`/`CrashReportCategory`（`BroadcastController#captureFramebuffer` :1089 崩溃报告）
- `net.minecraft.stats` → `Achievement`（`MetadataAchievement` 构造）；`net.minecraft.entity` → `EntityLivingBase`（`MetadataCombat`/`MetadataPlayerDeath`）
- 外部 `tv.twitch.*`（Maven `tv.twitch:twitch:6.5`，compile-only）→ `Core`/`Stream`/`Chat` 及各回调接口

## 覆盖声明

完整读取了 10/10 个文件（全部通过 Read 逐行读入）。逐行精读：`TwitchStream`、`BroadcastController`、`ChatController`、`IngestServerTester`、`IStream`、`NullStream`、`Metadata`、`MetadataAchievement`、`MetadataCombat`、`MetadataPlayerDeath`——即全部文件；无仅结构性浏览的类。另外用 grep + 局部 Read 核实了包外调用点（`Minecraft.java`、`NetHandlerPlayClient.java`、`GameSettings.java`、`gui/stream/*`、`client/pom.xml`）的行号。未读 `tv.twitch.*` SDK 源码（Maven 二进制依赖），其行为描述仅基于本包调用方式。
