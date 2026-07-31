# 分支拓扑:这条线是什么,主线在哪

2026-07-30。给下一个上下文,以及给"我在哪个分支上、该往哪推"这个问题一个单一答案。

**一句话:主线在 Windows 工作站,`feat/dwm-qml4j` 是 macOS arm64 上的分支线。
它不是一个等着合并回去的 feature 分支。**

---

## 0. 实测拓扑(不是记忆,是 `git rev-list` 的输出)

```
origin/main        c2cf357  2026-07-11   client + lwjgl2-shim
    │
    └─ origin/mcp-core  1dbf475  2026-07-17   + core + board + pg(内核与能力包)
           │                                   dwm 在这里被剥成空概念模块
           │
           └─ feat/dwm-qml4j  2026-07-30      + dwm 的 qml4j 实现(本线)
                              47 commits ahead
```

三条线是**严格祖先关系**,实测:

| 关系 | 结果 |
|---|---|
| `origin/main` 是本线祖先 | ✔ 完全包含 |
| `origin/mcp-core` 是本线祖先 | ✔ 完全包含 |
| `main` 有我们没有的提交 | **0** |
| `mcp-core` 有我们没有的提交 | **0** |
| 本线未推送的提交 | **0** |

**所以"和远程同步"在这条线上不需要任何 merge / rebase / pull。** 没有分叉,没有冲突,
没有落后。要做的只有 `git push`(已做)。

分叉点是 `1dbf475`("dwm: strip UI implementation to an empty concept module")——
那次提交把旧的 UI 实现全部删掉、把 dwm 留成一个空壳,本线就是从那个空壳开始重建 dwm 的。

---

## 1. 为什么不合并回 mcp-core

**这条线只在 macOS / Apple GL 2.1 上验证过。** 而项目的主环境是 Windows 工作站
(`.ai-notes/` 与 `codegraph` 都在那台机器上,本机没有——见 §4)。

本线包含一批**图形状态修复**,它们的正确性依赖驱动行为:

- `GL_ARRAY_BUFFER` 绑定泄漏(曾导致 SIGSEGV)
- vertex attrib array 使能泄漏
- FBO 绑定 + shader program 泄漏
- `GL_UNPACK_ALIGNMENT`

这些全部**只在 Apple 的 GL 2.1 兼容 profile 上跑过**。不同驱动对"有绑定时把堆指针当偏移"
这类行为的反应可能不同,所以**在 Windows 侧验证之前合并是不诚实的** ——
连续三份交接都把"Windows 侧验证"列为待办第一位或第二位,至今没做。

另有两条 macOS 专属约束写进了本线的形状,合并前必须想清楚它们在 Windows 上是否还成立:

- **`-XstartOnFirstThread`**:GLFW 占着进程主线程,AppKit 要求窗口事件循环在主线程,
  所以"第二套窗口体系没有线程可跑"——这是 dwm 画进 MC framebuffer 而非自建窗口的**根本原因**
  (`dwm-architecture-comparison.md` §3.1)。Windows 无此约束。
- **`-Djava.awt.headless=true`**:vanilla 剪贴板走 AWT,在 macOS 上会让 JVM 退不出去
  (`docs/macos/known-issues.md` MK-1)。

---

## 2. CI 从未在这条线上跑过

`.github/workflows/build.yml` 的 `push` 触发分支是 `[main, mcp-core, rank1-encryption-test]`
—— **不含 `feat/dwm-qml4j`**。所以这 47 个提交推上去之后,**GitHub Actions 一次都没跑**。

这不是疏忽被发现,是一个需要决定的事:

- 想要 CI 覆盖,就把分支加进 `push:` 列表(或开一个 draft PR,`pull_request:` 会触发);
- 但 CI 是 `ubuntu-latest`,**跑不了这条线真正需要的验证** —— live IT 与真机探针都要显示器
  和游戏素材,在 CI 上会 `Assume` 自跳过。CI 能给的只有"单测 + 能打包",
  拿不到本线唯一在乎的那种证据(像素)。

所以现状是:**本线的回归网是本地的**(core 662 + dwm 35 单测 + 43 live IT + 真机探针 33/33),
不是 CI 的。谁接手都要知道"绿"是在本机跑出来的。

---

## 3. 怎么跑(macOS,本线)

```bash
export JAVA_HOME=~/.jdks/jdk-25.0.3+9/Contents/Home   # 必须,见下
./mvnw -B -ntp test                                    # core 656 + dwm 32 单测
./mvnw -B -ntp -pl dwm verify -Ddwm.live.skip=false    # 43 live IT(要显示器)
./scripts/run-mcp.sh                                   # 起客户端
python3 scripts/live-dwm-probe.py                      # 33 项真机验证
```

