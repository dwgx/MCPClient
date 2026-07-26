# core JVMTI native module (C) — macOS arm64 移植调研

模块路径:`core/src/main/native/core-jvmti/`(位于 `origin/mcp-core` 分支,未检出;以下所有 file:line 均指该分支上的文件)。

## 这个模块是什么

这是整个项目唯一需要编译的 native 部分:一个 JVMTI agent,通过 `-agentpath` 在 JVM 启动时加载,给 C6 CONTROL-EXEC 层(`debug_*` MCP 工具)提供只有 onload 阶段才能拿到的调试能力(core-jvmti.h:4-9)。

事实清单(全部来自读过的代码):

- **入口与版本**:`Agent_OnLoad` 通过 `GetEnv(vm, ..., JVMTI_VERSION_1_2)` 获取 jvmtiEnv(core-jvmti.c:266);`JNI_OnLoad` 返回 `JNI_VERSION_10`(core-jvmti.c:323)。`Agent_OnUnload` 是空实现(core-jvmti.c:326-328)。
- **请求的 JVMTI capabilities**(core-jvmti.c:271-281,全部在 `Agent_OnLoad` 一次性 `AddCapabilities`):
  - `can_tag_objects`
  - `can_generate_field_modification_events`
  - `can_generate_field_access_events`
  - `can_pop_frame`
  - `can_access_local_variables`
  - `can_generate_single_step_events`
  - `can_generate_breakpoint_events`
  - `can_suspend`
  - `can_force_early_return`

  注意:`can_tag_objects` 和 `can_generate_field_access_events` 被请求了但代码里没有任何对应的使用(没有 SetTag/GetTag,没有 FieldAccess 回调或 watch)——是多余请求,不是功能。`AddCapabilities` 失败时不阻断 JVM 启动,只打日志并让 Java 侧的 `nAgentReady()` 返回 false(core-jvmti.c:282-286、94-96)。
- **安装的事件回调**(core-jvmti.c:288-298):`VMInit`、`Breakpoint`、`SingleStep`、`FieldModification`。启动时只 enable `JVMTI_EVENT_VM_INIT`;Breakpoint/SingleStep/FieldModification 按需在 `nSetBreakpoint`/`nSetSingleStep`/`nSetFieldModificationWatch` 里 enable(core-jvmti.c:141、152-153、193),未使用时零事件开销。
- **暴露给 Java 的功能**(通过 `RegisterNatives` 绑定到 `net/marcloud/mcp/core/kd/KdBridge` 的 15 个静态 native 方法,core-jvmti.c:217-233):SuspendThread / ResumeThread / PopFrame / ForceEarlyReturn(Void|Int|Object) / SetBreakpoint / ClearBreakpoint / SetSingleStep / GetLocalObject / GetLocalInt / SetLocalInt / SetFieldModificationWatch / ClearFieldModificationWatch / nAgentReady 探针。
- **绑定时机(双重绑定)**:`VMInit` 里做一次 best-effort 绑定(bootstrap classloader 上下文,core-jvmti.c:257-259);权威绑定发生在 `JNI_OnLoad`——Java 侧 `KdBridge` 的 static initializer 对同一个文件再调一次 `System.load`,触发 `JNI_OnLoad`,此时 `FindClass` 在 APP classloader 上下文解析(core-jvmti.c:306-324;KdBridge.java:44-66)。所以运行时需要**同一路径同时**出现在 `-agentpath:` 和 `-Dmcp.core.jvmtiLib=` 里(build.bat 尾部与 build-clang.sh:44 的 launch 提示也是这么写的)。
- **线程模型**:agent 自己不创建任何线程,没有锁,没有任何 OS 线程 API。事件回调在触发事件的 JVM 线程上执行,通过 `CallStaticVoidMethod` 回调 `KdBridge.onDebugEvent(int, Thread, String, long)`(core-jvmti.c:47-88),异常一律 `ExceptionClear`。全局状态是几个 static 指针加一个 `volatile int g_ready`(core-jvmti.c:13-17)。
- **指针假设**:jmethodID/jfieldID 按 `(jlong)(intptr_t)` 传给 Java,要求指针 ≤ 64 位(core-jvmti.h:17-19);arm64 满足。
- **Java 侧加载逻辑**(KdBridge.java:44-66):优先读 `-Dmcp.core.jvmtiLib` 用 `System.load`(绝对路径、不关心扩展名);属性缺失时退回 `System.loadLibrary("core-jvmti")`。任何失败都被捕获,`debug_*` 工具降级为诚实报错而不是死掉(McpCore.java:285-288)。
- **构建现状**:CI 不构建 native(.github/workflows/build.yml 里没有任何 native/clang 步骤);Windows 本地用 `scripts/build-c6-local.bat` → `build-clang.sh`(bat 文件头注明 "C6 native CANNOT be built in the ubuntu CI")。

