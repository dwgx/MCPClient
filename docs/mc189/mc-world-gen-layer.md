---
area: net/minecraft/world/gen/layer
slug: mc-world-gen-layer
files: 21
lines: 1923
tier: C
---

# net/minecraft/world/gen/layer — 生物群系分层生成器

## 定位

本包实现 1.8.9 的"分层放大"式生物群系生成：一条由 `GenLayer` 子类组成的装饰器链，从 1:4096 比例的大陆噪声开始，逐层缩放、加细节，最终输出每个方块列的 biome ID（`int[]`）。它是纯确定性函数管线——同一 seed 同一坐标永远得到同一结果，没有任何持久状态（除了 `IntCache` 的数组池）。

- 唯一的入口是 `GenLayer.initializeAllBiomeGenerators(long, WorldType, String)`（`GenLayer.java:28`），由 `net.minecraft.world.biome.WorldChunkManager` 构造函数调用（`WorldChunkManager.java:45`），返回的 `GenLayer[]` 中 `[0]` 为 1:4 分辨率的 `genBiomes`（用于结构布局、可生成点判断），`[1]` 为 1:1 分辨率的 `biomeIndexLayer`（`GenLayerVoronoiZoom` 包裹，用于每方块 biome 查询）。
- 它向下依赖 `net.minecraft.world.biome.BiomeGenBase`（biome ID 常量与 `getBiome` 查表）、`net.minecraft.world.gen.ChunkProviderSettings`（CUSTOMIZED 世界类型的 JSON 参数）、`net.minecraft.world.WorldType`。
- 如果本包消失：单人游戏（集成服务端）的地形生成、`AnvilSaveConverter` 的旧档转换、以及一切经 `WorldChunkManager` 的 biome 查询（草/树叶着色、生物生成表、结构选址）全部瘫痪。注意多人客户端里 chunk 的 biome 数组来自服务端封包，不经过本包。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| GenLayer | 239 | abstract，无父类 | 抽象基类：LCG 伪随机数（三重播种）、链构建静态工厂、biome 比较工具方法 |
| GenLayerAddIsland | 103 | extends GenLayer | 在海陆边界随机添加/侵蚀陆地，产生破碎海岸与小岛 |
| GenLayerAddMushroomIsland | 50 | extends GenLayer | 四邻全海洋的海洋格有 1/100 概率变为 mushroomIsland |
| GenLayerAddSnow | 59 | extends GenLayer | 把陆地(1)按 1/6、1/6、4/6 概率改写为温度组 4(snow)/3(cold)/1(warm) |
| GenLayerBiome | 118 | extends GenLayer | 把温度组值映射为具体 biome ID；处理 DEFAULT_1_1 / CUSTOMIZED / fixedBiome |
| GenLayerBiomeEdge | 166 | extends GenLayer | 在大 biome 边缘生成过渡带（mesa 边、megaTaiga→taiga、extremeHillsEdge、jungleEdge 等） |
| GenLayerDeepOcean | 70 | extends GenLayer | 四邻全为海洋(0)的海洋格改写为 deepOcean |
| GenLayerEdge | 139 | extends GenLayer（含 enum Mode） | 三合一：COOL_WARM/HEAT_ICE 抑制冷热相邻，SPECIAL 按 1/13 在高 8 位打变体标记 |
| GenLayerFuzzyZoom | 17 | extends GenLayerZoom | 覆写 selectModeOrRandom 为纯随机选取，用于最早期的模糊放大 |
| GenLayerHills | 198 | extends GenLayer | 双父链：按 river 噪声与随机把 biome 换成对应 hills / M(+128) 变体 |
| GenLayerIsland | 34 | extends GenLayer | 链源头：1/10 概率产陆(1)否则海(0)，并强制原点附近一格为陆 |
| GenLayerRareBiome | 49 | extends GenLayer | 1/57 概率把 plains 改为 plains+128（Sunflower Plains） |
| GenLayerRemoveTooMuchOcean | 45 | extends GenLayer | 四邻与自身全为海时 1/2 概率填成陆地，压缩过大海洋 |
| GenLayerRiver | 54 | extends GenLayer | 对 riverInit 噪声取邻域差异，不同则输出 river.biomeID，相同输出 -1 |
| GenLayerRiverInit | 31 | extends GenLayer | 陆地格填入 nextInt(299999)+2 的随机数作为河流/hills 噪声源 |
| GenLayerRiverMix | 70 | extends GenLayer | 双链合流：把 river 链结果叠进 biome 链（icePlains→frozenRiver，mushroom→shore） |
| GenLayerShore | 167 | extends GenLayer | 海陆交界生成 beach/coldBeach/stoneBeach/mushroomIslandShore，jungle/mesa 特判 |
| GenLayerSmooth | 66 | extends GenLayer | 邻域平滑：左右相等或上下相等时取邻值，消除单格噪点 |
| GenLayerVoronoiZoom | 95 | extends GenLayer | 1:4→1:1 的 Voronoi 细胞放大，产生自然的锯齿 biome 边界 |
| GenLayerZoom | 70 | extends GenLayer | 2 倍双线性风格放大（selectModeOrRandom 决定新格）；静态 magnify 重复包裹 |
| IntCache | 83 | 无（静态工具类） | int[] 数组池：256 小数组 + 动态大数组，避免链式调用海量分配 |

