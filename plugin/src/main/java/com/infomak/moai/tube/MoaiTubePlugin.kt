package com.infomak.moai.tube

import android.content.Context
import android.util.Log
import com.infomak.moai.tube.contract.IYouTubePlugin
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
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

    override fun pluginVersion(): String = PLUGIN_VERSION

    override fun init(context: Context, pluginDir: File) {
        val app = context.applicationContext ?: context
        appContext = app
        PluginAssets.pluginDir = pluginDir
        GlobalPreferences.instance(app)

        // Activar HLS extendido (VISIONOS) y arrancar en TV_SAMSUNG (índice 1),
        // que entrega DASH adaptativo en HD en vez de SABR-only.
        val data = MediaServiceData.instance()
        data.setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, true)
        if (data.clientType < 0) {
            data.clientType = CLIENT_TV_SAMSUNG
        }
        initialized = true
        Log.i(TAG, "MoaiTubePlugin v$PLUGIN_VERSION listo (pluginDir=${pluginDir.absolutePath})")
    }

    override fun resolve(videoId: String): String {
        val ctx = requireContext()
        val mediaItemService = YouTubeServiceManager.instance().mediaItemService
        val data = MediaServiceData.instance()

        var bestInfo: MediaItemFormatInfo? = null
        val tried = mutableSetOf<Int>()

        // 1) Probar primero el cliente actual; si sólo trae SABR/360p, rotar por
        // los clientes conocidos que entregan DASH o HLS en HD para Media3.
        val order = buildList {
            val current = data.clientType
            if (current >= 0) add(current)
            for (idx in HD_CLIENT_PRIORITY) {
                if (idx !in this) add(idx)
            }
        }

        for (clientIdx in order) {
            if (!tried.add(clientIdx)) continue
            try {
                if (data.clientType != clientIdx) {
                    data.clientType = clientIdx
                    // setFormatEnabled también invalida la caché interna de FormatInfoWrapper.
                    data.setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, true)
                }
                val info = mediaItemService.getFormatInfo(videoId) ?: continue
                if (info.isUnplayable) {
                    Log.w(TAG, "resolve($videoId) client#$clientIdx unplayable: ${info.playabilityReason}")
                    if (bestInfo == null) bestInfo = info
                    continue
                }
                val hasHd = info.containsDashFormats() || info.containsHlsUrl()
                Log.i(
                    TAG,
                    "resolve($videoId) client#$clientIdx (${info.clientInfo?.clientName}): " +
                        "dash=${info.containsDashFormats()} hls=${info.containsHlsUrl()} " +
                        "sabr=${info.containsSabrFormats()} url=${info.containsUrlFormats()}",
                )
                bestInfo = info
                if (hasHd) {
                    break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "resolve($videoId) client#$clientIdx falló: ${t.message}")
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

        if (info.isUnplayable) {
            return out.put("error", info.playabilityReason ?: "no se puede reproducir").toString()
        }

        if (info.isLive && info.containsHlsUrl()) {
            return out
                .put("url", info.hlsManifestUrl)
                .put("kind", "hls")
                .put("quality", "live")
                .toString()
        }

        if (info.containsDashFormats()) {
            val file = File(ctx.cacheDir, "moai_tube_$videoId.mpd")
            info.createMpdStream()?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            if (file.isFile && file.length() > 0) {
                val maxQuality = info.adaptiveFormats
                    ?.mapNotNull { it.qualityLabel }
                    ?.firstOrNull { it.isNotBlank() }
                    ?: "HD"
                return out
                    .put("url", "file://${file.absolutePath}")
                    .put("kind", "dash")
                    .put("quality", maxQuality)
                    .toString()
            }
        }

        if (info.containsHlsUrl()) {
            return out
                .put("url", info.hlsManifestUrl)
                .put("kind", "hls")
                .put("quality", "HD")
                .toString()
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
            groups = queries.mapNotNull { q ->
                runCatching {
                    content.getSearch(q)?.firstOrNull { !it.mediaItems.isNullOrEmpty() }
                }.getOrNull()
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

    private fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()

    companion object {
        private const val TAG = "MoaiTubePlugin"
        const val PLUGIN_VERSION = "1.0.0"

        // Índices de FormatInfoWrapper.CLIENT_LIST en MediaServiceCore:
        // 0=TV (SABR), 1=TV_SAMSUNG (DASH), 3=VISIONOS (HLS), 4=TV_DOWNGRADED, 7=IOS, 10=ANDROID_VR
        private const val CLIENT_TV_SAMSUNG = 1
        private const val CLIENT_VISIONOS = 3
        private const val CLIENT_TV_DOWNGRADED = 4
        private const val CLIENT_IOS = 7
        private const val CLIENT_ANDROID_VR = 10

        private val HD_CLIENT_PRIORITY = listOf(
            CLIENT_TV_SAMSUNG,
            CLIENT_VISIONOS,
            CLIENT_TV_DOWNGRADED,
            CLIENT_IOS,
            CLIENT_ANDROID_VR,
        )

        private val DEFAULT_FALLBACK_QUERIES = listOf(
            "música argentina",
            "noticias hoy",
            "recetas fáciles",
            "fútbol resumen",
        )
    }
}
