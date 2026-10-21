#!/usr/bin/env bash
# Makes the TV voice clips with Amazon Polly. You need the aws cli set up with access to Polly.
# You can pass a voice name, Joanna by default.
set -euo pipefail
cd "$(dirname "$0")/.."
VOICE="${1:-Joanna}"
OUT=tv/src/main/res/raw
mkdir -p "$OUT"
grep -v '^#' tools/clips.txt | while IFS='|' read -r name text; do
  [ -z "$name" ] && continue
  rm -f "$OUT/$name.m4a" "$OUT/$name.mp3" # android only allows one file per name
  aws polly synthesize-speech \
    --engine neural --voice-id "$VOICE" --output-format mp3 \
    --text-type ssml --text "<speak>$text</speak>" \
    "$OUT/$name.mp3" > /dev/null
  echo "made $name.mp3"
done
