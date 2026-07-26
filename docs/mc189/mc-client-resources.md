---
area: net/minecraft/client/resources
slug: mc-client-resources
files: 49
lines: 4305
tier: A
---

# net/minecraft/client/resources

## 定位

本包是客户端的**资源系统**：资源包（zip / 文件夹 / jar 内置 / 服务器下发）的发现、加载、层叠合并（fallback 链）、`.mcmeta` 元数据解析、语言/翻译（I18n）、玩家皮肤下载缓存，以及方块/物品模型的加载与烘焙（`model` 子包）。

- **谁调用它**：几乎所有渲染与 UI 代码。`Minecraft#startGame()` 构造 `ResourcePackRepository` / `SimpleReloadableResourceManager` / `LanguageManager` / `SkinManager` / `ModelManager`（`Minecraft.java:492-554`）；`TextureManager`、`FontRenderer`、`SoundHandler`、`RenderGlobal` 等都通过 `IResourceManager#getResource` 取资源；GUI 层通过 `I18n.format` 取翻译；`NetHandlerPlayClient#handleResourcePack`（`NetHandlerPlayClient.java:1701`）在收到 `S48PacketResourcePackSend` 时调用 `ResourcePackRepository#downloadResourcePack`。
- **它调用谁**：`net.minecraft.client.renderer.texture`（`TextureUtil`、`TextureManager`、`TextureMap`、`DynamicTexture`）、`net.minecraft.client.renderer.block.model`（`ModelBlock`、`FaceBakery` 等，模型烘焙）、`net.minecraft.util`（`ResourceLocation`、`JsonUtils`、`StringTranslate`、`HttpUtil`）、Mojang authlib（皮肤会话服务）。
- **如果消失**：客户端无法启动——启动阶段第一步就是 `refreshResources()`；纹理、字体、音效、模型、翻译全部无处加载；换资源包 / 换语言 / 服务器资源包 / 玩家皮肤全部失效。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| AbstractResourcePack | 97 | implements IResourcePack | 资源包基类：`ResourceLocation` → `assets/domain/path` 路径映射、pack.mcmeta/pack.png 读取 |
| DefaultPlayerSkin | 45 | - | 静态工具：按 UUID hash 奇偶决定 Steve/Alex 默认皮肤 |
| DefaultResourcePack | 97 | implements IResourcePack | 原版资源包：先查 classpath `/assets/`，再查 assets 索引映射的散列文件 |
| FallbackResourceManager | 137 | implements IResourceManager | 单 domain 的资源包层叠链，后加入的包优先 |
| FileResourcePack | 122 | extends AbstractResourcePack, implements Closeable | zip 资源包，懒打开 ZipFile |
| FolderResourcePack | 54 | extends AbstractResourcePack | 文件夹资源包，直接映射到文件系统 |
| FoliageColorReloadListener | 23 | implements IResourceManagerReloadListener | 重载时读 `textures/colormap/foliage.png` 灌入 ColorizerFoliage |
| GrassColorReloadListener | 23 | implements IResourceManagerReloadListener | 重载时读 `textures/colormap/grass.png` 灌入 ColorizerGrass |
| I18n | 19 | - | 静态翻译入口 `format(key, params)`，委托给当前 Locale |
| IReloadableResourceManager | 10 | extends IResourceManager | 可重载资源管理器接口：reloadResources / registerReloadListener |
| IResource | 18 | - | 单个资源接口：输入流 + .mcmeta 元数据 + 来源包名 |
| IResourceManager | 15 | - | 资源管理器接口：getResource / getAllResources / getResourceDomains |
| IResourceManagerReloadListener | 6 | - | 资源重载回调接口（单方法 onResourceManagerReload） |
| IResourcePack | 24 | - | 资源包接口：取流、判存在、domain 集合、pack 元数据/图标 |
| Language | 47 | implements Comparable\<Language\> | 语言条目值对象（code/region/name/bidirectional），按 code 排序去重 |
| LanguageManager | 100 | implements IResourceManagerReloadListener | 解析各包 language 元数据，重载时加载 en_US + 当前语言的 .lang |
| Locale | 143 | - | .lang 键值表：加载、%d/%f 归一化为 %s、unicode 比例检测、格式化 |
| ResourceIndex | 73 | - | 解析 `assets/indexes/<ver>.json`，建 "domain:path" → objects/散列文件 映射 |
| ResourcePackFileNotFoundException | 12 | extends FileNotFoundException | 带包名与内部路径的找不到资源异常 |
| ResourcePackListEntry | 251 | implements GuiListExtended.IGuiListEntry | 资源包选择 GUI 列表项基类：绘制图标/箭头、处理点击移动 |
| ResourcePackListEntryDefault | 100 | extends ResourcePackListEntry | "Default" 原版包列表项，不可移动不可关闭 |
| ResourcePackListEntryFound | 39 | extends ResourcePackListEntry | 包裹 ResourcePackRepository.Entry 的普通列表项 |
| ResourcePackRepository | 397 | - | resourcepacks 目录扫描、选中列表管理、服务器资源包下载（SHA-1 校验、保留 10 个） |
| SimpleReloadableResourceManager | 121 | implements IReloadableResourceManager | 顶层资源管理器：按 domain 分发到 FallbackResourceManager，广播重载 |
| SimpleResource | 139 | implements IResource | IResource 标准实现，懒解析伴随的 .mcmeta JSON |
| SkinManager | 159 | - | 皮肤/披风异步下载与缓存（2 线程池 + 15s Guava cache） |
| data/AnimationFrame | 33 | - | 单动画帧（index + time，-1 表示用默认时长） |
| data/AnimationMetadataSection | 81 | implements IMetadataSection | 纹理动画元数据：帧列表、宽高、默认帧时长、interpolate |
| data/AnimationMetadataSectionSerializer | 143 | extends BaseMetadataSectionSerializer\<AnimationMetadataSection\>, implements JsonSerializer\<AnimationMetadataSection\> | "animation" 节的 JSON 双向序列化 |
| data/BaseMetadataSectionSerializer | 5 | implements IMetadataSectionSerializer\<T\> | 空抽象基类，仅收拢类型参数 |
| data/FontMetadataSection | 15 | implements IMetadataSection | 字体元数据：256 个字符的 widths/lefts/spacings |
| data/FontMetadataSectionSerializer | 82 | extends BaseMetadataSectionSerializer\<FontMetadataSection\> | "font" 节反序列化（default + 逐字符覆盖） |
| data/IMetadataSection | 5 | - | 元数据节标记接口（空） |
| data/IMetadataSectionSerializer | 11 | extends JsonDeserializer\<T\> | 元数据节反序列化器接口，附 getSectionName() |
| data/IMetadataSerializer | 90 | - | 节名 → 序列化器注册表，基于 Gson 的 parseMetadataSection 总入口 |
| data/LanguageMetadataSection | 19 | implements IMetadataSection | pack.mcmeta 的 "language" 节：Language 集合 |
| data/LanguageMetadataSectionSerializer | 55 | extends BaseMetadataSectionSerializer\<LanguageMetadataSection\> | "language" 节反序列化，region/name 非空、去重校验 |
| data/PackMetadataSection | 25 | implements IMetadataSection | pack.mcmeta 的 "pack" 节：description + pack_format |
| data/PackMetadataSectionSerializer | 46 | extends BaseMetadataSectionSerializer\<PackMetadataSection\>, implements JsonSerializer\<PackMetadataSection\> | "pack" 节双向序列化 |
| data/TextureMetadataSection | 33 | implements IMetadataSection | 纹理元数据：blur/clamp/mipmaps 列表 |
| data/TextureMetadataSectionSerializer | 65 | extends BaseMetadataSectionSerializer\<TextureMetadataSection\> | "texture" 节反序列化 |
| model/BuiltInModel | 52 | implements IBakedModel | 占位模型（箱子等 TESR 渲染的物品），quads 全为 null，isBuiltInRenderer()=true |
| model/IBakedModel | 24 | - | 烘焙模型接口：面 quads、AO、gui3d、粒子纹理、相机变换 |
| model/ModelBakery | 722 | - | 模型烘焙总管：blockstates/models JSON 加载、父链解析、贴图收集、烘焙成 IBakedModel 注册表 |
| model/ModelManager | 57 | implements IResourceManagerReloadListener | 持有烘焙模型注册表，重载时重建 ModelBakery 并刷新 BlockModelShapes |
| model/ModelResourceLocation | 82 | extends ResourceLocation | 带 `#variant` 后缀的资源定位符 |
| model/ModelRotation | 115 | enum | X/Y 各 0/90/180/270 共 16 种模型旋转，预计算 Matrix4f |
| model/SimpleBakedModel | 154 | implements IBakedModel | 标准烘焙模型（按面分桶的 BakedQuad 列表）+ Builder |
| model/WeightedBakedModel | 120 | implements IBakedModel | 加权随机变体模型（blockstate variants 数组），按坐标 hash 选变体 |

