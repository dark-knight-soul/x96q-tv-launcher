plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.darkknightsoul.vitrina"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.darkknightsoul.vitrina"
        // На приставке X96Q Android 10 (API 29)
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Сжимаем код и ресурсы: на приставке 2 ГБ памяти, чем меньше — тем лучше
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Подписываем отладочным ключом — приложение только для своей приставки
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
