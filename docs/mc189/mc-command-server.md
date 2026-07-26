---
area: net/minecraft/command/server
slug: mc-command-server
files: 27
lines: 3971
tier: C
---

# net/minecraft/command/server

## 定位

本包是"服务端侧"命令的实现集合(1.8.9 客户端 jar 同时内置集成服务端,所以这些类存在于客户端源码树中)。26 个 `Command*` 类全部继承 `net.minecraft.command.CommandBase`,由 `ServerCommandManager` 构造函数逐个 `registerCommand(...)` 注册(`net/minecraft/command/ServerCommandManager.java:41-107`);唯一的例外是 `CommandBlockLogic`——它不是命令,而是命令方块的执行器抽象基类,实现 `ICommandSender`,被 `TileEntityCommandBlock`(命令方块)和 `EntityMinecartCommandBlock`(命令方块矿车)以匿名内部类方式实例化。

注意注册是分环境的:`op/deop/stop/save-all/save-off/save-on/ban-ip/pardon-ip/ban/banlist/pardon/list/whitelist` 等仅在 `MinecraftServer.getServer().isDedicatedServer()` 为 true 时注册;单人/LAN 集成服务端反而只注册 `CommandPublishLocalServer`(`ServerCommandManager.java:85-107`)。

调用链:玩家聊天输入 `/xxx` → `NetHandlerPlayServer.processChatMessage` → `MinecraftServer.getCommandManager().executeCommand(sender, cmd)` → `CommandHandler` 分发 → 本包各类的 `processCommand`。命令方块则走 `CommandBlockLogic.trigger(World)` 直接调 `ICommandManager.executeCommand`。

如果本包消失:单人游戏里 `/tp`、`/summon`、`/setblock`、`/scoreboard`、`/tell`、`/say`、`/me`、`/publish` 等全部失效;命令方块彻底不工作(`BlockCommandBlock`、`TileEntityCommandBlock`、`GuiCommandBlock`、`NetHandlerPlayServer` 的 `MC|AdvCdm` payload 处理均直接引用 `CommandBlockLogic`,连编译都过不了)。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| CommandAchievement | 207 | extends CommandBase | `/achievement give\|take`,授予/移除成就(含父成就链递归处理) |
| CommandBanIp | 114 | extends CommandBase | `/ban-ip`,按 IPv4 正则或在线玩家名封禁 IP 并踢出匹配玩家 |
| CommandBanPlayer | 94 | extends CommandBase | `/ban`,按 GameProfile 封禁玩家并踢出 |
| CommandBlockLogic | 258 | abstract, implements ICommandSender | 命令方块执行器基类:存储命令、成功计数、输出,NBT 读写,触发执行 |
| CommandBroadcast | 59 | extends CommandBase | `/say`,向全服广播 `chat.type.announcement` 消息 |
| CommandDeOp | 68 | extends CommandBase | `/deop`,从 op 列表移除玩家 |
| CommandEmote | 60 | extends CommandBase | `/me`,广播第三人称动作消息 |
| CommandListBans | 67 | extends CommandBase | `/banlist [ips\|players]`,列出封禁条目 |
| CommandListPlayers | 47 | extends CommandBase | `/list [uuids]`,列出在线玩家并写 QUERY_RESULT 统计 |
| CommandMessage | 90 | extends CommandBase | `/tell`(别名 `w`/`msg`),私聊,双向灰色斜体显示 |
| CommandMessageRaw | 82 | extends CommandBase | `/tellraw`,把 JSON 文本反序列化为 IChatComponent 发给目标 |
| CommandOp | 87 | extends CommandBase | `/op`,把玩家加入 op 列表 |
| CommandPardonIp | 76 | extends CommandBase | `/pardon-ip`,解除 IP 封禁(复用 CommandBanIp 的 IPv4 正则) |
| CommandPardonPlayer | 76 | extends CommandBase | `/pardon`,解除玩家封禁 |
| CommandPublishLocalServer | 43 | extends CommandBase | `/publish`,把单人世界通过 `shareToLAN` 开放为局域网服务器 |
| CommandSaveAll | 84 | extends CommandBase | `/save-all [flush]`,保存玩家数据与全部世界区块 |
| CommandSaveOff | 58 | extends CommandBase | `/save-off`,置各 WorldServer 的 `disableLevelSaving = true` |
| CommandSaveOn | 58 | extends CommandBase | `/save-on`,恢复自动保存 |
| CommandScoreboard | 1354 | extends CommandBase | `/scoreboard objectives\|players\|teams`,记分板全功能入口(本包最大类) |
| CommandSetBlock | 156 | extends CommandBase | `/setblock`,放置方块,支持 metadata、destroy/keep 模式与 TileEntity NBT |
| CommandSetDefaultSpawnpoint | 68 | extends CommandBase | `/setworldspawn`,设世界出生点并向所有玩家广播 S05PacketSpawnPosition |
| CommandStop | 38 | extends CommandBase | `/stop`,调用 `MinecraftServer.initiateShutdown()` |
| CommandSummon | 156 | extends CommandBase | `/summon`,从 NBT 生成实体(含 LightningBolt 特例与 Riding 链) |
| CommandTeleport | 192 | extends CommandBase | `/tp`,实体间或坐标传送,玩家走 S08PacketPlayerPosLook 相对坐标标志位 |
| CommandTestFor | 95 | extends CommandBase | `/testfor`,按选择器 + 可选 NBT 匹配检测实体 |
| CommandTestForBlock | 149 | extends CommandBase | `/testforblock`,检测坐标处方块类型/meta/TileEntity NBT |
| CommandWhitelist | 135 | extends CommandBase | `/whitelist on\|off\|list\|add\|remove\|reload`,白名单管理 |

