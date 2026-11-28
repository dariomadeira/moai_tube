pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "moai_tube"
include(":contract", ":plugin")

// Resolver el Android SDK sin versionar rutas de una máquina: local.properties
// (androidSdkDir o sdk.dir) o la variable de entorno ANDROID_HOME/ANDROID_SDK_ROOT.
run {
    val props = java.util.Properties()
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    val sdkDir = (
        props.getProperty("androidSdkDir")
            ?: props.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
        )?.replace("\\", "/")
    if (sdkDir != null) {
        gradle.beforeProject {
            if (!hasProperty("androidSdkDir")) {
                extensions.extraProperties["androidSdkDir"] = sdkDir
            }
        }
    }
}
