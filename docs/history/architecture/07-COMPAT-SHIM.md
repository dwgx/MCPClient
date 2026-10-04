---
doc: arch-compat-shim
title: 07 — Compat 补丁系统(NT AppCompat / Shim Engine 对应物)
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: ARCHITECTURE-LOCK.md
    when: 你要确认 compat 作为冻结层的边界与变更闸门
read_if: 你要理解"已确认的移植 bug 如何在启动时被签名补丁修复,而不改 client 源码、不走热加载"。
---

> **[陈旧警告 2026-07-15b:本篇停在 2026-07-12,签名/补丁两处已被超越,勿据旧文行动]**
> ① **签名不是 HMAC 占位** —— 真 **Ed25519PatchSigner** 已落地(+ `TufTrust`/`RootTrust`/`KernelTrustAnchor`/`ContentHash`/`PatchCanonicalizer`,TUF L0-L3,提交 `d77f832`…`34d211c`)。下文所有"HMAC 签名/待专门设计/接口占位"(:56/:68/:87/:95/:100/:118-120)均为 07-13 加密线之前的旧稿,`PatchSigner` **不再是空接口**。**别去"实现 HMAC"或改 `PatchCanonicalizer.signingInput`——那会破坏已落地的 TUF 信任链(安全脊柱)。**
> ② **默认补丁库不空** —— `Compat.defaultDatabase()` 已注册 **Ki1MipmapZeroFillPatch + Ki4LocalServerChannelPatch** 两个带真内核签名的补丁,KI-4 可 arm 且已 live 验(见 `known-issues.md` KI-4)。台账里 MCP-KI0001/0004 若标 `planned` 是旧状态。
> ③ 本篇 `next` 指向的 `../compat/README.md` 不存在(该台账实为本篇 §manifest 表)。
> 现状权威:`STATUS.md` §compat 加密线 + `known-issues.md` KI-1/KI-4。

# 07 — Compat 补丁系统

> ** 铁则(2026-07-13,一次真实疏忽换来的):游戏(`client/` 的 `net.minecraft.*`)的任何移植 bug 修复,一律走 compat 签名补丁 —— 绝不 `DIRECT_CLIENT_EDIT`(直接改 client 源)。**
> **教训经过**:一个修复 workflow 曾让 8 个 agent 全部产出 `DIRECT_CLIENT_EDIT` 候选(直接改 EntityRenderer/NetworkSystem/EnumConnectionState 等 vanilla 类),差点提交 —— 直接违反本篇 + 铁律③(client 纯 vanilla)。幸好都在 worktree 未落地。**根因:派 agent 时没把"必走 compat 补丁"写进硬约束,让 agent 自选落地方式。** 以后任何"修游戏 bug"的任务,派发前必须写死:产出 `CompatPatch`,不碰 client 源。修复逻辑可复用,但形态必须是补丁。

> NT 隐喻:**Windows AppCompat / Shim Engine**。平台在游戏底下变了
> (LWJGL2→3、Netty 4.2、JDK25)→ 产生移植 bug → **启动期(premain)**给受影响的
> vanilla 类套上**已签名的兼容补丁**修好。`client/` 保持纯 vanilla 映射,补丁独立存在、
> 可持续增长——像一块块**已签名的驱动修复补丁**。

## 为什么要它(设计动机)

移植 bug 的本质:游戏代码在原版环境(老 LWJGL2 / 老 Netty)是对的,是**底层库升级**
改变了行为,把原本"能凑合"的地方暴露成 bug(见 KI-1 mipmap、KI-4 LocalServerChannel)。

修这类 bug 有三条路,前两条都不满意:
- **改 client vanilla 源码** → 破坏"vanilla 映射"纯度(反射/字段名基准会漂);已被否决并回滚。
- **运行时热改(ldr redefine / flt hook)** → 修复依赖 Core 挂载,裸跑 client 时 bug 仍在;
  且是"跑起来才修",不是"游戏本身就是好的"。
- **compat(本篇)** → **启动期套补丁**:游戏类首次加载前就已打好补丁,client 源码零改动,
  修复是确定的、持久的、随游戏一起在。这是正解。

## 与现有层的边界(务必分清,否则以后会混)

| 层 | 时机 | 目的 | 谁触发 | 授权 |
|---|---|---|---|---|
| `flt` (+seam) | 运行时 | 观察(hook 发事件) | LLM / seam | 门控 |
| `ldr` | 运行时 | 交互式热改类 | LLM(`redefine_class`) | R-1 |
| **`compat`(新)** | **启动期 premain** | **修已确认的移植 bug** | **内核自动** | **验签,不走 R-1** |

关键区别:`flt`/`ldr` 是**运行时、LLM 驱动、受门控**的;`compat` 是**启动期、内核自动、
靠签名而非 ring 门控**的。三者共用 ByteBuddy/Instrumentation 底座,但目的与生命周期不同。

## 包结构(core 下)

