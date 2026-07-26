---
area: net/minecraft/realms
slug: mc-realms
files: 23
lines: 2120
tier: C
---

# net/minecraft/realms — Realms API 桥接层

## 定位

这个包是 Mojang 为外部 Realms 客户端 jar（`com.mojang.realmsclient.*`）预留的**稳定 API 门面**：Realms jar 只依赖本包的类，本包再把调用转发到客户端内部实现（`GuiScreen`/`GuiButton`/`WorldRenderer`/`NetworkManager` 等）。几乎每个类都是纯委托 wrapper，自身不含业务逻辑。

调用关系：

- **谁调用它**：`GuiMainMenu`（主菜单 "Minecraft Realms" 按钮，`GuiMainMenu.java:245/338` 经 `RealmsBridge` 进入）、`GuiIngameMenu`（`GuiIngameMenu.java:65`）、`NetHandlerPlayClient`（Realms 会话断线时构造 `DisconnectedRealmsScreen`，`NetHandlerPlayClient.java:801`），以及（若存在于 classpath 的）外部 Realms jar。
- **它调用谁**：`net.minecraft.client.gui` 下的五个 Proxy 类（`GuiScreenRealmsProxy`、`GuiButtonRealmsProxy`、`GuiSlotRealmsProxy`、`GuiSimpleScrolledSelectionListProxy`、`GuiClickableScrolledSelectionListProxy`）、`Minecraft` 单例、`NetworkManager`、`Tessellator`/`WorldRenderer`、`ISaveFormat`、`MathHelper`。
- **消失会坏什么**：主菜单/ESC 菜单的 Realms 按钮路径（`RealmsBridge`）编译失败；`NetHandlerPlayClient` 的 Realms 断线分支编译失败。本移植版**未捆绑** Realms jar（`RealmsBridge.java:29` 注释明说），所以运行时功能本就残缺——反射 `Class.forName("com.mojang.realmsclient.RealmsMainScreen")` 失败后静默返回，点 Realms 按钮无效果、通知屏为 null。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| DisconnectedRealmsScreen | 63 | extends RealmsScreen | Realms 断线提示屏，显示原因文本并提供返回按钮 |
| Realms | 117 | — | 静态工具门面：session/UUID、setScreen、资源包下载、gamemode id 等 |
| RealmsAnvilLevelStorageSource | 75 | — | 包装 `ISaveFormat`，暴露存档列举/转换/删除/重命名 |
| RealmsBridge | 55 | extends RealmsScreen | 反射加载外部 Realms jar 的入口屏；失败即静默回退 |
| RealmsBufferBuilder | 151 | — | 包装 `WorldRenderer`，顶点缓冲构建 API |
| RealmsButton | 89 | — | 包装 `GuiButtonRealmsProxy` 的按钮，子类可覆写 clicked/released/renderBg |
| RealmsClickableScrolledSelectionList | 106 | — | 可点击滚动列表基类，委托 `GuiClickableScrolledSelectionListProxy` |
| RealmsConnect | 124 | — | 后台线程建立到 Realms 服务器的登录连接（handshake+login），tick 泵包 |
| RealmsDefaultVertexFormat | 71 | — | 静态构建 12 种标准顶点格式与 6 种格式元素常量 |
| RealmsEditBox | 64 | — | 包装 `GuiTextField` 的文本输入框 |
| RealmsLevelSummary | 63 | implements Comparable&lt;RealmsLevelSummary&gt; | 包装 `SaveFormatComparator` 的存档摘要，按 lastPlayed 降序比较 |
| RealmsMth | 173 | — | `MathHelper` 静态转发（sin/cos/clamp/floor/parse 等） |
| RealmsScreen | 255 | — | 所有 Realms 屏幕基类；持有 `GuiScreenRealmsProxy`，提供绘制/按钮/字体/输入回调 |
| RealmsScrolledSelectionList | 89 | — | 滚动选择列表，委托 `GuiSlotRealmsProxy` |
| RealmsServerAddress | 31 | — | host+port 值对象，`parseString` 委托 `ServerAddress.fromString` |
| RealmsServerPing | 8 | — | ping 结果容器：三个 volatile 字段（人数/时间戳/玩家名单） |
| RealmsServerStatusPinger | 149 | — | 用 STATUS 协议异步 ping Realms 服务器，tick 泵包，removeAll 清理 |
| RealmsSharedConstants | 11 | — | 协议版本 47、TPS 20、版本串 "1.8.9"、非法文件名字符 |
| RealmsSimpleScrolledSelectionList | 89 | — | 简化版滚动列表，委托 `GuiSimpleScrolledSelectionListProxy` |
| RealmsSliderButton | 104 | extends RealmsButton | 滑块按钮：pct/value 换算、拖动更新、绘制滑块贴图 |
| RealmsVertexFormat | 119 | — | 包装 `VertexFormat` |
| RealmsVertexFormatElement | 53 | — | 包装 `VertexFormatElement` |
| Tezzelator | 61 | — | 包装 `Tessellator` 单例的立即模式绘制入口 |

