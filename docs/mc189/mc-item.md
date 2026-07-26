---
area: net/minecraft/item
slug: mc-item
files: 72
lines: 8439
tier: B
---

# net/minecraft/item — 物品系统

## 定位

本包定义了客户端里所有"物品"的行为：`Item` 是全部物品类型的单例基类（每种物品全局只有一个 `Item` 实例，注册在 `Item.itemRegistry` 里），`ItemStack` 是运行时"一叠物品"的可变数据载体（数量、损伤值/meta、NBT）。其余 70 个文件是 `Item` 的具体子类（工具、食物、方块物品、投掷物、地图、药水等）与三个枚举（`EnumAction` / `EnumDyeColor` / `EnumRarity`）。

谁调用它：
- `net.minecraft.init.Bootstrap.register()` 在启动时调用 `Item.registerItems()`（Bootstrap.java:519）完成注册表填充；
- `PlayerControllerMP` 在玩家右键时调用 `ItemStack#onItemUse`（PlayerControllerMP.java:436/443）和 `ItemStack#useItemRightClick`（PlayerControllerMP.java:467）；
- `InventoryPlayer.decrementAnimations()`（InventoryPlayer.java:352）每 tick 调用 `ItemStack#updateAnimation` 驱动 `Item#onUpdate`；
- `EntityPlayer.stopUsingItem()`（EntityPlayer.java:237）触发 `onPlayerStoppedUsing`（弓释放等）；
- 渲染层 `RenderItem`（RenderItem.java:225 调 `getColorFromItemStack`）、`ItemRenderer`/`RenderPlayer`（读 `getItemUseAction` 决定手持动画）；
- GUI 层 `GuiScreen.renderToolTip`（GuiScreen.java:160）调 `ItemStack#getTooltip`。

它调用谁：`net.minecraft.block`（放置方块、查询 `IBlockState`）、`net.minecraft.entity`（生成投掷物/矿车/盔甲架等实体、属性修饰符）、`net.minecraft.nbt`（stack NBT 读写）、`net.minecraft.potion`（药水效果）、`net.minecraft.world`（`World#setBlockState`、`spawnEntityInWorld`）、`net.minecraft.stats`（成就/统计）、`net.minecraft.enchantment`（附魔查询）。

