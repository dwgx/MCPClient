---
area: net/minecraft
slug: net-minecraft
files: 62
lines: 10344
tier: A
---

# net/minecraft（散装子包：crash / creativetab / dispenser / event / init / pathfinding / potion / profiler / scoreboard / stats / village）

> 本文覆盖 `client/src/main/java/net/minecraft/` 下不属于大型子系统（client、world、entity、network 等单独成桶）的 11 个小子包，共 62 个文件。所有路径均相对 `client/src/main/java/net/minecraft/`。

## 定位

这批包是客户端里的"基础设施 + 游戏规则杂项"层：

- **init**（`Bootstrap`/`Blocks`/`Items`）是整个游戏对象体系的启动闸门。`Minecraft#startGame` 在一切游戏逻辑之前调用 `Bootstrap.register()`（`client/Minecraft.java:397`），依次注册方块、物品、统计、发射器行为；`Blocks`/`Items` 只是注册表的静态快照。它消失则所有引用 `Blocks.xxx` / `Items.xxx` 的代码在类加载期直接抛 `RuntimeException`。
- **crash** 是全局异常出口。`Minecraft` 主循环 catch 到任何异常都会经 `CrashReport.makeCrashReport` 组装并 `displayCrashReport`（`client/Minecraft.java:412/450/755`）。
- **profiler** 提供主循环分段计时（`Minecraft.mcProfiler`，`client/Minecraft.java:314`）与遥测上报（`PlayerUsageSnooper`，客户端与集成服务端各持一份）。
- **potion** 是药水效果的数据与规则层，`EntityLivingBase` 每 tick 驱动（`entity/EntityLivingBase.java:618`）。
- **pathfinding** 是生物 AI 寻路，由 `EntityLiving` 每 tick 驱动（`entity/EntityLiving.java:635`），只在集成服务端线程上有意义。
- **scoreboard** 是计分板模型：客户端侧 `Scoreboard` 是纯数据容器（由 `NetHandlerPlayClient` 的 S3B~S3E 包处理器填充，`GuiIngame` 渲染），服务端侧 `ServerScoreboard` 负责把变更广播成封包并持久化。
- **stats** 是统计/成就体系：`StatList` 在 Bootstrap 期建全量注册表，`StatFileWriter` 是客户端玩家的统计容器，`StatisticsFile` 是服务端按玩家落盘的 JSON 文件。
- **village / dispenser / creativetab / event** 分别服务于村庄机制（服务端 tick）、发射器行为策略、创造模式物品栏分组、聊天组件的点击/悬停事件。

调用方向总结：`client`（Minecraft、GuiIngame、GuiScreen、GuiContainerCreative、NetHandlerPlayClient）、`entity`（EntityLiving、EntityLivingBase、EntityVillager）、`world`（WorldServer）、`item`（ItemPotion）、`server`（MinecraftServer）调用本桶；本桶向下调用 `block`、`item`、`nbt`、`network.play.server`、`util`。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| crash/CrashReport | 395 | - | 组装崩溃报告（环境信息、分节、栈裁剪）并写入文件 |
| crash/CrashReportCategory | 308 | - | 崩溃报告的一个分节；键值条目 + 裁剪后的栈；附带方块/坐标信息静态助手 |
| creativetab/CreativeTabs | 289 | abstract | 创造模式物品栏 12 个标签页的注册表与元数据（图标、背景、附魔类型） |
| dispenser/BehaviorDefaultDispenseItem | 80 | implements IBehaviorDispenseItem | 默认发射行为：弹出 EntityItem + 音效 + 粒子 |
| dispenser/BehaviorProjectileDispense | 49 | extends BehaviorDefaultDispenseItem | 抛射物发射行为模板（子类给出实体工厂） |
| dispenser/IBehaviorDispenseItem | 19 | interface | 发射行为策略接口；含 no-op 单例 itemDispenseBehaviorProvider |
| dispenser/IBlockSource | 19 | interface extends ILocatableSource | 发射源视图：坐标、BlockPos、metadata、TileEntity |
| dispenser/ILocatableSource | 5 | interface extends ILocation | 纯标记接口 |
| dispenser/ILocation | 8 | interface extends IPosition | 位置 + getWorld() |
| dispenser/IPosition | 10 | interface | double 三坐标 |
| dispenser/PositionImpl | 30 | implements IPosition | IPosition 的不可变实现 |
| event/ClickEvent | 124 | - | 聊天组件点击事件（action + value），Action 枚举含 canonicalName 映射 |
| event/HoverEvent | 123 | - | 聊天组件悬停事件（action + IChatComponent value） |
| init/Blocks | 456 | - | 方块注册表静态快照（198 个 public static final 字段） |
| init/Bootstrap | 538 | - | 一次性注册入口：Block/BlockFire/Item/StatList/发射器行为；stdout 重定向 |
| init/Items | 407 | - | 物品注册表静态快照（187 个 public static final 字段） |
| pathfinding/Path | 175 | - | PathPoint 二叉堆（按 distanceToTarget 排序的优先队列） |
| pathfinding/PathEntity | 129 | - | 一条已算好的路径（PathPoint[] + 当前索引） |
| pathfinding/PathFinder | 139 | - | A* 主循环，借 NodeProcessor 展开邻居节点 |
| pathfinding/PathNavigate | 337 | abstract | 生物导航器基类：请求寻路、每 tick 跟随路径、卡住检测 |
| pathfinding/PathNavigateClimber | 79 | extends PathNavigateGround | 爬墙生物（蜘蛛）导航：寻路失败时直接朝目标点移动 |
| pathfinding/PathNavigateGround | 296 | extends PathNavigate | 地面导航：WalkNodeProcessor、避水/避光、直线可达检测 |
| pathfinding/PathNavigateSwimmer | 76 | extends PathNavigate | 水中导航：SwimNodeProcessor + 射线直达检测 |
| pathfinding/PathPoint | 102 | - | 寻路节点（int 三坐标 + A* 权重字段 + 坐标哈希） |
| potion/Potion | 378 | - | 药水类型注册表（potionTypes[32]）与效果执行逻辑 |
| potion/PotionAbsorption | 25 | extends Potion | 伤害吸收：apply/remove 时直接改 absorptionAmount |
| potion/PotionAttackDamage | 17 | extends Potion | 力量/虚弱的攻击力修饰量计算 |
| potion/PotionEffect | 255 | - | 一个激活中的效果实例（id/duration/amplifier）+ NBT 读写 |
| potion/PotionHealth | 27 | extends Potion | 瞬间治疗/伤害（isInstant=true） |
| potion/PotionHealthBoost | 23 | extends Potion | 生命上限提升；移除时把血量夹回上限 |
| potion/PotionHelper | 604 | - | 药水 metadata 位串 DSL：配方解析、液体颜色、名称前缀 |
| profiler/IPlayerUsage | 13 | interface | snooper 数据提供方接口（Minecraft 与 MinecraftServer 实现） |
| profiler/PlayerUsageSnooper | 189 | - | 遥测采集器：Timer 线程每 15 分钟 POST 到 snoop.minecraft.net |
| profiler/Profiler | 186 | - | 分段计时器（点分层级 section 栈 + 耗时累计） |
| scoreboard/GoalColor | 36 | implements IScoreObjectiveCriteria | teamkill.<color> / killedByTeam.<color> 判据 |
| scoreboard/IScoreObjectiveCriteria | 60 | interface | 计分判据接口 + 全局 INSTANCES 注册表 + EnumRenderType |
| scoreboard/Score | 116 | - | 单个（玩家, objective）的分数；setScorePoints 触发脏通知 |
| scoreboard/ScoreDummyCriteria | 35 | implements IScoreObjectiveCriteria | 普通可写判据（dummy/trigger/deathCount 等） |
| scoreboard/ScoreHealthCriteria | 40 | extends ScoreDummyCriteria | health 判据：只读、按玩家血量+吸收计算、HEARTS 渲染 |
| scoreboard/ScoreObjective | 58 | - | 记分项（name + criteria + displayName + renderType） |
| scoreboard/ScorePlayerTeam | 184 | extends Team | 具体队伍：前后缀、友伤、可见性；所有 setter 触发 sendTeamUpdate |
| scoreboard/Scoreboard | 518 | - | 计分板数据容器（objectives/scores/teams/19 个显示槽），通知方法为空实现 |
| scoreboard/ScoreboardSaveData | 308 | extends WorldSavedData | 计分板 NBT 持久化（scoreboard.dat） |
| scoreboard/ServerScoreboard | 271 | extends Scoreboard | 服务端计分板：变更时广播 S3B/S3C/S3D/S3E 封包并 markDirty |
| scoreboard/Team | 68 | abstract | 队伍抽象 + EnumVisible（nametag/死亡消息可见性） |
| stats/Achievement | 169 | extends StatBase | 成就：GUI 网格坐标、父成就、描述、special 标记 |
| stats/AchievementList | 127 | - | 全部 34 个成就的静态注册表与 GUI 网格边界 |
| stats/IStatStringFormat | 11 | interface | 成就描述占位符格式化接口 |
| stats/IStatType | 9 | interface | 统计数值格式化接口（format(int)） |
| stats/ObjectiveStat | 14 | extends ScoreDummyCriteria | 把 StatBase 桥接成计分板判据 |
| stats/StatBase | 180 | - | 统计条目基类：statId、格式化器、聊天组件、注册去重 |
| stats/StatBasic | 26 | extends StatBase | 通用统计（额外进入 StatList.generalStats） |
| stats/StatCrafting | 27 | extends StatBase | 与具体 Item 绑定的统计（挖掘/合成/使用/损耗） |
| stats/StatFileWriter | 100 | - | 玩家统计内存容器（ConcurrentMap），客户端直接使用 |
| stats/StatisticsFile | 250 | extends StatFileWriter | 服务端每玩家统计：JSON 读写、成就广播、S37 增量同步 |
| stats/StatList | 302 | - | 全量统计注册表：通用统计 + 按 Block/Item 生成的四类数组 |
| village/MerchantRecipe | 180 | - | 单条村民交易（buy/buyB/sell/uses/maxUses）+ NBT 读写 |
| village/MerchantRecipeList | 133 | extends ArrayList&lt;MerchantRecipe&gt; | 交易列表：匹配查找、PacketBuffer 序列化、NBT 序列化 |
| village/Village | 582 | - | 单个村庄：门列表、中心/半径、声望、敌对者、铁傀儡生成 |
| village/VillageCollection | 305 | extends WorldSavedData | 每维度村庄集合：探测木门、建村、tick 分发、villages.dat |
| village/VillageDoorInfo | 115 | - | 一扇村庄门（门位置、内侧方向、活跃时间戳） |
| village/VillageSiege | 210 | - | 僵尸围城状态机（午夜 1/10 概率，分批刷 20 只僵尸） |

