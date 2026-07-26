---
area: net/minecraft/world/biome
slug: mc-world-biome
files: 25
lines: 3219
tier: C
---

# net/minecraft/world/biome

## 定位

本包是 1.8.9 的生物群系子系统：定义全部群系单例（`BiomeGenBase` 及其约 20 个子类）、按坐标查询群系的 `WorldChunkManager`（含缓存 `BiomeCache`）、区块装饰器 `BiomeDecorator`，以及渲染侧的颜色混合工具 `BiomeColorHelper`。

- 上游调用方：
  - `WorldProvider` 在 `registerWorldChunkManager()` 中构造 `WorldChunkManager` / `WorldChunkManagerHell`（`WorldProvider.java:84-92`，`WorldProviderHell.java:17`，`WorldProviderEnd.java:18`）。
  - `World#getBiomeGenForCoords`（`World.java:172`）→ `Chunk#getBiome(BlockPos, WorldChunkManager)`（`Chunk.java:1382`），是所有游戏逻辑取群系的统一入口。
  - `net.minecraft.world.gen` 的各 `ChunkProvider*`：地形生成阶段调 `getBiomesForGeneration` / `loadBlockGeneratorData` / `genTerrainBlocks`，populate 阶段调 `BiomeGenBase#decorate`。
  - `SpawnerAnimals` 用 `getSpawnableList` / `getSpawningChance` 决定生物生成。
  - 渲染侧：`BlockGrass` / `BlockLeaves` / `BlockLiquid` / `BlockDoublePlant` 等通过 `BiomeColorHelper` 取草/叶/水颜色；`World.java:1441-1443` 用 `getSkyColorByTemp` 算天空色。
- 下游依赖：`net.minecraft.world.gen.layer.GenLayer`（群系分布噪声）、`net.minecraft.world.gen.feature.*`（各类 WorldGen）、`net.minecraft.init.Blocks`、`net.minecraft.entity.*`（刷怪表）。
- 若移除：世界无法生成（ChunkProvider 全线依赖）、`World.getBiomeGenForCoords` 崩溃、草/叶/水全部失色、天气判定（`canRain` / `canSnowAt`）与刷怪失效。注意本客户端仓库含集成服务端逻辑，所以世界生成代码在客户端 jar 内是活代码（单人模式）。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| BiomeCache | 106 | - | 以 16x16 块为单位缓存 `WorldChunkManager` 的群系与降雨查询，30 秒未访问淘汰 |
| BiomeColorHelper | 66 | - | 对 pos 周围 3x3 列群系颜色做平均，供方块渲染取草/叶/水色 |
| BiomeDecorator | 495 | - | 区块 populate 阶段生成矿石、树、花草、甘蔗、湖泊等装饰物 |
| BiomeEndDecorator | 30 | extends BiomeDecorator | 末地装饰：黑曜石柱 + 在 (0,0) 区块生成末影龙 |
| BiomeGenBase | 698 | abstract | 群系基类兼注册表：静态数组 `biomeList[256]`、全部原版群系单例、地表生成与属性查询 |
| BiomeGenBeach | 18 | extends BiomeGenBase | 沙滩：沙子地表，无树无动物 |
| BiomeGenDesert | 36 | extends BiomeGenBase | 沙漠：沙地、仙人掌/枯木，1/1000 概率生成沙漠水井 |
| BiomeGenEnd | 28 | extends BiomeGenBase | 末地：只刷末影人，`getSkyColorByTemp` 恒返回 0（黑天） |
| BiomeGenForest | 201 | extends BiomeGenBase | 森林四变体（普通/花林/桦木/黑森林），自定义树、花与突变体 |
| BiomeGenHell | 20 | extends BiomeGenBase | 下界：刷怪表换成恶魂/僵尸猪人/岩浆怪 |
| BiomeGenHills | 103 | extends BiomeGenBase | 峭壁：绿宝石矿与蠹虫石生成，噪声决定石头/沙砾地表 |
| BiomeGenJungle | 86 | extends BiomeGenBase | 丛林：大丛林树/灌木/藤蔓/西瓜，刷豹猫 |
| BiomeGenMesa | 335 | extends BiomeGenBase | 恶地：按种子生成 64 层染色黏土条带，自定义整套 genTerrainBlocks |
| BiomeGenMushroomIsland | 22 | extends BiomeGenBase | 蘑菇岛：菌丝地表，只刷哞菇 |
| BiomeGenMutated | 90 | extends BiomeGenBase | 突变群系包装类：持有 `baseBiome` 并把 decorate/genTerrainBlocks 等委托给它 |
| BiomeGenOcean | 24 | extends BiomeGenBase | 海洋：`getTempCategory()` 返回 `TempCategory.OCEAN` |
| BiomeGenPlains | 109 | extends BiomeGenBase | 平原：噪声决定郁金香带，突变体为向日葵平原 |
| BiomeGenRiver | 10 | extends BiomeGenBase | 河流：仅清空动物刷怪表 |
| BiomeGenSavanna | 89 | extends BiomeGenBase | 热带草原：金合欢树、马；内部类 `Mutated` 按噪声换粗泥/石头地表 |
| BiomeGenSnow | 65 | extends BiomeGenBase | 冰原：可选冰刺变体（`WorldGenIceSpike`/`WorldGenIcePath`） |
| BiomeGenStoneBeach | 18 | extends BiomeGenBase | 石岸：石头地表，无装饰 |
| BiomeGenSwamp | 83 | extends BiomeGenBase | 沼泽：水色 14745518、睡莲、y=62 挖水面，兰花 |
| BiomeGenTaiga | 114 | extends BiomeGenBase | 针叶林三变体（普通/巨木/巨杉），灰化土地表与苔石团 |
| WorldChunkManager | 279 | - | 用两层 `GenLayer` 把噪声 int 索引映射为 `BiomeGenBase[]`，带 `BiomeCache` |
| WorldChunkManagerHell | 94 | extends WorldChunkManager | 单群系世界（下界/末地/超平坦）：所有查询恒返回构造时传入的群系 |

