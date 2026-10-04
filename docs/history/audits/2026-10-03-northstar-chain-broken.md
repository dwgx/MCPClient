# 北极星链条：从未接通过一次 —— 逐环核实

2026-10-03。主 agent 亲验每一环。**这是本会话的中心结论。**

---

## 0. 一句话

**北极星是「一个弱 LLM + 一份系统提示词 + **现有工具面**」能不能打完一局。
而三轮模型实验里，那个模型从来没有过这个项目的任何一个工具——
它有的是本机的终端驱动和浏览器。所以「弱模型能不能打完一局」至今没有任何有效数据。**

---

## 1. 五环链条与它断在哪

| 环 | 事实 | 证据 |
|---|---|---|
| 1 | `scripts/run-mcp.bat` **启动游戏本体**：`-javaagent:%CORE_JAR%` + `net.minecraft.client.main.Main`，cwd 在 `test_run` | `run-mcp.bat:103-107`、`:101` |
| 2 | 游戏的 kernel 在其中开一个 MCP server，**监听 127.0.0.1:25599（socket，不是 stdio）** | `run-mcp.bat:7`、`SocketTransportServer.java:35` `DEFAULT_PORT = 25599` |

## 2.0 判决记录本身没错，错的是解读 —— 而证据就在它自己的 JSON 里

`2026-10-03-model-round-model-round.json`（主 agent 亲读原文）：

```
"verdict":"FALSIFIED AT THE TOOL SURFACE
"registered_tool_that_performs_a_craft":null
"model_can_reach_a_chest_with_registered_tools":false
```

**注意第三个键：它是实验自己算出来并写进产物的。**
**「模型能用注册工具走到一个箱子」——false。**

**当时它被读成「工具面上没有一条到箱子的路」。**
**而按本文第1 节的环 4，真正的读法是「模型压根没接到那个工具面」。**

**两条读法差极远：**

- 前者 ⇒ 该扩建工具面（做了几周的正是这件事）
- 后者 ⇒ 该把工具面**接上**（一件配置加一个中继的事）

**同一个 `false`，两件相反的工作。而产物里没有任何一个字段能区分它们——
因为这个实验从来没有测过「模型看得见什么」。**

**所以这不是「结论错了」，是「那个实验测不到那个问题」。**
**而这正是 `2026-10-03-model-rounds-invalid.md` 第6 节的结论在判决层面的具体形态。**
| 3 | 代码自己写明为什么不是 stdio | **`McpCore.java:230`**：「server on 127.0.0.1:25599 (**not stdio — the game owns the console**)」 |
| 4 | **本项目从未被注册成 omp 的 MCP server** | **`~/.omp/agent/mcp.json` 的 `mcpServers` 是 `{}`**，全文件对 `mcpclient` / `marcloud` 命中 **0** |
| 5 | 于是 spawned 的 `omp` 拿到的是插件提供的桥 | 实测在调 `mcp__smartcli_*` / `mcp__chrome_devtools_*` / `mcp__codegraph_explore` |

**断点在环 4。** 而环 1-3 全部就绪。

---

## 4.5 环 4 的阻塞项已解除（2026-10-03 深夜，`McpBridge` 交付并实测）

**桥已存在并通过 6/6 验收**：`scripts/mcp_stdio_tcp_bridge.py`（279 行）
与 `scripts/test_mcp_stdio_tcp_bridge.py`（517 行）。

**它就是「读一行、写一行」的字节中继，没有协议翻译**——正如第 4 节预判的。

| 验收 | 实测 |
|---|---|
| initialize 往返逐字节 | 请求 138 B / 响应 159 B，**两侧逐字节相同**；非规范 JSON 间距与 UTF-8（café-中文）保留 |
| 反向：server 推 → stdout | 5 条非请求驱动的行在 **stdin 全程空闲**时送达 |
| **两个方向同时活跃** | server→client 400 行、client→server 50 行**都完成**，id 0..49 按序 |
| 端口没人听时立刻退出 | **exit 3，2.128 秒**（OS 拒绝基线 2.032 秒，`--connect-timeout` 是 30 秒）；stdout 空；stderr 点名 host:port 与下一步 |
| 4KB+ 单行不截断 | 请求 65,633 B、响应 131,136 B，**两侧逐字节相同** |
| stdout 只承载协议字节 | 活会话 stdout 42 字节 == 协议交换本身，6 条诊断在 stderr；失败运行 stdout 0 字节 |

**半双工不自锁是设计出来的，不是碰巧的**：
`mcp_stdio_tcp_bridge.py:257-260` 两条 daemon 线程（`socket->stdout` 与 `stdin->socket`）各跑一个方向。

