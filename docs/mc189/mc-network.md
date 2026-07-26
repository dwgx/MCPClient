---
area: net/minecraft/network
slug: mc-network
files: 34
lines: 5237
tier: A
---

# net/minecraft/network — 网络层核心

## 定位

这个包是整个客户端(以及内嵌单机服务端)的网络骨架:它定义了封包抽象(`Packet` / `PacketBuffer`)、协议状态机(`EnumConnectionState`)、Netty channel 管理(`NetworkManager` 客户端侧 / `NetworkSystem` 服务端侧)、以及压缩与加密的 pipeline handler。子包 `handshake` / `login` / `status` 装着这三个协议阶段的具体封包;PLAY 阶段的封包在兄弟包 `net/minecraft/network/play` 中(不在本 bucket)。

谁调用它:
- 客户端连接入口 `GuiConnecting`、`Minecraft`(单机 local channel)、`OldServerPinger`、`RealmsConnect` / `RealmsServerStatusPinger` 都通过 `NetworkManager.createNetworkManagerAndConnect(...)` 或 `NetworkManager.provideLocalClient(...)` 建立连接。
- 内嵌服务端 `MinecraftServer` 持有一个 `NetworkSystem`(MinecraftServer.java:205),每个服务端 tick 调 `networkTick()`(MinecraftServer.java:801)。
- 各 `INetHandler` 实现(`NetHandlerPlayClient`、`NetHandlerLoginClient`、`NetHandlerLoginServer`、本包的 `NetHandlerPlayServer` 等)在收包时被 `NetworkManager.channelRead0` 回调。

它调用谁:`net/minecraft/util` 里的 `MessageDeserializer` / `MessageDeserializer2` / `MessageSerializer` / `MessageSerializer2`(帧切分与包编解码,pipeline 的四个固定 handler)、`CryptManager`(AES/RSA)、`CompressedStreamTools`(NBT)、以及全体封包类。

如果它消失:客户端无法连接任何服务器,单机模式(经 local channel 连接内嵌服务端)也无法启动世界;所有封包收发、登录、加密、压缩全部瘫痪。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| EnumConnectionState | 344 | enum | 协议状态机:HANDSHAKING/PLAY/STATUS/LOGIN,维护 packetId ↔ 封包类 的双向注册表 |
| EnumPacketDirection | 7 | enum | 封包方向:SERVERBOUND / CLIENTBOUND |
| INetHandler | 11 | interface | 所有包处理器的根接口,只有 `onDisconnect` |
| NetHandlerPlayServer | 1460 | implements INetHandlerPlayServer, ITickable | 服务端 PLAY 阶段包处理器:移动校验、聊天、容器操作、反作弊踢人 |
| NettyCompressionDecoder | 61 | extends ByteToMessageDecoder | 入站解压:VarInt 长度前缀 + zlib Inflater |
| NettyCompressionEncoder | 52 | extends MessageToByteEncoder\<ByteBuf\> | 出站压缩:低于阈值写 0 前缀原样透传,否则 Deflater 压缩 |
| NettyEncryptingDecoder | 23 | extends MessageToMessageDecoder\<ByteBuf\> | 入站 AES 解密,委托 NettyEncryptionTranslator |
| NettyEncryptingEncoder | 22 | extends MessageToByteEncoder\<ByteBuf\> | 出站 AES 加密,委托 NettyEncryptionTranslator |
| NettyEncryptionTranslator | 54 | (无) | Cipher.update 的 ByteBuf 适配器,复用内部 byte[] 缓冲 |
| NetworkManager | 525 | extends SimpleChannelInboundHandler\<Packet\> | 单条连接的核心:pipeline 组装、收发队列、状态切换、断线处理 |
| NetworkSystem | 248 | (无) | 服务端监听器集合:LAN/local endpoint 绑定、每 tick 驱动所有连接 |
| Packet | 21 | interface | 封包三方法契约:readPacketData / writePacketData / processPacket |
| PacketBuffer | 1243 | extends ByteBuf | ByteBuf 装饰器,追加 VarInt/NBT/ItemStack/String/BlockPos 等 MC 类型读写 |
| PacketThreadUtil | 21 | (无) | 收包线程检查:不在主线程则调度到主线程并抛 ThreadQuickExitException |
| PingResponseHandler | 129 | extends ChannelInboundHandlerAdapter | 服务端 legacy ping(0xFE,1.6 及更早客户端)应答 |
| ServerStatusResponse | 248 | (无) | 服务器状态 JSON 数据模型(MOTD/人数/协议版本/favicon)+ Gson 序列化器 |
| ThreadQuickExitException | 17 | extends RuntimeException | 无栈单例异常,用于从 processPacket 快速退出 Netty 线程 |
| handshake/INetHandlerHandshakeServer | 14 | extends INetHandler | 握手阶段服务端处理器接口 |
| handshake/client/C00Handshake | 67 | implements Packet\<INetHandlerHandshakeServer\> | 握手包:协议版本、目标地址端口、请求进入的状态 |
| login/INetHandlerLoginClient | 18 | extends INetHandler | 登录阶段客户端处理器接口(4 个 handle 方法) |
| login/INetHandlerLoginServer | 12 | extends INetHandler | 登录阶段服务端处理器接口(2 个 process 方法) |
| login/client/C00PacketLoginStart | 51 | implements Packet\<INetHandlerLoginServer\> | 登录起始包,携带 GameProfile 用户名 |
| login/client/C01PacketEncryptionResponse | 62 | implements Packet\<INetHandlerLoginServer\> | 加密响应:RSA 加密后的共享密钥与验证 token |
| login/server/S00PacketDisconnect | 50 | implements Packet\<INetHandlerLoginClient\> | 登录阶段断线包,携带 IChatComponent 原因 |
| login/server/S01PacketEncryptionRequest | 69 | implements Packet\<INetHandlerLoginClient\> | 加密请求:serverId 散列、RSA 公钥、验证 token |
| login/server/S02PacketLoginSuccess | 56 | implements Packet\<INetHandlerLoginClient\> | 登录成功:UUID 字符串 + 用户名,触发切入 PLAY 状态 |
| login/server/S03PacketEnableCompression | 49 | implements Packet\<INetHandlerLoginClient\> | 启用压缩,携带 VarInt 阈值 |
| rcon/RConConsoleSource | 99 | implements ICommandSender | RCON 命令执行者的 ICommandSender 实现(单例,输出进 StringBuffer) |
| status/INetHandlerStatusClient | 12 | extends INetHandler | 状态查询客户端处理器接口 |
| status/INetHandlerStatusServer | 12 | extends INetHandler | 状态查询服务端处理器接口 |
| status/client/C00PacketServerQuery | 31 | implements Packet\<INetHandlerStatusServer\> | 空负载状态查询请求 |
| status/client/C01PacketPing | 49 | implements Packet\<INetHandlerStatusServer\> | ping 包,携带客户端时间戳 long |
| status/server/S00PacketServerInfo | 56 | implements Packet\<INetHandlerStatusClient\> | 状态响应:ServerStatusResponse 的 JSON 字符串 |
| status/server/S01PacketPong | 44 | implements Packet\<INetHandlerStatusClient\> | pong 包,原样回传客户端时间戳 |