## 核心类详解

### RealmsScreen（RealmsScreen.java）

Realms UI 的根基类。关键字段：`protected Minecraft minecraft`、`public int width`、`public int height`、`private GuiScreenRealmsProxy proxy = new GuiScreenRealmsProxy(this)`（RealmsScreen.java:26-29）。注意 proxy 在字段初始化时立即创建，即双向引用在构造期就建立。

关键方法（均逐字）：

- `public GuiScreenRealmsProxy getProxy()` — RealmsScreen.java:31。`Realms.setScreen` 与 `RealmsBridge` 拿它交给 `Minecraft.displayGuiScreen`。
- `public void init()` / `public void init(Minecraft p_init_1_, int p_init_2_, int p_init_3_)` — RealmsScreen.java:36/40，由 `GuiScreenRealmsProxy.initGui` 调用（屏幕打开或窗口 resize）。
- `public void render(int p_render_1_, int p_render_2_, float p_render_3_)` — RealmsScreen.java:94，默认只遍历 proxy 的按钮列表逐个 `render`；每帧由 proxy 的 `drawScreen` 调。
- `public void tick()` — RealmsScreen.java:136，每客户端 tick 经 proxy 的 `updateScreen` 转发。
- 输入回调：`public void mouseClicked(int, int, int)`（:209）、`mouseReleased`（:221）、`mouseDragged`（:225）、`keyPressed(char, int)`（:229）、`mouseEvent()`（:213）、`keyboardEvent()`（:217）、`confirmResult(boolean, int)`（:233）、`removed()`（:252，对应 `onGuiClosed`）。
- 按钮管理：`buttonsClear()`（:184）、`buttonsAdd(RealmsButton)`（:189）、`buttons()`（:194）、`buttonsRemove(RealmsButton)`（:199）、`buttonClicked(RealmsButton p_buttonClicked_1_)`（:170）。
- 静态工具：`public static String getLocalizedString(String p_getLocalizedString_0_)`（:237，转发 `I18n.format`）、`public static void bindFace(String p_bindFace_0_, String p_bindFace_1_)`（:117，绑定玩家头像皮肤纹理）、两个静态 `blit` 重载（:64/:69，转发 `Gui.drawScaledCustomSizeModalRect` / `drawModalRectWithCustomSizedTexture`）。

### RealmsConnect（RealmsConnect.java）

Realms 世界的连接器，功能上等价于 `GuiConnecting` 的连接逻辑。字段：`private final RealmsScreen onlineScreen`、`private volatile boolean aborted = false`、`private NetworkManager connection`（RealmsConnect.java:18-20）。

- `public void connect(final String p_connect_1_, final int p_connect_2_)` — RealmsConnect.java:27。先 `Realms.setConnectedToRealms(true)`，然后起名为 `"Realms-connect-task"` 的线程：DNS 解析 → `NetworkManager.createNetworkManagerAndConnect(inetaddress, p_connect_2_, Minecraft.getMinecraft().gameSettings.isUsingNativeTransport())`（:45）→ `setNetHandler(new NetHandlerLoginClient(...))`（:52）→ 发 `C00Handshake(47, host, port, EnumConnectionState.LOGIN)`（:59）→ 发 `C00PacketLoginStart(...getSession().getProfile())`（:66）。每步之间检查 `aborted`。失败路径清资源包并 `Realms.setScreen(new DisconnectedRealmsScreen(...))`（:79/:99）。
- `public void abort()` — :105，只置 volatile 标志，不主动断连。
- `public void tick()` — :110。通道开则 `connection.processReceivedPackets()`（把 Netty 收到的包在主线程回放），否则 `connection.checkDisconnected()`。调用方须每 tick 调它，否则登录流程停摆。

