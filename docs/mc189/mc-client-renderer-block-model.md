---
area: net/minecraft/client/renderer/block/model
slug: mc-client-renderer-block-model
files: 12
lines: 2051
tier: B
---

# net/minecraft/client/renderer/block/model

## 定位

本包是方块/物品 JSON 模型系统的"数据结构 + 烘焙"层：

- **JSON 反序列化**：blockstates 定义文件（`ModelBlockDefinition`）与 model 文件（`ModelBlock` 及其子结构 `BlockPart` / `BlockPartFace` / `BlockFaceUV` / `BlockPartRotation` / `ItemTransformVec3f` / `ItemCameraTransforms`）都用 Gson 自定义 `Deserializer` 解析。
- **几何烘焙**：`FaceBakery` 把上述声明式描述转换为 `BakedQuad` —— 每面 4 顶点、每顶点 7 个 int 的扁平顶点数组，是块渲染管线消费的最终格式。
- **平面物品模型生成**：`ItemModelGenerator` 从 `layer0..layer4` 贴图像素扫描出带厚度侧边的 `BlockPart` 列表。
- **破坏动画**：`BreakingFour` 把任意 `BakedQuad` 的 UV 重映射到裂纹贴图。

上游调用者主要是资源加载阶段的 `net.minecraft.client.resources.model.ModelBakery`（解析 + 烘焙全部模型），运行时消费者是 `BlockModelRenderer`（区块几何构建）、`RenderItem` / `RenderEntityItem`（物品渲染，用 `ItemCameraTransforms.applyTransform` 摆位）、`SimpleBakedModel`（破坏动画时包 `BreakingFour`）。若本包消失，所有方块与物品的模型加载、区块网格构建、手持/GUI/掉落物的物品渲染全部失效。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| `BakedQuad` | 41 | — | 烘焙后的四边形：`int[28]` 顶点数据 + tintIndex + face |
| `BlockFaceUV` | 119 | —（内部类 `Deserializer implements JsonDeserializer<BlockFaceUV>`） | 单面的 UV 数组与 0/90/180/270 旋转，含按旋转取 U/V 的索引换算 |
| `BlockPart` | 229 | —（内部类 `Deserializer`） | 模型中一个 from/to 立方体元素：面表、局部旋转、shade；构造时补默认 UV |
| `BlockPartFace` | 56 | —（内部类 `Deserializer`） | 一个面的 cullface、tintindex、texture 引用（`#name`）、`BlockFaceUV` |
| `BlockPartRotation` | 20 | — | 元素级旋转参数：origin/axis/angle/rescale 的纯数据载体 |
| `BreakingFour` | 69 | `extends BakedQuad` | 复制一个 BakedQuad 并把 UV 重映射到破坏裂纹贴图 |
| `FaceBakery` | 377 | — | 核心烘焙器：把 BlockPartFace + 旋转 + sprite 烘焙成 BakedQuad |
| `ItemCameraTransforms` | 131 | —（内部类 `Deserializer`；嵌套 `enum TransformType`） | 6 种显示场景（thirdperson/firstperson/head/gui/ground/fixed）的变换集合，并施加到 GlStateManager |
| `ItemModelGenerator` | 327 | —（嵌套 `Span`、`enum SpanFacing`） | 扫描物品贴图 alpha 通道，生成带侧边厚度的 2D 物品模型 |
| `ItemTransformVec3f` | 103 | —（内部类 `Deserializer`） | 单个变换（rotation/translation/scale 三个 Vector3f），值域被 clamp |
| `ModelBlock` | 295 | —（内部类 `Deserializer`、`Bookkeep`、`LoopException`） | model JSON 的内存表示：elements、textures 表、parent 链、纹理名解析 |
| `ModelBlockDefinition` | 284 | —（嵌套 `Deserializer`、`Variant`、`Variants`、`MissingVariantException`） | blockstates JSON 的内存表示：variant 名 → 加权模型列表 |

## 核心类详解

### BakedQuad（`BakedQuad.java`）

