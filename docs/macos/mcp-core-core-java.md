# core module (Java) — macOS arm64 移植调查

调查对象:`origin/mcp-core` 分支上的 `core/src/main/java/**` 与 `core/pom.xml`(只读,未检出分支)。

## 这个模块是什么

- `core` 是一个纯 Java 模块(`core/pom.xml`),把运行中的 Minecraft 1.8.9 客户端通过 Model Context Protocol 暴露给 AI:观察(状态/数据包/截图)、控制(移动/交互/GUI 点击)、热加载(JavaCompiler + Instrumentation 把代码编译进运行中的游戏)。pom 描述原文即如此(`core/pom.xml:16-24`)。
- 编译目标是 Java 25(`core/pom.xml:29`,覆盖父 pom 的 release=8),与 Java 8 的游戏字节码跑在同一 JVM 里。依赖:byte-buddy 1.17.6、MCP SDK 2.0.0、ASM 9.8、junit(test);游戏 `client` 模块为 `provided`。没有任何平台相关的 Maven profile 或 native 依赖。
- 入口是 Java agent:`boot/CoreAgent.java` 的 `premain`/`agentmain`(`-javaagent:core-all.jar`,manifest 由 maven-jar-plugin / shade 写入,`core/pom.xml:117-124, 168-172`),用 ByteBuddy 织入 `Minecraft.startGame()`,游戏初始化完成后由 `boot/CoreBootstrap.java` 点火 `McpCore`。
- 对外 IPC 全部是回环 TCP:
  - `io/transport/SocketTransportServer.java` — MCP JSON-RPC over loopback TCP,显式绑定 `127.0.0.1:25599`(`SocketTransportServer.java:36,64-70`);
  - `io/http/HttpFacade.java` — JDK 内置 `com.sun.net.httpserver.HttpServer`(`HttpFacade.java:13-14`),默认绑定 `127.0.0.1`(`McpCore.java:342`);
  - `alpc/AlpcServer.java` — 名字借用 Windows ALPC 概念,实现是普通 `java.net.ServerSocket`(`AlpcServer.java:10-11,54`),无命名管道。
- `kd/KdBridge.java` 是可选原生 JVMTI 调试器的 Java 侧:静态初始化尝试 `System.load(-Dmcp.core.jvmtiLib)` 或 `System.loadLibrary("core-jvmti")`(`KdBridge.java:48-52`),任何失败都优雅降级为 `isAvailable()==false`,`debug_*` 工具照常注册但返回诚实错误。
- `se/` 包里的 "Windows" 术语(AppContainer capability SID、MIC integrity level,`se/CapabilitySid.java:6`、`se/IntegrityLevel.java:4`)只是安全模型的命名灵感,全是纯 Java 实现。

## 文件清单

逐文件读过的(main 共 228 个 Java 文件,其余通过下述模式化 grep 全量覆盖):

- `core/pom.xml` — 模块定义:Java 25、byte-buddy/MCP SDK/ASM 依赖、agent manifest、shade fat jar;无平台相关内容。
- `core/src/main/java/net/marcloud/mcp/core/McpCore.java`(节选)— 总装配:启动 socket/HTTP 传输、MemoryStore(相对路径 `mcp_memory.json`,`McpCore.java:160`)、各系统属性开关;注释提到 `-agentpath:core-jvmti.dll`(`McpCore.java:286`)。
- `core/src/main/java/net/marcloud/mcp/core/boot/CoreAgent.java` — premain/agentmain 与启动钩子;动态自附加走 `-Djdk.attach.allowAttachSelf=true`(注释 `CoreAgent.java:27`),未直接调用 `VirtualMachine.attach`。
- `core/src/main/java/net/marcloud/mcp/core/boot/CoreBootstrap.java` — 一次性点火 McpCore,全部异常吞掉保游戏。
- `core/src/main/java/net/marcloud/mcp/core/kd/KdBridge.java` — 原生 JVMTI 库加载与优雅降级(见阻碍表)。
- `core/src/main/java/net/marcloud/mcp/core/kd/DebugTools.java`(节选)— debug_* MCP 工具;错误文案硬编码 `core-jvmti.dll`(`DebugTools.java:38,237`)。
- `core/src/main/java/net/marcloud/mcp/core/io/transport/SocketTransportServer.java` — loopback TCP 上复用 MCP SDK stdio 编解码;显式 IPv4 127.0.0.1。
- `core/src/main/java/net/marcloud/mcp/core/drivers/video/ScreenCapture.java` — 游戏线程上 `glGetTexImage` 读 FBO → BufferedImage → 缩放 → PNG;尺寸取自 `fb.framebufferWidth/Height`,与显示器 DPI 无关。
- `core/src/main/java/net/marcloud/mcp/core/drivers/video/DevProbe.java` — 纯聚合诊断,无平台代码。
- `core/src/main/java/net/marcloud/mcp/core/drivers/store/MemoryStore.java` — JSON 持久化:临时文件 + `ATOMIC_MOVE`,带 `AtomicMoveNotSupportedException` 回退(`MemoryStore.java:93-99`);APFS 上无问题。
- `core/src/main/java/net/marcloud/mcp/core/ldr/FileWatchDeployer.java` — `FileSystems.getDefault().newWatchService()` 监视 `.java` 目录,纯 NIO。
- `core/src/main/java/net/marcloud/mcp/core/ldr/InMemoryCompiler.java`(节选)— `ToolProvider.getSystemJavaCompiler()`,classpath 直接继承 `System.getProperty("java.class.path")`(`InMemoryCompiler.java:42`),不手拼分隔符。
- `core/src/main/native/core-jvmti/build-clang.sh` — 模块范围外,但为佐证读过:仅面向 Windows(输出 `.dll`、`-I$JBRINC/win32`、找 `/c/Program Files/LLVM/bin/clang.exe`)。

