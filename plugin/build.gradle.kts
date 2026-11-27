import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val androidSdkDir: String by project
val androidCompileSdk: String by project
val androidBuildTools: String by project
val androidJar = file("$androidSdkDir/platforms/android-$androidCompileSdk/android.jar")
val d8Bin = file(
    "$androidSdkDir/build-tools/$androidBuildTools/" +
        if (System.getProperty("os.name").lowercase().contains("win")) "d8.bat" else "d8",
)

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

// Igual que SmartTube / SPEC-58: OkHttp fijo en 3.12.13 (evita crash API 36).
configurations.all {
    resolutionStrategy.force("com.squareup.okhttp3:okhttp:3.12.13")
}

// Extrae classes.jar de los 4 AAR locales de MediaServiceCore para compilar en JVM.
val extractedAarsDir = layout.buildDirectory.dir("extracted-aars").get().asFile
val localAarsDir = rootProject.file("libs")
val localAarJars = mutableListOf<File>()

if (localAarsDir.isDirectory) {
    extractedAarsDir.mkdirs()
    localAarsDir.listFiles { f -> f.extension == "aar" }?.sortedBy { it.name }?.forEach { aar ->
        val outJar = File(extractedAarsDir, "${aar.nameWithoutExtension}.jar")
        if (!outJar.exists() || outJar.lastModified() < aar.lastModified()) {
            ZipFile(aar).use { zip ->
                val entry = zip.getEntry("classes.jar")
                if (entry != null) {
                    zip.getInputStream(entry).use { input ->
                        outJar.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        if (outJar.exists()) {
            localAarJars.add(outJar)
        }
    }
}

// Configuración separada para dependencias AAR externas (j2v8, androidx) para extraer classes.jar y .so.
val aarDeps: Configuration by configurations.creating

// Dependencias runtime propias del plugin (excluyendo lo que ya provee el host Moai3).
val pluginRuntime: Configuration by configurations.creating {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    compileOnly(project(":contract"))
    compileOnly(files(androidJar))

    // Clases de los 4 AAR de MediaServiceCore (se empaquetan en plugin.dex)
    implementation(files(localAarJars))

    // Dependencias Java puras de MediaServiceCore (mismas versiones que SharedModules)
    implementation("com.squareup.retrofit2:retrofit:2.5.0")
    implementation("com.squareup.retrofit2:converter-gson:2.5.0")
    implementation("com.jayway.jsonpath:json-path:2.4.0")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("com.github.florianingerl.util:regex:1.1.11")
    implementation("com.grack:nanojson:1.7")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:3.12.13")
    implementation("com.squareup.okhttp3:logging-interceptor:3.12.13")
    implementation("com.squareup.okhttp3:okhttp-brotli:4.1.0")
    implementation("com.jakewharton:disklrucache:2.0.2")
    implementation("dnsjava:dnsjava:2.1.9")
    implementation("com.google.net.cronet:cronet-okhttp:0.1.0")
    implementation("info.guardianproject.netcipher:netcipher:2.1.0")

    // AARs externos (RxAndroid, j2v8, Cronet API, OkHttpProfiler y AndroidX)
    aarDeps("io.reactivex.rxjava2:rxandroid:2.1.1@aar")
    aarDeps("com.eclipsesource.j2v8:j2v8:6.2.1@aar")
    aarDeps("org.chromium.net:cronet-api:105.5195.68@aar")
    aarDeps("com.localebro:okhttpprofiler:1.0.8@aar")
    aarDeps("androidx.webkit:webkit:1.11.0@aar")
    aarDeps("androidx.preference:preference:1.1.0@aar")
    aarDeps("androidx.appcompat:appcompat:1.2.0@aar")

    // Autocontenido para release con R8: el host Moai3 puede recortar gson/kotlin-stdlib.
    implementation("com.google.code.gson:gson:2.10.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

// Exponemos classes.jar de j2v8 y rxandroid para compilar sin AGP.
val extractExternalAars by tasks.registering {
    val outDir = layout.buildDirectory.dir("external-aars").get().asFile
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        aarDeps.resolve().forEach { aar ->
            val outJar = File(outDir, "${aar.nameWithoutExtension}.jar")
            ZipFile(aar).use { zip ->
                zip.getEntry("classes.jar")?.let { entry ->
                    zip.getInputStream(entry).use { input ->
                        outJar.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }
}

dependencies {
    compileOnly(fileTree(layout.buildDirectory.dir("external-aars")) { include("*.jar") })
}

tasks.named("compileKotlin") {
    dependsOn(extractExternalAars)
}

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Empaqueta el plugin listo para distribuir en `moai_tube/dist/`:
 * - `plugin.dex` (con d8, reemplazando UtilsKt por la versión PluginAssets)
 * - `assets.zip` (nsigsolver + potokennp2 desde youtubeapi.aar)
 * - `libs/<abi>/libj2v8.so` (armeabi-v7a, arm64-v8a, x86_64)
 * - `manifest.json` con SHA-256 de cada artefacto
 */
tasks.register("packagePlugin") {
    dependsOn("jar", extractExternalAars)
    doLast {
        require(d8Bin.isFile) { "No se encontró d8 en: ${d8Bin.absolutePath}" }
        require(androidJar.isFile) { "No se encontró android.jar en: ${androidJar.absolutePath}" }

        val distDir = rootProject.file("dist")
        val workDir = layout.buildDirectory.dir("package-work").get().asFile
        distDir.deleteRecursively()
        workDir.deleteRecursively()
        distDir.mkdirs()
        workDir.mkdirs()

        // 1) Preparar youtubeapi.jar sin el UtilsKt original (usamos el nuestro con PluginAssets)
        val patchedYoutubeApi = File(workDir, "youtubeapi-patched.jar")
        val origYoutubeApi = File(extractedAarsDir, "youtubeapi.jar")
        ZipFile(origYoutubeApi).use { zin ->
            ZipOutputStream(patchedYoutubeApi.outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name == "com/liskovsoft/youtubeapi/app/nsigsolver/common/UtilsKt.class" ||
                        e.name == "com/liskovsoft/youtubeapi/app/nsigsolver/common/ScriptLoaderError.class"
                    ) {
                        continue
                    }
                    zout.putNextEntry(ZipEntry(e.name))
                    zin.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
        }

        // 2) Recolectar todos los JARs a dexificar (excluyendo lo que ya trae el host Moai3)
        val dexInputs = mutableListOf<File>()
        dexInputs.add(tasks.named<Jar>("jar").get().archiveFile.get().asFile)
        dexInputs.add(patchedYoutubeApi)

        localAarJars.filter { it.name != "youtubeapi.jar" }.forEach { dexInputs.add(it) }

        // Sólo excluimos `contract` (debe venir del ClassLoader del host para
        // compartir identidad de IYouTubePlugin) y anotaciones compile-only.
        // kotlin-stdlib y gson se empaquetan en plugin.dex para sobrevivir a R8
        // en builds release de Moai3.
        val hostProvidedPrefixes = listOf(
            "kotlinx-coroutines",
            "annotations-",
            "contract",
        )
        pluginRuntime.resolve()
            .filter { f -> f.extension == "jar" && !localAarJars.contains(f) }
            .filter { f -> hostProvidedPrefixes.none { f.name.startsWith(it) } }
            .forEach { dexInputs.add(it) }

        // Sumar classes.jar de AARs externos no provistos por el host Moai3
        val extDir = layout.buildDirectory.dir("external-aars").get().asFile
        val bundleExternalPrefixes = listOf(
            "j2v8",
            "rxandroid",
            "cronet-",
            "okhttpprofiler",
            "netcipher",
            "webkit",
        )
        extDir.listFiles { f ->
            bundleExternalPrefixes.any { f.name.startsWith(it) }
        }?.forEach { dexInputs.add(it) }

        // 3) Ejecutar R8 (tree-shaking sin ofuscar) -> dist/plugin.dex único
        // (con --min-api 26 igual que Moai3). Así incluimos kotlin-stdlib y gson
        // autocontenidos sin superar el límite de 65k métodos de un único DEX.
        val d8Jar = file("$androidSdkDir/build-tools/$androidBuildTools/lib/d8.jar")
        require(d8Jar.isFile) { "No se encontró d8.jar en: ${d8Jar.absolutePath}" }
        val javaBin = File(System.getProperty("java.home"), "bin/" +
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")

        val r8Rules = File(workDir, "r8-plugin.pro").apply {
            writeText(
                """
                -dontobfuscate
                -dontoptimize
                -dontwarn **
                -ignorewarnings
                -keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
                -keep class com.infomak.moai.tube.** { *; }
                -keep class com.liskovsoft.** { *; }
                -keep class com.eclipsesource.v8.** { *; }
                -keep class retrofit2.** { *; }
                -keep class com.google.gson.** { *; }
                -keep class com.jayway.jsonpath.** { *; }
                -keep class androidx.webkit.** { *; }
                -keep class org.chromium.support_lib_boundary.** { *; }
                -keepclasseswithmembers class * {
                    native <methods>;
                }
                """.trimIndent(),
            )
        }

        val d8OutDir = File(workDir, "dex-out").apply { mkdirs() }
        val cmd = mutableListOf(
            javaBin.absolutePath,
            "-Xmx2g",
            "-cp", d8Jar.absolutePath,
            "com.android.tools.r8.R8",
            "--release",
            "--min-api", "26",
            "--lib", androidJar.absolutePath,
            "--pg-conf", r8Rules.absolutePath,
            "--output", d8OutDir.absolutePath,
        )
        // Pasar contract.jar como --lib (lo provee el host Moai3).
        project(":contract").tasks.named<Jar>("jar").get().archiveFile.get().asFile
            .takeIf { it.isFile }
            ?.let { cmd.addAll(listOf("--lib", it.absolutePath)) }
        dexInputs.forEach { cmd.add(it.absolutePath) }

        logger.lifecycle("Ejecutando R8 con ${dexInputs.size} jars...")
        val r8Log = File(workDir, "r8.log")
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(r8Log)
            .start()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            logger.error(r8Log.readText())
        }
        require(exitCode == 0) { "R8 falló con exit code $exitCode" }

        val dexFiles = d8OutDir.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }.orEmpty()
        require(dexFiles.size == 1) {
            "Se esperaba 1 classes.dex pero d8 generó ${dexFiles.size}: ${dexFiles.map { it.name }}"
        }
        val pluginDex = File(distDir, "plugin.dex")
        dexFiles.first().copyTo(pluginDex, overwrite = true)

        // 4) Empaquetar assets.zip desde youtubeapi.aar!/assets/**
        val assetsZip = File(distDir, "assets.zip")
        val youtubeAar = File(localAarsDir, "youtubeapi.aar")
        var assetCount = 0
        ZipFile(youtubeAar).use { zin ->
            ZipOutputStream(assetsZip.outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.startsWith("assets/") && !e.isDirectory) {
                        val rel = e.name.removePrefix("assets/")
                        zout.putNextEntry(ZipEntry(rel))
                        zin.getInputStream(e).use { it.copyTo(zout) }
                        zout.closeEntry()
                        assetCount++
                    }
                }
            }
        }
        logger.lifecycle("Empaquetados $assetCount archivos en dist/assets.zip")

        // 5) Extraer libj2v8.so por ABI desde j2v8-6.2.1.aar (nombres planos para GitHub Releases)
        val j2v8Aar = aarDeps.resolve().first { it.name.startsWith("j2v8") }
        val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        val nativeHashes = linkedMapOf<String, Pair<String, String>>()
        ZipFile(j2v8Aar).use { zin ->
            for (abi in supportedAbis) {
                val entry = zin.getEntry("jni/$abi/libj2v8.so") ?: continue
                val relPath = "libj2v8-$abi.so"
                val outFile = File(distDir, relPath)
                zin.getInputStream(entry).use { input ->
                    outFile.outputStream().use { input.copyTo(it) }
                }
                nativeHashes[abi] = relPath to sha256(outFile)
            }
        }

        // 6) Generar dist/manifest.json con SHA-256
        val nativeJson = nativeHashes.entries.joinToString(",\n") { (abi, pair) ->
            """    "$abi": { "path": "${pair.first}", "sha256": "${pair.second}" }"""
        }
        val manifest = """
{
  "id": "moai_tube",
  "nombre": "Moai Tube (YouTube)",
  "version": "${project.version}",
  "contractVersion": 1,
  "entryClass": "com.infomak.moai.tube.MoaiTubePlugin",
  "dex": {
    "path": "plugin.dex",
    "sha256": "${sha256(pluginDex)}"
  },
  "assets": {
    "path": "assets.zip",
    "sha256": "${sha256(assetsZip)}"
  },
  "nativeLibs": {
$nativeJson
  }
}
        """.trimIndent() + "\n"
        File(distDir, "manifest.json").writeText(manifest, Charsets.UTF_8)

        logger.lifecycle("✅ Plugin generado en ${distDir.absolutePath}")
        logger.lifecycle("   - plugin.dex (${pluginDex.length() / 1024} KB)")
        logger.lifecycle("   - assets.zip (${assetsZip.length() / 1024} KB, $assetCount archivos)")
        logger.lifecycle("   - nativeLibs: ${nativeHashes.keys.joinToString(", ")}")
    }
}
