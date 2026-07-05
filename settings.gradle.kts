pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android is JitPack-only. exclusiveContent means JitPack
        // can never resolve anything outside com.github.mik3y (supply-chain
        // guard rail — see docs/USB_SERIAL_LIB_UPGRADE.md).
        exclusiveContent {
            forRepository {
                maven { url = uri("https://jitpack.io") }
            }
            filter {
                includeGroup("com.github.mik3y")
            }
        }
    }
}

rootProject.name = "ft8vc"

include(":app")
include(":core")
include(":audio")
include(":rig")
include(":data")
include(":ft8-native")
