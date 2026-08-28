// Чистый JVM-модуль: проверяет отрисованные снимки SDK.
//
// Отдельно от :morris-sdk потому, что там тесты компилируются против
// android.jar, где нет javax.imageio — прочитать PNG оттуда нечем. Плюс это
// инструмент верификации, а не код, который уезжает партнёру в AAR.
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies { testImplementation("junit:junit:4.13.2") }

tasks.test {
    useJUnit()

    // Снимки — вход этой задачи. Без объявления Gradle считает её
    // UP-TO-DATE и МОЛЧА пропускает: правку вёрстки никто не проверит,
    // а сборка останется зелёной. Проверено — именно так и было.
    inputs.dir(rootProject.file("morris-sdk/src/test/snapshots/images"))
        .withPropertyName("snapshots")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    testLogging { showStandardStreams = false }
}
