---
area: net/minecraft/client
slug: mc-client
files: 42
lines: 10461
tier: A
---

# net/minecraft/client 架构笔记

## 定位

这是客户端的"根包"：进程入口（`main.Main`）、游戏主类与主循环（`Minecraft`）、客户端玩家实体（`entity.EntityPlayerSP` / `EntityOtherPlayerMP`）、设置与按键（`settings.GameSettings` / `KeyBinding`）、声音子系统（`audio.*`）、后处理着色器管线（`shader.*`）以及加载画面（`LoadingScreenRenderer`）。

- 谁调用它：JVM 启动器调用 `Main.main`；此后一切都从 `Minecraft.run()` 的 while 循环发出。渲染包（`client.renderer.*`）、GUI 包（`client.gui.*`）、网络包（`client.network.*`、`client.multiplayer.*`）全部通过 `Minecraft.getMinecraft()` 单例反向依赖本包。
- 它调用谁：`client.renderer`（EntityRenderer/RenderGlobal/GlStateManager/Tessellator）、`client.gui`（各 GuiScreen）、`client.multiplayer.PlayerControllerMP`、`client.network.NetHandlerPlayClient`、`network.play.client.C0x*` 封包、`server.integrated.IntegratedServer`、`paulscode.sound`（声音引擎）、以及 lwjgl2-shim 提供的 `org.lwjgl.*`（Display/Keyboard/Mouse/Sys 等，底层已是 LWJGL 3/GLFW）。
- 如果它消失：没有主循环、没有 tick、没有输入分发、没有玩家封包上行——整个客户端不存在。`Minecraft` 是所有子系统的组合根（composition root）。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| AnvilConverterException | 9 | extends Exception | 存档格式转换失败时抛出的受检异常 |
| ClientBrandRetriever | 9 | — | 返回客户端品牌字符串 `"vanilla"` |
| LoadingScreenRenderer | 221 | implements IProgressUpdate | 世界加载/保存时的进度条画面，节流 100ms 重绘一次 |
| Minecraft | 3318 | implements IThreadListener, IPlayerUsage | 游戏主类：主循环、tick、输入分发、世界装卸、所有子系统的持有者 |
| audio/GuardianSound | 38 | extends MovingSound | 守卫者激光攻击音效，随蓄力值调整 volume/pitch |
| audio/ISound | 42 | interface | 可播放声音的抽象（位置、音量、音高、循环、衰减类型） |
| audio/ISoundEventAccessor | 8 | interface（泛型 T） | 带权重的声音条目访问器：`getWeight()` / `cloneEntry()` |
| audio/ITickableSound | 8 | extends ISound, ITickable | 每 tick 更新的声音，附加 `isDonePlaying()` |
| audio/MovingSound | 18 | extends PositionedSound implements ITickableSound | 会移动的声音基类，持有 `donePlaying` 标志 |
| audio/MovingSoundMinecart | 48 | extends MovingSound | 矿车行驶音效，跟随矿车位置与速度 |
| audio/MovingSoundMinecartRiding | 46 | extends MovingSound | 玩家乘坐矿车时的内部音效（AttenuationType.NONE） |
| audio/MusicTicker | 102 | implements ITickable | 背景音乐调度器：按 MusicType 的 min/max 延迟随机播放 |
| audio/PositionedSound | 68 | implements ISound（abstract） | 固定位置声音的字段载体基类 |
| audio/PositionedSoundRecord | 39 | extends PositionedSound | 一次性声音的便捷工厂（create 三个重载） |
| audio/SoundCategory | 56 | enum | 9 个声音分类（master/music/…），静态双 map 校验 id/name 无冲突 |
| audio/SoundEventAccessor | 23 | implements ISoundEventAccessor&lt;SoundPoolEntry&gt; | 单个 SoundPoolEntry + 权重的包装 |
| audio/SoundEventAccessorComposite | 80 | implements ISoundEventAccessor&lt;SoundPoolEntry&gt; | 按权重随机抽取子条目的组合声音事件 |
| audio/SoundHandler | 288 | implements IResourceManagerReloadListener, ITickable | 声音门面：解析 sounds.json、注册表、转发到 SoundManager |
| audio/SoundList | 136 | — | sounds.json 单条事件的反序列化 POJO（含内部类 SoundEntry / Type） |
| audio/SoundListSerializer | 82 | implements JsonDeserializer&lt;SoundList&gt; | sounds.json 的 Gson 反序列化器 |
| audio/SoundManager | 543 | — | paulscode SoundSystem 的封装：播放/暂停/音量/监听者位置 |
| audio/SoundPoolEntry | 57 | — | 一个 .ogg 资源 + pitch/volume/streaming 标志 |
| audio/SoundRegistry | 30 | extends RegistrySimple&lt;ResourceLocation, SoundEventAccessorComposite&gt; | 声音事件注册表，可整表清空 |
| entity/AbstractClientPlayer | 145 | extends EntityPlayer（abstract） | 客户端玩家共同基类：皮肤/披风/NetworkPlayerInfo/FOV 修正 |
| entity/EntityOtherPlayerMP | 175 | extends AbstractClientPlayer | 其他玩家的客户端实体：服务端位置插值（每 tick 1/n 逼近） |
| entity/EntityPlayerSP | 910 | extends AbstractClientPlayer | 本地玩家：移动封包上行、疾跑/飞行逻辑、GUI 打开入口 |
| main/GameConfiguration | 96 | — | 启动参数的不可变载体（User/Display/Folder/Game/Server 五组） |
| main/Main | 120 | — | 进程入口：joptsimple 解析命令行 → new Minecraft(...).run() |
| player/inventory/ContainerLocalMenu | 62 | extends InventoryBasic implements ILockableContainer | 服务端容器在客户端的本地占位（field 值存 Map） |
| player/inventory/LocalBlockIntercommunication | 53 | implements IInteractionObject | 只带 guiID 与显示名的交互对象占位（createContainer 抛 UnsupportedOperationException） |
| settings/GameSettings | 1406 | — | options.txt 的读写、所有图形/声音/按键/聊天设置、C15 设置封包上行 |
| settings/KeyBinding | 151 | implements Comparable&lt;KeyBinding&gt; | 按键绑定：keyCode→binding 的静态 IntHashMap，pressed/pressTime 双状态 |
| shader/Framebuffer | 280 | — | FBO 封装：创建/绑定/清空/全屏 blit（framebufferRender） |
| shader/Shader | 117 | — | 一个后处理 pass：in/out framebuffer + ShaderManager + aux targets |
| shader/ShaderDefault | 47 | extends ShaderUniform | 所有 set 均为空操作的哨兵 uniform（uniform 不存在时返回它） |
| shader/ShaderGroup | 407 | — | 解析 shaders/post/*.json，管理 targets/passes，逐 pass 执行 |
| shader/ShaderLinkHelper | 58 | — | GL program 的创建/链接/删除（静态单例） |
| shader/ShaderLoader | 137 | — | 编译 .vsh/.fsh，按文件名缓存并引用计数 |
| shader/ShaderManager | 420 | — | 一个 GL program：解析 program json、samplers/uniforms/blend、useShader/endShader |
| shader/ShaderUniform | 322 | — | 单个 uniform 的 CPU 侧缓冲与 upload（int/float/matrix 分派） |
| util/JsonBlendingMode | 198 | — | shader json 的 "blend" 段 → GL 混合状态（带全局去重缓存） |
| util/JsonException | 88 | extends IOException | 带 "文件→路径链" 上下文的 JSON 解析异常 |

## 核心类详解

### Minecraft（Minecraft.java）

单例：`private static Minecraft theMinecraft`（Minecraft.java:210），`public static Minecraft getMinecraft()`（Minecraft.java:2777）。构造器 `public Minecraft(GameConfiguration gameConfig)`（Minecraft.java:366）只做字段赋值 + `Bootstrap.register()`（Minecraft.java:397），真正初始化在 `startGame()`。

关键字段（均在 Minecraft.java:190-364 区间）：
- `public WorldClient theWorld`（:227）、`public EntityPlayerSP thePlayer`（:232）、`private Entity renderViewEntity`（:233）——世界/玩家/摄像机实体三元组。
- `public PlayerControllerMP playerController`（:211）——所有方块交互/攻击的下行入口。
- `public GuiScreen currentScreen`（:244）——当前 GUI，null 表示游戏内。
- `public GameSettings gameSettings`（:271）、`public GuiIngame ingameGUI`（:262）、`public EntityRenderer entityRenderer`（:246）、`public RenderGlobal renderGlobal`（:228）、`public EffectRenderer effectRenderer`（:235）。
- `private Timer timer = new Timer(20.0F)`（:223）——20 tick/s 的固定步进计时器。
- `private Framebuffer framebufferMc`（:327）——主 FBO，一切先渲到它再 blit 上屏。
- `private final Queue < FutureTask<? >> scheduledTasks`（:334）+ `private final Thread mcThread = Thread.currentThread()`（:336）——跨线程任务队列（IThreadListener 实现）。
- `volatile boolean running = true`（:347）；`public static byte[] memoryReserve = new byte[10485760]`（:195）——OOM 时释放的 10MiB 保底内存。
- `private IntegratedServer theIntegratedServer`（:258）、`private NetworkManager myNetworkManager`（:310）。

关键方法：
- `public void run()`（Minecraft.java:400）——`startGame()` 后进入 `while (this.running)` 循环调 `runGameLoop()`；捕获 `OutOfMemoryError`（freeMemory + GuiMemoryErrorScreen）、`MinecraftError`（静默退出）、`ReportedException`/`Throwable`（写崩溃报告）；finally 走 `shutdownMinecraftApplet()`（:1044）。
- `private void startGame() throws LWJGLException, IOException`（Minecraft.java:473)——完整初始化序列，见"时序与生命周期"。
- `private void runGameLoop() throws IOException`（Minecraft.java:1078)——每帧一次；细节见时序节。
- `public void runTick() throws IOException`（Minecraft.java:1736)——每逻辑 tick 一次；输入分发、GUI tick、世界 tick 全在这里。
- `public void displayGuiScreen(GuiScreen guiScreenIn)`（Minecraft.java:981)——GUI 切换唯一入口：先 `currentScreen.onGuiClosed()`，null+无世界→GuiMainMenu，null+死亡→GuiGameOver；新屏调 `setWorldAndResolution`；传 null 时 `resumeSounds()` + `setIngameFocus()`。
- `public void loadWorld(WorldClient worldClientIn, String loadingMessage)`（Minecraft.java:2349)——世界装卸唯一入口：null 入参时 cleanup NetHandler、关闭 IntegratedServer、`stopSounds()`；非 null 时经 `playerController.func_178892_a` 创建 `thePlayer` 并 `spawnEntityInWorld`。
- `public void launchIntegratedServer(String folderName, String worldName, WorldSettings worldSettingsIn)`（Minecraft.java:2271)——单机进入世界：起服务端线程，忙等 `serverIsInRunLoop()`，然后 `NetworkManager.provideLocalClient(socketaddress)` 走本地管道发 `C00Handshake(47, ...)` + `C00PacketLoginStart`。
- `public void setDimensionAndSpawnPlayer(int dimension)`（Minecraft.java:2425)——维度切换时重建 `thePlayer` 并迁移 DataWatcher/EntityId/ClientBrand。
- 输入三连：`private void clickMouse()`（:1517，左键攻击/挖掘）、`private void rightClickMouse()`（:1565，右键交互/放置，`rightClickDelayTimer = 4`）、`private void middleClickMouse()`（:2494，选取方块，创造模式 Ctrl 时经 `pickBlockWithNBT`（:2639）带 NBT）。
- `private void sendClickBlockToController(boolean leftClick)`（Minecraft.java:1491)——持续挖掘：转发到 `playerController.onPlayerDamageBlock`。
- `public void dispatchKeypresses()`（Minecraft.java:3115)——全屏(F11)/截图(F2)/推流键的全局处理（不经 KeyBinding 的 isPressed 队列）。
- 线程调度：`public <V> ListenableFuture<V> addScheduledTask(Callable<V> callableToSchedule)`（:3221）、`public ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule)`（:3248）、`public boolean isCallingFromMinecraftThread()`（:3254）——Netty EventLoop 上收到的包处理会通过它切回主线程。
- `public NetHandlerPlayClient getNetHandler()`（Minecraft.java:2468)——即 `this.thePlayer.sendQueue`，玩家为 null 时返回 null。
- `public MusicTicker.MusicType getAmbientMusicType()`（Minecraft.java:3105)——按维度/boss/创造态选 BGM 类型。
- `public static long getSystemTime()`（Minecraft.java:3023)——`Sys.getTime() * 1000L / Sys.getTimerResolution()`，经 shim 映射到 GLFW 计时。
- 移植点：`private void initStream()`（Minecraft.java:611-617）不再尝试构造 TwitchStream，直接 `this.stream = new NullStream(null);`（源码注释说明 Twitch SDK 已死、natives 已移除）。窗口标题 `Display.setTitle("Minecraft 1.8.9")`（:622）。

### EntityPlayerSP（entity/EntityPlayerSP.java）

`public class EntityPlayerSP extends AbstractClientPlayer`（EntityPlayerSP.java:55）。本地玩家 = 客户端预测 + 封包上行。

关键字段：`public final NetHandlerPlayClient sendQueue`（:57）、`private double lastReportedPosX/Y/Z`（:64-76）、`private float lastReportedYaw/lastReportedPitch`（:82-88）、`private boolean serverSneakState/serverSprintState`（:91-94）、`private int positionUpdateTicks`（:100）、`public MovementInput movementInput`（:103）、`protected Minecraft mc`（:104）、`public int sprintingTicksLeft`（:114）、`public float timeInPortal / prevTimeInPortal`（:123-126）。

- `public void onUpdate()`（EntityPlayerSP.java:168）——先检查 `this.worldObj.isBlockLoaded(new BlockPos(this.posX, 0.0D, this.posZ))` 才 tick；骑乘时发 `C03PacketPlayer.C05PacketPlayerLook` + `C0CPacketInput`，否则进 `onUpdateWalkingPlayer()`。
- `public void onUpdateWalkingPlayer()`（EntityPlayerSP.java:189）——**移动封包核心**：疾跑/潜行状态变化发 `C0BPacketEntityAction`；位移平方 > 9.0E-4D 或 `positionUpdateTicks >= 20` 判定 flag2，视 flag2/flag3 组合发 `C06PacketPlayerPosLook` / `C04PacketPlayerPosition` / `C05PacketPlayerLook` / `C03PacketPlayer(onGround)` 四选一（:237-249）。
- `public void onLivingUpdate()`（EntityPlayerSP.java:715）——疾跑计时、传送门渐变（`timeInPortal += 0.0125F`）、`movementInput.updatePlayerMoveState()`、双击 W 疾跑（`sprintToggleTimer = 7`）、飞行切换（双击跳，`flyToggleTimer`）、马跳蓄力，最后 `super.onLivingUpdate()`。
- `public void sendChatMessage(String message)`（:296）→ `C01PacketChatMessage`；`public void swingItem()`（:304）→ `C0APacketAnimation`；`public EntityItem dropOneItem(boolean dropAll)`（:279）→ `C07PacketPlayerDigging(DROP_ALL_ITEMS|DROP_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN)`，返回 null；`public void respawnPlayer()`（:310）→ `C16PacketClientStatus(PERFORM_RESPAWN)`；`public void sendPlayerAbilities()`（:394）→ `C13PacketPlayerAbilities`。
- `public void closeScreen()`（:330）——发 `C0DPacketCloseWindow(this.openContainer.windowId)` 再 `closeScreenAndDropStack()`（:336，清手上物品栈 + `mc.displayGuiScreen((GuiScreen)null)`）。
- `public void setPlayerSPHealth(float health)`（:346）——服务端血量下发的本地对账（S06 处理路径调用），首次直接 set（`hasValidHealth` 标志）。
- GUI 工厂族：`displayGUIChest(IInventory chestInventory)`（:606，按 guiID 分发 GuiChest/GuiHopper/GuiFurnace/GuiBrewingStand/GuiBeacon/GuiDispenser）、`displayGui(IInteractionObject guiOwner)`（:645，crafting_table/enchanting_table/anvil）、`displayGUIHorse`（:640）、`displayVillagerTradeGui`（:663）、`openEditSign`（:580）、`openEditCommandBlock`（:585）、`displayGUIBook`（:593）。这些由 NetHandlerPlayClient 收到 S2D 等包后调用。
- `protected boolean pushOutOfBlocks(double x, double y, double z)`（:437）——卡墙推出逻辑；`protected boolean isCurrentViewEntity()`（:706）`return this.mc.getRenderViewEntity() == this;`。
- `public boolean attackEntityFrom(DamageSource source, float amount)`（:140）恒返回 false，`public void heal(float healAmount)`（:148）空——伤害与治疗全由服务端权威。

### EntityOtherPlayerMP（entity/EntityOtherPlayerMP.java）

- `public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean p_180426_10_)`（EntityOtherPlayerMP.java:39）——网络层写入插值目标。
- `public void onLivingUpdate()`（:86）——每 tick 向目标位置逼近 1/increments 并递减计数（:88-110）。`public void onUpdate()`（:52）处理肢体摆动与进食动画。`public boolean attackEntityFrom(...)`（:34）恒 true（允许受击动画）。构造器设 `this.noClip = true`（:26）——其他玩家不做本地碰撞。

### AbstractClientPlayer（entity/AbstractClientPlayer.java）

- `protected NetworkPlayerInfo getPlayerInfo()`（AbstractClientPlayer.java:47）——懒加载缓存 `Minecraft.getMinecraft().getNetHandler().getPlayerInfo(this.getUniqueID())`。
- `public boolean isSpectator()`（:33）查 tab 列表的 GameType；`public ResourceLocation getLocationSkin()`（:69）/ `getLocationCape()`（:75）/ `getSkinType()`（:103）皮肤三件套，无 info 时回落 `DefaultPlayerSkin`。
- `public static ThreadDownloadImageData getDownloadImageSkin(ResourceLocation resourceLocationIn, String username)`（:81）——注意仍是 `http://skins.minecraft.net/MinecraftSkins/%s.png`（:88），该服务已停用。
- `public float getFovModifier()`（:109）——飞行 ×1.1、移速属性、拉弓减 FOV。

### GameSettings（settings/GameSettings.java）

- 构造器 `public GameSettings(Minecraft mcIn, File optionsFileIn)`（GameSettings.java:184）：拼 `keyBindings` 数组、按 `mcIn.isJava64bit() && Runtime.getRuntime().maxMemory() >= 1000000000L` 决定 `RENDER_DISTANCE.setValueMax(32.0F)` 或 16（:195-202），然后 `loadOptions()`。另有无参构造器（:208）供无 Minecraft 环境使用（不 load）。
- 静态输入查询：`public static boolean isKeyDown(KeyBinding key)`（:233）——`key.getKeyCode() < 0` 走 `Mouse.isButtonDown(key.getKeyCode() + 100)`，否则 `Keyboard.isKeyDown(...)`；`public static String getKeyDisplayString(int key)`（:223）。
- `public void setOptionFloatValue(GameSettings.Options settingsOption, float value)`（:256）/ `public void setOptionValue(GameSettings.Options settingsOption, int value)`（:368）——GUI 滑条/按钮的写入口，带副作用（如 MIPMAP_LEVELS 触发 `scheduleResourcesRefresh()`（:318）、USE_VBO/GRAPHICS/AO 触发 `renderGlobal.loadRenderers()`、ENABLE_VSYNC 直接 `Display.setVSyncEnabled`（:492））；`setOptionValue` 末尾必 `saveOptions()`（:522）。
- `public void loadOptions()`（:687）——逐行 `s.split(":")` 解析 options.txt；`fov` 存储值×40+70（:713）；`key_<desc>` 与 `soundCategory_<name>`、`modelPart_<name>` 循环匹配（:1033-1055）；坏行只 warn 跳过（:1059）；结束时 `KeyBinding.resetKeyBindingArrayAndHash()`（:1063）。
- `public void saveOptions()`（:1085）——全量重写 options.txt，clouds 序列化为 false/fast/true（:1106-1118）；**末尾总是 `this.sendSettingsToServer()`**（:1186）。
- `public void sendSettingsToServer()`（:1203）——`thePlayer != null` 时发 `C15PacketClientSettings(this.language, this.renderDistanceChunks, this.chatVisibility, this.chatColours, i)`（i 为模型部件掩码）。
- 声音音量：`public float getSoundLevel(SoundCategory sndCategory)`（:1189，缺省 1.0F）、`public void setSoundLevel(SoundCategory sndCategory, float soundLevel)`（:1194，先转发 SoundHandler 再存 map）。
- 内嵌 `public static enum Options`（:1267）——每项带 enumFloat/enumBoolean/valueMin/valueMax/valueStep，`normalizeValue`/`denormalizeValue`/`snapToStepClamp`（:1380-1394）服务滑条 GUI。

### KeyBinding（settings/KeyBinding.java）

全静态注册：构造器（KeyBinding.java:73）把自身加进 `keybindArray`、`hash`（IntHashMap，keyCode→binding）、`keybindSet`（类别名）。
- `public static void onTick(int keyCode)`（:24）——按下事件时 `++keybinding.pressTime`（事件计数，供 `isPressed()` 消费）。
- `public static void setKeyBindState(int keyCode, boolean pressed)`（:37）——持续按住状态。
- `public boolean isKeyDown()`（:87）返回 `pressed`；`public boolean isPressed()`（:101）——`pressTime` 递减一次返回 true（队列语义，`while (isPressed())` 是标准消费写法）。
- `public static void unPressAllKeys()`（:50）——失焦时由 `Minecraft.setIngameNotInFocus()`（Minecraft.java:1469）调用。
- `public static void resetKeyBindingArrayAndHash()`（:58）——改键后重建 hash；`GameSettings.loadOptions` 和控制界面都会调。
- 鼠标键的约定：keyCode 为负（-100 左键、-99 右键、-98 中键），事件侧 `Mouse` 按钮号 `i - 100` 转换（Minecraft.java:1836）。

### SoundHandler（audio/SoundHandler.java）

- `public SoundHandler(IResourceManager manager, GameSettings gameSettingsIn)`（SoundHandler.java:53）持有 `SoundRegistry sndRegistry`（:49）与 `SoundManager sndManager`（:50）。
- `public void onResourceManagerReload(IResourceManager resourceManager)`（:59）——重载声音系统 + 清注册表，然后遍历每个资源域的 `sounds.json`，经 `GSON`（:32，注册了 `SoundListSerializer`）解析为 `Map<String, SoundList>` 再 `loadSoundResource`。
- `private void loadSoundResource(ResourceLocation location, SoundList sounds)`（:108）——按 `canReplaceExisting()` 决定并入或替换；FILE 型条目会实际 open 一次 `.ogg` 验证存在（:143），SOUND_EVENT 型生成延迟解引用的匿名 `ISoundEventAccessor`（:164-177）。
- 播放门面：`public void playSound(ISound sound)`（:196）、`public void playDelayedSound(ISound sound, int delay)`（:204）、`public void stopSound(ISound p_147683_1_)`（:252）、`public boolean isSoundPlaying(ISound sound)`（:284）、`public void setListener(EntityPlayer player, float p_147691_2_)`（:209）、`pauseSounds/stopSounds/resumeSounds/unloadSounds`（:214-240）。
- `public void update()`（:232）——每 tick 由 `Minecraft.runTick()`（Minecraft.java:2213）调用，转发 `sndManager.updateAllSounds()`。
- `public void setSoundLevel(SoundCategory category, float volume)`（:242）——MASTER 且 volume<=0 时直接 `stopSounds()`。
- `public static final SoundPoolEntry missing_sound`（:48）——空事件的哨兵。

### SoundManager（audio/SoundManager.java）

paulscode SoundSystem 的适配层。字段：`playingSounds`（HashBiMap，channel 名→ISound，:57）、`invPlayingSounds`（反向，:58）、`playingSoundPoolEntries`、`categorySounds`（Multimap）、`tickableSounds`、`delayedSounds`、`playingSoundsStopTime`（:59-63）、`private int playTime`（:56）。

- 构造器（SoundManager.java:65）注册 `LibraryLWJGLOpenAL` 与 `CodecJOrbis`（:78-79）。
- `private synchronized void loadSoundSystem()`（:96）——**在名为 "Sound Library Loader" 的新线程里**构造 `SoundSystemStarterThread` 并设主音量（:102-136）；失败则把 MASTER 音量清零并存盘。
- `public void playSound(ISound p_sound)`（:335）——查 `sndHandler.getSound(...)` → `cloneEntry()` 随机抽样 → 音量为 0 则跳过 → channel 名 `MathHelper.getRandomUuid(ThreadLocalRandom.current()).toString()`（:381，移植点：`java.util.concurrent.ThreadLocalRandom`，:9）→ streaming 走 `newStreamingSource` 否则 `newSource`（:383-390）→ `setPitch/setVolume/play`，登记 `playingSoundsStopTime.put(s, this.playTime + 20)`（:396）。
- `public void updateAllSounds()`（:220）——`++playTime`；先 tick 所有 `ITickableSound`（done 则停，否则同步 volume/pitch/position 到声道）；再扫描 `playingSounds` 移除已停声道（repeat 且 repeatDelay>0 的转入 `delayedSounds`）；最后触发到期的 delayedSounds。
- `public void setListener(EntityPlayer player, float p_148615_2_)`（:497）——用插值后的眼睛位置与朝向调 `setListenerPosition` / `setListenerOrientation`（每帧调用，不是每 tick）。
- `private float getNormalizedVolume(ISound sound, SoundPoolEntry entry, SoundCategory category)`（:427，clamp 后乘分类音量）、`private float getNormalizedPitch(ISound sound, SoundPoolEntry entry)`（:419，clamp [0.5, 2.0]）。
- `private static URL getURLForSoundResource(final ResourceLocation p_148612_0_)`（:464）——伪协议 `mcsounddomain:<domain>:<path>`，URLStreamHandler 的 `getInputStream()` 直接读资源管理器（:478）。**此流会在 paulscode 的解码线程被读取。**
- 内部类 `class SoundSystemStarterThread extends SoundSystem`（:521）覆写 `playing(String)` 加 `SoundSystemConfig.THREAD_SYNC` 同步（:529）。

### MusicTicker（audio/MusicTicker.java）

- `public void update()`（MusicTicker.java:24）——每 tick：类型变了就 stop 并重置短延迟；当前曲结束后随机 `timeUntilNextMusic ∈ [minDelay, maxDelay]`；倒计时归零调 `func_181558_a`（:49，play 并把 `timeUntilNextMusic = Integer.MAX_VALUE`）。`func_181557_a()`（:56）强制停止。
- `public static enum MusicType`（:66）——MENU(20,600)/GAME(12000,24000)/CREATIVE(1200,3600)/CREDITS/NETHER/END_BOSS(0,0)/END，由 `Minecraft.getAmbientMusicType()`（Minecraft.java:3105）选择。

### Framebuffer（shader/Framebuffer.java）

- `public Framebuffer(int p_i45078_1_, int p_i45078_2_, boolean p_i45078_3_)`（Framebuffer.java:25）——第三参 useDepth；构造即 `createBindFramebuffer`。
- `public void createBindFramebuffer(int width, int height)`（:39）——FBO 不可用时仅记录尺寸；否则删旧建新 + `checkFramebufferComplete()`（:142，四种 INCOMPLETE 直接 throw RuntimeException）。
- `public void createFramebuffer(int width, int height)`（:89）——`GL_RGBA8` 纹理 + 可选 `glRenderbufferStorage(GL_RENDERBUFFER, 33190, ...)` 深度（33190 = GL_DEPTH_COMPONENT24）。
- `public void bindFramebuffer(boolean p_147610_1_)`（:187，true 时顺带 viewport）、`public void unbindFramebuffer()`（:200）、`bindFramebufferTexture/unbindFramebufferTexture`（:171/:179）。
- `public void framebufferRender(int p_147615_1_, int p_147615_2_)`（:216）→ `public void framebufferRenderExt(int p_178038_1_, int p_178038_2_, boolean p_178038_3_)`（:221）——正交投影画全屏 quad 把 FBO 纹理 blit 上屏；每帧末尾由 `runGameLoop`（Minecraft.java:1167）调用。
- `public void framebufferClear()`（:265）——用 `framebufferColor` 清色，useDepth 时并清深度。
- `public void setFramebufferFilter(int p_147607_1_)`（:128）——9728 = GL_NEAREST。

### ShaderGroup / Shader / ShaderManager / ShaderLoader / ShaderUniform（shader/*）

后处理管线（对应 super secret settings / 旁观者视角着色器、实体发光）：
- `public ShaderGroup(TextureManager p_i1050_1_, IResourceManager p_i1050_2_, Framebuffer p_i1050_3_, ResourceLocation p_i1050_4_) throws JsonException, IOException, JsonSyntaxException`（ShaderGroup.java:41）——`parseGroup`（:54）读 json 的 `targets`（`initTarget`，:121，创建命名 Framebuffer）与 `passes`（`parsePass`，:143，含 auxtargets：可以是 target 名或 `textures/effect/<id>.png` 纹理）。
- `public void loadShaderGroup(float partialTicks)`（ShaderGroup.java:375）——维护 0..20 的循环时间 `field_148036_j`，逐 `shader.loadShader(this.field_148036_j / 20.0F)`。EntityRenderer 每帧调用。
- `public void createBindFramebuffers(int width, int height)`（ShaderGroup.java:358）——窗口 resize 时重建与主 FBO 同尺寸的 target。
- `public Shader addShader(String p_148023_1_, Framebuffer p_148023_2_, Framebuffer p_148023_3_) throws JsonException, IOException`（ShaderGroup.java:338）。
- `public void loadShader(float p_148042_1_)`（Shader.java:64）——一个 pass 的执行：解绑 in、绑 sampler（`DiffuseSampler` = framebufferIn，:71）、设 `ProjMat/InSize/OutSize/Time/ScreenSize` uniform（:79-84）、`manager.useShader()`、清并绑 out、画全屏 quad、`endShader()`。
- `public ShaderManager(IResourceManager resourceManager, String programName) throws JsonException, IOException`（ShaderManager.java:49）——读 `shaders/program/<name>.json`：`vertex`/`fragment`/`samplers`/`attributes`/`uniforms`/`blend`/`cull`，`ShaderLoader.loadShader` 编译两个 stage，`createProgram` + `linkProgram`，`setupUniforms()`（:272，glGetUniformLocation，找不到的 uniform 仅 warn）。
- `public void useShader()`（ShaderManager.java:191）——应用 blend、glUseProgram（带 `currentProgram` 静态去重，:197）、cull 开关、逐 sampler 绑纹理（支持 Framebuffer/ITextureObject/Integer 三种对象，:221-231）、逐 uniform `upload()`。`public void endShader()`（:174）复位。
- `public ShaderUniform getShaderUniform(String p_147991_1_)`（:256，可返回 null）与 `public ShaderUniform getShaderUniformOrDefault(String p_147984_1_)`（:264，回落 `ShaderDefault` 空对象——外部代码可无条件 set）。
- `public static ShaderLoader loadShader(IResourceManager resourceManager, ShaderLoader.ShaderType type, String filename) throws IOException`（ShaderLoader.java:53）——按文件名在 `ShaderType.loadedShaders` 缓存；编译失败抛带 info log 的 JsonException（:69-75）。`deleteShader`（:37）引用计数归零才真正 glDeleteShader。
- `public void upload()`（ShaderUniform.java:227）——按 `uniformType` 分派：0-3 int 向量、4-7 float 向量、8-10 矩阵（`glUniformMatrix2/3/4` 皆 transpose=true，:311-319）。`public static int parseType(String p_148085_0_)`（:55）："int"→0、"float"→4、"matrix4x4"→10。
- `ShaderLinkHelper`：静态单例（`setNewStaticShaderLinkHelper`，ShaderLinkHelper.java:14，由渲染初始化侧调用）；`public int createProgram() throws JsonException`（:31）、`public void linkProgram(ShaderManager manager) throws IOException`（:45，链接失败只 warn 不 throw）。

### LoadingScreenRenderer（LoadingScreenRenderer.java）

- 实现 `IProgressUpdate`；`public void setLoadingProgress(int progress)`（LoadingScreenRenderer.java:119）——**同步阻塞式渲染**：距上次 >=100ms 才画（:132），画背景砖纹 + 进度条 + 两行文字，然后 `this.mc.updateDisplay()`（:204）——因此世界加载期间窗口仍响应。
- `private void displayString(String message)`（:61）——`!this.mc.running` 且未成功时 `throw new MinecraftError()`（:69）：用户关窗时借异常从加载代码栈里逃逸，被 `Minecraft.run()` 的 `catch (MinecraftError var12)`（Minecraft.java:441）吞掉。
- 每次窗口 resize 会整个重建（`Minecraft.resize`，Minecraft.java:1711）。

### Main / GameConfiguration（main/*）

- `public static void main(String[] p_main_0_)`（Main.java:22）——`System.setProperty("java.net.preferIPv4Stack", "true")`；joptsimple 定义 `--server/--port/--gameDir/--assetsDir/--username/--uuid/--accessToken(必填)/--version(必填)/--width(854)/--height(480)` 等；SOCKS 代理与 `Authenticator`（:78）；组装 `GameConfiguration`（:104）；注册 "Client Shutdown Thread" 关机钩子调 `Minecraft.stopIntegratedServer()`（:105-111）；`Thread.currentThread().setName("Client thread")`（:112）后 `(new Minecraft(gameconfiguration)).run()`（:113）——主循环就跑在 main 线程上。
- `GameConfiguration`（GameConfiguration.java:8）五个不可变内部类：`UserInformation`（Session/PropertyMap×2/Proxy，:81）、`DisplayInformation`（width/height/fullscreen/checkGlErrors，:25）、`FolderInformation`（mcDataDir/resourcePacksDir/assetsDir/assetIndex，:41）、`GameInformation`（isDemo/version，:57）、`ServerInformation`（serverName/serverPort，:69）。

### player/inventory 两个占位类

- `ContainerLocalMenu`（ContainerLocalMenu.java:13）——`getField/setField/getFieldCount`（:24-37）把服务端下发的容器进度值（如熔炉燃烧时间）存 `Map<Integer, Integer> field_174895_b`（:16）；`createContainer` 抛 `UnsupportedOperationException`（:60）。NetHandlerPlayClient 打开窗口（S2D）时构造它交给 GUI 显示。
- `LocalBlockIntercommunication`（LocalBlockIntercommunication.java:9）——仅携带 `guiID` + `displayName` 的 `IInteractionObject`，同样 `createContainer` 抛异常（:22）。

## 时序与生命周期

**全部在主线程（"Client thread"）**，除非特别标注。

1. 进程启动：`Main.main`（Main.java:22）→ 解析参数 → `new Minecraft(gameConfig)`（构造器只赋字段 + `Bootstrap.register()`）→ `Minecraft.run()`（Minecraft.java:400）。
2. `startGame()`（Minecraft.java:473）初始化顺序（重要，顺序敏感）：
   `new GameSettings`（内部 loadOptions）→ `startTimerHackThread()`（:477，daemon 保活线程）→ `setWindowIcon`/`setInitialDisplayMode`/`createDisplay`（建 GL 上下文，shim → GLFW）→ `OpenGlHelper.initializeTextures()` → `new Framebuffer(displayWidth, displayHeight, true)`（主 FBO，:490）→ 注册 metadata serializers → `ResourcePackRepository` → `SimpleReloadableResourceManager` → `LanguageManager` → `refreshResources()`（:497，第一次全量资源重载）→ `TextureManager` → `drawSplashScreen`（:500，Mojang logo）→ `initStream()`（NullStream）→ `SkinManager` → `AnvilSaveConverter` → **`SoundHandler`（:504，注册为 reload listener，此时才会解析 sounds.json 并起声音线程）** → `MusicTicker` → 两个 `FontRenderer` → `MouseHelper` → GL 基础状态 → `TextureMap`（方块图集）→ `ModelManager` → `RenderItem`/`RenderManager`/`ItemRenderer` → `EntityRenderer` → `BlockRendererDispatcher` → `RenderGlobal` → `GuiAchievement` → `EffectRenderer` → `GuiIngame` → 显示 `GuiMainMenu`（或 `--server` 时 `GuiConnecting`，:571-578）→ `LoadingScreenRenderer` → vsync → `renderGlobal.makeEntityOutlineShader()`。
3. 每帧（`runGameLoop`，Minecraft.java:1078）：
   - 关窗检测 → `timer.updateTimer()`（暂停时冻结 `renderPartialTicks`，:1088-1097）→ 排空 `scheduledTasks`（:1101，**其他线程投递的任务在此执行**）→ `for (int j = 0; j < this.timer.elapsedTicks; ++j) this.runTick();`（:1113，0..n 个 tick）→ `mcSoundHandler.setListener(thePlayer, renderPartialTicks)`（:1122）→ 绑主 FBO → `entityRenderer.updateCameraAndRender(renderPartialTicks, i)`（:1141，**整个世界+GUI 渲染入口**）→ 调试饼图 → `guiAchievement.updateAchievementWindow()` → 解绑 FBO 并 `framebufferMc.framebufferRender(displayWidth, displayHeight)`（:1167）→ `updateDisplay()`（:1173，`Display.update()` + `checkWindowResize()`）→ fps 统计 → `isFramerateLimitBelowMax()` 时 `Display.sync(getLimitFramerate())`（:1207；主菜单无世界时限 30fps，:1250）。
   - `isGamePaused` 的判定在渲染后：`this.isSingleplayer() && this.currentScreen != null && this.currentScreen.doesGuiPauseGame() && !this.theIntegratedServer.getPublic()`（:1184）。
4. 每 tick（`runTick`，Minecraft.java:1736）：
   `rightClickDelayTimer--` → `ingameGUI.updateTick()`（非暂停）→ `entityRenderer.getMouseOver(1.0F)`（:1751，更新 `objectMouseOver`）→ `playerController.updateController()`（:1756）→ `renderEngine.tick()` → 死亡/睡觉自动切屏（:1766-1780）→ `currentScreen.handleInput()` + `currentScreen.updateScreen()`（:1791/:1811，异常包成 CrashReport）→（无屏或 allowUserInput 时）`Mouse.next()` 事件循环（:1833，`KeyBinding.setKeyBindState(i - 100, ...)`、滚轮切槽）→ `Keyboard.next()` 事件循环（:1899，`KeyBinding.setKeyBindState`/`onTick`、F3 组合键、`dispatchKeypresses()`）→ 热键消费（hotbar/inventory/drop/chat/attack/use/pickBlock，:2070-2161）→ `sendClickBlockToController(...)`（:2163）→ `entityRenderer.updateRenderer()`（:2183）→ `renderGlobal.updateClouds()` → `theWorld.updateEntities()`（:2202，**EntityPlayerSP.onUpdate 由此触发 → 移动封包发出**）→ `mcMusicTicker.update()` + `mcSoundHandler.update()`（:2212-2213）→ `theWorld.tick()`（:2224）→ `theWorld.doVoidFogParticles` → `effectRenderer.updateEffects()`；无世界但有 `myNetworkManager` 时 `processReceivedPackets()`（:2261，登录阶段）。
5. 世界进入/退出：单机 `launchIntegratedServer`（:2271，**服务端跑在独立线程**，客户端经本地 channel 连接）；`loadWorld(worldClientIn[, loadingMessage])`（:2341/:2349）装卸；`setDimensionAndSpawnPlayer`（:2425）换维度。退出：`shutdown()`（:1439）置 `running = false` → 循环退出 → finally `shutdownMinecraftApplet()`（:1044，loadWorld(null)、unloadSounds、`Display.destroy()`、`System.exit(0)`）。
6. 线程归属：
   - 主线程（"Client thread"）：以上全部；GL 调用只允许在此。
   - Netty EventLoop：封包解码与 NetHandler 回调起点；需要改游戏状态时经 `Minecraft.addScheduledTask`（:3221）投递回主线程（`isCallingFromMinecraftThread`，:3254）。
   - "Server thread"：IntegratedServer 独立跑；与客户端只通过本地 NetworkManager 通信。
   - "Sound Library Loader" 线程：`SoundManager.loadSoundSystem`（SoundManager.java:102）异步起声音引擎；paulscode 内部另有自己的播放/流线程（`SoundSystemConfig.THREAD_SYNC` 同步，SoundManager.java:529）。
   - "Timer hack thread"（Minecraft.java:725）：daemon，永眠，仅为保 JVM 计时器精度的历史 hack。
   - "Client Shutdown Thread"（Main.java:105）：JVM 关机钩子，停 IntegratedServer。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void runTick() throws IOException` | Minecraft.java:1736 | 每逻辑 tick（20/s） | 客户端模块 tick 总入口；前后插桩即得 pre/post tick 事件 | 内部有 GUI 异常→CrashReport 包装；勿在暂停判断前假设世界非 null |
| `private void runGameLoop() throws IOException` | Minecraft.java:1078 | 每帧 | 帧级 hook（HUD 覆盖、帧计时）；`scheduledTasks` 排空点在此 | private；渲染在主 FBO 内，blit 后再画会绕过后处理 |
| `public void displayGuiScreen(GuiScreen guiScreenIn)` | Minecraft.java:981 | 任何 GUI 打开/关闭 | GUI 打开/关闭事件、替换/取消屏幕（改写入参） | 传 null 有三重回落逻辑（主菜单/死亡屏）；会触发 `onGuiClosed` |
| `public void loadWorld(WorldClient worldClientIn, String loadingMessage)` | Minecraft.java:2349 | 进出世界、切服 | 世界加载/卸载事件；模块状态重置的标准挂点 | null 路径会 cleanup NetHandler 与 IntegratedServer；顺序敏感 |
| `public void runTick()` 内 `Mouse.next()` / `Keyboard.next()` 循环 | Minecraft.java:1833 / 1899 | 每 tick 排空输入事件 | 原始输入拦截（点击 GUI、按键宏、输入吞噬） | 事件只在 tick 内消费——帧率高于 20 时输入延迟本来就存在；LWJGL3 下由 shim 从 GLFW 队列喂入 |
| `public void dispatchKeypresses()` | Minecraft.java:3115 | 键盘事件循环内每事件一次 | 全局热键（截图/全屏同级）注入 | `Keyboard.isRepeatEvent()` 会被过滤；GuiControls 打开时有 20ms 抑制 |
| `private void clickMouse()` | Minecraft.java:1517 | 左键按下（攻击/开挖） | 攻击事件、reach/目标改写（`objectMouseOver`） | private，需字节码或包装 keyBindAttack；`leftClickCounter` 节流 |
| `private void rightClickMouse()` | Minecraft.java:1565 | 右键按下/按住 | 交互/放置事件、取消使用 | `rightClickDelayTimer = 4` 节流；itemstack 归零时置 null 槽位 |
| `private void sendClickBlockToController(boolean leftClick)` | Minecraft.java:1491 | 每 tick（持续挖掘） | 挖掘进度 hook、自动挖掘 | 与 `playerController.onPlayerDamageBlock` 联动 |
| `public void setIngameFocus()` / `public void setIngameNotInFocus()` | Minecraft.java:1448 / 1465 | 鼠标抓取/释放 | 焦点切换事件；后者会 `KeyBinding.unPressAllKeys()` | 抓取依赖 `Display.isActive()`（shim/GLFW 焦点语义） |
| `public <V> ListenableFuture<V> addScheduledTask(Callable<V> callableToSchedule)` | Minecraft.java:3221 | 任意线程 | **把工作调回主线程的唯一正道**（网络线程改状态必用） | 主线程直接调用会同步执行；队列在下一帧头排空 |
| `public void onUpdate()` | EntityPlayerSP.java:168 | 世界 tick 内（updateEntities） | 移动封包发出前的最后一站；取消/改写移动上报 | 区块未加载时整个跳过（连 super 都不 tick） |
| `public void onUpdateWalkingPlayer()` | EntityPlayerSP.java:189 | onUpdate 内（非骑乘） | C03/C04/C05/C06 移动包与 sneak/sprint C0B 的改写点（反作弊敏感功能都挂这里） | 阈值 9.0E-4D 与 20 tick 强制上报是协议行为，改动影响服务端 rubber-band |
| `public void sendChatMessage(String message)` | EntityPlayerSP.java:296 | 玩家发聊天/命令 | 聊天拦截、命令前缀处理 | 直接发 C01，无本地回显 |
| `public void closeScreen()` | EntityPlayerSP.java:330 | 容器 GUI 关闭 | 容器关闭事件（发 C0D 前） | `closeScreenAndDropStack` 会清 held stack |
| `public void displayGUIChest(IInventory chestInventory)` / `public void displayGui(IInteractionObject guiOwner)` | EntityPlayerSP.java:606 / 645 | 收到打开窗口包后 | 容器 GUI 替换（自定义箱子界面等） | guiID 字符串分发，未知 id 回落 GuiChest |
| `public void setPlayerSPHealth(float health)` | EntityPlayerSP.java:346 | S06 血量下发 | 血量变化事件（含首次同步） | 首次走 `hasValidHealth=false` 分支，别当受伤处理 |
| `public void onLivingUpdate()` | EntityPlayerSP.java:715 | 每 tick | 疾跑/飞行/传送门逻辑改写（如 toggle sprint） | `movementInput.updatePlayerMoveState()` 在此调用，之前读到的是上 tick 输入 |
| `public void updateEntityActionState()` | EntityPlayerSP.java:690 | 每 tick（living update 链） | moveStrafing/moveForward/isJumping 的注入点 | 仅 `isCurrentViewEntity()` 时生效 |
| `public void playSound(ISound sound)` | SoundHandler.java:196 | 任何声音播放 | 声音事件监听/替换/静默 | 也覆盖 MusicTicker；delayed 路径另有 :204 |
| `public void playSound(ISound p_sound)` | SoundManager.java:335 | SoundHandler 转发后 | 更底层：可拿到实际抽样的 SoundPoolEntry 与 channel 名 | master 音量为 0 时静默 return；channel 名是随机 UUID |
| `public void updateAllSounds()` | SoundManager.java:220 | 每 tick | tickable 声音的 volume/pitch/pos 同步点 | 遍历时用 iterator.remove，勿并发改集合 |
| `public void update()` | MusicTicker.java:24 | 每 tick | BGM 替换/禁用（改 `getAmbientMusicType` 或在此拦截） | `timeUntilNextMusic` 被 play 后设为 Integer.MAX_VALUE |
| `public void onResourceManagerReload(IResourceManager resourceManager)` | SoundHandler.java:59 | F3+T / 资源包切换 | 注入自定义 sounds.json 条目后处理 | 会 reload 整个声音引擎（异步线程重启） |
| `public static boolean isKeyDown(KeyBinding key)` | GameSettings.java:233 | 各处轮询 | 按键状态伪造（直接查询硬件而非 KeyBinding.pressed） | 负 keyCode 是鼠标键；与 `KeyBinding.isKeyDown()`（事件驱动）语义不同 |
| `public void saveOptions()` / `public void loadOptions()` | GameSettings.java:1085 / 687 | 设置变更/启动 | 自定义配置项持久化搭车点 | saveOptions 末尾必发 C15 设置包（:1186） |
| `public void sendSettingsToServer()` | GameSettings.java:1203 | saveOptions / 模型部件切换 | C15PacketClientSettings 改写（假 renderDistance 等） | thePlayer 为 null 时静默不发 |
| `public static void onTick(int keyCode)` / `public static void setKeyBindState(int keyCode, boolean pressed)` | KeyBinding.java:24 / 37 | 输入事件循环 | 合成按键注入（模拟点击） | onTick 只加 pressTime；两者配合才能模拟完整按压 |
| `public void framebufferRender(int p_147615_1_, int p_147615_2_)` | Framebuffer.java:216 | 每帧 blit 上屏 | 最终画面后处理/录制抓帧 | 会改矩阵与大量 GL 状态；depthMask/colorMask 有恢复但矩阵没有 pop |
| `public void loadShaderGroup(float partialTicks)` | ShaderGroup.java:375 | 每帧（shader 激活时） | 自定义后处理 pass 注入 | Time uniform 是 0..1 循环（20 tick 周期） |
| `public void useShader()` / `public void endShader()` | ShaderManager.java:191 / 174 | 每 pass | uniform 注入（先 `getShaderUniformOrDefault(...).set(...)`） | 静态 `currentProgram` 缓存——外部 glUseProgram 会让它失真 |
| `public MusicTicker.MusicType getAmbientMusicType()` | Minecraft.java:3105 | MusicTicker.update 每 tick | 改写返回值即可控制 BGM 选择 | 依赖 BossStatus 静态字段 |

## 数据与协议

### options.txt（GameSettings.loadOptions:687 / saveOptions:1085）

行格式 `key:value`，坏行跳过。要点字段：

| 键 | 类型/取值 | 读 | 写 | 备注 |
|---|---|---|---|---|
| mouseSensitivity | float | :706 | :1091 | |
| fov | float | :711（`* 40.0F + 70.0F`） | :1092（`(fovSetting - 70.0F) / 40.0F`） | 磁盘上是 -1..1 归一值 |
| renderDistance | int | :731 | :1095 | 上限受 JVM 位数影响（:195-204） |
| ao | true/false/int | :776（true→2, false→0） | :1104（直接 int） | 兼容旧格式 |
| renderClouds | true/false/fast | :792 | :1106-1118 | 三态字符串 |
| resourcePacks / incompatibleResourcePacks | JSON 数组 | :808/:818（gson） | :1120-1121 | 值内可含冒号，用 `s.substring(s.indexOf(58) + 1)` |
| key_&lt;keyDescription&gt; | int keyCode | :1033-1039 | :1164-1167 | 负数为鼠标键 |
| soundCategory_&lt;name&gt; | float 0..1 | :1041-1047 | :1169-1172 | 9 个分类 |
| modelPart_&lt;partName&gt; | bool | :1049-1055 | :1174-1177 | 皮肤外层部件 |

### 本包直接构造的上行封包

| 封包 | 触发点 | 文件:行号 |
|---|---|---|
| C00Handshake(47, addr, 0, EnumConnectionState.LOGIN) / C00PacketLoginStart | 单机本地连接 | Minecraft.java:2333-2334 |
| C01PacketChatMessage | sendChatMessage | EntityPlayerSP.java:298 |
| C03PacketPlayer / C04PacketPlayerPosition / C05PacketPlayerLook / C06PacketPlayerPosLook | 每 tick 移动上报 | EntityPlayerSP.java:237-254；骑乘 look：:176 |
| C07PacketPlayerDigging(DROP_ITEM/DROP_ALL_ITEMS) | dropOneItem | EntityPlayerSP.java:282 |
| C0APacketAnimation | swingItem | EntityPlayerSP.java:307 |
| C0BPacketEntityAction(START/STOP_SPRINTING, START/STOP_SNEAKING, RIDING_JUMP, OPEN_INVENTORY) | 状态变化/马 | EntityPlayerSP.java:197-217, 409, 414 |
| C0CPacketInput | 骑乘时每 tick | EntityPlayerSP.java:177 |
| C0DPacketCloseWindow | closeScreen | EntityPlayerSP.java:332 |
| C13PacketPlayerAbilities | sendPlayerAbilities | EntityPlayerSP.java:396 |
| C15PacketClientSettings | GameSettings.sendSettingsToServer | GameSettings.java:1214 |
| C16PacketClientStatus(PERFORM_RESPAWN / OPEN_INVENTORY_ACHIEVEMENT) | respawnPlayer / 开背包 | EntityPlayerSP.java:312；Minecraft.java:2095 |

### sounds.json（SoundListSerializer.deserialize，SoundListSerializer.java:15）

| 字段 | 类型 | 含义 |
|---|---|---|
| replace | bool（默认 false） | 是否替换低优先级资源包的同名事件（:19） |
| category | string（默认 "master"） | 必须命中 SoundCategory 名，否则 Validate 失败（:20-22） |
| sounds[] | string 或 object | 字符串=文件名简写；对象含 name / type("file"\|"event") / volume(>0) / pitch(>0) / weight(>0) / stream(bool)（:33-73） |

FILE 型最终映射到 `sounds/<path>.ogg`（SoundHandler.java:138）；EVENT 型运行时解引用另一事件（SoundHandler.java:164-177）。

### 后处理 shader JSON

- group json（`ShaderGroup.parseGroup`，ShaderGroup.java:54）：`targets[]`（字符串或 `{name,width,height}`）、`passes[]`（`{name,intarget,outtarget,auxtargets[{name,id,width,height,bilinear}],uniforms[{name,values[]}]}`）；`"minecraft:main"` 指主 FBO（ShaderGroup.java:405）。
- program json（`ShaderManager` 构造器，ShaderManager.java:49）：`vertex`、`fragment`、`samplers[{name,file?}]`、`attributes[]`、`uniforms[{name,type,count,values[]}]`、`blend{func,srcrgb,dstrgb,srcalpha,dstalpha}`（JsonBlendingMode.func_148110_a，JsonBlendingMode.java:109）、`cull`（默认 true）。
- NBT：`Minecraft.pickBlockWithNBT`（Minecraft.java:2639）——中键拾取带 TileEntity 时写 `BlockEntityTag`（skull 特殊为 `SkullOwner`）+ display.Lore "(+NBT)"。

## 不变量与陷阱

- **GL 单线程**：所有 GL 调用（Framebuffer/Shader*/LoadingScreenRenderer/Minecraft 渲染路径）必须在 "Client thread"。`Minecraft.isCallingFromMinecraftThread()`（Minecraft.java:3254）以 `mcThread`（:336，构造 Minecraft 的线程）为准——`Main` 里先 `setName("Client thread")` 再构造，两者一致。
- **Netty 回调不得直接改游戏状态**：必须 `addScheduledTask`。任务只在每帧开头排空（Minecraft.java:1101），暂停时也会执行（排空在 tick 循环之前）。
- `KeyBinding.isPressed()`（KeyBinding.java:101）是**消费型**（pressTime 递减）；漏消费会导致下 tick 重复触发，多消费会吞掉输入。`isKeyDown()` 才是电平语义。
- 改键后必须 `KeyBinding.resetKeyBindingArrayAndHash()`（KeyBinding.java:58），否则 `hash` 里仍是旧 keyCode 映射。构造新 KeyBinding 会自动进全局静态表——**没有反注册手段**，动态创建会泄漏。
- `EntityPlayerSP.onUpdate`（EntityPlayerSP.java:170）在玩家所在列区块未加载时整个不 tick——依赖玩家 tick 的模块在传送/刚进服时会有空窗。
- 移动上报阈值：位移平方 ≤ 9.0E-4D 且无转头时只发 `C03PacketPlayer(onGround)`；至多 20 tick 必发一次完整位置（EntityPlayerSP.java:230）。伪造/抑制这些包是服务端反作弊直接检测面。
- `GameSettings.saveOptions()` 每次都会 `sendSettingsToServer()`（GameSettings.java:1186）——高频调 saveOptions 会刷 C15 包。
- `SoundManager.loaded` 由 "Sound Library Loader" 线程异步置位（SoundManager.java:132）；启动早期 `playSound` 会因 `!this.loaded` 静默丢弃。`playingSoundsStopTime` 给每个声道 `playTime + 20` 的宽限（:396），`isSoundPlaying` 的语义包含"仍在宽限期"。
- `SoundHandler.getRandomSoundFromCategories`（SoundHandler.java:260）每次调用 `new Random()`——低熵、也别在热路径调（遍历全注册表）。
- `LoadingScreenRenderer.displayString/displayLoadingString/setLoadingProgress` 在 `!mc.running` 时抛 `MinecraftError`（LoadingScreenRenderer.java:69/104/125）——包裹世界加载代码时不要 catch Throwable 吞掉它，否则关窗卡死。
- `Framebuffer.framebufferRenderExt`（Framebuffer.java:221）修改投影/模型矩阵后**不恢复**；调用方（runGameLoop）用 push/pop 包住（Minecraft.java:1166-1168），自行调用时要照做。
- `ShaderManager` 的 `currentProgram`/`staticShaderManager` 是静态缓存（ShaderManager.java:30-31）——任何绕过它直接 `glUseProgram` 的代码之后必须让它失效（调 `endShader()`）。
- `ShaderUniform.upload()`（ShaderUniform.java:227）里 `if (!this.dirty) { ; }` 是反编译产物：**dirty 标志实际无效，每次都上传**。矩阵上传 transpose=true（:311-319），自定义 pass 传矩阵时注意行列序。
- `SoundEventAccessorComposite.cloneEntry`（SoundEventAccessorComposite.java:37）总重乘 eventPitch/eventVolume；空池返回 `SoundHandler.missing_sound` 哨兵——用 `==` 判断（SoundManager.java:355）。
- LWJGL3/JDK25 移植点：`org.lwjgl.opengl.Display`、`org.lwjgl.input.Keyboard/Mouse`、`org.lwjgl.Sys`、`org.lwjgl.util.vector.Matrix4f`、`org.lwjgl.util.glu.GLU` 均来自本仓库 `lwjgl2-shim` 模块（GLFW 之上重实现），不是真 LWJGL2。行为差异（vsync、焦点、事件队列、HiDPI）应查 shim 而非上游文档。
- 移植改动确认：`Minecraft.initStream()`（Minecraft.java:611-617）直接 NullStream（Twitch 移除，但 `GameSettings` 的 stream* 设置项与 `dispatchKeypresses` 的推流按键逻辑仍在，走 NullStream 空实现）；`SoundManager` 用 `ThreadLocalRandom.current()` 生成声道名（SoundManager.java:9, 381）。
- `AbstractClientPlayer.getDownloadImageSkin`（AbstractClientPlayer.java:88）硬编码 `http://skins.minecraft.net/...`——明文 http 且该域已停服，皮肤下载按失败处理（有默认皮肤回落）。
- `Minecraft.memoryReserve`（Minecraft.java:195）是 OOM 恢复机制的一部分：`freeMemory()`（:1258）先把它置空再 GC。JDK25 下堆行为不同，但机制保留。
- `EntityOtherPlayerMP` 构造即 `noClip = true`（EntityOtherPlayerMP.java:26）——不要用它做本地碰撞判定。

