---
area: net/minecraft/tileentity
slug: mc-tileentity
files: 24
lines: 5453
tier: B
---

# net/minecraft/tileentity — 方块实体（Block Entity）

## 定位

这个包实现所有"带持久化状态的方块"的逻辑载体：箱子、熔炉、酿造台、漏斗、信标、告示牌、刷怪笼、活塞动画等。方块本身（`net.minecraft.block`）是无状态单例，任何需要每个位置独立数据（物品栏、文本、计时器）的方块都把状态放进对应的 `TileEntity` 子类。

- **谁调用它**：`World#updateEntities()` 每 tick 遍历 `tickableTileEntities` 调用 `ITickable#update()`（World.java:1747）；`AnvilChunkLoader` 读区块 NBT 时经 `TileEntity.createAndLoadEntity` 反序列化（AnvilChunkLoader.java:447）；`NetHandlerPlayClient#handleUpdateTileEntity`（S35 包）与 `#handleUpdateSign`（S33 包）在主线程把服务端数据写回这些对象；`TileEntityRendererDispatcher` 每帧对特殊渲染方块调用 renderer；各 `Block` 子类在交互时取出 TileEntity 打开 GUI/触发逻辑。
- **它调用谁**：`net.minecraft.nbt`（序列化）、`net.minecraft.inventory`（Container/IInventory 契约）、`net.minecraft.network.play.server`（构造 S33/S35 描述包）、`net.minecraft.world.World`（block event、状态变更、声音、粒子）、`net.minecraft.entity`（刷怪、信标 buff、活塞推动）。
- **如果消失**：区块加载时所有容器方块丢内容并打印 "Skipping BlockEntity"、容器 GUI 无法打开、熔炉/酿造/漏斗停摆、告示牌无文字、活塞无动画、刷怪笼失效——本质上所有有状态方块全坏。

注意：本仓库虽是客户端工程，但内嵌集成服务端（单机），因此这些类的"服务端分支"（`!worldObj.isRemote`）在单机时同样会跑，跑在集成服务端线程。

## 类清单

| 类名 | 行数 | extends / implements | 一句话职责 |
|---|---|---|---|
| IHopper | 27 | interface, extends IInventory | 漏斗抽象：暴露 getWorld/getXPos/getYPos/getZPos，供 TileEntityHopper 与 EntityMinecartHopper 共用静态搬运逻辑 |
| MobSpawnerBaseLogic | 441 | abstract class | 刷怪笼核心逻辑（计时、候选实体、生成、NBT），与宿主（TileEntity 或矿车）解耦 |
| TileEntity | 317 | abstract class | 基类：位置/世界引用、id↔class 注册表、NBT 读写、markDirty、描述包、失效管理 |
| TileEntityBanner | 323 | extends TileEntity | 旗帜：baseColor + Patterns NBT，惰性展开 pattern/color 列表并生成纹理缓存 key |
| TileEntityBeacon | 507 | extends TileEntityLockable implements ITickable, IInventory | 信标：每 80 tick 扫描光束段/金字塔层数并给玩家加药水效果；1 格支付槽 |
| TileEntityBrewingStand | 448 | extends TileEntityLockable implements ITickable, ISidedInventory | 酿造台：4 槽（3 瓶 + 1 原料），brewTime 400 tick 倒计时并更新 HAS_BOTTLE 方块状态 |
| TileEntityChest | 524 | extends TileEntityLockable implements ITickable, IInventory | 箱子：27 槽物品、相邻大箱子探测、lidAngle 开盖动画、numPlayersUsing 同步 |
| TileEntityCommandBlock | 92 | extends TileEntity | 命令方块：内嵌匿名 CommandBlockLogic 把命令执行委托给 command 包 |
| TileEntityComparator | 30 | extends TileEntity | 比较器：仅存 outputSignal 一个 int 的信号强度 |
| TileEntityDaylightDetector | 23 | extends TileEntity implements ITickable | 阳光探测器：每 20 tick 服务端侧调 BlockDaylightDetector.updatePower |
| TileEntityDispenser | 265 | extends TileEntityLockable implements IInventory | 发射器：9 槽物品栏，getDispenseSlot 随机选非空槽 |
| TileEntityDropper | 17 | extends TileEntityDispenser | 投掷器：仅覆写名称与 GuiID |
| TileEntityEnchantmentTable | 169 | extends TileEntity implements ITickable, IInteractionObject | 附魔台：纯客户端书本动画状态（翻页/朝向/张开度），无物品存储 |
| TileEntityEndPortal | 5 | extends TileEntity | 末地传送门：空标记类，仅为触发 TileEntityEndPortalRenderer |
| TileEntityEnderChest | 111 | extends TileEntity implements ITickable | 末影箱：无自有库存（在 InventoryEnderChest），仅 lidAngle 动画与使用计数 |
| TileEntityFlowerPot | 76 | extends TileEntity | 花盆：flowerPotItem + flowerPotData 两字段，描述包把 Item 名换成数字 id |
| TileEntityFurnace | 522 | extends TileEntityLockable implements ITickable, ISidedInventory | 熔炉：3 槽（输入/燃料/输出），燃烧与烧炼计时、燃料表（静态 getItemBurnTime） |
| TileEntityHopper | 752 | extends TileEntityLockable implements IHopper, ITickable | 漏斗：5 槽，8 tick 冷却搬运；含整套静态物品搬运/合并工具方法 |
| TileEntityLockable | 53 | abstract, extends TileEntity implements IInteractionObject, ILockableContainer | 容器公共基类：LockCode 锁 + getDisplayName |
| TileEntityMobSpawner | 84 | extends TileEntity implements ITickable | 刷怪笼宿主：匿名 MobSpawnerBaseLogic 绑定到方块位置，转发 tick/NBT/事件 |
| TileEntityNote | 70 | extends TileEntity | 音符盒：note (byte 0..24)、changePitch、按下方材质选乐器发 block event |
| TileEntityPiston | 219 | extends TileEntity implements ITickable | 活塞移动方块的临时 TE：progress 0→1（每 tick +0.5），推挤实体，结束后落地方块 |
| TileEntitySign | 231 | extends TileEntity | 告示牌：4 行 IChatComponent、编辑状态、点击行 ClickEvent 执行命令 |
| TileEntitySkull | 147 | extends TileEntity | 头颅：skullType/skullRotation + 玩家 GameProfile（皮肤纹理经会话服务补全） |

