import * as React from "react"
import { cn } from "@/lib/utils"

const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn(
        "kl-field flex min-h-[72px] w-full rounded-[12px] border border-border px-3 pt-[9px] pb-2 text-sm text-foreground placeholder:text-muted-foreground/80 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-55",
        className,
      )}
      {...props}
    />
  ),
)
Textarea.displayName = "Textarea"

export { Textarea }
