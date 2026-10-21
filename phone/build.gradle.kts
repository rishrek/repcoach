import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The lambda url and key come from local.properties so they never end up in git.
// See backend README for the two lines to add. Without them we use our own summary text.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.repcoach.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.repcoach.phone"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "LAMBDA_URL", quoted(localProps.getProperty("repcoach.lambdaUrl", "")))
        buildConfigField("String", "LAMBDA_KEY", quoted(localProps.getProperty("repcoach.lambdaKey", "")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // mediapipe needs the model uncompressed
        noCompress += "task"
    }
}

// The pose models get downloaded from Google on the first build so we don't keep them in git.
val poseModels = mapOf(
    "pose_landmarker_full.task" to
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task",
    "pose_landmarker_lite.task" to
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
)

val downloadPoseModels = tasks.register("downloadPoseModels") {
    val assets = layout.projectDirectory.dir("src/main/assets").asFile
    outputs.files(poseModels.keys.map { File(assets, it) })
    doLast {
        assets.mkdirs()
        poseModels.forEach { (name, url) ->
            val target = File(assets, name)
            if (!target.exists()) {
                logger.lifecycle("Downloading $name")
                val partial = File(assets, "$name.part")
                URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                partial.renameTo(target)
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadPoseModels) }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(project(":pose-bridge"))
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mediapipe.tasks.vision)
}
