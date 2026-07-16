// 7 层安全内核 L1-L7。
// 结构字段（id/code/nt/status）不译；name/duty 三语手工翻译（见 glossary.ts 术语表）。
import type { L10n } from "./glossary";

export interface KernelLayer {
  id: string;
  name: L10n;
  code: string; // 类路径，不译
  nt: L10n; // NT 对标
  duty: L10n;
  status: "live" | "opt-in" | "built";
}

export const KERNEL_LAYERS: KernelLayer[] = [
  {
    id: "L1",
    name: { zh: "VTL 虚拟信任级", en: "VTL — Virtual Trust Level", ja: "VTL 仮想トラストレベル" },
    code: "se/SeRemoteMonitor · alpc/AlpcServer",
    nt: {
      zh: "虚拟信任级 / 独立地址空间",
      en: "Virtual trust level / separate address space",
      ja: "仮想トラストレベル / 独立アドレス空間",
    },
    duty: {
      zh: "把安全决策搬进游戏 JVM 够不到的独立进程（P-SECURE），经 127.0.0.1 socket RPC 判定——全模型里唯一的真墙，fail-closed。",
      en: "Moves the security decision into a separate process (P-SECURE) the game JVM cannot reach, adjudicated over a 127.0.0.1 socket RPC — the only real wall in the whole model, fail-closed.",
      ja: "セキュリティ決定を、ゲーム JVM が届かない別プロセス（P-SECURE）へ移し、127.0.0.1 の socket RPC で判定します。モデル全体で唯一の本物の壁であり、fail-closed です。",
    },
    status: "opt-in",
  },
  {
    id: "L2",
    name: { zh: "环 Ring", en: "Ring", ja: "リング Ring" },
    code: "se/Ring · SeClearancePolicy",
    nt: { zh: "CPU 保护环 R-1/0/3", en: "CPU protection rings R-1/0/3", ja: "CPU 保護リング R-1/0/3" },
    duty: {
      zh: "CPU 特权环（R-1 HYPERVISOR … R3 USER），环号越小越危险；降权是真 kill-switch，升权需 restore token。",
      en: "CPU privilege rings (R-1 HYPERVISOR … R3 USER); the lower the number, the more dangerous. Dropping privilege is a real kill-switch; raising it requires a restore token.",
      ja: "CPU 特権リング（R-1 HYPERVISOR … R3 USER）。番号が小さいほど危険です。権限の降格は本物の kill-switch であり、昇格には restore token が必要です。",
    },
    status: "live",
  },
  {
    id: "L3",
    name: { zh: "完整性 Integrity", en: "Integrity", ja: "完全性 Integrity" },
    code: "se/IntegrityLevel",
    nt: { zh: "强制完整性控制 MIC", en: "Mandatory Integrity Control (MIC)", ja: "強制完全性制御 MIC" },
    duty: {
      zh: "no-write-up：主体完整性 ≥ 资源完整性才能写（UNTRUSTED…PROTECTED 共 7 级）。",
      en: "No-write-up: a subject can write only when its integrity ≥ the resource's (7 levels, UNTRUSTED…PROTECTED).",
      ja: "no-write-up：主体の完全性が資源の完全性以上のときのみ書き込めます（UNTRUSTED…PROTECTED の 7 段階）。",
    },
    status: "live",
  },
  {
    id: "L4",
    name: { zh: "特权 Privilege", en: "Privilege", ja: "特権 Privilege" },
    code: "se/Privilege · PrivilegeToken",
    nt: { zh: "访问令牌特权", en: "Access-token privileges", ja: "アクセストークン特権" },
    duty: {
      zh: "动词特权（10 个），两态 granted/enabled，危险操作要求既 granted 又 enabled；主体不能自我提权。",
      en: "Verb privileges (10 of them), each in two states — granted/enabled. Dangerous operations require both granted and enabled; a subject cannot elevate itself.",
      ja: "動詞的な特権（10 個）で、granted/enabled の 2 状態を持ちます。危険な操作には granted かつ enabled の両方が必要で、主体は自己昇格できません。",
    },
    status: "live",
  },
  {
    id: "L5",
    name: { zh: "能力 SID", en: "Capability SID", ja: "ケイパビリティ SID" },
    code: "se/CapabilitySid · CapabilityCatalog",
    nt: { zh: "AppContainer capability SID", en: "AppContainer capability SID", ja: "AppContainer ケイパビリティ SID" },
    duty: {
      zh: "资源类能力 SID（14 个），default-deny：主体不持有工具所需 SID 即拒。",
      en: "Resource-class capability SIDs (14 of them), default-deny: if a subject lacks a tool's required SID, the call is denied.",
      ja: "リソース種別のケイパビリティ SID（14 個）で、default-deny です。ツールが要求する SID を主体が持たなければ拒否されます。",
    },
    status: "live",
  },
  {
    id: "L6",
    name: { zh: "对象句柄 Handle", en: "Object Handle", ja: "オブジェクトハンドル" },
    code: "ob/ObManager · ob/ObHandle",
    nt: { zh: "对象句柄 + 授权掩码", en: "Object handle + granted mask", ja: "オブジェクトハンドル + 許可マスク" },
    duty: {
      zh: "open-time 权限冻结：open 时定下掩码，后续只做子集校验，不再重判。",
      en: "Open-time rights freezing: the mask is fixed at open, and later calls only check subsets rather than re-adjudicating.",
      ja: "オープン時に権限を凍結します。マスクは open 時に確定し、以降は再判定せず部分集合の検査のみ行います。",
    },
    status: "built",
  },
  {
    id: "L7",
    name: { zh: "边界校验 Probe", en: "Boundary Probe", ja: "境界検証 Probe" },
    code: "io/IoProbe",
    nt: { zh: "ProbeForRead + 参数捕获", en: "ProbeForRead + argument capture", ja: "ProbeForRead + 引数キャプチャ" },
    duty: {
      zh: "dispatch 前把参数深拷贝 + 冻结（防 TOCTOU）并按 JSON schema 校验。",
      en: "Before dispatch, deep-copies and freezes arguments (defeating TOCTOU) and validates them against a JSON schema.",
      ja: "ディスパッチ前に引数をディープコピーして凍結し（TOCTOU 対策）、JSON スキーマで検証します。",
    },
    status: "live",
  },
];

