# Care Companion Android App

A privacy-conscious Android prototype for organizing medical records, tracking daily symptoms, asking an OpenAI-compatible LLM contextual questions, and searching PubMed and EU CTIS.

The implementation plan and production-hardening notes are in [`PLAN.md`](PLAN.md).

## Features

- Warm, spacious interface inspired by the supplied Android reference, with terracotta, cream, and sage tones
- Local no-fee providers first: LM Studio, Ollama, and llama.cpp, followed by Groq, OpenAI, and custom OpenAI-compatible servers
- Multiple patient profiles with isolated app-private JSON for imported documents, medical data, tracker definitions/history, chat, settings, and research searches
- Profile creation, switching, permanent deletion, and full JSON ZIP export from Settings
- PDF and text extraction plus bundled on-device ML Kit image OCR
- Built-in medical extraction structure or a profile-scoped, user-uploaded JSON Schema; required fields, types, enums, arrays, objects, and `additionalProperties: false` are checked before extracted data is saved
- JSON-only prompts for medical extraction and fixed-choice daily questionnaires with concrete hydration, medication, stool, symptom, and wellbeing answer bands
- Questionnaire history for today, seven days, four weeks, or all time, plus a trend graph for every question with numeric answer values
- Paginated literature modes for PubMed citations, PubMed abstracts, and true PMC full-text body search
- Nested JATS markup is handled in abstracts, and PMC article body text can be retrieved on demand through EFetch and read inside the app with its license notice
- Profile-scoped research history for PubMed and EU CTIS searches
- On-demand CTIS trial details including objectives, eligibility, endpoints, products, sponsors, sites, events, public documents, and published results
- Presentation-derived onboarding and six-destination main screen

> This prototype is informational and is not a medical device. It does not diagnose, prescribe, or replace a clinician.

## Prerequisites

- JDK 17
- Android SDK Platform 33 and Build Tools 33

The Latin OCR model is statically bundled in the APK through ML Kit. Users do not install an OCR engine or download a language model. This increases APK size but makes OCR immediately available offline.

An upload-ready schema example is available at [`examples/medical-extraction-schema.json`](examples/medical-extraction-schema.json).

## Build

```bash
./gradlew assembleDebug
```

Install on a connected device or emulator with:

```bash
./gradlew installDebug
```
