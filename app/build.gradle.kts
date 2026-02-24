import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.numrouya_cli"
    compileSdk = 36

    defaultConfig {
        fun String.escapeForBuildConfig(): String =
            replace("\\", "\\\\").replace("\"", "\\\"")

        val localProperties = Properties().apply {
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { load(it) }
            }
        }

        fun readSecretProperty(name: String): String =
            (project.findProperty(name) as String?)
                ?: localProperties.getProperty(name)
                ?: ""

        val mqttBrokerHost = readSecretProperty("MQTT_BROKER_HOST")
        val mqttUsername = readSecretProperty("MQTT_USERNAME")
        val mqttPassword = readSecretProperty("MQTT_PASSWORD")

        applicationId = "com.example.numrouya_cli"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MQTT_BROKER_HOST", "\"${mqttBrokerHost.escapeForBuildConfig()}\"")
        buildConfigField("String", "MQTT_USERNAME", "\"${mqttUsername.escapeForBuildConfig()}\"")
        buildConfigField("String", "MQTT_PASSWORD", "\"${mqttPassword.escapeForBuildConfig()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.hivemq.mqtt)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}