---
area: net/minecraft/entity/monster
slug: mc-entity-monster
files: 21
lines: 6697
tier: C
---

# net/minecraft/entity/monster

## 定位

本包是 1.8.9 中全部敌对生物（以及三个中立傀儡类）的实体逻辑实现：僵尸、骷髅、苦力怕、末影人、烈焰人、守卫者、史莱姆家族、蜘蛛家族、蠹虫、女巫、恶魂、僵尸猪人，加上铁傀儡 / 雪傀儡。每个类 = 一种生物的 AI 任务表、属性基值、DataWatcher 同步字段、NBT 存档字段、掉落表、音效名。

调用方：`net.minecraft.entity.EntityList` 按字符串 id 反射构造这些类（存档加载、S0FPacketSpawnMob 客户端侧生成、刷怪笼）；服务端 `World#updateEntities` → `Entity#onUpdate` 驱动逻辑；客户端渲染层（`net.minecraft.client.renderer.entity.RenderZombie` 等）读取这些类暴露的状态 getter（`getCreeperFlashIntensity`、`isScreaming`、`getSlimeSize`、`getAttackTimer`...）来驱动模型动画。被调用方：`entity.ai` 包（所有 AI 任务）、`entity.projectile`（火球/箭/药水投掷物）、`pathfinding`（自定义 Navigator）、`world`（方块读写、粒子、爆炸）。

注意：这是单机一体化代码，同一个类同时承担服务端逻辑（integrated server 线程）和客户端表现（`worldObj.isRemote` 分支）。如果它消失，任何敌对生物都无法实例化——存档加载、怪物生成包处理、渲染绑定全部断裂。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| EntityBlaze | 309 | extends EntityMob | 烈焰人：悬浮、火球三连发（内部类 AIFireballAttack），水会造成 drown 伤害 |
| EntityCaveSpider | 71 | extends EntitySpider | 洞穴蜘蛛：小体型 + 普通/困难难度攻击附加 poison |
| EntityCreeper | 315 | extends EntityMob | 苦力怕：引信状态机（DataWatcher 16/17/18）、爆炸、闪电充能、打火石点燃 |
| EntityEnderman | 617 | extends EntityMob | 末影人：随机/受击传送、注视激怒判定（AIFindPlayer）、搬运方块（AIPlaceBlock/AITakeBlock） |
| EntityEndermite | 197 | extends EntityMob | 末影螨：2400 tick 寿命自毁、playerSpawned 标记（被末影人仇恨的条件） |
| EntityGhast | 412 | extends EntityFlying implements IMob | 恶魂：自定义 GhastMoveHelper 自由飞行、大火球攻击、被玩家反弹火球击杀判 1000 伤害 |
| EntityGiantZombie | 32 | extends EntityMob | 巨人僵尸：6 倍体型 + 属性覆盖，无 AI 任务 |
| EntityGolem | 57 | extends EntityCreature implements IAnimals | 傀儡抽象基类：无摔落伤害、不消失、音效占位 "none" |
| EntityGuardian | 745 | extends EntityMob | 守卫者/远古守卫者：激光蓄力攻击、水中游泳导航（PathNavigateSwimmer）、荆棘反伤、Elder 挖掘疲劳光环 |
| EntityIronGolem | 357 | extends EntityGolem | 铁傀儡：村庄绑定、防御村庄 AI、攻击击飞（entityState byte 4）、送花（byte 11） |
| EntityMagmaCube | 174 | extends EntitySlime | 岩浆怪：免火、按体型给护甲、跳跃参数覆盖、FLAME 粒子 |
| EntityMob | 192 | extends EntityCreature implements IMob | 敌对生物抽象基类：阳光下加速 despawn 计数、和平模式自删、近战攻击公式、亮度刷怪校验 |
| EntityPigZombie | 317 | extends EntityZombie | 僵尸猪人：angerLevel 激怒机制（UUID 记仇、群体仇恨 AIHurtByAggressor） |
| EntitySilverfish | 306 | extends EntityMob | 蠹虫：受击召唤墙内同伴（AISummonSilverfish）、钻入石头变 monster_egg（AIHideInStone） |
| EntitySkeleton | 442 | extends EntityMob implements IRangedAttackMob | 骷髅/凋灵骷髅：SkeletonType（DataWatcher 13）切换体型与免火、按手持物切换弓/近战 AI |
| EntitySlime | 586 | extends EntityLiving implements IMob | 史莱姆：尺寸驱动一切（血量/速度/经验/掉落）、死亡分裂、squish 动画因子、SlimeMoveHelper 跳跃移动 |
| EntitySnowman | 126 | extends EntityGolem implements IRangedAttackMob | 雪傀儡：走过铺雪、扔雪球、高温/淋雨扣血 |
| EntitySpider | 311 | extends EntityMob | 蜘蛛：爬墙（isCollidedHorizontally 同步到 DataWatcher 16）、亮度≥0.5 放弃攻击、骷髅骑士彩蛋 |
| EntityWitch | 287 | extends EntityMob implements IRangedAttackMob | 女巫：喝药状态机（DataWatcher 21 + witchAttackTimer）、投掷伤害药水、魔法伤害减免 85% |
| EntityZombie | 821 | extends EntityMob | 僵尸：阳光燃烧、援军生成（reinforcementChance 属性）、村民感染/治疗转化、幼年/村民变体 |
| IMob | 23 | interface, extends IAnimals | 敌对标记接口；提供 `mobSelector` / `VISIBLE_MOB_SELECTOR` 两个 Predicate 常量 |

