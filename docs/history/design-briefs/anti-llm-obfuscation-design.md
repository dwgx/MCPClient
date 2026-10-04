---
doc: design-anti-llm-obf
title: 设计简报 — 抗-LLM 混淆栈(分层防御)
layer: reference
status: draft
updated: 2026-07-12
parent: ../README.md
next:
  - path: anti-llm-obfuscation-and-compat-crypto-research.md
    when: 你要看每个技术的来源/成熟度/诚实破法
read_if: 你要实现/review 抗-LLM 混淆栈。这是设计,未落代码;强调 JVM 移植的自研成本与诚实边界。
---

# 设计简报:抗-LLM 混淆栈

> 基于 27-agent 研究档案 + dwgx 2026-07-12 拍板(选择性 split-execution)。
> status: draft,review 后才落代码。目标(Wave1 定论):不是信息论墙,是**把成本推到攻击者放弃**。

## 0. 威胁模型与铁底线(先立,别忘)

- 对手 = 顶级黑客 + 最强 LLM(Claude/GPT5/Grok)+ 已持 R-1。
- **客户端无信息论墙**(R-1 能 dump 一切)。混淆只买时间 ×10²-10³。
- 三条诚实边界:①S5/状态类须绑 CoT 收税否则被 CoT 击穿 ②分词层 normalize 就废 ③无信息论墙。
- **真墙只在 split-execution**(关键逻辑在服务端,见 compat 简报)。混淆保护的是"留在客户端的那部分"。

## 1. 分层防御栈(按研究优先级)

```
L-VM   VM 虚拟化核        Tigress 式 per-capability 自定义字节码 + 随机 dispatch(9 选)   [High]
  └ L-HANDLER  handler 硬化   depth-9 非线性 MBA + 跨 handler 隐式流                        [Med, 裹在 VM 里]
L-DSE  路径爆炸            SVP for-loop 字节级分叉(k=3-5),补丁参数校验链上,炸符号执行     [High, 我们的王牌]
L-CFF  控制流             CFF on dispatcher + 零足迹不透明谓词(从 JVM 栈不变量合成)        [Med]
L-DECOY 抗重写诱饵         类名/标识符投毒(域内可信错名)                                  [Low, 怕重写对手]
L-STR  常量               per-string 多态解密(patchId 作 HKDF context)                    [Low, 卫生层非墙]
```

开销预算:10-50x 只加在**冷路径**(compat 补丁应用、关键 capability 加载),不碰热路径(渲染/tick)。
VM 虚拟化 120-337x 开销 → 只护 <5% 关键函数(签名校验/密钥派生);其余 OLLVM 4-6x。

## 2. 王牌:SVP for-loop 路径分叉(为什么押它)

- ~1.3x 运行开销,制造 2^(8k) DSE 路径爆炸;Triton/KLEE/Binsec 在 k=5 时 **0/10** 破。
- 正面压制"符号执行"这条最强的自动化攻击线(angr/Triton 是对手主武器)。
- **JVM 字节码实现全网空白**(论文 arXiv 1908.01549 只有 C)——这是我们能率先做、且真有护城河的点。
- 诚实边界:小输入空间要塞假输入撑场;`clang -Ofast` 破 P1 变体(我们在 JVM 无此问题但要防 JIT 等价优化);
  LLM 走语义可绕(不做 DSE)——所以 SVP **专克符号执行,不克 LLM 直读**,必须与 VM/CoT 收税叠加。

## 3. CoT 收税(对付 LLM 直读,补 SVP 的盲区)

SVP 挡符号执行,但 LLM 可以不做 DSE、直接"读懂"。对 LLM 的墙靠 CoT 收税:
- 跨方法/跨类的长程状态依赖(≥5 对象不可解置换链)→ 固定深度 Transformer(∈TC⁰)无法可靠追踪,
  逼它上 CoT;CoT 又被 handler 里的 rare control flow / 语义非典型性诱发震荡(CoTDeceptor 思路)。
- 理论支撑:Merrill&Sabharwal、Liu et al(word-problem/parallelism tradeoff)、"CoT empowers transformers"。
- 诚实边界:S5 单独会被 CoT 击穿,**必须**绑 CoT 收税(让 CoT 也贵);opaque 模型比透明模型难收税。

## 4. JVM 移植的自研成本(最大工程风险,先说清)

研究里的 High 技术**几乎都只有 C/native 实现**,移到 JVM 字节码是真自研,不是抄库:
- **SVP forking**:需 ASM 插入 ILOAD/IINC/IF_ICMPGT 循环模式,**保 stack-map frame**(JDK25 严格校验)。
- **VM 虚拟化**:Tigress 生成 C;JVM 需自研字节码 VM 生成器,或等 Grunt(pre-release),或自研 MapleIR SSA pass。
- **非线性 MBA**:plzin/mba、Loki 目标 C/汇编;需 Java 表达式 AST 重写 + 深度>6 多项式,且防 javac 常量折叠。
- **零足迹不透明谓词**:需从方法字节码抽栈/局部变量的自然不变量来合成谓词。

结论:**这是分阶段自研项目**,不是一次性接入。建议先做 SVP(王牌 + 空白 + 开销最低)验证价值,再上 VM。

## 5. 落地在架构哪一层

- 混淆是 00-META 的**第四关注点(自我保护,形态轴)**,独立于信任哲学。
- 作用对象:compat 补丁的校验/密钥代码(护 §compat 简报)+ 关键 capability 留在客户端的部分。
- **不碰** vanilla client(铁律#4),不碰热路径。作为构建期/premain 的独立 pass 施加在 core 产物上。

## 6. 验收线(拿对手工具打自己产物)
- Triton(DSE):SVP 处期望超时/路径爆炸。
- CoBRA(MBA 简化):depth>6 应抗住 >90%。
- angr(CFG 恢复):flattened dispatcher 期望不完整。
- VMDragonSlayer(去虚拟化天花板,982/1000):量化我们 VM 被恢复率。
- GPT5/Opus4.8/DS-R1 带 CoT 喂反编译源:目标 SRS<50%。
- 人工专家 24h time-box:确认需多日分析。
- **功能等价回归**:混淆后全测试 100% 绿(语义保持),冷路径开销 <2x 预算内。

## 7. 待定 gap
1. 分阶段顺序:先 SVP 单点验证,还是先搭 VM 框架?(建议前者)
2. 混淆工具链:自研 MapleIR pass vs 等 Grunt vs OLLVM(需把 core 逻辑降到 native)。
3. CoT 收税是否加主动 honeypot/canary 检测自动化逆向。
4. 多模型交叉验证成本税(强制攻击者付 3x 推理)是否值得工程化。

## 关联
- 技术来源/破法:`anti-llm-obfuscation-and-compat-crypto-research.md`。
- 战略定论:记忆 `project-anti-llm-obfuscation`。
- 护 compat 校验代码:`compat-crypto-core-design.md`。
- 元架构定位(第四关注点):`../architecture/00-META.md`。
