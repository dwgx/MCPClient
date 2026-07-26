---
area: net/minecraft/server/management
slug: mc-server-management
files: 17
lines: 3395
tier: C
---

# net/minecraft/server/management

## 定位

本包是**集成服务端（integrated server）的玩家管理层**。虽然位于客户端仓库，但除 `LowerStringMap` 外全部代码运行在服务端逻辑一侧（单人游戏 / 局域网开放时由 "Server thread" 驱动）。三大职责：

1. **玩家生命周期**：`ServerConfigurationManager` 负责玩家登录（`initializeConnectionToPlayer`）、登出、重生、跨维度传送、全体广播封包；它是抽象类，本仓库唯一实现是 `net.minecraft.server.integrated.IntegratedPlayerList`。
2. **区块可见性 / 增量同步**：`PlayerManager`（每个 `WorldServer` 一个实例）追踪每个玩家视距内的区块，把方块变更批量打包成 `S23PacketBlockChange` / `S22PacketMultiBlockChange` / `S21PacketChunkData` 发给正在观察该区块的玩家。
3. **服务端侧交互裁决**：`ItemInWorldManager`（每个 `EntityPlayerMP` 一个实例）在服务端裁决挖掘进度、方块破坏、物品使用与方块激活，是防作弊的最终裁判。

辅助设施：`UserList` 家族（ban / op / whitelist 的 JSON 持久化）、`PlayerProfileCache`（用户名 ↔ UUID 缓存，写 `usercache.json`）、`PreYggdrasilConverter`（旧版用户名转 UUID）、`LowerStringMap`（键小写化的 Map，被实体属性系统复用）。

上游调用者：`MinecraftServer`（tick、存档、创建 `PlayerProfileCache`）、`NetHandlerLoginServer`（登录握手）、`NetHandlerPlayServer`（挖掘/使用封包分发）、`WorldServer`（每 tick 调 `updatePlayerInstances`）、`EntityPlayerMP`（每 tick 调 `updateBlockRemoving`）。若本包消失：单人世界无法加入（登录链断裂）、方块变更不再同步到客户端、挖掘/放置无服务端裁决、ban/op/whitelist 与玩家存档全部失效，同时 `BaseAttributeMap` / `ServersideAttributeMap` 因依赖 `LowerStringMap` 无法编译。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| BanEntry | 78 | `abstract class BanEntry<T> extends UserListEntry<T>` | ban 条目基类：起止时间、执行者、理由，含 JSON 读写与过期判断 |
| BanList | 47 | `extends UserList<String, IPBanEntry>` | IP ban 列表，`SocketAddress` 转字符串后查表（banned-ips.json） |
| IPBanEntry | 36 | `extends BanEntry<String>` | 单条 IP ban，JSON 键为 `ip` |
| ItemInWorldManager | 455 | （无父类） | 每玩家一个：服务端裁决挖掘进度、破坏方块、使用物品、激活方块 |
| LowerStringMap | 75 | `implements Map<String, V>` | 键统一小写化的 LinkedHashMap 包装（被属性系统复用） |
| PlayerManager | 539 | （无父类，含内部类 `PlayerInstance`） | 每世界一个：管理玩家可见区块集合与方块变更增量广播 |
| PlayerProfileCache | 401 | （无父类，含内部类 `ProfileEntry`、`Serializer`） | 用户名/UUID → GameProfile 缓存，LRU 语义，持久化 usercache 文件 |
| PreYggdrasilConverter | 91 | （无父类，纯静态） | 旧版用户名 → UUID 字符串转换（离线模式用哈希 UUID） |
| ServerConfigurationManager | 1080 | `abstract class` | 服务端玩家总管：登录/登出/重生/传送/广播/ban/op/whitelist/存档 |
| UserList | 200 | `class UserList<K, V extends UserListEntry<K>>`（含内部类 `Serializer`） | 通用 JSON 名单容器：增删查 + 过期清理 + 落盘 |
| UserListBans | 57 | `extends UserList<GameProfile, UserListBansEntry>` | 玩家 ban 名单（banned-players.json），键为 UUID 字符串 |
| UserListBansEntry | 62 | `extends BanEntry<GameProfile>` | 单条玩家 ban，JSON 键 `uuid`/`name` |
| UserListEntry | 32 | （泛型基类） | 名单条目基类：仅持有 value，提供序列化/过期钩子 |
| UserListOps | 61 | `extends UserList<GameProfile, UserListOpsEntry>` | OP 名单（ops.json），附 bypassesPlayerLimit 查询 |
| UserListOpsEntry | 74 | `extends UserListEntry<GameProfile>` | 单条 OP：`permissionLevel` + `bypassesPlayerLimit` |
| UserListWhitelist | 55 | `extends UserList<GameProfile, UserListWhitelistEntry>` | 白名单（whitelist.json） |
| UserListWhitelistEntry | 52 | `extends UserListEntry<GameProfile>` | 单条白名单条目，JSON 键 `uuid`/`name` |

