---
area: net/minecraft/entity/ai
slug: mc-entity-ai
files: 66
lines: 6592
tier: C
---

# net/minecraft/entity/ai — 实体 AI 与属性系统

## 定位

本包是 1.8.9 的"新 AI"系统：以 `EntityAIBase` 为原子任务、`EntityAITasks` 为优先级调度器的行为树（准确说是优先级 + mutex 位掩码的任务选择器），外加四个逐 tick 的运动辅助器（`EntityLookHelper` / `EntityMoveHelper` / `EntityJumpHelper` / `EntitySenses`）和 `attributes/` 子包的实体属性系统（maxHealth、movementSpeed、followRange 等，带修饰符叠加）。

- **谁调用它**：`EntityLiving` 持有 `tasks` / `targetTasks` 两个 `EntityAITasks`（EntityLiving.java:55/58，构造于 :82-83），并在 `updateEntityActionState()`（EntityLiving.java:619-649）里逐 tick 驱动整个包。该方法只在 `EntityLivingBase.isServerWorld()`（即 `!worldObj.isRemote`，EntityLivingBase.java:2203-2206）为真时被调用（EntityLivingBase.java:1998-2003），所以**在本客户端仓库中 AI 只跑在集成服务器（单机）侧**；连服务器时远端实体在客户端不执行任何 AI。属性系统则两侧都用：客户端通过 `S20PacketEntityProperties` 同步（NetHandlerPlayClient.java:2050）。
- **它调用谁**：`net.minecraft.pathfinding`（`PathNavigate` / `PathNavigateGround` / `PathEntity`）、`net.minecraft.world.World`（方块查询、`getEntitiesWithinAABB`、`setEntityState`）、`net.minecraft.village`（村庄门、聚居信息）、各具体实体类（`EntityWolf`、`EntityVillager`、`EntityHorse`、`EntityCreeper` 等）的状态 setter。
- **消失会坏什么**：单机模式下所有生物变成"植物人"（不动、不攻击、不逃跑）；所有实体属性（生命上限、移速、攻击力）失效，`EntityLivingBase` 初始化直接 NPE；`S20PacketEntityProperties` 无法落地，客户端连远端服务器也会崩。`EntityMinecartMobSpawner` 是个"住错楼"的类（刷怪笼矿车实体，与 AI 无关，历史上就放在这个包）。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| EntityAIArrowAttack | 143 | EntityAIBase | 远程攻击：向 `IRangedAttackMob` 宿主提供接近+冷却+`attackEntityWithRangedAttack` 循环 |
| EntityAIAttackOnCollide | 155 | EntityAIBase | 近战追击：寻路逼近 attackTarget，进入距离后 `attackEntityAsMob` |
| EntityAIAvoidEntity\<T\> | 126 | EntityAIBase | 躲避指定类型实体：用 `RandomPositionGenerator.findRandomTargetBlockAwayFrom` 找逃点 |
| EntityAIBase | 71 | (abstract) | 所有 AI 任务的基类：shouldExecute/continueExecuting/start/reset/update + mutexBits |
| EntityAIBeg | 77 | EntityAIBase | 狼看到手持骨头/繁殖物的玩家时抬头乞食（`setBegging`） |
| EntityAIBreakDoor | 107 | EntityAIDoorInteract | 僵尸砸门：240 tick 进度条，仅 HARD 难度真正破坏 |
| EntityAIControlledByPlayer | 227 | EntityAIBase | 猪被玩家骑乘时的转向/加速/跳跃控制，胡萝卜钓竿损耗 |
| EntityAICreeperSwell | 70 | EntityAIBase | 苦力怕距目标 <3 格时 `setCreeperState(1)` 开始膨胀 |
| EntityAIDefendVillage | 70 | EntityAITarget | 铁傀儡把村庄侵略者设为攻击目标 |
| EntityAIDoorInteract | 118 | EntityAIBase (abstract) | 门交互基类：从路径中找出 2.25 距离内的木门 |
| EntityAIEatGrass | 121 | EntityAIBase | 羊吃草：40 tick 计时，第 4 tick 时改方块并 `eatGrassBonus()` |
| EntityAIFindEntityNearest | 116 | EntityAIBase | 非 EntityCreature 生物（如史莱姆）找最近的指定类目标 |
| EntityAIFindEntityNearestPlayer | 163 | EntityAIBase | 非 EntityCreature 生物找最近可攻击玩家（考虑潜行/隐身折减） |
| EntityAIFleeSun | 94 | EntityAIBase | 白天燃烧时随机找 10 次遮荫点逃离阳光 |
| EntityAIFollowGolem | 103 | EntityAIBase | 幼年村民白天跟随手持玫瑰的铁傀儡并取走玫瑰 |
| EntityAIFollowOwner | 149 | EntityAIBase | 宠物跟随主人，>12 格寻路失败时直接 `setLocationAndAngles` 传送 |
| EntityAIFollowParent | 112 | EntityAIBase | 幼年动物跟随 8 格内最近的成年同类 |
| EntityAIHarvestFarmland | 166 | EntityAIMoveToBlock | 农民村民收割成熟作物（AGE==7）并补种种子/土豆/胡萝卜 |
| EntityAIHurtByTarget | 74 | EntityAITarget | 被打后反击，可选广播求援（同类 AABB 内 setAttackTarget） |
| EntityAILeapAtTarget | 63 | EntityAIBase | 距目标 2-4 格时按 leapMotionY 扑向目标 |
| EntityAILookAtTradePlayer | 31 | EntityAIWatchClosest | 村民交易中注视顾客（`getCustomer()`） |
| EntityAILookAtVillager | 72 | EntityAIBase | 铁傀儡低概率(1/8000)注视村民 400 tick 并举玫瑰 |
| EntityAILookIdle | 62 | EntityAIBase | 2% 概率随机看向一个方向 20-40 tick |
| EntityAIMate | 160 | EntityAIBase | 动物繁殖：靠近爱心状态同类 60 tick 后 `spawnBaby()` |
| EntityAIMoveIndoors | 103 | EntityAIBase | 夜晚/下雨时村民走向村庄门内侧位置 |
| EntityAIMoveThroughVillage | 172 | EntityAIBase | 在村庄门之间巡逻，维护最近走过的 doorList（上限 15） |
| EntityAIMoveToBlock | 137 | EntityAIBase (abstract) | 螺旋搜索满足 `shouldMoveTo` 的方块并走过去，200+ tick 冷却 |
| EntityAIMoveTowardsRestriction | 65 | EntityAIBase | 超出 home 范围时向 `getHomePosition()` 方向回移 |
| EntityAIMoveTowardsTarget | 85 | EntityAIBase | 向 attackTarget 方向随机点移动（限 maxTargetDistance 内） |
| EntityAINearestAttackableTarget\<T\> | 136 | EntityAITarget | 标准索敌：AABB 内按距离排序选最近合适目标；内含 `Sorter` |
| EntityAIOcelotAttack | 87 | EntityAIBase | 豹猫扑击：按距离切换 0.6/0.8/1.33 三档速度接近并攻击 |
| EntityAIOcelotSit | 114 | EntityAIMoveToBlock | 驯服豹猫走到箱子/点燃熔炉/床脚上坐下 |
| EntityAIOpenDoor | 58 | EntityAIDoorInteract | 开门（`toggleDoor(...true)`），可选 20 tick 后关门 |
| EntityAIOwnerHurtByTarget | 60 | EntityAITarget | 主人被谁打，宠物就打谁（revengeTimer 变化触发） |
| EntityAIOwnerHurtTarget | 60 | EntityAITarget | 主人打了谁，宠物就打谁（`getLastAttacker()`） |
| EntityAIPanic | 63 | EntityAIBase | 被攻击或着火时向随机点逃窜 |
| EntityAIPlay | 124 | EntityAIBase | 幼年村民互相追逐玩耍 1000 tick（`setPlaying`） |
| EntityAIRestrictOpenDoor | 84 | EntityAIBase | 夜晚村民待在门内侧时禁用 navigator 的 enter/break doors |
| EntityAIRestrictSun | 38 | EntityAIBase | 白天让 `PathNavigateGround.setAvoidSun(true)` |
| EntityAIRunAroundLikeCrazy | 94 | EntityAIBase | 未驯服的马被骑时乱跑，按 temper 概率驯服或甩人 |
| EntityAISit | 67 | EntityAIBase | 驯服宠物的坐下开关（外部 `setSitting` 控制意图） |
| EntityAISwimming | 35 | EntityAIBase | 在水/岩浆中 80% 概率触发 jumpHelper 上浮 |
| EntityAITarget | 258 | EntityAIBase (abstract) | 索敌任务基类：视线/距离/队伍/无敌校验，静态 `isSuitableTarget` |
| EntityAITargetNonTamed\<T\> | 24 | EntityAINearestAttackableTarget | 仅在未驯服时索敌（狼猎羊、豹猫猎鸡） |
| EntityAITasks | 184 | - | AI 调度器：优先级 + mutexBits 决定并发；每 3 tick 重选任务 |
| EntityAITempt | 171 | EntityAIBase | 被手持特定物品的玩家吸引跟随；可被玩家突然移动吓跑 |
| EntityAITradePlayer | 60 | EntityAIBase | 村民交易期间站定不动（clearPathEntity） |
| EntityAIVillagerInteract | 102 | EntityAIWatchClosest2 | 村民互动时向缺粮同伴抛掷面包/作物 |
| EntityAIVillagerMate | 141 | EntityAIBase | 村民繁殖：受门数上限（villagers < doors*0.35）约束 |
| EntityAIWander | 94 | EntityAIBase | 随机漫步：1/executionChance 概率选 `findRandomTarget(10,7)` |
| EntityAIWatchClosest | 99 | EntityAIBase | 注视最近的指定类实体 40-80 tick（mutex 2） |
| EntityAIWatchClosest2 | 13 | EntityAIWatchClosest | 同上但 mutex 3（与移动互斥），村民用 |
| EntityJumpHelper | 28 | - | 跳跃意图缓存：`setJumping()` 置位，`doJump()` 消费 |
| EntityLookHelper | 144 | - | 头部朝向插值：目标点 → rotationYawHead/rotationPitch |
| EntityMinecartMobSpawner | 86 | EntityMinecart | 刷怪笼矿车（与 AI 无关，历史遗留放此包）；内嵌匿名 `MobSpawnerBaseLogic` |
| EntityMoveHelper | 121 | - | 移动意图缓存：setMoveTo → 转 yaw、setAIMoveSpeed、近距上坡触发跳 |
| EntitySenses | 59 | - | 每 tick 缓存的视线判定（seenEntities/unseenEntities 两个 List） |
| RandomPositionGenerator | 129 | - | 静态工具：随机采样 10 个点选 `getBlockPathWeight` 最高者 |
| attributes/AttributeModifier | 112 | - | 属性修饰符值对象：UUID + name + amount + operation(0/1/2) + isSaved |
| attributes/BaseAttribute | 57 | IAttribute (abstract) | 属性定义基类：unlocalizedName、defaultValue、shouldWatch、父属性 |
| attributes/BaseAttributeMap | 88 | (abstract) | 实体的属性注册表：按 IAttribute 与小写名双索引，批量 apply/remove |
| attributes/IAttribute | 14 | interface | 属性定义接口：name/clampValue/defaultValue/shouldWatch/父属性 |
| attributes/IAttributeInstance | 35 | interface | 属性实例接口：base value + modifier 增删查 + `getAttributeValue()` |
| attributes/ModifiableAttributeInstance | 206 | IAttributeInstance | 属性实例实现：三个 operation 桶、脏标记缓存、级联父属性 |
| attributes/RangedAttribute | 47 | BaseAttribute | 带 [min,max] 钳制的属性；description 用于 NBT 别名 |
| attributes/ServersideAttributeMap | 88 | BaseAttributeMap | 服务端属性表：追踪 shouldWatch 的脏实例集合供网络同步 |

