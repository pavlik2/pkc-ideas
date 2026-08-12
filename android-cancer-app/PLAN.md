# Cancer Companion Android Application Plan

## Product goal

Build a privacy-conscious Android companion that lets a patient import medical records, structure those records with a user-selected OpenAI-compatible LLM, track daily symptoms, chat about the stored context, and discover relevant PubMed papers and EU clinical trials. The application is informational and must never present itself as a replacement for a clinician or emergency care.

## Design source

`ApplicationScreensANDFunctionality.pptx` defines the primary flow and visual direction:

1. First-launch LLM access setup.
2. Provider profile fields (name, URL, access key, temperature).
3. Medical document selection (file or picture).
4. Visible processing progress.
5. A main screen linking Info, Chat, Medical tracker, Clinical trials, Medical Papers, and Settings.

The implementation keeps the light-blue canvas, strong blue rounded controls, large readable labels, and straightforward navigation while filling in the details needed for an operational app.

## Architecture

- Single-activity Android app with small screen controllers and XML resources.
- `JsonFileStore`: global LLM connections plus isolated patient-profile directories containing separate private JSON files for processed medical data, imported-document metadata, questionnaire definition/history, chat, settings, and research history; each patient profile can be exported as a ZIP.
- `RemoteLlmClient.java`: provider-neutral OpenAI Chat Completions HTTP client supporting OpenAI, Groq, LM Studio, Ollama's OpenAI endpoint, llama.cpp, and custom compatible servers.
- `PromptTemplates`: strict JSON prompts for medical-record extraction and questionnaire generation (default maximum 12; user-adjustable).
- `DocumentTextExtractor`: plain-text import, PDF text extraction, and bundled on-device image OCR with clear errors for unsupported/corrupt content.
- API clients for NCBI PubMed/PMC and the EU CTIS public clinical-trials endpoint.
- No API keys in logs, exported storage, or source control. Provider secrets are stored only in app-private storage for this local prototype.

## Delivery stages

### 1. App foundation and navigation

- Apply the presentation-derived theme and reusable card/button styles.
- Implement first-launch routing, back navigation, main menu, and safety disclaimer.
- Add manifest permissions and Android document/camera pickers.

### 2. LLM configuration and connectivity

- Add provider presets and editable name, base URL, model, API key, temperature, max tokens, timeout, and system prompt.
- Save profiles to a dedicated JSON file and provide a connection test.
- Normalize provider URLs without hard-coding a single vendor.

### 3. Medical document processing

- Accept PDF, TXT, common text MIME types, and images through the system picker.
- Extract text, send the medical extraction prompt, validate/pretty-print returned JSON, and save it separately from source metadata.
- Show processing progress and recoverable error states.

### 4. Core patient tools

- Info: render the structured medical summary.
- Chat: use saved medical context and persist separate chat history JSON.
- Medical tracker: generate/edit up to the configured number of questions, complete a daily questionnaire, and append dated answers to questionnaire-history JSON.

### 5. Research discovery

- Search PubMed through NCBI E-utilities and open result links externally.
- Search EU CTIS trials using the public API, display concise result cards, and support condition text derived from the medical summary.

### 6. Verification

- Compile with the Gradle wrapper and run unit checks where available.
- Exercise first launch, profile persistence, each JSON file, document-picker result paths, malformed LLM responses, offline errors, and screen navigation.
- Confirm no credentials or patient content are written to logs.

## Acceptance criteria

- A fresh install starts at LLM setup; a configured install starts at the main screen.
- OpenAI, Groq, LM Studio, Ollama OpenAI-compatible, llama.cpp, and custom endpoints can be represented without code changes.
- Imported data, processed data, tracker definition/history, chat history, and configuration live in separate private JSON files.
- Medical extraction and questionnaire generation require JSON-only LLM output and handle invalid output safely.
- All six presentation menu destinations are interactive.
- Network and parsing failures produce actionable UI messages rather than crashes.
- The project builds successfully with `./gradlew assembleDebug`.

## Deferred production hardening

- Encrypt API credentials and sensitive health files using Android Keystore-backed encryption.
- Add explicit consent, retention/export/delete controls, accessibility testing, localization, and a reviewed privacy policy.
- Validate OCR language packs and PDF extraction across a representative device/document matrix.
- Add clinician-reviewed safety copy and regulatory assessment before any real-world medical use.
