<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="128" height="128" alt="DDD Player Logo"/>
  <h1>DDD Video Player</h1>

  <p>
    <a href="LICENSE">
      <img src="https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square" alt="License GPL v3"/>
    </a>
    <a href="README_RU.md">
      <img src="https://img.shields.io/badge/Lang-Русский-red.svg?style=flat-square" alt="Читать на русском"/>
    </a>
  </p>

  <p>
    <b>Advanced 3D & HDR Video Player for Android TV and Mobile</b>
    <br>
    <i>Supports Android 6.0 (API 23) and above.</i>
  </p>
</div>

---

# DDD Player 2

**DDD Player 2** — Android-видеоплеер на базе Media3 / ExoPlayer с поддержкой внешнего запуска через `Intent.ACTION_VIEW`, плейлистов, субтитров, HTTP-заголовков, возврата результата и bridge-событий через Android Broadcast.

Проект является форком DDD Player и сейчас должен рассматриваться не как отдельный медиасервер и не как каталог, а как внешний плеер для уже подготовленных ссылок на видео. Основной интеграционный сценарий — запуск из Lampa / Lampac / собственного Android-клиента с передачей текущего видео, плейлиста и параметров bridge.

## Текущий статус

Сейчас в проекте реально есть:

- Android-приложение с `applicationId = top.rootu.dddplayer`;
- запуск видео через `Intent.ACTION_VIEW`;
- чтение одиночного видео из `intent.data`;
- чтение плейлиста из `video_list`;
- чтение названий, файлов, постеров, субтитров и HTTP-заголовков из extras;
- чтение стартовой позиции из `position`;
- возврат позиции через `setResult`, если передан `return_result = true`;
- bridge-конфигурация через `bridge_*` extras;
- отправка bridge-событий через Android Broadcast;
- события позиции, смены элемента плейлиста, окончания, ошибки, перемотки и завершения сессии.

Сейчас в проекте не надо описывать как готовые функции:

- встроенный HTTP-сервер внутри плеера;
- WebSocket-транспорт;
- HTTP callback transport;
- полноценный серверный API управления плеером;
- прямую запись прогресса в историю Lampa;
- готовый JavaScript-мост для web-версии Lampa;
- отдельную новую VR-версию проекта;
- серверную обработку TorrServer / Lampac внутри самого плеера.

Плеер читает входные параметры, открывает переданные ссылки и отдаёт наружу события. Серверная часть остаётся на стороне Lampac, TorrServer или другого источника.

## Общая схема работы

```text
Lampac / TorrServer / другой сервер
        ↓
готовая ссылка или набор ссылок на видео
        ↓
Lampa / Android-клиент / интеграционный слой
        ↓
Intent ACTION_VIEW + extras
        ↓
DDD Player 2
        ↓
Media3 / ExoPlayer
        ↓
Bridge events через Android Broadcast
        ↓
BroadcastReceiver / companion bridge / интеграционный слой Lampa
```

DDD Player 2 не должен сам искать фильмы, работать как TorrServer, хранить каталог или принимать решения о том, как Lampa должна сохранять историю. Его задача — воспроизвести то, что ему передали, и вернуть фактическое состояние просмотра.

## Работа с серверными ссылками

Плеер может открывать `http://` и `https://` ссылки, если они переданы как `intent.data` или элементы `video_list`.

Типовой серверный сценарий:

1. Lampac, TorrServer или другой backend формирует прямую ссылку на поток.
2. Lampa или внешний Android-клиент передаёт эту ссылку в DDD Player 2.
3. Плеер открывает ссылку через ExoPlayer.
4. Если нужны HTTP-заголовки, они передаются через extra `headers`.
5. Если включён bridge, плеер отправляет события наружу.

Пример с заголовками:

```kotlin
val intent = Intent(Intent.ACTION_VIEW)
intent.setDataAndType(
    Uri.parse("https://example.com/video.m3u8"),
    "video/*"
)

intent.putExtra("title", "Example stream")
intent.putExtra(
    "headers",
    arrayOf(
        "User-Agent", "DDDPlayer2",
        "Referer", "https://example.com/"
    )
)

startActivity(intent)
```

`headers` читается как плоский массив строк в формате:

```text
["Key1", "Value1", "Key2", "Value2"]
```

## Одиночное видео

Минимальный запуск:

```kotlin
val intent = Intent(Intent.ACTION_VIEW)
intent.setDataAndType(
    Uri.parse("https://example.com/video.mp4"),
    "video/*"
)

intent.putExtra("title", "Название видео")
intent.putExtra("thumbnail", "https://example.com/poster.jpg")
intent.putExtra("position", 120_000L)

startActivity(intent)
```

