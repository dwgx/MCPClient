---
area: net/minecraft/network/play/server
slug: mc-network-play-server
files: 71
lines: 6696
tier: A
---

# net/minecraft/network/play/server — PLAY 阶段服务端→客户端封包（S 系列）

## 定位

本包是 PLAY 连接阶段全部 **clientbound**（服务端发往客户端）封包的定义，共 71 个顶层类（含内部类实际封包 74 种：`S14PacketEntity` 内嵌 `S15/S16/S17` 三个子封包）。每个类都是纯数据载体 + 序列化逻辑，统一实现 `Packet<INetHandlerPlayClient>` 接口的三个方法：`readPacketData(PacketBuffer)`、`writePacketData(PacketBuffer)`、`processPacket(INetHandlerPlayClient)`。

- **谁调用它**：`EnumConnectionState.PLAY` 在静态初始化时按顺序注册所有类（`EnumConnectionState.java:125-198`，注册顺序即协议 ID）。Netty 解码器按 VarInt ID 反射构造实例并调 `readPacketData`；随后 `NetworkManager.channelRead0`（`NetworkManager.java:149-155`）调 `packet.processPacket(this.packetListener)`，把封包分发到 `net.minecraft.client.network.NetHandlerPlayClient` 的对应 `handleXxx` 方法。
- **它调用谁**：`processPacket` 单向回调 `INetHandlerPlayClient.handleXxx(this)`；序列化依赖 `PacketBuffer` 的读写原语（VarInt、BlockPos、ItemStack、NBT、ChatComponent、UUID、Enum）。个别类携带业务逻辑：`S21PacketChunkData.getExtractedData` 直接从 `Chunk` 抽取字节流，`S34PacketMaps.setMapdataTo` 把数据写回 `MapData`，`S44PacketWorldBorder.func_179788_a` 直接驱动 `WorldBorder`。
- **消失会坏什么**：客户端将无法理解服务端的任何游戏状态推送——世界不加载（S21/S26）、实体不出现不移动（S0C/S0E/S0F/S14/S18）、聊天/血量/背包/计分板全部失效。这是整个多人游戏客户端状态同步的唯一入口。

## 类清单

行数为 `wc -l` 实测。所有类均 `implements Packet<INetHandlerPlayClient>`，故该列只标注额外结构。

| 类名 | 行数 | extends/implements（附内部类） | 一句话职责 |
|---|---|---|---|
| S00PacketKeepAlive | 49 | — | 心跳保活，客户端需原样回 `C00PacketKeepAlive` |
| S01PacketJoinGame | 129 | — | 进服初始化：entityId、游戏模式、维度、难度、世界类型 |
| S02PacketChat | 73 | — | 聊天/系统消息/action bar 文本（type 区分显示区域） |
| S03PacketTimeUpdate | 68 | — | 世界总时间与日时间同步（负数表示 doDayLightCycle 关闭） |
| S04PacketEntityEquipment | 68 | — | 单个实体某装备槽的 ItemStack 更新 |
| S05PacketSpawnPosition | 50 | — | 世界出生点（罗盘指向） |
| S06PacketUpdateHealth | 67 | — | 玩家血量/饥饿/饱和度 |
| S07PacketRespawn | 84 | — | 重生/切维度：维度 ID、难度、游戏模式、世界类型 |
| S08PacketPlayerPosLook | 149 | 内部 enum `EnumFlags` | 服务端强制设置玩家位置视角（flags 位标记相对/绝对） |
| S09PacketHeldItemChange | 49 | — | 服务端切换玩家手持栏索引 |
| S0APacketUseBed | 63 | — | 某玩家上床睡觉 |
| S0BPacketAnimation | 59 | — | 实体动画（挥手、受击等，type 编码） |
| S0CPacketSpawnPlayer | 135 | — | 生成其他玩家实体（定点数坐标 + DataWatcher 元数据） |
| S0DPacketCollectItem | 58 | — | 拾取物品动画（物品实体飞向收集者） |
| S0EPacketSpawnObject | 228 | — | 生成非生物对象实体（矢车/箭/掉落物等，type+data） |
| S0FPacketSpawnMob | 194 | — | 生成生物实体（含速度与 DataWatcher） |
| S10PacketSpawnPainting | 79 | — | 生成画实体（title 标识画作） |
| S11PacketSpawnExperienceOrb | 87 | — | 生成经验球 |
| S12PacketEntityVelocity | 114 | — | 实体速度设置（击退来源，×8000 定点数） |
| S13PacketDestroyEntities | 59 | — | 批量移除实体（entityId 数组） |
| S14PacketEntity | 207 | 内嵌 `S15PacketEntityRelMove`、`S16PacketEntityLook`、`S17PacketEntityLookMove`（均 extends S14PacketEntity） | 实体增量移动/转向基类（byte 级相对量） |
| S18PacketEntityTeleport | 116 | — | 实体绝对位置传送（超出相对移动范围时使用） |
| S19PacketEntityHeadLook | 60 | — | 实体头部朝向 yaw |
| S19PacketEntityStatus | 60 | — | 实体状态操作码（受伤/死亡/进食等，注意类名 S19 与实际协议 ID 不符，见陷阱） |
| S1BPacketEntityAttach | 68 | — | 实体骑乘/拴绳关系（leash 字节区分） |
| S1CPacketEntityMetadata | 68 | — | 实体 DataWatcher 元数据增量更新 |
| S1DPacketEntityEffect | 100 | — | 添加/刷新药水效果 |
| S1EPacketRemoveEntityEffect | 59 | — | 移除药水效果 |
| S1FPacketSetExperience | 67 | — | 玩家经验条/等级/总经验 |
| S20PacketEntityProperties | 127 | 内部类 `Snapshot` | 实体属性（attributes）及其 AttributeModifier 列表 |
| S21PacketChunkData | 161 | 静态内部类 `Extracted` | 单区块数据（方块 char 数组 + 光照 + 生物群系） |
| S22PacketMultiBlockChange | 108 | 内部类 `BlockUpdateData` | 同一区块内多个方块变更（short 压缩坐标） |
| S23PacketBlockChange | 62 | — | 单方块变更（BLOCK_STATE_IDS 编码） |
| S24PacketBlockAction | 84 | — | 方块事件（音符盒/活塞/箱子开合） |
| S25PacketBlockBreakAnim | 68 | — | 方块破坏进度裂纹动画（0-9） |
| S26PacketMapChunkBulk | 118 | — | 批量区块数据（复用 S21.Extracted） |
| S27PacketExplosion | 147 | — | 爆炸：中心、强度、受影响方块（相对 byte 偏移）、玩家击退速度 |
| S28PacketEffect | 81 | — | 世界音效/粒子事件（soundType + data，serverWide 全服播放） |
| S29PacketSoundEffect | 98 | — | 命名音效播放（坐标 ×8 定点数，pitch ×63） |
| S2APacketParticles | 189 | — | 粒子生成（EnumParticleTypes + 可变参数） |
| S2BPacketChangeGameState | 59 | — | 游戏状态变更（下雨/模式切换/演示提示等，state+float） |
| S2CPacketSpawnGlobalEntity | 92 | — | 全局实体生成（目前仅雷电 type=1） |
| S2DPacketOpenWindow | 109 | — | 打开容器 GUI（inventoryType 字符串区分，EntityHorse 额外带 entityId） |
| S2EPacketCloseWindow | 44 | — | 服务端强制关闭当前窗口 |
| S2FPacketSetSlot | 68 | — | 单个槽位 ItemStack 更新（windowId=-1 为鼠标携带物品） |
| S30PacketWindowItems | 77 | — | 整个窗口全部槽位刷新 |
| S31PacketWindowProperty | 67 | — | 窗口进度条属性（熔炉燃烧/附魔等级等） |
| S32PacketConfirmTransaction | 67 | — | 窗口事务确认（accepted 标志，反作弊/延迟测量常用） |
| S33PacketUpdateSign | 72 | — | 告示牌四行文本更新 |
| S34PacketMaps | 134 | — | 地图物品数据（图标 + 颜色区域增量） |
| S35PacketUpdateTileEntity | 71 | — | TileEntity NBT 更新（metadata 区分类型） |
| S36PacketSignEditorOpen | 50 | — | 打开告示牌编辑器 GUI |
| S37PacketStatistics | 72 | — | 统计数据（StatBase→int 映射） |
| S38PacketPlayerListItem | 271 | 内部 enum `Action`、内部类 `AddPlayerData` | Tab 列表增删改（含 GameProfile 皮肤属性） |
| S39PacketPlayerAbilities | 145 | — | 玩家能力位标记（无敌/飞行/创造）+ 飞行/行走速度 |
| S3APacketTabComplete | 59 | — | 命令补全候选列表 |
| S3BPacketScoreboardObjective | 86 | — | 计分板目标创建/删除/更新 |
| S3CPacketUpdateScore | 108 | 内部 enum `Action` | 计分板分数变更/移除 |
| S3DPacketDisplayScoreboard | 67 | — | 计分板显示位置（侧边栏/Tab/名字下方） |
| S3EPacketTeams | 187 | — | 队伍创建/删除/更新/加人/踢人（action 0-4） |
| S3FPacketCustomPayload | 73 | — | 插件通道自定义数据（channel + 原始 PacketBuffer，上限 1048576 字节） |
| S40PacketDisconnect | 50 | — | 服务端断开连接（附原因 ChatComponent） |
| S41PacketServerDifficulty | 57 | — | 服务端难度同步 |
| S42PacketCombatEvent | 96 | 内部 enum `Event` | 战斗事件（进入/结束/死亡，含死亡消息） |
| S43PacketCamera | 51 | — | 旁观者视角绑定到指定实体 |
| S44PacketWorldBorder | 185 | 内部 enum `Action` | 世界边界初始化/尺寸/中心/警告参数 |
| S45PacketTitle | 147 | 内部 enum `Type` | 标题/副标题/时间/清除/重置 |
| S46PacketSetCompressionLevel | 40 | — | 动态设置压缩阈值（直接改 Netty pipeline） |
| S47PacketPlayerListHeaderFooter | 58 | — | Tab 列表页眉/页脚 |
| S48PacketResourcePackSend | 63 | — | 资源包下载请求（url + sha1 hash，hash ≤40 字符） |
| S49PacketUpdateEntityNBT | 61 | — | 实体完整 NBT 推送 |

