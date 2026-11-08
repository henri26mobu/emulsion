plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.rangermobu.emulsion.kotlin"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.rangermobu.emulsion.kotlin"
        minSdk = 29 // RELATIVE_PATH MediaStore (album Emulsion) sans permission de stockage ; même minSdk que l'app Capacitor
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// aucune dépendance : ni AndroidX, ni kotlin-stdlib explicite (ajoutée par le plugin Kotlin)