**⇒ 环 4 现在只差一步「把桥注册进 `mcp.json` 的 `mcpServers`」——
而那一步被两个问题挡着，两个都需要 Owner：**

1. **桥要不要进这个仓**（它改变仓库职责边界——它服务的是「让模型玩这一局」，不是游戏本体的一部分）
2. **在授权启动游戏之前，注册了也没有对端可连**

**而本文件第 1 节的结论不变：在那两步完成之前，北极星仍然没有被测过。**
**桥修的是环 4，不是环 5——环 5（隔离）由 `McpIsolation` 在并行处理，
它的验收标准是「子进程工具表里零个 `mcp__`」，不是「跑通了」。**

---

## 2. 环 4 的两个后果

### 后果一：被测对象从来没有过工具面

**第一局那个判决的原文是「`FALSIFIED AT THE TOOL SURFACE`」（在工具面上被证伪）。**

**而实测那个模型在同时开着 Chrome DevTools、读网络请求、列标签页。**

**它拿不到箱子，不是因为工具面不够，而是因为它从来就没有本项目的工具。**

### 后果二：那些「工具面」数字是进程内算的，不是模型看见的

第三局日志：
```
surface: substrate can craft=true, a registered tool that performs a craft=act_set(interact.kind="craft"), model can reach a chest=true
```

**那是 `modelSurface()` 在同一个 JVM 里算出来的诊断。**
**它证明本项目内部工具齐全，不证明模型看得见它们。**
**——这两件事在本会话之前被当成同一件事。**

---

## 3. 这是第 15 个失败形状的一个新变体，且方向相反

`failure-shapes.md` §15 说的是「**被文档声称的能力而其生产者缺席**」。

**这次的形状是它的镜像：「被诊断确认存在的能力，而它的消费者从未接通」。**

- 生产者在（`McpCore.registerBuiltins` 真的注册了几十个工具）
- 诊断也真的绿（`modelSurface().names()` 真的含它们）
- **而链路那一段是空的**

**后果方向与前 15 个相反**：前 15 个让模型**放弃**一条路；
**这一个让模型拥有一整张它从没见过的地图，而且没有人告诉它。**

---

## 4. 一个必须由你判断的架构问题

**环 2 与环 4 在协议层对不上。**

- 本项目提供的是**裸 TCP socket 上的 MCP**（25599）
- `mcp.json` 的 `$schema` 指向 oh-my-pi 的 MCP schema —— **需要确认它支持哪种传输**

**如果它只支持 stdio 与 HTTP/SSE，那么环 4 就需要一个桥**
（一个把 stdio 上的 MCP 转发到 127.0.0.1:25599 的小进程）。

**而那个桥本身会是一个新组件——它不在这个仓里，现在也不存在。**

**这是一个真实的缺口，不是配置项。**

---

## 5. 要真的跑通那一夜，顺序是这样（每一步都还没做过）

1. **启动游戏**（`run-mcp.bat`）——**需要你授权**，因为你明确发过火「莫名其妙的启动minecraft」
2. **起桥**：把 stdio MCP 转发到 `127.0.0.1:25599`，注册进 `mcp.json` 的 `mcpServers`
3. **隔离**：让 spawned 的 `omp` **除了本项目的工具面以外什么都拿不到**
   （现状是它拿到了 `smartcli` 终端与 `chrome_devtools` 浏览器 —— `omp --help` 的原文是
   「`--no-tools` Disable all **built-in** tools」，它只关内置工具）
4. **再跑整局**，且这次的三硬条件由活客户端重读

**第 3 步的验收标准不能是「跑通了」，必须是「工具表里零个 `mcp__` 桥」——
因为旧探针那个「TRANSPORT: OK / 5.2 秒」也没隔离，它不证明隔离成立。**

---

## 6. 已落盘的相邻产物

| 文件 | 内容 |
|---|---|
| `2026-10-03-model-rounds-invalid.md` | 三轮无效的证据（62 个 temp 文件的普查索引） |
| `2026-10-03-northstar-instruments.md` | 三把尺子的仪器状态（哪个在 main、哪个只在 test 树） |
| `2026-10-03-two-rulers-feasibility.md` | 血量与箱子各自能不能接活客户端，含 1.8.9 的协议层上限 |
| `2026-10-03-survival-production-wiring.md` | 血量仪器的正确形状（对 S06 流取 min） |

**五份合起来才是「北极星为什么还没跑过」的完整回答。**
**而它们的结论一致：链条不是难，是从来没接通过。**