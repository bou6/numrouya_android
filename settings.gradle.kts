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
        maven {
            name = "Eclipse Paho"
            url = uri("https://repo.eclipse.org/content/repositories/paho-releases/")
        }
    }
}

rootProject.name = "numrouya_cli"
include(":app")