## 核心类详解

### CommandBlockLogic(`CommandBlockLogic.java`)

本包唯一的非命令类,也是唯一被客户端渲染/GUI 层直接引用的类。

关键字段(`CommandBlockLogic.java:23-37`):
- `private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss")`
- `private int successCount` — 红石比较器输出用的成功计数
- `private boolean trackOutput = true`
- `private IChatComponent lastOutput = null`
- `private String commandStored = ""`
- `private String customName = "@"`
- `private final CommandResultStats resultStats = new CommandResultStats()`

关键方法签名(逐字):
- `public void writeDataToNBT(NBTTagCompound tagCompound)` — `CommandBlockLogic.java:58`
- `public void readDataFromNBT(NBTTagCompound nbt)` — `CommandBlockLogic.java:76`
- `public boolean canCommandSenderUseCommand(int permLevel, String commandName)` — `CommandBlockLogic.java:102`,恒 `return permLevel <= 2;`,即命令方块权限等级为 2
- `public void setCommand(String command)` — `CommandBlockLogic.java:110`,同时把 `successCount` 清零
- `public void trigger(World worldIn)` — `CommandBlockLogic.java:124`,核心执行入口;`worldIn.isRemote` 时只清零计数,服务端侧检查 `minecraftserver.isAnvilFileSet() && minecraftserver.isCommandBlockEnabled()` 后调 `icommandmanager.executeCommand(this, this.commandStored)`,异常包成 `ReportedException` 崩溃报告
- `public void addChatMessage(IChatComponent component)` — `CommandBlockLogic.java:193`,trackOutput 且服务端时把带时间戳的输出写入 `lastOutput` 并调 `updateCommand()`
- `public boolean sendCommandFeedback()` — `CommandBlockLogic.java:205`,读 gamerule `commandBlockOutput`
- `public abstract void updateCommand();` / `public abstract int func_145751_f();` / `public abstract void func_145757_a(ByteBuf p_145757_1_);` — `CommandBlockLogic.java:216-220`;`func_145751_f` 返回类型标识(TileEntity 版返回 0,矿车版返回 1),`func_145757_a` 把定位信息写入 ByteBuf,供 `GuiCommandBlock` 组 `MC|AdvCdm` payload(`GuiCommandBlock.java:91-92`)
- `public boolean tryOpenEditCommandBlock(EntityPlayer playerIn)` — `CommandBlockLogic.java:237`,创造模式 + 客户端侧才调 `playerIn.openEditCommandBlock(this)`

