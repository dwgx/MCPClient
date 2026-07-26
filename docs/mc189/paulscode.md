---
area: paulscode
slug: paulscode
files: 3
lines: 1391
tier: B
---

# paulscode.sound.libraries — LWJGL3 OpenAL 音频后端

## 定位

这个包是 paulscode SoundSystem 的 OpenAL 插件层，也是本仓库对 LWJGL3 移植改动最集中的音频代码。上游 paulscode 的 `soundsystem`、`codecwav`、`codecjorbis`、`libraryjavasound` 仍以 jar 依赖形式引入（`client/pom.xml:192-212`），唯独 `libraries.*` 三个类被抽进源码树重写，因为它们直接调用 LWJGL2 的 `AL.create()` / `AL.destroy()`，这两个 API 在 LWJGL3 中不存在。

- 谁调用它：`net.minecraft.client.audio.SoundManager` 在构造函数里执行 `SoundSystemConfig.addLibrary(LibraryLWJGLOpenAL.class)`（SoundManager.java:78），此后所有 `sndSystem.play/stop/setVolume/setListenerPosition/...` 调用经 paulscode `SoundSystem` 的命令队列最终落到本包三个类上。
- 它调用谁：`org.lwjgl.openal.AL10/ALC10/AL/ALC`（LWJGL3 真实绑定，不是 shim），以及 paulscode jar 中的基类 `Library`、`Channel`、`Source`、`SoundSystemConfig`、`ICodec` 等。
- 如果它消失：客户端所有声音（方块音效、音乐、唱片流式播放）全部失效——SoundManager 没有配置任何备用 Library，`libraryjavasound` 只在 pom 里存在，代码中未注册。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| ChannelLWJGLOpenAL | 378 | extends `paulscode.sound.Channel` | 包装一个 AL source（`IntBuffer ALSource`），负责 buffer 挂载、流式队列、播放控制与播放进度计算 |
| LibraryLWJGLOpenAL | 596 | extends `paulscode.sound.Library` | OpenAL 设备/上下文生命周期（LWJGL3 重写点）、AL buffer 缓存、listener 状态、Source/Channel 工厂 |
| SourceLWJGLOpenAL | 417 | extends `paulscode.sound.Source` | 单个逻辑声源：位置/音量/pitch/衰减同步到 AL source，流式播放的格式协商与预加载 |

## 核心类详解

### LibraryLWJGLOpenAL（LibraryLWJGLOpenAL.java）

关键字段：
- `private FloatBuffer listenerPositionAL / listenerOrientation / listenerVelocity`（:57-59）——持久 direct buffer，写入后用 `AL10.alListenerfv` 推送。
- `private static long alDevice = 0L; private static long alContext = 0L;`（:62-63）——**本仓库新增**，LWJGL3 的设备/上下文句柄。
- `private HashMap<String, IntBuffer> ALBufferMap`（:100）——文件名 → AL buffer id 缓存；与基类 `bufferMap`（文件名 → 解码后的 `SoundBuffer`）平行。
- `private static boolean alPitchSupported = true`（:101）。

