---
area: org/lwjgl
slug: shim
files: 25
lines: 3745
tier: A
---

# org/lwjgl — LWJGL2 兼容层（lwjgl2-shim 模块）

## 定位

本包是独立模块 `lwjgl2-shim`，在 LWJGL3（GLFW + 现代绑定）之上重新实现 Minecraft 1.8.9 依赖的 LWJGL2 API 表面：`Display`、`Keyboard`、`Mouse`、`Sys`、`GLContext`/`ContextCapabilities`、`GLU`/`Project`、`util.vector` 数学类，以及 `LWJGLException`/`OpenGLException`/`PixelFormat`/`DisplayMode` 等被 MC 源码直接引用的类型。它让 1.8.9 客户端代码几乎零改动地跑在 LWJGL3 + JDK 25 运行时上。

- 谁调用它：`net.minecraft.client.Minecraft`（窗口创建、每帧 `Display.update()`/`sync()`、输入事件循环 `Mouse.next()`/`Keyboard.next()`）、`MouseHelper`（grab/ungrab、视角 delta）、`EntityRenderer`/`GuiMainMenu`/`GuiEnchantment`（`Project.gluPerspective`）、`ActiveRenderInfo`（`GLU.gluUnProject`）、`OpenGlHelper`（`GLContext.getCapabilities()`）、`ShaderGroup`/`FaceBakery`/`ModelRotation`/`RenderGlobal`（`Matrix4f`/`Vector3f`/`Vector4f`）、各 GUI（`Keyboard.enableRepeatEvents`）。
- 它调用谁：真正的 LWJGL3——`org.lwjgl.glfw.GLFW` 全家、`org.lwjgl.opengl.GL`/`GL11`/`GLCapabilities`、`org.lwjgl.system.MemoryStack`/`MemoryUtil`、`org.lwjgl.BufferUtils`、`org.lwjgl.Version`。
- 如果它消失：客户端根本无法启动——没有窗口（`Display.create` 是 `Minecraft.startGame` 的第一步之一）、没有键鼠输入、没有透视投影矩阵、`GLContext.getCapabilities()` 的能力检测全崩，且 `net.minecraft.util.Matrix4f extends org.lwjgl.util.vector.Matrix4f`（client/src/main/java/net/minecraft/util/Matrix4f.java:3）会直接编译失败。

内部分层：`input.Keyboard`/`input.Mouse` 是静态门面（facade），通过 `LWJGLImplementationUtils` 拿到进程级单例 `CombinedInputImplementation`，后者把键盘半边委托给 `GLFWKeyboardImplementation`、鼠标半边委托给 `GLFWMouseImplementation`；两个 GLFW 后端各持一个 `EventQueue`（定宽字节记录环）缓冲事件，门面在 `poll()` 时排空。

## 类清单

| 类名 | 行数 | extends/implements | 一句话职责 |
|---|---|---|---|
| `LWJGLException` | 33 | extends `Exception` | LWJGL2 受检异常，供 `Display.create`/输入初始化的 `throws` 子句用 |
| `LWJGLUtil` | 83 | — (final) | 平台常量与 `os.name` 检测，`DEBUG` 开关下的 stderr 日志 |
| `Sys` | 70 | — (final) | nanoTime 计时器、版本号（转发 LWJGL3 `Version`）、`openURL`（macOS 走 `open` 命令） |
| `impl.LWJGLImplementationUtils` | 28 | — (final) | 懒加载单例持有者，构造进程唯一的 `CombinedInputImplementation` |
| `impl.glfw.GLFWKeyboardImplementation` | 141 | implements `KeyboardImplementation` | 注册 GLFW key/char 回调，产出 18 字节 LWJGL2 键盘事件，合并 char 到前一 key-down |
| `impl.glfw.GLFWMouseImplementation` | 217 | implements `MouseImplementation` | 注册 GLFW 鼠标回调，Y 翻转 + HiDPI 缩放，产出 22 字节 LWJGL2 鼠标事件 |
| `impl.input.CombinedInputImplementation` | 75 | implements `InputImplementation` | 把键盘调用转给一个后端、鼠标调用转给另一个后端的组合器 |
| `impl.input.InputImplementation` | 11 | extends `KeyboardImplementation, MouseImplementation` | 键鼠合一的 SPI 标记接口 |
| `impl.input.KeyboardImplementation` | 28 | interface | 键盘后端 SPI：create/destroy/poll/read |
| `impl.input.MouseImplementation` | 45 | interface | 鼠标后端 SPI：create/destroy/poll/read/setCursorPosition/grab 等 |
| `input.Cursor` | 73 | — | 原生自定义光标 stub，零能力，仅为编译链接存在 |
| `input.Keyboard` | 551 | — | 静态键盘门面：KEY_* DirectInput 扫描码表、GLFW→LWJGL2 映射表、事件读取 |
| `input.Mouse` | 396 | — | 静态鼠标门面：位置/delta/滚轮/按钮状态、grab 语义、事件读取 |
| `opengl.ContextCapabilities` | 183 | — | public boolean 能力字段集合，反射从 LWJGL3 `GLCapabilities` 拷贝同名字段 |
| `opengl.Display` | 489 | — (final) | GLFW 窗口封装：创建/全屏/vsync/图标/帧率限制/framebuffer 尺寸与 HiDPI 缩放 |
| `opengl.DisplayMode` | 117 | — (final) | 不可变显示模式描述符，双参构造=窗口化、四参构造=可全屏 |
| `opengl.EventQueue` | 101 | — | 定宽字节记录的同步事件环（容量 200 条），支持取回最后一条以合并 char |
| `opengl.GLContext` | 35 | — (final) | ThreadLocal 缓存的 `ContextCapabilities` 入口，替代 LWJGL2 GLContext |
| `opengl.OpenGLException` | 71 | extends `RuntimeException` | GL 错误码格式化为可读消息的非受检异常 |
| `opengl.PixelFormat` | 223 | — (final) | 不可变像素格式 builder（`withDepthBits` 等），由 `Display` 翻译为 GLFW hints |
| `util.glu.GLU` | 236 | — | 纯 Java 复刻 `gluPerspective`/`gluErrorString`/`gluUnProject` |
| `util.glu.Project` | 54 | — | 仅复刻 `gluPerspective`，MC 有 8 处调用点 |
| `util.vector.Matrix4f` | 340 | — | 列主序 4x4 float 矩阵，public m00..m33 字段 + 静态 mul/rotate/transform/invert |
| `util.vector.Vector3f` | 109 | — | 可变三分量向量，public x/y/z + 静态 add/sub/cross/dot |
| `util.vector.Vector4f` | 66 | — | 可变四分量向量，public x/y/z/w，配合 `Matrix4f.transform` 使用 |

