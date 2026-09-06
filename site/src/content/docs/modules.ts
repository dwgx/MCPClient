import type { DocPage } from "./types";

export const modules: DocPage = {
  slug: "modules",
  title: { zh: "模块与平台 SPI", en: "Modules & Platform SPI", ja: "モジュールとプラットフォーム SPI" },
  description: {
    zh: "三层模块结构，以及 core 与 board 如何零硬依赖协作。",
    en: "The three-tier module structure, and how core and board collaborate with zero hard dependency.",
    ja: "3 層のモジュール構造と、core と board が硬い依存なしに協調する仕組み。",
  },
  blocks: [
    {
      type: "p",
      text: {
        zh: "模块划分的判据只有一句：删了它，其余骨架还能否独立编译运行？据此分三层。",
        en: "There is a single test for module boundaries: remove it, and can the rest of the spine still compile and run on its own? That yields three tiers.",
        ja: "モジュールの境界を分ける基準はただ一つ——それを消しても、残りの骨格は単独でコンパイル・実行できるか。これに従って 3 層に分けます。",
      },
    },
    { type: "h2", text: { zh: "平台地基", en: "Platform base", ja: "プラットフォーム基盤" }, id: "platform" },
    {
      type: "p",
      text: {
        zh: "lwjgl2-shim：LWJGL2→LWJGL3 的 ABI 兼容垫片。它与 client 共生——删了它 client 编不了。只做 ABI 兼容，不含任何业务或安全权。",
        en: "lwjgl2-shim: the LWJGL2→LWJGL3 ABI compatibility shim. It is symbiotic with client — remove it and client won't compile. It does ABI compatibility only, with no business logic or security authority.",
        ja: "lwjgl2-shim：LWJGL2→LWJGL3 の ABI 互換シム。client と共生関係にあり、これを消すと client はコンパイルできません。ABI 互換のみを担い、業務ロジックやセキュリティ権限は一切持ちません。",
      },
    },
    { type: "h2", text: { zh: "设计骨架（恒为 3）", en: "The design spine (always 3)", ja: "設計骨格（常に 3 つ）" }, id: "spine" },
    {
      type: "list",
      items: [
        {
          zh: "core — NT 内核本体：MCP server + 7 层安全内核 + 能力包 + 原生 JVMTI 调试器；唯一可持有安全决策权。",
          en: "core — the NT kernel itself: MCP server + 7-layer security kernel + capability packs + native JVMTI debugger; the only module allowed to hold security decision authority.",
          ja: "core — NT カーネル本体：MCP サーバ + 7 層セキュリティカーネル + 能力パック + ネイティブ JVMTI デバッガ。セキュリティ決定権限を持てる唯一のモジュール。",
        },
        {
          zh: "board — 客户端功能框架（PCB 隐喻）；与 core 零硬依赖，可各自单独启动、互相拉起、各自满权。",
          en: "board — a client feature framework (PCB metaphor); zero hard dependency on core, each can start on its own, bring the other up, and run at full authority.",
          ja: "board — クライアント機能フレームワーク（PCB のメタファー）。core への硬い依存はなく、各々が単独で起動し、互いを立ち上げ、それぞれ完全な権限で動けます。",
        },
        {
          zh: "client — MC 1.8.9 vanilla 映射，是反射/GUI 字段名的唯一真相源；保持 vanilla，不被污染。",
          en: "client — the MC 1.8.9 vanilla mapping, the single source of truth for reflection and GUI field names; kept vanilla and never polluted.",
          ja: "client — MC 1.8.9 の vanilla マッピング。リフレクションや GUI のフィールド名に関する唯一の真実源であり、vanilla を保ち、汚染されません。",
        },
      ],
    },
    {
      type: "callout",
      tone: "info",
      text: {
        zh: "core 与 board 互不 import，只经纯反射的 Port + Backplane 服务发现互相拉起。删掉任一方，另一方照样编译照样跑。这是「平级双子系统」设计。",
        en: "core and board never import each other; they bring each other up purely via reflection through Port + Backplane service discovery. Remove either one and the other still compiles and runs. This is the \"peer twin-subsystem\" design.",
        ja: "core と board は互いに import せず、純粋なリフレクションによる Port + Backplane のサービスディスカバリだけで互いを立ち上げます。どちらを消しても、もう一方はコンパイルも実行もできます。これが「対等なツイン・サブシステム」設計です。",
      },
    },
    { type: "h2", text: { zh: "core 内部的 NT Executive 分层", en: "core's internal NT Executive layering", ja: "core 内部の NT Executive レイヤリング" }, id: "nt-executive" },
    {
      type: "table",
      head: [
        { zh: "包", en: "Package", ja: "パッケージ" },
        { zh: "职责", en: "Responsibility", ja: "責務" },
      ],
      rows: [
        [
          { zh: "se", en: "se", ja: "se" },
          {
            zh: "安全决策（引用监视器、环、完整性、特权、能力）",
            en: "Security decisions (reference monitor, rings, integrity, privilege, capability)",
            ja: "セキュリティ決定（リファレンスモニタ、リング、完全性、特権、ケイパビリティ）",
          },
        ],
        [
          { zh: "ob", en: "ob", ja: "ob" },
          { zh: "对象句柄（L6）", en: "Object handles (L6)", ja: "オブジェクトハンドル（L6）" },
        ],
        [
          { zh: "io", en: "io", ja: "io" },
          {
            zh: "IRP 分发 + 双门面（MCP / HTTP）",
            en: "IRP dispatch + dual facades (MCP / HTTP)",
            ja: "IRP ディスパッチ + 二つのファサード（MCP / HTTP）",
          },
        ],
        [
          { zh: "alpc", en: "alpc", ja: "alpc" },
          {
            zh: "P-SECURE（L1 独立决策进程）",
            en: "P-SECURE (the L1 separate decision process)",
            ja: "P-SECURE（L1 の独立決定プロセス）",
          },
        ],
        [
          { zh: "ke", en: "ke", ja: "ke" },
          { zh: "内核事件 + 时钟", en: "Kernel events + clock", ja: "カーネルイベント + クロック" },
        ],
        [
          { zh: "ldr / mm / flt / kd / ps / cm", en: "ldr / mm / flt / kd / ps / cm", ja: "ldr / mm / flt / kd / ps / cm" },
          {
            zh: "热加载 / 深访问 / hook 与 seam / JVMTI / 合成 / 自省",
            en: "Hot-load / deep-access / hooks & seams / JVMTI / synthesis / introspection",
            ja: "ホットロード / 深部アクセス / hook とシーム / JVMTI / 合成 / イントロスペクション",
          },
        ],
        [
          { zh: "compat", en: "compat", ja: "compat" },
          {
            zh: "启动期签名补丁系统",
            en: "The startup-time signed patch system",
            ja: "起動時の署名付きパッチシステム",
          },
        ],
        [
          { zh: "drivers.*", en: "drivers.*", ja: "drivers.*" },
          {
            zh: "gui / video / world / store / narrative / action 驱动",
            en: "gui / video / world / store / narrative / action drivers",
            ja: "gui / video / world / store / narrative / action の各ドライバ",
          },
        ],
      ],
    },
    { type: "h2", text: { zh: "可拆卸辅助", en: "Detachable auxiliaries", ja: "着脱可能な補助" }, id: "auxiliary" },
    {
      type: "p",
      text: {
        zh: "pg（PatchGuard 加固库）与 dwm（UI 子系统，qml4j 画进 Minecraft 自己的 GuiScreen）挂在骨架之外，零安全决策权。删了它们，三个设计骨架照编照跑。GL / ImGui / Skiko 后端已拆除，不是现行基板。",
        en: "pg (the PatchGuard hardening library) and dwm (the UI subsystem: qml4j painted into Minecraft's own GuiScreen) hang outside the spine with zero security decision authority. Remove them and the three design-spine modules still compile and run. The GL / ImGui / Skiko backends were removed; they are not the current substrate.",
        ja: "pg（PatchGuard 堅牢化ライブラリ）と dwm（UI サブシステム。qml4j が Minecraft 自身の GuiScreen に描画）は骨格の外にぶら下がり、セキュリティ決定権限を持ちません。これらを消しても、3 つの設計骨格はコンパイルも実行もできます。GL / ImGui / Skiko バックエンドは撤去済みで、現行の基板ではありません。",
      },
    },
  ],
};
