---
area: net/minecraft/world/storage
slug: mc-world-storage
files: 14
lines: 2867
tier: B
---

# net/minecraft/world/storage

## 定位

本包是世界存档层的抽象与基础实现：level.dat 的读写（`WorldInfo` / `SaveHandler`）、存档目录枚举与管理（`ISaveFormat` / `SaveFormatOld`）、玩家数据落盘（`IPlayerFileData`）、地图物品数据（`MapData` / `MapStorage`）、以及异步文件 IO 线程（`ThreadedFileIOBase`）。

调用方：
- 单人游戏（集成服务端）路径：`Minecraft` 在启动时构造 `AnvilSaveConverter`（`SaveFormatOld` 的子类，Minecraft.java:503），`GuiSelectWorld` 用 `getSaveList()` 枚举存档，`WorldServer` 持有 `ISaveHandler` 并在存档时调用它。
- 多人客户端路径：`WorldClient` 用 `SaveHandlerMP` + `SaveDataMemoryStorage`（WorldClient.java:52,58）——一切落盘操作变为 no-op，地图数据只存内存，由 `S34PacketMaps` 填充。
- `AnvilChunkLoader`（chunk/storage 包）通过 `ThreadedFileIOBase.getThreadedIOInstance().queueIO(this)` 把区块写入排到 IO 线程。

如果本包消失：无法读写 level.dat、无法进入单人世界、存档选择界面无数据、地图物品（filled_map）在客户端无法渲染更新、区块异步保存机制失效。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| DerivedWorldInfo | 310 | extends WorldInfo | 维度世界（Nether/End）共享主世界 WorldInfo 的只读代理，所有 setter 为空实现 |
| IPlayerFileData | 22 | interface | 玩家 NBT 数据读写接口（writePlayerData / readPlayerData / getAvailablePlayerDat） |
| ISaveFormat | 62 | interface | 存档格式层接口：枚举、删除、重命名、转换存档 |
| ISaveHandler | 57 | interface | 单个世界存档的读写接口：WorldInfo、chunk loader、玩家数据、map 文件 |
| IThreadedFileIO | 9 | interface | 异步 IO 任务接口，单方法 `boolean writeNextIO()` |
| MapData | 297 | extends WorldSavedData | 地图物品的像素颜色、中心坐标、装饰图标数据；含内部类 MapInfo 负责按玩家增量发包 |
| MapStorage | 236 | (无) | WorldSavedData 的加载/缓存/落盘管理器，附带 idcounts 唯一 ID 分配 |
| SaveDataMemoryStorage | 43 | extends MapStorage | 纯内存版 MapStorage，供多人客户端使用，不触盘 |
| SaveFormatComparator | 89 | implements Comparable&lt;SaveFormatComparator&gt; | 存档列表条目（文件名、显示名、最后游玩时间等），按 lastTimePlayed 降序排序 |
| SaveFormatOld | 278 | implements ISaveFormat | 老格式存档实现：level.dat 读取、删除/重命名存档；被 AnvilSaveConverter 继承 |
| SaveHandler | 352 | implements ISaveHandler, IPlayerFileData | 磁盘存档处理器：session.lock、level.dat 原子替换写入、playerdata/、data/ 目录 |
| SaveHandlerMP | 83 | implements ISaveHandler | 多人客户端的空实现，所有方法返回 null / no-op |
| ThreadedFileIOBase | 101 | implements Runnable | 单例后台 "File IO Thread"，轮询队列执行 IThreadedFileIO.writeNextIO() |
| WorldInfo | 928 | (无) | level.dat "Data" 标签的内存表示：种子、出生点、时间、天气、游戏规则、世界边界等 |

## 核心类详解

### WorldInfo（WorldInfo.java）

