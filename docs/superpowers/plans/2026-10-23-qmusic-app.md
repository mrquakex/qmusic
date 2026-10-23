# QMusic Application Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build QMusic — an ultra-fast, modern, ad-free Android music streaming application powered by YouTube Music, with YouTube account login & Spotify playlist import, zero SimpMusic traces, clean Material 3 UI, custom update engine, and professional DSP audio playback.

**Architecture:** Clean Architecture with Compose Multiplatform presentation, Media3 ExoPlayer audio playback with dual-stage DJ crossfade & 10-band software EQ, pure Kotlin Faraday/QuickJS cipher solving engine, Spotify playlist importer, and Room KMP local storage.

**Tech Stack:** Kotlin 2.1+, Jetpack Compose / Compose Multiplatform 1.7+, AndroidX Media3 1.5.1, Ktor 3.x, Koin 4.x, Room KMP, QuickJS-kt (`io.github.dokar3:quickjs-kt`).

**Spec:** `docs/superpowers/specs/2026-10-23-qmusic-design.md`

## Global Constraints
- App Name: `QMusic`
- Package ID / Namespace: `com.qmusic.app`
- Zero traces of SimpMusic or `maxrave-dev` in any visible string, package, comment, or URL
- Android `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- Completely ad-free, sponsor-free, and telemetry-free
- Independent update checker pointing to configurable repository/server endpoint

## Review Focus
- **403 Forbidden on expired YouTube streaming URLs:** `ResolvingDataSource` re-resolves fresh stream URLs at 5 MiB chunk boundaries without interrupting playback.
- **Sudden offline / network drop during playback:** Media3 player seamlessly plays from cached chunks; UI reflects offline state without crashing.
- **QuickJS native stack overflow during deep n-transform:** Dedicated 32 MB thread stack guarantees stability without JVM `SIGSEGV`.
- **ExoPlayer multi-instance state conflicts during crossfade:** `DelegatingForwardingPlayer` safely swaps underlying delegate while suppressing premature `STATE_ENDED` to keep foreground notification alive.
- **Update checker network failure or unexpected payload:** Silently fails without interrupting audio playback or blocking the user.
- **YouTube / Spotify login cookie expiration:** Seamless fallback to guest mode when cookies are invalid or expired without disrupting offline playback.

---

### Task 1: Scaffolding Multi-Module Project Structure & Gradle Configuration

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `androidApp/build.gradle.kts`
- Create: `composeApp/build.gradle.kts`
- Create: `core/yt-engine/build.gradle.kts`
- Create: `core/spotify/build.gradle.kts`
- Create: `core/player/build.gradle.kts`
- Create: `core/database/build.gradle.kts`
- Create: `core/updater/build.gradle.kts`

**Interfaces:**
- Produces: Clean multi-module Gradle build graph with `com.qmusic.app` namespace, Android target configuration, and Version Catalog dependencies.

- [x] **Step 1: Define `gradle/libs.versions.toml` with modern dependencies**
  Specify versions for Kotlin 2.1.0, Compose Multiplatform 1.7.1, Android Gradle Plugin 8.7.2, AndroidX Media3 1.5.1, Ktor 3.0.2, Koin 4.0.0, QuickJS 1.0.15, Room 2.7.0-alpha11, OkHttp 4.12.0.
- [x] **Step 2: Configure root `settings.gradle.kts` and `build.gradle.kts`**
  Include `:androidApp`, `:composeApp`, `:core:yt-engine`, `:core:spotify`, `:core:player`, `:core:database`, `:core:updater`.
- [x] **Step 3: Configure module build files**
  Configure `androidApp/build.gradle.kts` with `applicationId = "com.qmusic.app"`, `minSdk = 26`, `targetSdk = 35`. Configure KMP targets for `composeApp` and core modules.
- [x] **Step 4: Verify Gradle configuration compiles cleanly**
  Run: `./gradlew help --dry-run` or `./gradlew tasks`
  Expected: BUILD SUCCESSFUL
- [x] **Step 5: Commit scaffolding**
  `git add gradle/ build.gradle.kts settings.gradle.kts */build.gradle.kts`
  `git commit -m "chore: scaffold qmusic multi-module build configuration"`

---

### Task 2: Core YouTube Scraper & Faraday Cipher Engine (`core:yt-engine`)

**Files:**
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/cipher/QuickJsEngine.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/cipher/FaradayCipherEngine.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/cipher/ZemerCipherSolver.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/cipher/RemotePlayerConfigStore.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/cipher/RemotePlayerConfigParser.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/models/YouTubeClient.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/models/ITAG.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/models/SongItem.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/Ytmusic.kt`
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/YouTube.kt`
- Test: `core/yt-engine/src/commonTest/kotlin/com/qmusic/engine/YtEngineTest.kt`

**Interfaces:**
- Produces: `YouTube` facade:
  ```kotlin
  class YouTube(private val ytmusic: Ytmusic, private val cipherEngine: FaradayCipherEngine) {
      suspend fun search(query: String, filter: SearchFilter?): Result<SearchResult>
      suspend fun getSongInfo(videoId: String): Result<SongInfo>
      suspend fun resolveStreamUrls(videoId: String): Result<List<AudioStream>>
  }
  ```

- [ ] **Step 1: Write test for YouTube search and stream URL resolution**
  Test searching for a query and resolving playable audio streams with itags 251/140.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:yt-engine:test`
  Expected: FAIL with "Unresolved reference: YouTube"
