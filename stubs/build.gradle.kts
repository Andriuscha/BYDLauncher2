plugins {
    id("com.android.library")
}

android {
    namespace = "com.ar.bydlauncher.stubs"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}