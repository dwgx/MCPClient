---
area: net/minecraft/world/chunk
slug: mc-world-chunk
files: 14
lines: 3805
tier: B
---

# net/minecraft/world/chunk

## 定位

本包是世界数据的**物理存储层**：一个 `Chunk` 即一根 16x256x16 的柱子，内部按 16 个 `ExtendedBlockStorage`（16x16x16 段）竖直堆叠，存方块状态、天光/方块光（`NibbleArray`）、高度图、生物群系、实体列表和 TileEntity 映射。`storage` 子包负责 Anvil（.mca）磁盘格式的读写（`AnvilChunkLoader` / `RegionFile` / `RegionFileCache`）以及旧 McRegion 格式的转换（`AnvilSaveConverter` / `ChunkLoader` / `NibbleArrayReader`）。

上游调用方：`World` / `WorldServer` 的所有 getBlockState/setBlockState 最终落到 `Chunk`；`ChunkProviderClient`（多人客户端）与 `ChunkProviderServer`（单机集成服务端）实现本包的 `IChunkProvider` 接口来产出/回收 Chunk；`NetHandlerPlayClient` 收到 S21/S26 区块包后调用 `Chunk#fillChunk` 灌入网络数据；渲染层（`RenderChunk` 重建）通过 `Chunk#getBlockState` 读取。下游依赖：`net.minecraft.block`（`Block.BLOCK_STATE_IDS` 把 IBlockState 编成 char id）、`net.minecraft.nbt`（存盘）、`net.minecraft.world.storage`（`ThreadedFileIOBase` 异步写盘、`SaveHandler` 体系）。

如果这个包消失：世界没有任何方块数据可读写，光照系统失去存储介质，存档无法加载/保存，客户端收到区块包后无处安放——整个游戏世界层瘫痪。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| Chunk | 1706 | — | 16x256x16 区块柱：方块/光照/高度图/生物群系/实体/TileEntity 的容器与光照重算逻辑 |
| ChunkPrimer | 48 | — | 世界生成期的临时方块缓冲，`short[65536]` 存 block state id，供 `Chunk(World, ChunkPrimer, int, int)` 构造 |
| EmptyChunk | 182 | extends Chunk | 空占位区块：所有查询返回空气/默认值，所有写入为 no-op，`isEmpty()` 返回 true |
| IChunkProvider | 66 | interface | 区块提供者契约：`provideChunk` / `populate` / `saveChunks` / `unloadQueuedChunks` 等 |
| NibbleArray | 81 | — | 2048 字节的 4-bit 数组（4096 个 nibble），用于光照与元数据 |
| storage/AnvilChunkLoader | 483 | implements IChunkLoader, IThreadedFileIO | Chunk ↔ NBT 序列化，经 `ThreadedFileIOBase` 异步写入 RegionFile |
| storage/AnvilSaveConverter | 290 | extends SaveFormatOld | 存档列表枚举 + McRegion(.mcr, 19132) → Anvil(.mca, 19133) 格式转换 |
| storage/AnvilSaveHandler | 69 | extends SaveHandler | 按维度（DIM-1/DIM1）返回对应目录的 AnvilChunkLoader；flush 时等待 IO 线程并清 RegionFile 缓存 |
| storage/ChunkLoader | 154 | — | 静态工具：旧格式 NBT → `AnvilConverterData` → Anvil 分段 NBT（仅转换路径使用） |
| storage/ExtendedBlockStorage | 223 | — | 16x16x16 段：`char[4096]` 方块状态 + 两个 NibbleArray 光照 + 非空/随机 tick 计数 |
| storage/IChunkLoader | 33 | interface | 区块磁盘加载器契约：`loadChunk` / `saveChunk` / `chunkTick` 等 |
| storage/NibbleArrayReader | 23 | — | 旧格式（深度 128）nibble 数组的只读访问器，仅转换路径使用 |
| storage/RegionFile | 365 | — | 单个 .mca/.mcr 文件：1024 项偏移表 + 时间戳表 + 4KiB 扇区分配，gzip/deflate 压缩块 |
| storage/RegionFileCache | 82 | — | `Map<File, RegionFile>` 静态缓存（上限 256），提供按区块坐标取输入/输出流的静态入口 |

