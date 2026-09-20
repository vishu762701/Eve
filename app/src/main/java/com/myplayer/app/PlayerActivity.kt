package com.myplayer.app

import android.app.Activity
import android.app.AlertDialog
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
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var ui: FrameLayout
    private lateinit var title: TextView
    private lateinit var tCur: TextView
    private lateinit var tDur: TextView
    private lateinit var seek: SeekBar
    private lateinit var bPlay: ImageView
    private lateinit var toast: TextView
    private lateinit var skipL: TextView
    private lateinit var skipR: TextView
    private lateinit var unlockBtn: ImageView
    private lateinit var scaler: ScaleGestureDetector
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
    private var uiOn = true
    private var dragging = false
    private var locked = false
    private var oriLocked = false
    private var downX = 0f
    private var downY = 0f
    private var mode = 0
    private var startPos = 0L
    private var startVol = 0f
    private var startBright = 0.5f
    private var seekTarget = 0L
    private var lastTap = 0L
    private var lastSide = 9
    private var accum = 0
    private var slop = 16
    private var zoom = 1f
    private var longPress = false
    private var prevSpeed = 1f

    private val hideUiRun = Runnable { hideUi() }
    private val toggleRun = Runnable { if (uiOn) hideUi() else showUi() }
    private val hideSkip = Runnable {
        skipL.animate().alpha(0f).setDuration(250)
        skipR.animate().alpha(0f).setDuration(250)
    }
    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(250) }
    private val hideUnlock = Runnable {
        unlockBtn.animate().alpha(0f).setDuration(250).withEndAction {
            if (locked) unlockBtn.visibility = View.INVISIBLE
        }
    }
    private val lpRun = Runnable {
        if (mode == 0 && !locked) {
            longPress = true
            prevSpeed = player.playbackParameters.speed
            player.setPlaybackSpeed(2f)
            say("2x speed")
        }
    }
    private val sleepRun = Runnable {
        if (!released) player.pause()
        Toast.makeText(this, "Sleep timer ended", Toast.LENGTH_LONG).show()
    }
    private val saver = object : Runnable {
        override fun run() {
            savePos()
            h.postDelayed(this, 5000)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!released && uiOn && !dragging) {
                val d = player.duration
                val p = player.currentPosition
                tCur.text = fmt(p)
                if (d != C.TIME_UNSET && d > 0) {
                    tDur.text = fmt(d)
                    seek.progress = (p * 1000 / d).toInt()
                }
            }
            h.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

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
        view.useController = false
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        root.addView(view, FrameLayout.LayoutParams(MP, MP))
        buildUi(root)
        setContentView(root)

        scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 4f)
                applyZoom()
                say("Zoom " + (zoom * 100).toInt() + "%")
                return true
            }
        })
        view.setOnTouchListener { _, e ->
            onTouch(e)
            true
        }
        initPlayer(start)
        h.postDelayed(saver, 5000)
        h.post(tick)
        showUi()
    }

    private fun initPlayer(start: Int) {
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) {
                title.text = m?.mediaMetadata?.title ?: ""
                val u = m?.localConfiguration?.uri?.toString()
                if (u != null) {
                    Lib.addRecent(this@PlayerActivity, u)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
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
                if (!oriLocked && vs.width > 0 && vs.height > 0 && !isInPictureInPictureMode) {
                    requestedOrientation = if (vs.height > vs.width)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Error: " + error.errorCodeName, Toast.LENGTH_LONG).show()
            }

            override fun onIsPlayingChanged(p: Boolean) {
                updPlay()
                h.removeCallbacks(hideUiRun)
                if (p && uiOn && !locked) h.postDelayed(hideUiRun, 3500)
            }

            override fun onPlayWhenReadyChanged(w: Boolean, reason: Int) {
                updPlay()
            }

            override fun onPlaybackStateChanged(s: Int) {
                updPlay()
            }
        })
        val items = uris.map { u ->
            MediaItem.Builder()
                .setUri(Uri.parse(u))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(nameOf(Uri.parse(u)).substringBeforeLast('.')).build())
                .build()
        }
        val si = start.coerceIn(0, items.size - 1)
        val saved = prefs.getLong(uris[si], 0L)
        player.setMediaItems(items, si, if (saved > 5000) saved else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    private fun sz() = LinearLayout.LayoutParams(dp(52), dp(52))

    private fun btn(res: Int, onClick: () -> Unit): ImageView {
        val v = Ui.icon(this, res, 12)
        v.setOnClickListener {
            showUi()
            onClick()
        }
        return v
    }

    private fun timeText(): TextView {
        val t = TextView(this)
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        t.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        t.text = "0:00"
        return t
    }

    private fun skipView(): TextView {
        val v = TextView(this)
        v.setTextColor(Color.WHITE)
        v.textSize = 22f
        v.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        v.alpha = 0f
        return v
    }

    private fun buildUi(root: FrameLayout) {
        ui = FrameLayout(this)
        title = TextView(this)
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setShadowLayer(8f, 0f, 2f, Color.BLACK)
        ui.addView(title, FrameLayout.LayoutParams(MP, WC, Gravity.TOP).apply { setMargins(dp(64), dp(28), dp(64), 0) })

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(dp(28), 0, dp(28), dp(14))
        val times = LinearLayout(this)
        tCur = timeText()
        tDur = timeText()
        tDur.gravity = Gravity.END
        times.addView(tCur, LinearLayout.LayoutParams(0, WC, 1f))
        times.addView(tDur, LinearLayout.LayoutParams(0, WC, 1f))
        bottom.addView(times)

        seek = SeekBar(this)
        seek.max = 1000
        seek.progressDrawable = getDrawable(R.drawable.seek_progress)
        seek.thumb = getDrawable(R.drawable.seek_thumb)
        seek.splitTrack = false
        seek.setPadding(dp(8), 0, dp(8), 0)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val d = player.duration
                if (fromUser && d > 0) tCur.text = fmt((p / 1000.0 * d).toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
                h.removeCallbacks(hideUiRun)
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                val d = player.duration
                if (d > 0 && d != C.TIME_UNSET) player.seekTo((seek.progress / 1000.0 * d).toLong())
                showUi()
            }
        })
        bottom.addView(seek, LinearLayout.LayoutParams(MP, dp(34)))

        val row = FrameLayout(this)
        val left = LinearLayout(this)
        left.addView(btn(R.drawable.ic_subs) { subsDialog() }, sz())
        left.addView(btn(R.drawable.ic_rotate) { rotate() }, sz())
        row.addView(left, FrameLayout.LayoutParams(WC, WC, Gravity.START or Gravity.CENTER_VERTICAL))
        bPlay = Ui.icon(this, R.drawable.ic_pause_ring, 4)
        bPlay.setOnClickListener {
            showUi()
            togglePlay()
        }
        row.addView(bPlay, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        val right = LinearLayout(this)
        right.addView(btn(R.drawable.ic_fit) { cycleFit() }, sz())
        right.addView(btn(R.drawable.ic_more_h) { moreMenu() }, sz())
        row.addView(right, FrameLayout.LayoutParams(WC, WC, Gravity.END or Gravity.CENTER_VERTICAL))
        bottom.addView(row, LinearLayout.LayoutParams(MP, dp(72)))
        ui.addView(bottom, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))
        root.addView(ui, FrameLayout.LayoutParams(MP, MP))

        skipL = skipView()
        skipR = skipView()
        root.addView(skipL, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(70) })
        root.addView(skipR, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(70) })
        toast = TextView(this)
        toast.setTextColor(Color.WHITE)
        toast.textSize = 16f
        toast.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        toast.alpha = 0f
        root.addView(toast, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(100) })
        unlockBtn = Ui.icon(this, R.drawable.ic_lock, 14)
        unlockBtn.background = Ui.circle(0x66000000)
        unlockBtn.visibility = View.GONE
        unlockBtn.setOnClickListener { unlockScreen() }
        root.addView(unlockBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(70) })
    }

    private fun showUi() {
        if (locked) return
        uiOn = true
        ui.visibility = View.VISIBLE
        ui.animate().cancel()
        ui.animate().alpha(1f).setDuration(150)
        h.removeCallbacks(hideUiRun)
        if (player.isPlaying) h.postDelayed(hideUiRun, 3500)
    }

    private fun hideUi() {
        uiOn = false
        ui.animate().alpha(0f).setDuration(200).withEndAction {
            if (!uiOn) ui.visibility = View.GONE
        }
    }

    private fun updPlay() {
        val on = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        bPlay.setImageResource(if (on) R.drawable.ic_pause_ring else R.drawable.ic_play_ring)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
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

    private fun applyZoom() {
        val s = view.videoSurfaceView
        s?.scaleX = zoom
        s?.scaleY = zoom
    }

    private fun endLongPress() {
        if (longPress) {
            longPress = false
            player.setPlaybackSpeed(prevSpeed)
            say("Speed " + prevSpeed + "x")
        }
    }

    private fun lockScreen() {
        locked = true
        hideUi()
        say("Screen locked")
        showUnlock()
    }

    private fun unlockScreen() {
        locked = false
        h.removeCallbacks(hideUnlock)
        unlockBtn.visibility = View.GONE
        showUi()
    }

    private fun showUnlock() {
        unlockBtn.visibility = View.VISIBLE
        unlockBtn.animate().cancel()
        unlockBtn.alpha = 1f
        h.removeCallbacks(hideUnlock)
        h.postDelayed(hideUnlock, 2000)
    }

    private fun onTouch(e: MotionEvent) {
        scaler.onTouchEvent(e)
        if (locked) {
            if (e.actionMasked == MotionEvent.ACTION_UP) showUnlock()
            return
        }
        if (e.pointerCount > 1 || scaler.isInProgress) {
            mode = 4
            h.removeCallbacks(lpRun)
            h.removeCallbacks(toggleRun)
            endLongPress()
            return
        }
        val w = view.width.toFloat()
        val hh = view.height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; mode = 0
                startPos = player.currentPosition
                startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                val cur = window.attributes.screenBrightness
                startBright = if (cur < 0) 0.5f else cur
                longPress = false
                h.removeCallbacks(lpRun)
                h.postDelayed(lpRun, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 4 || longPress) return
                val dx = e.x - downX
                val dy = e.y - downY
                if (mode == 0 && (abs(dx) > slop * 2 || abs(dy) > slop * 2)) {
                    mode = if (abs(dx) > abs(dy)) 1 else if (downX < w / 2) 3 else 2
                    h.removeCallbacks(toggleRun)
                    h.removeCallbacks(lpRun)
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
                h.removeCallbacks(lpRun)
                if (longPress) endLongPress()
                else if (mode == 0) tap(e.x, w)
                else if (mode == 1) player.seekTo(seekTarget)
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(lpRun)
                endLongPress()
                mode = 0
            }
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
            togglePlay()
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
        o.animate().cancel()
        o.alpha = 0f
        t.text = if (side < 0) "<< " + acc + "s" else acc.toString() + "s >>"
        t.animate().cancel()
        t.alpha = 1f
        h.removeCallbacks(hideSkip)
        h.postDelayed(hideSkip, 600)
    }

    private fun cycleFit() {
        if (zoom != 1f) {
            zoom = 1f
            applyZoom()
            say("Zoom reset")
            return
        }
        fitMode = (fitMode + 1) % fitModes.size
        view.resizeMode = fitModes[fitMode]
        say(fitNames[fitMode])
    }

    private fun rotate() {
        oriLocked = true
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun moreMenu() {
        val items = arrayOf("Playback speed", "Audio track", "Sleep timer", "Lock screen", "Picture in picture", "Video info")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> speedDialog()
                1 -> trackDialog(C.TRACK_TYPE_AUDIO, "Audio track")
                2 -> sleepDialog()
                3 -> lockScreen()
                4 -> enterPip()
                5 -> infoDialog()
            }
        }.show()
    }

    private fun subsDialog() {
        trackDialog(C.TRACK_TYPE_TEXT, "Subtitles")
    }

    private fun speedDialog() {
        val v = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
        val names = v.map { it.toString() + "x" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(names) { _, w ->
            player.setPlaybackSpeed(v[w])
            say(names[w])
        }.show()
    }

    private fun trackDialog(type: Int, ttl: String) {
        val list = ArrayList<Triple<Tracks.Group, Int, String>>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val n = f.label ?: f.language?.let { Locale(it).displayLanguage } ?: ("Track " + (list.size + 1))
                list.add(Triple(g, i, n))
            }
        }
        val isText = type == C.TRACK_TYPE_TEXT
        val names = ArrayList<String>()
        if (isText) names.add("Off")
        for (t in list) names.add(t.third)
        if (isText) names.add("Add subtitle file...")
        if (names.isEmpty()) {
            say("No tracks")
            return
        }
        AlertDialog.Builder(this).setTitle(ttl).setItems(names.toTypedArray()) { _, w ->
            val off = if (isText) 1 else 0
            if (isText && w == 0) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, true).build()
            } else if (isText && w == names.size - 1) {
                pickSubtitle()
            } else {
                val t = list[w - off]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(t.first.mediaTrackGroup, t.second))
                    .build()
            }
        }.show()
    }

    private fun sleepDialog() {
        val names = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this).setTitle("Sleep timer").setItems(names) { _, w ->
            h.removeCallbacks(sleepRun)
            if (mins[w] > 0) {
                h.postDelayed(sleepRun, mins[w] * 60000L)
                say("Sleep in " + mins[w] + " min")
            } else say("Sleep timer off")
        }.show()
    }

    private fun infoDialog() {
        val f = player.videoFormat
        val a = player.audioFormat
        val vs = player.videoSize
        val br = if (f != null && f.bitrate > 0) (f.bitrate / 1000).toString() + " kbps" else "unknown"
        val msg = "Name: " + title.text +
                "\nDuration: " + fmt(player.duration.coerceAtLeast(0)) +
                "\nResolution: " + vs.width + " x " + vs.height +
                "\nVideo: " + (f?.sampleMimeType ?: "unknown") +
                "\nBitrate: " + br +
                "\nAudio: " + (a?.sampleMimeType ?: "unknown")
        AlertDialog.Builder(this).setTitle("Video info").setMessage(msg).setPositiveButton("OK", null).show()
    }

    @Suppress("DEPRECATION")
    private fun pickSubtitle() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 7)
    }

    @Suppress("DEPRECATION")
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
        ui.visibility = if (inPip) View.GONE else if (uiOn && !locked) View.VISIBLE else View.GONE
        if (inPip) unlockBtn.visibility = View.GONE
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
