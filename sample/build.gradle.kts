/**
 * Приложение-пример.
 *
 * Нужно для двух вещей сразу: показать партнёру минимальную интеграцию и дать
 * Firebase Test Lab пару APK — библиотеку туда не отправить, ферме нужно
 * приложение и тесты к нему.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.morris.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.morris.sample"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":morris-sdk"))

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    // Поднимает настоящий сервер прямо на устройстве: заявка, ролик и пиксели
    // идут по настоящему HTTP, а не через подменённый клиент.
    androidTestImplementation(libs.okhttp.mockwebserver)
}
