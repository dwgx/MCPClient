---
area: net/minecraft/client/model
slug: mc-client-model
files: 52
lines: 5173
tier: B
---

# net/minecraft/client/model 架构笔记

## 定位

本包是客户端所有"盒子拼装式"模型（实体、TileEntity 装饰模型）的几何与动画层。核心抽象只有四层：

- 几何原语：`PositionTextureVertex`（顶点+UV）→ `TexturedQuad`（4 顶点面片）→ `ModelBox`（8 顶点/6 面的立方体）。
- 骨骼节点：`ModelRenderer`（一段可旋转/平移的骨骼，持有若干 `ModelBox` 和子骨骼，编译成 GL display list）。
- 模型基类：`ModelBase`（持有 `boxList`、贴图尺寸、`render`/`setRotationAngles`/`setLivingAnimations` 三个动画入口）。
- 具体模型：其余 46 个 `ModelXxx` 类，全部是构造函数里摆盒子 + 覆写角度计算。

调用方：`net.minecraft.client.renderer.entity.RendererLivingEntity` 及其子类（每个 `RenderXxx` 持有一个 `mainModel`），`LayerRenderer` 各层（盔甲、披风、手持物品），`TileEntityChestRenderer`/`TileEntitySignRenderer`/`TileEntityBannerRenderer`/`TileEntityEnchantmentTableRenderer` 等 TESR，以及第一人称 `ItemRenderer`（调 `ModelPlayer.renderRightArm/renderLeftArm`）。

被调用方：`net.minecraft.client.renderer` 的 `GlStateManager`（矩阵栈/旋转平移）、`Tessellator`/`WorldRenderer`（display list 编译时写顶点）、`GLAllocation`（分配 display list）；实体侧读取 `net.minecraft.entity.*` 的状态 getter（如 `EntityHorse.getRearingAmount`、`EntityArmorStand.getHeadRotation`）。

