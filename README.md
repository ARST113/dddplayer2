# DDD Player 2

External Android video player for Lampa/Lampac/TorrServer-style launches.

DDD Player 2 is not a movie catalog, torrent searcher, Lampac replacement, or TorrServer replacement. It receives a prepared media URL or playlist through Android external playback, plays it, and exposes playback state back to the caller when bridge mode is enabled.

## Latest Release

- Release page: https://github.com/ARST113/dddplayer2/releases
- Latest APK: https://github.com/ARST113/dddplayer2/releases/download/v0.0.7/dddplayer-v0.0.7-release.apk
- Package: `top.rootu.dddplayer`
- Minimum Android: Android 6.0 / API 23

## Current State

DDD Player 2 currently supports:

- normal `Intent.ACTION_VIEW` external playback;
- single video launch through `intent.data`;
- internal playlist launch through `video_list` extras;
- robust playlist start matching where `intent.data` is authoritative over stale `start_index`;
- HTTP headers from intent extras;
- start position from `position`;
- posters/thumbnails for playlist items;
- Media3 / ExoPlayer as the primary backend;
- LibVLC fallback backend for streams Media3 cannot decode, especially HEVC and AV1 cases;
- sticky VLC fallback for the current playlist session after a decoder failure;
- VLC progress, duration, seekbar, and time labels;
- VLC audio selection by real VLC track id;
- compact audio labels in top panel, settings overlay, and audio menu, for example `AniLibria AAC 2.0` and `Japanese FLAC 2.0`;
- VLC audio metadata fallback from LibVLC when Media3 track formats are not available;
- first working VLC subtitle layer: VLC subtitle tracks are listed/selectable and external subtitle items are passed as VLC subtitle slaves;
- Media3 subtitle track selection and saved subtitle state;
- TorrServer cache pieces indicator like Lampa: 5 dots from `/cache`, not from seed/peer counters;
- local playback settings persistence for audio/subtitle choices;
- playlist audio restore by name/fingerprint, not only by fragile numeric ids;
- top panel with compact video/audio/subtitle values, peer/cache stats, and TorrServer pieces dots;
- bridge events through Android Broadcast and/or local in-memory HTTP bridge.

## Recent v0.0.7 Changes

Compared with `v0.0.6`, this release adds and fixes:

- AV1/dav1d video decoder failures now trigger LibVLC fallback instead of leaving playback in audio-only mode.
- 10-bit AV1 MKV streams such as `Trigun S01E17.mkv` can recover through VLC after Media3 reports `ERROR_CODE_DECODING_FAILED`.

## Playback Backends

### Media3

Media3 / ExoPlayer is used first. It handles normal HTTP/video playback, playlists, metadata extraction, subtitles, and track selection.

### VLC Fallback

When Media3 hits a decoder failure and fallback is allowed, DDD Player 2 switches the current item to LibVLC. For the rest of that playlist session, next/previous/play-index operations stay on VLC to avoid repeating the same decoder failure for every episode.

VLC mode currently has working progress updates, duration updates, audio track switching, compact audio labels, and the first subtitle track support layer.

## TorrServer Cache Indicator

For TorrServer stream URLs like:

```text
http://host:port/stream/file.mkv?link=<hash>&index=<id>&play
```

DDD Player 2 extracts:

- base URL: `http://host:port`
- hash: `link` or `hash`
- file index: `index`

It polls:

```http
POST /cache
{"action":"get","hash":"<hash>"}
```

The top-panel dots are calculated from:

- `Readers[0].Reader`
- `Readers[0].End`
- `Pieces[index].Completed`

This is the same kind of read-ahead cache health indicator Lampa shows. It is not calculated from connected peers, seeders, download speed, or ExoPlayer buffering.

Polling interval is 2 seconds. If `/cache` is unavailable, the indicator is hidden and playback continues normally.

## External Launch Contract

The recommended launch contract for Lampa-like clients:

1. Use `Intent.ACTION_VIEW`.
2. Put the clicked/current stream URL into `intent.data`.
3. Pass the full playlist through `video_list` extras when launching a series.
4. Treat `intent.data` as authoritative for the current item.
5. Pass `position` in milliseconds when resuming.
6. Pass headers through `headers` when needed.
7. Enable bridge only if the caller actually needs state/events back.

Important playlist rule:

When `intent.data` exists, DDD Player 2 matches it against playlist items and does not blindly trust stale `start_index`. Matching uses normalized URL, TorrServer `link/index`, and filename fallback. If no match is found, the player starts at index `0` rather than using stale external state.

## Intent Extras

Single-item extras:

| Extra | Type | Purpose |
| --- | --- | --- |
| `title` | `String` | Display title |
| `android.intent.extra.TITLE` | `String` | Alternate display title |
| `thumbnail` | `String` | Poster URL |
| `position` | `Long`, `Int`, or numeric `String` | Start position in ms |
| `headers` | string array/list | Flat HTTP header list |
| `return_result` | `Boolean` | Return last position through `setResult` |

Playlist extras:

| Extra | Purpose |
| --- | --- |
| `video_list` | Array/list of media URLs |
| `video_list.name` | Display titles |
| `video_list.filename` | Stable filenames |
| `video_list.thumbnail` | Poster URLs |
| `video_list.subtitles` | Per-item subtitles bundle |
| `start_index` | Fallback start index only when no `intent.data` exists |

Subtitle extras use URI/name arrays in the existing app format. In VLC mode, this release includes the first working layer for listing/selecting VLC subtitle tracks and passing external subtitles to VLC.

## Bridge

Bridge is optional. It can be enabled through extras or `ddd_*` URI fragment parameters.

Supported modes:

- `broadcast`
- `local`
- `both`

Default local bridge:

```text
http://127.0.0.1:39677
```

Endpoints:

- `/ping`
- `/state`
- `/events`

The bridge is for playback state and telemetry. It is not a video proxy and does not serve media.

Common bridge events include playback state changes, position ticks, playlist item changes, track selection changes, errors, and session finish events. External clients should treat events as telemetry and tolerate repeated or extended payload fields.

## What DDD Player 2 Does Not Do

DDD Player 2 does not:

- search movies or series;
- parse torrent trackers;
- replace Lampa, Lampac, or TorrServer;
- run a media catalog;
- proxy video streams through its local bridge;
- permanently own watch history for the whole ecosystem.

The external client remains responsible for catalog logic, torrent selection, long-term history, and deciding when to launch the player.

## UI Notes

The current player UI is optimized for TV-style external playback:

- compact top info panel;
- current title/poster and playlist state;
- video badge such as `1920x1080`;
- audio badge such as `AniLibria AAC 2.0`;
- subtitle badge when available;
- TorrServer peer/speed info when available;
- TorrServer cache pieces dots when the stream URL exposes `link`/`hash`.

Long track metadata is intentionally kept out of the top badge. Technical details remain available through menus/overlays where they matter.

## Build

Debug build:

```bash
./gradlew assembleDebug
```

Release build:

```bash
./gradlew assembleRelease
```

Android module settings:

```text
namespace     = top.rootu.dddplayer
applicationId = top.rootu.dddplayer
minSdk        = 23
compileSdk    = 36
targetSdk     = 34
```

Versioning:

- `versionCode` is based on `git rev-list --count HEAD`, unless `versionCodeOverride` is provided.
- `versionName` is based on `git describe --tags --dirty`, unless `versionNameOverride` is provided.

Example explicit version build:

```bash
./gradlew :app:assembleDebug -PversionNameOverride=0.0.5
```

## License

See [LICENSE](LICENSE).
