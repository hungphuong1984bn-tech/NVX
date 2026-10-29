plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

import java.util.Properties

// Phiên bản quản lý TẬP TRUNG ở version.properties; tác vụ phát hành `releaseApk` nằm trong gradle/release.gradle.kts (xem docs/RELEASE.md).
apply(from = rootProject.file("gradle/release.gradle.kts"))
val nvxVersionName = extra["nvxVersionName"] as String
val nvxVersionCode = extra["nvxVersionCode"] as Int

// Khóa ký phát hành: keystore.properties (storeFile, storePassword, keyAlias, keyPassword) hoặc biến môi trường NVX_KEYSTORE_FILE,
// NVX_KEYSTORE_PASSWORD, NVX_KEY_ALIAS, NVX_KEY_PASSWORD. Muốn cập nhật đè lên bản đã cài thì PHẢI dùng cùng một khóa mọi lần.
val nvxKeystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties"); if (f.isFile) f.inputStream().use { load(it) }
}
fun nvxSigning(key: String, env: String): String? = (nvxKeystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val nvxStoreFile = nvxSigning("storeFile", "NVX_KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.isFile }
val nvxHasReleaseKey = nvxStoreFile != null && nvxSigning("storePassword", "NVX_KEYSTORE_PASSWORD") != null &&
    nvxSigning("keyAlias", "NVX_KEY_ALIAS") != null && nvxSigning("keyPassword", "NVX_KEY_PASSWORD") != null
if (!nvxHasReleaseKey && (extra["nvxReleaseRequested"] as Boolean))
    logger.warn("CẢNH BÁO: chưa cấu hình khóa phát hành (keystore.properties). APK sẽ ký bằng khóa debug của máy này; chỉ cập nhật đè được lên bản cài từ CHÍNH khóa debug này. Xem docs/RELEASE.md.")

android {
    namespace = "vn.nvxconnect"
    compileSdk = 35
    // applicationId giữ nguyên "vn.nvxconnect" để cài đè lên bản cũ.
    defaultConfig { applicationId = "vn.nvxconnect"; minSdk = 26; targetSdk = 35; versionCode = nvxVersionCode; versionName = nvxVersionName }
    signingConfigs {
        if (nvxHasReleaseKey) create("release") {
            storeFile = nvxStoreFile
            storePassword = nvxSigning("storePassword", "NVX_KEYSTORE_PASSWORD")
            keyAlias = nvxSigning("keyAlias", "NVX_KEY_ALIAS")
            keyPassword = nvxSigning("keyPassword", "NVX_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false   // giữ hành vi như bản debug (Room/KSP, phản chiếu); không đổi mã khi phát hành
            signingConfig = if (nvxHasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }   // BuildConfig.VERSION_NAME/VERSION_CODE = metadata hiển thị trong app
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    testImplementation("junit:junit:4.13.2")
}
