---
area: net/minecraft/command
slug: mc-command
files: 49
lines: 6922
tier: C
---

# net/minecraft/command

## 定位

本包是 1.8.9 内置服务端（本仓库是客户端 + 集成服务端一体的移植）的**命令系统**：命令的接口定义（`ICommand` / `ICommandSender` / `ICommandManager`）、分发器（`CommandHandler` → `ServerCommandManager`）、参数解析工具箱（`CommandBase` 的一堆静态 parse 方法）、`@p/@a/@r/@e` 选择器（`PlayerSelector`）、命令结果写记分板的机制（`CommandResultStats`），以及三十多个具体的 vanilla 命令实现（`/give`、`/fill`、`/execute` 等）。

调用方向：

- **谁调用它**：`MinecraftServer` 构造时 `createNewCommandManager()` 创建 `ServerCommandManager`（MinecraftServer.java:214-217）；玩家聊天输入以 `/` 开头时由 `NetHandlerPlayServer.handleSlashCommand(String command)`（NetHandlerPlayServer.java:830-833）调用 `executeCommand`；Tab 补全由 `MinecraftServer.getTabCompletions(...)`（MinecraftServer.java:921-929）转发到 `commandManager.getTabCompletionOptions`；告示牌 clickEvent RUN_COMMAND 由 `TileEntitySign.executeCommand` 触发（TileEntitySign.java:219）；命令方块通过 `net.minecraft.command.server.CommandBlockLogic`（实现 `ICommandSender`，在子包 server 中，不属于本 bucket）。
- **它调用谁**：几乎所有服务端侧子系统——`MinecraftServer` 单例、`ServerConfigurationManager`（玩家查找）、`World`/`WorldServer`（方块、实体、时间、天气、边界）、`Scoreboard`、`Item.itemRegistry` / `Block.blockRegistry`、NBT（`JsonToNBT`）、封包发送（`playerNetServerHandler.sendPacket`）。
- **消失会坏什么**：单人游戏和局域网中所有 `/` 命令、聊天 Tab 补全、命令方块、告示牌命令、`@` 选择器解析（`ChatComponentProcessor` 也依赖 `PlayerSelector`）全部失效；`Entity`、`TileEntitySign`、`TileEntityCommandBlock`、`RConConsoleSource` 因引用 `CommandResultStats` / `ICommandSender` 无法编译。

