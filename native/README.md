# Native unified playback engine

The Android app builds `native/ddd_engine` and packages the pinned arm64 FFmpeg
libraries from `native/prebuilt/ffmpeg`. The corresponding FFmpeg and libxml2
sources are pinned as Git submodules. Clone with `--recurse-submodules` or run:

```text
git submodule update --init --recursive
```

The normal Gradle build does not rebuild FFmpeg. To reproduce the prebuilt
libraries, use the scripts in `native/scripts`; the exact configure options and
the Windows response-file patch are stored there.

FFmpeg is provided under the licenses in its `LICENSE.md` and `COPYING.*` files.
libxml2 is provided under its upstream license. The DDD engine sources in this
repository use the repository's project license.
