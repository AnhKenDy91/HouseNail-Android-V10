plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "sk.housenail.manager"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "sk.housenail.manager"
        minSdk = 28
        targetSdk = 35
        versionCode = 37
        versionName = "21.10.72.7"
        buildConfigField("String", "HN_FCM_APP_ID", "\""+(System.getenv("HN_FCM_APP_ID").orEmpty().ifBlank { "1:867056772500:android:445137a57add3319159b81" })+"\"")
        buildConfigField("String", "HN_FCM_API_KEY", "\""+(System.getenv("HN_FCM_API_KEY").orEmpty().ifBlank { "AIzaSyAZQZNzUkKHbc4NHduYFDe0QJc4Pl1eHc8" })+"\"")
        buildConfigField("String", "HN_FCM_SENDER_ID", "\""+(System.getenv("HN_FCM_SENDER_ID").orEmpty().ifBlank { "867056772500" })+"\"")
        buildConfigField("String", "HN_FCM_PROJECT_ID", "\""+(System.getenv("HN_FCM_PROJECT_ID").orEmpty().ifBlank { "housenail-54597" })+"\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("HN_STORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("HN_STORE_PASSWORD")
                keyAlias = System.getenv("HN_KEY_ALIAS")
                keyPassword = System.getenv("HN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
