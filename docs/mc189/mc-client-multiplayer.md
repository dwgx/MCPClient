---
area: net/minecraft/client/multiplayer
slug: mc-client-multiplayer
files: 8
lines: 2011
tier: A
---

# net/minecraft/client/multiplayer

## 定位

这个包是客户端"多人游戏侧"的核心胶水层，覆盖三块职责：

1. **连接建立**：`GuiConnecting` 负责从服务器列表/命令行发起 TCP 连接，完成 handshake + login start；`ServerAddress` 负责地址解析（含 SRV 记录查询），`ServerData` / `ServerList` 负责 `servers.dat` 的读写与服务器条目模型。
2. **远程世界镜像**：`WorldClient`（`World` 的客户端子类）持有服务端同步过来的世界状态，`ChunkProviderClient` 是它的纯内存 chunk 缓存——不生成、不保存，只装包里来的数据。
3. **玩家动作 → 封包**：`PlayerControllerMP` 把本地输入（挖掘、放置、攻击、容器点击）翻译成 `C0x` 系列 serverbound 封包，同时在客户端做预测性模拟（本地先破坏方块、先扣耐久）。

调用方向：上游主要是 `Minecraft`（tick / 输入分发）和 `net.minecraft.client.network.NetHandlerPlayClient`（收包后写入 `WorldClient`）；下游调用 `net.minecraft.network.NetworkManager`（发包）、`net.minecraft.world.World` 体系、GUI 体系。`ThreadLanServerPing` 是个例外——它被服务端侧的 `IntegratedServer` 使用，做 LAN 广播。

如果这个包消失：客户端无法连接任何服务器（包括 LAN 与集成服务端的"对局内世界"——`NetHandlerPlayClient.handleJoinGame` 也是通过 `new WorldClient` / `new PlayerControllerMP` 建立会话的），玩家的一切世界交互动作都无法发送到服务端。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| `ChunkProviderClient` | 177 | `implements IChunkProvider` | 客户端 chunk 内存缓存（`LongHashMap<Chunk>`），按封包指令装载/卸载，缺失时返回 `EmptyChunk` |
| `GuiConnecting` | 174 | `extends GuiScreen` | 连接进度界面；在 "Server Connector" 线程里建立 `NetworkManager` 并发送 handshake/login 包 |
| `PlayerControllerMP` | 637 | （无） | 把本地玩家动作转成 serverbound 封包并做客户端预测（挖掘进度、创造模式等） |
| `ServerAddress` | 115 | （无） | 解析 "host:port" 字符串，支持 IPv6 方括号写法与 `_minecraft._tcp` SRV 记录 |
| `ServerData` | 160 | （无） | 单个服务器条目模型（名称/IP/MOTD/ping/图标/资源包策略），NBT 双向序列化 |
| `ServerList` | 147 | （无） | `servers.dat` 的加载/保存与增删改查（`CompressedStreamTools` 压缩 NBT） |
| `ThreadLanServerPing` | 119 | `extends Thread` | 守护线程，每 1.5s 向 `224.0.2.60:4445` 组播 LAN 服务器 MOTD+地址 |
| `WorldClient` | 482 | `extends World` | 服务端世界的客户端镜像：实体 ID 映射、chunk 装卸、时间/声音/粒子，禁用存档与天气模拟 |

## 核心类详解

### WorldClient（WorldClient.java）

关键字段（`WorldClient.java:41-48`）：

- `private NetHandlerPlayClient sendQueue` — 发包出口，`sendQuittingDisconnectingPacket()` 用它关 channel。
- `private ChunkProviderClient clientChunkProvider` — 在 `createChunkProvider()`（`WorldClient.java:114`）中创建并同时赋给父类 `chunkProvider`。
- `private final Set<Entity> entityList` / `private final Set<Entity> entitySpawnQueue` — "强制实体"集合与重试生成队列：实体所在 chunk 尚未到达时先进队列，每 tick 重试至多 10 个。
- `private final Minecraft mc = Minecraft.getMinecraft()` — 直接静态取单例（`WorldClient.java:47`）。
- `private final Set<ChunkCoordIntPair> previousActiveChunkSet` — `updateBlocks()` 里限流 mood sound/光照检查用。

关键方法：

