plugins {
    id("com.android.application")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val keystoreFile = rootProject.file("keystore/holst.jks")
val keystorePassword = System.getenv("HOLST_KEYSTORE_PASSWORD") ?: "holst-canvas"

android {
    namespace = "app.holst"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.holst"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = "holst"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
