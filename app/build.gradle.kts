plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningEnvironment = mapOf(
    "BA_GRID_MASTER_KEYSTORE_FILE" to providers.environmentVariable("BA_GRID_MASTER_KEYSTORE_FILE").orNull,
    "BA_GRID_MASTER_KEYSTORE_PASSWORD" to providers.environmentVariable("BA_GRID_MASTER_KEYSTORE_PASSWORD").orNull,
    "BA_GRID_MASTER_KEY_ALIAS" to providers.environmentVariable("BA_GRID_MASTER_KEY_ALIAS").orNull,
    "BA_GRID_MASTER_KEY_PASSWORD" to providers.environmentVariable("BA_GRID_MASTER_KEY_PASSWORD").orNull,
)
val releaseSigningValueCount = releaseSigningEnvironment.values.count { !it.isNullOrBlank() }
check(releaseSigningValueCount == 0 || releaseSigningValueCount == releaseSigningEnvironment.size) {
    "Production release signing requires all four BA_GRID_MASTER_* environment variables, or none of them."
}
val productionSigningEnabled = releaseSigningValueCount == releaseSigningEnvironment.size
if (productionSigningEnabled) {
    val keyStorePath = releaseSigningEnvironment.getValue("BA_GRID_MASTER_KEYSTORE_FILE")!!
    check(file(keyStorePath).isFile) { "Production keystore does not exist: $keyStorePath" }
}

android {
    namespace = "com.bagridmaster.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bagridmaster.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "1.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (productionSigningEnabled) {
            create("productionRelease") {
                storeFile = file(releaseSigningEnvironment.getValue("BA_GRID_MASTER_KEYSTORE_FILE")!!)
                storePassword = releaseSigningEnvironment.getValue("BA_GRID_MASTER_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("BA_GRID_MASTER_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("BA_GRID_MASTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (productionSigningEnabled) {
                signingConfig = signingConfigs.getByName("productionRelease")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
