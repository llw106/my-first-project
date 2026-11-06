import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.hf.dyhide"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.hf.dyhide"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 自签名（debug keystore）即可：Xposed 模块不要求特定签名。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
            excludes += "META-INF/*.version"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // dexkit aar 的 Kotlin 元数据是 2.4，AGP 内置编译器是 2.2，跳过版本校验。
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation(files("libs/dexkit-2.2.0-76551eb-idle.aar"))
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")
}