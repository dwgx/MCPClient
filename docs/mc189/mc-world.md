---
area: net/minecraft/world
slug: mc-world
files: 38
lines: 9059
tier: B
---

# net/minecraft/world 架构笔记

## 定位

本包是整个游戏的"世界模型"层：抽象基类 `World` 持有方块访问、实体列表、TileEntity 列表、光照、天气、爆炸、红石电力查询等所有世界状态操作；`WorldServer` 在其上叠加集成服务端逻辑（计划 tick、生物生成、区块随机 tick、封包广播）。客户端侧的 `WorldClient`（位于 `net.minecraft.client.multiplayer`，不在本包）同样继承 `World`。

- 谁调用它：`Minecraft#runTick()` 每 tick 调 `theWorld.updateEntities()`（Minecraft.java:2202）；`MinecraftServer#updateTimeLightAndEntities()` 每 tick 调 `worldserver.tick()` 与 `worldserver.updateEntities()`（MinecraftServer.java:770/781）；渲染层 `RenderGlobal` 与服务端 `WorldManager` 通过 `IWorldAccess` 回调订阅世界变更；所有 Block/Entity/TileEntity 代码都拿着 `World` 引用做查询与写入。
- 它调用谁：`net.minecraft.world.chunk`（`IChunkProvider`/`Chunk` 存取方块）、`net.minecraft.world.biome`（`WorldChunkManager`）、`net.minecraft.world.storage`（`WorldInfo`/`ISaveHandler`/`MapStorage`）、`net.minecraft.block`（tick、碰撞、红石）、`net.minecraft.entity`、服务端侧的 `PlayerManager`/`EntityTracker`/`ConfigurationManager`（封包广播）。
- 如果消失：方块读写、实体 tick、光照、碰撞、射线检测全部不可用——客户端渲染与服务端模拟都会瘫痪；这是引擎的中枢数据结构。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| ChunkCache | 185 | implements IBlockAccess | 抓取一片 Chunk 引用形成只读快照，供寻路/渲染避免反复查 World |
| ChunkCoordIntPair | 113 | — | 不可变的区块 (x,z) 坐标对，含 long 打包 `chunkXZ2Int` |
| ColorizerFoliage | 44 | — | 静态类；持有 65536 项树叶颜色查找表（由资源包 foliage.png 填充） |
| ColorizerGrass | 24 | — | 静态类；草地颜色查找表，同上 |
| DifficultyInstance | 51 | — | 按世界难度/时间/区块居住时长/月相计算"局部难度"值 |
| EnumDifficulty | 41 | enum | 难度枚举 PEACEFUL/EASY/NORMAL/HARD，id 与翻译键 |
| EnumSkyBlock | 14 | enum | 光照类型 SKY(15)/BLOCK(0)，携带 defaultLightValue |
| Explosion | 261 | — | 两阶段爆炸：doExplosionA 算破坏与伤害，doExplosionB 出音效/粒子/掉落 |
| GameRules | 190 | — | TreeMap 存储的游戏规则表，NBT 读写，内部类 Value/ValueType |
| IBlockAccess | 33 | interface | 只读方块访问接口（World 与 ChunkCache 共同实现） |
| IInteractionObject | 12 | interface extends IWorldNameable | 可交互对象：createContainer + getGuiID |
| ILockableContainer | 12 | interface extends IInventory, IInteractionObject | 可上锁容器 |
| IWorldAccess | 50 | interface | 世界事件监听器：方块更新/音效/粒子/实体增删等回调 |
| IWorldNameable | 21 | interface | 可命名对象：getName/hasCustomName/getDisplayName |
| LockCode | 42 | — | 容器锁字符串，NBT "Lock" 键读写 |
| MinecraftException | 9 | extends Exception | 存档相关的受检异常（session lock 等） |
| NextTickListEntry | 73 | implements Comparable&lt;NextTickListEntry&gt; | 计划 tick 条目：位置+方块+scheduledTime+priority+自增 id |
| SpawnerAnimals | 272 | — (final) | 服务端自然刷怪：找合格区块并按 EnumCreatureType 生成生物 |
| Teleporter | 430 | — | 地狱门搜索/建造/传送定位，带 PortalPosition 缓存 |
| World | 3865 | abstract, implements IBlockAccess | 世界核心：方块/实体/光照/天气/碰撞/射线/红石的总入口 |
| WorldManager | 113 | implements IWorldAccess | 服务端侧监听器：把世界事件转成封包经 ConfigurationManager 广播 |
| WorldProvider | 283 | abstract | 维度提供者：区块生成器、天空角度、雾色、光亮表、维度 id |
| WorldProviderEnd | 131 | extends WorldProvider | 末地维度（dimensionId=1，hasNoSky，固定天体角） |
| WorldProviderHell | 120 | extends WorldProvider | 地狱维度（dimensionId=-1，isHellWorld，界边界坐标 /8） |
| WorldProviderSurface | 17 | extends WorldProvider | 主世界维度，仅提供名称 |
| WorldSavedData | 51 | abstract | 挂在 MapStorage 下的持久化数据基类（dirty 标记 + NBT 读写） |
| WorldServer | 1185 | extends World implements IThreadListener | 集成服务端世界：计划 tick、刷怪、随机 tick、封包广播、存档 |
| WorldServerMulti | 79 | extends WorldServer | 从属维度世界：共享主世界 MapStorage/记分板，镜像主世界边界 |
| WorldSettings | 229 | — (final) | 建世界参数：seed/GameType/hardcore/WorldType，内部 enum GameType |
| WorldType | 158 | — | 地形生成器类型注册表（DEFAULT/FLAT/AMPLIFIED/DEBUG_WORLD 等） |
| border/EnumBorderStatus | 24 | enum | 边界状态 GROWING/SHRINKING/STATIONARY（携带渲染颜色 int） |
| border/IBorderListener | 18 | interface | 边界变更监听器（7 个 on* 回调） |
| border/WorldBorder | 277 | — | 世界边界：中心/直径插值/伤害参数，setter 广播给 listeners |
| demo/DemoWorldManager | 147 | extends ItemInWorldManager | 演示模式交互管理：超过 120500 tick 后禁止破坏/使用 |
| demo/DemoWorldServer | 21 | extends WorldServer | 固定种子（"North Carolina".hashCode()）的演示世界 |
| pathfinder/NodeProcessor | 63 | abstract | 寻路节点处理器基类：PathPoint 池 + 实体尺寸 |
| pathfinder/SwimNodeProcessor | 93 | extends NodeProcessor | 水中寻路：六方向全水方块才可通行 |
| pathfinder/WalkNodeProcessor | 308 | extends NodeProcessor | 陆地寻路：门/栅栏/水/岩浆/掉落高度判定 |