（`wc -l` 实测合计 3775 行，与 bucket 元数据 3745 略有出入，以实测为准。）

## 核心类详解

### opengl.Display（Display.java）

单窗口 GLFW 封装。"未创建"哨兵是 `-1L`（LWJGL2 ABI），区别于 GLFW 失败返回的 `NULL (0L)`（Display.java:24-30）。

关键字段：
- `private static long window = -1L`（Display.java:30）
- `private static int width = 854; private static int height = 480`——framebuffer 像素尺寸，即 `getWidth()/getHeight()` 报告值（Display.java:39-40）
- `private static float pixelScaleX/pixelScaleY = 1.0F`——framebuffer 像素 / 窗口单位比，Retina 上为 2.0（Display.java:48-49）
- `private static int windowedWidth/windowedHeight/windowedX/windowedY`——退出全屏时恢复用（Display.java:52-55）
- `private static boolean resized/focused/fullscreen/deferredFullscreen/vsyncEnabled`（Display.java:33-36, 57-58）
- `private static ByteBuffer[] cachedIcons`——窗口创建前 `setIcon` 的深拷贝缓存（Display.java:61）
- `private static long syncNext`——纯 Java 帧率限制器状态（Display.java:69）

关键方法（签名逐字）：
- `public static void create(PixelFormat pixelFormat) throws LWJGLException`（Display.java:171）——设置 GLFW hints（非 macOS 请求 3.2 COMPAT profile，Display.java:187-191；`GLFW_SCALE_TO_MONITOR=FALSE`，Display.java:198），创建窗口、`glfwMakeContextCurrent` + `GL.createCapabilities()`（Display.java:209-210），居中窗口，读取 framebuffer 尺寸，注册 `GLFWFramebufferSizeCallback` 与 `GLFWWindowFocusCallback`（Display.java:232-251），随后 `Mouse.create(); Keyboard.create();`（Display.java:255-256），`glfwShowWindow`，应用 vsync/延迟全屏/缓存图标。
- `public static void update()`（Display.java:270）——`glfwSwapBuffers` → `glfwPollEvents` → `Mouse.poll()` → `Keyboard.poll()`。每帧核心。
- `public static void sync(int fps)`（Display.java:281）——sleep(1ms) 粗等 + yield 自旋精等的帧率限制；落后超过一帧则重新对齐（Display.java:307-309）。
- `public static void setFullscreen(boolean fs)`（Display.java:375）——窗口未创建时置 `deferredFullscreen`；切换用 `glfwSetWindowMonitor`，切换后必须重设 swap interval（Display.java:409）。
- `public static int setIcon(ByteBuffer[] icons)`（Display.java:424）——深拷贝堆缓冲，创建后经 `MemoryStack` 转 direct buffer 再 `glfwSetWindowIcon`；macOS 直接返回 0（Display.java:442-444）。图标尺寸按 `sqrt(bytes/4)` 推断（Display.java:452）。
- `public static void destroy()`（Display.java:466）——先 `Keyboard.destroy(); Mouse.destroy();` 再 `Callbacks.glfwFreeCallbacks`，避免回调双重释放（Display.java:470-476），最后 `glfwTerminate` 并复位所有静态状态。
- `public static long getWindowHandle()`（Display.java:94）/ `public static float getPixelScaleX()`（Display.java:144）——供输入后端桥接坐标。
- `public static DisplayMode getDisplayMode()`（Display.java:312）、`getDesktopDisplayMode()`（Display.java:342）、`getAvailableDisplayModes()`（Display.java:356）、`setDisplayMode(DisplayMode mode)`（Display.java:327）、`setVSyncEnabled(boolean enabled)`（Display.java:413）、`wasResized()`（Display.java:161，读后清零）、`isCloseRequested()`（Display.java:157）、`isActive()`（Display.java:153）。

调用方：`Minecraft.createDisplay/startGame`（client .../Minecraft.java:621-626, 646）、每帧 `Minecraft.updateDisplay`（Minecraft.java:1207, 1217）、`Minecraft.toggleFullscreen`（Minecraft.java:1687）、关闭检测（Minecraft.java:1083）。

### input.Keyboard（Keyboard.java）

静态键盘门面。事件线格式 18 字节：`int key + byte state + int character + long nanos + byte repeat`（Keyboard.java:23-30, 40）。所有键码是 LWJGL2 DirectInput 扫描码，因为 MC 的按键设置文件持久化的就是它们（Keyboard.java:32-35）。

