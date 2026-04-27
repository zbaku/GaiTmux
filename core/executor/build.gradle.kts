plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aishell.executor"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:terminal"))
    implementation(project(":core:vfs"))
    implementation(project(":core:platform"))
    implementation(libs.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}