调用方:`BlockCommandBlock` 红石触发时调 `trigger`(`BlockCommandBlock.java:66`),右键调 `tryOpenEditCommandBlock`(`BlockCommandBlock.java:82`),比较器读 `getSuccessCount()`(`BlockCommandBlock.java:93`);`EntityMinecartCommandBlock` 被激活铁轨触发时调 `trigger`(`EntityMinecartCommandBlock.java:112`);`NetHandlerPlayServer` 处理 `MC|AdvCdm` payload 时按 type byte 0/1 找回 logic 并 `setCommand`/`setTrackOutput`(`NetHandlerPlayServer.java:1362-1394`)。

### CommandScoreboard(`CommandScoreboard.java`)

1354 行,占本包三分之一。`processCommand`(`CommandScoreboard.java:63`)是三层 switch:`objectives`(list/add/remove/setdisplay)、`players`(list/add/remove/set/reset/enable/test/operation)、`teams`(list/add/remove/empty/join/leave/option)。

关键方法签名(逐字):
- `private boolean func_175780_b(ICommandSender p_175780_1_, String[] p_175780_2_) throws CommandException` — `CommandScoreboard.java:287`,处理 `*` 通配:把通配位替换成每个 objective 名后递归调 `this.processCommand`,只允许一个 `*`(否则 `commands.scoreboard.noMultiWildcard`)
- `protected Scoreboard getScoreboard()` — `CommandScoreboard.java:345`,恒取 `MinecraftServer.getServer().worldServerForDimension(0).getScoreboard()`,即记分板全局挂在主世界
- `protected ScoreObjective getObjective(String name, boolean edit) throws CommandException` — `CommandScoreboard.java:350`,`edit=true` 时拒绝 `isReadOnly()` 准则
- `protected void setPlayer(ICommandSender p_147197_1_, String[] p_147197_2_, int p_147197_3_) throws CommandException` — `CommandScoreboard.java:905`,set/add/remove 共用;带尾随 NBT 参数时用 `NBTUtil.func_181123_a` 做子集匹配校验
- `protected void func_175778_p(ICommandSender p_175778_1_, String[] p_175778_2_, int p_175778_3_) throws CommandException` — `CommandScoreboard.java:1041`,`players operation` 的 `+= -= *= /= %= = < > ><` 运算;`/=` 与 `%=` 在除数为 0 时静默跳过赋值

约束常量:objective/team 名 ≤ 16 字符、displayName ≤ 32、player 记分项名 ≤ 40(`CommandScoreboard.java:399/445/911`)。

### CommandTeleport(`CommandTeleport.java`)

`processCommand`(`CommandTeleport.java:46`)按参数个数区分"传送到实体"(1/2 参)与"传送到坐标"(3+ 参)。坐标模式用 `CommandBase.CoordinateArg`(`parseCoordinate`,支持 `~` 相对量);目标是 `EntityPlayerMP` 时不直接改坐标,而是构造 `EnumSet<S08PacketPlayerPosLook.EnumFlags>`(相对量对应 X/Y/Z/X_ROT/Y_ROT 标志)并调:

```java
((EntityPlayerMP)entity).playerNetServerHandler.setPlayerLocation(commandbase$coordinatearg.func_179629_b(), commandbase$coordinatearg1.func_179629_b(), commandbase$coordinatearg2.func_179629_b(), f, f1, set);
```

