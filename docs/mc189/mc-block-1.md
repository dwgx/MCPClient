---
area: net/minecraft/block#1
slug: mc-block-1
files: 75
lines: 13436
tier: B
---

# net/minecraft/block（第 1 桶：Block 基类 + A–M 段方块）

## 定位

本桶覆盖 `net.minecraft.block` 包中按字母序前半部分的 75 个文件，核心是整个方块系统的根类 `Block`（1553 行），以及大量具体方块子类（A 到 M：Air/Anvil/Banner … Log/Melon）。

职责三块：

1. **注册表**。`Block.blockRegistry`（`RegistryNamespacedDefaultedByKey<ResourceLocation, Block>`）与 `Block.BLOCK_STATE_IDS`（`ObjectIntIdentityMap<IBlockState>`）是全游戏 block id ↔ 实例 ↔ 状态 id 的唯一权威映射。`Block.registerBlocks()`（Block.java:1249）在 `Bootstrap.register()`（Bootstrap.java:517）中被调用，硬编码注册 id 0–197 全部原版方块；紧接着 `BlockFire.init()`（Bootstrap.java:518）填充可燃性表。区块序列化、网络封包中的方块数据（`getStateId`/`getStateById`）全部依赖这两张表。
2. **方块行为多态基座**。`Block` 定义了世界逻辑（tick、放置、破坏、掉落、红石、碰撞、光照、ray trace）与渲染元数据（`getRenderType`、`getBlockLayer`、`isOpaqueCube`、`colorMultiplier`）的全部虚方法；`world`、`entity`、`client.renderer`、`client.multiplayer.PlayerControllerMP` 等包通过这些虚方法驱动一切与方块相关的游戏行为。
3. **具体方块实现**。容器类（Chest/Furnace/Dispenser/Hopper/Beacon…，均继承 `BlockContainer` 并创建 TileEntity）、红石输入输出类（Button/Lever/PressurePlate/DaylightDetector）、植物类（Bush/Crops/Flower/DoublePlant/Leaves）、流体（BlockLiquid/BlockDynamicLiquid）、火（BlockFire）等。

谁调用它：`WorldServer`（updateTick/randomTick/fillWithRain）、`WorldClient`（randomDisplayTick）、`World`（onNeighborBlockChange、addBlockEvent 分发）、`Entity`（onEntityCollidedWithBlock、modifyAcceleration）、`PlayerControllerMP`（onBlockActivated/onBlockClicked）、`ItemBlock`（onBlockPlaced/onBlockPlacedBy）、渲染管线（chunk rebuild 时的 shouldSideBeRendered/getActualState/colorMultiplier）。它调用谁：`World` 的 setBlockState/scheduleUpdate/notifyNeighborsOfStateChange、`tileentity` 包的各 TileEntity、`item`/`init`（Blocks、Items 静态表）。

