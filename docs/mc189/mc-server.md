---
area: net/minecraft/server
slug: mc-server
files: 7
lines: 2430
tier: B
---

# net/minecraft/server — 内置服务端核心

## 定位

这个包是客户端里"单人游戏其实是本地服务器"这一架构的核心。`MinecraftServer` 是抽象服务端主类（本仓库是纯客户端源码树，没有 `DedicatedServer`，唯一具体实现是 `IntegratedServer`）。进入单人世界时，`Minecraft#launchIntegratedServer`（`client/Minecraft.java:2271`）会 `new IntegratedServer(this, folderName, worldName, worldSettingsIn)` 并 `startServerThread()`（`Minecraft.java:2291-2292`），随后客户端通过 `this.theIntegratedServer.getNetworkSystem().addLocalEndpoint()`（`Minecraft.java:2330`）建立本地内存管道连接自己。

它调用的下游：`net.minecraft.world`（`WorldServer` 的加载/tick/保存）、`net.minecraft.network`（`NetworkSystem#networkTick`、各登录/状态封包）、`net.minecraft.server.management`（`ServerConfigurationManager`）、`net.minecraft.command`（`ServerCommandManager`）、com.mojang.authlib（会话校验）。调用它的上游：`Minecraft`（启动/暂停/关闭内置服务器）、`NetworkSystem`（收到新连接时挂 `NetHandlerHandshakeTCP`）、`GuiShareToLan`（`shareToLAN`）。

如果这个包消失：单人游戏、局域网开放、以及作为"服务端"接受连接的整条链路（握手 → 登录 → 进入 PLAY 状态）全部不可用；多人客户端连接远程服务器的路径不受影响。

## 类清单

| 类名 | 行数 | extends / implements | 一句话职责 |
|---|---|---|---|
| `MinecraftServer` | 1551 | `implements Runnable, ICommandSender, IThreadListener, IPlayerUsage` | 抽象服务端主体：主循环、tick 调度、世界加载/保存、任务队列、状态响应 |
| `integrated/IntegratedServer` | 447 | `extends MinecraftServer` | 单人/局域网内置服务端；与 `Minecraft` 实例双向耦合（暂停、难度、视距同步） |
| `integrated/IntegratedPlayerList` | 56 | `extends ServerConfigurationManager` | 内置服务端的玩家列表；把房主玩家 NBT 缓存下来供写入 level.dat |
| `integrated/IntegratedServerCommandManager` | 7 | `extends ServerCommandManager` | 空壳子类，无任何成员（原版此类有 sender 判断逻辑，本仓库为空体） |
| `network/NetHandlerHandshakeTCP` | 70 | `implements INetHandlerHandshakeServer` | 服务端握手处理：按 intention 切换到 LOGIN 或 STATUS 状态并换 handler |
| `network/NetHandlerLoginServer` | 248 | `implements INetHandlerLoginServer, ITickable` | 服务端登录状态机：加密协商、Yggdrasil 会话校验、压缩启用、接纳玩家 |
| `network/NetHandlerStatusServer` | 51 | `implements INetHandlerStatusServer` | 服务端状态查询：回复 MOTD/玩家数（S00PacketServerInfo）与 ping/pong |

## 核心类详解

### MinecraftServer（MinecraftServer.java）

关键字段：
- `private static MinecraftServer mcServer` — 全局单例，`public static MinecraftServer getServer()` 读取（`MinecraftServer.java:86, 968`）。构造器与 `setInstance()` 都会覆写它。
- `public WorldServer[] worldServers` — 固定 3 维度数组，索引 0=主世界、1=下界(dim -1)、2=末地(dim 1)（`MinecraftServer.java:103, 274, 306-336`）。
- `private ServerConfigurationManager serverConfigManager` — 玩家管理器（`MinecraftServer.java:106`）。
- `private final NetworkSystem networkSystem` — 网络端点（`MinecraftServer.java:95`）；注意单参构造器里为 `null`（`MinecraftServer.java:191`）。
- `protected final Queue<FutureTask<?>> futureTaskQueue` — 跨线程任务队列（`MinecraftServer.java:182`），以 `synchronized (this.futureTaskQueue)` 保护。
- `private Thread serverThread` — 由 `startServerThread()` 创建的 "Server thread"（`MinecraftServer.java:183, 819-823`）。
- `private int tickCounter` / `public final long[] tickTimeArray = new long[100]` — tick 计数与最近 100 tick 耗时（纳秒）（`MinecraftServer.java:117, 147`）。
- `private final ServerStatusResponse statusResponse` — 服务器列表 ping 的响应对象（`MinecraftServer.java:96`）。

