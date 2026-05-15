plugins {
<<<<<<< ours
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

=======
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

>>>>>>> theirs
android {
    namespace = "com.flowseal.tgwsproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flowseal.tgwsproxy"
        minSdk = 24
        targetSdk = 35
<<<<<<< ours
        versionCode = 9
        versionName = "1.6.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
=======
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = false
>>>>>>> theirs
    }

    buildTypes {
        release {
            isMinifyEnabled = false
<<<<<<< ours
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
=======
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
>>>>>>> theirs
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
<<<<<<< ours
=======
        freeCompilerArgs += listOf(
            "-Xjsr305=strict",
        )
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
>>>>>>> theirs
    }
}

dependencies {
<<<<<<< ours
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
=======
    implementation(libs.kotlinx.coroutines.android)
>>>>>>> theirs
}
