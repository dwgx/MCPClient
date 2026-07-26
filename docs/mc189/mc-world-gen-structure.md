---
area: net/minecraft/world/gen/structure
slug: mc-world-gen-structure
files: 19
lines: 11191
tier: C
---

# net/minecraft/world/gen/structure

## 定位

本包是 1.8.9 世界生成中的"结构系统"：矿井（Mineshaft）、村庄（Village）、下界要塞（Fortress）、要塞（Stronghold）、散布地物（沙漠/丛林金字塔、女巫小屋）、海底神殿（Monument）六类结构的**选址、布局生成、方块落地、NBT 持久化**全部在这里。

虽然这是客户端源码树，但这些代码只在**单机集成服务端**的世界生成路径上运行（多人游戏时结构由远端服务器生成，客户端只收到方块数据）。调用关系：

- 上游：`net.minecraft.world.gen.MapGenBase#generate(IChunkProvider, World, int, int, ChunkPrimer)`（MapGenBase.java:19）在区块 provider 的 `provideChunk` 阶段以半径 `range=8` 的滑动窗口调用 `recursiveGenerate`（MapGenBase.java:34）；随后 `populate` 阶段由 `ChunkProviderGenerate`（407-427 行）、`ChunkProviderHell`（392 行）、`ChunkProviderFlat`（191 行）调用 `MapGenStructure#generateStructure` 落地方块。
- 下游：大量调用 `World#setBlockState / getBlockState / spawnEntityInWorld / getTopSolidOrLiquidBlock`，`Blocks.*` 方块状态，`WeightedRandomChestContent`（战利品箱），`TileEntityMobSpawner`（刷怪笼），`WorldSavedData`（持久化到 `data/<结构名>.dat`）。
- 特殊查询入口：`getClosestStrongholdPos`（末影之眼定位、`/locate` 等价逻辑）、`isPositionInStructure` / `func_175795_b`（要塞/神殿内怪物刷新表替换）。

如果这个包消失：单机新区块不再生成任何结构；已存档结构的 NBT 无法反序列化（`MapGenStructureIO` 注册表缺失）；下界要塞内烈焰人/凋灵骷髅刷新表、海底神殿守卫者刷新表失效；末影之眼将无法指向要塞。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| MapGenStructure | 268 | extends MapGenBase | 结构生成器抽象基类：选址缓存 `structureMap`、落地入口 `generateStructure`、NBT 持久化、最近要塞查询 |
| MapGenStructureIO | 117 | (无) | 静态注册表：结构名/piece 名 ↔ Class 双向映射，NBT 反序列化工厂 |
| MapGenStructureData | 49 | extends WorldSavedData | 每种结构一个 `.dat` 文件，把每个 StructureStart 的 NBT 按 `"[x,z]"` 键存入 `Features` compound |
| StructureStart | 187 | (abstract) | 一个结构实例：piece 列表 + 总包围盒 + 分块落地循环 + NBT 读写 |
| StructureComponent | 853 | (abstract) | 单个结构 piece 基类：局部坐标系旋转、fillWithBlocks 等落块工具、箱子/发射器/门放置 |
| StructureBoundingBox | 217 | (无) | int 轴对齐包围盒：相交/包含判断、旋转投影 `getComponentToAddBoundingBox`、NBTTagIntArray 序列化 |
| MapGenMineshaft | 40 | extends MapGenStructure | 矿井选址（概率 `field_82673_e=0.004` 且离原点越远越易出） |
| MapGenNetherBridge | 74 | extends MapGenStructure | 下界要塞选址 + 内部 `Start` + 专属怪物刷新表（Blaze/PigZombie/Skeleton/MagmaCube） |
| MapGenScatteredFeature | 154 | extends MapGenStructure | 散布地物选址（网格 32/8、种子盐 14357617、生物群系过滤）+ 女巫小屋判定 `func_175798_a` |
| MapGenStronghold | 167 | extends MapGenStructure | 要塞选址（环形分布 3 座，`findBiomePosition` 修正）+ `getCoordList` 供末影之眼回退 |
| MapGenVillage | 156 | extends MapGenStructure | 村庄选址（网格 32/8、种子盐 10387312）+ `Start.isSizeableStructure`（>2 个非道路 piece 才有效） |
| StructureOceanMonument | 197 | extends MapGenStructure | 海底神殿选址（网格 32/5、种子盐 10387313、deepOcean 校验）+ `StartMonument`（记录已处理区块，可重建 piece） |
| StructureMineshaftStart | 21 | extends StructureStart | 矿井 Start：以 `StructureMineshaftPieces.Room` 为根递归展开，`markAvailableHeight(…, 10)` |
| ComponentScatteredFeaturePieces | 700 | (容器类) | DesertPyramid / JunglePyramid / SwampHut 三个 piece + 公共基类 `Feature`（地表高度自适应） |
| StructureMineshaftPieces | 834 | (容器类) | 矿井 piece：Corridor / Cross / Room / Stairs，权重 70/20/10（Corridor/Cross/Stairs，`nextInt(100)` 分段）递归拼接，深度上限 8 |
| StructureNetherBridgePieces | 1398 | (容器类) | 下界要塞 15 种 piece + `PieceWeight` 权重选择器 + `Start`（继承 Crossing3） |
| StructureStrongholdPieces | 1638 | (容器类) | 要塞 13 种 piece + 静态权重表/`strongComponentType` 状态机 + `Stronghold.Door` 门样式 |
| StructureVillagePieces | 1883 | (容器类) | 村庄 13 种 piece + `Village` 基类（地表高度、沙漠材质替换、村民生成）+ `Start`(Well 派生) |
| StructureOceanMonumentPieces | 2238 | (容器类) | 海底神殿：`RoomDefinition` 5×5×3 房间图 + FitHelper 房型匹配 + MonumentBuilding 总控 piece |

