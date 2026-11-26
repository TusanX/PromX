// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.data.radio

import io.github.currencortex.music.data.song.AudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public Radio Browser catalog. Streams remain hosted by their originating stations. */
class FreeRadioRepository(
    private val endpoint: String = "https://de1.api.radio-browser.info",
) {
    data class Station(
        val id: String,
        val name: String,
        val url: String,
        val homepage: String,
        val favicon: String,
        val country: String,
        val language: String,
        val tags: String,
        val codec: String,
        val bitrate: Int,
        val votes: Int,
        val clicks: Int,
    ) {
        fun playable(): AudioSource = AudioSource(
            url = url,
            level = "网络电台" + bitrate.takeIf { it > 0 }?.let { " · ${it}kbps" }.orEmpty(),
            sampleRate = 0,
            channelCount = 0,
            format = codec.lowercase(),
            source = "radio-browser",
            validForSeconds = 300,
        )
    }

    suspend fun top(limit: Int = 40): List<Station> = withContext(Dispatchers.IO) {
        request(
            path = "/json/stations/topclick/${limit.coerceIn(1, 100)}",
            query = "hidebroken=true",
        )
    }

    suspend fun search(query: String, limit: Int = 40): List<Station> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isBlank()) return@withContext top(limit)
        val cap = limit.coerceIn(1, 100)
        val common = "order=clickcount&reverse=true&limit=$cap&hidebroken=true"
        (request("/json/stations/search", "name=${encode(keyword)}&$common") +
            request("/json/stations/search", "tag=${encode(keyword)}&$common"))
            .distinctBy { it.id }
            .take(cap)
    }

    private fun request(path: String, query: String): List<Station> = runCatching {
        val connection = (URL("$endpoint$path?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "PromX/1.6.17")
            setRequestProperty("Accept", "application/json")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(body).jsonArray
        root.mapNotNull { element ->
            val item = element.jsonObject
            val url = item["url_resolved"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("http") }
                ?: item["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            if (item["lastcheckok"]?.jsonPrimitive?.intOrNull == 0) return@mapNotNull null
            val id = item["stationuuid"]?.jsonPrimitive?.contentOrNull
                ?: "$url|${item["name"]?.jsonPrimitive?.contentOrNull}"
            Station(
                id = id,
                name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifBlank { "未命名电台" },
                url = url,
                homepage = item["homepage"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                favicon = item["favicon"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                country = item["country"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                language = item["language"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                tags = item["tags"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                codec = item["codec"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                bitrate = item["bitrate"]?.jsonPrimitive?.intOrNull ?: 0,
                votes = item["votes"]?.jsonPrimitive?.intOrNull ?: 0,
                clicks = item["clickcount"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
