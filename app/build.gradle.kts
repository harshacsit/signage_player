import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.signage.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.signage.player"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Define path for release keystore
        val keystorePath = "C:\\Users\\harsh\\keystores\\signage-release.jks"
        val keystoreFile = file(keystorePath)

        // Only create 'release' config if the file exists locally
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                // Use properties from local.properties or environment variables to avoid plaintext secrets
                storePassword = project.findProperty("SIGNING_STORE_PASSWORD")?.toString() ?: ""
                keyAlias = project.findProperty("SIGNING_KEY_ALIAS")?.toString() ?: ""
                keyPassword = project.findProperty("SIGNING_KEY_PASSWORD")?.toString() ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            // Use the release config if it exists, otherwise fallback to debug
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            } else {
                signingConfig = signingConfigs.getByName("debug")
                logger.warn("Release keystore not found at the specified path. Falling back to debug signing.")
            }

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
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Global safety check for all signing configurations (including 'externalOverride' from AS)
// This ensures that if a keystore file is missing, the build doesn't fail validation.
afterEvaluate {
    val debugConfig = android.signingConfigs.findByName("debug")
    if (debugConfig != null) {
        android.signingConfigs.forEach { config ->
            val file = config.storeFile
            if (file != null && !file.exists()) {
                project.logger.lifecycle("SigningConfig '${config.name}' specifies a missing keystore: ${file.absolutePath}. Falling back to debug keys to prevent build failure.")
                config.storeFile = debugConfig.storeFile
                config.storePassword = debugConfig.storePassword
                config.keyAlias = debugConfig.keyAlias
                config.keyPassword = debugConfig.keyPassword
            }
        }
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.play.services.auth)

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    implementation("io.coil-kt:coil:2.6.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.media3:media3-datasource:1.3.1")
    implementation("androidx.media3:media3-database:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")

    // WebRTC
    implementation("org.webrtc:google-webrtc:1.0.32006")

    // Supabase Realtime (signaling channel)
    implementation("io.github.jan-tennert.supabase:realtime-kt:2.6.0")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation(libs.kotlinx.serialization.json)

    // UVC USB webcam support
    implementation("com.github.saki4510t:UVCCamera:master-SNAPSHOT")
}
