plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Plain Kotlin on purpose: no Android, no native code, so the whole protocol runs in ordinary unit tests.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":camera:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