## 交叉引用

- client.renderer → `EntityRenderer#updateCameraAndRender`（Minecraft.java:1141 每帧调）、`EntityRenderer#getMouseOver`（:1751）、`RenderGlobal#loadRenderers`（GameSettings.java:325/415/421/498 等设置副作用）、`GlStateManager`/`OpenGlHelper`/`Tessellator`/`WorldRenderer`（Framebuffer、Shader、LoadingScreenRenderer 全量使用）、`TextureManager`（AbstractClientPlayer#getDownloadImageSkin、ShaderGroup#parsePass）。
- client.multiplayer → `PlayerControllerMP#updateController`（Minecraft.java:1756）、`PlayerControllerMP#func_178892_a`（Minecraft.java:2405，创建 EntityPlayerSP）、`PlayerControllerMP#onPlayerRightClick/clickBlock/attackEntity`（Minecraft.java:1600/1545/1537）、`WorldClient#tick/updateEntities`（Minecraft.java:2224/2202）。
- client.network → `NetHandlerPlayClient#addToSendQueue`（EntityPlayerSP 全部封包上行）、`NetHandlerPlayClient#getPlayerInfo`（AbstractClientPlayer.java:35/51）、`NetHandlerLoginClient`（Minecraft.java:2332）。
- network → `NetworkManager#provideLocalClient/sendPacket/processReceivedPackets`（Minecraft.java:2331-2334, 2261）。
- client.gui → `GuiScreen#setWorldAndResolution/handleInput/updateScreen/onGuiClosed`（Minecraft.java:1011/1791/1811/985）、`GuiIngame#updateTick`（:1747）、`GuiNewChat#printChatMessage`（EntityPlayerSP.java:434/537）、`GuiChest/GuiFurnace/...`（EntityPlayerSP.java:606-666 的 GUI 工厂）。
- server.integrated → `IntegratedServer#startServerThread/serverIsInRunLoop/initiateShutdown`（Minecraft.java:2292/2306/2362）、`Minecraft.stopIntegratedServer`（Main.java:109）。
- util → `Timer#updateTimer`（Minecraft.java:1091-1096）、`MouseHelper#grabMouseCursor/ungrabMouseCursor`（Minecraft.java:1455/1471）、`MovementInputFromOptions`（Minecraft.java:2411）、`ScreenShotHelper#saveScreenshot`（Minecraft.java:3189）、`MathHelper#getRandomUuid`（SoundManager.java:381）。
- world / entity → `WorldClient#spawnEntityInWorld`（Minecraft.java:2410）、`EntityGuardian#hasTargetedEntity/func_175477_p`（GuardianSound.java:24-29）、`EntityMinecart`（MovingSoundMinecart*）。
- stats → `StatFileWriter`（EntityPlayerSP.java:58、Minecraft.java:2405）、`AchievementList.openInventory#setStatStringFormatter`（Minecraft.java:520）。
- paulscode.sound（第三方）→ `SoundSystem/SoundSystemConfig/LibraryLWJGLOpenAL/CodecJOrbis`（SoundManager.java:29-35, 78-79）。
- lwjgl2-shim → `org.lwjgl.opengl.Display`（Minecraft 窗口全生命周期）、`org.lwjgl.input.Keyboard/Mouse`（Minecraft.runTick、GameSettings.isKeyDown）、`org.lwjgl.Sys`（Minecraft.getSystemTime:3023）。
- client.resources → `SimpleReloadableResourceManager#registerReloadListener`（startGame 注册 SoundHandler/字体/模型等 9+ 个 listener，Minecraft.java:496-564）、`I18n.format`（GameSettings/KeyBinding 的显示文本）。