level.dat 的核心数据类。关键字段（WorldInfo.java:15-87）：`long randomSeed`、`WorldType terrainType`、`String generatorOptions`、`int spawnX/spawnY/spawnZ`、`long totalTime`、`long worldTime`、`NBTTagCompound playerTag`、`String levelName`、`int saveVersion`、`WorldSettings.GameType theGameType`、`boolean hardcore`、`boolean allowCommands`、`EnumDifficulty difficulty`、边界字段 `borderCenterX/borderCenterZ/borderSize/...`、`GameRules theGameRules`。常量 `public static final EnumDifficulty DEFAULT_DIFFICULTY = EnumDifficulty.NORMAL;`（WorldInfo.java:15）。

关键方法：
- `public WorldInfo(NBTTagCompound nbt)`（WorldInfo.java:93）— 从 level.dat 的 "Data" 复合标签反序列化，含大量 hasKey 兼容分支。
- `public NBTTagCompound getNBTTagCompound()`（WorldInfo.java:306）、`public NBTTagCompound cloneNBTCompound(NBTTagCompound nbt)`（WorldInfo.java:316）— 序列化入口，均委托给 `private void updateTagCompound(NBTTagCompound nbt, NBTTagCompound playerNbt)`（WorldInfo.java:323）。
- `public void populateFromWorldSettings(WorldSettings settings)`（WorldInfo.java:253）— 新建世界时从 WorldSettings 填充。
- `public void addToCrashReport(CrashReportCategory category)`（WorldInfo.java:843）— 崩溃报告注入种子/生成器/时间等信息。

调用时机：`World` 构造与 tick 中随处读取（时间、天气、出生点）；`WorldServer.saveLevel` 存档时经 `saveWorldInfoWithPlayer` 序列化（WorldServer.java:941）。

### SaveHandler（SaveHandler.java）

磁盘存档处理器，同时实现 `ISaveHandler` 和 `IPlayerFileData`（`getPlayerNBTManager()` 返回 `this`，SaveHandler.java:302）。关键字段：`private final File worldDirectory / playersDirectory / mapDataDir`、`private final long initializationTime = MinecraftServer.getCurrentTimeMillis();`（SaveHandler.java:33）、`private final String saveDirectoryName`。

关键方法：
- `public SaveHandler(File savesDirectory, String directoryName, boolean playersDirectoryIn)`（SaveHandler.java:38）— 建目录（playerdata、data），末尾调 `setSessionLock()`。
- `private void setSessionLock()`（SaveHandler.java:58）— 写 `session.lock`，内容是 initializationTime 的一个 long；失败抛 `RuntimeException("Failed to check session lock, aborting")`。
- `public void checkSessionLock() throws MinecraftException`（SaveHandler.java:92）— 读回 session.lock，不等于 initializationTime 就抛 `MinecraftException("The save is being accessed from another location, aborting")`。
- `public WorldInfo loadWorldInfo()`（SaveHandler.java:128）— 先读 level.dat，失败回退 level.dat_old。
- `public void saveWorldInfoWithPlayer(WorldInfo worldInformation, NBTTagCompound tagCompound)`（SaveHandler.java:168）— 三文件轮换：写 level.dat_new → level.dat 改名 level.dat_old → level.dat_new 改名 level.dat。`saveWorldInfo(WorldInfo)`（SaveHandler.java:209）逻辑相同但不带 Player 标签。
- `public void writePlayerData(EntityPlayer player)`（SaveHandler.java:250）— 写 `playerdata/<uuid>.dat.tmp` 后 rename 为 `.dat`；`public NBTTagCompound readPlayerData(EntityPlayer player)`（SaveHandler.java:276）— 读入并调 `player.readFromNBT`。
- `public IChunkLoader getChunkLoader(WorldProvider provider)`（SaveHandler.java:120）— 基类直接 `throw new RuntimeException("Old Chunk Storage is no longer supported.")`；实际由子类 `AnvilSaveHandler`（chunk/storage 包）覆写。
- `public File getMapFileFromName(String mapName)`（SaveHandler.java:340）— 返回 `data/<mapName>.dat`。

