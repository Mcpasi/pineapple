package com.torrenttunes.app.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact InnerTube response shapes this app parses.
 *
 * These fixtures mirror payloads captured from youtube.com on 2026-10-19 and
 * from YouTube Music (WEB_REMIX) on 2026-10-20. The parser is the single most
 * fragile part of the app — when YouTube changes a schema it breaks silently,
 * with no exception and no crash, just an empty list. A failing test here is
 * the difference between "the app broke and I know" and "the app broke and I
 * found out from a user."
 */
class InnerTubeParsingTest {

    // ------------------------------------------------------------ search

    @Test
    fun `search extracts id title channel duration and views`() {
        val results = InnerTube.parseSearch(JSONObject(SEARCH_FIXTURE))
        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("j5-yKhDd64s", first.videoId)
        assertEquals("Eminem - Not Afraid", first.title)
        assertEquals("Eminem", first.channel)
        assertEquals("4:19", first.durationLabel)
        assertEquals(259L, first.durationSec)
        assertEquals(1234567890L, first.views)
        assertEquals("https://i.ytimg.com/vi/j5-yKhDd64s/mqdefault.jpg", first.thumbUrl)
    }

    @Test
    fun `live streams are dropped because they have no resolvable audio`() {
        val results = InnerTube.parseSearch(JSONObject(SEARCH_FIXTURE))
        assertTrue(results.none { it.videoId == "liveVideoId" })
    }

    @Test
    fun `non-video renderer entries are skipped`() {
        val results = InnerTube.parseSearch(JSONObject(SEARCH_FIXTURE))
        assertTrue(results.none { it.title.contains("ad") })
    }

    @Test
    fun `search tolerates a missing container instead of throwing`() {
        assertTrue(InnerTube.parseSearch(JSONObject("{}")).isEmpty())
        assertTrue(InnerTube.parseSearch(JSONObject("""{"contents":{}}""")).isEmpty())
    }

    @Test
    fun `search tolerates missing optional fields on a video`() {
        val results = InnerTube.parseSearch(JSONObject(BARE_VIDEO_FIXTURE))
        assertEquals(1, results.size)
        val v = results[0]
        assertEquals("bareId", v.videoId)
        assertEquals("Bare Title", v.title)
        assertEquals("", v.channel)
        assertEquals("", v.durationLabel)
        assertEquals(0L, v.durationSec)
        assertEquals(-1L, v.views)
    }

    // ------------------------------------------------------- music search

    @Test
    fun `music search keeps official uploads ahead of user renditions`() {
        val results = InnerTube.parseMusicSearch(JSONObject(MUSIC_FIXTURE))
        assertEquals(2, results.size)
        // Both ATV rows, in YouTube's order...
        assertEquals("xTvyyoF_LZY", results[0].videoId)
        assertEquals("DEYoQZnze80", results[1].videoId)
        // ...and the slowed + reverb file behind them.
        assertTrue(results.none { it.videoId == "mt0olxzbS28" })
    }

    @Test
    fun `music search drops rows that are not tracks`() {
        val results = InnerTube.parseMusicSearch(JSONObject(MUSIC_FIXTURE))
        // The artist row has no watchEndpoint, so there is no videoId to play.
        assertTrue(results.none { it.videoId.isEmpty() })
        assertTrue(results.none { it.title == "Ed Sheeran" })
    }

    @Test
    fun `music search extracts title artist and play count`() {
        val first = InnerTube.parseMusicSearch(JSONObject(MUSIC_FIXTURE))[0]
        assertEquals("Shape of You", first.title)
        assertEquals("Ed Sheeran", first.channel)
        assertEquals("9.5B plays", first.viewsLabel)
        assertEquals(9_500_000_000L, first.views)
        // YT Music shows plays rather than a clock for official songs.
        assertEquals("", first.durationLabel)
        assertEquals(0L, first.durationSec)
        assertEquals("https://i.ytimg.com/vi/xTvyyoF_LZY/mqdefault.jpg", first.thumbUrl)
    }

