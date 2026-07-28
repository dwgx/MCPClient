# 密钥仪式:两层信任链怎么重建

2026-07-28。本文记录 compat 补丁的签名信任模型、重建它的两个工具,以及**私钥在哪、丢了会怎样**。

先说最要紧的一条:

> **两把私钥在 `~/.mcp-keys/`,仓库外,权限 600。自己备份。**
> 丢了没有恢复路径 —— 必须重走整套仪式,并重签**所有**补丁。

---

## 0. 为什么会有这份文档

上一轮的结论是"KI-11 不 arm,因为私钥不在这台机器上,而这台机器不做签名"。
本轮用户要求新建密钥、旧的全部作废重签。做的时候发现**信任模型比预期深一层**,
而那一层没有任何工具 —— 所以先补工具,再做仪式。

---

## 1. 信任链是两层 TUF,不是一把钥匙

最初我以为换掉 `kernel-ed25519.pub` 再重签就完事。**换完三个补丁全部签名失败。**

诊断结果是决定性的:密钥对本身匹配(`raw pair verifies: true`)、公钥也确实是新的,
但补丁依然 `signature not trusted`。根因:

```
baked root key  (root-ed25519.pub,随包)
     │ 签署
     ▼
root-metadata.json          ← targetsKeys 声明:哪个 kernel 公钥有权签补丁
     │ 授权
     ▼
kernel 密钥  ──签──▶  KI-1 / KI-4 / KI-11
```

`Compat.defaultTrustAnchors()` **不直接读** kernel 公钥。它走
`RootTrust.effectiveAnchors()` → `TufTrust.effectiveAnchors()`,从 `root-metadata.json`
**派生**锚点 —— 而那份文档里内嵌的仍是旧 kernel 公钥,且它由 root 私钥签署。

所以只换下层等于:新 kernel 密钥签出的签名,验签时对不上文档授权的那把钥匙。
**诊断信息只有 `signature not trusted` 一句**,这也是整套仪式要自带验证的原因。

### 失败是向正确方向的

链条任何一环缺失或不匹配,`RootTrust` 返回空锚点,**一个补丁都不 arm**。
这是 fail-closed,不是 bug。

---

## 2. 两个新工具(项目原先都没有)

`PatchSignerCli` 只签名,不生成密钥,也不管 root 那一层。所以补了两个。

### `KernelKeygenCli` —— 生成 kernel 密钥对

```bash
java -cp core/target/core-1.8.9-all.jar \
  net.marcloud.mcp.core.compat.tools.KernelKeygenCli --out ~/.mcp-keys
```

两处设计是有意的:

- **两种编码不能搞反**。私钥写 **PKCS#8**(`PatchSignerCli --privkey` 读的),
  公钥写 **X.509/SPKI**(`KernelTrustAnchor` 读的)。搞反的话两个文件都是看起来正常的 base64,
  失败信息仍然只有 `signature not trusted`。
- **写盘前先自检**。生成的密钥对跑一次真实 sign/verify 往返,并检查签名是 64 字节
  (`Ed25519PatchSigner` 强制的长度)。一个不可用的密钥否则要等公钥进了构建之后才暴露。

拒绝覆盖已有密钥,除非 `--force` —— 静默替换会让所有已签补丁失效,而症状出现得很晚。

### `RootCeremonyCli` —— 重建并签署 root 文档

```bash
java -cp core/target/core-1.8.9-all.jar \
  net.marcloud.mcp.core.compat.tools.RootCeremonyCli \
  --keys ~/.mcp-keys \
  --resources core/src/main/resources/net/marcloud/mcp/core/compat
```

它生成新 root 密钥对、把 **kernel 公钥写进 `targetsKeys`**、用 root 私钥签署整份文档。

两条不重复实现的原则:

- **规范化输入不重写**。调 `RootMetadata.signingBytes()` **本身**
  (域标签 + 长度前缀 + keyId 排序)。手抄第二份编码是会静默漂移的那种重复。
- **用验证端自己的门禁自检**。跑 `TufTrust.isRootSignedToBakedTrust`,再确认派生出的锚点
  真的含 `mcp-kernel-ed25519-v1`。发出一份验不过的文档比失败更糟。

签名文件用**标准 base64**(不是 URL-safe)—— 补丁签名是 URL-safe 的,两者混用是静默失败。

---

## 3. 完整流程

