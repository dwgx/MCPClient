---
area: net/minecraft/entity
slug: mc-entity
files: 33
lines: 11593
tier: B
---

# net/minecraft/entity — 实体系统根包

## 定位

本包是实体系统的根：`Entity` 是所有实体（玩家、生物、掉落物、矿车、投掷物……）的基类，`EntityLivingBase` / `EntityLiving` 在其上叠加生命值、药水、装备、AI 调度层。同包还包含三块基础设施：

- **DataWatcher**：实体元数据的键值同步容器，是 S1CPacketEntityMetadata 的数据源，也是客户端反序列化元数据的落点。
- **EntityTracker / EntityTrackerEntry**：集成服务端（本仓库带内置 server 逻辑）向客户端广播实体 spawn/move/metadata/velocity 封包的机制。
- **EntityList / EntitySpawnPlacementRegistry / SharedMonsterAttributes**：实体 类↔名字↔数字ID 注册表、出生位置类型表、通用属性（maxHealth 等）定义。

调用方向：`World#updateEntities` 每 tick 调 `Entity#onUpdate`；`net.minecraft.client.multiplayer.NetHandlerPlayClient` 收包后写入本包对象（位置插值、DataWatcher、生命值）；渲染层（`net.minecraft.client.renderer.entity`）读取本包的 `prev*`/`limbSwing`/`renderYawOffset` 等字段做帧间插值。它调用 `world`（方块碰撞、粒子、声音）、`nbt`（存档）、`network.play.server`（广播封包）、`entity.ai`（任务系统）、`potion`、`item`。若此包消失，整个游戏对象模型不复存在——世界里除了方块什么都没有。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| DataWatcher | 450 | - | 实体元数据（byte/short/int/float/String/ItemStack/BlockPos/Rotations）同步容器，带读写锁与封包序列化 |
| Entity | 2819 | implements ICommandSender | 一切实体的基类：位置/速度/AABB/碰撞移动/火/传送门/NBT/骑乘 |
| EntityAgeable | 253 | extends EntityCreature | 有年龄（growingAge，DataWatcher id 12）能繁殖的生物基类 |
| EntityBodyHelper | 80 | - | 每 tick 把身体朝向 renderYawOffset 渐进对齐到头部朝向 rotationYawHead |
| EntityCreature | 165 | extends EntityLiving | 有 home 位置约束和拴绳拖拽逻辑的生物基类 |
| EntityFlying | 89 | extends EntityLiving | 飞行生物（Ghast 等）基类：无摔落伤害，自定义 moveEntityWithHeading |
| EntityHanging | 299 | extends Entity | 挂在墙上的实体（画/物品展示框）基类：facing + 表面有效性检查 |
| EntityLeashKnot | 172 | extends EntityHanging | 栓在栅栏上的拴绳结；interactFirst 转移附近被拴生物 |
| EntityList | 385 | - | 实体 类↔字符串名↔数字ID 静态注册表 + 反射构造工厂 + 刷怪蛋颜色 |
| EntityLiving | 1310 | extends EntityLivingBase | 有 AI 的生物层：tasks/targetTasks/navigator/装备数组/拴绳/despawn |
| EntityLivingBase | 2306 | extends Entity | 生命体层：血量/伤害计算/药水/属性表/摆臂/移动物理 |
| EntityMinecartCommandBlock | 146 | extends EntityMinecart | 命令方块矿车；命令与输出经 DataWatcher 23/24 同步 |
| EntitySpawnPlacementRegistry | 82 | - | Class → EntityLiving.SpawnPlacementType（ON_GROUND/IN_WATER）静态映射 |
| EntityTracker | 337 | - | 服务端侧：管理全部 EntityTrackerEntry，按实体类型分配追踪距离/频率 |
| EntityTrackerEntry | 646 | - | 单个实体的追踪条目：算增量移动包、构造 spawn 包、发给 trackingPlayers |
| EnumCreatureAttribute | 8 | enum | UNDEFINED/UNDEAD/ARTHROPOD（影响药水与附魔克制） |
| EnumCreatureType | 61 | enum | 自然刷新四大类（MONSTER/CREATURE/AMBIENT/WATER_CREATURE）及其上限/材质 |
| IEntityLivingData | 5 | interface | 空标记接口，onInitialSpawn 在同群生物间传递共享出生数据 |
| IEntityMultiPart | 12 | interface | 多部件实体（龙）：getWorld() + attackEntityFromPart(...) |
| IEntityOwnable | 8 | interface | 有主人的实体：getOwnerId()/getOwner() |
| IMerchant | 31 | interface | 交易者抽象：customer/recipes/useRecipe/verifySellingItem |
| INpc | 7 | interface extends IAnimals | NPC 标记接口（村民实现） |
| IProjectile | 9 | interface | 投掷物：setThrowableHeading(x,y,z,velocity,inaccuracy) |
| IRangedAttackMob | 9 | interface | 远程攻击生物：attackEntityWithRangedAttack(target, f) |
| NpcMerchant | 69 | implements IMerchant | 非实体商人（命令打开的交易 GUI 后端），持 InventoryMerchant |
| SharedMonsterAttributes | 144 | - | 五个通用 IAttribute 常量 + 属性表 NBT 读写工具 |
| boss/BossStatus | 17 | final class | 纯静态字段：GUI Boss 血条读取的 healthScale/bossName/statusBarTime |
| boss/EntityDragon | 781 | extends EntityLiving implements IBossDisplayData, IEntityMultiPart, IMob | 末影龙：ring buffer 动画、7 个部件、水晶回血、死亡生成传送门 |
| boss/EntityDragonPart | 63 | extends Entity | 龙的部件碰撞盒，把 attackEntityFrom 转发给 entityDragonObj |
| boss/EntityWither | 673 | extends EntityMob implements IBossDisplayData, IRangedAttackMob | 凋灵：三头目标（DataWatcher 17-19）、Invul 倒计时（20）、破坏方块 |
| boss/IBossDisplayData | 15 | interface | Boss 血条数据源：getMaxHealth()/getHealth()/getDisplayName() |
| effect/EntityLightningBolt | 130 | extends EntityWeatherEffect | 闪电：点火、雷声、对 AABB 内实体调 onStruckByLightning |
| effect/EntityWeatherEffect | 12 | abstract, extends Entity | 天气效果实体空基类 |

