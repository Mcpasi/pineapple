#!/usr/bin/env python3
"""Which InnerTube player clients still work?

The whole playback path rests on one claim: **VISIONOS (client 101, version
1.02) is the only client googlevideo serves a full file to.** Every other
client either hands back `signatureCipher` (which needs a JS engine to
decrypt — deliberately not shipped) or answers `206` for the first range and
`403` for the next.

That claim is the thing most likely to break. Run this when playback stops:

    python tools/probe_clients.py
    python tools/probe_clients.py dQw4w9WgXcQ

It prints one row per client, in the same order `PLAYER_CLIENTS` tries them,
so a regression shows up as a column changing rather than as a stack trace on
a phone.

Requires no dependencies beyond the standard library.

Arguments
---------
videoId  A public music video. Defaults to a long-lived one; override it if
         the default ever gets region-locked or removed.
"""

import json
import sys
import urllib.error
import urllib.request

KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
ENDPOINT = "https://www.youtube.com/youtubei/v1/"
DEFAULT_VIDEO_ID = "kJQP7kiw5Fk"  # Luis Fonsi — Despacito; stable and public

# Mirrors `PLAYER_CLIENTS` in InnerTube.kt, in try order. Keep the two in step:
# the point of this script is to predict what the app will do.
CLIENTS = [
    dict(
        name="VISIONOS", version="1.02", clientNameId="101",
        userAgent="Mozilla/5.0 (Apple Vision Pro; watchOS) AppleWebKit/605.1.15 "
                  "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
        clientExtras={
            "deviceMake": "Apple", "deviceModel": "RealityDevice17,1",
            "osName": "visionOS", "osVersion": "26.5.23O471",
        },
    ),
    dict(
        name="ANDROID", version="20.10.38", clientNameId="3",
        userAgent="com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
        clientExtras={"androidSdkVersion": 30},
    ),
    dict(
        name="ANDROID_VR", version="1.43.32", clientNameId="28",
        userAgent="com.google.android.apps.youtube.vr.oculus/1.43.32 "
                  "(Linux; U; Android 12; en_US; Quest 3; "
                  "Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
        clientExtras={
            "androidSdkVersion": 32, "osName": "Android", "osVersion": "12",
            "deviceMake": "Oculus", "deviceModel": "Quest 3",
        },
    ),
    dict(
        name="ANDROID", version="21.37.36", clientNameId="3",
        userAgent="com.google.android.youtube/21.37.36 (Linux; U; Android 11) gzip",
        clientExtras={"androidSdkVersion": 30},
    ),
    dict(
        name="IOS", version="19.45.4", clientNameId="5",
        userAgent="com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
        clientExtras={"deviceModel": "iPhone16,2"},
    ),
]

EXTRAS = {"contentCheckOk": True, "racyCheckOk": True}


def post(url, payload, headers, timeout=25):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(), method="POST", headers=headers
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def best_audio(formats):
    """Highest-bitrate audio-only format — same pick the app makes."""
    audio = [
        f for f in (formats or [])
        if isinstance(f, dict) and str(f.get("mimeType", "")).startswith("audio/")
    ]
    if not audio:
        return None
    return max(audio, key=lambda f: int(f.get("bitrate") or 0))


