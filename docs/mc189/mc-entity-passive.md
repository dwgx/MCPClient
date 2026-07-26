---
area: net/minecraft/entity/passive
slug: mc-entity-passive
files: 17
lines: 7055
tier: C
---

# net/minecraft/entity/passive

## 定位

被动生物（动物、村民、蝙蝠、鱿鱼）的实体逻辑层。本包定义繁殖（in-love）、驯服（tame/sit/owner）、玩家交互（`interact`）、掉落物、NBT 持久化，以及每类生物的 AI 任务组装（构造函数里 `tasks.addTask(...)`）。

- 上游调用者：`EntityLiving`/`EntityLivingBase` 的 tick 循环调用 `onUpdate`/`onLivingUpdate`/`updateAITasks`；`NetHandlerPlayClient.handleEntityStatus`（NetHandlerPlayClient.java:1037）在收到 S19PacketEntityStatus 后调用 `handleStatusUpdate(byte)`；`EntityPlayer.interactWith` 经服务端分发到各类的 `interact(EntityPlayer)`；渲染层（`RenderHorse`、`RenderWolf`、`RenderSquid`、`LayerSheepWool`、`ModelHorse` 等）每帧读取本包暴露的动画状态字段/方法。
- 下游依赖：`net.minecraft.entity.ai.*`（全部 AI 任务）、`DataWatcher`（状态同步）、`nbt`、`init.Items/Blocks`、`village.*`（村民）、`inventory.AnimalChest/InventoryBasic`（马、村民）。
- 若本包消失：所有动物/村民实体无法实例化，客户端收到这些实体的 spawn 包会崩；`RenderHorse` 等渲染器、`EntityAIMate` 等 AI、村民交易 GUI（`GuiMerchant` 的数据源 `IMerchant` 实现）全部失效。

注意：这是客户端源码树，但保留了完整的服务端逻辑（integrated-server 结构），大量方法用 `worldObj.isRemote` 区分两侧。

## 类清单

| 类名 | 行数 | extends / implements | 职责 |
|---|---|---|---|
| IAnimals | 5 | interface（空标记接口） | 标记"动物类"实体，无方法 |
| EntityAmbientCreature | 26 | extends EntityLiving implements IAnimals | 环境生物基类（蝙蝠），禁止拴绳与交互 |
| EntityWaterMob | 89 | extends EntityLiving implements IAnimals | 水生生物基类：水下呼吸、离水窒息、可 despawn |
| EntityAnimal | 241 | extends EntityAgeable implements IAnimals | 陆地动物基类：in-love 繁殖状态机、喂食交互、草地生成条件 |
| EntityTameable | 248 | extends EntityAnimal implements IEntityOwnable | 可驯服基类：owner UUID、sitting/tamed 位、驯服粒子 |
| EntityBat | 289 | extends EntityAmbientCreature | 蝙蝠：倒挂/飞行两态、随机游走、万圣节生成加成 |
| EntityChicken | 245 | extends EntityAnimal | 鸡：翅膀动画字段、下蛋计时、chicken jockey |
| EntityCow | 154 | extends EntityAnimal | 牛：挤奶交互、皮革/牛肉掉落 |
| EntityMooshroom | 83 | extends EntityCow | 蘑菇牛：碗取蘑菇煲、剪毛变普通牛 |
| EntityPig | 250 | extends EntityAnimal | 猪：鞍、胡萝卜钓竿骑乘操控、雷击变僵尸猪人 |
| EntitySheep | 389 | extends EntityAnimal | 羊：羊毛颜色（DataWatcher 位段）、剪毛、吃草回毛、染料混色繁殖 |
| EntityRabbit | 747 | extends EntityAnimal | 兔子：自定义 Jump/MoveHelper、EnumMoveType、杀手兔（type 99）、偷胡萝卜 AI |
| EntityOcelot | 391 | extends EntityTameable | 豹猫：鱼驯服成猫、tame skin、躲避玩家 AI |
| EntityWolf | 639 | extends EntityTameable | 狼：愤怒/乞食/甩水动画、骨头驯服、项圈染色、攻击目标选择 |
| EntityHorse | 1884 | extends EntityAnimal implements IInvBasic | 马/驴/骡/僵尸马/骷髅马：type+variant、AnimalChest、骑乘移动与跳跃、纹理合成 |
| EntityVillager | 1084 | extends EntityAgeable implements IMerchant, INpc | 村民：职业/职级交易表、村庄声望、交易 GUI 数据源、雷击变女巫 |
| EntitySquid | 291 | extends EntityWaterMob | 鱿鱼：触手/旋转动画、随机移动 AI、墨囊掉落 |

