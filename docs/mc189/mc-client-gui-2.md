---
area: net/minecraft/client/gui#2
slug: mc-client-gui-2
files: 45
lines: 7928
tier: A
---

# net/minecraft/client/gui #2 — 选择列表 / 文本框 / 旁观菜单 / 成就统计 / Twitch 流

## 定位

本 bucket 是 `net.minecraft.client.gui` 包的第二部分（按字母序 GuiScreenOptionsSounds 之后），外加三个子包 `gui.achievement`、`gui.spectator`、`gui.stream`。内容可分为六块：

1. **通用控件基建**：`GuiSlot`（所有可滚动列表的抽象基类）、`GuiTextField`（单行文本输入框）、`GuiSlider`（带回调的滑块）、`ScaledResolution`（GUI 缩放计算）、`GuiUtilRenderComponents`（聊天组件按宽度拆行）。这一块是被复用最广的：几乎每个带列表或输入框的界面都建立在它们之上。
2. **具体设置/功能屏幕**：声音、视频、聊天、Snooper、资源包、LAN 共享、直连服务器、选择世界、Yes/No 确认、结局字幕（GuiWinGame）等，全部继承本包（bucket #1）的 `GuiScreen`。
3. **多人服务器列表条目**：`ServerSelectionList` + `ServerListEntryNormal` / `ServerListEntryLanDetected` / `ServerListEntryLanScan`，由 `GuiMultiplayer`（bucket #1）持有，负责服务器 ping、图标解码、双击连接。
4. **旁观者（Spectator）快捷菜单**：`GuiSpectator` 挂在 `GuiIngame` 上，`spectator` 子包实现菜单数据模型；选中玩家会直接发 `C18PacketSpectate`。
5. **成就与统计**：`GuiAchievement`（右上角弹出通知，非 GuiScreen）、`GuiAchievements`（成就树）、`GuiStats`（统计页），后两者实现 `IProgressMeter`，等待服务器回发统计数据。
6. **Twitch/Realms 遗留**：`gui.stream` 四个屏幕 + `GuiStreamIndicator`，以及 `GuiScreenRealmsProxy` / `GuiSlotRealmsProxy` / `GuiSimpleScrolledSelectionListProxy` 三个把 `net.minecraft.realms` API 桥接到本地 GUI 体系的代理类。

谁调用它：`Minecraft`（displayGuiScreen、按键分发进 spectatorGui、每帧 `guiAchievement.updateAchievementWindow()`）、`GuiIngame`（每帧 render spectator/stream indicator）、`NetHandlerPlayClient`（收到 S37PacketStatistics 时回调 `IProgressMeter.doneLoading()`、收到成就统计时调 `guiAchievement.displayAchievement`、游戏胜利时 `displayGuiScreen(new GuiWinGame())`）、`EntityRenderer`/`ItemRenderer`/`RenderItemFrame`（地图渲染走 `MapItemRenderer`）。

它调用谁：`GameSettings`（读写选项并 `saveOptions()`）、`NetHandlerPlayClient.addToSendQueue`（发包）、`IntegratedServer.shareToLAN`、`ISaveFormat`（世界列表/删除）、`ResourcePackRepository`、`TextureManager`/`Tessellator`/`GlStateManager`（渲染）、`IStream`（Twitch）。

如果它消失：所有滚动列表（服务器列表、世界选择、资源包、统计页）、所有文本输入（聊天框内部也用 `GuiTextField`）、GUI 缩放计算、旁观者菜单、成就/统计界面、物品地图渲染全部不可用——客户端 UI 层会大面积瘫痪。

## 类清单

| 类名 | 行数 | extends / implements | 一句话职责 |
|---|---|---|---|
| GuiScreenOptionsSounds | 165 | extends GuiScreen | 音量设置屏，内部类 `Button` 是每个 SoundCategory 的自绘滑块 |
| GuiScreenRealmsProxy | 252 | extends GuiScreen | 把 `RealmsScreen` 包装成 GuiScreen，所有输入/绘制事件双向转发 |
| GuiScreenResourcePacks | 243 | extends GuiScreen | 资源包选择屏，左右两个列表，Done 时 `refreshResources()` |
| GuiScreenServerList | 110 | extends GuiScreen | 直连服务器输入框屏，结果经 `confirmClicked` 回传父屏 |
| GuiScreenWorking | 72 | extends GuiScreen implements IProgressUpdate | 通用"Working... N%"进度屏，`setDoneWorking()` 后自动关闭 |
| GuiSelectWorld | 342 | extends GuiScreen implements GuiYesNoCallback | 单人世界选择/删除/重建，双击进入世界 |
| GuiShareToLan | 116 | extends GuiScreen | 对局域网开放：选游戏模式/作弊后调 `IntegratedServer.shareToLAN` |
| GuiSimpleScrolledSelectionListProxy | 157 | extends GuiSlot | Realms 简单滚动列表代理，重写 `drawScreen` 去掉背景纹理 |
| GuiSleepMP | 68 | extends GuiChat | 多人睡觉时的聊天屏，附"Leave Bed"按钮，发 STOP_SLEEPING 包 |
| GuiSlider | 145 | extends GuiButton | 通用滑块控件，值变化经 `GuiPageButtonList.GuiResponder.onTick` 回调 |
| GuiSlot | 525 | （抽象基类） | 所有可滚动列表的基类：滚动、选中、拖拽、滚轮、槽位命中计算 |
| GuiSlotRealmsProxy | 79 | extends GuiSlot | 把 GuiSlot 的抽象方法转发给 `RealmsScrolledSelectionList` |
| GuiSnooper | 155 | extends GuiScreen | Snooper 数据查看屏，内部类 `List extends GuiSlot` 显示键值对 |
| GuiSpectator | 186 | extends Gui implements ISpectatorMenuRecipient | 旁观者热键条 UI：渲染 9 格菜单、处理热键选择 |
| GuiStreamIndicator | 109 | （无） | 直播状态角标（Twitch），闪烁 alpha 由每 tick 的 `updateStreamAlpha()` 驱动 |
| GuiTextField | 807 | extends Gui | 单行文本框：光标/选区/剪贴板/字符过滤/validator/横向滚动 |
| GuiUtilRenderComponents | 105 | （静态工具） | `splitText` 把 IChatComponent 按像素宽度拆成多行组件 |
| GuiVideoSettings | 128 | extends GuiScreen | 视频设置屏，用 `GuiOptionsRowList`；guiScale 改变时立刻重建分辨率 |
| GuiWinGame | 245 | extends GuiScreen | 终末之诗+职员表滚动屏，播完或按 Esc 发 PERFORM_RESPAWN |
| GuiYesNo | 112 | extends GuiScreen | 通用二选一确认屏，结果回调 `GuiYesNoCallback.confirmClicked` |
| GuiYesNoCallback | 6 | interface | 确认对话框回调：`confirmClicked(boolean result, int id)` |
| IProgressMeter | 8 | interface | 统计下载完成回调 `doneLoading()`；带 `lanSearchStates` 动画帧常量 |
| MapItemRenderer | 162 | （无） | 物品地图渲染：MapData → DynamicTexture 128x128 + 图标装饰 |
| ScaledResolution | 67 | （无） | 由窗口像素尺寸和 guiScale 计算缩放后的 GUI 逻辑分辨率 |
| ScreenChatOptions | 76 | extends GuiScreen | 聊天设置屏（可见性/颜色/宽高等 10 项） |
| ServerListEntryLanDetected | 67 | implements GuiListExtended.IGuiListEntry | 局域网发现的服务器条目，双击 250ms 内连接 |
| ServerListEntryLanScan | 53 | implements GuiListExtended.IGuiListEntry | "Scanning for games..."占位条目，`O o o` 动画 |
| ServerListEntryNormal | 343 | implements GuiListExtended.IGuiListEntry | 收藏服务器条目：异步 ping、MOTD/延迟/图标绘制、点击连接 |
| ServerSelectionList | 103 | extends GuiListExtended | 多人界面的复合列表：收藏服 + LAN 扫描行 + LAN 服 |
| achievement/GuiAchievement | 145 | extends Gui | 右上角成就获得弹窗（独立于 GuiScreen，每帧由 Minecraft 调用） |
| achievement/GuiAchievements | 568 | extends GuiScreen implements IProgressMeter | 可拖拽/缩放的成就树屏幕，背景为程序化生成的方块贴图 |
| achievement/GuiStats | 824 | extends GuiScreen implements IProgressMeter | 统计屏：General/Blocks/Items/Mobs 四个内部 GuiSlot 列表可排序 |
| spectator/BaseSpectatorGroup | 29 | implements ISpectatorMenuView | 旁观菜单根分组：TeleportToPlayer + TeleportToTeam |
| spectator/ISpectatorMenuObject | 14 | interface | 菜单项：选择行为、名称、图标绘制、是否可用 |
| spectator/ISpectatorMenuRecipient | 6 | interface | 菜单关闭通知：`func_175257_a(SpectatorMenu)` |
| spectator/ISpectatorMenuView | 11 | interface | 菜单分组视图：项目列表 + 提示文本 |
| spectator/PlayerMenuObject | 47 | implements ISpectatorMenuObject | 单个玩家菜单项，选择时发 `C18PacketSpectate` |
| spectator/SpectatorMenu | 183 | （无） | 旁观菜单状态机：分页、选中、子菜单入栈、关闭 |
| spectator/categories/SpectatorDetails | 31 | （无） | 菜单某一页的快照（分组 + 9 项 + 选中下标） |
| spectator/categories/TeleportToPlayer | 80 | implements ISpectatorMenuView, ISpectatorMenuObject | "传送到玩家"分组，按 UUID 排序、过滤旁观者 |
| spectator/categories/TeleportToTeam | 148 | implements ISpectatorMenuView, ISpectatorMenuObject | "传送到队伍"分组，内部类 `TeamSelectionObject` 随机取成员皮肤 |
| stream/GuiIngestServers | 178 | extends GuiScreen | Twitch 推流服务器选择/测速列表 |
| stream/GuiStreamOptions | 144 | extends GuiScreen | Twitch 推流参数设置（码率/FPS/麦克风等 + 聊天两项） |
| stream/GuiStreamUnavailable | 271 | extends GuiScreen | 推流不可用原因提示屏；静态 `func_152321_a` 是诊断分发器 |
| stream/GuiTwitchUserMode | 243 | extends GuiScreen | Twitch 聊天用户信息/管理屏（ban/timeout/mod 等命令） |

