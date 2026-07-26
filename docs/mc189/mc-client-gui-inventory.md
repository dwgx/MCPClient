---
area: net/minecraft/client/gui/inventory
slug: mc-client-gui-inventory
files: 12
lines: 2866
tier: A
---

# net/minecraft/client/gui/inventory

## 定位

本包是所有"容器类 GUI"（带物品槽位的界面）的客户端实现层。核心是抽象基类 `GuiContainer`（extends `GuiScreen`），它把 `net.minecraft.inventory.Container` 的槽位模型渲染到屏幕上，并把鼠标/键盘操作翻译成 `PlayerControllerMP.windowClick(...)` 调用（最终发出 `C0EPacketClickWindow`）。其余类是各种具体容器界面：玩家背包、创造模式背包、箱子、熔炉、酿造台、信标、发射器、工作台、马匹背包，外加一个不含槽位的告示牌编辑屏 `GuiEditSign` 和创造模式专用的 `ICrafting` 监听器 `CreativeCrafting`。

谁调用它：
- `EntityPlayerSP` 在收到服务端 `S2DPacketOpenWindow` 等开窗封包后，通过 `displayGUIChest` (`EntityPlayerSP.java:606`)、`displayGUIHorse` (`:640`)、`openEditSign` (`:580`) 等方法 `mc.displayGuiScreen(new GuiXxx(...))` 打开对应界面（`EntityPlayerSP.java:582,612,620,624,628,632,636,642,651`）。
- `Minecraft.java:2096` 在按下背包键时打开 `new GuiInventory(this.thePlayer)`。
- `Minecraft` 主循环每帧调 `drawScreen`，每 tick 调 `updateScreen`（经由 `GuiScreen` 的调度链）。

它调用谁：
- `PlayerControllerMP#windowClick / sendSlotPacket / sendPacketDropItem`（发包）。
- `NetHandlerPlayClient#addToSendQueue`（`GuiBeacon` 的 `MC|Beacon` 自定义载荷、`GuiEditSign` 的 `C12PacketUpdateSign`）。
- `RenderItem`（`itemRender`）、`GlStateManager`、`RenderHelper`、`FontRenderer` 做渲染；`GuiInventory.drawEntityOnScreen` 走 `RenderManager` 渲染实体。
- `Container` 的静态工具（`canAddItemToSlot`、`computeStackSize`、`func_94534_d`、`getDragEvent`）。

如果它消失：所有带槽位的界面无法显示，玩家无法移动物品、无法使用箱子/熔炉/合成/创造背包，告示牌无法编辑——客户端与服务端的容器交互协议（窗口点击封包）就没有发起方了。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| CreativeCrafting | 47 | implements ICrafting | 创造模式下监听玩家 inventoryContainer 的槽位变化，把变更用 `sendSlotPacket` 同步给服务端 |
| GuiBeacon | 321 | extends GuiContainer | 信标界面：药水效果选择按钮，确认时发 `MC|Beacon` 自定义载荷；含 Button/CancelButton/ConfirmButton/PowerButton 四个内部按钮类 |
| GuiBrewingStand | 93 | extends GuiContainer | 酿造台界面：根据 `getField(0)`（brewTime）画进度箭头和气泡动画 |
| GuiChest | 54 | extends GuiContainer | 箱子/大箱子界面：按行数动态计算 `ySize`，分两段贴图 |
| GuiContainer | 766 | extends GuiScreen（abstract） | 所有容器 GUI 的基类：槽位渲染、鼠标点击/拖拽分堆/双击归拢/触屏拖动、热键、窗口点击发包 |
| GuiContainerCreative | 1009 | extends InventoryEffectRenderer | 创造模式背包：页签切换、滚动、搜索、删除槽；内部 ContainerCreative（虚拟 45 槽容器）与 CreativeSlot（委托槽包装） |
| GuiCrafting | 45 | extends GuiContainer | 工作台 3x3 合成界面，纯贴图与标题 |
| GuiDispenser | 47 | extends GuiContainer | 发射器/投掷器 3x3 界面，纯贴图与标题 |
| GuiEditSign | 179 | extends GuiScreen | 告示牌编辑屏：键盘编辑 4 行文本，关闭时发 `C12PacketUpdateSign`；实时用 TESR 渲染牌子 |
| GuiFurnace | 74 | extends GuiContainer | 熔炉界面：由 `getField(0..3)` 画燃烧火焰与烧炼进度箭头 |
| GuiInventory | 151 | extends InventoryEffectRenderer | 生存模式玩家背包：2x2 合成格 + 玩家模型预览；提供静态 `drawEntityOnScreen` |
| GuiScreenHorseInventory | 80 | extends GuiContainer | 马匹背包：按 `isChested()`/`canWearArmor()` 条件贴图，渲染马模型 |

注：`InventoryEffectRenderer`（`GuiInventory`/`GuiContainerCreative` 的父类）在 `net.minecraft.client.renderer` 包（`client/src/main/java/net/minecraft/client/renderer/InventoryEffectRenderer.java:10`），本身 extends GuiContainer，不在本 bucket 内。

## 核心类详解

### GuiContainer（GuiContainer.java，766 行）

整个包的骨架。关键字段：

