# AppFunctions Testing Agent

AppFunctions Testing Agent is a testing and debugging tool for Android AppFunctions.
It allows developers to troubleshoot integration issues, manually invoke functions with
deterministic input, and test functions using an LLM-based agent.

## Prerequisites

-   **ADB**: Ensure you have `adb` installed and in your PATH.
-   **Device/Emulator**: A connected Android device or emulator with developer options enabled.

## Building and Running

To build, install, and launch the app with elevated privileges (required for some AppFunctions APIs),
use `run_privileged.sh` on macOS/Linux or `run_privileged.ps1` on Windows.

### Usage

#### macOS/Linux

```bash
./run_privileged.sh [OPTIONS]
```

#### Windows PowerShell

```powershell
.\run_privileged.ps1 [OPTIONS]
```

### Options

-   `-b, --build`: Force a rebuild of the app before installing.
-   `-r, --release`: Use the release build type instead of debug.
-   `-f, --flavor FLAVOR`: Specify the build flavor (`standard` or `retail`). Defaults to `standard`.
-   `-k, --api-key KEY`: The Gemini API key (required for `retail` builds if not set elsewhere).
-   `-h, --help`: Show the help message.

On Windows, use the PowerShell parameter names `-Build`, `-Release`, `-Flavor`,
`-ApiKey`, `-Serial`, and `-Help`.
If the PowerShell execution policy blocks the script, run it with
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run_privileged.ps1 -Build`.

### Examples

**Standard Debug Build**
```bash
./run_privileged.sh --build
```

**Standard Debug Build on Windows**
```powershell
.\run_privileged.ps1 -Build
```

**Retail Debug Build with API Key**
```bash
./run_privileged.sh --build --flavor retail --api-key YOUR_API_KEY
```

**Retail Debug Build on Windows**
```powershell
.\run_privileged.ps1 -Build -Flavor retail -ApiKey YOUR_API_KEY
```

## Voice in Agent Demo

Tap the microphone next to the message field to open Android's speech recognition
dialog. The recognized text replaces the current selection or is inserted at the
cursor; tap Send when ready. Canceling leaves the draft unchanged.

New assistant replies are read aloud automatically while the chat is open, using
the device language. Stop cancels the current reading and queued speech; subsequent
new replies are still read aloud. The checkbox above the messages enables or
disables automatic reading; disabling it also stops playback. Its state survives
screen recreation, but is not a persistent app setting. Re-enabling it does not
read replies received while it was disabled.

Long-press a message bubble to open its actions: Read aloud, Copy, or Select text
for copying part of the message. Manual reading works even with automatic reading
disabled and replaces current and queued speech. Image-only messages cannot be read.
Opening the microphone, changing conversations,
or leaving/backgrounding the chat stops playback. Loaded chat history is not read.

The device must have a speech recognition app and a text-to-speech engine with
voice data for the device language. Some emulator images do not include these
services. Processing may use the network depending on the selected system provider;
offline operation is not guaranteed. No additional Gemini API is used for voice.

## Testing

Always run relevant tests to verify your changes before committing.

### Unit Tests
Run unit tests for all variants:
```bash
./gradlew test
```
Or for a specific variant:
```bash
./gradlew testStandardDebugUnitTest
./gradlew testRetailDebugUnitTest -PGEMINI_API_KEY=YOUR_API_KEY
```

### Instrumented Tests
Run instrumented tests on a connected device:
```bash
./gradlew connectedAndroidTest
```
*Note: Some instrumented tests require a Gemini API key.
Pass it using `-Pandroid.testInstrumentationRunnerArguments.gemini_api_key=YOUR_API_KEY`.*

### Screenshot Tests
Verify UI changes with screenshot tests:
```bash
./gradlew updateDebugScreenshotTest
```

## Releasing (Bundling)

To create a distributable test suite bundle, use the `bundle.sh` script.
This script creates a zip file containing the APK, instructions, and a launch script.

### Usage

```bash
./bundle.sh [OPTIONS]
```

### Options

-   `-o, --output-dir DIR`: Directory to save the bundle (default: current directory).
-   `-r, --release`: Build the release version instead of debug.
-   `-f, --flavor FLAVOR`: Specify the build flavor (`standard` or `retail`). Defaults to `standard`.
-   `-k, --api-key KEY`: The Gemini API key (required for `retail` builds).

### Examples

**Bundle Standard Debug Version**
```bash
./bundle.sh
```

**Bundle Retail Debug Version**
```bash
./bundle.sh --flavor retail --api-key YOUR_API_KEY
```