### RealmsServerStatusPinger（RealmsServerStatusPinger.java）

Realms 服务器在线人数 ping，结构与 `ServerPinger`（vanilla 的 `net.minecraft.client.network.OldServerPinger` 同类物）一致。字段：`private final List<NetworkManager> connections = Collections.<NetworkManager>synchronizedList(Lists.<NetworkManager>newArrayList())`（:28）。

- `public void pingServer(final String p_pingServer_1_, final RealmsServerPing p_pingServer_2_) throws UnknownHostException` — :30。跳过 null/空/`"0.0.0.0"` 前缀地址；建立 STATUS 连接，匿名 `INetHandlerStatusClient`：`handleServerInfo`（:40）把在线人数与玩家名单写入 `RealmsServerPing` 的 volatile 字段后发 `C01PacketPing(Realms.currentTimeMillis())`；`handlePong`（:83）直接 `closeChannel`。handshake 用 `RealmsSharedConstants.NETWORK_PROTOCOL_VERSION`（:98）。
- `public void tick()` — :108，同步遍历 connections，开着的泵包，关了的移除并 `checkDisconnected()`。
- `public void removeAll()` — :131，以 `new ChatComponentText("Cancelled")` 关闭所有活动连接（屏幕关闭时调）。

注意 `handleServerInfo` 等回调在 **Netty EventLoop 线程**上执行（status 包是否被 `processReceivedPackets` 排队回主线程取决于 NetworkManager 的线程检查；`RealmsServerPing` 字段全 volatile 正是为跨线程读取准备的）。

### RealmsBridge（RealmsBridge.java）

vanilla 侧进入 Realms 的唯一入口。`public void switchToRealms(GuiScreen p_switchToRealms_1_)`（:15）反射查找 `com.mojang.realmsclient.RealmsMainScreen`，用 `Constructor<?>(RealmsScreen.class)` 实例化并 `displayGuiScreen(((RealmsScreen)object).getProxy())`；`public GuiScreenRealmsProxy getNotificationScreen(GuiScreen p_getNotificationScreen_1_)`（:33）同理反射 `com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen`，失败返回 null。本移植版两处 catch 均为空实现并带注释 "Realms client is intentionally not bundled in this port — expected, not an error."（:29/:46）。`public void init()`（:51）在自身被显示时立刻切回 `previousScreen`——这是反射失败后的兜底回退机制。

### Tezzelator / RealmsBufferBuilder / RealmsDefaultVertexFormat（绘制通道）

`Tezzelator` 持有 `public static Tessellator t = Tessellator.getInstance()` 与单例 `public static final Tezzelator instance = new Tezzelator()`（Tezzelator.java:7-8），`begin(int p_begin_1_, RealmsVertexFormat p_begin_2_)`（:36）/ `vertex` / `tex` / `color` / `endVertex` / `end()`（`t.draw()`，:10）。列表类的 `renderItem` 把 `Tezzelator.instance` 传给子类（RealmsScrolledSelectionList.java:40）。`RealmsBufferBuilder` 是对 `WorldRenderer` 的全量方法映射（改名版：`pos`→`vertex`、`finishDrawing`→`end`、`lightmap`→`tex2` 等）。`RealmsDefaultVertexFormat` 的 static 块（RealmsDefaultVertexFormat.java:27-70）按 vanilla `DefaultVertexFormats` 相同的元素顺序拼装 BLOCK/ENTITY/PARTICLE/POSITION_* 共 12 种格式。注意这些是**独立实例**，与 `DefaultVertexFormats` 中的常量不是同一对象（只是元素布局相同）。

## 时序与生命周期