如果本包消失：所有生物、盔甲架、船/矿车、箱子/告示牌/旗帜/附魔台书本等模型全部无法渲染，第一人称手臂也没了；方块与物品渲染（走 baked model 管线）不受影响。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| ModelArmorStand | 133 | extends ModelArmorStandArmor | 盔甲架本体模型，附加底座/腰杆/侧杆，从 EntityArmorStand 读取各部位 Rotations |
| ModelArmorStandArmor | 57 | extends ModelBiped | 盔甲架穿盔甲时用的 biped 变体，setRotationAngles 完全由实体姿态数据驱动 |
| ModelBanner | 31 | extends ModelBase | 旗帜（布面/立杆/横梁），由 TileEntityBannerRenderer 调 renderBanner() |
| ModelBase | 80 | abstract class | 所有模型的基类：boxList、贴图尺寸、swingProgress/isRiding/isChild 属性与三个动画钩子 |
| ModelBat | 111 | extends ModelBase | 蝙蝠模型，区分倒挂 (getIsBatHanging) 与飞行两套姿态 |
| ModelBiped | 276 | extends ModelBase | 双足人形基类：头/身/四肢，走路摆臂、挥手、潜行、拉弓等通用动画 |
| ModelBlaze | 78 | extends ModelBase | 烈焰人：头 + 12 根环绕旋转的烈焰棒（三层轨道） |
| ModelBoat | 46 | extends ModelBase | 船，5 块面板静态摆放，无动画 |
| ModelBook | 70 | extends ModelBase | 附魔台的书：封面/书页/翻页动画，由 TileEntityEnchantmentTableRenderer 使用 |
| ModelBox | 111 | (无) | 单个纹理立方体：8 个 PositionTextureVertex + 6 个 TexturedQuad，构造时展开 UV |
| ModelChest | 42 | extends ModelBase | 单箱模型（盖/箱体/锁扣），renderAll() 供 TESR 调用，盖子角度由外部设置 |
| ModelChicken | 105 | extends ModelBase | 鸡：头/喙/肉髯/身/双腿/双翅，翅膀 rotateAngleZ 直接用 ageInTicks（外部传入拍翅值） |
| ModelCow | 27 | extends ModelQuadruped | 牛：在四足基类上重摆头（带角）与身体（带乳房） |
| ModelCreeper | 75 | extends ModelBase | 苦力怕：头/身/四条短腿；creeperArmor 字段构造后从未渲染（死字段——带电层实为 LayerCreeperCharge 持有整只 ModelCreeper(2.0F)） |
| ModelDragon | 262 | extends ModelBase | 末影龙：用单个 spine 节点循环渲染颈椎 5 段与尾椎 12 段，姿态取自 EntityDragon.getMovementOffsets |
| ModelEnderCrystal | 59 | extends ModelBase | 末影水晶：底座 + 两层嵌套旋转的 glass 立方体 + 内核 cube |
| ModelEnderMite | 57 | extends ModelBase | 末影螨：4 段身体，按静态尺寸表 field_178716_a 排布 |
| ModelEnderman | 129 | extends ModelBiped | 末影人：加长四肢（30 高），isCarrying/isAttacking 两个姿态开关 |
| ModelGhast | 64 | extends ModelBase | 恶魂：身体 + 9 根随机长度触须（Random 种子固定 1660L） |
| ModelGuardian | 136 | extends ModelBase | 守卫者：身体 + 12 根刺 + 眼 + 3 段尾巴；眼睛朝向观察者或被瞄准实体 |
| ModelHorse | 573 | extends ModelBase | 马/驴/骡：全包最大模型，鞍具/箱子/缰绳按实体状态条件渲染，setLivingAnimations 做全部姿态 |
| ModelHumanoidHead | 36 | extends ModelSkeletonHead | 玩家头颅：在骷髅头基础上叠加 0.25F 外扩的 hat 层 |
| ModelIronGolem | 128 | extends ModelBase | 铁傀儡：攻击抡臂 (getAttackTimer)、送花 (getHoldRoseTick) 动画在 setLivingAnimations 完成 |
| ModelLargeChest | 23 | extends ModelChest | 大箱：构造函数里把三个部件换成 30 宽版本（128x64 贴图） |
| ModelLeashKnot | 43 | extends ModelBase | 拴绳结：单盒模型 field_110723_a |
| ModelMagmaCube | 71 | extends ModelBase | 岩浆怪：8 层薄片 + 核心，squishFactor 插值挤压各层 Y |
| ModelMinecart | 52 | extends ModelBase | 矿车：6 块面板，sideModels[5]（底内衬）随 p_78088_4_ 上下移动 |
| ModelOcelot | 219 | extends ModelBase | 豹猫：站/走/疾跑/坐四态由 field_78163_i 状态机切换，状态在 setLivingAnimations 判定 |
| ModelPig | 16 | extends ModelQuadruped | 猪：四足基类 + 鼻子盒 |
| ModelPlayer | 179 | extends ModelBiped | 玩家：外衣层 5 件 + 披风 + deadmau5 耳朵；smallArms 支持 Alex 3px 手臂 |
| ModelQuadruped | 90 | extends ModelBase | 四足动物基类：头/躯干/四腿，幼体缩放渲染逻辑 |
| ModelRabbit | 197 | extends ModelBase | 兔子：12 个部件，跳跃动画由 EntityRabbit.func_175521_o 驱动 |
| ModelRenderer | 313 | (无) | 骨骼节点：旋转点/欧拉角/offset/mirror/showModel/isHidden，惰性编译 display list 并递归渲染子节点 |
| ModelSheep1 | 56 | extends ModelQuadruped | 羊毛层模型（外扩 0.6~1.75），吃草低头由 EntitySheep 的 getter 驱动 |
| ModelSheep2 | 43 | extends ModelQuadruped | 剪毛后的羊身模型，同样的吃草动画 |
| ModelSign | 26 | extends ModelBase | 告示牌：板 + 立杆，renderSign() 供 TileEntitySignRenderer 调用 |
| ModelSilverfish | 87 | extends ModelBase | 蠹虫：7 段身体 + 3 片"翅"，尺寸/贴图坐标由静态表定义 |
| ModelSkeleton | 56 | extends ModelZombie | 骷髅：细四肢（2x12x2）；setLivingAnimations 按 getSkeletonType()==1 (凋灵骷髅) 决定 aimedBow |
| ModelSkeletonHead | 43 | extends ModelBase | 头颅基类：单个 8x8x8 skeletonHead 盒 |
| ModelSlime | 52 | extends ModelBase | 史莱姆：外壳或内核+眼嘴（由构造参数 p_i1157_1_ 区分层） |
| ModelSnowMan | 70 | extends ModelBase | 雪傀儡：三段雪球 + 双臂，身体随头部 yaw 旋转 1/4 |
| ModelSpider | 152 | extends ModelBase | 蜘蛛：头/颈/腹 + 8 条腿，腿部相位交错摆动 |
| ModelSquid | 61 | extends ModelBase | 鱿鱼：身体 + 8 根均匀分布触须，rotateAngleX 直接取 ageInTicks（外部传入的触须摆角） |
| ModelVillager | 86 | extends ModelBase | 村民：大头带鼻、抱臂（villagerArms 单件）、长袍层 |
| ModelWitch | 63 | extends ModelVillager | 女巫：帽子（4 层嵌套 child）+ 鼻上痣，鼻子随 ticksExisted 抖动 |
| ModelWither | 88 | extends ModelBase | 凋灵：3 个头（field_82904_b）+ 3 段躯干（field_82905_a），副头朝向由 EntityWither 数据驱动 |
| ModelWolf | 177 | extends ModelBase | 狼：坐姿/摇尾/甩水动画在 setLivingAnimations，头与尾用 renderWithRotation 渲染 |
| ModelZombie | 46 | extends ModelBiped | 僵尸：双臂前平举 + 挥击动画 |
| ModelZombieVillager | 55 | extends ModelBiped | 僵尸村民：换村民头（带鼻），臂部动画与 ModelZombie 相同（代码重复而非继承） |
| PositionTextureVertex | 34 | (无) | 不可变顶点：Vec3 位置 + (texturePositionX, texturePositionY) UV |
| TextureOffset | 16 | (无) | 命名贴图偏移 (textureOffsetX, textureOffsetY)，配合 ModelBase.setTextureOffset 的字符串键 |
| TexturedQuad | 73 | (无) | 4 顶点面片：构造时算 UV，draw() 算法线并写入 WorldRenderer |

