---
area: net/minecraft/nbt
slug: mc-nbt
files: 18
lines: 2879
tier: B
---

# net/minecraft/nbt — NBT 标签树与序列化

## 定位

NBT（Named Binary Tag）是 Minecraft 所有持久化与网络传输结构化数据的统一格式。这个包提供：

- 11 种标签类型的内存表示（`NBTTagByte` … `NBTTagIntArray`），全部继承 `NBTBase`；
- 二进制读写（`CompressedStreamTools`，含 GZIP 压缩与文件安全写入）；
- 字符串命令语法解析（`JsonToNBT`，`/give`、`/summon` 等命令的 `{...}` 参数）；
- 反序列化配额防护（`NBTSizeTracker`，防恶意超大封包）；
- `GameProfile` 与 NBT 互转、NBT 子集匹配（`NBTUtil`）。

调用方遍布全客户端：`Entity#writeToNBT/readFromNBT`、`TileEntity`、`ItemStack`（附魔/显示名等全部走 `tagCompound`）、`PacketBuffer#readNBTTagCompoundFromBuffer/writeNBTTagCompoundToBuffer`（网络封包中的物品与实体数据）、`SaveHandler`/`AnvilChunkLoader`（level.dat、区块存档）、`ServerList`（servers.dat）、各命令类（`CommandGive`、`CommandSummon` 等经 `JsonToNBT`）。

本包自身只向外依赖 `net.minecraft.crash`（崩溃报告）、`net.minecraft.util`（`MathHelper`、`StringUtils`、`ReportedException`）和 Mojang authlib（`GameProfile`）。如果它消失：存档读写、服务器列表、所有携带物品 NBT 的封包编解码、命令系统的数据参数全部瘫痪。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| CompressedStreamTools | 193 | — | NBT 根 compound 的二进制读写入口：GZIP 流、文件、`DataInput/DataOutput`，含 `safeWrite` 临时文件替换 |
| JsonToNBT | 553 | —（含内部类 Any/Compound/List/Primitive） | 把命令里的 `{key:value}` 字符串解析成 `NBTTagCompound` |
| NBTBase | 123 | abstract | 所有标签的抽象基类：`write/read/getId/copy` 契约 + `createNewByType` 工厂 + 内部抽象类 `NBTPrimitive` |
| NBTException | 9 | extends Exception | `JsonToNBT` 解析失败时抛出的受检异常 |
| NBTSizeTracker | 31 | — | 按位数累计反序列化开销，超过上限抛 `RuntimeException`；`INFINITE` 单例不限制 |
| NBTTagByte | 103 | extends NBTBase.NBTPrimitive | id=1，单字节值 |
| NBTTagByteArray | 77 | extends NBTBase | id=7，`byte[]` 值（getter 返回内部数组引用，不拷贝） |
| NBTTagCompound | 551 | extends NBTBase | id=10，`Map<String, NBTBase>` 键值容器，含全套类型化 get/set 与 `merge` |
| NBTTagDouble | 105 | extends NBTBase.NBTPrimitive | id=6，double 值 |
| NBTTagEnd | 41 | extends NBTBase | id=0，compound 结束哨兵，无数据 |
| NBTTagFloat | 104 | extends NBTBase.NBTPrimitive | id=5，float 值 |
| NBTTagInt | 103 | extends NBTBase.NBTPrimitive | id=3，int 值 |
| NBTTagIntArray | 92 | extends NBTBase | id=11，`int[]` 值（getter 同样返回内部数组引用） |
| NBTTagList | 300 | extends NBTBase | id=9，同类型标签的有序列表，`tagType` 记录元素类型 |
| NBTTagLong | 103 | extends NBTBase.NBTPrimitive | id=4，long 值 |
| NBTTagShort | 103 | extends NBTBase.NBTPrimitive | id=2，short 值 |
| NBTTagString | 93 | extends NBTBase | id=8，UTF 字符串值 |
| NBTUtil | 195 | final | `GameProfile` ↔ NBT 互转；`func_181123_a` NBT 子集匹配（命令/配方用） |

## 核心类详解

### NBTBase（NBTBase.java）