Поддерживаемые extras для одиночного видео:

| Extra | Тип | Назначение |
|---|---|---|
| `title` | `String` | Название видео в интерфейсе |
| `android.intent.extra.TITLE` | `String` | Альтернативный источник названия |
| `filename` | `String` | Резервное имя файла |
| `thumbnail` | `String` | URL постера или превью |
| `position` | `Int` / `Long` / `String` | Стартовая позиция в миллисекундах |
| `headers` | `String[]` | HTTP-заголовки для запроса |
| `return_result` | `Boolean` | Вернуть позицию при закрытии плеера |

Для постера сейчас используется `thumbnail`. Extra `poster` в текущем парсере одиночного видео не является основным documented-полем.

## Плейлист

Плейлист передаётся через `video_list`.

```kotlin
val videoUris = arrayOf(
    Uri.parse("https://example.com/s01e01.mp4"),
    Uri.parse("https://example.com/s01e02.mp4"),
    Uri.parse("https://example.com/s01e03.mp4")
)

val titles = arrayOf(
    "Сезон 1, серия 1",
    "Сезон 1, серия 2",
    "Сезон 1, серия 3"
)

val thumbnails = arrayOf(
    "https://example.com/s01e01.jpg",
    "https://example.com/s01e02.jpg",
    "https://example.com/s01e03.jpg"
)

val startUri = videoUris[1]

val intent = Intent(Intent.ACTION_VIEW)
intent.setDataAndType(startUri, "video/*")
intent.putExtra("video_list", videoUris)
intent.putExtra("video_list.name", titles)
intent.putExtra("video_list.thumbnail", thumbnails)
intent.putExtra("start_index", 1)
intent.putExtra("position", 300_000L)

startActivity(intent)
```

Важный текущий нюанс: для плейлиста `position` применяется к тому элементу, URI которого совпадает с `intent.data`. Поэтому при запуске сериала лучше одновременно передавать:

- `intent.data` как URI стартовой серии;
- `video_list` со всеми сериями;
- `start_index` с индексом стартовой серии;
- `position` со стартовой позицией.

Поддерживаемые extras для плейлиста:

| Extra | Тип | Назначение |
|---|---|---|
| `video_list` | `Parcelable[]` / `ArrayList` / строки URI | Список видео |
| `video_list.name` | `String[]` / `ArrayList<String>` | Названия элементов |
| `video_list.filename` | `String[]` / `ArrayList<String>` | Имена файлов |
| `video_list.thumbnail` | `String[]` / `ArrayList<String>` | Постеры элементов |
| `video_list.subtitles` | `ArrayList<Bundle>` | Субтитры для каждого элемента |
| `start_index` | `Int` | Индекс стартового элемента |
| `position` | `Int` / `Long` / `String` | Позиция стартового элемента, если он совпал с `intent.data` |
| `headers` | `String[]` | HTTP-заголовки, общие для элементов плейлиста |

## Субтитры

Для одиночного видео:

```kotlin
val subUris = arrayOf(
    Uri.parse("https://example.com/sub_ru.srt"),
    Uri.parse("https://example.com/sub_en.srt")
)

val subNames = arrayOf("Русский", "English")

intent.putExtra("subs", subUris)
intent.putExtra("subs.name", subNames)
```

Поддерживаемые extras:

| Extra | Тип | Назначение |
|---|---|---|
| `subs` | `Parcelable[]` / URI strings | Ссылки на субтитры |
| `subs.name` | `String[]` | Отображаемые названия субтитров |
| `subs.filename` | `String[]` | Имена файлов субтитров |

Для плейлиста используется `video_list.subtitles`. Это `ArrayList<Bundle>`, где каждый `Bundle` соответствует элементу плейлиста с тем же индексом.

Структура `Bundle` для субтитров элемента плейлиста:

| Ключ внутри Bundle | Тип | Назначение |
|---|---|---|
| `uris` | `Parcelable[]` / URI strings | Ссылки на субтитры |
| `names` | `String[]` | Названия дорожек |
| `uris.filename` | `String[]` | Имена файлов, если нужны |

Пример:

```kotlin
val subs1 = Bundle().apply {
    putParcelableArray(
        "uris",
        arrayOf(Uri.parse("https://example.com/s01e01_ru.srt"))
    )
    putStringArray("names", arrayOf("Русский"))
}

val subs2 = Bundle().apply {
    putParcelableArray(
        "uris",
        arrayOf(Uri.parse("https://example.com/s01e02_ru.srt"))
    )
    putStringArray("names", arrayOf("Русский"))
}

val subtitles = arrayListOf(subs1, subs2)
intent.putParcelableArrayListExtra("video_list.subtitles", subtitles)
```

