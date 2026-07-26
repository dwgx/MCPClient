---
area: net/minecraft/entity/item
slug: mc-entity-item
files: 20
lines: 6358
tier: B
---

# net/minecraft/entity/item — 非生物实体（物品/载具/爆炸物/悬挂物）

## 定位

本包收纳所有"非生物"的世界实体：掉落物 `EntityItem`、经验球 `EntityXPOrb`、各类矿车（`EntityMinecart` 及其子类）、船 `EntityBoat`、下落方块 `EntityFallingBlock`、点燃的 TNT `EntityTNTPrimed`、盔甲架 `EntityArmorStand`、物品展示框 `EntityItemFrame`、画 `EntityPainting`、末影水晶 `EntityEnderCrystal`、以及三个投掷/飞行物（`EntityEnderPearl`、`EntityExpBottle`、`EntityEnderEye`）和烟花火箭 `EntityFireworkRocket`。

调用方向：
- 上游（谁创建/驱动它们）：`NetHandlerPlayClient.handleSpawnObject` 在收到 `S0EPacketSpawnObject` 时按 type id 直接 `new` 出这些实体并加入 `clientWorldController`（如 `NetHandlerPlayClient.java:307/330/381/401`）；`handleSpawnExperienceOrb` 创建 `EntityXPOrb`（`NetHandlerPlayClient.java:450`）。集成服务器一侧由方块逻辑（`BlockFalling`、`BlockTNT`、方块掉落 `Block.spawnAsEntity`）和物品使用逻辑创建。每 tick 由 `World.updateEntities` → `Entity.onUpdate` 驱动。
- 下游（它们调用谁）：`World`（方块查询/爆炸/粒子/声音）、`DataWatcher`（客户端-服务端状态同步）、NBT 序列化、`InventoryHelper`/`Container`（矿车容器）、`TileEntityHopper`（漏斗矿车吸物品）。
- 渲染由 `net/minecraft/client/renderer/entity` 包中对应 Renderer 消费（`RenderEntityItem`、`RenderMinecart`、`RenderBoat`、`RenderXPOrb`、`RenderTNTPrimed`、`RenderFallingBlock`、`RenderPainting`、`ArmorStandRenderer` 等）。

如果这个包消失：掉落物拾取、经验获取、矿车/船乘骑、TNT 爆炸、沙子/沙砾下落、展示框/画/盔甲架全部失效，`NetHandlerPlayClient.handleSpawnObject` 无法实例化大半 object 类实体，客户端进入含这些实体的世界直接崩溃。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| EntityArmorStand | 996 | extends EntityLivingBase | 盔甲架：5 槽装备存取、六部位姿态（DataWatcher 10-16 位）、punch 两击破坏逻辑 |
| EntityBoat | 621 | extends Entity | 船：浮力采样、乘骑驱动、客户端位置插值、撞击/坠落掉落木板与木棍 |
| EntityEnderCrystal | 118 | extends Entity | 末影水晶：innerRotation 递增供渲染，被攻击即爆炸（威力 6.0F），End 维度脚下点火 |
| EntityEnderEye | 237 | extends Entity | 末影之眼：飞向 moveTowards 目标，80 tick 后掉落物品或碎裂（服务端逻辑） |
| EntityEnderPearl | 108 | extends EntityThrowable | 末影珍珠：onImpact 传送投掷者、5% 生成 Endermite、落地伤害 5.0F |
| EntityExpBottle | 64 | extends EntityThrowable | 经验瓶：onImpact 按 getXPSplit 拆分生成 EntityXPOrb |
| EntityFallingBlock | 324 | extends Entity | 下落方块：首 tick 移除原方块，落地转回方块并搬运 TileEntityData，砧板可伤实体 |
| EntityFireworkRocket | 216 | extends Entity | 烟花火箭：lifetime 到期后 setEntityState((byte)17)，客户端 handleStatusUpdate 触发 makeFireworks |
| EntityItem | 543 | extends Entity | 掉落物：DataWatcher 槽 10 存 ItemStack，合并邻近同类、6000 tick 消失、玩家碰撞拾取 |
| EntityItemFrame | 269 | extends EntityHanging | 展示框：DataWatcher 槽 8 显示物品、槽 9 旋转（mod 8），破坏时掉落并清除地图标记 |
| EntityMinecart | 1180 | extends Entity implements IWorldNameable | 矿车基类：轨道物理（matrix 查表）、碰撞推挤、DisplayTile、EnumMinecartType 工厂 |
| EntityMinecartChest | 69 | extends EntityMinecartContainer | 箱子矿车：27 格，GUI id "minecraft:chest"，销毁掉落 chest |
| EntityMinecartContainer | 283 | extends EntityMinecart implements ILockableContainer | 容器矿车基类：36 格数组、NBT "Items" 序列化、setDead 时倾倒内容、按红石信号计算 drag |
| EntityMinecartEmpty | 69 | extends EntityMinecart | 可乘坐矿车：interactFirst 上车，激活铁轨通电时弹出乘客 |
| EntityMinecartFurnace | 203 | extends EntityMinecart | 动力矿车：fuel/pushX/pushZ 推进，coal 加 3600 tick 燃料，DataWatcher 16 位标记点燃 |
| EntityMinecartHopper | 238 | extends EntityMinecartContainer implements IHopper | 漏斗矿车：5 格，onUpdate 经 TileEntityHopper.captureDroppedItems 吸取物品，激活铁轨阻断 |
| EntityMinecartTNT | 225 | extends EntityMinecart | TNT 矿车：fuse 80 tick，撞击/火矢/坠落触发 explodeCart，状态字节 10 同步点燃 |
| EntityPainting | 187 | extends EntityHanging | 画：EnumArt 26 种图案（title/sizeX/sizeY/offsetX/offsetY），放置时随机挑可容纳的图案 |
| EntityTNTPrimed | 129 | extends Entity | 点燃的 TNT：fuse 从 80 递减，归零后服务端 createExplosion(4.0F) |
| EntityXPOrb | 279 | extends Entity | 经验球：8 格内追踪最近玩家，碰撞加经验，getXPSplit/getTextureByXP 静态分档 |