- **类加载**：`RealmsDefaultVertexFormat` 的 static 块在首次引用时构建全部格式；`Tezzelator.t` 在类加载时抓取 `Tessellator.getInstance()`，因此必须在 `Minecraft` 渲染系统初始化之后才能触碰该类。
- **屏幕生命周期**（全部主线程，由 `GuiScreenRealmsProxy` 驱动）：构造 `RealmsScreen` 时同时构造 proxy → `Minecraft.displayGuiScreen(proxy)` → proxy 的 `initGui` 调 `RealmsScreen.init()` → 每 tick `updateScreen` 调 `tick()` → 每帧 `drawScreen` 调 `render(mouseX, mouseY, partialTicks)` → 关闭时 `onGuiClosed` 调 `removed()`。
- **RealmsConnect**：`connect()` 在主线程调用，实际连接在专用线程 `"Realms-connect-task"` 完成；此后包的收发由 Netty EventLoop 承担，`tick()` 必须在主线程每 tick 调用以回放包（登录完成后 `NetHandlerLoginClient` 移交 `NetHandlerPlayClient`）。
- **RealmsServerStatusPinger**：`pingServer` 建连接（Netty EventLoop 处理 IO），持有它的屏幕每 tick 调 `tick()`，屏幕关闭调 `removeAll()`。
- 本移植版因缺 Realms jar，上述生命周期实际只有 `DisconnectedRealmsScreen`（经 `NetHandlerPlayClient.java:801`）与 `RealmsBridge` 兜底回退会真正走到。

## 挂钩点（Hook Points）

