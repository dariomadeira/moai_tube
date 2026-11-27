package com.infomak.moai.tube

import android.content.Context
import android.util.Log
import com.infomak.moai.tube.contract.IYouTubePlugin
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Implementación del plugin `moai_tube` sobre MediaServiceCore (MIT).
 *
 * - Activa `FORMATS_EXTENDED_HLS` y rota clientes de MediaServiceCore
 *   (`TV_SAMSUNG` DASH, `VISIONOS` HLS, `TV_DOWNGRADED`, `IOS`, `ANDROID_VR`)
 *   para obtener calidades HD/1080p/4K reproducibles por Media3 (sin SABR).
 * - Expone búsqueda, sugerencias por video (sin cuenta), catálogo de canal
 *   y feed cronológico por RSS con fecha exacta de publicación.
 */
class MoaiTubePlugin : IYouTubePlugin {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var preferredClientName: String? = null

    private val appClientClass: Class<*> by lazy {
        Class.forName("com.liskovsoft.youtubeapi.common.helpers.AppClient")
    }

    private val appClientValueOf by lazy {
        appClientClass.getMethod("valueOf", String::class.java)
    }

    private val getVideoInfoMethod by lazy {
        VideoInfoService::class.java.getMethod(
            "getVideoInfo",
            appClientClass,
            String::class.java,
            String::class.java,
        )
    }

    override fun pluginVersion(): String = PLUGIN_VERSION