## 核心类详解

### MapGenStructure（MapGenStructure.java）

关键字段：
- `private MapGenStructureData structureData;`（22 行）— 惰性加载的持久化载体。
- `protected Map<Long, StructureStart> structureMap = Maps.newHashMap();`（23 行）— key 为 `ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ)`，是"该 chunk 是否为某结构起点"的内存缓存。

关键方法（逐字签名）：
- `protected final void recursiveGenerate(World worldIn, final int chunkX, final int chunkZ, int p_180701_4_, int p_180701_5_, ChunkPrimer chunkPrimerIn)`（30 行）— 由 `MapGenBase.generate` 对当前 chunk 周围 17×17 窗口逐格调用；先 `initializeStructureData`（32 行），若该 chunk 未缓存且 `canSpawnStructureAtCoords` 通过则 `getStructureStart` 并写入 `structureMap` + 存档（42-44 行）。注意 36 行的 `this.rand.nextInt()` 用于消耗随机数流，不能删。
- `public boolean generateStructure(World worldIn, Random randomIn, ChunkCoordIntPair chunkCoord)`（78 行）— populate 阶段调用；遍历所有已知 Start，对与当前 chunk 中心 16×16 区域相交者调用 `structurestart.generateStructure(...)`（89 行）并回写 NBT（92 行）。
- `public BlockPos getClosestStrongholdPos(World worldIn, BlockPos pos)`（152 行）— 强制在玩家所在 chunk 触发一次 `recursiveGenerate`（162 行），再在 `structureMap` 中找 `components.get(0)` 中心最近者；找不到时回退 `getCoordList()`（188 行，默认 null，MapGenStronghold 覆写于 114 行）。
- `private void initializeStructureData(World worldIn)`（219 行）— `worldIn.loadItemData(MapGenStructureData.class, this.getStructureName())`，遍历 `Features` 下每个含 `ChunkX`/`ChunkZ` 的 compound，经 `MapGenStructureIO.getStructureStart` 还原（246 行）。
- 子类契约：`protected abstract boolean canSpawnStructureAtCoords(int chunkX, int chunkZ);`（265 行）、`protected abstract StructureStart getStructureStart(int chunkX, int chunkZ);`（267 行）、`public abstract String getStructureName();`（25 行）。