注意：本仓库虽是客户端项目，但完整保留了集成服务端，所以这套"服务端命令系统"在单人模式下真实运行。连接远程服务器时，本包基本不参与（命令原样作为聊天发出，本地不执行）。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| CommandBase | 772 | implements ICommand | 所有命令的抽象基类：parseInt/parseDouble/坐标(~)解析、getPlayer/getEntity、Tab 补全工具、notifyOperators |
| CommandBlockData | 106 | extends CommandBase | /blockdata：把 JSON-NBT merge 进指定坐标的 TileEntity |
| CommandClearInventory | 113 | extends CommandBase | /clear：按 item/meta/数量/NBT 清空玩家背包 |
| CommandClone | 280 | extends CommandBase | /clone：区域复制方块（masked/filtered/move/force），含 TileEntity NBT 与 scheduled tick 搬迁 |
| CommandCompare | 155 | extends CommandBase | /testforblocks：逐块比较两个区域（含 TileEntity NBT），上限 524288 块 |
| CommandDebug | 192 | extends CommandBase | /debug start\|stop：开关服务端 Profiler 并把结果写入 debug/profile-results-*.txt |
| CommandDefaultGameMode | 57 | extends CommandGameMode | /defaultgamemode：改服务器默认游戏模式，forceGamemode 时同步所有在线玩家 |
| CommandDifficulty | 61 | extends CommandBase | /difficulty：setDifficultyForAllWorlds |
| CommandEffect | 161 | extends CommandBase | /effect：给 EntityLivingBase 加/清药水效果 |
| CommandEnchant | 142 | extends CommandBase | /enchant：给手持物品加附魔，校验冲突（canApplyTogether） |
| CommandEntityData | 92 | extends CommandBase | /entitydata：把 JSON-NBT merge 进非玩家实体（剥离 UUIDMost/UUIDLeast） |
| CommandException | 17 | extends Exception | 命令错误基类，message 是翻译 key，errorObjects 是格式化参数 |
| CommandExecuteAt | 151 | extends CommandBase | /execute：以目标实体身份/位置构造匿名 ICommandSender 递归执行子命令，支持 detect |
| CommandFill | 230 | extends CommandBase | /fill：区域填充（replace/destroy/keep/hollow/outline），上限 32768 块 |
| CommandGameMode | 99 | extends CommandBase | /gamemode：改单个玩家游戏模式 |
| CommandGameRule | 117 | extends CommandBase | /gamerule：查/设 GameRules，reducedDebugInfo 变更时广播 S19PacketEntityStatus |
| CommandGive | 122 | extends CommandBase | /give：构造 ItemStack（可带 NBT）塞进玩家背包，装不下的丢地上 |
| CommandHandler | 233 | implements ICommandManager | 命令注册表 + 分发器：切词、权限检查、username 参数选择器展开、异常转红字 |
| CommandHelp | 124 | extends CommandBase | /help：分页列出可用命令（每页 7 条），带 SUGGEST_COMMAND 点击事件 |
| CommandKill | 66 | extends CommandBase | /kill：对自己或目标实体调 onKillCommand() |
| CommandNotFoundException | 14 | extends CommandException | "commands.generic.notFound" |
| CommandParticle | 133 | extends CommandBase | /particle：WorldServer.spawnParticle 广播粒子 |
| CommandPlaySound | 135 | extends CommandBase | /playsound：向目标玩家发 S29PacketSoundEffect，超距按 minVolume 拉近播放 |
| CommandReplaceItem | 253 | extends CommandBase | /replaceitem：按 slot.* 快捷名替换方块容器或实体背包槽位 |
| CommandResultStats | 277 | （无） | 命令结果(SuccessCount 等 5 类)→记分板 objective 的映射，含 NBT 读写 |
| CommandServerKick | 79 | extends CommandBase | /kick：kickPlayerFromServer（仅专用服注册） |
| CommandSetPlayerTimeout | 47 | extends CommandBase | /setidletimeout：MinecraftServer.setPlayerIdleTimeout |
| CommandSetSpawnpoint | 68 | extends CommandBase | /spawnpoint：设置玩家重生点 |
| CommandShowSeed | 50 | extends CommandBase | /seed：显示世界种子（单人模式放开权限） |
| CommandSpreadPlayers | 402 | extends CommandBase | /spreadplayers：迭代松弛算法（最多 10000 轮）把玩家/队伍散布到区域内安全落点 |
| CommandStats | 224 | extends CommandBase | /stats：配置实体或命令方块/告示牌的 CommandResultStats |
| CommandTime | 121 | extends CommandBase | /time set/add/query：遍历所有 worldServers 设置时间 |
| CommandTitle | 137 | extends CommandBase | /title：向玩家发 S45PacketTitle（JSON 文本经 ChatComponentProcessor 处理） |
| CommandToggleDownfall | 49 | extends CommandBase | /toggledownfall：翻转 worldServers[0] 的 isRaining |
| CommandTrigger | 142 | extends CommandBase | /trigger：玩家自助修改 TRIGGER 类型记分板项并上锁 |
| CommandWeather | 96 | extends CommandBase | /weather clear/rain/thunder：写 WorldInfo 天气字段 |
| CommandWorldBorder | 209 | extends CommandBase | /worldborder set/add/center/damage/warning/get：操作 worldServers[0].getWorldBorder() |
| CommandXP | 110 | extends CommandBase | /xp：加经验值或等级（后缀 L 表示等级，负数仅限等级） |
| EntityNotFoundException | 14 | extends CommandException | "commands.generic.entity.notFound" |
| IAdminCommand | 9 | interface | notifyOperators 回调接口，由 ServerCommandManager 实现 |
| ICommand | 36 | interface extends Comparable&lt;ICommand&gt; | 命令契约：name/usage/aliases/processCommand/权限/Tab 补全/isUsernameIndex |
| ICommandManager | 25 | interface | executeCommand / getTabCompletionOptions / getPossibleCommands / getCommands |
| ICommandSender | 60 | interface | 命令来源抽象：名字、聊天回显、权限、位置、世界、关联实体、setCommandStat |
| NumberInvalidException | 14 | extends CommandException | "commands.generic.num.invalid" |
| PlayerNotFoundException | 14 | extends CommandException | "commands.generic.player.notFound" |
| PlayerSelector | 729 | （无，纯静态） | `@[pare][args]` 选择器解析：正则拆参 → Predicate 链过滤 → 排序/截断 |
| ServerCommandManager | 162 | extends CommandHandler implements IAdminCommand | 构造时注册全部 vanilla 命令（专用服/单人分支不同），实现 notifyOperators 广播 |
| SyntaxErrorException | 14 | extends CommandException | "commands.generic.snytax"（vanilla 原版拼写错误，保留） |
| WrongUsageException | 9 | extends SyntaxErrorException | 用法错误，CommandHandler 捕获后套 "commands.generic.usage" 显示 |

