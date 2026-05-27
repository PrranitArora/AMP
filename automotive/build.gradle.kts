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
        versionCode   = 1        // increment before each store upload
        versionName   = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Signing ──────────────────────────────────────────────────────────────
    signingConfigs {
        create("release") {
            storeFile     = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias      = keystoreProps.getProperty("keyAlias")
            keyPassword   = keystoreProps.getProperty("keyPassword")
        }
    }

    // ── Build types ──────────────────────────────────────────────────────────
    buildTypes {

        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            isDebuggable      = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            isDebuggable        = true
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.media)          // MediaBrowserCompat, MediaSessionCompat
    implementation(project(":shared"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
