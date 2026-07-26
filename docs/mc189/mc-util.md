---
area: net/minecraft/util
slug: mc-util
files: 82
lines: 9322
tier: B
---

# net/minecraft/util 架构笔记

## 定位

`net.minecraft.util` 是客户端的"公共底座"包，混杂了五类互不相干的东西：

1. **数学/几何原语**：`MathHelper`、`Vec3` / `Vec3i` / `BlockPos` / `AxisAlignedBB` / `EnumFacing` / `MovingObjectPosition`。整个世界模拟（碰撞、射线检测、方块寻址）和渲染（视锥、插值）都建立在它们之上。
2. **聊天组件系统**：`IChatComponent` 及其实现（`ChatComponentText` / `ChatComponentTranslation` / `ChatComponentScore` / `ChatComponentSelector`）、`ChatStyle`、`EnumChatFormatting`。这是聊天、死亡消息、物品名、记分板等所有富文本的数据模型，并带 JSON 序列化（与协议里的 chat 字段直接对应）。
3. **网络编解码**：`MessageSerializer` / `MessageDeserializer`（包 ID + 包体）、`MessageSerializer2` / `MessageDeserializer2`（VarInt 长度前缀分帧）、`CryptManager`（登录加密）。它们是 Netty pipeline 的实际 handler。
4. **游戏循环与输入**：`Timer`（tick/partialTicks 的来源）、`MouseHelper` / `MouseFilter`、`MovementInput(FromOptions)`、`FrameTimer`（lagometer）。
5. **杂项基础设施**：注册表（`RegistrySimple` → `RegistryNamespaced` → `RegistryNamespacedDefaultedByKey`）、`ResourceLocation`、本地化（`StringTranslate` / `StatCollector`）、专用哈希表（`IntHashMap` / `LongHashMap`）、伤害系统（`DamageSource` 族、`CombatTracker`）、`FoodStats`、`Session`、`HttpUtil`、`ScreenShotHelper` 等。

被谁调用：几乎所有包。`Minecraft` 持有 `Timer` / `MouseHelper` / `Session` / `MovingObjectPosition` 并实现 `IThreadListener`；`NetworkManager` 在 channel 初始化时装配四个 Message 编解码器；`EntityPlayerSP` 每 tick 读 `MovementInput`；`Block`/`Item` 注册表就是本包的 `RegistryNamespaced*`。

它调用谁：向下依赖极少（Guava、Gson、Netty、LWJGL shim）；少量类型反向依赖上层——`Timer` 引 `net.minecraft.client.Minecraft`，`ChatComponentScore` 引 `net.minecraft.server.MinecraftServer`，`FoodStats`/`DamageSource`/`CombatTracker` 引 entity 包，`ScreenShotHelper` 引 renderer 包。

如果它消失：没有 `MathHelper.sin` 表和 `BlockPos` 世界无法寻址；没有 Message 编解码器网络层无法分帧；没有 `Timer` 游戏循环无法把真实时间换算成 tick；没有 `IChatComponent` 所有文本渲染和协议 chat 字段全部失效。属于"抽掉即全塌"的包。

## 类清单

