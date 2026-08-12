# Audio transcript viewer

This project turns an audio recording into a denoised, speaker-labelled transcript, an AI-generated chapter summary, and an HTML page for reviewing both alongside the audio.

## Files

- `runAutomaticTranscription.sh` — the complete processing workflow: converts and denoises the audio, runs WhisperX transcription and diarization, optionally applies `run_name.sh`, asks an LLM to produce chapter summaries, and writes the final bundle to `output/`.
- `deepfilter.sh` — splits a WAV recording into chunks and denoises them with DeepFilterNet.
- `llm_script.py` — sends an SRT file to an OpenAI-compatible LM Studio server and saves structured chapter data as JSON.
- `index.html` — the browser viewer for the audio, transcript, and chapter summaries.
- `run_whisperx.sh` — a standalone batch WhisperX helper for M4A files.
- `replace_speaker_tags.py` and `run_name.sh` — helpers for replacing a speaker tag in an SRT file.
- `preview.html` — a lightweight viewer preview.

## Installation

The examples below target Linux. Install [Miniconda](https://docs.conda.io/projects/conda/en/stable/user-guide/install/linux.html) first, then restart the terminal (or run `source ~/.bashrc`). Create an isolated environment:

```bash
conda create -n transcript-html python=3.11 pip -y
conda activate transcript-html
conda install -c conda-forge ffmpeg -y
python -m pip install --upgrade pip
```

Install the Python components:

```bash
# CPU PyTorch. For an NVIDIA GPU, install the matching PyTorch build instead.
python -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
python -m pip install deepfilternet whisperx openai
```

This provides the `deepFilter`, `whisperx`, and `python3` commands used by the workflow. DeepFilterNet documents the `deepfilternet` package and `deepFilter` command; WhisperX documents the `whisperx` package and optional GPU setup. See the [DeepFilterNet installation guide](https://github.com/Rikorose/DeepFilterNet#deepfilternet-python-pypi) and [WhisperX installation guide](https://github.com/m-bain/whisperX#1-simple-installation-recommended) for platform- and GPU-specific details.

Confirm the command-line tools are visible in the active environment:

```bash
ffmpeg -version
deepFilter --help
whisperx --help
```

For diarization, create a read token at [Hugging Face](https://huggingface.co/settings/tokens), accept access conditions for WhisperX's required pyannote diarization model, then set it before running:

```bash
export HF_TOKEN=your_hugging_face_token
```

Install [LM Studio](https://lmstudio.ai/), download and load a model that can produce Russian JSON summaries, then start its local server before generating the summary. By default the script uses `http://127.0.0.1:1234/v1` and model `qwen/qwen3.5-9b`; configure the loaded model name with `LLM_MODEL` if needed.

## Run

```bash
cd transcriptHTML
export HF_TOKEN=your_hugging_face_token
export SPEAKER_NAME="Name Surname" # optional: replaces the most frequent WhisperX speaker tag
./runAutomaticTranscription.sh /path/to/recording.m4a
```

The script creates `output/` with these final files:

- `transcript.html` — HTML page to view and navigate the transcript and AI summary
- `chapters.json` — structured chapter summary
- `transcript.srt` — timestamped, speaker-labelled transcript
- `audio.wav` — denoised audio

Serve that directory locally so the browser can load the JSON and SRT files:

```bash
cd output
python3 -m http.server
```

Then open `http://localhost:8000/transcript.html`.

`SPEAKER_NAME` invokes `run_name.sh` after transcription. It replaces the most frequent WhisperX speaker tag with the provided name; omit it to keep the original `SPEAKER_XX` labels.

To rename a speaker in an existing SRT without running the full workflow:

```bash
./run_name.sh /path/to/srt-directory "Name Surname"
```

Use `OUTPUT_DIR`, `WHISPERX_MODEL`, and `LLM_MODEL` to override the output location or model choices.