## 核心类详解

### GuiSlot（`client/src/main/java/net/minecraft/client/gui/GuiSlot.java`）

所有滚动列表的基类。子类只须实现 4 个抽象方法：

```java
protected abstract int getSize();                                                        // GuiSlot.java:106
protected abstract void elementClicked(int slotIndex, boolean isDoubleClick, int mouseX, int mouseY);  // :111
protected abstract boolean isSelected(int slotIndex);                                    // :116
protected abstract void drawSlot(int entryID, int p_180791_2_, int p_180791_3_, int p_180791_4_, int mouseXIn, int mouseYIn);  // :132
```

关键字段（GuiSlot.java:13-62）：`protected final Minecraft mc`、`protected int top/bottom/left/right`（列表可视区域）、`protected final int slotHeight`、`protected float amountScrolled`（滚动偏移，float）、`protected int selectedElement = -1`、`protected long lastClicked`（双击判定基准）、`protected int initialClickY = -2`（拖动状态：-2 空闲 / -1 待判定 / ≥0 拖动中）、`protected float scrollMultiplier`。

关键方法：

- `public void drawScreen(int mouseXIn, int mouseYIn, float p_148128_3_)`（:222）——每帧由持有它的 GuiScreen 的 `drawScreen` 调用。流程：`drawBackground()` → `bindAmountScrolled()` 夹紧滚动 → 绘制 `Gui.optionsBackground` 平铺底纹 → `drawSelectionBox`（内部对每个 slot 调 `drawSlot`，:485）→ 上下遮罩渐变 → 滚动条（`func_148135_f()` > 0 时，:277）。
- `public void handleMouseInput()`（:316）——由 GuiScreen 的 `handleMouseInput` 手动转发（本类不是 GuiButton，不在 buttonList 里）。直接读 `Mouse.getEventButton()` / `Mouse.getEventButtonState()` / `Mouse.isButtonDown(0)` / `Mouse.getEventDWheel()`（:320,338,411）。命中槽位后调 `elementClicked`，双击判定条件是 `i1 == this.selectedElement && Minecraft.getSystemTime() - this.lastClicked < 250L`（:353）。点在表头区域（k < 0）调 `func_148132_a`（:334）。滚轮每格滚 `slotHeight / 2`（:424）。
- `public int getSlotIndexFromScreenCoords(int p_148124_1_, int p_148124_2_)`（:149）——屏幕坐标 → 槽位下标，越界返回 -1。
- `protected int getContentHeight()`（:121）默认 `getSize() * slotHeight + headerPadding`；`public int getListWidth()`（:442）默认 220；`protected int getScrollBarX()`（:489）默认 `width / 2 + 124`。子类常重写这三个。
- 空实现钩子：`func_148132_a`（表头点击, :141）、`func_148142_b`（每帧 post-render，GuiStats 用来画 tooltip, :145）、`drawListHeader`（:137）、`func_178040_a`（:128）。

### GuiTextField（`GuiTextField.java`）

单行文本框。关键字段（:14-58）：`private String text = ""`、`private int maxStringLength = 32`、`private int cursorCounter`（闪烁计数，需外部每 tick 调 `updateCursorCounter()`）、`private boolean isFocused`、`private boolean isEnabled = true`、`private int lineScrollOffset`（横向滚动起始字符）、`private int cursorPosition`、`private int selectionEnd`、`private GuiPageButtonList.GuiResponder field_175210_x`（文本变化回调）、`private Predicate<String> validator = Predicates.<String>alwaysTrue()`。

关键方法：

```java
public boolean textboxKeyTyped(char p_146201_1_, int p_146201_2_)   // GuiTextField.java:337
public void mouseClicked(int p_146192_1_, int p_146192_2_, int p_146192_3_)  // :499
public void drawTextBox()                                           // :525
public void writeText(String p_146191_1_)                           // :129
public void setText(String p_146180_1_)                             // :86
public void setValidator(Predicate<String> theValidator)            // :121
public void func_175207_a(GuiPageButtonList.GuiResponder p_175207_1_)  // :70
public void setFocused(boolean p_146195_1_)                         // :697
```

- `textboxKeyTyped` 由宿主 GuiScreen 的 `keyTyped` 转发。未聚焦直接返回 false（:339）。处理 Ctrl+A/C/V/X（走 `GuiScreen.isKeyComboCtrl*`，:343-373）、Backspace(14)/Home(199)/Left(203)/Right(205)/End(207)/Delete(211)，其余字符经 `ChatAllowedCharacters.isAllowedCharacter` 过滤后 `writeText`（:479-487）。
- `writeText`（:129）先 `ChatAllowedCharacters.filterAllowedCharacters`，拼接后必须通过 `validator.apply(s)` 才落盘（:159），成功后回调 `field_175210_x.func_175319_a(this.id, this.text)`（:166）。
- `drawTextBox`（:525）光标闪烁条件 `this.isFocused && this.cursorCounter / 6 % 2 == 0`（:540）；选区高亮用 GL 逻辑操作 `GlStateManager.colorLogicOp(5387)`（OR_REVERSE，:628）画反色竖条。
- `mouseClicked`（:499）点击框内设置焦点并按像素宽度定位光标；`canLoseFocus` 为 true 时点击框外失焦。

### ScaledResolution（`ScaledResolution.java`）

```java
public ScaledResolution(Minecraft p_i46445_1_)   // ScaledResolution.java:14
```

构造即计算：`guiScale == 0`（AUTO）视为 1000（:22-25），然后 `while (this.scaleFactor < i && this.scaledWidth / (this.scaleFactor + 1) >= 320 && this.scaledHeight / (this.scaleFactor + 1) >= 240) ++this.scaleFactor;`（:27）。Unicode 字体时强制偶数缩放（:32-35）。`scaledWidth = MathHelper.ceiling_double_int(scaledWidthD)`（:39）。全客户端每帧多处 `new ScaledResolution(mc)`（仓库内共 18 处），任何 HUD/GUI 坐标换算都以它为准。

