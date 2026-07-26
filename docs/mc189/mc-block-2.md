---
area: net/minecraft/block#2
slug: mc-block-2
files: 71
lines: 13113
tier: C
---

# net/minecraft/block（第 2 桶：BlockMobSpawner ~ BlockTripWireHook）

## 定位

本桶是 `net.minecraft.block` 包按字母序切出的第二段（M~T 区间），共 71 个具体方块实现类。它们全部继承自 `Block`（或其子类 `BlockContainer` / `BlockBush` / `BlockLeaves` / `BlockLog` / `BlockFalling` / `BlockBreakable` / `BlockLiquid` / `BlockDirectional` / `BlockBasePressurePlate` 等，这些基类在其它桶），通过覆写 `Block` 的虚方法来定义各方块的碰撞、渲染层、掉落、红石、tick 行为。唯一的例外是 `BlockSourceImpl`，它不是方块而是 `IBlockSource` 的实现，供发射器（dispenser）行为体系定位方块。

调用方：
- `World` / `WorldServer` 的 tick 循环调用 `updateTick` / `randomTick`；`WorldClient` 每 tick 调 `randomDisplayTick`（`net/minecraft/client/multiplayer/WorldClient.java:319`）做粒子与音效；
- `World.setBlockState` / `notifyNeighborsOfStateChange` 触发 `onBlockAdded` / `onNeighborBlockChange` / `breakBlock`；
- 玩家交互链（`PlayerControllerMP` → 服务器侧 `ItemInWorldManager`）调 `onBlockActivated` / `onBlockClicked` / `harvestBlock`；
- 区块渲染重建调 `getActualState` / `shouldSideBeRendered` / `getBlockLayer` / `colorMultiplier`；
- `S24PacketBlockAction` 经 `World.addBlockEvent` 队列最终调 `onBlockEventReceived`（`net/minecraft/world/World.java:3483`），活塞与音符盒依赖此通道；
- 红石求值（`World.getRedstonePower` / `isBlockPowered`）调 `getWeakPower` / `getStrongPower` / `canProvidePower`。

它们调用的对象：`World`（读写方块状态、调度 tick、生成实体/粒子）、`init.Blocks` / `init.Items`（注册表单例比较）、`tileentity.*`（Note/Piston/Skull/Comparator/Sign/MobSpawner）、`block.state.pattern.BlockPattern`（南瓜傀儡、凋灵、传送门多方块结构识别）、`world.gen.feature.WorldGen*`（树苗/蘑菇生长）。

如果本桶消失：世界里从矿石、原木、树叶、楼梯、台阶到整套红石元件（红石线、中继器、比较器、红石火把、活塞、压力板、绊线、探测铁轨）都无法实例化，`Blocks` 类静态初始化直接失败，客户端无法启动。

## 类清单