## 核心类详解

### EntityAnimal（EntityAnimal.java）

繁殖状态机的根。关键字段：`protected Block spawnableBlock = Blocks.grass`（:18）、`private int inLove`（:19）、`private EntityPlayer playerInLove`（:20）。

- `public void setInLove(EntityPlayer player)`（EntityAnimal.java:191）：`inLove = 600`，并 `worldObj.setEntityState(this, (byte)18)` 广播心形粒子。被 `interact`（:151，喂食路径）和 `EntityHorse.interact`（金苹果/金萝卜）调用。
- `public boolean interact(EntityPlayer player)`（EntityAnimal.java:151）：喂繁殖物触发 love；喂幼体加速成长（`func_175501_a`）。所有子类 `interact` 最终 `super` 到这里。
- `public boolean canMateWith(EntityAnimal otherAnimal)`（EntityAnimal.java:219）：同类且双方 `isInLove()`。被 `entity.ai.EntityAIMate` 每 tick 查询。
- `public void handleStatusUpdate(byte id)`（EntityAnimal.java:224）：id==18 播放 7 个 HEART 粒子（客户端）。
- `onLivingUpdate()`（:41）每 tick 递减 `inLove`，每 10 tick 出一个心形粒子。
- NBT：`writeEntityToNBT` 写 `"InLove"` int（:88-92）。

### EntityTameable（EntityTameable.java）

驯服语义的载体。字段：`protected EntityAISit aiSit = new EntityAISit(this)`（:17）。DataWatcher 槽 16（byte 位段：bit0=sitting、bit2=tamed）与槽 17（owner UUID 字符串），见 `entityInit()`（:25-30）。

- `public boolean isTamed()`（:116）/ `public void setTamed(boolean tamed)`（:121）：改位后调用 `setupTamedAI()`（:137，空钩子，`EntityOcelot` 覆写以增删 avoid-player AI）。
- `public EntityLivingBase getOwner()`（:170）：`UUID.fromString(getOwnerId())` 后 `worldObj.getPlayerEntityByUUID(uuid)`；非法字符串捕获 `IllegalArgumentException` 返回 null。
- `protected void playTameEffect(boolean play)`（:82）：HEART 或 SMOKE_NORMAL 粒子；`handleStatusUpdate`（:100）把 id 7/6 映射到成功/失败。
- `getTeam()`/`isOnSameTeam`（:201/:216）代理到 owner，使宠物继承主人队伍——PvP 判定路径依赖这一点。

### EntityHorse（EntityHorse.java）

包内最大类。DataWatcher 布局（`entityInit`，:106-114）：槽 16=int 位段（2=tamed、4=saddled、8=chested、16=breeding、32=eating、64=rearing、128=mouth open，见 `getHorseWatchableBoolean`:175）、槽 19=byte type（0 马/1 驴/2 骡/3 僵尸马/4 骷髅马，`getHorseType`:125）、槽 20=int variant（低 8 位毛色，8-15 位斑纹）、槽 21=String ownerUUID、槽 22=int 护甲索引。