## 核心类详解

### TileEntity（TileEntity.java）

所有方块实体的根。关键字段：

- `protected World worldObj`；`protected BlockPos pos = BlockPos.ORIGIN`；`protected boolean tileEntityInvalid`；`private int blockMetadata = -1`；`protected Block blockType`（TileEntity.java:25-31）。
- 两张静态注册表：`private static Map<String, Class<? extends TileEntity>> nameToClassMap` / `classToNameMap`（TileEntity.java:21-22），由类底部 `static {}` 块经 `addMapping(Class<? extends TileEntity> cl, String id)` 填充（TileEntity.java:36, 293-316）。注册 21 个 id，如 `"Furnace"`、`"Chest"`、`"Control"`（命令方块）、`"Cauldron"`（酿造台，历史命名）。重复 id 抛 `IllegalArgumentException("Duplicate id: " + id)`（TileEntity.java:40）。

关键方法（签名逐字）：

- `public static TileEntity createAndLoadEntity(NBTTagCompound nbt)`（TileEntity.java:98）——按 `"id"` 字段反射 `newInstance()` 再 `readFromNBT`；未注册 id 仅 `logger.warn("Skipping BlockEntity with id ...")`。被 `AnvilChunkLoader`（AnvilChunkLoader.java:447）和 S35 全量区块路径调用。
- `public void writeToNBT(NBTTagCompound compound)`（TileEntity.java:78）——写 `id/x/y/z`；类不在 `classToNameMap` 时抛 `RuntimeException(... + " is missing a mapping! This is a bug!")`（TileEntity.java:84）。**自定义 TileEntity 子类必须先 addMapping（私有静态，需反射或改源码）**。
- `public void markDirty()`（TileEntity.java:143)——`worldObj.markChunkDirty(pos, this)` 并触发 `updateComparatorOutputLevel`。所有库存变更走这里。
- `public Packet getDescriptionPacket()`（TileEntity.java:196）——默认 `null`；子类返回 S35/S33 用于服务端→客户端同步。
- `public boolean receiveClientEvent(int id, int type)`（TileEntity.java:222）——block event 到达 TE 的入口（World.addBlockEvent 的接收端）。
- `public void invalidate()` / `public void validate()` / `public boolean isInvalid()`（TileEntity.java:201-220）——区块卸载/方块移除时的生命周期开关。
- `public double getMaxRenderDistanceSquared()`（TileEntity.java:169）——默认 `4096.0D`（64 格）；Beacon 覆写为 `65536.0D`（TileEntityBeacon.java:257-260）。
- `public boolean func_183000_F()`（TileEntity.java:288）——默认 false；Sign/CommandBlock/MobSpawner 返回 true（含义：交互需 OP/特殊处理的 TE）。

### TileEntityLockable（TileEntityLockable.java）

容器类 TE 的公共基类。字段 `private LockCode code = LockCode.EMPTY_CODE`（TileEntityLockable.java:13）。`readFromNBT`/`writeToNBT` 处理锁 NBT（TileEntityLockable.java:15-29）；`public IChatComponent getDisplayName()`（TileEntityLockable.java:49）按 `hasCustomName()` 返回文本或翻译组件。子类必须实现 `IInteractionObject` 的 `getGuiID()` 与 `createContainer(InventoryPlayer, EntityPlayer)`，GUI 系统据此打开容器界面。

### TileEntityChest（TileEntityChest.java）

- 字段：`private ItemStack[] chestContents = new ItemStack[27]`（:21）、`public TileEntityChest adjacentChestZNeg/XPos/XNeg/ZPos`（:27-36）、`public float lidAngle` / `public float prevLidAngle`（:39-42）、`public int numPlayersUsing`（:45）、`private int ticksSinceSync`（:48）。
- `public void update()`（:327）——每 tick：`checkForAdjacentChests()`；服务端每 200 tick（带坐标偏移错峰）重算 `numPlayersUsing`（扫描 5 格内玩家的 `openContainer`，:335-352）；然后双端推进 `lidAngle`（±0.1/tick）并在开/过半关时播 `"random.chestopen"` / `"random.chestclosed"`（:372, :410）。
- `public void checkForAdjacentChests()`（:280）——惰性建立大箱子相邻引用；`updateContainingBlockInfo()` 与 `invalidate()` 都会使其失效重查（:227-231, :471-476）。
- `public boolean receiveClientEvent(int id, int type)`（:420）——id==1 时 `numPlayersUsing = type`，这是客户端开盖动画的唯一数据来源。
- `public void openInventory(EntityPlayer player)` / `public void closeInventory(EntityPlayer player)`（:433, :449）——增减计数并 `worldObj.addBlockEvent(this.pos, this.getBlockType(), 1, this.numPlayersUsing)`，同时通知上下邻居（比较器）。由 `Container`/`BlockChest` 在玩家开关 GUI 时调用。

