
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
}

android {
    namespace = "com.example.nudgev0"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nitulshah.nudge"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Default flavor — phone/laptop scroll sync AND opt-in analytics,
        // as used for the developer's own devices.
        create("dev") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_CLOUD_FEATURES", "true")
            buildConfigField("boolean", "ENABLE_TELEMETRY", "true")
        }
        // For builds handed to someone else to install (e.g. `assembleFriendDebug`),
        // including the public F-Droid build. Feature parity with dev — phone/
        // laptop scroll sync still works — but analytics/telemetry (retention +
        // product events) is compiled out entirely, not just toggled off.
        create("friend") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_CLOUD_FEATURES", "true")
            buildConfigField("boolean", "ENABLE_TELEMETRY", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the telemetry payload-inspection log (a no-op in release).
        buildConfig = true
    }

    // F-Droid reproducible-builds requirement: strip the Google Play install
    // metadata block that Gradle otherwise embeds in the APK/AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.appcompat)

    val room_version = "2.6.1"
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-service:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    // Unit tests for the platform-agnostic telemetry core (JVM, in-memory fakes).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Phase-0 retention telemetry: persistent storage of the anonymous install ID
    // + opt-in flag + offline event queue. (Thin Supabase POST uses HttpURLConnection;
    // no analytics SDK.)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