关键方法（签名逐字复制）：
- `public void run()`（`MinecraftServer.java:527`）— 服务端线程入口。`startServer()` 成功后进入 50ms 固定步长循环；落后 >2000ms 打 "Can't keep up" 并丢 tick；全员睡觉时单独 tick 一次清零欠账（`:560-572`）。异常时写 crash-report 到 `crash-reports/crash-*-server.txt`（`:597`），finally 里 `serverStopped = true; stopServer(); systemExitNow()`（`:614-624`）。
- `protected abstract boolean startServer() throws IOException;`（`MinecraftServer.java:222`）
- `public void tick()`（`MinecraftServer.java:678`）— 每 tick：`updateTimeLightAndEntities()`；每 5 秒刷新 `statusResponse` 玩家样本（`:693-707`）；每 900 tick 存玩家数据并 `saveAllWorlds(true)`（`:709-715`）；snooper 统计。
- `public void updateTimeLightAndEntities()`（`MinecraftServer.java:736`）— 顺序：排空 `futureTaskQueue`（`:740-746`）→ 逐维度 `worldserver.tick()` + `worldserver.updateEntities()` + `getEntityTracker().updateTrackedEntities()`（`:750-798`，每 20 tick 广播 `S03PacketTimeUpdate`，`:759-764`）→ `this.getNetworkSystem().networkTick()`（`:801`）→ `this.serverConfigManager.onTick()`（`:803`）→ 遍历 `playersOnline` 调 `ITickable.update()`（`:806-809`）。
- `protected void loadAllWorlds(String saveName, String worldNameIn, long seed, WorldType type, String worldNameIn2)`（`MinecraftServer.java:270`）— 转换旧存档、创建 3 个 `WorldServer`、挂 `WorldManager` world access、`initialWorldChunkLoad()`。
- `protected void initialWorldChunkLoad()`（`MinecraftServer.java:351`）— 以出生点为中心预加载 ±192 格（25×25 chunk）。
- `public void stopServer()`（`MinecraftServer.java:472`）— `terminateEndpoints()` → 存玩家 → `saveAllWorlds(false)` → 每个世界 `flush()` → 停 snooper。
- `public void initiateShutdown()`（`MinecraftServer.java:517`）— 仅置 `serverRunning = false`，由 run 循环自然退出。
- `public <V> ListenableFuture<V> callFromMainThread(Callable<V> callable)`（`MinecraftServer.java:1506`）与 `public ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule)`（`:1533`）— 非服务端线程投递任务；若已在服务端线程则同步执行并返回 `immediateFuture`。
- `public boolean isCallingFromMinecraftThread()`（`MinecraftServer.java:1539`）— `Thread.currentThread() == this.serverThread`。
- `public int getNetworkCompressionTreshold()`（`MinecraftServer.java:1547`）— 返回 `256`，登录成功前用于 `S03PacketEnableCompression`。
- `public WorldServer worldServerForDimension(int dimension)`（`MinecraftServer.java:844`）— dim -1 → index 1，dim 1 → index 2，其余 → index 0。

### IntegratedServer（integrated/IntegratedServer.java）

关键字段：`private final Minecraft mc`、`private final WorldSettings theWorldSettings`、`private boolean isGamePaused`、`private boolean isPublic`、`private ThreadLanServerPing lanServerPing`（`IntegratedServer.java:38-42`）。

