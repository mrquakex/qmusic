#!/usr/bin/env bash
set -euo pipefail

# Ev sunucusu için optimize edilmiş Nuvelo Android APK derleme betiği.
# Kullanım:
#   ./server/build-apk.sh fast     -> Hızlı test APK'sı (R8 + lintVital atlanır, yalnızca arm64-v8a, ~1 dk)
#   ./server/build-apk.sh release  -> Tam küçültülmüş release APK (R8 + arm64/armv7, lintVital atlanır)
#   ./server/build-apk.sh full     -> Tam release APK + lintVital (yayın öncesi eksiksiz kontrol)

MODE="${1:-fast}"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JDK_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.13.1+2}"
SDK_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

if pgrep -f "GradleWrapperMain" >/dev/null 2>&1; then
    echo "Başka bir Gradle derlemesi zaten çalışıyor. Bitmesini bekleyin." >&2
    exit 1
fi

cd "$REPO_DIR"

COMMON_ARGS=(
    ":androidApp:assembleRelease"
    "-Dorg.gradle.java.installations.paths=$JDK_HOME"
)

case "$MODE" in
    fast)
        EXTRA_ARGS=("-Pqmusic.fast=true" "-x" "lintVitalRelease" "-x" "uploadCrashlyticsMappingFileRelease")
        ;;
    release)
        EXTRA_ARGS=("-x" "lintVitalRelease" "-x" "uploadCrashlyticsMappingFileRelease")
        ;;
    full)
        EXTRA_ARGS=()
        ;;
    *)
        echo "Geçersiz mod: $MODE (fast | release | full)" >&2
        exit 2
        ;;
esac

echo "Nuvelo APK derleniyor (mod: $MODE, MemoryMax=15G, JDK: $JDK_HOME)..."
systemd-run --user --scope -q -p MemoryMax=15G \
    env JAVA_HOME="$JDK_HOME" ANDROID_HOME="$SDK_HOME" \
    ./gradlew "${COMMON_ARGS[@]}" "${EXTRA_ARGS[@]}"

echo ""
echo "APK hazır:"
ls -lh "$REPO_DIR/androidApp/build/outputs/apk/release/"
