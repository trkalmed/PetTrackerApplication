plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.pettrackerapplication"
    compileSdk = 35

    androidResources {
        noCompress += "tflite"
    }

    defaultConfig {
        applicationId = "com.example.pettrackerapplication"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room writes the schema JSON here; commit it so migrations have a baseline.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildTypes {
        release {
            // Shrinking cuts the unminified ML Kit + TFLite symbols out of the APK.
            // See proguard-rules.pro for the keeps the reflective libraries need.
            isMinifyEnabled = true
            isShrinkResources = true
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
}

dependencies {
    // Room
    implementation("androidx.room:room-runtime:2.6.1")

    annotationProcessor("androidx.room:room-compiler:2.6.1")
    // TFLite — the COCO detector behind the live view
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")

    // ML Kit — subject segmentation only, for cutting the background out of pet
    // photos in AddPetActivity. Object detection is TFLite's job, not ML Kit's.
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))
    implementation("com.google.firebase:firebase-auth")

    // Camera — keep all four on the same version; camera-view was pinned to
    // 1.2.3 against 1.4.0 siblings, which is asking for a runtime mismatch.
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")

    // UI
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity:1.9.2")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}