## 核心类详解

### Entity（Entity.java）

万物基类。关键字段（Entity.java:52-244）：

- `private int entityId` — 构造时 `this.entityId = nextEntityID++`（Entity.java:266），客户端收 spawn 包后由 `setEntityId(int id)`（Entity.java:251）覆盖为服务端 id。
- `public World worldObj` / `public double posX, posY, posZ` / `motionX/Y/Z` / `float rotationYaw, rotationPitch` 及对应 `prev*`。
- `private AxisAlignedBB boundingBox`（Entity.java:103）— 位置的真身；`resetPositionToBB()`（Entity.java:941）在移动后从 AABB 反推 posX/Y/Z。
- `public boolean onGround, isCollidedHorizontally, isCollidedVertically, isCollided, velocityChanged, isDead, noClip`。
- `protected DataWatcher dataWatcher`（Entity.java:200）— 构造器里注册 id 0（flags byte）、1（Air short）、2（CustomName String）、3（CustomNameVisible byte）、4（Silent byte）（Entity.java:286-290），随后调 `entityInit()`。

关键方法（签名逐字）：

- `public void onUpdate()`（Entity.java:403）→ `public void onEntityUpdate()`（Entity.java:411）：保存 prev 值、传送门计时、火焰伤害、岩浆、y<-64 调 `kill()`。每 tick 由 `World#updateEntityWithOptionalForce` 调用。
- `public void moveEntity(double x, double y, double z)`（Entity.java:598）：核心碰撞移动——蜘蛛网减速、潜行边缘防掉落（Entity.java:626-694）、逐轴 AABB 裁剪、stepHeight 上台阶、落地/行走音效、`doBlockCollisions()`。所有物理位移都走这里。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（Entity.java:1443）：基类只 `setBeenAttacked()` 并返回 false；子类层层覆写。
- `public void writeToNBT(NBTTagCompound tagCompund)`（Entity.java:1602）/ `public void readFromNBT(NBTTagCompound tagCompund)`（Entity.java:1656）：Pos/Motion/Rotation/Fire/Air/UUID 等，再委托抽象的 `writeEntityToNBT`/`readEntityFromNBT`（Entity.java:1749-1754）。
- `public void mountEntity(Entity entityIn)`（Entity.java:1975）：建立/解除 ridingEntity↔riddenByEntity 双向引用，带循环骑乘检查。
- `protected boolean getFlag(int flag)` / `protected void setFlag(int flag, boolean set)`（Entity.java:2207/2215）：DataWatcher id 0 位域——0 burning、1 sneaking、3 sprinting、4 eating、5 invisible。
- `public MovingObjectPosition rayTrace(double blockReachDistance, float partialTicks)`（Entity.java:1500）：从眼睛沿视线做方块射线检测，客户端选取方块目标的基础。
- `public void travelToDimension(int dimensionId)`（Entity.java:2457）：服务端跨维度传送（销毁旧实体、NBT 复制到新世界实例）。

### EntityLivingBase（EntityLivingBase.java）

生命体层。关键字段：`private BaseAttributeMap attributeMap`（:58）、`private final CombatTracker _combatTracker`（:59）、`private final Map<Integer, PotionEffect> activePotionsMap`（:60）、渲染插值字段 `swingProgress/limbSwing/limbSwingAmount/renderYawOffset/rotationYawHead`（:86-104）、服务端位置插值目标 `newPosX/newPosY/newPosZ/newRotationYaw/newRotationPitch/newPosRotationIncrements`（:153-166）。DataWatcher 注册：6=health(Float)、7=potion 颜色(Int)、8=ambient(Byte)、9=arrowCount(Byte)（`protected void entityInit()`，EntityLivingBase.java:211-217）。