## 核心类详解

### SimpleReloadableResourceManager（顶层资源管理器）

关键字段（`SimpleReloadableResourceManager.java:23-26`）：

- `private final Map<String, FallbackResourceManager> domainResourceManagers` — 按 domain（"minecraft"、"realms"…）分发。
- `private final List<IResourceManagerReloadListener> reloadListeners` — 顺序敏感的监听器列表。
- `private final Set<String> setResourceDomains` — `LinkedHashSet`，保序。
- `private final IMetadataSerializer rmMetadataSerializer`。

关键方法：

- `public IResource getResource(ResourceLocation location) throws IOException`（`SimpleReloadableResourceManager.java:55`）— 查 domain 对应的 FallbackResourceManager，无则抛 `FileNotFoundException`。
- `public void reloadResources(List<IResourcePack> resourcesPacksList)`（`SimpleReloadableResourceManager.java:89`）— 清空全部 domain 映射，逐包 `reloadResourcePack`，最后 `notifyReloadListeners()` 按注册顺序广播。由 `Minecraft#refreshResources()`（`Minecraft.java:783`）调用。
- `public void registerReloadListener(IResourceManagerReloadListener reloadListener)`（`SimpleReloadableResourceManager.java:108`）— **注册即立刻回调一次** `reloadListener.onResourceManagerReload(this)`（`:111`）。

### FallbackResourceManager（单 domain 层叠链）

- `protected final List<IResourcePack> resourcePacks`（`FallbackResourceManager.java:19`），`addResourcePack` 追加到尾部。
- `public IResource getResource(ResourceLocation location) throws IOException`（`FallbackResourceManager.java:37`）— **从尾向头**（`for (int i = this.resourcePacks.size() - 1; i >= 0; --i)`，`:42`）找第一个含该资源的包，即后加入的包（用户选中的资源包）覆盖先加入的（默认包）。同时独立查找 `.mcmeta` 伴随文件（`getLocationMcmeta`，`:97`——mcmeta 可以来自另一个包）。
- `public List<IResource> getAllResources(ResourceLocation location) throws IOException`（`:73`）— 正序返回所有包中的同名资源（Locale 合并 .lang 用）。
- `getResourceDomains()` 返回 `null`（`:32-35`），不可对其调用该方法。
- 内嵌 `InputStreamLeakedResourceLogger`（`:102`）：仅 debug 日志级别启用，`finalize()` 时对未 close 的流打印泄漏堆栈。

