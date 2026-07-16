// 精致像素图标数据。16x16 字符网格 + 调色板。
// 绘制规范：深色描边(o)包裹轮廓 + 左上高光 + 右下阴影 + 2-3 明度层次。
// 真 Minecraft 物品意象：末影之眼/红石中继器/盾牌/侦测器/成书与笔…

export const PALETTE: Record<string, string> = {
  o: "#17140f", // 描边
  w: "#ffffff", // 高光白
  H: "#86e06a", G: "#5fbd3f", e: "#3f8a2c", // 草
  T: "#9c7248", t: "#7a5433", k: "#543a22", // 泥土
  E: "#eceef4", S: "#b4b7c2", s: "#71747f", d: "#44464e", // 铁/石
  N: "#ffe36b", n: "#e0a92e", // 金
  C: "#8ff6ee", c: "#25c0d4", // 钻石青
  V: "#4ce8b0", v: "#0f9c72", // 末影绿
  R: "#ff5b52", r: "#b52a24", // 红石
  P: "#c08bff", p: "#5f38a8", // 紫（附魔）
  W: "#a9743f", m: "#6b4522", // 木
  X: "#241b33", x: "#120c1c", // 末影底/黑曜石
  b: "#2b6cff", // 蓝
};

export const ICON_GRIDS = {
  // 草方块正面（logo / 活体 JVM）
  grass: [
    "oooooooooooooooo",
    "oHHHHHHHHHHHHHHo",
    "oHGGHGGGGHGGGGHo",
    "oGGGGGGGGGGGGGGo",
    "oeGeeGeeeGeeGeeo",
    "oTTtTTTtTTTtTTto",
    "otTTkTTTtTTkTTTo",
    "oTTtTTtTTTtTTtTo",
    "oTkTTTtTTtTTtkTo",
    "otTTtTTTkTTtTTto",
    "oTTtTkTtTTTtTTTo",
    "oTtTTTtTTtTTkTto",
    "oTTkTTtTTTtTTtTo",
    "otTTtTTtTkTTtTto",
    "okTTtTTTtTTtTTko",
    "oooooooooooooooo",
  ],
  // 盾牌（安全内核）
  shield: [
    "..oooooooooooo..",
    ".oEEEEEEEEEEEEo.",
    ".oESSSSSSSSSSEo.",
    ".oESCCCCCCCCSEo.",
    ".oESCCCCCCCCSEo.",
    ".oESSSSSSSSSSEo.",
    ".oESSSSSSSSSSEo.",
    ".oESSSSSSSSSSEo.",
    "..oESSSSSSSSEo..",
    "..oESSSSSSSSEo..",
    "...oESSSSSSEo...",
    "....oESSSSEo....",
    ".....oESSEo.....",
    "......oEEo......",
    ".......oo.......",
    "................",
  ],
  // 末影之眼（LLM）
  endereye: [
    "................",
    "....oooooo....",
    "...oVVVVVVo...",
    "..oVVvvvvVVo..",
    ".oVVvXXXXvVVo.",
    ".oVvXXwwXXvVo.",
    ".oVvXwwwwXvVo.",
    ".oVvXwwwwXvVo.",
    ".oVvXXXXXXvVo.",
    ".oVVvxxxxvVVo.",
    "..oVVvvvvVVo..",
    "...oVVVVVVo...",
    "....oooooo....",
    "................",
    "................",
    "................",
  ],
  // 红石中继器（MCP 协议）
  repeater: [
    "................",
    "oooooooooooooooo",
    "oSSSSSSSSSSSSSSo",
    "oSsssssssssssSo.",
    "oSsRRsssssRRssSo",
    "oSsRRsssssRRssSo",
    "oSssssssssssssSo",
    "oSsssRRRRsssssSo",
    "oSsssRRRRsssssSo",
    "oSssssssssssssSo",
    "oSsdsssssssdssSo",
    "oSSSSSSSSSSSSSSo",
    "ooooooooooooooo o",
    "................",
    "................",
    "................",
  ],
  // 侦测器/观察者方块（原生 JVMTI 调试器）
  observer: [
    "oooooooooooooooo",
    "oSSSSSSSSSSSSSSo",
    "oSEEEEEEEEEEEESo",
    "oSEddddddddddESo",
    "oSEdRRRRRRRRdESo",
    "oSEdRCCCCCCRdESo",
    "oSEdRCwwwwCRdESo",
    "oSEdRCwwwwCRdESo",
    "oSEdRCCCCCCRdESo",
    "oSEdRRRRRRRRdESo",
    "oSEddddddddddESo",
    "oSEEEEEEEEEEEESo",
    "oSSSSSSSSSSSSSSo",
    "oooooooooooooooo",
    "................",
    "................",
  ],
  // 铁锁（AI 生成代码封锁）
  lock: [
    "................",
    "....oooooo....",
    "...oEEEEEEo...",
    "..oEo....oEo..",
    "..oEo....oEo..",
    ".ooooooooooooo.",
    ".oRRRRRRRRRRRRo",
    ".oRRRRwwRRRRRRo",
    ".oRRRwxxwRRRRRo",
    ".oRRRwxxwRRRRRo",
    ".oRRRRxxRRRRRRo",
    ".oRRRRRRRRRRRRo",
    ".oRRRRRRRRRRRRo",
    ".ooooooooooooo.",
    "................",
    "................",
  ],
  // 光标/箭头（结构化 GUI）
  cursor: [
    "oo..............",
    "oEo.............",
    "oEEo............",
    "oEEEo...........",
    "oEEEEo..........",
    "oEEEEEo.........",
    "oEEEEEEo........",
    "oEEEEEEEo.......",
    "oEEEEEEEEo......",
    "oEEEEEoooo......",
    "oEEoEEo.........",
    "ooo.oEEo........",
    "....oEEo........",
    ".....ooo........",
    "................",
    "................",
  ],
  // 成书与笔（Ed25519 签名）
  quill: [
    "..............oo",
    "............ooVo",
    "...........oVVo.",
    "..........oVVo..",
    ".oooooo..oVVo...",
    "oEEEEEEooVVo....",
    "oEwwwwEoVEo.....",
    "oEwwwwwEVo......",
    "oEwwwwwwEo......",
    "oEwwwwwwwEo.....",
    "oEwwwwwwEEo.....",
    "oEEEEEEEEo......",
    ".oooooooo.......",
    "................",
    "................",
    "................",
  ],
  // 放大镜（搜索）
  search: [
    "...oooooo.......",
    "..oEEEEEEo......",
    ".oESCCCCSEo.....",
    "oESCwwwwCSEo....",
    "oECwwwwwwCEo....",
    "oECwwwwwwCEo....",
    "oESCwwwwCSEo....",
    ".oESCCCCSEo.....",
    "..oEEEEEEoo.....",
    "...oooooEEo.....",
    ".......oEEo.....",
    "........oEEo....",
    ".........oEEo...",
    "..........ooo...",
    "................",
    "................",
  ],
} as unknown as Record<string, string[]>;

export type IconName =
  | "grass"
  | "shield"
  | "observer"
  | "lock"
  | "cursor"
  | "quill"
  | "endereye"
  | "repeater"
  | "search";
