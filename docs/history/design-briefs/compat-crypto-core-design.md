---
doc: design-compat-crypto
title: 设计简报 — compat 加密核心(填 07 的待定附录)
layer: reference
status: draft
updated: 2026-07-12
parent: ../README.md
next:
  - path: ../architecture/07-COMPAT-SHIM.md
    when: 你要看被本简报填充的 compat 系统架构(附录:加密核心待定)
read_if: 你要实现/review compat 补丁的加密核心(密钥树/签名/验签/split-execution)。这是设计,未落代码。
---

# 设计简报:compat 加密核心

> 填 `07-COMPAT-SHIM.md` 的「附录:加密核心(待定)」。基于 27-agent 研究
> (`anti-llm-obfuscation-and-compat-crypto-research.md`)+ dwgx 2026-07-12 拍板:
> **信任根=服务端派发 seed;上选择性 split-execution。** status: draft,review 后才落代码。

## 1. 信任根(dwgx 定:服务端派发)

补丁**签名权在服务端**,客户端只**验签**。这天然满足"客户端无单一可提取主密钥":
- 服务端持 **signing master**(客户端上不存在),给每个进库补丁签名。
- 客户端持 **verification material**——分两档(见 §4 split-execution):
  - 轻档:内嵌(混淆保护的)验签公料,离线可验(买时间,非信息论墙)。
  - 重档:关键补丁的验签走服务端权威应答(真墙,客户端无完整逻辑)。

> 与研究档案里"passphrase→Argon2id"方案的差异:那是纯客户端对称树;dwgx 选了服务端派发,
> 所以主签名密钥根本不在客户端 → 直接消灭"主钥可提取"这一类。Argon2id 仅在"服务端派 seed
> 后客户端本地展开子钥"时可选用(抬高本地爆破成本),非信任根。

## 2. 密钥树(HKDF 域分离)

服务端侧签名 + 客户端侧派生验签料,统一用 HKDF(RFC 5869,库:patrickfav/hkdf):

```
server signing master (仅服务端)
  └─ HKDF-Extract(salt=协议固定串, IKM=master) -> PRK
       └─ per-capability: HKDF-Expand(PRK, info="Cn.<name>") -> capability_root
            └─ per-patch:  HKDF-Expand(capability_root, info="patchId||version||purpose")
                              -> patch_sign_key   (服务端签)
                              -> patch_verify_material (客户端验)
```

- `info` 注入完整上下文(capability 编号/variant/patchId/用途),**单射编码防规范化碰撞**。
- 单个 patch 料泄露**不波及全树**(层级隔离)。

## 3. 签名 / 验签 / 加载(填 PatchSigner 占位)

- **签名(服务端)**:HMAC-SHA256(patch_sign_key, canonical(manifest) || contentHash || bytecode),
  tag 截 128-bit。覆盖字段 = patchId + version + targetClass + platformCondition + kiRef +
  contentHash(全部 manifest 身份字段,防挪用)。
- **验签(客户端 CompatEngine)**:`MessageDigest.isEqual` 常量时间比较,防 timing 侧信道。
- **补丁体加密(可选)**:ChaCha20(patch_decrypt_key, 96-bit 随机 nonce),nonce 存 manifest。
  多数移植补丁逻辑不算秘密,可只签不加密;涉密补丁才加密。
- **加载流程(接 07 的启动期打补丁)**:premain 取补丁 → HKDF 派生验料 → HMAC 常量时间验签
  → (可选 ChaCha20 解密) → ASM transform → **立即 zeroize 密钥料(Arrays.fill)** → 注册 transformer。
- **保护校验代码本身**:HKDF/HMAC 派生与比较这段用 §见混淆简报 的 VM 虚拟化/OLLVM 护住(<5% 关键函数)。

## 4. 选择性 split-execution(dwgx 定:关键走服务端权威)

补丁按敏感度分档,决定验签在哪:

| 档 | 谁 | 验签方式 | 性质 |
|---|---|---|---|
| 普通移植补丁(KI-1/KI-4 类) | 客户端 | 内嵌验料离线验签 | 买时间(混淆+签名),离线可跑 |
| 关键 capability / license 门槛 | 服务端权威 | 客户端把 patchId+nonce 发服务端,服务端应答"许可+一次性解密料/放行令牌" | 真墙:关键决策逻辑不在客户端 |

- 服务端权威应答用**新鲜 nonce + 短命令牌**(移动目标),防重放/内存快照复用。
- 网络不可达时的**故障模式**是待定 gap(见 §6):普通档可离线降级;关键档 fail-closed(拒绝放行)还是宽限?

## 5. 诚实边界(必须随方案存在,别当城墙吹)

- R-1 对手能 dump 解密窗口明文、内存中的派生料、验签逻辑——**客户端档只买时间**。
- HMAC 防被动篡改/伪造补丁有效;防同地址空间的 R-1 无效。
- 混淆护住校验代码只是 ×10²-10³ 拖慢,非密码学保证。
- **唯一信息论硬墙 = 关键档的 split-execution**(签名权+关键逻辑在服务端)。这正是 dwgx 选它的原因。

## 6. 待定 gap(实现前需 dwgx 拍板)
1. 服务端形态:独立服务?复用现有 MCP 后端?license 服务器?部署/成本。
2. 关键档故障模式:服务端不可达时 fail-closed vs 宽限窗口。
3. 补丁体是否默认加密(只签 vs 签+ChaCha20),按补丁敏感度分类规则。
4. 密钥轮换:master 泄露响应 + 定期轮换对已部署补丁的兼容性。
5. 服务端权威令牌的 TTL / 绑定(设备/会话/时间)。

## 7. 验收线
- HKDF/HMAC 跑 RFC 5869 + NIST CAVP 向量;isEqual timing t-test(p>0.05)。
- 伪造 tag → 拒;改 patchId 复用旧 tag → 拒(绑定验证);重放旧令牌 → 拒。
- 服务端不可达 → 关键档按定义的故障模式行为(测试锁定)。
- 每补丁 obfuscated+protected 版本与原版功能等价(golden 回归)。

## 关联
- 填充目标:`../architecture/07-COMPAT-SHIM.md` 附录。
- 技术来源 + 诚实边界:`anti-llm-obfuscation-and-compat-crypto-research.md`。
- 混淆护校验代码:`anti-llm-obfuscation-design.md`。
