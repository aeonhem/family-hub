package com.aeonhem.familyhub.data

/** The bottom bar's order: each person's 3 most recently used tabs come first. */
object RecentTabs {
    const val KEEP = 3

    /** [all] in their usual order, with the ones in [recent] (newest first) moved to the front. */
    fun <T> order(all: List<T>, recent: List<T>): List<T> {
        val front = recent.distinct().filter { it in all }.take(KEEP)
        return front + all.filter { it !in front }
    }

    /** [recent] after [used] was tapped: [used] first, no repeats, at most [KEEP]. */
    fun <T> used(recent: List<T>, used: T): List<T> = (listOf(used) + recent.filter { it != used }).take(KEEP)

    fun key(person: String) = "recentTabs_$person"
}
