import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. The key is deliberately NOT in this repo, which is public:
// a committed key lets anyone build an APK that Android will accept as an
// update to an existing install. Supply it through signing/keystore.properties
// (untracked, see signing/README.md) or through DK_* environment variables.
// A missing key degrades to an unsigned release build rather than failing the
// whole build, so `assembleDebug` and the tests still work in a fresh clone.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("signing/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(environmentVariable: String, property: String): String? =
    System.getenv(environmentVariable) ?: keystoreProperties.getProperty(property)

val releaseKeystore = signingValue("DK_KEYSTORE", "storeFile")?.let { rootProject.file(it) }
val hasReleaseKey = releaseKeystore?.exists() == true

android {
    namespace = "com.distractionkiller.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.distractionkiller.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("DK_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("DK_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("DK_KEY_PASSWORD", "keyPassword")
                // v2 covers everything from Android 7 and is what minSdk 26
                // needs; v3 is what current APKs carry, so ship both. v1 (JAR
                // signing) is legacy and irrelevant above API 24.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            // Left unminified so a locally built release APK behaves exactly
            // like the debug one. Turn this on if you care about APK size.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