## 核心类详解

### Chunk（Chunk.java）

关键字段（Chunk.java:46-105）：
- `private final ExtendedBlockStorage[] storageArrays` — 16 个竖直段，null 段表示全空气（:46）
- `private final byte[] blockBiomeArray` — 256 项生物群系 id，255 表示未定（:51）
- `private final int[] precipitationHeightMap` / `private final int[] heightMap` — 降水高度与光照高度图（:56, :66）
- `public final int xPosition; public final int zPosition`（:69, :72）
- `private final Map<BlockPos, TileEntity> chunkTileEntityMap`（:74）
- `private final ClassInheritanceMultiMap<Entity>[] entityLists` — 按 y>>4 分 16 层的实体索引（:75）
- `private boolean isModified` — 脏标记，决定 `needsSaving`（:85）
- `private int queuedLightChecks` — 轮转补光索引，初始 4096（即"已完成"）（:104）
- `private ConcurrentLinkedQueue<BlockPos> tileEntityPosQueue` — QUEUED 模式延迟创建 TileEntity 的队列（:105）

关键方法：
- `public IBlockState setBlockState(BlockPos pos, IBlockState state)`（Chunk.java:660）— 写方块的唯一入口。更新高度图/降水图、按需新建 `ExtendedBlockStorage`、触发 `relightBlock` / `propagateSkylightOcclusion`、处理旧 TileEntity 失效与新 TileEntity 创建、在服务端调 `block1.breakBlock` 与 `block.onBlockAdded`（:703, :756）。返回旧 state，无变化返回 null。由 `World#setBlockState` 调用。
- `public IBlockState getBlockState(final BlockPos pos)`（Chunk.java:586）— 读方块热路径；DEBUG_WORLD 有特判（:588）。
- `public void fillChunk(byte[] p_177439_1_, int p_177439_2_, boolean p_177439_3_)`（Chunk.java:1307）— 用网络字节流覆盖区块：按位掩码 `p_177439_2_` 逐段拷入 block data（char 小端拼装 :1325）、blocklight、skylight，`p_177439_3_`（groundUpContinuous）为 true 时再拷 biome 数组并允许清空段；末尾 `removeInvalidBlocks()`、置 `isLightPopulated = isTerrainPopulated = true`、`generateHeightMap()`。调用方：`NetHandlerPlayClient.java:764`（S21 单区块）与 `:1348`（S26 批量）。
- `public void onChunkLoad()`（Chunk.java:986）/ `public void onChunkUnload()`（Chunk.java:1005）— 加载时把 TileEntity/实体注册进 World，卸载时反注册。`ChunkProviderServer.java:140` 与 `ChunkProviderClient` 调用。
- `public void func_150804_b(boolean p_150804_1_)`（Chunk.java:1220）— 每 tick 一次的区块维护：补 gap 光照（`recheckGaps`）、若 terrain 已 populate 而 light 未 populate 则 `func_150809_p()` 做初始光照、消费 `tileEntityPosQueue` 延迟建 TileEntity 并 `markBlockRangeForRenderUpdate`（:1242）。
- `public void enqueueRelightChecks()`（Chunk.java:1439）— 每 tick 轮转推进最多 8 个索引位置的补光检查（4096 总量），由 `World.java:2666` 在玩家附近随机区块上调用。
- `public void generateSkylightMap()`（Chunk.java:246）— 生成/加载后重建天光柱。
- `public TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType p_177424_2_)`（Chunk.java:918）— 三种模式：`IMMEDIATE` 立即建、`QUEUED` 入队、`CHECK` 只查。
- `public void addEntity(Entity entityIn)`（Chunk.java:847）— 按 `posY/16` 分层插入 `entityLists`，写回 `entity.chunkCoordX/Y/Z`；坐标不匹配时 warn 并 `entityIn.setDead()`（:856）。
- `public boolean needsSaving(boolean p_76601_1_)`（Chunk.java:1093）— `hasEntities`+时间条件或 `isModified` 决定是否落盘。
- `public void populateChunk(IChunkProvider p_76624_1_, IChunkProvider p_76624_2_, int x, int z)`（Chunk.java:1120）— 检查 8 邻接区块是否存在，对满足 2x2 邻域的区块触发 `populate`（装饰阶段）。