- `public void onUpdate()`（EntityLivingBase.java:1784）：服务端同步装备变化（S04PacketEntityEquipment，:1814）与箭计数衰减，然后调 `this.onLivingUpdate()`（:1836），最后做 renderYawOffset/头身角度归一化。
- `public void onLivingUpdate()`（EntityLivingBase.java:1948）：客户端位置插值（newPosRotationIncrements）、微小速度清零、`updateEntityActionState()`（AI）、跳跃、`moveEntityWithHeading(this.moveStrafing, this.moveForward)`（:2034）、`collideWithNearbyEntities()`。
- `public void onEntityUpdate()`（EntityLivingBase.java:264）：溺水/边界墙伤害、hurtTime/deathTime 计时、`updatePotionEffects()`。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（EntityLivingBase.java:863）：服务端专属（`worldObj.isRemote` 直接 false，:869）；hurtResistantTime 内只结算超出 lastDamage 的差值；随后 knockback、`worldObj.setEntityState(this, (byte)2)`、死亡走 `onDeath(source)`。
- `protected void damageEntity(DamageSource damageSrc, float damageAmount)`（EntityLivingBase.java:1276）：护甲→药水→吸收 三段减伤后 `setHealth`。
- `public final float getHealth()`（:850）/ `public void setHealth(float health)`（:855）：血量完全存在 DataWatcher id 6，客户端血量即由 metadata 包驱动。
- `public void moveEntityWithHeading(float strafe, float forward)`（EntityLivingBase.java:1602）：陆地/水/岩浆三套移动物理（slipperiness 0.91、重力 -0.08、阻尼 0.98——反作弊/移动模块关心的所有常数都在这）。
- `public void swingItem()`（EntityLivingBase.java:1342）：启动摆臂并在服务端广播 S0BPacketAnimation。
- `public void handleStatusUpdate(byte id)`（EntityLivingBase.java:1356）：客户端处理 S19（entity status）：2=受伤动画+音效，3=死亡。
- `public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean p_180426_10_)`（EntityLivingBase.java:2111）：收到 S14/S18 移动包后设置 3-tick 插值目标（覆写了 Entity.java:2013 的立即落位版本）。
- `public void addPotionEffect(PotionEffect potioneffectIn)`（:745）、`protected void updatePotionEffects()`（:609）：药水生命周期；颜色写 DataWatcher 7/8。

### EntityLiving（EntityLiving.java）

AI 生物层。关键字段：`protected final EntityAITasks tasks` / `targetTasks`（EntityLiving.java:55-58）、`protected PathNavigate navigator`（:52）、`private ItemStack[] equipment = new ItemStack[5]`（:65，0=手持 1-4=甲）、`private boolean isLeashed; private Entity leashedToEntity`（:75-76）。DataWatcher 15 = NoAI byte（:174）。

- `protected final void updateEntityActionState()`（EntityLiving.java:619）：AI 主循环——`despawnEntity()` → `senses.clearSensingCache()` → `targetTasks.onUpdateTasks()` → `tasks.onUpdateTasks()` → `navigator.onUpdateNavigation()` → `updateAITasks()` → moveHelper/lookHelper/jumpHelper。仅当 `isServerWorld()` 时由 EntityLivingBase.onLivingUpdate（:2001）进入；`isServerWorld()` 被覆写为 `super.isServerWorld() && !this.isAIDisabled()`（EntityLiving.java:1283）。
- `public final boolean interactFirst(EntityPlayer playerIn)`（EntityLiving.java:1091）：拴绳优先，其次 `protected boolean interact(EntityPlayer player)`（:1133）供子类实现挤奶/骑乘等。
- `public void setLeashedToEntity(Entity entityIn, boolean sendAttachNotification)`（:1202）/ `public void clearLeashed(boolean sendPacket, boolean dropLead)`（:1165）：经 EntityTracker 广播 S1BPacketEntityAttach(1, ...)。
- `protected void despawnEntity()`（:585）：距最近玩家 >128 格直接 setDead，>32 格随机消失。
- `public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, IEntityLivingData livingdata)`（:1045）：首次自然生成时调用（读 NBT 加载不调）。
- 内部枚举 `public static enum SpawnPlacementType { ON_GROUND, IN_AIR, IN_WATER; }`（EntityLiving.java:1304-1309）。

### DataWatcher（DataWatcher.java）

- `public <T> void addObject(int id, T object)`（DataWatcher.java:37）：注册槽位；id 上限 31（:46），类型必须在 `dataTypes` 静态表内（:393-403：Byte=0, Short=1, Integer=2, Float=3, String=4, ItemStack=5, BlockPos=6, Rotations=7）。
- `public <T> void updateObject(int id, T newData)`（:146）：值变化时置 watched + objectChanged，并回调 `this.owner.onDataWatcherUpdate(id)`。
- `public List<DataWatcher.WatchableObject> getChanged()`（:190）：取走脏项并清 watched 标记——EntityTrackerEntry 每次发 metadata 包会消费一次，**只能有一个消费者**。
- `public void writeTo(PacketBuffer buffer) throws IOException`（:220）与 `private static void writeWatchableObjectToPacketBuffer(PacketBuffer buffer, DataWatcher.WatchableObject object)`（:256）：线格式为 `(type << 5 | id & 31)` 单字节头，0x7F 结尾。
- `public static List<DataWatcher.WatchableObject> readWatchedListFromPacketBuffer(PacketBuffer buffer) throws IOException`（:303）：Netty 解码线程侧反序列化；`public void updateWatchedObjectsFromList(List<DataWatcher.WatchableObject> p_75687_1_)`（:364）在主线程套用，同样触发 `owner.onDataWatcherUpdate`。
- 内部用 `ReentrantReadWriteLock`（:30）保护 map；`getWatchedObject` 注释明言"is threadsafe, unless it throws"（:118-120）。

