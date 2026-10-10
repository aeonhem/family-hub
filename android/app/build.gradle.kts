import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase's app settings (not secret) for memo push. Read here instead of
// through the google-services plugin so the app still builds, with push off,
// until android/app/google-services.json is added.
val firebase: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parse(f) as Map<String, Any?>
    val info = json["project_info"] as Map<*, *>
    val client = (json["client"] as List<Map<*, *>>).first {
        ((it["client_info"] as Map<*, *>)["android_client_info"] as Map<*, *>)["package_name"] == "com.aeonhem.familyhub"
    }
    mapOf(
        "FCM_PROJECT_ID" to info["project_id"].toString(),
        "FCM_SENDER_ID" to info["project_number"].toString(),
        "FCM_APP_ID" to (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString(),
        "FCM_API_KEY" to (client["api_key"] as List<Map<*, *>>).first()["current_key"].toString(),
    )
}.orEmpty()

android {
    namespace = "com.aeonhem.familyhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aeonhem.familyhub"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so every published build is newer than the
        // last one, which is what update checkers like Obtainium compare.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "0.1.$build"
        listOf("FCM_PROJECT_ID", "FCM_SENDER_ID", "FCM_APP_ID", "FCM_API_KEY").forEach {
            buildConfigField("String", it, "\"${firebase[it].orEmpty()}\"")
        }
    }

    signingConfigs {
        // A fixed key committed to the repo so every CI build can install
        // over the previous one. Fine for a sideloaded family app.
        getByName("debug") {
            storeFile = file("familyhub-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Same key for release builds, so they install over the old debug ones.
        create("family") {
            storeFile = file("familyhub-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("family")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // CI uploads the lint report; warnings shouldn't fail the build.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Memo push from the family server. Free, and wakes an idle phone.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    // Android's own org.json is a stub in unit tests; this is the real one.
    testImplementation("org.json:json:20240303")
}