本包在缺失 Realms jar 的移植版中大部分代码是死路径，真正有价值的挂钩点有限；列出仍会被 vanilla 侧触达或对功能层有杠杆价值的点：

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void switchToRealms(GuiScreen p_switchToRealms_1_)` | RealmsBridge.java:15 | 主菜单/ESC 菜单点 "Minecraft Realms"（GuiMainMenu.java:338、GuiIngameMenu.java:65） | 接管 Realms 按钮：弹自定义屏幕、复用该入口做任意功能菜单 | 反射失败时静默 no-op；若替换实现记得处理 `previousScreen` 回退 |
| `public GuiScreenRealmsProxy getNotificationScreen(GuiScreen p_getNotificationScreen_1_)` | RealmsBridge.java:33 | `GuiMainMenu` 初始化通知覆盖屏（GuiMainMenu.java:246） | 返回自定义 proxy 在主菜单上叠加渲染/tick 逻辑 | 返回 null 是合法的（当前即如此）；GuiMainMenu 会 tick/render 该屏 |
| `public void render(int p_render_1_, int p_render_2_, float p_render_3_)` | RealmsScreen.java:94 | 每帧，proxy 的 `drawScreen` | 所有 Realms 屏幕的统一渲染入口，可注入全局覆盖绘制 | 覆写后须保留按钮遍历渲染或自行绘制按钮 |
| `public void tick()` | RealmsScreen.java:136 | 每客户端 tick，proxy 的 `updateScreen` | 屏幕级周期逻辑 | 主线程 |
| `public void keyPressed(char p_keyPressed_1_, int p_keyPressed_2_)` | RealmsScreen.java:229 | proxy 的 `keyTyped` | 键盘输入拦截（keycode 为 LWJGL keyboard 常量，1=ESC） | LWJGL3 移植下 keycode 由 shim 映射，语义仍是 LWJGL2 值 |
| `public void tick()` | RealmsConnect.java:110 | 持有者每 tick（主线程） | 观察/拦截 Realms 登录期的包回放；断连检测 | 不调则登录卡死；`connection` 由后台线程赋值，存在短暂 null 窗口 |
| `public void connect(final String p_connect_1_, final int p_connect_2_)` | RealmsConnect.java:27 | Realms 屏幕选择世界后 | 改写连接目标、注入代理、观察 handshake（硬编码协议 47） | 网络建立在自建线程，UI 反馈须回主线程（用 `Realms.setScreen`） |
| `public static void setScreen(RealmsScreen p_setScreen_0_)` | Realms.java:62 | 任意 Realms 代码切屏 | Realms 侧切屏的单一汇聚点，可观察/重定向 | 静态方法，只能改字节码或改源码挂钩 |
| `public void handleServerInfo(S00PacketServerInfo packetIn)`（匿名类内） | RealmsServerStatusPinger.java:40 | STATUS 响应到达 | 观察/改写 ping 结果展示 | 运行在 Netty 线程，只能写 volatile 字段，勿碰 GL |
| `public void clicked(float p_clicked_1_)` | RealmsSliderButton.java:96 | 滑块拖动/点击换算出新值时 | 子类接收滑块值变化 | 拖动期间每帧触发（renderBg 内，:75） |

## 数据与协议

本包不定义封包与文件格式，仅复用现有协议。字段级要点：

| 字段/常量 | 类型 | 读/写位置 | 含义 |
|---|---|---|---|
| `RealmsSharedConstants.NETWORK_PROTOCOL_VERSION` | `public static int` = 47 | RealmsServerStatusPinger.java:98 handshake 用 | 1.8.x 协议号；注意 RealmsConnect.java:59 是**硬编码 47**，不引用此常量 |
| `RealmsSharedConstants.TICKS_PER_SECOND` | `public static int` = 20 | 外部 Realms jar 用 | 逻辑 tick 率 |
| `RealmsSharedConstants.VERSION_STRING` | `public static String` = "1.8.9" | 外部 Realms jar 用 | 客户端版本串 |
| `RealmsSharedConstants.ILLEGAL_FILE_CHARACTERS` | `public static char[]` | = `ChatAllowedCharacters.allowedCharactersArray` | 存档名过滤字符 |
| `RealmsServerPing.nrOfPlayers` | `public volatile String` = "0" | Netty 线程写（Pinger:46），UI 线程读 | 在线人数 |
| `RealmsServerPing.lastPingSnapshot` | `public volatile long` = 0L | 本包内无写入点 | 延迟快照（由外部 Realms jar 使用） |
| `RealmsServerPing.playerList` | `public volatile String` = "" | Netty 线程写（Pinger:72/77） | 换行分隔的玩家名单，超出部分 "... and N more ..." |

协议交互序列：STATUS ping 为 `C00Handshake(STATUS)` → `C00PacketServerQuery` → `S00PacketServerInfo` → `C01PacketPing` → `S01PacketPong` → 关闭；登录为 `C00Handshake(47, host, port, LOGIN)` → `C00PacketLoginStart(GameProfile)`，之后交由 `NetHandlerLoginClient`。

## 不变量与陷阱

- **proxy 双向绑定**：`RealmsScreen` 与 `GuiScreenRealmsProxy` 在构造期互持引用；给 `Minecraft.displayGuiScreen` 的永远是 proxy，不是 `RealmsScreen` 本身。`instanceof GuiScreenRealmsProxy` 是识别 Realms 屏幕的方式（NetHandlerPlayClient.java:801 即如此）。
- **反射入口是软依赖**：`RealmsBridge` 两处 `Class.forName` 在本仓库必然失败且被静默吞掉（有意为之，RealmsBridge.java:29 注释）。给 Realms 按钮加功能时不要指望异常日志。
- **协议号双写**：改协议版本要同时改 `RealmsSharedConstants.NETWORK_PROTOCOL_VERSION` 和 `RealmsConnect.java:59` 的字面量 47。
- **线程安全**：`RealmsConnect.aborted` 是 volatile 且只在步骤间检查——abort 后已入队的包仍会发出，连接也不会被主动关闭；`connection` 字段无同步，主线程 `tick()` 首次读到非 null 前有竞态窗口（实践上无害，因为只做 null 检查）。`RealmsServerStatusPinger.connections` 用 synchronizedList 且 tick/removeAll 手动 `synchronized`，遍历时不得再入。
- **Netty 线程禁 GL**：`handleServerInfo` 等 status 回调在 EventLoop 上跑，只允许写 volatile 字段/发包，禁止碰 GUI 与 GL 状态。
- **`RealmsDefaultVertexFormat` 与 `DefaultVertexFormats` 不是同一对象**：布局相同但实例独立，做 `==` 比较格式会失败，须用 `equals`（`RealmsVertexFormat.equals` 委托到 `VertexFormat.equals`，RealmsVertexFormat.java:105）。
- **类初始化顺序**：`Tezzelator` 静态字段抓 `Tessellator.getInstance()`——在渲染系统就绪前触碰该类会拿到过早初始化的 Tessellator（vanilla 同样如此，移植未改变此约束）。
- **LWJGL3/JDK25 移植**：本包无直接 LWJGL 调用，输入 keycode / 鼠标事件都经 proxy 层与 lwjgl2-shim 转换，`keyPressed` 里的 `p_keyPressed_2_ == 1`（ESC）等 LWJGL2 常量语义保持不变。`RealmsMth`/`RealmsBufferBuilder` 是纯转发，JDK25 无特殊风险。
- **`RealmsScreen.drawString` 陷阱**：五参重载忽略调用方传入的 shadow 布尔，恒以 `false` 转发（RealmsScreen.java:56）——vanilla 原样保留的怪癖，想要阴影须用 `fontDrawShadow`。
- **`RealmsLevelSummary.compareTo`**：排序是 lastPlayed **降序**（新的在前），同时间戳退化为 fileName 字典序（RealmsLevelSummary.java:61）。

## 交叉引用

- net.minecraft.client → `Minecraft#displayGuiScreen`、`Minecraft#getSession`、`Minecraft#setConnectedToRealms`、`Minecraft#getResourcePackRepository`、`Minecraft#getSaveLoader`、`Minecraft#getTextureManager`
- net.minecraft.client.gui → `GuiScreenRealmsProxy`（RealmsScreen 全部 UI 委托）、`GuiButtonRealmsProxy`（RealmsButton）、`GuiSlotRealmsProxy` / `GuiSimpleScrolledSelectionListProxy` / `GuiClickableScrolledSelectionListProxy`（三种列表）、`GuiTextField`（RealmsEditBox）、`Gui#drawScaledCustomSizeModalRect`
- net.minecraft.client.gui → `GuiMainMenu#actionPerformed` / `GuiMainMenu#switchToRealms`、`GuiIngameMenu#actionPerformed`（RealmsBridge 的调用方）
- net.minecraft.client.network → `NetHandlerLoginClient`（RealmsConnect#connect）、`NetHandlerPlayClient#onDisconnect`（构造 DisconnectedRealmsScreen）
- net.minecraft.network → `NetworkManager#createNetworkManagerAndConnect`、`NetworkManager#processReceivedPackets`、handshake/login/status 包类
- net.minecraft.client.renderer → `Tessellator#getInstance`、`WorldRenderer`（RealmsBufferBuilder）、`GlStateManager#color`（RealmsSliderButton#renderBg）
- net.minecraft.client.renderer.vertex → `VertexFormat` / `VertexFormatElement`（RealmsVertexFormat 系）
- net.minecraft.world.storage → `ISaveFormat`、`SaveFormatComparator`（存档两个 wrapper）
- net.minecraft.util → `MathHelper`（RealmsMth）、`Session`、`ChatAllowedCharacters`、`IChatComponent`
- net.minecraft.client.multiplayer → `ServerAddress#fromString`（RealmsServerAddress#parseString）
- net.minecraft.client.resources → `I18n#format`、`DefaultPlayerSkin#getDefaultSkin`（RealmsScreen#bindFace）
- net.minecraft.client.settings → `GameSettings.Options.REALMS_NOTIFICATIONS`（Realms#getRealmsNotificationsEnabled）

## 覆盖声明

完整读取了 23/23 个文件（每个文件从第 1 行读到末行）。逐行精读：RealmsScreen、RealmsConnect、RealmsServerStatusPinger、RealmsBridge、Realms、RealmsSliderButton、DisconnectedRealmsScreen、Tezzelator、RealmsDefaultVertexFormat。其余（RealmsBufferBuilder、RealmsMth、RealmsVertexFormat、RealmsVertexFormatElement、RealmsEditBox、RealmsButton、三个 ScrolledSelectionList、RealmsAnvilLevelStorageSource、RealmsLevelSummary、RealmsServerAddress、RealmsServerPing、RealmsSharedConstants）为纯委托/常量类，做了全文阅读但按转发表理解，未逐一核对每个被委托方法的实现体。另外核对了包外调用方 GuiMainMenu、GuiIngameMenu、NetHandlerPlayClient 的相关行号（grep 确认），GuiScreenRealmsProxy 仅确认存在与行数，未精读其 252 行实现。
