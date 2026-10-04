---
doc: research-s5-compat
title: 研究档案 — 抗-LLM混淆栈 + compat加密核心(一手技术采集)
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: ../architecture/07-COMPAT-SHIM.md
    when: 你要看 compat 补丁系统架构(本档为其加密核心提供技术选型)
read_if: 你要落地/重构抗-LLM混淆或 compat 加密核心;这里是 27-agent deep-research 采集的具名技术、真实来源、诚实边界。
---

# 研究档案:抗-LLM混淆 + compat加密核心

> 2026-07-12,27-agent deep-research(24 采集 + 3 综合,2.4M token)从 GitHub/arXiv/
> 从业者/论坛采集**具名技术 + 真实来源 + 诚实破法**。这是架构重构的素材,不是最终架构。
> 诚实底线(全程尊重):客户端无信息论墙;S5 须绑 CoT 收税;分词 normalize 破;唯一硬墙=split-execution。

## 一、抗-LLM 混淆栈(具名技术,按优先级)

### High(值得做)
- **Path-Oriented SVP Forking(for-loop 字节级路径分叉)**:~1.3x 运行开销制造 2^(8k) DSE 路径爆炸;
  KLEE/Binsec/Triton 在 k=5 时 0/10 破解。来源:arXiv 1908.01549(仅 C,**JVM 字节码实现是空白**)。
  破法:小输入空间需塞假输入;`clang -Ofast` 破 P1 变体;LLM 走语义可绕(不做 DSE)——**只挡符号执行**。
- **Tigress VM 虚拟化(自定义 ISA + handler 多样性)**:每函数字节码 + 9 种 dispatch + handler 拆分 +
  隐式流。结构多样性远超 O-LLVM(LLM ~70% vs 99%)。来源:tigress.cs.arizona.edu(生成 C,需移植 JVM 或用 Grunt WIP)。
  破法:Triton+LLVM 能 lift 纯函数;静态 LLVM pass 检测 dispatcher。多块+嵌套+MBA 抬高门槛非墙。

### Medium(配合用,别单独上)
- **非线性/高深度 MBA(carry-sensitive 混合域)**:线性 MBA 被 CoBRA 99.86% 破;Loki depth-9 让
  MBA-Blast 只简化 0.5%。来源:plzin/mba(Rust)、nhpcc502/MBA-Obfuscator、Loki(arXiv 2106.08913)。
  破法:CoBRA worklist + GPU 合成 + Transformer 逼近;孤立 MBA 终可破——**必须裹在 VM 上下文里**。
- **CFF + 不透明谓词(组合)**:单独弱,组合下 o3 在 >20 opcode 失败;Tigress 变体更硬。来源:O-LLVM/Tigress/Grunt。
  破法:D810/MODeflattener 自动反平坦化;GPT5+CoT 简单 O-LLVM 100% 破。只买复杂度不买机密。
- **隐式流(抗污点分析)**:用控制依赖藏数据流,击败轻量 taint。来源:Tigress implicit-flow pass。
- **OLLVM 三遍叠加(SUB+FLA+BCF)**:4-5x 复杂度乘数;ARM 天然比 x86 多 2.4x 抗 LLM(训练数据稀缺)。来源:wwh1004/ollvm-16、o-mvll。

### Low(诱饵类,便宜坑 LLM,怕重写式对手)
- **投毒标识符(语义-输出解耦)**:反编译产物里植误导名,LLM 100% 传播错名(Opus)。破法:重写式提示直接归零。
- **Acoda 遗传对抗混淆(安全对齐利用)**:假漏洞/危险命名触发 LLM 拒答,70% ASR。破法:强模型抵抗;预处理剥离。
- **CoTDeceptor(CoT 不稳定靶向)**:多 agent RL 演化混淆链诱发 CoT 幻觉。破法:未测重写式对手;高算力门槛。
- **多义 handler(Loki 式)/ 字符串加密**:301-482x 开销非生产;字符串加密断点即 dump 明文。

**综合建议栈**:VM 核(Tigress 式 per-capability 自定义字节码 + 随机 dispatch)→ handler 用 depth-9 MBA +
跨 handler 隐式流硬化 → 补丁参数校验链上 SVP for-loop 分叉(k=3-5)炸 DSE → dispatcher 上 CFF + 零足迹
不透明谓词 → 类名投毒诱饵 → per-string 多态解密(patchId 作 HKDF context)。开销 10-50x 只加在冷路径。

**验收线(拿这些打自己产物)**:Triton(DSE,期望 SVP 处超时/爆炸)· CoBRA(MBA 简化,depth>6 应抗住 >90%)·
angr(CFG 恢复,期望不完整)· VMDragonSlayer(去虚拟化天花板,982/1000 CFG 恢复)· GPT5/Opus4.8/DS-R1 带 CoT(目标 SRS<50%)· 人工专家 24h time-box。

## 二、compat 加密核心(具体方案)

