package vn.nvxconnect

/** CHỈ cho tools/verify-jvm.sh (không có Gradle/AGP). Bản build thật dùng BuildConfig do AGP sinh từ version.properties. */
object BuildConfig {
    const val APPLICATION_ID = "vn.nvxconnect"
    const val VERSION_NAME = "0.0.0-jvm"
    const val VERSION_CODE = 0
}
