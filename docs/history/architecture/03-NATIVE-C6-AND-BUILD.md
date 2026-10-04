---
doc: arch-native-c6
title: 03 · C6 原生 JVMTI 调试器 + 构建/运行 + 实机验证
layer: reference
status: authoritative
updated: 2026-07-13
parent: 02-CAPABILITIES.md
next:
  - path: 05-TEST-MAP.md
    when: 你想知道哪些能力/不变量有测试守护、覆盖缺口在哪
read_if: 只在你要改 C6 原生 JVMTI 调试器(DLL/agent)、构建运行链路(mvnw/agent jar/启动脚本/端口),或想确认某能力是否已在真机 JBR 验证过时读。
---
# 03 · C6 原生 JVMTI 调试器 + 构建/运行 + 实机验证

本文覆盖三部分:(A) C6 CONTROL-EXEC 原生 JVMTI 调试器层的全貌与"用 clang(无需 MSVC)怎么编 + 怎么启用"、(B) 整个 Core 的构建/运行链路(mvnw、fat agent jar、启动脚本、运行时、端口),以及 (C) 一份"哪些能力已在真机 JBR 里验证、哪些仍只在 headless 里测过"的清单。

所有断言都对着 `D:\Project\MCPClient` 分支 `mcp-core`(C6 相关提交 `1be1167` "Unblock C6 native JVMTI debugger: MSVC-free clang build + JNI_OnLoad bind fix",其后还有 `643738b` gap-fix / `b1d9cba` GUI / `6b93495` root CLAUDE.md 等)的真实源码,带 `file:line` 引用。凡是"尚未验证"的地方都明确标注,不粉饰。

---

## A. C6 原生 JVMTI 调试器层

C6 是 7 层能力模型里的 **CONTROL-EXEC**(调试器级执行控制):暂停线程、PopFrame、ForceEarlyReturn、断点、单步、读写局部变量、字段修改监视。这些能力的关键约束是 **onload-only**——只能在 `Agent_OnLoad` 时申请,动态 attach 拿不到——所以 C6 必须走原生 JVMTI agent(`core-jvmti.dll`),用 `-agentpath` 在 JVM 启动时加载。

当前状态:**Java 侧完整 + native 侧完整 + DLL 已编译并在真机 JBR 里验证通过**。`core-jvmti.dll` 现在用 **LLVM/clang** 编译(**不再需要 MSVC**);产物 `core/src/main/native/core-jvmti/build/core-jvmti.dll` 存在(并被复制一份到 `core-jvmti/core-jvmti.dll`),两者都被 gitignore。一处 **`JNI_OnLoad` 绑定修复**(natives 在 app-classloader 上下文而非仅 bootstrap 的 `VMInit` 上下文里绑定)让 `nAgentReady()` 不再抛 `UnsatisfiedLinkError`,C6 因此真正可用。当仍以 headless(无 `-agentpath`)方式运行时,Java 侧走优雅降级路径:`KdBridge.isAvailable()==false`,`debug_*` 工具照常注册且可调用,但返回 `isError` 而非静默死掉。

### A.1 native 目录构成

`core/src/main/native/core-jvmti/` 下的源码/构建文件:

| 文件 | 作用 |
|---|---|
| `core-jvmti.h` | C6 agent 头文件:声明三个 JVMTI 入口 (`Agent_OnLoad`/`Agent_OnUnload`/`JNI_OnLoad`) + 三个事件 kind 常量 (`core-jvmti.h:30-32`) |
| `core-jvmti.c` | agent 实现:onload 申请能力、共享的 `bindNatives()`(`RegisterNatives` 绑定到 `KdBridge`)、15 个 native 桥函数、3 个事件回调 |
| `build-clang.sh` | **主构建脚本 —— LLVM/clang 一键编译(无需 MSVC/Windows SDK)**;`clang -shared` 直接产出可用 DLL(`build-clang.sh:33-39`) |
| `build.bat` | MSVC `cl.exe` 备用编译脚本(windows-x64,需 VS Build Tools) |
| `CMakeLists.txt` | CMake 备用构建定义(pin 到 bundled JBR 头文件) |