## 核心类详解

### ServerConfigurationManager（ServerConfigurationManager.java）

抽象基类，实例由 `IntegratedServer` 创建为 `IntegratedPlayerList`（IntegratedPlayerList.java:9），通过 `MinecraftServer#getConfigurationManager()`（MinecraftServer.java:1304）暴露。

关键字段（ServerConfigurationManager.java:65-105）：
- `public static final File FILE_PLAYERBANS = new File("banned-players.json")`（:65，同组还有 `FILE_IPBANS`、`FILE_OPS`、`FILE_WHITELIST` :66-68）
- `private final MinecraftServer mcServer`（:73）
- `private final List<EntityPlayerMP> playerEntityList`（:74）—— 在线玩家权威列表
- `private final Map<UUID, EntityPlayerMP> uuidToPlayerMap`（:75）
- `private final UserListBans bannedPlayers`（:76）、`private final BanList bannedIPs`（:77）、`private final UserListOps ops`（:80）、`private final UserListWhitelist whiteListedPlayers`（:83）
- `private final Map<UUID, StatisticsFile> playerStatFiles`（:84）
- `private IPlayerFileData playerNBTManagerObj`（:87）—— 玩家 NBT 存档读写入口
- `protected int maxPlayers`（:95，构造器里初始化为 8，:117）

关键方法（签名逐字摘自源码）：
- `public void initializeConnectionToPlayer(NetworkManager netManager, EntityPlayerMP playerIn)`（:120）—— 登录主流程：读玩家 NBT、创建 `NetHandlerPlayServer`、发送 `S01PacketJoinGame`/`MC|Brand`/难度/出生点/能力/手持槽位，同步计分板、广播加入消息、调 `playerLoggedIn`。由 `NetHandlerLoginServer.tryAcceptPlayer` / `update` 调用（NetHandlerLoginServer.java:74/138）。
- `public String allowUserToConnect(SocketAddress address, GameProfile profile)`（:372）—— 依次查玩家 ban → 白名单 → IP ban → 人数上限；返回 null 表示放行，否则返回拒绝文案。`IntegratedPlayerList` 覆写它加了重名检查（IntegratedPlayerList.java:39-41）。
- `public EntityPlayerMP createPlayerForUser(GameProfile profile)`（:411）—— 踢掉同 UUID 的旧连接（"You logged in from another location"），按是否 demo 选 `DemoWorldManager` 或 `ItemInWorldManager` 构造新 `EntityPlayerMP`。
- `public EntityPlayerMP recreatePlayerEntity(EntityPlayerMP playerIn, int dimension, boolean conqueredEnd)`(:455) —— 死亡重生：新建实体、`clonePlayer`、床出生点回退、发送 `S07PacketRespawn` 等。
- `public void transferPlayerToDimension(EntityPlayerMP playerIn, int dimension)`（:524）与 `public void transferEntityToWorld(Entity entityIn, int p_82448_2_, WorldServer oldWorldIn, WorldServer toWorldIn)`（:552）—— 跨维度传送；地狱坐标 8 倍缩放与 `getDefaultTeleporter().placeInPortal` 都在后者（:556-626）。
- `public void onTick()`（:631）—— 每 600 tick 广播一次 `S38PacketPlayerListItem(UPDATE_LATENCY)`；由 `MinecraftServer` 主循环调用（MinecraftServer.java:803）。
- `public void sendPacketToAllPlayers(Packet packetIn)`（:640）、`public void sendToAllNearExcept(EntityPlayer p_148543_1_, double x, double y, double z, double radius, int dimension, Packet p_148543_11_)`（:806）—— 全服/范围广播的唯一出口。
- `public void playerLoggedIn(EntityPlayerMP playerIn)`（:315）/ `public void playerLoggedOut(EntityPlayerMP playerIn)`（:342）—— 维护 playerEntityList、uuidToPlayerMap、Tab 列表封包、实体生成/移除、存档落盘。
- `public NBTTagCompound readPlayerDataFromFile(EntityPlayerMP playerIn)`（:279）—— 单人房主从 level.dat 的 `Player` 标签读（:284-289），其他玩家走 `playerNBTManagerObj.readPlayerData`。
- `public void setPlayerManager(WorldServer[] worldServers)`（:224）—— 绑定主世界的玩家 NBT 管理器并注册 `IBorderListener`，把世界边界变化转成 `S44PacketWorldBorder` 广播（:227-255）。由 `MinecraftServer` 初始化世界时调用（MinecraftServer.java:346）。