## 核心类详解

### BiomeGenBase（BiomeGenBase.java）

群系注册表 + 属性载体 + 地表生成模板。

- 关键静态字段：`private static final BiomeGenBase[] biomeList = new BiomeGenBase[256]`（BiomeGenBase.java:69）；`public static final Set<BiomeGenBase> explorationBiomesList`（:70）；`public static final Map<String, BiomeGenBase> BIOME_ID_MAP`（:71）；原版群系单例 `ocean`(id 0) 到 `mesaPlateau`(id 39)（:72-125）；`public static final BiomeGenBase field_180279_ad = ocean`（:126，越界回退默认值）。
- 关键实例字段：`public String biomeName`、`public int color`、`public int field_150609_ah`（第二颜色，用于地图/雾）、`public IBlockState topBlock` / `fillerBlock`（:135-138）、`public float minHeight/maxHeight/temperature/rainfall`、`public int waterColorMultiplier`（:154）、`public BiomeDecorator theBiomeDecorator`（:157）、四张刷怪表 `spawnableMonsterList` 等（:158-161）、`public final int biomeID`（:172）。
- 构造器 `protected BiomeGenBase(int id)`（:183）：`biomeList[id] = this`（:199）即注册，随后填默认刷怪表。链式 setter：`setTemperatureRainfall(float, float)`（:228，温度落在 0.1~0.2 会直接 `throw IllegalArgumentException`）、`setHeight`、`setDisableRain`、`setEnableSnow`、`setColor` 等。
- 关键方法签名（逐字）：
  - `public final void generateBiomeTerrain(World worldIn, Random rand, ChunkPrimer chunkPrimerIn, int x, int z, double noiseVal)`（BiomeGenBase.java:459）— 自 y=255 下扫，铺 topBlock/fillerBlock/基岩，海面下按温度铺冰或水。
  - `public void genTerrainBlocks(World worldIn, Random rand, ChunkPrimer chunkPrimerIn, int x, int z, double noiseVal)`（:444）— 子类覆写入口，`ChunkProviderGenerate.java:197` 在生成区块时逐列调用。
  - `public void decorate(World worldIn, Random rand, BlockPos pos)`（:420）— populate 阶段被 `ChunkProviderGenerate.java:461` / `ChunkProviderFlat.java:226` / `ChunkProviderEnd.java:293` 调用。
  - `public final float getFloatTemperature(BlockPos pos)`（:407）— y>64 时用 `temperatureNoise` 随高度降温；`World#canSnowAt`（World.java:2738-2741）与天空色计算依赖它。
  - `public int getSkyColorByTemp(float p_76731_1_)`（:328）、`public List<BiomeGenBase.SpawnListEntry> getSpawnableList(EnumCreatureType creatureType)`（:335）、`public boolean canRain()`（:367）。
  - `public static BiomeGenBase getBiome(int id)`（:584）与 `public static BiomeGenBase getBiomeFromBiomeList(int biomeId, BiomeGenBase biome)`（:589）— 越界打 warn 并返回 `ocean`。
