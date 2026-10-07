import java.util.Properties

plugins {
    id("com.android.application")
}

// Personal signing key (keystore/keystore.properties + keystore/limitgauge.p12). Using the same key as
// the APK published on GitHub Releases lets a Gradle build install over it without uninstalling.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.ynozue.limitgauge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ynozue.limitgauge"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (!keystoreProps.isEmpty()) {
            create("personal") {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val personal = signingConfigs.findByName("personal")
        getByName("debug") {
            if (personal != null) signingConfig = personal
        }
        getByName("release") {
            isMinifyEnabled = false
            if (personal != null) signingConfig = personal
        }
    }

    compileOptions {
        // The sources stay Java 8 compatible so tools/build-apk.sh (javac + dx) can build them too.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
