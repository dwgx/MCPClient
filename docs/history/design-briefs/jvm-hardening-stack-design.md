---
doc: jvm-hardening-stack
title: JVM 独门加固分层栈设计(深挖综合)
layer: reference
status: draft
updated: 2026-07-12
parent: ../README.md
next:
  - path: jvm-hardening-lexicon.md
    when: 你要看每层技术的行话来源与搜索入口
read_if: 你要落地 JVM 加固(防抄袭轴)——这是 15-agent 深挖综合出的 7 层栈 + 落地优先级 + 诚实边界。draft,review 后动代码。
---

# JVM 独门加固分层栈(深挖综合)

> 15-agent 深挖(动态模型分配:6 Opus/high 设计+综合,9 sonnet 检索;1.13M token,11/12 采集回)。
> 目标=防抄袭(买时间轴),皇冠明珠=整套架构必须客户端跑 → 买时间 + 迭代速度甩开。诚实底线:
> R-1 活体 dump 时间=0,买时间只在"静态→动态"和"自动化→人工"两个门槛。

## 7 层栈(每层挡谁 / 开销 / JVM 独门优势 / 优先级)

| 层 | 技术 | 挡谁 | 开销 | JVM 独门 | 优先级 |
|---|---|---|---|---|---|
| **L1 动态自修改** | retransformClasses 周期重写 · DCEVM 结构变更 · 每会话 ISA 置换 · handler 多态 | 静态工具 · 一次性 deobf 脚本 · 跨会话指纹库 · 频率分析 · 后向切片 | load-time 一次扫描,稳态零开销 | **是**(DCEVM+retransform native 无此能力) | **high** |
| **L2 常量/引用隐藏** | CONDY 加密常量 · invokedynamic 藏调用 · MethodHandle 间接 | 纯静态反编译(CFR 无法求值 CONDY)· 调用图 · points-to · strings | 首次 ldc bootstrap,后零开销 | **是**(CONDY+indy+Lookup+CallSite 是 JVM 独有完整机制) | **high** |
| **L3 加载层加密** | Custom ClassLoader 解密 · JNI hook JVM_DefineClassWithSource · ClassFileLoadHook 解密 | 静态 JAR 解包 · 磁盘抓取 · 源码泄露 | 每类加载 AES 一次(大类拖启动) | 部分 | medium |
| **L4 Dispatcher 抗去虚拟化** | direct-threaded dispatch(消枢纽)· SVP For(k=5) 钉 vpc · overlapping exception-table | 自动 devirt 枢纽检测 · 符号执行(KLEE/Triton)· handler 模式库 | direct-threaded 零开销;SVP k=5 ×1.3 | 部分(JVM 缺成熟 devirt 工具=生地) | **high** |
| **L5 结构投毒** | StackMapTable strip · ConfusedCFRException underrun · LVT 投毒 · 字段挂 Code 属性 · 非法标识符 | 反编译器崩溃/降级 · javap · 自动化 pipeline | 纯元数据,运行时零开销 | **是**(class 文件格式攻击面) | low |
| **L6 Native 边界** | 敏感逻辑编 .so · VMProtect JNI · JVMTI GetBytecodes hash 校验 | Java 层 hook(Frida/Xposed)· 字节码篡改 · 内存 dump 还原 | JNI 微秒级;VMProtect ×10-100 选择性 | 部分(跨语言 barrier) | medium |
| **L7 反调试/反 agent** | DisableAttachMechanism · GetLoadedClasses 扫 agent · TracerPid · attach socket 检测 · timing | 动态 attach(Arthas/jmap)· Frida · gdb · JVMTI dump | 10-20ms,CPU<0.5% | 部分 | medium |

## 首要动作(采集与综合两路一致收敛的结论)

**三件套,约 1500 行,1 周,ROI 最高**:
- **每会话 ISA 置换 + CONDY 藏映射**:load 时生成会话种子 S → keyed-PRF 导出置换 π_S → 重排 dispatch 表 + 重写 opcode 字节流;映射表密钥藏进 CONSTANT_Dynamic bootstrap。
- **direct-threaded dispatch + handler 多态**:取消中心 switch,handler 尾跳;2-3 份语义等价变体,破 1:1 指纹 + 枢纽检测。
- **SVP For(k=5) 钉 vpc**:For-loop 分叉点钉在 vpc/opcode 选择变量,2^40 路径 → DSE 24h timeout,封死符号执行去虚拟化路径(CHES 2019 实证 KLEE/Binsec/Triton 0/10 solved;k=50 仍仅 ×1.3)。

为什么是它:成本最小(纯 ASM + 现有 agent 基础设施,无 native 依赖),打掉自动 devirt 的两大前提(中心枢纽 + 静态映射表),且 JVM 独门(CONDY = 工具荒漠)。**建在项目已有的 CoreAgent/ClassFileLoadHook 基础设施上。**

## 与 P-SECURE + 迭代速度的关系(三轴分工)

