import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvm()
    android {
        namespace = "info.piepgras.gridracer.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
