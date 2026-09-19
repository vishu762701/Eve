package com.myplayer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

class Vid(
    val id: Long, val uri: Uri, val name: String, val dur: Long, val size: Long,
    val date: Long, val folder: String, val w: Int, val h: Int
)

class Cell(val type: Int, val vid: Vid, val folder: String, val count: Int)

class Holder(
    val iv: ImageView, val badge: TextView, val fav: TextView,
    val prog: View, val title: TextView, val sub: TextView
)

class MainActivity : Activity() {
    private val all = ArrayList<Vid>()
    private val cells = ArrayList<Cell>()
    private var tab = 0
    private var openFolder: String? = null
    private var query = ""
    private var sort = 0
    private var loaded = false
    private var colW = 0
    private val sortNames = arrayOf(
        "Newest first", "Oldest first", "Name A-Z", "Name Z-A",
        "Largest size", "Smallest size", "Longest", "Shortest"
    )
    private val chips = ArrayList<TextView>()
    private lateinit var root: FrameLayout
    private lateinit var grid: GridView
    private lateinit var adapter: CellAdapter
    private lateinit var info: TextView
    private lateinit var search: EditText
    private lateinit var backdrop: ImageView
    private val cache = LruCache<Long, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3)
    private var pendRename: Pair<Vid, String>? = null
    private var pendDelete: Vid? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun hasRead() = checkSelfPermission(perms()[0]) == PackageManager.PERMISSION_GRANTED

    private fun glass(radiusDp: Int, color: Int = 0x22FFFFFF): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        g.setStroke(dp(1), 0x33FFFFFF)
        return g
    }

    private val press = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120)
            }
        }
        false
    }

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setTextColor(Color.WHITE)
        t.background = glass(20)
        t.setPadding(dp(16), dp(8), dp(16), dp(8))
        t.setOnClickListener { onClick() }
        t.setOnTouchListener(press)
        return t
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sort = getSharedPreferences("lib", MODE_PRIVATE).getInt("sort", 0)
        val dm = resources.displayMetrics
        val pad = dp(12)
        val gap = dp(10)
        val cols = max(2, (dm.widthPixels / dm.density / 170f).toInt())
        colW = (dm.widthPixels - 2 * pad - (cols - 1) * gap) / cols

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        backdrop = ImageView(this)
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.alpha = 0f
        root.addView(backdrop, FrameLayout.LayoutParams(MP, MP))
        val scrim = View(this)
        scrim.setBackgroundColor(0xC8000000.toInt())
        root.addView(scrim, FrameLayout.LayoutParams(MP, MP))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(pad, dp(14), pad, dp(6))
        val t = TextView(this)
        t.text = "Eve Player"
        t.textSize = 24f
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.04f
        t.setTextColor(Color.WHITE)
        head.addView(t, LinearLayout.LayoutParams(0, WC, 1f))
        head.addView(pill("Search") { toggleSearch() }, LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(8) })
        head.addView(pill("Sort") { sortDialog() }, LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(8) })
        col.addView(head)

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.setPadding(pad, dp(6), pad, dp(6))
        val names = arrayOf("All", "Folders", "Recent", "Favorites")
        for (i in names.indices) {
            val c = pill(names[i]) { setTab(i) }
            chips.add(c)
            row.addView(c, LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(8) })
        }
        row.addView(pill("Open file") { openFiles() }, LinearLayout.LayoutParams(WC, WC))
        hs.addView(row)
        col.addView(hs)

        search = EditText(this)
        search.hint = "Search videos"
        search.setHintTextColor(0x88FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.isSingleLine = true
        search.background = glass(12)
        search.setPadding(dp(14), dp(10), dp(14), dp(10))
        search.visibility = View.GONE
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                rebuild()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(search, LinearLayout.LayoutParams(MP, WC).apply { setMargins(pad, dp(4), pad, dp(4)) })

        info = TextView(this)
        info.textSize = 12f
        info.setTextColor(0x99FFFFFF.toInt())
        info.setPadding(pad + dp(2), dp(4), pad, dp(4))
        info.setOnClickListener {
            if (tab == 1 && openFolder != null) {
                openFolder = null
                rebuild()
            }
        }
        col.addView(info)

        grid = GridView(this)
        grid.numColumns = cols
        grid.columnWidth = colW
        grid.stretchMode = GridView.NO_STRETCH
        grid.horizontalSpacing = gap
        grid.verticalSpacing = gap
        grid.gravity = Gravity.CENTER_HORIZONTAL
        grid.setPadding(pad, dp(6), pad, dp(20))
        grid.clipToPadding = false
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        adapter = CellAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, view, pos, _ ->
            val c = cells[pos]
            view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120)
            }
            if (c.type == 1) {
                openFolder = c.folder
                rebuild()
                grid.setSelection(0)
            } else playFrom(c.vid)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val c = cells[pos]
            if (c.type == 0) menu(c.vid)
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(MP, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MP, MP))

        if (b == null) splash()
        setContentView(root)
        updateChips()

        if (hasRead()) load() else requestPermissions(perms(), 10)
    }

    private fun splash() {
        val s = FrameLayout(this)
        s.setBackgroundColor(Color.BLACK)
        s.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher_fg)
        logo.alpha = 0f
        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        col.addView(logo, LinearLayout.LayoutParams(dp(220), dp(220)))
        val nm = TextView(this)
        nm.text = "EVE PLAYER"
        nm.textSize = 18f
        nm.letterSpacing = 0.3f
        nm.setTextColor(Color.WHITE)
        nm.alpha = 0f
        col.addView(nm, LinearLayout.LayoutParams(WC, WC))
        s.addView(col, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))
        root.addView(s, FrameLayout.LayoutParams(MP, MP))
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
        nm.animate().alpha(1f).setStartDelay(350).setDuration(500).start()
        s.animate().alpha(0f).setStartDelay(1600).setDuration(450).withEndAction { root.removeView(s) }.start()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (hasRead()) load() else {
            loaded = true
            rebuild()
        }
    }

    override fun onResume() {
        super.onResume()
        if (loaded) rebuild()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val c = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, c) else String.format("%d:%02d", m, c)
    }

    private fun load() {
        Thread {
            val l = ArrayList<Vid>()
            try {
                val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val cols = arrayOf(
                    MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT
                )
                contentResolver.query(uri, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        l.add(
                            Vid(
                                id, ContentUris.withAppendedId(uri, id), c.getString(1) ?: "Video",
                                c.getLong(2), c.getLong(3), c.getLong(4), c.getString(5) ?: "Unknown",
                                c.getInt(6), c.getInt(7)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
            }
            runOnUiThread {
                all.clear()
                all.addAll(l)
                loaded = true
                rebuild()
                setBackdrop()
            }
        }.start()
    }
    @Suppress("DEPRECATION")
    private fun thumbBitmap(v: Vid): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(v.uri, Size(360, 202), null)
        else MediaStore.Video.Thumbnails.getThumbnail(contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    } catch (e: Exception) {
        null
    }

    private fun loadThumb(v: Vid, iv: ImageView) {
        iv.tag = v.id
        val c = cache.get(v.id)
        if (c != null) {
            iv.setImageBitmap(c)
            return
        }
        iv.setImageDrawable(null)
        pool.execute {
            val bm = thumbBitmap(v)
            if (bm != null) {
                cache.put(v.id, bm)
                runOnUiThread {
                    if (iv.tag == v.id) {
                        iv.setImageBitmap(bm)
                        iv.alpha = 0f
                        iv.animate().alpha(1f).setDuration(220)
                    }
                }
            }
        }
    }

    private fun setBackdrop() {
        if (all.isEmpty()) return
        val v = all[0]
        pool.execute {
            val bm = thumbBitmap(v)
            if (bm != null) {
                val small = Bitmap.createScaledBitmap(bm, 24, 14, true)
                runOnUiThread {
                    backdrop.setImageBitmap(small)
                    backdrop.animate().alpha(1f).setDuration(700)
                }
            }
        }
    }

    private fun sorted(l: List<Vid>): List<Vid> = when (sort) {
        0 -> l.sortedByDescending { it.date }
        1 -> l.sortedBy { it.date }
        2 -> l.sortedBy { it.name.lowercase() }
        3 -> l.sortedByDescending { it.name.lowercase() }
        4 -> l.sortedByDescending { it.size }
        5 -> l.sortedBy { it.size }
        6 -> l.sortedByDescending { it.dur }
        else -> l.sortedBy { it.dur }
    }

    private fun rebuild() {
        var src: List<Vid> = when (tab) {
            2 -> {
                val m = all.associateBy { it.uri.toString() }
                Lib.recent(this).mapNotNull { m[it] }
            }
            3 -> all.filter { Lib.isFav(this, it.uri.toString()) }
            else -> all
        }
        if (query.isNotBlank()) src = src.filter { it.name.contains(query, true) }
        if (tab != 2) src = sorted(src)
        cells.clear()
        if (tab == 1 && openFolder == null) {
            val g = src.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            for ((k, l) in g) cells.add(Cell(1, l[0], k, l.size))
        } else {
            val list = if (tab == 1) src.filter { it.folder == openFolder } else src
            for (v in list) cells.add(Cell(0, v, "", 0))
        }
        adapter.notifyDataSetChanged()
        info.text = when {
            tab == 1 && openFolder != null -> "< " + openFolder + "  -  " + cells.size + " videos  (tap to go back)"
            !loaded -> "Loading..."
            cells.isEmpty() -> when {
                !hasRead() -> "Permission needed. Use 'Open file' or allow video access in settings."
                tab == 2 -> "No recent videos yet"
                tab == 3 -> "No favorites yet. Long-press a video to add."
                else -> "No videos found"
            }
            else -> cells.size.toString() + (if (tab == 1) " folders" else " videos")
        }
    }

    private fun setTab(i: Int) {
        tab = i
        openFolder = null
        rebuild()
        updateChips()
        grid.setSelection(0)
    }

    private fun updateChips() {
        for (i in chips.indices) {
            if (i == tab) {
                chips[i].background = glass(20, Color.WHITE)
                chips[i].setTextColor(Color.BLACK)
            } else {
                chips[i].background = glass(20)
                chips[i].setTextColor(Color.WHITE)
            }
        }
    }

    private fun toggleSearch() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (search.visibility == View.VISIBLE) {
            search.setText("")
            search.visibility = View.GONE
            imm.hideSoftInputFromWindow(search.windowToken, 0)
        } else {
            search.visibility = View.VISIBLE
            search.requestFocus()
            imm.showSoftInput(search, 0)
        }
    }

    private fun sortDialog() {
        AlertDialog.Builder(this).setTitle("Sort by")
            .setSingleChoiceItems(sortNames, sort) { d, w ->
                sort = w
                getSharedPreferences("lib", MODE_PRIVATE).edit().putInt("sort", w).apply()
                d.dismiss()
                rebuild()
            }.show()
    }

    private fun openFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "video/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(i, 1)
    }

    private fun playFrom(v: Vid) {
        val vids = cells.filter { it.type == 0 }.map { it.vid }
        val idx = vids.indexOfFirst { it.id == v.id }
        if (idx < 0) return
        val from = max(0, idx - 50)
        val to = min(vids.size, idx + 250)
        val l = ArrayList<String>()
        for (i in from until to) l.add(vids[i].uri.toString())
        startPlayer(l, idx - from)
    }

    private fun startPlayer(l: ArrayList<String>, index: Int) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putStringArrayListExtra("uris", l)
        i.putExtra("index", index)
        startActivity(i)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun menu(v: Vid) {
        val u = v.uri.toString()
        val fav = Lib.isFav(this, u)
        val items = arrayOf(
            "Play", if (fav) "Remove from favorites" else "Add to favorites",
            "Share", "Rename", "Delete", "Info"
        )
        AlertDialog.Builder(this).setTitle(v.name).setItems(items) { _, w ->
            when (w) {
                0 -> playFrom(v)
                1 -> {
                    Lib.toggleFav(this, u)
                    rebuild()
                }
                2 -> share(v)
                3 -> askRename(v)
                4 -> askDelete(v)
                5 -> infoDialog(v)
            }
        }.show()
    }

    private fun share(v: Vid) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "video/*"
        i.putExtra(Intent.EXTRA_STREAM, v.uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun infoDialog(v: Vid) {
        val msg = "Name: " + v.name + "\nFolder: " + v.folder +
                "\nSize: " + Formatter.formatFileSize(this, v.size) +
                "\nDuration: " + fmt(v.dur) +
                "\nResolution: " + v.w + " x " + v.h +
                "\nAdded: " + DateFormat.getDateTimeInstance().format(Date(v.date * 1000))
        AlertDialog.Builder(this).setTitle("Video info").setMessage(msg).setPositiveButton("OK", null).show()
    }

    @Suppress("DEPRECATION")
    private fun askRename(v: Vid) {
        val et = EditText(this)
        val dot = v.name.lastIndexOf('.')
        val base = if (dot > 0) v.name.substring(0, dot) else v.name
        val ext = if (dot > 0) v.name.substring(dot) else ""
        et.setText(base)
        AlertDialog.Builder(this).setTitle("Rename").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    pendRename = Pair(v, n + ext)
                    if (Build.VERSION.SDK_INT >= 30) {
                        val pi = MediaStore.createWriteRequest(contentResolver, listOf(v.uri))
                        startIntentSenderForResult(pi.intentSender, 21, null, 0, 0, 0)
                    } else doRename()
                }
            }.setNegativeButton("Cancel", null).show()
    }
    @Suppress("DEPRECATION")
    private fun doRename() {
        val p = pendRename ?: return
        pendRename = null
        try {
            val cv = ContentValues()
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, p.second)
            contentResolver.update(p.first.uri, cv, null, null)
            toast("Renamed")
            load()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                pendRename = p
                startIntentSenderForResult(e.userAction.actionIntent.intentSender, 21, null, 0, 0, 0)
            } else toast("Rename failed")
        } catch (e: Exception) {
            toast("Rename failed")
        }
    }

    @Suppress("DEPRECATION")
    private fun askDelete(v: Vid) {
        AlertDialog.Builder(this).setTitle("Delete video?").setMessage(v.name)
            .setPositiveButton("Delete") { _, _ ->
                pendDelete = v
                if (Build.VERSION.SDK_INT >= 30) {
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(v.uri))
                    startIntentSenderForResult(pi.intentSender, 22, null, 0, 0, 0)
                } else doDelete()
            }.setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun doDelete() {
        val v = pendDelete ?: return
        pendDelete = null
        try {
            contentResolver.delete(v.uri, null, null)
            toast("Deleted")
            load()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                pendDelete = v
                startIntentSenderForResult(e.userAction.actionIntent.intentSender, 22, null, 0, 0, 0)
            } else toast("Delete failed")
        } catch (e: Exception) {
            toast("Delete failed")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            1 -> if (res == RESULT_OK && data != null) {
                val l = ArrayList<String>()
                val cd = data.clipData
                if (cd != null) {
                    for (i in 0 until cd.itemCount) l.add(cd.getItemAt(i).uri.toString())
                } else {
                    data.data?.let { l.add(it.toString()) }
                }
                for (s in l) {
                    try {
                        contentResolver.takePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                    }
                }
                if (l.isNotEmpty()) startPlayer(l, 0)
            }
            21 -> if (res == RESULT_OK) doRename()
            22 -> if (res == RESULT_OK) {
                if (Build.VERSION.SDK_INT >= 30) {
                    pendDelete = null
                    toast("Deleted")
                    load()
                } else doDelete()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (search.visibility == View.VISIBLE) {
            toggleSearch()
            return
        }
        if (tab == 1 && openFolder != null) {
            openFolder = null
            rebuild()
            return
        }
        super.onBackPressed()
    }

    private fun buildCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val frame = FrameLayout(this)
        frame.background = glass(14)
        frame.outlineProvider = ViewOutlineProvider.BACKGROUND
        frame.clipToOutline = true
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.addView(iv, FrameLayout.LayoutParams(MP, MP))
        val badge = TextView(this)
        badge.textSize = 11f
        badge.setTextColor(Color.WHITE)
        badge.setBackgroundColor(0xAA000000.toInt())
        badge.setPadding(dp(6), dp(2), dp(6), dp(2))
        frame.addView(badge, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(6), dp(8))
        })
        val fav = TextView(this)
        fav.text = "★"
        fav.textSize = 16f
        fav.setTextColor(Color.WHITE)
        fav.visibility = View.GONE
        frame.addView(fav, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(4), dp(8), 0)
        })
        val prog = View(this)
        prog.setBackgroundColor(Color.WHITE)
        frame.addView(prog, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
        card.addView(frame, LinearLayout.LayoutParams(colW, colW * 9 / 16))
        val title = TextView(this)
        title.textSize = 13f
        title.setTextColor(Color.WHITE)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        card.addView(title, LinearLayout.LayoutParams(colW, WC).apply { topMargin = dp(6) })
        val sub = TextView(this)
        sub.textSize = 11f
        sub.setTextColor(0x99FFFFFF.toInt())
        sub.isSingleLine = true
        sub.ellipsize = TextUtils.TruncateAt.END
        card.addView(sub, LinearLayout.LayoutParams(colW, WC))
        card.tag = Holder(iv, badge, fav, prog, title, sub)
        return card
    }
    inner class CellAdapter : BaseAdapter() {
        override fun getCount() = cells.size
        override fun getItem(p: Int): Any = cells[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val c = cells[p]
            val fresh = cv == null
            val card = cv ?: buildCard()
            val h = card.tag as Holder
            val v = c.vid
            loadThumb(v, h.iv)
            var pw = 0
            if (c.type == 0) {
                h.title.text = v.name
                h.sub.text = Formatter.formatShortFileSize(this@MainActivity, v.size) + "  -  " + v.folder
                h.badge.text = fmt(v.dur)
                h.badge.visibility = View.VISIBLE
                h.fav.visibility = if (Lib.isFav(this@MainActivity, v.uri.toString())) View.VISIBLE else View.GONE
                val pos = getSharedPreferences("pos", MODE_PRIVATE).getLong(v.uri.toString(), 0L)
                if (v.dur > 0 && pos > 5000) pw = (colW * min(1f, pos.toFloat() / v.dur)).toInt()
            } else {
                h.title.text = c.folder
                h.sub.text = ""
                h.badge.text = c.count.toString() + " videos"
                h.badge.visibility = View.VISIBLE
                h.fav.visibility = View.GONE
            }
            val lp = h.prog.layoutParams as FrameLayout.LayoutParams
            lp.width = pw
            h.prog.layoutParams = lp
            if (fresh) {
                card.alpha = 0f
                card.translationY = dp(16).toFloat()
                card.animate().alpha(1f).translationY(0f).setDuration(280)
            }
            return card
        }
    }
}
