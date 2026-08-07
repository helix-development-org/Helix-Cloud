import { useEffect, useRef } from "react"
import { Compartment, EditorState, type Extension } from "@codemirror/state"
import {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  drawSelection, highlightSpecialChars, rectangularSelection,
} from "@codemirror/view"
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands"
import {
  syntaxHighlighting, HighlightStyle, bracketMatching, foldGutter, foldKeymap, indentUnit,
} from "@codemirror/language"
import { tags as t } from "@lezer/highlight"
import { cn } from "@/lib/utils"

// One highlight style for every language. Colours come from CSS variables so
// the palette follows the dashboard's light/dark theme without reconfiguring.
const highlightStyle = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.self], color: "var(--syntax-keyword)" },
  { tag: [t.string, t.special(t.string)], color: "var(--syntax-string)" },
  { tag: [t.number, t.bool, t.null], color: "var(--syntax-number)" },
  { tag: [t.atom, t.color, t.constant(t.name)], color: "var(--syntax-atom)" },
  { tag: [t.propertyName, t.definition(t.propertyName)], color: "var(--syntax-property)" },
  { tag: [t.comment, t.lineComment, t.blockComment], color: "var(--syntax-comment)", fontStyle: "italic" },
  { tag: [t.tagName, t.function(t.variableName)], color: "var(--syntax-tag)" },
  { tag: [t.attributeName, t.attributeValue], color: "var(--syntax-attribute)" },
  { tag: [t.variableName, t.special(t.variableName)], color: "var(--syntax-variable)" },
  { tag: [t.typeName, t.className, t.namespace], color: "var(--syntax-type)" },
  { tag: [t.operator, t.punctuation, t.angleBracket, t.brace, t.squareBracket, t.paren], color: "var(--syntax-punct)" },
  { tag: [t.escape, t.regexp], color: "var(--syntax-escape)" },
  { tag: [t.meta, t.processingInstruction], color: "var(--syntax-meta)" },
  { tag: [t.heading], color: "var(--syntax-keyword)", fontWeight: "bold" },
  { tag: [t.strong], fontWeight: "bold" },
  { tag: [t.emphasis], fontStyle: "italic" },
  { tag: [t.link, t.url], color: "var(--syntax-attribute)", textDecoration: "underline" },
])

const theme = EditorView.theme({
  "&": { height: "100%", backgroundColor: "transparent", color: "var(--color-foreground)" },
  ".cm-scroller": { overflow: "auto", fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "12px", lineHeight: "1.6" },
  ".cm-content": { caretColor: "var(--color-foreground)" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--color-foreground)" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection": {
    backgroundColor: "color-mix(in oklab, var(--color-primary) 28%, transparent)",
  },
  ".cm-gutters": { backgroundColor: "transparent", color: "var(--color-muted-foreground)", border: "none" },
  ".cm-activeLine": { backgroundColor: "color-mix(in oklab, var(--color-muted-foreground) 8%, transparent)" },
  ".cm-activeLineGutter": { backgroundColor: "transparent", color: "var(--color-foreground)" },
  ".cm-foldPlaceholder": { backgroundColor: "var(--color-muted)", border: "none", color: "var(--color-muted-foreground)" },
  "&.cm-focused": { outline: "none" },
})

function baseExtensions(): Extension[] {
  return [
    lineNumbers(),
    highlightActiveLineGutter(),
    highlightSpecialChars(),
    history(),
    foldGutter(),
    drawSelection(),
    rectangularSelection(),
    EditorState.allowMultipleSelections.of(true),
    indentUnit.of("  "),
    bracketMatching(),
    highlightActiveLine(),
    EditorView.lineWrapping,
    syntaxHighlighting(highlightStyle),
    theme,
    keymap.of([...defaultKeymap, ...historyKeymap, ...foldKeymap, indentWithTab]),
  ]
}

interface CodeEditorProps {
  value: string
  onChange?: (value: string) => void
  /** Language extension (e.g. from languageForFile or minimessage()); plain text if null. */
  language?: Extension | null
  readOnly?: boolean
  className?: string
}

/** A CodeMirror 6 editor with syntax highlighting, wired to a controlled value. */
export function CodeEditor({ value, onChange, language, readOnly, className }: CodeEditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const langCompartment = useRef(new Compartment())
  const editableCompartment = useRef(new Compartment())
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  // Create the editor once; value/language/readOnly are pushed in via effects.
  useEffect(() => {
    if (!host.current) return
    const state = EditorState.create({
      doc: value,
      extensions: [
        ...baseExtensions(),
        langCompartment.current.of(language ?? []),
        editableCompartment.current.of(EditorView.editable.of(!readOnly)),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) onChangeRef.current?.(u.state.doc.toString())
        }),
      ],
    })
    const editor = new EditorView({ state, parent: host.current })
    view.current = editor
    return () => { editor.destroy(); view.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Reconfigure language without recreating the editor.
  useEffect(() => {
    view.current?.dispatch({ effects: langCompartment.current.reconfigure(language ?? []) })
  }, [language])

  useEffect(() => {
    view.current?.dispatch({ effects: editableCompartment.current.reconfigure(EditorView.editable.of(!readOnly)) })
  }, [readOnly])

  // Sync external value changes (e.g. opening a different file) into the editor.
  useEffect(() => {
    const editor = view.current
    if (!editor) return
    const current = editor.state.doc.toString()
    if (current !== value) {
      editor.dispatch({ changes: { from: 0, to: current.length, insert: value } })
    }
  }, [value])

  return (
    <div
      ref={host}
      className={cn("overflow-hidden rounded-md border border-input bg-transparent focus-within:ring-2 focus-within:ring-ring", className)}
    />
  )
}
