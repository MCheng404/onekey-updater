import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties


// Miuix (HyperOS 风格 Compose UI 库) 版本。要求 Kotlin >= 2.4.20 + Compose 1.12.x
val miuixVersion = "0.9.4"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.onekey.updater"
    compileSdk = 37

    val buildNumber = System.getenv("BUILD_NUMBER").orEmpty()
    defaultConfig {
        applicationId = "com.onekey.updater" + System.getenv("BUILD_TAG").orEmpty()
        minSdk = 26
        targetSdk = 37
        versionCode = if (buildNumber.isEmpty()) 55 else buildNumber.toInt()
        versionName = if (buildNumber.isEmpty()) "3.1.2" else "0.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            try {
                val props = Properties()
                props.load(FileInputStream(file("../local.properties")))
                storeFile = file(props.getProperty("keystore.file"))
                storePassword = props.getProperty("keystore.password")
                keyAlias = props.getProperty("keystore.keyalias")
                keyPassword = props.getProperty("keystore.keypassword")
            } catch (_: Exception) {
                val config = signingConfigs.getByName("debug")
                storeFile = config.storeFile
                storePassword = config.storePassword
                keyAlias = config.keyAlias
                keyPassword = config.keyPassword
                println("Signing config not found. Using debug settings.")
            }
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        warning.addAll(arrayOf("ExtraTranslation", "MissingTranslation", "MissingQuantity"))
    }
}

dependencies {

    // Compose BOM 2026.09.00 -> androidx.compose 1.12.1，与 Miuix 依赖的 CMP 1.12.0 对齐
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")

    // Miuix —— HyperOS 风格 Compose Multiplatform UI 库
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-icons:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-nav:$miuixVersion")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.navigation:navigation-runtime-ktx:2.9.7")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha12")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs-gson:0.4.3")
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs:0.4.3")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.auroraoss:gplayapi:3.5.8")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")
    implementation("io.github.g00fy2:versioncompare:1.5.0")
    implementation("io.insert-koin:koin-android:4.1.1")
    implementation("io.insert-koin:koin-androidx-compose:4.1.1")
    implementation("org.jsoup:jsoup:1.22.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")

}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { _ ->
            tasks.configureEach {
                if (name == "package${variant.name.replaceFirstChar { it.uppercase() }}") {
                    doLast {
                        outputs.files.files
                            .flatMap { it.walkTopDown().filter { f -> f.extension == "apk" }.toList() }
                            .forEach { apk -> runCatching { apk.copyTo(File(apk.parentFile, "${android.defaultConfig.applicationId}-${variant.buildType}.apk"), true) }.getOrNull() }
                    }
                }
            }
        }
    }
}
