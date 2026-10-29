#!/usr/bin/env bash
# Kiểm thử logic phiên bản/phát hành (gradle/release.gradle.kts) trên một dự án Gradle GIẢ (không cần Android SDK/AGP).
# Dự án giả chỉ có assembleRelease/testDebugUnitTest tối giản; script phát hành được dùng NGUYÊN BẢN từ repo.
# Cần: gradle >= 8 trong PATH (hoặc đặt GRADLE=/đường/dẫn/gradle) và JDK 17+.   Dùng: bash tools/test-release.sh
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; GRADLE="${GRADLE:-gradle}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/app" "$T/gradle"; cp "$ROOT/gradle/release.gradle.kts" "$T/gradle/"
printf 'VERSION_NAME=0.13.0\nVERSION_CODE=15\n' > "$T/version.properties"
printf 'rootProject.name = "t"\ninclude(":app")\n' > "$T/settings.gradle.kts"
cat > "$T/app/build.gradle.kts" <<'K'
plugins { base }
apply(from = rootProject.file("gradle/release.gradle.kts"))
val pvn = extra["nvxVersionName"] as String; val pvc = extra["nvxVersionCode"] as Int
tasks.register("testDebugUnitTest") { doLast { if (project.hasProperty("failTest")) throw GradleException("test fail") } }
tasks.register("assembleRelease") { doLast {
    if (project.hasProperty("failBuild")) throw GradleException("build fail")
    val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile; dir.deleteRecursively(); dir.mkdirs()
    val name = if (project.hasProperty("unsigned")) "app-release-unsigned.apk" else "app-release.apk"
    val vn = if (project.hasProperty("staleMeta")) "0.0.1" else pvn; val vc = if (project.hasProperty("staleMeta")) 1 else pvc
    java.util.zip.ZipOutputStream(File(dir, name).outputStream()).use { z ->
        fun put(n: String, b: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() }
        put("AndroidManifest.xml", "<manifest/>".toByteArray()); put("classes.dex", ByteArray(150_000).also { java.util.Random(7).nextBytes(it) })
        if (!project.hasProperty("unsigned")) z.setComment("xxAPK Sig Block 42xx")
    }
    File(dir, "output-metadata.json").writeText("""{"elements":[{"versionCode":$vc,"versionName":"$vn","outputFile":"$name"}]}""")
} }
K
cd "$T" || exit 1
FAIL=0
ver() { grep '^VERSION_NAME=' version.properties | cut -d= -f2; }
code() { grep '^VERSION_CODE=' version.properties | cut -d= -f2; }
apks() { ls release-apk 2>/dev/null | tr '\n' ' '; }
g() { "$GRADLE" --offline -q --console=plain "$@" >"$T/out.log" 2>&1; }
ok()   { if [ "$2" = "$3" ]; then echo "  OK   $1"; else echo "  FAIL $1 (có: '$2', cần: '$3')"; FAIL=1; fi; }
echo "[không phát hành → không đổi phiên bản]"
g assembleRelease testDebugUnitTest printVersion; ok "assembleRelease/test/printVersion giữ 0.13.0/15" "$(ver)/$(code)" "0.13.0/15"; ok "không có APK" "$(apks)" ""
echo "[thất bại → không tăng, không xuất]"
for f in -PfailBuild -PfailTest -PstaleMeta -Punsigned; do g releaseApk $f; ok "releaseApk $f giữ 0.13.0/15" "$(ver)/$(code)" "0.13.0/15"; ok "releaseApk $f không có APK" "$(apks)" ""; done
echo "[thành công → tăng đúng một lần, tên đúng]"
g releaseApk; ok "0.13.1/16" "$(ver)/$(code)" "0.13.1/16"; ok "tệp NVX-Connect-v0.13.1.apk" "$(apks)" "NVX-Connect-v0.13.1.apk "
H1="$(sha256sum release-apk/NVX-Connect-v0.13.1.apk | cut -d' ' -f1)"
g :app:releaseApk; ok "lần 2 → 0.13.2/17" "$(ver)/$(code)" "0.13.2/17"; ok "bản cũ còn nguyên" "$(sha256sum release-apk/NVX-Connect-v0.13.1.apk | cut -d' ' -f1)" "$H1"
echo "[không ghi đè]"
echo giu > release-apk/NVX-Connect-v0.13.3.apk; g releaseApk
ok "đích đã có → giữ 0.13.2/17" "$(ver)/$(code)" "0.13.2/17"; ok "đích không bị ghi đè" "$(cat release-apk/NVX-Connect-v0.13.3.apk)" "giu"; ok "không để lại .part" "$(ls release-apk | grep -c part)" "0"
echo
[ "$FAIL" = 0 ] && echo "TẤT CẢ ĐẠT" || { echo "CÓ LỖI"; exit 1; }
