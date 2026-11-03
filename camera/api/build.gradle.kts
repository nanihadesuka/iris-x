plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Definitions only: no implementation, and no Android, so both kinds of backend can depend on it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
