plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.drivedeck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.drivedeck"
        minSdk = 26
        targetSdk = 36
        // CI passes -PdeckVersionCode / -PdeckVersionName so every release is a newer version.
        versionCode = (findProperty("deckVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("deckVersionName") as String?) ?: "1.0.0"
    }

    // Every build must be signed with the SAME key, or Android refuses to update the installed app.
    // Keep keystore/drivedeck-signing.keystore safe (it is git-ignored, never commit it).
    val deckKeystore = rootProject.file("keystore/drivedeck-signing.keystore")
    if (deckKeystore.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = deckKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = true
        // Versions are pinned deliberately (AGP 8.x line); upgrades are done on purpose, not by lint.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(libs.androidx.media)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.car.app.testing)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Screenshot tests (Robolectric + Roborazzi) write PNGs to docs/screenshots when run with
// ./gradlew testDebugUnitTest -Proborazzi.record
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", project.hasProperty("roborazzi.record").toString())
    systemProperty("roborazzi.output.dir", rootProject.file("docs/screenshots").absolutePath)
}