- `protected boolean startServer() throws IOException`（`IntegratedServer.java:136`）— 固定开启 onlineMode/spawnAnimals/spawnNPCs/pvp/allowFlight，`setKeyPair(CryptManager.generateKeyPair())`，`loadAllWorlds(...)`，MOTD 设为 `owner + " - " + worldName`。
- `public void tick()`（`IntegratedServer.java:154`）— 先读 `Minecraft.getMinecraft().isGamePaused()`；从"未暂停→暂停"边沿触发一次 存玩家 + `saveAllWorlds(false)`（`:159-164`）；暂停期间只排空 `futureTaskQueue` 不 tick 世界（`:166-175`）；未暂停时 `super.tick()` 并把客户端的 `renderDistanceChunks`、难度/难度锁同步进服务端（`:178-208`）。
- `protected void loadAllWorlds(String saveName, String worldNameIn, long seed, WorldType type, String worldNameIn2)`（`IntegratedServer.java:70`）— 覆写版：用 `this.theWorldSettings` 而不是重建 `WorldSettings`；不调用 `setUserMessage("menu.loadingLevel")`，也不按 `isSinglePlayer()` 覆盖 gameType。
- `public String shareToLAN(WorldSettings.GameType type, boolean allowCheats)`（`IntegratedServer.java:341`）— `HttpUtil.getSuitableLanPort()` 拿端口（失败退 25564），`this.getNetworkSystem().addLanEndpoint((InetAddress)null, i)`，起 `ThreadLanServerPing` 广播线程，`setGameType`/`setCommandsAllowedForAll`。
- `public void initiateShutdown()`（`IntegratedServer.java:393`）— 先 `Futures.getUnchecked(this.addScheduledTask(...))` 在服务端线程把所有玩家 `playerLoggedOut`，再 `super.initiateShutdown()`，并中断 LAN ping 线程。注意：从服务端线程自己调用时 `addScheduledTask` 会同步执行。
- `protected void finalTick(CrashReport report)`（`IntegratedServer.java:276`）— 服务端崩溃转交 `this.mc.crashed(report)`，即内置服务端崩溃会拉死整个客户端。
- `public void setStaticInstance()`（`IntegratedServer.java:414`）— 暴露 `setInstance()`；`Minecraft.java:2363` 在每次客户端 loop 里调它，保证静态 `mcServer` 指向当前内置服务端。

### IntegratedPlayerList（integrated/IntegratedPlayerList.java）

- 字段 `private NBTTagCompound hostPlayerData`（`IntegratedPlayerList.java:14`）。
- `protected void writePlayerData(EntityPlayerMP playerIn)`（`IntegratedPlayerList.java:25`）— 若是房主（`playerIn.getName().equals(this.getServerInstance().getServerOwner())`），先把玩家写入 `hostPlayerData` 再走父类逻辑；这份 NBT 之后由存档系统写进 level.dat 的 Player 标签。
- `public String allowUserToConnect(SocketAddress address, GameProfile profile)`（`IntegratedPlayerList.java:39`）— 拒绝与房主同名的二次登录（"That name is already taken."），否则走父类 ban/白名单/满员检查。
- 构造器里 `this.setViewDistance(10)`（`IntegratedPlayerList.java:19`），之后由 `IntegratedServer#tick` 每 tick 与 `gameSettings.renderDistanceChunks` 对齐。

### NetHandlerHandshakeTCP（network/NetHandlerHandshakeTCP.java）

- `public void processHandshake(C00Handshake packetIn)`（`NetHandlerHandshakeTCP.java:28`）— `LOGIN` intention：`setConnectionState(EnumConnectionState.LOGIN)`，协议号必须等于 47（1.8.9），`> 47` 回 "Outdated server! I'm still on 1.8.9"，`< 47` 回 "Outdated client! Please use 1.8.9"，相等则 `setNetHandler(new NetHandlerLoginServer(this.server, this.networkManager))`；`STATUS` intention：切 STATUS 并挂 `NetHandlerStatusServer`；其他抛 `UnsupportedOperationException`。
- 由 `NetworkSystem` 在 LAN socket 新连接的 pipeline 初始化时挂上（`network/NetworkSystem.java:127`）；本地内存通道走的是 `client/network/NetHandlerHandshakeMemory`（`NetworkSystem.java:153`），后者直接挂 `NetHandlerLoginServer`（`NetHandlerHandshakeMemory.java:29`），不做协议号检查。