关键方法（签名逐字复制）：
- `private static void lwjgl3CreateAL() throws LWJGLException`（:68）——LWJGL2 `AL.create()` 的替代：`ALC10.alcOpenDevice((java.nio.ByteBuffer) null)` → `ALC.createCapabilities` → `alcCreateContext` → `alcMakeContextCurrent` → `AL.createCapabilities(alcCaps)`。失败抛 `LWJGLException` 以维持原有错误路径。
- `private static void lwjgl3DestroyAL()`（:85）——先 `alcMakeContextCurrent(0L)` 再销毁 context、关闭 device。
- `public void init() throws SoundSystemException`（:107）——创建 AL、推送 listener 三元组、设置 doppler，然后 `super.init()` 创建 channels，最后用 normal channel #1 探测 `AL_PITCH`（0x1003=4099）支持，不支持则抛 `Exception("OpenAL: AL_PITCH not supported.", 108)`（:149）。
- `public static boolean libraryCompatible()`（:159）——SoundSystem 选库时调用：若未创建则试创建再销毁一次。
- `protected Channel createChannel(int type)`（:178）——`alGenSources` 生成一个 source 包成 `ChannelLWJGLOpenAL`；失败返回 null（channel 数量由此被硬件上限截断）。
- `public boolean loadSound(FilenameURL filenameURL)`（:215）——用 `SoundSystemConfig.getCodec(...)` 解码整个文件（`codec.readAll()`），按声道/位深映射 AL 格式（4352=AL_FORMAT_MONO8、4353=MONO16、4354=STEREO8、4355=STEREO16），`alGenBuffers`+`alBufferData` 上传并双写 `bufferMap`/`ALBufferMap`。重载 `public boolean loadSound(SoundBuffer buffer, String identifier)`（:290）。
- `public void newSource(boolean priority, boolean toStream, boolean toLoop, String sourcename, FilenameURL filenameURL, float x, float y, float z, int attModel, float distOrRoll)`（:360）——非流式先确保 buffer 已加载，再 `sourceMap.put(sourcename, new SourceLWJGLOpenAL(...))`。
- `public void quickPlay(...)`（:394）与 `public void rawDataStream(AudioFormat audioFormat, boolean priority, String sourcename, float x, float y, float z, int attModel, float distOrRoll)`（:390）。
- `public void setMasterVolume(float value)`（:354）——`alListenerf(4106 /*AL_GAIN*/, value)`。
- `public void setListenerPosition(float x, float y, float z)`（:456）、`setListenerOrientation(...)`（:473）、`setListenerData(ListenerData l)`（:485）、`setListenerVelocity(...)`（:507）、`dopplerChanged()`（:515）。
- `public void cleanup()`（:194）——删除全部 AL buffer 后调用 `lwjgl3DestroyAL()`。
- 内部类 `public static class Exception extends SoundSystemException`（:576）——错误码常量 `CREATE=101` … `NO_AL_PITCH=108`。

被谁调用：全部由 paulscode `SoundSystem` 的 CommandThread 驱动（见时序节）；`libraryCompatible()` 由 `SoundSystem.libraryCompatible` 静态检查调用。

### ChannelLWJGLOpenAL（ChannelLWJGLOpenAL.java）

关键字段：`public IntBuffer ALSource`（:25，容量 1 的 AL source id）、`public int ALformat`（:26）、`public int sampleRate`（:27）、`public float millisPreviouslyPlayed = 0.0f`（:28，流式播放已消耗 buffer 的累计毫秒数）。

关键方法：
- `public ChannelLWJGLOpenAL(int type, IntBuffer src)`（:30）——type 0=normal、1=streaming（由基类 `channelType` 判定，判据见 :59、:111）。
- `public boolean attachBuffer(IntBuffer buf)`（:58）——normal channel 专用，`alSourcei(src, 4105 /*AL_BUFFER*/, buf.get(0))`。
- `public void setAudioFormat(AudioFormat audioFormat)`（:72）/ `public void setFormat(int format, int rate)`（:103）。
- `public boolean preLoadBuffers(LinkedList<byte[]> bufferList)`（:108）——流式启动：清掉已处理 buffer、`alGenBuffers` 批量上传、`alSourceQueueBuffers`、`alSourcePlay`。
- `public boolean queueBuffer(byte[] buffer)`（:171）——回收一个已处理 buffer（`alSourceUnqueueBuffers`）复用上传新数据；回收时把其时长累加进 `millisPreviouslyPlayed`（:182）。
- `public int feedRawAudioData(byte[] buffer)`（:193）——rawDataStream 路径：无已处理 buffer 时 `alGenBuffers` 新建（buffer 数量会随喂入速度增长），返回本次回收的 processed 数。
- `public float millisInBuffer(int alBufferi)`（:236）——由 AL_SIZE(8196)/AL_CHANNELS(8195)/AL_BITS(8194) 计算 buffer 时长。
- `public float millisecondsPlayed()`（:240）——AL_BYTE_OFFSET(4134) 换算毫秒，流式再加 `millisPreviouslyPlayed`。
- 播放控制：`public void play()`（:313）、`pause()`（:318）、`stop()`（:323）、`rewind()`（:330，流式为 no-op）、`public boolean playing()`（:340，state==4114 即 AL_PLAYING）、`public int buffersProcessed()`（:268）、`public void flush()`（:279）、`close()`（:300）、`cleanup()`（:36，stop+`alDeleteSources`）。