| 类名 | 行数 | extends / implements | 一句话职责 |
|---|---|---|---|
| AxisAlignedBB | 412 | — | 不可变轴对齐包围盒；碰撞偏移计算与射线求交 |
| BlockPos | 366 | extends Vec3i | 不可变方块坐标；long 序列化、方向偏移、盒式遍历；含 MutableBlockPos |
| Cartesian | 181 | — | 笛卡尔积迭代器工具（方块状态排列组合用） |
| ChatAllowedCharacters | 32 | — | 聊天/命名合法字符过滤（禁 §、控制字符） |
| ChatComponentProcessor | 86 | — | 服务端侧解析 score/selector 组件为具体文本 |
| ChatComponentScore | 105 | extends ChatComponentStyle | 记分板分数占位组件 |
| ChatComponentSelector | 69 | extends ChatComponentStyle | 实体选择器（@p 等）占位组件 |
| ChatComponentStyle | 148 | implements IChatComponent | 组件基类：siblings 列表 + 样式继承 + 文本拼接 |
| ChatComponentText | 67 | extends ChatComponentStyle | 纯文本组件 |
| ChatComponentTranslation | 277 | extends ChatComponentStyle | 翻译键组件；惰性按当前语言展开 children |
| ChatComponentTranslationFormatException | 19 | extends IllegalArgumentException | 翻译格式串解析异常 |
| ChatStyle | 643 | — | 文本样式（颜色/粗斜体/click/hover/insertion），父链继承；含 Gson Serializer |
| ClassInheritanceMultiMap | 149 | extends AbstractSet\<T\> | 按类层次索引的集合（chunk 内实体按类型查询） |
| CombatEntry | 59 | — | 单次受击记录（伤害源、血量、坠落距离） |
| CombatTracker | 263 | — | 实体受击历史；生成死亡消息、判定击杀归属 |
| CryptManager | 222 | — | 登录握手加密：AES 密钥生成、RSA 加解密、serverId 哈希、AES/CFB8 流加密 Cipher |
| DamageSource | 282 | — | 伤害类型描述符 + 静态单例（inFire/fall/outOfWorld 等）与工厂方法 |
| EnchantmentNameParts | 44 | — | 附魔台"天书"随机名生成 |
| EntityDamageSource | 62 | extends DamageSource | 有直接实体来源的伤害 |
| EntityDamageSourceIndirect | 40 | extends EntityDamageSource | 间接来源伤害（箭/火球，区分弹射物与射手） |
| EntitySelectors | 69 | — | 常用实体 Predicate 常量（存活、非旁观等） |
| EnumChatFormatting | 177 | enum | § 格式化代码枚举（16 色 + 5 样式 + RESET） |
| EnumFacing | 478 | enum, implements IStringSerializable | 六方向枚举 + Axis/AxisDirection/Plane 子枚举 |
| EnumParticleTypes | 126 | enum | 42 种粒子的名字/ID/参数数量 |
| EnumTypeAdapterFactory | 67 | implements TypeAdapterFactory | Gson 枚举小写名适配器 |
| EnumWorldBlockLayer | 21 | enum | 渲染层（SOLID/CUTOUT_MIPPED/CUTOUT/TRANSLUCENT） |
| FoodStats | 162 | — | 玩家饥饿/饱和/消耗值逻辑与 NBT 读写 |
| FrameTimer | 88 | — | 240 帧耗时环形缓冲（lagometer 数据源） |
| HttpUtil | 342 | — | HTTP GET/POST、资源包下载线程池、LAN 端口探测 |
| IChatComponent | 294 | interface extends Iterable\<IChatComponent\> | 聊天组件接口；内嵌 Serializer（Gson JSON ↔ 组件树） |
| IJsonSerializable | 13 | interface | fromJson / getSerializableElement 契约 |
| IObjectIntIterable | 5 | interface extends Iterable\<T\> | 标记接口：可按 int ID 迭代 |
| IProgressUpdate | 27 | interface | 加载/保存进度回调 |
| IRegistry | 11 | interface extends Iterable\<V\> | getObject / putObject 注册表契约 |
| IStringSerializable | 6 | interface | getName()（方块状态属性序列化用） |
| IThreadListener | 10 | interface | addScheduledTask / isCallingFromMinecraftThread（线程封送） |
| ITickable | 9 | interface | update()（TileEntity 等每 tick 回调） |
| IntHashMap | 282 | — | int 键开链哈希表（实体 ID 查找等） |
| IntegerCache | 24 | — | 0..65534 的 Integer 装箱缓存 |
| JsonSerializableSet | 44 | extends ForwardingSet\<String\> implements IJsonSerializable | 可 JSON 化的字符串集合（成就页等） |
| JsonUtils | 337 | — | JsonObject 字段的带错误信息取值工具 |
| LazyLoadBase | 20 | abstract | 惰性单值加载器 |
| LoggingPrintStream | 35 | extends PrintStream | 把 System.out/err 重定向进 log4j |
| LongHashMap | 293 | — | long 键开链哈希表（chunk 坐标 → chunk） |
| MapPopulator | 33 | — | 按 keys/values 两个 Iterable 填 Map |
| MathHelper | 564 | — | sin 表、floor/clamp/wrapAngle、快速 atan2/rsqrt、颜色换算 |
| Matrix4f | 29 | extends org.lwjgl.util.vector.Matrix4f | 从 float[16] 构造矩阵的薄包装（shim 类型） |
| MessageDeserializer | 61 | extends ByteToMessageDecoder | 帧内 VarInt 包 ID → Packet 实例并 readPacketData |
| MessageDeserializer2 | 55 | extends ByteToMessageDecoder | VarInt 长度前缀拆帧（"splitter"） |
| MessageSerializer | 57 | extends MessageToByteEncoder\<Packet\> | Packet → 包 ID + writePacketData |
| MessageSerializer2 | 27 | extends MessageToByteEncoder\<ByteBuf\> | 写 VarInt 长度前缀（"prepender"，上限 3 字节） |
| MinecraftError | 5 | extends Error | 主动关闭游戏用的哨兵 Error |
| MouseFilter | 33 | — | 鼠标平滑滤波（cinematic camera） |
| MouseHelper | 38 | — | 鼠标捕获/释放与逐帧 delta 读取（经 LWJGL2 shim） |
| MovementInput | 20 | — | 移动输入数据载体（strafe/forward/jump/sneak） |
| MovementInputFromOptions | 48 | extends MovementInput | 从 GameSettings 按键状态采样移动输入 |
| MovingObjectPosition | 65 | — | 射线命中结果（MISS/BLOCK/ENTITY + 位置/面/实体） |
| ObjectIntIdentityMap | 42 | implements IObjectIntIterable\<T\> | 对象 ↔ int ID 双向映射（IdentityHashMap + List） |
| RegistryDefaulted | 20 | extends RegistrySimple | 查不到返回默认对象的注册表 |
| RegistryNamespaced | 70 | extends RegistrySimple, implements IObjectIntIterable | 名字 + int ID 双索引注册表（BiMap） |
| RegistryNamespacedDefaultedByKey | 52 | extends RegistryNamespaced | 带默认键的命名注册表（Block/Item 用，air 兜底） |
| RegistrySimple | 60 | implements IRegistry | HashMap 注册表基类 |
| ReportedException | 32 | extends RuntimeException | 携带 CrashReport 的异常 |
| ResourceLocation | 85 | — | domain:path 资源标识（默认 minecraft 域） |
| Rotations | 76 | — | 三轴角度组（盔甲架姿态），NBTTagList 读写 |
| ScreenShotHelper | 152 | — | 读回帧缓冲像素并写 PNG 截图 |
| Session | 90 | — | 登录会话（username/uuid/token → GameProfile） |
| StatCollector | 53 | — | StringTranslate 的静态门面（translateToLocal 等） |
| StringTranslate | 131 | — | 语言表：内置 en_US.lang 加载 + replaceWith 热替换 |
| StringUtils | 32 | — | tick→mm:ss、去 § 码、isNullOrEmpty |
| ThreadSafeBoundList | 64 | — | 读写锁保护的定长环形列表（快照统计用） |
| Timer | 110 | — | 真实时间 → elapsedTicks / renderPartialTicks 换算 |
| Tuple | 29 | — | 二元组 |
| TupleIntJsonSerializable | 36 | — | (int, IJsonSerializable) 二元组（统计数据） |
| Util | 42 | — | 操作系统探测 + FutureTask 同步执行 |
| Vec3 | 212 | — | 不可变 double 三维向量；旋转、插值、点/叉积 |
| Vec3i | 124 | implements Comparable\<Vec3i\> | 不可变 int 三维向量基类 |
| Vec4b | 71 | — | 四字节值对象（地图图标） |
| Vector3d | 18 | — | 可变 double 三元组（ActiveRenderInfo 用） |
| WeightedRandom | 65 | — | 权重随机选择（含内部类 Item） |
| WeightedRandomChestContent | 95 | extends WeightedRandom.Item | 宝箱/发射器随机战利品生成 |
| WeightedRandomFishable | 60 | extends WeightedRandom.Item | 钓鱼战利品条目（损耗/附魔） |