| 类名 | 行数 | extends / implements | 一句话职责 |
|---|---|---|---|
| BlockMobSpawner | 79 | extends BlockContainer | 刷怪笼，创建 TileEntityMobSpawner，不掉落自身，破坏掉经验 |
| BlockMushroom | 127 | extends BlockBush implements IGrowable | 小蘑菇，随机 tick 蔓延，骨粉可长成巨型蘑菇 |
| BlockMycelium | 98 | extends Block | 菌丝，随机 tick 向邻近泥土蔓延，SNOWY 属性由 getActualState 推导 |
| BlockNetherBrick | 23 | extends Block | 地狱砖，仅覆写 getMapColor |
| BlockNetherWart | 125 | extends BlockBush | 地狱疣，AGE 0-3 随机生长，只能种在 soul_sand 上 |
| BlockNetherrack | 23 | extends Block | 地狱岩，仅覆写 getMapColor |
| BlockNewLeaf | 125 | extends BlockLeaves | 1.7 新增两种树叶（ACACIA/DARK_OAK），meta 低 2 位存 variant-4 |
| BlockNewLog | 139 | extends BlockLog | 新原木（ACACIA/DARK_OAK），meta 低 2 位 variant、高 2 位 LOG_AXIS |
| BlockNote | 123 | extends BlockContainer | 音符盒，红石上升沿触发，经 block event 播音效与粒子 |
| BlockObsidian | 34 | extends Block | 黑曜石，掉落自身，黑色地图色 |
| BlockOldLeaf | 160 | extends BlockLeaves | 旧四种树叶（OAK/SPRUCE/BIRCH/JUNGLE），含针叶/桦木固定色 |
| BlockOldLog | 147 | extends BlockLog | 旧四种原木，meta 编码同 BlockNewLog |
| BlockOre | 119 | extends Block | 煤/钻/青金/绿宝石/石英矿石通用类，按 this==Blocks.* 分支决定掉落与经验 |
| BlockPackedIce | 23 | extends Block | 浮冰，slipperiness = 0.98F，不掉落 |
| BlockPane | 204 | extends Block | 玻璃板/铁栏杆，NORTH/EAST/SOUTH/WEST 连接属性全由 getActualState 计算 |
| BlockPistonBase | 488 | extends Block | 活塞本体，checkForMove/doMove 实现推拉，经 block event 双端同步 |
| BlockPistonExtension | 279 | extends Block | 活塞头（piston_head），依附本体存在，破坏时连带处理本体 |
| BlockPistonMoving | 312 | extends BlockContainer | 移动中活塞占位方块（piston_extension），碰撞箱由 TileEntityPiston 进度插值 |
| BlockPlanks | 145 | extends Block | 木板，内含 BlockPlanks.EnumType（6 种木头）供全包复用 |
| BlockPortal | 484 | extends BlockBreakable | 下界传送门，内部类 Size 检测/填充黑曜石门框，碰撞触发 entity.setPortal |
| BlockPotato | 37 | extends BlockCrops | 马铃薯作物，成熟时 1/50 概率额外掉毒马铃薯 |
| BlockPressurePlate | 96 | extends BlockBasePressurePlate | 木/石压力板，POWERED 二值输出，Sensitivity 区分 EVERYTHING/MOBS |
| BlockPressurePlateWeighted | 84 | extends BlockBasePressurePlate | 测重压力板，POWER 0-15 按实体数占比输出 |
| BlockPrismarine | 141 | extends Block | 海晶石三变种（ROUGH/BRICKS/DARK） |
| BlockPumpkin | 190 | extends BlockDirectional | 南瓜，onBlockAdded 时用 BlockPattern 检测雪傀儡/铁傀儡结构并生成实体 |
| BlockQuartz | 164 | extends Block | 石英块，LINES 变种放置时按面轴向旋转 |
| BlockRail | 53 | extends BlockRailBase | 普通铁轨，允许弯轨；三邻接时红石信号可切换道岔 |
| BlockRailBase | 674 | extends Block（abstract） | 铁轨基类，内部类 Rail 实现邻接铁轨形状自适应算法 |
| BlockRailDetector | 209 | extends BlockRailBase | 探测铁轨，检测矿车输出红石，含 comparator override 读矿车库存 |
| BlockRailPowered | 199 | extends BlockRailBase | 动力铁轨，func_176566_a 沿轨传播供电最远 8 格 |
| BlockRedFlower | 12 | extends BlockFlower | 红花容器类，仅返回 EnumFlowerColor.RED |
| BlockRedSandstone | 121 | extends Block | 红砂岩三变种（DEFAULT/CHISELED/SMOOTH） |
| BlockRedstoneComparator | 339 | extends BlockRedstoneDiode implements ITileEntityProvider | 比较器，COMPARE/SUBTRACT 两模式，输出值存 TileEntityComparator |
| BlockRedstoneDiode | 290 | extends BlockDirectional（abstract） | 中继器/比较器共同基类，powered/unpowered 双 Block 实例切换 |
| BlockRedstoneLight | 88 | extends Block | 红石灯，isOn 双实例，熄灭延迟 4 tick |
| BlockRedstoneOre | 178 | extends Block | 红石矿，点击/踩踏切换 lit 实例并发粒子，掉红石与经验 |
| BlockRedstoneRepeater | 156 | extends BlockRedstoneDiode | 中继器，DELAY 1-4，LOCKED 由侧向中继器信号推导 |
| BlockRedstoneTorch | 228 | extends BlockTorch | 红石火把，isOn 双实例，static toggles 表实现烧毁（60 tick 内 8 次翻转） |
| BlockRedstoneWire | 516 | extends Block | 红石线，calculateCurrentChanges 传播 POWER 0-15，连接形状由 getActualState 推导 |
| BlockReed | 177 | extends Block | 甘蔗，AGE 0-15 计数到 15 长高一格，最高 3 格，需邻水 |
| BlockRotatedPillar | 21 | extends Block（abstract） | 轴向柱状方块基类，只声明 AXIS 属性 |
| BlockSand | 133 | extends BlockFalling | 沙子/红沙，重力方块，VARIANT 决定地图色 |
| BlockSandStone | 130 | extends Block | 砂岩三变种 |
| BlockSapling | 269 | extends BlockBush implements IGrowable | 树苗，STAGE 0-1 两段生长，generateTree 分派 8 种 WorldGenerator，含 2x2 巨树检测 |
| BlockSeaLantern | 56 | extends Block | 海晶灯，掉 2-3 个 prismarine_crystals，可精准采集 |
| BlockSign | 102 | extends BlockContainer | 告示牌基类，创建 TileEntitySign，右击执行命令 |
| BlockSilverfish | 238 | extends Block | 蠹虫方块（monster_egg），破坏时刷 EntitySilverfish，EnumType 映射伪装方块 |
| BlockSkull | 304 | extends BlockContainer | 头颅，创建 TileEntitySkull；checkWitherSpawn 用 BlockPattern 召唤凋灵，玩家头写 SkullOwner NBT |
| BlockSlab | 191 | extends Block（abstract） | 台阶基类，HALF TOP/BOTTOM，isDouble() 区分单双台阶实例 |
| BlockSlime | 70 | extends BlockBreakable | 粘液块，onLanded 反弹 motionY，潜行落地不弹 |
| BlockSnow | 176 | extends Block | 雪层，LAYERS 1-8，光照 >11 融化，harvestBlock 掉 layers+1 个雪球 |
| BlockSnowBlock | 46 | extends Block | 雪块，掉 4 雪球，光照 >11 融化 |
| BlockSoulSand | 34 | extends Block | 灵魂沙，碰撞箱矮 0.125，碰撞实体水平速度 ×0.4 |
| BlockSourceImpl | 55 | implements IBlockSource | 发射器行为用的 (World, BlockPos) 包装，非方块 |
| BlockSponge | 203 | extends Block | 海绵，BFS 吸水（半径 6 层、上限 ~64 格），WET 状态滴水粒子 |
| BlockStainedGlass | 117 | extends BlockBreakable | 染色玻璃，16 色 COLOR，增删时调 BlockBeacon.updateColorAsync |
| BlockStainedGlassPane | 98 | extends BlockPane | 染色玻璃板，COLOR + 四向连接 |
| BlockStairs | 844 | extends Block | 楼梯，形状（内外角）由邻接推导，8 子体素射线求交，其余行为委托 modelBlock |
| BlockStandingSign | 53 | extends BlockSign | 立式告示牌，ROTATION 0-15，下方不实心则掉落 |
| BlockStaticLiquid | 106 | extends BlockLiquid | 静止液体，邻居变化时转 BlockDynamicLiquid；岩浆随机 tick 点燃周围 |
| BlockStem | 237 | extends BlockBush implements IGrowable | 南瓜/西瓜茎，AGE 0-7，成熟后向四周空地生成果实，FACING 由 getActualState 指向果实 |
| BlockStone | 165 | extends Block | 石头 7 变种（含花岗岩/闪长岩/安山岩），STONE 变种掉圆石 |
| BlockStoneBrick | 126 | extends Block | 石砖 4 变种（DEFAULT/MOSSY/CRACKED/CHISELED） |
| BlockStoneSlab | 227 | extends BlockSlab（abstract） | 旧石台阶 8 变种，双台阶实例带 SEAMLESS 位 |
| BlockStoneSlabNew | 219 | extends BlockSlab（abstract） | 红砂岩台阶（stone_slab2），结构与 BlockStoneSlab 相同 |
| BlockTNT | 161 | extends Block | TNT，红石/火焰弹/燃烧箭点燃生成 EntityTNTPrimed |
| BlockTallGrass | 226 | extends BlockBush implements IGrowable | 草丛/蕨，剪刀掉自身否则 1/8 掉种子，骨粉长成双层草 |
| BlockTorch | 312 | extends Block | 火把，FACING 五向依附，失去支撑掉落，火焰粒子 |
| BlockTrapDoor | 312 | extends Block | 活板门，OPEN/HALF/FACING，铁活板门只认红石不认右击 |
| BlockTripWire | 300 | extends Block | 绊线（string），实体碰撞检测，notifyHook 沿线通知最远 42 格内的钩子 |
| BlockTripWireHook | 373 | extends Block | 绊线钩，func_176260_a 扫描整条线求 ATTACHED/POWERED，输出红石强信号 |

