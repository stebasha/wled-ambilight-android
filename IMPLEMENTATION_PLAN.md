# IMPLEMENTATION_PLAN — WLED Ambilight for Android / Android TV

Документ ведётся по мере разработки. Отметки `[x]` означают, что этап реально собран и проверен
командами из раздела «Проверка». Незавершённые пункты не выдаются за готовые.

## 1. Аудит репозитория и окружения (Этап 0)

### 1.1. Состояние репозитория

| Проверка | Результат |
|---|---|
| Каталог проекта | `D:\AI\Deepseek` |
| Содержимое до начала работ | только `PROMPT_WLED_Ambilight_Android.md` (ТЗ), git-репозитория не было |
| Существующий Android-код | отсутствует |
| README / Gradle / CI | отсутствуют |
| Вывод | проект создаётся с нуля |

### 1.2. Окружение хоста

| Компонент | Фактически найдено | Действие |
|---|---|---|
| ОС | Windows 10.0.26200 (x64) | — |
| JDK | только Java 8 (`1.8.0_503`), `JAVA_HOME` пуст | скачан Temurin JDK 17.0.20.1 в `.tools/jdk` |
| Android SDK | отсутствует (`ANDROID_HOME` пуст) | скачаны cmdline-tools, platform 35, build-tools 35.0.0, platform-tools 37.0.1 в `.tools/android-sdk` |
| Gradle | не установлен | скачан дистрибутив Gradle 8.13, wrapper добавлен в репозиторий |
| adb | не установлен | входит в platform-tools |
| Node.js | 24.21.0 | не требуется для Android-сборки |
| Сеть | `dl.google.com`, `services.gradle.org` доступны (HTTP 200) | зависимости скачиваются из google()/mavenCentral() |
| Эмулятор / устройство | нет подключённого ADB-устройства | инструментальные тесты компилируются, но не запускаются |
| WLED-контроллер | физически отсутствует | сетевые протоколы проверяются unit-тестами и MockWebServer; реальный прогон описан в TESTING.md |

`sdkmanager` не смог работать из-за недоступного `%USERPROFILE%\.android`; SDK-компоненты установлены
вручную из официального `repository2-1.xml` (URL и размеры сверены). Все инструменты лежат в
`.tools/` и исключены из git; проект не зависит от них на уровне исходников.

### 1.3. Ограничения аудита

- Нет физического Android-устройства, Android TV и WLED-контроллера, поэтому проверки
  MediaProjection, D-pad и реальной доставки UDP выполняются только компиляцией, unit-тестами
  и чек-листом ручной приёмки.
- DRM-контент (Netflix и т. п.) не тестировался; приложение не обходит защиту и явно объясняет
  ограничение.
- Google Play-подпись недоступна: release-сборка собирается неподписанной, ключ должен добавить владелец.

## 2. Принятые архитектурные решения

1. **Один Gradle-модуль `:app`** с жёсткими границами пакетов (`core/model`, `core/imaging`,
   `core/protocol`, `core/network`, `core/discovery`, `core/data`, `core/capture`, `service`, `ui`).
   ТЗ разрешает один модуль при явных границах; это ускоряет сборку и не создаёт пустых модулей.
2. **Ручной DI (`AppContainer`)** вместо Hilt: приложение небольшое, нет kapt/KSP-цепочки и
   связанного риска сборки; зависимости внедряются конструкторами и тестируются fakes.
3. **DataStore Preferences + kotlinx.serialization** для настроек, устройств и раскладки LED.
   Room не нужен: данных мало, запросов нет. Повреждённый JSON не роняет приложение — значения
   заменяются дефолтными, событие пишется в диагностику.
4. **MediaProjection только через foreground service** с типом `mediaProjection`; порядок
   «разрешение → startForegroundService → startForeground → getMediaProjection → VirtualDisplay»
   соответствует требованиям Android 14+.
5. **Realtime-протоколы MVP: DDP (UDP 4048, основной) и WLED UDP Realtime (WARLS/DRGB/DNRGB,
   порт по умолчанию 21324)**. Hyperion и USB Adalight не заявлены: для них нет проверенного
   аппаратного пути в этом окружении.
