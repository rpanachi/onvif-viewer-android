import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rpanachi.onvifviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rpanachi.onvifviewer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Optional: a cameras.yaml at the repository root (git-ignored, see cameras.example.yaml) is copied
    // into the APK assets and seeds the camera list on first start. Without it the app starts empty.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/cameras"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val syncCameras by tasks.registering(Copy::class) {
    from(rootProject.file("cameras.yaml"))
    into(layout.buildDirectory.dir("generated/cameras"))
}
tasks.named("preBuild") { dependsOn(syncCameras) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