如果本桶消失：方块注册表不存在，`Blocks` 类初始化失败，世界无法反序列化，客户端在 `Minecraft.startGame` 的 `Bootstrap.register()` 处直接崩溃。它是客户端最底层的内容包之一。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| Block | 1553 | （根类） | 方块基类；注册表、声音类型、边界/碰撞/ray trace、掉落、红石、tick 等全部虚方法与 `registerBlocks()` 静态注册 |
| BlockAir | 56 | extends Block | 空气；无碰撞、无渲染（getRenderType()=-1）、可被替换 |
| BlockAnvil | 190 | extends BlockFalling | 铁砧；会下落、砸伤实体，右键打开 `ContainerRepair`（内部类 Anvil implements IInteractionObject） |
| BlockBanner | 256 | extends BlockContainer | 旗帜基类；掉落物携带 `BlockEntityTag` NBT；含内部类 BlockBannerStanding / BlockBannerHanging |
| BlockBarrier | 49 | extends Block | 屏障；不可破坏、不渲染、不掉落 |
| BlockBasePressurePlate | 245 | extends Block（abstract） | 压力板基类；实体碰撞触发红石，20 tick 轮询关断 |
| BlockBeacon | 156 | extends BlockContainer | 信标；打开 GUI、邻居变化触发 updateBeacon + addBlockEvent；含异步 updateColorAsync |
| BlockBed | 336 | extends BlockDirectional | 床；trySleep 入口、爆炸（地狱）、HEAD/FOOT 双方块联动 |
| BlockBookshelf | 33 | extends Block | 书架；掉落 3 本书 |
| BlockBreakable | 54 | extends Block | 玻璃/冰等"同类相邻不渲染面"的基类 |
| BlockBrewingStand | 216 | extends BlockContainer | 酿造台；HAS_BOTTLE[3] 属性、GUI、比较器输出 |
| BlockBush | 98 | extends Block | 植物基类；只能放在 grass/dirt/farmland 上，站不住就掉落 |
| BlockButton | 386 | extends Block（abstract） | 按钮基类；按下供电 15、定时弹回（木 30/石 20 tick）、木按钮响应箭 |
| BlockButtonStone | 9 | extends BlockButton | 石按钮（wooden=false） |
| BlockButtonWood | 9 | extends BlockButton | 木按钮（wooden=true） |
| BlockCactus | 151 | extends Block | 仙人掌；生长（AGE 0–15）、碰撞伤害、邻边实心即自毁 |
| BlockCake | 182 | extends Block | 蛋糕；BITES 0–6，左键/右键都吃 |
| BlockCarpet | 151 | extends Block | 地毯；1/16 高，下方为空气即掉落 |
| BlockCarrot | 17 | extends BlockCrops | 胡萝卜；种子和作物都是 Items.carrot |
| BlockCauldron | 283 | extends Block | 炼药锅；LEVEL 0–3 水位、灭火、洗皮甲/旗帜、装瓶 |
| BlockChest | 609 | extends BlockContainer | 箱子（chestType 0 普通/1 陷阱）；双箱合并、朝向修正、被堵检测、陷阱箱供电 |
| BlockClay | 33 | extends Block | 黏土块；掉 4 个 clay_ball |
| BlockCocoa | 229 | extends BlockDirectional implements IGrowable | 可可豆；挂在丛林原木上，AGE 0–2 |
| BlockColored | 74 | extends Block | 羊毛/染色硬化黏土；COLOR=EnumDyeColor 16 色 |
| BlockCommandBlock | 171 | extends BlockContainer | 命令方块；红石上升沿 schedule 1 tick 后 trigger CommandBlockLogic |
| BlockCompressedPowered | 29 | extends Block | 红石块；恒定 weak power 15 |
| BlockContainer | 57 | extends Block implements ITileEntityProvider（abstract） | 带 TileEntity 方块的基类；breakBlock 时 removeTileEntity，转发 block event 给 TileEntity |
| BlockCrops | 220 | extends BlockBush implements IGrowable | 小麦类作物；AGE 0–7，光照≥9 按耕地湿度概率生长 |
| BlockDaylightDetector | 186 | extends BlockContainer | 阳光传感器（inverted 两个实例）；updatePower 由 TileEntity 驱动 |
| BlockDeadBush | 70 | extends BlockBush | 枯灌木；沙/硬化黏土/泥土上生存，剪刀收割 |
| BlockDirectional | 21 | extends Block（abstract） | 提供水平 FACING 属性的基类 |
| BlockDirt | 178 | extends Block | 泥土；VARIANT（DIRT/COARSE_DIRT/PODZOL）+SNOWY |
| BlockDispenser | 303 | extends BlockContainer | 发射器；dispenseBehaviorRegistry 行为分发、红石上升沿 4 tick 后 dispense |
| BlockDoor | 477 | extends Block | 门；上下半块 5 属性联动，铁门只认红石 |
| BlockDoublePlant | 405 | extends BlockBush implements IGrowable | 双格植物（向日葵等 6 种）；上下半块联动、剪刀收割 |
| BlockDoubleStoneSlab | 9 | extends BlockStoneSlab | 双石台阶（isDouble()=true） |
| BlockDoubleStoneSlabNew | 9 | extends BlockStoneSlabNew | 双红砂岩台阶 |
| BlockDoubleWoodSlab | 9 | extends BlockWoodSlab | 双木台阶 |
| BlockDragonEgg | 149 | extends Block | 龙蛋；下落逻辑（复制 BlockFalling）+ 点击随机传送 |
| BlockDropper | 89 | extends BlockDispenser | 投掷器；不用行为注册表，优先塞入朝向的 IInventory |
| BlockDynamicLiquid | 293 | extends BlockLiquid | 流动液体；扩散/回缩、找最短下落路径、遇水变石 |
| BlockEnchantmentTable | 128 | extends BlockContainer | 附魔台；书架粒子、displayGui(TileEntityEnchantmentTable) |
| BlockEndPortal | 108 | extends BlockContainer | 末地传送门；碰撞即 travelToDimension(1)，无碰撞箱 |
| BlockEndPortalFrame | 119 | extends Block | 末地门框；EYE 属性、比较器输出 15/0 |
| BlockEnderChest | 175 | extends BlockContainer | 末影箱；打开玩家的 InventoryEnderChest，掉落 8 黑曜石 |
| BlockEventData | 62 | （POJO） | World.addBlockEvent 的事件记录（pos、block、eventID、eventParameter），WorldServer 队列去重用 equals |
| BlockFalling | 103 | extends Block | 沙/砾石基类；2 tick 后检查下落，生成 EntityFallingBlock |
| BlockFarmland | 177 | extends Block | 耕地；MOISTURE 0–7，无水退干、被踩/被压变泥土 |
| BlockFence | 198 | extends Block | 栅栏；四向 connect getActualState、1.5 高碰撞箱、右键拴绳 |
| BlockFenceGate | 197 | extends BlockDirectional | 栅栏门；OPEN/POWERED/IN_WALL，红石开合 |
| BlockFire | 506 | extends Block | 火；蔓延/熄灭主循环、encouragements/flammabilities 两张表、static init() |
| BlockFlower | 196 | extends BlockBush（abstract） | 花基类；EnumFlowerColor(YELLOW/RED)×EnumFlowerType 元数据映射 |
| BlockFlowerPot | 497 | extends BlockContainer | 花盆；CONTENTS 由 TileEntityFlowerPot 反推（getActualState），右键插花 |
| BlockFurnace | 272 | extends BlockContainer | 熔炉（isBurning 两个实例）；setState 静态换块保 TileEntity（keepInventory 标志） |
| BlockGlass | 38 | extends BlockBreakable | 玻璃；不掉落、可精准采集 |
| BlockGlowstone | 51 | extends Block | 萤石；掉 2–4 荧石粉（fortune 上限 4） |
| BlockGrass | 174 | extends Block implements IGrowable | 草方块；蔓延/退化、骨粉长草与花、biome 染色 |
| BlockGravel | 31 | extends BlockFalling | 砾石；10% 概率掉燧石（受 fortune 影响） |
| BlockHalfStoneSlab | 9 | extends BlockStoneSlab | 单石台阶（isDouble()=false） |
| BlockHalfStoneSlabNew | 9 | extends BlockStoneSlabNew | 单红砂岩台阶 |
| BlockHalfWoodSlab | 9 | extends BlockWoodSlab | 单木台阶 |
| BlockHardenedClay | 23 | extends Block | 硬化黏土；只改 MapColor |
| BlockHay | 83 | extends BlockRotatedPillar | 干草块；AXIS 三轴朝向 |
| BlockHopper | 253 | extends BlockContainer | 漏斗；ENABLED=!isBlockPowered，比较器输出，分段碰撞箱 |
| BlockHugeMushroom | 160 | extends Block | 巨型蘑菇块；EnumType 13 种面组合，掉小蘑菇 |
| BlockIce | 94 | extends BlockBreakable | 冰；slipperiness=0.98，光照>11-opacity 融化成水，采集特殊处理 |
| BlockJukebox | 205 | extends BlockContainer | 唱片机；HAS_RECORD、内部类 TileEntityJukebox 存 ItemStack（NBT "RecordItem"） |
| BlockLadder | 164 | extends Block | 梯子；FACING 贴墙，墙没了就掉落 |
| BlockLeaves | 304 | extends BlockLeavesBase（abstract） | 树叶；DECAYABLE/CHECK_DECAY 腐烂 BFS（32^3 缓存数组）、fancy/fast 切换 |
| BlockLeavesBase | 30 | extends Block | 树叶渲染基类；fancyGraphics 控制同类面剔除 |
| BlockLever | 380 | extends Block | 拉杆；EnumOrientation 8 朝向、翻转供电 15 |
| BlockLilyPad | 84 | extends BlockBush | 睡莲；只能放在静水（LEVEL=0）上，船可穿过 |
| BlockLiquid | 410 | extends Block（abstract） | 液体基类；LEVEL 0–15、流向矢量、亮度混合、水岩浆混合成石 |
| BlockLog | 95 | extends BlockRotatedPillar（abstract） | 原木基类；LOG_AXIS、breakBlock 标记周围 4 格树叶 CHECK_DECAY |
| BlockMelon | 42 | extends Block | 西瓜；掉 3–7 片（上限 9） |