### PlayerManager（PlayerManager.java）

每个 `WorldServer` 一个，构造时用 `getConfigurationManager().getViewDistance()` 初始化视距（PlayerManager.java:41-45）。

关键字段（:24-36）：
- `private final WorldServer theWorldServer`
- `private final LongHashMap<PlayerManager.PlayerInstance> playerInstances`（:26）—— 区块坐标打包成 long 作键：`(long)chunkX + 2147483647L | (long)chunkZ + 2147483647L << 32`（:97）
- `private final List<PlayerManager.PlayerInstance> playerInstancesToUpdate`（:27）—— 本 tick 有方块变更的实例
- `private int playerViewRadius`（:33，clamp 到 3..32，:300）

关键方法：
- `public void updatePlayerInstances()`（:58）—— 每 tick 由 `WorldServer.tick()` 调用（WorldServer.java:216）。刷掉 `playerInstancesToUpdate` 里的增量；每 8000 tick 全量走一遍并累计 `InhabitedTime`；无玩家且维度不能重生时卸载全部区块（:84-92）。
- `public void addPlayer(EntityPlayerMP player)`（:134）/ `public void removePlayer(EntityPlayerMP player)`（:211）—— 以玩家所在区块为中心 ±viewRadius 注册/注销 `PlayerInstance`。
- `public void updateMountedMovingPlayer(EntityPlayerMP player)`（:246）—— 移动超过 64 平方块（8 格）才做增量换入换出；由 `ServerConfigurationManager.serverUpdateMountedMovingPlayer`（:334-337）转发。
- `public void markBlockForUpdate(BlockPos pos)`（:119）—— 世界改方块后进入这里，转给 `PlayerInstance.flagChunkForUpdate(int x, int y, int z)`（:428）。
- 内部类 `PlayerInstance.onUpdate()`（:466）—— 变更数 1 → `S23PacketBlockChange`；等于 64 → 整块 `S21PacketChunkData`（按 `flagsYAreasToUpdate` 的 Y 段掩码）；其余 → `S22PacketMultiBlockChange`；含 TileEntity 的坐标补发 `getDescriptionPacket()`（:478-517）。变更坐标压缩为 `short short1 = (short)(x << 12 | z << 8 | y)`（:439），去重后存入 `locationOfBlockChange[64]`。
- `PlayerInstance.removePlayer(EntityPlayerMP player)`（:386）—— 最后一个观察者离开时结算 InhabitedTime 并 `theChunkProviderServer.dropChunk`（:412）。

### ItemInWorldManager（ItemInWorldManager.java）

每个 `EntityPlayerMP` 持有一个（字段 `theItemInWorldManager`）；`DemoWorldManager extends ItemInWorldManager`（DemoWorldManager.java:12）是演示模式变体。

关键字段（:26-45）：
- `public World theWorld`、`public EntityPlayerMP thisPlayerMP`
- `private WorldSettings.GameType gameType = WorldSettings.GameType.NOT_SET`（:30）
- `private boolean isDestroyingBlock`（:33）、`private int curblockDamage`（:36）、`private boolean receivedFinishDiggingPacket`（:42）、`private int durabilityRemainingOnBlock = -1`（:45）
- `private BlockPos field_180240_f = BlockPos.ORIGIN`（:35，正在挖的坐标）与 `field_180241_i`（:43，"收到完成包但没挖够时间"的坐标）

