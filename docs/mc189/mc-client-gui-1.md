---
area: net/minecraft/client/gui#1
slug: mc-client-gui-1
files: 52
lines: 13467
tier: A
---

# net/minecraft/client/gui #1 — GUI 基础框架、HUD 与主要屏幕

> 本 bucket 覆盖 `net.minecraft.client.gui` 包中按字母序的前 52 个文件（ChatLine ~ GuiScreenDemo）。
> 同包的其余文件（GuiSlot、GuiTextField、GuiSlider、GuiYesNo、GuiSelectWorld、ScaledResolution、
> GuiSpectator、GuiStreamIndicator、GuiUtilRenderComponents、ServerSelectionList 等）在 bucket #2，
> 本文引用它们时只作外部依赖对待。

## 定位

这一批文件是客户端 2D 界面的地基和大部分具体屏幕：

- **绘制原语层**：`Gui`（drawRect / drawTexturedModalRect / drawGradientRect 等静态与实例绘制方法）、
  `FontRenderer`（位图字体 + Unicode 分页字体渲染、§ 格式码解析、折行/测宽）。所有 2D 绘制最终落到
  `Tessellator`/`WorldRenderer`/`GlStateManager`（`net.minecraft.client.renderer`）。
- **屏幕框架层**：`GuiScreen`（抽象基类：按钮列表、输入分发、tooltip、聊天组件 click/hover 处理、剪贴板）、
  `GuiButton` 及其子类（GuiOptionButton / GuiOptionSlider / GuiListButton / GuiLockIconButton /
  GuiButtonLanguage / GuiButtonRealmsProxy）、`GuiLabel`、列表框架 `GuiListExtended` /
  `GuiPageButtonList` / `GuiOptionsRowList` / `GuiKeyBindingList` / `GuiResourcePackList`。
- **HUD 层（无 currentScreen 时也常驻）**：`GuiIngame`（准星、血量/饥饿/护甲/经验、计分板、标题、
  唱片提示、聊天框绘制入口）、`GuiNewChat` + `ChatLine`（聊天历史与滚动）、`GuiPlayerTabOverlay`
  （Tab 玩家列表）、`GuiOverlayDebug`（F3 调试面板）。
- **具体屏幕**：主菜单 `GuiMainMenu`、单人建图 `GuiCreateWorld`/`GuiCreateFlatWorld`/`GuiFlatPresets`/
  `GuiCustomizeWorldScreen`/`GuiScreenCustomizePresets`、多人 `GuiMultiplayer`/`GuiScreenAddServer`/
  `GuiDisconnected`/`GuiDownloadTerrain`、游戏内 `GuiChat`/`GuiIngameMenu`/`GuiGameOver`/`GuiScreenDemo`、
  容器类 `GuiEnchantment`/`GuiHopper`/`GuiMerchant`/`GuiRepair`（基类 GuiContainer 在
  `client/gui/inventory`）、编辑类 `GuiCommandBlock`/`GuiScreenBook`/`GuiRenameWorld`、
  设置类 `GuiOptions`/`GuiControls`/`GuiLanguage`/`GuiCustomizeSkin`，以及错误屏
  `GuiErrorScreen`/`GuiMemoryErrorScreen`、链接确认 `GuiConfirmOpenLink`。

**谁调用它**：`Minecraft.displayGuiScreen(GuiScreen)`（Minecraft.java:981）驱动屏幕切换；
`Minecraft.runTick()` 每 tick 调 `ingameGUI.updateTick()`（Minecraft.java:1747）、
`currentScreen.handleInput()`（:1791）与 `currentScreen.updateScreen()`（:1811）；
`EntityRenderer.updateCameraAndRender` 每帧调 `ingameGUI.renderGameOverlay(partialTicks)`
（EntityRenderer.java:1169）和 `currentScreen.drawScreen(k1, l1, partialTicks)`（:1191）。
`NetHandlerPlayClient` 在收包时直接调 HUD：`setRecordPlaying`（NetHandlerPlayClient.java:855）、
`getChatGUI().printChatMessage`（:859）、`displayTitle`（:1589）、`getTabList().setHeader/setFooter`
（:1602-1603）、`guichat.onAutocompleteResponse`（:1691）。

**它调用谁**：`net.minecraft.client.renderer`（Tessellator/GlStateManager/RenderHelper/RenderItem）、
`org.lwjgl.input.Keyboard/Mouse` 与 `org.lwjgl.opengl.Display/GL11`（本仓库由 `lwjgl2-shim` 模块提供，
底层是 LWJGL3）、`net.minecraft.network.play.client` 的多种 C 系封包、`GameSettings`/`KeyBinding`、
`I18n`、世界生成配置（FlatGeneratorInfo/ChunkProviderSettings）、存档接口 `ISaveFormat`。

**如果它消失**：客户端没有任何菜单、聊天、HUD、字体渲染——从主菜单到死亡界面全部不存在，
`Minecraft.displayGuiScreen` 无目标可显示，游戏不可交互。

## 类清单

| 类名 | 行数 | extends / implements | 一句话职责 |
|---|---|---|---|
| ChatLine | 37 | — | 一条已渲染聊天行：IChatComponent + 创建时的 updateCounter + 可删除 ID |
| FontRenderer | 996 | implements IResourceManagerReloadListener | 位图/Unicode 字体渲染、§ 格式码、测宽与折行 |
| Gui | 217 | — | 2D 绘制原语基类（drawRect、drawTexturedModalRect、drawGradientRect、zLevel） |
| GuiButton | 157 | extends Gui | 标准 200x20 按钮：绘制、hover 状态、mousePressed/Released、按键音 |
| GuiButtonLanguage | 33 | extends GuiButton | 主菜单 20x20 语言（地球）图标按钮 |
| GuiButtonRealmsProxy | 105 | extends GuiButton | 把 GuiButton 事件转发给 RealmsButton 的适配器 |
| GuiChat | 353 | extends GuiScreen | 聊天输入屏：历史、滚轮滚动、Tab 补全（C14PacketTabComplete） |
| GuiClickableScrolledSelectionListProxy | 117 | extends GuiSlot | 把 GuiSlot 回调转发给 RealmsClickableScrolledSelectionList |
| GuiCommandBlock | 189 | extends GuiScreen | 命令方块编辑屏，Done 时发 `MC\|AdvCdm` 自定义载荷 |
| GuiConfirmOpenLink | 77 | extends GuiYesNo | 打开外部链接确认屏（含"复制到剪贴板"按钮与安全警告） |
| GuiControls | 176 | extends GuiScreen | 按键绑定设置屏；buttonId 非空时下一次按键/点击即改绑 |
| GuiCreateFlatWorld | 260 | extends GuiScreen（内部类 Details extends GuiSlot） | 超平坦层级编辑屏，读写 FlatGeneratorInfo 字符串 |
| GuiCreateWorld | 553 | extends GuiScreen | 建立新世界：种子/游戏模式/世界类型，最终 launchIntegratedServer |
| GuiCustomizeSkin | 100 | extends GuiScreen（内部类 ButtonPart extends GuiButton） | 皮肤部件开关屏（EnumPlayerModelParts） |
| GuiCustomizeWorldScreen | 1005 | extends GuiScreen implements GuiSlider.FormatHelper, GuiPageButtonList.GuiResponder | CUSTOMIZED 世界类型的 4 页参数编辑器（ChunkProviderSettings.Factory） |
| GuiDisconnected | 74 | extends GuiScreen | 断线原因展示屏（折行显示 IChatComponent） |
| GuiDownloadTerrain | 65 | extends GuiScreen | "下载地形中"过场屏，每 20 tick 发 C00PacketKeepAlive |
| GuiEnchantment | 354 | extends GuiContainer | 附魔台 GUI：3D 书本模型、三个附魔选项、sendEnchantPacket |
| GuiErrorScreen | 53 | extends GuiScreen | 两行文字 + Cancel 的通用错误屏 |
| GuiFlatPresets | 281 | extends GuiScreen（内部类 ListSlot extends GuiSlot；静态类 LayerItem） | 超平坦预设列表 + 预设字符串编辑框 |
| GuiGameOver | 148 | extends GuiScreen implements GuiYesNoCallback | 死亡屏：respawn / 回主菜单，按钮延迟 20 tick 启用 |
| GuiHopper | 51 | extends GuiContainer | 漏斗容器 GUI（仅贴图和标题） |
| GuiIngame | 1206 | extends Gui | 游戏内 HUD 总渲染器 + 每 tick 状态（标题计时、物品高亮） |
| GuiIngameMenu | 114 | extends GuiScreen | Esc 菜单：返回游戏/选项/成就/统计/LAN/退出世界 |
| GuiKeyBindingList | 187 | extends GuiListExtended（内部类 CategoryEntry / KeyEntry implements IGuiListEntry） | 按键绑定滚动列表 |
| GuiLabel | 98 | extends Gui | 多行文本标签（可居中、可带背景框） |
| GuiLanguage | 165 | extends GuiScreen（内部类 List extends GuiSlot） | 语言选择屏，切换后 refreshResources 并调 fontRendererObj 的 unicode/bidi 标志 |
| GuiListButton | 58 | extends GuiButton | "标签: 是/否"开关按钮，点击回调 GuiResponder.func_175321_a |
| GuiListExtended | 95 | abstract, extends GuiSlot | 把 GuiSlot 槽位委托到 IGuiListEntry 的列表框架 |
| GuiLockIconButton | 97 | extends GuiButton（内部 enum Icon） | 难度锁定挂锁按钮（六态贴图） |
| GuiMainMenu | 627 | extends GuiScreen implements GuiYesNoCallback | 主菜单：全景天空盒、splash 文本、单人/多人/Realms 入口 |
| GuiMemoryErrorScreen | 59 | extends GuiScreen | OOM 提示屏（硬编码英文文案） |
| GuiMerchant | 274 | extends GuiContainer（内部类 MerchantButton extends GuiButton） | 村民交易 GUI，翻页时发 `MC\|TrSel` |
| GuiMultiplayer | 490 | extends GuiScreen implements GuiYesNoCallback | 服务器列表屏：ServerList 持久化、LAN 探测线程、OldServerPinger |
| GuiNewChat | 379 | extends Gui | 聊天缓冲区：chatLines/drawnChatLines 两级列表、滚动、按坐标取组件 |
| GuiOptionButton | 30 | extends GuiButton | 携带 GameSettings.Options 的选项按钮 |
| GuiOptionSlider | 94 | extends GuiButton | 绑定 GameSettings.Options 的滑条（normalize/denormalize） |
| GuiOptions | 241 | extends GuiScreen implements GuiYesNoCallback | 选项主屏：难度/锁定、各子设置屏入口、Super Secret Settings |
| GuiOptionsRowList | 138 | extends GuiListExtended（静态类 Row implements IGuiListEntry） | 每行两个选项控件的列表（视频设置等用） |
| GuiOverlayDebug | 284 | extends Gui | F3 调试信息（左右两栏）+ lagometer 帧图 |
| GuiPageButtonList | 628 | extends GuiListExtended（静态类 GuiEntry/GuiListEntry/GuiSlideEntry/GuiButtonEntry/EditBoxEntry/GuiLabelEntry、接口 GuiResponder） | 多页控件列表框架（世界自定义屏用），Tab 焦点循环、Ctrl+V 批量粘贴 |
| GuiPlayerTabOverlay | 399 | extends Gui（静态类 PlayerComparator implements Comparator&lt;NetworkPlayerInfo&gt;） | Tab 键玩家列表：皮肤头像、ping 图标、计分板值、header/footer |
| GuiRenameWorld | 110 | extends GuiScreen | 世界重命名屏（ISaveFormat.renameWorld） |
| GuiRepair | 226 | extends GuiContainer implements ICrafting | 铁砧 GUI：物品改名发 `MC\|ItemName`，监听槽位变化回填输入框 |
| GuiResourcePackAvailable | 19 | extends GuiResourcePackList | "可用资源包"列表（只提供表头文案） |
| GuiResourcePackList | 64 | abstract, extends GuiListExtended | 资源包列表共用框架（带表头、36px 槽高） |
| GuiResourcePackSelected | 19 | extends GuiResourcePackList | "已选资源包"列表（只提供表头文案） |
| GuiScreen | 795 | abstract, extends Gui implements GuiYesNoCallback | 屏幕基类：输入分发、按钮/标签列表、tooltip、聊天组件事件、剪贴板、背景绘制 |
| GuiScreenAddServer | 164 | extends GuiScreen | 添加/编辑服务器屏（IDN 校验、资源包模式轮换），结果经 parentScreen.confirmClicked 返回 |
| GuiScreenBook | 673 | extends GuiScreen（静态类 NextPageButton extends GuiButton） | 书与笔/成书阅读编辑屏，NBT pages 读写，`MC\|BEdit` / `MC\|BSign` |
| GuiScreenCustomizePresets | 236 | extends GuiScreen（静态类 Info；内部类 ListPreset extends GuiSlot） | 世界自定义 JSON 预设列表（静态块内置 7 个预设） |
| GuiScreenDemo | 97 | extends GuiScreen | Demo 模式帮助屏（购买链接 + 键位说明） |

