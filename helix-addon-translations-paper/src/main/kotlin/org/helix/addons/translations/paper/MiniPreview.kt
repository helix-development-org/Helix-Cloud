package org.helix.addons.translations.paper

import de.tytoss.igui.display.DisplayBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.helix.api.message.LegacyToMini

/**
 * Renders MiniMessage/legacy templates for the translations editor: to a
 * component (for item names and the live anvil preview) and, flattened into
 * styled runs, onto the dirt background via [DisplayBuilder].
 *
 * Placeholders (`{name}`) and newlines are left raw — MiniMessage does not
 * treat `{}` specially, so they render verbatim; newlines split the preview
 * across rows. Colors, hex, gradients and rainbow render faithfully (each is
 * its own colored run); `<bold>`/`<italic>`/`<obfuscated>`/`<underlined>`/
 * `<strikethrough>` ride along as decorations, kept pixel-accurate by
 * [DisplayBuilder.styledText].
 */
object MiniPreview {
    private val mm = MiniMessage.miniMessage()

    /**
     * Renders raw MiniMessage/legacy text to a component (italic reset, since
     * item names/lore default to italic).
     *
     * @param raw the template.
     * @return the rendered component.
     */
    fun render(raw: String): Component =
        runCatching { mm.deserialize(LegacyToMini.translate(raw)) }
            .getOrElse { Component.text(raw) }
            .decoration(TextDecoration.ITALIC, false)

    /**
     * A run of text sharing one resolved style.
     *
     * @property text the run's text.
     * @property color resolved color, or `null` to inherit the default.
     */
    data class Run(
        val text: String,
        val color: TextColor?,
        val bold: Boolean,
        val italic: Boolean,
        val obfuscated: Boolean,
        val underlined: Boolean,
        val strikethrough: Boolean,
    )

    /**
     * Flattens a template into styled runs, resolving inherited style.
     *
     * @param raw the template.
     * @return the runs, in reading order.
     */
    fun runs(raw: String): List<Run> {
        val root = runCatching { mm.deserialize(LegacyToMini.translate(raw)) }.getOrElse { Component.text(raw) }
        val out = ArrayList<Run>()
        flatten(root, Style.empty(), out)
        return out
    }

    private fun flatten(component: Component, inherited: Style, out: MutableList<Run>) {
        val effective = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        val content = (component as? TextComponent)?.content().orEmpty()
        if (content.isNotEmpty()) {
            out += Run(
                text = content,
                color = effective.color(),
                bold = effective.hasDecoration(TextDecoration.BOLD),
                italic = effective.hasDecoration(TextDecoration.ITALIC),
                obfuscated = effective.hasDecoration(TextDecoration.OBFUSCATED),
                underlined = effective.hasDecoration(TextDecoration.UNDERLINED),
                strikethrough = effective.hasDecoration(TextDecoration.STRIKETHROUGH),
            )
        }
        component.children().forEach { flatten(it, effective, out) }
    }

    /**
     * Draws a rendered preview of [value] onto [display], starting at pixel
     * [leftX], across text rows [firstRow]..[lastRow]. Newlines break rows;
     * each row is truncated to [maxChars] characters with a `...` marker.
     *
     * @param display the title builder to append onto.
     * @param value the template to preview.
     * @param leftX left pixel of every rendered row.
     * @param firstRow first text row (0..6).
     * @param lastRow last text row (0..6).
     * @param maxChars maximum characters rendered per row.
     */
    fun draw(display: DisplayBuilder, value: String, leftX: Int, firstRow: Int, lastRow: Int, maxChars: Int) {
        val lines = splitLines(runs(value))
        val rowCount = lastRow - firstRow + 1
        lines.take(rowCount).forEachIndexed { index, lineRuns ->
            val row = firstRow + index
            display.moveTo(leftX)
            var budget = maxChars
            val overflowed = lineRuns.sumOf { it.text.length } > maxChars ||
                (index == rowCount - 1 && lines.size > rowCount)
            for (run in lineRuns) {
                if (budget <= 0) break
                val text = if (run.text.length > budget) run.text.substring(0, budget) else run.text
                budget -= text.length
                if (text.isEmpty()) continue
                display.styledText(
                    text, row, run.color ?: NamedTextColor.WHITE,
                    run.bold, run.italic, run.obfuscated, run.underlined, run.strikethrough,
                )
            }
            if (overflowed) {
                display.styledText("...", row, NamedTextColor.GRAY)
            }
            display.toStart()
        }
    }

    private fun splitLines(runs: List<Run>): List<List<Run>> {
        val lines = ArrayList<MutableList<Run>>()
        lines.add(ArrayList())
        for (run in runs) {
            val parts = run.text.split("\n")
            parts.forEachIndexed { i, part ->
                if (i > 0) lines.add(ArrayList())
                if (part.isNotEmpty()) lines.last().add(run.copy(text = part))
            }
        }
        return lines
    }

    private fun Style.hasDecoration(decoration: TextDecoration): Boolean =
        decoration(decoration) == TextDecoration.State.TRUE
}
