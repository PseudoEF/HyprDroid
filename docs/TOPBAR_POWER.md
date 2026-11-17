# Power Menu и глобальный TopBar v0.9 (прототип)

Разработка продолжает последний вариант Quick Settings v0.8; Workspace Sessions не включены. Базовый Home по-прежнему работает без Accessibility, Usage Access или overlay. Все дополнительные доступы включаются пользователем отдельно.

## Какие сигналы действительно доступны

Прежняя OverlaySidebarService знала только lifecycle собственного Home, SCREEN_OFF/USER_PRESENT, PowerManager.isInteractive, KeyguardManager и собственные окна. Она не знала foreground-пакет, категорию игры, состояние чужого видео, IME или fullscreen чужого Activity.

Добавлены добровольные package-only подсказки UsageStatsManager.queryEvents (последние 120 секунд при холодном старте, затем короткие интервалы). В памяти остаются текущий пакет и категория ApplicationInfo. История не записывается и не отправляется. Это не гарантированный top-task: несколько RESUMED Activity, PiP, OEM-задержки, отсутствие событий и отозванный Usage Access дают неопределённость. Категория задаётся поставщиком приложения, может отсутствовать или быть неверной.

**Auto не распознаёт fullscreen.** Эвристика скрывает целиком приложения категории Game/Video, в том числе их обычные экраны. Видео в браузере или игре без категории надёжно не определяется. Insets относятся к собственному overlay; наличие/отсутствие status bar в них не выдаётся за флаги чужого окна. Для таких случаев нужны исключения и ручное скрытие.

## Режимы и управление

Меню Home → **TopBar поверх приложений**. По умолчанию Home only: прежний Waybar в Home остаётся всегда, дубликат WindowManager поверх него не создаётся.

- **Always visible**: глобальный док в обычных приложениях. Контекстная эвристика Game/Video и правила приложений всё ещё применяются.
- **Auto-hide**: обычный док сворачивается после 6 секунд без касания самой панели. Касания приложения под ним не отслеживаются. Появляется небольшая кнопка раскрытия; это не полноэкранная невидимая сенсорная зона.
- Приложения: **Auto / Always show / Always hide**. Always show отключает эвристику категории и таймер для известного пакета; Always hide скрывает док. Правила сохраняются, даже если приложение удалено. Без Usage Access они не могут точно определяться; при наличии Always hide и неизвестном пакете используется консервативное скрытие.
- **Скрыть TopBar до восстановления** в меню глобального дока сохраняет ручное скрытие. Уведомление → **Показать 20 с** временно переопределяет таймер/исключения и снимает ручное скрытие. Блокировка, выключенный экран, Home и защищённые контексты при этом не обходятся.
- **Отключить** в уведомлении/панели останавливает общую overlay-службу и сохраняет выключатель. Имеющиеся боковые панели используют тот же master-switch; их геометрия и модули не сбрасываются.

Ширина, минимальная высота и непрозрачность настраиваются для глобального окна, есть предпросмотр и явное сохранение. Видимость обычного Home Waybar не отключается этими правилами. Вне Home workspace-кнопок нет: кнопка **Home** открывает HyprDroid, не управляет чужими task. Кнопки боковых панелей доступны только при их apps-scope; More открывает инструменты/Quick Settings/Power Menu.

## Окна, клавиатура и жизненный цикл

Док использует тот же Compose Waybar, отдельный визуальный WindowContext и ограниченное окно TYPE_APPLICATION_OVERLAY. FLAG_NOT_FOCUSABLE сохраняет фокус приложения/IME, FLAG_NOT_TOUCH_MODAL позволяет касания вне границ. Полноэкранного прозрачного окна для закрытого дока нет. Для API30+ учитываются публичные system-bar/cutout Insets и метрики окна; API29 использует консервативный верхний отступ. Ориентация пересчитывает геометрию. Это не замена системной строке состояния.

Если собственное окно получает сигнал видимости IME, док становится невидимым без захвата ввода; attached observer остаётся для восстановления после закрытия клавиатуры. Этот сигнал у нефокусируемого overlay не гарантирован на всех Android/OEM, поэтому обещается сохранение фокуса и размещение ниже системных окон, а не универсальное распознавание чужой клавиатуры. При необходимости используйте ручное скрытие.