```
core/src/main/java/net/marcloud/mcp/core/compat/
├── CompatEngine.java      引擎:premain 收集补丁 → 验签 → 注册总 ClassFileTransformer
├── CompatPatch.java       补丁接口(每补丁一个类实现)
├── PatchManifest.java     补丁身份/元信息(对标 Windows .cat + .inf)
├── PatchSigner.java       HMAC 签名/验签(内核密钥)——核心加密,待专门设计
├── CompatDatabase.java    补丁库(= Windows .sdb 角色):登记所有已确认补丁
├── CompatTools.java       向 IoManager 注册只读 list_compat_patches
└── patches/               补丁本体,一块块加(像驱动补丁)
    ├── Ki1MipmapZeroFillPatch.java
    └── Ki4LocalServerChannelPatch.java
```

## 机制:启动期打补丁(不是热加载)

1. `boot` 的 premain 拿到 `Instrumentation`(已有能力)。
2. `CompatEngine` 从 `CompatDatabase` 取出所有已登记补丁。
3. 对每个补丁 **验签**(`PatchSigner`,内核密钥 HMAC);验签失败 → 拒绝加载该补丁。
4. 对通过验签、且 `platformCondition` 匹配当前环境的补丁,注册进一个总
   `ClassFileTransformer`。
5. 游戏类**首次加载时**被打好补丁 —— 游戏从一开始就是修复态,`client/` 源码一字未改。

因此这是"加载期补丁",不是 `ldr` 那种"运行中热 redefine";也不依赖 LLM 连上来。

## 补丁身份:PatchManifest(对标 Windows,逐条映射)

| 字段 | 对标 Windows | 含义 |
|---|---|---|
| `patchId` | SDB fix GUID | 从「目标类+变换内容+KI+publisher」**确定性派生**(内容寻址,改一字节即变) |
| `code`(`MCP-KI0001`) | KB number | 人读补丁编号,一编号对应一个 KI |
| `name` / `version` | Shim name / FileVersion(四段) | 补丁短名 / 自身版本(同一 bug 修法迭代) |
| `kiRef`(KI-1) | KB↔issue 绑定 | 挂哪个已证实的已知问题 |
| `targetClass` | EXE 匹配条件 | 打哪个类(可扩展到方法签名) |
| `platformCondition` | RUNTIME_PLATFORM / Applicability rules | 仅在触发 bug 的平台生效(如 KI-1 仅 LWJGL3;KI-4 仅 Netty≥4.2) |
| `publisher` | Signer | 谁做的补丁 |
| `builtAt`(ISO-8601) | RFC3161 时间戳 | 生产日期(补丁固化时间) |
| `contentHash` + `signature` | Catalog hash(.cat) + Authenticode | 变换逻辑哈希 + 其 HMAC 签名 |
| `supersedes` | Supersedence | 取代哪个旧补丁(可空) |
| `evidence` | (Windows 无) | 指向真机验证/测试——**比 Windows 更严的一条** |
| `status` | — | verified / superseded / disabled |

## 签名 = 信任模型的安全支柱(重心:密码学真防伪造)

补丁"已核实即信任、启动期自动应用、不走 R-1 门控"——**凭什么信它没被塞私货?**
答案是签名:`CompatEngine` 只加载经**内核密钥 HMAC 签过**的补丁,验签失败即拒。
**没有内核密钥的人,伪造不出能被加载的补丁。** 这不是障眼法(不是"故意写乱的格式"),
是真正的密码学边界——它才让"不门控自动应用"这件事安全。

> **核心加密逻辑(密钥管理、签名算法、派生规则)是独立的安全设计,单独立章确定,
> 见本篇 [附录:加密核心(待定)]。在它定稿前,`PatchSigner` 仅留接口占位。**

## 信任 / 进库门槛

每个补丁进 `CompatDatabase` 必须三者齐全:
1. 挂一个**已证实的 KI**(带真机/测试证据,`evidence` 指向它);
2. 走**类 ADR 的确认**(记录为何这是真 bug、为何这样修);
3. 被**内核密钥签名**。

## 可观察(走上层 API 的唯一一处)

补丁的**应用**是内核底层自动的(不穿过 Board / MCP 工具门控)。但补丁的**状态**对上层透明:
`CompatTools` 向 `IoManager` 注册只读工具 `list_compat_patches` —— LLM / 开发者能查
"当前加载了哪些补丁、各修哪个 KI、目标类、生产日期、签名状态、是否被取代"。
应用自动、观察走 API、透明可审计 —— 呼应 Windows"能查程序应用了哪些兼容性设置"。

## 附录:加密核心(待定)

`PatchSigner` 的密钥来源、HMAC 算法参数、`patchId` 派生函数、签名覆盖字段范围,
是**独立的安全设计**,用户将单独敲定。此前 `PatchSigner` 只暴露
`sign(manifest, transformHash)` / `verify(manifest)` 接口占位,不落实现。