- `protected static final ResourceLocation inventoryBackground`（`:25`）— `"textures/gui/container/inventory.png"`，也被 `GuiBeacon.PowerButton` 借用画药水图标（`GuiBeacon.java:304`）。
- `protected int xSize = 176; protected int ySize = 166;`（`:28,:31`）— 窗口像素尺寸，子类构造器里改。
- `public Container inventorySlots;`（`:34`）— 当前容器模型，public，功能层可直接读。
- `protected int guiLeft; protected int guiTop;`（`:39,:44`）— `initGui` 中按 `(width - xSize) / 2` 居中计算（`:92-93`）。
- `private Slot theSlot;`（`:47`）— 当前悬停槽位，每帧在 `drawScreen` 里重算（`:115` 置 null，`:128` 赋值）。private，外部想拿悬停槽只能反射或改源码。
- 触屏拖动状态：`clickedSlot / isRightMouseClick / draggedStack / returningStack / returningStackDestSlot / returningStackTime`（`:50-63`）。
- 拖拽分堆状态：`protected final Set<Slot> dragSplittingSlots`（`:66`）、`protected boolean dragSplitting`（`:67`）、`dragSplittingLimit / dragSplittingButton / dragSplittingRemnant`（`:68-71`）。
- 双击归拢状态：`lastClickTime / lastClickSlot / lastClickButton / doubleClick / shiftClickedSlot`（`:72-76`）。

关键方法（签名逐字）：

- `public void initGui()`（`:88`）— `super.initGui()` 后执行 `this.mc.thePlayer.openContainer = this.inventorySlots;`（`:91`），这是客户端"当前打开容器"指针的唯一常规写入点，再算 `guiLeft/guiTop`。
- `public void drawScreen(int mouseX, int mouseY, float partialTicks)`（`:99`）— 每帧调用。顺序：`drawDefaultBackground` → `drawGuiContainerBackgroundLayer`（`:104`）→ `super.drawScreen`（按钮/文本框，`:109`）→ 平移到 `(guiLeft, guiTop)` 后遍历 `inventorySlots.inventorySlots` 逐槽 `drawSlot(slot)` 并检测悬停高亮（`:121-139`）→ `drawGuiContainerForegroundLayer`（`:142`）→ 画手上拿着的 `draggedStack`/`getItemStack()`（`:144-170`）→ 触屏返回动画（`:172-187`）→ pop 矩阵后 `renderToolTip(itemstack1, mouseX, mouseY)`（`:194`）。注意 tooltip 在矩阵还原之后画，坐标是屏幕绝对坐标。
- `private void drawItemStack(ItemStack stack, int x, int y, String altText)`（`:205`）— zLevel 抬到 200 画跟随鼠标的物品。
- `protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)`（`:219`）— 空实现；子类画标题文字。调用时矩阵已平移到 guiLeft/guiTop，所以子类里的坐标是窗口相对坐标。
- `protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);`（`:226`）— 唯一 abstract 方法；调用时矩阵未平移，子类须自己用 `(width - xSize) / 2` 或 `guiLeft`。
- `private void drawSlot(Slot slotIn)`（`:228`）— 单槽渲染：空槽画 `slot.getSlotTexture()`（盔甲占位图，走 `TextureMap.locationBlocksTexture` 图集，`:280-291`）；拖拽分堆时预览堆叠数（`:243-273`）。
- `private void updateDragSplitting()`（`:309`）— 用 `Container.computeStackSize` 重算 `dragSplittingRemnant`。
- `private Slot getSlotAtPosition(int x, int y)`（`:341`）— 线性扫描所有槽位命中检测。
- `protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException`（`:359`）— 判定双击（同槽同键 250ms 内，`:365`）、窗口外点击（slotId = -999，`:380-383`）、touchscreen 分支、shift-click（`Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54)`，`:416`）、开始拖拽分堆（`:436-451`）。pick-block 鼠标键号为 `keyBindPickBlock.getKeyCode() + 100`（`:362`）。
- `protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick)`（`:466`）— 触屏拖动投放（500ms 延迟一次，`:488`）；非触屏时把经过的合法槽位加入 `dragSplittingSlots`（`:505-509`）。
- `protected void mouseReleased(int mouseX, int mouseY, int state)`（`:515`）— 松键结算：双击归拢（clickType 6 或 shift 双击遍历同 inventory 槽位逐个 shift 移动，`:533-555`）、拖拽分堆三段协议（start/add/end，用 `Container.func_94534_d(0|1|2, this.dragSplittingLimit)` 编码按钮值，`:615-625`）、普通落下。
- `protected boolean isPointInRegion(int left, int top, int right, int bottom, int pointX, int pointY)`(`:666`) — 注意参数名有误导：第 3、4 参实际是宽和高；判定含 1 像素外扩。
- `protected void handleMouseClick(Slot slotIn, int slotId, int clickedButton, int clickType)`（`:678`）— **所有槽位操作的唯一出口**：`this.mc.playerController.windowClick(this.inventorySlots.windowId, slotId, clickedButton, clickType, this.mc.thePlayer);`（`:685`），后者本地执行 `slotClick` 并发 `C0EPacketClickWindow`（`PlayerControllerMP.java:534-538`）。
- `protected void keyTyped(char typedChar, int keyCode) throws IOException`（`:692`）— ESC（keyCode 1）或背包键调 `this.mc.thePlayer.closeScreen()`（`:694-697`）；然后 `checkHotbarKeys`；悬停槽上支持 pick-block（clickType 3）和丢弃键（clickType 4，Ctrl 丢整组，`:707-710`）。
- `protected boolean checkHotbarKeys(int keyCode)`（`:718`）— 数字键 1-9 悬停交换（clickType 2，button = 热键槽序号）。
- `public void onGuiClosed()`（`:738`）— `this.inventorySlots.onContainerClosed(this.mc.thePlayer);`（`:742`）。注意：**这里不发关窗封包**；`C0DPacketCloseWindow` 由 `EntityPlayerSP.closeScreen()` 发。
- `public boolean doesGuiPauseGame()`（`:749`）— 返回 `false`：打开容器不暂停单机游戏。
- `public void updateScreen()`（`:757`）— 每 tick：玩家死亡则 `closeScreen()`（`:761-764`）。