### GuiSelectWorld（`GuiSelectWorld.java`）

单人世界选择。`initGui`（:51）经 `this.mc.getSaveLoader().getSaveList()` 读世界列表并排序（`loadLevelList`, :89）。按钮：1=Play、3=Create、6=Rename、2=Delete、7=Re-Create、0=Cancel（:114-126）。

- `public void func_146615_e(int p_146615_1_)`（:178）——真正进入世界：`this.mc.launchIntegratedServer(s, s1, (WorldSettings)null)`（:201），`field_146634_i` 防重入。
- `public void confirmClicked(boolean result, int id)`（:206）——删除确认回调：`isaveformat.deleteWorldDirectory(this.func_146621_a(id))`（:216）。
- `public static GuiYesNo makeDeleteWorldYesNo(GuiYesNoCallback selectWorld, String name, int id)`（:251）。
- 内部类 `List extends GuiSlot`（:261）：`elementClicked`（:273）单击启用四个按钮，双击直接 `func_146615_e(slotIndex)`（:284）。

### ServerListEntryNormal（`ServerListEntryNormal.java`）

多人列表里一行收藏服务器。静态线程池：

```java
private static final ThreadPoolExecutor field_148302_b = new ScheduledThreadPoolExecutor(5, (new ThreadFactoryBuilder()).setNameFormat("Server Pinger #%d").setDaemon(true).build());   // ServerListEntryNormal.java:28
```

- `public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected)`（:48）——**首次绘制时惰性触发 ping**：`this.server.field_78841_f` 为 false 时置位并向线程池提交 `owner.getOldServerPinger().ping(this.server)`（:56-75），异常时在 worker 线程里直接写 `server.pingToServer = -1L` / `serverMOTD`。协议版本判断硬编码 `this.server.version > 47` / `< 47`（1.8 的协议号 47，:78-79）。延迟图标按 pingToServer 分档 150/300/600/1000ms（:109-128）。服务器返回新的 base64 图标时调 `prepareServerIcon()` 并 `owner.getServerList().saveServerList()`（:157-162）。悬停延迟/玩家列表走 `owner.setHoveringText(...)`（:178,182）。
- `private void prepareServerIcon()`（:244）——Netty `Base64.decode` → `TextureUtil.readBufferedImage`，强制 64x64（`Validate.validState(bufferedimage.getWidth() == 64, ...)`，:261-262），失败则清空 icon 数据；成功则写进 `DynamicTexture` 并 `updateDynamicTexture()`（:286）。
- `public boolean mousePressed(int slotIndex, int p_148278_2_, int p_148278_3_, int p_148278_4_, int p_148278_5_, int p_148278_6_)`（:293）——relativeX≤32 区域是"加入/上移/下移"迷你按钮（:295-315）；否则 `owner.selectServer(slotIndex)`，250ms 内二次点击 `owner.connectToSelected()`（:319-321）。

### ServerSelectionList（`ServerSelectionList.java`）

`extends GuiListExtended`。三段拼接：`getSize() = serverListInternet.size() + 1 + serverListLan.size()`（:48-51），中间那 1 行固定是 `lanScanEntry`（:14）。`getListEntry(int index)`（:26）按下标路由到三种 entry。`func_148195_a(ServerList)`（:71）重建收藏列表、`func_148194_a(List<LanServerDetector.LanServer>)`（:81）重建 LAN 列表——两者都由 `GuiMultiplayer` 在刷新时调用。

### GuiSpectator + spectator 子包

`GuiSpectator extends Gui implements ISpectatorMenuRecipient`（GuiSpectator.java:14）。字段：`private long field_175270_h`（最后交互时间戳）、`private SpectatorMenu field_175271_i`（当前菜单，null 表示未打开）、`public static final ResourceLocation field_175269_a = new ResourceLocation("textures/gui/spectator_widgets.png")`（:17）。

- `public void func_175260_a(int p_175260_1_)`（:27）——热键数字 1-9 入口：菜单未开则 `new SpectatorMenu(this)`，已开则 `field_175271_i.func_178644_b(slot)`。调用方 `Minecraft.java:2076`（`processKeyBinds` 中旁观者模式按 hotbar 键）。
- `public void func_175261_b()`（:168）——鼠标中键：菜单未开则打开，已开且有选中则再次触发选中项。调用方 `Minecraft.java:1842`。
- `public void func_175259_b(int p_175259_1_)`（:152）——滚轮在 9 格间移动选中（跳过不可用项）。调用方 `Minecraft.java:1864`。
- `public void renderTooltip(ScaledResolution p_175264_1_, float p_175264_2_)`（:47）——每帧由 `GuiIngame.renderGameOverlay`（GuiIngame.java:164）调用；打开后 5 秒无操作淡出（`func_175265_c()`，:41），alpha 归零时自动 `func_178641_d()` 关闭。
- `public void func_175257_a(SpectatorMenu p_175257_1_)`（:141）——菜单关闭回调，把 `field_175271_i` 置 null。

`SpectatorMenu`（SpectatorMenu.java:13）是纯数据状态机：`func_178643_a(int)`（:47）把 9 个格子映射为 上一页/下一页/关闭/普通项；`func_178644_b(int)`（:75）第一次按选中、第二次按且 `func_178662_A_()` 为 true 时执行 `func_178661_a(this)`；`func_178647_a(ISpectatorMenuView)`（:102）进入子菜单并把当前页快照压入 `field_178652_g`。哨兵空项 `public static final ISpectatorMenuObject field_178657_a`（:19）。

`PlayerMenuObject.func_178661_a(SpectatorMenu menu)`（PlayerMenuObject.java:25）直接发包：`Minecraft.getMinecraft().getNetHandler().addToSendQueue(new C18PacketSpectate(this.profile.getId()))`（:27）。`TeleportToPlayer` 构造时按 UUID 排序在线玩家并过滤 `WorldSettings.GameType.SPECTATOR`（TeleportToPlayer.java:37-48）；`TeleportToTeam.TeamSelectionObject` 从记分板队伍取成员，随机挑一个成员的皮肤做图标（TeleportToTeam.java:82-107）。

### GuiAchievement（`achievement/GuiAchievement.java`）

**不是 GuiScreen**，是常驻 HUD 弹窗。`Minecraft.java:565` 构造，`Minecraft.java:1163` 每帧调 `updateAchievementWindow()`。

- `public void displayAchievement(Achievement ach)`（:32）——由 `NetHandlerPlayClient.handleStatistics`（NetHandlerPlayClient.java:1473）在收到新成就时调用；3 秒滑入滑出动画（`(Minecraft.getSystemTime() - this.notificationTime) / 3000.0D`，:75）。
- `public void displayUnformattedAchievement(Achievement achievementIn)`（:41）——`permanentNotification = true` 的常驻提示（"按 E 打开背包"，NetHandlerPlayClient.java:1491）。
- `private void updateAchievementWindowScale()`（:50）——**每次绘制自建正交投影**（`GlStateManager.ortho(0.0D, (double)this.width, (double)this.height, 0.0D, 1000.0D, 3000.0D)`，:65），因为它在主 GUI 投影之外被调用。
- `public void clearAchievements()`（:140）——`Minecraft.java:2367` 在 `loadWorld(null)` 时清理。

### GuiAchievements / GuiStats（`achievement/`）

两者共享同一模式：`initGui` 发 `C16PacketClientStatus(REQUEST_STATS)`（GuiAchievements.java:65 / GuiStats.java:59），在 `loadingAchievements` / `doesGuiPauseGame` 标志为 true 期间画 `lanSearchStates` 转圈动画（GuiAchievements.java:110 / GuiStats.java:157）；`NetHandlerPlayClient.handleStatistics` 末尾 `((IProgressMeter)this.gameController.currentScreen).doneLoading()`（NetHandlerPlayClient.java:1498）解除等待。