### ExtendedBlockStorage（storage/ExtendedBlockStorage.java）

字段：`private int yBase`（:13）、`private int blockRefCount`（:18）、`private int tickRefCount`（:24）、`private char[] data`（:25，4096 项，索引 `y << 8 | z << 4 | x`）、`private NibbleArray blocklightArray / skylightArray`（:28, :31，无天空维度时 skylight 为 null）。

- `public IBlockState get(int x, int y, int z)`（:45）— `Block.BLOCK_STATE_IDS.getByValue(this.data[y << 8 | z << 4 | x])`，查不到回退空气。
- `public void set(int x, int y, int z, IBlockState state)`（:51）— 维护 `blockRefCount` / `tickRefCount` 后写入 `(char)Block.BLOCK_STATE_IDS.get(state)`（:77）。
- `public boolean isEmpty()`（:101）— `blockRefCount == 0`，渲染与 `getAreLevelsEmpty` 靠它跳过空段。
- `public void removeInvalidBlocks()`（:155）— 全量重算两个计数；`fillChunk` 与 NBT 读取后必须调用。

### AnvilChunkLoader（storage/AnvilChunkLoader.java）

字段：`private Map<ChunkCoordIntPair, NBTTagCompound> chunksToRemove = new ConcurrentHashMap()`（:35）、`private Set<ChunkCoordIntPair> pendingAnvilChunksCoordinates`（:36）、`private final File chunkSaveLocation`（:39）。

- `public Chunk loadChunk(World worldIn, int x, int z) throws IOException`（:50）— 先查 `chunksToRemove`（待写队列里的最新数据），否则 `RegionFileCache.getChunkInputStream` + `CompressedStreamTools.read`，再 `checkedReadChunkFromNBT`（:73，校验 "Level"/"Sections" 键并纠正坐标错位）。
- `public void saveChunk(World worldIn, Chunk chunkIn) throws MinecraftException, IOException`（:106）— 同步序列化成 NBT 后 `addChunkToPending`（:124），由 `ThreadedFileIOBase.getThreadedIOInstance().queueIO(this)`（:131）异步刷盘。
- `public boolean writeNextIO()`（:137）— IO 线程回调：从 `chunksToRemove` 取一个写入 RegionFile；返回 false 表示队列空。
- `private void writeChunkToNBT(Chunk chunkIn, World worldIn, NBTTagCompound p_75820_3_)`（:231）/ `private Chunk readChunkFromNBT(World worldIn, NBTTagCompound p_75823_2_)`（:357）— 见"数据与协议"表。

### RegionFile（storage/RegionFile.java）

字段：`private final int[] offsets = new int[1024]`（:23，`(sectorStart << 8) | sectorCount`）、`private final int[] chunkTimestamps = new int[1024]`（:24）、`private List<Boolean> sectorFree`（:25）。构造器（:31）把文件补齐到 8KiB 头 + 4KiB 对齐并载入两张表。

- `public synchronized DataInputStream getChunkDataInputStream(int x, int z)`（:109）— 读扇区，按版本字节 1=gzip、2=deflate 解压（:151-162），越界/损坏返回 null。
- `public DataOutputStream getChunkDataOutputStream(int x, int z)`（:181）— 返回 `DeflaterOutputStream(ChunkBuffer)`；内部类 `ChunkBuffer extends ByteArrayOutputStream` 在 `close()` 时回调 `RegionFile.this.write(chunkX, chunkZ, buf, count)`（:362）——**数据在流关闭时才落盘**。
- `protected synchronized void write(int x, int z, byte[] data, int length)`（:189）— 扇区复用/首次适配分配/追加扩容三分支；`l >= 256`（约 1MiB）直接丢弃返回（:198）。写入格式恒为版本 2（deflate，:289）。

### RegionFileCache（storage/RegionFileCache.java）