## 核心类详解

### EntityMob（抽象基类）

全包除 EntityGhast、EntitySlime、EntityGolem 系以外的共同父类。

关键方法（均逐字来自源码）：
- `public void onLivingUpdate()` — EntityMob.java:27。每 tick 调 `updateArmSwingProgress()`；亮度 > 0.5 时 `this.entityAge += 2`（加速 despawn 判定）。
- `public void onUpdate()` — EntityMob.java:43。服务端且 `EnumDifficulty.PEACEFUL` 时 `setDead()`（和平模式清怪的实现点）。
- `public boolean attackEntityAsMob(Entity entityIn)` — EntityMob.java:104。近战伤害公式：`attackDamage` 属性 + `EnchantmentHelper.getModifierForCreature(this.getHeldItem(), ...)`，附带击退与火焰附加。
- `protected boolean isValidLightLevel()` — EntityMob.java:147。天空光 > `rand.nextInt(32)` 拒绝；雷暴时临时 `setSkylightSubtracted(10)` 重算方块光。
- `public boolean getCanSpawnHere()` — EntityMob.java:174。`难度 != PEACEFUL && isValidLightLevel() && super.getCanSpawnHere()`。

注意 EntityMob.java:75 的 `return this.riddenByEntity != entity && this.ridingEntity != entity ? true : true;` —— 恒为 true，是原版反编译遗留的无效分支，不是本仓库 bug。

### EntityZombie

包内最大类（821 行），三个正交变体维度：child（DataWatcher 12）、villager（DataWatcher 13）、converting（DataWatcher 14）。

- `protected static final IAttribute reinforcementChance` — EntityZombie.java:51，`new RangedAttribute((IAttribute)null, "zombie.spawnReinforcements", 0.0D, 0.0D, 1.0D)`。EntityPigZombie 继承后置 0（EntityPigZombie.java:59）。
- `public boolean attackEntityFrom(DamageSource source, float amount)` — EntityZombie.java:258。HARD 难度受击时按该属性概率在 7–40 格内尝试 50 次生成援军僵尸，并给呼叫者/被叫者各 -0.05 修饰符。
- `public void onKillEntity(EntityLivingBase entityLivingIn)` — EntityZombie.java:476。NORMAL/HARD 下杀死 EntityVillager 时移除村民、原地生成 `setVillager(true)` 的僵尸，播放 auxSFX 1016。
- `protected void startConversion(int ticks)` — EntityZombie.java:649。喂金苹果触发（`interact` EntityZombie.java:616 要求 `isVillager() && isPotionActive(Potion.weakness)`），倒计时在 `onUpdate()`（EntityZombie.java:310）里由 `getConversionTimeBoost()`（EntityZombie.java:721，附近铁栏杆/床加速）扣减，归零调 `convertToVillager()`（EntityZombie.java:692）。
- `protected final void setSize(float width, float height)` — EntityZombie.java:766，final：把尺寸缓存进 `zombieWidth/zombieHeight`，幼年时 `multiplySize(0.5F)`。子类不能再覆盖 setSize。