关键字段：
- `public static final int EVENT_SIZE = 4 + 1 + 4 + 8 + 1`（Keyboard.java:40）
- `public static final int KEYBOARD_SIZE = 256`（Keyboard.java:49）
- `private static final int BUFFER_SIZE = 50`（Keyboard.java:43）
- `KEY_NONE(0x00)` 到 `KEY_SLEEP(0xDF)` 的完整 KEY_* 常量表，经 `private static int register(String name, int scancode)` 同时写入 `keyNames[]` 与 `keyMap`（Keyboard.java:56-62, 66-193）；别名 `KEY_LWIN = KEY_LMETA`、`KEY_RWIN = KEY_RMETA`（Keyboard.java:196-197）
- `private static final ByteBuffer keyDownBuffer`——poll 快照（Keyboard.java:208）；`private static ByteBuffer readBuffer`——滚动事件缓冲（Keyboard.java:211）
- `private static final int[] GLFW_TO_LWJGL = new int[GLFW.GLFW_KEY_LAST + 1]`——GLFW→DirectInput 映射唯一真源（Keyboard.java:395，静态块 397-516 填充）

关键方法（签名逐字）：
- `public static void create() throws LWJGLException`（Keyboard.java:242）——要求 `Display.isCreated()`，否则抛 `IllegalStateException("Display must be created.")`。
- `public static void poll()`（Keyboard.java:274）——`implementation.pollKeyboard(keyDownBuffer)` 后 `read()`（compact→backend 追加→flip，Keyboard.java:282-286）。
- `public static boolean next()`（Keyboard.java:320）——repeat 被禁用时静默跳过 auto-repeat 事件（Keyboard.java:325-327）。
- `public static boolean isKeyDown(int key)`（Keyboard.java:288）、`public static int getEventKey()`（370）、`public static char getEventCharacter()`（366）、`public static boolean getEventKeyState()`（374）、`public static long getEventNanoseconds()`（378）、`public static boolean isRepeatEvent()`（382）。
- `public static void enableRepeatEvents(boolean enable)`（Keyboard.java:358）——GUI 文本框打开/关闭时被各 GuiScreen 调用。
- `public static synchronized String getKeyName(int key)`（298）/ `public static synchronized int getKeyIndex(String keyName)`（305）——按键绑定 UI 与 options 文件解析用。
- `public static int getKeyIndexFromGLFW(int glfwKey)`（Keyboard.java:530）——越界或 `GLFW_KEY_UNKNOWN(-1)` 返回 `KEY_NONE`。
- `public static int getNumKeyboardEvents()`（Keyboard.java:331）——不破坏读位置地计数。

调用方：`Minecraft.runTick` 的 `while (Keyboard.next())` 分发循环（Minecraft.java:1899-1902）；MC 的 `getEventKey()==0` 分支走 `getEventCharacter() + 256` 文本路径（Minecraft.java:1901）——这就是 char-only 事件 keyCode=0 约定的意义。

### input.Mouse（Mouse.java）

静态鼠标门面。事件线格式 22 字节：`byte button + byte state + int x + int y + int dz + long nanos`（Mouse.java:22-30, 38）。坐标由后端预先转成 LWJGL2 左下角原点（Mouse.java:32-33）。

关键字段：
- `public static final int EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8`（Mouse.java:38）；`private static final int BUFFER_SIZE = 50`（41）
- `private static int x, y`（钳制后位置）与 `absoluteX, absoluteY`（未钳制）（Mouse.java:66-69）
- `private static int dx, dy, dwheel`——累积 delta，`getDX()` 等读取即清零（Mouse.java:72-74）
- `private static int grabX, grabY`——grab 时保存、ungrab 时恢复光标的位置（Mouse.java:87-88）
- `private static int lastRawEventX, lastRawEventY`——非 grab 模式下计算逐事件 delta（Mouse.java:91-92）
- `private static boolean clipToWindow = !Boolean.getBoolean("org.lwjgl.input.Mouse.allowNegativeMouseCoords")`（Mouse.java:94-95）

关键方法（签名逐字）：
- `public static void create() throws LWJGLException`（Mouse.java:142）——同样要求 Display 已创建。
- `public static void poll()`（Mouse.java:175）——grab 时把后端报告的 delta 累加进 x/y/dx/dy；非 grab 时以绝对坐标差分出 dx/dy（Mouse.java:185-197）；`clipToWindow` 时钳制到 `[0, Display.getWidth()-1]`（199-202）。
- `public static boolean next()`（Mouse.java:242）——按 grab 状态解释事件里的坐标字段为 delta 或绝对值（Mouse.java:253-269）。
- `public static void setGrabbed(boolean grab)`（Mouse.java:355）——grab 时存 `grabX/grabY`，ungrab 时 `implementation.setCursorPosition(grabX, grabY)` 回警原位（Mouse.java:359-364）；随后 `grabMouse` → `poll()` → 事件坐标重置 → `resetMouse()`。
- `public static void setCursorPosition(int newX, int newY)`（Mouse.java:380）——grab 状态下只更新锚点不动真光标。
- `public static int getDX()`（321）/ `getDY()`（327）/ `getDWheel()`（333）——读取清零语义；`getEventButton()`（281）、`getEventButtonState()`（285）、`getEventX()`（297）、`getEventY()`（301）、`getEventDWheel()`（305）、`isButtonDown(int button)`（214）、`isInsideWindow()`（394）。

调用方：`Minecraft.runTick` 的 `while (Mouse.next())`（Minecraft.java:1833-1854）；`MouseHelper.grabMouseCursor/ungrabMouseCursor/mouseXYChange`（MouseHelper.java:19, 29-30, 35-36）。

### impl.glfw.GLFWKeyboardImplementation（GLFWKeyboardImplementation.java）

