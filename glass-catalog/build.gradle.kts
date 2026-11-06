plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.cxo.filmpack.glasscatalog"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Same published Compose runtime/API versions as the local 2.0.1 catalog.
    api("org.jetbrains.compose.foundation:foundation:1.12.0")
    api("org.jetbrains.compose.ui:ui:1.12.0")
    api("org.jetbrains.compose.ui:ui-graphics:1.12.0")
    api("org.jetbrains.compose.material:material-ripple:1.12.0")
    api("androidx.activity:activity-compose:1.13.0")
    api("io.github.kyant0:shapes:1.2.1")
    implementation("org.jetbrains:annotations:26.1.0")
}