## 核心类详解

### Gui（Gui.java, 217 行）

绘制原语。关键字段：`protected float zLevel`（Gui.java:15），三个静态贴图：
`optionsBackground` / `statIcons` / `icons`（Gui.java:12-14）。

关键方法（签名逐字）：

- `public static void drawRect(int left, int top, int right, int bottom, int color)`（Gui.java:50）——
  纯色矩形，内部 `GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)`，ARGB 颜色。
- `protected void drawGradientRect(int left, int top, int right, int bottom, int startColor, int endColor)`（Gui.java:90）——
  垂直渐变，用 `shadeModel(7425)` 平滑着色，使用 `this.zLevel`。
- `public void drawTexturedModalRect(int x, int y, int textureX, int textureY, int width, int height)`（Gui.java:138）——
  按 256x256 贴图（`f = 0.00390625F`）绘制，z 取 `zLevel`。另有 float 版（:155）与
  `TextureAtlasSprite` 版（:172）。
- `public static void drawModalRectWithCustomSizedTexture(int x, int y, float u, float v, int width, int height, float textureWidth, float textureHeight)`（Gui.java:187）。
- `public static void drawScaledCustomSizeModalRect(int x, int y, float u, float v, int uWidth, int vHeight, int width, int height, float tileWidth, float tileHeight)`（Gui.java:204）——
  GuiPlayerTabOverlay 用它画 8x8 皮肤头像（GuiPlayerTabOverlay.java:186）。
- `public void drawCenteredString(FontRenderer fontRendererIn, String text, int x, int y, int color)`（Gui.java:122）、
  `public void drawString(FontRenderer fontRendererIn, String text, int x, int y, int color)`（Gui.java:130）——
  都带阴影（内部调 drawStringWithShadow）。

### FontRenderer（FontRenderer.java, 996 行）

关键字段：`private int[] charWidth = new int[256]`（:32，default.png 每字符宽），
`public int FONT_HEIGHT = 9`（:35），`private byte[] glyphWidth = new byte[65536]`（:41，每 Unicode
字符起止列的高低 nibble），`private int[] colorCode = new int[32]`（:47，16 色 + 16 暗色阴影色），
`private float posX / posY`（:54-57），`private boolean unicodeFlag / bidiFlag`（:62-67），
样式标志 `randomStyle/boldStyle/italicStyle/underlineStyle/strikethroughStyle`（:85-101）。

构造：`public FontRenderer(GameSettings gameSettingsIn, ResourceLocation location, TextureManager textureManagerIn, boolean unicode)`（:103）——
在 `Minecraft` 启动时创建两个实例：`fontRendererObj`（ascii.png，Minecraft.java:507）与
`standardGalacticFontRenderer`（ascii_sga.png，Minecraft.java:515），均注册为资源重载监听器；
`onResourceManagerReload(IResourceManager resourceManager)`（:145）重读 `readFontTexture()`（:150，
从贴图像素推算 charWidth）；`readGlyphSizes()`（:210）读 `font/glyph_sizes.bin` 到 glyphWidth。

渲染路径：`public int drawString(String text, float x, float y, int color, boolean dropShadow)`（:341）
→ `private int renderString(String text, float x, float y, int color, boolean dropShadow)`（:569，
处理 bidi、alpha 缺省 `color |= -16777216`、阴影色 `(color & 16579836) >> 2`）→
`private void renderStringAtPos(String text, boolean shadow)`（:392，解析 `§` 后跟
`"0123456789abcdefklmnor"` 的格式码，:400）→ `private float renderChar(char ch, boolean italic)`（:232）
→ `renderDefaultChar`（:248）或 `renderUnicodeChar`（:290）。注意本移植版这两个方法使用
**立即模式** `GL11.glBegin(GL11.GL_TRIANGLE_STRIP)`（:256、:308），不是 Tessellator；
而删除线/下划线用 Tessellator（:520-545）。

度量与折行：`public int getStringWidth(String text)`（:607）、
`public int getCharWidth(char character)`（:658，`§` 返回 -1，空格返回 4），
`public String trimStringToWidth(String text, int width, boolean reverse)`（:708），
`public void drawSplitString(String str, int x, int y, int wrapWidth, int textColor)`（:786），
`public List<String> listFormattedStringToWidth(String str, int wrapWidth)`（:844），
`String wrapFormattedStringToWidth(String str, int wrapWidth)`（:852，递归插 `\n` 并用
`getFormatFromString` 继承格式码），`public static String getFormatFromString(String text)`（:958），
`public int getColorCode(char character)`（:992）。

### GuiScreen（GuiScreen.java, 795 行）

关键字段：`protected Minecraft mc`（:54）、`protected RenderItem itemRender`（:59）、
`public int width / height`（:62-65）、`protected List<GuiButton> buttonList`（:66）、
`protected List<GuiLabel> labelList`（:67）、`public boolean allowUserInput`（:68）、
`protected FontRenderer fontRendererObj`（:71）、`private GuiButton selectedButton`（:74）、
`private URI clickedLinkURI`（:82）。

生命周期与输入（签名逐字）：

- `public void setWorldAndResolution(Minecraft mc, int width, int height)`（:548）——
  注入 mc/itemRender/fontRendererObj、清空 buttonList 后调 `initGui()`。由
  `Minecraft.displayGuiScreen`（Minecraft.java:1011）和窗口 resize（`onResize`，:791）调用。
- `public void initGui()`（:575）——子类在此填 buttonList。
- `public void handleInput() throws IOException`（:582）——`while (Mouse.next())` →
  `handleMouseInput()`；`while (Keyboard.next())` → `handleKeyboardInput()`。每 tick 由
  Minecraft.runTick（Minecraft.java:1791）调用。
- `public void handleMouseInput() throws IOException`（:604）——把 `Mouse.getEventX/Y` 换算到
  scaled 坐标（`i = Mouse.getEventX() * this.width / this.mc.displayWidth`，:606），分派到
  `mouseClicked` / `mouseReleased` / `mouseClickMove`；含 touchscreen 的 touchValue 计数逻辑。
- `public void handleKeyboardInput() throws IOException`（:641）——按下事件调
  `this.keyTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey())`，随后总是
  `this.mc.dispatchKeypresses()`。
- `protected void keyTyped(char typedChar, int keyCode) throws IOException`（:104）——
  默认 keyCode==1（Esc）关屏并 `setIngameFocus()`。