- 字段（`BakedQuad.java:11-13`）：`protected final int[] vertexData`、`protected final int tintIndex`、`protected final EnumFacing face`。
- `vertexData` 布局：4 顶点 × 7 int = 28；每顶点为 `(x, y, z, shadeColor, u, v, <unused>)`（文件头注释 `BakedQuad.java:7-10`，写入逻辑见 `FaceBakery.storeVertexData`，`FaceBakery.java:101-110`）。x/y/z/u/v 是 `Float.floatToRawIntBits` 存的 float，shadeColor 是 ARGB int。
- 关键方法：`public int[] getVertexData()`（`BakedQuad.java:22`）、`public boolean hasTintIndex()`（`:27`，即 `tintIndex != -1`）、`public EnumFacing getFace()`（`:37`）。
- 消费方：`BlockModelRenderer` 在区块构建时遍历 `List<BakedQuad>` 直接把 `getVertexData()` 灌进 `WorldRenderer`（`BlockModelRenderer.java:135`、`:267`）。

### FaceBakery（`FaceBakery.java`）

- 常量（`FaceBakery.java:15-16`）：
  ```java
  private static final float SCALE_ROTATION_22_5 = 1.0F / (float)Math.cos(0.39269909262657166D) - 1.0F;
  private static final float SCALE_ROTATION_GENERAL = 1.0F / (float)Math.cos((Math.PI / 4D)) - 1.0F;
  ```
- 入口（`FaceBakery.java:18`）：
  ```java
  public BakedQuad makeBakedQuad(Vector3f posFrom, Vector3f posTo, BlockPartFace face, TextureAtlasSprite sprite, EnumFacing facing, ModelRotation modelRotationIn, BlockPartRotation partRotation, boolean uvLocked, boolean shade)
  ```
  流程：`makeQuadVertexData`（`int[28]`，`:36-46`）→ `getFacingFromVertexData` 由法线反推朝向（`:21`）→ uvLocked 时 `lockUv`（`:23-26`）→ `partRotation == null` 时 `applyFacing` 把顶点吸附到包围盒（`:28-31`）。
- 顶点填充：`fillVertexData`（`:90-99`）先 `modelRotationIn.rotateFace(facing)` 求旋转后的面以取 shade 颜色，再经 `rotatePart`（元素级旋转，`:112-156`）与 `rotateVertex`（模型级旋转，`:158-169`）变换位置，最后 `storeVertexData`（`:101-110`）写入。
- 面亮度硬编码于 `getFaceBrightness`（`:55-76`）：DOWN=0.5F、UP=1.0F、NORTH/SOUTH=0.8F、WEST/EAST=0.6F。
- 静态工具（`:188`）：
  ```java
  public static EnumFacing getFacingFromVertexData(int[] faceData)
  ```
  用前 3 个顶点叉积求法线，与 6 个方向向量点积取最大者；全部失败时兜底返回 `EnumFacing.UP`（`:219-222`）。`BreakingFour` 构造器也调用它（`BreakingFour.java:12`）。
- 调用时机：仅在资源(重)加载时由 `ModelBakery.makeBakedQuad`（`ModelBakery.java:477-479`）调用，主线程执行。

### ModelBlock（`ModelBlock.java`）

- 静态 Gson（`ModelBlock.java:28`）：`SERIALIZER` 注册了 `ModelBlock` / `BlockPart` / `BlockPartFace` / `BlockFaceUV` / `ItemTransformVec3f` / `ItemCameraTransforms` 六个 TypeAdapter，是本包 JSON 解析的总装配点。
- 关键字段（`:29-36`）：`private final List<BlockPart> elements`、`private final boolean gui3d`、`private final boolean ambientOcclusion`、`private ItemCameraTransforms cameraTransforms`、`public String name`、`protected final Map<String, String> textures`、`protected ModelBlock parent`、`protected ResourceLocation parentLocation`。
- 入口：
  ```java
  public static ModelBlock deserialize(Reader readerIn)      // ModelBlock.java:38
  public static ModelBlock deserialize(String jsonString)    // ModelBlock.java:43
  ```
