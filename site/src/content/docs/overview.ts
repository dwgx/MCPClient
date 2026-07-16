import type { DocPage } from "./types";

export const overview: DocPage = {
  slug: "overview",
  title: { zh: "概览", en: "Overview", ja: "概要" },
  description: {
    zh: "MCPClient 是什么，以及它的设计北极星。",
    en: "What MCPClient is, and the north star behind its design.",
    ja: "MCPClient とは何か、そしてその設計の指針。",
  },
  blocks: [
    {
      type: "p",
      text: {
        zh: "MCPClient（代号 the Kernel）是一个系统级控制平台：它用 -javaagent 注入一个活着的 Minecraft 1.8.9 客户端，把「观察游戏状态 / 收发封包 / 驱动 GUI / 热替换字节码 / 原生 JVMTI 断点单步」这一整条能力谱，经 Model Context Protocol 交给 AI 使用。",
        en: "MCPClient (codename: the Kernel) is a system-level control platform. A -javaagent injects a live Minecraft 1.8.9 client and hands the whole capability spectrum — observing game state, sending/receiving packets, driving the GUI, hot-swapping bytecode, and native JVMTI breakpoints/single-stepping — to an AI over the Model Context Protocol.",
        ja: "MCPClient（コードネーム the Kernel）はシステムレベルの制御プラットフォームです。-javaagent で稼働中の Minecraft 1.8.9 クライアントに注入し、「ゲーム状態の観察／パケットの送受信／GUI の駆動／バイトコードのホットスワップ／ネイティブ JVMTI のブレークポイントとシングルステップ」という能力の全域を、Model Context Protocol 経由で AI に渡します。",
      },
    },
    {
      type: "callout",
      tone: "info",
      text: {
        zh: "北极星：把控制暴露给 LLM 是最重要的驱动入口，但不是本质。本质是「对活体 JVM 的系统级完全控制」——AI 只是驱动它的旗舰方式，也可由脚本 / GUI / 直接 API 驱动。",
        en: "North star: exposing control to an LLM is the most important entry point — but not the essence. The essence is total system-level control over a live JVM; an AI is merely its flagship way to drive it, and scripts / a GUI / a direct API can drive it too.",
        ja: "指針：制御を LLM に公開することは最も重要な入口ですが、本質ではありません。本質は「稼働中の JVM に対するシステムレベルの完全制御」であり、AI はそれを駆動する旗艦的な手段にすぎず、スクリプトや GUI、直接 API でも駆動できます。",
      },
    },
    { type: "h2", text: { zh: "为什么需要一道内核", en: "Why a kernel is needed", ja: "なぜカーネルが必要か" }, id: "why-kernel" },
    {
      type: "p",
      text: {
        zh: "因为这种能力密度极高——AI 可以热改任意方法、在任意线程下断点、发送任意协议封包——所有工具调用都必须先穿过同一道门：一套参照 Windows NT 内核特权体系设计的 7 层引用监视器（reference monitor），任一层拒绝即整体拒绝。",
        en: "Because the capability density is so high — the AI can hot-swap any method, set breakpoints on any thread, and send any protocol packet — every tool call must first pass through the same gate: a 7-layer reference monitor modeled on the Windows NT kernel privilege system, where any single layer's denial denies the whole call.",
        ja: "この能力密度が極めて高い（AI は任意のメソッドをホットスワップし、任意のスレッドにブレークポイントを置き、任意のプロトコルパケットを送れる）ため、すべてのツール呼び出しはまず同一の門を通らねばなりません。それは Windows NT カーネルの特権体系に倣って設計された 7 層のリファレンスモニタであり、いずれか 1 層でも拒否すれば呼び出し全体が拒否されます。",
      },
    },
    {
      type: "p",
      text: {
        zh: "设计目标可以概括为三句：内核调试器级的 AI 全控、最大灵活性、杜绝「宣传了却是死的工具」。每一个暴露给 AI 的能力都必须是真实可用、经过验证的，而不是摆设。",
        en: "The design goals boil down to three: kernel-debugger-grade full control for the AI, maximum flexibility, and no \"advertised but dead\" tools. Every capability exposed to the AI must be genuinely usable and verified — not a decoration.",
        ja: "設計目標は 3 つに集約されます。カーネルデバッガ級の AI 全制御、最大の柔軟性、そして「宣伝されているのに動かないツール」を排すること。AI に公開されるあらゆる能力は、飾りではなく、実際に使えて検証済みでなければなりません。",
      },
    },
    { type: "h2", text: { zh: "三个层次的定位", en: "Three levels of positioning", ja: "3 段階の位置づけ" }, id: "positioning" },
    {
      type: "list",
      items: [
        {
          zh: "超短：把活着的 Minecraft JVM 变成 AI 可达、内核调试器级控制的可编程基底。",
          en: "Shortest: turn a live Minecraft JVM into a programmable substrate an AI can reach, with kernel-debugger-grade control.",
          ja: "最短：稼働中の Minecraft JVM を、AI が到達でき、カーネルデバッガ級の制御を伴うプログラム可能な基盤に変える。",
        },
        {
          zh: "标准：把运行中的 MC 1.8.9 客户端经 MCP 暴露给 LLM，AI 能观察、操控、热改、深度调试运行中的游戏，而每次调用都受 7 层特权内核约束。",
          en: "Standard: expose a running MC 1.8.9 client to an LLM over MCP; the AI can observe, act on, hot-swap, and deeply debug the running game, with every call constrained by the 7-layer privilege kernel.",
          ja: "標準：稼働中の MC 1.8.9 クライアントを MCP 経由で LLM に公開し、AI が動いているゲームを観察・操作・ホットスワップ・深くデバッグできる。ただし各呼び出しは 7 層の特権カーネルに制約される。",
        },
        {
          zh: "完整：一个系统级控制平台，用字节码注入把整条能力谱交给 AI，并用一套 NT 风格引用监视器把所有调用收束到同一道可审计的门。",
          en: "Full: a system-level control platform that hands the entire capability spectrum to the AI via bytecode injection, and funnels every call through a single auditable gate with an NT-style reference monitor.",
          ja: "完全版：バイトコード注入で能力の全域を AI に渡し、NT 風のリファレンスモニタによってすべての呼び出しを単一の監査可能な門へ収れんさせる、システムレベルの制御プラットフォーム。",
        },
      ],
    },
    { type: "h2", text: { zh: "接下来读什么", en: "What to read next", ja: "次に読むもの" }, id: "next" },
    {
      type: "p",
      text: {
        zh: "如果你关心「AI 能被允许做什么、如何被约束」，先读《7 层安全内核》。如果你关心「AI 具体能调用哪些能力」，读《能力包 C1-C9》与《MCP 工具参考》。如果你关心工程结构，读《模块与平台 SPI》。",
        en: "If you care about \"what the AI is allowed to do and how it is constrained,\" start with the 7-Layer Security Kernel. If you care about \"which capabilities the AI can actually call,\" read Capability Packs C1–C9 and the MCP Tools Reference. If you care about the engineering structure, read Modules & Platform SPI.",
        ja: "「AI に何が許され、どう制約されるか」が気になるなら、まず『7 層セキュリティカーネル』を。「AI が具体的にどの能力を呼べるか」が気になるなら『能力パック C1–C9』と『MCP ツールリファレンス』を。エンジニアリング構造が気になるなら『モジュールとプラットフォーム SPI』をお読みください。",
      },
    },
  ],
};
