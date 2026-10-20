#!/usr/bin/env python3
"""Does Discovery's trending parse still find the chart?

Mirrors `parseTrending` / `parseMusicRow` in InnerTube.kt against live
traffic, so a schema change shows up here as a shorter list rather than as an
empty Discovery tab on a phone.

    python tools/probe_trending.py

Run it whenever Discovery comes back thin or blank, or after any change to
the row parser. The two are deliberately kept in step: if this script and the
Kotlin disagree, trust the Kotlin and fix the script.

Requires no dependencies beyond the standard library.
"""

import json
import time
import urllib.error
import urllib.request

KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
ENDPOINT = "https://www.youtube.com/youtubei/v1/"
EXPLORE_BROWSE_ID = "FEmusic_explore"

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
HEADERS = {
    "Content-Type": "application/json",
    "User-Agent": UA,
    "X-Youtube-Client-Name": "67",
    "X-Youtube-Client-Version": "1.20250310.01.00",
}
CLIENT = {
    "clientName": "WEB_REMIX",
    "clientVersion": "1.20250310.01.00",
    "hl": "en",
    "gl": "US",
}

OFFICIAL_TYPES = {"MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"}


def post(endpoint, payload, tries=4):
    url = f"{ENDPOINT}{endpoint}?key={KEY}"
    body = json.dumps(payload).encode()
    last = None
    for attempt in range(tries):
        req = urllib.request.Request(url, data=body, method="POST", headers=HEADERS)
        try:
            return json.loads(urllib.request.urlopen(req, timeout=30).read().decode()), None
        except urllib.error.HTTPError as e:
            return None, f"HTTP {e.code}: {e.read()[:160].decode('utf8', 'replace')}"
        except Exception as e:  # transient: dropped socket, timeout
            last = e
            time.sleep(1.5 * (attempt + 1))
    return None, f"transport: {last}"


# --------------------------------------------------------------- the parsers

def collect_nodes(node, kind, out):
    """Depth-first collection of every node named `kind`.

    Walking by name rather than by path is the whole reason these parses
    survive YouTube reshuffling its pages, so it is worth mirroring exactly.
    """
    if isinstance(node, dict):
        if kind in node and isinstance(node[kind], dict):
            out.append(node[kind])
        for v in node.values():
            collect_nodes(v, kind, out)
    elif isinstance(node, list):
        for v in node:
            collect_nodes(v, kind, out)


def runs_text(node):
    """Text from an object that may carry `runs`, `content` or `simpleText`."""
    if not isinstance(node, dict):
        return ""
    if isinstance(node.get("runs"), list):
        return "".join(r.get("text", "") for r in node["runs"] if isinstance(r, dict))
    for key in ("content", "simpleText"):
        if isinstance(node.get(key), str):
            return node[key]
    return ""


def column_text(column):
    return runs_text(column.get("text") if isinstance(column, dict) else None)


def parse_clock(text):
    parts = text.strip().split(":")
    if not 1 < len(parts) < 4 or not all(p.strip().isdigit() for p in parts):
        return 0
    total = 0
    for p in parts:
        total = total * 60 + int(p)
    return total


def is_artist_run(run):
    if not isinstance(run, dict):
        return False
    page = ((run.get("navigationEndpoint") or {}).get("browseEndpoint") or {})
    page = page.get("browseEndpointContextSupportedConfigs") or {}
    return (page.get("browseEndpointContextMusicConfig") or {}).get("pageType") == "MUSIC_PAGE_TYPE_ARTIST"