6. **Захват сразу в уменьшенном разрешении**: VirtualDisplay/ImageReader создаются в размере
   анализа (ширина из настроек, высота по аспекту дисплея), чтобы не масштабировать Bitmap на CPU.
7. **latest-frame-wins**: `MutableSharedFlow(extraBufferCapacity = 1, DROP_OLDEST)` + `collectLatest`
   + ограничитель FPS. Очередь кадров не растёт.
8. **Чистое ядро**: геометрия LED, анализ цвета, сглаживание и формирование пакетов не зависят от
   Android и покрыты JVM unit-тестами.
9. **Без аналитики и облака**: приложение не отправляет кадры, токены и телеметрию; сеть — только
   локальные HTTP/UDP и mDNS.

## 3. Объём MVP

### 3.1. Входит

- onboarding, главный экран, устройства, калибровка, цвет/сглаживание, диагностика, настройки;
- ручное добавление WLED (host/IP + HTTP порт + протокол + выходной порт), проверка `/json/info`;
- mDNS/NSD-поиск `_wled._tcp` и явно запускаемый ограниченный скан локальной /24;
- тест цвета по HTTP и по realtime-протоколу;
- MediaProjection с системным согласием, foreground service, уведомление со «Стоп»;
- расчёт цветов по четырём сторонам, геометрия (угол, направление, реверс сторон),
  яркость/насыщенность/гамма/баланс/мин. яркость/толщина зоны/компенсация чёрных полос;
- профили сглаживания Off/Fast/Balanced/Smooth/Custom с разными параметрами;
- профили производительности (энергосбережение/баланс/плавность/минимальная задержка);
- DDP и WLED UDP (WARLS, DRGB, DNRGB) с packet-level тестами;
- статистика FPS/задержки/пакетов, детект чёрного кадра, журнал ошибок без содержимого экрана;
- D-pad-навигация и видимый фокус, адаптивная навигация (bottom bar / rail);
- unit-тесты, `assembleDebug`, `assembleRelease`, `lintDebug`, компиляция androidTest;
- документация: README, ARCHITECTURE, PROTOCOLS, TESTING, CONTRIBUTING, PRIVACY, CHANGELOG, LICENSES.

### 3.2. Не входит (после MVP)

- Hyperion/HyperHDR, USB Adalight, звуковая реактивность, несколько устройств одновременно,
  экспорт/импорт профилей, автоматический запуск после загрузки, in-app переопределение языка.

## 4. Этапы, критерии готовности и проверка

| Этап | Содержание | Критерий готовности | Проверка | Статус |
|---|---|---|---|---|
| 0 | Аудит, план, инструменты | план зафиксирован, SDK/JDK/Gradle доступны | `java -version`, `source.properties` SDK, `gradle --version` | [x] |
| 1 | Каркас Gradle/Compose/DI/навигация, модели, DataStore | `assembleDebug` успешен | `gradlew assembleDebug` | [x] |
| 2 | WLED HTTP, устройства, mDNS, ручное добавление, тест цвета | probe/JSON/CRUD устройств покрыты тестами | `gradlew testDebugUnitTest` | [x] |
| 3 | Геометрия LED, анализ кадра, сглаживание, превью | unit-тесты алгоритма зелёные | `gradlew testDebugUnitTest` | [x] |
| 4 | MediaProjection, foreground service, жизненный цикл | сборка + код-ревью жизненного цикла, компиляция androidTest | `gradlew assembleDebug assembleDebugAndroidTest` | [x] |
| 5 | DDP + WLED UDP, отправка, ограничение FPS | packet-level тесты зелёные | `gradlew testDebugUnitTest` | [x] |
| 6 | UI/TV UX, калибровка, диагностика, доступность | все экраны собираются, D-pad-фокус реализован | `assembleDebug`, `lintDebug` | [x] |
| 7 | Стабилизация, длительная работа, документы | отчёт с фактическими результатами | `testDebugUnitTest lintDebug assembleRelease` | [x] |
| 8 | Release APK, версия, известные ограничения | APK собран, подпись не подменена | `assembleRelease`, `apkanalyzer` | [x] |

## 5. План тестов