    @Test
    fun `music search falls back to user uploads when nothing official exists`() {
        val results = InnerTube.parseMusicSearch(JSONObject(MUSIC_UGC_ONLY_FIXTURE))
        assertEquals(1, results.size)
        assertEquals("mt0olxzbS28", results[0].videoId)
        assertEquals("4:35", results[0].durationLabel)
        assertEquals(275L, results[0].durationSec)
    }

    @Test
    fun `plain-text artist column ignores view counts and durations`() {
        // A user upload with no linked artist page, whose metadata column is
        // only "Video • 7.6M views • 4:35". The fallback used to take whatever
        // followed the first separator, crediting the track to "7.6M views".
        val row = plainRow(
            title = "Hamari Anganaw",
            videoId = "yViCc5kRnj4",
            musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
            metadata = "Video \u2022 7.6M views \u2022 4:35",
        )
        val results = InnerTube.parseMusicSearch(JSONObject(shelfOf(row)))
        assertEquals(1, results.size)
        // Every remaining piece is something YouTube already labelled, so
        // there is no name to show — which is better than the wrong one.
        assertEquals("", results[0].channel)
        assertEquals("4:35", results[0].durationLabel)
    }

    @Test
    fun `plain-text artist column still picks up a real name`() {
        val row = plainRow(
            title = "Some Song",
            videoId = "abc12345",
            musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
            metadata = "Video \u2022 Sanity Tapes \u2022 4:35",
        )
        val results = InnerTube.parseMusicSearch(JSONObject(shelfOf(row)))
        assertEquals("Sanity Tapes", results[0].channel)
    }

    @Test
    fun `videos shelf rows and podcast episodes are not official`() {        // Two ways junk used to get through: a reaction video that arrives
        // with no musicVideoType at all, and a podcast episode that arrives
        // with a type we had simply never heard of.
        val results = InnerTube.parseMusicSearch(JSONObject(MUSIC_VIDEOS_SHELF_FIXTURE))
        assertEquals(1, results.size)
        assertEquals("officialId", results[0].videoId)
        assertTrue(results.none { it.videoId == "reactionId" })
        assertTrue(results.none { it.videoId == "podcastId" })
    }

    @Test
    fun `music search tolerates a missing container instead of throwing`() {
        assertTrue(InnerTube.parseMusicSearch(JSONObject("{}")).isEmpty())
        assertTrue(InnerTube.parseMusicSearch(JSONObject("""{"contents":{}}""")).isEmpty())
    }

    @Test
    fun `trending picks the Trending carousel and keeps YouTube's order`() {
        // Nesting is deliberately arbitrary: collectNodes walks by name, which
        // is the whole reason this parse survives YouTube reshuffling pages.
        val browse = """
            {"contents":{"arbitrary":[
              ${carousel("New albums & singles", "")},
              ${carousel(
                  "Trending",
                  plainRow("First", "firstId", "MUSIC_VIDEO_TYPE_ATV", "Song \u2022 An Artist") + "," +
                      plainRow("Second", "secondId", "MUSIC_VIDEO_TYPE_ATV", "Song \u2022 An Artist"),
              )}
            ]}}
        """.trimIndent()

        val results = InnerTube.parseTrending(JSONObject(browse))
        assertEquals(listOf("firstId", "secondId"), results.map { it.videoId })
        assertEquals("First", results[0].title)
        assertEquals("An Artist", results[0].channel)
    }

    @Test
    fun `trending tolerates a missing container instead of throwing`() {
        assertTrue(InnerTube.parseTrending(JSONObject("{}")).isEmpty())
        assertTrue(InnerTube.parseTrending(JSONObject("""{"contents":{}}""")).isEmpty())
    }

    // ------------------------------------------------------------ related

