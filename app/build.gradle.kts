import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing material (D14): environment first, then ~/.gradle/gradle.properties.
// The build never creates or stores a key; when anything is missing the release build falls back
// to the debug keystore (with a warning) so `assembleRelease` never breaks.
fun signingSecret(name: String): String? =
    System.getenv(name) ?: providers.gradleProperty(name).orNull

val releaseKeystore = signingSecret("TCGSCAN_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
val releaseStorePassword = signingSecret("TCGSCAN_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingSecret("TCGSCAN_KEY_ALIAS")
val releaseKeyPassword = signingSecret("TCGSCAN_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystore != null &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "org.tcgscan.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.tcgscan.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // No applicationIdSuffix: the manual-debug procedure pushes packs into
            // /data/data/org.tcgscan.app/files, which must hold for the debug APK too.
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    // NFR-8: one APK per supported ABI, no universal APK (ORT + OpenCV natives are ~57 MB each).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // D1: only JDK 21 is installed, so no `jvmToolchain {}`; bytecode target stays 11.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

ksp {
    // Room schema history (NFR-9); the database itself arrives with T4.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    implementation(libs.onnxruntime.android)
    implementation(libs.opencv)
}

if (!hasReleaseSigning) {
    logger.warn(
        "TCG Scan: no release keystore (TCGSCAN_KEYSTORE_FILE/_PASSWORD, TCGSCAN_KEY_ALIAS/_PASSWORD); " +
            "release builds will be signed with the debug key and must not be published.",
    )
}