> `build/core-jvmti.dll`(+ `.lib`)与 `core-jvmti/core-jvmti.dll` 是**已编译的产物**,均 gitignored——仓库里只保留源码与构建脚本。

### A.2 `Agent_OnLoad` 申请的 9 个 onload 能力

`Agent_OnLoad` (`core-jvmti.c:263-304`) 先 `GetEnv(JVMTI_VERSION_1_2)` 取 `jvmtiEnv`,再 `memset` 清零一个 `jvmtiCapabilities` 后把 9 个位打开 (`core-jvmti.c:273-281`):

```c
caps.can_tag_objects = 1;
caps.can_generate_field_modification_events = 1;
caps.can_generate_field_access_events = 1;
caps.can_pop_frame = 1;
caps.can_access_local_variables = 1;
caps.can_generate_single_step_events = 1;
caps.can_generate_breakpoint_events = 1;
caps.can_suspend = 1;
caps.can_force_early_return = 1;
```

其中 `can_pop_frame` / `can_generate_breakpoint_events` / `can_generate_single_step_events` / `can_access_local_variables` / `can_suspend` / `can_force_early_return` / `can_generate_field_modification_events` 这些正是**动态 attach 拿不到、必须 onload 申请**的那批——这就是 C6 强制 `-agentpath` 的根因。

**失败即降级、绝不阻断启动**:若 `AddCapabilities` 失败,agent 打印 `[core-jvmti] AddCapabilities failed — debugger disabled` 后**仍返回 `JNI_OK`** (`core-jvmti.c:282-286`),让 JVM 照常引导,Java 侧通过 `nAgentReady()==false` 感知。只有 `AddCapabilities` 成功后才会 `g_ready = 1` (`core-jvmti.c:301`)。

随后 `SetEventCallbacks` 装上 `VMInit`/`Breakpoint`/`SingleStep`/`FieldModification` 四个回调 (`core-jvmti.c:288-297`),但只 `SetEventNotificationMode(..., JVMTI_EVENT_VM_INIT, ...)` 常开;断点/单步/字段修改三类通知模式是**按需开启**的(见 A.4),所以在没有 debug 工具调用前 JVM 零事件开销 (`core-jvmti.c:298-300`)。

### A.3 `RegisterNatives` 绑到 KdBridge —— 权威绑定在 `JNI_OnLoad`(bind-fix)

Java↔native 绑定集中在一个共享的 `bindNatives(JNIEnv*)` (`core-jvmti.c:211-252`),被 `vmInit` 和 `JNI_OnLoad` 两处调用:

1. `FindClass("net/marcloud/mcp/core/debug/KdBridge")`,找不到就 `ExceptionClear` 并返回 0(natives 不绑定,不崩)(`core-jvmti.c:212-216`)。
2. 用一张 15 项的 `JNINativeMethod M[]` 表 (`core-jvmti.c:217-233`) 调 `RegisterNatives`,把 C 函数绑到 `KdBridge` 的 15 个 `private static native` 方法上。**表里的名字 + JNI 签名与 `KdBridge.java:174-202` 的声明逐一对应**(比如 `nSetBreakpoint` 签名 `(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;J)I`)。
3. `NewGlobalRef` 刷新到"当前绑定所在的那个 `KdBridge` Class 实例"上(从 `JNI_OnLoad` 调用时即 app-classloader 实例),并缓存 `onDebugEvent(int,Thread,String,long)V` 的 `jmethodID` 到 `g_onEvent` (`core-jvmti.c:239-250`),供事件回调回吐。

