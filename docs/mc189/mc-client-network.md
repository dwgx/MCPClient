---
area: net/minecraft/client/network
slug: mc-client-network
files: 6
lines: 2972
tier: A
---

# net/minecraft/client/network

## 定位

本包是客户端"网络语义层"：底层字节收发、加密、压缩由 `net.minecraft.network.NetworkManager`（Netty pipeline）负责，本包负责把解码后的 `Packet` 翻译成客户端游戏状态变更。核心是三个 `INetHandler` 实现，对应协议的三个阶段：

- LOGIN 阶段：`NetHandlerLoginClient`（加密握手、Mojang 会话验证、压缩开关）；
- PLAY 阶段：`NetHandlerPlayClient`（全部游戏内 S→C 封包的处理入口，也是 C→S 封包唯一发送出口 `addToSendQueue`）；
- 单机集成服务端一侧的 HANDSHAKING 阶段：`NetHandlerHandshakeMemory`（虽在 client 包，实际运行在集成服务端线程上）。

辅助类：`NetworkPlayerInfo`（Tab 列表中每个玩家的档案/延迟/皮肤缓存）、`OldServerPinger`（服务器列表 ping，含 1.6- 旧协议兼容 ping）、`LanServerDetector`（UDP 组播发现局域网世界）。

谁调用它：`GuiConnecting`/`Minecraft#loadWorld`/`RealmsConnect` 创建 login handler；Netty EventLoop 通过 `NetworkManager.channelRead0` 调用各 `handleXxx`；`PlayerControllerMP#updateController` 每 tick 驱动收发；`EntityPlayerSP.sendQueue`、`PlayerControllerMP`、各 GUI 通过 `addToSendQueue` 发包；`GuiMultiplayer` 驱动 pinger 与 LAN 探测；`GuiPlayerTabOverlay`/`AbstractClientPlayer` 消费 `NetworkPlayerInfo`。

它调用谁：`Minecraft`（loadWorld / displayGuiScreen / addScheduledTask）、`WorldClient`（实体与方块状态写入）、`EntityPlayerSP`、`Scoreboard`、`SkinManager`、`NetworkManager`。

如果它消失：客户端无法完成登录握手、无法进入世界，进入世界后收到的任何服务端封包都不会反映到本地世界——多人游戏与单机（走本地 channel 的集成服务端）全部瘫痪。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| `LanServerDetector`（含内部类 `LanServer`、`LanServerList`、`ThreadLanServerFind`） | 156 | `ThreadLanServerFind extends Thread` | 监听 224.0.2.60:4445 UDP 组播，发现局域网开放的世界并维护列表 |
| `NetHandlerHandshakeMemory` | 38 | `implements INetHandlerHandshakeServer` | 集成服务端处理本地内存 channel 的握手：切协议状态并移交 `NetHandlerLoginServer` |
| `NetHandlerLoginClient` | 127 | `implements INetHandlerLoginClient` | LOGIN 阶段客户端：加密请求、joinServer 会话验证、压缩、成功后切换到 PLAY handler |
| `NetHandlerPlayClient` | 2125 | `implements INetHandlerPlayClient` | PLAY 阶段客户端：约 70 个 `handleXxx` 把 S 包写入世界/玩家/GUI 状态；`addToSendQueue` 发 C 包 |
| `NetworkPlayerInfo` | 209 | （无） | Tab 列表条目：GameProfile、游戏模式、延迟、displayName、皮肤/披风纹理懒加载 |
| `OldServerPinger` | 317 | （无） | 服务器列表 ping：STATUS 协议查询 + pong 计延迟；失败时回退 0xFE 旧协议 ping |

## 核心类详解

### NetHandlerPlayClient（NetHandlerPlayClient.java）

字段（`NetHandlerPlayClient.java:217-255`）：

- `private final NetworkManager netManager` — 收发通道（L223）。
- `private final GameProfile profile` — 本地玩家档案（L224）。
- `private final GuiScreen guiScreenServer` — 断线后返回的界面；集成服务端为 null（L230）。
- `private Minecraft gameController` — 非 final，`handleResourcePack` 的回调里会重新赋值为 `Minecraft.getMinecraft()`（L235, L1763）。
- `private WorldClient clientWorldController` — 当前客户端世界，`handleJoinGame`/`handleRespawn` 重建（L240）。
- `private boolean doneLoadingTerrain` — 收到首个 `S08PacketPlayerPosLook` 后置 true 并关闭 `GuiDownloadTerrain`（L246, L719-726）。
- `private final Map<UUID, NetworkPlayerInfo> playerInfoMap = Maps.<UUID, NetworkPlayerInfo>newHashMap()` — Tab 列表数据源（L247）。
- `public int currentServerMaxPlayers = 20`（L248）；`private boolean field_147308_k` — 是否已收到过一次统计包（L249）；`private final Random avRandomizer`（L255）。

