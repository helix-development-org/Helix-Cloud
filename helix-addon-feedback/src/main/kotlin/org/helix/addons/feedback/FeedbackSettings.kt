package org.helix.addons.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Tunable settings of the feedback addon, stored as one document and editable through `feedback.settings`.
 *
 * @property testphase whether the closed test phase is running (new reports are accepted while true).
 * @property cooldownSeconds minimum seconds between two reports of the same player.
 * @property maxPerDay maximum reports per player and calendar day in [zone].
 * @property zone time zone of the calendar day.
 * @property logMinutes how far back service log lines are attached.
 * @property logMaxLines maximum number of attached log lines.
 * @property maxAttachmentBytes maximum size of one attachment.
 * @property agreementVersion current version of the tester agreement.
 * @property points points per severity for confirmed reports.
 * @property commentCooldownSeconds minimum seconds between two comments of the same (non-staff) player.
 * @property maxCommentsPerDay maximum comments per (non-staff) player and calendar day.
 * @property maxAttachmentsPerReport maximum attachments a non-staff caller may add to one report.
 * @property maxAutoPerServiceHour maximum new automatic reports per service and hour (existing ones still count up).
 * @property rewards release reward table, one entry per tier (`minPoints`, `kronen`, `keys`, `items`, `note`).
 */
@Serializable
data class FeedbackSettings(
    val testphase: Boolean = true,
    val cooldownSeconds: Int = 60,
    val maxPerDay: Int = 20,
    val zone: String = "Europe/Berlin",
    val logMinutes: Int = 10,
    val logMaxLines: Int = 100,
    val maxAttachmentBytes: Int = 2 * 1024 * 1024,
    val agreementVersion: Int = 1,
    val points: Map<String, Int> = mapOf(
        Severities.MINOR to 1,
        Severities.ANNOYING to 2,
        Severities.BLOCKING to 4,
        Severities.CRASH to 6,
        Severities.EXPLOIT to 10,
    ),
    val commentCooldownSeconds: Int = 10,
    val maxCommentsPerDay: Int = 50,
    val maxAttachmentsPerReport: Int = 10,
    val maxAutoPerServiceHour: Int = 20,
    val rewards: List<JsonObject> = emptyList(),
)
