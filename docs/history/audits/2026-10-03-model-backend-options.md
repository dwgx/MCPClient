# 模型后端：不是「不可用」，是「没起」 —— 而盘上就有那个弱模型

2026-10-03 深夜。主 agent 亲验（本节全部为实测，主 agent 自己跑的）。

---

## 0. 一句话

**`Runbook` 报的「模型后端不可用」只对了一半：
本地后端已配置好、权重在盘上、启动器有五个，缺的只是「起一下」。**
**而北极星要的「一个弱 LLM」字面就在那里：`RWKV-7 G1d 0.4B`。**

---

## 1. 本机没有任何本地推理后端在跑（实测）

```
netstat LISTENING 上 8091 / 11434 / 1234 / 1337 ：全部无监听
which ollama / llama-server / rwkv-server / lms ：全部不存在
```

**⇒ 所以「本地 RWKV 8091 没起」是对的。**

---

## 2. 但配置与权重都在（实测）

`~/.omp/profiles/modelbridge-iso/agent/models.yml`：
```
:3   # - 后端地址由 D:\LLM_Model\Start-RWKV*.cmd 决定（默认 8091）
:15  baseUrl: http://127.0.0.1:8091/v1
:20  name: RWKV-7 G1j 2.9B (local)
:28  name: RWKV-7 G1j 1.5B (local)
:36  name: RWKV-7 G1d 0.4B (local)
```

`D:\LLM_Model\` 实际内容（实测）：
```
Start-RWKV.cmd  Start-RWKV-Server.cmd  Start-RWKV-Chat.cmd  Stop-RWKV.cmd
Start-Llama-Model.cmd  Start-MiniMax-H3.cmd
RWKV7-G1d-0.4B/   RWKV7-G1j-1.5B/   RWKV7-G1j-2.9B/     ← 三档权重都在盘上
```

**⇒ 决策三从「用哪个后端」变成「起 `Start-RWKV.cmd`，选哪一档」。**

---

## 3. 而北极星要的「一个弱 LLM」字面存在

**`RWKV-7 G1d 0.4B` 是三档里最弱的。而 Owner 的北极星原文是
「一个弱 LLM + 一份系统提示词 + 现有工具面」。**

**⇒ 之前三轮用的是 `deepseek-v4.1-flash`（托管），而那个现在 402。**
**⇒ 换成本地 0.4B 反而更贴合北极星原文——它才是「弱模型」那个东西。**

### 3bis. 权重已核实（主 agent 亲验）

```
RWKV7-G1d-0.4B/rwkv7-g1d-0.4b-Q8_0.gguf                    501,498,016 字节
RWKV7-G1j-1.5B/rwkv7-g1j-1.5b-Q8_0.gguf                  1,693,647,008 字节
RWKV7-G1j-2.9B/rwkv7-g1j-2.9b-20260831-Q8_0.gguf         3,184,721,792 字节
RWKV7-G1j-2.9B/rwkv_vocab_v20230424.txt                          1,093,733 字节
```

**三档都是 GGUF、都是 Q8_0 量化、都能被 llama.cpp 直接加载。
⇒ 模型后端完全离线可用，三档，不需要下载任何东西。**

**⇒ 而三档的体积差是 500 MB / 1.7 GB / 3.2 GB——
这也给了「弱模型」一个可测的定义，而不只是形容词：0.4B 与 2.9B 差 6.4 倍。**

### 3ter. 仍然未核实的

- **`Start-RWKV.cmd` 实际能不能在这台机器上起起来**——
**我没有擅自启动，因为那是一个服务进程，而 Owner 对「莫名其妙的启动」发过火。**

---

## 4. 启动器自己的注释独立佐证了今晚的测量（实测，原文）

`D:\LLM_Model\Start-RWKV.cmd` 头部：
```
RWKV-7 local server (llama.cpp) - main launcher, ASCII only.
PER-SLOT CONTEXT = 131072 (128K), TOTAL = slots x 131072.
  omp's agent prefix (system prompt + tool schemas) measures ~14-16K tokens,
  so anything below ~64K per slot makes omp compact on EVERY turn
  (observed: 61 empty handoffs in 14 minutes at 32K/slot).
```

**三个独立的观察：**

1. **「omp 的 agent 前缀（系统提示词 + 工具 schema）约 14–16K token」——
   与今晚实测的 `tools/list` 24,264 token 同一量级**（两个独立来源：
   一个是当初配本地模型的人写的注释，一个���今天的 `SurfaceProbe` 测量）。

2. **「低于 ~64K per slot 会让 omp 每 turn 都 compact」——
   而 128K 的默认 slot 大小正是为了避开它。**

3. **「32K/slot 时 14 分钟里 61 次空交接」——
   这与三轮模型实验的静默超时是同一现象的另一个实例。**

---

## 5. 一条由本节引出的、必须先说的风险

**一个 0.4B 的模型，面对 24k token 的工具面。**

**这不是「弱模型能不能推理」的问题，是「它连那些工具说明能不能读完」的问题。**

**⇒ 所以用 0.4B 跑之前，应该先量一件事：
`omp` 在 `contextWindow` 较小的时候会不会提前 compact——
而那份启动器注释说 32K/slot 时「14 分钟 61 次空交接」。**

**⇒ 建议（不要现在做）：先起 2.9B 打通全链，确认「一局」能跑完，
再用同一套参数换 0.4B 跑第二局。**
**⇒ 因为如果 0.4B 的失败原因分不清是「它不会推理」还是「它读不完工具面」，
那一局的信息量是零。** 而本会话已经因为「测不到那个问题」付过一次学费（三轮实验）。

---

## 6. 本节未核实的

- **那三个权重目录里到底有没有可加载的权重文件**（我只列了目录名，没递归进去看文件）。
- **`Start-RWKV.cmd` 实际能不能在这台机器上起起来**（**不许擅自启动**——
  **Owner 对「莫名其妙的启动」发过火，而这次要起的是一个服务进程。**）