## 核心类详解

### CommandHandler（分发器）

字段（CommandHandler.java:19-21）：
- `private static final Logger logger`
- `private final Map<String, ICommand> commandMap`（名字/别名 → 命令）
- `private final Set<ICommand> commandSet`（去重集合，供 /help 遍历）

关键方法：
- `public int executeCommand(ICommandSender sender, String rawCommand)`（CommandHandler.java:32）。流程：trim → 去掉前导 `/` → `split(" ")` → 查 commandMap → 无则红字 "commands.generic.notFound"；有则 `canCommandSenderUseCommand` 权限检查；若 `getUsernameIndex` 找到匹配多目标的选择器参数（CommandHandler.java:214），先 `PlayerSelector.matchEntities` 展开，**对每个实体把该参数替换成其 UUID 字符串后各执行一次**（CommandHandler.java:58-72）；返回成功执行次数并写 `SUCCESS_COUNT` stat（CommandHandler.java:91）。
- `protected boolean tryExecute(ICommandSender sender, String[] args, ICommand command, String input)`（CommandHandler.java:95）：调 `command.processCommand`，把 `WrongUsageException` / `CommandException` 转成红色 ChatComponentTranslation 回显；兜底 catch `Throwable` 打日志（CommandHandler.java:114-120）——命令抛任何异常都不会炸服务端 tick。
- `public ICommand registerCommand(ICommand command)`（CommandHandler.java:128）：注册主名和别名；别名不覆盖已有的"主名注册"（CommandHandler.java:135-140）。
- `public List<String> getTabCompletionOptions(ICommandSender sender, String input, BlockPos pos)`（CommandHandler.java:156）：单词时按前缀匹配命令名（且做权限过滤），多词时委托给命令自身的 `addTabCompletionOptions`。

### ServerCommandManager（注册表 + 管理员广播）

- 构造器（ServerCommandManager.java:39-109）注册 43 个通用命令；`MinecraftServer.getServer().isDedicatedServer()` 分支再注册 op/ban/save 等 15 个专用服命令，否则只注册 `CommandPublishLocalServer`（ServerCommandManager.java:85-106）。末尾 `CommandBase.setAdminCommander(this)`（ServerCommandManager.java:108）把自己挂到 CommandBase 的静态字段上。
- `public void notifyOperators(ICommandSender sender, ICommand command, int flags, String msgFormat, Object... msgParams)`（ServerCommandManager.java:114）：向所有有 canSendCommands 权限的在线玩家广播灰色斜体 "chat.type.admin" 消息；受 gamerule `logAdminCommands` / `sendCommandFeedback`、`shouldBroadcastConsoleToOps`、命令方块 `shouldTrackOutput()` 控制（ServerCommandManager.java:145-160）。`flags & 1` 置位时不向 sender 本人回显（ServerCommandManager.java:157）。

