---
area: net/minecraft/entity/player
slug: mc-entity-player
files: 5
lines: 4837
tier: A
---

# net/minecraft/entity/player

## 定位

本包是玩家实体的**公共基类层**：`EntityPlayer` 是所有玩家形态（客户端本地玩家 `EntityPlayerSP`、客户端远端玩家 `EntityOtherPlayerMP`、服务端玩家 `EntityPlayerMP`）的共同父类，承载物品使用、攻击、经验、饥饿、睡觉、背包、能力（飞行/创造）等与"玩家"绑定的全部游戏逻辑。`EntityPlayerMP` 也在本包内，是集成服务端侧的玩家实现，负责把状态变更翻译成 S 系列封包发给客户端。

- **谁调用它**：客户端侧由 `Minecraft`（输入分发）、`PlayerControllerMP`（交互/攻击）、`NetHandlerPlayClient`（封包应用）驱动；服务端侧由 `NetHandlerPlayServer`（C 系列封包处理）、`ServerConfigurationManager`（登录/重生/换维度）、`ItemInWorldManager`（挖掘/放置）驱动；世界 tick（`World.updateEntities`）像普通实体一样调 `onUpdate()`。
- **它调用谁**：`InventoryPlayer`/`Container`（背包与 GUI 容器）、`FoodStats`（饥饿）、`ItemStack`/`Item`（物品使用与攻击）、`EnchantmentHelper`（附魔加成）、`StatList`/`StatisticsFile`（统计与成就）、`Scoreboard`（计分板）、`NetHandlerPlayServer.sendPacket`（服务端→客户端封包）。
- **如果它消失**：玩家无法移动/攻击/使用物品/开背包，客户端渲染层（`RenderPlayer`）与 GUI 层（所有 `GuiContainer`）失去数据来源，服务端无法同步任何玩家状态——整个游戏不可玩。

继承链：`Entity` → `EntityLivingBase` → `EntityPlayer` → { `EntityPlayerMP`（服务端）, `AbstractClientPlayer` → `EntityPlayerSP` / `EntityOtherPlayerMP`（客户端，位于 `net/minecraft/client/entity`）}。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| `EntityPlayer` | 2518 | `extends EntityLivingBase`（abstract） | 玩家实体公共逻辑：物品使用、攻击、经验、睡觉、NBT 持久化、能力；含内部枚举 `EnumChatVisibility`、`EnumStatus` |
| `EntityPlayerMP` | 1297 | `extends EntityPlayer implements ICrafting` | 服务端玩家：把状态变化（血量/经验/容器/药水/GUI）转成封包发给客户端，管理区块发送与旁观 |
| `EnumPlayerModelParts` | 48 | `enum` | 皮肤外层部件（披风/外套/袖子/裤腿/帽子）的位掩码定义 |
| `InventoryPlayer` | 893 | `implements IInventory` | 玩家背包：36 格主背包 + 4 格盔甲 + 鼠标持有物，堆叠/存取/NBT 序列化 |
| `PlayerCapabilities` | 81 | （无） | 玩家能力开关：无敌、飞行、允许飞行、创造模式、允许编辑、飞行/行走速度，NBT 读写 |

## 核心类详解

### EntityPlayer（EntityPlayer.java:79）

关键字段：

- `public InventoryPlayer inventory`（EntityPlayer.java:82）— 玩家背包，构造时 `new InventoryPlayer(this)`。
- `private InventoryEnderChest theInventoryEnderChest`（EntityPlayer.java:83）— 末影箱，跨死亡保留（`clonePlayer` 直接引用转移，EntityPlayer.java:2197）。
- `public Container inventoryContainer` / `public Container openContainer`（EntityPlayer.java:88, 91）— 背包容器与当前打开的容器；`openContainer` 默认指向 `inventoryContainer`。
- `protected FoodStats foodStats`（EntityPlayer.java:94）— 饥饿系统，仅服务端 tick（EntityPlayer.java:393-395）。
- `protected int flyToggleTimer`（EntityPlayer.java:100）— 双击跳跃切飞行的窗口计时。
- `public int xpCooldown`（EntityPlayer.java:107）— 经验球吸收冷却。
- `public double chasingPosX/Y/Z, prevChasingPosX/Y/Z`（EntityPlayer.java:108-113）— 披风物理的追踪点，`onUpdate` 中以 0.25 系数插值（EntityPlayer.java:384-386）。
- `protected boolean sleeping` / `private int sleepTimer` / `public BlockPos playerLocation`（EntityPlayer.java:116-120）— 睡觉状态机。
- `private BlockPos spawnChunk; private boolean spawnForced`（EntityPlayer.java:126-131）— 床重生点。
- `public PlayerCapabilities capabilities`（EntityPlayer.java:137）。
- `public int experienceLevel; public int experienceTotal; public float experience; private int xpSeed`（EntityPlayer.java:140-152）— 经验条状态与附魔种子。
- `private ItemStack itemInUse; private int itemInUseCount`（EntityPlayer.java:157-162）— 长按右键使用中的物品与剩余 tick。
- `protected float speedOnGround = 0.1F; protected float speedInAir = 0.02F`（EntityPlayer.java:163-164）。
- `private final GameProfile gameProfile`（EntityPlayer.java:168）— 身份；`getName()` 即 `gameProfile.getName()`（EntityPlayer.java:2227-2230）。
- `public EntityFishHook fishEntity`（EntityPlayer.java:174）— 鱼钩引用。

DataWatcher 注册（`protected void entityInit()`，EntityPlayer.java:196-203）：ID 16 = byte（本包内未见读写方，见 openQuestions）、ID 17 = float（吸收血量，EntityPlayer.java:2343-2356）、ID 18 = int（分数，EntityPlayer.java:688-708）、ID 10 = byte（皮肤部件位掩码，`isWearing` EntityPlayer.java:2394-2397 读取，服务端由 `EntityPlayerMP.handleClientSettings` 写入）。

关键方法（签名逐字）：

