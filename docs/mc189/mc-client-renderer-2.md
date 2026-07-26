---
area: net/minecraft/client/renderer#2
slug: mc-client-renderer-2
files: 9
lines: 1029
tier: A
---

# net/minecraft/client/renderer#2 — TileEntity 特殊渲染器 + 顶点格式/VBO

本桶包含两个子包：`renderer/tileentity`（TESR 分发器与三个具体渲染器 + 抽象基类）和 `renderer/vertex`（顶点格式描述与 VBO 封装）。

## 定位

**tileentity 子包**：负责"方块模型系统画不了的方块"——告示牌文字、头颅皮肤、移动中的活塞等，需要每帧用 GL 立即模式/模型重画。入口是单例 `TileEntityRendererDispatcher.instance`，由 `RenderGlobal.renderEntities` 在每帧实体渲染阶段调用；`RenderChunk.rebuildChunk` 在区块重建时用它判断某个 TileEntity 是否需要 TESR（有则加入 `CompiledChunk`，供每帧遍历）。`GuiEditSign` 也直接调它在编辑界面里画告示牌预览。如果这个子包消失：告示牌/头颅/活塞/箱子/信标等全部不可见，编辑告示牌 GUI 崩溃，物品栏里的头颅物品（走 `TileEntityItemStackRenderer` → `TileEntitySkullRenderer.instance`）也无法渲染。

**vertex 子包**：定义顶点内存布局（`VertexFormat` = 一串 `VertexFormatElement`，每个元素有 GL 类型、用途、分量数、字节偏移）和 OpenGL VBO 的薄封装（`VertexBuffer`）。`WorldRenderer`（顶点缓冲构建器）按 `VertexFormat` 计算 stride/offset 写字节；`WorldVertexBufferUploader` 按元素列表设置 `gl*Pointer`；`RenderChunk` 每层持有一个 `VertexBuffer(DefaultVertexFormats.BLOCK)`，`VboRenderList` 绑定后 `drawArrays(7)` 画区块。如果这个子包消失：整个世界渲染管线（区块、粒子、物品、天空 VBO）没有布局信息，什么都画不出来。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| `TileEntityRendererDispatcher` | 155 | — | 单例；按 TileEntity 类型分发到对应 TESR，缓存相机插值位置，包 try/catch 出崩溃报告 |
| `TileEntitySpecialRenderer<T extends TileEntity>` | 50 | —（抽象基类） | 所有 TESR 的基类：`renderTileEntityAt` 抽象方法 + `bindTexture`/`getWorld`/`getFontRenderer` 便捷方法 + 破坏阶段纹理数组 |
| `TileEntityPistonRenderer` | 79 | extends `TileEntitySpecialRenderer<TileEntityPiston>` | 用方块模型系统 + Tessellator 立即绘制移动中的活塞本体/活塞头 |
| `TileEntitySignRenderer` | 122 | extends `TileEntitySpecialRenderer<TileEntitySign>` | 渲染告示牌模型（`ModelSign`）与四行文字，支持破坏裂纹阶段 |
| `TileEntitySkullRenderer` | 147 | extends `TileEntitySpecialRenderer<TileEntitySkull>` | 渲染五种头颅（骷髅/凋灵骷髅/僵尸/玩家/苦力怕），玩家头查询皮肤缓存；有静态 `instance` 供物品/头戴层复用 |
| `DefaultVertexFormats` | 68 | — | 静态注册表：12 个预定义 `VertexFormat` 常量 + 6 个 `VertexFormatElement` 常量 |
| `VertexBuffer` | 50 | — | OpenGL VBO 封装：生成/绑定/上传（GL_STATIC_DRAW）/glDrawArrays/删除 |
| `VertexFormat` | 202 | — | 可变的顶点布局：元素列表 + 每元素字节偏移，缓存 color/normal/uv 偏移，提供 stride（`getNextOffset`） |
| `VertexFormatElement` | 156 | — | 单个顶点属性描述：`EnumType`（GL 类型+字节数）× `EnumUsage`（语义）× index × 分量数 |

## 核心类详解

### TileEntityRendererDispatcher（`tileentity/TileEntityRendererDispatcher.java`）

关键字段：
- `private Map<Class<? extends TileEntity>, TileEntitySpecialRenderer<? extends TileEntity>> mapSpecialRenderers`（行 29）——类型→渲染器映射，HashMap。
- `public static TileEntityRendererDispatcher instance = new TileEntityRendererDispatcher();`（行 30）——类加载时构造的全局单例。
- `public static double staticPlayerX; / staticPlayerY; / staticPlayerZ;`（行 34-40）——相机插值坐标，由 `RenderGlobal.renderEntities` 每帧写入（RenderGlobal.java:577-579），`renderTileEntity` 用它把世界坐标转成相机相对坐标。
- `public TextureManager renderEngine; public World worldObj; public Entity entity;`（行 41-43）以及插值后的 `entityYaw/entityPitch/entityX/entityY/entityZ`（行 44-48）。
- `private FontRenderer fontRenderer;`（行 31）——供 `TileEntitySignRenderer` 画字。

