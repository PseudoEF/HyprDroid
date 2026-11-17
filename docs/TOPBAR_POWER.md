# TopBar только на Home и Power Menu

## Результат исследования Android 17

Обычный сторонний HOME-лаунчер без системной подписи не может заменить системную строку состояния публичными API Android 17. `TYPE_APPLICATION_OVERLAY` находится выше окон Activity, но ниже status bar и IME; разрешение `SYSTEM_ALERT_WINDOW` не превращает его в системное окно. `WindowInsetsController` управляет видимостью системных панелей своего окна, а не оформлением других приложений. Публичный `StatusBarManager` предоставляет отдельные операции вроде запроса добавления QS-плитки, а не замену SystemUI.

Поэтому эксперимент глобального TopBar завершён. Док под системной строкой перекрывал заголовки приложений и не решал задачу замены status bar. Новые и существующие установки показывают Waybar **только на Home**. В остальных приложениях HyprDroid не создаёт верхнее окно и не меняет системную строку: её видимость определяет Android и само приложение, включая его immersive mode.

Удалены глобальное окно, Auto-hide, эвристика Game/Video, чтение Usage Events, исключения приложений, редактор глобального бара и действие восстановления дока. `PACKAGE_USAGE_STATS` больше не объявляется и не запрашивается. Старые JSON-настройки `topbar_overlay_configuration` остаются неактивной записью совместимости: даже сохранённый Always visible не запускает службу и не создаёт окно. Перезапись или сброс DataStore для отключения не нужны.

## Что сохранено

Home Waybar, workspace, dock, app drawer, Quick Settings, GIF и виджеты работают в прежней архитектуре. Правая/левая overlay-панели сохраняют независимые режимы, модули, геометрию и master-switch. Их служба FGS specialUse запускается только при наличии стороны с apps-scope и добровольного overlay-разрешения. Остановка, системный Back, блокировка и очистка окон остаются прежними. Уведомление службы относится к боковым панелям, с открытием Home и отключением; кнопки восстановления TopBar больше нет.

Ранее опубликованный экспериментальный APK10091 всё ещё содержит глобальный док. Для получения Home-only поведения нужен новый APK из успешного CI этого изменения; выбор старого artifact не обновит реализацию. applicationId, постоянная подпись, счётчик versionCode и пути DataStore сохраняются. Обновление устанавливается поверх, без удаления приложения.

## Power Menu

Quick Settings на Home → **⏻** открывает собственный экран питания. Он предлагает открыть **системное меню питания**. Выключение/перезагрузка выполняются только в системном диалоге Android; прямых привилегированных команд нет.

Опциональная PowerDialogService использует только `GLOBAL_ACTION_POWER_DIALOG` по явному нажатию. `canRetrieveWindowContent=false`, runtime eventTypes/flags=0; нет подписок, чтения окон/узлов, перехвата ввода или других global actions. Без разрешения показаны объяснение и переход в системные настройки. Службу можно отключить из Power Menu. Для sideloaded APK Android может потребовать ручного разрешения ограниченных настроек. Основному лаунчеру и боковым панелям Accessibility не нужен.

## Проверки

Проверка снятия эксперимента включает старые Always visible/Auto-hide в DataStore, Home Waybar, отсутствие дока над отдельным APK, касания/фокус приложения и сохранение работы службы боковых панелей. Прежние UI-тесты панелей, Quick Settings, workspace, виджетов и GIF сохраняются. Подписанный CI настроен на обновление с APK10091 с сохранением данных; результат текущей сборки фиксируется в [BUILD](BUILD.md). Локально Java/JDK отсутствует. Физический Android17 не проверен: вывод об API основан на официальном контракте платформы, не на запуске на устройстве.

## Публичные источники

- [WindowManager.LayoutParams — TYPE_APPLICATION_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY).
- [Immersive mode для собственного окна](https://developer.android.com/develop/ui/views/layout/immersive), [WindowInsetsController](https://developer.android.com/reference/android/view/WindowInsetsController).
- [Публичный StatusBarManager](https://developer.android.com/reference/android/app/StatusBarManager).
- [Android17: изменения для всех приложений](https://developer.android.com/about/versions/17/behavior-changes-all), [изменения для target17](https://developer.android.com/about/versions/17/behavior-changes-17).
- [AccessibilityService / GLOBAL_ACTION_POWER_DIALOG](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
