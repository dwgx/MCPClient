# board 模块 macOS arm64 移植调查(origin/mcp-core)

## 这个模块是什么

board 是一个纯 Java 的客户端功能框架(Maven 模块 `board`,artifactId `board`),自称 mcp-core 的"对等模块"(peer):两边零编译期依赖,互相只通过反射和 `Backplane` 服务注册表发现对方(`board/pom.xml` 的 description,以及 `Board.java:101-103`)。它包含:

- 框架骨架:`Trace`(自研事件总线)、`Chip`(功能单元)、`Matrix`(功能管理器)、`Clock`(优先级)、`Signal`(事件基类)、`Backplane`(服务注册表)、`Board`(静态门面)。
- 内置功能芯片(`chips/`):ChatLogChip、TickCounterChip、FullbrightChip、CoordinatesHudChip、FpsMeterChip、LoginChip、StartupScreenChip、ChipBridgePort(向 dwm 启动器发布芯片清单/开关命令)。
- HUD(`hud/`)、按键绑定(`input/`)、跨模块桥(`link/`)、持久化(`persist/`,自带 JSON 编解码 + 原子写文件)、信号类型(`signals/`,全部是纯 primitive 值对象)。

对游戏的所有访问全部经过 `Class.forName("net.minecraft.client.Minecraft")` 反射,任何失败(headless/映射漂移)都被吞掉降级为 no-op。编译目标 Java 8(pom 继承 release=8),运行时依赖只有 JDK;`client` 与 `pg-api` 均为 provided,测试仅 JUnit 4.13.2(`board/pom.xml:31-59`)。构建期由 `pg-maven-plugin`(seed=1337)对 `@Guarded` 类(仅 `TickCounterChip`)做字节码加固(`board/pom.xml:76-95`)。

关键结论:**全模块没有任何 OS 分支、native 库名、进程创建、AWT/Swing 引用或平台路径假设**。用 `git grep -E "os\.name|lwjgl|java\.awt|javax\.swing|ProcessBuilder|Runtime\.getRuntime|loadLibrary|File\.separator|\.dll|\.dylib"` 扫描 `origin/mcp-core -- board/` 只命中 `Json.java` 里的转义字符序列(误报)。文件 I/O 全部走 `java.nio.file.Path`(`persist/Store.java:1-10`),原子写用 `ATOMIC_MOVE` 并对不支持的文件系统回退 `REPLACE_EXISTING`(`Store.java:158-160`),APFS 上无问题。

## 文件清单

主代码(逐个完整读过):