- `public void onUpdate()`（EntityPlayer.java:266）— 每 tick：spectator 时 `noClip = true`；推进 `itemInUse` 倒计时（每 4 tick 出粒子，归零时 `onItemUseFinish()`）；睡觉计时；调 `super.onUpdate()`；服务端校验 `openContainer.canInteractWith(this)` 不通过则关容器（EntityPlayer.java:335-339）；披风追踪点插值；服务端 tick `foodStats` 并累加游玩时间统计；最后把 X/Z 钳制在 ±2.9999999E7（EntityPlayer.java:404-411）。
- `public void onLivingUpdate()`（EntityPlayer.java:597）— 每 tick：`flyToggleTimer` 递减；和平难度自然回血回饱食度；`inventory.decrementAnimations()`；服务端把移动速度属性基值设为 `capabilities.getWalkSpeed()`（EntityPlayer.java:622-625）；疾跑时 `jumpMovementFactor += speedInAir * 0.3`；更新 `cameraYaw/cameraPitch` 视角摆动；对周围 1 格（骑乘时并集扩 1 格）实体逐个 `collideWithPlayer`（触发拾取，EntityPlayer.java:656-680）。
- `public void setItemInUse(ItemStack stack, int duration)`（EntityPlayer.java:2102）/ `public void stopUsingItem()`（EntityPlayer.java:237）/ `public void clearItemInUse()`（EntityPlayer.java:247）/ `protected void onItemUseFinish()`（EntityPlayer.java:485）— 物品使用状态机；`onItemUseFinish` 调 `itemInUse.onItemUseFinish` 并把结果写回 `inventory.mainInventory[this.inventory.currentItem]`（EntityPlayer.java:495）。
- `public boolean isBlocking()`（EntityPlayer.java:258）— `isUsingItem() && getItemUseAction == EnumAction.BLOCK`；格挡时 `damageEntity` 把伤害折半（EntityPlayer.java:1160-1163）。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（EntityPlayer.java:1051）— 创造无敌短路；睡觉被打则醒；按难度缩放伤害（PEACEFUL 归零、EASY 减半+1、HARD ×1.5）。
- `protected void damageEntity(DamageSource damageSrc, float damageAmount)`（EntityPlayer.java:1156）— 格挡减伤 → `applyArmorCalculations` → `applyPotionDamageCalculations` → 扣吸收 → 扣血并记录 `CombatTracker`。
- `public void attackTargetEntityWithCurrentItem(Entity targetEntity)`（EntityPlayer.java:1305）— 近战主逻辑：`attackDamage` 属性 + 附魔加成；空中下落且非水中非致盲判定暴击 ×1.5（EntityPlayer.java:1333-1338）；击退、火焰附加、反伤/节肢附魔、耐久消耗；命中的 `EntityPlayerMP` 若 `velocityChanged` 则单发 `S12PacketEntityVelocity` 并回滚服务端速度（EntityPlayer.java:1365-1372）。
- `public boolean interactWith(Entity targetEntity)`（EntityPlayer.java:1220）— 右键实体：spectator 只开箱式 GUI；先 `targetEntity.interactFirst(this)`，失败再 `itemstack.interactWithEntity`；创造模式恢复物品数量。
- `public EntityItem dropOneItem(boolean dropAll)`（EntityPlayer.java:823）/ `public EntityItem dropItem(ItemStack droppedItem, boolean dropAround, boolean traceItem)`（EntityPlayer.java:836）— 丢物品；`dropItem` 设置 40 tick 拾取延迟并按朝向计算初速。
- `public float getToolDigEfficiency(Block p_180471_1_)`（EntityPlayer.java:900）— 挖掘速度：工具基础值 × 效率附魔（i*i+1）× 急迫/挖掘疲劳 ×（水中无水下速掘 ÷5）×（浮空 ÷5）。
- `public EntityPlayer.EnumStatus trySleep(BlockPos bedLocation)`（EntityPlayer.java:1494）/ `public void wakeUpPlayer(boolean immediately, boolean updateWorldFlag, boolean setSpawn)`（EntityPlayer.java:1607）— 睡觉状态机；`trySleep` 服务端校验维度/时间/距离/敌怪，成功后 `setSize(0.2F, 0.2F)` 并按床朝向摆放；`wakeUpPlayer` 恢复 `setSize(0.6F, 1.8F)` 并可设置重生点。
- `public void readEntityFromNBT(NBTTagCompound tagCompund)`（EntityPlayer.java:970）/ `public void writeEntityToNBT(NBTTagCompound tagCompound)`（EntityPlayer.java:1016）— 持久化，字段见"数据与协议"。
- `public void addExperience(int amount)`（EntityPlayer.java:1995）/ `public void addExperienceLevel(int levels)`（EntityPlayer.java:2036）/ `public int xpBarCap()`（EntityPlayer.java:2059）— 经验曲线：`>=30` 级为 `112 + (lvl-30)*9`，`>=15` 级为 `37 + (lvl-15)*5`，否则 `7 + lvl*2`。
- `public void addExhaustion(float p_71020_1_)`（EntityPlayer.java:2067）— 仅服务端且非无敌时累加到 `foodStats`。
- `public void clonePlayer(EntityPlayer oldPlayer, boolean respawnFromEnd)`（EntityPlayer.java:2172）— 重生/过末地时复制状态；末影箱与 `xpSeed`、DW10 无条件复制。
- `public void moveEntityWithHeading(float strafe, float forward)`（EntityPlayer.java:1788）— 飞行时临时把 `jumpMovementFactor` 换成 `capabilities.getFlySpeed() * (this.isSprinting() ? 2 : 1)`，落地后 `motionY *= 0.6`；随后 `addMovementStat`。
- `public void fall(float distance, float damageMultiplier)`（EntityPlayer.java:1929）— `capabilities.allowFlying` 为真时完全跳过摔落伤害。
- `public float getEyeHeight()`（EntityPlayer.java:2326）— 1.62F，睡觉 0.2F，潜行 -0.08F。
- `public abstract boolean isSpectator();`（EntityPlayer.java:2289）— 子类实现。
- `public static UUID getUUID(GameProfile profile)`（EntityPlayer.java:2361）/ `public static UUID getOfflineUUID(String username)`（EntityPlayer.java:2373）— 离线 UUID = `UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(Charsets.UTF_8))`。
- GUI 打开族在基类为空实现：`public void openEditSign(TileEntitySign signTile)`（EntityPlayer.java:1186）、`public void openEditCommandBlock(CommandBlockLogic cmdBlockLogic)`（EntityPlayer.java:1190）、`public void displayVillagerTradeGui(IMerchant villager)`（EntityPlayer.java:1194）、`public void displayGUIChest(IInventory chestInventory)`（EntityPlayer.java:1201）、`public void displayGUIHorse(EntityHorse horse, IInventory horseInventory)`（EntityPlayer.java:1205）、`public void displayGui(IInteractionObject guiOwner)`（EntityPlayer.java:1209）、`public void displayGUIBook(ItemStack bookStack)`（EntityPlayer.java:1216）——客户端在 `EntityPlayerSP` 覆写弹 GUI，服务端在 `EntityPlayerMP` 覆写发封包。
- 统计钩子同为空实现：`public void addStat(StatBase stat, int amount)`（EntityPlayer.java:1759）、`public void func_175145_a(StatBase p_175145_1_)`（EntityPlayer.java:1763）。