如果它消失：物品注册表为空 → 背包/合成/掉落物全部失效；`ItemStack` 是网络封包（S2FPacketSetSlot 等）、NBT 存档、容器系统的基础数据类型，整个物品栏与交互体系会崩溃。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| EnumAction | 10 | enum | 使用物品时的动画类型（NONE/EAT/DRINK/BLOCK/BOW） |
| EnumDyeColor | 102 | enum implements IStringSerializable | 16 种染料颜色，meta 与 dyeDamage 双向查表 |
| EnumRarity | 26 | enum | 物品稀有度与对应聊天颜色 |
| Item | 1035 | (根类) | 物品单例基类 + 静态注册表 + `registerItems()` 全量注册 |
| ItemAnvilBlock | 20 | extends ItemMultiTexture | 铁砧物品，`getMetadata(damage)` 返回 `damage << 2` |
| ItemAppleGold | 61 | extends ItemFood | 金苹果，meta>0 为附魔金苹果（EPIC + 强化 buff） |
| ItemArmor | 281 | extends Item | 盔甲：材质/部位/染色/发射器穿戴行为，内含 ArmorMaterial 枚举 |
| ItemArmorStand | 106 | extends Item | 放置盔甲架实体，应用 EntityTag NBT 与随机旋转 |
| ItemAxe | 22 | extends ItemTool | 斧，对木/植物/藤蔓材质用材料效率 |
| ItemBanner | 171 | extends ItemBlock | 旗帜：立/挂放置、Patterns NBT tooltip、底色取色 |
| ItemBed | 79 | extends Item | 放置床（FOOT+HEAD 两格） |
| ItemBlock | 179 | extends Item | 方块的物品形态：放置逻辑 + BlockEntityTag 写入 |
| ItemBoat | 114 | extends Item | 射线求交后在方块上生成 EntityBoat |
| ItemBook | 20 | extends Item | 普通书，可附魔（enchantability=1） |
| ItemBow | 138 | extends Item | 弓：蓄力发射 EntityArrow，处理力量/冲击/火矢/无限附魔 |
| ItemBucket | 166 | extends Item | 桶：取水/岩浆与倒出液体（isFull 标识内容物） |
| ItemBucketMilk | 61 | extends Item | 奶桶：喝完 `clearActivePotions()`，返还空桶 |
| ItemCarrotOnAStick | 62 | extends Item | 胡萝卜钓竿：骑猪加速，损耗 7 点后变钓鱼竿 |
| ItemCloth | 31 | extends ItemBlock | 羊毛/染色方块类，meta 直通 + 颜色后缀名 |
| ItemCoal | 32 | extends Item | 煤/木炭（meta 0/1 两个子类型） |
| ItemColored | 58 | extends ItemBlock | 需着色渲染的方块物品（草/藤蔓等），可设子类型名 |
| ItemDoor | 80 | extends Item | 放门（静态 `placeDoor` 决定铰链方向，上下两半） |
| ItemDoublePlant | 20 | extends ItemMultiTexture | 双格植物，GRASS/FERN 用草地色 `ColorizerGrass` |
| ItemDye | 187 | extends Item | 染料：骨粉催熟、可可豆种植、给羊染色，`dyeColors` 颜色表 |
| ItemEditableBook | 150 | extends Item | 成书：resolve pages JSON、书名显示、翻页 GUI |
| ItemEgg | 37 | extends Item | 投掷 EntityEgg |
| ItemEmptyMap | 47 | extends ItemMapBase | 右键创建新 MapData 并转为 filled_map |
| ItemEnchantedBook | 135 | extends Item | 附魔书：StoredEnchantments NBT 读写、宝箱随机生成 |
| ItemEnderEye | 177 | extends Item | 末影之眼：点亮传送门框架 + 补全门 + 投掷寻路实体 |
| ItemEnderPearl | 40 | extends Item | 投掷 EntityEnderPearl（创造模式不消耗且不投掷） |
| ItemExpBottle | 41 | extends Item | 投掷 EntityExpBottle，恒有附魔光效 |
| ItemFireball | 52 | extends Item | 火焰弹：在目标面点火 |
| ItemFirework | 79 | extends Item | 烟花火箭：对方块使用生成 EntityFireworkRocket |
| ItemFireworkCharge | 187 | extends Item | 烟花之星：Explosion NBT 取色与 tooltip |
| ItemFishFood | 170 | extends ItemFood | 鱼类食物：FishType 枚举按 meta 决定营养/河豚中毒 |
| ItemFishingRod | 77 | extends Item | 钓鱼竿：抛/收 EntityFishHook |
| ItemFlintAndSteel | 43 | extends Item | 打火石：目标面放火并损耗 1 点 |
| ItemFood | 144 | extends Item | 食物基类：EAT 动作 32 tick、营养/饱和/概率药水效果 |
| ItemGlassBottle | 66 | extends Item | 玻璃瓶：对水取样变水瓶（potionitem meta 0） |
| ItemHangingEntity | 66 | extends Item | 画/物品展示框：侧面放置 EntityHanging |
| ItemHoe | 98 | extends Item | 锄：草/泥土变耕地，粗泥变泥土 |
| ItemLead | 71 | extends Item | 拴绳：把拴住的生物系到栅栏（EntityLeashKnot） |
| ItemLeaves | 39 | extends ItemBlock | 树叶物品：`getMetadata` 返回 `damage | 4`（不衰减位） |
| ItemLilyPad | 72 | extends ItemColored | 睡莲：射线到水面上方放置 |
| ItemMap | 303 | extends ItemMapBase | 已填充地图：MapData 加载/逐列扫描更新颜色/缩放复制 |
| ItemMapBase | 21 | extends Item | 地图基类：`isMap()` 为 true，`createMapDataPacket` 默认 null |
| ItemMinecart | 128 | extends Item | 矿车：铁轨上生成 EntityMinecart，含发射器行为 |
| ItemMonsterPlacer | 220 | extends Item | 刷怪蛋：生成实体/改刷怪笼/对液体右键，蛋色查 EntityList |
| ItemMultiTexture | 55 | extends ItemBlock | 多子类型方块物品：nameFunction 由 meta 得名字后缀 |
| ItemNameTag | 37 | extends Item | 命名牌：给 EntityLiving 设名并 enablePersistence |
| ItemPickaxe | 30 | extends ItemTool | 镐：按材料 harvestLevel 决定可采集矿物 |
| ItemPiston | 20 | extends ItemBlock | 活塞物品：`getMetadata(damage)` 恒返回 7 |
| ItemPotion | 395 | extends Item | 药水：效果缓存、喷溅判断（meta&16384）、颜色/名称/tooltip |
| ItemRecord | 88 | extends Item | 唱片：插入唱片机，静态 RECORDS 名字查表 |
| ItemRedstone | 51 | extends Item | 红石粉：放置 redstone_wire |
| ItemReed | 72 | extends Item | "物品放方块"通用类（甘蔗/蛋糕/中继器/炼药锅等） |
| ItemSaddle | 49 | extends Item | 鞍：给猪装鞍 |
| ItemSeedFood | 47 | extends ItemFood | 可吃可种（胡萝卜/土豆）：耕地上放作物方块 |
| ItemSeeds | 48 | extends Item | 种子：指定 soil 上放作物 |
| ItemShears | 48 | extends Item | 剪刀：树叶/羊毛/蛛网特判挖速与采集 |
| ItemSign | 76 | extends Item | 告示牌：立/挂放置后 `openEditSign` 打开编辑 GUI |
| ItemSimpleFoiled | 9 | extends Item | 恒定附魔光效（下界之星） |
| ItemSkull | 199 | extends Item | 头颅：放置 TileEntitySkull、SkullOwner profile 解析 |
| ItemSlab | 132 | extends ItemBlock | 台阶：同半合并为双台阶的放置逻辑 |
| ItemSnow | 77 | extends ItemBlock | 雪层：同格叠层（LAYERS ≤ 7 时 +1） |
| ItemSnowball | 37 | extends Item | 投掷 EntitySnowball |
| ItemSoup | 24 | extends ItemFood | 汤：吃完返还碗 |
| ItemSpade | 24 | extends ItemTool | 锹：雪类可采集 |
| ItemStack | 1086 | final class（无继承） | 物品堆：数量/损伤/NBT/附魔/tooltip/属性修饰符 |
| ItemSword | 144 | extends Item | 剑：攻击伤害属性、BLOCK 格挡动作、蛛网速挖 |
| ItemTool | 106 | extends Item | 工具基类：有效方块集合、效率、耐久损耗、攻击属性 |
| ItemWritableBook | 61 | extends Item | 书与笔：打开书 GUI，pages NBT 校验 |

## 核心类详解

### Item（Item.java）