## 核心类详解

### init/Bootstrap（Bootstrap.java）

- 字段：`private static boolean alreadyRegistered = false`（Bootstrap.java:60）；`private static final PrintStream SYSOUT = System.out`（Bootstrap.java:57）。
- `public static boolean isRegistered()`（Bootstrap.java:66）— 被 `Blocks`/`Items` 的静态块用作守卫。
- `public static void register()`（Bootstrap.java:506）— 依次执行 `Block.registerBlocks(); BlockFire.init(); Item.registerItems(); StatList.init(); registerDispenserBehaviors();`（Bootstrap.java:517-521）。若 log4j debug 开启还会把 `System.out/err` 换成 `LoggingPrintStream`（Bootstrap.java:528-532）。调用点：`client/Minecraft.java:397`（`startGame`，主线程，早于窗口创建后的资源加载）。
- `static void registerDispenserBehaviors()`（Bootstrap.java:71）— 向 `BlockDispenser.dispenseBehaviorRegistry` 塞入 arrow/egg/snowball/experience_bottle/potionitem/spawn_egg/fireworks/fire_charge/boat/水桶岩浆桶/空桶/flint_and_steel/dye(骨粉)/TNT/skull/pumpkin 的匿名行为对象。**这是发射器行为的唯一注册处**。
- `public static void printToSYSOUT(String p_179870_0_)`（Bootstrap.java:534）— 绕过重定向直接打原始 stdout；崩溃时 `Minecraft#displayCrashReport` 用它（client/Minecraft.java:763）。

### init/Blocks 与 init/Items

- 两者结构相同：一批 `public static final` 字段 + `private static Block getRegisteredBlock(String blockName)`（Blocks.java:243）/ `private static Item getRegisteredItem(String name)`（Items.java:205），在 `static {}` 里从 `Block.blockRegistry` / `Item.itemRegistry` 按名字取出（Blocks.java:248-455、Items.java:210-406）。
- 静态块开头强校验：`if (!Bootstrap.isRegistered()) throw new RuntimeException("Accessed Blocks before Bootstrap!")`（Blocks.java:250-253；Items.java:212-215 同理，消息为 `"Accessed Items before Bootstrap!"`）。
- 注意字段名与注册名不一致的几处：`oak_door = getRegisteredBlock("wooden_door")`（Blocks.java:322）、`oak_fence = getRegisteredBlock("fence")`（Blocks.java:348）、`oak_fence_gate = getRegisteredBlock("fence_gate")`（Blocks.java:374）、`slime_block = getRegisteredBlock("slime")`（Blocks.java:442）、`Items.oak_door = getRegisteredItem("wooden_door")`（Items.java:286）、`Items.potionitem = getRegisteredItem("potion")`（Items.java:347）。

### crash/CrashReport（CrashReport.java）

- 字段：`private final String description`；`private final Throwable cause`；`private final CrashReportCategory theReportCategory = new CrashReportCategory(this, "System Details")`（CrashReport.java:33）；`private File crashReportFile`。
- `public CrashReport(String descriptionIn, Throwable causeThrowable)`（CrashReport.java:43）构造时即调用 `populateEnvironment()`（CrashReport.java:54），登记 Minecraft Version（硬编码 `"1.8.9"`，CrashReport.java:60）、OS、Java 版本、内存、`-X` JVM 参数（通过 `ManagementFactory.getRuntimeMXBean()`）、`IntCache.getCacheSizes()`。
- `public static CrashReport makeCrashReport(Throwable causeIn, String descriptionIn)`（CrashReport.java:380）— 若 cause 是 `ReportedException` 则复用其内嵌报告。
- `public String getCompleteReport()`（CrashReport.java:229）；`public boolean saveToFile(File toFile)`（CrashReport.java:266）— 只写一次，重复调用返回 false。
- `public CrashReportCategory makeCategoryDepth(String categoryName, int stacktraceLength)`（CrashReport.java:311）— 追加分节并做栈去重裁剪。
- 调用者：`Minecraft#run/runGameLoop` 的异常路径（client/Minecraft.java:412、437、450、458）、各处 `ReportedException` 抛出点（如 world/entity tick 包裹）。

### crash/CrashReportCategory（CrashReportCategory.java）

- `public void addCrashSection(String sectionName, Object value)`（CrashReportCategory.java:106）；`public void addCrashSectionCallable(String sectionName, Callable<String> callable)`（CrashReportCategory.java:91）— Callable 立即求值，异常降级为 `~~ERROR~~` 条目。
- `public static String getCoordinateInfo(BlockPos pos)`（CrashReportCategory.java:28）— 生成 World/Chunk/Region 三级坐标描述。
- `public static void addBlockInfo(CrashReportCategory category, final BlockPos pos, final Block blockIn, final int blockData)`（CrashReportCategory.java:215）与 `IBlockState` 重载（CrashReportCategory.java:256）。
- `public int getPrunedStackTrace(int size)`（CrashReportCategory.java:123）— 截取当前线程栈并剪掉顶部 `3 + size` 帧。

### profiler/Profiler（Profiler.java）

- 字段：`public boolean profilingEnabled`（Profiler.java:18）；`private String profilingSection = ""`；`private final Map<String, Long> profilingMap`。
- `public void startSection(String name)`（Profiler.java:37）/ `public void endSection()`（Profiler.java:55）/ `public void endStartSection(String name)`（Profiler.java:152）— section 名用 `.` 级联；单段超过 100ms 会 `logger.warn("Something's taking too long! ...")`（Profiler.java:73-76）。
- `public List<Profiler.Result> getProfilingData(String profilerName)`（Profiler.java:82）— F3 调试饼图数据源；每次取数把所有累计值乘 999/1000 做衰减（Profiler.java:133-136）。
- 实例：客户端 `Minecraft.mcProfiler`（client/Minecraft.java:314，每帧 `startSection("root")` 于 client/Minecraft.java:1081）；世界侧 `World.theProfiler`（`PathNavigate.getPathToPos` 里的 `"pathfind"` 段即打进它，PathNavigate.java:90）。
- 线程归属：完全无锁，**只能在拥有该实例的线程使用**（mcProfiler=主线程，WorldServer.theProfiler=服务端线程）。