export interface Capability {
  id: string;
  name: L10n;
  pkg: string; // 包路径，不译
  duty: L10n;
}

export const CAPABILITIES: Capability[] = [
  {
    id: "C1",
    name: { zh: "INTROSPECT 自省", en: "INTROSPECT", ja: "INTROSPECT 自己反映" },
    pkg: "cm/",
    duty: {
      zh: "枚举已加载类、反射描述类结构、跨类查方法、聚合列出所有运行时 hook（全只读）。",
      en: "Enumerate loaded classes, describe class structure via reflection, find methods across classes, and list all runtime hooks — all read-only.",
      ja: "ロード済みクラスの列挙、リフレクションによるクラス構造の記述、クラス横断のメソッド検索、実行時 hook の一覧をすべて読み取り専用で行います。",
    },
  },
  {
    id: "C2",
    name: { zh: "OBSERVE 被动观察", en: "OBSERVE", ja: "OBSERVE 受動観察" },
    pkg: "drivers/world · C6",
    duty: {
      zh: "被动监视点、方法出入、包抓取、字段修改监视（field-watch 依赖 C6 原生层）。",
      en: "Passive watch points, method entry/exit, packet capture, and field-modification watches (field-watch relies on the C6 native layer).",
      ja: "受動的なウォッチポイント、メソッドの出入り、パケットキャプチャ、フィールド変更の監視を行います（field-watch は C6 ネイティブ層に依存）。",
    },
  },
  {
    id: "C3",
    name: { zh: "INTERCEPT 拦截", en: "INTERCEPT", ja: "INTERCEPT インターセプト" },
    pkg: "flt/FltDynamicManager",
    duty: {
      zh: "运行时装/卸 ByteBuddy retransformation hook，可外科式单独还原一个 hook。",
      en: "Install/remove ByteBuddy retransformation hooks at runtime, and surgically revert a single hook on its own.",
      ja: "ByteBuddy の retransformation hook を実行時に着脱でき、個々の hook を外科的に単独で元へ戻せます。",
    },
  },
  {
    id: "C4",
    name: { zh: "REDEFINE 热重定义", en: "REDEFINE", ja: "REDEFINE 再定義" },
    pkg: "ldr/",
    duty: {
      zh: "对活类 redefineExisting，借 JBR+DCEVM 改方法体 + 加字段/加方法。",
      en: "redefineExisting on live classes — change method bodies and add fields/methods via JBR+DCEVM.",
      ja: "稼働中クラスに redefineExisting を行い、JBR+DCEVM でメソッド本体の変更やフィールド・メソッドの追加を行います。",
    },
  },
  {
    id: "C5",
    name: { zh: "MUTATE-STATE 深访问", en: "MUTATE-STATE", ja: "MUTATE-STATE 深部アクセス" },
    pkg: "mm/",
    duty: {
      zh: "读写任意字段（含 private/static final）、调私有方法、开放平台模块。",
      en: "Read/write arbitrary fields (including private/static final), call private methods, and open platform modules.",
      ja: "任意のフィールド（private/static final を含む）の読み書き、private メソッドの呼び出し、プラットフォームモジュールの開放を行います。",
    },
  },
  {
    id: "C6",
    name: { zh: "CONTROL-EXEC 调试器级执行控制", en: "CONTROL-EXEC — debugger-grade execution control", ja: "CONTROL-EXEC デバッガ級の実行制御" },
    pkg: "kd/",
    duty: {
      zh: "原生 JVMTI 调试器：暂停线程、PopFrame、ForceEarlyReturn、断点、单步、读写局部变量、字段监视。",
      en: "Native JVMTI debugger: suspend threads, PopFrame, ForceEarlyReturn, breakpoints, single-step, read/write locals, and field watches.",
      ja: "ネイティブ JVMTI デバッガ：スレッド停止、PopFrame、ForceEarlyReturn、ブレークポイント、シングルステップ、ローカル変数の読み書き、フィールド監視を行います。",
    },
  },
  {
    id: "C7",
    name: { zh: "SYNTHESIZE 合成", en: "SYNTHESIZE", ja: "SYNTHESIZE 合成" },
    pkg: "ps/PsSynthesizer",
    duty: {
      zh: "编译 AI 提供的 Java 为可 GC 的隐藏类并执行，用完即弃、对自省不可见、不可 redefine。",
      en: "Compile AI-supplied Java into a GC-able hidden class and run it — discarded after use, invisible to introspection, and not redefinable.",
      ja: "AI が渡した Java を GC 可能な hidden class にコンパイルして実行します。使い捨てで、イントロスペクションからは見えず、再定義もできません。",
    },
  },
  {
    id: "C8",
    name: { zh: "SEAM 接缝", en: "SEAM", ja: "SEAM シーム" },
    pkg: "flt/seam/",
    duty: {
      zh: "三种运行时接缝：Netty pipeline MITM、GLFW 输入回调、tick 注入，可逆装卸。",
      en: "Three runtime seams — Netty pipeline MITM, GLFW input callbacks, and tick injection — all reversibly installable.",
      ja: "3 種類の実行時シーム（Netty パイプラインの MITM、GLFW 入力コールバック、tick 注入）を、可逆的に着脱できます。",
    },
  },
  {
    id: "C9",
    name: { zh: "GUI-INTERACT 结构化 GUI", en: "GUI-INTERACT — structured GUI", ja: "GUI-INTERACT 構造化 GUI" },
    pkg: "drivers/gui/",
    duty: {
      zh: "把活 GuiScreen 反射成可寻址元素，按元素 id 驱动真实 vanilla 处理器（而非发送像素坐标）。",
      en: "Reflect a live GuiScreen into addressable elements and drive the real vanilla handlers by element id — instead of sending pixel coordinates.",
      ja: "稼働中の GuiScreen をアドレス可能な要素へリフレクションし、ピクセル座標を送るのではなく要素 id で本物の vanilla ハンドラを駆動します。",
    },
  },
];