Unit (JVM), фактически 13 классов / 81 тест:
- `LedLayoutMapperTest` — углы, направления, реверс, нулевые стороны, невалидная геометрия;
- `FrameAnalyzerTest` — синтетические кадры, зоны, веса, отступ, чёрные полосы, чёрный кадр;
- `ColorMathTest` — sRGB/linear, насыщенность, гамма, минимальная яркость, баланс;
- `SmoothingTest` — пресеты, порог, ограничение скорости, отсутствие шлейфа;
- `FrameCadenceTest` — ограничение FPS и размеры анализа (портрет/ландшафт);
- `DdpPacketBuilderTest` — заголовок, offset/length, chunking, sequence, push;
- `WledUdpPacketBuilderTest` — WARLS/DRGB/DNRGB, границы и ошибки;
- `UdpLedOutputTest` — реальная отправка по UDP, счётчики, закрытие сокета;
- `WledHttpClientTest` — MockWebServer: info/state, неизвестные поля, битый JSON, таймаут, лимит;
- `SettingsCodecTest` — round-trip, повреждение, легаси-формат, неизвестный enum, санитайзинг;
- `ValidationTest` — host/port/LED, границы настроек;
- `SubnetPlannerTest` — /24 без network/broadcast/self, приватные диапазоны;
- `DiagnosticsRepositoryTest` — ограничение журнала, отсутствие данных экрана в экспорте.

Инструментальные: компилируются (`assembleDebugAndroidTest`), запуск требует устройства — описано в TESTING.md.
Ручные: реальный WLED, Android TV, слабый Wi-Fi, DRM, поворот, 30–60 минут — чек-лист в TESTING.md.

## 6. Риски и стратегия

| Риск | Вероятность | Влияние | Мера |
|---|---|---|---|
| Нет JDK 17 (есть Java 8) | высокая | сборка невозможна | локальный JDK 17 в `.tools`, скрипт `scripts/build.ps1` подставляет `JAVA_HOME` |
| Нет Android SDK | высокая | сборка невозможна | SDK установлен в `.tools`, `local.properties` генерируется скриптом |
| Нет WLED/TV/устройства | 100% | нельзя подтвердить реальную доставку | честный отчёт, packet-level тесты, чек-лист ручной приёмки |
| Android 14 FGS-ограничения | средняя | падение при старте | startForeground до getMediaProjection, тип mediaProjection, ServiceCompat |
| DRM-чёрный кадр | средняя | нет цветов | детект чёрного кадра + объяснение, без обхода |
| mDNS заблокирован в сети | высокая | автопоиск не работает | ручное добавление + явный скан /24 |
| UDP заблокирован | средняя | нет управления | диагностика HTTP/UDP отдельно, понятные шаги |
| Различия версий WLED | средняя | часть полей отсутствует | nullable-поля, игнор неизвестного JSON, ограничения в PROTOCOLS.md |

## 7. Решения по умолчанию (ТЗ п. 18)

- minSdk 26 (MediaProjection, adaptive icons, foreground service); targetSdk 35 (актуальный стабильный
  на момент разработки), compileSdk 35.
- DDP — основной протокол MVP; порт 4048, тип данных RGB888 (0x0B), chunk 480 LED (1440 байт).
- WLED UDP — порт 21324 по умолчанию, режим DNRGB для раскладок >255 LED; WARLS/DRGB доступны.
  Порт 19446 из ТЗ не считается универсальным и не используется по умолчанию.
- Языки: английский (базовый) и русский; выбор языка — системный, переопределение в приложении — post-MVP.
- Локальное хранение: только настройки/устройства; кадры не сохраняются.

## 8. Итоговый отчёт (фактические результаты)

### 8.1. Подготовка окружения

| Шаг | Результат |
|---|---|
| JDK | Temurin 17.0.20.1 распакован в `.tools/jdk/jdk-17.0.20.1+1` |
| Android SDK | platform 35 (`AndroidVersion.ApiLevel=35`), build-tools 35.0.0, platform-tools 37.0.1 в `.tools/android-sdk` |
| Gradle | wrapper 8.13, `gradle/wrapper/gradle-wrapper.jar` добавлен в репозиторий |
| sdkmanager | не смог скачать manifest из-за заблокированного `%USERPROFILE%\.android`; компоненты скачаны вручную из официального `repository2-1.xml` и проверены (`source.properties`, `adb version`) |
| `GRADLE_USER_HOME` / `TEMP` | перенесены в `.tools` из-за запрета записи в системный temp; это описано в README/`scripts/build.ps1` |