### profiler/PlayerUsageSnooper（PlayerUsageSnooper.java）

- `public PlayerUsageSnooper(String side, IPlayerUsage playerStatCollector, long startTime)`（PlayerUsageSnooper.java:35）— URL 为 `"http://snoop.minecraft.net/" + side + "?version=" + 2`（PlayerUsageSnooper.java:39）。
- `public void startSnooper()`（PlayerUsageSnooper.java:53）— 在 `Timer("Snooper Timer", true)` 守护线程上每 `900000L` ms 执行一次 `HttpUtil.postMap(...)`（PlayerUsageSnooper.java:80-83），受 `isSnooperEnabled()` 开关控制。
- `public void addClientStat(String statName, Object statValue)`（PlayerUsageSnooper.java:129）/ `public void addStatToSnooper(String statName, Object statValue)`（PlayerUsageSnooper.java:137）— 均以 `syncLock` 同步，可跨线程调用。
- 实例：`client/Minecraft.java:226`（"client"）、`server/MinecraftServer.java:90`（"server"，tick>100 后启动，server/MinecraftServer.java:722-724）。

### potion/Potion（Potion.java）

- 注册表：`public static final Potion[] potionTypes = new Potion[32]`（Potion.java:23）+ `private static final Map<ResourceLocation, Potion> field_180150_I`（Potion.java:24）。构造器 `protected Potion(int potionID, ResourceLocation location, boolean badEffect, int potionColor)`（Potion.java:104）自动写入两者。23 个静态实例（moveSpeed…saturation，Potion.java:26-74）。
- `public void performEffect(EntityLivingBase entityLivingBaseIn, int p_76394_2_)`（Potion.java:150）— regeneration/poison/wither/hunger/saturation/heal/harm 的每 tick 逻辑；由 `PotionEffect.performEffect` 转发。
- `public boolean isReady(int p_76397_1_, int p_76397_2_)`（Potion.java:230）— 各效果的触发节流（如 regen 为 `50 >> amplifier` tick 一次）。
- `public void affectEntity(Entity p_180793_1_, Entity p_180793_2_, EntityLivingBase entityLivingBaseIn, int p_180793_4_, double p_180793_5_)`（Potion.java:194）— 喷溅药水按距离衰减的瞬时效果。
- 属性修饰：`public Potion registerPotionAttributeModifier(IAttribute p_111184_1_, String p_111184_2_, double p_111184_3_, int p_111184_5_)`（Potion.java:334）；`public void applyAttributesModifiersToEntity(EntityLivingBase entityLivingBaseIn, BaseAttributeMap p_111185_2_, int amplifier)`（Potion.java:359）/ `removeAttributesModifiersFromEntity`（Potion.java:346）— 效果添加/移除时由 EntityLivingBase 调用。

### potion/PotionEffect（PotionEffect.java）

- 字段：`private int potionID; private int duration; private int amplifier; private boolean isSplashPotion; private boolean isAmbient; private boolean isPotionDurationMax; private boolean showParticles`（PotionEffect.java:13-29）。
- `public boolean onUpdate(EntityLivingBase entityIn)`（PotionEffect.java:126）— 每 tick 由 `EntityLivingBase`（entity/EntityLivingBase.java:618）调用；`isReady` 命中则 `performEffect`，随后 duration 自减；返回 false 表示效果结束。
- `public void combine(PotionEffect other)`（PotionEffect.java:63）— 同 id 效果合并规则（高等级覆盖，同级取长时）。
- NBT：`public NBTTagCompound writeCustomPotionEffectToNBT(NBTTagCompound nbt)`（PotionEffect.java:206）/ `public static PotionEffect readCustomPotionEffectFromNBT(NBTTagCompound nbt)`（PotionEffect.java:219）。

### potion/PotionHelper（PotionHelper.java）

- 一套以 metadata 位为变量的字符串 DSL：材料效果串常量（如 `public static final String sugarEffect = "-0+1-2-3&4-4+13"`，PotionHelper.java:13），`potionRequirements` / `potionAmplifiers` 静态表（PotionHelper.java:579-603）。
- `public static List<PotionEffect> getPotionEffects(int p_77917_0_, boolean p_77917_1_)`（PotionHelper.java:386）— metadata → 效果列表（持续时间基数 `1200 * (i * 3 + (i - 1) * 2)`，喷溅位 16384 打 0.75 折，PotionHelper.java:421-429）。
- `public static int applyIngredient(int p_77913_0_, String p_77913_1_)`（PotionHelper.java:490）— 酿造时把材料串应用到 damage 值，结果 `& 32767`。
- `public static int getLiquidColor(int dataValue, boolean bypassCache)`（PotionHelper.java:132）— 带 `DATAVALUE_COLORS` 缓存；`public static int calcPotionLiquidColor(Collection<PotionEffect> p_77911_0_)`（PotionHelper.java:68）被 `EntityLivingBase`（entity/EntityLivingBase.java:686）用于实体粒子颜色。
- 调用者：`item/ItemPotion.java:66/80/181/236`（tooltip、颜色、名称前缀）。

### pathfinding 子包

- `PathPoint`：`public final int xCoord/yCoord/zCoord`，`public static int makeHash(int x, int y, int z)`（PathPoint.java:45）把 y 压 8 位、x/z 各 15 位 + 符号位。
- `Path`：`public PathPoint addPoint(PathPoint point)`（Path.java:14）— 若 `point.index >= 0` 抛 `IllegalStateException("OW KNOWS!")`；`public PathPoint dequeue()`（Path.java:47）；`public void changeDistance(PathPoint p_75850_1_, float p_75850_2_)`（Path.java:65）。初始容量 1024，翻倍扩容。
- `PathFinder`：`public PathEntity createEntityPathTo(IBlockAccess blockaccess, Entity entityIn, BlockPos targetPos, float dist)`（PathFinder.java:33）；核心 `private PathEntity addToPath(Entity entityIn, PathPoint pathpointStart, PathPoint pathpointEnd, float maxDistance)`（PathFinder.java:55）是启发值取 `distanceToSquared` 的 A* 变体（**平方距离而非欧氏距离**，原版如此）；找不到终点时回退到"离目标最近的已访问点"（PathFinder.java:105-112）。
- `PathNavigate`：构造时读 `SharedMonsterAttributes.followRange` 作为搜索半径（PathNavigate.java:48）。`public PathEntity getPathToPos(BlockPos pos)`（PathNavigate.java:81）在 `ChunkCache`（半径 = followRange + 8）上寻路并包 `theProfiler.startSection("pathfind")`；`public boolean setPath(PathEntity pathentityIn, double speedIn)`（PathNavigate.java:152）；`public void onUpdateNavigation()`（PathNavigate.java:191）每 tick 由 `EntityLiving`（entity/EntityLiving.java:635）调用，推进路径索引并 `getMoveHelper().setMoveTo(...)`（PathNavigate.java:228）；`protected void checkForStuck(Vec3 positionVec3)`（PathNavigate.java:280）— 100 tick 内位移平方 < 2.25 则清空路径。
- `PathNavigateGround`：`protected PathFinder getPathFinder()`（PathNavigateGround.java:25）创建 `WalkNodeProcessor` 并 `setEnterDoors(true)`；`protected boolean canNavigate()`（PathNavigateGround.java:35）包含"僵尸骑鸡"特判；`protected void removeSunnyPath()`（PathNavigateGround.java:79）在 `shouldAvoidSun` 时把路径截断到第一个露天点。开关转发：`setAvoidsWater/setBreakDoors/setEnterDoors/setCanSwim`（PathNavigateGround.java:257-290）。
- `PathNavigateSwimmer.isDirectPathBetweenPoints`（PathNavigateSwimmer.java:71）用 `worldObj.rayTraceBlocks` 判定直达。
- `PathNavigateClimber.onUpdateNavigation()`（PathNavigateClimber.java:56）— 无路径时直接向 `targetPosition` 移动。

