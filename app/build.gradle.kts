plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()

// Crash-reporting endpoint. Deliberately not in the repository: a build made from
// published source must not report into the maintainer's instance, so an absent DSN
// is the normal case and compiles crash reporting out. Set `sentry.dsn` in
// local.properties, or pass -Psentry.dsn=..., to point a build at your own.
val sentryDsn: String = run {
    val fromLocalProperties = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { Properties().apply { load(it.inputStream()) }.getProperty("sentry.dsn") }
    (findProperty("sentry.dsn") as String? ?: fromLocalProperties ?: "").trim()
}

android {
    namespace = "info.piepgras.gridracer"
    compileSdk = 37

    defaultConfig {
        applicationId = "info.piepgras.gridracer"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.3"

        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            val keystoreProperties = Properties().apply {
                load(keystorePropertiesFile.inputStream())
            }
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile")!!)
                storePassword = keystoreProperties.getProperty("storePassword")!!
                keyAlias = keystoreProperties.getProperty("keyAlias")!!
                keyPassword = keystoreProperties.getProperty("keyPassword")!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // NewApi as fatal, so `bundleRelease` fails on it: `lintVitalRelease` runs inside
        // the bundle task and checks fatal-severity issues only, while plain `lintRelease`
        // (where NewApi is merely an error) is not part of any release build and nothing
        // runs it. That gap is how a `List#removeFirst()` needing API 35 sat in the report
        // from 6c269fd until Google Play's pre-review scan rejected the upload for it.
        fatal += "NewApi"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// AppPermissionsTest reads the source and merged manifests. The paths arrive as system
// properties because Gradle and the IDE run tests from different directories, and the
// source manifest is a declared input: without that, adding a permission leaves the test
// task UP-TO-DATE and the test that exists for exactly that change never runs.
tasks.withType<Test>().configureEach {
    systemProperty("app.sourceManifest", file("src/main/AndroidManifest.xml").absolutePath)
    systemProperty("app.buildDir", layout.buildDirectory.get().asFile.absolutePath)
    inputs.file("src/main/AndroidManifest.xml")
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    // LifecycleResumeEffect, so the permissions screen re-reads the device's answer when
    // the user comes back from Android settings rather than showing the stale one.
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.sentry.android.core)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.junit)
}
