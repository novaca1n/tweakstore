import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release key lives outside the repo (../keys/keystore.properties or $RELEASE_KEYS); without it release builds
// are signed with the debug key, which is fine for local builds.
val keyProps = Properties().apply {
    val f = file(System.getenv("RELEASE_KEYS") ?: "${rootDir}/../keys/keystore.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }   // UTF-8: the path may be non-ASCII
}

android {
    namespace = "dev.novacain.tweakstore"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.novacain.tweakstore"
        minSdk = 31          // dynamic colors (Material You)
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")   // Material 3 Expressive
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