### scoreboard 子包

- `Scoreboard`（数据容器）：核心存储 `scoreObjectives`、`scoreObjectiveCriterias`、`entitiesScoreObjectives`（玩家名 → objective → Score）、`objectiveDisplaySlots = new ScoreObjective[19]`（Scoreboard.java:20）、`teams`、`teamMemberships`。
  - `public ScoreObjective addScoreObjective(String name, IScoreObjectiveCriteria criteria)`（Scoreboard.java:36）— 名字 >16 字符抛 IllegalArgumentException。
  - `public Score getValueFromObjective(String name, ScoreObjective objective)`（Scoreboard.java:96）— 玩家名 >40 抛异常；不存在则创建。
  - `public Collection<Score> getSortedScores(ScoreObjective objective)`（Scoreboard.java:124）— GuiIngame 侧边栏数据源（client/gui/GuiIngame.java:553）。
  - `public void setObjectiveInDisplaySlot(int p_96530_1_, ScoreObjective p_96530_2_)`（Scoreboard.java:246）— 槽位语义：0=list、1=sidebar、2=belowName、3~18=sidebar.team.<color>（`getObjectiveDisplaySlot`，Scoreboard.java:432）。
  - 一组空实现通知钩子：`func_96536_a(Score)`（分数变更，Scoreboard.java:399）、`func_96516_a(String)`（玩家整行移除，Scoreboard.java:403）、`func_178820_a(String, ScoreObjective)`（单分数移除，Scoreboard.java:407）、`broadcastTeamCreated`/`sendTeamUpdate`/`func_96513_c`（队伍增/改/删，Scoreboard.java:414-427）、`onScoreObjectiveAdded/onObjectiveDisplayNameChanged/onScoreObjectiveRemoved`（Scoreboard.java:387-397）。
- `ServerScoreboard`：把上述钩子全部覆写为"发包 + markDirty"。如 `public void func_96536_a(Score p_96536_1_)`（ServerScoreboard.java:27）广播 `S3CPacketUpdateScore`；`func_96549_e(ScoreObjective)`（ServerScoreboard.java:211）向所有玩家发送 objective 全量（S3B+S3D+每条 S3C）并加入已同步集合 `field_96553_b`；`sendDisplaySlotRemovalPackets`（ServerScoreboard.java:242）反向撤销。持久化经 `protected void markSaveDataDirty()`（ServerScoreboard.java:182）→ `ScoreboardSaveData.markDirty()`。
- `Score.setScorePoints(int points)`（Score.java:72）— 值变化或 forceUpdate 时回调 `getScoreScoreboard().func_96536_a(this)`；只读判据上调用 `increseScore/decreaseScore` 抛 `IllegalStateException("Cannot modify read-only score")`（Score.java:35）。
- `ScorePlayerTeam`：所有 setter（`setTeamName`/`setNamePrefix`/`setNameSuffix`/`setAllowFriendlyFire`/`setSeeFriendlyInvisiblesEnabled`/`setNameTagVisibility`/`setDeathMessageVisibility`）都触发 `theScoreboard.sendTeamUpdate(this)`（ScorePlayerTeam.java:42-150）；`public String formatString(String input)`（ScorePlayerTeam.java:95）= prefix + input + suffix，是名牌/tab 列表着色的最终出口；`func_98299_i()/func_98298_a(int)`（ScorePlayerTeam.java:152-173）是 S3E 封包的 friendlyFlags 位编码。
- `IScoreObjectiveCriteria.INSTANCES`（IScoreObjectiveCriteria.java:11）是全局判据注册表；`StatBase` 构造器会把每个统计包装成 `ObjectiveStat` 注册进去（StatBase.java:67-68），`StatCrafting` 额外按数字 id 再注册一份（StatCrafting.java:19）。
- 客户端侧填充：`NetHandlerPlayClient#handleScoreboardObjective`（client/network/NetHandlerPlayClient.java:1869）等 S3B~S3E 处理器直接调用 `Scoreboard` 的增删改（基类空通知，不回发包）。

### stats 子包

- `StatBase`：`public StatBase registerStat()`（StatBase.java:89）— statId 重复抛 `RuntimeException("Duplicate stat id: ...")`；`public IChatComponent createChatComponent()`（StatBase.java:127）生成带 `HoverEvent.Action.SHOW_ACHIEVEMENT` 的 `[名字]` 组件。四个格式化器：`simpleStatType`/`timeStatType`/`distanceStatType`/`field_111202_k`（÷10，伤害用，StatBase.java:25-60）。
- `StatList.init()`（StatList.java:125）— 顺序：`initMiningStats(); initStats(); initItemDepleteStats(); initCraftableStats(); AchievementList.init(); EntityList.func_151514_a();`。四个数组：`mineBlockStatArray[4096]`、`objectCraftStats[32000]`、`objectUseStats[32000]`、`objectBreakStats[32000]`（StatList.java:114-123）；`replaceAllSimilarBlocks`（StatList.java:247）把 lit/unlit、双台阶等等价方块合并到同一 StatBase。
- `Achievement`：构造器（Achievement.java:61）更新 `AchievementList.minDisplayColumn` 等 GUI 边界；`public Achievement registerStat()`（Achievement.java:115）额外加入 `AchievementList.achievementList`；`exploreAllBiomes` 通过 `func_150953_b(JsonSerializableSet.class)` 携带进度集合（AchievementList.java:118）。
- `StatFileWriter`：`protected final Map<StatBase, TupleIntJsonSerializable> statsData = Maps.newConcurrentMap()`（StatFileWriter.java:11）；`public void increaseStat(EntityPlayer player, StatBase stat, int amount)`（StatFileWriter.java:48）— 成就未解锁父级则忽略；`public void unlockAchievement(EntityPlayer playerIn, StatBase statIn, int p_150873_3_)`（StatFileWriter.java:59）。客户端实例挂在 `EntityPlayerSP`（client/entity/EntityPlayerSP.java:58，由 client/Minecraft.java:2405 创建）。
- `StatisticsFile`（服务端）：`public void readStatFile()`（StatisticsFile.java:41）/ `public void saveStatFile()`（StatisticsFile.java:61）— JSON 文件；覆写的 `unlockAchievement`（StatisticsFile.java:76）在成就首次达成/被清空时置脏并按 `isAnnouncingPlayerAchievements()` 向全服发 `chat.type.achievement` 消息；`public void func_150876_a(EntityPlayerMP p_150876_1_)`（StatisticsFile.java:212）每 300 tick 或有成就变化时用 `S37PacketStatistics` 增量同步；`public void sendAchievements(EntityPlayerMP player)`（StatisticsFile.java:230）登录时全量推送。

### village 子包（全部服务端线程）

- `VillageCollection`：`public void tick()`（VillageCollection.java:61）每 world tick 由 `WorldServer#tick`（world/WorldServer.java:218）调用——先 `village.tick(tickCounter)`，再清灭亡村庄、消费 `villagerPositionsList`（村民 AI 通过 `addToVillagerPositionList`（VillageCollection.java:47）上报位置，上限 64）、`addDoorsAround`（VillageCollection.java:153）在 16x4x16 范围扫木门、`addNewDoorsToVillageOrCreateVillage`（VillageCollection.java:133）把新门并入 32 格内最近村庄或建新村；每 400 tick markDirty。门方向判定：`addToNewDoorsList`（VillageCollection.java:211）比较门两侧 5 格内可见天空数。
- `Village`：`public void tick(int p_75560_1_)`（Village.java:67）— 清理失效门（门被拆或 1200 tick 无活动，Village.java:364-392）与过期敌对者（300 tick，Village.java:349）；每 20 tick 数村民、每 30 tick 数铁傀儡；满足 `numIronGolems < numVillagers/10 && doors > 20 && rand.nextInt(7000)==0` 时生成铁傀儡（Village.java:85-96）。声望：`public int setReputationForPlayer(String p_82688_1_, int p_82688_2_)`（Village.java:435）夹在 [-30,10]；`public boolean isPlayerReputationTooLow(String p_82687_1_)`（Village.java:446）阈值 ≤ -15。中心/半径由门坐标和 `centerHelper` 均值维护，`villageRadius = Math.max(32, sqrt(maxDistSq)+1)`（Village.java:419）。
- `VillageSiege`：`public void tick()`（VillageSiege.java:37）— 状态 `field_75536_c`：-1 未初始化、0 白天复位、1 围城进行、2 今晚放弃；`getCelestialAngle` 在 [0.5,0.501] 的一瞬掷 `rand.nextInt(10)==0` 决定是否围城（VillageSiege.java:47-54）；条件：≥10 门、20 tick 内无新门、≥20 村民（VillageSiege.java:115）；随后每 2 tick 刷一只，共 20 只 `EntityZombie`（`setVillager(false)`），并 `setHomePosAndDistance` 锚定村庄（VillageSiege.java:164-193）。
- `MerchantRecipe`/`MerchantRecipeList`：交易数据 + 两套序列化（NBT 与 PacketBuffer，见"数据与协议"）。`public MerchantRecipe canRecipeBeUsed(ItemStack p_77203_1_, ItemStack p_77203_2_, int p_77203_3_)`（MerchantRecipeList.java:25）是交易槽验证入口；`func_181078_a`（MerchantRecipeList.java:48）要求卖方 NBT 是买方 NBT 的子集匹配。使用方：`entity/passive/EntityVillager`、`entity/NpcMerchant`、`entity/IMerchant`。

