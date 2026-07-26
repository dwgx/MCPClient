# mcp-core → macOS arm64 移植计划

依据:`docs/macos/mcp-core-*.md` 七篇模块调研(只读 `origin/mcp-core`),
外加本文档作者在 **macOS 26.5 / Apple M2 / Temurin 25.0.3+9** 真机上补做的四项验证(见 §1)。

前置事实:`client` + `lwjgl2-shim` 已在 macOS 跑通(分支 `feat/macos-arm64`,单人世界实测进世界)。
本文只讲 `mcp-core` 分支多出来的四个模块 + native + 脚本。

---

## 结论

**这是小活,不是大活。** 六个模块里 **board / pg / dwm 三个零改动**,`core` 的 Java 侧只有两处文案,
reactor 与 CI 一行不用动。真正要做的事集中在两处:**启动脚本**(缺 `.sh` 对应物 + 缺 `-XstartOnFirstThread`)
和 **argfile**(一个 JBR 专有旗标会让 Temurin 直接拒绝启动)。

原本预期最大的风险是 C6 那个 JVMTI native agent —— **已经在真机上验证通过,零源码修改**(§1)。
排除它之后,剩下最大的不确定项是 Set-of-Marks 截图叠加在 Retina 下的坐标对齐(§5),
那是个功能正确性问题,不阻塞"跑起来"。

---

## 1. 真机已验证(把未知变成事实)

这四项原本列在调研的"不确定的地方",现已实测,**不必再验**:

| 问题 | 结果 | 证据 |
|---|---|---|
| Temurin 25 能否加载未签名 `.dylib` agent | **能,无需开发者证书** | `codesign -d --entitlements - $JAVA_HOME/bin/java` 含 `com.apple.security.cs.disable-library-validation`;实测 `-agentpath` 加载 linker ad-hoc 签名的 dylib 成功 |
| JVMTI agent 能否在 macOS arm64 编译 | **能,C 源码零修改** | `clang -shared -O2 -fPIC -arch arm64 -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin core-jvmti.c -o libcore-jvmti.dylib` → 合法 `Mach-O 64-bit dynamically linked shared library arm64`,34K |
| 9 个 JVMTI capability 是否全部可用 | **全部获取成功** | 启动输出 `[core-jvmti] agent loaded; JVMTI debugger capabilities acquired.`(即 core-jvmti.c:302 的成功分支,不是 :284 的失败分支) |
| Temurin 25 对 `-XX:+AllowEnhancedClassRedefinition` 的反应 | **直接拒绝启动**,不是警告 | `Unrecognized VM option 'AllowEnhancedClassRedefinition'` + `Could not create the Java Virtual Machine` |

最后一条决定了 argfile 必须拆分而不是"加个 IgnoreUnrecognizedVMOptions 混过去"—— 后者会把真正的拼写错误也一起吞掉。

---

## 2. 依赖顺序

把"能编译 / 能加载 / 能跑起来"分开,每层都有明确的成败信号:

**第 0 层 — 已经成立(无需工作)**
`./mvnw -B -ntp test` 与 `clean package -DskipTests` 在 macOS 上本来就能过整个 reactor:
所有 pom 无 `<profile>`/`<os>` 激活,native 完全不接线到 Maven,JDK 25 满足 core/dwm 的 `release=25`。
LWJGL 的 `natives-windows` 依赖**不阻塞编译**(natives jar 只是普通 Maven 依赖,任何 OS 都能下载并 shade)。

**第 1 层 — 能启动(必须先做,否则后面全都无法验证)**
1. `client/pom.xml` 加 `natives-macos-arm64`(已在 `feat/macos-arm64` 做完,合并即可)
2. 拆出 macOS 版 argfile(去掉 `AllowEnhancedClassRedefinition`)
3. 写 `scripts/run-mcp.sh`
信号:游戏窗口起来,`127.0.0.1:25599` 有 MCP 监听。

**第 2 层 — 能验证内核**
4. `smoke-live-gl.sh` / `capture-overlay.sh` 补 `-XstartOnFirstThread` + `JBR_HOME` 默认值
信号:`smoke-live-gl.sh` exit 0,`dev_probe` 的 `game.up`/`gl.present` 断言通过。

**第 3 层 — 可选能力**
5. C6 JVMTI(§1 已证可行)、P-SECURE 双进程脚本、SoM Retina 对齐

---

## 3. 工作项

