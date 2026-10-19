package com.torrenttunes.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Plays a YouTube track by driving the official IFrame embed player inside a
 * hidden WebView.
 *
 * Why a WebView and not ExoPlayer
 * -------------------------------
 * ExoPlayer needs a plain stream URL. Since 2024 YouTube only serves the first
 * 1 MiB of one unless the request carries a proof-of-origin token minted by
 * Google's own botguard JavaScript. Reproducing that by hand means maintaining
 * a permanent fork of obfuscated Google code that gets rotated.
 *
 * A WebView sidesteps the problem instead of solving it: in a browser context
 * YouTube's player is simply allowed to play, exactly as it does on
 * youtube.com. We drive the **published** IFrame API, so there is no reverse
 * engineering at all — just Google's supported embed player, talking to us
 * over a small JS bridge. Audio comes out of the WebView's media stack.
 *
 * The view is 1x1 px and parked in the corner: invisible, but attached to a
 * window, which is what a WebView needs in order to keep running its scripts.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class WebViewAudioPlayer(
    context: Context,
    private val listener: Listener? = null,
) {

    interface Listener {
        fun onReady(durationSec: Long) {}
        fun onPlaying() {}
        fun onPaused() {}
        fun onBuffering() {}
        fun onEnded() {}
        fun onProgress(positionSec: Long, durationSec: Long) {}
        fun onError(code: Int) {}
    }

    private val container = FrameLayout(context)
    private var webView: WebView? = null

    /** Latest position/duration, mirrored from the page's 500 ms ticker. */
    @Volatile var positionMs: Long = 0L
        private set
    @Volatile var durationMs: Long = 0L
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var title: String = ""
        private set

    init {
        // 1x1 px, parked top-left: invisible, but attached to a window, which
        // is what a WebView needs in order to keep running its scripts.
        (context as? android.app.Activity)?.window?.decorView?.let { decor ->
            (decor as? ViewGroup)?.addView(container, FrameLayout.LayoutParams(1, 1))
        }
    }

    fun start(videoId: String) {
        release()
        val wv = WebView(container.context)
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // WebView's default UA contains "; wv)", which brands the app to
        // Google as an embedded WebView. YouTube answers that with error 152
        // ("cannot be played in an embedded player") even on videos that are
        // explicitly embeddable. Present as a normal Chrome instead.
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 12; SM-M215F) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        // Without this the embed refuses to autoplay, and every tap would need
        // a second tap on an invisible control.
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.allowFileAccess = false
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        container.addView(
            wv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // The referrer argument matters more than it looks: without a real
        // youtube.com document origin and Referer, YouTube treats the document
        // as untrusted and refuses embedding (error 152) even for videos that
        // are explicitly embeddable.
        // Navigate to the real embed URL rather than injecting a synthetic
        // document. A genuine youtube.com navigation carries the origin and
        // cookies YouTube's player checks; loadDataWithBaseURL does not, and
        // the player answers that with error 152.
        wv.loadUrl(
            "https://www.youtube.com/embed/$videoId" +
                "?autoplay=1&playsinline=1&controls=0&enablejsapi=1" +
                "&rel=0&modestbranding=1&iv_load_policy=3",
        )
        Log.i(TAG, "loading embed for $videoId")
    }

    fun pause() {
        webView?.evaluateJavascript("player && player.pauseVideo();", null)
    }

    fun resume() {
        webView?.evaluateJavascript("player && player.playVideo();", null)
    }

    fun seekTo(positionMs: Long) {
        webView?.evaluateJavascript("player && player.seekTo(${positionMs / 1000.0}, true);", null)
    }

    fun release() {
        container.removeView(webView)
        webView?.destroy()
        webView = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }

    // ------------------------------------------------------------- bridge

    @Suppress("unused")
    inner class Bridge {
        @JavascriptInterface fun onReady(duration: Double) {
            durationMs = (duration * 1000).toLong()
            Log.i(TAG, "ready, duration=${duration}s")
            listener?.onReady(durationMs)
        }

        @JavascriptInterface fun onState(state: Int) {
            when (state) {
                STATE_PLAYING -> {
                    isPlaying = true
                    Log.i(TAG, "playing")
                    listener?.onPlaying()
                }
                STATE_PAUSED -> {
                    isPlaying = false
                    listener?.onPaused()
                }
                STATE_BUFFERING -> listener?.onBuffering()
                STATE_ENDED -> {
                    isPlaying = false
                    listener?.onEnded()
                }
            }
        }

        @JavascriptInterface fun onProgress(position: Double, duration: Double) {
            positionMs = (position * 1000).toLong()
            durationMs = (duration * 1000).toLong()
            listener?.onProgress(positionMs, durationMs)
        }

        @JavascriptInterface fun onError(code: Int) {
            Log.e(TAG, "embed error $code")
            listener?.onError(code)
        }
    }

    // --------------------------------------------------------------- page

    private fun page(videoId: String): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        </head><body style="margin:0;background:#000;overflow:hidden">
        <div id="player"></div>
        <script>
        var bridge = window.AndroidBridge;
        var s = document.createElement('script');
        s.src = 'https://www.youtube.com/iframe_api';
        document.head.appendChild(s);
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('player', {
            height:'1', width:'1', videoId:'$videoId',
            playerVars:{ autoplay:1, playsinline:1, controls:0, disablekb:1,
                         modestbranding:1, rel:0, iv_load_policy:3 },
            events:{
              onReady:function(e){
                bridge.onReady(e.target.getDuration());
                e.target.playVideo();
                setInterval(function(){
                  try { bridge.onProgress(e.target.getCurrentTime(), e.target.getDuration()); } catch(err){}
                }, 500);
              },
              onStateChange:function(e){ try { bridge.onState(e.data); } catch(err){} },
              onError:function(e){ try { bridge.onError(e.data); } catch(err){} }
            }
          });
        }
        </script></body></html>
    """.trimIndent()

    private companion object {
        const val TAG = "WebPlay"
        const val STATE_ENDED = 0
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_BUFFERING = 3
    }
}