## 核心类详解

### EntityAITasks（调度器）

字段：`List<EntityAITasks.EntityAITaskEntry> taskEntries` / `executingTaskEntries`（EntityAITasks.java:13-14）、`private final Profiler theProfiler`（:17）、`private int tickCount`、`private int tickRate = 3`（:18-19）。内部类 `EntityAITaskEntry { public EntityAIBase action; public int priority; }`（:173-183）。

关键方法（签名逐字）：

- `public void addTask(int priority, EntityAIBase task)` — EntityAITasks.java:29。priority 越小越优先。
- `public void removeTask(EntityAIBase task)` — :37。若正在执行会先 `resetTask()`。
- `public void onUpdateTasks()` — :59。每 `tickRate`(=3) tick 做一次完整重选（:63-101）：对每个 entry，正在执行的先检查 `canUse` && `canContinue`，失败则 `resetTask()` 并移出；未执行的检查 `canUse` && `shouldExecute()` 通过则 `startExecuting()` 加入。其余 tick 只淘汰 `!canContinue` 的（:104-116）。最后对所有执行中任务调 `updateTask()`（:121-124）。
- `private boolean canUse(EntityAITasks.EntityAITaskEntry taskEntry)` — :142。规则：对更高优先级(数值更小)的执行中任务要求 mutex 兼容；对更低优先级的执行中任务要求其 `isInterruptible()`。
- `private boolean areTasksCompatible(...)` — :168，即 `(a.getMutexBits() & b.getMutexBits()) == 0`（:170）。

