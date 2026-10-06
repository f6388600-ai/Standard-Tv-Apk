package com.livetv.premium

import android.content.Context

private fun prefs(context: Context) =
    context.getSharedPreferences("hasu_prefs", Context.MODE_PRIVATE)

private fun readSet(context: Context, key: String): Set<String> = try {
    prefs(context).getString(key, "").orEmpty().split("\n").filter { it.isNotBlank() }.toCollection(LinkedHashSet())
} catch (_: Throwable) {
    emptySet()
}

private fun writeSet(context: Context, key: String, set: Set<String>) {
    try {
        prefs(context).edit().putString(key, set.joinToString("\n")).apply()
    } catch (_: Throwable) {
    }
}

/** Channels the user marked with a heart. */
object FavoriteStore {
    fun load(context: Context): Set<String> = readSet(context, "favorites")

    fun toggle(context: Context, id: String): Set<String> {
        val set = LinkedHashSet(load(context))
        if (!set.add(id)) set.remove(id)
        writeSet(context, "favorites", set)
        return set
    }

    fun clear(context: Context): Set<String> {
        writeSet(context, "favorites", emptySet())
        return emptySet()
    }
}

/** Channels that failed to play last time (shown with a red dot). */
object HealthStore {
    fun load(context: Context): Set<String> = readSet(context, "failed")

    fun mark(context: Context, id: String, ok: Boolean): Set<String> {
        val set = LinkedHashSet(load(context))
        val changed = if (ok) set.remove(id) else set.add(id)
        if (changed) writeSet(context, "failed", set)
        return set
    }
}

object SettingsStore {
    fun autoResume(context: Context): Boolean = prefs(context).getBoolean("auto_resume", true)
    fun setAutoResume(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_resume", value).apply()
    }

    fun lowData(context: Context): Boolean = prefs(context).getBoolean("low_data", false)
    fun setLowData(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("low_data", value).apply()
    }

    /** 0 = automatic (by device memory), otherwise minutes. */
    fun bufferMinutes(context: Context): Int = prefs(context).getInt("buffer_min", 0)
    fun setBufferMinutes(context: Context, value: Int) {
        prefs(context).edit().putInt("buffer_min", value).apply()
    }

    fun lastChannel(context: Context): String? = prefs(context).getString("last_channel", null)
    fun setLastChannel(context: Context, id: String) {
        prefs(context).edit().putString("last_channel", id).apply()
    }
}
