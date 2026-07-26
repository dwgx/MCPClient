---
area: net/minecraft/inventory
slug: mc-inventory
files: 30
lines: 5221
tier: B
---

# net/minecraft/inventory

## 定位

这个包是物品栏 / 容器交互的核心逻辑层，纯逻辑、不含渲染代码。三层结构：

- `IInventory` 及其实现（`InventoryBasic`、`InventoryCrafting`、`InventoryMerchant` 等）：物品的实际存储（`ItemStack[]`）。
- `Slot` 及其子类：单个格子的访问代理 + 校验规则（`isItemValid` / `canTakeStack` / 堆叠上限）+ 屏幕坐标。
- `Container` 及其子类（`ContainerChest`、`ContainerFurnace`…）：把多个 `Slot` 聚合成一个"打开的窗口"，处理所有点击语义（拿起、放下、shift 移动、拖拽分堆、数字键交换、双击收集），并通过 `ICrafting` 监听器把变更同步给客户端。

谁调用它：
- 客户端侧：`PlayerControllerMP#windowClick`（PlayerControllerMP.java:537）在本地预测执行 `Container.slotClick` 然后发 `C0EPacketClickWindow`；`GuiContainer` 系列 GUI 读取 `inventorySlots` 渲染格子；`NetHandlerPlayClient` 收到 `S30PacketWindowItems`/`S2FPacketSetSlot`/`S31PacketWindowProperty` 后调 `putStacksInSlots` / `putStackInSlot` / `updateProgressBar`（NetHandlerPlayClient.java:1205/1293）。
- 服务端侧（本仓库含完整集成服务端代码）：`NetHandlerPlayServer#processClickWindow`（NetHandlerPlayServer.java:1007）执行权威 `slotClick`；`EntityPlayerMP implements ICrafting`（EntityPlayerMP.java:99），每 tick 调 `openContainer.detectAndSendChanges()`（EntityPlayerMP.java:282）把差异下发成封包。

它调用谁：`net.minecraft.item`（ItemStack 比较/拆分）、`item.crafting.CraftingManager`/`FurnaceRecipes`（配方匹配）、`enchantment.EnchantmentHelper`（附魔台/铁砧计算）、`entity.player`（丢物品、经验、成就）、`world`（附魔台数书架、铁砧损坏）、`tileentity`（燃料判定、末影箱开合）。

如果消失：所有 GUI 容器（背包、箱子、熔炉、工作台、附魔台、铁砧、村民交易、马匹）全部失效，客户端与服务端的物品栏同步协议（window 系封包的处理端）也随之断裂。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| AnimalChest | 16 | extends InventoryBasic | 马/驴背包的存储，仅两个构造器转发 |
| Container | 800 | abstract | 容器基类：槽位聚合、slotClick 全部点击语义、监听器差异同步、mergeItemStack |
| ContainerBeacon | 161 | extends Container | 信标容器，1 个限贵重物品的 BeaconSlot，关闭时退还物品 |
| ContainerBrewingStand | 206 | extends Container | 酿造台容器，3 药水槽 + 1 原料槽，同步 brewTime |
| ContainerChest | 99 | extends Container | 箱子容器，按 numRows 动态布槽，开/关时通知 openInventory/closeInventory |
| ContainerDispenser | 85 | extends Container | 发射器/投掷器容器，3x3 普通槽 |
| ContainerEnchantment | 425 | extends Container | 附魔台容器，数书架、按 xpSeed 算附魔选项并经进度条协议同步 |
| ContainerFurnace | 166 | extends Container | 熔炉容器，同步 cookTime/burnTime 等 4 个 field |
| ContainerHopper | 87 | extends Container | 漏斗容器，一行 5 槽 |
| ContainerHorseInventory | 129 | extends Container | 马匹容器，鞍/甲专用槽 + 可选箱子区，校验马存活与距离 |
| ContainerMerchant | 169 | extends Container | 村民交易容器，内建 InventoryMerchant，关闭时退还输入 |
| ContainerPlayer | 197 | extends Container | 玩家背包容器，2x2 合成 + 4 盔甲槽（匿名 Slot 限定 armorType） |
| ContainerRepair | 506 | extends Container | 铁砧容器，updateRepairOutput 计算修理/改名/合书费用，取出时损坏铁砧 |
| ContainerWorkbench | 154 | extends Container | 工作台容器，3x3 合成矩阵，关闭时退还材料 |
| ICrafting | 27 | interface | 容器变更监听器：整表/单槽/进度条同步回调 |
| IInvBasic | 9 | interface | InventoryBasic 变更监听回调 |
| IInventory | 66 | interface extends IWorldNameable | 物品存储的统一抽象：取/放/拆栈、field、open/close |
| ISidedInventory | 21 | interface extends IInventory | 按面自动化插取的扩展（漏斗/熔炉用） |
| InventoryBasic | 281 | implements IInventory | 通用数组存储实现，带 IInvBasic 监听器列表 |
| InventoryCraftResult | 157 | implements IInventory | 单槽合成结果存储 |
| InventoryCrafting | 210 | implements IInventory | 合成矩阵（width x height），任何写操作回调 eventHandler.onCraftMatrixChanged |
| InventoryEnderChest | 90 | extends InventoryBasic | 末影箱 27 格，NBT 读写，联动 TileEntityEnderChest 开合动画 |
| InventoryHelper | 68 | （无父类，静态工具） | 把整个 IInventory 的物品掉落为 EntityItem |
| InventoryLargeChest | 220 | implements ILockableContainer | 双箱视图：把上下两个 ILockableContainer 拼成一个大 inventory |
| InventoryMerchant | 283 | implements IInventory | 交易 3 槽存储，resetRecipeAndSlots 根据输入匹配 MerchantRecipe |
| Slot | 163 | （无父类） | 格子代理：读写委托 inventory，校验钩子，屏幕坐标 |
| SlotCrafting | 162 | extends Slot | 合成输出槽：禁放入、onPickupFromSlot 扣减矩阵、触发合成成就 |
| SlotFurnaceFuel | 31 | extends Slot | 燃料槽：只收燃料，桶限 1 个 |
| SlotFurnaceOutput | 109 | extends Slot | 熔炉输出槽：禁放入、取出时按配方掉落经验球 |
| SlotMerchantResult | 124 | extends Slot | 交易输出槽：取出时执行 doTrade 扣减买入物并 useRecipe |