### StructureStart（StructureStart.java）

关键字段：`protected LinkedList<StructureComponent> components`（13 行）、`protected StructureBoundingBox boundingBox`（14 行）、`private int chunkPosX; private int chunkPosZ;`（15-16 行）。

- `public void generateStructure(World worldIn, Random rand, StructureBoundingBox structurebb)`（41 行）— 遍历 piece，与本 chunk 相交且 `addComponentParts` 返回 false 的 piece 直接 `iterator.remove()`（49-52 行，例如矿井 piece 碰到液体放弃）。
- `public NBTTagCompound writeStructureComponentsToNBT(int chunkX, int chunkZ)`（69 行）/ `public void readStructureComponentsFromNBT(World worldIn, NBTTagCompound tagCompound)`（92 行）— 见"数据与协议"。
- `protected void markAvailableHeight(World worldIn, Random rand, int p_75067_3_)`（119 行）与 `protected void setRandomHeight(World worldIn, Random rand, int p_75070_3_, int p_75070_4_)`（138 行）— 整体竖直平移所有 piece（调用 `structurecomponent.func_181138_a(0, k, 0)`）。
- `public boolean isSizeableStructure()`（164 行，默认 true）— `MapGenVillage.Start` 覆写为 `hasMoreThanTwoComponents`（MapGenVillage.java:139）。
- `public boolean func_175788_a(ChunkCoordIntPair pair)`（169 行）/ `public void func_175787_b(ChunkCoordIntPair pair)`（174 行）— "该 chunk 是否已处理"钩子，仅海底神殿 `StartMonument` 实现（StructureOceanMonument.java:154-163）。

### StructureComponent（StructureComponent.java）

关键字段：`protected StructureBoundingBox boundingBox;`（23 行）、`protected EnumFacing coordBaseMode;`（26 行，局部坐标系朝向）、`protected int componentType;`（29 行，递归深度）。

- `public abstract boolean addComponentParts(World worldIn, Random randomIn, StructureBoundingBox structureBoundingBoxIn);`（96 行）— 真正写方块的方法；只允许写入传入的 chunk 裁剪盒内。
- `public void buildComponent(StructureComponent componentIn, List<StructureComponent> listIn, Random rand)`（88 行）— 布局阶段递归展开子 piece（不触碰世界）。
- 坐标旋转：`protected int getXWithOffset(int x, int z)`（196 行）、`protected int getYWithOffset(int y)`（222 行）、`protected int getZWithOffset(int x, int z)`（227 行）；`protected int getMetadataWithOffset(Block blockIn, int meta)`（256 行）对 rail/door/stairs/ladder/button/piston 等做朝向元数据换算。
- 落块工具：`protected void setBlockState(World worldIn, IBlockState blockstateIn, int x, int y, int z, StructureBoundingBox boundingboxIn)`（591 行，flag=2，只有在裁剪盒内才写）、`protected void fillWithBlocks(...)`（631 行）、`protected void fillWithRandomizedBlocks(...)`（659 行，配 `StructureComponent.BlockSelector`，842 行）、`protected void replaceAirAndLiquidDownwards(...)`（763 行，向下打地基）、`protected void clearCurrentPositionBlocksUpwards(...)`（746 行）。
- 战利品：`protected boolean generateChestContents(World worldIn, StructureBoundingBox boundingBoxIn, Random rand, int x, int y, int z, List<WeightedRandomChestContent> listIn, int max)`（779 行）、`generateDispenserContents`（802 行）；`placeDoorCurrentPosition`（827 行）经 `ItemDoor.placeDoor` 放门。
- `public static StructureComponent findIntersecting(List<StructureComponent> listIn, StructureBoundingBox boundingboxIn)`（114 行）— 布局阶段防重叠核心。

### MapGenStructureIO（MapGenStructureIO.java）

