plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePath = System.getenv("CM_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("CM_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("CM_KEY_ALIAS")
val releaseKeyPassword = System.getenv("CM_KEY_PASSWORD")
val hasReleaseKey = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.meuplayer.app"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.meuplayer.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("doramaxRelease") {
            if (hasReleaseKey) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("doramaxRelease")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("doramaxRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.configureEach {
    if (name == "validateSigningRelease") {
        doFirst {
            check(hasReleaseKey) {
                "DORAMAX signing key missing. Configure the Codemagic Android keystore (CM_KEYSTORE_PATH, CM_KEYSTORE_PASSWORD, CM_KEY_ALIAS, CM_KEY_PASSWORD) before building a release."
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.6.0")
}