## 核心类详解

### EntityItem（EntityItem.java）
- 关键字段：`private int age`、`private int delayBeforeCanPickup`、`private int health`（初始 5）、`private String thrower`、`private String owner`、`public float hoverStart`（`EntityItem.java:27-36`）。ItemStack 本体不在字段里，而在 DataWatcher 槽 10（`entityInit` 处 `addObjectByDataType(10, 5)`，`EntityItem.java:75-78`）。
- `public void onUpdate()`（`EntityItem.java:83`）：递减 pickup delay、重力 `motionY -= 0.03999999910593033D`、`pushOutOfBlocks` 结果写入 `noClip`（`EntityItem.java:102`）、跨过方块边界或每 25 tick 检查岩浆弹跳并 `searchForOtherItemsNearby()`（仅服务端，`EntityItem.java:116-119`）、`age >= 6000` 时 `setDead()`（`EntityItem.java:145-148`）。
- `private boolean combineItems(EntityItem other)`（`EntityItem.java:167`）：物品、NBT、metadata 全等且总量不超 maxStackSize 才合并；小堆并入大堆，自己 `setDead()`。
- `public void onCollideWithPlayer(EntityPlayer entityIn)`（`EntityItem.java:363`）：服务端拾取入口，owner 限制（`this.owner == null || 6000 - this.age <= 200 || this.owner.equals(entityIn.getName())`，`EntityItem.java:370`），成功后触发成就、播 "random.pop"、`entityIn.onItemPickup(this, i)`。
- `public ItemStack getEntityItem()`（`EntityItem.java:455`）：DataWatcher 为 null 时打 error 日志并返回 `new ItemStack(Blocks.stone)` 兜底（`EntityItem.java:459-467`）。
- 特殊值：`delayBeforeCanPickup == 32767` 表示永不可拾取，`age == -32768` 表示永不消失（`EntityItem.java:93/138`）。

### EntityMinecart（EntityMinecart.java）
- 关键字段：`private static final int[][][] matrix`（10 种轨道朝向的方向查表，`EntityMinecart.java:39`）、客户端插值字段 `turnProgress/minecartX/minecartY/minecartZ/minecartYaw/minecartPitch` 与 `velocityX/velocityY/velocityZ`（`EntityMinecart.java:42-50`）、`private String entityName`。
- `public static EntityMinecart getMinecart(World worldIn, double x, double y, double z, EntityMinecart.EnumMinecartType type)`（`EntityMinecart.java:59`）：类型工厂，被 `NetHandlerPlayClient.handleSpawnObject` 调用（`NetHandlerPlayClient.java:307`）。
- `public void onUpdate()`（`EntityMinecart.java:241`）：先衰减 rolling/damage；`posY < -64.0D` 时 `kill()`；服务端处理 portal 计时；`worldObj.isRemote` 分支只做 turnProgress 插值（`EntityMinecart.java:310-329`）；非 remote 分支执行轨道物理 `func_180460_a` 或 `moveDerailedMinecart()`，activator rail 上调用 `onActivatorRailPass`（`EntityMinecart.java:348-356`），最后对周边矿车 `applyEntityCollision`。
- `protected void func_180460_a(BlockPos p_180460_1_, IBlockState p_180460_2_)`（`EntityMinecart.java:451`）：核心轨道物理——golden_rail 加速/停车、ascending 分支、matrix 投影对齐轨道、速度上限 `getMaximumSpeed()`（0.4D，`EntityMinecart.java:412-415`）。
- `public void applyEntityCollision(Entity entityIn)`（`EntityMinecart.java:877`）：服务端专属；移动中的 RIDEABLE 矿车可自动让非玩家生物上车（`EntityMinecart.java:885-888`）；矿车间按 FURNACE 类型区分动量传递。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（`EntityMinecart.java:153`）：damage 累加 `amount * 10.0F`，超过 40.0F 即 `killMinecart(source)`；创造模式玩家攻击且矿车无自定义名时直接 `setDead()`（无掉落），否则同样走 `killMinecart`。
- DataWatcher 槽位：17 rollingAmplitude(int)、18 rollingDirection(int)、19 damage(float)、20 displayTile stateId(int)、21 displayTileOffset(int)、22 hasDisplayTile(byte)（`EntityMinecart.java:95-103`）。

