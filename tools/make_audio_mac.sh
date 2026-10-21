#!/usr/bin/env bash
# Makes stand in voice clips with the mac voice so we don't need AWS for testing.
# Use make_audio_polly.sh for the real Polly voice. You can pass a voice name, Samantha by default.
set -euo pipefail
cd "$(dirname "$0")/.."
VOICE="${1:-Samantha}"
OUT=tv/src/main/res/raw
TMP="$(mktemp -d)"
mkdir -p "$OUT"
grep -v '^#' tools/clips.txt | while IFS='|' read -r name text; do
  [ -z "$name" ] && continue
  # turn the pauses into something say understands
  spoken="$(echo "$text" | sed -E 's#<break time="([0-9]+)ms"/>#[[slnc \1]]#g')"
  say -v "$VOICE" -o "$TMP/$name.aiff" "$spoken"
  rm -f "$OUT/$name.m4a" "$OUT/$name.mp3"
  afconvert -f m4af -d aac -b 64000 "$TMP/$name.aiff" "$OUT/$name.m4a"
done
rm -rf "$TMP"
echo "made $(ls "$OUT"/*.m4a | wc -l | tr -d ' ') clips in $OUT"