## 核心类详解

### Timer（`Timer.java`）
- 关键字段：`float ticksPerSecond`（Timer.java:8，构造时传 20.0F）；`public int elapsedTicks`（:18）；`public float renderPartialTicks`（:24）；`public float timerSpeed = 1.0F`（:30）；`public float elapsedPartialTicks`（:35）；`private double timeSyncAdjustment = 1.0D`（:53）。
- 关键方法：`public void updateTimer()`（Timer.java:65）——用 `Minecraft.getSystemTime()` 与 `System.nanoTime()` 双时钟校准，把流逝的真实时间累加进 `elapsedPartialTicks`，取整得 `elapsedTicks`（上限 10，Timer.java:103-106），余数即 `renderPartialTicks`。
- 调用方：`Minecraft` 持有 `private Timer timer = new Timer(20.0F)`（Minecraft.java:223），`runGameLoop` 每帧调 `this.timer.updateTimer()`（Minecraft.java:1091/1096），随后按 `elapsedTicks` 次数调 `runTick()`。主线程。

### MathHelper（`MathHelper.java`）
- 关键字段：`private static final float[] SIN_TABLE = new float[65536]`（MathHelper.java:13，static 块 :546-549 预填 sin 值）；`multiplyDeBruijnBitPosition`（:551，De Bruijn log2 查表）。
- 关键方法（逐字）：
  - `public static float sin(float p_76126_0_)`（:30）/ `public static float cos(float value)`（:38）——查表三角函数，精度 2π/65536。
  - `public static int floor_double(double value)`（:73）、`public static float clamp_float(float num, float min, float max)`（:131）、`public static float wrapAngleTo180_float(float value)`（:212）。
  - `public static double atan2(double p_181159_0_, double p_181159_2_)`（:411）与 `public static double func_181161_i(double p_181161_0_)`（:475，快速逆平方根，magic number `6910469410427058090L`）。
  - `public static long getCoordinateRandom(int x, int y, int z)`（:392，坐标伪随机种子）。
- 调用方：全代码库热路径（实体移动、渲染插值、光照、噪声）。无状态，任意线程安全（只读表）。

### BlockPos（`BlockPos.java`）
- 关键常量：`NUM_X_BITS = 1 + MathHelper.calculateLogBaseTwo(MathHelper.roundUpToPowerOfTwo(30000000))`（BlockPos.java:11，= 26）；`NUM_Y_BITS = 64 - NUM_X_BITS - NUM_Z_BITS`（:13，= 12）；`X_MASK/Y_MASK/Z_MASK`（:16-18）。
- 关键方法：`public long toLong()`（:200）与 `public static BlockPos fromLong(long serialized)`（:208）——26/12/26 位打包，是 S22PacketMultiBlockChange、chunk 存储等的坐标序列化格式；`public BlockPos offset(EnumFacing facing, int n)`（:184）；`public static Iterable<BlockPos> getAllInBox(BlockPos from, BlockPos to)`（:216）；可变变体 `getAllInBoxMutable`（:269）复用同一 `MutableBlockPos` 实例（:324-365）以省分配。
- 调用方：World/Chunk/Block 全部寻址接口。注意 `add(0,0,0)` / `offset(f,0)` 返回 `this`（:50/:58/:186）。

### AxisAlignedBB（`AxisAlignedBB.java`）
- 字段全 `public final double minX..maxZ`（AxisAlignedBB.java:5-10），不可变。
- 关键方法：`public double calculateXOffset(AxisAlignedBB other, double offsetX)`（:127，Y/Z 重叠时算 X 向允许位移，实体碰撞核心；同族 `calculateYOffset` :163、`calculateZOffset` :199）；`public boolean intersectsWith(AxisAlignedBB other)`（:233）；`public MovingObjectPosition calculateIntercept(Vec3 vecA, Vec3 vecB)`（:271，六面求交取最近点并给出 `EnumFacing`）。
- 调用方：`Entity.moveEntity` 碰撞解算；`EntityRenderer.getMouseOver` 对实体包围盒调 `calculateIntercept`（EntityRenderer.java:463）。

### EnumFacing（`EnumFacing.java`）
- 枚举序：`DOWN(0,1,-1,...) ... EAST(5,4,3,...)`（EnumFacing.java:12-17），D-U-N-S-W-E；`HORIZONTALS` 序为 S-W-N-E（:38）。
- 关键方法：`public static EnumFacing getFront(int index)`（:265）；`public static EnumFacing getHorizontal(int p_176731_0_)`（:273）；`public static EnumFacing fromAngle(double angle)`（:281，yaw→水平朝向）；`public int getFrontOffsetX()`（:223）等偏移量；`public EnumFacing getOpposite()`（:79）。子枚举 `Axis`（:358）、`AxisDirection`（:421）、`Plane`（:445，可迭代/可当 Predicate）。
- 调用方：方块状态（`PropertyDirection`）、包（挖掘面）、渲染面剔除。协议中方块面的 int 即 `getIndex()`。