- 静态初始化块（:603-652）：对约 20 个群系调 `createMutation()`（生成 id+128 的突变体），随后构建 `BIOME_ID_MAP`，重名会 `throw new Error`；最后初始化 `temperatureNoise`（seed 1234）、`GRASS_COLOR_NOISE`（seed 2345）、`DOUBLE_PLANT_GENERATOR`。
- 内部类：`Height`（:654，rootHeight/variation + `attenuate()`）、`SpawnListEntry extends WeightedRandom.Item`（:671）、`enum TempCategory { OCEAN, COLD, MEDIUM, WARM }`（:691）。

### WorldChunkManager（WorldChunkManager.java）

把 `GenLayer` 的 int 输出翻译为群系对象。

- 字段：`private GenLayer genBiomes`（低分辨率，1/4 尺度，用于生成/搜索）、`private GenLayer biomeIndexLayer`（逐方块分辨率）、`private BiomeCache biomeCache`、`private List<BiomeGenBase> biomesToSpawnIn`（WorldChunkManager.java:17-24）。
- `public WorldChunkManager(long seed, WorldType worldTypeIn, String options)`（:41）调 `GenLayer.initializeAllBiomeGenerators(seed, worldTypeIn, options)` 拿两层。
- 关键方法签名：
  - `public BiomeGenBase getBiomeGenerator(BlockPos pos, BiomeGenBase biomeGenBaseIn)`（:68）— 走 `biomeCache.func_180284_a`。
  - `public BiomeGenBase[] getBiomesForGeneration(BiomeGenBase[] biomes, int x, int z, int width, int height)`（:128）— 用 `genBiomes`；被 `ChunkProviderGenerate.java:116` 每区块调用。
  - `public BiomeGenBase[] getBiomeGenAt(BiomeGenBase[] listToReuse, int x, int z, int width, int length, boolean cacheFlag)`（:174）— cacheFlag 且对齐 16x16 时直接拷 `biomeCache.getCachedBiomes(x, z)`。
  - `public BlockPos findBiomePosition(int x, int z, int range, List<BiomeGenBase> biomes, Random random)`（:243）— 出生点与结构（如神殿）选址用。
  - `public boolean areBiomesViable(int p_76940_1_, int p_76940_2_, int p_76940_3_, List<BiomeGenBase> p_76940_4_)`（:205）。
  - `public void cleanupCache()`（:275）— `WorldServer.java:175` 每 tick 调。
- 每个查询方法先 `IntCache.resetIntCache()`（如 :78、:130、:176），复用 `GenLayer` 的 int 数组池。

### BiomeDecorator（BiomeDecorator.java）