## 核心类详解

### ModelBase（ModelBase.java）

- 关键字段：`public float swingProgress`、`public boolean isRiding`、`public boolean isChild = true`（ModelBase.java:13-15，注意默认值是 true）；`public List<ModelRenderer> boxList`（:16，每个 ModelRenderer 构造时自动注册进来）；`private Map<String, TextureOffset> modelTextureMap`（:17）；`public int textureWidth = 64; public int textureHeight = 32`（:18-19）。
- 关键方法（逐字）：
  - `public void render(Entity entityIn, float p_78088_2_, float p_78088_3_, float p_78088_4_, float p_78088_5_, float p_78088_6_, float scale)`（ModelBase.java:24，空实现）
  - `public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor, Entity entityIn)`（:33）
  - `public void setLivingAnimations(EntityLivingBase entitylivingbaseIn, float p_78086_2_, float p_78086_3_, float partialTickTime)`（:41）
  - `public ModelRenderer getRandomModelBox(Random rand)`（:45，LayerArrow 渲染插在实体身上的箭时取随机盒用）
  - `public static void copyModelAngles(ModelRenderer source, ModelRenderer dest)`（:64）
  - `public void setModelAttributes(ModelBase model)`（:74，盔甲层模型同步姿态属性用）
- 调用时机：`RendererLivingEntity.doRender` 每帧先写 `mainModel.swingProgress/isRiding/isChild`（RendererLivingEntity.java:93-95），随后 `mainModel.setLivingAnimations(...)`（:151）→ `mainModel.setRotationAngles(...)`（:152）→ `renderModel(...)` → `mainModel.render(...)`（:268）。

### ModelRenderer（ModelRenderer.java）

- 关键字段：`public float rotationPointX/Y/Z`、`public float rotateAngleX/Y/Z`（ModelRenderer.java:24-29）；`private boolean compiled; private int displayList`（:30-33）；`public boolean mirror; public boolean showModel; public boolean isHidden`（:34-38）；`public List<ModelBox> cubeList; public List<ModelRenderer> childModels`（:39-40）；`public float offsetX/offsetY/offsetZ`（:43-45）。
- 关键方法（逐字）：
  - `public ModelRenderer(ModelBase model, String boxNameIn)`（:47，副作用 `model.boxList.add(this)`，:54）
  - `public void addChild(ModelRenderer renderer)`（:73）
  - `public ModelRenderer addBox(String partName, float offX, float offY, float offZ, int width, int height, int depth)`（:90，走 `baseModel.getTextureOffset(partName)` 的命名贴图路径）
  - `public void addBox(float p_78790_1_, float p_78790_2_, float p_78790_3_, int width, int height, int depth, float scaleFactor)`（:114）
  - `public void render(float p_78785_1_)`（:126，快路径：无旋转时直接 translate + callList；有旋转走 pushMatrix，Z→Y→X 顺序 rotate，:174-187；递归渲染 childModels）
  - `public void renderWithRotation(float p_78791_1_)`（:207，旋转顺序为 Y→X→Z 且不渲染子节点）
  - `public void postRender(float scale)`（:245，只施加变换不画，用于把后续绘制"挂"到骨骼上——手持物品/头戴层的关键）
  - `private void compileDisplayList(float scale)`（:289，首次 render 时懒编译：`GLAllocation.generateDisplayLists(1)` + `GL11.glNewList` + 逐 ModelBox `render(worldrenderer, scale)` + `glEndList`）