内部枚举：`EnumChatVisibility { FULL, SYSTEM, HIDDEN }`（EntityPlayer.java:2470，带 `ID_LOOKUP` 静态表）；`EnumStatus { OK, NOT_POSSIBLE_HERE, NOT_POSSIBLE_NOW, TOO_FAR_AWAY, OTHER_PROBLEM, NOT_SAFE }`（EntityPlayer.java:2509，`trySleep` 返回值）。

### EntityPlayerMP（EntityPlayerMP.java:99）

关键字段：

- `public NetHandlerPlayServer playerNetServerHandler`（EntityPlayerMP.java:107）— 所有发往该玩家的封包出口。
- `public final MinecraftServer mcServer`（EntityPlayerMP.java:110）；`public final ItemInWorldManager theItemInWorldManager`（EntityPlayerMP.java:113）— 游戏模式与挖掘管理，构造函数里互相绑定（`interactionManager.thisPlayerMP = this`，EntityPlayerMP.java:169）。
- `public final List<ChunkCoordIntPair> loadedChunks`（EntityPlayerMP.java:120）— 待发送区块队列，每 tick 最多发 10 个。
- `private final List<Integer> destroyedItemsNetCache`（EntityPlayerMP.java:121）— 待销毁实体 ID 队列，批量发 `S13PacketDestroyEntities`。
- `private float lastHealth; private int lastFoodLevel; private boolean wasHungry; private int lastExperience`（EntityPlayerMP.java:130-139）— 差量同步的"上次已发送值"。
- `private int respawnInvulnerabilityTicks = 60`（EntityPlayerMP.java:140）— 重生无敌帧。
- `private Entity spectatingEntity`（EntityPlayerMP.java:146）；`private int currentWindowId`（EntityPlayerMP.java:151）— 窗口 ID 在 1..100 循环（`this.currentWindowId = this.currentWindowId % 100 + 1;`，EntityPlayerMP.java:750-753）。
- `public boolean isChangingQuantityOnly`（EntityPlayerMP.java:157）；`public int ping`（EntityPlayerMP.java:158）；`public boolean playerConqueredTheEnd`（EntityPlayerMP.java:164）。

关键方法：

- `public EntityPlayerMP(MinecraftServer server, WorldServer worldIn, GameProfile profile, ItemInWorldManager interactionManager)`（EntityPlayerMP.java:166）— 在出生点保护区内随机落点、向上顶出碰撞体。由 `ServerConfigurationManager.createPlayerForUser`（ServerConfigurationManager.java:449）与 `recreatePlayerEntity`（ServerConfigurationManager.java:476）构造。
- `public void onUpdate()`（EntityPlayerMP.java:272）— **不调 super**：tick `theItemInWorldManager.updateBlockRemoving()`；递减无敌帧与 `hurtResistantTime`；`this.openContainer.detectAndSendChanges()`（容器差量同步的源头，EntityPlayerMP.java:282）；冲刷 `destroyedItemsNetCache`；从 `loadedChunks` 取最多 10 个已填充区块发 `S21PacketChunkData`/`S26PacketMapChunkBulk` + TileEntity 描述包；旁观跟随（贴到目标坐标，潜行退出）。
- `public void onUpdateEntity()`（EntityPlayerMP.java:380）— 这里才调 `super.onUpdate()`，由 `NetHandlerPlayServer.processPlayer` 在处理移动包时调用（NetHandlerPlayServer.java:272, 307, 347）；随后：地图物品发数据包；血量/饥饿变化发 `S06PacketUpdateHealth`；血量+吸收变化刷新计分板 health 目标；经验变化发 `S1FPacketSetExperience`；周期检查全生物群系成就。异常包进 `CrashReport`。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（EntityPlayerMP.java:545）— 加服务端规则：重生无敌帧内免伤（`outOfWorld` 除外）、PVP 开关、队伍友伤检查。
- `public void onDeath(DamageSource cause)`（EntityPlayerMP.java:489）— 死亡消息按队伍可见性广播、掉落背包、计分板 deathCount、combatTracker.reset()。
- `public void travelToDimension(int dimensionId)`（EntityPlayerMP.java:602）— 末地终点弹 `S2BPacketChangeGameState(4, 0.0F)`（通关动画）并置 `playerConqueredTheEnd`；否则委托 `ServerConfigurationManager.transferPlayerToDimension` 并重置差量缓存。
- GUI 族覆写：`public void displayGui(IInteractionObject guiOwner)`（EntityPlayerMP.java:755）、`public void displayGUIChest(IInventory chestInventory)`（EntityPlayerMP.java:767，含 `ILockableContainer` 锁检查）、`public void displayVillagerTradeGui(IMerchant villager)`（EntityPlayerMP.java:803，附 `"MC|TrList"` 自定义载荷）、`public void displayGUIHorse(EntityHorse horse, IInventory horseInventory)`（EntityPlayerMP.java:823）、`public void displayGUIBook(ItemStack bookStack)`（EntityPlayerMP.java:840，`"MC|BOpen"`）、`public void openEditSign(TileEntitySign signTile)`（EntityPlayerMP.java:741）——模式统一为 `getNextWindowId()` → 发 `S2DPacketOpenWindow` → 换 `openContainer` → `onCraftGuiOpened(this)`。
- `ICrafting` 实现：`public void sendSlotContents(Container containerToSend, int slotInd, ItemStack stack)`（EntityPlayerMP.java:854，发 `S2FPacketSetSlot`，跳过 `SlotCrafting`）、`public void updateCraftingInventory(Container containerToSend, List<ItemStack> itemsList)`（EntityPlayerMP.java:873，发 `S30PacketWindowItems`）、`public void sendProgressBarUpdate(Container containerIn, int varToUpdate, int newValue)`（EntityPlayerMP.java:884，发 `S31PacketWindowProperty`）、`public void sendAllWindowProperties(Container p_175173_1_, IInventory p_175173_2_)`（EntityPlayerMP.java:889）。
- `public void closeScreen()`（EntityPlayerMP.java:900）— 发 `S2EPacketCloseWindow` 后 `closeContainer()`；`public void closeContainer()`（EntityPlayerMP.java:920）— `openContainer.onContainerClosed(this)` 并复位为 `inventoryContainer`。
- `public void addStat(StatBase stat, int amount)`（EntityPlayerMP.java:948）— 写 `statsFile` 并同步计分板。
- `public void setGameType(WorldSettings.GameType gameType)`（EntityPlayerMP.java:1105）— 改 `theItemInWorldManager`，发 `S2BPacketChangeGameState(3, id)`，spectator 时下坐骑，然后 `sendPlayerAbilities()`。
- `public boolean isSpectator()`（EntityPlayerMP.java:1126）— `theItemInWorldManager.getGameType() == WorldSettings.GameType.SPECTATOR`。
- `public void handleClientSettings(C15PacketClientSettings packetIn)`（EntityPlayerMP.java:1177）— 收客户端设置：语言、聊天可见性、颜色、皮肤部件位掩码写入 DW10。
- `public void setSpectatingEntity(Entity entityToSpectate)`（EntityPlayerMP.java:1256）— 发 `S43PacketCamera` 并传送到目标。
- `public void sendPlayerAbilities()`（EntityPlayerMP.java:1088）— 发 `S39PacketPlayerAbilities(this.capabilities)`。
- 药水同步：`protected void onNewPotionEffect(PotionEffect id)`（EntityPlayerMP.java:1046）/ `protected void onChangedPotionEffect(PotionEffect id, boolean p_70695_2_)`（EntityPlayerMP.java:1052）→ `S1DPacketEntityEffect`；`protected void onFinishedPotionEffect(PotionEffect effect)`（EntityPlayerMP.java:1058）→ `S1EPacketRemoveEntityEffect`。
- `public void removeEntity(Entity p_152339_1_)`（EntityPlayerMP.java:1220）— 玩家立即发销毁包，其余进 `destroyedItemsNetCache` 攒批。
- `public void setPositionAndUpdate(double x, double y, double z)`（EntityPlayerMP.java:1067）— 服务端强制传送即 `playerNetServerHandler.setPlayerLocation(...)`。

