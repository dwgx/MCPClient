---
area: net/minecraft/client/renderer/texture
slug: mc-client-renderer-texture
files: 16
lines: 2209
tier: A
---

# net/minecraft/client/renderer/texture

## 定位

这个包是客户端所有 GL 纹理的生命周期管理层：从资源包读 PNG（`ImageIO` → `BufferedImage`）、拼接方块/物品图集（`Stitcher` + `TextureMap`）、按 tick 推进动画帧（`ITickable`）、把像素数据上传到 GL（`TextureUtil`）。核心入口是 `TextureManager`（即 `Minecraft.renderEngine`，`Minecraft.java:498` 创建），几乎所有渲染代码在绑定任何纹理前都要经过 `TextureManager#bindTexture`。

- **谁调用它**：`Minecraft`（初始化 + 每 tick 调 `renderEngine.tick()`，`Minecraft.java:1763`）、`EntityRenderer`（lightmap 用 `DynamicTexture`）、`ModelBakery`（通过 `IIconCreator` 向 `TextureMap` 注册全部方块/物品 sprite）、`RenderGlobal` / `BlockFluidRenderer` / `ItemRenderer` / `GuiContainer` 等（取 `TextureAtlasSprite` 的 UV）、`SkinManager` / `MapItemRenderer` / `GuiMainMenu` / `ResourcePackRepository`（动态纹理）、`RenderHorse` / `TileEntityBannerRenderer`（分层纹理）。
- **它调用谁**：`GlStateManager`（genTexture/bindTexture/deleteTexture）、直接的 `GL11/GL12/GL14` 调用（glTexImage2D / glTexSubImage2D / glTexParameteri）、`IResourceManager`（读资源）、`AnimationMetadataSection` / `TextureMetadataSection`（mcmeta 元数据）。
- **如果它消失**：任何纹理都无法加载和绑定——方块/物品图集不存在、字体和 GUI 贴图无法上传、动画纹理（水、岩浆、指南针、钟）停止、lightmap 无法更新。客户端在 `Minecraft.startGame` 阶段即崩溃。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| AbstractTexture | 65 | implements ITextureObject | 持有 glTextureId 与 blur/mipmap 过滤状态的纹理基类 |
| DynamicTexture | 45 | extends AbstractTexture | CPU 侧 int[] 像素数组、可随时整体重传的动态纹理（lightmap、地图、logo 等） |
| IIconCreator | 6 | interface | 回调接口：在图集重建时向 TextureMap 注册 sprite |
| ITextureObject | 15 | interface | 纹理对象最小契约：loadTexture / getGlTextureId / blur-mipmap 存取 |
| ITickable | 6 | interface | 单方法 `tick()` |
| ITickableTextureObject | 5 | interface, extends ITextureObject, ITickable | 可 tick 的纹理对象（目前只有 TextureMap 实现） |
| LayeredColorMaskTexture | 94 | extends AbstractTexture | 底图 + 多层染色蒙版合成（旗帜纹理） |
| LayeredTexture | 55 | extends AbstractTexture | 多张贴图简单叠加合成（马的变种皮肤） |
| SimpleTexture | 64 | extends AbstractTexture | 单文件 PNG 纹理，读 mcmeta 的 blur/clamp 后整图上传 |
| Stitcher | 420 | （无，含内部类 Holder/Slot） | 二维装箱算法：把一组 sprite 排进一张图集并给出每个的位置 |
| TextureAtlasSprite | 432 | （无） | 图集中一个 sprite：UV、帧数据、动画推进、mipmap 生成 |
| TextureClock | 63 | extends TextureAtlasSprite | 钟表 sprite：按世界时间角度选帧 |
| TextureCompass | 98 | extends TextureAtlasSprite | 指南针 sprite：按出生点方位角选帧 |
| TextureManager | 142 | implements ITickable, IResourceManagerReloadListener | 全局纹理注册表：ResourceLocation → ITextureObject，绑定/加载/tick/重载 |
| TextureMap | 319 | extends AbstractTexture implements ITickableTextureObject | 方块/物品纹理图集：注册 sprite → 读图 → mipmap → stitch → 上传 → 动画 |
| TextureUtil | 380 | （无，纯静态） | GL 上传工具：分配纹理、glTexSubImage2D 分块上传、mipmap 数据生成、missing 纹理 |

## 核心类详解

### TextureManager（TextureManager.java）

字段（`TextureManager.java:22-25`）：
- `private final Map<ResourceLocation, ITextureObject> mapTextureObjects`（HashMap）
- `private final List<ITickable> listTickables`
- `private final Map<String, Integer> mapTextureCounters` — 动态纹理名去重计数
- `private IResourceManager theResourceManager`