## 核心类详解

### BlockPistonBase（BlockPistonBase.java，488 行）

关键字段：
- `public static final PropertyDirection FACING`（BlockPistonBase.java:27）
- `public static final PropertyBool EXTENDED`（BlockPistonBase.java:28）
- `private final boolean isSticky`（BlockPistonBase.java:31）— 普通/粘性活塞共用此类，构造参数区分。

关键方法（签名逐字）：
- `private void checkForMove(World worldIn, BlockPos pos, IBlockState state)`（BlockPistonBase.java:92）— 由 `onBlockPlacedBy`/`onNeighborBlockChange`/`onBlockAdded` 在服务端（`!worldIn.isRemote`）调用；应伸出时先用 `BlockPistonStructureHelper` 验证可推，再 `worldIn.addBlockEvent(pos, this, 0, enumfacing.getIndex())`（BlockPistonBase.java:101）。
- `public boolean onBlockEventReceived(World worldIn, BlockPos pos, IBlockState state, int eventID, int eventParam)`（BlockPistonBase.java:144）— block event 到达时双端执行：eventID 0 伸出（`doMove` + 播 `"tile.piston.out"`），eventID 1 收回（放置 `Blocks.piston_extension` 占位方块 + `BlockPistonMoving.newTileEntity`，粘性活塞尝试拉回前方 2 格方块）。
- `public static boolean canPush(Block blockIn, World worldIn, BlockPos pos, EnumFacing direction, boolean allowDestroy)`（BlockPistonBase.java:322）— 推动规则中枢：黑曜石、硬度 -1、mobilityFlag==2、含 TileEntity 的方块不可推；mobilityFlag==1 仅在 allowDestroy 时可破坏。`BlockPistonStructureHelper` 也调用它。
- `private boolean doMove(World worldIn, BlockPos pos, EnumFacing direction, boolean extending)`（BlockPistonBase.java:376）— 逆序销毁 destroy 列表、把 move 列表逐个替换为 `Blocks.piston_extension` + `TileEntityPiston`，最后统一 `notifyNeighborsOfStateChange`。

时机：红石变化 → `onNeighborBlockChange`（主线程，服务端逻辑）→ `checkForMove` → block event 队列 → 下一 tick `World` 派发 `onBlockEventReceived`（客户端由 `S24PacketBlockAction` 驱动，见 `net/minecraft/client/network/NetHandlerPlayClient.java:1318` 的注释）。

### BlockRedstoneWire（BlockRedstoneWire.java，516 行）

关键字段：
- `public static final PropertyInteger POWER = PropertyInteger.create("power", 0, 15)`（BlockRedstoneWire.java:34）
- `private boolean canProvidePower = true`（BlockRedstoneWire.java:35）— 计算自身输入时临时置 false 以避免自反馈（BlockRedstoneWire.java:123-125）。
- `private final Set<BlockPos> blocksNeedingUpdate`（BlockRedstoneWire.java:36）— 单实例可变状态，传播过程中累积待通知坐标。

关键方法：
- `private IBlockState calculateCurrentChanges(World worldIn, BlockPos pos1, BlockPos pos2, IBlockState state)`（BlockRedstoneWire.java:117）— 取「非红石线输入 k」与「邻接线最大强度 l - 1」的较大者写回 POWER，变更时把自身及 6 邻加入 `blocksNeedingUpdate`。
- `private IBlockState updateSurroundingRedstone(World worldIn, BlockPos pos, IBlockState state)`（BlockRedstoneWire.java:103）— 调上者后逐个 `worldIn.notifyNeighborsOfStateChange(blockpos, this)`；由 `onBlockAdded`（212）、`breakBlock`（244）、`onNeighborBlockChange`（294）触发。
- `public int getWeakPower(IBlockAccess worldIn, BlockPos pos, IBlockState state, EnumFacing side)`（BlockRedstoneWire.java:323）— 依据 `func_176339_d` 计算的水平连接集合决定该面是否输出。
- `protected static boolean canConnectTo(IBlockState blockState, EnumFacing side)`（BlockRedstoneWire.java:389）— 线/中继器（仅轴向）/任意 `canProvidePower()` 方块的连接判定，`getActualState`（49）与渲染连接形状都依赖它。