### EntityArmorStand（EntityArmorStand.java）
- 关键字段：`private final ItemStack[] contents`（长度 5：0 手持，1-4 盔甲）、`private long punchCooldown`、`private int disabledSlots`、六个 `Rotations` 姿态字段及各自 DEFAULT 常量（`EntityArmorStand.java:28-48`）。
- DataWatcher：槽 10 状态字节（bit 1 Small / 2 NoGravity / 4 ShowArms / 8 NoBasePlate / 16 Marker，见 `setSmall` 等，`EntityArmorStand.java:811-921`），槽 11-16 六个部位 `Rotations`（`EntityArmorStand.java:79-89`）。
- `public boolean interactAt(EntityPlayer player, Vec3 targetVec3)`（`EntityArmorStand.java:371`）：按点击高度 `targetVec3.yCoord` 选槽位换装，服务端执行；`hasMarker()` 时直接返回 false。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（`EntityArmorStand.java:515`）：非常规——始终返回 false；玩家两次快速 punch（间隔 ≤5 tick 世界时间）才 `dropBlock()` + `setDead()`（`EntityArmorStand.java:580-591`）。
- `public void onUpdate()`（`EntityArmorStand.java:693`）：每 tick 从 DataWatcher 读回六个 Rotations（客户端同步姿态），并在 Marker 状态切换时经 `func_181550_a` 把尺寸置为 0x0 或 0.5x1.975（`EntityArmorStand.java:757-773`）。

### EntityBoat（EntityBoat.java）
- 关键字段：`private boolean isBoatEmpty`（true=无人）、`private double speedMultiplier`（0.07 起步，最高 0.35）、插值字段 `boatPosRotationIncrements/boatX/boatY/boatZ/boatYaw/boatPitch`、`velocityX/velocityY/velocityZ`（`EntityBoat.java:24-34`）。
- `public void onUpdate()`（`EntityBoat.java:231`）：把包围盒纵向切成 5 片采样水面占比 d0 决定浮力；`worldObj.isRemote && isBoatEmpty` 时只做服务器同步插值（`EntityBoat.java:290-322`），否则执行本地物理——乘客 `moveStrafing/moveForward` 驱动（`EntityBoat.java:340-346`）、碾碎 snow_layer/waterlily（`EntityBoat.java:388-397`）、高速撞墙时服务端拆船掉 3 planks + 2 stick（`EntityBoat.java:410-428`）。
- `public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean p_180426_10_)`（`EntityBoat.java:171`）：网络位置同步入口；有乘客且 p_180426_10_ 为 true 时直接硬设位置。
- `public boolean interactFirst(EntityPlayer playerIn)`（`EntityBoat.java:514`）：服务端 `playerIn.mountEntity(this)` 上船。
- DataWatcher：17 timeSinceHit(int)、18 forwardDirection(int)、19 damageTaken(float)（`EntityBoat.java:54-59`）；damageTaken 超 40.0F 拆船（`EntityBoat.java:129`）。
- `isBoatEmpty` 由 `NetHandlerPlayClient.handleEntityAttach` 维护（`NetHandlerPlayClient.java:980-989`）。

### EntityFallingBlock（EntityFallingBlock.java）
- 关键字段：`private IBlockState fallTile`、`public int fallTime`、`public boolean shouldDropItem = true`、`private boolean hurtEntities`、`private int fallHurtMax = 40`、`private float fallHurtAmount = 2.0F`、`public NBTTagCompound tileEntityData`（`EntityFallingBlock.java:27-34`）。
- `public void onUpdate()`（`EntityFallingBlock.java:80`）：`fallTime++ == 0` 的首 tick 把原位置 `setBlockToAir`（`EntityFallingBlock.java:94-107`）；服务端落地后 `setBlockState(blockpos1, this.fallTile, 3)` 并回调 `BlockFalling.onEndFalling`、合并 `tileEntityData` 到新 TileEntity（`EntityFallingBlock.java:129-160`）；无法放置或超时（fallTime > 100 且 y 越界，或 > 600）则掉落物品化（`EntityFallingBlock.java:162-177`）。
- `public void fall(float distance, float damageMultiplier)`（`EntityFallingBlock.java:182`）：hurtEntities 时按 `(float)Math.min(MathHelper.floor_float((float)i * this.fallHurtAmount), this.fallHurtMax)` 砸伤 AABB 内实体，砧板另有损坏概率递增 `BlockAnvil.DAMAGE`。

### EntityXPOrb（EntityXPOrb.java）
- 关键字段：`public int xpColor`、`public int xpOrbAge`、`public int delayBeforeCanPickup`、`private int xpOrbHealth = 5`、`private int xpValue`、`private EntityPlayer closestPlayer`、`private int xpTargetColor`（`EntityXPOrb.java:17-33`）。
- `public void onUpdate()`（`EntityXPOrb.java:86`）：以 `xpTargetColor < this.xpColor - 20 + this.getEntityId() % 100` 节流地重找 8 格内最近玩家（`EntityXPOrb.java:111-119`），向其加速（二次方衰减，`EntityXPOrb.java:126-141`）；`xpOrbAge >= 6000` 消失。
- `public void onCollideWithPlayer(EntityPlayer entityIn)`（`EntityXPOrb.java:232`）：服务端；`entityIn.xpCooldown == 0` 时置 2、播 "random.orb"、`entityIn.addExperience(this.xpValue)`。
- `public static int getXPSplit(int expValue)`（`EntityXPOrb.java:267`）与 `public int getTextureByXP()`（`EntityXPOrb.java:259`）：静态分档表，前者被 `EntityExpBottle.onImpact` 使用（`EntityExpBottle.java:56`）。

