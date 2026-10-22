import * as React from "react"
import * as SwitchPrimitive from "@radix-ui/react-switch"
import { cn } from "@/lib/utils"

const Switch = React.forwardRef<
  React.ElementRef<typeof SwitchPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SwitchPrimitive.Root>
>(({ className, ...props }, ref) => (
  <SwitchPrimitive.Root
    ref={ref}
    className={cn(
      "peer inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full border-2 border-border shadow-[inset_0_3px_0_color-mix(in_srgb,var(--ink)_25%,transparent)] transition-colors duration-150 ease-out focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-55 data-[state=checked]:bg-success data-[state=unchecked]:bg-muted",
      className,
    )}
    {...props}
  >
    <SwitchPrimitive.Thumb className="pointer-events-none block size-[18px] rounded-full border-2 border-border bg-parchment shadow-[inset_0_-3px_0_var(--parchment-lo)] transition-transform duration-150 ease-out data-[state=checked]:translate-x-[20px] data-[state=unchecked]:translate-x-[1px]" />
  </SwitchPrimitive.Root>
))
Switch.displayName = "Switch"

export { Switch }