四个静态 Map（13-16 行）构成双向注册表。`static` 初始化块（102-116 行）注册六个 Start：`"Mineshaft"`、`"Village"`、`"Fortress"`、`"Stronghold"`、`"Temple"`、`"Monument"`，并触发各 Pieces 类的 `registerStructurePieces()` 等静态注册。反序列化：`public static StructureStart getStructureStart(NBTTagCompound tagCompound, World worldIn)`（40 行）与 `public static StructureComponent getStructureComponent(NBTTagCompound tagCompound, World worldIn)`（71 行），均按 NBT `"id"` 查类并 `oclass.newInstance()`（50/81 行，依赖每个 piece 的 public 无参构造器），失败仅 `logger.warn` 后跳过。

### StructureOceanMonumentPieces.MonumentBuilding（StructureOceanMonumentPieces.java）

与其它结构"buildComponent 递归"不同，神殿是**单一 piece 内部自带子 piece 列表**：`private List<StructureOceanMonumentPieces.Piece> field_175843_q`（683 行）。构造器（689 行）先 `func_175836_a(Random)`（763 行）生成 5×5×3 的 `RoomDefinition[75]` 房间连通图（索引函数 `func_175820_a = y*25 + z*5 + x`，1508 行），随机封闭部分连通口后，用 7 个 `MonumentRoomFitHelper`（XY/YZ/Z/X/Y 双房 + SimpleTop + Simple，710-716 行）贪心匹配房型；`addComponentParts`（906 行）先整体灌水/清空（`func_181655_a`，1576 行：海平面以上放 air、以下放 water），再画外墙/翼房/核心（`func_175840_a` 等），最后遍历 `field_175843_q` 中与当前 chunk 相交的子 piece 落地（961-967 行）。材质常量：`field_175828_a`(ROUGH prismarine)、`field_175826_b`(BRICKS)、`field_175827_c`(DARK)、`field_175825_e`(sea_lantern)（1496-1500 行）。`func_175817_a`（1645 行）生成 `EntityGuardian` 并 `setElder(true)` —— 远古守卫者共 3 只（Penthouse:1489 行 + 两个 WingRoom:2066/2110 行）。

### StructureStrongholdPieces（StructureStrongholdPieces.java）

静态权重表 `pieceWeightArray`（23 行），其中 `Library` 与 `PortalRoom` 用匿名子类覆写 `public boolean canSpawnMoreStructuresOfType(int p_75189_1_)` 限制递归深度（>4 / >5）。**静态可变状态**：`private static List<StructureStrongholdPieces.PieceWeight> structurePieceList;`（37 行）、`private static Class<? extends StructureStrongholdPieces.Stronghold> strongComponentType;`（38 行）、`static int totalWeight;`（39 行），每次生成前必须调用 `public static void prepareStructurePieces()`（62 行，`MapGenStronghold.Start` 构造器 150 行调用）重置。`MapGenStronghold.getStructureStart`（129-139 行）用 for 循环**反复重建整个 Start** 直到 `strongholdPortalRoom != null`。`Stronghold.placeDoor`（1486 行）按 `Door` 枚举（OPENING/WOOD_DOOR/GRATES/IRON_DOOR，1630 行）放门；`PortalRoom.addComponentParts`（821 行）在 889-900 行放 12 块 `Blocks.end_portal_frame`，每块 `BlockEndPortalFrame.EYE` 以 `randomIn.nextFloat() > 0.9F` 概率带眼，并在 902-918 行放 Silverfish 刷怪笼。

## 时序与生命周期