**`JAVA_HOME` 不设会失败,而且失败得像回归。** PATH 上的 `java` 是 Homebrew JDK **21**,
而 `core/target` 里的测试类是 JDK 25 编的(class file 69),surefire 会报
`... has been compiled by a more recent version of the Java Runtime`。
`run-mcp.sh` 自己会找 JDK 25,所以只有 Maven 需要这一步。

---

## 4. 本机与主线的环境差异(接手时先看这条)

| 东西 | 主线(Windows 工作站) | 本机(macOS arm64) |
|---|---|---|
| `.ai-notes/`(STATUS/机关地图/模板) | 有 | **没有** —— 所以本线文档写在 `docs/` |
| `codegraph`(CLAUDE.md 铁律⑥的读码路径) | 有 | **没有** —— 读码只能 Read/Grep |
| `ARCHITECTURE-LOCK.md` | 有 | **没有** —— 动骨架要不要走 ADR 本机无法自证 |
| 两把 Ed25519 私钥 | ? | **在 `~/.mcp-keys/`,权限 600,仓库外** |

**私钥那条最要紧:丢了没有恢复路径**,必须重走两层仪式并重签所有 compat 补丁。
`~/.mcp-keys/{kernel,root}-ed25519.key.b64`。流程见 `dwm/key-ceremony.md`。

CLAUDE.md 的模块表还列着 `dwm-compose/`,但**本仓库没有这个目录**(`pom.xml` 是
shim/client/core/board/pg/dwm 六个)。改 CLAUDE.md 需 3 次确认,所以只在此 flag。

---

## 5. 本线相对 mcp-core 改了什么(范围,不是清单)

```
零改动:  client/src/  board/  pg/      ← 冻结的 vanilla 源码与骨架,一行没动
改了:    client/pom.xml                ← 仅加 macOS arm64 原生库(见下,不违反冻结)
         lwjgl2-shim/                  ← ABI 垫片,本职
         core/  ← 仅 compat 层:三个补丁的签名常量、四个随包公开材料、
                   两个新 CLI 工具(KernelKeygenCli / RootCeremonyCli)、两个测试
新建:    dwm/                          ← 本线主体
```

**`client/pom.xml` 改了 35 行,而这不违反"client 冻结"**,区别要说清:冻结的是
**vanilla 源码**(`client/src`,反射/GUI 字段名的真相来源),不是构建描述。本线加的是
`natives-macos-arm64` 分类器的 LWJGL3 运行时依赖 —— **原生库依赖没法从补丁层加**,
而改 vanilla 源码可以且必须走补丁层(KI-1/KI-4/KI-11 就是)。同一个 fat jar 仍然两平台通用:
LWJGL 运行时按 `os.name`/`os.arch` 选对应的那套。

可验证:

```bash
git diff --stat origin/mcp-core..HEAD -- client/src board pg   # 应为空
git diff --stat origin/mcp-core..HEAD -- client                # 只有 pom.xml
```

> 历史注记:相对 v1.0.0 干净基线,`client/src` 里还留着两处**早于本线**的行为改动
> (`TextureUtil.java` 的 `[0].length` 与 `GL_CLAMP_TO_EDGE`),以及一批 Guava/oshi
> 依赖迁移适配。**不是本线引入的**,但"client 从未被改过"作为绝对陈述并不成立 ——
> 本线的准确说法是"本线不碰 `client/src`"。

`core/` 的改动**全部在 compat 层**,没有碰安全内核(7 层权限、能力包、P-SECURE)。

---

## 6. 合并回主线之前必须做的(按顺序)

1. **Windows 侧验证那批 GL 修复** —— 尤其 ARRAY_BUFFER 绑定与 vertex attrib array 两条。
   这是唯一的硬前提,没有它合并就是把未验证的驱动假设推给主线。
2. **决定 CI 策略**(§2)—— 至少让单测在这条线上跑。
3. ~~卡片面在真机不可见~~ **已修复(2026-07-31)** —— 根因是 MC 的 `GL_ALPHA_TEST` 丢弃
   alpha ≤ 25 的片元(卡片面是 alpha 13)。`GlStateGuard` 现在为 Skia 禁用它。
   见 `handoff-2026-07-29.md` §3、commit `5669a04`。已配真机断言 + 探针检查。
4. ~~**`defaultTrustAnchors()` 的信任回退**~~ **已关闭(2026-07-31)** —— 撤销现在生效;
   代价是任何 root 资源损坏即全部补丁失效。见 `dwm/handoff-2026-07-29.md` §7。

**明确不做**:把这条线 rebase 到 main / 强推 / 合并 main 进来。三者都不需要 —— 见 §0,
根本没有分叉。
