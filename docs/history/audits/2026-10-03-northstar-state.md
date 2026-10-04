# 北极星当前状态 —— 主 agent 亲验的版本

2026-10-03 深夜。**这一份是唯一可以据以判断「做到哪了」的汇总**，
它区分**我自己验证过的**与**worker 报告的**。

---

## 0. 一句话

**三把尺子全部接到活客户端、桥已存在并注册、隔离已成立——
而这一切加起来的结论是：北极星的前提「现有工具面」已被证伪，
所以现在该做的不是「跑一局」，而是先裁定工具面要不要放宽。**

---

## 1. 我自己亲跑的数字

```
PATH="D:\Software\Developer\jdk\25\bin:$PATH" JAVA_HOME= sh ./mvnw -pl client,core test
Tests run: 2145, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS （98.00 秒）
```

**起点是 2025 / 5 failures / 0 errors / 1 skipped。**
**⇒ +120 个测试、5 红全清，且这是六个 worker 落地之后我自己的跑数，
不是转述。**

---

## 2. 三把尺子（`Rulers` 落地，我核实了文件在 main 树）

| 尺子 | 工具名 | 生产类 |
|---|---|---|
| 庇护所 | `night_shelter` | `eval/NightShelter.java`（本会话早前） |
| **血量** | `night_health` | `eval/NightHealth.java`、`eval/ClientVitals.java`（新） |
| **箱子** | `night_box` | `eval/DawnChestRegion.java`、`eval/ClientArea.java`（新） |

**五个新文件我亲验在 `core/src/main`**；`drivers/observe/NightRulerTools.java:70` 与 `:160`
分别是 `night_health` 与 `night_box`。

**而它证明了我预判的那条血量边界**：三个量各自独立成载荷键，
`absorbedHits = hitEdges - healthDrops`；变异实测删掉它的 `put` → 10 条里只 1 条红。
**断言名即那条边界：`aHitTheInvulnerabilityGuardRefusedIsInvisibleToTheBarAndNotToThisRuler`。**

---

## 3. 链条（环 4 与环 5 已闭合）

```
1  run-mcp.bat:103-107      启动游戏本体                —— 只差两个 jar
2  SocketTransportServer    kernel 开 MCP 于 25599      —— 就绪
3  McpCore.java:230         「not stdio — 游戏占着 console」—— 就绪
4  桥 scripts/mcp_stdio_tcp_bridge.py  6/6 验收通过      —— 已闭合
4' 注册到隔离档自有用户域 ~/.omp/profiles/modelbridge-iso/agent/mcp.json —— 已写入
5  隔离：--profile + overlay enableProjectConfig:false 两个作用域  mcp__=0 —— 已闭合
```

**而 `Runbook` 的第④ 次实测就是链条走通的证据：25599 有东西说话时，
9 个工具 / 3 个 `mcp__`，恰好是那三把尺子。**
（③ 是游戏没起时 omp 报 `mcpclient` failed to connect——**说明注册确实被读到了**。）

**注册答案（三层问题，主 agent 核实文件已落）：**
> **`--profile` 换掉的是用户域的位置不是生死，`enableProjectConfig:false` 换掉的是项目域不是用户域。
> 两个开关都指着别处，所以必须写第三层：隔离档自己的用户域。**

---

## 4. 一条假绿，我已从一手源码核实

`Runbook` 自己踩到：**第一版 registry 检查在游戏已停的情况下报 PASS。**

**原因（主 agent 从 GitHub 一手源码核实，`packages/coding-agent/src/mcp/tool-cache.ts`）**：
```
:12   const CACHE_PREFIX = "mcp_tools:";
:13   const CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
```
**（`Runbook` 报的是 `:11-12`，实际 `:12-13`——行号差一，内容完全正确。）**

**⇒ 连接未就绪时 omp 用一份 30 天的缓存工具清单顶替，
所以「工具面核对」可以对着一台死掉的游戏报绿。**
**⇒ 而注册写错会报错，这个不报错——**对着死游戏还绿的检查比红的更糟。**

**⚠ 本轮我没能独立复现这个现象**（需要起游戏再走一轮注册）。
**上面那段是源码事实，不是复现结果。**

---

## 5. 两条前提已被证伪

| 前提 | 判决 | 判据 |
|---|---|---|
| **「现有工具面」** | **已证伪** | 把模型全部输出换成一条写死坐标的 dig，工具面活不过一夜 |
| **「一份系统提示词」** | 字面满足、**实质证伪** | `INSTRUCTIONS` = 25 token = 每 turn 的 **0.103%**；承载物是 `tools/list` 的 **24,264 token**；三个硬条件一个字都不在 `INSTRUCTIONS` 里 |

**而「现有工具面」被证伪的深层原因**：
`go_to` + A* 已经免掉了「怎么走过去」，**缺的是「去哪里」**——
`inspect_block` 只回答「一个给定格子」能不能站，**格子得有人给**。

**⇒ 修法不是补工具，是补那一层。而那一层在本仓存在过一次：`GoalPolicy` 1339 行，在 test 树，
它自己的 javadoc 写着「It is NOT evidence that a weak model plus a system prompt could produce the decision」。**

---

## 6. 三件只有 Owner 能解

1. **是否授权启动 Minecraft。** 第一环绕不过 `run-mcp.bat`，而你对「莫名其妙的启动minecraft」发过火。
2. **那个中继要不要进这个仓。** 它改变仓库职责边界（它服务的是「让模型玩这一局」，不是游戏本体的一部分）。
3. **模型后端用哪个。** `Runbook` 实测：**本地 RWKV 的 8091 没起，线上那个 402 Insufficient Balance**——
   **这一项与手册任何一步无关，但它挡住开一局。**

---

## 7. 一条还没人答的

**熔断 `IoSupervisor` 的 `TRIP_THRESHOLD=3` / `COOLDOWN_MS=30000`：
弱模型连续错 3 次 `act_set` 就失去唯一动作动词 30 秒，而它读不出自己被隔离。**

**「工具自己坏了」与「模型想错了」是两个失效类别，却共用一个门。**
`DecisionLayer` 正在查它的计数口径与最小修法。

---

## 8. 可复用的一条（本会话最该继承的）

> **一个更差的替身产生了同样可接受的输出。**

**今天的四个实例**：
`night_shelter` 的「从未测量过的夜」与「安全」在线上逐字节相同 ·
`getWorldTime()` 失败报成「时刻 0、白天」 ·
`guarantees.md` §1c 九行全绿而模型手上零工具 ·
**omp 的 30 天缓存让 registry 检查对着死游戏报绿**

**⇒ 四次都不是「守卫失灵」，是「一个更差的东西产生了同样可接受的输出」。**
**⇒ 而识别它的方法是同一句话：谁是这个检查的替身？**