- 调用时机：各 ModelXxx.render 内逐部件调用；`LayerHeldItem` 等层通过 `postRenderArm`→`postRender` 复用手臂变换。

### ModelBox / TexturedQuad / PositionTextureVertex

- `public ModelBox(ModelRenderer renderer, int textureX, int textureY, float p_i46301_4_, float p_i46301_5_, float p_i46301_6_, int p_i46301_7_, int p_i46301_8_, int p_i46301_9_, float p_i46301_10_, boolean p_i46301_11_)`（ModelBox.java:39）：最后一个 float 是 scaleFactor（各方向外扩，:52-57），bool 是 mirror（交换 X 并 `flipFace()` 所有面，:59-64、:89-95）。6 个面在 :82-87 按标准盒展开布置 UV。
- `public void render(WorldRenderer renderer, float scale)`（ModelBox.java:98）：仅在 display list 编译时被调用一次。
- `public void draw(WorldRenderer renderer, float scale)`（TexturedQuad.java:47）：用前三个顶点叉积算面法线（:49-51），`renderer.begin(7, DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL)`（:63）后写 4 个顶点并 `Tessellator.getInstance().draw()`（:71）。每个 quad 一次 begin/draw——因为都在 display list 内录制，运行时无此开销。
- `PositionTextureVertex.setTexturePosition(float, float)` 返回共享同一 `Vec3` 的新实例（PositionTextureVertex.java:16-19）。

### ModelBiped（ModelBiped.java）

- 关键字段：`public ModelRenderer bipedHead / bipedHeadwear / bipedBody / bipedRightArm / bipedLeftArm / bipedRightLeg / bipedLeftLeg`（ModelBiped.java:9-25）；`public int heldItemLeft; public int heldItemRight; public boolean isSneak; public boolean aimedBow`（:30-39）。heldItemRight 取值：0 无、1 普通持物、2（跳过角度调整）、3 方块类特殊姿势（:160-174 switch）。
- `public ModelBiped(float modelSize, float p_i1149_2_, int textureWidthIn, int textureHeightIn)`（:51）为主构造，p_i1149_2_ 是整体 Y 偏移（Enderman 传 -14）。
- `public void render(...)`（:83）：先 `setRotationAngles`，isChild 时头 1.5/2 缩放、身体 1/2 缩放（:88-104）。
- `public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor, Entity entityIn)`（:129）：走路摆臂（:133-138）、骑乘（:142-150）、挥击 swingProgress（:178-198）、潜行（:200-219）、待机呼吸晃臂（:221-224）、拉弓（:226-242），最后 `copyModelAngles(this.bipedHead, this.bipedHeadwear)`（:244）。
- `public void setModelAttributes(ModelBase model)`（:247）：盔甲层用，把 heldItem/isSneak/aimedBow 从本体模型拷到盔甲模型。
- `public void setInvisible(boolean invisible)`（:261）、`public void postRenderArm(float scale)`（:272，`LayerHeldItem` 渲染手持物品前调用）。

### ModelPlayer（ModelPlayer.java）

- 字段：`public ModelRenderer bipedLeftArmwear / bipedRightArmwear / bipedLeftLegwear / bipedRightLegwear / bipedBodyWear`（ModelPlayer.java:8-12）、`private ModelRenderer bipedCape / bipedDeadmau5Head`、`private boolean smallArms`（:13-15）。
- `public ModelPlayer(float p_i46304_1_, boolean p_i46304_2_)`（:17）：第二参即 smallArms（Alex 皮肤 3px 手臂，:27-41 分支）。
- `public void renderDeadmau5Head(float p_178727_1_)`（:105，LayerDeadmau5Head 调）、`public void renderCape(float p_178728_1_)`（:113，LayerCape 调）。
- `public void renderRightArm()` / `public void renderLeftArm()`（:142/:148）：第一人称手臂，`ItemRenderer` 使用，硬编码 scale 0.0625F。
- `public void postRenderArm(float scale)`（:166）：smallArms 时先 `++this.bipedRightArm.rotationPointX` 再 postRender 再还原（:168-173），修正手持物品位置。

### ModelHorse（ModelHorse.java）

- 全包唯一把几乎所有姿态放进 `setLivingAnimations` 的模型：`public void setLivingAnimations(EntityLivingBase entitylivingbaseIn, float p_78086_2_, float p_78086_3_, float partialTickTime)`（ModelHorse.java:353），自己做 yaw 插值（`updateHorseRotation`，:332）、吃草 `getGrassEatingAmount`、后立 `getRearingAmount`、张嘴 `getMouthOpennessAngle`（:378-381）加权混合。
- `public void render(Entity entityIn, ...)`（:210）：强转 `EntityHorse` 后按 `isHorseSaddled()/isChested()/getHorseType()`（i==1||i==2 为驴/骡耳，:218）条件渲染鞍具、箱子、缰绳；幼马用三段不同的 GlStateManager.scale（腿 :245、躯干 :266、头 :281-291）而非 isChild 通用路径。

