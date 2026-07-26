---
area: net/minecraft/block#3
slug: mc-block-3
files: 30
lines: 2905
tier: C
---

# net/minecraft/block#3 — 方块尾部类 + material / properties / state 子包

## 定位

本桶是 `net.minecraft.block` 包的第 3 段，包含两部分：

1. 字母序末尾的几个具体方块类（`BlockVine` / `BlockWall` / `BlockWallSign` / `BlockWeb` / `BlockWoodSlab` / `BlockWorkbench` / `BlockYellowFlower`）和两个能力接口（`IGrowable` / `ITileEntityProvider`）。
2. 方块系统的三个基础子包，整个方块体系都建立在它们之上：
   - `block.material` — `Material` / `MapColor`：方块的物理属性（可燃、可推、透光、流体）与地图颜色。每个 `Block` 构造时必须传入一个 `Material`。
   - `block.properties` — `IProperty` 及其实现：blockstate 属性系统（bool / int / enum / direction）。
   - `block.state` — `BlockState` / `IBlockState` / `BlockStateBase`：**不可变 blockstate 实现，是整个世界数据模型的核心**。`World.getBlockState` / `setBlockState`、渲染、网络同步全部以 `IBlockState` 为货币。附带 `BlockPistonStructureHelper`（活塞推动集合计算）和 `state.pattern`（多方块结构匹配：凋灵、铁傀儡、雪傀儡、末地门户）。

如果 `block.state` 子包消失，`Block` 构造函数（`Block.java:298` 调用 `this.createBlockState()`）直接崩溃，客户端连方块注册都完不成；`Material` 消失则所有方块构造、光照、活塞、流体判断全坏；`pattern` 子包消失则傀儡召唤、地狱门/末地门检测失效。

调用方：几乎所有包。`World`、`Chunk`、`BlockRendererDispatcher`、`ItemBlock`、`ItemMap`（地图颜色采样）、以及 `net.minecraft.block` 包内全部方块类。本桶自身向外调用 `net.minecraft.world`（`World` / `IBlockAccess`）、`net.minecraft.util`（`BlockPos` / `EnumFacing`）、`net.minecraft.init`（`Blocks` / `Items`）等。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| BlockVine | 513 | extends Block | 藤蔓：5 个 PropertyBool 面属性、随机 tick 蔓延、剪刀采集掉落 |
| BlockWall | 237 | extends Block | 圆石/苔石墙：连接判定 + 碰撞箱 1.5 格高，含 EnumType 内部枚举 |
| BlockWallSign | 94 | extends BlockSign | 墙上告示牌：FACING 属性，依附方块消失时自毁掉落 |
| BlockWeb | 66 | extends Block | 蜘蛛网：碰撞时 entityIn.setInWeb()，掉落线 |
| BlockWoodSlab | 133 | abstract, extends BlockSlab | 木台阶：VARIANT(BlockPlanks.EnumType) + HALF 的 meta 编解码 |
| BlockWorkbench | 77 | extends Block | 工作台：右键经 displayGui 打开合成界面，含 InterfaceCraftingTable 内部类 |
| BlockYellowFlower | 12 | extends BlockFlower | 黄花：仅覆写 getBlockType() 返回 EnumFlowerColor.YELLOW |
| IGrowable | 18 | interface | 骨粉催熟能力接口：canGrow / canUseBonemeal / grow |
| ITileEntityProvider | 12 | interface | 有 TileEntity 的方块标记接口：createNewTileEntity(World, int) |
| MapColor | 95 | (无) | 地图调色板，36 个静态实例注册进 mapColorArray[64] |
| Material | 226 | (无) | 方块材质：可燃/可替换/透明/工具需求/推动性 + 全部静态实例 |
| MaterialLiquid | 35 | extends Material | 流体材质：isLiquid=true, isSolid=false, blocksMovement=false |
| MaterialLogic | 34 | extends Material | 电路/植物类材质：非固体、不挡光、不挡移动、冒险模式豁免 |
| MaterialPortal | 33 | extends Material | 传送门材质：非固体、不挡光、不挡移动 |
| MaterialTransparent | 34 | extends Material | 空气/火材质：可替换、非固体、不挡光、不挡移动 |
| IProperty | 17 | interface\<T extends Comparable\<T\>\> | blockstate 属性契约：getName / getAllowedValues / getValueClass |
| PropertyBool | 32 | extends PropertyHelper\<Boolean\> | 布尔属性，允许值固定 {true,false} |
| PropertyDirection | 40 | extends PropertyEnum\<EnumFacing\> | 朝向属性，可用 Predicate 过滤方向集合 |
| PropertyEnum | 68 | extends PropertyHelper\<T\>, T: Enum & IStringSerializable | 枚举属性，维护 name→value 映射，重名抛异常 |
| PropertyHelper | 52 | abstract, implements IProperty\<T\> | 属性基类：name + valueClass，equals/hashCode 按二者 |
| PropertyInteger | 85 | extends PropertyHelper\<Integer\> | 整数区间属性 [min,max]，min<0 或 max<=min 抛异常 |
| BlockPistonStructureHelper | 224 | (无) | 活塞推动计算：toMove/toDestroy 列表，含粘液块递归、12 块上限 |
| BlockState | 203 | (无) | 每个 Block 一个：笛卡尔积生成全部 StateImplementation 并互相连边 |
| BlockStateBase | 70 | abstract, implements IBlockState | cycleProperty 与 toString 的公共实现 |
| BlockWorldState | 60 | (无) | 惰性缓存某 (World,BlockPos) 的 state 与 TileEntity，供 pattern 匹配用 |
| IBlockState | 21 | interface | 不可变方块状态契约：getValue / withProperty / cycleProperty / getBlock |
| BlockHelper | 25 | implements Predicate\<IBlockState\> | 谓词：state 的 Block 是否等于给定 Block |
| BlockPattern | 205 | (无) | 3D 结构匹配器（finger/thumb/palm 轴系），含 PatternHelper 与 CacheLoader |
| BlockStateHelper | 61 | implements Predicate\<IBlockState\> | 谓词：Block 相同且各 property 满足子谓词 |
| FactoryBlockPattern | 123 | (无) | BlockPattern 的字符画 builder：aisle() + where() + build() |