关键方法（签名逐字摘自源码）：

- `public NetHandlerPlayClient(Minecraft mcIn, GuiScreen p_i46300_2_, NetworkManager p_i46300_3_, GameProfile p_i46300_4_)`（L257）— 由 `NetHandlerLoginClient.handleLoginSuccess` 创建（NetHandlerLoginClient.java:104）。
- `public void cleanup()`（L268）— 置空 `clientWorldController`；`Minecraft.loadWorld(null)` 时调用（Minecraft.java:2357）。
- `public void handleJoinGame(S01PacketJoinGame packetIn)`（L277）— 新建 `PlayerControllerMP` 与 `WorldClient`，`loadWorld`，显示 `GuiDownloadTerrain`，回发 `MC|Brand` 自定义载荷（L291）。
- `public void handlePlayerPosLook(S08PacketPlayerPosLook packetIn)`（L669）— 按 `EnumFlags` 处理相对/绝对坐标，`setPositionAndRotation` 后立即回发 `C03PacketPlayer.C06PacketPlayerPosLook`（L717）；首次收到时结束 terrain 加载并 `displayGuiScreen((GuiScreen)null)`（L725）。服务端反作弊回拉（setback）就走这里。
- `public void addToSendQueue(Packet p_147297_1_)`（L814）— 等价于 `this.netManager.sendPacket(...)`，客户端所有 C 包的唯一逻辑出口。
- `public void onDisconnect(IChatComponent reason)`（L793）— `loadWorld(null)` 后按来源显示 `GuiDisconnected` 或 Realms 断线界面。
- `public void handleDisconnect(S40PacketDisconnect packetIn)`（L785）— 直接 `netManager.closeChannel(packetIn.getReason())`，无线程转移。
- 实体生命周期：`handleSpawnObject`（L297，按硬编码 type id 分派约 20 种实体）、`handleSpawnMob`（L913）、`handleSpawnPlayer`（L530，创建 `EntityOtherPlayerMP`，依赖 `getPlayerInfo(packetIn.getPlayer())` 已存在）、`handleDestroyEntities`（L654）。
- 实体状态：`handleEntityMovement`（L613，serverPosX/Y/Z 以 1/32 格累加）、`handleEntityTeleport`（L566）、`handleEntityVelocity`（L501，速度 /8000）、`handleEntityMetadata`（L516，写 DataWatcher）、`handleEntityProperties`（L2051，非 living 实体直接 `throw new IllegalStateException`，L2060）。
- 世界数据：`handleChunkData`（L747，`chunk.fillChunk`）、`handleMapChunkBulk`（L1337）、`handleBlockChange`（L776）、`handleMultiBlockChange`（L734）。
- 容器：`handleOpenWindow`（L1092，按 guiId 字符串分派）、`handleSetSlot`（L1133）、`handleWindowItems`（L1198）、`handleConfirmTransaction`（L1174，自动回 `C0FPacketConfirmTransaction(..., true)`）、`handleCloseWindow`（L1311）。
- `public void handleChat(S02PacketChat packetIn)`（L849）— type==2 走 action bar（`setRecordPlaying`），否则 `printChatMessage`。
- `public void handleKeepAlive(S00PacketKeepAlive packetIn)`（L1663）— 直接回 `C00PacketKeepAlive`，**无** `checkThreadAndEnqueue`，在 Netty 线程执行。
- `public void handleCustomPayload(S3FPacketCustomPayload packetIn)`(L1822) — 处理 `MC|TrList` / `MC|Brand` / `MC|BOpen` 三个通道；`MC|TrList` 分支 `finally { packetbuffer.release(); }`（L1848）。
- `public void handlePlayerListItem(S38PacketPlayerListItem packetIn)`（L1618）— 维护 `playerInfoMap`（ADD/REMOVE/UPDATE_GAME_MODE/UPDATE_LATENCY/UPDATE_DISPLAY_NAME）。
- `public void handleResourcePack(S48PacketResourcePackSend packetIn)`（L1701）— 支持 `level://` 本地路径与 URL 下载，按 `ServerData.ServerResourceMode` 决定接受/拒绝/弹 `GuiYesNo` 询问；**无** `checkThreadAndEnqueue`，主体在 Netty 线程跑，仅弹窗路径经 `addScheduledTask`（L1755）。
- 查询接口：`public NetworkManager getNetworkManager()`（L2090）、`public Collection<NetworkPlayerInfo> getPlayerInfoMap()`（L2095）、`public NetworkPlayerInfo getPlayerInfo(UUID p_175102_1_)`（L2100）、`public NetworkPlayerInfo getPlayerInfo(String p_175104_1_)`（L2108，按名字线性扫描）、`public GameProfile getGameProfile()`（L2121）。