> **JNI_OnLoad bind-fix(C6 解锁的关键)**:`vmInit` (`core-jvmti.c:257-259`) 只做一次 best-effort 的早绑定(bootstrap 上下文),**权威绑定发生在 `JNI_OnLoad`**(`core-jvmti.c:313-324`)。当 `KdBridge` 在 app-classloader 里 `System.load` 那个已被 `-agentpath` 加载的模块时,JVM 用调用线程的 `JNIEnv` 触发 `JNI_OnLoad`,此时 `FindClass` 解析到的正是"其 `native` 方法必须被绑定"的那个 app-classloader Class 实例。修复前,只有 bootstrap 上下文的 `VMInit` 绑定,可能绑到另一个 Class 实例(或没绑),app 侧 `nAgentReady()` 因而抛 `UnsatisfiedLinkError`——这正是此前 C6 不可用的根因。`bindNatives` 幂等,二次调用无害且以后者(正确的)为准。头文件注释 (`core-jvmti.h:6-10`) 与代码一致:**绑定在 `JNI_OnLoad`**。

15 个 native 桥函数(`core-jvmti.c:94-201`)覆盖:`nAgentReady`、`nSuspendThread`/`nResumeThread`、`nPopFrame`、`nForceEarlyReturnVoid/Int/Object`、`nSetBreakpoint`/`nClearBreakpoint`、`nSetSingleStep`、`nGetLocalObject`/`nGetLocalInt`/`nSetLocalInt`、`nSetFieldModificationWatch`/`nClearFieldModificationWatch`。每个都用 `check()` (`core-jvmti.c:20-28`) 把 `jvmtiError` 原样作为 `jint` 回传 Java(0=成功)。

三个事件回调 `onBreakpoint`/`onSingleStep`/`onFieldModification` (`core-jvmti.c:47-88`) 把事件通过缓存的 `g_onEvent` 回调进 `KdBridge.onDebugEvent`,并在调用后 `ExceptionCheck`+`ExceptionClear`,保证 JVMTI 回调线程廉价、不抛。

### A.4 KdBridge 的优雅降级契约(不泄漏 UnsatisfiedLinkError)

`KdBridge` (`KdBridge.java:33-203`) 是 C6 唯一的 Java↔native 咽喉,也是 `SeProtectedObjects` 成员(`SeProtectedObjects.java:79-80`,防止被 redefine/hook 从内部瓦解调试门禁)。

**降级是默认态**。静态初始化块 (`KdBridge.java:44-66`) 的逻辑:

1. 读 `-Dmcp.core.jvmtiLib`;非空则 `System.load(绝对路径)`,否则 `System.loadLibrary("core-jvmti")` 走 `java.library.path` (`KdBridge.java:48-53`)。
2. 调 `nAgentReady()` 探针——它返回 `g_ready`,即"`Agent_OnLoad` 跑了 **且** `AddCapabilities` 成功"(`core-jvmti.c:94-96`)。
3. **任何** `Throwable`(`UnsatisfiedLinkError`/`SecurityException`/……)都被 `catch` 且**永不逃逸** (`KdBridge.java:59-63`),结果落到 `AVAILABLE=false` + 一条固定 reason。

对外只暴露两个查询 `isAvailable()` (`KdBridge.java:69-71`) / `unavailableReason()` (`KdBridge.java:74-76`)。每个 public 包装方法(`suspendThread` 等,`KdBridge.java:86-161`)第一步都调 `ensure()` (`KdBridge.java:78-82`):不可用就抛 `DebuggerUnavailableException`(一个干净的 domain 异常,`DebuggerUnavailableException.java`),**绝不会去解引用任何 native 符号**。成功路径才把 native 返回码交给 `JvmtiError.check()`(非 0 抛 `DebuggerException`,`JvmtiError.java:16-20`)。

这条契约有专门的回归测试兜底:`DebuggerBridgeFallbackTest`(`core/src/test/java/DebuggerBridgeFallbackTest.java`)断言无 DLL 时 `isAvailable()==false`、reason 里点名缺失的 `-agentpath:core-jvmti.dll`,且 12 个 public op 全部抛 `DebuggerUnavailableException` 而**非** `UnsatisfiedLinkError`/`NoClassDefFoundError` (`DebuggerBridgeFallbackTest.java:45-54`)。