- `GuiAchievements.doneLoading()`（:201）只翻标志；`GuiStats.doneLoading()`（:167）额外 `func_175366_f()` 构建四个列表、`createButtons()`、默认显示 `generalStats`。
- `GuiAchievements.drawScreen`（:104）直接读 `Mouse.isButtonDown(0)` / `Mouse.getDWheel()` 实现拖拽与缩放（:114,144），缩放 clamp 到 [1.0, 2.0]（:156）。`updateScreen`（:212）做每 tick 0.85 系数的平滑跟随。`drawAchievementScreen`（:241）背景用玩家 ID 哈希做种子的伪随机矿石贴图（:299），成就间连线颜色按解锁状态区分（:358-375）。`doesGuiPauseGame()` 返回 `!this.loadingAchievements`（:564）。
- `GuiStats` 内部抽象类 `Stats extends GuiSlot`（GuiStats.java:225）实现三列可排序表头：`func_148132_a`（表头点击命中，:311）→ `func_148212_h`（切换排序列/方向后 `Collections.sort(this.statsHolder, this.statSorter)`，:434-452）；`func_148142_b`（:361）画悬停 tooltip。三个子类 `StatsBlock`（:455）/`StatsItem`（:627）/`StatsMobsList`（:758）+ `StatsGeneral extends GuiSlot`（:586）。

### GuiScreenRealmsProxy（`GuiScreenRealmsProxy.java`）

Realms 桥。构造时把 `buttonList` 换成同步 List：`super.buttonList = Collections.<GuiButton>synchronizedList(Lists.<GuiButton>newArrayList())`（:18）——Realms 代码可能从别的线程加按钮。所有生命周期方法先转发给 `RealmsScreen` 再（可选）调 super：`initGui` → `field_154330_a.init()`（:32）、`drawScreen` → `render`（:97，**不调 super**）、`updateScreen` → `tick`（:127）、`mouseClicked/handleMouseInput/handleKeyboardInput/keyTyped/mouseReleased/mouseClickMove/confirmClicked/onGuiClosed` 逐一对应（:189-251）。`public final void actionPerformed(GuiButton button)`（:154）把按钮强转 `GuiButtonRealmsProxy` 后回调 `buttonClicked`。

### MapItemRenderer（`MapItemRenderer.java`）

由 `EntityRenderer` 持有（EntityRenderer.java:189，`getMapItemRenderer()` :2046）。

```java
public void updateMapTexture(MapData mapdataIn)            // MapItemRenderer.java:30
public void renderMap(MapData mapdataIn, boolean p_148250_2_)  // :35
public void clearLoadedMaps()                              // :59
```

内部类 `Instance`（:69）按 `mapData.mapName` 缓存在 `loadedMaps`（:20,43-54），每个实例一张 `new DynamicTexture(128, 128)`（:79）。`updateMapTexture`（:89）把 `this.mapData.colors[i] & 255` 16384 个字节经 `MapColor.mapColorArray[j / 4].getMapColor(j & 3)` 转 ARGB（:91-103），`j / 4 == 0` 时画棋盘格底色（:97）。`render(boolean noOverlayRendering)`（:108）画 128x128 quad 加 `mapData.mapDecorations` 图标（`Vec4b`：`func_176110_a()`=图标类型、`func_176112_b()/func_176113_c()`=坐标、`func_176111_d()`=旋转，:130-153）。调用方：`ItemRenderer.java:219`（第一人称手持）、`RenderItemFrame.java:124`（展示框）、`Minecraft.java:2368`（`clearLoadedMaps`）。

### GuiUtilRenderComponents（`GuiUtilRenderComponents.java`）

```java
public static String func_178909_a(String p_178909_0_, boolean p_178909_1_)   // :12
public static List<IChatComponent> splitText(IChatComponent p_178908_0_, int p_178908_1_, FontRenderer p_178908_2_, boolean p_178908_3_, boolean p_178908_4_)   // :17
```

`splitText` 是聊天 GUI（`GuiNewChat`）分行的核心：展开兄弟组件、处理 `\n`、按 `FontRenderer.getStringWidth` 在空格处断行、保留 `ChatStyle`（每段 `createShallowCopy()`）。`func_178909_a` 在 `gameSettings.chatColours` 关闭时剥掉格式码（:14）。

### GuiSlider（`GuiSlider.java`）

`extends GuiButton`。与 `GuiOptionSlider` 不同，本类是通用滑块（世界自定义等页面使用），值域 `[min, max]`，每次变动回调 `this.responder.onTick(this.id, this.func_175220_c())`（:41,86,99,123）。关键方法：`mousePressed`（:106，按下即跳值）、`mouseDragged`（:67，拖动更新+绘制滑块钮）、`func_175218_a(float p_175218_1_, boolean p_175218_2_)`（:34，程序设值，可选触发回调）、`func_175220_c()`（:29，读真实值）。内嵌 `public interface FormatHelper { String getText(int id, String name, float value); }`（:141）。

### GuiScreenOptionsSounds（`GuiScreenOptionsSounds.java`）

MASTER 单独一行宽 310，其余类别 150 宽两列（:38-48）。内部类 `Button extends GuiButton`（:84）是自绘滑块：`mouseDragged`（:105）实时 `mc.gameSettings.setSoundLevel(...)` 并 `saveOptions()`（:113-114）；`mouseReleased`（:146）播放 `gui.button.press` 音效验证音量；`playPressSound` 被覆写为空（:142）防止按下时提前播音。

### GuiWinGame（`GuiWinGame.java`）

- `public void updateScreen()`（:36）——第 0 tick 切 `MusicTicker.MusicType.CREDITS`（:44）；滚动进度超过 `(float)(this.field_146579_r + this.height + this.height + 24) / this.field_146578_s` 后 `sendRespawnPacket()`（:50-55）。
- `private void sendRespawnPacket()`（:70）——`new C16PacketClientStatus(C16PacketClientStatus.EnumState.PERFORM_RESPAWN)` 然后关屏（:72-73）。Esc（keyCode==1）同样触发（:64）。
- `initGui`（:88）从资源 `texts/end.txt` / `texts/credits.txt` 读文本（:99,126），`PLAYERNAME` 替换为当前用户名（:108,131），乱码占位符用固定种子 `new Random(8124371L)`（:101）。`doesGuiPauseGame()` 返回 true（:79）。
- 由 `NetHandlerPlayClient.java:1387` 打开（`handleChangeGameState(S2BPacketChangeGameState)` 的 state==4 分支，NetHandlerPlayClient.java:1358,1384-1387）。

### GuiSleepMP（`GuiSleepMP.java`）

`extends GuiChat`。Esc 不关屏而是 `wakeFromSleep()`（:26-29）；回车发送聊天但不关屏（:30-45）。`private void wakeFromSleep()`（:63）发 `new C0BPacketEntityAction(this.mc.thePlayer, C0BPacketEntityAction.Action.STOP_SLEEPING)`（:66）。由 `Minecraft.java:1774` 在玩家 `isPlayerSleeping()` 时强制打开、醒来时（Minecraft.java:1777）自动关闭。

### GuiShareToLan（`GuiShareToLan.java`）

按钮 101=Start（`String s = this.mc.getIntegratedServer().shareToLAN(WorldSettings.GameType.getByName(this.field_146599_h), this.field_146600_i);`，:90，结果端口打进聊天）、104=循环切游戏模式（spectator→creative→adventure→survival→spectator，:61-81）、103=允许作弊开关、102=取消。注意失败分支的消息 `new ChatComponentText("commands.publish.failed")` 没有走翻译（原版 bug 原样保留，:99）。

### GuiScreenResourcePacks（`GuiScreenResourcePacks.java`）

`initGui`（:42）在 `changed == false` 时从 `ResourcePackRepository` 重建左右两个 entry 列表（可用 / 已选，已选列表反转显示，:47-67）。按钮 2=打开资源包文件夹：macOS 走 `Runtime.getRuntime().exec(new String[] {"/usr/bin/open", s})`（:124）、Windows 走 `cmd.exe /C start`（:134-138）、否则反射 `java.awt.Desktop`（:151-153），最后兜底 `Sys.openURL("file://" + s)`（:164）。按钮 1=Done：若 `changed`，把已选列表逆序写回 `setRepositories`、更新 `gameSettings.resourcePacks` / `incompatibleResourcePacks`、`saveOptions()` 后 **`this.mc.refreshResources()`**（:167-198）。`markChanged()`（:239）由列表 entry 在拖动/增删时调用。

