---
area: net/minecraft/client/renderer#1
slug: mc-client-renderer-1
files: 63
lines: 13455
tier: A
---

# net/minecraft/client/renderer #1 — 世界渲染核心

## 定位

这个 bucket 是客户端 3D 渲染的主干：**每帧世界渲染入口（EntityRenderer）**、**地形/实体/TileEntity 全局渲染器（RenderGlobal）**、**区块网格异步编译流水线（chunk/ 子包）**、**顶点缓冲构建（WorldRenderer/Tessellator）**、**GL 状态影子层（GlStateManager/OpenGlHelper）**，以及方块模型/流体/物品的网格化渲染器与一批 TileEntity 特殊渲染器。

- 上游调用者：`Minecraft.runGameLoop` 每帧调用 `entityRenderer.updateCameraAndRender(this.timer.renderPartialTicks, i)`（Minecraft.java:1141）；`Minecraft.runTick` 每 tick 调用 `entityRenderer.updateRenderer()`（Minecraft.java:2183）和 `renderGlobal.updateClouds()`（Minecraft.java:2190）。`RenderGlobal` 同时实现 `IWorldAccess`，`World`/`WorldClient` 的方块变更、音效、粒子事件（多数由 `NetHandlerPlayClient` 处理封包后触发）都会回调进来。
- 下游依赖：`client.renderer.entity.RenderManager`（实体渲染，另一 bucket）、`client.renderer.texture.*`（纹理图集）、`client.renderer.vertex.*`（VertexFormat/VertexBuffer）、`client.shader.*`（后处理 ShaderGroup）、`client.resources.model.*`（烘焙模型）、以及 `world`/`block` 包读取方块状态。
- 如果它消失：游戏窗口只剩 GUI 正交层，没有世界、没有实体、没有手持物品；区块网格无法构建，F3 调试信息（`getDebugInfoRenders`）也没有数据来源。GlStateManager 消失则整个客户端所有 GL 调用点全部失效——它是全工程 GL 状态的唯一出入口。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| RenderGlobal | 2412 | implements IWorldAccess, IResourceManagerReloadListener | 世界级渲染器：天空/云/地形层/实体/TileEntity/破坏动画/世界边界，并接收世界事件回调 |
| EntityRenderer | 2050 | implements IResourceManagerReloadListener | 每帧渲染总入口：相机、FOV、雾、光照贴图、拾取射线、雨雪、后处理 shader、调度 renderWorldPass |
| OpenGlHelper | 925 | (无) | GL 能力探测与 ARB/EXT/core 分发层（FBO、shader、VBO、多纹理、blendFuncSeparate） |
| GlStateManager | 814 | (无) | GL 固定管线状态影子缓存，去重后才真正调 GL11 |
| BlockModelRenderer | 638 | (无) | 把 IBakedModel 的 quad 写入 WorldRenderer，含 AO（环境光遮蔽）与平滑光照计算 |
| ItemRenderer | 631 | (无) | 第一人称手/手持物品渲染与举起动画、着火/水下/方块内屏幕覆盖层 |
| WorldRenderer | 626 | (无) | 顶点缓冲构建器（即后世 BufferBuilder）：pos/tex/color/lightmap/normal 链式写入直接 ByteBuffer |
| chunk/RenderChunk | 389 | (无) | 单个 16³ 渲染区块：持有 VBO、CompiledChunk，负责 rebuildChunk/resortTransparency |
| BlockModelShapes | 314 | (无) | IBlockState → IBakedModel 映射表；注册所有原版方块的 IStateMapper |
| BlockFluidRenderer | 297 | (无) | 水/岩浆流体的专用网格化（动态液面高度、流向 UV） |
| chunk/ChunkRenderDispatcher | 290 | (无) | 区块编译调度：2 个 "Chunk Batcher" 线程、任务队列、上传队列、5 个 RegionRenderCacheBuilder 池 |
| tileentity/RenderItemFrame | 229 | extends Render&lt;EntityItemFrame&gt; | 物品展示框（含地图、指南针特判）渲染 |
| chunk/VisGraph | 208 | (无) | 区块内不透明方块泛洪填充，计算面-面可见性（SetVisibility） |
| chunk/ChunkRenderWorker | 206 | implements Runnable | 编译工作线程主体：取任务→rebuild/resort→提交 upload |
| tileentity/TileEntityChestRenderer | 199 | extends TileEntitySpecialRenderer&lt;TileEntityChest&gt; | 箱子（含大箱、陷阱箱、圣诞皮肤）模型渲染，盖子插值动画 |
| ViewFrustum | 178 | (无) | RenderChunk 三维环形网格（X/Z 环绕、Y 固定 16 层），坐标→RenderChunk 查找与脏标记 |
| BlockRendererDispatcher | 174 | implements IResourceManagerReloadListener | 按 Block.getRenderType() 分发到流体/箱子/模型渲染器 |
| ThreadDownloadImageData | 162 | extends SimpleTexture | 异步 HTTP 下载皮肤纹理（"Texture Downloader #N" 守护线程），主线程惰性上传 GL |
| tileentity/TileEntityBannerRenderer | 151 | extends TileEntitySpecialRenderer&lt;TileEntityBanner&gt; | 旗帜渲染；按图案组合动态生成 LayeredColorMaskTexture 并做 256 项超时缓存 |
| chunk/ChunkCompileTaskGenerator | 141 | (无) | 一次区块编译任务的状态机（PENDING/COMPILING/UPLOADING/DONE）+ finish 回调 |
| ActiveRenderInfo | 140 | (无) | 静态相机信息：gluUnProject 求视点世界坐标、视角旋转分量、视点所在方块 |
| tileentity/TileEntityBeaconRenderer | 131 | extends TileEntitySpecialRenderer&lt;TileEntityBeacon&gt; | 信标光柱（内实心+外半透明两层）渲染，forceTileEntityRender()=true |
| tileentity/TileEntityEndPortalRenderer | 127 | extends TileEntitySpecialRenderer&lt;TileEntityEndPortal&gt; | 末地传送门 16 层 texgen 星空视差效果 |
| ImageBufferDownload | 116 | implements IImageBuffer | 把 32 高旧版皮肤转换为 64×64 新版布局并修正透明区 |
| InventoryEffectRenderer | 113 | extends GuiContainer | 带药水效果侧栏的容器 GUI 基类（背包/创造模式界面的父类） |
| culling/ClippingHelperImpl | 99 | extends ClippingHelper | 从当前 GL 矩阵提取 6 个视锥平面（单例） |
| ItemModelMesher | 98 | (无) | Item+meta → IBakedModel 注册与缓存；支持 ItemMeshDefinition 动态映射 |
| chunk/CompiledChunk | 89 | (无) | 区块编译产物：各层使用/启动标记、TileEntity 列表、SetVisibility、半透明层顶点快照 State |
| WorldVertexBufferUploader | 88 | (无) | 立即模式上传：按 VertexFormat 设置 client array 指针并 glDrawArrays |
| RegionRenderCache | 86 | extends ChunkCache | 区块编译线程用的世界快照缓存（blockState/combinedLight 各 8000 项数组缓存） |
| tileentity/TileEntityItemStackRenderer | 84 | (无) | 物品形式的箱子/旗帜/头颅等 builtin 方块的 TESR 渲染入口（含 SkullOwner NBT 解析） |
| block/statemap/StateMap | 83 | extends StateMapperBase | 可配置的状态→模型资源名映射（withName/withSuffix/ignore） |
| tileentity/TileEntityEnderChestRenderer | 82 | extends TileEntitySpecialRenderer&lt;TileEntityEnderChest&gt; | 末影箱渲染，盖子动画同箱子 |
| RenderHelper | 80 | (无) | 物品渲染用双光源固定管线光照的开关 |
| chunk/SetVisibility | 78 | (无) | 6×6 面对面可见性 BitSet |
| GLAllocation | 72 | (无) | display list 分配/释放与 native 序直接缓冲创建 |
| DestroyBlockProgress | 69 | (无) | 单个玩家的方块破坏进度（0–10）与创建 tick |
| tileentity/TileEntityEnchantmentTableRenderer | 68 | extends TileEntitySpecialRenderer&lt;TileEntityEnchantmentTable&gt; | 附魔台悬浮书本动画渲染 |
| tileentity/RenderWitherSkull | 67 | extends Render&lt;EntityWitherSkull&gt; | 凋灵头颅弹射物渲染 |
| EnumFaceDirection | 62 | enum | 六个面 4 顶点的 x/y/z 索引查表（模型烘焙用） |
| block/statemap/StateMapperBase | 52 | implements IStateMapper | 状态映射基类：遍历 validStates 生成 property 字符串 |
| tileentity/RenderEnderCrystal | 46 | extends Render&lt;EntityEnderCrystal&gt; | 末影水晶实体渲染 |
| culling/Frustum | 44 | implements ICamera | 带世界坐标偏移的视锥测试包装 |
| block/statemap/BlockStateMapper | 42 | (无) | Block→IStateMapper 注册表；builtin 方块跳过模型生成 |
| VboRenderList | 41 | extends ChunkRenderContainer | VBO 路径的区块层绘制（bindBuffer + 固定 stride 28 指针） |
| tileentity/TileEntityMobSpawnerRenderer | 38 | extends TileEntitySpecialRenderer&lt;TileEntityMobSpawner&gt; | 刷怪笼内旋转小怪渲染 |
| ChunkRenderContainer | 38 | abstract | 每帧待绘制 RenderChunk 列表 + 相机相对平移 |
| Tessellator | 34 | (无) | 静态单例（2MB 缓冲）：WorldRenderer + 立即上传器的组合 |
| culling/ClippingHelper | 32 | (无) | 6 平面 AABB 视锥测试的数据与算法 |
| chunk/ListedRenderChunk | 28 | extends RenderChunk | display list 路径的 RenderChunk（每层一个 list） |
| RenderList | 27 | extends ChunkRenderContainer | display list 路径的区块层绘制（glCallList） |
| RegionRenderCacheBuilder | 26 | (无) | 4 层各一个 WorldRenderer 的缓冲组（SOLID 2MB/CUTOUT 128KB×2/TRANSLUCENT 256KB） |
| VertexBufferUploader | 19 | extends WorldVertexBufferUploader | VBO 路径上传：把 WorldRenderer 字节流 bufferData 进 VertexBuffer |
| ChestRenderer | 15 | (无) | renderType==2 方块（箱子类）的亮度渲染入口 |
| block/statemap/DefaultStateMapper | 14 | extends StateMapperBase | 默认映射：注册名 + 全 property 字符串 |
| StitcherException | 14 | extends RuntimeException | 纹理拼接失败异常（携带 Stitcher.Holder） |
| culling/ICamera | 13 | interface | 视锥测试接口（isBoundingBoxInFrustum/setPosition） |
| chunk/VboChunkFactory | 13 | implements IRenderChunkFactory | 创建 RenderChunk（VBO 路径） |
| chunk/ListChunkFactory | 13 | implements IRenderChunkFactory | 创建 ListedRenderChunk（display list 路径） |
| block/statemap/IStateMapper | 11 | interface | putStateModelLocations(Block) 契约 |
| chunk/IRenderChunkFactory | 10 | interface | makeRenderChunk 工厂契约 |
| IImageBuffer | 10 | interface | 皮肤图像后处理回调（parseUserSkin/skinAvailable） |
| ItemMeshDefinition | 9 | interface | ItemStack → ModelResourceLocation 动态映射 |

