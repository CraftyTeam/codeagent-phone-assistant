import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val modelUrl = providers.gradleProperty("CODEAGENT_MODEL_URL").get()
val modelFileName = providers.gradleProperty("CODEAGENT_MODEL_FILENAME").get()
val modelSha256 = providers.gradleProperty("CODEAGENT_MODEL_SHA256").get()
val modelSizeBytes = providers.gradleProperty("CODEAGENT_MODEL_SIZE_BYTES").get()

android {
    namespace = "ro.craftyteam.localassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.codeagent.phoneassistant"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"

        buildConfigField("String", "MODEL_URL", modelUrl.asBuildConfigString())
        buildConfigField("String", "MODEL_FILE_NAME", modelFileName.asBuildConfigString())
        buildConfigField("String", "MODEL_SHA256", modelSha256.asBuildConfigString())
        buildConfigField("long", "MODEL_SIZE_BYTES", "${modelSizeBytes}L")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
