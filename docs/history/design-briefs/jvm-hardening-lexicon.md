---
doc: jvm-hardening-lexicon
title: JVM 独门加固术语库 — 5 方向行话 + 深挖搜索入口
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: antipiracy-architecture-research.md
    when: 你要看这些加固手段在整体防盗用/防抄袭里的定位
read_if: 你要开下一轮"JVM 独门加固"深挖沙盒——这里是钉死的专业行话 + 精确搜索词,喂 subagent 用。
---

# JVM 独门加固术语库

> 10-路并发采集(788K token,311 术语,207 个 JVM 独有)去重整理。方向:利用 JVM 特性做加固,
> 攻别人没现成工具打的 JVM 层。**这是行话入口,不是分析**;深挖是下一轮沙盒的事。
> 诚实边界不变:全是"买时间/收税",R-1 无信息论墙——但 JVM 层是破解工具的生地,收税效率高。

## 方向 1:JVM 动态性武器化(把热改能力反用于加固)

核心 insight:项目已有的热改/反射/agent 能力,可反过来让"静态 dump 到的类 ≠ 运行时真跑的类"。

- **`Instrumentation.redefineClasses` / `retransformClasses`** — 运行时替换/链式重织已加载类字节码。
- **`defineHiddenClass` (JEP 371)** — 动态定义不可发现的匿名类;跳过 ClassFileLoadHook(gpt5 复刻的关键)。
- **`invokedynamic`** — 调用目标由 bootstrap method 运行时决定,隐藏调用链。
- **`CONSTANT_Dynamic` (CONDY, JEP 309)** — 常量池条目懒计算,值首次 LDC 时才动态生成(藏常量/字符串)。
- **`MethodHandles.Lookup` indirection** — 运行时解析方法句柄,藏调用目标。
- **custom ClassLoader decryption** — 密文存储 + 加载期解密再交 JVM。
- **DCEVM / HotswapAgent** — 增强热替换(可改类结构),项目已用 JBR25+DCEVM。
- **`-XX:+DisableAttachMechanism` / JEP 451 EnableDynamicAgentLoading** — 禁外部 attach(反调试面)。
- **VarHandle / Class-File API (JEP 457/466/484)** — JDK22+ 官方字节码 API,取代 ASM。
- **武器化母题**:self-modifying bytecode · runtime class regeneration · dynamic bytecode weaving for protection。

## 方向 2:反编译器投毒(JVM 特有,native 世界没有这一层)

核心 insight:构造校验器接受、但 CFR/Fernflower/Vineflower/Procyon/JADX 反编译**出错或崩溃**的类。
攻击者拿 LLM 读反编译结果 → 你喂给反编译器的是毒。

- **StackMapTable manipulation / StackMap frame manipulation** — 栈图帧欺骗,反编译器类型推断崩。
- **`ConfusedCFRException` underrun / Underrun type stack** — 专门崩 CFR 的构造(有具名异常)。
- **LocalVariableTable poisoning** — 毒化局部变量表,反编译出错误变量。
- **Code attribute on field exploitation** — 往字段塞 Code 属性(非法但校验器可能放行)。
- **Paramorphism CFR-specific strategies / nearly-malformed bytecode** — 具名的近畸形字节码混淆器。
- **CFR illegal identifiers bypass** — 非法标识符名绕过。
- **Radon trash classes** — 垃圾类注入。
- **Zelix KlassMaster Flow/Reference Obfuscation · Allatori stack-trace-dependent string decryption · DashO overload-induction renaming** — 商业混淆器的具名 pass。
- **对手工具(验收线)**:Krakatau(字节码汇编器)· java-deobfuscator · Threadtear · Recaf · CFR/Procyon/Vineflower。

## 方向 3:kernel-as-data 虚拟化(JVM 上几乎空白 = 我们的生地)

核心 insight:关键架构逻辑不是 `defineClass` 的字节码,是自定义 ISA 的**数据**,跑在你自己的解释器里。
反编译器看到解释器不是逻辑;去虚拟化工具没有你的 ISA 规范。**JVM bytecode VM protector 现状是空白**(采集实证)。

