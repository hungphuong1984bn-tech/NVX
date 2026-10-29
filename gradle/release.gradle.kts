// Quản lý phiên bản + tác vụ phát hành APK. Áp dụng từ app/build.gradle.kts TRƯỚC khối android { }:
//     apply(from = rootProject.file("gradle/release.gradle.kts"))
//
// Quy tắc:
//  - version.properties (gốc dự án) là NGUỒN DUY NHẤT của versionName/versionCode.
//  - Build debug, test, assembleRelease, sync Android Studio... KHÔNG bao giờ đổi version.properties.
//  - Chỉ `releaseApk` mới phát hành: tính phiên bản kế tiếp (patch+1, code+1) trong bộ nhớ, build bản ký với phiên bản đó,
//    kiểm tra APK, chép sang release-apk/NVX-Connect-v<phiên bản>.apk (KHÔNG ghi đè), và CHỈ KHI TẤT CẢ thành công mới ghi
//    version.properties. Build thất bại = phiên bản không đổi, không có APK mới. Mỗi lần chạy thành công tăng đúng một lần.
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

val nvxVersionFile: File = rootProject.file("version.properties")
val nvxReleaseDir: File = rootProject.file("release-apk")

fun nvxReadVersion(): Pair<String, Int> {
    if (!nvxVersionFile.isFile) throw GradleException("Thiếu ${nvxVersionFile.name} ở gốc dự án.")
    val p = Properties().apply { nvxVersionFile.inputStream().use { load(it) } }
    val name = p.getProperty("VERSION_NAME")?.trim().orEmpty()
    val code = p.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()
    if (!Regex("""\d+\.\d+\.\d+""").matches(name)) throw GradleException("VERSION_NAME phải có dạng MAJOR.MINOR.PATCH, hiện là '$name'.")
    if (code == null || code < 1) throw GradleException("VERSION_CODE phải là số nguyên dương.")
    return name to code
}

fun nvxNext(v: Pair<String, Int>): Pair<String, Int> {
    val (maj, min, pat) = v.first.split(".").map { it.toInt() }
    return "$maj.$min.${pat + 1}" to (v.second + 1)
}

fun nvxSha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().buffered().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val nvxCurrent = nvxReadVersion()
// Chỉ coi là phát hành khi người dùng GỌI TRỰC TIẾP releaseApk (hoặc :app:releaseApk).
val nvxReleaseRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "releaseApk" }
val nvxEffective = if (nvxReleaseRequested) nvxNext(nvxCurrent) else nvxCurrent
extra["nvxVersionName"] = nvxEffective.first
extra["nvxVersionCode"] = nvxEffective.second
extra["nvxReleaseRequested"] = nvxReleaseRequested
val nvxApkName = "NVX-Connect-v${nvxEffective.first}.apk"

tasks.register("printVersion") {
    group = "release"; description = "In phiên bản hiện tại và phiên bản của lần phát hành kế tiếp (không thay đổi gì)."
    val cur = nvxCurrent; val nxt = nvxNext(nvxCurrent); val eff = nvxEffective
    doLast { println("Phiên bản hiện tại: ${cur.first} (code ${cur.second}) · bản phát hành kế tiếp: ${nxt.first} (code ${nxt.second}) · phiên bản đang dùng để build: ${eff.first} (code ${eff.second})") }
}

val nvxCheckTarget = tasks.register("checkReleaseTarget") {
    group = "release"; description = "Kiểm tra trước khi phát hành: tệp đích chưa tồn tại và version.properties chưa bị đổi."
    val target = File(nvxReleaseDir, nvxApkName); val cur = nvxCurrent; val file = nvxVersionFile
    doLast {
        if (target.exists()) throw GradleException("${target.path} đã tồn tại: không ghi đè bản cũ. Di chuyển/đổi tên nó rồi chạy lại.")
        val now = nvxReadVersion()
        if (now != cur) throw GradleException("${file.name} đã đổi trong lúc cấu hình (${cur.first}→${now.first}). Chạy lại để tránh tăng phiên bản hai lần.")
    }
}

// Thứ tự: kiểm tra đích → test → assembleRelease. (Khớp theo tên vì assembleRelease/testDebugUnitTest do AGP đăng ký muộn.)
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { mustRunAfter(nvxCheckTarget) }
tasks.matching { it.name == "assembleRelease" }.configureEach { mustRunAfter(nvxCheckTarget, "testDebugUnitTest") }

