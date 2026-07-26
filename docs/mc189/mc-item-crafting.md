---
area: net/minecraft/item/crafting
slug: mc-item-crafting
files: 19
lines: 2309
tier: B
---

# net/minecraft/item/crafting

## 定位

本包是合成/熔炼配方系统：定义 `IRecipe` 抽象，维护两个全局单例注册表——`CraftingManager`（工作台/背包 2x2 合成）和 `FurnaceRecipes`（熔炉配方 + 经验值）。所有配方在类加载时通过私有构造器一次性硬编码注册，没有任何数据驱动的加载（1.8.9 没有 JSON recipe）。

调用方（谁用它）：
- `net.minecraft.inventory.ContainerWorkbench#onCraftMatrixChanged`（ContainerWorkbench.java:56）和 `ContainerPlayer`（ContainerPlayer.java:77）在合成栏内容变化时查 `CraftingManager.getInstance().findMatchingRecipe(...)` 填充输出槽。
- `net.minecraft.inventory.SlotCrafting#onPickupFromSlot`（SlotCrafting.java:137）取走产物时调 `CraftingManager.getInstance().func_180303_b(...)` 计算容器残留物（如桶、瓶）。
- `net.minecraft.tileentity.TileEntityFurnace`（TileEntityFurnace.java:321,333）、`ContainerFurnace`（:116）、`SlotFurnaceOutput`（:71）使用 `FurnaceRecipes.instance()` 判断可熔炼、取产物和经验。
- `net.minecraft.stats.StatList`（StatList.java:143,151）遍历 `getRecipeList()` / `getSmeltingList()` 生成合成/熔炼统计项。
- `net.minecraft.entity.passive.EntitySheep`（EntitySheep.java:350）用 `findMatchingRecipe` 计算羊繁殖出的羊毛颜色（染料混色复用合成逻辑）。

它调用谁：`net.minecraft.init.Blocks` / `Items`（配方原料）、`InventoryCrafting`、`ItemStack`、NBT 类、`TileEntityBanner`（旗帜图案枚举）、`MapData`（地图缩放）。

如果它消失：工作台/背包合成输出槽永远为空、熔炉不工作、羊繁殖颜色逻辑崩溃、合成相关统计无法初始化。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| CraftingManager | 352 | - | 合成配方单例注册表；构造器里注册全部原版配方并排序；提供匹配查询 |
| FurnaceRecipes | 132 | - | 熔炉配方单例注册表（输入→输出 Map + 输出→经验 Map） |
| IRecipe | 27 | interface | 配方抽象：matches / getCraftingResult / getRecipeSize / getRecipeOutput / getRemainingItems |
| RecipeBookCloning | 134 | implements IRecipe | 成书复制：written_book + N 本 writable_book → N 本下一代成书 |
| RecipeFireworks | 270 | implements IRecipe | 烟花火箭/烟火之星动态合成，在 matches 内构建 NBT 结果 |
| RecipeRepairItem | 123 | implements IRecipe | 两件同类可损耗物品合并修复（耐久相加 + 5% 奖励） |
| RecipesArmor | 28 | - | 批量注册 4 材质 x 4 部位盔甲配方的辅助类 |
| RecipesArmorDyes | 167 | implements IRecipe | 皮革甲染色：混合已有颜色与染料 RGB 求平均 |
| RecipesBanners | 377 | -（含两个 static 内部类 implements IRecipe） | 注册 16 色旗帜配方 + RecipeDuplicatePattern / RecipeAddPattern 两个特殊配方 |
| RecipesCrafting | 59 | - | 批量注册杂项方块配方（箱子、熔炉、石砖、海晶石等） |
| RecipesDyes | 59 | - | 批量注册染料/羊毛/玻璃/陶瓦/地毯染色配方 |
| RecipesFood | 27 | - | 批量注册食物配方（炖菜、曲奇、南瓜派等） |
| RecipesIngots | 29 | - | 批量注册锭↔块 9:1 互转配方 |
| RecipesMapCloning | 129 | implements IRecipe | 地图复制：filled_map + N 张空 map → N+1 张同 ID 地图 |
| RecipesMapExtending | 80 | extends ShapedRecipes | 地图扩展：8 纸围地图，检查 MapData.scale < 4，结果打 "map_is_scaling" NBT |
| RecipesTools | 31 | - | 批量注册 5 材质 x 4 种工具配方 + 剪刀 |
| RecipesWeapons | 32 | - | 批量注册 5 材质剑配方 + 弓 + 箭 |
| ShapedRecipes | 157 | implements IRecipe | 有序合成实现：宽 x 高网格匹配（含镜像） |
| ShapelessRecipes | 96 | implements IRecipe | 无序合成实现：清单消除式匹配 |

