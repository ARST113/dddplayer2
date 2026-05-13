 
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

**DDD Player 2** — внешний Android-видеоплеер на базе **Media3 / ExoPlayer** с bridge-слоем для передачи состояния воспроизведения внешнему клиенту.

Основной сценарий использования — запуск плеера из **Lampa**, **Lampac**, **TorrServer** или другого Android/web-клиента, который уже подготовил ссылку на видео или плейлист.

Плеер не является каталогом фильмов, не ищет торренты, не заменяет Lampac / TorrServer и не хранит долговременную историю просмотра. Его роль ограничена воспроизведением переданного потока и отдачей событий просмотра наружу.

```text
получил video URI или playlist
→ открыл поток через ExoPlayer
→ воспроизвёл
→ отдал позицию, состояние, переходы и завершение через bridge
```

## Текущий статус

На текущий момент в проекте реализованы:

* запуск через `Intent.ACTION_VIEW`;
* одиночное видео через `intent.data`;
* внутренний playlist через extra `video_list`;
* стартовая позиция `position`;
* общие HTTP-заголовки через extra `headers`;
* субтитры для одиночного видео и элементов плейлиста;
* возврат результата через `setResult`, если передан `return_result = true`;
* bridge через Android Broadcast;
* bridge через локальный in-memory store;
* локальный HTTP-сервер на `127.0.0.1`;
* endpoints `/ping`, `/state`, `/events`;
* режимы bridge `broadcast`, `local`, `both`;
* включение bridge через extras `bridge_*`;
* включение bridge через URI fragment-параметры `ddd_*`.

## Требования и идентификаторы

| Параметр                   | Значение                           |
| -------------------------- | ---------------------------------- |
| Package / applicationId    | `top.rootu.dddplayer`              |
| Минимальная версия Android | Android 6.0 / API 23               |
| Playback engine            | Media3 / ExoPlayer                 |
| Основной Activity          | `PlayerActivity`                   |
| Основной запуск            | `Intent.ACTION_VIEW`               |
| Default bridge action      | `top.rootu.dddplayer.bridge.EVENT` |
| Default local bridge host  | `127.0.0.1`                        |
| Default local bridge port  | `39677`                            |
| Default bridge client      | `lampa`                            |
| Default bridge schema      | `1`                                |

## Что не делает DDD Player 2

DDD Player 2 не выполняет задачи backend-слоя:

* не ищет фильмы и сериалы;
* не работает как каталог;
* не парсит торрент-трекеры;
* не поднимает видеопрокси;
* не раздаёт видео через локальный HTTP-сервер;
* не заменяет TorrServer;
* не заменяет Lampac;
* не является постоянным хранилищем истории просмотра.

Локальный HTTP-сервер внутри DDD Player 2 нужен только для bridge-состояния и bridge-событий.

## Архитектура

Упрощённая схема:

```text
Lampa / Lampac / TorrServer / Android-клиент
        ↓
Intent.ACTION_VIEW + extras или URI fragment
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
BroadcastTransport / LocalBridgeTransport / CompositeTransport
        ↓
Android Broadcast или LocalBridgeStore
        ↓
LocalBridgeServer 127.0.0.1
        ↓
внешний клиент читает /state и /events
```

Ключевые классы:

| Класс                  | Назначение                                                                    |
| ---------------------- | ----------------------------------------------------------------------------- |
| `IntentUtils`          | Парсит `Intent`, playlist, subtitles, headers и bridge-конфигурацию           |
| `PlayerActivity`       | Принимает intent, инициализирует playlist, bridge и player UI                 |
| `PlayerViewModel`      | Управляет ExoPlayer, состоянием воспроизведения и эмиссией bridge-событий     |
| `BridgeConfig`         | Конфигурация bridge-сессии                                                    |
| `BridgeDispatcher`     | Единая точка отправки bridge-событий                                          |
| `BroadcastTransport`   | Отправляет события через Android Broadcast                                    |
| `LocalBridgeTransport` | Кладёт события в `LocalBridgeStore`                                           |
| `CompositeTransport`   | Отправляет событие сразу в несколько transport-реализаций                     |
| `LocalBridgeStore`     | In-memory store последних событий и состояния по session id                   |
| `LocalBridgeServer`    | Локальный HTTP-сервер для `/ping`, `/state`, `/events`                        |
| `LocalBridgeManager`   | Управляет запуском, переиспользованием и остановкой локального bridge-сервера |

## Запуск одиночного видео

Минимальный пример:

```kotlin
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(
        Uri.parse("https://example.com/video.mp4"),
        "video/*"
    )
    putExtra("title", "Название видео")
    putExtra("thumbnail", "https://example.com/poster.jpg")
    putExtra("position", 120_000L)
}

startActivity(intent)
```

Поддерживаемые extras для одиночного видео:

| Extra                        | Тип                                                 | Назначение                                     |
| ---------------------------- | --------------------------------------------------- | ---------------------------------------------- |
| `title`                      | `String`                                            | Название видео                                 |
| `android.intent.extra.TITLE` | `String`                                            | Альтернативное поле названия                   |
| `thumbnail`                  | `String`                                            | URL постера / превью                           |
| `position`                   | `Long` / `Int` / `String`                           | Стартовая позиция в миллисекундах              |
| `headers`                    | `String[]` / `ArrayList<String>` / `CharSequence[]` | HTTP-заголовки                                 |
| `return_result`              | `Boolean`                                           | Вернуть позицию через `setResult` при закрытии |

### Важный нюанс по `filename` одиночного видео

Для одиночного видео extra `filename` сейчас не читается как отдельное публичное поле. Имя файла определяется из `content://` URI через `OpenableColumns.DISPLAY_NAME` или берётся из `uri.lastPathSegment`.

Если нужно передать стабильные имена файлов для серий, используйте playlist-режим и extra `video_list.filename`.

## HTTP-заголовки

Заголовки передаются через extra `headers` как плоский массив строк:

```text
["Key1", "Value1", "Key2", "Value2"]
```

Пример:

```kotlin
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(
        Uri.parse("https://example.com/video.m3u8"),
        "video/*"
    )

    putExtra("headers", arrayOf(
        "User-Agent", "DDDPlayer2",
        "Referer", "https://example.com/"
    ))
}

startActivity(intent)
```

В playlist-режиме `headers` являются общими для всех элементов списка.

## Плейлист

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

val filenames = arrayOf(
    "show.s01e01.mkv",
    "show.s01e02.mkv",
    "show.s01e03.mkv"
)

val thumbnails = arrayOf(
    "https://example.com/s01e01.jpg",
    "https://example.com/s01e02.jpg",
    "https://example.com/s01e03.jpg"
)

val startIndex = 1
val startUri = videoUris[startIndex]

val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(startUri, "video/*")
    putExtra("video_list", videoUris)
    putExtra("video_list.name", titles)
    putExtra("video_list.filename", filenames)
    putExtra("video_list.thumbnail", thumbnails)
    putExtra("start_index", startIndex)
    putExtra("position", 300_000L)
}

startActivity(intent)
```

Поддерживаемые extras для playlist-режима:

| Extra                  | Тип                                                     | Назначение                        |
| ---------------------- | ------------------------------------------------------- | --------------------------------- |
| `video_list`           | `Parcelable[]` / `ArrayList<Uri>` / `ArrayList<String>` | Список URI видео                  |
| `video_list.name`      | `String[]` / `ArrayList<String>` / `CharSequence[]`     | Названия элементов                |
| `video_list.filename`  | `String[]` / `ArrayList<String>` / `CharSequence[]`     | Имена файлов элементов            |
| `video_list.thumbnail` | `String[]` / `ArrayList<String>` / `CharSequence[]`     | Постеры элементов                 |
| `video_list.subtitles` | `ArrayList<Bundle>`                                     | Субтитры для элементов плейлиста  |
| `start_index`          | `Int`                                                   | Индекс стартового элемента        |
| `position`             | `Long` / `Int` / `String`                               | Стартовая позиция в миллисекундах |
| `headers`              | `String[]` / `ArrayList<String>` / `CharSequence[]`     | Общие HTTP-заголовки              |

Для сериалов и anime-case желательно передавать:

* полный `video_list`;
* корректный `start_index`;
* `intent.data`, совпадающий с URI стартовой серии;
* `position`, относящийся именно к стартовой серии;
* понятные `video_list.name`;
* стабильные `video_list.filename`;
* стабильный `bridge_session_id` / `ddd_sid`.

## Правило применения `position` в playlist-режиме

В playlist-режиме стартовая позиция применяется к элементу, URI которого совпадает с `intent.data` после удаления URI fragment.

Практически это значит:

```text
intent.data должен указывать на ту же серию, к которой относится position
```

Правильный запуск второй серии с позиции:

```kotlin
val startIndex = 1
val startUri = videoUris[startIndex]

val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(startUri, "video/*")
    putExtra("video_list", videoUris)
    putExtra("start_index", startIndex)
    putExtra("position", 180_000L)
}
```

Если bridge-параметры передаются через URI fragment, fragment будет удалён перед передачей URI в ExoPlayer.

Для playlist-режима надёжнее передавать bridge-конфигурацию через extras, а не добавлять fragment к URI элемента плейлиста.

## Субтитры

### Субтитры для одиночного видео

```kotlin
val subUris = arrayListOf(
    Uri.parse("https://example.com/sub_ru.srt"),
    Uri.parse("https://example.com/sub_en.srt")
)

val subNames = arrayOf("Русский", "English")