关键方法：
- `public void bindTexture(ResourceLocation resource)`（`TextureManager.java:32`）— 若未注册则懒创建 `SimpleTexture` 并 `loadTexture`，然后 `TextureUtil.bindTexture(itextureobject.getGlTextureId())`。这是全客户端绑定纹理的统一入口（FontRenderer、Gui、实体渲染全部走这里）。
- `public boolean loadTexture(ResourceLocation textureLocation, ITextureObject textureObj)`（`TextureManager.java:58`）— 调 `textureObj.loadTexture(this.theResourceManager)`；`IOException` 时降级为 `TextureUtil.missingTexture` 并返回 false（`TextureManager.java:66-72`）；其它 `Throwable` 直接抛 `ReportedException` 崩溃报告（`TextureManager.java:73-87`）。
- `public boolean loadTickableTexture(ResourceLocation textureLocation, ITickableTextureObject textureObj)`（`TextureManager.java:45`）— loadTexture 成功后追加进 `listTickables`。
- `public ITextureObject getTexture(ResourceLocation textureLocation)`（`TextureManager.java:93`）
- `public ResourceLocation getDynamicTextureLocation(String name, DynamicTexture texture)`（`TextureManager.java:98`）— 生成 `"dynamic/%s_%d"` 形式的位置并注册；被 `EntityRenderer:191`（lightMap）、`MapItemRenderer:81`、`GuiMainMenu:197`、`ResourcePackRepository:348` 等调用。
- `public void tick()`（`TextureManager.java:117`）— 遍历 `listTickables` 调 `tick()`；由 `Minecraft.runTick()` 在非暂停时每 tick 调用（`Minecraft.java:1763`）。
- `public void deleteTexture(ResourceLocation textureLocation)`（`TextureManager.java:125`）— 只删 GL 对象，**不从 map 移除条目**。
- `public void onResourceManagerReload(IResourceManager resourceManager)`（`TextureManager.java:135`）— 资源包重载时对 map 里每个纹理重新 `loadTexture`。

### TextureMap（TextureMap.java）

字段（`TextureMap.java:28-37`）：
- `public static final ResourceLocation LOCATION_MISSING_TEXTURE = new ResourceLocation("missingno")`（`TextureMap.java:29`）
- `public static final ResourceLocation locationBlocksTexture = new ResourceLocation("textures/atlas/blocks.png")`（`TextureMap.java:30`）
- `private final List<TextureAtlasSprite> listAnimatedSprites`
- `private final Map<String, TextureAtlasSprite> mapRegisteredSprites` / `mapUploadedSprites`
- `private final String basePath` — 实际实例为 `"textures"`（`Minecraft.java:548`）
- `private final IIconCreator iconCreator`；`private int mipmapLevels`；`private final TextureAtlasSprite missingImage`

关键方法：
- `public void loadTexture(IResourceManager resourceManager) throws IOException`（`TextureMap.java:64`）— 仅当 `iconCreator != null` 时转 `loadSprites`。注意 `Minecraft` 用无 creator 构造器创建实例，所以 `TextureManager.onResourceManagerReload` 对它是 no-op；真正的重建由 `ModelBakery.loadSprites()`（`ModelBakery.java:584-601`）驱动。
- `public void loadSprites(IResourceManager resourceManager, IIconCreator p_174943_2_)`（`TextureMap.java:72`）— 清空注册表 → `p_174943_2_.registerSprites(this)` → `initMissingImage()` → `deleteGlTexture()` → `loadTextureAtlas(resourceManager)`。
- `public void loadTextureAtlas(IResourceManager resourceManager)`（`TextureMap.java:81`）— 完整重建流程：
  1. `Minecraft.getGLMaximumTextureSize()` 定上限，`new Stitcher(i, i, true, 0, this.mipmapLevels)`（`TextureMap.java:83-84`）；
  2. 逐 sprite 读 PNG（`completeResourceLocation` 拼 `"%s/%s%s"` 路径，`TextureMap.java:258-261`），读 `"texture"` mcmeta 的额外 miplevel 图、读 `"animation"` mcmeta 后 `textureatlassprite.loadSprite(abufferedimage, animationmetadatasection)`（`TextureMap.java:140-141`）；失败的 sprite 被 continue 跳过（之后落到 missingImage）；
  3. 按最小 sprite 尺寸下调 `mipmapLevels`（`TextureMap.java:166-173`）；
  4. 所有 sprite `generateMipmaps(this.mipmapLevels)`（`TextureMap.java:179`），失败抛 `ReportedException`；
  5. `stitcher.doStitch()`（`TextureMap.java:216`），`TextureUtil.allocateTextureImpl(this.getGlTextureId(), this.mipmapLevels, ...)`（`TextureMap.java:224`）；
  6. 对每个已放置 sprite 调 `TextureUtil.uploadTextureMipmap(...)` 上传第 0 帧（`TextureMap.java:235`），有动画元数据的加入 `listAnimatedSprites`（`TextureMap.java:246-249`）；
  7. 未能上传的 sprite 全部 `copyFrom(this.missingImage)`（`TextureMap.java:252-255`）。
