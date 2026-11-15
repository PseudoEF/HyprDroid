# Сборка и проверка CI

Инструкции установки APK и быстрого запуска сборки находятся в [README](../README.md).

## Версии инструментов

| Компонент | Версия |
| --- | --- |
| JDK / Java и Kotlin JVM target | 17 |
| Gradle Wrapper | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin Android / Compose Compiler plugin | 2.0.21 / 2.0.21 |
| Compose BOM | 2024.10.01 |
| Compose UI / Foundation (из BOM) | 1.7.5 |
| Material 3 (из BOM) | 1.3.1 |
| Activity Compose | 1.9.3 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 |
| SDK Build Tools (по умолчанию AGP 8.7) | 34.0.0 |

[AGP 8.7](https://developer.android.com/build/releases/agp-8-7-0-release-notes) требует Gradle 8.9 и JDK 17, поддерживает API 35. Версии Wrapper и CI соответствуют этим требованиям.

Версии Kotlin и [Compose Compiler plugin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html) совпадают. BOM управляет версиями библиотек UI, а не компилятором. Состав BOM опубликован в [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.10.01/compose-bom-2024.10.01.pom).

[Матрица Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-configure-project.html) указывает для Kotlin 2.0.21 полностью протестированный диапазон AGP до 8.5; используемый AGP выходит за этот диапазон. Gradle 8.9 поддерживается согласно примечанию о Gradle 8.7–8.10 для обычного Android-проекта. Практическая сборка закреплённой комбинации подтверждена указанным ниже запуском CI; это не означает официальной проверки всей комбинации разработчиками Kotlin.

## Локальная сборка

Установите JDK 17 и Android SDK, примите лицензии SDK и установите пакеты:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"
```

Задайте `ANDROID_HOME` или `sdk.dir` в `local.properties`, затем из корня репозитория выполните:

```sh
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

В Windows используйте `gradlew.bat`. Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`. Для debug-сборки не нужны собственные ключи подписи или секреты GitHub.

Gradle Wrapper хранится в репозитории и загружает Gradle самостоятельно. Установка системного Gradle не требуется. Для загрузки инструментов и зависимостей нужен доступ к сети. Если JDK или SDK отсутствуют, сборку необходимо выполнить в подготовленном окружении или через GitHub Actions.

## GitHub Actions

[Workflow Android debug APK](https://github.com/PseudoEF/HyprDroid/actions/workflows/android.yml) запускается при push в `main`, для pull request и вручную. Он устанавливает JDK 17 и пакеты SDK, настраивает Gradle, выполняет `:app:assembleDebug` через Wrapper и загружает APK.

Artifact называется `HyprDroid-debug-apk`, содержит `app-debug.apk` и хранится 14 дней. Отсутствие APK считается ошибкой job. Это отладочная сборка с debug-подписью, не release-публикация.

[Запуск 43699829017](https://github.com/PseudoEF/HyprDroid/actions/runs/43699829017) для коммита `64d83e1` от 15 ноября 2026 года успешно завершил сборку и публикацию artifact. Установка и работа на реальном устройстве пока не подтверждены; соответствующие пункты [ROADMAP](ROADMAP.md) остаются открытыми.

Для просмотра запусков и загрузки доступного artifact можно использовать GitHub CLI, заменив `RUN_ID` идентификатором нужного запуска:

```sh
gh run list --workflow android.yml --limit 5
gh run view RUN_ID --log-failed
gh run download RUN_ID --name HyprDroid-debug-apk --dir .local/apk
```

## Целостность Wrapper

Файлы Wrapper взяты из официального тега Gradle `v8.9.0`. `gradlew` хранится в Git с правом исполнения; окончания строк определяются `.gitattributes`.

- [SHA-256 Wrapper JAR](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256): `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.
- [SHA-256 дистрибутива](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256), закреплённая в `gradle-wrapper.properties`: `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`.

## Устранённая ошибка настройки SDK

[Первоначальный запуск CI](https://github.com/PseudoEF/HyprDroid/actions/runs/43698100026) завершился до Gradle с сообщением `Failed to find package 'tools'`. Параметр `packages` у `setup-android` теперь явно задаёт необходимые пакеты вместо значения по умолчанию с удалённым `tools`. Поведение параметра описано в [документации setup-android](https://github.com/android-actions/setup-android/tree/v3#additional-packages).
