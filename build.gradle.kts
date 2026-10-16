plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
}
group = "dev.wallpapermanager"
version = "0.1.0"
kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
tasks.test { useJUnitPlatform() }
compose.desktop {
    application {
        mainClass = "wallpaper.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "WallpaperManager"
            packageVersion = "0.1.0"
            description = "Wallpaper library, playlists and daily schedules"
            vendor = "Wallpaper Manager"
            modules("java.sql", "java.desktop", "java.prefs")
        }
    }
}