- `board/pom.xml` — 模块 POM:client/pg-api provided、JUnit test、pg-maven-plugin 加固。
- `board/src/main/java/net/marcloud/mcp/board/Backplane.java` — 静态 ConcurrentHashMap 服务注册表,跨子系统零耦合发现。
- `board/src/main/java/net/marcloud/mcp/board/Board.java` — 静态门面;`init()` 发布 BoardPort、装 OfficialChips、发布 ChipBridgePort。
- `board/src/main/java/net/marcloud/mcp/board/Chip.java` — 功能单元基类,生命周期 + 自动退订袋 + 键位属性。
- `board/src/main/java/net/marcloud/mcp/board/Clock.java` — 订阅优先级枚举。
- `board/src/main/java/net/marcloud/mcp/board/Manager.java` — id 键管理器接口。
- `board/src/main/java/net/marcloud/mcp/board/Matrix.java` — LinkedHashMap 功能管理器(非线程安全,游戏线程用)。
- `board/src/main/java/net/marcloud/mcp/board/Signal.java` — 信号基类 + Cancellable。
- `board/src/main/java/net/marcloud/mcp/board/Trace.java` — 自研事件总线,COW 存储 + 按运行时类缓存派发表。
- `board/src/main/java/net/marcloud/mcp/board/chips/ChatLogChip.java` — 观察 ChatSendSignal 并持久化计数,纯 JDK。
- `board/src/main/java/net/marcloud/mcp/board/chips/ChipBridgePort.java` — 向 Backplane 发布 `chip.roster`/`chip.toggle`;toggle 反射经 `Minecraft.addScheduledTask(Callable)` 调度到游戏线程,5s 超时。
- `board/src/main/java/net/marcloud/mcp/board/chips/CoordinatesHudChip.java` — 反射读 `thePlayer.posX/posY/posZ`。
- `board/src/main/java/net/marcloud/mcp/board/chips/FpsMeterChip.java` — 反射读静态 `Minecraft.getDebugFPS()`。
- `board/src/main/java/net/marcloud/mcp/board/chips/FullbrightChip.java` — 反射改 `gameSettings.gammaSetting`,禁用时恢复。
- `board/src/main/java/net/marcloud/mcp/board/chips/LoginChip.java` — 样例登录芯片,反射读 `getSession().getUsername()`。
- `board/src/main/java/net/marcloud/mcp/board/chips/OfficialChips.java` — 内置芯片清单安装器,`mcp.board.officialChips` 属性可关。
- `board/src/main/java/net/marcloud/mcp/board/chips/StartupScreenChip.java` — 样例启动屏芯片,反射探测 `currentScreen`/`displayGuiScreen`。
- `board/src/main/java/net/marcloud/mcp/board/chips/TickCounterChip.java` — 计 tick 的样例芯片,`@Guarded` 加固首个消费者。
- `board/src/main/java/net/marcloud/mcp/board/hud/HudMatrix.java` — HUD 管理器,订阅 RenderSignal 逐面板绘制;自身不碰 GL。
- `board/src/main/java/net/marcloud/mcp/board/hud/Panel.java` — HUD 元素基类,锚点布局纯算术,GL 只进 `onRender`。
- `board/src/main/java/net/marcloud/mcp/board/input/Pin.java` — 键绑定(TOGGLE/HOLD),持有 int 键码,无输入库依赖。
- `board/src/main/java/net/marcloud/mcp/board/input/PinMatrix.java` — 键绑定注册表,按键码路由 KeySignal。
- `board/src/main/java/net/marcloud/mcp/board/link/BoardPort.java` — Board 对外反射端口,Backplane 键 `board.port`。
- `board/src/main/java/net/marcloud/mcp/board/link/McpLink.java` — 反射探测/点火 mcp-core(`McpCore`、`CoreBootstrap.core()`/`onGameInitialized()`)。
- `board/src/main/java/net/marcloud/mcp/board/persist/DataView.java` — 容错类型化 key/value 包。
- `board/src/main/java/net/marcloud/mcp/board/persist/Json.java` — 零依赖手写 JSON 编解码。
- `board/src/main/java/net/marcloud/mcp/board/persist/Persistable.java` — 自序列化契约接口。
- `board/src/main/java/net/marcloud/mcp/board/persist/Store.java` — 原子写 + 损坏隔离的持久化引擎,`java.nio.file` 全程。
- `board/src/main/java/net/marcloud/mcp/board/signals/*.java`(12 个)— 全部读过或逐类扫描 import/字段:纯 primitive/String 值对象,无 `net.minecraft.*`、无输入库类型。`KeySignal.java` 携带 "LWJGL-style" int 键码;`RenderSignal.java` 携带缩放屏幕尺寸 + partialTicks。

测试(抽查,与移植相关处):

- `board/src/test/java/net/marcloud/mcp/board/NoThirdBusTest.java` — 用 `Files.walk` 扫源码防第三总线;路径全走 `Path.resolve`,可移植。
- `board/src/test/java/net/marcloud/mcp/board/persist/StoreTest.java` — 用 JUnit TemporaryFolder,无硬编码路径。
- `board/src/test/java/net/marcloud/mcp/board/link/BoundaryDisciplineTest.java` — 扫查(grep 路径相关行),无平台假设。
- 其余 20 余个测试文件仅列目录确认存在,未逐个细读(纯框架逻辑测试,与 OS 无关的概率极高,但此处如实说明未全读)。

## macOS 移植阻碍

**无。**

