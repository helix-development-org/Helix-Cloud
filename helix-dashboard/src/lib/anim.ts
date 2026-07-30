import { useEffect, useLayoutEffect, useRef, useState } from "react"
import { animate, stagger } from "animejs"

/** Whether the OS asks for reduced motion — every helper degrades to no-op then. */
function reducedMotion(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches
}

/**
 * Staggered reveal of a view: the container's direct children rise and fade
 * in one after another. Runs once per mount/key change (see [useViewReveal]).
 */
export function revealView(root: HTMLElement) {
  if (reducedMotion()) return
  let children = Array.from(root.children) as HTMLElement[]
  // The wrapper usually holds exactly the view's own root element — stagger
  // that view's cards/sections rather than fading one big block.
  if (children.length === 1 && children[0].children.length > 1) {
    children = Array.from(children[0].children) as HTMLElement[]
  }
  if (!children.length) return
  animate(children, {
    opacity: [0, 1],
    translateY: [10, 0],
    duration: 420,
    ease: "outQuad",
    delay: stagger(45),
  })
}

/**
 * Replays the view reveal whenever `key` changes — attach the returned ref
 * to the container wrapping the active view, keyed by the view id, so every
 * navigation gets a page transition.
 */
export function useViewReveal(key: string) {
  const ref = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    if (ref.current) revealView(ref.current)
  }, [key])
  return ref
}

/**
 * Animated number: counts from the previously shown value to `value` on
 * every change. Returns the text to render.
 */
export function useCountUp(value: number, digits = 0): string {
  const [shown, setShown] = useState(value)
  const previous = useRef(value)
  useEffect(() => {
    const from = previous.current
    previous.current = value
    if (from === value) return
    if (reducedMotion()) {
      setShown(value)
      return
    }
    const proxy = { v: from }
    const animation = animate(proxy, {
      v: value,
      duration: 600,
      ease: "outCubic",
      onUpdate: () => setShown(proxy.v),
    })
    return () => {
      animation.pause()
    }
  }, [value])
  return digits ? shown.toFixed(digits) : Math.round(shown).toString()
}

/**
 * Pops the referenced element (scale bounce) whenever `dep` changes after
 * mount — used for live status badges (service state changes and similar),
 * so a change catches the eye without a layout shift.
 */
export function usePopOnChange(dep: unknown) {
  const ref = useRef<HTMLElement | null>(null)
  const mounted = useRef(false)
  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true
      return
    }
    if (!ref.current || reducedMotion()) return
    animate(ref.current, {
      scale: [1, 1.25, 1],
      duration: 450,
      ease: "outBack",
    })
  }, [dep])
  return ref
}