关键方法：
- `public void updateBlockRemoving()`（:91）—— 每 tick 由 `EntityPlayerMP.onUpdate()` 调用（EntityPlayerMP.java:274）。推进 `curblockDamage`，按 `getPlayerRelativeBlockHardness` 计算 0-10 档破坏进度并 `sendBlockBreakProgress` 广播；进度满时触发 `tryHarvestBlock`。
- `public void onBlockClicked(BlockPos pos, EnumFacing side)`（:151）—— 开始挖掘（C07PacketPlayerDigging.START_DESTROY_BLOCK → NetHandlerPlayServer.java:543）。创造模式直接 `tryHarvestBlock`；冒险模式检查 `canDestroy`；硬度 ≥1.0 的瞬间破坏。
- `public void blockRemoving(BlockPos pos)`（:212）—— 客户端宣称挖完（STOP_DESTROY_BLOCK → NetHandlerPlayServer.java:554）。服务端复核进度：`f >= 0.7F` 放行，否则置 `receivedFinishDiggingPacket` 等 tick 补足——这是反"快速挖掘"作弊的关键路径。
- `public void cancelDestroyingBlock()`（:243）—— ABORT_DESTROY_BLOCK（NetHandlerPlayServer.java:558）。
- `public boolean tryHarvestBlock(BlockPos pos)`（:269）—— 真正破坏：`removeBlock` → 创造模式回发 `S23PacketBlockChange` 抑制掉落，否则 `onBlockDestroyed` + `harvestBlock` 掉落物品。创造模式手持 `ItemSword` 直接返回 false（:271-274）。
- `public boolean tryUseItem(EntityPlayer player, World worldIn, ItemStack stack)`（:338）与 `public boolean activateBlockOrUseItem(EntityPlayer player, World worldIn, ItemStack stack, BlockPos pos, EnumFacing side, float offsetX, float offsetY, float offsetZ)`（:386）—— 右键使用/激活（NetHandlerPlayServer.java:595/601）。SPECTATOR 只允许看容器 GUI（:388-415）；潜行 + 手持物品时跳过 `onBlockActivated`（:418）；创造模式用完后恢复堆叠数与耐久（:432-440）。
- `public void setGameType(WorldSettings.GameType type)`（:52）—— 改能力、`sendPlayerAbilities`、并向全服广播 `S38PacketPlayerListItem(UPDATE_GAME_MODE)`。

### UserList 家族（UserList.java 及子类）

- 泛型容器 `public class UserList<K, V extends UserListEntry<K>>`（UserList.java:29），底层 `Map<String, V> values`（:34），键由 `protected String getObjectKey(K obj)`（:115）生成——GameProfile 系列子类统一覆写为 `obj.getId().toString()`（UserListBans.java:40-43、UserListOps.java:41-44、UserListWhitelist.java:35-38），即**按 UUID 而非用户名索引**。
- `public void addEntry(V entry)`（:73）/ `public void removeEntry(K entry)`（:93）每次改动立即 `writeChanges()` 落盘（Gson pretty-print）。
- `public V getEntry(K obj)`（:87）先 `removeExpired()`（:128）清掉 `hasBanExpired()` 的条目——**注意 removeExpired 只改内存不落盘**。
- 反序列化经内部类 `Serializer`（:173），多态由子类覆写 `protected UserListEntry<K> createEntry(JsonObject entryData)`（:146）完成。
- `BanEntry` 的过期判断：`boolean hasBanExpired()`（BanEntry.java:66）为包私有；`banEndDate == null` 表示永久（序列化写 `"forever"`，:75）。

### PlayerProfileCache（PlayerProfileCache.java）

- 三个索引：`usernameToProfileEntryMap`（键为 `toLowerCase(Locale.ROOT)`）、`uuidToProfileEntryMap`、`LinkedList<GameProfile> gameProfiles`（:46-48）—— 链表头是最近使用，`getEntriesWithLimit(1000)`（:287）落盘时只留前 1000 条，实现 LRU 截断。
- `public GameProfile getGameProfileForUsername(String username)`（:153）—— 命中且未过期则提到链表头；过期则删除并走 `getGameProfile(MinecraftServer server, String username)`（:84）向 Mojang `GameProfileRepository` 查询；离线模式回退 `EntityPlayer.getUUID` 的名字哈希 UUID（:100-105）。每次调用结尾都 `save()`（:183）。
- 构造于 `MinecraftServer`（MinecraftServer.java:192/206），条目默认过期时间为当前时间 +1 个月（`calendar.add(2, 1)`，:127-130）。

## 时序与生命周期

