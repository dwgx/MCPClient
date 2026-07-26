---
area: net/minecraft/client/particle
slug: mc-client-particle
files: 35
lines: 3692
tier: B
---

# net/minecraft/client/particle

## 定位

客户端粒子系统。核心是 `EffectRenderer`：它持有全部存活粒子（按纹理层 × alpha 层分桶）、粒子工厂注册表（particle id → `IParticleFactory`），负责每 tick 更新和每帧渲染。其余 33 个文件是 `EntityFX`（所有粒子的基类，继承 `Entity`）及其具体子类 + 各自的嵌套 `Factory`。

谁调用它：
- `Minecraft`：构造（`Minecraft.java:567`）、每 tick 调 `updateEffects()`（`Minecraft.java:2255`）、挖掘时调 `addBlockHitEffects`（`Minecraft.java:1506`）、换世界时调 `clearEffects`（`Minecraft.java:2400`）。
- `EntityRenderer.renderWorldPass`：每帧调 `renderLitParticles` / `renderParticles`（`EntityRenderer.java:1443` / `1447`）。
- `RenderGlobal.spawnEntityFX`：`World.spawnParticle` 的最终落点，做距离裁剪后调 `spawnEffectParticle`（`RenderGlobal.java:2086/2091`）；方块破坏事件调 `addBlockDestroyEffects`（`RenderGlobal.java:2283`）。
- `NetHandlerPlayClient`：`handleParticles`（S2APacketParticles，`NetHandlerPlayClient.java:2003`）经 `WorldClient.spawnParticle` 进入；拾取动画直接 `addEffect(new EntityPickupFX(...))`（`NetHandlerPlayClient.java:841`）；暴击动画调 `emitParticleAtEntity`（`NetHandlerPlayClient.java:890-894`）。
- `WorldClient.makeFireworks` 直接 `addEffect(new EntityFirework.StarterFX(...))`（`WorldClient.java:457`）。
- `EntityPlayerSP.onCriticalHit / onEnchantmentCritical` 调 `emitParticleAtEntity`（`EntityPlayerSP.java:673/678`）。
- `GuiOverlayDebug` 通过 `getStatistics()` 显示 "P: N"。

它调用谁：`Tessellator`/`WorldRenderer`/`GlStateManager`（渲染）、`TextureManager`（绑定纹理）、`World`（碰撞、光照、`spawnParticle` 递归产生子粒子）、`RenderManager`（`EntityPickupFX`、`MobAppearance` 渲染真实实体）、`Minecraft.getMinecraft()` 单例。