注意：1.8 的红石线更新是重入递归 + 集合去重，不是 1.13+ 的图算法；单块变更可能触发 O(线长) 次 `setBlockState`。

### BlockRailBase / BlockRailBase.Rail（BlockRailBase.java，674 行）

- `public abstract IProperty<BlockRailBase.EnumRailDirection> getShapeProperty()`（BlockRailBase.java:182）— 三个子类各自声明 SHAPE 属性（弯轨谓词不同）后经此暴露给基类。
- `public static boolean isRailBlock(IBlockState state)`（BlockRailBase.java:29）— 判定四种铁轨，矿车物理（EntityMinecart）也用它。
- `protected IBlockState func_176564_a(World worldIn, BlockPos p_176564_2_, IBlockState p_176564_3_, boolean p_176564_4_)`（BlockRailBase.java:151）— 服务端把形状自适应委托给内部类 `Rail`。
- 内部类 `public class Rail`（BlockRailBase.java:245）：`public BlockRailBase.Rail func_180364_a(boolean p_180364_1_, boolean p_180364_2_)`（BlockRailBase.java:506）扫描四邻求出 `EnumRailDirection`（含 ASCENDING_* 与四个弯角，isPowered 铁轨禁弯），写回后递归修正邻轨（`func_150645_c`，BlockRailBase.java:410）。
- `public void onNeighborBlockChange(World worldIn, BlockPos pos, IBlockState state, Block neighborBlock)`（BlockRailBase.java:106）— 支撑检查（脚下与上坡方向必须 `World.doesBlockHaveSolidTopSurface`），失败即掉落；否则进子类钩子 `onNeighborChangedInternal`（147，空实现）。

`EnumRailDirection`（BlockRailBase.java:184）10 值 meta 0-9；探测/动力轨只用 0-5 并把 bit 8 留给 POWERED。

### BlockPortal（BlockPortal.java，484 行）

- `public static final PropertyEnum<EnumFacing.Axis> AXIS`（BlockPortal.java:26）— 仅 X/Z。
- `public boolean func_176548_d(World worldIn, BlockPos p_176548_2_)`（BlockPortal.java:95）— 点火入口：先按 X 轴再按 Z 轴构造 `BlockPortal.Size`，`func_150860_b()` 校验门框（宽 2-21、高 3-21、黑曜石包边）通过且框内无既有 portal（`field_150864_e == 0`）则 `func_150859_c()` 填充。调用方是 `BlockFire`（`net/minecraft/block/BlockFire.java:384`）。
- `public void onNeighborBlockChange(World worldIn, BlockPos pos, IBlockState state, Block neighborBlock)`（BlockPortal.java:123）— 重新验证门框，不完整则把自己变回空气（传送门连锁熄灭的实现方式）。
- `public void onEntityCollidedWithBlock(World worldIn, BlockPos pos, IBlockState state, Entity entityIn)`（BlockPortal.java:197）— 无骑乘关系时 `entityIn.setPortal(pos)`，维度切换从这里开始。
- `public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)`（BlockPortal.java:35）— 主世界按难度概率经 `ItemMonsterPlacer.spawnCreature(worldIn, 57, ...)` 刷僵尸猪人（57 为实体 ID 硬编码）。
- 内部类 `public static class Size`（BlockPortal.java:317）字段 `field_150868_h`（宽）、`field_150862_g`（高）、`field_150864_e`（已有 portal 方块计数）、`field_150861_f`（底角，位于 EAST/NORTH 方向尽头，左右取决于观察方向 (未验证)）。

### BlockStairs（BlockStairs.java，844 行）

- 字段 `private final Block modelBlock; private final IBlockState modelState;`（BlockStairs.java:34-35）— 构造时从 `modelState` 抄硬度/爆炸抗性/脚步声，并把 `randomDisplayTick`/`onBlockClicked`/`updateTick`/`onBlockActivated`/`breakBlock` 等十余个行为直接委托给 modelBlock（BlockStairs.java:548-655）。
- `private boolean hasRaytraced; private int rayTracePass;`（BlockStairs.java:36-37）— 单实例可变状态，供射线求交时切换 8 个半格子体素包围盒。
- `public MovingObjectPosition collisionRayTrace(World worldIn, BlockPos pos, Vec3 start, Vec3 end)`（BlockStairs.java:679）— 按 `field_150150_a[i + (flag ? 4 : 0)]` 排除不属于该朝向的子体素后对其余逐个 `super.collisionRayTrace`，取距 end 最远的命中（即距 start 最近的进入点）。
- `public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos)`（BlockStairs.java:753）— 用 `func_176306_h`（内角判定）/`func_176305_g`/`func_176307_f` 推导 SHAPE（STRAIGHT/INNER_*/OUTER_*），只影响渲染模型，不存 meta。
- `public void addCollisionBoxesToList(World worldIn, BlockPos pos, IBlockState state, AxisAlignedBB mask, List<AxisAlignedBB> list, Entity collidingEntity)`（BlockStairs.java:533）— 半砖底座 + 1~2 个角盒。
- meta 编码非常规：`i = i | 5 - ((EnumFacing)state.getValue(FACING)).getIndex();`（BlockStairs.java:745），FACING 用 `EnumFacing.getFront(5 - (meta & 3))` 还原（BlockStairs.java:729）。

