---
area: net/minecraft/network/play
slug: mc-network-play
files: 25
lines: 2302
tier: A
---

# net/minecraft/network/play — PLAY 状态协议接口与 serverbound 封包

## 定位

本包是 PLAY 连接状态（`EnumConnectionState.PLAY`）的协议层：

- 两个 handler 接口 `INetHandlerPlayClient` / `INetHandlerPlayServer`（均 `extends INetHandler`），定义了 PLAY 状态下双向所有封包的处理入口。前者由 `net.minecraft.client.network.NetHandlerPlayClient` 实现（`NetHandlerPlayClient.java:215`），后者由集成服务端的 `net.minecraft.network.NetHandlerPlayServer` 实现（`NetHandlerPlayServer.java:98`，`implements INetHandlerPlayServer, ITickable`）。
- `client/` 子目录下 23 个 serverbound 封包类（C00–C19，其中 C04/C05/C06 是 `C03PacketPlayer` 的静态内部子类），每个都 `implements Packet<INetHandlerPlayServer>`，只做三件事：`readPacketData` / `writePacketData`（线格式）和 `processPacket`（分派到 handler）。

调用关系：客户端游戏逻辑（`EntityPlayerSP`、`PlayerControllerMP`、各 `Gui*`、`GameSettings`）构造这些封包，经 `NetHandlerPlayClient#addToSendQueue(Packet)`（`NetHandlerPlayClient.java:814`）→ `NetworkManager#sendPacket` 出站；入站方向由 Netty 解码器按 `EnumConnectionState` 注册表（`EnumConnectionState.java:199-224`，SERVERBOUND）反射实例化（`oclass.newInstance()`，`EnumConnectionState.java:291`），调用 `readPacketData`，再由 `NetworkManager` 调 `processPacket` 分派给 `NetHandlerPlayServer`。

如果本包消失：PLAY 状态的全部 serverbound 通信（移动、挖掘、放置、聊天、容器操作、客户端设置……）无法编译/发送，客户端登录后立即失去与服务端的一切交互能力；clientbound 方向的 S 系封包也因 `INetHandlerPlayClient` 缺失而没有分派目标。

注意：本包本身不含任何逻辑，全部是数据载体 + 双分派入口。真正的处理逻辑在 `net.minecraft.client.network.NetHandlerPlayClient`（clientbound，另见 mc-network.md）和 `net.minecraft.network.NetHandlerPlayServer`（serverbound，另见 mc-network-play-server.md）。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| `INetHandlerPlayClient` | 385 | `extends INetHandler` | 定义 PLAY 状态全部 clientbound 封包（S00–S49）的 handle* 入口，共 66 个方法 |
| `INetHandlerPlayServer` | 144 | `extends INetHandler` | 定义 PLAY 状态全部 serverbound 封包（C00–C19）的 process*/handle* 入口，共 23 个方法 |
| `C00PacketKeepAlive` | 49 | `implements Packet<INetHandlerPlayServer>` | 回应服务端 keep-alive 的 key（VarInt） |
| `C01PacketChatMessage` | 54 | `implements Packet<INetHandlerPlayServer>` | 发送聊天/命令文本，构造时截断到 100 字符 |
| `C02PacketUseEntity` | 92 | `implements Packet<INetHandlerPlayServer>` | 对实体 INTERACT / ATTACK / INTERACT_AT（带命中点） |
| `C03PacketPlayer` | 200 | `implements Packet<INetHandlerPlayServer>` | 每 tick 移动包基类（仅 onGround），含 C04/C05/C06 三个子类 |
| `C07PacketPlayerDigging` | 81 | `implements Packet<INetHandlerPlayServer>` | 挖掘状态机 + 丢弃物品 + 释放使用（Action 枚举 6 值） |
| `C08PacketPlayerBlockPlacement` | 111 | `implements Packet<INetHandlerPlayServer>` | 方块放置/物品使用，含"对空气右键"特殊形态（pos=(-1,-1,-1), dir=255） |
| `C09PacketHeldItemChange` | 49 | `implements Packet<INetHandlerPlayServer>` | 切换快捷栏选中槽位（short） |
| `C0APacketAnimation` | 31 | `implements Packet<INetHandlerPlayServer>` | 挥手动画，零字段空载荷 |
| `C0BPacketEntityAction` | 79 | `implements Packet<INetHandlerPlayServer>` | 潜行/疾跑/起床/骑乘跳/开背包等实体动作（Action 枚举 7 值 + auxData） |
| `C0CPacketInput` | 91 | `implements Packet<INetHandlerPlayServer>` | 骑乘时上报移动输入（strafe/forward/jump/sneak） |
| `C0DPacketCloseWindow` | 44 | `implements Packet<INetHandlerPlayServer>` | 通知服务端关闭容器窗口 |
| `C0EPacketClickWindow` | 106 | `implements Packet<INetHandlerPlayServer>` | 容器槽位点击（windowId/slot/button/mode/actionNumber/预期物品） |
| `C0FPacketConfirmTransaction` | 62 | `implements Packet<INetHandlerPlayServer>` | 确认容器事务（apology 机制） |
| `C10PacketCreativeInventoryAction` | 59 | `implements Packet<INetHandlerPlayServer>` | 创造模式直接设置槽位物品（slotId=-1 为丢出） |
| `C11PacketEnchantItem` | 58 | `implements Packet<INetHandlerPlayServer>` | 附魔台按钮点击（windowId + button） |
| `C12PacketUpdateSign` | 73 | `implements Packet<INetHandlerPlayServer>` | 提交告示牌 4 行文本（IChatComponent JSON） |
| `C13PacketPlayerAbilities` | 135 | `implements Packet<INetHandlerPlayServer>` | 上报飞行状态切换（bit flags + 速度） |
| `C14PacketTabComplete` | 76 | `implements Packet<INetHandlerPlayServer>` | 请求命令补全（可带注视方块坐标） |
| `C15PacketClientSettings` | 81 | `implements Packet<INetHandlerPlayServer>` | 上报语言/视距/聊天可见性/皮肤部件设置 |
| `C16PacketClientStatus` | 56 | `implements Packet<INetHandlerPlayServer>` | 请求重生 / 请求统计 / 打开背包成就（EnumState 枚举 3 值） |
| `C17PacketCustomPayload` | 73 | `implements Packet<INetHandlerPlayServer>` | 插件通道消息（channel ≤20 字符，payload ≤32767 字节） |
| `C18PacketSpectate` | 52 | `implements Packet<INetHandlerPlayServer>` | 旁观模式传送到指定 UUID 实体 |
| `C19PacketResourcePackStatus` | 61 | `implements Packet<INetHandlerPlayServer>` | 回应服务端资源包请求（hash ≤40 字符 + Action 枚举 4 值） |