事件回流:native 通过 `onDebugEvent` (`KdBridge.java:168-170`) 投递到 `DebugEventQueue.INSTANCE`——一个 1024 容量、**drop-oldest、非阻塞、绝不抛**的队列 (`DebugEventQueue.java:26,39-55`),专为"JVMTI 回调线程可能持原生 monitor、绝不能阻塞/重入 JVMTI"设计。`McpCore` 把该队列的 listener 接到 `EventBus`(`McpCore.java:201` `DebugEventQueue.INSTANCE.addListener(bus::publish)`),`DebugEvent` 继承 `GameEvent`(`DebugEvent.java:11`),所以断点/单步/字段事件能无缝上现有事件总线。

### A.5 9 个 `debug_*` 工具

`DebugTools`(`DebugTools.java:41`)注册 9 个工具,名字集中在 `TOOL_NAMES` (`DebugTools.java:70-73`):

| 工具 | 作用 |
|---|---|
| `debug_suspend_thread` | 按线程名暂停/恢复(`resume=true` 恢复);暂停游戏/渲染线程会**冻结客户端** (`DebugTools.java:214-260`) |
| `debug_pop_frame` | 弹出已暂停线程的栈顶帧(恢复后重执调用) (`DebugTools.java:262-288`) |
| `debug_force_return` | 强制当前方法早返回,`kind=void\|int\|object`(object 只能返回 null,JSON 无法编组对象值) (`DebugTools.java:290-327`) |
| `debug_set_breakpoint` | 在 类+方法+字节码位置 下断点,命中发 `DebugEvent`;拒绝 protected Core 类 (`DebugTools.java:329-350`) |
| `debug_clear_breakpoint` | 清除断点 (`DebugTools.java:352-371`) |
| `debug_single_step` | 开/关某线程的单步事件(每条字节码一个事件,量极大,慎用) (`DebugTools.java:400-429`) |
| `debug_read_local` | 读已暂停帧的局部变量,`type=int\|object` (`DebugTools.java:431-482`) |
| `debug_write_local` | **仅 int** 局部写(JVMTI 已验证只有 `SetLocalInt` 这条写路径) (`DebugTools.java:484-520`) |
| `debug_watch_field` | 开/关字段**修改**监视(读监视不在已验证的 JVMTI 面里);拒绝 protected Core 类 (`DebugTools.java:522-566`) |

**7 层门控**:全部 R-1 HYPERVISOR,要求 `SE_DEBUG_CONTROL` 特权 enabled + `CAP_DEBUG_CONTROL` 能力 SID。每个 handler 先过共享 `guard()` (`DebugTools.java:201-212`):先 `if (!KdBridge.isAvailable()) return err(unavailableReason())`(没装 native 就诚实报错),再 `gate.require(CAP_DEBUG_CONTROL, SE_DEBUG_CONTROL)` 做纵深防御。断点/字段监视还会先 `SeProtectedObjects.isProtected(className)` 拒绝对 Core 自身下钩(`DebugTools.java:380-382,547-549`)。

这两个 SID/特权在内核层坐实:

| 载体 | file:line |
|---|---|
| `CapabilitySid.CAP_DEBUG_CONTROL` | `CapabilitySid.java:29` |
| `Privilege.SE_DEBUG_CONTROL` | `Privilege.java:26` |
| `CapabilityCatalog` 9 条映射 | `CapabilityCatalog.java:72-80` |
| `SeToolRequirement` 9 条映射 | `SeToolRequirement.java:143-151` |

**无死工具**:即便 DLL 没编,9 个工具照常 `registerAll` 到 registry(`DebugTools.java:75-81`),`McpCore` 启动时若探测到 unavailable 会打印一行提示但不阻断(`McpCore.java:201-214`)。

