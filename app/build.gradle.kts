plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.s70rm3892.enginesim"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.s70rm3892.enginesim"
        minSdk = 26          // AAudio (Oboe fast path) + GLES 3.0 保証
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fno-exceptions", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    // 同じネイティブコアから 2 つの独立したアプリを作る (パッケージ名・アプリ名・アイコンが別)
    flavorDimensions += "app"
    productFlavors {
        create("simulator") {
            dimension = "app"
            applicationId = "com.s70rm3892.enginesim"
        }
        create("empire") {
            dimension = "app"
            applicationId = "com.s70rm3892.engineempire"
        }
    }

    // リリース署名: 環境変数 (CI の Secrets) があればその鍵、無ければデバッグ鍵
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { prefab = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // android-all の取得元を差し替えたい環境向け (例: Maven Central のミラー)
            System.getenv("ROBOLECTRIC_REPO")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
        }
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