## 时序与生命周期

- 类加载/注册：所有构造器为 protected/public，由 `Block.registerBlocks()`（经 `net.minecraft.init.Bootstrap` → `Blocks` 静态初始化）在客户端启动早期于主线程一次性实例化并注册。每个 Block 是全局单例；`createBlockState()` 在 `Block` 构造器中被调用，因此子类 static Property 字段必须在构造前完成初始化（都是 static final，天然满足）。
- 每 tick（服务端逻辑侧，本仓库为客户端源码树但保留服务端 World 逻辑）：
  - 随机 tick：`setTickRandomly(true)` 的类（BlockMushroom、BlockMycelium、BlockNetherWart、BlockPortal、BlockPumpkin、BlockRedstoneOre(lit)、BlockReed、BlockSnow、BlockSnowBlock、BlockSapling、BlockStem、BlockTorch、BlockRedstoneTorch、BlockRailDetector、BlockTripWire、BlockTripWireHook、BlockStaticLiquid(lava)）收到 `updateTick`/`randomTick`。注意 BlockRailDetector/BlockRedstoneDiode/BlockRedstoneTorch/BlockTripWire/BlockTripWireHook 覆写 `randomTick` 为空（如 BlockRailDetector.java:74），只吃计划 tick。
  - 计划 tick：`worldIn.scheduleUpdate` / `updateBlockTick` 驱动，延迟由 `tickRate(World)` 给出——BlockRedstoneTorch 2、BlockPressurePlateWeighted 10、BlockRailDetector 20、BlockRedstoneOre 30、中继器 `DELAY*2`、比较器固定 2。
  - block event：`World.addBlockEvent` 收集，tick 末派发 `onBlockEventReceived`（World.java:3483）；服务端同时广播 `S24PacketBlockAction`，客户端在 Netty EventLoop 收包后转主线程重放同一方法（NetHandlerPlayClient.java:1318 注释明确列出 BlockPistonBase 与 BlockNote）。