### CommandBase（工具箱基类）

字段：`private static IAdminCommand theAdmin`（CommandBase.java:24），经 `setAdminCommander` 注入。

关键方法（全部逐字来自源码）：
- `public int getRequiredPermissionLevel()`（CommandBase.java:29）默认返回 4，子类逐个覆写（多数为 2）。
- `public boolean canCommandSenderUseCommand(ICommandSender sender)`（CommandBase.java:42）→ 委托 `sender.canCommandSenderUseCommand(this.getRequiredPermissionLevel(), this.getCommandName())`。
- `public static int parseInt(String input, int min, int max) throws NumberInvalidException`（CommandBase.java:69）
- `public static double parseDouble(double base, String input, int min, int max, boolean centerBlock) throws NumberInvalidException`（CommandBase.java:447）：处理 `~` 相对坐标；整数字面量且 centerBlock 时 +0.5（CommandBase.java:470-473）。
- `public static BlockPos parseBlockPos(ICommandSender sender, String[] args, int startIndex, boolean centerBlock) throws NumberInvalidException`（CommandBase.java:117）：y 范围硬编码 0..256，x/z ±30000000。
- `public static EntityPlayerMP getPlayer(ICommandSender sender, String username) throws PlayerNotFoundException`（CommandBase.java:201）：先选择器、再 UUID、再用户名三段回退。
- `public static <T extends Entity> T getEntity(ICommandSender commandSender, String p_175759_1_, Class <? extends T > p_175759_2_) throws EntityNotFoundException`（CommandBase.java:237）
- `public static Item getItemByText(ICommandSender sender, String id) throws NumberInvalidException`（CommandBase.java:498）/ `getBlockByText`（CommandBase.java:518）：**只查 registry，不再支持数字 ID**（javadoc 提到的整数 ID 分支在本源码中不存在）。
- `public static List<String> getListOfStringsMatchingLastWord(String[] args, String... possibilities)`（CommandBase.java:675）：Tab 补全的前缀匹配核心。
- `public static void notifyOperators(ICommandSender sender, ICommand command, int p_152374_2_, String msgFormat, Object... msgParams)`（CommandBase.java:723）：转发到静态 `theAdmin`，null 时静默不发。
- 内部类 `CommandBase.CoordinateArg`（CommandBase.java:744）：`func_179628_a()` 绝对值、`func_179629_b()` 相对增量、`func_179630_c()` 是否 `~`。

### PlayerSelector（@选择器）

三个正则（PlayerSelector.java:42-52）：
- `tokenPattern = Pattern.compile("^@([pare])(?:\\[([\\w=,!-]*)\\])?$")`
- `intListPattern = Pattern.compile("\\G([-!]?[\\w-]*)(?:$|,)")`（位置参数 x,y,z,r）
- `keyValueListPattern = Pattern.compile("\\G(\\w+)=([-!]?[\\w-]*)(?:$|,)")`

`WORLD_BINDING_ARGS = Sets.newHashSet(new String[] {"x", "y", "z", "dx", "dy", "dz", "rm", "r"})`（PlayerSelector.java:53）——带这些参数时只搜 sender 所在世界，否则遍历 `MinecraftServer.getServer().worldServers`（PlayerSelector.java:135-149）。

- `public static <T extends Entity> List<T> matchEntities(ICommandSender sender, String token, Class <? extends T > targetClass)`（PlayerSelector.java:90）：入口。**要求 `sender.canCommandSenderUseCommand(1, "@")`**（PlayerSelector.java:94），否则返回空表。按 type/lm‑l/m/team/score_*/name/rm‑r/rx‑ry 构造 `Predicate<Entity>` 链（PlayerSelector.java:113-121），`filterResults` 选择 AABB 或全表扫描（PlayerSelector.java:446），`func_179658_a` 按 `c` 参数排序（@p 按距离、@r 洗牌）并截断（PlayerSelector.java:531-571）。
- `public static boolean matchesMultiplePlayers(String p_82377_0_)`（PlayerSelector.java:650）：`CommandHandler.executeCommand` 用它决定是否展开 username 参数。
- `public static boolean hasArguments(String p_82378_0_)`（PlayerSelector.java:670）：仅判断 token 是否形如选择器。
- `public static IChatComponent matchEntitiesToChatComponent(ICommandSender sender, String token)`（PlayerSelector.java:69）：被 `getChatComponentFromNthArg`（CommandBase.java:349）和 `ChatComponentProcessor` 使用。