clickType 语义（本包内实际使用值）：0 普通点击；1 shift 点击；2 热键交换；3 pick-block 复制；4 丢弃（slotId -999 表示丢到窗口外）；5 拖拽分堆（配合 `func_94534_d` 编码的 button）；6 双击归拢。

### GuiContainerCreative（GuiContainerCreative.java，1009 行）

创造模式背包，不与服务端窗口同步，直接用 `C10PacketCreativeInventoryAction` 覆写槽位。关键字段：

- `private static InventoryBasic field_147060_v = new InventoryBasic("tmp", true, 45);`（`:39`）— 静态 45 槽虚拟陈列柜，5 行 x 9 列。
- `private static int selectedTabIndex = CreativeTabs.tabBlock.getTabIndex();`（`:42`）— 静态，跨开关记住页签。
- `private float currentScroll; private boolean isScrolling; private boolean wasClicking;`（`:45-53`）。
- `private GuiTextField searchField;`（`:54`）— 搜索框，仅 `tabAllSearch` 页签可见。
- `private Slot field_147064_C;`（`:56`）— 删除槽（destroy/bin slot）。
- `private CreativeCrafting field_147059_E;`（`:58`）— 注册到 `inventoryContainer` 的 ICrafting 监听器。

关键方法：

- 构造 `public GuiContainerCreative(EntityPlayer p_i1088_1_)`（`:60`）— `super(new GuiContainerCreative.ContainerCreative(p_i1088_1_))`，`ySize = 136; xSize = 195;`。
- `public void updateScreen()`（`:72`）— 每 tick 检查 `isInCreativeMode()`，否则切回 `new GuiInventory(...)`（与 `GuiInventory.updateScreen` (`GuiInventory.java:34-42`) 互为镜像，游戏模式切换时两个界面互相顶替）。
- `protected void handleMouseClick(Slot slotIn, int slotId, int clickedButton, int clickType)`（`:85`）— **完全覆写基类发包路径**：窗口外丢弃直接 `dropPlayerItemWithRandomChoice` + `sendPacketDropItem`（`:99-100`）；shift 点删除槽清空整个背包（逐槽 `sendSlotPacket((ItemStack)null, j)`，`:117-123`）；背包页签走 `this.mc.thePlayer.inventoryContainer.slotClick(...)` + `detectAndSendChanges()`（`:144-145`）；陈列槽（`slotIn.inventory == field_147060_v`）本地无限取物（`:148-228`）；其余情况本地 `slotClick` 后用 `sendSlotPacket` 把结果槽覆写到服务端（`:231-244`）。
- `public void initGui()`（`:263`）— `Keyboard.enableRepeatEvents(true)`；建搜索框；重设页签；`this.mc.thePlayer.inventoryContainer.onCraftGuiOpened(this.field_147059_E);`（`:279`）。
- `public void onGuiClosed()`（`:290`）— `removeCraftingFromCrafters(this.field_147059_E)`（`:296`）、`Keyboard.enableRepeatEvents(false)`。
- `protected void keyTyped(char typedChar, int keyCode) throws IOException`（`:306`）— 非搜索页按聊天键跳到搜索页；搜索页把键入喂给 `searchField.textboxKeyTyped` 并 `updateCreativeSearch()`。
- `private void updateCreativeSearch()`（`:341`）— 遍历 `Item.itemRegistry` + `Enchantment.enchantmentsBookList` 重建 `itemList`，按 tooltip 文本小写包含过滤（`:362-383`）。
- `private void setCurrentCreativeTab(CreativeTabs p_147050_1_)`（`:456`）— 切页签；`tabInventory` 页签时把 `inventorySlots`（列表）整体换成包装过的 `CreativeSlot` 列表并重排坐标（盔甲槽 2x2、隐藏 crafting 槽移到 -2000，`:474-513`），加删除槽（`:512-513`）。
- `public void handleMouseInput() throws IOException`（`:546`）— `Mouse.getEventDWheel()` 滚轮翻页，`scrollTo(this.currentScroll)`。
- `public void drawScreen(int mouseX, int mouseY, float partialTicks)`（`:574`）— 用 `Mouse.isButtonDown(0)` 手动实现滚动条拖动（不走事件），再画页签悬浮提示与删除槽提示。
- `protected void renderToolTip(ItemStack stack, int x, int y)`（`:622`）— 搜索页 tooltip 附加所属页签名。
- `protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY)`（`:676`）— 画全部页签、当前页背景 `"textures/gui/container/creative_inventory/tab_" + creativetabs.getBackgroundImageName()`（`:692`）、滚动条、背包页签时画玩家模型（`:710`）。
- `protected boolean func_147049_a(CreativeTabs p_147049_1_, int p_147049_2_, int p_147049_3_)`（`:714`）— 页签命中检测；`protected void func_147051_a(CreativeTabs p_147051_1_)`（`:780`）— 画单个页签。
- `public int getSelectedTabIndex()`（`:847`）。

内部类 `static class ContainerCreative extends Container`（`:852`）：`public List<ItemStack> itemList`（`:854`）为当前页签全部物品；`public void scrollTo(float p_148329_1_)`（`:881`）把 `itemList` 的一个 5x9 窗口拷进 `field_147060_v`；`public boolean canInteractWith(EntityPlayer playerIn)` 恒真（`:876`）；`transferStackInSlot`（`:918`）对 hotbar 槽 shift 点击 = 清空该槽；`canMergeSlot`（`:933`）/`canDragIntoSlot`（`:938`）用 `yDisplayPosition > 90` 区分陈列区与玩家区。

