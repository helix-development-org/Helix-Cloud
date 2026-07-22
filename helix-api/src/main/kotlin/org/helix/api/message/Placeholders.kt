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