### creativetab/CreativeTabs

- `public static final CreativeTabs[] creativeTabArray = new CreativeTabs[12]`（CreativeTabs.java:15）；构造器 `public CreativeTabs(int index, String label)`（CreativeTabs.java:116）自动入数组——**新建实例即注册，index 冲突会静默覆盖**。
- `public abstract Item getTabIconItem()`（CreativeTabs.java:151）；链式配置 `setBackgroundImageName/setNoScrollbar/setNoTitle/setRelevantEnchantmentTypes`。
- `public void displayAllReleventItems(List<ItemStack> p_78018_1_)`（CreativeTabs.java:247）— 遍历 `Item.itemRegistry` 按 `getCreativeTab()==this` 收集 + 附魔书；调用点 `client/gui/inventory/GuiContainerCreative.java:463`。

### event/ClickEvent 与 event/HoverEvent

- 结构对称：`ClickEvent(ClickEvent.Action theAction, String theValue)`（ClickEvent.java:11）；`HoverEvent(HoverEvent.Action actionIn, IChatComponent valueIn)`（HoverEvent.java:12）。
- `ClickEvent.Action`：OPEN_URL/OPEN_FILE/RUN_COMMAND/TWITCH_USER_INFO/SUGGEST_COMMAND/CHANGE_PAGE，各带 `allowedInChat`（OPEN_FILE、TWITCH_USER_INFO 为 false，ClickEvent.java:85-90）；`public static ClickEvent.Action getValueByCanonicalName(String canonicalNameIn)`（ClickEvent.java:112）供 JSON 反序列化。HoverEvent.Action：SHOW_TEXT/SHOW_ACHIEVEMENT/SHOW_ITEM/SHOW_ENTITY（HoverEvent.java:86-89）。
- 消费点：`GuiScreen#handleComponentClick`（client/gui/GuiScreen.java:392-445）执行点击动作；聊天渲染时读取 HoverEvent 画 tooltip。**`shouldAllowInChat` 是服务器下发聊天的安全阀**——OPEN_FILE 永远不该被远端触发。

### dispenser 子包

- `IBehaviorDispenseItem`：`ItemStack dispense(IBlockSource source, ItemStack stack)`（IBehaviorDispenseItem.java:18）；`itemDispenseBehaviorProvider`（IBehaviorDispenseItem.java:7）是"什么都不做"的默认值。
- `BehaviorDefaultDispenseItem`：模板方法 `public final ItemStack dispense(IBlockSource source, ItemStack stack)`（BehaviorDefaultDispenseItem.java:14）= `dispenseStack` + `playDispenseSound` + `spawnDispenseParticles`；**`dispense` 是 final，定制只能覆写后三者**。`public static void doDispense(World worldIn, ItemStack stack, int speed, EnumFacing facing, IPosition position)`（BehaviorDefaultDispenseItem.java:34）生成带随机动量的 `EntityItem`。
- `BehaviorProjectileDispense`：`protected abstract IProjectile getProjectileEntity(World worldIn, IPosition position)`（BehaviorProjectileDispense.java:38）；`func_82498_a()`=散布 6.0F、`func_82500_b()`=初速 1.1F（BehaviorProjectileDispense.java:40-48）。
- 音效协议：`playAuxSFX(1000)` 正常弹出、粒子 `2000 + 朝向编码`（BehaviorDefaultDispenseItem.java:65/73）；`1001` 失败空响与 `1009` 火焰弹在 Bootstrap 的匿名行为里（init/Bootstrap.java:331/190）；`1002` 抛射物在 BehaviorProjectileDispense.java:32。

## 时序与生命周期

**初始化（客户端主线程）**：`Minecraft#startGame` → `Bootstrap.register()`（client/Minecraft.java:397）→ `Block.registerBlocks` → `BlockFire.init` → `Item.registerItems` → `StatList.init`（内部触发 `AchievementList.init` 与 `EntityList.func_151514_a`）→ `registerDispenserBehaviors`。此后任何 `Blocks`/`Items`/`StatList`/`AchievementList` 静态字段访问才合法。`Potion` 的静态实例、`IScoreObjectiveCriteria` 的静态判据、`CreativeTabs` 的 12 个标签在各自类首次被加载时初始化（`Potion`/`CreativeTabs` 在 Bootstrap 注册物品阶段即被带出）。

**每帧（客户端主线程）**：`Minecraft#runGameLoop` 用 `mcProfiler.startSection("root")` 开帧（client/Minecraft.java:1081），随后 `"scheduledExecutables"`、`"tick"` 等段嵌套整个帧；F3 图表消费 `getProfilingData`。

**每 tick**：
- 客户端/服务端实体 tick：`EntityLivingBase` 遍历 `activePotionsMap` 调 `PotionEffect.onUpdate(this)`（entity/EntityLivingBase.java:618）；`EntityLiving` 调 `navigator.onUpdateNavigation()`（entity/EntityLiving.java:635，实际寻路只发生在服务端世界）。
- 服务端世界 tick：`WorldServer#tick` 的 `"village"` 段执行 `villageCollectionObj.tick()` 与 `villageSiege.tick()`（world/WorldServer.java:217-219）。
- 服务端统计同步：`StatisticsFile.func_150876_a` 按 300 tick 节流发 S37（StatisticsFile.java:217）。

**后台线程**：`PlayerUsageSnooper` 的 `Timer("Snooper Timer", true)` 每 15 分钟在自己的线程执行 HTTP POST（PlayerUsageSnooper.java:59-83）；与主线程通过 `syncLock` 同步。除此之外本桶所有类都无自有线程。