关键字段：`private static final IAttribute horseJumpStrength`（:52，`RangedAttribute("horse.jumpStrength", 0.7D, 0.0D, 2.0D)`，`setShouldWatch(true)` 会随属性包同步）、`private AnimalChest horseChest`（:66）、`protected float jumpPower`（:73）、动画插值 `headLean/rearingAmount/mouthOpenness` 及其 prev（:75-80）。

- `public void moveEntityWithHeading(float strafe, float forward)`（EntityHorse.java:1306）：被骑且有鞍时，把 rider 的 `moveStrafing/moveForward` 转为马的移动，`jumpPower > 0` 时执行跳跃（`motionY = getHorseJumpStrength() * jumpPower`，:1331）。每 tick 由 `EntityLivingBase.onLivingUpdate` 调用。
- `public void setJumpPower(int jumpPowerIn)`（EntityHorse.java:1698）：由 `NetHandlerPlayServer`（NetHandlerPlayServer.java:877，C0CPacketInput 的 auxData）驱动，即客户端蓄力跳的服务端入口。
- `public boolean interact(EntityPlayer player)`（EntityHorse.java:796）：分支极多——喂食恢复/成长/temper、放箱子、开 GUI（`openGUI`:784 → `playerEntity.displayGUIHorse(this, this.horseChest)`）、`mountTo(player)`（:986）。
- `public String getHorseTexture()`（:764）/`getVariantTexturePaths()`（:774）：懒生成 `texturePrefix` 与 3 层纹理数组，`RenderHorse.java:82/95` 用其构建 `LayeredTexture`。`onUpdate`（:1114）在客户端检测 `dataWatcher.hasObjectChanged()` 后 `resetTexturePrefix()`（:1118-1122），保证 variant/armor 变化后纹理重建。
- `public void onInventoryChanged(InventoryBasic p_76316_1_)`（:499）：IInvBasic 回调，鞍/甲槽变化时同步 DataWatcher 并播声音。
- NBT（:1392-1516）：`EatingHaystack/ChestedHorse/HasReproduced/Bred/Type/Variant/Temper/Tame/OwnerUUID/Items/ArmorItem/SaddleItem`；读取时兼容旧 `"Owner"` 名（经 `PreYggdrasilConverter.getStringUUIDFromName`）与旧 `"Saddle"` 布尔。

### EntityVillager（EntityVillager.java）

`IMerchant` 的唯一常规实现，交易 GUI 的数据源。关键字段：`Village villageObj`（:69）、`private EntityPlayer buyingPlayer`（:72）、`private MerchantRecipeList buyingList`（:75）、`private int careerId`（:85）/`careerLevel`（:88）、`private InventoryBasic villagerInventory`（:91，8 格）、静态四维交易表 `DEFAULT_TRADE_LIST_MAP`（:96，[profession][career][careerLevel][entries]）。DataWatcher 槽 16=int profession（`getProfession()`:358 做 `% 5` 截断）。

- `public boolean interact(EntityPlayer player)`（EntityVillager.java:227）：非幼体且未交易中时 `setCustomer(player); player.displayVillagerTradeGui(this)`（服务端），并触发 `StatList.timesTalkedToVillagerStat`。
- `public MerchantRecipeList getRecipes(EntityPlayer p_70934_1_)`（:560）与 `private void populateBuyingList()`（:570）：懒生成 careerId/careerLevel 并从静态表填充。
- `public void useRecipe(MerchantRecipe recipe)`（:503）：交易结算——`incrementToolUses`、概率解锁下一级（`timeUntilReset = 40; needsInitilization = true`）、累计 `wealth`、生成 `EntityXPOrb`。由 `SlotMerchantResult`/服务端容器逻辑调用。
- `protected void updateAITasks()`（:165）：每约 70-120 tick 注册位置到 `VillageCollection` 并解析最近村庄；交易冷却结束时补货 + `Potion.regeneration`。
- `handleStatusUpdate`（:714）：12=HEART、13=VILLAGER_ANGRY、14=VILLAGER_HAPPY 粒子。
- 雷击 `onStruckByLightning`（:777）：原地换成 `EntityWitch` 并 `setDead()`。