- parent 链语义：`getElements()`（`:69`）、`isAmbientOcclusion()`（`:79`）在有 parent 时**完全取 parent 的值**（elements 与 parent 在 JSON 中二选一，`Deserializer.deserialize` 强制校验，`:225-232`）；`isGui3d()`（`:84`）只看自身；`getTransform`（`:177-180`）逐个 TransformType 回退到 parent。
- 纹理解析：`public String resolveTextureName(String textureName)`（`:107`）沿 parent 链解析 `#var` 间接引用，用 `Bookkeep`（`:204-213`）检测向上循环引用，失败返回字符串 `"missingno"`。`isTexturePresent`（`:102`）就是判断解析结果不等于 `"missingno"`。
- `public static void checkModelHierarchy(Map<ResourceLocation, ModelBlock> p_178312_0_)`（`:182`）：用 Floyd 快慢指针找 parent 环，找到则抛 `ModelBlock.LoopException`；无环时靠 NPE 跳出（catch 后空处理，`:197-200`）——这是原版风格的"异常当控制流"，勿加日志改动语义。
- `getParentFromMap`（`:94`）由 `ModelBakery` 在全部模型解析完后调用，把 `parentLocation` 连接为对象引用。

### ModelBlockDefinition（`ModelBlockDefinition.java`）

- 静态 Gson（`:24`）：注册 `ModelBlockDefinition.Deserializer` 与 `Variant.Deserializer`。
- 入口（`:27`）：
  ```java
  public static ModelBlockDefinition parseFromReader(Reader p_178331_0_)
  ```
  调用点：`ModelBakery`（`ModelBakery.java:147`），加载 `blockstates/*.json`。
- `public ModelBlockDefinition.Variants getVariants(String p_178330_1_)`（`:48`）：查不到时抛 `MissingVariantException`（**非静态内部类**，`:128`）。
- `Variant`（`:132`）字段：`modelLocation`（`Variant.Deserializer.makeModelLocation` 会自动加 `"block/"` 前缀，`:204-209`）、`modelRotation`（仅支持 `ModelRotation.getModelRotation(x, y)` 的 90 度组合）、`uvLock`、`weight`（默认 1）。注意 `Variant.equals`（`:167`）**不比较 weight**（`:180`），`hashCode` 同样忽略 weight（`:184-190`）——影响以 Variant 为 key 的去重行为。

### ItemCameraTransforms（`ItemCameraTransforms.java`）

- 6 个 `public final ItemTransformVec3f` 字段：`thirdPerson`、`firstPerson`、`head`、`gui`、`ground`、`fixed`（`:23-28`）；`public static final ItemCameraTransforms DEFAULT`（`:13`）。
- 9 个 `public static float` 全局偏移量 `field_181690_b` ... `field_181698_j`（`:14-22`），初始全 0，在 `applyTransform` 中分别加到 translation(3)/rotation(3)/scale(3) 上——这是原版留下的全局调试旋钮，也是外部想统一微调物品渲染位置的现成注入点。
- ```java
  public void applyTransform(ItemCameraTransforms.TransformType type)   // :55
  ```
  取对应变换，非 DEFAULT 时按 translate → rotate Y → rotate X → rotate Z → scale 顺序调 `GlStateManager`（`:61-65`）。调用方：`RenderItem.renderItemModelTransform`（`RenderItem.java:327`）与 GUI 渲染路径（`RenderItem.java:366`，固定用 `TransformType.GUI`），每帧物品渲染时在主线程执行。
- `public boolean func_181687_c(ItemCameraTransforms.TransformType type)`（`:96`）：该场景是否有非默认变换，被 `ModelBlock.getTransform` 用来决定是否回退 parent。
- `enum TransformType`（`:121-130`）：`NONE, THIRD_PERSON, FIRST_PERSON, HEAD, GUI, GROUND, FIXED`。

### ItemModelGenerator（`ItemModelGenerator.java`）