- 每帧无逐帧逻辑；`randomDisplayTick`（火把/传送门/红石线/中继器/菌丝/海绵/红石矿的粒子）由 `WorldClient` 在客户端 tick（非渲染帧）随机采样调用（WorldClient.java:319），仍是主线程。
- 线程归属：本桶全部方法都假定主线程串行调用。唯一的跨线程触点是 BlockStainedGlass/BlockStainedGlassPane 在 `onBlockAdded`/`breakBlock` 里调 `BlockBeacon.updateColorAsync(worldIn, pos)`（BlockStainedGlass.java:93,101），该方法在 BlockBeacon（另一桶）内提交到后台线程扫描信标光束颜色。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public boolean onBlockEventReceived(World worldIn, BlockPos pos, IBlockState state, int eventID, int eventParam)` | BlockPistonBase.java:144 | 活塞伸缩 block event 到达（双端） | 观察/取消活塞动作、无幽灵方块的活塞动画替换、反作弊回放 | 双端都会执行；服务端会先复核 `shouldBeExtended`，只改客户端会导致状态漂移 |
| `public boolean onBlockEventReceived(World worldIn, BlockPos pos, IBlockState state, int eventID, int eventParam)` | BlockNote.java:108 | 音符盒被触发（eventID=乐器, eventParam=音高） | 拦截/替换音效、可视化音符、做音乐机 UI | 音效名 `"note." + getInstrument(eventID)` 硬编码 5 种乐器（BlockNote.java:19） |
| `public void onEntityCollidedWithBlock(World worldIn, BlockPos pos, IBlockState state, Entity entityIn)` | BlockPortal.java:197 | 实体每 tick 与传送门相交 | 拦截 `entityIn.setPortal(pos)` 可禁用/重定向维度切换 | 每 tick 反复触发，冷却逻辑在 Entity 侧 |
| `public void onLanded(World worldIn, Entity entityIn)` | BlockSlime.java:44 | 实体落到粘液块上 | 修改反弹系数（`entityIn.motionY = -entityIn.motionY`）、做弹跳预测 | 注释明确要求必须更新 motionY；潜行分支走 super |
| `public void onEntityCollidedWithBlock(World worldIn, BlockPos pos, IBlockState state, Entity entityIn)` | BlockSoulSand.java:29 | 实体在灵魂沙内移动 | 移动类功能（速度修正、NoSlow 类检测点） | 直接乘 `motionX/Z *= 0.4D`，无状态可查询 |
| `public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumFacing side, float hitX, float hitY, float hitZ)` | BlockTrapDoor.java:127 | 右击活板门 | GUI/交互层拦截开关门、播放自定义反馈 | 铁活板门 `blockMaterial == Material.iron` 直接 return true 不切换 |
| `public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumFacing side, float hitX, float hitY, float hitZ)` | BlockRedstoneComparator.java:164 | 右击比较器切换模式 | 红石调试工具：显示当前输出 `calculateOutput` | 客户端也执行 `cycleProperty(MODE)`；真实输出存 TileEntityComparator |
| `public void explode(World worldIn, BlockPos pos, IBlockState state, EntityLivingBase igniter)` | BlockTNT.java:76 | TNT 被点燃（红石/打火石/燃烧箭三条路径汇聚点） | 记录点燃者（igniter）、禁爆区、爆炸预测 | 仅服务端分支生成 EntityTNTPrimed；EXPLODE 属性必须为 true |
| `public void checkWitherSpawn(World worldIn, BlockPos pos, TileEntitySkull te)` | BlockSkull.java:201 | 放置凋灵骷髅头后（ItemSkull.java:110、Bootstrap.java:439 发射器分支） | 凋灵召唤检测/拦截、成就联动 | PEACEFUL 或 `worldIn.isRemote` 时直接返回 |
| `private void trySpawnGolem(World worldIn, BlockPos pos)` | BlockPumpkin.java:56 | `onBlockAdded`（BlockPumpkin.java:45）即南瓜落位时 | 傀儡召唤观测；private，需在 onBlockAdded 层挂钩 | 消耗结构方块并 `spawnEntityInWorld`，客户端世界不应执行 |
| `public int getWeakPower(IBlockAccess worldIn, BlockPos pos, IBlockState state, EnumFacing side)` | BlockRedstoneWire.java:323 | 每次红石求值 | 红石可视化 HUD 读 POWER；改写可伪造信号 | 受 `canProvidePower` 瞬态标志影响，传播中读取会得到 0 |
| `public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)` | BlockRedstoneTorch.java:112 | 火把计划 tick（延迟 2） | 观测烧毁机制（toggles 表）、时钟电路分析 | static `toggles` 以 World 为键，跨维度共享类状态 |
| `public MovingObjectPosition collisionRayTrace(World worldIn, BlockPos pos, Vec3 start, Vec3 end)` | BlockStairs.java:679 | 准星拾取/射弹命中楼梯 | 精确命中判定（8 子体素）；瞄准辅助需复用此逻辑 | 依赖实例字段 hasRaytraced/rayTracePass，非线程安全 |
| `protected void onNeighborChangedInternal(World worldIn, BlockPos pos, IBlockState state, Block neighborBlock)` | BlockRailBase.java:147 | 铁轨邻居变化且支撑检查通过后 | 子类钩子：轨道形状/供电联动（BlockRail.java:20、BlockRailPowered.java:149 为现成示例） | 仅服务端路径（外层已判 `!worldIn.isRemote`） |
| `public void func_176260_a(World worldIn, BlockPos pos, IBlockState hookState, boolean p_176260_4_, boolean p_176260_5_, int p_176260_6_, IBlockState p_176260_7_)` | BlockTripWireHook.java:129 | 绊线钩重扫整条线（放置/断线/计划 tick） | 陷阱检测器（读 ATTACHED/POWERED 推导）、绊线可视化 | 一次扫 42 格并可能批量 setBlockState，勿在其中再触发扫描 |

## 数据与协议

本桶不直接编解码网络封包，但两类数据在线上/存档中流动：

1) meta ↔ IBlockState 编码（`getStateFromMeta`/`getMetaFromState`），随区块数据与 `S23PacketBlockChange` 传输。代表性编码表：

| 方块 | 位段 | 含义 | 读/写方法 |
|---|---|---|---|
| BlockPistonBase | bit0-2 = FACING.getIndex()，bit3 = EXTENDED | 朝向 0-5；8 为伸出 | getStateFromMeta:463 / getMetaFromState:471 |
| BlockPistonExtension / BlockPistonMoving | bit0-2 = FACING，bit3 = TYPE==STICKY | 头/占位块共享编码 | BlockPistonExtension.java:231,239 |
| BlockOldLeaf / BlockNewLeaf | bit0-1 = VARIANT（New 系为 variant-4），bit2 = !DECAYABLE，bit3 = CHECK_DECAY | 树叶腐烂标志 | BlockOldLeaf.java:103,111；BlockNewLeaf.java:77,85 |
| BlockOldLog / BlockNewLog | bit0-1 = VARIANT，bit2-3 = LOG_AXIS（0=Y,4=X,8=Z,12=NONE） | 原木轴向 | BlockOldLog.java:77,107 |
| BlockRail | meta 0-9 = EnumRailDirection | 含 4 弯角 | BlockRail.java:36,44 |
| BlockRailDetector / BlockRailPowered | bit0-2 = SHAPE（0-5），bit3 = POWERED | 无弯角 | BlockRailDetector.java:184,192 |
| BlockRedstoneWire | meta = POWER 0-15 | 信号强度 | BlockRedstoneWire.java:475,483 |
| BlockRedstoneRepeater | bit0-1 = FACING.getHorizontalIndex()，bit2-3 = DELAY-1；LOCKED 不落盘 | 延迟档位 | BlockRedstoneRepeater.java:136,144 |
| BlockRedstoneComparator | bit0-1 = FACING，bit2 = MODE==SUBTRACT，bit3 = POWERED | 输出值另存 TileEntity | BlockRedstoneComparator.java:277,285 |
| BlockSapling | bit0-2 = TYPE，bit3 = STAGE | 两段生长 | BlockSapling.java:249,257 |
| BlockSnow | meta = LAYERS-1（0-7） | 雪层数 | BlockSnow.java:151,167 |
| BlockStairs | bit0-1 经 `5 - FACING.getIndex()` 映射，bit2 = HALF==TOP；SHAPE 不落盘 | 非常规编码 | BlockStairs.java:726,736 |
| BlockTrapDoor | bit0-1 = 自定义 facing 表（0=N,1=S,2=W,3=E），bit2 = OPEN，bit3 = HALF==TOP | 与 EnumFacing 索引不同 | BlockTrapDoor.java:208,227,259 |
| BlockTripWire | bit0 = POWERED，bit1 = SUSPENDED，bit2 = ATTACHED，bit3 = DISARMED；四向连接不落盘 | 绊线状态 | BlockTripWire.java:261,269 |
| BlockTripWireHook | bit0-1 = FACING.getHorizontalIndex()，bit2 = ATTACHED，bit3 = POWERED；SUSPENDED 不落盘 | 注意 bit0/bit3 与绊线本体含义不同（bit2=ATTACHED 两者相同；绊线本体 POWERED 在 bit0、bit3 是 DISARMED） | BlockTripWireHook.java:343,351 |
| BlockTorch | meta 1=EAST,2=WEST,3=SOUTH,4=NORTH,5=UP（switch 硬编码） | 五向依附 | BlockTorch.java:244,277 |
| BlockPortal | meta 1=X,2=Z（`getMetaForAxis`） | 门轴向 | BlockPortal.java:85,245,253 |

2) NBT：`BlockSkull.breakBlock` 掉落玩家头时构造 `NBTTagCompound`，经 `NBTUtil.writeGameProfile(nbttagcompound, tileentityskull.getPlayerProfile())` 写入并挂到物品 `"SkullOwner"` 标签（BlockSkull.java:174-177）。

3) block event（`S24PacketBlockAction` 载荷即 eventID/eventParam）：BlockNote 用 eventID=乐器索引、eventParam=音高半音数（`(float)Math.pow(2.0D, (double)(eventParam - 12) / 12.0D)`，BlockNote.java:110）；BlockPistonBase 用 eventID 0/1=伸/缩、eventParam=facing index；BlockRedstoneComparator 把 event 转发给 `tileentity.receiveClientEvent(eventID, eventParam)`（BlockRedstoneComparator.java:259-263）。

## 不变量与陷阱

- Block 是全局单例，`minX..maxZ` 包围盒是实例可变字段。`setBlockBoundsBasedOnState` → 读包围盒必须在同一线程内连续完成（BlockPane、BlockSnow、BlockStairs、BlockTrapDoor、BlockTorch 都依赖这个时序）。任何并发/异步读 Block 包围盒都会读到别处刚设置的值。
- 同理 BlockStairs 的 `hasRaytraced`/`rayTracePass`（BlockStairs.java:36-37）与 BlockRedstoneWire 的 `canProvidePower`/`blocksNeedingUpdate`（BlockRedstoneWire.java:35-36）都是"借实例字段当局部变量"的写法，严格主线程。
- `BlockRedstoneTorch.toggles` 是 `private static Map<World, List<BlockRedstoneTorch.Toggle>>`（BlockRedstoneTorch.java:20），以 World 为键且从不移除条目——World 卸载后仍持引用，长会话反复进出存档会缓慢泄漏；列表清理只在 `updateTick` 中按时间戳做（BlockRedstoneTorch.java:117-120）。
- 双实例模式：redstone_lamp/lit_redstone_lamp、redstone_ore/lit_redstone_ore、redstone_torch/unlit_redstone_torch、powered/unpowered repeater 与 comparator 都是"同类两个 Block 实例互相 setBlockState 切换"。判断"这是不是中继器"必须用 `isAssociated`/`isAssociatedBlock`（BlockRedstoneDiode.java:258,281），不能用 `==`。
- `BlockOre.getItemDropped`（BlockOre.java:32）等大量逻辑用 `this == Blocks.coal_ore` 分支，意味着 Blocks 注册表未初始化前这些方法行为未定义；单元测试需先跑 Bootstrap。
- meta 编码陷阱：BlockStairs 的 `5 - FACING.getIndex()`、BlockTrapDoor 的私有 facing 表、BlockTripWireHook 与 BlockTripWire 的位布局不同（bit2=ATTACHED 相同，但 POWERED 分别在 bit3 与 bit0，TripWire 的 bit3 是 DISARMED）——写世界编辑/协议工具时不能套用统一公式。
- `getActualState` 派生属性（Pane/RedstoneWire/TripWire/Stairs SHAPE/Mycelium SNOWY/Repeater LOCKED/Stem FACING）不进 meta，只有渲染和 `getActualState` 调用者可见；比较两个位置状态是否相同要先决定用哪一层。
- 活塞：`canPush` 拒绝一切 `ITileEntityProvider`（BlockPistonBase.java:363）；`doMove` 中占位方块用 flag 4（不触发渲染更新）放置，依赖后续统一 notify——插入额外 setBlockState 会打乱顺序产生幽灵方块。
- 移植相关：BlockTripWireHook 使用 `import com.google.common.base.MoreObjects`（BlockTripWireHook.java:3）与 `MoreObjects.firstNonNull`（BlockTripWireHook.java:164），这是 Guava 升级后的改动（原版 1.8.9 为 `com.google.common.base.Objects.firstNonNull`），本仓库与原版 MCP 源不逐字一致的实例。JDK25 下本桶无反射/Unsafe 用法，注意点仅是 `@SuppressWarnings("incomplete-switch")` 出现在 javadoc 之前的非常规位置（BlockNewLog.java:94、BlockOldLog.java:102）。
- `BlockPortal.updateTick` 硬编码实体 ID 57（僵尸猪人）刷怪（BlockPortal.java:51）；改动实体注册表会静默破坏此逻辑。
- 大量方法有 `!worldIn.isRemote` 保护（生长、红石、活塞调度），但也有双端执行的（BlockTrapDoor.onBlockActivated 的开关、BlockRedstoneComparator.onBlockActivated 的模式切换、piston 的 onBlockEventReceived）；给客户端加预测逻辑时要先确认该方法属于哪一类。

## 交叉引用

- net.minecraft.world → `World#setBlockState` / `World#notifyNeighborsOfStateChange` / `World#addBlockEvent` / `World#scheduleUpdate` / `World#updateBlockTick`（全桶写世界与调度的唯一通道）
- net.minecraft.world → `World#doesBlockHaveSolidTopSurface`（Rail/Diode/TripWire/Torch/Pumpkin 的支撑检查）
- net.minecraft.client.multiplayer → `WorldClient#doVoidFogParticles`（WorldClient.java:319 调 `Block#randomDisplayTick`）
- net.minecraft.client.network → `NetHandlerPlayClient#handleBlockAction`（S24 → `Block#onBlockEventReceived`）
- net.minecraft.init → `Blocks` / `Items`（注册表单例比较与掉落物）
- net.minecraft.tileentity → `TileEntityNote#triggerNote`、`TileEntityPiston#clearPistonTileEntity`/`getProgress`、`TileEntitySkull#getSkullType`/`getPlayerProfile`、`TileEntityComparator#getOutputSignal`/`setOutputSignal`、`TileEntitySign#executeCommand`、`TileEntityMobSpawner`
- net.minecraft.block.state → `BlockPistonStructureHelper#canMove`/`getBlocksToMove`（BlockPistonBase）、`BlockState`/`IBlockState#withProperty`
- net.minecraft.block.state.pattern → `FactoryBlockPattern#start` / `BlockPattern#match`（BlockPumpkin 傀儡、BlockSkull 凋灵、BlockPortal#func_181089_f）
- net.minecraft.world.gen.feature → `WorldGenBigMushroom`、`WorldGenBigTree`/`WorldGenTrees`/`WorldGenMegaPineTree`/`WorldGenMegaJungle`/`WorldGenCanopyTree`/`WorldGenSavannaTree`/`WorldGenForest`/`WorldGenTaiga2`（BlockMushroom/BlockSapling 生长）
- net.minecraft.entity → `Entity#setPortal`（BlockPortal）、`EntityTNTPrimed`（BlockTNT）、`EntitySnowman`/`EntityIronGolem`（BlockPumpkin）、`EntityWither`（BlockSkull）、`EntitySilverfish`（BlockSilverfish）、`EntityMinecart`/`EntityMinecartCommandBlock`（BlockRailDetector）、`EntityItemFrame#func_174866_q`（BlockRedstoneComparator 读展示框）
- net.minecraft.item → `ItemDye`（经 `IGrowable` 接口对 BlockMushroom/BlockSapling/BlockStem/BlockTallGrass 施骨粉，ItemDye.java:107）、`ItemSkull`（ItemSkull.java:110 调 `checkWitherSpawn`）、`ItemMonsterPlacer#spawnCreature`（BlockPortal 刷猪人）
- net.minecraft.block（本包其它桶）→ `BlockFire`（BlockFire.java:384 调 `Blocks.portal.func_176548_d` 点燃传送门）、`BlockBeacon#updateColorAsync`（染色玻璃增删时）、`BlockCrops#getGrowthChance`（BlockStem 生长速率）、`BlockDoublePlant#placeAt`（BlockTallGrass 骨粉）
- net.minecraft.dispenser → `IBlockSource`（BlockSourceImpl 实现；`Bootstrap` 中发射器行为经它放置南瓜/头颅，Bootstrap.java:439）
- net.minecraft.nbt → `NBTUtil#writeGameProfile`（BlockSkull 玩家头掉落）
- net.minecraft.inventory → `Container#calcRedstoneFromInventory`（BlockRailDetector comparator override）
- net.minecraft.world → `ColorizerFoliage` / `ColorizerGrass` / `BiomeGenBase#getGrassColorAtPos`（BlockOldLeaf/BlockTallGrass/BlockReed 的 colorMultiplier）

