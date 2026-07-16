import type { CSSProperties } from "react";
import { PALETTE, ICON_GRIDS, type IconName } from "./iconData";

export type { IconName };

const base: CSSProperties = { shapeRendering: "crispEdges" };

interface IconProps {
  name: IconName;
  size?: number;
  className?: string;
}

/*
 * 精致像素图标：从 16x16 字符网格渲染成 <rect> 群。
 * 每个字符查 PALETTE 取色；'.' 透明。深色描边 + 高光 + 阴影已画进网格。
 */
export function PixelIcon({ name, size = 32, className = "" }: IconProps) {
  const grid = ICON_GRIDS[name];
  if (!grid) return null;

  const rects: React.ReactNode[] = [];
  for (let y = 0; y < grid.length; y++) {
    const row = grid[y];
    for (let x = 0; x < row.length; x++) {
      const ch = row[x];
      const fill = PALETTE[ch];
      if (!fill) continue; // '.' 或未知字符 → 透明
      rects.push(
        <rect key={`${x}-${y}`} x={x} y={y} width={1} height={1} fill={fill} />
      );
    }
  }

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      style={base}
      className={className}
      role="img"
      aria-hidden="true"
    >
      {rects}
    </svg>
  );
}