- `public static synchronized RegionFile createOrLoadRegionFile(File worldDir, int chunkX, int chunkZ)`（:14）— 以 `r.<x>>5.<z>>5.mca` 为键缓存，超过 256 个时 `clearRegionFileReferences()` 全清（:31）。
- `public static synchronized void clearRegionFileReferences()`（:45）— 关闭并清空所有句柄；`AnvilSaveHandler#flush`（AnvilSaveHandler.java:67）与 `AnvilSaveConverter#flushCache`（AnvilSaveConverter.java:90）调用。
- `public static DataInputStream getChunkInputStream(File worldDir, int chunkX, int chunkZ)`（:68）/ `getChunkOutputStream`（:77）— 注意这两个**没有** synchronized，只有内部的 `createOrLoadRegionFile` 有锁。

### NibbleArray（NibbleArray.java）

- 构造 `public NibbleArray(byte[] storageArray)`（:16）强校验长度必须 2048，否则 `IllegalArgumentException`（:22）。
- `public int get(int x, int y, int z)`（:29）/ `public void set(int x, int y, int z, int value)`（:37），索引 `y << 8 | z << 4 | x`（:44），偶索引取低 4 位。

### ChunkPrimer（ChunkPrimer.java）

- `private final short[] data = new short[65536]`（:9），索引 `x << 12 | z << 8 | y`（:14）——注意与 ExtendedBlockStorage 的 `y<<8|z<<4|x` **不同**。
- `public IBlockState getBlockState(int index)`（:18）/ `public void setBlockState(int index, IBlockState state)`（:37），越界抛 `IndexOutOfBoundsException`。世界生成器（ChunkProviderGenerate 等）先写 primer，再由 `Chunk(World worldIn, ChunkPrimer primer, int x, int z)`（Chunk.java:131）一次性转成分段存储。

### EmptyChunk（EmptyChunk.java）

`ChunkProviderClient` 用它做"未加载区域"的哨兵对象：`getBlock` 恒返回 `Blocks.air`（:52）、`getBlockLightOpacity` 恒 255（:57）、`needsSaving` 恒 false（:159）、`isEmpty()` 恒 true（:169）。所有变更方法为空实现，因此对未加载区域的写入静默丢弃。

## 时序与生命周期