## 核心类详解

### NetworkManager (NetworkManager.java)

一条连接一个实例,同时是 pipeline 末端的 `"packet_handler"`。

关键静态字段:
- `public static final AttributeKey<EnumConnectionState> attrKeyConnectionState = AttributeKey.<EnumConnectionState>valueOf("protocol");`(NetworkManager.java:57)——当前协议状态挂在 channel attribute 上,`MessageDeserializer`/`MessageSerializer` 靠它查包 ID 表。
- 三个 `LazyLoadBase` 事件循环组:`CLIENT_NIO_EVENTLOOP`(:58,`NioEventLoopGroup`,线程名 `Netty Client IO #%d`)、`CLIENT_EPOLL_EVENTLOOP`(:65)、`CLIENT_LOCAL_EVENTLOOP`(:72,注意类型是 `DefaultEventLoopGroup` —— Netty 4.2 移植后替代了原版的 `LocalEventLoopGroup`)。

关键实例字段:
- `private final EnumPacketDirection direction;`(:79)——本端接收方向,客户端为 CLIENTBOUND。
- `private final Queue<NetworkManager.InboundHandlerTuplePacketListener> outboundPacketsQueue`(:80)——channel 尚未打开时的出站暂存队列;`private final ReentrantReadWriteLock readWriteLock`(:81)保护它。
- `private Channel channel;`(:84)、`private SocketAddress socketAddress;`(:87)、`private INetHandler packetListener;`(:90)、`private IChatComponent terminationReason;`(:93)、`private boolean isEncrypted;`(:94)、`private boolean disconnected;`(:95)。

关键方法:
- `public void channelActive(ChannelHandlerContext p_channelActive_1_) throws Exception`(:102)——Netty 回调,记录 channel 并把状态置为 `EnumConnectionState.HANDSHAKING`。
- `public void setConnectionState(EnumConnectionState newState)`(:121)——写 channel attribute 并重开 autoRead。
- `protected void channelRead0(ChannelHandlerContext p_channelRead0_1_, Packet p_channelRead0_2_) throws Exception`(:149)——**所有入站封包的唯一入口**,调 `p_channelRead0_2_.processPacket(this.packetListener)` 并吞掉 `ThreadQuickExitException`。运行在 Netty EventLoop 线程。
- `public void setNetHandler(INetHandler handler)`(:168)——切换阶段处理器(握手→登录→PLAY),不做适配检查。
- `public void sendPacket(Packet packetIn)`(:175)与 `public void sendPacket(Packet packetIn, GenericFutureListener <? extends Future <? super Void >> listener, GenericFutureListener <? extends Future <? super Void >> ... listeners)`(:197)——**所有出站封包的唯一入口**;channel 未开则入队。
- `private void dispatchPacket(final Packet inPacket, final GenericFutureListener <? extends Future <? super Void >> [] futureListeners)`(:223)——实际写出;若封包所属状态与当前状态不同,先关 autoRead(:231)再在 EventLoop 中切状态;不在 EventLoop 线程时用 `channel.eventLoop().execute(...)` 投递(:252)。
- `public void processReceivedPackets()`(:301)——每 tick 由持有者调用:flush 出站队列、若 `packetListener instanceof ITickable` 则调其 `update()`、最后 `channel.flush()`。
- `public void closeChannel(IChatComponent message)`(:324)——同步关闭 channel 并记录 `terminationReason`。
- `public static NetworkManager createNetworkManagerAndConnect(InetAddress address, int serverPort, boolean useNativeTransport)`(:349)——远程连接工厂;pipeline 组装在 :379:`timeout`(ReadTimeoutHandler(30)) → `splitter`(MessageDeserializer2) → `decoder`(MessageDeserializer(CLIENTBOUND)) → `prepender`(MessageSerializer2) → `encoder`(MessageSerializer(SERVERBOUND)) → `packet_handler`(本实例)。调用方:GuiConnecting.java:65、OldServerPinger.java:55、RealmsConnect.java:45、RealmsServerStatusPinger.java:35。
- `public static NetworkManager provideLocalClient(SocketAddress address)`(:389)——单机内存连接,pipeline 只有 `packet_handler`(:396),不经过编解码/压缩/加密。调用方:Minecraft.java:2331。
- `public void enableEncryption(SecretKey key)`(:405)——在 `splitter` 前插 `decrypt`,`prepender` 前插 `encrypt`。调用方:NetHandlerLoginClient.java:90(客户端)、server/network/NetHandlerLoginServer.java:185(服务端)。
- `public void setCompressionTreshold(int treshold)`(:454)——阈值 ≥0 时在 `decoder` 前插 `decompress`、`encoder` 前插 `compress`,否则移除两者。调用方:NetHandlerLoginClient.java:124、server/network/NetHandlerLoginServer.java:123。
- `public void checkDisconnected()`(:490)——channel 已关且未通知过时,回调 `getNetHandler().onDisconnect(...)`;重复调用只打 warn。
- 静态内部类 `InboundHandlerTuplePacketListener`(:514)——出站队列元素:`packet` + `futureListeners`。

### NetworkSystem (NetworkSystem.java)

服务端(含内嵌单机服务端)的监听器管理。

关键字段:`private final MinecraftServer mcServer;`(:74)、`public volatile boolean isAlive;`(:77)、`private final List<ChannelFuture> endpoints`(:78,synchronizedList)、`private final List<NetworkManager> networkManagers`(:79,synchronizedList)。事件循环组:`eventLoops`(:51,`Netty Server IO #%d`)、`SERVER_EPOLL_EVENTLOOP`(:58)、`SERVER_LOCAL_EVENTLOOP`(:65,`DefaultEventLoopGroup`)。