调用者：`EntityLiving.updateEntityActionState()` 中 `this.targetTasks.onUpdateTasks()`（EntityLiving.java:629）和 `this.tasks.onUpdateTasks()`（:632），带 profiler section `targetSelector` / `goalSelector`。mutexBits 惯例（从各任务 setMutexBits 归纳）：bit0(1)=移动/寻路，bit1(2)=头部注视，bit2(4)=跳跃/游泳类，7=独占。

### EntityAIBase（任务契约）

`private int mutexBits`（EntityAIBase.java:9）。五个生命周期方法：`public abstract boolean shouldExecute()`（:14）、`public boolean continueExecuting()`（:19，默认转发 shouldExecute）、`public boolean isInterruptible()`（:28，默认 true）、`public void startExecuting()`（:36）、`public void resetTask()`（:43）、`public void updateTask()`（:50）。`setMutexBits(int mutexBitsIn)` :58 / `getMutexBits()` :67。所有子类只被 `EntityAITasks` 调用；顺序保证：startExecuting 先于 updateTask，resetTask 在停止时恰好一次。

### EntityAITarget（索敌基类）

字段：`protected final EntityCreature taskOwner`（EntityAITarget.java:20）、`protected boolean shouldCheckSight`（:25）、`private boolean nearbyOnly`（:30）、`private int targetSearchStatus / targetSearchDelay / targetUnseenTicks`（:35-46）。

