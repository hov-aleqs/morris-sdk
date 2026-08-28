#!/usr/bin/env bash
#
# Компиляция Unity-моста без Unity.
#
# Проверяет три сборки, потому что мост живёт в трёх видах:
#   UNITY_ANDROID                — то, что уедет на телефон (весь JNI-код)
#   UNITY_ANDROID + UNITY_EDITOR — то, что видит разработчик в редакторе
#   ни то ни другое              — сборка под другую платформу
#
# UnityEngine.dll берётся из пакета NuGet Unity Technologies — 6 МБ вместо
# четырёх с половиной гигабайт редактора.
set -uo pipefail

cd "$(dirname "$0")/.."
CACHE="${MORRIS_UNITY_CACHE:-$HOME/.cache/morris-unity}"
DLL="$CACHE/UnityEngine.dll"
DOTNET="${DOTNET:-dotnet}"
command -v dotnet >/dev/null 2>&1 && DOTNET="$(command -v dotnet)"

if [ ! -x "$DOTNET" ]; then
    echo "ПРОПУЩЕНО: .NET SDK не найден, мост не скомпилирован."
    echo "  Поставить: curl -sSL https://dot.net/v1/dotnet-install.sh | bash -s -- --channel 8.0 --install-dir \$HOME/dotnet"
    exit 0
fi

if [ ! -f "$DLL" ]; then
    echo "==> Достаю UnityEngine.dll (пакет Unity3D.SDK, ~6 МБ)"
    mkdir -p "$CACHE"
    tmp="$(mktemp -d)"
    if ! curl -sL --max-time 180 -o "$tmp/u.nupkg" \
        "https://www.nuget.org/api/v2/package/Unity3D.SDK/2021.1.14.1"; then
        echo "ПРОПУЩЕНО: не удалось скачать UnityEngine.dll (нет сети?)"
        exit 0
    fi
    unzip -q -o "$tmp/u.nupkg" -d "$tmp" && cp "$tmp/lib/UnityEngine.dll" "$DLL"
    rm -rf "$tmp"
fi

export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
failed=0

build() {
    local label="$1" defines="$2"
    printf '  %-34s ' "$label"
    out="$("$DOTNET" build tools/unity-compile-check/bridge.csproj \
            -v quiet --nologo \
            -p:UnityEngineDll="$DLL" \
            -p:DefineConstants="$defines" 2>&1)"
    if echo "$out" | grep -q "error"; then
        echo "ОШИБКА"
        echo "$out" | grep "error" | sed 's/^/      /' | head -8
        failed=1
    else
        echo "ок"
    fi
}

echo "Компиляция моста против настоящей UnityEngine.dll:"
build "устройство (UNITY_ANDROID)"        "UNITY_ANDROID"
build "редактор (UNITY_ANDROID+EDITOR)"   "UNITY_ANDROID%3BUNITY_EDITOR"
build "другая платформа"                  "UNITY_IOS"

[ "$failed" -eq 0 ] && echo "мост компилируется во всех трёх видах" || echo "мост не компилируется"
exit "$failed"