- `public void addLanEndpoint(InetAddress address, int port) throws IOException`(:90)——对外 TCP 监听;子 channel pipeline(:123):`timeout` → `legacy_query`(PingResponseHandler) → `splitter` → `decoder`(SERVERBOUND) → `prepender` → `encoder`(CLIENTBOUND) → `packet_handler`;初始 handler 为 `NetHandlerHandshakeTCP`(:127)。
- `public SocketAddress addLocalEndpoint()`(:142)——单机内存监听(LocalServerChannel),初始 handler 为 `NetHandlerHandshakeMemory`(:153)。注意 local endpoint 的 group 用的是 `SERVER_LOCAL_EVENTLOOP`(:157)而非原版的 NIO 组 `eventLoops` —— 这是 Netty 4.2 移植修改点:4.2 经 IoHandler 注册且要求 channel 类型匹配,`LocalServerChannel` 挂在 NIO loop 上会直接失败(见源码注释 :136-141)。调用方:Minecraft.java:2330。
- `public void terminateEndpoints()`(:167)——置 `isAlive=false` 并同步关闭全部监听 channel。
- `public void networkTick()`(:188)——服务端每 tick:遍历 `networkManagers`,已断开的移除并 `checkDisconnected()`;活跃的调 `processReceivedPackets()`;处理异常时对内存连接直接抛 `ReportedException` 崩溃报告(:224),对远程连接发 `S40PacketDisconnect` 后关闭(:229)。调用方:MinecraftServer.java:801。

### NetHandlerPlayServer (NetHandlerPlayServer.java)

服务端 PLAY 阶段处理器,由 `ServerConfigurationManager`(ServerConfigurationManager.java:142)在玩家登录完成时创建;构造函数(:129)里 `networkManagerIn.setNetHandler(this)` 并回写 `playerIn.playerNetServerHandler = this`。因为 implements `ITickable`,`NetworkManager.processReceivedPackets()` 每 tick 会调它的 `update()`。

关键字段:`public final NetworkManager netManager;`(:101)、`private final MinecraftServer serverController;`(:102)、`public EntityPlayerMP playerEntity;`(:103)、`private int networkTickCount;`(:104)、`private int floatingTickCount;`(:111,悬空 >80 tick 踢出)、`private int chatSpamThresholdCount;`(:121,每条聊天 +20,每 tick -1,>200 踢出)、`private int itemDropThreshold;`(:122)、`private IntHashMap<Short> field_147372_n`(:123,窗口事务确认表)、`lastPosX/lastPosY/lastPosZ`(:124-126)、`private boolean hasMoved = true;`(:127)。

关键方法(全部签名逐字):
- `public void update()`(:141)——每 tick:每 40 tick 发 `S00PacketKeepAlive`(:147-152),衰减聊天/丢物阈值,检查挂机踢出(:167)。
- `public void kickPlayerFromServer(String reason)`(:181)——发 `S40PacketDisconnect`,在发送完成的 listener 里 `closeChannel`,并 `disableAutoRead()`。
- `public void processPlayer(C03PacketPlayer packetIn)`(:219)——移动主校验:非有限坐标踢出(:223-226 经 `func_183006_b`,:211)、`|x|>3.0E7` 踢 "Illegal position"(:334)、速度校验 "moved too quickly!"(:361-363)、穿墙校验 "moved wrongly!"(:390-393)、悬空飞行检测(:412-424)。
- `public void setPlayerLocation(double x, double y, double z, float yaw, float pitch)`(:443)/ 带 `Set<S08PacketPlayerPosLook.EnumFlags> relativeSet` 的重载(:448)——服务端强制回拉,置 `hasMoved = false` 并发 `S08PacketPlayerPosLook`。
- `public void processPlayerDigging(C07PacketPlayerDigging packetIn)`(:492)——挖掘/丢物;距离平方 >36 忽略(:529)。
- `public void processPlayerBlockPlacement(C08PacketPlayerBlockPlacement packetIn)`(:578)——放置/交互;方向 255 表示"对空使用物品"(:588)。
- `public void onDisconnect(IChatComponent reason)`(:708)——广播离开消息、`playerLoggedOut`;单机房主退出时 `initiateShutdown()`(:721)。
- `public void sendPacket(final Packet packetIn)`(:725)——服务端对该玩家发包的收口,含聊天可见性过滤(:727-741),异常包成 `ReportedException`。
- `public void processChatMessage(C01PacketChatMessage packetIn)`(:783)——非法字符踢出(:801-805)、`/` 开头走 `handleSlashCommand`(:830)、刷屏踢出 "disconnect.spam"(:820-823)。
- `public void processUseEntity(C02PacketUseEntity packetIn)`(:899)——攻击/交互实体,距离上限 36(可见)/9(不可见)(:909-913),攻击无效实体踢出(:928-933)。
- `public void processClickWindow(C0EPacketClickWindow packetIn)`(:1007)——容器点击与事务确认(`S32PacketConfirmTransaction`)。
- `public void processClientStatus(C16PacketClientStatus packetIn)`(:945)——重生/统计/成就。
- `public void processVanilla250Packet(C17PacketCustomPayload packetIn)`(:1241)——自定义 payload 分发:`MC|BEdit`(:1245)、`MC|BSign`(:1287)、`MC|TrSel`(:1332)、`MC|AdvCdm`(:1349)、`MC|Beacon`(:1414)、`MC|ItemName`(:1441)。
- `public void processKeepAlive(C00PacketKeepAlive packetIn)`(:1189)——计算 ping:`this.playerEntity.ping = (this.playerEntity.ping * 3 + i) / 4;`(:1194)。注意此方法**没有** `checkThreadAndEnqueue`,直接在 Netty 线程执行。
- 其余 process* 方法(processInput :205、processHeldItemChange :765、handleAnimation :835、processEntityAction :846、processCloseWindow :996、processEnchantItem :1059、processCreativeInventoryAction :1074、processConfirmTransaction :1139、processUpdateSign :1150、processPlayerAbilities :1206、processTabComplete :1215、processClientSettings :1232、handleSpectate :643、handleResourcePackStatus :701)首行都是 `PacketThreadUtil.checkThreadAndEnqueue(packetIn, this, this.playerEntity.getServerForPlayer());`,即真正逻辑在服务端主线程执行。

### PacketBuffer (PacketBuffer.java)

`ByteBuf` 的全量委托装饰器(`private final ByteBuf buf;`,:30),追加 Minecraft 协议类型。核心自定义方法:

- `public static int getVarIntSize(int input)`(:41)
- `public void writeByteArray(byte[] array)`(:54)/ `public byte[] readByteArray()`(:60)——VarInt 长度前缀
- `public BlockPos readBlockPos()`(:67)/ `public void writeBlockPos(BlockPos pos)`(:72)——long 压缩坐标
- `public IChatComponent readChatComponent() throws IOException`(:77)/ `public void writeChatComponent(IChatComponent component) throws IOException`(:82)——JSON 字符串
- `public <T extends Enum<T>> T readEnumValue(Class<T> enumClass)`(:87)/ `public void writeEnumValue(Enum<?> value)`(:92)——VarInt ordinal
- `public int readVarIntFromBuffer()`(:101)/ `public void writeVarIntToBuffer(int input)`(:166)——7 bit 分组,最多 5 字节
- `public long readVarLong()`(:125)/ `public void writeVarLong(long value)`(:177)——最多 10 字节
- `public void writeUuid(UUID uuid)`(:149)/ `public UUID readUuid()`(:155)——两个 long
- `public void writeNBTTagCompoundToBuffer(NBTTagCompound nbt)`(:191)/ `public NBTTagCompound readNBTTagCompoundFromBuffer() throws IOException`(:213)——null 写单字节 0;读取带 `NBTSizeTracker(2097152L)` 限制(:225)
- `public void writeItemStackToBuffer(ItemStack stack)`(:232)/ `public ItemStack readItemStackFromBuffer() throws IOException`(:257)——short id(-1 = null)+ byte count + short meta + NBT
- `public String readStringFromBuffer(int maxLength)`(:277)——**移植修改点**:原版用 `buf.array()`,这里改为先 `readBytes` 进 `byte[]` 再 `new String(stringBytes, Charsets.UTF_8)`(:294-296),因为 Netty 4.1+/LWJGL3 环境下是 pooled direct buffer,`.array()` 会抛 `UnsupportedOperationException`(注释 :291-293)
- `public PacketBuffer writeString(String string)`(:309)——UTF-8 编码后长度上限 32767 字节
- :1062 起是 Netty 4.1 新增抽象方法的委托实现(`isReadOnly`、`getShortLE` 系列、`readRetainedSlice`、`FileChannel` 重载等),属移植补丁。

### EnumConnectionState (EnumConnectionState.java)

四个状态:`HANDSHAKING(-1)`(:116,仅注册 C00Handshake)、`PLAY(0)`(:122,注册 74 个 CLIENTBOUND + 26 个 SERVERBOUND 封包类)、`STATUS(1)`(:227)、`LOGIN(2)`(:236)。包 ID 就是注册顺序(`bimap.put(Integer.valueOf(bimap.size()), packetClass)`,:279)。

- `protected EnumConnectionState registerPacket(EnumPacketDirection direction, Class <? extends Packet > packetClass)`(:261)——重复注册直接 `throw new IllegalArgumentException`(:275)
- `public Integer getPacketId(EnumPacketDirection direction, Packet packetIn)`(:284)——序列化时由 `MessageSerializer` 调用
- `public Packet getPacket(EnumPacketDirection direction, int packetId) throws InstantiationException, IllegalAccessException`(:289)——反序列化时由 `MessageDeserializer` 调用,`oclass.newInstance()` 反射建包
- `public static EnumConnectionState getById(int stateId)`(:299)、`public static EnumConnectionState getFromPacket(Packet packetIn)`(:304)
- 静态初始化块(:309)在类加载时对每个封包类做 `oclass.newInstance()` 实例化自检(:332),失败抛 `Error`——所以**每个封包类必须有 public 无参构造器**。

### 压缩 / 加密 handler

- `NettyCompressionDecoder extends ByteToMessageDecoder`:`protected void decode(ChannelHandlerContext p_decode_1_, ByteBuf p_decode_2_, List<Object> p_decode_3_) throws DataFormatException, Exception`(NettyCompressionDecoder.java:23)。帧内先读 VarInt 未压缩长度:0 = 未压缩透传(:30-33);非 0 时校验下限(`i < this.treshold` 抛 DecoderException,:36)与上限 `2097152`(:41-44),然后 Inflater 解压。`public void setCompressionTreshold(int treshold)`(:57)。
- `NettyCompressionEncoder extends MessageToByteEncoder<ByteBuf>`:`protected void encode(ChannelHandlerContext p_encode_1_, ByteBuf p_encode_2_, ByteBuf p_encode_3_) throws Exception`(NettyCompressionEncoder.java:20)。小于阈值写 `0` 前缀原样输出,否则写原始长度 + Deflater 压缩块(8192 字节工作缓冲,:10)。
- `NettyEncryptingDecoder` / `NettyEncryptingEncoder` 各自持有一个 `NettyEncryptionTranslator`;后者的 `protected ByteBuf decipher(ChannelHandlerContext ctx, ByteBuf buffer) throws ShortBufferException`(NettyEncryptionTranslator.java:32)与 `protected void cipher(ByteBuf in, ByteBuf out) throws ShortBufferException`(:41)是 `Cipher.update` 的流式适配(AES/CFB8,Cipher 实例由 `CryptManager.createNetCipherInstance` 提供,见 NetworkManager.java:408-409)。注意 `decipher` 里用了 `bytebuf.array()`(:37)——heap buffer 前提。

### PacketThreadUtil / ThreadQuickExitException

- `public static <T extends INetHandler> void checkThreadAndEnqueue(final Packet<T> p_180031_0_, final T p_180031_1_, IThreadListener p_180031_2_) throws ThreadQuickExitException`(PacketThreadUtil.java:7)——不在主线程时把 `processPacket` 包成 Runnable 投给 `IThreadListener.addScheduledTask`,然后 `throw ThreadQuickExitException.INSTANCE`(:18)中断 Netty 线程上的当前处理;`NetworkManager.channelRead0` 捕获并忽略该异常(NetworkManager.java:157)。客户端侧 `NetHandlerPlayClient` 共 66 处调用(target 是 `this.gameController` 即 Minecraft)。
- `ThreadQuickExitException` 是无栈单例:`public static final ThreadQuickExitException INSTANCE`(ThreadQuickExitException.java:5),`public synchronized Throwable fillInStackTrace()`(:12)清空栈避免开销。

### PingResponseHandler (PingResponseHandler.java)

