# mcp-core 分支 scripts/ 模块勘察(macOS arm64 移植)

调查对象:`origin/mcp-core` 分支的 `scripts/` 目录(未检出,全部经 `git show` 读取)。
对照文件:`origin/main:jvm-args-jdk25.txt`。

## 这个模块是什么

启动与构建脚本集合,共 16 个文件:6 个 Windows `.bat` 启动脚本、4 个 POSIX `.sh` 脚本、2 个 `.bat`/`.sh` 成对的构建/冒烟入口、3 个 JVM `@argfile`、1 个 README。它们覆盖四条启动路径:

1. **裸游戏**(`run.bat` / `run.sh`):`java @jvm-args-jdk25.txt -cp client/target/MCP-1.8.9.jar net.minecraft.client.main.Main --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"`,cwd 固定为 `test_run/`。
2. **游戏 + MCP Core Kernel**(`run-mcp.bat`、`run-mcp-overlay.bat`、`smoke-live-gl.sh`、`capture-overlay.sh`):同上,但换 `@jvm-args-mcp.txt`,`core-1.8.9-all.jar` 同时作 `-javaagent` 和 `-cp` 成员;overlay 变体额外把 `board`/`dwm-gl`/`dwm-imgui`/`dwm-skiko` jar 追加到 classpath 并设 `-Dmcp.core.overlay=true`。
3. **P-SECURE 双进程**(`run-psecure.bat` 起 authority:`java @jvm-args-psecure.txt -cp core-1.8.9-all.jar net.marcloud.mcp.core.alpc.AlpcMain`;`run-mcp-psecure.bat` 起游戏侧,`@jvm-args-mcp.txt` + `-Dmcp.core.psecure=true` 等回环连接参数)。
4. **构建**(`build-jars.bat` / `build-jars.sh` 走 mvnw;`build-c6-local.bat` 构建 Windows JVMTI DLL,文件头自述 "Windows-only",`core-jvmti.dll` 是 windows-x64 产物,scripts/build-c6-local.bat:3-5)。

所有游戏类 `.bat` 都默认 `JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16`(JetBrains Runtime 25 with DCEVM),`.sh` 同样默认该 Windows 路径(smoke-live-gl.sh:45、capture-overlay.sh:35),仅 `run.sh`/`run.bat` 走 `JAVA_HOME`(JDK 25 Temurin)。

## 文件清单

- `scripts/run.bat` — Windows 裸游戏启动;JAVA_HOME 默认 Temurin 25 路径,`@jvm-args-jdk25.txt`,单 jar classpath。
- `scripts/run.sh` — 上者的 Linux/macOS 版;已含 `[ "$(uname)" = "Darwin" ] && EXTRA="-XstartOnFirstThread"`(run.sh:20),classpath 用 `:`。
- `scripts/run-mcp.bat` — 游戏 + Kernel(`-javaagent` + `-cp`),`@jvm-args-mcp.txt`,可选追加 board/dwm-gl jar;**无 .sh 对应**。
- `scripts/run-mcp-overlay.bat` — run-mcp 基础上 `-Dmcp.core.overlay=true`,按存在性追加 gl/imgui/skiko jar,imgui 需预解出 `imgui-java64.dll` 并设 `-Dimgui.library.path`(run-mcp-overlay.bat:61-72),skiko 设 `-Dskiko.renderApi=OPENGL`;**无 .sh 对应**。
- `scripts/run-mcp-psecure.bat` — 游戏侧接入 P-SECURE authority(`-Dmcp.core.psecure*` 四个属性);**无 .sh 对应**。
- `scripts/run-psecure.bat` — P-SECURE authority 独立 JVM,headless,不碰 GL;**无 .sh 对应**。
- `scripts/build-jars.bat` — mvnw.cmd 构建 client + core-all + dwm-gl + dwm-imgui + dwm-skiko 五个 jar,`--no-native` 跳过后两个。
- `scripts/build-jars.sh` — POSIX 版,但**只构建** client + core-all + dwm-gl(纯 Java,无 imgui/skiko,build-jars.sh:21-30)。
- `scripts/build-c6-local.bat` — Windows-only:经 Git Bash 调 `core/src/main/native/core-jvmti/build-clang.sh` 产出 `core-jvmti.dll`,并打印 `-agentpath` 用法;**无 .sh 对应**。
- `scripts/smoke-live-gl.sh` — 活体冒烟:后台起游戏(同 run-mcp 的调用形态)、等 HTTP facade 绑 127.0.0.1:1337、POST dev_probe、断言 `game.up`/`gl.present`、清理;经 `cygpath` 探测决定 classpath 分隔符与路径翻译(smoke-live-gl.sh:129,136),POSIX 上自动用 `:` 与原生路径。
- `scripts/smoke-live-gl.bat` — 纯 wrapper,找 Git Bash 后转调 `.sh`。
- `scripts/smoke-live-gl.README.md` — 冒烟测试说明、退出码、2026-07-13 Windows/NVIDIA 验证记录。
- `scripts/capture-overlay.sh` — 起游戏(`@jvm-args-mcp.txt` + overlay armed + HTTP facade),curl `/v1/screen` 抓 PNG 截图后拆除;同样有 cygpath/CPSEP 双栖处理(capture-overlay.sh:53-54)。
- `scripts/jvm-args-jdk25.txt` — 裸游戏 argfile:UTF-8、`--sun-misc-unsafe-memory-access=allow`、`--enable-native-access=ALL-UNNAMED`、11 个 java.base + 3 个 java.desktop `--add-opens`;尾部注释了条件项,含 `# --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED # macOS only`(jvm-args-jdk25.txt:39)。
- `scripts/jvm-args-mcp.txt` — jdk25 版的超集(见下节对比)。
- `scripts/jvm-args-psecure.txt` — 极简 headless argfile:仅 UTF-8 + 3 个 `--add-opens`(java.lang / java.lang.reflect / java.util)。