intent.putParcelableArrayListExtra("subs", subUris)
intent.putExtra("subs.name", subNames)
```

Поддерживаемые extras:

| Extra           | Тип                                                     | Назначение             |
| --------------- | ------------------------------------------------------- | ---------------------- |
| `subs`          | `Parcelable[]` / `ArrayList<Uri>` / `ArrayList<String>` | URI субтитров          |
| `subs.name`     | `String[]` / `ArrayList<String>`                        | Названия дорожек       |
| `subs.filename` | `String[]` / `ArrayList<String>`                        | Имена файлов субтитров |

### Субтитры для плейлиста

Для playlist-режима используется extra `video_list.subtitles`.

Это `ArrayList<Bundle>`, где каждый `Bundle` соответствует элементу плейлиста с тем же индексом.

Структура `Bundle`:

| Ключ            | Тип                                                     | Назначение             |
| --------------- | ------------------------------------------------------- | ---------------------- |
| `uris`          | `Parcelable[]` / `ArrayList<Uri>` / `ArrayList<String>` | URI субтитров          |
| `names`         | `String[]` / `ArrayList<String>`                        | Названия дорожек       |
| `uris.filename` | `String[]` / `ArrayList<String>`                        | Имена файлов субтитров |

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

val playlistSubtitles = arrayListOf(subs1, subs2)
intent.putParcelableArrayListExtra("video_list.subtitles", playlistSubtitles)
```

## Возврат результата через `setResult`

Если передать:

```kotlin
intent.putExtra("return_result", true)
```

то при закрытии плеера будет вызван:

```kotlin
setResult(RESULT_OK, resultIntent)
```

Возвращаемые данные:

| Поле                | Тип      | Назначение                  |
| ------------------- | -------- | --------------------------- |
| `resultIntent.data` | `Uri?`   | URI текущего видео          |
| `position`          | `Long`   | Текущая / финальная позиция |
| `duration`          | `Long`   | Длительность                |
| `end_by`            | `String` | Причина завершения          |

Текущие значения `end_by`:

| Значение     | Смысл                     |
| ------------ | ------------------------- |
| `user_exit`  | Пользователь закрыл плеер |
| `completion` | Видео завершилось само    |

`setResult` подходит для простых Android-интеграций. Для Lampa, сериалов, регулярной синхронизации прогресса и восстановления после аварийного завершения лучше использовать bridge.

## Bridge

Bridge — слой обратной связи от DDD Player 2 к внешнему клиенту.

Через bridge плеер сообщает:

* старт сессии;
* play / pause / resume;
* буферизацию;
* текущую позицию;
* перемотку;
* смену элемента плейлиста;
* завершение видео;
* закрытие сессии;
* ошибку воспроизведения;
* изменение выбранной дорожки;
* пользовательские действия, если они эмитируются соответствующим кодом.

Bridge-события формируются в `PlayerViewModel` и отправляются через `BridgeDispatcher`.

## Режимы bridge

| Режим       | Что делает                                                                  |
| ----------- | --------------------------------------------------------------------------- |
| `broadcast` | Отправляет события через Android Broadcast                                  |
| `local`     | Кладёт события в `LocalBridgeStore` и отдаёт их через локальный HTTP-сервер |
| `both`      | Одновременно использует Broadcast и LocalBridgeStore                        |

Рекомендуемые режимы:

| Сценарий                | Режим       |
| ----------------------- | ----------- |
| Нативный Android-клиент | `broadcast` |
| Web-Lampa / JS-плагин   | `local`     |
| Нужны оба канала        | `both`      |

## Включение bridge через extras

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
intent.putExtra("bridge_local_port", 39677)
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

| Extra                         |       Тип |                       По умолчанию | Назначение                                                            |
| ----------------------------- | --------: | ---------------------------------: | --------------------------------------------------------------------- |
| `bridge_enabled`              | `Boolean` |                            `false` | Включить bridge                                                       |
| `bridge_session_id`           |  `String` |                             `null` | ID текущей сессии                                                     |
| `bridge_mode`                 |  `String` |                        `broadcast` | `broadcast`, `local`, `both`                                          |
| `bridge_emit_position`        | `Boolean` |                             `true` | Отправлять периодические события позиции                              |
| `bridge_emit_user_actions`    | `Boolean` |                             `true` | Разрешить пользовательские action-события, если они эмитируются кодом |
| `bridge_position_interval_ms` |    `Long` |                             `1000` | Интервал отправки `position_tick`                                     |
| `bridge_client`               |  `String` |                            `lampa` | Имя клиента                                                           |
| `bridge_event_action`         |  `String` | `top.rootu.dddplayer.bridge.EVENT` | Action для Android Broadcast                                          |
| `bridge_receiver_package`     |  `String` |                             `null` | Ограничить Broadcast конкретным package                               |
| `bridge_schema_version`       |     `Int` |                                `1` | Версия bridge-схемы                                                   |
| `bridge_local_port`           |     `Int` |                            `39677` | Порт локального HTTP-сервера                                          |
| `bridge_local_token`          |  `String` |                             `null` | Токен доступа к `/state` и `/events`                                  |

Минимальный фактический интервал `bridge_position_interval_ms` ограничивается значением `250` мс.

Порт local bridge ограничивается диапазоном `1024..65535`.

## Включение bridge через URI fragment

Bridge можно включить через fragment-параметры в URI. Это удобно для web-интеграций, где проще сформировать одну ссылку.

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