1. **注册（类加载时一次）**：首次触碰 `MapGenStructureIO`（例如世界加载/反序列化第一个结构）执行 static 块，注册全部 Start 与 piece 的名字映射。
2. **选址（chunk decorate 前）**：`ChunkProviderGenerate.provideChunk` → 各 `MapGenStructure.generate(...)`（继承自 MapGenBase）→ 对周围 17×17 chunk 逐个 `recursiveGenerate` → 命中时构造 `StructureStart`（此时完成**整个结构的布局**：buildComponent 递归、updateBoundingBox、高度调整），写入 `structureMap` 并 `markDirty()` 存档。
3. **落地（chunk populate 阶段）**：`ChunkProviderGenerate.populate`（407-427 行）/ `ChunkProviderHell.populate`（392 行）/ `ChunkProviderFlat.populate`（191 行）→ `generateStructure(world, rand, chunkPair)` → 每个相交 piece 的 `addComponentParts`，只写当前 chunk 的 16×16 裁剪盒。同一结构随周边 chunk 逐步 populate 而分片落地。
4. **持久化**：每次新增 Start 或 populate 改动状态（如箱子已放置标志）后 `setStructureStart` → `MapGenStructureData.writeInstance` + `markDirty()`，由 `WorldSavedData` 机制随存档落盘为 `data/Mineshaft.dat` 等。
5. **线程归属**：全部在**集成服务端线程**（"Server thread"）的 chunk 生成/填充路径同步执行。不在客户端渲染线程，也不在 Netty EventLoop。无每 tick / 每帧行为；`getClosestStrongholdPos` 在处理末影之眼投掷时按需调用。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `protected final void recursiveGenerate(World worldIn, final int chunkX, final int chunkZ, int p_180701_4_, int p_180701_5_, ChunkPrimer chunkPrimerIn)` | MapGenStructure.java:30 | 每个新 chunk 生成时对周围 17×17 窗口逐格调用 | 观察/拦截结构选址；实现"结构雷达"（读 `structureMap`）；禁用某类结构 | 方法为 final，需在 `canSpawnStructureAtCoords` 层拦截；36 行 `rand.nextInt()` 参与种子流，跳过会改变世界生成 |
| `public boolean generateStructure(World worldIn, Random randomIn, ChunkCoordIntPair chunkCoord)` | MapGenStructure.java:78 | populate 阶段每 chunk 一次 | 结构落地前后事件；统计/取消落地 | 返回值在 ChunkProviderGenerate:412 赋给 `flag`，用于抑制该 chunk 后续的水/岩浆湖生成（430/438 行） |
| `protected abstract boolean canSpawnStructureAtCoords(int chunkX, int chunkZ)` | MapGenStructure.java:265 | recursiveGenerate 内 | 覆写/包装即可增删结构位置（自定义频率、白名单区域） | 各子类内部会 `setSeed` 或消耗 `this.rand`，改动会破坏与原版一致的种子决定性 |
| `public BlockPos getClosestStrongholdPos(World worldIn, BlockPos pos)` | MapGenStructure.java:152 | 末影之眼 / `World.getStrongholdPos`（World.java:3686）→ `getStrongholdGen` 路径（ChunkProviderGenerate.java:562） | 实现要塞坐标显示、伪造定位结果 | 内部会触发一次 `recursiveGenerate`，副作用是可能新建 Start 并写存档 |
| `public void generateStructure(World worldIn, Random rand, StructureBoundingBox structurebb)` | StructureStart.java:41 | 每个相交 chunk populate 时 | 逐 piece 观察；piece 失败剔除（49-52 行）是结构"缺一角"的根因，可在此记录 | 迭代中 `iterator.remove()`，包装时不要缓存列表快照 |
| `public abstract boolean addComponentParts(World worldIn, Random randomIn, StructureBoundingBox structureBoundingBoxIn)` | StructureComponent.java:96 | 单个 piece 落地 | 单 piece 粒度的替换/装饰（换方块材质、注入战利品） | 必须尊重传入裁剪盒，越界写方块会污染未生成 chunk 并触发级联生成 |
| `protected void setBlockState(World worldIn, IBlockState blockstateIn, int x, int y, int z, StructureBoundingBox boundingboxIn)` | StructureComponent.java:591 | 所有结构落块的最终汇聚点 | 全局结构方块过滤/替换（一处改所有结构） | 村庄的 `Village.setBlockState`（StructureVillagePieces.java:1668）在其上又包了沙漠材质替换 `func_175847_a`（1630 行） |
| `static void registerStructureComponent(Class <? extends StructureComponent > componentClass, String componentName)` / `private static void registerStructure(Class <? extends StructureStart > startClass, String structureName)` | MapGenStructureIO.java:24 / 18 | 类加载 static 块 | 注册自定义结构/piece 使其可持久化 | name→class 冲突会静默覆盖；piece 必须有 public 无参构造器（`newInstance()`，71-100 行） |
| `protected boolean generateChestContents(World worldIn, StructureBoundingBox boundingBoxIn, Random rand, int x, int y, int z, List<WeightedRandomChestContent> listIn, int max)` | StructureComponent.java:779 | 各 piece 放战利品箱时 | 修改/记录战利品；箱子坐标采集 | 矿井 Corridor 覆写了同名方法改为生成 `EntityMinecartChest`（StructureMineshaftPieces.java:289） |
| `protected void spawnVillagers(World worldIn, StructureBoundingBox p_74893_2_, int p_74893_3_, int p_74893_4_, int p_74893_5_, int p_74893_6_)` | StructureVillagePieces.java:1600 | 村庄建筑 piece 落地末尾 | 拦截/统计初始村民；改职业（`func_180779_c`，1625 行） | `villagersSpawned` 持久化在 piece NBT（`VCount`），重复触发有防重 |
| `public List<BiomeGenBase.SpawnListEntry> getSpawnList()` | MapGenNetherBridge.java:30 | ChunkProviderHell.getPossibleCreatures（487-494 行）每次怪物刷新查询 | 修改下界要塞刷怪表 | 返回的是内部 list 引用，可直接改 |
| `public boolean isPositionInStructure(World worldIn, BlockPos pos)` / `public boolean func_175795_b(BlockPos pos)` | MapGenStructure.java:137 / 99 | 神殿守卫者、要塞怪物刷新判定（ChunkProviderGenerate.java:551、ChunkProviderHell.java:487） | 实现"是否在结构内"的通用查询（ESP、区域提示） | `func_175795_b` 要求点同时在某 piece 盒内（105-135 行），比 `isPositionInStructure` 严格 |