| # | 工作项 | 模块 | 严重度 | 工作量 | 前置 |
|---|---|---|---|---|---|
| 1 | 加 5 个 `natives-macos-arm64` runtime 依赖(与 windows 并存,一个 jar 双端通用;**不要**用 os-activated profile,那会让产物依构建机而变) | client | 阻断(运行) | 10 行 XML | — |
| 2 | macOS argfile:复制 `jvm-args-mcp.txt`,删掉 `-XX:+AllowEnhancedClassRedefinition`(§1 已证 Temurin 拒绝启动)。`-Xms512m -Xmx2g` 可留(注释说是绕 Windows pagefile,但对 macOS 无害) | scripts | 阻断 | 小 | — |
| 3 | 新写 `scripts/run-mcp.sh`:以 `run.sh` 为骨架(它已正确处理 Darwin)+ `run-mcp.bat` 的 `-javaagent` 与 board/dwm-gl classpath 逻辑;`;`→`:`、`%~dp0..`→`$(cd "$(dirname "$0")/.." && pwd)`、去掉 `pause` | scripts | 阻断 | 小 | 1,2 |
| 4 | `smoke-live-gl.sh:137` 与 `capture-overlay.sh:83` 插 `-XstartOnFirstThread`(**必须在命令行,不能进 argfile**——Windows JVM 会拒绝该 `-X` 选项) | scripts | 阻断(冒烟) | 小 | 3 |
| 5 | 两个 `.sh` 的 `JBR_HOME` 默认值按 `uname` 分支,Darwin 用 `/usr/libexec/java_home -v 25` | scripts | 高 | 小 | — |
| 6 | `build-clang.sh` 加 Darwin 分支(patch 见 §6,**已实测编译通过**) | core/native | 中(仅 C6) | 小 | — |
| 7 | 两处用户可见文案硬编码 `core-jvmti.dll` → 用 `System.mapLibraryName("core-jvmti")` 或写成平台中立措辞 | core | 低(纯文案) | trivial | — |
| 8 | `run-psecure.sh` / `run-mcp-psecure.sh`(authority 侧 headless 不需要 `-XstartOnFirstThread`,游戏侧需要) | scripts | 中 | 小 | 3 |
| 9 | `run-mcp-overlay.sh`:**只移植 `dwm-gl`**。imgui 解的是 `imgui-java64.dll`、skiko fat jar 自述打包 `skiko-windows-x64.dll`,两者在 macOS 无意义(`build-jars.sh` 本来也只构建纯 Java 三件套) | scripts | 中 | 中 | 3 |
| 10 | 可选:CI 加 `runs-on: macos-14` job(macos-14+ 即 arm64) | CI | 低 | 小 | 1 |

---

## 4. 无需改动的部分(经调研确认,别浪费时间)

- **`board`** — 零 OS 分支、零 native、零进程派生、零 AWT。它能不能跑完全取决于反射的 client 侧目标还在不在;
  调研列出的 9 个反射目标(`Minecraft.getMinecraft()`、`thePlayer.posX`、`getDebugFPS()`、`gameSettings.gammaSetting`、
  `addScheduledTask(Callable)`、`currentScreen`/`displayGuiScreen` 等)全部是 vanilla 映射名,
  与 macOS 修改面(窗口/输入/剪贴板)不重叠。**改 client 时不要碰这些签名。**
