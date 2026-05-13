 
<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="128" height="128" alt="DDD Player Logo"/>

  <h1>DDD Player 2</h1>

  <p>
    <a href="LICENSE">
      <img src="https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square" alt="License GPL v3"/>
    </a>
    <a href="README.md">
      <img src="https://img.shields.io/badge/Lang-English-blue.svg?style=flat-square" alt="Read in English"/>
    </a>
  </p>

  <p>
    <b>Android-плеер на базе Media3 / ExoPlayer с bridge-интеграцией для Lampa</b>
    <br>
    <i>Android 6.0 / API 23 и выше</i>
  </p>
</div>

---

# DDD Player 2

**DDD Player 2** — внешний Android-видеоплеер на базе **Media3 / ExoPlayer**.

Плеер запускается через `Intent.ACTION_VIEW`, получает ссылку на видео или плейлист, открывает переданный поток и отдаёт наружу события просмотра.

Основной сценарий — интеграция с **Lampa**, **Lampac**, **TorrServer** или собственным Android-клиентом.

DDD Player 2 не ищет фильмы, не хранит каталог и не заменяет Lampac / TorrServer. Он работает как исполнительный слой:

```text
получил ссылку → открыл видео → воспроизвёл → отдал события просмотра
````

---

# Главная идея

DDD Player 2 состоит из двух основных частей:

1. **Плеер** — открывает видео через ExoPlayer.
2. **Bridge** — отдаёт внешнему клиенту состояние просмотра.

Bridge может работать через:

* Android Broadcast;
* локальный HTTP-сервер внутри самого плеера;
* оба транспорта одновременно.

Для web-версии Lampa важен именно локальный сервер. Плеер сам поднимает сервер на `127.0.0.1`, а плагин Lampa забирает оттуда состояние и события.

---

# Архитектура

Общая схема работы:

```text
Lampac / TorrServer / другой backend
        ↓
готовая ссылка или список ссылок
        ↓
Lampa / JS-плагин / Android-клиент
        ↓
Intent ACTION_VIEW + extras или URI fragment
        ↓
DDD Player 2
        ↓
IntentUtils
        ↓
PlayerActivity
        ↓
PlayerViewModel
        ↓
Media3 / ExoPlayer
        ↓
BridgeDispatcher
        ↓
BroadcastTransport / LocalStoreTransport / CompositeTransport
        ↓
Android Broadcast или LocalBridgeStore
        ↓
LocalBridgeServer на 127.0.0.1
        ↓
плагин Lampa читает /state или /events
        ↓
Lampa сохраняет прогресс, серию и историю
```

---

# Что делает DDD Player 2

Плеер умеет:

* запускаться через `Intent.ACTION_VIEW`;
* читать одиночное видео из `intent.data`;
* читать плейлист из `video_list`;
* принимать названия, имена файлов, постеры, субтитры и HTTP-заголовки через extras;
* начинать просмотр с позиции `position`;
* возвращать финальную позицию через `setResult`, если передан `return_result = true`;
* включать bridge через `bridge_*` extras;
* включать bridge через URI fragment-параметры `ddd_*`;
* отправлять bridge-события через Android Broadcast;
* сохранять bridge-события во внутренний `LocalBridgeStore`;
* поднимать локальный HTTP-сервер;
* отдавать состояние и события через `/ping`, `/state`, `/events`;
* передавать события play / pause, буферизации, позиции, перемотки, смены серии, завершения и ошибки.

`applicationId`:

```text
top.rootu.dddplayer
```

---
Важно: локальный HTTP-сервер в DDD Player 2 — это не сервер видео. Он не раздаёт фильмы и не проксирует поток. Он нужен только для bridge-состояния и событий просмотра.
---

# Запуск видео

Минимальный запуск одиночного видео:

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

| Extra                        | Тип                       | Назначение                          |
| ---------------------------- | ------------------------- | ----------------------------------- |
| `title`                      | `String`                  | Название видео                      |
| `android.intent.extra.TITLE` | `String`                  | Альтернативное поле названия        |
| `filename`                   | `String`                  | Резервное имя файла                 |
| `thumbnail`                  | `String`                  | URL постера или превью              |
| `position`                   | `Int` / `Long` / `String` | Стартовая позиция в миллисекундах   |
| `headers`                    | `String[]`                | HTTP-заголовки                      |
| `return_result`              | `Boolean`                 | Вернуть позицию при закрытии плеера |

Для постера используется `thumbnail`.

---

# HTTP-заголовки

Заголовки передаются через extra `headers`.

Формат — плоский массив строк:

```text
["Key1", "Value1", "Key2", "Value2"]
```

Пример:

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

Заголовки применяются к сетевым запросам ExoPlayer.

Для плейлиста `headers` сейчас являются общими для элементов списка.

---

# Плейлист

Плейлист передаётся через extra `video_list`.

Пример:

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

val startIndex = 1
val startUri = videoUris[startIndex]

val intent = Intent(Intent.ACTION_VIEW)

intent.setDataAndType(startUri, "video/*")

intent.putExtra("video_list", videoUris)
intent.putExtra("video_list.name", titles)
intent.putExtra("video_list.thumbnail", thumbnails)
intent.putExtra("start_index", startIndex)
intent.putExtra("position", 300_000L)

startActivity(intent)
```