调用时机：所有 `handleXxx` 首次由 Netty EventLoop 在 `NetworkManager.channelRead0`（NetworkManager.java:149-155）中触发；带 `PacketThreadUtil.checkThreadAndEnqueue(packetIn, this, this.gameController)` 的方法会把自己 reschedule 到主线程并抛 `ThreadQuickExitException` 中止本次调用（PacketThreadUtil.java:7-19），随后由 `Minecraft.runGameLoop` 的 scheduled task 队列在主线程重放。

### NetHandlerLoginClient（NetHandlerLoginClient.java）

字段：`private final Minecraft mc`、`private final GuiScreen previousGuiScreen`、`private final NetworkManager networkManager`、`private GameProfile gameProfile`（L33-36）。

- `public void handleEncryptionRequest(S01PacketEncryptionRequest packetIn)`（L45）— `CryptManager.createNewSharedKey()` 生成 AES 密钥，计算 serverId hash 后调 `MinecraftSessionService.joinServer`（Mojang 会话服务器，同步阻塞在 Netty 线程）；LAN 服务器（`isOnLAN()`，L52）验证失败仅告警不断开；否则按 `AuthenticationUnavailableException` / `InvalidCredentialsException` / `AuthenticationException` 三种情况 `closeChannel`。成功后发 `C01PacketEncryptionResponse`，并在发送完成的 future listener 里 `enableEncryption(secretkey)`（L86-92）——顺序保证：响应包本身明文发出，之后的流量才加密。
- `public void handleLoginSuccess(S02PacketLoginSuccess packetIn)`（L100）— `setConnectionState(EnumConnectionState.PLAY)` 并 `setNetHandler(new NetHandlerPlayClient(this.mc, this.previousGuiScreen, this.networkManager, this.gameProfile))`（L104），协议阶段切换点。
- `public void onDisconnect(IChatComponent reason)`（L110）— 显示 `GuiDisconnected(previousGuiScreen, "connect.failed", reason)`。
- `public void handleEnableCompression(S03PacketEnableCompression packetIn)`（L120）— 本地 channel 跳过，否则 `setCompressionTreshold`。

创建点：`GuiConnecting`（GuiConnecting.java:66，远程连接线程）、`Minecraft.java:2332`（集成服务端本地 channel）、`RealmsConnect.java:52`。

### NetworkPlayerInfo（NetworkPlayerInfo.java）

字段：`private final GameProfile gameProfile`（L21）、`private WorldSettings.GameType gameType`（L22）、`private int responseTime`（L25，毫秒 ping）、`private boolean playerTexturesLoaded`（L26）、`private ResourceLocation locationSkin / locationCape`（L27-28）、`private String skinType`（L29）、`private IChatComponent displayName`（L34）、以及 Tab 界面记分动画用的 `field_178873_i / field_178870_j / field_178871_k / field_178868_l / field_178869_m`（L35-39，未去混淆，配套 getter/setter `func_178835_l`…`func_178843_c`，L160-208，仅被 `GuiPlayerTabOverlay` 读写）。

- 构造器 `public NetworkPlayerInfo(S38PacketPlayerListItem.AddPlayerData p_i46295_1_)`（L46）— 由 `handlePlayerListItem` ADD_PLAYER 分支调用（NetHandlerPlayClient.java:1634）。
- `public ResourceLocation getLocationSkin()`（L92）— 懒触发 `loadPlayerTextures()`，未就绪时回退 `DefaultPlayerSkin.getDefaultSkin(uuid)`（L99，`MoreObjects.firstNonNull`）。
- `protected void loadPlayerTextures()`（L117）— `synchronized (this)` 双检 `playerTexturesLoaded`，调 `SkinManager.loadProfileTextures(..., true)`，回调里填 `locationSkin`/`locationCape`/`skinType`（skinType 为 null 时置 `"default"`，L134-137）。
- `public ScorePlayerTeam getPlayerTeam()`（L112）— 走 `Minecraft.getMinecraft().theWorld.getScoreboard()`，theWorld 为 null 时 NPE。

### OldServerPinger（OldServerPinger.java）

字段：`private static final Splitter PING_RESPONSE_SPLITTER = Splitter.on('\u0000').limit(6)`（L48）、`private final List<NetworkManager> pingDestinations = Collections.<NetworkManager>synchronizedList(...)`（L50）。