## 核心类详解

### INetHandlerPlayClient（INetHandlerPlayClient.java:76）

`public interface INetHandlerPlayClient extends INetHandler`，声明 66 个 `void handleXxx(SxxPacketYyy packetIn)` 方法，覆盖 S00–S49 全部 clientbound 封包（INetHandlerPlayClient.java:81-384）。代表性签名（逐字）：

- `void handleJoinGame(S01PacketJoinGame packetIn);` — INetHandlerPlayClient.java:259，进入世界的第一个 PLAY 包
- `void handleChunkData(S21PacketChunkData packetIn);` — INetHandlerPlayClient.java:249
- `void handlePlayerPosLook(S08PacketPlayerPosLook packetIn);` — INetHandlerPlayClient.java:273，服务端强制回拉位置
- `void handleEntityMovement(S14PacketEntity packetIn);` — INetHandlerPlayClient.java:266
- `void handleChat(S02PacketChat packetIn);` — INetHandlerPlayClient.java:156
- `void handleOpenWindow(S2DPacketOpenWindow packetIn);` — INetHandlerPlayClient.java:196
- `void handleDisconnect(S40PacketDisconnect packetIn);` — INetHandlerPlayClient.java:219
- `void handleSetCompressionLevel(S46PacketSetCompressionLevel packetIn);` — INetHandlerPlayClient.java:378
- `void handleResourcePack(S48PacketResourcePackSend packetIn);` — INetHandlerPlayClient.java:382
- `void handleEntityNBT(S49PacketUpdateEntityNBT packetIn);` — INetHandlerPlayClient.java:384

唯一实现是 `NetHandlerPlayClient`（`client/src/main/java/net/minecraft/client/network/NetHandlerPlayClient.java:215`）。每个 S 系封包的 `processPacket(INetHandlerPlayClient)` 在 Netty EventLoop 上调用对应 handle* 方法，方法内部第一行通常经 `PacketThreadUtil.checkThreadAndEnqueue` 转投主线程。

### INetHandlerPlayServer（INetHandlerPlayServer.java:28）

`public interface INetHandlerPlayServer extends INetHandler`，23 个方法与本包 23 个 C 系封包一一对应（INetHandlerPlayServer.java:30-143）。全部签名（逐字）：

```java
void handleAnimation(C0APacketAnimation packetIn);
void processChatMessage(C01PacketChatMessage packetIn);
void processTabComplete(C14PacketTabComplete packetIn);
void processClientStatus(C16PacketClientStatus packetIn);
void processClientSettings(C15PacketClientSettings packetIn);
void processConfirmTransaction(C0FPacketConfirmTransaction packetIn);
void processEnchantItem(C11PacketEnchantItem packetIn);
void processClickWindow(C0EPacketClickWindow packetIn);
void processCloseWindow(C0DPacketCloseWindow packetIn);
void processVanilla250Packet(C17PacketCustomPayload packetIn);
void processUseEntity(C02PacketUseEntity packetIn);
void processKeepAlive(C00PacketKeepAlive packetIn);
void processPlayer(C03PacketPlayer packetIn);
void processPlayerAbilities(C13PacketPlayerAbilities packetIn);
void processPlayerDigging(C07PacketPlayerDigging packetIn);
void processEntityAction(C0BPacketEntityAction packetIn);
void processInput(C0CPacketInput packetIn);
void processHeldItemChange(C09PacketHeldItemChange packetIn);
void processCreativeInventoryAction(C10PacketCreativeInventoryAction packetIn);
void processUpdateSign(C12PacketUpdateSign packetIn);
void processPlayerBlockPlacement(C08PacketPlayerBlockPlacement packetIn);
void handleSpectate(C18PacketSpectate packetIn);
void handleResourcePackStatus(C19PacketResourcePackStatus packetIn);
```

唯一实现是集成服务端的 `NetHandlerPlayServer`（`client/src/main/java/net/minecraft/network/NetHandlerPlayServer.java:98`）。

### C03PacketPlayer 及 C04/C05/C06 子类（C03PacketPlayer.java:8）

移动包族，客户端每 tick 恰好发一个。字段（C03PacketPlayer.java:10-17，全部 `protected` 供子类复用）：

```java
protected double x;
protected double y;
protected double z;
protected float yaw;
protected float pitch;
protected boolean onGround;
protected boolean moving;
protected boolean rotating;
```

`moving` / `rotating` **不上线**——它们由封包类型隐式表达（四个类注册为四个不同的 packet ID，EnumConnectionState.java:202-205），构造器里硬编码置位（如 C03PacketPlayer.java:101、134-135、164-165）。

- 基类 `C03PacketPlayer`：只序列化 `onGround`（`readPacketData` 读 `buf.readUnsignedByte() != 0`，C03PacketPlayer.java:41；写 `buf.writeByte(this.onGround ? 1 : 0)`，C03PacketPlayer.java:49）。
- `public static class C04PacketPlayerPosition extends C03PacketPlayer`（C03PacketPlayer.java:97）：追加 3 个 double（x/y/z）。
- `public static class C05PacketPlayerLook extends C03PacketPlayer`（C03PacketPlayer.java:130）：追加 2 个 float（yaw/pitch）。
- `public static class C06PacketPlayerPosLook extends C03PacketPlayer`（C03PacketPlayer.java:160）：追加 5 个（x/y/z/yaw/pitch）。构造器 `public C06PacketPlayerPosLook(double playerX, double playerY, double playerZ, float playerYaw, float playerPitch, boolean playerIsOnGround)`（C03PacketPlayer.java:168）。

发送方：`EntityPlayerSP.onUpdateWalkingPlayer()`（EntityPlayerSP.java:189）按位移>3.0E-2（平方 9.0E-4D）或 `positionUpdateTicks >= 20` 决定发 C04/C05/C06/基类哪一个（EntityPlayerSP.java:237-249）；骑乘时发 `new C03PacketPlayer.C06PacketPlayerPosLook(this.motionX, -999.0D, this.motionZ, ...)`，y 用 -999.0D 哨兵值（EntityPlayerSP.java:254）。服务端回拉后 `NetHandlerPlayClient.handlePlayerPosLook` 立即回发 C06 确认（NetHandlerPlayClient.java:717）。服务端处理入口 `public void processPlayer(C03PacketPlayer packetIn)`（NetHandlerPlayServer.java:219），第一行 `PacketThreadUtil.checkThreadAndEnqueue(packetIn, this, this.playerEntity.getServerForPlayer())`（NetHandlerPlayServer.java:221）。

