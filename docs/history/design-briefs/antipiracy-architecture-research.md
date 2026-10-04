---
doc: research-antipiracy
title: 研究档案 — 防盗用架构(目标A:解密型/防patch/服务端权威)
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: compat-crypto-core-design.md
    when: 你要看这套如何接进 compat 加密核心
read_if: 你要落地/review 防盗用(目标A)架构;这里是 17-agent deep-research 的具名技术 + 4 层纵深 + 破法 + 诚实边界。
---

# 研究档案:防盗用架构(目标 A)

> 2026-07-12,17-agent deep-research(14 采集 + 3 综合,1.3M token;1 路"沙盒喂料"stalled,已由主 agent 补)。
> dwgx 定:**目标 A 防盗用**(没付费/被撤销就用不了),不是 B 防逆向。胜利条件=没有服务端持续配合就用不了。

## 一、4 层纵深架构(研究综合,升级了 dwgx 原方案)

- **L1 启动(客户端,无秘密)**:启动器 pin 服务端证书 + 多源硬件指纹(OSHI:CPU/盘/MAC)+ 有 TPM 则远程证明(无则软件降级)。发 {hwid, nonce, timestamp}。
- **L2 会话建立(服务端权威)**:验账号(黑名单/并发限/异地登录)→ HKDF(master_PSK||salt||ctx) 派生一次性 AES-256-GCM 会话密钥 → 加密关键字节码类 → 返回 {加密 payload, RSA-OAEP 包裹的会话密钥, lease_id, ttl≈600s}。**payload 每会话多态**(服务端 ASM/ByteBuddy 生成语义等价但结构不同的字节码)。
- **L3 运行时(客户端解密+执行+心跳)**:Custom ClassLoader 解密 → defineClass 加载 → 函数级懒解密(明文窗口→微秒)→ JVMTI native agent 持续查完整性/查 hook/查调试器 → 每 5-15min(抖动)心跳换新 lease(旧的作废)→ 撤销信号来:SecretKey.destroy() + Arrays.fill(bytecode,0) + System.exit()。
- **L4 检测与撤销(服务端 ML + kill-switch)**:遥测流(API 频率/会话时长/geo 跳变/换设备)→ 实时异常检测(streaming EWMA + HLL 并发基数)→ 分级(60-80 静默限流 / 40-60 强制 MFA / <40 封号+全会话失效)。人工复核队列 + 后台手动 kill。

**关键性质**:客户端无单点秘密(启动无秘密、会话钥短命);多态字节码 + lease 轮换破静态 crack;服务端持终极权威(patch 伪造不出 lease);行为检测抓规模化共享;优雅降级(无 TPM 弱但不废)。**承认 MATE 现实:R-1 能 dump 一个会话的解密代码,但无法分发通用 crack(下会话就变)、无法躲服务端行为封禁。**

## 二、提升项排序(值不值得加)

**High(核心护城河,客户端 patch 不了)**:
- **服务端行为异常检测 + 渐进信誉评分** — 抗 patch 极强(客户端改不了服务器看到的流量)。JVM 完美适配(遥测流 Kafka/Flink)。边界:滞后反应(分钟-小时);慢速攻击可能低于阈值。
- **会话密钥轮换 + opaque token + 服务端撤销(kill-switch)** — 抗 patch 极强(服务端状态即真相,patch 复活不了已撤销会话)。JVM 原生(SecureRandom/Redis/HttpClient)。边界:TTL 内离线仍可用(故 TTL 要短);Redis 是单点(需 HA)。
- **服务端下发多态字节码(每会话变体)** — 抗 patch 强(单次 dump 只破一个会话)。JVM 可行但有成本($0.41-0.73/payload;1万会话/天≈$4-7k/天,须只对热方法变体 + 缓存模板)。边界:攻击者收集多样本做静态归一化提取不变核心。
- **HKDF 每会话派生 + 前向保密** — 抗 patch 强(密码学派生无绕过)。JVM 原生。边界:master PSK 是单点(须 TPM seal 或 split-knowledge 护)。

**Medium(第二层,配合用)**:
- **函数级懒解密 + 持续重加密(Tigress Dynamic)** — 明文窗口→微秒。JVM 中等挑战(纯 Java 每次解密=JNI/纯 Java AES,开销 7.9x+;native JVMTI 更快但部署复杂)。边界:递归/函数指针破保护;全覆盖 trace + 硬件断点可重建。
- **环境键控**(hash(user||machine||time)→密钥,错环境解出垃圾) — 无 if 可 patch。边界:可重置 VM 迭代猜测;低熵字段(computerName)弱化,须高熵源。
- **远程证明(TPM 2.0 挑战响应)** — 有硬件时抗 patch 强(软件伪造不出 TPM 签名)。边界:仅启动时点、非运行时;中继攻击;TPM 覆盖率(企业本 ~70%,消费机 <40%,SGX 已退出消费级)。
- **代码虚拟化(自定义 VM)** — 中(自动去虚拟化成熟:X64Unpack/VMDragonSlayer,920/920 样本 100% lift)。高开销 2-10x,只对 ~10% 关键方法可行。
- **split-knowledge 密钥托管(Shamir,客户端+服务端各半)** — 抗 patch 强(单方提取无用)。边界:组合协议是攻击面(MitM);每次解密加一次服务端往返(非离线友好)。
- **设备指纹聚类 + 并发会话限制** — 中(抓普通共享;anti-detect 工具可绕但要投入)。Java 桌面信号比浏览器少。

