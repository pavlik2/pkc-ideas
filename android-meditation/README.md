# Android Meditation

A minimal Kotlin Android application for development in VS Code.

## Prerequisites

- JDK 17
- Android SDK Platform 33 and Build Tools 33
- VS Code with the recommended workspace extensions

Set `ANDROID_HOME` to your Android SDK directory, then build with:

```bash
./gradlew assembleDebug
```

Install on a connected device or emulator with:

```bash
./gradlew installDebug
```