- `public WorldClient(NetHandlerPlayClient netHandler, WorldSettings settings, int dimension, EnumDifficulty difficulty, Profiler profilerIn)`（`WorldClient.java:50`）— 用 `SaveHandlerMP`（空存档处理器）+ `WorldInfo(settings, "MpServer")` 构造；由 `NetHandlerPlayClient` 在 `handleJoinGame`（NetHandlerPlayClient.java:281）和 `handleRespawn`（NetHandlerPlayClient.java:1064）创建。
- `public void tick()`（`WorldClient.java:66`）— 每 tick：`super.tick()`；`setTotalWorldTime(+1)`；若 gamerule `doDaylightCycle` 为 true 则 `setWorldTime(+1)`（本地推时间，服务端定期用 S03 校正）；从 `entitySpawnQueue` 重试至多 10 个实体；`clientChunkProvider.unloadQueuedChunks()`；`updateBlocks()`。调用方：`Minecraft.runTick`（Minecraft.java:2224）。
- `public void doPreChunk(int chuncX, int chuncZ, boolean loadChunk)`（`WorldClient.java:153`）— 装载或卸载 chunk；卸载时 `markBlockRangeForRenderUpdate` 整列 0..256。调用方：`NetHandlerPlayClient.handleChunkData`（:755/:759）与 `handleMapChunkBulk`（:1345）。
- `public boolean spawnEntityInWorld(Entity entityIn)`（`WorldClient.java:173`）— 失败（chunk 未加载）则入 `entitySpawnQueue`；`EntityMinecart` 生成时挂 `MovingSoundMinecart`。
- `public void addEntityToWorld(int entityID, Entity entityToSpawn)`（`WorldClient.java:234`）— 服务端实体 ID 映射入口：同 ID 旧实体先移除，`entitiesById.addKey(entityID, entityToSpawn)`。调用方：`NetHandlerPlayClient` 的各类 spawn 包处理（:425、:457、:495、:554、:943）。
- `public Entity getEntityByID(int id)`（`WorldClient.java:257`）— 特判：ID 等于本地玩家时直接返回 `this.mc.thePlayer`（respawn 后服务端 ID 表可能未含本地玩家）。
- `public Entity removeEntityFromWorld(int entityID)`（`WorldClient.java:262`）— S13 destroy entities 的处理入口（NetHandlerPlayClient.java:660）。
- `public boolean invalidateRegionAndSetBlock(BlockPos pos, IBlockState state)`（`WorldClient.java:275`）— 封包驱动的方块写入（S23/S22），内部调 `super.setBlockState(pos, state, 3)`。注意 `invalidateBlockReceiveRegion`（`WorldClient.java:107`）在本版本是空方法。
- `public void sendQuittingDisconnectingPacket()`（`WorldClient.java:287`）— `closeChannel(new ChatComponentText("Quitting"))`；由 `GuiIngameMenu`（:56）、`GuiGameOver`（:91）调用。
- `protected void updateWeather()`（`WorldClient.java:295`）— 空覆写：客户端天气完全由封包驱动，不本地模拟。
- `public void setWorldTime(long time)`（`WorldClient.java:468`）— 负数时间是协议约定：取绝对值并把 `doDaylightCycle` 置 false。
- `public void removeAllEntities()`（`WorldClient.java:331`）— respawn/换维度时清理 unloaded/dead 实体并解除骑乘关系。
- `public void playSound(double x, double y, double z, String soundName, float volume, float pitch, boolean distanceDelay)`（`WorldClient.java:439`）— 距离平方 >100 时按 `sqrt(d0)/40*20` tick 延迟播放（模拟音速）。
- `public void doVoidFogParticles(int posX, int posY, int posZ)`（`WorldClient.java:304`）— 每帧驱动 1000 次 `randomDisplayTick`，并为创造模式手持 barrier 的玩家显示屏障粒子。
- `public CrashReportCategory addWorldInfoToCrashReport(CrashReport report)`（`WorldClient.java:388`）— 崩溃报告加 "Forced entities" / "Retry entities" / "Server brand" / "Server type" 四段。

### PlayerControllerMP（PlayerControllerMP.java）

关键字段（`PlayerControllerMP.java:35-62`）：

- `private final Minecraft mc` / `private final NetHandlerPlayClient netClientHandler` — 全部封包经 `netClientHandler.addToSendQueue(...)` 发出。
- `private BlockPos currentBlock = new BlockPos(-1, -1, -1)` — 正在挖掘的方块。
- `private ItemStack currentItemHittingBlock` / `private float curBlockDamageMP` / `private float stepSoundTickCounter` / `private int blockHitDelay` / `private boolean isHittingBlock` — 客户端挖掘进度状态机。
- `private WorldSettings.GameType currentGameType = WorldSettings.GameType.SURVIVAL` — 由 S01/S07/S2B 经 `setGameType` 更新。
- `private int currentPlayerItem` — 上次同步给服务端的快捷栏索引。

关键方法：