## 核心类详解

### CraftingManager（CraftingManager.java）

- 字段：`private static final CraftingManager instance`（:26）；`private final List<IRecipe> recipes = Lists.<IRecipe>newArrayList()`（:27）。
- `public static CraftingManager getInstance()`（:32）——唯一入口；首次触碰该类时私有构造器（:37-194）注册全部配方，末尾用匿名 `Comparator<IRecipe>`（:187-193）排序：ShapedRecipes 优先于 ShapelessRecipes，同类按 `getRecipeSize()` 降序——保证大配方先匹配。
- `public ShapedRecipes addRecipe(ItemStack stack, Object... recipeComponents)`（:199）——解析 `"XXX"` 模式字符串 + `char→Item/Block/ItemStack` 映射为 `ShapedRecipes`；`Block` 参数会被转成 wildcard 元数据 `new ItemStack((Block)recipeComponents[i + 1], 1, 32767)`（:242）。
- `public void addShapelessRecipe(ItemStack stack, Object... recipeComponents)`（:276）——非法类型抛 `IllegalArgumentException`（:294）。
- `public void addRecipe(IRecipe recipe)`（:307）——直接追加任意 IRecipe，功能层注入自定义配方的最简入口（注意：绕过了排序）。
- `public ItemStack findMatchingRecipe(InventoryCrafting p_82787_1_, World worldIn)`（:315）——线性遍历，第一个 `matches` 命中即返回 `getCraftingResult`；无命中返回 `null`。被 `ContainerWorkbench:56`、`ContainerPlayer:77`、`EntitySheep:350` 调用。
- `public ItemStack[] func_180303_b(InventoryCrafting p_180303_1_, World worldIn)`（:328）——返回匹配配方的 `getRemainingItems`；无匹配时原样返回格子内容。被 `SlotCrafting#onPickupFromSlot`（SlotCrafting.java:137）调用。
- `public List<IRecipe> getRecipeList()`（:348）——返回内部 List 的直接引用（非拷贝），`StatList:143` 遍历它。

### FurnaceRecipes（FurnaceRecipes.java）

- 字段：`private static final FurnaceRecipes smeltingBase`（:17）；`private Map<ItemStack, ItemStack> smeltingList`（:18）；`private Map<ItemStack, Float> experienceList`（:19）。
- `public static FurnaceRecipes instance()`（:24）。
- `public void addSmeltingRecipeForBlock(Block input, ItemStack stack, float experience)`（:69）→ `public void addSmelting(Item input, ItemStack stack, float experience)`（:77，输入包成 wildcard 32767）→ `public void addSmeltingRecipe(ItemStack input, ItemStack stack, float experience)`（:85）。
- `public ItemStack getSmeltingResult(ItemStack stack)`（:94）——线性遍历 Map entry，用 `compareItemStacks`（:110）比 item + metadata（32767 通配）。注意返回的是注册表内部的 ItemStack 引用，调用方（TileEntityFurnace:321,333）需自行 copy。
- `public float getSmeltingExperience(ItemStack stack)`（:120）——按输出物匹配经验，被 `SlotFurnaceOutput:71` 调用。

### IRecipe（IRecipe.java:7-27）

五个方法（签名逐字）：
```java
boolean matches(InventoryCrafting inv, World worldIn);
ItemStack getCraftingResult(InventoryCrafting inv);
int getRecipeSize();
ItemStack getRecipeOutput();
ItemStack[] getRemainingItems(InventoryCrafting inv);
```
约定：`matches` 只判断；`getCraftingResult` 生成新 ItemStack（可带 NBT）；`getRecipeOutput` 返回模板输出（特殊配方多数返回 `null`）；`getRemainingItems` 返回取走产物后每格残留（容器物品）。

### ShapedRecipes（ShapedRecipes.java）

