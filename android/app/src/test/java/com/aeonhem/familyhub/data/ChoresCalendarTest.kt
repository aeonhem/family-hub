package com.aeonhem.familyhub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChoresCalendarTest {

    private fun cal(id: Long, name: String, canWrite: Boolean = true) =
        CalendarInfo(id, name, "julian@example.com", "com.google", canWrite)

    private val family = cal(1, "Family")

    @Test
    fun findsFamilyChoresByName() {
        val cals = listOf(family, cal(2, "Julian"), cal(3, " family chores "))
        assertEquals(3L, CalendarInfo.pickChores(cals, -1L, family.id)?.id)
    }

    @Test
    fun noneMeansChoresStayOnFamily() {
        assertNull(CalendarInfo.pickChores(listOf(family, cal(2, "Julian")), -1L, family.id))
    }

    @Test
    fun skipsReadOnlyCopies() {
        assertNull(CalendarInfo.pickChores(listOf(family, cal(2, "Family Chores", canWrite = false)), -1L, family.id))
    }

    @Test
    fun savedOneWinsWhileItIsThere() {
        val cals = listOf(family, cal(2, "Family Chores"), cal(5, "Family Chores"))
        assertEquals(5L, CalendarInfo.pickChores(cals, 5L, family.id)?.id)
        assertEquals(2L, CalendarInfo.pickChores(cals, 9L, family.id)?.id)
    }
}