- `public boolean clickBlock(BlockPos loc, EnumFacing face)`（`PlayerControllerMP.java:198`）— 左键按下：adventure 权限检查、world border 检查；创造模式发 `START_DESTROY_BLOCK` 后直接 `clickBlockCreative`；生存模式若切换目标先发 `ABORT_DESTROY_BLOCK` 再发 `START_DESTROY_BLOCK`，硬度 >=1.0F 即秒破，否则进入挖掘状态机。调用方：`Minecraft.clickMouse`（Minecraft.java:1545）。
- `public boolean onPlayerDamageBlock(BlockPos posBlock, EnumFacing directionFacing)`（`PlayerControllerMP.java:285`）— 左键持续按住时每 tick 调用（Minecraft.java:1504）：累加 `curBlockDamageMP`，每 4 tick 播 step sound，满 1.0F 发 `STOP_DESTROY_BLOCK` 并本地破坏，之后 `blockHitDelay = 5`。
- `public boolean onPlayerDestroyBlock(BlockPos pos, EnumFacing side)`（`PlayerControllerMP.java:123`）— 客户端预测性破坏：`world.playAuxSFX(2001, ...)` + `world.setBlockToAir(pos)` + 工具耐久 `itemstack1.onBlockDestroyed(...)`。创造模式手持 `ItemSword` 直接返回 false（不可破坏）。
- `public void resetBlockRemoving()`（`PlayerControllerMP.java:274`）— 松开左键时发 `ABORT_DESTROY_BLOCK` 并清挖掘进度（Minecraft.java:1512）。
- `public boolean onPlayerRightClick(EntityPlayerSP player, WorldClient worldIn, ItemStack heldStack, BlockPos hitPos, EnumFacing side, Vec3 hitVec)`（`PlayerControllerMP.java:390`）— 右键方块：先试 `onBlockActivated`（非潜行或空手），**无论结果都发** `C08PacketPlayerBlockPlacement`（带 8 位 offset f/f1/f2），未激活则本地 `onItemUse`；创造模式放置后恢复 metadata 和 stackSize。调用方：Minecraft.java:1600。
- `public boolean sendUseItem(EntityPlayer playerIn, World worldIn, ItemStack itemStackIn)`（`PlayerControllerMP.java:456`）— 右键空气/使用物品：发 `C08PacketPlayerBlockPlacement(playerIn.inventory.getCurrentItem())`（无坐标形式）+ 本地 `useItemRightClick`。调用方：Minecraft.java:1627。
- `public void attackEntity(EntityPlayer playerIn, Entity targetEntity)`（`PlayerControllerMP.java:495`）— 发 `C02PacketUseEntity(..., Action.ATTACK)`，非旁观模式再本地 `attackTargetEntityWithCurrentItem`。调用方：Minecraft.java:1537。
- `public boolean interactWithEntitySendPacket(EntityPlayer playerIn, Entity targetEntity)`（`PlayerControllerMP.java:509`）与 `public boolean isPlayerRightClickingOnEntity(EntityPlayer player, Entity entityIn, MovingObjectPosition movingObject)`（`PlayerControllerMP.java:523`）— 实体交互两种形式：`Action.INTERACT` 与带命中相对坐标 `Vec3` 的 `INTERACT_AT`（Minecraft.java:1582/1586）。
- `public void updateController()`（`PlayerControllerMP.java:349`）— **每 tick 的收包泵**：`syncCurrentPlayItem()`；channel open 则 `processReceivedPackets()`，否则 `checkDisconnected()`。调用方：`Minecraft.runTick` 的 "gameMode" profiler 段（Minecraft.java:1756），非暂停且 `theWorld != null` 时执行。
- `private void syncCurrentPlayItem()`（`PlayerControllerMP.java:379`）— 快捷栏索引变化时发 `C09PacketHeldItemChange`；几乎所有动作方法开头都会调用它。
- `public ItemStack windowClick(int windowId, int slotId, int mouseButtonClicked, int mode, EntityPlayer playerIn)`（`PlayerControllerMP.java:534`）— 容器点击：本地 `slotClick` 预测 + `C0EPacketClickWindow`（带事务 ID `short1`）。调用方：`GuiContainer.handleMouseClick`（GuiContainer.java:685）。
- `public void sendSlotPacket(ItemStack itemStackIn, int slotId)`（`PlayerControllerMP.java:557`）/ `public void sendPacketDropItem(ItemStack itemStackIn)`（`PlayerControllerMP.java:568`）— 创造模式库存直写/丢弃（`C10PacketCreativeInventoryAction`，slotId=-1 表示丢地上）。
- `public void sendEnchantPacket(int windowID, int button)`（`PlayerControllerMP.java:549`）— `C11PacketEnchantItem`，由 `GuiEnchantment`（:98）调用。
- `public void onStoppedUsingItem(EntityPlayer playerIn)`（`PlayerControllerMP.java:576`）— 松开右键（弓等）：`C07PacketPlayerDigging(Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN)`。调用方：Minecraft.java:2122。
- `public void setGameType(WorldSettings.GameType type)`（`PlayerControllerMP.java:101`）— 同时重配玩家 capabilities；由 `NetHandlerPlayClient`（:289、:1072、:1383）调用。
- `public float getBlockReachDistance()`（`PlayerControllerMP.java:344`）— 创造 5.0F / 其它 4.5F（注释里写 4F 是错的，以代码为准）。
- `public EntityPlayerSP func_178892_a(World worldIn, StatFileWriter statWriter)`（`PlayerControllerMP.java:487`）— 本地玩家工厂；`Minecraft.loadWorld`（:2405）与 respawn（:2441）用它创建 `EntityPlayerSP`。
- 状态查询组：`isSpectator()`（:91）、`shouldDrawHUD()`（:115）、`gameIsSurvivalOrAdventure()`（:583）、`isNotCreative()`（:591）、`isInCreativeMode()`（:599）、`extendedReach()`（:607）、`isRidingHorse()`（:615）、`isSpectatorMode()`（:620）、`getCurrentGameType()`（:625）、`getIsHittingBlock()`（:633）。

### GuiConnecting（GuiConnecting.java）

