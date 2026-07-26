---
area: net/minecraft/world/gen
slug: mc-world-gen
files: 19
lines: 4835
tier: C
---

# net/minecraft/world/gen — 地形生成（区块生成器 + 噪声）

## 定位

本包是单机（集成服务端）模式下的地形生成核心：把 `(chunkX, chunkZ)` 坐标变成一个填好方块、生物群系与光照的 `Chunk`。它分三层：

1. **区块提供者**：`ChunkProviderServer` 是服务端区块缓存/调度器（加载、生成、卸载、保存的总入口）；`ChunkProviderGenerate` / `ChunkProviderHell` / `ChunkProviderEnd` / `ChunkProviderFlat` / `ChunkProviderDebug` 是五种维度/世界类型的实际生成器，全部实现 `net.minecraft.world.chunk.IChunkProvider`。
2. **雕刻器**：`MapGenBase` 及其子类 `MapGenCaves` / `MapGenCavesHell` / `MapGenRavine`，在 `ChunkPrimer` 阶段挖洞（洞穴、峡谷）。
3. **噪声**：`NoiseGenerator`（空抽象基类）、`NoiseGeneratorImproved`（改良 Perlin）、`NoiseGeneratorOctaves`（多倍频叠加）、`NoiseGeneratorSimplex` / `NoiseGeneratorPerlin`（Simplex 及其倍频封装），为地形密度场和表层替换提供确定性伪随机数据。

调用链：`WorldServer.createChunkProvider()`（WorldServer.java:718-722）构造 `ChunkProviderServer`，其内部生成器由 `WorldProvider.createChunkGenerator()`（WorldProvider.java:99-102，按 `WorldType` 分派 FLAT/DEBUG_WORLD/CUSTOMIZED/默认）或 `WorldProviderHell` / `WorldProviderEnd` 的同名覆盖提供。本包向下调用 `world.gen.feature.*`（WorldGenLakes、WorldGenDungeons 等）、`world.gen.structure.*`（村庄、要塞、下界要塞等）、`world.biome.*`（群系表层与装饰）。

纯多人客户端不走本包（客户端区块来自网络包，由 `ChunkProviderClient` 管理）；但单人模式一旦没有它，世界无法生成、加载、卸载、存盘。此外 `FlatGeneratorInfo` / `ChunkProviderSettings` 被创建世界 GUI 直接引用，删掉会连带打不开超平坦/自定义世界界面。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| ChunkProviderDebug | 187 | implements IChunkProvider | 调试世界：y=60 铺 barrier、y=70 按索引铺全部方块状态（每个 IBlockState 一格） |
| ChunkProviderEnd | 366 | implements IChunkProvider | 末地生成器：3D 噪声密度场生成悬浮 end_stone 岛屿 |
| ChunkProviderFlat | 315 | implements IChunkProvider | 超平坦生成器：按 FlatGeneratorInfo 分层铺方块，可选村庄/湖/地牢等 feature |
| ChunkProviderGenerate | 602 | implements IChunkProvider | 主世界生成器：噪声地形 + 群系表层 + 洞穴/峡谷/结构 + populate 装饰 |
| ChunkProviderHell | 521 | implements IChunkProvider | 下界生成器：netherrack 密度场、岩浆海、下界要塞、火/萤石/石英装饰 |
| ChunkProviderServer | 378 | implements IChunkProvider | 服务端区块缓存：加载/生成/populate/卸载队列/存盘的调度中枢 |
| ChunkProviderSettings | 671 | （无；含内部类 Factory、Serializer） | 自定义世界生成参数集（约 80 个字段）及其 JSON 序列化 |
| FlatGeneratorInfo | 328 | （无） | 超平坦预设的解析/序列化（"3;minecraft:bedrock,2*minecraft:dirt,…;1;village" 格式） |
| FlatLayerInfo | 110 | （无） | 超平坦单层描述：方块状态、层厚、起始 Y |
| GeneratorBushFeature | 32 | extends WorldGenerator | 在 8x4x8 范围内随机撒 64 次灌木类方块（下界蘑菇用） |
| MapGenBase | 45 | （无） | 雕刻器基类：以 range=8 遍历邻域区块、按种子调 recursiveGenerate |
| MapGenCaves | 267 | extends MapGenBase | 主世界洞穴雕刻：蠕虫式隧道 + 大洞分叉，y<10 填 lava |
| MapGenCavesHell | 222 | extends MapGenBase | 下界洞穴雕刻：只挖 netherrack/dirt/grass，高度上限 128 |
| MapGenRavine | 227 | extends MapGenBase | 峡谷雕刻：1/50 概率，纵向拉伸（竖直系数 3.0），y<10 填 flowing_lava |
| NoiseGenerator | 5 | abstract class | 空的噪声基类（仅作类型标记） |
| NoiseGeneratorImproved | 205 | extends NoiseGenerator | 单倍频改良 Perlin 噪声，permutations 512 表，累加写入数组 |
| NoiseGeneratorOctaves | 72 | extends NoiseGenerator | 持有 N 个 NoiseGeneratorImproved 做倍频叠加（幅度 d3 每倍频减半） |
| NoiseGeneratorPerlin | 66 | extends NoiseGenerator | 持有 N 个 NoiseGeneratorSimplex 的 2D 倍频封装（表层 stoneNoise 用） |
| NoiseGeneratorSimplex | 216 | （无，不继承 NoiseGenerator） | 2D Simplex 噪声实现（12 梯度向量表） |