**Low(别当主防线)**:
- **白盒密码** — 弱,学术共识全破(DCA 2^22-2^30 提密钥);JVM 是最差平台(反射/instrumentation 天然白盒)。只能当减速带。
- **HBHC 级联撤销** — 需 TEE(SGX 消费级已移除),更适合服务端层级而非客户端。

## 三、破法路径(攻击者能走的路 → 我们的反制)
- **R-1 内存 dump 明文** → 破所有客户端解密型。反制:移动目标 + 短 TTL + 行为撤销,让 dump 的 ROI 趋负(不阻止,让不划算)。
- **客户端 patch 验证逻辑** → 破所有 if-check 型。反制:**解密型架构**——错 license 解出错代码→未定义行为崩(非 graceful fail),没有 if 可 NOP。
- **JVMTI/Agent hook** → 捕获 defineClass 后明文/拦会话钥。反制:JVMTI native agent 检测外部 agent + 服务端行为分析(高频 retransform 上报→撤销)。
- **Hypervisor ring-1 伪造硬件指纹** → 破硬件绑定。反制:TPM 远程证明(hypervisor 伪造不出 PCR quote),但受 TPM 覆盖率限制。
- **中继 attestation(farm 真机签名转发)** → 破 TPM。反制:attestation + 运行时行为双验(单硬件对多账号=farming 信号)。
- **coldboot/DMA 物理抠 RAM** → 破一切运行时明文。反制:**承认物理访问防不住**(威胁模型外)。
- **LLM 辅助去混淆 / VM 自动去虚拟化** → 破静态混淆。反制:多层正交组合(VM+MBA+懒解密+时序)+ 服务端移动目标,买时间非墙。

## 四、诚实边界(必须随方案存在)
- 客户端任何检查最终可被 patch/hook,减速带非城墙,只买时间(小时-月),无信息论保证。
- 加密算法强 ≠ 系统安全:明文必在内存→R-1 可 dump;白盒全破;**真护城河是架构(服务端持逻辑)非算法**。
- TPM/TEE 覆盖率低 + 有侧信道;Java LWJGL 游戏几乎塞不进 enclave。
- 离线 grace period = 攻击窗口;真防盗用=zero offline tolerance,但有 UX 代价。
- **检测≠阻止**:只能发现后响应(撤销),有 time-to-response 窗口。
- 服务端依赖=单点 + 成本:需 HA + 用户接受 always-online。
- 行为检测有 baseline poisoning + concept drift + 撤销传播滞后。

## 五、12 个硬问题(标记"值得丢给前沿模型深挖")
1. 行为 ML 能否区分正版多设备用户 vs 共享团伙(攻击者加人类抖动时)?检测成本 > 盗版损失的 ROI 拐点在哪?
2. 软件-only 会话 attestation 能否达 TPM 90% 安全 @ 10% 成本?2026 消费机 TPM 覆盖缺口多大?
3. 函数级懒解密(明文窗口≤16 字节)+ 持续重加密,亚毫秒窗口能否打败自动化工具?理论下界?
4. 白盒学术已破,但商业 DRM 叠混淆存活多年——Fable5/o3 能否比人类专家更快击穿分层(WBC+VM+MBA+CFF),还是组合爆炸仍需人工?
5. 服务端多态代码生成 $0.41-0.73/payload,请求率多高时成本 > 防盗用价值?攻击者"请求→dump→归一化→提取不变量"流水线能否比服务端生成新变体更快?
6. 环境键控被可重置 VM 暴力采样——概率环境键控(+TPM nonce + 服务端时戳)能否消除重放且保留离线宽限?
7. MATE 证明客户端只能拖延——"crack 时间 vs 收入保护窗口"实测函数?12 周延迟真保 95% 收入(Denuvo 宣称),还是 cracker 工业化 + LLM 去混淆把窗口压到几天?
8. kill-switch 需 watchdog 自身防篡改——split-knowledge(watchdog 半钥 + 服务端半钥)+ 远程证明能否让客户端 watchdog 抗 root,还是必须整个搬服务端?
9. LLM 去混淆对 CFG 扁平化 + opaque predicate 达 93-98%——叠多正交变换能否压到 <50%,还是模型 scaling 轻松破任何静态组合?
10. 后量子会话协议(ML-KEM+HKDF-SHA3)加 0.5-0.7ms + 1640 字节——实时游戏(16ms 帧预算)每会话 PQ 握手可行,还是接受 harvest-now-decrypt-later 靠服务端逻辑保密?
11. 行为检测误报管理需人工标注 + shadow 测试(天-周)——主动学习 + 对抗测试能否压到小时而不牺牲精度?
12. 客户端 instrument 检测(Frida/Xposed 扫描)自身可被 hook——递归自校验(A 查 B,B 查 A,都报服务端)+ 挑战响应能否 <5% 误报检测 hook?

## 关联
- 接入 compat 加密:`compat-crypto-core-design.md`
- 混淆护关键代码:`anti-llm-obfuscation-design.md`
- 战略定论:记忆 `project-anti-llm-obfuscation`