- 关键字段：
  - `public static final RegistryNamespaced<ResourceLocation, Item> itemRegistry`（Item.java:49）—— 全局物品注册表（数字 ID + 命名 ID 双索引）。
  - `private static final Map<Block, Item> BLOCK_TO_ITEM`（Item.java:50）—— Block → ItemBlock 映射，`getItemFromBlock` 查它。
  - `protected static final UUID itemModifierUUID = UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF")`（Item.java:51）—— 武器/工具攻击力 AttributeModifier 的固定 UUID。
  - `protected int maxStackSize = 64`（Item.java:58）、`private int maxDamage`（Item.java:61）、`protected boolean hasSubtypes`（Item.java:69）。
- 关键方法（签名逐字）：
  - `public boolean onItemUse(ItemStack stack, EntityPlayer playerIn, World worldIn, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ)`（Item.java:135）—— 对方块右键；由 `ItemStack#onItemUse` 转发（ItemStack.java:146）。
  - `public ItemStack onItemRightClick(ItemStack itemStackIn, World worldIn, EntityPlayer playerIn)`（Item.java:148）—— 空手方向右键；`PlayerControllerMP.sendUseItem` 路径（PlayerControllerMP.java:467）。
  - `public void onUpdate(ItemStack stack, World worldIn, Entity entityIn, int itemSlot, boolean isSelected)`（Item.java:343）—— 背包内每 tick；只有 ItemMap 有实质实现。
  - `public static void registerItems()`（Item.java:511）—— 一次性注册全部原版物品（方块物品 + ID 256..431、2256..2267 的独立物品）。
  - `protected MovingObjectPosition getMovingObjectPositionFromPlayer(World worldIn, EntityPlayer playerIn, boolean useLiquids)`（Item.java:437）—— 5 格射线求交，桶/瓶/睡莲/刷怪蛋共用。
  - 内部枚举 `public static enum ToolMaterial`（Item.java:982）：`WOOD(0, 59, 2.0F, 0.0F, 15), STONE(1, 131, 4.0F, 1.0F, 5), IRON(2, 250, 6.0F, 2.0F, 14), EMERALD(3, 1561, 8.0F, 3.0F, 10), GOLD(0, 32, 12.0F, 0.0F, 22)`（Item.java:984-988）。

### ItemStack（ItemStack.java）

- 关键字段：`public int stackSize`（ItemStack.java:41）、`public int animationsToGo`（ItemStack.java:46）、`private Item item`（ItemStack.java:47）、`private NBTTagCompound stackTagCompound`（ItemStack.java:52）、`private int itemDamage`（ItemStack.java:53）、`private EntityItemFrame itemFrame`（ItemStack.java:56）以及 canDestroy/canPlaceOn 各一对单元素缓存（ItemStack.java:57-60）。
- 关键方法：
  - `public NBTTagCompound writeToNBT(NBTTagCompound nbt)`（ItemStack.java:183）/ `public void readFromNBT(NBTTagCompound nbt)`（ItemStack.java:201）—— 存档与封包序列化基础（id/Count/Damage/tag）。
  - `public boolean attemptDamageItem(int amount, Random rand)`（ItemStack.java:302）—— 耐久损耗，Unbreaking 附魔逐点判定豁免；超过 maxDamage 返回 true。
  - `public void damageItem(int amount, EntityLivingBase entityIn)`（ItemStack.java:339）—— 创造模式免损；破损时 `renderBrokenItemStack` + 数量归零 + 统计。
  - `public void updateAnimation(World worldIn, Entity entityIn, int inventorySlot, boolean isCurrentItem)`（ItemStack.java:486）—— 每 tick 由 `InventoryPlayer.decrementAnimations()`（InventoryPlayer.java:358）调用，递减 `animationsToGo` 并转发 `Item#onUpdate`。
  - `public List<String> getTooltip(EntityPlayer playerIn, boolean advanced)`（ItemStack.java:644）—— 组装名字/附魔/Lore/属性修饰符/Unbreakable/CanDestroy/CanPlaceOn/耐久等全部 tooltip 行；支持 `HideFlags` 位掩码（1=ench, 2=modifiers, 4=Unbreakable, 8=CanDestroy, 16=CanPlaceOn, 32=addInformation）。
  - `public Multimap<String, AttributeModifier> getAttributeModifiers()`（ItemStack.java:967）—— NBT `AttributeModifiers` 优先，否则回落 `Item#getItemAttributeModifiers`。
  - `public boolean canDestroy(Block blockIn)`（ItemStack.java:1025）/ `public boolean canPlaceOn(Block blockIn)`（ItemStack.java:1056）—— 冒险模式限制判定，带单元素缓存。

### ItemBlock（ItemBlock.java）

- 字段：`protected final Block block`（ItemBlock.java:19）。
- `public boolean onItemUse(ItemStack stack, EntityPlayer playerIn, World worldIn, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ)`（ItemBlock.java:38）—— 标准放方块流程：replaceable 判断 → `canPlayerEdit` → `canBlockBePlaced` → `onBlockPlaced` 取状态 → `setBlockState(pos, iblockstate1, 3)` → `setTileEntityNBT` + `onBlockPlacedBy` → 播放放置声 → `--stack.stackSize`。
- `public static boolean setTileEntityNBT(World worldIn, EntityPlayer pos, BlockPos stack, ItemStack p_179224_3_)`（ItemBlock.java:83）—— 把 stack 的 `BlockEntityTag` 合并进新 TileEntity（注意参数名与语义错位是 MCP 反混淆遗留）。依赖 `MinecraftServer.getServer()` 非 null，纯客户端多人环境下直接返回 false。
- 名称、CreativeTab、子物品全部委托给 `this.block`（ItemBlock.java:146-173）。

### ItemPotion（ItemPotion.java）

