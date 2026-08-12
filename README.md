# PKC AI Research — Ideas and Experiments

This repository contains independent research projects, prototypes, and experiments from PKC AI Research OU. Each top-level project directory has its own documentation, requirements, and build or usage instructions.

## Whitepapers will fillow soon
Set of whitepapers written by human about AI usage in different fields, harm of AI, cheating with AI usage will follow. 

## Main disclaimer

These projects were created with the intention of being useful to society. OpenAI Codex was used as a development tool in the creation of these projects.

The projects are experimental and are provided without warranty. They may be incomplete, contain errors, or require additional testing and security work before production use. Nothing in this repository should be treated as medical, legal, financial, or other professional advice. In particular, the medical-related prototype is not a medical device and does not diagnose, prescribe, or replace qualified healthcare professionals.

## Projects

### [Care Companion Android App](android-cancer-app/README.md)

A privacy-conscious Android prototype for organizing medical records, tracking symptoms, querying OpenAI-compatible language models with patient context, and searching PubMed and the EU Clinical Trials Information System. It includes local profile storage, document import and OCR, configurable medical extraction schemas, questionnaires, trends, research history, and data export.

### [Android Meditation](android-meditation/README.md)

A minimal Kotlin Android meditation application designed for development in VS Code. The project includes a Gradle-based Android build and instructions for installing it on a connected device or emulator.

### [Audio Transcript Viewer](transcriptHTML/readme.md)

An automated audio-processing workflow that denoises recordings, creates speaker-labelled transcripts with WhisperX, generates structured chapter summaries through an OpenAI-compatible local LLM, and builds an HTML page for reviewing the audio, transcript, and summary together.

### [EvoNN](work-cpp-ai/README.md)

A neural-network classifier written in C that uses a genetic algorithm to search for hidden-layer architectures. It supports JSON datasets, SGD training, CPU instruction-set acceleration, optional CUDA kernels, runtime backend benchmarking, and command-line prediction.

## Repository structure

```text
android-cancer-app/   Care Companion medical-record and research prototype
android-meditation/   Minimal Kotlin Android meditation application
transcriptHTML/       Audio transcription, summarization, and HTML viewer
work-cpp-ai/          EvoNN neural-network architecture search in C
```

See each project's linked README for installation, dependencies, safety notes, and usage instructions.

## License

This repository and its projects are licensed under the [GNU General Public License, version 3](https://www.gnu.org/licenses/gpl-3.0.html) (GPLv3).

Copyright © 2026 PKC AI Research AI.

## Contact

- Email: [pavel@pkcairesearch.com](mailto:pavel@pkcairesearch.com)
- Website: [pkcairesearch.com](https://pkcairesearch.com/)