- `public TextureAtlasSprite registerSprite(ResourceLocation location)`（`TextureMap.java:285`）— null 抛 `IllegalArgumentException`；用 `TextureAtlasSprite.makeAtlasSprite(location)` 创建并以 `location.toString()` 为 key 存入。
- `public TextureAtlasSprite getAtlasSprite(String iconName)`（`TextureMap.java:263`）— 查不到返回 `missingImage`，永不为 null。
- `public void updateAnimations()`（`TextureMap.java:275`）— 绑定图集后遍历 `listAnimatedSprites` 调 `updateAnimation()`。
- `public void tick()`（`TextureMap.java:305`）— 即 `updateAnimations()`；经 `TextureManager.listTickables` 每 tick 触发。
- `public void setMipmapLevels(int mipmapLevelsIn)`（`TextureMap.java:310`）— 被 `Minecraft.java:549` 和 `GameSettings.java:315`（视频设置里改 mipmap 档位）调用。
- `public TextureAtlasSprite getMissingSprite()`（`TextureMap.java:315`）

### Stitcher（Stitcher.java）

字段（`Stitcher.java:13-23`）：`mipmapLevelStitcher`、`Set<Stitcher.Holder> setStitchHolders`、`List<Stitcher.Slot> stitchSlots`、`currentWidth/currentHeight`、`maxWidth/maxHeight`、`forcePowerOf2`、`maxTileDimension`。

- `public void addSprite(TextureAtlasSprite p_110934_1_)`（`Stitcher.java:44`）— 包成 `Holder`；`maxTileDimension > 0` 时 `setNewDimension` 限制单 tile 尺寸（TextureMap 传 0，不生效）。
- `public void doStitch()`（`Stitcher.java:56`）— Holder 数组排序（按高→宽→名字降序，`Holder#compareTo`，`Stitcher.java:270-294`）后逐个 `allocateSlot`；放不下抛 `StitcherException`（`net.minecraft.client.renderer.StitcherException`），消息含 `"Unable to fit: %s ..."`（`Stitcher.java:65`）。`forcePowerOf2` 时最终尺寸向上取 2 的幂。
- `public List<TextureAtlasSprite> getStichSlots()`（`Stitcher.java:77`）— 收集所有已占用叶子 Slot，对每个 sprite 调 `initSprite(this.currentWidth, this.currentHeight, slot.getOriginX(), slot.getOriginY(), holder.isRotated())`（`Stitcher.java:92`）。
- `private boolean allocateSlot(Stitcher.Holder p_94310_1_)`（`Stitcher.java:107`）— 先在现有 Slot 树里试原方向和旋转 90° 两个方向；都失败则 `expandAndAllocateSlot` 扩容（`Stitcher.java:132`）。
- `Stitcher.Slot#addSlot(Stitcher.Holder holderIn)`（`Stitcher.java:329`）— 经典 guillotine 切分：完全占用则记 holder，否则把剩余空间切成子 Slot 递归。
- `Stitcher.Holder` 会在构造时根据 mipmap 对齐后的宽高决定初始 `rotated`（`Stitcher.java:229`）；`getWidth()/getHeight()` 返回按 `getMipmapDimension`（`Stitcher.java:99-102`）对齐到 `1<<mipmapLevel` 倍数的尺寸。

### TextureAtlasSprite（TextureAtlasSprite.java）

字段（`TextureAtlasSprite.java:18-34`）：`iconName`、`List<int[][]> framesTextureData`（帧 × miplevel × 像素）、`int[][] interpolatedFrameData`、`AnimationMetadataSection animationMetadata`、`rotated/originX/originY/width/height`、`minU/maxU/minV/maxV`、`frameCounter/tickCounter`、静态 `locationNameClock = "builtin/clock"` / `locationNameCompass = "builtin/compass"`。

- `protected static TextureAtlasSprite makeAtlasSprite(ResourceLocation spriteResourceLocation)`（`TextureAtlasSprite.java:41`）— 名字命中 clock/compass 时实例化 `TextureClock` / `TextureCompass`，否则普通 sprite。`ModelBakery.java:624/628` 会在解析模型时把实际贴图名写进 `setLocationNameCompass/setLocationNameClock`。
- `public void initSprite(int inX, int inY, int originInX, int originInY, boolean rotatedIn)`（`TextureAtlasSprite.java:57`）— 计算 UV 时向内收缩 `0.009999999776482582D / inX`（约 0.01 像素级）防 bleeding（`TextureAtlasSprite.java:62-67`）。
- `public void loadSprite(BufferedImage[] images, AnimationMetadataSection meta) throws IOException`（`TextureAtlasSprite.java:255`）— `meta == null` 且 `j != i` 抛 `RuntimeException("broken aspect ratio and not an animation")`（`TextureAtlasSprite.java:284`）；有 meta 时按竖条切帧，帧越界抛 `"invalid frameindex "`；miplevel 图尺寸不匹配抛 `"Unable to load miplevel: ..."`（`TextureAtlasSprite.java:272`）。
- `public void updateAnimation()`（`TextureAtlasSprite.java:170`）— 每 tick 由 `TextureMap.updateAnimations` 调；到达帧时长则换帧并 `TextureUtil.uploadTextureMipmap(...)`，否则若 `animationMetadata.isInterpolate()` 走 `updateAnimationInterpolated()`（`TextureAtlasSprite.java:193`，逐像素 RGB 线性插值后上传）。
- `public void generateMipmaps(int level)`（`TextureAtlasSprite.java:330`）— 逐帧调 `TextureUtil.generateMipmapData`，异常包成 `ReportedException`。
- 读 UV 的 getter：`getMinU/getMaxU/getMinV/getMaxV`、`public float getInterpolatedU(double u)`（`TextureAtlasSprite.java:134`）、`public float getInterpolatedV(double v)`（`TextureAtlasSprite.java:159`）— 被 BlockFluidRenderer、粒子、GuiContainer 等大量消费。
- `public void copyFrom(TextureAtlasSprite atlasSpirit)`（`TextureAtlasSprite.java:70`）— 加载失败的 sprite 拷贝 missingImage 的位置与 UV。

