---
area: net/minecraft/entity/projectile
slug: mc-entity-projectile
files: 10
lines: 2583
tier: C
---

# net/minecraft/entity/projectile

## 定位

本包实现所有"发射物"实体：箭（EntityArrow）、钓鱼浮漂（EntityFishHook）、火球家族（EntityFireball / EntityLargeFireball / EntitySmallFireball / EntityWitherSkull）以及投掷物家族（EntityThrowable / EntityEgg / EntitySnowball / EntityPotion）。三条继承链各自内置一套完整的"射线检测 + 实体扫描 + 命中回调 + 惯性/重力积分"物理循环，全部在 `onUpdate()` 里逐 tick 推进。

调用方：
- 物品层（`ItemBow`、`ItemSnowball`、`ItemPotion`、`ItemFishingRod`）在玩家右键时构造并 `worldObj.spawnEntityInWorld(...)`；
- 怪物 AI（`EntitySkeleton`、`EntityGhast`、`EntityWitch`、`EntitySnowman`、`EntityWither`）发射对应的投射物；
- 客户端网络层 `NetHandlerPlayClient#handleSpawnObject` 收到 `S0EPacketSpawnObject` 时按 type id 直接 new 出这些类的实例放进 `clientWorldController`。

被调用方：`World`（rayTraceBlocks、getEntitiesWithinAABBExcludingEntity、spawnParticle、newExplosion）、`DamageSource` 工厂方法、`EnchantmentHelper`、`DataWatcher`、NBT 读写。

本仓库是单一客户端源码树但保留了完整的集成服务器逻辑（`worldObj.isRemote` 分支随处可见）。如果这个包消失：弓箭、钓鱼、雪球/鸡蛋/药水投掷、恶魂/凋灵/烈焰人的远程攻击全部无法工作，`NetHandlerPlayClient.handleSpawnObject` 与 `EntityList` 注册表直接编译失败。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| EntityArrow | 602 | extends Entity implements IProjectile | 箭：独立实现飞行/插地/伤害/暴击粒子/拾取，damage 与 knockbackStrength 可调 |
| EntityEgg | 69 | extends EntityThrowable | 鸡蛋：命中造成 0 伤害，服务端 1/8 概率孵出小鸡（1/32 再乘 4 只） |
| EntityFireball | 355 | extends Entity（abstract） | 火球基类：加速度驱动（无重力）、可被攻击反弹、亮度恒为满 |
| EntityFishHook | 622 | extends Entity | 钓鱼浮漂：抛竿物理、水中咬钩三阶段状态机、收杆结算战利品（JUNK/TREASURE/FISH 权重表） |
| EntityLargeFireball | 69 | extends EntityFireball | 恶魂大火球：命中造成 6.0F 伤害并产生 explosionPower 强度爆炸 |
| EntityPotion | 184 | extends EntityThrowable | 喷溅药水：重力 0.05F、初速 0.5F、inaccuracy -20.0F，命中后 4x2x4 范围施加药水效果 |
| EntitySmallFireball | 91 | extends EntityFireball | 烈焰人小火球：5.0F 伤害 + 点燃 5s，打空处放火（受 mobGriefing 约束） |
| EntitySnowball | 54 | extends EntityThrowable | 雪球：0 伤害（对 EntityBlaze 为 3），命中撒 8 个 SNOWBALL 粒子 |
| EntityThrowable | 380 | extends Entity implements IProjectile（abstract） | 投掷物基类：抛物线物理（重力 0.03F）、命中回调 `onImpact`、thrower 按名字/UUID 惰性解析 |
| EntityWitherSkull | 157 | extends EntityFireball | 凋灵头颅：8.0F 伤害 + 凋零效果 + 爆炸；蓝色（invulnerable）变体经 DataWatcher 10 同步且减速至 0.73F |

## 核心类详解

### EntityThrowable（投掷物基类）