Поддерживаемые extras для плейлиста:

| Extra                  | Тип                                       | Назначение                       |
| ---------------------- | ----------------------------------------- | -------------------------------- |
| `video_list`           | `Parcelable[]` / `ArrayList` / строки URI | Список видео                     |
| `video_list.name`      | `String[]` / `ArrayList<String>`          | Названия элементов               |
| `video_list.filename`  | `String[]` / `ArrayList<String>`          | Имена файлов                     |
| `video_list.thumbnail` | `String[]` / `ArrayList<String>`          | Постеры элементов                |
| `video_list.subtitles` | `ArrayList<Bundle>`                       | Субтитры для элементов плейлиста |
| `start_index`          | `Int`                                     | Индекс стартового элемента       |
| `position`             | `Int` / `Long` / `String`                 | Стартовая позиция                |
| `headers`              | `String[]`                                | Общие HTTP-заголовки             |

Для сериалов желательно всегда передавать:

* полный `video_list`;
* корректный `start_index`;
* `intent.data`, совпадающий со стартовой серией;
* `position` для стартовой серии;
* понятные `video_list.name`;
* стабильный `bridge_session_id`.

---

# Важный нюанс по `position`

Для плейлиста стартовая позиция применяется к элементу, URI которого совпадает с `intent.data`.

Практически это значит:

```text
intent.data должен указывать на ту же серию, к которой относится position
```

Рекомендуемый запуск серии:

```kotlin
val startIndex = 1
val startUri = videoUris[startIndex]

intent.setDataAndType(startUri, "video/*")
intent.putExtra("video_list", videoUris)
intent.putExtra("start_index", startIndex)
intent.putExtra("position", 180_000L)
```

Если bridge-параметры передаются через URI fragment, нужно учитывать, что fragment удаляется перед передачей URI в ExoPlayer. Для плейлистов надёжнее передавать bridge-параметры через extras, а не добавлять fragment к URI элемента плейлиста.

---

# Субтитры

## Субтитры для одиночного видео

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

| Extra           | Тип                          | Назначение             |
| --------------- | ---------------------------- | ---------------------- |
| `subs`          | `Parcelable[]` / URI strings | Ссылки на субтитры     |
| `subs.name`     | `String[]`                   | Названия дорожек       |
| `subs.filename` | `String[]`                   | Имена файлов субтитров |

## Субтитры для плейлиста

Для плейлиста используется `video_list.subtitles`.

Это `ArrayList<Bundle>`, где каждый `Bundle` соответствует элементу плейлиста с тем же индексом.

Структура `Bundle`:

| Ключ            | Тип                          | Назначение             |
| --------------- | ---------------------------- | ---------------------- |
| `uris`          | `Parcelable[]` / URI strings | Ссылки на субтитры     |
| `names`         | `String[]`                   | Названия дорожек       |
| `uris.filename` | `String[]`                   | Имена файлов субтитров |

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

---

# Возврат результата через `setResult`

Если передать:

```kotlin
intent.putExtra("return_result", true)
```

то при закрытии плеера будет вызван:

```kotlin
setResult(RESULT_OK, resultIntent)
```

Возвращаемые данные:

| Поле                | Тип      | Назначение                    |
| ------------------- | -------- | ----------------------------- |
| `resultIntent.data` | `Uri`    | URI текущего видео            |
| `position`          | `Long?`  | Текущая или финальная позиция |
| `duration`          | `Long?`  | Длительность                  |
| `end_by`            | `String` | Причина завершения            |

Значения `end_by`:

| Значение     | Смысл                     |
| ------------ | ------------------------- |
| `user`       | Пользователь закрыл плеер |
| `completion` | Видео завершилось само    |

`setResult` подходит для простых Android-интеграций.

Для Lampa, сериалов и регулярной синхронизации прогресса лучше использовать bridge.

---

# Bridge

Bridge — слой обратной связи от DDD Player 2 к внешнему клиенту.

Через bridge плеер сообщает:

* старт сессии;
* play / pause;
* буферизацию;
* текущую позицию;
* перемотку;
* смену элемента плейлиста;
* окончание видео;
* закрытие плеера;
* ошибку воспроизведения.

Bridge-события формируются в `PlayerViewModel` и отправляются через `BridgeDispatcher`.

Дальше событие уходит в один из транспортов:

| Транспорт             | Назначение                                       |
| --------------------- | ------------------------------------------------ |
| `BroadcastTransport`  | Отправляет событие через Android Broadcast       |
| `LocalBridgeTransport` | Кладёт событие во внутренний `LocalBridgeStore`  |
| `CompositeTransport`  | Отправляет событие сразу в несколько транспортов |

---

# Режимы bridge

Поддерживаемые режимы:

| Режим       | Что делает                                                                     |
| ----------- | ------------------------------------------------------------------------------ |
| `broadcast` | Отправляет события через Android Broadcast                                     |
| `local`     | Сохраняет события в `LocalBridgeStore` и отдаёт их через локальный HTTP-сервер |
| `both`      | Одновременно использует Broadcast и локальный store                            |

Рекомендуемые режимы:

| Сценарий                | Режим       |
| ----------------------- | ----------- |
| Нативный Android-клиент | `broadcast` |
| Web-Lampa / JS-плагин   | `local`     |
| Нужны оба канала сразу  | `both`      |

---

# Включение bridge через extras

Пример режима `local`:

```kotlin
intent.putExtra("bridge_enabled", true)
intent.putExtra("bridge_session_id", "lampa-session-001")
intent.putExtra("bridge_client", "lampa")
intent.putExtra("bridge_mode", "local")
intent.putExtra("bridge_emit_position", true)
intent.putExtra("bridge_emit_user_actions", true)
intent.putExtra("bridge_position_interval_ms", 1000L)
intent.putExtra("bridge_schema_version", 1)
intent.putExtra("bridge_local_token", "secret-token")
```

Пример режима `both`:

```kotlin
intent.putExtra("bridge_enabled", true)
intent.putExtra("bridge_session_id", "lampa-session-001")
intent.putExtra("bridge_client", "lampa")
intent.putExtra("bridge_mode", "both")
intent.putExtra("bridge_emit_position", true)
intent.putExtra("bridge_position_interval_ms", 1000L)
intent.putExtra("bridge_event_action", "top.rootu.dddplayer.bridge.EVENT")
intent.putExtra("bridge_schema_version", 1)
```

Поддерживаемые bridge extras:

| Extra                         | Тип       | По умолчанию                       | Назначение                                                 |
| ----------------------------- | --------- | ---------------------------------- | ---------------------------------------------------------- |
| `bridge_enabled`              | `Boolean` | `false`                            | Включить bridge                                            |
| `bridge_session_id`           | `String`  | `null`                             | ID текущей сессии                                          |
| `bridge_mode`                 | `String`  | `broadcast`                        | `broadcast`, `local` или `both`                            |
| `bridge_emit_position`        | `Boolean` | `true`                             | Отправлять периодические события позиции                   |
| `bridge_emit_user_actions`    | `Boolean` | `true`                             | Отправлять пользовательские действия, если они реализованы |
| `bridge_position_interval_ms` | `Long`    | `1000`                             | Интервал отправки позиции                                  |
| `bridge_client`               | `String`  | `lampa`                            | Имя клиента                                                |
| `bridge_event_action`         | `String`  | `top.rootu.dddplayer.bridge.EVENT` | Action для Broadcast                                       |
| `bridge_receiver_package`     | `String`  | `null`                             | Ограничить Broadcast конкретным package                    |
| `bridge_schema_version`       | `Int`     | `1`                                | Версия схемы события                                       |
| `bridge_local_token`          | `String`  | `null`                             | Токен для локального HTTP-сервера                          |