(`CommandTeleport.java:132`)。非玩家实体走 `entity.setLocationAndAngles(...)`(`CommandTeleport.java:146`)。俯仰角超 ±90° 时做翻转归一(`CommandTeleport.java:125-129`)。传送前恒 `entity.mountEntity((Entity)null)` 强制下坐骑(`CommandTeleport.java:131/163`)。跨维度传送直接抛 `commands.tp.notSameDimension`(`CommandTeleport.java:159`)。

### CommandSummon(`CommandSummon.java`)

`processCommand`(`CommandSummon.java:50`):默认在 sender 位置生成,4+ 参数时 `parseDouble` 解析相对坐标。`"LightningBolt"` 走 `world.addWeatherEffect(new EntityLightningBolt(world, d0, d1, d2))` 特例(`CommandSummon.java:79-83`);其余把实体名写入 NBT 的 `"id"` 键后 `EntityList.createEntityFromNBT(nbttagcompound, world)`(`CommandSummon.java:109`)。未提供 NBT 且是 `EntityLiving` 时调 `onInitialSpawn` 走自然生成初始化(`CommandSummon.java:124-127`)。随后循环处理 NBT 中的 `"Riding"` 复合标签链,逐个生成并 `entity.mountEntity(entity1)`(`CommandSummon.java:132-144`)。

### CommandSetBlock(`CommandSetBlock.java`)

`processCommand`(`CommandSetBlock.java:49`):`parseBlockPos` 解析坐标,`CommandBase.getBlockByText` 解析方块,第 5 参为 meta(0-15),第 6 参 `replace|destroy|keep`,第 7 参起为 TileEntity 的 JSON→NBT。要点:目标位置原有 TileEntity 是 `IInventory` 时先 `clear()` 再置 air,避免掉落物(`CommandSetBlock.java:113-121`);`world.setBlockState(blockpos, iblockstate, 2)` 用 flag 2(只发客户端更新,不触发邻居更新),成功后手动 `world.notifyNeighborsRespectDebug(blockpos, iblockstate.getBlock())`(`CommandSetBlock.java:125/144`);NBT 写入前强制覆盖 x/y/z 键(`CommandSetBlock.java:137-139`)。

## 时序与生命周期

本包无自身 tick/render 循环,所有代码都是被动调用:

- 注册时机:`ServerCommandManager` 构造(服务端启动时,`MinecraftServer` 初始化阶段)一次性 new 出全部命令实例。命令对象无状态,单例复用。
- 执行时机:全部 `processCommand` 在**服务端主线程**执行——聊天命令由 `NetHandlerPlayServer.processChatMessage` 经 `PacketThreadUtil.checkThreadAndEnqueue` 转到服务端线程后分发;命令方块由 `BlockCommandBlock` 的方块更新/`EntityMinecartCommandBlock.onActivatorRailPass` 在世界 tick 中调 `CommandBlockLogic.trigger`。任何一处都不在 Netty EventLoop 上跑。
- `addTabCompletionOptions` 同样在服务端线程执行(响应 C14PacketTabComplete)。
- `CommandBlockLogic` 生命周期跟随宿主 TileEntity/Entity:世界加载时 `readDataFromNBT` 恢复,保存时 `writeDataToNBT`;客户端侧的 logic 副本只用于 GUI 编辑,`trigger` 在 `worldIn.isRemote` 时仅清零 `successCount`(`CommandBlockLogic.java:126-129`)。

## 挂钩点(Hook Points)

