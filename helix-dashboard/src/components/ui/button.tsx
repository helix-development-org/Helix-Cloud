import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

/**
 * Buttons follow the design concept (§4, §17): 3 px ink outline, 13 px radius, top highlight + bottom edge
 * in the surface's own hi/lo stage, hard ink drop shadow; pressing drops the face 2 px and halves the edge.
 * default = primary, secondary = gold/wood, outline = parchment, destructive = red, ghost stays flat.
 */
const buttonVariants = cva(
  "kl-press inline-flex select-none items-center justify-center gap-2 whitespace-nowrap rounded-[var(--radius-button)] font-display text-[15px] leading-none tracking-[0.02em] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-55 disabled:saturate-50 [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default:
          "kl-raised border-[3px] border-border bg-primary text-primary-foreground [--bevel-hi:var(--primary-hi)] [--bevel-lo:var(--primary-lo)] [text-shadow:var(--primary-text-shadow)]",
        destructive:
          "kl-raised border-[3px] border-border bg-destructive text-destructive-foreground [--bevel-hi:var(--destructive-hi)] [--bevel-lo:var(--destructive-lo)] [text-shadow:0_1px_0_var(--border)]",
        outline:
          "kl-raised border-[3px] border-border bg-parchment text-ink [--bevel-hi:var(--parchment-hi)] [--bevel-lo:var(--parchment-lo)]",
        secondary:
          "kl-raised border-[3px] border-border bg-secondary text-secondary-foreground [--bevel-hi:var(--secondary-hi)] [--bevel-lo:var(--secondary-lo)]",
        success:
          "kl-raised border-[3px] border-border bg-success text-success-foreground [--bevel-hi:var(--success-hi)] [--bevel-lo:var(--success-lo)] [text-shadow:0_1px_0_var(--border)]",
        ghost: "border-[3px] border-transparent text-foreground hover:bg-[color-mix(in_srgb,var(--gold)_15%,transparent)]",
        link: "font-sans font-bold text-foreground underline decoration-[var(--gold)] decoration-2 underline-offset-4 hover:decoration-[3px]",
      },
      size: {
        default: "h-10 px-4 pb-[3px]",
        sm: "h-8 px-3 pb-[2px] text-[13px] [--edge-hi:3px] [--edge-lo:4px] [--drop:3px]",
        lg: "h-12 px-6 pb-[3px] text-[17px]",
        icon: "size-10 pb-[3px]",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  },
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button"
    return <Comp className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />
  },
)
Button.displayName = "Button"

export { Button, buttonVariants }
