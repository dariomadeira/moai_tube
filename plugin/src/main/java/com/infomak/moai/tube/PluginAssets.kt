package com.infomak.moai.tube

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Redirige las lecturas de assets de MediaServiceCore (`nsigsolver/` y
 * `potokennp2/`) hacia la carpeta donde Moai desempaquetó el plugin
 * (`pluginDir/assets/`), con respaldo al `context.assets` del APK.
 */
object PluginAssets {
    @Volatile
    var pluginDir: File? = null

    @JvmStatic
    fun open(context: Context, filename: String): InputStream =
        openAsset(context.assets, filename)

    @JvmStatic
    fun openAsset(assets: AssetManager, filename: String): InputStream {
        val dir = pluginDir
        if (dir != null) {
            val localFile = File(File(dir, "assets"), filename)
            if (localFile.isFile) {
                return FileInputStream(localFile)
            }
        }
        return assets.open(filename)
    }
}