- 两个构造器（`GuiConnecting.java:30` / `:40`）：`ServerData` 版会 `ServerAddress.fromString(p_i1181_3_.serverIP)` + `mcIn.setServerData(...)`；两者都先 `mcIn.loadWorld((WorldClient)null)` 清掉当前世界。
- `private void connect(final String ip, final int port)`（`GuiConnecting.java:48`）— 起一条 `"Server Connector #" + CONNECTION_ID.incrementAndGet()` 线程：DNS 解析 → `NetworkManager.createNetworkManagerAndConnect(inetaddress, port, GuiConnecting.this.mc.gameSettings.isUsingNativeTransport())` → 挂 `NetHandlerLoginClient` → 发 `new C00Handshake(47, ip, port, EnumConnectionState.LOGIN)` 和 `new C00PacketLoginStart(GuiConnecting.this.mc.getSession().getProfile())`（`GuiConnecting.java:65-68`）。失败则 `mc.displayGuiScreen(new GuiDisconnected(...))`，并把异常文本里的 "地址:端口" 抹掉（:88-94）。
- `public void updateScreen()`（`GuiConnecting.java:105`）— **login 阶段的收包泵**：channel open 时 `processReceivedPackets()`，否则 `checkDisconnected()`。由 `Minecraft.runTick` 对 `currentScreen` 的常规 tick 驱动。
- `protected void actionPerformed(GuiButton button)`（`GuiConnecting.java:141`）— cancel 按钮：置 `cancel = true`、`closeChannel(new ChatComponentText("Aborted"))`、回到 `previousGuiScreen`。
- `protected void keyTyped(char typedChar, int keyCode)`（`GuiConnecting.java:124`）— 空实现，连接中禁用 Esc。
- 创建入口：`GuiMultiplayer.connectToServer`（GuiMultiplayer.java:399）与命令行直连（Minecraft.java:573）。

### ChunkProviderClient（ChunkProviderClient.java）

- `private Chunk blankChunk`（:26）— `EmptyChunk` 占位；`provideChunk` 永不返回 null。
- `private LongHashMap<Chunk> chunkMapping`（:27）+ `private List<Chunk> chunkListing`（:28）— 双结构：hash 查询 + 顺序遍历。
- `public boolean chunkExists(int x, int z)`（:42）— 恒 true（因为总能拿到 blankChunk）。
- `public Chunk loadChunk(int chunkX, int chunkZ)`（:70）— `new Chunk(this.worldObj, chunkX, chunkZ)` 空 chunk 入表并 `setChunkLoaded(true)`，数据由后续封包填充。
- `public void unloadChunk(int x, int z)`（:51）— 非空则 `chunk.onChunkUnload()`（卸实体），随后移出双结构。
- `public Chunk provideChunk(int x, int z)`（:83）— 查不到返回 `blankChunk`。
- `public boolean unloadQueuedChunks()`（:109）— 名不副实：实际是**客户端 chunk tick**——遍历 `chunkListing` 调 `chunk.func_150804_b(...)`（超 5ms 后传 true 表示跳过重活），总耗时 >100ms 打 "Clientside chunk ticking took {} ms" 日志。由 `WorldClient.tick()`（WorldClient.java:90）每 tick 调用。
- `saveChunks`（:93）恒 true、`canSave`（:129）恒 false、`populate`/`populateChunk`/`getPossibleCreatures`/`getStrongholdGen` 为空或 null（:137-162）。

### ServerAddress / ServerData / ServerList / ThreadLanServerPing

- `public static ServerAddress fromString(String p_78860_0_)`（`ServerAddress.java:30`）— 支持 `[ipv6]:port`；端口缺省或解析失败取 25565；**仅在端口为 25565 时**才做 SRV 查询。`private static String[] getServerAddress(String p_78863_0_)`（`ServerAddress.java:83`）用 JNDI `com.sun.jndi.dns.DnsContextFactory` 查 `"_minecraft._tcp." + host` 的 SRV 记录，任何 Throwable 都回退原地址。`public String getIP()`（:20）返回 `IDN.toASCII(this.ipAddress)`。
- `ServerData`：公开字段 `serverName` / `serverIP` / `populationInfo` / `serverMOTD` / `pingToServer` / `int version = 47` / `String gameVersion = "1.8.9"` / `field_78841_f` / `playerList`（`ServerData.java:9-31`）；私有 `resourceMode`（默认 `PROMPT`）、`serverIcon`、`lanServer`。`public NBTTagCompound getNBTCompound()`（:48）与 `public static ServerData getServerDataFromNBTCompound(NBTTagCompound nbtCompound)`（:84）互逆。内嵌 `public static enum ServerResourceMode { ENABLED, DISABLED, PROMPT }`（:142），MOTD 走 `"addServer.resourcePack." + name` 翻译键。
- `ServerList`：构造即 `loadServerList()`（`ServerList.java:21-25`）。`public void loadServerList()`（:31）读 `new File(this.mc.mcDataDir, "servers.dat")`，`public void saveServerList()`（:60）经 `CompressedStreamTools.safeWrite` 写回。`public void swapServers(int p_78857_1_, int p_78857_2_)`（:116）交换后立即保存。`public static void func_147414_b(ServerData p_147414_0_)`（:129）按 name+ip 匹配更新单条并保存——`NetHandlerPlayClient`（:1795）在玩家应答资源包提示后用它持久化 `acceptTextures`。
- `ThreadLanServerPing`：构造器（`ThreadLanServerPing.java:22`）`setDaemon(true)` 并开 `DatagramSocket`。`public void run()`（:31）循环向组播地址 `224.0.2.60` 端口 `4445` 发送 `getPingResponse(this.motd, this.address)`（格式 `"[MOTD]" + motd + "[/MOTD][AD]" + address + "[/AD]"`，:67-70），间隔 `sleep(1500L)`。`public void interrupt()`（:61）额外把 `isStopping` 置 false（字段名反直觉：true 表示"继续跑"）。静态解析方法 `getMotdFromPingResponse`（:72）/ `getAdFromPingResponse`（:87）供 LAN 扫描侧使用。由 `IntegratedServer.shareToLAN`（IntegratedServer.java:364-365）启动，`IntegratedServer`（:385、:409）停止。