GLFW 键盘后端。关键设计：GLFW 的 key 回调与 char 回调是分离的，本类在 `private void postCharacter(int codepoint)`（GLFWKeyboardImplementation.java:85）里把 code point 合并进前一条仍为 key-down 且尚无字符的事件（借助 `EventQueue.getLastEvent()`，89-95）；无法合并的（死键/IME/组合输入）作为 keyCode=0 的独立事件发出（98），驱动 MC 的 `getEventKey()==0` 文本分支。

- `public void createKeyboard()`（GLFWKeyboardImplementation.java:47）——`GLFW.glfwSetKeyCallback` / `glfwSetCharCallback` 挂到 `Display.getWindowHandle()`。key 回调里未映射键（translate 结果 `<= 0`）直接丢弃，避免 KEY_NONE 被误标为按下（52-57）；`GLFW_REPEAT` 置 repeat 位，press/repeat 都算 down（59-67）。
- `public void pollKeyboard(ByteBuffer keyDownBuffer)`（123）——整块拷贝 `keyDownState[256]` 快照且不动 position。
- `public void readKeyboard(ByteBuffer readBuffer)`（129）——`eventQueue.copyEvents(readBuffer)`。
- `public void destroyKeyboard()`（112）——`free()` 两个回调。
- `public static int translateKeyFromGLFW(int glfwKey)`（138）——转发 `Keyboard.getKeyIndexFromGLFW`。

### impl.glfw.GLFWMouseImplementation（GLFWMouseImplementation.java）

GLFW 鼠标后端。坐标翻转 `y = Display.getHeight() - 1 - glfwY` 且乘 `Display.getPixelScaleX/Y()` 桥接 HiDPI（GLFWMouseImplementation.java:82-85）；滚轮按 `WHEEL_DELTA = 120` 缩放（35, 105）。

- `public void createMouse()`（60）——平台支持且未设 `org.lwjgl.input.Mouse.disableRawInput` 系统属性时开启 `GLFW_RAW_MOUSE_MOTION`（65-68）；注册 button/pos/scroll/enter 四个回调（124-127）。
- `public void pollMouse(IntBuffer coordBuffer, ByteBuffer buttonsBuffer)`（163）——grab 时写累积 delta、否则写绝对位置，随后清零累加器。
- `public void setCursorPosition(int x, int y)`（184）——反向撤销翻转与 HiDPI 缩放后 `glfwSetCursorPos`，并同步 `lastX/lastY` 防跳变。
- `public void grabMouse(boolean grab)`（197）——`GLFW_CURSOR_DISABLED/NORMAL` 切换，并 `eventQueue.clearEvents()` + 清空 delta（200-204）。
- `public int getButtonCount()`（211）——`GLFW.GLFW_MOUSE_BUTTON_LAST + 1`（8 个）。

### opengl.EventQueue（EventQueue.java）

同步定宽事件环，容量 `QUEUE_SIZE = 200` 条（EventQueue.java:21）。缓冲区在 put 之间保持 fill 模式（position=写游标）。

- `public synchronized boolean putEvent(ByteBuffer event)`（50）——事件尺寸不符抛 `IllegalArgumentException`；队满静默丢弃返回 false。
- `public synchronized void copyEvents(ByteBuffer dest)`（68）——flip→按 dest 余量截断→put→compact，未排空部分留待下次。
- `public synchronized ByteBuffer getLastEvent()`（87）——返回指向最近一条记录的可写 slice；队列为空时返回 `null`（93-95），否则会把独立字符"合并"进垃圾数据而丢字。
- `public synchronized void clearEvents()`（40）。

### opengl.GLContext 与 ContextCapabilities

- `public static ContextCapabilities getCapabilities()`（GLContext.java:24）——ThreadLocal 缓存，首次访问懒构建（GLContext.java:14, 26-34）。前提：该线程已 `GL.createCapabilities()`。
- `ContextCapabilities` 暴露 MC 读取的全部 public boolean 字段：`OpenGL13..OpenGL30`（ContextCapabilities.java:19-24）、约 57 个 `GL_ARB_*`（27-83）、31 个 `GL_EXT_*`（86-116）、`GL_NV_fog_distance`（119）。构造函数 `public ContextCapabilities()`（127）从 `GL.getCapabilities()` 取源，`private void copyFrom(GLCapabilities source)`（151）按字段名反射拷贝同名 public boolean；LWJGL3 没有的字段（如 `GL_ARB_compatibility`）保持 false（174-181）。

调用方：`OpenGlHelper.initializeTextures`（OpenGlHelper.java:99）、`EntityRenderer` 雾判断（EntityRenderer.java:1974, 2018）、`GuiMainMenu`（GuiMainMenu.java:148）、`Minecraft` snooper（Minecraft.java:2831）、`GuiStreamUnavailable`（165-167）。

### util.glu.GLU / Project

- `public static void gluPerspective(float fovy, float aspect, float zNear, float zFar)`（Project.java:19；GLU.java:24 有等价实现）——构建列主序透视矩阵后 `GL11.glMultMatrixf(buf)` 乘到当前 GL 矩阵。退化参数（deltaZ/sine/aspect 为 0）静默返回（Project.java:29-32）。
- `public static boolean gluUnProject(float winx, float winy, float winz, FloatBuffer modelMatrix, FloatBuffer projMatrix, IntBuffer viewport, FloatBuffer obj_pos)`（GLU.java:86-88）——proj*modelview 求逆做反投影，矩阵不可逆或 w=0 返回 false。使用静态临时数组 `IN/OUT/FINAL_MODELVIEW/FINAL_PROJ/TMP_MATRIX`（GLU.java:18-22），非线程安全非重入。
- `public static String gluErrorString(int error_code)`（GLU.java:56）——`Minecraft.checkGLError`（Minecraft.java:1032）与 `GLAllocation`（GLAllocation.java:27）使用。