### ModelDragon（ModelDragon.java）

- `private float partialTicks`（ModelDragon.java:45）由 `setLivingAnimations`（:130-133）暂存——它是唯一用 setLivingAnimations 只存 partialTick 的模型。
- `render`（:138）用单个 `this.spine` 节点在循环里改姿态并重复 `this.spine.render(scale)` 画 5 段颈（:159-173）与 12 段尾（:224-238）；同一 display list 重复调用、每次外部矩阵不同。身体两侧翼/腿靠 `GlStateManager.scale(-1.0F, 1.0F, 1.0F)` 镜像第二遍并翻转 cullFace（:206-211，`GlStateManager.cullFace(1028)` 即 GL_FRONT）。
- 构造函数用命名贴图 API：`this.setTextureOffset("body.body", 0, 0)` 等（:51-69）配合 `addBox(String partName, ...)`。

### ModelGuardian（ModelGuardian.java）

- 唯一在 setRotationAngles 里访问全局单例的模型：`Entity entity = Minecraft.getMinecraft().getRenderViewEntity();`（ModelGuardian.java:95），守卫者眼睛跟随观察者或 `entityguardian.getTargetedEntity()`（:97-100）。刺的伸缩由 `entityguardian.func_175469_o(f)` 驱动（:82）。

### ModelChest / ModelSign / ModelBanner / ModelBook（TESR 模型）

- 不走 `render(Entity, ...)` 入口，而是各自暴露专用方法：`public void renderAll()`（ModelChest.java:35，渲染前 `this.chestKnob.rotateAngleX = this.chestLid.rotateAngleX` 同步锁扣）、`public void renderSign()`（ModelSign.java:21）、`public void renderBanner()`（ModelBanner.java:24）。盖子角度、旗帜摆动全由对应 TileEntityRenderer 在调用前写入字段。
- `ModelBook.setRotationAngles`（ModelBook.java:56）语义被重载：limbSwing=时间、limbSwingAmount=右页翻页进度、ageInTicks=左页翻页进度、netHeadYaw=开合度——参数名不代表实际含义。

## 时序与生命周期