### stream 子包（Twitch 遗留）

- `GuiStreamUnavailable.func_152321_a(GuiScreen p_152321_0_)`（GuiStreamUnavailable.java:156）——静态诊断分发器：按 FBO 支持（`OpenGlHelper.framebufferSupported`，:161，并直接查询 `GLContext.getCapabilities()` 的三个扩展位 :165-167）、`NullStream`、OS 版本、`twitch_access_token`、鉴权状态依次选 `Reason` 枚举（:232-245，每个成员携带说明文案和可选按钮文案）并 `displayGuiScreen`。
- `GuiStreamOptions`（GuiStreamOptions.java:12）——两组 `GameSettings.Options`（推流 8 项 + 聊天 2 项，:14-15）；广播中修改非聊天项会显示红字"需要重启流"（`field_152315_t`，:94-96,137-140）；201 按钮进 `GuiIngestServers`。
- `GuiIngestServers`（GuiIngestServers.java:13）——`initGui` 若未在测速则启动 `func_152909_x()`（:33-36）；`onGuiClosed` 中止测速（:54-60）；内部类 `ServerList extends GuiSlot` 点击行把 `serverUrl` 写入 `gameSettings.streamPreferredServer` 并保存（:105-109）。
- `GuiTwitchUserMode`（GuiTwitchUserMode.java:19）——展示 `ChatUserInfo` 的身份/订阅徽章（静态工具 `func_152328_a/func_152329_a/func_152330_a`，:38,71,101），按钮直接向 Twitch 聊天发 `/ban`、`/unban`、`/mod`、`/unmod`、`/timeout` 命令（`this.stream.func_152917_b(...)`，:197-223）。
- `GuiStreamIndicator`（GuiStreamIndicator.java:10）——`render(int, int)`（:22）仅在 `mc.getTwitchStream().isBroadcasting()` 时画角标与观众数；`updateStreamAlpha()`（:86）驱动 0.025/tick 的呼吸闪烁。调用方 GuiIngame.java:548（每帧）与 GuiIngame.java:1087（`updateTick` 每 tick）。

### 其余小类

- `GuiYesNo`（GuiYesNo.java:8）：`actionPerformed` 一行 `this.parentScreen.confirmClicked(button.id == 0, this.parentButtonClickedId);`（:63）。`setButtonDelay(int p_146350_1_)`（:87）禁用按钮 N tick（`updateScreen` :100 里 `--this.ticksUntilEnable == 0` 时恢复），用于"删除世界"这类危险确认。
- `GuiScreenWorking`（GuiScreenWorking.java:5）：实现 `IProgressUpdate`，`setDoneWorking()`（:47）后下一帧 `drawScreen` 里 `displayGuiScreen((GuiScreen)null)`（:57-62，Realms 连接中除外）。`ResourcePackRepository.java:218` 用它显示服务器资源包下载进度。
- `GuiScreenServerList`（GuiScreenServerList.java:8）：直连屏。确认时把地址写进 `this.field_146301_f.serverIP` 再 `this.field_146303_a.confirmClicked(true, 0)`（:68-69）——由父屏（GuiMultiplayer）实际发起连接。`onGuiClosed` 把地址存入 `gameSettings.lastServer`（:51）。
- `GuiVideoSettings`（GuiVideoSettings.java:8）：选项列表放在 `GuiOptionsRowList`（非 buttonList），需手动转发 `handleMouseInput/mouseClicked/mouseReleased`（:61-116）。`mouseClicked/mouseReleased` 检测 `guiScale` 变化后立刻 `new ScaledResolution(this.mc)` 并 `setWorldAndResolution` 重排界面（:91-97,109-115）。不支持 VBO 时把 `USE_VBO` 及之后的选项截掉（:34-51）。
- `GuiSnooper`（GuiSnooper.java:10）：客户端 snooper 统计 + 集成服务器统计（前缀 "C "/"S "，:53,61）。
- `GuiSlotRealmsProxy`（GuiSlotRealmsProxy.java:6）/ `GuiSimpleScrolledSelectionListProxy`（GuiSimpleScrolledSelectionListProxy.java:11）：把 GuiSlot 的抽象方法逐一转发给 Realms 的列表接口；后者整个重写 `drawScreen`（:85）以跳过 `Gui.optionsBackground` 底纹（Realms 自绘背景）。
- `ServerListEntryLanDetected`（ServerListEntryLanDetected.java:7）：`mousePressed`（:39）选中 + 250ms 双击连接（:43-46）。
- `ServerListEntryLanScan`（ServerListEntryLanScan.java:6）：纯动画占位，`Minecraft.getSystemTime() / 300L % 4L` 切换 `O o o` 帧（:16）。
- `ScreenChatOptions`（ScreenChatOptions.java:7）：10 个聊天相关 `GameSettings.Options` 的标准两列布局。
- `IProgressMeter`（IProgressMeter.java:3）：`String[] lanSearchStates = new String[] {"oooooo", "Oooooo", "oOoooo", "ooOooo", "oooOoo", "ooooOo", "oooooO"};`（:5）+ `void doneLoading();`（:7）。
- `GuiYesNoCallback`（GuiYesNoCallback.java:3）：`void confirmClicked(boolean result, int id);`（:5）。GuiScreen 本身实现了它，所以任何屏幕都能当确认回调方。

## 时序与生命周期

全部代码运行在**主线程（客户端渲染线程）**，唯一例外是 `ServerListEntryNormal` 的 ping 任务跑在 5 线程守护池 "Server Pinger #%d"（ServerListEntryNormal.java:28），worker 直接写 `ServerData` 字段、主线程每帧读——无同步，靠字段写入的原子性凑合（原版行为）。

GuiScreen 子类的标准生命周期（由 `Minecraft.displayGuiScreen` → `GuiScreen.setWorldAndResolution` 驱动）：

1. **构造**：只存父屏引用和参数，不碰 `mc`/`width`/`height`（此时还没注入）。
2. **initGui**：窗口尺寸就绪后调用；窗口 resize 会清空 buttonList 后**重新调用**——所以 `GuiScreenResourcePacks.changed`、`GuiWinGame.field_146582_i == null` 这类守卫存在的意义就是防 resize 重置状态。
3. **每 tick**：`updateScreen()`——`GuiScreenServerList` 里递增文本框光标（:23-26）、`GuiYesNo` 倒计时启用按钮（:100）、`GuiAchievements` 平滑滚动（:212）、`GuiWinGame` 推进字幕并检查结束（:36）、`GuiScreenRealmsProxy` 转发 `tick()`（:125）。
4. **每帧**：`handleMouseInput`/`handleKeyboardInput`（有输入事件时）→ `drawScreen(mouseX, mouseY, partialTicks)`。持有 GuiSlot 的屏幕必须在这两处手动转发（如 GuiVideoSettings.java:61-65,121-127）。
5. **onGuiClosed**：`GuiScreenServerList` 保存 lastServer（:48-53）、`GuiIngestServers` 中止测速（:54）、`GuiScreenRealmsProxy` 转发 `removed()`（:247）。

非 GuiScreen 常驻对象的时序：