- 字段：`private Map<Integer, List<PotionEffect>> effectCache`（ItemPotion.java:29）、`private static final Map<List<PotionEffect>, Integer> SUB_ITEMS_CACHE`（ItemPotion.java:30，static，跨实例共享）。
- `public List<PotionEffect> getEffects(ItemStack stack)`（ItemPotion.java:40）—— NBT `CustomPotionEffects` 优先，否则按 meta 走 `PotionHelper.getPotionEffects` 并缓存。
- `public static boolean isSplash(int meta)`（ItemPotion.java:174）—— `return (meta & 16384) != 0;`（ItemPotion.java:176），喷溅位。
- `public ItemStack onItemRightClick(ItemStack itemStackIn, World worldIn, EntityPlayer playerIn)`（ItemPotion.java:145）—— 喷溅药水直接掷出 `EntityPotion`，普通药水 `setItemInUse` 进入 32 tick DRINK。
- `public ItemStack onItemUseFinish(ItemStack stack, World worldIn, EntityPlayer playerIn)`（ItemPotion.java:91）—— 服务端侧施加效果，返还玻璃瓶。
- `getSubItems`（ItemPotion.java:339）遍历 meta 位组合（|8192 饮用 / |16384 喷溅，|32 强化 / |64 延长）填充创造模式列表。

### ItemArmor（ItemArmor.java）

- 字段：`public final int armorType`（ItemArmor.java:63，0=helmet…3=boots）、`public final int damageReduceAmount`（ItemArmor.java:66）、`public final int renderIndex`（ItemArmor.java:72）、`private final ItemArmor.ArmorMaterial material`（ItemArmor.java:75）；`private static final int[] maxDamageArray = new int[] {11, 16, 15, 13}`（ItemArmor.java:23）。
- 构造器把自身注册进 `BlockDispenser.dispenseBehaviorRegistry`（ItemArmor.java:86）：发射器可给实体穿戴（匿名 `dispenserBehavior`，ItemArmor.java:25-58）。
- `public int getColor(ItemStack stack)`（ItemArmor.java:135）—— 皮革读 `display.color`，默认 `10511680`；`public void setColor(ItemStack stack, int color)`（ItemArmor.java:183）对非皮革抛 `UnsupportedOperationException`。
- `public ItemStack onItemRightClick(ItemStack itemStackIn, World worldIn, EntityPlayer playerIn)`（ItemArmor.java:221）—— 空位直接穿上（copy 后原 stack 数量置 0）。
- `public static enum ArmorMaterial`（ItemArmor.java:235）：`LEATHER("leather", 5, new int[]{1, 3, 2, 1}, 15)` … `DIAMOND("diamond", 33, new int[]{3, 8, 6, 3}, 10)`（ItemArmor.java:237-241）。

### ItemMap（ItemMap.java）

- `public static MapData loadMapData(int mapId, World worldIn)`（ItemMap.java:31）—— 按 `"map_" + mapId` 从 world 存储加载/新建。
- `public void updateMapData(World worldIn, Entity viewer, MapData data)`（ItemMap.java:65）—— 核心扫描：以玩家为中心逐列采样区块高度图与 MapColor，写入 `data.colors[k1 + l1 * 128]` 并 `data.updateMapData(k1, l1)`（ItemMap.java:213-220）。
- `public void onUpdate(ItemStack stack, World worldIn, Entity entityIn, int itemSlot, boolean isSelected)`（ItemMap.java:235）—— 仅 `!worldIn.isRemote`（服务端/集成服）执行；手持选中时才扫描地形。
- `public Packet createMapDataPacket(ItemStack stack, World worldIn, EntityPlayer player)`（ItemMap.java:254）—— 由 `EntityPlayerMP.onUpdateEntity`（EntityPlayerMP.java:392）与 `EntityTrackerEntry`（EntityTrackerEntry.java:168，物品展示框内地图）调用，向客户端同步地图颜色。

### ItemBow（ItemBow.java）

- `public static final String[] bowPullIconNameArray = new String[] {"pulling_0", "pulling_1", "pulling_2"}`（ItemBow.java:14）—— 渲染层用的拉弓贴图名。
- `public void onPlayerStoppedUsing(ItemStack stack, World worldIn, EntityPlayer playerIn, int timeLeft)`（ItemBow.java:26）—— 蓄力公式 `f = (f * f + f * 2.0F) / 3.0F`（ItemBow.java:34），f<0.1 不发射；处理 power/punch/flame/infinity 附魔；服务端 `spawnEntityInWorld(entityarrow)`（ItemBow.java:88）。
- `public int getMaxItemUseDuration(ItemStack stack)`（ItemBow.java:105）返回 `72000`。

### ItemFood（ItemFood.java）

- 字段：`public final int itemUseDuration`（ItemFood.java:12，恒 32）、`private final int healAmount`（ItemFood.java:15）、`private final float saturationModifier`（ItemFood.java:16）、`private boolean alwaysEdible`（ItemFood.java:24）、potionId/Duration/Amplifier/Probability（ItemFood.java:29-38）。
- `public ItemStack onItemUseFinish(ItemStack stack, World worldIn, EntityPlayer playerIn)`（ItemFood.java:58）—— `playerIn.getFoodStats().addStats(this, stack)` + burp 音效 + `onFoodEaten`。
- `public ItemStack onItemRightClick(ItemStack itemStackIn, World worldIn, EntityPlayer playerIn)`（ItemFood.java:95）—— `playerIn.canEat(this.alwaysEdible)` 通过才 `setItemInUse`。

### ItemTool / ItemSword（ItemTool.java / ItemSword.java）