- **密钥管理(无单一可提取密钥)**:master IKM = passphrase/server-seed → Argon2id(t=2,m=64MB,p=1)→ 256-bit;
  HKDF-Extract → PRK → per-capability HKDF-Expand(info="C6.native.debugger")→ capability_root →
  per-patch HKDF-Expand(info="patchId||version||purpose")→ patch_HMAC_key + patch_decrypt_key。info 单射编码防规范化碰撞。
- **MAC**:HMAC-SHA256(输出截 128-bit tag);key ≥32B;`MessageDigest.isEqual` 常量时间比较;
  绑定 HMAC(key, patchId||timestamp||bytecode)= 身份+新鲜度+内容。
- **patchId 派生**:SHA-256(capability||variant||semantic_hash) 前 128 位;补丁体 ChaCha20(96-bit 随机 nonce)加密。
- **防篡改校验图**:加载→HKDF 派生→HMAC 常量时间校验→ChaCha20 解密→ASM transform→**立即 zeroize 密钥**→执行。
  校验/派生代码本身用 OLLVM(CFF+MBA+SUB)或 Tigress VM 虚拟化保护。
- **真实实现库**:patrickfav/hkdf(RFC 5869,Java 生产级,直接可用)。
- **诚实 R-1 边界**:R-1 对手能 dump 解密窗口明文/内存 PRK/校验逻辑;HMAC 防被动篡改有效、防 R-1 无效(密钥同地址空间);
  混淆只 ×10²-10³ 拖慢非密码学保证;**唯一硬墙=split-execution(服务端持主密钥/签名权,客户端仅验证)**。
- **工程平衡**:假设对手经济理性(非 nation-state);VM 虚拟化 120-337x 开销只护 <5% 关键函数(HMAC/KDF),其余 OLLVM 4-6x。

**验收线**:HKDF/HMAC RFC 向量 + NIST CAVP;isEqual timing t-test(p>0.05);伪造 tag→拒;改 patchId→校验失败;
Argon2id<200ms;Frida hook HMAC→检测;Triton 打 VM 函数目标 >8h。

## 三、待你拍板的 gaps(两个功能共同)
1. **密钥初始来源**:用户 passphrase(UX 摩擦)vs 服务端派 seed(需网络)vs hardcoded 混淆(可提取)——决定整个信任根。
2. **是否上 split-execution 真墙**:license/关键 capability 可选服务端权威;你之前砍了强制后端,但说乐意做——需定容忍度。
3. **混淆工具链选型**:Tigress(C,需 JNI 桥)vs OLLVM(LLVM IR,需重编译)vs Grunt(JVM native,pre-release)vs 自研 MapleIR pass。
4. **JVM 移植的空白**:SVP forking / Loki 式 MBA / VM 虚拟化都只有 C/native 实现,移到 JVM 字节码(ASM,保 stack-map frame)是自研工作。
5. **失败模式**:完整性校验失败后=静默降级 vs 明确拒绝 vs 部分功能?产品决策。
6. **CoT 收税**:当前混淆强制 LLM 用 CoT 但无主动检测;是否加 honeypot/canary 检测自动化逆向?

## 四、MC-Java 圈真空白点(我们可率先做)
- 活体 JVM 热改 + MCP 暴露(国内 MC 圈只有静态模组)。
- JVMTI 原生调试器 + 7 层 NT 特权内核(MC 模组圈无安全模型)。
- 抗-LLM 混淆 for Java 游戏(威胁模型是 R-1+Claude/GPT5,不是传统逆向;无人针对 CoT 成本设计)。
- Compat keyless crypto(HKDF 子钥 + 服务端签名 + 客户端零主钥验证)。
- Loki 级 MBA / SVP forking / VM 虚拟化移植到 JVM 字节码 + ASM + 常量池约束。
- MCP server 本身当攻击面(工具元数据/错误消息/参数 schema 都可能泄露语义给 LLM)。
- 多模型交叉验证成本税(强制攻击者付 3x 推理 + CoT 开销)。

## 五、值得长期追踪(人 / repo / 社区)
- **人**:Tim Blazytko(synthesis.to,Loki/msynth)· Jonathan Salwan(Triton,去虚拟化)· Christian Collberg(Tigress)·
  Romain Thomas(O-MVLL/LIEF)· Hugo Krawczyk(HKDF 作者)· momo5502(游戏反篡改 bypass 实战)· Sam S-J(Skidfuscator)· Trail of Bits / Quarkslab 团队。
- **repo**:RUB-SysSec/loki · Tigress · trailofbits/CoBRA · poppopjmp/VMDragonSlayer · JonathanSalwan/Triton ·
  o-mvll / wwh1004/ollvm-16 · skidfuscatordev/skidfuscator · patrickfav/hkdf · leibnitz27/cfr · java-deobfuscator。
- **社区**:r/ReverseEngineering · tuts4you.com · 0x00sec · crypto.stackexchange · IACR ePrint · CheckMATE@CCS(MATE 攻防)· MITRE ATT&CK T1027。

## 关联
- 战略定论(Wave1/2):记忆 `project-anti-llm-obfuscation`。
- compat 系统架构:`../architecture/07-COMPAT-SHIM.md`。
