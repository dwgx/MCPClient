import type { DocPage } from "./types";

export const architecture: DocPage = {
  slug: "architecture",
  title: { zh: "架构总览", en: "Architecture", ja: "アーキテクチャ" },
  description: {
    zh: "注入方式、进程/端口拓扑，以及数据如何流经内核。",
    en: "How injection works, the process/port topology, and how data flows through the kernel.",
    ja: "注入の仕組み、プロセス／ポートのトポロジ、そしてデータがカーネルをどう流れるか。",
  },
  blocks: [
    { type: "h2", text: { zh: "注入方式", en: "Injection", ja: "注入の仕組み" }, id: "injection" },
    {
      type: "p",
      text: {
        zh: "MCPClient 以 Java agent 形式注入游戏进程。CoreAgent 在 premain/agentmain 阶段捕获 Instrumentation 句柄，StartupAdvice 织入 Minecraft.startGame() 的尾点，在游戏就绪时点火 McpCore。",
        en: "MCPClient is injected into the game process as a Java agent. CoreAgent captures the Instrumentation handle during premain/agentmain, and StartupAdvice weaves into the tail of Minecraft.startGame() to ignite McpCore once the game is ready.",
        ja: "MCPClient は Java エージェントとしてゲームプロセスに注入されます。CoreAgent は premain/agentmain の段階で Instrumentation ハンドルを捕捉し、StartupAdvice が Minecraft.startGame() の末尾に織り込まれて、ゲーム準備完了時に McpCore を起動します。",
      },
    },
    {
      type: "code",
      lang: "text",
      code: "fat agent jar: core/target/core-1.8.9-all.jar\n  · 既作为 -javaagent 加载（拿到 Instrumentation）\n  · 又在 -cp 上（提供运行时类）\n  · manifest 写入 Premain-Class / Agent-Class\n    + Can-Redefine-Classes / Can-Retransform-Classes = true",
    },
    { type: "h2", text: { zh: "进程与端口拓扑", en: "Process & port topology", ja: "プロセスとポートのトポロジ" }, id: "topology" },
    {
      type: "table",
      head: [
        { zh: "端点", en: "Endpoint", ja: "エンドポイント" },
        { zh: "地址", en: "Address", ja: "アドレス" },
        { zh: "作用", en: "Role", ja: "役割" },
      ],
      rows: [
        [
          { zh: "MCP", en: "MCP", ja: "MCP" },
          { zh: "127.0.0.1:25599", en: "127.0.0.1:25599", ja: "127.0.0.1:25599" },
          {
            zh: "newline JSON-RPC，SocketTransportServer，AI 主入口",
            en: "Newline JSON-RPC, SocketTransportServer, the AI's main entry",
            ja: "newline 区切りの JSON-RPC、SocketTransportServer、AI のメイン入口",
          },
        ],
        [
          { zh: "REST facade", en: "REST facade", ja: "REST facade" },
          { zh: "127.0.0.1:1337", en: "127.0.0.1:1337", ja: "127.0.0.1:1337" },
          {
            zh: "JDK 内置 httpserver，/v1/models /v1/tools /v1/screen",
            en: "JDK's built-in httpserver: /v1/models, /v1/tools, /v1/screen",
            ja: "JDK 内蔵の httpserver：/v1/models、/v1/tools、/v1/screen",
          },
        ],
        [
          { zh: "P-SECURE", en: "P-SECURE", ja: "P-SECURE" },
          { zh: "127.0.0.1:25601", en: "127.0.0.1:25601", ja: "127.0.0.1:25601" },
          {
            zh: "L1 独立决策进程（opt-in）",
            en: "The L1 separate decision process (opt-in)",
            ja: "L1 の独立決定プロセス（opt-in）",
          },
        ],
      ],
    },
    { type: "h2", text: { zh: "一次工具调用的生命周期", en: "The lifecycle of a tool call", ja: "ツール呼び出しのライフサイクル" }, id: "lifecycle" },
    {
      type: "p",
      text: {
        zh: "无论请求来自 MCP socket 还是 HTTP 前门，都收束到同一条 supervised handler。决策权威是接口 SeReferenceMonitor.evaluate()，唯一调用点是 IoManager.supervise()。",
        en: "Whether a request arrives from the MCP socket or the HTTP front door, it funnels into the same supervised handler. The decision authority is the SeReferenceMonitor.evaluate() interface, with IoManager.supervise() as its only call site.",
        ja: "リクエストが MCP ソケットから来ても HTTP のフロントドアから来ても、同一の supervised handler に収れんします。決定権限はインターフェース SeReferenceMonitor.evaluate() であり、その唯一の呼び出し点が IoManager.supervise() です。",
      },
    },
    {
      type: "list",
      items: [
        {
          zh: "L7 IoProbe 先把参数深拷贝并冻结（防 TOCTOU），按 JSON schema 校验。",
          en: "L7 IoProbe first deep-copies and freezes the arguments (defeating TOCTOU) and validates them against a JSON schema.",
          ja: "L7 IoProbe がまず引数をディープコピーして凍結し（TOCTOU 対策）、JSON スキーマで検証します。",
        },
        {
          zh: "7 层安全内核 AND 组合逐层评估，任一层拒绝即短路返回，并标明是哪一层拒的。",
          en: "The 7-layer security kernel evaluates layer by layer, AND-ed together; any single denial short-circuits and names the layer that refused.",
          ja: "7 層セキュリティカーネルが AND で結合しながら 1 層ずつ評価し、いずれか 1 層でも拒否すれば短絡して、どの層が拒否したかを示します。",
        },
        {
          zh: "全部通过后，调用才真正交给执行器（executor）——权限判定始终在执行之前，拒绝不污染熔断器。",
          en: "Only after all layers pass is the call actually handed to the executor — the privilege check always precedes execution, so a denial never pollutes the circuit breaker.",
          ja: "すべての層を通過して初めて、呼び出しが実際に executor へ渡されます。権限判定は常に実行の前に行われるため、拒否がサーキットブレーカーを汚すことはありません。",
        },
      ],
    },
    {
      type: "callout",
      tone: "warn",
      text: {
        zh: "HTTP 前门与 MCP 走同一道 supervised handler，无法绕过。HttpFacade 支持可选 bearer token；配置非 loopback 绑定却无 token 时，facade 拒绝启动（fail-safe）。",
        en: "The HTTP front door and MCP go through the same supervised handler, with no way around it. HttpFacade supports an optional bearer token; if a non-loopback bind is configured without a token, the facade refuses to start (fail-safe).",
        ja: "HTTP のフロントドアと MCP は同一の supervised handler を通り、迂回はできません。HttpFacade はオプションの bearer token に対応します。非ループバックのバインドをトークンなしで設定した場合、facade は起動を拒否します（フェイルセーフ）。",
      },
    },
    { type: "h2", text: { zh: "双 JDK 字节码共存", en: "Dual-JDK bytecode coexistence", ja: "デュアル JDK バイトコードの共存" }, id: "bytecode" },
    {
      type: "p",
      text: {
        zh: "parent 默认以 --release 8 编译（游戏是 Java 8 字节码），core 覆盖为 --release 25。两种字节码在同一个 JBR 25 JVM 里共存运行——游戏保持 vanilla，内核用现代 Java。",
        en: "The parent compiles with --release 8 by default (the game is Java 8 bytecode), and core overrides to --release 25. The two bytecode targets coexist in the same JBR 25 JVM — the game stays vanilla while the kernel uses modern Java.",
        ja: "parent は既定で --release 8 でコンパイルし（ゲームは Java 8 バイトコード）、core は --release 25 に上書きします。2 つのバイトコードターゲットは同一の JBR 25 JVM 内で共存します——ゲームは vanilla のまま、カーネルはモダンな Java を使います。",
      },
    },
    {
      type: "callout",
      tone: "danger",
      text: {
        zh: "字节码锁必须用 --release 8 而非 -source/-target 8。后者不会切换 API 签名，会让 post-8 方法（如协变返回的 ByteBuffer.flip()）在运行时炸 NoSuchMethodError。",
        en: "The bytecode lock must use --release 8, not -source/-target 8. The latter doesn't switch the API signatures, so post-8 methods (such as the covariant-return ByteBuffer.flip()) blow up at runtime with NoSuchMethodError.",
        ja: "バイトコードのロックには -source/-target 8 ではなく --release 8 を使わねばなりません。後者は API シグネチャを切り替えないため、8 以降のメソッド（共変戻り値の ByteBuffer.flip() など）が実行時に NoSuchMethodError で落ちます。",
      },
    },
  ],
};
