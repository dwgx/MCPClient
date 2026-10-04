---
doc: pg-design
title: PatchGuard (pg) — 标注驱动加固库设计
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: jvm-hardening-stack-design.md
    when: 你要看 pg 将要承载的完整加固技术栈(三件套)
read_if: 你要用 @Guarded、加新 HardenPass、或理解 pg 的 3-artifact 结构与 fail-safe 契约。
---

# PatchGuard (pg) — 标注驱动加固库

> **状态(2026-07-13,诚实标注):Phase 1 — 引擎就绪,但未接线到任何模块,尚不保护任何代码。**
> `@Guarded` 在 `pg/` 之外**零消费者**,pg-maven-plugin 没挂进任何模块的构建(已实证)。
> 即"advertised-but-dead":产品里没有一个类被加固。**任何"pg 保护内核/安全类"的说法目前都不成立。**
> 这是有意保留的现状(dwgx 决定先列出、不接线),不是遗漏。接线前必须先修下述 ASM 正确性 bug。

> **接线前必修(2026-07-13 全项目 review,3 条,详见 `../project/known-issues.md` KI-5/6/7):**
> - **H6(dead)**:接线才生效——挑真实类打 `@Guarded` + 把插件挂进该模块构建 + 加"打包后 .class 不含明文串"的断言。
> - **H8(非幂等)**:`StringConstantPass` 二次运行会注入重复 `pg$dec` 方法 → `ClassFormatError`(增量构建不 clean 就中招);且 `verify()` 抓不到,报 HARDENED 却是坏类。修:检测已有 `pg$dec` 则跳过整类。
> - **H9(COMPUTE_FRAMES)**:`ClassWriter.COMPUTE_FRAMES` 用插件类加载器解析引用类型合并,看不到被加固的项目类 → 任何引用类型帧合并的 `@Guarded` 方法静默回退(明文串照旧),构建仍报成功。修:override `getCommonSuperClass` 默认 `Object`。

> NT 母题命名(Windows 内核 PatchGuard,防内核代码被 patch)。业务代码标 `@Guarded`,
> 构建期自动施加字节码加固,产出加固 jar,**零源码污染**。设计为可独立外抽(零 core/board 依赖)。

## 为什么 3 个 artifact(不是 1 个)

三者生命周期/依赖面不同,合一会互相污染。分离是"高质量+可扩展+鲁棒"的正解:

| artifact | 职责 | 依赖 | release |
|---|---|---|---|
| **pg-api** | `@Guarded` 标注契约(业务代码唯一 import 的) | 零 | 8(全模块可用,含 board/client) |
| **pg-engine** | HardenPass SPI + 具体 pass(ASM);fail-safe 引擎 | pg-api + ASM | 17(构建期跑) |
| **pg-maven-plugin** | 构建期 Mojo:扫 `@Guarded` → 调 engine → 原子替换 .class | pg-engine + maven | 17 |

## 引用体验(零污染)

```java
import net.marcloud.pg.Guarded;

@Guarded                              // 默认 STANDARD
public final class SeReferenceMonitor { ... }

@Guarded(Guarded.Level.VIRTUALIZE)    // 最强档(留给最小最冷最值钱的逻辑)
public final class KeyDerivation { ... }
```

模块 pom 挂 `pg-maven-plugin`(bind 到 process-classes)+ 依赖 `pg-api`,`mvn package` 自动加固。

## 标注契约

- `@Guarded` 可标 TYPE 或 METHOD;标任一成员即"加固此类"(方法级粒度是 pass 的事)。
- **CLASS retention**(不是 RUNTIME):marker 要活到 .class 供构建期扫,但不留运行时"这类被加固了"的索引给逆向者。
- `Level`:`STANDARD`(元数据+常量加固,零开销)⊂ `FLOW`(+控制流)⊂ `VIRTUALIZE`(+ISA 虚拟化)。每级是下级超集。

## HardenPass SPI(可扩展骨架)

每个加固技术 = 一个 `HardenPass`(id + minLevel + apply)。加技术 = 加 pass + 注册,**pg-api 和业务代码不变**。
- 契约:不适用就原样返回;内部错误可抛(引擎当 fail-safe 处理);同 (seed, class) 必确定(可复现构建)。
- 已实现:`string-constant`(STANDARD,XOR 常量 + 注入解码器,消除明文串)。
- 待实现(承载 jvm-hardening-stack 的三件套):ISA 置换 / SVP / dispatch 混淆(FLOW/VIRTUALIZE);DLL emit stub(将来)。

## Fail-safe 契约(鲁棒性核心)

**加固是构建期便利,绝不能产出加载不了的 jar。** 引擎逐 pass:
- pass 抛异常 → 丢该 pass,保 pre-pass 字节。
- pass 产出 → `CheckClassAdapter` + COMPUTE_FRAMES 重验证 → 不过则 revert 该 pass。
- 最坏情况 = "这类没被加固",绝不 = "这类坏了"。
- 插件写盘 = temp + ATOMIC_MOVE(崩溃不留半写)。

## 移动目标 / 可复现

`HardenContext.seed`:插件默认 `-1` = 每次构建随机 seed(per-release 移动目标);pin 固定 seed = 可复现构建。
pass 从 `classSeed()`(seed 混类名)派生随机,同 seed 同类必同输出。

## 诚实边界

加固买时间,非信息论墙:R-1 活体 dump 拿得到运行时明文(解码后的串在内存里)。string-constant 只挡静态提取/反编译阅读。
pg 的价值 = 抬高静态/自动化分析成本 + 配合迭代速度(见 jvm-hardening-stack-design 的三轴分工)。

## 外抽计划

物理位置现在项目内(叶子模块,反应堆先构建),架构按外部库设计:零 core/board 依赖、干净 API 边界。
验证稳定后抽成独立仓库 = 搬文件夹 + 建仓,不重写。

## 关联
- 加固技术栈(pg 要承载的三件套):`jvm-hardening-stack-design.md`
- 术语库:`jvm-hardening-lexicon.md` · 整体防盗用:`antipiracy-architecture-research.md`
- 代码:`pg/pg-api`、`pg/pg-engine`、`pg/pg-maven-plugin`
