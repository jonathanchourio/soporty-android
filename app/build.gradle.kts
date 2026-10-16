import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ─── Ajusta aquí la dirección de tu instalación de Soporty ───────────────
val serverUrl = "https://soporty.com"
// ─────────────────────────────────────────────────────────────────────────

android {
    namespace = "com.soporty.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soporty.app"
        minSdk = 26
        targetSdk = 35
        // En GitHub el número sube solo con cada compilación, para que el cliente pueda actualizar
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    buildFeatures { buildConfig = true }

    // Firma del APK. En GitHub se toma de los «secrets» del repositorio (ver LEEME.md).
    val ks = System.getenv("SP_KEYSTORE_FILE")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("SP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SP_KEY_ALIAS")
                keyPassword = System.getenv("SP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // WebRTC usa JNI: se deja sin ofuscar para evitar errores al ejecutar
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // WebRTC para Android (org.webrtc.*), compilado y publicado por Stream en Maven Central
    implementation("io.getstream:stream-webrtc-android:1.3.9")
}
