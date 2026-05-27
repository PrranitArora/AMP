import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ─── Signing ──────────────────────────────────────────────────────────────────
// Copy keystore.properties.template → keystore.properties and fill in real
// values before running assembleRelease.  keystore.properties is git-ignored.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().also { props ->
    if (keystorePropsFile.exists()) props.load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.example.automotivemediaserviceprranit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.automotivemediaserviceprranit"
        minSdk        = 28
        targetSdk     = 36
        versionCode   = 1        // increment before each Play Store upload
        versionName   = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Signing ──────────────────────────────────────────────────────────────
    signingConfigs {
        create("release") {
            // All four values come from keystore.properties (never committed).
            // The build still assembles without them; only signing will fail,
            // keeping CI green while the keystore is provisioned separately.
            storeFile     = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias      = keystoreProps.getProperty("keyAlias")
            keyPassword   = keystoreProps.getProperty("keyPassword")
        }
    }

    // ── Build types ──────────────────────────────────────────────────────────
    buildTypes {

        release {
            // R8 full-mode: dead-code elimination + obfuscation + optimisation
            isMinifyEnabled   = true
            // Remove unused resources (layout strings, drawables, etc.)
            isShrinkResources = true
            isDebuggable      = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            // Parallel install alongside the release APK on a device
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            isDebuggable        = true
            // Minification off — faster builds; debugger stays attached
            isMinifyEnabled     = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // BuildConfig.DEBUG guard is available for logging / feature flags
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.media)          // MediaBrowserCompat, MediaSessionCompat
    implementation(project(":shared"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
