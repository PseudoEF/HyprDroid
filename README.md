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

**Важно:** проект пока не проверен реальной Gradle-сборкой. Dotfiles и настоящий тайлинг сторонних окон пока не реализованы.

## GitHub Actions
Создай репозиторий, загрузи файлы в корень, открой **Actions → Android debug APK → Run workflow**. После успешной сборки APK будет в **Artifacts**. Push в `main` также запускает сборку.

## Локальная сборка
Требуются JDK 17, Android SDK 35 и Gradle 8.9. В стартовом архиве нет Gradle Wrapper (нет `gradlew`), поэтому первый запуск:

```bash
gradle :app:assembleDebug
```

После установки Gradle можно добавить wrapper: `gradle wrapper --gradle-version 8.9` и закоммитить `gradlew`, `gradlew.bat`, `gradle/wrapper/*`. Android Studio также может открыть проект.

## Codex CLI

```bash
cd HyprDroid
codex
```

Стартовый запрос: «Прочитай AGENTS.md и docs/*.md. Сначала проверь сборку проекта и устрани ошибки, затем реализуй Этап 1 из ROADMAP маленькими проверяемыми коммитами».

## Ограничение
Обычный Android-лаунчер без root не может автоматически управлять геометрией окон произвольных сторонних приложений. См. `docs/PROJECT.md`.