## 三个 jvm-args-*.txt 与 main 上 jvm-args-jdk25.txt 的对比

- `scripts/jvm-args-jdk25.txt`(mcp-core)与 `jvm-args-jdk25.txt`(main 根目录)**逐字节相同**(`diff` 验证,输出 IDENTICAL)。
- `scripts/jvm-args-mcp.txt` = jdk25 版全部内容,**另加**:`-Xms512m -Xmx2g`(jvm-args-mcp.txt:14-15,注释解释是避免 Windows pagefile 预留失败)、`-XX:+AllowEnhancedClassRedefinition`(:23,DCEVM/JBR 专有)、`-XX:+EnableDynamicAgentLoading`(:25)、`-Djdk.attach.allowAttachSelf=true`(:27),以及两行**已注释**的 `-agentpath:...core-jvmti.dll` / `-Dmcp.core.jvmtiLib=...dll`(:55-56)。`--add-opens` 集合与 jdk25 版完全一致,但删去了 jdk25 版尾部的 CONDITIONAL 注释块。
- `scripts/jvm-args-psecure.txt` = jdk25 版的**极小子集**:去掉 unsafe/native-access/desktop/绝大多数 add-opens,只留 UTF-8 + java.lang / java.lang.reflect / java.util 三个 opens(jvm-args-psecure.txt:10-15),文件头明言该进程不渲染、不需要 LWJGL/DCEVM 标志。

## macOS 移植阻碍