### C02PacketUseEntity（C02PacketUseEntity.java:11）

字段：`private int entityId;`、`private C02PacketUseEntity.Action action;`、`private Vec3 hitVec;`（C02PacketUseEntity.java:13-15）。`public static enum Action { INTERACT, ATTACK, INTERACT_AT; }`（C02PacketUseEntity.java:86-91）。`hitVec` 仅当 `action == Action.INTERACT_AT` 时序列化为 3 个 float（C02PacketUseEntity.java:41-44、55-60）。辅助方法 `public Entity getEntityFromWorld(World worldIn)`（C02PacketUseEntity.java:71）在服务端按 entityId 解析实体。

发送方：`PlayerControllerMP` 的 `attackEntity`（ATTACK，PlayerControllerMP.java:498）、`interactWithEntitySendPacket`（INTERACT，PlayerControllerMP.java:512）、`isPlayerRightClickingOnEntity`（INTERACT_AT 带 Vec3，PlayerControllerMP.java:527）。

### C07PacketPlayerDigging（C07PacketPlayerDigging.java:10）

字段：`private BlockPos position;`、`private EnumFacing facing;`、`private C07PacketPlayerDigging.Action status;`（C07PacketPlayerDigging.java:12-16）。构造器 `public C07PacketPlayerDigging(C07PacketPlayerDigging.Action statusIn, BlockPos posIn, EnumFacing facingIn)`（C07PacketPlayerDigging.java:22）。

```java
public static enum Action
{
    START_DESTROY_BLOCK,
    ABORT_DESTROY_BLOCK,
    STOP_DESTROY_BLOCK,
    DROP_ALL_ITEMS,
    DROP_ITEM,
    RELEASE_USE_ITEM;
}
```
（C07PacketPlayerDigging.java:72-80）

facing 序列化为 `buf.writeByte(this.facing.getIndex())`，读侧 `EnumFacing.getFront(buf.readUnsignedByte())`（C07PacketPlayerDigging.java:36、46）。发送方：`PlayerControllerMP.clickBlock`（START，PlayerControllerMP.java:232/243）、`resetBlockRemoving`（ABORT，PlayerControllerMP.java:278）、`onPlayerDamageBlock`（STOP，PlayerControllerMP.java:324）、`onStoppedUsingItem`（RELEASE_USE_ITEM 配 `BlockPos.ORIGIN`+`EnumFacing.DOWN`，PlayerControllerMP.java:579）；丢物品走 `EntityPlayerSP` DROP 路径（EntityPlayerSP.java:282）。

### C08PacketPlayerBlockPlacement（C08PacketPlayerBlockPlacement.java:10）

字段：`private static final BlockPos field_179726_a = new BlockPos(-1, -1, -1);`（C08PacketPlayerBlockPlacement.java:12）、`private BlockPos position;`、`private int placedBlockDirection;`、`private ItemStack stack;`、`private float facingX; facingY; facingZ;`（C08PacketPlayerBlockPlacement.java:13-18）。

两个构造器（C08PacketPlayerBlockPlacement.java:24-37）：
- `public C08PacketPlayerBlockPlacement(ItemStack stackIn)` — 委托为 `this(field_179726_a, 255, stackIn, 0.0F, 0.0F, 0.0F)`，即"对空气右键使用物品"形态（pos=(-1,-1,-1)，direction=255）。
- `public C08PacketPlayerBlockPlacement(BlockPos positionIn, int placedBlockDirectionIn, ItemStack stackIn, float facingXIn, float facingYIn, float facingZIn)` — 真实放置；`this.stack = stackIn != null ? stackIn.copy() : null;`（防御性拷贝，C08PacketPlayerBlockPlacement.java:33）。

命中偏移量以 1/16 精度编码：写 `buf.writeByte((int)(this.facingX * 16.0F))`，读 `(float)buf.readUnsignedByte() / 16.0F`（C08PacketPlayerBlockPlacement.java:47-49、60-62）。发送方：`PlayerControllerMP.onPlayerRightClick`（PlayerControllerMP.java:424）、`sendUseItem`（空气形态，PlayerControllerMP.java:465）。

### C0EPacketClickWindow（C0EPacketClickWindow.java:9）

字段（C0EPacketClickWindow.java:11-27）：`private int windowId;`（0 = 玩家背包）、`private int slotId;`、`private int usedButton;`、`private short actionNumber;`（事务号）、`private ItemStack clickedItem;`（客户端预期该槽的物品，供服务端校验）、`private int mode;`（点击模式：普通/shift/数字键/丢弃/拖拽/双击）。构造器 `public C0EPacketClickWindow(int windowId, int slotId, int usedButton, int mode, ItemStack clickedItem, short actionNumber)`（C0EPacketClickWindow.java:33），同样对 `clickedItem` 做 `.copy()`。唯一发送方 `PlayerControllerMP.windowClick`（PlayerControllerMP.java:538），`actionNumber` 来自 `Container#getNextTransactionID`。校验失败时服务端经 S32 → 客户端 `NetHandlerPlayClient.handleConfirmTransaction` 回 `C0FPacketConfirmTransaction(windowId, actionNumber, true)`（NetHandlerPlayClient.java:1191）。

### C0BPacketEntityAction（C0BPacketEntityAction.java:9）

字段：`private int entityID;`、`private C0BPacketEntityAction.Action action;`、`private int auxData;`（C0BPacketEntityAction.java:11-13），三者均 VarInt/枚举序列化（C0BPacketEntityAction.java:36-38）。

```java
public static enum Action
{
    START_SNEAKING,
    STOP_SNEAKING,
    STOP_SLEEPING,
    START_SPRINTING,
    STOP_SPRINTING,
    RIDING_JUMP,
    OPEN_INVENTORY;
}
```
（C0BPacketEntityAction.java:69-78）

