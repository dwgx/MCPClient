---
area: net/minecraft/client/renderer/entity
slug: mc-client-renderer-entity
files: 81
lines: 7467
tier: B
---

# net/minecraft/client/renderer/entity — 实体渲染器

## 定位

本包是客户端**实体渲染**的全部实现：把世界里每个 `Entity`（生物、玩家、掉落物、矿车、弹射物、闪电、画等）画到屏幕上。核心是三层结构：

1. `RenderManager` — 调度中心。持有 `Class<? extends Entity> -> Render` 映射表，缓存摄像机视角信息（`playerViewX/Y`、`viewerPosX/Y/Z`、`renderPosX/Y/Z`），把每次实体渲染分派给对应的 `Render` 子类。
2. `Render<T extends Entity>` 及其子类树（`RendererLivingEntity` -> `RenderLiving` -> `RenderBiped` -> 具体生物）— 每种实体一个渲染器，负责矩阵变换、贴图绑定、模型绘制、阴影/着火/名牌。
3. `layers/` 子包 — `LayerRenderer` 接口的实现，在主模型之上叠加渲染：盔甲、手持物品、披风、头颅、羊毛、蜘蛛眼等。

另外 `RenderItem`（本包中最大的类，1074 行）虽然名字里带 Render，但它不继承 `Render`：它是 **ItemStack 渲染器**，负责把物品模型画到 GUI、地面、实体手上，并维护 `Item -> ModelResourceLocation` 的注册表（`registerItems()`）。

调用关系：
- 上游：`Minecraft` 构造期创建 `RenderItem` 与 `RenderManager`（`Minecraft.java:555-556`）；每帧 `RenderGlobal.renderEntities` 调 `renderManager.cacheActiveRenderInfo(...)` / `setRenderPosition(...)` / `renderEntitySimple(...)`（`RenderGlobal.java:568-678`）；各 GUI 用 `itemRender.renderItemAndEffectIntoGUI(...)` 画物品格子；`ItemRenderer`（第一人称手臂）调 `RenderPlayer.renderRightArm/renderLeftArm`。
- 下游：`client.model.*`（ModelBase 系列）、`GlStateManager`/`OpenGlHelper`/`Tessellator`/`WorldRenderer`（绘制原语）、`TextureManager`（贴图）、`BlockRendererDispatcher`（方块形态渲染）、`TileEntitySkullRenderer`/`TileEntityMobSpawnerRenderer`/`TileEntityItemStackRenderer`（内置模型）、`scoreboard`（名牌可见性与队伍颜色）。

