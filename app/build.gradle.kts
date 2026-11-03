import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.conectoma.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.conectoma.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "3.7"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // the keystore is not committed; create one with tools/make_keystore.ps1
        val keystore = file("../keystore/conectoma.jks")
        if (keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("CONECTOMA_STORE_PASSWORD") ?: "conectoma"
                keyAlias = System.getenv("CONECTOMA_KEY_ALIAS") ?: "conectoma"
                keyPassword = System.getenv("CONECTOMA_KEY_PASSWORD") ?: "conectoma"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("bin")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "3g"
            it.environment("CONECTOMA_PDF_TEST", System.getenv("CONECTOMA_PDF_TEST") ?: "")
            it.testLogging { events("passed", "failed", "skipped") }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui:1.9.2")
    implementation("androidx.compose.ui:ui-graphics:1.9.2")
    implementation("androidx.compose.ui:ui-text:1.9.2")
    implementation("androidx.compose.foundation:foundation:1.9.2")
    implementation("androidx.compose.foundation:foundation-layout:1.9.2")
    implementation("androidx.compose.animation:animation:1.9.2")
    implementation("androidx.compose.runtime:runtime:1.9.2")
    implementation("androidx.compose.material:material:1.9.2")
    implementation("androidx.compose.material:material-ripple:1.9.2")
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