私有构造器（行 50-67）注册 10 个渲染器：`TileEntitySign/TileEntityMobSpawner/TileEntityPiston/TileEntityChest/TileEntityEnderChest/TileEntityEnchantmentTable/TileEntityEndPortal/TileEntityBeacon/TileEntitySkull/TileEntityBanner`，随后对每个渲染器调 `setRendererDispatcher(this)`（行 63-66）。注意本桶只含其中 3 个具体渲染器，其余 7 个在别的桶。

关键方法：
- `public <T extends TileEntity> TileEntitySpecialRenderer<T> getSpecialRendererByClass(Class<? extends TileEntity> teClass)`（行 69）——查不到时沿 superclass 链向上递归，并**把结果写回 map 作为缓存**（行 76）；到 `TileEntity.class` 仍无则返回 null（null 也会被缓存）。
- `public <T extends TileEntity> TileEntitySpecialRenderer<T> getSpecialRenderer(TileEntity tileEntityIn)`（行 82）——null 安全的按实例查询。
- `public void cacheActiveRenderInfo(World worldIn, TextureManager textureManagerIn, FontRenderer fontrendererIn, Entity entityIn, float partialTicks)`（行 87）——每帧由 RenderGlobal.java:568 调用，缓存插值后的相机状态。
- `public void renderTileEntity(TileEntity tileentityIn, float partialTicks, int destroyStage)`（行 104）——距离裁剪（`getDistanceSq` vs `getMaxRenderDistanceSquared()`，行 106），设置 lightmap 坐标（`OpenGlHelper.setLightmapTextureCoords`，行 111），然后以 `blockpos - staticPlayerXYZ` 为坐标转发（行 114）。
- `public void renderTileEntityAt(TileEntity tileEntityIn, double x, double y, double z, float partialTicks)`（行 121）——`destroyStage = -1` 的便捷重载，`GuiEditSign` 用的就是它。
- `public void renderTileEntityAt(TileEntity tileEntityIn, double x, double y, double z, float partialTicks, int destroyStage)`（行 126）——真正分发；渲染器抛异常时包成 `CrashReport`"Rendering Block Entity" 再抛 `ReportedException`（行 136-142）。
- `public void setWorld(World worldIn)`（行 146）、`public FontRenderer getFontRenderer()`（行 151）。

### TileEntitySpecialRenderer（`tileentity/TileEntitySpecialRenderer.java`）

- `protected static final ResourceLocation[] DESTROY_STAGES`（行 11）——10 张 `textures/blocks/destroy_stage_0..9.png` 破坏裂纹纹理，子类共享。
- `public abstract void renderTileEntityAt(T te, double x, double y, double z, float partialTicks, int destroyStage);`（行 14）——唯一抽象方法；x/y/z 是相机相对坐标。
- `protected void bindTexture(ResourceLocation location)`（行 16）——经 `rendererDispatcher.renderEngine` 绑定，texturemanager 为 null 时静默跳过。
- `protected World getWorld()`（行 26）、`public void setRendererDispatcher(TileEntityRendererDispatcher rendererDispatcherIn)`（行 31）、`public FontRenderer getFontRenderer()`（行 36）。
- `public boolean forceTileEntityRender()`（行 46）——默认 false；`RenderChunk.rebuildChunk` 在 RenderChunk.java:167 检查它，true 表示无视视锥剔除始终渲染（vanilla 中仅 banner/箱子类不覆写，实际覆写者在别的桶）。

### TileEntityPistonRenderer（`tileentity/TileEntityPistonRenderer.java`）

- `private final BlockRendererDispatcher blockRenderer = Minecraft.getMinecraft().getBlockRendererDispatcher();`（行 23）——字段初始化时就取 Minecraft 单例，意味着该类**必须在 Minecraft 初始化后才能实例化**（dispatcher 单例是懒加载类初始化，实际首次触发在游戏启动后，安全）。
- `public void renderTileEntityAt(TileEntityPiston te, double x, double y, double z, float partialTicks, int destroyStage)`（行 25）——仅当 `block.getMaterial() != Material.air && te.getProgress(partialTicks) < 1.0F` 才画（行 31）。流程：绑定 `TextureMap.locationBlocksTexture` → `RenderHelper.disableStandardItemLighting()` → blend/cull/shadeModel 设置（AO 开则 `shadeModel(7425)` 即 GL_SMOOTH，行 41-48）→ `worldrenderer.begin(7, DefaultVertexFormats.BLOCK)`（行 50）→ 用 `setTranslation` 把方块模型平移到活塞插值偏移处（行 51）→ 按三种情况经 `blockRenderer.getBlockModelRenderer().renderModel(...)` 写入顶点 → `tessellator.draw()`（行 75）→ 恢复光照。
- 三种分支：活塞头且 progress < 0.5 时画 SHORT 头（行 54-58）；收回中的活塞本体需要补画一个头 + 本体（行 59-68）；其余直接画本体（行 71）。