    @Test
    fun `related extracts videos from the nested lockup schema`() {
        val results = InnerTube.parseRelated(JSONObject(RELATED_FIXTURE))
        assertEquals(1, results.size)
        assertEquals("S9bCLPwzSC0", results[0].videoId)
        assertEquals("Eminem - Lose Yourself", results[0].title)
    }

    @Test
    fun `related skips playlists and channels`() {
        val results = InnerTube.parseRelated(JSONObject(RELATED_FIXTURE))
        assertTrue(results.none { it.videoId == "PLplaylistId" })
    }

    @Test
    fun `related tolerates a missing container instead of throwing`() {
        assertTrue(InnerTube.parseRelated(JSONObject("{}")).isEmpty())
    }

    // -------------------------------------------------------- primitives

    @Test
    fun `clock parses single and multi part durations`() {
        assertEquals(259L, InnerTube.parseClock("4:19"))
        assertEquals(3723L, InnerTube.parseClock("1:02:03"))
        assertEquals(59L, InnerTube.parseClock("0:59"))
    }

    @Test
    fun `clock returns zero for anything unparsable`() {
        assertEquals(0L, InnerTube.parseClock(""))
        assertEquals(0L, InnerTube.parseClock("LIVE"))
        assertEquals(0L, InnerTube.parseClock("4:19:20:11"))
    }

    @Test
    fun `counts parse abbreviated and full forms`() {
        assertEquals(1_200_000_000L, InnerTube.parseCount("1.2B views"))
        assertEquals(853_000L, InnerTube.parseCount("853K views"))
        assertEquals(1_234L, InnerTube.parseCount("1,234 views"))
        assertEquals(12L, InnerTube.parseCount("12 views"))
    }

    @Test
    fun `counts parse YT Music play counts`() {
        // YouTube Music says "plays" where the web client says "views".
        assertEquals(9_500_000_000L, InnerTube.parseCount("9.5B plays"))
        assertEquals(853_000L, InnerTube.parseCount("853K plays"))
    }

    @Test
    fun `counts report -1 when unparsable`() {
        assertEquals(-1L, InnerTube.parseCount(""))
        assertEquals(-1L, InnerTube.parseCount("views"))
    }

    // ------------------------------------------------------- artist catalogue

    @Test
    fun `artists-only search returns artists with page ids and no tracks`() {
        val results = InnerTube.parseMusicSearch(resourceFixture("search_artists_portishead.json"))
        assertEquals(5, results.size)
        assertTrue(results.all { it.isArtist })
        assertTrue(results.all { it.videoId.isEmpty() })

        val portishead = results[0]
        assertEquals("Portishead", portishead.title)
        assertEquals("UCweAx0TIRdAylp6G1nviqbQ", portishead.browseId)
        assertEquals("Artist", portishead.channel)
        assertEquals("7.83M monthly audience", portishead.viewsLabel)
        assertTrue(portishead.thumbUrl.startsWith("https://"))
        assertTrue(portishead.dedupeKey.startsWith("browse:"))

        // Without the browse: prefix both artist rows would key "" and a
        // duplicate LazyColumn key is a crash, not a wrinkle.
        assertEquals(results.size, results.map { it.dedupeKey }.distinct().size)
    }

    @Test
    fun `mixed search keeps the top-result artist first and related artists last`() {
        val results = InnerTube.parseMusicSearch(resourceFixture("search_portishead.json"))
        assertTrue(results.isNotEmpty())

        // The top-result card is YouTube's own answer: it leads.
        val top = results[0]
        assertTrue(top.isArtist)
        assertEquals("Portishead", top.title)
        assertEquals("UCweAx0TIRdAylp6G1nviqbQ", top.browseId)
        assertEquals("7.83M monthly audience", top.viewsLabel)

        // The music the query was for still occupies the middle of the list.
        // Exact count: this fixture yields 8 ATV/OMV rows — pinning the number
        // is the point, because a silent drop here is how search goes quiet.
        val tracks = results.filter { it.videoId.isNotEmpty() }
        assertEquals(8, tracks.size)
        // ...and related artists sit behind it, never above it.
        assertTrue(results.any { it.isArtist && it.title == "Massive Attack" })
        val lastTrack = results.indexOfLast { it.videoId.isNotEmpty() }
        val lastArtist = results.indexOfLast { it.isArtist }
        assertTrue(lastArtist > lastTrack)

        assertEquals(results.size, results.map { it.dedupeKey }.distinct().size)
    }