- [ ] **Step 3: Implement QuickJS cipher engine with 32 MB thread stack**
  Create `QuickJsEngine` allocating a dedicated single thread with `32 * 1024 * 1024` bytes stack and mock DOM globals for YouTube `base.js`.
- [ ] **Step 4: Implement `FaradayCipherEngine` and `ZemerCipherSolver`**
  Port player hash extraction, `player_configs.json` parsing, and `_cipherSigFunc` / `_nTransformFunc` script injection.
- [ ] **Step 5: Implement `Ytmusic` Innertube client and `YouTube` facade**
  Implement Ktor 3.x requests for `/search`, `/browse`, `/next`, and `/player`.
- [ ] **Step 6: Run test to verify it passes**
  Run: `./gradlew :core:yt-engine:test`
  Expected: PASS
- [ ] **Step 7: Commit yt-engine**
  `git add core/yt-engine/`
  `git commit -m "feat(yt-engine): implement pure kotlin youtube scraper and faraday cipher solver"`

---

### Task 3: YouTube Account Sync & Spotify Playlist Importer (`core:spotify` & `core:yt-engine`)

**Files:**
- Create: `core/yt-engine/src/commonMain/kotlin/com/qmusic/engine/auth/YouTubeAuth.kt`
- Create: `core/spotify/src/commonMain/kotlin/com/qmusic/spotify/SpotifyImporter.kt`
- Create: `core/spotify/src/commonMain/kotlin/com/qmusic/spotify/model/SpotifyTrack.kt`
- Create: `core/spotify/src/commonMain/kotlin/com/qmusic/spotify/model/SpotifyPlaylist.kt`
- Test: `core/spotify/src/commonTest/kotlin/com/qmusic/spotify/SpotifyImporterTest.kt`

**Interfaces:**
- Produces: `YouTubeAuth` and `SpotifyImporter`:
  ```kotlin
  class YouTubeAuth {
      fun setCookies(cookieString: String)
      fun getSapisidHash(): String?
      val isLoggedIn: Boolean
  }

  interface SpotifyImporter {
      suspend fun importPlaylistFromUrl(url: String): Result<SpotifyPlaylist>
      suspend fun matchTrackToYouTube(track: SpotifyTrack, youTube: YouTube): Result<SongItem>
  }
  ```

- [ ] **Step 1: Write test for Spotify playlist URL parsing and track extraction**
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:spotify:test`
  Expected: FAIL with "Unresolved reference: SpotifyImporter"
- [ ] **Step 3: Implement `YouTubeAuth` with `SAPISIDHASH` computation**
  Implement SHA-1 digest calculation for authenticated InnerTube user requests.
- [ ] **Step 4: Implement `SpotifyImporter` using Ktor public playlist scraper**
  Parse tracks (title, artist, album, duration) and implement match query generator for YouTube search.
- [ ] **Step 5: Run tests to verify they pass**
  Run: `./gradlew :core:spotify:test`
  Expected: PASS
- [ ] **Step 6: Commit account sync & spotify importer**
  `git add core/spotify/ core/yt-engine/`
  `git commit -m "feat(sync): implement youtube auth and spotify playlist importer"`

---

### Task 4: Media3 Audio Playback, DJ Crossfade & 10-Band EQ Engine (`core:player`)

**Files:**
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/service/SimpleMediaService.kt`
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/adapter/DelegatingForwardingPlayer.kt`
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/adapter/CrossfadeExoPlayerAdapter.kt`
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/dsp/EqualizerAudioProcessor.kt`
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/dsp/CrossfadeFilterAudioProcessor.kt`
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/dsp/BiquadFilter.kt`
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/cache/CacheProvider.kt`
- Create: `core/player/src/androidMain/kotlin/com/qmusic/player/source/ResolvingDataSource.kt`
- Create: `core/player/src/commonMain/kotlin/com/qmusic/player/PlayerController.kt`
- Test: `core/player/src/androidUnitTest/kotlin/com/qmusic/player/EqualizerTest.kt`

