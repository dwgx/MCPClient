# 会话令状 — PHASE 0（整段复制到 Claude Code）

---

你是 MCPClient 仓库的实现 agent（Claude Code）。

## 本会话唯一任务

**只做 PHASE 0：compat 补丁真 arm（KI-4 可生效）。**
做完停。禁止开始 PHASE T / P / W / A / E。

## 必读（按序）

1. 仓库根 `CLAUDE.md`（铁律；禁止改本文件）
2. `docs/history/design-briefs/perception-control/BOOK-method.md`（怎么打勾）
3. `docs/history/design-briefs/perception-control/PROGRESS.md`（进度板）
4. `docs/history/design-briefs/perception-control-max-roadmap.md` 的 **§0、§3.2、PHASE 0 全表、FORKS-S1**
5. codegraph / 读：`Compat`、`Ed25519PatchSigner`、`TrustAnchors`、`PatchCanonicalizer`、`Ki4LocalServerChannelPatch`、`CompatEngine`

## 产品决策（已拍板）

- **方案 A**：Ed25519 真签；公钥进运行时 TrustAnchors；私钥**永不进 git**
- 空 TrustAnchors 路径测试仍须 **不 arm**（fail-safe）
- 禁止默认 `trustUnsigned` 当长期方案
- 禁止改 `client/` 源码修 KI-4

## 任务 ID（做完在 PROGRESS.md 勾选）

- P0.1 钥 + 公钥进 TrustAnchors + 私钥 gitignore/本地路径
- P0.2 可重复签发（脚本或小工具，调现有 `Ed25519PatchSigner`）
- P0.3 KI-4 带有效 signature；有钥时 arm；篡改/错签不 arm（非空转测试）
- P0.4 `-Dmcp.compat.ki4=false` 仍可关
- P0.5 live 进世界 → 笔记写 `pending-owner`，**不要假装你已 live 验过**（除非环境真验了）
- P0.6 更新 `docs/history/project/known-issues.md` KI-4 状态（诚实：armed vs live）

## 强制收尾（缺一不可）

1. `./mvnw -pl core test`（或至少 compat 相关全绿）通过后再 commit
2. 打开 `PROGRESS.md`：完成的 ID `[ ]`→`[x]`；更新「当前指针」状态；追加「实现笔记」一节（HEAD、测试、FORK、风险）
3. 可更新 `.ai-notes/STATUS.md` 一句话 + 测试数
4. 汇报用：

```
PHASE 0 完成摘要
- 完成 ID:
- 未完成 ID:
- 测试:
- HEAD:
- PROGRESS 已更新: yes/no
- 风险:
```

## 铁律

- 无 AI 署名进 commit
- 先测再提交
- 安全类有罪推定；不削弱空 keyring 默认
- 读代码优先 codegraph

现在开始 PHASE 0。
