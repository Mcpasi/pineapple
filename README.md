# Pineapple

A native Android music app in Kotlin: type a song, get results, tap, and it
plays — with a continuous mix building behind whatever you picked.

> **Status: playback works.** Search, prefetch, thumbnails, continuous queue and
> audio are all verified on device — `state=3` with the position advancing in
> real time. The 403 that blocked audio for weeks is fixed; see
> [The 403, and the fix](#the-403-and-the-fix), because the real cause was the
> opposite of every theory it was blamed on.

---

## What works

| Area | State |
|---|---|
| Search | 19 results in ~1.2 s via InnerTube `/search` |
| **Playback** | **Verified on device: `state=3`, position advancing in real time, whole file** |
| Stream-URL prefetch | Top 5 resolved while you read the list; **5/5** on device |
| Album art | Loads — was broken by main-thread HTTP, now fixed |
| Playback threading | Wrong-thread crash fixed and verified (`onMain {}`) |
| Continuous queue | Up to 6 related tracks enqueued behind the first |
| UI | SoundCloud-style light theme: white ground, orange `#FF5500`, hairline dividers |
| APK size | 72.6 MB → **12.4 MB** after fixing stale incremental packaging |
| Unit tests | 12/12 passing, pinning YouTube's exact response schemas |
| Library (in progress) | Likes / playlists / recents persisted, **not yet wired into the screen** |

## What does not work

- **The Library and NowPlaying screens are not mounted** — they compile, but
  `TorrentTunesScreen` still renders the single search column.
- **Discovery tab** — placeholder only.
- **PO tokens / WebView embed / bounded-range shim** — all three are gone on
  purpose; none of them is needed now. See [the fix](#the-403-and-the-fix).

---

## The 403, and the fix

For weeks the app resolved a stream URL and then ExoPlayer died with a bare
`Response code: 403`. It looked like a network problem — the URL is bound to
the requesting IP, the phone is dual-stack — and the first measurements looked
like a **1 MiB cap** on the file.

**Both theories were wrong.** The gate is the **player client that issued the
URL**, not the network, not the region, and not the byte count.

Measured 2026-10-20 from one IP, in the same minute, same video
(`Ky-QenzQD6U`, 4.7 MB audio), fresh URL per request:

| range | ANDROID 20.10.38 | VISIONOS 1.02 |
|---|---|---|
| no `Range` (whole file) | **403** | **200** — read all 4738365 bytes |
| `bytes=0-65535` | 206 | 206 |
| `bytes=1048576-2097151` | **403** | 206 |
| `bytes=3000000-4194303` | **403** | 206 |

The "1 MiB cap" was an artifact of always measuring ANDROID URLs; a later
theory blaming a bot-flagged IP was disproven by replaying the phone's exact
URL from two machines. On the device itself `src=` and `boundIp=` matched
byte for byte, so IP binding was satisfied the whole time.

Region is not the cause either: the working VISIONOS URL **still carries
`gcr=in`**, and no proof-of-origin token is involved (`pot` is absent).

### The fix

`InnerTube` now leads with **`VISIONOS 1.02`** (client id `101`) and stamps the
player request with `X-Goog-Visitor-Id`. Nothing else changed — no WebView, no
JS engine, no proof-of-origin token, no new dependency.

One requirement matters: drop **`X-Goog-Visitor-Id`** and VISIONOS answers
`LOGIN_REQUIRED`. `Origin`, `playbackContext`, and the client's
`userAgent`/`timeZone`/`utcOffsetMinutes` are each optional — measured
individually. Every InnerTube response hands back a visitor id, including the
rejected `/player` response, so `InnerTube.captureVisitor` usually learns it
from the search that precedes a tap, and `tryResolve` retries exactly once if
it started with none. That makes the first play of a cold session self-healing.

### Approaches tried before it

1. **`BoundedRangeDataSource`** — a correct segmented range reader, and
   *deleted*. It could only request what the server allowed, and ANDROID URLs
   were allowed exactly 1 MiB of it.
2. **Proof-of-origin token (BotGuard)** — the theoretically correct answer, and
   what every real downloader uses for gated clients. Prototyped end to end on
   a PC (`Create` → run the VM → `GenerateIT`): YouTube handed back only a
   `websafeFallbackToken`, and the BotGuard VM never published a WebPO minter.
   **Moot** — VISIONOS needs no token at all, `pot` is absent from working URLs.
   (LuanRT/BgUtils and rustypipe-botguard are both MIT, if that path is ever
   needed again for a different client.)
3. **WebView + published IFrame API** in a synthetic `loadDataWithBaseURL`
   document — **error 152**, "cannot be played in an embedded player", even on
   videos reporting `playableInEmbed=true`.
4. **WebView navigating to the real embed URL** — error 152 gone, loads clean,
   but no audio because autoplay was blocked without a user gesture.

### Every other client was ruled out on the same music video

| client | result |
|---|---|
| `ANDROID 20.10.38` | resolves a URL — but googlevideo serves only the first 1 MiB |
| `ANDROID 21.37.36` | ciphered URLs only |
| `IOS 19.45.4` | fails to resolve |
| `WEB`, `MWEB`, `WEB_REMIX` | `UNPLAYABLE` |
| `TVHTML5`, `TVEMBEDDED`, `ANDROID_VR`, `IOS_MUSIC`, `ANDROID_MUSIC`, `ANDROID_CREATOR` | `LOGIN_REQUIRED` or `ERROR` |
| **`VISIONOS 1.02`** | **`OK` — 23/23 formats with direct URLs, whole file served** |

The stream URL carries no `pot` token, so nothing about proof-of-origin ever
applied. yt-dlp independently confirms the direction: as of `2026.08.19` its
default client is `visionos`, and it is listed with **no** `GVS_PO_TOKEN_POLICY`
at all — `pot=False`, default client pool `('visionos','web')`.

### Why this looked like three other things

Because the symptom was a bare `Response code: 403`, and three plausible
explanations each *did* reproduce something real:

- **IP binding** — the URL does bind to the requesting IP. But `src=` and
  `boundIp=` matched byte for byte on the device, and replaying the phone's own
  URL from a PC failed the same way. Binding was never violated.
- **Regional gating** — false. The working VISIONOS URL still carries `gcr=in`.
- **A 1 MiB cap** — real, but only ever observed on ANDROID URLs, because those
  were the only ones being measured.

The measurements are worth keeping because each hypothesis was cheap to test
and the obvious one (BotGuard) turned out to be required by *none* of them.

---

## Next steps

Ordered by value:

1. **Wire the library into the UI.** `LibraryStore` and `LibraryViewModel` are
   built and compile, but `LibraryScreen` and `Navigation` are **not yet
   mounted** — `TorrentTunesScreen` still renders the single search column.
   The task is to add the bottom `AppNavBar` with Discovery / Search / Library
   tabs and route to `LibraryScreen`.

2. **Mount `NowPlayingScreen`.** Written, not yet reachable. Needs a
   `currentTrack` flow on `MainViewModel` (set in `playResult`) so the like
   toggle knows which track it is acting on — the parameter already exists on
   the composable.

3. **Discovery tab content.** Currently a placeholder. Natural options: trending
   InnerTube results, or last-played.

4. **Background/offline policy.** Per requirement: *nothing is downloaded
   permanently unless the user explicitly makes an offline playlist*. Enforce
   this when a download path is eventually added — `LibraryStore` today stores
   only references, never media.

5. **Keep the client list alive.** If playback ever 403s again, the first
   question is "did YouTube retire `VISIONOS 1.02`?" — every number and the
   client matrix above are the baseline to re-run against
   (`probeClients.ps1`, `probeMatrix.ps1`, `probeRange.ps1`).

*Not* on the list: the WebView player, the bounded-range shim, and PO tokens.
All three are solved or superseded.

---

## Architecture

```
app/src/main/java/com/torrenttunes/app/
├── di/AppModule.kt            ExoPlayer; fetches googlevideo under the client's own UA
├── youtube/InnerTube.kt       Hand-written InnerTube client (search / next / player)
├── search/                    SearchResult, AudioStream, HttpURLConnection helper
├── player/
│   ├── PlayerController.kt    Thread confinement: every call goes through onMain {}
│   └── WebViewAudioPlayer.kt  IFrame-embed prototype — superseded, kept for reference
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
- **VISIONOS for `/player`, WEB for `/search` and `/next`.** VISIONOS returns
  plain URLs with no `signatureCipher` *and* is the one client googlevideo
  serves in full; WEB encrypts them behind a cipher needing a JS engine.
  `PLAYER_CLIENTS` in `InnerTube.kt` is the single place to bump when YouTube
  rotates client versions, and it leads with the client that works.
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