## 核心类详解

以下文件路径省略前缀 `client/src/main/java/net/minecraft/network/play/server/`。

### S01PacketJoinGame — 进服首包

字段（`S01PacketJoinGame.java:13-20`）：`int entityId`、`boolean hardcoreMode`、`WorldSettings.GameType gameType`、`int dimension`、`EnumDifficulty difficulty`、`int maxPlayers`、`WorldType worldType`、`boolean reducedDebugInfo`。

关键读取逻辑（`S01PacketJoinGame.java:41-59`）：gamemode 字节的 bit3 复用为 hardcore 标志：

```java
int i = buf.readUnsignedByte();
this.hardcoreMode = (i & 8) == 8;
i = i & -9;
this.gameType = WorldSettings.GameType.getByID(i);
```

`worldType` 解析失败回退 `WorldType.DEFAULT`（`S01PacketJoinGame.java:53-56`）。由 `NetHandlerPlayClient.handleJoinGame`（`NetHandlerPlayClient.java:277`）消费，创建 `WorldClient` 并设置本地玩家 entityId——是客户端世界生命周期的起点。

### S08PacketPlayerPosLook — 服务端位置矫正

字段（`S08PacketPlayerPosLook.java:12-17`）：`double x, y, z`、`float yaw, pitch`、`Set<S08PacketPlayerPosLook.EnumFlags> field_179835_f`。

内部枚举 `EnumFlags`（`S08PacketPlayerPosLook.java:97-148`）：`X(0), Y(1), Z(2), Y_ROT(3), X_ROT(4)`，对应位在 flags 字节中置位表示该分量为**相对值**（叠加在当前值上），未置位为绝对值。位掩码互转：

```java
public static Set<S08PacketPlayerPosLook.EnumFlags> func_180053_a(int p_180053_0_)
public static int func_180056_a(Set<S08PacketPlayerPosLook.EnumFlags> p_180056_0_)
```

由 `NetHandlerPlayClient.handlePlayerPosLook`（`NetHandlerPlayClient.java:669`）消费，客户端必须回 `C03PacketPlayer` 确认，否则位置不同步。反作弊回弹（rubber-band）即由此包实现。

### S14PacketEntity 家族 — 实体增量移动

基类字段（`S14PacketEntity.java:12-19`）：`protected int entityId; protected byte posX, posY, posZ; protected byte yaw, pitch; protected boolean onGround; protected boolean field_149069_g;`（`field_149069_g` = 是否携带转向数据）。

- 基类本身只序列化 entityId（`S14PacketEntity.java:33-44`），作为"无移动"心跳。
- `S15PacketEntityRelMove`（`S14PacketEntity.java:99-131`）：附加 `posX/posY/posZ` 三个 byte（1/32 格定点相对位移）+ onGround。
- `S16PacketEntityLook`（`S14PacketEntity.java:133-164`）：附加 yaw/pitch byte（256 = 360°），构造器置 `field_149069_g = true`。
- `S17PacketEntityLookMove`（`S14PacketEntity.java:166-206`）：位移 + 转向合并。

取值方法为 SRG 名：`func_149062_c()/func_149061_d()/func_149064_e()`（xyz）、`func_149066_f()/func_149063_g()`（yaw/pitch）、`func_149060_h()`（是否有转向）。三个子类分别在 `EnumConnectionState.java:146-148` 注册为独立协议 ID。由 `handleEntityMovement` 消费。相对位移 byte 范围只有 ±4 格（±127/32），超出改发 `S18PacketEntityTeleport`。

### S21PacketChunkData / S26PacketMapChunkBulk — 区块数据

`S21PacketChunkData` 字段（`S21PacketChunkData.java:14-17`）：`int chunkX; int chunkZ; S21PacketChunkData.Extracted extractedData; boolean field_149279_g;`（`field_149279_g` = groundUpContinuous，true 表示整块新加载并携带生物群系）。