## 核心类详解

### Container（Container.java）

所有容器窗口的基类，客户端和服务端共用同一份逻辑（客户端预测 + 服务端权威）。

关键字段（Container.java:16-30）：
- `public List<ItemStack> inventoryItemStacks` — 上次同步时每个槽的快照（copy），用于差异检测。
- `public List<Slot> inventorySlots` — 槽位表，`slotNumber` 即下标。
- `public int windowId` — 窗口 id，与 window 系封包对应；玩家背包固定为 0。
- `private short transactionID` — `getNextTransactionID` 递增，配合 `C0FPacketConfirmTransaction`。
- `private int dragMode` / `private int dragEvent` / `private final Set<Slot> dragSlots` — 拖拽分堆状态机。
- `protected List<ICrafting> crafters` — 监听器（服务端为 `EntityPlayerMP`，客户端创造模式为 `CreativeCrafting`）。

关键方法：
- `protected Slot addSlotToContainer(Slot slotIn)`（Container.java:35）— 子类构造器中注册槽位，赋 `slotNumber`。
- `public void onCraftGuiOpened(ICrafting listener)`（Container.java:43）— 注册监听器并立即整表推送（`updateCraftingInventory`）。重复注册抛 `IllegalArgumentException`（Container.java:47）。
- `public void detectAndSendChanges()`（Container.java:80）— 与快照逐槽比对，变了就 `sendSlotContents` 给所有 crafters。服务端每 tick 调用。
- `public ItemStack slotClick(int slotId, int clickedButton, int mode, EntityPlayer playerIn)`（Container.java:140）— 全部点击语义。mode 含义：0=普通点击，1=shift 点击（走 `transferStackInSlot`），2=数字键换到快捷栏，3=创造模式中键克隆，4=Q 丢弃，5=拖拽分堆（状态机），6=双击收集。`slotId == -999` 表示点在窗口外丢弃手上物品（Container.java:234）。
- `public ItemStack transferStackInSlot(EntityPlayer playerIn, int index)`（Container.java:131）— shift 点击的路由，基类只返回原栈，所有子类都覆写。
- `protected boolean mergeItemStack(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection)`（Container.java:597）— 先叠加同类栈再填空槽；注释明确警告：**不检查 `isItemValid`**。
- `public abstract boolean canInteractWith(EntityPlayer playerIn)`（Container.java:590）— 服务端每 tick 检查（EntityPlayer.java:335、EntityPlayerMP.java:284），失败即强制关窗。
- `public void onContainerClosed(EntityPlayer playerIn)`（Container.java:516）— 把手上的浮动物品丢回世界。
- `public void updateProgressBar(int id, int data)`（Container.java:554）— 客户端收到 `S31PacketWindowProperty` 后的入口。
- `public static int calcRedstone(TileEntity te)` / `calcRedstoneFromInventory(IInventory inv)`（Container.java:769/774）— 比较器信号强度计算。