### EntityWolf（EntityWolf.java）

驯服 + 战斗 + 最重的客户端动画状态。DataWatcher：槽 18=Float 血量镜像（`updateAITasks`:123 每 tick 写入，供 `getLivingSound`/`getTailRotation` 在客户端读）、槽 19=byte begging、槽 20=byte 项圈色；槽 16 bit1=angry（`isAngry`:518）。

- `public boolean interact(EntityPlayer player)`（EntityWolf.java:386）：已驯服→喂肉回血/染料换项圈/主人右键坐下切换；未驯服→骨头 1/3 概率驯服（`setTamed(true)`、`setHealth(20.0F)`、`setEntityState (byte)7`）。
- `public void onUpdate()`（:227）：客户端插值 `headRotationCourse`（乞食抬头）与甩水动画 `timeWolfIsShaking`，喷 WATER_SPLASH 粒子；`handleStatusUpdate` id==8（:479）由服务端 `onLivingUpdate`（:210-216）触发开始甩水。
- 渲染读点：`getShadingWhileWet(float)`（:292，RenderWolf.java:36）、`getShakeAngle`（:297）、`getInterestedAngle`（:313）、`getTailRotation`（:493）。
- `public boolean shouldAttackEntity(EntityLivingBase p_142018_1_, EntityLivingBase p_142018_2_)`（:613）：过滤苦力怕/恶魂/同主人狼/已驯服马，供 `EntityAIOwnerHurtByTarget` 等使用。

## 时序与生命周期