| 问题 | 位置 (file:line) | 严重度 | 具体怎么改 | 工作量 |
|---|---|---|---|---|
| `smoke-live-gl.sh` 起游戏时没有 `-XstartOnFirstThread`,macOS 上 GLFW 无法建窗,冒烟必然 exit 2 | scripts/smoke-live-gl.sh:137-142 | 高 | 照抄 run.sh:18-20 的模式:`[ "$(uname)" = "Darwin" ] && EXTRA="-XstartOnFirstThread"`,插在 `"@$W_ARGS"` 之后(不能进 argfile,Windows JVM 会拒绝该 -X 选项) | 小 |
| `capture-overlay.sh` 同样缺 `-XstartOnFirstThread` | scripts/capture-overlay.sh:83-90 | 高 | 同上,在 `"@$(winpath "$ARGS_FILE")"` 后加 Darwin 条件的 `$EXTRA` | 小 |
| `jvm-args-mcp.txt` 含 `-XX:+AllowEnhancedClassRedefinition`,这是 JBR/DCEVM 专有旗标;目标运行时 Temurin 25 会以 "Unrecognized VM option" 拒绝启动 | scripts/jvm-args-mcp.txt:23 | 高 | 在 macOS 上要么改用 JBR aarch64,要么把该行(连同依赖 JBR 的假设)注释掉;最小改法是复制一份 macOS argfile 或用注释开关,不动 Windows 路径 | 小 |
| 两个 `.sh` 默认 `JBR_HOME=$ROOT/_tools/jbrsdk-25.0.3-windows-x64-b508.16`(Windows x64 JBR),macOS 上目录不存在且 `bin/java.exe`→`bin/java` 回退也落空 | scripts/smoke-live-gl.sh:45-47;scripts/capture-overlay.sh:35-36 | 高 | macOS 上 `export JBR_HOME=<Temurin/JBR aarch64 路径>` 即可跑通现脚本;若要免配置,默认值改为按 `uname` 分支,Darwin 用 `/usr/libexec/java_home -v 25` | 小 |
| `run-mcp.bat` 无 .sh 对应:macOS 版必须换 `$VAR`/`${VAR:-default}`、classpath 分隔符 `;`→`:`、`%~dp0..`→`$(cd "$(dirname "$0")/.." && pwd)`、`java.exe`→`java`、JDK 发现(JBR_HOME/JAVA_HOME/java_home)、`cd /d`→`cd`、去掉 `pause`,并在 `@jvm-args-mcp.txt` 后插 `-XstartOnFirstThread` | scripts/run-mcp.bat:15-44 | 高 | 写 `scripts/run-mcp.sh`,以 run.sh 为骨架 + run-mcp.bat 的 `-javaagent`/board/dwm-gl classpath 逻辑(`if [ -f "$BOARD_JAR" ] && CP="$CP:$BOARD_JAR"`) | 小 |
| `run-mcp-overlay.bat` 无 .sh 对应:除上一行的通用差异外,imgui 分支解出的是 `imgui-java64.dll`(macOS 需 `.dylib` 且 fat jar 里未必有 macos-arm64 natives),skiko fat jar 注释自述打包 `skiko-windows-x64.dll` | scripts/run-mcp-overlay.bat:56-82 | 中 | 最小改法:macOS 版只支持纯 Java 的 `gl`/`gl-ui` backend(与 build-jars.sh 只产 dwm-gl 一致),imgui/skiko 分支直接不移植;若日后要 skiko,需换含 skiko-macos-arm64 natives 的 jar 并把 `-Dimgui.library.path` 指向 `.dylib` 目录 | 中 |
| `run-psecure.bat` / `run-mcp-psecure.bat` 无 .sh 对应:纯 headless / 游戏侧,差异只有 `%VAR%`→`$VAR`、默认值语法、`;`→`:`、java 路径;authority 侧不碰 GL,不需要 `-XstartOnFirstThread`,游戏侧需要 | scripts/run-psecure.bat:26-46;scripts/run-mcp-psecure.bat:28-52 | 中 | 各写一个 `.sh`:authority 用 `${PSECURE_TOKEN:-dev-psecure-token}` 等默认值,`-cp` 单 jar 无分隔符问题;游戏侧照 run-mcp.sh 加 psecure 四属性 | 小 |
| `build-c6-local.bat` 无 .sh 对应:产物是 windows-x64 JVMTI DLL,`-agentpath` 指向 `.dll`;macOS 等价物必须用 clang 针对 macOS JDK 头文件产 `core-jvmti.dylib`,`-agentpath:`/`-Dmcp.core.jvmtiLib` 改指 `.dylib`(jvm-args-mcp.txt:55-56 目前注释着,不阻塞启动) | scripts/build-c6-local.bat:3-9,29-38 | 低 | 可暂不移植(旗标默认注释,C6 是可选调试功能);若要做,需先确认 `core/src/main/native/core-jvmti/build-clang.sh` 是否已能吃 Darwin 目标(本次未读该文件,在 core 模块范围外) | 未知 |
| `build-jars.bat` 的 imgui/skiko 两步在 macOS 无意义(产物内嵌 Windows DLL) | scripts/build-jars.bat:30-36 | 低 | 无需改:`build-jars.sh` 已是 macOS 可用的对应物且只构建纯 Java 三件套(build-jars.sh:21-30) | 无 |
| `jvm-args-jdk25.txt` 尾部 `sun.lwawt.macosx` opens 处于注释态 | scripts/jvm-args-jdk25.txt:39 | 低 | 按文件自身规则:仅当真机出现点名它的 `InaccessibleObjectException` 才解注释,先不动 | 小 |

无阻碍的文件:`run.sh`(已正确处理 Darwin)、`build-jars.sh`、`jvm-args-psecure.txt`、`smoke-live-gl.bat`/`smoke-live-gl.README.md`(Windows wrapper/文档,macOS 直接用 `.sh`)。`smoke-live-gl.sh` 与 `capture-overlay.sh` 的 cygpath/CPSEP/清理逻辑在 POSIX 分支下本身是对的(smoke-live-gl.sh:118-121 走 `kill "$GAME_PID"`),阻碍只在上表所列各项。

## 不确定的地方

- Temurin 25 对 `-XX:+AllowEnhancedClassRedefinition` 的具体失败形态(启动即拒 vs 需 `-XX:+IgnoreUnrecognizedVMOptions`)需真机确认;这决定 argfile 是拆分还是加一行忽略开关。
- `-XstartOnFirstThread` 与 `-javaagent`(core Kernel premain)及 HTTP facade 线程模型是否共存无碍——依赖方给的上下文说另一分支已跑通,但该分支的启动命令本次未读到,smoke/capture 加旗标后需真机跑一次 exit 0 才算数。
- `dev_probe` 在 macOS 上报告的 `gl.present`/GL 身份串(Apple 的 OpenGL 兼容 2.1 上下文)是否满足 smoke-live-gl.sh:194-195 的断言,只能真机验证。
- `core/src/main/native/core-jvmti/build-clang.sh` 是否已支持 Darwin 目标(决定 C6 的 `.dylib` 工作量),该文件不在本模块范围,未读。
- `_tools/` 下是否会放置 macOS aarch64 的 JBR(影响默认 JBR_HOME 该写成什么),仓库分支上未见对应目录约定。