- `public void ping(final ServerData server) throws UnknownHostException`（L52）— 建独立 `NetworkManager`，挂匿名 `INetHandlerStatusClient`：`handleServerInfo`（L65）填 MOTD/版本/人数/favicon 并发 `C01PacketPing`；`handlePong`（L155）算 `server.pingToServer` 后 `closeChannel`；`onDisconnect`（L162）未收到 info 时回退 `tryCompatibilityPing`。随后发 `C00Handshake(47, ip, port, EnumConnectionState.STATUS)` + `C00PacketServerQuery`（L176-177）。
- `private void tryCompatibilityPing(final ServerData server)`（L185）— 裸 Netty Bootstrap 发 1.6 旧版 0xFE 0x01 0xFA "MC|PingHost" 探测，解析 0xFF UTF-16BE 响应（`§1` 前缀，L250），`server.version = -1` 标记旧服。
- `public void pingPendingNetworks()`（L276）— 遍历 `pingDestinations`，开着的 `processReceivedPackets()`，关了的 `checkDisconnected()`；由 `GuiMultiplayer.updateScreen` 每 tick 调（GuiMultiplayer.java:118）。
- `public void clearPendingNetworks()`（L299）— 关闭全部未完成 ping；`GuiMultiplayer.onGuiClosed` 调（GuiMultiplayer.java:134）。触发点：`ServerListEntryNormal.java:62` 在列表项可见时调 `ping(server)`。

### LanServerDetector（LanServerDetector.java）

- `LanServerList.func_77551_a(String p_77551_1_, InetAddress p_77551_2_)`（L71）— 解析组播报文（`ThreadLanServerPing.getMotdFromPingResponse/getAdFromPingResponse`），按 `ip:port` 去重，新条目置 `wasUpdated = true`。所有方法 `synchronized`。
- `ThreadLanServerFind`（L100）— 守护线程，`new MulticastSocket(4445)` + `joinGroup(InetAddress.getByName("224.0.2.60"))`，`setSoTimeout(5000)`；循环 `receive`，超时 continue，IOException 记日志退出（L133-137）。`GuiMultiplayer.initGui` 创建并启动（GuiMultiplayer.java:60-64）。

### NetHandlerHandshakeMemory（NetHandlerHandshakeMemory.java）

- `public void processHandshake(C00Handshake packetIn)`（L26）— `setConnectionState(packetIn.getRequestedState())` 后移交 `new NetHandlerLoginServer(this.mcServer, this.networkManager)`。由 `NetworkSystem.addLocalEndpoint` 的 channel initializer 安装（NetworkSystem.java:147），运行在**集成服务端**侧，`onDisconnect` 为空实现（L35）。

## 时序与生命周期

连接建立（多人）：`GuiConnecting` 后台线程 `NetworkManager.createNetworkManagerAndConnect` → `setNetHandler(new NetHandlerLoginClient(...))` → 发 `C00Handshake(47, ..., LOGIN)` + `C00PacketLoginStart`。单机：`Minecraft.java:2330-2335` 走 `provideLocalClient(socketaddress)` 本地 channel，服务端侧由 `NetHandlerHandshakeMemory` 接。

LOGIN 阶段（远程）：`S01PacketEncryptionRequest` → joinServer 验证 → `C01PacketEncryptionResponse` → 启用加密；`S03PacketEnableCompression` 随时可到；`S02PacketLoginSuccess` → 切 PLAY、创建 `NetHandlerPlayClient`。此阶段收包由 `GuiConnecting.updateScreen` 的 `processReceivedPackets()`（GuiConnecting.java:111）或 `Minecraft.runTick` 的 `myNetworkManager.processReceivedPackets()`（Minecraft.java:2261，无世界时的 pendingConnection 分支）驱动。

PLAY 阶段每 tick：`Minecraft.runTick` → `PlayerControllerMP.updateController()`（PlayerControllerMP.java:348-359）：channel 开着则 `processReceivedPackets()`（NetworkManager.java:301：flush 出站队列 + `((ITickable)this.packetListener).update()` + `channel.flush()`），关了则 `checkDisconnected()` → 触发 `onDisconnect`。注意 `NetHandlerPlayClient` 本身不实现 `ITickable`，客户端侧 update 分支不生效，入站包不走队列。

进入世界序列：`S01PacketJoinGame`（建 world/controller，显示 `GuiDownloadTerrain`，发 MC|Brand 与客户端设置）→ 若干 `S21PacketChunkData`/`S26PacketMapChunkBulk` → 首个 `S08PacketPlayerPosLook`（`doneLoadingTerrain = true`，关加载屏，回发确认位置）。跨维度 `S07PacketRespawn` 重置 `doneLoadingTerrain` 并携带旧 `Scoreboard` 重建 `WorldClient`（L1063-1065）。

每帧：本包无每帧逻辑；`GuiPlayerTabOverlay` 渲染时每帧读 `playerInfoMap` 与 `NetworkPlayerInfo`。