（注：BlockStaticLiquid、BlockSlab、BlockStone 等 N–Z 段类不在本桶。）

## 核心类详解

### Block（Block.java）

全局静态：
- `public static final RegistryNamespacedDefaultedByKey<ResourceLocation, Block> blockRegistry`（Block.java:40），默认键 `"air"`。
- `public static final ObjectIntIdentityMap<IBlockState> BLOCK_STATE_IDS`（Block.java:41），在 `registerBlocks()` 末尾（Block.java:1488-1495）以 `id << 4 | meta` 填充；这是网络与区块存储的 state id 编码。

关键实例字段（Block.java:100-150）：`protected boolean fullBlock`、`protected int lightOpacity`、`protected boolean translucent`、`protected int lightValue`、`protected boolean useNeighborBrightness`、`protected float blockHardness`、`protected float blockResistance`、`protected boolean needsRandomTick`、`protected boolean isBlockContainer`、`protected double minX..maxZ`（可变共享边界，见陷阱）、`public Block.SoundType stepSound`、`protected final Material blockMaterial`、`public float slipperiness`、`protected final BlockState blockState`、`private IBlockState defaultBlockState`。

关键方法（签名逐字）：
- `public static int getStateId(IBlockState state)`（Block.java:160）/ `public static IBlockState getStateById(int id)`（Block.java:174）— id = blockId + (meta << 12)。
- `public IBlockState getStateFromMeta(int meta)`（Block.java:257）与 `public int getMetaFromState(IBlockState state)`（Block.java:265）— 元数据↔状态双向转换，几乎每个子类都重写；基类对含属性的状态直接 `throw new IllegalArgumentException`。
- `public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos)`（Block.java:281）— 渲染前补全不入 meta 的属性（栅栏连接等）。
- `public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)`（Block.java:533）与 `public void randomTick(World worldIn, BlockPos pos, IBlockState state, Random random)`（Block.java:528，默认转发 updateTick）— 计划 tick / 随机 tick 入口。
- `public MovingObjectPosition collisionRayTrace(World worldIn, BlockPos pos, Vec3 start, Vec3 end)`（Block.java:681）— 逐面求交；被 `World.rayTraceBlocks`（World.java:874 起）调用，是准星选块的最终判定。
- `public void harvestBlock(World worldIn, EntityPlayer player, BlockPos pos, IBlockState state, TileEntity te)`（Block.java:985）— 统计、饱食度、精准采集/时运掉落。
- `public static void spawnAsEntity(World worldIn, BlockPos pos, ItemStack stack)`（Block.java:631）— 服务端且 gamerule doTileDrops 时生成 EntityItem。
- `public static void registerBlocks()`（Block.java:1249）— 注册后两个循环：为每个方块计算 `useNeighborBrightness`（Block.java:1464-1486，stairs/slab/farmland/translucent/lightOpacity==0 为 true），再填充 BLOCK_STATE_IDS。
- 内部类 `public static class SoundType`（Block.java:1515）：字段 `public final String soundName; public final float volume; public final float frequency;`，方法 `getBreakSound()`=`"dig."+soundName`、`getStepSound()`=`"step."+soundName`、`getPlaceSound()` 默认同 break。注意 `soundTypeGlass`/`soundTypeAnvil`/`SLIME_SOUND` 是匿名子类重写（Block.java:53-99）。

### BlockContainer（BlockContainer.java）

`public abstract class BlockContainer extends Block implements ITileEntityProvider`（BlockContainer.java:11）。构造器置 `this.isBlockContainer = true`（BlockContainer.java:21）。`public int getRenderType()` 返回 -1（BlockContainer.java:37，本身不走方块模型；BlockChest/BlockEnderChest 覆盖为 2 走 TESR）。`public void breakBlock(World worldIn, BlockPos pos, IBlockState state)`（BlockContainer.java:42）额外 `worldIn.removeTileEntity(pos)`。`public boolean onBlockEventReceived(World worldIn, BlockPos pos, IBlockState state, int eventID, int eventParam)`（BlockContainer.java:51）转发到 `tileentity.receiveClientEvent(eventID, eventParam)` — 这是 chest 开盖动画等客户端事件的通道，服务端从 `WorldServer.java:1063` 分发，客户端从 `World.java:3483` 分发。

### BlockFire（BlockFire.java）

属性：`AGE`(0–15)、`FLIP`、`ALT`、`NORTH/EAST/SOUTH/WEST`、`UPPER`(0–2)（BlockFire.java:25-32）；后七个仅由 `getActualState`（BlockFire.java:40）按邻居可燃性现算，不入 meta。两张表 `private final Map<Block, Integer> encouragements / flammabilities`（BlockFire.java:33-34）由 `public static void init()`（BlockFire.java:72）在 Bootstrap 里通过 `setFireInfo(Block blockIn, int encouragement, int flammability)`（BlockFire.java:111）填充。`public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)`（BlockFire.java:151）：受 gamerule `"doFireTick"` 保护；`tickRate=30`（BlockFire.java:146）+ 随机 0–9 再排程；对 6 邻 `catchOnFire`（概率 300/250 - 湿度修正），再对 3×3×6 区域按 `getNeighborEncouragement` 与难度扩散。TNT 被烧掉时特判调用 `Blocks.tnt.onBlockDestroyedByPlayer(..., iblockstate.withProperty(BlockTNT.EXPLODE, Boolean.valueOf(true)))`（BlockFire.java:313）。`onBlockAdded`（BlockFire.java:382）先探测下界传送门骨架 `Blocks.portal.func_176548_d(worldIn, pos)`。

### BlockLiquid / BlockDynamicLiquid

`public abstract class BlockLiquid extends Block`（BlockLiquid.java:23），唯一属性 `public static final PropertyInteger LEVEL = PropertyInteger.create("level", 0, 15)`（BlockLiquid.java:25）。要点：
- `protected Vec3 getFlowVector(IBlockAccess worldIn, BlockPos pos)`（BlockLiquid.java:150）→ `public Vec3 modifyAcceleration(World worldIn, BlockPos pos, Entity entityIn, Vec3 motion)`（BlockLiquid.java:197），由 `World.handleMaterialAcceleration`（World.java:2113）调用推动实体。
- `public int tickRate(World worldIn)`（BlockLiquid.java:205）：水 5、岩浆 30（无天空维度 10）。
- `public boolean checkForMixing(World worldIn, BlockPos pos, IBlockState state)`（BlockLiquid.java:307）：岩浆邻水 → level 0 变 obsidian、≤4 变 cobblestone。
- `public int getMixedBrightnessForBlock(IBlockAccess worldIn, BlockPos pos)`（BlockLiquid.java:210）取本格与上格光照最大值 — 渲染水面亮度用。
- 静态映射 `getFlowingBlock(Material)`/`getStaticBlock(Material)`（BlockLiquid.java:379/395），非法材质抛 `IllegalArgumentException`。

