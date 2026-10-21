plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// fire tv has no google play services so don't add play services or firebase here
android {
    namespace = "com.repcoach.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.repcoach.tv"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(project(":pose-bridge"))
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit4)
}