Минимальный интервал `bridge_position_interval_ms` ограничивается значением `250` мс.

---

# Включение bridge через URI fragment

Bridge можно включить через fragment-параметры в URI.

Это удобно для web-интеграций, где проще сформировать одну ссылку.

Пример:

```text
https://example.com/video.m3u8#ddd_mode=local&ddd_sid=lampa-session-001&ddd_port=39677&ddd_token=secret-token&ddd_client=lampa
```

Поддерживаемые fragment-параметры:

| Параметр     | Назначение                                 |
| ------------ | ------------------------------------------ |
| `ddd_mode`   | Режим bridge: `broadcast`, `local`, `both` |
| `ddd_sid`    | ID сессии                                  |
| `ddd_port`   | Порт локального HTTP-сервера               |
| `ddd_token`  | Токен доступа к локальному серверу         |
| `ddd_client` | Имя клиента                                |

Если в URI есть `ddd_mode`, `ddd_sid`, `ddd_port` или `ddd_token`, bridge считается включённым автоматически.

Fragment используется только для настройки bridge. Перед передачей ссылки в ExoPlayer fragment удаляется.

---

# Локальный HTTP-сервер

В режимах `local` и `both` DDD Player 2 сам запускает локальный HTTP-сервер.

По умолчанию:

```text
http://127.0.0.1:39677
```

Сервер слушает только `127.0.0.1`.

Он предназначен для локальной связи между плеером и плагином Lampa.

Локальный сервер:

* не раздаёт видео;
* не проксирует поток;
* не заменяет Lampac;
* не заменяет TorrServer;
* не хранит долговременную историю;
* отдаёт только bridge-состояние и события.

---

# Endpoints локального сервера

## `/ping`

Проверяет, что сервер запущен.

```text
GET http://127.0.0.1:39677/ping
```

Пример ответа:

```json
{
  "ok": true,
  "service": "dddplayer-local-bridge"
}
```

## `/state`

Возвращает последнее состояние указанной сессии.

```text
GET http://127.0.0.1:39677/state?sid=lampa-session-001
```

Если задан токен:

```text
GET http://127.0.0.1:39677/state?sid=lampa-session-001&token=secret-token
```

## `/events`

Возвращает список последних событий указанной сессии.

```text
GET http://127.0.0.1:39677/events?sid=lampa-session-001
```

Если задан токен:

```text
GET http://127.0.0.1:39677/events?sid=lampa-session-001&token=secret-token
```

---

# Авторизация локального сервера

Если `bridge_local_token` или `ddd_token` не задан, endpoints `/state` и `/events` доступны без токена.

Если токен задан, его нужно передавать в query-параметре:

```text
token=secret-token
```

Без правильного токена сервер вернёт:

```json
{
  "error": "forbidden"
}
```

---

# Хранение событий

В режиме `local` события кладутся во внутренний `LocalBridgeStore`.

Хранилище работает по `session_id`.

Для каждой сессии хранится:

* последнее состояние;
* список последних событий.

Список событий ограничен последними 200 событиями.

Это оперативное in-memory-хранилище. Оно нужно для связи с плагином, а не для долговременного хранения истории.

Историю просмотра должна сохранять Lampa, плагин, Lampac или другой внешний слой.

---

# JSON-обёртка события

Каждое bridge-событие упаковано в envelope:

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

Поля envelope:

| Поле        | Тип       | Назначение                               |
| ----------- | --------- | ---------------------------------------- |
| `schema`    | `Int`     | Версия схемы                             |
| `type`      | `String`  | Тип события                              |
| `client`    | `String`  | Клиент из `bridge_client` / `ddd_client` |
| `sessionId` | `String?` | ID сессии                                |
| `ts`        | `Long`    | Время события                            |
| `payload`   | `Object`  | Данные события                           |

---

# Android Broadcast

В режиме `broadcast` событие отправляется через Android Broadcast.

Action по умолчанию:

```text
top.rootu.dddplayer.bridge.EVENT
```

Broadcast extras:

| Extra        | Тип       | Назначение           |
| ------------ | --------- | -------------------- |
| `schema`     | `Int`     | Версия схемы         |
| `client`     | `String`  | Имя клиента          |
| `session_id` | `String?` | ID сессии            |
| `event_type` | `String`  | Тип события          |
| `event_json` | `String`  | Полный JSON envelope |

Пример чтения:

```kotlin
val eventType = intent.getStringExtra("event_type")
val eventJson = intent.getStringExtra("event_json")
val sessionId = intent.getStringExtra("session_id")
```

---

# Bridge-события

## `SessionStarted`

Отправляется после загрузки непустого плейлиста.

Payload:

| Поле            | Тип                | Назначение            |
| --------------- | ------------------ | --------------------- |
| `sessionId`     | `String?`          | ID сессии             |
| `ts`            | `Long`             | Время события         |
| `uri`           | `String?`          | URI текущего элемента |
| `title`         | `String?`          | Название              |
| `playlistSize`  | `Int`              | Размер плейлиста      |
| `startIndex`    | `Int`              | Стартовый индекс      |
| `startPosition` | `Long?`            | Стартовая позиция     |
| `currentItem`   | `BridgeMediaItem?` | Текущий элемент       |

---

## `PlaybackStateChanged`

Отправляется при изменении состояния воспроизведения.

Это событие покрывает:

* старт воспроизведения;
* паузу;
* продолжение после паузы;
* буферизацию;
* выход из буферизации;
* изменение `isPlaying`.

При паузе приходит событие с:

```json
{
  "type": "PlaybackStateChanged",
  "payload": {
    "isPlaying": false
  }
}
```

При продолжении воспроизведения приходит событие с:

```json
{
  "type": "PlaybackStateChanged",
  "payload": {
    "isPlaying": true
  }
}
```

Payload:

| Поле          | Тип       | Назначение                 |
| ------------- | --------- | -------------------------- |
| `isPlaying`   | `Boolean` | Идёт ли воспроизведение    |
| `isBuffering` | `Boolean` | Идёт ли буферизация        |
| `position`    | `Long?`   | Текущая позиция            |
| `duration`    | `Long?`   | Длительность               |
| `windowIndex` | `Int?`    | Индекс элемента плейлиста  |
| `title`       | `String?` | Название текущего элемента |

Для истории просмотра это важное событие. Принимающий слой может сохранять позицию сразу при паузе, не дожидаясь следующего `PositionTick`.

---

## `PositionTick`

Периодическое событие позиции.

Отправляется, если включено:

```text
bridge_emit_position = true
```

Payload:

| Поле                 | Тип       | Назначение                 |
| -------------------- | --------- | -------------------------- |
| `position`           | `Long?`   | Текущая позиция            |
| `duration`           | `Long?`   | Длительность               |
| `bufferedPosition`   | `Long?`   | Позиция буфера             |
| `bufferedPercentage` | `Int?`    | Процент буфера             |
| `windowIndex`        | `Int?`    | Индекс элемента плейлиста  |
| `title`              | `String?` | Название текущего элемента |

`PositionTick` используется для регулярной синхронизации прогресса.

---

## `SeekCompleted`

Отправляется после перемотки.

Payload:

| Поле           | Тип     | Назначение                |
| -------------- | ------- | ------------------------- |
| `fromPosition` | `Long?` | Позиция до перемотки      |
| `toPosition`   | `Long?` | Позиция после перемотки   |
| `windowIndex`  | `Int?`  | Индекс элемента плейлиста |

---

## `PlaylistItemChanged`

Отправляется при смене элемента плейлиста.

Payload:

| Поле           | Тип                | Назначение                     |
| -------------- | ------------------ | ------------------------------ |
| `uri`          | `String?`          | URI нового элемента            |
| `windowIndex`  | `Int`              | Индекс нового элемента         |
| `playlistSize` | `Int`              | Размер плейлиста               |
| `title`        | `String?`          | Название нового элемента       |
| `reason`       | `String`           | Причина перехода               |
| `position`     | `Long?`            | Текущая позиция                |
| `duration`     | `Long?`            | Длительность                   |
| `hasPrevious`  | `Boolean`          | Есть ли предыдущий элемент     |
| `hasNext`      | `Boolean`          | Есть ли следующий элемент      |
| `currentItem`  | `BridgeMediaItem?` | Текущий элемент, если заполнен |

