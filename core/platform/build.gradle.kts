plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aishell.platform"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.all {
            it.jvmArgs("-Dmockk.agent.enabled=false", "-noverify", "-Dorg.conscrypt.provider.disable=true", "-Dorg.conscrypt.native.enabled=false")
        }
    }
 }
 
 dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:terminal"))
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.xz.java)
    implementation(libs.hilt.android)
    implementation(libs.androidx.core)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // testImplementation(libs.mockk) // removed to avoid MockK agent issues
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    // testImplementation("org.conscrypt:conscrypt-android:2.5.2") // removed to avoid native library loading
}