## 核心类详解

### BlockState / BlockState.StateImplementation（`state/BlockState.java`）

blockstate 系统的心脏。构造函数（`BlockState.java:39`）：

```java
public BlockState(Block blockIn, IProperty... properties)
```

- 先按 `IProperty.getName()` 字母序排序 properties（`BlockState.java:42-48`）——这决定 meta 无关的状态枚举顺序。
- 用 `Cartesian.cartesianProduct(this.getAllowedValues())` 枚举全部属性组合（`BlockState.java:53`），每个组合建一个 `StateImplementation`，随后逐个调用 `buildPropertyValueTable(map)`（`BlockState.java:63`）建立"改一个属性跳到哪个实例"的 `ImmutableTable<IProperty, Comparable, IBlockState> propertyValueTable`（`BlockState.java:110`）。
- 关键字段：`private final Block block`、`private final ImmutableList<IProperty> properties`、`private final ImmutableList<IBlockState> validStates`（`BlockState.java:35-37`）。

`StateImplementation` 的两个热路径方法（每帧渲染/每 tick 世界逻辑都在调）：

```java
public <T extends Comparable<T>> T getValue(IProperty<T> property)          // BlockState.java:123
public <T extends Comparable<T>, V extends T> IBlockState withProperty(IProperty<T> property, V value)  // BlockState.java:135
```

`withProperty` 不 new 对象，只查 `propertyValueTable`（`BlockState.java:147`），所以 **同一 Block 的相同状态在整个 JVM 里是同一个实例**，`StateImplementation.equals` 直接 `this == p_equals_1_`（`BlockState.java:161-164`）。调用时机：`Block` 构造函数在 `Block.java:298` 调 `this.createBlockState()`，随后 `getBaseState()`（`BlockState.java:86`）作为 defaultState；`Block.registerBlocks` 在 `Block.java:1493` 把所有 validStates 塞进 `BLOCK_STATE_IDS`。

### Material（`material/Material.java`）

每个 Block 的物理属性单例。所有实例是类顶部的静态常量（`Material.java:5-49`），如 `public static final Material web`（`Material.java:39`）用匿名子类覆写 `blocksMovement()` 返回 false。私有字段：`boolean canBurn`、`boolean replaceable`、`boolean isTranslucent`、`final MapColor materialMapColor`、`boolean requiresNoTool = true`、`int mobilityFlag`、`boolean isAdventureModeExempt`（`Material.java:52-76`）。