## 数据与协议

不涉及网络封包。持久化格式：每种结构一个 `WorldSavedData` 文件（`data/<getStructureName()>.dat`，如 `Village.dat`）。

**MapGenStructureData**（MapGenStructureData.java）根结构：

| 字段 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|
| `Features` | NBTTagCompound | `readFromNBT`(18 行) / `writeToNBT`(26 行) | 所有 Start 的容器 |
| `Features.["[x,z]"]` | NBTTagCompound | `writeInstance`(35 行)，键由 `formatChunkCoords`(40 行) 生成 | 单个 StructureStart |

**StructureStart 级**（`writeStructureComponentsToNBT` StructureStart.java:69 / `readStructureComponentsFromNBT` 92 行）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | String | 注册名（`MapGenStructureIO.getStructureStartName`），如 `"Fortress"` |
| `ChunkX` / `ChunkZ` | int | 起点 chunk 坐标 |
| `BB` | int[6] | 总包围盒（`StructureBoundingBox.toNBTTagIntArray`，StructureBoundingBox.java:213） |
| `Children` | NBTTagList(compound) | 各 piece 的 `createStructureBaseNBT` |
| `Valid` | boolean | 仅 Village.Start：`hasMoreThanTwoComponents`（MapGenVillage.java:144-154） |
| `Processed` | NBTTagList({X:int, Z:int}) | 仅 Monument.StartMonument：已 populate 过的 chunk 集合（StructureOceanMonument.java:165-195） |

**StructureComponent 级公共字段**（`createStructureBaseNBT` StructureComponent.java:46 / `readStructureBaseNBT` 67 行）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | String | piece 注册名（如 `"NeBCr"`、`"SHPR"`、`"ViBH"`、`"OMB"`、`"MSCorridor"`、`"TeDP"`） |
| `BB` | int[6] | piece 包围盒 |
| `O` | int | `coordBaseMode` 的 horizontalIndex，-1 表示 null |
| `GD` | int | `componentType`（递归深度） |