### TextureUtil（TextureUtil.java，纯静态）

字段：
- `private static final IntBuffer dataBuffer = GLAllocation.createDirectIntBuffer(4194304)`（`TextureUtil.java:23`）— 16MB 共享上传缓冲。
- `public static final DynamicTexture missingTexture = new DynamicTexture(16, 16)`（`TextureUtil.java:24`）+ `public static final int[] missingTextureData`（`TextureUtil.java:25`）— 黑紫棋盘在 static 块生成并立即 `updateDynamicTexture()`（`TextureUtil.java:363-379`）。
- `private static final int[] mipmapBuffer`（长度 4）。

关键方法：
- `public static int glGenTextures()`（`TextureUtil.java:28`）/ `public static void deleteTexture(int textureId)`（:33）/ `static void bindTexture(int p_94277_0_)`（:295）— 全部转发 `GlStateManager`，保证状态缓存一致。
- `public static void allocateTextureImpl(int p_180600_0_, int p_180600_1_, int p_180600_2_, int p_180600_3_)`（`TextureUtil.java:195`）— 删旧、绑定、设 `GL12.GL_TEXTURE_MAX_LEVEL/MIN_LOD/MAX_LOD` 与 `GL14.GL_TEXTURE_LOD_BIAS`，然后每级 `glTexImage2D(..., GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, null)` 占位。
- `public static void uploadTextureMipmap(int[][] p_147955_0_, int p_147955_1_, int p_147955_2_, int p_147955_3_, int p_147955_4_, boolean p_147955_5_, boolean p_147955_6_)`（`TextureUtil.java:158`）— 每 miplevel 走 `uploadTextureSub`。
- `private static void uploadTextureSub(...)`（`TextureUtil.java:167`）— 以 `4194304 / width` 行为批次拷进 `dataBuffer` 后 `GL11.glTexSubImage2D(..., GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, dataBuffer)`。
- `public static int uploadTextureImageAllocate(int p_110989_0_, BufferedImage p_110989_1_, boolean p_110989_2_, boolean p_110989_3_)`（`TextureUtil.java:184`）— SimpleTexture 等整图路径。
- `public static int[][] generateMipmapData(int p_147949_0_, int p_147949_1_, int[][] p_147949_2_)`（`TextureUtil.java:49`）— CPU 侧 2×2 盒式降采样；含 alpha 的纹理走 gamma-2.2 加权分支 `blendColors`（`TextureUtil.java:98`）。
- `private static void setTextureClamped(boolean p_110997_0_)`（`TextureUtil.java:241`）— **移植点**：clamp 分支用 `GL12.GL_CLAMP_TO_EDGE`（源码注释说明 `GL_CLAMP` 0x2900 在 core-ish profile 下无效，见 `TextureUtil.java:245-248`），与原版 1.8.9 的 `GL11.GL_CLAMP` 不同。
- `private static void copyToBufferPos(int[] p_110994_0_, int p_110994_1_, int p_110994_2_)`（`TextureUtil.java:281`）— 若 `Minecraft.getMinecraft().gameSettings.anaglyph` 为真，先经 `updateAnaglyph`（`TextureUtil.java:326`）做红青 3D 变换再上传。
- `public static BufferedImage readBufferedImage(InputStream imageStream) throws IOException`（`TextureUtil.java:310`）— `ImageIO.read` + `IOUtils.closeQuietly`；本包所有 PNG 解码入口。
- `public static void processPixelValues(int[] p_147953_0_, int p_147953_1_, int p_147953_2_)`（`TextureUtil.java:350`）— 行序垂直翻转（截图 glReadPixels 后处理用）。

### AbstractTexture / DynamicTexture / SimpleTexture / Layered*