### NetHandlerLoginServer（network/NetHandlerLoginServer.java）

状态机 `static enum LoginState { HELLO, KEY, AUTHENTICATING, READY_TO_ACCEPT, DELAY_ACCEPT, ACCEPTED; }`（`NetHandlerLoginServer.java:239-247`）。

关键字段：`private final byte[] verifyToken = new byte[4]`（构造时 `RANDOM.nextBytes` 填充，`:39, 55`）、`private GameProfile loginGameProfile`、`private SecretKey secretKey`、`private int connectionTimer`、`private EntityPlayerMP player`。

- `public void processLoginStart(C00PacketLoginStart packetIn)`（`NetHandlerLoginServer.java:156`）— 校验状态为 HELLO；在线模式且非本地通道 → 进 KEY 并发 `S01PacketEncryptionRequest(this.serverId, this.server.getKeyPair().getPublic(), this.verifyToken)`；否则直接 READY_TO_ACCEPT（内置服务端本地连接走这条路，房主不做加密和会话校验）。
- `public void processEncryptionResponse(C01PacketEncryptionResponse packetIn)`（`NetHandlerLoginServer.java:172`）— 校验 verifyToken（不匹配抛 `IllegalStateException("Invalid nonce!")`）、`networkManager.enableEncryption(this.secretKey)`，然后起匿名线程 `"User Authenticator #" + AUTHENTICATOR_THREAD_ID.incrementAndGet()` 调 `sessionService.hasJoinedServer(...)` 做 Yggdrasil 校验（`:186-229`）。校验失败但 `isSinglePlayer()`（局域网房）时降级为离线 profile 放行（`:202-207, 216-221`）。
- `public void update()`（`NetHandlerLoginServer.java:61`）— 每 tick 由 `NetworkManager#processReceivedPackets` 调（`network/NetworkManager.java:305-307` 判断 `packetListener instanceof ITickable`）。READY_TO_ACCEPT → `tryAcceptPlayer()`；DELAY_ACCEPT → 等旧同 UUID 玩家退出后再接入；`connectionTimer++ == 600`（30 秒）超时踢出 "Took too long to log in"。
- `public void tryAcceptPlayer()`（`NetHandlerLoginServer.java:100`）— `allowUserToConnect` 检查 → 状态置 ACCEPTED → 非本地通道先发 `S03PacketEnableCompression(this.server.getNetworkCompressionTreshold())` 并在 future 回调里 `setCompressionTreshold`（`:117-126`）→ 发 `S02PacketLoginSuccess(this.loginGameProfile)` → `initializeConnectionToPlayer(...)` 进 PLAY；若同 UUID 已在线则转 DELAY_ACCEPT。
- `protected GameProfile getOfflineProfile(GameProfile original)`（`NetHandlerLoginServer.java:233`）— UUID = `UUID.nameUUIDFromBytes(("OfflinePlayer:" + original.getName()).getBytes(Charsets.UTF_8))`。

### NetHandlerStatusServer（network/NetHandlerStatusServer.java）

- `public void processServerQuery(C00PacketServerQuery packetIn)`（`NetHandlerStatusServer.java:33`）— 首次回 `S00PacketServerInfo(this.server.getServerStatusResponse())`；重复查询直接关通道（`handled` 标志，1.8.9 的反 ping-flood 修复）。
- `public void processPing(C01PacketPing packetIn)`（`NetHandlerStatusServer.java:46`）— 原样回 `S01PacketPong(packetIn.getClientTime())` 后关通道。