- `public boolean continueExecuting()` — :63。目标死亡/同队/超出 `getTargetDistance()`/连续 60 tick 不可见（:100-103）/创造模式玩家 均放弃。
- `protected double getTargetDistance()` — :112，读 `SharedMonsterAttributes.followRange` 属性，缺省 16.0D。
- `public void resetTask()` — :131，`this.taskOwner.setAttackTarget((EntityLivingBase)null)`。
- `public static boolean isSuitableTarget(EntityLiving attacker, EntityLivingBase target, boolean includeInvincibles, boolean checkSight)` — :139。null/自身/死亡/`canAttackClass` 否/同队/同主人/无敌玩家 逐项排除，最后视线检查（:185）。这是全包索敌合法性的单一事实来源，被 `EntityAIFindEntityNearest`、`EntityAIFindEntityNearestPlayer` 等外部复用。
- `private boolean canEasilyReach(EntityLivingBase target)` — :233。nearbyOnly 模式下用寻路终点距目标 ≤2.25D²判可达（:254）。

### EntityLookHelper / EntityMoveHelper / EntityJumpHelper（意图缓存三件套）

模式统一：AI 任务在 `updateTask()` 里写入意图，`EntityLiving.updateEntityActionState()` 在所有任务跑完后统一消费——因此**同 tick 内后写的任务覆盖先写的**。

- `public void setLookPositionWithEntity(Entity entityIn, float deltaYaw, float deltaPitch)`（EntityLookHelper.java:36）与 `setLookPosition(double x, double y, double z, float deltaYaw, float deltaPitch)`（:58）置位 `isLooking`；`public void onUpdateLook()`（:71）做角度插值，注意首行 `this.entity.rotationPitch = 0.0F`（:73），且无路径时把头偏转钳在 ±75°（:94-105）。消费点 EntityLiving.java:644。
- `public void setMoveTo(double x, double y, double z, double speedIn)`（EntityMoveHelper.java:40）；`public void onUpdateMoveHelper()`（:49）先 `setMoveForward(0.0F)`，有意图时转 yaw（限 30°/tick）、`setAIMoveSpeed((float)(this.speed * this.entity.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue()))`（:66），目标高于自己且水平距 <1 时触发跳（:68-71）。消费点 EntityLiving.java:642。速度语义：AI 传的 speed 是 movementSpeed 属性的**倍率**。
- `public void setJumping()`（EntityJumpHelper.java:15）/ `public void doJump()`（:23）把标志转交 `entity.setJumping(...)` 后清零。消费点 EntityLiving.java:646。

### attributes/ModifiableAttributeInstance + BaseAttributeMap（属性系统）