### util.vector.Matrix4f / Vector3f / Vector4f

`Matrix4f` 列主序（mCR = 列 C 行 R），public 字段 `m00..m33`（Matrix4f.java:18-33）。关键静态方法签名：
- `public static Matrix4f setIdentity(Matrix4f m)`（45）
- `public static Matrix4f transpose(Matrix4f src, Matrix4f dest)`（71）
- `public static Matrix4f mul(Matrix4f left, Matrix4f right, Matrix4f dest)`（114）
- `public static Vector4f transform(Matrix4f left, Vector4f right, Vector4f dest)`（157）
- `public static Matrix4f rotate(float angle, Vector3f axis, Matrix4f src, Matrix4f dest)`（176）——angle 单位是弧度
- `public static Matrix4f invert(Matrix4f src, Matrix4f dest)`（232）——行列式为 0 返回 `null`
- `public float determinant()`（289）

所有 dest 参数允许 null（自动新建）。`net.minecraft.util.Matrix4f` 直接继承本类并逐字段写入；`ShaderGroup` 按字段构建正交矩阵；`FaceBakery`/`ModelRotation`/`RenderGlobal` 用静态 helpers（Matrix4f.java:8-11 注释、经 grep 确认调用方存在）。`Vector3f` 提供 `set/scale/length/lengthSquared` 与静态 `add/sub/cross/dot`（Vector3f.java:39-104）；`Vector4f` 仅字段 + `set/length`（Vector4f.java:44-61）。

### Sys / LWJGLUtil / 其余小类

- `public static long getTime()`（Sys.java:35）——`(System.nanoTime() - TIMER_OFFSET) & 0x7FFFFFFFFFFFFFFFL`，配合 `public static long getTimerResolution()`（30）返回 `1000000000L`。`Minecraft.getSystemTime()` 定义为 `Sys.getTime() * 1000L / Sys.getTimerResolution()`（Minecraft.java:3025），是整个客户端 tick 计时的基础。
- `public static boolean openURL(String url)`（Sys.java:39）——macOS 上 AWT Desktop 会碰 AppKit 主线程（GLFW 在 `-XstartOnFirstThread` 下已占用），故改为 `new ProcessBuilder("open", url).start()`（Sys.java:43-53）；其余平台走 `Desktop.browse`。
- `LWJGLUtil.getPlatform()`（LWJGLUtil.java:52）基于 `os.name` 静态检测；`log(CharSequence msg)`（77）仅 `DEBUG=true` 时输出。
- `PixelFormat`：不可变 builder，`new PixelFormat()` 默认 `(0, 8, 0, 0, 0, ...)` 即 8 位 alpha、0 深度（PixelFormat.java:37-40）；MC 用 `(new PixelFormat()).withDepthBits(24)`（Minecraft.java:626）。`Display.create` 只消费 `getDepthBits/getAlphaBits/getStencilBits/isStereo`（Display.java:192-195）。
- `DisplayMode`：`equals/hashCode` 不含 `fullscreenCapable`（DisplayMode.java:86-112），MC 全屏模式匹配（Minecraft.java:839-884）依赖该语义。
- `Cursor`：全 stub，`getCapabilities()` 恒 0（Cursor.java:47-50）——MC `GuiContainerCreative` 等不再能设置自定义硬件光标（静默降级）。
- `OpenGLException(int glErrorCode)`（OpenGLException.java:30）把 GL 错误码翻成 `"Invalid enum (0x500)"` 风格消息。

## 时序与生命周期

全部在**主线程（glfw 主线程，macOS 需 `-XstartOnFirstThread`）**上运行，无其它线程参与（`GLContext.getCapabilities()` 理论上任意线程可调，但只有主线程有 GL 上下文）。

初始化顺序（由 `Minecraft.startGame` 驱动）：
1. `Display.setResizable(true)` / `Display.setTitle(...)`（Minecraft.java:621-622）——窗口未建，只记状态。
2. `Display.create(new PixelFormat().withDepthBits(24))`（Minecraft.java:626）→ 内部：`ensureInit()`（glfwInit + error callback，Display.java:80-92）→ hints → `glfwCreateWindow` → `glfwMakeContextCurrent` + `GL.createCapabilities()` → 居中/framebuffer 尺寸/`updatePixelScale()` → 注册 resize/focus 回调 → **`Mouse.create()` + `Keyboard.create()`**（Display.java:255-256，二者经 `LWJGLImplementationUtils.getOrCreateInputImplementation()` 共享同一个 `CombinedInputImplementation`，并在 GLFW 窗口上注册 key/char/button/pos/scroll/enter 回调）→ `glfwShowWindow` → vsync/延迟全屏/图标。
3. 首次 `GLContext.getCapabilities()`（OpenGlHelper.initializeTextures，OpenGlHelper.java:99）构建 ThreadLocal 能力快照。

每帧（`Minecraft.runGameLoop`）：
- 渲染完成后 `Display.sync(fps)`（Minecraft.java:1207）限帧；
- `Display.update()`（Minecraft.java:1217）= swap buffers + `glfwPollEvents`（此刻 GLFW 回调同步触发、事件进入两个 `EventQueue`）+ `Mouse.poll()` + `Keyboard.poll()`（把事件从 EventQueue 排入门面的 readBuffer、刷新快照）；
- 随后 `Display.wasResized()` 检查（Minecraft.java:1224）驱动 `resize()`。

每 tick（`Minecraft.runTick`）：`while (Mouse.next())` / `while (Keyboard.next())` 消费 readBuffer 中的事件并分发到 `KeyBinding`/`GuiScreen`（Minecraft.java:1833, 1899）。