### Slot（Slot.java）

关键字段：`private final int slotIndex`（inventory 内下标）、`public final IInventory inventory`、`public int slotNumber`（容器内下标）、`public int xDisplayPosition` / `yDisplayPosition`（Slot.java:9-21）。

关键方法：
- `public boolean isItemValid(ItemStack stack)`（Slot.java:73）— 放入校验，默认 true。
- `public boolean canTakeStack(EntityPlayer playerIn)`（Slot.java:150）— 取出校验，默认 true（铁砧输出槽用它做经验门槛，ContainerRepair.java:71）。
- `public void putStack(ItemStack stack)`（Slot.java:97）/ `public ItemStack decrStackSize(int amount)`（Slot.java:134）— 委托到 inventory，前者附带 `onSlotChanged()`。
- `public void onPickupFromSlot(EntityPlayer playerIn, ItemStack stack)`（Slot.java:65）— 取出后回调，输出类槽子在此消耗材料 / 给经验 / 执行交易。
- `public void onSlotChange(ItemStack p_75220_1_, ItemStack p_75220_2_)`（Slot.java:34）— shift 取出输出槽时用于统计合成数量差。
- `public int getSlotStackLimit()`（Slot.java:115）、`public int getItemStackLimit(ItemStack stack)`（Slot.java:120）、`public String getSlotTexture()`（Slot.java:125，盔甲槽占位图标）、`public boolean canBeHovered()`（Slot.java:159，GUI 高亮控制）。

### IInventory（IInventory.java）

存储抽象，自有 15 个方法（IInventory.java:12-65，另经 IWorldNameable 继承 3 个）。注意 `getField(int id)` / `setField(int id, int value)` / `getFieldCount()` 是 tile entity 进度值的统一通道（熔炉 4 个 field、酿造台 1 个、信标 3 个），`ICrafting.sendProgressBarUpdate` 同步的就是这些值。`markDirty()` 在容器语境里兼作"变更通知"（`Slot.onSlotChanged` 调它）。

### InventoryBasic（InventoryBasic.java）

`ItemStack[] inventoryContents` 数组存储 + `List<IInvBasic> changeListeners`（InventoryBasic.java:13-17）。`markDirty()` 遍历监听器调 `onInventoryChanged(this)`（InventoryBasic.java:225）。`public ItemStack func_174894_a(ItemStack stack)`（InventoryBasic.java:98）是"尽量塞入，返回剩余"的工具（漏斗/矿车用）。附魔台和铁砧用匿名子类覆写 `markDirty()` 把变更转发给 `onCraftMatrixChanged`（ContainerEnchantment.java:45、ContainerRepair.java:54）。

### InventoryCrafting(InventoryCrafting.java)

合成矩阵。核心耦合点：`private final Container eventHandler`（InventoryCrafting.java:21），`setInventorySlotContents` / `decrStackSize` 都会回调 `this.eventHandler.onCraftMatrixChanged(this)`（InventoryCrafting.java:108/120/136），这就是"放材料立即刷新输出"的机制。`getStackInRowAndColumn(int row, int column)`（InventoryCrafting.java:51）供 `CraftingManager` 形状匹配。注意 `markDirty()` 是空实现（InventoryCrafting.java:151）。

### ContainerPlayer（ContainerPlayer.java）

玩家常驻容器（`EntityPlayer` 构造时创建，EntityPlayer.java:181，`openContainer` 默认指向它）。槽位布局：0=SlotCrafting 输出，1-4=2x2 合成，5-8=盔甲（匿名 Slot：`getSlotStackLimit()` 返回 1，`isItemValid` 按 `((ItemArmor)stack.getItem()).armorType == k_f` 校验，且南瓜/头颅只允许头槽，ContainerPlayer.java:39-53），9-35=主背包，36-44=快捷栏。`onCraftMatrixChanged` 调 `CraftingManager.getInstance().findMatchingRecipe(this.craftMatrix, this.thePlayer.worldObj)` 填输出槽（ContainerPlayer.java:77）。`canInteractWith` 恒 true（ContainerPlayer.java:100）。

### SlotCrafting（SlotCrafting.java）

