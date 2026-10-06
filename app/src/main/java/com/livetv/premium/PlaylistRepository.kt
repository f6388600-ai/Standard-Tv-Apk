package com.livetv.premium

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object PlaylistRepository {
    private const val PREFS = "playlist_cache"
    private const val KEY_DATA = "channels_json"
    private const val KEY_SYNC = "last_sync"

    fun lastSync(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SYNC, 0L)

    data class LoadResult(val channels: List<Channel>, val fresh: Boolean)

    suspend fun load(context: Context): List<Channel> = loadInternal(context, false).channels

    /** Manual refresh: always goes to the network. `fresh` tells if new data was really fetched. */
    suspend fun refresh(context: Context): LoadResult = loadInternal(context, true)

    private suspend fun loadInternal(context: Context, force: Boolean): LoadResult = withContext(Dispatchers.IO) {
        val cached = readCache(context)
        val lastSync = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SYNC, 0L)

        if (!force && cached.isNotEmpty() && System.currentTimeMillis() - lastSync < BrandConfig.REFRESH_INTERVAL_MS) {
            return@withContext LoadResult(cached, false)
        }

        try {
            val urls = BrandConfig.PLAYLIST_URLS
            val texts = coroutineScope {
                urls.map { url ->
                    async(Dispatchers.IO) {
                        try {
                            fetchPlaylist(url)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            // If one source could not be downloaded, keep the last full list instead of a partial one.
            if (texts.isEmpty() || (texts.size < urls.size && cached.isNotEmpty())) {
                return@withContext LoadResult(cached, false)
            }
            val parsed = parseAndSelectLive(texts)
            if (parsed.isNotEmpty()) {
                saveCache(context, parsed)
                return@withContext LoadResult(parsed, true)
            }
            LoadResult(cached, false)
        } catch (_: Exception) {
            LoadResult(cached, false)
        }
    }

    private fun fetchPlaylist(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 7_000
            connection.readTimeout = 12_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "HasuLiveTv/1.0")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun parseAndSelectLive(texts: List<String>): List<Channel> = coroutineScope {
        val candidates = parseCandidates(texts)
        val gate = Semaphore(16)
        candidates.map { candidate ->
            async(Dispatchers.IO) {
                val url = try {
                    gate.withPermit { chooseWorkingUrl(candidate.urls) }
                } catch (e: Exception) {
                    candidate.urls.firstOrNull().orEmpty()
                }
                if (url.isBlank()) null else Channel(
                    id = candidate.id,
                    name = candidate.name,
                    url = url,
                    logo = candidate.logo,
                    category = candidate.category
                )
            }
        }.awaitAll().filterNotNull()
    }

    private fun parseCandidates(texts: List<String>): List<ChannelCandidate> {
        val output = LinkedHashMap<String, MutableChannel>()
        texts.forEach { parseInto(it, output) }
        return output.values.map {
            ChannelCandidate(it.id, it.name, it.logo, it.category, it.urls.toList())
        }
    }

    private fun parseInto(text: String, output: LinkedHashMap<String, MutableChannel>) {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        var pendingInfo: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingInfo = line
                continue
            }
            if (line.startsWith("#")) continue
            val info = pendingInfo ?: continue
            val url = line.trim()
            val name = info.substringAfterLast(",", "Live Channel").trim().ifBlank { "Live Channel" }
            val tvgId = attr(info, "tvg-id")
            val tvgName = attr(info, "tvg-name")
            val logo = attr(info, "tvg-logo")
            val group = attr(info, "group-title").ifBlank { "Live TV" }
            val finalName = tvgName.ifBlank { name }
            val key = normalize(if (tvgId.isNotBlank()) tvgId else finalName)

            if (key.isNotBlank()) {
                val current = output.getOrPut(key) {
                    MutableChannel(key, finalName, logo, group, mutableListOf())
                }
                if (current.logo.isBlank() && logo.isNotBlank()) current.logo = logo
                if (current.category == "Live TV" && group.isNotBlank()) current.category = group
                if (url.isNotBlank() && !current.urls.contains(url)) current.urls += url
            }
            pendingInfo = null
        }
    }

    private suspend fun chooseWorkingUrl(urls: List<String>): String = coroutineScope {
        if (urls.isEmpty()) return@coroutineScope ""
        if (urls.size == 1) return@coroutineScope urls.first()

        val winner = CompletableDeferred<String?>()
        val jobs = urls.map { url ->
            launch(Dispatchers.IO) {
                if (probeUrl(url)) winner.complete(url)
            }
        }
        val result = withTimeoutOrNull(3_500L) { winner.await() }
        jobs.forEach { it.cancel() }
        result ?: urls.first()
    }

    private fun probeUrl(url: String): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_500
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "HasuLiveTv/1.0")
            val code = connection.responseCode
            connection.disconnect()
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }

    private fun attr(line: String, key: String): String {
        val regex = Regex("""$key\s*=\s*[\"']([^\"']*)[\"']""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).replace(Regex("""[^a-z0-9]+"""), "").trim()

    private fun readCache(context: Context): List<Channel> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(Channel(o.getString("id"), o.getString("name"), o.getString("url"), o.optString("logo"), o.optString("category", "Live TV")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCache(context: Context, channels: List<Channel>) {
        val array = JSONArray()
        channels.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("url", it.url)
                put("logo", it.logo)
                put("category", it.category)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATA, array.toString())
            .putLong(KEY_SYNC, System.currentTimeMillis())
            .apply()
    }

    private data class ChannelCandidate(
        val id: String,
        val name: String,
        val logo: String,
        val category: String,
        val urls: List<String>
    )

    private data class MutableChannel(
        val id: String,
        val name: String,
        var logo: String,
        var category: String,
        val urls: MutableList<String>
    )
}