关键字段（`EntityThrowable.java:24-35`）：`private int xTile/yTile/zTile`、`private Block inTile`、`protected boolean inGround`、`public int throwableShake`、`private EntityLivingBase thrower`、`private String throwerName`、`private int ticksInGround`、`private int ticksInAir`。

关键方法：
- `public void setThrowableHeading(double x, double y, double z, float velocity, float inaccuracy)`（`EntityThrowable.java:102`）— 归一化方向、加高斯噪声（系数 `0.007499999832361937D`）、乘速度、反算 yaw/pitch。由构造函数与 `IProjectile` 调用方使用。
- `public void onUpdate()`（`EntityThrowable.java:143`）— 每 tick：插地检测 → `rayTraceBlocks` 块检测 → 服务端限定的实体相交扫描（`EntityThrowable.java:192` 的 `!this.worldObj.isRemote` 包裹）→ 命中传送门则 `setPortal`（`EntityThrowable.java:230-233`），否则 `this.onImpact(movingobjectposition)`（`EntityThrowable.java:236`）→ 位置积分，空气阻力 `f2 = 0.99F`，水中 0.8F，重力 `getGravityVelocity()`。
- `protected abstract void onImpact(MovingObjectPosition p_70184_1_);`（`EntityThrowable.java:300`）— 子类命中回调。
- `protected float getGravityVelocity()`（`EntityThrowable.java:292`）— 返回 `0.03F`；`protected float getVelocity()`（:89）返回 `1.5F`；`protected float getInaccuracy()`（:94）返回 `0.0F`，均供子类覆写（EntityPotion 三个都覆写了）。
- `public EntityLivingBase getThrower()`（`EntityThrowable.java:354`）— thrower 为 null 时先按 `throwerName` 查玩家，再在 `WorldServer` 里按 `UUID.fromString(this.throwerName)` 查实体，异常吞掉置 null（`EntityThrowable.java:360-375`）。
- 插地超时：`ticksInGround == 1200` 时 `setDead()`（`EntityThrowable.java:161-164`）。

### EntityArrow（不走 Throwable 链的独立实现）

关键字段（`EntityArrow.java:30-50`）：`public int canBePickedUp`、`public int arrowShake`、`public Entity shootingEntity`、`private double damage = 2.0D`、`private int knockbackStrength`；DataWatcher slot 16 存暴击位（`EntityArrow.java:121`）。

关键方法：
- `public EntityArrow(World worldIn, EntityLivingBase shooter, float velocity)`（`EntityArrow.java:96`）— `ItemBow.onPlayerStoppedUsing` 使用的构造器，初速 `velocity * 1.5F`。
- `public void setThrowableHeading(double x, double y, double z, float velocity, float inaccuracy)`（`EntityArrow.java:127`）— 与 Throwable 版几乎相同但噪声带 `this.rand.nextBoolean() ? -1 : 1` 符号翻转。
- `public void onUpdate()`（`EntityArrow.java:178`）— 双阶段：`inGround` 时计数到 `ticksInGround >= 1200` 消失（`EntityArrow.java:217-219`）；飞行时块射线 + 实体扫描（击中自己人需 `ticksInAir >= 5`，`EntityArrow.java:254`），命中实体伤害为 `MathHelper.ceiling_double_int((double)f2 * this.damage)`（速度标量 x damage，`EntityArrow.java:292-293`），暴击追加 `rand.nextInt(l / 2 + 2)`；燃烧的箭点燃目标 5s（末影人除外，`EntityArrow.java:311-314`）；knockback 沿水平速度方向推 `knockbackStrength * 0.6...`（`EntityArrow.java:327-335`）；命中玩家时向射手（EntityPlayerMP）发 `S2BPacketChangeGameState(6, 0.0F)` 播放叮声（`EntityArrow.java:343-346`）。空气阻力 `f4 = 0.99F`、重力 `f6 = 0.05F`（`EntityArrow.java:430-431`）。
- `public void onCollideWithPlayer(EntityPlayer entityIn)`（`EntityArrow.java:517`）— 服务端、插地且 `arrowShake <= 0` 时结算拾取（`canBePickedUp == 1` 进背包，`== 2` 仅创造模式）。
- `public void setIsCritical(boolean critical)` / `public boolean getIsCritical()`（`EntityArrow.java:580` / `:597`）— DataWatcher 16 bit0，客户端据此在 `onUpdate` 里喷 CRIT 粒子（`EntityArrow.java:394-400`）。
- `public void setDamage(double damageIn)`（:546）、`public void setKnockbackStrength(int knockbackStrengthIn)`（:559）— `ItemBow` 按蓄力与附魔调用。