### IResourcePack 的四个实现

- `AbstractResourcePack`：`private static String locationToName(ResourceLocation location)`（`AbstractResourcePack.java:32`）拼 `"assets/%s/%s"`；`getPackMetadata` 读包内 `pack.mcmeta`（`:61-64`）；`getPackImage()` 读 `pack.png`（`:88`）。静态 `readMetadata(IMetadataSerializer, InputStream, String)`（`:66`）被 `DefaultResourcePack` 复用。
- `FileResourcePack`：懒初始化 `ZipFile`（`FileResourcePack.java:27-35`）；`getResourceDomains()` 枚举 zip 条目，取 `assets/<domain>/` 第二段，非全小写 domain 被丢弃并告警（`:93-100`）；`close()` 关闭并置空 ZipFile（`:114`）。
- `FolderResourcePack`：`hasResourceName` 即 `File#isFile()`（`FolderResourcePack.java:25-28`）。
- `DefaultResourcePack`：`getInputStream` 先 `class.getResourceAsStream("/assets/…")` 再落到 `mapAssets`（由 `ResourceIndex#getResourceMap()` 提供，键是 `location.toString()`，`DefaultResourcePack.java:27-54`）；`defaultResourceDomains = ImmutableSet.of("minecraft", "realms")`（`:19`）。

### ResourcePackRepository（包仓库 + 服务器资源包）

关键字段（`ResourcePackRepository.java:52-60`）：`File dirResourcepacks`、`File dirServerResourcepacks`、`public final IResourcePack rprDefaultResourcePack`、`public final IMetadataSerializer rprMetadataSerializer`、`IResourcePack resourcePackInstance`（当前服务器包）、`ReentrantLock lock`、`ListenableFuture<Object> downloadingPacks`、`repositoryEntriesAll` / `repositoryEntries`（全部 / 选中）。

- 构造器（`:62-91`）：扫描目录后按 `settings.resourcePacks` 恢复选中项；`func_183027_f() == 1`（pack_format）或在 `settings.incompatibleResourcePacks` 中才保留，否则从设置里删除并告警。
- `resourcePackFilter`（`:43-51`）：`.zip` 文件或含 `pack.mcmeta` 的目录。
- `public void updateRepositoryEntriesAll()`（`:113`）— 重新扫描目录，新条目 `updateResourcePack()`，被移除的条目 `closeResourcePack()`。
- `public ListenableFuture<Object> downloadResourcePack(String url, String hash)`（`:175`）— hash 匹配 `^[a-f0-9]{40}$` 则作为文件名，否则用 `"legacy"`；已存在且 SHA-1 一致直接 `setResourcePackInstance`；否则 `deleteOldServerResourcesPacks()`（只保留最近 10 个，`:254`）后经 `HttpUtil.downloadResourcePack`（上限 52428800 字节 = 50MB）下载，期间在主线程 `displayGuiScreen(new GuiScreenWorking())`。调用方：`NetHandlerPlayClient.java:1737`（S48 资源包封包）与 `Realms.java:99`。
- `public ListenableFuture<Object> setResourcePackInstance(File resourceFile)`（`:270`）— 包装成 `FileResourcePack` 并 `Minecraft.getMinecraft().scheduleResourcesRefresh()`。
- `public void clearResourcePack()`（`:284`）— 取消下载 future、清 instance、触发刷新；断开服务器时由 `Minecraft.java:2382` 调用。
- 内部类 `Entry`（`:309`）：`updateResourcePack() throws IOException`（`:322`）按目录/zip 建包、读 "pack" 元数据、读图标（失败回落到默认包图标）后**立即 close**；`bindTexturePackIcon(TextureManager textureManagerIn)`（`:344`）懒注册 DynamicTexture；`toString()` 为 `"%s:%s:%d"`（文件名:folder|zip:lastModified），`equals/hashCode` 基于它——**文件被修改（mtime 变化）即视为新条目**。

### IMetadataSerializer 与 data 子包

- `IMetadataSerializer`：`metadataSectionSerializerRegistry`（`IMetadataSerializer.java:15`）+ 惰性 `Gson`；`public <T extends IMetadataSection> void registerMetadataSectionType(IMetadataSectionSerializer<T> metadataSectionSerializer, Class<T> clazz)`（`:30`）注册后把缓存的 `gson` 置 null；`public <T extends IMetadataSection> T parseMetadataSection(String sectionName, JsonObject json)`（`:37`）— 节不存在返回 null，节不是对象或序列化器未注册抛 `IllegalArgumentException`。构造器给 Gson 注册了 `IChatComponent.Serializer` / `ChatStyle.Serializer` / `EnumTypeAdapterFactory`（`:25-27`）。
- 五个节在 `Minecraft#registerMetadataSerializers()`（`Minecraft.java:602-609`）注册：`texture`、`font`、`animation`、`pack`、`language`。
- `SimpleResource#getMetadata`（`SimpleResource.java:51`）懒解析 mcmeta 流为 JsonObject（只读一次，`mcmetaJsonChecked` 防重入），再走 `srMetadataSerializer.parseMetadataSection`。注意 `mapMetadataSections` 只被 get 从未被 put（`:75-79`），缓存实际不生效，每次调用都会重新解析该节。

### LanguageManager / Locale / I18n