### TileEntityFurnace（TileEntityFurnace.java）

- 字段：`private ItemStack[] furnaceItemStacks = new ItemStack[3]`（:37，0=输入 1=燃料 2=输出）、`private int furnaceBurnTime`、`private int currentItemBurnTime`、`private int cookTime`、`private int totalCookTime`（:40-47）。
- `public void update()`（:235）——仅服务端分支干活：点燃燃料（`getItemBurnTime` 赋给 `furnaceBurnTime`，消耗燃料并处理容器物品如岩浆桶→桶，:251-267）；燃烧中 `++this.cookTime`，达到 `totalCookTime`（`getCookTime` 恒 200，:305-308）时 `smeltItem()`；熄火时 `cookTime` 每 tick 回退 2（:289）；燃烧状态翻转时 `BlockFurnace.setState(this.isBurning(), this.worldObj, this.pos)` 切换亮/暗方块（:295）。
- `public static int getItemBurnTime(ItemStack p_145952_0_)`（:362）——硬编码燃料表（木质 300、煤 1600、煤块 16000、岩浆桶 20000、烈焰棒 2400 等）。
- GUI 进度条经 `getField/setField`（id 0..3 = burnTime/currentItemBurnTime/cookTime/totalCookTime，:468-508）由 `ContainerFurnace` 走 S31PacketWindowProperty 同步，客户端不本地模拟。
- `ISidedInventory`：`getSlotsForFace`（DOWN→{2,1}，UP→{0}，侧面→{1}，:425-428）；`canExtractItem` 禁止从底部抽走非空桶燃料（:443-456）。

### TileEntityHopper（TileEntityHopper.java）

- 字段：`private ItemStack[] inventory = new ItemStack[5]`（:28）、`private int transferCooldown = -1`（:30）。
- `public void update()`（:224）——服务端递减冷却，到 0 调 `updateHopper()`。
- `public boolean updateHopper()`（:238）——`BlockHopper.isEnabled(this.getBlockMetadata())`（红石未锁）时先 `transferItemsOut()` 再 `captureDroppedItems(this)`；任一成功则 `setTransferCooldown(8)` + `markDirty()`。
- 静态工具方法是全包搬运物品的事实标准：
  - `public static ItemStack putStackInInventoryAllSlots(IInventory inventoryIn, ItemStack stack, EnumFacing side)`（:522）——返回剩余；
  - `public static boolean captureDroppedItems(IHopper p_145891_0_)`（:410）——从上方容器抽取或吸取地面 `EntityItem`；
  - `public static boolean putDropInInventoryAllSlots(IInventory p_145898_0_, EntityItem itemIn)`（:492）；
  - `public static IInventory getInventoryAtPosition(World worldIn, double x, double y, double z)`（:640）——TE 容器优先（大箱子经 `BlockChest.getLockableContainer` 合并），否则随机选一个 AABB 内实体容器；
  - `private static ItemStack insertStack(IInventory inventoryIn, ItemStack stack, int index, EnumFacing side)`（:571）——注入目标若是 `TileEntityHopper` 且 `mayTransfer()`，重置其冷却为 8（:596-603）。
- `getXPos/getYPos/getZPos` 返回方块中心（+0.5D，:685-704），实现 `IHopper` 供矿车漏斗复用同一套静态逻辑。

### TileEntityBeacon（TileEntityBeacon.java）

- 字段：`public static final Potion[][] effectsList`（:33，4 层可选效果）、`private final List<TileEntityBeacon.BeamSegment> beamSegments`（:34）、`private boolean isComplete`、`private int levels = -1`、`private int primaryEffect`、`private int secondaryEffect`、`private ItemStack payment`（:37-49）。
- `public void update()`（:55）——`worldObj.getTotalWorldTime() % 80L == 0L` 时 `updateBeacon()`（= `updateSegmentColors()` + `addEffectsToPlayers()`）。
- `private void updateSegmentColors()`（:102）——向上扫到 y=256 计算光束分段颜色（染色玻璃/玻璃板混色，遇不透光方块判 `isComplete=false`）；向下验证 1..4 层金字塔（仅 emerald/gold/diamond/iron block，:180）；4 层达成时给附近玩家 `AchievementList.fullBeacon` 成就。
- `private void addEffectsToPlayers()`（:69）——服务端在 `levels*10+10` 范围（Y 向延伸到世界顶）给玩家 `new PotionEffect(this.primaryEffect, 180, i, true, true)`。
- `public float shouldBeamRender()`（:214）——渲染端调用，返回 0..1 的光束淡入系数（内部按 `getTotalWorldTime` 差值衰减，**每帧调用但按 tick 计数**）。
- `public boolean receiveClientEvent(int id, int type)`（:468）——id==1 时立刻 `updateBeacon()`（设置效果 GUI 确认后服务端广播的 block event）。
- `getField/setField` id 0..2 = levels/primaryEffect/secondaryEffect，`func_183001_h(int)` 白名单过滤非法药水 id（:262-273）。

### MobSpawnerBaseLogic + TileEntityMobSpawner

