plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // A real WebSocket client, for the end-to-end test that boots this server in-process and
    // drives two players through it. Everything else exercises MessageRouter/Room directly
    // with a fake Connection; this is the one test that goes over the actual wire.
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)

    runtimeOnly(libs.logback.classic)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

application {
    mainClass.set("info.piepgras.gridracer.server.ApplicationKt")
}