## 时序与生命周期

启动（客户端主线程）：`Minecraft#launchIntegratedServer`（`Minecraft.java:2271`）→ `new IntegratedServer(...)`（`:2291`，构造器里设置 owner/folderName/worldName/`setConfigManager(new IntegratedPlayerList(this))`）→ `startServerThread()`（`:2292`，创建 "Server thread"）。主线程随后轮询 `getUserMessage()` 显示加载进度，最后 `addLocalEndpoint()`（`:2330`）建立内存通道。

服务端线程内：`run()`（`MinecraftServer.java:527`）→ `startServer()`（`IntegratedServer.java:136`：keypair → `loadAllWorlds` → `initialWorldChunkLoad`）→ 50ms/tick 主循环。

每 tick（服务端线程，`IntegratedServer#tick` → `MinecraftServer#tick` → `updateTimeLightAndEntities`）：
1. 暂停检查（仅 integrated；暂停时只排任务队列）
2. 排空 `futureTaskQueue`
3. 3 个维度依次 `worldserver.tick()` / `updateEntities()` / entity tracker
4. `NetworkSystem#networkTick()` — 在服务端线程排干每个连接的收包队列，`NetHandlerLoginServer.update()` 也在此被驱动
5. `serverConfigManager.onTick()`
6. 每 5s 刷 status 玩家样本；每 900 tick 自动保存；每 6000 tick snooper 内存统计

每帧：无（本包不参与渲染；`Minecraft.java:2363` 每个客户端 loop 调 `setStaticInstance()` 刷新静态单例）。

线程归属：
- 世界 tick、任务队列、玩家管理 — "Server thread"（`MinecraftServer.java:821`）
- 封包解码与 handler 首次挂载 — Netty EventLoop；但 handshake/login/status 的 `processXxx` 实际在 `networkTick` 排干队列时于服务端线程执行（本地内存通道除外的收包会先入队）
- Yggdrasil 会话校验 — 临时线程 "User Authenticator #N"（`NetHandlerLoginServer.java:186`）
- LAN 广播 — `ThreadLanServerPing`（`IntegratedServer.java:42`）
- 客户端主线程只通过 `addScheduledTask` / `callFromMainThread` 与服务端线程交互

