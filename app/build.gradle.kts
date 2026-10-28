plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.shppointcap.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.shppointcap.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Two build targets, same code:
    //   standard -> Android 8+ (minSdk 26), current platform behaviour
    //   bv9800   -> Android 9 (API 28) Blackview BV9800, pinned to the API 28
    //              permission/behaviour model so nothing depends on 29+
    //              semantics, and so the artifact states what it was tested on.
    // Both are needed: the LocationListener fix below is required on *every*
    // build, but the 9 device gets its own pinned artifact.
    flavorDimensions += "device"
    productFlavors {
        create("standard") {
            dimension = "device"
        }
        create("bv9800") {
            dimension = "device"
            minSdk = 28
            targetSdk = 28
        }
    }

    // Release signing is optional. Set these in ~/.gradle/gradle.properties (never commit them):
    //   SHPCAP_KEYSTORE=/path/to/shp-cap-release.keystore
    //   SHPCAP_KEY_ALIAS=shpcap
    //   SHPCAP_STORE_PASSWORD=...
    //   SHPCAP_KEY_PASSWORD=...
    signingConfigs {
        if (project.hasProperty("SHPCAP_KEYSTORE")) {
            create("release") {
                storeFile = file(project.property("SHPCAP_KEYSTORE") as String)
                storePassword = project.property("SHPCAP_STORE_PASSWORD") as String
                keyAlias = project.property("SHPCAP_KEY_ALIAS") as String
                keyPassword = project.property("SHPCAP_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (project.hasProperty("SHPCAP_KEYSTORE")) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":gcap-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)
    implementation("org.json:json:20240303")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}