import java.util.Properties

val buildTime = providers.gradleProperty("buildTime")
    .orElse(providers.environmentVariable("BUILD_TIME"))
    .getOrElse("dev")
val appVersionName = providers.gradleProperty("appVersionName").get()
val appVersionCode = providers.gradleProperty("appVersionCode").map(String::toInt).get()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.z1812.hyperdock"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    signingConfigs {
        create("release") {
            // 优先从环境变量读取（GitHub Actions 使用）
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keyAlias = System.getenv("KEY_ALIAS") ?: ""
            val keyPassword = System.getenv("KEY_PASSWORD") ?: ""

            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                // 回退到 keystore.properties 文件
                val propsFile = rootProject.file("keystore.properties")
                val props = Properties()
                if (propsFile.exists()) props.load(propsFile.inputStream())
                storeFile = props.getProperty("storeFile")?.let { file(it) }
                storePassword = props.getProperty("storePassword") ?: ""
                this.keyAlias = props.getProperty("keyAlias") ?: ""
                this.keyPassword = props.getProperty("keyPassword") ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.z1812.hyperdock"
        minSdk = 35
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("releaseFast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation.android)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}