- ItemTool 字段：`private Set<Block> effectiveBlocks`（ItemTool.java:15）、`protected float efficiencyOnProperMaterial = 4.0F`（ItemTool.java:16）、`private float damageVsEntity`（ItemTool.java:19）、`protected Item.ToolMaterial toolMaterial`（ItemTool.java:22）。
- `public float getStrVsBlock(ItemStack stack, Block state)`（ItemTool.java:35）—— 有效集合内返回材料效率，否则 1.0F；挖掘速度计算入口。
- `public Multimap<String, AttributeModifier> getItemAttributeModifiers()`（ItemTool.java:100 / ItemSword.java:138）—— 以 `itemModifierUUID` 挂 attackDamage 修饰符（"Tool modifier"/"Weapon modifier"）。
- ItemSword：`attackDamage = 4.0F + material.getDamageVsEntity()`（ItemSword.java:26）；`getItemUseAction` 返回 `EnumAction.BLOCK`（ItemSword.java:86）实现格挡。

### ItemMonsterPlacer（ItemMonsterPlacer.java）

- `public static Entity spawnCreature(World worldIn, int entityID, double x, double y, double z)`（ItemMonsterPlacer.java:180）—— 由 `EntityList.createEntityByID` 创建、`onInitialSpawn` 初始化、`spawnEntityInWorld` 落地；也被发射器逻辑复用。
- `onItemUse`（ItemMonsterPlacer.java:56）对 `Blocks.mob_spawner` 特判：改写 `MobSpawnerBaseLogic.setEntityName`（ItemMonsterPlacer.java:77）。
- 蛋的双色来自 `EntityList.entityEggs`（ItemMonsterPlacer.java:49）。

## 时序与生命周期