### TileEntitySignRenderer（`tileentity/TileEntitySignRenderer.java`）

- `private static final ResourceLocation SIGN_TEXTURE = new ResourceLocation("textures/entity/sign.png");`（行 17）；`private final ModelSign model = new ModelSign();`（行 20）。
- `public void renderTileEntityAt(TileEntitySign te, double x, double y, double z, float partialTicks, int destroyStage)`（行 22）——站立牌按 `te.getBlockMetadata() * 360 / 16` 旋转并显示 `model.signStick`（行 28-34）；墙牌按 metadata 2/4/5 映射 180/90/-90 度且隐藏杆（行 37-58）。`destroyStage >= 0` 时绑定裂纹纹理并进入纹理矩阵（`GlStateManager.matrixMode(5890)` 即 GL_TEXTURE）做 `scale(4.0F, 2.0F, 1.0F)` 平铺（行 61-69）。牌面以 `scale(f, -f, -f)`（f = 0.6666667F）画模型（行 76-79）。
- 文字渲染（行 80-109）：`f3 = 0.015625F * f` 缩放、`GL11.glNormal3f(0.0F, 0.0F, -1.0F * f3)`（行 84，直接调 GL11 而非 GlStateManager）、`depthMask(false)` 后逐行 `GuiUtilRenderComponents.splitText(ichatcomponent, 90, fontrenderer, false, true)` 截取第一段画出；`j == te.lineBeingEdited` 的行加 `"> " + s + " <"` 包裹（行 98-102）。文字颜色固定 `i = 0`（黑色，行 86）。

### TileEntitySkullRenderer（`tileentity/TileEntitySkullRenderer.java`）

- 纹理常量（行 22-25）：`SKELETON_TEXTURES/WITHER_SKELETON_TEXTURES/ZOMBIE_TEXTURES/CREEPER_TEXTURES`。
- `public static TileEntitySkullRenderer instance;`（行 26）——在 `setRendererDispatcher`（行 36-40）里自赋值，供 `TileEntityItemStackRenderer`（TileEntityItemStackRenderer.java:61）和 `LayerCustomHead`（LayerCustomHead.java:106）渲染头颅物品/头戴头颅。
- 模型：`private final ModelSkeletonHead skeletonHead = new ModelSkeletonHead(0, 0, 64, 32);`（行 27）、`private final ModelSkeletonHead humanoidHead = new ModelHumanoidHead();`（行 28）。
- `public void renderTileEntityAt(TileEntitySkull te, double x, double y, double z, float partialTicks, int destroyStage)`（行 30）——取 `EnumFacing.getFront(te.getBlockMetadata() & 7)` 与 `te.getSkullRotation() * 360 / 16.0F` 转发给 renderSkull。
- `public void renderSkull(float p_180543_1_, float p_180543_2_, float p_180543_3_, EnumFacing p_180543_4_, float p_180543_5_, int p_180543_6_, GameProfile p_180543_7_, int p_180543_8_)`（行 42）——参数依次为 x/y/z、朝向、rotation 度数、skullType（0 骷髅 / 1 凋灵骷髅 / 2 僵尸 / 3 玩家 / 4 苦力怕，行 57-98）、GameProfile、destroyStage。玩家头（case 3）走 `minecraft.getSkinManager().loadSkinFromCache(p_180543_7_)`，缓存命中则 `loadSkin(...)`，否则按 `EntityPlayer.getUUID(p_180543_7_)` 取 `DefaultPlayerSkin.getDefaultSkin(uuid)`（行 75-93）。非 UP 朝向的头贴墙偏移 0.74/0.26 并改写 rotation（行 104-127）；最终 `modelbase.render((Entity)null, 0.0F, 0.0F, 0.0F, p_180543_5_, 0.0F, f)`，f = 0.0625F（行 133-137）。

### VertexFormatElement（`vertex/VertexFormatElement.java`）