## 核心类详解

### EntityRenderer（每帧总入口）

关键字段：`private Minecraft mc`、`public final ItemRenderer itemRenderer`（EntityRenderer.java:84）、`private final DynamicTexture lightmapTexture`（16×16，:136）、`private final int[] lightmapColors`（:141）、`private float fovModifierHand / fovModifierHandPrev`（:115-118）、`private ShaderGroup theShaderGroup`（:174）、`public static boolean anaglyphEnable`（:74）、`private int frameCount`（:179）、`private Entity pointedEntity`（:91）。

关键方法（签名逐字）：

- `public void updateCameraAndRender(float partialTicks, long nanoTime)` — EntityRenderer.java:1069。每帧由 `Minecraft.runGameLoop` 调用（Minecraft.java:1141）。处理失焦暂停、鼠标视角转动（`this.mc.thePlayer.setAngles(f2, f3 * (float)i)`，:1116/:1122）、调用 `renderWorld`、后处理 shader、`ingameGUI.renderGameOverlay(partialTicks)`（:1169）、`currentScreen.drawScreen(k1, l1, partialTicks)`（:1191，带崩溃报告包裹）。
- `public void renderWorld(float partialTicks, long finishTimeNano)` — :1290。updateLightmap → getMouseOver → 按 anaglyph 决定 1 或 2 次 `renderWorldPass`。
- `private void renderWorldPass(int pass, float partialTicks, long finishTimeNano)` — :1323。完整一帧 3D 顺序：clear → `setupCameraTransform` → `ActiveRenderInfo.updateRenderInfo` → `ClippingHelperImpl.getInstance()` → 新建 `Frustum` 并 `setPosition` → 天空(`renderglobal.renderSky(partialTicks, pass)`，:1354) → 云（视点 y<128 先画，:1364-1367）→ `renderglobal.setupTerrain(entity, (double)partialTicks, icamera, this.frameCount++, this.mc.thePlayer.isSpectator())`（:1374）→ `updateChunks(finishTimeNano)`（:1379）→ SOLID/CUTOUT_MIPPED/CUTOUT 三层（:1386-1390）→ `renderglobal.renderEntities(entity, icamera, partialTicks)`（:1402）→ 选择框 `drawSelectionBox`（:1414/:1427）→ 破坏纹理 `drawBlockDamageTexture`（:1435）→ 粒子（:1443/:1447）→ 雨雪 `renderRainSnow`（:1454）→ 世界边界（:1456）→ TRANSLUCENT 层（:1467）→ 高空云（:1477）→ `renderHand(partialTicks, pass)`（:1485）。
- `public void getMouseOver(float partialTicks)` — :409。写 `this.mc.objectMouseOver` 与 `this.mc.pointedEntity`；方块拾取用 `entity.rayTrace(d0, partialTicks)`（:420），实体拾取遍历 `getEntitiesInAABBexcluding`，距离基准 `this.mc.playerController.getBlockReachDistance()`，`extendedReach()` 时 6.0（:428）。这是攻击/交互目标的唯一来源。
- `private void orientCamera(float partialTicks)` — :634。睡觉、第三人称（4.0 距离 + 8 点射线避墙，:685-704）、`debugCamEnable` 分支；末尾计算 `this.cloudFog = this.mc.renderGlobal.hasCloudFog(...)`（:742）。
- `private void setupCameraTransform(float partialTicks, int pass)` — :748。`Project.gluPerspective(this.getFOVModifier(partialTicks, true), (float)this.mc.displayWidth / (float)this.mc.displayHeight, 0.05F, this.farPlaneDistance * MathHelper.SQRT_2)`（:766）；hurt 抖动、viewBobbing、传送门扭曲（:782-798）。
- `private float getFOVModifier(float partialTicks, boolean useFOVSetting)` — :551。基础 `f = this.mc.gameSettings.fovSetting` 乘 fovModifierHand 插值；死亡缩放（:568-572）；水下 `f * 60.0F / 70.0F`（:576-579）。
- `private void updateLightmap(float partialTicks)` — :922。填充 256 项 `lightmapColors`，含火把闪烁、末地固定色（:963）、夜视（:970-988）、gamma（:1005-1014），最后 `this.lightmapTexture.updateDynamicTexture()`（:1056）。
- `public void enableLightmap()` / `public void disableLightmap()` — :892/:885。切到 `OpenGlHelper.lightmapTexUnit` 绑定光照贴图。
- `private void setupFog(int startCoords, float partialTicks)` — :1936。失明/云雾/水下/岩浆/普通线性雾分支；`GLContext.getCapabilities().GL_NV_fog_distance` 时 `GL11.glFogi(34138, 34139)`（:1974-1977）。
- `private void updateFogColor(float partialTicks)` — :1763。天空色/雨/雷/水/岩浆/虚空/boss/夜视各种叠加，最后 `GlStateManager.clearColor(...)`（:1927）。
- `public void updateRenderer()` — :327。每 tick：`updateFovModifierHand`、`updateTorchFlicker`、平滑相机滤波、`++this.rendererUpdateCount`、`this.itemRenderer.updateEquippedItem()`（:367）、`addRainParticles`、boss 颜色渐变。
- shader 相关：`public void loadEntityShader(Entity entityIn)`（:232，旁观爬行者/蜘蛛/末影人自动加载 creeper/spider/invert.json）、`public void activateNextShader()`（:258，由 GuiOptions "Super Secret Settings..." 按钮触发（GuiOptions.java:164），循环 24 个 `shaderResourceLocations`，:175）、`public void switchUseShader()`（:224，F4 触发，Minecraft.java:1930-1933）。
- `public void setupOverlayRendering()` — :1748。GUI 正交投影（`GlStateManager.ortho(0.0D, scaledresolution.getScaledWidth_double(), scaledresolution.getScaledHeight_double(), 0.0D, 1000.0D, 3000.0D)`）。

### RenderGlobal（世界渲染器 + IWorldAccess）

关键字段：`private Set<RenderChunk> chunksToUpdate = Sets.<RenderChunk>newLinkedHashSet()`（RenderGlobal.java:105）、`private List<RenderGlobal.ContainerLocalRenderInformation> renderInfos`（:106，容量 69696）、`private final Set<TileEntity> setTileEntities`（:107，同步访问）、`private ViewFrustum viewFrustum`（:108）、`private final ChunkRenderDispatcher renderDispatcher = new ChunkRenderDispatcher()`（:145）、`private ChunkRenderContainer renderContainer`（:146）、`private boolean vboEnabled`（:164）、`IRenderChunkFactory renderChunkFactory`（:165）、`private final Map<Integer, DestroyBlockProgress> damagedBlocks`（:127）、`private Framebuffer entityOutlineFramebuffer` / `private ShaderGroup entityOutlineShader`（:130/:133）、`private boolean displayListEntitiesDirty`（:169）。

关键方法：