Это главное событие для сериалов. По нему плагин понимает, что пользователь перешёл на другую серию внутри плеера.

---

## `PlaybackEnded`

Отправляется при достижении конца текущего элемента.

Payload:

| Поле           | Тип       | Назначение        |
| -------------- | --------- | ----------------- |
| `windowIndex`  | `Int`     | Индекс элемента   |
| `playlistSize` | `Int`     | Размер плейлиста  |
| `title`        | `String?` | Название          |
| `position`     | `Long?`   | Финальная позиция |
| `duration`     | `Long?`   | Длительность      |

---

## `SessionFinished`

Отправляется при закрытии `PlayerActivity`.

Payload:

| Поле           | Тип       | Назначение            |
| -------------- | --------- | --------------------- |
| `uri`          | `String?` | URI текущего элемента |
| `position`     | `Long?`   | Финальная позиция     |
| `duration`     | `Long?`   | Длительность          |
| `endBy`        | `String`  | Причина завершения    |
| `windowIndex`  | `Int?`    | Индекс элемента       |
| `playlistSize` | `Int?`    | Размер плейлиста      |
| `title`        | `String?` | Название              |

Возможные значения `endBy`:

| Значение     | Смысл                     |
| ------------ | ------------------------- |
| `user`       | Пользователь закрыл плеер |
| `completion` | Видео завершилось само    |

После закрытия плеера локальный сервер не останавливается мгновенно. Он остаётся доступен короткое время, чтобы плагин успел забрать финальное состояние.

---

## `Error`

Отправляется при ошибке воспроизведения, если ошибка не была восстановлена автоматически.

Payload:

| Поле          | Тип       | Назначение           |
| ------------- | --------- | -------------------- |
| `code`        | `String?` | Код ошибки ExoPlayer |
| `message`     | `String?` | Сообщение ошибки     |
| `windowIndex` | `Int?`    | Индекс элемента      |

---

## `UserAction`

Модель события есть в коде.

Документировать конкретные пользовательские действия как публичный API нужно только после того, как они будут стабильно отправляться из интерфейса.

---

# Как плагину Lampa обрабатывать события

Плагин должен:

1. Получить ссылку или плейлист от Lampa / Lampac / TorrServer.
2. Сформировать Intent для DDD Player 2.
3. Передать `bridge_session_id`.
4. Включить bridge в режиме `local` или `both`.
5. Запустить плеер.
6. Читать `/state` или `/events` с локального сервера.
7. Обновлять прогресс, активную серию и историю просмотра.

Пример чтения событий:

```text
GET http://127.0.0.1:39677/events?sid=lampa-card-123-s01e02
```

Пример чтения последнего состояния:

```text
GET http://127.0.0.1:39677/state?sid=lampa-card-123-s01e02
```

Рекомендуемая логика обработки:

| Событие                | Что делать плагину                                    |
| ---------------------- | ----------------------------------------------------- |
| `SessionStarted`       | Создать или обновить активную сессию                  |
| `PlaybackStateChanged` | Обновить play/pause/buffering и сохранить позицию     |
| `PositionTick`         | Регулярно обновлять прогресс                          |
| `SeekCompleted`        | Сохранить новую позицию после перемотки               |
| `PlaylistItemChanged`  | Переключить активную серию по `windowIndex` или `uri` |
| `PlaybackEnded`        | Отметить серию или видео как завершённые              |
| `SessionFinished`      | Финально сохранить состояние просмотра                |
| `Error`                | Показать ошибку или сохранить диагностику             |

---

# Пример запуска серии с локальным bridge

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
intent.putExtra("bridge_mode", "local")
intent.putExtra("bridge_emit_position", true)
intent.putExtra("bridge_position_interval_ms", 1000L)
intent.putExtra("bridge_schema_version", 1)