服务端 pipeline 中位于 `splitter` 之前的 `"legacy_query"`。`public void channelRead(ChannelHandlerContext p_channelRead_1_, Object p_channelRead_2_) throws Exception`(:24):首字节为 `254`(0xFE)时按 <1.3.x(:40)/1.4-1.5.x(:46)/1.6(:57)三种旧版 ping 格式应答(`§` 分隔或 `MC|PingHost`),响应以 0xFF + UTF-16BE 字符串写回并关闭连接(:110-113)。不是 legacy ping 时 `resetReaderIndex()`、把自己从 pipeline 移除(:104)并 `fireChannelRead` 放行。

### ServerStatusResponse (ServerStatusResponse.java)

状态查询 JSON 的数据类:字段 `serverMotd`(IChatComponent)、`playerCount`、`protocolVersion`、`favicon`(:19-22)。三层嵌套 static Serializer(:191 / :135 / :85)分别生成 `{description, players:{max,online,sample}, version:{name,protocol}, favicon}`。`S00PacketServerInfo` 的静态 `GSON`(S00PacketServerInfo.java:16)注册了全部适配器。

### rcon/RConConsoleSource

`ICommandSender` 的傀儡实现:`getName()` 返回 `"Rcon"`(:24-27),`canCommandSenderUseCommand` 恒 true(:48),聊天输出累积进 `private StringBuffer buffer`(:19)。本 bucket 中 rcon 子包仅存此文件(其余 rcon 线程类未随移植保留)。

## 时序与生命周期

连接建立(客户端主动连接远程服务器):
1. `GuiConnecting`(:65)在**独立连接线程**调 `NetworkManager.createNetworkManagerAndConnect(...)`,Bootstrap `connect(...).syncUninterruptibly()` 阻塞至连上。
2. Netty EventLoop 触发 `channelActive`(NetworkManager.java:102)→ 状态置 HANDSHAKING。
3. 客户端发 `C00Handshake(47, ip, port, EnumConnectionState.LOGIN)` + `C00PacketLoginStart`;`dispatchPacket` 检测到封包状态 ≠ 当前状态,自动切状态(NetworkManager.java:228-239)。
4. 登录阶段(处理器 `NetHandlerLoginClient`,非本包):服务器可发 `S01PacketEncryptionRequest` → 客户端回 `C01PacketEncryptionResponse` 并 `enableEncryption`;`S03PacketEnableCompression` → `setCompressionTreshold`;`S02PacketLoginSuccess` → 切 PLAY、`setNetHandler(new NetHandlerPlayClient(...))`。
5. 单机路径:`Minecraft`(:2330-2331)`addLocalEndpoint()` + `provideLocalClient(...)`,pipeline 无编解码,`Packet` 对象直接跨 local channel 传递。

每 tick(客户端主线程):`Minecraft.java:2261` / `GuiConnecting.java:111` / `PlayerControllerMP.java:355` 调 `networkManager.processReceivedPackets()` —— flush 出站队列、tick 处理器、flush channel。每 tick(服务端线程):`MinecraftServer.java:801` → `NetworkSystem.networkTick()` → 每个连接 `processReceivedPackets()` → `NetHandlerPlayServer.update()`(keepalive 每 40 tick,挂机检测)。

每帧:无——本包与渲染无关。

线程归属:
- **Netty EventLoop**(`Netty Client IO #N` / `Netty Server IO #N` / Local 组):`channelRead0`、所有 pipeline handler(压缩/加密/编解码)、`processPacket` 的第一次进入。
- **主线程(客户端)/ 服务端线程**:经 `checkThreadAndEnqueue` 转投后的封包实际处理、`processReceivedPackets`。
- **连接线程**:`GuiConnecting`/`RealmsConnect` 内 new Thread 做 DNS 解析与阻塞连接。

断开:`channelInactive`(:128)/ `exceptionCaught`(:133)→ `closeChannel` 记录原因 → 下一次 tick 侧 `checkDisconnected()`(:490)在 tick 线程回调 `onDisconnect`。