- `public RenderGlobal(Minecraft mcIn)` — :171。按 `OpenGlHelper.useVbo()` 选择 `VboRenderList`+`VboChunkFactory` 或 `RenderList`+`ListChunkFactory`（:183-192），生成星空/天空几何。
- `public void setWorldAndLoadRenderers(WorldClient worldClientIn)` — :456。换世界时移除/注册 IWorldAccess 并 `loadRenderers()`。
- `public void loadRenderers()` — :482。渲染距离或 VBO 开关变化时重建 `ViewFrustum`（:523）、`stopChunkUpdates()`、清空 setTileEntities、`renderEntitiesStartupCounter = 2`（:535）。
- `public void setupTerrain(Entity viewEntity, double partialTicks, ICamera camera, int frameCount, boolean playerSpectator)` — :774。相机移动超过 16 块距离时 `viewFrustum.updateChunkPositions`（:786-795）；`displayListEntitiesDirty` 时做 BFS 可见性遍历：从视点 RenderChunk 出发按 `renderchunk3.getCompiledChunk().isVisible(enumfacing2.getOpposite(), enumfacing1)` + `camera.isBoundingBoxInFrustum(renderchunk2.boundingBox)` 扩散（:882-902），产出 `renderInfos`；末尾把需要更新的区块分为 `updateChunkNow`（距视点 ≤16 块，"build near"，:926）与 `chunksToUpdate` 延迟队列（:932）。
- `public int renderBlockLayer(EnumWorldBlockLayer blockLayerIn, double partialTicks, int pass, Entity entityIn)` — :1023。TRANSLUCENT 层：相机移动 >1 块时对最近 15 个区块 `renderDispatcher.updateTransparencyLater`（:1034-1047）；随后按层正序/半透明逆序把非空区块塞进 `renderContainer` 并调私有 `renderBlockLayer(blockLayerIn)`（:1078，开关 lightmap 与 VBO client state）。
- `public void renderEntities(Entity renderViewEntity, ICamera camera, float partialTicks)` — :556。启动计数器未归零直接跳过（:558-561）；顺序：weatherEffects → 旁观者轮廓 FBO（`isRenderEntityOutlines()`，:267：需 spectator + `keyBindSpectatorOutlines.isKeyDown()`）→ 按 `renderInfos` 遍历区块内实体，判定 `this.renderManager.shouldRender(entity2, camera, d0, d1, d2) || entity2.riddenByEntity == this.mc.thePlayer`（:659）→ `this.renderManager.renderEntitySimple(entity2, partialTicks)`（:671）→ TileEntity：先渲染各 CompiledChunk 的列表（:687-698），再渲染全局 `setTileEntities`（:700-706），最后破坏中的箱子/告示牌/头颅带 destroyStage 重渲（:710-737）。
- `public void updateChunks(long finishTimeNano)` — :1650。先 `runChunkUploads`，再在时间预算内把 `chunksToUpdate` 逐个 `updateChunkLater`。
- 天空/云：`public void renderSky(float partialTicks, int pass)`（:1203，末地走 `renderSkyEnd()` :1148；主世界画天空穹、日月、星星 VBO/display list、地平线下黑幕）；`public void renderClouds(float partialTicks, int pass)`（:1420，fancy 时 `renderCloudsFancy` :1494）；`public boolean hasCloudFog(double x, double y, double z, float partialTicks)`（:1489，恒 false）。
- `public void renderWorldBorder(Entity entityIn, float partialTicks)` — :1679。forcefield 纹理滚动四面墙。
- 破坏进度：`public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress)`（:2362，progress 0–9 建/更新 `DestroyBlockProgress`，否则移除）；`public void drawBlockDamageTexture(Tessellator tessellatorIn, WorldRenderer worldRendererIn, Entity entityIn, float partialTicks)`（:1819，用 `destroyBlockIcons`[10] 即 `minecraft:blocks/destroy_stage_0..9`，:206-214）。
- 选择框：`public void drawSelectionBox(EntityPlayer player, MovingObjectPosition movingObjectPositionIn, int execute, float partialTicks)`（:1875）；静态工具 `public static void drawSelectionBoundingBox(AxisAlignedBB boundingBox)`（:1904）与 `public static void drawOutlinedBoundingBox(AxisAlignedBB boundingBox, int red, int green, int blue, int alpha)`（:1934）。
- IWorldAccess 回调：`public void markBlockForUpdate(BlockPos pos)`（:1972，±1 扩散到 `viewFrustum.markBlocksForUpdate`）、`public void notifyLightSet(BlockPos pos)`（:1980）、`public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2)`（:1992）、`public void playRecord(String recordName, BlockPos blockPosIn)`（:1997）、`public void broadcastSound(int soundID, BlockPos pos, int data)`（:2123，1013 凋灵/1018 末影龙）、`public void playAuxSFX(EntityPlayer player, int sfxType, BlockPos blockPosIn, int data)`（:2160，见"数据与协议"）、`public void spawnParticle(int particleID, boolean ignoreRange, final double xCoord, final double yCoord, final double zCoord, double xOffset, double yOffset, double zOffset, int... parameters)`（:2036）、`public void onEntityAdded(Entity entityIn)` / `onEntityRemoved`（:2104/:2112，空实现）。
- 调试：`public String getDebugInfoRenders()`（:748）、`public String getDebugInfoEntities()`（:769）。
- `public void updateTileEntities(Collection<TileEntity> tileEntitiesToRemove, Collection<TileEntity> tileEntitiesToAdd)` — :2388，被 RenderChunk.rebuildChunk 从工作线程调用（synchronized on setTileEntities）。
- 内部类 `ContainerLocalRenderInformation`（:2397）：`final RenderChunk renderChunk; final EnumFacing facing; final Set<EnumFacing> setFacing; final int counter;`。

### 区块编译流水线（chunk/ 子包）

**ChunkRenderDispatcher**（ChunkRenderDispatcher.java）：
- 字段：`queueChunkUpdates`（ArrayBlockingQueue 容量 100，:31）、`queueFreeRenderBuilders`（容量 5，:32）、`queueChunkUploads`（ArrayDeque + synchronized，:35）、`listThreadedWorkers`、`renderWorker`（主线程同步用，自带 builder，:53）。构造器起 2 个 `threadFactory`（"Chunk Batcher %d"，daemon）线程（:40-46）。
- `public boolean runChunkUploads(long p_178516_1_)`（:61）：主线程在时间预算内执行上传任务。
- `public boolean updateChunkLater(RenderChunk chunkRenderer)`（:95）/ `public boolean updateChunkNow(RenderChunk chunkRenderer)`（:127）/ `public boolean updateTransparencyLater(RenderChunk chunkRenderer)`（:196）。
- `public ListenableFuture<Object> uploadChunk(final EnumWorldBlockLayer player, final WorldRenderer p_178503_2_, final RenderChunk chunkRenderer, final CompiledChunk compiledChunkIn)`（:228）：`Minecraft.getMinecraft().isCallingFromMinecraftThread()` 则直接 `uploadVertexBuffer`/`uploadDisplayList`，否则包成 ListenableFutureTask 入 `queueChunkUploads` 等主线程执行。
- `public void stopChunkUpdates()`（:155）：清任务、跑完上传、把 5 个 builder 全部收回（阻塞 take）。

**ChunkRenderWorker**：`protected void processTask(final ChunkCompileTaskGenerator generator) throws InterruptedException`（ChunkRenderWorker.java:57）。状态机 PENDING→COMPILING→UPLOADING→DONE；COMPILING 阶段按 Type 调 `generator.getRenderChunk().rebuildChunk(f, f1, f2, generator)` 或 `resortTransparency`（:94-101）；上传 future 回调里 `generator.getRenderChunk().setCompiledChunk(lvt_7_1_)`（:179）；worker 崩溃直接 `Minecraft.getMinecraft().crashed(...)`（:51）。回调 executor 为 `com.google.common.util.concurrent.MoreExecutors.directExecutor()`（:190）。

**RenderChunk**：
- `public void rebuildChunk(float x, float y, float z, ChunkCompileTaskGenerator generator)`（RenderChunk.java:115）：在工作线程建 `new RegionRenderCache(this.world, blockpos.add(-1, -1, -1), blockpos1.add(1, 1, 1), 1)`（:131）；遍历 16³ 方块：不透明的进 VisGraph（:153-156）、有 TileEntity 且有 special renderer 的进 CompiledChunk（:158-172）、`block.getRenderType() != -1` 的按 `block.getBlockLayer()` 写入对应层 WorldRenderer（:174-188，首次写该层前 `preRenderBlocks` :297：`begin(7, DefaultVertexFormats.BLOCK)` + `setTranslation(-pos)`）；结束时 `compiledchunk.setVisibility(lvt_10_1_.computeVisibility())`（:205）并 diff 全局 TileEntity 集合（:206-221）。
- `public void resortTransparency(float x, float y, float z, ChunkCompileTaskGenerator generator)`（:103）：用 `compiledchunk.getState()` 恢复顶点再 `sortVertexData`。
- `public void setPosition(BlockPos pos)`（:89）：更新 boundingBox、六向偏移表、`initModelviewMatrix()`（:314，1.000001 微缩放矩阵）。
- 锁：`lockCompileTask` / `lockCompiledChunk` 两把 ReentrantLock（:39-40）；`public boolean setFrameIndex(int frameIndexIn)`（:71）防止 BFS 同帧重访。
- `public void deleteGlResources()`（:356）删除 4 层 VertexBuffer。

**ViewFrustum**：`protected void setCountChunksXYZ(int renderDistanceChunks)`（ViewFrustum.java:54）：`countChunksX/Z = renderDistanceChunks * 2 + 1`，`countChunksY = 16`；`public void updateChunkPositions(double viewEntityX, double viewEntityZ)`（:62）环形复用 RenderChunk；`public void markBlocksForUpdate(...)`（:104）按 16 桶取模置脏；`protected RenderChunk getRenderChunk(BlockPos pos)`（:148）。

**VisGraph/SetVisibility**：`public SetVisibility computeVisibility()`（VisGraph.java:37）：非透明数 `4096 - this.field_178611_f < 256` 时全可见（:41），全满时全不可见；否则从 1352 个边界格出发泛洪（静态表 :188-207）。`SetVisibility.isVisible(EnumFacing facing, EnumFacing facing2)`（SetVisibility.java:39）为对称 BitSet。

**CompiledChunk**：`public static final CompiledChunk DUMMY`（CompiledChunk.java:12，setLayer* 抛 UnsupportedOperationException、isVisible 恒 false）；`isLayerEmpty`/`isLayerStarted`/`getTileEntities`/`setState`。