- `protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException`（:499）——
  左键遍历 buttonList，命中则设 selectedButton、播音效、调 `actionPerformed(guibutton)`（:511）。
- `protected void mouseReleased(int mouseX, int mouseY, int state)`（:520）、
  `protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick)`（:533）、
  `protected void actionPerformed(GuiButton button) throws IOException`（:540）。
- `public void drawScreen(int mouseX, int mouseY, float partialTicks)`（:87）——画 buttonList 与
  labelList；子类通常先画自己再 `super.drawScreen(...)`。
- `public void updateScreen()`（:654）、`public void onGuiClosed()`（:661）、
  `public boolean doesGuiPauseGame()`（:708，默认 true）。

聊天组件与工具：

- `protected void handleComponentHover(IChatComponent component, int x, int y)`（:272）——
  处理 SHOW_ITEM（JsonToNBT 解析物品）、SHOW_ENTITY、SHOW_TEXT、SHOW_ACHIEVEMENT。
- `protected boolean handleComponentClick(IChatComponent component)`（:384）——OPEN_URL（协议白名单
  `PROTOCOLS = {"http","https"}`，:50；`chatLinksPrompt` 时弹 `GuiConfirmOpenLink(this, clickevent.getValue(), 31102009, false)`，:428）、
  OPEN_FILE、SUGGEST_COMMAND（`setText(value, true)`）、RUN_COMMAND（`sendChatMessage(value, false)`）、
  TWITCH_USER_INFO。
- `public void sendChatMessage(String msg, boolean addToChat)`（:486）——写入 sentMessages 后
  `this.mc.thePlayer.sendChatMessage(msg)`。**功能层拦聊天输出可挂这里**。
- `public static String getClipboardString()`（:120）/ `public static void setClipboardString(String copyText)`（:142）——
  走 `java.awt.Toolkit` 的系统剪贴板。
- `protected void drawHoveringText(List<String> textLines, int x, int y)`（:189）——tooltip 绘制，
  临时把 `zLevel` 和 `itemRender.zLevel` 抬到 300（:228-229）。
- 修饰键静态查询：`public static boolean isCtrlKeyDown()`（:744，`Minecraft.isRunningOnMac` 时用
  key 219/220 即 Cmd）、`isShiftKeyDown()`（:752）、`isAltKeyDown()`（:760）、
  `isKeyComboCtrlX/V/C/A(int keyID)`（:765-783）。
- 背景：`public void drawDefaultBackground()`（:668）→ `drawWorldBackground(0)`（:673，有世界画
  渐变，无世界画 `drawBackground(tint)` 泥土贴图，:688）。

### GuiButton（GuiButton.java, 157 行）

字段：`protected int width / height`、`public int xPosition / yPosition`、`public String displayString`、
`public int id`、`public boolean enabled / visible`、`protected boolean hovered`（:14-34）。
贴图 `buttonTextures = new ResourceLocation("textures/gui/widgets.png")`（:11）。

- `public void drawButton(Minecraft mc, int mouseX, int mouseY)`（:78）——hover 判定写入
  `this.hovered`（:85），按 `getHoverState` 选 46/66/86 行的两半贴图（:90-91），随后调
  `this.mouseDragged(mc, mouseX, mouseY)`（:92）；文字色 14737632（正常）/10526880（禁用）/
  16777120（hover）。
- `protected int getHoverState(boolean mouseOver)`（:59）——0 禁用 / 1 正常 / 2 hover。
- `public boolean mousePressed(Minecraft mc, int mouseX, int mouseY)`（:126）——命中检测
  `enabled && visible && 坐标在框内`。
- `public void playPressSound(SoundHandler soundHandlerIn)`（:143）——`gui.button.press`。

### GuiIngame（GuiIngame.java, 1206 行）

关键字段：`private final GuiNewChat persistantChatGUI`（:55）、`private int updateCounter`（:57）、
`private String recordPlaying` / `recordPlayingUpFor` / `recordIsPlaying`（:60-64）、
`private int remainingHighlightTicks` / `private ItemStack highlightingItemStack`（:70-73）、
`private final GuiOverlayDebug overlayDebug`（:74）、`private final GuiSpectator spectatorGui`（:77）、
`private final GuiPlayerTabOverlay overlayPlayerList`（:78）、标题字段
`titlesTimer/displayedTitle/displayedSubTitle/titleFadeIn/titleDisplayTime/titleFadeOut`（:81-96）、
血量闪烁用 `playerHealth/lastPlayerHealth/lastSystemTime/healthUpdateCounter`（:97-104）。

- 构造 `public GuiIngame(Minecraft mcIn)`（:106）由 `Minecraft`（Minecraft.java:569
  `this.ingameGUI = new GuiIngame(this)`）创建，同时 new 出 debug/spectator/chat/tab 子对象。
- `public void renderGameOverlay(float partialTicks)`（:128）——每帧由
  EntityRenderer.java:1169 调用。顺序：`setupOverlayRendering` → vignette（FancyGraphics 时，:138）
  → 南瓜头 overlay（:149）→ 传送门 overlay（:158）→ 热键栏（spectator 走 `spectatorGui.renderTooltip`，
  否则 `renderTooltip(scaledresolution, partialTicks)`，:162-169）→ 准星（`showCrosshair()`，:175-180）
  → `renderBossHealth()`（:184）→ `renderPlayerStats(scaledresolution)`（:189，
  仅 `shouldDrawHUD()`）→ 睡眠遮罩（:194）→ 马跳条/经验条（:217-224）→ 手持物品名
  `renderSelectedItem`（:228）/ demo 计时（:237）/ F3 `overlayDebug.renderDebugInfo`（:242）→
  唱片提示（:245-275）→ 标题与副标题（:277-316，缩放 4x/2x）→ 计分板 sidebar
  `renderScoreboard(scoreobjective1, scaledresolution)`（:336）→ 聊天：
  `GlStateManager.translate(0.0F, (float)(j - 48), 0.0F)` 后 `persistantChatGUI.drawChat(this.updateCounter)`
  （:343-345）→ Tab 列表 `overlayPlayerList.updatePlayerList(...)` / `renderPlayerlist(i, scoreboard, scoreobjective1)`（:350-358）。
- `public void updateTick()`（:1068）——每 tick 由 Minecraft.java:1747 调用：递减
  recordPlayingUpFor/titlesTimer、`++this.updateCounter`（:1086）、维护手持物品高亮
  `remainingHighlightTicks = 40`（:1106）。
- `public void displayTitle(String title, String subTitle, int timeFadeIn, int displayTime, int timeFadeOut)`（:1125）——
  由 NetHandlerPlayClient.java:1589（S45PacketTitle 处理）调用；全 null/负参重置，title 非空时
  `titlesTimer = titleFadeIn + titleDisplayTime + titleFadeOut`。
- `public void setRecordPlaying(String message, boolean isPlaying)`（:1118）与
  `public void setRecordPlaying(IChatComponent component, boolean isPlaying)`（:1166）——
  actionbar 文本入口（S02PacketChat type=2 走 NetHandlerPlayClient.java:855）。
- 访问器：`public GuiNewChat getChatGUI()`（:1174）、`public int getUpdateCounter()`（:1179）、
  `public FontRenderer getFontRenderer()`（:1184）、`public GuiSpectator getSpectatorGui()`（:1189）、
  `public GuiPlayerTabOverlay getTabList()`（:1194）、`public void resetPlayersOverlayFooterHeader()`（:1202）。
- `protected boolean showCrosshair()`（:513）——F3 展开时不画准星；spectator 只在指向实体或
  可开容器方块时画。

### GuiNewChat（GuiNewChat.java, 379 行）+ ChatLine

字段：`private final List<String> sentMessages`（:19）、`private final List<ChatLine> chatLines`（:20，
原始行）、`private final List<ChatLine> drawnChatLines`（:21，按当前宽度折行后的展示行）、
`private int scrollPos` / `private boolean isScrolled`（:22-23）。两个列表都截断到 100 条
（:162-175）。

- `public void drawChat(int updateCounter)`（:30）——`chatVisibility != HIDDEN` 时按
  `updateCounter - chatline.getUpdatedCounter() < 200`（约 10 秒）淡出绘制；聊天打开时全量显示
  并画滚动条（:93-109）。
- `public void printChatMessage(IChatComponent chatComponent)`（:126）→
  `public void printChatMessageWithOptionalDeletion(IChatComponent chatComponent, int chatLineId)`（:134）→
  `private void setChatLine(IChatComponent chatComponent, int chatLineId, int updateCounter, boolean displayOnly)`（:140，
  用 `GuiUtilRenderComponents.splitText` 按 `getChatWidth()/getChatScale()` 折行；chatLineId != 0
  先 `deleteChatLine(chatLineId)`）。**收包显示聊天的必经点**（NetHandlerPlayClient.java:859）。
- `public void refreshChat()`（:178）——chatWidth/scale 改变后由设置屏触发全量重折行。
- `public IChatComponent getChatComponent(int mouseX, int mouseY)`（:245）——传入
  **真实像素坐标**（GuiChat 传 `Mouse.getX(), Mouse.getY()`），内部自行除以 scaleFactor（:254-259），
  用于点击/悬停命中检测。
- `public void scroll(int amount)`（:222）、`public void resetScroll()`（:211）、
  `public boolean getChatOpen()`（:305，`this.mc.currentScreen instanceof GuiChat`）、
  `public void addToSentMessages(String message)`（:200，去重相邻重复）、
  `public static int calculateChatboxWidth(float scale)`（:361）/ `calculateChatboxHeight`（:368）、
  `public int getLineCount()`（:375，`getChatHeight() / 9`）。

`ChatLine`（ChatLine.java）：`getChatComponent()`（:23）、`getUpdatedCounter()`（:28）、
`getChatLineID()`（:33）。

