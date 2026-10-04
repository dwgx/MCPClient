# 那一夜的操作手册（2026-10-03）

**北极星（Owner 原话，唯一一句）：** 「一个弱 LLM + 一份系统提示词 + 现有工具面」能不能打完一局。

**本手册的边界。** 它把断点接到「跑起来」，不负责赢。里面每一条要么是我亲手跑出来的，要么标了
【未核实】。我没有启动过 Minecraft——Owner 对此发过火，本手册因此把所有「必须先有游戏在跑」的
环节都单独标了出来。

---

## 0. 结论先行：三件最容易白跑一小时的事

| # | 陷阱 | 答案 |
|---|---|---|
| 1 | 注册完发现被隔离一起关掉了 | **写到隔离档自己的 `agent/mcp.json`**，见第 1 节。实测通过 |
| 2 | 桥启动成功就以为通了 | 桥在没人听的端口上 2.1 秒后 exit 3，**成功启动什么都不能证明**，见第 2 节 |
| 3 | 问模型「你有什么工具」 | **模型会凭空编 10 个**。读注册表，见第 4 节。另有一个 30 天工具缓存会让注册表**假绿**，见第 4.3 节 |

---

## 1. 第一件事：注册写在哪一层（最容易出错的那一条）

### 1.1 核实过 schema 与源码，不是推测

| 事实 | 出处（我亲自打开过） |
|---|---|
| schema 的 `description` 自己写着适用于 `mcp.json`、`.mcp.json`、`.omp/mcp.json`、`~/.omp/agent/mcp.json` 四处 | `mcp-schema.json:5` |
| `serverConfig` 是 `oneOf`，只有 stdio / http / sse，**没有裸 TCP** | `mcp-schema.json:264-276` |
| 用户域路径 = `path.join(getAgentDir(), "mcp.json")` | `packages/utils/src/dirs.ts:1075-1078` |
| `getAgentDir()` **跟着当前 profile 走**：有 profile 时是 `~/.omp/profiles/<name>/agent`，没 profile 时才是 `~/.omp/agent` | `dirs.ts:341-348`（`getProfileConfigRoot` + `defaultAgent`）、`dirs.ts:566-570` |
| 项目域路径 = `<cwd>/.omp/mcp.json` 和 `<cwd>/.omp/.mcp.json`，**按工作目录**解析 | `discovery/builtin.ts:215-220` |
| `mcp.enableProjectConfig:false` 的过滤条件是 `enableProjectConfig \|\| server._source.level !== "project"` —— **它只杀 project 级** | `mcp/config.ts:133-134` |
| 隔离档的用户域文件**默认不存在**，这正是隔离的机制本身 | `ModelBridge.java:143-148`（"mcp.json is deliberately not copied, which is the entire mechanism"） |

### 1.2 答案：两者都不对，要写第三层

| 候选 | 结果 | 为什么 |
|---|---|---|
| `~/.omp/agent/mcp.json`（默认用户域） | **会被一起关掉** | `--profile` 不是「禁用用户域」，是**把用户域搬走了**。`getAgentDir()` 换了目录，那个文件根本不在读取路径上 |
| `D:/Project/MCPClient/.omp/mcp.json`（项目域） | **会被一起关掉** | overlay 的 `enableProjectConfig:false` 精确地只杀这一级（`config.ts:133-134`） |
| **`~/.omp/profiles/modelbridge-iso/agent/mcp.json`（隔离档自己的用户域）** | **活** | 它在 `--profile` 下**就是**用户域（level=`"user"`），所以 overlay 那条 project 级过滤碰不到它 |

### 1.3 为什么这个组合成立（一句话）

> `--profile` 换掉的是**用户域的位置**，不是用户域的生死；
> `enableProjectConfig:false` 换掉的是**项目域**，不是用户域。
> 把注册写进**隔离档自己的用户域**，两个开关都指着别处，它就同时躲开了两个。

这不是推论，是实测。三个测量，全部读 omp 自己的注册表。
**① 和 ② 在补测时都先清了 30 天工具缓存**（见 4.3），所以它们是活连接的结果，不是缓存顶上来的：

