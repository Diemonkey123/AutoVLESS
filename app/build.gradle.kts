plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autovless.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autovless.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 15
        versionName = "1.4.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
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

dependencies {
    val libboxAar = file("libs/libbox.aar")
    if (!libboxAar.exists()) {
        throw GradleException("app/libs/libbox.aar is missing. GitHub Actions must build/copy libbox.aar before assembleDebug.")
    }
    implementation(files(libboxAar))
}