本包大多数命令是薄封装,真正值得挂钩的是命令方块执行链与几个改世界状态的命令:

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void trigger(World worldIn)` | CommandBlockLogic.java:124 | 命令方块被红石激活 / 命令矿车过激活铁轨,每次触发一次(服务端线程) | 拦截/记录/改写所有命令方块执行的命令;实现命令方块沙箱或审计 | 客户端侧(`isRemote`)只清计数不执行;抛出的 Throwable 会变成 ReportedException 崩服 |
| `public void setCommand(String command)` | CommandBlockLogic.java:110 | 玩家在 GuiCommandBlock 保存 → `MC|AdvCdm` payload → NetHandlerPlayServer.java:1388 | 校验/过滤玩家写入命令方块的命令内容 | 会把 successCount 清零;网络路径已要求 permLevel 2 + 创造模式 |
| `public boolean tryOpenEditCommandBlock(EntityPlayer playerIn)` | CommandBlockLogic.java:237 | 玩家右键命令方块(BlockCommandBlock.java:82) | 客户端 UI 层接管命令方块编辑界面(替换 GuiCommandBlock) | 仅创造模式返回 true;`openEditCommandBlock` 只在 `isRemote` 侧调用 |
| `public abstract void func_145757_a(ByteBuf p_145757_1_);` | CommandBlockLogic.java:220 | GuiCommandBlock 保存时组 `MC|AdvCdm` payload(GuiCommandBlock.java:92) | 观察/伪造命令方块定位数据(type 0 = 方块坐标 3×int,type 1 = 实体 id) | 与 NetHandlerPlayServer 的读取顺序必须逐字节对应 |
| `public void processCommand(ICommandSender sender, String[] args) throws CommandException`(CommandTeleport) | CommandTeleport.java:46 | `/tp` 执行时(服务端线程) | 观察/改写传送行为;功能层做传送日志、回退点记录 | 玩家路径走 S08PacketPlayerPosLook 相对标志位,直接改 posX/Y/Z 不会同步客户端 |
| `public void processCommand(ICommandSender sender, String[] args) throws CommandException`(CommandSetBlock) | CommandSetBlock.java:49 | `/setblock` 执行时 | 世界编辑审计、区域保护(在 setBlockState 前拦截) | setBlockState flag 为 2,邻居更新是事后手动补发的 |
| `public void processCommand(ICommandSender sender, String[] args) throws CommandException`(CommandSummon) | CommandSummon.java:50 | `/summon` 执行时 | 实体生成白名单/上限控制 | NBT `Riding` 链可一次生成多个实体;`EntityList.createEntityFromNBT` 抛 RuntimeException 被吞成 commands.summon.failed |
| `protected Scoreboard getScoreboard()` | CommandScoreboard.java:345 | CommandScoreboard 每个子操作开头 | 重定向记分板来源(如 per-world 记分板实验) | 原实现固定取 dimension 0,全部子命令都经过它,是单点 |

聊天类命令(`/say`、`/tell`、`/me`)的更上游挂钩点在 `ServerConfigurationManager#sendChatMsg` 与 `NetHandlerPlayServer#processChatMessage`,不在本包。

## 数据与协议

### CommandBlockLogic NBT(`writeDataToNBT` / `readDataFromNBT`,CommandBlockLogic.java:58-97)

| 字段名 | NBT 类型 | 读写方法 | 取值含义 |
|---|---|---|---|
| Command | String (8) | `setString` / `getString` | 存储的命令文本 |
| SuccessCount | Integer | `setInteger` / `getInteger` | 上次执行成功计数(红石比较器输出) |
| CustomName | String (8) | `setString` / `getString("CustomName")`,读取带 `hasKey("CustomName", 8)` 保护 | 发送者显示名,默认 `"@"` |
| TrackOutput | Boolean (1) | `setBoolean` / `getBoolean`,读取带 `hasKey("TrackOutput", 1)` 保护 | 是否记录 LastOutput |
| LastOutput | String (8) | `IChatComponent.Serializer.componentToJson` / `jsonToComponent`,仅 `trackOutput` 时读写 | 上次输出的 JSON 聊天组件 |
| (stats) | — | `resultStats.writeStatsToNBT(tagCompound)` / `readStatsFromNBT(nbt)` | CommandResultStats 委托读写 |