### CommandResultStats（结果→记分板）

字段：`private String[] entitiesID; private String[] objectives;`（CommandResultStats.java:22-25），空态共享静态 `STRING_RESULT_TYPES` 数组以省内存（CommandResultStats.java:16-17）。

- `public void setCommandStatScore(final ICommandSender sender, CommandResultStats.Type resultTypeIn, int scorePoint)`（CommandResultStats.java:38）：命令执行后由 sender 侧（Entity/CommandBlockLogic）调用，把结果写到配置好的 scoreboard objective；内部用匿名 ICommandSender 包装以绕过权限（`canCommandSenderUseCommand` 恒 true，CommandResultStats.java:58-61）。
- `public void readStatsFromNBT(NBTTagCompound tagcompound)`（CommandResultStats.java:117）/ `public void writeStatsToNBT(NBTTagCompound tagcompound)`（CommandResultStats.java:138）：NBT 格式见"数据与协议"。
- `public static void setScoreBoardStat(CommandResultStats stats, CommandResultStats.Type resultType, String entityID, String objectiveName)`（CommandResultStats.java:166）：`/stats` 命令的写入口；传 null/空串等价删除。
- 枚举 `Type`：`SUCCESS_COUNT(0, "SuccessCount")`, `AFFECTED_BLOCKS(1, "AffectedBlocks")`, `AFFECTED_ENTITIES(2, "AffectedEntities")`, `AFFECTED_ITEMS(3, "AffectedItems")`, `QUERY_RESULT(4, "QueryResult")`（CommandResultStats.java:226-230）。

### CommandExecuteAt（/execute，命令组合器）

- `public void processCommand(final ICommandSender sender, String[] args) throws CommandException`（CommandExecuteAt.java:42）：解析目标实体和坐标后构造**匿名 ICommandSender**（CommandExecuteAt.java:77-120）——name/position/world/entity 来自目标实体，chat 回显和权限仍走原 sender，`sendCommandFeedback()` 读 gamerule `commandBlockOutput`；然后 `MinecraftServer.getServer().getCommandManager().executeCommand(icommandsender, s)` 递归分发（CommandExecuteAt.java:121-125）。`detect` 子句先校验指定坐标方块与 meta（CommandExecuteAt.java:57-74）。想拦截所有命令执行只挂 `CommandHandler.executeCommand` 即可，因为 /execute 也走它。

## 时序与生命周期