- 字段：`private final VertexFormatElement.EnumType type; private final VertexFormatElement.EnumUsage usage; private int index; private int elementCount;`（行 9-12）。
- `public VertexFormatElement(int indexIn, VertexFormatElement.EnumType typeIn, VertexFormatElement.EnumUsage usageIn, int count)`（行 14）——校验 `func_177372_a(indexIn, usageIn)`：`index != 0` 且用途不是 UV 时**强制把 usage 改成 UV** 并 warn（行 16-20）。
- `public final int getSize()`（行 61）= `type.getSize() * elementCount`（元素总字节数）。
- `public final boolean isPositionElement()`（行 66）。
- `equals`/`hashCode`（行 71-95）按四元组比较——`VertexFormat.equals` 依赖它。
- `EnumType`（行 97-132）：`FLOAT(4, "Float", 5126)`、`UBYTE(1, "Unsigned Byte", 5121)`、`BYTE(1, "Byte", 5120)`、`USHORT(2, "Unsigned Short", 5123)`、`SHORT(2, "Short", 5122)`、`UINT(4, "Unsigned Int", 5125)`、`INT(4, "Int", 5124)`；`getGlConstant()`（行 128）即 GL_FLOAT 等 GL 枚举，供 `WorldVertexBufferUploader`（WorldVertexBufferUploader.java:25）传给 `gl*Pointer`。
- `EnumUsage`（行 134-155）：`POSITION/NORMAL/COLOR/UV/MATRIX/BLEND_WEIGHT/PADDING`（MATRIX 与 BLEND_WEIGHT 在 1.8.9 中未实际使用）。

### VertexFormat（`vertex/VertexFormat.java`）

- 字段（行 11-18）：`private final List<VertexFormatElement> elements; private final List<Integer> offsets;`（平行列表，offsets[i] = 第 i 个元素的字节偏移）、`private int nextOffset;`（当前 stride）、`private int colorElementOffset; private List<Integer> uvOffsetsById; private int normalElementOffset;`（快速查询缓存）。
- `public VertexFormat(VertexFormat vertexFormatIn)`（行 20）拷贝构造——`WorldRenderer.begin` 在启用某些格式变体时会用到；`public VertexFormat()`（行 32）空格式。
- `public VertexFormat addElement(VertexFormatElement element)`（行 53）——重复 POSITION 会被拒绝并 warn（行 55-59）；按 usage 更新 `normalElementOffset/colorElementOffset/uvOffsetsById`（行 65-77，注意 `uvOffsetsById.add(element.getIndex(), ...)` 用 index 作为 List 插入位置），`nextOffset += element.getSize()`（行 79）。返回 this 可链式调用。
- `public void clear()`（行 42）——全部重置。
- 查询方法：`hasNormal()`/`getNormalOffset()`（行 84/89）、`hasColor()`/`getColorOffset()`（行 94/99）、`hasUvOffset(int id)`/`getUvOffsetById(int id)`（行 104/109）、`getIntegerSize()`（行 148，= `getNextOffset() / 4`，即 stride 按 int 计）、`getNextOffset()`（行 153，**语义是 stride**）、`getElements()`/`getElementCount()`/`getElement(int index)`（行 158-171）、`getOffset(int p_181720_1_)`（行 173）。
- `equals`/`hashCode`（行 178-201）按 elements + offsets + nextOffset。
- 主要消费者：`WorldRenderer`（大量调用 `getNextOffset/getIntegerSize/getOffset/getUvOffsetById/getColorOffset`，如 WorldRenderer.java:91、140、199、265、294）、`WorldVertexBufferUploader.draw`、`RealmsVertexFormat` 包装（RealmsVertexFormat.java:87）。

### DefaultVertexFormats（`vertex/DefaultVertexFormats.java`）

12 个格式常量（行 5-16）与 6 个元素常量（行 17-22），全部在 static 块（行 24-67）中组装。常用格式的布局与 stride：

| 格式 | 元素序列 | stride（字节） |
|---|---|---|
| `BLOCK` | POSITION_3F + COLOR_4UB + TEX_2F + TEX_2S | 12+4+8+4 = 28 |
| `ITEM` | POSITION_3F + COLOR_4UB + TEX_2F + NORMAL_3B + PADDING_1B | 12+4+8+3+1 = 28 |
| `PARTICLE_POSITION_TEX_COLOR_LMAP` | POSITION_3F + TEX_2F + COLOR_4UB + TEX_2S | 28 |
| `POSITION` | POSITION_3F | 12 |
| `POSITION_TEX` | POSITION_3F + TEX_2F | 20 |
| `POSITION_TEX_COLOR` | POSITION_3F + TEX_2F + COLOR_4UB | 24 |

元素常量定义（行 17-22）：`POSITION_3F`（index 0, FLOAT×3, POSITION）、`COLOR_4UB`（index 0, UBYTE×4, COLOR）、`TEX_2F`（index 0, FLOAT×2, UV）、`TEX_2S`（**index 1**, SHORT×2, UV——光照贴图坐标）、`NORMAL_3B`（index 0, BYTE×3, NORMAL）、`PADDING_1B`（index 0, BYTE×1, PADDING）。

### VertexBuffer（`vertex/VertexBuffer.java`）

