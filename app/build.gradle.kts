plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val admobAppId = providers.gradleProperty("EQR_ADMOB_APP_ID")
val admobBannerUnitId = providers.gradleProperty("EQR_ADMOB_BANNER_UNIT_ID")
val admobLargeBannerUnitId = providers.gradleProperty("EQR_ADMOB_LARGE_BANNER_UNIT_ID")
val privacyPolicyUrl = providers.gradleProperty("EQR_PRIVACY_POLICY_URL")

val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testAdaptiveBannerId = "ca-app-pub-3940256099942544/9214589741"
val testFixedBannerId = "ca-app-pub-3940256099942544/6300978111"

android {
    namespace = "com.embasic.eqr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.embasic.eqr"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["admobAppId"] = admobAppId.orElse(testAdmobAppId).get()
        buildConfigField("boolean", "ADS_ENABLED", "true")
        buildConfigField(
            "String",
            "ADMOB_BANNER_UNIT_ID",
            "\"${admobBannerUnitId.orElse(testAdaptiveBannerId).get()}\"",
        )
        buildConfigField(
            "String",
            "ADMOB_LARGE_BANNER_UNIT_ID",
            "\"${admobLargeBannerUnitId.orElse(testFixedBannerId).get()}\"",
        )
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            "\"${privacyPolicyUrl.orElse("").get()}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("io.github.zxing-cpp:android:3.1.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}

val verifyReleasePrivacy by tasks.registering {
    group = "verification"
    description = "Fails release builds when direct logging APIs are present in production Kotlin sources."

    doLast {
        val forbidden = listOf(
            "android.util.Log",
            "Log.",
            "println(",
            "printStackTrace(",
            "Timber.",
            "System.out.print",
            "System.err.print",
        )
        val violations = fileTree("src/main/java") { include("**/*.kt") }
            .files
            .sortedBy { it.path }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    forbidden.firstOrNull { token -> line.contains(token) }?.let { token ->
                        "${file.relativeTo(projectDir).path}:${index + 1}: $token"
                    }
                }
            }
        check(violations.isEmpty()) {
            "Release privacy check found forbidden logging APIs:\n${violations.joinToString("\n")}"
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleasePrivacy)
    doFirst {
        val missing = buildList {
            if (!admobAppId.isPresent) add("EQR_ADMOB_APP_ID")
            if (!admobBannerUnitId.isPresent) add("EQR_ADMOB_BANNER_UNIT_ID")
            if (!admobLargeBannerUnitId.isPresent) add("EQR_ADMOB_LARGE_BANNER_UNIT_ID")
            if (!privacyPolicyUrl.isPresent || !privacyPolicyUrl.get().startsWith("https://")) {
                add("EQR_PRIVACY_POLICY_URL (https://...)")
            }
        }
        check(missing.isEmpty()) {
            "Release build requires production configuration: ${missing.joinToString()}"
        }
    }
}