## Возврат результата через `setResult`

Если передать:

```kotlin
intent.putExtra("return_result", true)
```

то при закрытии плеера будет вызван `setResult(RESULT_OK, resultIntent)`.

Возвращаемые данные:

| Поле | Тип | Назначение |
|---|---|---|
| `resultIntent.data` | `Uri` | URI текущего видео |
| `position` | `Long?` | Текущая позиция |
| `duration` | `Long?` | Длительность |
| `end_by` | `String` | Причина завершения |

Значения `end_by`:

| Значение | Смысл |
|---|---|
| `user` | Пользователь закрыл плеер |
| `completion` | Видео завершилось само |

Этот механизм полезен для простых интеграций, но для сериалов и регулярного обновления позиции лучше использовать bridge-события.

## Bridge

Bridge — это текущий слой обратной связи от DDD Player 2 к внешнему клиенту.

Сейчас реализован один транспорт:

```text
Android Broadcast
```

Режимы HTTP и WebSocket в текущем коде не реализованы, поэтому их не нужно указывать в README как готовые возможности.

### Включение bridge

```kotlin
intent.putExtra("bridge_enabled", true)
intent.putExtra("bridge_session_id", "lampa-session-001")
intent.putExtra("bridge_client", "lampa")
intent.putExtra("bridge_mode", "broadcast")
intent.putExtra("bridge_emit_position", true)
intent.putExtra("bridge_emit_user_actions", true)
intent.putExtra("bridge_position_interval_ms", 1000L)
intent.putExtra("bridge_event_action", "top.rootu.dddplayer.bridge.EVENT")
intent.putExtra("bridge_schema_version", 1)
```

Поддерживаемые bridge extras:

| Extra | Тип | Значение по умолчанию | Назначение |
|---|---|---|---|
| `bridge_enabled` | `Boolean` | `false` | Включить отправку bridge-событий |
| `bridge_session_id` | `String` | `null` | ID текущей сессии просмотра |
| `bridge_mode` | `String` | `broadcast` | Сейчас поддерживается только `broadcast` |
| `bridge_emit_position` | `Boolean` | `true` | Отправлять периодические события позиции |
| `bridge_emit_user_actions` | `Boolean` | `true` | Флаг для пользовательских действий |
| `bridge_position_interval_ms` | `Long` | `1000` | Интервал отправки позиции; минимум принудительно ограничен `250` мс |
| `bridge_client` | `String` | `lampa` | Имя клиента |
| `bridge_event_action` | `String` | `top.rootu.dddplayer.bridge.EVENT` | Broadcast action |
| `bridge_receiver_package` | `String` | `null` | Package получателя, если broadcast надо ограничить конкретным приложением |
| `bridge_schema_version` | `Int` | `1` | Версия схемы события |

### Broadcast action

По умолчанию события отправляются с action:

```text
top.rootu.dddplayer.bridge.EVENT
```

Broadcast содержит extras:

| Extra | Тип | Назначение |
|---|---|---|
| `schema` | `Int` | Версия схемы |
| `client` | `String` | Клиент, указанный в `bridge_client` |
| `session_id` | `String?` | ID сессии |
| `event_type` | `String` | Тип события |
| `event_json` | `String` | JSON-обёртка события |

Формат `event_json`:

```json
{
  "schema": 1,
  "type": "PositionTick",
  "client": "lampa",
  "sessionId": "lampa-session-001",
  "ts": 1710000000000,
  "payload": {
    "sessionId": "lampa-session-001",
    "ts": 1710000000000,
    "uri": "https://example.com/s01e02.mp4",
    "position": 300000,
    "duration": 2700000,
    "bufferedPosition": 320000,
    "bufferedPercentage": 42,
    "windowIndex": 1,
    "title": "Сезон 1, серия 2"
  }
}
```

## Bridge-события

### `SessionStarted`

Отправляется после загрузки непустого плейлиста.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `sessionId` | `String?` | ID сессии |
| `ts` | `Long` | Время события |
| `uri` | `String?` | URI текущего элемента |
| `title` | `String?` | Название |
| `playlistSize` | `Int` | Размер плейлиста |
| `startIndex` | `Int` | Стартовый индекс |
| `startPosition` | `Long?` | Стартовая позиция, если больше нуля |
| `currentItem` | `BridgeMediaItem?` | Текущий элемент |