```
① 只加 --profile，不加 overlay：
   registry: 21 tool(s) total, 1 mcp__ registered, 1 mcp__ active
   mcpNames ['mcp__codegraph_explore']
   → 项目域的 codegraph 活了下来。--profile 单独不够。

② 加 --profile + overlay，尚未注册：
   registry: 20 tool(s) total, 0 mcp__ registered, 0 mcp__ active
   → 隔离成立。

③ 注册进隔离档 agent/mcp.json + overlay，游戏没起：
   Warning: MCP server "mcpclient" failed to connect: MCP subprocess closed stdout
   before responding; its tools are unavailable for this run.
   → omp 读到了注册（它认得 "mcpclient" 这个名字），只是连不上。

④ 注册进隔离档 agent/mcp.json + overlay + 25599 上有东西说话（用替身服务器，见第 6 节）：
   registry: 9 tool(s) total, 3 mcp__ registered, 3 mcp__ active
   activeNames ['mcp__mcpclient_night_box', 'mcp__mcpclient_night_health', 'mcp__mcpclient_night_shelter']
   → 隔离没吃掉注册，注册表里恰好只有这三个。
```

第 ③ 条是关键：**注册被读到了**。挂掉的是连接，不是配置。

### 1.4 一个必须知道的代价

`ModelBridge.ensureProfile()` 只在目标**不存在时**才复制 `agent.db` / `models.db` / `models.yml`，
并且**从不碰 `mcp.json`**（`ModelBridge.java:173-185`）。所以我们写进隔离档的 `mcp.json` 不会被
它覆盖，重跑 provisioning 是安全的。反过来，这个文件也是隔离成立的唯一原因——
**谁要是往 `~/.omp/agent/mcp.json` 补一份，整个隔离就漏了。**

---

## 2. 第二件事：`mcpServers` 那一行怎么写

### 2.1 字段核实（逐个对着 schema）

| 字段 | schema 位置 | 结论 |
|---|---|---|
| `command` | `mcp-schema.json:163-167` | **required**，`minLength: 1`。`"python"` |
| `args` | `:168-174` | `string[]`，可省。这里要给脚本路径 + `--host` + `--port` |
| `type` | `:158-162` | `enum:["stdio"]`，**省略时也是 stdio**。写上更清楚 |
| `cwd` | `:179-182` | 可选。桥自己不开文件，不需要 |
| `env` | `:175-178` | 可选 |
| `timeout` | `:124-126` | 毫秒，**0 = 关掉客户端侧超时**。见下 |
| `url` | `:184-186` | `not: {required:["url"]}` —— stdio 分支里出现 `url` 直接不合法 |

顶层还有 `disabledServers` / `enabledServers`（`:23-40`），`disabledServers` 是**用户级黑名单、
优先级最高**（`:25`）。空的即可。

### 2.2 可直接用的片段

写入 **`C:\Users\dwgx1\.omp\profiles\modelbridge-iso\agent\mcp.json`**：

```json
{
  "$schema": "https://raw.githubusercontent.com/can1357/oh-my-pi/main/packages/coding-agent/src/config/mcp-schema.json",
  "mcpServers": {
    "mcpclient": {
      "type": "stdio",
      "command": "python",
      "args": [
        "D:\\Project\\MCPClient\\scripts\\mcp_stdio_tcp_bridge.py",
        "--host", "127.0.0.1",
        "--port", "25599"
      ],
      "timeout": 0
    }
  },
  "disabledServers": []
}
```

- `timeout: 0` 是有意的：`night_shelter` 在天亮那一 tick 要过游戏线程，客户端侧默认超时可能把
  一次合法调用掐成失败。关掉它，失败就只会来自游戏本身。
- 服务器名 `mcpclient` 要匹配 `^[a-zA-Z0-9_.-]{1,100}$`（`:16-18`）。
- 注册后 omp 里的工具名是 `mcp__mcpclient_night_shelter` 等
  （`mcp/tool-bridge.ts:427`，`mcp__${server}_${tool}`）。**注意是单下划线。**

> Windows 上如果 `python` 不在 PATH（我这台机器上它在），把 `command` 换成完整的
> `python.exe` 路径。schema 只要求一个可执行字符串。

### 2.3 可运行的确认方式 —— 以及为什么它不是「桥起来了」

**桥启动成功不是确认。** 端口没人听时它 2.1 秒后 exit 3 并点名 host:port。刚跑出来的实测：