关闭：`initiateShutdown()` 置 `serverRunning=false` → run 循环退出 → finally 里 `serverStopped=true; stopServer()`（存档、断网络、停 snooper）→ `systemExitNow()`（integrated 下为空实现）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void tick()` | MinecraftServer.java:678 | 服务端线程每 50ms | 服务端侧每 tick 逻辑总入口；测 TPS（`tickTimeArray`）；注入全局逻辑 | 在服务端线程，别碰客户端渲染状态 |
| `public void updateTimeLightAndEntities()` | MinecraftServer.java:736 | 每 tick 由 `tick()` 调 | 在世界 tick 前后插逻辑；观察任务队列排空时机 | 抛异常会带崩整个服务端（ReportedException） |
| `public void tick()`（覆写版） | IntegratedServer.java:154 | 每 tick，先于 super.tick | 感知/篡改暂停行为、视距与难度同步 | 暂停判断读 `Minecraft.getMinecraft()`，跨线程读客户端字段 |
| `public void run()` | MinecraftServer.java:527 | 服务端线程入口 | 接管主循环节奏（tick 速率、追帧策略） | 改 50L 常量即改 TPS；finally 链保证存档，别短路 |
| `public ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule)` | MinecraftServer.java:1533 | 任意线程 | 向服务端线程投递任务的标准入口 | 已在服务端线程则同步执行；服务端停了会直接执行而非入队 |
| `public <V> ListenableFuture<V> callFromMainThread(Callable<V> callable)` | MinecraftServer.java:1506 | 任意线程 | 同上，带返回值 | 同上 |
| `protected boolean startServer() throws IOException` | IntegratedServer.java:136 | 服务端线程启动时一次 | 世界加载前后做初始化；改默认 flags（pvp/flight 等） | 返回 false 走 `finalTick(null)` 直接崩 |
| `protected void loadAllWorlds(String saveName, String worldNameIn, long seed, WorldType type, String worldNameIn2)` | MinecraftServer.java:270 / IntegratedServer.java:70 | startServer 期间 | 拦截世界创建、换 WorldServer 实现、挂额外 IWorldAccess | integrated 覆写版行为与基类不同（见详解） |
| `public void stopServer()` | MinecraftServer.java:472 / IntegratedServer.java:379 | 关服时（run 的 finally） | 存档完成前后的清理钩子 | 可能被调用多次路径（deleteWorld 时 `worldIsBeingDeleted` 短路） |
| `public void initiateShutdown()` | MinecraftServer.java:517 / IntegratedServer.java:393 | 退出世界 / 删除世界 | 感知"开始关服"；integrated 版会先踢出所有玩家 | integrated 版内含 `Futures.getUnchecked` 阻塞等待 |
| `public void processHandshake(C00Handshake packetIn)` | NetHandlerHandshakeTCP.java:28 | LAN 连接第一包 | 版本门禁、自定义握手、伪装协议号 | 本地内存通道不走这里（走 NetHandlerHandshakeMemory） |
| `public void processLoginStart(C00PacketLoginStart packetIn)` | NetHandlerLoginServer.java:156 | 登录第一包 | 改名/白名单前置检查、跳过加密 | 状态机校验 `Validate.validState`，顺序错会断线 |
| `public void processEncryptionResponse(C01PacketEncryptionResponse packetIn)` | NetHandlerLoginServer.java:172 | 在线模式登录第二包 | 接管会话校验（如自建认证） | 校验逻辑在匿名子线程；`loginGameProfile` 被两个线程读写 |
| `public void tryAcceptPlayer()` | NetHandlerLoginServer.java:100 | update() 里状态 READY_TO_ACCEPT 时 | 登录最后关卡：压缩阈值、拒绝逻辑、玩家实体创建前 | 这里之后就进 PLAY，是登录侧最后的可拦截点 |
| `public void update()` | NetHandlerLoginServer.java:61 | 每 tick（NetworkManager.java:305-307） | 改登录超时（600 tick）、轮询外部校验结果 | `connectionTimer++ == 600` 是精确相等判断 |
| `public void processServerQuery(C00PacketServerQuery packetIn)` | NetHandlerStatusServer.java:33 | 服务器列表 ping | 伪造/定制 MOTD、玩家数、favicon | 二次查询即断线（`handled` 标志） |
| `public String shareToLAN(WorldSettings.GameType type, boolean allowCheats)` | IntegratedServer.java:341 | GUI"对局域网开放" | 换端口策略、加鉴权、控制 cheats | 失败静默返回 null；端口探测失败硬编码退 25564 |
| `public String allowUserToConnect(SocketAddress address, GameProfile profile)` | IntegratedPlayerList.java:39 | tryAcceptPlayer 内 | LAN 连接准入控制（自定义白名单） | 返回非 null 字符串即踢出理由 |
| `public static MinecraftServer getServer()` | MinecraftServer.java:968 | 任意代码 | 拿服务端单例做观察 | 静态可变单例；多次开关世界后指向最新实例 |
| `public void setDifficultyForAllWorlds(EnumDifficulty difficulty)` | MinecraftServer.java:1061 / IntegratedServer.java:314 | 加载世界、客户端改难度 | 难度联动逻辑 | integrated 版会回写 `mc.theWorld` 的 WorldInfo |
| `public int getNetworkCompressionTreshold()` | MinecraftServer.java:1547 | 登录接纳时 | 调压缩阈值（返回负数=关压缩） | 与 Netty 压缩编解码链一致性要求高（见仓库压缩 framing 测试） |

## 数据与协议

本包不定义封包类，但驱动 HANDSHAKING/STATUS/LOGIN 三个协议阶段的服务端侧：

| 封包 | 方向 | 处理/发送方法 | 含义 |
|---|---|---|---|
| `C00Handshake` | C→S | `NetHandlerHandshakeTCP#processHandshake`（:28） | `getRequestedState()`：LOGIN/STATUS；`getProtocolVersion()` 必须 == 47 |
| `C00PacketServerQuery` | C→S | `NetHandlerStatusServer#processServerQuery`（:33） | 请求服务器信息 |
| `S00PacketServerInfo` | S→C | 同上（:42） | 携带 `ServerStatusResponse`（MOTD、协议 "1.8.9"/47、玩家样本、favicon base64 data-URI，见 `MinecraftServer.java:535-537, 628-654`） |
| `C01PacketPing` / `S01PacketPong` | C↔S | `processPing`（:46-50） | 回显 `getClientTime()` 测延迟 |
| `C00PacketLoginStart` | C→S | `processLoginStart`（:156） | 携带 `GameProfile`（仅名字） |
| `S01PacketEncryptionRequest` | S→C | 同上（:164） | serverId(空串) + RSA 公钥 + 4 字节 verifyToken |
| `C01PacketEncryptionResponse` | C→S | `processEncryptionResponse`（:172） | RSA 加密的 AES secretKey + verifyToken 回显 |
| `S03PacketEnableCompression` | S→C | `tryAcceptPlayer`（:119） | 阈值 256（`getNetworkCompressionTreshold`），仅非本地通道 |
| `S02PacketLoginSuccess` | S→C | `tryAcceptPlayer`（:128) | 登录完成，进入 PLAY |
| `S00PacketDisconnect`（login 版） | S→C | handshake/login 各拒绝路径 | 携带 ChatComponent 断线原因 |
| `S03PacketTimeUpdate` | S→C | `updateTimeLightAndEntities`（MinecraftServer.java:762） | 每 20 tick 按维度广播世界时间 + doDaylightCycle |