## 核心类详解

### World（World.java）

关键字段（World.java:58-151）：
- `public final List<Entity> loadedEntityList`（:64）、`protected final List<Entity> unloadedEntityList`（:65）
- `public final List<TileEntity> loadedTileEntityList`（:66）、`public final List<TileEntity> tickableTileEntities`（:67）、私有 `addedTileEntityList`/`tileEntitiesToBeRemoved`（:68-69）
- `public final List<EntityPlayer> playerEntities`（:70）、`public final List<Entity> weatherEffects`（:71）
- `protected final IntHashMap<Entity> entitiesById`（:72，移植时加了泛型推断注释）
- `public final Random rand`（:101）、`public final WorldProvider provider`（:104）
- `protected List<IWorldAccess> worldAccesses`（:105）、`protected IChunkProvider chunkProvider`（:108）
- `protected WorldInfo worldInfo`（:114）、`public final boolean isRemote`（:131）
- `protected Set<ChunkCoordIntPair> activeChunkSet`（:132）、`int[] lightUpdateBlockList`（:151，32768 长度）

关键方法签名（逐字）：
- `public boolean setBlockState(BlockPos pos, IBlockState newState, int flags)`（World.java:345）——flag 1 邻居更新、2 发给客户端、4 阻止重渲染；内部依次 `chunk.setBlockState` → `checkLight` → `markBlockForUpdate` → `notifyNeighborsRespectDebug`。
- `public IBlockState getBlockState(BlockPos pos)`（World.java:850）——越界返回 `Blocks.air.getDefaultState()`。
- `public void updateEntities()`（World.java:1619）——每 tick 的实体/TileEntity 主循环，见时序节。
- `public MovingObjectPosition rayTraceBlocks(Vec3 vec31, Vec3 vec32, boolean stopOnLiquid, boolean ignoreBlockWithoutBoundingBox, boolean returnLastUncollidableBlock)`（World.java:888）——DDA 步进最多 200 格。
- `public boolean spawnEntityInWorld(Entity entityIn)`（World.java:1149）、`public void removeEntity(Entity entityIn)`（World.java:1199）。
- `public boolean checkLightFor(EnumSkyBlock lightType, BlockPos pos)`（World.java:2836）——BFS 光照传播，使用 `lightUpdateBlockList` 打包位。
- `public Explosion newExplosion(Entity entityIn, double x, double y, double z, float strength, boolean isFlaming, boolean isSmoking)`（World.java:2218）。
- `public void tick()`（World.java:2476）——基类只做 `updateWeather()`。
- `public int getRedstonePower(BlockPos pos, EnumFacing facing)`（World.java:3244）、`public boolean isBlockPowered(BlockPos pos)`（World.java:3251）。

调用时机：`updateEntities` 由 `Minecraft#runTick`（客户端，每 game tick）和 `MinecraftServer#updateTimeLightAndEntities`（服务端）驱动；`setBlockState` 被所有方块交互/生成/封包处理代码调用。

### WorldServer（WorldServer.java）

关键字段：`private final MinecraftServer mcServer`（:77）、`private final EntityTracker theEntityTracker`（:78）、`private final PlayerManager thePlayerManager`（:79）、`pendingTickListEntriesHashSet`/`pendingTickListEntriesTreeSet`（:80-81，计划 tick 双集合）、`private final Map<UUID, Entity> entitiesByUuid`（:82）、`public ChunkProviderServer theChunkProviderServer`（:83）、`private final Teleporter worldTeleporter`（:95）、`private final SpawnerAnimals mobSpawner = new SpawnerAnimals()`（:96）、`blockEventQueue`（:98，双缓冲方块事件队列）。