子类补充字段（举例，均逐字来自各 `writeStructureToNBT`）：Mineshaft Corridor `hr/sc/hps/Num`（StructureMineshaftPieces.java:105-111）、Room `Entrances`(int[] 列表，715-725 行)；Stronghold 公共 `EntryDoor`（StructureStrongholdPieces.java:1476-1479）、ChestCorridor `Chest`、Library `Tall`、PortalRoom `Mob`、Crossing `leftLow/leftHigh/rightLow/rightHigh`、Stairs `Source`、Straight `Left/Right`、Corridor `Steps`、RoomCrossing `Type`；NetherBridge Corridor/Corridor2 `Chest`、End `Seed`、Throne `Mob`；Village 公共 `HPos/VCount/Desert`（StructureVillagePieces.java:1505-1510）、Field1 `CA/CB/CC/CD`(方块注册 ID)、House2 `Chest`、WoodHut `T/C`、House4Garden `Terrace`、Path `Length`；ScatteredFeature 公共 `Width/Height/Depth/HPos`（ComponentScatteredFeaturePieces.java:300-306）、DesertPyramid `hasPlacedChest0..3`、JunglePyramid `placedMainChest/placedHiddenChest/placedTrap1/placedTrap2`、SwampHut `Witch`。

## 不变量与陷阱

- **种子决定性**：`canSpawnStructureAtCoords` 各实现依赖精确的随机数消耗次序（如 MapGenStructure.java:36 的 `rand.nextInt()`、MapGenNetherBridge.java:39-40 的 `setSeed` + `nextInt()`）。任何插入的额外随机调用都会改变世界。
- **静态可变状态，非线程安全**：`StructureStrongholdPieces.structurePieceList / strongComponentType / totalWeight`（37-40 行）与 `StructureNetherBridgePieces.primaryComponents/secondaryComponents` 内的 `PieceWeight.field_78827_c` 计数（1132 行，Start 构造器 1234-1246 行重置）都是 static 且在生成过程中被写。两个世界并行生成同类结构会互相踩；一切结构生成必须留在单一服务端线程。
- **piece 反序列化契约**：每个 piece 类必须保留 public 无参构造器且在对应 `registerXxxPieces()` 中注册，否则 `MapGenStructureIO` 只会 warn 并丢弃该 piece（41-69/71-100 行），结构静默残缺。
- **裁剪盒纪律**：`addComponentParts` 只能通过 `setBlockState(…, boundingboxIn)` 系列写方块；直接 `world.setBlockState` 越过裁剪会触碰未生成 chunk，引发级联生成甚至死递归。原版也有越界写：`clearCurrentPositionBlocksUpwards`/`replaceAirAndLiquidDownwards` 的 y 方向不受盒限制（这是有意的）。
- **piece 剔除语义**：`addComponentParts` 返回 false ⇒ 该 piece 被永久从 Start 移除（StructureStart.java:49-52），例如矿井/要塞 piece 的 `isLiquidInStructureBoundingBox` 检查。若在包装中吞掉返回值，会导致 piece 每个 chunk 重复重试。
- **`StructureBoundingBox(int[] coords)`**（33 行）在数组长度 ≠6 时静默保留全 0 盒，坏 NBT 不会报错，只会得到 (0,0,0)-(0,0,0) 的 piece。
- **Stronghold Start 重试循环**（MapGenStronghold.java:133）没有次数上限：若权重表被改到无法生成 PortalRoom，会死循环。
- **Monument 的懒重建**：`StartMonument.generateStructure`（StructureOceanMonument.java:143-152）在 `field_175790_d == false`（刚从 NBT 读回）时会 clear 并用世界种子重建全部 piece —— 神殿 piece 本身不持久化任何布局字段，改种子会让半生成的神殿错位。
- **LWJGL3/JDK25 移植注意**：本包为纯逻辑代码，无 GL/输入依赖；与原版差异极小（`StructureBoundingBox.toString` 用 `com.google.common.base.MoreObjects` 而非老版 `Objects.toStringHelper`，StructureBoundingBox.java:3,210）。JDK 25 下 `Class.newInstance()`（MapGenStructureIO.java:50,81）已弃用但仍可用，注意它会直接抛受检异常穿透（此处被 catch(Exception) 吞掉）。`@SuppressWarnings("incomplete-switch")`（StructureMineshaftPieces.java:22 等）表明 switch(EnumFacing) 故意不覆盖 UP/DOWN。
- **海底神殿房间索引**：`func_175820_a(x, y, z) = y * 25 + z * 5 + x`（StructureOceanMonumentPieces.java:1508-1511），`RoomDefinition.func_175961_b()` 用 `field_175967_a >= 75` 标记三个"虚拟房间"（1001/1002/1003），遍历时必须跳过。

