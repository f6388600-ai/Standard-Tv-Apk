package com.livetv.premium

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object PlaylistRepository {
    private const val PREFS = "playlist_cache"
    private const val KEY_DATA = "channels_json"

    suspend fun load(context: Context): List<Channel> = withContext(Dispatchers.IO) {
        val cached = readCache(context)
        return@withContext try {
            val connection = URL(BrandConfig.PLAYLIST_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "LiveTVPremium/1.0")
            connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                val parsed = parseM3u(text)
                if (parsed.isNotEmpty()) saveCache(context, parsed)
                if (parsed.isNotEmpty()) parsed else cached
            }.also { connection.disconnect() }
        } catch (_: Exception) {
            cached
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val output = LinkedHashMap<String, Channel>()
        var pendingInfo: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingInfo = line
                continue
            }
            if (line.startsWith("#")) continue
            val info = pendingInfo ?: continue
            val url = line
            val name = info.substringAfterLast(",", "Live Channel").trim().ifBlank { "Live Channel" }
            val tvgId = attr(info, "tvg-id")
            val tvgName = attr(info, "tvg-name")
            val logo = attr(info, "tvg-logo")
            val group = attr(info, "group-title").ifBlank { "Live TV" }
            val finalName = tvgName.ifBlank { name }
            val key = normalize(if (tvgId.isNotBlank()) tvgId else finalName)

            if (key.isNotBlank() && !output.containsKey(key)) {
                output[key] = Channel(
                    id = key,
                    name = finalName,
                    url = url,
                    logo = logo,
                    category = group
                )
            }
            pendingInfo = null
        }
        return output.values.toList()
    }

    private fun attr(line: String, key: String): String {
        val regex = Regex("""$key\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), "")
            .trim()

    private fun readCache(context: Context): List<Channel> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        Channel(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            url = o.getString("url"),
                            logo = o.optString("logo"),
                            category = o.optString("category", "Live TV")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCache(context: Context, channels: List<Channel>) {
        val array = JSONArray()
        channels.forEach {
            array.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("url", it.url)
                    put("logo", it.logo)
                    put("category", it.category)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, array.toString())
            .apply()
    }
}