    @Test
    fun `artist detail parses the header and official top songs`() {
        val detail = checkNotNull(
            InnerTube.parseArtistDetail(resourceFixture("artist_portishead.json")),
        )
        assertEquals("Portishead", detail.name)
        assertEquals("7.83M monthly audience", detail.audienceLabel)
        assertTrue(detail.bannerUrl.startsWith("https://"))

        assertEquals(5, detail.topSongs.size)
        assertEquals("Glory Box", detail.topSongs[0].title)
        assertTrue(detail.topSongs.all { it.videoId.isNotEmpty() })
        assertTrue(detail.topSongs.none { it.isArtist })

        // Release carousels were already fetched; keep them rather than dropping.
        assertTrue(detail.albums.isNotEmpty())
        assertTrue(detail.albums.all { it.isAlbum })
        assertTrue(detail.albums.all { it.browseId.startsWith("MPRE") })
        // Singles carousel is merged into the same list.
        assertTrue(detail.albums.size >= 5)
        // Dedupe identity: an album door has no video id but a unique browse id.
        assertEquals(detail.albums.size, detail.albums.map { it.dedupeKey }.distinct().size)

        assertTrue(detail.videos.isNotEmpty())
        assertTrue(detail.videos.all { it.videoId.isNotEmpty() })
        assertTrue(detail.videos.none { it.isArtist || it.isAlbum })

        assertTrue(detail.relatedArtists.isNotEmpty())
        assertTrue(detail.relatedArtists.all { it.isArtist })
        val related = detail.relatedArtists.first { it.title == "Massive Attack" }
        assertEquals("UCr1uxon7aFztw_dnrau0TDA", related.browseId)
        assertEquals("51.9M monthly audience", related.viewsLabel)

        // The description shelf is the artist's bio (real text, not fabricated).
        assertTrue(detail.description.contains("Portishead"))
        assertTrue(detail.description.contains("Bristol"))
    }

    @Test
    fun `artist detail reports an unknown payload instead of an empty page`() {
        // No header and no Top songs shelf means the schema moved; a silent
        // empty page would read as "this artist has nothing".
        assertEquals(null, InnerTube.parseArtistDetail(JSONObject("{}")))
    }

    @Test
    fun `mixed search keeps album doors with page ids and correct subtitles`() {
        val results = InnerTube.parseMusicSearch(resourceFixture("search_portishead.json"))
        val albums = results.filter { it.isAlbum }
        // The album rows this payload carries — kept, not dropped at the guard.
        assertEquals(3, albums.size)
        // An album door is never mistaken for a playable track or an artist.
        assertTrue(albums.all { it.videoId.isEmpty() })
        assertTrue(albums.none { it.isArtist })
        assertTrue(albums.all { it.browseId.startsWith("MPRE") })
        assertTrue(albums.all { it.dedupeKey.startsWith("browse:") })

        val dummy = albums.first { it.title == "Dummy" }
        assertEquals("MPREb_G6JQUdInW98", dummy.browseId)
        // Subtitle reads `Album • Portishead • 1994`: artist then year.
        assertEquals("Portishead", dummy.channel)
        assertEquals("1994", dummy.viewsLabel)
        assertTrue(dummy.thumbUrl.startsWith("https://"))

        assertEquals(results.size, results.map { it.dedupeKey }.distinct().size)
    }

