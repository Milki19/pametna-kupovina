import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use {
        localProperties.load(it)
    }
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun configuredValue(name: String, defaultValue: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name, defaultValue)

val backendBaseUrl = configuredValue(
    "BACKEND_BASE_URL",
    "http://10.0.2.2:8080/"
)
    .let { if (it.endsWith('/')) it else "$it/" }

// Web OAuth klijent iz Google Cloud-a. Nije tajna: po njemu Google zna kom
// serveru izdaje token, a server prima samo tokene izdate baš njemu.
val googleClientId = configuredValue(
    "GOOGLE_CLIENT_ID",
    "1064409596151-2urs62fuq3udekpl4b18omirhhe5j08t.apps.googleusercontent.com"
)

// Release signing key, kept outside Git (see infra/ORACLE.md). Without the file
// the release APK is built unsigned.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use {
        keystoreProperties.load(it)
    }
}

fun keystoreValue(name: String): String =
    keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: error("keystore.properties has no $name")

android {
    namespace = "rs.pametnakupovina.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "rs.pametnakupovina.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"

        testInstrumentationRunner =
            "rs.pametnakupovina.app.testing.HiltTestRunner"

        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            backendBaseUrl.asBuildConfigString()
        )
        buildConfigField(
            "String",
            "GOOGLE_CLIENT_ID",
            googleClientId.asBuildConfigString()
        )
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreValue("storeFile"))
                storePassword = keystoreValue("storePassword")
                keyAlias = keystoreValue("keyAlias")
                keyPassword = keystoreValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("androidTest").assets.directories.add(
        "$projectDir/schemas"
    )
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// A release build does not allow plain HTTP, so an http:// server address would
// only fail on the phone. Stop the build instead.
val checkReleaseBackendUrl by tasks.registering {
    val releaseBackendBaseUrl = backendBaseUrl
    doLast {
        check(releaseBackendBaseUrl.startsWith("https://")) {
            "Release build needs an https:// BACKEND_BASE_URL, " +
                "e.g. -PBACKEND_BASE_URL=https://ime.duckdns.org/ " +
                "(now: $releaseBackendBaseUrl)"
        }
    }
}

tasks.named { it == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseBackendUrl)
}

dependencies {
    constraints {
        // 16 KB pages (Google Play): graphics-path 1.0.1, which Compose pulls
        // in, ships a native library with its RELRO segment aligned to 4 KB.
        implementation(libs.androidx.graphics.path)
    }
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    // Hilt's generated component annotates a method @CanIgnoreReturnValue;
    // nothing in its own POM declares this, some other dependency always
    // carried it in before.
    compileOnly(libs.errorprone.annotations)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.google.play.services.location)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    implementation(libs.google.play.services.code.scanner)
    implementation(libs.zxing.core)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.dagger.hilt.android.testing)
    kspAndroidTest(libs.dagger.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