### EntityTNTPrimed（EntityTNTPrimed.java）
- 关键字段：`public int fuse`（构造时 80）、`private EntityLivingBase tntPlacedBy`（`EntityTNTPrimed.java:12-13`）。
- `public void onUpdate()`（`EntityTNTPrimed.java:61`）：简单抛物运动；`if (this.fuse-- <= 0)` 时 `setDead()`，服务端调用 `private void explode()`（`EntityTNTPrimed.java:95`，`createExplosion(this, ..., 4.0F, true)`）；否则每 tick 冒 SMOKE_NORMAL 粒子。
- NBT 仅 `"Fuse"` 一个 byte（`EntityTNTPrimed.java:104-115`）。注意：fuse 是 public 字段，客户端渲染 `RenderTNTPrimed` 直接读它做闪烁。

### EntityItemFrame（EntityItemFrame.java）
- 关键字段：`private float itemDropChance = 1.0F`（`EntityItemFrame.java:20`）。展示物品在 DataWatcher 槽 8（ItemStack），旋转在槽 9（byte，mod 8）（`EntityItemFrame.java:33-37`）。
- `public boolean interactFirst(EntityPlayer playerIn)`（`EntityItemFrame.java:241`）：空框放手持物品，非空框服务端 `setItemRotation(this.getRotation() + 1)`。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（`EntityItemFrame.java:47`）：非爆炸伤害且有展示物时只掉物品不掉框。
- `private void removeFrameFromMap(ItemStack p_110131_1_)`（`EntityItemFrame.java:132`）：filled_map 从 `MapData.mapDecorations` 移除 `"frame-" + this.getEntityId()`。
- `setDisplayedItemWithUpdate` / `func_174865_a` 变更后调用 `this.worldObj.updateComparatorOutputLevel(this.hangingPosition, Blocks.air)` 通知比较器（`EntityItemFrame.java:170/193`）。

### EntityFireworkRocket（EntityFireworkRocket.java）
- 关键字段：`private int fireworkAge`、`private int lifetime`（`10 * i + this.rand.nextInt(6) + this.rand.nextInt(7)`，i = 1 + NBT "Fireworks"."Flight"，`EntityFireworkRocket.java:13-18, 46-63`）；烟花 ItemStack 存 DataWatcher 槽 8。
- `public void onUpdate()`（`EntityFireworkRocket.java:86`）：`motionX *= 1.15D; motionZ *= 1.15D; motionY += 0.04D` 加速上升；age 0 时播 "fireworks.launch"；服务端 age 超 lifetime 时 `this.worldObj.setEntityState(this, (byte)17)` 后 `setDead()`（`EntityFireworkRocket.java:134-138`）。
- `public void handleStatusUpdate(byte id)`（`EntityFireworkRocket.java:141`）：id == 17 且 isRemote 时读槽 8 的 NBT "Fireworks" 调 `this.worldObj.makeFireworks(...)` 产生爆炸粒子——这是客户端唯一的爆炸表现路径。

### EntityMinecartContainer（EntityMinecartContainer.java）
- 关键字段：`private ItemStack[] minecartContainerItems = new ItemStack[36]`（读 NBT 时重建为 `getSizeInventory()` 大小，`EntityMinecartContainer.java:16, 212`）、`private boolean dropContentsWhenDead = true`（`EntityMinecartContainer.java:22`）。
- `public void setDead()`（`EntityMinecartContainer.java:173`）：dropContentsWhenDead 时 `InventoryHelper.dropInventoryItems`；`travelToDimension` 先把标志置 false 以便跨维度保留内容（`EntityMinecartContainer.java:164-168`）。
- `public boolean interactFirst(EntityPlayer playerIn)`（`EntityMinecartContainer.java:229`）：服务端 `playerIn.displayGUIChest(this)` 打开 GUI。
- `protected void applyDrag()`（`EntityMinecartContainer.java:239`）：`int i = 15 - Container.calcRedstoneFromInventory(this); float f = 0.98F + (float)i * 0.001F;` —— 内容越多 i 越小、f 越小，阻力越大（矿车越满减速越快）。

## 时序与生命周期