Сейчас `currentItem` при старте заполняется базовыми полями `uri`, `title`, `filename`.

### `PlaybackStateChanged`

Отправляется при изменении состояния воспроизведения.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `isPlaying` | `Boolean` | Идёт ли воспроизведение |
| `isBuffering` | `Boolean` | Идёт ли буферизация |
| `position` | `Long?` | Текущая позиция |
| `duration` | `Long?` | Длительность |
| `windowIndex` | `Int?` | Индекс элемента плейлиста |
| `title` | `String?` | Название |

### `PositionTick`

Периодическое событие позиции. Отправляется, если включён `bridge_emit_position`.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `position` | `Long?` | Текущая позиция |
| `duration` | `Long?` | Длительность |
| `bufferedPosition` | `Long?` | Позиция буфера |
| `bufferedPercentage` | `Int?` | Расчётный процент буфера |
| `windowIndex` | `Int?` | Индекс элемента плейлиста |
| `title` | `String?` | Название текущего элемента |

Для интеграции с Lampa это основное событие для регулярной синхронизации прогресса.

### `SeekCompleted`

Отправляется после перемотки.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `fromPosition` | `Long?` | Позиция до перемотки |
| `toPosition` | `Long?` | Позиция после перемотки |
| `windowIndex` | `Int?` | Индекс элемента плейлиста |

### `PlaylistItemChanged`

Отправляется при смене элемента плейлиста.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `uri` | `String?` | URI нового элемента |
| `windowIndex` | `Int` | Индекс нового элемента |
| `playlistSize` | `Int` | Размер плейлиста |
| `title` | `String?` | Название нового элемента |
| `reason` | `String` | Причина перехода |
| `position` | `Long?` | Текущая позиция |
| `duration` | `Long?` | Длительность |
| `hasPrevious` | `Boolean` | Есть ли предыдущий элемент |
| `hasNext` | `Boolean` | Есть ли следующий элемент |
| `currentItem` | `BridgeMediaItem?` | В модели есть поле, но в текущей отправке события оно может быть `null` |

Это главное событие для сериалов. По нему внешний слой может понять, что пользователь перешёл на другую серию внутри плеера.

### `PlaybackEnded`

Отправляется при достижении конца текущего элемента.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `windowIndex` | `Int` | Индекс элемента |
| `playlistSize` | `Int` | Размер плейлиста |
| `title` | `String?` | Название |
| `position` | `Long?` | Финальная позиция |
| `duration` | `Long?` | Длительность |

### `SessionFinished`

Отправляется при закрытии `PlayerActivity`.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `uri` | `String?` | URI текущего элемента |
| `position` | `Long?` | Финальная позиция |
| `duration` | `Long?` | Длительность |
| `endBy` | `String` | Причина завершения |
| `windowIndex` | `Int?` | Индекс элемента |
| `playlistSize` | `Int?` | Размер плейлиста |
| `title` | `String?` | Название |

### `Error`

Отправляется при ошибке воспроизведения, если ошибка не была восстановлена автоматически.

Поля payload:

| Поле | Тип | Назначение |
|---|---|---|
| `code` | `String?` | Код ошибки ExoPlayer |
| `message` | `String?` | Сообщение ошибки |
| `windowIndex` | `Int?` | Индекс элемента |

### `UserAction`

Модель события есть в коде, но текущая документация не должна описывать конкретные пользовательские действия как готовый API, пока они явно не отправляются в нужных местах интерфейса.

## Интеграция с Lampa / Lampac

Правильное разделение ответственности:

| Компонент | Ответственность |
|---|---|
| Lampac / TorrServer / сервер | Подготовить ссылку или набор ссылок на видео |
| Lampa / интеграционный плагин | Выбрать карточку, серию, источник, позицию, собрать Intent |
| DDD Player 2 | Прочитать Intent, воспроизвести видео, отправить события |
| BroadcastReceiver / bridge-приёмник | Получить события и передать их туда, где хранится прогресс |

Для сериалов Lampa-плагину желательно передавать:

- полный `video_list`;
- корректный `start_index`;
- `intent.data`, совпадающий с URI стартовой серии;
- `position` для стартовой серии;
- понятные `video_list.name`;
- `bridge_session_id`;
- `bridge_enabled = true`.

Минимальный пример запуска серии с bridge:

