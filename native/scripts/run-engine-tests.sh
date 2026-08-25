#!/usr/bin/env bash
#
# Сборка и запуск инструментальных тестов движка на устройстве.
#
# Почему не `gradle connectedAndroidTest`: он переустанавливает приложение вокруг
# запуска, а переустановка стирает /sdcard/Android/data/<пакет>/, где лежат
# медиафайлы. Получается гонка: файлы залиты до запуска, а тест их уже не видит и
# молча пропускается через assumeTrue. Здесь порядок задан явно — установка,
# заливка, запуск, — и приложение после прогона остаётся на устройстве.
#
#   bash run-engine-tests.sh                          — все тесты движка
#   bash run-engine-tests.sh NativeProbeTest          — один класс
#   bash run-engine-tests.sh NativeProbeTest#seekTest — один метод
#   SKIP_BUILD=1 bash run-engine-tests.sh             — без пересборки
#
# Устройство: SERIAL=<серийник>, по умолчанию Pixel 6. Гарнитура для тестов
# движка не нужна — проверяется демукс и метаданные, а не VR-вывод.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HERE/../../dddplayer2" && pwd)"

SDK="${ANDROID_SDK_ROOT:-F:/CODEX/android-toolchain/sdk}"
ADB="${ADB:-$SDK/platform-tools/adb.exe}"
export JAVA_HOME="${JAVA_HOME:-F:/CODEX/android-toolchain/jdk/jdk-17.0.20+8}"
export ANDROID_SDK_ROOT="$SDK"

SERIAL="${SERIAL:-24081FDF600AK4}"
APP_ID_SUFFIX="${APP_ID_SUFFIX:-}"
PACKAGE="${PACKAGE:-top.rootu.dddplayer${APP_ID_SUFFIX}}"
TEST_PACKAGE="$PACKAGE.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_PREFIX="top.rootu.dddplayer.engine"

# На устройстве уже может стоять сборка с бо́льшим versionCode (обычная установка
# пользователя). Ставить поверх с downgrade нельзя, а удалять чужую сборку с
# данными — тем более, поэтому просто перебиваем versionCode.
VERSION_CODE="${VERSION_CODE:-900}"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[ -x "$ADB" ] || die "adb не найден: $ADB"

adb_() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" "$@"; }
win()  { cygpath -m "$1"; }

adb_ get-state >/dev/null 2>&1 || die "устройство $SERIAL недоступно ($("$ADB" devices | tr '\n' ' '))"

APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$PROJECT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

# ─── 1. сборка ─────────────────────────────────────────────────────────────────
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    echo ">>> сборка APK приложения и тестов"
    gradle_args=(
        --console=plain -q
        :app:assembleDebug :app:assembleDebugAndroidTest
        "-PversionCodeOverride=$VERSION_CODE"
        -PversionNameOverride=0.0.7-engine
    )
    if [[ -n "$APP_ID_SUFFIX" ]]; then
        gradle_args+=("-PapplicationIdSuffixOverride=$APP_ID_SUFFIX")
    fi
    (cd "$PROJECT" && ./gradlew "${gradle_args[@]}") || die "сборка не прошла"
fi

[ -f "$APK" ]      || die "нет APK: $APK"
[ -f "$TEST_APK" ] || die "нет тестового APK: $TEST_APK"

# ─── 2. установка ──────────────────────────────────────────────────────────────
echo ">>> устройство"
adb_ shell 'echo "  $(getprop ro.product.model) / Android $(getprop ro.build.version.release) / $(getprop ro.product.cpu.abi)"'

# -r: поверх, с сохранением данных. -d: разрешить downgrade, если на устройстве
# осталась сборка с бо́льшим номером. -t: разрешить тестовые APK.
echo ">>> установка $PACKAGE"
adb_ install -r -d -t "$(win "$APK")" 2>&1 | sed 's/^/  /'
echo ">>> установка $TEST_PACKAGE"
adb_ install -r -d -t "$(win "$TEST_APK")" 2>&1 | sed 's/^/  /'

# ─── 3. медиафайлы (только ПОСЛЕ установки: она может очистить внешний каталог) ─
echo ">>> медиафайлы"
SERIAL="$SERIAL" ADB="$ADB" PACKAGE="$PACKAGE" bash "$HERE/push-test-media.sh" 2>&1 | sed 's/^/  /'

# ─── 4. запуск ─────────────────────────────────────────────────────────────────
CLASS_ARG=""
if [[ -n "${1:-}" ]]; then
    # Короткое имя дополняем пакетом: писать полный путь на каждый запуск незачем.
    spec="$1"
    case "$spec" in
        top.rootu.*) ;;                       # уже полное имя
        *) spec="$TEST_PREFIX.$spec" ;;       # NativeProbeTest / NativeProbeTest#метод
    esac
    CLASS_ARG="-e class $spec"
    echo ">>> запуск $spec"
else
    CLASS_ARG="-e package $TEST_PREFIX"
    echo ">>> запуск всех тестов пакета $TEST_PREFIX"
fi

echo "----------------------------------------------------------------"
# -r (raw): единственный режим, в котором виден результат КАЖДОГО теста. Без него
# пропущенный по assumeTrue тест не отличить от пройденного — сводка всё равно
# скажет «OK (11 tests)». Именно так шаг 3 один раз и «прошёл» на пустом каталоге.
set +e
out=$(adb_ shell "am instrument -w -r $CLASS_ARG $TEST_PACKAGE/$RUNNER 2>&1; echo __RC=\$?")
set -e
# adb отдаёт CRLF; без снятия \r якоря конца строки в grep ниже не срабатывают.
out="$(printf '%s' "$out" | tr -d '\r')"

# Код статуса на тест: 0 — пройден, -1/-2 — ошибка/провал, -3 — @Ignore,
# -4 — не выполнен из-за assumeTrue.
passed=$(printf '%s\n' "$out" | grep -c 'STATUS_CODE: 0$' || true)
failed=$(printf '%s\n' "$out" | grep -cE 'STATUS_CODE: -[12]$' || true)
skipped=$(printf '%s\n' "$out" | grep -cE 'STATUS_CODE: -[34]$' || true)

printf '  пройдено: %s   провалено: %s   пропущено: %s\n' "$passed" "$failed" "$skipped"

if [[ "$failed" != "0" ]]; then
    echo "--- провалы:"
    printf '%s\n' "$out" | grep -E 'test=|stack=|^\s+at (top\.rootu|org\.junit)' | sed 's/^/  /'
fi
echo "----------------------------------------------------------------"

# adb shell возвращает код своего соединения, а не код процесса на устройстве,
# поэтому реальный результат берём из маркера.
rc="$(printf '%s' "$out" | sed -n 's/.*__RC=\([0-9]*\).*/\1/p' | tail -1)"

if printf '%s\n' "$out" | grep -q 'Process crashed\|INSTRUMENTATION_FAILED'; then
    die "прогон не состоялся"
fi
[[ "$failed" == "0" ]] || die "провалов: $failed"
[[ "${rc:-1}" == "0" ]] || die "am instrument вернул $rc"
[[ "$passed" != "0" ]] || die "не выполнено ни одного теста"

# Пропуск через assumeTrue выглядит как успех, но ничего не проверяет. Для шага,
# который должен что-то доказать, это провал не меньший, чем красный тест.
if [[ "$skipped" != "0" ]]; then
    echo "ВНИМАНИЕ: пропущено тестов: $skipped — проверьте медиафайлы (push-test-media.sh --check)"
    exit 2
fi

echo "ГОТОВО"