- `MobSpawnerBaseLogic` 字段：`private int spawnDelay = 20`、`private String mobID = "Pig"`、`minSpawnDelay = 200`、`maxSpawnDelay = 800`、`spawnCount = 4`、`maxNearbyEntities = 6`、`activatingRangeFromPlayer = 16`、`spawnRange = 4`、`private Entity cachedEntity`（渲染用缓存）、`mobRotation/prevMobRotation`（MobSpawnerBaseLogic.java:24-46）。
- `public void updateSpawner()`（:82）——`isActivated()`（16 格内有玩家）时：客户端只喷 SMOKE/FLAME 粒子并旋转展示实体（:88-103）；服务端倒计时归零后循环 `spawnCount` 次 `EntityList.createEntityByName` → 校验 `getCanSpawnHere()`/`isNotColliding()` → `spawnNewEntity(entity, true)` + `playAuxSFX(2004, ...)`，成功则 `resetTimer()`（:104-160）。
- 三个抽象钩子由宿主实现：`public abstract void func_98267_a(int id);`、`public abstract World getSpawnerWorld();`、`public abstract BlockPos getSpawnerPosition();`（:381-385）。`TileEntityMobSpawner` 的匿名子类把 `func_98267_a` 实现为 `worldObj.addBlockEvent(pos, Blocks.mob_spawner, id, 0)`（TileEntityMobSpawner.java:15-18）。
- `public boolean setDelayToMin(int delay)`（:358）——仅 `delay == 1 && isRemote` 时把客户端 spawnDelay 拉到 min；即 block event id=1 是"刚刷怪"的客户端提示。`TileEntityMobSpawner#receiveClientEvent` 转发之（TileEntityMobSpawner.java:70-73）。
- `public Entity func_180612_a(World worldIn)`（:339）——渲染器取笼内展示实体（懒加载 `cachedEntity`）。

### TileEntitySign（TileEntitySign.java）

- 字段：`public final IChatComponent[] signText = new IChatComponent[] {...}`（:24，4 行）、`public int lineBeingEdited = -1`（:30，仅客户端编辑光标）、`private boolean isEditable = true`（:31）、`private EntityPlayer player`（:32）、`private final CommandResultStats stats`（:33）。
- `readFromNBT`（:48）——`isEditable = false`；每行 JSON → `IChatComponent`，并用匿名 `ICommandSender` 跑 `ChatComponentProcessor.processComponent`（展开 selector），JSON 解析失败降级纯文本（:98-115）。
- `public Packet getDescriptionPacket()`（:124）——返回 `new S33PacketUpdateSign(this.worldObj, this.pos, aichatcomponent)`（**签名类型注意**：告示牌走 S33 专用包，不走 S35）。
- `public boolean executeCommand(final EntityPlayer playerIn)`（:164）——遍历 4 行的 `ChatStyle#getChatClickEvent()`，`ClickEvent.Action.RUN_COMMAND` 时经 `MinecraftServer.getServer().getCommandManager().executeCommand(...)` 以 permLevel≤2 的伪 sender 执行（:217-220）。由 `BlockSign`/玩家右击路径触发（服务端）。
- 编辑流程：S36 打开→ `NetHandlerPlayClient` 若 TE 不是 sign 则 `new TileEntitySign()` 临时对象并 `thePlayer.openEditSign(...)`（NetHandlerPlayClient.java:1221-1228）打开 `GuiEditSign`。

### TileEntityPiston（TileEntityPiston.java）

- 字段：`private IBlockState pistonState`、`private EnumFacing pistonFacing`、`private boolean extending`、`private boolean shouldHeadBeRendered`、`private float progress`、`private float lastProgress`（:16-25）。
- `public void update()`（:169）——`progress` 每 tick +0.5F（即动画共 2 tick）；到 1.0 后 `launchWithSlimeBlock(1.0F, 0.25F)`、`worldObj.removeTileEntity(this.pos)`、`invalidate()`，并把 `piston_extension` 替换回真实 `pistonState`（:176-183）。
- `public float getProgress(float ticks)`（:72）——渲染插值入口（`TileEntityPistonRenderer` 每帧调用）；`getOffsetX/Y/Z(float ticks)`（:82-95）给出被推方块的渲染位移。
- `private void launchWithSlimeBlock(float p_145863_1_, float p_145863_2_)`（:97）——推挤 AABB 内实体；粘液块 + extending 时直接设置实体 `motionX/Y/Z` 弹射（:120-135）。
- `public void clearPistonTileEntity()`（:150）——外部强制结束动画（方块被破坏等）。

### TileEntitySkull（TileEntitySkull.java）

- 字段：`private int skullType`、`private int skullRotation`、`private GameProfile playerProfile = null`（:16-18）。
- `readFromNBT`（:34）——`skullType == 3`（玩家头）时读 `"Owner"` GameProfile，或旧格式 `"ExtraType"` 字符串名并 `updatePlayerProfile()`。
- `public static GameProfile updateGameprofile(GameProfile input)`（:94）——经 `MinecraftServer.getServer().getPlayerProfileCache()` 与 `getMinecraftSessionService().fillProfileProperties(gameprofile, true)` 补全 textures 属性；**`MinecraftServer.getServer() == null`（纯远程客户端）时原样返回**（:102-104），此调用可能同步访问 Mojang 会话服务（网络 IO 在调用线程上发生）。

## 时序与生命周期