销毁：`Minecraft.shutdownMinecraftApplet` → `Display.destroy()`（Minecraft.java:1064）→ `Keyboard.destroy()`、`Mouse.destroy()`、`glfwFreeCallbacks`、`glfwDestroyWindow`、`glfwTerminate`（Display.java:466-489）。

## 挂钩点（Hook Points）

| 方法签名 | 文件:行号 | 何时被调用 | 在此可以做什么 | 风险/注意 |
|---|---|---|---|---|
| `public static void update()` | Display.java:270 | 每帧一次（Minecraft.java:1217） | 帧边界钩子：swap 前后插桩、截帧、覆盖层最后写入、FPS 统计 | swap 与事件泵在一起；改动顺序会影响输入延迟一帧 |
| `public static void sync(int fps)` | Display.java:281 | 每帧限帧（Minecraft.java:1207） | 接管帧率策略（自定义 pacing、无限 FPS、节能模式） | 自旋等待占 CPU；`syncNext` 状态在 destroy 时清零 |
| `public static void create(PixelFormat pixelFormat) throws LWJGLException` | Display.java:171 | 启动一次 | 修改 GLFW hints（GL 版本/profile/MSAA）、注入调试上下文、包装窗口创建 | macOS 分支不设版本 hints；`Mouse/Keyboard.create()` 在此内部发生，注入需在 show 前 |
| `public static void setFullscreen(boolean fs)` | Display.java:375 | F11 / 设置切换（Minecraft.java:1687） | 观察/改写全屏策略（无边框窗口化等） | 切换后必须重设 swap interval（已内置，Display.java:409） |
| `public static boolean wasResized()` | Display.java:161 | 每帧检查（Minecraft.java:1224) | 分辨率变更通知（重建 FBO、UI 缩放重算） | 读取即清除，一帧只能消费一次 |
| `public static void poll()` (Keyboard) | Keyboard.java:274 | 每帧 `Display.update()` 内 | 键盘状态快照/事件到达的统一入口：录制、注入、宏 | 抛 `IllegalStateException` 若未 create；必须在 `glfwPollEvents` 之后才有新事件 |
| `public static boolean next()` (Keyboard) | Keyboard.java:320 | 每 tick 事件循环（Minecraft.java:1899） | 拦截/吞掉/改写单条键盘事件（功能层键位接管的首选点） | repeat 过滤在此发生；吞事件会同时影响 KeyBinding 与 GuiScreen |
| `public static boolean isKeyDown(int key)` | Keyboard.java:288 | 各处即时查询（如 Minecraft.java:1916） | 伪造持续按住状态（如 freecam 锁定移动键） | 参数是 DirectInput 扫描码不是 GLFW 键码 |
| `public static void enableRepeatEvents(boolean enable)` | Keyboard.java:358 | GuiScreen 打开/关闭文本框时 | 观察 GUI 文本输入模式切换 | 全局标志，多层 GUI 嵌套时注意恢复 |
| `public static boolean next()` (Mouse) | Mouse.java:242 | 每 tick 事件循环（Minecraft.java:1833） | 拦截/改写点击、滚轮、移动事件（点击辅助、GUI 层输入接管） | 坐标解释依赖 grab 状态；事件坐标已 Y 翻转 |
| `public static void poll()` (Mouse) | Mouse.java:175 | 每帧 `Display.update()` 内 | 修改累积 dx/dy/dwheel（灵敏度、平滑） | delta 与绝对位置双轨制；clip 逻辑在此 |
| `public static int getDX()` / `public static int getDY()` | Mouse.java:321/327 | `MouseHelper.mouseXYChange`（MouseHelper.java:35-36），每帧视角更新 | 视角移动的最终数值出口：aim 类功能、回放注入的理想挂点 | 读取即清零，重复读返回 0 |
| `public static void setGrabbed(boolean grab)` | Mouse.java:355 | 进出 GUI 时（MouseHelper.java:19, 30） | 观察 GUI 打开/关闭（grab 状态即"是否在游戏视角"）；接管光标策略 | 内部会 poll 并清事件队列；ungrab 回警光标到 grab 前位置 |
| `public void invoke(long window, int glfwKey, int scancode, int action, int mods)`（GLFWKeyCallback 匿名类） | GLFWKeyboardImplementation.java:51 | `glfwPollEvents` 期间同步回调 | 最底层键盘注入/过滤点（先于扫描码翻译） | 未映射键在此被丢弃；改动会影响 keyDownState 一致性 |
| `public void invoke(long window, double xpos, double ypos)`（GLFWCursorPosCallback 匿名类） | GLFWMouseImplementation.java:81 | `glfwPollEvents` 期间同步回调 | 最底层鼠标移动注入（原始 delta 生成处） | HiDPI 缩放与 Y 翻转在此完成；grab/非 grab 事件语义不同 |
| `public synchronized boolean putEvent(ByteBuffer event)` | EventQueue.java:50 | 每次 GLFW 回调产出事件 | 合成事件注入的通用入口（对键鼠皆有效） | 队满（200 条）静默丢弃；record 布局必须逐字节正确 |
| `public static ContextCapabilities getCapabilities()` | GLContext.java:24 | 渲染器/GUI 能力检查（OpenGlHelper.java:99 等） | 伪造/屏蔽 GL 能力（强制 FBO 路径、禁用 shader 分支） | ThreadLocal 缓存，首次访问后再改字段才生效于同一实例 |
| `public static void gluPerspective(float fovy, float aspect, float zNear, float zFar)` | Project.java:19 | 每帧多次（EntityRenderer.java:766, 844, 1352 等 8 处） | 改写 FOV/投影（zoom、动态 FOV 类功能的矩阵级挂点） | 直接 `glMultMatrixf` 到当前矩阵栈，必须在正确的 matrix mode 下 |
| `public static boolean gluUnProject(float winx, float winy, float winz, FloatBuffer modelMatrix, FloatBuffer projMatrix, IntBuffer viewport, FloatBuffer obj_pos)` | GLU.java:86 | `ActiveRenderInfo.updateRenderInfo`（ActiveRenderInfo.java:61） | 观察/替换相机世界坐标解算 | 静态临时数组，非线程安全 |
| `public static boolean openURL(String url)` | Sys.java:39 | 资源包目录等 UI 动作（GuiScreenResourcePacks.java:164） | 拦截外链打开 | macOS 走子进程 `open`，不经 AWT |
| `public static long getTime()` | Sys.java:35 | `Minecraft.getSystemTime()`（Minecraft.java:3025），tick 计时核心 | 时间缩放/慢动作（谨慎） | 单调、类加载起算；改动影响所有 Timer 逻辑 |

