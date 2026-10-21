plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// targets java 17 so nobody has to install that exact jdk
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replays data/*.csv through the analyzers and prints count accuracy."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.repcoach.core.ReplayKt")
    args(rootProject.file("data").absolutePath)
}
