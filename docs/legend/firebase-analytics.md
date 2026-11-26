# Google Analytics for Firebase

## Подключение DWIJ

- Только Android-модуль `app`; desktop и shared не зависят от Firebase.
- Для сборки с аналитикой скачайте конфиг своего Firebase-проекта для пакета `com.yellastrodev.dwij` и положите его в `app/google-services.json`. Git игнорирует этот файл. Для официальной сборки используется локальный конфиг проекта DWIJ.
- `app/build.gradle.kts` применяет Google Services `4.5.0` только при наличии этого файла. Без него сборка пропускает обработку конфига, блокирует вызовы отправки просмотров и отключает автоматический сбор через `firebase_analytics_collection_deactivated`.
- Зависимость `firebase-analytics` управляется Firebase BoM `33.16.0` (Analytics `22.5.0`) через `gradle/libs.versions.toml`.
- SDK инициализируется автоматически через свой Android provider; вручную вызывать инициализацию в `yApplication` не требуется.
- Сохраняется автоматический сбор SDK, кроме автопросмотров Activity. Из явных событий добавлен только стандартный `screen_view`; события действий и user ID не добавлены.
- С 2026-11-26 Compose-навигация размечена через `DwijApp(onScreenViewed)` → `MainActivity.reportScreenView`: стандартные параметры `screen_name` и `screen_class=MainActivity`.
- `DwijApp` наблюдает lifecycle текущей записи back stack: событие отправляется в `RESUMED`, включая первый экран, возврат назад, другой объект того же типа и возврат из фона. Рекомпозиции и обновления плеера не создают просмотров; промежуточные экраны, не дошедшие до `RESUMED`, не учитываются.
- Имена берутся из белого списка `NavBackStackEntry.analyticsScreenName()` в `DwijApp.kt`: `home`, `player`, `settings`, `artist`, `album`, `yandex_playlist`, `vk_playlist` и другие. ID объектов, названия и произвольные аргументы не отправляются. Неизвестный маршрут пропускается; при добавлении экрана нужно обновить этот список.
- В `app/src/main/AndroidManifest.xml` отключён только `google_analytics_automatic_screen_reporting_enabled`, чтобы автоматический просмотр Activity не дублировал ручной просмотр Compose-экрана.
- Desktop использует пустой callback по умолчанию и не отправляет события Firebase.

## Проверка на устройстве

1. Собрать и установить приложение с локальным конфигом Firebase. Для проверки сборки без аналитики временно убрать `app/google-services.json`, собрать снова и убедиться, что приложение запускается без него. Gradle в этом репозитории запускает пользователь.
2. Для проверки включить DebugView: `adb shell setprop debug.firebase.analytics.app com.yellastrodev.dwij`.
3. Запустить приложение и проверить события в Firebase → Analytics → DebugView. Google Analytics должен быть включён для Firebase-проекта. Пройти «Главная → Настройки → Назад → Плеер» и проверить последовательность `screen_view` с именами `home`, `settings`, `home`, `player`.
   - Проверить альбомы, артистов, Яндекс/VK и локальные плейлисты, в том числе переход между двумя объектами одного типа.
   - Обновление позиции плеера и другие рекомпозиции не должны создавать события. После сворачивания и возврата ожидается новый просмотр текущего экрана.
   - В отчёте «Экраны и страницы» выбрать измерение имени экрана: класс у всех экранов остаётся `MainActivity`. Перечень типов событий по-прежнему содержит один `screen_view`, различаются его параметры.
4. После проверки отключить режим: `adb shell setprop debug.firebase.analytics.app .none.`.

На 2026-11-08 проверены конфигурация пакета и изменения исходников. Пользовательская release-сборка с BoM `35.1.0` обработала `google-services.json`, но упала на компиляции Kotlin: Measurement `24.0.0` содержит метаданные `2.4.0`, несовместимые с Kotlin `2.1.20` проекта. После замены BoM на `33.16.0` повторная сборка ожидается. Запуск на устройстве и доставка событий в Firebase не проверялись.

## Устарело

- Первоначальный BoM `35.1.0` заменён на `33.16.0` из-за подтверждённой ошибки совместимости Kotlin. Не возвращать его без проверки совместимости компилятора и зависимостей.

## Источники

- [Настройка Firebase для Android](https://firebase.google.com/docs/android/setup).
- [Соответствие BoM 33.16.0 и Analytics 22.5.0](https://firebase.google.com/support/release-notes/android#bom_v33-16-0).
- [Автоматическая инициализация FirebaseApp](https://firebase.google.com/docs/reference/android/com/google/firebase/FirebaseApp).
- [Автоматические и ручные просмотры экранов](https://firebase.google.com/docs/analytics/screenviews).
- [Проверка через DebugView](https://firebase.google.com/docs/analytics/debugview).

На 2026-11-26 изменения навигационной аналитики проверены статически; сборка и проверка новых просмотров на устройстве не выполнялись.