关键查询方法（被 `Block`、光照引擎、活塞、实体碰撞广泛调用）：

```java
public boolean isLiquid()            // Material.java:86，流体判定
public boolean isSolid()             // Material.java:94，告示牌依附、寻路
public boolean blocksLight()         // Material.java:102，光照透过
public boolean blocksMovement()      // Material.java:110，碰撞
public boolean isOpaque()            // Material.java:170：isTranslucent ? false : blocksMovement()
public int getMaterialMobility()     // Material.java:187：0 自由 / 1 不可推但可覆盖 / 2 完全不可推
```

setter 全是 protected/private 的链式方法（`setBurning` / `setTranslucent` / `setNoPushMobility` 等），只在静态初始化时用——运行期 Material 事实不可变。

### BlockPistonStructureHelper（`state/BlockPistonStructureHelper.java`）

活塞伸缩前计算受影响方块集合。字段：`final World world`、`final BlockPos pistonPos`、`final BlockPos blockToMove`、`final EnumFacing moveDirection`、`final List<BlockPos> toMove`、`final List<BlockPos> toDestroy`（`BlockPistonStructureHelper.java:15-20`）。

```java
public BlockPistonStructureHelper(World worldIn, BlockPos posIn, EnumFacing pistonFacing, boolean extending)  // :22
public boolean canMove()                       // :39，clear 两表后从 blockToMove 递归
public List<BlockPos> getBlocksToMove()        // :215
public List<BlockPos> getBlocksToDestroy()     // :220
```

核心递归 `private boolean func_177251_a(BlockPos origin)`（`:77`）：遇 `Blocks.slime_block` 沿反方向回溯粘连块（`:107-123`），总数超 12 立即失败（`:101`、`:119`、`:175`）；mobility==1 的方块进 `toDestroy`（`:169-173`）。粘液块还要经 `func_177250_b`（`:202`）检查垂直于推动轴的四个邻面。调用方：`BlockPistonBase`（本仓库中 grep 确认的唯一使用者），在活塞收到 block event 时执行——注意这在客户端和集成服务端两侧都会跑。

### BlockPattern + FactoryBlockPattern（`state/pattern/`）

多方块结构识别。`BlockPattern` 持有 `private final Predicate<BlockWorldState>[][][] blockMatches` 与三轴长度 `fingerLength / thumbLength / palmLength`（`BlockPattern.java:15-18`）。

```java
public BlockPattern.PatternHelper match(World worldIn, BlockPos pos)   // BlockPattern.java:81
protected static BlockPos translateOffset(BlockPos pos, EnumFacing finger, EnumFacing thumb, int palmOffset, int thumbOffset, int fingerOffset)  // BlockPattern.java:117
public static LoadingCache<BlockPos, BlockWorldState> func_181627_a(World p_181627_0_, boolean p_181627_1_)  // BlockPattern.java:108
```

`match` 对以 pos 为角、边长 max(三轴) 的立方体内每个点尝试全部 6×4 个 (finger, thumb) 正交组合（`BlockPattern.java:86-103`），源码注释自称 "fairly heavy function"。查询经 Guava `LoadingCache<BlockPos, BlockWorldState>` 去重（`CacheLoader` 内部类 `:132`）。构建方式为 `FactoryBlockPattern.start().aisle("^", "#", "#").where('#', ...).build()` 风格：`aisle` 校验每层字符画尺寸一致（`FactoryBlockPattern.java:29-69`），`build()` 前 `checkMissingPredicates` 保证每个符号都绑定了谓词（`:106-122`），空格符号默认 `Predicates.alwaysTrue()`（`:26`）。使用方（grep 确认）：`BlockSkull`（凋灵）、`BlockPumpkin`（铁傀儡/雪傀儡）、`BlockPortal`、`world.Teleporter`、`entity.Entity`。

### BlockVine（`BlockVine.java`）

本桶最大的具体方块类，是"多面布尔属性 + 随机 tick 世界改写"的典型样本。5 个 `PropertyBool`（UP/NORTH/EAST/SOUTH/WEST，`BlockVine.java:28-33`），但 meta 只有 4 bit，UP 不入 meta 而由 `getActualState`（`:47`）按上方方块是否 `isBlockNormalCube()` 动态推导。

