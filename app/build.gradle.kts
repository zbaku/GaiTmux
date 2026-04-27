plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aishell"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aishell"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:ai"))
    implementation(project(":core:terminal"))
    implementation(project(":core:executor"))
    implementation(project(":core:vfs"))
    implementation(project(":core:platform"))
    implementation(project(":core:security"))
    implementation(project(":core:automation"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:files"))
    implementation(project(":feature:devices"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    coreLibraryDesugaring(libs.desugar.jdk)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
}