- **初始化**：`MinecraftServer` 构造（MinecraftServer.java:207 `this.commandManager = this.createNewCommandManager()`）→ `new ServerCommandManager()` → 注册全部命令 → `CommandBase.setAdminCommander(this)`。每次启动集成服务端（进入单人世界）都会新建一套；退出世界后随 server 丢弃。`CommandReplaceItem.SHORTCUTS` 在类静态块中一次性填充（CommandReplaceItem.java:212-252）。
- **每 tick / 每帧**：无。本包完全是请求驱动——只有命令被执行时才有代码运行。命令方块每 tick 触发属于 `CommandBlockLogic`（子包 server），不在本 bucket。
- **线程归属**：全部在**服务端线程**执行。玩家聊天包 `C01PacketChatMessage` 经 `PacketThreadUtil.checkThreadAndEnqueue` 回到服务端线程后才进入 `handleSlashCommand`；Tab 补全请求同理。Netty EventLoop 与客户端渲染线程不会直接触碰本包。`CommandHandler.commandMap` 为普通 HashMap，无同步。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public int executeCommand(ICommandSender sender, String rawCommand)` | CommandHandler.java:32 | 每次任何来源（玩家 `/`、命令方块、告示牌、rcon、/execute 递归）执行命令 | 全局命令拦截/改写/审计；注入客户端自定义命令的最佳位置（覆写或包装 ICommandManager） | /execute 会递归进入，注意别双重记录；仅单人/集成服务端生效，连远程服务器不走这里 |
| `protected boolean tryExecute(ICommandSender sender, String[] args, ICommand command, String input)` | CommandHandler.java:95 | executeCommand 权限检查通过后逐目标调用 | 观察单条命令成功/失败；统一错误上报 | 兜底 catch Throwable：在此抛异常只会变成红字，不会向上传播 |
| `public ICommand registerCommand(ICommand command)` | CommandHandler.java:128 | ServerCommandManager 构造时批量调用；随时可再调 | 运行时注册自定义命令 / 覆盖 vanilla 命令（同名 put 直接顶替） | 每次进世界 server 重建，需重新注册；别名不覆盖主名注册 |
| `public List<String> getTabCompletionOptions(ICommandSender sender, String input, BlockPos pos)` | CommandHandler.java:156 | 客户端发 C14PacketTabComplete → `MinecraftServer.getTabCompletions`（MinecraftServer.java:921） | 增删补全项、为自定义命令提供补全 | 返回 null 表示无补全；单词分支已做权限过滤，别绕过 |
| `public static <T extends Entity> List<T> matchEntities(ICommandSender sender, String token, Class <? extends T > targetClass)` | PlayerSelector.java:90 | 所有 `@` 选择器解析（命令参数、tellraw selector 组件） | 扩展选择器语法 / 观察目标解析结果 | 静态方法，需字节码级替换或改源码；内含 `canCommandSenderUseCommand(1, "@")` 权限门 |
| `public static void notifyOperators(ICommandSender sender, ICommand command, int p_152374_2_, String msgFormat, Object... msgParams)` | CommandBase.java:723 | 几乎每条命令成功后 | 统一捕获"命令成功"事件（含参数）做日志/HUD 提示 | theAdmin 为 null 时静默；flags&1 语义见 ServerCommandManager.java:157 |
| `public static void setAdminCommander(IAdminCommand command)` | CommandBase.java:734 | ServerCommandManager 构造末尾 | 替换成自己的 IAdminCommand 以接管全部管理员广播 | 静态全局，进新世界会被 ServerCommandManager 重新覆盖，需在其后再挂 |
| `void processCommand(ICommandSender sender, String[] args) throws CommandException` | ICommand.java:23 | tryExecute 逐命令调用 | 实现该接口即成为新命令；包装既有实例可做单命令代理 | 抛 CommandException 系列即报错红字，语义友好；其它异常被吞成 "commands.generic.exception" |
| `public void notifyOperators(ICommandSender sender, ICommand command, int flags, String msgFormat, Object... msgParams)` | ServerCommandManager.java:114 | CommandBase.notifyOperators 转发到此 | 精确控制广播范围（op 列表、gamerule 过滤逻辑） | 逻辑依赖 worldServers[0] 的 gamerule，world 未加载时会 NPE |

## 数据与协议

**CommandStats NBT**（`CommandResultStats.readStatsFromNBT` / `writeStatsToNBT`，CommandResultStats.java:117-158；存在于实体、命令方块、告示牌的 NBT 中）：

| 字段名 | 类型 | 读写方法 | 取值含义 |
|---|---|---|---|
| `CommandStats` | NBTTagCompound (id 10) | `tagcompound.getCompoundTag("CommandStats")` / `setTag` | 容器，全空时不写出（CommandResultStats.java:154-157） |
| `CommandStats.<TypeName>Name` | String (id 8) | `getString` / `setString` | 记分板目标实体：玩家名或选择器/UUID 字符串；TypeName ∈ SuccessCount / AffectedBlocks / AffectedEntities / AffectedItems / QueryResult |
| `CommandStats.<TypeName>Objective` | String (id 8) | `getString` / `setString` | 记分板 objective 名；Name 与 Objective 必须成对出现才生效（CommandResultStats.java:128） |

**间接发送的封包**（本包不定义封包，但直接构造发送）：

| 封包 | 发送处 | 用途 |
|---|---|---|
| `S19PacketEntityStatus` (opcode 22/23) | CommandGameRule.java:79-84 | gamerule `reducedDebugInfo` 变更广播给所有玩家 |
| `S29PacketSoundEffect` | CommandPlaySound.java:118 | /playsound 定向播放 |
| `S45PacketTitle` | CommandTitle.java:84/108/119 | /title 的 TIMES / 文本 / CLEAR‑RESET 三种形态 |

**选择器语法**（PlayerSelector 解析的键）：位置序参数 `x,y,z,r`；命名参数 `type`(可 `!` 取反)、`lm`/`l`(经验等级下/上限)、`m`(gamemode id)、`team`(可 `!`)、`score_<obj>`/`score_<obj>_min`、`name`(可 `!`)、`rm`/`r`(距离环)、`rym`/`ry`/`rxm`/`rx`(yaw/pitch 区间，角度归一化见 `func_179650_a`，PlayerSelector.java:587)、`dx`/`dy`/`dz`(体积)、`c`(数量，负数取最远)。

**/replaceitem 槽位表**（CommandReplaceItem.java:212-252 静态块）：`slot.container.0-53`→0-53、`slot.hotbar.0-8`→0-8、`slot.inventory.0-26`→9-35、`slot.enderchest.0-26`→200-226、`slot.villager.0-7`→300-307、`slot.horse.0-14`→500-514、`slot.weapon`→99、`slot.armor.head/chest/legs/feet`→103/102/101/100、`slot.horse.saddle/armor/chest`→400/401/499。

## 不变量与陷阱

- **`MinecraftServer.getServer()` 单例依赖遍布全包**（CommandBase、PlayerSelector、几乎每个命令）。集成服务端未运行时调用任何命令逻辑都会 NPE——不要在纯客户端上下文（如多人服务器聊天）里复用这些类。
- **权限模型**：`CommandBase.getRequiredPermissionLevel()` 默认 4，子类必须覆写否则普通玩家用不了；选择器另有独立的 `canCommandSenderUseCommand(1, "@")` 门槛（PlayerSelector.java:94）。
- **CommandHandler 的选择器展开只处理第一个 `isUsernameIndex` 且 `matchesMultiplePlayers` 的参数**（CommandHandler.java:214-231），展开时参数被临时替换为 UUID 字符串再还原（CommandHandler.java:64-72）——自定义命令实现 `isUsernameIndex` 时要能接受 UUID 形参。
- **异常即控制流**：命令用 `CommandException` 子类报错，message 是 i18n key 不是人话；`tryExecute` 兜底 catch Throwable（CommandHandler.java:114），任何真 bug 都只显示 "commands.generic.exception" + 一行 warn 日志，排查时要看日志不是聊天框。
- **`SyntaxErrorException` 默认 key 是 `"commands.generic.snytax"`**（SyntaxErrorException.java:7）——vanilla 原版拼写错误，语言文件同样拼错，不要"修复"。
- **`getItemByText`/`getBlockByText` 的 javadoc 撒谎**：注释说会回退解析数字 ID，实际代码只查 registry（CommandBase.java:498-539）。
- `CommandResultStats` 的空态用共享静态数组 `STRING_RESULT_TYPES` 做哨兵（`==` 比较，CommandResultStats.java:170/190），克隆或反射改这两个数组会破坏所有实例。
- 世界修改类命令的硬上限：/fill 32768（CommandFill.java:68）、/clone 32768（CommandClone.java:62）、/testforblocks 524288（CommandCompare.java:57）；y 范围硬编码 0..256。/spreadplayers 松弛迭代上限 10000 轮（CommandSpreadPlayers.java:151），失败抛 CommandException。
- **线程安全**：`commandMap`/`commandSet` 无同步，只能在服务端线程读写；`CommandBase.theAdmin` 是可变静态字段，进出世界时被重写。
- **LWJGL3/JDK25 移植注意**：本包无渲染/输入/native 依赖，移植零改动；但 `CommandDebug.getWittyComment` 用 `System.nanoTime()` 取模（CommandDebug.java:180），`CommandBase` 用 Guava `Doubles.isFinite`/`Functions.toStringFunction()`——升级 Guava 时留意后者的弃用状态。`ServerCommandManager` 反编译痕迹：`CommandServerKick`、`CommandSetPlayerTimeout`、`CommandShowSeed` 等在专用服分支引用但类在本包，单人分支仍可实例化（/seed 单人放开权限，CommandShowSeed.java:13-16）。
- `CommandWorldBorder`/`CommandWeather`/`CommandToggleDownfall`/`CommandGameRule` 都只操作 `worldServers[0]`（主世界），其他维度共享同一 WorldBorder/WorldInfo 语义由 world 包保证——改命令行为前先确认那边的共享机制。

## 交叉引用

- net/minecraft/server → `MinecraftServer#createNewCommandManager`（MinecraftServer.java:214）、`MinecraftServer#getCommandManager`（MinecraftServer.java:1002）、`MinecraftServer#getTabCompletions`（MinecraftServer.java:921）
- net/minecraft/network → `NetHandlerPlayServer#handleSlashCommand`（NetHandlerPlayServer.java:830，命令入口）
- net/minecraft/network/rcon → `RConConsoleSource`（implements ICommandSender，ServerCommandManager 广播分支特判）
- net/minecraft/command/server（子包，不在本 bucket）→ `CommandBlockLogic`（implements ICommandSender）、`CommandSetBlock` 等 27 个类由 `ServerCommandManager` 构造器注册
- net/minecraft/tileentity → `TileEntitySign#executeCommand`（TileEntitySign.java:219，clickEvent 执行命令）、`TileEntityCommandBlock#getCommandResultStats`（CommandStats.java:144 使用）
- net/minecraft/entity → `Entity#getCommandStats` / `Entity#setCommandStat` / `Entity#onKillCommand`；`EntityPlayerMP#playerNetServerHandler.sendPacket`（playsound/title/kick）
- net/minecraft/util → `ChatComponentProcessor#processComponent`（CommandTitle.java:107；反向依赖 `PlayerSelector`）、`BlockPos`、`ChatComponentTranslation`
- net/minecraft/scoreboard → `Scoreboard#getObjective` / `Score#setScorePoints`（CommandResultStats、CommandTrigger、PlayerSelector score 谓词）
- net/minecraft/world → `WorldServer#spawnParticle`（CommandParticle.java:122）、`WorldInfo` 天气字段（CommandWeather.java:55-82）、`WorldBorder`（CommandWorldBorder.java:200-203）、`GameRules`（CommandGameRule.java:113-116）
- net/minecraft/item / net/minecraft/block → `Item.itemRegistry` / `Block.blockRegistry`（CommandBase.java:501/522，getItemByText/getBlockByText 与 Tab 补全）
- net/minecraft/nbt → `JsonToNBT#getTagFromJson`（blockdata/entitydata/give/fill/replaceitem/clear 的 NBT 参数）

## 覆盖声明

- 完整读取了 **49/49** 个文件（每个文件从第 1 行读到末尾）。
- 逐行精读：CommandBase、PlayerSelector、CommandHandler、ServerCommandManager、CommandResultStats、CommandExecuteAt、ICommand、ICommandSender、ICommandManager、IAdminCommand、全部 7 个异常类。
- 通读但按"入口 + 分支结构"消化（未逐行推演算法细节）：CommandClone、CommandSpreadPlayers（松弛算法内部数值行为未验证）、CommandFill、CommandCompare、CommandStats、CommandReplaceItem、CommandWorldBorder、CommandDebug 及其余单一职责命令类。
- 行号引用均来自本次 Read 输出；交叉引用行号经 grep + sed 复核（MinecraftServer.java、NetHandlerPlayServer.java、TileEntitySign.java）。