## 核心类详解

### GenLayer（GenLayer.java）

所有层的基类，同时承载链构建工厂与 LCG PRNG。

关键字段（`GenLayer.java:13-26`）：
- `private long worldGenSeed` — 由世界 seed 与 baseSeed 混合而来
- `protected GenLayer parent` — 上游层
- `private long chunkSeed` — 按 (x,z) 坐标派生的最终随机种子
- `protected long baseSeed` — 构造时由传入的层 seed 三轮混淆得到

关键方法（签名逐字）：
- `public static GenLayer[] initializeAllBiomeGenerators(long seed, WorldType p_180781_2_, String p_180781_3_)` — `GenLayer.java:28`。硬编码整条链：Island → FuzzyZoom → AddIsland×n → RemoveTooMuchOcean → AddSnow → Edge(COOL_WARM/HEAT_ICE/SPECIAL) → Zoom×2 → AddIsland → AddMushroomIsland → DeepOcean，之后分叉出 biome 链（GenLayerBiome → BiomeEdge → Hills → RareBiome → Zoom 循环内插 AddIsland/Shore → Smooth）与 river 链（RiverInit → Zoom → River → Smooth），最后 `GenLayerRiverMix` 合流并套 `GenLayerVoronoiZoom`。返回 `{genlayerrivermix, genlayer3, genlayerrivermix}`（`GenLayer.java:98`）。`WorldType.LARGE_BIOMES` 时 biomeSize 由 4 改 6（`GenLayer.java:60-63`）；`WorldType.CUSTOMIZED` 时 biomeSize/riverSize 取自 `ChunkProviderSettings`（`GenLayer.java:53-58`）。
- `public void initWorldGenSeed(long seed)` — `GenLayer.java:116`。递归向 parent 传播，然后用常量 `6364136223846793005L` / `1442695040888963407L`（Knuth MMIX LCG）三轮混入 baseSeed。
- `public void initChunkSeed(long p_75903_1_, long p_75903_3_)` — `GenLayer.java:136`。每个采样点调用一次，把 (x,z) 四轮混入 worldGenSeed。
- `protected int nextInt(int p_75902_1_)` — `GenLayer.java:152`。取 `chunkSeed >> 24` 取模，负数修正，然后推进 chunkSeed。**注意模偏差是刻意保留的原版行为，不能"修复"，否则地形不兼容。**
- `public abstract int[] getInts(int areaX, int areaY, int areaWidth, int areaHeight)` — `GenLayer.java:170`。唯一的抽象方法，行主序返回 `areaWidth * areaHeight` 个 int。
- 工具方法：`protected static boolean biomesEqualOrMesaPlateau(int biomeIDA, int biomeIDB)` — `GenLayer.java:172`；`protected static boolean isBiomeOceanic(int p_151618_0_)` — `GenLayer.java:219`；`protected int selectRandom(int... p_151619_1_)` — `GenLayer.java:227`；`protected int selectModeOrRandom(int p_151617_1_, int p_151617_2_, int p_151617_3_, int p_151617_4_)` — `GenLayer.java:235`。

### GenLayerZoom / GenLayerVoronoiZoom

