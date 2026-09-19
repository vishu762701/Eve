package com.myplayer.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private lateinit var toast: TextView
    private lateinit var skipL: TextView
    private lateinit var skipR: TextView
    private lateinit var bar: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var am: AudioManager
    private val h = Handler(Looper.getMainLooper())
    private val uris = ArrayList<String>()
    private var released = false
    private var fitMode = 0
    private val fitModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val fitNames = arrayOf("Fit", "Zoom", "Stretch")

    private var downX = 0f
    private var downY = 0f
    private var mode = 0 // 0 none, 1 seek, 2 volume, 3 brightness
    private var startPos = 0L
    private var startVol = 0f
    private var startBright = 0.5f
    private var seekTarget = 0L
    private var lastTap = 0L
    private var lastSide = 9
    private var accum = 0
    private var slop = 16

    private val toggleRun = Runnable {
        if (view.isControllerFullyVisible) view.hideController() else view.showController()
    }
    private val hideSkip = Runnable {
        skipL.animate().alpha(0f).setDuration(250)
        skipR.animate().alpha(0f).setDuration(250)
    }
    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(250) }
    private val saver = object : Runnable {
        override fun run() {
            savePos()
            h.postDelayed(this, 5000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        prefs = getSharedPreferences("pos", MODE_PRIVATE)
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        slop = ViewConfiguration.get(this).scaledTouchSlop

        val list = intent.getStringArrayListExtra("uris")
        val start = intent.getIntExtra("index", 0)
        if (list != null && list.isNotEmpty()) uris.addAll(list)
        else intent.data?.let { uris.add(it.toString()) }
        if (uris.isEmpty()) {
            finish(); return
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        view = PlayerView(this)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.controllerShowTimeoutMs = 3000
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0x66000000)
        bar.setPadding(dp(48), dp(6), dp(48), dp(6))
        title = TextView(this)
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barBtn("CC+") { pickSubtitle() })
        bar.addView(barBtn("Fit") { cycleFit() })
        bar.addView(barBtn("PiP") { enterPip() })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        skipL = skipView()
        skipR = skipView()
        root.addView(skipL, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(70) })
        root.addView(skipR, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(70) })

        toast = TextView(this)
        toast.setTextColor(Color.WHITE)
        toast.textSize = 16f
        toast.setBackgroundColor(0x99000000.toInt())
        toast.setPadding(dp(16), dp(8), dp(16), dp(8))
        toast.alpha = 0f
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(70) })

        setContentView(root)

        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            bar.visibility = if (vis == View.VISIBLE) View.VISIBLE else View.GONE
        })
        view.setOnTouchListener { _, e ->
            onTouch(e)
            true
        }

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) {
                title.text = m?.mediaMetadata?.title ?: ""
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    val u = m?.localConfiguration?.uri?.toString()
                    if (u != null) {
                        val s = prefs.getLong(u, 0L)
                        if (s > 5000) player.seekTo(s)
                    }
                }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    old.mediaItem?.localConfiguration?.uri?.toString()?.let { prefs.edit().remove(it).apply() }
                }
            }

            override fun onVideoSizeChanged(vs: VideoSize) {
                if (vs.width > 0 && vs.height > 0 && !isInPictureInPictureMode) {
                    requestedOrientation = if (vs.height > vs.width)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Error: " + error.errorCodeName, Toast.LENGTH_LONG).show()
            }
        })

        val items = uris.map { u ->
            MediaItem.Builder()
                .setUri(Uri.parse(u))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(nameOf(Uri.parse(u))).build())
                .build()
        }
        val si = start.coerceIn(0, items.size - 1)
        val saved = prefs.getLong(uris[si], 0L)
        player.setMediaItems(items, si, if (saved > 5000) saved else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
        h.postDelayed(saver, 5000)
    }

    private fun barBtn(t: String, onClick: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.textSize = 16f
        v.setPadding(dp(14), dp(8), dp(14), dp(8))
        v.setOnClickListener { onClick() }
        return v
    }

    private fun skipView(): TextView {
        val v = TextView(this)
        v.setTextColor(Color.WHITE)
        v.textSize = 22f
        v.setBackgroundColor(0x66000000)
        v.setPadding(dp(18), dp(10), dp(18), dp(10))
        v.alpha = 0f
        return v
    }

    private fun say(t: String) {
        toast.text = t
        toast.animate().cancel()
        toast.alpha = 1f
        h.removeCallbacks(hideToast)
        h.postDelayed(hideToast, 800)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600
        val m = (s % 3600) / 60
        val c = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, m, c) else String.format("%d:%02d", m, c)
    }

    private fun onTouch(e: MotionEvent) {
        val w = view.width.toFloat()
        val hh = view.height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; mode = 0
                startPos = player.currentPosition
                startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                val cur = window.attributes.screenBrightness
                startBright = if (cur < 0) 0.5f else cur
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (mode == 0 && (abs(dx) > slop * 2 || abs(dy) > slop * 2)) {
                    mode = if (abs(dx) > abs(dy)) 1 else if (downX < w / 2) 3 else 2
                    h.removeCallbacks(toggleRun)
                }
                when (mode) {
                    1 -> {
                        var t = startPos + (dx / w * 120000).toLong()
                        t = t.coerceAtLeast(0)
                        val d = player.duration
                        if (d != C.TIME_UNSET && d > 0) t = t.coerceAtMost(d)
                        seekTarget = t
                        val diff = (t - startPos) / 1000
                        say(fmt(t) + "  (" + (if (diff >= 0) "+" else "-") + abs(diff) + "s)")
                    }
                    2 -> {
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val nv = (startVol + (-dy / (hh * 0.7f)) * max).coerceIn(0f, max.toFloat())
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, nv.roundToInt(), 0)
                        say("Volume " + (nv / max * 100).toInt() + "%")
                    }
                    3 -> {
                        val nb = (startBright - dy / (hh * 0.7f)).coerceIn(0.02f, 1f)
                        val lp = window.attributes
                        lp.screenBrightness = nb
                        window.attributes = lp
                        say("Brightness " + (nb * 100).toInt() + "%")
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 0) tap(e.x, w)
                else if (mode == 1) player.seekTo(seekTarget)
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> mode = 0
        }
    }

    private fun tap(x: Float, w: Float) {
        val side = if (x < w * 0.35f) -1 else if (x > w * 0.65f) 1 else 0
        val now = SystemClock.uptimeMillis()
        val chain = now - lastTap < 500 && side == lastSide
        if (chain && side != 0) {
            h.removeCallbacks(toggleRun)
            accum += 10
            skip(side * 10000L)
            flash(side, accum)
            lastTap = now
        } else if (chain) {
            h.removeCallbacks(toggleRun)
            if (player.isPlaying) player.pause() else player.play()
            lastTap = 0
        } else {
            lastTap = now
            lastSide = side
            accum = 0
            h.removeCallbacks(toggleRun)
            h.postDelayed(toggleRun, 350)
        }
    }

    private fun skip(ms: Long) {
        var t = player.currentPosition + ms
        val d = player.duration
        if (d != C.TIME_UNSET && d > 0) t = t.coerceAtMost(d)
        player.seekTo(t.coerceAtLeast(0))
    }

    private fun flash(side: Int, acc: Int) {
        val t = if (side < 0) skipL else skipR
        val o = if (side < 0) skipR else skipL
        o.animate().cancel(); o.alpha = 0f
        t.text = if (side < 0) "⏪ " + acc + "s" else acc.toString() + "s ⏩"
        t.animate().cancel()
        t.alpha = 1f
        h.removeCallbacks(hideSkip)
        h.postDelayed(hideSkip, 600)
    }

    private fun cycleFit() {
        fitMode = (fitMode + 1) % fitModes.size
        view.resizeMode = fitModes[fitMode]
        say(fitNames[fitMode])
    }

    private fun pickSubtitle() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 7)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val u = data?.data
        if (req == 7 && res == RESULT_OK && u != null) addSubtitle(u)
    }

    private fun addSubtitle(u: Uri) {
        val n = nameOf(u).lowercase()
        val mime = when {
            n.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            n.endsWith(".ass") || n.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val cur = player.currentMediaItem ?: return
        val sub = MediaItem.SubtitleConfiguration.Builder(u)
            .setMimeType(mime)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        player.replaceMediaItem(idx, cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build())
        player.seekTo(idx, pos)
        player.play()
        say("Subtitle added")
    }

    private fun nameOf(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (e: Exception) {
        }
        return u.lastPathSegment ?: "Video"
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val b = PictureInPictureParams.Builder()
            val vs = player.videoSize
            if (vs.width > 0 && vs.height > 0) {
                val r = vs.width.toFloat() / vs.height
                if (r in 0.42f..2.39f) b.setAspectRatio(Rational(vs.width, vs.height))
            }
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::player.isInitialized && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        view.useController = !inPip
        if (inPip) bar.visibility = View.GONE
    }

    private fun savePos() {
        if (released) return
        val u = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val d = player.duration
        val p = player.currentPosition
        if (d != C.TIME_UNSET && d > 0 && d - p < 10000) prefs.edit().remove(u).apply()
        else prefs.edit().putLong(u, p).apply()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(f: Boolean) {
        super.onWindowFocusChanged(f)
        if (f) hideBars()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized && !released) {
            savePos()
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacksAndMessages(null)
        if (::player.isInitialized && !released) {
            savePos()
            released = true
            player.release()
        }
    }
}