- `AbstractTexture`：`public int getGlTextureId()`（`AbstractTexture.java:47`）懒生成 GL id；`public void setBlurMipmapDirect(boolean p_174937_1_, boolean p_174937_2_)`（`AbstractTexture.java:13`）直接对**当前绑定的** TEXTURE_2D 设 min/mag filter（常量 9987=GL_LINEAR_MIPMAP_LINEAR、9729=GL_LINEAR、9986=GL_NEAREST_MIPMAP_LINEAR、9728=GL_NEAREST）；`setBlurMipmap`/`restoreLastBlurMipmap`（`AbstractTexture.java:35/42`）保存-恢复一层过滤状态（FontRenderer 等临时开 blur 用）。
- `DynamicTexture`：构造即 `TextureUtil.allocateTexture(this.getGlTextureId(), textureWidth, textureHeight)`（`DynamicTexture.java:29`，需要 GL 上下文）；`public void updateDynamicTexture()`（`DynamicTexture.java:36`）把 `dynamicTextureData` 整体重传；`public int[] getTextureData()`（:41）暴露内部数组供外部直接写（`EntityRenderer.java:192` 的 lightmapColors 就是这个数组，每帧改完调 `updateDynamicTexture()`，`EntityRenderer.java:1056`）。`loadTexture` 为空实现——重载资源包时不会重建内容。
- `SimpleTexture#loadTexture`（`SimpleTexture.java:23`）：读 `"texture"` mcmeta 的 `getTextureBlur()/getTextureClamp()` 后 `TextureUtil.uploadTextureImageAllocate`；元数据解析失败仅 warn。
- `LayeredTexture#loadTexture`（`LayeredTexture.java:24`）：按 `layeredTextureNames` 顺序把多张图画到一张 `BufferedImage(w, h, 2)` 上再上传；IOException 仅 log 后 return（**纹理保持未上传**）。调用方：`RenderHorse.java:95`。
- `LayeredColorMaskTexture#loadTexture`（`LayeredColorMaskTexture.java:34`）：底图 + 最多 17 层蒙版，每层用 `EnumDyeColor.getMapColor().colorValue` 经 `MathHelper.func_180188_d`（`MathHelper.java:368`）做颜色相乘后叠加。调用方：`TileEntityBannerRenderer.java:133`。

### TextureClock / TextureCompass

- `TextureClock#updateAnimation()`（`TextureClock.java:16`）：读 `minecraft.theWorld.getCelestialAngle(1.0F)`，非主世界（`!minecraft.theWorld.provider.isSurfaceWorld()`）用 `Math.random()` 乱转；弹簧阻尼（`angleDelta += d1 * 0.1D; angleDelta *= 0.8D`）平滑后选帧上传。
- `TextureCompass`：`public double currentAngle` / `public double angleDelta` / `public static String locationSprite`（`TextureCompass.java:11-15`，public 字段）；`public void updateCompass(World worldIn, double p_94241_2_, double p_94241_4_, double p_94241_6_, boolean p_94241_8_, boolean p_94241_9_)`（`TextureCompass.java:40`）按 `worldIn.getSpawnPoint()` 算方位角；`p_94241_9_` 为 true 时直接跳到目标角（物品展示框用，`RenderItemFrame.java:143`）。

## 时序与生命周期

全部在**主线程（GL 线程）**执行，本包没有任何自建线程或 Netty 交互。

初始化（`Minecraft.startGame`）：
1. `Minecraft.java:498` — `this.renderEngine = new TextureManager(this.mcResourceManager)`；`:499` 注册为 reload listener；`:500` `drawSplashScreen(this.renderEngine)`（此时首次触发 `TextureUtil` 静态初始化 → missingTexture 上传，**要求 GL 上下文已就绪**）。
2. `Minecraft.java:548-552` — `new TextureMap("textures")` → `setMipmapLevels(gameSettings.mipmapLevels)` → `renderEngine.loadTickableTexture(TextureMap.locationBlocksTexture, this.textureMapBlocks)`（此时 iconCreator 为 null，loadTexture 是 no-op，只完成注册）→ `bindTexture` → `setBlurMipmapDirect(false, mipmapLevels > 0)`。
3. `Minecraft.java:553` — `new ModelManager(this.textureMapBlocks)` 注册 reload listener；资源加载时 `ModelBakery.loadSprites()`（`ModelBakery.java:584`）构造 `IIconCreator` 并调 `textureMap.loadSprites(...)`，真正完成图集 stitch 与上传。

每 tick（主线程，`Minecraft.runTick`）：
- `Minecraft.java:1763` `this.renderEngine.tick()`（非暂停时）→ `TextureManager.tick()` → `listTickables`（即 `textureMapBlocks`）→ `TextureMap.updateAnimations()` → 绑定图集 → 每个动画 sprite `updateAnimation()`，需要换帧的执行 `glTexSubImage2D` 局部上传。

每帧：
- 本包自身没有每帧逻辑；但 `EntityRenderer` 每帧可能改 lightmap 像素并调 `DynamicTexture.updateDynamicTexture()`（`EntityRenderer.java:1056`），走本包上传路径。渲染代码每帧大量调用 `TextureManager.bindTexture`。