调用时机：`SaveFormatOld.getSaveLoader`（SaveFormatOld.java:242）构造；`WorldServer.saveLevel` 存档路径调用 checkSessionLock（经 World.java:3382-3384）与 saveWorldInfoWithPlayer；`ConfigurationManager` 通过 `getPlayerNBTManager()` 读写玩家数据。

### SaveFormatOld（SaveFormatOld.java）

`ISaveFormat` 的基础实现，实际运行时使用的是其子类 `AnvilSaveConverter`。字段：`protected final File savesDirectory`（SaveFormatOld.java:22）。

关键方法：
- `public WorldInfo getWorldInfo(String saveName)`（SaveFormatOld.java:67）— 读 `<save>/level.dat`（回退 level.dat_old）的 "Data" 标签构造 WorldInfo。
- `public void renameWorld(String dirName, String newName)`（SaveFormatOld.java:117）— 只改 level.dat 里的 "LevelName"，不改目录名。
- `public boolean deleteWorldDirectory(String saveName)`（SaveFormatOld.java:172）— 递归删除，最多重试 5 次、每次间隔 500ms（对 Windows 文件句柄残留的容错）。
- `public ISaveHandler getSaveLoader(String saveName, boolean storePlayerdata)`（SaveFormatOld.java:242）— `return new SaveHandler(this.savesDirectory, saveName, storePlayerdata);`（AnvilSaveConverter 覆写为返回 AnvilSaveHandler）。
- `protected static boolean deleteFiles(File[] files)`（SaveFormatOld.java:216）。

### ThreadedFileIOBase（ThreadedFileIOBase.java）

饿汉单例后台 IO 线程。字段：`private static final ThreadedFileIOBase threadedIOInstance = new ThreadedFileIOBase();`（ThreadedFileIOBase.java:10）、`private List<IThreadedFileIO> threadedIOQueue = Collections.synchronizedList(...)`、`private volatile long writeQueuedCounter / savedIOCounter`、`private volatile boolean isThreadWaiting`。

关键方法：
- 构造器（private，ThreadedFileIOBase.java:16）— `new Thread(this, "File IO Thread")`，优先级 1（最低），类加载时即启动。
- `public static ThreadedFileIOBase getThreadedIOInstance()`（ThreadedFileIOBase.java:26）。
- `public void run()`（ThreadedFileIOBase.java:31）— 死循环调 `processQueue()`；每个条目之间 sleep 10ms（waitForFinish 期间为 0ms），队列空时 sleep 25ms。
- `public void queueIO(IThreadedFileIO p_75735_1_)`（ThreadedFileIOBase.java:81）— 去重入队，`writeQueuedCounter++`。
- `public void waitForFinish() throws InterruptedException`（ThreadedFileIOBase.java:90）— 自旋等待 `writeQueuedCounter == savedIOCounter`。

调用时机：`AnvilChunkLoader.saveChunk` 入队（AnvilChunkLoader.java:131）；`AnvilSaveHandler.flush()` 调用 `waitForFinish()`（AnvilSaveHandler.java:60），后者由 `WorldServer.flush()`（WorldServer.java:1071）在服务端退出/保存路径触发（MinecraftServer.java:498,1134）。

### MapData（MapData.java）

地图物品的世界存档数据，键形如 `"map_" + itemDamage`。关键字段（MapData.java:22-31）：`public int xCenter; public int zCenter; public byte dimension; public byte scale;`、`public byte[] colors = new byte[16384];`（128x128 像素）、`public List<MapData.MapInfo> playersArrayList`、`private Map<EntityPlayer, MapData.MapInfo> playersHashMap`、`public Map<String, Vec4b> mapDecorations`（LinkedHashMap，键为玩家名或 `"frame-" + entityId`）。