如果它消失：所有环境/战斗/交互视觉反馈没了（挖掘碎屑、爆炸、药水、烟火、暴击星、拾取飞行动画、下雨溅水、传送门颗粒等），F3 的 P 计数崩，`World.spawnParticle` 在客户端变成空操作；游戏逻辑本身不受影响——本包纯视觉，无任何回传服务器的数据。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| Barrier | 56 | extends EntityFX | 屏障方块图标粒子（创造模式手持屏障时显示），用 item 模型的 particle icon，layer 1 |
| EffectRenderer | 480 | (无父类) | 粒子系统枢纽：注册工厂、分桶存储、每 tick 更新、每帧批量渲染 |
| EntityAuraFX | 61 | extends EntityFX | 菌丝/村庄氛围小灰点（TOWN_AURA/SUSPENDED_DEPTH），含 HappyVillagerFactory 绿星变体 |
| EntityBlockDustFX | 25 | extends EntityDiggingFX | 方块粉尘（BLOCK_DUST），与 DiggingFX 的区别是速度直接取传入值 |
| EntityBreakingFX | 98 | extends EntityFX | 物品破裂碎片（ITEM_CRACK/SNOWBALL/SLIME），layer 1，用物品图标的 1/4 随机子区域 |
| EntityBubbleFX | 56 | extends EntityFX | 水中气泡，上浮，离开水材质立即死亡 |
| EntityCloudFX | 85 | extends EntityFX | 云雾粒子（CLOUD），会被 2 格内玩家"吸"向脚下 |
| EntityCrit2FX | 95 | extends EntityFX | 暴击星（CRIT/CRIT_MAGIC），构造器里就先跑一次 onUpdate() |
| EntityCritFX | 19 | extends EntitySmokeFX | SMOKE_LARGE 大烟雾——仅是 scale=2.5F 的 EntitySmokeFX |
| EntityDiggingFX | 123 | extends EntityFX | 挖方块碎屑（BLOCK_CRACK），取方块纹理 + colorMultiplier，layer 1 |
| EntityDropParticleFX | 158 | extends EntityFX | 水滴/岩浆滴（DRIP_WATER/DRIP_LAVA），bobTimer 挂顶 40 tick 再落下，落水面生成 WATER_SPLASH |
| EntityEnchantmentTableParticleFX | 94 | extends EntityFX | 附魔台符文，从偏移点向坐标原点回卷运动，亮度随年龄升高 |
| EntityExplodeFX | 53 | extends EntityFX | 普通爆炸烟粒（EXPLOSION_NORMAL），纹理索引随年龄 7→0 |
| EntityFX | 273 | extends Entity | 所有粒子基类：颜色/年龄/缩放/纹理索引字段，默认 billboard 渲染与运动学 |
| EntityFirework | 432 | (容器类，含 3 个 EntityFX 子类 + Factory) | 烟花：StarterFX 读 NBT 编排爆炸，SparkFX 火花（可拖尾/闪烁/褪色），OverlayFX 闪光面片 |
| EntityFishWakeFX | 56 | extends EntityFX | 钓鱼水波纹（WATER_WAKE），尺寸和纹理索引随剩余寿命变化 |
| EntityFlameFX | 100 | extends EntityFX | 火焰（FLAME），自发光（brightness 随年龄增强），缩放收缩 |
| EntityFootStepFX | 88 | extends EntityFX | 脚印（FOOTSTEP），layer 3，自绑 footprint.png 独立 draw，静止 200 tick 淡出 |
| EntityHeartFX | 94 | extends EntityFX | 爱心（HEART）/愤怒村民（VILLAGER_ANGRY），上飘 16 tick |
| EntityHugeExplodeFX | 60 | extends EntityFX | 巨型爆炸（EXPLOSION_HUGE）：不渲染，每 tick 散布 6 个 EXPLOSION_LARGE |
| EntityLargeExplodeFX | 99 | extends EntityFX | 大爆炸火球（EXPLOSION_LARGE），layer 3，自绑 explosion.png 播 16 帧序列 |
| EntityLavaFX | 97 | extends EntityFX | 岩浆迸溅（LAVA），全亮度，衰减期间随机吐 SMOKE_NORMAL |
| EntityNoteFX | 86 | extends EntityFX | 音符（NOTE），颜色由音高参数经 sin 计算 |
| EntityParticleEmitter | 63 | extends EntityFX | 附着在实体上的发射器：3 tick 内每 tick 在实体体积内撒 ≤16 个指定粒子 |
| EntityPickupFX | 73 | extends EntityFX | 物品拾取动画：3 tick 内把被拾实体插值渲染飞向拾取者，layer 3 |
| EntityPortalFX | 103 | extends EntityFX | 传送门紫粒（PORTAL），从偏移点回卷到原点，亮度随年龄升高 |
| EntityRainFX | 93 | extends EntityFX | 雨滴落地水花（WATER_DROP），落到液面/固面即死 |
| EntityReddustFX | 93 | extends EntityFX | 红石尘（REDSTONE），RGB 由速度参数携带（xSpeed==0 时强制红色） |
| EntitySmokeFX | 88 | extends EntityFX | 普通烟雾（SMOKE_NORMAL），基础可缩放烟雾，被 EntityCritFX 复用 |
| EntitySnowShovelFX | 81 | extends EntityFX | 铲雪粒（SNOW_SHOVEL），带重力的白色烟雾变体 |
| EntitySpellParticleFX | 135 | extends EntityFX | 药水/法术粒子，5 个 Factory：SPELL/INSTANT/MOB/MOB_AMBIENT/WITCH，颜色经速度参数传入 |
| EntitySplashFX | 28 | extends EntityRainFX | 溅水（WATER_SPLASH），雨滴的加速变体 |
| EntitySuspendFX | 52 | extends EntityFX | 水下悬浮颗粒（SUSPENDED），静止不动，出水即死 |
| IParticleFactory | 8 | interface | 单方法工厂接口 `getEntityFX(...)`，EffectRenderer 注册表的值类型 |
| MobAppearance | 87 | extends EntityFX | 远古守卫者幻影（MOB_APPEARANCE），layer 3，用 RenderManager 渲染一只 elder EntityGuardian |

## 核心类详解

### EffectRenderer（EffectRenderer.java）

关键字段：
- `private static final ResourceLocation particleTextures = new ResourceLocation("textures/particle/particles.png")`（EffectRenderer.java:32）
- `private List<EntityFX>[][] fxLayers = new List[4][]`（EffectRenderer.java:36）— 第一维是纹理层（0=particles.png，1=方块图集，3=自绘制；2 未使用），第二维是 alpha 桶（0=半透明，1=不透明）。
- `private List<EntityParticleEmitter> particleEmitters`（EffectRenderer.java:37）— 发射器单独存放，不进 fxLayers。
- `private Map<Integer, IParticleFactory> particleTypes`（EffectRenderer.java:42）— 粒子注册表。