### A.6 精确的编译(clang,无需 MSVC)+ 启用步骤

DLL 现在用 **LLVM/clang** 编译,不再需要 Visual Studio / Windows SDK。按这个顺序做:

**第 0 步(一次性)— 装 clang。** `winget install --id LLVM.LLVM -e`(`build-clang.sh:9,21`)。脚本默认找 `/c/Program Files/LLVM/bin/clang.exe`,找不到再回退 `PATH` 上的 `clang`(`build-clang.sh:18-19`);也可用 `CLANG=<path>` 覆盖。

**第 1 步 — 编 DLL。** 从 Git Bash 运行 `bash core/src/main/native/core-jvmti/build-clang.sh`(`build-clang.sh:1-41`)。脚本会:
- 从 `../../../../../_tools/jbrsdk-25.0.3-windows-x64-b508.16/include` 定位 JBR 的 `jvmti.h`,找不到直接报错退出(`build-clang.sh:16,24-27`)——注意这是**bundled JBR 的头**,保证 `jvmtiCapabilities` 结构布局与运行时精确一致(`CMakeLists.txt:4-6`)。
- `clang -shared -O2 -I<jbr/include> -I<jbr/include/win32> core-jvmti.c -o build/core-jvmti.dll`(`build-clang.sh:33-36`),LLVM 自带 Windows 运行时 + 导入库,所以 `clang -shared` 无需 MSVC 即可链出可用 agent DLL。不需要链接 `jvm.lib`——agent 由 JVM 加载而非链接(`CMakeLists.txt:11-12`)。
- 产物拷贝一份到 `core-jvmti/core-jvmti.dll`(`build-clang.sh:38`),并打印可直接粘贴的 `-agentpath` / `-Dmcp.core.jvmtiLib` 启动串(`build-clang.sh:40`)。
- (备选路径:MSVC `build.bat`——从 "x64 Native Tools Command Prompt for VS" 或先跑 `vcvars64.bat` 后运行,`cl /LD /O2 /MD` 产出同名 DLL,`build.bat:25-30`;CMake:`CMakeLists.txt` 输出名 `core-jvmti`、无 `lib` 前缀、强制 x64,`CMakeLists.txt:13-18`。注意 clang 的 GNU driver 能正确解析 JBR 头,而 clang-cl / MSVC driver 在没装 Windows SDK 时链不过,见 `build-clang.sh:31-32`。)

**第 2 步 — 放开 2 行 jvm-arg。** 编辑 `jvm-args-mcp.txt`,取消最后两行的注释(`jvm-args-mcp.txt:46-47`),并让两者指向**同一个绝对 DLL 路径**:

```
-agentpath:<abs>\core-jvmti.dll
-Dmcp.core.jvmtiLib=<abs>\core-jvmti.dll
```

两行必须是同一文件:`-agentpath` 让 JVM 在启动时加载模块并跑 `Agent_OnLoad` 拿 onload 能力;`-Dmcp.core.jvmtiLib` 让 `KdBridge` 的 `System.load` 绑到**同一个已加载模块**(Windows 上 refcount,重复 load 无害,`core-jvmti.c:288-289`)。

**为什么 `-agentpath` 是强制的:** onload-only 能力(PopFrame/断点/单步/局部变量/suspend/force-early-return/字段监视)只能在 `Agent_OnLoad` 申请;动态 attach 拿不到。所以哪怕只 `System.load` 了 DLL,`nAgentReady()` 也会因为 `AddCapabilities` 没在 onload 跑过而返回 false。这也是交接文档定的"地基假设":没有 `-agentpath`,C6 就不可用(但客户端仍能起——降级契约保证)。

**注意副作用**:JVM 只允许一个断点级调试器,选了 `core-jvmti.dll` 就**不能同时用 JDWP**;C6 与 `-javaagent`(ByteBuddy/热加载)+ DCEVM 三参数共存无冲突(`jvm-args-mcp.txt:38-48` 注释亦如此说明)。另外 `build.bat` 未编时**切勿**提前取消注释:一个坏的 `-agentpath` 路径会让 JVM 引导直接 abort(`jvm-args-mcp.txt:42-44`)。