内部类 `class CreativeSlot extends Slot`（`:944`）：持有 `private final Slot slot;`，把 `getStack/putStack/onSlotChanged/...` 全部委托给真实槽——背包页签下显示坐标与真实 `inventoryContainer` 槽解耦的手段。

### GuiBeacon（GuiBeacon.java，321 行）

- 构造（`:32`）：`super(new ContainerBeacon(playerInventory, tileBeaconIn))`，`xSize = 230; ySize = 219;`。
- `public void updateScreen()`（`:56`）— 每 tick 读 `tileBeacon.getField(0/1/2)`（levels/primary/secondary）；首次拿到 `i >= 0` 时按 `TileEntityBeacon.effectsList` 动态生成 `PowerButton`（id 编码 `level << 8 | potionId`，`:75`）；确认键可用条件 `this.tileBeacon.getStackInSlot(0) != null && j > 0`（`:125`）。字段值来自服务端 `S31PacketWindowProperty` 更新的容器字段，所以按钮要等一 tick 以上才出现（`buttonsNotDrawn` 门闩，`:63`）。
- `protected void actionPerformed(GuiButton button) throws IOException`（`:131`）— id -2 取消关屏；id -1 确认：写 `PacketBuffer`（两个 int：field 1、field 2）发 `new C17PacketCustomPayload("MC|Beacon", packetbuffer)`（`:139-143`）后关屏；`PowerButton` 点击只改本地 `tileBeacon.setField(1|2, k)` 并重建按钮（`:157-168`），不发包。
- 内部类：`static class Button extends GuiButton`（`:211`，带选中态 `field_146142_r`，`func_146141_c()`/`func_146140_b(boolean)` 为读/写选中态）；`CancelButton`（`:271`）、`ConfirmButton`（`:284`）、`PowerButton`（`:297`，图标取自 `GuiContainer.inventoryBackground` 的药水图标区，`:304`）。

### GuiEditSign（GuiEditSign.java，179 行）

不是容器，直接 extends GuiScreen。字段：`private TileEntitySign tileSign;`（`:21`）、`private int updateCounter;`（`:24`）、`private int editLine;`（`:27`）、`private GuiButton doneBtn;`（`:30`）。

- `public void initGui()`（`:41`）— `Keyboard.enableRepeatEvents(true)`；`this.tileSign.setEditable(false);`（`:46`）。
- `public void onGuiClosed()`（`:52`）— **发包点**：`nethandlerplayclient.addToSendQueue(new C12PacketUpdateSign(this.tileSign.getPos(), this.tileSign.signText));`（`:59`），再 `setEditable(true)`。任何路径关闭界面（含 ESC）都会提交文本。
- `protected void keyTyped(char typedChar, int keyCode) throws IOException`（`:92`）— 上/下/回车换行（`editLine ± 1 & 3`）；退格截尾；`ChatAllowedCharacters.isAllowedCharacter(typedChar)` 且行宽 `<= 90` 像素才接收（`:111`）；每次键入直接 `this.tileSign.signText[this.editLine] = new ChatComponentText(s);`（`:116`）——就地改 TileEntity；ESC 等价按 Done。
- `public void drawScreen(int mouseX, int mouseY, float partialTicks)`（`:127`）— 按方块类型（`Blocks.standing_sign` 用 metadata*360/16，墙上牌按 metadata 2/4/5 转向）摆位后，用 `TileEntityRendererDispatcher.instance.renderTileEntityAt(this.tileSign, -0.5D, -0.75D, -0.5D, 0.0F);`（`:174`）实时渲染；光标闪烁通过 `this.tileSign.lineBeingEdited = this.editLine;`（每 6 tick 翻转，`:169-175`）传给 TESR。

### GuiInventory（GuiInventory.java，151 行）

- 构造（`:25`）：`super(p_i1094_1_.inventoryContainer)` — 直接复用玩家常驻 `inventoryContainer`（windowId 0），不新建 Container。`allowUserInput = true`。
- `public void updateScreen()`（`:34`）— 创造模式则切到 `new GuiContainerCreative(...)`；否则 `updateActivePotionEffects()`（父类 InventoryEffectRenderer 提供，含药水效果侧栏导致的 guiLeft 偏移）。
- `public static void drawEntityOnScreen(int posX, int posY, int scale, float mouseX, float mouseY, EntityLivingBase ent)`（`:96`）— 通用"GUI 内画活体实体"工具：暂存并篡改 `renderYawOffset/rotationYaw/rotationPitch/rotationYawHead/prevRotationYawHead` 让实体看向鼠标，`rendermanager.renderEntityWithPosYaw(ent, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F)` 后恢复（`:103-127`）。被 `GuiContainerCreative.java:710` 与 `GuiScreenHorseInventory.java:68` 复用。
- `protected void actionPerformed(GuiButton button) throws IOException`（`:139`）— id 0 成就屏 / id 1 统计屏（按钮本身由父类 `InventoryEffectRenderer` 体系添加）。

### 简单子类（GuiChest / GuiFurnace / GuiBrewingStand / GuiDispenser / GuiCrafting / GuiScreenHorseInventory）

全部只覆写两个绘制方法，共享基类交互逻辑：