关键方法签名：
- `public WorldServer(MinecraftServer server, ISaveHandler saveHandlerIn, WorldInfo info, int dimensionId, Profiler profilerIn)`（WorldServer.java:103）——构造中 `provider.registerWorld(this)`、`createChunkProvider()`、`calculateInitialSkylight()`、`calculateInitialWeather()`。
- `public World init()`（WorldServer.java:117）——MapStorage、VillageCollection、ServerScoreboard、WorldBorder 参数恢复。由 `MinecraftServer.loadAllWorlds` 在 new 之后立即链式调用（MinecraftServer.java:324-335）。
- `public void tick()`（WorldServer.java:166）——服务端世界每 tick 主入口，见时序节。
- `public void updateBlockTick(BlockPos pos, Block blockIn, int delay, int priority)`（WorldServer.java:463）与 `public boolean tickUpdates(boolean p_72955_1_)`（WorldServer.java:554）——计划 tick 排队与执行（每 tick 上限 1000 条，:570-573）。
- `protected void updateBlocks()`（WorldServer.java:339）——遍历 `activeChunkSet` 做雷击/结冰/降雪/randomTick。
- `public void addBlockEvent(BlockPos pos, Block blockIn, int eventID, int eventParam)`（WorldServer.java:1026）与 `private void sendQueuedBlockEvents()`（WorldServer.java:1041）——事件转 `S24PacketBlockAction` 广播。
- `public ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule)`（WorldServer.java:1169）、`public boolean isCallingFromMinecraftThread()`（WorldServer.java:1174）——IThreadListener 实现，转发给 `mcServer`；Netty 线程收包后必须经此调度回服务端主线程。

### Explosion（Explosion.java）

字段：`isFlaming`/`isSmoking`（:29/:32）、`explosionRNG`、`worldObj`、`explosionX/Y/Z`、`exploder`、`explosionSize`、`affectedBlockPositions`（List&lt;BlockPos&gt;）、`playerKnockbackMap`（Map&lt;EntityPlayer, Vec3&gt;）。

- `public void doExplosionA()`（Explosion.java:72）——16×16×16 射线壳采样确定破坏方块集合；对半径 2×size 内实体施加伤害 `entity.attackEntityFrom(DamageSource.setExplosionSource(this), ...)`（:155）与击退，玩家击退单独记入 `playerKnockbackMap`（:163）。
- `public void doExplosionB(boolean spawnParticles)`（Explosion.java:174）——音效 "random.explode"、粒子、按 `1.0F / this.explosionSize` 概率掉落、清方块、isFlaming 时放火。

调用时机：`World#newExplosion`（World.java:2218）A+B(true)；`WorldServer#newExplosion`（WorldServer.java:1004）A+B(false) 并向 64 格内玩家发送 `S27PacketExplosion`（:1019）——客户端收包后本地重放 B 阶段。

### WorldProvider 家族（WorldProvider.java）

- `public final void registerWorld(World worldIn)`（WorldProvider.java:51）——World/WorldServer 构造时调用，初始化 `worldChunkMgr` 与 `lightBrightnessTable`。
- `public IChunkProvider createChunkGenerator()`（WorldProvider.java:99）——按 WorldType 选择 ChunkProviderFlat/Debug/Generate。
- `public float calculateCelestialAngle(long worldTime, float partialTicks)`（WorldProvider.java:115）——渲染与光照都用；Hell 固定 0.5F（WorldProviderHell.java:72），End 固定 0.0F（WorldProviderEnd.java:34）。
- `public static WorldProvider getProviderForDimension(int dimension)`（WorldProvider.java:198）——-1→Hell、0→Surface、1→End，其它返回 null。
- `public WorldBorder getWorldBorder()`（WorldProvider.java:279）——注意每次调用 new 一个 WorldBorder；Hell 覆盖为中心坐标 /8 的匿名子类（WorldProviderHell.java:106-119）。World 构造时缓存一份（World.java:164），之后都用 `World#getWorldBorder()` 取缓存实例。

### WorldBorder（border/WorldBorder.java）

字段：`listeners`、`centerX/centerZ`、`startDiameter = 6.0E7D`（:15）、`endDiameter`、`startTime/endTime`（毫秒墙钟）、`worldSize = 29999984`（:28）、`damageAmount = 0.2D`、`damageBuffer = 5.0D`、`warningTime = 15`、`warningDistance = 5`。

- `public double getDiameter()`（WorldBorder.java:140）——非 STATIONARY 时按 `System.currentTimeMillis()` 插值，插值完成后自动 `setTransition(this.endDiameter)` 收敛。
- `public void setTransition(double oldSize, double newSize, long time)`（WorldBorder.java:180）——启动收缩/扩张并回调 `onTransitionStarted`。
- `contains(BlockPos)` / `contains(ChunkCoordIntPair)` / `contains(AxisAlignedBB)`（:35/:40/:45）——刷怪、TileEntity tick、方块可编辑判定都依赖。

监听者：`WorldServerMulti` 构造时给主世界边界挂 `IBorderListener` 把七类变更镜像到子维度（WorldServerMulti.java:19-49）；客户端侧由封包处理器同步（不在本包）。

### GameRules（GameRules.java）