静态内部类 `Extracted`（`S21PacketChunkData.java:156-160`）：`public byte[] data; public int dataSize;`（dataSize 是 16 个 section 的位掩码）。

尺寸计算与抽取（逐字签名）：

```java
protected static int func_180737_a(int p_180737_0_, boolean p_180737_1_, boolean p_180737_2_)   // S21PacketChunkData.java:69
public static S21PacketChunkData.Extracted getExtractedData(Chunk p_179756_0_, boolean p_179756_1_, boolean p_179756_2_, int p_179756_3_)   // S21PacketChunkData.java:78
```

`func_180737_a` 中每 section 大小 = 方块数据 `2*16*16*16` 字节（char LSB-first，`S21PacketChunkData.java:100-107`）+ 方块光 `16*16*16/2` + 天空光（有天空时）`16*16*16/2`，groundUp 时末尾附 256 字节生物群系。`S26PacketMapChunkBulk.readPacketData`（`S26PacketMapChunkBulk.java:42-63`）先读所有 (x,z,dataSize) 头，再按 `func_180737_a` 预分配并连续读入各区块字节。二者分别由 `handleChunkData`/`handleMapChunkBulk` 消费，最终进入 `Chunk.fillChunk`。

### S22PacketMultiBlockChange — 压缩多方块变更

内部类 `BlockUpdateData`（`S22PacketMultiBlockChange.java:76-107`）持有 `short chunkPosCrammed`（高 4 位 x、次 4 位 z、低 8 位 y）与 `IBlockState blockState`。解码（`S22PacketMultiBlockChange.java:95`）：

```java
public BlockPos getPos()
{
    return new BlockPos(S22PacketMultiBlockChange.this.chunkPosCoord.getBlock(this.chunkPosCrammed >> 12 & 15, this.chunkPosCrammed & 255, this.chunkPosCrammed >> 8 & 15));
}
```

方块状态经 `Block.BLOCK_STATE_IDS` 全局 palette 编解码（`S22PacketMultiBlockChange.java:43,59`），`S23PacketBlockChange` 同理（`S23PacketBlockChange.java:33,42`）。

### S0EPacketSpawnObject / S0FPacketSpawnMob / S0CPacketSpawnPlayer — 实体生成三件套

共同点：坐标为 `MathHelper.floor_double(pos * 32.0D)` 定点 int，角度为 `(byte)((int)(rot * 256.0F / 360.0F))`。

- `S0EPacketSpawnObject`（对象实体）：`field_149020_k` 为 data 值（如抛射物 owner id），**仅当 > 0 才附带速度**（`S0EPacketSpawnObject.java:101-106`）；速度 clamp 到 ±3.9 后 ×8000（`S0EPacketSpawnObject.java:44-84`）。提供 `setX/setY/setZ/setSpeedX/setSpeedY/setSpeedZ/func_149002_g` 修改器（`S0EPacketSpawnObject.java:194-227`），是包内少数可变封包。
- `S0FPacketSpawnMob`（生物）：额外带 `headPitch` 与 `DataWatcher` 列表；`func_149027_c()`（`S0FPacketSpawnMob.java:130-138`）惰性读取 `field_149043_l.getAllWatched()`。
- `S0CPacketSpawnPlayer`（玩家）：以 `UUID playerId` 关联 tab 列表中的 `GameProfile`（须先收到 `S38PacketPlayerListItem ADD_PLAYER`）；`currentItem` 为手持物品 Item ID（`S0CPacketSpawnPlayer.java:42`）；`func_148944_c()` 同样惰性取元数据（`S0CPacketSpawnPlayer.java:86-94`）。

### S38PacketPlayerListItem — Tab 列表

`Action` 枚举（`S38PacketPlayerListItem.java:222-229`）：`ADD_PLAYER, UPDATE_GAME_MODE, UPDATE_LATENCY, UPDATE_DISPLAY_NAME, REMOVE_PLAYER`。`readPacketData`（`S38PacketPlayerListItem.java:48-118`）按 action 分支读取：ADD_PLAYER 携带完整 `GameProfile`（UUID + name ≤16 + properties 三元组，含皮肤签名 `Property(s, s1, buf.readStringFromBuffer(32767))`，`S38PacketPlayerListItem.java:74`）。内部类 `AddPlayerData`（`S38PacketPlayerListItem.java:231-270`）为不可变条目：`ping/gamemode/profile/displayName`。皮肤系统、头颅渲染、玩家可见性全部依赖此包。

### S44PacketWorldBorder — 自应用型封包

`Action` 枚举（`S44PacketWorldBorder.java:176-184`）：`SET_SIZE, LERP_SIZE, SET_CENTER, INITIALIZE, SET_WARNING_TIME, SET_WARNING_BLOCKS`。特殊在于封包自带应用逻辑：

```java
public void func_179788_a(WorldBorder border)   // S44PacketWorldBorder.java:134
```

直接调用 `border.setTransition/setCenter/setSize/setWarningDistance/setWarningTime`，handler 只需转发 world border 实例。

### S34PacketMaps — 同为自应用型

```java
public void setMapdataTo(MapData mapdataIn)   // S34PacketMaps.java:115
```

把 `mapVisiblePlayersVec4b` 图标写入 `mapdataIn.mapDecorations`（key 为 `"icon-" + i`）、把增量颜色矩形写回 `mapdataIn.colors[128×128]`（`S34PacketMaps.java:117-132`）。图标编码：一个 byte 高 4 位 type、低 4 位 rotation（`S34PacketMaps.java:57-58`）。

### S3FPacketCustomPayload — 插件通道

字段：`String channel`（≤20 字符）、`PacketBuffer data`。构造与读取双向强制 1 MiB 上限（`S3FPacketCustomPayload.java:23-27, 37-44`）。原版用于 `MC|Brand`、`MC|BOpen`、`MC|TrList` 等；任何自定义客户端-服务端私有协议都从这里走。

### S46PacketSetCompressionLevel — 管线控制包

仅一个 `int threshold`。`NetHandlerPlayClient.handleSetCompressionLevel`（`NetHandlerPlayClient.java:1592-1598`）**不经过主线程 enqueue**，在 Netty EventLoop 上直接 `this.netManager.setCompressionTreshold(packetIn.getThreshold())`——必须如此，否则后续已压缩的封包会在重排队期间用旧管线解码失败。本仓库近期 Netty 4.1.124→4.2.16 升级即以压缩分帧 golden test 守护此路径（见 git log）。

### S32PacketConfirmTransaction — 窗口事务

字段：`int windowId; short actionNumber; boolean field_148893_c;`（`field_148893_c` = accepted）。客户端若收到 accepted=false 需回 `C0FPacketConfirmTransaction` 应答。因服务端对每次点击都回发递增 `actionNumber`，功能层常借它做精确延迟测量与"lag-back"检测。

### S42PacketCombatEvent — 死亡界面入口

`Event` 枚举（`S42PacketCombatEvent.java:90-95`）：`ENTER_COMBAT, END_COMBAT, ENTITY_DIED`。字段全部 `public`（`S42PacketCombatEvent.java:12-16`）：`eventType/field_179774_b`（死亡实体 id）`/field_179775_c`（对手 id）`/field_179772_d`（战斗时长）`/deathMessage`。ENTITY_DIED 时触发客户端死亡界面。