```
test_refuses_when_nothing_listening
  exit 3 after 2.100s (OS refusal baseline 2.063s, --connect-timeout 30s); stdout empty
  stderr| [mcp-stdio-tcp-bridge] cannot connect to 127.0.0.1:63800: [WinError 10061] No connection could be made because the target machine actively refused it
  stderr| [mcp-stdio-tcp-bridge] is the game running? (scripts/run-mcp.bat starts it); nothing is listening on 127.0.0.1:63800
```

所以顺序是硬的：

1. **先**让游戏在 25599 上说话（`scripts\run-mcp.bat`）。
2. **再**起 omp 会话。

反过来的话，桥已经 exit 3，omp 这一局就再也连不上了（不会自动重连到新进程）。

**真正的确认是游戏先起来了**：

```
python scripts/runbook_preflight.py wire
```

它直接对 25599 发 `initialize` + `tools/list`，不看任何缓存。游戏没起时它说：

```
FAIL  nothing is listening on 127.0.0.1:25599 -- [WinError 10061] No connection could be made because the target machine actively refused it
       The game is not up. scripts\run-mcp.bat starts it, and it needs Owner to authorize the launch.
RESULT: FAIL
```

---

## 3. 第三件事：逐步手册

标注含义：
**现在就能跑** = 不需要任何授权 ·
**缺两个 jar** = `scripts\build-jars.bat` 一步 ·
**卡在 Owner 授权启动游戏** = 需要他点头 ·
**卡在别的** = 说清卡在什么

---

### 第 0 步 · 产物在不在 — **缺两个 jar**

```bat
python scripts\runbook_preflight.py artifacts
```

刚跑出来的结果：

```
FAIL  client\target\MCP-1.8.9.jar -- missing -- scripts\build-jars.bat
FAIL  core\target\core-1.8.9-all.jar -- missing -- scripts\build-jars.bat
PASS  D:\Project\MCPClient\test_run
PASS  D:\Project\MCPClient\test_run\assets\indexes\1.8.json
PASS  D:\Project\MCPClient\scripts\mcp_stdio_tcp_bridge.py
PASS  C:\Users\dwgx1\.omp\profiles\modelbridge-iso\agent\mcp.json
PASS  test_run/saves -- 11 world save(s)
RESULT: FAIL
```

**只有两个 jar 缺**，其余齐备（`test_run/assets/` 已填充，存档 11 个）。

→ 跑 `scripts\build-jars.bat`，然后重跑上面这条命令直到 `RESULT: PASS`。

> 注意：资产索引的真实路径是 `test_run/assets/indexes/1.8.json`，**不是**
> `test_run/indexes/1.8.json`——`run-mcp.bat:107` 传的是 `--assetsDir assets` 且 cwd 是
> `test_run`。我一开始按后者写检查，报了个假 FAIL，已经改对。

---

### 第 1 步 · 确认桥本身没坏 — **现在就能跑**

```bat
python scripts\test_mcp_stdio_tcp_bridge.py
```

刚跑出来的结果：`6/6 passed`，6.67 秒。这一步不需要游戏，也不需要 jar。

---

### 第 2 步 · 确认注册没被隔离掉 — **现在就能跑**

```bat
python scripts\runbook_preflight.py registry
```

**注册之前**跑它，看到的是干净的 0（这是隔离成立的证据）。
**注册之后 + 游戏没起**跑它，看到 `FAIL ... missing: mcp__mcpclient_night_*`——
这**不是**注册坏了，是桥 exit 3 了。第 3 步会解释怎么区分。

---

### 第 3 步 · 起游戏 — **卡在 Owner 授权启动游戏**

```bat
scripts\run-mcp.bat
```

- `run-mcp.bat:60-71` 三个存在性检查（两个 jar + `test_run`）过了才会往下走。
- `:103-107` 用 `-javaagent:core-1.8.9-all.jar` + `net.minecraft.client.main.Main` 起本体。
- `SocketTransportServer` 在里面开 MCP，监听 `127.0.0.1:25599`，
  成功时 stderr 打印 `[MCP Core] socket transport listening on 127.0.0.1:25599`
  （`SocketTransportServer.java:118`）。**看到这一行才算起来。**
- 脚本末尾有 `pause`，JVM 的退出码才是脚本的退出码——别把 pause 的 0 当成成功。

**Owner 必须先点头。** 我没有启动过它，本手册也没有假装我启动过。

→ 起来之后立刻：

```bat
python scripts\runbook_preflight.py wire
```

`RESULT: PASS` 才继续。

---

### 第 4 步 · 模型能用的工具面核对 — **现在就能跑**（但要第 3 步先过）