- 字段：`private final int recipeWidth`（:11）、`private final int recipeHeight`（:14）、`private final ItemStack[] recipeItems`（:17）、`private final ItemStack recipeOutput`（:20）、`private boolean copyIngredientNBT`（:21）。
- 构造器 `public ShapedRecipes(int width, int height, ItemStack[] p_i1917_3_, ItemStack output)`（:23）。
- `public boolean matches(InventoryCrafting inv, World worldIn)`（:56）——在 3x3 内平移窗口，每个位置各试正向与镜像：`checkMatch(inv, i, j, true)` / `false`（:62,:67）。
- `private boolean checkMatch(InventoryCrafting p_77573_1_, int p_77573_2_, int p_77573_3_, boolean p_77573_4_)`（:80）——元数据 32767 作通配（:116）。
- `public ItemStack getCraftingResult(InventoryCrafting inv)`（:130）——copy 输出；若 `copyIngredientNBT` 为 true 则把最后一个带 NBT 原料的 tag 复制到结果（:134-145）。注意：本包内没有任何代码把 `copyIngredientNBT` 置 true，也没有 setter——它只在声明处默认 false（疑似原版 `func_92100_c` 被裁剪，见 openQuestions）。

### ShapelessRecipes（ShapelessRecipes.java）

- 字段：`private final ItemStack recipeOutput`（:12）、`private final List<ItemStack> recipeItems`（:13）。
- `public boolean matches(InventoryCrafting inv, World worldIn)`（:46）——复制原料清单，逐格从清单消除；格子有多余物品或清单未清空即失败（:78 `return list.isEmpty();`）。
- `public int getRecipeSize()`（:92）返回原料个数——这使 CraftingManager 的排序对 shapeless 也成立。

### RecipeFireworks（RecipeFireworks.java）

- 字段：`private ItemStack field_92102_a`（:15）——**matches 的副作用产物**。`public boolean matches(InventoryCrafting inv, World worldIn)`（:20）统计火药/染料/纸等数量，分三个分支（火箭 :88、烟火之星 :116、加淡出色 :174）直接在 matches 内构建带 `Fireworks`/`Explosion` NBT 的结果存进字段；`getCraftingResult`（:236）只是 `return this.field_92102_a.copy();`。这个"先 matches 后 getCraftingResult"的隐式时序耦合是全包最脆的设计。
- `getRecipeSize()` 返回 10（:244-247）。

### RecipeRepairItem（RecipeRepairItem.java）

- `matches`（:15）要求恰好 2 个同 Item、stackSize==1、`isDamageable()` 的堆叠。
- `getCraftingResult`（:45）耐久合并公式（:77-80）：
```java
int j = item.getMaxDamage() - itemstack2.getItemDamage();
int k = item.getMaxDamage() - itemstack3.getItemDamage();
int l = j + k + item.getMaxDamage() * 5 / 100;
```
结果丢弃附魔与 NBT（`new ItemStack(itemstack2.getItem(), 1, i1)`，:87）。

### RecipesArmorDyes（RecipesArmorDyes.java）

- `matches`（:18）：恰一件 `ItemArmor.ArmorMaterial.LEATHER` 皮革甲 + 至少一个 `Items.dye`。
- `getCraftingResult`（:58）：把现有颜色与各染料 RGB（经 `EntitySheep.getDyeRgb(EnumDyeColor.byDyeDamage(...))`，:104）分量求和取平均，再按最大亮度归一（:123-133），最后 `itemarmor.setColor(itemstack, lvt_12_3_)`。

### RecipesBanners（RecipesBanners.java）

- `void addRecipes(CraftingManager p_179534_1_)`（:18，包私有）注册 16 色旗帜 + 两个内部配方。
- `RecipeAddPattern#matches`（:35）限制 `TileEntityBanner.getPatterns(itemstack) >= 6` 时拒绝（:50）；`func_179533_c`（:151）遍历 `TileEntityBanner.EnumBannerPattern` 按 craftingStack 或 craftingLayers 布局识别图案。`getCraftingResult`（:69）把 `{Pattern, Color}` compound 追加进 `BlockEntityTag.Patterns` 列表（:102-118）。
- `RecipeDuplicatePattern#matches`（:259）要求两面同底色旗帜、一面有图案一面空白；`getRemainingItems`（:352）把带图案的原旗帜留在格内（:366-370），即复制不消耗原版。

### RecipesMapExtending（RecipesMapExtending.java）