`private TreeMap<String, GameRules.Value> theGameRules`（:9）。构造器注册 15 条默认规则（:13-27），含 `"randomTickSpeed", "3", GameRules.ValueType.NUMERICAL_VALUE`（:25）。`public NBTTagCompound writeToNBT()`（:76）/ `public void readFromNBT(NBTTagCompound nbt)`（:92）以字符串形式全量存取。`Value.setValue`（:138）同时解析 boolean/int/double，解析失败静默保留默认。调用方：`World#getGameRules()`（World.java:3505）委托给 `worldInfo.getGameRulesInstance()`；`WorldServer.tick` 每 tick 查询 `"doDaylightCycle"`/`"doMobSpawning"`。

### SpawnerAnimals（SpawnerAnimals.java）

- `public int findChunksForSpawning(WorldServer worldServerIn, boolean spawnHostileMobs, boolean spawnPeacefulMobs, boolean p_77192_4_)`（SpawnerAnimals.java:29）——以每个非旁观玩家为中心 17×17 区块（`l = 8`，:46）圈定合格区块；按 `enumcreaturetype.getMaxNumberOfCreature() * i / MOB_COUNT_DIV`（:77，`MOB_COUNT_DIV = (int)Math.pow(17.0D, 2.0D)`，:22）计算上限；候选点需距玩家 >24 格且距出生点平方距离 ≥576（:113）；反射构造 `entityClass.getConstructor(new Class[] {World.class})`（:131）。
- `public static boolean canCreatureTypeSpawnAtLocation(EntityLiving.SpawnPlacementType spawnPlacementTypeIn, World worldIn, BlockPos pos)`（:182）——地面/水中判定，且必须 `worldIn.getWorldBorder().contains(pos)`。
- `public static void performWorldGenSpawning(World worldIn, BiomeGenBase biomeIn, int p_77191_2_, int p_77191_3_, int p_77191_4_, int p_77191_5_, Random randomIn)`（:217）——区块生成期的初始动物群。

调用时机：`WorldServer.tick` 内 `this.mobSpawner.findChunksForSpawning(this, this.spawnHostileMobs, this.spawnPeacefulMobs, this.worldInfo.getWorldTotalTime() % 400L == 0L)`（WorldServer.java:192，第四参=每 400 tick 才允许动物生成）。

### Teleporter（Teleporter.java）

- `public void placeInPortal(Entity entityIn, float rotationYaw)`（Teleporter.java:32）——dimensionId==1（末地）直接铸黑曜石平台；否则 `placeInExistingPortal` 失败即 `makePortal` 再放置。
- `public boolean placeInExistingPortal(Entity entityIn, float rotationYaw)`（:70）——±128 格柱状扫描找最近 `Blocks.portal`，命中缓存 `destinationCoordinateCache`（LongHashMap，键为 `ChunkCoordIntPair.chunkXZ2Int(j, k)`）。
- `public boolean makePortal(Entity entityIn)`（:192）——16 格半径内找可建位；找不到就在 y∈[70, actualHeight-10] 硬造框架。
- `public void removeStalePortalLocations(long worldTime)`（:399）——每 100 tick 清理 300 tick 未使用的缓存；由 `WorldServer.tick` 的 "portalForcer" 段调用（WorldServer.java:221）。

### ChunkCache（ChunkCache.java）

- `public ChunkCache(World worldIn, BlockPos posFromIn, BlockPos posToIn, int subIn)`（ChunkCache.java:24）——把 `[posFrom-sub, posTo+sub]` 覆盖的 Chunk 引用拷进 `chunkArray`；同时算 `hasExtendedLevels`。
- `getBlockState`（:84）带越界回退 air；`getLightForExt`（:110）处理 `getUseNeighborBrightness` 方块取邻居最大光。
- 使用者：`PathNavigate`（PathNavigate.java:93/132）为寻路建快照；渲染层 `RegionRenderCache extends ChunkCache`（RegionRenderCache.java:13）供区块重建线程读取——这是它存在的核心理由：让非主线程读方块时不触碰 `World` 的可变结构。

### WalkNodeProcessor（pathfinder/WalkNodeProcessor.java）

- `public int findPathOptions(PathPoint[] pathOptions, Entity entityIn, PathPoint currentPoint, PathPoint targetPoint, float maxDistance)`（WalkNodeProcessor.java:78）——四个水平方向 + 允许跳 1 格（`j = 1` 当头顶可站，:83-86）。
- `public static int func_176170_a(IBlockAccess blockaccessIn, Entity entityIn, int x, int y, int z, int sizeX, int sizeY, int sizeZ, boolean avoidWater, boolean breakDoors, boolean enterDoors)`（:194）——碰撞语义编码：1 可走 / 0 实体阻挡 / -1 水(避水时) / -2 岩浆 / -3 栅栏墙(及轨道边缘) / -4 关闭的活板门 / 2 开活板门或水上可通过（注释 :183-188）。
- `getSafePoint`（:119）用 `entityIn.getMaxFallHeight()`（:160）限制下落。被 `PathFinder`（net.minecraft.pathfinding）在实体 AI 寻路时调用，处理器由 `PathNavigateGround` 等配置 `setEnterDoors/setBreakDoors/setAvoidsWater/setCanSwim`。

## 时序与生命周期