### 8.2. Выполненные команды

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
```

Итог: `BUILD SUCCESSFUL`, 123 задачи.

| Проверка | Фактический результат |
|---|---|
| `testDebugUnitTest` | 81 тест, 0 падений (13 классов) |
| `lintDebug` | 0 ошибок, 0 предупреждений; 1 informational (AutoboxingStateCreation) |
| `assembleDebug` | успешно; `app/build/outputs/apk/debug/app-debug.apk`, 17.18 МБ |
| `assembleDebugAndroidTest` | успешно; `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, 0.92 МБ |
| `assembleRelease` (R8, shrink, resource shrink) | успешно; `app/build/outputs/apk/release/app-release-unsigned.apk`, 1.49 МБ |
| `aapt2 dump badging` | `minSdkVersion=26`, `targetSdkVersion=35`, package `dev.wledambilight.debug` |
| `aapt2 dump xmltree` | `AmbilightService` с `foregroundServiceType=0x20` (mediaProjection), `LEANBACK_LAUNCHER`, leanback/touchscreen not required |
| `apksigner verify` release | `DOES NOT VERIFY` — ожидаемо: release не подписан, ключ владельца не создавался |

### 8.3. Что проверено автоматически, а что требует оборудования

| Критерий приёмки (ТЗ §17) | Статус |
|---|---|
| 1. Сборка из чистого checkout | проверено (build-каталог удалён, полная сборка прошла) |
| 2. Ручное добавление, проверка, сохранение | HTTP-клиент и кодек покрыты тестами; UI-путь не прогнан на устройстве |
| 3. Автопоиск mDNS + ручной путь | реализовано; mDNS требует реальной сети |
| 4. Тестовый цвет на реальном WLED | пакеты проверены unit-тестами и локальным UDP; контроллера нет |
| 5. Явное разрешение MediaProjection | реализовано по требованиям Android 14+; устройство отсутствует |
| 6. Анализ незащищённого изображения, соответствие зон | алгоритм покрыт тестами; экранный захват не запускался |
| 7. Нет накопления очереди кадров | реализовано `DROP_OLDEST` + `collectLatest`, проверено тестом cadence |
| 8. Остановка освобождает ресурсы | код-ревью; инструментальная проверка не запускалась |
| 9. Ошибки сети/чёрного кадра/DRM объясняются | реализованы коды, строки и подсказки |
| 10. Действия доступны с TV-пульта | D-pad-фокус и rail реализованы; на TV не проверялось |
| 11. Unit-тесты алгоритма и протокола | 81/81 |
| 12. README с командами | есть |
| 13. Нет ложных заявлений | ограничения перечислены в README/PROTOCOLS/TESTING |

### 8.4. Известные ограничения фактической проверки

- Нет физического Android-устройства, Android TV, эмулятора и WLED-контроллера: не проверены
  MediaProjection, foreground service, D-pad, реальная UDP-доставка, поворот экрана и длительная
  работа.
- DRM-поведение не тестировалось; обход не реализован.
- Release-сборка прошла R8, но её runtime не запускался; правила ProGuard для kotlinx.serialization
  добавлены.
- Инструментальный тест `MainActivitySmokeTest` только компилируется.
- Приложение не переопределяет язык; используется системный (en/ru).
- Hyperion/HyperHDR, USB Adalight, несколько контроллеров, RGBW и импорт/экспорт — post-MVP.

### 8.5. Следующие шаги

1. Прогнать `TESTING.md` на WLED 0.14/0.15 и Android TV, заполнить таблицу проверенных версий.
2. Добавить эмулятор в CI и запускать `connectedDebugAndroidTest`.
3. Проверить release APK после подписи ключом владельца.
4. Реализовать Hyperion/HyperHDR и USB Adalight только с реальным стендом.

