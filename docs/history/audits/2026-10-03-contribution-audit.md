# 对冲审计的裁决 —— 「30 小时零产出」这个指控成立到什么程度

2026-10-03。一路 1 小时 24 分的只读审计，核了 15 个提交 + 53 条保证 + 14 个失败形状实例。
**本文只保留它推翻 Owner 自己指控的那一半**，因为那一半 Owner 从未听到过。

---

## 0. 一句话

**指控在「北极星从未推进」这一层完全成立；
作为对那 30 小时代码工作的判决不成立。**

准确的指控是：**产出被写在了 gitignored 的 `.ai-notes/` 和 `docs/agency/` 里，
没有一条被组织成「离北极星的距离」分级，也没有一次北极星运行。**

---

## 1. 四张清单（全部带 file:line，审计本轮复核）

### 1.1 15 个提交按对北极星的贡献

| 类 | 条数 | 提交 |
|---|---|---|
| **直接推进** | **5** | `1ba0a3d`（客户端时钟）/ `6389b29`（放置面别错 + 未知方块 id）/ `757ac16`（第四判据有生产者 + 工具面分层）/ `3f1f707`（第四判据的仪器）/ `d77f838`（线上方块 id 越界先抛） |
| 间接 | 7 | `7973f76` / `c3e3c5d`（接缝进 main 树）/ `cc0cf76` / `fe96e48` / `d8957ab` / `03eb6a39` / `25ddd38` |
| 纯卫生 | 1 | `012c04c`（`failure-shapes.md` 全文 767 行，零运行时行为） |
| 必要但不相关 | 2 | `d350ca6` / `d5a82b6`（签发脚本 classpath） |

**5/15 直接推进。** 而纯卫生只有 1 条——
**这与「一直在写文档」的指控不符**。

**主 agent 修正一处归因**：`Daylight.java` 这个文件本身归 `7973f76`，
而 `1ba0a3d` 的 subject 是「the clock the client never kept」且改了 `LivePlayerActuator.java`。
**同一波工作，两个提交**，审计归给后者是合理的。已核实 `LivePlayerActuator.java:243`：

```java
return w != null && Daylight.isDaytime(w.getWorldTime());
```

**不走 `World.isDaytime()`**——而后者在客户端上是冻结的。这就是那条 CRITICAL 的修法。

### 1.2 53 条保证按「离北极星多远」

判据是硬的：**这条保证的测试在真客户端上驱动生产类，还是只驱动 `SimWorld` + `GoalPolicy`？**

已核实 `GoalPolicy.java:80` 在 **test 树**，`EvalSuite.java:1709` 与 `:2853` 都 `runWith(GoalPolicy::new)`。
所以只驱动它的那一类**与北极星无关**——因为 `guarantees.md:1350-1353` §6.2 明令
「不许把目标策略写进 Java，验收对象是弱模型的决策」。

| 类 | 条数 |
|---|---|
| **A 直接决定成败** | **11** |
| B 提高成功率 | 24 |
| C 只在特定路径 | 6 |
| D 与一局无关 | 12 |

**A 类那 11 条值得单独看**，因为它们说明「即使工具面全修好，这 11 条一断，一局照样死」：

- 33 客户端昼夜判定派生自时钟（`WorldViewCapture.java:502`）
- 34 夜界是 vanilla 的、全 24000 tick（`Daylight.java:98,113`）
- 35 时钟助手结构上无法变成第二个冻结读（`Daylight.java:71,98,113,118,123`）
- 25 每个 interact/look 参数既被解析器读也被描述命名（`ActToolsTest.java:544,578`）
  —— **未命名的 `hitX/Y/Z` 直接等于箱子放偏**
- 29 危险绝不从未读格猜（`NavController.readJam`）
  —— **破坏后未加载区块到处是幻影深渊，模型一步不动**
- 39 存活行读低水位（`EvalSuite.java:155-157`）
- 40 eval 能红且证据是植入危险（`EvalSuite.java:1823 plantedHazardRun`）
- 41 伤害算术是 vanilla 的（`FireDamage.java:61 LAVA_CONTACT_DAMAGE=8`）

### 1.3 `failure-shapes` 十四个实例的今日状态

**13 已修 / 1 还在**（2.11：`IoProbe` 的 `minimum`/`maximum`，**有意保留**——
`2026-10-02-context-and-plan.md:63-64` 记录了决定：改它会一次性改变整个工具面的行为，
「这是关于工具面的策略问题，不是缺陷」）。

**14 减 1。** 而审计同时找出**第 8 个**（`craft_plan` 的合成出口），
已落盘在 `2026-10-03-tool-surface-falsification.md`。

### 1.4 六条被埋掉的真推进（`guarantees.md` 零命中）

这是本次审计**最有价值的产出**，因为它直接回答了
「有没有真推进被埋掉」——**有，六条，全都没进 53 条保证表**：

1. `requiredFace` —— 放置面别必填且越界拒（`ActIntentParser.java:490-503,528-538`）
2. `walk_straight` 元数 + `act_cancel{slots:all}` —— 取消可以指定槽位
3. 反应延迟改为**右偏抽样**（半正态），支撑集仍是 4..8，且**更快**（均值 6.00→5.54 tick）
4. 客户端**空块普查** —— 空方块不再被算成一种方块
5. 游泳/攀爬各自的**终止理由**（`SwimSteering` / `ClimbSteering` 各自发 WATER 危险）
6. `world_view` 描述拆分（`ToolDescriptionsMatchTheirBoundsTest` 那一族）