### IChatComponent / ChatComponentStyle / ChatStyle
- `ChatComponentStyle.appendSibling`（ChatComponentStyle.java:17）把子组件样式的 parent 指向自己（:19），形成样式继承树；`getFormattedText()`（:87）遍历深拷贝迭代器，对每段输出 `getFormattingCode()` + 文本 + `EnumChatFormatting.RESET`（:93-95）。
- `ChatStyle` 每个属性为 nullable，`getColor()` 等（ChatStyle.java:129）为空则查 `getParent()`（:353，无 parent 落到 `rootStyle` :34）。`isEmpty()`（:177）注意**不含 insertion**。
- `IChatComponent.Serializer`（IChatComponent.java:57）是协议 chat JSON 的唯一编解码器：`public static String componentToJson(IChatComponent component)`（:275）、`public static IChatComponent jsonToComponent(String json)`（:280）；识别 `text` / `translate`+`with` / `score` / `selector` / `extra` 键（:100-174）。
- `ChatComponentTranslation.ensureInitialized()`（ChatComponentTranslation.java:38）比较 `StatCollector.getLastTranslationUpdateTimeInMilliseconds()` 判断语言是否热更过，失效则重展开 children（:50-69，失败回退 en_US fallback）。
- 调用方：`GuiNewChat` 渲染、S02PacketChat、死亡消息、物品 hover。

### MessageSerializer / MessageDeserializer / *2（Netty 编解码四件套）
- 装配位置：`NetworkManager.java:379` —— pipeline 顺序 `timeout → splitter(MessageDeserializer2) → decoder(MessageDeserializer(CLIENTBOUND)) → prepender(MessageSerializer2) → encoder(MessageSerializer(SERVERBOUND)) → packet_handler`。压缩开启后 NetworkManager 会在 splitter/prepender 之后插 decompress/compress（见 network 包）。
- `MessageDeserializer2.decode`（MessageDeserializer2.java:13）：最多读 3 字节 VarInt，长度不足则 `resetReaderIndex()` 等待；超 21-bit 抛 `CorruptedFrameException("length wider than 21-bit")`（:53）。
- `MessageDeserializer.decode`（MessageDeserializer.java:29）：`readVarIntFromBuffer()` 取包 ID，经 channel attr `NetworkManager.attrKeyConnectionState` 查 `EnumConnectionState.getPacket(direction, i)`（:35），空 ID 抛 `IOException("Bad packet id " + i)`；`readPacketData` 后若有剩余字节抛"was larger than I expected"（:45-48）。
- `MessageSerializer.encode`（MessageSerializer.java:29）：查 `getPacketId`，null 抛 `IOException("Can't serialize unregistered packet")`（:40）；注意 `writePacketData` 的异常只被 `logger.error((Object)throwable)` 吞掉（:51-54），会发出半截包体。
- `MessageSerializer2.encode`（MessageSerializer2.java:10）：长度 VarInt 超 3 字节抛 `IllegalArgumentException`（:17）。
- 线程：全部运行在 **Netty EventLoop**，不得在此触碰世界状态。

### CryptManager（`CryptManager.java`）
- `public static SecretKey createNewSharedKey()`（CryptManager.java:36，AES-128）；`public static byte[] getServerIdHash(String serverId, PublicKey publicKey, SecretKey secretKey)`（:72，SHA-1 拼接，Mojang session 认证哈希）；`public static Cipher createNetCipherInstance(int opMode, Key key)`（:209，`AES/CFB8/NoPadding`，IV = key 本身）。
- 调用方：`NetHandlerLoginClient` 处理 S01PacketEncryptionRequest 时生成密钥并回 C01PacketEncryptionResponse；`NetworkManager.enableEncryption` 用 :209 的 Cipher 装入加解密 handler。Netty EventLoop / 登录线程。

### RegistryNamespaced / RegistryNamespacedDefaultedByKey
- `RegistryNamespaced` 三索引：`registryObjects`（HashBiMap，`createUnderlyingMap()` RegistryNamespaced.java:24）、`inverseObjectRegistry`（:11/:15）、`underlyingIntegerMap`（ObjectIntIdentityMap，:10）。`public void register(int id, K key, V value)`（:18）；`public V getObjectById(int id)`（:61）。
- `RegistryNamespacedDefaultedByKey.getObjectById`（RegistryNamespacedDefaultedByKey.java:47）查不到回退 `defaultValue`——`Block.blockRegistry`（默认 air）与 `Item.itemRegistry` 就是这个类型，协议里的方块/物品 int ID 都经它解析。
- 初始化时机：`Block.registerBlocks()` / `Item.registerItems()` 在 `Bootstrap.register()` 中调用，须早于任何世界/物品逻辑。

### StringTranslate / StatCollector
- `StringTranslate` 构造器（StringTranslate.java:35）从 classpath `/assets/minecraft/lang/en_US.lang` 读默认表，`numericVariablePattern`（:19）把 `%d`/`%.2f` 等统一替换为 `%s`（:50）。`public static synchronized void replaceWith(Map<String, String> p_135063_0_)`（:75）由 `LanguageManager` 在资源重载/切语言时调用，更新 `lastUpdateTimeInMilliseconds`，从而使所有 `ChatComponentTranslation` 缓存失效。
- `StatCollector` 是静态门面：`public static String translateToLocal(String key)`（StatCollector.java:16）、`public static boolean canTranslate(String key)`（:41）。

### CombatTracker / DamageSource 族
- `EntityLivingBase` 持 `private final CombatTracker _combatTracker = new CombatTracker(this)`（EntityLivingBase.java:59），受伤时 `attackEntityFrom` → `getCombatTracker().trackDamage(damageSrc, f1, damageAmount)`（EntityLivingBase.java:1290）。
- `public void trackDamage(DamageSource damageSrc, float healthIn, float damageAmount)`（CombatTracker.java:56）记录 `CombatEntry` 并在首次被活体攻击时 `this.fighter.sendEnterCombat()`（:70）；`public IChatComponent getDeathMessage()`（:74）组合 fall-assist 逻辑；`public void reset()`（:236）按 100/300 tick 超时清空并 `sendEndCombat()`。
- `DamageSource` 静态单例在类加载时构造（DamageSource.java:12-26）；布尔标志（isUnblockable/fireDamage/projectile/…）经链式 setter 配置，`getDeathMessage`（:219）按 `"death.attack." + this.damageType` 查翻译键。

