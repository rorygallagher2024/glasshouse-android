import java.util.Properties

plugins {
    id("com.android.application")
}

/*
 * The upload key for Play. Locally it is named in keystore.properties (kept
 * out of git); the release workflow passes the same four values as
 * environment variables. Without either, a release build is left unsigned.
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    keystoreProps.getProperty(property) ?: System.getenv(env)?.takeIf { it.isNotEmpty() }

val uploadStoreFile = signingValue("storeFile", "GLASSHOUSE_KEYSTORE")

android {
    namespace = "org.glasshouse.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.glasshouse.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile)
                storePassword = signingValue("storePassword", "GLASSHOUSE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GLASSHOUSE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GLASSHOUSE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
}