全部逻辑（除 `LowerStringMap` 的属性用途）归属**服务端线程**（`new Thread(this, "Server thread")`，MinecraftServer.java:821）。Netty EventLoop 只负责把封包排队，`NetHandlerPlayServer` / `NetHandlerLoginServer` 的处理均已在服务端 tick 内。

初始化顺序：
1. `MinecraftServer` 构造 → `new PlayerProfileCache(this, ...)`（构造器内 `load()` 读 usercache）。
2. `IntegratedServer` 创建 `IntegratedPlayerList`（即 `ServerConfigurationManager` 构造器 :107-118：new 出 4 个 UserList 并 `setLanServer(false)` 两个 ban 表；**此处不读盘**，本仓库没有调用 `readSavedFile` 之类的加载逻辑，名单文件只写不读——见"不变量与陷阱"）。
3. 世界加载后 `MinecraftServer` 调 `serverConfigManager.setPlayerManager(this.worldServers)`（MinecraftServer.java:346）绑定玩家 NBT 与世界边界监听。
4. 每个 `WorldServer` 构造自己的 `PlayerManager`。

登录时序（每 tick 驱动 `NetHandlerLoginServer.update()`）：`allowUserToConnect`（:372，ban/白名单/满员裁决）→ `createPlayerForUser`（:411，踢重复登录 + new EntityPlayerMP）→ `initializeConnectionToPlayer`（:120，建 NetHandlerPlayServer、灌初始封包）→ `playerLoggedIn`（:315，入列表、Tab 广播、`preparePlayer` 注册 PlayerManager）。

每 tick：
- `MinecraftServer` 主循环 → `serverConfigManager.onTick()`（每 600 tick 广播延迟刷新，:631-638）。
- `WorldServer.tick()` → `thePlayerManager.updatePlayerInstances()`（增量方块广播 + 每 8000 tick 的 InhabitedTime 结算）。
- `EntityPlayerMP.onUpdate()` → `theItemInWorldManager.updateBlockRemoving()`（挖掘进度推进）。
- 玩家移动（含载具）→ `serverUpdateMountedMovingPlayer` → `PlayerManager.updateMountedMovingPlayer`（区块换入换出）。

每帧：无（本包与渲染无关）。