合成输出槽。`isItemValid` 恒 false（SlotCrafting.java:37）。`onPickupFromSlot`（SlotCrafting.java:134）：先 `onCrafting(stack)`（触发 `stack.onCrafting` + 十余个成就判定，SlotCrafting.java:69-132），再调 `CraftingManager.getInstance().func_180303_b(this.craftMatrix, playerIn.worldObj)` 取"剩余物"数组（如桶），逐格 `decrStackSize(i, 1)` 并放回剩余物，放不下就 `dropPlayerItemWithRandomChoice`。

### ContainerRepair（ContainerRepair.java）

铁砧。字段：`public int maximumCost`（同步给客户端显示，进度条 id 0）、`private int materialCost`、`private String repairedItemName`（ContainerRepair.java:35-39）。`public void updateRepairOutput()`（ContainerRepair.java:159）实现原版全部铁砧规则：材料修理（每份材料修 1/4 上限耐久）、同类合并（+12% 上限）、附魔书合并（冲突附魔每个 +1 费用、按 `enchantment.getWeight()` 折算费用）、改名 +1；`maximumCost >= 40` 且非创造则输出为 null（ContainerRepair.java:365）。输出槽是匿名 Slot：`canTakeStack` 要求 `playerIn.experienceLevel >= maximumCost`（ContainerRepair.java:71-74），`onPickupFromSlot` 扣经验、清空输入、12% 概率升级 `BlockAnvil.DAMAGE` 或破坏铁砧（ContainerRepair.java:106-120）。`public void updateItemName(String newName)`（ContainerRepair.java:486）由 GuiRepair 的文本框每次击键调用（经 `C17PacketCustomPayload "MC|ItemName"` 到服务端）。

### ContainerEnchantment（ContainerEnchantment.java）

字段：`public int xpSeed`、`public int[] enchantLevels`（3 项）、`public int[] enchantmentIds`（3 项，`effectId | level << 8` 编码，-1 表示无，ContainerEnchantment.java:26-30）。`onCraftMatrixChanged`（ContainerEnchantment.java:145）在服务端数 15 格书架（两层，隔一格空气），用 `this.rand.setSeed((long)this.xpSeed)` 保证选项确定性，然后 `detectAndSendChanges` 经进度条 id 0-6 下发。`public boolean enchantItem(EntityPlayer playerIn, int id)`（ContainerEnchantment.java:243）是 `C11PacketEnchantItem` 的服务端处理：校验青金石数量（`itemstack1.stackSize < i` 拒绝）与等级，书转 `Items.enchanted_book`，扣青金石与等级后刷新 seed。进度条含义：0-2=三个选项等级，3=`xpSeed & -16`，4-6=enchantmentIds（ContainerEnchantment.java:93-99）。

### InventoryMerchant + SlotMerchantResult

`InventoryMerchant.resetRecipeAndSlots()`（InventoryMerchant.java:198）：取 0/1 槽输入（允许顺序颠倒），`merchantrecipelist.canRecipeBeUsed(itemstack, itemstack1, this.currentRecipeIndex)` 匹配后把 `getItemToSell().copy()` 放 2 槽，并 `theMerchant.verifySellingItem`。`setCurrentRecipeIndex`（InventoryMerchant.java:256）由 GUI 翻页按钮经 `MC|TrSel` payload 触发。`SlotMerchantResult.onPickupFromSlot`（SlotMerchantResult.java:70）：`doTrade` 双向尝试扣减买入物（SlotMerchantResult.java:101），成功则 `theMerchant.useRecipe(merchantrecipe)` 并触发交易统计。

## 时序与生命周期