**Interfaces:**
- Consumes: `core:yt-engine` `AudioStream` and `SongItem`.
- Produces: `PlayerController` interface:
  ```kotlin
  interface PlayerController {
      val currentTrack: StateFlow<SongItem?>
      val isPlaying: StateFlow<Boolean>
      val playbackPosition: StateFlow<Long>
      val duration: StateFlow<Long>
      fun play(song: SongItem)
      fun pause()
      fun resume()
      fun seekTo(positionMs: Long)
      fun setEqualizerBand(bandIndex: Int, gainDb: Float)
      fun setCrossfadeDuration(seconds: Int)
  }
  ```

- [ ] **Step 1: Write unit test for 10-Band Equalizer biquad filter calculations**
  Verify RBJ peaking filter frequency response and preamp attenuation headroom.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:player:testDebugUnitTest`
  Expected: FAIL with "Unresolved reference: EqualizerAudioProcessor"
- [ ] **Step 3: Implement `BiquadFilter` and `EqualizerAudioProcessor`**
  Implement peaking biquad filters for 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz.
- [ ] **Step 4: Implement `DelegatingForwardingPlayer` and `CrossfadeExoPlayerAdapter`**
  Implement reflection delegate swapping, `suppressPlaybackEnded` notification protection, and equal-power sinusoidal volume crossfading.
- [ ] **Step 5: Implement `ResolvingDataSource` with 5 MiB chunk boundaries and `CacheProvider`**
  Configure LRU `SimpleCache` (`PLAYER_CACHE`) and persistent `DOWNLOAD_CACHE`.
- [ ] **Step 6: Implement `SimpleMediaService` (MediaLibraryService)**
  Configure foreground notification provider, session token, and media button receiver.
- [ ] **Step 7: Run tests to verify they pass**
  Run: `./gradlew :core:player:testDebugUnitTest`
  Expected: PASS
- [ ] **Step 8: Commit core:player**
  `git add core/player/`
  `git commit -m "feat(player): implement media3 playback, dj crossfade and 10-band equalizer"`

---

### Task 5: Room KMP Local Database & Offline Audio Tagging (`core:database`)

**Files:**
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/entity/SongEntity.kt`
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/entity/PlaylistEntity.kt`
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/entity/HistoryEntity.kt`
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/dao/SongDao.kt`
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/dao/PlaylistDao.kt`
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/dao/HistoryDao.kt`
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/QMusicDatabase.kt`
- Create: `core/database/src/commonMain/kotlin/com/qmusic/database/repository/MusicRepository.kt`
- Test: `core/database/src/commonTest/kotlin/com/qmusic/database/MusicRepositoryTest.kt`

**Interfaces:**
- Produces: `MusicRepository`:
  ```kotlin
  interface MusicRepository {
      fun getFavorites(): Flow<List<SongItem>>
      fun getHistory(): Flow<List<SongItem>>
      suspend fun toggleFavorite(song: SongItem)
      suspend fun recordHistory(song: SongItem)
      fun getPlaylists(): Flow<List<Playlist>>
      suspend fun createPlaylist(name: String): Long
      suspend fun addSongToPlaylist(playlistId: Long, song: SongItem)
  }
  ```

- [ ] **Step 1: Write test for Room KMP favorite toggling and playlist relations**
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:database:test`
  Expected: FAIL with "Unresolved reference: QMusicDatabase"
- [ ] **Step 3: Define Room entities and DAOs**
  Implement `SongEntity`, `PlaylistEntity`, `HistoryEntity`, and corresponding DAOs with Flow queries.
- [ ] **Step 4: Implement `QMusicDatabase` and `MusicRepositoryImpl`**
  Implement RoomDatabase constructor and repository methods.
- [ ] **Step 5: Run tests to verify they pass**
  Run: `./gradlew :core:database:test`
  Expected: PASS
- [ ] **Step 6: Commit core:database**
  `git add core/database/`
  `git commit -m "feat(database): implement room kmp local library and playlist storage"`

---

### Task 6: Self-Hosted Update Checker Engine (`core:updater`)

**Files:**
- Create: `core/updater/src/commonMain/kotlin/com/qmusic/updater/model/UpdateInfo.kt`
- Create: `core/updater/src/commonMain/kotlin/com/qmusic/updater/UpdateConfig.kt`
- Create: `core/updater/src/commonMain/kotlin/com/qmusic/updater/UpdateChecker.kt`
- Create: `core/updater/src/commonMain/kotlin/com/qmusic/updater/GithubReleaseUpdateChecker.kt`
- Test: `core/updater/src/commonTest/kotlin/com/qmusic/updater/UpdateCheckerTest.kt`

**Interfaces:**
- Produces: `UpdateChecker`:
  ```kotlin
  interface UpdateChecker {
      suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?>
  }
  ```

- [ ] **Step 1: Write test for semantic version comparison (`isNewerVersion`)**
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:updater:test`
  Expected: FAIL with "Unresolved reference: UpdateChecker"