关键方法（签名逐字）：
- `public EffectRenderer(World worldIn, TextureManager rendererIn)`（EffectRenderer.java:44）— 初始化 4×2 桶并调 `registerVanillaParticles()`。
- `private void registerVanillaParticles()`（EffectRenderer.java:62）— 注册全部 41 个原版工厂（EffectRenderer.java:64-104）。
- `public void registerParticle(int id, IParticleFactory particleFactory)`（EffectRenderer.java:107）— 公开注册入口，可覆盖原版 id 或添加自定义 id。
- `public EntityFX spawnEffectParticle(int particleId, double xCoord, double yCoord, double zCoord, double xSpeed, double ySpeed, double zSpeed, int... parameters)`（EffectRenderer.java:128）— 查工厂 → `getEntityFX` → `addEffect`，返回粒子实例（工厂查不到或返回 null 则返回 null）。被 `RenderGlobal.spawnEntityFX` 调用。
- `public void addEffect(EntityFX effect)`（EffectRenderer.java:146）— 按 `getFXLayer()` 和 `getAlpha() != 1.0F` 分桶；桶满 4000 时先移除最旧一个（EffectRenderer.java:151-154）。
- `public void updateEffects()`（EffectRenderer.java:159）— 每 tick 由 `Minecraft.runTick` 调用；遍历 4 层调 `updateEffectLayer`，再 tick 全部 emitter 并移除死亡的。
- `public void renderParticles(Entity entityIn, float partialTicks)`（EffectRenderer.java:239）— 每帧渲染层 0-2：写入静态插值原点 `EntityFX.interpPosX/Y/Z`（EffectRenderer.java:246-248），设置 blend/alphaFunc(516, 0.003921569F)，alpha 桶 0 关 depthMask、桶 1 开，层 0 绑 particles.png、层 1 绑 `TextureMap.locationBlocksTexture`，用 `DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP` 批量提交后 `tessellator.draw()`（EffectRenderer.java:285/317）。tick 与 render 中的异常都会包成 `ReportedException` 崩溃报告（EffectRenderer.java:207-234、295-314）。
- `public void renderLitParticles(Entity entityIn, float partialTick)`（EffectRenderer.java:327）— 只渲染层 3；注意它不 begin/draw，层 3 的粒子各自负责绑纹理与提交（FootStep/LargeExplode/Pickup/MobAppearance 均自绘）。
- `public void clearEffects(World worldIn)`（EffectRenderer.java:354）— 换维度/世界时清空所有桶并更新 `worldObj`。
- `public void addBlockDestroyEffects(BlockPos pos, IBlockState state)`（EffectRenderer.java:369）— 4×4×4=64 个 `EntityDiggingFX`。
- `public void addBlockHitEffects(BlockPos pos, EnumFacing side)`（EffectRenderer.java:395）— 在被击面上随机取点生成 1 个缩小的 DiggingFX；`block.getRenderType() != -1` 才生成（EffectRenderer.java:400）。
- `public void moveToAlphaLayer(EntityFX effect)` / `public void moveToNoAlphaLayer(EntityFX effect)`（EffectRenderer.java:444/449）— 由 `EntityFX.setAlphaF` 在 alpha 跨越 1.0 边界时回调，线性扫描 4 层做搬桶（EffectRenderer.java:454-464）。
- `public String getStatistics()`（EffectRenderer.java:466）— F3 调试行的粒子总数。

### EntityFX（EntityFX.java）

粒子基类，继承 `Entity`，因此免费获得 `posX/motionX/onGround/noClip/moveEntity/setDead/rand` 等实体机制。

关键字段：`protected int particleTextureIndexX / particleTextureIndexY`（16×16 网格索引，EntityFX.java:13-14）、`protected float particleTextureJitterX / particleTextureJitterY`（碎屑类 UV 抖动，EntityFX.java:15-16）、`protected int particleAge / particleMaxAge`（EntityFX.java:17-18）、`protected float particleScale / particleGravity`（EntityFX.java:19-20）、`protected float particleRed/particleGreen/particleBlue/particleAlpha`（EntityFX.java:23-36）、`protected TextureAtlasSprite particleIcon`（layer 1 用图集精灵，EntityFX.java:39）、`public static double interpPosX/interpPosY/interpPosZ`（静态相机插值原点，EntityFX.java:40-42）。

关键方法：
- `protected EntityFX(World worldIn, double posXIn, double posYIn, double posZIn)`（EntityFX.java:44）— 默认 `particleMaxAge = (int)(4.0F / (this.rand.nextFloat() * 0.9F + 0.1F))`（EntityFX.java:57）。
- `public EntityFX(World worldIn, double xCoordIn, double yCoordIn, double zCoordIn, double xSpeedIn, double ySpeedIn, double zSpeedIn)`（EntityFX.java:61）— 六参构造给速度加随机扰动并归一化，多数子类先调它再覆盖 motion。
- `public void onUpdate()`（EntityFX.java:149）— 默认逐帧物理：年龄++ 超龄 setDead、`motionY -= 0.04D * particleGravity`、`moveEntity`、×0.98 阻尼、着地水平 ×0.7（EntityFX.java:155-170）。
- `public void renderParticle(WorldRenderer worldRendererIn, Entity entityIn, float partialTicks, float rotationX, float rotationZ, float rotationYZ, float rotationXY, float rotationXZ)`（EntityFX.java:176）— 标准 billboard 四顶点，UV 来自 `particleTextureIndexX/Y ÷ 16`（单格宽 0.0624375F）或 `particleIcon`，位置=插值坐标−`interpPos*`，光照 lightmap 来自 `getBrightnessForRender`（EntityFX.java:178-201）。
- `public void setAlphaF(float alpha)`（EntityFX.java:99）— 跨越 alpha==1.0 边界时通过 `Minecraft.getMinecraft().effectRenderer` 触发搬桶（EntityFX.java:101-108）。
- `public int getFXLayer()`（EntityFX.java:204）— 默认 0；子类覆盖为 1（方块/物品图集）或 3（自绘）。
- `public void setParticleIcon(TextureAtlasSprite icon)`（EntityFX.java:226）— 仅 layer 1 允许，否则 `throw new RuntimeException("Invalid call to Particle.setTex, use coordinate methods")`（EntityFX.java:236）。
- `public void setParticleTextureIndex(int particleTextureIndex)`（EntityFX.java:243）— 仅 layer 0 允许，否则 `throw new RuntimeException("Invalid call to Particle.setMiscTex")`（EntityFX.java:247）。
- `public void writeEntityToNBT(NBTTagCompound tagCompound)` / `public void readEntityFromNBT(NBTTagCompound tagCompund)` 均为空实现（EntityFX.java:212-221）— 粒子不持久化。

### EntityFirework（EntityFirework.java）