关闭：`MinecraftServer.stopServer` → `saveAllPlayerData()` + `removeAllPlayers()`（MinecraftServer.java:486-487）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void initializeConnectionToPlayer(NetworkManager netManager, EntityPlayerMP playerIn)` | ServerConfigurationManager.java:120 | 玩家登录握手完成后（服务端线程） | 加入事件、修改初始封包序列、注入自定义 MOTD/资源包逻辑 | 顺序敏感：`S01PacketJoinGame` 必须最先；漏发 `setPlayerLocation` 会卡在下落 |
| `public String allowUserToConnect(SocketAddress address, GameProfile profile)` | ServerConfigurationManager.java:372 | 每次连接请求裁决 | 自定义准入（额外白名单、密码、人数策略）；返回非 null 即拒绝并作为踢出文案 | `IntegratedPlayerList` 已覆写加重名检查，继承时记得 `super` |
| `public void playerLoggedIn(EntityPlayerMP playerIn)` / `public void playerLoggedOut(EntityPlayerMP playerIn)` | ServerConfigurationManager.java:315 / :342 | 玩家进入/离开世界 | 玩家进出事件、统计、清理会话状态 | logout 里 `writePlayerData` 先于列表移除；不要在遍历 `playerEntityList` 时移除 |
| `public void onTick()` | ServerConfigurationManager.java:631 | 服务端每 tick | 周期性玩家层逻辑（自定义 Tab、心跳） | 600 tick 的 UPDATE_LATENCY 广播依赖此方法 |
| `public void sendPacketToAllPlayers(Packet packetIn)` | ServerConfigurationManager.java:640 | 一切全服广播的汇聚点 | 观察/过滤/改写所有广播封包（含聊天、Tab、边界） | 高频调用，勿做重活；同族还有 `sendToAllNearExcept`（:806）、`sendPacketToAllPlayersInDimension`（:648） |
| `public void sendChatMsgImpl(IChatComponent component, boolean isChat)` | ServerConfigurationManager.java:1004 | 每条服务端聊天/系统消息 | 聊天过滤、日志、格式改写 | 也会写到 `mcServer.addChatMessage`（控制台/日志） |
| `public EntityPlayerMP recreatePlayerEntity(EntityPlayerMP playerIn, int dimension, boolean conqueredEnd)` | ServerConfigurationManager.java:455 | 死亡重生 / 出末地 | 自定义重生点、保留物品、重生事件 | 返回的是**新** EntityPlayerMP 实例，旧引用全部失效 |
| `public void transferPlayerToDimension(EntityPlayerMP playerIn, int dimension)` | ServerConfigurationManager.java:524 | 传送门跨维度 | 维度切换事件、拦截/改目的地 | 坐标 8 倍缩放与 placeInPortal 在 `transferEntityToWorld`（:552） |
| `public void onBlockClicked(BlockPos pos, EnumFacing side)` | ItemInWorldManager.java:151 | 收到 C07 START_DESTROY_BLOCK | 挖掘开始事件、区域保护（直接 return 即禁挖） | 需同时回发 S23PacketBlockChange 纠正客户端预测，否则出现幽灵方块 |
| `public boolean tryHarvestBlock(BlockPos pos)` | ItemInWorldManager.java:269 | 方块真正被破坏前 | 破坏事件/保护/自定义掉落的唯一收口 | 返回 false 阻止破坏；创造+剑的特判在 :271 |
| `public boolean activateBlockOrUseItem(EntityPlayer player, World worldIn, ItemStack stack, BlockPos pos, EnumFacing side, float offsetX, float offsetY, float offsetZ)` | ItemInWorldManager.java:386 | 收到 C08 右键方块 | 交互事件、容器保护、右键行为改写 | SPECTATOR 分支（:388-415）只开只读 GUI；注意潜行短路逻辑 :418 |
| `public void updateBlockRemoving()` | ItemInWorldManager.java:91 | EntityPlayerMP 每 tick | 改挖掘速度、进度显示 | 与 `blockRemoving`（:212）的 `f >= 0.7F` 复核联动，改一处需同步另一处 |
| `public void updatePlayerInstances()` | PlayerManager.java:58 | WorldServer 每 tick | 观察/节流方块同步；区块流控 | 空玩家时会 `unloadAllChunks`（:90） |
| `public void markBlockForUpdate(BlockPos pos)` | PlayerManager.java:119 | 世界任意方块变更后 | 拦截/记录将要广播给客户端的方块变更 | 每 chunk 每 tick 上限 64 条，超过退化为整块重发 |
| `PlayerInstance#sendToAllPlayersWatchingChunk(Packet thePacket)` | PlayerManager.java:453 | 区块级广播 | 按区块订阅粒度的封包观察/过滤 | 跳过仍在 `loadedChunks` 排队的玩家（:459） |

## 数据与协议

本包不定义封包，但读写 4 类 JSON 文件并构造大量 S 系封包。

### banned-players.json（UserListBansEntry.onSerialization, UserListBansEntry.java:25-33 + BanEntry.java:71-77）

| 字段 | 类型 | 读 / 写 | 含义 |
|---|---|---|---|
| uuid | String | `toGameProfile(json)` / `onSerialization` | 玩家 UUID（同时是内存 Map 的键） |
| name | String | 同上 | 玩家名（仅展示用） |
| created | String | `BanEntry(T,JsonObject)` :32 / :73 | ban 开始时间，格式 `yyyy-MM-dd HH:mm:ss Z`（BanEntry.java:10） |
| source | String | :40 / :74 | 执行者，缺省 `"(Unknown)"` |
| expires | String | :45 / :75 | 到期时间；写出时 null → 字面量 `"forever"` |
| reason | String | :53 / :76 | 理由，缺省 `"Banned by an operator."` |

### banned-ips.json（IPBanEntry.java:28-35）
同上表，把 `uuid`/`name` 换成单一 `ip`（String）字段。

### ops.json（UserListOpsEntry.onSerialization, UserListOpsEntry.java:39-49）

| 字段 | 类型 | 读 / 写 | 含义 |
|---|---|---|---|
| uuid / name | String | `constructProfile` :51 / :43-44 | 同 ban 表 |
| level | int | :22 / :46 | OP 权限级，缺省 0；写入时取 `mcServer.getOpPermissionLevel()`（ServerConfigurationManager.java:763） |
| bypassesPlayerLimit | boolean | :23 / :47 | 满员时是否仍可进入（`allowUserToConnect` :404 使用） |

### whitelist.json（UserListWhitelistEntry.java:19-27）
仅 `uuid` + `name`。

