// 落地页亮点卡片 / 技术栈 / 模块。结构字段不译，文案三语手工翻译。
import type { IconName } from "@/components/mc/PixelIcon";
import type { L10n } from "./glossary";

export interface Feature {
  id: string;
  icon: IconName;
  title: L10n;
  body: L10n;
}

export const FEATURES: Feature[] = [
  {
    id: "live-jvm",
    icon: "endereye",
    title: {
      zh: "把活体 JVM 交给 LLM",
      en: "Hand a live JVM to an LLM",
      ja: "稼働中の JVM を LLM へ",
    },
    body: {
      zh: "用 -javaagent 注入运行中的 Minecraft 1.8.9，把观察、操控、热改、深度调试整条能力谱经 MCP 协议暴露给 AI。一切能力都是运行时经字节码注入挂上的动态 seam——追求最大灵活性，而非工具数量。",
      en: "A -javaagent injects a running Minecraft 1.8.9 and exposes the whole capability spectrum — observe, act, hot-swap, deep-debug — to an AI over MCP. Every capability is a dynamic seam attached at runtime via bytecode injection: the goal is maximum flexibility, not tool count.",
      ja: "-javaagent で稼働中の Minecraft 1.8.9 に注入し、観察・操作・ホットスワップ・深いデバッグという能力の全域を MCP 経由で AI に公開します。あらゆる能力は実行時にバイトコード注入で取り付けた動的なシームであり、狙いはツール数ではなく最大の柔軟性です。",
    },
  },
  {
    id: "kernel",
    icon: "shield",
    title: {
      zh: "7 层 NT 风格权限内核",
      en: "7-layer, NT-style privilege kernel",
      ja: "7 層の NT 風特権カーネル",
    },
    body: {
      zh: "参照 Windows NT 内核特权体系设计的 7 层引用监视器，全部 AND 组合、任一层拒绝即短路并标明「哪层拒的」。所有工具（含 AI 运行时自造的工具）与 HTTP 前门都走同一道门，无法绕过。",
      en: "A 7-layer reference monitor modeled on the Windows NT privilege system: all layers AND together, any one denial short-circuits and names the layer that refused. Every tool — including ones the AI builds at runtime — and the HTTP front door go through the same gate, with no way around it.",
      ja: "Windows NT の特権体系に倣った 7 層のリファレンスモニタです。全層が AND で結合し、いずれか 1 層でも拒否すれば短絡して「どの層が拒否したか」を示します。すべてのツール（AI が実行時に自作したものを含む）も HTTP のフロントドアも同じ門を通り、迂回はできません。",
    },
  },
  {
    id: "jvmti",
    icon: "observer",
    title: {
      zh: "原生 JVMTI 调试器",
      en: "Native JVMTI debugger",
      ja: "ネイティブ JVMTI デバッガ",
    },
    body: {
      zh: "C6 用 LLVM/clang 编出原生 JVMTI agent，提供暂停线程、PopFrame、ForceEarlyReturn、断点、单步、读写局部变量。已在运行中的真实 MC 客户端里实机验证；DLL 缺失时优雅降级为干净拒绝。",
      en: "C6 builds a native JVMTI agent with LLVM/clang — thread suspend, PopFrame, ForceEarlyReturn, breakpoints, single-step, and reading/writing locals. It has been verified on a real, running MC client; when the DLL is missing it degrades gracefully into a clean refusal.",
      ja: "C6 は LLVM/clang でネイティブ JVMTI エージェントをビルドし、スレッド停止・PopFrame・ForceEarlyReturn・ブレークポイント・シングルステップ・ローカル変数の読み書きを提供します。稼働中の実 MC クライアントで実機検証済みで、DLL が無い場合はクリーンな拒否へ穏やかに縮退します。",
    },
  },
  {
    id: "generated-lock",
    icon: "lock",
    title: {
      zh: "AI 生成代码顶格封锁",
      en: "AI-generated code, locked to the max",
      ja: "AI 生成コードを最上位で封鎖",
    },
    body: {
      zh: "AI 用 create_tool/eval 造的工具危险度等同 eval_java，因此无条件盖上最强门：R-1 HYPERVISOR + SYSTEM 完整性 + SE_RUN_GENERATED + CAP_TOOL_CREATE。一次降权即可把所有生成工具全锁死。",
      en: "Tools the AI builds via create_tool/eval are as dangerous as eval_java, so they get the strongest gate unconditionally: R-1 HYPERVISOR + SYSTEM integrity + SE_RUN_GENERATED + CAP_TOOL_CREATE. A single privilege drop locks down every generated tool at once.",
      ja: "AI が create_tool/eval で作るツールは eval_java と同等に危険なので、無条件で最強の門を被せます：R-1 HYPERVISOR + SYSTEM 完全性 + SE_RUN_GENERATED + CAP_TOOL_CREATE。権限を一度下げるだけで、生成ツールをすべて封鎖できます。",
    },
  },
  {
    id: "gui-api",
    icon: "cursor",
    title: {
      zh: "结构化 GUI-as-API",
      en: "Structured GUI-as-API",
      ja: "構造化された GUI-as-API",
    },
    body: {
      zh: "把整个可点击 GUI 反射成可寻址元素，LLM 永不发送像素、只发元素 id；每次操作携带快照 epoch+fingerprint，屏幕变了则拒绝陈旧误点，绕开困扰 computer-use agent 的坐标换算 bug。",
      en: "The entire clickable GUI is reflected into addressable elements; the LLM never sends pixels, only element ids. Each action carries a snapshot epoch+fingerprint, so a changed screen rejects the stale mis-click — sidestepping the coordinate-mapping bugs that plague computer-use agents.",
      ja: "クリック可能な GUI 全体をアドレス可能な要素へリフレクションし、LLM はピクセルではなく要素 id だけを送ります。各操作はスナップショットの epoch+fingerprint を伴うため、画面が変われば古い誤クリックを拒否し、computer-use エージェントを悩ませる座標変換のバグを回避します。",
    },
  },
  {
    id: "compat",
    icon: "quill",
    title: {
      zh: "Ed25519 签名 + TUF 信任链",
      en: "Ed25519 signatures + TUF trust chain",
      ja: "Ed25519 署名 + TUF 信頼チェーン",
    },
    body: {
      zh: "移植 bug 在启动期用已签名的兼容补丁修复，client 源码零改动。补丁靠 Ed25519 验签而非 ring 门控——没有内核密钥就伪造不出能被加载的补丁，构成真正的密码学信任边界（TUF L0-L3）。",
      en: "Porting bugs are fixed at startup by signed compatibility patches, with zero changes to the client source. Patches are gated by Ed25519 verification rather than rings — without the kernel key you cannot forge a loadable patch, forming a genuine cryptographic trust boundary (TUF L0–L3).",
      ja: "移植由来のバグは起動時に署名済みの互換パッチで修正し、client のソースは一切変更しません。パッチは ring ではなく Ed25519 の検証で門を通します。カーネル鍵が無ければロード可能なパッチは偽造できず、本物の暗号学的な信頼境界（TUF L0–L3）を成します。",
    },
  },
];