## 核心类详解

### ChunkProviderServer（ChunkProviderServer.java）

服务端唯一的"对外"区块提供者，其余生成器都躲在它身后（字段 `private IChunkProvider serverChunkGenerator`，ChunkProviderServer.java:39）。

关键字段：
- `private Set<Long> droppedChunksSet = Collections.<Long>newSetFromMap(new ConcurrentHashMap())` — 待卸载区块（ChunkProviderServer.java:31）
- `private LongHashMap<Chunk> id2ChunkMap` / `private List<Chunk> loadedChunks`（ChunkProviderServer.java:47-48）
- `public boolean chunkLoadOverride = true` — 为 false 时 `provideChunk` 对未加载区块返回 `dummyChunk`（EmptyChunk）而不是触发加载（ChunkProviderServer.java:46,154）
- `private IChunkLoader chunkLoader` — 磁盘读写（AnvilChunkLoader）

关键方法（签名逐字）：
- `public Chunk loadChunk(int chunkX, int chunkZ)`（ChunkProviderServer.java:104）— 缓存未命中 → `loadChunkFromFile` → 仍无则 `serverChunkGenerator.provideChunk`，生成异常包成 `ReportedException` 崩溃报告（:128-133）；随后 `chunk.onChunkLoad()` + `chunk.populateChunk(this, this, chunkX, chunkZ)`（:140-141）。
- `public Chunk provideChunk(int x, int z)`（:151）— 缓存查询；未命中时只有 `isFindingSpawnPoint()` 或 `chunkLoadOverride` 为真才真正加载，否则给 dummyChunk（:154）。
- `public void populate(IChunkProvider chunkProvider, int x, int z)`（:227）— 首次装饰：`chunk.func_150809_p()` 后委托生成器 `populate`。
- `public boolean unloadQueuedChunks()`（:306）— 每 tick 最多处理 100 个待卸载区块：`onChunkUnload()` → 存盘 → 移出缓存；然后 `chunkLoader.chunkTick()`。
- `public boolean saveChunks(boolean saveAllChunks, IProgressUpdate progressCallback)`（:261）— 增量模式一次最多 24 个（:281）。

调用时机：`WorldServer.tick()` 每 tick 调 `this.chunkProvider.unloadQueuedChunks()`（WorldServer.java:196）；`WorldServer.saveAllChunks` → `saveChunks`（WorldServer.java:903）；`PlayerManager` / `ServerConfigurationManager` 通过 `theChunkProviderServer` 直接触发 `loadChunk` / `dropChunk`。全部在服务端线程。