- 构造：`EntityPlayer` 构造时创建 `ContainerPlayer`（`inventoryContainer`），`openContainer = inventoryContainer`（EntityPlayer.java:181-182）。其余容器在打开 GUI 时构造：服务端 `EntityPlayerMP.displayGUIChest` 等发 `S2DPacketOpenWindow` 并 `openContainer.onCraftGuiOpened(this)`（EntityPlayerMP.java:761 等）；客户端 `NetHandlerPlayClient.handleOpenWindow`（NetHandlerPlayClient.java:1092）按 GuiID 构造对应 GUI + Container，随后 `S30PacketWindowItems` 整表填充。
- 每 tick（服务端 / 集成服务端线程）：`EntityPlayerMP.onUpdate` 调 `this.openContainer.detectAndSendChanges()`（EntityPlayerMP.java:282）；`EntityPlayer.onUpdate` 检查 `!this.openContainer.canInteractWith(this)` 则关容器回到背包（EntityPlayer.java:335-338）。子类的 `detectAndSendChanges` 覆写在此顺带同步进度 field（熔炉、酿造台、附魔台）。
- 事件驱动（无每帧逻辑）：点击 → 客户端 `PlayerControllerMP#windowClick` 本地执行 `slotClick` + 发 `C0EPacketClickWindow`（PlayerControllerMP.java:537）→ 服务端 `NetHandlerPlayServer.processClickWindow` 权威执行、比对结果、回 `S32PacketConfirmTransaction`（NetHandlerPlayServer.java:1007-1041）。
- 渲染（每帧，客户端主线程）：本包不渲染；`GuiContainer` 读取 `container.inventorySlots` 的 `xDisplayPosition/yDisplayPosition` 画格子。
- 关闭：客户端发 `C0DPacketCloseWindow` / 服务端调 `closeScreen` → `onContainerClosed`（丢浮动物品、退还临时槽、`closeInventory`）。
- 线程归属：全部在游戏逻辑线程执行。客户端容器操作在客户端主线程；服务端权威逻辑在（集成）服务端线程。Netty EventLoop 只搬运封包，处理已被调度回主线程。**同一 Container 类会分别在两个线程各有一份实例，不共享。**

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public ItemStack slotClick(int slotId, int clickedButton, int mode, EntityPlayer playerIn)` | Container.java:140 | 每次窗口点击，客户端预测（PlayerControllerMP.java:537）与服务端权威（NetHandlerPlayServer.java:1007）各一次 | 拦截/改写一切物品栏操作：物品移动过滤、自动整理、点击宏、反作弊观测 | 客户端与服务端逻辑必须保持一致，否则触发事务失败回滚（S32PacketConfirmTransaction）；mode/button 语义复杂，见核心类详解 |
| `public void detectAndSendChanges()` | Container.java:80 | 服务端每 tick（EntityPlayerMP.java:282）及若干操作后 | 观测任意槽位变更（服务端视角）；追加自定义同步 | 每 tick 高频调用，勿做重活；快照为 copy，直接改 `inventoryItemStacks` 会破坏差异检测 |
| `public void onCraftGuiOpened(ICrafting listener)` | Container.java:43 | GUI 打开、维度切换恢复窗口时（EntityPlayerMP.java:248/761 等） | 感知"窗口打开"事件；注入自定义 ICrafting 监听器旁路全部同步流量 | 重复注册同一 listener 抛 IllegalArgumentException |
| `public void onContainerClosed(EntityPlayer playerIn)` | Container.java:516 | 窗口关闭（两端各自调用） | 感知关窗；清理自定义状态 | 子类普遍覆写并退还物品，覆盖时必须调 super，否则手上浮动物品丢失 |
| `public ItemStack transferStackInSlot(EntityPlayer playerIn, int index)` | Container.java:131（各子类覆写） | shift 点击（slotClick mode 1 内部，Container.java:266） | 改写快捷移动路由（例如自定义排序目标） | 返回值必须在无移动时为 null，否则 retrySlotClick 死循环风格重试（Container.java:275） |
| `public abstract boolean canInteractWith(EntityPlayer playerIn)` | Container.java:590 | 服务端每 tick（EntityPlayer.java:335、EntityPlayerMP.java:284）及 processClickWindow 前置校验 | 控制窗口存活（距离/条件）；返回 false 即强制关窗 | 客户端不检查；恒 true 会让远距离容器保持打开 |
| `public boolean canMergeSlot(ItemStack stack, Slot slotIn)` | Container.java:500 | 双击收集（slotClick mode 6，Container.java:473） | 排除某些槽参与双击收集（合成输出槽已排除） | 仅影响 mode 6 |
| `protected boolean mergeItemStack(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection)` | Container.java:597 | 各子类 transferStackInSlot 内部 | 改写 shift 移动的填充策略 | **不调用 isItemValid**，越权塞入需自行校验 |
| `public void updateProgressBar(int id, int data)` | Container.java:554（各子类覆写） | 客户端收到 S31PacketWindowProperty（NetHandlerPlayClient.java:1293） | 观测/篡改服务端下发的进度值（熔炉进度、附魔选项、铁砧费用） | 仅客户端路径；id 含义随容器类型不同 |
| `public void putStacksInSlots(ItemStack[] p_75131_1_)` | Container.java:546 | 客户端收到 S30PacketWindowItems（NetHandlerPlayClient.java:1205/1209） | 观测整表同步（服务端强制刷新点） | 直接覆盖本地状态，客户端预测在此被纠正 |
| `public void putStackInSlot(int slotID, ItemStack stack)` | Container.java:538 | 客户端收到 S2FPacketSetSlot | 观测单槽服务端纠正 | 同上 |
| `public boolean isItemValid(ItemStack stack)` | Slot.java:73（子类广泛覆写） | 放入物品前（slotClick / mergeItemStack 部分路径 / 拖拽） | 自定义槽位准入规则 | mergeItemStack 不走它 |
| `public boolean canTakeStack(EntityPlayer playerIn)` | Slot.java:150 | 取出物品前（slotClick 各 mode） | 锁定槽位（如经验不足禁取，参考 ContainerRepair.java:71） | 客户端也要一致，否则显示与结果不符 |
| `public void onPickupFromSlot(EntityPlayer playerIn, ItemStack stack)` | Slot.java:65 | 从槽中拿走物品后 | 感知"取出"事件：合成完成、熔炼取出、交易达成的统一挂点 | 输出槽子类在此有副作用（扣材料/给经验/交易），覆盖必须链式调用 |
| `public void onSlotChanged()` | Slot.java:106 | 每次槽内容变化（putStack、mergeItemStack 等） | 最细粒度的槽变更观测点 | 高频；默认只做 `inventory.markDirty()` |
| `protected void onCrafting(ItemStack stack)` | SlotCrafting.java:69 | 合成品被拿出时 | 感知合成事件（含成就触发） | protected，需子类化挂钩 |
| `public void onCraftMatrixChanged(IInventory inventoryIn)` | Container.java:530（ContainerPlayer.java:75、ContainerWorkbench.java:54、ContainerRepair.java:146、ContainerEnchantment.java:145、ContainerMerchant.java:62 覆写） | 合成矩阵/输入槽任何变动（经 InventoryCrafting.java:136 或匿名 markDirty） | 拦截配方匹配结果：自定义配方、合成预览 | 客户端与服务端都会跑；附魔台版本仅服务端计算（`!worldPointer.isRemote`） |
| `public boolean enchantItem(EntityPlayer playerIn, int id)` | Container.java:103（ContainerEnchantment.java:243 覆写） | 服务端收到 C11PacketEnchantItem | 拦截附魔按钮（也是通用"容器按钮"通道） | 基类恒 false；仅服务端有实效 |
| `public void updateRepairOutput()` | ContainerRepair.java:159 | 铁砧输入变化、updateItemName | 改写铁砧费用/输出规则 | maximumCost>=40 截断规则藏在此处 |
| `public void updateItemName(String newName)` | ContainerRepair.java:486 | GuiRepair 击键 → MC|ItemName payload | 拦截改名 | 服务端未过滤字符串长度于此处（在包处理层） |
| `public void markDirty()` | InventoryBasic.java:225 | 该 inventory 任何写操作 | 通过 `addInventoryChangeListener(IInvBasic listener)`（InventoryBasic.java:37）无侵入观测存储变更 | 监听器列表懒初始化；`removeInventoryChangeListener` 在 null 列表上会 NPE |
| `public void resetRecipeAndSlots()` | InventoryMerchant.java:198 | 交易输入变化、翻页 | 改写交易匹配 | 会递归触发 setInventorySlotContents（槽 2 不在 reset 条件内，不会死循环） |
| `public static void dropInventoryItems(World worldIn, BlockPos pos, IInventory inventory)` | InventoryHelper.java:15 | 方块被破坏时（Block#breakBlock 调用方） | 拦截容器爆出物品 | 静态方法，只能字节码级挂钩 |

## 数据与协议

本包不直接读写封包，但它是 window 系协议的两端处理者。字段级对应关系：

### ICrafting 回调 ↔ 封包（ICrafting.java:11-26）

| 回调 | 对应封包 | 载荷 | 含义 |
|---|---|---|---|
| `updateCraftingInventory(Container containerToSend, List<ItemStack> itemsList)` | S30PacketWindowItems | windowId + ItemStack[] | 整表刷新，`onCraftGuiOpened` 时必发 |
| `sendSlotContents(Container containerToSend, int slotInd, ItemStack stack)` | S2FPacketSetSlot | windowId + slot + stack | 单槽差异，`detectAndSendChanges` 产生 |
| `sendProgressBarUpdate(Container containerIn, int varToUpdate, int newValue)` | S31PacketWindowProperty | windowId + var + value | 进度值；注释注明非本地 SMP 下截断为 short（ICrafting.java:22） |
| `sendAllWindowProperties(Container p_175173_1_, IInventory p_175173_2_)` | 多个 S31 | 遍历 `getFieldCount()` | 打开时全量推 field |

### 进度条 id 表（`updateProgressBar` 语义）

| 容器 | id | 含义 | 来源 |
|---|---|---|---|
| ContainerFurnace | 0/1/2/3 | furnaceBurnTime / currentItemBurnTime / cookTime / totalCookTime | ContainerFurnace.java:55-73（映射到 `tileFurnace.getField`） |
| ContainerBrewingStand | 0 | brewTime | ContainerBrewingStand.java:56-62 |
| ContainerEnchantment | 0-2 / 3 / 4-6 | enchantLevels / `xpSeed & -16` / enchantmentIds（`effectId \| level << 8`） | ContainerEnchantment.java:122-140 |
| ContainerRepair | 0 | maximumCost | ContainerRepair.java:395-401 |
| ContainerBeacon | 透传 | `tileBeacon.setField(id, data)` | ContainerBeacon.java:43-46 |
| ContainerMerchant | — | 空实现 | ContainerMerchant.java:73 |

### NBT（仅 InventoryEnderChest）

| 字段 | 类型 | 读 / 写方法 | 含义 |
|---|---|---|---|
| `"Slot"` | byte（`& 255` 读取） | `loadInventoryFromNBT(NBTTagList p_70486_1_)`（InventoryEnderChest.java:23）/ `saveInventoryToNBT()`（InventoryEnderChest.java:42） | 槽下标 0-26 |
| （余下键） | ItemStack NBT | `ItemStack.loadItemStackFromNBT` / `itemstack.writeToNBT` | 物品本体（id/Count/Damage/tag），存于玩家 NBT 的 EnderItems |

## 不变量与陷阱

- `inventorySlots` 与 `inventoryItemStacks` 必须等长且下标对齐——只能通过 `addSlotToContainer` 添加槽位（Container.java:35-40）。
- `slotNumber`（容器内 id，封包用）≠ `slotIndex`（inventory 内下标，私有）。跨容器复用 inventory 时（如 ContainerChest 的玩家背包区）两者不同，混用是经典 bug 源。
- `mergeItemStack` 不做 `isItemValid` 校验（Container.java:593-595 注释明示），子类 `transferStackInSlot` 必须自己保证目标区间合法。
- `transferStackInSlot` 在"没有移动任何东西"时必须返回 null；返回非 null 且槽内仍是同 Item 会触发 `retrySlotClick` 循环搬运（Container.java:273-276）。
- 服务端每 tick 的 `canInteractWith` 检查只在 `!worldObj.isRemote` 分支（EntityPlayer.java:335），客户端不自检；功能层若伪造窗口需自己维持一致。
- `ItemStack == null` 表示空槽（1.8.9 语义，没有 `ItemStack.EMPTY`），所有比较都要先判 null；`stackSize == 0` 的栈必须显式置 null（`slot.putStack((ItemStack)null)` 模式随处可见）。
- `InventoryCrafting.markDirty()` 是空的（InventoryCrafting.java:151），变更通知走 `eventHandler.onCraftMatrixChanged`；反过来附魔台/铁砧的输入 InventoryBasic 靠覆写 `markDirty()` 通知。两套机制并存，挂钩时别选错。
- `InventoryBasic.removeInventoryChangeListener` 未判 `changeListeners == null`（InventoryBasic.java:52-55），从未 add 过就 remove 会 NPE。
- `InventoryBasic.getStackInSlot` 有越界保护（InventoryBasic.java:60-63），但 `decrStackSize` / `removeStackFromSlot` / `setInventorySlotContents` 没有——传非法 index 直接 `ArrayIndexOutOfBoundsException`。
- `ContainerMerchant.onContainerClosed` 调了两次 `super.onContainerClosed(playerIn)`（ContainerMerchant.java:148-150），第二次是冗余但无害（浮动物品第一次已丢）。移植版保留了原版这个瑕疵。
- `ContainerPlayer` 的盔甲槽在本移植版用 `final int k_f = k;` 捕获循环变量供匿名类使用（ContainerPlayer.java:38），与原版 MCP 反编译（直接捕获 k）写法不同，行为一致。
- 线程安全：所有类无锁、非线程安全。禁止在 Netty EventLoop 直接触碰 Container/IInventory，必须经 `addScheduledTask` 调度回主线程（NetHandlerPlayClient 的包处理已统一这样做）。
- 拖拽分堆是三阶段状态机（start=0/add=1/end=2，`getDragEvent`，Container.java:695），中途任何非 mode 5 点击会 `resetDrag()`；模拟点击时事件序列必须完整。
- LWJGL3/JDK25 移植对本包无直接影响（无 GL/输入依赖）；输入差异全部被上游 `GuiContainer`/`PlayerControllerMP` 吸收。

## 交叉引用

- net.minecraft.entity.player → `EntityPlayer#inventoryContainer` / `#openContainer`（EntityPlayer.java:181-182，容器持有与每 tick canInteractWith 检查）
- net.minecraft.entity.player → `EntityPlayerMP implements ICrafting`（EntityPlayerMP.java:99；detectAndSendChanges 每 tick：EntityPlayerMP.java:282）
- net.minecraft.entity.player → `InventoryPlayer#getItemStack` / `#setItemStack`（slotClick 中的鼠标浮动物品）
- net.minecraft.network → `NetHandlerPlayServer#processClickWindow`（NetHandlerPlayServer.java:1007 → Container#slotClick）
- net.minecraft.client.network → `NetHandlerPlayClient#handleWindowItems` / `#handleSetSlot` / `#handleWindowProperty`（NetHandlerPlayClient.java:1198/1133/1286 → Container#putStacksInSlots / #putStackInSlot / #updateProgressBar）
- net.minecraft.client.multiplayer → `PlayerControllerMP#windowClick`（PlayerControllerMP.java:537 → Container#slotClick）
- net.minecraft.client.gui.inventory → `GuiContainer`（渲染 Slot 坐标）、`CreativeCrafting implements ICrafting`、`GuiRepair implements ICrafting`（GuiRepair.java:23 → ContainerRepair#updateItemName）
- net.minecraft.item.crafting → `CraftingManager#findMatchingRecipe` / `#func_180303_b`（ContainerPlayer.java:77、SlotCrafting.java:137）、`FurnaceRecipes#getSmeltingResult` / `#getSmeltingExperience`（ContainerFurnace.java:116、SlotFurnaceOutput.java:71）
- net.minecraft.enchantment → `EnchantmentHelper#calcItemStackEnchantability` / `#buildEnchantmentList`（ContainerEnchantment.java:203/311）、`#getEnchantments` / `#setEnchantments`（ContainerRepair.java:183/381）
- net.minecraft.tileentity → `TileEntityFurnace#isItemFuel`（SlotFurnaceFuel.java:19）、`TileEntityEnderChest#openChest` / `#closeChest`（InventoryEnderChest.java:74/84）
- net.minecraft.village → `MerchantRecipeList#canRecipeBeUsed`、`MerchantRecipe#getItemToSell` 等（InventoryMerchant.java:216-234、SlotMerchantResult.java:101-120）
- net.minecraft.entity → `IMerchant#getCustomer` / `#useRecipe` / `#verifySellingItem`（ContainerMerchant.java:79、SlotMerchantResult.java:82、InventoryMerchant.java:248）、`EntityHorse#isArmorItem` / `#canWearArmor`（ContainerHorseInventory.java:31）
- net.minecraft.world → `ILockableContainer` / `LockCode`（InventoryLargeChest.java:12）、`World#getBlockState` / `#isAirBlock`（附魔台数书架、铁砧/工作台距离校验）
- net.minecraft.stats → `AchievementList` / `StatList`（SlotCrafting.java:80-130、SlotFurnaceOutput.java:101、SlotMerchantResult.java:83、ContainerBrewingStand.java:195）
- net.minecraft.block → `BlockAnvil.DAMAGE`（ContainerRepair.java:108，取出成品时损坏铁砧）

## 覆盖声明

完整读取了 30/30 个文件（每个文件从第 1 行到末行）。

逐行精读：Container、Slot、IInventory、ICrafting、InventoryBasic、InventoryCrafting、InventoryMerchant、ContainerPlayer、ContainerRepair、ContainerEnchantment、SlotCrafting、SlotFurnaceOutput、SlotMerchantResult、InventoryEnderChest、InventoryLargeChest、ContainerFurnace、ContainerBrewingStand、ContainerBeacon。

完整读取但以结构性理解为主（逻辑高度模板化）：ContainerChest、ContainerDispenser、ContainerHopper、ContainerHorseInventory、ContainerMerchant、ContainerWorkbench、InventoryCraftResult、InventoryHelper、SlotFurnaceFuel、AnimalChest、IInvBasic、ISidedInventory。

行号引用均来自本仓库源码 Read 输出；跨包调用点（EntityPlayer.java:181、EntityPlayerMP.java:282、NetHandlerPlayServer.java:1007、NetHandlerPlayClient.java:1205 等）经 grep 逐一确认。
