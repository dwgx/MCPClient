import Link from "next/link";
import type { ReactNode } from "react";
import styles from "./mc.module.css";

type Variant = "primary" | "secondary" | "danger";

const variantClass: Record<Variant, string> = {
  primary: styles.btnPrimary,
  secondary: styles.btnSecondary,
  danger: styles.btnDanger,
};

interface McButtonProps {
  children: ReactNode;
  href?: string;
  variant?: Variant;
  size?: "md" | "sm";
  external?: boolean;
  className?: string;
}

export function McButton({
  children,
  href,
  variant = "primary",
  size = "md",
  external = false,
  className = "",
}: McButtonProps) {
  const cls = [
    styles.btn,
    variantClass[variant],
    size === "sm" ? styles.btnSm : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  if (href) {
    if (external) {
      return (
        <a href={href} className={cls} target="_blank" rel="noopener noreferrer">
          {children}
        </a>
      );
    }
    return (
      <Link href={href} className={cls}>
        {children}
      </Link>
    );
  }

  return <button className={cls}>{children}</button>;
}