`auxData` 只在 RIDING_JUMP 时有意义：`new C0BPacketEntityAction(this, C0BPacketEntityAction.Action.RIDING_JUMP, (int)(this.getHorseJumpPower() * 100.0F))`（EntityPlayerSP.java:409）。潜行/疾跑状态变更在 `EntityPlayerSP.onUpdateWalkingPlayer` 里与 `serverSprintState`/`serverSneakState` 差分后发送（EntityPlayerSP.java:197-217）；起床由 `GuiSleepMP` 发 STOP_SLEEPING（GuiSleepMP.java:66）。

### C0CPacketInput（C0CPacketInput.java:8）

字段：`private float strafeSpeed;`（正=左）、`private float forwardSpeed;`（正=前）、`private boolean jumping;`、`private boolean sneaking;`（C0CPacketInput.java:11-16）。jump/sneak 打包进一个 byte 的 bit0/bit1（C0CPacketInput.java:37-39、49-61）。仅骑乘实体时每 tick 由 `EntityPlayerSP.onUpdate` 发送（EntityPlayerSP.java:177），与 C05PacketPlayerLook 成对。

### C13PacketPlayerAbilities（C13PacketPlayerAbilities.java:9）

字段：`invulnerable / flying / allowFlying / creativeMode`（boolean，打包进一个 byte 的 bit0-bit3，C13PacketPlayerAbilities.java:37-41、51-73）+ `flySpeed / walkSpeed`（float）。构造器 `public C13PacketPlayerAbilities(PlayerCapabilities capabilities)`（C13PacketPlayerAbilities.java:22）从 `PlayerCapabilities` 拍快照。发送方 `EntityPlayerSP.sendPlayerAbilities()`（EntityPlayerSP.java:394-396），在玩家切换飞行时调用；服务端实际只信任 `isFlying` 位。

### C17PacketCustomPayload（C17PacketCustomPayload.java:9）

字段：`private String channel;`、`private PacketBuffer data;`（C17PacketCustomPayload.java:11-12）。构造器强校验 `if (dataIn.writerIndex() > 32767) throw new IllegalArgumentException("Payload may not be larger than 32767 bytes");`（C17PacketCustomPayload.java:23-26）；读侧同样限制并且 channel 名最长 20 字符：`this.channel = buf.readStringFromBuffer(20);`（C17PacketCustomPayload.java:34-44）。payload 长度不显式编码，读侧用 `buf.readableBytes()` 取余量——依赖外层 frame 定界。发送方（原版通道）：`NetHandlerPlayClient` "MC|Brand"（NetHandlerPlayClient.java:291）、`GuiMerchant` "MC|TrSel"（GuiMerchant.java:131）、`GuiCommandBlock` "MC|AdvCdm"（GuiCommandBlock.java:95）、`GuiScreenBook` "MC|BEdit"/"MC|BSign"（GuiScreenBook.java:209）、`GuiRepair` "MC|ItemName"（GuiRepair.java:147）、`GuiBeacon` "MC|Beacon"（GuiBeacon.java:143）。

### 其余小型封包（结构一致，逐个已精读）

- `C00PacketKeepAlive`：单字段 `private int key;`（VarInt）。由 `NetHandlerPlayClient.handleKeepAlive` 回射（NetHandlerPlayClient.java:1665）；`GuiDownloadTerrain` 每若干 tick 发空 key 包保活（GuiDownloadTerrain.java:44）。
- `C01PacketChatMessage`：构造器截断 `if (messageIn.length() > 100) { messageIn = messageIn.substring(0, 100); }`（C01PacketChatMessage.java:18-21），读侧 `buf.readStringFromBuffer(100)`。发送方 `EntityPlayerSP.sendChatMessage(String message)`（EntityPlayerSP.java:296-298）。
- `C09PacketHeldItemChange`：单字段 `slotId`（short）。由 `PlayerControllerMP.syncCurrentPlayItem`（PlayerControllerMP.java:386）在 `currentPlayerItem` 与 `inventory.currentItem` 不一致时发送——任何用手逻辑前都会先 sync。
- `C0APacketAnimation`：零字段。`EntityPlayerSP.swingItem()`（EntityPlayerSP.java:304-307）。
- `C0DPacketCloseWindow`：单字段 `windowId`（byte）。`EntityPlayerSP.closeScreen()`（EntityPlayerSP.java:330-332）。
- `C0FPacketConfirmTransaction`：`windowId`（byte）+ `uid`（short）+ `accepted`（byte 布尔）。只有 getter `getWindowId()`/`getUid()`，无 `accepted` 的 getter（C0FPacketConfirmTransaction.java:53-61）。
- `C10PacketCreativeInventoryAction`：`slotId`（short，-1 = 丢到世界）+ `stack`（含 `.copy()` 防御拷贝，C10PacketCreativeInventoryAction.java:21）。`PlayerControllerMP.sendSlotPacket`（PlayerControllerMP.java:561）/`sendPacketDropItem`（slotId=-1，PlayerControllerMP.java:572）。
- `C11PacketEnchantItem`：`windowId` + `button`（均 byte）。`PlayerControllerMP.sendEnchantPacket`（PlayerControllerMP.java:551）。
- `C12PacketUpdateSign`：`pos` + `IChatComponent[] lines`（固定 4 行，每行 JSON 字符串上限 384，C12PacketUpdateSign.java:35）。`GuiEditSign`（GuiEditSign.java:59）。
- `C14PacketTabComplete`：`message`（≤32767，写侧 `StringUtils.substring(this.message, 0, 32767)`，C14PacketTabComplete.java:49）+ 可选 `targetBlock`（boolean 前缀）。`GuiChat`（GuiChat.java:263）。
- `C15PacketClientSettings`：`lang`（读上限 7 字符，C15PacketClientSettings.java:35）、`view`（byte 视距）、`chatVisibility`、`enableColors`、`modelPartFlags`（皮肤部件位集）。注意没有 `getView()` getter。`GameSettings.sendSettingsToServer`（GameSettings.java:1214）。
- `C16PacketClientStatus`：`EnumState { PERFORM_RESPAWN, REQUEST_STATS, OPEN_INVENTORY_ACHIEVEMENT }`（C16PacketClientStatus.java:50-55）。发送方：死亡界面重生 `EntityPlayerSP.respawnPlayer`（EntityPlayerSP.java:310-312）、`GuiWinGame`（GuiWinGame.java:72）、统计/成就界面（GuiAchievements.java:65、GuiStats.java:59）、打开背包成就（Minecraft.java:2095）。
- `C18PacketSpectate`：单字段 `UUID id`。`PlayerMenuObject`（旁观者快捷菜单，PlayerMenuObject.java:27）。服务端解析 `public Entity getEntity(WorldServer worldIn)`（C18PacketSpectate.java:48）。
- `C19PacketResourcePackStatus`：`hash`（构造器截断到 40 字符，C19PacketResourcePackStatus.java:19-22）+ `Action { SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, ACCEPTED }`（C19PacketResourcePackStatus.java:54-60）。由 `NetHandlerPlayClient.handleResourcePack` 的下载回调链发送（NetHandlerPlayClient.java:1714-1792）。

