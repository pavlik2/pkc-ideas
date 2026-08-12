#!/usr/bin/env bash
# Create a denoised audio transcript, chapter summary, and self-contained viewer.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-$SCRIPT_DIR/output}"
WHISPERX_MODEL="${WHISPERX_MODEL:-large-v3}"
LLM_MODEL="${LLM_MODEL:-qwen/qwen3.5-9b}"
SPEAKER_NAME="${SPEAKER_NAME:-}"

usage() {
  cat <<'EOF'
Usage: runAutomaticTranscription.sh AUDIO_FILE

Environment variables:
  OUTPUT_DIR      Directory for final artifacts (default: ./output)
  WHISPERX_MODEL  WhisperX model (default: large-v3)
  LLM_MODEL       Model loaded in LM Studio (default: qwen/qwen3.5-9b)
  SPEAKER_NAME    Name for WhisperX's most frequent speaker tag (optional)

The script requires ffmpeg, deepFilter, whisperx, python3, and the Python
dependencies used by llm_script.py.  For speaker diarization, export HF_TOKEN
before running WhisperX.
EOF
}

if [[ $# -ne 1 || "$1" == "-h" || "$1" == "--help" ]]; then
  usage
  [[ $# -eq 1 ]] && exit 0
  exit 2
fi

INPUT_FILE="$(realpath -e "$1")"
[[ -f "$INPUT_FILE" ]] || { echo "Audio file not found: $1" >&2; exit 1; }

for command in ffmpeg deepFilter whisperx python3; do
  command -v "$command" >/dev/null || {
    echo "Required command is not available: $command" >&2
    exit 1
  }
done

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/automatic-transcription.XXXXXX")"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

mkdir -p "$OUTPUT_DIR"

echo "1/5 Converting input to a mono WAV file..."
ffmpeg -y -i "$INPUT_FILE" -ar 48000 -ac 1 "$WORK_DIR/input.wav"

echo "2/5 Denoising audio with DeepFilterNet..."
(
  cd "$WORK_DIR"
  "$SCRIPT_DIR/deepfilter.sh" "$WORK_DIR/input.wav"
)
DENOISED_AUDIO="$WORK_DIR/joined/input_denoised.wav"
[[ -f "$DENOISED_AUDIO" ]] || { echo "Denoised audio was not created." >&2; exit 1; }
cp "$DENOISED_AUDIO" "$OUTPUT_DIR/audio.wav"

echo "3/5 Transcribing and diarizing with WhisperX..."
mkdir -p "$WORK_DIR/whisperx"
WHISPERX_ARGS=(
  --language ru
  --model "$WHISPERX_MODEL"
  --diarize
  --output_format srt
  --output_dir "$WORK_DIR/whisperx"
)
if [[ -n "${HF_TOKEN:-}" ]]; then
  WHISPERX_ARGS+=(--hf_token "$HF_TOKEN")
fi
whisperx "$DENOISED_AUDIO" "${WHISPERX_ARGS[@]}"

SRT_FILE="$(find "$WORK_DIR/whisperx" -maxdepth 1 -type f -name '*.srt' -print -quit)"
[[ -n "$SRT_FILE" && -f "$SRT_FILE" ]] || { echo "WhisperX did not create an SRT file." >&2; exit 1; }
cp "$SRT_FILE" "$OUTPUT_DIR/transcript.srt"

if [[ -n "$SPEAKER_NAME" ]]; then
  echo "Applying speaker name with run_name.sh..."
  "$SCRIPT_DIR/run_name.sh" "$OUTPUT_DIR" "$SPEAKER_NAME"
  RENAMED_SRT="$OUTPUT_DIR/transcript.replaced.srt"
  [[ -f "$RENAMED_SRT" ]] || { echo "run_name.sh did not create a renamed SRT file." >&2; exit 1; }
  mv "$RENAMED_SRT" "$OUTPUT_DIR/transcript.srt"
else
  echo "Skipping speaker-name replacement (set SPEAKER_NAME to enable it)."
fi

echo "4/5 Generating chapter summary with the LLM..."
python3 "$SCRIPT_DIR/llm_script.py" \
  "$OUTPUT_DIR/transcript.srt" \
  --model "$LLM_MODEL" \
  --output "$OUTPUT_DIR/chapters.json"

echo "5/5 Creating the transcript viewer..."
cp "$SCRIPT_DIR/index.html" "$OUTPUT_DIR/transcript.html"
# The viewer normally defaults to an M4A input; this generated bundle contains audio.wav.
sed -i 's/"input\.m4a"/"audio.wav"/' "$OUTPUT_DIR/transcript.html"
sed -i 's/type="audio\/mp4"/type="audio\/wav"/' "$OUTPUT_DIR/transcript.html"

echo
echo "Done. Final files are in: $OUTPUT_DIR"
echo "  transcript.html  - transcript and summary viewer"
echo "  chapters.json    - LLM-generated chapter summary"
echo "  transcript.srt   - WhisperX transcript"
echo "  audio.wav        - denoised audio"
echo
echo "Open the viewer through a local HTTP server, for example:"
echo "  cd \"$OUTPUT_DIR\" && python3 -m http.server"