`BlockDynamicLiquid.updateTick`（BlockDynamicLiquid.java:27）是流体扩散主算法：`checkAdjacentBlock` 统计 `adjacentSourceBlocks`（≥2 源且水 → 生成新源，BlockDynamicLiquid.java:70-82）；向下可流则 `tryFlowInto(..., i >= 8 ? i : i + 8)`（下落位 +8）；否则 `getPossibleFlowDirections`（BlockDynamicLiquid.java:211）用 `func_176374_a` 递归 4 格找最短落差路径。静止化：`placeStaticBlock`（BlockDynamicLiquid.java:22）换成 BlockStaticLiquid 同 LEVEL。

### BlockChest（BlockChest.java）

`public final int chestType`（BlockChest.java:34；0 普通 / 1 陷阱）。双箱逻辑：`onBlockAdded`（BlockChest.java:90）与 `checkForSurroundingChests`（BlockChest.java:176，服务端专用，`worldIn.isRemote` 直接返回）统一两半朝向；`canPlaceBlockAt`（BlockChest.java:329）禁止三连箱。打开：`public boolean onBlockActivated(...)`（BlockChest.java:427）→ `getLockableContainer`（BlockChest.java:455）拼装 `InventoryLargeChest`，被 `isBlocked`（上方 isNormalCube 或坐着的豹猫，BlockChest.java:547-570）拦截时返回 null。陷阱箱红石：`getWeakPower`（BlockChest.java:522）返回 `TileEntityChest.numPlayersUsing` clamp 0–15，`getStrongPower` 仅 `EnumFacing.UP`（BlockChest.java:542）。

### BlockButton / BlockLever / BlockBasePressurePlate（红石输入三件套）

- `BlockButton`（abstract，BlockButton.java:22）：`FACING`（全 6 向）+`POWERED`。`onBlockActivated`（BlockButton.java:167）置 POWERED、播 `"random.click"`、`notifyNeighbors`（自身与背面，BlockButton.java:297）、`scheduleUpdate(pos, this, this.tickRate(worldIn))`；`updateTick`（BlockButton.java:219）服务端弹回（木按钮改查箭 `checkForArrows`，BlockButton.java:268）。`getStrongPower` 只在朝向面给 15（BlockButton.java:199）。静态吸附判定 `protected static boolean func_181088_a(World p_181088_0_, BlockPos p_181088_1_, EnumFacing p_181088_2_)`（BlockButton.java:84）被 BlockLever 复用（BlockLever.java:70-73）。
- `BlockLever`：属性 `FACING` 为 `BlockLever.EnumOrientation`（8 值含 UP_X/UP_Z/DOWN_X/DOWN_Z，BlockLever.java:273）。`onBlockActivated`（BlockLever.java:196）服务端 `state.cycleProperty(POWERED)` 后双向 notify。
- `BlockBasePressurePlate`（abstract）：三个抽象钩子 `protected abstract int computeRedstoneStrength(World worldIn, BlockPos pos); protected abstract int getRedstoneStrength(IBlockState state); protected abstract IBlockState setRedstoneStrength(IBlockState state, int strength);`（BlockBasePressurePlate.java:240-244）。`onEntityCollidedWithBlock`（BlockBasePressurePlate.java:133）由 `Entity.doBlockCollisions`（Entity.java:971）触发 → `updateState`（BlockBasePressurePlate.java:149）→ 只要保持按下就 `scheduleUpdate` 20 tick 复查。

### BlockDispenser / BlockDropper

`public static final RegistryDefaulted<Item, IBehaviorDispenseItem> dispenseBehaviorRegistry = new RegistryDefaulted(new BehaviorDefaultDispenseItem())`（BlockDispenser.java:35）— 想给某物品自定义发射行为就往这张表注册。触发链：`onNeighborBlockChange`（BlockDispenser.java:157）检测 `worldIn.isBlockPowered(pos) || worldIn.isBlockPowered(pos.up())`（准连接性），上升沿 `scheduleUpdate(pos, this, this.tickRate(worldIn))`（4 tick）→ `updateTick`（BlockDispenser.java:173）服务端 `dispense`（BlockDispenser.java:122）。`BlockDropper` 覆盖 `protected void dispense(World worldIn, BlockPos pos)`（BlockDropper.java:32）：朝向若有 `IInventory` 则 `TileEntityHopper.putStackInInventoryAllSlots` 塞入而不是喷出。

### BlockDoor

5 属性（BlockDoor.java:28-32）：`FACING`、`OPEN`、`HINGE`（EnumHingePosition）、`POWERED`、`HALF`（EnumDoorHalf）。meta 只有 4 位，所以上半块存 HINGE/POWERED、下半块存 FACING/OPEN，运行时靠 `getActualState`（BlockDoor.java:340）与 `public static int combineMetadata(IBlockAccess worldIn, BlockPos pos)`（BlockDoor.java:295）拼合成 6 位组合。`onBlockActivated`（BlockDoor.java:156）铁门直接 return true（不可手开）；木门 `cycleProperty(OPEN)` 后 `worldIn.playAuxSFXAtEntity(playerIn, 1003/1006, pos, 0)`。`toggleDoor(World worldIn, BlockPos pos, boolean open)`（BlockDoor.java:182）是 AI/活塞外部开门入口。`onNeighborBlockChange`（BlockDoor.java:203）处理下半块缺失自毁与红石开合。

### BlockLeaves

腐烂算法在 `updateTick`（BlockLeaves.java:81）：CHECK_DECAY 且 DECAYABLE 时，以 `int[] surroundings`（32768 项懒分配，BlockLeaves.java:96-99）对 9×9×9 范围做 4 轮 BFS 找 log（Blocks.log/log2），找不到则 `destroy`。`public void setGraphicsLevel(boolean fancy)`（BlockLeaves.java:286）由 `RenderGlobal.loadRenderers`（RenderGlobal.java:487-488）在每次图形设置变化/资源重载时调用，切换 `isOpaqueCube()`（=!fancyGraphics）与 `getBlockLayer()`（CUTOUT_MIPPED/SOLID）。抽象方法 `public abstract BlockPlanks.EnumType getWoodType(int meta);`（BlockLeaves.java:303）。

### BlockFalling / BlockAnvil / BlockDragonEgg