**初始化（服务端）**：`MinecraftServer.loadAllWorlds` → `new WorldServer(...)`（构造内：`WorldProvider.getProviderForDimension` → `provider.registerWorld(this)` → `createChunkProvider()` → `calculateInitialSkylight()` → `calculateInitialWeather()` → `getWorldBorder().setSize(...)`）→ `.init()`（MapStorage/村庄/记分板/边界参数）→ 维度 -1/1 用 `WorldServerMulti` 复用主世界数据 → `addWorldAccess(new WorldManager(this, worldServers[i]))`（MinecraftServer.java:338）。首次建档再走 `initialize(WorldSettings)` → `createSpawnPosition`（WorldServer.java:748/801）。

**每 tick（服务端线程，`MinecraftServer.updateTimeLightAndEntities`）**，顺序即 `WorldServer.tick()`（WorldServer.java:166-224）内部 profiler 段：
1. `super.tick()` → `updateWeather()`（雨/雷计时与强度渐变，变化时广播 `S2BPacketChangeGameState`，WorldServer.java:1077）
2. 硬核难度强制 HARD；`cleanupCache`；全员睡觉则跳日并 `wakeAllPlayers()`
3. `mobSpawner.findChunksForSpawning(...)`（受 `doMobSpawning` 规则控制）
4. `chunkProvider.unloadQueuedChunks()`；重算 skylightSubtracted；`worldTotalTime+1`，`doDaylightCycle` 时 `worldTime+1`
5. `tickUpdates(false)`（计划 tick，≤1000 条/tick）
6. `updateBlocks()`（activeChunkSet：雷击、结冰/降雪、randomTickSpeed 次随机 tick）
7. `thePlayerManager.updatePlayerInstances()`；村庄 tick + `villageSiege.tick()`；`worldTeleporter.removeStalePortalLocations(...)`
8. `sendQueuedBlockEvents()`（双缓冲队列翻转）

随后 `MinecraftServer` 调 `worldserver.updateEntities()`（MinecraftServer.java:781）：无玩家超过 1200 tick 后跳过（WorldServer.java:528-534），否则进入 `World.updateEntities()`（World.java:1619）——顺序为 weatherEffects → 处理 unloadedEntityList → loadedEntityList 逐个 `updateEntity`（内部 `entity.onUpdate()` 并做 NaN 坐标复位与跨区块迁移，World.java:1855）→ tickableTileEntities（仅 `isBlockLoaded && worldBorder.contains` 才 `((ITickable)tileentity).update()`，World.java:1743-1747）→ 合并 addedTileEntityList。

**客户端**：`Minecraft.runTick` 每 tick 调 `this.theWorld.updateEntities()`（Minecraft.java:2202）；`WorldClient extends World` 覆盖网络相关行为。渲染（每帧）不走本包的 tick 路径，但 `RenderGlobal implements IWorldAccess` 通过 `markBlockForUpdate`/`markBlockRangeForRenderUpdate` 收到重建通知，并经 `RegionRenderCache extends ChunkCache` 在 chunk builder 线程读取世界快照。