- `GenLayerZoom.getInts`（`GenLayerZoom.java:15`）：向 parent 请求半分辨率区域（`(areaWidth >> 1) + 2`），每个源格扩成 2×2，新格由 `selectRandom` / `selectModeOrRandom` 决定，最后按 `areaX & 1` / `areaY & 1` 偏移 `System.arraycopy` 裁剪（`GenLayerZoom.java:46-51`）。静态工具 `public static GenLayer magnify(long p_75915_0_, GenLayer p_75915_2_, int p_75915_3_)`（`GenLayerZoom.java:59`）连续包裹 n 层 Zoom，被 `initializeAllBiomeGenerators` 多处使用。
- `GenLayerFuzzyZoom` 仅覆写 `protected int selectModeOrRandom(...)` 为 `this.selectRandom(...)`（`GenLayerFuzzyZoom.java:13-16`），使最早的放大完全随机、边界更碎。
- `GenLayerVoronoiZoom.getInts`（`GenLayerVoronoiZoom.java:15`）：4 倍放大。先 `areaX = areaX - 2`，向 parent 取 1:4 数据，对每个 2×2 源格窗口生成 4 个带 ±1.8 随机抖动的 Voronoi 站点（`3.6D` 抖动幅度，`GenLayerVoronoiZoom.java:35-47`），16 个子格各取最近站点的 biome，注意取值时 `& 255` 掩掉高位标记（`GenLayerVoronoiZoom.java:48-49`）。它是 `WorldChunkManager.biomeIndexLayer` 的最外层，因此是每次 1:1 biome 查询的实际执行者。

### GenLayerBiome（GenLayerBiome.java）

温度组 → 具体 biome 的查表层。字段（`GenLayerBiome.java:9-13`）：
- `private BiomeGenBase[] field_151623_c` — warm 组：desert×3, savanna×2, plains
- `private BiomeGenBase[] field_151621_d` — medium 组：forest, roofedForest, extremeHills, plains, birchForest, swampland
- `private BiomeGenBase[] field_151622_e` — cold 组：forest, extremeHills, taiga, plains
- `private BiomeGenBase[] field_151620_f` — snow 组：icePlains×3, coldTaiga
- `private final ChunkProviderSettings field_175973_g` — CUSTOMIZED 世界时非 null

构造函数 `public GenLayerBiome(long p_i45560_1_, GenLayer p_i45560_3_, WorldType p_i45560_4_, String p_i45560_5_)`（`GenLayerBiome.java:15`）：`WorldType.DEFAULT_1_1` 会替换 warm 表为 1.1 版组合（`GenLayerBiome.java:22`）。`getInts`（`GenLayerBiome.java:39`）里 `int l = (k & 3840) >> 8` 取出 `GenLayerEdge.SPECIAL` 写入的高位标记（`GenLayerBiome.java:50-51`）：warm+special→mesaPlateau/mesaPlateau_F，medium+special→jungle，cold+special→megaTaiga；`fixedBiome >= 0` 时整图恒定（`GenLayerBiome.java:53-56`）。

### GenLayerHills（GenLayerHills.java）

唯一带日志的层。字段：`private static final Logger logger`、`private GenLayer field_151628_d`（第二条输入链，接 riverInit 的 Zoom 结果，`GenLayerHills.java:9-10`）。构造 `public GenLayerHills(long p_i45479_1_, GenLayer p_i45479_3_, GenLayer p_i45479_4_)`（`GenLayerHills.java:12`）。`getInts`（`GenLayerHills.java:23`）：用 river 噪声 `(l - 2) % 29 == 1` 且 `k < 128` 时直接输出 `k + 128` 的 M 变体（`GenLayerHills.java:43-53`）；否则 1/3 概率或 `(l - 2) % 29 == 0`（flag）时查 hills 映射表（desert→desertHills 等，`GenLayerHills.java:62-137`），deepOcean 有 1/3×nextInt(2) 概率产平原/森林岛；替换只有在 ≥3 个邻格与原 biome 相同（不在边缘）时才落地（`GenLayerHills.java:157-190`）。`k > 255` 时 `logger.debug("old! " + k)`（`GenLayerHills.java:38-41`）。

### IntCache（IntCache.java）

静态数组池。字段（`IntCache.java:8-12`）：`private static int intCacheSize = 256`、`freeSmallArrays` / `inUseSmallArrays` / `freeLargeArrays` / `inUseLargeArrays`（均为 `List<int[]>`）。

- `public static synchronized int[] getIntCache(int p_76445_0_)` — `IntCache.java:14`。≤256 走小池（固定 256 长度）；请求超过当前 `intCacheSize` 时**清空大池并抬高水位**（`IntCache.java:31-39`）；否则复用大池。返回数组**未清零**，且实际长度可能大于请求值——每层都必须自己写满所有请求的格子。
- `public static synchronized void resetIntCache()` — `IntCache.java:57`。把 in-use 全部移回 free（并各丢弃一个 free 数组作为缓慢收缩）。由 `WorldChunkManager` 在每次查询前调用（`WorldChunkManager.java:78,130,176,207,245`）。
- `public static synchronized String getCacheSizes()` — `IntCache.java:79`。被 `CrashReport.java:127` 用于崩溃报告的 "IntCache" 一节。

## 时序与生命周期