- `public static final List<String> LAYERS = Lists.newArrayList(new String[] {"layer0", "layer1", "layer2", "layer3", "layer4"})`（`:15`）——最多 5 层，层号即 tintIndex。
- ```java
  public ModelBlock makeItemModel(TextureMap textureMapIn, ModelBlock blockModel)   // :17
  ```
  对每个存在的 layer：正反两面（SOUTH/NORTH 的全幅 quad，z 从 7.5 到 8.5，`:51-54`）+ 沿贴图 alpha 边缘扫描出的侧边条（`func_178393_a` 扫描每帧像素，`:169-193`；`func_178397_a` 转成 BlockPart，`:59-167`）。alpha 判定：`(pixel >> 24 & 255) == 0` 视为透明（`func_178391_a`，`:238`）。
  返回的 ModelBlock 以 `ambientOcclusion=false, gui3d=false` 构造并继承 `blockModel.getAllTransforms()`（`:44`）。全部 layer 缺失时返回 `null`（`:37-40`）。
- 调用方：`ModelBakery.makeItemModel`（`ModelBakery.java:709-712`），仅资源加载期。
- `SpanFacing` 枚举注意（`:291-294`）：`LEFT(EnumFacing.EAST, -1, 0)`、`RIGHT(EnumFacing.WEST, 1, 0)` —— LEFT 映射 EAST、RIGHT 映射 WEST，是刻意的镜像，勿"修正"。

### BreakingFour（`BreakingFour.java`）

- ```java
  public BreakingFour(BakedQuad quad, TextureAtlasSprite textureIn)   // :10
  ```
  深拷贝顶点数组（`Arrays.copyOf`），face 用 `FaceBakery.getFacingFromVertexData` 重算，然后 `remapQuad` 按 face 把每个顶点的 xyz 投影成 0..16 的 UV 写回 `vertexData[i+4]` / `[i+5]`（`:25-68`）。
- 调用方：`SimpleBakedModel.Builder`（`SimpleBakedModel.java:97`、`:105`），即 `BlockRendererDispatcher.renderBlockDamage` 构建破坏动画模型时。

### BlockPart / BlockPartFace / BlockFaceUV / BlockPartRotation

- `BlockPart` 字段（`BlockPart.java:20-24`）：`public final Vector3f positionFrom`、`positionTo`、`public final Map<EnumFacing, BlockPartFace> mapFaces`、`public final BlockPartRotation partRotation`（可为 null）、`public final boolean shade`。构造器调 `setDefaultUvs()`（`:33`、`:36-43`）：对没写 `"uv"` 的面按 from/to 投影生成默认 UV（`getFaceUvs`，`:45-68`）。
- `BlockPart.Deserializer` 校验：from/to 各分量必须在 [-16, 32]（`:184`、`:198`）；rotation 的 angle 只允许 0/±22.5/±45（`:113-115`）；faces 至少 1 个（`:142-144`）；rotation origin 解析后 `scale(0.0625F)` 即除以 16（`:99`）。
- `BlockFaceUV`：`public float[] uvs`（4 元素，可为 null 直到 `setUvs` 补默认值）、`public final int rotation`（`BlockFaceUV.java:14-15`）。`func_178348_a(int)` / `func_178346_b(int)` 按旋转后的顶点序取 U/V（`:23-47`）；`func_178345_c(int)` 是反向索引映射，供 `FaceBakery.lockVertexUv` 用（`FaceBakery.java:373`）。rotation 只允许 0/90/180/270（`Deserializer.parseRotation`，`:81-88`）。
- `BlockPartFace` 字段（`BlockPartFace.java:14-18`）：`public static final EnumFacing FACING_DEFAULT = null`、`public final EnumFacing cullFace`（JSON 无 "cullface" 时 `EnumFacing.byName("")` 返回 null）、`public final int tintIndex`（默认 -1）、`public final String texture`（必填）、`public final BlockFaceUV blockFaceUV`。
- `BlockPartRotation`（`BlockPartRotation.java:8-11`）：`origin` / `axis` / `angle` / `rescale` 四个 final 字段的纯数据类。

### ItemTransformVec3f（`ItemTransformVec3f.java`）