## 挂钩点(Hook Points)

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `protected void channelRead0(ChannelHandlerContext p_channelRead0_1_, Packet p_channelRead0_2_) throws Exception` | NetworkManager.java:149 | 每个入站封包解码完成后(Netty EventLoop) | **全局收包拦截**:过滤/篡改/取消任意 S 包(改字段、不调 processPacket 即丢弃) | 运行在 Netty 线程,不能碰世界/GUI 状态;必须放行 ThreadQuickExitException |
| `public void sendPacket(Packet packetIn)` | NetworkManager.java:175 | 客户端所有出站封包(任意线程) | **全局发包拦截**:取消/改写 C 包(移动欺骗、包队列、blink 类功能) | channel 未开时会入队而非发送;修改需同时覆盖 :197 的三参重载 |
| `private void dispatchPacket(final Packet inPacket, final GenericFutureListener <? extends Future <? super Void >> [] futureListeners)` | NetworkManager.java:223 | sendPacket / flushOutboundQueue 内部 | 最底层出站点(队列 flush 也经过);观察真实发出顺序 | private;含协议状态切换副作用,拦截时勿破坏 setConnectionState 逻辑 |
| `public void setNetHandler(INetHandler handler)` | NetworkManager.java:168 | 协议阶段切换时(握手→登录→PLAY) | 包装/替换处理器,感知进入世界与登出时刻 | 无类型检查;换错处理器会在下个包 processPacket 时 ClassCastException |
| `public void processReceivedPackets()` | NetworkManager.java:301 | 每 tick(Minecraft.java:2261、GuiConnecting.java:111 等) | tick 边界钩子;控制出站队列 flush 时机(人为延迟发包) | 也负责 `ITickable` 处理器的 update;完全不调会积压出站队列 |
| `public void closeChannel(IChatComponent message)` | NetworkManager.java:324 | 断线/被踢/主动断开 | 感知并记录断开原因;自动重连入口 | `awaitUninterruptibly` 阻塞;可能从 EventLoop 或主线程调用 |
| `public void checkDisconnected()` | NetworkManager.java:490 | tick 线程发现 channel 已关时 | 断线事件的 tick 线程侧回调点 | 只触发一次(disconnected 标志);重复调用仅 warn |
| `public void enableEncryption(SecretKey key)` | NetworkManager.java:405 | 登录加密握手完成时 | 抓取会话密钥(调试/抓包解密);插入自定义 handler 的参照位 | pipeline 修改必须在 EventLoop 安全;名字 "decrypt"/"encrypt" 被硬编码 |
| `public void setCompressionTreshold(int treshold)` | NetworkManager.java:454 | 收到 S03PacketEnableCompression / S46PacketSetCompressionLevel | 观察/覆盖压缩阈值 | :469 存在原版遗留 bug(见下节);pipeline 名字硬编码 |
| `public static NetworkManager createNetworkManagerAndConnect(InetAddress address, int serverPort, boolean useNativeTransport)` | NetworkManager.java:349 | 每次连远程服务器 | 统一注入自定义 pipeline handler(封包记录器、协议翻译层) | 静态方法,只能改源或换调用方;`syncUninterruptibly` 阻塞调用线程 |
| `public void setConnectionState(EnumConnectionState newState)` | NetworkManager.java:121 | channelActive 及 dispatchPacket 切态时 | 感知协议阶段迁移 | 必须在 EventLoop 内调用(dispatchPacket 已保证) |
| `public void networkTick()` | NetworkSystem.java:188 | 服务端每 tick(MinecraftServer.java:801) | 服务端侧全连接遍历点;注入全局服务端包处理 | 持 networkManagers 锁;内存连接异常会直接崩服(ReportedException) |
| `protected void initChannel(Channel p_initChannel_1_) throws Exception` | NetworkSystem.java:112(LAN)/ :150(local) | 每个新入站连接 | 服务端侧 pipeline 注入点 | 匿名类,需改源;两条路径 pipeline 不同 |
| `public void update()` | NetHandlerPlayServer.java:141 | 服务端每 tick 每玩家 | keepalive/挂机/阈值衰减逻辑;每玩家 tick 钩子 | 修改 40 tick keepalive 周期会影响 ping 计算 |
| `public void processPlayer(C03PacketPlayer packetIn)` | NetHandlerPlayServer.java:219 | 客户端每个移动包(约每 tick 一个) | 服务端移动校验总入口:改反作弊阈值、放宽 "moved too quickly" | 首行 checkThreadAndEnqueue 决定线程;`hasMoved` 状态机易搞坏导致回拉循环 |
| `public void setPlayerLocation(double x, double y, double z, float yaw, float pitch)` | NetHandlerPlayServer.java:443 | 服务端强制传送/回拉 | 观察全部服务端回拉(S08PacketPlayerPosLook 的唯一服务端出口) | 置 `hasMoved=false`,直到客户端确认前移动包被半忽略 |
| `public void kickPlayerFromServer(String reason)` | NetHandlerPlayServer.java:181 | 各种违规检测触发 | 统一踢人出口,可拦截/记录 | 发包后异步 closeChannel;含跨线程 addScheduledTask |
| `public void sendPacket(final Packet packetIn)` | NetHandlerPlayServer.java:725 | 服务端对单个玩家发任意包 | 服务端侧对玩家出站过滤(聊天可见性已在此处理) | 与 NetworkManager.sendPacket 是两层;仅覆盖此处会漏掉直接调 netManager 的路径 |
| `public void processChatMessage(C01PacketChatMessage packetIn)` | NetHandlerPlayServer.java:783 | 玩家聊天/命令 | 命令拦截(:808 的 `/` 分支)、聊天过滤、刷屏阈值调整 | 非法字符与 spam 检查会踢人;逻辑在服务端主线程 |
| `public void processVanilla250Packet(C17PacketCustomPayload packetIn)` | NetHandlerPlayServer.java:1241 | 自定义 payload(书、命令方块、铁砧命名等) | 注册自定义 channel 实现私有协议 | 各分支自行 release PacketBuffer;新增分支注意引用计数 |
| `public static <T extends INetHandler> void checkThreadAndEnqueue(final Packet<T> p_180031_0_, final T p_180031_1_, IThreadListener p_180031_2_) throws ThreadQuickExitException` | PacketThreadUtil.java:7 | 几乎每个 PLAY 包处理方法首行 | 全局"包到达主线程前"钩子(改此处可在 Netty 线程先行观察) | 抛单例异常是正常控制流;别在此吞掉异常否则包会被处理两次 |
| `void processPacket(T handler)`(接口方法) | Packet.java:20 | channelRead0 或主线程重放 | 单个封包级别的处理拦截(子类化/代理具体包) | 同一包实例可能在两个线程各进入一次(第一次抛 quick-exit) |
| `protected void decode(ChannelHandlerContext p_decode_1_, ByteBuf p_decode_2_, List<Object> p_decode_3_) throws DataFormatException, Exception` | NettyCompressionDecoder.java:23 | 每个入站压缩帧 | 原始字节级观察(解密后、解码前) | EventLoop 线程;Inflater 有内部状态,非线程安全 |
| `public void channelRead(ChannelHandlerContext p_channelRead_1_, Object p_channelRead_2_) throws Exception` | PingResponseHandler.java:24 | 服务端每个新连接的首个数据块 | 自定义 legacy ping 应答 / MOTD 伪装 | 处理后必须 release 或放行 ByteBuf,否则泄漏 |
| `public void onDisconnect(IChatComponent reason)` | INetHandler.java:10(接口)/ NetHandlerPlayServer.java:708 | checkDisconnected 时(tick 线程) | 断线善后:保存状态、UI 提示、自动重连 | 每连接仅一次;客户端实现在 NetHandlerPlayClient(非本包) |

## 数据与协议

### 封包线格式(本 bucket 内的封包)

外层帧(由 `MessageSerializer2`/`MessageDeserializer2` 处理,类在 net/minecraft/util):`VarInt 帧长 + [VarInt packetId + 包体]`。压缩启用后帧内变为 `VarInt 未压缩长度(0=未压缩) + 数据`。

C00Handshake(HANDSHAKING, SERVERBOUND, id 0)— C00Handshake.java:31-48:

| 字段 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|
| protocolVersion | int | readVarIntFromBuffer / writeVarIntToBuffer | 协议版本(1.8.9 = 47) |
| ip | String(≤255) | readStringFromBuffer(255) / writeString | 目标主机名 |
| port | int | readUnsignedShort / writeShort | 目标端口 |
| requestedState | EnumConnectionState | `EnumConnectionState.getById(buf.readVarIntFromBuffer())` / `writeVarIntToBuffer(this.requestedState.getId())` | 1=STATUS, 2=LOGIN |

LOGIN 阶段:

| 封包 | 字段 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|---|
| C00PacketLoginStart(id 0) | profile | GameProfile | `readStringFromBuffer(16)` / `writeString(this.profile.getName())` | 仅用户名,UUID 为 null(C00PacketLoginStart.java:28) |
| C01PacketEncryptionResponse(id 1) | secretKeyEncrypted | byte[] | readByteArray / writeByteArray | RSA 公钥加密的 AES 共享密钥 |
| | verifyTokenEncrypted | byte[] | readByteArray / writeByteArray | RSA 加密的验证 token |
| S00PacketDisconnect(id 0) | reason | IChatComponent | readChatComponent / writeChatComponent | 断线原因 JSON |
| S01PacketEncryptionRequest(id 1) | hashedServerId | String(≤20) | readStringFromBuffer(20) / writeString | serverId(1.8 为空串场景常见) |
| | publicKey | PublicKey | `CryptManager.decodePublicKey(buf.readByteArray())` / `writeByteArray(this.publicKey.getEncoded())` | X.509 DER RSA 公钥 |
| | verifyToken | byte[] | readByteArray / writeByteArray | 随机 4 字节 token |
| S02PacketLoginSuccess(id 2) | profile | GameProfile | 读 `readStringFromBuffer(36)`(UUID 带连字符字符串)+ `readStringFromBuffer(16)`(名字) | 登录成功后客户端切 PLAY |
| S03PacketEnableCompression(id 3) | compressionTreshold | int | readVarIntFromBuffer / writeVarIntToBuffer | 压缩阈值,负值表示关闭 |

STATUS 阶段:

| 封包 | 字段 | 类型 | 读/写方法 | 含义 |
|---|---|---|---|---|
| C00PacketServerQuery(id 0) | (无) | — | 空 read/write | 请求状态 JSON |
| C01PacketPing(id 1) | clientTime | long | readLong / writeLong | 客户端时间戳,测延迟 |
| S00PacketServerInfo(id 0) | response | ServerStatusResponse | `GSON.fromJson(buf.readStringFromBuffer(32767), ServerStatusResponse.class)` / `writeString(GSON.toJson((Object)this.response))` | 状态 JSON(S00PacketServerInfo.java:33,41) |
| S01PacketPong(id 1) | clientTime | long | readLong / writeLong | 原样回传 |

ServerStatusResponse JSON 字段:`description`(IChatComponent)、`players`:{`max` int, `online` int, `sample`:[{`id` uuid-string, `name`}]}(ServerStatusResponse.java:137-188)、`version`:{`name` string, `protocol` int}(:87-99)、`favicon`(data-URI base64 PNG 字符串,:213-216)。

### 压缩帧格式(NettyCompressionDecoder.java:23-54)

`VarInt i`:i==0 → 后续字节未压缩;i>0 → i 为解压后长度,必须满足 `i >= treshold`(:36)且 `i <= 2097152`(:41),否则 DecoderException。

### PacketBuffer 类型编码约定

| 类型 | 编码 | 位置 |
|---|---|---|
| VarInt | 7 bit/字节,MSB 续位,≤5 字节 | PacketBuffer.java:101,166 |
| VarLong | 同上,≤10 字节 | :125,177 |
| String | VarInt 字节长 + UTF-8;读上限 `maxLength*4` 字节且解码后 ≤ maxLength 字符;写上限 32767 字节 | :277,309 |
| NBTTagCompound | 首字节 0 = null,否则未压缩 NBT 流;读大小上限 2097152 | :191,213 |
| ItemStack | short itemId(-1=null)+ byte stackSize + short metadata + NBT | :232,257 |
| BlockPos | 单个 long(`BlockPos.fromLong`/`toLong`) | :67,72 |
| UUID | 两个 long(MSB,LSB) | :149,155 |
| byte[] | VarInt 长度前缀 | :54,60 |
| Enum | VarInt ordinal | :87,92 |

## 不变量与陷阱

1. **每个封包类必须有 public 无参构造器**——`EnumConnectionState` 静态块在类加载时逐个 `newInstance()` 自检(EnumConnectionState.java:332),失败直接 `throw new Error`,游戏起不来。
2. **包 ID 由注册顺序决定**(:279)。在 `PLAY` 的初始化块中间插入/删除一行 `registerPacket` 会使其后所有包 ID 平移,与标准 1.8.9(协议 47)服务器直接不兼容。
3. **一个封包类只能属于一个协议状态**(:325-328),重复归属抛 Error。
4. **processPacket 的双线程语义**:大多数 PLAY 包处理方法首行 `checkThreadAndEnqueue` 会在 Netty 线程抛 `ThreadQuickExitException` 并把同一 Packet 实例重投主线程——即 `readPacketData` 之后的字段在两个线程可见,处理方法体会被真正执行一次、进入两次。在包对象里加可变状态要小心。
5. **`NetHandlerPlayServer.processKeepAlive`(:1189)没有线程转投**,直接跑在 Netty EventLoop;在它里面碰世界状态是竞态。
6. **原版遗留 bug 被原样保留**:`NetworkManager.setCompressionTreshold` 更新已有压缩 encoder 时写的是 `((NettyCompressionEncoder)this.channel.pipeline().get("decompress")).setCompressionTreshold(treshold);`(NetworkManager.java:469)——取的是 `"decompress"` handler 强转 `NettyCompressionEncoder`。首次设置(addBefore 路径)不受影响;只有"二次修改阈值"才会走到这行并抛 ClassCastException。原版同款,勿当成移植引入的问题,但也别依赖二次设置。
7. **pipeline handler 名字是硬编码契约**:`timeout`/`legacy_query`/`splitter`/`decompress`/`decoder`/`decrypt`/`prepender`/`compress`/`encoder`/`encrypt`/`packet_handler`。`enableEncryption`(:408-409)和 `setCompressionTreshold`(:454-487)按名字 addBefore/remove,自定义 handler 起冲突名会破坏协议栈。
8. **LWJGL3/Netty 4.2 移植点**:
   - `PacketBuffer.readStringFromBuffer`(:291-296)显式改为 `readBytes` 到 byte[],因 pooled direct buffer 的 `.array()` 抛 `UnsupportedOperationException`。写自定义字节读取时同样**不要调用 `buf.array()`**。
   - `NettyEncryptionTranslator.decipher`(:37)仍用 `bytebuf.array()`,但该 buffer 来自 `ctx.alloc().heapBuffer(...)`(:36),是 heap buffer,安全;改成 directBuffer 就会炸。
   - 事件循环组从原版 `LocalEventLoopGroup` 换成 `io.netty.channel.DefaultEventLoopGroup`(NetworkManager.java:20,72;NetworkSystem.java:17,65);`ByteProcessor` 取代了旧 `ByteBufProcessor`(PacketBuffer.java:8)。
   - PacketBuffer.java:1062 起补齐了 Netty 4.1+ 新增抽象方法;若再升 Netty 需检查是否有新的抽象方法要补。
