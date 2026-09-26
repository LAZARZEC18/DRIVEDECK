import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.drivedeck"
    compileSdk = 36

    defaultConfig {
        // Play package name (globally unique). The code namespace stays com.drivedeck.
        applicationId = "com.lazarzec.drivedeck"
        minSdk = 26
        targetSdk = 36
        // CI passes -PdeckVersionCode / -PdeckVersionName so every release is a newer version.
        versionCode = (findProperty("deckVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("deckVersionName") as String?) ?: "1.0.0"
    }

    // Debug ("DRIVEDECK Dev") builds: fixed project key so dev updates install over the top.
    // Keep keystore/ safe: it is git-ignored, never commit it.
    val deckKeystore = rootProject.file("keystore/drivedeck-signing.keystore")
    if (deckKeystore.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = deckKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Release (Google Play) builds: signed with the upload key from keystore/upload.properties
    // locally, or the UPLOAD_* environment variables in CI. Google Play re-signs for devices.
    val uploadProps = Properties().apply {
        val f = rootProject.file("keystore/upload.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun uploadValue(key: String, env: String): String? = uploadProps.getProperty(key) ?: System.getenv(env)
    val uploadStore = uploadValue("storeFile", "UPLOAD_STORE_FILE")?.let { rootProject.file("keystore/$it") }
    val hasUploadKey = uploadStore?.exists() == true
    if (hasUploadKey) {
        signingConfigs.create("upload") {
            storeFile = uploadStore
            storePassword = uploadValue("storePassword", "UPLOAD_STORE_PASSWORD")
            keyAlias = uploadValue("keyAlias", "UPLOAD_KEY_ALIAS")
            keyPassword = uploadValue("keyPassword", "UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Separate app ("DRIVEDECK Dev") so test builds never clash with the Play install.
            applicationIdSuffix = ".dev"
        }
        release {
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
            // R8 is off until crash reports confirm the minified build is safe on the phone.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // "Direct" = the fast release build, installed from GitHub Releases (Obtainium keeps it
        // updated). Used for background mode, which doesn't need the Play-only Android Auto
        // launcher. Own package, so it never clashes with a Play install.
        create("direct") {
            initWith(getByName("release"))
            applicationIdSuffix = ".direct"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
    implementation(libs.androidx.work.runtime.ktx)

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