**这六条的性质值得说清**：它们不是「文档漏了」，是**53 条那张表有选择标准，
而这六条不符合那个标准所以没进去**。而标准本身是「每条保证 = 一个 `file:line` + 一个测试类」——
**一个需要三段论证才说得清的东西进不了那张表**。

---

## 2. 这条审计的可信度边界（审计自己声明的，我保留）

**它没能跑 `git log --stat`**（`xd://mcp__smartcli_start` 返回 `session limit reached (8/8)`，
且那 8 个会话里有属别 agent 的，不得 close）。

所以：**提交 subject 是【实测】（reflog raw），提交内容归属是【推断】**
（依据 reflog subject + wave 报告的 Base 行 + `guarantees.md` 的 file:line 交叉推断）。

**53 条保证的编号与标题是本轮逐行核出的**，那部分是【实测】。

---

## 3. 应该改的一处东西

**`guarantees.md` 的 53 条表需要一个「离北极星多远」列。**

理由不是「让文档更好」，是**那张表现在无法回答 Owner 唯一关心的问题**：
「这些保证和我要的『打完一局』有什么关系？」

而 12 条「与一局无关」和 11 条「直接决定成败」在表里长得**一模一样**。

**这一列的判据必须是硬的**：这条保证的测试**在真客户端上驱动生产类**，
还是**只驱动 `SimWorld` + `GoalPolicy`**。因为后者与北极星无关——
`guarantees.md` §6.2 已经把这条写成铁律了，只是那张表没有执行它。

**这一项建议放进下一批提交**，与正在跑的三个 worker 不冲突。

---

# 追加：第二轮复核的更正与补全（2026-10-03，主 agent 与第二路审计共同核实）

第一版有几处锚点偏移，且**漏掉了 3 个提交**。以下为准。

## A. 三处锚点更正（主 agent 亲自 `sed -n` 打开确认）

| 项 | 第一版写的 | 实际位置 | 怎么核的 |
|---|---|---|---|
| `requiredFace` 方法体 | `:490-503,528-538` | **`:552-561`**（`:552` 签名、`:553-560` 抛错、`:561` return），调用点 `:360` | 第一版那两行落在 `requireTriple`/`padded` 一带，与本方法无关 |
| `daytime = Daylight.isDaytime(time)` | `:502` | **`:509`** | `:497-508` 是解释「为何这个缺陷致命」的注释；`guarantees.md` 也写 `:502`，同样偏移 |
| 反应延迟的均值 | `6.00 -> 5.54` | **两处不同**：测试 javadoc `:32` 写 **5.55**，`MoveApplier` `:125` 写 **5.54** | 断言是区间 `5.2 < mean < 5.9`，**两个值都落在区间内** |

**第三项是一个新发现，不是单纯的更正** —— 见
`2026-10-03-failure-shape-16.md`（第 16 个失败形状）。

## B. 漏掉的 3 个提交【实测，来源 `.git/logs/HEAD`】

`c877231` 之后有 **18** 条 commit 行，**第一版的四类清单只覆盖 15 条**。
漏掉的三条全是 `review:` 前缀：

```
ba9977c   review: this project's own rules, in a form open-code-review can read
d0bae0b   review: adopt the control-pair discipline the corpus already uses
71ca961   review: the control-pair rule was shadowed and therefore dead
```

**所以更正后的分布是 18 条不是 15 条**，
而三条 `review:` 提交按性质属「纯卫生」——
**这会让「纯卫生」从 1 条变成 4 条**，即「卫生占四分之一」而不是「只有 1 条」。
**这比我第一版写的更不利于「30 小时在做事」那个辩护方向，所以它必须写进来。**

## C. `.agent/HANDOFF.md` 落后多少【实测】

```
第 3 行  board_head: c877231ec632a73e4589d6ca29a6752fb55cd16f
实际 HEAD d77f838
落后     18 个提交
而正文写 core 1629 tests / 4 red —— 今天实测 2076 / 0 failures
```

**那份文件描述的是 18 个提交之前的世界，而且它自己不知道。**

## D. 「有意保留」那条不必再靠报告转述【实测】

`failure-shapes.md` §2.11（`IoProbe` 不强制 `minimum`/`maximum`）
的保留理由现在有源码级出处：`IoProbe.java:117-134`，
其 javadoc 标题就是 **「What this does NOT enforce: minimum and maximum」**，
理由与 `2026-10-02-context-and-plan.md:63-64` 记录的一致
（改它会一次性改变整个工具面行为）。

## E. 「离北极星多远」那一列的硬判据，其证据在两处【实测】

```
core/src/test/java/net/marcloud/mcp/core/eval/GoalPolicy.java:80
    public final class GoalPolicy implements Policy          <- test 树
core/src/test/java/net/marcloud/mcp/core/eval/EvalSuite.java:1709, :2853
    runWith(GoalPolicy::new)                                  <- 26 个 task 由它驱动
```

**凡是只被这两处驱动的保证，都与北极星无关**——
因为 `guarantees.md:1350-1353` §6.2 已经把「不许把目标策略写进 Java」
写成铁律，而**只驱动 `GoalPolicy` 的测试恰恰证明了控制器会组合，
没有一件证明模型会产生那个计划**。

## F. 结论不变，但支撑它的一条要修正

**「30 小时零产出」在「北极星从未推进」这一层完全成立**——
四判据全是谓词、零决策，唯一一次报 `dawn` 是假且作者两分钟后撤回。

**作为对那 30 小时代码工作的判决不成立**，但**更正后更弱了一点**：
纯卫生从 1/15 变成 4/18，而「直接推进」仍是 5 条。