### EntityTracker / EntityTrackerEntry（服务端）

- `public void trackEntity(Entity entityIn)`（EntityTracker.java:57）：按类型硬编码 (trackingRange, updateFrequency, sendVelocityUpdates)——如 EntityPlayerMP(512,2)、EntityArrow(64,20,false)、EntityDragon(160,3,true)、EntityHanging(160,Integer.MAX_VALUE,false)。
- `public void updateTrackedEntities()`（EntityTracker.java:256）：每服务端 tick 由 WorldServer 调，对每个条目跑 `updatePlayerList`。
- `public void sendToAllTrackingEntity(Entity entityIn, Packet p_151247_2_)`（EntityTracker.java:299）：向所有正在看该实体的玩家发包；`func_151248_b`（:309）额外包含实体本人。
- `public void updatePlayerList(List<EntityPlayer> players)`（EntityTrackerEntry.java:135）：核心增量同步——位置量化为 `posX*32` 定点（:185-187），|Δ|<4 不发，128 内发 S15RelMove/S16Look/S17LookMove，否则 S18Teleport；每 400 tick 强制 teleport（:199）；`sendMetadataToAllAssociatedPlayers()`（:308）消费 DataWatcher 脏项发 S1CPacketEntityMetadata 与 S20PacketEntityProperties。
- `public void updatePlayerEntity(EntityPlayerMP playerMP)`（EntityTrackerEntry.java:369）：进入视距时发 `createSpawnPacket()`（:488，按类型选 S0C/S0E/S0F/S10/S11）＋全量 metadata＋属性＋装备＋药水＋骑乘/拴绳附着。

### EntityList（注册表）

- `private static void addMapping(Class <? extends Entity > entityClass, String entityName, int id)`（EntityList.java:90）：五张 map 同步写入，重复名/重复 id/id==0/null class 都抛 IllegalArgumentException。静态块（:302-366）注册全部原版实体：Item=1 … Guardian=68、Pig=90 … Rabbit=101、Villager=120、EnderCrystal=200。带蛋色的重载（:121）填 `entityEggs`。
- `public static Entity createEntityByID(int entityID, World worldIn)`（:193）：客户端 NetHandlerPlayClient 处理 S0FPacketSpawnMob 时的实体工厂（反射调 `(World)` 构造器）。
- `public static Entity createEntityFromNBT(NBTTagCompound nbt, World worldIn)`（:154）：区块加载反序列化入口，含旧版 "Minecart"+Type 迁移。
- `public static int getIDFromString(String entityName)`（:244）：查不到返回 90（猪）。

### SharedMonsterAttributes

五个常量（SharedMonsterAttributes.java:18-22）：`maxHealth`（generic.maxHealth，默认 20，shouldWatch=true）、`followRange`（32）、`knockbackResistance`（0）、`movementSpeed`（0.7，shouldWatch=true）、`attackDamage`（2）。`public static NBTTagList writeBaseAttributeMapToNBT(BaseAttributeMap map)`（:27）/ `public static void setAttributeModifiers(BaseAttributeMap map, NBTTagList list)`（:82）被 EntityLivingBase 的 NBT 读写调用（EntityLivingBase.java:531/563）。

### boss/EntityDragon 与 boss/EntityWither

- EntityDragon：`public double[][] ringBuffer = new double[64][3]`（EntityDragon.java:39）记录 64 tick 的 yaw/Y 历史，`public double[] getMovementOffsets(int p_70974_1_, float p_70974_2_)`（:108）供渲染器取尾巴/脖子偏移。`public void onLivingUpdate()`（:133）完全覆写飞行 AI（不走 tasks 系统），每 tick 手动 `onUpdate()` 并摆放 7 个 `EntityDragonPart`（:321-368）。`public boolean attackEntityFromPart(EntityDragonPart dragonPart, DamageSource source, float p_70965_3_)`（:562）是唯一受伤入口——`attackEntityFrom`（:588）本体只接受 thorns。死亡动画 `protected void onDeathUpdate()`（:617）200 tick 后 `generatePortal(...)`（:676）。
- EntityWither：DataWatcher 17/18/19 = 三个头的目标实体 id，20 = Invul 时间（entityInit，EntityWither.java:76-83）。`protected void updateAITasks()`（:231）处理 Invul 倒计时（结束时爆炸 :239）、副头发射凋灵头颅、blockBreakCounter 破坏方块（:342-378）。`public boolean attackEntityFrom(DamageSource source, float amount)`（:504）：免疫溺水/同类/半血时的箭。

## 时序与生命周期

**构造**：`new Entity(World)`（Entity.java:264）→ 分配自增 entityId → 建 DataWatcher 并注册 id 0-4 → 调 `entityInit()`。子类链条上 `EntityLivingBase(World)`（EntityLivingBase.java:197）先 `applyEntityAttributes()` 再 `setHealth(getMaxHealth())`；`EntityLiving(World)`（EntityLiving.java:79）建 tasks/navigator/各 helper。注意 `entityInit()` 在基类构造器中执行，子类字段此时尚未初始化。