- `public static final ItemTransformVec3f DEFAULT`（`:16`）；三个 final `Vector3f`：`rotation`、`translation`、`scale`（`:17-19`），构造器防御性拷贝（`:23-25`）。
- Deserializer 数值约束（`:64-71`）：translation 先 `scale(0.0625F)` 再 clamp 到 [-1.5, 1.5]；scale clamp 到 [-4, 4]。
- `equals`（`:28`）注意：`this.getClass() != p_equals_1_.getClass()` 且未判 null——传 null 会 NPE；`ItemCameraTransforms.func_181687_c` 依赖此 equals 与 DEFAULT 比较。

## 时序与生命周期

全部逻辑属**资源加载期**与**渲染期**，无 tick 概念：

1. **启动 / F3+T 资源重载**（主线程，`ModelBakery` 驱动）：
   - `ModelBlockDefinition.parseFromReader` 解析全部 blockstates（`ModelBakery.java:147`）；
   - `ModelBlock.deserialize` 解析全部 model JSON（`ModelBakery.java:247`）；
   - `ModelBlock.getParentFromMap` + `checkModelHierarchy` 连接并校验 parent 链；
   - 物品模型经 `ItemModelGenerator.makeItemModel`（`ModelBakery.java:709`）；
   - `FaceBakery.makeBakedQuad` 逐面烘焙为 `BakedQuad`，装入 `SimpleBakedModel`。
   之后本包大部分对象（Deserializer、FaceBakery、ItemModelGenerator）不再被触碰。
2. **每帧**：`ItemCameraTransforms.applyTransform` 在每次物品渲染时改 GL 矩阵栈（主线程）；`BakedQuad.getVertexData` 在区块重建（chunk builder 线程池调用 `BlockModelRenderer`）与物品渲染中被读取。
3. **破坏动画**：玩家挖掘时 `SimpleBakedModel.Builder` 每次重建 damage model 都会 new 一批 `BreakingFour`。

线程归属：解析与烘焙在主线程（资源重载）；`BakedQuad.vertexData` 烘焙完成后被区块构建工作线程并发**只读**。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public BakedQuad makeBakedQuad(Vector3f posFrom, Vector3f posTo, BlockPartFace face, TextureAtlasSprite sprite, EnumFacing facing, ModelRotation modelRotationIn, BlockPartRotation partRotation, boolean uvLocked, boolean shade)` | FaceBakery.java:18 | 资源加载期每个模型面一次 | 拦截/改写所有方块与物品的几何：自定义顶点格式、全局 UV 处理、注入额外 quad | 只在重载时跑；改动需 F3+T 才生效；返回的 vertexData 会被多线程读 |
| `public static EnumFacing getFacingFromVertexData(int[] faceData)` | FaceBakery.java:188 | 烘焙时 + `BreakingFour` 构造时 | 改变面剔除归类逻辑（如自定义斜面模型的 cull 行为） | 静态方法，替换需改字节码或换类；兜底返回 UP |
| `public void applyTransform(ItemCameraTransforms.TransformType type)` | ItemCameraTransforms.java:55 | 每帧每个物品渲染（RenderItem.java:327、:366） | 物品摆位总入口：改手持视角模型位置、做 old-animations 类功能 | 直接操作 GlStateManager 矩阵栈，必须与外层 push/pop 配对；DEFAULT 变换会被跳过 |
| `public static float field_181690_b` ...`field_181698_j`（9 个静态字段） | ItemCameraTransforms.java:14-22 | 被 applyTransform 每次读取 | 零字节码改动即可全局偏移物品 translation/rotation/scale——功能层调物品渲染的最省事入口 | 全局生效影响所有 TransformType 路径；用完记得归零 |
| `public ItemTransformVec3f getTransform(ItemCameraTransforms.TransformType type)` | ItemCameraTransforms.java:69 | applyTransform 内部及 RenderItem 负缩放检测（RenderItem.java:329） | 按场景（GUI/手持/头戴…）返回替换的变换 | 返回值也用于 `isThereOneNegativeScale` 判定，改 scale 符号会影响剔除方向 |
| `public static ModelBlock deserialize(Reader readerIn)` | ModelBlock.java:38 | 每个 model JSON 加载时（ModelBakery.java:247） | 注入/替换模型 JSON 内容，实现自定义模型格式预处理 | 静态 + 静态 SERIALIZER；解析异常直接炸资源加载 |
| `public static ModelBlockDefinition parseFromReader(Reader p_178331_0_)` | ModelBlockDefinition.java:27 | 每个 blockstates JSON 加载时（ModelBakery.java:147） | 拦截 blockstates，动态增删 variant | 同上，静态入口 |
| `public String resolveTextureName(String textureName)` | ModelBlock.java:107 | 烘焙期解析 `#texture` 引用；ItemModelGenerator.java:31、:43 | 重定向纹理（换皮、动态纹理包功能） | 失败约定是返回字符串 "missingno" 而非抛异常，调用方按此判断 |
| `public ModelBlock makeItemModel(TextureMap textureMapIn, ModelBlock blockModel)` | ItemModelGenerator.java:17 | 资源加载期每个 builtin/generated 物品模型一次 | 改平面物品的 3D 化方式（厚度、层数、发光层） | 返回 null 表示无 layer；层号被写作 tintIndex |
| `public int[] getVertexData()` | BakedQuad.java:22 | 区块重建线程（BlockModelRenderer.java:135、:267）与物品渲染每帧 | 读侧改写顶点（着色、抖动）需在此数组上做 | 返回内部数组引用，不拷贝——原地改会永久污染模型；多线程只读约定 |
| `public BreakingFour(BakedQuad quad, TextureAtlasSprite textureIn)` | BreakingFour.java:10 | 挖掘时构建破坏动画模型（SimpleBakedModel.java:97、:105） | 自定义破坏裂纹渲染 | 它 copy 顶点数组，父 quad 安全 |
| `public ModelBlockDefinition.Variants getVariants(String p_178330_1_)` | ModelBlockDefinition.java:48 | 烘焙期按 blockstate 属性串查 variant | 动态换模型 variant | 缺失抛 `MissingVariantException`（非静态内部类，构造需外部实例） |
| `public static void checkModelHierarchy(Map<ResourceLocation, ModelBlock> p_178312_0_)` | ModelBlock.java:182 | ModelBakery 连接 parent 后 | 加入自定义模型时需保证不引入 parent 环 | 用 NPE 作正常控制流；环会抛 `LoopException` 使加载崩溃 |

