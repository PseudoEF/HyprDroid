# Сборка и проверка CI

## Закреплённые версии

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

[AGP 8.7](https://developer.android.com/build/releases/agp-8-7-0-release-notes) требует Gradle 8.9 и JDK 17, поддерживает API 35. Версии Wrapper и CI соответствуют этим требованиям. Java и Kotlin компилируются в JVM 17.

Версии Kotlin и [Compose Compiler plugin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html) совпадают; при Kotlin 2.x не требуется старый `kotlinCompilerExtensionVersion`. BOM управляет версиями библиотек UI, а не компилятором. Состав BOM проверен по [POM в Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.10.01/compose-bom-2024.10.01.pom).

Ограничение проверки: [матрица Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-configure-project.html) указывает для Kotlin 2.0.21 полностью протестированный диапазон AGP до 8.5; более новые AGP могут использоваться, но выходят за этот диапазон. Gradle 8.9 поддерживается согласно примечанию о Gradle 8.7–8.10 для обычного Android-проекта. Само по себе это не доказывает ошибку сборки. Исходные версии зависимостей сохранены; окончательная проверка этой комбинации — успешная компиляция в CI.

## Исправленная ошибка GitHub Actions

В [run 43698100026](https://github.com/PseudoEF/HyprDroid/actions/runs/43698100026) сборка остановилась до запуска Gradle на `android-actions/setup-android@v3`:

```text
Warning: Failed to find package 'tools'
```

У action по умолчанию указаны `tools platform-tools`. Параметр `packages` теперь переопределён значением `platform-tools platforms;android-35 build-tools;34.0.0`, поэтому удалённый пакет `tools` не запрашивается. См. [документацию setup-android](https://github.com/android-actions/setup-android/tree/v3#additional-packages).

CI устанавливает JDK 17 и SDK, настраивает Gradle через `gradle/actions/setup-gradle@v4` и выполняет:

```sh
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

Затем `actions/upload-artifact@v4` загружает ровно `app/build/outputs/apk/debug/app-debug.apk`. При отсутствии файла job падает. Artifact `HyprDroid-debug-apk` хранится 14 дней. Не нужны signing secrets для debug-сборки.

## Что проверено локально

- `sh -n gradlew`: синтаксис корректен.
- `unzip -t gradle/wrapper/gradle-wrapper.jar`: ошибок архива нет.
- SHA-256 JAR совпадает с [официальной контрольной суммой Gradle](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256): `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.
- В properties закреплена [контрольная сумма дистрибутива](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256): `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`.
- Запуск `./gradlew :app:assembleDebug --no-daemon --stacktrace` останавливается с `JAVA_HOME is not set and no 'java' command could be found`. Java, Gradle и Android SDK в локальной среде не установлены.

Успешная сборка APK и публикация artifact обновлённого workflow пока не подтверждены. После отправки коммита в GitHub необходимо проверить новый run, наличие artifact и установку APK на устройстве; этап проверки CI в ROADMAP остаётся открытым.

```sh
gh run list --workflow android.yml --limit 5
gh run view RUN_ID --log-failed
gh run download RUN_ID --name HyprDroid-debug-apk --dir .local/apk
```

Wrapper взят из официального тега Gradle `v8.9.0`, включая Windows-скрипт и JAR. Его файлы должны храниться в Git; системный Gradle не требуется. Право исполнения `gradlew` и окончания строк задаются в репозитории.