- `LanguageManager` 构造时执行 `I18n.setLocale(currentLocale)`（`LanguageManager.java:28`），`currentLocale` 是 `protected static final Locale`（`:21`）——**全局单例**。
- `public void parseLanguageMetadata(List<IResourcePack> resourcesPacks)`（`:31`）— 从每个包的 pack.mcmeta "language" 节收集 `Language`，先见者优先；由 `Minecraft#refreshResources()`（`Minecraft.java:813`）在 `reloadResources` 之后调用。
- `public void onResourceManagerReload(IResourceManager resourceManager)`（`:63`）— 加载 `["en_US", currentLanguage]`（en_US 兜底在前），随后 `StringTranslate.replaceWith(currentLocale.properties)`（`:73`）同步给服务端侧翻译（`ChatComponentTranslation` 用）。
- `Locale#loadLocaleDataFiles`（`Locale.java:27`，`synchronized`）— 对每个 domain 尝试 `lang/%s.lang`，用 `getAllResources` 合并所有包；行格式 `key=value`，`#` 开头跳过（`:100-116`）；正则 `%(\d+\$)?[\d\.]*[df]` 把 `%d`/`%f` 改写为 `%s`（`:20`、`:111`）；`checkUnicode()`（`:56`）统计码点 ≥256 的字符占比 >0.1 判定 unicode（决定是否强制 unicode 字体）。
- `I18n.format(String translateKey, Object... parameters)`（`I18n.java:15`）— 全局静态入口，全客户端 GUI 约 346 处调用。

### SkinManager（异步皮肤）

- `private static final ExecutorService THREAD_POOL = new ThreadPoolExecutor(0, 2, 1L, TimeUnit.MINUTES, new LinkedBlockingQueue())`（`SkinManager.java:29`）。
- `public ResourceLocation loadSkin(final MinecraftProfileTexture profileTexture, final Type p_152789_2_, final SkinManager.SkinAvailableCallback skinAvailableCallback)`（`:60`）— 纹理位置固定为 `"skins/" + profileTexture.getHash()`；已在 TextureManager 中则同步回调；否则建 `ThreadDownloadImageData`（磁盘缓存 `skinCacheDir/<hash前2位>/<hash>`，占位 `DefaultPlayerSkin.getDefaultSkinLegacy()`）交给 `textureManager.loadTexture`。**必须在主线程调用**（会碰 GL）。
- `public void loadProfileTextures(final GameProfile profile, final SkinManager.SkinAvailableCallback skinAvailableCallback, final boolean requireSecure)`（`:107`）— 在 THREAD_POOL 里查 sessionService（网络），若空且是本人则回填 profile properties 重查；结果通过 `Minecraft.getMinecraft().addScheduledTask` 弹回主线程再 `loadSkin`。调用方：`NetworkPlayerInfo.java:124`（tab 列表玩家）。
- `public Map<Type, MinecraftProfileTexture> loadSkinFromCache(GameProfile profile)`（`:150`）— 15 秒过期的 Guava LoadingCache，miss 时**同步**走网络（`:40-46`），调用线程会被阻塞。
- `public interface SkinAvailableCallback`：`void skinAvailable(Type p_180521_1_, ResourceLocation location, MinecraftProfileTexture profileTexture)`（`:155-158`）。

### ModelBakery / ModelManager（模型管线）

`ModelManager#onResourceManagerReload`（`ModelManager.java:22-28`）每次重载新建一个 `ModelBakery` 并：

```java
public IRegistry<ModelResourceLocation, IBakedModel> setupModelRegistry()
```
（`ModelBakery.java:78-86`）依次执行：

1. `loadVariantItemModels()`（`:88`）— 从 `BlockModelShapes.getBlockStateMapper().putAllStateModelLocations()` 拿全部 blockstate → ModelResourceLocation 映射，逐个读 `blockstates/<name>.json`（`getBlockStateLocation`，`:172`，会用 `getAllResources` 合并多个包的定义，`:140`）；注册 `MODEL_MISSING`（`"builtin/missing"#missing`，`:51`）与 `item_frame` 的 normal/map 变体；再 `loadVariantModels()`（`:177`，读 `models/<path>.json`）与 `loadItemModels()`（`:265`，按 `registerVariantNames()` 硬编码的多变体物品表 `:292-336` + `Item.itemRegistry` 全量，路径 `item/<name>`，`:350`）。
2. `loadModelsCheck()`（`:482`）— `loadModels()` 用 Deque 迭代解析 parent 链（`:494-537`），然后 `ModelBlock.checkModelHierarchy`。
3. `loadSprites()`（`:584`）— 收集全部纹理引用（含 `LOCATIONS_BUILTIN_TEXTURES` 硬编码集合 `:49`：水/岩浆/destroy_stage_0-9/盔甲槽占位），通过 `IIconCreator` 回调驱动 `this.textureMap.loadSprites(this.resourceManager, iiconcreator)`（`:600`）完成图集缝合。
4. `bakeItemModels()`（`:677`）— `builtin/generated`/compass/clock 根模型走 `ItemModelGenerator.makeItemModel`（2D 贴图挤出）；随后把无动画元数据的 sprite `clearFramesTextureData()`（`:700-706`，释放内存）。
5. `bakeBlockModels()`（`:356`）— 每个变体经 `bakeModel(ModelBlock modelBlockIn, ModelRotation modelRotationIn, boolean uvLocked)`（`:451`）+ `FaceBakery#makeBakedQuad`（`:477-480`）生成 quads；多变体包成 `WeightedBakedModel`，单变体直接放入；物品模型注册为 `new ModelResourceLocation((String)entry.getKey(), "inventory")`（`:395`）；`builtin/entity` 根模型注册 `BuiltInModel`（`:400-403`）。