- **单机加载路径（服务端线程）**：`ChunkProviderServer.provideChunk` → `AnvilChunkLoader.loadChunk`（同步读盘+解 NBT）→ `chunk.onChunkLoad()`（Chunk.java:986，注册实体/TileEntity）→ 邻域齐后 `Chunk#populateChunk`（:1120）触发装饰。
- **多人加载路径（客户端主线程）**：S21/S26 包经 Netty EventLoop 收到后，`PacketThreadUtil` 调度回客户端主线程，`NetHandlerPlayClient` 调 `ChunkProviderClient.loadChunk` 新建空 Chunk，再 `chunk.fillChunk(...)`（NetHandlerPlayClient.java:764, 1348）灌数据。**fillChunk 始终在主线程执行**。
- **每 tick**：`WorldServer` 对活跃区块调 `chunk.func_150804_b(false)`（WorldServer.java:347, 363）；客户端 `ChunkProviderClient.unloadQueuedChunks` 对每个已加载 Chunk 调 `chunk.func_150804_b(System.currentTimeMillis() - i > 5L)`（ChunkProviderClient.java:115，超 5ms 就让 `recheckGaps` 提前退出）。另外 `World.java:2666` 在玩家周边随机区块上调 `enqueueRelightChecks()`。`IChunkLoader#chunkTick`（IChunkLoader.java:26）每次 `World.tick()` 被调，但 `AnvilChunkLoader` 实现为空（AnvilChunkLoader.java:199）。
- **每帧**：本包无每帧逻辑；渲染线程只经 `RenderChunk` 重建时读 `getBlockState`。
- **保存路径**：主/服务端线程 `saveChunk` 序列化 → 入 `chunksToRemove` → `ThreadedFileIOBase` 专用 IO 线程反复调 `writeNextIO()` 落盘。`AnvilSaveHandler.flush()`（AnvilSaveHandler.java:56）阻塞等待 IO 线程清空后关闭所有 RegionFile。
- **线程归属**：Chunk 本体与 ExtendedBlockStorage 无同步，只能在拥有该 World 的线程（客户端主线程或集成服务端线程）访问；AnvilChunkLoader 的两个集合是并发容器，横跨游戏线程与 IO 线程；RegionFile 读写方法 `synchronized`；RegionFileCache 静态方法 `synchronized`（但见"陷阱"）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public IBlockState setBlockState(BlockPos pos, IBlockState state)` | Chunk.java:660 | World#setBlockState 的最终落点，任何方块变更 | 拦截/观察所有方块写入（矿物记录、幽灵方块、区块回放）；返回 null 表示"未变更" | 内部触发光照重算与 TileEntity 生命周期，勿在钩子里递归 setBlockState；仅限主线程 |
| `public IBlockState getBlockState(final BlockPos pos)` | Chunk.java:586 | 渲染重建、碰撞、光照等一切读方块 | X-Ray/替换视觉方块（改返回值）、缓存 | 极热路径，任何开销都会放大；DEBUG_WORLD 分支在前 |
| `public void fillChunk(byte[] p_177439_1_, int p_177439_2_, boolean p_177439_3_)` | Chunk.java:1307 | 客户端收到 S21PacketChunkData / S26PacketMapChunkBulk（NetHandlerPlayClient.java:764, 1348） | 观察服务器下发的区块数据（NewChunks 类功能）、修改进入客户端的方块 | 数据是裸字节流，段掩码决定布局；结束后会覆盖 heightMap 与 populate 标记 |
| `public void onChunkLoad()` | Chunk.java:986 | ChunkProviderServer.java:140 / ChunkProviderClient 加载区块时 | 区块加载事件（雷达、实体扫描的天然触发点） | 此时实体/TileEntity 刚注册进 World |
| `public void onChunkUnload()` | Chunk.java:1005 | 区块被 provider 卸载时 | 区块卸载事件；清理与该区块绑定的功能状态 | 之后 `isLoaded()` 为 false，写入会被 removeTileEntity 忽略 |
| `public void func_150804_b(boolean p_150804_1_)` | Chunk.java:1220 | 每 tick 每活跃区块（WorldServer.java:347; ChunkProviderClient.java:115） | 区块级 per-tick 钩子；观察延迟 TileEntity 创建 | 参数 true 表示"跳过 gap 补光"（客户端超时降级） |
| `public void addEntity(Entity entityIn)` / `public void removeEntity(Entity entityIn)` | Chunk.java:847 / 881 | 实体跨入区块、World.updateEntity 迁移时 | 实体进入/离开区块的观察点（比遍历 loadedEntityList 便宜） | addEntity 会 setDead 坐标不符的实体；y 层被 clamp 到 0..15 |
| `public TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType p_177424_2_)` | Chunk.java:918 | World#getTileEntity 及渲染/交互 | 伪造/隐藏 TileEntity | CHECK 模式下惰性移除 invalid 项，有副作用 |
| `public void addTileEntity(BlockPos pos, TileEntity tileEntityIn)` | Chunk.java:953 | 方块放置、区块加载、S35 包更新 | TileEntity 创建观察（箱子 ESP 等） | 若该位置方块非 ITileEntityProvider 则静默不加 |
| `public int getLightFor(EnumSkyBlock p_177413_1_, BlockPos pos)` / `public void setLightFor(EnumSkyBlock p_177431_1_, BlockPos pos, int value)` | Chunk.java:781 / 790 | 渲染取亮度、光照引擎写回 | 全亮（Fullbright 的底层实现位置之一）、光照调试 | setLightFor 可能新建段并触发 generateSkylightMap |
| `public void getEntitiesWithinAABBForEntity(Entity entityIn, AxisAlignedBB aabb, List<Entity> listToFill, Predicate <? super Entity > p_177414_4_)` | Chunk.java:1031 | World#getEntitiesInAABBexcluding（碰撞、攻击索敌） | 过滤/注入碰撞候选实体 | 热路径；注意 getParts() 多部分实体分支 |
| `public boolean needsSaving(boolean p_76601_1_)` | Chunk.java:1093 | 自动保存扫描 | 强制/抑制区块落盘 | isModified 由众多路径置位 |
| `public void enqueueRelightChecks()` | Chunk.java:1439 | World.java:2666 每 tick 随机区块 | 加速/禁用轮转补光 | 每次最多 8 索引，全量约 25.6s |
| `Chunk provideChunk(int x, int z)`（接口） | IChunkProvider.java:21 | 一切按坐标取区块的路径 | 在 provider 实现处包一层可拦截所有区块获取 | 客户端实现对未加载坐标返回 EmptyChunk 单例语义 |
| `public Chunk loadChunk(World worldIn, int x, int z) throws IOException` | AnvilChunkLoader.java:50 | ChunkProviderServer 缺区块时（单机） | 存档读取观察、区块数据迁移 | 同步 IO，阻塞服务端线程 |
| `public void saveChunk(World worldIn, Chunk chunkIn) throws MinecraftException, IOException` | AnvilChunkLoader.java:106 | 自动保存 / 卸载 / 退出世界 | 备份、导出区块 | 序列化在调用线程，写盘在 IO 线程 |
| `public boolean writeNextIO()` | AnvilChunkLoader.java:137 | ThreadedFileIOBase IO 线程循环 | 观察落盘进度 | 非游戏线程，勿碰 World |
| `public IChunkLoader getChunkLoader(WorldProvider provider)` | AnvilSaveHandler.java:22 | WorldServer.java:720 建 provider 时 | 替换整个存储后端（自定义格式） | 每维度目录不同（DIM-1 / DIM1） |

## 数据与协议

### Anvil 区块 NBT（"Level" compound；写 AnvilChunkLoader.java:231 `writeChunkToNBT`，读 :357 `readChunkFromNBT`）

| 字段名 | 类型 | 读/写方法 | 取值含义 |
|---|---|---|---|
| V | byte | 写 :233 | 版本标记，恒 1（读侧不校验） |
| xPos / zPos | int | 写 :234-235 / 读 :359-360 | 区块坐标；不匹配时 checkedReadChunkFromNBT 改写后重读（:96-98） |
| LastUpdate | long | 写 :236 | `worldIn.getTotalWorldTime()`（读侧本类未使用） |
| HeightMap | int[256] | 写 :237 / 读 :362 | 光照高度图 |
| TerrainPopulated / LightPopulated | boolean | 写 :238-239 / 读 :363-364 | 装饰/光照完成标记 |
| InhabitedTime | long | 写 :240 / 读 :365 | 玩家累计驻留 tick（区域难度输入） |
| Sections | list(compound) | 写 :299 / 读 :366 | 每个非空 16³ 段一项，见下表 |
| Biomes | byte[256] | 写 :300 / 读 :404-407（有键才读） | 生物群系 id |
| Entities | list(compound) | 写 :318 / 读 :409（含 "Riding" 链递归挂载 :424） | 实体 NBT |
| TileEntities | list(compound) | 写 :328 / 读 :440 | `TileEntity.createAndLoadEntity` 还原 |
| TileTicks | list(compound) | 写 :349 / 读 :456 | 计划 tick：`i`(方块名 string，兼容旧 int id :467-474)、`x/y/z`、`t`(相对延迟)、`p`(优先级) |

### Sections 单段（写 :250-295，读 :373-399）

| 字段名 | 类型 | 含义 |
|---|---|---|
| Y | byte | 段索引（yBase >> 4） |
| Blocks | byte[4096] | 方块 id 低 8 位（char 的 bit4-11） |
| Add | byte[2048]，可选 | 方块 id 高 4 位（char 的 bit12-15），全 0 时省略 |
| Data | byte[2048] | 元数据 nibble（char 的 bit0-3） |
| BlockLight | byte[2048] | 方块光 |
| SkyLight | byte[2048] | 天光；无天空维度写等长全 0 数组（:292） |

内存中 `char` 编码：`block_state_id = add << 12 | blockId << 4 | meta`，与 `Block.BLOCK_STATE_IDS` 互查（读侧拼装 AnvilChunkLoader.java:387）。

### fillChunk 网络字节布局（Chunk.java:1307，来源 S21/S26 包）

按段掩码低位到高位依次：每个置位段 4096 个小端 char 的 block data（:1325）→ 每段 2048 字节 BlockLight →（有天空时）每段 2048 字节 SkyLight →（groundUpContinuous 时）256 字节 Biomes（:1360）。

### RegionFile（.mca/.mcr）物理格式（RegionFile.java）

| 区域 | 大小 | 含义 |
|---|---|---|
| 偏移表 | 4096 B（1024 x int） | 每项 `(sectorStart << 8) \| sectorCount`，0 = 未保存（:85, :314） |
| 时间戳表 | 4096 B（1024 x int） | 秒级 Unix 时间（:274） |
| 数据扇区 | N x 4096 B | 每块前缀 `int length`（含版本字节）+ `byte version`（1=gzip, 2=deflate），本版本恒写 2（:288-290） |

索引 `x + z * 32`，x/z 为区块坐标对 32 取模；文件名 `r.<chunkX>>5.<chunkZ>>5.mca`（RegionFileCache.java:17）。

## 不变量与陷阱

- `storageArrays[i] == null` 与"全空气段"等价，读侧到处依赖这个约定回退 `Blocks.air`；`setBlockState` 对 null 段写空气会直接返回 null 不建段（Chunk.java:688-690）。
- `ExtendedBlockStorage.blockRefCount / tickRefCount` 必须与 `data` 一致；任何绕过 `set()` 直改 `data`（如 `setData`、`fillChunk` 直接写 `getData()` 返回的数组）之后**必须**调 `removeInvalidBlocks()`（fillChunk 在 :1368、NBT 读取在 AnvilChunkLoader.java:398 都遵守了）。
- 三套坐标索引不要混用：ExtendedBlockStorage/NibbleArray 是 `y << 8 | z << 4 | x`；ChunkPrimer 是 `x << 12 | z << 8 | y`；Chunk 的 heightMap 是 `z << 4 | x`，precipitationHeightMap 是 `x + (z << 4)`（同值但写法不同）。
- 无天空维度（`worldObj.provider.getHasNoSky()`）下 `skylightArray == null`，直接调 `getExtSkylightValue` 会 NPE——所有调用点都先判了 provider，钩子代码也必须判。
- `Chunk` 完全无同步。从渲染线程或其它线程读方块依赖"主线程当前没在写"这一脆弱事实；功能代码要改方块必须调度回主线程。
- `AnvilChunkLoader.saveExtraData()`（:207-225）的 `while (true) { if (writeNextIO()) continue; }` 在队列清空后**不会退出**（缺 else break），死循环；原版同样有此 bug，但任何想调用它的钩子都要绕开。上游 `IChunkProvider#saveExtraData` 注释也标明 "Currently unimplemented"。
- `RegionFileCache.getChunkInputStream / getChunkOutputStream` 无 synchronized，返回的流也可能在 `clearRegionFileReferences()` 关闭底层 `RandomAccessFile` 后继续被使用；缓存满 256 全清的策略意味着长时间跨维度移动会反复关文件。写入方与 flush 必须在同一协调下（原版靠 ThreadedFileIOBase 单线程 + flush 时序保证）。
- `RegionFile.ChunkBuffer` 在 `close()` 时才真正写盘（RegionFile.java:360-363）；忘记 close `getChunkDataOutputStream` 返回的流 = 数据静默丢失。单块压缩后超过 255 个扇区（约 1MiB）直接被丢弃（:198-201）。
- `EmptyChunk` 让未加载区域的一切写入静默无效——功能层在未加载区块上 setBlock/addTileEntity 不会报错也不会生效，判断 `chunk.isEmpty()` 或 `world.isBlockLoaded` 先行。
- `fillChunk` 会把 `isLightPopulated / isTerrainPopulated` 强制置 true 并重建 heightMap（:1372-1374），依赖这些标记的功能在多人环境语义与单机不同。
- `Chunk.getBlock(int, int, int)` 只对 x/z 取 `& 15`，y 越界（<0 或 >=256）安全返回空气（:521-544 getBlock0 的范围判断）。
- LWJGL3/JDK25 移植面：本包无渲染/输入依赖，未见移植改动点；`char[]` 段数据与 `Block.BLOCK_STATE_IDS` 的耦合意味着方块状态 id 超过 16 bit 会静默截断（`(char)` 强转，ExtendedBlockStorage.java:77、ChunkPrimer 的 `(short)` 强转 :41）。
- `RegionFile` 用 `MinecraftServer.getCurrentTimeMillis()` 打时间戳（:274）——纯客户端（无集成服务端）路径不会走到这里，但独立测试 RegionFile 时该静态调用是隐藏依赖。

