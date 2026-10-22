import * as React from "react"
import * as TabsPrimitive from "@radix-ui/react-tabs"
import { cn } from "@/lib/utils"

const Tabs = TabsPrimitive.Root

const TabsList = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.List>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.List>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.List
    ref={ref}
    className={cn("inline-flex h-11 items-center justify-center gap-1 rounded-full border-2 border-border bg-muted p-1 text-muted-foreground shadow-[inset_0_3px_0_var(--muted-lo)]", className)}
    {...props}
  />
))
TabsList.displayName = "TabsList"

const TabsTrigger = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Trigger>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.Trigger
    ref={ref}
    className={cn(
      "inline-flex h-full items-center justify-center whitespace-nowrap rounded-full border-2 border-transparent px-3.5 pt-[1px] font-display text-sm tracking-[0.02em] transition-[background-color,color,box-shadow] duration-150 ease-out hover:text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring data-[state=active]:border-border data-[state=active]:bg-[var(--gold)] data-[state=active]:text-[var(--ink)] data-[state=active]:shadow-[inset_0_3px_0_var(--gold-hi),inset_0_-3px_0_var(--gold-lo)]",
      className,
    )}
    {...props}
  />
))
TabsTrigger.displayName = "TabsTrigger"

const TabsContent = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Content>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.Content ref={ref} className={cn("mt-4 focus-visible:outline-none", className)} {...props} />
))
TabsContent.displayName = "TabsContent"

export { Tabs, TabsList, TabsTrigger, TabsContent }