### InventoryPlayer（InventoryPlayer.java:19）

关键字段：`public ItemStack[] mainInventory = new ItemStack[36];`（InventoryPlayer.java:24）、`public ItemStack[] armorInventory = new ItemStack[4];`（InventoryPlayer.java:27）、`public int currentItem;`（InventoryPlayer.java:30，0-8）、`public EntityPlayer player;`（InventoryPlayer.java:33）、`private ItemStack itemStack;`（InventoryPlayer.java:34，鼠标持有物）、`public boolean inventoryChanged;`（InventoryPlayer.java:40，只置真不置假）。

关键方法：

- `public ItemStack getCurrentItem()`（InventoryPlayer.java:50）— `currentItem` 越界返回 null。
- `public static int getHotbarSize()`（InventoryPlayer.java:58）— 恒 9。
- `public void changeCurrentItem(int direction)`（InventoryPlayer.java:165）— 滚轮切槽，方向被钳制到 ±1，模 9 循环；由 `EntityPlayerSP`/`Minecraft` 滚轮输入调用。
- `public boolean addItemStackToInventory(final ItemStack itemStackIn)`（InventoryPlayer.java:397）— 拾取入包：已损坏物品只找空格；否则 `storePartialItemStack` 循环合堆；创造模式失败时也吞掉（`stackSize = 0` 返回 true）；异常包 `ReportedException`。
- `private int storePartialItemStack(ItemStack itemStackIn)`（InventoryPlayer.java:295）— 合堆并设 `animationsToGo = 5`（拾取动画）。
- `public ItemStack decrStackSize(int index, int count)`（InventoryPlayer.java:475）/ `public ItemStack removeStackFromSlot(int index)`（InventoryPlayer.java:514）/ `public void setInventorySlotContents(int index, ItemStack stack)`（InventoryPlayer.java:539）— 统一寻址：`index >= 36` 落到 `armorInventory[index - 36]`。
- `public int clearMatchingItems(Item itemIn, int metadataIn, int removeCount, NBTTagCompound itemNBT)`（InventoryPlayer.java:196）— `/clear` 命令后端，依次清主背包、盔甲、鼠标持有物。
- `public void decrementAnimations()`（InventoryPlayer.java:352）— 每 tick 对每个槽调 `updateAnimation`（物品 tick，如地图更新），由 `EntityPlayer.onLivingUpdate` 调用（EntityPlayer.java:617）。
- `public NBTTagList writeToNBT(NBTTagList nbtTagListIn)`（InventoryPlayer.java:570）/ `public void readFromNBT(NBTTagList nbtTagListIn)`（InventoryPlayer.java:602）— Slot 字节编码：主背包 0-35，盔甲 100-103；`readFromNBT` 先重建数组再填充。
- `public int getSizeInventory()`（InventoryPlayer.java:631）— `mainInventory.length + 4` = 40。
- `public int getTotalArmorValue()`（InventoryPlayer.java:710）— 累加 `ItemArmor.damageReduceAmount`。
- `public void damageArmor(float damage)`（InventoryPlayer.java:729）— `damage / 4`（最小 1）对四件盔甲各扣耐久。
- `public void dropAllItems()`（InventoryPlayer.java:755）— 死亡掉落，逐槽 `player.dropItem(stack, true, false)`。
- `public void copyInventory(InventoryPlayer playerInventory)`（InventoryPlayer.java:852）— 重生复制（深拷贝每个 ItemStack）。
- `public boolean isUseableByPlayer(EntityPlayer player)`（InventoryPlayer.java:804）— 距离平方 ≤ 64。

