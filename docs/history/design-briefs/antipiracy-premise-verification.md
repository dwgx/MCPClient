---
doc: antipiracy-premise-check
title: 致命前提验证 — 真实代码里有没有"客户端无法自产的服务端接缝"
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: antipiracy-architecture-research.md
    when: 你要看这个前提为什么是整套防盗用的地基
read_if: 你要判断防盗用方案在本项目真实代码里能不能落地——这是 codegraph 实证,不是纸上设计。
---

# 致命前提验证(codegraph 实证)

> 所有 12 份防盗用设计都指向同一个前提:"解密型防 patch"只有当那段服务端下发的代码是
> 客户端**真正无法自产**的东西时才成立。本文用 codegraph 查了真实 core/client,给出实证答案。

## 结论(一句)

**答案是"部分 YES"——项目里已经有一条真接缝(P-SECURE / ALPC 跨进程权威),但当前它只做"权限决策",
不是"下发客户端无法自产的功能代码"。要落地防盗用,是把这条已存在的接缝从"决策权威"扩成"能力权威",
而不是从零造。**

## 查到了什么(真实代码)

### 1. 已存在的真接缝:P-SECURE / ALPC(这是金子)
- `alpc/AlpcMain.java`:**独立 JVM**,自己的地址空间,游戏 JVM 只能经 loopback socket 够到。
  注释原话:"a compromised in-game hook cannot forge a grant here"。
- `se/SeRemoteMonitor.java`:关键——`evaluate()` **故意不发送 subject**,"P-SECURE 进程拥有权威 subject,
  本地只发工具名"。权威**不在客户端**。`call()` 失败**fail-closed**(deny)。
- 这正是防盗用要的"服务端权威 + 客户端够不到"的**信息论接缝**,而且**已经建好、有测试**
  (PSecureHardenedDenyTest / PSecureRpcTest / RemotePolicyEngineDropFailClosedTest)。
- **但**:它现在传的是**布尔决策**(allow/deny + ring),客户端 patch 掉调用点就绕过(grok 的 Path D)。
  它是"检查型"权威,不是"解密型/能力型"权威。

### 2. C6 native handle(KdBridge):不是那个接缝
- `kd/KdBridge.java`:native 调试器句柄,但它是**客户端自己 System.load 本地 DLL** 得到的能力,
  不依赖服务端下发。**客户端能自产**(DLL 在本地)。所以它不满足"无法自产"。它是能力,不是防盗用杠杆。

### 3. 启动引导(CoreAgent/CoreBootstrap):是 gate,不是解密型
- `boot/CoreAgent.premain` + `StartupAdvice` 织进 `Minecraft.startGame()` → `CoreBootstrap.onGameInitialized()`。
  这是"启动时点火",纯本地,**没有服务端依赖**。patch 掉就绕过。典型的"检查型"接缝。

### 4. asset 解密链:未发现现成的"服务端下发密钥才能解"的资产管线
- client 的 `HttpUtil` 是 vanilla 资源包下载(明文 HTTP,无我方加密)。没有现成的加密资产接缝。

## 判定:方案该怎么落地(基于实证,修正纸上设计)

**好消息**:不用从零造服务端权威——`P-SECURE` 已经是"客户端够不到的独立进程权威",有协议、有 fail-closed、有测试。

**要做的转变**(把三方共识落到这条真接缝上):
1. **P-SECURE 从"决策权威"扩成"能力权威"**:不再只回 allow/deny(可 patch),而是回**客户端无法自产的东西**
   ——按 Fable/gpt5 复刻的设计,回一段**加密的、客户端必须执行、缺了跑不通**的能力材料 / 一次性挑战响应。
   这样 patch 调用点没用(没有正确材料,功能残缺崩溃 = 解密型,非检查型)。
2. **皇冠明珠搬进 P-SECURE 进程**(grok 的 Change 1):哪些能力包/决策是"被盗最痛"的,把它们的核心计算
   放进 P-SECURE 权威进程,客户端只拿结果。这把 P-SECURE 从"本地独立进程"升级成"远程服务端"的路已经铺好
   (SeRemoteMonitor 就是 socket 客户端,换 host 即可)。
3. **动作级短命能力**(grok 的 Change 2):P-SECURE 已经每次 evaluate 都 round-trip;把 license lease
   绑到能力材料上,短 TTL + 撤销。

**诚实边界(不变)**:R-1 仍能 dump 客户端拿到的能力材料明文;这只买时间。真墙是"皇冠明珠计算在权威进程里,
客户端从来没有完整逻辑"——而项目已有的 P-SECURE 独立进程,正是这个真墙的现成地基。

## 下一步(可落地的实事)
- 定"皇冠明珠清单":哪几个能力/决策值得搬进 P-SECURE 权威(需 dwgx 拍板产品价值)。
- 把 P-SECURE 协议从"布尔决策"扩到"能力材料下发"(SeRemoteMonitor / AlpcServer 是改造点,已定位)。
- 加密核心用 compat-crypto-core-design 的 HKDF 树 + 会话密钥(patrickfav/hkdf)。

## 关联
- 研究:`antipiracy-architecture-research.md` · 加密:`compat-crypto-core-design.md`
- 复刻共识:`../../sandbox/kiro-replica/COMPARISON.md`
- 真接缝代码:`core/alpc/AlpcMain.java`、`core/se/SeRemoteMonitor.java`、`core/alpc/AlpcServer.java`