- 类型名表：`public static final String[] NBT_TYPES = new String[] {"END", "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE", "BYTE[]", "STRING", "LIST", "COMPOUND", "INT[]"}`（NBTBase.java:9）。
- 序列化契约（包私有，只有本包能触发原始读写）：
  - `abstract void write(DataOutput output) throws IOException`（NBTBase.java:14）
  - `abstract void read(DataInput input, int depth, NBTSizeTracker sizeTracker) throws IOException`（NBTBase.java:16）
- `public abstract byte getId()`（NBTBase.java:23）— 标签类型字节，同时是 `equals`/`hashCode` 的基础（NBTBase.java:86-102，`equals` 只比较 id，子类必须再比数据）。
- `protected static NBTBase createNewByType(byte id)`（NBTBase.java:28）— 工厂，id 0–11 对应 12 个类；**未知 id 返回 `null`**（NBTBase.java:69），调用处（`NBTTagCompound.readNBT`、`NBTTagList.read`）不判空，坏数据会 NPE。
- `public abstract NBTBase copy()`（NBTBase.java:76）— 深拷贝。
- 内部类 `NBTPrimitive`（NBTBase.java:109-122）：数值标签的公共读数接口 `getLong/getInt/getShort/getByte/getDouble/getFloat`，是 `NBTTagCompound.getByte` 等对任意数值类型宽容读取的基础。

### NBTTagCompound（NBTTagCompound.java）

- 唯一字段：`private Map<String, NBTBase> tagMap = Maps.<String, NBTBase>newHashMap()`（NBTTagCompound.java:17）— 普通 `HashMap`，无序、非线程安全。
- 写盘格式：`void write(DataOutput output)`（NBTTagCompound.java:22）逐条 `writeEntry(s, nbtbase, output)` 后写终止字节 `output.writeByte(0)`（NBTTagCompound.java:30）。
- 读取：`void read(DataInput input, int depth, NBTSizeTracker sizeTracker)`（NBTTagCompound.java:33）——`depth > 512` 直接抛 `RuntimeException("Tried to read NBT tag with too high complexity, depth > 512")`（NBTTagCompound.java:37-39）；循环 `readType`/`readKey`/`readNBT` 直到遇到 type 0。
- 类型化访问核心：
  - `public boolean hasKey(String key, int type)`（NBTTagCompound.java:186）— `type == 99` 是"任意数值类型"通配（NBTTagCompound.java:205 返回 `i == 1 || i == 2 || i == 3 || i == 4 || i == 5 || i == 6`）。
  - `public byte getByte(String key)`（NBTTagCompound.java:212）等 primitive getter：键不存在或类型不匹配返回 0/空串，`ClassCastException` 被吞掉返回默认值——**不会报错**。
  - `public byte[] getByteArray(String key)`（NBTTagCompound.java:317）、`getIntArray`（:332）、`getCompoundTag`（:348）、`getTagList`（:363）：类型错则抛 `ReportedException`（崩溃报告），与 primitive getter 行为不同。
  - `public NBTTagList getTagList(String key, int type)`（NBTTagCompound.java:363）— 列表存在但元素类型不符时返回**新空列表**（NBTTagCompound.java:374），静默丢数据。
- `public void merge(NBTTagCompound other)`（NBTTagCompound.java:527）— 子 compound 递归合并，其余类型直接 `copy()` 覆盖；被 `CommandEntityData`、mob spawner 等使用。
- `static NBTBase readNBT(byte id, String key, DataInput input, int depth, NBTSizeTracker sizeTracker)`（NBTTagCompound.java:504）— 单条标签反序列化，IOException 包装成带 "Loading NBT data" 的 `ReportedException`。

### NBTTagList（NBTTagList.java）