## 数据与协议

无网络封包/NBT/文件格式。本包唯一的"线格式"是两种进程内事件记录（历史 LWJGL2 ABI），字段级布局如下。

键盘事件（`Keyboard.EVENT_SIZE = 18` 字节，Keyboard.java:40；写入 GLFWKeyboardImplementation.java:101-110，读取 Keyboard.java:346-356）：

| 偏移 | 字段 | 类型 | 写方法 | 读方法 | 含义 |
|---|---|---|---|---|---|
| 0 | keyCode | int | `scratch.putInt(keyCode)` | `readBuffer.getInt()` | LWJGL2 DirectInput 扫描码；0 = char-only 事件 |
| 4 | state | byte | `scratch.put(state)` | `readBuffer.get() != 0` | 1 = 按下（含 repeat），0 = 释放 |
| 5 | character | int | `scratch.putInt(character)` | `readBuffer.getInt()` | Unicode code point，0 = 无字符（可被 charCallback 事后合并写入，GLFWKeyboardImplementation.java:93） |
| 9 | nanos | long | `scratch.putLong(nanos)` | `readBuffer.getLong()` | `System.nanoTime()` 生成时刻 |
| 17 | repeat | byte | `scratch.put(repeat ? (byte) 1 : (byte) 0)` | `readBuffer.get() == 1` | 1 = OS auto-repeat |

鼠标事件（`Mouse.EVENT_SIZE = 22` 字节，Mouse.java:38；写入 GLFWMouseImplementation.java:144-154，读取 Mouse.java:250-277）：

| 偏移 | 字段 | 类型 | 写方法 | 读方法 | 含义 |
|---|---|---|---|---|---|
| 0 | button | byte | `scratch.put(button)` | `readBuffer.get()` | 按钮索引；-1 = 移动/滚轮事件 |
| 1 | state | byte | `scratch.put(state)` | `readBuffer.get() != 0` | 1 = 按下，0 = 释放 |
| 2 | x / dx | int | `scratch.putInt(coord1)` | `readBuffer.getInt()` | grab 时为 dx，否则为绝对 x（GL 左下原点、framebuffer 像素） |
| 6 | y / dy | int | `scratch.putInt(coord2)` | `readBuffer.getInt()` | 同上（y 已按 `Display.getHeight()-1-glfwY` 翻转） |
| 10 | dz | int | `scratch.putInt(dz)` | `readBuffer.getInt()` | 滚轮增量，每格 ±120（WHEEL_DELTA） |
| 14 | nanos | long | `scratch.putLong(nanos)` | `readBuffer.getLong()` | `System.nanoTime()` 生成时刻 |

## 不变量与陷阱