如果本包消失：世界里所有实体（包括其他玩家、掉落物、弹射物）不可见，GUI 物品格子全部空白，第一人称手臂消失，实体阴影/着火/名牌全无 —— 客户端画面只剩方块世界。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| RenderItem | 1074 | implements IResourceManagerReloadListener | ItemStack 渲染（GUI/实体/世界）+ 物品模型注册表 + 附魔光效 + 耐久条/堆叠数 |
| RendererLivingEntity | 599 | extends Render\<T extends EntityLivingBase\> | 活体实体渲染骨架：插值旋转、模型动画、受击变红、层渲染、名牌 |
| RenderManager | 498 | （无父类） | 实体渲染调度器：渲染器映射表、视角缓存、doRenderEntity 分派、调试碰撞箱 |
| Render | 385 | 抽象基类 | 所有实体渲染器的基类：视锥剔除、阴影、着火、名牌标签 |
| RenderPlayer | 209 | extends RendererLivingEntity\<AbstractClientPlayer\> | 玩家渲染：皮肤/slim 手臂、模型部件可见性、睡觉姿势、第一人称手臂 |
| RenderGuardian | 181 | extends RenderLiving\<EntityGuardian\> | 守卫者 + 激光束渲染（含束线视锥补充判断） |
| RenderEntityItem | 163 | extends Render\<EntityItem\> | 掉落物：悬浮/旋转动画，按堆叠数画 1-5 份模型 |
| layers/LayerArmorBase | 158 | implements LayerRenderer\<EntityLivingBase\> | 盔甲层抽象基类：4 槽位渲染、皮革染色、附魔光效、盔甲贴图缓存 |
| RenderLiving | 157 | extends RendererLivingEntity\<T extends EntityLiving\> | EntityLiving 渲染：名牌规则（指向实体）、拴绳渲染、拴绳视锥补充 |
| RenderDragon | 154 | extends RenderLiving\<EntityDragon\> | 末影龙：死亡爆炸半透明、受击变红、水晶治疗光束 |
| RenderLightningBolt | 149 | extends Render\<EntityLightningBolt\> | 闪电：以 boltVertex 为种子的随机折线多层四边形 |
| RenderPainting | 146 | extends Render\<EntityPainting\> | 画：按 EnumArt 尺寸分 16x16 块画六面体，逐块取方块光照 |
| RenderMinecart | 127 | extends Render\<T extends EntityMinecart\> | 矿车：轨道姿态插值、受击摇晃、内部展示方块 |
| layers/LayerCustomHead | 117 | implements LayerRenderer\<EntityLivingBase\> | 头部佩戴层：方块（南瓜等）或 skull（读 NBT SkullOwner） |
| RenderFish | 112 | extends Render\<EntityFishHook\> | 鱼漂 billboard + 从竿尖到鱼漂的钓线折线 |
| RenderHorse | 102 | extends RenderLiving\<EntityHorse\> | 马/驴/骡/僵尸马/骷髅马贴图选择，变种贴图用 LayeredTexture 动态合成缓存 |
| layers/LayerHeldItemWitch | 99 | implements LayerRenderer\<EntityWitch\> | 女巫手持物品层（挂在 villagerNose 上，特殊角度） |
| RenderZombie | 96 | extends RenderBiped\<EntityZombie\> | 僵尸：普通/村民僵尸双模型双层列表切换，转化时抖动 |
| RenderArrow | 95 | extends Render\<EntityArrow\> | 箭：贴图四边形拼装，arrowShake 抖动 |
| RenderVillager | 76 | extends RenderLiving\<EntityVillager\> | 村民：按 profession 选贴图，幼年缩放 0.5 |
| layers/LayerHeldItem | 75 | implements LayerRenderer\<EntityLivingBase\> | 第三人称手持物品层（挂在手臂骨骼后） |
| layers/LayerEnderDragonDeath | 74 | implements LayerRenderer\<EntityDragon\> | 末影龙死亡光柱（随机方向渐变三角扇） |
| RenderXPOrb | 74 | extends Render\<EntityXPOrb\> | 经验球 billboard，按 XP 选贴图格，正弦变色 |
| RenderTNTPrimed | 74 | extends Render\<EntityTNTPrimed\> | 点燃 TNT：引信末期膨胀 + 白色闪烁覆盖层 |
| RenderFallingBlock | 73 | extends Render\<EntityFallingBlock\> | 下落方块：直接用 BlockModelRenderer 画烘焙模型 |
| layers/LayerArrow | 70 | implements LayerRenderer\<EntityLivingBase\> | 身上插的箭：按 getArrowCountInEntity 在随机模型盒位置画 EntityArrow |
| RenderFireball | 64 | extends Render\<EntityFireball\> | 火球 billboard（fire_charge 粒子图标） |
| layers/LayerCape | 63 | implements LayerRenderer\<AbstractClientPlayer\> | 披风：按移动/潜行摆动角度渲染 renderCape |
| RenderRabbit | 63 | extends RenderLiving\<EntityRabbit\> | 兔子：按 getRabbitType 选贴图，名字 "Toast" 彩蛋 |
| RenderCreeper | 62 | extends RenderLiving\<EntityCreeper\> | 苦力怕：膨胀缩放 + 爆炸前白闪（getColorMultiplier） |
| layers/LayerMooshroomMushroom | 61 | implements LayerRenderer\<EntityMooshroom\> | 哞菇背上/头上的红蘑菇方块 |
| RenderBoat | 61 | extends Render\<EntityBoat\> | 船：受击摇晃 + ModelBoat |
| ArmorStandRenderer | 57 | extends RendererLivingEntity\<EntityArmorStand\> | 盔甲架：ModelArmorStand + 盔甲/手持/头颅层 |
| layers/LayerSheepWool | 55 | implements LayerRenderer\<EntitySheep\> | 羊毛层：染色渲染，"jeb_" 彩虹渐变彩蛋 |
| RenderWither | 55 | extends RenderLiving\<EntityWither\> | 凋灵：Boss 血条注册、无敌期贴图与缩放 |
| RenderOcelot | 55 | extends RenderLiving\<EntityOcelot\> | 豹猫：按 getTameSkin 选贴图，驯服后缩 0.8 |
| layers/LayerWitherAura | 54 | implements LayerRenderer\<EntityWither\> | 凋灵护甲光环（滚动纹理坐标 + additive blend） |
| layers/LayerSpiderEyes | 54 | implements LayerRenderer\<EntitySpider\> | 蜘蛛发光眼（blendFunc(1,1) + 满亮度 lightmap 61680） |
| RenderGiantZombie | 54 | extends RenderLiving\<EntityGiantZombie\> | 巨人：整体 scale=6 + 僵尸盔甲/手持层 |
| layers/LayerHeldBlock | 53 | implements LayerRenderer\<EntityEnderman\> | 末影人手持方块 |
| RenderSnowball | 53 | extends Render\<T extends Entity\> | 通用"物品 billboard"弹射物渲染（雪球/蛋/末影珍珠/烟花等） |
| RenderSkeleton | 53 | extends RenderBiped\<EntitySkeleton\> | 骷髅：凋灵骷髅贴图 + 1.2 倍缩放 |
| layers/LayerCreeperCharge | 52 | implements LayerRenderer\<EntityCreeper\> | 闪电苦力怕蓝色电甲（滚动纹理 + additive blend） |
| layers/LayerBipedArmor | 52 | extends LayerArmorBase\<ModelBiped\> | 人形盔甲层：初始化 ModelBiped(0.5/1.0)，按槽位控制部件可见性 |
| RenderEnderman | 52 | extends RenderLiving\<EntityEnderman\> | 末影人：尖叫时位置抖动、isCarrying/isAttacking 模型标志 |
| RenderTntMinecart | 51 | extends RenderMinecart\<EntityMinecartTNT\> | TNT 矿车：引信膨胀 + 白闪覆盖 |
| layers/LayerIronGolemFlower | 50 | implements LayerRenderer\<EntityIronGolem\> | 铁傀儡手持玫瑰（挂在右臂旋转角上） |
| RenderWolf | 50 | extends RenderLiving\<EntityWolf\> | 狼：湿身变暗、驯服/愤怒贴图、尾巴角度 |
| RenderWitch | 50 | extends RenderLiving\<EntityWitch\> | 女巫：field_82900_g 手持标志 + 0.9375 缩放 |
| RenderBat | 48 | extends RenderLiving\<EntityBat\> | 蝙蝠：0.35 缩放，倒挂/飞行姿态位移 |
| RenderSlime | 47 | extends RenderLiving\<EntitySlime\> | 史莱姆：阴影按体型、squishFactor 压扁缩放 |
| layers/LayerDeadmau5Head | 45 | implements LayerRenderer\<AbstractClientPlayer\> | 玩家名 "deadmau5" 的双耳朵彩蛋 |
| layers/LayerEndermanEyes | 44 | implements LayerRenderer\<EntityEnderman\> | 末影人发光眼 |
| layers/LayerEnderDragonEyes | 44 | implements LayerRenderer\<EntityDragon\> | 末影龙发光眼 |
| RenderSquid | 43 | extends RenderLiving\<EntitySquid\> | 鱿鱼：squidPitch/squidYaw 姿态旋转、触手角度 |
| RenderLeashKnot | 43 | extends Render\<EntityLeashKnot\> | 栅栏上的拴绳结 |
| RenderBiped | 42 | extends RenderLiving\<T extends EntityLiving\> | 人形生物基类（默认 steve 贴图 + 头颅层/手持层） |
| layers/LayerSnowmanHead | 39 | implements LayerRenderer\<EntitySnowman\> | 雪傀儡南瓜头 |
| RenderIronGolem | 39 | extends RenderLiving\<EntityIronGolem\> | 铁傀儡：行走时躯干左右摆动 + 玫瑰层 |
| layers/LayerSlimeGel | 38 | implements LayerRenderer\<EntitySlime\> | 史莱姆半透明外胶层 |
| RenderGhast | 38 | extends RenderLiving\<EntityGhast\> | 恶魂：攻击时换贴图，4.5 倍缩放 |
| RenderMagmaCube | 37 | extends RenderLiving\<EntityMagmaCube\> | 岩浆怪：squish 压扁缩放 |
| layers/LayerWolfCollar | 36 | implements LayerRenderer\<EntityWolf\> | 狼项圈染色层 |
| RenderPigZombie | 34 | extends RenderBiped\<EntityPigZombie\> | 僵尸猪人（僵尸模型盔甲层） |
| RenderChicken | 34 | extends RenderLiving\<EntityChicken\> | 鸡：翅膀扇动角度作 rotation float |
| layers/LayerSaddle | 33 | implements LayerRenderer\<EntityPig\> | 猪鞍层（ModelPig(0.5F) 换贴图重画） |
| RenderCaveSpider | 33 | extends RenderSpider\<EntityCaveSpider\> | 洞穴蜘蛛：0.7 缩放 + 专属贴图 |
| RenderEntity | 32 | extends Render\<Entity\> | 兜底渲染器：画白色 AABB 盒（Entity.class 的默认映射） |
| RenderSpider | 30 | extends RenderLiving\<T extends EntitySpider\> | 蜘蛛：发光眼层，死亡旋转 180 |
| RenderSnowMan | 30 | extends RenderLiving\<EntitySnowman\> | 雪傀儡 + 南瓜头层 |
| RenderSilverfish | 28 | extends RenderLiving\<EntitySilverfish\> | 蠹虫（死亡旋转 180） |
| RenderEndermite | 28 | extends RenderLiving\<EntityEndermite\> | 末影螨（死亡旋转 180） |
| RenderSheep | 25 | extends RenderLiving\<EntitySheep\> | 羊 + 羊毛层 |
| RenderPig | 25 | extends RenderLiving\<EntityPig\> | 猪 + 鞍层 |
| RenderMooshroom | 25 | extends RenderLiving\<EntityMooshroom\> | 哞菇 + 蘑菇层 |
| RenderMinecartMobSpawner | 24 | extends RenderMinecart\<EntityMinecartMobSpawner\> | 刷怪笼矿车：额外画笼内旋转小怪 |
| RenderCow | 23 | extends RenderLiving\<EntityCow\> | 牛 |
| RenderBlaze | 23 | extends RenderLiving\<EntityBlaze\> | 烈焰人 |
| layers/LayerVillagerArmor | 18 | extends LayerBipedArmor | 村民僵尸盔甲层（ModelZombieVillager 变体） |
| RenderPotion | 18 | extends RenderSnowball\<EntityPotion\> | 喷溅药水：按 getPotionDamage 构造 ItemStack |
| layers/LayerRenderer | 10 | interface | 层渲染接口：doRenderLayer + shouldCombineTextures |