## 数据与协议

无网络封包/NBT。涉及两种 JSON 文件格式（Gson 解析）与其字段级约束：

**model JSON（`ModelBlock.SERIALIZER`，ModelBlock.java:28）**

| 字段 | 类型 | 读取方法 | 取值含义 |
|---|---|---|---|
| `parent` | string | `ModelBlock.Deserializer.getParent`（ModelBlock.java:266） | 父模型路径；与 `elements` 互斥，二者必有其一（:225-232） |
| `elements` | array | `getModelElements`（:276） | `BlockPart` 列表 |
| `textures` | object | `getTextures`（:249） | 纹理变量表，值可为 `#var` 间接引用 |
| `ambientocclusion` | bool，默认 true | `getAmbientOcclusionEnabled`（:271） | AO 开关（有 parent 时取 parent 的值） |
| `display` | object | ModelBlock.java:239-243 | 反序列化为 `ItemCameraTransforms` |
| element `from`/`to` | float[3]，[-16,32] | `BlockPart.Deserializer.parsePositionFrom/To`（BlockPart.java:180-206） | 立方体角点，1/16 方块单位 |
| element `rotation` | object | `parseRotation`（BlockPart.java:91） | `origin`（÷16）、`axis`（x/y/z）、`angle`（仅 0/±22.5/±45）、`rescale`（默认 false） |
| element `shade` | bool，默认 true | BlockPart.java:80-87 | 是否施加方向亮度 |
| face `uv` | float[4]，可省略 | `BlockFaceUV.Deserializer.parseUV`（BlockFaceUV.java:91） | 省略时由 `BlockPart.setDefaultUvs` 按几何投影补 |
| face `rotation` | int，0/90/180/270 | `parseRotation`（BlockFaceUV.java:77） | UV 旋转 |
| face `texture` | string，必填 | `BlockPartFace.Deserializer.parseTexture`（BlockPartFace.java:45） | `#var` 引用 |
| face `cullface` | string，默认 "" | `parseCullFace`（BlockPartFace.java:50） | 邻块遮挡剔除方向；空串解析为 null |
| face `tintindex` | int，默认 -1 | `parseTintIndex`（BlockPartFace.java:40） | -1 表示无着色 |
| display 各项 `rotation`/`translation`/`scale` | float[3] | `ItemTransformVec3f.Deserializer`（ItemTransformVec3f.java:59-73） | translation ÷16 后 clamp ±1.5；scale clamp ±4 |