## 时序与生命周期

本包自身无 tick/帧逻辑，封包对象生命周期 = 单次收发；但其**解码与分发路径有严格的线程时序**：

1. **注册（一次性）**：`EnumConnectionState` 类加载时，PLAY 状态按 `EnumConnectionState.java:125-198` 的 `registerPacket(EnumPacketDirection.CLIENTBOUND, ...)` 顺序分配协议 ID（0x00–0x49）。
2. **解码（Netty EventLoop）**：解压/分帧后，`PacketBuffer` 交给按 ID new 出来的封包实例执行 `readPacketData`。
3. **分发（Netty EventLoop）**：`NetworkManager.channelRead0`（`NetworkManager.java:149-155`）调 `processPacket(packetListener)`。
4. **线程切换（大多数封包）**：`NetHandlerPlayClient.handleXxx` 首行调 `PacketThreadUtil.checkThreadAndEnqueue(packetIn, this, this.gameController)`（`PacketThreadUtil.java:7`）；若当前不在主线程，则把整个 `processPacket` 重新入队到 `Minecraft` 主线程任务队列，并抛 `ThreadQuickExitException.INSTANCE`（`PacketThreadUtil.java:18`）中止本次 Netty 线程执行。真正的状态修改发生在**下一次主线程 tick 的任务排空阶段**。
5. **Netty 线程直通的例外**：`handleKeepAlive`（`NetHandlerPlayClient.java:1663-1666`，立即回包降低延迟）、`handleDisconnect`（`NetHandlerPlayClient.java:785-788`）、`handleSetCompressionLevel`（`NetHandlerPlayClient.java:1592-1598`）、`handlePlayerListHeaderFooter`（`NetHandlerPlayClient.java:1600-1604`）、`handleResourcePack`（`NetHandlerPlayClient.java:1701` 起）不做 enqueue，直接在 EventLoop 上生效。
6. **典型进服序列**（`ServerConfigurationManager.java:143-148` 实测发包顺序）：登录压缩设定 →（进入 PLAY）`S01PacketJoinGame` → `S3FPacketCustomPayload(MC|Brand)` → `S41PacketServerDifficulty` → `S05PacketSpawnPosition` → `S39PacketPlayerAbilities` → `S09PacketHeldItemChange` → `S38PacketPlayerListItem(ADD_PLAYER)` → `S26PacketMapChunkBulk`/`S21PacketChunkData` 批量 → `S08PacketPlayerPosLook`（首次位置确认后玩家才真正进入世界）→ 常态循环：每秒级 `S00PacketKeepAlive`、每 tick 级 S14 家族/S1C/S12 实体流与 `S03PacketTimeUpdate`。
7. **写方向**：客户端正常不发送 S 系列（`writePacketData` 供本地集成服务端 loopback 与测试使用）。

## 挂钩点（Hook Points）