## 核心类详解

### RenderManager（RenderManager.java）

关键字段：
- `private Map<Class<? extends Entity>, Render<? extends Entity>> entityRenderMap`（RenderManager.java:106）— 构造函数里注册全部约 60 个渲染器（140-198 行）。
- `private Map<String, RenderPlayer> skinMap` / `private RenderPlayer playerRenderer`（107-108）— `"default"` 与 `"slim"` 两套玩家渲染器（199-201）。
- `public World worldObj; public Entity livingPlayer; public Entity pointedEntity; public float playerViewY; public float playerViewX;`（118-124）— 每帧由 cacheActiveRenderInfo 刷新的视角缓存。
- `public double viewerPosX/viewerPosY/viewerPosZ`（128-130）与 `private double renderPosX/renderPosY/renderPosZ`（112-114）。
- `private boolean renderOutlines`（131，观察者模式的轮廓渲染）、`private boolean renderShadow = true`（132）、`private boolean debugBoundingBox`（135，F3+B）。

关键方法（逐字）：
- `public RenderManager(TextureManager renderEngineIn, RenderItem itemRendererIn)`（RenderManager.java:137）— 由 `Minecraft` 初始化时调用一次（Minecraft.java:556）。
- `public <T extends Entity> Render<T> getEntityRenderObject(Entity entityIn)`（224）— 玩家走 skinMap，其余走 `getEntityClassRenderObject`（211，未命中时沿父类向上找并**回写缓存**）。
- `public void cacheActiveRenderInfo(World worldIn, FontRenderer textRendererIn, Entity livingPlayerIn, Entity pointedEntityIn, GameSettings optionsIn, float partialTicks)`（238）— 每帧由 `RenderGlobal.renderEntities` 调（RenderGlobal.java:569）；处理睡觉视角与第三人称反转。
- `public boolean renderEntityStatic(Entity entity, float partialTicks, boolean hideDebugBox)`（310）— 位置插值 + lightmap 坐标设置 + 着火满亮度（`i = 15728880`），再调 doRenderEntity。
- `public boolean doRenderEntity(Entity entity, double x, double y, double z, float entityYaw, float partialTicks, boolean hideDebugBox)`（360）— 单实体渲染总入口：取渲染器、传播 renderOutlines、`render.doRender(...)`、`render.doRenderShadowAndFire(...)`、调试碰撞箱；异常统一转 `ReportedException` 崩溃报告。
- `public boolean shouldRender(Entity entityIn, ICamera camera, double camX, double camY, double camZ)`（304）— RenderGlobal 剔除入口。

### Render（Render.java）

关键字段：`protected final RenderManager renderManager;`（Render.java:26）、`protected float shadowSize;`（27）、`protected float shadowOpaque = 1.0F;`（32）。