### PlayerCapabilities（PlayerCapabilities.java:5）

字段：`public boolean disableDamage;`、`public boolean isFlying;`、`public boolean allowFlying;`、`public boolean isCreativeMode;`、`public boolean allowEdit = true;`（PlayerCapabilities.java:8-22）、`private float flySpeed = 0.05F;`、`private float walkSpeed = 0.1F;`（PlayerCapabilities.java:23-24）。

方法：`public void writeCapabilitiesToNBT(NBTTagCompound tagCompound)`（PlayerCapabilities.java:26）/ `public void readCapabilitiesFromNBT(NBTTagCompound tagCompound)`（PlayerCapabilities.java:39）——写入/读取 `"abilities"` 子标签；`public float getFlySpeed()`（PlayerCapabilities.java:62）、`public void setFlySpeed(float speed)`（PlayerCapabilities.java:67）、`public float getWalkSpeed()`（PlayerCapabilities.java:72）、`public void setPlayerWalkSpeed(float speed)`（PlayerCapabilities.java:77）。

注意：`walkSpeed` 每 tick 被服务端写回移动速度属性（EntityPlayer.java:622-625），改它即改移动速度；`S39PacketPlayerAbilities` 双向同步该对象。

### EnumPlayerModelParts（EnumPlayerModelParts.java:6）

七个常量：`CAPE(0, "cape")` 到 `HAT(6, "hat")`（EnumPlayerModelParts.java:8-14）。`partMask = 1 << partIdIn`（EnumPlayerModelParts.java:24）。`public int getPartMask()`（EnumPlayerModelParts.java:29）、`public int getPartId()`（EnumPlayerModelParts.java:34）、`public String getPartName()`（EnumPlayerModelParts.java:39）、`public IChatComponent func_179326_d()`（EnumPlayerModelParts.java:44，本地化名 `"options.modelPart." + partNameIn`）。消费方：`GameSettings`（客户端设置持久化）、`GuiCustomizeSkin`（皮肤自定义界面）、`RenderPlayer`/`LayerCape`（渲染判断 `isWearing`）。

## 时序与生命周期

- **构造**：`EntityPlayer(World, GameProfile)`（EntityPlayer.java:176）设置 UUID（`getUUID(gameProfileIn)`）、创建 `ContainerPlayer` 并令 `openContainer = inventoryContainer`、落到世界出生点。服务端由 `ServerConfigurationManager` 在登录（ServerConfigurationManager.java:449）和重生（ServerConfigurationManager.java:476）时构造 `EntityPlayerMP`；重生走 `clonePlayer` 复制旧实体状态。
- **每 tick（服务端玩家，两段式）**：
  1. `EntityPlayerMP.onUpdate()`（EntityPlayerMP.java:272）由世界实体 tick 驱动——只做网络侧工作（容器差量、区块发送、销毁包、旁观跟随），**不含移动物理**。
  2. `EntityPlayerMP.onUpdateEntity()`（EntityPlayerMP.java:380）由 `NetHandlerPlayServer.processPlayer`（NetHandlerPlayServer.java:272, 307, 347）在收到 `C03PacketPlayer` 系移动包时调用，内部才执行 `super.onUpdate()`（即 `EntityPlayer.onUpdate` → `EntityLivingBase.onUpdate` → `onLivingUpdate`）并做血量/经验差量同步。即：**服务端玩家的实体逻辑 tick 由客户端移动包驱动**。