### EntityFireball（加速度驱动的火球基类）

关键字段（`EntityFireball.java:26-31`）：`public EntityLivingBase shootingEntity`、`private int ticksAlive`、`public double accelerationX/accelerationY/accelerationZ`。构造时把归一化方向乘 `0.1D` 存进 acceleration（`EntityFireball.java:67-69`）。

关键方法：
- `public void onUpdate()`（`EntityFireball.java:92`）— 入口先做存活检查：`if (this.worldObj.isRemote || (this.shootingEntity == null || !this.shootingEntity.isDead) && this.worldObj.isBlockLoaded(new BlockPos(this)))` 否则 `setDead()`（`EntityFireball.java:94, 225`）。每 tick `this.setFire(1)`（:97）；插地 600 tick 消失（:105）；实体扫描中打自己人需 `ticksInAir >= 25`（:144）；命中即 `this.onImpact(movingobjectposition)`（:170）；无重力，改为 `motion += acceleration` 后乘 `getMotionFactor()`（:214-219）；每 tick 喷一个 SMOKE_NORMAL 粒子（:220）。
- `protected float getMotionFactor()`（`EntityFireball.java:232`）— `0.95F`，水中改 0.8F（:211）；EntityWitherSkull 无敌变体覆写为 0.73F。
- `protected abstract void onImpact(MovingObjectPosition movingObject);`（`EntityFireball.java:240`）。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（`EntityFireball.java:305`）— 火球可被打回：取攻击者 `getLookVec()` 作为新 motion 并重设 acceleration（击回恶魂机制），且把攻击者设为新的 `shootingEntity`（:315-332）。
- `public float getBrightness(float partialTicks)` 返回 `1.0F`（:346）、`public int getBrightnessForRender(float partialTicks)` 返回 `15728880`（:351）— 渲染时全亮。

### EntityFishHook（状态机最复杂的一个）

关键字段：静态战利品表 `private static final List<WeightedRandomFishable> JUNK / TREASURE / FISH`（`EntityFishHook.java:34-36`）；`public EntityPlayer angler`、`public Entity caughtEntity`、咬钩状态机 `private int ticksCatchable / ticksCaughtDelay / ticksCatchableDelay`、`private float fishApproachAngle`（:42-50）；客户端插值 `fishPosRotationIncrements / fishX / fishY / fishZ / fishYaw / fishPitch` 与 `clientMotionX/Y/Z`（:51-59）。构造时 `anglerIn.fishEntity = this`（:82, :93）建立双向引用。