board 模块不含 OS 分支、不加载 native、不 spawn 进程、不用 AWT/Swing、不硬编码路径分隔符;所有游戏访问都是反射 + 全量 Throwable 吞并降级。它能否在 macOS 上正常工作完全取决于它反射的 client/ 侧目标是否仍然存在——见下节。

供 main 分支 macOS 修改时对照的反射目标清单(改 client/ 时不得破坏这些签名):

| 反射目标 | 使用位置 |
| --- | --- |
| `net.minecraft.client.Minecraft.getMinecraft()`(静态) | `chips/ChipBridgePort.java:45,152`、`chips/CoordinatesHudChip.java:32`、`chips/FullbrightChip.java:61`、`chips/LoginChip.java:78`、`chips/StartupScreenChip.java:67-68` |
| `Minecraft.addScheduledTask(Callable)` 返回 Future | `chips/ChipBridgePort.java:140` |
| `Minecraft.thePlayer` 字段 + `posX/posY/posZ` 公共字段 | `chips/CoordinatesHudChip.java:36-42` |
| `Minecraft.getDebugFPS()`(静态) | `chips/FpsMeterChip.java:33` |
| `Minecraft.gameSettings` 字段 + `gammaSetting` 公共 float 字段 | `chips/FullbrightChip.java:65-74` |
| `Minecraft.getSession().getUsername()` | `chips/LoginChip.java:78-87` |
| `Minecraft.currentScreen` 字段、`displayGuiScreen(GuiScreen)` | `chips/StartupScreenChip.java:75-78` |
| `net.marcloud.mcp.core.McpCore`、`net.marcloud.mcp.core.boot.CoreBootstrap.core()` / `onGameInitialized()` | `link/McpLink.java:31,34,55-56,77-78` |
| Backplane 字符串键:`board.port`、`board`、`mcp.port`/`mcp`、`chip.roster`、`chip.toggle` | `link/BoardPort.java:26`、`Board.java:152`、`link/McpLink.java:96-98`、`chips/ChipBridgePort.java:42-44` |

以上目标全是游戏自身的 MCP 映射名和本仓库自己的类,与 LWJGL2→3、GLFW、剪贴板、Retina 等 macOS 修复面(窗口/输入/剪贴板层)不重叠,预期无需改动 board。

## 不确定的地方

1. **KeySignal 键码语义**(`signals/KeySignal.java:21-32`):注释写"LWJGL-style integer key code",board 只透传 int,不做转换。真正发布 KeySignal 的桥在 core/client 侧。若 macOS 分支把输入换成了 GLFW,键码空间(GLFW `GLFW_KEY_*` vs LWJGL2 `Keyboard.KEY_*`)不一致——需要在真机上确认发布侧发的是哪套键码,以及 `Pin` 绑定处用的常量是否同一套。board 本身无需改,但两侧必须一致。
2. **`Minecraft.getDebugFPS()` / `addScheduledTask(Callable)` 在 macOS 修复后的 client 上是否仍保留原签名**——这些是 client/ 源码,理论上 macOS 修复不动它们,但只有在合并后的 main + mcp-core 组合上跑一次(或跑 `ChipBridgePortTest`/`FeatureChipsTest`)才能确证。
3. **`Files.move(..., ATOMIC_MOVE)` 在目标机器实际使用的文件系统上是否走原子路径**(`persist/Store.java:158-160`):代码已有回退,APFS 应支持,但"回退是否被触发"只能真机验证(不影响正确性,只影响崩溃窗口)。
4. **pg-maven-plugin 加固在 Temurin JDK 25 上构建、产物在 Java 8 字节码级运行**的组合是否正常——插件本体在 pg 模块(不在本次调查范围),board 只是消费者(`board/pom.xml:76-95`、`TickCounterChip` 的 `@Guarded`);需要 CI/真机构建确认。
5. 约 20 个测试文件未逐一细读(见文件清单),其中若有依赖工作目录布局的扫描类测试(如 `NoThirdBusTest` 的 `repoRoot()` 推导),在非标准构建目录下跑需要验证——已读部分未见平台相关代码。
