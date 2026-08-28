#!/usr/bin/env bash
#
# Выложить SDK в наш Maven-репозиторий.
#
# Maven-репозиторий — это каталог файлов, отданный по HTTP. Здесь он сначала
# собирается локально, а потом копируется на сервер. Ничего, кроме веб-сервера
# со статикой, не нужно.
#
#   ./tools/publish-maven.sh                       — только собрать локально
#   ./tools/publish-maven.sh user@host:/path — собрать и выложить
#
set -euo pipefail
cd "$(dirname "$0")/.."

# Пути берём из окружения: у каждого они свои.
: "${ANDROID_HOME:?укажите ANDROID_HOME}"
export ANDROID_HOME
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac || command -v java)")")")}"

# Группа — домен наоборот. Меняется здесь или через -PmorrisGroupId.
GROUP="${MORRIS_GROUP:-com.morris}"
VERSION="${MORRIS_VERSION:-1.0.0}"
TARGET="${1:-}"
REPO=morris-sdk/build/maven-repo

# Локальный каталог собирается заново каждый раз. Иначе файлы от прошлой
# пробы с другим именем уедут на сервер и останутся там навсегда: rsync
# намеренно ничего не удаляет.
rm -rf "$REPO"

echo "==> Собираю $GROUP:morris-ads:$VERSION"
./gradlew --no-daemon :morris-sdk:publish \
    -PmorrisGroupId="$GROUP" -PmorrisVersion="$VERSION"

echo
echo "==> Готово локально:"
find "$REPO" -type f ! -name '*.md5' ! -name '*.sha*' | sed 's|^|    |'

if [ -n "$TARGET" ]; then
    echo
    echo "==> Выкладываю на $TARGET"
    # Без --delete: старые версии обязаны остаться. Партнёр, собирающий
    # приложение на прошлой версии, не должен однажды перестать собираться.
    rsync -av "$REPO/" "$TARGET/"
fi

echo
echo "Партнёру дать это:"
echo
echo "  // settings.gradle.kts"
echo "  dependencyResolutionManagement {"
echo "      repositories {"
echo "          maven { url = uri(\"https://<адрес>/maven\") }"
echo "      }"
echo "  }"
echo
echo "  // build.gradle.kts приложения"
echo "  implementation(\"$GROUP:morris-ads:$VERSION\")"