### usercache 文件（PlayerProfileCache.Serializer, PlayerProfileCache.java:332-399）

| 字段 | 类型 | 读 / 写 | 含义 |
|---|---|---|---|
| name | String | `deserialize` :347 / `serialize` :335 | 用户名 |
| uuid | String | :348 / :337（null → `""`） | UUID；解析失败整条丢弃（:377-380） |
| expiresOn | String | :349 / :338 | 缓存过期时间，同 `yyyy-MM-dd HH:mm:ss Z` |

### 构造的出站封包（部分）
S01PacketJoinGame、S3FPacketCustomPayload("MC|Brand")、S41PacketServerDifficulty、S05PacketSpawnPosition、S39PacketPlayerAbilities、S09PacketHeldItemChange、S38PacketPlayerListItem（ADD/REMOVE/UPDATE_GAME_MODE/UPDATE_LATENCY）、S07PacketRespawn、S1FPacketSetExperience、S1DPacketEntityEffect、S02PacketChat、S03PacketTimeUpdate、S2BPacketChangeGameState、S44PacketWorldBorder、S23PacketBlockChange、S22PacketMultiBlockChange、S21PacketChunkData。

## 不变量与陷阱

- **UserList 的键是 UUID 字符串，不是用户名**：三个 GameProfile 子类都覆写 `getObjectKey` 为 `obj.getId().toString()`；`getObjectKey` 会在 `getId() == null` 时 NPE——所以进入名单的 GameProfile 必须 isComplete。按名字查询要用 `isUsernameBanned` / `getGameProfileFromName` / `getBannedProfile` 的线性遍历。
- **UserList 只写不读**：本仓库 `UserList` 没有从磁盘加载的方法调用路径（`Serializer.deserialize` 存在但无人触发读取）；`ServerConfigurationManager` 构造器只是 new 出空表。集成服务端场景下 ban/op/whitelist 实际从空开始，改动会覆盖工作目录里的同名 JSON。注意这四个 `File` 常量是**相对路径**（`new File("banned-players.json")`），落在进程当前目录。
- **removeExpired 不落盘**：过期 ban 只从内存移除（UserList.java:128-144），文件里的过期条目要等下一次 add/remove 触发 `writeChanges` 才消失。
- **UserListBansEntry 构造器 bug（原版遗留）**：`super(profile, endDate, banner, endDate, banReason)`（UserListBansEntry.java:17）把 `endDate` 传进了 `startDate` 位置，`startDate` 参数被丢弃。移植时保留了原版行为，不要"顺手修复"，否则与原版存档行为不一致。
- **PlayerInstance 的 64 条上限**：`flagChunkForUpdate` 超过 64 条后不再记录坐标只置位 Y 段掩码，`onUpdate` 里 `numBlocksToUpdate == 64` 走整块重发；恰好 64 与超过 64 表现相同（计数封顶在 64）。
- **区块 key 打包公式**在 PlayerManager.java:97/:106/:402 出现三次，必须保持一致：`(long)chunkX + 2147483647L | (long)chunkZ + 2147483647L << 32`。
- **反作弊复核链**：`blockRemoving` 的 `f >= 0.7F` 与 `updateBlockRemoving` 的 `f >= 1.0F` 共同构成服务端挖掘校验；改挖掘速度类功能必须两处同改，否则出现方块回弹。
- **recreatePlayerEntity 返回新实例**：任何缓存 `EntityPlayerMP` 引用的功能层，在重生/出末地后必须刷新引用（旧实例的 `playerNetServerHandler` 被移接到新实例，:477）。
- **线程约束**：所有方法假定在 Server thread 上单线程执行，容器全是非线程安全的 ArrayList/HashMap。`PlayerProfileCache.getGameProfileForUsername` 在线模式会**同步阻塞**做 Mojang HTTP 查询（经 `GameProfileRepository.findProfilesByNames`），在服务端线程上调用会卡 tick。`sendPacketToAllPlayers` 里的 `sendPacket` 由 NetworkManager 负责调度到 Netty EventLoop，调用侧无需切线程。
- **SimpleDateFormat 是共享静态实例**（BanEntry.java:10、PlayerProfileCache.java:45、ServerConfigurationManager.java:70）且非线程安全——只要坚持单线程访问就没问题，JDK 25 下切勿在异步任务里复用。
- **LowerStringMap.containsValue 是 bug**（LowerStringMap.java:28-31）：实现写成了 `internalMap.containsKey(...)`。原版即如此，现有调用方没有用到它，勿依赖。
- LWJGL3/JDK25 移植对本包无直接影响（无渲染、无 native 调用）；Gson/authlib 依赖与原版相同。