Если в URI есть хотя бы один из параметров `ddd_mode`, `ddd_sid`, `ddd_port`, `ddd_token`, `ddd_client`, bridge считается включённым автоматически.

Fragment используется только для настройки bridge. Перед передачей ссылки в ExoPlayer fragment удаляется.

## Приоритет bridge-настроек

Для части bridge-настроек fragment имеет приоритет над extras:

| Настройка  | Fragment     | Extra                |
| ---------- | ------------ | -------------------- |
| Режим      | `ddd_mode`   | `bridge_mode`        |
| Session id | `ddd_sid`    | `bridge_session_id`  |
| Client     | `ddd_client` | `bridge_client`      |
| Port       | `ddd_port`   | `bridge_local_port`  |
| Token      | `ddd_token`  | `bridge_local_token` |

## Локальный HTTP-сервер

В режимах `local` и `both` DDD Player 2 запускает локальный HTTP-сервер.

По умолчанию:

```text
http://127.0.0.1:39677
```

Сервер слушает только `127.0.0.1` и предназначен для локальной связи между плеером и внешним клиентом.

Локальный сервер:

* не раздаёт видео;
* не проксирует поток;
* не хранит долговременную историю;
* отдаёт только последнее состояние и последние bridge-события;
* использует in-memory store;
* поддерживает CORS-заголовок `Access-Control-Allow-Origin: *`;
* поддерживает методы `GET` и `OPTIONS`.

## HTTP API локального bridge

### `GET /ping`

Проверяет, что local bridge server запущен.

```text
GET http://127.0.0.1:39677/ping
```

Пример ответа:

```json
{
  "ok": true,
  "service": "dddplayer-local-bridge",
  "port": 39677
}
```

### `GET /state`

Возвращает последнее состояние указанной сессии.

```text
GET http://127.0.0.1:39677/state?sid=lampa-session-001
```

Если задан токен:

```text
GET http://127.0.0.1:39677/state?sid=lampa-session-001&token=secret-token
```

Пример ответа:

```json
{
  "ok": true,
  "state": {
    "sessionId": "lampa-session-001",
    "lastEvent": {
      "schema": 1,
      "type": "position_tick",
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
        "title": "Сезон 1, серия 2",
        "reason": "tick"
      }
    }
  }
}
```

Форма объекта `state` зависит от текущего состояния `LocalBridgeStore`. Внешний клиент должен быть устойчив к `null`, пустому состоянию и отсутствующим полям.

### `GET /events`

Возвращает список последних событий указанной сессии.

```text
GET http://127.0.0.1:39677/events?sid=lampa-session-001
```

Если задан токен:

```text
GET http://127.0.0.1:39677/events?sid=lampa-session-001&token=secret-token
```

Поддерживаемые query-параметры:

| Параметр | Тип      | Назначение                                     |
| -------- | -------- | ---------------------------------------------- |
| `sid`    | `String` | ID bridge-сессии                               |
| `token`  | `String` | Токен, если local bridge был запущен с токеном |
| `since`  | `Long`   | Вернуть события после указанного timestamp     |
| `limit`  | `Int`    | Ограничить количество событий                  |

Пример:

```text
GET http://127.0.0.1:39677/events?sid=lampa-session-001&since=1710000000000&limit=50
```

Пример ответа:

```json
{
  "ok": true,
  "events": [
    {
      "schema": 1,
      "type": "position_tick",
      "client": "lampa",
      "sessionId": "lampa-session-001",
      "ts": 1710000001000,
      "payload": {
        "sessionId": "lampa-session-001",
        "ts": 1710000001000,
        "uri": "https://example.com/s01e02.mp4",
        "position": 301000,
        "duration": 2700000,
        "bufferedPosition": 330000,
        "bufferedPercentage": 44,
        "windowIndex": 1,
        "title": "Сезон 1, серия 2",
        "reason": "tick"
      }
    }
  ]
}
```

### Ошибки HTTP API

Если endpoint не найден:

```json
{
  "error": "not_found"
}
```

Если метод не поддерживается:

```json
{
  "error": "method_not_allowed"
}
```

Если задан токен, но в запросе его нет или он неверный:

```json
{
  "error": "forbidden"
}
```

## Авторизация local bridge

Если `bridge_local_token` / `ddd_token` не задан, endpoints `/state` и `/events` доступны без токена.

Если токен задан, его нужно передавать в query-параметре:

```text
token=secret-token
```

Токен не применяется к `/ping`.

## Хранение событий

В режиме `local` события кладутся во внутренний `LocalBridgeStore`.

Хранилище работает по `sessionId`. Для каждой сессии хранится:

* последнее состояние;
* очередь последних событий.

По умолчанию очередь ограничена последними `200` событиями.

Это оперативное in-memory-хранилище. Оно существует для связи с внешним клиентом и не должно рассматриваться как постоянная история просмотра.

Историю, прогресс и связь с карточкой фильма / серии должен сохранять внешний слой: Lampa, plugin, Lampac или другой клиент.

## JSON envelope bridge-события

Каждое bridge-событие упаковано в envelope:

```json
{
  "schema": 1,
  "type": "position_tick",
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
    "title": "Сезон 1, серия 2",
    "reason": "tick"
  }
}
```

Поля envelope:

| Поле        | Тип       | Назначение                               |
| ----------- | --------- | ---------------------------------------- |
| `schema`    | `Int`     | Версия схемы                             |
| `type`      | `String`  | Публичный тип события в `snake_case`     |
| `client`    | `String`  | Клиент из `bridge_client` / `ddd_client` |
| `sessionId` | `String?` | ID сессии                                |
| `ts`        | `Long`    | Timestamp события                        |
| `payload`   | `Object`  | Данные события                           |

## Android Broadcast

В режиме `broadcast` событие отправляется через Android Broadcast.

Action по умолчанию:

```text
top.rootu.dddplayer.bridge.EVENT
```

Broadcast extras:

| Extra        | Тип       | Назначение                           |
| ------------ | --------- | ------------------------------------ |
| `schema`     | `Int`     | Версия схемы                         |
| `client`     | `String`  | Имя клиента                          |
| `session_id` | `String?` | ID сессии                            |
| `event_type` | `String`  | Публичный тип события в `snake_case` |
| `event_json` | `String`  | Полный JSON envelope                 |

Пример чтения:

```kotlin
val eventType = intent.getStringExtra("event_type")
val eventJson = intent.getStringExtra("event_json")
val sessionId = intent.getStringExtra("session_id")
```

Если передан `bridge_receiver_package`, broadcast ограничивается указанным package.

## Типы bridge-событий

Публичные значения `type` / `event_type` используют `snake_case`.

| Kotlin event class      | Public `type` / `event_type` |
| ----------------------- | ---------------------------- |
| `SessionStarted`        | `session_started`            |
| `PlaybackStateChanged`  | `playback_state_changed`     |
| `PositionTick`          | `position_tick`              |
| `SeekCompleted`         | `seek_completed`             |
| `PlaylistItemChanged`   | `playlist_item_changed`      |
| `PlaybackEnded`         | `playback_ended`             |
| `SessionFinished`       | `session_finished`           |
| `Error`                 | `error`                      |
| `TrackSelectionChanged` | `track_selection_changed`    |
| `UserAction`            | `user_action`                |

Нельзя ориентироваться на PascalCase-имя Kotlin-класса как на публичную строку события. Для внешнего клиента контрактом является именно `snake_case`.

## Payload событий

### `session_started`

Отправляется после загрузки непустого playlist.

Payload:

| Поле            | Тип                | Назначение            |
| --------------- | ------------------ | --------------------- |
| `sessionId`     | `String?`          | ID сессии             |
| `ts`            | `Long`             | Timestamp             |
| `uri`           | `String?`          | URI текущего элемента |
| `title`         | `String?`          | Название              |
| `playlistSize`  | `Int`              | Размер playlist       |
| `startIndex`    | `Int`              | Стартовый индекс      |
| `startPosition` | `Long?`            | Стартовая позиция     |
| `currentItem`   | `BridgeMediaItem?` | Текущий элемент       |

### `playback_state_changed`

Отправляется при изменении состояния playback.

Покрывает:

* play;
* pause;
* resume;
* buffering;
* ready;
* ended;
* изменение `isPlaying`.

Payload:

| Поле          | Тип       | Назначение                 |
| ------------- | --------- | -------------------------- |
| `sessionId`   | `String?` | ID сессии                  |
| `ts`          | `Long`    | Timestamp                  |
| `uri`         | `String?` | URI текущего элемента      |
| `isPlaying`   | `Boolean` | Идёт ли воспроизведение    |
| `isBuffering` | `Boolean` | Идёт ли буферизация        |
| `position`    | `Long?`   | Текущая позиция            |
| `duration`    | `Long?`   | Длительность               |
| `windowIndex` | `Int?`    | Индекс элемента playlist   |
| `title`       | `String?` | Название текущего элемента |
| `reason`      | `String?` | Причина изменения          |

Для истории просмотра это важное событие: принимающий слой может сохранять позицию при `pause`, `background`, `buffering`, `state_ready` и других reason, не дожидаясь следующего `position_tick`.

### `position_tick`

Периодическое событие позиции.

Отправляется, если включено:

```text
bridge_emit_position = true
```

Payload:

| Поле                 | Тип       | Назначение                 |
| -------------------- | --------- | -------------------------- |
| `sessionId`          | `String?` | ID сессии                  |
| `ts`                 | `Long`    | Timestamp                  |
| `uri`                | `String?` | URI текущего элемента      |
| `position`           | `Long?`   | Текущая позиция            |
| `duration`           | `Long?`   | Длительность               |
| `bufferedPosition`   | `Long?`   | Позиция буфера             |
| `bufferedPercentage` | `Int?`    | Процент буфера             |
| `windowIndex`        | `Int?`    | Индекс элемента playlist   |
| `title`              | `String?` | Название текущего элемента |
| `reason`             | `String?` | Причина события            |

Частые значения `reason`:

| Reason                  | Смысл                      |
| ----------------------- | -------------------------- |
| `tick`                  | Периодический tick         |
| `pause`                 | Пауза                      |
| `resume`                | Возобновление              |
| `buffering`             | Буферизация                |
| `state_ready`           | Плеер готов                |
| `seek`                  | Перемотка                  |
| `seek_forward`          | Перемотка вперёд           |
| `seek_backward`         | Перемотка назад            |
| `playlist_item_changed` | Смена элемента playlist    |
| `background`            | Activity ушла в background |
| `destroy`               | Activity уничтожается      |
| `user_exit`             | Пользовательский выход     |
| `ended`                 | Конец playback             |
| `error`                 | Ошибка                     |

### `seek_completed`

Отправляется после перемотки.

Payload:

| Поле           | Тип       | Назначение               |
| -------------- | --------- | ------------------------ |
| `sessionId`    | `String?` | ID сессии                |
| `ts`           | `Long`    | Timestamp                |
| `uri`          | `String?` | URI текущего элемента    |
| `fromPosition` | `Long?`   | Позиция до перемотки     |
| `toPosition`   | `Long?`   | Позиция после перемотки  |
| `windowIndex`  | `Int?`    | Индекс элемента playlist |

### `playlist_item_changed`

Отправляется при смене элемента playlist.

Payload:

| Поле           | Тип                | Назначение                     |
| -------------- | ------------------ | ------------------------------ |
| `sessionId`    | `String?`          | ID сессии                      |
| `ts`           | `Long`             | Timestamp                      |
| `uri`          | `String?`          | URI нового элемента            |
| `windowIndex`  | `Int`              | Индекс нового элемента         |
| `playlistSize` | `Int`              | Размер playlist                |
| `title`        | `String?`          | Название нового элемента       |
| `reason`       | `String`           | Причина перехода               |
| `position`     | `Long?`            | Текущая позиция                |
| `duration`     | `Long?`            | Длительность                   |
| `hasPrevious`  | `Boolean`          | Есть ли предыдущий элемент     |
| `hasNext`      | `Boolean`          | Есть ли следующий элемент      |
| `currentItem`  | `BridgeMediaItem?` | Текущий элемент, если заполнен |

Это основное событие для сериалов и episode tracking.

### `playback_ended`

Отправляется, когда ExoPlayer сообщает `STATE_ENDED`.

Payload:

| Поле           | Тип       | Назначение               |
| -------------- | --------- | ------------------------ |
| `sessionId`    | `String?` | ID сессии                |
| `ts`           | `Long`    | Timestamp                |
| `uri`          | `String?` | URI текущего элемента    |
| `windowIndex`  | `Int`     | Индекс элемента playlist |
| `playlistSize` | `Int`     | Размер playlist          |
| `title`        | `String?` | Название                 |
| `position`     | `Long?`   | Финальная позиция        |
| `duration`     | `Long?`   | Длительность             |

### `session_finished`

Финальное событие сессии.

Payload:

| Поле           | Тип       | Назначение               |
| -------------- | --------- | ------------------------ |
| `sessionId`    | `String?` | ID сессии                |
| `ts`           | `Long`    | Timestamp                |
| `uri`          | `String?` | URI текущего элемента    |
| `position`     | `Long?`   | Последняя позиция        |
| `duration`     | `Long?`   | Длительность             |
| `endBy`        | `String`  | Причина завершения       |
| `windowIndex`  | `Int?`    | Индекс элемента playlist |
| `playlistSize` | `Int?`    | Размер playlist          |
| `title`        | `String?` | Название                 |

Текущие значения `endBy`:

| Значение     | Смысл                       |
| ------------ | --------------------------- |
| `user_exit`  | Пользователь закрыл плеер   |
| `completion` | Воспроизведение завершилось |
| `destroy`    | Activity уничтожается       |
| `background` | Activity ушла в background  |
| `error`      | Завершение из-за ошибки     |

Фактическое значение зависит от точки вызова `flushProgress` / `finish`.

### `error`

Отправляется при ошибке воспроизведения.

Payload:

| Поле                 | Тип       | Назначение                    |
| -------------------- | --------- | ----------------------------- |
| `sessionId`          | `String?` | ID сессии                     |
| `ts`                 | `Long`    | Timestamp                     |
| `uri`                | `String?` | URI текущего элемента         |
| `code`               | `String?` | Строковый код ошибки          |
| `errorCode`          | `Int?`    | Числовой код ошибки ExoPlayer |
| `message`            | `String?` | Сообщение ошибки              |
| `windowIndex`        | `Int?`    | Индекс элемента playlist      |
| `position`           | `Long?`   | Последняя позиция             |
| `duration`           | `Long?`   | Длительность                  |
| `bufferedPosition`   | `Long?`   | Последняя позиция буфера      |
| `bufferedPercentage` | `Int?`    | Последний процент буфера      |
| `playlistSize`       | `Int?`    | Размер playlist               |
| `title`              | `String?` | Название текущего элемента    |
| `fatal`              | `Boolean` | Фатальная ли ошибка           |