### EntitySlime

不继承 EntityMob 而直接 `extends EntityLiving implements IMob`，因此没有 EntityMob 的和平模式清除逻辑，改在自己的 `onUpdate()`（EntitySlime.java:115）里做 `this.isDead = true`。

- `protected void setSlimeSize(int size)` — EntitySlime.java:53。单一入口同时设定 DataWatcher 16、碰撞箱 `0.51000005F * size`、maxHealth = `size * size`、movementSpeed = `0.2F + 0.1F * size`、experienceValue = size。
- `public void setDead()` — EntitySlime.java:198。服务端、size > 1 且血量 ≤ 0 时分裂出 `2 + rand.nextInt(3)` 个半尺寸个体（经 `createInstance()`，EntityMagmaCube 覆盖之），继承自定义名与 persistence。
- `public void onDataWatcherUpdate(int dataID)` — EntitySlime.java:177。客户端收到 size 变更时重设碰撞箱——渲染尺寸与 DataWatcher 同步的挂点。
- 内部类 `SlimeMoveHelper`（EntitySlime.java:514）完全替换默认移动：不用路径 forward，而是转向 + `getJumpHelper().setJumping()` 周期跳跃；`AISlimeAttack/AISlimeFaceRandom/AISlimeFloat/AISlimeHop` 四个 AI 都只跟它交互。
- squish 动画三元组 `squishAmount / squishFactor / prevSquishFactor`（EntitySlime.java:30-32，public）在 `onUpdate()` 里插值，渲染层按 partialTicks 读取。

### EntityEnderman

- `protected boolean teleportTo(double x, double y, double z)` — EntityEnderman.java:221。直接改 posY 向下探测可站立方块，成功后 `setPositionAndUpdate` 并校验无碰撞、无液体；失败回滚原坐标。粒子沿旧→新位置线性撒 128 个 PORTAL。
- `private boolean shouldAttackPlayer(EntityPlayer player)` — EntityEnderman.java:123。头戴 pumpkin 直接豁免；否则用视线向量点积判定"正在注视"：`d1 > 1.0D - 0.025D / d0 ? player.canEntityBeSeen(this) : false`。
- `public boolean attackEntityFrom(DamageSource source, float amount)` — EntityEnderman.java:360。间接伤害（`EntityDamageSourceIndirect`，即箭/雪球）不掉血只触发最多 64 次随机传送；EntityEndermite 来源的伤害不触发尖叫。
- `static final Set<Block> carriableBlocks` — EntityEnderman.java:46，static 块（EntityEnderman.java:424-440）注册 14 种可搬运方块（grass/dirt/sand/gravel/两种花/两种蘑菇/tnt/cactus/clay/pumpkin/melon_block/mycelium）。
- 内部类 `AIFindPlayer`（EntityEnderman.java:442）实现"对视 5 tick 才锁定 + 靠近 16 格内先传送走 + 256 格外追传"状态机，并挂/摘 `attackingSpeedBoostModifier`（+0.15 速度）。

### EntityGuardian

包内唯一大量使用"客户端从 DataWatcher 反查实体"模式的类：

- `public EntityLivingBase getTargetedEntity()` — EntityGuardian.java:192。服务端直接返回 `getAttackTarget()`；客户端用 `worldObj.getEntityByID(this.dataWatcher.getWatchableObjectInt(17))` 解析并缓存到 `targetedEntity` 字段。激光束渲染的数据来源。
- `public void onDataWatcherUpdate(int dataID)` — EntityGuardian.java:225。dataID 16（flags：2=moving、4=elder）触发客户端 setSize；dataID 17 重置激光蓄力计数 `field_175479_bo = 0`。
- `public float func_175477_p(float p_175477_1_)` — EntityGuardian.java:421。返回 0..1 的蓄力进度 `((float)this.field_175479_bo + p_175477_1_) / (float)this.func_175464_ck()`，渲染层据此调激光颜色；蓄力时长 `func_175464_ck()` elder 60 / 普通 80（EntityGuardian.java:148）。
- `protected void updateAITasks()` — EntityGuardian.java:426。Elder 每 1200 tick 给 50 格内生存/冒险玩家发 `S2BPacketChangeGameState(10, 0.0F)`（幽灵图像）并施加 6000 tick 挖掘疲劳 III —— 包内唯一直接构造并发送网络封包的位置。
- `public void moveEntityWithHeading(float strafe, float forward)` — EntityGuardian.java:560。水中改用 `moveFlying(strafe, forward, 0.1F)` + 0.9 摩擦的自定义泳动。