- **创建/加载**：区块从磁盘加载 → `AnvilChunkLoader`（AnvilChunkLoader.java:447）调 `TileEntity.createAndLoadEntity(nbt)` → 加入 Chunk/World；或方块被放置时 `Block#createNewTileEntity` 创建。`World#addTileEntity` 时若 `instanceof ITickable` 则进入 `tickableTileEntities`（World.java:1815-1817）。`validate()`/`invalidate()` 切换有效性；`World.removeTileEntity` 移除。
- **每 tick**（集成服务端线程对 WorldServer，客户端主线程对 WorldClient）：`World#updateEntities()`（World.java:1619）遍历 `tickableTileEntities`，对每个已加载且在 world border 内的 TE 调 `((ITickable)tileentity).update()`（World.java:1747），异常包成 "Ticking block entity" CrashReport 并经 `addInfoToCrashReport` 附加信息。各 TE 的 tick 工作见核心类详解；周期性任务错峰：Beacon 80 tick、DaylightDetector 20 tick、Chest 玩家数复核 200 tick（加坐标偏移）、EnderChest block event 广播约 80 tick（`++this.ticksSinceSync % 20 * 4 == 0`，注意运算优先级实为 `(ticksSinceSync % 20) * 4 == 0`，TileEntityEnderChest.java:21）。
- **每帧**（客户端渲染线程 = 主线程）：`TileEntityRendererDispatcher#renderTileEntity(TileEntity tileentityIn, float partialTicks, int destroyStage)`（TileEntityRendererDispatcher.java:104）对有 special renderer 的 TE（Chest/EnderChest/Sign/Skull/Banner/Beacon/Piston/EnchantmentTable/EndPortal/MobSpawner）渲染；插值数据来自 TE 的 prev/current 字段对（lidAngle、mobRotation、progress、pageFlip 等）。
- **网络同步**（Netty EventLoop 收包 → `PacketThreadUtil.checkThreadAndEnqueue` 转主线程）：S35 到达后 `NetHandlerPlayClient#handleUpdateTileEntity`（NetHandlerPlayClient.java:1267）按类型 1..6 校验 TE 类别后直接 `tileentity.readFromNBT(packetIn.getNbtCompound())`；S33 由 `handleUpdateSign`（:1234）写 sign 文本。TE 的 `readFromNBT` 因此必然在主线程执行。
- **销毁**：方块破坏/区块卸载 → `invalidate()`；Chest/EnderChest 覆写 `invalidate()` 附带 `updateContainingBlockInfo()` 刷新相邻缓存；Piston 在动画结束时自我移除。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void update()`（各 ITickable 实现） | TileEntityChest.java:327, TileEntityFurnace.java:235, TileEntityHopper.java:224, TileEntityBeacon.java:55, TileEntityBrewingStand.java:78, TileEntityPiston.java:169, TileEntityEnderChest.java:19, TileEntityEnchantmentTable.java:54, TileEntityDaylightDetector.java:11, TileEntityMobSpawner.java:53 | `World#updateEntities()` 每 tick（World.java:1747） | 观察/改写容器自动化（熔炉速度、漏斗吞吐）、箱子动画、刷怪节奏；功能层做 ChestESP/容器统计的数据源 | 双端共用，必须区分 `worldObj.isRemote`；抛异常会带崩整个 tick（CrashReport） |
| `public static TileEntity createAndLoadEntity(NBTTagCompound nbt)` | TileEntity.java:98 | 区块加载、S35 全量路径 | 拦截/替换 TE 实例（注入自定义子类）、审计未知 id | 反射 `newInstance()` 要求无参构造；未注册 id 静默丢弃 |
| `private static void addMapping(Class<? extends TileEntity> cl, String id)` | TileEntity.java:36 | 类初始化 static 块（:293-316） | 注册自定义 TileEntity（需反射访问 private） | 重复 id 抛 IllegalArgumentException；不注册则 writeToNBT 抛 RuntimeException |
| `public void markDirty()` | TileEntity.java:143 | 任何库存/状态变更后 | 监听所有容器内容变化（物品追踪、自动整理触发点） | 高频调用；`worldObj == null` 时跳过 |
| `public Packet getDescriptionPacket()` | TileEntity.java:196（覆写：Banner:94, Beacon:250, CommandBlock:71, FlowerPot:52, MobSpawner:62, Sign:124, Skull:68） | 服务端 chunk 发送/`markBlockForUpdate` 后 | 改写同步给客户端的数据（隐藏告示牌内容、伪造头颅皮肤） | 仅服务端调用；返回 null 表示无同步 |
| `public boolean receiveClientEvent(int id, int type)` | TileEntity.java:222（覆写：Chest:420, EnderChest:73, Beacon:468, MobSpawner:70） | `World` 分发 block event（S24PacketBlockAction 的客户端落点） | 观察箱子开关（id=1, type=玩家数）、信标刷新、刷怪笼刚刷怪信号 | id/type 语义每类不同；返回 false 会继续走 Block#onBlockEventReceived |
| `public void openInventory(EntityPlayer player)` / `public void closeInventory(EntityPlayer player)` | TileEntityChest.java:433/449（空实现见 Beacon:397, BrewingStand:349, Dispenser:218, Furnace:409, Hopper:205） | Container 打开/关闭时 | GUI 打开/关闭事件源；统计谁在用哪个箱子 | Chest 版有副作用（block event + 邻居通知），勿重复调用 |
| `public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)` + `public String getGuiID()` | TileEntityChest.java:498/493, TileEntityFurnace.java:463/458, TileEntityHopper.java:726/721, TileEntityBeacon.java:418/413, TileEntityBrewingStand.java:408/403, TileEntityDispenser.java:239/234, TileEntityEnchantmentTable.java:160/165 | 玩家右击方块打开 GUI（IInteractionObject 路径） | 替换/包装 Container 实现自定义容器 UI | 服务端与客户端各建一份 Container，windowId 必须匹配 |
| `public void updateSpawner()` | MobSpawnerBaseLogic.java:82 | TileEntityMobSpawner#update 每 tick | 关闭/加速刷怪、替换生成实体、粒子控制 | isActivated 每 tick 做玩家距离查询；客户端分支只管展示 |
| `public boolean updateHopper()` | TileEntityHopper.java:238 | update() 冷却归零时；也被 BlockHopper 外部触发 | 接管物品物流（过滤、统计、加速） | 仅服务端有效；返回 true 才重置 8 tick 冷却 |
| `public static ItemStack putStackInInventoryAllSlots(IInventory inventoryIn, ItemStack stack, EnumFacing side)` | TileEntityHopper.java:522 | 漏斗/投掷器所有注入路径 | 全局物品注入拦截点（唯一收口） | 静态方法，hook 需字节码层面；side==null 走全槽位 |
| `public boolean executeCommand(final EntityPlayer playerIn)` | TileEntitySign.java:164 | 服务端处理玩家右击告示牌 | 拦截 RUN_COMMAND 点击事件（防钓鱼命令） | 权限固定 permLevel<=2；`MinecraftServer.getServer()` 纯远程时为 null（原版此路径仅服务端跑） |
| `public float shouldBeamRender()` | TileEntityBeacon.java:214 | TileEntityBeaconRenderer 每帧 | 控制光束显隐/透明度 | 有副作用（更新 beamRenderCounter/field_146014_j），每帧多次调用会扰乱淡入 |
| `public float getProgress(float ticks)` | TileEntityPiston.java:72 | TileEntityPistonRenderer 每帧 | 活塞动画插值观察/篡改（免卡视觉） | 纯读（含 clamp）；真实碰撞由 update() 决定，改这里只影响渲染 |
| `public boolean setDelayToMin(int delay)` | MobSpawnerBaseLogic.java:358 | receiveClientEvent(id=1) | 检测"刷怪笼刚触发"事件（客户端可感知） | 仅 isRemote 分支生效 |
| `public void setItemValues(ItemStack stack)` | TileEntityBanner.java:30 | 放置旗帜方块时由 Block/Item 侧调用 | 拦截旗帜图案初始化 | 会清空缓存列表，之后首次 getPatternList 惰性重建 |
| `public boolean isUseableByPlayer(EntityPlayer player)` | TileEntityChest.java:222（同型：Beacon:392, BrewingStand:344, Dispenser:213, Furnace:404, Hopper:200; EnderChest 为 `canBeUsed`:107） | Container#canInteractWith 每 tick 校验 | 放宽/收紧容器交互距离（默认 64.0D 平方 = 8 格） | 服务端同样校验，仅改客户端会被踢出 GUI |
| `public void invalidate()` | TileEntity.java:209（覆写 Chest:471, EnderChest:89） | 方块移除/区块卸载 | TE 生命周期结束事件（清理缓存、注销监听） | 与 validate 配对；invalid 后仍可能短暂被引用 |
| `public void handleUpdateTileEntity(S35PacketUpdateTileEntity packetIn)`（包外落点） | NetHandlerPlayClient.java:1267 | Netty EventLoop 收包→enqueue→主线程 | 观察服务端推送的 TE 数据（刷怪笼类型、信标等级、头颅 profile） | 类型 1..6 与 TE 类别强校验，不匹配即丢弃 |

