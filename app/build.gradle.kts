plugins {
    id("com.android.application")
}

// Signing key for installable builds. Read from the environment (the GitHub
// workflow writes it from a repository secret), never from the repository.
val keystorePath: String? = System.getenv("DILMUN_KEYSTORE")
val keystorePassword: String? = System.getenv("DILMUN_KEYSTORE_PASSWORD")

android {
    namespace = "app.dilmun.portal"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dilmun.portal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("dilmun") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "dilmun"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("dilmun")
        }
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("dilmun")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
}