- **virtualization-based obfuscation / bytecode-to-bytecode virtualization** — 方法编译成自定义 VM 字节码 + 解释器。
- **handler dispatch obfuscation** — dispatch 表混淆(VM 的核心攻击面)。
- **ConstantDynamic string encryption / String pool encryption** — 常量/字符串走 CONDY 懒解密。
- **control flow flattening + opaque predicate**(通用,但在 JVM 上组合)。
- **JVM bytecode VM protector gap** — 记住这个:成熟工具都打 native VM(VMProtect/Themida),JVM 层没有。
- **对手工具(验收线)**:VMDragonSlayer · Tigress_protection(去虚拟化案例)· Triton/Soot/SootUp/WALA(符号执行+分析)。
- **诚实边界**:去虚拟化研究成熟(但针对 native);JVM 上首次落地是自研,买"周"不买"小时"。

## 方向 4:JVMTI 反噬(用你的 native 层守 Java 层)

核心 insight:项目已有 C6 native JVMTI 调试器;同一套能力反过来**检测外部 agent/调试器/instrument**。

- **JVMTI `GetBytecodes` / `GetLoadedClasses` / `GetCapabilities` enumeration** — 检测别人在 dump/枚举你的类。
- **JVMTI `ClassFileLoadHook` / `CanRetransformClasses` capability** — 检测外部 retransform。
- **`JNI_GetCreatedJavaVMs` enumeration** — native 侧枚举 JVM 状态。
- **ClassFileTransformer presence detection via Instrumentation** — 检测有没有别的 transformer 挂着。
- **Anti-Agent-Agent** — 用 agent 防 agent 的母题(有具名)。
- **native-side integrity check of Java classes** — native 层校验 Java 类没被改。
- **对手绕法(诚实)**:R-1 控制 agent 加载顺序/可 stub 检测/hypervisor 看得更多——同权限打架,收税非墙。

## 方向 5:反调试 + 反VM/反沙箱(JVM 语境)

核心 insight:检测调试器/instrument/虚拟环境,挡业余 + 逼对手上真机,抬高基础设施成本。

- **JDWP detection**:`/proc/self/task/comm` JDWP string · `-agentlib:jdwp` substring check · JPDA backend damaging。
- **attach socket 检测**:`/tmp/.java_pid<pid>` · `/tmp/hsperfdata_<user>/<pid>` PerfData file check。
- **JVM flag 面**:`-XX:+DisableAttachMechanism` · `-XX:-UsePerfData` · `AttachPermission` · JEP 451。
- **timing-based debugger detection** — 时序探测(通用,可在 Java 落地)。
- **VM/sandbox artifact detection** — 检测虚拟机痕迹(逼真机)。
- **对手绕法(诚实)**:R-1/hypervisor 骗过所有本地检测;这些只挡业余 + 自动化农场,是"收税"层。

## 深挖搜索入口(下一轮沙盒直接喂,精确英文)

采集出 187 条,下面是去重后最该用的入口(按方向):
- 投毒:`CFR ConfusedCFRException underrun bytecode` · `StackMapTable manipulation anti-decompilation` · `Paramorphism decompiler crash` · `LocalVariableTable poisoning Java`
- 虚拟化:`Java bytecode virtualization obfuscator open source` · `JVM devirtualization symbolic execution` · `bytecode-as-data interpreter protection JVM`
- 动态性:`self-modifying bytecode JVM protection` · `defineHiddenClass anti-tamper` · `CONSTANT_Dynamic string encryption obfuscation` · `runtime class regeneration obfuscation`
- JVMTI 反噬:`detect java agent attached JVMTI` · `native anti-instrumentation Java` · `anti-debugging JNI TracerPid Java`
- 反调试:`Java anti-debugging JDWP detection` · `detect -agentlib:jdwp runtime` · `Java anti-VM sandbox detection`

## 关联
- 整体定位:`antipiracy-architecture-research.md`(加固是"防抄袭"轴,买时间非墙)
- 落地地基:`antipiracy-premise-verification.md`(P-SECURE 是服务端权威真接缝)
- 全量 311 术语原始数据:workflow `wf_a0d4e796-5fd` journal(本地)