1. 构造：各 `RenderXxx` / `TileEntityXxxRenderer` 在渲染器注册时（`RenderManager` 构造、TESR instance 初始化）new 出模型。构造函数只建 `ModelRenderer`/`ModelBox` 数据结构，不触碰 GL。
2. 首帧渲染：`ModelRenderer.render/renderWithRotation/postRender` 首次调用时 `compileDisplayList`（ModelRenderer.java:132-135）在 GL 线程编译 display list，之后 `compiled=true` 永久复用。display list 从不释放（无 delete 路径；`GLAllocation.deleteDisplayLists` 存在但本包不调用）。
3. 每帧（非每 tick——本包没有 tick 逻辑）：`RendererLivingEntity.doRender` 顺序为 写 swingProgress/isRiding/isChild（RendererLivingEntity.java:93-95）→ `setLivingAnimations`（:151，拿 EntityLivingBase + partialTicks 做实体相关动画）→ `setRotationAngles`（:152）→ `mainModel.render`（:268，多数模型 render 内会再调一次 setRotationAngles）。之后各 LayerRenderer 可能对同一模型再次调 `setRotationAngles`/`postRender`。
4. 线程归属：全部在客户端主线程（GL 渲染线程）。任何字段都无同步；display list 编译也必须在主线程。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void render(Entity entityIn, float p_78088_2_, float p_78088_3_, float p_78088_4_, float p_78088_5_, float p_78088_6_, float scale)` | ModelBase.java:24（各子类覆写，如 ModelBiped.java:83） | RendererLivingEntity.renderModel → mainModel.render（RendererLivingEntity.java:268），每实体每帧 | 整体替换/包裹模型绘制：透视 ESP 前后改 depth 状态、取消渲染、注入自定义部件 | 内部会先调 setRotationAngles；GlStateManager push/pop 必须配平，否则污染后续所有实体 |
| `public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor, Entity entityIn)` | ModelBase.java:33 / ModelBiped.java:129 | render 内部 + RendererLivingEntity.java:152，每实体每帧至少一次 | 姿态改写的总入口：自定义动画、摆臂风格、取消头部转向等 | 同帧可能被调多次（doRender 一次 + render 内一次 + 盔甲层一次）；写在这里的逻辑要幂等 |
| `public void setLivingAnimations(EntityLivingBase entitylivingbaseIn, float p_78086_2_, float p_78086_3_, float partialTickTime)` | ModelBase.java:41（ModelHorse.java:353、ModelWolf.java:112、ModelIronGolem.java:97 等覆写） | RendererLivingEntity.java:151，早于 setRotationAngles | 唯一能拿到 EntityLivingBase 强类型与 partialTicks 的动画钩子；读实体状态做插值动画 | 多数实现直接强转实体类型（如 `(EntityHorse)entitylivingbaseIn`），换模型给错误实体会 ClassCastException |
| `public void render(float p_78785_1_)` | ModelRenderer.java:126 | 各模型 render 内逐骨骼调用 | 单骨骼级隐藏/替换/矩阵注入；也是首次触发 compileDisplayList 的位置 | 快路径（无旋转）不 push/pop 矩阵，靠正负 translate 抵消（:137/:202）——在中间插 GL 变换会破坏还原 |
| `public void postRender(float scale)` | ModelRenderer.java:245 | LayerHeldItem/LayerCustomHead 等在画附着物前，经 postRenderArm 转发 | 把任意自绘内容挂到某根骨骼的坐标系（手、头） | 只施加变换不画、也不 push 矩阵；调用方必须自己 pushMatrix/popMatrix 包住 |
| `public void postRenderArm(float scale)` | ModelBiped.java:272 / ModelPlayer.java:166 / ModelArmorStand.java:126 | LayerHeldItem 渲染手持物品前 | 调整手持物品挂点；ModelPlayer 版本处理 smallArms 偏移 | ModelArmorStand 版本会临时强开 `bipedRightArm.showModel`（:128-131）再还原 |
| `public void renderRightArm()` / `public void renderLeftArm()` | ModelPlayer.java:142 / :148 | ItemRenderer 第一人称空手/持图渲染 | 第一人称手臂替换、动画注入（viewmodel 改造的核心挂点） | scale 硬编码 0.0625F；只画 arm+armwear 两个盒 |
| `public void renderCape(float p_178728_1_)` | ModelPlayer.java:113 | LayerCape.doRenderLayer | 自定义披风渲染/物理 | bipedCape 贴图尺寸独立设为 64x32（ModelPlayer.java:24） |
| `public void renderDeadmau5Head(float p_178727_1_)` | ModelPlayer.java:105 | LayerDeadmau5Head（仅用户名 deadmau5） | 自定义头饰渲染参考实现 | 每次调用前 copyModelAngles 同步头部角度 |
| `public void setModelAttributes(ModelBase model)` | ModelBase.java:74 / ModelBiped.java:247 | LayerArmorBase 等把本体姿态同步到盔甲模型时 | 拦截/扩展本体→附属模型的属性同步（加自定义字段就要在这扩展） | ModelBiped 版本仅在 `model instanceof ModelBiped` 时拷贝四个附加字段 |
| `public void setInvisible(boolean invisible)` | ModelBiped.java:261 / ModelPlayer.java:154 | RenderPlayer 按可见性/皮肤层设置调用 | 逐部件显隐控制（隐身、皮肤层开关） | 参数语义是"可见性"（true=显示），方法名带误导 |
| `public void renderAll()` | ModelChest.java:35 | TileEntityChestRenderer / TileEntityEnderChestRenderer 每帧 | 箱子开盖动画拦截（读/改 `chestLid.rotateAngleX`，TESR 在调用前写入） | ModelLargeChest 复用同一方法 |
| `public void renderSign()` | ModelSign.java:21 | TileEntitySignRenderer 每帧 | 告示牌板体渲染改写 | signStick 显隐由 TESR 侧控制（墙上牌不画杆） |
| `public void renderBanner()` | ModelBanner.java:24 | TileEntityBannerRenderer 每帧 | 旗帜渲染；`bannerSlate.rotateAngleX` 摆动角由 TESR 写入 | renderBanner 每次强制 `bannerSlate.rotationPointY = -32.0F`（:26） |
| `private void compileDisplayList(float scale)` | ModelRenderer.java:289 | 每骨骼首次 render | 若要改几何生成方式（VBO 化、法线处理），这是必改点 | private；scale 被烘进 display list，首次调用的 scale 决定永久几何（见陷阱） |
| `public ModelRenderer getRandomModelBox(Random rand)` | ModelBase.java:45 | LayerArrow.doRenderLayer 渲染插在实体身上的箭时（getArrowCountInEntity>0） | 控制箭矢挂点取样 | boxList 为空会抛 IllegalArgumentException（nextInt(0)） |

## 数据与协议

无。本包不接触封包、NBT、文件格式或注册表；所有实体状态经由 `net.minecraft.entity.*` 的 getter 读取（其背后是 DataWatcher，但那属于 entity 包的职责）。

## 不变量与陷阱

- **display list 与 scale 烘焙**：`compileDisplayList` 只执行一次，顶点坐标乘以首次调用传入的 scale（TexturedQuad.java:68）。所有调用点都传 0.0625F（1/16），如果用不同 scale 调用同一 ModelRenderer，第二次以后不会重编译——scale 不一致会渲染错误。运行期改 `cubeList`（增删盒子）同样无效，因为 `compiled` 不会复位且没有公开的重编译入口。
- **构造顺序不变量**：`ModelRenderer` 构造时 `this.setTextureSize(model.textureWidth, model.textureHeight)`（ModelRenderer.java:56），因此子类必须先设置 `this.textureWidth/textureHeight` 再 new ModelRenderer（ModelHorse.java:67-68 即此模式）；反过来贴图 UV 全错。同理 `addBox` 读取当下的 `textureOffsetX/Y` 与 `mirror`，摆盒顺序敏感。
- **命名 addBox 前置条件**：`addBox(String partName, ...)`（ModelRenderer.java:90）要求 `baseModel.getTextureOffset(partName)` 非 null，否则 NPE——即必须先在 ModelBase 子类里 `setTextureOffset("head.main", x, y)`（ModelOcelot.java:38-41、ModelDragon.java:51-69 的先注册模式）。
- **isChild 默认 true**：ModelBase.java:15。TESR 模型（chest/sign/banner/book）不经过 RendererLivingEntity 覆写该值也不读它，无影响；但自定义模型若直接走 `ModelBiped.render` 而没先设置 isChild=false，会走幼体缩放分支。
- **render 快路径的矩阵约定**：无旋转分支不 pushMatrix，用 `GlStateManager.translate(offset)` … `translate(-offset)` 抵消（ModelRenderer.java:137/:202）。浮点上可逆，但任何在骨骼渲染中途插入的缩放会导致抵消失败。有旋转分支的旋转顺序是 Z→Y→X（:174-187），`renderWithRotation` 是 Y→X→Z（:221-233），两者不可互换。
- **同帧多次 setRotationAngles**：`ModelBiped.render` 内部先调 setRotationAngles（ModelBiped.java:85），而 RendererLivingEntity 在 renderModel 前已经调过一次（RendererLivingEntity.java:152）；盔甲层还会再调。所有姿态计算必须是"纯覆写"式（先归零/赋值再叠加），任何 `+=` 型逻辑若不先重置将逐帧累积——现有代码里 `+=` 只出现在同一次调用内已赋值字段之后，改动时必须维持该结构。
- **强转陷阱**：ModelHorse/ModelDragon/ModelGuardian/ModelBat/ModelRabbit 等在动画方法里直接 `(EntityXxx)entityIn` 强转；把模型挂到别的实体（或传 null）直接崩。ModelArmorStand 系用 `instanceof` 防护（ModelArmorStandArmor.java:30），是例外。
- **LWJGL3/JDK25 移植注意**：本包仍使用固定管线 display list（`GL11.glNewList/glEndList`，ModelRenderer.java:292/:300）。LWJGL3 下这要求 GL 兼容性上下文（Compatibility Profile）；若窗口创建改成 core profile，本包全部失效。矩阵操作全部经 `GlStateManager` 转发，未直接调用 GL11 矩阵函数，移植面收敛在 GlStateManager/GLAllocation 两处。`ModelDragon.render` 直接用整数常量 `GlStateManager.cullFace(1028)/(1029)`（GL_FRONT/GL_BACK，ModelDragon.java:210/:215），grep GL 常量时别漏掉。
- **线程约束**：一切方法只能在客户端主线程调用；`compileDisplayList` 在渲染中途惰性触发，意味着"预热模型"必须也在 GL 线程做。
- **ModelGuardian 的全局依赖**：setRotationAngles 里 `Minecraft.getMinecraft().getRenderViewEntity()`（ModelGuardian.java:95）——离线单测该模型必须先初始化 Minecraft 单例或跳过。

## 交叉引用

- net.minecraft.client.renderer.entity → `RendererLivingEntity#doRender`（写 mainModel 属性、调 setLivingAnimations/setRotationAngles）、`RendererLivingEntity#renderModel`（调 ModelBase#render）
- net.minecraft.client.renderer.entity → `RenderPlayer`（持有 ModelPlayer，调 setInvisible/renderRightArm 等）
- net.minecraft.client.renderer.entity.layers → `LayerHeldItem#doRenderLayer` → `ModelBiped#postRenderArm`；`LayerCape` → `ModelPlayer#renderCape`；`LayerDeadmau5Head` → `ModelPlayer#renderDeadmau5Head`；盔甲层 → `ModelBase#setModelAttributes`
- net.minecraft.client.renderer → `ItemRenderer` → `ModelPlayer#renderRightArm` / `ModelPlayer#renderLeftArm`（第一人称）
- net.minecraft.client.renderer.tileentity → `TileEntityChestRenderer`/`TileEntityEnderChestRenderer` → `ModelChest#renderAll`；`TileEntitySignRenderer` → `ModelSign#renderSign`；`TileEntityBannerRenderer` → `ModelBanner#renderBanner`；`TileEntityEnchantmentTableRenderer` → `ModelBook#render`
- net.minecraft.client.gui → `GuiEnchantment`（复用 ModelBook 画界面里的书）
- net.minecraft.client.renderer → `GlStateManager#translate/rotate/scale/pushMatrix/popMatrix/callList`（ModelRenderer 渲染路径）、`Tessellator#getInstance` + `WorldRenderer#begin/pos/tex/normal/endVertex`（TexturedQuad#draw）、`GLAllocation#generateDisplayLists`（ModelRenderer#compileDisplayList）
- net.minecraft.client.renderer.vertex → `DefaultVertexFormats#OLDMODEL_POSITION_TEX_NORMAL`（TexturedQuad.java:63）
- net.minecraft.entity → `Entity#isSneaking`、`EntityLivingBase#renderYawOffset/rotationYawHead/prevRotationPitch`（ModelHorse#setLivingAnimations）
- net.minecraft.entity.passive → `EntityHorse#getGrassEatingAmount/getRearingAmount/getMouthOpennessAngle/isHorseSaddled/isChested`、`EntitySheep#getHeadRotationPointY/getHeadRotationAngleX`、`EntityWolf#getShakeAngle/getInterestedAngle/isAngry/isSitting`、`EntityOcelot#isSitting/isSprinting`、`EntityRabbit#func_175521_o`、`EntityBat#getIsBatHanging`
- net.minecraft.entity.monster → `EntitySkeleton#getSkeletonType`、`EntityIronGolem#getAttackTimer/getHoldRoseTick`、`EntityGuardian#hasTargetedEntity/getTargetedEntity/func_175469_o/func_175471_a`、`EntityMagmaCube#squishFactor`
- net.minecraft.entity.boss → `EntityDragon#getMovementOffsets/animTime/prevAnimTime`、`EntityWither#func_82207_a/func_82210_r`
- net.minecraft.entity.item → `EntityArmorStand#getHeadRotation/getBodyRotation/getLeftArmRotation/getRightArmRotation/getLeftLegRotation/getRightLegRotation/getShowArms/hasNoBasePlate`
- net.minecraft.client → `Minecraft#getRenderViewEntity`（仅 ModelGuardian）
- net.minecraft.util → `MathHelper#cos/sin/sqrt_float`、`Vec3#subtractReverse/crossProduct/normalize`（TexturedQuad 法线）