关键方法：
- `public void handleHookCasting(double p_146035_1_, double p_146035_3_, double p_146035_5_, float p_146035_7_, float p_146035_8_)`（`EntityFishHook.java:128`）— 抛竿版 setThrowableHeading。
- `public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean p_180426_10_)`（`EntityFishHook.java:149`）— 客户端收到 S18PacketEntityTeleport/S14 系移动包时缓存目标位姿，交由 `onUpdate` 前段做 N tick 插值（:179-190）。
- `public void onUpdate()`（`EntityFishHook.java:175`）— 服务端：角色死亡/换手/超出 32 格（`getDistanceSqToEntity(this.angler) > 1024.0D`，:197）即销毁；钩住实体时贴附其位置（:204-216）；命中实体用 0 伤害攻击试探成功后设 `caughtEntity`（:297-300）。水中部分（仅服务端，:360 起）跑三段咬钩状态机：`ticksCaughtDelay`（100-900 tick，受 Lure 附魔每级减 100，:465-466）→ `ticksCatchableDelay`（20-80 tick，鱼靠近的水花粒子）→ `ticksCatchable`（10-30 tick 可收杆窗口）。下雨加速（l = 2，:366-369）。
- `public int handleHookRetraction()`（`EntityFishHook.java:528`）— `ItemFishingRod.onItemRightClick` 在 `playerIn.fishEntity != null` 时调用；钩住实体则把实体拉向玩家返回 3，`ticksCatchable > 0` 则生成战利品 `EntityItem` 飞向玩家并掉 1-6 经验返回 1，插地返回 2；返回值作为鱼竿耐久扣减。
- `private ItemStack getFishingResult()`（`EntityFishHook.java:577`）— 按 Luck of the Sea / Lure 修正的概率在 JUNK（基础 10%）/ TREASURE（基础 5%）/ FISH 三表间 `WeightedRandom.getRandomItem` 抽取，并触发对应 StatList 成就。
- `public void setDead()`（`EntityFishHook.java:613`）— 覆写以清空 `this.angler.fishEntity = null`。

### EntityWitherSkull（DataWatcher 同步的变体开关）

- `protected void entityInit()`（`EntityWitherSkull.java:137`）— `this.dataWatcher.addObject(10, Byte.valueOf((byte)0));`。
- `public boolean isInvulnerable()` / `public void setInvulnerable(boolean invulnerable)`（`EntityWitherSkull.java:145` / `:153`）— DataWatcher 10 == 1 为蓝色头颅；`EntityWither` 发射时设置，客户端渲染层据此换贴图。
- `protected float getMotionFactor()`（`EntityWitherSkull.java:33`）— `return this.isInvulnerable() ? 0.73F : super.getMotionFactor();`。
- `protected void onImpact(MovingObjectPosition movingObject)`（`EntityWitherSkull.java:71`）— 8.0F mob 伤害，杀死目标时给凋灵回 5.0F 血（:83），普通/困难难度附加 10s/40s Wither II 效果（:98-112），最后 1.0F 爆炸。
- `public float getExplosionResistance(Explosion explosionIn, World worldIn, BlockPos pos, IBlockState blockStateIn)`（`EntityWitherSkull.java:55`）— 蓝色头颅对 `EntityWither.canDestroyBlock(block)` 的方块把抗性钳到 0.8F，实现"蓝头骨能炸黑曜石"。

## 时序与生命周期