### GuiChat（GuiChat.java, 353 行）

字段：`private int sentHistoryCursor = -1`（:27）、`private boolean waitingOnAutocomplete`（:29）、
`private List<String> foundPlayerNames`（:31）、`protected GuiTextField inputField`（:34）、
`private String defaultInputFieldText`（:39，`GuiChat(String defaultText)` 用于按 `/` 打开）。

- `public void initGui()`（:54）——`Keyboard.enableRepeatEvents(true)`，输入框
  `new GuiTextField(0, this.fontRendererObj, 4, this.height - 12, this.width - 4, 12)`，
  `setMaxStringLength(100)`、`setCanLoseFocus(false)`。
- `protected void keyTyped(char typedChar, int keyCode) throws IOException`（:87）——
  keyCode 15（Tab）→ `autocompletePlayerNames()`；1（Esc）关屏；28/156（回车）→
  `this.sendChatMessage(s)` 后关屏（:129-137）；200/208 上下历史 `getSentHistory(±1)`；
  201/209 PgUp/PgDn 整页滚动。
- `public void handleMouseInput() throws IOException`（:143）——`Mouse.getEventDWheel()` 滚一格
  1 行，未按 Shift 时 `i *= 7`。
- `protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException`（:172）——
  左键先试 `handleComponentClick(ichatcomponent)`（点击聊天里的链接/命令）。
- 补全：`private void sendAutocompleteRequest(String p_146405_1_, String p_146405_2_)`（:252）发
  `new C14PacketTabComplete(p_146405_1_, blockpos)`（:263，带当前注视方块坐标）；服务器回包后
  NetHandlerPlayClient.java:1691 调 `public void onAutocompleteResponse(String[] p_146406_1_)`（:315），
  取公共前缀或轮换候选。
- `public boolean doesGuiPauseGame()`（:349）返回 false。

### GuiOverlayDebug（GuiOverlayDebug.java, 284 行）

- `public void renderDebugInfo(ScaledResolution scaledResolutionIn)`（:40）——左栏
  `renderDebugInfoLeft()`（:61）+ 右栏 `renderDebugInfoRight(scaledRes)`（:81）；
  `this.mc.gameSettings.showLagometer` 时 `renderLagometer()`（:219）。
- `protected List<String> call()`（:102）——左栏内容：版本行 `"Minecraft 1.8.9 (" + this.mc.getVersion() + "/" + ClientBrandRetriever.getClientModName() + ")"`、
  fps 行 `this.mc.debug`、渲染/实体统计、XYZ/Block/Chunk/Facing/Biome/Light/Local Difficulty、
  Looking at；`isReducedDebug()`（:56）时缩减。
- `protected List<String> getDebugInfoRight()`（:171）——Java 版本、内存、CPU、
  `Display.getWidth()/getHeight()` 与 `GL11.glGetString(GL11.GL_VENDOR/GL_RENDERER/GL_VERSION)`
  （:177，经 lwjgl2-shim），以及注视方块的 blockstate 属性列表。

### GuiPlayerTabOverlay（GuiPlayerTabOverlay.java, 399 行）

- `public void updatePlayerList(boolean willBeRendered)`（:57）——记录 `lastTimeOpened`。
- `public void renderPlayerlist(int width, Scoreboard scoreboardIn, ScoreObjective scoreObjectiveIn)`（:70）——
  从 `this.mc.thePlayer.sendQueue.getPlayerInfoMap()` 取玩家（:72-73），
  `field_175252_a.sortedCopy`（PlayerComparator：spectator 靠后 → 队伍名 → 玩家名，:392-397），
  最多 80 人、每列 20 行分栏（:89-97）；在线模式（`getIsencrypted()` 或集成服务器，:99）画皮肤
  头像；`drawPing(...)`（:237，按 responseTime 分 5 档 + 未知）；HEARTS 型计分板走
  `drawScoreboardValues(...)`（:274）。
- `public void setFooter(IChatComponent footerIn)`（:370）/ `setHeader`（:375）/
  `resetFooterHeader()`（:380）——由 S47PacketPlayerListHeaderFooter 处理器
  （NetHandlerPlayClient.java:1602-1603）调用。

### 列表框架：GuiListExtended / GuiPageButtonList / GuiKeyBindingList / GuiOptionsRowList / GuiResourcePackList

`GuiListExtended`（GuiListExtended.java）把 GuiSlot 的槽位绘制与点击委托给
`public interface IGuiListEntry`（:85-94）：`drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected)`、
`mousePressed(...)`、`mouseReleased(...)`、`setSelected(...)`。
`public boolean mouseClicked(int mouseX, int mouseY, int mouseEvent)`（:41）命中后
`this.setEnabled(false)`（:56），`mouseReleased`（:65）再恢复——嵌套列表屏都遵循这个约定。
子类必须实现 `public abstract GuiListExtended.IGuiListEntry getListEntry(int index)`（:83）。

`GuiPageButtonList`（GuiPageButtonList.java）是多页表单框架：构造收
`GuiPageButtonList.GuiListEntry[]... p_i45536_8_`（:21），按声明生成
GuiSlider/GuiListButton/GuiTextField/GuiLabel（:206-247），控件按 id 存入
`IntHashMap<Gui> field_178073_v`（:14）供 `public Gui func_178061_c(int p_178061_1_)`（:125）查询。
翻页 `func_181156_c(int)`（:82）切换可见性（:130-163）；
`public void func_178062_a(char p_178062_1_, int p_178062_2_)`（:249）处理 Tab 焦点循环与
`GuiScreen.isKeyComboCtrlV` 的 `;` 分隔批量粘贴（:304-326）。回调接口
`public interface GuiResponder`（:583-590）：`void func_175321_a(int p_175321_1_, boolean p_175321_2_)`
（布尔项）、`void onTick(int id, float value)`（滑条）、
`void func_175319_a(int p_175319_1_, String p_175319_2_)`（文本框）。

`GuiKeyBindingList`：构造时 clone 并排序 `mcIn.gameSettings.keyBindings`（:23-25），按
`getKeyCategory()` 插入 CategoryEntry；KeyEntry.mousePressed 点"改绑"把
`GuiKeyBindingList.this.field_148191_k.buttonId = this.keybinding`（:162）交回 GuiControls，点
"重置"直接 `setOptionKeyBinding(this.keybinding, this.keybinding.getKeyCodeDefault())` 并
`KeyBinding.resetKeyBindingArrayAndHash()`（:167-168）。冲突键位显示红色（:150-153）。

`GuiOptionsRowList.Row.mousePressed`（:93）点击即改 `gameSettings.setOptionValue(...)`（:99），
无需经过所属屏幕的 actionPerformed。

`GuiResourcePackList`：`setHasListHeader(true, (int)((float)mcIn.fontRendererObj.FONT_HEIGHT * 1.5F))`
（:20），条目类型是外部的 `ResourcePackListEntry`。

### GuiMainMenu（GuiMainMenu.java, 627 行）

- 构造（:90）读 `texts/splashes.txt` 随机 splash（排除 hashCode 125780783 的一条，:119）；
  `!GLContext.getCapabilities().OpenGL20 && !OpenGlHelper.areShadersSupported()` 时设置旧 GL 警告
  （:148-153）。
- `public void initGui()`（:194）——`new DynamicTexture(256, 256)` 作全景 viewport 贴图（:196）、
  节日 splash（:201-212）、demo 与正式按钮（`addSingleplayerMultiplayerButtons`，:260；
  `addDemoButtons`，:270）、Options/Quit/语言按钮（:226-228）、Realms 通知子屏
  `this.field_183503_M = realmsbridge.getNotificationScreen(this)`（:246）。
- `protected void actionPerformed(GuiButton button) throws IOException`（:286）——id 0 Options、
  5 Language、1 `new GuiSelectWorld(this)`、2 `new GuiMultiplayer(this)`、14 Realms、4
  `this.mc.shutdown()`、11 `this.mc.launchIntegratedServer("Demo_World", "Demo_World", DemoWorldServer.demoWorldSettings)`、
  12 删 Demo 世界确认。
- 全景绘制：`private void drawPanorama(int p_73970_1_, int p_73970_2_, float p_73970_3_)`（:374，
  `Project.gluPerspective(120.0F, 1.0F, 0.05F, 10.0F)` 走 shim 的 GLU）→
  `private void rotateAndBlurSkybox(float p_73968_1_)`（:464，`GL11.glCopyTexSubImage2D` 累积模糊）
  → `private void renderSkybox(int p_73971_1_, int p_73971_2_, float p_73971_3_)`（:499，
  unbind 主 framebuffer、viewport 到 256x256 画 7 次模糊再画回主缓冲）。
- `public void updateScreen()`（:164）`++this.panoramaTimer`。

### GuiCreateWorld（GuiCreateWorld.java, 553 行）

- 字段：`private String gameMode = "survival"`（:20）、`private boolean hardCoreMode`（:39）、
  `private boolean alreadyGenerated`（:40，防双击建两次世界）、
  `public String chunkProviderSettingsJson = ""`（:54，被 GuiCreateFlatWorld/GuiCustomizeWorldScreen
  写回）、`private static final String[] disallowedFilenames`（:57，Windows 保留名）。
- `public static String getUncollidingSaveDirName(ISaveFormat saveLoader, String name)`（:177）——
  替换 `[\\./"]`、加 `_` 包裹保留名、重名加 `-`。
- `actionPerformed` id 0（:216-261）：解析种子（数字直接用，非数字取 `s.hashCode()`，:227-244），
  构造 `WorldSettings`，注意 `worldsettings.setWorldName(this.chunkProviderSettingsJson)`（:248，
  这里的 "WorldName" 实际承载生成器 JSON），最后
  `this.mc.launchIntegratedServer(this.saveDirName, this.worldNameField.getText().trim(), worldsettings)`（:260）。
  id 2 在 survival→hardcore→creative 循环；id 8 按世界类型进 `GuiCreateFlatWorld` 或
  `GuiCustomizeWorldScreen`（:351-361）。
