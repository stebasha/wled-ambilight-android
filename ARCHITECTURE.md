# Архитектура

## 1. Обзор

Один Gradle-модуль `:app` с явными границами пакетов. Такое решение выбрано для MVP: оно ускоряет
сборку и не создаёт пустых модулей, но зависимости направлены только «внутрь» — UI зависит от
данных и движка, движок от чистого ядра, ядро не знает об Android (кроме сети/захвата).

```text
ui ─────────────► engine ─────► core/capture
 │                   │  │
 │                   │  └────► core/protocol (DDP, WLED UDP, UdpLedOutput)
 │                   └───────► core/imaging (LedLayoutMapper, FrameAnalyzer, ColorSmoother)
 │
 ├──► core/data (SettingsRepository, DeviceRepository, DiagnosticsRepository, AppContainer)
 │        └──► core/network (WledHttpClient, SubnetScanner) + core/discovery (NSD)
 └──► core/model (settings, layout, diagnostics, validation)
```

```text
app/src/main/java/dev/wledambilight/
├── AmbilightApplication.kt        Application + AppContainer
├── MainActivity.kt                единственная Activity, edge-to-edge Compose
├── core/
│   ├── model/                     Kotlin-модели, валидация, каталог ошибок
│   ├── imaging/                   чистая геометрия/анализ/сглаживание (JVM-тесты)
│   ├── protocol/                  DDP/WLED UDP пакеты, UDP-сокет, test patterns
│   ├── network/                   WLED HTTP-клиент, /24 скан, сетевые утилиты
│   ├── discovery/                 Android NSD/mDNS
│   ├── data/                      DataStore, кодеки, репозитории, DI-контейнер
│   └── capture/                   MediaProjection + ImageReader
├── engine/                        AmbilightEngine + сессии
├── service/                       foreground service и уведомление
└── ui/                            Compose: тема, экраны, компоненты, AppViewModel
```

## 2. Поток данных сессии

```text
MediaProjection ──► VirtualDisplay (320×180) ──► ImageReader (RGBA_8888)
                                                   │ handler thread: ARGB IntArray
                                                   ▼
                          MutableSharedFlow(capacity=1, DROP_OLDEST)
                                                   │ collectLatest
                                                   ▼
                       FrameCadenceLimiter ─► FrameAnalyzer ─► ColorSmoother
                                                   │ IntArray colors (index = LED)
                                                   ▼
                          UdpLedOutput (IO) ─► DDP / WARLS / DRGB / DNRGB
                                                   │
                                                   ▼
                                      preview / stats StateFlow ──► Compose
```

Ключевые свойства:

- **latest-frame-wins**: буфер кадров не растёт. Если анализ медленнее дисплея, промежуточные кадры
  отбрасываются (`DROP_OLDEST` + `collectLatest` + ограничитель FPS).
- **Захват сразу в размере анализа.** VirtualDisplay и ImageReader создаются маленькими, поэтому нет
  масштабирования полноразмерного Bitmap на CPU.
- **Анализ не на main thread**: `Dispatchers.Default`; отправка — `Dispatchers.IO`; ImageReader — свой
  HandlerThread.
- **Один сокет на сессию**, повторное использование адреса; закрытие идемпотентно.
- **Сглаживание хранит состояние только на сессию** и сбрасывается при старте/смене числа LED.

## 3. Индексация LED

`LedLayoutMapper` — единственный источник истины. Он возвращает `List<LedZone>` в порядке физических
индексов: каждая зона содержит `index`, сторону и доли вдоль стороны в **геометрическом** направлении
по часовой стрелке. Угол старта, направление и реверс стороны применяются здесь. Тот же список
используется превью в калибровке, поэтому схема и реальный вывод не могут разойтись.

## 4. Жизненный цикл захвата

1. Пользователь нажимает «Запустить» → системный диалог `createScreenCaptureIntent()`.
2. Результат передаётся в `AmbilightService` (foreground service, тип `mediaProjection`).
3. Сервис сначала вызывает `startForeground`, затем `getMediaProjection` (требование Android 14+).
4. `AmbilightEngine.start` проверяет раскладку и протокол, опционально делает HTTP-probe (таймаут
   2.5 с, не блокирует UDP), резолвит адрес, открывает `UdpLedOutput`, создаёт
   `MediaProjectionFrameSource`, запускает сбор кадров, статистику и слушатель поворота экрана.
5. `MediaProjection.Callback.onStop` (системная остановка/отзыв) → событие → `EngineState.Failed`
   с `PROJECTION_STOPPED` и полная очистка.
6. Стоп: отмена корутин, `VirtualDisplay.release`, `ImageReader.close`, `projection.stop`, закрытие
   сокета, снятие Wi-Fi lock, сброс статистики. WLED выходит из realtime по таймауту пакетов.

`EngineState`: `Idle → Starting → Running | Failed`, `Failed → Idle` при новом старте/стопе.
`generation` защищает от гонки «стоп во время старта».

## 5. Данные и хранение

DataStore Preferences, ключи:

| Ключ | Содержимое |
|---|---|
| `ambilight_config_v1` | JSON `{version, config:{settings, layout}}` |
| `devices_v1` | JSON `{version, devices:[...]}` |
| `selected_device_id` | id выбранного контроллера |
| `onboarding_complete` | Boolean |

`SettingsCodec` отделён от DataStore и покрыт JVM-тестами:

- повреждённый JSON → значения по умолчанию + событие `CONFIG_CORRUPT`;
- старый формат (без envelope) читается как `AmbilightConfig`;
- неизвестное значение enum считается повреждением, а не молчаливой заменой;
- все числовые настройки санитайзятся при чтении.

## 6. Сеть и безопасность

- Только локальные HTTP и UDP; входящих сокетов нет, сервер не поднимается.
- HTTP: connect 3 с, read 4 с, call 6 с, лимит ответа 256 КиБ, отмена через `Call.cancel`.
- JSON: `ignoreUnknownKeys`, `isLenient`, без `coerceInputValues`; неизвестные поля игнорируются,
  неизвестные enum-значения — ошибка.
- mDNS: `_wled._tcp`, multicast lock, таймаут 12 с. Скан /24 — только по явному подтверждению,
  параллельность 24, таймаут 1.2 с на адрес.
- Пароли Wi-Fi не запрашиваются; API-токены не используются; содержимое экрана не логируется.

## 7. DI

`AppContainer` создаётся в `Application`. Зависимости передаются конструкторами:

```text
AppContainer
├── diagnostics (in-memory journal)
├── codec → settingsRepository (DataStore)
├── httpClient → deviceRepository
├── discovery, scanner
└── engine (context, diagnostics, httpClient)
```

Ручной DI выбран вместо Hilt: граф маленький, нет kapt/KSP-цепочки, тесты используют конструкторы и
fakes. Это зафиксировано в IMPLEMENTATION_PLAN.md.

## 8. UI

- Одна Activity, Compose, Material 3, фиксированная тёмная палитра (тёмная/AMOLED).
- `AppViewModel` — фасад состояния: config, devices, discovery, session, diagnostics.
- Навигация: `NavigationRail` при ширине ≥ 840 dp или на TV, иначе `NavigationBar`.
- D-pad: все интерактивные элементы фокусируемы, `tvFocusRing` рисует видимую рамку, стартовые
  действия запрашивают фокус.
- Превью рисуется на Canvas по тем же зонам, что и анализ; в калибровке есть маркер LED 1 и
  «бегущий огонёк», который реально отправляет пакеты.