- 字段：`private List<NBTBase> tagList = Lists.<NBTBase>newArrayList()`（NBTTagList.java:14）、`private byte tagType = 0`（NBTTagList.java:19）。
- `void write(DataOutput output)`（NBTTagList.java:24）— 写出前根据首元素**重算** `tagType`（NBTTagList.java:26-33），再写 type 字节 + int 数量 + 各元素 payload。
- `void read(...)`（NBTTagList.java:44）— 同样有 `depth > 512` 限制（:48）；`tagType == 0 && i > 0` 抛 `RuntimeException("Missing type on ListTag")`（NBTTagList.java:57-59）。
- `public void appendTag(NBTBase nbt)`（NBTTagList.java:105）— 类型不匹配时只打 `LOGGER.warn("Adding mismatching tag types to tag list")` 并**静默丢弃**（NBTTagList.java:117-120）；`set(int idx, NBTBase nbt)`（:130）同理。
- 取值方法都做了越界与类型防御，失败返回空对象：`getCompoundTagAt(int i)`（:175）、`getIntArrayAt`（:188）、`getDoubleAt`（:201）、`getFloatAt`（:214）、`getStringTagAt`（:230）、`get(int idx)`（:246，越界返回 `new NBTTagEnd()`）。
- `public int getTagType()`（NBTTagList.java:296）。

### CompressedStreamTools（CompressedStreamTools.java）

- `public static NBTTagCompound readCompressed(InputStream is) throws IOException`（CompressedStreamTools.java:26）— GZIP 解压后 `read(datainputstream, NBTSizeTracker.INFINITE)`。用于 `ServerList`、`SaveFormatOld`（level.dat）等。
- `public static void writeCompressed(NBTTagCompound p_74799_0_, OutputStream outputStream) throws IOException`（CompressedStreamTools.java:46）。
- `public static void safeWrite(NBTTagCompound p_74793_0_, File p_74793_1_) throws IOException`（CompressedStreamTools.java:60）— 写 `_tmp` 文件再 `renameTo`，注意这条路径写的是**未压缩**格式（内部走 `write(File)`）。
- `public static NBTTagCompound read(DataInput p_152456_0_, NBTSizeTracker p_152456_1_) throws IOException`（CompressedStreamTools.java:135）— 根必须是 compound，否则 `IOException("Root tag must be a named compound tag")`（:145）。`PacketBuffer` 从这里进。
- `private static NBTBase func_152455_a(DataInput p_152455_0_, int p_152455_1_, NBTSizeTracker p_152455_2_)`（CompressedStreamTools.java:165）— 读 type 字节、**读并丢弃根名字**（`p_152455_0_.readUTF()`，:175）、分发到 `NBTBase.createNewByType(b0)`。
- 对应地 `private static void writeTag(NBTBase p_150663_0_, DataOutput p_150663_1_)`（:154）总是把根名字写成空串 `""`（:160）。

### NBTSizeTracker（NBTSizeTracker.java）

- `public static final NBTSizeTracker INFINITE = new NBTSizeTracker(0L) { public void read(long bits) { } }`（NBTSizeTracker.java:5-10）— 匿名子类覆盖为 no-op；本地文件读取都用它。
- `public void read(long bits)`（NBTSizeTracker.java:22）— 注意参数是**位**，内部 `this.read += bits / 8L` 换算成字节（:24），超过 `max` 抛 `RuntimeException`（:28）。
- 网络侧唯一有限配额：`PacketBuffer.readNBTTagCompoundFromBuffer` 传 `new NBTSizeTracker(2097152L)`（2 MB，PacketBuffer.java:225）。

### JsonToNBT（JsonToNBT.java）

- 入口：`public static NBTTagCompound getTagFromJson(String jsonString) throws NBTException`（JsonToNBT.java:16）— 必须以 `{` 开头，`func_150310_b`（:34）做括号/引号平衡校验并统计顶层标签数（多于 1 个报错）。
- 解析器是三个内部类（均实现 `public abstract NBTBase parse() throws NBTException`，JsonToNBT.java:390）：
  - `Compound`（:393）→ `NBTTagCompound`；`List`（:415）→ `NBTTagList`；`Primitive`（:437）→ 按正则匹配类型后缀：`DOUBLE`=`[-+]?[0-9]*\.?[0-9]+[d|D]`、`FLOAT`(…f)、`BYTE`(…b)、`LONG`(…l)、`SHORT`(…s)、`INTEGER`、`DOUBLE_UNTYPED`，再兜底 `true/false` → `NBTTagByte`（:459-497）。
  - 纯数字数组文本（正则 `field_179273_b = Pattern.compile("\\[[-+\\d|,\\s]+\\]")`，:14）在 `Primitive.parse` 里转成 `NBTTagIntArray`（:505-525）。