grep 全量扫过 `core/src/main/java/**` + `core/pom.xml` 的模式(均无命中或命中为良性):`os.name`、`os.arch`、`.dll`/`System.load(Library)`、`cmd.exe`/`powershell`/`taskkill`/`wmic`/registry、`Runtime.exec`/`ProcessBuilder`/`ProcessHandle`、`VirtualMachine`/`tools.jar`/`jdk.attach`、named pipe/`\\\\`/盘符 `C:\`、`File.separator`/`path.separator`/硬编码 `;` classpath、`AppData`/`user.home`/temp 目录、`java.awt`、`localhost`/socket 绑定、`com.sun.*`/`sun.misc`、LWJGL/GLFW 引用。`java.awt` 命中仅限图像类(`drivers/gui/GuiTools.java:7`、`drivers/gui/SoMOverlay.java:5-10`、`drivers/video/ScreenCapture.java:3-5` 的 `BufferedImage`/`Graphics2D`/`ImageIO`),不触碰 `Toolkit`/`Robot`/剪贴板/窗口。

## macOS 移植阻碍

| 问题 | 位置 (file:line) | 严重度 | 具体怎么改 | 工作量 |
|---|---|---|---|---|
| 原生 JVMTI 调试库只有 Windows 构建产物:Java 侧 `System.loadLibrary("core-jvmti")` 在 macOS 会找 `libcore-jvmti.dylib`,而该库当前只以 `core-jvmti.dll` 形式构建(`build-clang.sh` 硬编码 Windows 路径与 `win32` 头)。Java 代码本身已优雅降级(启动不受影响,仅 `debug_*` 9 个工具不可用) | `core/src/main/java/net/marcloud/mcp/core/kd/KdBridge.java:48-52`;佐证 `core/src/main/native/core-jvmti/build-clang.sh:1-40` | 中(功能缺失,非崩溃) | Java 侧零改动。为 macOS 编 `libcore-jvmti.dylib`:`clang -shared -O2 -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin core-jvmti.c -o libcore-jvmti.dylib`,启动加 `-agentpath:<abs>/libcore-jvmti.dylib -Dmcp.core.jvmtiLib=<abs>/libcore-jvmti.dylib`(native 侧属另一模块调查范围) | small |
| 用户可见文案硬编码 `-agentpath:core-jvmti.dll`,macOS 上会误导使用者 | `core/src/main/java/net/marcloud/mcp/core/kd/KdBridge.java:39`;`core/src/main/java/net/marcloud/mcp/core/kd/DebugTools.java:237`(另有注释性提及 `DebugTools.java:38`、`McpCore.java:286`、`DebuggerUnavailableException.java:5`) | 低(纯文案) | 把两处字符串改为按 `System.mapLibraryName("core-jvmti")` 生成或写成平台中立的 "core-jvmti native library";注释可不动 | trivial |

除此之外:**无**。具体地——没有 `os.name` 分支;没有进程派生(全模块无 `Runtime.exec`/`ProcessBuilder`);没有注册表访问;没有命名管道(`alpc` 包名字像 Windows ALPC,实现是 loopback `ServerSocket`,`AlpcServer.java:10-11`);没有硬编码路径分隔符或盘符(仅有的文件路径是 cwd 相对的 `mcp_memory.json` 和 NIO `Path`);classpath 传递用 `System.getProperty("java.class.path")` 原样透传(`InMemoryCompiler.java:42`);文件写入用 NIO 临时文件 + 原子 move 且带回退,无 Windows 式文件锁假设;JVM attach 只依赖 `Instrumentation`(premain)与可选的 `-Djdk.attach.allowAttachSelf=true` 自附加,无 `tools.jar`/进程 id 处理;`java.awt` 仅用无头安全的图像类。`core/pom.xml` 无平台 profile、无 native classifier。

## 不确定的地方

1. **AWT 图像类与 `-XstartOnFirstThread` 的共存**:`ScreenCapture`/`SoMOverlay` 只用 `BufferedImage`/`Graphics2D`/`ImageIO`,理论上无头安全、不启动 AppKit;但 macOS 上 GLFW 占用主线程时首次加载 AWT 偶有冲突的历史案例。建议真机验证 `screenshot`/`gui_snapshot` 工具,必要时加 `-Djava.awt.headless=true`(对这些图像类无副作用)。
2. **Retina 下 Set-of-Marks 叠加是否对齐**:`GuiSnapshotService.java:193-202` 用 `new ScaledResolution(mc)` 的 `scaleFactor` 并把 `mc.displayWidth/Height` 当 framebuffer 尺寸,而截图实际尺寸来自 `fb.framebufferWidth`(`ScreenCapture.java:74-76`)。已知 macOS 分支对光标坐标做了 Retina framebuffer 比例缩放;若该分支上 `mc.displayWidth` 是窗口点数而非像素,`SoMOverlay` 的 scaled-GUI→framebuffer 映射(`SoMOverlay.java:58`)会整体偏移一倍。需在真机上开一个 GUI 截图核对标注框位置。
3. **`FileWatchDeployer` 的 WatchService 延迟**:JDK 22+ 在 macOS 有基于 FSEvents 的实现,Temurin 25 应该已含;但热加载"保存即部署"的实际延迟需真机确认(旧 JDK 在 macOS 上是轮询实现,延迟可达数秒)。
4. **动态自附加路径**:`agentmain` 回退(`CoreAgent.java:27` 注释)依赖 `jdk.attach.allowAttachSelf`,macOS 上走 Unix domain socket 机制,预期可用但本次未在真机验证;首选的 `-javaagent` 路径与平台无关。