NBT：`IntegratedPlayerList.hostPlayerData`（`NBTTagCompound`，`writePlayerData` 写入，`getHostPlayerData()` 读出）— 房主玩家数据进 level.dat 的通道。文件格式：`server-icon.png` 必须 64×64（`MinecraftServer.java:639-640`）；`usercache.json`（`USER_CACHE_FILE`，`:83`）；世界目录下 `resources.zip` 会被注册为 `level://` 资源包（`:385-393`）。

## 不变量与陷阱

- `worldServers` 长度恒为 3，index↔dimension 映射硬编码（0→0, 1→-1, 2→1）；`worldServerForDimension` 与 `loadAllWorlds` 两处必须一致。
- 协议号 47 在 `NetHandlerHandshakeTCP.java:35,41` 与 `MinecraftServer.java:536` 两处硬编码；改协议要同时改。
- 所有世界/实体/玩家状态只能在服务端线程改。跨线程必须走 `addScheduledTask` / `callFromMainThread`；注意其"已在目标线程则同步执行、服务端已停也同步执行"的双重语义（`MinecraftServer.java:1510`）。
- `IntegratedServer#tick` 与 `getDifficulty()`（`IntegratedServer.java:225-228`）直接从服务端线程读 `Minecraft.getMinecraft()` / `this.mc.theWorld` —— 原版遗留的数据竞争，`mc.theWorld` 在退出世界瞬间可能为 null，勿在此基础上加更多跨线程读取。
- 静态单例 `mcServer` 永不清空；退出世界后 `getServer()` 仍返回已停止的实例，用前查 `isServerStopped()` / `isServerRunning()`。`Minecraft` 每 loop 调 `setStaticInstance()` 兜底。
- `NetHandlerLoginServer` 的 `loginGameProfile` / `currentLoginState` 被服务端线程（update）与 authenticator 线程并发读写，无 volatile/锁——移植到 JDK 25 后内存模型未变但仍是脆弱点，别在这两个字段上加复杂逻辑。
- 登录超时判断是 `this.connectionTimer++ == 600` 精确相等（`NetHandlerLoginServer.java:79`），外部若改过 timer 会永不超时。
- 本地内存通道（房主）跳过：协议号检查（走 `NetHandlerHandshakeMemory`）、加密（`isLocalChannel()`，`NetHandlerLoginServer.java:161`）、压缩（`:117`）。测试网络相关功能必须走 LAN 路径才能覆盖完整链路。
- `initiateShutdown()`（integrated 版）如果从非服务端线程调且服务端线程已卡死，`Futures.getUnchecked` 会永久阻塞调用线程。
- `MinecraftServer` 单参构造器（`:186`）创建的是"残废"实例（`networkSystem`/`commandManager`/`anvilConverterForAnvilFile` 全 null），只用于 `Minecraft.java:388` 早期占位；对其调 `startServer` 相关路径会 NPE。
- crash 处理：世界 tick 抛出的任何 Throwable 都会包成 `ReportedException` 直接终结服务端（`MinecraftServer.java:768-788`），integrated 下经 `finalTick` → `mc.crashed` 拉死客户端。
- 快照/演示模式：`isDemo()` 为 true 时世界一律用 `DemoWorldServer.demoWorldSettings`，用户设置被忽略。
- LWJGL3/JDK25 移植：本包无直接 LWJGL 依赖，7 个文件与原版 MCP 1.8.9 内容一致（`IntegratedServerCommandManager` 为空体除外）；间接注意点是 Netty 4.2.16 升级（见仓库提交历史），压缩阈值 256 与 framing 行为有 golden test 保护。`java.awt.GraphicsEnvironment.isHeadless()`（`MinecraftServer.java:1204`）在 macOS + JDK25 下可能触发 AWT 初始化，仅 snooper 统计用。

