package org.helix.api.message

import java.util.concurrent.ConcurrentHashMap

/**
 * Network-wide placeholder values applied to every formatted message after
 * the message-specific parameters — for example the global `{prefix}`.
 *
 * The node sets the values (from the panel-editable network settings);
 * every [Messages.format] call picks them up automatically, so `{prefix}`
 * works in every configurable message of every addon. Message-specific
 * parameters win: a placeholder already replaced locally is untouched.
 */
object GlobalPlaceholders {
    private val values = ConcurrentHashMap<String, String>()

    /**
     * Sets (or clears) a global placeholder.
     *
     * @param name placeholder name without braces, for example `prefix`.
     * @param value the replacement; empty removes the placeholder.
     */
    fun set(name: String, value: String) {
        if (value.isEmpty()) values.remove(name) else values[name] = value
    }

    /**
     * Applies all global placeholders to a text.
     *
     * @param text the text after message-specific substitution.
     * @return the text with every global `{name}` replaced.
     */
    fun apply(text: String): String {
        var result = text
        values.forEach { (name, value) -> result = result.replace("{$name}", value) }
        return result
    }
}