**每 tick（逻辑，主线程 / 集成服务端线程各自世界）**：`World#updateEntities` → `Entity#onUpdate`。EntityLivingBase 的链条：`onUpdate`（:1784，装备同步）→ `onEntityUpdate`（:264，环境伤害/药水/计时）→ `onLivingUpdate`（:1948，插值→AI→跳跃→`moveEntityWithHeading`→实体碰撞）→ 角度归一化。EntityLiving 在 `onEntityUpdate` 里加环境音（EntityLiving.java:201），`onUpdate` 里加 `updateLeashedState()`（:284，仅服务端）。AI（`updateEntityActionState`）仅在 `isServerWorld()` 为真时运行——客户端远程实体只做插值。

**每服务端 tick（网络同步）**：`EntityTracker#updateTrackedEntities`（EntityTracker.java:256）→ 每个 `EntityTrackerEntry#updatePlayerList`（EntityTrackerEntry.java:135）按 updateFrequency 与位移阈值发增量包/metadata/velocity。

**每帧**：本包不含渲染代码；渲染层用 `prevPosX + (posX - prevPosX) * partialTicks` 及 `prevRenderYawOffset`/`prevRotationYawHead`/`getSwingProgress(float partialTickTime)`（EntityLivingBase.java:2188）插值。因此每 tick 开头必须先存 prev 值（Entity.java:421-425、EntityLivingBase.java:379-383），破坏此顺序会导致渲染抖动。

**线程归属**：实体状态只允许主线程（客户端）或对应世界的服务端 tick 线程改动。Netty EventLoop 只触碰 `DataWatcher.readWatchedListFromPacketBuffer`（纯解码，产出独立 List）；套用 `updateWatchedObjectsFromList` 发生在主线程封包处理阶段。