startActivity(intent)
```

После запуска плагин может читать:

```text
http://127.0.0.1:39677/state?sid=lampa-card-123-s01e02
```

и:

```text
http://127.0.0.1:39677/events?sid=lampa-card-123-s01e02
```

---

# Сопоставление серий

Сейчас bridge отдаёт:

* `uri`;
* `windowIndex`;
* `title`.

Этого достаточно для базового сопоставления, если плейлист стабилен.

Рекомендуемая стратегия:

| Поле          | Как использовать                             |
| ------------- | -------------------------------------------- |
| `sessionId`   | Привязка к карточке / сезону / серии в Lampa |
| `windowIndex` | Индекс серии внутри переданного плейлиста    |
| `uri`         | Проверка фактического элемента               |
| `title`       | Отладка и отображение                        |

Для более надёжного сопоставления в будущем можно использовать внешние идентификаторы:

| Поле         | Назначение          |
| ------------ | ------------------- |
| `externalId` | Внешний ID элемента |
| `season`     | Номер сезона        |
| `episode`    | Номер серии         |
| `source`     | Источник            |

Эти поля есть в модели `BridgeMediaItem`, но если Intent-парсер их ещё не принимает как отдельные extras, не стоит описывать их как стабильный публичный API.

---

# Рекомендуемый `session_id`

`bridge_session_id` должен быть стабильным для конкретного просмотра.

Для фильма:

```text
lampa:movie:{card_id}:{source_id}
```

Для серии:

```text
lampa:series:{card_id}:s{season}:e{episode}:{source_id}
```

Пример:

```text
lampa:series:12345:s1:e2:lampac
```

Главное требование — плагин должен знать, какой `session_id` он передал, чтобы потом читать:

```text
/state?sid=...
/events?sid=...
```

---

# Диагностика

Проверить запуск локального сервера:

```text
http://127.0.0.1:39677/ping
```

Проверить события:

```text
http://127.0.0.1:39677/events?sid=YOUR_SESSION_ID
```

Проверить последнее состояние:

```text
http://127.0.0.1:39677/state?sid=YOUR_SESSION_ID
```

Смотреть логи:

```bash
adb logcat | grep -i DDDPlayer
```

Для bridge полезно проверять:

1. Передан ли `bridge_enabled = true`.
2. Правильно ли указан `bridge_mode`.
3. Есть ли стабильный `bridge_session_id`.
4. Совпадает ли `sid` в запросе `/state` или `/events`.
5. Не требуется ли `token`.
6. Совпадает ли порт из `ddd_port` с тем, куда обращается плагин.
7. Не конфликтует ли порт `39677` с другим процессом.
8. Передаётся ли `intent.data`.
9. Для плейлиста передан ли корректный `start_index`.
10. Для плейлиста совпадает ли стартовая серия с `intent.data`.

---

# Проверка через adb

Одиночное видео с Broadcast bridge:

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

Одиночное видео с local bridge через URI fragment:

```bash
adb shell am start \
  -a android.intent.action.VIEW \
  -d "https://example.com/video.mp4#ddd_mode=local&ddd_sid=adb-local-session&ddd_port=39677&ddd_client=lampa" \
  -t "video/*" \
  --es title "Test local bridge" \
  --el position 120000
```

После запуска проверить:

```bash
adb shell "curl http://127.0.0.1:39677/ping"
```

и:

```bash
adb shell "curl http://127.0.0.1:39677/events?sid=adb-local-session"
```

Если на устройстве нет `curl`, endpoint можно проверять из самого плагина Lampa или через другой доступный HTTP-клиент на устройстве.

---

# Сборка

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

---

# Границы ответственности

| Компонент                     | Ответственность                                               |
| ----------------------------- | ------------------------------------------------------------- |
| Lampac / TorrServer / backend | Подготовить прямую ссылку или список ссылок                   |
| Lampa / JS-плагин             | Выбрать контент, источник, серию, позицию и запустить плеер   |
| DDD Player 2                  | Принять Intent, воспроизвести видео, отправить bridge-события |
| LocalBridgeServer             | Отдать состояние и события плагину через localhost            |
| Lampa / плагин / backend      | Сохранить историю просмотра                                   |

DDD Player 2 не решает, как именно Lampa должна хранить историю. Он отдаёт фактические события просмотра. Внешний слой принимает решение, что считать просмотренным, куда сохранять позицию и как сопоставлять серии.

---

# License

GPL-3.0.

Подробности см. в файле [LICENSE](LICENSE).

```

Главная правка по смыслу: **локальный сервер — часть плеера**, а не внешний companion. Внешний слой здесь только плагин Lampa, который запускает плеер и читает `/state` / `/events` с `127.0.0.1`.
::contentReference[oaicite:1]{index=1}
```

[1]: https://github.com/ARST113/dddplayer2 "GitHub - ARST113/dddplayer2: Try full js · GitHub"