线程归属：
- Netty EventLoop（"Netty Client IO #x"）：`channelRead0` 直接调 `handleXxx`；带 `checkThreadAndEnqueue` 的方法在此线程只做 reschedule。`NetHandlerLoginClient` 全部逻辑（含阻塞的 joinServer HTTP 调用）、`OldServerPinger` 的匿名 status handler、`handleKeepAlive`/`handleDisconnect`/`handleSetCompressionLevel`/`handlePlayerListHeaderFooter`/`handleResourcePack`（主体）都真正跑在这里。
- 客户端主线程：其余全部 `handleXxx` 的实际执行（经 `Minecraft.addScheduledTask` 重放）。
- 集成服务端线程：`NetHandlerHandshakeMemory.processHandshake`。
- 专用线程：`ThreadLanServerFind`（守护线程，阻塞在 UDP receive）。
- `SkinManager` 回调线程：填 `NetworkPlayerInfo.locationSkin/locationCape`。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void addToSendQueue(Packet p_147297_1_)` | NetHandlerPlayClient.java:814 | 客户端逻辑发任意 C 包时（EntityPlayerSP、PlayerControllerMP、GUI 全走这里） | 出站包拦截/改写/取消/记录（reach、velocity、chat 前置处理） | 少数代码直接调 `netManager.sendPacket`（如 L717 位置确认、L291 MC\|Brand、资源包状态回包），只钩这里覆盖不全 |
| `public void handleChat(S02PacketChat packetIn)` | NetHandlerPlayClient.java:849 | 收到聊天/action bar | 聊天过滤、命令回显解析、action bar 接管（type==2 分支） | 主线程执行；`getChatComponent()` 含格式化代码 |
| `public void handlePlayerPosLook(S08PacketPlayerPosLook packetIn)` | NetHandlerPlayClient.java:669 | 服务端强制设置玩家位置（传送/setback/上马/重生） | 检测反作弊回拉、Blink/回放类功能必须在此同步内部状态 | 会立即回发 C06 确认包（L717）；首个包关闭加载屏（L725），吞掉会卡在 GuiDownloadTerrain |
| `public void handleJoinGame(S01PacketJoinGame packetIn)` | NetHandlerPlayClient.java:277 | 登录完成进入世界 | 模块生命周期"世界加载"事件、修改 MC\|Brand（L291） | 此时 `thePlayer` 刚由 `loadWorld` 创建；顺序敏感 |
| `public void handleRespawn(S07PacketRespawn packetIn)` | NetHandlerPlayClient.java:1056 | 死亡重生/跨维度 | 重置与世界绑定的缓存（实体列表、路径点） | 仅维度变化才重建 WorldClient；`doneLoadingTerrain` 被重置 |
| `public void onDisconnect(IChatComponent reason)` | NetHandlerPlayClient.java:793 | 连接断开（任何原因） | 清理会话状态、自动重连入口 | 已 `loadWorld(null)`；区分 Realms/普通分支 |
| `public void handleSpawnPlayer(S0CPacketSpawnPlayer packetIn)` / `handleSpawnMob(S0FPacketSpawnMob packetIn)` / `handleSpawnObject(S0EPacketSpawnObject packetIn)` | NetHandlerPlayClient.java:530 / 913 / 297 | 实体进入视野 | 实体出现事件（ESP、目标缓存） | SpawnPlayer 依赖 `getPlayerInfo` 已有条目，否则 L538 NPE；SpawnObject 按魔法数字 type id 分派 |
| `public void handleDestroyEntities(S13PacketDestroyEntities packetIn)` | NetHandlerPlayClient.java:654 | 实体移出视野/死亡移除 | 目标失效清理 | — |
| `public void handleEntityVelocity(S12PacketEntityVelocity packetIn)` | NetHandlerPlayClient.java:501 | 服务端设置实体速度（击退、爆炸） | Velocity 类功能（修改/取消对 thePlayer 的击退） | 对自身实体也生效；速度单位 /8000 |
| `public void handleEntityMetadata(S1CPacketEntityMetadata packetIn)` / `handleEntityTeleport(...)` / `handleEntityMovement(S14PacketEntity packetIn)` | NetHandlerPlayClient.java:516 / 566 / 613 | 实体状态/位移更新 | 位置插值观察、反瞬移检测 | serverPosX/Y/Z 为 1/32 格定点数，Movement 为增量 |
| `public void handleUpdateHealth(S06PacketUpdateHealth packetIn)` | NetHandlerPlayClient.java:1042 | 血量/饥饿变更 | 受击检测、自动食用触发 | — |
| `public void handleOpenWindow(S2DPacketOpenWindow packetIn)` / `handleCloseWindow(S2EPacketCloseWindow packetIn)` | NetHandlerPlayClient.java:1092 / 1311 | 服务端开/关容器 GUI | 容器自动化（自动买卖、偷箱子）、静默处理 GUI | windowId 必须与后续 SetSlot/WindowItems 匹配 |
| `public void handleSetSlot(S2FPacketSetSlot packetIn)` / `handleWindowItems(S30PacketWindowItems packetIn)` | NetHandlerPlayClient.java:1133 / 1198 | 物品栏/容器内容同步 | 库存跟踪、物品变更事件 | 创造模式界面有 tab 特判（L1146-1150） |
| `public void handleConfirmTransaction(S32PacketConfirmTransaction packetIn)` | NetHandlerPlayClient.java:1174 | 容器事务校验 | 事务节奏观察（延迟测量） | 自动回 accepted=true，改动会导致服务端回滚点击 |
| `public void handleBlockChange(S23PacketBlockChange packetIn)` / `handleMultiBlockChange(...)` / `handleChunkData(S21PacketChunkData packetIn)` / `handleMapChunkBulk(...)` | NetHandlerPlayClient.java:776 / 734 / 747 / 1337 | 方块/区块同步 | 世界变更监听（矿物扫描、建筑记录） | ChunkData `func_149274_i()` 且 size==0 表示卸载区块（L753-757） |
| `public void handleKeepAlive(S00PacketKeepAlive packetIn)` | NetHandlerPlayClient.java:1663 | 服务端心跳 | 延迟测量、假延迟（延迟回包实现 lag switch） | **Netty 线程**执行，无主线程重放；阻塞会掉线 |
| `public void handleCustomPayload(S3FPacketCustomPayload packetIn)` | NetHandlerPlayClient.java:1822 | 插件通道消息 | 自定义通道协议（客户端-服务端模组通信） | `MC\|TrList` 分支负责 `packetbuffer.release()`，新增分支注意 buffer 生命周期 |
| `public void handlePlayerListItem(S38PacketPlayerListItem packetIn)` | NetHandlerPlayClient.java:1618 | Tab 列表增删改 | 玩家加入/离开事件（比实体 spawn 更早、范围更大） | REMOVE 只删 map，不动世界里的实体 |
| `public void handleTitle(S45PacketTitle packetIn)` / `handleChangeGameState(S2BPacketChangeGameState packetIn)` | NetHandlerPlayClient.java:1565 / 1358 | 标题显示 / 游戏状态（雨、gamemode、演示） | HUD 接管、状态事件 | ChangeGameState i==3 改本地 gamemode |
| `public void handleTabComplete(S3APacketTabComplete packetIn)` | NetHandlerPlayClient.java:1683 | 命令补全响应 | 命令系统集成 | 仅当 currentScreen 是 GuiChat 才生效 |
| `public void handleCamera(S43PacketCamera packetIn)` | NetHandlerPlayClient.java:1547 | 旁观者视角切换 | 视角接管检测 | `setRenderViewEntity` 影响渲染主体 |
| `public void handleResourcePack(S48PacketResourcePackSend packetIn)` | NetHandlerPlayClient.java:1701 | 服务端推送资源包 | 拦截/审计资源包 URL | Netty 线程；`level://` 分支未做路径穿越校验（见陷阱） |
| `public void handleLoginSuccess(S02PacketLoginSuccess packetIn)` | NetHandlerLoginClient.java:100 | LOGIN→PLAY 切换 | 替换/包装 NetHandlerPlayClient（整层代理的唯一注入点） | 必须先 `setConnectionState(PLAY)` 再 `setNetHandler` |
| `public void handleEncryptionRequest(S01PacketEncryptionRequest packetIn)` | NetHandlerLoginClient.java:45 | 远程服务器要求加密 | 会话验证代理、alt 账号处理 | Netty 线程同步 HTTP；LAN 分支验证失败不断开 |
| `public void ping(final ServerData server) throws UnknownHostException` | OldServerPinger.java:52 | 服务器列表条目可见时 | 自定义 ping 逻辑、批量查询 | handler 在 Netty 线程写 `ServerData` 字段，GUI 线程并发读 |
| `public ResourceLocation getLocationSkin()` | NetworkPlayerInfo.java:92 | 渲染玩家/头像时 | 皮肤替换（cape 系统） | 懒加载触发 SkinManager 异步下载 |

