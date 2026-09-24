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
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "app.dilmun.portal"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // Phones only: the model runtime is built for 64-bit ARM.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // optimized native code even in debug builds; an unoptimized model is unusably slow
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // The CPU backends are loaded from the app's native library folder at runtime,
    // so the libraries must be extracted there on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