关键方法：
- `public boolean shouldRender(T livingEntity, ICamera camera, double camX, double camY, double camZ)`（39）— 距离 + 视锥判定；AABB 有 NaN 或退化时用 ±2 兜底盒。
- `public void doRender(T entity, double x, double y, double z, float entityYaw, float partialTicks)`（54）— 基类实现只画名牌；子类覆写后通常 `super.doRender` 收尾。
- `protected abstract ResourceLocation getEntityTexture(T entity);`（80）— 唯一抽象方法。
- `public void doRenderShadowAndFire(Entity entityIn, double x, double y, double z, float yaw, float partialTicks)`（299）— `options.entityShadows` 且 shadowSize>0 时画阴影（透明度随距离 256 衰减），`canRenderOnFire()` 时画火焰 billboard；由 `RenderManager.doRenderEntity` 在主渲染后调用（RenderManager.java:388）。
- `protected void renderLivingLabel(T entityIn, String str, double x, double y, double z, int maxDistance)`（332）— 名牌绘制（黑底 + 两遍文字，disableDepth 透视）。

### RendererLivingEntity（RendererLivingEntity.java）

关键字段：
- `protected ModelBase mainModel;`（33）、`protected FloatBuffer brightnessBuffer = GLAllocation.createDirectFloatBuffer(4);`（34）、`protected List<LayerRenderer<T>> layerRenderers = Lists.<LayerRenderer<T>>newArrayList();`（35）、`protected boolean renderOutlines = false;`（36）。
- `private static final DynamicTexture textureBrightness = new DynamicTexture(16, 16);`（32）— 静态块（588-598）初始化为全白，用于受击叠色的第三纹理单元。

关键方法：
- `public void doRender(T entity, double x, double y, double z, float entityYaw, float partialTicks)`（89）— 活体渲染主流程：`interpolateRotation` 求身体/头部 yaw（99-100）→ 骑乘时钳制头身夹角 ±85°（103-126）→ `renderLivingAt`（129）→ `rotateCorpse`（131）→ `scale(-1,-1,1)` 翻转 + `preRenderCallback`（133-134）→ `translate(0, -1.5078125F, 0)`（136）→ `mainModel.setLivingAnimations` / `setRotationAngles`（151-152）→ 轮廓模式走 `setScoreTeamColor`，正常模式走 `setDoRenderBrightness` + `renderModel` + `renderLayers`（154-180）→ 异常仅 `logger.error("Couldn't render entity", exception)` 吞掉不崩溃（184-187）→ 非轮廓时 `super.doRender` 画名牌（195-198）。
- `protected void renderModel(T entitylivingbaseIn, float p_77036_2_, float p_77036_3_, float p_77036_4_, float p_77036_5_, float p_77036_6_, float scaleFactor)`（246）— 隐身且非本队时以 alpha 0.15 半透明绘制。
- `protected boolean setBrightness(T entitylivingbaseIn, float partialTicks, boolean combineTextures)`（285）— 受击/死亡红闪与 `getColorMultiplier` 叠色：直接用 `GL11.glTexEnvi(GL_TEXTURE_ENV, ...)` 配 GL_COMBINE 多纹理组合（304-360）；`unsetBrightness()`（366）恢复。
- `protected void renderLayers(T entitylivingbaseIn, float p_177093_2_, float p_177093_3_, float partialTicks, float p_177093_5_, float p_177093_6_, float p_177093_7_, float p_177093_8_)`（459）— 遍历 layerRenderers，逐层按 `shouldCombineTextures()` 包 setBrightness。
- `protected void preRenderCallback(T entitylivingbaseIn, float partialTickTime)`（490）— 空实现，子类用来做缩放（几乎所有变体大小都在这）。
- `public void renderName(T entity, double x, double y, double z)`（494）/ `protected boolean canRenderName(T entity)`（547）— 名牌距离（潜行 32 否则 64）与 Team.EnumVisible 规则。
- `protected void rotateCorpse(T bat, float p_77043_2_, float p_77043_3_, float partialTicks)`（415）— 死亡翻倒 + "Dinnerbone"/"Grumm" 倒挂彩蛋。
- `public void setRenderOutlines(boolean renderOutlinesIn)`（583）。

### RenderPlayer（RenderPlayer.java）

- `public RenderPlayer(RenderManager renderManager, boolean useSmallArms)`（31）— 挂 6 层：LayerBipedArmor、LayerHeldItem、LayerArrow、LayerDeadmau5Head、LayerCape、LayerCustomHead（35-40）。
- `public void doRender(AbstractClientPlayer entity, double x, double y, double z, float entityYaw, float partialTicks)`（51）— `if (!entity.isUser() || this.renderManager.livingPlayer == entity)` 跳过第一人称本人；非 EntityPlayerSP 潜行时 y-0.125。
- `private void setModelVisibilities(AbstractClientPlayer clientPlayer)`（67）— 按皮肤部件开关 wear 层、按手持物品/使用动作设 `heldItemRight`（1/3）与 `aimedBow`。
- `public void renderRightArm(AbstractClientPlayer clientPlayer)`（157）/ `public void renderLeftArm(AbstractClientPlayer clientPlayer)`（169）— 供 `ItemRenderer`（第一人称）调用（ItemRenderer.java:150,161）。
- `protected void renderOffsetLivingLabel(AbstractClientPlayer entityIn, double x, double y, double z, String str, float p_177069_9_, double p_177069_10_)`（139）— 名牌下附加 belowName 计分板（`getObjectiveInDisplaySlot(2)`）。

### RenderLiving（RenderLiving.java）

- `protected boolean canRenderName(T entity)`（21）— 只有 `getAlwaysRenderNameTagForRender()` 或（有自定义名且是 `renderManager.pointedEntity`）才显示名牌。
- `public boolean shouldRender(T livingEntity, ICamera camera, double camX, double camY, double camZ)`（26）— 被拴住时拴绳目标在视锥内也要渲染。
- `protected void renderLeash(T entityLivingIn, double x, double y, double z, float entityYaw, float partialTicks)`（68）— 手绘拴绳双三角带；`public void setLightmap(T entityLivingIn, float partialTicks)`（52）供发光眼层恢复光照。

### RenderItem（RenderItem.java）

关键字段：`public float zLevel;`（69）、`private final ItemModelMesher itemModelMesher;`（70）、`private final TextureManager textureManager;`（71）、`private static final ResourceLocation RES_ITEM_GLINT`（63）。