- 调用方：`CommandGive`、`CommandSummon`、`CommandEntityData`、`CommandBlockData`、`CommandFill`、`CommandReplaceItem`、`CommandTestForBlock`、`ItemSkull` 等（都在集成服务器/命令执行线程上）。

### NBTUtil（NBTUtil.java）

- `public static GameProfile readGameProfileFromNBT(NBTTagCompound compound)`（NBTUtil.java:13）— 读 `"Name"`(8)、`"Id"`(8)、`"Properties"`(10)；Id 非法 UUID 时容忍为 null（:36-43）。`TileEntitySkull`、`LayerCustomHead` 等用它还原头颅皮肤。
- `public static NBTTagCompound writeGameProfile(NBTTagCompound tagCompound, GameProfile profile)`（NBTUtil.java:79）— 反向写出，Property 带 `"Value"`/`"Signature"`。
- `public static boolean func_181123_a(NBTBase p_181123_0_, NBTBase p_181123_1_, boolean p_181123_2_)`（NBTUtil.java:121）— "模板 ⊆ 目标"的子集匹配：**第一参为 null 恒返回 true**（:127-129）；compound 递归比对模板的每个键；`p_181123_2_` 为 true 时列表按"每个模板元素在目标里存在匹配"比较（无序）。用于 `/testforblock`、`/scoreboard`、`InventoryPlayer` 的按 NBT 清点、`MerchantRecipeList` 交易匹配。

### 数值标签共性（NBTTagByte/Short/Int/Long/Float/Double）

以 `NBTTagInt` 为代表：字段 `private int data`（NBTTagInt.java:10）；包私有无参构造给工厂用（:12），公有带值构造（:16）；`getId()` 返回对应类型字节；跨类型 getter 做截断转换（如 `NBTTagInt.getShort()` 是 `(short)(this.data & 65535)`，NBTTagInt.java:84-87）。浮点类取整走 `MathHelper.floor_double/floor_float`（NBTTagDouble.java:83、NBTTagFloat.java:82）。`toString` 带类型后缀（`b`/`s`/空/`L`/`f`/`d`），与 `JsonToNBT` 的解析正则互逆——这是命令数据往返的基础。

## 时序与生命周期

本包无自身 tick/帧循环，是被动数据结构库。时序性体现在调用点的线程归属：

- **主线程（客户端）**：`ServerList` 读写 servers.dat（进入多人游戏界面时）；`ItemStack`/`Entity`/`TileEntity` 的 NBT 读写；GUI 层读取物品 NBT（附魔光效、皮肤头颅渲染）。
- **Netty EventLoop**：`PacketBuffer.readNBTTagCompoundFromBuffer / writeNBTTagCompoundToBuffer` 在封包编解码阶段执行（PacketBuffer.java:191、:213）——即 slot 更新、实体元数据里的物品 NBT 都在网络线程上反序列化，size tracker 上限 2 MB 就是防这条路径被打爆。
- **集成服务器线程**：`SaveHandler`、`AnvilChunkLoader`、`MapStorage` 的存档读写；命令系统的 `JsonToNBT` 解析。
- **文件 IO 线程（区块保存）**：`AnvilChunkLoader` 的异步保存队列也会调用 `CompressedStreamTools`。

初始化无顺序要求：所有类无静态注册，`NBTSizeTracker.INFINITE`、`NBT_TYPES`、`JsonToNBT` 的正则均为静态常量，类加载即可用。

## 挂钩点（Hook Points）