被谁调用：`SourceLWJGLOpenAL.play(Channel c)` 与基类 `Source`/`Library`；流式方法由 SoundSystem 的 StreamThread 循环调用。

### SourceLWJGLOpenAL（SourceLWJGLOpenAL.java）

关键字段：`private ChannelLWJGLOpenAL channelOpenAL`（:31）、`private IntBuffer myBuffer`（:32，非流式的 AL buffer）、`private FloatBuffer listenerPosition / sourcePosition / sourceVelocity`（:33-35）。

关键方法：
- 三个构造器：`public SourceLWJGLOpenAL(FloatBuffer listenerPosition, IntBuffer myBuffer, boolean priority, boolean toStream, boolean toLoop, String sourcename, FilenameURL filenameURL, SoundBuffer soundBuffer, float x, float y, float z, int attModel, float distOrRoll, boolean temporary)`（:37）；拷贝构造（:50，用于 `copySources` 切库）；raw-stream 构造（:63）。均设置 `this.pitch = 1.0f` 并 `resetALInformation()`。
- `public void play(Channel c)`（:237）——核心：若换 channel，重推 position/pitch/velocity/rolloff/looping 并 `attachBuffer(myBuffer)`（非流式，:286）；流式则由 codec 的 `AudioFormat` 协商 AL 格式，`channelOpenAL.setFormat(...)` 后置 `preLoad = true`（:329-330），实际数据由 StreamThread 调 `preLoad()` 灌入；最后 `this.channel.play()`。
- `public boolean preLoad()`（:339）——`codec.initialize(url)` 后读 `SoundSystemConfig.getNumberStreamingBuffers()` 块交给 `channel.preLoadBuffers(...)`。
- `public boolean incrementSoundSequence()`（:90）——流式播放列表切歌：在 `soundSequenceLock` 同步块内换 codec、重协商格式。
- 状态同步族（每个都先调 super 再在"本 source 当前占有 channel"时写 AL）：`public void setPosition(float x, float y, float z)`（:150）、`public void positionChanged()`（:166，重算 distance/gain 并写 AL_GAIN=4106）、`public void setLooping(boolean lp)`（:183，AL_LOOPING=4103）、`public void setAttenuation(int model)`（:195，AL_ROLLOFF_FACTOR=4129）、`public void setDistOrRoll(float dr)`（:207）、`public void setVelocity(float x, float y, float z)`（:219）、`public void setPitch(float value)`（:229）、`public void listenerMoved()`（:146）。
- `private void checkPitch()`（:176）——仅在 `LibraryLWJGLOpenAL.alPitchSupported()` 时写 AL_PITCH=4099。
- `private void calculateDistance()`（:364）与 `private void calculateGain()`（:373）——attModel==2（linear）时手动线性衰减：`gain = 1 - distance/distOrRoll`，clamp 到 [0,1]；其余模型 gain=1 交给 AL rolloff。

被谁调用：`Library.play/stop/pause/setVolume/setPitch/setPosition` 等按 sourcename 查 `sourceMap` 后分发；来源是 SoundManager 每 tick 的 `updateAllSounds()` 与播放请求。

## 时序与生命周期

