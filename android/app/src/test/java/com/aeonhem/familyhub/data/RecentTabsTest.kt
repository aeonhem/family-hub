package com.aeonhem.familyhub.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentTabsTest {
    private val all = listOf("today", "dinner", "chores", "memos", "shopping", "lights", "map")

    @Test fun noHistoryKeepsTheUsualOrder() = assertEquals(all, RecentTabs.order(all, emptyList()))

    @Test fun recentTabsComeFirstNewestLeft() = assertEquals(
        listOf("map", "lights", "dinner", "today", "chores", "memos", "shopping"),
        RecentTabs.order(all, listOf("map", "lights", "dinner")),
    )

    @Test fun unknownAndRepeatedTabsAreSkipped() = assertEquals(
        listOf("map", "today", "dinner", "chores", "memos", "shopping", "lights"),
        RecentTabs.order(all, listOf("gone", "map", "map")),
    )

    @Test fun usedMovesToFrontAndKeepsThree() {
        assertEquals(listOf("map"), RecentTabs.used(emptyList(), "map"))
        assertEquals(listOf("chores", "map", "lights"), RecentTabs.used(listOf("map", "lights", "dinner"), "chores"))
        assertEquals(listOf("lights", "map", "dinner"), RecentTabs.used(listOf("map", "lights", "dinner"), "lights"))
    }
}
