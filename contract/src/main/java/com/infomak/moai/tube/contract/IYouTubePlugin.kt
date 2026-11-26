package com.infomak.moai.tube.contract

import android.content.Context
import java.io.File

/**
 * Contrato del plugin de YouTube para Moai TV (v1).
 *
 * Vive tanto en Moai3 (host) como en `moai_tube` (compileOnly).
 * Todos los payloads se intercambian como JSON String para evitar conflictos
 * de serialización/classloader entre el host y el `.dex` cargado dinámicamente.
 */
interface IYouTubePlugin {
    /** Versión del contrato que implementa este plugin. */
    fun contractVersion(): Int = CONTRACT_VERSION

    /** Versión semántica del plugin (ej. "1.0.0"). */
    fun pluginVersion(): String

    /**
     * Inicializa el plugin con el [Context] de la app y la carpeta local donde
     * Moai desempaquetó `assets/` y `libs/`.
     */
    fun init(context: Context, pluginDir: File)

    /**
     * Resuelve un [videoId] buscando la mejor calidad reproducible por Media3
     * (DASH `.mpd` o HLS `.m3u8` en HD/4K, con fallback a directo 360p).
     *
     * JSON devuelto:
     * `{ "url", "kind", "headers": { "User-Agent": ... }, "title", "author",
     *    "channelId", "quality", "client", "summary", "error" }`
     */
    fun resolve(videoId: String): String

    /**
     * Busca videos en YouTube sin cuenta de Google.
     *
     * JSON devuelto:
     * `{ "items": [ { "videoId", "title", "author", "channelId", "thumb",
     *    "durationMs", "live", "badge", "publishedDate" } ] }`
     */
    fun search(query: String): String

    /**
     * Obtiene videos sugeridos/relacionados por el algoritmo de YouTube a partir
     * de un [videoId] (para recomendaciones sin cuenta de Google).
     *
     * JSON devuelto: `{ "rows": [ { "title", "items": [...] } ] }`
     */
    fun suggestions(videoId: String): String

    /**
     * Consulta los últimos videos de una lista de canales (`channelIds`) vía RSS
     * oficial de YouTube, ordenados por fecha exacta de publicación descendente.
     * Ideal para "suscripciones locales" y cola cronológica sin cuenta de Google.
     *
     * JSON devuelto: `{ "items": [ { "videoId", "title", "author", "channelId",
     *    "thumb", "publishedDate", "publishedIso", "live", "badge" } ] }`
     */
    fun channelFeed(channelIds: List<String>, filterShorts: Boolean = true): String

    /**
     * Lista los videos de un canal específico sin cuenta de Google.
     *
     * JSON devuelto: `{ "title", "items": [...] }`
     */
    fun channelVideos(channelId: String): String

    /**
     * Devuelve filas para la pantalla principal (secciones públicas o búsquedas
     * de respaldo si YouTube exige sesión para Inicio/Tendencias).
     *
     * JSON devuelto: `{ "rows": [ { "title", "items": [...] } ] }`
     */
    fun home(fallbackQueries: List<String>? = null): String

    /** Libera recursos/hilos internos del plugin. */
    fun shutdown()

    companion object {
        const val CONTRACT_VERSION = 1
        const val ENTRY_CLASS = "com.infomak.moai.tube.MoaiTubePlugin"
    }
}
