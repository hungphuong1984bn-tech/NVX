#!/bin/bash
# Sinh docs/HELP_CONTENT_VI.md từ HelpContent.kt. Cần kotlinc (PATH) và JDK.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
KLIB=$(dirname "$(dirname "$(readlink -f "$(which kotlinc)")")")/lib
OUT=$(mktemp -d)
kotlinc "$ROOT/app/src/main/java/vn/nvxconnect/helpcenter/HelpModels.kt" "$ROOT/app/src/main/java/vn/nvxconnect/helpcenter/HelpContent.kt" "$ROOT/tools/GenerateHelpMd.kt" -d "$OUT" 2>/dev/null
java -cp "$OUT:$KLIB/kotlin-stdlib.jar" GenerateHelpMdKt "$ROOT/docs/HELP_CONTENT_VI.md"
echo "Đã sinh $ROOT/docs/HELP_CONTENT_VI.md"
