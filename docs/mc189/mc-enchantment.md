---
area: net/minecraft/enchantment
slug: mc-enchantment
files: 21
lines: 1822
tier: B
---

# net/minecraft/enchantment 架构笔记

## 定位

附魔系统的完整实现：注册表（`Enchantment` 静态字段 + 256 槽数组）、类型匹配（`EnumEnchantmentType`）、以及所有读取/写入/计算逻辑的静态门面 `EnchantmentHelper`。附魔数据本身不存在于本包——它存在 `ItemStack` 的 NBT（`"ench"` 列表）里；本包只负责解释这些数据。

调用方（按 grep 实际确认）：`entity` 包（伤害、击退、掉落、水下移动计算）、`item` 包（`ItemStack` tooltip 与攻击伤害、`ItemBow` 射箭、`ItemEnchantedBook`）、`inventory` 包（`ContainerEnchantment` 附魔台、`ContainerRepair` 铁砧）、`block` 包（精准采集/时运判定）、`client.renderer.EntityRenderer`（水下雾距用 respiration）、`client.gui.inventory.GuiContainerCreative`（创造搜索页按附魔展示书）。本包向外依赖 `item`、`entity`、`nbt`、`util`（`WeightedRandom`、`DamageSource`、`ResourceLocation`、`StatCollector`）、`potion`（EnchantmentDamage 的缓慢效果）。

如果本包消失：物品 NBT 里的附魔变成无法解释的死数据——伤害/保护计算、附魔台、铁砧、精准/时运掉落、tooltip 附魔名全部失效。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| Enchantment | 260 | abstract class | 附魔基类兼注册表：静态字段注册全部 24 个附魔到 256 槽数组与 ResourceLocation 映射 |
| EnchantmentArrowDamage | 36 | extends Enchantment | 弓·力量（power, id 48），max Lv5 |
| EnchantmentArrowFire | 36 | extends Enchantment | 弓·火矢（flame, id 50），max Lv1 |
| EnchantmentArrowInfinite | 36 | extends Enchantment | 弓·无限（infinity, id 51），max Lv1 |
| EnchantmentArrowKnockback | 36 | extends Enchantment | 弓·冲击（punch, id 49），max Lv2 |
| EnchantmentDamage | 117 | extends Enchantment | 锋利/亡灵杀手/节肢杀手三合一（damageType 0/1/2），附带节肢缓慢效果 |
| EnchantmentData | 19 | extends WeightedRandom.Item | (Enchantment, level) 值对，weight 取自 enchantment.getWeight() 供加权抽取 |
| EnchantmentDigging | 46 | extends Enchantment | 效率（efficiency, id 32），额外允许 shears |
| EnchantmentDurability | 57 | extends Enchantment | 耐久（unbreaking, id 34），静态 negateDamage 决定是否免耗 |
| EnchantmentFireAspect | 36 | extends Enchantment | 火焰附加（fire_aspect, id 20），max Lv2 |
| EnchantmentFishingSpeed | 36 | extends Enchantment | 钓竿·饵钓（lure, id 62），max Lv3 |
| EnchantmentHelper | 575 | class（全静态） | 门面：NBT 读写、等级查询、伤害/保护聚合、附魔台随机附魔算法 |
| EnchantmentKnockback | 36 | extends Enchantment | 击退（knockback, id 19），max Lv2 |
| EnchantmentLootBonus | 56 | extends Enchantment | 抢夺/时运/海之眷顾三合一（按 EnumEnchantmentType 区分名字），与 silkTouch 互斥 |
| EnchantmentOxygen | 36 | extends Enchantment | 水下呼吸（respiration, id 5），头盔，max Lv3 |
| EnchantmentProtection | 136 | extends Enchantment | 五种保护（protectionType 0-4），calcModifierDamage 按伤害类型给减伤点数 |
| EnchantmentThorns | 88 | extends Enchantment | 荆棘（thorns, id 7），onUserHurt 概率反伤并额外损耗装备 |
| EnchantmentUntouching | 54 | extends Enchantment | 精准采集（silk_touch, id 33），与 fortune 互斥，额外允许 shears |
| EnchantmentWaterWalker | 36 | extends Enchantment | 深海探索者（depth_strider, id 8），靴子，max Lv3 |
| EnchantmentWaterWorker | 36 | extends Enchantment | 水下速掘（aqua_affinity, id 6），头盔，max Lv1 |
| EnumEnchantmentType | 54 | enum | 11 种附魔槽位类型；canEnchantItem 按 Item 子类/armorType 判定 |