### `MC|AdvCdm` custom payload(客户端→服务端,GuiCommandBlock 组包 / NetHandlerPlayServer.java:1357-1394 解包)

| 字段 | 类型 | 写方法 (GuiCommandBlock) | 读方法 (NetHandlerPlayServer) | 含义 |
|---|---|---|---|---|
| type | byte | `packetbuffer.writeByte(this.localCommandBlock.func_145751_f())` | `packetbuffer.readByte()` | 0 = TileEntity 命令方块,1 = 命令矿车 |
| 定位数据 | 3×int(type 0)/ int(type 1) | `this.localCommandBlock.func_145757_a(packetbuffer)` | `readInt()`×3 → BlockPos / `readInt()` → entity id | 找回服务端 CommandBlockLogic |
| command | String | writeString | `packetbuffer.readStringFromBuffer(packetbuffer.readableBytes())` | 新命令文本 |
| trackOutput | boolean | writeBoolean | `packetbuffer.readBoolean()` | false 时服务端顺带 `setLastOutput((IChatComponent)null)` |

### 其它协议触点

- `CommandSetDefaultSpawnpoint` 广播 `S05PacketSpawnPosition(blockpos)` 给所有玩家(`CommandSetDefaultSpawnpoint.java:60`)。
- `CommandTeleport` 经 `playerNetServerHandler.setPlayerLocation(...)` 间接发 `S08PacketPlayerPosLook`,`EnumFlags` 集合标记哪些分量是相对值(`CommandTeleport.java:84-132`)。
- `CommandBanIp.field_147211_a` 是公开的 IPv4 校验正则常量,被 `CommandPardonIp.java:54` 复用。
- JSON→NBT:`CommandSetBlock`/`CommandSummon`/`CommandTestFor`/`CommandTestForBlock`/`CommandScoreboard.setPlayer` 都用 `JsonToNBT.getTagFromJson`,NBT 子集比对统一走 `NBTUtil.func_181123_a(expected, actual, true)`。

## 不变量与陷阱

- **所有 processCommand 假定运行在服务端主线程**,且大量使用静态单例 `MinecraftServer.getServer()`。单人游戏退出世界后 server 实例更替;若从异步上下文调用会拿到过期/空引用(`CommandBlockLogic.trigger` 对 null server 有防护,各命令类没有)。
- 命令对象是**无状态单例**,在 `ServerCommandManager` 构造时创建一次;不要往里加可变字段。
- `CommandBlockLogic.canCommandSenderUseCommand` 硬编码 `permLevel <= 2`,所以命令方块跑不了 `/op`、`/ban` 等 3 级命令;`trigger` 还受 `isCommandBlockEnabled()`(server.properties `enable-command-block`)门控。
- `ban/ban-ip/banlist/pardon/pardon-ip` 的 `canCommandSenderUseCommand` 依赖 `getBannedPlayers()/getBannedIPs().isLanServer()`——MCP 命名有误导,该方法实际语义是"该 UserList 已启用"。
- `CommandScoreboard` 的 `*` 通配递归调用 `this.processCommand`,通配只允许出现一次;`players operation` 的 `/=`、`%=` 除数为 0 时**静默不改分**,不报错(`CommandScoreboard.java:1082-1095`)。
- `CommandSetBlock` 用 flag 2 setBlockState 后手动补 `notifyNeighborsRespectDebug`;直接改这段要理解 flag 位义(1=邻居更新,2=发客户端,4=不重渲染)。
- `CommandSaveOff/On` 直接翻转 `WorldServer.disableLevelSaving` 公有字段;`CommandSaveAll` 会临时把它压成 false 保存后再还原(`CommandSaveAll.java:50-53`),并发改这个字段会互相覆盖。
- `CommandAchievement` 的 give 会自动补齐未解锁的父成就链,take `*` 是逆序移除;`func_175145_a` 是"撤销成就"的未映射名。
- `CommandSummon` 的 `"LightningBolt"` 是字符串特判,不走 EntityList;`Riding` NBT 链没有深度限制。
- LWJGL3/JDK25 移植相关:本包纯逻辑层,无 GL/输入依赖,唯一外部类型是 `io.netty.buffer.ByteBuf`(`CommandBlockLogic.func_145757_a`)——仓库已升级 Netty 4.2.16,该签名未受影响。`SimpleDateFormat timestampFormat` 是静态共享且非线程安全,但只在服务端线程使用,维持这一约束即可。