export interface TechFact {
  label: L10n;
  value: string; // 技术值不译
}

export const TECH_FACTS: TechFact[] = [
  { label: { zh: "运行时", en: "Runtime", ja: "ランタイム" }, value: "JetBrains Runtime 25 + DCEVM" },
  { label: { zh: "游戏", en: "Game", ja: "ゲーム" }, value: "Minecraft 1.8.9 vanilla" },
  { label: { zh: "图形栈", en: "Graphics", ja: "グラフィックス" }, value: "LWJGL3 + qml4j (Skija into MC FBO)" },
  { label: { zh: "协议", en: "Protocol", ja: "プロトコル" }, value: "MCP over socket 127.0.0.1:25599" },
  { label: { zh: "注入", en: "Injection", ja: "注入" }, value: "-javaagent premain" },
  { label: { zh: "REST facade", en: "REST facade", ja: "REST facade" }, value: "127.0.0.1:1337" },
];

export interface Module {
  tierKey: "base" | "spine" | "aux"; // 稳定键，用于配色，不随语言变
  tier: L10n;
  name: string; // 模块名不译
  duty: L10n;
}

export const MODULES: Module[] = [
  {
    tierKey: "base",
    tier: { zh: "平台地基", en: "Platform base", ja: "プラットフォーム基盤" },
    name: "lwjgl2-shim",
    duty: {
      zh: "LWJGL2→LWJGL3 ABI 兼容垫片；只做 ABI 兼容，不含业务/安全权。",
      en: "LWJGL2→LWJGL3 ABI compatibility shim; ABI only, with no business logic or security authority.",
      ja: "LWJGL2→LWJGL3 の ABI 互換シム。ABI 互換のみを担い、業務ロジックやセキュリティ権限は持ちません。",
    },
  },
  {
    tierKey: "spine",
    tier: { zh: "设计骨架", en: "Design spine", ja: "設計骨格" },
    name: "core",
    duty: {
      zh: "NT 内核本体：MCP server + 7 层安全内核 + 能力包 + 原生 JVMTI 调试器。",
      en: "The NT kernel itself: MCP server + 7-layer security kernel + capability packs + native JVMTI debugger.",
      ja: "NT カーネル本体：MCP サーバ + 7 層セキュリティカーネル + 能力パック + ネイティブ JVMTI デバッガ。",
    },
  },
  {
    tierKey: "spine",
    tier: { zh: "设计骨架", en: "Design spine", ja: "設計骨格" },
    name: "board",
    duty: {
      zh: "客户端功能框架（PCB 隐喻）；与 core 零硬依赖，可各自单独启动。",
      en: "A client feature framework (PCB metaphor); zero hard dependency on core, and each can start on its own.",
      ja: "クライアント機能フレームワーク（PCB のメタファー）。core への硬い依存はなく、各々が単独で起動できます。",
    },
  },
  {
    tierKey: "spine",
    tier: { zh: "设计骨架", en: "Design spine", ja: "設計骨格" },
    name: "client",
    duty: {
      zh: "MC 1.8.9 vanilla 映射，反射/GUI 字段名的唯一真相源。",
      en: "The MC 1.8.9 vanilla mapping — the single source of truth for reflection and GUI field names.",
      ja: "MC 1.8.9 の vanilla マッピング。リフレクションや GUI のフィールド名に関する唯一の真実源です。",
    },
  },
  {
    tierKey: "aux",
    tier: { zh: "可拆卸辅助", en: "Detachable aux", ja: "着脱可能な補助" },
    name: "pg",
    duty: {
      zh: "PatchGuard 加固库，@Guarded 标注驱动的字节码加固。",
      en: "PatchGuard hardening library — @Guarded-annotation-driven bytecode hardening.",
      ja: "PatchGuard 堅牢化ライブラリ。@Guarded アノテーション駆動のバイトコード堅牢化を行います。",
    },
  },
  {
    tierKey: "aux",
    tier: { zh: "可拆卸辅助", en: "Detachable aux", ja: "着脱可能な補助" },
    name: "dwm",
    duty: {
      zh: "UI 子系统：qml4j 场景图画进 MC 的 GuiScreen。不是热切换后端。",
      en: "UI subsystem: a qml4j scene graph painted into MC's GuiScreen. Not a hot-swappable backend bus.",
      ja: "UI サブシステム。qml4j のシーングラフを MC の GuiScreen に描画します。ホットスワップ可能なバックエンドではありません。",
    },
  },
];