## 核心类详解

### Enchantment（Enchantment.java）

注册表兼行为基类。关键字段：

- `private static final Enchantment[] enchantmentsList = new Enchantment[256];`（Enchantment.java:18）——按 `effectId` 索引。
- `public static final Enchantment[] enchantmentsBookList;`（:19）——静态块（:246-259）把非 null 槽压紧成数组，供附魔台/创造页遍历。
- `private static final Map<ResourceLocation, Enchantment> locationEnchantments`（:20）——名字查询，供 `/enchant` 命令补全。
- 实例字段：`public final int effectId;`（:84）、`private final int weight;`（:85）、`public EnumEnchantmentType type;`（:88，注意非 final，`EnchantmentProtection` 构造器里会改写）、`protected String name;`（:91）。

关键方法（逐字）：

- `public static Enchantment getEnchantmentById(int enchID)`（:96）——越界返回 null。
- `protected Enchantment(int enchID, ResourceLocation enchName, int enchWeight, EnumEnchantmentType enchType)`（:101）——id 冲突抛 `IllegalArgumentException("Duplicate enchantment id!")`（:109）。
- `public static Enchantment getEnchantmentByLocation(String location)`（:121）、`public static Set<ResourceLocation> func_181077_c()`（:126）——后者被 `CommandEnchant` 的 tab 补全使用。
- `public int getMinEnchantability(int enchantmentLevel)`（:159，默认 `1 + enchantmentLevel * 10`）/ `public int getMaxEnchantability(int enchantmentLevel)`（:167，默认 min+5）——附魔台候选筛选的核心，几乎每个子类都覆写。
- `public int calcModifierDamage(int level, DamageSource source)`（:175，默认 0）——保护类覆写。
- `public float calcDamageByCreature(int level, EnumCreatureAttribute creatureType)`（:184，默认 0.0F）——伤害类覆写。
- `public boolean canApplyTogether(Enchantment ench)`（:192，默认 `this != ench`）。
- `public boolean canApply(ItemStack stack)`（:226，委托 `this.type.canEnchantItem(stack.getItem())`）。
- `public String getTranslatedName(int level)`（:217）——tooltip 名，`ItemStack.getTooltip` / `ItemEnchantedBook` 调用。
- `public void onEntityDamaged(EntityLivingBase user, Entity target, int level)`（:234）/ `public void onUserHurt(EntityLivingBase user, Entity attacker, int level)`（:242）——空实现，由 `EnchantmentHelper` 的两个 Iterator 在攻击/受击时机回调。

### EnchantmentHelper（EnchantmentHelper.java）

全静态门面，无实例。关键字段：

- `private static final Random enchantmentRand = new Random();`（EnchantmentHelper.java:24）——保护减伤的随机源。
- 四个可变单例访问器对象：`enchantmentModifierDamage`（:29，`ModifierDamage`）、`enchantmentModifierLiving`（:34，`ModifierLiving`）、`ENCHANTMENT_ITERATOR_HURT`（:35，`HurtIterator`）、`ENCHANTMENT_ITERATOR_DAMAGE`（:36，`DamageIterator`）。全部实现内部接口 `interface IModifier { void calculateModifier(Enchantment enchantmentIn, int enchantmentLevel); }`（:541-544）。

关键方法（逐字）：

