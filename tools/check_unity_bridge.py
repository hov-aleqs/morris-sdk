#!/usr/bin/env python3
"""
Сверка Unity-моста с настоящим AAR.

Мост зовёт Kotlin по строковым именам через JNI. Компилятор не проверяет
ни одной из этих строк: переименовали метод в SDK — сборка зелёная, а у
партнёра в игре просто перестаёт приходить награда.

Скрипт разбирает C# и проверяет каждое имя по классам из собранного AAR.
Отдельно сверяет слушателей в обе стороны: обработан ли каждый колбэк SDK и
не обрабатывает ли мост то, чего в SDK уже нет.
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAR = os.path.join(ROOT, "morris-sdk/build/outputs/aar/morris-sdk-release.aar")
UNITY = os.path.join(ROOT, "unity/com.morris.ads/Runtime")
JAVAP = os.environ.get("JAVAP", "/usr/lib/jvm/java-17-openjdk-amd64/bin/javap")

# Методы, которые зовутся на объектах, чей класс в C# по имени не назван:
# они приходят аргументами колбэков.
IMPLICIT = {
    "com.morris.ads.model.Reward": ["getAmount", "getCurrency"],
    "com.morris.ads.net.AdError": ["getKind"],
    "com.morris.ads.net.AdError$Server": ["getCode"],
}

# Не наши: Android и java.lang.
FOREIGN = {"runOnUiThread", "currentActivity", "toString", "getMessage", "getClass"}

# Пары «интерфейс слушателя → его класс объявления» для сверки в обе стороны.
LISTENERS = [
    "com.morris.ads.MorrisRewardedAd$Listener",
    "com.morris.ads.MorrisInterstitialAd$Listener",
]

problems = []
notes = []


def fail(msg):
    problems.append(msg)


def members(classes_dir, cls):
    """Публичные члены класса из AAR. Наследуемые javap не показывает."""
    out = subprocess.run(
        [JAVAP, "-classpath", classes_dir, cls],
        capture_output=True, text=True,
    )
    if out.returncode != 0 or not out.stdout.strip():
        return None
    names = set()
    for line in out.stdout.splitlines():
        m = re.search(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", line)
        if m:
            names.add(m.group(1))
        m = re.search(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*;\s*$", line)
        if m:
            names.add(m.group(1))
    return names


def main():
    if not os.path.isfile(AAR):
        print("AAR не собран. Выполните: ./gradlew :morris-sdk:assembleRelease")
        return 2

    newest_src = max(
        (os.path.getmtime(os.path.join(d, f))
         for d, _, fs in os.walk(os.path.join(ROOT, "morris-sdk/src/main"))
         for f in fs if f.endswith(".kt")),
        default=0,
    )
    if newest_src > os.path.getmtime(AAR):
        notes.append("AAR старше исходников SDK — проверка идёт по устаревшей сборке")

    tmp = tempfile.mkdtemp(prefix="morris-bridge-")
    with zipfile.ZipFile(AAR) as z:
        z.extract("classes.jar", tmp)
    with zipfile.ZipFile(os.path.join(tmp, "classes.jar")) as z:
        z.extractall(tmp)

    source = ""
    for d, _, fs in os.walk(UNITY):
        for f in fs:
            if f.endswith(".cs"):
                source += open(os.path.join(d, f), encoding="utf-8").read() + "\n"
    if not source:
        return fail("не найдено ни одного файла моста") or 1

    # --- классы, названные в C# ---
    named = sorted(set(re.findall(r'"(com\.morris\.[A-Za-z0-9_.$]+)"', source)))
    if not named:
        fail("в мосте нет ни одной ссылки на классы SDK — проверять нечего")

    known = {}
    for cls in named + list(IMPLICIT):
        found = members(tmp, cls)
        if found is None:
            fail(f"класса нет в AAR: {cls}")
        else:
            known[cls] = found

    # --- методы, вызванные по имени ---
    called = set(re.findall(r'\.Call(?:Static)?(?:<[^>]+>)?\(\s*"([A-Za-z_][A-Za-z0-9_]*)"', source))
    called |= set(re.findall(r'\.(?:Get|Set)Static(?:<[^>]+>)?\(\s*"([A-Za-z_][A-Za-z0-9_]*)"', source))

    every = set()
    for names in known.values():
        every |= names

    for name in sorted(called):
        if name in FOREIGN or name in every:
            continue
        fail(f"метод не найден ни в одном классе SDK: {name}()")

    # объявленные неявные вызовы должны существовать на своих классах
    for cls, names in IMPLICIT.items():
        for name in names:
            if cls in known and name not in known[cls]:
                fail(f"{cls}.{name}() объявлен в проверке, но в AAR его нет")

    # --- слушатели в обе стороны ---
    handled = set(re.findall(r'case\s+"(on[A-Za-z0-9_]+)"', source))
    for iface in LISTENERS:
        declared = members(tmp, iface)
        if declared is None:
            fail(f"интерфейса слушателя нет в AAR: {iface}")
            continue
        callbacks = {n for n in declared if n.startswith("on")}
        missed = callbacks - handled
        if missed:
            fail(f"{iface}: мост не обрабатывает " + ", ".join(sorted(missed)) +
                 " — эти события молча пропадут")

    all_callbacks = set()
    for iface in LISTENERS:
        d = members(tmp, iface)
        if d:
            all_callbacks |= {n for n in d if n.startswith("on")}
    extra = handled - all_callbacks
    if extra:
        fail("мост обрабатывает события, которых в SDK нет: " + ", ".join(sorted(extra)))

    # --- итог ---
    print(f"классов сверено: {len(known)}")
    print(f"вызовов по имени: {len(called)}")
    print(f"колбэков слушателей: {len(all_callbacks)}, обработано мостом: {len(handled)}")
    for n in notes:
        print(f"ВНИМАНИЕ: {n}")
    if problems:
        print()
        for p in problems:
            print(f"  ОШИБКА: {p}")
        print(f"\nмост разошёлся с SDK: {len(problems)} расхождений")
        return 1
    print("\nмост согласован с AAR")
    return 0


if __name__ == "__main__":
    sys.exit(main())
