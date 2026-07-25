package org.helix.addons.guard

/**
 * Primitive type of an IGuard config value.
 *
 * @property id lowercase identifier used in action responses and the panel.
 */
enum class GuardValueType(val id: String) {
    /** Free-form text; rendered double-quoted in YAML. */
    STRING("string"),

    /** Whole number; rendered bare in YAML. */
    INT("int"),

    /** Decimal number; rendered bare in YAML. */
    DOUBLE("double"),

    /** `true` or `false`; rendered bare in YAML. */
    BOOLEAN("boolean"),
    ;

    /**
     * Validates and canonicalizes a raw value.
     *
     * @param raw the user-supplied value.
     * @return the canonical string form, or `null` when the value does not
     *   parse as this type.
     */
    fun canonicalize(raw: String): String? = when (this) {
        STRING -> raw
        INT -> raw.trim().toLongOrNull()?.toString()
        DOUBLE -> raw.trim().toDoubleOrNull()?.toString()
        BOOLEAN -> raw.trim().lowercase().takeIf { it == "true" || it == "false" }
    }
}