## 交叉引用

- net.minecraft.client → `Minecraft#launchIntegratedServer` / `Minecraft#getIntegratedServer` / `Minecraft#isGamePaused`（IntegratedServer 双向耦合核心）
- net.minecraft.network → `NetworkSystem#networkTick`（MinecraftServer.java:801 每 tick 调）、`NetworkSystem#addLanEndpoint` / `#addLocalEndpoint`、`NetworkManager#processReceivedPackets`（驱动 `NetHandlerLoginServer#update`，NetworkManager.java:305-307）
- net.minecraft.client.network → `NetHandlerHandshakeMemory`（本地通道版握手，直接 new `NetHandlerLoginServer`）
- net.minecraft.server.management → `ServerConfigurationManager#initializeConnectionToPlayer` / `#allowUserToConnect` / `#onTick` / `#playerLoggedOut`、`PlayerProfileCache`
- net.minecraft.world → `WorldServer#tick` / `#updateEntities` / `#saveAllChunks` / `#flush`、`WorldManager`（world access）、`AnvilSaveConverter`
- net.minecraft.command → `ServerCommandManager`（`createNewCommandManager`）、`CommandBase#doesStringStartWith`（tab 补全）
- net.minecraft.util → `CryptManager#generateKeyPair` / `#getServerIdHash`、`HttpUtil#getSuitableLanPort`、`Util#runTask`
- net.minecraft.client.multiplayer → `ThreadLanServerPing`（LAN 广播）
- net.minecraft.profiler → `Profiler`、`PlayerUsageSnooper`（IPlayerUsage 实现）
- com.mojang.authlib → `YggdrasilAuthenticationService` / `MinecraftSessionService#hasJoinedServer`（登录会话校验）

## 覆盖声明

完整读取了 7/7 个文件。逐行精读：`MinecraftServer`、`IntegratedServer`、`NetHandlerLoginServer`、`NetHandlerHandshakeTCP`、`NetHandlerStatusServer`、`IntegratedPlayerList`、`IntegratedServerCommandManager`（全部；无仅结构性浏览的文件）。另外为核实调用关系，节选查阅了包外文件 `client/Minecraft.java`、`network/NetworkSystem.java`、`network/NetworkManager.java`、`client/network/NetHandlerHandshakeMemory.java` 的相关行。