`BlockFalling`：`public static boolean fallInstantly`（BlockFalling.java:14，世界生成期间置 true 直接落地不生成实体）。`onBlockAdded`/`onNeighborBlockChange` 都是 `worldIn.scheduleUpdate(pos, this, this.tickRate(worldIn))`（tickRate=2）；`checkFallable`（BlockFalling.java:48）服务端生成 `EntityFallingBlock` 前回调 `protected void onStartFalling(EntityFallingBlock fallingEntity)`（BlockFalling.java:81）— BlockAnvil 借此 `fallingEntity.setHurtEntities(true)`（BlockAnvil.java:107-110），落地回调 `public void onEndFalling(World worldIn, BlockPos pos)` 播 1022 音效（BlockAnvil.java:112）。`public static boolean canFallInto(World worldIn, BlockPos pos)`（BlockFalling.java:93）：fire/air/water/lava。BlockDragonEgg 不继承 BlockFalling 但复用其静态方法（BlockDragonEgg.java:44-48），点击 `teleport`（BlockDragonEgg.java:81）在 ±16/±8 内试 1000 次找空气格。

### BlockFlowerPot

`CONTENTS`（EnumFlowerType，22 值）纯属渲染态：`getActualState`（BlockFlowerPot.java:317）从 `TileEntityFlowerPot.getFlowerPotItem()/getFlowerPotData()` 反推枚举；meta 只存 `LEGACY_DATA`。`onBlockActivated`（BlockFlowerPot.java:95）验证 `canNotContain`（BlockFlowerPot.java:141，白名单：两种花/仙人掌/双蘑菇/树苗/枯灌木/蕨）后 `setFlowerPotData` + `markDirty` + `worldIn.markBlockForUpdate(pos)`。`createNewTileEntity(World worldIn, int meta)`（BlockFlowerPot.java:230）用旧版 meta 1–13 硬编码映射内容。

## 时序与生命周期

**初始化（一次性，主线程）**：`Minecraft.startGame` → `Bootstrap.register()`（Minecraft.java:397 → Bootstrap.java:517-518）→ `Block.registerBlocks()` 依 id 0–197 顺序构造并注册全部方块实例（含本桶注册的 fire=51、chest=54、door=64/71/193-197 等），随后统一计算 `useNeighborBrightness` 并填 `BLOCK_STATE_IDS`；紧接着 `BlockFire.init()` 填可燃表。此后 blockRegistry 只读。

**每 tick（集成服务端线程，单机时与客户端不同线程）**：
- `WorldServer.updateBlocks`：随机 tick — 每区块每 section 按 gamerule `randomTickSpeed` 抽 `block.randomTick(...)`（WorldServer.java:426），下雨时对随机柱顶 `fillWithRain`（WorldServer.java:399）。
- `WorldServer.tickUpdates`：到期的计划 tick 调 `iblockstate.getBlock().updateTick(this, nextticklistentry.position, iblockstate, this.rand)`（WorldServer.java:480）。BlockFire/BlockDynamicLiquid/BlockButton/BlockFalling 等的核心逻辑全在这里。
- Entity tick：`Entity.moveEntity` 末尾调 `block1.onEntityCollidedWithBlock(this.worldObj, blockpos, this)`（Entity.java:869，脚下版本），`doBlockCollisions` 调四参重载（Entity.java:971）；`World.handleMaterialAcceleration` 调 `modifyAcceleration`（World.java:2113）。
- 块事件：`World.addBlockEvent` → WorldServer 每 tick 排空队列，`onBlockEventReceived`（WorldServer.java:1063）成功才广播 `S24PacketBlockAction` 给客户端，客户端在收包后经 `World.java:3483` 再调一次。

**每帧/客户端 tick（主线程）**：
- `WorldClient.doVoidFogParticles` 每客户端 tick 对玩家周围 1000 个随机位置调 `randomDisplayTick`（WorldClient.java:319）— 火焰音效、熔炉/传送门粒子都在此。
- Chunk rebuild（chunk builder 线程）读取 `getActualState`、`shouldSideBeRendered`、`colorMultiplier`、`getBlockLayer`、`isOpaqueCube`；`setBlockBoundsBasedOnState` 会写共享字段（见陷阱）。
- 交互：`PlayerControllerMP.onPlayerRightClick` 在客户端主线程调 `onBlockActivated`（PlayerControllerMP.java:408），服务端在 `ItemInWorldManager` 再调一次。