- 字段（行 9-11）：`private int glBufferId;`（构造时 `OpenGlHelper.glGenBuffers()`，行 16）、`private final VertexFormat vertexFormat;`、`private int count;`（顶点数）。
- `public void bindBuffer()`（行 19）——`OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, this.glBufferId)`。
- `public void bufferData(ByteBuffer p_181722_1_)`（行 24）——bind → `OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, p_181722_1_, 35044)`（35044 = GL_STATIC_DRAW）→ unbind → `this.count = p_181722_1_.limit() / this.vertexFormat.getNextOffset();`（行 29）。
- `public void drawArrays(int mode)`（行 32）——`GL11.glDrawArrays(mode, 0, this.count)`；**不自动 bind**，调用者须先 `bindBuffer()` 并自行设置 `gl*Pointer`（见 VboRenderList.java:20-34）。
- `public void unbindBuffer()`（行 37）、`public void deleteGlBuffers()`（行 42，删除后 `glBufferId = -1` 防二次删除；RenderChunk.java:365 与 RenderGlobal.java:279/313/374 调用）。

## 时序与生命周期

全部在**主线程（GL 渲染线程）**，唯一例外见下。

1. **类初始化**：`TileEntityRendererDispatcher.instance` 在类首次被引用时构造（行 30），一次性 new 出全部 10 个 TESR 并注入 dispatcher 引用；`TileEntitySkullRenderer.setRendererDispatcher` 顺带发布 `instance`。`DefaultVertexFormats` 的 static 块在类加载时组装所有格式——纯 Java 对象，不碰 GL。`VertexBuffer` 构造需要 GL 上下文（glGenBuffers）。
2. **每帧**（`RenderGlobal.renderEntities`，pass 0）：
   - RenderGlobal.java:568 → `cacheActiveRenderInfo(...)` 刷新相机插值状态；
   - RenderGlobal.java:577-579 → 写 `staticPlayerX/Y/Z`；
   - 遍历可见 `RenderChunk` 的 `CompiledChunk` 里的 TileEntity 列表 → RenderGlobal.java:695 `renderTileEntity(tileentity2, partialTicks, -1)`；再遍历 `forceTileEntityRender` 集合（RenderGlobal.java:704）；最后对正在被破坏的方块以 `destroyblockprogress.getPartialBlockDamage()` 作为 destroyStage 再画一遍裂纹（RenderGlobal.java:735）。
   - 区块层渲染：`VboRenderList.renderChunkLayer` 每帧对每个可见区块 `bindBuffer()` → `setupArrayPointers()` → `drawArrays(7)`（VboRenderList.java:16-22）。