- 初始化：`Bootstrap.register()`（Bootstrap.java:519）→ `Item.registerItems()`（Item.java:511）。先注册全部 ItemBlock（与 `Block.blockRegistry` 同 ID、同名），再注册 256 起的独立物品。必须晚于 Block 注册、早于 `Items` 类静态字段解析。之后注册表只读。
- 每 tick（客户端主线程 + 集成服务端线程各自跑）：`EntityPlayer.onUpdate` → `InventoryPlayer.decrementAnimations()`（InventoryPlayer.java:352）→ 每个非空槽位 `ItemStack.updateAnimation(...)`（ItemStack.java:486）→ `Item.onUpdate(...)`。地图扫描（ItemMap.onUpdate）只在 `!worldIn.isRemote` 端执行。
- 物品使用生命周期：右键 → `PlayerControllerMP.onPlayerRightClick`（对方块，PlayerControllerMP.java:436）或 `sendUseItem`（PlayerControllerMP.java:467）→ `Item#onItemUse` / `onItemRightClick`；若调用了 `EntityPlayer.setItemInUse(stack, duration)`（EntityPlayer.java:2102）则进入持续使用态，倒计时归零走 `ItemStack#onItemUseFinish`（吃完/喝完），中途松开走 `EntityPlayer.stopUsingItem()`（EntityPlayer.java:237）→ `onPlayerStoppedUsing`（弓在这里发射）。
- 每帧（渲染线程=主线程）：`RenderItem` 取 `getColorFromItemStack`（RenderItem.java:225）做 tint；`ItemRenderer`/`RenderPlayer` 读 `getItemUseAction` 选择第一/第三人称手持姿势；GUI 悬停时 `ItemStack#getTooltip`（GuiScreen.java:160）。
- 线程归属：本包代码全部运行在调用方线程 —— 客户端逻辑/渲染在主线程，世界修改类方法（onItemUse 等）在集成服务端线程也会被调用（`worldIn.isRemote` 区分）。没有任何类做同步；Netty EventLoop 不直接进入本包（封包在网络线程解码后调度回主线程才触碰 ItemStack）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public boolean onItemUse(ItemStack stack, EntityPlayer playerIn, World worldIn, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ)` | Item.java:135 | 玩家对方块右键（PlayerControllerMP.java:436/443 经 ItemStack.java:146 转发） | 拦截/替换任意物品的对方块交互；实现放置类功能（Scaffold 类模块的注入面） | 客户端与集成服两端都会调；返回 true 会触发统计与手臂挥动 |
| `public ItemStack onItemRightClick(ItemStack itemStackIn, World worldIn, EntityPlayer playerIn)` | Item.java:148 | 手持右键未命中方块（PlayerControllerMP.java:467） | 拦截投掷物/食物/弓的启用；自动使用类功能 | 返回值会整体替换手上 stack |
| `public ItemStack onItemUseFinish(ItemStack stack, World worldIn, EntityPlayer playerIn)` | Item.java:157 | 使用计时归零（吃完/喝完） | 观察进食完成、替换返还物 | 弓不走这里（它覆写为返回原 stack，ItemBow.java:97） |
| `public void onPlayerStoppedUsing(ItemStack stack, World worldIn, EntityPlayer playerIn, int timeLeft)` | Item.java:381 | 松开右键（EntityPlayer.java:241） | 弓类蓄力释放的观察/改写（FastBow 类） | timeLeft 是剩余 tick，蓄力 = maxDuration - timeLeft |
| `public void onUpdate(ItemStack stack, World worldIn, Entity entityIn, int itemSlot, boolean isSelected)` | Item.java:343 | 每 tick 每个背包槽位（InventoryPlayer.java:358） | 物品级 tick 逻辑注入点 | 每 tick × 每槽位调用，代价敏感 |
| `public void updateAnimation(World worldIn, Entity entityIn, int inventorySlot, boolean isCurrentItem)` | ItemStack.java:486 | 同上，Item.onUpdate 的上游 | 统一观察所有物品 tick；animationsToGo 拾取动画 | — |
| `public void onPlayerStoppedUsing(ItemStack stack, World worldIn, EntityPlayer playerIn, int timeLeft)`（弓实现） | ItemBow.java:26 | 拉弓松开 | 修改蓄力公式/附魔加成/箭属性 | `spawnEntityInWorld` 仅服务端（ItemBow.java:86-89） |
| `public boolean hitEntity(ItemStack stack, EntityLivingBase target, EntityLivingBase attacker)` | Item.java:216 | 用该物品击中实体（ItemStack.java:375 转发） | 攻击命中回调；武器耐久策略 | 返回 true 触发 objectUseStats |
| `public boolean onBlockDestroyed(ItemStack stack, World worldIn, Block blockIn, BlockPos pos, EntityLivingBase playerIn)` | Item.java:224 | 用该物品挖掉方块 | 挖掘完成回调；耐久策略 | 硬度 0 的方块工具不掉耐久（ItemTool.java:55） |
| `public float getStrVsBlock(ItemStack stack, Block state)` | Item.java:140（覆写：ItemTool.java:35、ItemSword.java:37、ItemShears.java:44、ItemPickaxe.java:26、ItemAxe.java:18） | 每次计算挖掘速度 | 改挖速（快挖类功能核心） | 每帧多次调用，需轻量 |
| `public boolean canHarvestBlock(Block blockIn)` | Item.java:232（覆写：ItemPickaxe.java:21 等） | 判定破坏后是否掉落 | 采集等级判定改写 | — |
| `public boolean itemInteractionForEntity(ItemStack stack, EntityPlayer playerIn, EntityLivingBase target)` | Item.java:240 | 右键实体（ItemStack.java:406 `interactWithEntity` 转发） | 拦截命名牌/染料/鞍等对实体交互 | — |
| `public int getColorFromItemStack(ItemStack stack, int renderPass)` | Item.java:334（覆写：ItemArmor.java:89、ItemPotion.java:184、ItemMonsterPlacer.java:47、ItemBanner.java:117 等） | 每帧物品渲染 tint（RenderItem.java:225） | 物品染色/高亮 | 渲染热路径 |
| `public EnumAction getItemUseAction(ItemStack stack)` | Item.java:365 | 每帧手持渲染 + 使用状态判断（EntityPlayer.java:260 格挡判断） | 改手持动画/伪格挡 | 影响 `isBlocking()` 语义 |
| `public int getMaxItemUseDuration(ItemStack stack)` | Item.java:373 | setItemInUse 时与蓄力计算 | 改使用时长（快吃） | 与服务端校验有关，仅客户端改会不同步 |
| `public List<String> getTooltip(EntityPlayer playerIn, boolean advanced)` | ItemStack.java:644 | GUI 悬停（GuiScreen.java:160） | 增删 tooltip 行（信息类 UI 功能） | 每帧悬停调用，会新建 List |
| `public void addInformation(ItemStack stack, EntityPlayer playerIn, List<String> tooltip, boolean advanced)` | Item.java:407 | getTooltip 内部（ItemStack.java:692，受 HideFlags&32 控制） | 物品级 tooltip 注入 | — |
| `public boolean attemptDamageItem(int amount, Random rand)` | ItemStack.java:302 | 一切耐久损耗 | 观察/取消耐久消耗 | 返回 true = 破损 |
| `public void damageItem(int amount, EntityLivingBase entityIn)` | ItemStack.java:339 | 上者的常用外壳 | 破损保护类功能挂点 | 创造模式内部已短路 |
| `public NBTTagCompound writeToNBT(NBTTagCompound nbt)` / `public void readFromNBT(NBTTagCompound nbt)` | ItemStack.java:183 / 201 | 存档、封包编解码（PacketBuffer 层调用） | 观察/篡改 stack 序列化（NBT 注入检测） | 格式见"数据与协议" |
| `public Multimap<String, AttributeModifier> getAttributeModifiers()` | ItemStack.java:967 | tooltip 与实体装备属性应用 | 改攻速/伤害显示与实际属性 | NBT 存在 `AttributeModifiers` 时完全覆盖物品默认值 |
| `public Packet createMapDataPacket(ItemStack stack, World worldIn, EntityPlayer player)` | ItemMap.java:254（基类 ItemMapBase.java:17） | 服务端向观察者同步地图（EntityPlayerMP.java:392、EntityTrackerEntry.java:168） | 地图内容拦截 | 仅服务端路径 |
| `public static Entity spawnCreature(World worldIn, int entityID, double x, double y, double z)` | ItemMonsterPlacer.java:180 | 刷怪蛋使用与发射器 | 实体生成拦截 | 服务端语境 |
| `public static void placeDoor(World worldIn, BlockPos pos, EnumFacing facing, Block door)` | ItemDoor.java:58 | 放门 | 门放置逻辑复用/拦截 | 直接两次 setBlockState + 邻居通知 |

## 数据与协议

ItemStack NBT 序列化（`writeToNBT` ItemStack.java:183 / `readFromNBT` ItemStack.java:201），同样用于 S2FPacketSetSlot 等封包内的 slot 编码：

| 字段 | NBT 类型 | 读写方法 | 含义 |
|---|---|---|---|
| `id` | String（写）；读兼容 short（旧档，ItemStack.java:209） | `nbt.setString("id", ...)` / `Item.getByNameOrId` | 物品命名 ID，如 `minecraft:apple`；null item 写 `"minecraft:air"` |
| `Count` | byte | `nbt.setByte("Count", (byte)this.stackSize)` | 堆叠数量 |
| `Damage` | short | `nbt.setShort("Damage", (short)this.itemDamage)` | 损伤值/meta，读入时负值钳为 0 |
| `tag` | Compound(10) | `nbt.setTag("tag", this.stackTagCompound)` | 自由 NBT；读入后回调 `Item#updateItemStackNBT`（ItemStack.java:226） |