### `track_selection_changed`

Отправляется при изменении выбранной дорожки, если соответствующая логика эмитирует событие.

Payload:

| Поле             | Тип       | Назначение                        |
| ---------------- | --------- | --------------------------------- |
| `sessionId`      | `String?` | ID сессии                         |
| `ts`             | `Long`    | Timestamp                         |
| `uri`            | `String?` | URI текущего элемента             |
| `trackType`      | `String`  | Тип дорожки                       |
| `trackIndex`     | `Int`     | Индекс дорожки                    |
| `trackId`        | `String?` | ID дорожки                        |
| `language`       | `String?` | Язык                              |
| `label`          | `String?` | Label                             |
| `sampleMimeType` | `String?` | MIME type                         |
| `channelCount`   | `Int?`    | Количество аудиоканалов           |
| `reason`         | `String`  | Причина выбора                    |
| `matchScore`     | `Int?`    | Оценка совпадения при автоподборе |

### `user_action`

Модель события существует в bridge-схеме. Событие предназначено для передачи пользовательских действий.

Payload:

| Поле          | Тип                   | Назначение               |
| ------------- | --------------------- | ------------------------ |
| `sessionId`   | `String?`             | ID сессии                |
| `ts`          | `Long`                | Timestamp                |
| `uri`         | `String?`             | URI текущего элемента    |
| `action`      | `String`              | Название действия        |
| `payload`     | `Map<String, String>` | Дополнительные данные    |
| `windowIndex` | `Int?`                | Индекс элемента playlist |

Наличие модели не означает, что все возможные пользовательские действия уже эмитируются в runtime.

## `BridgeMediaItem`

Некоторые события могут содержать `currentItem`.

Структура:

| Поле         | Тип       | Назначение             |
| ------------ | --------- | ---------------------- |
| `uri`        | `String?` | URI элемента           |
| `title`      | `String?` | Название элемента      |
| `filename`   | `String?` | Имя файла              |
| `externalId` | `String?` | Резерв для внешнего ID |
| `season`     | `Int?`    | Резерв для сезона      |
| `episode`    | `Int?`    | Резерв для серии       |
| `source`     | `String?` | Резерв для источника   |

На текущем уровне intent-парсинга стабильно заполняются прежде всего `uri`, `title`, `filename`. Остальные поля зарезервированы для дальнейшей интеграции и не должны считаться обязательными.

## Рекомендованный polling-клиент для Web-Lampa

Минимальная схема:

```js
const sid = 'lampa-session-001';
const base = 'http://127.0.0.1:39677';
let lastTs = 0;

async function pollEvents() {
  const url = `${base}/events?sid=${encodeURIComponent(sid)}&since=${lastTs}&limit=50`;
  const res = await fetch(url);
  const json = await res.json();

  if (!json.ok || !Array.isArray(json.events)) return;

  for (const event of json.events) {
    lastTs = Math.max(lastTs, event.ts || 0);

    switch (event.type) {
      case 'position_tick':
        // сохранить позицию
        break;

      case 'playback_state_changed':
        // обработать pause/resume/buffering
        break;

      case 'playlist_item_changed':
        // обновить текущую серию
        break;

      case 'session_finished':
        // финально сохранить позицию
        break;
    }
  }
}

setInterval(pollEvents, 1000);
```

## Рекомендованный запуск из Lampa-подобного клиента

Для web-клиента, который запускает Android external player, минимальный контракт должен включать:

* `Intent.ACTION_VIEW`;
* `intent.data` как URI текущей серии;
* `video_list` как полный список серий;
* `video_list.name`;
* `video_list.filename`, если доступно;
* `start_index`;
* `position`;
* `bridge_enabled = true` или `ddd_*` fragment;
* `bridge_session_id` / `ddd_sid`;
* `bridge_mode = local` или `ddd_mode=local`;
* стабильный `bridge_client = lampa` / `ddd_client=lampa`.

Пример через extras:

```kotlin
intent.putExtra("bridge_enabled", true)
intent.putExtra("bridge_mode", "local")
intent.putExtra("bridge_session_id", "movie-123-s1e2")
intent.putExtra("bridge_client", "lampa")
intent.putExtra("bridge_position_interval_ms", 1000L)
```

Пример через fragment:

```text
https://example.com/s01e02.mp4#ddd_mode=local&ddd_sid=movie-123-s1e2&ddd_client=lampa
```

## Совместимость и ограничения

### Local bridge

Local bridge работает только как локальный канал состояния.

Он не должен использоваться как видеосервер или как механизм передачи самого потока.

### Fragment-параметры

URI fragment удаляется перед воспроизведением. Это правильно для ExoPlayer, но важно учитывать при сравнении URI во внешнем клиенте.

### Playlist position

В playlist-режиме `position` применяется только к элементу, совпавшему с `intent.data`. Если `intent.data` не совпадает с URI нужной серии, позиция может быть применена не туда или не применена вообще.

### Event order

Внешний клиент должен быть устойчив к нескольким событиям подряд:

* `position_tick` перед `playlist_item_changed`;
* `playback_state_changed` после `playback_ended`;
* финальный `position_tick` перед `session_finished`;
* повторные события с близкими позициями.

События bridge нужно рассматривать как поток телеметрии, а не как строго единственный источник истины для UI.

### Session id

Для корректной интеграции внешний клиент должен задавать стабильный `sessionId`.

Если `sid` не задан, local store всё равно может хранить события, но клиенту сложнее надёжно отличать одну сессию от другой.

## Сборка

Проект собирается как Android application.

Базовая команда:

```bash
./gradlew assembleDebug
```

Release-сборка:

```bash
./gradlew assembleRelease
```

Параметры Android-модуля:

```text
namespace     = top.rootu.dddplayer
applicationId = top.rootu.dddplayer
minSdk        = 23
compileSdk    = 36
targetSdk     = 34
```

Version code считается по количеству коммитов `git rev-list --count HEAD`.

Version name берётся из `git describe --tags --dirty`; если тегов нет, используется fallback вида:

```text
0.0.1-dev-<short_commit_hash>
```

## Быстрая проверка local bridge

1. Запустить видео с bridge-режимом `local`.

2. Проверить доступность сервера:

```bash
curl "http://127.0.0.1:39677/ping"
```

Ожидаемый ответ:

```json
{
  "ok": true,
  "service": "dddplayer-local-bridge",
  "port": 39677
}
```

3. Проверить события:

```bash
curl "http://127.0.0.1:39677/events?sid=lampa-session-001"
```

4. Проверить последнее состояние:

```bash
curl "http://127.0.0.1:39677/state?sid=lampa-session-001"
```

Если local bridge запущен с токеном:

```bash
curl "http://127.0.0.1:39677/events?sid=lampa-session-001&token=secret-token"
```

## Практический контракт для интегратора

Минимум, на который можно рассчитывать при интеграции:

1. Плеер открывается через `Intent.ACTION_VIEW`.
2. Основной video URI передаётся через `intent.data`.
3. Playlist передаётся через `video_list`.
4. Стартовый элемент задаётся через `start_index`, но при совпадении `intent.data` с элементом playlist индекс может быть уточнён по URI.
5. `position` задаётся в миллисекундах.
6. Bridge включается через `bridge_enabled=true` или через fragment `ddd_*`.
7. Для Web-Lampa предпочтительный режим — `local`.
8. Local bridge endpoint — `http://127.0.0.1:39677` по умолчанию.
9. Основные endpoints — `/ping`, `/state`, `/events`.
10. Public event names — только `snake_case`.
11. При ручном выходе `end_by` / `endBy` сейчас имеет значение `user_exit`.
12. Историю просмотра сохраняет внешний клиент, не DDD Player 2.

## Рекомендуемая обработка прогресса во внешнем клиенте

Для надёжного сохранения позиции внешний клиент должен учитывать несколько событий:

* `position_tick` с reason `tick` — регулярное обновление;
* `position_tick` с reason `pause` — сохранить сразу при паузе;
* `position_tick` с reason `background` — сохранить при уходе Activity в background;
* `position_tick` с reason `before_playlist_item_changed` — сохранить старую серию перед переходом;
* `playlist_item_changed` — сменить текущий элемент;
* `session_finished` — финально сохранить сессию;
* `error` — сохранить последнюю известную позицию перед ошибкой.

Для сериалов нельзя сохранять только `session_finished`: пользователь может переключать серии внутри плеера, и важные позиции придут раньше через `playlist_item_changed` / `position_tick`.

## Рекомендуемые правила для Lampa-плагина

Для Lampa-подобного JS-плагина рекомендуется:

* генерировать стабильный `sid` на карточку / сезон / серию;
* хранить mapping между `sid`, `movie`, `season`, `episode`, `playlist_index` и stream-параметрами;
* читать `/events`, а не только `/state`;
* использовать `since` для polling;
* сохранять позицию на `pause`, `background`, `before_playlist_item_changed`, `playlist_item_changed`, `session_finished`, `error`;
* использовать `windowIndex` и `currentItem.filename` для episode inference;
* не привязывать сохранённую позицию к конкретному TorrServer host;
* быть устойчивым к отсутствующим полям в payload.

## Stability notes

Текущий bridge-контракт пригоден для интеграции, но часть полей следует считать расширяемой:

* новые event-типы могут быть добавлены без изменения старых;
* payload событий может расширяться новыми nullable-полями;
* внешний клиент должен игнорировать неизвестные поля;
* внешний клиент должен игнорировать неизвестные `event.type`, если не умеет их обрабатывать.

Публично значимыми считаются:

* имена extras;
* fragment-параметры `ddd_*`;
* endpoint names `/ping`, `/state`, `/events`;
* public event names в `snake_case`;
* базовые поля envelope: `schema`, `type`, `client`, `sessionId`, `ts`, `payload`;
* значения `bridge_mode`: `broadcast`, `local`, `both`.

## Лицензия

Подробности см. в файле [LICENSE](LICENSE).