容器类，含三个粒子 + Factory：
- `EntityFirework.Factory`（EntityFirework.java:15）— 注册在 FIREWORKS_SPARK 下，产出 `SparkFX` 并 `setAlphaF(0.99F)`（这会把它放进半透明桶）。
- `public static class SparkFX extends EntityFX`（EntityFirework.java:54）— 字段 `private int baseTextureIndex = 160`（EntityFirework.java:56）、`trail/twinkle/hasFadeColour` 布尔、`fadeColourRed/Green/Blue`、`private final EffectRenderer field_92047_az`。`onUpdate()`（EntityFirework.java:122）过半龄后 alpha 线性衰减 + 颜色向 fadeColour 插值，`trail` 开启时每 2 tick 在原地再生成一个半龄 SparkFX（EntityFirework.java:158-175）；`public int getBrightnessForRender(float partialTicks)` 恒返回 `15728880`（全亮，EntityFirework.java:178-181）。
- `public static class StarterFX extends EntityFX`（EntityFirework.java:189）— 构造签名 `public StarterFX(World p_i46464_1_, double p_i46464_2_, double p_i46464_4_, double p_i46464_6_, double p_i46464_8_, double p_i46464_10_, double p_i46464_12_, EffectRenderer p_i46464_14_, NBTTagCompound p_i46464_15_)`（EntityFirework.java:196）。从 NBT 读 `"Explosions"` TagList；`renderParticle` 为空（不可见，纯编排器）。`onUpdate()`（EntityFirework.java:236）第 0 tick 播 `fireworks.blast/largeBlast(_far)` 音效，此后每 2 tick 消费一条 Explosion：Type 1=大球 `createBall(0.5D, 4, ...)`、2=星形 `createShaped`、3=苦力怕脸 `createShaped`、4=爆裂 `createBurst`、默认小球 `createBall(0.25D, 2, ...)`（EntityFirework.java:280-299），随后追加一个 `OverlayFX` 闪光。结束时按 `twinkle` 播 `fireworks.twinkle(_far)`。`func_92037_i()` 以与观察者距离平方 ≥256 判定 "_far" 音效（EntityFirework.java:325-329）。
- `public static class OverlayFX extends EntityFX`（EntityFirework.java:25）— 4 tick 大闪光面片，UV 硬编码取 particles.png 的 (0.25-0.5, 0.125-0.375) 区域。

### EntityDiggingFX（EntityDiggingFX.java）

字段：`private IBlockState sourceState`、`private BlockPos sourcePos`（EntityDiggingFX.java:14-15）。构造器从 `Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getTexture(state)` 取纹理（EntityDiggingFX.java:21），重力用 `state.getBlock().blockParticleGravity`（EntityDiggingFX.java:22）。
- `public EntityDiggingFX setBlockPos(BlockPos pos)`（EntityDiggingFX.java:30）— 记录来源坐标并乘上 `colorMultiplier`（草方块跳过，避免侧面染绿）。
- `public EntityDiggingFX func_174845_l()`（EntityDiggingFX.java:48）— 无 pos 版本，用 `getRenderColor` 染色。
- `public int getBrightnessForRender(float partialTicks)`（EntityDiggingFX.java:103）— 自身位置光照为 0 时退回取来源方块的 `getCombinedLight`（碎屑嵌在方块里也不会全黑）。
- `renderParticle`（EntityDiggingFX.java:75）— 用 `particleIcon.getInterpolatedU/V` 从方块纹理里裁 1/4 大小的随机子区域（jitter 决定）。

### EntityPickupFX（EntityPickupFX.java）

字段：`private Entity field_174840_a`（被拾取实体）、`private Entity field_174843_ax`（拾取者）、`private int age; private int maxAge`（=3）、`private float field_174841_aA`（Y 偏移）、`private RenderManager field_174842_aB`（EntityPickupFX.java:13-18）。构造器为 `public EntityPickupFX(World worldIn, Entity p_i1233_2_, Entity p_i1233_3_, float p_i1233_4_)`（EntityPickupFX.java:20），无 Factory——只由 `NetHandlerPlayClient.handleCollectItem`（NetHandlerPlayClient.java:841）直接 new。`renderParticle`（EntityPickupFX.java:32）在两实体插值位置之间按 f² 缓动，调 `this.field_174842_aB.renderEntityWithPosYaw(...)` 渲染真实实体（EntityPickupFX.java:53）。layer 3。

### EntityParticleEmitter（EntityParticleEmitter.java）

`public EntityParticleEmitter(World worldIn, Entity p_i46279_2_, EnumParticleTypes particleTypesIn)`（EntityParticleEmitter.java:15），构造器末尾立即执行一次 `this.onUpdate()`。`onUpdate()`（EntityParticleEmitter.java:34）每 tick 掷 16 次单位球内随机点，在附着实体体积内 `this.worldObj.spawnParticle(this.particleTypes, false, d3, d4, d5, d0, d1 + 0.2D, d2, new int[0])`（EntityParticleEmitter.java:47），3 tick 后 setDead。它存放在 `EffectRenderer.particleEmitters` 而非 fxLayers（经 `emitParticleAtEntity`，EffectRenderer.java:112-115），`renderParticle` 为空。

### EntityDropParticleFX（EntityDropParticleFX.java）