**RegionRenderCache**：`public TileEntity getTileEntity(BlockPos pos)`（RegionRenderCache.java:30）用 `Chunk.EnumCreateEntityType.QUEUED`（不在工作线程创建 TileEntity）；`getCombinedLight`/`getBlockState` 各有 8000 项一维缓存，索引 `i * 400 + k * 20 + j`（:84）。

### WorldRenderer / Tessellator / 上传器

- `public void begin(int glMode, VertexFormat format)`（WorldRenderer.java:179）：重复 begin 抛 `IllegalStateException("Already building!")`；`public void finishDrawing()`（:550）：未 begin 抛 `"Not building!"`。
- 链式写入：`public WorldRenderer pos(double x, double y, double z)`（:443）、`public WorldRenderer tex(double u, double v)`（:197）、`public WorldRenderer color(int red, int green, int blue, int alpha)`（:371，受 `noColor` 拦截）、`public WorldRenderer lightmap(int p_181671_1_, int p_181671_2_)`（:230）、`public WorldRenderer normal(float p_181663_1_, float p_181663_2_, float p_181663_3_)`（:506）、`public void endVertex()`（:437，自动 `growBuffer`）。
- 批量/回填：`public void addVertexData(int[] vertexData)`（:429）、`public void putBrightness4(int p_178962_1_, int p_178962_2_, int p_178962_3_, int p_178962_4_)`（:263）、`public void putPosition(double x, double y, double z)`（:273）、`public void putColorMultiplier(float red, float green, float blue, int p_178978_4_)`（:297）、`public void putNormal(float x, float y, float z)`（:480）——这些都是对"最后 4 个顶点"的原地修改，是 BlockModelRenderer 的配套 API。
- 半透明排序：`public void sortVertexData(float p_181674_1_, float p_181674_2_, float p_181674_3_)`（:66，按 quad 中心距离降序原地重排）；`public WorldRenderer.State getVertexState()` / `public void setVertexState(WorldRenderer.State state)`（:126/:163）。
- `private void growBuffer(int p_181670_1_)`（:44）按 2097152 字节步长扩容并 warn 日志。
- `Tessellator`：`private static final Tessellator instance = new Tessellator(2097152)`（Tessellator.java:9）；`public void draw()`（:24）= finishDrawing + `WorldVertexBufferUploader.draw`。
- `WorldVertexBufferUploader.draw(WorldRenderer p_181679_1_)`（WorldVertexBufferUploader.java:12）：按 VertexFormat 元素设置 glVertexPointer/glTexCoordPointer/glColorPointer/glNormalPointer + `GL11.glDrawArrays(p_181679_1_.getDrawMode(), 0, p_181679_1_.getVertexCount())`（:54），结束后 `p_181679_1_.reset()`。
- `VertexBufferUploader.draw`（VertexBufferUploader.java:9）：仅 `this.vertexBuffer.bufferData(p_181679_1_.getByteBuffer())`。
- `VboRenderList.setupArrayPointers()`（VboRenderList.java:32）：stride 28 的 BLOCK 格式指针（pos float×3 @0、color ubyte×4 @12、tex float×2 @16、lightmap short×2 @24）。

### GlStateManager / OpenGlHelper / GLAllocation / RenderHelper

- GlStateManager 全静态：每类状态一个影子对象（`BooleanState`、`BlendState`、`DepthState`、`FogState`、`TextureState[8]`、`ColorMask`、`Color` 等，GlStateManager.java:8-27），值不变则不发 GL 调用。代表性方法：`public static void tryBlendFuncSeparate(int srcFactor, int dstFactor, int srcFactorAlpha, int dstFactorAlpha)`（:147）、`public static void bindTexture(int texture)`（:347）、`public static void setActiveTexture(int texture)`（:310，索引 = `texture - OpenGlHelper.defaultTexUnit`）、`public static void color(float colorRed, float colorGreen, float colorBlue, float colorAlpha)`（:488）与 `public static void resetColor()`（:505，置 -1 强制下次生效）、`public static void deleteTexture(int texture)`（:334，同时清各 unit 的影子 textureName）。矩阵/视口类不做缓存直通 GL11（:428-486）。LWJGL3 命名：`GL11.glGetFloatv(pname, params)`（:450）、`GL11.glMultMatrixf(matrix)`（:485）。
- OpenGlHelper：`public static void initializeTextures()`（OpenGlHelper.java:97）在 GL 上下文建立后由 `Minecraft` 调用（Minecraft.java:489），探测 ARB multitexture/texture_env_combine、FBO 三种路径（`framebufferType` 0=GL30/1=ARB/2=EXT，:169-215）、shader（`arbShaders`，:230-250）、VBO（`arbVbo`/`vboSupported`，:262-263）、厂商标记 `nvidia`/`ati`（:261/:282），并用 oshi 取 CPU 字符串（:296-305）。`public static boolean useVbo()`（:629）= `vboSupported && Minecraft.getMinecraft().gameSettings.useVbo`。`public static void glShaderSource(int shaderIn, ByteBuffer string)`（:354）里用 `MemoryUtil.memUTF8(string)`（LWJGL3 API）。`public static void glBlendFunc(int sFactorRGB, int dFactorRGB, int sfactorAlpha, int dfactorAlpha)`（:897）。`public static int defaultTexUnit` / `public static int lightmapTexUnit`（:54/:60，33984/33985）。
- GLAllocation：`public static synchronized int generateDisplayLists(int range)`（GLAllocation.java:16，返回 0 时抛 IllegalStateException 带 gluErrorString）；`createDirectByteBuffer/IntBuffer/FloatBuffer`（:51-71，native order）。
- RenderHelper：`public static void enableStandardItemLighting()`（RenderHelper.java:28，GL_LIGHT0/1 双方向光 + colorMaterial）、`public static void disableStandardItemLighting()`（:17）、`public static void enableGUIStandardItemLighting()`（:72）。

### 方块网格化（BlockRendererDispatcher / BlockModelRenderer / BlockFluidRenderer / BlockModelShapes）

- `public boolean renderBlock(IBlockState state, BlockPos pos, IBlockAccess blockAccess, WorldRenderer worldRendererIn)`（BlockRendererDispatcher.java:53）：renderType -1 不渲染、1 流体、2 箱子类（世界内不画，返回 false）、3 模型；异常包 CrashReport。`public void renderBlockDamage(IBlockState state, BlockPos pos, TextureAtlasSprite texture, IBlockAccess blockAccess)`（:39，用 `SimpleBakedModel.Builder` 换贴图重烘）。`public void renderBlockBrightness(IBlockState state, float brightness)`（:134，GUI/手持路径）。`getModelFromBlockState`（:108）会应用 `getActualState` 与 `allowBlockAlternatives` 的 `WeightedBakedModel` 替换。
- BlockModelRenderer：`public boolean renderModel(IBlockAccess blockAccessIn, IBakedModel modelIn, IBlockState blockStateIn, BlockPos blockPosIn, WorldRenderer worldRendererIn, boolean checkSides)`（BlockModelRenderer.java:30）：`Minecraft.isAmbientOcclusionEnabled() && blockStateIn.getBlock().getLightValue() == 0 && modelIn.isAmbientOcclusion()` 走 AO 路径（:32-37）。AO 路径 `renderModelAmbientOcclusionQuads`（:116）配合内部类 `AmbientOcclusionFace.updateVertexBrightness(...)`（:364）做 4 顶点亮度/颜色乘子；标准路径 `renderModelStandardQuads`（:245）。`public void renderModelBrightness(IBakedModel model, IBlockState p_178266_2_, float brightness, boolean p_178266_4_)`（:310）与 `renderModelBrightnessColor`（:300）是手持/展示框用的立即模式路径（每 quad 一次 `tessellator.draw()`，:355）。`EntityRenderer.anaglyphEnable` 在此参与颜色变换（:146/:282/:317）。
- BlockFluidRenderer：`public boolean renderFluid(IBlockAccess blockAccess, IBlockState blockStateIn, BlockPos blockPosIn, WorldRenderer worldRendererIn)`（BlockFluidRenderer.java:33）：顶面按 `BlockLiquid.getFlowDirection` 旋转 UV（:71-115），四侧面双面各 8 顶点（:240-247）；`private float getFluidHeight(IBlockAccess blockAccess, BlockPos blockPosIn, Material blockMaterial)`（:255）对 2×2 邻域求平均液面。贴图在 `protected void initAtlasSprites()`（:24）取 `lava_still/lava_flow/water_still/water_flow`。
- BlockModelShapes：`public IBakedModel getModelForState(IBlockState state)`（BlockModelShapes.java:122，miss 回退 missingModel）；`public TextureAtlasSprite getTexture(IBlockState state)`（:76，对 builtin 方块给 planks_oak/obsidian/lava_still/water_still/soul_sand/barrier 兜底粒子贴图）；`public void reloadModels()`（:139，资源重载时由 ModelManager 侧触发重建 identity map）；`private void registerAllBlocks()`（:159）注册 16 个 builtin 方块与全部原版 IStateMapper（例：`this.registerBlockWithStateMapper(Blocks.leaves, (new StateMap.Builder()).withName(BlockOldLeaf.VARIANT).withSuffix("_leaves").ignore(new IProperty[] {BlockLeaves.CHECK_DECAY, BlockLeaves.DECAYABLE}).build())`，:164）。
- statemap 子包：`StateMapperBase.getPropertyString(Map<IProperty, Comparable> p_178131_1_)`（StateMapperBase.java:15，空时 "normal"）；`BlockStateMapper.putAllStateModelLocations()`（BlockStateMapper.java:28，builtin 跳过、无映射用 `new DefaultStateMapper()`）。

### ItemRenderer（第一人称）