**死亡**：`setDead()`（Entity.java:339）只置 isDead；EntityLivingBase 血量归零后每 tick 走 `onDeathUpdate()`（:398），deathTime==20 时掉经验并 `setDead()`；World 下一 tick 移除，服务端随后 `EntityTracker#untrackEntity`（EntityTracker.java:235）广播销毁。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void onUpdate()` | Entity.java:403 / EntityLivingBase.java:1784 / EntityLiving.java:278 | World#updateEntities 每 tick，每个实体一次 | 实体级 pre/post-tick 钩子；ESP/追踪器数据采集；冻结实体 | 不调 super 则 prev 值不更新，渲染插值当场坏掉 |
| `public void onLivingUpdate()` | EntityLivingBase.java:1948 | onUpdate 内每 tick | 移动/AI 前后注入；改 moveStrafing/moveForward 即改输入 | 客户端本地玩家的按键→移动也走此链（EntityPlayerSP 覆写） |
| `public void moveEntity(double x, double y, double z)` | Entity.java:598 | 一切物理位移 | 碰撞修改、Phase、NoSlow、步高魔改（stepHeight） | 位移逻辑约 340 行，逐轴顺序 Y→X→Z 不能乱 |
| `public void moveEntityWithHeading(float strafe, float forward)` | EntityLivingBase.java:1602 | onLivingUpdate 每 tick | Speed/滑行/水中加速——所有摩擦、重力常数所在地 | 服务端有移动校验（本仓库为集成服务端，仍会经 NetHandlerPlayServer 检查） |
| `public boolean attackEntityFrom(DamageSource source, float amount)` | EntityLivingBase.java:863（另 Entity.java:1443） | 任何伤害来源 | 伤害拦截/修改/统计；无敌帧逻辑 | 仅服务端生效（:869 客户端直接 false）；客户端表现走 handleStatusUpdate |
| `public void onDeath(DamageSource cause)` | EntityLivingBase.java:1020 | 血量归零时（服务端）与 status 3（客户端） | 击杀统计、掉落改写 | 客户端由 handleStatusUpdate(3)（:1373）触发，别在此处做只应服务端做的事 |
| `public void handleStatusUpdate(byte id)` | EntityLivingBase.java:1356 / EntityLiving.java:263 / Entity.java:2097 | 客户端收 S19PacketEntityStatus | 观察受伤（2）/死亡（3）/爆炸粒子（20）事件 | id 语义分散在各覆写层 |
| `public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean p_180426_10_)` | EntityLivingBase.java:2111（Entity.java:2013） | 客户端收 S14/S18 实体移动包 | 服务端位置回写钩子；反 lag-back、回放录制 | LivingBase 版是 3-tick 插值，Entity 版立即落位并上抬出方块 |
| `public <T> void updateObject(int id, T newData)` | DataWatcher.java:146 | 任何 metadata 写入（双端） | 观察/篡改所有实体元数据（血量、隐身、着火…） | 会触发 owner.onDataWatcherUpdate；类型错直接 ClassCastException |
| `public void onDataWatcherUpdate(int dataID)` | Entity.java:2650（覆写例 EntityMinecartCommandBlock.java:126） | DataWatcher 值变化后回调 | 客户端监听服务端下发的元数据变更 | 基类为空实现；updateObject 路径回调在持锁外，updateWatchedObjectsFromList 路径回调在写锁内（DataWatcher.java:366-377） |
| `public void updateWatchedObjectsFromList(List<DataWatcher.WatchableObject> p_75687_1_)` | DataWatcher.java:364 | 客户端处理 S1CPacketEntityMetadata | metadata 包级过滤（如反隐身） | 未注册的 id 被静默丢弃（:372 判空） |
| `public boolean interactFirst(EntityPlayer playerIn)` | Entity.java:1863 / EntityLiving.java:1091 | 玩家右键实体（服务端处理 C02UseEntity） | 交互拦截/GUI 打开入口（交易、矿车命令块） | EntityLiving 的版本是 final，只能改 `interact`（:1133） |
| `public void swingItem()` | EntityLivingBase.java:1342 | 玩家挥手/生物攻击 | 无摆动/杀手挥拳检测；动画节奏 | 服务端侧会广播 S0BPacketAnimation（:1351） |
| `protected void jump()` | EntityLivingBase.java:1567 | onLivingUpdate 中 isJumping 且 onGround | HighJump/LongJump；冲刺跳的 0.2 前向加速在此 | motionY=getJumpUpwardsMotion()=0.42F（:1559） |
| `public void knockBack(Entity entityIn, float p_70653_2_, double p_70653_3_, double p_70653_5_)` | EntityLivingBase.java:1076 | 受击后（attackEntityFrom :962） | 反击退（服务端）/击退倍率 | 受 knockbackResistance 属性随机豁免 |
| `protected final void updateEntityActionState()` | EntityLiving.java:619 | 服务端 AI tick | AI 整体停用点（也可用 setNoAI） | final；用 `protected void updateAITasks()`（:651）做子类扩展 |
| `public void updatePlayerList(List<EntityPlayer> players)` | EntityTrackerEntry.java:135 | 服务端每 tick 每被追踪实体 | 观察/改写所有出站实体同步包 | 位置量化 *32 定点；改包需理解增量/绝对切换条件 |
| `public void sendToAllTrackingEntity(Entity entityIn, Packet p_151247_2_)` | EntityTracker.java:299 | 各系统广播实体事件时 | 统一的实体包广播拦截点 | 不含实体本人；带本人用 func_151248_b（:309） |
| `public static void setBossStatus(IBossDisplayData displayData, boolean hasColorModifierIn)` | BossStatus.java:10 | 渲染 boss 时由渲染层调用 | 自定义 Boss 血条数据源 | 全静态、无清理，靠 statusBarTime=100 自然衰减 |
| `public boolean isEntityInvulnerable(DamageSource source)` | Entity.java:2427 | 所有 attackEntityFrom 开头 | 无敌判定统一入口 | outOfWorld 与创造玩家伤害不可免 |
| `public MovingObjectPosition rayTrace(double blockReachDistance, float partialTicks)` | Entity.java:1500 | 客户端每帧选取方块目标 | Reach 修改、目标欺骗 | 只测方块，实体射线在渲染层 EntityRenderer |
| `public void setDead()` | Entity.java:339 | 各处销毁实体 | 实体移除事件监听 | 只置标志，真正移除在 World 下一 tick |

## 数据与协议

**DataWatcher 线格式**（S1CPacketEntityMetadata payload，DataWatcher.java:256-361）：

| 字段 | 类型 | 读 / 写方法 | 含义 |
|---|---|---|---|
| header | byte | `buffer.readByte()` / `buffer.writeByte((object.getObjectType() << 5 \| object.getDataValueId() & 31) & 255)` | 高 3 位类型，低 5 位槽位 id；0x7F(127) 终止 |
| type 0 | byte | readByte/writeByte | Byte 值 |
| type 1 | short | readShort/writeShort | Short 值 |
| type 2 | int | readInt/writeInt | Integer 值 |
| type 3 | float | readFloat/writeFloat | Float 值 |
| type 4 | String | `readStringFromBuffer(32767)` / `writeString` | 字符串 |
| type 5 | ItemStack | `readItemStackFromBuffer()` / `writeItemStackToBuffer(itemstack)` | 物品 |
| type 6 | BlockPos | 3×readInt / 3×writeInt | 坐标 |
| type 7 | Rotations | 3×readFloat / 3×writeFloat | 盔甲架姿态 |

**本包定义的 DataWatcher 槽位**：0 flags(Byte, Entity.java:286)、1 Air(Short)、2 CustomName(String)、3 CustomNameVisible(Byte)、4 Silent(Byte)；6 Health(Float)、7 PotionColor(Int)、8 PotionAmbient(Byte)、9 ArrowCount(Byte)（EntityLivingBase.java:213-216）；12 GrowingAge(Byte, EntityAgeable.java:78)；15 NoAI(Byte, EntityLiving.java:174)；17-19 WitherTarget(Int)、20 WitherInvul(Int)（EntityWither.java:79-82）；23 Command(String)、24 LastOutput(String)（EntityMinecartCommandBlock.java:66-67）。

**NBT（存档）主要键**：Entity 层（Entity.java:1602-1651）：`Pos`(List<Double>×3)、`Motion`、`Rotation`(List<Float>×2)、`FallDistance`(Float)、`Fire`(Short)、`Air`(Short)、`OnGround`(Boolean)、`Dimension`(Int)、`Invulnerable`、`PortalCooldown`、`UUIDMost/UUIDLeast`(Long)、`CustomName`/`CustomNameVisible`、`Silent`、`Riding`(Compound 递归)。LivingBase 层（EntityLivingBase.java:514-552）：`HealF`(Float)/`Health`(Short 兼容旧档)、`HurtTime`、`HurtByTimestamp`、`DeathTime`、`AbsorptionAmount`、`Attributes`(List)、`ActiveEffects`(List)。EntityLiving 层（EntityLiving.java:337-434）：`CanPickUpLoot`、`PersistenceRequired`、`Equipment`(List×5)、`DropChances`(List<Float>×5)、`Leashed`/`Leash`(UUID 或 X/Y/Z)、`NoAI`。属性子结构（SharedMonsterAttributes.java:42-80）：`Name`(String)、`Base`(Double)、`Modifiers`[{`Name`,`Amount`(Double),`Operation`(Int),`UUIDMost`,`UUIDLeast`}]。EntityHanging：`Facing`(Byte，兼容旧键 `Direction`/`Dir`，EntityHanging.java:241-260)、`TileX/TileY/TileZ`。EntityWither：`Invul`(Int, EntityWither.java:91)。EntityAgeable：`Age`、`ForcedAge`(Int, EntityAgeable.java:152-153)。

**注册表**：EntityList 数字 id 同时是存档 id 与 S0FPacketSpawnMob 的 type 字段；EntityTrackerEntry.createSpawnPacket 中 S0EPacketSpawnObject 的 object type（如 arrow=60、item=2、TNT=50、armor stand=78，EntityTrackerEntry.java:488-632）是另一套独立枚举，不要与 EntityList id 混淆。

## 不变量与陷阱

- **位置的权威是 boundingBox**：改 posX/Y/Z 不改 AABB 会在下次 `resetPositionToBB()`（Entity.java:941）被吞掉。要传送请用 `setPosition`/`setPositionAndUpdate`/`setLocationAndAngles`。
- **prev 字段必须在 tick 开头保存**（Entity.java:420-425 等）。任何提前 return 的 onUpdate 覆写都会导致渲染抖动或轨迹残影。
- **客户端不结算伤害**：`EntityLivingBase.attackEntityFrom` 在 `worldObj.isRemote` 时恒 false（EntityLivingBase.java:869）；客户端受伤动画来自 entity status 2/3 与 metadata 的血量变化。
- **DataWatcher id ≤ 31、类型固定**：`addObject` 超 31 或未知类型直接抛异常（DataWatcher.java:41-51）；子类新增槽位必须避开父类已注册的 id（本包已用 0-4、6-9、12、15、17-20、23-24）。
- **`getChanged()` 是破坏性读取**（DataWatcher.java:190）：会清 watched 标记。功能层若想旁观脏数据，只能包装/hook，不能自己调它，否则 EntityTrackerEntry 会漏发 metadata。
- **entityInit() 在基类构造器内被调**（Entity.java:291）：此时子类字段全是默认值，勿在 entityInit 里读子类字段（JDK 25 下依旧如此，且更容易被 IDE 静态检查标红）。
- **entityId 双轨制**：本地构造用自增 `nextEntityID`（Entity.java:266），网络实体随 spawn 包被 `setEntityId` 覆盖。`equals`/`hashCode` 都基于 entityId（Entity.java:301-309），跨世界比较无意义。
- **EntityTracker/EntityTrackerEntry 属于服务端线程**：字段无锁（trackingPlayers 是普通 HashSet），从客户端线程或 Netty 线程触碰会产生并发修改。
- **AI 仅服务端**：`isServerWorld()`（EntityLivingBase.java:2203 / EntityLiving.java:1283）门控 `updateEntityActionState`；NoAI 通过 DataWatcher 15 同步，可被客户端观测。
- **移动常数是行为指纹**：0.91 slipperiness 因子、0.16277136F、重力 0.08、阻尼 0.98（EntityLivingBase.java:1610-1682）、跳跃 0.42F（:1561）——修改会被服务端移动校验/反作弊察觉。
- **龙不可被本体攻击**：伤害必须经 `EntityDragonPart.attackEntityFrom`（EntityDragonPart.java:51）转发到 `attackEntityFromPart`；直接对 EntityDragon 调 `attackEntityFrom` 只处理 thorns（EntityDragon.java:588-596）。龙的部件不在 World 实体列表中注册渲染逻辑，位置每 tick 由龙手动摆放。
- **BossStatus 是全局静态可变状态**（BossStatus.java:5-8）：同 tick 多个 boss 时后写者赢；非线程安全。
- **EntityHanging 不参与物理**：`moveEntity`/`addVelocity` 一旦有位移直接自毁掉落（EntityHanging.java:206-225）；其 `onUpdate` 每 100 tick 才检查一次表面有效性（:106）。
- **LWJGL3/JDK25 移植注意**：本包为纯逻辑代码，未发现 GL/Keyboard/Mouse 依赖，移植改动集中在渲染/输入层；但 `Entity.rand = new Random()`（Entity.java:272）为每实体独立实例，勿替换为共享 ThreadLocalRandom（部分逻辑依赖可复现序列，如 EntityDragon 目标选择）。反射构造（EntityList.java:140 `oclass.getConstructor(new Class[] {World.class}).newInstance(...)`）在 JDK 25 强封装下仍可用（同包自身类），新增实体类必须保留 public `(World)` 构造器。

## 交叉引用

- world → `World#updateEntities` 调 `Entity#onUpdate`；`World#spawnEntityInWorld` / `World#setEntityState`（status 广播）/ `World#getCollidingBoundingBoxes`（moveEntity 碰撞）。
- world → `WorldServer#getEntityTracker` 返回本包 `EntityTracker`（EntityLivingBase.java:1351、EntityLiving.java:1179 使用）。
- network → `NetHandlerPlayClient` 调 `EntityList#createEntityByID`、`Entity#setPositionAndRotation2`、`DataWatcher#updateWatchedObjectsFromList`、`Entity#setVelocity`、`Entity#handleStatusUpdate`。
- network.play.server → EntityTrackerEntry 构造 `S0CPacketSpawnPlayer`/`S0EPacketSpawnObject`/`S0FPacketSpawnMob`/`S14PacketEntity$*`/`S18PacketEntityTeleport`/`S1CPacketEntityMetadata`/`S12PacketEntityVelocity`/`S20PacketEntityProperties`/`S1BPacketEntityAttach` 等（EntityTrackerEntry.java:40-55 imports）。
- entity.ai → `EntityLiving#updateEntityActionState` 调 `EntityAITasks#onUpdateTasks`、`PathNavigate#onUpdateNavigation`、`EntityMoveHelper#onUpdateMoveHelper`、`EntityLookHelper#onUpdateLook`、`EntityJumpHelper#doJump`。
- entity.ai.attributes → `EntityLivingBase#getAttributeMap` 持 `BaseAttributeMap`/`ServersideAttributeMap`；SharedMonsterAttributes 定义 `RangedAttribute` 实例。
- potion → `EntityLivingBase#updatePotionEffects` 调 `PotionEffect#onUpdate`、`Potion.potionTypes[...]#applyAttributesModifiersToEntity`。
- item → `EntityLiving#equipment` 存 `ItemStack`；`ItemStack#getAttributeModifiers` 在装备同步时套用（EntityLivingBase.java:1818-1824）。
- nbt → `NBTTagCompound`/`NBTTagList` 贯穿 `writeToNBT`/`readFromNBT` 全链。
- block → `Entity#moveEntity` 调 `Block#onEntityCollidedWithBlock`、`Block#onLanded`、`Block#onFallenUpon`；`Block.slipperiness` 进入移动物理。
- util → `DamageSource`（所有伤害）、`CombatTracker`（EntityLivingBase.java:59）、`MathHelper`、`AxisAlignedBB`、`BlockPos`、`Vec3`。
- scoreboard → `EntityLivingBase#getTeam` 调 `worldObj.getScoreboard().getPlayersTeam(...)`（EntityLivingBase.java:2272）。
- command → `Entity implements ICommandSender`；`EntityMinecartCommandBlock` 内嵌 `CommandBlockLogic`（EntityMinecartCommandBlock.java:17）。
- stats → `EntityList.EntityEggInfo` 构造调 `StatList.getStatKillEntity`/`getStatEntityKilledBy`（EntityList.java:381-382）。
- village → `IMerchant`/`NpcMerchant` 使用 `MerchantRecipe`/`MerchantRecipeList`。
- crash → `Entity#addEntityCrashInfo`（Entity.java:2541）与 EntityTracker/DataWatcher 的 CrashReport 生成。
- 渲染层（net.minecraft.client.renderer.entity）→ 读取 `limbSwing`/`renderYawOffset`/`getSwingProgress`/`getBrightnessForRender`/`isInRangeToRenderDist`（Entity.java:1545）；`BossStatus` 由 GUI 血条读取。

## 覆盖声明

完整读取了 33/33 个文件（EntityLivingBase.java 与 Entity.java 因超页各分两次 Read，均覆盖到文件末行）。

逐行精读：Entity、EntityLivingBase、EntityLiving、DataWatcher、EntityTracker、EntityTrackerEntry、EntityList、SharedMonsterAttributes、EntityDragon、EntityWither、EntityHanging、EntityAgeable、EntityCreature、EntityMinecartCommandBlock、EntityLeashKnot、EntityLightningBolt、EntityBodyHelper、EntityFlying、EntityDragonPart、BossStatus、NpcMerchant、EnumCreatureType、EntitySpawnPlacementRegistry 及全部小接口/枚举（IEntityLivingData、INpc、EnumCreatureAttribute、IEntityOwnable、IProjectile、IRangedAttackMob、IEntityMultiPart、EntityWeatherEffect、IBossDisplayData、IMerchant）。

只做结构性浏览的类：无（本包所有文件均逐行读过；但 Entity.moveEntity 的 stepHeight 分支（Entity.java:721-813）与 EntityDragon.onLivingUpdate 的飞行数学（EntityDragon.java:212-302）只核对了控制流与常数，未逐式推导几何含义）。