### ChunkProviderGenerate（ChunkProviderGenerate.java）

主世界（含 CUSTOMIZED、AMPLIFIED）生成器。生成分两个阶段：`provideChunk`（地形成形）与 `populate`（装饰）。

关键字段：
- `private ChunkProviderSettings settings`（:52）— 仅当构造参数 `structuresJson != null` 时由 `ChunkProviderSettings.Factory.jsonToFactory(structuresJson).func_177864_b()` 赋值（:103-108），否则保持 null（见陷阱）。
- `private final double[] field_147434_q = new double[825]`（5×5×33 密度场，:91）、`private final float[] parabolicField`（5×5 群系权重核，:92）
- 噪声：`field_147431_j/field_147432_k`（lower/upper limit，16 倍频）、`field_147429_l`（main，8 倍频）、`field_147430_m`（NoiseGeneratorPerlin，4 层，表层 stoneNoise）、`noiseGen5/noiseGen6/mobSpawnerNoise`（:32-42）
- 雕刻/结构：`caveGenerator`（MapGenCaves）、`ravineGenerator`（MapGenRavine）、`strongholdGenerator`、`villageGenerator`、`mineshaftGenerator`、`scatteredFeatureGenerator`、`oceanMonumentGenerator`（:55-69）
- `private Block oceanBlockTmpl = Blocks.water`（:53）— `settings.useLavaOceans` 时换成 lava（:106）

关键方法（签名逐字）：
- `public ChunkProviderGenerate(World worldIn, long seed, boolean generateStructures, String structuresJson)`（:78）
- `public void setBlocksInChunk(int x, int z, ChunkPrimer primer)`（:114）— 用 `func_147423_a` 算出的 825 点密度场做三线性插值，>0 填 stone，否则海平面下填 oceanBlockTmpl（:159-166）。
- `public void replaceBlocksForBiome(int x, int z, ChunkPrimer primer, BiomeGenBase[] biomeGens)`（:187）— 逐柱调 `biomegenbase.genTerrainBlocks(...)` 换表层（:197）。
- `public Chunk provideChunk(int x, int z)`（:206）— 顺序：setSeed（`(long)x * 341873128712L + (long)z * 132897987541L`，:208）→ setBlocksInChunk → replaceBlocksForBiome → caves → ravines → mineshaft/village/stronghold/temple/monument 的 `generate`（布局阶段）→ new Chunk → 写 biome 数组 → `generateSkylightMap()`。
- `private void func_147423_a(int x, int y, int z)`（:261）— 5×5 群系高度加权 + depth/main/lower/upper 四组噪声合成密度场；AMPLIFIED 在 :292-296 放大。
- `public void populate(IChunkProvider chunkProvider, int x, int z)`（:391）— `BlockFalling.fallInstantly = true` 包裹（:393,484）；结构 `generateStructure`、水/岩浆湖、地牢、`biomegenbase.decorate`、`SpawnerAnimals.performWorldGenSpawning`（:462）、结冰/铺雪（:465-482）。
- `public void recreateStructures(Chunk chunkIn, int x, int z)`（:570）— 从磁盘加载区块后重建结构布局（primer 传 null）。

调用时机：全部由 `ChunkProviderServer.loadChunk/populate/recreateStructures` 在服务端线程调用。

### ChunkProviderHell（ChunkProviderHell.java）

下界生成器。`public ChunkProviderHell(World worldIn, boolean p_i45637_2_, long seed)`（:77）中 `p_i45637_2_` 控制是否生成下界要塞（`field_177466_i`，:269-272）；构造时 `worldIn.setSeaLevel(63)`（:89）。