    @Test
    fun `album detail parses the header and ordered track list`() {
        val detail = checkNotNull(
            InnerTube.parseAlbumDetail(resourceFixture("album_portishead.json")),
        )
        assertEquals("Dummy", detail.title)
        assertEquals("Portishead", detail.artistName)
        assertEquals("UCweAx0TIRdAylp6G1nviqbQ", detail.artistBrowseId)
        assertEquals("Album", detail.kindLabel)
        assertEquals("1994", detail.yearLabel)
        assertEquals("11 songs", detail.trackCountLabel)
        assertEquals("49 minutes", detail.durationLabel)
        assertTrue(detail.artUrl.startsWith("https://"))

        assertEquals(11, detail.tracks.size)
        assertEquals("Mysterons", detail.tracks[0].title)
        // Every track is playable and official, in YouTube's own order.
        assertTrue(detail.tracks.all { it.videoId.isNotEmpty() })
        assertTrue(detail.tracks.none { it.isArtist || it.isAlbum })
        assertEquals(detail.tracks.size, detail.tracks.map { it.dedupeKey }.distinct().size)
    }

    @Test
    fun `album detail reports an unknown payload instead of an empty page`() {
        assertEquals(null, InnerTube.parseAlbumDetail(JSONObject("{}")))
    }