- **构建**：世界加载时 `WorldProvider.registerWorldChunkManager` → `new WorldChunkManager(world)` → `GenLayer.initializeAllBiomeGenerators`（`WorldChunkManager.java:45`）。链构建后立即 `initWorldGenSeed(seed)` 递归播种（`GenLayer.java:96-97`），此后整条链只读（chunkSeed 除外）。
- **每次查询**：`WorldChunkManager` 的各查询方法先 `IntCache.resetIntCache()`，再对 `genBiomes`（1:4）或 `biomeIndexLayer`（1:1）调 `getInts`；调用自顶向下递归，每层向 parent 请求（通常外扩 1 格或半分辨率）的区域，处理后返回借自 IntCache 的数组。无 tick、无帧逻辑。
- **线程归属**：单人时在集成服务端线程执行（chunk 生成路径）；`IntCache` 的三个方法均 `synchronized`，但 `GenLayer.chunkSeed` 是实例可变状态且**无任何同步**——同一条链绝不能被两个线程并发调用 `getInts`。原版通过"每个 world 一个 WorldChunkManager、只在该 world 的服务端线程使用"来保证。

## 挂钩点（Hook Points）

本包内部方法都在紧密递归中被调用（每 chunk 数千次），直接 hook 单层的成本高、收益低；有价值的接管点集中在链的入口/出口。

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public static GenLayer[] initializeAllBiomeGenerators(long seed, WorldType p_180781_2_, String p_180781_3_)` | GenLayer.java:28 | WorldChunkManager 构造时一次 | 替换/插入自定义层实现全新 biome 分布（Forge 正是在此挂 WorldTypeEvent.InitBiomeGens） | 改动即改变世界生成，老存档 chunk 边界会出现 biome 断层 |
| `public abstract int[] getInts(int areaX, int areaY, int areaWidth, int areaHeight)` | GenLayer.java:170 | 每次 biome 查询递归调用 | 包一层代理 GenLayer 可观察/改写任意中间结果（biome 调试可视化、强制 biome） | 返回数组必须来自 IntCache 且写满 width*height；极热路径，勿加分配或日志 |
| `public void initWorldGenSeed(long seed)` | GenLayer.java:116 | 链构建后一次（RiverMix 覆写见 GenLayerRiverMix.java:21） | 自定义层若持有多条 parent 链，必须仿照 GenLayerRiverMix 覆写以播种所有分支 | 漏播种的分支 worldGenSeed 为 0，产出与 seed 无关的地形 |
| `public static synchronized void resetIntCache()` | IntCache.java:57 | WorldChunkManager 每次查询前 | 观察分配水位、排查泄漏 | 在 getInts 递归尚未完成时调用会导致在用数组被复用、数据损坏 |
| `public static GenLayer magnify(long p_75915_0_, GenLayer p_75915_2_, int p_75915_3_)` | GenLayerZoom.java:59 | 链构建期间 | 调整放大倍数即可实现自定义 biome 尺度（LARGE_BIOMES 就是 i=6） | 倍数改变会整体平移 biome 图，与现有存档不兼容 |

## 数据与协议

无封包、NBT、文件格式。唯一的"协议"是层间 int 值的约定：

| 阶段 | 取值 | 含义 |
|---|---|---|
| Island～RemoveTooMuchOcean | 0 / 1 | 海洋 / 陆地 |
| AddSnow～Edge | 0,1,2,3,4 | 海洋 / warm / medium(由 COOL_WARM 产生) / cold / snow 温度组 |
| Edge(SPECIAL) 之后 | `k \| (1 + nextInt(15)) << 8 & 3840` | 高位 8-11 bit 为变体标记（GenLayerEdge.java:123），由 GenLayerBiome.java:50 读出、GenLayerRiverMix.java:50 与 GenLayerVoronoiZoom.java:48-49 用 `& 255` 剥离 |
| RiverInit 支链 | 0 或 `nextInt(299999) + 2` | 河流/hills 共用噪声；GenLayerRiver.func_151630_c 压缩为 0/1/2/3 后比邻域，GenLayerHills 用 `(l - 2) % 29` |
| Biome 之后 | BiomeGenBase.biomeID（+128 为 mutated 变体） | 最终输出；River 链输出 `BiomeGenBase.river.biomeID` 或 -1（GenLayerRiver.java:36-43） |

## 不变量与陷阱

- **确定性是硬约束**：所有魔法常数（LCG 乘子 `6364136223846793005L`、增量 `1442695040888963407L`、`nextInt(299999)`、`% 29`、`3.6D` 抖动、`1/13`、`1/57` 等）与调用顺序共同决定世界形态。任何"清理"（包括调整 `initChunkSeed` 调用位置、改掉 `nextInt` 的模偏差）都会静默改变所有世界的生成结果。
- **IntCache 返回的数组是脏的、且可能比请求长**：每层必须写满自己请求的每个格子；小池数组恒为 256 长，`areaWidth * areaHeight` 小于实际长度是常态，不能用 `arr.length` 推断区域大小。
- **数组生命周期只到下一次 `resetIntCache()`**：`WorldChunkManager.getBiomeGenAt(..., cacheFlag=false)` 这类路径每次都 reset，层返回的数组绝不能被外部长期持有——要持有必须拷贝。
- **`GenLayerRiverMix` / `GenLayerHills` 是双输入层**：`GenLayerRiverMix` 覆写了 `initWorldGenSeed`（`GenLayerRiverMix.java:21`）以播种两条链；`GenLayerHills` 的第二条链 `field_151628_d` 依赖它在工厂里恰好也被主调用链播种（`GenLayer.java:96` 从 genlayerrivermix 递归可达）。新增多输入层时必须处理播种，否则地形与 seed 脱钩。
- **线程安全**：`IntCache` 是全局静态、`synchronized`，但两个 `WorldChunkManager`（如主世界+末地）并发 getInts 时会共享同一池——原版依赖所有维度的 chunk 生成都在同一服务端线程串行。移植到多线程 chunk 生成前必须把 IntCache 改为 ThreadLocal（Forge 后期版本的做法）。`GenLayer.chunkSeed` 同样非线程安全。
- **LWJGL3/JDK25 移植**：本包不触 GL/输入/NIO，无移植改动点；纯 long 运算依赖 Java 溢出语义，JDK 25 下行为不变。`GenLayerHills` 里保留了 log4j `logger.debug`（`GenLayerHills.java:40`），是包内唯一的日志调用。
- `GenLayerIsland.getInts` 强制 `aint[-areaX + -areaY * areaWidth] = 1`（`GenLayerIsland.java:27-30`），保证世界原点附近必有陆地——这是出生点搜索能终止的前提之一。

## 交叉引用

- `net.minecraft.world.biome` → `WorldChunkManager#<init>`（调用 `GenLayer.initializeAllBiomeGenerators`，`WorldChunkManager.java:45`）、`WorldChunkManager#getBiomesForGeneration` / `#getBiomeGenAt`（调用 `GenLayer#getInts` 与 `IntCache.resetIntCache`）
- `net.minecraft.world.biome` → `BiomeGenBase#getBiome` / `#biomeID` / `#isEqualTo` / `#getTempCategory` / `#isSnowyBiome` / `#getBiomeClass`（几乎每个层都查表；`GenLayerShore` 还引用 `BiomeGenJungle`、`BiomeGenMesa`）
- `net.minecraft.world.gen` → `ChunkProviderSettings.Factory#jsonToFactory`（CUSTOMIZED 参数，`GenLayer.java:55`、`GenLayerBiome.java:27`）
- `net.minecraft.world` → `WorldType`（`DEFAULT_1_1` / `CUSTOMIZED` / `LARGE_BIOMES` 分支）
- `net.minecraft.crash` → `CrashReport` / `CrashReportCategory` / `ReportedException`（`GenLayer#biomesEqualOrMesaPlateau` 的崩溃上下文）；反向：`CrashReport#populateEnvironment` 调 `IntCache.getCacheSizes()`（`CrashReport.java:127`）
- `net.minecraft.world.chunk.storage` → `AnvilSaveConverter#convertSaveDirectory`（构造 `WorldChunkManager` 间接使用本包，`AnvilSaveConverter.java:152`）

## 覆盖声明

完整读取了 21/21 个文件（bucket 内每个文件从第 1 行读到末行）。逐行精读：GenLayer、GenLayerZoom、GenLayerVoronoiZoom、GenLayerBiome、GenLayerHills、GenLayerEdge、GenLayerRiverMix、GenLayerShore、IntCache。其余（GenLayerAddIsland、GenLayerAddMushroomIsland、GenLayerAddSnow、GenLayerBiomeEdge、GenLayerDeepOcean、GenLayerFuzzyZoom、GenLayerIsland、GenLayerRareBiome、GenLayerRemoveTooMuchOcean、GenLayerRiver、GenLayerRiverInit、GenLayerSmooth）为全文通读但只做逻辑级归纳，未逐个核对每条邻域索引算式。外部调用方通过 grep 与 WorldChunkManager 定点读取确认，未通读 WorldChunkManager 全文。
