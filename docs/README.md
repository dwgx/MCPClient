# docs/ — 索引

进 git 的是产品设计与验证方法。AI 交接 / session 笔记不进 git
(2026-08-20 已从 `feat/dwm-qml4j` 历史拿掉),活进度(HEAD SHA、测试条数)在工作站 `.ai-notes/`。

> **2026-08-22:** GitHub 默认分支 `main` 已快进到产品 tip,与 `mcp-core` /
> `feat/dwm-qml4j` 同 SHA。Mac qml4j GuiScreen **就是**产品 UI。日常开发分支名仍是
> `mcp-core`;合主线只用 `--ff-only`。细节 `branch-topology.md`。

> **读码走 codegraph(铁律⑥)。** 用法与盲区见 `codegraph.md`。

---

## 0. 接手路径

**内核 / 游戏行动:**

1. `agency/command-to-action.md` — 已建好什么、关键路径、Fork。逐节看订正横幅。
2. `branch-topology.md` — 分支关系。GitHub `main` = 产品主线(已含 qml4j dwm)。
3. `debugging.md` — 排查前必读,§10 真机纪律。

**UI (dwm):** 仓库根 `dwm/README.md`(活架构,qml4j 底层) → `dwm/live-verification.md` →
`debugging.md` → `dwm/fluent-spec.md` / `dwm/key-ceremony.md`。
dwm **不是**已完结:底层钉死,产品页还可以写。

**审计:** 2026-09-30 对 v1.0.0 干净基线(`5eec5b9`)→ HEAD 做过一次全量审计,
报告在工作站 `.ai-notes/docs/audits/2026-09-30-base-to-head.md`(gitignored)。
结论一句话:**安全脊柱是干净的(找不到任何让未签补丁 arm 的路径),缺陷集中在
"新写的功能自己骗自己"** —— 2 条 CRITICAL 都在 `drivers/plan/`:搭桥的"确认"谓词把
水/岩浆当成已铺好的地板,以及规划器把岩浆当成可通行空间(同一个问题,读侧 `LocalGrid`
已经标了 `walk = -2`,两边答案矛盾)。改任何 `plan/` 之前先读那两条。

---

## 1. 全部文档

### 顶层

| 文档 | 内容 | 何时读 |
|---|---|---|
| `branch-topology.md` | 分支关系、环境、怎么跑 | 接手 |
| `codegraph.md` | 本机读码入口、盲区 | 读码前 |
| `debugging.md` | 调试手段 + 三个能杀客户端的坑 + §10 | 排查前 |

### agency(一条命令怎么变成游戏里的动作)

| 文档 | 内容 |
|---|---|
| `agency/command-to-action.md` | §1 已建好什么(别重建) · §4 关键路径 · §5 Fork |
| `agency/guarantees.md` | 38 条保证的 `file:line` + **强制它的测试类**(每个 `file:line` 都亲自读过,查不到就写 unenforced)· 五个测量仪器各自抓什么/瞎什么 · §1e 时钟:客户端曾经**永远**回答「白天」,dusk 13807 / dawn 22193 实测 · §0.1 测试数(1883)的分解(哪几条是实测、哪几条是历史) · **surefire 收不到测试的坑**(实测 323/300/23) · §3.4 `.lwjgl/` 等无产出物的忽略项 · 仍然测不到的四条北极星判据 · 「不要做这个」清单 |

会话交接不进 git。工作站:`.ai-notes/docs/project/handoff/`。

### dwm(UI,qml4j 底层)

| 文档 | 内容 | 状态 |
|---|---|---|
| `../dwm/README.md` | **活架构**:qml4j substrate、包、契约、帧循环 | 当前 |
| `dwm/live-verification.md` | 真机验证方法 + 抓到的 bug | 当前 |
| `dwm/key-ceremony.md` | TUF 密钥仪式、私钥在哪 | 当前 |
| `dwm/entry-point.md` | KI-11 为什么走补丁层 | 当前 |
| `dwm/fluent-spec.md` | Fluent 度量 + alpha 陷阱 | 当前 |
| `dwm/settings-page.md` | Settings 页结构、动画策略 | 当前 |
| `dwm/research/frame-sequence-verified.md` | 客户端源码证实的帧序列 | 当前 |
| `dwm/dwm-architecture-comparison.md` | 与真 Windows DWM 对照 | 当前(有订正横幅) |
| `dwm/dwm-deep-dive.md` | 真 DWM 设计动机研究 | **研究笔记**;模块地图以 `dwm/README.md` 为准 |
| `macos/dwm-qml4j-plan.md` | 当年落地方案 | **历史**;pin 以 `dwm/pom.xml` 为准 |
| `macos/known-issues.md` | macOS 专属(MK-1) | 当前 |

`dwm-gl` / imgui / skiko / Compose **已拆除**。不要按它们写新代码。

---

## 2. 按问题查

| 你想知道 | 去 |
|---|---|
| DWM 现在怎么分层 | `dwm/README.md`(仓库根,不是本目录) |
| 接手内核任务 | `agency/command-to-action.md` + `debugging.md` §10 |
| `act_set` `route` 真机 | `scripts/live-route-probe.py --allow-unfocused`(Windows COMPLETE 2026-08-21) |
| `act_plan` 真机 | `scripts/live-act-plan-probe.py --allow-unfocused`(Windows COMPLETE 2026-08-21) |
| 算路代码在哪 | `core/src/main/java/net/marcloud/mcp/core/drivers/plan/` |
| 「这条保证有人测吗」 | `agency/guarantees.md` §1(每行给 `file:line` + 测试类,查不到就写 unenforced) |
| 我新写的测试会被收集吗 | `agency/guarantees.md` §3(**契约测试文件名必须以 `Test` 结尾**;helper 和 `*LiveIT` 不许改) |
| 「现在几点、天亮了吗」 | `agency/guarantees.md` §1e(别读 `World.isDaytime()`,客户端上它是冻结的) |
| 空转断言怎么证伪 | `scripts/mutate.py` |
| 真机陷阱(熔断 / 失焦 / 死玩家) | `debugging.md` §10 |
| Fork 还没拍的 | `agency/command-to-action.md` §5 |