- `public void renderItemInFirstPerson(float partialTicks)`（ItemRenderer.java:355）：equipProgress 插值 `float f = 1.0F - (this.prevEquippedProgress + (this.equippedProgress - this.prevEquippedProgress) * partialTicks)`（:357）；地图专用 `renderItemMap`（:180）；使用中物品按 `EnumAction`（NONE/EAT/DRINK/BLOCK/BOW）套 `transformFirstPersonItem(float equipProgress, float swingProgress)`（:296）、`doBlockTransformations()`（:344）、`doBowTransformations(float partialTicks, AbstractClientPlayer clientPlayer)`（:314）；空手 `renderPlayerArm`（:229）。最终 `this.renderItem(abstractclientplayer, this.itemToRender, ItemCameraTransforms.TransformType.FIRST_PERSON)`（:406）→ `RenderItem.renderItemModelForEntity`（:75）。
- `public void renderOverlays(float partialTicks)`（:421）：头在方块内画 `renderBlockInHand`（:472）、水下 `renderWaterOverlayTexture`（:505）、着火 `renderFireInFirstPerson`（:539）；旁观者跳过后两者（:450）。
- `public void updateEquippedItem()`（:581）：每 tick 由 `EntityRenderer.updateRenderer` 调用；物品变化时 equippedProgress 向 0 收敛，`< 0.1F` 时切换 `this.itemToRender = itemstack`（:609-613）。`public void resetEquippedProgress()` / `resetEquippedProgress2()`（:619/:627）。
- 调用时机：`EntityRenderer.renderHand`（EntityRenderer.java:831）在 `thirdPersonView == 0 && !hideGUI && !isSpectator()` 时调 `renderItemInFirstPerson`（:863-868），之后调 `renderOverlays`（:874）。

### TileEntity/实体特殊渲染器（tileentity/ 子包）

统一签名 `public void renderTileEntityAt(T te, double x, double y, double z, float partialTicks, int destroyStage)`，由 `TileEntityRendererDispatcher.instance.renderTileEntity(tileentity, partialTicks, -1)`（RenderGlobal.java:695/:704）或破坏中 `destroyStage>=0`（:735）调用：

- TileEntityChestRenderer（TileEntityChestRenderer.java:34）：单/大箱模型选择、metadata 旋转（2→180/3→0/4→90/5→-90，:129-147）、盖角 `f = 1.0F - f; f = 1.0F - f * f * f;`（:183-184）；destroyStage>=0 时纹理矩阵缩放贴 destroy_stage。
- TileEntityEnderChestRenderer（:13）同箱子的单箱版本。
- TileEntityBannerRenderer（:25）：`func_178463_a(TileEntityBanner bannerObj)`（:85）按 `getPatternResourceLocation()` 缓存 `LayeredColorMaskTexture`，容量 256、60000ms LRU 淘汰（:99-120）。
- TileEntityBeaconRenderer（:17）：光柱两段绘制；`public boolean forceTileEntityRender()`（:127）返回 true → rebuildChunk 时被加入 RenderGlobal 全局 setTileEntities（RenderChunk.java:167-170）。
- TileEntityEndPortalRenderer（:22）：16 层 texgen 平面 + `ActiveRenderInfo.getPosition()` 视差。
- TileEntityEnchantmentTableRenderer（:16）：ModelBook 翻页/旋转插值。
- TileEntityMobSpawnerRenderer（:11）与静态 `public static void renderMob(MobSpawnerBaseLogic mobSpawnerLogic, double posX, double posY, double posZ, float partialTicks)`（:22）。
- TileEntityItemStackRenderer（:18）：`public void renderByItem(ItemStack itemStackIn)`（:27）分发 banner/skull/ender_chest/trapped_chest/普通 chest；skull 分支解析 NBT `SkullOwner`（compound 或 string，:38-53）。
- 实体渲染器（放在本包但属 Render 体系）：RenderItemFrame.`doRender(EntityItemFrame entity, double x, double y, double z, float entityYaw, float partialTicks)`（RenderItemFrame.java:50，展示框模型 + 内容物；地图/指南针特判 :105-151；`renderName` :177 悬浮名）；RenderWitherSkull.`doRender`（RenderWitherSkull.java:43）；RenderEnderCrystal.`doRender`（RenderEnderCrystal.java:26）。

### 相机与裁剪（ActiveRenderInfo / culling/）

- `public static void updateRenderInfo(EntityPlayer entityplayerIn, boolean p_74583_1_)`（ActiveRenderInfo.java:54）：抓当前 GL 矩阵 `GLU.gluUnProject` 反求视点相对坐标存 `position`，并算 rotationX/XZ/Z/YZ/XY（Billboard 粒子用）。每 pass 由 EntityRenderer.renderWorldPass 调用（EntityRenderer.java:1335）。
- `public static Block getBlockAtEntityViewpoint(World worldIn, Entity p_180786_1_, float p_180786_2_)`（:84）：雾/水下判断的依据，含液面高度修正。
- ClippingHelperImpl：`public static ClippingHelper getInstance()`（ClippingHelperImpl.java:18，单例 + `init()` 从 GL 矩阵重算 6 平面）；ClippingHelper.`public boolean isBoxInFrustum(...)`（ClippingHelper.java:18）8 角点 6 平面测试。
- Frustum：`public Frustum()`（Frustum.java:12）默认取 `ClippingHelperImpl.getInstance()`；`setPosition` 后所有测试减去相机坐标。

### 其它

- ThreadDownloadImageData：`public void loadTexture(IResourceManager resourceManager) throws IOException`（ThreadDownloadImageData.java:71，优先本地 cacheFile）→ `protected void loadTextureFromServer()`（:106，daemon 线程 HTTP 下载，2xx 才接受）；`public int getGlTextureId()`（:55）主线程惰性 `checkTextureUploaded()`（:38）上传。皮肤链路配 ImageBufferDownload.`public BufferedImage parseUserSkin(BufferedImage image)`（ImageBufferDownload.java:14，32 高转 64×64 + 透明区规范化）。
- InventoryEffectRenderer：`protected void updateActivePotionEffects()`（InventoryEffectRenderer.java:30，有药水效果时 `guiLeft = 160 + (this.width - this.xSize - 200) / 2`）；`drawScreen`（:47）后置绘制 `drawActivePotionEffects()`（:60）。
- ItemModelMesher：`public IBakedModel getItemModel(ItemStack stack)`（ItemModelMesher.java:35，simpleShapesCache → ItemMeshDefinition → missingModel）；索引 `Item.getIdFromItem(item) << 16 | meta`（:70）；`public void rebuildCache()`（:89，资源重载）。
- EnumFaceDirection：`public static EnumFaceDirection getFacing(EnumFacing facing)`（EnumFaceDirection.java:17），FaceBakery 烘焙顶点顺序查表。
- ChestRenderer：`public void renderChestBrightness(Block p_178175_1_, float color)`（ChestRenderer.java:9）→ TileEntityItemStackRenderer。
- DestroyBlockProgress：`setPartialBlockDamage(int damage)` 上限截断 10（DestroyBlockProgress.java:39-47）。
- StitcherException（StitcherException.java:5）：纹理图集装填失败时由 texture 包抛出。

## 时序与生命周期

**初始化（主线程，Minecraft.startGame）**
1. GL 上下文建立后 `OpenGlHelper.initializeTextures()`（Minecraft.java:489）——必须先于一切 GL 能力分支。
2. `this.itemRenderer = new ItemRenderer(this)`（:557）→ `this.entityRenderer = new EntityRenderer(this, this.mcResourceManager)`（:559，构造 lightmapTexture 与雨坐标表）→ `this.renderGlobal = new RenderGlobal(this)`（:563，此处构造 `ChunkRenderDispatcher`，**启动 2 个 Chunk Batcher 线程**，并按 useVbo 生成天空几何）→ `this.renderGlobal.makeEntityOutlineShader()`（:599）。
3. 进入世界时 `RenderGlobal.setWorldAndLoadRenderers` → `loadRenderers()` 建 ViewFrustum（(2r+1)²×16 个 RenderChunk，每个 VBO 路径预分配 4 个 VertexBuffer）。

**每 tick（主线程，Minecraft.runTick）**
- `this.entityRenderer.updateRenderer()`（Minecraft.java:2183）：FOV 收敛、火把闪烁（置 `lightmapUpdateNeeded`）、平滑相机、`itemRenderer.updateEquippedItem()`、雨粒子与雨声。
- `this.renderGlobal.updateClouds()`（Minecraft.java:2190）：`++cloudTickCounter`，每 20 tick 清理超过 400 tick 的 DestroyBlockProgress（RenderGlobal.java:1138-1146）。

**每帧（主线程，Minecraft.runGameLoop）**
`entityRenderer.updateCameraAndRender(partialTicks, nanoTime)` → `renderWorld` → `renderWorldPass(2, ...)`（anaglyph 时 pass 0/1 各一次）：
1. `updateLightmap`（仅 lightmapUpdateNeeded 时）→ `getMouseOver`（更新 objectMouseOver/pointedEntity）。
2. clear + `setupCameraTransform` + `ActiveRenderInfo.updateRenderInfo` + 新 `Frustum`。
3. `renderSky` → 低空云 → `setupTerrain`（BFS 可见集 + near 区块同步编译）→ `updateChunks(finishTimeNano)`（跑上传队列 + 派发延迟编译，帧预算 `1000000000 / fps / 4`，EntityRenderer.java:1141-1145）。
4. SOLID → CUTOUT_MIPPED → CUTOUT → `renderEntities`（实体 + TileEntity）→ 选择框 → 破坏纹理 → 粒子 → 雨雪 → 世界边界 → TRANSLUCENT（先触发重排序任务）→ 高空云 → `renderHand`。
5. 回到 updateCameraAndRender：旁观轮廓 FBO 合成、`theShaderGroup.loadShaderGroup`、`ingameGUI.renderGameOverlay`、`currentScreen.drawScreen`。