- `public static int getEnchantmentLevel(int enchID, ItemStack stack)`（:41）——遍历 `stack.getEnchantmentTagList()` 匹配 `"id"` short，返回 `"lvl"`；最热的查询入口。
- `public static Map<Integer, Integer> getEnchantments(ItemStack stack)`（:73）——附魔书走 `Items.enchanted_book.getEnchantments(stack)`（`"StoredEnchantments"`），普通物品走 `"ench"`；返回 LinkedHashMap 保序。
- `public static void setEnchantments(Map<Integer, Integer> enchMap, ItemStack stack)`（:94）——写回 NBT；铁砧结果和 `GuiContainerCreative` 使用。
- `public static int getMaxEnchantmentLevel(int enchID, ItemStack[] stacks)`（:134）——盔甲逐件取最大。
- `public static int getEnchantmentModifierDamage(ItemStack[] stacks, DamageSource source)`（:197）——聚合四件盔甲的 `calcModifierDamage`，钳到 [0,25]，然后 `(damageModifier + 1 >> 1) + enchantmentRand.nextInt((damageModifier >> 1) + 1)`（:212）——即 50%-100% 随机取整。`EntityLivingBase.applyPotionDamageCalculations`（EntityLivingBase.java:1253）调用。
- `public static float getModifierForCreature(ItemStack p_152377_0_, EnumCreatureAttribute p_152377_1_)`（:215）——锋利系加伤，`EntityPlayer.attackTargetEntityWithCurrentItem`（EntityPlayer.java:1317/1321）与 `EntityMob.attackEntityAsMob`（EntityMob.java:111）调用。
- `public static void applyThornEnchantments(EntityLivingBase p_151384_0_, Entity p_151384_1_)`（:223）/ `public static void applyArthropodEnchantments(EntityLivingBase p_151385_0_, Entity p_151385_1_)`（:239）——受击/命中回调分发，调用点：`Entity.java:2814/2817`、`EntityPlayer.java:1393/1396`、`EntityArrow.java:339/340`。
- 一批便捷查询：`getKnockbackModifier`（:258）、`getFireAspectModifier`（:266）、`getRespiration(Entity player)`（:274）、`getDepthStriderModifier(Entity player)`（:282）、`getEfficiencyModifier`（:290）、`getSilkTouchModifier`（:298）、`getFortuneModifier`（:306）、`getLuckOfSeaModifier`（:314）、`getLureModifier`（:322）、`getLootingModifier`（:330）、`getAquaAffinityModifier`（:338）——各自绑定 `Enchantment.<X>.effectId`，参数为持有者实体。
- `public static ItemStack getEnchantedItem(Enchantment p_92099_0_, EntityLivingBase p_92099_1_)`（:343）——荆棘找该扣耐久的装备。
- 附魔台算法三件套：`public static int calcItemStackEnchantability(Random rand, int enchantNum, int power, ItemStack stack)`（:360，power 封顶 15，三个槽位分别 `Math.max(j / 3, 1)` / `j * 2 / 3 + 1` / `Math.max(j, power * 2)`，:377）；`public static ItemStack addRandomEnchantment(Random p_77504_0_, ItemStack p_77504_1_, int p_77504_2_)`（:384，book 就地 `setItem(Items.enchanted_book)`，:391）；`public static List<EnchantmentData> buildEnchantmentList(Random randomIn, ItemStackIn 略)`（:412，签名逐字为 `public static List<EnchantmentData> buildEnchantmentList(Random randomIn, ItemStack itemStackIn, int p_77513_2_)`）——加权抽第一个，然后 `for (int l = k; randomIn.nextInt(50) <= l; l >>= 1)`（:446）概率追加，剔除 `canApplyTogether` 冲突项。
- `public static Map<Integer, EnchantmentData> mapEnchantmentData(int p_77505_0_, ItemStack p_77505_1_)`（:483）——遍历 `enchantmentsBookList`，book 无视类型限制（`enchantment.type.canEnchantItem(item) || flag`，:491）。

### EnchantmentProtection（EnchantmentProtection.java）

