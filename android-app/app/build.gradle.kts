plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mbmlife.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mbmlife.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
    versionName = "0.7.6-v424-battery-fix"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "PWA_URL",
            "\"https://66bardiambm.github.io/mbm-life/?source=android&asset=v418\""
        )
    }

    signingConfigs {
        val ciKeystorePath = System.getenv("MBM_ANDROID_KEYSTORE_PATH")
        if (!ciKeystorePath.isNullOrBlank()) {
            create("ciDebug") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("MBM_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MBM_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("MBM_ANDROID_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("ciDebug")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md"
        )
    }
}

val bundledPwaAssets = layout.buildDirectory.dir("generated/pwaAssets")
val bundleCurrentPwa by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("index.html"))
            versionCode = 18
        versionName = "0.9.0-BOOTTEST"
android.sourceSets.getByName("main").assets.srcDir(bundledPwaAssets)
tasks.named("preBuild").configure { dependsOn(bundleCurrentPwa) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