- `GuiChest`：构造中 `this.inventoryRows = lowerInv.getSizeInventory() / 9; this.ySize = j + this.inventoryRows * 18;`（`GuiChest.java:29-30`，j = 222 - 108 = 114）；`allowUserInput = false`（`:26`）；背景分上下两段贴 `generic_54.png`（`:51-52`）。
- `GuiFurnace`：`private int getCookProgressScaled(int pixels)`（`:56`，field 2/3 = cookTime/totalCookTime）、`private int getBurnLeftScaled(int pixels)`（`:63`，field 0/1 = furnaceBurnTime/currentItemBurnTime，field 1 为 0 时按 200 兜底）；火焰与箭头绘制在 `:46-53`。
- `GuiBrewingStand`：`getField(0)` 为 brewTime（满 400），箭头高度 `(int)(28.0F * (1.0F - (float)k / 400.0F))`（`:48`），气泡高度由 `k / 2 % 7` 查表（`:55-85`）。
- `GuiDispenser` / `GuiCrafting`：纯静态贴图 + 标题；`GuiCrafting` 有双构造器，`GuiCrafting(InventoryPlayer playerInv, World worldIn)` 委托到 `BlockPos.ORIGIN` 版本（`GuiCrafting.java:15-23`）——客户端容器的 `canInteractWith` 距离检查因此被绕开。
- `GuiScreenHorseInventory`：按 `this.horseEntity.isChested()`（`:58`）补画 15 格箱区、`canWearArmor()`（`:63`）补画甲槽，`GuiInventory.drawEntityOnScreen(i + 51, j + 60, 17, ...)` 画马（`:68`）。

### CreativeCrafting（CreativeCrafting.java，47 行）

`implements ICrafting`，只有一个有效回调：`public void sendSlotContents(Container containerToSend, int slotInd, ItemStack stack)`（`:30`）→ `this.mc.playerController.sendSlotPacket(stack, slotInd);`（`:32`，即 `C10PacketCreativeInventoryAction`）。`updateCraftingInventory` / `sendProgressBarUpdate` / `sendAllWindowProperties` 均为空实现。它在 `GuiContainerCreative.initGui`（`:279`）注册、`onGuiClosed`（`:296`）注销，作用是把创造背包里通过 `inventoryContainer.detectAndSendChanges()` 检测到的本地槽位变化推给服务端。

## 时序与生命周期

打开：服务端发开窗封包 → `NetHandlerPlayClient` 处理（Netty EventLoop 收包后调度到主线程）→ `EntityPlayerSP.displayGUIChest` 等 → `mc.displayGuiScreen(new GuiXxx(...))`；构造器里 new 出客户端 `Container`；随后主线程调 `setWorldAndResolution` → `initGui()`：`GuiContainer.initGui` 设置 `mc.thePlayer.openContainer = inventorySlots` 并算 `guiLeft/guiTop`（`GuiContainer.java:91-93`）。窗口尺寸变化会重跑 `initGui`（buttonList 先被清空），`GuiBeacon` 靠 `buttonsNotDrawn` 门闩在 `updateScreen` 里重建效果按钮。

每 tick（主线程，`Minecraft.runTick` → `currentScreen.updateScreen()`）：
- `GuiContainer.updateScreen`：玩家死亡关屏（`:757-765`）。
- `GuiInventory` / `GuiContainerCreative` 的 `updateScreen`：检测游戏模式互切界面 + `updateActivePotionEffects()`。
- `GuiBeacon.updateScreen`：轮询容器字段建按钮、刷新确认键可用性。
- `GuiEditSign.updateScreen`：`++this.updateCounter`（光标闪烁计时）。

每帧（主线程，`EntityRenderer` → `currentScreen.drawScreen`）：`GuiContainer.drawScreen` 的固定序列（背景层 → 按钮 → 槽位 → 前景层 → 手持物品 → tooltip）；`GuiContainerCreative.drawScreen` 额外在帧内轮询 `Mouse.isButtonDown(0)` 做滚动条拖动。

输入：`GuiScreen.handleInput` 主线程分发 → `mouseClicked / mouseClickMove / mouseReleased / keyTyped / handleMouseInput`。所有槽位操作汇聚到 `handleMouseClick` → `PlayerControllerMP.windowClick`：**本地立即执行** `Container.slotClick`（预测），同时 `addToSendQueue` 发 `C0EPacketClickWindow`（发送在 Netty EventLoop 上执行，队列入口线程安全）。

关闭：`mc.displayGuiScreen(null)` 或 `thePlayer.closeScreen()` → `onGuiClosed()`：`GuiContainer` 调 `inventorySlots.onContainerClosed`；`GuiEditSign` 发 `C12PacketUpdateSign`；`GuiContainerCreative` 注销 `CreativeCrafting`。`C0DPacketCloseWindow` 不在本包发出（由 `EntityPlayerSP.closeScreenAndDropStack` 一侧负责）。