**线程归属**：`Scoreboard`（客户端实例）只在主线程被 NetHandlerPlayClient（经 PacketThreadUtil 调度回主线程）与 GuiIngame 访问；`ServerScoreboard`/`StatisticsFile`/`village/*`/寻路只在服务端线程；`Profiler` 与其宿主线程绑定；`StatFileWriter.statsData` 用 ConcurrentMap 容忍跨线程读。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public static void register()` | init/Bootstrap.java:506 | 游戏启动最早期（client/Minecraft.java:397），仅一次 | 在原注册完成后追加自定义方块/物品/发射行为/统计 | 必须在任何 Blocks/Items 类加载前完成；`alreadyRegistered` 使重复调用无效 |
| `static void registerDispenserBehaviors()` | init/Bootstrap.java:71 | register() 内部 | 覆盖/新增发射器对某 Item 的行为（`BlockDispenser.dispenseBehaviorRegistry.putObject`） | 后注册覆盖先注册；行为对象是共享单例，勿存易变状态（原版匿名类里的 `field_150839_b` 布尔即是踩坑示范） |
| `ItemStack dispense(IBlockSource source, ItemStack stack)` | dispenser/IBehaviorDispenseItem.java:18 | 发射器被红石触发时（服务端） | 完全接管某物品的发射逻辑 | `BehaviorDefaultDispenseItem.dispense` 是 final，需覆写 `dispenseStack`/`playDispenseSound`/`spawnDispenseParticles` |
| `public void onUpdateNavigation()` | pathfinding/PathNavigate.java:191 | 每个 EntityLiving 每 tick（entity/EntityLiving.java:635） | 观察/改写生物移动决策、注入自定义路径跟随 | 服务端线程；`currentPath` 可为 null |
| `public boolean setPath(PathEntity pathentityIn, double speedIn)` | pathfinding/PathNavigate.java:152 | AI task 请求移动时 | 拦截/替换生物目标路径（防 AI、引导） | 传 null 会清空路径并返回 false |
| `public PathEntity getPathToPos(BlockPos pos)` | pathfinding/PathNavigate.java:81 | 寻路请求 | 缓存/限流寻路（这是性能热点，含 ChunkCache 拷贝） | 内含 `theProfiler` 段，必须在世界线程调用 |
| `public boolean onUpdate(EntityLivingBase entityIn)` | potion/PotionEffect.java:126 | 每 tick、每个激活效果（entity/EntityLivingBase.java:618） | 观察效果衰减、伪造时长（UI 显示药水剩余时间即读 `getDuration()`） | 返回 false 即被移除并触发属性回滚 |
| `public void performEffect(EntityLivingBase entityLivingBaseIn, int p_76394_2_)` | potion/Potion.java:150 | isReady 命中的 tick | 修改再生/中毒/凋零等每跳效果 | id 判断链硬编码；新增 Potion 需覆写此方法 |
| `public void applyAttributesModifiersToEntity(EntityLivingBase entityLivingBaseIn, BaseAttributeMap p_111185_2_, int amplifier)` | potion/Potion.java:359 | 效果添加/等级变更时 | 速度/攻击等属性修饰注入点 | 与 `removeAttributesModifiersFromEntity`（Potion.java:346）必须成对，否则属性泄漏 |
| `public static List<PotionEffect> getPotionEffects(int p_77917_0_, boolean p_77917_1_)` | potion/PotionHelper.java:386 | 药水 tooltip/使用/酿造预览（item/ItemPotion.java:66） | 自定义药水 metadata → 效果映射 | 位串 DSL 难读；结果未缓存（颜色才有缓存） |
| `public void startSection(String name)` / `public void endSection()` | profiler/Profiler.java:37/55 | 每帧/每 tick 大量调用 | 借 section 名感知当前帧阶段；性能 HUD 数据源 `getProfilingData`（Profiler.java:82） | 仅 `profilingEnabled` 时生效；非线程安全 |
| `public void startSnooper()` | profiler/PlayerUsageSnooper.java:53 | 客户端 tick 计数、服务端 tick>100 时 | 禁用遥测（覆写 `isSnooperEnabled` 返回 false 即可静默） | Timer 线程发 HTTP，明文 http://snoop.minecraft.net（已死端点），隐私敏感 |
| `public void func_96536_a(Score p_96536_1_)` | scoreboard/Scoreboard.java:399 | 任意分数变更（Score.setScorePoints → 此处） | 客户端侧观察计分板变化（原版空实现，是理想的 UI 刷新钩子） | ServerScoreboard 覆写后会广播 S3C，别在覆写里再改分数造成递归 |
| `public void setObjectiveInDisplaySlot(int p_96530_1_, ScoreObjective p_96530_2_)` | scoreboard/Scoreboard.java:246 | S3D 包处理 / 命令 | 拦截 sidebar(槽1)/tab(槽0)/belowName(槽2) 显示切换 | 槽 3~18 是按队伍颜色的 sidebar 变体 |
| `public Collection<Score> getSortedScores(ScoreObjective objective)` | scoreboard/Scoreboard.java:124 | GuiIngame 渲染侧边栏每帧（client/gui/GuiIngame.java:553） | 自定义侧边栏排序/过滤（改 HUD 常从这截) | 每帧新建 List，注意别在此做重活 |
| `public String formatString(String input)` | scoreboard/ScorePlayerTeam.java:95 | 名牌/聊天/tab 渲染取显示名时 | 队伍前后缀着色的统一改写点 | prefix/suffix 由服务器 S3E 下发 |
| `public void sendTeamUpdate(ScorePlayerTeam playerTeam)` | scoreboard/Scoreboard.java:421 | ScorePlayerTeam 任意 setter | 观察队伍属性变更 | 客户端空实现；服务端覆写发 S3E action=2 |
| `public void increaseStat(EntityPlayer player, StatBase stat, int amount)` | stats/StatFileWriter.java:48 | 玩家做出任何计入统计的行为 | 统计/成就系统总入口：拦截可实现成就监听、行为遥测 | 成就需父级已解锁；客户端实例只是镜像，权威在服务端 StatisticsFile |
| `public void unlockAchievement(EntityPlayer playerIn, StatBase statIn, int p_150873_3_)` | stats/StatisticsFile.java:76 | 服务端统计写入 | 成就达成广播、反向清除成就（传 0） | 触发全服聊天广播（受 isAnnouncingPlayerAchievements 控制） |
| `public void func_150876_a(EntityPlayerMP p_150876_1_)` | stats/StatisticsFile.java:212 | 每 300 tick / 有变化时 | 控制 S37 统计同步节奏 | 节流字段 `field_150885_f` 初值 -300 保证首包立即发 |
| `public static CrashReport makeCrashReport(Throwable causeIn, String descriptionIn)` | crash/CrashReport.java:380 | 任何未捕获异常升级为崩溃时 | 崩溃拦截/上报的统一入口 | ReportedException 会复用旧报告，别重复包装 |
| `public boolean saveToFile(File toFile)` | crash/CrashReport.java:266 | Minecraft#displayCrashReport | 改写崩溃文件落盘位置/格式 | 仅首个调用生效（crashReportFile 非 null 直接 false） |
| `public void displayAllReleventItems(List<ItemStack> p_78018_1_)` | creativetab/CreativeTabs.java:247 | 打开/切换创造物品栏标签（client/gui/inventory/GuiContainerCreative.java:463） | 注入/隐藏创造栏物品（功能 UI 常在此加自定义条目） | 遍历全物品注册表，别每帧调用 |
| `public MerchantRecipe canRecipeBeUsed(ItemStack p_77203_1_, ItemStack p_77203_2_, int p_77203_3_)` | village/MerchantRecipeList.java:25 | 村民交易 GUI 槽位变化时 | 交易验证/自动交易逻辑 | index>0 时走精确槽匹配分支，注意 `p_77203_3_ > 0` 导致 index 0 恒走遍历分支 |
| `public void tick()` | village/VillageCollection.java:61 | 每 WorldServer tick（world/WorldServer.java:218） | 村庄机制总开关（禁围城/禁铁傀儡可从这断） | 仅服务端线程 |
| `public void tick(int p_75560_1_)` | village/Village.java:67 | VillageCollection.tick 内 | 铁傀儡自然生成条件在此（Village.java:85-96） | rand.nextInt(7000) 的低概率分支 |
| `public int setReputationForPlayer(String p_82688_1_, int p_82688_2_)` | village/Village.java:435 | 交易/攻击村民时 | 声望系统读写点 | clamp 在 [-30,10]；≤-15 触发铁傀儡敌对（Village.java:446） |
| `ClickEvent.Action#shouldAllowInChat()` | event/ClickEvent.java:102 | 聊天组件反序列化/点击校验 | 限制服务器可下发的点击动作 | OPEN_FILE/TWITCH_USER_INFO 为 false 是安全边界，勿放开 |

## 数据与协议

### PotionEffect 自定义药水 NBT（PotionEffect.java:206-241）

| 字段 | 类型 | 读写方法 | 含义 |
|---|---|---|---|
| Id | byte | `nbt.setByte("Id", ...)` / `nbt.getByte("Id")` | Potion id（0~31，越界读取返回 null） |
| Amplifier | byte | setByte/getByte | 等级-1 |
| Duration | int | setInteger/getInteger | 剩余 tick |
| Ambient | boolean | setBoolean/getBoolean | 是否信标来源 |
| ShowParticles | boolean | setBoolean/getBoolean（缺省 true，读取时 `hasKey("ShowParticles", 1)` 判定） | 是否显示粒子 |

### MerchantRecipe NBT（MerchantRecipe.java:128-179）