```bash
# 1. 生成 kernel 密钥对
./mvnw -q -pl core -am package -DskipTests
java -cp core/target/core-1.8.9-all.jar \
  net.marcloud.mcp.core.compat.tools.KernelKeygenCli --out ~/.mcp-keys

# 2. 把新公钥装进随包资源
cp ~/.mcp-keys/kernel-ed25519.pub \
   core/src/main/resources/net/marcloud/mcp/core/compat/kernel-ed25519.pub

# 3. 生成 root 密钥并签署授权文档(会写 root-ed25519.pub / root-metadata.json / .sig)
./mvnw -q -pl core -am package -DskipTests
java -cp core/target/core-1.8.9-all.jar \
  net.marcloud.mcp.core.compat.tools.RootCeremonyCli \
  --keys ~/.mcp-keys \
  --resources core/src/main/resources/net/marcloud/mcp/core/compat

# 4. 重建,否则下一步读到的是旧公钥
./mvnw -q -pl core -am package -DskipTests

# 5. 重签每一个补丁
for p in Ki1MipmapZeroFillPatch Ki4LocalServerChannelPatch Ki11DwmHotkeyPatch; do
  scripts/sign-patch.sh --privkey ~/.mcp-keys/kernel-ed25519.key.b64 --patch $p
done
# 把每个输出的签名粘进对应补丁类的 KERNEL_SIGNATURE

# 6. 重建 + 真机确认
./mvnw -q -pl core -am package -DskipTests
./scripts/run-mcp.sh   # 必须看到 "N patch(es) armed, 0 skipped"
```

### 第 4 步不能省

`sign-patch.sh` 用 `core/target/classes` 里的公钥做自检。装了新公钥但没重建,
它会用旧公钥验新签名 —— **这个坑我踩了两次**,现象是三个补丁全部
`FAIL: the produced signature does NOT verify`。

---

## 4. 本轮的实际产物

| 项 | 值 |
|---|---|
| kernel 公钥 | `MCowBQYDK2VwAyEAzoQe4VBHoL2rqMCN1NoGLLsDm5M99B1CEJeFYlq+MCY=` |
| root 文档版本 | 2(不复用 1 —— 复用旧版本号与被替换的文档无法区分) |
| 重签的补丁 | KI-1、KI-4、KI-11 |
| 真机结果 | `3 patch(es) armed, 0 skipped` |
| 私钥位置 | `~/.mcp-keys/{kernel,root}-ed25519.key.b64`,权限 600 |

**旧密钥全部作废**,这是换 root 的固有代价,不是可以规避的副作用。

---

## 5. 进仓库的与不进的

进仓库(全是公开材料):
`kernel-ed25519.pub`、`root-ed25519.pub`、`root-metadata.json`、`root-metadata.sig`,
以及三个补丁类里的签名常量。

**绝不进仓库**:两个 `*.key.b64`。它们在 `~/.mcp-keys/`,而那不在任何工作树里。
提交前核对过 `git status`,没有任何 key/pem/secret 命名的文件。

嵌入公钥不授予任何伪造能力 —— 这正是能把它随包发布的原因。

---

## 6. 测试守什么

`KernelKeygenCliTest`(5 项,headless):

- 私钥必须能按 **PKCS#8** 解码、公钥按 **X.509/SPKI** —— 用**真实消费者的同一个读取路径**,
  而不是"文件存在"。
- 两个文件必须是**同一对**,用真实签名往返证明。
- 无 `--force` 必须拒绝覆盖,且拒绝后原文件**逐字节未动**。
- 自检必须能拒掉拼错的密钥对(拿两对密钥的一半拼起来)。
- POSIX 上私钥不可被 group/other 读。

一处断言写得过严并被实测纠正:JDK 报的算法名是 **`EdDSA`**(家族名)而不是 `Ed25519`(曲线名),
断言曲线名会在一把正确的密钥上失败。曲线由自检的 64 字节签名长度和配对测试钉住。

`Ki11SigningContractTest` 见 `entry-point.md` §4。

---

## 7. 已知边界

- **root 密钥没有轮换工具**。再换 root 就是重跑 `RootCeremonyCli`,而那会作废所有补丁签名。
  阈值是 1(`rootThreshold`),所以也没有多签或密钥恢复。
- **`root-metadata.json` 是手写 JSON**。core 只有 JSON 读取器、没有写入器,所以
  `RootCeremonyCli` 拼字符串。key 的顺序不影响验签 —— `signingBytes()` 按 keyId 排序,
  这正是让编码与文本排布无关的设计。
- **KI-10 未变**:签名绑定 manifest 的**标签**,不绑定实际执行的 transform 字节;
  `contentHash` 仍由作者提供。本轮既没有收窄也没有放宽这个缺口。