**线程归属**：`World`/`WorldServer` 的一切可变操作属主线程（客户端 = 主渲染线程，集成服务端 = Server thread）。Netty EventLoop 收到的封包必须经 `WorldServer#addScheduledTask`（IThreadListener，WorldServer.java:1169）调回服务端线程。`WorldBorder.getDiameter()` 基于 `System.currentTimeMillis()`，任意线程读是近似安全的，但 setter 会遍历 listeners，不可跨线程调。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void updateEntities()` | World.java:1619 | 每 game tick（Minecraft.java:2202 / MinecraftServer.java:781） | 实体/TileEntity tick 前后插桩、性能统计、实体过滤 | 循环内 remove 用索引回退技巧，插入逻辑勿破坏迭代 |
| `public void updateEntity(Entity ent)` / `public void updateEntityWithOptionalForce(Entity entityIn, boolean forceUpdate)` | World.java:1846 / World.java:1855 | 每实体每 tick | 单实体 tick 拦截（freecam、实体冻结、反作弊观测） | WorldServer 覆盖后者会杀死动物/NPC（WorldServer.java:690），注意 super 链 |
| `public boolean setBlockState(BlockPos pos, IBlockState newState, int flags)` | World.java:345 | 一切方块写入（破坏/放置/生成/封包） | 方块变更监听、幽灵方块修正、schematic 记录 | flags 语义：1 邻居更新 2 发客户端 4 不重渲染；client 上 DEBUG_WORLD 例外分支 |
| `public IBlockState getBlockState(BlockPos pos)` | World.java:850 | 极高频（每帧/每 tick 海量） | X-Ray、方块替换视觉 | 热路径，加逻辑必须 O(1)；越界返回 air |
| `public boolean spawnEntityInWorld(Entity entityIn)` | World.java:1149 | 实体入世（含玩家） | 实体出现通知、过滤 | 玩家与 forceSpawn 绕过区块加载检查 |
| `public void removeEntity(Entity entityIn)` | World.java:1199 | 实体标记移除 | 实体消失通知 | 真正移除延迟到下个 updateEntities |
| `protected void onEntityAdded(Entity entityIn)` / `protected void onEntityRemoved(Entity entityIn)` | World.java:1180 / World.java:1188 | 实体增删的统一汇点 | 比 spawn/remove 更全的实体生命周期钩子 | WorldServer 覆盖维护 entitiesById/entitiesByUuid（WorldServer.java:945/961） |
| `public void addWorldAccess(IWorldAccess worldAccess)` | World.java:1249 | RenderGlobal（RenderGlobal.java:474）与 WorldManager（MinecraftServer.java:338）注册 | **无需字节码改动即可订阅世界事件**：注册自定义 IWorldAccess 收方块更新/音效/粒子/实体增删 | 回调在主线程；勿在回调里再改世界结构 |
| `public MovingObjectPosition rayTraceBlocks(Vec3 vec31, Vec3 vec32, boolean stopOnLiquid, boolean ignoreBlockWithoutBoundingBox, boolean returnLastUncollidableBlock)` | World.java:888 | 玩家准星拾取、投掷物碰撞、爆炸密度 | Reach 类功能、命中修改 | 200 步上限；NaN 输入返回 null |
| `public List<AxisAlignedBB> getCollidingBoundingBoxes(Entity entityIn, AxisAlignedBB bb)` | World.java:1262 | 实体移动物理每 tick | 碰撞体过滤（Phase/NoClip 类功能） | 内含边界外用 stone 填充与 setOutsideBorder 副作用 |
| `public void playSoundAtEntity(Entity entityIn, String name, float volume, float pitch)` / `public void playSoundEffect(double x, double y, double z, String soundName, float volume, float pitch)` | World.java:1072 / World.java:1096 | 各类游戏事件发声 | 声音事件监听（音效 ESP）、静音 | 仅转发给 worldAccesses，客户端实际出声在 RenderGlobal |
| `public void tick()` | WorldServer.java:166 | 服务端每 tick | 整世界 tick 前后插桩、时间流速控制 | 覆盖需保持 profiler 段配对 |
| `public boolean tickUpdates(boolean p_72955_1_)` | WorldServer.java:554 | WorldServer.tick "tickPending" 段 | 计划 tick 观测/节流 | HashSet 与 TreeSet 不同步会抛 IllegalStateException（:566） |
| `public void updateBlockTick(BlockPos pos, Block blockIn, int delay, int priority)` | WorldServer.java:463 | 方块请求计划 tick（液体流动、红石等） | 计划 tick 拦截 | `scheduledUpdatesAreImmediate` 分支会同步执行 updateTick |
| `protected void updateBlocks()` | WorldServer.java:339 | WorldServer.tick "tickBlocks" 段 | 随机 tick / 天气效果（结冰降雪雷击）拦截 | randomTickSpeed=0 可整体关掉随机 tick |
| `public Explosion newExplosion(Entity entityIn, double x, double y, double z, float strength, boolean isFlaming, boolean isSmoking)` | World.java:2218 / WorldServer.java:1004 | TNT/苦力怕/床等引爆 | 爆炸取消、防爆区、伤害改写 | 服务端版本额外发 S27PacketExplosion（WorldServer.java:1019） |
| `public void doExplosionA()` / `public void doExplosionB(boolean spawnParticles)` | Explosion.java:72 / Explosion.java:174 | newExplosion 内部两阶段 | 拆分拦截：A 管伤害/击退，B 管方块与视效 | 客户端收包后单独重放 B，两端行为需一致 |
| `public void addBlockEvent(BlockPos pos, Block blockIn, int eventID, int eventParam)` | WorldServer.java:1026 | 活塞/音符盒/箱子开合等 | 方块事件监听 | 双缓冲队列在 tick 末 sendQueuedBlockEvents 排空 |
| `public boolean checkLightFor(EnumSkyBlock lightType, BlockPos pos)` | World.java:2836 | 方块变更后光照重算 | 光照 hack（Fullbright 更宜改 lightBrightnessTable） | BFS 需 17 格范围已加载否则直接 false |
| `public void setTransition(double oldSize, double newSize, long time)` 等 WorldBorder setter | border/WorldBorder.java:180 | /worldborder 命令、封包同步 | 边界事件监听（addListener 即可，无需改代码） | getDiameter 用墙钟毫秒，暂停进程会导致跳变 |
| `public int findChunksForSpawning(WorldServer worldServerIn, boolean spawnHostileMobs, boolean spawnPeacefulMobs, boolean p_77192_4_)` | SpawnerAnimals.java:29 | WorldServer.tick "mobSpawner" 段 | 刷怪控制/统计 | 反射构造实体，异常只 printStackTrace 后提前 return |
| `public void placeInPortal(Entity entityIn, float rotationYaw)` | Teleporter.java:32 | 跨维度传送落点 | 传送落点改写 | 末地分支会直接硬改方块建平台 |
| `Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)` / `String getGuiID()` | IInteractionObject.java:9/11 | 玩家交互可开 GUI 对象时（displayGUIChest 路径） | 自定义容器 GUI 注入点 | GuiID 字符串在客户端映射具体 Gui 类 |
| `public ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule)` | WorldServer.java:1169 | Netty EventLoop 需要改世界时 | 把自己的异步任务安全调回服务端线程 | 唯一合法的跨线程写世界通道 |

`IWorldAccess`（IWorldAccess.java:7-50）本身就是官方的观察者接口，12 个回调（`markBlockForUpdate`、`notifyLightSet`、`markBlockRangeForRenderUpdate`、`playSound`、`playSoundToNearExcept`、`spawnParticle`、`onEntityAdded`、`onEntityRemoved`、`playRecord`、`broadcastSound`、`playAuxSFX`、`sendBlockBreakProgress`）覆盖了功能层最常见的观察需求，优先用 `addWorldAccess` 注册而不是改 World 源码。

## 数据与协议

**GameRules NBT**（GameRules.java:76-99）：

| 字段名 | 类型 | 读写方法 | 取值含义 |
|---|---|---|---|
| （每条规则名作为键） | NBT String | `writeToNBT()` / `readFromNBT(NBTTagCompound nbt)` | 规则字符串值；读入时 `setOrCreateGameRule` 未知规则按 ANY_VALUE 创建 |

默认规则（GameRules.java:13-27）：`doFireTick` `mobGriefing` `keepInventory` `doMobSpawning` `doMobLoot` `doTileDrops` `doEntityDrops` `commandBlockOutput` `naturalRegeneration` `doDaylightCycle` `logAdminCommands` `showDeathMessages` `sendCommandFeedback` `reducedDebugInfo`（均 BOOLEAN_VALUE，默认见源码）与 `randomTickSpeed`=3（NUMERICAL_VALUE）。

**LockCode NBT**（LockCode.java:25-41）：

| 字段名 | 类型 | 读写方法 | 取值含义 |
|---|---|---|---|
| `Lock` | NBT String (type 8) | `toNBT(NBTTagCompound nbt)` / `static fromNBT(NBTTagCompound nbt)` | 空串/缺失 = `EMPTY_CODE` 未上锁；否则需手持同名物品才能打开 |

**WorldSavedData**（WorldSavedData.java:21-26）：抽象契约 `public abstract void readFromNBT(NBTTagCompound nbt)` / `public abstract void writeToNBT(NBTTagCompound nbt)`，配合 `mapName` 由 `MapStorage` 存到 `data/<mapName>.dat`；`markDirty()` 后在 `WorldServer.saveLevel` 的 `mapStorage.saveAllData()`（WorldServer.java:942）落盘。

**本包直接构造/发送的封包**（均服务端 → 客户端）：
- `S27PacketExplosion`（WorldServer.java:1019）：爆炸坐标、strength、受影响方块列表、该玩家的击退向量。
- `S24PacketBlockAction`（WorldServer.java:1052）：方块事件 pos/block/eventID/eventParameter，64 格内广播。
- `S2BPacketChangeGameState`（WorldServer.java:1084-1104；DemoWorldManager.java:34/55/59/63）：state 1/2 雨起/雨停、7/8 雨/雷强度、5 演示模式提示。
- `S2CPacketSpawnGlobalEntity`（WorldServer.java:984）：闪电，512 格广播。
- `S2APacketParticles`（WorldServer.java:1149）：粒子，默认 256 平方距离内（longDistance 65536）。
- `S19PacketEntityStatus`（WorldServer.java:998）：实体状态字节。
- WorldManager 转发：`S29PacketSoundEffect`（WorldManager.java:54/62）、`S28PacketEffect`（:88/:93）、`S25PacketBlockBreakAnim`（:108，32 格平方 1024 内、排除破坏者本人）。

**WorldBorder 持久化**：经 `WorldInfo`（`setBorderSize`/`setBorderLerpTarget`/`setBorderLerpTime` 等，WorldServer.java:932-940）写入 level.dat；`init()`（WorldServer.java:145-158）恢复。

## 不变量与陷阱

- **主线程约束**：所有世界写操作必须在拥有该世界的线程；异步来源（Netty 收包、其它线程）只能走 `WorldServer#addScheduledTask`。`loadedEntityList`/`tickableTileEntities` 均是裸 ArrayList，无任何同步。
- **isRemote 分叉**：`World.isRemote == true` 是客户端从属世界，`setBlockState` 的邻居通知（flags&1）与 `notifyBlockOfStateChange` 在 remote 世界被跳过（World.java:381/531）；写功能代码必须区分两端。
- **TileEntity 迭代保护**：`processingLoadedTiles` 为 true 期间（updateEntities 的 blockEntities 段），`setTileEntity`/`addTileEntities` 会转入 `addedTileEntityList` 延迟合并（World.java:2356/1825）；直接改 `loadedTileEntityList` 会 CME。
- **计划 tick 双集合不变量**：`pendingTickListEntriesHashSet.size() == pendingTickListEntriesTreeSet.size()` 必须恒成立，`tickUpdates` 开头显式检查并抛 `IllegalStateException("TickNextTick list out of synch")`（WorldServer.java:564-566）。
- **坐标合法域**：`World.isValid` 限定 |x|,|z| < 30000000 且 0 ≤ y < 256（World.java:240-243）；越界 getBlockState 返回 air 而不是异常，容易掩盖 bug。
- **WorldProvider.getWorldBorder() 每次 new**（WorldProvider.java:281）：只有 World 构造时取的那份被缓存（World.java:164）；不要再直接调 provider 的版本，否则拿到的是无人监听的孤儿实例。
- **爆炸两端一致性**：服务端 `doExplosionB(false)` 不出粒子，靠 `S27PacketExplosion` 让客户端重放；若只改服务端爆炸逻辑而不改客户端处理，视觉与实际会分裂。
- **WorldBorder 用墙钟**：`getDiameter()` 依赖 `System.currentTimeMillis()`（WorldBorder.java:144），调试断点/系统睡眠会让边界瞬移；JDK 25 下行为不变但值得留意。
- **ColorizerGrass 的越界判断是 `k > grassBuffer.length`**（ColorizerGrass.java:22）——用 `>` 而非 `>=`，`k == length` 仍会越界抛 AIOOBE；这是原版遗留行为，替换色表时保证 65536 长度。
- **EnumDifficulty.getDifficultyEnum 用取模**（EnumDifficulty.java:27）：负数 id 会崩（负模），封包侧应保证非负。
- **SpawnerAnimals 反射构造**：实体类必须有 `(World)` 单参构造器（SpawnerAnimals.java:131），异常仅 printStackTrace 并中止本轮刷怪。
- **LWJGL3/JDK25 移植**：本包无窗口/GL 依赖，移植改动极小；确认到的唯一标注改动是 `World.entitiesById` 加了钻石泛型（World.java:72 注释 "Added type inference"）。仍用 log4j（WorldServer.java:76）与 Guava `ListenableFuture`（WorldServer.java:7）。`GameRules.theGameRules`、`Teleporter.destinationCoordinateCache` 等仍是原始泛型 new（`new TreeMap()`/`new LongHashMap()`），编译告警属正常。
- **DEBUG_WORLD 特例**：非 remote 的 DEBUG_WORLD 禁止 setBlockState（World.java:351）、tickUpdates 直接 false（WorldServer.java:556）、updateBlocks 走精简分支（WorldServer.java:343）。