## 时序与生命周期

- **类加载期（主线程，Minecraft 构造之前/期间）**：`MathHelper` static 块填 65536 项 sin 表（MathHelper.java:544-563）；`EnumFacing`/`EnumChatFormatting`/`EnumParticleTypes`/`Session.Type` static 块建 lookup 表；`StringTranslate.instance` 读 en_US.lang；`DamageSource` 静态单例构造；`IntegerCache` 填 65535 个 Integer。
- **启动期**：`Minecraft.startGame` 里 `this.mouseHelper = new MouseHelper()`（Minecraft.java:534）；`Timer` 随 `Minecraft` 字段初始化（:223）；Block/Item 注册表在 Bootstrap 阶段灌入。
- **每帧（主线程 runGameLoop）**：`timer.updateTimer()`（Minecraft.java:1091/1096）→ 决定本帧跑几个 tick；`EntityRenderer` 相机更新时 `this.mc.mouseHelper.mouseXYChange()`（EntityRenderer.java:1096）读鼠标 delta；`FrameTimer.addFrame` 记帧耗时（debug lagometer 开启时）。
- **每 tick（主线程 runTick）**：`EntityPlayerSP.onLivingUpdate` 调 `this.movementInput.updatePlayerMoveState()`（EntityPlayerSP.java:786）采样键盘；`EntityPlayer.onUpdate` → `this.foodStats.onUpdate(this)`（EntityPlayer.java:395，客户端仅本地/集成服有效）；`CombatTracker.reset()` 超时判定；`ITickable.update()` 由 World 对 TileEntity 逐个调用。
- **Netty EventLoop**：Message(De)Serializer(2) 的 encode/decode 在 channel 线程执行；解出的 Packet 经 `PacketThreadUtil.checkThreadAndEnqueue` 用 `IThreadListener.addScheduledTask`（PacketThreadUtil.java:11）封送回主线程处理。`Minecraft implements IThreadListener`（Minecraft.java:188）。
- **下载线程池**：`HttpUtil.field_180193_a`（HttpUtil.java:33，守护线程 "Downloader %d"）执行资源包下载，进度经 `IProgressUpdate` 回调（在下载线程上调用）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void updateTimer()` | Timer.java:65 | 每帧 runGameLoop 开头（Minecraft.java:1091） | 改 `timerSpeed` 实现慢动作/加速；覆写可自定义 tick 速率 | `elapsedTicks` 被硬夹到 10（:103-106）；partialTicks 供渲染插值，篡改会导致画面抖动 |
| `public void updatePlayerMoveState()` | MovementInputFromOptions.java:14 | 每 tick `EntityPlayerSP.onLivingUpdate`（EntityPlayerSP.java:786） | 移动/自动走/键位改写的最干净入口——替换 `EntityPlayerSP.movementInput` 实例即可 | sneak 会把 strafe/forward 乘 0.3（:42-46）；值随后被抄进 `moveStrafing/moveForward`（EntityPlayerSP.java:696-697）并上报 C0CPacketInput |
| `protected void decode(ChannelHandlerContext p_decode_1_, ByteBuf p_decode_2_, List<Object> p_decode_3_) throws IOException, InstantiationException, IllegalAccessException, Exception` | MessageDeserializer.java:29 | Netty EventLoop，每个完整帧到达时 | 观察/丢弃/伪造入站包（在 Packet 实例化之后、主线程处理之前） | 运行在 EventLoop，禁止直接碰世界；不 add 进 list 即静默丢包；剩余字节校验（:45）会对畸形包抛 IOException 断线 |
| `protected void encode(ChannelHandlerContext p_encode_1_, Packet p_encode_2_, ByteBuf p_encode_3_) throws IOException, Exception` | MessageSerializer.java:29 | Netty EventLoop，每个出站包 | 拦截/改写/记录出站包（最后一道闸） | `writePacketData` 异常被吞（:51-54）会发出截断包体；未注册包抛 IOException |
| `protected void decode(ChannelHandlerContext p_decode_1_, ByteBuf p_decode_2_, List<Object> p_decode_3_) throws Exception` | MessageDeserializer2.java:13 | Netty EventLoop，字节流分帧 | 自定义分帧/流量统计 | 半包时必须 `resetReaderIndex()` 返回；帧长上限 2^21-1 |
| `public void grabMouseCursor()` / `public void ungrabMouseCursor()` | MouseHelper.java:17 / :27 | 进入世界 `setIngameFocus`（Minecraft.java:1455）/ 打开 GUI `setIngameNotInFocus`（:1471） | 感知"进入/离开游戏视角"；替换实例可接管鼠标捕获策略（LWJGL3 下经 shim `Mouse.setGrabbed`） | grab 时清零 delta；ungrab 把光标挪到窗口中心，多显示器/HiDPI 下依赖 shim 的 Display 尺寸换算 |
| `public void mouseXYChange()` | MouseHelper.java:33 | 每帧相机更新（EntityRenderer.java:1096） | 视角控制（aim 辅助、回放系统）：改 `deltaX/deltaY` 即改视角输入 | 每帧调用会消费 shim 累积的 DX/DY，别处再读得 0 |
| `ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule)` | IThreadListener.java:7 | 任意线程向主线程投递任务（Packet 处理封送：PacketThreadUtil.java:11） | 在主线程安全执行任意逻辑的标准入口；也可包装以观察全部封送任务 | Minecraft 实现里若已在主线程则同步执行；任务内异常会打进 crash 流程 |
| `public IChatComponent appendSibling(IChatComponent component)` | ChatComponentStyle.java:17 | 所有组件树构建处 | 文本改写/过滤（配合替换 Serializer 可全局劫持 chat JSON） | 会 set parentStyle（:19），拆树时记得断开否则样式泄漏 |
| `public static IChatComponent jsonToComponent(String json)` | IChatComponent.java:280 | S02PacketChat 等所有协议 chat 字段解码 | 单点拦截所有服务器下发富文本（反注入、翻译、日志） | GSON 静态单例（:285-292）；恶意 JSON 深嵌套可栈溢出 |
| `public void trackDamage(DamageSource damageSrc, float healthIn, float damageAmount)` | CombatTracker.java:56 | `EntityLivingBase.attackEntityFrom`（EntityLivingBase.java:1290） | 观察受击事件（来源、伤害量、坠落距离）——做 HUD 伤害指示器的现成挂点 | 客户端对远程实体只有部分伤害信息；`reset()` 会按超时清空历史 |
| `public void onUpdate(EntityPlayer player)` | FoodStats.java:41 | 每 tick `EntityPlayer.onUpdate`（EntityPlayer.java:395） | 饥饿/回血逻辑观察或改写（仅本地权威时有效） | 多人模式下真实值由服务器 S06 包同步，本地改动会被覆盖 |
| `public IChatComponent getDeathMessage()` | CombatTracker.java:74 | 实体死亡生成死亡消息时（服务端/集成服） | 自定义死亡消息 | 依赖 combatEntries 未被 reset |
| `public V getObjectById(int id)` | RegistryNamespacedDefaultedByKey.java:47 | 协议解码方块/物品 ID、世界存储读取 | 注入自定义 Block/Item 映射；观察未知 ID | 查不到静默回退默认值（air），不会报错——排查协议不匹配时注意 |
| `public static synchronized void replaceWith(Map<String, String> p_135063_0_)` | StringTranslate.java:75 | LanguageManager 资源重载/切换语言 | 注入自定义翻译键（无需改 lang 文件） | 全量 clear+putAll；时间戳更新会使所有 ChatComponentTranslation 缓存重建 |
| `public static IChatComponent saveScreenshot(File gameDirectory, String screenshotName, int width, int height, Framebuffer buffer)` | ScreenShotHelper.java:47 | F2 键分发（Minecraft.dispatchKeypresses） | 自定义截图管线（水印、异步写盘、上传） | 必须在 GL 线程调用（glReadPixels/glGetTexImage）；复用静态 pixelBuffer，非线程安全 |
| `public void addFrame(long runningTime)` | FrameTimer.java:22 | 每帧（debug 图表数据采集） | 帧耗时监控/自定义性能 HUD | 环形缓冲 240 项，`getFrames()` 直接暴露内部数组 |
| `void update()` | ITickable.java:8 | World tick 循环对每个 TileEntity | 实现该接口即获得 per-tick 回调 | 主线程；卸载 chunk 后不再调用 |
| `protected void encode(ChannelHandlerContext p_encode_1_, ByteBuf p_encode_2_, ByteBuf p_encode_3_) throws Exception` | MessageSerializer2.java:10 | Netty EventLoop，出站帧前缀 | 出站字节级统计/整形 | 长度 VarInt 限 3 字节；压缩启用后其输入是压缩帧 |
| `public MovingObjectPosition calculateIntercept(Vec3 vecA, Vec3 vecB)` | AxisAlignedBB.java:271 | `EntityRenderer.getMouseOver` 实体拾取（EntityRenderer.java:463）及弹射物碰撞 | reach 修改、命中判定观察 | 返回的 MovingObjectPosition 无 blockPos（用 :24 的两参构造，blockPos 为 ORIGIN） |

## 数据与协议

**帧格式（MessageSerializer2 / MessageDeserializer2）**

| 字段 | 类型 | 读写方法 | 含义 |
|---|---|---|---|
| length | VarInt（≤3 字节） | `PacketBuffer.writeVarIntToBuffer` / `readVarIntFromBuffer`（MessageSerializer2.java:23 / MessageDeserializer2.java:34） | 后续包体字节数，上限 2^21-1 |
| body | byte[length] | `writeBytes` / `readBytes` | 一个完整（可能已压缩的）packet 帧 |

**包体格式（MessageSerializer / MessageDeserializer）**

| 字段 | 类型 | 读写方法 | 含义 |
|---|---|---|---|
| packetId | VarInt | MessageSerializer.java:45 / MessageDeserializer.java:34 | 在当前 `EnumConnectionState` + 方向下的包 ID |
| payload | 包自定义 | `Packet.writePacketData` / `readPacketData` | 各包字段 |

**chat JSON（IChatComponent.Serializer，IChatComponent.java:57-293）**

| JSON 键 | 类型 | 对应组件/字段 | 说明 |
|---|---|---|---|
| `text` | string | ChatComponentText | 纯文本；空样式无 siblings 时序列化为裸 string（:198-201） |
| `translate` / `with` | string / array | ChatComponentTranslation(key, args) | with 里的纯文本子组件会被降级为 String（:117-125） |
| `score` {`name`,`objective`,`value`} | object | ChatComponentScore | name+objective 必需（:139-142） |
| `selector` | string | ChatComponentSelector | 实体选择器串 |
| `extra` | array | siblings | 空数组抛 JsonParseException（:165-168） |
| `bold`/`italic`/`underlined`/`strikethrough`/`obfuscated` | boolean | ChatStyle 同名字段 | ChatStyle.Serializer（ChatStyle.java:484） |
| `color` | string | ChatStyle.color | 经 EnumTypeAdapterFactory 小写名 |
| `clickEvent`/`hoverEvent` {`action`,`value`} | object | ClickEvent/HoverEvent | `shouldAllowInChat()` 过滤非法 action（ChatStyle.java:545/:562） |
| `insertion` | string | ChatStyle.insertion | shift-click 插入文本 |

**NBT**

| 类 | 字段 → NBT 键 | 类型 | 方法 |
|---|---|---|---|
| FoodStats | foodLevel→`foodLevel`, foodTimer→`foodTickTimer`, foodSaturationLevel→`foodSaturationLevel`, foodExhaustionLevel→`foodExhaustionLevel` | int/int/float/float | `readNBT`（FoodStats.java:94，须有 `foodLevel` 键且类型 99）/ `writeNBT`（:108） |
| Rotations | x,y,z | NBTTagList[3×NBTTagFloat] | 构造器（Rotations.java:24）/ `writeToNBT`（:31） |

**BlockPos long 打包**（BlockPos.java:200-214）：`X[26bit] << 38 | Y[12bit] << 26 | Z[26bit]`，与协议 Position 字段一致。

**登录加密（CryptManager）**：serverIdHash = SHA-1(serverId(ISO-8859-1) ‖ secretKey ‖ publicKey.encoded)（CryptManager.java:72-83）；流加密 `AES/CFB8/NoPadding`，IV=key（:209-221）；shared key 用服务器 RSA 公钥加密回传。

**语言文件**：`/assets/minecraft/lang/en_US.lang`，`key=value` 行格式，`#` 开头为注释（StringTranslate.java:39-54），数值占位符统一转为 `%s`。