## 数据与协议

### NBT 字段（按类）

| 类 | 字段名 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|---|
| TileEntity | `id` | String | writeToNBT（TileEntity.java:88）/ createAndLoadEntity | 注册表名（"Furnace" 等 21 个） |
| TileEntity | `x`/`y`/`z` | int | readFromNBT:75 / writeToNBT:89-91 | 方块坐标 |
| TileEntityLockable | Lock（经 LockCode） | String | readFromNBT:18 / writeToNBT:27 | 容器锁物品名 |
| 容器类（Chest/Dispenser/Furnace/BrewingStand/Hopper） | `Items` | TagList(Compound{`Slot`:byte + ItemStack}) | 各自 read/writeToNBT | 槽位物品；Chest 读取时 `getByte("Slot") & 255`（TileEntityChest.java:178） |
| 容器类 | `CustomName` | String(8) | 各自 read/writeToNBT | 自定义名（改 GUI 标题） |
| TileEntityFurnace | `BurnTime`/`CookTime`/`CookTimeTotal` | short | :173-175 / :187-189 | 燃烧余量/烧炼进度/总时长 |
| TileEntityBrewingStand | `BrewTime` | short | :247 / :258 | 酿造倒计时（400 起） |
| TileEntityHopper | `TransferCooldown` | int | :43 / :74 | 搬运冷却 |
| TileEntityBeacon | `Primary`/`Secondary`/`Levels` | int | :278-280 / :286-288 | 效果 id（白名单过滤）与层数 |
| TileEntityBanner | `Base` | int; `Patterns` | TagList(Compound{`Pattern`:String, `Color`:int}) | :82-83 / :66（经 `setBaseColorAndPatterns`:69） | 底色 dye damage 与图案叠层 |
| TileEntitySign | `Text1`..`Text4` | String（IChatComponent JSON） | :96 / :41-43 | 4 行文本；另有 CommandResultStats 统计字段 |
| TileEntitySkull | `SkullType`/`Rot` | byte; `Owner` | Compound(GameProfile); 旧 `ExtraType` String | :37-48 / :23-30 | 头颅类型(3=玩家)/朝向/所有者 |
| TileEntityFlowerPot | `Item` | String（写）或 int（旧读）; `Data` | int | :36-45 / :28-29 | 盆栽物品与 meta |
| TileEntityNote | `note` | byte | :27（读后 clamp 0..24）/ :21 | 音高 |
| TileEntityComparator | `OutputSignal` | int | :18 / :12 | 比较器输出强度 |
| TileEntityPiston | `blockId`/`blockData`/`facing` | int; `progress` | float; `extending` | boolean | :204-207 / :213-217 | 被推方块状态与动画进度 |
| MobSpawnerBaseLogic | `EntityId` | String; `Delay`/`MinSpawnDelay`/`MaxSpawnDelay`/`SpawnCount`/`MaxNearbyEntities`/`RequiredPlayerRange`/`SpawnRange` | short; `SpawnData` | Compound; `SpawnPotentials` | TagList(Compound{`Properties`,`Type`,`Weight`}) | readFromNBT:249-296 / writeToNBT:298-337 | 刷怪配置全集；`"Minecart"` 会规格化为 `"MinecartRideable"`（:55-58, :416-425） |