| 字段 | 类型 | 读写方法 | 含义 |
|---|---|---|---|
| buy | NBTTagCompound | `ItemStack.loadItemStackFromNBT` / `writeToNBT` | 第一收购物 |
| buyB | NBTTagCompound（可选） | 同上，`hasKey("buyB", 10)` | 第二收购物 |
| sell | NBTTagCompound | 同上 | 出售物 |
| uses | int | getInteger/setInteger | 已用次数 |
| maxUses | int（缺省 7） | getInteger/setInteger | 最大次数 |
| rewardExp | boolean（缺省 true） | getBoolean/setBoolean | 交易是否给经验 |

### MerchantRecipeList 网络序列化（MerchantRecipeList.java:53-106，走 S3F 自定义 payload "MC|TrList"）

| 字段 | 类型 | 写 / 读 | 含义 |
|---|---|---|---|
| size | unsigned byte | `buffer.writeByte((byte)(this.size() & 255))` / `buffer.readByte() & 255` | 条目数（上限 255） |
| itemToBuy | ItemStack | `writeItemStackToBuffer`/`readItemStackFromBuffer` | 收购物 1 |
| itemToSell | ItemStack | 同上 | 出售物 |
| hasSecond | boolean | writeBoolean/readBoolean | 有无收购物 2（有则跟一个 ItemStack） |
| disabled | boolean | `writeBoolean(merchantrecipe.isRecipeDisabled())`；读侧为 true 时 `compensateToolUses()` | 交易锁定 |
| toolUses / maxTradeUses | int / int | writeInt/readInt | 次数信息 |

### ScoreboardSaveData NBT（scoreboard.dat，ScoreboardSaveData.java:40-307）

| 键 | 类型 | 含义 |
|---|---|---|
| Objectives | TagList(10) | 每项：Name（截 16）、CriteriaName（查 `IScoreObjectiveCriteria.INSTANCES`，未知判据整项丢弃）、DisplayName、RenderType |
| PlayerScores | TagList(10) | 每项：Name（截 40）、Objective、Score(int)、Locked(bool) |
| Teams | TagList(10) | 每项：Name（截16）、DisplayName（截32）、TeamColor、Prefix、Suffix、AllowFriendlyFire、SeeFriendlyInvisibles、NameTagVisibility、DeathMessageVisibility、Players(TagList(8)) |
| DisplaySlots | Compound | `slot_0` ~ `slot_18` → objective 名 |

### Village / VillageCollection NBT（villages*.dat，Village.java:454-545、VillageCollection.java:269-299）

| 键 | 类型 | 含义 |
|---|---|---|
| Tick（顶层） | int | VillageCollection.tickCounter |
| Villages | TagList(10) | 村庄列表 |
| PopSize/Radius/Golems/Stable/Tick/MTick | int | 村民数/半径/傀儡数/最后加门时间戳/tick/禁繁殖时间戳 |
| CX,CY,CZ / ACX,ACY,ACZ | int | center / centerHelper（门坐标和） |
| Doors | TagList(10) | 每扇门：X,Y,Z,IDX,IDZ,TS（IDX/IDZ = insideDirection 偏移×2，TS = lastActivityTimestamp） |
| Players | TagList(10) | 声望：UUID（经 PlayerProfileCache 解析回名字）或旧格式 Name，S = 声望值 |

### StatisticsFile JSON（stats/<uuid>.json，StatisticsFile.java:111-202）

| 形态 | 含义 |
|---|---|
| `"stat.xxx": <int>` | 普通统计计数 |
| `"achievement.xxx": {"value": <int>, "progress": <json>}` | 带进度对象的成就（如 exploreAllBiomes 的 JsonSerializableSet，经反射 `statbase.func_150954_l().getConstructor()` 还原） |
| 未知键 | 打 warn 后忽略 |

### 计分板相关封包（仅服务端发出，ServerScoreboard.java）

| 封包 | 触发点 | 语义 |
|---|---|---|
| S3BPacketScoreboardObjective | func_96549_e(mode 0)/onObjectiveDisplayNameChanged(mode 2)/func_96548_f(mode 1) | objective 创建/更新/移除 |
| S3CPacketUpdateScore | func_96536_a / func_96516_a / func_178820_a | 分数更新/整行移除/单项移除 |
| S3DPacketDisplayScoreboard | setObjectiveInDisplaySlot | 显示槽绑定 |
| S3EPacketTeams | broadcastTeamCreated(0)/func_96513_c(1)/sendTeamUpdate(2)/addPlayerToTeam(3)/removePlayerFromTeam(4) | 队伍生命周期与成员变更 |
| S37PacketStatistics | StatisticsFile.func_150876_a / sendAchievements | 统计增量/成就全量 |

### PotionHelper 位串 DSL（PotionHelper.java:13-31、579-603）

metadata 低 15 位为效果位，bit14（16384）为喷溅位。材料串如 `"-0+1-2-3&4-4+13"`：`+n`/`-n` 置位/清位，`!n` 翻转，`&n` 条件保留；`potionRequirements` 中 `"0 & !1 & !2 & !3 & 0+6"` 是效果成立的位条件，`potionAmplifiers` 的 `"5"` 表示 bit5 为等级位。`applyIngredient` 返回值恒 `& 32767`。

## 不变量与陷阱

- **Bootstrap 先于一切**：`Blocks`/`Items` 的静态块直接抛 `RuntimeException("Accessed Blocks before Bootstrap!")`（Blocks.java:252、Items.java:214）。任何早期代码（含单元测试、mixin 静态初始化）碰 `Blocks.*` 都必须先 `Bootstrap.register()`。`alreadyRegistered` 是普通 boolean，无并发保护——注册必须且只能发生在主线程一次。
- **注册名 ≠ 字段名**：`oak_door`→`"wooden_door"`、`oak_fence`→`"fence"`、`slime_block`→`"slime"`、`potionitem`→`"potion"` 等。按字段名去 registry 查会得到 null。
- **静态可变全局遍地都是**：`Potion.potionTypes[32]`（id 越界=数组越界崩溃）、`CreativeTabs.creativeTabArray[12]`（构造即注册、同 index 静默覆盖）、`IScoreObjectiveCriteria.INSTANCES`、`StatList.oneShotStats`（重复 statId 抛 RuntimeException，StatBase.java:93）。这些结构均非线程安全，只能在主/服务端线程初始化期写。
- **Scoreboard 客户端-服务端对偶**：基类 `Scoreboard` 的全部通知方法是空壳；只有 `ServerScoreboard` 会发包。给客户端 Scoreboard"改分数"不会同步到任何地方，只影响本地渲染。
- **Score 只读判据**：对 `health` 等 `isReadOnly()==true` 的判据调用 `increseScore` 抛 IllegalStateException（Score.java:35）。名字长度硬限制：objective/team ≤16、玩家名 ≤40（Scoreboard.java:38/98/269/311），超长抛异常，NBT 读取侧则是静默截断（ScoreboardSaveData.java:71/79/159/179）——两侧行为不一致。
- **发射行为对象是共享单例**：Bootstrap 里多个匿名行为用实例字段（如 `field_150839_b`）在 `dispenseStack` 与 `playDispenseSound` 之间传状态；并发世界或重入会串味。自定义行为不要模仿。
- **寻路只在服务端**：`PathNavigate` 系依赖 `getMoveHelper()`、followRange 属性；客户端世界的实体是网络傀儡，不走这套。`Path.addPoint` 对已入堆节点抛 `IllegalStateException("OW KNOWS!")`（Path.java:18）；`PathPoint.makeHash` 只保 x/z 15 位，±16384 之外的寻路坐标会哈希碰撞（原版即如此）。
- **PathFinder 启发式用平方距离**（PathFinder.java:58-59、84），不是可采纳启发式，路径并非最短——移植时"修正"它反而会改变原版行为。
- **Village 的 NBT 读写依赖 `MinecraftServer.getServer().getPlayerProfileCache()`**（Village.java:481/533）——纯客户端上下文调用会 NPE；村庄体系整体只能在集成服务端线程使用。
- **`VillageDoorInfo.getInsidePosY()` 名不副实**：它返回 `lastActivityTimestamp`（VillageDoorInfo.java:96-99），`Village.addVillageDoorInfo` 里 `this.lastAddDoorTimestamp = doorInfo.getInsidePosY()`（Village.java:277）读的其实是时间戳。MCP 命名陷阱，勿按字面义使用。
- **`VillageDoorInfo.func_179850_c` 存在坐标轴混用**：`int j = pos.getZ() - this.doorBlockPos.getY()`（VillageDoorInfo.java:57）用了 Y 而非 Z——这是原版自带 bug，移植保持原样。
- **Snooper 隐私/网络**：`PlayerUsageSnooper` 往明文 `http://snoop.minecraft.net` POST 机器信息（PlayerUsageSnooper.java:39），端点早已失效；Timer 是守护线程但 `stopSnooper()` 只在客户端关闭路径被调用。想彻底禁用应让 `IPlayerUsage.isSnooperEnabled()` 返回 false。
- **Profiler 阈值告警**：任何 section 超过 100ms 打 warn（Profiler.java:73），调试期日志刷屏多半来自这里；`getProfilingData` 每次调用衰减历史数据（×999/1000），不是纯读操作。
- **JDK25 移植相关**：`CrashReport.populateEnvironment` 与 `PlayerUsageSnooper.addJvmArgsToSnooper` 使用 `java.lang.management.ManagementFactory`——需要 `java.management` 模块可用；`StatisticsFile.parseJson` 用 `new JsonParser().parse(...)`（StatisticsFile.java:113），在新版 gson 中是 deprecated 但仍可用；`Achievement`/`StatisticsFile` 的反射构造（`getConstructor().newInstance`）在强封装下仅访问本工程类，无 `--add-opens` 需求。本桶完全不触碰 LWJGL，窗口/GL 移植对它无影响。
- **CrashReport 的 "Minecraft Version" 硬编码 "1.8.9"**（CrashReport.java:60），改版本号需要同步改 `PlayerUsageSnooper.addOSData` 的 `"version", "1.8.9"`（PlayerUsageSnooper.java:99）。