stack `tag` 内本包直接消费的键：

| 键 | 类型 | 读写处 | 含义 |
|---|---|---|---|
| `ench` | List(9) of Compound{id:short, lvl:short} | ItemStack.java:562/880 | 附魔列表 |
| `display.Name` | String | ItemStack.java:578-608 | 自定义名 |
| `display.color` | Int(3) | ItemArmor.java:135-207 | 皮革甲颜色（默认 10511680） |
| `display.Lore` | List(9) of String | ItemStack.java:732 | Lore 行 |
| `HideFlags` | Int(99 校验) | ItemStack.java:685 | tooltip 位掩码（1/2/4/8/16/32） |
| `Unbreakable` | Boolean | ItemStack.java:252/786 | 不可损坏 |
| `RepairCost` | Int(3) | ItemStack.java:949-965 | 铁砧惩罚 |
| `AttributeModifiers` | List(9) of Compound | ItemStack.java:971-985 | 属性修饰符覆盖（AttributeName + SharedMonsterAttributes 序列化格式） |
| `CanDestroy` / `CanPlaceOn` | List(9) of String | ItemStack.java:1035/1066 | 冒险模式白名单（方块名） |
| `CustomPotionEffects` | List(9) of Compound | ItemPotion.java:42-58 | 自定义药水效果 |
| `StoredEnchantments` | List(9) of Compound{id, lvl} | ItemEnchantedBook.java:37-104 | 附魔书存储附魔 |
| `pages` / `title` / `author` / `generation` / `resolved` | List/String/String/Int/Boolean | ItemWritableBook.java:29-60、ItemEditableBook.java:27-144 | 书内容；title ≤ 32 字符，page ≤ 32767 字符 |
| `SkullOwner` | String(8) 或 Compound(10) GameProfile | ItemSkull.java:88-197 | 头颅所有者；String 形式会被 `updateItemStackNBT` 升级为 profile |
| `BlockEntityTag` | Compound | ItemBlock.java:93-108、ItemBanner.java:97、ItemSign.java:67 | 放置时合并进 TileEntity；旗帜的 `Base`/`Patterns[{Color,Pattern}]` 在其中 |
| `Explosion` / `Fireworks` | Compound | ItemFireworkCharge.java:57-70、ItemFirework.java:44-77 | 烟花参数：Type:byte、Colors/FadeColors:int[]、Trail/Flicker:boolean、Flight:byte、Explosions:List |
| `EntityTag` | Compound | ItemArmorStand.java:75-81 | 盔甲架实体初始 NBT |
| `map_is_scaling` | Boolean | ItemMap.java:264 | 触发地图放大复制 |

注册表：`Item.itemRegistry`（RegistryNamespaced，数字 ID ↔ ResourceLocation ↔ Item），封包里物品用数字 ID（`Item.getIdFromItem` / `getItemById`）。药水 meta 位编码：低 4 位效果基码，`|32` 强化、`|64` 延长、`|8192` 饮用位、`|16384` 喷溅位（ItemPotion.java:174-177、339-385）。

## 不变量与陷阱

- Item 是无状态单例：一个 `Item` 实例被所有同类 ItemStack 共享，绝不能把 per-stack 状态放进 Item 字段（ItemPotion.effectCache 是按 meta 的纯缓存，例外但只增不改）。
- 注册顺序不变量：`Item.registerItems()` 必须在 `Block` 注册之后（`registerItemBlock` 用 `Block.getIdFromBlock` 与 `Block.blockRegistry.getNameForObject`，Item.java:968）；整个流程由 Bootstrap 保证，且只能执行一次。
- `ItemStack.item` 可为 null（`loadItemStackFromNBT` 失败时返回 null，ItemStack.java:107），但许多方法（`getMaxStackSize` 等）不判 null 直接解引用 —— 调用前须确保非空。
- `isDamageable()` 要求 `maxDamage > 0 && !hasSubtypes`（Item.java:209）：给有耐久的物品设 subtypes 会静默关掉耐久系统。
- meta 与 damage 是同一个字段（`itemDamage`）；`Item.getMetadata(int damage)` 是"stack damage → 放置方块 meta"的转换（ItemPiston 恒 7、ItemAnvilBlock 左移 2、ItemLeaves `| 4`），别与 `stack.getMetadata()` 混淆。
- 双端执行：`onItemUse`/`onItemRightClick` 在客户端和集成服务端各跑一次，副作用必须用 `worldIn.isRemote` 守卫；本包多处（ItemBed、ItemFireball、ItemMonsterPlacer）在客户端直接 `return true` 只做预测。
- `ItemBlock.setTileEntityNBT` 依赖 `MinecraftServer.getServer()`（ItemBlock.java:85）—— 纯多人客户端上为 null，方法恒返回 false，BlockEntityTag 由服务端应用。
- `ItemArmor` / `ItemMinecart` 构造器有注册副作用（写 `BlockDispenser.dispenseBehaviorRegistry`，ItemArmor.java:86、ItemMinecart.java:88）；重复构造会重复注册。
- `ItemRecord` 构造器写静态 `RECORDS` map（ItemRecord.java:29），同名重复构造会覆盖。
- `ItemPotion.SUB_ITEMS_CACHE` 是 static 懒填充（ItemPotion.java:343），首次打开创造药水页会有一次 O(16×2×3) 的 PotionHelper 解析。
- 线程安全：`Item.itemRand` 是共享静态 `Random`（Item.java:55），无同步；所有 ItemStack 操作都假定单线程（各自世界的主 tick 线程）。不要从 Netty EventLoop 直接改 ItemStack。
- `ItemStack.canDestroy/canPlaceOn` 的单元素缓存按 Block 引用比较（ItemStack.java:1027），NBT 在运行中被改写后缓存不会失效。
- LWJGL3/JDK25 移植：本包无渲染/窗口代码，未见移植改动点；泛型原始类型（`new RegistryNamespaced()`、`(List)this.effectCache.get(...)`）与 MCP 风格代码在 JDK25 下仅产生编译警告。`@SuppressWarnings("incomplete-switch")` 出现在 ItemHoe.java:26（注解位置在 javadoc 之前，属反编译遗留风格）。
- `ItemStack` 是 `final class` 且无 equals/hashCode 覆写：比较必须用 `areItemStacksEqual`/`isItemEqual` 系列静态方法，把它放进 HashSet/HashMap 键是身份语义。