## 覆盖声明

- 完整读取了 71/71 个文件（本桶全部文件均通过 Read 全文读取，无抽样）。
- 逐行精读：BlockPistonBase、BlockPistonExtension、BlockPistonMoving、BlockRedstoneWire、BlockRedstoneDiode、BlockRedstoneComparator、BlockRedstoneRepeater、BlockRedstoneTorch、BlockRailBase、BlockRailPowered、BlockRailDetector、BlockPortal、BlockStairs、BlockTripWire、BlockTripWireHook、BlockSkull、BlockPumpkin、BlockSapling、BlockNote、BlockTNT、BlockSponge、BlockTorch、BlockTrapDoor。
- 结构性浏览（读全文但仅按"属性 + meta 编码 + 少量覆写"模式归纳）：各变种/装饰类——BlockNetherBrick、BlockNetherrack、BlockObsidian、BlockPackedIce、BlockPlanks、BlockPrismarine、BlockQuartz、BlockRedFlower、BlockRedSandstone、BlockRotatedPillar、BlockSand、BlockSandStone、BlockSeaLantern、BlockSilverfish、BlockSnowBlock、BlockSoulSand、BlockSourceImpl、BlockStainedGlass、BlockStainedGlassPane、BlockStandingSign、BlockStone、BlockStoneBrick、BlockStoneSlab、BlockStoneSlabNew、BlockTallGrass、BlockPotato、BlockPressurePlate、BlockPressurePlateWeighted、BlockMobSpawner、BlockMushroom、BlockMycelium、BlockNetherWart、BlockNewLeaf、BlockNewLog、BlockOldLeaf、BlockOldLog、BlockOre、BlockPane、BlockRail、BlockRedstoneLight、BlockRedstoneOre、BlockReed、BlockSign、BlockSlab、BlockSlime、BlockSnow、BlockStaticLiquid、BlockStem。
- 行号引用均来自本次 Read 输出；未验证运行期行为（如 WorldServer 随机 tick 采样细节属于其它桶的文件，本文仅引用已确认的调用点）。