线程归属：本包全部逻辑跑在客户端主线程；与网络的边界只有 `addToSendQueue` / `windowClick` / `sendSlotPacket`。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void drawScreen(int mouseX, int mouseY, float partialTicks)` | GuiContainer.java:99 | 容器界面每帧 | 覆盖整个容器渲染；前后插入 overlay（搜索高亮、槽位标记） | 内部矩阵 push/pop 与 GL 状态切换成对；tooltip 在 pop 之后画 |
| `protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY)` | GuiContainer.java:219 | drawScreen 中段（:142） | 在槽位之上、手持物之下画窗口内容 | 坐标系已平移到 (guiLeft, guiTop)；灯光已被 disableStandardItemLighting |
| `protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);` | GuiContainer.java:226 | drawScreen 开头（:104） | 换皮肤/背景纹理、画进度条 | 坐标系未平移，需自己加 guiLeft/guiTop |
| `protected void handleMouseClick(Slot slotIn, int slotId, int clickedButton, int clickType)` | GuiContainer.java:678 | 一切槽位操作的收口 | 拦截/改写/记录所有窗口点击（自动整理、防误点、宏）；这是发 `C0EPacketClickWindow` 前最后一层 | `GuiContainerCreative` 已完全覆写它绕开发包路径；slotIn 非 null 时 slotId 被强制覆盖为 `slotIn.slotNumber` |
| `protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException` | GuiContainer.java:359 | 鼠标按下 | 自定义点击手势、拦截双击判定（250ms 窗口） | 需 `super` 以保留按钮点击；-999 = 窗口外 |
| `protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick)` | GuiContainer.java:466 | 按住拖动每帧 | 改写拖拽分堆的槽位收集规则 | 触屏与非触屏两条互斥分支 |
| `protected void mouseReleased(int mouseX, int mouseY, int state)` | GuiContainer.java:515 | 鼠标松开 | 拦截拖拽分堆结算（三段 clickType 5）与双击归拢（clickType 6） | 分堆按钮值经 `Container.func_94534_d` 编码，勿直接传 raw button |
| `protected void keyTyped(char typedChar, int keyCode) throws IOException` | GuiContainer.java:692 | 容器内键入 | 加快捷键；拦截 ESC/背包键关屏 | 不调 super 会失去关屏、pick-block、丢弃、热键交换 |
| `protected boolean checkHotbarKeys(int keyCode)` | GuiContainer.java:718 | keyTyped 内 | 改写数字键交换行为 | 仅在手上无物品且有悬停槽时生效 |
| `public void initGui()` | GuiContainer.java:88 | 打开与每次窗口 resize | 注入自定义按钮/组件；感知"容器打开" | resize 会重复触发，勿在此做一次性副作用；`:91` 写 `thePlayer.openContainer` |
| `public void onGuiClosed()` | GuiContainer.java:738 | 界面关闭 | 感知"容器关闭"、清理状态 | 基类只调 `onContainerClosed`，不发关窗包 |
| `public void updateScreen()` | GuiContainer.java:757 | 每 tick | 容器内周期逻辑（自动操作节流） | 玩家死亡会强制 closeScreen |
| `private void drawSlot(Slot slotIn)` | GuiContainer.java:228 | drawScreen 逐槽 | （改源码/字节码）槽位高亮、物品替换显示 | private，无法覆写，只能改基类或 ASM |
| `private Slot getSlotAtPosition(int x, int y)` | GuiContainer.java:341 | 各鼠标事件 | （改源码）自定义槽位命中区域 | private；`isMouseOverSlot`/`isPointInRegion`（:657/:666）为其基础 |
| `protected boolean isPointInRegion(int left, int top, int right, int bottom, int pointX, int pointY)` | GuiContainer.java:666 | 命中检测 | 统一改判定（例如去掉 1px 外扩） | 参数 right/bottom 实为宽/高 |
| `protected void handleMouseClick(Slot slotIn, int slotId, int clickedButton, int clickType)` | GuiContainerCreative.java:85 | 创造背包一切点击 | 拦截创造模式取物/清背包/丢弃 | 与基类语义完全不同：走 `C10PacketCreativeInventoryAction` 而非窗口点击 |
| `private void setCurrentCreativeTab(CreativeTabs p_147050_1_)` | GuiContainerCreative.java:456 | 页签切换 | 加自定义页签逻辑、感知页签变化 | private；会整体替换 `inventorySlots.inventorySlots` 列表（tabInventory 页签） |
| `private void updateCreativeSearch()` | GuiContainerCreative.java:341 | 搜索框内容变化 | 改搜索算法（模糊/正则/ID 搜索） | private；遍历全 `Item.itemRegistry`，别在每帧调 |
| `public void handleMouseInput() throws IOException` | GuiContainerCreative.java:546 | 每个鼠标事件 | 拦截滚轮翻页 | 使用 `Mouse.getEventDWheel()`（shim 提供） |
| `protected void renderToolTip(ItemStack stack, int x, int y)` | GuiContainerCreative.java:622 | 悬停物品每帧 | 自定义 tooltip 内容 | 基类版本在 `GuiScreen`；此覆写只在搜索页签走特殊分支 |
| `protected void actionPerformed(GuiButton button) throws IOException` | GuiBeacon.java:131 | 信标按钮点击 | 拦截 `MC|Beacon` 自定义载荷发送（:139-143） | PowerButton 点击只改本地字段，确认才发包 |
| `public void updateScreen()` | GuiBeacon.java:56 | 每 tick | 观察信标字段同步、自动选效果 | 按钮在 `getField(0) >= 0` 前不存在 |
| `public void onGuiClosed()` | GuiEditSign.java:52 | 关闭编辑屏 | 拦截/改写 `C12PacketUpdateSign`（:59） | 所有关闭路径都发包，包括 ESC |
| `protected void keyTyped(char typedChar, int keyCode) throws IOException` | GuiEditSign.java:92 | 编辑牌子键入 | 放宽 90px 行宽限制、支持颜色符 | 直接改 `tileSign.signText`，无本地/远端分离 |
| `public static void drawEntityOnScreen(int posX, int posY, int scale, float mouseX, float mouseY, EntityLivingBase ent)` | GuiInventory.java:96 | 背包/马/创造背包画模型 | 通用 GUI 实体预览（皮肤查看器等直接复用） | 会临时篡改实体 5 个朝向字段，方法尾部恢复；非 reentrant |
| `public void updateScreen()` | GuiInventory.java:34 / GuiContainerCreative.java:72 | 每 tick | 感知生存/创造界面互切 | 两者互相 `displayGuiScreen`，覆写时防循环 |
| `public void sendSlotContents(Container containerToSend, int slotInd, ItemStack stack)` | CreativeCrafting.java:30 | 创造模式 `detectAndSendChanges` 检出差异时 | 观察/过滤创造模式槽位同步 | 走 `sendSlotPacket`（C10），slotInd 是 inventoryContainer 槽号 |

## 数据与协议

本包不解析封包，只构造并发送以下四种：

**C0EPacketClickWindow**（经 `PlayerControllerMP.windowClick`，`PlayerControllerMP.java:538`；触发点 `GuiContainer.handleMouseClick`，`GuiContainer.java:685`）

| 字段 | 类型 | 写入来源 | 取值含义 |
|---|---|---|---|
| windowId | int | `this.inventorySlots.windowId` | 当前窗口 id（玩家背包恒 0） |
| slotId | int | `slot.slotNumber` 或 -999 | -999 = 窗口外（丢弃/拖拽协议控制帧） |
| mouseButtonClicked | int | 0/1/热键序号/`func_94534_d` 编码值 | clickType 2 时为热键槽 0-8；clickType 5 时为 `func_94534_d(0|1|2, dragSplittingLimit)` |
| mode (clickType) | int | 见 GuiContainer 详解 | 0 普通 / 1 shift / 2 热键 / 3 pick-block / 4 丢弃 / 5 拖拽 / 6 双击 |
| clickedItem + actionNumber | ItemStack, short | windowClick 内部生成 | 服务端事务确认用 |

**C10PacketCreativeInventoryAction**（经 `PlayerControllerMP.sendSlotPacket` / `sendPacketDropItem`，`PlayerControllerMP.java:561,572`；触发点 `GuiContainerCreative.handleMouseClick` 多处与 `CreativeCrafting.sendSlotContents`）

| 字段 | 类型 | 写入来源 | 取值含义 |
|---|---|---|---|
| slotId | int | inventoryContainer 槽号；-1 | -1 = 直接丢出物品；`(ItemStack)null` 内容 = 清空该槽 |
| stack | ItemStack | 目标槽新内容 | 服务端无条件采纳（创造模式特权） |

**C17PacketCustomPayload "MC|Beacon"**（`GuiBeacon.java:139-143`）

| 字段 | 类型 | 写入方法 | 取值含义 |
|---|---|---|---|
| channel | String | 构造参数 `"MC|Beacon"` | 自定义载荷通道名 |
| payload int #1 | int | `packetbuffer.writeInt(this.tileBeacon.getField(1))` | 主效果 Potion id |
| payload int #2 | int | `packetbuffer.writeInt(this.tileBeacon.getField(2))` | 副效果 Potion id |

**C12PacketUpdateSign**（`GuiEditSign.java:59`）

| 字段 | 类型 | 写入来源 | 取值含义 |
|---|---|---|---|
| pos | BlockPos | `this.tileSign.getPos()` | 牌子坐标 |
| lines | IChatComponent[] | `this.tileSign.signText`（4 元素，每行 `ChatComponentText`） | 编辑后的 4 行文本 |

容器字段（`IInventory.getField(int)`，经 `S31PacketWindowProperty` 由服务端推送，本包只读）：
- 熔炉：0 furnaceBurnTime、1 currentItemBurnTime、2 cookTime、3 totalCookTime（`GuiFurnace.java:56-73`）。
- 酿造台：0 brewTime，满值 400（`GuiBrewingStand.java:44-48`）。
- 信标：0 levels、1 primary、2 secondary（`GuiBeacon.java:59-61`）。

## 不变量与陷阱

- `GuiContainer.initGui` 会执行 `mc.thePlayer.openContainer = this.inventorySlots`（`GuiContainer.java:91`）——`openContainer` 与当前 GUI 必须一致，服务端槽位同步封包按 windowId 路由到 `openContainer`；自定义容器 GUI 若跳过 `super.initGui()` 会造成物品同步错位。
- `drawGuiContainerBackgroundLayer` 与 `drawGuiContainerForegroundLayer` 坐标系不同：前者未平移、后者已平移到 `(guiLeft, guiTop)`。搞混会导致 resize 后内容错位。
- `windowClick` 是"本地预测 + 发包"：本地 `slotClick` 立即改客户端容器，服务端拒绝时通过 `S32PacketConfirmTransaction` / `S2FPacketSetSlot` 回滚。Hook 时若只吞掉发包不吞本地执行，会造成客户端与服务端不同步（ghost item）。
- `GuiContainerCreative` 的 `selectedTabIndex` 与 `field_147060_v` 是 **static**：跨界面实例共享，页签记忆即由此实现；写测试或多实例场景注意状态泄漏。
- `GuiContainerCreative.setCurrentCreativeTab` 在 tabInventory 页签会把 `inventorySlots.inventorySlots` 整个换成新 List（`GuiContainerCreative.java:474`），持有旧列表引用的代码会看到过期槽位。
- `GuiChest.ySize` 依赖 `lowerInv.getSizeInventory()` 是 9 的整数倍；非 9 倍数的自定义容器会画错高度。
- `GuiBeacon` 的效果按钮依赖服务端先同步容器字段（`getField(0) >= 0` 才建按钮）；本地 `setField` 只是 UI 预选，真正生效靠确认时的 `MC|Beacon` 包。
- `GuiEditSign.onGuiClosed` 无条件发 `C12PacketUpdateSign`——不存在"取消编辑"；且 `keyTyped` 直接改 `TileEntitySign.signText`，编辑过程立刻反映在世界渲染里。
- `GuiInventory.drawEntityOnScreen` 会临时改写实体的 `renderYawOffset/rotationYaw/rotationPitch/rotationYawHead/prevRotationYawHead` 再恢复（`GuiInventory.java:103-127`）；渲染期间抛异常会让实体朝向永久错乱，包装时注意 try/finally 不存在。
- LWJGL3 移植：`org.lwjgl.input.Keyboard` / `Mouse` 来自 `lwjgl2-shim/src/main/java/org/lwjgl/input/`（shim 模拟 LWJGL2 API）。本包用到 `Keyboard.isKeyDown(42)/(54)`（左右 shift 原始扫描码）、`Keyboard.enableRepeatEvents(boolean)`（`GuiContainerCreative.java:269/299`、`GuiEditSign.java:44/54`）、`Mouse.getEventDWheel()`（`GuiContainerCreative.java:549`）、`Mouse.isButtonDown(0)`（`:576`）。滚轮增量与按键重复语义依赖 shim 对 GLFW 事件的还原，行为差异首先查 shim。
- `mouseClicked` 中 pick-block 的鼠标按键编码是 `keyBindPickBlock.getKeyCode() + 100`（`GuiContainer.java:362`）——LWJGL2 惯例"鼠标键 = keyCode - 100"，shim 必须保持该映射。
- 线程约束：本包所有方法只能在客户端主线程调用（GL 上下文 + 未加锁的容器状态）；唯一线程安全出口是 `addToSendQueue`。
- `doesGuiPauseGame()` 返回 false（`GuiContainer.java:749`）：打开容器时世界继续 tick，玩家可被攻击，`updateScreen` 里的死亡检测（`:761`）就是兜底。
- `theSlot`（悬停槽）、`drawSlot`、`getSlotAtPosition` 均为 private——功能层拿悬停槽没有官方入口，常见做法是在 `drawGuiContainerForegroundLayer` 里用 `isPointInRegion` 自算，或改基类可见性。

## 交叉引用

- `net.minecraft.client.entity` → `EntityPlayerSP#displayGUIChest / #displayGUIHorse / #openEditSign / #displayGui`（打开本包各界面，`EntityPlayerSP.java:580-651`）
- `net.minecraft.client` → `Minecraft#displayGuiScreen`（界面切换）、`Minecraft.java:2096`（背包键打开 `GuiInventory`）、`Minecraft#getSystemTime`（双击/拖动计时）
- `net.minecraft.client.multiplayer` → `PlayerControllerMP#windowClick / #sendSlotPacket / #sendPacketDropItem / #isInCreativeMode`（所有交互出口）
- `net.minecraft.client.network` → `NetHandlerPlayClient#addToSendQueue`（`GuiBeacon`、`GuiEditSign` 直发包）
- `net.minecraft.inventory` → `Container#slotClick / #detectAndSendChanges / #onCraftGuiOpened / #removeCraftingFromCrafters / #onContainerClosed`、静态 `Container.canAddItemToSlot / .computeStackSize / .func_94534_d / .getDragEvent`、`Slot` 及 `ContainerChest / ContainerFurnace / ContainerBrewingStand / ContainerBeacon / ContainerDispenser / ContainerWorkbench / ContainerHorseInventory`（各 GUI 构造器 new 出）
- `net.minecraft.client.renderer` → `InventoryEffectRenderer`（`GuiInventory`/`GuiContainerCreative` 的父类，药水效果侧栏）、`GlStateManager`、`RenderHelper`、`OpenGlHelper#setLightmapTextureCoords`
- `net.minecraft.client.renderer.entity` → `RenderManager#renderEntityWithPosYaw`（`GuiInventory#drawEntityOnScreen`）
- `net.minecraft.client.renderer.tileentity` → `TileEntityRendererDispatcher#renderTileEntityAt`（`GuiEditSign#drawScreen`）
- `net.minecraft.client.gui` → `GuiScreen`（基类；`drawCreativeTabHoveringText` 定义在 `GuiScreen.java:181`）、`GuiButton`、`GuiTextField`
- `net.minecraft.client.gui.achievement` → `GuiAchievements / GuiStats`（`GuiInventory#actionPerformed`、`GuiContainerCreative#actionPerformed` 打开）
- `net.minecraft.creativetab` → `CreativeTabs#displayAllReleventItems / #getTabIndex / #getIconItemStack`（创造页签数据源）
- `net.minecraft.item` → `Item.itemRegistry`（搜索遍历）、`ItemStack#getTooltip`
- `net.minecraft.enchantment` → `Enchantment.enchantmentsBookList`、`EnchantmentHelper#getEnchantments`（搜索页附魔书归类）
- `net.minecraft.tileentity` → `TileEntityBeacon.effectsList`、`TileEntitySign#signText / #setEditable / #lineBeingEdited`
- `net.minecraft.network.play.client` → `C0EPacketClickWindow / C10PacketCreativeInventoryAction / C12PacketUpdateSign / C17PacketCustomPayload`
- `org.lwjgl.input`（lwjgl2-shim）→ `Keyboard#isKeyDown / #enableRepeatEvents`、`Mouse#getEventDWheel / #isButtonDown`

## 覆盖声明

完整读取了 12/12 个文件（Read 全文，无截断）。逐行精读的类：GuiContainer、GuiContainerCreative（含 ContainerCreative / CreativeSlot 内部类）、GuiBeacon（含四个内部按钮类）、GuiEditSign、GuiInventory、CreativeCrafting。其余简单子类（GuiChest、GuiFurnace、GuiBrewingStand、GuiDispenser、GuiCrafting、GuiScreenHorseInventory）体量小（45-93 行），同样全文读完并核对了字段与签名。另外对 `EntityPlayerSP`、`PlayerControllerMP`、`GuiScreen`、`InventoryEffectRenderer`、lwjgl2-shim 做了定点 Grep 验证调用关系，未通读。所有行号引用均对照 Read 输出核实。
