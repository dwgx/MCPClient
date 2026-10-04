---
doc: compat-ledger
title: Compat 补丁台账(可增长)
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../architecture/07-COMPAT-SHIM.md
read_if: 你要看当前有哪些兼容补丁、或按流程加一个新补丁。概念/架构见 07-COMPAT-SHIM。
---

# Compat 补丁台账

> 这是**会持续长**的补丁账本——每确认一个移植 bug、做一块签名补丁,就在这里记一条。
> 架构与签名机制见 [`../architecture/07-COMPAT-SHIM.md`](../architecture/07-COMPAT-SHIM.md)。
> 补丁本体在 `core/.../compat/patches/`。

## 如何加一个新补丁(流程,像发布一块驱动补丁)

1. **确认它是真 bug**:必须有已证实的 KI(带真机/测试证据)。**没证据不做补丁**——
   猜测不进库(呼应铁律:can't reproduce = probably not a bug)。
2. **登记 KI**:在 `docs/project/known-issues.md` 有对应 KI 条目,状态与证据齐全。
3. **写补丁类**:`compat/patches/` 下新增一个实现 `CompatPatch` 的类:
   - `targetClass()` 打哪个类
   - `platformCondition()` 仅在触发 bug 的平台生效
   - `transform(bytes)` 字节码怎么改
   - `manifest()` 身份(code / kiRef / version / publisher / builtAt / supersedes …)
4. **签名**:用内核密钥对补丁固化签名(机制待加密核心定稿)。
5. **登记进 `CompatDatabase`** + 在本台账下方**补丁清单**加一行。
6. **配非空转回归**:补丁必须有一个"在打补丁前会失败"的测试(headless 能测的部分),
   或明确标注需真机验证的部分(像 KI-1 的 glGetTexImage 读回证明)。

## 补丁编号规则

`MCP-KI####` —— 与 KI 编号对应(一编号一已知问题),对标 Windows 的 KB number。

## 补丁清单(台账)

> 状态图例:`planned`(设计中,未实现)· `verified`(已签名+已验证生效)·
> `superseded`(被新补丁取代)· `disabled`(暂时停用)。

| 编号 | 名称 | 修的 KI | 目标类 | 适用条件 | 状态 | 证据 |
|---|---|---|---|---|---|---|
| MCP-KI0001 | MipmapZeroFill | KI-1 | `net.minecraft.client.renderer.texture.TextureUtil` | 仅 LWJGL3 | planned | 真机 glGetTexImage 读回全 0(见 KI-1) |
| MCP-KI0004 | LocalServerChannelBind | KI-4 | `net.minecraft.network.NetworkSystem` | 仅 Netty≥4.2 | planned | 真机 GUI 进单人世界(见 KI-4) |

> 说明:KI-1 / KI-4 此前用直接改 client 源码验证过修复有效(已回滚以保 client 纯 vanilla)。
> 它们现列为 compat 补丁的**首批候选**,状态 `planned` —— 待 compat 引擎 + 加密核心落地后,
> 以签名补丁形式重新实现(不再碰 client 源码)。

## 与已回滚工作的关系

KI-1(mipmap 零填充)、KI-4(LocalServerChannel 绑定)曾以直接改 client 源码的方式实现并
真机证实,后因破坏"client 纯 vanilla"原则**已回滚**(本地+远端历史已重写)。compat 系统
正是为这类修复提供**不碰 client 的正式归宿**。真因与证据仍完整保存在 known-issues KI-1/KI-4。