**blockstates JSON（`ModelBlockDefinition.GSON`，ModelBlockDefinition.java:24）**

| 字段 | 类型 | 读取方法 | 取值含义 |
|---|---|---|---|
| `variants` | object | `Deserializer.parseVariantsList`（ModelBlockDefinition.java:93） | key 为属性串（如 `"facing=north"`），值为单对象或数组 |
| variant `model` | string，必填 | `Variant.Deserializer.parseModel`（:232） | 自动前缀 `"block/"`（`makeModelLocation`，:204-209） |
| variant `x`/`y` | int，默认 0 | `parseRotation`（:216） | 90 度倍数，映射 `ModelRotation.getModelRotation(x, y)` |
| variant `uvlock` | bool，默认 false | `parseUvLock`（:211） | 旋转时锁定世界空间 UV |
| variant `weight` | int，默认 1 | `parseWeight`（:237） | 随机加权（`WeightedBakedModel` 消费）；不参与 equals/hashCode |

**BakedQuad 顶点格式**（FaceBakery.storeVertexData，FaceBakery.java:101-110）：stride 7 int/顶点 —— `[0]=x, [1]=y, [2]=z`（float bits）、`[3]=shadeColor`（ARGB）、`[4]=u, [5]=v`（float bits，已插值到 atlas 坐标）、`[6]` 未使用。

## 不变量与陷阱

- **`BakedQuad.getVertexData()` 返回内部数组引用**（BakedQuad.java:22-25），无防御拷贝。烘焙后被区块构建线程并发读取，任何运行时原地修改都是数据竞争且永久生效。需要改就走 `BreakingFour` 式的 copy。
- **顶点 stride 恒为 7**、数组长恒 28：`FaceBakery`、`BreakingFour`、`BlockModelRenderer` 都硬编码 `7 * i`。改顶点格式必须全链路同步。
- 模型 JSON 中 `elements` 与 `parent` **严格二选一**（ModelBlock.java:225-232），有 parent 的模型自身 elements 恒为空 list；`getElements()`/`isAmbientOcclusion()` 整体委托 parent 而非合并。
- `resolveTextureName` 的失败通道是魔法字符串 `"missingno"`（ModelBlock.java:124、:142），`isTexturePresent` 与 `ItemModelGenerator` 都依赖它，不能改成抛异常。
- `checkModelHierarchy` 靠 **NullPointerException 作正常退出路径**（ModelBlock.java:197-200）——按 CLAUDE.md 的"不吞异常"标准这是坑，但属原版语义，勿动。
- `ModelBlockDefinition.Variant.equals/hashCode` **忽略 weight**（ModelBlockDefinition.java:180、:184-190）；`MissingVariantException` 是**非静态**内部类（:128），外部无法直接 `new`。
- `ItemTransformVec3f.equals(null)` 会 NPE（ItemTransformVec3f.java:34 直接 `p_equals_1_.getClass()`）。
- `ItemCameraTransforms` 的 9 个 `public static float` 是**全局可变状态**，多处物品渲染共享；渲染线程外写它们没有同步保证。
- `ItemModelGenerator.SpanFacing` 的 LEFT→`EnumFacing.EAST`、RIGHT→`EnumFacing.WEST` 是刻意镜像（ItemModelGenerator.java:293-294）。
- **LWJGL3 移植点**：本包用的 `org.lwjgl.util.vector.Vector3f/Matrix4f/Vector4f` 在 LWJGL3 中已删除，由本仓库 `lwjgl2-shim/src/main/java/org/lwjgl/util/vector/` 提供的 shim 实现支撑（Matrix4f.java、Vector3f.java、Vector4f.java）。数学语义须与 LWJGL2 一致（`Matrix4f.rotate/transform` 为静态、列主序），改 shim 会直接扭曲全部模型烘焙。
- `BlockFaceUV.uvs` 允许构造时为 null，`setUvs` 只在 null 时写入一次（BlockFaceUV.java:59-65）；在补默认 UV 前调用 `func_178348_a` 会抛 NullPointerException("uvs")。
- 角度约束在解析层强制：element angle ∈ {0, ±22.5, ±45}，face UV rotation ∈ {0,90,180,270}，variant x/y 为 90 的倍数。绕过 Deserializer 手工构造对象时也必须遵守（`FaceBakery.rotatePart` 的 rescale 只区分 22.5 与其它，FaceBakery.java:136-148）。