## 时序与生命周期

**连接建立（主线程 + Server Connector 线程 + Netty EventLoop）**

1. 主线程：`GuiMultiplayer` → `new GuiConnecting(...)` → `mc.loadWorld(null)` 清世界 → 起 "Server Connector #N" 线程。
2. Connector 线程：DNS/SRV 解析、`NetworkManager.createNetworkManagerAndConnect`（Netty bootstrap）、发 C00Handshake（protocol 47）+ C00PacketLoginStart。
3. login 阶段每 tick：主线程 `GuiConnecting.updateScreen()` 泵 `processReceivedPackets()`（Netty EventLoop 收包入队，主线程消费）。
4. login 成功后 `NetHandlerLoginClient` 切到 PLAY 状态；`NetHandlerPlayClient.handleJoinGame`（NetHandlerPlayClient.java:280-281）在主线程 `new PlayerControllerMP` + `new WorldClient`，随后 `Minecraft.loadWorld` 用 `playerController.func_178892_a` 造出 `EntityPlayerSP`。

**每 tick（主线程，`Minecraft.runTick`）**

- Minecraft.java:1756 `this.playerController.updateController()` — 同步手持槽位 + 泵 PLAY 阶段收包（这是所有 S 包处理的执行点）。
- Minecraft.java:2224 `this.theWorld.tick()` → `WorldClient.tick()`（WorldClient.java:66）— 推时间、重试实体生成（≤10 个/tick）、`ChunkProviderClient.unloadQueuedChunks()` 做 chunk tick、`updateBlocks()`（≤10 个新 active chunk 的 mood sound/光照检查）。
- 按住左键时每 tick `onPlayerDamageBlock` 推进挖掘状态机。

**每帧（主线程）**

- `GuiConnecting.drawScreen`（GuiConnecting.java:159）在连接阶段渲染；`WorldClient.doVoidFogParticles`（WorldClient.java:304）由 `EntityRenderer` 侧每帧驱动 randomDisplayTick 粒子。

**线程归属**

- `WorldClient` / `PlayerControllerMP` / `ServerList`：仅主线程（客户端线程）。
- `GuiConnecting.connect` 匿名线程：只做建连与发前两个包，之后 `networkManager` 字段被主线程读（无同步，依赖引用可见性，见"陷阱"）。
- 封包实际收发在 Netty EventLoop；`addToSendQueue` / `processReceivedPackets` 是两个世界的交接点。
- `ThreadLanServerPing`：独立守护线程，归集成服务端管理。

