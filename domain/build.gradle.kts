plugins {
    alias(libs.plugins.android.library)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nanihadesuka.irisx.domain"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    api(project(":camera:api"))
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
}