- `public RenderItem(TextureManager textureManager, ModelManager modelManager)`（73）— 构造时 `registerItems()`（522-1068，约 510 条 `registerBlock/registerItem` + 5 个 `ItemMeshDefinition` 匿名类：药水/刷怪蛋/旗帜/附魔书/填充地图）。
- `public void renderItem(ItemStack stack, IBakedModel model)`（140）— `model.isBuiltInRenderer()` 时走 `TileEntityItemStackRenderer.instance.renderByItem(stack)`（箱子/旗帜/头颅），否则 `renderModel` + `hasEffect()` 时 `renderEffect(model)`（170，紫色滚动 glint，颜色常量 `-8372020`）。
- `public void renderItemModelForEntity(ItemStack stack, EntityLivingBase entityToRenderFor, ItemCameraTransforms.TransformType cameraTransformType)`（272）— 钓竿抛出 / 弓拉弦（18/13/0 tick 阈值切 `bow_pulling_2/1/0`）的模型替换。
- `public void renderItemIntoGUI(ItemStack stack, int x, int y)`（353）、`public void renderItemAndEffectIntoGUI(final ItemStack stack, int xPosition, int yPosition)`（398，含崩溃报告包装）、`public void renderItemOverlayIntoGUI(FontRenderer fr, ItemStack stack, int xPosition, int yPosition, String text)`（455，堆叠数 + 耐久条）— GUI 物品格子三件套，被所有容器 GUI 调用。
- `public void onResourceManagerReload(IResourceManager resourceManager)`（1070）— 资源包重载时 `itemModelMesher.rebuildCache()`。

### LayerArmorBase（layers/LayerArmorBase.java）

- `public void doRenderLayer(...)`（32）— 按槽位 4(头)→3(胸)→2(腿)→1(靴) 依次 `renderLayer`（45）；皮革先染色画一遍再画 overlay；`itemstack.isItemEnchanted()` 时 `renderGlint`（101）。
- `private ResourceLocation getArmorResource(ItemArmor p_177178_1_, boolean p_177178_2_, String p_177178_3_)`（141）— 贴图路径模板 `"textures/models/armor/%s_layer_%d%s.png"`（143），结果缓存于 `ARMOR_TEXTURE_RES_MAP`。
- `protected abstract void initArmor();`（155）— 各生物用匿名子类替换 `modelLeggings/modelArmor`（如 RenderSkeleton.java:19-26、RenderZombie.java:32-39）。

## 时序与生命周期

全部工作发生在**客户端主线程（渲染线程）**。本包没有 tick 逻辑，只有每帧逻辑；不接触 Netty EventLoop 与服务端线程。

1. **初始化（一次）**：`Minecraft` 启动时先 `new RenderItem(this.renderEngine, this.modelManager)`（构造时执行完整 `registerItems()`），紧接着 `new RenderManager(this.renderEngine, this.renderItem)`（构造时实例化所有实体渲染器，各渲染器构造时又实例化其 Model 与 Layer）。见 Minecraft.java:555-556。`RendererLivingEntity` 的静态块另建 16x16 全白 `textureBrightness`。
2. **每帧**（`EntityRenderer` → `RenderGlobal.renderEntities`）：
   - `renderManager.cacheActiveRenderInfo(...)`（RenderGlobal.java:569）刷新视角缓存；
   - `renderManager.setRenderPosition(d3, d4, d5)`（RenderGlobal.java:580）设置插值后的摄像机原点；
   - 对每个通过 `shouldRender` 的实体调 `renderManager.renderEntitySimple(entity1, partialTicks)`（RenderGlobal.java:593,615,671）→ `renderEntityStatic` → `doRenderEntity` → 具体 `Render.doRender` → （活体）模型动画 + 层渲染 → `doRenderShadowAndFire`；
   - 观察者高亮实体走 `setRenderOutlines(true/false)` 包裹（RenderGlobal.java:605-619）。
