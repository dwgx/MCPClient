---
area: net/minecraft/world/gen/feature
slug: mc-world-gen-feature
files: 41
lines: 4227
tier: C
---

# net/minecraft/world/gen/feature

## 定位

本包是"小型地物生成器"集合：树、巨型蘑菇、矿脉、湖泊、地牢、仙人掌、花草、冰刺、沙漠水井等所有一次性、局部的世界装饰物。所有类都继承自同一个抽象基类 `WorldGenerator`，对外只暴露一个入口 `public abstract boolean generate(World worldIn, Random rand, BlockPos position)`（WorldGenerator.java:26）。

调用方（均在包外）：
- `net.minecraft.world.biome.BiomeDecorator` / `BiomeEndDecorator` 及各 `BiomeGenXxx` 子类 —— 区块装饰（populate）阶段批量调用各生成器；
- `net.minecraft.world.gen.ChunkProviderGenerate` / `ChunkProviderHell` / `ChunkProviderFlat` —— 区块生成时直接调用（湖泊、地牢、下界的火/岩浆/萤石等）；
- `net.minecraft.block.BlockSapling`（树苗生长时 new 各种树生成器，BlockSapling.java:75-149）与 `net.minecraft.block.BlockMushroom`（骨粉催化巨型蘑菇，BlockMushroom.java:92,96）—— 游戏运行期触发；
- `net.minecraft.world.WorldServer.createBonusChest`（WorldServer.java:861）—— 新建世界时生成奖励箱；
- `net.minecraft.world.gen.GeneratorBushFeature` —— 包外的一个 `WorldGenerator` 子类（下界蘑菇丛）。

它调用的下游：`World#setBlockState` / `World#getBlockState` / `World#isAirBlock`、`Blocks` 常量、方块状态 API（`IBlockState#withProperty`）、以及少量 TileEntity（箱子、刷怪笼）与实体（`EntityEnderCrystal`）。