- 唯一 `extends ShapedRecipes` 的类（:10）；构造器（:12）定义 8 纸围 `filled_map`（wildcard 元数据）。
- `matches`（:20）追加检查 `MapData mapdata = Items.filled_map.getMapData(itemstack, worldIn); return mapdata == null ? false : mapdata.scale < 4;`（:46-47）——是包内唯一真正使用 `World` 参数的 matches 实现。
- `getCraftingResult`（:55）复制地图并 `itemstack.getTagCompound().setBoolean("map_is_scaling", true)`（:77），实际放大由 ItemMap 侧处理。

## 时序与生命周期

- **初始化**：`CraftingManager` 与 `FurnaceRecipes` 均为 static final 单例，首次调用 `getInstance()` / `instance()` 时由 JVM 类初始化触发私有构造器，一次性注册全部配方。前提是 `net.minecraft.init.Blocks` / `Items` 已完成注册（由 Bootstrap 保证）；过早触碰会拿到 null 物品。注册完成后列表在运行期不再被原版代码修改。
- **每 tick / 每帧**：本包自身无 tick、无渲染。调用发生在事件驱动路径：合成格内容变化（`Container#onCraftMatrixChanged`）、取走产物（`SlotCrafting#onPickupFromSlot`）、熔炉 tick（`TileEntityFurnace#update` 每 tick 调 `getSmeltingResult`，属于熔炉的 tick 而非本包的）。
- **线程归属**：匹配逻辑在逻辑主线程执行——单机时 Container 逻辑同时跑在客户端主线程（预测显示）与集成服务端线程（权威结果）；`TileEntityFurnace` 只在服务端线程。两个单例注册表被两条线程并发读；只读场景下安全，运行期写入（动态增删配方）没有任何同步保护。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public ItemStack findMatchingRecipe(InventoryCrafting p_82787_1_, World worldIn)` | CraftingManager.java:315 | 合成格每次变化（ContainerWorkbench.java:56 / ContainerPlayer.java:77）、羊繁殖（EntitySheep.java:350） | 拦截/替换合成结果、实现自动合成预览、注入条件配方 | 每次格子变化全表线性扫描；返回 null 表示无配方；客户端与集成服务端都会调用 |
| `public ItemStack[] func_180303_b(InventoryCrafting p_180303_1_, World worldIn)` | CraftingManager.java:328 | 玩家从输出槽取走产物时（SlotCrafting.java:137） | 改写容器残留逻辑（如不消耗某原料） | 与 findMatchingRecipe 分别独立再匹配一次，两次匹配结果需一致 |
| `public void addRecipe(IRecipe recipe)` | CraftingManager.java:307 | 初始化及任何自定义时机 | 注册任意自定义 IRecipe（最通用扩展点） | 追加在列表末尾，不重排序；shapeless 大配方可能被先注册的小 shaped 配方抢先匹配 |
| `public ShapedRecipes addRecipe(ItemStack stack, Object... recipeComponents)` | CraftingManager.java:199 | 初始化 | 按原版 DSL 注册有序配方 | 传 Block 会自动变 wildcard 元数据 32767 |
| `public void addShapelessRecipe(ItemStack stack, Object... recipeComponents)` | CraftingManager.java:276 | 初始化 | 注册无序配方 | 非 Item/Block/ItemStack 参数抛 IllegalArgumentException（:294） |
| `public List<IRecipe> getRecipeList()` | CraftingManager.java:348 | StatList 初始化（StatList.java:143）、任何功能层 | 直接拿到可变内部 List，可遍历/删除/重排（配方查看器、AutoCraft 的数据源） | 返回内部引用，无防御拷贝；并发修改无保护 |
| `boolean matches(InventoryCrafting inv, World worldIn)` | IRecipe.java:12 | findMatchingRecipe / func_180303_b 遍历时 | 自定义 IRecipe 实现任意匹配逻辑（NBT 敏感、按世界状态） | RecipeFireworks 证明 matches 允许副作用，但依赖"matches 后必调 getCraftingResult"的隐式时序 |
| `ItemStack getCraftingResult(InventoryCrafting inv)` | IRecipe.java:17 | matches 命中后 | 生成动态 NBT 产物 | 必须返回新实例或 copy；返回共享引用会污染注册表 |
| `ItemStack[] getRemainingItems(InventoryCrafting inv)` | IRecipe.java:26 | 取走产物时 | 保留/返还原料（旗帜复制即用此机制，RecipesBanners.java:352） | 数组长度必须等于 `inv.getSizeInventory()` |
| `public ItemStack getSmeltingResult(ItemStack stack)` | FurnaceRecipes.java:94 | 熔炉 tick（TileEntityFurnace.java:321,333）、shift 点击分拣（ContainerFurnace.java:116） | 拦截熔炼产物、加自定义熔炼 | 返回注册表内部 ItemStack 引用，调用方 copy；每 tick 被熔炉调用，热路径 |
| `public void addSmeltingRecipe(ItemStack input, ItemStack stack, float experience)` | FurnaceRecipes.java:85 | 初始化 | 注册自定义熔炼配方 | 经验以**输出物**为 key，两种输入同输出会互相覆盖经验 |
| `public float getSmeltingExperience(ItemStack stack)` | FurnaceRecipes.java:120 | 取出熔炉产物时（SlotFurnaceOutput.java:71） | 改写熔炼经验 | 按输出匹配，wildcard 语义同上 |
| `public Map<ItemStack, ItemStack> getSmeltingList()` | FurnaceRecipes.java:115 | StatList.java:151 | 遍历全部熔炼配方（配方查看器数据源） | 同样返回内部可变 Map |

## 数据与协议

本包不直接收发封包、不读写文件、不注册注册表项；但多个配方读写 ItemStack NBT，字段如下：

| NBT 字段 | 类型 | 读/写位置 | 含义 |
|---|---|---|---|
| `generation` | Integer | 写：RecipeBookCloning.java:89（`setInteger("generation", ItemEditableBook.getGeneration(itemstack) + 1)`）；读经 `ItemEditableBook.getGeneration`（:85） | 成书世代；`>= 2` 不可再复制 |
| `Fireworks.Explosions` | TagList(Compound) | 写：RecipeFireworks.java:108；读：:102-105（从 firework_charge 的 `Explosion` 收集） | 火箭包含的爆炸效果列表 |
| `Fireworks.Flight` | Byte | 写：RecipeFireworks.java:109 | 飞行时长 = 火药数（1-3） |
| `Explosion.Colors` | IntArray | 写：RecipeFireworks.java:168 | 烟火之星颜色（`ItemDye.dyeColors[meta & 15]`，:132） |
| `Explosion.Type` | Byte | 写：RecipeFireworks.java:169 | 形状：0 小球 / 1 大球(fire_charge) / 2 星形(gold_nugget) / 3 爬行者(skull) / 4 爆裂(feather)（:142-157） |
| `Explosion.Flicker` / `Explosion.Trail` | Boolean | 写：RecipeFireworks.java:136 / :140 | 闪烁(glowstone_dust) / 轨迹(diamond) |
| `Explosion.FadeColors` | IntArray | 写：RecipeFireworks.java:213 | 淡出颜色（给已有 firework_charge 加染料） |
| `BlockEntityTag.Patterns[]` 内 `Pattern` | String | 写：RecipesBanners.java:116（`EnumBannerPattern.getPatternID()`） | 旗帜图案 ID |
| `BlockEntityTag.Patterns[]` 内 `Color` | Integer | 写：RecipesBanners.java:117 | 图案染料 metadata |
| `map_is_scaling` | Boolean | 写：RecipesMapExtending.java:77 | 标记地图待放大，由 ItemMap 侧消费 |
| 皮革甲颜色（`display.color`，经 `ItemArmor#setColor/getColor` 间接读写） | Integer | RecipesArmorDyes.java:86 / :133 | 0xRRGGBB 混合色 |

