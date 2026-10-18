plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

// Signing material comes from keystore.properties (local, gitignored) or
// environment variables (CI). The keystore itself is NOT stored in the repo.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signProp(name: String): String? =
    keystoreProps.getProperty(name) ?: System.getenv(name.replaceFirstChar { it.uppercase() })
        ?.takeIf { it.isNotBlank() }

android {
    namespace = "com.zenes.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zenes.vpn"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "1.7.3"
    }

    signingConfigs {
        val storePath = signProp("storeFile")
        if (storePath != null) {
            create("release") {
                // Path is resolved relative to the app module (keystore/zenes.jks).
                storeFile = file(storePath)
                storePassword = signProp("storePassword")
                keyAlias = signProp("keyAlias")
                keyPassword = signProp("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signProp("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The bundled lint in AGP 8.7.3 crashes on newer AndroidX libs; skip it.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(files("libs/libbox.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2025.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // Material 3 Expressive: MaterialExpressiveTheme + MotionScheme.expressive() are
    // public ONLY in the 1.4.0-alpha13 line (stable 1.4.0 made them internal).
    // strictly() on BOTH artifacts — otherwise the BOM overrides the alpha with the release.
    implementation("androidx.compose.material3:material3") {
        version { strictly("1.4.0-alpha13") }
    }
    implementation("androidx.compose.material3:material3-android") {
        version { strictly("1.4.0-alpha13") }
    }
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.3")
}