**线程归属**：本包所有方法既可能在客户端主线程（isRemote=true 分支）也可能在服务端线程执行，靠 `worldIn.isRemote` 区分；唯一显式跨线程的是 `BlockBeacon.updateColorAsync`（BlockBeacon.java:117）— 提交到 `HttpUtil.field_180193_a` 线程池扫描，再用 `((WorldServer)worldIn).addScheduledTask(...)`（BlockBeacon.java:138）回主线程改状态。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumFacing side, float hitX, float hitY, float hitZ)` | Block.java:851 | 右键方块（PlayerControllerMP.java:408 客户端 + 服务端 ItemInWorldManager） | 拦截/伪造任何方块交互（自动开箱、ChestStealer、右键取消）；返回 true 会吞掉物品使用 | 客户端与服务端各调一次；很多子类在 isRemote 时直接 return true，实际逻辑在服务端 |
| `public void onBlockClicked(World worldIn, BlockPos pos, EntityPlayer playerIn)` | Block.java:872 | 左键开始挖掘（PlayerControllerMP.clickBlock） | 观察/接管左键交互（蛋糕、龙蛋、下界传送门点火判定） | 与挖掘状态机耦合，勿在此改世界状态 |
| `public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)` | Block.java:533 | 计划 tick 到期（WorldServer.java:480,609） | 改写火蔓延、液体流速、作物生长速率等任意方块调度逻辑 | 服务端线程；多数子类内部再 `scheduleUpdate` 自续，覆盖时保持链条 |
| `public void randomTick(World worldIn, BlockPos pos, IBlockState state, Random random)` | Block.java:528 | 随机 tick（WorldServer.java:426，按 randomTickSpeed） | 关停/加速随机行为（BlockButton/BlockBasePressurePlate 覆盖为空以免误触发） | 默认转发 updateTick，二者别双重计数 |
| `public void randomDisplayTick(World worldIn, BlockPos pos, IBlockState state, Random rand)` | Block.java:537 | 客户端每 tick 环境粒子采样（WorldClient.java:319） | 增删环境粒子与音效（火、熔炉、末影箱、酿造台） | 纯客户端；不得改世界状态 |
| `public void onEntityCollidedWithBlock(World worldIn, BlockPos pos, IBlockState state, Entity entityIn)` | Block.java:969 | 实体包围盒与方块相交（Entity.java:971） | 实现/屏蔽压力板触发、仙人掌伤害、末地门传送、炼药锅灭火 | 每 tick 每相交方块调用一次，代价敏感 |
| `public void onEntityCollidedWithBlock(World worldIn, BlockPos pos, Entity entityIn)` | Block.java:859 | 实体站在方块上（Entity.java:869） | 脚底方块效果（slime 弹跳类逻辑挂这里） | 与四参版本是两个不同钩子，别混 |
| `public Vec3 modifyAcceleration(World worldIn, BlockPos pos, Entity entityIn, Vec3 motion)` | Block.java:876 | `World.handleMaterialAcceleration`（World.java:2113） | 改水流推力（Velocity/水流免疫类功能的正统挂点） | 返回值直接进实体运动积分 |
| `public void onNeighborBlockChange(World worldIn, BlockPos pos, IBlockState state, Block neighborBlock)` | Block.java:551 | 邻块变更通知（World.notifyBlockOfStateChange） | 观察红石边沿（Dispenser/Door/FenceGate 全在此响应）、阻止依附方块自毁 | 高频；在此 setBlockState 易引发级联通知风暴 |
| `public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state)` / `public void breakBlock(World worldIn, BlockPos pos, IBlockState state)` | Block.java:563 / Block.java:567 | setBlockState 加块/移块时 | 方块生命周期监听；容器掉落物在 breakBlock（BlockChest.java:414 等） | breakBlock 时 TileEntity 尚在，BlockContainer.breakBlock 之后才移除 |
| `public boolean onBlockEventReceived(World worldIn, BlockPos pos, IBlockState state, int eventID, int eventParam)` | Block.java:1072 | `World.addBlockEvent` 分发（World.java:3483 客户端 / WorldServer.java:1063 服务端） | 监听箱子开合计数、信标刷新等 S24PacketBlockAction 事件 | 服务端返回 false 则不广播给客户端 |
| `public MovingObjectPosition collisionRayTrace(World worldIn, BlockPos pos, Vec3 start, Vec3 end)` | Block.java:681 | `World.rayTraceBlocks`（准星选块、投射物） | 改写命中判定（扩大/缩小可选中范围） | 依赖 setBlockBoundsBasedOnState 的共享 minX..maxZ，线程敏感 |
| `public void addCollisionBoxesToList(World worldIn, BlockPos pos, IBlockState state, AxisAlignedBB mask, List<AxisAlignedBB> list, Entity collidingEntity)` | Block.java:489 | 实体移动碰撞收集（World.getCollidingBoundingBoxes） | Phase/Jesus 类功能的碰撞改写点；BlockFenceGate/BlockCauldron 示范多箱拼装 | 直接影响物理；客户端预测与服务端要一致否则回弹 |
| `public AxisAlignedBB getCollisionBoundingBox(World worldIn, BlockPos pos, IBlockState state)` | Block.java:499 | 上述收集与实体逻辑 | 返回 null = 无碰撞（fire/button/lever/pressureplate 均如此） | null 语义特殊，勿返回零体积箱代替 |
| `public boolean shouldSideBeRendered(IBlockAccess worldIn, BlockPos pos, EnumFacing side)` | Block.java:468 | chunk mesh 重建（BlockModelRenderer） | X-Ray/剔除策略（注意参数 pos 是**邻块**坐标） | chunk builder 线程调用 |
| `public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos)` | Block.java:281 | 渲染取模型前、掉落判定 | 注入自定义渲染态（fence 连接、fire 形态、flower pot 内容） | 必须无副作用、可在工作线程执行 |
| `public int colorMultiplier(IBlockAccess worldIn, BlockPos pos, int renderPass)` | Block.java:943 | chunk 染色（草/叶/水 biome 色） | 改方块染色（自定义世界色调） | 返回 0xFFFFFF=16777215 为无染色 |
| `public float getPlayerRelativeBlockHardness(EntityPlayer playerIn, World worldIn, BlockPos pos)` | Block.java:590 | 每 tick 挖掘进度计算 | 挖掘速度显示/修改的读取点 | 除以 30（可采）或 100（不可采） |
| `public void harvestBlock(World worldIn, EntityPlayer player, BlockPos pos, IBlockState state, TileEntity te)` | Block.java:985 | 服务端确认破坏后 | 掉落改写（银触/时运在此分派） | te 参数是破坏前抓取的 TileEntity 快照 |
| `public boolean onBlockActivated`（BlockChest 版） | BlockChest.java:427 | 右键箱子 | 打开容器 GUI 的入口（`playerIn.displayGUIChest(ilockablecontainer)`） | 被堵（上方实心/豹猫）时 getLockableContainer 返回 null |
| `public void toggleDoor(World worldIn, BlockPos pos, boolean open)` | BlockDoor.java:182 | AI/外部逻辑开关门 | 程序化开门（自动门功能直接调它） | 内部会找 LOWER 半块并播 1003/1006 音效 |
| `public void setFireInfo(Block blockIn, int encouragement, int flammability)` | BlockFire.java:111 | Bootstrap 注册期 | 注册/修改任意方块可燃性 | identity map，须用注册表里的同一实例 |
| `dispenseBehaviorRegistry`（字段） | BlockDispenser.java:35 | `getBehavior(ItemStack)`（BlockDispenser.java:149） | 给物品注册自定义发射行为 | BlockDropper 绕过此表（BlockDropper.java:19-22） |
| `public void setGraphicsLevel(boolean fancy)` | BlockLeaves.java:286 | RenderGlobal.loadRenderers（RenderGlobal.java:487） | 强制树叶 fast/fancy（性能/视距功能） | 改后需触发 chunk 全量重建才生效 |
| `public static void registerBlocks()` | Block.java:1249 | Bootstrap.register()（Bootstrap.java:517） | 注册自定义方块的唯一时机（仿照 registerBlock 私有重载） | 必须在任何 Blocks 字段被触碰前完成；id 冲突即崩溃 |

## 数据与协议

本桶不直接收发封包，但定义了三类进入序列化/协议层的数据：

**1. Block/State ID 编码**（用于 S23PacketBlockChange、区块数据、`ObjectIntIdentityMap`）：

| 编码 | 组成 | 读 | 写 | 含义 |
|---|---|---|---|---|
| item/save state id | `getIdFromBlock(block) + (meta << 12)` | `Block.getStateById(int)`（Block.java:174） | `Block.getStateId(IBlockState)`（Block.java:160） | 低 12 位 blockId，高 4 位 meta |
| network/chunk state id | `blockRegistry.getIDForObject(block14) << 4 \| block14.getMetaFromState(iblockstate)` | `BLOCK_STATE_IDS.get(...)` | 填充于 Block.java:1492 | 高位 blockId，低 4 位 meta（与上者位序相反，勿混用） |

**2. meta 位打包**（各子类 `getStateFromMeta`/`getMetaFromState`，字段级）：

| 类 | 位布局 |
|---|---|
| BlockDoor（BlockDoor.java:367-404） | bit3=HALF(UPPER)；上半块：bit0=HINGE RIGHT、bit1=POWERED；下半块：bit0-1=FACING（`EnumFacing.getHorizontal(meta & 3).rotateYCCW()`）、bit2=OPEN |
| BlockBed（BlockBed.java:263-307） | bit0-1=FACING、bit3=PART HEAD、bit2=OCCUPIED（仅 HEAD 有效） |
| BlockButton（BlockButton.java:306-380） | bit0-2=朝向（0=DOWN,1=EAST,2=WEST,3=SOUTH,4=NORTH,5=UP）、bit3=POWERED |
| BlockLever（BlockLever.java:247-266） | bit0-2=EnumOrientation.getMetadata()（8 值）、bit3=POWERED |
| BlockDispenser（BlockDispenser.java:278-297） | bit0-2=FACING.getIndex()、bit3=TRIGGERED |
| BlockHopper（BlockHopper.java:228-247） | bit0-2=FACING、bit3=**!ENABLED**（存的是"被供电"，见 isEnabled，BlockHopper.java:205） |
| BlockAnvil（BlockAnvil.java:133-147） | bit0-1=FACING、bit2-3=DAMAGE(0–2) |
| BlockCocoa（BlockCocoa.java:209-223） | bit0-1=FACING、bit2-3=AGE(0–2) |
| BlockBrewingStand（BlockBrewingStand.java:182-210） | bit i = HAS_BOTTLE[i]（i=0..2） |
| BlockHay/BlockLog | bit2-3=轴：4=X、8=Z、0=Y（BlockHay.java:28-63） |
| BlockLiquid/BlockCauldron/BlockCake/BlockCactus/BlockCrops/BlockFire 等 | meta 即单一 Integer 属性（LEVEL/BITES/AGE…）原值 |

**3. NBT**：

| 所在 | 字段 | 类型 | 读/写 | 含义 |
|---|---|---|---|---|
| BlockJukebox.TileEntityJukebox | `"RecordItem"` | NBTTagCompound(ItemStack) | `readFromNBT`/`writeToNBT`（BlockJukebox.java:170-192） | 当前唱片；兼容旧版 int `"Record"`（读到 >0 时按 item id 恢复） |
| BlockBanner 掉落物 | `"BlockEntityTag"` | NBTTagCompound | `dropBlockAsItemWithChance`（BlockBanner.java:106-126）写 TileEntityBanner NBT 并 `removeTag("x"/"y"/"z"/"id")` | 旗帜颜色与图案随物品保留 |

**4. Block Event（S24PacketBlockAction 载荷）**：`BlockEventData`（BlockEventData.java:5）字段 `position`/`blockType`/`eventID`/`eventParameter`；本桶中 `BlockBeacon.onNeighborBlockChange` 发 `worldIn.addBlockEvent(pos, this, 1, 0)`（BlockBeacon.java:108）。

## 不变量与陷阱

- **共享可变边界**。`minX..maxZ` 是 Block 单例上的实例字段，`setBlockBoundsBasedOnState` / `setBlockBounds` 会全局改写（Block.java:441-449）。`getCollisionBoundingBox`、`collisionRayTrace` 都读它。同一 Block 实例被所有同类方块共享，**任何并发调用（chunk builder 线程 vs 主线程）都可能读到别人刚设置的边界**。BlockCauldron/BlockHopper/BlockFence 的 addCollisionBoxesToList 连续多次 setBlockBounds+super 调用（BlockCauldron.java:41-55），中途被抢占就产生错误碰撞箱。移植到多线程渲染时这是首要雷区。
- **Block 实例是单例**。`==` 比较合法且被广泛使用（`worldIn.getBlockState(pos).getBlock() == this`）；`BlockFire.encouragements` 用 `newIdentityHashMap`（BlockFire.java:33），传入非注册表实例无效。
- **注册顺序不变量**。`registerBlocks()` 中局部变量 block/block1/block2… 被后续 stairs/stem 构造引用（如 Block.java:1310 的 `block1.getDefaultState()`），且 `useNeighborBrightness` 的 farmland 特判直接比 `block13 == block6`（Block.java:1475）；插入新注册项时不能打乱这些局部引用。
- **getMetaFromState 抛异常**。基类实现对带属性的 state 抛 `IllegalArgumentException`（Block.java:267-269）；自定义带属性方块必须成对重写 `getStateFromMeta`/`getMetaFromState`/`createBlockState`，否则 registerBlocks 末尾填 BLOCK_STATE_IDS 时（Block.java:1492）当场崩溃。
- **两套 state id 位序相反**（见"数据与协议"表 1），拿错一个方向解出来的是完全不同的方块。
- **isRemote 分工**：`checkForSurroundingChests`（BlockChest.java:178）、`spawnAsEntity`（Block.java:633）、`dropBlockAsItemWithChance`（Block.java:609）等在客户端是 no-op；写功能时在客户端调用它们不会有效果。反之 `randomDisplayTick` 仅客户端。
- **BlockFurnace.setState 的 keepInventory**：`private static boolean keepInventory`（BlockFurnace.java:28）是**静态**标志，setState（BlockFurnace.java:137）期间置 true 防 breakBlock 掉物；非线程安全，若服务端多世界并发换炉会互踩（原版单线程无碍，移植加线程需注意）。同一方法里 `worldIn.setBlockState(...)` 连续调用两次（BlockFurnace.java:145-146）是原版原样保留的怪写法。
- **BlockDoublePlant/BlockDoor/BlockBed 双方块一致性**：上/下（头/脚）任一半被非常规手段移除时依赖 `onNeighborBlockChange` 清理另一半；直接 setBlockState flag 不带通知（flag&1==0）会留下孤儿半块。
- **BlockLeaves.surroundings** 是 32×32×32 的 `int[]` 实例字段（BlockLeaves.java:23,98），首次腐烂检查懒分配 128KB 且**非线程安全**——updateTick 只在服务端线程跑才成立。
- **BlockBeacon.updateColorAsync** 在 `HttpUtil.field_180193_a` 线程池里读世界（BlockBeacon.java:123-136），只有回写走 addScheduledTask；读侧本身即是与主线程的数据竞争（原版已知瑕疵，移植时勿模仿）。
- **BlockEventData.equals 未重写 hashCode**（BlockEventData.java:45）——WorldServer 用 List 去重而非 HashSet，改容器类型会破坏语义。
- **准连接性**：BlockDispenser/BlockCommandBlock 检测 `isBlockPowered(pos) || isBlockPowered(pos.up())`（BlockDispenser.java:159），这是 QC 行为的源头，红石相关功能勿"顺手修复"。
- **LWJGL3/JDK25 移植注意**：本桶为纯逻辑代码，无直接 GL/输入调用；泛型已现代化（如 Block.java:40 的 `<>` 推断、增强 for）。唯一与渲染路径耦合的是 leaves 的 `setGraphicsLevel` 与各 `getBlockLayer`/`isOpaqueCube`，若渲染层改成多线程 chunk build，需先解决上面的共享边界字段问题。

## 交叉引用

- net.minecraft.init → `Bootstrap#register` 调 `Block#registerBlocks` 与 `BlockFire#init`（Bootstrap.java:517-518）；`Blocks`/`Items` 静态表被本桶几乎每个类引用。
- net.minecraft.world → `WorldServer#updateBlocks` 调 `Block#randomTick`/`Block#fillWithRain`；`WorldServer#tickUpdates` 调 `Block#updateTick`；`World#rayTraceBlocks` 调 `Block#collisionRayTrace`；`World#handleMaterialAcceleration` 调 `Block#modifyAcceleration`；`WorldServer#fireBlockEvent` / `World#blockEvent` 调 `Block#onBlockEventReceived`。
- net.minecraft.client.multiplayer → `PlayerControllerMP#onPlayerRightClick` 调 `Block#onBlockActivated`（PlayerControllerMP.java:408）；`WorldClient#doVoidFogParticles` 调 `Block#randomDisplayTick`（WorldClient.java:319）。
- net.minecraft.client.renderer → `RenderGlobal#loadRenderers` 调 `BlockLeaves#setGraphicsLevel`（RenderGlobal.java:487-488）；chunk 渲染读 `Block#getActualState`/`Block#shouldSideBeRendered`/`Block#colorMultiplier`/`Block#getBlockLayer`。
- net.minecraft.entity → `Entity#moveEntity`/`Entity#doBlockCollisions` 调 `Block#onEntityCollidedWithBlock`（Entity.java:869/971）；`EntityFallingBlock` 由 `BlockFalling#checkFallable` 生成并回调 `BlockFalling#onEndFalling`。
- net.minecraft.entity.player → `EntityPlayer#displayGUIChest`/`EntityPlayer#displayGui`/`EntityPlayer#trySleep` 被 BlockChest/BlockAnvil/BlockBed 等调用；`EntityPlayer#canHarvestBlock` 被 `Block#getPlayerRelativeBlockHardness` 调用。
- net.minecraft.tileentity → BlockContainer 系列各自 `createNewTileEntity` 构造 TileEntityChest/Furnace/Dispenser/Hopper/Banner/Beacon/BrewingStand/FlowerPot/DaylightDetector/EnchantmentTable/EndPortal/CommandBlock；`BlockDropper#dispense` 调 `TileEntityHopper#putStackInInventoryAllSlots`。
- net.minecraft.inventory → `InventoryHelper#dropInventoryItems`（各容器 breakBlock）；`Container#calcRedstone`/`Container#calcRedstoneFromInventory`（比较器输出）；`InventoryLargeChest`（BlockChest#getLockableContainer）；`ContainerRepair`（BlockAnvil.Anvil#createContainer）。
- net.minecraft.item → `ItemBlock` 放置链调 `Block#onBlockPlaced`/`Block#onBlockPlacedBy`/`Block#canReplace`；`ItemLead#attachToFence`（BlockFence.java:174）；`ItemArmor#removeColor`、`ItemBanner`（BlockCauldron）。
- net.minecraft.dispenser → `IBehaviorDispenseItem#dispense`、`BehaviorDefaultDispenseItem`、`IBlockSource`（BlockDispenser/BlockDropper）。
- net.minecraft.block.state / block.properties / block.material → `BlockState`/`IBlockState#withProperty`、`PropertyBool/Integer/Enum/Direction`、`Material`/`MapColor` 是本桶全部状态建模的基座。
- net.minecraft.world.biome → `BiomeColorHelper#getGrassColorAtPos`/`#getFoliageColorAtPos`/`#getWaterColorAtPos`（BlockGrass/BlockLeaves/BlockLiquid 染色）；`BiomeGenBase#pickRandomFlower`（BlockGrass#grow）。
- net.minecraft.util → `HttpUtil.field_180193_a` 线程池（BlockBeacon#updateColorAsync）；`StatList` 各交互统计（多数 onBlockActivated 内 triggerAchievement）。