### EntityCreeper

- 引信状态机全在 `public void onUpdate()` — EntityCreeper.java:133：`timeSinceIgnited += getCreeperState()`（state -1 倒退 / 1 前进），到 `fuseTime`（默认 30）调 `explode()`（EntityCreeper.java:282，服务端 `worldObj.createExplosion(this, ..., explosionRadius * (getPowered() ? 2.0F : 1.0F), mobGriefing)` 后 `setDead()`）。
- `public float getCreeperFlashIntensity(float p_70831_1_)` — EntityCreeper.java:221。渲染层（RenderCreeper / LayerCreeperCharge）每帧用 partialTicks 调它算白闪插值。
- `protected boolean interact(EntityPlayer player)` — EntityCreeper.java:259。打火石强制点燃（写 DataWatcher 18）。
- `public void onStruckByLightning(...)` — EntityCreeper.java:250。写 DataWatcher 17 = 1（充能）。充能爆头掉落逻辑在各受害者的 `onDeath` 里（EntityZombie.java:797、EntitySkeleton.java:197、EntityCreeper.java:187）。

## 时序与生命周期

**构造**：`EntityXxx(World)` → 父类链中先后调 `entityInit()`（注册 DataWatcher 槽位）与 `applyEntityAttributes()`（属性基值）——两者都在构造函数返回前由 Entity/EntityLivingBase 构造器回调，随后子类构造体填充 `tasks` / `targetTasks`。EntitySkeleton 构造器尾部有服务端专属的 `setCombatTask()`（EntitySkeleton.java:61-64，`worldIn != null && !worldIn.isRemote` 判空是因为渲染注册期会传 null world 构造实体）。

**首次生成**（仅服务端，不含存档恢复）：`onInitialSpawn(DifficultyInstance, IEntityLivingData)` —— 骷髅在此决定凋灵骷髅分支并二选一挂 `aiArrowAttack/aiAttackOnCollide`（EntitySkeleton.java:284）；僵尸决定 child/villager/鸡骑士/万圣节南瓜（EntityZombie.java:533）；蜘蛛 1% 骷髅骑士 + HARD 药水 buff（EntitySpider.java:202）；史莱姆掷尺寸 1/2/4（EntitySlime.java:387）。

**每 tick**（服务端逻辑线程 = integrated server 线程；客户端本地世界在客户端主线程重复运行 isRemote 分支）：`World#updateEntities` → `onUpdate()` → `onLivingUpdate()` →（EntityLiving 内部）AI 任务的 `shouldExecute/updateTask` 与 `updateAITasks()`。本包覆盖点：
- `onUpdate`：Creeper 引信、Slime squish/分裂检测、Spider 爬墙位同步、Zombie 转化倒计时、Ghast/EntityMob 和平模式自删。
- `onLivingUpdate`：Blaze/Enderman/Endermite/Guardian 客户端粒子、Zombie/Skeleton 阳光点燃（服务端）、Witch 喝药状态机（服务端）、Snowman 铺雪（服务端）、IronGolem attackTimer/holdRoseTick 递减。
- `updateAITasks`（仅服务端、有 AI 时）：Blaze 浮空与 heightOffset、Enderman 白天传送逃离、PigZombie 怒气衰减、IronGolem 村庄重绑定（每 70+rand(50) tick）、Guardian Elder 光环。

**每帧**：本包不含渲染代码；渲染层每帧只读状态（squishFactor、getCreeperFlashIntensity、func_175477_p、isScreaming、getAttackTimer 等）。`getBrightnessForRender`（EntityBlaze.java:82、EntityMagmaCube.java:48 返回常量 15728880 = 满亮度）在渲染取光照时被调用。

