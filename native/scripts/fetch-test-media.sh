#!/usr/bin/env bash
#
# Скачивает тестовые медиафайлы. Всё, кроме двух файлов Big Buck Bunny, лежит в
# FATE — официальном наборе семплов FFmpeg, поэтому набор воспроизводим и не
# требует хранить 25 МБ бинарников в репозитории.
#
#   bash fetch-test-media.sh          — докачать отсутствующее
#   bash fetch-test-media.sh --force  — перекачать всё
#
# Проверка целостности — в test-media/README.md (sha256 по каждому файлу).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$(cd "$HERE/../../test-media" 2>/dev/null && pwd || echo "$HERE/../../test-media")"
FATE="https://fate-suite.ffmpeg.org"

mkdir -p "$DEST"

# «локальное имя|URL». Локальные имена короче и однороднее оригинальных: в тестах
# они встречаются десятки раз, и `hdr10plus-hevc.hevc` читается лучше, чем
# `hdr10_plus_h265_sample.hevc`.
FILES=(
    "hdr10tags-both.mkv|$FATE/mkv/hdr10tags-both.mkv"
    "spherical.mkv|$FATE/mkv/spherical.mkv"
    "test7_cut.mkv|$FATE/mkv/test7_cut.mkv"
    "hdr10plus-vp9.webm|$FATE/mkv/hdr10_plus_vp9_sample.webm"
    "spherical.mov|$FATE/mov/spherical.mov"
    "displaymatrix.mov|$FATE/mov/displaymatrix.mov"
    "dovi-p5.mp4|$FATE/mov/dovi-p5.mp4"
    "dovi-p7.mp4|$FATE/mov/dovi-p7.mp4"
    "dovi-p81.mp4|$FATE/mov/dovi-p81.mp4"
    "dovi-p84.mov|$FATE/hevc/dv84.mov"
    "hdr10plus-hevc.hevc|$FATE/hevc/hdr10_plus_h265_sample.hevc"
    "hdrvivid-hevc.hevc|$FATE/hevc/hdr_vivid_h265_sample.hevc"
)

force=0
[[ "${1:-}" == "--force" ]] && force=1

for entry in "${FILES[@]}"; do
    name="${entry%%|*}"
    url="${entry#*|}"
    out="$DEST/$name"
    if [[ -f "$out" && $force -eq 0 ]]; then
        printf '  %-22s есть\n' "$name"
        continue
    fi
    printf '  %-22s ' "$name"
    if curl -fsSL --retry 3 -o "$out.part" "$url"; then
        mv "$out.part" "$out"
        echo "$(stat -c%s "$out" 2>/dev/null || stat -f%z "$out") байт"
    else
        rm -f "$out.part"
        echo "ОШИБКА (см. $url)"
    fi
done

# Big Buck Bunny: источник в истории проекта не зафиксирован, и точные копии
# восстановить нельзя. Тесты используют их только как «обычное SDR-видео», так что
# годится любой семпл BBB (CC-BY 3.0). Подробности — в README.md.
for name in buck480p30.mp4 bbb-hevc-10s.mp4; do
    [[ -f "$DEST/$name" ]] || echo "  ВНИМАНИЕ: $name отсутствует, см. test-media/README.md"
done