- **`pg` 全家** — 纯构建期 ASM 字节码处理,运行时不需要 pg 任何东西。类名转换同时替换 `/` 和 `\`,
  `ATOMIC_MOVE` 带降级,`FrameSafeClassWriter` 刻意不经 classloader 解析类型(反而比一般实现更平台无关)。
- **`dwm`** — 空模块、零依赖、无源码,原样构建即可。
- **`core` 的 Java 侧** — 除工作项 7 的两处文案外无改动。已确认:无 `os.name` 分支、无 `Runtime.exec`/`ProcessBuilder`、
  无注册表、无命名管道(`alpc` 包只是**命名**借用 Windows ALPC,实现是 loopback `ServerSocket`)、
  classpath 用 `System.getProperty("java.class.path")` 原样透传、`java.awt` 只用到 `BufferedImage`/`Graphics2D`/`ImageIO`
  这些无头安全的图像类(不碰 `Toolkit`/`Robot`/剪贴板)。
- **reactor pom 与 CI** — 一行不用改。10 个 pom 里零 `<profile>`/`<os>` 激活;
  CI 的 `if [ -d core ]` 条件断言机制让同一份 workflow 天然适配两个分支。
- **`core-jvmti.c` / `.h`** — §1 已证:一行都不用动。

---

## 5. 仍需真机验证的问题

已排除 §1 那四项后,剩下这些:

| 问题 | 怎么验 |
|---|---|
| **SoM 叠加在 Retina 下是否偏移一倍**(最值得先查) `GuiSnapshotService.java:193-202` 把 `mc.displayWidth` 当 framebuffer 尺寸,而 `ScreenCapture.java:74-76` 用的是 `fb.framebufferWidth`。若 `displayWidth` 是窗口点数,标注框会整体偏移 2x | 开一个 GUI,调 `gui_snapshot`,肉眼核对标注框是否套在控件上 |
| AWT 图像类与 `-XstartOnFirstThread` 共存 | 跑 `screenshot` / `gui_snapshot` 工具;若冲突加 `-Djava.awt.headless=true`(对这些图像类无副作用) |
| `dev_probe` 的 `gl.present` 断言能否满足 Apple 的 OpenGL 2.1 兼容上下文 | `smoke-live-gl.sh` 跑一次看 exit code |
| `PopFrame`/`ForceEarlyReturn` 在 aarch64 JIT 帧上的 deopt 行为 | 带 `-agentpath` 跑 `NativeDebugOpLiveIT` |
| `SuspendThread` 挂起 AppKit 主线程(= GLFW 渲染线程)的系统级后果 | 实测;macOS 可能触发"应用无响应"变灰/风火轮,Windows 上只是画面冻结 |
| `FileWatchDeployer` 的保存→部署延迟 | 改个 `.java` 计时;JDK 22+ 在 macOS 用 FSEvents,预期不是旧版的轮询 |
| `repo.marcloud.net` 是否可达且托管 authlib/patchy/icu4j/twitch/paulscode | 清空相关 `~/.m2` 缓存后 `./mvnw package`(若已有缓存则感知不到) |
| board 的 `seed=1337` 硬化产物在 JDK 25/macOS 上是否与 Windows 逐字节一致 | 两平台产物做 `shasum` 对比 |

---

## 6. 建议的第一步

**目标:让 `core` Kernel 在 macOS 上带着游戏起来,并让 `smoke-live-gl.sh` 退 0。** 一次会话可完成,成败信号明确。

顺序:合并 `feat/macos-arm64` 的 client natives 改动 → 拆 macOS argfile → 写 `run-mcp.sh` → 给
`smoke-live-gl.sh` 补 `-XstartOnFirstThread` 和 `JBR_HOME` → 跑冒烟。

C6 native 可以顺手做掉,因为 §1 已经把风险清零了。给 `build-clang.sh` 的 patch(保持 Windows 路径逐字节不变,
且保留 `BuildScriptContractTest` 断言的 `${JBRINC:-` 与 `_tools/jbrsdk-25.0.3-windows-x64-b508.16/include` 两个字面量):

```bash
# 平台差异:头文件子目录与产物名。Windows 路径保持原样。
case "$(uname -s)" in
  Darwin) MD=darwin; LIBNAME=libcore-jvmti.dylib; ARCHFLAG="-arch arm64" ;;
  *)      MD=win32;  LIBNAME=core-jvmti.dll;      ARCHFLAG= ;;
esac
# macOS 上 JBRINC 默认值(Windows JBR SDK)不存在,用运行 JDK 自己的头文件
if [ ! -f "$JBRINC/jvmti.h" ] && [ -n "${JAVA_HOME:-}" ]; then
  JBRINC="$JAVA_HOME/include"
fi

"$CLANG" -shared -O2 -fPIC $ARCHFLAG \
  -I"$JBRINC" -I"$JBRINC/$MD" \
  "$HERE/core-jvmti.c" \
  -o "$HERE/build/$LIBNAME"
```

产物名用 `libcore-jvmti.dylib`(带 `lib` 前缀),这样 `KdBridge.java:52` 的
`System.loadLibrary("core-jvmti")` 兜底路径才成立;`-agentpath` 和 `System.load` 本身不关心文件名。
启动加:

```
-agentpath:<abs>/libcore-jvmti.dylib -Dmcp.core.jvmtiLib=<abs>/libcore-jvmti.dylib
```

两个参数必须指向**同一个路径** —— 权威的 `RegisterNatives` 绑定发生在 `JNI_OnLoad`,
由 Java 侧 `KdBridge` 的 static initializer 对同一文件再 `System.load` 一次触发(此时 `FindClass` 在 APP
classloader 上下文解析);`-agentpath` 单独存在时只有 `VMInit` 里那次 bootstrap 上下文的 best-effort 绑定。