Раскрытые инструменты/Quick Settings/боковые панели — прежние модальные overlay-окна: нажатие снаружи закрывает их. Это намеренный режим взаимодействия, отличающийся от ограниченного окна дока. Back обрабатывает прежний native BackFrame; Activity-only BackHandler в службу не добавлен.

На экране блокировки, при screen-off и в Home глобальные окна убираются. Док также консервативно скрывается для известных собственных/Settings/SystemUI/permission-controller пакетов; окончательная защита системных экранов и запрет рисования поверх приложений остаются за Android. Специальные приложения могут запрещать overlay; обхода нет.

Служба FGS specialUse стартует только из видимого Home/явного уведомления, START_NOT_STICKY, без BOOT_COMPLETED и обхода ограничений фонового старта. Отзыв SYSTEM_ALERT_WINDOW останавливает службу. Наблюдатели/Compose/Lifecycle очищаются при удалении окон/остановке. При отключённых уведомлениях Android13+ восстановление через шторку уведомлений недоступно: можно вернуться через Home и настройки; есть добровольный запрос POST_NOTIFICATIONS.

## Power Menu

Quick Settings → **⏻** открывает собственный экран питания в теме HyprDroid. Он предлагает **системное меню питания**, не имитирует reboot/shutdown. Без доступа кнопка системного действия недоступна, объяснение ведёт в стандартные специальные возможности Android. Физические кнопки устройства остаются альтернативой.

PowerDialogService — только GLOBAL_ACTION_POWER_DIALOG по явному нажатию. В метаданных нет подписок на события, canRetrieveWindowContent=false, нет canPerformGestures или запросов окон; runtime eventTypes/flags обнуляются. onAccessibilityEvent ничего не делает. Не читаются rootInActiveWindow, тексты, уведомления, key events или содержимое приложений; сервис не участвует в Auto/исключениях. Доступ можно отключить прямо из Power Menu. Результат performGlobalAction обрабатывается как запрос, отказ Android показывает объяснение; прямых привилегированных power-команд нет.

На sideloaded APK Android может потребовать вручную разрешить ограниченные настройки перед включением службы. HyprDroid не включает Accessibility, Usage Access или overlay автоматически.

## Хранение и проверки

Аддитивный JSON v1 `topbar_overlay_configuration` внутри прежнего shell DataStore. Неизвестные версии/некорректные параметры не перезаписываются. Master overlay, panel/widget схемы, workspace, Quick Settings, GIF, applicationId и цепочка подписи сохранены. Разрешения хранятся Android, не выдаются из DataStore.

Тесты должны подтвердить реальные касания в отдельном приложении, фокус EditText, поворот, Game/override, ручное восстановление через уведомление, screen-off, отказ Accessibility и настоящий GLOBAL_ACTION_POWER_DIALOG без нажатий выключения/перезагрузки. Полный UI, подписанная сборка и обновление поверх APK10083 пока ожидаются. Физические Android15–17/OEM, браузерное fullscreen-видео, PiP и несколько RESUMED Activity требуют ручной проверки.

## Источники Android 15–17

- [AccessibilityService / GLOBAL_ACTION_POWER_DIALOG](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [метаданные AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service).
- [TYPE_APPLICATION_OVERLAY и флаги окон](https://developer.android.com/reference/android/view/WindowManager.LayoutParams), [WindowInsets](https://developer.android.com/reference/android/view/WindowInsets).
- [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager), [ApplicationInfo categories](https://developer.android.com/reference/android/content/pm/ApplicationInfo).
- [Android15: более узкое исключение фонового FGS-start с SAW](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-types), [FGS specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
- [Android16: изменения для всех приложений](https://developer.android.com/about/versions/16/behavior-changes-all), [Android17: изменения](https://developer.android.com/about/versions/17/behavior-changes-all), [Android17 background audio](https://developer.android.com/about/versions/17/changes/bg-audio). Ограничения аудио могут затронуть ползунок в глобальном Quick Settings: ошибки/неизменившееся состояние не выдаются за успех, системный экран остаётся fallback. Точный OEM/fullscreen статус эти API не предоставляют.