## 交叉引用

- `net.minecraft.command` → `CommandBase`(全部命令的基类,`getPlayer`/`parseBlockPos`/`parseCoordinate`/`notifyOperators` 等工具)、`CommandHandler`/`ServerCommandManager#registerCommand`(注册)、`ICommandManager#executeCommand`(CommandBlockLogic 调用)、`CommandResultStats`(QUERY_RESULT/AFFECTED_* 统计)
- `net.minecraft.server` → `MinecraftServer#getServer`、`#getConfigurationManager`、`#initiateShutdown`(CommandStop)、`#shareToLAN`(CommandPublishLocalServer)
- `net.minecraft.server.management` → `ServerConfigurationManager`(op/ban/whitelist/sendChatMsg)、`IPBanEntry`、`UserListBansEntry`
- `net.minecraft.network` → `NetHandlerPlayServer#processVanilla250Packet`(MC|AdvCdm → CommandBlockLogic#setCommand)、`NetHandlerPlayServer#setPlayerLocation`(CommandTeleport)、`S05PacketSpawnPosition`、`S08PacketPlayerPosLook.EnumFlags`
- `net.minecraft.tileentity` → `TileEntityCommandBlock`(匿名子类化 CommandBlockLogic)
- `net.minecraft.entity` → `EntityMinecartCommandBlock`(匿名子类化 CommandBlockLogic)、`EntityList#createEntityFromNBT`(CommandSummon)、`EntityPlayer#openEditCommandBlock`
- `net.minecraft.block` → `BlockCommandBlock` → `CommandBlockLogic#trigger/#tryOpenEditCommandBlock/#getSuccessCount`
- `net.minecraft.client.gui` → `GuiCommandBlock` → `CommandBlockLogic#func_145751_f/#func_145757_a/#getCommand`
- `net.minecraft.scoreboard` → `Scoreboard`/`ScoreObjective`/`ScorePlayerTeam`/`Score`/`IScoreObjectiveCriteria`/`Team.EnumVisible`(CommandScoreboard 全量使用)
- `net.minecraft.nbt` → `JsonToNBT#getTagFromJson`、`NBTUtil#func_181123_a`、`NBTTagCompound`
- `net.minecraft.stats` → `StatList#getOneShotStat`、`AchievementList`、`Achievement.parentAchievement`(CommandAchievement)
- `net.minecraft.world` → `WorldServer.disableLevelSaving`/`#saveAllChunks`/`#saveChunkData`(save 三兄弟)、`World#setSpawnPoint`/`#setBlockState`/`#spawnEntityInWorld`

## 覆盖声明

完整读取了 27/27 个文件(每个文件从第 1 行读到末尾)。逐行精读:CommandBlockLogic、CommandScoreboard、CommandTeleport、CommandSummon、CommandSetBlock、CommandBanIp、CommandTestForBlock、CommandAchievement。其余 19 个命令类体量小(38-135 行)、结构同构(getCommandName/getRequiredPermissionLevel/getCommandUsage/processCommand/addTabCompletionOptions),同样全文读取但按模式化方式核对。另外为核实调用关系,结构性浏览了包外的 ServerCommandManager(注册段)、NetHandlerPlayServer(1355-1394 行 payload 处理)、TileEntityCommandBlock、EntityMinecartCommandBlock、BlockCommandBlock、GuiCommandBlock 的相关行(grep 定位,未全文精读)。