```bat
python scripts\runbook_preflight.py registry
```

游戏起来后应该看到（这是我拿替身服务器实测到的形状）：

```
== registry (isolated spawn, --no-tools, cache purged) ==
  purged N cached mcp_tools row(s) from the profile's agent.db
  registry: 9 tool(s) total, 3 mcp__ registered, 3 mcp__ active
  active mcp__ tools: mcp__mcpclient_night_box, mcp__mcpclient_night_health, mcp__mcpclient_night_shelter
  PASS  all three north-star rulers are ACTIVE
  PASS  nothing else got through the isolation
  PASS  --no-tools closed the built-in surface
RESULT: PASS
```

`activeNames` **恰好只有这三个**。这才是那一夜该有的工具面：一个终端都没有，一个浏览器都没有，
三个只读量尺。

---

### 第 5 步 · 开一局 — **现在就能跑**（要 Owner 选模型，见第 6 节）

把 `scripts/runbook_preflight.py registry` 里那条 spawn 命令原样抄过来，只把末尾的
`/tooltable` 换成真正的提示词：

```
omp -p --mode json --no-session ^
  --profile modelbridge-iso ^
  --config <overlay> ^
  --no-tools ^
  --model <Owner 指定的弱模型> ^
  --thinking off ^
  --max-time <整夜的长度> ^
  --system-prompt <提示词> ^
  @<transcript 文件>
```

- overlay 就是第 2 步那段 `mcp:\n  enableProjectConfig: false\n`，脚本会自己写到
  `.ai-notes/scratch/runbook-overlay.yml`。
- `--max-time` 必须够长到跨过一整夜。Minecraft 一天 24000 tick ≈ 20 分钟。
- `--auto-approve` **故意不给**：print 模式没有审批人，一个工具调用会永远等下去；
  而给审批就等于让模型开这台机器。见 `ModelBridge.java:326-329`。
- `@file` 而不是把整段历史塞进 argv：Windows 命令行 32767 字符上限，第 10 轮就会撞上。

---

### 第 6 步 · 模型能不能用 — **卡在别的：两个后端现在都不通**

这是我这轮顺手撞见的，和本手册无关但会挡住第 5 步：

```
$ python -c "urllib.request.urlopen('http://127.0.0.1:8091/v1/models')"
8091 unreachable: URLError [WinError 10061]
```

- 隔离档 `models.yml` 里只有三个**本地 RWKV** 模型，后端 `http://127.0.0.1:8091/v1`，
  **现在没起**（要 `D:\LLM_Model\Start-RWKV*.cmd`，三个尺寸共用一个端口，同时只能跑一个）。
- 线上那个也断了：

```
"errorStatus":402,"errorMessage":"402 Insufficient Balance (request_id: 42801554-9f66-...)"
```

**所以第 5 步现在跑不起来**，和本手册的任何一步都无关。要么起本地 RWKV，要么充值。
这两件都需要 Owner 定，我不动。

---

## 4. 第四件事：三条前置检查

> **铁律：绝不问模型它有什么工具。**
> 在 `--no-tools` 下问它「列出你的工具」，它凭空答出 10 个（bash/read/write/edit/…），
> 而那一局它一个工具都没有。所有工具面检查都读 omp 自己的注册表。

### 4.0 读法（照抄 `SingleCallProbe.HOOK_SOURCE`）

我把它落成了独立文件 **`scripts/tooltable.ts`**——形状逐字抄自
`core/src/test/java/net/marcloud/mcp/core/eval/SingleCallProbe.java:147-181`：

```ts
import { writeFileSync } from "node:fs";
interface ToolInfo { readonly name: string; readonly mcpServerName?: string | null; }
interface ToolTableApi {
  registerCommand(name: string, spec: { description: string; handler(): Promise<void> }): void;
  getAllTools(): readonly ToolInfo[];
  getActiveTools?(): readonly string[];
}
export default function toolTable(pi: unknown): void {
  const api = pi as ToolTableApi;
  api.registerCommand("tooltable", {
    description: "Dump the live tool registry as JSON and exit",
    async handler() {
      const active = new Set<string>(api.getActiveTools?.() ?? []);
      const rows = (api.getAllTools() ?? []).map((t) => ({
        name: t.name,
        active: active.has(t.name),
        mcp: t.name.startsWith("mcp__") || (t.mcpServerName ?? "") !== "",
      }));
      const out = process.env.TOOLTABLE_OUT;
      if (out) {
        writeFileSync(out, JSON.stringify({
          total: rows.length,
          mcpTotal: rows.filter((r) => r.mcp).length,
          mcpActive: rows.filter((r) => r.mcp && r.active).length,
          names: rows.map((r) => r.name).sort(),
          mcpNames: rows.filter((r) => r.mcp).map((r) => r.name).sort(),
          activeNames: rows.filter((r) => r.active).map((r) => r.name).sort(),
        }, null, 2), "utf8");
      }
      process.exit(0);
    },
  });
}
```

