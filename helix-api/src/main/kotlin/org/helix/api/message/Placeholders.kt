package org.helix.api.message

/**
 * Substitutes `{name}` placeholders in a template: first the given
 * message-specific parameters, then the network-wide [GlobalPlaceholders]
 * (for example the global `{prefix}`).
 *
 * @param template the message template.
 * @param params placeholder name to value pairs.
 * @return the template with every `{name}` replaced.
 */
fun applyPlaceholders(template: String, params: Array<out Pair<String, String>>): String {
    var result = template
    params.forEach { (name, value) -> result = result.replace("{$name}", value) }
    return GlobalPlaceholders.apply(result)
}

/**
 * Prepends the network prefix to a chat-message template.
 *
 * Every [Messages.format]/[Messages.formatFor] result carries the network
 * prefix automatically; screens ([Messages.screenFor]) and raw reads do
 * not. A template already containing `{prefix}` keeps its own placement,
 * and while no prefix is configured the template stays untouched — so a
 * network without a prefix (and every unit test) sees no leading junk.
 *
 * @param template the resolved chat-message template.
 * @return the template with the prefix prepended.
 */
fun prefixed(template: String): String {
    if (template.isBlank() || template.contains("{prefix}")) {
        return template
    }
    val prefix = GlobalPlaceholders.apply("{prefix}")
    if (prefix == "{prefix}" || prefix.isBlank()) {
        return template
    }
    return "$prefix $template"
}