本包类是纯数据类，功能层挂钩有两种形态：**(a)** 在 `NetworkManager.channelRead0` / `processPacket` 处做包级拦截（改字段、吞包、注入）；**(b)** 在 `NetHandlerPlayClient.handleXxx` 处做语义级挂钩。下表列出最值得接管的点，`文件:行号` 指本包内定义处。

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void processPacket(INetHandlerPlayClient handler)`（所有 74 种封包统一入口） | 各文件，如 S00PacketKeepAlive.java:24 | `NetworkManager.channelRead0`（NetworkManager.java:149-155），Netty EventLoop | 全局收包事件总线：拦截/取消/改写任意 clientbound 包 | 此刻在 Netty 线程，不得触碰世界状态；吞掉 S00/S08 会导致踢出或位置失步 |
| `public void readPacketData(PacketBuffer buf) throws IOException`（各封包） | 各文件，如 S01PacketJoinGame.java:41 | 解码器构造实例后立即调用，Netty EventLoop | 协议级观察/记录原始字节；协议兼容层改写 | 读取长度必须与写入端严格一致，多读少读都会毁掉整条流 |
| `public void processPacket(INetHandlerPlayClient handler)`（S02） | S02PacketChat.java:50 | 收到聊天/actionbar 时 | 聊天过滤、命令劫持、自动回复；`getType()==2` 区分 actionbar | 修改 `chatComponent` 需保持 IChatComponent 结构合法 |
| `public void processPacket(INetHandlerPlayClient handler)`（S08） | S08PacketPlayerPosLook.java:62 | 服务端矫正玩家位置（传送、回弹） | Blink/反回弹类功能的核心观察点；读 `func_179834_f()` 判断相对/绝对 | 不确认此包（C03 应答）会被服务端持续重发直至踢出 |
| `public void processPacket(INetHandlerPlayClient handler)`（S12） | S12PacketEntityVelocity.java:90 | 服务端设置实体速度（击退/爆炸推力） | Velocity/AntiKnockback：按比例缩放 `motionX/Y/Z` 或对本地玩家吞包 | 仅当 `entityID == 本地玩家` 时改动才是击退调整；动其他实体会造成视觉漂移 |
| `public void processPacket(INetHandlerPlayClient handler)`（S27） | S27PacketExplosion.java:103 | 爆炸发生时 | 读取 `func_149149_c()/func_149144_d()/func_149147_e()` 拦截爆炸击退 | 爆炸方块列表照常应用，否则与服务端方块状态分叉 |
| `public void processPacket(INetHandlerPlayClient handler)`（S0C） | S0CPacketSpawnPlayer.java:81 | 玩家实体进入视距 | ESP/雷达登记目标；关联 `getPlayer()` UUID 与 tab 资料 | 必须先有 S38 ADD_PLAYER，否则客户端丢弃该玩家（皮肤/名字缺失） |
| `public void processPacket(INetHandlerPlayClient handler)`（S0E/S0F） | S0EPacketSpawnObject.java:134 / S0FPacketSpawnMob.java:125 | 对象/生物实体生成 | 投掷物预警（type 判断）、生物 ESP | S0E 速度字段仅 `func_149009_m() > 0` 时存在 |
| `public void processPacket(INetHandlerPlayClient handler)`（S13） | S13PacketDestroyEntities.java:50 | 实体移除（离开视距/死亡） | 目标列表清理、掉落物追踪结束 | 未清理引用会导致悬挂 entityId 被后续复用 |
| `public void processPacket(INetHandlerPlayClient handler)`（S14 及 S15/S16/S17） | S14PacketEntity.java:49 | 每 tick 实体增量移动 | 运动插值观察、backtrack 类功能的数据源 | 高频包（每实体每 tick），挂钩内勿做重活 |
| `public void processPacket(INetHandlerPlayClient handler)`（S18） | S18PacketEntityTeleport.java:77 | 实体大幅位移 | 实体瞬移检测；与 S14 相对流互补 | 坐标为 ×32 定点 int，需 `/32.0D` 还原 |
| `public void processPacket(INetHandlerPlayClient handler)`（S1C） | S1CPacketEntityMetadata.java:54 | DataWatcher 变更（着火/隐身/血量等） | 实体状态监听（如 index 6 血量做击杀特效） | WatchableObject 列表可为增量，勿假设全量 |
| `public void processPacket(INetHandlerPlayClient handler)`（S00） | S00PacketKeepAlive.java:24 | 服务端心跳（秒级） | 精确 ping 测量（配合回包时间戳） | handler 在 Netty 线程直接回包（NetHandlerPlayClient.java:1663），延迟应答会被判定超时 |
| `public void processPacket(INetHandlerPlayClient handler)`（S32） | S32PacketConfirmTransaction.java:28 | 每次窗口事务/服务端主动 ping 槽位同步 | 事务式延迟测量、lag-back 判定 | `actionNumber` 有符号 short，会回绕 |
| `public void processPacket(INetHandlerPlayClient handler)`（S2D） | S2DPacketOpenWindow.java:43 | 服务端要求打开容器 GUI | 容器自动化（ChestStealer 等）入口；`getGuiId()` 分流 | `"EntityHorse"` 分支多读一个 int，拦截改写时注意 |
| `public void processPacket(INetHandlerPlayClient handler)`（S2E） | S2EPacketCloseWindow.java:24 | 服务端强制关窗 | GUI 关闭钩子；防止服务端打断本地界面 | 吞包会造成窗口 id 失同步，后续 S2F/S30 落错窗口 |
| `public void processPacket(INetHandlerPlayClient handler)`（S2F/S30/S31） | S2FPacketSetSlot.java:29 / S30PacketWindowItems.java:63 / S31PacketWindowProperty.java:28 | 槽位/整窗/进度更新 | 背包状态镜像、自动补货判定 | S2F `windowId=-1` 表示鼠标携带堆，`0` 为玩家背包 |
| `public void processPacket(INetHandlerPlayClient handler)`（S21/S26） | S21PacketChunkData.java:59 / S26PacketMapChunkBulk.java:89 | 区块载入/卸载（S21 全空 section + groundUp 即卸载） | 世界扫描（矿物/容器搜索）在解码后的数据上进行 | 数据是原始 byte[]，需按 `func_180737_a` 布局手动解析；主线程应用前世界尚无此区块 |
| `public void processPacket(INetHandlerPlayClient handler)`（S23/S22） | S23PacketBlockChange.java:48 / S22PacketMultiBlockChange.java:66 | 方块变更 | 破坏/放置监听、幻影方块修正 | state 经 `Block.BLOCK_STATE_IDS` 编码，palette 不一致直接得到错方块 |
| `public void processPacket(INetHandlerPlayClient handler)`（S38） | S38PacketPlayerListItem.java:202 | tab 列表变化 | 玩家加入/离开事件、皮肤属性抓取、staff 检测 | UPDATE_* 分支 profile 只有 UUID（name 为 null），勿直接取名字 |
| `public void processPacket(INetHandlerPlayClient handler)`（S40） | S40PacketDisconnect.java:41 | 服务端断连 | 自动重连、断线原因记录 | handler 在 Netty 线程直接 closeChannel（NetHandlerPlayClient.java:785），挂钩要快进快出 |
| `public void processPacket(INetHandlerPlayClient handler)`（S45/S47） | S45PacketTitle.java:83 / S47PacketPlayerListHeaderFooter.java:44 | 标题/Tab 页眉页脚推送 | HUD 层改写或屏蔽服务端标题 | S45 TIMES/CLEAR/RESET 分支无 message，读 `getMessage()` 可能为 null |
| `public void processPacket(INetHandlerPlayClient handler)`（S3B/S3C/S3D/S3E） | S3BPacketScoreboardObjective.java:62 等 | 计分板/队伍变更 | 侧边栏改写、队友识别（S3E `getPlayers()`） | S3E action 语义见协议表；错误 action 组合构造器直接抛 IllegalArgumentException（S3EPacketTeams.java:61-74） |
| `public void processPacket(INetHandlerPlayClient handler)`（S3F） | S3FPacketCustomPayload.java:59 | 插件消息到达 | 自定义通道协议、服务端指纹识别（MC\|Brand） | `getBufferData()` 是同一 ByteBuf 引用，读完注意 readerIndex/释放 |
| `public void processPacket(INetHandlerPlayClient handler)`（S43） | S43PacketCamera.java:42 | 旁观视角切换 | 摄像机接管、自由视角功能 | 绑定到不存在的 entityId 时 `getEntity(worldIn)` 返回 null |
| `public void processPacket(INetHandlerPlayClient handler)`（S46） | S46PacketSetCompressionLevel.java:31 | 服务端动态调整压缩阈值 | 基本只应观察，不应改写 | 必须留在 Netty 线程同步生效（NetHandlerPlayClient.java:1592-1598）；enqueue 到主线程会毁掉整条连接 |
| `public void processPacket(INetHandlerPlayClient handler)`（S48） | S48PacketResourcePackSend.java:49 | 服务端要求下载资源包 | URL 审计/拒绝策略（隐私考虑） | 吞包后需自行回 status，否则部分服务端会踢人 |
| `public void func_179788_a(WorldBorder border)` | S44PacketWorldBorder.java:134 | handler 处理 world border 包时 | 边界可视化、逃逸预警的数据源 | 直接修改传入的 `WorldBorder` 单例，多世界切换时勿缓存旧实例 |
| `public void setMapdataTo(MapData mapdataIn)` | S34PacketMaps.java:115 | handler 处理地图包时 | 地图数据镜像/导出 | 只写增量矩形，首包前 `colors` 是全零 |

## 数据与协议

协议 ID 由 `EnumConnectionState.java:125-198` 的注册顺序决定（`S00`→0x00 … `S49`→0x49，S14 内部三子类占 0x15/0x16/0x17）。下面按复杂度给出字段级线格式；"读方法"均指 `PacketBuffer` 方法，写方向与之对称（不对称处单独注明）。

### 通用编码约定

| 概念 | 编码 |
|---|---|
| 实体坐标（生成/传送包） | `int` = `MathHelper.floor_double(pos * 32.0D)`，即 1/32 格定点数 |
| 相对位移（S15/S17） | `byte`，1/32 格，范围 ±3.96875 格 |
| 角度 | `byte` = `(int)(deg * 256.0F / 360.0F)` |
| 速度 | `short` = `(int)(motion * 8000.0D)`，构造时 clamp 到 ±3.9 |
| 方块状态 | VarInt = `Block.BLOCK_STATE_IDS.get(state)` |
| 实体元数据 | `DataWatcher.readWatchedListFromPacketBuffer(buf)` 自终止列表 |

### 复杂封包字段表

**S01PacketJoinGame**（`S01PacketJoinGame.java:41-59`）

| 字段 | 类型 | 读方法 | 含义 |
|---|---|---|---|
| entityId | int | `readInt` | 本地玩家实体 ID |
| gameType+hardcoreMode | unsigned byte | `readUnsignedByte` | bit0-2 gamemode，bit3 hardcore（`(i & 8) == 8`，再 `i & -9` 取模式） |
| dimension | byte | `readByte` | -1 下界 / 0 主世界 / 1 末地 |
| difficulty | unsigned byte | `readUnsignedByte` → `EnumDifficulty.getDifficultyEnum` | 难度 |
| maxPlayers | unsigned byte | `readUnsignedByte` | 服务器最大人数（tab 渲染用） |
| worldType | String(16) | `readStringFromBuffer(16)` → `WorldType.parseWorldType`，null 回退 `WorldType.DEFAULT` | 地形类型 |
| reducedDebugInfo | boolean | `readBoolean` | F3 信息缩减 |

**S08PacketPlayerPosLook**（`S08PacketPlayerPosLook.java:36-44`）

| 字段 | 类型 | 读方法 | 含义 |
|---|---|---|---|
| x, y, z | double ×3 | `readDouble` | 目标坐标（按 flags 可为相对增量） |
| yaw, pitch | float ×2 | `readFloat` | 目标视角 |
| field_179835_f | unsigned byte → `Set<EnumFlags>` | `EnumFlags.func_180053_a(buf.readUnsignedByte())` | bit0 X, bit1 Y, bit2 Z, bit3 Y_ROT, bit4 X_ROT；置位 = 相对值 |

**S0EPacketSpawnObject**（`S0EPacketSpawnObject.java:90-107`）

| 字段 | 类型 | 读方法 | 含义 |
|---|---|---|---|
| entityId | VarInt | `readVarIntFromBuffer` | 实体 ID |
| type | byte | `readByte` | 对象类型（EntityList 对象表） |
| x, y, z | int ×3 | `readInt` | ×32 定点坐标 |
| pitch, yaw | byte ×2 | `readByte` | ×256/360 角度 |
| field_149020_k | int | `readInt` | data 值（如抛射物 owner+1）；>0 时后随速度 |
| speedX/Y/Z | short ×3（条件） | `readShort` | ×8000 速度，仅 `field_149020_k > 0` 存在 |

**S0FPacketSpawnMob**（`S0FPacketSpawnMob.java:87-101`）：VarInt entityId、`readByte() & 255` type、int×3 坐标、byte×3（yaw/pitch/headPitch）、short×3 速度、DataWatcher 列表。

**S0CPacketSpawnPlayer**（`S0CPacketSpawnPlayer.java:49-60`）：VarInt entityId、UUID playerId（`readUuid`）、int×3 坐标、byte×2 角度、short currentItem（手持 Item ID，0=空手）、DataWatcher 列表。

**S14PacketEntity 家族**（`S14PacketEntity.java:114-205`）

| 封包 | 附加字段（基类先读 VarInt entityId） |
|---|---|
| S14PacketEntity | 无（仅 entityId） |
| S15PacketEntityRelMove | byte posX, posY, posZ; boolean onGround |
| S16PacketEntityLook | byte yaw, pitch; boolean onGround |
| S17PacketEntityLookMove | byte posX, posY, posZ, yaw, pitch; boolean onGround |

**S21PacketChunkData**（`S21PacketChunkData.java:34-42`）

| 字段 | 类型 | 读方法 | 含义 |
|---|---|---|---|
| chunkX, chunkZ | int ×2 | `readInt` | 区块坐标 |
| field_149279_g | boolean | `readBoolean` | groundUpContinuous：整块加载（携带生物群系；配合空掩码 = 卸载） |
| extractedData.dataSize | short | `readShort` | 16 个 section 存在位掩码 |
| extractedData.data | byte[] | `readByteArray` | 布局：各 section 方块 char[4096]（低字节在前）→ 各 section 方块光 nibble[2048] → （有天空）天空光 nibble[2048] → （groundUp）biome[256] |

**S26PacketMapChunkBulk**（`S26PacketMapChunkBulk.java:42-63`）：boolean isOverworld、VarInt count、count×(int x, int z, short dataSize)，随后 count 个连续 data 块（长度由 `S21PacketChunkData.func_180737_a(Integer.bitCount(dataSize), isOverworld, true)` 推出）。

**S22PacketMultiBlockChange**（`S22PacketMultiBlockChange.java:36-45`）：int chunkX、int chunkZ、VarInt count、count×(short crammedPos, VarInt stateId)；crammedPos 布局 `x<<12 | z<<8 | y`。

**S27PacketExplosion**（`S27PacketExplosion.java:46-69`）：float×3 中心（读入 double 字段）、float strength、int count、count×(byte dx, byte dy, byte dz)（相对 `(int)pos` 的偏移）、float×3 玩家击退速度。

**S34PacketMaps**（`S34PacketMaps.java:49-70`）：VarInt mapId、byte scale、VarInt iconCount、iconCount×(byte type<<4\|rotation, byte x, byte z)、unsigned byte columns（mapMaxX）；columns>0 时再读 rows、offsetX、offsetZ、`readByteArray` 颜色数据。

**S38PacketPlayerListItem**（`S38PacketPlayerListItem.java:48-118`）

| Action | 每条目字段 |
|---|---|
| ADD_PLAYER | UUID、String name(16)、VarInt propCount、propCount×(String name, String value, boolean hasSig[, String signature])、VarInt gamemode、VarInt ping、boolean hasDisplayName[, ChatComponent] |
| UPDATE_GAME_MODE | UUID、VarInt gamemode |
| UPDATE_LATENCY | UUID、VarInt ping |
| UPDATE_DISPLAY_NAME | UUID、boolean hasDisplayName[, ChatComponent] |
| REMOVE_PLAYER | UUID |

**S3EPacketTeams**（`S3EPacketTeams.java:80-104`）：String name(16)、byte action（0 创建 / 1 删除 / 2 更新信息 / 3 加人 / 4 踢人）；action∈{0,2} 附 displayName(32)、prefix(16)、suffix(16)、byte friendlyFlags、nameTagVisibility(32)、byte color；action∈{0,3,4} 附 VarInt count + count×String player(40)。

**S44PacketWorldBorder**（`S44PacketWorldBorder.java:41-80`）：Enum action；SET_SIZE→double targetSize；LERP_SIZE→double diameter, double targetSize, VarLong time；SET_CENTER→double×2；SET_WARNING_BLOCKS/TIME→VarInt；INITIALIZE→double×4 + VarLong + VarInt×3。

**S45PacketTitle**（`S45PacketTitle.java:43-58`）：Enum type（TITLE/SUBTITLE/TIMES/CLEAR/RESET）；TITLE/SUBTITLE 附 ChatComponent；TIMES 附 int×3（fadeIn/display/fadeOut）。

**S42PacketCombatEvent**（`S42PacketCombatEvent.java:45-60`）：Enum eventType；END_COMBAT→VarInt duration + int opponentId；ENTITY_DIED→VarInt deadId + int opponentId + String deathMessage(32767)。

**S20PacketEntityProperties**（`S20PacketEntityProperties.java:36-56`）：VarInt entityId、**int**（非 VarInt）count、count×(String key(64), double baseValue, VarInt modCount, modCount×(UUID, double amount, byte operation))；反序列化的 modifier 名固定为 `"Unknown synced attribute modifier"`（`S20PacketEntityProperties.java:51`）。

### 简单封包速览

| 封包 | 线格式（顺序） |
|---|---|
| S00KeepAlive | VarInt id |
| S02Chat | ChatComponent、byte type（0/1 聊天区，2 actionbar） |
| S03TimeUpdate | long totalWorldTime、long worldTime（负值=daylight cycle 关） |
| S04EntityEquipment | VarInt entityID、short slot（0 手持 1-4 盔甲）、ItemStack |
| S05SpawnPosition | BlockPos |
| S06UpdateHealth | float health、VarInt food、float saturation |
| S07Respawn | int dimensionID、ubyte difficulty、ubyte gamemode、String worldType(16) |
| S09HeldItemChange | byte index |
| S0AUseBed | VarInt playerID、BlockPos |
| S0BAnimation | VarInt entityId、ubyte type |
| S0DCollectItem | VarInt collectedId、VarInt collectorId |
| S11SpawnExperienceOrb | VarInt id、int×3 坐标(×32)、short xpValue |
| S12EntityVelocity | VarInt id、short×3(×8000) |
| S13DestroyEntities | VarInt count、count×VarInt |
| S18EntityTeleport | VarInt id、int×3(×32)、byte×2 角度、boolean onGround |
| S19EntityHeadLook | VarInt id、byte yaw |
| S19EntityStatus | **int** id（非 VarInt）、byte opcode |
| S1BEntityAttach | **int** entityId、**int** vehicleId(-1 解绑)、ubyte leash |
| S1CEntityMetadata | VarInt id、DataWatcher 列表 |
| S1DEntityEffect | VarInt id、byte effectId、byte amplifier、VarInt duration（32767=∞ 哨兵，`func_149429_c`）、byte hideParticles |
| S1ERemoveEntityEffect | VarInt id、ubyte effectId |
| S1FSetExperience | float bar、VarInt level、VarInt total |
| S23BlockChange | BlockPos、VarInt stateId |
| S24BlockAction | BlockPos、ubyte data1、ubyte data2、VarInt blockId & 4095 |
| S25BlockBreakAnim | VarInt breakerId、BlockPos、ubyte progress |
| S28Effect | **int** soundType、BlockPos、**int** data、boolean serverWide |
| S29SoundEffect | String name(256)、int×3(×8)、float volume、ubyte pitch(÷63 还原) |
| S2AParticles | int particleID、boolean longDistance、float×7、int count、`getArgumentCount()` 个 VarInt |
| S2BChangeGameState | ubyte state、float value |
| S2CSpawnGlobalEntity | VarInt id、byte type(1=雷电)、int×3(×32) |
| S2DOpenWindow | ubyte windowId、String guiId(32)、ChatComponent title、ubyte slotCount、[guiId=="EntityHorse"→int entityId] |
| S2ECloseWindow | ubyte windowId |
| S2FSetSlot | **byte** windowId（可为 -1）、short slot、ItemStack |
| S30WindowItems | ubyte windowId、short count、count×ItemStack |
| S31WindowProperty | ubyte windowId、short varIndex、short varValue |
| S32ConfirmTransaction | ubyte windowId、short actionNumber、boolean accepted |
| S33UpdateSign | BlockPos、ChatComponent×4 |
| S35UpdateTileEntity | BlockPos、ubyte metadata（1 出怪笼 2 命令方块 3 信标 4 头颅 5 花盆 6 旗帜）、NBTTagCompound |
| S36SignEditorOpen | BlockPos |
| S37Statistics | VarInt count、count×(String statId(32767), VarInt value)；未知 statId 静默丢弃（S37PacketStatistics.java:47-50） |
| S39PlayerAbilities | byte flags（1 无敌 2 飞行中 4 可飞 8 创造）、float flySpeed、float walkSpeed |
| S3ATabComplete | VarInt count、count×String(32767) |
| S3BScoreboardObjective | String name(16)、byte mode（0 建 1 删 2 改）、[mode∈{0,2}→String value(32)、String renderType(16)] |
| S3CUpdateScore | String name(40)、Enum action、String objective(16)、[action!=REMOVE→VarInt value] |
| S3DDisplayScoreboard | byte position（0 tab 1 侧栏 2 名下）、String name(16) |
| S3FCustomPayload | String channel(20)、剩余字节全部为 payload（≤1048576） |
| S40Disconnect | ChatComponent reason |
| S41ServerDifficulty | ubyte difficulty（difficultyLocked **不上线**，见陷阱） |
| S43Camera | VarInt entityId |
| S46SetCompressionLevel | VarInt threshold |
| S47PlayerListHeaderFooter | ChatComponent header、ChatComponent footer |
| S48ResourcePackSend | String url(32767)、String hash(40) |
| S49UpdateEntityNBT | VarInt entityId、NBTTagCompound |

## 不变量与陷阱

- **线程边界**：`processPacket` 一定在 Netty EventLoop 被首调；除 S00/S40/S46/S47/S48 五个直通外，所有 handler 都靠 `PacketThreadUtil.checkThreadAndEnqueue` 抛 `ThreadQuickExitException` 二段式转主线程。**在封包层挂钩时读到的字段是安全的（对象已构造完），但绝不能在 Netty 线程改世界状态**；同一封包对象会被 `processPacket` 执行两次（第一次抛异常中止，第二次在主线程完整跑），挂钩逻辑必须幂等或自行判线程。
- **S46 不可重排队**：`handleSetCompressionLevel` 在 Netty 线程同步改压缩阈值（`NetHandlerPlayClient.java:1592-1598`）。若把它挪到主线程，EventLoop 会继续用旧配置解后续帧，连接立刻损坏。本仓库 Netty 升级（4.1.124→4.2.16）时专门加了压缩分帧 golden test 守护这条路径。
- **类名 ≠ 协议 ID**：存在两个 "S19"——`S19PacketEntityHeadLook` 与 `S19PacketEntityStatus`。真实 ID 由 `EnumConnectionState.java:125-198` 注册顺序决定（EntityStatus 实为 0x1A 起的顺延）。按类名前缀推断 ID 会错。
- **序列化不对称的字段**：`S41PacketServerDifficulty.difficultyLocked` 只在构造器赋值，`readPacketData`/`writePacketData` 都不含它（`S41PacketServerDifficulty.java:35-46`）——网络收到的包该字段恒 false。`S33PacketUpdateSign.world` 同样不上线（`S33PacketUpdateSign.java:13,31-53`）。`S47PacketPlayerListHeaderFooter(IChatComponent headerIn)` 构造器不设 footer（`S47PacketPlayerListHeaderFooter.java:18-21`）；`writeChatComponent` 经 `GSON.toJson` 会把 null 序列化为字符串 `"null"`（`PacketBuffer.java:82-85`、`IChatComponent.java:275-278`），接收端反序列化得到 null footer，由下游 GUI 自行承担空值。
- **构造与读取的类型差**：`S0CPacketSpawnPlayer.currentItem` 写侧来自 Item ID int，读侧是 `readShort()`（`S0CPacketSpawnPlayer.java:58,74`）。`S20PacketEntityProperties` 的属性条数用 `readInt` 而非 VarInt（`S20PacketEntityProperties.java:39`）。`S19PacketEntityStatus`、`S1BPacketEntityAttach`、`S28PacketEffect` 的实体/数据字段是裸 int，与包内其他 VarInt 风格不一致——手写协议兼容层最易在这几处翻车。
- **惰性双字段模式**：S0C/S0F/S1C 各持有一个 `DataWatcher` 引用（发送侧）和一个 `List<DataWatcher.WatchableObject>`（接收侧），`func_148944_c()`/`func_149027_c()` 首次调用才从 watcher 拉取（`S0CPacketSpawnPlayer.java:86-94`）。发送侧封包在写出前引用的是**活的** DataWatcher，延迟写出可能带上后续变更。
- **可变封包**：`S0EPacketSpawnObject` 提供 setter（`S0EPacketSpawnObject.java:194-227`），vanilla 服务端在发送前会用它修正抛射物参数；拦截层不要假设封包不可变。
- **S29 的哑代码**：`S29PacketSoundEffect` 构造器最后一行 `pitch = MathHelper.clamp_float(pitch, 0.0F, 255.0F);`（`S29PacketSoundEffect.java:32`）对局部变量赋值后即丢弃，clamp 实际无效——是 vanilla 原有 bug，移植保留，勿"顺手修复"导致与服务端行为分叉。
- **S2A 的容错回退**：未知粒子 ID 回退 `EnumParticleTypes.BARRIER`（`S2APacketParticles.java:53-56`）；参数个数由 `particleType.getArgumentCount()` 决定，改写 particleType 而不同步参数数组会破坏后续读取。
- **S1D 的 32767 哨兵**：duration ≥32767 压到 32767，`func_149429_c()` 以 `duration == 32767` 判"无限时长"（`S1DPacketEntityEffect.java:27-34,63-66`）。
- **S3F 的缓冲区所有权**：`getBufferData()` 返回内部 `PacketBuffer`（Netty ByteBuf 包装），多个挂钩重复读取需自行 `markReaderIndex/resetReaderIndex`；Netty 4.2 下引用计数语义更严格，泄漏会被 leak detector 报告。
- **坐标定点换算**：所有 ×32/×8000/×256÷360 的换算常量遍布本包（见协议表）。写功能时统一走 getter 换算，不要自己再除一遍。
- **LWJGL3/JDK25 移植面**：本包纯数据无渲染/输入依赖，未发现移植改动点；唯一外部敏感面是 Netty（S3F 的 `io.netty.buffer.ByteBuf` 直接 import，`S3FPacketCustomPayload.java:3`）。

## 交叉引用

- `net.minecraft.network` → `Packet`（全部 71 类实现）、`PacketBuffer`（全部读写原语）、`EnumConnectionState#registerPacket`（ID 分配，EnumConnectionState.java:125-198）、`NetworkManager#channelRead0`（分发，NetworkManager.java:149-155）、`PacketThreadUtil#checkThreadAndEnqueue`（线程切换）
- `net.minecraft.network.play` → `INetHandlerPlayClient`（每个封包的 `processPacket` 目标接口）
- `net.minecraft.client.network` → `NetHandlerPlayClient#handleJoinGame`/`#handlePlayerPosLook`/`#handleKeepAlive`/`#handleDisconnect`/`#handleSetCompressionLevel` 等全部 74 个 handler（唯一消费者）
- `net.minecraft.entity` → `DataWatcher#readWatchedListFromPacketBuffer`/`#writeWatchedListToPacketBuffer`/`#getAllWatched`/`#getChanged`（S0C/S0F/S1C）、`EntityList#getEntityID`（S0F）
- `net.minecraft.entity.ai.attributes` → `IAttributeInstance`、`AttributeModifier`（S20）
- `net.minecraft.block` → `Block.BLOCK_STATE_IDS`（S22/S23）、`Block#getBlockById`/`#getIdFromBlock`（S24）
- `net.minecraft.world.chunk` → `Chunk#getBlockStorageArray`/`#getBiomeArray`；`net.minecraft.world.chunk.storage` → `ExtendedBlockStorage#getData`/`#getBlocklightArray`/`#getSkylightArray`（S21）
- `net.minecraft.world.border` → `WorldBorder#setTransition`/`#setCenter`/`#setSize`/`#setWarningDistance`/`#setWarningTime`（S44#func_179788_a）
- `net.minecraft.world.storage` → `MapData.mapDecorations`/`MapData.colors`（S34#setMapdataTo）
- `net.minecraft.scoreboard` → `ScoreObjective`、`Score`、`ScorePlayerTeam`、`Team.EnumVisible`、`IScoreObjectiveCriteria.EnumRenderType`（S3B–S3E）
- `net.minecraft.stats` → `StatList#getOneShotStat`、`StatBase.statId`（S37）
- `net.minecraft.potion` → `PotionEffect#getPotionID`/`#getAmplifier`/`#getDuration`/`#getIsShowParticles`（S1D/S1E)
- `net.minecraft.util` → `BlockPos`、`IChatComponent`、`MathHelper#floor_double`、`EnumFacing#getHorizontal`（S10）、`EnumParticleTypes#getParticleFromId`（S2A）、`Vec4b`（S34）、`CombatTracker#func_94550_c`（S42）
- `net.minecraft.world` → `WorldSettings.GameType#getByID`、`EnumDifficulty#getDifficultyEnum`、`WorldType#parseWorldType`（S01/S07/S41）
- `com.mojang.authlib` → `GameProfile`、`properties.Property`（S38，皮肤签名链）
- `net.minecraft.entity.player` → `EntityPlayerMP`（S38 构造器，服务端侧类型出现在客户端源树中）