- `private boolean canSelectCurWorldType()`（:369）——DEBUG_WORLD 仅按住 Shift 可选。
- `public void recreateFromExistingWorld(WorldInfo original)`（:531）——"重建世界"入口。

### GuiCustomizeWorldScreen（GuiCustomizeWorldScreen.java, 1005 行）

CUSTOMIZED 世界类型的参数编辑器。字段 `private ChunkProviderSettings.Factory field_175334_E`
（默认值基线，:43）与 `field_175336_F`（当前编辑值，:44）；`private GuiPageButtonList field_175349_r`
（:23）。`private void func_175325_f()`（:111）构建 4 页控件：第 1 页基础开关/滑条（id 148-164、
210）、第 2 页矿物分布（id 165-209）、第 3 页噪声滑条（id 100-115）、第 4 页同名噪声文本框
（id 132-147，`Predicate<String> field_175332_D` 校验非负 float，:35-42）。

作为 `GuiPageButtonList.GuiResponder` 的三个回调：
`public void func_175319_a(int p_175319_1_, String p_175319_2_)`（:144，文本框→clamp→同步滑条
`((GuiSlider)this.field_175349_r.func_178061_c(p_175319_1_ - 132 + 100)).func_175218_a(f1, false)`，:230）、
`public void func_175321_a(int p_175321_1_, boolean p_175321_2_)`（:340，开关）、
`public void onTick(int id, float value)`（:394，滑条→字段，id 100-115 同步回文本框，:711-719）。
作为 `GuiSlider.FormatHelper`：`public String getText(int id, String name, float value)`（:244），
id 162（fixedBiome）显示群系名（:322-336）。actionPerformed（:730）：300 写回
`this.field_175343_i.chunkProviderSettingsJson = this.field_175336_F.toString()`（:737）、301
Randomize、302/303 翻页、304 Defaults（带确认弹层 `field_175339_B`）、305
`new GuiScreenCustomizePresets(this)`。`func_175324_a(String)`（:132）用
`ChunkProviderSettings.Factory.jsonToFactory` 解析传入 JSON。

### 容器类四屏（GuiEnchantment / GuiMerchant / GuiRepair / GuiHopper）

- `GuiEnchantment`：`protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException`
  （:85）命中三个附魔条且 `this.container.enchantItem(this.mc.thePlayer, k)` 通过时
  `this.mc.playerController.sendEnchantPacket(this.container.windowId, k)`（:98）。
  `drawGuiContainerBackgroundLayer`（:106）用独立投影矩阵渲染 `MODEL_BOOK`（ModelBook 3D 书，
  `Project.gluPerspective(90.0F, 1.3333334F, 9.0F, 80.0F)`，:120）；附魔文字用
  `this.mc.standardGalacticFontRenderer`（:193）。`func_147068_g()`（:306）每 tick 更新书页开合
  动画状态。
- `GuiMerchant`：`actionPerformed`（:98）翻页后
  `((ContainerMerchant)this.inventorySlots).setCurrentRecipeIndex(this.selectedMerchantRecipe)` 并发
  `new C17PacketCustomPayload("MC|TrSel", packetbuffer)`（:131，payload 为 `writeInt(recipeIndex)`）。
- `GuiRepair implements ICrafting`：`private void renameItem()`（:136）发
  `new C17PacketCustomPayload("MC|ItemName", (new PacketBuffer(Unpooled.buffer())).writeString(s))`
  （:147）；`public void sendSlotContents(Container containerToSend, int slotInd, ItemStack stack)`
  （:200）在槽 0 变化时回填 nameField 并再次 renameItem。initGui 里
  `this.inventorySlots.onCraftGuiOpened(this)`（:53）注册自己为 ICrafting 监听者。
- `GuiHopper`：只有前景标题（:34）与背景贴图（:43），`ySize = 133`（:28）。

### GuiScreenBook（GuiScreenBook.java, 673 行）

构造（:69）从 `book.getTagCompound().getTagList("pages", 8)` **拷贝**页数据（:78-82）。
`private void sendBookToServer(boolean publish) throws IOException`（:160）：裁掉尾部空页，
把 pages 写回 ItemStack NBT；publish 时频道从 `"MC|BEdit"` 换成 `"MC|BSign"`（:188-192），写
`author`/`title` 字符串 tag，把每页文本包成 `ChatComponentText` 再
`IChatComponent.Serializer.componentToJson` 序列化（:196-202），并
`this.bookObj.setItem(Items.written_book)`（:204）；payload 是
`packetbuffer.writeItemStackToBuffer(this.bookObj)`（:208）。编辑限制：每页
`this.fontRendererObj.splitStringWidth(s1 + "" + EnumChatFormatting.BLACK + "_", 118)` ≤128 且长度
<256（:398-400），页数 <50（:269），标题 <16 字符（:362）。`handleComponentClick`（:544）额外处理
`ClickEvent.Action.CHANGE_PAGE`。已签名书按页缓存组件 `field_175386_A`（:58，
`GuiUtilRenderComponents.splitText(ichatcomponent, 116, this.fontRendererObj, true, true)`，:476）。

### GuiMultiplayer（GuiMultiplayer.java, 490 行）

`initGui`（:50）首次进入时 `this.savedServerList = new ServerList(this.mc); loadServerList()`（:58-59）、
启动 `LanServerDetector.ThreadLanServerFind`（:64-65，**独立线程**），构造
`ServerSelectionList`（bucket #2 类，:72）；resize 时只 `setDimensions`（:77）。
`updateScreen`（:107）轮询 `this.lanServerList.getWasUpdated()` 并
`this.oldServerPinger.pingPendingNetworks()`（:118）。`onGuiClosed`（:124）interrupt LAN 线程并
`clearPendingNetworks()`。连接入口 `public void connectToSelected()`（:382）与
`private void connectToServer(ServerData server)`（:397，`new GuiConnecting(this, this.mc, server)`）。
增删改通过 `public void confirmClicked(boolean result, int id)`（:199）按
deletingServer/directConnect/addingServer/editingServer 四个标志分派，改动后
`this.savedServerList.saveServerList()` 持久化。快捷键：F5（keyCode 63）刷新（:271），
Shift+上下移动排序（:279-347）。

### 其余小屏幕要点

- `GuiControls.mouseClicked`（:98）：`this.buttonId != null` 时把鼠标键当绑定
  `this.options.setOptionKeyBinding(this.buttonId, -100 + mouseButton)`；`keyTyped`（:127）Esc=解绑
  （keyCode 0）、`typedChar + 256` 处理 keyCode==0 的字符键。改绑后必须
  `KeyBinding.resetKeyBindingArrayAndHash()`（:104、:146）。
- `GuiLanguage.List.elementClicked`（:130）：切语言 → `this.mc.refreshResources()` →
  `fontRendererObj.setUnicodeFlag(...)` / `setBidiFlag(...)`（:136-137）→ `saveOptions()`。
- `GuiOptions.actionPerformed`（:134）：id<100 的 GuiOptionButton 直接
  `setOptionValue(gamesettings$options, 1)`；108 难度循环、109 难度锁定（经 GuiYesNo id=109）、
  8675309 "Super Secret Settings" 调 `this.mc.entityRenderer.activateNextShader()`（:164）。
- `GuiGameOver`：`updateScreen`（:135）计 20 tick 后才启用按钮；`confirmClicked`（:87）true 时
  `this.mc.theWorld.sendQuittingDisconnectingPacket(); this.mc.loadWorld((WorldClient)null);` 回主菜单。
- `GuiDownloadTerrain.updateScreen`（:38）：`++this.progress; if (this.progress % 20 == 0)` 发
  `new C00PacketKeepAlive()`（:44）防止登录期超时。
- `GuiCommandBlock.actionPerformed` id 0（:88-102）：PacketBuffer 依次
  `writeByte(this.localCommandBlock.func_145751_f())`（类型）、
  `this.localCommandBlock.func_145757_a(packetbuffer)`（定位数据）、`writeString(command)`、
  `writeBoolean(shouldTrackOutput)`，发 `"MC|AdvCdm"`。
- `GuiScreenAddServer`：serverIPField 的 `setValidator(this.field_181032_r)`（:81）用
  `IDN.toASCII` 校验主机名（:37）；确认/取消都走 `this.parentScreen.confirmClicked(...)`（:107/:113）。
- `GuiConfirmOpenLink.actionPerformed`（:42）：id 2 复制链接后**仍会**调
  `this.parentScreen.confirmClicked(button.id == 0, this.parentButtonClickedId)`（:49）。
- `GuiButtonRealmsProxy.mousePressed`（:56）调了**两次** `super.mousePressed`（:58、:63），命中时
  `realmsButton.clicked` 会触发一次——原版即如此。
- `GuiFlatPresets` / `GuiScreenCustomizePresets` 都在 `static {}` 块注册内置预设
  （GuiFlatPresets.java:183-193 共 8 个；GuiScreenCustomizePresets.java:139-162 共 7 个 JSON）。

## 时序与生命周期

**全部在主线程（客户端渲染/游戏循环线程）**。Netty EventLoop 不直接进入本包：
`NetHandlerPlayClient` 的包处理经 `PacketThreadUtil.checkThreadAndEnqueue` 已转到主线程后才调
`ingameGUI`/`GuiChat` 等（本包内没有任何锁；`GuiMainMenu.threadLock`（GuiMainMenu.java:61）是唯一
的 synchronized，用于 GL 警告文本布局字段）。

