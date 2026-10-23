# QMusic

Fast, ad-free music streaming for Android, powered by YouTube Music.

## Features

- **Ad-free YouTube Music playback** with a pure-Kotlin engine (search, stream resolution, cipher solving).
- **YouTube account sign-in** to sync your own library.
- **Spotify playlist import**: paste a public playlist link and QMusic matches each track on YouTube Music.
- **10-band studio equalizer** and **DJ crossfade** between tracks.
- **Local library**: favorites, playlists and history stored on device.
- **Self-hosted updates** through GitHub releases.
- Monochrome design system with a custom icon set, animated splash and a vinyl player.

## Modules

| Module | Purpose |
|---|---|
| `androidApp` | Application, activity and Android wiring |
| `composeApp` | Compose Multiplatform UI (theme, screens, view model) |
| `core:yt-engine` | YouTube Music client, stream resolution, account auth |
| `core:player` | Media3 playback, crossfade, equalizer, caching |
| `core:database` | Room KMP local library |
| `core:spotify` | Spotify playlist importer |
| `core:updater` | Release update checker |

## Build

```
./gradlew :androidApp:assembleDebug
```

The debug APK is written to `androidApp/build/outputs/apk/debug/`.

Requirements: JDK 21, Android SDK with platform 36.