## 交叉引用

- net/minecraft/server → `MinecraftServer#getConfigurationManager` / `MinecraftServer#getPlayerProfileCache` / `MinecraftServer#getGameProfileRepository`；主循环调 `ServerConfigurationManager#onTick`（MinecraftServer.java:803），初始化调 `#setPlayerManager`（:346），停服调 `#saveAllPlayerData` / `#removeAllPlayers`（:486-487）
- net/minecraft/server/integrated → `IntegratedPlayerList extends ServerConfigurationManager`（覆写 `allowUserToConnect`、`writePlayerData`、`getHostPlayerData`）；`IntegratedServer` 构造它
- net/minecraft/server/network → `NetHandlerLoginServer#tryAcceptPlayer` / `#update` 调 `allowUserToConnect`、`createPlayerForUser`、`initializeConnectionToPlayer`
- net/minecraft/network → `NetHandlerPlayServer#processPlayerDigging` / `#processPlayerBlockPlacement` 调 `ItemInWorldManager#onBlockClicked` / `#blockRemoving` / `#cancelDestroyingBlock` / `#tryUseItem` / `#activateBlockOrUseItem`
- net/minecraft/entity/player → `EntityPlayerMP#onUpdate` 每 tick 调 `ItemInWorldManager#updateBlockRemoving`；`EntityPlayerMP.theItemInWorldManager` 字段；`EntityPlayer#getUUID` 提供离线 UUID
- net/minecraft/world → `WorldServer#tick` 调 `PlayerManager#updatePlayerInstances`；`WorldServer#getPlayerManager`；`WorldServer.theChunkProviderServer#loadChunk` / `#dropChunk` 由 `PlayerInstance` 驱动
- net/minecraft/world/demo → `DemoWorldManager extends ItemInWorldManager`
- net/minecraft/world/border → `setPlayerManager` 注册 `IBorderListener` 广播 `S44PacketWorldBorder`
- net/minecraft/entity/ai/attributes → `BaseAttributeMap` / `ServersideAttributeMap` 使用 `LowerStringMap`（本包唯一被客户端逻辑直接消费的类）
- net/minecraft/entity/passive → `EntityHorse` / `EntityTameable` 读 NBT 时调 `PreYggdrasilConverter.getStringUUIDFromName` 转换旧版主人名
- net/minecraft/stats → `StatisticsFile` 由 `getPlayerStatsFile` 创建并缓存（stats/&lt;uuid&gt;.json，含旧文件按名重命名迁移，ServerConfigurationManager.java:1026-1037）
- net/minecraft/scoreboard → `sendScoreboard(ServerScoreboard, EntityPlayerMP)` 登录时同步队伍与计分项
- net/minecraft/command → `CommandBanIp` / `CommandDeOp` / `CommandListBans` 等直接操作 `getBannedIPs()#addEntry` / `getOppedPlayers()#getGameProfileFromName`；`EntityPlayerMP#canCommandSenderUseCommand`（EntityPlayerMP.java:1152）查 `UserListOpsEntry#getPermissionLevel`
- com/mojang/authlib → `GameProfile` / `GameProfileRepository` / `ProfileLookupCallback`（PlayerProfileCache、PreYggdrasilConverter 的在线查询）

## 覆盖声明

完整读取了 17/17 个文件（每个文件从第 1 行读到末行）。

逐行精读：ServerConfigurationManager、PlayerManager、ItemInWorldManager、UserList、PlayerProfileCache、BanEntry、UserListBansEntry、UserListOpsEntry、LowerStringMap。

通读但结构简单、未逐条展开分析：BanList、IPBanEntry、PreYggdrasilConverter、UserListBans、UserListEntry、UserListOps、UserListWhitelist、UserListWhitelistEntry（均为 100 行以下的薄封装，行为已在类清单与数据表中覆盖）。

行号引用均来自本仓库当前源码；另用 Grep 核实了 NetHandlerLoginServer、NetHandlerPlayServer、MinecraftServer、WorldServer、EntityPlayerMP、IntegratedPlayerList、DemoWorldManager、BaseAttributeMap 等调用方。
