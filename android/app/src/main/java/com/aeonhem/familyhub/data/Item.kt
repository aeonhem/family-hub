package com.aeonhem.familyhub.data

import java.time.LocalDate
import java.time.LocalDateTime

/** What a Family calendar event represents, decided by its title prefix. */
enum class Kind { EVENT, DINNER, CHORE, MEMO, NOTE }

/**
 * One event on the Family calendar, parsed per docs/calendar-conventions.md.
 *
 * [title] has the prefix stripped. [meta] holds the `key: value` lines from
 * the description (`for`, `from`, `cook`, `done-by`), keys lower-cased.
 */
data class Item(
    val eventId: Long,
    val kind: Kind,
    val title: String,
    val date: LocalDate,
    val start: LocalDateTime?,
    val allDay: Boolean,
    val done: Boolean,
    val meta: Map<String, String>,
    val note: String,
) {
    val forWho: String? get() = meta["for"]
    val from: String? get() = meta["from"]
    val cook: String? get() = meta["cook"]
    val doneBy: String? get() = meta["done-by"]

    companion object {
        const val DINNER = "🍽"
        const val CHORE_OPEN = "☐"
        const val CHORE_DONE = "✅"
        const val MEMO = "📣"
        const val NOTE = "📝"

        private val metaLine = Regex("""^\s*(for|from|cook|done-by)\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

        /** Splits a title into its kind, done flag and the text after the prefix. */
        fun parseTitle(raw: String): Triple<Kind, Boolean, String> {
            // Strip a trailing emoji variation selector so "🍽️" and "🍽" both match.
            val t = raw.trim().replaceFirst("️", "")
            fun rest(prefix: String) = t.removePrefix(prefix).trim()
            return when {
                t.startsWith(DINNER) -> Triple(Kind.DINNER, false, rest(DINNER))
                t.startsWith(CHORE_OPEN) -> Triple(Kind.CHORE, false, rest(CHORE_OPEN))
                t.startsWith(CHORE_DONE) -> Triple(Kind.CHORE, true, rest(CHORE_DONE))
                t.startsWith(MEMO) -> Triple(Kind.MEMO, false, rest(MEMO))
                t.startsWith(NOTE) -> Triple(Kind.NOTE, false, rest(NOTE))
                else -> Triple(Kind.EVENT, false, raw.trim())
            }
        }

        /** Separates `key: value` lines from free text in a description. */
        fun parseDescription(desc: String?): Pair<Map<String, String>, String> {
            if (desc.isNullOrBlank()) return emptyMap<String, String>() to ""
            val meta = mutableMapOf<String, String>()
            val rest = mutableListOf<String>()
            for (line in desc.lines()) {
                val m = metaLine.matchEntire(line)
                if (m != null) meta[m.groupValues[1].lowercase()] = m.groupValues[2] else rest += line
            }
            return meta to rest.joinToString("\n").trim()
        }

        fun buildDescription(meta: Map<String, String?>, note: String = ""): String =
            (meta.filterValues { !it.isNullOrBlank() }.map { (k, v) -> "$k: $v" } +
                listOfNotNull(note.ifBlank { null })).joinToString("\n")
    }
}
