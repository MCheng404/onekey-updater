// AGP 9.x 自带内建 Kotlin 支持（AGP 9.1.0 内建 KGP 2.2.10）。
// Miuix / Compose Multiplatform 1.12 要求 Kotlin >= 2.4.20，因此必须在此向上覆盖 KGP 版本。
// 注意：只能向上覆盖；若需低于 AGP 内建版本，须先在 gradle.properties 加 android.builtInKotlin=false。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    // Compose 编译器插件版本必须与 Kotlin 版本严格一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