`ModelManager` 字段与方法：`modelRegistry`、`texMap`、`modelProvider`（构造时 `new BlockModelShapes(this)`，`ModelManager.java:19`）、`defaultModel`（missing model）；`public IBakedModel getModel(ModelResourceLocation modelLocation)`（`:30`）null 或未注册一律回落 `defaultModel`。消费方：`BlockModelShapes#reloadModels`（`BlockModelShapes.java:139`）与 `RenderItem`（物品模型 mesher）。

- `ModelResourceLocation`：`parsePathString`（`ModelResourceLocation.java:31`）按 `#` 切 variant，缺省 `"normal"`，variant 强制小写（`:13`）；`equals/hashCode/toString` 均纳入 variant。
- `ModelRotation`：16 个枚举值，构造时预计算 `Matrix4f`（**LWJGL2 的 `org.lwjgl.util.vector.Matrix4f`**，`ModelRotation.java:7-8`）；`rotateFace(EnumFacing)`（`:60`）与 `rotateVertex(EnumFacing, int)`（`:80`）供 FaceBakery 使用；`getModelRotation(int, int)`（`:104`）经 `MathHelper.normalizeAngle` 查表。
- `WeightedBakedModel#getAlternativeModel(long p_177564_1_)`（`WeightedBakedModel.java:61`）— 用位置 hash `Math.abs((int)positionRandom >> 16) % totalWeight` 选变体，渲染方块时由 BlockRendererDispatcher 调用（`BlockRendererDispatcher.java:102,128`）。
- `SimpleBakedModel.Builder#makeBakedModel()`（`SimpleBakedModel.java:142`）— `builderTexture == null` 时抛 `RuntimeException("Missing particle!")`。

## 时序与生命周期

**初始化（主线程，`Minecraft#startGame()`）**，顺序即依赖关系：

1. `Minecraft` 构造器：`mcDefaultResourcePack = new DefaultResourcePack((new ResourceIndex(assetsDir, assetIndex)).getResourceMap())`（`Minecraft.java:375`）。
2. `registerMetadataSerializers()`（`Minecraft.java:492`）→ 五个 mcmeta 节可用。
3. `new ResourcePackRepository(...)`（`:493`）→ 扫描 resourcepacks 目录、恢复选中项。
4. `new SimpleReloadableResourceManager(metadataSerializer_)`（`:494`）。
5. `new LanguageManager(...)` + `registerReloadListener(mcLanguageManager)`（`:495-496`）——语言必须最先注册，后续 listener 初始化会用到翻译。
6. `refreshResources()`（`:497`）— 组装包列表（默认包 + 选中包 + 服务器包）→ `reloadResources` → 广播；异常时清空资源包重试并保存设置（`:783-819`）。
7. 之后按序注册 listener：`renderEngine`(TextureManager, `:499`) → `mcSoundHandler`(`:505`) → 两个 `FontRenderer`(`:516-517`) → `GrassColorReloadListener` / `FoliageColorReloadListener`(`:518-519`) → `modelManager`(`:554`) → `renderItem`(`:558`) → `entityRenderer`(`:560`) → `blockRenderDispatcher`(`:562`) → `renderGlobal`(`:564`)。每次 register 立即触发一次该 listener 的 reload。
8. `new SkinManager(renderEngine, new File(fileAssets, "skins"), sessionService)`（`:502`）。

**运行期重载**：用户在 GUI 改包/语言（`Minecraft.java:1953/1983`）、服务器包下载完成或清除（`scheduleResourcesRefresh`，`Minecraft.java:2782`）都会走 `refreshResources()`，同步阻塞主线程完成全量重载（图集缝合 + 模型重烘焙，秒级）。

**每 tick / 每帧**：本包无 tick/帧逻辑。纹理动画由 renderer.texture 包驱动；本包只在重载瞬间工作。

