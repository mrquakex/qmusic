# QMusic: Architecture & Design Specification

**Date:** 2026-10-23  
**Status:** Approved for Implementation Planning  
**Target:** Greenfield Android & Desktop Music Application (Android MVP First)  

---

## 1. Intent & Context

The goal is to build **QMusic** — an ultra-fast, modern, ad-free music streaming application that leverages YouTube Music as a backend, with optional YouTube Music account sync and Spotify playlist import. While referencing the battle-tested audio playback, extractor logic, and streaming mechanics of SimpMusic, QMusic is built cleanly from scratch.

QMusic contains:
- **Zero traces** of SimpMusic, third-party promotional dialogs, or developer donation popups.
- **No anti-tamper / unofficial build locks** that hinder customization.
- **Custom, state-of-the-art UI/UX** designed with Jetpack Compose / Compose Multiplatform (Material 3 Expressive base with AMOLED mode, custom bottom player sheet, and animated waveforms).
- **YouTube Account Sync & Spotify Import**:
  - Optional Google/YouTube Music login via WebView cookie capture (`SAPISID` -> `SAPISIDHASH`) to fetch liked songs, personal library, and custom playlists.
  - Spotify playlist importer: Paste any Spotify playlist URL or connect via Spotify WebView to import and map tracks to high-bitrate YouTube Music streams.
- **Configurable update infrastructure** pointing to QMusic's own GitHub releases or custom API endpoint.
- **Modern tech stack**: Kotlin 2.1+, Compose Multiplatform 1.7+, Media3 1.5.1, Ktor 3.x, Koin 4.x.

---

## 2. Goals, Non-Goals, & Anti-Goals

### Goals
- **High-Performance Audio Streaming:** Instant playback start, DASH stream handling, seamless chunk caching, and background playback via Android `MediaLibraryService`.
- **Completely Ad-Free:** Direct YouTube audio streaming without any sponsor banners, audio ads, or promotional interruptions.
- **Bespoke Modern UI:** Clean, fluid user interface designed for music lovers (Dynamic dominant color theming, gesture-based queue reordering, and dark/AMOLED themes).
- **Core Music Features:**
  - YouTube Music Search (songs, albums, artists, playlists) + instant suggestions.
  - YouTube Account Integration: Access liked songs and remote playlists.
  - Spotify Playlist Importer: Fetch tracks from Spotify playlists and stream them seamlessly.
  - Synced and unsynced lyrics (LRCLIB, Kugou, YouTube).
  - 10-Band Software Equalizer (Robert Bristow-Johnson Audio EQ Cookbook biquad filters with preamp headroom).
  - DJ-Style Equal-Power Crossfade with dual-stage 4th order LPF/HPF frequency sweeps.
  - Local playback history and custom user playlists (Room KMP).
  - Offline audio downloads with embedded ID3 metadata tags (artwork, title, artist).
- **Self-Controlled Update Engine:** Clean, non-intrusive update checker querying QMusic's own repository or endpoint, with one-click direct APK download/install prompt.

### Non-Goals
- Proprietary vendor-specific hacks (e.g., Origin Island for specific phone brands).
- Third-party star/rating nagging dialogs (e.g. Footguns promotion).
- Legacy Android support below Android 7.0 (API 24). Focus is on modern Android (API 26-35+) and Desktop (JVM).

### Anti-Goals
- **No Code Bloat:** Do not carry forward unused legacy classes, duplicated modules, or dead code from referenced projects.
- **No Hidden Locks:** Avoid any hardcoded certificate fingerprint checks or package name locks.
- **No Sluggishness:** Strict adherence to asynchronous non-blocking I/O, lazy-loaded lists, and minimal memory footprints during audio playback.

---

## 3. Technical Architecture & Component Design

```
qmusic/
├── androidApp/             # Android application entry, AndroidManifest, MediaLibraryService
├── composeApp/             # Shared UI & ViewModels (Compose Multiplatform)
│   ├── src/commonMain/     # UI screens, design tokens, navigation, state holders
│   ├── src/androidMain/    # Android-specific UI & platform bridges (WebView for login)
│   └── src/desktopMain/    # Desktop-specific UI & platform bridges (Phase 2)
├── core/
│   ├── network/            # Ktor 3.x HTTP client, headers, proxy handling, Brotli
│   ├── yt-engine/          # YouTube Music scraper, Innertube client, Faraday QuickJS cipher resolver
│   ├── spotify/            # Spotify playlist scraper & track mapper
│   ├── player/             # Media3 ExoPlayer wrapper, playback queue, cache manager, EQ & crossfade
│   ├── database/           # Room KMP (playlists, favorites, cache index, history)
│   └── updater/            # QMusic update verification & release checker
└── gradle/                 # Modern Gradle 8.x+, Kotlin 2.x, Version Catalog (libs.versions.toml)
```

### 3.1. Account & Playlist Synchronization
- **YouTube Login**: In-app secure WebView loads Google sign-in. Intercepts cookies for `music.youtube.com` (`SAPISID`, `__Secure-3PAPISID`, `SSID`). Calculates `SAPISIDHASH` on every authenticated request to load personal playlists and liked songs.
- **Spotify Importer**: Reads public Spotify playlist URLs via Ksoup/Ktor or user token to parse track metadata (Artist, Title, ISRC), matching each track against YouTube Music's catalog with 99%+ accuracy.

### 3.2. Core Audio & Playback Engine (`core:player`)
- **Android Media3 Architecture**:
  - `SimpleMediaService`: Extends `MediaLibraryService` (`foregroundServiceType="mediaPlayback"`).
  - `DelegatingForwardingPlayer`: Reflection-based delegate swapper that enables continuous `MediaSession` connection across multiple simultaneous ExoPlayer instances.
  - Notification controls: Custom buttons (Like, Repeat, Shuffle, Radio) integrated into `DefaultMediaNotificationProvider` with system binder bitmap size safeguards.
- **Audio DSP Chain**:
  - `EqualizerAudioProcessor`: 10-band peaking biquad filters (31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz) with preamp gain compensation.
  - `CrossfadeExoPlayerAdapter`: Equal-power sinusoidal curve fading + dynamic exponential LPF/HPF filter sweeps for seamless DJ mixing.
- **Dual-Layer Caching**:
  - `PLAYER_CACHE` (LRU cache with user-configurable size limit) + `DOWNLOAD_CACHE` (No-eviction persistent storage).
  - `ResolvingDataSource`: Uses 5 MiB chunk boundaries with cache validity verification.

### 3.3. Identity & Rebranding
- **App Name:** QMusic
- **Package ID:** `com.qmusic.app`
- **Application ID:** `com.qmusic.app`
- **Namespace:** `com.qmusic.app`