### 封包

| 包 | 方向 | 构造点 | 客户端落点 | 说明 |
|---|---|---|---|---|
| S35PacketUpdateTileEntity | S→C | Banner(type 6, TileEntityBanner.java:98)、Beacon(3, :254)、CommandBlock(2, :75)、Skull(4, :72)、FlowerPot(5, :58)、MobSpawner(1, TileEntityMobSpawner.java:67，剔除 `SpawnPotentials`) | NetHandlerPlayClient.java:1267，类型与 TE instanceof 双重校验后 `readFromNBT` | FlowerPot 描述包把 `Item` 从字符串替换为 `Item.getIdFromItem` 数字（TileEntityFlowerPot.java:56-57） |
| S33PacketUpdateSign | S→C | TileEntitySign#getDescriptionPacket（TileEntitySign.java:128） | NetHandlerPlayClient.java:1234 | 告示牌专用文本同步 |
| S24PacketBlockAction（block event） | S→C | `World#addBlockEvent`：Chest:443、EnderChest:23/98/104、Note:67、MobSpawner 匿名类 func_98267_a | 落到 `receiveClientEvent(int id, int type)` | Chest/EnderChest id=1 type=使用人数；Note id=乐器 type=音高 |
| S31PacketWindowProperty | S→C | Container 侧轮询 `getField` | Container#updateProgressBar → `setField` | 熔炉 4 个、酿造台 1 个、信标 3 个进度字段 |

## 不变量与陷阱

- **注册表闭包**：`writeToNBT` 要求本类在 `classToNameMap` 中，否则运行期 `RuntimeException`（TileEntity.java:84）。新增 TE 子类必须同步 addMapping（private static，需改源码或反射）。
- **反射构造**：`createAndLoadEntity` 用 `oclass.newInstance()`（TileEntity.java:108）——所有可持久化 TE 必须有 public 无参构造（TileEntityPiston/TileEntityChest/TileEntityFlowerPot 均额外声明无参构造）。JDK 25 下 `Class#newInstance` 已 deprecated 但仍可用；若未来换 `getDeclaredConstructor().newInstance()` 注意异常类型变化。
- **isRemote 双分支**：几乎每个 `update()` 同时跑在客户端 WorldClient 和集成服务端 WorldServer 上；Hopper/Furnace/BrewingStand/DaylightDetector/Beacon 的实质逻辑仅在 `!isRemote`；Chest/EnderChest/EnchantmentTable 的动画在双端都推进。误在客户端分支改物品会产生幽灵物品。
- **线程约束**：TE 的一切读写都应在拥有该 World 的线程上（客户端主线程 / 集成服务端线程）。收包在 Netty EventLoop，但 `PacketThreadUtil.checkThreadAndEnqueue` 保证 `readFromNBT` 在主线程执行。功能层从渲染路径读 TE 字段是安全的（同线程），但读集成服务端世界的 TE 属跨线程，需谨慎。
- **tick 中修改 TE 列表**：`World#updateEntities` 使用迭代器遍历 `tickableTileEntities`；Piston 在 update 内 `removeTileEntity` 是通过 World 的延迟移除列表（`tileEntitiesToBeRemoved`，World.java:1775）安全完成的——自定义逻辑不要直接操作该 List。
- **Chest 相邻缓存**：`adjacentChestChecked` 缓存必须在方块变更后失效（`updateContainingBlockInfo`）；持有 `adjacentChestXPos` 等引用的代码要先 `checkForAdjacentChests()` 且注意引用可能指向已 invalid 的 TE。
- **Hopper decrStackSize 语义分裂**：`TileEntityBrewingStand#decrStackSize`（TileEntityBrewingStand.java:291-303）无视 count 直接取整组（与 Chest/Furnace 的按 count 拆分不同）——这是原版行为，勿"修复"。
- **Beacon 的 `getMaxRenderDistanceSquared() = 65536.0D`** 使光束在 256 格外仍尝试渲染；做渲染裁剪优化时这是特例。
- **`shouldBeamRender()` 有副作用**，只能由渲染器每帧调用一次；在功能层重复调用会加速淡入状态机。
- **EnderChest 计时表达式** `++this.ticksSinceSync % 20 * 4 == 0`（TileEntityEnderChest.java:21）因优先级实为 `(x % 20) * 4`，即每 20 tick 触发一次（x%20==0），并非注释直觉的 80 tick——依赖该节律时以代码为准。
- **Skull profile 补全**：`updateGameprofile` 在调用线程上可能触发 Mojang 会话服务网络请求（TileEntitySkull.java:120）；集成服务端加载大量玩家头颅会卡 tick。纯远程客户端 `MinecraftServer.getServer() == null` 直接跳过。
- **Sign 的 `signText` 是 `public final` 数组**：元素可被外部直接替换（GuiEditSign 就这么做）；渲染器逐字渲染 JSON 组件，注入超长文本会影响渲染但不会崩。
- **LWJGL3/JDK25 移植**：本包不直接触碰 GL/LWJGL，移植风险集中在其消费者（renderer 包）。JDK 25 相关仅有上述 `newInstance()` deprecation 与 log4j 使用，均无行为变化。