## 不变量与陷阱

- **配方顺序即优先级**：`findMatchingRecipe` 取第一个命中。构造器末尾的排序（CraftingManager.java:187-193）保证 shaped 先于 shapeless、大配方先于小配方；但 `addRecipe(IRecipe)`（:307）以及构造器排序**之后**注册的任何配方只是尾插。运行期动态加配方若需正确优先级要自行重排。
- **元数据 32767 是全局通配约定**：ShapedRecipes.java:116、ShapelessRecipes.java:62、FurnaceRecipes.java:112 三处一致。`CraftingManager.addRecipe` 里裸 `Block` 参数自动通配（:242）而裸 `Item` 不通配（:238，metadata 0）。
- **RecipeFireworks 有状态**：`field_92102_a` 由 `matches` 写入、`getCraftingResult` 读取。若单独调 `getCraftingResult` 或两个合成台并发匹配同一实例（客户端 + 集成服务端线程），结果可能串台/NPE。原版调用序列恰好保证 matches 紧邻 getCraftingResult，改动调用方时别打破它。
- **FurnaceRecipes 的经验以输出为 key**（FurnaceRecipes.java:88 `this.experienceList.put(stack, ...)`) ：多个输入映射到相同输出时经验互相覆盖；查询也是 O(n) 线性遍历而非 Map 查找（:96,:122——因为 ItemStack 没有 hashCode/equals 语义，Map 只当列表用）。
- **返回内部可变引用**：`getRecipeList()`、`getSmeltingList()`、`getSmeltingResult()` 都不做防御拷贝。功能层若误改返回的 ItemStack（例如直接 `stackSize++`）会永久污染注册表。
- **修复配方吞附魔**：RecipeRepairItem.java:87 用裸 `new ItemStack(item, 1, damage)` 构造结果，附魔/命名全部丢失——这是原版行为，不是移植 bug。
- **RecipeBookCloning 潜在 NPE**：getCraftingResult:88 直接 `itemstack.getTagCompound().copy()`，written_book 无 tag 时会 NPE；原版依赖成书必有 tag 的约定。
- **线程安全**：两个单例注册表无锁。单机模式下客户端主线程与集成服务端线程并发调用 `matches`；纯读 + 配方无状态时安全，RecipeFireworks 是唯一例外（见上）。若功能层在运行期增删配方，必须保证在两条逻辑线程都空闲时进行，或自行加同步。
- **LWJGL3/JDK25 移植**：本包为纯逻辑代码，无渲染/输入/native 依赖，移植零改动风险。与原版 1.8.9 MCP 对照未发现语义差异（`func_180303_b`、`func_92102_a` 等 SRG 残留名与原版一致）。