def probe_ranges(url, user_agent):
    """Does a second byte range survive? This is the real question.

    A client can answer `status: OK` and hand back a perfectly plain URL, then
    403 the moment you ask for a range past the first megabyte. That is
    exactly how ANDROID 20.10.38 fails, and it is invisible to a probe that
    only checks whether a URL came back at all — which is why this one asks
    twice, using the same offsets the app's own notes record as the failing
    case.
    """
    out = {}
    for label, offset in (("first", 0), ("late", 1_048_576)):
        req = urllib.request.Request(
            url,
            headers={
                "Range": f"bytes={offset}-{offset + 65_535}",
                "User-Agent": user_agent,
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                out[label] = resp.status
        except urllib.error.HTTPError as e:
            out[label] = e.code
        except Exception as e:  # noqa: BLE001
            out[label] = type(e).__name__
    return out


def probe(video_id, visitor_id=None):
    results = []
    for c in CLIENTS:
        client = {
            "clientName": c["name"], "clientVersion": c["version"],
            "hl": "en", "gl": "US",
        }
        client.update(c["clientExtras"])

        payload = {"context": {"client": client}, "videoId": video_id}
        payload.update(EXTRAS)

        headers = {
            "Content-Type": "application/json",
            "X-Youtube-Client-Name": c["clientNameId"],
            "X-Youtube-Client-Version": c["version"],
            "User-Agent": c["userAgent"],
        }
        if visitor_id:
            headers["X-Goog-Visitor-Id"] = visitor_id

        row = {"client": f'{c["name"]} {c["version"]}', "ok": False}

        try:
            root = post(f"{ENDPOINT}player?key={KEY}", payload, headers)
        except urllib.error.HTTPError as e:
            row["status"] = f"HTTP {e.code}"
            results.append(row)
            continue
        except Exception as e:  # noqa: BLE001 - report, never crash the run
            row["status"] = f"transport: {type(e).__name__}"
            results.append(row)
            continue

        # Learning a visitor id is what unlocks VISIONOS; surface it once.
        visitor_id = visitor_id or root.get("responseContext", {}).get("visitorData")

        playability = root.get("playabilityStatus") or {}
        row["status"] = playability.get("status") or "?"
        row["reason"] = playability.get("reason") or ""

        best = best_audio((root.get("streamingData") or {}).get("adaptiveFormats"))
        if best is None:
            row["url"] = "none"
            results.append(row)
            continue

        # A ciphered URL cannot be used without a JS engine, which this app
        # deliberately does not ship — so it counts as a failure.
        row["url"] = "ciphered" if "signatureCipher" in best else "plain"
        row["mime"] = str(best.get("mimeType", "")).split(";")[0]

        if row["url"] == "plain" and row["status"] == "OK" and best.get("url"):
            ranges = probe_ranges(best["url"], c["userAgent"])
            row["ranges"] = f'{ranges["first"]}/{ranges["late"]}'
            # Usable only if the *late* range survives too. A 206 on the first
            # request and a 403 on the second is the exact failure that made
            # playback stop, and it is invisible to a probe that only asks for
            # a URL.
            row["ok"] = ranges["first"] == 206 and ranges["late"] == 206
        else:
            row["ranges"] = "-"
        results.append(row)

    return results, visitor_id


def main():
    video_id = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_VIDEO_ID
    print(f"videoId = {video_id}\n")

    # A visitor id is learned from whichever client answers first; the app
    # does the same on its first search, so mimic it by running twice.
    results, visitor_id = probe(video_id)
    if visitor_id:
        results, visitor_id = probe(video_id, visitor_id)

    print(f"{'client':<24} {'status':<16} {'url':<9} {'ranges':<9} {'usable'}")
    print("-" * 72)
    for r in results:
        print(
            f'{r["client"]:<24} {r.get("status", "?"):<16} '
            f'{r.get("url", "-"):<9} {r.get("ranges", "-"):<9} '
            f'{"YES" if r.get("ok") else "no"}'
        )
        if r.get("reason"):
            print(f'{"":<24} -> {r["reason"][:70]}')

    print()
    print("ranges = first/late byte-range status codes; both need to be 206.")
    print("A 403 on the late one is the signature of a gated client.")

    working = [r["client"] for r in results if r.get("ok")]
    print()
    if working:
        print(f"Usable clients: {', '.join(working)}")
    else:
        print("NO USABLE CLIENT — playback is broken. YouTube has likely")
        print("retired one or more client versions; bump PLAYER_CLIENTS in")
        print("InnerTube.kt and re-run this script.")

    visionos = next((r for r in results if r["client"].startswith("VISIONOS")), None)
    if visionos and not visionos.get("ok"):
        print("\nVISIONOS specifically failed. That is the client playback")
        print("depends on, so this is the case that stops songs playing at all.")
        print(f'  status={visionos.get("status")} url={visionos.get("url")}')


if __name__ == "__main__":
    main()