如果本包消失：单人游戏的区块装饰阶段（地形之上的一切装饰）会全部失效，树苗/巨型蘑菇无法生长，地牢与奖励箱不会生成——即所有非结构类（structure 之外）的世界地物都消失。注意这是移植到客户端的完整代码，多人联机时这些逻辑不在客户端执行（方块由服务端下发），本包只服务于内置服务端（integrated server）。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| WorldGenerator | 43 | (abstract) | 所有地物生成器的基类，定义 `generate` 入口和带通知策略的 `setBlockAndNotifyAdequately` |
| WorldGenAbstractTree | 34 | abstract, extends WorldGenerator | 树类生成器公共基类：可替换方块判定 `func_150523_a`、垫土 `func_175921_a` |
| WorldGenBigMushroom | 222 | WorldGenerator | 巨型红/棕蘑菇（`mushroomType` 为 null 时随机选色） |
| WorldGenBigTree | 377 | WorldGenAbstractTree | 大橡树：叶节点列表 + 分枝算法（本包最复杂的树） |
| WorldGenBlockBlob | 72 | WorldGenerator | 苔石/圆石团块（megataiga 的 blob） |
| WorldGenCactus | 32 | WorldGenerator | 仙人掌，10 次随机散布，高 1-3 |
| WorldGenCanopyTree | 219 | WorldGenAbstractTree | 深色橡树（2x2 树干、伞状树冠） |
| WorldGenClay | 59 | WorldGenerator | 水下黏土盘（替换 dirt/clay） |
| WorldGenDeadBush | 33 | WorldGenerator | 枯灌木，4 次随机散布 |
| WorldGenDesertWells | 106 | WorldGenerator | 沙漠水井（硬编码砂岩结构 + 流动水） |
| WorldGenDoublePlant | 35 | WorldGenerator | 双格高植物（向日葵/大型蕨等），64 次散布 |
| WorldGenDungeons | 167 | WorldGenerator | 地牢：苔石房 + 刷怪笼 + 战利品箱 |
| WorldGenFire | 24 | WorldGenerator | 下界地狱岩上的火，64 次散布 |
| WorldGenFlowers | 39 | WorldGenerator | 单格花，64 次散布，可通过 `setGeneratedBlock` 换花种 |
| WorldGenForest | 136 | WorldGenAbstractTree | 白桦树（`useExtraRandomHeight` 时为超高白桦） |
| WorldGenGlowStone1 | 57 | WorldGenerator | 下界萤石簇（与 GlowStone2 逐字节相同的算法） |
| WorldGenGlowStone2 | 57 | WorldGenerator | 下界萤石簇（1500 次尝试的变体，代码与 1 相同） |
| WorldGenHellLava | 96 | WorldGenerator | 下界墙面岩浆/隐藏岩浆（`field_94524_b` 区分是否封闭） |
| WorldGenHugeTrees | 153 | abstract, extends WorldGenAbstractTree | 2x2 巨树公共基类（高度、场地检查、叶层圆盘） |
| WorldGenIcePath | 61 | WorldGenerator | 浮冰路径（packed_ice 圆盘，替换 dirt/snow/ice） |
| WorldGenIceSpike | 119 | WorldGenerator | 冰刺（冰刺之地，含 1/60 概率超高刺） |
| WorldGenLakes | 173 | WorldGenerator | 水/岩浆湖（16x8x16 布尔体素壳算法） |
| WorldGenLiquids | 88 | WorldGenerator | 主世界石壁上的水/岩浆流出点 |
| WorldGenMegaJungle | 133 | WorldGenHugeTrees | 2x2 巨型丛林树（含侧枝与藤蔓） |
| WorldGenMegaPineTree | 147 | WorldGenHugeTrees | 2x2 巨型云杉（`useBaseHeight` 区分两种树冠；灰化土处理在 `func_180711_a`） |
| WorldGenMelon | 24 | WorldGenerator | 西瓜块，64 次散布 |
| WorldGenMinable | 92 | WorldGenerator | 矿脉：沿随机线段布椭球，`predicate` 决定可替换母岩 |
| WorldGenPumpkin | 26 | WorldGenerator | 南瓜（随机朝向），64 次散布 |
| WorldGenReed | 38 | WorldGenerator | 甘蔗（要求邻水），20 次散布，高 2-4 |
| WorldGenSand | 60 | WorldGenerator | 水下沙/砂砾盘（替换 dirt/grass） |
| WorldGenSavannaTree | 219 | WorldGenAbstractTree | 金合欢树（斜干、双层伞冠、可能的第二根斜枝） |
| WorldGenShrub | 68 | WorldGenTrees | 丛林矮灌木（1 格木 + 2 层叶） |
| WorldGenSpikes | 70 | WorldGenerator | 末地黑曜石柱 + 顶部基岩 + EntityEnderCrystal |
| WorldGenSwamp | 200 | WorldGenAbstractTree | 沼泽橡树（可站水中、挂藤蔓） |
| WorldGenTaiga1 | 137 | WorldGenAbstractTree | 云杉变体 1（锥形叶冠，不用于树苗） |
| WorldGenTaiga2 | 152 | WorldGenAbstractTree | 云杉变体 2（分层叶冠，树苗生长用） |
| WorldGenTallGrass | 42 | WorldGenerator | 高草/蕨，128 次散布 |
| WorldGenTrees | 262 | WorldGenAbstractTree | 普通橡树/丛林树（可选藤蔓与可可豆） |
| WorldGenVines | 37 | WorldGenerator | 丛林墙面藤蔓（沿 y 向上扫到 128） |
| WorldGenWaterlily | 26 | WorldGenerator | 睡莲，10 次散布 |
| WorldGeneratorBonusChest | 92 | WorldGenerator | 出生点奖励箱 + 四周火把 |

## 核心类详解

### WorldGenerator（基类）

- 关键字段：`private final boolean doBlockNotify`（WorldGenerator.java:14）——决定 setBlockState 的 flags。世界初生成时为 false（flags=2，仅发客户端更新、不触发邻块通知）；树苗/骨粉运行期生长时为 true（flags=3）。
- 关键方法：
  - `public abstract boolean generate(World worldIn, Random rand, BlockPos position)`（WorldGenerator.java:26）——唯一入口，返回是否成功放置。
  - `public void func_175904_e()`（WorldGenerator.java:28）——空实现的"装饰前准备"钩子；`WorldGenBigTree` 覆写它把 `leafDistanceLimit` 设为 5（WorldGenBigTree.java:300-303）。由 `BiomeDecorator.java:217` 与 `BiomeGenForest.java:116` 在调用 `generate` 前调用。
  - `protected void setBlockAndNotifyAdequately(World worldIn, BlockPos pos, IBlockState state)`（WorldGenerator.java:32）——按 `doBlockNotify` 选 flags 3/2。
