import * as React from "react"
import { cn } from "@/lib/utils"

const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, type, ...props }, ref) => (
    <input
      type={type}
      ref={ref}
      className={cn(
        "kl-field flex h-10 w-full rounded-[12px] border border-border px-3 pt-[5px] pb-1 text-sm text-foreground transition-[box-shadow,border-color] duration-150 ease-out placeholder:text-muted-foreground/80 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-55",
        className,
      )}
      {...props}
    />
  ),
)
Input.displayName = "Input"

export { Input }