```kotlin
val videoUris = arrayOf(
    Uri.parse("https://server.local/s01e01.m3u8"),
    Uri.parse("https://server.local/s01e02.m3u8")
)

val titles = arrayOf(
    "Сезон 1, серия 1",
    "Сезон 1, серия 2"
)

val startIndex = 1
val startUri = videoUris[startIndex]

val intent = Intent(Intent.ACTION_VIEW)
intent.setDataAndType(startUri, "video/*")
intent.putExtra("video_list", videoUris)
intent.putExtra("video_list.name", titles)
intent.putExtra("start_index", startIndex)
intent.putExtra("position", 180_000L)

intent.putExtra("bridge_enabled", true)
intent.putExtra("bridge_session_id", "lampa-card-123-s01e02")
intent.putExtra("bridge_client", "lampa")
intent.putExtra("bridge_mode", "broadcast")
intent.putExtra("bridge_emit_position", true)
intent.putExtra("bridge_position_interval_ms", 1000L)
intent.putExtra("bridge_event_action", "top.rootu.dddplayer.bridge.EVENT")
intent.putExtra("bridge_schema_version", 1)

startActivity(intent)
```

## Что должен делать принимающий слой

Принимающий слой должен зарегистрировать `BroadcastReceiver` на action:

```text
top.rootu.dddplayer.bridge.EVENT
```

Дальше он должен читать:

```kotlin
val eventType = intent.getStringExtra("event_type")
val eventJson = intent.getStringExtra("event_json")
val sessionId = intent.getStringExtra("session_id")
```

Рекомендуемая логика обработки:

| Событие | Что делать принимающему слою |
|---|---|
| `SessionStarted` | Создать или обновить активную сессию просмотра |
| `PositionTick` | Обновить текущую позицию |
| `SeekCompleted` | Зафиксировать перемотку и новую позицию |
| `PlaylistItemChanged` | Переключить активную серию по `windowIndex` / `uri` |
| `PlaybackEnded` | Отметить элемент как досмотренный или близкий к досмотру |
| `SessionFinished` | Финально сохранить состояние |
| `Error` | Показать диагностику или сохранить ошибку |

Если Lampa работает как web-приложение, ей может понадобиться отдельный native-companion или Android-слой, который примет broadcast и уже потом передаст данные в JS. Такой слой не является частью текущего репозитория DDD Player 2.

## Сопоставление серий

Текущий bridge уже отдаёт `uri`, `windowIndex` и `title`. Этого достаточно для базового сопоставления, если плейлист стабилен.

Для более надёжного сопоставления в будущем лучше передавать и сохранять внешние идентификаторы серии, сезона, карточки и источника. В модели `BridgeMediaItem` такие поля есть:

| Поле | Назначение |
|---|---|
| `externalId` | Внешний ID элемента |
| `season` | Номер сезона |
| `episode` | Номер серии |
| `source` | Источник |

Но текущий Intent-парсер не читает отдельные extras для `externalId`, `season`, `episode` и `source`, поэтому в README нельзя описывать их как уже готовую интеграцию.

## Сборка

Debug-сборка:

```bash
./gradlew assembleDebug
```

Release-сборка:

```bash
./gradlew assembleRelease
```

Установка debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Проверка запуска через adb:

```bash
adb shell am start \
  -a android.intent.action.VIEW \
  -d "https://example.com/video.mp4" \
  -t "video/*" \
  --es title "Test video" \
  --el position 120000 \
  --ez bridge_enabled true \
  --es bridge_session_id "adb-test-session" \
  --es bridge_client "lampa" \
  --es bridge_mode "broadcast" \
  --ez bridge_emit_position true \
  --el bridge_position_interval_ms 1000
```

## Диагностика

Смотреть события и ошибки можно через logcat:

```bash
adb logcat | grep -i DDDPlayer
```

Для bridge дополнительно проверять:

1. Передан ли `bridge_enabled = true`.
2. Совпадает ли `bridge_event_action` у отправителя и получателя.
3. Не указан ли ошибочный `bridge_receiver_package`.
4. Зарегистрирован ли `BroadcastReceiver` у принимающего слоя.
5. Передаётся ли стабильный `bridge_session_id`.
6. Передаётся ли `intent.data` при запуске плейлиста с позиции.
7. Совпадает ли `start_index` с фактической стартовой серией.

## Ключевая идея текущей версии

```text
DDD Player 2 — это внешний Android-плеер, который читает Intent, воспроизводит переданные серверные ссылки и отправляет фактические события просмотра через Android Broadcast.
```

Он не заменяет Lampac, TorrServer или Lampa. Он должен быть аккуратным исполнительным слоем между серверной ссылкой и системой, которая хранит историю просмотра.

## License

GPL-3.0. Подробности см. в файле `LICENSE`.
See the [LICENSE](LICENSE) file for details.