**线程归属**：全部逻辑在游戏 tick 线程（服务端/客户端主线程）。DataWatcher 写入由 Netty 层在 tick 末打包成 S1CPacketEntityMetadata 发出；本包代码本身从不在 Netty EventLoop 上执行。唯一直接触网络的是 EntityGuardian.java:451 的 `sendPacket(new S2BPacketChangeGameState(10, 0.0F))`（服务端 tick 线程调用，Netty 负责实际写出）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void onUpdate()` | EntityMob.java:43（及 Creeper:133 / Slime:115 / Zombie:310 / Spider:73） | 每 tick，双端 | 观察/改写单类怪物全部逐帧逻辑；ESP、行为分析的数据源 | 双端各跑一次，改动需区分 `worldObj.isRemote` |
| `public void onLivingUpdate()` | EntityMob.java:27（各子类覆盖） | 每 tick，onUpdate 内 | 拦截阳光燃烧（Zombie:212 / Skeleton:137）、关闭客户端粒子（Enderman:151 / Blaze:99） | super 链很深，跳过 super 会丢 AI/护甲计算 |
| `public boolean attackEntityFrom(DamageSource source, float amount)` | EntityEnderman.java:360 / EntityZombie.java:258 / EntityGuardian.java:531 / EntityPigZombie.java:166 | 任意伤害进入时（服务端为主） | 观察/取消末影人受击传送、僵尸援军、守卫者荆棘、猪人激怒 | 客户端调用只做表现；真实判定在服务端，纯客户端 mod 只能观察 |
| `public boolean attackEntityAsMob(Entity entityIn)` | EntityMob.java:104（Creeper:205 恒 true / IronGolem:174 击飞） | AI 近战命中时 | 修改怪物近战伤害/附加效果 | Creeper 版是空实现（爆炸才是伤害来源），别在这挂爆炸逻辑 |
| `public float getCreeperFlashIntensity(float p_70831_1_)` | EntityCreeper.java:221 | 渲染层每帧 | 苦力怕预警 UI（读引信进度）；改写可禁用白闪 | 纯客户端安全；数值可超 1（爆炸前一刻） |
| `public int getCreeperState()` / `public boolean hasIgnited()` | EntityCreeper.java:234 / 293 | 任意 | 读 DataWatcher 判断"正在引爆"，做逃跑提示 | state 由服务端 AI 写入，客户端只读 |
| `public EntityLivingBase getTargetedEntity()` | EntityGuardian.java:192 | 渲染激光束时（客户端） | 检测守卫者是否正瞄准本地玩家（激光预警） | 客户端有实体 ID 解析缓存，`hasTargetedEntity()` 先行判断 |
| `public float func_175477_p(float p_175477_1_)` | EntityGuardian.java:421 | 渲染每帧 | 读激光蓄力进度 0..1，蓄满即受击 | 客户端计数靠 dataID 17 更新重置，别手动清 `field_175479_bo` |
| `public boolean isScreaming()` | EntityEnderman.java:414 | 渲染（换贴图/张嘴）及逻辑 | 检测末影人激怒状态 | DataWatcher 18，双端一致 |
| `protected boolean teleportTo(double x, double y, double z)` | EntityEnderman.java:221 | 受击/白天/追击传送 | 观察或禁止末影人传送（反瞬移逃逸） | 直接改 posX/posY/posZ 再回滚的写法，Hook 时注意失败路径恢复坐标 |
| `public void handleStatusUpdate(byte id)` | EntityIronGolem.java:190 / EntityWitch.java:187 / EntityZombie.java:658 | 客户端收到 S19PacketEntityStatus 时 | 捕获服务端广播的瞬时事件：4=铁傀儡挥臂、11=送花、15=女巫粒子、16=僵尸治疗音 | 只在客户端触发；id 与 `worldObj.setEntityState` 调用一一对应 |
| `public void onDataWatcherUpdate(int dataID)` | EntitySlime.java:177 / EntityGuardian.java:225 | 客户端 metadata 包应用后 | 监听尺寸/目标/elder 变化的即时回调 | 服务端本地也会触发；判 isRemote |
| `public void setCombatTask()` | EntitySkeleton.java:321 | 读 NBT 后、`setCurrentItemOrArmor(0, ...)` 后（服务端） | 改写骷髅武器→AI 映射 | `setCurrentItemOrArmor`（EntitySkeleton.java:420）服务端 slot 0 自动重调它 |
| `protected boolean interact(EntityPlayer player)` | EntityCreeper.java:259 / EntityZombie.java:616 | 玩家右键实体 | 拦截打火石点燃/金苹果治疗交互 | 客户端先行调用做手感，真正扣耐久/开始转化在 `!isRemote` 分支 |
| `public boolean getCanSpawnHere()` / `protected boolean isValidLightLevel()` | EntityMob.java:174 / 147 | 自然生成尝试（服务端） | 改刷怪规则 | Blaze/Endermite/Silverfish/Guardian 覆盖 isValidLightLevel 恒 true |

## 数据与协议

本包不直接定义封包（唯一发送点：EntityGuardian.java:451 `S2BPacketChangeGameState(10, 0.0F)`）。状态同步靠 DataWatcher（自动进 S0FPacketSpawnMob / S1CPacketEntityMetadata）与 NBT 存档。

### DataWatcher 槽位（本包新增部分）

| 类 | 槽位 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|---|
| EntityBlaze | 16 | Byte | `func_70845_n()` / `setOnFire(boolean)` | bit0 = 燃烧攻击姿态 |
| EntityCreeper | 16 | Byte | `getCreeperState()` / `setCreeperState(int)` | -1 idle / 1 引信推进 |
| EntityCreeper | 17 | Byte | `getPowered()` / 雷击写 1 | 闪电充能 |
| EntityCreeper | 18 | Byte | `hasIgnited()` / `ignite()` | 打火石强制点燃 |
| EntityEnderman | 16 | Short | `getHeldBlockState()` / `setHeldBlockState(IBlockState)` | 手持方块 stateId 低 16 位 |
| EntityEnderman | 17 | Byte | 无读取方（未使用） | 保留槽 |
| EntityEnderman | 18 | Byte | `isScreaming()` / `setScreaming(boolean)` | 激怒尖叫 |
| EntityGhast | 16 | Byte | `isAttacking()` / `setAttacking(boolean)` | 开火姿态（贴图切换） |
| EntityGuardian | 16 | Int | `isSyncedFlagSet` / `setSyncedFlag` | 位标志：2=moving、4=elder |
| EntityGuardian | 17 | Int | `getTargetedEntity()` / `setTargetedEntity(int)` | 激光目标 entityId，0=无 |
| EntityIronGolem | 16 | Byte | `isPlayerCreated()` / `setPlayerCreated(boolean)` | bit0 = 玩家搭建 |
| EntitySkeleton | 13 | Byte | `getSkeletonType()` / `setSkeletonType(int)` | 0 普通 / 1 凋灵骷髅 |
| EntitySlime | 16 | Byte | `getSlimeSize()` / `setSlimeSize(int)` | 尺寸 1/2/4 |
| EntitySpider | 16 | Byte | `isBesideClimbableBlock()` / `setBesideClimbableBlock(boolean)` | bit0 = 贴墙（客户端爬墙动画） |
| EntityWitch | 21 | Byte | `getAggressive()` / `setAggressive(boolean)` | 1 = 正在喝药 |
| EntityZombie | 12 | Byte | `isChild()` / `setChild(boolean)` | 幼年 |
| EntityZombie | 13 | Byte | `isVillager()` / `setVillager(boolean)` | 村民变体 |
| EntityZombie | 14 | Byte | `isConverting()` / `startConversion(int)` 写 1 | 治疗转化中 |

### NBT 字段

| 类 | 键 | 类型 | 写入方法 | 含义 |
|---|---|---|---|---|
| EntityCreeper | `powered` / `Fuse` / `ExplosionRadius` / `ignited` | Boolean/Short/Byte/Boolean | `writeEntityToNBT` EntityCreeper.java:92 | 充能 / 引信长度（默认 30）/ 爆炸半径（默认 3）/ 已点燃 |
| EntityEnderman | `carried` / `carriedData` | Short/Short | EntityEnderman.java:92 | 手持方块 id+meta；读取端兼容字符串 id（`hasKey("carried", 8)`，EntityEnderman.java:108） |
| EntityEndermite | `Lifetime` / `PlayerSpawned` | Int/Boolean | EntityEndermite.java:108 | 存活 tick（≥2400 自毁）/ 玩家珍珠生成标记 |
| EntityGhast | `ExplosionPower` | Int | EntityGhast.java:184 | 火球爆炸强度（默认 1） |
| EntityGuardian | `Elder` | Boolean | EntityGuardian.java:92 | 远古守卫者 |
| EntityIronGolem | `PlayerCreated` | Boolean | EntityIronGolem.java:159 | 玩家搭建（不攻击玩家、死不扣村庄声望） |
| EntityPigZombie | `Anger` / `HurtBy` | Short/String(UUID) | EntityPigZombie.java:125 | 怒气值 / 记仇玩家 UUID（空串=无） |
| EntitySkeleton | `SkeletonType` | Byte | EntitySkeleton.java:411 | 0/1；读取后必调 `setCombatTask()`（EntitySkeleton.java:405） |
| EntitySlime | `Size` / `wasOnGround` | Int/Boolean | EntitySlime.java:75 | 存 `getSlimeSize()-1`；读回 +1 |
| EntityZombie | `IsBaby` / `IsVillager` / `ConversionTime` / `CanBreakDoors` | Boolean×2/Int/Boolean | EntityZombie.java:430 | 变体标记；ConversionTime=-1 表示未转化 |

## 不变量与陷阱

- **DataWatcher 槽位是继承共享的命名空间**：EntityLiving 层已占 0–11、15；本包用 12–14、16–18、21。给这些类加新槽位时必须避开父类与兄弟类已占用的 id，否则 `addObject` 抛异常。
- **`entityInit()` 与 `applyEntityAttributes()` 在子类构造体之前执行**（由父类构造器回调）。在这两个方法里访问子类字段会读到默认值——EntitySkeleton 的 `aiArrowAttack` 字段初始化器能用，正是因为它只在构造体尾部被引用。
- **EntityZombie.setSize 是 final**（EntityZombie.java:766）且语义变了：它写缓存而非直接设尺寸，实际生效经 `multiplySize`。EntityPigZombie/EntityGiantZombie 相关的尺寸调整都必须走这条路。
- **isRemote 分支决定权威性**：爆炸（Creeper:284）、转化（Zombie:312）、喝药（Witch:116）、铺雪（Snowman:53）都包在 `!worldObj.isRemote`；粒子/音效包在 `isRemote`。纯客户端注入改不了服务端结果，只能改表现或发交互包。
- **和平模式清怪有三处实现**：EntityMob.onUpdate（:47）、EntityGhast.onUpdate（:64）、EntitySlime.onUpdate（:117，额外判 `getSlimeSize() > 0`）。EntitySlime 用 `this.isDead = true` 而非 `setDead()`——正是为了绕过 setDead 的分裂逻辑。
- **EntityGuardian.AIGuardianAttack.updateTask 里有空分支**（EntityGuardian.java:662-665：`else if (this.tickCounter >= 60 && this.tickCounter % 20 == 0) { ; }`），原版遗留，勿"顺手清理"，行号被多处文档引用。
- **EntityWitch.onLivingUpdate 有重复的速度药水分支**（EntityWitch.java:158 与 162 完全相同的条件），同为原版遗留。
- **药水数值魔数**：Witch 使用原始 potion damage 值（8237 水肺、16307 抗火、16341 治疗、16274 迅捷、32732/32698/32660/32696 投掷系），这些是 1.8 药水元数据编码，改动前先理解位编码。
- **线程安全**：所有字段都无同步措施，仅允许 tick 线程访问。从渲染线程读 `squishFactor` 等 public 字段之所以可行，是因为 1.8.9 客户端渲染与 tick 同线程（主线程）；本仓库 LWJGL3 移植保持了这一模型，不要引入异步读取。
- **LWJGL3/JDK25 移植面**：本包零平台 API 依赖（无 GL/输入/NIO），与原版 MCP 1.8.9 逐字节一致的纯逻辑代码；`Calendar` 万圣节判定（EntitySkeleton.java:306、EntityZombie.java:586）在新 JDK 行为不变。移植风险集中在其调用的渲染/世界层，不在此包。
- **`func_*` 未映射名**：`func_70845_n`（Blaze 燃烧态）、`func_175493_co`（Creeper 头颅掉落计数）、`func_175451_e`（Slime 碰撞伤害）、`func_175472_n`（Guardian moving 位）、`func_179462_f`（Silverfish 召唤触发）等在包外也被引用，重命名需全局搜索。

## 交叉引用

- entity.ai → 构造器中挂载的全部 `EntityAI*` 任务类；`EntityAICreeperSwell`（ai 包内，反向依赖 EntityCreeper#setCreeperState）
- entity.ai.attributes → `EntityEnderman#attackingSpeedBoostModifier`、`EntityPigZombie#ATTACK_SPEED_BOOST_MODIFIER`、`EntityWitch#MODIFIER`、`EntityZombie#reinforcementChance`（AttributeModifier/RangedAttribute）
- entity.projectile → `EntityBlaze.AIFireballAttack#updateTask`（EntitySmallFireball）、`EntityGhast.AIFireballAttack#updateTask`（EntityLargeFireball）、`EntitySkeleton#attackEntityWithRangedAttack`（EntityArrow）、`EntitySnowman#attackEntityWithRangedAttack`（EntitySnowball）、`EntityWitch#attackEntityWithRangedAttack`（EntityPotion）、`EntityGuardian#addRandomDrop`（EntityFishHook.func_174855_j）
- pathfinding → `EntityGuardian#getNewNavigator`（PathNavigateSwimmer）、`EntitySpider#getNewNavigator`（PathNavigateClimber）、`EntityZombie` 构造器（PathNavigateGround#setBreakDoors）、`EntityIronGolem`/`EntitySnowman` 构造器（PathNavigateGround#setAvoidsWater）
- entity.passive → `EntityZombie#onKillEntity` / `convertToVillager`（EntityVillager）、`EntityZombie#onInitialSpawn`（EntityChicken#setChickenJockey）、`EntityGuardian.GuardianTargetSelector#apply`（EntitySquid）、`EntityCreeper` 构造器（EntityOcelot 回避）
- entity.player → `EntityEnderman#attackEntityFrom`（EntityPlayerMP.theItemInWorldManager#isCreative）、`EntityGuardian#updateAITasks`（EntityPlayerMP.playerNetServerHandler#sendPacket）
- network.play.server → `EntityGuardian#updateAITasks`（S2BPacketChangeGameState）
- village → `EntityIronGolem#updateAITasks`（VillageCollection#getNearestVillage、Village#setReputationForPlayer）
- block → `EntitySilverfish.AIHideInStone#startExecuting`（BlockSilverfish#canContainSilverfish、Blocks.monster_egg）、`EntityEnderman#carriableBlocks`（Blocks.*）、`EntitySnowman#onLivingUpdate`（Blocks.snow_layer#canPlaceBlockAt）
- world → `EntityCreeper#explode`（World#createExplosion）、`EntitySlime#getCanSpawnHere`（Chunk#getRandomWithSeed、BiomeGenBase.swampland）、`EntitySkeleton#onInitialSpawn`（WorldProviderHell）
- stats → `EntityGhast#attackEntityFrom`（AchievementList.ghast）、`EntitySkeleton#onDeath`（AchievementList.snipeSkeleton）
- enchantment → `EntityMob#attackEntityAsMob`、`EntitySkeleton#attackEntityWithRangedAttack`（EnchantmentHelper、Enchantment.power/punch/flame）
- client.renderer.entity（反向）→ RenderCreeper/RenderEnderman/RenderSlime/RenderGuardian 等读取本包状态 getter（本包不 import 渲染类）

## 覆盖声明

完整读取了 21/21 个文件（每个文件从第 1 行读到末行，未截断）。

逐行精读：EntityMob、EntityZombie、EntitySlime、EntityEnderman、EntityGuardian、EntityCreeper、EntitySkeleton、EntityGhast、EntityIronGolem、EntityWitch、EntityPigZombie、EntitySilverfish、EntitySpider。

完整读取但按结构性理解归档（逻辑简单，无需逐行推演）：EntityBlaze、EntityCaveSpider、EntityEndermite、EntityGiantZombie、EntityGolem、EntityMagmaCube、EntitySnowman、IMob。

行号引用均来自本次 Read 输出，与仓库当前 HEAD 一致。