- `public void decorate(World worldIn, Random random, BiomeGenBase biome, BlockPos p_180292_4_)`（BiomeDecorator.java:141）：入口做重入保护 — `if (this.currentWorld != null) { throw new RuntimeException("Already decorating"); }`（:143-145）；随后从 `worldIn.getWorldInfo().getGeneratorOptions()` 解析 `ChunkProviderSettings`（:150-159），构建 11 个 `WorldGenMinable`，调 `genDecorations(biome)`，最后把 `currentWorld` / `randomGenerator` 置回 null。
- `protected void genDecorations(BiomeGenBase biomeGenBaseIn)`（:180）：顺序为 `generateOres()` → 沙/黏土/沙砾 → 树（`biomeGenBaseIn.genBigTreeChance(this.randomGenerator)`，:216）→ 大蘑菇 → 花（`pickRandomFlower`）→ 草 → 枯木 → 睡莲 → 蘑菇 → 甘蔗 → 南瓜 → 仙人掌 → `generateLakes` 时的水/岩浆流。数量由 `treesPerChunk`、`flowersPerChunk = 2`、`grassPerChunk = 1`、`sandPerChunk = 1`、`sandPerChunk2 = 3`、`clayPerChunk = 1` 等 protected 字段控制（:82-136），子群系构造器直接改写这些字段。
- `protected void generateOres()`（:481）：矿石数量/高度全部来自 `chunkProviderSettings`（自定义世界选项可改）。

### BiomeCache（BiomeCache.java）

- `public BiomeCache.Block getBiomeCacheBlock(int x, int z)`（BiomeCache.java:26）：key 为 `(long)x & 4294967295L | ((long)z & 4294967295L) << 32`（x、z 先 `>> 4`），miss 时 new `Block`（其构造器一次性拉取 16x16 的 `getRainfall` 与 `getBiomeGenAt(..., false)`，:97-98 — cacheFlag=false 防递归）。
- `public void cleanupCache()`（:53）：距上次清理超 7500ms 才执行，淘汰 30000ms 未访问的块。时间源是 `MinecraftServer.getCurrentTimeMillis()`（:40、:55）。
- 无任何锁：`cacheMap`/`cache` 均非线程安全，只能在拥有该 World 的线程访问。

### BiomeGenMesa（BiomeGenMesa.java）

唯一完全重写 `genTerrainBlocks` 的群系（不调 `generateBiomeTerrain`）。

- `public void genTerrainBlocks(World worldIn, Random rand, ChunkPrimer chunkPrimerIn, int x, int z, double noiseVal)`（BiomeGenMesa.java:71）：惰性按 `worldIn.getSeed()` 重建条带表与噪声（:73-85，`field_150622_aD` 记录上次种子）；`field_150626_aH`（Bryce 变体）时用双 Perlin 造尖塔（:88-108）。
- `private void func_150619_a(long p_150619_1_)`（:230）：以世界种子生成 `IBlockState[64] field_150621_aC` 染色黏土条带（ORANGE/YELLOW/BROWN/RED/WHITE/SILVER）。
- `private IBlockState func_180629_a(int p_180629_1_, int p_180629_2_, int p_180629_3_)`（:311）：按 y+噪声偏移取条带；注意其噪声两个参数都传 `p_180629_1_`（x），与原版一致的"bug"。

## 时序与生命周期

