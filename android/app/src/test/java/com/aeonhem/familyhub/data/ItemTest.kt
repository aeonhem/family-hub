package com.aeonhem.familyhub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Mirrors bot/tests/test_calendar_store.py so the app and bot agree.
class ItemTest {

    @Test
    fun titles() {
        assertEquals(Triple(Kind.DINNER, false, "Tacos"), Item.parseTitle("🍽 Tacos"))
        assertEquals(Triple(Kind.CHORE, false, "Bins out"), Item.parseTitle("☐ Bins out"))
        assertEquals(Triple(Kind.CHORE, true, "Bins out"), Item.parseTitle("✅ Bins out"))
        assertEquals(Triple(Kind.MEMO, false, "Milk please"), Item.parseTitle("📣 Milk please"))
        assertEquals(Triple(Kind.NOTE, false, "Wi-Fi changed"), Item.parseTitle("📝 Wi-Fi changed"))
        assertEquals(Triple(Kind.EVENT, false, "Swimming"), Item.parseTitle("Swimming"))
    }

    @Test
    fun titleWithEmojiVariationSelector() {
        // "🍽️" is the plate emoji followed by U+FE0F, as some keyboards type it.
        assertEquals(Triple(Kind.DINNER, false, "Tacos"), Item.parseTitle("🍽️ Tacos"))
    }

    @Test
    fun descriptionRoundTrip() {
        val (meta, note) = Item.parseDescription("for: Erlina\nFrom: Sally\nRemember gloves")
        assertEquals(mapOf("for" to "Erlina", "from" to "Sally"), meta)
        assertEquals("Remember gloves", note)
        assertEquals(
            "for: Erlina\nRemember gloves",
            Item.buildDescription(mapOf("for" to "Erlina", "done-by" to null), note),
        )
    }

    @Test
    fun emptyDescription() {
        assertEquals(emptyMap<String, String>() to "", Item.parseDescription(null))
        assertEquals(emptyMap<String, String>() to "", Item.parseDescription("  "))
        assertEquals("", Item.buildDescription(emptyMap()))
    }

    @Test
    fun coversEveryDayOfAMultiDayEvent() {
        val mon = LocalDate.of(2026, 10, 5)
        val camp = Item(1, Kind.EVENT, "Camp", mon, null, true, false, emptyMap(), "", endDate = mon.plusDays(4))
        assertTrue(camp.covers(mon))
        assertTrue(camp.covers(mon.plusDays(4)))
        assertFalse(camp.covers(mon.plusDays(5)))
        assertFalse(camp.covers(mon.minusDays(1)))
    }

    @Test
    fun keySeparatesOccurrencesOfOneSeries() {
        val day = LocalDate.of(2026, 10, 6)
        val a = Item(7, Kind.CHORE, "Bins", day, null, true, false, emptyMap(), "", begin = 1L, recurring = true)
        val b = a.copy(date = day.plusDays(7), begin = 2L)
        assertTrue(a.key != b.key)
    }
}