1. **启动**：`Minecraft.startGame` 里 `this.ingameGUI = new GuiIngame(this)`（Minecraft.java:569）、
   两个 FontRenderer 创建并注册资源重载（:507-517）。
2. **屏幕切换**：`Minecraft.displayGuiScreen(GuiScreen guiScreenIn)`（Minecraft.java:981）：
   旧屏 `onGuiClosed()` → null 且无世界 → 强制 GuiMainMenu；null 且玩家已死 → 强制 GuiGameOver →
   目标是 GuiMainMenu 时清聊天、关 F3（:997-1001）→ `setIngameNotInFocus()` →
   `setWorldAndResolution(this, i, j)`（清 buttonList 并 `initGui()`）；传 null 则
   `setIngameFocus()` 恢复抓鼠标。
3. **每 tick**（`Minecraft.runTick`）：`this.ingameGUI.updateTick()`（:1747）→ 若有 currentScreen：
   `this.currentScreen.handleInput()`（:1791，泵 Mouse/Keyboard 事件队列 → keyTyped/mouseClicked/
   mouseReleased/mouseClickMove）→ `this.currentScreen.updateScreen()`（:1811，各屏在此
   `updateCursorCounter()`、计时器自增、ping LAN 等）。
4. **每帧**（`EntityRenderer.updateCameraAndRender`）：有世界时
   `this.mc.ingameGUI.renderGameOverlay(partialTicks)`（EntityRenderer.java:1169）；有 currentScreen 时
   `this.mc.currentScreen.drawScreen(k1, l1, partialTicks)`（:1191）。HUD 内部顺序见上文
   GuiIngame.renderGameOverlay 分解。
5. **关闭**：切屏或关屏时旧屏 `onGuiClosed()` ——用输入框的屏都在此
   `Keyboard.enableRepeatEvents(false)`；GuiMultiplayer 在此 interrupt LAN 线程；GuiChat 在此
   `resetScroll()`。
6. **窗口 resize**：`GuiScreen.onResize(Minecraft mcIn, int w, int h)`（GuiScreen.java:791）→
   重新 `setWorldAndResolution` → `initGui()` 重建控件（所以控件状态必须能从字段重建，如
   GuiMultiplayer 用 `initialized` 标志保住 ServerSelectionList）。