## 覆盖声明

完整读取了 42/42 个文件（每个文件从第 1 行读到末行）。

- 逐行精读：Minecraft.java、EntityPlayerSP.java、GameSettings.java、KeyBinding.java、SoundManager.java、SoundHandler.java、MusicTicker.java、Framebuffer.java、ShaderGroup.java、ShaderManager.java、Shader.java、ShaderLoader.java、ShaderUniform.java、LoadingScreenRenderer.java、Main.java、AbstractClientPlayer.java、EntityOtherPlayerMP.java。
- 完整读取但仅结构性归纳（逻辑简单，无需逐行分析）：AnvilConverterException、ClientBrandRetriever、GameConfiguration、ISound、ISoundEventAccessor、ITickableSound、MovingSound、MovingSoundMinecart、MovingSoundMinecartRiding、GuardianSound、PositionedSound、PositionedSoundRecord、SoundCategory、SoundEventAccessor、SoundEventAccessorComposite、SoundList、SoundListSerializer、SoundPoolEntry、SoundRegistry、ContainerLocalMenu、LocalBlockIntercommunication、ShaderDefault、ShaderLinkHelper、JsonBlendingMode、JsonException。
- Minecraft.java 的 addServerTypeToSnooper（GL caps 上报，2825-2936 行）为样板代码，读取但未展开记录。