- `GuiAchievement`：`Minecraft.runGameLoop` 每帧调 `updateAchievementWindow()`（Minecraft.java:1163），自建 ortho 投影，画完恢复深度状态。
- `GuiSpectator`：`GuiIngame.renderGameOverlay` 每帧调 `renderTooltip`（GuiIngame.java:164）与 `renderSelectedItem`（:232）；输入侧由 `Minecraft.runTick` 在旁观者模式下把中键/滚轮/数字键路由过来（Minecraft.java:1842,1864,2076）。
- `GuiStreamIndicator`：`GuiIngame` 每帧 render（:548）、每 tick `updateStreamAlpha()`（:1087）。
- `MapItemRenderer`：`NetHandlerPlayClient` 收到 S34PacketMaps 时（经主线程调度后）`updateMapTexture`；渲染发生在 `ItemRenderer`（第一人称）与 `RenderItemFrame`（展示框）的绘制阶段；`Minecraft.loadWorld(null)` 时 `clearLoadedMaps()`（Minecraft.java:2368）。
- 统计等待链：`GuiAchievements/GuiStats.initGui` 发 REQUEST_STATS → 服务器回 S37PacketStatistics → `NetHandlerPlayClient.handleStatistics`（已被 `checkThreadAndEnqueue` 调度回主线程）→ `currentScreen instanceof IProgressMeter` 时 `doneLoading()`（NetHandlerPlayClient.java:1498）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void handleMouseInput()` | GuiSlot.java:316 | 宿主屏每个鼠标事件转发 | 接管所有列表点击/拖拽/滚轮；实现平滑滚动、拖拽排序 | 直接读 `Mouse` 静态事件队列，一帧内多事件时依赖宿主循环；改动会影响所有列表 |
| `protected abstract void elementClicked(int slotIndex, boolean isDoubleClick, int mouseX, int mouseY)` | GuiSlot.java:111 | 列表项被点击（双击判定 250ms） | 观察/拦截所有列表选择行为的单一收口 | 各子类语义不同（选中 vs 立即执行） |
| `public void drawScreen(int mouseXIn, int mouseYIn, float p_148128_3_)` | GuiSlot.java:222 | 宿主屏每帧 | 替换列表整体渲染（自定义皮肤、模糊背景） | 内部改变 GL 状态较多（shadeModel/blend/alpha），退出前须还原 |
| `protected void drawSlot(int entryID, ...)` | GuiSlot.java:132（抽象） | 每帧每个可见槽位 | 单行内容渲染注入点 | 坐标是列表内容坐标，非屏幕坐标 |
| `public boolean textboxKeyTyped(char p_146201_1_, int p_146201_2_)` | GuiTextField.java:337 | 宿主 `keyTyped` 转发 | 全局文本输入拦截/宏展开/输入法适配 | 返回值决定宿主是否继续处理该键；字符经 `ChatAllowedCharacters` 过滤 |
| `public void writeText(String p_146191_1_)` | GuiTextField.java:129 | 键入/粘贴时 | 文本插入统一入口（含粘贴），配合 `setValidator` 做输入约束 | validator 拒绝时静默丢弃，无任何提示 |
| `public void func_175207_a(GuiPageButtonList.GuiResponder p_175207_1_)` | GuiTextField.java:70 | 初始化时注册 | 文本变化回调 `func_175319_a(id, text)`，无需轮询 | 仅 `writeText`/`deleteFromCursor` 触发，`setText` 不触发 |
| `public ScaledResolution(Minecraft p_i46445_1_)` | ScaledResolution.java:14 | 每帧多处 new | 修改 GUI 缩放策略（自定义 scale、DPI 适配） | 全局 18 处调用点，行为需一致；LWJGL3 下 `displayWidth/Height` 是 framebuffer 像素 |
| `public void func_175260_a(int p_175260_1_)` | GuiSpectator.java:27 | 旁观模式按热键 1-9（Minecraft.java:2076） | 拦截/扩展旁观菜单热键行为 | 菜单未打开时首次按键只开菜单不选择 |
| `public void func_175261_b()` / `public void func_175259_b(int p_175259_1_)` | GuiSpectator.java:168 / :152 | 旁观模式中键 / 滚轮（Minecraft.java:1842/1864） | 自定义旁观菜单导航 | `func_175259_b` 假设菜单已打开（`field_175271_i` 非 null 才安全） |
| `public void func_178644_b(int p_178644_1_)` | SpectatorMenu.java:75 | 菜单格被选择/确认 | 菜单项执行前的统一闸口（第二次按下才执行） | 对 `field_178657_a` 哨兵直接忽略 |
| `public void func_178661_a(SpectatorMenu menu)` | PlayerMenuObject.java:25 | 确认选择某玩家 | **发包点**：`C18PacketSpectate(profile.getId())`，可改为本地观战逻辑 | 直接走 `getNetHandler().addToSendQueue`，无二次确认 |
| `private void wakeFromSleep()` | GuiSleepMP.java:63 | 睡觉屏按 Esc 或按钮 | **发包点**：`C0BPacketEntityAction(STOP_SLEEPING)` | private，需在 `keyTyped`/`actionPerformed`（:24/:51）层挂钩 |
| `public void updateScreen()` | GuiWinGame.java:36 | 结局字幕每 tick | 跳过/加速字幕；`sendRespawnPacket` 发 `C16PacketClientStatus(PERFORM_RESPAWN)` | 第 0 tick 有音乐切换副作用 |
| `public void initGui()` | GuiAchievements.java:63 / GuiStats.java:55 | 打开成就/统计屏 | **发包点**：`C16PacketClientStatus(REQUEST_STATS)`；可缓存避免重复请求 | 服务器不回包则永远停在加载动画（见陷阱） |
| `void doneLoading()` | IProgressMeter.java:7（impl: GuiAchievements.java:201, GuiStats.java:167） | NetHandlerPlayClient.java:1498 收到 S37 统计包 | 统计数据就绪通知；注入自己的 IProgressMeter 屏幕 | 仅当 `currentScreen instanceof IProgressMeter` 时才回调 |
| `public void displayAchievement(Achievement ach)` | GuiAchievement.java:32 | NetHandlerPlayClient.java:1473 收到新成就 | 替换/抑制成就弹窗（自定义 toast 系统） | 单槽位：新成就覆盖旧动画 |
| `public void updateAchievementWindow()` | GuiAchievement.java:71 | Minecraft.java:1163 每帧 | 常驻 HUD 弹窗渲染点（自带 ortho 投影，适合叠加自绘 HUD） | 会 `GlStateManager.clear(256)` 清深度并重设投影矩阵 |
| `public void drawEntry(int slotIndex, ...)` | ServerListEntryNormal.java:48 | 服务器列表每帧每行 | **ping 触发点**（首帧惰性提交线程池）；改延迟显示/图标逻辑 | 含跨线程写 `ServerData`；`version > 47` 硬编码协议号 |
| `public boolean mousePressed(int slotIndex, ...)` | ServerListEntryNormal.java:293 | 点击服务器行 | 拦截连接（`owner.connectToSelected()`）、迷你按钮区域 | relativeX≤32 区域是加入/移动按钮，勿误判 |
| `public void func_146615_e(int p_146615_1_)` | GuiSelectWorld.java:178 | 选择/双击世界 | **进入单人世界的唯一入口**：`launchIntegratedServer` | `field_146634_i` 防重入标志置位后不再复位（本屏生命周期内） |
| `public void confirmClicked(boolean result, int id)` | GuiSelectWorld.java:206 | 删除确认对话框回调 | 拦截世界删除（`deleteWorldDirectory`） | `id` 即世界下标，非按钮 id |
| `protected void actionPerformed(GuiButton button)` | GuiShareToLan.java:55 | LAN 屏按钮 | 拦截 `IntegratedServer.shareToLAN`（改端口/鉴权） | 成功后端口号通过聊天组件展示 |
| `protected void actionPerformed(GuiButton button)` | GuiScreenResourcePacks.java:110 | 资源包屏 Done/打开文件夹 | 拦截 `refreshResources()`（重载耗时提示）；文件夹打开逻辑含 `Runtime.exec` | macOS/Windows 分支直接 exec；JDK25 下 AWT Desktop 反射兜底可能 headless 失败 |
| `public void updateMapTexture(MapData mapdataIn)` | MapItemRenderer.java:30 | 收到地图数据包后 | 地图纹理更新钩子（小地图数据提取点） | 16384 像素全量重写，频繁更新有成本 |
| `public void renderMap(MapData mapdataIn, boolean p_148250_2_)` | MapItemRenderer.java:35 | 手持地图/展示框渲染 | 替换地图渲染（缩放、waypoint 叠加） | 调用方已设好模型矩阵，只画 [0,128]² |
| `public static List<IChatComponent> splitText(IChatComponent p_178908_0_, int p_178908_1_, FontRenderer p_178908_2_, boolean p_178908_3_, boolean p_178908_4_)` | GuiUtilRenderComponents.java:17 | 聊天 GUI 分行等 | 聊天渲染前的文本变换单点（关键词高亮须保 style） | 返回的组件是浅拷贝 style，改动会影响 hover/click 事件归属 |
| `protected void actionPerformed(GuiButton button)` | GuiYesNo.java:61 | 确认屏点击 | 所有 Yes/No 决策统一经 `confirmClicked(button.id == 0, parentButtonClickedId)` 回流 | 回调方随后通常要自己 `displayGuiScreen` 切走 |
| `protected void mouseDragged(Minecraft mc, int mouseX, int mouseY)` | GuiSlider.java:67 | 滑块拖动每帧 | 值变化实时回调 `responder.onTick`；可做吸附/步进 | 每帧回调，重逻辑要防抖 |
| `protected void mouseClicked(int mouseX, int mouseY, int mouseButton)` | GuiVideoSettings.java:85 | 视频设置屏点击 | guiScale 变化即时重建 `ScaledResolution` + `setWorldAndResolution` 的范例 | resize 会重跑 initGui，勿在 initGui 存易失状态 |
| `public static void func_152321_a(GuiScreen p_152321_0_)` | GuiStreamUnavailable.java:156 | 尝试打开推流功能时 | Twitch 可用性诊断分发器（移植版里几乎必然进错误分支） | 直接调用 `GLContext.getCapabilities()`（LWJGL2 API，经 shim） |

## 数据与协议

本 bucket 不定义封包类，但有 4 个**发包点**与 2 个**收包消费方**：

| 封包 | 方向 | 字段/取值 | 触发处 |
|---|---|---|---|
| `C18PacketSpectate` | C→S | 构造参数 `this.profile.getId()`（GameProfile UUID）：请求传送到该玩家 | PlayerMenuObject.java:27 |
| `C0BPacketEntityAction` | C→S | `C0BPacketEntityAction.Action.STOP_SLEEPING`，实体为 `this.mc.thePlayer` | GuiSleepMP.java:66 |
| `C16PacketClientStatus` | C→S | `EnumState.REQUEST_STATS`（请求统计） | GuiAchievements.java:65、GuiStats.java:59 |
| `C16PacketClientStatus` | C→S | `EnumState.PERFORM_RESPAWN`（结局后重生） | GuiWinGame.java:72 |
| `S37PacketStatistics`（间接） | S→C | 到达后 `NetHandlerPlayClient` 回调 `IProgressMeter.doneLoading()` | NetHandlerPlayClient.java:1498 |
| `S34PacketMaps`（间接） | S→C | 数据落进 `MapData` 后由 `MapItemRenderer.updateMapTexture` 消费 | MapItemRenderer.java:30 |

文件/数据格式：

| 数据 | 字段 | 类型 | 读写方法 | 含义 |
|---|---|---|---|---|
| 服务器图标 | `ServerData.getBase64EncodedIconData()` | String（base64 PNG） | 读：ServerListEntryNormal.java:157；解码 :253-254（Netty `Base64.decode`）；写回失败置 null :268 | 必须是 64x64 PNG（:261-262），成功后随 `saveServerList()` 持久化到 servers.dat |
| 地图颜色 | `MapData.colors` | `byte[16384]`（128x128） | 读：MapItemRenderer.java:93；`j / 4` 是 MapColor 索引、`j & 3` 是明暗档 | `j / 4 == 0` 画透明棋盘格（:97） |
| 地图装饰 | `MapData.mapDecorations` | `Map<String, Vec4b>` | 读：MapItemRenderer.java:130 | `func_176110_a()`=图标类型（4x4 图集索引）、`func_176112_b()/c()`=坐标（-128..127）、`func_176111_d()`=朝向（0-15） |
| ping 结果 | `ServerData.pingToServer / serverMOTD / populationInfo / version / gameVersion / playerList / field_78841_f` | long/String/int/boolean | 写：Server Pinger 线程（ServerListEntryNormal.java:56-75）+ OldServerPinger；读：drawEntry 每帧 | `pingToServer`：-2 进行中、-1 失败、≥0 毫秒延迟；`version != 47` 视为不兼容 |
| 世界存档元数据 | `SaveFormatComparator`（getFileName/getDisplayName/getLastTimePlayed/requiresConversion/getEnumGameType/isHardcoreModeEnabled/getCheatsEnabled） | — | 读：GuiSelectWorld.java:92,303-339 | 经 `ISaveFormat.getSaveList()` 从 level.dat 汇总 |
| 结局文本 | `texts/end.txt`、`texts/credits.txt` | 资源文件（UTF-8） | 读：GuiWinGame.java:99,126 | `PLAYERNAME` 占位符替换；`§f§k§a§b` 序列替换为乱码块 |

## 不变量与陷阱

- **initGui 会在窗口 resize 时重跑**，且 buttonList 已被清空。凡在 initGui 里做一次性工作的类都有守卫：`GuiScreenResourcePacks.changed`（:47）、`GuiWinGame.field_146582_i == null`（:90）、`GuiStreamUnavailable.field_152323_r.isEmpty()`（:54）。写新屏幕时忘记这点会导致 resize 后状态丢失或重复副作用（如重复发 REQUEST_STATS——GuiAchievements/GuiStats 正是每次 initGui 都重发，原版即如此）。
- **GuiSlot 不在 buttonList 里**：`handleMouseInput`、`drawScreen`、（GuiSelectWorld 等还有滚动按钮的）`actionPerformed` 都必须由宿主手动转发；漏转发的症状是列表能画不能点/不能滚。
- **GuiSlot 双击 = 250ms 内同一 index 二连击**（GuiSlot.java:353）；ServerListEntryNormal/LanDetected 又各自维护了自己的 250ms 计时（:319 / :43），两套机制并存。
- **Server Pinger 线程安全**：worker 线程直接写 `ServerData` 的 String/long 字段，主线程每帧读，无 volatile/锁。long 写在 64 位 JVM 上事实原子，但这是"能跑"不是"正确"；给这些字段加代理或搬到别的线程时要意识到这一点。
- **`ServerListEntryNormal` 硬编码协议版本 47**（:78-79），改协议兼容层时这里要同步。
- **统计屏依赖服务器回包**：`GuiAchievements`/`GuiStats` 在 `doneLoading()` 之前停在加载动画且 `doesGuiPauseGame()` 行为反直觉（GuiStats 的字段 `doesGuiPauseGame` 是"正在加载"标志，方法返回它的**取反**，GuiStats.java:181-184）。服务器不发 S37 就永远卡住，且 `GuiStats.displaySlot` 在 doneLoading 前为 null——`drawScreen` 的 else 分支（:161）此时会 NPE，靠"标志翻转前不可能走到 else"这一不变量保护。
- **GuiAchievement 自建投影矩阵**（GuiAchievement.java:50-68）并 `clear(256)` 清深度缓冲；在它之后叠加渲染的代码要重新设置自己的矩阵。
- **GuiScreenRealmsProxy 的 buttonList 是 synchronizedList**（:18），且 `drawScreen` 完全不调 `super.drawScreen`（:95-98）——按钮渲染由 RealmsScreen 自己负责；直接遍历它的 buttonList 做注入的通用代码要考虑锁与"按钮全是 GuiButtonRealmsProxy"这两个特例（`actionPerformed` :156 有硬强转）。
- **GuiTextField 的 validator 静默拒绝**（:159, :218）：setText/writeText 不通过校验时无任何反馈，调试输入"打不进去"先查 validator。
- **GL 状态**：`GuiSlot.drawScreen` 结束时 enableTexture2D/shadeModel(7424)/enableAlpha/disableBlend（:309-312）、`GuiTextField.drawCursorVertical` 用完 colorLogicOp 必须 `disableColorLogic`（:635）——在这些方法中途 return 的补丁容易漏还原状态。
- **LWJGL3/JDK25 移植相关**：
  - `org.lwjgl.input.Keyboard` / `org.lwjgl.input.Mouse` / `org.lwjgl.Sys` / `org.lwjgl.opengl.GLContext` 都来自本仓库的 `lwjgl2-shim`（`lwjgl2-shim/src/main/java/org/lwjgl/...`），不是真 LWJGL2。`GuiSlot.handleMouseInput` 依赖 shim 正确实现事件队列语义（`getEventButton/getEventButtonState/getEventDWheel` 必须与当前正在迭代的事件一致）；`Keyboard.enableRepeatEvents(true/false)`（GuiScreenServerList.java:34,50）需要 shim 支持按键重复开关。
  - `Mouse.getEventDWheel()` 的滚轮增量在 GLFW 下是 ±1 级别而 LWJGL2 是 ±120 级别；GuiSlot 只判正负（:411-422），GuiAchievements 的 `Mouse.getDWheel()`（:144）同样只判正负，因此这里天然兼容，但若 shim 返回累计值为 0 的空事件会导致滚动失灵。
  - `GuiScreenResourcePacks` 的"打开文件夹"与 `GuiStreamUnavailable.func_152320_a` 依赖反射 `java.awt.Desktop`（:151 / :146）——JDK25 无 SecurityManager 问题，但 headless 或未捆绑 AWT 的运行时会走 Throwable 分支；macOS 分支直接 `exec /usr/bin/open` 反而最稳。
  - `GuiWinGame` 使用已废弃的 `org.apache.commons.io.Charsets`（:19,100）；JDK 侧无碍，升级 commons-io 时注意。
  - Twitch 栈（`tv.twitch.*`）在本移植里只剩编译依赖，`GuiStreamUnavailable.func_152321_a` 的 `NullStream` 分支（:170）是实际运行路径。

## 交叉引用

- `net.minecraft.client` → `Minecraft#displayGuiScreen`（所有屏幕切换）、`Minecraft#launchIntegratedServer`（GuiSelectWorld.java:201）、`Minecraft#refreshResources`（GuiScreenResourcePacks.java:197）、`Minecraft#getSystemTime`（双击/动画计时全靠它）、`Minecraft#runGameLoop → guiAchievement.updateAchievementWindow`（Minecraft.java:1163）、`Minecraft#runTick → spectatorGui 三个输入口`（Minecraft.java:1842/1864/2076）
- `net.minecraft.client.gui`（bucket #1）→ `GuiScreen`（基类：buttonList/initGui/drawScreen 协议）、`GuiButton`/`GuiOptionButton`/`GuiOptionSlider`/`GuiOptionsRowList`、`GuiListExtended#IGuiListEntry`（三个 ServerListEntry 实现它）、`GuiMultiplayer#selectServer/connectToSelected/setHoveringText/func_175391_a/func_175393_b`（ServerListEntryNormal.java:299-321）、`GuiIngame#renderGameOverlay`（spectator/streamIndicator 渲染宿主）、`GuiChat`（GuiSleepMP 基类）、`GuiCreateWorld`/`GuiRenameWorld`/`GuiErrorScreen`（GuiSelectWorld 跳转）、`Gui#drawModalRectWithCustomSizedTexture/drawScaledCustomSizeModalRect/drawRect`（entry/spectator 绘制）
- `net.minecraft.client.network` → `NetHandlerPlayClient#addToSendQueue`（4 个发包点）、`NetHandlerPlayClient#handleStatistics → IProgressMeter#doneLoading`（NetHandlerPlayClient.java:1498）、`NetworkPlayerInfo#getGameProfile/getGameType`（TeleportToPlayer.java:27,43）、`LanServerDetector.LanServer#getServerMotd/getServerIpPort`（ServerListEntryLanDetected.java:24,32）
- `net.minecraft.client.settings` → `GameSettings#setOptionValue/getKeyBinding/saveOptions/setSoundLevel/getSoundLevel`（所有设置屏）、`GameSettings.Options`（ScreenChatOptions/GuiVideoSettings/GuiStreamOptions 的选项数组）、`GameSettings#keyBindsHotbar`（GuiSpectator.java:109）
- `net.minecraft.client.renderer` → `GlStateManager`/`Tessellator`/`WorldRenderer`/`DefaultVertexFormats`（几乎所有自绘）、`EntityRenderer#getMapItemRenderer`（EntityRenderer.java:2046）、`ItemRenderer`/`RenderItemFrame → MapItemRenderer#renderMap`、`RenderItem#renderItemAndEffectIntoGUI`（GuiAchievement.java:133、GuiAchievements.java:467）、`OpenGlHelper#vboSupported/framebufferSupported`（GuiVideoSettings.java:34、GuiStreamUnavailable.java:161）
- `net.minecraft.client.resources` → `I18n#format`（全部文案）、`ResourcePackRepository#setRepositories/updateRepositoryEntriesAll`（GuiScreenResourcePacks.java:52,182）、`ResourcePackListEntry*`、`DefaultPlayerSkin#getDefaultSkinLegacy`（TeleportToTeam.java:105）
- `net.minecraft.client.multiplayer` → `ServerData`（ping 字段读写）、`ServerList#countServers/getServerData/saveServerList`（ServerSelectionList.java:75-77、ServerListEntryNormal.java:161）
- `net.minecraft.stats` → `StatFileWriter#hasAchievementUnlocked/canUnlockAchievement/readStat/func_150874_c`、`AchievementList`、`StatList`（GuiAchievements/GuiStats 全部数据源）
- `net.minecraft.world.storage` → `ISaveFormat#getSaveList/deleteWorldDirectory/canLoadWorld`、`MapData#colors/mapDecorations`（GuiSelectWorld、MapItemRenderer）
- `net.minecraft.network.play.client` → `C18PacketSpectate`、`C0BPacketEntityAction`、`C16PacketClientStatus`
- `net.minecraft.realms` → `RealmsScreen`/`RealmsButton`/`RealmsScrolledSelectionList`/`RealmsSimpleScrolledSelectionList`（三个 Proxy 类的对端）
- `net.minecraft.server.integrated`（经 Minecraft）→ `IntegratedServer#shareToLAN/getPlayerUsageSnooper`（GuiShareToLan.java:90、GuiSnooper.java:59）
- `org.lwjgl`（lwjgl2-shim）→ `input.Mouse#getEventButton/getEventButtonState/isButtonDown/getEventDWheel/getDWheel`（GuiSlot.java:320-411、GuiAchievements.java:114,144、GuiStats.java:256）、`input.Keyboard#enableRepeatEvents`（GuiScreenServerList.java:34,50）、`Sys#openURL`（GuiScreenResourcePacks.java:164）、`opengl.GLContext#getCapabilities`（GuiStreamUnavailable.java:165-167）
- `tv.twitch` → `broadcast.IngestServer`、`chat.ChatUserInfo/ChatUserMode/ChatUserSubscription`（GuiIngestServers、GuiTwitchUserMode）