**关闭**：`GuiIngameMenu` / `GuiGameOver` → `theWorld.sendQuittingDisconnectingPacket()`（WorldClient.java:287）→ `closeChannel` → 下一次泵包时 `checkDisconnected()` 触发断开 GUI。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void updateController()` | PlayerControllerMP.java:349 | 每 tick，`Minecraft.runTick` "gameMode" 段（Minecraft.java:1756） | **所有 PLAY 收包的处理点**——前后插桩可观察/拦截全部 S 包副作用；也是每 tick 逻辑的稳定锚点 | 暂停或 `theWorld == null` 时不执行；不要在此阻塞 |
| `public void tick()` | WorldClient.java:66 | 每 tick，Minecraft.java:2224 | 世界 tick 前后钩子：时间流速、实体重试队列、chunk tick | 覆写时必须调 `super.tick()`，否则实体/方块 tick 全停 |
| `public boolean clickBlock(BlockPos loc, EnumFacing face)` | PlayerControllerMP.java:198 | 左键按下命中方块（Minecraft.java:1545） | 拦截/改写挖掘开始；nuker/reach 类功能入口 | 返回值影响手臂挥动；发包与本地状态机必须一致，否则服务端回滚 |
| `public boolean onPlayerDamageBlock(BlockPos posBlock, EnumFacing directionFacing)` | PlayerControllerMP.java:285 | 按住左键每 tick（Minecraft.java:1504） | 修改挖掘速度（改 `curBlockDamageMP` 增量）、无延迟挖掘（清 `blockHitDelay`） | 服务端独立校验挖掘时长，偏差过大会被拒 |
| `public boolean onPlayerDestroyBlock(BlockPos pos, EnumFacing side)` | PlayerControllerMP.java:123 | 挖掘完成/创造点击 | 观察方块破坏预测；取消本地破坏 | 只是预测，权威在服务端 |
| `public void resetBlockRemoving()` | PlayerControllerMP.java:274 | 松开左键（Minecraft.java:1512） | 观察挖掘中断；抑制 ABORT 包 | 与 `isHittingBlock` 状态耦合 |
| `public boolean onPlayerRightClick(EntityPlayerSP player, WorldClient worldIn, ItemStack heldStack, BlockPos hitPos, EnumFacing side, Vec3 hitVec)` | PlayerControllerMP.java:390 | 右键方块（Minecraft.java:1600) | scaffold/放置类功能；改 hitVec/side | C08 无条件发出——只改本地判断不改包会造成不同步 |
| `public boolean sendUseItem(EntityPlayer playerIn, World worldIn, ItemStack itemStackIn)` | PlayerControllerMP.java:456 | 右键使用物品（Minecraft.java:1627） | 观察/伪造物品使用（例如假吃） | 同上，C08 空坐标形式 |
| `public void attackEntity(EntityPlayer playerIn, Entity targetEntity)` | PlayerControllerMP.java:495 | 左键命中实体（Minecraft.java:1537） | killaura/触发式攻击的封包出口；攻击事件观察 | `syncCurrentPlayItem()` 在包前执行；旁观模式跳过本地挥击 |
| `public boolean interactWithEntitySendPacket(EntityPlayer playerIn, Entity targetEntity)` | PlayerControllerMP.java:509 | 右键实体（Minecraft.java:1586） | 实体交互拦截 | 与 `isPlayerRightClickingOnEntity`（:523，INTERACT_AT）成对出现，两包都会发 |
| `public ItemStack windowClick(int windowId, int slotId, int mouseButtonClicked, int mode, EntityPlayer playerIn)` | PlayerControllerMP.java:534 | 容器内点击（GuiContainer.java:685） | 库存操作自动化（inv manager）；事务 ID 在此生成 | 事务 ID 由 `getNextTransactionID` 顺序产生，跳过本方法直接发包会打乱确认序列 |
| `private void syncCurrentPlayItem()` | PlayerControllerMP.java:379 | 几乎所有动作方法开头 | 观察/伪造手持槽位切换（C09） | private；hook 需在字节码层或改调用方 |
| `public void setGameType(WorldSettings.GameType type)` | PlayerControllerMP.java:101 | S01 join / S07 respawn / S2B change game state | 游戏模式变更事件 | 会立即重配 capabilities（飞行等） |
| `public float getBlockReachDistance()` | PlayerControllerMP.java:344 | 每次射线检测取 reach | 改挖掘/交互距离 | 服务端有自己的距离校验 |
| `public void doPreChunk(int chuncX, int chuncZ, boolean loadChunk)` | WorldClient.java:153 | S21 chunk data / S26 bulk（NetHandlerPlayClient.java:755/759/1345） | chunk 装卸事件（小地图、区块缓存） | Netty 线程不会直接到这——已被 `PacketThreadUtil` 调度回主线程 |
| `public void addEntityToWorld(int entityID, Entity entityToSpawn)` | WorldClient.java:234 | 各类 spawn 包 | 实体进入世界的统一观察点（ESP 等） | 同 ID 旧实体会被静默替换 |
| `public Entity removeEntityFromWorld(int entityID)` | WorldClient.java:262 | S13 destroy / S0D collect | 实体移除观察点 | 返回可能为 null |
| `public boolean invalidateRegionAndSetBlock(BlockPos pos, IBlockState state)` | WorldClient.java:275 | S23 block change / S22 multi block change | 服务端方块更新的统一入口 | flag 固定为 3（通知+重渲染） |
| `public boolean spawnEntityInWorld(Entity entityIn)` | WorldClient.java:173 | addEntityToWorld 及本地粒子/物品实体 | 生成拦截 | 失败会进 `entitySpawnQueue` 稍后重试，别当作最终失败 |
| `public void sendQuittingDisconnectingPacket()` | WorldClient.java:287 | 玩家主动退出服务器 | 断开前清理（保存配置等） | 之后 channel 即关闭，不能再发包 |
| `public void updateScreen()` | GuiConnecting.java:105 | login 阶段每 tick | 观察连接进度；注入登录期逻辑 | 此时 `NetHandlerLoginClient` 是 handler，PLAY 包不可用 |
| `private void connect(final String ip, final int port)` | GuiConnecting.java:48 | 构造时一次 | 改目标地址/代理接管；协议版本在 :67 的 `C00Handshake(47, ...)` 硬编码 | 跑在 Connector 线程；`cancel` 标志无 volatile |
| `public void playSound(double x, double y, double z, String soundName, float volume, float pitch, boolean distanceDelay)` | WorldClient.java:439 | 世界声音事件 | 声音观察/替换 | 距离 >10 有人为延迟 |
| `public void loadServerList()` / `public void saveServerList()` | ServerList.java:31 / :60 | 打开多人菜单 / 每次列表变更 | servers.dat 迁移、注入条目 | `swapServers` 每次都写盘 |
| `public static ServerAddress fromString(String p_78860_0_)` | ServerAddress.java:30 | 连接与 ping 前 | 地址重写（代理） | SRV 查询是同步 JNDI 调用，可能阻塞数秒——只在端口 25565 时发生 |

## 数据与协议

**发出的 serverbound 封包（全部经 `NetHandlerPlayClient.addToSendQueue`）**

| 封包 | 触发方法 | 关键字段/构造 |
|---|---|---|
| `C00Handshake` | `GuiConnecting.connect`（GuiConnecting.java:67） | `(47, ip, port, EnumConnectionState.LOGIN)` — 协议号 47 = 1.8.x |
| `C00PacketLoginStart` | GuiConnecting.java:68 | `mc.getSession().getProfile()` |
| `C07PacketPlayerDigging` | `clickBlock` / `onPlayerDamageBlock` / `resetBlockRemoving` / `onStoppedUsingItem` | `Action` ∈ START_DESTROY_BLOCK / ABORT_DESTROY_BLOCK / STOP_DESTROY_BLOCK / RELEASE_USE_ITEM；RELEASE_USE_ITEM 用 `BlockPos.ORIGIN` + `EnumFacing.DOWN`（PlayerControllerMP.java:579） |
| `C08PacketPlayerBlockPlacement` | `onPlayerRightClick`（:424，带 pos/side/offset f,f1,f2）、`sendUseItem`（:465，仅 ItemStack） | 空坐标形式表示"对空气使用" |
| `C09PacketHeldItemChange` | `syncCurrentPlayItem`（:386） | 快捷栏索引 |
| `C02PacketUseEntity` | `attackEntity`（:498）/ `interactWithEntitySendPacket`（:512）/ `isPlayerRightClickingOnEntity`（:527） | `Action.ATTACK` / `Action.INTERACT` / `Vec3` 相对命中点（INTERACT_AT） |
| `C0EPacketClickWindow` | `windowClick`（:538） | `(windowId, slotId, mouseButtonClicked, mode, itemstack, short1)`，`short1` 为事务 ID |
| `C10PacketCreativeInventoryAction` | `sendSlotPacket`（:561）/ `sendPacketDropItem`（:572） | `slotId = -1` 表示丢出 |
| `C11PacketEnchantItem` | `sendEnchantPacket`（:551） | `(windowID, button)` |

**servers.dat NBT 格式**（`ServerList.java:36-48` 读，`:64-73` 写；条目见 `ServerData.java:48-110`）

| NBT 键 | 类型 | 读/写 | 含义 |
|---|---|---|---|
| `servers` | TagList(10) | `getTagList("servers", 10)` / `setTag` | 服务器条目列表 |
| `name` | String(8) | `getString("name")` / `setString` | 显示名 |
| `ip` | String | `getString("ip")` / `setString` | 地址（host 或 host:port） |
| `icon` | String(8) | `getString("icon")` / 仅非 null 时写 | base64 服务器图标 |
| `acceptTextures` | Byte(1) | `getBoolean` / ENABLED 写 true、DISABLED 写 false、PROMPT 不写 | 资源包三态策略 |

文件经 `CompressedStreamTools.read` / `safeWrite`（gzip NBT），路径 `new File(this.mc.mcDataDir, "servers.dat")`。

**LAN 广播文本协议**（`ThreadLanServerPing.java:40-41, 67-70`）：UDP 组播 `224.0.2.60:4445`，载荷 `"[MOTD]" + motd + "[/MOTD][AD]" + address + "[/AD]"`，每 1500ms 一次。

**SRV 查询**（`ServerAddress.java:94`）：JNDI DNS 查 `"_minecraft._tcp." + host` 的 `SRV` 属性，取 split 后的 `astring[3]`（target）与 `astring[2]`（port）。

## 不变量与陷阱

- **收包只在主线程**：`processReceivedPackets` 的两个泵（`PlayerControllerMP.updateController` 与 `GuiConnecting.updateScreen`）都在主线程执行；S 包 handler 中触碰世界状态是安全的，前提是没人从别的线程调 `WorldClient` 方法。`WorldClient` / `PlayerControllerMP` 完全没有内部同步。
- **游戏暂停 = 不泵包**：Minecraft.java:1756 有 `!this.isGamePaused` 条件；单人暂停时封包会积压。
- **`ChunkProviderClient.provideChunk` 永不返回 null**，未加载区域拿到 `EmptyChunk`（ChunkProviderClient.java:86）。判断"是否真加载"要用 `Chunk#isEmpty()`，不能判 null。
- **`unloadQueuedChunks()` 实际是 chunk tick**（ChunkProviderClient.java:109），恒返回 false。改名/挪走会停掉客户端 chunk tick。
- **挖掘状态机的一致性**：`clickBlock`/`onPlayerDamageBlock`/`resetBlockRemoving` 维护 `isHittingBlock`/`currentBlock`/`curBlockDamageMP` 三元组，且 `isHittingPosition`（PlayerControllerMP.java:363）会比较手持物品（NBT 相等 + 可损耗物品忽略 metadata）——换手持物会重置挖掘。绕过其中一个方法直接发 C07 会造成客户端-服务端挖掘状态漂移。
- **C08 无条件发送**：`onPlayerRightClick` 中即使 `onBlockActivated` 已返回 true，`C08PacketPlayerBlockPlacement` 照发（PlayerControllerMP.java:424）。功能层如果吞掉本地激活但不吞包（或反之）会不同步。
- **`GuiConnecting.cancel` 与 `networkManager` 无 volatile/锁**（GuiConnecting.java:26-27）：主线程写 `cancel`、Connector 线程写 `networkManager`，跨线程可见性靠运气。JDK 25 下 JMM 未变，但插桩时不要假设它是同步的。
- **`ServerAddress.getServerAddress` 依赖 `com.sun.jndi.dns.DnsContextFactory`**（ServerAddress.java:88）：JDK 25 中该内部类仍存在但属于 `jdk.naming.dns` 模块；若运行时裁剪了该模块，`Class.forName` 抛异常后被 `catch (Throwable)` 静默吞掉，SRV 解析退化为直连——排查"连不上用 SRV 的服务器"时先查这里。查询是同步阻塞调用，且只在端口为默认 25565 时触发。
- **`ThreadLanServerPing.isStopping` 语义反转**：true 表示继续运行（ThreadLanServerPing.java:19,36）；`interrupt()` 必须走覆写版（:61）才会置 false。同样无 volatile。
- **`WorldClient` 构造即绑定 `Minecraft.getMinecraft()` 单例**（WorldClient.java:47），不可脱离客户端环境实例化（测试注意）。
- **本地时间是预测值**：`WorldClient.tick()` 自增 `worldTime`/`totalWorldTime`，服务端 S03 会周期性覆盖；`setWorldTime` 的负值约定（WorldClient.java:468）意味着直接喂负数会顺带改 gamerule。
- **`invalidateBlockReceiveRegion` 是空方法**（WorldClient.java:107）——注释描述的"80 receive ticks 失效队列"在 1.8 已删除，只剩接口壳，别按注释理解行为。
- **entitySpawnQueue 每 tick 只消化 10 个**（WorldClient.java:78）：大量实体在 chunk 到达前生成时会延迟数 tick 才可见。
- `getBlockReachDistance()` 的 javadoc 写 "player reach distance = 4F"，实际返回 5.0F/4.5F（PlayerControllerMP.java:344-347）——MCP 注释不可尽信。
- LWJGL3 移植对本包无直接影响：包内不含任何 `org.lwjgl` 引用；键盘常量仅出现在 `keyTyped` 注释里。网络层（Netty 4.2.16，见仓库提交记录）的行为经由 `NetworkManager` 抽象，本包未感知。