---

## B. 构建 / 运行

### B.1 mvnw 命令

模块结构:parent `mcpclient-parent` 聚合 `lwjgl2-shim` / `client` / `core` / `board` / `pg` 五模块(`pom.xml:13-18`;`pg` 本身又是含 `pg-api`/`pg-engine`/`pg-maven-plugin` 的 parent)。parent 默认 `release=8`(`pom.xml:21`),`core` 覆盖为 `release=25`(`core/pom.xml:28`)——同一 JDK 25 里 Core 的 Java 25 字节码与游戏的 Java 8 字节码共存。

以 JBR 25 作 `JAVA_HOME`,常用命令:

```bash
./mvnw -B -ntp -pl core -am package -DskipTests   # 只编 core + 依赖模块,出 jar
./mvnw -B -ntp test                                 # 跑测试
./mvnw -B -ntp clean package                        # 全量打包(含测试)
```

(`run-mcp.bat:8` 内注的推荐构建命令为 `mvnw.cmd -q -pl core -am package -DskipTests`。)

> Bash 陷阱(记忆在案):直接从 Git Bash 调 JBR 的 java/javac 时,MSYS 会把 `-cp` 里的 `/c/...` 路径传成 Windows java 无法解析的形式,需 `cygpath -w`;走 `mvn` 不受影响。

### B.2 测试现状

`core/src/test` 有多个 C6/native 测试(`DebugEventQueueTest`、`DebuggerBridgeFallbackTest`、`DebugToolsTest`、`NativeDebugGateTest`、`kd/NativeDebugEventSinkTest`、`NativeDebugOpLiveIT`、`ob/L6DebugGateThroughRegistryTest` 等)与多个结构化 GUI 测试(`GuiSnapshotTest`、`GuiToolGateTest`、`GuiSnapshotImageAssemblyTest`、`GuiTrajectoryTest`、`GuiClickLiveIT` 等)。精确清单/计数以 `05-TEST-MAP.md` + `../../STATUS.md` 为准(单一真相;C6 提交 `5999fdf` 追加 debug 测试、audit-fix 提交 `5f9f2c2`/`de83a33` 补齐 guard 回归、GUI 提交 `b1d9cba` 加入 snapshot/gate 测试)。**这些全部是 headless JVM 单元测试**(无 `-agentpath`,故 `KdBridge` 恒 unavailable——正是 fallback 测试验证的场景)。另有黄金协议测试保持不变。

### B.3 fat agent jar

`core` 的 `maven-shade-plugin`(`core/pom.xml:99-140`)产出 **`core/target/core-1.8.9-all.jar`**,classifier `all`(`core/pom.xml:111-112`):
- 打包 Core + ByteBuddy(Apache-2.0)+ MCP SDK 2.0.0(MIT)+ Jackson/Reactor;`client`(游戏)是 `provided` 不打进来(`core/pom.xml:38-43`)。
- manifest 写入 `Premain-Class`/`Agent-Class` = `net.marcloud.mcp.core.boot.CoreAgent` + `Can-Redefine-Classes`/`Can-Retransform-Classes` = true(`core/pom.xml:127-134`),所以这 jar **既是 `-javaagent` 又在 `-cp` 上**,自给自足。
- 无 GPL:DCEVM 是 JBR 内建(不是 HotSwapAgent),copyleft 不入 jar(`core/pom.xml:96-98`)。C5 需要的 `--add-opens jdk.internal.misc` + unsafe 已在 jvm-args 里(见下)。

普通 `core-1.8.9.jar` 也带 agent manifest(`maven-jar-plugin`,`core/pom.xml:79-92`),但需 deps 也在 `-cp` 上;实际启动用 fat jar。

### B.4 jvm-args-mcp.txt + run-mcp.bat