## 交叉引用

- world → `World#updateEntities`（World.java:1619, 1747 调 `ITickable#update`）；`World#addTileEntity` / `#removeTileEntity` / `#markChunkDirty` / `#addBlockEvent` / `#updateComparatorOutputLevel`
- world.chunk.storage → `AnvilChunkLoader`（:447）调 `TileEntity#createAndLoadEntity`
- client.network → `NetHandlerPlayClient#handleUpdateTileEntity`（:1267）、`#handleUpdateSign`（:1234）、`#handleSignEditorOpen`（:1221-1228 调 `EntityPlayerSP#openEditSign`）
- client.renderer.tileentity → `TileEntityRendererDispatcher#renderTileEntity` / `#renderTileEntityAt`（TileEntityRendererDispatcher.java:104/126）及各 `TileEntity*Renderer`
- client.gui.inventory → `GuiEditSign`（构造入参 `TileEntitySign`，GuiEditSign.java:32）
- inventory → `ContainerChest` / `ContainerFurnace` / `ContainerBeacon` / `ContainerBrewingStand` / `ContainerDispenser` / `ContainerHopper` / `ContainerEnchantment`（各 `createContainer` 返回值）；`ISidedInventory` / `IInventory` 契约
- block → `BlockChest#getLockableContainer`（TileEntityHopper.java:659）、`BlockFurnace.setState`（TileEntityFurnace.java:295）、`BlockHopper.isEnabled/getFacing`（TileEntityHopper.java:242/308/620）、`BlockBrewingStand.HAS_BOTTLE`（TileEntityBrewingStand.java:120）、`BlockDaylightDetector#updatePower`（TileEntityDaylightDetector.java:19）、`BlockJukebox.TileEntityJukebox`（注册于 TileEntity.java:298）
- network.play.server → `S35PacketUpdateTileEntity`（Banner/Beacon/CommandBlock/Skull/FlowerPot/MobSpawner 的 `getDescriptionPacket`）、`S33PacketUpdateSign`（TileEntitySign.java:128）
- command → `CommandBlockLogic`（TileEntityCommandBlock.java:16 匿名实现）、`CommandResultStats`、`MinecraftServer#getCommandManager`（TileEntitySign.java:219）
- entity → `EntityList.createEntityByName`（MobSpawnerBaseLogic.java:121/189/343）、`EntityLiving#onInitialSpawn`（:220）、`EntityPlayer#addPotionEffect`（TileEntityBeacon.java:89）、`Entity#moveEntity`（TileEntityPiston.java:138）、`EntityItem`（漏斗吸物）
- item.crafting → `FurnaceRecipes.instance().getSmeltingResult`（TileEntityFurnace.java:321/333）
- potion → `PotionHelper.applyIngredient`（TileEntityBrewingStand.java:227）、`Potion.potionTypes` 白名单（TileEntityBeacon.java:264）
- authlib（外部库）→ `GameProfile` / `MinecraftSessionService#fillProfileProperties`（TileEntitySkull.java:120）
- stats → `AchievementList.fullBeacon`（TileEntityBeacon.java:204）

## 覆盖声明

完整读取了 24/24 个文件（全部经 Read 工具逐文件全文读取，无抽样）。

- 逐行精读：TileEntity、TileEntityLockable、TileEntityChest、TileEntityFurnace、TileEntityHopper、TileEntityBeacon、MobSpawnerBaseLogic、TileEntityMobSpawner、TileEntitySign、TileEntityPiston、TileEntitySkull、TileEntityBrewingStand。
- 全文读取但仅做结构性梳理（逻辑简单，未逐句推演）：IHopper、TileEntityBanner（EnumBannerPattern 常量表未逐项核对图案字符串语义）、TileEntityCommandBlock、TileEntityComparator、TileEntityDaylightDetector、TileEntityDispenser、TileEntityDropper、TileEntityEnchantmentTable（动画数学未逐步验证）、TileEntityEndPortal、TileEntityEnderChest、TileEntityFlowerPot、TileEntityNote。
- 交叉引用行号经 Grep/Read 在 World.java、NetHandlerPlayClient.java、AnvilChunkLoader.java、TileEntityRendererDispatcher.java、GuiEditSign.java 中逐一确认。