```java
public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)   // BlockVine.java:241
public void onNeighborBlockChange(World worldIn, BlockPos pos, IBlockState state, Block neighborBlock)  // :232
public void harvestBlock(World worldIn, EntityPlayer player, BlockPos pos, IBlockState state, TileEntity te)  // :414
```

`updateTick` 与 `onNeighborBlockChange` 开头都有 `!worldIn.isRemote` 门（`:243`、`:234`）——蔓延与自毁只在服务端逻辑侧执行，客户端靠 setBlockState flag=2 的包同步。`harvestBlock`（`:414`）只有手持 `Items.shears` 才 `spawnAsEntity(worldIn, pos, new ItemStack(Blocks.vine, 1, 0))`。

## 时序与生命周期

- **类加载期（一次性）**：`Material` / `MapColor` 的全部静态实例在首次触碰各自类时初始化；`MapColor` 构造函数把自己写进 `mapColorArray[index]`（`MapColor.java:58`），越界抛 `IndexOutOfBoundsException`（`:62`）。这一切发生在 `Blocks` 注册之前。
- **Block 注册期**：每个 Block 构造时 `createBlockState()` 生成该方块的全部 `StateImplementation`（属性笛卡尔积），之后 `Block.registerBlocks` 把 (state, id<<4|meta) 写入 `BLOCK_STATE_IDS`（`Block.java:1493`）。此后 blockstate 对象集合冻结，运行期零分配。
- **每 tick（集成服务端线程）**：`BlockVine.updateTick`（`BlockVine.java:241`）在随机 tick 抽中时执行蔓延；各 `onNeighborBlockChange` 在邻块变化时同 tick 内被 `World.notifyNeighborsOfStateChange` 链式调用。`BlockPistonStructureHelper.canMove` 在活塞 block event 处理时运行。
- **每帧（主线程 / chunk rebuild 线程）**：渲染侧通过 `getActualState`（BlockVine `:47`、BlockWall `:173`）与 `setBlockBoundsBasedOnState`、`shouldSideBeRendered`（BlockWall `:148`）、`colorMultiplier`（BlockVine `:224`）读取本桶类；`StateImplementation.getValue` 是这条路径上的最热调用之一。
- **线程归属**：properties / state 对象不可变，可被主线程、chunk 构建线程、集成服务端线程并发读。带 `World` 参数的方法归属由调用方的 world 决定（`isRemote` 区分）；`BlockPattern.match` 在哪个线程调 world 就在哪个线程读方块。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumFacing side, float hitX, float hitY, float hitZ)` | BlockWorkbench.java:27 | 玩家右键工作台 | 拦截/替换合成 GUI 打开（`playerIn.displayGui(new BlockWorkbench.InterfaceCraftingTable(worldIn, pos))`），做 GUI 层接管 | `worldIn.isRemote` 时直接返回 true，真正开 GUI 在服务端侧；客户端 GUI 由 displayGui 链路弹出 |
| `public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)` | BlockWorkbench.java:67 | displayGui 链路创建容器时 | 替换 ContainerWorkbench 实现自定义合成逻辑 | getGuiID 返回 `"minecraft:crafting_table"`，客户端按此字符串路由 GUI |
| `public void onEntityCollidedWithBlock(World worldIn, BlockPos pos, IBlockState state, Entity entityIn)` | BlockWeb.java:26 | 实体 AABB 与蛛网相交的每 tick | 观察/取消 `entityIn.setInWeb()` 减速（移动类功能常关注） | 两侧都会调；只改客户端会与服务端位置校验冲突 |
| `public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)` | BlockVine.java:241 | 服务端随机 tick | 控制藤蔓蔓延速率/禁止蔓延 | 有 `!worldIn.isRemote` 门；多人下改客户端无效 |
| `public void onNeighborBlockChange(World worldIn, BlockPos pos, IBlockState state, Block neighborBlock)` | BlockVine.java:232 / BlockWallSign.java:54 | 邻块变化 | 观察方块自毁（藤蔓失去附着、墙牌失去背板 `dropBlockAsItem` + `setBlockToAir`） | 同 tick 内可能级联触发更多 neighbor 更新 |
| `public AxisAlignedBB getCollisionBoundingBox(World worldIn, BlockPos pos, IBlockState state)` | BlockWall.java:115 / BlockWeb.java:39 / BlockVine.java:149 | 实体碰撞检测每 tick 多次 | 墙的 1.5 格碰撞（`this.maxY = 1.5D`）、藤蔓/蛛网返回 null（无碰撞）是移动/自由镜头类功能的关注点 | BlockWall 先调 `setBlockBoundsBasedOnState` 再改 maxY，方法有副作用（写共享的 min/max 字段），非线程安全 |
| `public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos)` | BlockVine.java:47 / BlockWall.java:173 | chunk rebuild 渲染前 | 观察/篡改渲染用连接状态（X-Ray、轮廓类渲染改动的入口之一） | 在 chunk 构建线程被调；只影响渲染，不影响逻辑 |
| `public boolean canMove()` | BlockPistonStructureHelper.java:39 | 活塞 block event | 观察/否决活塞推动，读取 toMove/toDestroy 做预测 | 每次调用先 clear 两个列表；对象非复用安全 |
| `public BlockPattern.PatternHelper match(World worldIn, BlockPos pos)` | BlockPattern.java:81 | 凋灵/傀儡/门户构建检测 | 观察多方块结构成型时机（自动化、提示类功能） | 源码注明 heavy；每次 match 新建 LoadingCache，勿高频轮询 |
| `boolean canGrow(World worldIn, BlockPos pos, IBlockState state, boolean isClient)` / `void grow(World worldIn, Random rand, BlockPos pos, IBlockState state)` | IGrowable.java:13 / :17 | ItemDye 骨粉右键 | 自动骨粉/催熟类功能的统一判定入口 | 接口无默认实现，行为完全在各实现类 |

## 数据与协议

本桶不直接收发封包，但 **meta 编解码是 chunk 数据与 MultiBlockChange 封包的落地格式**（`Block.getStateById` → `getStateFromMeta`，`Block.java:178`）。字段级编码：

| 方块 | meta bit | 读方法 | 写方法 | 含义 |
|---|---|---|---|---|
| BlockVine | bit0=SOUTH, bit1=WEST, bit2=NORTH, bit3=EAST | `getStateFromMeta(int meta)` BlockVine.java:435 | `getMetaFromState(IBlockState state)` :443 | 各面附着；UP 不存 meta，由 getActualState 推导 |
| BlockWall | meta 0-1 = VARIANT (NORMAL=0/MOSSY=1) | BlockWall.java:156 | :164 | UP/N/E/S/W 全部不存 meta，渲染期动态计算 |
| BlockWallSign | meta = FACING.getIndex()，轴为 Y 时强制 NORTH | BlockWallSign.java:70 | :85 | 朝向 |
| BlockWoodSlab | bit0-2 = VARIANT (BlockPlanks.EnumType), bit3 = HALF TOP | BlockWoodSlab.java:92 | :107 | 双台阶时无 HALF 属性 |

注册表相关：`MapColor` 构造即写入 `public static final MapColor[] mapColorArray = new MapColor[64]`（`MapColor.java:8`），`colorIndex`（0-63）与 `colorValue`（RGB int）被 `ItemMap.updateMapData` 采样后写进地图物品的 NBT 颜色字节；`getMapColor(int p_151643_1_)`（`MapColor.java:66`）按亮度档 0-3（180/220/220→变体/255/135）输出最终 ARGB。

## 不变量与陷阱

- **blockstate 同一性不变量**：同 Block 同属性组合永远是同一个 `StateImplementation` 实例（`withProperty` 只查表，`BlockState.java:147`）。可以放心用 `==` 比较 state；反过来，绝不能自己 new 一个 IBlockState 实现塞进 world——`BLOCK_STATE_IDS` 与渲染缓存都认实例。
- `withProperty` / `getValue` 对不存在的属性抛 `IllegalArgumentException`（`BlockState.java:127`、`:139`、`:143`）。跨方块复用 IProperty 常量（如把 `BlockVine.UP` 用在别的方块）会直接炸。
- `BlockState` 构造函数对 properties 按名字排序（`BlockState.java:42`），`getBaseState()` 是排序后笛卡尔积的第 0 项——**不是**"全 false/第一个枚举值"的直觉序，依赖属性声明顺序的假设不成立。
- `PropertyHelper.equals` 只比 name + valueClass（`PropertyHelper.java:40`）：两个同名同类型但允许值不同的 PropertyInteger 在 `PropertyHelper` 层被视为相等；`PropertyInteger` 自己覆写补上了 allowedValues 比较（`PropertyInteger.java:42`），`PropertyEnum` 没有覆写。
- `Material` 的 setter 是 protected 链式方法，仅静态初始化期调用；运行期把它当只读对象。`Material.web` 是匿名子类（`Material.java:39-45`），`instanceof MaterialLiquid` 之类的判断对它不适用。
- `Block.setBlockBounds` 系方法（BlockWall/BlockWallSign/BlockVine 的 `setBlockBoundsBasedOnState`）写的是 Block 单例上的共享 minX..maxZ 字段——**有副作用且非线程安全**，渲染线程与逻辑线程并发调用同一 Block 时存在竞态（vanilla 原有问题，移植未改）。
- `BlockPistonStructureHelper` 的 12 块上限出现在三处（`:101`、`:119`、`:175`），修改推动上限要同时改齐。
- `BlockPattern.match` 每次新建 LoadingCache、体积与朝向全枚举，禁止每帧调用。
- **移植注意（Guava 升级）**：本仓库 Guava 已升到 33.6.0-jre（`client/pom.xml:118`，其上方注释 "Guava kept at 17.0" 已过时，勿信）。因此本桶源码用的是 `com.google.common.base.MoreObjects.toStringHelper`（`PropertyHelper.java:28`、`BlockState.java:103`、`BlockPattern.java:202`）而非 vanilla 1.8.9 的 `Objects.toStringHelper`——对照原版 MCP 源码 diff 时这是预期差异，不是逻辑改动。
- 本桶无 LWJGL 直接依赖，LWJGL3 移植对其无影响；JDK 25 下泛型原始类型（`IProperty` 裸用、`Predicate` 裸转型如 `FactoryBlockPattern.java:90`）只产生编译警告，行为不变。

## 交叉引用

- `net.minecraft.block` → `Block#createBlockState` / `Block#getStateFromMeta`（Block.java:298 / :257 挂接本桶 state/properties 体系）
- `net.minecraft.block` → `BlockPistonBase#canPush`（BlockPistonStructureHelper.java:45 反向调用）
- `net.minecraft.block` → `BlockSkull` / `BlockPumpkin` / `BlockPortal`（使用 FactoryBlockPattern / BlockPattern 检测凋灵、傀儡、地狱门）
- `net.minecraft.world` → `World#getBlockState` / `World#setBlockState`（BlockVine.updateTick 等全部世界改写）
- `net.minecraft.world` → `Teleporter`（使用 BlockPattern 相关 API）
- `net.minecraft.world` → `ColorizerFoliage#getFoliageColorBasic`（BlockVine.java:216）
- `net.minecraft.entity` → `Entity#setInWeb`（BlockWeb.java:28）
- `net.minecraft.entity.player` → `EntityPlayer#displayGui`（BlockWorkbench.java:35，GUI 打开链路）
- `net.minecraft.inventory` → `ContainerWorkbench`（BlockWorkbench.java:69）
- `net.minecraft.item` → `ItemMap`（采样 `Block#getMapColor` → MapColor，地图渲染）
- `net.minecraft.stats` → `StatList#mineBlockStatArray` / `StatList.field_181742_Z`（BlockVine.java:418 / BlockWorkbench.java:36）
- `net.minecraft.util` → `Cartesian#cartesianProduct` / `MapPopulator#createMap`（BlockState.java:53 / :55）
- `net.minecraft.init` → `Blocks` / `Items`（Blocks.slime_block、Blocks.barrier、Items.shears、Items.string 等常量引用）

## 覆盖声明

完整读取了 30/30 个文件（每个文件从第 1 行到最后一行）。

逐行精读：BlockVine、BlockWall、BlockWoodSlab、BlockWorkbench、Material、MapColor、BlockState、BlockStateBase、BlockPistonStructureHelper、BlockPattern、FactoryBlockPattern、PropertyEnum、PropertyInteger、PropertyHelper。

完整读取但内容简单、只需结构性理解：BlockWallSign、BlockWeb、BlockYellowFlower、IGrowable、ITileEntityProvider、MaterialLiquid、MaterialLogic、MaterialPortal、MaterialTransparent、IProperty、PropertyBool、PropertyDirection、BlockWorldState、IBlockState、BlockHelper、BlockStateHelper。

行号引用均来自本次 Read 输出；外部调用方（BlockPistonBase、BlockSkull、BlockPumpkin、ItemMap、Teleporter、Block.java 挂接点）经 grep 确认。