3. **GUI 渲染时**：容器 GUI 每帧对每格调 `renderItemAndEffectIntoGUI` / `renderItemOverlays`；`GuiInventory` 画玩家模型前后 `rendermanager.setPlayerViewY(180.0F)` / `setRenderShadow(false/true)`（GuiInventory.java:119-122）。
4. **资源包重载**：`RenderItem.onResourceManagerReload` 重建物品模型缓存。
5. **世界切换**：`RenderManager.set(World worldIn)`（473）更新 `worldObj`。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public boolean doRenderEntity(Entity entity, double x, double y, double z, float entityYaw, float partialTicks, boolean hideDebugBox)` | RenderManager.java:360 | 每帧每个可见实体一次 | **单实体渲染总闸**：ESP/取消渲染/替换渲染器/全局后处理 | 内部异常会转 ReportedException 直接崩溃；坐标是相对摄像机的 |
| `public boolean renderEntityStatic(Entity entity, float partialTicks, boolean hideDebugBox)` | RenderManager.java:310 | doRenderEntity 之前，负责插值与 lightmap | 改实体亮度（着火全亮逻辑在此，`i = 15728880`）、位置插值篡改 | ticksExisted==0 时会重置 lastTickPos |
| `public boolean shouldRender(Entity entityIn, ICamera camera, double camX, double camY, double camZ)` | RenderManager.java:304（基类实现 Render.java:39） | RenderGlobal 剔除阶段 | 强制渲染视锥外实体（穿墙 ESP 的前置）、按类型隐藏实体 | 返回 true 过多直接拖帧率 |
| `public void cacheActiveRenderInfo(World worldIn, FontRenderer textRendererIn, Entity livingPlayerIn, Entity pointedEntityIn, GameSettings optionsIn, float partialTicks)` | RenderManager.java:238 | 每帧实体渲染开始前 | 自由视角（改 playerViewX/Y）、假第三人称 | thirdPersonView==2 时 yaw 已 +180，勿重复处理 |
| `public <T extends Entity> Render<T> getEntityRenderObject(Entity entityIn)` | RenderManager.java:224 | 每次分派 | 替换/包装某类实体的渲染器（配合 entityRenderMap 注入自定义 Render） | map 有父类回填缓存（RenderManager.java:218），替换要在首次渲染前 |
| `public void doRender(T entity, double x, double y, double z, float entityYaw, float partialTicks)` | RendererLivingEntity.java:89 | 每帧每个活体 | 模型动画/旋转/隐身/受击效果的总流程，改写可实现自定义动画 | 内部 try-catch 吞异常只打 log（186），错误易被掩盖 |
| `protected void preRenderCallback(T entitylivingbaseIn, float partialTickTime)` | RendererLivingEntity.java:490 | doRender 中模型绘制前 | 最干净的缩放/矩阵 hook（原版所有体型变化都在子类覆写这里） | 处于 `scale(-1,-1,1)` 翻转坐标系内 |
| `protected void renderModel(T entitylivingbaseIn, float p_77036_2_, float p_77036_3_, float p_77036_4_, float p_77036_5_, float p_77036_6_, float scaleFactor)` | RendererLivingEntity.java:246 | doRender 内，含隐身判定 | Chams/皮肤替换/强制可见（隐身实体在此被跳过或半透明化） | 半透明分支改了 depthMask 与 alphaFunc，需成对恢复 |
| `protected void renderLayers(T entitylivingbaseIn, float p_177093_2_, float p_177093_3_, float partialTicks, float p_177093_5_, float p_177093_6_, float p_177093_7_, float p_177093_8_)` | RendererLivingEntity.java:459 | 主模型之后 | 增删装备/披风/自定义层；`addLayer`/`removeLayer`（45/50）是公开的层注册点 | 旁观者玩家不走层（RendererLivingEntity.java:176-179） |
| `void doRenderLayer(E entitylivingbaseIn, float p_177141_2_, float p_177141_3_, float partialTicks, float p_177141_5_, float p_177141_6_, float p_177141_7_, float scale)` | layers/LayerRenderer.java:7 | renderLayers 逐层 | 实现该接口即可给任意活体加渲染层（光环/标记/自定义装备） | 必须自行恢复 GL 状态；`shouldCombineTextures()` 决定是否被 setBrightness 包裹 |
| `public void renderName(T entity, double x, double y, double z)` / `protected boolean canRenderName(T entity)` | RendererLivingEntity.java:494 / 547 | 名牌阶段 | 自定义名牌（血量/距离显示）、强制显示/隐藏 | canRenderName 里有 Team.EnumVisible 逻辑，覆写会绕过队伍隐藏规则 |
| `protected void renderLivingLabel(T entityIn, String str, double x, double y, double z, int maxDistance)` | Render.java:332 | 任意名牌/标签绘制 | 通用"浮空文字"绘制原语，可直接复用画自定义标签 | 内部 disableDepth，画完已恢复；受 renderManager.playerViewX/Y 影响 |
| `public void doRenderShadowAndFire(Entity entityIn, double x, double y, double z, float yaw, float partialTicks)` | Render.java:299 | doRenderEntity 中主渲染后 | 关闭/替换阴影与着火效果 | renderOutlines 模式下不会被调用（RenderManager.java:386-390） |
| `public void doRender(AbstractClientPlayer entity, double x, double y, double z, float entityYaw, float partialTicks)` | RenderPlayer.java:51 | 每帧每个可见玩家 | 玩家 ESP、皮肤替换、自视角第三人称身体（isUser 判断在此） | 第一人称时本人被跳过，改此判断可实现 Freecam 看见自己 |
| `private void setModelVisibilities(AbstractClientPlayer clientPlayer)` | RenderPlayer.java:67 | 玩家 doRender 前 | 控制皮肤外层/手持姿势（格挡=3、拉弓 aimedBow） | private，需覆写 doRender 或反射 |
| `public void renderRightArm(AbstractClientPlayer clientPlayer)` / `public void renderLeftArm(AbstractClientPlayer clientPlayer)` | RenderPlayer.java:157 / 169 | 第一人称 ItemRenderer 每帧 | 自定义第一人称手臂（动画/皮肤） | 会重置 swingProgress/isSneak 为 0 |
| `public void renderItem(ItemStack stack, IBakedModel model)` | RenderItem.java:140 | 所有物品渲染路径的汇聚点 | 全局物品渲染替换（自定义模型、2D/3D 切换） | isBuiltInRenderer 分支走 TileEntityItemStackRenderer，勿一刀切 |
| `public void renderItemModelForEntity(ItemStack stack, EntityLivingBase entityToRenderFor, ItemCameraTransforms.TransformType cameraTransformType)` | RenderItem.java:272 | 实体手持物品渲染 | 拉弓/钓竿模型切换逻辑在此，可改判定阈值或加自定义动态模型 | 只对 EntityPlayer 生效 |
| `public void renderItemAndEffectIntoGUI(final ItemStack stack, int xPosition, int yPosition)` | RenderItem.java:398 | 每个 GUI 物品格每帧 | 物品格渲染 hook（高亮、自定义 glint） | zLevel ±50 配对；异常会构造崩溃报告 |
| `public void renderItemOverlayIntoGUI(FontRenderer fr, ItemStack stack, int xPosition, int yPosition, String text)` | RenderItem.java:455 | 物品格前景（数量/耐久） | 自定义耐久条/数量显示 | 内部多次开关 depth/lighting，插入代码注意状态 |
| `protected ResourceLocation getEntityTexture(T entity)` | Render.java:80（各子类实现） | bindEntityTexture 时 | 皮肤/贴图替换的统一入口（每个渲染器都实现） | 返回 null 会使 renderModel 直接跳过绘制（Render.java:86-89） |
| `public void setRenderOutlines(boolean renderOutlinesIn)` | RenderManager.java:494 | RenderGlobal 观察者高亮时 | 复用原版轮廓管线做自定义高亮 | 轮廓模式跳过阴影、火、名牌 |
| `protected void rotateCorpse(T bat, float p_77043_2_, float p_77043_3_, float partialTicks)` | RendererLivingEntity.java:415 | doRender 定位后 | 自定义实体朝向/死亡动画 | Dinnerbone 倒挂彩蛋也在这里 |
| `public void set(World worldIn)` | RenderManager.java:473 | 世界切换 | 观察世界加载/卸载对渲染层的影响 | worldObj 为 null 期间不得渲染阴影（renderShadow 直接查 world） |

## 数据与协议

本包不做封包收发。仅两处涉及持久化数据格式：

**LayerCustomHead 读取头颅 NBT**（layers/LayerCustomHead.java:86-104）：

| 字段名 | 类型 | 读写方法 | 取值含义 |
|---|---|---|---|
| `SkullOwner` | NBT type 10（compound） | `nbttagcompound.getCompoundTag("SkullOwner")` → `NBTUtil.readGameProfileFromNBT(...)`（LayerCustomHead.java:92） | 完整 GameProfile（含皮肤纹理属性） |
| `SkullOwner` | NBT type 8（string） | `nbttagcompound.getString("SkullOwner")` → `TileEntitySkull.updateGameprofile(new GameProfile((UUID)null, s))`，随后 `nbttagcompound.setTag("SkullOwner", NBTUtil.writeGameProfile(new NBTTagCompound(), gameprofile))` **回写**（96-101） | 仅玩家名；渲染层会就地升级为 compound 形式 |
| `itemstack.getMetadata()` | int | `TileEntitySkullRenderer.instance.renderSkull(..., itemstack.getMetadata(), gameprofile, -1)`（106） | 头颅类型：0 骷髅 / 1 凋灵 / 2 僵尸 / 3 玩家 / 4 苦力怕（对应 RenderItem.java:1004-1008 的 skull_* 注册） |

**盔甲贴图路径约定**（layers/LayerArmorBase.java:143）：`String.format("textures/models/armor/%s_layer_%d%s.png", material, isLeggings ? 2 : 1, suffix)`，suffix 为 `"_overlay"`（皮革染色覆盖层）或空。

**物品模型注册表**：`RenderItem.registerItems()`（RenderItem.java:522）把 Item+metadata 映射到 `ModelResourceLocation(identifier, "inventory")`；动态模型（药水 splash/drinkable、刷怪蛋、旗帜、附魔书、filled_map）用 `ItemMeshDefinition` 回调（如 RenderItem.java:969-975）。这是资源包物品模型系统的客户端侧注册处。

## 不变量与陷阱

- **所有渲染调用必须在主线程且持有 GL 上下文**。本包大量直接改 GL 全局状态（矩阵栈、blend、depthMask、纹理单元），任何 hook 必须把状态成对恢复，否则污染后续所有绘制。
- **坐标系约定**：传入 `doRender` 的 x/y/z 是"实体插值位置 − renderPos（摄像机）"的相对坐标（RenderManager.java:334）。活体渲染在 `doRender` 内做了 `GlStateManager.scale(-1.0F, -1.0F, 1.0F)`（RendererLivingEntity.java:133）加 `translate(0.0F, -1.5078125F, 0.0F)`（136）——preRenderCallback 与模型渲染处于这个翻转坐标系。
- **异常语义不一致**：`RenderManager.doRenderEntity` 出错会构造崩溃报告并抛出（RenderManager.java:381）；但 `RendererLivingEntity.doRender` 内部把异常吞成一条 `logger.error("Couldn't render entity", exception)`（186）——活体渲染 bug 表现为刷屏日志而不是崩溃。
- **entityRenderMap 的父类回填**：`getEntityClassRenderObject` 未命中时沿超类查找并把结果写回 map（RenderManager.java:215-219）。想给某实体子类注册专属渲染器必须在它第一次被渲染之前，否则 map 里已缓存了父类渲染器。
- **多纹理受击变红是固定管线 GL_COMBINE**：`setBrightness`/`unsetBrightness` 用 `GL11.glTexEnvi` 直接编排三个纹理单元（RendererLivingEntity.java:304-360, 370-404）。这是 LWJGL3 移植中最脆的一段——依赖兼容性 profile 的固定管线 texenv；任何"现代化"改动（core profile、着色器化）都会先在这里坏。`OpenGlHelper.GL_COMBINE` 等常量必须与 shim 的 GL13/ARB 值一致。
- **LWJGL3 移植面**：本包直接 import `org.lwjgl.opengl.GL11` 的文件有 Render、RendererLivingEntity、RenderArrow、RenderGuardian（`glTexParameterf` 设 GL_REPEAT=10497、`glNormal3f`、`glTexEnvfv`）。其余大量魔法数字（5888=GL_MODELVIEW、5890=GL_TEXTURE、770/771/768/772=blend factor、514/515=depth func、1028/1029=cull face、7=GL_QUADS、5=GL_TRIANGLE_STRIP、3=GL_LINE_STRIP、6=GL_TRIANGLE_FAN）经 `GlStateManager` 转发，语义未变。
- **贴图为 null 即不画**：`bindEntityTexture` 在 `getEntityTexture` 返回 null 时返回 false（Render.java:82-95），`renderModel` 据此直接跳过（RendererLivingEntity.java:253-256）。RenderEntity/RenderLightningBolt 合法地返回 null。
- **阴影渲染依赖 worldObj 与 options**：`doRenderShadowAndFire` 以 `this.renderManager.options != null` 为总开关（Render.java:301）；GUI 内渲染实体（GuiInventory）靠 `setRenderShadow(false)` 关阴影，忘记恢复会把世界阴影一起关掉。
- **RenderZombie 会整体切换 mainModel 与 layerRenderers 引用**（RenderZombie.java:71-85），对它做层注入时要同时加进 `field_177121_n` 和 `field_177122_o` 两个列表，否则村民僵尸形态丢层。
- **RenderGuardian 检测 ModelGuardian 重建**：`doRender` 每帧比对 `func_178706_a()` 并可能 `new ModelGuardian()`（RenderGuardian.java:71-75），持有其 mainModel 引用不可靠。
- **RenderItem.zLevel 是共享可变状态**：`renderItemAndEffectIntoGUI` 用 `zLevel += 50 ... -= 50` 包住（RenderItem.java:402-443）；嵌套/异步调用会画错深度。
- **名牌与旁观者**：旁观玩家不渲染层（RendererLivingEntity.java:176）；着火但旁观的玩家不画火（Render.java:314）。
- **静态贴图缓存只增不减**：`RenderHorse.field_110852_a`（RenderHorse.java:14）与 `LayerArmorBase.ARMOR_TEXTURE_RES_MAP`（LayerArmorBase.java:24）为 static HashMap，资源包重载不会清空。

## 交叉引用

- `net.minecraft.client` → `Minecraft#getRenderManager` / `Minecraft#getRenderItem` / `Minecraft#getItemRenderer`（构造于 Minecraft.java:555-556；F3+B 切 `RenderManager#setDebugBoundingBox`，Minecraft.java:2004）
- `net.minecraft.client.renderer` → `RenderGlobal#renderEntities`（调 `RenderManager#cacheActiveRenderInfo` / `#setRenderPosition` / `#renderEntitySimple` / `#renderWitherSkull` / `#setRenderOutlines`，RenderGlobal.java:568-678）；`RenderGlobal.drawOutlinedBoundingBox`（被 `RenderManager#renderDebugBoundingBox` 调用，RenderManager.java:448）
- `net.minecraft.client.renderer` → `ItemRenderer#renderRightArm/renderLeftArm`（调 `RenderPlayer#renderRightArm/#renderLeftArm`，ItemRenderer.java:150,161,252）；`LayerHeldItem` 等反向调 `Minecraft#getItemRenderer().renderItem(...)`（LayerHeldItem.java:66）
- `net.minecraft.client.renderer` → `GlStateManager#*` / `OpenGlHelper#setLightmapTextureCoords` / `Tessellator#getInstance` / `WorldRenderer#begin/pos/endVertex`（全包的绘制原语）
- `net.minecraft.client.renderer` → `BlockRendererDispatcher#renderBlockBrightness`（RenderTNTPrimed、RenderMinecart、LayerHeldBlock、LayerIronGolemFlower、LayerMooshroomMushroom）；`#getModelFromBlockState` + `BlockModelRenderer#renderModel`（RenderFallingBlock.java:54-55）
- `net.minecraft.client.renderer.tileentity` → `TileEntityItemStackRenderer#renderByItem`（RenderItem.java:153）、`TileEntitySkullRenderer#renderSkull`（LayerCustomHead.java:106）、`TileEntityMobSpawnerRenderer#renderMob`（RenderMinecartMobSpawner.java:21）、`RenderEnderCrystal`/`RenderItemFrame`/`RenderWitherSkull`（注册进 entityRenderMap，RenderManager.java:170-186，类本体在 tileentity 包）
- `net.minecraft.client.model` → `ModelBase#render/#setRotationAngles/#setLivingAnimations/#setModelAttributes` 及全部具体 Model 类（每个渲染器构造时实例化）
- `net.minecraft.client.renderer.texture` → `TextureManager#bindTexture/#loadTexture`、`DynamicTexture`（受击白纹理）、`LayeredTexture`（马变种，RenderHorse.java:95）、`TextureMap.locationBlocksTexture`
- `net.minecraft.client.resources.model` → `ModelManager#getModel`、`IBakedModel#getItemCameraTransforms/isGui3d/isBuiltInRenderer`（RenderItem / RenderEntityItem）
- `net.minecraft.scoreboard` → `Team#getNameTagVisibility`（RendererLivingEntity#canRenderName）、`ScorePlayerTeam#getColorPrefix`（#setScoreTeamColor）、`Scoreboard#getObjectiveInDisplaySlot`（RenderPlayer#renderOffsetLivingLabel）
- `net.minecraft.entity.boss` → `BossStatus#setBossStatus`（RenderDragon.java:94、RenderWither.java:26，Boss 血条数据源）
- `net.minecraft.nbt` → `NBTUtil#readGameProfileFromNBT/#writeGameProfile`、`TileEntitySkull#updateGameprofile`（LayerCustomHead）
- `net.minecraft.client.gui` → 各容器 GUI 调 `RenderItem#renderItemAndEffectIntoGUI/#renderItemOverlays`；`GuiInventory` 调 `RenderManager#setPlayerViewY/#setRenderShadow`（GuiInventory.java:119-122）
- `net.minecraft.client.particle` → `MobAppearance#renderParticle` 调 `RenderManager#setRenderPosition`（MobAppearance.java:55）
- `org.lwjgl.opengl` → `GL11`（Render、RendererLivingEntity、RenderArrow、RenderGuardian 直接调用；经 lwjgl2-shim 适配 LWJGL3）