**区块编译（跨线程）**
`markBlockForUpdate`（主线程，封包/本地改方块触发）→ RenderChunk.needsUpdate=true → 下帧 `setupTerrain` 收集 → 近处 `renderDispatcher.updateChunkNow`（主线程用 `renderWorker` 同步跑）/ 远处 `updateChunkLater` 入 `queueChunkUpdates` → **Chunk Batcher 线程** `processTask`：`rebuildChunk`（读 RegionRenderCache 快照，写 RegionRenderCacheBuilder 的 WorldRenderer）→ `uploadChunk` 包装为 FutureTask 入 `queueChunkUploads` → **主线程** `runChunkUploads` 执行 glBufferData/glNewList → future 回调 `setCompiledChunk`、builder 归还池。

**线程归属**：本包所有 GL 调用只允许主线程；Chunk Batcher ×2（daemon）只做 CPU 侧网格化；Texture Downloader #N（daemon）只做 HTTP + 图像解码；Netty EventLoop 不直接进入本包（封包 → 主线程任务队列 → IWorldAccess 回调）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void updateCameraAndRender(float partialTicks, long nanoTime)` | EntityRenderer.java:1069 | 每帧，Minecraft.runGameLoop | 帧级总钩子：注入 HUD 前后逻辑、拦截整帧渲染、自由视角 | 内含鼠标视角处理，整体替换会失去转头 |
| `private void renderWorldPass(int pass, float partialTicks, long finishTimeNano)` | EntityRenderer.java:1323 | 每帧（anaglyph 两次） | 在世界渲染任意阶段间插入自绘（ESP、轨迹线等，通常挂在 hand 之前） | 各阶段 GL 状态假设严格，插入后要恢复 blend/depth/texture |
| `public void updateRenderer()` | EntityRenderer.java:327 | 每 tick，Minecraft.runTick:2183 | tick 级渲染状态钩子（FOV、平滑相机、equip 动画节奏） | 不在此做 GL 调用以外的重活；rendererUpdateCount 被雨雪/传送门动画依赖 |
| `public void getMouseOver(float partialTicks)` | EntityRenderer.java:409 | 每帧 renderWorld 开头 | 改写命中目标：reach 扩展、目标过滤、aim 辅助（mc.objectMouseOver / mc.pointedEntity 唯一来源） | 服务器有自己的距离校验；`extendedReach()` 仅创造 |
| `private void orientCamera(float partialTicks)` | EntityRenderer.java:634 | 每 pass，setupCameraTransform 尾部 | 自定义相机（第三人称距离、freecam、去除翻滚） | private，需字节码或改源；末尾维护 cloudFog |
| `private float getFOVModifier(float partialTicks, boolean useFOVSetting)` | EntityRenderer.java:551 | 每 pass 多次（透视/天空/云/手各自投影） | 动态 FOV / 去除水下 FOV 缩放 | 手部渲染传 useFOVSetting=false |
| `private void hurtCameraEffect(float partialTicks)` / `private void setupViewBobbing(float partialTicks)` | EntityRenderer.java:585 / :615 | setupCameraTransform 与 renderHand | 去除受击晃动 / 视角摆动（no-hurtcam） | 两处调用点都要覆盖 |
| `private void updateLightmap(float partialTicks)` + `public void enableLightmap()` / `public void disableLightmap()` | EntityRenderer.java:922 / :892 / :885 | lightmapUpdateNeeded 时 / 各渲染段 | Fullbright（把 lightmapColors 全置 0xFFFFFFFF）、gamma hack | lightmapColors 数组即 DynamicTexture 后备数组，写完需 updateDynamicTexture |
| `private void setupFog(int startCoords, float partialTicks)` / `private void updateFogColor(float partialTicks)` | EntityRenderer.java:1936 / :1763 | 每 pass 多次 | 去雾/自定义雾色与距离 | startCoords==-1 是天空专用近雾 |
| `private void renderHand(float partialTicks, int xOffset)` | EntityRenderer.java:831 | 每 pass 末尾 | 隐藏手/自定义 viewmodel 投影 | 先 `GlStateManager.clear(256)` 清深度；旁观/睡觉/hideGUI 已跳过 |
| `public void loadEntityShader(Entity entityIn)` / `public void activateNextShader()` / `public void stopUseShader()` | EntityRenderer.java:232 / :258 / :213 | 切换旁观目标 / GuiOptions "Super Secret Settings..." 按钮（F4 触发的是 switchUseShader，Minecraft.java:1930） / 退出 | 自定义全屏后处理 shader 注入点 | 需 `OpenGlHelper.shadersSupported` |
| `public void setupOverlayRendering()` | EntityRenderer.java:1748 | 每帧 GUI 前；无世界时也调 | 自定义 GUI 投影（缩放、平移动画） | z 范围 1000–3000，translate -2000 |
| `public void renderEntities(Entity renderViewEntity, ICamera camera, float partialTicks)` | RenderGlobal.java:556 | 每 pass "entities" 段 | 实体 ESP/过滤/替换渲染；TileEntity 渲染也在此 | 前 2 帧被 renderEntitiesStartupCounter 跳过；lightmap 已启用 |
| `public void setupTerrain(Entity viewEntity, double partialTicks, ICamera camera, int frameCount, boolean playerSpectator)` | RenderGlobal.java:774 | 每 pass "terrain_setup" | 禁用遮挡剔除（强制 renderInfos 全量）、控制重建节流 | BFS 依赖 CompiledChunk.isVisible；`mc.renderChunksMany` 是开关 |
| `public int renderBlockLayer(EnumWorldBlockLayer blockLayerIn, double partialTicks, int pass, Entity entityIn)` | RenderGlobal.java:1023 | 每 pass 4 次（4 层） | X-Ray（跳层/自定义着色）、透明层排序控制 | TRANSLUCENT 反向遍历；内部会 enable/disableLightmap |
| `public void drawSelectionBox(EntityPlayer player, MovingObjectPosition movingObjectPositionIn, int execute, float partialTicks)` | RenderGlobal.java:1875 | 每 pass "outline" | 自定义方块高亮（颜色/形状） | 仅 typeOfHit==BLOCK 且 execute==0 |
| `public static void drawOutlinedBoundingBox(AxisAlignedBB boundingBox, int red, int green, int blue, int alpha)` | RenderGlobal.java:1934 | 调试方向轴等 | 现成的线框 AABB 绘制工具（ESP 直接可用） | 需自行设置 blend/depth/线宽；坐标须已减相机 |
| `public void markBlockForUpdate(BlockPos pos)` / `public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2)` / `public void notifyLightSet(BlockPos pos)` | RenderGlobal.java:1972 / :1992 / :1980 | World.markBlockForUpdate 等（封包驱动） | 监听世界方块/光照变更（客户端最终汇聚点） | 主线程；±1 扩散，频繁调用会导致重建风暴 |
| `public void playAuxSFX(EntityPlayer player, int sfxType, BlockPos blockPosIn, int data)` / `public void broadcastSound(int soundID, BlockPos pos, int data)` / `public void playRecord(String recordName, BlockPos blockPosIn)` | RenderGlobal.java:2160 / :2123 / :1997 | S28PacketEffect 等 → World → IWorldAccess | 世界事件监听（门开关、破坏音、唱片） | 见协议表；playSound/playSoundToNearExcept 是空实现（:2025-2034） |
| `public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress)` | RenderGlobal.java:2362 | S25PacketBlockBreakAnim（NetHandlerPlayClient.java:1334）与本地挖掘 | 观察他人挖掘进度（breakerId=实体 id） | progress 0–9 有效，其余删除条目 |
| `public void spawnParticle(int particleID, boolean ignoreRange, ...)` | RenderGlobal.java:2036 | World.spawnParticle → IWorldAccess | 粒子过滤/替换 | 距离 >16 或 particleSetting 限制会丢弃（:2090-2091） |
| `public void loadRenderers()` | RenderGlobal.java:482 | 改渲染距离/VBO 开关/换世界/F3+A | 全量重建渲染器；自定义渲染距离逻辑 | 重建期间 renderEntitiesStartupCounter=2 黑屏两帧实体 |
| `public void updateChunks(long finishTimeNano)` | RenderGlobal.java:1650 | 每 pass "updatechunks" | 控制每帧编译/上传预算（加速或限速区块加载） | finishTimeNano 为绝对纳秒截止 |
| `public void updateTileEntities(Collection<TileEntity> tileEntitiesToRemove, Collection<TileEntity> tileEntitiesToAdd)` | RenderGlobal.java:2388 | rebuildChunk（工作线程） | 追踪 forceRender TileEntity 集合 | 唯一被工作线程调用的 RenderGlobal 公有方法，synchronized |
| `public boolean renderBlock(IBlockState state, BlockPos pos, IBlockAccess blockAccess, WorldRenderer worldRendererIn)` | BlockRendererDispatcher.java:53 | rebuildChunk 遍历每个方块（工作线程） | 网格级 X-Ray/自定义方块外观（返回 false 即不产生几何） | 运行在 Chunk Batcher 线程，禁止 GL 调用与非快照世界访问 |
| `public boolean renderModel(IBlockAccess blockAccessIn, IBakedModel modelIn, IBlockState blockStateIn, BlockPos blockPosIn, WorldRenderer worldRendererIn, boolean checkSides)` | BlockModelRenderer.java:30 | renderBlock case 3 | 替换模型/AO 开关/自定义 tint | 同上线程约束；AO 依赖邻块快照 |
| `public void rebuildChunk(float x, float y, float z, ChunkCompileTaskGenerator generator)` | RenderChunk.java:115 | ChunkRenderWorker.processTask | 区块网格全过程钩子（统计、剔除、注入几何） | 工作线程；持 generator.getLock() 检查状态，勿长时间阻塞 |
| `public void renderItemInFirstPerson(float partialTicks)` / `private void transformFirstPersonItem(float equipProgress, float swingProgress)` | ItemRenderer.java:355 / :296 | renderHand | 自定义手持动画（老版挥动、位置缩放） | transform* 为 private；矩阵栈由调用方管理 |
| `public void renderOverlays(float partialTicks)` | ItemRenderer.java:421 | renderHand（第一人称且非睡觉） | 去除着火/水下遮挡覆盖层 | 旁观者已跳过部分 |
| `public void updateEquippedItem()` | ItemRenderer.java:581 | 每 tick | 切换物品动画控制（快速举起） | equippedProgress<0.1 才真正换 itemToRender |
| `public boolean isBoxInFrustum(double p_78553_1_, ..., double p_78553_11_)` | ClippingHelper.java:18 | 所有视锥测试最终落点 | 恒 true = 关闭视锥剔除（配合实体 ESP 透视） | 性能开销大；区块 BFS 也走它 |
| `public static void updateRenderInfo(EntityPlayer entityplayerIn, boolean p_74583_1_)` | ActiveRenderInfo.java:54 | 每 pass camera 段 | 读取精确相机位置/朝向分量（世界坐标叠加 `projectViewFromEntity`） | 静态全局，freecam 需同步伪造 |
| `public void renderTileEntityAt(TileEntityChest te, double x, double y, double z, float partialTicks, int destroyStage)` | TileEntityChestRenderer.java:34 | TileEntityRendererDispatcher | 箱子 ESP/动画修改（各 TESR 同理） | 坐标是相对相机的；destroyStage>=0 时纹理矩阵被推栈 |
| `protected void updateActivePotionEffects()` | InventoryEffectRenderer.java:30 | initGui 及药水变化 | GUI 偏移控制（去除药水栏挤压） | guiLeft 被子类布局引用 |
| `public static void initializeTextures()` | OpenGlHelper.java:97 | 启动一次（Minecraft.java:489） | 强制关闭 VBO/FBO/shader 路径（兼容性开关） | 只能调用一次；之后所有静态常量已定型 |
| `public static void bindTexture(int texture)` / `public static void color(...)` / `public static void tryBlendFuncSeparate(...)` | GlStateManager.java:347 / :488 / :147 | 全客户端所有 GL 状态变更 | 全局 GL 状态观察/强制改写（shader 注入、颜色钩子） | 影子缓存：绕过它直接调 GL11 会造成状态失同步 |