## 时序与生命周期

- **注册（一次性）**：`EnumConnectionState.PLAY` 的实例初始化块把本包 26 个封包类（含 C03 的三个子类）按顺序 `registerPacket(EnumPacketDirection.SERVERBOUND, ...)`（EnumConnectionState.java:199-224）。注册顺序 = packet ID（C00→0x00 … C19→0x19），**顺序即协议**。
- **实例化**：出站包由游戏代码 `new` + 参数构造器；入站包由解码器经 `EnumConnectionState.getPacket(direction, packetId)` 反射 `oclass.newInstance()`（EnumConnectionState.java:291）调无参构造器，再 `readPacketData`。因此每个封包类必须保留 public 无参构造器。
- **每 tick（客户端主线程）**：`EntityPlayerSP.onUpdate()`（EntityPlayerSP.java:168）—— 骑乘时发 C05+C0C 一对（EntityPlayerSP.java:176-177），否则进 `onUpdateWalkingPlayer()`（EntityPlayerSP.java:189）：先差分发送 C0B 疾跑/潜行，再按 `flag2`（位移平方 > 9.0E-4D 或 `positionUpdateTicks >= 20`）/ `flag3`（视角变化）四选一发 C03 族。**只要 `isCurrentViewEntity()` 成立，每 tick 恰好一个 C03 系包**（相机被 S43 切走时不发），服务端反作弊依赖此节律。
- **每帧**：无。本包不参与渲染。
- **线程归属**：
  - 封包构造与 `addToSendQueue` 调用发生在客户端主线程；`NetworkManager.sendPacket`（NetworkManager.java:175）内部 `dispatchPacket` 若不在 channel EventLoop 上则 `channel.eventLoop().execute(...)` 转投（NetworkManager.java:233-252）——`writePacketData` 因此在 **Netty EventLoop** 上执行。
  - 入站方向 `readPacketData` 与 `processPacket` 首调都在 Netty EventLoop 上；handler 实现第一行用 `PacketThreadUtil.checkThreadAndEnqueue`（签名：`public static <T extends INetHandler> void checkThreadAndEnqueue(final Packet<T> p_180031_0_, final T p_180031_1_, IThreadListener p_180031_2_) throws ThreadQuickExitException`，PacketThreadUtil.java:7）把处理重新排入目标逻辑线程（serverbound → 服务端线程，clientbound → 客户端主线程），并以 `ThreadQuickExitException` 快速退出当前 EventLoop 调用。
- **连接生命周期**：PLAY 状态从 `S01PacketJoinGame` 到 `S40PacketDisconnect`/断链；本包封包只在此窗口内合法。

## 挂钩点（Hook Points）

本包类本身是纯数据，功能层挂钩通常落在"构造点/发送点/分派点"三类位置。以下为最有价值的接管点：

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void addToSendQueue(Packet p_147297_1_)` | client/src/main/java/net/minecraft/client/network/NetHandlerPlayClient.java:814 | 客户端所有游戏逻辑出站封包的统一入口 | 全局出站封包拦截/取消/改写/延迟（blink、包记录器、发包器） | C19 资源包回执与部分包走 `netManager.sendPacket` 直发，绕过此入口 |
| `public void sendPacket(Packet packetIn)` | client/src/main/java/net/minecraft/network/NetworkManager.java:175 | 真正提交到 Netty channel 前 | 兜底拦截所有出站包（含绕过 sendQueue 的） | 可能在任意线程被调；channel 未开时入 `outboundPacketsQueue` |
| `void processPacket(T handler)`（`Packet` 接口） | client/src/main/java/net/minecraft/network/Packet.java:20 | 入站包解码完成后由 NetworkManager 分派 | 按包类型观察/吞掉入站事件（配合 ChannelHandler 注入） | 首调在 Netty EventLoop，禁止直接碰世界状态 |
| `public void onUpdateWalkingPlayer()` | client/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java:189 | 客户端每 tick（未骑乘） | 移动包改写核心：位置欺骗、onGround 伪造、静默转头（改 yaw/pitch 后还原） | 每 tick 必须恰好一个 C03 系包；乱序/超发触发服务端回拉 |
| `public void onUpdate()` | client/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java:168 | 客户端每 tick | 在移动包生成前后插入逻辑；骑乘分支（C05+C0C）也从这里走 | 区块未加载时整段跳过（`isBlockLoaded` 检查） |
| `public void sendChatMessage(String message)` | client/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java:296 | 玩家发送聊天/命令 | 客户端命令系统前置拦截（`.` 命令不上服） | C01 构造器会把消息截到 100 字符 |
| `public void swingItem()` | client/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java:304 | 挥手（攻击/交互/放置） | 抑制或补发 C0A（无摆动/杀戮光环补动画） | 无 |
| `public void closeScreen()` | client/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java:330 | 关闭容器 GUI | 抑制 C0D 实现"关屏但服务端仍认为容器打开"（inv-walk 类功能） | 与服务端窗口状态失同步会导致后续 C0E 被拒 |
| `public void sendPlayerAbilities()` | client/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java:394 | 切换飞行时 | 观察/伪造 C13 能力位 | 服务端会校验 allowFlying |
| `public void respawnPlayer()` | client/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java:310 | 死亡界面点重生 | 自动重生等 | 无 |
| `public boolean clickBlock(BlockPos loc, EnumFacing face)`（发 C07 START） | client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:198（发送在 232/243） | 开始挖方块 | 速挖/多方块挖掘的封包侧入口 | C07 状态机必须 START→(ABORT\|STOP) 配对 |
| `public boolean onPlayerDamageBlock(BlockPos posBlock, EnumFacing directionFacing)`（发 C07 STOP） | client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:285（发送在 324） | 挖掘进度完成 | 提前发 STOP 实现即时破坏 | 服务端校验挖掘时长 |
| `public boolean onPlayerRightClick(EntityPlayerSP player, WorldClient worldIn, ItemStack heldStack, BlockPos hitPos, EnumFacing side, Vec3 hitVec)`（发 C08） | client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:390（发送在 424） | 对方块右键 | scaffold/放置辅助的封包出口；改 facing 偏移 | 偏移量按 1/16 量化 |
| `public boolean sendUseItem(EntityPlayer playerIn, World worldIn, ItemStack itemStackIn)`（发 C08 空气形态） | client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:456（发送在 465） | 对空气右键使用物品 | 假吃/快弓类功能出口 | pos 固定 (-1,-1,-1)、dir=255，服务端据此识别 |
| `public void attackEntity(EntityPlayer playerIn, Entity targetEntity)`（发 C02 ATTACK） | client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:495（发送在 498） | 攻击实体 | killaura 的最终封包出口；攻击前自动 `syncCurrentPlayItem` | 服务端校验攻击距离与视线 |
| `public ItemStack windowClick(int windowId, int slotId, int mouseButtonClicked, int mode, EntityPlayer playerIn)`（发 C0E） | client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:534（发送在 538） | 容器槽位点击 | 背包整理/自动装备等库存自动化出口 | `actionNumber` 必须取自 `Container#getNextTransactionID`，否则事务失同步 |
| `private void syncCurrentPlayItem()`（发 C09） | client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:379（发送在 386） | 任何用手动作前 | 静默切换手持槽位（发 C09 而不动本地 UI） | 服务端以最后收到的 C09 为准；private，需在类内或反射挂钩 |
| `void readPacketData(PacketBuffer buf) throws IOException`（各封包） | 本包各文件 | Netty 解码线程 | 线格式级观察（配合注入的 ChannelHandler 做协议分析） | Netty EventLoop 线程；勿阻塞 |
| `void writePacketData(PacketBuffer buf) throws IOException`（各封包） | 本包各文件 | Netty 编码线程 | 同上，出站方向 | 同上 |
| `public void handlePlayerPosLook(S08PacketPlayerPosLook packetIn)`（接口声明） | client/src/main/java/net/minecraft/network/play/INetHandlerPlayClient.java:273 | 服务端回拉位置 | 检测/处理 lagback；实现处会回发 C06 确认（NetHandlerPlayClient.java:717） | 吞掉确认包会被服务端反复回拉 |
| `public void handleConfirmTransaction`（实现处回发 C0F） | client/src/main/java/net/minecraft/client/network/NetHandlerPlayClient.java:1191 | 容器事务被服务端质疑 | 观察事务失同步 | 不回 C0F 会锁死容器操作 |

