# Echora

Echora is an Android audio dashcam. It keeps a rolling buffer of recent audio on your phone. When you want to keep something you just heard, open Echora and save the last few minutes as a WAV.

## Features

- Rolling audio buffer from 1 to 60 minutes, with a 10-minute default.
- WAV export to `Music/Echora/`, with sharing through Android.
- Optional capture of detected phone and VoIP calls. Turn off **Call recording** to pause capture during detected calls and resume afterward with the earlier buffer intact.
- Material You colors and automatic light and dark themes.
- Optional startup at boot when the privileged service is available.

## Requirements

- Android 12 or later (API 31+).
- A running Shizuku-compatible service with permission to open the requested audio sources. Start Shizuku before opening Echora, then authorize Echora when prompted.
- Root is recommended for the privileged audio sources. Ambient capture and WAV export have been tested with Shizuku Next running as root on a Samsung Galaxy S23+ (SM-S916B, Android 17). Capture availability with Shizuku running as the ADB shell depends on the device.

## Install

Download the preview APK from [GitHub Releases](https://github.com/ilbebino08/Echora/releases). The downloadable build is a debug APK intended for testing.

To build from source, install JDK 17, Android SDK Platform 36, and Android Platform Tools. Build and install the app with these commands.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you already have a build signed with another key, Android will reject an in-place update. Keep your existing installation if you need its settings or unsaved buffer.

## Use Echora

1. Start Shizuku or Shizuku Next and open Echora.
2. Tap **Grant Shizuku permission** if Echora asks for access.
3. Choose a duration and tap **Save to WAV**. Echora saves up to that much audio from the current buffer.
4. Open **Settings** to change the buffer duration, call recording, or startup options. Tap **Save changes** to apply them.

The current app interface is in Italian. Look for **Concedi il permesso Shizuku**, **Salva in WAV**, **Impostazioni**, **Registrazione chiamate**, and **Salva modifiche**.

## How capture works

Echora runs an audio recorder in a Shizuku user service and streams 16-bit mono PCM at 16 kHz into a file-backed circular buffer. The buffer is local to the app. Restarting capture starts a fresh buffer. Echora writes exported recordings to `Music/Echora/` and lets you share them through Android.

- **Ambient audio** uses Android's privileged `HOTWORD` source.
- **Phone calls** use `VOICE_CALL` when the device supports it. Both directions depend on the device and audio route.
- **VoIP calls** combine the microphone with eligible voice communication playback. The app shows when it can capture only one side. The VoIP path has been tested with a synthetic signal on the Samsung device. Audio quality in real phone and VoIP calls has yet to be verified.

Run the unit tests with `./gradlew :app:testDebugUnitTest`.

## Credits and license

Echora's source is licensed under the [MIT License](LICENSE). The privileged capture design was inspired by [Ambient Music Mod](https://github.com/KieronQuinn/AmbientMusicMod) by Kieron Quinn.