## 数据与协议

本包不直接编解码封包，但它是多个 S→C 封包的**最终消费端**（经 `NetHandlerPlayClient` → `World`/`WorldClient` → `IWorldAccess`）：

| 入口方法 | 字段 | 类型 | 读/写 | 取值含义 |
|---|---|---|---|---|
| `sendBlockBreakProgress` (RenderGlobal.java:2362) | breakerId | int | 读（S25PacketBlockBreakAnim） | 挖掘者实体 id，damagedBlocks 的 key |
| 同上 | progress | int | 读 | 0–9：破坏阶段（贴图 destroy_stage_N）；其它值删除条目 |
| `broadcastSound` (:2123) | soundID | int | 读（S28PacketEffect global） | 1013=mob.wither.spawn，1018=mob.enderdragon.end |
| `playAuxSFX` (:2160) | sfxType | int | 读（S28PacketEffect） | 1000 click / 1001 click(1.2) / 1002 bow / 1003 door_open / 1004 fizz / 1005 唱片(data=itemId) / 1006 door_close / 1007 ghast.charge / 1008-1009 ghast.fireball / 1010-1012 zombie 木/铁门 / 1014 wither.shoot / 1015 bat.takeoff / 1016 infect / 1017 unfect / 1020-1022 anvil / 2000 烟雾方向(data%3-1, data/3%3-1) / 2001 方块破坏(data 低 12 位=blockId，>>12=meta) / 2002 喷溅药水(data=药水 meta，取色 `Items.potionitem.getColorFromDamage(data)`) / 2003 末影之眼 / 2004 刷怪笼烟火 / 2005 骨粉 |
| `spawnParticle` (:2036) | particleID / parameters | int / int[] | 读（S2APacketParticles） | EnumParticleTypes id；ITEM_CRACK 等经 parameters 带 item id |
| `playRecord` (:1997) | recordName | String | 读 | "records.xxx"；null 停止；mapSoundPositions 按 BlockPos 去重 |
| TileEntityItemStackRenderer.renderByItem (TileEntityItemStackRenderer.java:38-53) | SkullOwner | NBT compound(10) 或 string(8) | 读+写回 | 头颅皮肤 GameProfile；string 形式会 `TileEntitySkull.updateGameprofile` 后升级为 compound 写回 ItemStack NBT |

注册表类：BlockModelShapes/BlockStateMapper 把 `Block.blockRegistry` 全量映射为 `ModelResourceLocation`（格式 `名称#property=value,...` 或 `#normal`，StateMapperBase.java:15-39）；ItemModelMesher 用 `Item.getIdFromItem(item) << 16 | meta` 做 int 索引（ItemModelMesher.java:70）。

## 不变量与陷阱

- **GL 只在主线程**。`ChunkRenderDispatcher.uploadChunk` 对非主线程调用会转投 `queueChunkUploads`（ChunkRenderDispatcher.java:230-259）；Chunk Batcher 线程内任何 GL 调用都会崩。判定依据是 `Minecraft.getMinecraft().isCallingFromMinecraftThread()`。
- **GlStateManager 是唯一真相**。它对 blend/depth/texture 等做影子去重（例：`bindTexture` 只比较 `textureState[activeTextureUnit].textureName`，GlStateManager.java:349）。绕过它直接调 `GL11.glBindTexture` 等会让缓存失真，之后"相同值"的设置被跳过，产生难查的花屏。外部代码改了状态后应调用对应 GlStateManager 方法或 `resetColor()` 之类强制失效。
- **WorldRenderer 单线程使用 + begin/finish 配对**。`begin` 重入抛 "Already building!"（WorldRenderer.java:182），`finishDrawing` 未配对抛 "Not building!"（:554）。`Tessellator.getInstance()` 是全局单例（Tessellator.java:9），只能主线程用；工作线程必须用 RegionRenderCacheBuilder 里自己的 WorldRenderer。
- **putBrightness4/putPosition/putColorMultiplier/putNormal 都假定"刚写完 4 个顶点"**（基址 `(this.vertexCount - 4)`，WorldRenderer.java:265/:276/:487）。quad 未满 4 顶点时调用会写坏前面的数据。
- **颜色字节序**：`putColorRGBA`/`putColorMultiplier` 显式按 `ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN` 分支（:306/:348）。缓冲区都是 native order（GLAllocation.createDirectByteBuffer，GLAllocation.java:53）。
- **growBuffer 后 rawFloatBuffer 变为只读**（`asFloatBuffer().asReadOnlyBuffer()`，WorldRenderer.java:58）——它只被 `sortVertexData`/`getDistanceSq` 读取，若新增代码想写 float 视图会抛 ReadOnlyBufferException。
- **RenderChunk 双锁**：`lockCompileTask` 保护 compileTask 生命周期，`lockCompiledChunk` 保护 compiledChunk 替换（RenderChunk.java:39-40）。`stopCompileTask` 会把 compiledChunk 置回 `CompiledChunk.DUMMY`，而 DUMMY 的 `setLayerUsed/setLayerStarted` 抛 UnsupportedOperationException（CompiledChunk.java:12-26）——凡是拿到 compiledChunk 引用的代码不要缓存后再写。
- **queueChunkUpdates 容量 100、builder 池只有 5 个**（ChunkRenderDispatcher.java:31-32）。`updateChunkLater` offer 失败会直接 `finish()` 任务（needsUpdate 会被 finish 重新置 true，ChunkCompileTaskGenerator.java:75-78），所以刷屏式 markDirty 不丢更新但会反复排队。
- **CompiledChunk 可见性剪枝**：`setupTerrain` 的 BFS 依赖 `mc.renderChunksMany`（RenderGlobal.java:828）与 `isVisible`；旁观者视点在实体方块内时会退化关闭（:854-857）。做"透视所有区块"时直接把 renderInfos 填满比改 VisGraph 便宜。
- **renderEntitiesStartupCounter**：换世界后前 2 次 renderEntities 直接 return（RenderGlobal.java:558-561），依赖实体渲染副作用的功能要考虑这两帧空窗。
- **半透明层排序**：只有相机移动平方距离 >1 才触发重排（:1034），且每帧最多 15 个区块（`k++ < 15`，:1043）；TRANSLUCENT 绘制顺序与其它层相反（:1055-1058）。
- **anaglyphEnable 是 public static 全局量**（EntityRenderer.java:74），被 BlockModelRenderer 网格化路径读取（BlockModelRenderer.java:146）——即工作线程也读它，改动它有可见性/撕裂问题（原版即如此）。
- **LWJGL3/JDK25 移植点**：
  - `org.lwjgl.input.Mouse`、`org.lwjgl.opengl.Display`、`org.lwjgl.opengl.GLContext`、`org.lwjgl.util.glu.GLU/Project`、`org.lwjgl.util.vector.*` 全部来自本仓库的 `lwjgl2-shim` 模块（lwjgl2-shim/src/main/java/org/lwjgl/...），不是真 LWJGL2；行为差异要去 shim 查。
  - `OpenGlHelper.glShaderSource(int shaderIn, ByteBuffer string)` 用 LWJGL3 的 `MemoryUtil.memUTF8(string)` 先转 String（OpenGlHelper.java:358/:362）；uniform 系列用 LWJGL3 命名 `GL20.glUniform1iv/glUniform1fv/...`（:447 等）。
  - `GlStateManager.getFloat` → `GL11.glGetFloatv`、`multMatrix` → `GL11.glMultMatrixf`（GlStateManager.java:450/:485）：LWJGL3 的重命名 API。
  - CPU 信息改用 oshi（`new SystemInfo()).getHardware().getProcessor()`，OpenGlHelper.java:298），异常被静默吞掉（:301-304）。
  - `ChunkRenderWorker` 显式用 `MoreExecutors.directExecutor()`（ChunkRenderWorker.java:190）——新 Guava API。
  - `BlockStateMapper` 用 `com.google.common.base.MoreObjects.firstNonNull`（BlockStateMapper.java:3/:36）——Guava 升级产物。
  - 固定管线（display list、glEnableClientState、texgen、glFog）要求 GL 兼容性 profile 上下文；shim 的 Display 必须以 compatibility 模式建窗，否则本包大面积失效。