- 类加载：首次 touch `BiomeGenBase` 时静态初始化跑完全部群系注册与突变体创建（BiomeGenBase.java:603-652）。这发生在主线程首次引用任一群系常量时；注册顺序即字段声明顺序，突变体 id = 原 id + 128。
- 世界创建：`WorldProvider.registerWorldChunkManager()`（WorldProvider.java:84-92）按维度/WorldType 构造 `WorldChunkManager(worldObj)` 或 `WorldChunkManagerHell`。
- 每 tick：`WorldServer.tick()` 调 `this.provider.getWorldChunkManager().cleanupCache()`（WorldServer.java:175），集成服务端线程执行。
- 区块生成（集成服务端线程）：`ChunkProviderGenerate.provideChunk` → `getBiomesForGeneration`（低清）→ 地形噪声 → `loadBlockGeneratorData`（逐块）→ 每列 `biomegenbase.genTerrainBlocks(...)`（ChunkProviderGenerate.java:197）；populate 阶段 → `biomegenbase.decorate(...)`（ChunkProviderGenerate.java:461）→ `theBiomeDecorator.decorate(...)`。
- 每帧（客户端主线程/渲染线程）：无主动逻辑；被动地被 `BiomeColorHelper.getGrassColorAtPos` 等在区块重建/方块着色时调用，以及 `World.java:1441-1443` 计算天空颜色。
- 线程归属：群系查询在多人客户端走主线程（`Chunk#getBiome` 读区块 blob）；世界生成与 `BiomeCache` 在集成服务端线程。无 Netty EventLoop 参与。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public BiomeGenBase getBiomeGenerator(BlockPos pos, BiomeGenBase biomeGenBaseIn)` | WorldChunkManager.java:68 | 每次按坐标查群系（出生点、刷怪、`Chunk#getBiome` 回填） | 统一改写/伪造群系查询结果（如全图单群系、群系显示欺骗） | 多人模式客户端多走 `Chunk` 内存的 biome 数组，此处只覆盖生成/回填路径 |
| `public void decorate(World worldIn, Random rand, BlockPos pos)` | BiomeGenBase.java:420 | 区块 populate 阶段（集成服务端线程） | 拦截/替换整块装饰逻辑，注入自定义地物 | 有 "Already decorating" 重入保护；改动影响世界种子一致性 |
| `public void genTerrainBlocks(World worldIn, Random rand, ChunkPrimer chunkPrimerIn, int x, int z, double noiseVal)` | BiomeGenBase.java:444 | `ChunkProviderGenerate.java:197` 每区块每列 | 替换地表方块方案（自定义群系地表） | 热路径（每区块 256 列）；某些子类改写 `this.topBlock` 属实例可变状态，不可并发 |
| `public int getGrassColorAtPos(BlockPos pos)` | BiomeGenBase.java:425 | 区块渲染重建、`BlockReed`/`BlockTallGrass` 着色 | 全局改草色（自定义着色/季节效果） | 改后需触发区块重渲染才可见 |
| `public int getFoliageColorAtPos(BlockPos pos)` | BiomeGenBase.java:432 | `BlockLeaves.java:49`、`BlockVine.java:226` 等 | 全局改叶色 | 同上 |
| `private static int getColorAtPos(IBlockAccess blockAccess, BlockPos pos, BiomeColorHelper.ColorResolver colorResolver)` | BiomeColorHelper.java:30 | 每个需要 biome 着色的方块渲染时 | 单点接管全部草/叶/水颜色混合（去 3x3 平滑、性能优化） | 渲染热路径，每次分配 MutableBlockPos 迭代 9 格；private static，需改字节码或改调用方 |
| `public int getSkyColorByTemp(float p_76731_1_)` | BiomeGenBase.java:328 | `World.java:1443` 每帧算天空色 | 自定义天空颜色 | 每帧调用，保持纯函数 |
| `public List<BiomeGenBase.SpawnListEntry> getSpawnableList(EnumCreatureType creatureType)` | BiomeGenBase.java:335 | `SpawnerAnimals.java:219`、各 ChunkProvider 的 `getPossibleCreatures` | 增删刷怪条目（单人调整刷怪） | 返回的是内部 live List，直接改会永久影响该群系单例 |
| `public final float getFloatTemperature(BlockPos pos)` | BiomeGenBase.java:407 | `World#canSnowAt`（World.java:2741）、天空色、地表冰判定 | 观察点：温度决定雪/冰/雨 | `final`，不能覆写，只能在调用方拦截 |
| `public void cleanupCache()` | WorldChunkManager.java:275 | `WorldServer.java:175` 每 tick | 挂缓存统计/自定义淘汰策略 | 服务端线程；别在此做耗时操作 |
| `public BlockPos findBiomePosition(int x, int z, int range, List<BiomeGenBase> biomes, Random random)` | WorldChunkManager.java:243 | 世界出生点选择、结构选址 | 控制出生点/结构落点 | 消耗 `random`，改动会破坏种子确定性 |

## 数据与协议

本包不直接读写封包或 NBT。相关的数据面：

- 群系 ID 注册表（内存内，隐式协议）：