- 构造：`EntityXxx(World)` → `super(worldIn)` 链中 `Entity` 构造器调用 `entityInit()`（各类在此注册 DataWatcher 槽），随后子类构造体注册 AI（`tasks.addTask`）。注意 `entityInit` 在子类字段初始化之前执行（Java 构造顺序），所以其中只能操作 dataWatcher。
- 首次生成（非 NBT 加载）：服务端调用 `onInitialSpawn(DifficultyInstance, IEntityLivingData)`（马 :1615 随机 type/variant/属性；兔 :443 随机皮肤；羊 :334 随机毛色；豹猫 :374 概率带两只幼崽；村民 :749 随机职业）。`GroupData`/`RabbitTypeData` 通过 `IEntityLivingData` 在同批生成的群体间传递，保证同群同类型。
- 每 tick（逻辑，integrated server 线程；纯客户端远程实体只跑客户端侧分支）：`onUpdate()` → `onLivingUpdate()` →（服务端）`updateAITasks()`。动画字段（马的 headLean/rearingAmount、狼的 headRotationCourse、鱿鱼的 squidRotation、鸡的 wingRotation）在两侧每 tick 更新，prev* 字段保存上一 tick 值。
- 每帧（渲染线程=主线程）：渲染器/模型用 partialTicks 在 prev* 与当前值之间插值（如 `getGrassEatingAmount(partialTicks)`、`prevSquidPitch + (squidPitch - prevSquidPitch) * partialTicks`）。本包自身不含渲染代码。
- 网络：客户端 Netty EventLoop 收包后经 `PacketThreadUtil` 调度回客户端主线程，`NetHandlerPlayClient.handleEntityStatus`（:1037）才调用 `handleStatusUpdate`——本包方法不会在 Netty 线程上执行。DataWatcher 变更由服务端 tick 末的 metadata 包同步。
- 死亡：`onDeath(DamageSource)`（马 :1058 掉箱子内容物；村民 :413 扣声望/结束繁殖季；EntityTameable :239 给主人发死亡消息）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public boolean interact(EntityPlayer player)` | EntityAnimal.java:151（各子类均覆写：EntityHorse.java:796、EntityVillager.java:227、EntityWolf.java:386、EntityCow.java:122、EntitySheep.java:181、EntityMooshroom.java:24、EntityPig.java:122、EntityOcelot.java:205） | 玩家右键实体，经 C02PacketUseEntity → `EntityPlayer.interactWith` | 拦截/改写喂食、驯服、骑乘、开 GUI；实现自动交互功能 | 客户端与服务端都会执行，副作用（扣物品）需按 `worldObj.isRemote` 区分；返回 true 阻断后续处理 |
| `public void handleStatusUpdate(byte id)` | EntityAnimal.java:224、EntityHorse.java:1739、EntityTameable.java:100、EntityWolf.java:479、EntityVillager.java:714、EntitySheep.java:148、EntityRabbit.java:491、EntitySquid.java:235 | 客户端主线程，NetHandlerPlayClient.java:1037 收到 S19PacketEntityStatus | 观察服务端事件（驯服成功/失败 7/6、繁殖 18、羊吃草 10、狼甩水 8）；ESP/提示类功能的事件源 | 仅客户端；id 语义按类不同，勿跨类复用 |
| `public boolean isTamed()` / `public EntityLivingBase getOwner()` | EntityTameable.java:116 / EntityTameable.java:170 | 任意线程读 DataWatcher | 目标选择器排除自家宠物；显示宠物归属 | `getOwner()` 只查在线玩家，离线返回 null |
| `public void moveEntityWithHeading(float strafe, float forward)` | EntityHorse.java:1306 | 每 tick，`EntityLivingBase.onLivingUpdate` 移动阶段 | 修改骑乘手感、速度、跳跃（horse speed/jump 类功能的核心改写点） | 服务端权威移动只在 `!worldObj.isRemote` 分支执行 super；客户端只做动画 |
| `public void setJumpPower(int jumpPowerIn)` | EntityHorse.java:1698 | 服务端，NetHandlerPlayServer.java:877 处理 C0CPacketInput auxData | 改写马跳蓄力曲线（90 满蓄 → 1.0F） | 未装鞍时整个方法为 no-op |
| `public void openGUI(EntityPlayer playerEntity)` | EntityHorse.java:784 | interact 内多个分支（sneak 右键、持鞍/甲） | 拦截马背包 GUI 打开；`displayGUIHorse` 客户端实现在 EntityPlayerSP.java:640 | 仅服务端执行且要求 `isTame()` |
| `public MerchantRecipeList getRecipes(EntityPlayer p_70934_1_)` | EntityVillager.java:560 | 打开交易 GUI 时（服务端），及 GUI 数据请求 | 读取/改写交易列表（auto-trade、交易预览） | 首次调用有懒初始化副作用（populateBuyingList 会随机 careerId） |
| `public void useRecipe(MerchantRecipe recipe)` | EntityVillager.java:503 | 交易槽取走结果物品时（服务端容器） | 观察交易完成、统计 wealth、触发自动续购 | 有概率触发补货锁定（timeUntilReset=40） |
| `public boolean canMateWith(EntityAnimal otherAnimal)` | EntityAnimal.java:219（EntityHorse.java:1521、EntityWolf.java:579、EntityOcelot.java:278 覆写） | `EntityAIMate` 每 tick 扫描 | 自动繁殖类功能的判定点 | — |
| `public void setInLove(EntityPlayer player)` | EntityAnimal.java:191 | interact 喂食成功后 | 繁殖流程观测/触发 | 服务端语义；playerInLove 用于繁殖成就归属 |
| `public boolean attackEntityFrom(DamageSource source, float amount)` | EntityAnimal.java:67（EntityBat.java:216、EntityHorse.java:368、EntityWolf.java:335、EntityOcelot.java:178、EntityRabbit.java:345 覆写） | 受击路径 | 观察实体受伤；注意马会忽略骑手伤害（:371） | 覆写链有副作用（清 inLove、狼站起、蝙蝠松爪） |
| `public String getHorseTexture()` / `public String[] getVariantTexturePaths()` | EntityHorse.java:764 / EntityHorse.java:774 | 每帧，RenderHorse.java:82/95 | 替换马纹理、注入自定义 LayeredTexture | `field_175508_bO` 为 false 时 RenderHorse 走 fallback；变更 DataWatcher 后由 onUpdate:1118 重置缓存 |

## 数据与协议

### DataWatcher 槽位（随 entity metadata 包同步，客户端功能可直接读）

| 类 | 槽 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|---|
| EntityBat | 16 | byte | `getIsBatHanging`/`setIsBatHanging`（:94/:99） | bit0=倒挂 |
| EntityPig | 16 | byte | `getSaddled`/`setSaddled`（:176/:184） | bit0=有鞍 |
| EntitySheep | 16 | byte | `getFleeceColor`/`setFleeceColor`（:260/:268）、`getSheared`/`setSheared`（:277/:285） | 低 4 位=毛色 meta，bit4=已剪毛 |
| EntityTameable | 16 | byte | `isSitting`/`isTamed`（:141/:116） | bit0=坐下，bit2=已驯服；EntityWolf 复用 bit1=angry（:518） |
| EntityTameable | 17 | String | `getOwnerId`/`setOwnerId`（:160/:165） | owner UUID 字符串 |
| EntityOcelot | 18 | byte | `getTameSkin`/`setTameSkin`（:299/:304） | 猫皮肤 0-3 |
| EntityRabbit | 18 | byte | `getRabbitType`/`setRabbitType`（:415/:420） | 皮肤 0-5；99=杀手兔 |
| EntityWolf | 18 | Float | `updateAITasks` 写（:125），`getWatchableObjectFloat(18)` 读 | 血量镜像（尾巴角度/叫声用） |
| EntityWolf | 19 / 20 | byte / byte | `isBegging`/`setBegging`（:600/:564）、`getCollarColor`/`setCollarColor`（:540/:545） | 乞食标志 / 项圈染料色 |
| EntityHorse | 16 | int | `getHorseWatchableBoolean`/`setHorseWatchableBoolean`（:175/:180） | 位段：2 tamed、4 saddled、8 chested、16 breeding、32 eating、64 rearing、128 mouth |
| EntityHorse | 19 / 20 | byte / int | `getHorseType`（:125）/ `getHorseVariant`（:136） | 种类 0-4 / 毛色+斑纹 |
| EntityHorse | 21 / 22 | String / int | `getOwnerId`（:212）/ `getHorseArmorIndexSynced`（:278） | ownerUUID / 护甲 0-3 |
| EntityVillager | 16 | int | `getProfession`/`setProfession`（:358/:353） | 职业 0-4（读取时 `% 5`） |

### Entity status 事件（S19PacketEntityStatus → `handleStatusUpdate`）

| id | 类 | 效果 |
|---|---|---|
| 6 / 7 | EntityTameable.java:100、EntityHorse.java:1739 | 驯服失败烟雾 / 成功心形 |
| 8 | EntityWolf.java:479 | 开始甩水动画 |
| 10 | EntitySheep.java:148 | `sheepTimer = 40`（吃草低头动画） |
| 1 | EntityRabbit.java:491 | 起跳粒子 + 跳跃动画计数 |
| 12/13/14 | EntityVillager.java:714 | HEART / VILLAGER_ANGRY / VILLAGER_HAPPY 粒子 |
| 18 | EntityAnimal.java:224 | 繁殖心形粒子 |
| 19 | EntitySquid.java:235 | `squidRotation = 0.0F`（动画相位同步） |

### NBT 键（各类 `writeEntityToNBT`/`readEntityFromNBT`）

| 类 | 键 | 类型 | 含义 |
|---|---|---|---|
| EntityAnimal | `InLove` | int | 剩余求偶 tick |
| EntityBat | `BatFlags` | byte | dataWatcher 槽 16 原样存取 |
| EntityChicken | `IsChickenJockey` / `EggLayTime` | boolean / int | 鸡骑士 / 下蛋倒计时 |
| EntityPig | `Saddle` | boolean | 鞍 |
| EntitySheep | `Sheared` / `Color` | boolean / byte | 剪毛 / 毛色 |
| EntityRabbit | `RabbitType` / `MoreCarrotTicks` | int / int | 类型 / 偷萝卜冷却 |
| EntityOcelot | `CatType` | int | 猫皮肤 |
| EntityTameable | `OwnerUUID`（兼容旧 `Owner`）/ `Sitting` | String / boolean | 主人 / 坐下 |
| EntityWolf | `Angry` / `CollarColor` | boolean / byte | 愤怒 / 项圈色 |
| EntityHorse | `EatingHaystack` `ChestedHorse` `HasReproduced` `Bred` `Type` `Variant` `Temper` `Tame` `OwnerUUID` `Items` `ArmorItem` `SaddleItem`（兼容旧 `Saddle`） | 混合 | 见 EntityHorse.java:1392-1516 |
| EntityVillager | `Profession` `Riches` `Career` `CareerLevel` `Willing` `Offers` `Inventory` | 混合 | 交易与库存全量持久化（:258-319） |

## 不变量与陷阱

- `entityInit()` 在子类构造函数体和字段初始化器之前运行（父类构造链触发）。若在子类里加字段并在 `entityInit` 使用会得到默认值/NPE。
- DataWatcher 槽位是继承敏感的：`EntityTameable` 占 16/17，其子类只能从 18 起（EntityOcelot 用 18，EntityWolf 用 18/19/20）；`EntityHorse` 直接绕过 EntityTameable 自己实现 tame/owner（槽 16 位段 + 槽 21），所以 `EntityHorse.isTame()` 与 `EntityTameable.isTamed()` 是两套互不相通的机制——写通用"宠物判断"时必须分别处理。
- 副作用必须按 `worldObj.isRemote` 分侧：掉落、生成实体、`setEntityState` 只在服务端；粒子只在客户端（`handleStatusUpdate` 路径）。`interact` 两侧都会跑。
- `EntityHorse.setHorseType/setHorseVariant/setHorseArmorStack` 都会 `resetTexturePrefix()`；客户端另在 `onUpdate`（:1118）监听 `dataWatcher.hasObjectChanged()` 兜底。绕过这些 setter 直接写 dataWatcher 会导致纹理不刷新。
- `EntityVillager.getProfession()` 对同步值取 `% 5` 后 `Math.max(...,0)`，负数/越界 profession 不会数组越界；但 `populateBuyingList` 的 careerId 随机化只在 careerId==0 或 careerLevel==0 时发生（条件为 `careerId != 0 && careerLevel != 0` 才递增），NBT 中的非法 `Career` 值可能越过 `aentityvillager$itradelist[i]` 边界（j 有范围检查，i 没有）——修改交易数据时注意。
- `EntitySquid` 构造函数 `this.rand.setSeed((long)(1 + this.getEntityId()))`（:46）：动画随机性绑定 entityId，测试或复用实体时随机序列可预测。
- `EntityWolf.getLivingSound`/`getTailRotation` 读 dataWatcher 槽 18 的血量镜像而不是 `getHealth()`，因为 1.8 的 health 本身不向客户端同步其它实体的精确值。
- `EntityRabbit` 替换了 `jumpHelper` 与 `moveHelper` 为内部子类（:57-58），任何假设 `EntityLiving.jumpHelper` 为基类类型的代码需做 instanceof 检查。
- LWJGL3/JDK25 移植面：本包为纯逻辑代码，无直接 GL/键盘依赖，未发现移植改动；`EntityBat` 用 `java.util.Calendar` 判万圣节（:280-283），JDK25 下行为不变。`new Byte(...)`/`new Float(...)`（EntityBat.java:29、EntityWolf.java:131 等）是已废弃构造器，JDK25 编译会有 deprecation 警告但可用。
- 线程安全：所有方法假定单线程（逻辑 tick 线程 / 客户端主线程）访问；DataWatcher 内部有锁，但本包对它的 read-modify-write 位操作（如 `setHorseWatchableBoolean`）不是原子的，不能从其它线程调用。

## 交叉引用

- net/minecraft/entity/ai → 各构造函数注册的全部 AI 任务；`EntityAIMate#canMateWith` 依赖 `EntityAnimal#canMateWith` 与 `EntityAnimal#createChild`；`EntityAISit` 由 `EntityTameable#getAISit` 暴露
- net/minecraft/client/network → `NetHandlerPlayClient#handleEntityStatus`（:1037）→ 各类 `#handleStatusUpdate`
- net/minecraft/network/play/server（NetHandlerPlayServer.java:877）→ `EntityHorse#setJumpPower`（C0CPacketInput auxData）
- net/minecraft/client/entity → `EntityPlayerSP#displayGUIHorse`（:640）、`EntityPlayerSP#isRidingHorse`（:572，读 `EntityHorse#isHorseSaddled`）
- net/minecraft/client/renderer/entity → `RenderHorse#getEntityTexture`（:82/:95）读 `EntityHorse#getHorseTexture`/`#getVariantTexturePaths`；`RenderWolf`（:36）读 `EntityWolf#getShadingWhileWet`；`RenderSquid`（:27）读 `EntitySquid#squidPitch`；`layers/LayerSheepWool`（:41）读 `EntitySheep.getDyeRgb` 与 `#getFleeceColor`
- net/minecraft/client/model → `ModelHorse`（:214/:378）读 `EntityHorse#getGrassEatingAmount`/`#getRearingAmount`/`#getMouthOpennessAngle`
- net/minecraft/village → `EntityVillager#updateAITasks` 调 `VillageCollection#addToVillagerPositionList`/`#getNearestVillage`、`Village#setReputationForPlayer`/`#endMatingSeason`；`MerchantRecipeList`/`MerchantRecipe` 是交易数据结构
- net/minecraft/inventory → `AnimalChest`（马背包，`EntityHorse implements IInvBasic#onInventoryChanged`）、`InventoryBasic`（村民 8 格）、`InventoryCrafting`（`EntitySheep#getDyeColorMixFromParents` 用合成表混色）
- net/minecraft/item/crafting → `CraftingManager#findMatchingRecipe`（EntitySheep.java:350）
- net/minecraft/entity/monster → `EntityPig#onStruckByLightning` 生成 `EntityPigZombie`；`EntityVillager#onStruckByLightning` 生成 `EntityWitch`；`EntityWolf` 目标过滤引用 `EntityCreeper`/`EntityGhast`/`EntitySkeleton`
- net/minecraft/server/management → `PreYggdrasilConverter#getStringUUIDFromName`（旧存档 owner 名转 UUID，EntityHorse.java:1459、EntityTameable.java:66）
- net/minecraft/stats → `EntityPig#fall` 触发 `AchievementList.flyPig`；`EntityVillager#interact` 触发 `StatList.timesTalkedToVillagerStat`

## 覆盖声明

完整读取了 17/17 个文件（每个文件从第 1 行到最后一行全量 Read）。

- 逐行精读：EntityAnimal、EntityTameable、EntityHorse、EntityVillager、EntityWolf、EntityRabbit、EntitySheep、EntityBat、EntityOcelot、EntityPig、EntitySquid、EntityWaterMob、EntityAmbientCreature、IAnimals。
- 全量读取但只做结构级归纳（未逐分支推演）：EntityChicken、EntityCow、EntityMooshroom（逻辑简单，表格与挂钩点已覆盖其全部公开行为）。
- 另抽查了包外调用点以核实交叉引用：NetHandlerPlayClient.java:1037、NetHandlerPlayServer.java:877、RenderHorse.java:82/95、RenderWolf.java:36、RenderSquid.java:27、LayerSheepWool.java:41、ModelHorse.java:214/378、EntityPlayerSP.java:572/640。
