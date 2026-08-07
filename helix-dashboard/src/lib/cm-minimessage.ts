// A CodeMirror StreamLanguage that tokenises MiniMessage markup so tags,
// arguments and placeholders can be highlighted inside the translation editor.
import { StreamLanguage, LanguageSupport } from "@codemirror/language"
import { tags as t } from "@lezer/highlight"
import { classifyTag } from "@/lib/minimessage"

interface State { inTag: boolean }

const parser = StreamLanguage.define<State>({
  name: "minimessage",
  startState: () => ({ inTag: false }),
  token(stream, state) {
    if (!state.inTag) {
      // Escaped bracket or backslash.
      if (stream.match(/^\\[<>\\]/)) return "mmEscape"
      // Placeholder such as {network} or {player}.
      if (stream.peek() === "{") {
        if (stream.match(/^\{[^}<>]*\}/)) return "mmPlaceholder"
        stream.next()
        return null
      }
      // Opening of a tag: `<name…>` or `</name>`.
      if (stream.peek() === "<") {
        if (stream.match(/^<\/?(?=[a-z0-9_#!?])/i)) { state.inTag = true; return "mmBracket" }
        stream.next()
        return null
      }
      // Plain text run up to the next significant character.
      stream.match(/^[^<{\\]+/) || stream.next()
      return null
    }

    // Inside a tag.
    if (stream.match(/^'(\\.|[^'])*'/) || stream.match(/^"(\\.|[^"])*"/)) return "mmString"
    if (stream.eat(">")) { state.inTag = false; return "mmBracket" }
    if (stream.eat(":")) return "mmBracket"
    if (stream.match(/^#[0-9a-fA-F]{3,6}/)) return "mmHex"
    const word = stream.match(/^[^:>'"\s]+/) as RegExpMatchArray | null
    if (word) {
      switch (classifyTag(word[0].toLowerCase())) {
        case "color": return "mmColor"
        case "decoration":
        case "reset": return "mmFormat"
        case "function": return "mmFunction"
        default: return "mmArg"
      }
    }
    stream.next()
    return null
  },
  tokenTable: {
    mmBracket: t.angleBracket,
    mmColor: t.keyword,
    mmFormat: t.modifier,
    mmFunction: t.tagName,
    mmArg: t.propertyName,
    mmString: t.string,
    mmHex: t.number,
    mmPlaceholder: t.variableName,
    mmEscape: t.escape,
  },
})

export function minimessage() {
  return new LanguageSupport(parser)
}
