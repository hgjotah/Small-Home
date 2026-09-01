# Contributing

## Setup

1. Install Android Studio with JDK 17 and Android SDK 36.
2. Clone the repository and create a local `local.properties` that points to the
   Android SDK; never commit that file.
3. Open the repository root as the Gradle project.
4. For firmware work, install ESP32 Arduino core 3.x, Arduino_GFX, and
   ArduinoJson 7.x.

## Development rules

- Keep Android-to-panel transport BLE-only and compatible with protocol v2.
- Treat `docs/BLE_PROTOCOL_v2.md` and the firmware implementation as one wire
  contract; update both in the same change when the contract changes.
- Preserve the existing Kotlin, Compose, coroutines, and StateFlow architecture.
- Do not add periodic CoinMarketCap refreshes.
- Do not log or commit API keys, tokens, Wi-Fi passwords, notification content,
  keystores, or personal data.
- Keep OpenRouter credentials and conversation state on Android only.

## Checks

Run before opening a pull request:

```text
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Pull requests should explain behavior changes, include tests for protocol/state
logic, and describe any hardware verification performed. Keep changes focused;
do not mix a visual redesign with transport or protocol work.