字段 `private Material materialType`、`private int bobTimer`（EntityDropParticleFX.java:14-17）。挂在方块底面 40 tick（bobTimer，纹理 113），然后切纹理 112 下落；水滴 onGround 时 `this.worldObj.spawnParticle(EnumParticleTypes.WATER_SPLASH, ...)` 并死亡（EntityDropParticleFX.java:110），岩浆滴落地切纹理 114；进入液体/固体高度即死（EntityDropParticleFX.java:121-140）。岩浆滴自发光：`getBrightnessForRender` 非水时返回 `257`（EntityDropParticleFX.java:46-49）。

### MobAppearance（MobAppearance.java）

远古守卫者诅咒特效。`onUpdate()` 首帧懒加载 `new EntityGuardian(this.worldObj)` 并 `setElder()`（MobAppearance.java:39-44）。`renderParticle`（MobAppearance.java:50）在相机前方以 sin 曲线 alpha 直接用 `RenderManager.renderEntityWithPosYaw` 画整只实体，先 `rendermanager.setRenderPosition(EntityFX.interpPosX, ...)`（MobAppearance.java:55）。layer 3、寿命 30 tick。

## 时序与生命周期

初始化：`Minecraft.loadWorld` 中 `this.effectRenderer = new EffectRenderer(this.theWorld, this.renderEngine)`（Minecraft.java:567）→ 构造器内建 4×2 空桶并 `registerVanillaParticles()`。换世界/维度时 `Minecraft` 调 `clearEffects(worldClientIn)`（Minecraft.java:2400）清空全部粒子并换绑 `worldObj`。

每 tick（主线程，`Minecraft.runTick` → `Minecraft.java:2255`）：`updateEffects()` 按层遍历调每个粒子的 `onUpdate()`，死亡的收集后 `removeAll`；随后 tick 全部 `EntityParticleEmitter`。粒子在 `onUpdate` 中可能再生成粒子（HugeExplode、Lava、Drop、Firework trail），新粒子在本 tick 内追加进正在被遍历的 list——`updateEffectAlphaLayer` 用 index for 循环 `entitiesFX.size()`（EffectRenderer.java:193），所以同 tick 追加的粒子也会被 tick 到（且不会 CME）。

每帧（主线程，`EntityRenderer.renderWorldPass`）：先 `renderLitParticles`（层 3，EntityRenderer.java:1443），再 `renderParticles`（层 0-2，EntityRenderer.java:1447）。`renderParticles` 开头把相机插值位置写进静态 `EntityFX.interpPosX/Y/Z`，全部粒子的顶点都是相对这个原点的相机相对坐标。

粒子生命周期：Factory 创建 → `addEffect` 入桶（满 4000 淘汰最旧）→ 每 tick `onUpdate`，`particleAge >= particleMaxAge` 或环境条件（出水、落地、碰液面）时 `setDead()` → 下一次 `updateEffects` 从桶移除。粒子虽然是 `Entity` 子类，但从不加入 `World` 的实体列表，只活在 `EffectRenderer` 的桶里。