## 覆盖声明

完整读取了 **71/71** 个文件（每个文件从第 1 行读到末行，无抽样）。

逐行精读的类（结构 + 序列化逻辑 + 内部类全部核对）：S01PacketJoinGame、S08PacketPlayerPosLook、S0CPacketSpawnPlayer、S0EPacketSpawnObject、S0FPacketSpawnMob、S14PacketEntity（含 S15/S16/S17）、S20PacketEntityProperties、S21PacketChunkData、S22PacketMultiBlockChange、S26PacketMapChunkBulk、S27PacketExplosion、S34PacketMaps、S38PacketPlayerListItem、S3EPacketTeams、S42PacketCombatEvent、S44PacketWorldBorder、S45PacketTitle、S3FPacketCustomPayload、S46PacketSetCompressionLevel。

其余 52 个类为简单 DTO（字段 + 对称读写 + getter），同样全文读取并核对了字段类型、读写方法与 getter 名，只是无需逐行推演逻辑。

为核实分发与线程模型，额外结构性浏览（grep + 局部读取，未全文精读）：`net/minecraft/network/EnumConnectionState.java`（注册顺序 125-198）、`net/minecraft/network/NetworkManager.java`（channelRead0:149-155）、`net/minecraft/network/PacketThreadUtil.java`（checkThreadAndEnqueue:7,18）、`net/minecraft/client/network/NetHandlerPlayClient.java`（handleJoinGame:277、handlePlayerPosLook:669、handleDisconnect:785-788、handleSetCompressionLevel:1592-1598、handleKeepAlive:1663-1666）。这些行号已逐一验证。