3. **区块重建时**（**ChunkRenderWorker 工作线程**）：`RenderChunk.rebuildChunk` 调 `TileEntityRendererDispatcher.instance.getSpecialRenderer(tileentity)`（RenderChunk.java:161）判断收集哪些 TileEntity——这是本桶唯一会在非主线程被调用的路径（见"陷阱"）。重建完成后主线程经 `ChunkRenderDispatcher.uploadVertexBuffer`（ChunkRenderDispatcher.java:272）→ `VertexBufferUploader.draw` → `VertexBuffer.bufferData` 上传。
4. **销毁**：世界重载/关闭时 `RenderChunk.deleteGlResources` → `vertexBuffers[i].deleteGlBuffers()`（RenderChunk.java:365）；天空/星星 VBO 在 `RenderGlobal` 重建时先 delete 再 new。
5. **无 tick 逻辑**：本桶所有类都不参与游戏 tick，只在渲染帧被调用。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void renderTileEntityAt(TileEntity tileEntityIn, double x, double y, double z, float partialTicks, int destroyStage)` | TileEntityRendererDispatcher.java:126 | 每帧每个可见 TileEntity（含破坏裂纹二次渲染） | **TESR 总闸**：替换/包裹渲染器、按类型隐藏、注入 ESP 高亮/发光、统计渲染耗时 | 渲染器抛异常会变成 ReportedException 直接崩游戏；坐标是相机相对坐标 |
| `public void renderTileEntity(TileEntity tileentityIn, float partialTicks, int destroyStage)` | TileEntityRendererDispatcher.java:104 | 每帧，进入前做距离裁剪与 lightmap 设置 | 改渲染距离（绕过 `getMaxRenderDistanceSquared`）、全亮 hack（改 lightmap 坐标）、条件跳过 | 跳过时注意 GlStateManager.color 已被设为白色；destroyStage 语义：-1 = 正常，0-9 = 裂纹 |
| `public void cacheActiveRenderInfo(World worldIn, TextureManager textureManagerIn, FontRenderer fontrendererIn, Entity entityIn, float partialTicks)` | TileEntityRendererDispatcher.java:87 | 每帧一次（RenderGlobal.java:568），实体渲染开始前 | 观察/篡改相机插值状态；自由视角（freecam）可在此替换 entity | `staticPlayerX/Y/Z` 不在这里设置，而在 RenderGlobal.java:577-579，两处要一起改 |
| `public <T extends TileEntity> TileEntitySpecialRenderer<T> getSpecialRendererByClass(Class<? extends TileEntity> teClass)` | TileEntityRendererDispatcher.java:69 | 分发查询 + RenderChunk 重建判断 | **注册自定义 TESR**：往 `mapSpecialRenderers` put 自己的类即可（含自定义 TileEntity 子类） | 查询结果（含 null）会被缓存写回 map；后注册的渲染器不会覆盖已缓存的父类结果，注册要趁早 |
| `public abstract void renderTileEntityAt(T te, double x, double y, double z, float partialTicks, int destroyStage);` | TileEntitySpecialRenderer.java:14 | 分发器转发 | 实现自定义 TESR 的入口；覆写现有渲染器时的目标方法 | 必须保持 GL 状态平衡（push/pop 配对、depthMask 恢复） |
| `public boolean forceTileEntityRender()` | TileEntitySpecialRenderer.java:46 | RenderChunk.rebuildChunk（RenderChunk.java:167） | 返回 true 让某类 TileEntity 无视视锥剔除始终渲染（穿墙渲染的基础） | 在 worker 线程被调用；对大量 TE 返回 true 会显著掉帧 |
| `public void renderSkull(float p_180543_1_, float p_180543_2_, float p_180543_3_, EnumFacing p_180543_4_, float p_180543_5_, int p_180543_6_, GameProfile p_180543_7_, int p_180543_8_)` | TileEntitySkullRenderer.java:42 | 头颅方块 TESR + 物品渲染（TileEntityItemStackRenderer.java:61）+ 头戴层（LayerCustomHead.java:106） | 换肤/皮肤加载观察、自定义头颅类型 | 三个调用方共用；静态 `instance` 在 dispatcher 构造时才非 null |
| `public void renderTileEntityAt(TileEntitySign te, double x, double y, double z, float partialTicks, int destroyStage)` | TileEntitySignRenderer.java:22 | 每帧每块可见告示牌 + GuiEditSign 预览（GuiEditSign.java:174） | 告示牌文字读取/替换（反和谐、翻译）、编辑高亮样式（`lineBeingEdited`，行 98） | 文字在 `depthMask(false)` 下绘制；每帧每牌都跑 `splitText`，重逻辑勿放此处 |
| `public void renderTileEntityAt(TileEntityPiston te, double x, double y, double z, float partialTicks, int destroyStage)` | TileEntityPistonRenderer.java:25 | 每帧每个移动中的活塞 | 观察活塞动画进度（`te.getProgress(partialTicks)`） | 使用 Tessellator 全局单例，不可重入 |
| `public void bufferData(ByteBuffer p_181722_1_)` | VertexBuffer.java:24 | 区块编译上传（VertexBufferUploader.java:12）、天空/星星 VBO 构建 | 观察/替换上传的区块几何数据（X-ray 类改动的底层入口之一） | 主线程 only；`count` 由 `limit() / getNextOffset()` 推得，buffer limit 必须恰好是 stride 整数倍 |
| `public void drawArrays(int mode)` | VertexBuffer.java:32 | VboRenderList.java:22（mode=7 GL_QUADS）、RenderGlobal 天空绘制（如 RenderGlobal.java:1239） | 按区块跳过绘制、包裹计数 draw call | 调用前必须已 bindBuffer 且指针已设置，否则读到别的 VBO |
| `public VertexFormat addElement(VertexFormatElement element)` | VertexFormat.java:53 | DefaultVertexFormats static 块及少数动态构造处 | 扩展顶点格式（如加自定义属性） | `DefaultVertexFormats` 的常量是全局共享可变对象，运行期改它等于改所有使用方的布局 |

## 数据与协议

无封包/NBT/文件格式。唯一的"注册表"是两个内存映射：

**`TileEntityRendererDispatcher.mapSpecialRenderers`**（TileEntityRendererDispatcher.java:29，构造器行 52-61 填充）：

| 键（TileEntity 类） | 值（渲染器） |
|---|---|
| `TileEntitySign.class` | `TileEntitySignRenderer` |
| `TileEntityMobSpawner.class` | `TileEntityMobSpawnerRenderer` |
| `TileEntityPiston.class` | `TileEntityPistonRenderer` |
| `TileEntityChest.class` | `TileEntityChestRenderer` |
| `TileEntityEnderChest.class` | `TileEntityEnderChestRenderer` |
| `TileEntityEnchantmentTable.class` | `TileEntityEnchantmentTableRenderer` |
| `TileEntityEndPortal.class` | `TileEntityEndPortalRenderer` |
| `TileEntityBeacon.class` | `TileEntityBeaconRenderer` |
| `TileEntitySkull.class` | `TileEntitySkullRenderer` |
| `TileEntityBanner.class` | `TileEntityBannerRenderer` |

**顶点内存布局**（`DefaultVertexFormats.BLOCK`，区块 VBO 与 `VboRenderList.setupArrayPointers` 硬编码共同约定）：

| 偏移 | 元素 | GL 类型 | 含义 |
|---|---|---|---|
| 0 | POSITION_3F | 3 × GL_FLOAT (5126) | 顶点坐标 |
| 12 | COLOR_4UB | 4 × GL_UNSIGNED_BYTE (5121) | RGBA 顶点色 |
| 16 | TEX_2F | 2 × GL_FLOAT (5126) | 方块纹理 UV（纹理单元 0） |
| 24 | TEX_2S | 2 × GL_SHORT (5122) | 光照贴图坐标（纹理单元 1，即 `OpenGlHelper.lightmapTexUnit`） |
| 28 | — | — | stride |

skullType 取值（TileEntitySkullRenderer.java:57-98）：0 骷髅、1 凋灵骷髅、2 僵尸、3 玩家（用 GameProfile 查皮肤）、4 苦力怕。

## 不变量与陷阱

- **`VboRenderList.setupArrayPointers` 硬编码 stride 28**（VboRenderList.java:34 `GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L)`）——它与 `DefaultVertexFormats.BLOCK` 的布局是隐式耦合的；改 BLOCK 格式必须同步改那里，编译器不会帮你。
- **`getSpecialRendererByClass` 会污染 map**：对未注册子类的查询把父类渲染器（或 null）缓存进 `mapSpecialRenderers`（TileEntityRendererDispatcher.java:76）。因此：(1) 自定义渲染器注册必须早于首次查询；(2) `RenderChunk.rebuildChunk` 在 **ChunkRenderWorker 线程**调用该方法（RenderChunk.java:161），而 map 是普通 HashMap——vanilla 靠"稳态后不再写入"侥幸安全，若在运行期热注册渲染器存在真实的数据竞争（HashMap 并发读写可致死循环/丢失条目）。
- `TileEntityRendererDispatcher` 构造器是 private 且 `instance` 是 static final 语义（实际非 final 但无人重赋值）；所有 TESR 是**无状态单例**，不要在渲染器实例里存每个 TileEntity 的状态。
- `VertexFormatElement` 构造器会**悄悄改写 usage**：index != 0 且非 UV 时强制变 UV（VertexFormatElement.java:16-20），只打 warn 不抛异常。
- `VertexFormat.getNextOffset()` 名字有误导——它返回的是 **stride（每顶点总字节数）**，不是"下一个偏移"以外的任何东西。
- `DefaultVertexFormats` 的 `VertexFormat` 常量是**可变对象**（`addElement`/`clear` 都是 public），全客户端共享，任何运行期修改都是全局性的。
- `VertexBuffer.drawArrays` 不做 bind；`bufferData` 用 35044（GL_STATIC_DRAW）——区块几何虽会重建但每次重建都完整重新 `glBufferData`，与 STATIC_DRAW 语义相符。
- `TileEntityPistonRenderer.renderTileEntityAt` 行 66 `iblockstate.withProperty(BlockPistonBase.EXTENDED, Boolean.valueOf(true));` 的**返回值被丢弃**——vanilla 原样保留的无效语句（IBlockState 不可变），移植时勿"顺手修复"改变渲染行为，也别指望它生效。
- `TileEntitySignRenderer` 行 98-106 的 if/else 两个分支的 `drawString` 调用完全相同，区别只在 `s` 是否被 `"> " + s + " <"` 包裹——这是反编译产物形态，语义正常。
- destroyStage 裂纹渲染依赖 **GL 纹理矩阵**（`matrixMode(5890)` push/scale/pop，SignRenderer.java:64-68、SkullRenderer.java:49-53）——固定管线特性；本移植仍走 lwjgl2-shim 的兼容 profile，若未来上 core profile 这套会失效。
- LWJGL3/JDK25 移植注意：本桶代码本身零改动（`org.lwjgl.opengl.GL11` 由 shim 提供同名 API）；`VertexBuffer` 经 `OpenGlHelper` 间接调 GL15/ARB VBO（`OpenGlHelper.vboSupported` 在 OpenGlHelper.java:263 探测），所有 GL 调用必须在持有上下文的主线程。
- `TileEntitySignRenderer.renderTileEntityAt` 行 84 直接调 `GL11.glNormal3f` 绕过 `GlStateManager`——glNormal 无状态缓存，安全，但写 hook 时别假设一切 GL 调用都过 GlStateManager。
- GL 常量速查（源码里全是裸数字）：7 = GL_QUADS、7424/7425 = GL_FLAT/GL_SMOOTH、770/771 = GL_SRC_ALPHA/GL_ONE_MINUS_SRC_ALPHA、5888/5890 = GL_MODELVIEW/GL_TEXTURE、35044 = GL_STATIC_DRAW。

## 交叉引用

- `net.minecraft.client.renderer` → `RenderGlobal#renderEntities`：每帧调 `cacheActiveRenderInfo`（RenderGlobal.java:568）、写 `staticPlayerX/Y/Z`（:577-579）、调 `renderTileEntity`（:695/:704/:735）
- `net.minecraft.client.renderer.chunk` → `RenderChunk#rebuildChunk`：worker 线程调 `getSpecialRenderer`/`forceTileEntityRender`（RenderChunk.java:161/167）；`RenderChunk` 构造 `new VertexBuffer(DefaultVertexFormats.BLOCK)`（:66），销毁调 `deleteGlBuffers`（:365）
- `net.minecraft.client.renderer.chunk` → `ChunkRenderDispatcher#uploadVertexBuffer`：经 `VertexBufferUploader` 上传区块几何（ChunkRenderDispatcher.java:272）
- `net.minecraft.client.renderer` → `VertexBufferUploader#draw`：`VertexBuffer.bufferData`（VertexBufferUploader.java:12）
- `net.minecraft.client.renderer` → `VboRenderList#renderChunkLayer`：`bindBuffer`/`drawArrays(7)`（VboRenderList.java:20-22）
- `net.minecraft.client.renderer` → `WorldRenderer#begin` 等：全面依赖 `VertexFormat` 的 stride/offset 查询（WorldRenderer.java:91 等）
- `net.minecraft.client.renderer` → `WorldVertexBufferUploader#draw`：遍历 `VertexFormatElement`，用 `getGlConstant()` 设置指针（WorldVertexBufferUploader.java:25-32）
- `net.minecraft.client.renderer` → `OpenGlHelper#glGenBuffers/glBindBuffer/glBufferData/glDeleteBuffers`：`VertexBuffer` 的全部 GL 调用出口；`OpenGlHelper#useVbo`（OpenGlHelper.java:629）决定是否走 VBO 路径
- `net.minecraft.client.gui.inventory` → `GuiEditSign#drawScreen`：`renderTileEntityAt(this.tileSign, -0.5D, -0.75D, -0.5D, 0.0F)`（GuiEditSign.java:174）
- `net.minecraft.client.renderer.tileentity` → `TileEntityItemStackRenderer#renderByItem`：`TileEntitySkullRenderer.instance.renderSkull`（TileEntityItemStackRenderer.java:61）
- `net.minecraft.client.renderer.entity.layers` → `LayerCustomHead#doRenderLayer`：`TileEntitySkullRenderer.instance.renderSkull`（LayerCustomHead.java:106）
- `net.minecraft.client.renderer` → `BlockRendererDispatcher#getBlockModelRenderer/getModelFromBlockState`：活塞渲染器复用方块模型系统（TileEntityPistonRenderer.java:57-71）
- `net.minecraft.client.resources` → `DefaultPlayerSkin#getDefaultSkin/getDefaultSkinLegacy` 与 `net.minecraft.client.resources.SkinManager#loadSkinFromCache/loadSkin`：玩家头颅皮肤（TileEntitySkullRenderer.java:75-93）
- `net.minecraft.client.gui` → `GuiUtilRenderComponents#splitText` / `FontRenderer#drawString`：告示牌文字（TileEntitySignRenderer.java:95-105）
- `net.minecraft.realms` → `RealmsVertexFormat`：包装 `VertexFormat` 暴露给 Realms API（RealmsVertexFormat.java:87-102）
- `net.minecraft.crash` → `CrashReport#makeCrashReport` / `TileEntity#addInfoToCrashReport`：TESR 异常上报（TileEntityRendererDispatcher.java:138-140）

## 覆盖声明

完整读取了 9/9 个文件（行数合计 79+155+122+147+50+68+50+202+156 = 1029，与桶清单一致）。逐行精读的类：`TileEntityRendererDispatcher`、`TileEntitySpecialRenderer`、`TileEntityPistonRenderer`、`TileEntitySignRenderer`、`TileEntitySkullRenderer`、`DefaultVertexFormats`、`VertexBuffer`、`VertexFormat`、`VertexFormatElement`（即全部 9 个）。另外为核实调用方，对桶外文件做了定点 grep/节选阅读（未通读）：`RenderGlobal`、`RenderChunk`、`ChunkRenderDispatcher`、`VboRenderList`、`VertexBufferUploader`、`WorldVertexBufferUploader`、`WorldRenderer`、`OpenGlHelper`、`GuiEditSign`、`TileEntityItemStackRenderer`、`LayerCustomHead`、`RealmsVertexFormat`。所有行号均来自本仓库当前源码。