## 数据与协议

本包不定义封包格式（封包类在 `net.minecraft.network.*`），但持有两处协议级细节：

旧协议兼容 ping 请求（`tryCompatibilityPing`，OldServerPinger.java:210-232，手写字节流）：

| 字段 | 类型/写入方法 | 取值含义 |
|---|---|---|
| packet id | `bytebuf.writeByte(254)` | 0xFE 旧版 server list ping |
| payload | `bytebuf.writeByte(1)` | 1.4+ 扩展标记 |
| plugin msg id | `bytebuf.writeByte(250)` | 0xFA plugin message |
| channel | `writeShort(len)` + 逐 `writeChar` | 字符串 `"MC|PingHost"`（UTF-16） |
| 剩余长度 | `bytebuf.writeShort(7 + 2 * serveraddress.getIP().length())` | 后续负载字节数 |
| protocol | `bytebuf.writeByte(127)` | 假协议版本 |
| host | `writeShort(len)` + 逐 `writeChar` | 服务器地址 |
| port | `bytebuf.writeInt(serveraddress.getPort())` | 端口 |

旧协议响应（OldServerPinger.java:239-261）：首字节须为 `255`（0xFF kick），随后 `readShort()*2` 字节 UTF-16BE 字符串，按 `\u0000` 分割上限 6 段（`PING_RESPONSE_SPLITTER`，L48）：`[0]="§1"` 魔数、`[1]` 协议号、`[2]` 版本名、`[3]` MOTD、`[4]` 在线人数、`[5]` 上限；解析用 `MathHelper.parseIntWithDefault`。