线程归属：全部在客户端主线程。`NetHandlerPlayClient.handleParticles` 首行 `PacketThreadUtil.checkThreadAndEnqueue`（NetHandlerPlayClient.java:2005）把 Netty EventLoop 的调用转投主线程后才进入本包。无服务端线程参与。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void registerParticle(int id, IParticleFactory particleFactory)` | EffectRenderer.java:107 | 构造时注册原版；随时可再调 | 注册自定义粒子 id，或覆盖原版工厂替换某类粒子的实现/外观 | id 与 `EnumParticleTypes` 冲突时后注册者赢；同 id 覆盖影响所有来源（含服务器包） |
| `public EntityFX spawnEffectParticle(int particleId, double xCoord, double yCoord, double zCoord, double xSpeed, double ySpeed, double zSpeed, int... parameters)` | EffectRenderer.java:128 | 一切 `World.spawnParticle` 路径的终点（经 RenderGlobal.java:2086/2091） | 全局粒子过滤/替换/统计（反挂视觉、性能限流、粒子倍增） | 返回值被调用方使用（可能对返回的 EntityFX 继续 set*），返回 null 是合法的 |
| `public void addEffect(EntityFX effect)` | EffectRenderer.java:146 | spawnEffectParticle、addBlock*Effects、外部直接 new（Pickup/Firework） | 最底层的粒子入口：连绕过工厂的直 new 也经过这里；改 4000 上限、按类型丢弃 | 分桶依据 `getFXLayer()` 与 `getAlpha()`，入桶后改 alpha 必须走 `setAlphaF` 搬桶 |
| `public void updateEffects()` | EffectRenderer.java:159 | `Minecraft.runTick` 每 tick 一次（Minecraft.java:2255） | 整体暂停/减速粒子、tick 前后做批量处理 | tick 内粒子可自增殖；异常会 `ReportedException` 直接崩游戏 |
| `public void renderParticles(Entity entityIn, float partialTicks)` | EffectRenderer.java:239 | `EntityRenderer.renderWorldPass` 每帧（EntityRenderer.java:1447） | 关闭/替换全部常规粒子渲染；注入自绘批次；改 blend 风格 | 必须保持退出时的 GL 状态（depthMask true、blend off、alphaFunc 0.1F），否则污染后续世界渲染 |
| `public void renderLitParticles(Entity entityIn, float partialTick)` | EffectRenderer.java:327 | 同上，早于 renderParticles，lightmap 开启状态（EntityRenderer.java:1443） | 挂层 3 自绘粒子（自定义 HUD 世界内元素常放这层） | 该方法不 begin/draw；粒子必须自己 bind 纹理并 `Tessellator.getInstance().draw()` |
| `public void onUpdate()` | EntityFX.java:149 | 每 tick 每粒子 | 子类覆盖点：自定义运动学/寿命/条件死亡 | 覆盖后须自己维护 prevPos*、年龄与 setDead，否则粒子不死不动 |
| `public void renderParticle(WorldRenderer worldRendererIn, Entity entityIn, float partialTicks, float rotationX, float rotationZ, float rotationYZ, float rotationXY, float rotationXZ)` | EntityFX.java:176 | 每帧每粒子 | 子类覆盖点：自定义外观/顶点/动画帧 | 层 0-2 内只能 `pos().tex().color().lightmap().endVertex()`，顶点格式固定为 PARTICLE_POSITION_TEX_COLOR_LMAP；层 3 才允许改 GL 状态 |
| `EntityFX getEntityFX(int particleID, World worldIn, double xCoordIn, double yCoordIn, double zCoordIn, double xSpeedIn, double ySpeedIn, double zSpeedIn, int... p_178902_15_)` | IParticleFactory.java:7 | spawnEffectParticle 查表命中时 | 实现此接口即接管某 id 的粒子创建；返回 null 可吞掉该粒子 | `p_178902_15_` 语义依 id 而定（见"数据与协议"），越界访问会崩 tick |
| `public void addBlockDestroyEffects(BlockPos pos, IBlockState state)` | EffectRenderer.java:369 | 方块破坏事件（RenderGlobal.java:2283，sendBlockBreakProgress 值 2001） | 观察方块破坏（客户端侧）、替换碎屑效果 | 一次 64 个粒子，高频破坏时是主要粒子压力源 |
| `public void addBlockHitEffects(BlockPos pos, EnumFacing side)` | EffectRenderer.java:395 | 玩家持续挖掘（Minecraft.java:1506） | 观察"正在挖掘哪个面"、关闭挖掘碎屑 | 仅 `getRenderType() != -1` 的方块生成 |
| `public void emitParticleAtEntity(Entity entityIn, EnumParticleTypes particleTypes)` | EffectRenderer.java:112 | 暴击（EntityPlayerSP.java:673/678；NetHandlerPlayClient.java:890-894） | 观察本地/远程暴击事件（战斗视觉挂钩） | emitter 自身不渲染，实际粒子经 `World.spawnParticle` 再入桶 |
| `public void clearEffects(World worldIn)` | EffectRenderer.java:354 | 换世界/维度（Minecraft.java:2400） | 感知世界切换、清理挂在粒子上的自定义状态 | 调用后 `worldObj` 已指向新世界，旧粒子引用全部失效 |
| `public void setAlphaF(float alpha)` | EntityFX.java:99 | 粒子代码任意时刻 | 让粒子在透明/不透明渲染桶间迁移 | 内部经 `Minecraft.getMinecraft().effectRenderer` 单例回调搬桶——粒子未 addEffect 前调用无副作用，但依赖单例非空 |
| `public String getStatistics()` | EffectRenderer.java:466 | F3 调试界面每帧（GuiOverlayDebug.java:108/134） | 读取当前粒子总量做性能监控 | 只读，8 个桶求和 |

## 数据与协议

本包不直接编解码封包；服务器粒子经 `S2APacketParticles` → `NetHandlerPlayClient.handleParticles`（NetHandlerPlayClient.java:2003）→ `WorldClient.spawnParticle` → `RenderGlobal.spawnEntityFX` → `spawnEffectParticle` 到达。与本包直接相关的数据约定有两处：

1. `IParticleFactory.getEntityFX` 的 `int... p_178902_15_` 可变参数（来源是封包的 `getParticleArgs()`）：

| 粒子 id | 参数下标 | 类型 | 读取处 | 含义 |
|---|---|---|---|---|
| ITEM_CRACK | [0] | int | `Item.getItemById(p_178902_15_[0])`（EntityBreakingFX.java:79） | 物品 id |
| ITEM_CRACK | [1]（可选） | int | `p_178902_15_.length > 1 ? p_178902_15_[1] : 0`（EntityBreakingFX.java:78） | 物品 metadata |
| BLOCK_CRACK | [0] | int | `Block.getStateById(p_178902_15_[0])`（EntityDiggingFX.java:120） | 方块状态 id（state id，非 block id） |
| BLOCK_DUST | [0] | int | `Block.getStateById(p_178902_15_[0])`（EntityBlockDustFX.java:21） | 方块状态 id；`getRenderType() == -1` 时工厂返回 null |
| REDSTONE | — | — | 颜色不走 parameters，而是 `(float)xSpeedIn/ySpeedIn/zSpeedIn`（EntityReddustFX.java:90） | RGB 缩放系数，xSpeed==0 强制为 1（红） |
| SPELL_MOB / SPELL_MOB_AMBIENT | — | — | 同上，速度即 RGB（EntitySpellParticleFX.java:91/119） | 药水颜色 |

2. 烟花 NBT（`EntityFirework.StarterFX` 构造器读取，来源 `WorldClient.makeFireworks` 的 `NBTTagCompound compund`）：

| 字段 | 类型 | 读取方法 | 含义 |
|---|---|---|---|
| Explosions | TagList(10) | `p_i46464_15_.getTagList("Explosions", 10)`（EntityFirework.java:207） | 爆炸列表，每 2 tick 消费一条 |
| Explosions[i].Type | byte | `nbttagcompound1.getByte("Type")`（EntityFirework.java:269） | 0=小球 1=大球 2=星形 3=苦力怕脸 4=爆裂 |
| Explosions[i].Flicker | boolean | `nbttagcompound.getBoolean("Flicker")`（EntityFirework.java:221/271） | 闪烁；为 true 时 `particleMaxAge += 15` |
| Explosions[i].Trail | boolean | `nbttagcompound1.getBoolean("Trail")`（EntityFirework.java:270） | 火花拖尾 |
| Explosions[i].Colors | int[] | `nbttagcompound1.getIntArray("Colors")`（EntityFirework.java:272） | 0xRRGGBB；为空时回退 `ItemDye.dyeColors[0]`（EntityFirework.java:277） |
| Explosions[i].FadeColors | int[] | `nbttagcompound1.getIntArray("FadeColors")`（EntityFirework.java:273） | 褪色目标色 |

`EntityFX.writeEntityToNBT / readEntityFromNBT` 为空（EntityFX.java:212-221），粒子不参与实体 NBT 持久化。

## 不变量与陷阱

- fxLayers 第一维虽然长度为 4，但渲染只覆盖 0/1/3：`renderParticles` 循环 `i < 3` 处理 0-2（层 2 无原版粒子使用但会绑 particles.png 渲染），层 3 只由 `renderLitParticles` 处理。`getFXLayer()` 返回 2 的自定义粒子能被 tick 和渲染但纹理是 particles.png；返回 ≥4 会在 `addEffect` 直接 `ArrayIndexOutOfBoundsException`。
- alpha 分桶是入桶时一次性判定（`getAlpha() != 1.0F`，EffectRenderer.java:149）。入桶后直接改 `particleAlpha` 字段不会搬桶——必须走 `setAlphaF`，而它依赖 `Minecraft.getMinecraft().effectRenderer` 静态单例（EntityFX.java:103/107），意味着 EntityFX 无法脱离 Minecraft 单例做单元测试。
- `setParticleTextureIndex` 只许 layer 0、`setParticleIcon` 只许 layer 1，违反直接抛 RuntimeException（EntityFX.java:236/247）；这个异常发生在 tick 里会被 EffectRenderer 包成崩溃报告——不是被吞掉，是崩游戏。
- `EntityFX.interpPosX/Y/Z` 是静态字段，由 `renderParticles` 每帧写入（EffectRenderer.java:246-248）；`renderLitParticles` 不写它但层 3 粒子（FootStep/Pickup/MobAppearance）在读——依赖上一帧 `renderParticles` 留下的值以及两者同帧调用的顺序（EntityRenderer 先 lit 后普通，所以层 3 实际用的是上一帧的原点，误差一帧、正常不可见，但改渲染顺序时要意识到）。
- 每桶 4000 上限是"移除最旧"不是"拒绝新增"（EffectRenderer.java:151-154），刷屏式粒子攻击不会 OOM 但会让老粒子瞬间消失。
- 粒子是 `Entity` 但从不进 `World.loadedEntityList`；`moveEntity` 仍会做方块碰撞（除非 `noClip = true`）。大量非 noClip 粒子（爆炸碎屑）时碰撞检测是 tick 大头。
- 构造器里调 `onUpdate()` 的类（EntityCrit2FX.java:34、EntityParticleEmitter.java:21）在对象未完全构造时就跑一 tick——子类化它们时字段初始化顺序要小心。
- 递归生成：`EntityHugeExplodeFX.onUpdate` 每 tick spawn 6 个 EXPLOSION_LARGE（EntityHugeExplodeFX.java:32-38）、`SparkFX` trail 自增殖（EntityFirework.java:158-175）。`updateEffectAlphaLayer` 的 index 循环保证本 tick 新增也会被处理，但意味着一个 tick 内 list 会边遍历边增长——挂钩统计时不要缓存 size。
- 渲染层 0-2 的粒子共享一个 `worldrenderer.begin(...)` 批次，`renderParticle` 里绝不能碰 GL 状态或 draw；层 3 相反，必须自己 begin/draw（EntityFootStepFX.java:53/58、EntityLargeExplodeFX.java:56/61）。`renderParticles` 结束时恢复 `depthMask(true)`、`disableBlend`、`alphaFunc(516, 0.1F)`（EffectRenderer.java:322-324），自定义渲染注入也要遵守。
- LWJGL3/JDK25 移植相关：本包不直接调 LWJGL——所有 GL 经 `GlStateManager`/`OpenGlHelper`/`Tessellator` 抽象，源码与原版 MCP 1.8.9 一致（对照未发现移植性改动；`EffectRenderer.renderParticles` 里的 `final int i_f`（EffectRenderer.java:257）是为匿名类捕获而设，属反编译产物而非移植改动）。固定管线状态（alphaFunc、depthMask、lightmap 多纹理）由 shim/GlStateManager 层负责兼容。
- 泛型数组 `new List[4]`（EffectRenderer.java:36/51）是 unchecked 创建，JDK25 下仅告警，行为不变。
- 线程约束：整个包假定单线程（主线程）访问。`fxLayers` 是普通 ArrayList，任何非主线程 addEffect（例如在 Netty EventLoop 里直接调）都会与 tick/render 竞态；正确姿势是经 `PacketThreadUtil.checkThreadAndEnqueue` 或 `Minecraft.addScheduledTask` 转投。

## 交叉引用

- net.minecraft.client → `Minecraft#runTick`（每 tick 调 `EffectRenderer#updateEffects`，Minecraft.java:2255）、`Minecraft#loadWorld`（构造/`clearEffects`）、`Minecraft#getMinecraft`（EntityFX/Barrier/EntityBreakingFX/EntityDiggingFX/EntityPickupFX/EntityFootStepFX/EntityLargeExplodeFX/MobAppearance/EntityFirework 取单例服务）
- net.minecraft.client.renderer → `EntityRenderer#renderWorldPass`（每帧调 `renderLitParticles`/`renderParticles`）、`RenderGlobal#spawnEntityFX`（`World.spawnParticle` 落点 → `EffectRenderer#spawnEffectParticle`）、`RenderGlobal#sendBlockBreakProgress` 事件 2001（→ `EffectRenderer#addBlockDestroyEffects`）、`Tessellator#getInstance`、`WorldRenderer#pos/tex/color/lightmap/endVertex`、`GlStateManager#*`、`ActiveRenderInfo#getRotationX` 等（billboard 基向量）、`RenderHelper#disableStandardItemLighting`
- net.minecraft.client.renderer.texture → `TextureManager#bindTexture`、`TextureMap#locationBlocksTexture`、`TextureAtlasSprite#getMinU/getInterpolatedU`
- net.minecraft.client.renderer.vertex → `DefaultVertexFormats#PARTICLE_POSITION_TEX_COLOR_LMAP`（层 0-2 固定格式）、`VertexFormat#addElement`（EntityLargeExplodeFX 自定义格式）
- net.minecraft.client.renderer.entity → `RenderManager#renderEntityWithPosYaw`（EntityPickupFX、MobAppearance）、`RenderManager#setRenderPosition`
- net.minecraft.client.network → `NetHandlerPlayClient#handleParticles`（S2APacketParticles 入口）、`NetHandlerPlayClient#handleCollectItem`（new EntityPickupFX）、`NetHandlerPlayClient#handleAnimation`（emitParticleAtEntity）
- net.minecraft.client.multiplayer → `WorldClient#makeFireworks`（new EntityFirework.StarterFX）、`WorldClient#doVoidFogParticles`（BARRIER 等环境粒子经 spawnParticle）
- net.minecraft.client.entity → `EntityPlayerSP#onCriticalHit/#onEnchantmentCritical`（emitParticleAtEntity）
- net.minecraft.world → `World#spawnParticle`（粒子进出双向：外部生成入口 + HugeExplode/Lava/Drop/Emitter 的再生成出口）、`World#getBlockState/#getCombinedLight/#playSound/#getClosestPlayerToEntity`
- net.minecraft.entity → `Entity`（EntityFX 的父类：位置/运动/`moveEntity`/`setDead`）、`EntityLivingBase`、`net.minecraft.entity.monster.EntityGuardian#setElder`（MobAppearance）
- net.minecraft.block → `Block#getStateById/#colorMultiplier/#getRenderColor/#blockParticleGravity/#getRenderType`、`BlockLiquid#getLiquidHeightPercent/#LEVEL`、`Material#water/#lava/#isLiquid/#isSolid`
- net.minecraft.item / net.minecraft.init → `Item#getItemById/#getItemFromBlock`、`ItemDye#dyeColors`、`Blocks#barrier/#snow/#grass`、`Items#slime_ball/#snowball`
- net.minecraft.nbt → `NBTTagCompound#getTagList/getByte/getBoolean/getIntArray`、`NBTTagList#getCompoundTagAt`（烟花）
- net.minecraft.util → `EnumParticleTypes#getParticleID`（注册表键）、`BlockPos`、`MathHelper`、`ResourceLocation`、`ReportedException`
- net.minecraft.crash → `CrashReport#makeCrashReport`、`CrashReportCategory#addCrashSectionCallable`（tick/render 异常上报）
- net.minecraft.client.gui → `GuiOverlayDebug`（读 `EffectRenderer#getStatistics`）

