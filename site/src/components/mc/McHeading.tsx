import type { ReactNode } from "react";

interface McHeadingProps {
  children: ReactNode;
  as?: "h1" | "h2" | "h3" | "h4";
  className?: string;
  shadow?: boolean;
}

const sizeByTag: Record<string, string> = {
  h1: "text-2xl sm:text-3xl md:text-4xl",
  h2: "text-xl sm:text-2xl",
  h3: "text-base sm:text-lg",
  h4: "text-sm",
};

export function McHeading({
  children,
  as = "h2",
  className = "",
  shadow = true,
}: McHeadingProps) {
  const Tag = as;
  const cls = [
    "font-pixel",
    sizeByTag[as],
    shadow ? "text-shadow-mc" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return <Tag className={cls}>{children}</Tag>;
}

/** 像素小标签（用于分类 / 徽章） */
export function McTag({
  children,
  color = "grass",
}: {
  children: ReactNode;
  color?: "grass" | "stone" | "danger" | "diamond" | "gold";
}) {
  const bg: Record<string, string> = {
    grass: "bg-grass-500",
    stone: "bg-stone-500",
    danger: "bg-danger-500",
    diamond: "bg-diamond-500",
    gold: "bg-gold-400 text-black",
  };
  return (
    <span
      className={`font-pixel text-[0.55rem] px-2 py-1 border-2 border-black/60 ${bg[color]} text-shadow-mc-sm`}
    >
      {children}
    </span>
  );
}