`ModifiableAttributeInstance` 字段：`mapByOperation: Map<Integer, Set<AttributeModifier>>`、`mapByName: Map<String, Set<AttributeModifier>>`、`mapByUUID: Map<UUID, AttributeModifier>`、`double baseValue`、`boolean needsUpdate = true`、`double cachedValue`（ModifiableAttributeInstance.java:18-23）。

- `public void applyModifier(AttributeModifier modifier)` — :89。同 UUID 重复施加抛 `IllegalArgumentException("Modifier is already applied on this attribute!")`（:93）。
- `public double getAttributeValue()` — :155，脏标记缓存。`private double computeValue()` — :166：op0 求和加到 base，op1 按原始 op0 结果的百分比累加，op2 连乘 `(1.0D + amount)`，最终 `this.genericAttribute.clampValue(d1)`（:187）。`func_180375_b(int operation)`（:190）还会沿 `IAttribute.func_180372_d()` 父属性链收集修饰符。
- `protected void flagForUpdate()` — :112，同时通知 `this.attributeMap.func_180794_a(this)`。

`BaseAttributeMap.registerAttribute(IAttribute attribute)`（BaseAttributeMap.java:30）重名抛异常，并把父属性依赖登记进 `field_180377_c`。`ServersideAttributeMap.func_180794_a`（ServersideAttributeMap.java:51）把 shouldWatch 的脏实例收进 `attributeInstanceSet`，供 `EntityTrackerEntry` 每 tick 摘走生成 `S20PacketEntityProperties`（EntityTrackerEntry.java:320、396）。注意：本仓库 `EntityLivingBase.getAttributeMap()`（EntityLivingBase.java:1429-1433）**无条件 new ServersideAttributeMap()**，客户端实体也用服务端实现。

## 时序与生命周期

1. **构造期**：`EntityLiving` 构造器创建 `tasks` / `targetTasks`（EntityLiving.java:82-83）以及 lookHelper/moveHelper/jumpHelper/senses/navigator；各具体实体子类构造器里用 `this.tasks.addTask(priority, new EntityAIXxx(...))` 装配任务表。属性在 `EntityLivingBase.applyEntityAttributes()`（EntityLivingBase.java:221-223 注册 maxHealth/knockbackResistance/movementSpeed）及子类覆写中注册。
2. **每 tick（仅 `isServerWorld()`，即集成服务器线程 "Server thread"）**：`EntityLivingBase.onLivingUpdate` → `updateEntityActionState()`（EntityLivingBase.java:2001；EntityLiving 覆写于 EntityLiving.java:619），顺序固定：
   `despawnEntity` → `senses.clearSensingCache()`(:626) → `targetTasks.onUpdateTasks()`(:629) → `tasks.onUpdateTasks()`(:632) → `navigator.onUpdateNavigation()`(:635) → `updateAITasks()`(:638，子类钩子) → `moveHelper.onUpdateMoveHelper()`(:642) → `lookHelper.onUpdateLook()`(:644) → `jumpHelper.doJump()`(:646)。
   任务重选只在 `tickCount % 3 == 0` 的 tick 发生；`updateTask()` 每 tick 都跑。