`jvm-args-mcp.txt` 是冻结的 `jvm-args-jdk25.txt` 的超集(`jvm-args-mcp.txt:1-4`),关键项:
- `--sun-misc-unsafe-memory-access=allow` + `--enable-native-access=ALL-UNNAMED`(Netty/LWJGL3/JNA)(`:9-11`)。
- **DCEVM 三参数**:`-XX:+AllowEnhancedClassRedefinition`(加字段/方法)、`-XX:+EnableDynamicAgentLoading`、`-Djdk.attach.allowAttachSelf=true`(`:13-18`)。
- 一批 `--add-opens java.base/...`(反射路径,含 C5 用的 `jdk.internal.misc`/`jdk.internal.ref`,`:21-31`)+ `java.desktop`(窗口图标 + ImageIO 截图,`:34-36`)。
- C6 的两行 `-agentpath` / `-Dmcp.core.jvmtiLib` **默认仍被注释**(`:46-47`);DLL 现已编好(见 A.6),要在真机启用 C6 时把两行取消注释并指向同一绝对 DLL 路径即可。(注:文件内该段注释仍写 "build.bat once MSVC Build Tools are installed" —— 那句已过时,现走 `build-clang.sh` 无需 MSVC。)

`run-mcp.bat`(`run-mcp.bat:1-32`)用 `_tools\jbrsdk-25.0.3-windows-x64-b508.16\bin\java.exe`,`@jvm-args-mcp.txt` + `-javaagent:core-1.8.9-all.jar` + `-cp "MCP-1.8.9.jar;core-1.8.9-all.jar"`,启 `net.minecraft.client.main.Main`,工作目录切到 `test_run`(资源/存档所在)。

### B.5 运行时 + 端口

- **运行时**:JetBrains Runtime 25 + DCEVM,位于 `_tools/jbrsdk-25.0.3-windows-x64-b508.16`(gitignored)。`_tools/` 下已存在该 JBR 及 dcevm_probe/e2e 探针目录;`core/src/main/native/core-jvmti/build/core-jvmti.dll`(+ 拷贝 `core-jvmti/core-jvmti.dll`)现已由 clang 编出并存在(均 gitignored)。
- **端口**:
  - **MCP socket 25599** — `SocketTransportServer.DEFAULT_PORT`(`SocketTransportServer.java:35`),127.0.0.1 上 newline-JSON-RPC。
  - **REST facade 1337** — `HttpFacade.DEFAULT_PORT`(`HttpFacade.java:45`),JDK 内建 `com.sun.net.httpserver`,`/v1/models` `/v1/tools` `/v1/screen` 等。
  - **P-SECURE 25601** — `AlpcProtocol.DEFAULT_PORT`(`AlpcProtocol.java:34`),L1 VTL 独立决策进程,opt-in(`-Dmcp.core.psecure=true` + 共享 `-Dmcp.core.psecureToken`),`McpCore` 里默认 `psecure=false`(`McpCore.java:332`)。启动器见 `AlpcMain`(`AlpcMain.java:17`)。

---

## C. 实机(JBR 在游戏里)验证清单

下面列出 Phase 2 各能力的真机验证状态。**C6 原生调试器已在运行中的真机 MC 客户端里做过首轮实机验证**(见第 5 项);其余能力**至今仍只在 headless 单元测试里验证过**。每项给出为什么 headless 不够、要在真机怎么看:

1. **Hook 实际触发(C3 动态 hook / FltDynamicManager)** — headless 只证明 transformer 能装/卸、route table 正确。需真机:进真实世界后确认 `NetworkManager.channelRead0`/`sendPacket`/`closeChannel` 的 advice 在真实收发包时 fire,`recent_packets` 有真实流量;装/卸 hook 后行为可逆。