- **每 tick（客户端）**：`EntityPlayerSP`/`EntityOtherPlayerMP` 走 `EntityPlayer.onUpdate()` → `onLivingUpdate()` 全链；`isRemote` 分支跳过 foodStats tick、容器校验、walkSpeed 写回。
- **每帧**：本包无逐帧逻辑；渲染层读取 `prevCameraYaw/cameraYaw`、`prevChasingPos*/chasingPos*`、`renderOffsetX/Y/Z` 做插值（披风、睡觉偏移）。
- **死亡/重生**：`onDeath`（EntityPlayer.java:713 / EntityPlayerMP.java:489）→ 服务端 `ServerConfigurationManager.recreatePlayerEntity` 新建实体 → `clonePlayer`（EntityPlayer.java:2172 / EntityPlayerMP.java:1037）→ `preparePlayerToSpawn`（EntityPlayer.java:578）。
- **线程归属**：`EntityPlayer` 子类逻辑分别归属客户端主线程（`EntityPlayerSP` 等）与服务端线程（`EntityPlayerMP`）。`playerNetServerHandler.sendPacket` 从服务端线程调用，Netty EventLoop 负责实际写出。C 系封包处理经 `PacketThreadUtil` 调度回服务端线程后才触达本包，**本包代码不应在 Netty 线程直接执行**。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void onUpdate()` | EntityPlayer.java:266 | 每实体 tick（客户端主线程 / 服务端线程） | 玩家级 pre/post tick 事件、物品使用监控、移动修改 | 客户端与服务端子类共用；`EntityPlayerMP.onUpdate` 不调 super，别假设两侧对称 |
| `public void onLivingUpdate()` | EntityPlayer.java:597 | 每 tick，`onUpdate` 链内 | 速度修改（服务端此处写回 walkSpeed）、视角摆动、碰撞拾取拦截 | 改 `jumpMovementFactor` 会影响空中加速；碰撞循环里删实体要小心并发修改 |
| `public void attackTargetEntityWithCurrentItem(Entity targetEntity)` | EntityPlayer.java:1305 | 客户端 `PlayerControllerMP.attackEntity`（PlayerControllerMP.java:502）；服务端 `NetHandlerPlayServer`（NetHandlerPlayServer.java:935） | 攻击事件、伤害修改、暴击条件改写、reach 之外的攻击逻辑 | 服务端版本先检查 spectator（EntityPlayerMP.java:1272）；暴击条件含 `!this.isInWater()` 等硬编码 |
| `public boolean attackEntityFrom(DamageSource source, float amount)` | EntityPlayer.java:1051 / EntityPlayerMP.java:545 | 受击时 | 免伤规则、伤害缩放、受击事件 | MP 版叠加重生无敌与 PVP 检查；返回 false 即完全吞掉 |
| `protected void damageEntity(DamageSource damageSrc, float damageAmount)` | EntityPlayer.java:1156 | `attackEntityFrom` 通过后 | 精确修改最终伤害（护甲/药水/吸收之后） | 格挡折半在最前；此处不发包，同步靠 `onUpdateEntity` 差量 |
| `public boolean interactWith(Entity targetEntity)` | EntityPlayer.java:1220 | 右键实体（PlayerControllerMP.java:513；NetHandlerPlayServer.java:920） | 实体交互事件、取消交互 | 创造模式会回滚物品数量，覆写时保持该语义 |
| `public void setItemInUse(ItemStack stack, int duration)` | EntityPlayer.java:2102 | `Item.onItemRightClick`（如 ItemBow.java:125、ItemFood.java:99） | 监听开始使用物品（拉弓/吃食物） | MP 覆写会广播吃东西动画（EntityPlayerMP.java:1023） |
| `public void stopUsingItem()` / `protected void onItemUseFinish()` | EntityPlayer.java:237 / EntityPlayer.java:485 | 松开右键 / 使用计满 | 监听放箭、吃完；改写消耗结果 | `onItemUseFinish` 直接写 `inventory.mainInventory[currentItem]`；MP 版先发 `S19PacketEntityStatus(9)` |
| `public EntityItem dropOneItem(boolean dropAll)` | EntityPlayer.java:823 | 按 Q（Minecraft.java:2104）；服务端收 C07（NetHandlerPlayServer.java:504, 512） | 丢弃事件、防丢保护 | 客户端 `EntityPlayerSP` 有覆写（EntityPlayerSP.java:279），两侧都要挂 |
| `public void jump()` | EntityPlayer.java:1770 | 起跳帧 | 跳跃事件、消耗修改 | 疾跑跳消耗 0.8 饥饿度在此 |
| `public void moveEntityWithHeading(float strafe, float forward)` | EntityPlayer.java:1788 | 每移动 tick | 飞行速度修改、移动统计拦截 | 飞行分支临时改 `jumpMovementFactor`，覆写需还原 |
| `public EntityPlayer.EnumStatus trySleep(BlockPos bedLocation)` | EntityPlayer.java:1494 | 右键床（BlockBed.java:70）；客户端收 S0A（NetHandlerPlayClient.java:906） | 睡觉条件改写 | 成功路径会 `setSize(0.2F, 0.2F)` |
| `public void wakeUpPlayer(boolean immediately, boolean updateWorldFlag, boolean setSpawn)` | EntityPlayer.java:1607 / EntityPlayerMP.java:682 | 醒来（受击/白天/离床） | 醒来事件、重生点设置拦截 | MP 版广播动画并回传位置 |
| `protected void closeScreen()` / `public void closeScreen()` | EntityPlayer.java:538 / EntityPlayerMP.java:900 | 容器关闭 | GUI 关闭事件 | MP 版发 `S2EPacketCloseWindow`；基类只是复位引用 |
| `public void displayGui(IInteractionObject guiOwner)` 及 `displayGUIChest/displayVillagerTradeGui/displayGUIHorse/displayGUIBook/openEditSign` | EntityPlayer.java:1186-1218；EntityPlayerMP.java:741-848 | 交互方块/实体时 | GUI 打开事件、替换容器实现 | 服务端窗口 ID 循环 1..100；覆写必须保持 `onCraftGuiOpened` 调用否则客户端不收物品列表 |
| `public void onDeath(DamageSource cause)` | EntityPlayer.java:713 / EntityPlayerMP.java:489 | 血量归 0 | 死亡事件、keepInventory 类逻辑 | `keepInventory` gamerule 已内置；MP 版还处理死亡消息与计分板 |
| `public void clonePlayer(EntityPlayer oldPlayer, boolean respawnFromEnd)` | EntityPlayer.java:2172 / EntityPlayerMP.java:1037 | 重生新实体构造后 | 自定义数据跨死亡迁移 | 新旧是两个实体对象，指针缓存全部失效 |
| `public void onUpdateEntity()` | EntityPlayerMP.java:380 | 服务端收移动包时（NetHandlerPlayServer.java:272, 307, 347） | 服务端玩家真正的 tick 入口；差量同步拦截 | 客户端不发移动包则不执行——挂机检测/反作弊要考虑这点 |
| `public void handleClientSettings(C15PacketClientSettings packetIn)` | EntityPlayerMP.java:1177 | 收客户端设置包 | 语言/皮肤部件感知 | 写 DW10，渲染层立即生效 |
| `public void setGameType(WorldSettings.GameType gameType)` | EntityPlayerMP.java:1105 | /gamemode 等 | 模式切换事件 | 会级联 `sendPlayerAbilities` 与药水元数据刷新 |
| `public void setSpectatingEntity(Entity entityToSpectate)` | EntityPlayerMP.java:1256 | 旁观左键实体 / 退出 | 相机控制（S43PacketCamera） | 传 null 表示回到自身 |
| `public void sendPlayerAbilities()` | EntityPlayerMP.java:1088 | 能力变化后（飞行开关、模式切换） | 拦截/伪造能力同步 | 客户端对应处理在 `NetHandlerPlayClient`，双向都有包（C13/S39） |
| `public void addStat(StatBase stat, int amount)` | EntityPlayer.java:1759 / EntityPlayerMP.java:948 | 各类行为统计 | 行为埋点（挖掘、移动、击杀全经过这里） | 基类为空实现，客户端侧默认无统计 |
| `public boolean addItemStackToInventory(final ItemStack itemStackIn)` | InventoryPlayer.java:397 | 拾取/给予物品 | 拾取过滤、整理逻辑 | 会原地修改传入 stack 的 `stackSize`；创造模式失败也返回 true |
| `public void changeCurrentItem(int direction)` | InventoryPlayer.java:165 | 滚轮切换快捷栏 | 切槽事件 | 方向语义反直觉：正值使 `currentItem` 递减 |
| `public void setInventorySlotContents(int index, ItemStack stack)` | InventoryPlayer.java:539 | 容器同步、命令 | 槽位写入监控 | index ≥ 36 映射到盔甲槽 |

## 数据与协议

### EntityPlayer NBT（`writeEntityToNBT` EntityPlayer.java:1016 / `readEntityFromNBT` EntityPlayer.java:970）

| 字段名 | 类型 | 读写方法 | 取值含义 |
|---|---|---|---|
| `Inventory` | TagList(10) | `inventory.writeToNBT` / `inventory.readFromNBT` | 全部物品，槽位编码见下 |
| `SelectedItemSlot` | int | `setInteger`/`getInteger` | 当前快捷栏索引 0-8 |
| `Sleeping` | boolean | `setBoolean`/`getBoolean` | 是否在床上；读档为真时立即 `wakeUpPlayer(true, true, false)` |
| `SleepTimer` | short | `setShort`/`getShort` | 睡觉计时 0-100 |
| `XpP` | float | `setFloat`/`getFloat` | 经验条进度 0-1 |
| `XpLevel` / `XpTotal` / `XpSeed` | int | `setInteger`/`getInteger` | 等级 / 总经验 / 附魔种子（读到 0 则随机重生成，EntityPlayer.java:984-987） |
| `Score` | int | `setInteger`/`getInteger` | 死亡画面分数（DW18） |
| `SpawnX/SpawnY/SpawnZ` | int×3 | `setInteger`/`getInteger` | 床重生点，三个键都存在才生效（`hasKey(..., 99)`） |
| `SpawnForced` | boolean | `setBoolean`/`getBoolean` | 跳过床有效性检查 |
| `foodStats` 系列 | — | `foodStats.writeNBT`/`readNBT` | 饥饿/饱和/耗竭（键在 FoodStats 内） |
| `abilities` | Compound | `capabilities.writeCapabilitiesToNBT`/`readCapabilitiesFromNBT` | 见下表 |
| `EnderItems` | TagList(10) | `theInventoryEnderChest.saveInventoryToNBT`/`loadInventoryFromNBT` | 末影箱 |
| `SelectedItem` | Compound | `itemstack.writeToNBT` | 当前手持物快照（只写不读） |
| `playerGameType`（仅 MP） | int | EntityPlayerMP.java:209-229 | 游戏模式 ID；`getForceGamemode()` 时被服务器默认值覆盖 |

### `abilities` 子标签（PlayerCapabilities.java:26-60）

| 字段名 | 类型 | 对应字段 | 含义 |
|---|---|---|---|
| `invulnerable` | boolean | `disableDamage` | 无敌 |
| `flying` | boolean | `isFlying` | 正在飞 |
| `mayfly` | boolean | `allowFlying` | 允许飞 |
| `instabuild` | boolean | `isCreativeMode` | 创造模式 |
| `mayBuild` | boolean | `allowEdit` | 允许改动方块（读取时需 `hasKey("mayBuild", 1)`） |
| `flySpeed` / `walkSpeed` | float | `flySpeed` / `walkSpeed` | 速度；两者必须同时存在才读（`hasKey("flySpeed", 99)` 守卫） |

### InventoryPlayer 槽位编码（InventoryPlayer.java:570-626）

| Slot 值 | 含义 |
|---|---|
| 0-35 | `mainInventory`（0-8 为快捷栏） |
| 100-103 | `armorInventory[Slot - 100]`（100=靴子 … 103=头盔） |

`replaceItemInInventory`（EntityPlayer.java:2407）使用另一套命令寻址：0-35 主背包、100-103 盔甲（含类型校验）、200+ 末影箱。

### DataWatcher（EntityPlayer.entityInit，EntityPlayer.java:196-203）

| ID | 类型 | 读写 | 含义 |
|---|---|---|---|
| 10 | byte | `isWearing`（EntityPlayer.java:2394）读；`handleClientSettings`（EntityPlayerMP.java:1182）、`clonePlayer`（EntityPlayer.java:2198）写 | `EnumPlayerModelParts` 位掩码 |
| 16 | byte | 本包内未发现读写方 | 见 openQuestions |
| 17 | float | `getAbsorptionAmount`/`setAbsorptionAmount`（EntityPlayer.java:2343-2356） | 吸收血量 |
| 18 | int | `getScore`/`setScore`/`addScore`（EntityPlayer.java:688-708） | 分数 |

### EntityPlayerMP 发出的主要封包

`S06PacketUpdateHealth`（血量/饥饿差量，EntityPlayerMP.java:403）、`S1FPacketSetExperience`（EntityPlayerMP.java:422）、`S21PacketChunkData`/`S26PacketMapChunkBulk`（区块，EntityPlayerMP.java:340-344）、`S13PacketDestroyEntities`（EntityPlayerMP.java:303, 1224）、`S2DPacketOpenWindow`/`S2EPacketCloseWindow`/`S2FPacketSetSlot`/`S30PacketWindowItems`/`S31PacketWindowProperty`（容器族）、`S39PacketPlayerAbilities`（EntityPlayerMP.java:1092）、`S1DPacketEntityEffect`/`S1EPacketRemoveEntityEffect`（药水）、`S0APacketUseBed`/`S0BPacketAnimation`（睡觉/动画）、`S42PacketCombatEvent`（EntityPlayerMP.java:257, 266）、`S43PacketCamera`（EntityPlayerMP.java:1263）、`S2BPacketChangeGameState`（模式/通关）、`S3FPacketCustomPayload`（`"MC|TrList"` EntityPlayerMP.java:819、`"MC|BOpen"` EntityPlayerMP.java:846）、`S48PacketResourcePackSend`（EntityPlayerMP.java:1192）、`S02PacketChat`（EntityPlayerMP.java:1008, 1136）。

## 不变量与陷阱

- **`openContainer` 永不为 null**：构造时指向 `inventoryContainer`，关闭只会复位不置空（EntityPlayer.java:538-541；EntityPlayerMP.java:920-924）。每 tick 服务端校验 `canInteractWith`，距离超限自动关闭（EntityPlayer.java:335-339）。
- **服务端玩家 tick 依赖客户端移动包**：`super.onUpdate()` 只在 `onUpdateEntity()` 里执行，而后者由 `NetHandlerPlayServer.processPlayer` 触发。客户端停止发包，服务端玩家的实体逻辑（含 foodStats、itemInUse 倒计时）就停摆。
- **`EntityPlayerMP.onUpdate` 不调 super**（EntityPlayerMP.java:272-378），覆写 `EntityPlayer.onUpdate` 的功能在服务端玩家上不会随世界 tick 执行。
- **睡觉改碰撞箱**：`trySleep` → `setSize(0.2F, 0.2F)`（EntityPlayer.java:1533），死亡同样（EntityPlayer.java:716）；醒来/重生恢复 `0.6F × 1.8F`。任何缓存包围盒的功能要监听这两处。
- **`inventoryChanged` 只置真**：`markDirty()`（InventoryPlayer.java:780-783）置位后无人清零，消费方要自己复位。
- **`changeCurrentItem` 方向反转**：`direction > 0` 使 `currentItem` 递减（InventoryPlayer.java:177），即滚轮向上选左边的槽。
- **`addItemStackToInventory` 原地修改入参**：调用后 `itemStackIn.stackSize` 已被扣减；创造模式下失败也返回 true 且置零（InventoryPlayer.java:414-417, 439-443）。
- **窗口 ID 域为 1..100 循环**（EntityPlayerMP.java:752），功能层缓存 windowId 必须容忍复用。
- **重生 = 新对象**：`clonePlayer` 复制到全新 `EntityPlayerMP` 实例，任何以实体引用为键的缓存跨死亡即失效；末影箱是**引用共享**而非拷贝（EntityPlayer.java:2197）。
- **`capabilities.walkSpeed` 是速度的权威来源**：服务端每 tick `iattributeinstance.setBaseValue((double)this.capabilities.getWalkSpeed())`（EntityPlayer.java:622-625），直接改属性基值会被覆盖。
- **坐标钳制 ±2.9999999E7**（EntityPlayer.java:404-411），超界传送会被拉回。
- **线程安全**：本包无任何锁；`EntityPlayerMP` 的字段只能在服务端线程访问，`playerNetServerHandler.sendPacket` 是唯一允许的跨线程边界（内部由 Netty 调度）。不要从 Netty EventLoop 直接改玩家状态。
- **LWJGL3/JDK25 移植面**：本包不触 GL/GLFW，是纯逻辑层，移植风险低；`sendCommandFeedback()` 直接引用 `MinecraftServer.getServer().worldServers[0]`（EntityPlayer.java:2404），无服务器上下文（纯远程连接）时调用会 NPE，这是原版遗留而非移植引入。`getPlayerIP()` 解析 `getRemoteAddress().toString()` 的字符串格式（EntityPlayerMP.java:1169-1175），对 IPv6 地址的 `indexOf(":")` 截取不健壮。
- **`isChangingQuantityOnly` 抑制槽位包**（EntityPlayerMP.java:854-863, 909-915）：合成搬运期间置真，覆写容器同步逻辑时必须尊重该标志，否则客户端闪格。

## 交叉引用

- `net.minecraft.client.entity` → `AbstractClientPlayer extends EntityPlayer`（AbstractClientPlayer.java:21）；`EntityPlayerSP` 覆写 `dropOneItem`（EntityPlayerSP.java:279）等，是客户端本地玩家实现。
- `net.minecraft.client.multiplayer` → `PlayerControllerMP#attackEntity`（PlayerControllerMP.java:502 调 `attackTargetEntityWithCurrentItem`）、`PlayerControllerMP#interactWithEntitySendPacket`（PlayerControllerMP.java:513 调 `interactWith`）。
- `net.minecraft.client` → `Minecraft` 按键分发调 `thePlayer.dropOneItem(GuiScreen.isCtrlKeyDown())`（Minecraft.java:2104）。
- `net.minecraft.client.network` → `NetHandlerPlayClient#handleUseBed` 调 `trySleep`（NetHandlerPlayClient.java:906）。
- `net.minecraft.network` → `NetHandlerPlayServer#processPlayer` 调 `EntityPlayerMP#onUpdateEntity`（NetHandlerPlayServer.java:272, 307, 347）；`processPlayerDigging` 调 `dropOneItem`（NetHandlerPlayServer.java:504, 512）；`processUseEntity` 调 `interactWith` / `attackTargetEntityWithCurrentItem`（NetHandlerPlayServer.java:920, 935）。
- `net.minecraft.server.management` → `ServerConfigurationManager#createPlayerForUser` / `#recreatePlayerEntity` 构造 `EntityPlayerMP`（ServerConfigurationManager.java:449, 476）；`ItemInWorldManager` 与 `EntityPlayerMP` 双向引用（EntityPlayerMP.java:169）。
- `net.minecraft.inventory` → `Container#detectAndSendChanges` 经 `ICrafting`（`EntityPlayerMP` 实现）回调 `sendSlotContents` / `updateCraftingInventory` / `sendProgressBarUpdate`。
- `net.minecraft.item` → `ItemBow`/`ItemFood`/`ItemPotion` 等调 `EntityPlayer#setItemInUse`（ItemBow.java:125, ItemFood.java:99, ItemPotion.java:166）。
- `net.minecraft.block` → `BlockBed#onBlockActivated` 调 `EntityPlayer#trySleep`（BlockBed.java:70）。
- `net.minecraft.client.settings` / `net.minecraft.client.gui` → `GameSettings`、`GuiCustomizeSkin` 消费 `EnumPlayerModelParts`；`GuiPlayerTabOverlay` 读 `EntityPlayerMP#getTabListDisplayName` 语义。
- `net.minecraft.client.renderer.entity` → `RenderPlayer`、`LayerCape` 读 `EntityPlayer#isWearing`（DW10）与 `chasingPos*` 插值。
- `net.minecraft.util` → `FoodStats#onUpdate(EntityPlayer)`（EntityPlayer.java:395 调用）。
- `net.minecraft.scoreboard` → `Scoreboard#getObjectivesFromCriteria` 等在 `addToPlayerScore` / `addStat` / `onDeath` 中大量使用。

## 覆盖声明

完整读取了 5/5 个文件（EntityPlayer.java 2518 行、EntityPlayerMP.java 1297 行、InventoryPlayer.java 893 行、PlayerCapabilities.java 81 行、EnumPlayerModelParts.java 48 行，均全文逐行读取，无抽样）。逐行精读：全部 5 个类。仅结构性浏览：无。包外调用方（NetHandlerPlayServer、PlayerControllerMP、ServerConfigurationManager、BlockBed 等）仅通过 grep 定位调用行，未通读。