现行 STATUS ping：握手协议号硬编码 `47`（1.8.x，OldServerPinger.java:176；C00Handshake 处 Minecraft.java:2333 亦为 47）。favicon 必须以 `"data:image/png;base64,"` 开头（L136）。

LAN 发现：UDP 组播组 `224.0.2.60`、端口 `4445`（LanServerDetector.java:111-112），报文由 `ThreadLanServerPing.getMotdFromPingResponse/getAdFromPingResponse` 解析（形如 `[MOTD]xxx[/MOTD][AD]port[/AD]`，具体格式在 multiplayer 包）。

自定义载荷通道：客户端发 `MC|Brand`（内容 `ClientBrandRetriever.getClientModName()`，L291）；收 `MC|TrList`（村民交易表，`MerchantRecipeList.readFromBuf`）、`MC|Brand`（`readStringFromBuffer(32767)`）、`MC|BOpen`（打开手持成书）。

NBT：`handleUpdateTileEntity`（L1267）按 type 1-6 白名单（MobSpawner/CommandBlock/Beacon/Skull/FlowerPot/Banner）调 `tileentity.readFromNBT(packetIn.getNbtCompound())`；`handleEntityNBT`（L1805）调 `entity.clientUpdateEntityNBT`。

## 不变量与陷阱

- **线程模型是最大陷阱**：`handleXxx` 首先在 Netty EventLoop 上被调用，`checkThreadAndEnqueue` 靠抛 `ThreadQuickExitException` 中止后在主线程重放。因此(1)在 handler 开头插入的 hook 代码会被执行**两次**（一次 Netty 线程、一次主线程），除非放在 `checkThreadAndEnqueue` 之后；(2)没有该调用的 handler（`handleKeepAlive` L1663、`handleDisconnect` L785、`handleSetCompressionLevel` L1592、`handlePlayerListHeaderFooter` L1600、`handleResourcePack` L1701 主体）全程在 Netty 线程执行，其中 `handlePlayerListHeaderFooter` 从 Netty 线程直接改 `ingameGUI.getTabList()`，与渲染线程存在数据竞争（原版遗留行为）。
- `playerInfoMap` 是普通 `HashMap`（L247），写入在主线程（handlePlayerListItem 有线程转移），但渲染线程经 `getPlayerInfoMap()` 遍历——依赖"写只发生在主线程 tick 内"这一原版约定，勿从其他线程写。
- `handleSpawnPlayer` 无条件 `this.getPlayerInfo(packetIn.getPlayer()).getGameProfile()`（L538）：服务端必须先发 ADD_PLAYER 的 S38 再发 S0C，违序即 NPE。
- `doneLoadingTerrain` 不变量：`handleJoinGame`/`handleRespawn`（跨维度）后必须等到一个 `S08PacketPlayerPosLook` 才会关闭 `GuiDownloadTerrain`；吞掉或改写该包会卡加载屏。
- 加密顺序不变量：`C01PacketEncryptionResponse` 必须明文发出，`enableEncryption` 在发送完成的 listener 里才调（NetHandlerLoginClient.java:86-92）。若改为同步启用会破坏协议。
- `handleResourcePack` 的 `level://` 分支直接 `new File(file1, s2)`（L1710）拼路径、无 `..` 穿越校验——这是原版 1.8 的已知安全隐患，移植时原样保留；恶意服务器可探测/加载 saves 目录外文件，功能层若暴露该路径应自行校验。
- `gameController` 字段非 final 且会在资源包确认回调里被重赋值（L1763），不要缓存假设其恒等于构造时传入的实例。
- `NetHandlerHandshakeMemory` 在 client 包但属于服务端逻辑（被 `NetworkSystem.java:147` 安装、运行在集成服务端），不要当客户端 handler 钩。
- `OldServerPinger` 的匿名 handler 在 Netty 线程直接写 `ServerData` 的公有字段（serverMOTD/pingToServer/populationInfo 等），GUI 线程并发读，无同步——原版即如此，新增字段勿照抄。
- `handleEntityProperties` 对非 `EntityLivingBase` 实体 `throw new IllegalStateException`（L2060），该异常会传播到包处理循环。
- 移植痕迹（与原版 1.8.9 反编译产物不同处）：Guava 升级——`MoreObjects.firstNonNull`（NetworkPlayerInfo.java:99，原版为 `Objects.firstNonNull`）；`Futures.addCallback(..., com.google.common.util.concurrent.MoreExecutors.directExecutor())` 显式传 executor（NetHandlerPlayClient.java:1725, 1747, 1783，原版两参重载已被新 Guava 移除）。行为等价，但打 patch/对拍原版源码时行号与调用形状不一致。
- JDK25 注意：`MulticastSocket.joinGroup(InetAddress)` / `leaveGroup(InetAddress)`（LanServerDetector.java:114, 146）自 Java 14 起为 deprecated API，当前仍可用；`Charsets`（Guava）与 `new String(bytes, Charsets.UTF_16BE)` 正常。Netty 已升 4.2.16（见仓库提交记录），`OldServerPinger.tryCompatibilityPing` 的裸 Bootstrap 与 `NetworkManager.CLIENT_NIO_EVENTLOOP` 共用 EventLoopGroup，协议行为已通过 golden test 验证。
- 本包与 LWJGL 无直接耦合，移植风险集中在 JDK/Netty/Guava 三处。