| 字段/入口 | 类型 | 读写方法 | 取值含义 |
|---|---|---|---|
| `biomeList` | `BiomeGenBase[256]`（BiomeGenBase.java:69） | 写：构造器 `biomeList[id] = this`（:199）；读：`getBiome(int)`（:584）、`getBiomeFromBiomeList(int, BiomeGenBase)`（:589） | 下标即群系 id；0-39 原版，+128 为突变体；越界/空槽回退 `ocean` 并 warn |
| `BIOME_ID_MAP` | `Map<String, BiomeGenBase>`（:71） | 静态块填充（:636） | biomeName → 群系；重名抛 `Error` |
| `biomeID` | `public final int`（:172） | 只读 | 与 `Chunk` 的 biome 字节数组（`Chunk.java:1395` 用 `BiomeGenBase.getBiome(k)` 反查）及存档 NBT `Biomes` 字节数组的取值一致；服务端 `S21PacketChunkData` 携带的 biome 字节也用此 id（本包不参与编解码） |
| `color` / `field_150609_ah` | `int` | `setColor(int)`（:297）/ `func_150557_a(int, boolean)`（:309） | RGB 主色/次色，地图与 UI 用 |
| `waterColorMultiplier` | `int`（:154） | 构造器赋值（沼泽 14745518） | 水渲染乘色 |

- `BiomeDecorator` 的矿石参数来自 `ChunkProviderSettings.Factory.jsonToFactory(s)`（BiomeDecorator.java:154），即世界存档 `generatorOptions` JSON 字符串（自定义世界类型），字段如 `dirtSize/dirtCount/dirtMinHeight/dirtMaxHeight` 等（:483-493 使用处）。

## 不变量与陷阱

- 群系 id 空间不变量：`biomeList` 长度 256，突变体固定为 `原 id + 128`（BiomeGenBase.java:548-551）；`Chunk` 存档与网络传输的 biome 字节必须能在此表中反查，否则回退 ocean 并刷 warn 日志。
- `getBiomeFromBiomeList` 的边界判断是 `biomeId >= 0 && biomeId <= biomeList.length`（:591）——`<=` 而非 `<`，id 恰为 256 会 `ArrayIndexOutOfBoundsException` 而非走回退分支。原版同款 off-by-one，勿"顺手修"，除非确认没有依赖此行为的调用。
- `setTemperatureRainfall` 禁止 0.1~0.2 开区间温度（:230-233），自定义群系踩到直接抛异常。
- `BiomeGenBase` 静态初始化顺序敏感：群系字段声明顺序即注册顺序；在静态块跑完前调用 `getBiome` 可能拿到 null 槽。任何提前触发类加载的 mixin/hook 需小心。
- 群系单例是全局可变状态：`BiomeGenHills#genTerrainBlocks`（BiomeGenHills.java:69-81）、`BiomeGenTaiga`（BiomeGenTaiga.java:94-104）等在生成期间改写 `this.topBlock/fillerBlock`；`DOUBLE_PLANT_GENERATOR` 是共享 static，`setPlantType` 后立即使用（BiomeGenPlains.java:73、BiomeGenSavanna.java:43、BiomeGenTaiga.java:77）。**世界生成必须单线程**，并行化区块生成会产生数据竞争。
- `BiomeDecorator.decorate` 有显式重入保护（"Already decorating"，BiomeDecorator.java:145），且每次调用都重新 JSON 解析 `ChunkProviderSettings` 并 new 一批 `WorldGenMinable`（:154-173）——per-chunk 分配热点，性能敏感改造可在此下手。
- `BiomeCache` 与 `IntCache.resetIntCache()`（WorldChunkManager.java:78 等）均非线程安全；`IntCache` 是跨调用共享的 int[] 池，任何线程外调用 `getInts` 路径都会互相踩内存。
- `BiomeCache.Block` 构造器调 `getBiomeGenAt(..., false)`（BiomeCache.java:98），cacheFlag=false 是防无限递归的关键；自定义 `WorldChunkManager` 覆写时必须保留该语义（参考 `WorldChunkManagerHell.getBiomeGenAt` 直接绕开缓存，WorldChunkManagerHell.java:77-80）。
- `BiomeEndDecorator.genDecorations`（BiomeEndDecorator.java:23-28）在装饰 (0,0) 区块时直接 spawn `EntityDragon`——装饰阶段有副作用实体生成，重复 populate 该区块会重复出龙。
- LWJGL3/JDK25 移植面：本包纯逻辑，无 GL/输入依赖，未见移植改动；时间源用 `MinecraftServer.getCurrentTimeMillis()` 而非 `System.nanoTime`。JDK 25 下注意 `new LongHashMap()`（BiomeCache.java:15）为 raw type，仅是编译警告。
- 与原版差异：逐类对读未发现语义偏离 1.8.9 MCP 的改动（本包看起来是原样保留）；但不要据"常识"外推其它包。

