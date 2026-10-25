#!/bin/bash
# Kiểm tra nhanh KHÔNG cần Android SDK/Gradle: biên dịch phần logic + lớp Android (type-check với android.jar)
# rồi chạy unit test bằng JUnit trên JVM thường. Không thay thế `gradlew assembleDebug` (không biên dịch Compose/Room KSP/osmdroid).
#
# Bước 1 type-check cả lớp Android (NvxApp, TripService, ViewModel...) với stub; bước 2 chạy unit test.
# Cần: kotlinc 2.0.x (PATH), JDK 17+, android.jar (API 35), junit4.jar + hamcrest-core.jar.
#   ANDROID_JAR=/duong/dan/android.jar JUNIT_DIR=/thu/muc/chua/jar ./tools/verify-jvm.sh
set -e
: "${ANDROID_JAR:?Đặt ANDROID_JAR tới android.jar API 35}" ; : "${JUNIT_DIR:?Đặt JUNIT_DIR chứa junit4.jar và hamcrest-core.jar}"
KLIB=$(dirname "$(dirname "$(readlink -f "$(which kotlinc)")")")/lib
ROOT=$(cd "$(dirname "$0")/.." && pwd); SRC=$ROOT/app/src; M=$SRC/main/java/vn/nvxconnect; T=$SRC/test/java/vn/nvxconnect
OUT=$(mktemp -d); OUT1=$(mktemp -d)
CPBASE="$ANDROID_JAR:$KLIB/kotlinx-coroutines-core-jvm.jar"
kotlinc $(ls $M/bluetooth/*.kt $M/protocol/*.kt $M/navigation/*.kt $M/util/*.kt $M/domain/*.kt $M/data/*.kt $M/location/*.kt $M/auto/*.kt $M/capability/*.kt $M/helpcenter/*.kt $M/vehicledata/*.kt $M/tftintegration/*.kt $M/diagnostics/*.kt $M/telemetry/*.kt $M/pro/*.kt $M/AppInfo.kt $M/NvxApp.kt $M/ui/*ViewModel.kt) $ROOT/tools/stubs/*.kt $ROOT/tools/stubs-typecheck/*.kt -cp "$CPBASE" -d "$OUT1"
echo "== type-check Android: OK =="
FILES="$(ls $M/bluetooth/*.kt $M/protocol/*.kt $M/navigation/*.kt $M/util/*.kt $M/auto/*.kt $M/capability/*.kt $M/helpcenter/*.kt $M/vehicledata/*.kt $M/tftintegration/*.kt $M/diagnostics/*.kt $M/telemetry/*.kt $M/pro/*.kt $M/domain/*.kt $M/data/Db.kt $M/data/RoomTripStore.kt $M/data/DataExporter.kt $M/location/TripEngine.kt)"
kotlinc $FILES $T/*.kt $ROOT/tools/stubs/*.kt -cp "$ANDROID_JAR:$KLIB/kotlinx-coroutines-core-jvm.jar:$JUNIT_DIR/junit4.jar:$JUNIT_DIR/hamcrest-core.jar" -d "$OUT"
CLS=$(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g' | grep -v '\$')
java -cp "$OUT:$KLIB/kotlin-stdlib.jar:$KLIB/kotlinx-coroutines-core-jvm.jar:$JUNIT_DIR/junit4.jar:$JUNIT_DIR/hamcrest-core.jar" org.junit.runner.JUnitCore $CLS
