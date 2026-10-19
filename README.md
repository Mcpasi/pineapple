# Pineapple

A native Android music app in Kotlin: type a song, get results, tap, and it
plays — with a continuous mix building behind whatever you picked.

> **Status: paused with playback unsolved.** Everything up to and including a
> finished UI, working search, prefetch, thumbnails, and a persistent library
> is in place. The one thing that does **not** work is getting actual audio out
> of YouTube, for a reason that is YouTube's, not the app's. See
> [The blocker](#the-blocker) before picking this up.

---

## What works

| Area | State |
|---|---|
| Search | 19 results in ~1.2 s via InnerTube `/search` |
| Stream-URL prefetch | Top 5 resolved while you read the list; **5/5** on device |
| Album art | Loads — was broken by main-thread HTTP, now fixed |
| Playback threading | Wrong-thread crash fixed and verified (`onMain {}`) |
| Continuous queue | Up to 6 related tracks enqueued behind the first |
| UI | SoundCloud-style light theme: white ground, orange `#FF5500`, hairline dividers |
| APK size | 72.6 MB → **12.4 MB** after fixing stale incremental packaging |
| Unit tests | 12/12 passing, pinning YouTube's exact response schemas |
| Library (in progress) | Likes / playlists / recents persisted, **not yet wired into the screen** |

## What does not work

**Audio.** Tap a result and ExoPlayer dies with `Response code: 403`. This is
the whole remaining problem, and it is documented below so nobody re-derives it.

---

## The blocker

YouTube serves its ANDROID-client stream URLs **only for the first 1 MiB of a
file**, unless the request carries a proof-of-origin token. Measured on
2026-10-20, fresh URL per request, reproduced independently from a PC and from
the phone:

```
bare GET                -> 403
Range: bytes=0-         -> 403      (open-ended; ExoPlayer's normal request)
Range: bytes=0-65535    -> 206
Range: bytes=0-1048575  -> 206      <- the entire playable region
Range: bytes=0-1572863  -> 403
bytes=1048576-2097151   -> 403      <- chunk 2, never served
bytes=500000-1048575    -> 206      <- start may vary, end may not
```

The cap is on the **absolute end offset (byte 1,048,575)**, not the window
width — `bytes=500000-1048575` is a valid 1 MiB window and succeeds, while
`bytes=262144-1310719`, the same width, fails.

### Approaches tried

1. **ExoPlayer with bounded range requests** — `BoundedRangeDataSource` issues
   sequential 1 MiB chunks and presents them as one stream. The shim is
   correct; the server refuses to hand out byte 1,048,576. Playback reaches
   `state=6` (buffering) at position 0 and dies ~40 s in.
2. **WebView + published IFrame API** in a synthetic `loadDataWithBaseURL`
   document — **error 152**, "cannot be played in an embedded player", even on
   videos reporting `playableInEmbed=true`.
3. **WebView navigating to the real embed URL** — error 152 gone, loads clean,
   but no audio because autoplay was blocked without a user gesture. **This is
   the most promising path** and is where work stopped.

Every other InnerTube client was ruled out: `ANDROID 21.37.36` returns
ciphered URLs only; `IOS 19.45.4` fails to resolve; `MWEB` → `UNPLAYABLE`;
`TVHTML5` / `TVEMBEDDED` / `ANDROID_VR` → `LOGIN_REQUIRED` or `ERROR`; the
music clients (`ANDROID_MUSIC`, `IOS_MUSIC`, `WEB_REMIX`) → `ERROR`.

Adding a `X-Goog-Visitor-Id` header does **not** lift the cap. The stream URL
carries no `pot` token.

### Why Snaptube works

Snaptube embeds a WebView and lets YouTube's own `base.js` run — a trusted
browser context is simply allowed to play. Option 3 above is that approach in
its cheapest form: use the official, supported IFrame embed rather than
reproducing Google's obfuscated botguard attestation.

---

## Next steps

Ordered by value:

1. **Finish the WebView player (highest value — unblocks everything).**
   - Make the player view visible rather than 1×1, or start playback from a
     real user gesture so autoplay is permitted.
   - Drive it over `postMessage` with `enablejsapi=1` for play / pause / seek,
     and source position + duration from the same channel.
   - Reuse `WebViewAudioPlayer` (`app/src/main/java/.../player/WebViewAudioPlayer.kt`),
     which already has the JS bridge, state codes and progress ticker written.
   - Wire it behind `PlayerController` so `MainViewModel` is unchanged.

2. **Wire the library into the UI.** `LibraryStore` and `LibraryViewModel` are
   built and compile, but `LibraryScreen` and `Navigation` are **not yet
   mounted** — `TorrentTunesScreen` still renders the single search column.
   The task is to add the bottom `AppNavBar` with Discovery / Search / Library
   tabs and route to `LibraryScreen`.

3. **Mount `NowPlayingScreen`.** Written, not yet reachable. Needs a
   `currentTrack` flow on `MainViewModel` (set in `playResult`) so the like
   toggle knows which track it is acting on — the parameter already exists on
   the composable.

4. **Discovery tab content.** Currently a placeholder. Natural options: trending
   InnerTube results, or last-played.

5. **Background/offline policy.** Per requirement: *nothing is downloaded
   permanently unless the user explicitly makes an offline playlist*. Enforce
   this when a download path is eventually added — `LibraryStore` today stores
   only references, never media.

---

## Architecture

```
app/src/main/java/com/torrenttunes/app/
├── di/AppModule.kt            ExoPlayer + bounded-range data source
├── youtube/InnerTube.kt       Hand-written InnerTube client (search / next / player)
├── search/                    SearchResult, AudioStream, HttpURLConnection helper
├── player/
│   ├── PlayerController.kt    Thread confinement: every call goes through onMain {}
│   ├── BoundedRangeDataSource.kt   Segmented range reader (correct, but capped upstream)
│   └── WebViewAudioPlayer.kt  IFrame-embed player — the intended replacement
├── playback/PlaybackService.kt
├── library/
│   ├── LibraryStore.kt        Likes, playlists, recents — SharedPreferences + JSON
│   └── LibraryViewModel.kt
├── ui/
│   ├── MainActivity.kt        Scaffold, top bar, results, player bar
│   ├── MainViewModel.kt       Search / play / queue + 250 ms transport poll
│   ├── Navigation.kt          AppTab + bottom nav (not yet mounted)
│   ├── LibraryScreen.kt       Library tab + long-press actions sheet (not yet mounted)
│   ├── NowPlayingScreen.kt    Full-screen Spotify-style player (not yet mounted)
│   ├── components/SoundCloudUi.kt   Artwork, Thumbnail, Waveform, Canvas glyphs
│   └── theme/SoundCloudTheme.kt
└── util/CrashLogger.kt
```

### Deliberate decisions

- **No new dependencies.** InnerTube is written by hand rather than using
  NewPipeExtractor (GPLv3). HTTP is `HttpURLConnection`. Glyphs are Canvas.
- **ANDROID client for `/player`, WEB for `/search` and `/next`.** ANDROID
  returns plain URLs with no `signatureCipher`; WEB encrypts them behind a
  cipher needing a JS engine. `PLAYER_CLIENTS` in `InnerTube.kt` is the single
  place to bump when YouTube rotates client versions.
- **Keep YouTube's relevance order** and flag index 0 as `BEST`. Deliberately
  *not* sorted by views, which would promote louder covers.
- **Thread safety lives in `PlayerController`**, not at call sites — all player
  access funnels through `onMain {}`.
- **Always `clean assembleDebug`.** Incremental packaging once shipped ~60 MB
  of untracked bytes (72.6 MB APK for 12.3 MB of entries). `dev.ps1` does this.
- **Library persistence is SharedPreferences + JSON**, not a database — a few
  hundred rows, and it keeps the app dependency-free.

---

## Building

```powershell
.\gradlew.bat clean assembleDebug testDebugUnitTest --console=plain
```

- JDK 17, `JAVA_HOME` and `ANDROID_HOME` set permanently.
- `dev.ps1` provides `run | build | test | install | logs | devices | pair | clean`.
- Debug APK: `app\build\outputs\apk\debug\app-debug.apk` (~12.4 MB).

**Tested device:** Samsung Galaxy M21 (SM-M215F), Android 12, `arm64-v8a`,
1080x2340.

> The keyguard has a PIN, so `wm dismiss-keyguard` cannot unlock it. Check
> `dumpsys window | grep mCurrentFocus` before driving UI — if it reports
> `NotificationShade`, the app never reached the foreground.

### Tests

`app/src/test/.../youtube/InnerTubeParsingTest.kt` — 12 tests pinning the
exact response schemas (search fields, live-stream drop, non-video skip,
`lockupViewModel` nesting, clock/count parsing). `org.json:json` is added as a
test dependency because Android ships it as a stub.

---

## Naming

Branding is **Pineapple**: the launcher label, the in-app wordmark, the crash
banner and the fallback artwork letter.

Deliberately left unchanged:

- **Package / applicationId `com.torrenttunes.app`** — renaming touches every
  file's package declaration plus Hilt, the manifest and the service
  references. Worth doing as its own change, not mixed into anything else.
- **`TorrentTunesTheme`, `TorrentTunesScreen`** — internal identifiers, not
  user-visible.
- **Local folder name** — left as `TorrentTunes` to avoid disturbing Gradle's
  project paths while paused.

---

## Legal

This uses YouTube's private InnerTube API without an API key, which is outside
their Terms of Service. It is a personal, sideloaded project and **cannot be
published to Google Play**. The same technique is used by NewPipe. It will
break whenever YouTube changes its response shapes or client requirements.