- **ThreadDownloadImageData**：下载线程只填 `bufferedImage` 字段，GL 上传延迟到主线程首次 `getGlTextureId()`（ThreadDownloadImageData.java:55-59）；`setBufferedImage` 后 `textureUploaded` 不复位——皮肤只上传一次，热替换需自行 delete。
- **TileEntityBannerRenderer 纹理缓存**：DESIGNS 上限 256，60s 未用才淘汰，超限直接不渲染新图案（返回 null，TileEntityBannerRenderer.java:116-119）。
- **ViewFrustum 是环形数组**：`getRenderChunk` 对 X/Z 取模（ViewFrustum.java:156-168），拿到的 RenderChunk 可能是"错位复用"的旧位置区块；判断有效性要比对 `getPosition()`。Y 超界返回 null。
- **RegionRenderCache 缓存索引假设 20×16×20 邻域**（`i * 400 + k * 20 + j`，RegionRenderCache.java:84），构造参数 subIn 固定为 1（RenderChunk.java:131），别拿它当通用世界快照。

## 交叉引用

- net/minecraft/client → `Minecraft#runGameLoop`（调 EntityRenderer#updateCameraAndRender）、`Minecraft#runTick`（调 EntityRenderer#updateRenderer、RenderGlobal#updateClouds）、`Minecraft#startGame`（构造顺序与 OpenGlHelper#initializeTextures）、`Minecraft#getBlockRendererDispatcher/getRenderItem/getRenderManager/getTextureManager/getTextureMapBlocks`、`Minecraft#isCallingFromMinecraftThread`（ChunkRenderDispatcher 上传路由）
- net/minecraft/client/renderer/entity → `RenderManager#renderEntitySimple/shouldRender/cacheActiveRenderInfo/setRenderPosition/setRenderOutlines`（RenderGlobal#renderEntities）、`RenderItem#renderItemModelForEntity/shouldRenderItemIn3D/renderItem`（ItemRenderer、RenderItemFrame）、`RenderPlayer#renderRightArm/renderLeftArm`（ItemRenderer 手臂）
- net/minecraft/client/renderer/tileentity → `TileEntityRendererDispatcher#instance.renderTileEntity/cacheActiveRenderInfo/getSpecialRenderer`（RenderGlobal#renderEntities、RenderChunk#rebuildChunk）
- net/minecraft/client/renderer/texture → `TextureManager#bindTexture/getTexture/loadTexture/getDynamicTextureLocation`、`TextureMap#getAtlasSprite/locationBlocksTexture`（destroy icons、流体贴图、地形绑定）、`DynamicTexture#updateDynamicTexture`（lightmap）、`TextureUtil#anaglyphColor/uploadTextureImage`
- net/minecraft/client/renderer/vertex → `VertexFormat/VertexFormatElement`（WorldRenderer 布局）、`VertexBuffer#bufferData/bindBuffer/drawArrays/deleteGlBuffers`（VBO 路径）、`DefaultVertexFormats#BLOCK/POSITION/POSITION_TEX/POSITION_TEX_COLOR/ITEM/PARTICLE_POSITION_TEX_COLOR_LMAP`
- net/minecraft/client/shader → `ShaderGroup#createBindFramebuffers/loadShaderGroup/deleteShaderGroup`、`ShaderLinkHelper#getStaticShaderLinkHelper/setNewStaticShaderLinkHelper`、`Framebuffer#bindFramebuffer/framebufferClear/framebufferRenderExt`（entity outline 与后处理）
- net/minecraft/client/resources/model → `ModelManager#getModel/getMissingModel/getTextureMap`、`IBakedModel#getFaceQuads/getGeneralQuads/isAmbientOcclusion/getParticleTexture`、`ModelResourceLocation`（BlockModelShapes、ItemModelMesher、RenderItemFrame）
- net/minecraft/client/multiplayer → `WorldClient#addWorldAccess/removeWorldAccess/getLoadedEntityList/weatherEffects`（RenderGlobal 注册与实体遍历）；`PlayerControllerMP(playerController)#getBlockReachDistance/extendedReach/isSpectator/getCurrentGameType`（EntityRenderer#getMouseOver/isDrawBlockOutline）
- net/minecraft/client/network → `NetHandlerPlayClient#handleBlockBreakAnim`（→ World#sendBlockBreakProgress → RenderGlobal）、`#handleEffect`（→ World#playAuxSFX/broadcastSound）
- net/minecraft/world → `World#markBlockForUpdate/markBlockRangeForRenderUpdate/notifyLightSet/playAuxSFX/playBroadcastSound/sendBlockBreakProgress/spawnParticle`（IWorldAccess 分发，World.java:434-1124 与 3626-3746）、`WorldBorder#minX/maxX/minZ/maxZ/getClosestDistance/getStatus`（renderWorldBorder）、`ChunkCache`（RegionRenderCache 父类）、`Chunk#getEntityLists/getBlock/getTileEntity`
- net/minecraft/block → `Block#getRenderType/getBlockLayer/shouldSideBeRendered/getMixedBrightnessForBlock/colorMultiplier/getActualState/isOpaqueCube/getSelectedBoundingBox/setBlockBoundsBasedOnState/getOffsetType`（网格化全流程）、`BlockLiquid#getFlowDirection/getLiquidHeightPercent/LEVEL`（BlockFluidRenderer、ActiveRenderInfo）
- net/minecraft/client/gui → `GuiIngame(ingameGUI)#renderGameOverlay/renderStreamIndicator/setRecordPlayingMessage`、`GuiScreen(currentScreen)#drawScreen`、`ScaledResolution`、`MapItemRenderer#renderMap`（地图物品）、`GuiContainer`（InventoryEffectRenderer 父类）
- net/minecraft/client/settings → `GameSettings#renderDistanceChunks/useVbo/fancyGraphics/anaglyph/thirdPersonView/gammaSetting/fovSetting/smoothCamera/viewBobbing/hideGUI/showDebugInfo/particleSetting/allowBlockAlternatives/shouldRenderClouds/keyBindSpectatorOutlines`
- net/minecraft/client/audio → `SoundHandler#playSound/stopSound`、`PositionedSoundRecord#create`（playRecord/playAuxSFX 2001）
- net/minecraft/entity → `Entity#rayTrace/getPositionEyes/getLook/isInRangeToRender3d/getEntityBoundingBox`（拾取与剔除）、`EntityLivingBase#isPlayerSleeping/hurtTime/deathTime`（相机效果）
- lwjgl2-shim → `org.lwjgl.opengl.Display#isActive/getWidth/getHeight`、`org.lwjgl.input.Mouse#getX/getY/setGrabbed/isButtonDown/isInsideWindow`、`org.lwjgl.util.glu.Project#gluPerspective`、`org.lwjgl.util.glu.GLU#gluUnProject/gluErrorString`、`org.lwjgl.opengl.GLContext#getCapabilities`（EntityRenderer/ActiveRenderInfo/OpenGlHelper/GLAllocation）

## 覆盖声明

完整读取了 **63/63** 个文件（每个文件从第 1 行读到最后一行；RenderGlobal.java 因体积分 3 次分页读完，EntityRenderer.java 一次读完）。

逐行精读的类：RenderGlobal、EntityRenderer、GlStateManager、OpenGlHelper、WorldRenderer、ItemRenderer、BlockModelRenderer、BlockRendererDispatcher、BlockFluidRenderer、BlockModelShapes、RenderChunk、ChunkRenderDispatcher、ChunkRenderWorker、ChunkCompileTaskGenerator、CompiledChunk、ViewFrustum、VisGraph、SetVisibility、RegionRenderCache、RegionRenderCacheBuilder、ChunkRenderContainer、RenderList、VboRenderList、ListedRenderChunk、Tessellator、WorldVertexBufferUploader、VertexBufferUploader、GLAllocation、RenderHelper、ActiveRenderInfo、ClippingHelper、ClippingHelperImpl、Frustum、ICamera、ThreadDownloadImageData、ImageBufferDownload、IImageBuffer、InventoryEffectRenderer、ItemModelMesher、ItemMeshDefinition、DestroyBlockProgress、EnumFaceDirection、ChestRenderer、StitcherException、statemap 全部 5 个类、chunk 工厂 3 个接口/类，以及 tileentity 子包全部 11 个渲染器。

只做结构性浏览的类：无（本 bucket 无未精读文件）。BlockModelRenderer 的 EnumNeighborInfo 枚举常量表（BlockModelRenderer.java:536-541）与 EnumFaceDirection 的顶点表逐字段核对过结构但未逐项验证每个 Orientation 组合的数学正确性。

行号引用基于当前工作区源码（分支 main，commit c2cf357 时点）。