## 交叉引用

- `net.minecraft.client` → `Minecraft#runTick`（调 `PlayerControllerMP#updateController`、`WorldClient#tick`、输入分发到 clickBlock/attackEntity 等）；`Minecraft#loadWorld` → `PlayerControllerMP#func_178892_a`
- `net.minecraft.client.network` → `NetHandlerPlayClient#handleJoinGame` / `#handleRespawn`（创建 `WorldClient`、`PlayerControllerMP`）；spawn/destroy/block 包 → `WorldClient#addEntityToWorld` / `#removeEntityFromWorld` / `#invalidateRegionAndSetBlock` / `#doPreChunk`；`NetHandlerPlayClient#handleResourcePack` → `ServerList.func_147414_b`
- `net.minecraft.client.network` → `NetHandlerLoginClient`（`GuiConnecting#connect` 挂载）；`OldServerPinger` → `ServerAddress.fromString`
- `net.minecraft.network` → `NetworkManager#createNetworkManagerAndConnect` / `#addToSendQueue` / `#processReceivedPackets` / `#closeChannel`；`C00Handshake`、`C00PacketLoginStart`、`C02/C07/C08/C09/C0E/C10/C11` 系列
- `net.minecraft.client.gui` → `GuiMultiplayer#connectToServer`（创建 `GuiConnecting`、持有 `ServerList`）；`GuiContainer#handleMouseClick` → `PlayerControllerMP#windowClick`；`GuiEnchantment` → `#sendEnchantPacket`；`GuiIngameMenu` / `GuiGameOver` → `WorldClient#sendQuittingDisconnectingPacket`
- `net.minecraft.client.gui.inventory` → `GuiContainerCreative` / `CreativeCrafting` → `PlayerControllerMP#sendSlotPacket` / `#sendPacketDropItem`
- `net.minecraft.server.integrated` → `IntegratedServer#shareToLAN`（启动 `ThreadLanServerPing`）、`IntegratedServer#stopServer`/finalTick（`#interrupt`）
- `net.minecraft.world` → `World`（`WorldClient` 父类）；`net.minecraft.world.chunk` → `Chunk#func_150804_b`、`EmptyChunk`；`net.minecraft.world.storage` → `SaveHandlerMP`、`SaveDataMemoryStorage`
- `net.minecraft.nbt` → `CompressedStreamTools#read` / `#safeWrite`（servers.dat）
- `net.minecraft.realms` → `RealmsServerAddress` → `ServerAddress.fromString`
- `net.minecraft.client.entity` → `EntityPlayerSP`（由 `PlayerControllerMP#func_178892_a` 构造）

## 覆盖声明

完整读取了 8/8 个文件（每个文件从第 1 行读到末行）。逐行精读的类：`WorldClient`、`PlayerControllerMP`、`GuiConnecting`、`ChunkProviderClient`、`ServerAddress`、`ServerData`、`ServerList`、`ThreadLanServerPing`——即全部 8 个。没有只做结构性浏览的类。另对包外调用方（`Minecraft.java`、`NetHandlerPlayClient.java`、`GuiMultiplayer.java`、`IntegratedServer.java`、`GuiContainer.java` 等）做了 grep 级交叉验证，未整读。