- **调用顺序不变量**：`Display.create()` 必须先于 `Keyboard.create()`/`Mouse.create()`（二者显式检查并抛 `IllegalStateException("Display must be created.")`，Keyboard.java:243-245 / Mouse.java:143-145）；未 create 就 `poll()`/`next()`/`isKeyDown()` 也抛 `IllegalStateException`。
- **单线程约束**：GLFW 要求窗口/事件 API 在主线程调用；macOS 必须加 `-XstartOnFirstThread`（仓库根有 `jvm-args-jdk25.txt`）。`EventQueue` 虽然 `synchronized`，但生产与消费实际都在主线程。`GLU.gluUnProject` 与 `Project.gluPerspective` 用静态 scratch 数组，绝不可跨线程调用。
- **键码域**：门面层一切键码都是 LWJGL2 DirectInput 扫描码（options.txt 持久化格式）；GLFW 键码只存在于 `GLFWKeyCallback` 内部，经 `Keyboard.getKeyIndexFromGLFW`（Keyboard.java:530）翻译。未映射键被后端丢弃（GLFWKeyboardImplementation.java:53-57）——F20+ 与媒体键对 MC 不可绑定。
- **坐标域**：`Display.getWidth()/getHeight()` 是 framebuffer 像素（LWJGL2 语义）；GLFW 光标回调给的是窗口单位。Retina 上二者差 2 倍，由 `Display.getPixelScaleX/Y()` 桥接（Display.java:42-49；GLFWMouseImplementation.java:82-85）。任何绕过门面直接读 GLFW 光标位置的代码都要自己处理缩放 + Y 翻转。
- **grab 语义双轨**：grab 时事件与 poll 的坐标字段是 delta，非 grab 时是绝对位置（MouseImplementation.java:21-27）。`Mouse.setGrabbed` 切换时清空事件队列与累加器（GLFWMouseImplementation.java:200-204），切换瞬间不要指望读到残留事件。
- **`getDX()/getDY()/getDWheel()` 读取即清零**（Mouse.java:321-337）——同一帧内第二个消费者拿到 0，功能层若要观察视角 delta 必须包装而非二次读取。
- **事件队列容量**：每个设备 200 条（EventQueue.java:21），门面 readBuffer 每次最多再囤 50 条（BUFFER_SIZE，Keyboard.java:43 / Mouse.java:41）；队满静默丢事件（EventQueue.java:56-60），长时间不 poll（如卡顿）会丢输入。
- **char 合并启发式**：字符只会合并进"前一条、keyCode>0、state==1、character==0"的事件（GLFWKeyboardImplementation.java:89-95）；队列为空时 `getLastEvent()` 返回 null 是关键防御（EventQueue.java:92-95），否则会把字符并进脏数据丢字。
- **macOS 特例**：不设 GL 版本 hints（macOS 无 COMPAT profile，Display.java:186-191）、`setIcon` 恒不生效返回 0（Display.java:442-444）、`Sys.openURL` 走 `open` 子进程避免 AWT/AppKit 与 GLFW 抢主线程（Sys.java:43-53）。
- **`ContextCapabilities` 是一次性反射快照**：LWJGL3 缺失的字段永远 false（如 `GL_ARB_compatibility`、`GL_EXT_paletted_texture`）；上下文重建后 ThreadLocal 缓存不会自动失效（GLContext.java:14 无清除路径——本客户端窗口只创建一次所以无碍）。
- **`Display` 哨兵**：`window == -1L` 表示未创建（Display.java:30），不要与 GLFW 的 `NULL(0)` 混淆；`glfwCreateWindow` 失败时代码显式把 window 置回 -1L（Display.java:203-206）。
- **销毁顺序**：必须 `Keyboard/Mouse.destroy()` 在 `Callbacks.glfwFreeCallbacks` 之前，否则回调被双重 free（Display.java:470-476 注释）。
- **`Cursor` 是纯 stub**：自定义原生光标静默无效，依赖它的视觉效果（如创造模式物品栏拖动光标）不会出现。

## 交叉引用

- `net.minecraft.client` → `Minecraft#startGame`（Display.create/setTitle/setIcon，Minecraft.java:621-681）、`Minecraft#updateDisplay`（Display.sync/update，1207/1217）、`Minecraft#runTick`（Mouse.next/Keyboard.next 事件循环，1833/1899）、`Minecraft#toggleFullscreen`（1687）、`Minecraft#getSystemTime`（Sys.getTime，3025）、`Minecraft#checkGLError`（GLU.gluErrorString，1032）
- `net.minecraft.util` → `MouseHelper#grabMouseCursor/#ungrabMouseCursor`（Mouse.setGrabbed，MouseHelper.java:19/30）、`MouseHelper#mouseXYChange`（Mouse.getDX/getDY，35-36）；`Matrix4f`（extends org.lwjgl.util.vector.Matrix4f，Matrix4f.java:3）
- `net.minecraft.client.renderer` → `EntityRenderer`（Project.gluPerspective ×6、GLContext.getCapabilities().GL_NV_fog_distance）、`ActiveRenderInfo#updateRenderInfo`（GLU.gluUnProject，61）、`OpenGlHelper#initializeTextures`（GLContext.getCapabilities，99）、`GLAllocation`（GLU.gluErrorString，27）、`RenderGlobal`（Matrix4f/Vector4f）
- `net.minecraft.client.renderer.block.model` → `FaceBakery`/`BlockPartRotation`/`ItemTransformVec3f`/`ItemModelGenerator`（util.vector 数学类）
- `net.minecraft.client.resources.model` → `ModelRotation`（Matrix4f.rotate 等）
- `net.minecraft.client.shader` → `ShaderGroup`/`Shader`（Matrix4f 逐字段构建正交投影）
- `net.minecraft.client.gui` → `GuiMainMenu`（Project.gluPerspective:381、GLContext 能力检查:148）、`GuiEnchantment`（Project.gluPerspective:120）、`GuiChat` 等十余个 GUI（Keyboard.enableRepeatEvents）、`GuiScreenResourcePacks`（Sys.openURL，164）、`GuiStreamUnavailable`（ContextCapabilities 字段，165-167）
- 对外依赖：`org.lwjgl.glfw.GLFW#glfwCreateWindow/#glfwPollEvents/#glfwSetKeyCallback/...`、`org.lwjgl.opengl.GL#createCapabilities/#getCapabilities`、`org.lwjgl.opengl.GL11#glMultMatrixf`、`org.lwjgl.system.MemoryStack#stackPush`、`org.lwjgl.BufferUtils#createByteBuffer`、`org.lwjgl.Version#getVersion`

## 覆盖声明

完整读取了 25/25 个文件（每个文件从第 1 行读到最后一行）。逐行精读：`Display`、`Keyboard`、`Mouse`、`GLFWKeyboardImplementation`、`GLFWMouseImplementation`、`EventQueue`、`GLContext`、`ContextCapabilities`、`Sys`、`GLU`、`Project`、`Matrix4f`。结构性通读（内容简单、全文已过目但无需逐行推敲）：`LWJGLException`、`LWJGLUtil`、`LWJGLImplementationUtils`、`CombinedInputImplementation`、`InputImplementation`、`KeyboardImplementation`、`MouseImplementation`、`Cursor`、`DisplayMode`、`OpenGLException`、`PixelFormat`、`Vector3f`、`Vector4f`。客户端侧调用点（Minecraft.java、MouseHelper.java 等）仅经 grep 验证行号，未整读。实测行数合计 3775，与 bucket 标注的 3745 有 30 行出入。
