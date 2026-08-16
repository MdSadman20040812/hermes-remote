pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // jitpack: com.github.barteksc:android-pdf-viewer
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "HermesMobile"

include(":app")
