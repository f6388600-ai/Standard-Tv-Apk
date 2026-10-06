package com.livetv.premium

import android.content.Context

/** Remembers the last watched channels (most recent first). */
object RecentStore {
    private const val PREFS = "recent_prefs"
    private const val KEY = "ids"
    private const val MAX = 20

    fun load(context: Context): List<String> = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
    } catch (_: Throwable) {
        emptyList()
    }

    fun add(context: Context, id: String): List<String> {
        val list = (listOf(id) + load(context).filter { it != id }).take(MAX)
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, list.joinToString("\n"))
                .apply()
        } catch (_: Throwable) {
        }
        return list
    }

    fun clear(context: Context): List<String> {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        } catch (_: Throwable) {
        }
        return emptyList()
    }
}
