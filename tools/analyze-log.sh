#!/bin/bash
# Phân tích log ngoại tuyến bằng Kotlin (cần kotlinc 2.x + JDK 17+; KHÔNG cần Android SDK/Gradle).
#   tools/analyze-log.sh log.json [log2.csv] [--ref tham_chieu.csv] [--json]
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd); M=$ROOT/app/src/main/java/vn/nvxconnect
KLIB=$(dirname "$(dirname "$(readlink -f "$(which kotlinc)")")")/lib
OUT=${NVX_CLI_CACHE:-/tmp/nvx-analyze-cli}
if [ ! -f "$OUT/done" ] || [ -n "$(find $M/telemetry $ROOT/tools/AnalyzeLogCli.kt -newer $OUT/done 2>/dev/null)" ]; then
  rm -rf "$OUT"; mkdir -p "$OUT"
  kotlinc $M/telemetry/*.kt $M/bluetooth/SessionLog.kt $M/bluetooth/SurveyModels.kt $M/bluetooth/SurveyRedactor.kt $M/bluetooth/AddressKind.kt \
    $M/protocol/Protocol.kt $M/protocol/VehicleConnection.kt $M/bluetooth/BleError.kt $M/domain/DataSource.kt $M/util/MiniJson.kt $ROOT/tools/AnalyzeLogCli.kt \
    -cp "$KLIB/kotlinx-coroutines-core-jvm.jar" -d "$OUT" 2>&1 | grep -E "error" -A2 && exit 1
  touch "$OUT/done"
fi
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT:$KLIB/kotlin-stdlib.jar:$KLIB/kotlinx-coroutines-core-jvm.jar" AnalyzeLogCliKt "$@"