## 交叉引用

- net.minecraft.client → `Minecraft#startGame` 调 `Bootstrap#register`（client/Minecraft.java:397）；`Minecraft#displayCrashReport` 调 `CrashReport#getFile`/`getCompleteReport` 与 `Bootstrap#printToSYSOUT`（client/Minecraft.java:755-763）；`Minecraft.mcProfiler` 为 `Profiler` 实例（client/Minecraft.java:314）；`Minecraft.usageSnooper`（client/Minecraft.java:226）实现 `IPlayerUsage`。
- net.minecraft.client.entity → `EntityPlayerSP#getStatFileWriter`（client/entity/EntityPlayerSP.java:427）持有 `StatFileWriter`。
- net.minecraft.client.gui → `GuiIngame#renderScoreboard` 调 `Scoreboard#getSortedScores`/`ScorePlayerTeam#formatPlayerName`（client/gui/GuiIngame.java:551+）；`GuiScreen#handleComponentClick` 消费 `ClickEvent#getAction`（client/gui/GuiScreen.java:392）；`GuiContainerCreative` 调 `CreativeTabs#displayAllReleventItems`（client/gui/inventory/GuiContainerCreative.java:463）。
- net.minecraft.client.network → `NetHandlerPlayClient#handleScoreboardObjective` 等调 `Scoreboard#addScoreObjective`/`removeObjective`（client/network/NetHandlerPlayClient.java:1869）；成就 toast 判定读 `StatFileWriter#readStat`（client/network/NetHandlerPlayClient.java:1470）。
- net.minecraft.entity → `EntityLivingBase#updatePotionEffects` 调 `PotionEffect#onUpdate` 与 `PotionHelper#calcPotionLiquidColor`（entity/EntityLivingBase.java:618/686）；`EntityLiving#onLivingUpdate` 调 `PathNavigate#onUpdateNavigation`（entity/EntityLiving.java:635）；`EntityVillager`/`NpcMerchant`/`IMerchant` 使用 `MerchantRecipeList`。
- net.minecraft.world → `WorldServer#tick` 调 `VillageCollection#tick` 与 `VillageSiege#tick`（world/WorldServer.java:218-219）；`World#getVillageCollection`（world/World.java:3846）；`VillageCollection` extends `world.WorldSavedData`。
- net.minecraft.server → `MinecraftServer` 持有 `PlayerUsageSnooper`（server/MinecraftServer.java:90）并实现 `IPlayerUsage`；`Village` 读写声望经 `MinecraftServer#getPlayerProfileCache`（village/Village.java:481）。
- net.minecraft.block → `Bootstrap#registerDispenserBehaviors` 写 `BlockDispenser.dispenseBehaviorRegistry`；`BlockDispenser#getFacing`/`getDispensePosition` 被 dispenser 行为类调用（BehaviorDefaultDispenseItem.java:27-28）。
- net.minecraft.item → `ItemPotion` 调 `PotionHelper#getPotionEffects`/`getLiquidColor`/`getPotionPrefix`（item/ItemPotion.java:66/181/236）；`StatList` 遍历 `Item.itemRegistry`、`CraftingManager#getRecipeList`、`FurnaceRecipes#instance`。
- net.minecraft.network → `ServerScoreboard` 构造 `S3BPacketScoreboardObjective`/`S3CPacketUpdateScore`/`S3DPacketDisplayScoreboard`/`S3EPacketTeams`；`StatisticsFile` 构造 `S37PacketStatistics`；`MerchantRecipeList#writeToBuf/readFromBuf` 依赖 `network.PacketBuffer`。
- net.minecraft.nbt → `PotionEffect`/`MerchantRecipe`/`ScoreboardSaveData`/`Village` 的 NBT 读写；`Bootstrap` 的 skull 行为用 `NBTUtil#readGameProfileFromNBT`。
- net.minecraft.util → `CrashReport` 用 `util.ReportedException`；`Bootstrap` 用 `util.LoggingPrintStream`（util/LoggingPrintStream.java:8）；`StatBase` 用 `util.TupleIntJsonSerializable`/`IJsonSerializable`；`PotionHelper` 用 `util.IntegerCache`。
- net.minecraft.world.gen.layer → `CrashReport#populateEnvironment` 调 `IntCache#getCacheSizes`（crash/CrashReport.java:127）。
- net.minecraft.world.pathfinder → `PathNavigateGround`/`PathNavigateSwimmer` 分别实例化 `WalkNodeProcessor`/`SwimNodeProcessor`；`PathFinder` 依赖 `NodeProcessor#findPathOptions`。

## 覆盖声明

- 完整读取了 **62/62** 个文件（每个文件从第 1 行读到最后一行）。
- 逐行精读：Bootstrap、Blocks、Items、CrashReport、CrashReportCategory、Profiler、PlayerUsageSnooper、Potion、PotionEffect、PotionHelper、PathNavigate、PathNavigateGround、PathFinder、Path、PathPoint、Scoreboard、ServerScoreboard、ScoreboardSaveData、ScorePlayerTeam、Score、StatBase、StatList、StatisticsFile、StatFileWriter、Achievement、AchievementList、Village、VillageCollection、VillageSiege、VillageDoorInfo、MerchantRecipe、MerchantRecipeList、CreativeTabs、ClickEvent、HoverEvent、BehaviorDefaultDispenseItem、BehaviorProjectileDispense。
- 结构性浏览（文件极小或纯样板，已全文读过但未逐行推敲语义细节）：IBehaviorDispenseItem、IBlockSource、ILocatableSource、ILocation、IPosition、PositionImpl、PotionAbsorption、PotionAttackDamage、PotionHealth、PotionHealthBoost、IPlayerUsage、GoalColor、IScoreObjectiveCriteria、ScoreDummyCriteria、ScoreHealthCriteria、ScoreObjective、Team、IStatStringFormat、IStatType、ObjectiveStat、StatBasic、StatCrafting、PathEntity、PathNavigateClimber、PathNavigateSwimmer。
- 交叉引用行号（Minecraft.java、WorldServer.java、EntityLivingBase.java、EntityLiving.java、GuiIngame.java、GuiScreen.java、GuiContainerCreative.java、NetHandlerPlayClient.java、ItemPotion.java、MinecraftServer.java、EntityPlayerSP.java、World.java、LoggingPrintStream.java）均经 grep 验证，未通读这些外部文件全文。