## 覆盖声明

完整读取了 35/35 个文件（bucket 列表中的全部文件均逐行 Read，无抽样）。

逐行精读并在文中给出行号引用的类：EffectRenderer、EntityFX、EntityFirework（含 Factory/OverlayFX/SparkFX/StarterFX）、EntityDiggingFX、EntityPickupFX、EntityParticleEmitter、EntityDropParticleFX、MobAppearance、EntityBreakingFX、EntityFootStepFX、EntityLargeExplodeFX、EntityHugeExplodeFX、Barrier、IParticleFactory。

其余类（EntityAuraFX、EntityBlockDustFX、EntityBubbleFX、EntityCloudFX、EntityCrit2FX、EntityCritFX、EntityEnchantmentTableParticleFX、EntityExplodeFX、EntityFishWakeFX、EntityFlameFX、EntityHeartFX、EntityLavaFX、EntityNoteFX、EntityPortalFX、EntityRainFX、EntityReddustFX、EntitySmokeFX、EntitySnowShovelFX、EntitySpellParticleFX、EntitySplashFX、EntitySuspendFX）同样全文读取，但因结构高度模板化（构造器调参 + onUpdate/renderParticle 覆盖 + Factory），仅在类清单和相关小节中概括，未逐一展开字段级详解。

外部调用方（Minecraft、EntityRenderer、RenderGlobal、NetHandlerPlayClient、WorldClient、EntityPlayerSP、GuiOverlayDebug、World）仅做了针对性 grep 与片段阅读以确认行号，不属于本 bucket。
