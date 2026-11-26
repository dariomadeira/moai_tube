plugins {
    id("org.jetbrains.kotlin.jvm")
}

val androidSdkDir: String by project
val androidCompileSdk: String by project
val androidJar = file("$androidSdkDir/platforms/android-$androidCompileSdk/android.jar")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    compileOnly(files(androidJar))
}