2. **DCEVM 结构性 redefine(C4 / redefine_class)** — 探针 `_tools/dcevm_probe` 证明过加字段+方法,但在**运行中的游戏**里对活跃 MC 类 redefine、且老实例语义(新字段读默认值、上栈帧跑旧代码、下次调用生效)未在真机确认。需真机:游戏循环中途 redefine 一个活跃类,观察无崩溃、无 stale VarHandle 数据损坏(审计 H4 已修 `LdrEngine.setOnRedefined→MmAccess.invalidate`,需真机复验)。

3. **Seam 注入(C8 / SeamController:Netty MITM / GLFW / tick)** — headless 只测装卸与 dead `TickEvent` 接线。需真机:确认 Netty pipeline 注入的只读 ByteBuf dup 真的出现在收发路径、GLFW key callback 真收到输入、tick 注入真在 `runTick` 上跑,且 `stop()` 能干净拆除(重连不泄漏 handler——审计 H6 已修,需真机复验)。

4. **Deep-access(C5 / MmAccess:读写任意字段、调私有、开模块)** — headless 用测试类验证过 privateLookupIn/Unsafe 缓存路径。需真机:对真实 MC private/static-final 字段与私有方法操作,确认在 JBR25 运行时下 `--add-opens jdk.internal.misc`/unsafe 真的放行。

5. **原生调试器(C6 核心)** — **DLL 已用 clang 编出、并已在运行中的真机 MC 客户端里验证通过**(`JNI_OnLoad` bind-fix 之后)。已实机确认的项:`KdBridge.isAvailable()` 变 true;`debug_set_breakpoint` 在 `Minecraft.runTick` 上成功下断;线程 suspend 的按名查找可用;`debug_single_step` 开关切换执行真实 JVMTI 调用。**仍待补充实机验证的余项**(不粉饰):断点命中后 `DebugEvent` 端到端上 `EventBus` 的一次真实命中回流;`debug_pop_frame`/`debug_force_return`/`debug_read_local`/`debug_write_local` 在暂停帧上的语义;`debug_single_step` 事件洪流被 drop-oldest 队列限流而不 wedge 回调线程;`debug_watch_field` 修改监视的真实 fire。

6. **结构化 GUI 交互(GUI 层 / `gui_snapshot`+`gui_click_element`/`gui_type_text`/`gui_press_key`)** — headless 有 `GuiSnapshotTest`/`GuiToolGateTest` 验证快照建模与门控(`gui_snapshot` R2 OBSERVE;三个交互工具 R1 + `Privilege.SE_GUI_INTERACT`,`Privilege.java:25`)。需真机:在真实打开的 MC 屏幕(标题/暂停/多人列表等)上确认 `gui_snapshot` 用 vanilla 1.8.9 反射映射(`GuiButton.xPosition`、`Slot.xDisplayPosition`、`getStack()` 空时为 null)枚举出真实按钮/槽位与其 `clickPoint`,且 `gui_click_element`/`gui_type_text`/`gui_press_key` 驱动的是真实控件、快照失效时正确 REFUSE。

此外记忆列出的更宽的真机待办(F 项):JBR+DCEVM 长会话 soak(hook 持续 fire、无泄漏、循环中 redefine 安全)、RANK1 真实加密联机(需用户账号)、黄金回归在 JBR 运行时复确认、真实世界会话里的活包观测(目前仅 Stage-3 e2e 证明过、未在真实 world session 里)。

**一句话结论**:安全内核(L2-L7 + L1 opt-in)、C1/C3/C5/C7/C8 集成、C6 的 Java 侧 + native + 已编出的 DLL 都在源码里坐实并有 headless 测试;此前"`core-jvmti.dll` 卡 MSVC 未编"的硬缺口**已消除**——DLL 现用 clang(无需 MSVC/SDK)编出,且 C6 已在真机 MC 客户端里首轮验证通过(`Minecraft.runTick` 下断等)。剩下的缺口是 **整个 Phase 2 栈的完整真机 live 验证**(C1/C3/C4/C5/C8 全程 + C6 的余项,见上),都已给出精确的落地步骤。