- 调用时机：区块 populate 阶段（BiomeDecorator/ChunkProvider）与运行期方块生长（BlockSapling/BlockMushroom），均在内置服务端线程。

### WorldGenAbstractTree / WorldGenHugeTrees（树类骨架）

- `protected boolean func_150523_a(Block p_150523_1_)`（WorldGenAbstractTree.java:17）——树生长时允许覆盖/穿过的方块集合：air、leaves、grass、dirt、log、log2、sapling、vine。所有树的场地检查都基于它。
- `public void func_180711_a(World worldIn, Random p_180711_2_, BlockPos p_180711_3_)`（WorldGenAbstractTree.java:23）——空实现的"生成成功后"钩子；`WorldGenMegaPineTree` 覆写它铺灰化土（WorldGenMegaPineTree.java:94-112）。由 `BiomeDecorator.java:222`、`BiomeGenForest.java:120` 在 `generate` 返回 true 后调用。
- `protected void func_175921_a(World worldIn, BlockPos pos)`（WorldGenAbstractTree.java:27）——把树根下方块强制换成 dirt。
- `WorldGenHugeTrees` 增加字段 `protected final int baseHeight`、`protected final IBlockState woodMetadata`、`protected final IBlockState leavesMetadata`、`protected int extraRandomHeight`（WorldGenHugeTrees.java:14-21），并提供 `func_150533_a(Random)`（随机高度，:32）、`func_175929_a(World, Random, BlockPos, int)`（场地+土壤检查，:102）、`func_175925_a` / `func_175928_b`（两种叶层圆盘，:107/:132）。

### WorldGenBigTree（大橡树，本包算法最重的类）

- 关键字段（WorldGenBigTree.java:18-34）：`private Random rand; private World world; private BlockPos basePos = BlockPos.ORIGIN; int heightLimit; int height; double heightAttenuation = 0.618D; double branchSlope = 0.381D; double scaleWidth = 1.0D; double leafDensity = 1.0D; int trunkSize = 1; int heightLimitLimit = 12; int leafDistanceLimit = 4; List<WorldGenBigTree.FoliageCoordinates> field_175948_j;`
- `public boolean generate(World worldIn, Random rand, BlockPos position)`（:305）——注意 `this.rand = new Random(rand.nextLong())`（:309），即内部使用派生 Random；流程为 `validTreeLocation()` → `generateLeafNodeList()` → `generateLeaves()` → `generateTrunk()` → `generateLeafNodeBases()`（:322-325）。
- `int checkBlockLine(BlockPos posOne, BlockPos posTwo)`（:272）——沿线段检查可穿透性，返回 -1 表示通畅。
- 内部类 `static class FoliageCoordinates extends BlockPos`（:362）——叶节点坐标 + 分枝基准 y（`func_177999_q()`）。
- 该类把 `world`/`rand`/`basePos` 存为实例字段，**同一实例不可并发调用 generate**。

### WorldGenMinable（矿脉）

- 字段（WorldGenMinable.java:14-18）：`private final IBlockState oreBlock; private final int numberOfBlocks; private final Predicate<IBlockState> predicate;`
- 构造器：`public WorldGenMinable(IBlockState state, int blockCount)`（:20，默认 predicate 为 `BlockHelper.forBlock(Blocks.stone)`）与 `public WorldGenMinable(IBlockState state, int blockCount, Predicate<IBlockState> p_i45631_3_)`（:25）。
- `generate`（:32）：以 position 为基准取一条随机方向线段（注意 :35-38 的 x/z 都加了 8，即以区块 populate 偏移为中心），沿线布 `numberOfBlocks` 个椭球，椭球内满足 `this.predicate.apply(worldIn.getBlockState(blockpos))` 的方块替换为 `oreBlock`（:78-81），flags 固定为 2。
- 调用方：`BiomeDecorator`（主世界各矿）、`ChunkProviderHell`（石英矿）、mesa 生物群系（金矿）等。

