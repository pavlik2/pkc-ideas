#!/usr/bin/env bash

# Exit on error
set -e

INPUT_DIR="${1:-.}"   # default = current directory
SPEAKER_NAME="${2:-${SPEAKER_NAME:-}}"

if [ -z "$SPEAKER_NAME" ]; then
    echo "Usage: $0 [input_directory] SPEAKER_NAME" >&2
    echo "Or set SPEAKER_NAME in the environment." >&2
    exit 2
fi

for file in "$INPUT_DIR"/*.srt; do
    # Skip if no files found
    [ -e "$file" ] || continue

    echo "Processing: $file"

python3 "$(dirname "$0")/replace_speaker_tags.py" --speaker "$SPEAKER_NAME" "$file"


    echo "Done: $file"
    echo "-------------------------"
done

echo "All files processed."