1. **注册**（主线程）：`SoundManager` 构造时 `SoundSystemConfig.addLibrary(LibraryLWJGLOpenAL.class)`（SoundManager.java:78）。
2. **启动**（"Sound Library Loader" 线程）：`SoundManager.loadSoundSystem()` 起一个线程 new `SoundSystemStarterThread`（extends `SoundSystem`，SoundManager.java:131、:521）。`SoundSystem` 构造会调 `libraryCompatible()` → 内部再起 **CommandThread**，由它执行 `LibraryLWJGLOpenAL.init()`：`lwjgl3CreateAL()` → listener 初始化 → doppler → `super.init()`（经 `createChannel` 逐个 `alGenSources` 直到失败或到上限）→ AL_PITCH 探测。**AL context 因此绑定在 CommandThread，不在渲染主线程。**
3. **每 tick**：`SoundManager.updateAllSounds()` 调 `sndSystem.setVolume/setPitch/setPosition/playing/removeSource`（SoundManager.java:235-264）；`setListener` 每帧由 `RenderGlobal`/game loop 调 `setListenerPosition`+`setListenerOrientation`（SoundManager.java:516-517）。这些调用被 `SoundSystem` 包装成命令入队，真正的 AL 调用仍在 CommandThread 执行。
4. **流式播放**：每个流式 source 的数据泵由 SoundSystem 的 StreamThread 驱动——`preLoad()`、`channel.queueBuffer(...)`、`buffersProcessed()` 循环。
5. **关闭**：`SoundManager.unloadSoundSystem()` → `sndSystem.cleanup()`（SoundManager.java:194）→ `Library.cleanup()` 停掉所有 source/channel → `LibraryLWJGLOpenAL.cleanup()`（:194）删 buffer 并 `lwjgl3DestroyAL()`（:205）。资源包重载走 `reloadSoundSystem()`（SoundManager.java:87）= unload + load，整个 AL device 会重建。

线程归属：本包代码几乎不在主线程运行；运行在 paulscode 的 CommandThread / StreamThread 上。

## 挂钩点（Hook Points）