### WorldGenDungeons（地牢）

- 静态数据（WorldGenDungeons.java:22-23）：`private static final String[] SPAWNERTYPES = new String[] {"Skeleton", "Zombie", "Zombie", "Spider"};` 与硬编码的 `CHESTCONTENT`（`List<WeightedRandomChestContent>`，含 saddle/iron_ingot/golden_apple/唱片/马铠等）。
- `generate`（:25）：随机 5x5~7x7 房间，要求墙上有 1-5 个开口（:66），铺苔石/圆石地板墙壁，随机放至多 2 个箱子并用 `WeightedRandomChestContent.generateChestContents(rand, list, (TileEntityChest)tileentity1, 8)` 填充（:131，另混入一本随机附魔书 :126），中心放 `Blocks.mob_spawner` 并通过 `((TileEntityMobSpawner)tileentity).getSpawnerBaseLogic().setEntityName(this.pickMobSpawner(rand))` 设置实体名（:145）；取不到 TileEntity 时用 log4j 记录 error（:149）。
- 调用方：`ChunkProviderGenerate`（每区块 8 次尝试）。

### WorldGenLakes（湖）

- 字段：`private Block block`（WorldGenLakes.java:14），构造传入 water 或 lava。
- `generate`（:21）：先把 position 平移 (-8,0,-8) 并下探到地面，用 `boolean[2048]`（16x16x8 体素）叠加 4-8 个随机椭球成湖体；边界检查失败即放弃（:79-87）；下半 (<4) 填液体、上半清空（:101）；湖沿草方块化（含 mycelium 特判 :121-124）；lava 湖用 stone 包壳（:135-152）；water 湖在结冰生物群系表面结冰（:154-168）。

## 时序与生命周期

- 本包对象都是**短生命周期、无注册表**：调用方即用即 new（BlockSapling、BiomeDecorator 每次装饰都新建或复用字段中的实例），没有初始化顺序问题。少数常量（如 `WorldGenDungeons.CHESTCONTENT`、各树的 `field_18163x_x` 方块状态常量）在类加载时静态初始化，依赖 `Blocks` 已完成注册。
- 每 tick / 每帧：无。生成器只在两个时机被调用：
  1. 区块 populate 阶段（`ChunkProviderGenerate.populate` → `BiomeGenBase.decorate` → `BiomeDecorator.decorate`），此时 `doBlockNotify=false`；
  2. 运行期方块随机 tick / 骨粉（`BlockSapling.generateTree`、`BlockMushroom.generateBigMushroom`），此时 `doBlockNotify=true`。