关键点：它**只注册一个命令、只读、然后 `process.exit(0)`**。不注册工具、不调工具、
**不发任何 provider 请求**，所以跑它不烧 token，1 秒内出结果。
判定读 `TOOLTABLE_OUT` 指的那个 JSON 文件，**不是读控制台**。

手工跑法：

```bat
set TOOLTABLE_OUT=D:\tmp\tooltable.json
omp -p --mode json --no-session --profile modelbridge-iso ^
  --config D:\Project\MCPClient\.ai-notes\scratch\runbook-overlay.yml ^
  --no-tools --hook D:\Project\MCPClient\scripts\tooltable.ts ^
  --max-time 150s /tooltable
```

### 4.1 三条检查，各一条命令

| # | 命令 | 判什么 | 该看到 |
|---|---|---|---|
| 1 | `python scripts\runbook_preflight.py artifacts` | 两个 jar、`test_run`、资产索引、桥脚本、隔离档的 `mcp.json`、存档数 | 全 PASS |
| 2 | `python scripts\runbook_preflight.py wire` | **游戏**在 25599 上说 MCP，`tools/list` 里有 `night_shelter` | `RESULT: PASS` |
| 3 | `python scripts\runbook_preflight.py registry` | 隔离下的注册表里**恰好**三个 `mcp__mcpclient_night_*` 是 ACTIVE，且没有别的 | `RESULT: PASS` |

**顺序有因果，不能换**：先 2 再 3。桥在没人听的端口上 2.1 秒 exit 3，所以对着死游戏跑第 3 条，
`mcpActive` 是 0——那是桥死了，不是注册错了。

### 4.2 三条都基于注册表 / 线路，一条都不问模型

- 第 1 条读磁盘。
- 第 2 条读**游戏自己的** MCP `tools/list`（走 25599，不走 omp）。
- 第 3 条读 **omp 自己的** tool registry（走 4.0 的 hook）。

模型一次都没被问。

### 4.3 我自己踩到的假绿，这条比上面三条都重要

第一版第 3 条检查，在**游戏已经停了**的情况下报：

```
registry: 9 tool(s) total, 3 mcp__ registered, 3 mcp__ active
PASS  all three north-star rulers are ACTIVE
RESULT: PASS
```

**这是假绿。** 查下去：omp 把每个 MCP server 的工具清单缓存在 profile 的 `agent.db` 里，
key 是 `mcp_tools:<server>`，**TTL 30 天**（`src/mcp/tool-cache.ts:11-12`），
连接还没建好时 `MCPManager` 就拿缓存顶上（`manager.ts:872-883`、`:908-913`）。
我在隔离档的库里亲眼看到这一行：

```
mcp_tools:mcpclient -> {"version":1,"configHash":"d76b1a...","tools":[{"name":"night_shelter","description":"STUB. ...
```

——那是替身服务器那一轮写进去的，之后游戏死了它还在，照样报 PASS。
**所以 `check_registry` 现在先删掉 `agent.db` 里所有 `mcp_tools:%` 行再 spawn**，
而且只删这一个 profile、这一个前缀（那个库同时存着 `auth_credentials`，不能乱删）。
删完重跑，游戏仍然没起，它诚实地报：

```
  purged 11 cached mcp_tools row(s) from the profile's agent.db
  registry: 6 tool(s) total, 0 mcp__ registered, 0 mcp__ active
  FAIL  all three north-star rulers are ACTIVE -- missing: mcp__mcpclient_night_box, ...
RESULT: FAIL
```

**一个对着死游戏还绿的检查，比一个红的更糟。** 这是今晚最容易白跑一小时的地方，
比注册写错那一层还要阴——注册写错会报错，这个不报错。

---

## 5. 第五件事：跑完怎么读结果

### 5.0 三个硬条件各自的读数

