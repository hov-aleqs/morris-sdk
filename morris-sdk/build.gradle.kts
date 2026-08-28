plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.paparazzi)
    `maven-publish`
}

android {
    namespace = "com.morris.ads"
    compileSdk = 34

    defaultConfig {
        minSdk = 21          // Android 5 — как у вендорских SDK
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Реализации по умолчанию в интерфейсах-слушателях должны стать
        // НАСТОЯЩИМИ default-методами Java. Без этого партнёр на Java обязан
        // реализовать все семь колбэков, даже если ему нужен один.
        freeCompilerArgs += "-Xjvm-default=all"
    }

    publishing {
        singleVariant("release") {
            // Исходники в поставке: партнёр видит документацию прямо в среде
            // разработки, а при разборе сбоя — настоящий код, а не декомпиляцию.
            withSourcesJar()
        }
    }

    // Публичная поверхность — контракт с партнёром. Explicit API не даёт
    // случайно опубликовать класс, который потом нельзя будет убрать.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric нужны настоящие ресурсы: без этого не найдётся ни
        // разметка оверлея, ни строки маркировки.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { explicitApi() }

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    // org.json входит в android.jar только заглушками, а с
    // isReturnDefaultValues=true они молча возвращают нули вместо разбора.
    // Настоящая реализация в тестах — единственный способ проверять парсер
    // на JVM, не поднимая устройство.
    testImplementation(libs.json)
    // Поднимает настоящий HTTP-сервер в тесте: клиент проверяется на реальных
    // ответах и таймаутах, а не на подменённом интерфейсе.
    testImplementation(libs.okhttp.mockwebserver)
    // Гоняет настоящий Android-фреймворк на JVM. Эмулятора на машине нет
    // (нет аппаратной виртуализации), и это единственный способ проверить
    // жизненный цикл, ресурсы и системные API без устройства.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

/**
 * Положить свежий AAR в Unity-пакет.
 *
 * Пакет носит библиотеку файлом, а не тянет координатой Maven, потому что
 * координаты пока нет. Когда SDK будет опубликован, здесь появится
 * `Dependencies.xml` для EDM4U, а файл и эта задача уйдут.
 */
val unityPluginDir = rootProject.file("unity/com.morris.ads/Runtime/Plugins/Android")

tasks.register<Copy>("syncUnityPlugin") {
    group = "morris"
    description = "Скопировать release-AAR в Unity-пакет"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/aar/morris-sdk-release.aar"))
    into(unityPluginDir)
    rename { "morris-sdk.aar" }
}

/**
 * Сверить Unity-мост с собранным AAR.
 *
 * Мост зовёт Kotlin по строковым именам — компилятор их не проверяет.
 * Переименование в SDK иначе обнаружил бы партнёр, а не мы.
 */
tasks.register<Exec>("checkUnityBridge") {
    group = "verification"
    description = "Проверить, что Unity-мост не разошёлся с SDK"
    // syncUnityPlugin кладёт AAR внутрь каталога, который здесь читается.
    // Объявляем зависимость честно — заодно у пакета всегда свежая библиотека.
    dependsOn("assembleRelease", "syncUnityPlugin")
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/check_unity_bridge.py")

    // Только исходники моста. Каталог целиком брать нельзя: туда же
    // syncUnityPlugin кладёт AAR, и Gradle справедливо считает это
    // необъявленной зависимостью между задачами.
    inputs.files(rootProject.fileTree("unity/com.morris.ads/Runtime") { include("**/*.cs") })
        .withPropertyName("bridge")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.buildDirectory.file("outputs/aar/morris-sdk-release.aar"))
        .withPropertyName("aar")
    outputs.upToDateWhen { false }
}

/**
 * Скомпилировать Unity-мост без Unity.
 *
 * Сверка имён проверяет, что мост зовёт существующие методы; эта задача — что
 * он вообще компилируется и не разошёлся с API самой Unity. Без .NET SDK
 * пропускается: требовать его для сборки Android-библиотеки было бы чересчур.
 */
tasks.register<Exec>("checkUnityCompile") {
    group = "verification"
    description = "Скомпилировать Unity-мост против настоящей UnityEngine.dll"
    // syncUnityPlugin пишет внутрь читаемого здесь каталога.
    dependsOn("syncUnityPlugin")
    workingDir = rootProject.projectDir
    commandLine("bash", "tools/check-unity-compile.sh")

    inputs.dir(rootProject.file("unity/com.morris.ads/Runtime"))
        .withPropertyName("bridge")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
}

tasks.named("check") { dependsOn("checkUnityBridge", "checkUnityCompile") }

/**
 * Поставка.
 *
 * Координата вынесена в свойства, а не зашита: `morrisGroupId` придётся
 * выбирать под конкретный способ раздачи. Maven Central принимает только
 * обратный DNS домена, которым владеешь, либо `io.github.<организация>` —
 * голое `morris` там не пройдёт. Для своего репозитория или GitHub Packages
 * ограничения нет.
 *
 *   ./gradlew :morris-sdk:publishToMavenLocal        — в ~/.m2, работает сразу
 *   ./gradlew :morris-sdk:publish -PmorrisRepoUrl=…  — в удалённый репозиторий
 */
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }

            groupId = providers.gradleProperty("morrisGroupId").getOrElse("com.morris")
            artifactId = "morris-ads"
            version = providers.gradleProperty("morrisVersion").getOrElse("1.0.0")

            pom {
                name.set("Morris Ads")
                description.set("Рекламный SDK для Android: rewarded и interstitial")
                licenses {
                    license {
                        name.set("Proprietary — all rights reserved")
                        url.set("https://github.com/hov-aleqs/morris-sdk/blob/main/LICENSE")
                        distribution.set("manual")
                    }
                }
                url.set("https://github.com/hov-aleqs/morris-sdk")
            }
        }
    }

    repositories {
        // По умолчанию собираем дерево репозитория в build/maven-repo: его
        // достаточно выложить статикой на любой веб-сервер. Maven-репозиторий —
        // это просто файлы по HTTP, ни Central, ни GPG для этого не нужны.
        val url = providers.gradleProperty("morrisRepoUrl").getOrElse(
            layout.buildDirectory.dir("maven-repo").get().asFile.toURI().toString()
        )
        run {
            maven {
                name = "morris"
                setUrl(url)
                // Учётные данные задаются, только если репозиторий их требует:
                // для каталога на диске и для публичного HTTP они не нужны.
                val user = providers.gradleProperty("morrisRepoUser").orNull
                val password = providers.gradleProperty("morrisRepoPassword").orNull
                if (user != null && password != null) {
                    credentials {
                        username = user
                        this.password = password
                    }
                }
            }
        }
    }
}
