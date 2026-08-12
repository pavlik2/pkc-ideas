#!/usr/bin/env bash
set -e

INPUT="$1"
SEGMENT_TIME="${2:-600}"

if [ -z "$INPUT" ]; then
  echo "Usage: $0 input.wav [segment_seconds]"
  exit 1
fi

BASE="$(basename "$INPUT")"
NAME="${BASE%.*}"

rm -rf chunks denoised joined
mkdir -p chunks denoised joined

echo "Splitting into ${SEGMENT_TIME}s chunks..."
ffmpeg -y -i "$INPUT" -ar 48000 -ac 1 -f segment -segment_time "$SEGMENT_TIME" chunks/chunk_%04d.wav

echo "Running DeepFilterNet..."
for f in chunks/*.wav; do
  echo "Processing $f"
  deepFilter "$f" --output-dir denoised
done

echo "Preparing concat list..."
find denoised -name "*.wav" | sort | while read -r f; do
  echo "file '$PWD/$f'"
done > joined/list.txt

echo "Joining..."
ffmpeg -y -f concat -safe 0 -i joined/list.txt -c copy "joined/${NAME}_denoised.wav"

echo "Done: joined/${NAME}_denoised.wav"