三个都是**注册表里的只读 MCP 工具**，全部在同一个 tick 缝上填账本，
**不是**问模型「你成功了吗」。三个共用同一个夜窗（`NightEnclosure` 的 24000-tick 日循环
加时钟回拨检测，`NightShelter` / `NightHealth` / `DawnChestRegion` 各抄一份）。
**必须同一时刻读**，否则你在把不同窗口的数字凑成一句结论
（`NightHealth.java:64-67`、`DawnChestRegion.java:91-95` 都写了这条理由）。

| 硬条件 | 工具 | 读哪个键 | 判据 |
|---|---|---|---|
| 有庇护所 | `mcp__mcpclient_night_shelter` | `shelteredSoFar` | `true`。它 = `measured && openSamples == 0` |
| 整夜 hp 从未低于 18 | `mcp__mcpclient_night_health` | `floorHeld`，配 `minHealth` / `firstBelowFloorTick` | `floorHeld == true`，且 `minHealth >= northStarFloor`（`northStarFloor` 字段会回 18.0，`SurvivalDamage.java:71`） |
| 天亮后箱子还立着 | `mcp__mcpclient_night_box` | `standingAtDawn` / `someStandingAtDawn` | `true` |

**读法上的两个坑，踩了就白读：**

1. **`measuredOnce` 必须第一个读，它是唯一能区分三种状态的位。**
   `night_shelter` 的 payload 有 17 个键（`ShelterTools.java:151-167`），16 个被它门控；
   `night_health` 18 个（`NightRulerTools.java:130-147`）、`night_box` 19 个
   （`:218-236`），同理。这个门控本身是被测试钉住的缺陷：原始形状是「16 个里有 15 个被
   门控」，三种需要相反答案的状态因此被压成了字节相同的三份
   （`TheShelterPayloadSaysWhetherItHasEverMeasuredTest.java:29-32`）。
2. **天亮后第一个白昼 tick 会把整夜读数清零**——那恰恰是不采样的那一 tick
   （`DawnChestRegion.java:480-485` 写了这条）。所以：
   - 夜里中段读 → 看趋势（`exposedNow`、`minHealth`、`firstBelowFloorTick`）
   - **天亮那一刻读 → 看结论**
   - 天亮之后读 → `measured:false` 是「没测」，不是「测砸了」。**别把它读成失败。**

三个 payload 都带一个 `fact` 字段，是一句人话判词（`HELD` / `BROKEN` /
`ticks ARE arriving` 之类）。**它是给人看的，判据是那几个数字**——以数字为准。

### 5.2 产物落在哪、什么形状

`ModelRound.writeArtifacts`（`ModelRound.java:475-506`）已经定了形状，沿用它：

- `D:\Project\MCPClient\.ai-notes\docs\audits\<时间戳>-model-round.md`
- `D:\Project\MCPClient\.ai-notes\docs\audits\<时间戳>-model-round.json`

JSON 顶层键（照抄 `ModelRound.java:486-505`）：

```
stamp / model / verdict / tool_surface / edge_model / edge_control_goalpolicy
cost{calls, fresh_input_tokens, output_tokens, prompt_tokens_incl_cache,
     cache_read_tokens, usd, wall_millis}
stand_in_observation_boundary / scaffold_input_tokens_per_call / rerun_command
```

**这一局要在上面基础上加三个键**，因为它们才是北极星，而现在的形状里没有：

```json
"north_star": {
  "night_shelter": { "measuredOnce": true, "shelteredSoFar": true, "openSamples": 0,
                     "firstOpenTick": -1, "worldTime": 24120, "fact": "..." },
  "night_health":  { "measuredOnce": true, "floorHeld": true,
                     "minHealth": 20.0, "northStarFloor": 18.0,
                     "firstBelowFloorTick": -1, "healthDrops": 0, "fact": "..." },
  "night_box":     { "measuredOnce": true, "standingAtDawn": true,
                     "chestsSeen": 1, "firstAbsentTick": -1, "fact": "..." }
}
```

抄下来的必须是**原样 payload**，不是人转述的结论。三个必须带 `measuredOnce`，
否则「测了没有」这一位就丢了。

落盘路径要用 `ModelRound.repoRoot()`（`:519-528`）往上找 `.git` 的那种写法，**不要**用相对
`.ai-notes`——Surefire 的工作目录是模块目录，相对路径会落到 `core/.ai-notes/`，
在「`.ai-notes` 不入库」这个仓库里造出第二份静默分叉的控制面。