**线程归属**：
- 资源加载 / 重载 / 模型烘焙 / `loadSkin`：主线程（GL 上下文）。
- `SkinManager.THREAD_POOL`（2 线程）：sessionService 网络查询，结果经 `addScheduledTask` 回主线程。
- `HttpUtil.downloadResourcePack` 的下载线程 + `Futures.addCallback(directExecutor)`：回调 `setResourcePackInstance` 在下载线程执行，但内部只是 `scheduleResourcesRefresh()` 排回主线程。
- `NetHandlerPlayClient#handleResourcePack`：Netty EventLoop 收包后处理（其中 GUI 弹窗经 `addScheduledTask` 回主线程）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void reloadResources(List<IResourcePack> resourcesPacksList)` | SimpleReloadableResourceManager.java:89 | 每次 `Minecraft#refreshResources()`（启动、换包、换语言、服务器包） | 注入自定义 IResourcePack（模组资源、内存包）；观察完整重载 | 主线程阻塞式；注入包需含合法 domain 目录结构 |
| `public void registerReloadListener(IResourceManagerReloadListener reloadListener)` | SimpleReloadableResourceManager.java:108 | 启动期 / 任意时刻 | 功能层挂"资源重载后重建缓存"回调（自定义纹理、shader 等） | 注册时**立即**同步回调一次；注册顺序决定重载顺序，依赖 TextureManager 的须晚于它注册 |
| `void onResourceManagerReload(IResourceManager resourceManager)` | IResourceManagerReloadListener.java:5 | reloadResources 广播 | 实现该接口即是标准的资源重载扩展点 | 在主线程，勿做网络 IO |
| `public IResource getResource(ResourceLocation location) throws IOException` | SimpleReloadableResourceManager.java:55 | 所有纹理/字体/声音/模型读取 | 包装/代理可实现资源替换、访问审计、热重定向 | 调用极高频（重载期间）；返回的流由调用方负责 close |
| `public IResource getResource(ResourceLocation location) throws IOException` | FallbackResourceManager.java:37 | 上一行的 domain 分发目标 | 改 fallback 优先级、单 domain 覆盖 | 逆序遍历是覆盖语义的根基，改动会颠倒资源包优先级 |
| `public void refreshResources()` | Minecraft.java:783 | 启动、GuiScreenResourcePacks 关闭、语言切换、服务器包变化 | 总重载入口，前后插桩可做加载耗时统计、自定义包注入 | 失败会清空用户资源包设置（`:799-807`） |
| `public ListenableFuture<Object> scheduleResourcesRefresh()` | Minecraft.java:2782 | 服务器包下载完成/清除 | 异步触发重载的排队点 | 只是 addScheduledTask 包装 |
| `public ListenableFuture<Object> downloadResourcePack(String url, String hash)` | ResourcePackRepository.java:175 | Netty 线程处理 S48PacketResourcePackSend（NetHandlerPlayClient.java:1737）、Realms | 拦截/校验服务器资源包 URL、改大小上限、跳过下载 | 有 ReentrantLock；hash 不合法时文件名退化为 "legacy"（不校验内容） |
| `public void clearResourcePack()` | ResourcePackRepository.java:284 | 断开服务器（Minecraft.java:2382、RealmsConnect） | 观察服务器包生命周期结束 | 会取消进行中的下载 future |
| `public void setRepositories(List<ResourcePackRepository.Entry> repositories)` | ResourcePackRepository.java:164 | GuiScreenResourcePacks 应用选择 | 程序化启停资源包（配合 refreshResources） | 仅改列表，不触发重载，需自行调 refreshResources |
| `public void updateRepositoryEntriesAll()` | ResourcePackRepository.java:113 | 构造器、打开资源包 GUI | 磁盘热扫描钩子 | mtime 变化即算新 Entry（toString 含 lastModified） |
| `public static String format(String translateKey, Object... parameters)` | I18n.java:15 | 全客户端 GUI（约 346 处） | 翻译劫持/覆盖、伪本地化调试 | 静态方法；`i18nLocale` 未 set 前调用 NPE |
| `public String formatMessage(String translateKey, Object[] parameters)` | Locale.java:130 | I18n.format 的实际实现 | 按 key 注入自定义文案 | 格式错误返回 `"Format error: " + s` 而非抛异常 |
| `public void onResourceManagerReload(IResourceManager resourceManager)` (LanguageManager) | LanguageManager.java:63 | 每次重载 | 追加语言、覆盖 .lang 合并逻辑 | 会调 `StringTranslate.replaceWith` 影响服务端侧翻译 |
| `public ResourceLocation loadSkin(final MinecraftProfileTexture profileTexture, final Type p_152789_2_, final SkinManager.SkinAvailableCallback skinAvailableCallback)` | SkinManager.java:60 | 主线程；NetworkPlayerInfo / TileEntitySkullRenderer | 皮肤替换（改返回的 ResourceLocation 或包装 IImageBuffer）、盗版皮肤修复 | 必须主线程（GL）；纹理键固定为 `skins/<hash>` |
| `public void loadProfileTextures(final GameProfile profile, final SkinManager.SkinAvailableCallback skinAvailableCallback, final boolean requireSecure)` | SkinManager.java:107 | 玩家进入渲染范围 / tab 列表建立（NetworkPlayerInfo.java:124） | 皮肤来源劫持（自建皮肤服务器） | 在 THREAD_POOL 线程执行网络查询，回调经主线程 |
| `public void onResourceManagerReload(IResourceManager resourceManager)` (ModelManager) | ModelManager.java:22 | 每次重载（含启动） | 模型注入的总入口：在 `setupModelRegistry()` 返回后向 registry putObject 自定义 IBakedModel | 重载后旧 IBakedModel 引用全部失效，勿缓存跨重载 |
| `public IBakedModel getModel(ModelResourceLocation modelLocation)` | ModelManager.java:30 | BlockModelShapes.reloadModels、RenderItem 取物品模型 | 按位置替换任意方块/物品模型（自定义渲染核心钩子） | null/未注册回落 missing model |
| `public IRegistry<ModelResourceLocation, IBakedModel> setupModelRegistry()` | ModelBakery.java:78 | ModelManager 重载 | 五阶段模型管线插桩（加自定义 blockstate、纹理、烘焙后处理） | 阶段顺序固定：variants→models→sprites→item bake→block bake |
| `public IBakedModel getAlternativeModel(long p_177564_1_)` | WeightedBakedModel.java:61 | BlockRendererDispatcher 渲染随机变体方块 | 固定/替换随机变体（X-ray 类功能常改此处） | 每方块每帧级别的热路径 |
| `void skinAvailable(Type p_180521_1_, ResourceLocation location, MinecraftProfileTexture profileTexture)` | SkinManager.java:157 | 皮肤下载完成（主线程） | 皮肤就绪事件（换肤 UI、披风系统） | 回调可能在 loadSkin 内同步触发（已缓存时） |

## 数据与协议

**pack.mcmeta（JSON，UTF-8）**——经 `IMetadataSerializer.parseMetadataSection` 解析：

| 节 | 字段 | 类型 | 读取方法 | 含义 |
|---|---|---|---|---|
| `pack` | `pack_format` | int | `PackMetadataSectionSerializer#deserialize`（PackMetadataSectionSerializer.java:26） | 包格式版本；本版要求 `== 1`（ResourcePackRepository.java:80） |
| `pack` | `description` | IChatComponent | 同上 :18 | 包描述，缺失抛 `JsonParseException("Invalid/missing description!")` |
| `language` | `<code>.region` / `.name` / `.bidirectional` | String/String/boolean(默认 false) | `LanguageMetadataSectionSerializer#deserialize`（:16-46） | 语言注册；region/name 空或重复 code 抛异常 |