## 不变量与陷阱

- **不可变约定**：`Vec3` / `Vec3i` / `BlockPos` / `AxisAlignedBB` / `ResourceLocation` / `Rotations` 全部不可变，所有运算返回新对象。唯一例外 `BlockPos.MutableBlockPos`——`getAllInBoxMutable` 每次 `next()` 返回**同一个实例**（BlockPos.java:277-318），绝不能把它存进集合或跨迭代持有。
- `MutableBlockPos` 用字段遮蔽（自己的 `private int x,y,z` 覆盖 getter，父类字段恒为 0，BlockPos.java:326-356）；任何绕过 getter 直接读 `Vec3i` 字段的代码会拿到 0。
- `Vec3` 构造器把 `-0.0D` 规格化为 `0.0D`（Vec3.java:16-29）。
- `AxisAlignedBB(BlockPos pos1, BlockPos pos2)` **不做 min/max 归一化**（AxisAlignedBB.java:22-30），与六 double 构造器（:12，做归一化）行为不同；传反序坐标会得到反向盒。
- `ChatStyle.hashCode()` 对字段直接调 `.hashCode()`，字段为 null（常态）时 **NPE**（ChatStyle.java:430-442）；同理 `ChatComponentStyle.hashCode()` 在 `style == null` 时 NPE（ChatComponentStyle.java:139-142）。别把组件放进 HashSet/HashMap 键。
- `ChatStyle.isEmpty()` 不检查 `insertion`（ChatStyle.java:177-180），带 insertion 的"空"样式会在序列化时被 `Serializer.serialize` 的 isEmpty 短路丢掉（:580-583）。
- `MessageSerializer.encode` 吞掉 `writePacketData` 的 Throwable（MessageSerializer.java:51-54）：包体写一半失败仍会带着正确包 ID 发出，服务器端表现为"包比预期短"。排查断线优先怀疑这里。
- 网络编解码器全在 **Netty EventLoop** 执行；世界/实体只能在主线程碰，跨线程用 `IThreadListener.addScheduledTask`。`StringTranslate` 的 public 方法 `synchronized`，`ChatComponentTranslation.ensureInitialized` 有自己的 `syncLock`——这两处是本包仅有的线程安全设施；`RegistrySimple`/`IntHashMap`/`LongHashMap`/`ClassInheritanceMultiMap` 都**不是**线程安全的。
- `ClassInheritanceMultiMap.field_181158_a` 是 **static** 的全局类集合（ClassInheritanceMultiMap.java:16）：任何一个实例上做过 `getByClass(X)` 都会让之后新建的所有实例预建 X 的索引；卸载类加载器场景会泄漏。
- `RegistryNamespaced` 的构造依赖 `createUnderlyingMap()` 返回 `HashBiMap`（RegistryNamespaced.java:15 直接 cast `(BiMap)this.registryObjects`）；子类若覆写该方法返回普通 Map 会 CCE。
- `RegistryNamespacedDefaultedByKey.getObjectById` 静默兜底默认值（air），未知 ID 不报错——协议版本不匹配时症状是"全是空气"而非异常。
- `IntegerCache.getInteger(0)` 走 `Integer.valueOf` 分支（条件是 `value > 0`，IntegerCache.java:12），行为正确但和缓存路径不同；缓存上限 65534。
- `Timer.elapsedTicks` 上限 10：卡顿超过 500ms 后游戏时间会慢于真实时间（追帧上限）。`timerSpeed` 影响 tick 与 partialTicks 两者。
- `EnumFacing.getFront(index)` 用 `abs_int(index % 6)`（EnumFacing.java:267）：负数输入不会越界但映射不对称（-1 → 1 → UP），别依赖负 index。
- **LWJGL3/JDK25 移植点**：`MouseHelper` 仍然 import `org.lwjgl.input.Mouse` / `org.lwjgl.opengl.Display`（MouseHelper.java:3-4），`Matrix4f` extends `org.lwjgl.util.vector.Matrix4f`（Matrix4f.java:3）——这些类在本仓库由 `lwjgl2-shim/src/main/java/org/lwjgl/...` 提供（GLFW 之上的 LWJGL2 API 仿真），并非真 LWJGL2。改鼠标行为要去 shim 的 `Mouse.java`，不要在这里绕。
- `ScreenShotHelper` 直接调 `GL11.glGetTexImage` / `GL11.glReadPixels`（ScreenShotHelper.java:75/:79），必须在拥有 GL context 的主线程调用；静态 `pixelBuffer`/`pixelValues` 复用（:62-66），并发调用会串数据。
- `CryptManager.generateKeyPair` 用 RSA-1024（CryptManager.java:58）、serverIdHash 用 SHA-1——协议规定如此，JDK25 下若安全策略禁用弱算法会在登录路径抛异常；`decodePublicKey` 失败返回 null（吞异常但有 `LOGGER.error("Public key reconstitute failed!")` 日志，:111-130）。
- `HttpUtil.post` 用明文 `HttpURLConnection` 且信任 URL 参数；`Session.Type.setSessionType` 对未知类型返回 null（Session.java:78-81），`Session` 构造未校验。
- `StringTranslate` 构造器 `catch (Exception var7) { ; }` 静默吞掉资源缺失（StringTranslate.java:58-61）：en_US.lang 不在 classpath 时所有翻译键原样显示，无任何日志。
- `ThreadSafeBoundList.func_152756_c()` 在读锁**外**分配数组、锁内拷贝（ThreadSafeBoundList.java:44-63），size 读取（field_152762_d）本身不在锁内，极端并发下快照长度可能过期——只用于统计展示，可容忍。
- `FrameTimer.getLagometerValue` 的除数 `1.6666666E7D`（FrameTimer.java:51）即 60 FPS 的每帧纳秒数，是 lagometer 刻度基准。

