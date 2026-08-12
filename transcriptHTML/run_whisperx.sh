#!/usr/bin/env bash

# Exit on error
set -e

# Optional: set your HF token here (or export before running)
# export HF_TOKEN=your_token_here

INPUT_DIR="${1:-.}"   # default = current directory

for file in "$INPUT_DIR"/*.m4a; do
    # Skip if no files found
    [ -e "$file" ] || continue

    echo "Processing: $file"

    whisperx \
        --language ru \
        "$file" \
        --model large-v3 \
        --diarize \
        --output_format srt

    echo "Done: $file"
    echo "-------------------------"
done

echo "All files processed."