## 覆盖声明

完整读取了 52/52 个文件（逐文件 Read 全文，无抽样）。

逐行精读的类：ModelBase、ModelRenderer、ModelBox、TexturedQuad、PositionTextureVertex、TextureOffset、ModelBiped、ModelPlayer、ModelHorse、ModelDragon、ModelGuardian、ModelArmorStand、ModelArmorStandArmor、ModelQuadruped、ModelChest、ModelSign、ModelBanner、ModelBook。

其余具体模型类（ModelBat、ModelBlaze、ModelBoat、ModelChicken、ModelCow、ModelCreeper、ModelEnderCrystal、ModelEnderMite、ModelEnderman、ModelGhast、ModelHumanoidHead、ModelIronGolem、ModelLargeChest、ModelLeashKnot、ModelMagmaCube、ModelMinecart、ModelOcelot、ModelPig、ModelRabbit、ModelSheep1、ModelSheep2、ModelSilverfish、ModelSkeleton、ModelSkeletonHead、ModelSlime、ModelSnowMan、ModelSpider、ModelSquid、ModelVillager、ModelWitch、ModelWither、ModelWolf、ModelZombie、ModelZombieVillager）同样全文读取，但仅做结构性梳理（构造摆盒 + 动画公式未逐项验算）。

调用方结论（RendererLivingEntity 的调用顺序与行号、TESR/Layer 使用者清单）经 Grep 对 client/src/main/java/net/minecraft/client/renderer 验证。