- 创建：客户端一律由 `NetHandlerPlayClient.handleSpawnObject`（Netty EventLoop 收包 → `PacketThreadUtil.checkThreadAndEnqueue` 转主线程后执行）实例化并 `addEntityToWorld`；矿车经 `EntityMinecart.getMinecart` 工厂 + `EnumMinecartType.byNetworkID`。集成服务器侧由方块 tick / 物品使用创建并 `spawnEntityInWorld`。
- 每 tick：`World.updateEntities` → 各类 `onUpdate()`。共同模式是"isRemote 分支只做插值/粒子，权威物理与判死在服务端分支"：
  - `EntityMinecart.onUpdate`（:241）与 `EntityBoat.onUpdate`（:231）客户端消费 `setPositionAndRotation2` 存下的目标位姿做 turnProgress/boatPosRotationIncrements 线性插值。
  - `EntityItem`、`EntityXPOrb`、`EntityTNTPrimed`、`EntityFallingBlock` 两端都跑完整物理（同一套确定性代码），但 setDead/放方块/爆炸/拾取仅服务端。
  - `EntityEnderEye.onUpdate`（:106）中目标追踪与消亡在 `!this.worldObj.isRemote` 块内；粒子两端都发（客户端 World.spawnParticle 才有效果）。
- 每帧：本包不含渲染代码；渲染插值用 `prevPosX/prevPosY/prevPosZ`、`prevRotationYaw/prevRotationPitch` 以及 `EntityEnderCrystal.innerRotation`、`EntityItem.hoverStart`、`EntityTNTPrimed.fuse`、`EntityXPOrb.xpColor` 等公开字段，由对应 Renderer 在渲染线程（=主线程）读取。
- 消亡：`setDead()` 后由 `World.updateEntities` 下一轮移除。`EntityMinecartContainer.setDead` 有倾倒副作用（:173-181）。
- 线程归属：全部逻辑在主线程（客户端 tick 线程 / 集成服务端线程各自的世界）。Netty EventLoop 不直接触碰这些类，收包处理已被排队到主线程。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void onUpdate()` | EntityItem.java:83 | 每 tick，World.updateEntities | 掉落物 ESP/计时、修改重力与合并、物品追踪 | age/delayBeforeCanPickup 有魔数语义（32767/-32768） |
| `public void onCollideWithPlayer(EntityPlayer entityIn)` | EntityItem.java:363 | 玩家 AABB 与掉落物相交时 | 拾取过滤、自动拾取通知、统计 | 仅服务端生效（isRemote 直接跳过） |
| `public ItemStack getEntityItem()` | EntityItem.java:455 | 渲染器与拾取逻辑 | 观察/替换展示的物品 | null 时兜底返回 stone，别假定与服务器一致 |
| `private boolean combineItems(EntityItem other)` | EntityItem.java:167 | searchForOtherItemsNearby（服务端） | 禁用/自定义堆叠合并 | 私有；改语义会导致地面物品数不同步 |
| `public void onUpdate()` | EntityMinecart.java:241 | 每 tick | 矿车速度修改、轨道物理接管、derail 检测 | isRemote 分支只是插值，物理只在服务端分支 |
| `protected void func_180460_a(BlockPos p_180460_1_, IBlockState p_180460_2_)` | EntityMinecart.java:451 | onUpdate 在轨道方块上时 | 改写在轨物理（加速上限、弯道行为） | matrix 查表与 posY 修正耦合紧密 |
| `public void onActivatorRailPass(int x, int y, int z, boolean receivingPower)` | EntityMinecart.java:420（子类覆写 EntityMinecartEmpty.java:46、EntityMinecartTNT.java:137、EntityMinecartHopper.java:75） | 每 tick 在 activator rail 上 | 自定义激活铁轨行为 | 基类为空实现，逐子类语义不同 |
| `public void applyEntityCollision(Entity entityIn)` | EntityMinecart.java:877 | 实体互相推挤时 | 取消/修改矿车推挤与自动载客 | 仅服务端；含生物自动上车副作用（:885-888） |
| `public boolean attackEntityFrom(DamageSource source, float amount)` | EntityMinecart.java:153 | 被攻击时 | 一击破矿车、保护矿车 | damage 是 DataWatcher 值 ×10 累加，阈值 40 |
| `public void killMinecart(DamageSource source)` | EntityMinecart.java:195 | attackEntityFrom 判死后；子类链式覆写 | 拦截矿车掉落物 | 子类各自追加 drop（chest/furnace/tnt/hopper） |
| `public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean p_180426_10_)` | EntityMinecart.java:970、EntityBoat.java:171、EntityPainting.java:136 | 收到 S18PacketEntityTeleport / S14PacketEntity 后（主线程） | 观察/改写服务器位置同步（反回拉、插值平滑） | 直接改会与服务器权威位置漂移 |
| `public void setVelocity(double x, double y, double z)` | EntityBoat.java:221、EntityMinecart.java:986、EntityEnderEye.java:89、EntityFireworkRocket.java:69 | 收到 S12PacketEntityVelocity 后 | 观察/钳制服务器速度同步 | 部分实现顺带初始化朝向 |
| `public boolean interactFirst(EntityPlayer playerIn)` | EntityBoat.java:514、EntityMinecartEmpty.java:22、EntityMinecartContainer.java:229、EntityMinecartFurnace.java:141、EntityMinecartHopper.java:62、EntityItemFrame.java:241 | 玩家右键实体 | 拦截上车/开 GUI/放物品/加燃料 | 返回 true 即消费交互；容器 GUI 只在服务端打开 |
| `public boolean interactAt(EntityPlayer player, Vec3 targetVec3)` | EntityArmorStand.java:371 | 玩家右键盔甲架（带命中点向量） | 自定义换装逻辑、盔甲架编辑器 | 高度判定依赖 isSmall 缩放；Marker 直接不可交互 |
| `public void onUpdate()` | EntityArmorStand.java:693 | 每 tick | 读取/覆写六部位姿态（动画盔甲架） | 姿态 setter 同时写 DataWatcher，客户端写会被服务器覆盖 |
| `public void handleStatusUpdate(byte id)` | EntityFireworkRocket.java:141、EntityMinecartTNT.java:145 | 收到 S19PacketEntityStatus（id 17=烟花爆炸，10=TNT 矿车点燃） | 拦截爆炸特效/点燃提示 | 客户端专属视觉路径，勿在此做游戏逻辑 |
| `public void ignite()` | EntityMinecartTNT.java:160 | 激活铁轨、状态包 10、火焰 | 观察/取消 TNT 矿车点燃 | 服务端调用会广播状态字节并播声音 |
| `protected void explodeCart(double p_94103_1_)` | EntityMinecartTNT.java:107 | fuse 归零、高速撞击、火矢、坠落 | 爆炸威力修改/取消 | 仅服务端；威力随速度平方根缩放，上限 5.0D |
| `public void onUpdate()` | EntityTNTPrimed.java:61 | 每 tick | TNT 计时 HUD、爆炸预测 | fuse 为 public，可直接读；explode 私有且仅服务端 |
| `public void onUpdate()` | EntityFallingBlock.java:80 | 每 tick | 下落方块可视化、防砸提示 | 首 tick 有"吃掉原方块"副作用（:94-107） |
| `public void onCollideWithPlayer(EntityPlayer entityIn)` | EntityXPOrb.java:232 | 玩家碰到经验球 | 经验统计/拾取拦截 | 受 `entityIn.xpCooldown`（2 tick）节流 |
| `protected void onImpact(MovingObjectPosition p_70184_1_)` | EntityEnderPearl.java:37、EntityExpBottle.java:47 | EntityThrowable 命中方块/实体 | 珍珠传送预测、取消落地伤害 | 传送与 5.0F 摔落伤害均在服务端块内 |
| `public void moveTowards(BlockPos p_180465_1_)` | EntityEnderEye.java:60 | ItemEnderEye 使用时（服务端） | 要塞定位辅助（读 targetX/targetZ 需反射，字段私有） | shatterOrDrop 为随机 4/5 概率 |
| `public boolean isInRangeToRenderDist(double distance)` | EntityArmorStand.java:608、EntityEnderEye.java:39、EntityFireworkRocket.java:35、EntityItemFrame.java:83 | RenderManager 剔除判定 | 强制渲染距离（ESP 类功能） | 每帧高频调用，保持轻量 |
| `public void setDead()` | EntityMinecartContainer.java:173 | 实体销毁 | 监听容器矿车销毁与内容倾倒 | 有倾倒副作用；travelToDimension 会先关掉它 |
| `public boolean attackEntityFrom(DamageSource source, float amount)` | EntityEnderCrystal.java:92 | 水晶被任何攻击 | 水晶爆炸预警（威力 6.0F） | 一击必炸，无血量衰减（health 直接置 0） |

## 数据与协议

### DataWatcher 槽位（客户端-服务端实体元数据同步）

| 类 | 槽位 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|---|
| EntityItem | 10 | ItemStack | getEntityItem()/setEntityItemStack() | 展示与拾取的物品堆 |
| EntityXPOrb | — | — | — | 无 DataWatcher 对象（entityInit 为空，xpValue 走专用 spawn 包） |
| EntityMinecart | 17 | int | getRollingAmplitude()/setRollingAmplitude() | 受击晃动幅度 |
| EntityMinecart | 18 | int | getRollingDirection()/setRollingDirection() | 晃动方向 ±1 |
| EntityMinecart | 19 | float | getDamage()/setDamage() | 累计伤害，>40 拆车 |
| EntityMinecart | 20 | int | getDisplayTile()/func_174899_a() | 展示方块 Block.getStateId |
| EntityMinecart | 21 | int | getDisplayTileOffset()/setDisplayTileOffset() | 展示方块 Y 偏移 |
| EntityMinecart | 22 | byte | hasDisplayTile()/setHasDisplayTile() | 是否有自定义展示方块 |
| EntityMinecartFurnace | 16 | byte | isMinecartPowered()/setMinecartPowered() | bit0 = 点燃中 |
| EntityBoat | 17 | int | getTimeSinceHit()/setTimeSinceHit() | 受击倒计时 |
| EntityBoat | 18 | int | getForwardDirection()/setForwardDirection() | 晃动方向 |
| EntityBoat | 19 | float | getDamageTaken()/setDamageTaken() | 累计伤害，>40 拆船 |
| EntityArmorStand | 10 | byte | isSmall()/hasNoGravity()/getShowArms()/hasNoBasePlate()/hasMarker() | bit1/2/4/8/16 状态位 |
| EntityArmorStand | 11-16 | Rotations | getHeadRotation()...setRightLegRotation() | 头/身/左臂/右臂/左腿/右腿姿态 |
| EntityItemFrame | 8 | ItemStack | getDisplayedItem()/setDisplayedItem() | 框内物品 |
| EntityItemFrame | 9 | byte | getRotation()/setItemRotation() | 旋转 0-7 |
| EntityFireworkRocket | 8 | ItemStack | (dataWatcher 直接读写) | 烟花物品（含 "Fireworks" NBT） |
| EntityEnderCrystal | 8 | int | (onUpdate 每 tick updateObject) | health（客户端渲染无实际用途） |

### NBT 持久化关键字段（节选）

| 类 | NBT key | 类型 | 含义 |
|---|---|---|---|
| EntityItem | "Health"/"Age"/"PickupDelay"/"Thrower"/"Owner"/"Item" | short×3, string×2, compound | 见 writeEntityToNBT（EntityItem.java:306） |
| EntityXPOrb | "Health"/"Age"/"Value" | short | 经验值存 short（EntityXPOrb.java:212-217） |
| EntityFallingBlock | "Block"/"Data"/"Time"/"DropItem"/"HurtEntities"/"FallHurtAmount"/"FallHurtMax"/"TileEntityData" | string, byte, byte, bool, bool, float, int, compound | 读侧兼容旧格式 "TileID"/"Tile"（EntityFallingBlock.java:247-258） |
| EntityMinecart | "CustomDisplayTile"/"DisplayTile"/"DisplayData"/"DisplayOffset"/"CustomName" | bool, string, int, int, string | EntityMinecart.java:811-872 |
| EntityMinecartContainer | "Items"（list of compound，含 "Slot" byte） | list | EntityMinecartContainer.java:186-224 |
| EntityMinecartFurnace | "PushX"/"PushZ"/"Fuel" | double, double, short | EntityMinecartFurnace.java:163-180 |
| EntityMinecartTNT | "TNTFuse" | int | EntityMinecartTNT.java:207-224 |
| EntityTNTPrimed | "Fuse" | byte | EntityTNTPrimed.java:104-115 |
| EntityArmorStand | "Equipment"(list)/"Invisible"/"Small"/"ShowArms"/"DisabledSlots"/"NoGravity"/"NoBasePlate"/"Marker"/"Pose"{Head,Body,LeftArm,RightArm,LeftLeg,RightLeg} | 混合 | EntityArmorStand.java:160-227 |
| EntityItemFrame | "Item"/"ItemRotation"/"ItemDropChance" | compound, byte, float | EntityItemFrame.java:200-236 |
| EntityPainting | "Motive" | string | EnumArt.title 匹配，找不到回退 KEBAB（EntityPainting.java:76-94） |
| EntityFireworkRocket | "Life"/"LifeTime"/"FireworksItem" | int, int, compound | EntityFireworkRocket.java:162-194 |
| EntityBoat / EntityEnderCrystal / EntityEnderEye | — | — | write/readEntityToNBT 为空实现，无持久化数据 |

### 协议关联

- `EnumMinecartType.getNetworkID()`（EntityMinecart.java:1157）与 `byNetworkID(int id)`（:1167，未知 id 回退 RIDEABLE）对应 S0EPacketSpawnObject 的 data 字段，消费点 `NetHandlerPlayClient.java:307`。
- 实体状态字节（S19PacketEntityStatus）：17 = 烟花爆炸（EntityFireworkRocket.java:136/143），10 = TNT 矿车点燃（EntityMinecartTNT.java:147/166）。
- `EntityPainting.EnumArt` 的 title 字符串同时是网络标识（spawn painting 包按 title 匹配）与贴图 UV 表（offsetX/offsetY）。

## 不变量与陷阱

- 判权威的唯一依据是 `this.worldObj.isRemote`：所有 setDead、爆炸、方块变更、掉落、传送都必须只在服务端分支执行；客户端分支仅粒子、声音、插值。给这些方法打钩子时保持该约束，否则出现客户端鬼影实体。
- `EntityItem` 的 ItemStack 真身在 DataWatcher 槽 10 而非 Java 字段；`getEntityItem()` 在 null 时打 error 日志后返回 stone 兜底（EntityItem.java:463-466），检测"空掉落物"不要用 `!= null` 判断。
- `EntityItem.delayBeforeCanPickup == 32767` 与 `age == -32768` 是魔数哨兵值（永不可拾取/永不过期），任何对这两个字段的算术操作都要绕开哨兵。
- `EntityArmorStand.attackEntityFrom` 永远返回 false 且自带两击破坏状态机（punchCooldown 用 `worldObj.getTotalWorldTime()` 比较），不要按普通生物的伤害语义对待；`setInvisible` 同时改 `canInteract`（EntityArmorStand.java:784-788），语义纠缠。
- `EntityMinecart.setPosition` 覆写了基类（EntityMinecart.java:682），包围盒底部贴 posY 而不是居中——继承或反射设置位置时注意。
- `EntityMinecartContainer.setDead()` 有倾倒物品副作用；跨维度必须走 `travelToDimension`（先置 `dropContentsWhenDead = false`）否则内容会掉一地。
- `EntityMinecartContainer.minecartContainerItems` 初始 36 格，但 readEntityFromNBT 会按 `getSizeInventory()` 重建（漏斗 5 格、箱子 27 格）；新建后未读 NBT 前数组尺寸与逻辑容量不一致。
- `EntityBoat` 的客户端物理分支由 `isBoatEmpty` 门控，而该标志由 `NetHandlerPlayClient.handleEntityAttach` 维护——本地玩家上船后船体物理在客户端本地跑（1.8 船的"客户端权威"特例），修改移动相关代码时这是反作弊敏感点。
- `EntityFallingBlock.onUpdate` 首 tick 把原方块 setBlockToAir，且客户端也会执行这一步（EntityFallingBlock.java:94-101 不在 isRemote 保护内，仅"方块不匹配即 setDead"那半句限定服务端）；重放/预测逻辑要考虑这个副作用。
- `EntityEnderCrystal.onUpdate` 每 tick 无条件 `dataWatcher.updateObject(8, health)`（EntityEnderCrystal.java:56），以及 End 维度里每 tick 尝试放火（:61-64），是已知的原版低效写法，勿"顺手优化"导致行为差异。
- 烟花爆炸只有客户端表现（handleStatusUpdate id 17）；如果拦截了 `Entity.handleStatusUpdate` 分发链，烟花会静默消失。
- 线程约束：所有类都假定单线程访问（主线程 tick）。DataWatcher 本身有锁，但本包逻辑（如 combineItems 的双向修改）没有任何并发保护，不要从 Netty EventLoop 或后台线程直接调用。
- LWJGL3/JDK25 移植注意：本包为纯逻辑代码，无 GL/输入依赖，未见移植改动痕迹；`new Integer(0)`/`new Byte((byte)0)` 等已废弃的装箱构造在 JDK25 下仅是告警不是错误，若日后清理需全仓统一，避免只改一处引起 review 噪音。

## 交叉引用

- net/minecraft/client/network → NetHandlerPlayClient#handleSpawnObject（实例化本包大部分实体）、#handleSpawnExperienceOrb（EntityXPOrb）、#handleEntityAttach（EntityBoat#setIsBoatEmpty）
- net/minecraft/client/renderer/entity → RenderEntityItem / RenderXPOrb#getTextureByXP / RenderMinecart#getDisplayTile / RenderBoat / RenderTNTPrimed（读 EntityTNTPrimed.fuse）/ RenderFallingBlock#getBlock / RenderPainting（EnumArt UV）/ ArmorStandRenderer（六 Rotations）
- net/minecraft/entity → EntityList#addMapping（"Item"=1 等存档 id 注册）、EntityHanging（EntityItemFrame/EntityPainting 的父类）、EntityLivingBase（EntityArmorStand 父类；moveStrafing/moveForward 驱动船与矿车）
- net/minecraft/entity/projectile → EntityThrowable#onImpact（EntityEnderPearl/EntityExpBottle 的模板方法）、EntityArrow（盔甲架与 TNT 矿车的箭判定）
- net/minecraft/block → BlockRailBase#isRailBlock / BlockRailPowered.POWERED（矿车轨道物理）、BlockFalling#canFallInto/#onEndFalling（EntityFallingBlock）、BlockAnvil.DAMAGE（砧板损坏）
- net/minecraft/inventory → InventoryHelper#dropInventoryItems、Container#calcRedstoneFromInventory、ContainerChest/ContainerHopper（createContainer）
- net/minecraft/tileentity → TileEntityHopper#captureDroppedItems/#putDropInInventoryAllSlots（EntityMinecartHopper#func_96112_aD）、IHopper 接口
- net/minecraft/world → World#createExplosion / #setEntityState / #spawnParticle / #makeFireworks / #playAuxSFX、ILockableContainer/LockCode（EntityMinecartContainer）、WorldProviderEnd（EntityEnderCrystal）
- net/minecraft/entity/player → EntityPlayer#inventory.addItemStackToInventory / #onItemPickup / #addExperience / #displayGUIChest / #mountEntity、EntityPlayerMP（珍珠传送）
- net/minecraft/stats → AchievementList.mineWood/killCow/diamonds/blazeRod/diamondsToYou（EntityItem 拾取成就）
- net/minecraft/util → Rotations（盔甲架姿态的 NBT/DataWatcher 载体）
- net/minecraft/world/storage → MapData#mapDecorations（EntityItemFrame 移除地图标记）

## 覆盖声明

完整读取了 20/20 个文件（每个文件从第 1 行到最后一行经 Read 工具全文读入）。

逐行精读的类：EntityItem、EntityMinecart、EntityArmorStand、EntityBoat、EntityFallingBlock、EntityXPOrb、EntityTNTPrimed、EntityItemFrame、EntityFireworkRocket、EntityMinecartContainer、EntityMinecartTNT、EntityMinecartFurnace、EntityMinecartHopper、EntityEnderEye、EntityEnderPearl、EntityEnderCrystal、EntityPainting、EntityExpBottle、EntityMinecartChest、EntityMinecartEmpty（即全部 20 个；其中 EntityMinecart.func_180460_a / func_70489_a 的矩阵代数细节只核对了结构与常量，未逐项推导几何含义）。

只做结构性浏览的外部文件：NetHandlerPlayClient.java、EntityList.java 与 renderer 目录仅通过 grep 确认引用点，未通读。