资源包重载：
- `SimpleReloadableResourceManager` 依注册顺序回调 → `TextureManager.onResourceManagerReload`（`TextureManager.java:135`）对全部已注册纹理重新 loadTexture；随后 `ModelManager` 的回调触发图集重建（`TextureMap.loadSprites`）。改 mipmap 设置时 `GameSettings.java:315-317` 先 `setMipmapLevels` + `setBlurMipmapDirect` 再触发资源刷新。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void bindTexture(ResourceLocation resource)` | TextureManager.java:32 | 每帧极高频，所有渲染绑定纹理的统一入口 | 纹理替换/重定向（换皮肤、换 GUI 贴图）、统计绑定、注入自定义 ITextureObject | 极热路径，勿做 IO 或分配；懒加载分支会把任意路径落成 SimpleTexture |
| `public boolean loadTexture(ResourceLocation textureLocation, ITextureObject textureObj)` | TextureManager.java:58 | 任何纹理注册/重载时 | 拦截或包装纹理对象、注入自定义加载逻辑、观察失败降级 | 覆盖同 key 会替换旧对象但不删旧 GL id（泄漏）；Throwable 分支直接崩溃 |
| `public void tick()` | TextureManager.java:117 | 每 tick 一次（Minecraft.java:1763，非暂停） | 挂接自定义 ITickable 纹理动画；暂停/加速全部动画纹理 | 主线程 GL 上下文内；列表无去重 |
| `public boolean loadTickableTexture(ResourceLocation textureLocation, ITickableTextureObject textureObj)` | TextureManager.java:45 | 注册可 tick 纹理（目前仅 blocks 图集） | 注册自己的可 tick 纹理对象 | 失败时不进 listTickables；重复注册会重复 tick |
| `public ResourceLocation getDynamicTextureLocation(String name, DynamicTexture texture)` | TextureManager.java:98 | 皮肤/地图/logo/背景等动态纹理注册 | 观察或劫持所有动态纹理的诞生 | 名字带自增计数，旧条目从不回收 |
| `public void onResourceManagerReload(IResourceManager resourceManager)` | TextureManager.java:135 | 资源包重载 | 重载后统一后处理（重新打补丁、清缓存） | 遍历中对同 key put，安全；但极耗时，阻塞主线程 |
| `public void updateAnimations()` | TextureMap.java:275 | 每 tick（经 TextureMap.tick） | 关停/节流动画纹理（性能模组常改这里只更新可见 sprite） | 先绑定图集再逐 sprite glTexSubImage2D；跳过会导致水/岩浆静止 |
| `public TextureAtlasSprite registerSprite(ResourceLocation location)` | TextureMap.java:285 | 图集重建时由 IIconCreator 回调 | 注入自定义 sprite 子类（自绘/程序化纹理入口） | 只能在 loadSprites 窗口内调用；null 抛 IllegalArgumentException |
| `public void loadTextureAtlas(IResourceManager resourceManager)` | TextureMap.java:81 | 资源重载/启动时图集重建 | 改 stitch 参数、插入额外 sprite、观察图集尺寸 | 抛 StitcherException 即崩溃；耗时大，主线程阻塞 |
| `public TextureAtlasSprite getAtlasSprite(String iconName)` | TextureMap.java:263 | 渲染层取 UV（流体、火焰、粒子、GUI） | 替换特定 sprite 的返回值实现贴图重定向 | 永不返回 null（缺失返回 missingImage） |
| `void registerSprites(TextureMap iconRegistry)` | IIconCreator（IIconCreator.java:5）；实现在 ModelBakery.java:591 | 图集重建时 | 追加自定义 sprite 进方块图集 | 注册过多/过大 sprite 会撑爆图集触发 StitcherException |
| `public void updateAnimation()` | TextureAtlasSprite.java:170 | 每 tick，每个动画 sprite | 覆写实现自定义动画逻辑（Clock/Compass 即先例） | 内部直接 GL 上传；animationMetadata 为 null 会 NPE（只有 listAnimatedSprites 里的才被调用） |
| `public void updateCompass(World worldIn, double p_94241_2_, double p_94241_4_, double p_94241_6_, boolean p_94241_8_, boolean p_94241_9_)` | TextureCompass.java:40 | 每 tick + RenderItemFrame.java:143 | 让指南针指向任意目标（waypoint 功能） | 静态 locationSprite 被构造器覆写；帧上传有 i != frameCounter 去抖 |
| `public void updateDynamicTexture()` | DynamicTexture.java:36 | 持有者随时（lightmap 每帧、地图更新时） | 任意 CPU 侧绘制后推 GL；自绘 HUD 纹理的标准通道 | 整幅重传，大纹理频繁调用是带宽杀手 |
| `public int[] getTextureData()` | DynamicTexture.java:41 | 持有者直接写像素 | 直接改像素数组（如 lightmap 调色） | 改完必须调 updateDynamicTexture 才生效 |
| `public void setBlurMipmapDirect(boolean p_174937_1_, boolean p_174937_2_)` | AbstractTexture.java:13 | GameSettings.java:317、FontRenderer 等 | 切换过滤模式（平滑字体/图集抗锯齿） | 作用于当前绑定纹理，调用前必须先 bind 正确对象 |
| `public static void uploadTextureMipmap(int[][] p_147955_0_, int p_147955_1_, int p_147955_2_, int p_147955_3_, int p_147955_4_, boolean p_147955_5_, boolean p_147955_6_)` | TextureUtil.java:158 | 所有图集局部上传（动画帧、stitch 后首传） | 统一观察/改写上传数据（着色、调试可视化） | 共享 dataBuffer 非线程安全；仅主线程 |
| `public void tick()` | ITickable.java:5 | TextureManager 每 tick 分发 | 实现该接口即可挂入纹理 tick 循环 | 需经 loadTickableTexture 注册 |

## 数据与协议

无封包/NBT/注册表。涉及的文件格式与元数据：

| 数据 | 类型/格式 | 读取方法 | 含义 |
|---|---|---|---|
| PNG 图片 | `BufferedImage`（ImageIO 解码） | `TextureUtil.readBufferedImage(InputStream)`（TextureUtil.java:310） | 所有纹理源；上传时按 ARGB int → `GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV` 解释 |
| `.mcmeta` `"texture"` 节 | `TextureMetadataSection` | `iresource.getMetadata("texture")`（SimpleTexture.java:40、TextureMap.java:101） | `getTextureBlur()`（线性过滤）、`getTextureClamp()`（边缘钳制）、`getListMipmaps()`（手工 miplevel 列表） |
| `.mcmeta` `"animation"` 节 | `AnimationMetadataSection` | `iresource.getMetadata("animation")`（TextureMap.java:140） | `getFrameCount()/getFrameIndex(i)/getFrameTimeSingle(i)/isInterpolate()`，驱动 TextureAtlasSprite.updateAnimation |
| 图集路径模板 | 字符串拼接 | `TextureMap.completeResourceLocation`（TextureMap.java:258） | level 0: `"{basePath}/{path}.png"`；miplevel n: `"{basePath}/mipmaps/{path}.{n}.png"` |
| 动态纹理命名 | `"dynamic/%s_%d"` | `TextureManager.getDynamicTextureLocation`（TextureManager.java:112) | 同名自增计数防冲突 |
| missing 纹理 | 16×16 黑紫棋盘 | `TextureUtil` static 块（TextureUtil.java:363-379） | 颜色常量 `-524040`（紫）与 `-16777216`（黑） |

## 不变量与陷阱

- **GL 上下文前置**：`TextureUtil` 的静态初始化会构造 `DynamicTexture(16,16)` 并立即执行 GL 调用（`TextureUtil.java:24/377`）。任何在 GLFW 上下文创建之前触碰 `TextureUtil`（包括仅引用 `missingTextureData`）的代码都会崩溃。LWJGL3 移植下这一点比 LWJGL2 更严格（无隐式 Display 上下文）。
- **单线程约束**：`dataBuffer` 是全局共享 `IntBuffer`（`TextureUtil.java:23`），`copyToBufferPos` 无任何同步；本包所有方法只能在主线程调用。
- **`setBlurMipmapDirect` 作用于"当前绑定"**：`AbstractTexture.java:31-32` 直接 `glTexParameteri(GL_TEXTURE_2D, ...)`，不 bind 自己。调用前必须保证目标纹理已绑定（`Minecraft.java:551` 先 bind 再调即是范例）。
- **`GL_CLAMP` → `GL_CLAMP_TO_EDGE` 移植差异**：`TextureUtil.setTextureClamped`（`TextureUtil.java:247-248`）已改用 `GL12.GL_CLAMP_TO_EDGE`，与原版 1.8.9 的 `GL11.GL_CLAMP` 不同；边缘像素行为更正确，但与依赖旧行为的着色器包对比时要留意。
- **`registerSprite` 的 key 类型错配**：`TextureMap.java:293` 对 `Map<String, TextureAtlasSprite>` 调 `this.mapRegisteredSprites.get(location)`（传的是 `ResourceLocation`），永远 miss——重复注册同一位置会创建新的 `TextureAtlasSprite` 实例并覆盖旧 put。这是原版遗留行为；持有旧 sprite 引用的一方在重复注册后拿到的是孤儿对象。
- **`TextureManager.deleteTexture` 不清 map**：`TextureManager.java:125-133` 只删 GL 对象，条目仍在 `mapTextureObjects`；之后 `bindTexture` 同一位置会对已删 id 重新 `getGlTextureId()`（懒生成新 id 但内容为空）。动态纹理生命周期管理要自己小心。
- **加载失败的降级路径不一致**：`TextureManager.loadTexture` IOException → missingTexture；但 `LayeredTexture`/`LayeredColorMaskTexture` 的 `loadTexture` 内部 catch IOException 后直接 return（`LayeredTexture.java:47-51`、`LayeredColorMaskTexture.java:86-90`），既不抛出也不上传——纹理 id 存在但内容未定义。
- **动画 sprite 尺寸假设**：`loadSprite` 中动画图必须是宽度整数倍的竖条（`j / i` 帧），且 `meta == null` 时强制方图（`TextureAtlasSprite.java:282-285`）。sprite 尺寸非 2 的幂会把整个图集的 `mipmapLevels` 拉低（`TextureMap.java:155-161` 的 `Integer.lowestOneBit` 检查）。
- **`Stitcher.doStitch` 失败即崩溃**：`StitcherException` 从 `TextureMap.java:216-221` 原样抛出，最终变成崩溃报告。给图集塞自定义 sprite 时要控制总量与尺寸（上限 `Minecraft.getGLMaximumTextureSize()`，用 `GL_PROXY_TEXTURE_2D` 探测，`Minecraft.java:2941`）。
- **anaglyph 全局副作用**：`gameSettings.anaglyph` 开启时所有上传都要过 `updateAnaglyph` 逐像素变换（`TextureUtil.java:285-288`），上传成本翻倍，且要求 `Minecraft.getMinecraft()` 可用——单元测试里直接调 TextureUtil 上传会 NPE。
- **每 tick 动画上传**：`TextureMap.updateAnimations` 在 tick 循环内做 `glTexSubImage2D`，动画 sprite 数量直接影响 tick 耗时；插值动画（`isInterpolate`）每 tick 都上传而非仅换帧时。
- **`mipmapLevels` 可被运行时下调**：`loadTextureAtlas` 会根据最小 sprite 把 `this.mipmapLevels` 改小（`TextureMap.java:169-173`）且不会恢复——之后 `GameSettings` 再设置时以新值重建。

## 交叉引用

- `net.minecraft.client` → `Minecraft#startGame`（创建 TextureManager/TextureMap，`Minecraft.java:498/548`）、`Minecraft#runTick`（`renderEngine.tick()`，`Minecraft.java:1763`）、`Minecraft#getGLMaximumTextureSize`（`Minecraft.java:2941`，Stitcher 上限）、`Minecraft#drawSplashScreen`（`Minecraft.java:889`）
- `net.minecraft.client.renderer` → `GlStateManager#generateTexture/#deleteTexture/#bindTexture`（TextureUtil 全部转发）、`GLAllocation#createDirectIntBuffer`（dataBuffer）、`StitcherException`（Stitcher 失败）
- `net.minecraft.client.renderer` → `EntityRenderer`（lightmap `DynamicTexture`，`EntityRenderer.java:190-192/1056`）、`ItemRenderer#renderFireInFirstPerson`（`getAtlasSprite("minecraft:blocks/fire_layer_1")`，`ItemRenderer.java:553`）、`BlockFluidRenderer`（流体 UV，`BlockFluidRenderer.java:26`）、`RenderGlobal`（破坏动画 sprite，`RenderGlobal.java:208`）
- `net.minecraft.client.renderer.entity` → `Render#renderEntityOnFire`（火焰 sprite，`Render.java:108`）、`RenderHorse`（`new LayeredTexture`，`RenderHorse.java:95`）
- `net.minecraft.client.renderer.tileentity` → `TileEntityBannerRenderer`（`new LayeredColorMaskTexture`，`TileEntityBannerRenderer.java:133`）、`RenderItemFrame`（`TextureCompass#updateCompass`，`RenderItemFrame.java:143`）
- `net.minecraft.client.resources` → `IResourceManager#getResource`、`IResource#getMetadata`、`IResourceManagerReloadListener`（TextureManager 实现）、`ResourcePackRepository`（`ResourcePackRepository.java:348`）
- `net.minecraft.client.resources.data` → `AnimationMetadataSection`、`AnimationFrame`、`TextureMetadataSection`（mcmeta 驱动）
- `net.minecraft.client.resources.model` → `ModelBakery#loadSprites`（IIconCreator 实现与图集重建入口，`ModelBakery.java:584-601`）、`ModelBakery`（`setLocationNameCompass/Clock`，`ModelBakery.java:624/628`）
- `net.minecraft.client.settings` → `GameSettings#setOptionValue`（mipmap 档位改动，`GameSettings.java:315-317`）
- `net.minecraft.client.gui` → `MapItemRenderer`（`MapItemRenderer.java:81`）、`GuiMainMenu`（`GuiMainMenu.java:197`）、`GuiContainer`（`GuiContainer.java:284`）
- `net.minecraft.util` → `MathHelper#roundUpToPowerOfTwo/#calculateLogBaseTwo/#clamp_double/#func_180188_d`（`MathHelper.java:368`）、`ResourceLocation`、`ReportedException`
- `net.minecraft.crash` → `CrashReport/CrashReportCategory`（loadTexture、generateMipmaps、stitch 的崩溃报告）
- `net.minecraft.block.material` / `net.minecraft.item` → `MapColor`、`EnumDyeColor#getMapColor`（LayeredColorMaskTexture 染色）
- `org.lwjgl.opengl` → `GL11/GL12/GL14` 直接调用（glTexImage2D、glTexSubImage2D、glTexParameteri、LOD 参数）

## 覆盖声明

完整读取了 16/16 个文件（全部逐行读完，无截断）。

- 逐行精读：TextureManager、TextureMap、TextureUtil、TextureAtlasSprite、Stitcher、AbstractTexture、DynamicTexture、SimpleTexture、TextureClock、TextureCompass、LayeredTexture、LayeredColorMaskTexture，以及三个接口 ITextureObject、ITickable、ITickableTextureObject 和 IIconCreator（均为数行的接口）。
- 只做结构性浏览的类：无。
- 另外为核对调用方与行号，定点查阅了 Minecraft.java（498-552、889、1763、2941）、ModelBakery.java（584-628）、EntityRenderer.java（190-192、1056）、GameSettings.java（315-317）、GlStateManager.java、MathHelper.java（368）等外部文件的相关片段，未通读。