    override fun init(context: Context, pluginDir: File) {
        val app = context.applicationContext ?: context
        appContext = app
        PluginAssets.pluginDir = pluginDir
        val prefs = GlobalPreferences.instance(app)
        val sysLocale = Locale.getDefault()
        val country = if (sysLocale.language.equals("es", ignoreCase = true) && sysLocale.country.length == 2) {
            sysLocale.country.uppercase(Locale.US)
        } else {
            "AR"
        }
        prefs.preferredLanguage = "es"
        prefs.preferredCountry = country

        val data = MediaServiceData.instance()
        data.setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, true)
        initialized = true
        Log.i(TAG, "MoaiTubePlugin v$PLUGIN_VERSION listo (hl=es gl=$country, pluginDir=${pluginDir.absolutePath})")
    }

    override fun resolve(videoId: String): String {
        val ctx = requireContext()
        val videoInfoService = VideoInfoService.instance()

        var bestInfo: MediaItemFormatInfo? = null
        val order = buildList {
            preferredClientName?.let { add(it) }
            for (name in HD_CLIENTS) {
                if (name !in this) add(name)
            }
        }

        for (clientName in order) {
            try {
                val clientObj = appClientValueOf.invoke(null, clientName) ?: continue
                val videoInfo = getVideoInfoMethod.invoke(
                    videoInfoService,
                    clientObj,
                    videoId,
                    null,
                ) as? VideoInfo ?: continue
                val info = YouTubeMediaItemFormatInfo.from(videoInfo) ?: continue
                if (info.isUnplayable) {
                    Log.w(TAG, "resolve($videoId) $clientName unplayable: ${info.playabilityReason}")
                    if (bestInfo == null) bestInfo = info
                    continue
                }
                val hasDash = info.containsDashFormats()
                val hasHls = info.containsHlsUrl()
                Log.i(
                    TAG,
                    "resolve($videoId) $clientName (${info.clientInfo?.clientName}): " +
                        "dash=$hasDash hls=$hasHls sabr=${info.containsSabrFormats()} " +
                        "url=${info.containsUrlFormats()} adapt=${info.adaptiveFormats?.size ?: 0}",
                )
                if (hasHls) {
                    bestInfo = info
                    preferredClientName = clientName
                    break
                }
                if (hasDash && (bestInfo == null || bestInfo.isUnplayable || !bestInfo.containsDashFormats())) {
                    bestInfo = info
                    preferredClientName = clientName
                } else if (bestInfo == null || bestInfo.isUnplayable) {
                    bestInfo = info
                }
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                Log.w(TAG, "resolve($videoId) $clientName falló: ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }

        val info = bestInfo ?: return errorJson("sin respuesta de YouTube")
        return buildResolveJson(ctx, videoId, info)
    }

    private fun buildResolveJson(
        ctx: Context,
        videoId: String,
        info: MediaItemFormatInfo,
    ): String {
        val clientName = info.clientInfo?.clientName ?: "unknown"
        val userAgent = info.clientInfo?.userAgent?.takeIf { it.isNotBlank() }
            ?: DefaultHeaders.APP_USER_AGENT
        val summary = "client=$clientName dash=${info.containsDashFormats()} " +
            "sabr=${info.containsSabrFormats()} hls=${info.containsHlsUrl()} " +
            "url=${info.containsUrlFormats()} adaptativos=${info.adaptiveFormats?.size ?: 0} " +
            "directos=${info.urlFormats?.size ?: 0} unplayable=${info.isUnplayable} " +
            "motivo=${info.playabilityReason}"

        val out = JSONObject()
            .put("videoId", videoId)
            .put("title", info.title ?: "")
            .put("author", info.author ?: "")
            .put("channelId", info.channelId ?: "")
            .put("client", clientName)
            .put("summary", summary)
            .put("headers", JSONObject().put("User-Agent", userAgent))
            .put("subtitles", buildSubtitlesJson(info))

        if (info.isUnplayable) {
            return out.put("error", info.playabilityReason ?: "no se puede reproducir").toString()
        }

        // Preferimos HLS (FORMATS_EXTENDED_HLS) sobre DASH: en YouTube 2025/2026
        // los streams DASH de ANDROID_VR sin PoToken devuelven HTTP 403 a los 60 s,
        // mientras que HLS reproduce videos largos completos en HD sin cortes.
        if (info.containsHlsUrl()) {
            val maxQuality = if (info.isLive) {
                "live"
            } else {
                info.adaptiveFormats
                    ?.filter { !it.qualityLabel.isNullOrBlank() }
                    ?.maxByOrNull { it.height }
                    ?.qualityLabel
                    ?: "HD"
            }
            return out
                .put("url", info.hlsManifestUrl)
                .put("kind", "hls")
                .put("quality", maxQuality)
                .toString()
        }

        if (info.containsDashFormats()) {
            val file = File(ctx.cacheDir, "moai_tube_$videoId.mpd")
            info.createMpdStream()?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            if (file.isFile && file.length() > 0) {
                val maxQuality = info.adaptiveFormats
                    ?.filter { !it.qualityLabel.isNullOrBlank() }
                    ?.maxByOrNull { it.height }
                    ?.qualityLabel
                    ?: "HD"
                return out
                    .put("url", "file://${file.absolutePath}")
                    .put("kind", "dash")
                    .put("quality", maxQuality)
                    .toString()
            }
        }

        val direct = info.urlFormats?.firstOrNull { !it.url.isNullOrEmpty() }
        if (direct != null) {
            return out
                .put("url", direct.url)
                .put("kind", "direct")
                .put("quality", direct.qualityLabel ?: "360p")
                .toString()
        }

        return out.put("error", "sin formatos reproducibles por Media3").toString()
    }

    override fun search(query: String): String {
        requireContext()
        val content = YouTubeServiceManager.instance().contentService
        val groups = content.getSearch(query).orEmpty()
        val items = JSONArray()
        for (group in groups) {
            for (item in group.mediaItems.orEmpty()) {
                if (!item.videoId.isNullOrEmpty()) {
                    items.put(itemToJson(item))
                }
            }
        }
        return JSONObject()
            .put("query", query)
            .put("items", items)
            .toString()
    }

    override fun suggestions(videoId: String): String {
        requireContext()
        val meta = YouTubeServiceManager.instance().mediaItemService.getMetadata(videoId)
            ?: return JSONObject().put("rows", JSONArray()).toString()
        val rows = groupsToJson(meta.suggestions.orEmpty())
        return JSONObject()
            .put("videoId", videoId)
            .put("title", meta.title ?: "")
            .put("author", meta.author ?: "")
            .put("channelId", meta.channelId ?: "")
            .put("rows", rows)
            .toString()
    }

    override fun channelFeed(channelIds: List<String>, filterShorts: Boolean): String {
        requireContext()
        val cleanIds = channelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.isEmpty()) {
            return JSONObject().put("items", JSONArray()).toString()
        }
        val content = YouTubeServiceManager.instance().contentService
        val feed = content.getRssFeed(*cleanIds.toTypedArray())
        val items = JSONArray()
        for (item in feed?.mediaItems.orEmpty()) {
            if (item.videoId.isNullOrEmpty()) continue
            if (filterShorts && item.isShorts) continue
            items.put(itemToJson(item))
        }
        return JSONObject().put("items", items).toString()
    }

    override fun channelVideos(channelId: String): String {
        requireContext()
        val content = YouTubeServiceManager.instance().contentService
        val groups = content.getChannelObserve(channelId).blockingFirst(emptyList())
        val rows = groupsToJson(groups.orEmpty())
        return JSONObject()
            .put("channelId", channelId)
            .put("rows", rows)
            .toString()
    }

    override fun home(fallbackQueries: List<String>?): String {
        requireContext()
        val content = YouTubeServiceManager.instance().contentService
        fun isEmpty(g: List<MediaGroup>?) = g.isNullOrEmpty() || g.all { it.mediaItems.isNullOrEmpty() }

        var groups: List<MediaGroup>? = runCatching { content.home }.getOrNull()
        if (isEmpty(groups)) {
            groups = runCatching {
                content.trendingObserve.blockingFirst(emptyList())
            }.getOrNull()
        }
        if (isEmpty(groups)) {
            val queries = fallbackQueries?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_FALLBACK_QUERIES
            val todayTop = SearchOptions.UPLOAD_DATE_TODAY or SearchOptions.SORT_BY_VIEW_COUNT
            val weekTop = SearchOptions.UPLOAD_DATE_THIS_WEEK or SearchOptions.SORT_BY_VIEW_COUNT
            groups = queries.mapNotNull { q ->
                val todayGroup = runCatching {
                    content.getSearch(q, todayTop)?.firstOrNull { !it.mediaItems.isNullOrEmpty() }
                }.getOrNull()
                if (todayGroup != null && (todayGroup.mediaItems?.size ?: 0) >= 6) {
                    todayGroup
                } else {
                    runCatching {
                        content.getSearch(q, weekTop)?.firstOrNull { !it.mediaItems.isNullOrEmpty() }
                    }.getOrNull() ?: todayGroup
                }
            }
        }
        return JSONObject()
            .put("rows", groupsToJson(groups.orEmpty()))
            .toString()
    }

    override fun shutdown() {
        initialized = false
        appContext = null
        PluginAssets.pluginDir = null
    }

    private fun requireContext(): Context {
        return appContext ?: throw IllegalStateException("MoaiTubePlugin no inicializado (llamar init primero)")
    }

    private fun groupsToJson(groups: List<MediaGroup>): JSONArray {
        val rows = JSONArray()
        for (group in groups) {
            val items = JSONArray()
            for (item in group.mediaItems.orEmpty()) {
                if (!item.videoId.isNullOrEmpty()) {
                    items.put(itemToJson(item))
                }
            }
            if (items.length() > 0) {
                rows.put(
                    JSONObject()
                        .put("title", group.title ?: "")
                        .put("items", items),
                )
            }
        }
        return rows
    }

    private fun itemToJson(item: MediaItem): JSONObject {
        val pubMs = item.publishedDate
        val pubIso = if (pubMs > 0L) formatIsoUtc(pubMs) else ""
        return JSONObject()
            .put("videoId", item.videoId ?: "")
            .put("title", item.title ?: "")
            .put("author", item.author ?: "")
            .put("channelId", item.channelId ?: "")
            .put("thumb", item.cardImageUrl ?: "")
            .put("durationMs", item.durationMs)
            .put("live", item.isLive)
            .put("shorts", item.isShorts)
            .put("badge", item.badgeText ?: "")
            .put("publishedDate", pubMs)
            .put("publishedIso", pubIso)
    }

    private fun formatIsoUtc(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    private fun buildSubtitlesJson(info: MediaItemFormatInfo): JSONArray {
        val arr = JSONArray()
        val subs = info.subtitles.orEmpty()
        if (subs.isEmpty()) return arr

        val seenKeys = HashSet<String>()
        var hasSpanish = false
        var translatableFallbackUrl: String? = null

        for (sub in subs) {
            val rawUrl = sub.baseUrl?.trim().orEmpty()
            if (rawUrl.isEmpty()) continue
            val vttUrl = ensureVttFormat(rawUrl)
            val lang = sub.languageCode?.trim().orEmpty()
            val name = sub.name?.trim().takeIf { !it.isNullOrEmpty() } ?: lang.ifEmpty { "Subtítulos" }
            val key = "${lang.lowercase(Locale.US)}|${name.lowercase(Locale.US)}"
            if (!seenKeys.add(key)) continue

            if (lang.lowercase(Locale.US).startsWith("es")) {
                hasSpanish = true
            }
            if (translatableFallbackUrl == null && sub.isTranslatable) {
                translatableFallbackUrl = vttUrl
            }
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("language", lang)
                    .put("url", vttUrl)
                    .put("mimeType", "text/vtt"),
            )
        }

        // Si no hay pista en español pero alguna es traducible, ofrecer Español auto-traducido.
        if (!hasSpanish && translatableFallbackUrl != null) {
            val sep = if (translatableFallbackUrl.contains("?")) "&" else "?"
            arr.put(
                JSONObject()
                    .put("name", "Español (traducido)")
                    .put("language", "es")
                    .put("url", "${translatableFallbackUrl}${sep}tlang=es")
                    .put("mimeType", "text/vtt"),
            )
        }
        return arr
    }

    private fun ensureVttFormat(url: String): String {
        val cleaned = url.replace(Regex("([?&])fmt=[^&]*"), "$1fmt=vtt")
        if (cleaned.contains("fmt=vtt")) return cleaned
        val sep = if (cleaned.contains("?")) "&" else "?"
        return "${cleaned}${sep}fmt=vtt"
    }

    private fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()

    companion object {
        private const val TAG = "MoaiTubePlugin"
        const val PLUGIN_VERSION = "1.0.8"

        // Clientes de MediaServiceCore probados directo contra VideoInfoService
        // (sin pasar por la caché de FormatInfoWrapper) para obtener DASH/HLS HD.
        private val HD_CLIENTS = listOf(
            "ANDROID_VR",
            "VISIONOS",
            "IOS",
            "TV_SAMSUNG",
            "TV_DOWNGRADED",
            "WEB_EMBED",
            "TV",
        )

        private val DEFAULT_FALLBACK_QUERIES = listOf(
            "música argentina",
            "noticias hoy",
            "recetas fáciles",
            "fútbol resumen",
        )
    }
}