- [ ] **Step 3: Implement `UpdateInfo` and `GithubReleaseUpdateChecker`**
  Parse GitHub releases JSON (`tag_name`, `body`, `html_url`, `assets.browser_download_url`).
- [ ] **Step 4: Implement safe non-blocking error handling**
  Ensure HTTP network timeouts and malformed JSON return `Result.failure` without crashing.
- [ ] **Step 5: Run tests to verify they pass**
  Run: `./gradlew :core:updater:test`
  Expected: PASS
- [ ] **Step 6: Commit core:updater**
  `git add core/updater/`
  `git commit -m "feat(updater): implement configurable github release update checker"`

---

### Task 7: Modern Compose UI, Spotify Importer & Login Screens (`composeApp`)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/theme/Theme.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/theme/Color.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/theme/Type.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/navigation/Screen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/component/MiniPlayer.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/component/FullPlayerSheet.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/component/EqualizerDialog.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/screen/HomeScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/screen/SearchScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/screen/LibraryScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/screen/SettingsScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/screen/YouTubeLoginScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/screen/SpotifyImportScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/qmusic/ui/App.kt`

**Interfaces:**
- Consumes: `PlayerController`, `MusicRepository`, `YouTube`, `YouTubeAuth`, `SpotifyImporter`, `UpdateChecker`.
- Produces: Top-level `QMusicApp()` composable with complete navigation and player bottom sheet.

- [ ] **Step 1: Implement Material 3 Expressive Theme with AMOLED dark support**
  Configure high-contrast dark color palette, dynamic primary color accent, and typography.
- [ ] **Step 2: Implement `MiniPlayer` bar and expandable `FullPlayerSheet`**
  Display track title, artist, high-res artwork, playback scrubber, play/pause, skip buttons, and synchronized lyrics viewer.
- [ ] **Step 3: Implement `HomeScreen`, `SearchScreen`, `LibraryScreen`, and `SettingsScreen`**
- [ ] **Step 4: Implement `YouTubeLoginScreen` and `SpotifyImportScreen`**
  Add in-app login WebView for YouTube and URL paste modal for Spotify playlist import.
- [ ] **Step 5: Wire `QMusicApp` top-level scaffold**
  Assemble navigation rail/bottom navigation, content area, persistent MiniPlayer, and update alert dialog.
- [ ] **Step 6: Commit composeApp UI**
  `git add composeApp/`
  `git commit -m "feat(ui): implement bespoke material 3 qmusic user interface with spotify import"`

---

### Task 8: Application Integration, Android Entry & Verification (`androidApp`)

**Files:**
- Create: `androidApp/src/main/AndroidManifest.xml`
- Create: `androidApp/src/main/java/com/qmusic/app/QMusicApplication.kt`
- Create: `androidApp/src/main/java/com/qmusic/app/MainActivity.kt`
- Create: `androidApp/src/main/res/values/strings.xml`
- Create: `androidApp/src/main/res/mipmap-*/ic_launcher.png`

**Interfaces:**
- Produces: Installable, fully functioning `QMusic` APK with foreground service, media session controls, and background playback.

- [ ] **Step 1: Configure `AndroidManifest.xml`**
  Add `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`. Register `SimpleMediaService`.
- [ ] **Step 2: Implement `QMusicApplication` and Koin DI bootstrap**
  Initialize Koin modules for network, yt-engine, spotify, player, database, and updater.
- [ ] **Step 3: Implement `MainActivity`**
  Set Compose content to `QMusicApp()`, handle edge-to-edge system insets, and bind `MediaController`.
- [ ] **Step 4: Build debug APK and verify compilation**
  Run: `./gradlew :androidApp:assembleDebug`
  Expected: BUILD SUCCESSFUL (outputs `androidApp/build/outputs/apk/debug/androidApp-debug.apk`)
- [ ] **Step 5: Run end-to-end lint and test verification**
  Run: `./gradlew check`
  Expected: All unit tests pass across all modules.
- [ ] **Step 6: Commit complete integration**
  `git add androidApp/`
  `git commit -m "feat(app): finalize qmusic android application entry and di bootstrap"`
