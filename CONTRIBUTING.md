# Contributing to Pineapple

Pineapple streams YouTube music with as little ceremony as possible. It is a
personal, sideloaded project — **it cannot be published to Google Play**,
because it uses YouTube's private InnerTube API without an API key. The same
technique is used by NewPipe. Read [Legal](README.md#legal) before you start.

## The two rules that shape everything else

**1. No new dependencies.**

This is not a style preference, it is a constraint the app is built around:

- `NewPipeExtractor` is GPLv3 and would make the whole app copyleft.
  `rustypipe-botguard` is MIT but drags in `deno_core` and JSDOM — roughly
  50 MB of engine — for a token that turned out to be unnecessary.
- The APK is **12.5 MB**, down from 72.6 MB after stale incremental packaging
  was found shipping ~60 MB of untracked bytes. Every dependency is a fight
  against that number.

So InnerTube is hand-written Kotlin, HTTP is `HttpURLConnection`, and every
icon is drawn on a `Canvas`. If you need a capability, write it. If you think
you have found a dependency worth breaking the rule for, open an issue and
argue it — do not just add it.

**2. Stability outranks novelty.**

Playback that starts and keeps going is the product. A clever client that
works for a week is worse than a plain one that works for a month. When the
two conflict, take the boring option.

## Setting up

- JDK 17
- Android SDK with `ANDROID_HOME` set
- A device or emulator on API 26+ (that is where adaptive icons arrive, so
  there are no PNG fallbacks to maintain)

`dev.ps1` in the project root covers the day-to-day:

```
.\dev.ps1 run | build | test | install | logs | devices | pair | clean
```

## Building

```
.\gradlew.bat clean assembleDebug testDebugUnitTest --console=plain
```

**Always `clean`.** Incremental packaging once shipped ~60 MB of untracked
bytes — a 72.6 MB APK holding 12.3 MB of entries. The debug build is the one
you use day to day; `dev.ps1 build` does the clean for you.

Release builds (`assembleRelease`) run R8 with resource shrinking and land at
**1.9 MB**. See [Release signing](#release-signing).

## Testing

### Unit tests

`app/src/test/.../youtube/InnerTubeParsingTest.kt` — these are the app's real
safety net. They pin YouTube's exact response schemas, which is the part that
breaks without warning. When a parse changes behaviour, add a fixture that
captures the shape you saw, not a description of it.

`org.json:json` is a test dependency because Android ships `org.json` as a
stub that throws `not mocked` on every call.

```
.\gradlew.bat testDebugUnitTest
```

### On a device

The unit tests cannot tell you whether music plays. They tell you whether the
parsing that finds music still matches what YouTube returns.

**Never trust the `Play: started` log line.** It records that playback was
*requested*, not that audio moved. Verify it properly:

```
adb shell dumpsys media_session | grep -E "state=|position"
```

You want `state=3` (playing) **and** a `position` that advances across two
readings a few seconds apart. A `position` that stays at `0:00` while the log
says `started` means the stream URL resolved and then stalled.

### Before you drive someone's phone

```
adb shell "dumpsys window | grep mCurrentFocus"
```

If that does not name your activity, the app never reached the foreground —
the keyguard may be up, or the user may be in another app. Check before every
tap. `wm dismiss-keyguard` cannot unlock a PIN-protected device.

## Diagnosing a broken build

When playback stops working, the question is almost always *"did YouTube
retire a client?"*. The whole playback path rests on one claim: **VISIONOS
(client 101, version 1.02) is the only client googlevideo serves a full file
to.** Other clients answer `206` for the first byte range and `403` for the
next.

Two probes reproduce the diagnosis without a phone:

```
python tools/probe_clients.py          # which player clients still work
python tools/probe_trending.py         # does Discovery's chart still parse
```

`probe_clients.py` deliberately asks for a second byte range, because a
client can hand back a perfectly usable-looking URL and then 403 everything
past the first megabyte. A probe that only checks for a URL will tell you a
gated client is fine.

Both mirror the corresponding Kotlin parsers. **If a probe and the app
disagree, trust the app** — the probe is the thing to fix.

## Conventions

- **Keep the client list in step.** `PLAYER_CLIENTS` in `InnerTube.kt` is the
  one place client versions live, and `tools/probe_clients.py` mirrors it.
  Change one, check the other.
- **Comment the *why*, not the *what*.** The interesting comments in this
  codebase explain a decision that was expensive to reach — why VISIONOS, why
  a whitelist and not a blacklist, why there is no PoToken. A comment
  restating the code is noise, and a comment explaining a choice nobody can
  revisit is worse than none.
- **Test fixtures mirror captured traffic.** Not idealised shapes — the real
  thing, because the real thing is what breaks.
- **Whitelists over blacklists** for anything YouTube-classified. A blacklist
  leaks: user uploads are easy to name, but podcast episodes arrive typed
  `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` and videos-shelf rows often carry no type
  at all.
- **Degrade, do not fail.** Discovery falls back to the first carousel rather
  than showing nothing; search falls back to user uploads rather than an empty
  list. Prefer showing the user something imperfect to showing them an error.

## Release signing

Release builds are signed with the **debug key** when `keystore.properties` is
absent, which is the default on a fresh clone. That is deliberate: without it,
`assembleRelease` fails and nobody ever tests the minified build — and an
untested minified build is where R8 surprises live.

To sign properly, create `keystore.properties` (it is gitignored, and so are
`*.jks` and `*.keystore`):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> **Back the keystore up.** Android requires every update to be signed by the
> same key. Lose it and you can never update an existing install again — only
> ask people to uninstall and reinstall, losing their library. There is no
> recovery.

Until a keystore exists, keep installing the **debug** build, which is what
your current install already uses. Installing a release build signed with a
different key is refused as an update, and would mean uninstalling first.

## Pull requests

Keep them small and single-purpose. A change that touches both a parser and a
screen is two changes that cannot be reverted independently.

Say what you verified, and how — "23/23 unit tests" and "confirmed playing via
`dumpsys media_session`, position advanced 0:04 → 0:11" are useful; "should
work" is not.