3. **每帧**：本包无任何逐帧逻辑，不碰渲染。
4. **网络侧（客户端主线程）**：`NetHandlerPlayClient.handleEntityProperties`（NetHandlerPlayClient.java:2050）经 `PacketThreadUtil.checkThreadAndEnqueue` 转到客户端主线程后，把 S20 快照写进实体 attributeMap（setBaseValue → removeAllModifiers → 逐个 applyModifier，:2074-2081）。属性是本包唯一在纯客户端场景仍活跃的部分。
5. **NBT**：属性随实体存档读写（EntityLivingBase.java:531 写 / :563 读，经 `SharedMonsterAttributes`）；`EntityMinecartMobSpawner` 的刷怪逻辑在 `readEntityFromNBT`/`writeEntityToNBT`（EntityMinecartMobSpawner.java:53-66）中持久化，其 `onUpdate()`（:76）每 tick 调 `mobSpawnerLogic.updateSpawner()`。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void onUpdateTasks()` | EntityAITasks.java:59 | 每实体每 tick 两次（targetTasks/tasks），EntityLiving.java:629/632 | 整体接管/冻结某实体 AI、观察任务切换、插入自定义调度策略 | 仅集成服务器侧生效；重选每 3 tick 一次，即时性有限 |
| `public void addTask(int priority, EntityAIBase task)` / `public void removeTask(EntityAIBase task)` | EntityAITasks.java:29 / :37 | 实体构造期，也可运行时动态调用 | 给任意生物注入自定义 `EntityAIBase` 子类（最干净的行为扩展点） | 注意 mutexBits 冲突与 priority 取值；`tasks` / `targetTasks` 是 `protected`，需在实体子类或反射访问 |
| `public boolean shouldExecute()` 等五个生命周期方法 | EntityAIBase.java:14-52 | 由 onUpdateTasks 按状态机调用 | 覆写实现任意自定义行为；包装既有任务做开关 | `continueExecuting` 默认转发 `shouldExecute`，覆写时留意语义差异 |
| `public static boolean isSuitableTarget(EntityLiving attacker, EntityLivingBase target, boolean includeInvincibles, boolean checkSight)` | EntityAITarget.java:139 | 所有索敌任务选目标时 | 全局改写"谁能打谁"（反仇恨、友军保护等） | static，需字节码级替换或改源；多个类各自复制了玩家潜行/隐身折减逻辑，不全走这里 |
| `public void resetTask()` | EntityAITarget.java:131 | 索敌任务停止时 | 观察/拦截仇恨清除（`setAttackTarget(null)`） | — |
| `public boolean canSee(Entity entityIn)` | EntitySenses.java:31 | 各任务视线判定，缓存一 tick | 实现"隐身于 AI"或强制可见 | 缓存在 :626 每 tick 清一次；直接改返回值影响所有 AI |
| `public void onUpdateLook()` | EntityLookHelper.java:71 | EntityLiving.java:644，每 tick | 接管生物头部朝向（做注视效果、锁头） | 首行强制 `rotationPitch = 0.0F`；后写覆盖先写 |
| `public void onUpdateMoveHelper()` | EntityMoveHelper.java:49 | EntityLiving.java:642，每 tick | 接管移动意图；speed 倍率 × movementSpeed 属性在 :66 结算 | 每 tick 先 `setMoveForward(0.0F)`，不持续 setMoveTo 就会停 |
| `public void doJump()` | EntityJumpHelper.java:23 | EntityLiving.java:646，每 tick | 强制/禁止跳跃 | 单 tick 脉冲语义，消费后即清零 |
| `public double getAttributeValue()` | ModifiableAttributeInstance.java:155 | 所有伤害/移速/追踪距离计算 | 属性显示（HUD）、数值改写 | 有缓存，改动要走 `applyModifier`/`setBaseValue` 触发 `flagForUpdate` |
| `public void applyModifier(AttributeModifier modifier)` | ModifiableAttributeInstance.java:89 | 装备/药水/S20 包落地时 | 观察属性变更、注入自定义 buff | 同 UUID 二次施加直接抛异常，先 `hasModifier` 检查 |
| `public void handleEntityProperties(S20PacketEntityProperties packetIn)` | NetHandlerPlayClient.java:2050 | 服务器同步实体属性时（客户端主线程） | 功能层读取远端实体真实 maxHealth/movementSpeed 的唯一入口 | 包外类；对非 EntityLivingBase 抛 IllegalStateException |
| `public void boostSpeed()` | EntityAIControlledByPlayer.java:213 | 玩家骑猪右键胡萝卜钓竿时 | 观察/修改骑乘加速（140-980 tick 随机时长） | 仅单机有效 |
| `public void onUpdate()` | EntityMinecartMobSpawner.java:76 | 刷怪笼矿车每 tick | 拦截矿车刷怪 | 与 AI 无关；客户端亦 tick（渲染粒子由 logic 内部区分） |

## 数据与协议

**NBT — 实体 `Attributes` 列表**（写：SharedMonsterAttributes.java:42-80；读：:82-126；挂在 EntityLivingBase.java:531/563）：

| 字段 | 类型 | 读写方法 | 含义 |
|---|---|---|---|
| `Name` | String | `writeAttributeInstanceToNBT` / `setAttributeModifiers` | 属性名，如 `generic.movementSpeed`（`BaseAttributeMap.attributesByName` 为小写不敏感 LowerStringMap） |
| `Base` | Double | 同上 | 基础值 |
| `Modifiers` | TagList(10) | 同上 | 修饰符数组；仅 `isSaved()==true` 的写入（AttributeModifier.java:18 注释：冲刺加速等"自然"修饰符不落盘） |
| `Modifiers[].Name` | String | `writeAttributeModifierToNBT` / `readAttributeModifierFromNBT` | 修饰符名（非空校验 AttributeModifier.java:32） |
| `Modifiers[].Amount` | Double | 同上 | 数值 |
| `Modifiers[].Operation` | Int | 同上 | 0=加法，1=基于 op0 结果的百分比加成，2=最终乘 (1+amount)；合法域 [0,2]（AttributeModifier.java:33） |
| `Modifiers[].UUIDMost/UUIDLeast` | Long | 同上 | 修饰符 UUID（等值判定只看 UUID，AttributeModifier.java:73-101） |

**协议 — `S20PacketEntityProperties`**：服务端 `EntityTrackerEntry` 从 `ServersideAttributeMap.getAttributeInstanceSet()`（增量，EntityTrackerEntry.java:320）或 `getWatchedAttributes()`（首次 spawn，:396）取 shouldWatch 属性发包；客户端在 NetHandlerPlayClient.java:2065-2081 落地：未知属性名会即席注册为 `new RangedAttribute((IAttribute)null, name, 0.0D, 2.2250738585072014E-308D, Double.MAX_VALUE)`（:2071）。shouldWatch==true 的仅 `generic.maxHealth` 和 `generic.movementSpeed`（SharedMonsterAttributes.java:18/21）。

AI 任务本身不直接收发封包；间接的世界副作用有：`world.sendBlockBreakProgress`（EntityAIBreakDoor.java:76/96，砸门进度）、`world.setEntityState`（EntityAIEatGrass.java:56 byte 10；EntityAIRunAroundLikeCrazy.java:81/91 byte 7/6；EntityAIVillagerMate.java:112/139 byte 12）、`world.playAuxSFX`（EntityAIBreakDoor.java:88/103/104）。

## 不变量与陷阱

- **AI 只跑单机**：`updateEntityActionState` 被 `isServerWorld()` 守卫（EntityLivingBase.java:1998-2003）。想在联机客户端"预测"生物行为不能靠本包。
- **mutexBits 是唯一并发约束**：两个任务同时跑当且仅当 `bits1 & bits2 == 0` 且优先级规则允许。加自定义任务时给错 bits 会出现"边走边坐"类 bug。
- **priority 数值越小越优先**；`canUse` 对低优先级任务只看 `isInterruptible()`（EntityAITasks.java:155），vanilla 全部返回 true。
- **helper 后写覆盖**：同 tick 多个任务写 lookHelper/moveHelper，只有最后一个生效；顺序即 `executingTaskEntries` 的插入顺序，不是 priority 顺序。
- **`RandomPositionGenerator.staticVector` 明确非线程安全**（RandomPositionGenerator.java:12-15 原注释 "WARNING: NEVER THREAD SAFE"）。整个包默认单线程（集成服务器线程）访问；不要从 Netty EventLoop 或渲染线程碰任何 AI/寻路状态。
- **构造期强制导航器类型**：`EntityAIDoorInteract`（:31-34）、`EntityAIFollowOwner`（:37-40）、`EntityAIMoveThroughVillage`（:32-35）、`EntityAITempt`（:57-60）、`EntityAIRestrictOpenDoor`（:18-21）都在构造器里对 `PathNavigateGround` 做 instanceof 检查并抛 `IllegalArgumentException`——给飞行/水生生物装这些任务直接炸。`EntityAIArrowAttack` 要求宿主是 `EntityLivingBase`（:44-47）。
- **applyModifier 抛异常而非幂等**（ModifiableAttributeInstance.java:91-93）；`BaseAttributeMap.applyAttributeModifiers` 之所以先 remove 再 apply（BaseAttributeMap.java:83-84）就是为绕开它。自定义代码请模仿。
- **本仓库客户端也用 ServersideAttributeMap**（EntityLivingBase.java:1433），与 attributes 包内不存在 "ClientsideAttributeMap" 一致——不要按原版 wiki 假设有客户端专用实现。
- **`EntityAITasks` 的 profiler 可能为 null**：EntityLiving.java:82 传入 `worldIn != null && worldIn.theProfiler != null ? worldIn.theProfiler : null`，而 `onUpdateTasks` 无 null 检查（EntityAITasks.java:61）——无 world 构造的实体一旦 tick AI 会 NPE。
- **JDK25/移植相关**：`AttributeModifier(String,double,int)` 用 `ThreadLocalRandom.current()` 生成 UUID（AttributeModifier.java:22），依赖 `MathHelper.getRandomUuid`；`EntityAITasks.onUpdateTasks` 里的 raw `Iterator` + label 跳转（:65-100）是反编译产物，重构时注意 label38 的退出语义。本包无任何 LWJGL 依赖，移植风险集中在包外。
- **索敌的"玩家感知折减"逻辑重复三份**（EntityAIFindEntityNearestPlayer.java:55-74、EntityAINearestAttackableTarget.java:51-76、EntityAIFindEntityNearest.java:38-45），改隐身机制要三处同改。
- `EntityAIHurtByTarget.startExecuting` 的求援判断 `if (!flag)`（EntityAIHurtByTarget.java:59）语义是 targetClasses 为**排除**列表（列出的类不被传染仇恨），与直觉相反。

## 交叉引用

- net/minecraft/entity → `EntityLiving#updateEntityActionState`（驱动全包）、`EntityLiving#tasks/targetTasks`、`EntityLiving#getLookHelper/getMoveHelper/getJumpHelper/getEntitySenses/getNavigator`、`EntityLivingBase#getAttributeMap`、`EntityLivingBase#getEntityAttribute`、`SharedMonsterAttributes#followRange/movementSpeed`（属性常量与 NBT 编解码）
- net/minecraft/pathfinding → `PathNavigate#tryMoveToEntityLiving/tryMoveToXYZ/setPath/noPath/clearPathEntity`、`PathNavigateGround#setAvoidsWater/setAvoidSun/setBreakDoors/setEnterDoors/getEnterDoors`、`PathEntity#getFinalPathPoint`
- net/minecraft/world → `World#getEntitiesWithinAABB/getClosestPlayerToEntity/findNearestEntityWithinAABB/setEntityState/destroyBlock/setBlockState/playAuxSFX/sendBlockBreakProgress`、`World#getVillageCollection`
- net/minecraft/village → `Village#findNearestVillageAggressor/getDoorInfo/getNearestDoor/isMatingSeason`、`VillageDoorInfo#getInsideBlockPos/getDoorBlockPos`
- net/minecraft/entity/passive · monster → `EntityWolf#setBegging`、`EntityVillager#getCustomer/getVillagerInventory/setMating`、`EntityCreeper#setCreeperState`、`EntityIronGolem#setHoldingRose`、`EntityHorse#setTamedBy/increaseTemper`、`EntityTameable#isTamed/getOwner/setSitting/getAISit`
- net/minecraft/entity（tracker）→ `EntityTrackerEntry#sendMetadataToAllAssociatedPlayers`（EntityTrackerEntry.java:320 取 `ServersideAttributeMap#getAttributeInstanceSet`）、spawn 流程（:396 取 `getWatchedAttributes`）
- net/minecraft/client/network → `NetHandlerPlayClient#handleEntityProperties`（S20 → `BaseAttributeMap#getAttributeInstanceByName/registerAttribute`）
- net/minecraft/network/play/server → `S20PacketEntityProperties`（属性同步载体）
- net/minecraft/tileentity → `MobSpawnerBaseLogic#updateSpawner/readFromNBT/writeToNBT`（EntityMinecartMobSpawner 内嵌）
- net/minecraft/world/pathfinder → `WalkNodeProcessor.func_176170_a`（EntityAIControlledByPlayer.java:168 骑猪跳跃判定）
- net/minecraft/scoreboard → `Team`（EntityAITarget 同队免伤判定）

## 覆盖声明

完整读取了 66/66 个文件（全部经 Read 工具整读，无抽样）。逐行精读：`EntityAITasks`、`EntityAIBase`、`EntityAITarget`、`EntityAIControlledByPlayer`、`ModifiableAttributeInstance`、`EntityLookHelper`、`EntityMoveHelper`、`EntityJumpHelper`、`EntitySenses`、`RandomPositionGenerator`、`BaseAttributeMap`、`ServersideAttributeMap`、`AttributeModifier`、`EntityAINearestAttackableTarget`、`EntityAIHurtByTarget`、`EntityMinecartMobSpawner`。其余 50 个具体任务类为整读但按"构造参数 + 五个生命周期方法"的结构快速精读（这些类结构高度模板化）。另精读了包外关联段落：EntityLiving.java:54-90、:610-660，EntityLivingBase.java:1995-2010、:1426-1433、:2200-2206，NetHandlerPlayClient.java:2045-2084，SharedMonsterAttributes.java:15-145，EntityTrackerEntry.java:320/396（grep 定位）。所有行号均经 Read/grep 核实。