## 覆盖声明

完整读取了 45/45 个文件（每个文件从第 1 行读到末行）。

逐行精读并在文中给出行号级引用的类：GuiSlot、GuiTextField、ScaledResolution、GuiSelectWorld、ServerListEntryNormal、ServerSelectionList、GuiSpectator、SpectatorMenu、PlayerMenuObject、TeleportToPlayer、TeleportToTeam、GuiAchievement、GuiAchievements、GuiStats、GuiScreenRealmsProxy、MapItemRenderer、GuiUtilRenderComponents、GuiWinGame、GuiSleepMP、GuiShareToLan、GuiScreenResourcePacks、GuiScreenOptionsSounds、GuiStreamUnavailable、GuiSlider、GuiYesNo、GuiVideoSettings、GuiScreenServerList、GuiStreamIndicator。

读取全文但仅做结构性归纳（未逐条展开每个方法）的类：GuiScreenWorking、ScreenChatOptions、GuiSnooper、GuiSlotRealmsProxy、GuiSimpleScrolledSelectionListProxy、ServerListEntryLanDetected、ServerListEntryLanScan、GuiIngestServers、GuiStreamOptions、GuiTwitchUserMode、BaseSpectatorGroup、SpectatorDetails、以及五个接口（GuiYesNoCallback、IProgressMeter、ISpectatorMenuObject、ISpectatorMenuRecipient、ISpectatorMenuView）。

跨包调用关系（Minecraft、GuiIngame、NetHandlerPlayClient、EntityRenderer、ItemRenderer、RenderItemFrame、ResourcePackRepository、lwjgl2-shim）经 grep 逐条验证了行号；未通读这些外部文件的全文。