关键方法：
- `public void readFromNBT(NBTTagCompound nbt)`（MapData.java:50）/ `public void writeToNBT(NBTTagCompound nbt)`（MapData.java:94）— NBT 序列化，写出固定 width/height=128。
- `public void updateVisiblePlayers(EntityPlayer player, ItemStack mapStack)`（MapData.java:108）— 服务端逐 tick（经 ItemMap.onUpdate）维护持图玩家列表与装饰图标。
- `public Packet getMapPacket(ItemStack mapStack, World worldIn, EntityPlayer player)`（MapData.java:219）— 经 MapInfo 生成增量 `S34PacketMaps`（脏区第一次全量，其后每 5 次调用发一次仅装饰的空包）。
- `public void updateMapData(int x, int y)`（MapData.java:225）— 标脏 + 扩展每个 MapInfo 的脏矩形。
- `public void calculateMapCenter(double x, double z, int mapScale)`（MapData.java:38）。
- 内部类 `MapInfo`：`public Packet getPacket(ItemStack stack)`（MapData.java:265）、`public void update(int x, int y)`（MapData.java:278）。

调用时机：服务端由 `ItemMap.getMapData` / `updateMapData` / `createMapDataPacket` 驱动；客户端由 `NetHandlerPlayClient.handleMaps`（NetHandlerPlayClient.java:1433）经 `ItemMap.loadMapData` 创建后用 `packetIn.setMapdataTo(mapdata)` 填充，再交给 `MapItemRenderer.updateMapTexture`。

### MapStorage / SaveDataMemoryStorage（MapStorage.java / SaveDataMemoryStorage.java）

`MapStorage` 管理所有 `WorldSavedData`（地图、村庄等）的缓存与落盘。字段：`private ISaveHandler saveHandler`、`protected Map<String, WorldSavedData> loadedDataMap`、`private List<WorldSavedData> loadedDataList`、`private Map<String, Short> idCounts`（MapStorage.java:20-23）。

- `public WorldSavedData loadData(Class <? extends WorldSavedData > clazz, String dataIdentifier)`（MapStorage.java:35）— 命中缓存直接返回；否则反射调用 `clazz.getConstructor(String.class)` 构造并从 `data/<id>.dat` 读入（gzip 压缩 NBT，根标签下的 "data"）。
- `public void setData(String dataIdentifier, WorldSavedData data)`（MapStorage.java:87）。
- `public void saveAllData()`（MapStorage.java:101）— 遍历 loadedDataList，`isDirty()` 的写盘后 `setDirty(false)`；由 `WorldServer` 存档时调用（WorldServer.java:942）。
- `public int getUniqueDataId(String key)`（MapStorage.java:188）— 自增 short 计数并同步写 `data/idcounts.dat`（注意：此文件是**未压缩** `CompressedStreamTools.write/read`，与地图数据的 `writeCompressed` 不同）。

`SaveDataMemoryStorage` 覆写四个公开方法：loadData 只查内存 map（SaveDataMemoryStorage.java:16），setData 只放内存，`saveAllData()` 空实现，`getUniqueDataId` 恒返回 0。构造时 `super((ISaveHandler)null)`。用于 `WorldClient`（WorldClient.java:58）。

### DerivedWorldInfo（DerivedWorldInfo.java）

包装另一个 `WorldInfo`（字段 `private final WorldInfo theWorldInfo`，DerivedWorldInfo.java:13），所有 getter 委托、所有 setter 空实现（DerivedWorldInfo.java:161-309），使 Nether/End 与主世界共享时间/天气/规则且不可独立修改。由 `WorldServerMulti` 构造（WorldServerMulti.java:17）。

### SaveHandlerMP（SaveHandlerMP.java）

多人客户端专用 `ISaveHandler` 空实现：`loadWorldInfo()`、`getChunkLoader`、`getPlayerNBTManager`、`getMapFileFromName`、`getWorldDirectory` 返回 null；`getWorldDirectoryName()` 返回 `"none"`（SaveHandlerMP.java:73）。由 `WorldClient` 构造（WorldClient.java:52）。

## 时序与生命周期