## 交叉引用

- net.minecraft.client → `Minecraft#runGameLoop`（调 `Timer#updateTimer`，Minecraft.java:1091）、`Minecraft#setIngameFocus`（调 `MouseHelper#grabMouseCursor`）、`Minecraft implements IThreadListener`（Minecraft.java:188）
- net.minecraft.client.entity → `EntityPlayerSP#onLivingUpdate`（调 `MovementInput#updatePlayerMoveState`，EntityPlayerSP.java:786；读 jump/sneak 发 C0CPacketInput，:177）
- net.minecraft.client.renderer → `EntityRenderer#getMouseOver`（调 `AxisAlignedBB#calculateIntercept`，EntityRenderer.java:463）、`EntityRenderer#updateCameraAndRender`（调 `MouseHelper#mouseXYChange`，:1096）；`ScreenShotHelper` 反向调用 `GlStateManager#bindTexture`、`OpenGlHelper#isFramebufferEnabled`、`TextureUtil#processPixelValues`
- net.minecraft.network → `NetworkManager`（pipeline 装配四个 Message 编解码器，NetworkManager.java:379；`attrKeyConnectionState` 被解码器读取）、`PacketThreadUtil#checkThreadAndEnqueue`（调 `IThreadListener#addScheduledTask`，PacketThreadUtil.java:11）、`PacketBuffer#readVarIntFromBuffer/writeVarIntToBuffer`
- net.minecraft.network.login → `NetHandlerLoginClient` / `C01PacketEncryptionResponse`（调 `CryptManager#createNewSharedKey/encryptData/getServerIdHash`）
- net.minecraft.entity → `EntityLivingBase#attackEntityFrom`（调 `CombatTracker#trackDamage`，EntityLivingBase.java:1290）、`EntityLivingBase#getCombatTracker`（:1299）
- net.minecraft.entity.player → `EntityPlayer#onUpdate`（调 `FoodStats#onUpdate`，EntityPlayer.java:395）
- net.minecraft.block / net.minecraft.item → `Block.blockRegistry` / `Item.itemRegistry`（类型为 `RegistryNamespacedDefaultedByKey`）；`Block#getStateFromMeta` 等消费 `EnumFacing`
- net.minecraft.client.resources → `LanguageManager`（调 `StringTranslate#replaceWith`）、`Locale`（GUI 侧翻译，与 StatCollector 并行的另一套表）
- net.minecraft.event → `ClickEvent` / `HoverEvent`（被 `ChatStyle` 持有并序列化）
- net.minecraft.scoreboard / net.minecraft.server → `ChatComponentScore#getUnformattedTextForChat`（调 `MinecraftServer#getServer` 与 `Scoreboard#getValueFromObjective`，ChatComponentScore.java:46-56）
- net.minecraft.command → `ChatComponentProcessor#processComponent`（调 `PlayerSelector#matchEntities`）
- net.minecraft.nbt → `Rotations`（NBTTagList/NBTTagFloat）、`FoodStats`（NBTTagCompound）
- net.minecraft.crash → `ReportedException`（包装 `CrashReport`）
- lwjgl2-shim（org.lwjgl.input.Mouse / org.lwjgl.opengl.Display / org.lwjgl.util.vector.Matrix4f）→ `MouseHelper`、`Matrix4f`、`ScreenShotHelper`（BufferUtils/GL11/GL12）

