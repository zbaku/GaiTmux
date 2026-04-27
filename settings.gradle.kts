pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

rootProject.name = "AIShell"

include(":app")
include(":core:domain", ":core:data", ":core:engine", ":core:ai")
include(":core:terminal", ":core:executor", ":core:vfs")
include(":core:platform", ":core:security", ":core:automation")
include(":feature:terminal", ":feature:sessions", ":feature:files")
include(":feature:devices", ":feature:settings")