def parse_music_row(row):
    """One `musicResponsiveListItemRenderer`, as the app reads it."""
    flex = row.get("flexColumns")
    if not isinstance(flex, list):
        return None

    columns = []
    for col in flex:
        if isinstance(col, dict) and isinstance(col.get("musicResponsiveListItemFlexColumnRenderer"), dict):
            columns.append(col["musicResponsiveListItemFlexColumnRenderer"])
    if not columns:
        return None

    title = column_text(columns[0])
    if not title:
        return None

    endpoint = None
    runs = (columns[0].get("text") or {}).get("runs")
    if isinstance(runs, list) and runs:
        endpoint = (runs[0].get("navigationEndpoint") or {}).get("watchEndpoint")
    if not endpoint or not endpoint.get("videoId"):
        overlay = row.get("overlay") or {}
        overlay = overlay.get("musicItemThumbnailOverlayRenderer") or {}
        button = (overlay.get("content") or {}).get("musicPlayButtonRenderer") or {}
        endpoint = (button.get("playNavigationEndpoint") or {}).get("watchEndpoint")

    video_id = (endpoint or {}).get("videoId") or ""
    if not video_id:
        return None  # an artist, album or playlist header — nothing playable

    config = ((endpoint or {}).get("watchEndpointMusicSupportedConfigs") or {})
    music_type = (config.get("watchEndpointMusicConfig") or {}).get("musicVideoType") or ""

    channel = duration_label = views_label = ""
    for i in range(1, len(columns)):
        column_runs = (columns[i].get("text") or {}).get("runs")
        if isinstance(column_runs, list):
            for run in column_runs:
                if not channel and is_artist_run(run):
                    text = run.get("text", "")
                    if text.strip():
                        channel = text
        # A column holds several facts separated by " • "; check each piece,
        # or "Video • 4:24" never parses as a clock.
        for piece in column_text(columns[i]).split("•"):
            text = piece.strip()
            if not text:
                continue
            if not duration_label and ":" in text and parse_clock(text) > 0:
                duration_label = text
            elif not views_label and "plays" in text.lower():
                views_label = text

    # Some rows give the artist as plain text rather than a browse link.
    # Skip pieces YouTube has already labelled, or a user upload with no
    # linked artist gets credited to "7.6M views".
    if not channel and len(columns) > 1:
        for piece in column_text(columns[1]).split("•"):
            text = piece.strip()
            if not text:
                continue
            if "plays" in text.lower() or "views" in text.lower():
                continue
            if ":" in text and parse_clock(text) > 0:
                continue
            if text.lower() in ("song", "video", "episode"):
                continue
            channel = text
            break

    return {
        "title": title,
        "videoId": video_id,
        "channel": channel,
        "duration": duration_label,
        "official": music_type in OFFICIAL_TYPES,
    }


def carousel_title(carousel):
    """A carousel's heading.

    Read straight off the title object, NOT through a helper that looks for a
    nested `text` wrapper — a header carries its runs directly, and looking
    for the wrapper returns an empty string, which makes the shelf look
    untitled and drops it. That bug shipped once already.
    """
    header = (carousel.get("header") or {}).get("musicCarouselShelfBasicHeaderRenderer")
    if not isinstance(header, dict):
        return ""
    title = header.get("title")
    if isinstance(title, dict):
        if isinstance(title.get("runs"), list):
            text = "".join(r.get("text", "") for r in title["runs"] if isinstance(r, dict))
            if text:
                return text
        if isinstance(title.get("content"), str) and title["content"]:
            return title["content"]
    return header.get("simpleText") or ""


def parse_trending(root):
    shelves = []
    collect_nodes(root, "musicCarouselShelfRenderer", shelves)

    target = None
    for shelf in shelves:
        if carousel_title(shelf).lower() == "trending":
            target = shelf
            break
    if target is None:
        target = next((s for s in shelves if carousel_title(s)), None)
    if target is None:
        return []

    rows = []
    collect_nodes(target, "musicResponsiveListItemRenderer", rows)

    out, seen = [], set()
    for row in rows:
        parsed = parse_music_row(row)
        if not parsed or parsed["videoId"] in seen:
            continue
        seen.add(parsed["videoId"])
        out.append(parsed)
    return out


def main():
    root, err = post("browse", {"context": {"client": CLIENT}, "browseId": EXPLORE_BROWSE_ID})
    if err:
        print(f"FAILED: {err}")
        raise SystemExit(1)

    shelves = []
    collect_nodes(root, "musicCarouselShelfRenderer", shelves)
    print(f"carousels found: {len(shelves)}")
    for shelf in shelves:
        rows = []
        collect_nodes(shelf, "musicResponsiveListItemRenderer", rows)
        print(f'  {carousel_title(shelf) or "(untitled)":<26} rows={len(rows)}')

    chart = parse_trending(root)
    print(f"\nparsed trending -> {len(chart)} tracks")
    for t in chart[:15]:
        flag = "" if t["official"] else "  (user upload)"
        print(f'  {t["title"][:50]:<52} {t["videoId"]:<12} {t["channel"][:24]}{flag}')

    print(f'\nblank titles : {sum(1 for t in chart if not t["title"].strip())}')
    print(f'blank channel: {sum(1 for t in chart if not t["channel"].strip())}')
    print(f'user uploads : {sum(1 for t in chart if not t["official"])}')

    if not chart:
        print("\nNO TRENDING ROWS — Discovery would show nothing. Either the")
        print("explore page changed shape, or the Trending shelf was renamed.")


if __name__ == "__main__":
    main()