注意：音频功能层通常应该挂在 `SoundManager`（主线程、命令入队前），挂在本包意味着运行在音频线程上。下面列出本包内真正有价值的接管点。

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public void init() throws SoundSystemException` | LibraryLWJGLOpenAL.java:107 | 声音引擎启动（CommandThread） | 选择输出设备（改 `alcOpenDevice` 参数）、启用 ALC 扩展（HRTF）、调 doppler | 抛异常会让 SoundManager 静音降级；必须在 super 前建好 context |
| `private static void lwjgl3CreateAL() throws LWJGLException` | LibraryLWJGLOpenAL.java:68 | init 与 libraryCompatible | 设备枚举/切换、context attributes | static、私有；改签名会影响三处调用（:110、:164） |
| `public boolean loadSound(FilenameURL filenameURL)` | LibraryLWJGLOpenAL.java:215 | 首次播放某文件（非流式） | 音频资源拦截/替换、缓存统计、格式转换 | 整文件解码在音频线程做，大文件会卡音频命令队列 |
| `public void unloadSound(String filename)` | LibraryLWJGLOpenAL.java:349 | 释放某文件 | 缓存淘汰观察 | 注意此处不删 AL buffer，只移 map（AL buffer 实际由 cleanup 统一删，见"陷阱"） |
| `public void newSource(...)` / `public void quickPlay(...)` | LibraryLWJGLOpenAL.java:360 / :394 | 每次 playSound（流式/非流式） | 观察/否决每一次播放、改 sourcename、注入自定义 Source 子类 | sourcename 是 SoundManager 生成的 UUID 串；同名会覆盖旧 source |
| `public void rawDataStream(AudioFormat audioFormat, boolean priority, String sourcename, float x, float y, float z, int attModel, float distOrRoll)` | LibraryLWJGLOpenAL.java:390 | 外部推 PCM 流（如语音） | 自定义 PCM 注入通道 | 配合 `feedRawAudioData`；vanilla 不用此路径 |
| `public void setMasterVolume(float value)` | LibraryLWJGLOpenAL.java:354 | 主音量改变 | 全局音量曲线/静音开关 | 直接写 AL_GAIN(4106) |
| `public void setListenerData(ListenerData l)` / `setListenerPosition` / `setListenerOrientation` | LibraryLWJGLOpenAL.java:485 / :456 / :473 | 每帧相机更新 | 3D 音频空间变换（镜头分离、录像回放视角） | 每帧调用，别做重活 |
| `public void play(Channel c)` | SourceLWJGLOpenAL.java:237 | source 被分配 channel 开播时 | 逐 source 的最终播放拦截、DSP 参数注入（此处 AL source id 可拿：`channelOpenAL.ALSource.get(0)`） | 换 channel 分支里状态重推顺序有依赖；c 可能为 null |
| `public void positionChanged()` | SourceLWJGLOpenAL.java:166 | source 或 listener 移动 | 自定义衰减/gain 曲线（覆盖 `calculateGain`） | 高频调用；gain 公式含 `fadeOutGain/fadeInGain` |
| `public boolean preLoad()` | SourceLWJGLOpenAL.java:339 | 流式开播/切歌后 StreamThread 首灌 | 流式数据源替换（网络电台等） | 在音频线程阻塞解码 |
| `public boolean incrementSoundSequence()` | SourceLWJGLOpenAL.java:90 | 流式播放列表切下一首 | 无缝衔接、动态歌单 | 需持 `soundSequenceLock` |
| `public int feedRawAudioData(byte[] buffer)` | ChannelLWJGLOpenAL.java:193 | raw stream 喂数据 | 实时 PCM（TTS/语音聊天）输出 | 喂太快会无限 `alGenBuffers`（无上限回收前提是播放跟得上） |
| `public float millisecondsPlayed()` | ChannelLWJGLOpenAL.java:240 | 查询播放进度 | 歌词/进度条同步 | 流式依赖 `millisPreviouslyPlayed` 累计，flush/stop 会清零（:297、:326） |
| `public void play()` / `pause()` / `stop()` | ChannelLWJGLOpenAL.java:313 / :318 / :323 | 最底层播放控制 | 最终级别的播放事件观察 | 直接 AL 调用，必须在持有 AL context 的线程 |

## 数据与协议

无封包/NBT/注册表。唯一的"协议"是 javax `AudioFormat` → OpenAL 格式常量的映射（在四处重复出现：LibraryLWJGLOpenAL.java:249-273、:311-335，ChannelLWJGLOpenAL.java:74-98，SourceLWJGLOpenAL.java:115-139、:304-328）：

| channels | sampleSizeInBits | AL 常量（源码字面值） | 含义 |
|---|---|---|---|
| 1 | 8 | 4352 | AL_FORMAT_MONO8 |
| 1 | 16 | 4353 | AL_FORMAT_MONO16 |
| 2 | 8 | 4354 | AL_FORMAT_STEREO8 |
| 2 | 16 | 4355 | AL_FORMAT_STEREO16 |

其余取值一律报错拒绝。源码全部用十进制魔数写 AL 枚举（4099=AL_PITCH、4100=AL_POSITION、4102=AL_VELOCITY、4103=AL_LOOPING、4105=AL_BUFFER、4106=AL_GAIN、4111=AL_ORIENTATION、4112=AL_SOURCE_STATE、4114=AL_PLAYING、4117=AL_BUFFERS_QUEUED、4118=AL_BUFFERS_PROCESSED、4129=AL_ROLLOFF_FACTOR、4134=AL_BYTE_OFFSET；40961-40965=AL_INVALID_NAME…AL_OUT_OF_MEMORY），这是 CFR 反编译产物，勿"顺手"改成常量名以免与上游 diff 失配。

## 不变量与陷阱

- **AL context 线程绑定**：`lwjgl3CreateAL()` 在哪个线程执行，`alcMakeContextCurrent` 就绑在哪个线程（实际是 SoundSystem CommandThread）。从主线程直接调 `AL10.*` 会拿到无 context 错误甚至 JVM 崩溃。想动 AL 状态必须经 `SoundSystem` 命令队列。
- **`alDevice`/`alContext` 是 static**（LibraryLWJGLOpenAL.java:62-63）：全进程只支持一个 OpenAL device/context 实例。`reloadSoundSystem()` 依赖 cleanup→init 的严格顺序；如果两个 Library 实例并存会互相踩句柄。
- `libraryCompatible()`（:159）在已创建时直接返回 true 而**不**销毁现有 context——这是移植时刻意的，避免探测把正在用的 context 拆掉。
- `codec.reverseByteOrder(true)`：`Library.reverseByteOrder = true` 在构造器设置（:104），Source 构造器也逐个设 codec（SourceLWJGLOpenAL.java:41、:54、:106）。OpenAL 要小端 PCM，丢了这一步流式声音变噪音。
- `unloadSound(String filename)`（:349）把 id 从 `ALBufferMap` 移除但**不调 `alDeleteBuffers`**——上游原样的泄漏，AL buffer 要到 `cleanup()` 才批量删除，而那时 `bufferMap` 里已不含该文件名，实际是永久泄漏到 context 销毁。
- `checkALError()` 在三个类中各有一份私有拷贝（ChannelLWJGLOpenAL.java:348、LibraryLWJGLOpenAL.java:523、SourceLWJGLOpenAL.java:387），语义是"取错误码并打日志"，会**清除** AL 错误标志；调用顺序里穿插的 `alGetError()` 都是刻意的清标志操作，删掉会让后续误报。
- AL_PITCH 探测失败会抛 `NO_AL_PITCH`(=108)（:149、:155），SoundSystem 捕获后可能降级换库；`alPitchSupported` 的读写经 `private static synchronized boolean alPitchSupported(boolean action, boolean value)`（:557）串行化。
- `createChannel`（:178）以 `alGenSources` 失败作为 channel 上限探测手段——返回 null 是正常路径，不是 bug。
- `SourceLWJGLOpenAL.setPosition`（:150）在 `sourcePosition == null` 时先 `resetALInformation()` 再无条件 `sourcePosition.put(...)`——依赖 `resetALInformation` 必定分配 buffer；两个分支后都会写，逻辑绕但正确。
- 大量空 catch（如 ChannelLWJGLOpenAL.java:42-51）是上游/反编译原样，cleanup 路径刻意吞异常保证释放继续。
- 所有 buffer 都用 `org.lwjgl.BufferUtils`（LWJGL3 的 direct buffer 工具），JDK25 下没有 `sun.misc` 依赖问题。

## 交叉引用

- net.minecraft.client.audio → `SoundManager#<init>`（`SoundSystemConfig.addLibrary(LibraryLWJGLOpenAL.class)`，SoundManager.java:78）
- net.minecraft.client.audio → `SoundManager.SoundSystemStarterThread`（extends `paulscode.sound.SoundSystem`，SoundManager.java:521）——所有 play/stop/setVolume/setListener* 的入口
- paulscode.sound（jar，非本源码树）→ `Library#init` / `Library#cleanup` / `Source#play` / `Channel`：本包三类的全部虚方法契约来自这些基类
- paulscode.sound（jar）→ `SoundSystemConfig#getCodec` / `#getNumberStreamingBuffers` / `#getDopplerFactor` / `#getDopplerVelocity`
- com.jcraft（jar，codecjorbis）→ `CodecJOrbis`：SoundManager.java 注册的 ogg 解码器，`loadSound`/`preLoad` 经 `ICodec` 接口调用
- org.lwjgl.openal → `AL10` / `ALC10` / `AL#createCapabilities` / `ALC#createCapabilities`：真实 LWJGL3 绑定（不是 lwjgl2-shim）

## 覆盖声明

完整读取了 3/3 个文件（ChannelLWJGLOpenAL.java 378 行、LibraryLWJGLOpenAL.java 596 行、SourceLWJGLOpenAL.java 417 行），三个类均逐行精读。另对 `net/minecraft/client/audio/SoundManager.java` 和 `client/pom.xml` 做了针对性浏览以确认调用方与依赖关系（未通读）。paulscode 基类（`Library`/`Channel`/`Source`/`SoundSystem`）来自 jar 依赖，未反编译核实，文中关于 CommandThread/StreamThread 的线程模型描述基于 paulscode SoundSystem 的公开行为与 SoundManager 的使用方式推断。