tasks.register("releaseApk") {
    group = "release"
    description = "Phát hành có kiểm soát: test → build bản ký → kiểm tra → release-apk/NVX-Connect-v<phiên bản>.apk → tăng version.properties đúng một lần."
    dependsOn(nvxCheckTarget, "testDebugUnitTest", "assembleRelease")
    val cur = nvxCurrent; val eff = nvxEffective; val versionFile = nvxVersionFile; val outDir = nvxReleaseDir; val apkName = nvxApkName
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    doLast {
        if (!nvxReleaseRequested) throw GradleException("releaseApk chỉ hợp lệ khi được gọi trực tiếp.")
        val dir = apkDir.get().asFile
        val candidates = dir.listFiles { f -> f.isFile && f.extension == "apk" }.orEmpty().toList()
        val src = candidates.singleOrNull { !it.name.contains("unsigned") }
            ?: throw GradleException("Không tìm thấy đúng một APK đã ký trong ${dir.path} (có: ${candidates.map { it.name }}). Kiểm tra cấu hình ký (keystore.properties).")

        // 1) Metadata do AGP ghi phải khớp phiên bản dự kiến (chặn APK cũ còn sót).
        val metaFile = File(dir, "output-metadata.json")
        if (!metaFile.isFile) throw GradleException("Thiếu output-metadata.json nên không xác minh được phiên bản của APK.")
        val meta = metaFile.readText()
        val gotCode = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(meta)?.groupValues?.get(1)?.toInt()
        val gotName = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
        if (gotCode != eff.second || gotName != eff.first) throw GradleException("APK có phiên bản $gotName ($gotCode), dự kiến ${eff.first} (${eff.second}). Không phát hành.")

        // 2) APK hợp lệ: mở được, có manifest + dex, có khối chữ ký APK (v2+) hoặc chữ ký JAR.
        if (src.length() < 100_000) throw GradleException("APK quá nhỏ (${src.length()} byte): nghi ngờ hỏng.")
        ZipFile(src).use { z ->
            if (z.getEntry("AndroidManifest.xml") == null || z.getEntry("classes.dex") == null) throw GradleException("APK thiếu AndroidManifest.xml hoặc classes.dex.")
            val jarSigned = z.entries().asSequence().any { it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC")) }
            val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
            val v2Signed = src.readBytes().let { b -> (0..b.size - magic.size).any { i -> b[i] == magic[0] && magic.indices.all { j -> b[i + j] == magic[j] } } }
            if (!jarSigned && !v2Signed) throw GradleException("APK chưa được ký.")
        }

        // 3) Chép KHÔNG ghi đè: sang tệp tạm, kiểm tra SHA-256, ghi version.properties, rồi mới đổi tên thành tên cuối.
        outDir.mkdirs()
        val target = File(outDir, apkName)
        if (target.exists()) throw GradleException("${target.path} đã tồn tại: không ghi đè.")
        val part = File(outDir, "$apkName.part")
        part.delete()
        var versionWritten = false
        val oldText = versionFile.readText()
        try {
            Files.copy(src.toPath(), part.toPath())
            if (part.length() != src.length() || nvxSha256(part) != nvxSha256(src)) throw GradleException("Bản chép APK không khớp bản gốc.")
            if (nvxReadVersion() != cur) throw GradleException("${versionFile.name} đã đổi giữa chừng; không ghi để tránh tăng phiên bản hai lần.")
            val tmp = File(versionFile.parentFile, versionFile.name + ".tmp")
            tmp.writeText(oldText.lines().joinToString("\n") { l ->
                when { l.startsWith("VERSION_NAME=") -> "VERSION_NAME=${eff.first}"; l.startsWith("VERSION_CODE=") -> "VERSION_CODE=${eff.second}"; else -> l }
            })
            Files.move(tmp.toPath(), versionFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            versionWritten = true
            if (target.exists()) throw GradleException("${target.path} vừa xuất hiện: không ghi đè.")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            part.delete()
            if (versionWritten) versionFile.writeText(oldText)          // hoàn tác: thất bại thì KHÔNG được tăng phiên bản
            throw e
        }
        println("PHÁT HÀNH THÀNH CÔNG: ${target.path}")
        println("  phiên bản ${eff.first} (versionCode ${eff.second}) · ${target.length()} byte · SHA-256 ${nvxSha256(target)}")
        println("  version.properties: ${cur.first} (${cur.second}) → ${eff.first} (${eff.second})")
    }
}