## 交叉引用

- net.minecraft.world → `World#getBiomeGenForCoords`（World.java:172）、`World#canSnowAt`（World.java:2738）、`WorldProvider#registerWorldChunkManager`（WorldProvider.java:84-92）、`WorldServer#tick` → `WorldChunkManager#cleanupCache`（WorldServer.java:175）、`SpawnerAnimals#getSpawnableList/getSpawningChance`（SpawnerAnimals.java:219-223）
- net.minecraft.world.chunk → `Chunk#getBiome(BlockPos, WorldChunkManager)`（Chunk.java:1382）、`ChunkPrimer#setBlockState/getBlockState`（genTerrainBlocks 写入目标）
- net.minecraft.world.gen → `ChunkProviderGenerate#setBlocksInChunk/replaceBlocksForBiome`（ChunkProviderGenerate.java:116、197）、`ChunkProviderGenerate#populate` → `BiomeGenBase#decorate`（:461）、`ChunkProviderSettings.Factory#jsonToFactory`（BiomeDecorator.java:154）
- net.minecraft.world.gen.layer → `GenLayer#initializeAllBiomeGenerators`（WorldChunkManager.java:45）、`IntCache#resetIntCache`
- net.minecraft.world.gen.feature → `WorldGenAbstractTree`、`WorldGenMinable`、`WorldGenDoublePlant` 等全部装饰生成器
- net.minecraft.block → `BlockGrass#colorMultiplier` → `BiomeColorHelper#getGrassColorAtPos`（BlockGrass.java:53）、`BlockLeaves`（BlockLeaves.java:49）、`BlockVine`（BlockVine.java:226）、`BlockDoublePlant`（BlockDoublePlant.java:152）
- net.minecraft.world（着色器）→ `ColorizerGrass#getGrassColor` / `ColorizerFoliage#getFoliageColor`（BiomeGenBase.java:429、436）
- net.minecraft.entity.* → 刷怪表条目类（EntitySheep/EntityZombie/EntityGhast/EntityDragon 等）
- net.minecraft.server → `MinecraftServer#getCurrentTimeMillis`（BiomeCache.java:40）
- net.minecraft.world.chunk.storage → `AnvilSaveConverter` 构造 `WorldChunkManager(Hell)` 做旧档转换（AnvilSaveConverter.java:148-157）

## 覆盖声明

- 完整读取了 25/25 个文件（bucket 内全部文件均用 Read 整读）。
- 逐行精读：BiomeGenBase、WorldChunkManager、WorldChunkManagerHell、BiomeCache、BiomeDecorator、BiomeEndDecorator、BiomeColorHelper、BiomeGenMesa、BiomeGenForest、BiomeGenMutated、BiomeGenHills、BiomeGenTaiga、BiomeGenSwamp、BiomeGenPlains、BiomeGenSavanna、BiomeGenSnow、BiomeGenJungle。
- 结构性通读（文件短小、整读但未逐行推演逻辑）：BiomeGenBeach、BiomeGenDesert、BiomeGenEnd、BiomeGenHell、BiomeGenMushroomIsland、BiomeGenOcean、BiomeGenRiver、BiomeGenStoneBeach。
- 交叉引用行号（World.java、Chunk.java、ChunkProviderGenerate.java、WorldServer.java、WorldProvider*.java、Block*.java、SpawnerAnimals.java、AnvilSaveConverter.java）均经 grep 验证，但这些文件本身未整读。