## 数据与协议

所有封包经由 `PacketBuffer`（Netty `ByteBuf` 包装）序列化。字段级线格式（按写入顺序）：

| 封包 | 字段 | 线类型 | 读方法 → 写方法 | 取值含义 |
|---|---|---|---|---|
| C00PacketKeepAlive | `key` | VarInt | `readVarIntFromBuffer` → `writeVarIntToBuffer` | 回射服务端 S00 携带的 key |
| C01PacketChatMessage | `message` | String(≤100) | `readStringFromBuffer(100)` → `writeString` | 聊天文本或 `/` 命令 |
| C02PacketUseEntity | `entityId` | VarInt | `readVarIntFromBuffer` → `writeVarIntToBuffer` | 目标实体 ID |
| | `action` | VarInt 枚举 | `readEnumValue(Action.class)` → `writeEnumValue` | 0=INTERACT 1=ATTACK 2=INTERACT_AT（ordinal） |
| | `hitVec` | 3×float，条件 | `readFloat`×3 → `writeFloat`×3 | 仅 INTERACT_AT；实体局部命中点 |
| C03PacketPlayer | `onGround` | ubyte | `readUnsignedByte() != 0` → `writeByte(onGround ? 1 : 0)` | 非零=着地 |
| C04PacketPlayerPosition | `x,y,z` + `onGround` | 3×double + ubyte | `readDouble`×3 → `writeDouble`×3 | y 为包围盒 minY（脚部） |
| C05PacketPlayerLook | `yaw,pitch` + `onGround` | 2×float + ubyte | `readFloat`×2 → `writeFloat`×2 | 角度制，不归一化 |
| C06PacketPlayerPosLook | `x,y,z,yaw,pitch` + `onGround` | 3×double+2×float+ubyte | 同上组合 | 骑乘时 y=-999.0D 哨兵 |
| C07PacketPlayerDigging | `status` | VarInt 枚举 | `readEnumValue(Action.class)` → `writeEnumValue` | 0=START_DESTROY_BLOCK … 5=RELEASE_USE_ITEM |
| | `position` | long | `readBlockPos` → `writeBlockPos` | 压缩 BlockPos |
| | `facing` | ubyte | `EnumFacing.getFront(readUnsignedByte())` → `writeByte(facing.getIndex())` | 面索引 0-5 |
| C08PacketPlayerBlockPlacement | `position` | long | `readBlockPos` → `writeBlockPos` | (-1,-1,-1) = 空气使用 |
| | `placedBlockDirection` | ubyte | `readUnsignedByte` → `writeByte` | 0-5 面；255 = 空气使用 |
| | `stack` | ItemStack | `readItemStackFromBuffer` → `writeItemStackToBuffer` | 手持物品快照（含 NBT） |
| | `facingX/Y/Z` | 3×ubyte | `readUnsignedByte()/16.0F` → `writeByte((int)(f*16.0F))` | 命中点在方块内偏移，1/16 精度 |
| C09PacketHeldItemChange | `slotId` | short | `readShort` → `writeShort` | 快捷栏 0-8 |
| C0APacketAnimation | — | 空载荷 | — | 挥臂 |
| C0BPacketEntityAction | `entityID` | VarInt | `readVarIntFromBuffer` → `writeVarIntToBuffer` | 自身实体 ID |
| | `action` | VarInt 枚举 | `readEnumValue(Action.class)` → `writeEnumValue` | 0=START_SNEAKING … 6=OPEN_INVENTORY |
| | `auxData` | VarInt | `readVarIntFromBuffer` → `writeVarIntToBuffer` | RIDING_JUMP 时 = 跳跃力×100，其余 0 |
| C0CPacketInput | `strafeSpeed` | float | `readFloat` → `writeFloat` | 正=左 |
| | `forwardSpeed` | float | `readFloat` → `writeFloat` | 正=前 |
| | `jumping`/`sneaking` | byte 位集 | `(b0 & 1) > 0` / `(b0 & 2) > 0` → 或运算组装 | bit0=跳 bit1=潜行 |
| C0DPacketCloseWindow | `windowId` | byte | `readByte` → `writeByte` | 0 = 玩家背包 |
| C0EPacketClickWindow | `windowId` | byte | `readByte` → `writeByte` | 目标窗口 |
| | `slotId` | short | `readShort` → `writeShort` | -999 = 窗口外 |
| | `usedButton` | byte | `readByte` → `writeByte` | 鼠标键/数字键 |
| | `actionNumber` | short | `readShort` → `writeShort` | 事务号，递增 |
| | `mode` | byte | `readByte` → `writeByte` | 点击模式 0-6 |
| | `clickedItem` | ItemStack | `readItemStackFromBuffer` → `writeItemStackToBuffer` | 客户端预期槽内物品（注意：读顺序中 clickedItem 在最后，mode 在 actionNumber 之后） |
| C0FPacketConfirmTransaction | `windowId` | byte | `readByte` → `writeByte` | |
| | `uid` | short | `readShort` → `writeShort` | 对应 S32 的 actionNumber |
| | `accepted` | byte | `readByte() != 0` → `writeByte(accepted ? 1 : 0)` | 客户端总是回 true |
| C10PacketCreativeInventoryAction | `slotId` | short | `readShort` → `writeShort` | -1 = 丢到世界 |
| | `stack` | ItemStack | `readItemStackFromBuffer` → `writeItemStackToBuffer` | 任意物品（服务端仅创造模式接受） |
| C11PacketEnchantItem | `windowId` | byte | `readByte` → `writeByte` | 附魔台窗口 |
| | `button` | byte | `readByte` → `writeByte` | 附魔选项 0-2 |
| C12PacketUpdateSign | `pos` | long | `readBlockPos` → `writeBlockPos` | 告示牌位置 |
| | `lines[4]` | 4×String(≤384) | `readStringFromBuffer(384)` + `IChatComponent.Serializer.jsonToComponent` → `componentToJson` + `writeString` | 每行一个 JSON 文本组件 |
| C13PacketPlayerAbilities | 4 个布尔 | byte 位集 | 位测试 → 位组装 | bit0=invulnerable bit1=flying bit2=allowFlying bit3=creativeMode |
| | `flySpeed`/`walkSpeed` | 2×float | `readFloat` → `writeFloat` | 能力速度快照 |
| C14PacketTabComplete | `message` | String(≤32767) | `readStringFromBuffer(32767)` → `writeString(StringUtils.substring(message,0,32767))` | 待补全文本 |
| | `targetBlock` | bool + 可选 long | `readBoolean` 守卫 `readBlockPos` → 对称 | 注视的方块（命令方块补全用） |
| C15PacketClientSettings | `lang` | String(≤7) | `readStringFromBuffer(7)` → `writeString` | 如 "en_US" |
| | `view` | byte | `readByte` → `writeByte` | 视距（区块） |
| | `chatVisibility` | byte | `EnumChatVisibility.getEnumChatVisibility(readByte())` → `writeByte(chatVisibility.getChatVisibility())` | 0=全部 1=仅命令 2=隐藏 |
| | `enableColors` | bool | `readBoolean` → `writeBoolean` | 聊天颜色 |
| | `modelPartFlags` | ubyte | `readUnsignedByte` → `writeByte` | 皮肤外层部件位集 |
| C16PacketClientStatus | `status` | VarInt 枚举 | `readEnumValue(EnumState.class)` → `writeEnumValue` | 0=PERFORM_RESPAWN 1=REQUEST_STATS 2=OPEN_INVENTORY_ACHIEVEMENT |
| C17PacketCustomPayload | `channel` | String(≤20) | `readStringFromBuffer(20)` → `writeString` | 如 "MC|Brand" |
| | `data` | 原始字节 | `new PacketBuffer(buf.readBytes(readableBytes()))` → `writeBytes((ByteBuf)data)` | 无长度前缀，靠帧定界；≤32767 字节 |
| C18PacketSpectate | `id` | UUID(2×long) | `readUuid` → `writeUuid` | 传送目标实体 UUID |
| C19PacketResourcePackStatus | `hash` | String(≤40) | `readStringFromBuffer(40)` → `writeString` | 资源包 SHA-1（截断到 40） |
| | `status` | VarInt 枚举 | `readEnumValue(Action.class)` → `writeEnumValue` | 0=SUCCESSFULLY_LOADED 1=DECLINED 2=FAILED_DOWNLOAD 3=ACCEPTED |