**纹理 .mcmeta**（`<texture>.png.mcmeta`）：

| 节 | 字段 | 类型 | 读取方法 | 含义 |
|---|---|---|---|---|
| `animation` | `frametime` | int，默认 1 | `AnimationMetadataSectionSerializer#deserialize`（:23） | 默认帧时长（tick），≥1 |
| `animation` | `frames` | array（int 或 `{index,time}`） | 同上 :30-51、`parseAnimationFrame` :70 | 帧序列；对象形式 time≥1、index≥0 |
| `animation` | `width` / `height` | int，默认 -1 | 同上 :53-54 | 帧尺寸（非正方形动画条用） |
| `animation` | `interpolate` | boolean，默认 false | 同上 :66 | 帧间插值 |
| `texture` | `blur` / `clamp` | boolean，默认 false | `TextureMetadataSectionSerializer#deserialize`（:18-19） | 采样过滤 / 边缘钳制 |
| `texture` | `mipmaps` | int array | 同上 :22-53 | 自定义 mipmap 层 |
| `font` | `characters.default.width/spacing/left` + `characters.<0-255>` | float | `FontMetadataSectionSerializer#deserialize`（:23-69） | 逐字符字体度量覆盖（注意 `:44` 中 `left` 的默认取的是 `f1` 即 spacing 的默认值，原版即如此） |

**assets 索引**（`assets/indexes/<ver>.json`）：`objects` 对象的每个键 `"a/b"` 变为 `"a:b"`，值 `hash`（SHA-1 字符串）映射到 `objects/<hash[0:2]>/<hash>`（`ResourceIndex.java:42-51`）。

**.lang 文件**：每行 `key=value`，`#` 注释；`%d`/`%f`（含位置参数形式）统一改写为 `%s`（`Locale.java:20,111`）。

**服务器资源包协议**：`S48PacketResourcePackSend`（url + hash）→ `downloadResourcePack`（限 52428800 字节）→ 客户端以 `C19PacketResourcePackStatus`（ACCEPTED / DECLINED / SUCCESSFULLY_LOADED / FAILED_DOWNLOAD）应答（`NetHandlerPlayClient.java:1701-1760`）。缓存文件名 = SHA-1 hash（40 位 hex），否则 `"legacy"`。

**模型文件**：`assets/<domain>/blockstates/<block>.json`（variants，由 renderer.block.model 的 `ModelBlockDefinition` 解析）、`assets/<domain>/models/<path>.json`（元素/纹理/parent，`ModelBlock` 解析）；本包内四个内置模型常量 `MODEL_GENERATED` / `MODEL_COMPASS` / `MODEL_CLOCK` / `MODEL_ENTITY`（`ModelBakery.java:63-66`）和 `BUILT_IN_MODELS` 里的 `"missing"` JSON（`:716`）。

## 不变量与陷阱

- **重载监听顺序不可乱**：`LanguageManager` 必须第一个注册（否则后续 listener 初始化取不到翻译）；`ModelManager` 依赖 `textureMapBlocks` 已创建；`TextureManager` 必须先于一切需要绑纹理的 listener。顺序固化在 `Minecraft.java:496-564`。
- **`registerReloadListener` 立即同步回调**（`SimpleReloadableResourceManager.java:111`）：listener 构造函数里若还没准备好依赖就注册，会在注册瞬间被调用。
- **fallback 方向**：`FallbackResourceManager` 逆序查找（`:42`），列表尾部 = 最高优先级；而 `getAllResources` 是正序。`Minecraft#refreshResources` 的组包顺序是 默认包 → 用户选中包 → 服务器包（`Minecraft.java:785-793`），所以服务器包优先级最高。
- **`FallbackResourceManager.getResourceDomains()` 返回 null**（`:32-35`），只能对顶层 `SimpleReloadableResourceManager` 调用。
- **domain 必须全小写**：zip/folder 包中非小写 domain 直接被忽略（`FileResourcePack.java:93-96`、`FolderResourcePack.java:41-44`）。
- **`Entry.func_183027_f()` 可 NPE**：`rePackMetadataSection.getPackFormat()`（`ResourcePackRepository.java:379`）在 pack.mcmeta 缺失/无 "pack" 节时 `rePackMetadataSection` 为 null；`getTexturePackDescription`（`:374`）有判空而它没有。
- **IBakedModel 跨重载失效**：每次 reload `ModelManager` 重建整个 registry（`ModelManager.java:24-27`），任何缓存的 `IBakedModel` / `TextureAtlasSprite` 引用都会指向旧图集。
- **`SimpleResource` 的元数据缓存是死代码**：`mapMetadataSections` 从未写入（`SimpleResource.java:75-79`），重复 `getMetadata` 会重复走 Gson；另外 mcmeta 流只能被解析一次（`mcmetaJsonChecked`）。
- **资源流必须 close**：`FallbackResourceManager.InputStreamLeakedResourceLogger`（`:102-136`）靠 `finalize()` 报泄漏——JDK 25 下 finalization 已弃用（见下），泄漏检测基本失效，更要靠调用方自律。
- **JDK 25 移植注意**：`FileResourcePack#finalize`（`FileResourcePack.java:108`）与上述 logger 依赖 finalizer，JDK 18+（JEP 421）finalization 默认仍在但已弃用且可被 `--finalization=disabled` 关掉；zip 句柄的真正关闭依赖 `ResourcePackRepository.Entry#closeResourcePack` 与 `FileResourcePack#close`。`DefaultPlayerSkin.isSlimSkin` 依赖 `UUID.hashCode()`（`DefaultPlayerSkin.java:43`），行为跨 JDK 稳定，无碍。
- **LWJGL3 移植注意**：`ModelRotation` 用的 `org.lwjgl.util.vector.Matrix4f/Vector3f` 来自本仓库的 `lwjgl2-shim`（`lwjgl2-shim/src/main/java/org/lwjgl/util/vector/`），不是 LWJGL3 API；改动 shim 的矩阵语义会静默破坏所有旋转模型。
- **线程约束**：`Locale#loadLocaleDataFiles` 是 `synchronized`，但 `properties` 本身是普通 HashMap 且被 `StringTranslate.replaceWith` 共享——重载只应发生在主线程。`SkinManager.loadSkin` 只能主线程调；`loadSkinFromCache` miss 时同步网络请求，别在渲染热路径首次调用。`ResourcePackRepository` 的 `lock` 只保护 download/clear，`repositoryEntries` 列表无锁，只能主线程操作。
- **服务器包 hash 不强校验**：hash 非 40 位 hex 时存为 `"legacy"` 且完全跳过 SHA-1 验证（`ResourcePackRepository.java:179-186,195`）；同名文件命中即复用。
- **`registerVariantNames` 硬编码**（`ModelBakery.java:292-336`）：多 metadata 物品（羊毛、染料、鱼等）的 inventory 变体名写死在代码里，加物品变体必须改这里。
- **模型加载失败静默降级**：坏 blockstate/model 只 `LOGGER.warn` 并回落 missing model（`ModelBakery.java:112-120,192-195`），不会崩溃，排查需看日志。