9. **出站队列语义**:channel 未打开时 `sendPacket` 只是入队(NetworkManager.java:184-194),真正发送要等 `flushOutboundQueue`(:277)在下一次 `sendPacket`/`processReceivedPackets` 被调;连接建立前发的包顺序保持但时机不确定。
10. **`dispatchPacket` 的状态切换副作用**:发送不属于当前状态的包会自动关 autoRead 并切换协议状态(:228-239)——发错状态的包不会报错,而是把整条连接的协议搞乱。
11. **线程安全约束**:`outboundPacketsQueue` 由 `readWriteLock` 保护(入队 writeLock、flush readLock——注意是反直觉的用法,poll 在 readLock 下靠 ConcurrentLinkedQueue 自身线程安全);`NetworkSystem.endpoints`/`networkManagers` 是 synchronizedList 且遍历时手动 synchronized(NetworkSystem.java:92,146,190)。
12. **压缩解码器上限 2097152 字节**(NettyCompressionDecoder.java:41)与 NBT 读取上限 `NBTSizeTracker(2097152L)`(PacketBuffer.java:225)一致;自定义大包要考虑这两处。
13. `Inflater`/`Deflater`/`Cipher` 实例均为 handler 私有且有状态,handler 不能被多 channel 共享(没有 @Sharable,本来也不该共享)。
14. `closeChannel` 里 `close().awaitUninterruptibly()`(:328)是阻塞调用;从主线程调用时若 EventLoop 卡死会拖住主线程。

## 交叉引用

- net/minecraft/util → `MessageDeserializer2` / `MessageDeserializer` / `MessageSerializer2` / `MessageSerializer`:pipeline 四件套(NetworkManager.java:379;NetworkSystem.java:123);`MessageDeserializer#decode` 反查 `EnumConnectionState#getPacket`,`MessageSerializer#encode` 反查 `EnumConnectionState#getPacketId`
- net/minecraft/util → `CryptManager#createNetCipherInstance`(NetworkManager.java:408-409)、`CryptManager#encryptData` / `decryptSharedKey` / `decryptData` / `decodePublicKey`(C01PacketEncryptionResponse.java:23-24,55,60;S01PacketEncryptionRequest.java:33)
- net/minecraft/util → `LazyLoadBase`(事件循环组懒加载)、`ITickable`(NetworkManager.java:305 判断)、`IThreadListener`(PacketThreadUtil.java:7)、`IChatComponent.Serializer`(PacketBuffer.java:79,84)
- net/minecraft/nbt → `CompressedStreamTools#write` / `#read`、`NBTSizeTracker`(PacketBuffer.java:201,225)
- net/minecraft/item → `Item#getIdFromItem` / `#getItemById`、`ItemStack`(PacketBuffer.java:240,266)
- net/minecraft/client → `Minecraft#runTick` 调 `NetworkManager#processReceivedPackets`(Minecraft.java:2261);`Minecraft#launchIntegratedServer` 调 `NetworkSystem#addLocalEndpoint` + `NetworkManager#provideLocalClient`(Minecraft.java:2330-2331)
- net/minecraft/client/multiplayer → `GuiConnecting#connect` 调 `NetworkManager#createNetworkManagerAndConnect`(GuiConnecting.java:65);`PlayerControllerMP#updateController` 调 `processReceivedPackets`(PlayerControllerMP.java:355)
- net/minecraft/client/network → `NetHandlerPlayClient`(66 处 `PacketThreadUtil#checkThreadAndEnqueue`)、`NetHandlerLoginClient` 调 `NetworkManager#enableEncryption` / `#setCompressionTreshold`(NetHandlerLoginClient.java:90,124)、`OldServerPinger` 使用 STATUS 封包 + `ServerStatusResponse`(OldServerPinger.java:55)、`NetHandlerHandshakeMemory`(NetworkSystem.java:153 引用)
- net/minecraft/server → `MinecraftServer` 持有并 tick `NetworkSystem`(MinecraftServer.java:205,801);`MinecraftServer#addScheduledTask`(NetHandlerPlayServer.java:192)
- net/minecraft/server/network → `NetHandlerHandshakeTCP`(NetworkSystem.java:127)、`NetHandlerLoginServer` 调 `NetworkManager#enableEncryption` / `#setCompressionTreshold`(NetHandlerLoginServer.java:185,123)
- net/minecraft/server/management → `ServerConfigurationManager#initializeConnectionToPlayer` new `NetHandlerPlayServer`(ServerConfigurationManager.java:142);`NetHandlerPlayServer` 大量回调 `ServerConfigurationManager#sendChatMsgImpl` / `#recreatePlayerEntity` / `#playerLoggedOut` 等
- net/minecraft/network/play → PLAY 阶段全部封包类(EnumConnectionState.java:125-224 注册;NetHandlerPlayServer 消费 C 包、生产 S 包)
- net/minecraft/realms → `RealmsConnect` / `RealmsServerStatusPinger` 直接使用 `NetworkManager`(RealmsConnect.java:45,116)
- net/minecraft/command → `ICommandSender`(RConConsoleSource 实现)、`CommandBlockLogic`(NetHandlerPlayServer.java:1362)

## 覆盖声明

完整读取了 34/34 个文件(全部逐行 Read,无抽样)。

逐行精读:NetworkManager、NetworkSystem、NetHandlerPlayServer、EnumConnectionState、PacketBuffer(自定义方法部分)、PacketThreadUtil、ThreadQuickExitException、NettyCompressionDecoder、NettyCompressionEncoder、NettyEncryptingDecoder、NettyEncryptingEncoder、NettyEncryptionTranslator、PingResponseHandler、以及 handshake/login/status 全部封包与接口。

结构性浏览(读完全文但未逐行推演逻辑):PacketBuffer 的纯委托方法段(PacketBuffer.java:325-1243,均为单行转发 `this.buf`)、ServerStatusResponse 的三个 Gson Serializer(逐字段核对过 JSON 键名)、RConConsoleSource(全部为平凡实现)。

行号引用均来自本仓库当前源码;引用的包外调用点(Minecraft.java、GuiConnecting.java 等)经 Grep 核实。