- 线程归属：全部在**内置服务端线程**（单人模式）执行。客户端渲染/主线程从不直接调用本包。多人联机时本包代码在客户端进程内不会执行。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public abstract boolean generate(World worldIn, Random rand, BlockPos position)` | WorldGenerator.java:26 | 区块 populate、树苗/蘑菇生长、奖励箱生成 | 统一拦截/替换所有地物生成：禁用某类地物、替换方块、记录生成位置（矿物统计、地牢定位器） | 运行在服务端线程；改动会破坏世界生成的种子确定性（同种子不同世界） |
| `protected void setBlockAndNotifyAdequately(World worldIn, BlockPos pos, IBlockState state)` | WorldGenerator.java:32 | 生成器每放一个方块 | 单点观察/过滤所有经此路径的方块写入（如 X-ray 式地物高亮、生成日志） | 并非所有生成器都走它——很多类直接调 `worldIn.setBlockState(..., 2)`（如 WorldGenMinable.java:80、WorldGenLakes.java:101），拦截不全 |
| `public void func_175904_e()` | WorldGenerator.java:28 | `BiomeDecorator.java:217`、`BiomeGenForest.java:116` 在 generate 前 | 树生成前的参数调整钩子（原版仅 BigTree 用它改 `leafDistanceLimit`） | 只有装饰器路径调用；BlockSapling 路径不调用 |
| `public void func_180711_a(World worldIn, Random p_180711_2_, BlockPos p_180711_3_)` | WorldGenAbstractTree.java:23 | `BiomeDecorator.java:222`、`BiomeGenForest.java:120` 在 generate 成功后 | 树生成后的地表后处理（原版 MegaPineTree 铺灰化土）；可挂"树生成完成"事件 | 同上，仅装饰器路径 |
| `protected boolean func_150523_a(Block p_150523_1_)` | WorldGenAbstractTree.java:17 | 所有树的场地检查与放木判定 | 扩大/收紧树可覆盖的方块集合（如允许树长在自定义方块上） | 被检查与放置两处共用，放宽会让树切进建筑 |
| `((TileEntityMobSpawner)tileentity).getSpawnerBaseLogic().setEntityName(this.pickMobSpawner(rand))` | WorldGenDungeons.java:145 | 地牢生成尾声 | 改写地牢刷怪笼实体类型（配合 :22 的 SPAWNERTYPES） | 实体名是字符串（"Skeleton" 等 1.8 命名），写错静默失效 |
| `worldIn.spawnEntityInWorld(entity)`（EntityEnderCrystal） | WorldGenSpikes.java:61 | 末地黑曜石柱生成时 | 观察/替换末影水晶生成 | 本包唯一生成实体的地方 |
| `WeightedRandomChestContent.generateChestContents(rand, list, (TileEntityChest)tileentity1, 8)` | WorldGenDungeons.java:131（另 WorldGeneratorBonusChest.java:57） | 地牢/奖励箱填充战利品 | 改写战利品表（1.8 无 loot table 文件，全在代码里） | CHESTCONTENT 是 static final List，可反射改但影响全局 |

## 数据与协议

无封包、无 NBT 序列化、无文件格式。仅两处硬编码"数据表"：

- `WorldGenDungeons.SPAWNERTYPES`（WorldGenDungeons.java:22）：`{"Skeleton", "Zombie", "Zombie", "Spider"}`，Zombie 权重 2/4。
- `WorldGenDungeons.CHESTCONTENT`（:23）与 `WorldGeneratorBonusChest.chestItems`（构造传入，来自 `WorldServer.java:861` 一带）：`WeightedRandomChestContent` 列表，字段为 (item, meta, min, max, weight)。地牢表中 golden_apple 与 diamond_horse_armor 权重 1，其余多为 10。

## 不变量与陷阱

- **flags 语义**：`setBlockState` 的 flags=2 表示"发送给客户端但不触发邻块更新"，flags=3 加邻块更新。世界生成期必须用 2（否则触发连锁更新导致级联生成/卡顿）；树苗生长必须用 3（否则邻接方块状态不刷新）。`doBlockNotify` 就是这个开关，构造生成器时传错 boolean 是经典错误。
- **实例不可复用/不可并发**：`WorldGenBigTree` 把 `world`、`rand`、`basePos`、`field_175948_j` 存为实例字段（WorldGenBigTree.java:18-34），同一实例并发调用会互相污染。其它多数生成器无状态，但 `WorldGenBigMushroom.mushroomType` 在无参构造后第一次 generate 会被随机赋值并**永久保留**（WorldGenBigMushroom.java:29-32）。
- **种子确定性**：所有随机都来自调用方传入的 `Random`（populate 阶段由区块种子派生）。任何多消费/少消费一次 `rand.nextX()` 的改动都会改变同种子下的整个后续装饰序列。`WorldGenBigTree` 用 `new Random(rand.nextLong())` 隔离了自身消耗（:309），这是原版为保证消耗量恒定的手法。
- **y 范围硬编码 0..256**：几乎所有生成器写死 `position.getY() + i + 1 <= 256`、`j >= 0 && j < 256` 等（如 WorldGenForest.java:37,59）；`WorldGenVines` 写死向上扫到 y<128（WorldGenVines.java:15）。
- **GlowStone1/2 是重复代码**：两个文件逐行相同（各 57 行），改 bug 要改两处；`ChunkProviderHell` 分别持有它们。
- **WorldGenShrub 继承 WorldGenTrees 只为复用类型**：它完全覆写 `generate`，父类字段（minTreeHeight 等）用 `super(false)` 走默认构造后闲置——不要以为改 WorldGenTrees 的参数能影响灌木。
- **WorldGenSpikes 生成实体**：黑曜石柱顶会 `spawnEntityInWorld(new EntityEnderCrystal(...))`（WorldGenSpikes.java:59-61），在非末地维度手动调用会凭空出现末影水晶。
- **地牢刷怪笼容错**：拿不到 `TileEntityMobSpawner` 时仅 log error 继续（WorldGenDungeons.java:149），不会抛异常。
- LWJGL3/JDK25 移植相关：本包纯世界逻辑，无渲染/输入/Unsafe 依赖，未见移植改动痕迹；唯一外部库是 Guava（`Lists`、`Predicate`/`Predicates`）与 log4j。

## 交叉引用

- `net.minecraft.world.biome` → `BiomeDecorator#decorate`（持有并调用大多数生成器实例；`BiomeDecorator.java:217/222` 调 `func_175904_e`/`func_180711_a`）；`BiomeGenBase#genBigTreeChance`（返回 WorldGenTrees/WorldGenBigTree）；各 `BiomeGenForest/Taiga/Savanna/Swamp/...` 持有专属树生成器。
- `net.minecraft.world.gen` → `ChunkProviderGenerate#populate`（WorldGenLakes、WorldGenDungeons）、`ChunkProviderHell#populate`（WorldGenFire、WorldGenGlowStone1/2、WorldGenHellLava、WorldGenMinable）、`ChunkProviderFlat`（WorldGenLakes、WorldGenDungeons）、`GeneratorBushFeature extends WorldGenerator`。
- `net.minecraft.block` → `BlockSapling`（BlockSapling.java:75-149 按树苗类型 new 树生成器）、`BlockMushroom`（BlockMushroom.java:92/96 new WorldGenBigMushroom）；生成器反向调用 `Blocks.cactus.canBlockStay`、`Blocks.double_plant.placeAt`、`Blocks.chest.correctFacing` 等方块 API。
- `net.minecraft.world` → `World#setBlockState/getBlockState/isAirBlock/forceBlockUpdateTick/spawnEntityInWorld/canBlockFreezeWater/getLightFor`；`WorldServer#createBonusChest`（WorldServer.java:861 new WorldGeneratorBonusChest）。
- `net.minecraft.tileentity` → `TileEntityChest`、`TileEntityMobSpawner#getSpawnerBaseLogic`（WorldGenDungeons、WorldGeneratorBonusChest）。
- `net.minecraft.entity.item` → `EntityEnderCrystal`（WorldGenSpikes.java:59）。
- `net.minecraft.util` → `BlockPos`（含 MutableBlockPos、getAllInBox）、`MathHelper`、`EnumFacing`、`WeightedRandomChestContent`。
- `net.minecraft.block.state.pattern` → `BlockHelper.forBlock`（WorldGenMinable.java:22）、`BlockStateHelper.forBlock`（WorldGenDesertWells.java:17）。

## 覆盖声明

- 完整读取了 41/41 个文件（每个文件从头到尾 Read）。
- 逐行精读：WorldGenerator、WorldGenAbstractTree、WorldGenBigTree、WorldGenMinable、WorldGenDungeons、WorldGenLakes、WorldGenHugeTrees、WorldGenTrees、WorldGenSpikes、WorldGeneratorBonusChest、WorldGenMegaPineTree。
- 结构性通读（读全文但未逐行推演坐标算法）：WorldGenBigMushroom、WorldGenCanopyTree、WorldGenSavannaTree、WorldGenSwamp、WorldGenTaiga1/2、WorldGenForest、WorldGenMegaJungle、WorldGenIceSpike、WorldGenDesertWells 及其余小型散布类（Cactus/DeadBush/Fire/Flowers/GlowStone1/2/HellLava/IcePath/Liquids/Melon/Pumpkin/Reed/Sand/Shrub/TallGrass/Vines/Waterlily/Clay/BlockBlob/DoublePlant）。
- 调用方（BiomeDecorator、BlockSapling、WorldServer 等）仅做 grep 级确认，未通读。