`public final int protectionType;`（:33，0=all 1=fire 2=fall 3=explosion 4=projectile）。构造器（:35）注册为 `EnumEnchantmentType.ARMOR`，但 `protectionType == 2`（摔落保护）改写 `this.type = EnumEnchantmentType.ARMOR_FEET;`（:42）。

- `public int calcModifierDamage(int level, DamageSource source)`（:73）——`source.canHarmInCreative()` 直接 0；基数 `float f = (float)(6 + level * level) / 3.0F;`（:81），各类型乘 0.75/1.25/2.5/1.5/1.5。
- `public boolean canApplyTogether(Enchantment ench)`（:97）——同为保护时：同类型 false；否则仅当任一方是摔落保护（type 2）才兼容（:102）。
- 两个静态工具被实体侧调用：`public static int getFireTimeForEntity(Entity p_92093_0_, int p_92093_1_)`（:113，火保每级减 15% 着火 tick）、`public static double func_92092_a(Entity p_92092_0_, double p_92092_1_)`（:125，爆炸保护减爆炸冲击）。

### EnchantmentDamage（EnchantmentDamage.java）

`public final int damageType;`（:36）。注意 :34 注释写 "3 = arthropods" 但代码全部用 `damageType == 2` 判断节肢（:74、:110）——注释是错的，以代码为准。

- `public float calcDamageByCreature(int level, EnumCreatureAttribute creatureType)`（:72）——sharpness 每级 1.25F，smite/bane 对应属性每级 2.5F。
- `public boolean canApply(ItemStack stack)`（:96）——`stack.getItem() instanceof ItemAxe` 放行（斧也可上锋利，仅限直接 canApply 路径即铁砧书合成；附魔台走 type.canEnchantItem 不含斧）。
- `public void onEntityDamaged(EntityLivingBase user, Entity target, int level)`（:104）——bane of arthropods 命中节肢时施加 `new PotionEffect(Potion.moveSlowdown.id, i, 3)`，时长 `20 + user.getRNG().nextInt(10 * level)`（:112-113）。

### EnchantmentThorns（EnchantmentThorns.java）

- `public void onUserHurt(EntityLivingBase user, Entity attacker, int level)`（:55）——`func_92094_a`（:79，`p_92094_1_.nextFloat() < 0.15F * (float)p_92094_0_`）判定触发；触发则 `attacker.attackEntityFrom(DamageSource.causeThornsDamage(user), (float)func_92095_b(level, random))` 并播 `"damage.thorns"`（:64-65），装备 `damageItem(3, user)`；未触发也 `damageItem(1, user)`（:75）——荆棘装备被打就掉耐久。
- `public boolean canApply(ItemStack stack)`（:46）——任意 `ItemArmor` 放行（不限胸甲），注册 type 是 `ARMOR_TORSO`（:15）仅限附魔台。

### EnchantmentDurability（EnchantmentDurability.java）

- `public static boolean negateDamage(ItemStack p_92097_0_, int p_92097_1_, Random p_92097_2_)`（:53）——盔甲 60% 概率不免耗，否则 `p_92097_2_.nextInt(p_92097_1_ + 1) > 0`（level/(level+1) 概率免耗）。被 `ItemStack.attemptDamageItem` 调用。
- `public boolean canApply(ItemStack stack)`（:43）——`stack.isItemStackDamageable()` 即可，type 为 `BREAKABLE`（:12）。

### EnumEnchantmentType（EnumEnchantmentType.java）

11 个枚举值（:12-22）：`ALL, ARMOR, ARMOR_FEET, ARMOR_LEGS, ARMOR_TORSO, ARMOR_HEAD, WEAPON, DIGGER, FISHING_ROD, BREAKABLE, BOW`。

- `public boolean canEnchantItem(Item p_77557_1_)`（:27）——ALL 恒真；BREAKABLE 看 `p_77557_1_.isDamageable()`；ItemArmor 按 `armorType`（0=head, 1=torso, 2=legs, 3=feet，:46）；其余按 `ItemSword→WEAPON / ItemTool→DIGGER / ItemBow→BOW / ItemFishingRod→FISHING_ROD`（:51）。