## 覆盖声明

完整读取了 75/75 个文件（Block.java 分两次分页读完，其余均单次全文读取）。

逐行精读的类：Block、BlockChest、BlockFire、BlockLiquid、BlockDynamicLiquid、BlockDoor、BlockButton、BlockLever、BlockBasePressurePlate、BlockDispenser、BlockDropper、BlockFalling、BlockLeaves、BlockFlowerPot、BlockContainer、BlockCauldron、BlockFurnace、BlockBanner、BlockBed、BlockBeacon、BlockJukebox、BlockDoublePlant。

全文读取但按结构性理解（未逐语句推演其数值细节）的类：BlockCocoa、BlockCrops、BlockBrewingStand、BlockFence、BlockFenceGate、BlockFlower、BlockAnvil、BlockDaylightDetector、BlockCake、BlockDirt、BlockFarmland、BlockEnderChest、BlockGrass、BlockCommandBlock、BlockLadder、BlockHugeMushroom、BlockCarpet、BlockCactus、BlockDragonEgg、BlockEnchantmentTable、BlockEndPortalFrame、BlockEndPortal、BlockBush、BlockLog、BlockIce、BlockLilyPad、BlockHay、BlockColored、BlockDeadBush、BlockEventData、BlockAir、BlockBreakable、BlockGlowstone、BlockBarrier、BlockMelon、BlockGlass、BlockClay、BlockBookshelf、BlockGravel、BlockLeavesBase、BlockCompressedPowered、BlockHardenedClay、BlockDirectional、BlockCarrot，以及 8 个 ≤9 行的桩类（BlockHalf*/BlockDouble*Slab、BlockButtonStone、BlockButtonWood）。

外部调用点（Bootstrap、WorldServer、WorldClient、PlayerControllerMP、Entity、RenderGlobal、World）均用 grep 验证过行号，未凭记忆书写。