## 覆盖声明

完整读取了 81/81 个文件（每个文件从第 1 行读到最后一行；RenderItem.java 因长度分两次读完 1-1074 行）。

逐行精读的类：RenderManager、Render、RendererLivingEntity、RenderLiving、RenderPlayer、RenderItem、RenderEntityItem、RenderGuardian、RenderDragon、RenderZombie、LayerArmorBase、LayerCustomHead、LayerHeldItem、LayerCape、LayerRenderer。

结构性浏览（读全文但主要提取签名、贴图常量与覆写点）的类：其余全部具体生物渲染器（RenderBat、RenderBlaze、RenderBoat、RenderCaveSpider、RenderChicken、RenderCow、RenderCreeper、RenderEnderman、RenderEndermite、RenderEntity、RenderFallingBlock、RenderFireball、RenderFish、RenderGhast、RenderGiantZombie、RenderHorse、RenderIronGolem、RenderLeashKnot、RenderLightningBolt、RenderMagmaCube、RenderMinecart、RenderMinecartMobSpawner、RenderMooshroom、RenderOcelot、RenderPainting、RenderPig、RenderPigZombie、RenderPotion、RenderRabbit、RenderSheep、RenderSilverfish、RenderSkeleton、RenderSlime、RenderSnowMan、RenderSnowball、RenderSpider、RenderSquid、RenderTNTPrimed、RenderTntMinecart、RenderVillager、RenderWitch、RenderWither、RenderWolf、RenderXPOrb、RenderArrow、RenderBiped、ArmorStandRenderer）与其余 layers（LayerArrow、LayerBipedArmor、LayerCreeperCharge、LayerDeadmau5Head、LayerEnderDragonDeath、LayerEnderDragonEyes、LayerEndermanEyes、LayerHeldBlock、LayerHeldItemWitch、LayerIronGolemFlower、LayerMooshroomMushroom、LayerSaddle、LayerSheepWool、LayerSlimeGel、LayerSnowmanHead、LayerSpiderEyes、LayerVillagerArmor、LayerWitherAura、LayerWolfCollar）。

外部行号引用（Minecraft.java、RenderGlobal.java、ItemRenderer.java、GuiInventory.java、MobAppearance.java）来自 grep 命中行，未通读这些文件全文。