## 交叉引用

- net.minecraft.world → `World#setBlockState` / `World#getBlockState`（经 `Chunk#setBlockState` / `#getBlockState`）；`World#playerCheckLight` → `Chunk#enqueueRelightChecks`（World.java:2666）；`Chunk` 反向大量调用 `World#checkLight` / `#checkLightFor` / `#notifyLightSet` / `#isAreaLoaded` / `#setTileEntity`
- net.minecraft.world → `WorldServer#tick` → `Chunk#func_150804_b`（WorldServer.java:347, 363）；`WorldServer` 构造 → `ISaveHandler#getChunkLoader`（WorldServer.java:720）
- net.minecraft.client.multiplayer → `ChunkProviderClient`（implements `IChunkProvider`）→ `Chunk#onChunkLoad` / `#onChunkUnload` / `#func_150804_b`；未加载坐标返回 `EmptyChunk`
- net.minecraft.client.network → `NetHandlerPlayClient#handleChunkData` / `#handleMapChunkBulk` → `Chunk#fillChunk`（NetHandlerPlayClient.java:764, 1348）
- net.minecraft.world.gen → `ChunkProviderServer#provideChunk` → `AnvilChunkLoader#loadChunk`、`Chunk#onChunkLoad`（ChunkProviderServer.java:140）、`Chunk#populateChunk`；`ChunkProviderGenerate` 等生成器 → `ChunkPrimer#setBlockState` → `Chunk(World, ChunkPrimer, int, int)`；`Chunk#getBlockState` 的 DEBUG_WORLD 分支 → `ChunkProviderDebug.func_177461_b`（Chunk.java:599）
- net.minecraft.block → `Block.BLOCK_STATE_IDS`（ExtendedBlockStorage#get/set、ChunkPrimer）；`Block#breakBlock` / `#onBlockAdded` / `#getLightOpacity`（Chunk#setBlockState）；`Block.blockRegistry#getNameForObject`（AnvilChunkLoader TileTicks 写出）
- net.minecraft.nbt → `CompressedStreamTools#read/write`（AnvilChunkLoader、AnvilSaveConverter）
- net.minecraft.world.storage → `ThreadedFileIOBase#queueIO / #waitForFinish`（AnvilChunkLoader#addChunkToPending、AnvilSaveHandler#flush）；`SaveHandler` / `SaveFormatOld`（AnvilSaveHandler / AnvilSaveConverter 的父类）；`SaveHandlerMP#getChunkLoader` 返回 null（多人无磁盘存储）
- net.minecraft.tileentity → `TileEntity.createAndLoadEntity` / `#writeToNBT`（AnvilChunkLoader）；`TileEntity#validate/invalidate/updateContainingBlockInfo`（Chunk）
- net.minecraft.entity → `EntityList.createEntityFromNBT`（AnvilChunkLoader#readChunkFromNBT）；`Entity#onChunkLoad` / `#writeToNBTOptional` / `chunkCoordX/Y/Z`
- net.minecraft.world.biome → `WorldChunkManager#getBiomeGenerator`（Chunk#getBiome、ChunkLoader#convertToAnvilFormat）
- net.minecraft.server → `MinecraftServer.getCurrentTimeMillis`（RegionFile#write 时间戳）

## 覆盖声明

完整读取了 14/14 个文件（每个文件从第 1 行读到末行）。逐行精读：Chunk、ExtendedBlockStorage、AnvilChunkLoader、RegionFile、RegionFileCache、NibbleArray、ChunkPrimer、fillChunk 相关路径及 NBT 读写全部字段。结构性通读（逻辑较薄、未逐分支推演）：AnvilSaveConverter 的 convertChunks/进度计算细节、ChunkLoader.convertToAnvilFormat 的旧坐标位运算、EmptyChunk、IChunkProvider、IChunkLoader、NibbleArrayReader、AnvilSaveHandler。行号引用均来自本次 Read 输出。调用方位置（WorldServer/NetHandlerPlayClient/ChunkProviderClient 等）经 grep 确认，但未通读那些文件全文。