唯一的非主线程参与者：`GuiMultiplayer` 启动的 `LanServerDetector.ThreadLanServerFind`（后台线程写
`lanServerList`，主线程在 updateScreen 里用 `getWasUpdated()` 轮询消费）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void renderGameOverlay(float partialTicks)` | GuiIngame.java:128 | 每帧，EntityRenderer.java:1169，有世界时 | 接管/追加整个 HUD：自绘模块、隐藏原版元素、ClickGUI 叠加 | 内部大量改 GlStateManager 状态；末尾已恢复 color/alpha，插入点要自己保存/恢复状态 |
| `public void updateTick()` | GuiIngame.java:1068 | 每 tick，Minecraft.java:1747 | HUD 级每 tick 逻辑（计时、通知队列） | 与渲染共享字段（updateCounter 等），只在主线程动 |
| `public void drawScreen(int mouseX, int mouseY, float partialTicks)` | GuiScreen.java:87 | 每帧，EntityRenderer.java:1191（有 currentScreen 时） | 覆写任意屏幕外观；在 super 前后插自定义层 | mouseX/mouseY 是 ScaledResolution 坐标，不是像素 |
| `public void initGui()` | GuiScreen.java:575 | displayGuiScreen 与每次 resize（setWorldAndResolution:548 清空 buttonList 后） | 注入/移除按钮，改布局 | 会被 resize 重复调用，勿在此做一次性副作用（GuiMultiplayer 用 initialized 标志规避） |
| `public void handleInput() throws IOException` / `handleMouseInput()` / `handleKeyboardInput()` | GuiScreen.java:582 / 604 / 641 | 每 tick，Minecraft.java:1791 | 全局拦截 GUI 输入（键盘宏、鼠标手势） | handleKeyboardInput 末尾固定调 `mc.dispatchKeypresses()`（:648），拦截时别丢 |
| `protected void keyTyped(char typedChar, int keyCode) throws IOException` | GuiScreen.java:104 | handleKeyboardInput 按下事件 | 每屏按键钩子；默认 Esc 关屏 | keyCode 是 LWJGL 扫描码（28/156=回车，15=Tab，1=Esc） |
| `protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException` | GuiScreen.java:499 | handleMouseInput 按下事件 | 拦截点击、命中自定义控件 | 命中按钮即触发 actionPerformed（:511），在 super 之前拦可阻止 |
| `protected void actionPerformed(GuiButton button) throws IOException` | GuiScreen.java:540 | mouseClicked 命中按钮后 | 统一的按钮语义入口（各屏 switch(button.id)） | 部分屏由 keyTyped 直接调（回车触发 id 0），不只来自鼠标 |
| `public void updateScreen()` | GuiScreen.java:654 | 每 tick，Minecraft.java:1811 | 屏内每 tick 逻辑 | GuiDownloadTerrain 在此发 KeepAlive——覆写别断它 |
| `public void onGuiClosed()` | GuiScreen.java:661 | displayGuiScreen 换屏时（Minecraft.java:985） | 观察 GUI 关闭、清理资源 | 忘记调 super 会漏掉 `Keyboard.enableRepeatEvents(false)` 等清理 |
| `public boolean doesGuiPauseGame()` | GuiScreen.java:708 | Minecraft 判断单人是否暂停 | 让自定义屏不暂停游戏（返回 false） | 默认 true |
| `public void sendChatMessage(String msg, boolean addToChat)` | GuiScreen.java:486 | GuiChat 回车、RUN_COMMAND 点击事件 | **出站聊天/命令统一拦截点**（.命令前缀、过滤） | 真正发包在 `thePlayer.sendChatMessage`；此处拦不到其他代码直接调玩家的路径 |
| `protected boolean handleComponentClick(IChatComponent component)` | GuiScreen.java:384 | 点击聊天/书本组件 | 拦 URL/命令点击；扩展自定义 ClickEvent | OPEN_URL 有 http/https 白名单（:420）；GuiScreenBook 覆写加了 CHANGE_PAGE（GuiScreenBook.java:544） |
| `public void printChatMessageWithOptionalDeletion(IChatComponent chatComponent, int chatLineId)` | GuiNewChat.java:134 | 收到 S02PacketChat（NetHandlerPlayClient.java:859）及本地提示 | **入站聊天统一拦截/改写点**；自定义通知也从这里注入 | 会 `logger.info("[CHAT] ...")`（:137）；折行依赖 gameSettings.chatWidth/chatScale |
| `public void drawChat(int updateCounter)` | GuiNewChat.java:30 | 每帧，GuiIngame.java:345（已 translate 到 (2, j-48)） | 替换聊天渲染（动画、无限历史） | 坐标系已被 translate/scale，直接画屏幕坐标会错位 |
| `protected void keyTyped(char typedChar, int keyCode) throws IOException` | GuiChat.java:87 | 聊天屏按键 | 命令补全、语法高亮触发、历史扩展 | 回车分支 trim 后为空不发送；Esc/回车都会关屏 |
| `public void onAutocompleteResponse(String[] p_146406_1_)` | GuiChat.java:315 | S3APacketTabComplete → NetHandlerPlayClient.java:1691 | 自定义补全 UI/来源 | 仅 `waitingOnAutocomplete` 为 true 时生效 |
| `public void displayTitle(String title, String subTitle, int timeFadeIn, int displayTime, int timeFadeOut)` | GuiIngame.java:1125 | S45PacketTitle → NetHandlerPlayClient.java:1584-1589 | 观察/抑制服务器标题；本地弹标题 | 参数为 null/-1 的组合有清除与只改时序两种语义（见 :1127-1163） |
| `public void setRecordPlaying(String message, boolean isPlaying)` | GuiIngame.java:1118 | actionbar（S02 type=2，NetHandlerPlayClient.java:855）、唱片、上马提示 | actionbar 文本统一入口 | recordPlayingUpFor 固定 60 tick |
| `public void renderPlayerlist(int width, Scoreboard scoreboardIn, ScoreObjective scoreObjectiveIn)` | GuiPlayerTabOverlay.java:70 | 按住 keyBindPlayerList 时每帧（GuiIngame.java:357） | 自定义 Tab 列表（排序、ping 数字化） | 数据来自 `thePlayer.sendQueue.getPlayerInfoMap()`；上限 80 人 |
| `public void renderDebugInfo(ScaledResolution scaledResolutionIn)` / `protected List<String> call()` / `getDebugInfoRight()` | GuiOverlayDebug.java:40 / 102 / 171 | F3 打开时每帧（GuiIngame.java:242） | 增删调试行（call/getDebugInfoRight 返回 List&lt;String&gt;，最易覆写） | reducedDebugInfo 分支要一并处理 |
| `public void drawButton(Minecraft mc, int mouseX, int mouseY)` | GuiButton.java:78 | 每帧 GuiScreen.drawScreen 遍历 buttonList | 换按钮皮肤（圆角、动画） | 内部还负责 hovered 更新与 mouseDragged 转发，完全替换时别丢这两个 |
| `public boolean mousePressed(Minecraft mc, int mouseX, int mouseY)` | GuiButton.java:126 | GuiScreen.mouseClicked 遍历 | 改命中区域/禁用逻辑 | GuiListButton/GuiOptionSlider 覆写后有副作用（翻转值/开始拖动） |
| `public int drawString(String text, float x, float y, int color, boolean dropShadow)` | FontRenderer.java:341 | 所有文本绘制最终入口 | 全局字体替换/描边/自定义格式码 | 立即模式 glBegin 渲染（:256）；改渲染路径时注意 shadow 两遍绘制（:349-350） |
| `public int getStringWidth(String text)` | FontRenderer.java:607 | 布局测量（几乎所有屏） | 与自定义字体保持宽度一致 | 与渲染不一致会导致所有居中/折行错位 |
| `protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException` | GuiEnchantment.java:85 | 附魔屏点击 | 观察/自动化附魔选择 | 客户端先 `container.enchantItem` 预校验再发包（:96-98） |
| `private void sendBookToServer(boolean publish) throws IOException` | GuiScreenBook.java:160 | Done/Finalize 按钮、标题回车 | 拦截/改写书内容上行 | private；外部只能从 actionPerformed（:217）层面拦 |
| `protected void actionPerformed(GuiButton button) throws IOException`（id 0 分支） | GuiCreateWorld.java:208 | 点"创建新的世界" | 拦截世界创建参数 | `alreadyGenerated` 防重入；种子 hashCode 回退逻辑在 :227-244 |
| `public void connectToSelected()` / `private void connectToServer(ServerData server)` | GuiMultiplayer.java:382 / 397 | 选服双击/按钮 | 拦截出站连接（代理、协议切换） | connectToServer 是 private，公有面是 connectToSelected |
| `public void confirmClicked(boolean result, int id)` | GuiScreen.java:713（各屏覆写：GuiMultiplayer.java:199、GuiGameOver.java:87、GuiMainMenu.java:342、GuiOptions.java:118） | GuiYesNo 子流程回调 | 劫持确认类流程 | id 是各屏自定义整数（31102009=链接确认），跨屏不唯一 |

## 数据与协议

本包不定义封包，但直接**发送**以下封包/自定义载荷：

| 封包 / 频道 | 发送点 | 字段（写入顺序） | 含义 |
|---|---|---|---|
| `C14PacketTabComplete` | GuiChat.java:263 `this.mc.thePlayer.sendQueue.addToSendQueue(new C14PacketTabComplete(p_146405_1_, blockpos))` | `String`（光标前全文）、`BlockPos`（可空，当前注视方块） | 请求 Tab 补全；回包走 `onAutocompleteResponse(String[])`（GuiChat.java:315） |
| `C00PacketKeepAlive` | GuiDownloadTerrain.java:44 | 无参构造 | 地形下载期间每 20 tick 保活 |
| `C17PacketCustomPayload "MC\|AdvCdm"` | GuiCommandBlock.java:95 | `writeByte(localCommandBlock.func_145751_f())`；`localCommandBlock.func_145757_a(packetbuffer)`（类型相关定位数据）；`writeString(commandTextField.getText())`；`writeBoolean(shouldTrackOutput())` | 提交命令方块命令与 trackOutput 开关 |
| `C17PacketCustomPayload "MC\|TrSel"` | GuiMerchant.java:131 | `writeInt(this.selectedMerchantRecipe)` | 通知服务器当前选中的交易配方索引 |
| `C17PacketCustomPayload "MC\|ItemName"` | GuiRepair.java:147 | `writeString(s)`（新名字，空串=清除自定义名） | 铁砧改名 |
| `C17PacketCustomPayload "MC\|BEdit"` / `"MC\|BSign"` | GuiScreenBook.java:209 | `writeItemStackToBuffer(this.bookObj)`（整个书 ItemStack 含 NBT） | 保存草稿 / 签名出版（BSign 前已写 author/title 并把 pages 转 JSON、item 换成 `Items.written_book`） |
| （方法调用）`sendEnchantPacket(int, int)` | GuiEnchantment.java:98 `this.mc.playerController.sendEnchantPacket(this.container.windowId, k)` | windowId、按钮索引 0-2 | 底层是 C11PacketEnchantItem（在 PlayerControllerMP 中） |

NBT / 文件格式：

| 结构 | 位置 | 字段 | 说明 |
|---|---|---|---|
| 书 NBT | GuiScreenBook.java:75-97、:180-204 | `pages`: NBTTagList of NBTTagString（未签名=纯文本，签名后=IChatComponent JSON）；`author`: String；`title`: String（≤15 字符输入 + trim） | tag type 8 = String；页数上限 50，每页 splitStringWidth ≤128、长度 <256 |
| `font/glyph_sizes.bin` | FontRenderer.java:210-227 | 65536 字节，每字节高 nibble=起始列、低 nibble=结束列 | Unicode 字符宽 = `(k - j) / 2 + 1`（FontRenderer.java:688） |
| 超平坦预设字符串 | GuiCreateFlatWorld.java:46、GuiFlatPresets.java:180 | `FlatGeneratorInfo.createFlatGeneratorFromString` / `toString()` 往返 | 存在 `GuiCreateWorld.chunkProviderSettingsJson` |
| 自定义世界 JSON | GuiCustomizeWorldScreen.java:132-142、:737 | `ChunkProviderSettings.Factory.jsonToFactory` / `toString()` | 字段即第 1-4 页所有参数（seaLevel、coordinateScale、各矿 size/count/minHeight/maxHeight 等） |
| `servers.dat`（间接） | GuiMultiplayer.java:59、:210 等 | 经 `ServerList.loadServerList()/saveServerList()` | 本包只调接口，格式在 ServerList |
| `texts/splashes.txt` | GuiMainMenu.java:100-124 | UTF-8 按行 | 随机 splash，排除 hashCode==125780783 的行 |

## 不变量与陷阱

- **坐标系有三套**：GuiScreen 的 mouseX/mouseY 是 ScaledResolution 坐标；
  `GuiNewChat.getChatComponent(int mouseX, int mouseY)`（GuiNewChat.java:245）却要**真实像素**
  （GuiChat.java:176 传 `Mouse.getX(), Mouse.getY()`）；聊天绘制发生在
  `translate(0, j-48)` + `translate(2, 20)` + `scale(chatScale)` 之后（GuiIngame.java:343、
  GuiNewChat.java:50-51）。混用必错位。
- **initGui 会被 resize 反复调用**且调用前 buttonList 已被 `setWorldAndResolution` 清空
  （GuiScreen.java:555-556）。持久状态放字段，不放控件；GuiCommandBlock/GuiDisconnected 等还在
  initGui 里自行 `this.buttonList.clear()`（冗余但无害）。
- **`Keyboard.enableRepeatEvents(true)` 必须在 onGuiClosed 关掉**，否则游戏内按住键会重复触发。
  所有带输入框的屏都遵守；自定义屏忘记就是全局副作用。
- **FontRenderer 非线程安全且有可变绘制状态**（posX/posY/样式标志/颜色），`drawString` 前会
  `resetStyles()`（FontRenderer.java:344），但任何并发调用都会串状态。只能主线程用。
- **FontRenderer.renderDefaultChar/renderUnicodeChar 用 GL11.glBegin 立即模式**
  （FontRenderer.java:256、:308）——本移植版依赖 lwjgl2-shim + 兼容性 profile；升级到 core profile
  时这是第一个崩的地方。
- **getStringWidth 与实际渲染宽必须一致**：粗体每字符 +1（FontRenderer.java:645-648），
  `§` 宽 -1 触发跳过下一字符（:623-641）。替换字体只改渲染不改测量会毁掉所有布局。
- `renderString` 中 red/blue/green 的赋值顺序是 **r→red, g→blue, b→green**
  （FontRenderer.java:592-594，字段名与内容错位是原版反编译的既有事实），
  `§r` 重置时 `GlStateManager.color(this.red, this.blue, this.green, this.alpha)`（:451）
  恰好把错位抵消回去。改字段名或"修 bug"会改变颜色行为。
- **Gui.drawRect 会翻转参数并关闭贴图**（Gui.java:52-64、:73），调用后贴图态已被恢复
  （enableTexture2D，:82），但 blend 被关（:83）；drawGradientRect 则要求调用方处于 alpha 启用
  状态、内部临时 disableAlpha（:102/:115）。混画贴图与色块时按源码顺序恢复状态。
- **zLevel 语义**：tooltip 绘制把 `this.zLevel` 与 `this.itemRender.zLevel` 抬到 300 再归零
  （GuiScreen.java:228-229、:256-257）；GuiPlayerTabOverlay.drawPing 用 `zLevel += 100` 保证 ping
  图标盖住头像（GuiPlayerTabOverlay.java:269-271）。自绘时不还原 zLevel 会影响后续所有
  drawTexturedModalRect。
- **GuiListExtended 的 setEnabled(false)/true 配对**（GuiListExtended.java:56、:76）：entry 的
  mousePressed 返回 true 会禁用列表滚动直到 mouseReleased；自定义 entry 吞掉 release 会让列表
  卡死在禁用态。
- **GuiScreen.mouseClicked 在遍历中直接调 actionPerformed**（GuiScreen.java:503-513），
  actionPerformed 里 displayGuiScreen 换屏会让循环继续跑在旧 buttonList 上——原版按钮习惯上
  互斥命中所以没事，注入重叠按钮时注意。
- **剪贴板与打开链接走 java.awt**（GuiScreen.java:124、:731-733 反射 `java.awt.Desktop`）。
  JDK 25 + macOS 下 AWT 与 GLFW 主线程的兼容性（`-XstartOnFirstThread` 场景）是移植敏感点；
  shim 若没接管 `org.lwjgl.input.Keyboard/Mouse` 的事件队列语义（`next()/getEventKey()` 等），
  整个 GUI 输入都会失效。
- **isCtrlKeyDown 在 mac 上是 Cmd**（键码 219/220，GuiScreen.java:746），依赖
  `Minecraft.isRunningOnMac`。
- **GuiMainMenu.renderSkybox 会解绑主 framebuffer 并改 viewport**（GuiMainMenu.java:501-512），
  异常路径中断会让整帧渲染错乱；它每帧做 7 次 `rotateAndBlurSkybox`（各一次
  `glCopyTexSubImage2D`），是主菜单性能热点。
- **GuiChat 的补全是异步的**：`sendAutocompleteRequest` 设 `waitingOnAutocomplete = true`
  （GuiChat.java:264），任何后续按键都会清掉它（:89）；响应晚到会被静默丢弃。
- **GuiCreateWorld 的 `worldsettings.setWorldName(this.chunkProviderSettingsJson)`**
  （GuiCreateWorld.java:248）——WorldSettings 的这个"name"字段实为生成器选项通道，勿按字面理解。
- **GuiMultiplayer 持有后台线程**：忘记走 onGuiClosed（例如直接替换 `mc.currentScreen` 字段而不经
  displayGuiScreen）会泄漏 `ThreadLanServerFind` 与 pinger 连接。
- **displayGuiScreen 的强制重定向**：传 null 时若无世界必得 GuiMainMenu、玩家死亡必得 GuiGameOver
  （Minecraft.java:988-996）；想"无屏"必须先满足这两个条件。切到 GuiMainMenu 会清空全部聊天
  （:1000）。
- `GuiScreenBook` 构造时**复制** pages（GuiScreenBook.java:82），编辑不影响原 ItemStack，直到
  sendBookToServer 写回；`bookIsModified` 为 false 时 Done 不发包（:162）。
- `GuiLanguage.List.drawSlot` 临时强开 bidi 再还原（GuiLanguage.java:160-162）——在列表中途抛异常
  会把全局 FontRenderer 留在 bidi 态。

## 交叉引用

- `net.minecraft.client` → `Minecraft#displayGuiScreen`（屏幕栈唯一入口，Minecraft.java:981）、
  `Minecraft#runTick`（tick 驱动，:1747/:1791/:1811）、`Minecraft#launchIntegratedServer`
  （GuiCreateWorld.java:260、GuiMainMenu.java:320）、`Minecraft#shutdown`（GuiMainMenu.java:315、
  GuiMemoryErrorScreen.java:30）、`Minecraft#setIngameFocus` / `setIngameNotInFocus`、
  `Minecraft#dispatchKeypresses`（GuiScreen.java:648）、`Minecraft#refreshResources`
  （GuiLanguage.java:135）。
