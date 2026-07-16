import type { ReactNode } from "react";
import styles from "./mc.module.css";

interface McPanelProps {
  children: ReactNode;
  variant?: "raised" | "inset";
  hover?: boolean;
  className?: string;
}

export function McPanel({
  children,
  variant = "raised",
  hover = false,
  className = "",
}: McPanelProps) {
  const cls = [
    variant === "inset" ? styles.panelInset : styles.panel,
    hover ? styles.panelHover : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return <div className={cls}>{children}</div>;
}

export function PixelDivider({ className = "" }: { className?: string }) {
  return <div className={`${styles.divider} ${className}`} />;
}