## 交叉引用

- `net.minecraft.client.resources.model` → `ModelBakery#makeBakedQuad`（ModelBakery.java:477 调 `FaceBakery.makeBakedQuad`）、`ModelBakery` → `ModelBlock#deserialize` / `ModelBlockDefinition#parseFromReader` / `ItemModelGenerator#makeItemModel` / `ModelBlock#getAllTransforms`（ModelBakery.java:402）
- `net.minecraft.client.resources.model` → `ModelRotation#rotateFace` / `#rotateVertex` / `#getMatrix4d`（被 `FaceBakery` 消费）
- `net.minecraft.client.resources.model` → `SimpleBakedModel$Builder`（SimpleBakedModel.java:97、:105 new `BreakingFour`）；`IBakedModel#getItemCameraTransforms` 返回本包 `ItemCameraTransforms`；`BuiltInModel` 构造接收 `ItemCameraTransforms`
- `net.minecraft.client.renderer` → `BlockModelRenderer#renderModelAmbientOcclusionQuads` / `#renderModelStandardQuads`（消费 `BakedQuad#getVertexData`）
- `net.minecraft.client.renderer` → `EnumFaceDirection#getFacing` / `EnumFaceDirection.Constants`（FaceBakery 的顶点索引表）
- `net.minecraft.client.renderer` → `GlStateManager#translate/rotate/scale`（ItemCameraTransforms.java:61-65）
- `net.minecraft.client.renderer.entity` → `RenderItem#renderItemModelTransform`（RenderItem.java:326-327 调 `applyTransform`）、`RenderItem#renderItem(ItemStack, ItemCameraTransforms.TransformType)`（RenderItem.java:263）
- `net.minecraft.client.renderer.texture` → `TextureAtlasSprite#getInterpolatedU/V`（FaceBakery、BreakingFour）、`TextureAtlasSprite#getFrameTextureData`（ItemModelGenerator.java:177）、`TextureMap#getAtlasSprite`（ItemModelGenerator.java:33）
- `net.minecraft.util` → `EnumFacing#byName` / `EnumFacing.Axis#byName`、`JsonUtils`（全部 Deserializer）、`MathHelper#clamp_int/clamp_float/epsilonEquals/floor_float`、`Vec3i#getDirectionVec`
- `org.lwjgl.util.vector`（lwjgl2-shim）→ `Vector3f` / `Vector4f` / `Matrix4f#rotate` / `Matrix4f#transform`

## 覆盖声明

完整读取了 12/12 个文件（每个文件从第 1 行读到末尾）。逐行精读：FaceBakery、ModelBlock、ModelBlockDefinition、ItemCameraTransforms、ItemModelGenerator、BakedQuad、BreakingFour、BlockFaceUV、BlockPart、BlockPartFace、ItemTransformVec3f、BlockPartRotation（全部）。另对外部调用方 ModelBakery、RenderItem、BlockModelRenderer、SimpleBakedModel 做了 grep 级结构性确认（未逐行精读），交叉引用的行号来自 grep 输出。ItemModelGenerator 中侧边 UV 的逐分量算术（f2..f16 的具体换算）只做了结构性理解，未逐值推演验证。
