#!/usr/bin/env bash
#
# Прогон инструментальных тестов Morris на настоящем устройстве.
#
#   ./tools/run-device-tests.sh local   — на телефоне, подключённом по adb
#   ./tools/run-device-tests.sh ftl     — в Firebase Test Lab
#
# Тесты самодостаточны: сервер поднимается на самом устройстве, ролик лежит
# внутри тестового APK. Внешняя сеть не нужна ни там, ни там.
set -euo pipefail

cd "$(dirname "$0")/.."

# Пути берём из окружения: у каждого они свои.
: "${ANDROID_HOME:?укажите ANDROID_HOME}"
export ANDROID_HOME
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac || command -v java)")")")}"
GRADLE="${GRADLE:-./gradlew}"

APP=sample/build/outputs/apk/debug/sample-debug.apk
TEST=sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk

build() {
    echo "==> Собираю APK"
    "$GRADLE" --no-daemon :sample:assembleDebug :sample:assembleDebugAndroidTest
    ls -la "$APP" "$TEST"
}

case "${1:-local}" in
  local)
    build
    if ! adb get-state >/dev/null 2>&1; then
        echo "Устройство не подключено. Включите отладку по USB или"
        echo "подключитесь по сети: adb connect <ip телефона>:5555"
        exit 1
    fi
    echo "==> Гоню на $(adb shell getprop ro.product.model | tr -d '\r')"
    "$GRADLE" --no-daemon :sample:connectedDebugAndroidTest
    echo "Отчёт: sample/build/reports/androidTests/connected/index.html"
    ;;

  ftl)
    build
    GCLOUD="${GCLOUD:-gcloud}"

    # Физическое устройство, а не виртуальное: проверяем настоящее
    # декодирование и настоящий аудиофокус, ради которых всё и затевалось.
    # Список доступных моделей: gcloud firebase test android models list
    DEVICE="${DEVICE:-model=redfin,version=30,locale=ru,orientation=portrait}"

    echo "==> Отправляю в Firebase Test Lab: $DEVICE"
    "$GCLOUD" firebase test android run \
        --type instrumentation \
        --app "$APP" \
        --test "$TEST" \
        --device "$DEVICE" \
        --timeout 10m \
        --record-video \
        --results-history-name "morris-sdk"
    ;;

  *)
    echo "Использование: $0 [local|ftl]"; exit 2
    ;;
esac