packet ID 由 `EnumConnectionState` 注册顺序决定（EnumConnectionState.java:199-224）：C00→0x00、C01→0x01、C02→0x02、C03→0x03、C04→0x04、C05→0x05、C06→0x06、C07→0x07、C08→0x08、C09→0x09、C0A→0x0A、C0B→0x0B、C0C→0x0C、C0D→0x0D、C0E→0x0E、C0F→0x0F、C10→0x10、C11→0x11、C12→0x12、C13→0x13、C14→0x14、C15→0x15、C16→0x16、C17→0x17、C18→0x18、C19→0x19（与类名十六进制前缀一致，1.8 协议版本 47）。

## 不变量与陷阱

- **枚举按 ordinal 上线**：`writeEnumValue`/`readEnumValue` 序列化的是枚举序号。给 `Action`/`EnumState` 增删或重排常量 = 破坏协议。同理 `EnumConnectionState` 里的注册顺序不可动。
- **无参构造器不可删**：入站实例化走 `oclass.newInstance()`（EnumConnectionState.java:291）。JDK 25 下 `Class#newInstance` 已弃用但仍可用；封包类若加 final 字段或删掉默认构造器会在运行时炸。
- **`moving`/`rotating` 是本地标志**：C03 族的这两个字段不序列化，接收侧靠封包子类型还原（构造器置位）。手工构造 C03 基类但期待服务端读坐标是无效的。
- **每 tick 恰一个 C03（`isCurrentViewEntity()` 时）**：`onUpdateWalkingPlayer` 保证的节律是许多服务端反作弊的基线；功能层多发/漏发移动包都会被检测或导致回拉。静止时 `positionUpdateTicks >= 20` 也会强制发位置包（`boolean flag2 = d0 * d0 + d1 * d1 + d2 * d2 > 9.0E-4D || this.positionUpdateTicks >= 20;`，EntityPlayerSP.java:230）。
- **ItemStack 防御拷贝**：C08/C0E/C10 构造器均 `stackIn.copy()`——封包持有的是快照，不要试图通过封包对象改活体背包；反之，自造封包时传入活体 stack 也不会被后续修改污染。
- **C17 payload 无长度前缀**：读侧用 `readableBytes()` 吃掉帧内全部余量。若在同一 buffer 里追加数据会被当作 payload 一部分；payload 超 32767 直接 `IOException`/`IllegalArgumentException`。
- **字符串上限各不相同**：C01=100、C12 每行=384、C14=32767、C15 lang=7、C17 channel=20、C19 hash=40。超限在读侧抛异常断连（服务端视角），写侧 C01/C19 构造器主动截断、C14 写时截断，其余不截断——自造封包要自己守边界。
- **线程约束**：`readPacketData`/`writePacketData`/`processPacket` 首调都在 Netty EventLoop；只有经 `PacketThreadUtil.checkThreadAndEnqueue` 转投后才能安全触碰世界/GUI。`checkThreadAndEnqueue` 用 `ThreadQuickExitException` 中断当前调用（PacketThreadUtil.java:7）——在 handler 里 catch 全部异常会破坏该机制。
- **出站线程**：`NetworkManager.sendPacket` 可在任意线程调用；channel 未打开时包进 `outboundPacketsQueue` 由 `readWriteLock` 保护（NetworkManager.java:184-193）。发送顺序在跨线程场景下由 EventLoop 串行化保证。
- **LWJGL3/JDK25 移植**：本包零 LWJGL 依赖，纯 Netty + 反射；仓库已把 Netty 升到 4.2.16（见 git log "Upgrade Netty 4.1.124 -> 4.2.16 (protocol verified, zero source changes)"），`PacketBuffer` 继承的 ByteBuf 语义未变。风险点集中在反射实例化（`newInstance` 弃用警告）与 `io.netty.buffer.ByteBuf` 强转（C17PacketCustomPayload.java:53）。
- **C0F 只有半套 getter**：`accepted` 字段没有 getter（C0FPacketConfirmTransaction.java:53-61 只有 `getWindowId`/`getUid`）；C15 没有 `getView()`。读这些字段要靠反射或改源码。
- **C08 空气形态识别**：服务端靠 `position == (-1,-1,-1) && direction == 255` 区分"使用物品"与"放方块"。自造放置包时 direction 必须是 0-5 面索引，写入是 ubyte。