- `public void func_180515_a(int p_180515_1_, int p_180515_2_, ChunkPrimer p_180515_3_)`（:92）— 5×17×5 密度场（y 只有 17 层 → 128 高），密度>0 填 netherrack，海平面（`getSeaLevel()/2 + 1`，即 32）以下填 lava（:95,135-143）。
- `public void func_180516_b(int p_180516_1_, int p_180516_2_, ChunkPrimer p_180516_3_)`（:166）— 表层替换：soul_sand/gravel 噪声带、顶底 bedrock（:250）。
- `public Chunk provideChunk(int x, int z)`（:261）— func_180515_a → func_180516_b → `netherCaveGenerator.generate`（MapGenCavesHell）→ 可选 `genNetherBridge.generate` → 写 biome 后 `chunk.resetRelightChecks()`（:283，注意：不是 generateSkylightMap）。
- `public void populate(IChunkProvider chunkProvider, int x, int z)`（:387）— 要塞 `generateStructure`、隐藏岩浆流 `field_177472_y`×8、火、两种萤石、双色蘑菇（GeneratorBushFeature）、石英矿×16、岩浆流×16。
- `public List<BiomeGenBase.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos)`（:483）— 位于下界要塞内（或结构范围内且脚下是 nether_brick）时返回 `genNetherBridge.getSpawnList()`，这是烈焰人/凋灵骷髅刷怪的来源。

### MapGenBase / MapGenCaves / MapGenRavine

`MapGenBase.generate`（签名逐字）：`public void generate(IChunkProvider chunkProviderIn, World worldIn, int x, int z, ChunkPrimer chunkPrimerIn)`（MapGenBase.java:19）。以 `range = 8`（MapGenBase.java:11）遍历目标区块周围 17×17 邻域，对每个邻域区块用 `(long)l * j ^ (long)i1 * k ^ worldIn.getSeed()` 设种子后调 `protected void recursiveGenerate(World worldIn, int chunkX, int chunkZ, int p_180701_4_, int p_180701_5_, ChunkPrimer chunkPrimerIn)`（MapGenBase.java:42，基类空实现）——这保证跨区块隧道确定性（同一条洞在相邻区块各自算一遍，只写落在本 primer 内的部分）。

`MapGenCaves` 的挖掘核心是 `protected void func_180702_a(long p_180702_1_, int p_180702_3_, int p_180702_4_, ChunkPrimer p_180702_5_, double p_180702_6_, double p_180702_8_, double p_180702_10_, float p_180702_12_, float p_180702_13_, float p_180702_14_, int p_180702_15_, int p_180702_16_, double p_180702_17_)`（MapGenCaves.java:21）：蠕虫式步进，遇水面放弃（:141-144）、y<10 填 lava（:186-189）、挖穿 grass 下方 dirt 时补 biome topBlock（:199-203）。可挖方块白名单在 `protected boolean func_175793_a(IBlockState p_175793_1_, IBlockState p_175793_2_)`（:222）。`MapGenRavine.recursiveGenerate` 触发概率 `this.rand.nextInt(50) == 0`（MapGenRavine.java:211），纵截面用 `field_75046_d`（256 项宽度表，:13）调制。

### NoiseGeneratorOctaves / NoiseGeneratorImproved

`public double[] generateNoiseOctaves(double[] noiseArray, int xOffset, int yOffset, int zOffset, int xSize, int ySize, int zSize, double xScale, double yScale, double zScale)`（NoiseGeneratorOctaves.java:29）：清零数组后逐倍频调 `NoiseGeneratorImproved.populateNoiseArray`，幅度 `d3` 从 1 每倍频除 2；坐标做 `% 16777216L` 折叠（:54-55）防止大坐标精度崩坏。`populateNoiseArray`（NoiseGeneratorImproved.java:70）是累加写入（`noiseArray[j7] += d13 * d0`，:199），且 `ySize == 1` 有专用 2D 快速路径（:72-121）。噪声实例的随机相位（`xCoord/yCoord/zCoord`、permutations 表）在构造时由传入的 `Random` 决定——所以各 ChunkProvider 构造函数里 new 各噪声生成器的**顺序**就是世界种子约定的一部分，不能调换。

## 时序与生命周期