## 交叉引用

- `net.minecraft.client` → `Minecraft#runTick` 调 `World#updateEntities`（Minecraft.java:2202）
- `net.minecraft.client.multiplayer` → `WorldClient extends World`（WorldClient.java:38）
- `net.minecraft.client.renderer` → `RenderGlobal implements IWorldAccess`（RenderGlobal.java:89，:474 处 `addWorldAccess(this)`）；`RegionRenderCache extends ChunkCache`（RegionRenderCache.java:13）
- `net.minecraft.client.resources` → `GrassColorReloadListener` / `FoliageColorReloadListener` 调 `ColorizerGrass#setGrassBiomeColorizer` / `ColorizerFoliage#setFoliageBiomeColorizer`（各自 :16）
- `net.minecraft.server` → `MinecraftServer#loadAllWorlds` 构造 `WorldServer/WorldServerMulti/DemoWorldServer` 并挂 `WorldManager`（MinecraftServer.java:324-338）；`MinecraftServer#updateTimeLightAndEntities` 调 `WorldServer#tick`、`WorldServer#updateEntities`（:770/:781）
- `net.minecraft.server.management` → `PlayerManager#updatePlayerInstances`、`ItemInWorldManager`（DemoWorldManager 的父类）
- `net.minecraft.world.chunk` → `World#getChunkFromChunkCoords` → `IChunkProvider#provideChunk`；`WorldServer#createChunkProvider` → `ChunkProviderServer`
- `net.minecraft.world.gen` → `WorldProvider#createChunkGenerator` → `ChunkProviderGenerate/Flat/Debug/Hell/End`
- `net.minecraft.world.biome` → `WorldProvider#registerWorldChunkManager` → `WorldChunkManager/WorldChunkManagerHell`
- `net.minecraft.world.storage` → `World#worldInfo`（WorldInfo）、`saveHandler`（ISaveHandler）、`mapStorage`（MapStorage）；`WorldServerMulti` 用 `DerivedWorldInfo`
- `net.minecraft.pathfinding` → `PathNavigate` 构造 `ChunkCache`（PathNavigate.java:93/132）；`PathPoint` 被 `NodeProcessor` 家族使用
- `net.minecraft.block` → `Block#updateTick/randomTick/onNeighborBlockChange/onBlockEventReceived`（由 World/WorldServer 触发）
- `net.minecraft.entity` → `Entity#onUpdate`（World#updateEntityWithOptionalForce 触发）、`EntityTracker`（WorldManager#onEntityAdded → `trackEntity`）
- `net.minecraft.village` → `VillageCollection#tick`、`VillageSiege#tick`（WorldServer.tick "village" 段）
- `net.minecraft.scoreboard` → `ServerScoreboard`/`ScoreboardSaveData`（WorldServer.init）
- `net.minecraft.network.play.server` → 见"数据与协议"节列出的 8 类封包

## 覆盖声明

完整读取了 38/38 个文件（World.java 分三段全量读完；其余 37 个文件单次全文读取）。

逐行精读：World、WorldServer、Explosion、SpawnerAnimals、Teleporter、WorldProvider（含三个子类）、WorldBorder、GameRules、ChunkCache、WalkNodeProcessor、WorldManager、WorldServerMulti、NextTickListEntry。

结构性浏览（全文已读但未逐行推演逻辑细节）：ChunkCoordIntPair、ColorizerFoliage、ColorizerGrass、DifficultyInstance、EnumDifficulty、EnumSkyBlock、各 interface（IBlockAccess/IInteractionObject/ILockableContainer/IWorldAccess/IWorldNameable）、LockCode、MinecraftException、WorldSavedData、WorldSettings、WorldType、EnumBorderStatus、IBorderListener、demo 包两个类、NodeProcessor、SwimNodeProcessor。

行号引用均来自本仓库当前源码的 Read 输出；跨包引用（Minecraft.java、MinecraftServer.java、RenderGlobal.java、RegionRenderCache.java、PathNavigate.java、WorldClient.java、两个 ColorReloadListener）已用 grep 逐一核实。
