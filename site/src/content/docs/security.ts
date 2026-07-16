import type { DocPage } from "./types";
import { KERNEL_LAYERS } from "@/content/kernel";

const statusText = {
  live: { zh: "已生效", en: "Live", ja: "有効" },
  "opt-in": { zh: "opt-in", en: "opt-in", ja: "opt-in" },
  built: { zh: "已构建", en: "Built", ja: "実装済み" },
};

export const security: DocPage = {
  slug: "security",
  title: { zh: "7 层安全内核", en: "7-Layer Security Kernel", ja: "7 層セキュリティカーネル" },
  description: {
    zh: "参照 Windows NT 特权体系的 7 层引用监视器；全部 AND 组合，任一层拒绝即拒绝。",
    en: "A 7-layer reference monitor modeled on the Windows NT privilege system; all layers AND together — any one denial denies the call.",
    ja: "Windows NT の特権体系に倣った 7 層のリファレンスモニタ。全層が AND で結合し、いずれか 1 層でも拒否すれば呼び出しは拒否されます。",
  },
  blocks: [
    {
      type: "p",
      text: {
        zh: "安全内核是 MCPClient 的核心约束机制。因为 AI 能做的事危险度极高，所有能力调用都必须穿过 7 层门；这 7 层是 AND 关系，任何一层拒绝就短路返回，并明确标出是哪一层拒的。",
        en: "The security kernel is MCPClient's core constraint mechanism. Because what the AI can do is so dangerous, every capability call must pass through 7 gates; the layers are AND-ed, so any single denial short-circuits and names the layer that refused.",
        ja: "セキュリティカーネルは MCPClient の中核的な制約機構です。AI にできることは非常に危険なため、あらゆる能力呼び出しは 7 つの門を通ります。各層は AND で結合しており、いずれか 1 層でも拒否すれば短絡し、どの層が拒否したかを明示します。",
      },
    },
    {
      type: "callout",
      tone: "info",
      text: {
        zh: "单一决策权威：接口 SeReferenceMonitor.evaluate()，唯一调用点 IoManager.supervise()。这意味着没有任何旁路——包括 AI 运行时自造的工具，也走这同一道门。",
        en: "A single decision authority: the SeReferenceMonitor.evaluate() interface, with IoManager.supervise() as its only call site. That means there is no bypass — even tools the AI builds at runtime go through this same gate.",
        ja: "単一の決定権限：インターフェース SeReferenceMonitor.evaluate() と、その唯一の呼び出し点 IoManager.supervise()。つまり迂回路は存在せず、AI が実行時に自作したツールも同じ門を通ります。",
      },
    },
    { type: "h2", text: { zh: "七层逐层解析", en: "The seven layers", ja: "7 層を 1 層ずつ" }, id: "layers" },
    ...KERNEL_LAYERS.flatMap((l) => [
      {
        type: "h3" as const,
        text: {
          zh: `${l.id} · ${l.name.zh}`,
          en: `${l.id} · ${l.name.en}`,
          ja: `${l.id} · ${l.name.ja}`,
        },
        id: l.id.toLowerCase(),
      },
      {
        type: "p" as const,
        text: {
          zh: `${l.duty.zh}（NT 对标：${l.nt.zh}；现状：${statusText[l.status].zh}）`,
          en: `${l.duty.en} (NT analogue: ${l.nt.en}; status: ${statusText[l.status].en})`,
          ja: `${l.duty.ja}（NT 対応：${l.nt.ja}／状態：${statusText[l.status].ja}）`,
        },
      },
      { type: "code" as const, lang: "text", code: l.code },
    ]),
    { type: "h2", text: { zh: "环号语义", en: "Ring semantics", ja: "リング番号の意味" }, id: "ring" },
    {
      type: "p",
      text: {
        zh: "L2 的环号越小越危险：R-1 HYPERVISOR（最危险，如 eval_java / redefine_class）→ R0 KERNEL → R1 SYSTEM → R2 OBSERVE → R3 USER（只读）。降权是真正的 kill-switch——降权自由，升权则需要 restore token。",
        en: "In L2 the lower the ring number, the more dangerous: R-1 HYPERVISOR (most dangerous — e.g. eval_java / redefine_class) → R0 KERNEL → R1 SYSTEM → R2 OBSERVE → R3 USER (read-only). Dropping privilege is a real kill-switch — dropping is free, raising requires a restore token.",
        ja: "L2 ではリング番号が小さいほど危険です：R-1 HYPERVISOR（最も危険。例：eval_java / redefine_class）→ R0 KERNEL → R1 SYSTEM → R2 OBSERVE → R3 USER（読み取り専用）。権限の降格は本物の kill-switch であり、降格は自由、昇格には restore token が必要です。",
      },
    },
    { type: "h2", text: { zh: "两个正交自保守卫", en: "Two orthogonal self-protection guards", ja: "2 つの直交する自己防衛ガード" }, id: "self-protect" },
    {
      type: "list",
      items: [
        {
          zh: "SeProtectedObjects：禁止 redefine/hook 重写内核类自身，含数组/内部类名归一化以防绕过。",
          en: "SeProtectedObjects: forbids redefining/hooking the kernel classes themselves, with array/inner-class name normalization to prevent bypass.",
          ja: "SeProtectedObjects：カーネルクラス自体の redefine/hook を禁止します。配列や内部クラス名の正規化により迂回を防ぎます。",
        },
        {
          zh: "AgentAccess：把 Instrumentation 句柄从 public 裸抓收成一个受门控的、可审计的 choke point。",
          en: "AgentAccess: turns the Instrumentation handle from a public grab-anywhere into a gated, auditable choke point.",
          ja: "AgentAccess：Instrumentation ハンドルを、どこからでも取れる public な状態から、門で守られた監査可能なチョークポイントへ収れんさせます。",
        },
      ],
    },
    {
      type: "callout",
      tone: "warn",
      text: {
        zh: "架构选择：进程内的层负责纵深防御，而跨地址空间的强隔离由 L1 P-SECURE 承担——把安全决策搬进游戏 JVM 够不到的独立进程。各层的具体启用姿态按部署场景配置。",
        en: "By design, the in-process layers provide defense-in-depth while cross-address-space isolation is carried by L1 P-SECURE, which moves the security decision into a separate process the game JVM cannot reach. The exact activation posture of each layer is configured per deployment.",
        ja: "設計上、プロセス内の各層は多層防御を担い、アドレス空間をまたぐ強い隔離は L1 P-SECURE が担います。セキュリティ決定を、ゲーム JVM が届かない別プロセスへ移すものです。各層の有効化姿勢はデプロイごとに設定します。",
      },
    },
  ],
};