### EnchantmentData（EnchantmentData.java）

`public final Enchantment enchantmentobj;`（:8）、`public final int enchantmentLevel;`（:11）；构造器 `public EnchantmentData(Enchantment enchantmentObj, int enchLevel)`（:13）把 `enchantmentObj.getWeight()` 传给 `WeightedRandom.Item`。被 `buildEnchantmentList`、`ItemEnchantedBook.addEnchantment`、`EntityVillager` 交易生成使用。

## 时序与生命周期

- **初始化**：全部发生在 `Enchantment` 类加载时。静态字段按声明顺序（:21-83）依次构造 24 个附魔实例并写入 `enchantmentsList` / `locationEnchantments`；随后静态块（:246）压紧出 `enchantmentsBookList`。首个触发类加载的引用（通常是物品/实体代码引用 `Enchantment.xxx`）即完成注册。此后注册表只读。
- **每 tick / 每帧**：本包无自主 tick。所有方法都是被动调用：伤害计算发生在攻击/受击事件（主线程逻辑 tick 内）；`EntityRenderer` 每帧渲染水下雾时查 `getRespiration`（EntityRenderer.java:1838/1994）。
- **线程归属**：设计上全部在主线程（客户端 tick + 渲染线程同一线程；集成服务端逻辑在服务端线程）。`EnchantmentHelper` 的可变单例（见陷阱节）使其**非线程安全**。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public static int getEnchantmentLevel(int enchID, ItemStack stack)` | EnchantmentHelper.java:41 | 所有附魔等级查询的最终汇点（ItemBow 射箭、Block 掉落、EntitySkeleton 等） | 伪造/放大任意附魔等级，一处接管全部效果 | 调用极频繁（含每帧 tooltip / 渲染路径），别做重活；只影响本地计算，服务端有权威校验 |
| `public static int getEnchantmentModifierDamage(ItemStack[] stacks, DamageSource source)` | EnchantmentHelper.java:197 | `EntityLivingBase.applyPotionDamageCalculations`（EntityLivingBase.java:1253）计算护甲附魔减伤 | 观察/改写保护减伤（显示预估伤害、免伤类功能） | 内含 `enchantmentRand` 随机；改返回值会与服务端结算偏差 |
| `public static float getModifierForCreature(ItemStack p_152377_0_, EnumCreatureAttribute p_152377_1_)` | EnchantmentHelper.java:215 | 玩家/怪物近战出手时（EntityPlayer.java:1317、EntityMob.java:111）及 `ItemStack.getTooltip`（ItemStack.java:760） | 伤害预估、攻击指示器类 UI 的数据源 | 同一方法同时服务战斗与 tooltip，hook 时按调用栈区分 |
| `public static void applyThornEnchantments(EntityLivingBase p_151384_0_, Entity p_151384_1_)` | EnchantmentHelper.java:223 | 实体受击后（Entity.java:2814、EntityPlayer.java:1393、EntityArrow.java:339） | 受击事件观察点（被谁打了）；拦截荆棘反伤 | 复用共享单例 `ENCHANTMENT_ITERATOR_HURT`，字段残留上次实体引用 |
| `public static void applyArthropodEnchantments(EntityLivingBase p_151385_0_, Entity p_151385_1_)` | EnchantmentHelper.java:239 | 命中目标后（Entity.java:2817、EntityPlayer.java:1396、EntityArrow.java:340） | 命中事件观察点（打中了谁）——比 hook 攻击键更靠后、含箭矢命中 | 同上，共享单例 `ENCHANTMENT_ITERATOR_DAMAGE` |
| `public void onEntityDamaged(EntityLivingBase user, Entity target, int level)` | Enchantment.java:234 | 上一行的 DamageIterator 对每个附魔回调（EnchantmentHelper.java:522） | 自定义附魔的命中效果入口（子类覆写） | 每个附魔、每件装备各触发一次 |
| `public void onUserHurt(EntityLivingBase user, Entity attacker, int level)` | Enchantment.java:242 | HurtIterator 回调（EnchantmentHelper.java:537）；荆棘实现在 EnchantmentThorns.java:55 | 自定义附魔的受击效果入口 | 同上 |
| `public static int getRespiration(Entity player)` | EnchantmentHelper.java:274 | 每 tick 空气值更新（EntityLivingBase.java:441）+ **每帧**水下雾计算（EntityRenderer.java:1838/1994） | 改写水下视距/雾（水下清晰类功能常直接 hook 此处或其 EntityRenderer 调用点） | 每帧路径，保持廉价 |
| `public static int getDepthStriderModifier(Entity player)` | EnchantmentHelper.java:282 | `EntityLivingBase.moveEntityWithHeading` 水中移动（EntityLivingBase.java:1705） | 水中速度修改（移动类功能的合法上限参考） | 改动影响本地物理，服务端反作弊可见 |
| `public static int getKnockbackModifier(EntityLivingBase player)` | EnchantmentHelper.java:258 | 近战出手（EntityPlayer.java:1324、EntityMob.java:112） | 观察/伪造击退等级 | 击退结算主要在服务端 |
| `public static int getEfficiencyModifier(EntityLivingBase player)` / `getAquaAffinityModifier` | EnchantmentHelper.java:290 / :338 | `EntityPlayer.getToolDigEfficiency`（EntityPlayer.java:906/946）每次挖掘速度采样 | 挖掘速度显示/修改（fastbreak 类功能的数据源） | 服务端按自己的公式校验破坏进度 |
| `public static boolean getSilkTouchModifier(EntityLivingBase player)` / `getFortuneModifier` | EnchantmentHelper.java:298 / :306 | `Block.harvestBlock` 路径（Block.java:990/1001、BlockIce.java:38/55） | 掉落预测类功能 | 客户端侧掉落只是预测，权威在服务端 |
| `public static List<EnchantmentData> buildEnchantmentList(Random randomIn, ItemStack itemStackIn, int p_77513_2_)` | EnchantmentHelper.java:412 | 附魔台开 GUI/点击时（ContainerEnchantment.java:311）、生成附魔战利品 | 附魔预览（在 GUI 提前显示会附出什么）——用相同 seed 的 Random 可复算 | 结果依赖传入 Random 的状态；服务端用自己的 seed |
| `public static int calcItemStackEnchantability(Random rand, int enchantNum, int power, ItemStack stack)` | EnchantmentHelper.java:360 | 附魔台计算三个槽位等级（ContainerEnchantment.java:203） | 附魔台等级显示逻辑 | power 封顶 15（书架数） |
| `public static Map<Integer, Integer> getEnchantments(ItemStack stack)` | EnchantmentHelper.java:73 | 铁砧合并（ContainerRepair.java:183/244）、创造页搜索（GuiContainerCreative.java:631） | 统一读取任意物品（含附魔书）附魔的入口，UI 展示首选 | 附魔书与普通物品 NBT 键不同，此方法已抹平 |
| `public static void setEnchantments(Map<Integer, Integer> enchMap, ItemStack stack)` | EnchantmentHelper.java:94 | 铁砧写回结果（ContainerRepair.java:381） | 本地构造附魔物品（ghost item、预览） | 对 enchanted_book 走 addEnchantment 追加而非覆盖（见陷阱） |
| `public boolean canApplyTogether(Enchantment ench)` | Enchantment.java:192 | buildEnchantmentList 追加抽取（EnchantmentHelper.java:457）与铁砧合并 | 修改互斥规则 | 需双向一致：A.canApplyTogether(B) 与 B.canApplyTogether(A) 都会被查 |
| `public static int getFireTimeForEntity(Entity p_92093_0_, int p_92093_1_)` | EnchantmentProtection.java:113 | 实体被点燃时（火保减免着火时长） | 着火时长显示/修改 | 静态方法，绕过实例分发 |

## 数据与协议

本包不直接收发封包；附魔随 `ItemStack` NBT 走物品同步封包。NBT 布局（读写点均在 `EnchantmentHelper`）：

| 字段 | 类型 | 读 | 写 | 含义 |
|---|---|---|---|---|
| `ench` | NBTTagList\<NBTTagCompound\>（挂在 ItemStack 根 tag） | `stack.getEnchantmentTagList()`（EnchantmentHelper.java:49/76/165） | `stack.setTagInfo("ench", nbttaglist)`（:122）；空表时 `removeTag("ench")`（:127） | 普通物品的附魔列表 |
| `ench[i].id` | short | `getCompoundTagAt(i).getShort("id")`（:59） | `nbttagcompound.setShort("id", (short)i)`（:107） | 附魔 effectId（0-255） |
| `ench[i].lvl` | short | `getCompoundTagAt(i).getShort("lvl")`（:60） | `setShort("lvl", ...)`（:108） | 等级 |
| `StoredEnchantments` | NBTTagList（附魔书专用，格式同上） | `Items.enchanted_book.getEnchantments(stack)`（:76） | `Items.enchanted_book.addEnchantment(stack, EnchantmentData)`（:113） | 附魔书存储的附魔（实现在 ItemEnchantedBook，本包只是调用方） |

注册表：`enchantmentsList[256]`（数组下标即协议中的 id）、`locationEnchantments`（`ResourceLocation` → 实例，`"protection"` 等名字见 Enchantment.java:21-83）。id 分段：0-8 盔甲、16-21 武器、32-35 工具、48-51 弓、61-62 钓竿。

## 不变量与陷阱

- **effectId 唯一且 < 256**：构造器强制（Enchantment.java:107-110）。NBT 用 short 存 id，但数组只有 256 槽——越界 id 经 `getEnchantmentById` 返回 null 被静默跳过。
- **`enchantmentsBookList` 在静态块生成**（Enchantment.java:246-259）：任何"运行期注册新附魔"的改造对附魔台/创造页不可见，除非同时重建该数组。
- **EnchantmentHelper 的四个 IModifier 单例是可变共享状态**（EnchantmentHelper.java:29-36）：`damageModifier`/`source`/`user`/`attacker` 等字段跨调用复用，无任何同步。多线程调用会得到脏数据；这些字段还会残留上一次的实体引用（轻微的对象滞留）。JDK 25 移植下若把逻辑挪到其他线程（如异步渲染准备）必须注意。
- **保护减伤带随机**：`getEnchantmentModifierDamage` 用私有 `enchantmentRand`（:24、:212），同输入不同输出；做伤害预测 UI 时只能给区间 `[(m+1)/2, m]`。
- **`EnchantmentDamage` 的注释是错的**：:34 写 "3 = arthropods"，实际代码用 `damageType == 2`（:74、:110）。`protectionName` 数组（:15）也按 index 2 = "arthropods"。
- **`canApply` 与 `type.canEnchantItem` 不等价**：斧上锋利（EnchantmentDamage.java:98）、剪刀上效率/精准（EnchantmentDigging.java:44、EnchantmentUntouching.java:52）、任意护甲上荆棘（EnchantmentThorns.java:48）只在 `canApply` 路径（铁砧）放行；附魔台的 `mapEnchantmentData` 走 `enchantment.type.canEnchantItem(item)`（EnchantmentHelper.java:491），抽不出这些组合。
- **`Enchantment.type` 非 final**：`EnchantmentProtection` 构造中把摔落保护改成 `ARMOR_FEET`（EnchantmentProtection.java:42）。不要假设 type 与构造参数一致。
- **`setEnchantments` 对附魔书是追加语义**：走 `Items.enchanted_book.addEnchantment`（EnchantmentHelper.java:113），对已有 `StoredEnchantments` 的书会叠加而非替换；且书分支不清理旧数据。用它"改写"附魔书前先清 NBT。
- **`addRandomEnchantment` 会原地改物品类型**：book → enchanted_book 用 `p_77504_1_.setItem(Items.enchanted_book)`（:391），传入的 ItemStack 被就地修改。
- **互斥规则必须双向声明**：`EnchantmentLootBonus.canApplyTogether`（:52-55）排斥 silkTouch，`EnchantmentUntouching.canApplyTogether`（:42-45）排斥 fortune——两边都写了；新增互斥若只写一边，`buildEnchantmentList` 的过滤（EnchantmentHelper.java:457）方向不同结果不同。
- **本包与 LWJGL3 移植无直接接触**：纯逻辑代码，无 GL/输入依赖；移植注意点仅剩上面的线程安全问题。

## 交叉引用

- entity → `EntityLivingBase#applyPotionDamageCalculations`（getEnchantmentModifierDamage）、`EntityLivingBase#moveEntityWithHeading`（getDepthStriderModifier）、`EntityLivingBase`（getRespiration、getLootingModifier）
- entity → `Entity#（受击/命中路径, Entity.java:2814/2817）`（applyThornEnchantments / applyArthropodEnchantments）
- entity.player → `EntityPlayer#attackTargetEntityWithCurrentItem`（getModifierForCreature、getKnockbackModifier、getFireAspectModifier）、`EntityPlayer#getToolDigEfficiency`（getEfficiencyModifier、getAquaAffinityModifier）
- entity.monster → `EntityMob#attackEntityAsMob`、`EntitySkeleton#（射箭附魔, EntitySkeleton.java:343-357）`
- entity.projectile → `EntityArrow#onUpdate（命中, :339-340）`、`EntityFishHook#（:466/580-581）`（getLuckOfSeaModifier、getLureModifier）
- entity.passive → `EntityVillager#（交易生成, :1023）`（addRandomEnchantment）
- item → `ItemStack#getTooltip / attemptDamageItem`（getTranslatedName、EnchantmentDurability.negateDamage）、`ItemBow#onPlayerStoppedUsing（:28-67）`（power/punch/flame/infinity 查询）、`ItemEnchantedBook#（StoredEnchantments 读写）`
- inventory → `ContainerEnchantment#（:203/:311）`（calcItemStackEnchantability、buildEnchantmentList）、`ContainerRepair#updateRepairOutput（:183/:244/:381）`（getEnchantments、setEnchantments）
- block → `Block#harvestBlock（:990/:1001）`、`BlockIce#harvestBlock（:38/:55）`（getSilkTouchModifier、getFortuneModifier）
- client.renderer → `EntityRenderer#（水下雾, :1838/:1994）`（getRespiration）
- client.gui.inventory → `GuiContainerCreative#（搜索页, :631）`（getEnchantments）
- command → `CommandEnchant`（getEnchantmentByLocation、func_181077_c）
- util → `WeightedRandom#getRandomItem`（EnchantmentData 抽取）、`WeightedRandomFishable#（:43）`（addRandomEnchantment）
- nbt → `NBTTagCompound` / `NBTTagList`（"ench" 列表读写）
- potion → `Potion#moveSlowdown`（EnchantmentDamage 节肢缓慢）

## 覆盖声明

完整读取了 21/21 个文件（每个文件从第 1 行读到末行）。逐行精读：`Enchantment`、`EnchantmentHelper`、`EnchantmentProtection`、`EnchantmentDamage`、`EnchantmentThorns`、`EnchantmentDurability`、`EnumEnchantmentType`、`EnchantmentData`。其余 13 个子类（ArrowDamage/ArrowFire/ArrowInfinite/ArrowKnockback/Digging/FireAspect/FishingSpeed/Knockback/LootBonus/Oxygen/Untouching/WaterWalker/WaterWorker）体量均 ≤57 行、结构同构（构造器 + 三个 enchantability 覆写），也已全文读取并核对数值。交叉引用调用点行号来自 grep 输出，未逐一打开外部文件核实上下文语义（EntityRenderer、ContainerEnchantment 等的具体方法名基于行号与常识推断，方法名以外的行号本身准确）。