- **初始化**：进入单人世界 → `WorldServer` 构造 → `createChunkProvider()`（WorldServer.java:718）→ `new ChunkProviderServer(this, ichunkloader, this.provider.createChunkGenerator())`（:721）。生成器构造时一次性建好全部噪声实例并可能 `setSeaLevel`（ChunkProviderGenerate.java:107、ChunkProviderHell.java:89、ChunkProviderFlat.java:121）。`ChunkProviderDebug` 有静态块在类加载时枚举 `Block.blockRegistry` 收集所有合法方块状态（ChunkProviderDebug.java:177-186）。
- **区块生成**：`loadChunk` 同步完成 provideChunk；`populate` 在相邻区块齐备后由 `Chunk.populateChunk` 回调触发（惰性、也在服务端线程）。生成全程是同步阻塞的——没有 1.9+ 的异步区块生成。
- **每 tick**：`WorldServer.tick()` → `chunkProvider.unloadQueuedChunks()`（WorldServer.java:196），每次最多卸载 100 个 + `chunkLoader.chunkTick()`。
- **每帧**：无。本包与渲染无关。
- **线程归属**：全部逻辑在**服务端线程**执行。`droppedChunksSet` 用 ConcurrentHashMap 支撑是因为 `dropChunk` 可能被别的路径并发调用，但生成本身单线程。
- **关闭/存档**：`MinecraftServer` 停服 → `worldserver.saveAllChunks(true, (IProgressUpdate)null)`（MinecraftServer.java:458）→ `ChunkProviderServer.saveChunks` + `saveExtraData`。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public Chunk loadChunk(int chunkX, int chunkZ)` | ChunkProviderServer.java:104 | 任何区块首次被需要（玩家移动、找出生点、传送） | 观察/统计区块加载，预生成，替换生成结果，做加载耗时探针 | 同步阻塞服务端线程；此处注入慢逻辑直接卡 tick |
| `public boolean unloadQueuedChunks()` | ChunkProviderServer.java:306 | WorldServer.tick 每 tick（WorldServer.java:196） | 拦截/延迟卸载（区块常驻）、卸载事件通知 | 阻止卸载会让 loadedChunks 无界增长 |
| `public void populate(IChunkProvider chunkProvider, int x, int z)` | ChunkProviderServer.java:227 | 区块四邻齐备、首次装饰时 | "区块装饰完成"事件；矿物/结构自定义的总闸 | 只触发一次（isTerrainPopulated 置位后不再来） |
| `public Chunk provideChunk(int x, int z)` | ChunkProviderGenerate.java:206 | ChunkProviderServer 缓存未命中且磁盘无档 | 整体接管主世界地形（自定义生成器最自然的替换点：换掉 serverChunkGenerator 字段即可） | 必须保持确定性（同种子同输出），否则区块边界撕裂 |
| `public void populate(IChunkProvider chunkProvider, int x, int z)` | ChunkProviderGenerate.java:391 | 主世界区块装饰阶段 | 增删矿物/湖/地牢/结构；矿物 X-Ray 类功能想要的"矿物在哪"信息源头 | 内部改写 `BlockFalling.fallInstantly` 全局静态；抛异常会让它留在 true |
| `public List<BiomeGenBase.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos)` | ChunkProviderGenerate.java:540 / ChunkProviderHell.java:483 | SpawnerAnimals 每次自然刷怪选表 | 控制/过滤自然刷怪（含女巫小屋、海底神殿、下界要塞特判） | 返回 null 会 NPE；返回空 list 才是"不刷" |
| `public BlockPos getStrongholdGen(World worldIn, String structureName, BlockPos position)` | ChunkProviderServer.java:360（转发到 Generate:560 / Flat:282） | 末影之眼投掷 / `/locate` 式查询 | 观察或伪造要塞定位 | 仅 "Stronghold" 字符串匹配 |
| `public void generate(IChunkProvider chunkProviderIn, World worldIn, int x, int z, ChunkPrimer chunkPrimerIn)` | MapGenBase.java:19 | provideChunk 的雕刻阶段（Generate:216-221、Hell:267、Flat:151） | 替换洞穴/峡谷算法、关洞穴（子类化后换字段） | 操作对象是 ChunkPrimer 不是世界；此时 Chunk 尚不存在 |
| `public boolean saveChunks(boolean saveAllChunks, IProgressUpdate progressCallback)` | ChunkProviderServer.java:261 | 自动存盘与停服（MinecraftServer.java:458） | 存盘节流、备份钩子 | 增量模式每次上限 24 区块（:281） |
| `public static ChunkProviderSettings.Factory jsonToFactory(String p_177865_0_)` | ChunkProviderSettings.java:261 | 世界创建/GuiCustomizeWorldScreen 及 ChunkProviderGenerate 构造（:105） | 注入/校验自定义世界参数预设 | 解析失败静默回落默认值（catch 空吞，:273-277 与 :579-582） |

## 数据与协议

不涉及网络封包与 NBT。有两种**字符串/JSON 格式**由本包定义：

**1. 超平坦预设字符串**（`FlatGeneratorInfo.createFlatGeneratorFromString`，FlatGeneratorInfo.java:238；序列化 `toString`，:56）。格式：`版本;层列表;biomeID;feature列表`，当前版本写死 3（:59）。

| 字段/段 | 类型 | 读 / 写方法 | 取值含义 |
|---|---|---|---|
| 版本前缀 | int（0-3） | createFlatGeneratorFromString :247-249 / toString :59 | ≥3 层用 `count*block` 与 `Block.getBlockFromName`；<3 用 `count x id:meta` 数字 ID（func_180715_a，:119-207） |
| 层列表 | `List<FlatLayerInfo>`，逗号分隔 | func_180716_a :209 / FlatLayerInfo.toString（FlatLayerInfo.java:77） | 自底向上；总高被钳到 <256（:131-134）；meta 越界归 0（:194-197） |
| biomeToUse | int | :259-266 / :73 | 群系 ID，默认 `BiomeGenBase.plains.biomeID` |
| worldFeatures | `Map<String, Map<String,String>>` | :268-301 / :75-114 | 如 `village(size=1)`；无 feature 段时默认放 `village`（:300） |

`ChunkProviderFlat` 消费的 feature 键：`village` / `biome_1` / `mineshaft` / `stronghold` / `oceanmonument` / `lake` / `lava_lake` / `dungeon` / `decoration`（ChunkProviderFlat.java:49-92,122）。

**2. 自定义世界 JSON**（`ChunkProviderSettings.Serializer` implements `JsonDeserializer<ChunkProviderSettings.Factory>, JsonSerializer<ChunkProviderSettings.Factory>`，ChunkProviderSettings.java:478）。字段与 JSON 键同名，全部有默认值（Factory 字段初始化 :182-259），逐项 `JsonUtils.getFloat/getInt/getBoolean` 读取（:487-577）。代表性字段：

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| coordinateScale / heightScale | float | 684.412F | 主噪声水平/垂直采样尺度 |
| upperLimitScale / lowerLimitScale | float | 512.0F | 上/下限噪声除数（func_147423_a :362-363） |
| mainNoiseScaleX/Y/Z | float | 80/160/80 | 主噪声细分尺度 |
| baseSize / stretchY | float | 8.5F / 12.0F | 基准地面高度与 Y 拉伸 |
| biomeDepthWeight / biomeDepthOffSet / biomeScaleWeight / biomeScaleOffset | float | 1/0/1/0 | 群系高度混合权重（注意公开字段名 `biomeDepthOffSet` 的大写 S，ChunkProviderSettings.java:31；Factory 侧为 `biomeDepthOffset`） |
| seaLevel | int | 63 | 海平面（构造时写回 `worldIn.setSeaLevel`，ChunkProviderGenerate.java:107） |
| useCaves/useRavines/useDungeons/useStrongholds/useVillages/useMineShafts/useTemples/useMonuments | boolean | true | 各要素开关 |
| waterLakeChance / lavaLakeChance / dungeonChance | int | 4 / 80 / 8 | 概率分母/次数 |
| useLavaOceans | boolean | false | 海洋填 lava |
| fixedBiome | int | -1 | 固定群系；反序列化时 ≥hell.biomeID 会 +2 跳过 hell/sky（:520-526） |
| biomeSize / riverSize | int | 4 / 4 | GenLayer 缩放参数（由 biome 包消费） |
| dirtSize/…/lapisSpread（16 组矿物） | int | 见 :216-259 | 每种矿脉的 size/count/minHeight/maxHeight（lapis 用 centerHeight/spread） |

## 不变量与陷阱

- **确定性是硬约束**：同一 seed + 坐标必须产出相同区块。三处保证机制不可动：区块种子公式 `(long)x * 341873128712L + (long)z * 132897987541L`（Generate:208、Hell:263、End:174）；populate 的 `(long)x * k + (long)z * l ^ seed`（Generate:398-401、Flat:185-188）；MapGenBase 的邻域重放（MapGenBase.java:27-35）。各 ChunkProvider 构造函数中噪声生成器的**创建顺序**同样消耗 RNG 序列，属于种子格式的一部分。
- **`ChunkProviderGenerate.settings` 可为 null**：仅 `structuresJson != null` 时初始化（:103-108），而 `provideChunk`/`populate` 无条件解引用（:163、:214 等）。现行调用方 `WorldProvider.createChunkGenerator` 传的是 `generatorSettings`（普通世界为 ""，非 null），所以没炸；自己 new 这个类时绝不能传 null。
- **`BlockFalling.fallInstantly` 是全局静态开关**：populate 用 true/false 包裹（Generate:393/484、Hell:389/434、End:291/294）。若在中途抛异常或从中间 return，它会卡在 true，之后全世界的沙砾都会瞬间落地。挂钩 populate 时要保证恢复。
- **ChunkProviderHell 不算天光**：provideChunk 末尾是 `chunk.resetRelightChecks()`（Hell:283）而非 `generateSkylightMap()`；ChunkProviderDebug 反而调了两次 `generateSkylightMap()`（Debug:55,64，第二次冗余但无害）。
- **雕刻器只能在 ChunkPrimer 阶段用**：`MapGenStructure.generate(this, worldObj, x, z, (ChunkPrimer)null)`（recreateStructures 路径，Generate:570-596、Flat:303-309、Hell:512-515）传 null primer 是合法的——那是只重建结构布局、不写方块的模式；但 MapGenCaves/Ravine 传 null 会 NPE。
- **线程安全**：除 `droppedChunksSet`（ConcurrentHashMap 集合）外全部结构非线程安全（LongHashMap、ArrayList、共享的 double[] 噪声缓冲、`MapGenBase.rand` 复用）。一切生成必须留在服务端线程；想做异步预生成需要整层复制状态。
- **`ChunkProviderServer.chunkLoadOverride`**：public 可写；置 false 后 `provideChunk` 对未加载区块返回 EmptyChunk，物理/寻路会表现为"世界是空气"。改它影响全服。
- **解析容错=静默吞错**：`ChunkProviderSettings.Factory.jsonToFactory` 与 `Serializer.deserialize` 的 catch 均为空（:273-277、:579-582），`FlatGeneratorInfo` 解析失败一律回落 `getDefaultFlatGenerator()`（:242,307,312）——排查"预设没生效"时先怀疑格式错误被吞。
- **移植相关**：`MapGenCaves` 用 `com.google.common.base.MoreObjects.firstNonNull`（MapGenCaves.java:3,177），是移植时随 Guava 升级从原版 `Objects.firstNonNull` 改的；`ChunkProviderGenerate` 的 `oceanBlockTmpl`（:53）也是本仓库对原版混淆名的重命名。本包纯 CPU 计算，无 LWJGL 依赖，JDK 25 下唯一要留意的是这些 double 运算依赖严格 IEEE 语义（Java 17+ 恒为 strictfp，行为与老版本一致）。
- **NoiseGeneratorSimplex 不继承 NoiseGenerator**（NoiseGeneratorSimplex.java:5），别按"所有噪声类都是 NoiseGenerator 子类"写反射/instanceof 逻辑。
- **`NoiseGeneratorImproved.populateNoiseArray` 是累加语义**（`+=`，:199），调用方负责清零；`NoiseGeneratorOctaves.generateNoiseOctaves` 帮你清（:35-41），直接用 Improved 则要自己清。

## 交叉引用

- `net.minecraft.world` → `WorldServer#createChunkProvider`（构造 ChunkProviderServer，WorldServer.java:718-722）、`WorldServer#tick`（unloadQueuedChunks）、`WorldServer#saveAllChunks`
- `net.minecraft.world` → `WorldProvider#createChunkGenerator`（WorldType 分派，WorldProvider.java:99）；`WorldProviderHell#createChunkGenerator`、`WorldProviderEnd#createChunkGenerator`
- `net.minecraft.world.chunk` → `IChunkProvider`（本包全部 provider 的接口）、`ChunkPrimer#setBlockState/getBlockState`、`Chunk#populateChunk`、`Chunk#generateSkylightMap`、`EmptyChunk`
- `net.minecraft.world.chunk.storage` → `IChunkLoader#loadChunk/saveChunk/chunkTick`（ChunkProviderServer 的磁盘层）
- `net.minecraft.world.biome` → `WorldChunkManager#getBiomesForGeneration/loadBlockGeneratorData`、`BiomeGenBase#genTerrainBlocks/decorate/getSpawnableList`；`BiomeGenBase`/`BiomeGenMesa` 反向持有 `NoiseGeneratorPerlin`
- `net.minecraft.world.gen.structure` → `MapGenStronghold/MapGenVillage/MapGenMineshaft/MapGenScatteredFeature/StructureOceanMonument/MapGenNetherBridge#generate/generateStructure/getClosestStrongholdPos`
- `net.minecraft.world.gen.feature` → `WorldGenLakes/WorldGenDungeons/WorldGenFire/WorldGenGlowStone1/WorldGenGlowStone2/WorldGenHellLava/WorldGenMinable#generate`；`GeneratorBushFeature extends WorldGenerator`
- `net.minecraft.world` → `SpawnerAnimals#performWorldGenSpawning`（ChunkProviderGenerate.java:462）
- `net.minecraft.block` → `BlockFalling.fallInstantly`（静态开关）、`Block.blockRegistry`（ChunkProviderDebug 静态块、FlatLayerInfo.toString）
- `net.minecraft.client.gui` → `GuiCreateFlatWorld`/`GuiFlatPresets`（消费 FlatGeneratorInfo）、`GuiCustomizeWorldScreen`/`GuiScreenCustomizePresets`（消费 ChunkProviderSettings.Factory）
- `net.minecraft.server` → `MinecraftServer#stopServer` 存档路径（MinecraftServer.java:458）；`PlayerManager`、`ServerConfigurationManager` 直接操作 `theChunkProviderServer`
- `net.minecraft.crash` → `CrashReport/ReportedException`（生成失败的崩溃报告，ChunkProviderServer.java:126-133）

## 覆盖声明

完整读取了 19/19 个文件（每个文件从第 1 行读到最后一行）。逐行精读：ChunkProviderServer、ChunkProviderGenerate、ChunkProviderHell、ChunkProviderEnd、ChunkProviderFlat、MapGenBase、MapGenCaves、MapGenRavine、MapGenCavesHell、NoiseGeneratorOctaves、NoiseGeneratorImproved、FlatGeneratorInfo、FlatLayerInfo、GeneratorBushFeature、NoiseGenerator、NoiseGeneratorPerlin、ChunkProviderDebug。结构性浏览（读了全文但未逐项核对数学细节）：NoiseGeneratorSimplex 的梯度/单纯形数学、ChunkProviderSettings 的 equals/hashCode 长链（:381、:389-470）与 Serializer 的逐字段 JSON 映射（抽查核对，未逐键复核全部 80 项）。行号引用均经 Read 输出核实；WorldServer/WorldProvider/MinecraftServer 的外部调用点经 grep + sed 确认。