    /** A captured WEB_REMIX payload from `src/test/resources`. */
    private fun resourceFixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/$name")) {
            "missing fixture $name - src/test/resources must be on the test classpath"
        }.bufferedReader(Charsets.UTF_8).readText()
        return JSONObject(text)
    }

    // ------------------------------------------------------------ fixtures

    private companion object {

        /**
         * One `musicResponsiveListItemRenderer`: a title run carrying its
         * watchEndpoint, plus a single metadata column.
         *
         * Built rather than pasted as a fixture because these exist only to
         * exercise the metadata-column fallback, and a 30-line JSON blob would
         * bury the one line that actually matters.
         */
        private fun plainRow(
            title: String,
            videoId: String,
            musicVideoType: String,
            metadata: String,
        ): String {
            // " • " separates facts inside a column, and it has to be a run of
            // its own — dropping it fuses everything into one string, which is
            // what the parser then sees as a single unparseable fact.
            val pieces = metadata.split(" \u2022 ")
            val metaRuns = pieces.flatMapIndexed { index, piece ->
                buildList {
                    if (index > 0) add("""{"text":" \u2022 "}""")
                    add("""{"text":${JSONObject.quote(piece)}}""")
                }
            }.joinToString(",")
            return """
                {"musicResponsiveListItemRenderer":{"flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                    {"text":${JSONObject.quote(title)},"navigationEndpoint":{"watchEndpoint":{
                      "videoId":${JSONObject.quote(videoId)},
                      "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{
                        "musicVideoType":${JSONObject.quote(musicVideoType)}
                      }}
                    }}}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$metaRuns]}}}
                ]}}
            """.trimIndent()
        }

        /** Wraps rows in the shelf shape `parseMusicSearch` expects. */
        private fun shelfOf(vararg rows: String): String =
            """{"contents":{"musicShelfRenderer":{"contents":[${rows.joinToString(",")}]}}}"""

        /** One explore-page carousel, headed however YouTube headed it. */
        private fun carousel(title: String, rows: String): String = """
            {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{
                "title":{"runs":[{"text":${JSONObject.quote(title)}}]}
              }},
              "contents":[$rows]
            }}
        """.trimIndent()

        const val SEARCH_FIXTURE = """
        {
          "contents": {
            "twoColumnSearchResultsRenderer": {
              "primaryContents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "itemSectionRenderer": {
                        "contents": [
                          {
                            "videoRenderer": {
                              "videoId": "j5-yKhDd64s",
                              "title": { "runs": [ { "text": "Eminem - Not Afraid" } ] },
                              "ownerText": { "runs": [ { "text": "Eminem" } ] },
                              "lengthText": { "simpleText": "4:19" },
                              "viewCountText": { "simpleText": "1,234,567,890 views" }
                            }
                          },
                          {
                            "videoRenderer": {
                              "videoId": "liveVideoId",
                              "title": { "runs": [ { "text": "Eminem Radio Live" } ] },
                              "badges": [ { "metadataBadgeRenderer": { "label": "LIVE" } } ]
                            }
                          },
                          { "adRenderer": { "whatever": "ignored" } },
                          {
                            "videoRenderer": {
                              "videoId": "secondId",
                              "title": { "simpleText": "Second result" },
                              "ownerText": { "runs": [ { "text": "SomeChannel" } ] },
                              "lengthText": { "simpleText": "3:33" },
                              "shortViewCountText": { "simpleText": "853K views" }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """

        const val BARE_VIDEO_FIXTURE = """
        {
          "contents": {
            "twoColumnSearchResultsRenderer": {
              "primaryContents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "itemSectionRenderer": {
                        "contents": [
                          {
                            "videoRenderer": {
                              "videoId": "bareId",
                              "title": { "runs": [ { "text": "Bare Title" } ] }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """

        /**
         * A YouTube Music shelf exactly as captured: an official song, a
         * user-uploaded rendition of the same query, a second official song,
         * and an artist row that carries no videoId at all.
         */
        const val MUSIC_FIXTURE = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "itemSectionRenderer": {
                    "contents": [
                      {
                        "musicShelfRenderer": {
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          {
                                            "text": "Shape of You",
                                            "navigationEndpoint": {
                                              "watchEndpoint": {
                                                "videoId": "xTvyyoF_LZY",
                                                "watchEndpointMusicSupportedConfigs": {
                                                  "watchEndpointMusicConfig": {
                                                    "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          { "text": "Song" },
                                          { "text": " \u2022 " },
                                          {
                                            "text": "Ed Sheeran",
                                            "navigationEndpoint": {
                                              "browseEndpoint": {
                                                "browseId": "UClmXPfaYhXOYsNn_QUyheWQ",
                                                "browseEndpointContextSupportedConfigs": {
                                                  "browseEndpointContextMusicConfig": {
                                                    "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": { "runs": [ { "text": "9.5B plays" } ] }
                                    }
                                  }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          {
                                            "text": "Ed Sheeran - Shape of You (slowed and reverb)",
                                            "navigationEndpoint": {
                                              "watchEndpoint": {
                                                "videoId": "mt0olxzbS28",
                                                "watchEndpointMusicSupportedConfigs": {
                                                  "watchEndpointMusicConfig": {
                                                    "musicVideoType": "MUSIC_VIDEO_TYPE_UGC"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          { "text": "Video" },
                                          { "text": " \u2022 " },
                                          { "text": "sanity" }
                                        ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          { "text": " \u2022 " },
                                          { "text": "4:35" }
                                        ]
                                      }
                                    }
                                  }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          {
                                            "text": "Shape of You",
                                            "navigationEndpoint": {
                                              "watchEndpoint": {
                                                "videoId": "DEYoQZnze80",
                                                "watchEndpointMusicSupportedConfigs": {
                                                  "watchEndpointMusicConfig": {
                                                    "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  },
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          { "text": "Song" },
                                          { "text": " \u2022 " },
                                          { "text": "Ed Sheeran" }
                                        ]
                                      }
                                    }
                                  }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "flexColumns": [
                                  {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          {
                                            "text": "Ed Sheeran",
                                            "navigationEndpoint": {
                                              "browseEndpoint": {
                                                "browseId": "UClmXPfaYhXOYsNn_QUyheWQ",
                                                "browseEndpointContextSupportedConfigs": {
                                                  "browseEndpointContextMusicConfig": {
                                                    "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """

        /** The same query on a search where YouTube Music only has UGC results. */
        const val MUSIC_UGC_ONLY_FIXTURE = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": {
                                  "runs": [
                                    {
                                      "text": "Ed Sheeran - Shape of You (slowed and reverb)",
                                      "navigationEndpoint": {
                                        "watchEndpoint": {
                                          "videoId": "mt0olxzbS28",
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": {
                                              "musicVideoType": "MUSIC_VIDEO_TYPE_UGC"
                                            }
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": {
                                  "runs": [
                                    { "text": "Video" },
                                    { "text": " \u2022 " },
                                    { "text": "sanity" }
                                  ]
                                }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": {
                                  "runs": [
                                    { "text": " \u2022 " },
                                    { "text": "4:35" }
                                  ]
                                }
                              }
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """

        /**
         * An official song alongside the two kinds of junk that used to leak
         * through: a videos-shelf row with **no** `musicVideoType` (live
         * sessions, reaction uploads) and a podcast episode typed with a
         * classification we had simply never heard of.
         */
        const val MUSIC_VIDEOS_SHELF_FIXTURE = """
        {
          "contents": {
            "musicShelfRenderer": {
              "contents": [
                {
                  "musicResponsiveListItemRenderer": {
                    "flexColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": {
                            "runs": [
                              {
                                "text": "Namunaaste",
                                "navigationEndpoint": {
                                  "watchEndpoint": {
                                    "videoId": "officialId",
                                    "watchEndpointMusicSupportedConfigs": {
                                      "watchEndpointMusicConfig": {
                                        "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                      }
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      },
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": {
                            "runs": [
                              { "text": "Song" },
                              { "text": " \u2022 " },
                              { "text": "Seedhe Maut" }
                            ]
                          }
                        }
                      }
                    ]
                  }
                },
                {
                  "musicResponsiveListItemRenderer": {
                    "flexColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": {
                            "runs": [
                              {
                                "text": "LIVE REACTION to Seedhe Maut's new song",
                                "navigationEndpoint": {
                                  "watchEndpoint": { "videoId": "reactionId" }
                                }
                              }
                            ]
                          }
                        }
                      },
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": {
                            "runs": [
                              { "text": "Video" },
                              { "text": " \u2022 " },
                              { "text": "Some Random Channel" }
                            ]
                          }
                        }
                      }
                    ]
                  }
                },
                {
                  "musicResponsiveListItemRenderer": {
                    "flexColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": {
                            "runs": [
                              {
                                "text": "@raftaarmusic Talks About THE REAL STATE Of Indian Hip Hop",
                                "navigationEndpoint": {
                                  "watchEndpoint": {
                                    "videoId": "podcastId",
                                    "watchEndpointMusicSupportedConfigs": {
                                      "watchEndpointMusicConfig": {
                                        "musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
                                      }
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      },
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": {
                            "runs": [
                              { "text": "Episode" },
                              { "text": " \u2022 " },
                              { "text": "The Ranveer Show" }
                            ]
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """

        const val RELATED_FIXTURE = """
        {
          "contents": {
            "twoColumnWatchNextResults": {
              "secondaryResults": {
                "secondaryResults": {
                  "results": [
                    {
                      "lockupViewModel": {
                        "contentId": "S9bCLPwzSC0",
                        "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                        "metadata": {
                          "lockupMetadataViewModel": {
                            "title": { "content": "Eminem - Lose Yourself" }
                          }
                        }
                      }
                    },
                    {
                      "lockupViewModel": {
                        "contentId": "PLplaylistId",
                        "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                        "metadata": {
                          "lockupMetadataViewModel": {
                            "title": { "content": "A playlist that must be skipped" }
                          }
                        }
                      }
                    },
                    { "shelfRenderer": { "whatever": "ignored" } }
                  ]
                }
              }
            }
          }
        }
        """
    }
}
