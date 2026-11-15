# HyprDroid

**Hyprland-inspired, rootless, customizable Android launcher.**

HyprDroid — экспериментальный Android-лаунчер с рабочими пространствами, раскладками и будущей поддержкой обмена dotfiles. Основная цель — кастомизация в духе Hyprland без root.

## Уже реализовано в исходниках
- Android HOME activity
- Kotlin + Jetpack Compose
- 3 рабочих пространства (свайпы)
- Переключатель Grid/List для плиток приложений
- Запуск доступных приложений
- CI workflow для debug APK

**Статус сборки:** исправлена настройка SDK в CI и добавлен Gradle Wrapper. Успешная сборка обновлённой версии пока не подтверждена; подробности проверки — в [docs/BUILD.md](docs/BUILD.md). Dotfiles пока не реализованы.

## GitHub Actions
После коммита и push изменений открой **Actions → Android debug APK**. Workflow запускается при push в `main`, pull request и вручную через **Run workflow**. После успешной сборки скачай **Artifacts → HyprDroid-debug-apk**, распакуй архив и установи `app-debug.apk`. Artifact хранится 14 дней. Это debug APK с отладочной подписью, не release-сборка.

## Локальная сборка
Требуются JDK 17 и Android SDK с пакетами `platform-tools`, `platforms;android-35`, `build-tools;34.0.0`. Укажи путь SDK через `ANDROID_HOME` или `sdk.dir` в локальном `local.properties` (не коммитить). Отдельная установка Gradle не нужна: Wrapper загрузит закреплённую версию 8.9 с проверкой SHA-256. При первом запуске нужен доступ к серверам Gradle, Google Maven и Maven Central.

```bash
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

В Windows используй `gradlew.bat :app:assembleDebug --no-daemon --stacktrace`. Результат: `app/build/outputs/apk/debug/app-debug.apk`. Android Studio также может открыть проект; выбери Gradle JDK 17. Компоненты Android SDK используются для сборки и не требуют root, Shizuku или ADB от пользователей лаунчера.

## Codex CLI

```bash
cd HyprDroid
codex
```

Стартовый запрос: «Прочитай AGENTS.md и docs/*.md. Сначала проверь сборку проекта и устрани ошибки, затем реализуй Этап 1 из ROADMAP маленькими проверяемыми коммитами».

## Ограничение
Обычный Android-лаунчер без root не может автоматически управлять геометрией окон произвольных сторонних приложений. См. `docs/PROJECT.md`.