- 生成：本地集成服务器路径由物品/AI 直接 new + `spawnEntityInWorld`；纯客户端路径由 `NetHandlerPlayClient#handleSpawnObject`（`net/minecraft/client/network/NetHandlerPlayClient.java:297`）按 spawn object type 构造（fish hook :315、arrow :322、wither skull :362、potion :371），随后 :433-435 把 owner entityId 解析回 `EntityArrow.shootingEntity`。
- 每 tick：`World.updateEntities` → `Entity.onEntityUpdate`/各类 `onUpdate()`，顺序为：插值（仅 FishHook 有显式插值段）→ 插地/存活检查 → 块射线 → 实体扫描 → onImpact → 位置积分与阻力/重力。EntityThrowable 的实体扫描只在服务端跑（`EntityThrowable.java:192`），EntityArrow / EntityFireball / EntityFishHook 的扫描两侧都跑。
- 每帧：本包不含渲染代码；渲染由 `net.minecraft.client.renderer.entity` 下的 RenderArrow / RenderFish / RenderFireball / RenderWitherSkull 完成，读取本包实体的位置与 DataWatcher。
- 消亡：命中（多数子类 onImpact 里服务端 `setDead()`）、插地超时（箭/投掷物 1200 tick，火球 600 tick）、FishHook 的距离/持杆检查、Fireball 的 shooter 死亡或区块未加载。
- 线程归属：全部逻辑在主客户端 tick 线程与集成服务器线程各自的 world tick 中执行；Netty EventLoop 不直接触碰这些类（封包先经 `PacketThreadUtil.checkThreadAndEnqueue` 转到主线程再 handleSpawnObject）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void onUpdate()` | EntityArrow.java:178 | 每 tick（world 实体更新） | 弹道预测/显示、命中判定观察、修改箭物理（重力 0.05F、阻力 0.99F 硬编码在局部变量 f6/f4） | 客户端与服务端逻辑混在同一方法，改动会双侧生效；命中伤害仅服务端有意义 |
| `public void setThrowableHeading(double x, double y, double z, float velocity, float inaccuracy)` | EntityArrow.java:127 / EntityThrowable.java:102 | 发射瞬间（ItemBow / 构造器） | 篡改初速与散布（inaccuracy 归零即无散布） | IProjectile 接口方法，Skeleton AI 也走这里 |
| `protected void onImpact(MovingObjectPosition p_70184_1_)`（abstract） | EntityThrowable.java:300 / EntityFireball.java:240 | 命中块或实体的那一 tick | 所有投掷物/火球命中事件的统一观测点；子类实现见各 onImpact | 多数子类实现以 `!this.worldObj.isRemote` 包裹，纯客户端 hook 只能看到雪球/鸡蛋的粒子分支 |
| `public boolean getIsCritical()` / `public void setIsCritical(boolean critical)` | EntityArrow.java:597 / :580 | 渲染层与 onUpdate 粒子分支 | 读 DataWatcher 16 判断暴击箭；ESP/轨迹类功能的数据源 | DataWatcher 由服务端同步，客户端改写不影响伤害 |
| `public int handleHookRetraction()` | EntityFishHook.java:528 | ItemFishingRod.onItemRightClick 收杆时 | 自动钓鱼类功能的结算点；返回值 = 鱼竿耐久损耗 | 客户端直接返回 0（:530-533），逻辑全在服务端 |
| `public void onUpdate()`（FishHook） | EntityFishHook.java:175 | 每 tick | 观察 `ticksCatchable > 0` 即"咬钩窗口"——但该字段是 private 且不经 DataWatcher 同步，客户端只能靠 motionY 突降或 splash 音效间接判断 | 咬钩状态机整体在 `!this.worldObj.isRemote` 分支内 |
| `public boolean attackEntityFrom(DamageSource source, float amount)` | EntityFireball.java:305 | 玩家/实体攻击火球时 | 火球反弹机制的入口（改向 + 换 shootingEntity） | EntitySmallFireball / EntityWitherSkull 覆写为恒 false，不可反弹 |
| `public void onCollideWithPlayer(EntityPlayer entityIn)` | EntityArrow.java:517 | 玩家碰撞箭实体时（每 tick 碰撞检测） | 观察/控制箭的拾取 | 服务端限定；`canBePickedUp == 2` 只允许创造模式 |
| `public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean p_180426_10_)` | EntityArrow.java:148 / EntityFishHook.java:149 | 客户端收到实体移动/传送包后（主线程） | 服务端权威位置到达客户端的入口；反 desync、轨迹重建 | Arrow 版直接 setPosition 无插值，FishHook 版做 N tick 插值 |
| `public void setVelocity(double x, double y, double z)` | EntityArrow.java:157 / EntityFishHook.java:165 / EntityThrowable.java:126 | 客户端收到 S12PacketEntityVelocity 时 | 初速修正观察点 | 仅客户端路径调用 |

## 数据与协议

DataWatcher（经 S0C/S1C metadata 包同步）：

| slot | 类型 | 所在类 | 写方法 | 读方法 | 含义 |
|---|---|---|---|---|---|
| 16 | Byte | EntityArrow | `setIsCritical(boolean)`（EntityArrow.java:580） | `getIsCritical()`（:597） | bit0 = 暴击箭（客户端 CRIT 粒子） |
| 10 | Byte | EntityWitherSkull | `setInvulnerable(boolean)`（EntityWitherSkull.java:153） | `isInvulnerable()`（:145） | 1 = 蓝色（凋灵半血期）头颅：减速 0.73F、可炸特殊方块 |

NBT（writeEntityToNBT / readEntityFromNBT）：

| 字段 | 类型 | 类 | 含义 |
|---|---|---|---|
| `xTile`/`yTile`/`zTile` | Short | Arrow / Throwable / Fireball / FishHook | 插入方块坐标 |
| `inTile` | String（旧存档兼容 Byte id） | 同上 | 插入方块注册名；读取时 `hasKey("inTile", 8)` 区分新旧格式 |
| `inGround` | Byte | 同上 | 是否插地 |
| `shake` | Byte | Arrow(:470) / Throwable(:312) / FishHook(:502) | 命中抖动动画计时 |
| `life` | Short | EntityArrow.java:466 | ticksInGround |
| `inData` | Byte | EntityArrow.java:469 | 插入方块 metadata |
| `pickup`（兼容旧 `player` Boolean） | Byte | EntityArrow.java:472/:504-511 | canBePickedUp |
| `damage` | Double | EntityArrow.java:473 | 基础伤害（默认 2.0D） |
| `direction` | TagList(Double x3) | EntityFireball.java:253 | motion 向量；缺失时读档直接 `setDead()`（:285） |
| `ownerName` | String | EntityThrowable.java:320 | thrower 玩家名或 UUID 字符串 |
| `Potion`（兼容旧 `potionValue` Int） | Compound | EntityPotion.java:157-164 | 药水 ItemStack；为 null 时 `setDead()` |
| `ExplosionPower` | Integer | EntityLargeFireball.java:54 | 爆炸强度（默认 1） |

协议侧：本包类被 `S0EPacketSpawnObject` 的 type id 映射构造（NetHandlerPlayClient.java:297 起）；`EntityList` 注册名/ID：ThrownEgg=7、Arrow=10、Snowball=11、Fireball=12、SmallFireball=13、ThrownPotion=16、WitherSkull=19（EntityList.java:306-318）。EntityFishHook 不在 EntityList 注册（不落盘），由 spawn object type 90 特殊处理。EntityArrow 命中玩家时服务端发 `S2BPacketChangeGameState(6, 0.0F)`（EntityArrow.java:345）。

## 不变量与陷阱

- 三条继承链是复制粘贴式的三份物理循环（Arrow / Throwable / Fireball / FishHook 四份 raytrace+扫描代码几乎相同但常数不同）：改一处不影响另外三处。Arrow 重力 0.05F、阻力 0.99F；Throwable 重力 0.03F（Potion 0.05F）、阻力 0.99F；Fireball 无重力、系数 0.95F（WitherSkull 蓝色 0.73F）；FishHook 阻力 0.92F（着地 0.5F）。
- 自伤宽限期常数不同：Arrow/Throwable/FishHook 为 `ticksInAir >= 5`，Fireball 为 `ticksInAir >= 25`（EntityFireball.java:144）。
- `EntityThrowable` 的实体命中扫描只在服务端执行（EntityThrowable.java:192），但 `EntityArrow`/`EntityFireball` 客户端也执行——纯客户端 world 里箭会"假命中"反弹，最终以服务端位置包纠正。
- `EntityFishHook` 与 `EntityPlayer.fishEntity` 是双向强引用：构造器写入（EntityFishHook.java:82,93）、`setDead()` 清空（:613-621）。绕过 setDead 直接移除实体会留下悬挂引用，导致鱼竿右键行为异常。
- `EntityFireball.onUpdate` 首行存活检查（:94）意味着：服务端上 shooter 死亡或所在区块未加载的火球会立即消失，但 `worldObj.isRemote` 分支恒通过——客户端幽灵火球依赖服务端销毁包。
- `EntityPotion.getInaccuracy()` 返回 `-20.0F`（EntityPotion.java:62-65）——它实际被 `EntityThrowable` 构造器当作 pitch 偏移用于 motionY 计算（EntityThrowable.java:77），名字有误导性，不是散布。
- `EntityFishHook` 的咬钩字段（ticksCatchable 等）private 且不同步到客户端；写自动钓鱼不能读字段，只能监听 `random.splash` 音效包或浮漂 motionY。
- `getThrower()` 用玩家名字符串存 owner，跨会话后按名字再查（EntityThrowable.java:354-379）；名字重登/改名会导致 thrower 丢失，UUID 分支仅在 `WorldServer` 生效且靠 `UUID.fromString` 抛异常回退。
- 反射/序列化注意：`EntityFireball.readEntityFromNBT` 缺 `direction` 标签直接 `setDead()`；`EntityPotion` 读档 potionDamage 为 null 也 `setDead()`。
- LWJGL3/JDK25 移植：本包纯逻辑，无 GL/输入依赖，与原版 1.8.9 MCP 代码一致；未发现移植改动。线程安全上所有字段无同步，只允许 world tick 线程访问。

## 交叉引用

- net/minecraft/item → `ItemBow#onPlayerStoppedUsing`（构造 EntityArrow、setIsCritical/setDamage/setKnockbackStrength/setFire）、`ItemFishingRod#onItemRightClick`（构造 EntityFishHook / 调 `EntityFishHook#handleHookRetraction`）、`ItemSnowball#onItemRightClick`、`ItemPotion#onItemRightClick`
- net/minecraft/entity/monster → `EntitySkeleton`（EntityArrow 攻击）、`EntityGhast`（EntityLargeFireball）、`EntityBlaze`（EntitySmallFireball 目标；EntitySnowball 对其 3 点伤害）、`EntityWitch`（EntityPotion）、`EntitySnowman`（EntitySnowball）
- net/minecraft/entity/boss → `EntityWither`（发射 EntityWitherSkull / `EntityWither.canDestroyBlock` 被 EntityWitherSkull#getExplosionResistance 调用）
- net/minecraft/client/network → `NetHandlerPlayClient#handleSpawnObject`（按 spawn type 构造全部投射物实体）
- net/minecraft/entity → `EntityList`（注册表 ID 映射）、`EntityTrackerEntry`（服务端同步频率/速度包）、`IProjectile#setThrowableHeading`（EntityArrow、EntityThrowable 实现）
- net/minecraft/client/renderer/entity → RenderManager 注册的对应 Render 类（LayerArrow 读取玩家身上箭数、RenderWitherSkull 读 isInvulnerable）
- net/minecraft/enchantment → `EnchantmentHelper#applyThornEnchantments/applyArthropodEnchantments`（EntityArrow#onUpdate）、`#getLureModifier/getLuckOfSeaModifier`（EntityFishHook）
- net/minecraft/util → `DamageSource#causeArrowDamage/causeThrownDamage/causeFireballDamage/causeMobDamage`
- net/minecraft/potion → `Potion.potionTypes[i].affectEntity` / `PotionEffect`（EntityPotion、EntityWitherSkull）
- net/minecraft/world → `World#rayTraceBlocks/getEntitiesWithinAABBExcludingEntity/newExplosion/spawnParticle`、`WorldServer#getEntityFromUuid/spawnParticle`
- net/minecraft/network/play/server → `S2BPacketChangeGameState`（EntityArrow 命中玩家提示音）
- net/minecraft/stats → `StatList.junkFishedStat/treasureFishedStat/fishCaughtStat`（EntityFishHook#getFishingResult）

## 覆盖声明

完整读取了 10/10 个文件。逐行精读：EntityArrow、EntityThrowable、EntityFireball、EntityFishHook、EntityWitherSkull、EntityPotion。逐行读过但内容简单（单一 onImpact 覆写）：EntityEgg、EntitySnowball、EntitySmallFireball、EntityLargeFireball。行号引用均来自本次 Read 输出；交叉引用调用方经 grep 验证（NetHandlerPlayClient.java:297/315/322/362/371/433、EntityList.java:306-318、ItemFishingRod.java:40-42）。未验证的推断已列入 openQuestions。