## 交叉引用

- `net.minecraft.client` → `Minecraft#startGame`（构造与注册全链，`Minecraft.java:475-564`）、`Minecraft#refreshResources`（:783）、`Minecraft#scheduleResourcesRefresh`（:2782）、`Minecraft#registerMetadataSerializers`（:602）
- `net.minecraft.client.renderer.texture` → `TextureUtil#readBufferedImage` / `TextureUtil#readImageData`（pack.png、colormap 读取）；`TextureManager#loadTexture` / `getDynamicTextureLocation`（皮肤、包图标）；`TextureMap#loadSprites`（图集缝合，`ModelBakery.java:600`）；`TextureAtlasSprite#setLocationNameCompass/Clock`（`ModelBakery.java:624-628`）
- `net.minecraft.client.renderer.block.model` → `ModelBlock#deserialize` / `ModelBlockDefinition#parseFromReader` / `FaceBakery#makeBakedQuad` / `ItemModelGenerator#makeItemModel`（模型解析与烘焙）
- `net.minecraft.client.renderer` → `BlockModelShapes#reloadModels`（`ModelManager.java:27`）；`ImageBufferDownload` / `ThreadDownloadImageData`（皮肤下载）
- `net.minecraft.client.gui` → `GuiScreenResourcePacks`（消费 `ResourcePackListEntry*`）；`GuiScreenWorking`（下载进度）；全 GUI → `I18n#format`
- `net.minecraft.client.network` → `NetHandlerPlayClient#handleResourcePack`（S48 → `ResourcePackRepository#downloadResourcePack`）；`NetworkPlayerInfo` → `SkinManager#loadProfileTextures`
- `net.minecraft.client.renderer.tileentity` → `TileEntitySkullRenderer` → `SkinManager#loadSkin`
- `net.minecraft.util` → `StringTranslate#replaceWith`（`LanguageManager.java:73`）；`HttpUtil#downloadResourcePack`（`ResourcePackRepository.java:229`）；`ResourceLocation` / `JsonUtils` / `IRegistry` / `RegistrySimple` / `WeightedRandom`
- `net.minecraft.world` → `ColorizerGrass#setGrassBiomeColorizer` / `ColorizerFoliage#setFoliageBiomeColorizer`（两个 ReloadListener）
- `net.minecraft.init` / `net.minecraft.item` → `Blocks` / `Items` / `Item.itemRegistry`（`ModelBakery#registerVariantNames` / `loadItemModels`）
- `net.minecraft.realms` → `Realms#downloadResourcePack` / `clearResourcePack`
- `com.mojang.authlib` → `MinecraftSessionService#getTextures`（SkinManager）
- `org.lwjgl.util.vector`（lwjgl2-shim）→ `ModelRotation` 的 `Matrix4f` / `Vector3f`

## 覆盖声明

完整读取了 49/49 个文件（bucket 列表全量，每个文件从第 1 行读到末行）。

- 逐行精读：`SimpleReloadableResourceManager`、`FallbackResourceManager`、`ResourcePackRepository`（含内部类 Entry）、`ModelBakery`、`ModelManager`、`SkinManager`、`LanguageManager`、`Locale`、`IMetadataSerializer`、`SimpleResource`、`AbstractResourcePack`、`DefaultResourcePack`、`FileResourcePack`、`ModelResourceLocation`、`ModelRotation`、`WeightedBakedModel`、五个 SectionSerializer。
- 结构性通读（逻辑简单，读完但未逐语句推演）：`ResourcePackListEntry` 三件套的绘制坐标细节、`SimpleBakedModel.Builder` 的 BreakingFour 路径、`FontMetadataSectionSerializer` 的逐字符循环、各纯数据类（`Language`、`AnimationFrame`、各 `*MetadataSection`、`BuiltInModel`、接口文件）。
- 另外核对了包外调用方：`Minecraft.java`（startGame/refreshResources/registerMetadataSerializers/scheduleResourcesRefresh）、`NetHandlerPlayClient.java:1701-1760`、`NetworkPlayerInfo.java:124`、`BlockModelShapes.java`、`GuiScreenResourcePacks.java`、`Realms.java`，以及 `lwjgl2-shim` 中 `org.lwjgl.util.vector` 的存在。
