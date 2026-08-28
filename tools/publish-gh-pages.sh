#!/usr/bin/env bash
#
# Выложить свежую версию SDK в раздачу на GitHub Pages.
#
# Ветка gh-pages держит только собранные артефакты и не имеет ничего общего с
# историей исходников. Старые версии НЕ удаляются: партнёр, собирающий
# приложение на прошлой версии, не должен однажды перестать собираться.
set -euo pipefail
cd "$(dirname "$0")/.."

# Пути берём из окружения: у каждого они свои.
: "${ANDROID_HOME:?укажите ANDROID_HOME}"
export ANDROID_HOME
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac || command -v java)")")")}"


REMOTE="$(git remote get-url origin)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "==> Собираю артефакты"
rm -rf morris-sdk/build/maven-repo
./gradlew --no-daemon :morris-sdk:publish

echo "==> Забираю уже выложенное, чтобы не потерять прошлые версии"
git clone -q --depth 1 --branch gh-pages "$REMOTE" "$STAGE"

cp -r morris-sdk/build/maven-repo/* "$STAGE/maven/"

cd "$STAGE"
if git diff --quiet && git diff --cached --quiet && [ -z "$(git status --porcelain)" ]; then
    echo "Ничего не изменилось — эта версия уже выложена."
    exit 0
fi

VERSION="$(grep -oP '(?<=morrisVersion=).*' "$OLDPWD/gradle.properties" || echo "?")"
git add -A
git commit -q -m "Выложена версия $VERSION"
git push origin gh-pages

echo
echo "Готово. Проверить через минуту-две:"
echo "  curl -I <адрес репозитория>/<группа>/morris-ads/<версия>/morris-ads-<версия>.aar"