## 交叉引用

- `net.minecraft.world.gen` → `MapGenBase#generate`（本包所有生成器的驱动入口）
- `net.minecraft.world.gen` → `ChunkProviderGenerate#populate` / `#getPossibleCreatures` / `#getStrongholdGen`（ChunkProviderGenerate.java:407-427, 548-553, 562）
- `net.minecraft.world.gen` → `ChunkProviderHell#populate` / `#getPossibleCreatures`（392, 487-494 行，调用 `MapGenNetherBridge#getSpawnList`）
- `net.minecraft.world.gen` → `ChunkProviderFlat`（构造器 58-78 行按 flat 设置实例化各 MapGen；populate 191 行）
- `net.minecraft.world` → `World#loadItemData` / `World#setItemData` / `World#setRandomSeed`（持久化与网格选址种子）
- `net.minecraft.world` → `WorldSavedData`（MapGenStructureData 的父类，脏标记落盘）
- `net.minecraft.world.biome` → `WorldChunkManager#areBiomesViable` / `#getBiomeGenerator` / `#findBiomePosition`（村庄/神殿/要塞选址的生物群系判定）
- `net.minecraft.util` → `WeightedRandomChestContent#generateChestContents` / `#generateDispenserContents` / `#func_177629_a`（战利品）
- `net.minecraft.tileentity` → `TileEntityMobSpawner#getSpawnerBaseLogic().setEntityName(...)`（CaveSpider/Blaze/Silverfish 刷怪笼）
- `net.minecraft.entity` → `EntityWitch` / `EntityVillager` / `EntityGuardian` / `EntityMinecartChest`（结构落地时直接 spawn）
- `net.minecraft.item` → `ItemDoor#placeDoor`（StructureComponent#placeDoorCurrentPosition）
- `net.minecraft.nbt` → `NBTTagCompound` / `NBTTagList` / `NBTTagIntArray`（全部持久化）
- `net.minecraft.init` → `Blocks`（所有 piece 的建材来源）

## 覆盖声明

完整读取了 19/19 个文件（六个超长文件 StructureVillagePieces、StructureOceanMonumentPieces、StructureStrongholdPieces、StructureNetherBridgePieces、ComponentScatteredFeaturePieces、StructureMineshaftPieces 通过分页 Read 读完全文）。

逐行精读：MapGenStructure、MapGenStructureIO、MapGenStructureData、StructureStart、StructureComponent、StructureBoundingBox、MapGenMineshaft、MapGenNetherBridge、MapGenScatteredFeature、MapGenStronghold、MapGenVillage、StructureOceanMonument、StructureMineshaftStart，以及各 Pieces 文件的框架部分（注册方法、PieceWeight/权重选择、Start 类、基类 Piece/Village/Stronghold/Feature、NBT 读写、getNextComponent* 族）。

只做结构性浏览（读过但未逐坐标核对）：各 piece 的 `addComponentParts` 内部大段 `fillWithBlocks`/`setBlockState` 摆块坐标序列（如 DesertPyramid、Crossing3、Library、House3、MonumentBuilding 的装饰细节）——这些是几何数据，不影响架构结论。

另外用 Grep 核实了包外调用方（MapGenBase、ChunkProviderGenerate、ChunkProviderHell、ChunkProviderFlat）的行号。