## 交叉引用

- net.minecraft.network → `NetworkManager#sendPacket` / `NetworkManager#dispatchPacket`（出站提交，NetworkManager.java:175/222）
- net.minecraft.network → `EnumConnectionState.PLAY`（packet ID 注册，EnumConnectionState.java:199-224）
- net.minecraft.network → `Packet#readPacketData/writePacketData/processPacket`（Packet.java:10-20，本包全部封包实现该接口）
- net.minecraft.network → `PacketBuffer`（全部序列化原语：readVarIntFromBuffer、readBlockPos、readItemStackFromBuffer、readEnumValue、readUuid 等）
- net.minecraft.network → `PacketThreadUtil#checkThreadAndEnqueue`（线程转投，PacketThreadUtil.java:7）
- net.minecraft.network → `NetHandlerPlayServer`（INetHandlerPlayServer 的唯一实现，NetHandlerPlayServer.java:98；详见 mc-network-play-server.md）
- net.minecraft.client.network → `NetHandlerPlayClient`（INetHandlerPlayClient 的唯一实现 + `addToSendQueue`，NetHandlerPlayClient.java:215/814）
- net.minecraft.client.entity → `EntityPlayerSP#onUpdate/onUpdateWalkingPlayer/sendChatMessage/swingItem/closeScreen/sendPlayerAbilities/respawnPlayer`（C01/C03族/C07/C0A/C0B/C0C/C0D/C13/C16 的主要发送方）
- net.minecraft.client.multiplayer → `PlayerControllerMP#clickBlock/onPlayerDamageBlock/onPlayerRightClick/sendUseItem/attackEntity/windowClick/sendEnchantPacket/sendSlotPacket/syncCurrentPlayItem`（C02/C07/C08/C09/C0E/C10/C11 的发送方）
- net.minecraft.client.settings → `GameSettings#sendSettingsToServer`（C15，GameSettings.java:1214）
- net.minecraft.client.gui → `GuiChat#sendAutocompleteRequest`（C14）、`GuiEditSign`（C12）、`GuiSleepMP`（C0B STOP_SLEEPING）、`GuiWinGame`/`GuiAchievements`/`GuiStats`（C16）、`GuiMerchant`/`GuiCommandBlock`/`GuiScreenBook`/`GuiRepair`/`GuiBeacon`（C17 各通道）、`GuiDownloadTerrain`（C00 保活）、spectator `PlayerMenuObject`（C18）
- net.minecraft.entity.player → `PlayerCapabilities`（C13 构造器入参）、`EntityPlayer.EnumChatVisibility`（C15 字段类型）
- net.minecraft.item → `ItemStack#copy`（C08/C0E/C10 防御拷贝）
- net.minecraft.util → `BlockPos`/`EnumFacing`/`Vec3`/`IChatComponent.Serializer`（坐标、朝向、命中点、告示牌 JSON）
- net.minecraft.world → `World#getEntityByID`（C02PacketUseEntity#getEntityFromWorld）、`WorldServer#getEntityFromUuid`（C18PacketSpectate#getEntity）

## 覆盖声明

完整读取了 25/25 个文件（每个文件从第 1 行读到末尾）。

逐行精读的类：全部 25 个——`INetHandlerPlayClient`、`INetHandlerPlayServer` 以及 C00–C19 全部 23 个封包类（含 C03 内部的 C04/C05/C06）。本包文件普遍短小（31–385 行），无一采用抽样或结构性浏览。

包外佐证材料（部分读取，用于确认调用方与行号）：`EnumConnectionState.java`（注册块 190-230 与实例化 284-291）、`NetworkManager.java`（175-260）、`NetHandlerPlayClient.java`（grep 定位的发送/回射行）、`NetHandlerPlayServer.java`（98、207、219-221）、`EntityPlayerSP.java`（160-260 及方法签名 grep）、`PlayerControllerMP.java` / 各 `Gui*` / `GameSettings.java`（仅 grep 命中的发送行）、`Packet.java`（全文）、`PacketThreadUtil.java`（签名行）。这些包外文件未全文精读，相关结论仅限所引行号。