## 交叉引用

- `net.minecraft.network` → `NetworkManager#channelRead0`（入站分发）、`NetworkManager#sendPacket`、`NetworkManager#processReceivedPackets`、`NetworkManager#setNetHandler`、`NetworkManager#setConnectionState`、`NetworkManager#enableEncryption`、`NetworkManager#setCompressionTreshold`、`PacketThreadUtil#checkThreadAndEnqueue`、`NetworkSystem#addLocalEndpoint`（安装 NetHandlerHandshakeMemory）
- `net.minecraft.client` → `Minecraft#loadWorld`、`Minecraft#displayGuiScreen`、`Minecraft#addScheduledTask`、`Minecraft#getNetHandler`（返回 `thePlayer.sendQueue`，Minecraft.java:2468）、`Minecraft#getSessionService`、`Minecraft#getSkinManager`
- `net.minecraft.client.multiplayer` → `WorldClient`（构造与全部状态写入）、`PlayerControllerMP#updateController`（每 tick 驱动）、`GuiConnecting`（创建 NetHandlerLoginClient）、`ServerData`（pinger 写字段）、`ServerAddress#fromString`、`ServerList#func_147414_b`、`ThreadLanServerPing#getMotdFromPingResponse` / `#getAdFromPingResponse`
- `net.minecraft.client.entity` → `EntityPlayerSP.sendQueue`（字段即 NetHandlerPlayClient）、`EntityOtherPlayerMP`（handleSpawnPlayer 创建）、`AbstractClientPlayer`（经 getPlayerInfo 取皮肤）
- `net.minecraft.client.gui` → `GuiMultiplayer#updateScreen`（pingPendingNetworks、LAN 轮询）、`ServerListEntryNormal`（触发 ping）、`GuiPlayerTabOverlay`（消费 NetworkPlayerInfo 与 func_178835_l 系列）、`GuiChat#onAutocompleteResponse`、`GuiDownloadTerrain`、`GuiDisconnected`、`GuiIngame`（chat/title/tablist）
- `net.minecraft.client.resources` → `SkinManager#loadProfileTextures`、`DefaultPlayerSkin#getDefaultSkin` / `#getSkinType`
- `net.minecraft.server.network` → `NetHandlerLoginServer`（NetHandlerHandshakeMemory 移交）
- `net.minecraft.realms` → `RealmsConnect`（创建 NetHandlerLoginClient）、`DisconnectedRealmsScreen`
- `net.minecraft.util` → `CryptManager#createNewSharedKey` / `#getServerIdHash`
- `net.minecraft.scoreboard` → `Scoreboard`（objective/score/team 全套 handler 写入）

## 覆盖声明

完整读取了 6/6 个文件（2972/2972 行）。逐行精读：`NetHandlerPlayClient`、`NetHandlerLoginClient`、`NetworkPlayerInfo`、`OldServerPinger`、`LanServerDetector`、`NetHandlerHandshakeMemory`（全部）。另为核实调用关系结构性浏览了包外文件：`Minecraft.java`（2250-2270、2320-2340、2464-2472）、`PlayerControllerMP.java`（348-360）、`PacketThreadUtil.java`、`NetworkManager.java`（149-157、295-311）、`GuiMultiplayer.java`/`ServerListEntryNormal.java`/`GuiPlayerTabOverlay.java`/`WorldClient.java`/`EntityPlayerSP.java`/`GuiConnecting.java`/`RealmsConnect.java`/`NetworkSystem.java`（仅 grep 命中行）。未虚报。