- 启动：`Minecraft` 构造 `AnvilSaveConverter`（Minecraft.java:503）。`ThreadedFileIOBase` 在首次被类引用时即启动 "File IO Thread"（饿汉单例）。
- 进入单人世界：`ISaveFormat.getSaveLoader(...)` → `new SaveHandler(...)`（建目录 + 写 session.lock）→ `loadWorldInfo()` 读 level.dat → `WorldServer` 构造 `new MapStorage(this.saveHandler)`（WorldServer.java:119）。
- 进入多人世界：`WorldClient` 构造 `SaveHandlerMP` + `new WorldInfo(settings, "MpServer")` + `SaveDataMemoryStorage`。
- 每 tick（服务端线程）：`ItemMap.onUpdate` → `MapData.updateVisiblePlayers`；`WorldServer` tick 中读写 `WorldInfo` 的时间/天气字段。自动保存（每 900 tick）与手动保存走 `saveAllChunks` → `saveLevel`：`checkSessionLock()` → `saveWorldInfoWithPlayer` → `mapStorage.saveAllData()`（WorldServer.java:931-942）。
- 每帧（客户端主线程）：本包无每帧逻辑；`MapData` 的渲染由 render 包的 `MapItemRenderer` 消费。
- 退出/停服：`WorldServer.flush()` → `AnvilSaveHandler.flush()` → `ThreadedFileIOBase.waitForFinish()` 阻塞至队列清空。
- 线程归属：level.dat / playerdata / map data 的读写都在**调用方线程**（服务端线程或客户端主线程）同步执行；只有经 `queueIO` 提交的区块写入在 "File IO Thread" 上执行。`handleMaps` 经 `PacketThreadUtil.checkThreadAndEnqueue` 保证在客户端主线程处理（NetHandlerPlayClient.java:1435）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public WorldInfo loadWorldInfo()` | SaveHandler.java:128 | 进入单人世界加载 level.dat 时 | 拦截/修改世界元数据（种子、游戏模式、规则）加载 | 返回 null 会走"新建世界"路径 |
| `public void saveWorldInfoWithPlayer(WorldInfo worldInformation, NBTTagCompound tagCompound)` | SaveHandler.java:168 | 每次世界保存（自动保存 / 退出 / F3+S 类操作） | 观察存档事件、注入自定义 NBT、备份钩子 | 同步 IO，阻塞服务端线程；三文件轮换不可打断 |
| `public void writePlayerData(EntityPlayer player)` | SaveHandler.java:250 | 玩家存档（下线 / 世界保存） | 备份/审计玩家数据、附加自定义标签 | 异常仅 logger.warn，静默丢档风险 |
| `public NBTTagCompound readPlayerData(EntityPlayer player)` | SaveHandler.java:276 | 玩家进入世界 | 修改载入的玩家 NBT（位置、背包） | 副作用：方法内部已调用 `player.readFromNBT` |
| `public void checkSessionLock() throws MinecraftException` | SaveHandler.java:92 | 每次 saveLevel 前（WorldServer.java:931） | 自定义多进程互斥策略 | 抛 MinecraftException 会中止保存 |
| `public IChunkLoader getChunkLoader(WorldProvider provider)` | SaveHandler.java:120 | 每个维度世界初始化时 | 替换区块存储实现（自定义格式/压缩） | 基类直接抛 RuntimeException，实际入口在 AnvilSaveHandler 覆写 |
| `public ISaveHandler getSaveLoader(String saveName, boolean storePlayerdata)` | SaveFormatOld.java:242 | 选择世界进入时 | 返回自定义 SaveHandler，整体接管存档层 | 运行时实际走 AnvilSaveConverter 的覆写版本 |
| `public List<SaveFormatComparator> getSaveList() throws AnvilConverterException` | SaveFormatOld.java:42 | GuiSelectWorld 打开时 | 过滤/注入存档列表条目 | 实际使用 AnvilSaveConverter.getSaveList（AnvilSaveConverter.java:45） |
| `public boolean deleteWorldDirectory(String saveName)` | SaveFormatOld.java:172 | GUI 删除世界 | 加回收站/确认逻辑 | 递归硬删除，重试 5 次；不可恢复 |
| `public WorldSavedData loadData(Class <? extends WorldSavedData > clazz, String dataIdentifier)` | MapStorage.java:35 | World.loadItemData（World.java:3612）、ItemMap.loadMapData | 注册自定义 WorldSavedData、拦截地图数据加载 | 反射要求目标类有 `(String)` 构造器，否则 RuntimeException |
| `public void saveAllData()` | MapStorage.java:101 | WorldServer.saveLevel（WorldServer.java:942） | 观察/追加持久化数据 | 同步 gzip 写盘 |
| `public int getUniqueDataId(String key)` | MapStorage.java:188 | 创建新地图物品等 | 接管 ID 分配 | short 溢出无保护；每次调用都写 idcounts 文件 |
| `public void updateVisiblePlayers(EntityPlayer player, ItemStack mapStack)` | MapData.java:108 | 服务端每 tick（ItemMap.onUpdate 持图时） | 注入自定义地图装饰（waypoint 类功能） | mapDecorations 键冲突会被覆盖 |
| `public Packet getMapPacket(ItemStack mapStack, World worldIn, EntityPlayer player)` | MapData.java:219 | ItemMap.createMapDataPacket（每 tick 节流后） | 改写发给客户端的 S34PacketMaps | 增量逻辑依赖 MapInfo 脏矩形状态，勿乱序调用 |
| `public void updateMapData(int x, int y)` | MapData.java:225 | 地图像素被重算时（ItemMap.updateMapData） | 感知地图内容变更 | 调用 `super.markDirty()`，触发下次 saveAllData 落盘 |
| `public void queueIO(IThreadedFileIO p_75735_1_)` | ThreadedFileIOBase.java:81 | AnvilChunkLoader 排队区块写入（AnvilChunkLoader.java:131） | 挂接自定义异步落盘任务 | 队列去重靠 `contains`（对象相等）；任务在 IO 线程执行，注意共享状态 |
| `public void waitForFinish() throws InterruptedException` | ThreadedFileIOBase.java:90 | AnvilSaveHandler.flush（退出世界/停服） | 在完全落盘后执行收尾逻辑 | 自旋 + sleep(10)，调用线程阻塞 |
| `public void handleMaps(S34PacketMaps packetIn)`（NetHandlerPlayClient，消费本包） | NetHandlerPlayClient.java:1433 | 客户端收到地图封包 | 观察/改写客户端地图数据（小地图类功能的现成数据源） | 已被 checkThreadAndEnqueue 调度到主线程 |

## 数据与协议

### level.dat（gzip 压缩 NBT，根 → "Data" 复合标签）

由 `WorldInfo(NBTTagCompound)`（WorldInfo.java:93）读、`updateTagCompound`（WorldInfo.java:323）写。主要字段：

| 字段名 | NBT 类型 | 读/写方法 | 含义 |
|---|---|---|---|
| RandomSeed | long | getLong/setLong | 世界种子 |
| generatorName / generatorVersion / generatorOptions | string/int/string | getString 等 | 地形生成器类型、版本、选项（经 WorldType.parseWorldType 解析） |
| GameType | int | getInteger/setInteger | 游戏模式 ID（WorldSettings.GameType.getByID） |
| MapFeatures | boolean | getBoolean，缺省 true | 是否生成结构 |
| SpawnX / SpawnY / SpawnZ | int | getInteger/setInteger | 出生点 |
| Time / DayTime | long | getLong/setLong | 总 tick 数 / 昼夜时间（DayTime 缺失时回退为 Time） |
| LastPlayed | long | 写入 `MinecraftServer.getCurrentTimeMillis()` | 最后游玩时间（WorldInfo.java:337） |
| SizeOnDisk / LevelName / version | long/string/int | — | 存档大小（不精确）、显示名、存档版本（19132=McRegion，19133=Anvil） |
| clearWeatherTime / rainTime / raining / thunderTime / thundering | int/int/bool/int/bool | — | 天气状态 |
| hardcore / allowCommands / initialized | boolean | allowCommands 缺失时按 `GameType.CREATIVE` 推断（WorldInfo.java:175） | 模式标志 |
| Player | compound | getCompoundTag | 单人玩家数据；其 "Dimension" 决定 dimension 字段 |
| GameRules | compound | GameRules.readFromNBT/writeToNBT | 游戏规则 |
| Difficulty / DifficultyLocked | byte/boolean | — | 难度 |
| BorderCenterX/Z, BorderSize, BorderSizeLerpTime, BorderSizeLerpTarget, BorderSafeZone, BorderDamagePerBlock, BorderWarningBlocks, BorderWarningTime | double/long | 各自 hasKey 保护；注意写出时 BorderWarningBlocks/Time 用 `setDouble`（WorldInfo.java:355-356），读入时用 `getInteger` | 世界边界参数 |

### data/map_N.dat（gzip 压缩 NBT，根 → "data" 复合标签）

由 `MapData.readFromNBT` / `writeToNBT`（MapData.java:50,94）读写：

| 字段名 | NBT 类型 | 含义 |
|---|---|---|
| dimension | byte | 地图所属维度 |
| xCenter / zCenter | int | 地图中心世界坐标 |
| scale | byte | 缩放 0-4（读入时 clamp） |
| width / height | short | 写出恒为 128；读入非 128x128 时居中重排（注意 MapData.java:75,81 的条件是 `\|\|` 而非 `&&`，为原版遗留 bug，边界裁剪实际不生效） |
| colors | byte[] | 16384 字节像素颜色索引 |

### data/idcounts.dat

未压缩 NBT（`CompressedStreamTools.read/write`，MapStorage.java:163,224），根标签下每个键为 ID 前缀（如 "map"）、值为 NBTTagShort 计数。

### 封包

`MapData.MapInfo.getPacket`（MapData.java:265）产出 `S34PacketMaps(mapId, scale, decorations, colors, minX, minY, width, height)`；客户端 `handleMaps` 用 `packetIn.setMapdataTo(mapdata)` 回填本地 MapData。playerdata/<uuid>.dat 为 gzip 压缩的实体 NBT（`EntityPlayer.writeToNBT` 全量）。

## 不变量与陷阱

- **session.lock 协议**：`SaveHandler` 构造即写锁；每次保存前 `checkSessionLock()` 比较文件内容与本进程的 `initializationTime`。两个进程打开同一存档时，后开的会覆盖锁，先开的下次保存抛 `MinecraftException`。锁不会被主动释放/删除。
- **level.dat 三文件轮换**（new → old → 正式）提供一次崩溃回退：`loadWorldInfo` 与 `SaveFormatOld.getWorldInfo` 都会回退读 level.dat_old。
- **DerivedWorldInfo 的 setter 全是静默 no-op**：对 Nether/End 世界调用 `setWorldTime` 等不会报错也不会生效，必须改主世界的 WorldInfo。
- **多人客户端一切为 null/no-op**：`SaveHandlerMP.getWorldDirectory()` 返回 null，任何假设"世界必有目录"的功能代码在多人下会 NPE。`SaveDataMemoryStorage.getUniqueDataId` 恒 0。
- **ThreadedFileIOBase 是 JVM 级单例且线程永不退出**：`run()` 是 `while(true)` 无退出条件；`processQueue` 的 `threadedIOQueue.size()`/`get(i)`/`remove(i--)` 依赖 synchronizedList 的单方法原子性，遍历本身无锁（原版即如此）。`waitForFinish` 靠两个 volatile long 计数对齐，不要在 IO 线程自身调用（死锁）。
- **MapStorage.loadData 的反射约定**：自定义 WorldSavedData 子类必须提供 `(String)` 单参构造器，否则 `RuntimeException("Failed to instantiate ...")`。
- **错误处理普遍是 printStackTrace/logger.warn 后继续**（MapStorage.java:70,139,181,230；SaveHandler.java:142,202,243,269）——存档失败不会中止游戏，排查丢档要看日志。
- **WorldInfo 两个命名陷阱**：`public void getBorderCenterZ(double posZ)` 与 `public void getBorderCenterX(double posX)`（WorldInfo.java:743,751）名为 get 实为 setter，原版反编译遗留，勿按名字理解。
- **MapData.readFromNBT 的 `||` bug**（MapData.java:75,81）：`j1 >= 0 || j1 < 128` 恒真，非 128 尺寸地图重排时不做真正的边界检查，异常尺寸数据可能越界（原版同款行为）。
- LWJGL3/JDK25 移植注意：本包纯 java.io + NBT，无 LWJGL 依赖，移植风险低。JDK 25 下 `File.renameTo` 跨卷/被占用时仍可能静默失败（代码未检查返回值，SaveHandler.java:186,193,227,234,265），与原版一致。
- 文件 IO 全部同步在调用线程执行（除区块写入），大存档保存会卡服务端线程一帧以上。

## 交叉引用

- net.minecraft.client → `Minecraft#saveLoader`（Minecraft.java:503，构造 AnvilSaveConverter）
- net.minecraft.client.gui → `GuiSelectWorld`（使用 `ISaveFormat#getSaveList` / `SaveFormatComparator`，GuiSelectWorld.java:15,305）
- net.minecraft.client.multiplayer → `WorldClient#<init>`（SaveHandlerMP、SaveDataMemoryStorage，WorldClient.java:52,58）
- net.minecraft.client.network → `NetHandlerPlayClient#handleMaps`（NetHandlerPlayClient.java:1433，消费 MapData）
- net.minecraft.world → `World#checkSessionLock` / `World#loadItemData` / `World#setItemData` / `World#getUniqueDataId`（World.java:3382,3612,3603,3621）
- net.minecraft.world → `WorldServer#saveLevel`（checkSessionLock / saveWorldInfoWithPlayer / mapStorage.saveAllData，WorldServer.java:931-942）、`WorldServer#flush`（WorldServer.java:1071）
- net.minecraft.world → `WorldServerMulti#<init>`（DerivedWorldInfo，WorldServerMulti.java:17）
- net.minecraft.world.chunk.storage → `AnvilSaveConverter extends SaveFormatOld`（AnvilSaveConverter.java:28）、`AnvilSaveHandler extends SaveHandler`（AnvilSaveHandler.java:12）、`AnvilChunkLoader implements IThreadedFileIO`（queueIO，AnvilChunkLoader.java:131）
- net.minecraft.item → `ItemMap#loadMapData` / `ItemMap#getMapData` / `ItemMap#createMapDataPacket`（ItemMap.java:31,45,254）
- net.minecraft.nbt → `CompressedStreamTools#readCompressed/writeCompressed/read/write`（所有落盘路径）
- net.minecraft.server → `MinecraftServer#getCurrentTimeMillis`（SaveHandler.java:33、WorldInfo.java:337）；`MinecraftServer` 停服时逐世界 `flush()`（MinecraftServer.java:498,1134）
- net.minecraft.network.play.server → `S34PacketMaps`（MapData.java:270,274）
- net.minecraft.realms → `RealmsLevelSummary` / `RealmsAnvilLevelStorageSource`（包装 SaveFormatComparator / ISaveFormat）
- net.minecraft.crash → `CrashReportCategory#addCrashSectionCallable`（WorldInfo.java:843-927）

## 覆盖声明

完整读取了 14/14 个文件（全部逐行精读，本包最大文件 WorldInfo.java 928 行亦通读全文）。无仅做结构性浏览的类。此外对包外的 AnvilSaveConverter、AnvilSaveHandler、AnvilChunkLoader、WorldServer、WorldClient、World、ItemMap、NetHandlerPlayClient、Minecraft、MinecraftServer 做了定点 grep/节选核对，用于交叉引用行号；这些文件未通读。