NBT 包本身没有游戏事件，但它是"数据经过的收口"，功能层想观察/篡改物品、实体、存档数据时最集中的拦截面在这里。

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public static NBTTagCompound read(DataInput p_152456_0_, NBTSizeTracker p_152456_1_) throws IOException` | CompressedStreamTools.java:135 | 所有二进制 NBT 反序列化的总入口（封包、存档、servers.dat） | 全局观察/过滤进入客户端的一切 NBT；注入自定义数据清洗 | Netty EventLoop 与主线程都会进来，钩子必须无状态或线程安全 |
| `public static void write(NBTTagCompound p_74800_0_, DataOutput p_74800_1_) throws IOException` | CompressedStreamTools.java:149 | 所有 NBT 序列化出口（发包、写盘） | 出站数据审计、剥离敏感字段 | 同上；勿在此做重活，区块保存频繁调用 |
| `public NBTTagCompound readNBTTagCompoundFromBuffer() throws IOException` | PacketBuffer.java:213 | 封包解码含 NBT 字段时（物品 slot、实体数据） | 针对网络来源的 NBT 做上限/内容检查（反崩溃客户端书、超大物品名等） | 运行在 Netty EventLoop；已有 2 MB `NBTSizeTracker`，但深度炸弹仍靠 depth>512 兜底 |
| `public void setTag(String key, NBTBase value)` | NBTTagCompound.java:76 | 一切结构化写入的最终落点（所有 setXxx 都进 tagMap） | 观察某个键的写入（如 ItemStack `"ench"`、`"display"`） | 调用极高频，钩子开销直接影响存档与发包性能 |
| `public NBTBase getTag(String key)` | NBTTagCompound.java:164 | 一切读取的底层入口 | 键级别的数据虚拟化/重定向 | 同上，且 primitive getter 不经过它时会走 `tagMap.get` 直取（如 getByte :216） |
| `public void merge(NBTTagCompound other)` | NBTTagCompound.java:527 | `/entitydata`、`/blockdata` 等增量修改实体/方块实体时 | 拦截外部对实体 NBT 的批量改写 | 递归合并，注意子 compound 是原地修改 |
| `public void appendTag(NBTBase nbt)` | NBTTagList.java:105 | 列表构建（附魔列表、Lore、Pages 等） | 校验/改写列表元素 | 类型不匹配时静默丢弃且只 warn，勿依赖异常发现问题 |
| `public static NBTTagCompound getTagFromJson(String jsonString) throws NBTException` | JsonToNBT.java:16 | 玩家/命令方块执行带 `{...}` 参数的命令时 | 命令数据参数的语法扩展、白名单过滤 | 抛 `NBTException` 会变成命令报错，属预期路径 |
| `protected static NBTBase createNewByType(byte id)` | NBTBase.java:28 | 每读取一个标签就调用一次 | 注册自定义标签类型（返回自定义 NBTBase 子类） | 未知 id 返回 null → 上层 NPE；改这里等于改磁盘/线上格式，会破坏与原版服务器的互通 |
| `public void read(long bits)` | NBTSizeTracker.java:22 | 每个标签反序列化时累计开销 | 调整网络 NBT 配额、加监控统计 | 参数是位不是字节；`INFINITE` 覆盖为 no-op，本地文件不设防 |
| `public static boolean func_181123_a(NBTBase p_181123_0_, NBTBase p_181123_1_, boolean p_181123_2_)` | NBTUtil.java:121 | `/testfor*`、`/scoreboard`、`InventoryPlayer.clearMatchingItems`、村民交易匹配 | 改写物品 NBT 匹配语义（如忽略某些键） | 模板为 null 恒 true 的语义被多处依赖，勿改 |
| `public NBTBase copy()` | NBTBase.java:76（各子类实现，如 NBTTagCompound.java:453） | ItemStack.copy、merge、实体克隆等 | 深拷贝时机是做"写时快照"或脏标记的天然位置 | Compound/List 递归深拷贝，大 NBT 上很贵 |

## 数据与协议

### 二进制线上格式（存档与封包共用）

根标签由 `CompressedStreamTools.writeTag/func_152455_a` 处理：`[type:byte][name:UTF(恒为"")][payload]`；compound 内部每条目由 `NBTTagCompound.writeEntry`（NBTTagCompound.java:483）写 `[type:byte][name:UTF][payload]`，以 type=0 结束。

| id | 类 | payload 编码（write 方法） | sizeTracker 计费（read） |
|---|---|---|---|
| 0 | NBTTagEnd | 无 | 64 bits（NBTTagEnd.java:11） |
| 1 | NBTTagByte | `writeByte` | 72 |
| 2 | NBTTagShort | `writeShort` | 80 |
| 3 | NBTTagInt | `writeInt` | 96 |
| 4 | NBTTagLong | `writeLong` | 128 |
| 5 | NBTTagFloat | `writeFloat` | 96 |
| 6 | NBTTagDouble | `writeDouble` | 128 |
| 7 | NBTTagByteArray | `writeInt(len)` + `write(data)` | 192 + 8·len |
| 8 | NBTTagString | `writeUTF` | 288 + 16·len |
| 9 | NBTTagList | `writeByte(tagType)` + `writeInt(size)` + 元素 payload（无名字） | 296 + 32·size + 元素自身 |
| 10 | NBTTagCompound | 条目序列 + `writeByte(0)` | 384 + 每键 224+16·keyLen（重复键再 +288） |
| 11 | NBTTagIntArray | `writeInt(len)` + 逐个 `writeInt` | 192 + 32·len |

### GameProfile NBT 结构（NBTUtil）

| 字段名 | 类型 | 读写方法 | 含义 |
|---|---|---|---|
| `Name` | 8 (STRING) | `readGameProfileFromNBT` / `writeGameProfile`（NBTUtil.java:18/:83） | 玩家名 |
| `Id` | 8 (STRING) | 同上（:23/:88） | UUID 字符串，解析失败容忍为 null |
| `Properties` | 10 (COMPOUND) | :47/:115 | 键为属性名（如 textures），值为 list of compound |
| `Properties.<k>[i].Value` | 8 | :58/:102 | 属性值（base64 皮肤数据等） |
| `Properties.<k>[i].Signature` | 8 | :60/:106 | 可选 Mojang 签名 |

### JsonToNBT 字面量类型规则（Primitive，JsonToNBT.java:439-445）

`1b`→byte、`1s`→short、`1`→int、`1l`→long、`1.5f`→float、`1.5d`/`1.5`→double、`true/false`→byte(1/0)、`[1,2,3]`→int array（仅纯数字）、其余→string。

## 不变量与陷阱

- **根标签必须是 compound**：`CompressedStreamTools.read` 强制（CompressedStreamTools.java:145）。
- **深度上限 512**：compound 与 list 的 `read` 都检查（NBTTagCompound.java:37、NBTTagList.java:48）；超限抛的是 `RuntimeException` 不是 `IOException`，会穿透常规 IO 捕获。
- **NBTSizeTracker 参数是位**：`read(long bits)` 内部除以 8（NBTSizeTracker.java:24）。文件路径全部走 `INFINITE`，只有 `PacketBuffer` 设 2 MB 上限。
- **getter 语义不一致**：primitive getter 吞 `ClassCastException` 返回 0；数组/compound/list getter 抛 `ReportedException` 崩溃报告；`getTagList` 类型不符则返回新空列表。写代码前先确认想要哪种失败模式。
- **数组标签不拷贝**：`NBTTagByteArray.getByteArray()`（NBTTagByteArray.java:73）与 `NBTTagIntArray.getIntArray()`（NBTTagIntArray.java:88）直接返回内部数组引用；外部修改会污染标签，`copy()` 才做拷贝。
- **NBTTagList 静默丢弃**：`appendTag`/`set` 类型不匹配只 warn 不抛（NBTTagList.java:119、:144）；`tagType` 在 `write` 时按首元素重算（:26-33）。
- **`NBTBase.createNewByType` 未知 id 返回 null**，`readNBT`/`NBTTagList.read` 直接调 `nbtbase.read(...)` 会 NPE——损坏存档表现为 NPE 而非清晰报错。
- **`NBTBase.equals` 只比 id**（NBTBase.java:86-97），子类靠 `super.equals` 短路后再比数据；`NBTTagCompound.hashCode` 依赖 `HashMap.hashCode`，无序但一致。
- **HashMap 无序**：`NBTTagCompound.tagMap` 是普通 `HashMap`（NBTTagCompound.java:17），`write` 的键序不稳定——同一数据两次序列化字节可能不同，不能做字节级比较/哈希签名。
- **线程安全**：所有标签类完全无同步。同一 `NBTTagCompound` 严禁跨线程共享读写；Netty 线程反序列化出来的 compound 交给主线程后不应再被网络线程碰。
- **`NBTTagString` 构造判空写反了注释**：`new NBTTagString(null)` 中 `this.data = data` 先执行、后抛 `IllegalArgumentException("Empty string not allowed")`（NBTTagString.java:17-25）——传 null 会抛异常，空串合法且 `hasNoTags()` 为 true（:66-69）。
- **`safeWrite` 不压缩**：`CompressedStreamTools.safeWrite`（:60）内部走未压缩的 `write(File)`，与 `writeCompressed` 是两种磁盘格式，读取端必须对应。
- **LWJGL3/JDK25 移植**：本包为纯 `java.io`/`java.util` 代码，无 LWJGL 依赖，移植零改动。注意 JDK 的 `writeUTF/readUTF` 为 modified UTF-8 且单串上限 65535 字节——超长字符串标签（如超大 Pages）写出时会抛 `UTFDataFormatException`，行为与 JDK8 一致。
- **`NBTUtil.func_181123_a` 的 null 语义**：模板（第一参）为 null 恒 true（NBTUtil.java:127-129）——"无 NBT 要求"匹配一切；目标为 null 才是 false。

## 交叉引用

- `net/minecraft/network` → `PacketBuffer#readNBTTagCompoundFromBuffer` / `PacketBuffer#writeNBTTagCompoundToBuffer`（调用 `CompressedStreamTools.read/write`，唯一带 2 MB `NBTSizeTracker` 的路径）
- `net/minecraft/world/storage` → `SaveHandler#saveWorldInfoWithPlayer`、`SaveFormatOld`、`MapStorage`（level.dat / data 文件夹，`CompressedStreamTools.readCompressed/writeCompressed/safeWrite`）
- `net/minecraft/world/chunk/storage` → `AnvilChunkLoader#writeChunkToNBT/readChunkFromNBT`、`AnvilSaveConverter`（区块存档）
- `net/minecraft/client/multiplayer` → `ServerList#loadServerList/saveServerList`（servers.dat）
- `net/minecraft/entity` → `Entity#writeToNBT/readFromNBT`（实体持久化）
- `net/minecraft/tileentity` → `TileEntity#writeToNBT/readFromNBT`、`TileEntitySkull`（经 `NBTUtil.readGameProfileFromNBT`）
- `net/minecraft/item` → `ItemStack#writeToNBT/readFromNBT/setTagCompound`（物品 NBT 主战场）
- `net/minecraft/command` → `CommandGive`/`CommandSummon`/`CommandEntityData`/`CommandBlockData`/`CommandFill`/`CommandReplaceItem` 等（`JsonToNBT.getTagFromJson`）；`CommandTestFor`/`CommandTestForBlock`/`CommandScoreboard`（`NBTUtil.func_181123_a`）
- `net/minecraft/entity/player` → `InventoryPlayer#clearMatchingItems`（`NBTUtil.func_181123_a`）
- `net/minecraft/village` → `MerchantRecipeList`（交易 NBT 匹配 `NBTUtil.func_181123_a`）
- `net/minecraft/crash` + `net/minecraft/util` → `CrashReport#makeCrashReport`、`ReportedException`（NBT 读取失败的崩溃报告）、`MathHelper#floor_double/floor_float`、`StringUtils#isNullOrEmpty`
- `com/mojang/authlib` → `GameProfile`、`Property`（`NBTUtil` 双向转换）

## 覆盖声明

完整读取了 18/18 个文件（每个文件从第 1 行读到最后一行）。逐行精读：`NBTBase`、`NBTTagCompound`、`NBTTagList`、`CompressedStreamTools`、`NBTSizeTracker`、`NBTUtil`、`JsonToNBT` 及全部 11 个具体标签类——本包体量小，没有只做结构性浏览的类。外部调用点（`PacketBuffer`、`SaveHandler`、命令类等）通过 Grep 确认存在并精读了 `PacketBuffer.java:185-230` 片段，其余调用方仅确认了引用位置，未逐行阅读。