## 交叉引用

- net/minecraft/init → `Bootstrap#register`（调用 `Item.registerItems`，Bootstrap.java:519）；`Items` 类持有全部注册后实例的静态引用。
- net/minecraft/block → `Block#onBlockPlaced`、`Block#onBlockPlacedBy`、`Block#isReplaceable`、`Block.blockRegistry`（ItemBlock/ItemReed/ItemSlab 放置流程）；`BlockDispenser.dispenseBehaviorRegistry`（ItemArmor/ItemMinecart 注册发射行为）。
- net/minecraft/client/multiplayer → `PlayerControllerMP#onPlayerRightClick`（ItemStack#onItemUse）、`PlayerControllerMP#sendUseItem`（ItemStack#useItemRightClick）。
- net/minecraft/entity/player → `EntityPlayer#setItemInUse`、`EntityPlayer#stopUsingItem`（→ Item#onPlayerStoppedUsing）、`InventoryPlayer#decrementAnimations`（→ ItemStack#updateAnimation）、`EntityPlayerMP#onUpdateEntity`（→ ItemMapBase#createMapDataPacket）。
- net/minecraft/entity → `EntityList#createEntityByID` / `EntityList.entityEggs`（ItemMonsterPlacer）；`EntityLiving#getArmorPosition`（ItemArmor）；投掷物构造（EntityArrow/EntityEgg/EntitySnowball/EntityEnderPearl/EntityExpBottle/EntityPotion/EntityFishHook/EntityEnderEye/EntityFireworkRocket/EntityBoat/EntityMinecart/EntityArmorStand/EntityLeashKnot/EntityHanging）。
- net/minecraft/enchantment → `EnchantmentHelper#getEnchantmentLevel`（ItemStack#attemptDamageItem、ItemBow）；`EnchantmentDurability#negateDamage`。
- net/minecraft/potion → `PotionHelper#getPotionEffects` / `getLiquidColor` / `getPotionPrefix`（ItemPotion）；`Potion.potionTypes`。
- net/minecraft/nbt → `NBTTagCompound`/`NBTTagList`（ItemStack 序列化与全部 NBT 键）；`NBTUtil#readGameProfileFromNBT`（ItemSkull）。
- net/minecraft/world/storage → `MapData`（ItemMap/ItemEmptyMap 的 `World#loadItemData` / `setItemData`）。
- net/minecraft/tileentity → `TileEntitySkull#updateGameprofile`、`TileEntityBanner#setItemValues` / `EnumBannerPattern`、`TileEntitySign`、`TileEntityMobSpawner#getSpawnerBaseLogic`。
- net/minecraft/stats → `StatList.objectUseStats` / `objectBreakStats` / `objectCraftStats`（几乎所有使用路径的成就触发）。
- net/minecraft/client/renderer → `RenderItem#renderQuads`（Item#getColorFromItemStack，RenderItem.java:225）；`ItemRenderer` / `RenderPlayer`（ItemStack#getItemUseAction）。
- net/minecraft/client/gui → `GuiScreen#renderToolTip`（ItemStack#getTooltip，GuiScreen.java:160）；`GuiContainerCreative`（Item#getSubItems 经 CreativeTabs）。
- net/minecraft/creativetab → `CreativeTabs`（Item#setCreativeTab / getCreativeTab）。
- net/minecraft/network → `S2FPacketSetSlot`（ItemEditableBook#resolveContents 直接发包，ItemEditableBook.java:139）；`Packet`（ItemMapBase#createMapDataPacket 返回类型）。
- net/minecraft/server → `MinecraftServer#getServer`（ItemBlock#setTileEntityNBT 权限校验）。
- com/mojang/authlib → `GameProfile`（ItemSkull 头颅所有者）。

## 覆盖声明

完整读取了 72/72 个文件（每个文件均通过 Read 全文读取，无抽样）。

逐行精读（并核对行号引用）的类：Item、ItemStack、ItemBlock、ItemPotion、ItemArmor、ItemMap、ItemBow、ItemFood、ItemTool、ItemSword、ItemMonsterPlacer、ItemSkull、ItemDye、ItemBanner、ItemEnchantedBook、ItemEditableBook、ItemBucket、ItemSlab、ItemMinecart、ItemEnderEye、ItemFireworkCharge、ItemFishFood。

其余 50 个小文件（多为 9-130 行的简单子类与枚举）全文读取并确认了类签名、构造参数与覆写方法，但未对每一行逻辑做交叉验证。跨包调用点（Bootstrap、PlayerControllerMP、InventoryPlayer、EntityPlayer、EntityPlayerMP、EntityTrackerEntry、RenderItem、GuiScreen）均用 Grep 实际确认了行号。