- **加固(本栈)= 买时间轴**:拖慢逆向/复制(周-月),守内核"怎么看懂"。
- **P-SECURE = 防白嫖轴**:实时门控(秒级),守边界"谁能用"。dump 后实例缺服务端配合跑不起来。
- **迭代速度 = 真护城河(进攻轴)**:6 周/版本,让破解成果过时。
- **关键对齐**:加固买的时间(数周)必须 ≥ 版本迭代周期(6 周),破解者才永远追新版。
- **纵深**:P-SECURE 失守(key 泄露)→ 加固仍挡逆向;加固失守(R-1 dump)→ P-SECURE 仍挡大规模白嫖。P-SECURE 的 auth/license 逻辑本身要用 L6/L7 native integrity 护(否则被 patch = 防白嫖失效)。

## 诚实边界(必须随方案存在)
- **R-1 活体 dump 时间 = 0**。买时间只在两个门槛:静态→动态、自动化→人工。
- L5 投毒:工具链 2-6 个月适配,ASM COMPUTE_FRAMES 一键修 StackMapTable;是减速带非墙。
- L7 反调试:Frida/shouganaiyo-loader 进程注入绕过所有 Java 层检测(5 分钟);只挡自动化 + 心理威慑。
- L6 native integrity:2025-26 Frida 高度自动化,实测防御可能 <1 天。
- 移动目标(L1)对 R-1 无效,只让"一次 dump + 一次脚本"失效,逼每会话重导出。

## Gap 验证(codegraph 实证,2026-07-12)

**结论:三件套的基础设施 100% 已就位——首要动作不用造地基,是在现成能力上加 pass。**

真实代码确认:
- **ByteBuddy + ASM 已是 core 依赖**(CoreAgent/FltManager/FltDynamicManager 都用 `AgentBuilder`+`Advice`)。ASM 是 ByteBuddy 底层,SVP/dispatch 字节码重写可达。
- **Instrumentation + retransform 已捕获并封装**:`CoreAgent.premain` 拿 Instrumentation,`AgentAccess` 是唯一门控入口,`FltDynamicManager` 已做 install/uninstall/retransform 全生命周期,`canInstall()` 检 `isRetransformClassesSupported()`。**L1 周期 retransform 的机制现成。**
- **ClassFileTransformer 基础设施在**:AgentBuilder RETRANSFORMATION 策略已用于 hook。**load-time ISA 置换(ClassFileLoadHook)可挂同一套。**
- **自定义 ClassLoader + defineClass 现成**:`DynamicClassLoader.define/findClass` 已在跑;`InMemoryCompiler` 内存编译;`LdrEngine` 统一 load/redefine。**L3 加密加载链有现成骨架可扩。**
- **DCEVM 结构重定义已接**:`LdrRedefiner.redefine` + `LdrEngine.redefineExisting` 已处理 DCEVM(结构变更走 JBR+DCEVM,失败优雅降级)。**L1 结构变更能力已验证在跑。**
- **防护自伤已有守卫**:`SeProtectedObjects.isProtected` + `notProtected()` matcher 保证加固 pass 不会织进内核自身/被 redefine——加固施加时天然复用这道闸。

**因此三件套(ISA置换/direct-threaded/SVP)= 在 FltDynamicManager/CoreAgent 的 retransform 通道 + DynamicClassLoader 的 defineClass 通道上加字节码 pass,不新建骨架。** 冻结骨架(ARCHITECTURE-LOCK)不动:加固是构建期/加载期的新 pass,施加于 core 产物,复用现有 agent 基础设施。

仍需**真机实测**(codegraph 答不了,要跑):
- SVP For-loop 在 JVM 字节码 + JDK25/JBR25 的 stack-map frame 是否稳(ASM COMPUTE_FRAMES 行为)。
- DynOpVm 按块切表被 C2 JIT 折叠回枢纽的风险(需 `-XX:+PrintCompilation` 量化)。
- v50 无 StackMapTable 的类在 JBR25+DCEVM 能否 load。
- 周期 retransform 的 Metaspace 压力实测。

## 待验证 gap(深挖原始清单,部分已被上面 codegraph 消解)
1. **SVP For-loop 在 JVM 字节码的实现**:CHES 2019 在 native 实证,Java 需自己 ASM 实现(受输入界定循环+分叉点),可行但无公开实现。
2. **DynOpVm 按块切表的 JIT 折叠风险**:C2 公共子表达式消除可能把尾分派折回枢纽,需实测 handler 多态能否对冲。
3. **ClassLoader decrypt + JNI hook 跨平台构建**:现成 hook 是 Rust+GPL-3.0(能否集成?或自写 ~400 行 C++)。
4. **v50 StackMapTable strip 在 JDK25/JBR25+DCEVM 的实际加载行为**未验证(legacy verifier failover 可能已移除)。
5. native integrity 对 2025-26 Frida 的真实防御时间需实测。

## 关联
- 术语来源:`jvm-hardening-lexicon.md` · 整体定位:`antipiracy-architecture-research.md`
- 防白嫖地基:`antipiracy-premise-verification.md`(P-SECURE)
- 加密核心:`compat-crypto-core-design.md`
