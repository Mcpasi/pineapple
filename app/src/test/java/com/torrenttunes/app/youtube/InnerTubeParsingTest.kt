package com.torrenttunes.app.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact InnerTube response shapes this app parses.
 *
 * These fixtures mirror payloads captured from youtube.com on 2026-10-19. The
 * parser is the single most fragile part of the app — when YouTube changes a
 * schema it breaks silently, with no exception and no crash, just an empty
 * list. A failing test here is the difference between "the app broke and I
 * know" and "the app broke and I found out from a user."
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
    fun `counts report -1 when unparsable`() {
        assertEquals(-1L, InnerTube.parseCount(""))
        assertEquals(-1L, InnerTube.parseCount("views"))
    }

    // ------------------------------------------------------------ fixtures

    private companion object {

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
