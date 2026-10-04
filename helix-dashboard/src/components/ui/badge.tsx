import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

/**
 * Status pill (§17): always a pill with an ink outline. running/online = success, starting = info,
 * paused = warning, error/stopped = destructive. White text only on the saturated fills, with a 1 px ink shadow.
 */
const badgeVariants = cva(
  "inline-flex items-center gap-1 whitespace-nowrap rounded-full border-2 border-border px-2.5 py-[3px] font-display text-xs leading-none tracking-[0.03em] shadow-[0_2px_0_var(--border)]",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground [text-shadow:var(--primary-text-shadow)]",
        secondary: "bg-muted text-foreground",
        outline: "bg-transparent text-foreground shadow-none",
        success: "bg-success text-success-foreground [text-shadow:0_1px_0_var(--border)]",
        info: "bg-info text-info-foreground [text-shadow:0_1px_0_var(--border)]",
        warning: "bg-warning text-warning-foreground",
        destructive: "bg-destructive text-destructive-foreground [text-shadow:0_1px_0_var(--border)]",
      },
    },
    defaultVariants: { variant: "default" },
  },
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

/** Small status label. */
function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />
}

export { Badge, badgeVariants }