## 交叉引用

- net.minecraft.inventory → ContainerWorkbench#onCraftMatrixChanged、ContainerPlayer#onCraftMatrixChanged（调用 CraftingManager#findMatchingRecipe）
- net.minecraft.inventory → SlotCrafting#onPickupFromSlot（调用 CraftingManager#func_180303_b）
- net.minecraft.inventory → ContainerFurnace#transferStackInSlot、SlotFurnaceOutput#onCrafting（调用 FurnaceRecipes#getSmeltingResult / #getSmeltingExperience）
- net.minecraft.tileentity → TileEntityFurnace#canSmelt / #smeltItem（调用 FurnaceRecipes#getSmeltingResult）
- net.minecraft.tileentity → TileEntityBanner#getPatterns / #getBaseColor / EnumBannerPattern（RecipesBanners 依赖）
- net.minecraft.stats → StatList（遍历 CraftingManager#getRecipeList、FurnaceRecipes#getSmeltingList）
- net.minecraft.entity.passive → EntitySheep#getDyeRgb（RecipesArmorDyes 取染料 RGB）；EntitySheep 反向调用 CraftingManager#findMatchingRecipe（繁殖混色）
- net.minecraft.item → ItemEditableBook#getGeneration（RecipeBookCloning）、ItemDye.dyeColors（RecipeFireworks）、ItemArmor#getColor/#setColor/#hasColor（RecipesArmorDyes）、ItemFishFood.FishType（FurnaceRecipes 鱼类熔炼）
- net.minecraft.world.storage → MapData.scale（RecipesMapExtending#matches）
- net.minecraft.init → Blocks / Items（所有配方原料；要求 Bootstrap 先完成注册）
- net.minecraft.nbt → NBTTagCompound / NBTTagList（烟花、旗帜、书、地图配方的结果构造）

## 覆盖声明

完整读取了 19/19 个文件（每个文件从第 1 行读到末行）。

逐行精读：CraftingManager、FurnaceRecipes、IRecipe、ShapedRecipes、ShapelessRecipes、RecipeFireworks、RecipeRepairItem、RecipesArmorDyes、RecipesBanners、RecipesMapExtending、RecipeBookCloning、RecipesMapCloning。

结构性浏览（内容为纯配方注册清单，逐条配方未逐一核对但注册模式已确认）：RecipesTools、RecipesWeapons、RecipesIngots、RecipesFood、RecipesCrafting、RecipesDyes、RecipesArmor。

调用方引用（ContainerWorkbench:56、ContainerPlayer:77、SlotCrafting:137、TileEntityFurnace:321/333、ContainerFurnace:116、SlotFurnaceOutput:71、StatList:143/151、EntitySheep:350）经 grep 确认行号，未精读这些外部文件全文。