## 覆盖声明

- 完整读取了 **82/82** 个文件（每个文件均通过 Read 全文读入）。
- 逐行精读的类：`Timer`、`MathHelper`、`BlockPos`、`Vec3i`、`Vec3`、`AxisAlignedBB`、`EnumFacing`、`MouseHelper`、`MovementInput(FromOptions)`、`MessageSerializer(2)`、`MessageDeserializer(2)`、`CryptManager`、`IChatComponent`、`ChatComponentStyle`、`ChatComponentTranslation`、`ChatStyle`、`StringTranslate`、`StatCollector`、`RegistrySimple/Namespaced/DefaultedByKey`、`ObjectIntIdentityMap`、`DamageSource` 族、`CombatTracker`、`FoodStats`、`ScreenShotHelper`、`ClassInheritanceMultiMap`、`IntHashMap`、`LongHashMap`、`FrameTimer`、`Session`、`IntegerCache`、`ThreadSafeBoundList`。
- 只做结构性浏览（读了全文但未逐行推演算法细节）的类：`Cartesian`（ProductIterator 状态机）、`HttpUtil`（downloadResourcePack 匿名 Runnable 细节）、`JsonUtils`（重复的 getter 变体）、`WeightedRandomChestContent` / `WeightedRandomFishable`、`EnumParticleTypes` 逐项枚举值、`ChatComponentProcessor`、`EnumTypeAdapterFactory`、`MouseFilter` 滤波系数推导。
- 行号引用均来自本次 Read 输出；调用方引用（Minecraft.java / EntityPlayerSP.java / NetworkManager.java / EntityLivingBase.java / EntityPlayer.java / EntityRenderer.java / PacketThreadUtil.java）经 grep 逐条核实。