### 5.3 「一局的证据」应该长什么样

`docs/history/audits/` 现在 130+ 份**全是审计**。这个数字在动——别的 worker 也在往同一个
目录里写，我写下这段时数到 131，几分钟后再数已经不一样了，所以别拿它当基线。
它们回答的是
「这个设计对不对」「这个测试有没有牙齿」「这波改动漏了什么」。
**审计的形状是「一个问题 + 一个判定 + 理由」。**

一局的证据不是那个形状。它的形状是：

1. **一条完整回合序列，原样。** 每一轮：世界看到了什么 / 模型说了什么 / 生产 handler 回了什么。
   不是摘要，不是「模型尝试了 12 次」。`ModelRound.report` 的转录块就是这个形状
   （`:559-561`）。
2. **三个硬条件的原始读数，带 `measuredOnce`。** 见 5.2。
3. **工具面快照。** 那一局的注册表：几个工具、哪些 ACTIVE、叫什么。脱离这一条，
   后面所有结论都无法复现——工具面变了就不是同一局了。
4. **成本与墙钟。** `cost{...}`。没有这个，「弱模型打完一局」这句话没有分母。
5. **一条能重跑的命令，逐字。** `rerun_command`。
6. **一个可以被推翻的判定。** `verdictOf` 允许结论是「FALSIFIED」（`:536-544`）。
   一局的证据如果只会说「成了」，那它不是证据，是贺词。

**一句话区分**：审计问「这行代码对吗」，一局的证据问「那一夜到底发生了什么」。
前者可以没有时间戳，后者没有时间戳就没有意义——它是**一次性的**，只对那一局成立。

---

## 6. 这一轮我实际跑过的东西（不是推论）

| 命令 | 结果 |
|---|---|
| `python scripts/test_mcp_stdio_tcp_bridge.py` | **6/6 passed**，6.67s |
| `python scripts/test_probe_liveness.py` | **5 tests OK**，0.15s |
| `python scripts/runbook_preflight.py artifacts` | FAIL，**只缺两个 jar** |
| `python scripts/runbook_preflight.py wire`（无游戏） | FAIL，10061，诚实 |
| `python scripts/runbook_preflight.py wire`（替身服务器） | **PASS**，3 个工具 |
| `python scripts/runbook_preflight.py registry` × 6 种组合 | 见 1.3 与 4.3，全部实跑（含清缓存后的补测） |
| `git status --porcelain` | 65 项（开工时；不 commit） |

**替身服务器。** 为了在不启动 Minecraft 的前提下把整条线验通，我写了
`scripts/fake-game-mcp.py`：在 25599 上说同一套 newline-delimited JSON-RPC，
`tools/list` 回 `night_shelter` / `night_health` / `night_box`。
它证明的是**线路**（配置被读 → 桥被 spawn → 工具进了 omp 的注册表），
**它不能证明游戏能用**。用它跑出来的 PASS，换成真游戏还要再验一遍。

**没跑过的：** Minecraft 本体、整夜那一局、`mvn test`、`mvnw` 任何编译。
两个 jar 缺、且有别的 worker 在改 `core`，本手册不碰 maven。

---

## 7. 新增/改动的文件

| 文件 | 是什么 |
|---|---|
| `scripts/tooltable.ts` | 只读注册表 dump 的 hook，形状抄自 `SingleCallProbe.HOOK_SOURCE` |
| `scripts/runbook_preflight.py` | 三条前置检查，各一条命令 |
| `scripts/fake-game-mcp.py` | 25599 上的替身服务器，供无授权演练 |
| `.ai-notes/scratch/runbook-overlay.yml` | 隔离 overlay，由 `runbook_preflight.py registry` 自动生成 |
| `~/.omp/profiles/modelbridge-iso/agent/mcp.json` | **注册本体**（不是 `~/.omp/agent/mcp.json`） |

未 commit。`core/` / `client/` / `docs/` / `~/.omp/agent/mcp.json` 一律没动。

---

## 8. 提交簇提案

原提案落在 `.ai-notes/docs/project/commit-clusters.md` 的 `runbook` 一节（**未入仓**：那是一次
已完成的分组提案）。结论保留在这里，不指向仓外：
3 个新脚本 + 本文件 1 个 commit，不与任何 `core/` 改动混在一起。