## 文件清单

- `core/src/main/native/core-jvmti/CMakeLists.txt` — CMake 构建,硬编码 Windows JBR 头文件路径,产出 `core-jvmti.dll`(无 lib 前缀)。
- `core/src/main/native/core-jvmti/build.bat` — MSVC cl.exe 构建路径(Windows 专用,macOS 上无关)。
- `core/src/main/native/core-jvmti/build-clang.sh` — 免 MSVC 的 clang 构建脚本;`JBRINC` 可用环境变量覆盖,但默认值和 `-I win32`、输出名 `.dll` 都是 Windows 的。
- `core/src/main/native/core-jvmti/core-jvmti.c` — agent 全部实现:能力申请、事件回调、RegisterNatives 绑定、15 个 native 桥函数。
- `core/src/main/native/core-jvmti/core-jvmti.h` — 事件 kind 常量与三个导出入口声明,只 include `<jvmti.h>`。

辅助读过(不属于本模块,但决定移植约束):

- `core/src/main/java/net/marcloud/mcp/core/kd/KdBridge.java` — Java 侧加载与降级逻辑。
- `core/src/test/java/net/marcloud/mcp/core/kd/BuildScriptContractTest.java` — 以文本断言 build-clang.sh 必须含 `${JBRINC:-` 且保留 `_tools/jbrsdk-25.0.3-windows-x64-b508.16/include` 字面量。
- `scripts/build-c6-local.bat` — Windows 本地一键构建入口。
- `.github/workflows/build.yml` — 确认 CI 无 native 构建步骤。
- `core/src/main/java/net/marcloud/mcp/core/McpCore.java`(285-296 行)— agent 缺席时的降级注册。

## macOS 移植阻碍

先说结论:**C 源码本身零 Windows 依赖**。core-jvmti.c / core-jvmti.h 没有 include 任何 Windows 头、没有 WinAPI 调用、没有 .def 文件;导出全靠 `JNIEXPORT`(darwin 的 `jni_md.h` 里展开为 `__attribute__((visibility("default")))`,`JNICALL` 为空),`snprintf`/`fprintf`/`memset` 都是标准 C。agent 只通过 `(*jvmti)->` / `(*env)->` 函数指针调用 JVM,**不引用任何 libjvm 符号**,所以链接时既不需要 libjvm,也不需要 `-undefined dynamic_lookup` —— 普通 `clang -shared` 即可。阻碍全部集中在构建脚本层。

| 问题 | 位置 (file:line) | 严重度 | 具体怎么改 | 工作量 |
|---|---|---|---|---|
| `JBRINC` 默认指向 Windows JBR SDK,且 macOS 机器上根本没有 `_tools/jbrsdk-25.0.3-windows-x64-b508.16` | build-clang.sh:20 | 高(不改无法编译) | 已有 `${JBRINC:-}` 覆盖机制:macOS 上 `export JBRINC=$JAVA_HOME/include`(Temurin 25)。脚本本体可不改;若要让脚本自动探测,注意 BuildScriptContractTest.java:50-54 要求保留 `${JBRINC:-` 和 `_tools/...windows-x64.../include` 两个字面量 | 小 |
| 头文件子目录写死 `win32`,macOS 的 `jni_md.h` 在 `include/darwin` | build-clang.sh:38;CMakeLists.txt:9 | 高(不改无法编译) | 按平台切换:`case "$(uname -s)" in Darwin) MD=darwin;; *) MD=win32;; esac`,`-I"$JBRINC/$MD"`;CMake 侧用 `$<IF:$<PLATFORM_ID:Darwin>,darwin,win32>` 或 if(APPLE) | 小 |
| 输出文件名硬编码 `core-jvmti.dll` | build-clang.sh:11,34,40,42-44;CMakeLists.txt:11-13 | 中 | macOS 上产出 `libcore-jvmti.dylib`(带 lib 前缀,这样 `System.loadLibrary("core-jvmti")` 的兜底路径才成立,见 KdBridge.java:52;`-agentpath` 和 `System.load` 本身不关心文件名)。clang 的 `-shared` 在 macOS 上直接产出合法 Mach-O dylib,无需换 `-dynamiclib`。CMake 上 if(APPLE) 时不要设 `PREFIX ""` | 小 |
| `CLANG` 默认路径是 `/c/Program Files/LLVM/bin/clang.exe` | build-clang.sh:22 | 低(有兜底) | 第 23 行已兜底 `command -v clang`,macOS 上 Xcode CLT 的 clang 会被找到,无需改 | 无 |
| CMakeLists 硬编码 Windows 头路径 + 注释声明 windows-x64 only | CMakeLists.txt:6,17 | 低 | CMake 路径实际没被任何脚本调用(build.bat/build-clang.sh 都直接调编译器);可以整个忽略,只用 build-clang.sh。第 16 行的检查只查 `CMAKE_SIZEOF_VOID_P EQUAL 8`,arm64 本来就通过 | 无/小 |
| build.bat / scripts/build-c6-local.bat 是 Windows 专用 | build.bat 全文;scripts/build-c6-local.bat 全文 | 无 | 不用改,macOS 走 build-clang.sh 即可 | 无 |
| 架构:脚本没有 `-arch` 参数 | build-clang.sh:37-40 | 低 | Apple Silicon 上默认 clang 目标就是 arm64,与 Temurin arm64 JVM 匹配;若担心 Rosetta 环境下误编 x86_64,可显式加 `-arch arm64` | 无/小 |