- `net.minecraft.client.renderer` → `EntityRenderer#updateCameraAndRender`（调 renderGameOverlay:1169
  与 drawScreen:1191）、`GlStateManager`/`Tessellator`/`WorldRenderer`/`RenderHelper`（全部绘制）、
  `entity.RenderItem`（物品图标：GuiScreen.itemRender、GuiIngame.renderHotbarItem）、
  `EntityRenderer#activateNextShader`（GuiOptions.java:164）、`texture.TextureManager#bindTexture`。
- `net.minecraft.client.network` → `NetHandlerPlayClient#addToSendQueue`（GuiChat.java:263、
  GuiCommandBlock.java:95、GuiMerchant.java:131、GuiRepair.java:147、GuiScreenBook.java:209、
  GuiDownloadTerrain.java:44）、`NetworkPlayerInfo`（GuiPlayerTabOverlay 数据源）、
  `OldServerPinger` / `LanServerDetector`（GuiMultiplayer）。
- `net.minecraft.network.play.client` → `C14PacketTabComplete`、`C00PacketKeepAlive`、
  `C17PacketCustomPayload`；`net.minecraft.network` → `PacketBuffer`。
- `net.minecraft.client.settings` → `GameSettings#setOptionValue/getKeyBinding/setOptionKeyBinding/
  saveOptions`（GuiOptions/GuiControls/GuiOptionSlider/GuiOptionsRowList）、
  `KeyBinding#resetKeyBindingArrayAndHash`（GuiControls.java:86/:104/:146、GuiKeyBindingList.java:168）。
- `net.minecraft.client.resources` → `I18n#format`（全包文案）、`LanguageManager`（GuiLanguage）、
  `IResourceManagerReloadListener`（FontRenderer）、`ResourcePackListEntry`（GuiResourcePackList）。
- `net.minecraft.client.multiplayer` → `ServerList` / `ServerData` / `GuiConnecting`
  （GuiMultiplayer.java:399）、`WorldClient`（GuiGameOver.java:92、GuiIngameMenu.java:57 的
  `loadWorld((WorldClient)null)`）。
- `net.minecraft.client.gui.inventory` → `GuiContainer`（GuiEnchantment/GuiHopper/GuiMerchant/
  GuiRepair 的基类，含 xSize/ySize/guiLeft/guiTop/isPointInRegion）。
- `net.minecraft.world.gen` → `FlatGeneratorInfo` / `FlatLayerInfo`（GuiCreateFlatWorld、
  GuiFlatPresets）、`ChunkProviderSettings.Factory#jsonToFactory/toString`
  （GuiCustomizeWorldScreen.java:136、GuiScreenCustomizePresets.java:141）。
- `net.minecraft.world.storage` → `ISaveFormat#getWorldInfo/renameWorld/deleteWorldDirectory`、
  `WorldInfo`（GuiCreateWorld/GuiRenameWorld/GuiMainMenu）。
- `net.minecraft.scoreboard` → `Scoreboard/ScoreObjective/Score/ScorePlayerTeam`
  （GuiIngame#renderScoreboard、GuiPlayerTabOverlay#drawScoreboardValues）。
- `net.minecraft.command.server` → `CommandBlockLogic#getCommand/setTrackOutput/func_145751_f/
  func_145757_a`（GuiCommandBlock）。
- `net.minecraft.inventory` → `ContainerEnchantment#enchantItem`、`ContainerMerchant#setCurrentRecipeIndex`、
  `ContainerRepair#updateItemName`、`ICrafting`（GuiRepair 实现）。
- `net.minecraft.realms` → `RealmsButton`（GuiButtonRealmsProxy）、
  `RealmsClickableScrolledSelectionList` / `Tezzelator`（GuiClickableScrolledSelectionListProxy）、
  `RealmsBridge#switchToRealms/getNotificationScreen`（GuiMainMenu/GuiIngameMenu）。
- `org.lwjgl`（lwjgl2-shim 提供）→ `input.Keyboard#next/getEventKey/getEventCharacter/
  enableRepeatEvents/isKeyDown`、`input.Mouse#next/getEventX/getEventY/getEventDWheel/getX/getY`、
  `opengl.Display#getWidth/getHeight`（GuiOverlayDebug.java:177）、`opengl.GLContext#getCapabilities`
  （GuiMainMenu.java:148）、`util.glu.Project#gluPerspective`（GuiMainMenu.java:381、
  GuiEnchantment.java:120）、`opengl.GL11`（FontRenderer 立即模式、GuiMainMenu 天空盒）。
- `com.ibm.icu` → `ArabicShaping` / `Bidi`（FontRenderer#bidiReorder，FontRenderer.java:363-375）。
- 同包 bucket #2 → `GuiSlot`（所有列表的真正滚动实现）、`GuiTextField`（所有输入框）、
  `GuiSlider`、`GuiYesNo`/`GuiYesNoCallback`、`ScaledResolution`、`GuiUtilRenderComponents#splitText/
  func_178909_a`（GuiNewChat.java:148/:278、GuiScreenBook.java:476）、`GuiSpectator`、
  `GuiStreamIndicator`、`ServerSelectionList`/`ServerListEntryNormal`/`ServerListEntryLanDetected`/
  `ServerListEntryLanScan`（GuiMultiplayer）、`GuiSelectWorld`、`GuiVideoSettings`、
  `GuiScreenResourcePacks`、`ScreenChatOptions`、`GuiSnooper`、`GuiScreenOptionsSounds`、
  `GuiShareToLan`、`GuiScreenServerList`、`GuiSleepMP`。

## 覆盖声明

- 完整读取了 **52/52** 个文件（每个文件从第 1 行读到最后一行，无抽样）。
- 逐行精读并核对行号的类：FontRenderer、Gui、GuiScreen、GuiButton、GuiIngame、GuiNewChat、
  GuiChat、GuiPlayerTabOverlay、GuiOverlayDebug、GuiListExtended、GuiPageButtonList、
  GuiCreateWorld、GuiCustomizeWorldScreen、GuiMultiplayer、GuiScreenBook、GuiMainMenu、
  GuiCommandBlock、GuiMerchant、GuiRepair、GuiEnchantment、GuiControls、GuiKeyBindingList、
  GuiOptions、GuiDownloadTerrain。
- 完整读取但只做结构性归纳（未逐字段展开）的类：ChatLine、GuiButtonLanguage、
  GuiButtonRealmsProxy、GuiClickableScrolledSelectionListProxy、GuiConfirmOpenLink、
  GuiCreateFlatWorld、GuiCustomizeSkin、GuiDisconnected、GuiErrorScreen、GuiFlatPresets、
  GuiGameOver、GuiHopper、GuiIngameMenu、GuiLabel、GuiLanguage、GuiListButton、GuiLockIconButton、
  GuiMemoryErrorScreen、GuiOptionButton、GuiOptionSlider、GuiOptionsRowList、GuiRenameWorld、
  GuiResourcePackAvailable、GuiResourcePackList、GuiResourcePackSelected、GuiScreenAddServer、
  GuiScreenCustomizePresets、GuiScreenDemo。
- 另外核对了包外调用点：Minecraft.java（:507/:515/:569/:981-1019/:1747/:1791/:1811/:2110-2115）、
  EntityRenderer.java（:1169/:1191）、NetHandlerPlayClient.java（:855/:859/:1584-1589/:1602-1603/
  :1691），以及 lwjgl2-shim 提供的 `org.lwjgl.input`/`org.lwjgl.opengl`/`org.lwjgl.util.glu` 类清单。
- 未读取（属其他 bucket，仅作依赖引用）：GuiSlot、GuiTextField、GuiSlider、GuiYesNo、
  ScaledResolution、GuiUtilRenderComponents、GuiContainer、ServerSelectionList 等。