代码层面(core-jvmti.c/h)**没有需要修改的地方** —— 一行都不用动。NativeBridgeContractTest 以文本断言 .c 里的 FQN 和方法签名,移植不触碰这些。

关于 JVMTI 能力在 macOS HotSpot 上的可用性:请求的 9 个能力(见上)都属于 HotSpot 在所有平台 onload 阶段的 potential capabilities 集合,JVMTI 规范和 HotSpot aarch64 端口都支持 `can_pop_frame` / `can_force_early_return` / `can_generate_single_step_events`(aarch64 有完整的解释器与 deopt 支持)。我没有发现任何一项是文档记载"macOS 不可用"的;且即便 `AddCapabilities` 失败,core-jvmti.c:282-286 的降级路径保证 JVM 照常启动。真正的行为差异只能上真机确认(见下节)。

关于加载安全机制(基于对 macOS 机制的了解,非本仓库代码,需真机验证):

- **本机 clang 编译的 dylib**:macOS 11+ 的 ld64 对 arm64 输出自动做 ad-hoc 签名,且本地生成的文件没有 `com.apple.quarantine` 属性,Gatekeeper 不拦。Temurin 的 `java` 二进制虽启用 hardened runtime,但带 `com.apple.security.cs.disable-library-validation` 等 entitlement,允许加载非同 Team ID 的 dylib。预期 `-agentpath` 直接可用,无需开发者证书。
- **分发场景**(如果将来把 dylib 放进 release 下载):下载落地会带 quarantine 属性,首次加载会被 Gatekeeper 拒("cannot be verified");需要 `xattr -d com.apple.quarantine` 或正式签名+公证。当前项目 native 本来就是本地构建(CI 不产 dll),所以这不是移植第一步的问题。
- SIP 不影响向自己启动的第三方 JVM 传 `-agentpath`。

## 不确定的地方

需要在 macOS 26 / M2 / Temurin 25 真机上验证:

1. Temurin 25 macOS arm64 的 `java` 是否确实保留 `com.apple.security.cs.disable-library-validation` entitlement(用 `codesign -d --entitlements - $(which java)` 确认),即未签名/ad-hoc 签名的 `libcore-jvmti.dylib` 能否被 `-agentpath` 加载。若不能,退路是 `codesign -s - libcore-jvmti.dylib`(ad-hoc)是否足够。
2. `AddCapabilities` 在 Temurin 25 aarch64 上是否对全部 9 个能力返回 `JVMTI_ERROR_NONE`(启动后看 stderr 是否打出 core-jvmti.c:302 的 "agent loaded" 而不是 :284 的 "AddCapabilities failed")。
3. `PopFrame` / `ForceEarlyReturn*` 在 aarch64 HotSpot 上对被 suspend 的线程是否与 windows-x64 行为一致(尤其目标帧是 JIT 编译帧时的 deopt 路径)——`NativeDebugOpLiveIT` 就是为这个准备的,可在真机用 `-agentpath` 跑一遍。
4. 与 `-XstartOnFirstThread` 的共存:agent 不建线程、不碰 UI,理论上无冲突,但 `SuspendThread` 如果挂起 AppKit 主线程(即 GLFW 渲染线程),窗口事件循环会冻结——这是使用层语义问题,和 Windows 上挂起渲染线程一样,但 macOS 上冻结主线程可能触发系统 "应用无响应" 变灰/风火轮,值得实测。
5. `SingleStep` 全局性能开销在 aarch64 解释器上的量级(仅按需开启,预期无影响,但没实测数据)。
