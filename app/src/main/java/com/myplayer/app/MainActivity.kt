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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
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

class Cell(val type: Int, val name: String, val vids: List<Vid>)

class Holder(
    val iv: ImageView, val q: TextView, val dots: ImageView,
    val prog: View, val title: TextView, val sub: TextView
)

class MainActivity : Activity() {
    private val all = ArrayList<Vid>()
    private val cells = ArrayList<Cell>()
    private var tab = 0
    private var page = 0
    private var pageName = ""
    private var query = ""
    private var sort = 0
    private var loaded = false
    private var colW = 0
    private val sortNames = arrayOf(
        "Newest first", "Oldest first", "Name A-Z", "Name Z-A",
        "Largest size", "Smallest size", "Longest", "Shortest"
    )
    private val cache = LruCache<String, Bitmap>(150)
    private val pool = Executors.newFixedThreadPool(3)
    private var pendRename: Pair<Vid, String>? = null
    private var pendDelete: Vid? = null
    private lateinit var back: ImageView
    private lateinit var title: TextView
    private lateinit var tabsRow: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private lateinit var adapter: CellAdapter
    private lateinit var empty: TextView
    private val tabTexts = ArrayList<TextView>()
    private val tabLines = ArrayList<View>()
    private val navIcons = ArrayList<ImageView>()
    private val navTexts = ArrayList<TextView>()

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun hasRead() = checkSelfPermission(perms()[0]) == PackageManager.PERMISSION_GRANTED

    private val press = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120)
        }
        false
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sort = getSharedPreferences("lib", MODE_PRIVATE).getInt("sort", 0)
        val dm = resources.displayMetrics
        val pad = dp(16)
        val gap = dp(14)
        val cols = max(2, (dm.widthPixels / dm.density / 190f).toInt())
        colW = (dm.widthPixels - 2 * pad - (cols - 1) * gap) / cols
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.addView(buildTop())
        root.addView(buildTabs())
        search = EditText(this)
        search.hint = "Search"
        search.setHintTextColor(0x88FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.isSingleLine = true
        search.background = Ui.shape(this, 0xFF262626.toInt(), 12)
        search.setPadding(dp(14), dp(10), dp(14), dp(10))
        search.visibility = View.GONE
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                rebuild()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
        })
        root.addView(search, LinearLayout.LayoutParams(MP, WC).apply { setMargins(pad, dp(4), pad, dp(4)) })
        root.addView(buildContent(cols, pad, gap), LinearLayout.LayoutParams(MP, 0, 1f))
        root.addView(buildNav())
        setContentView(root)
        header()
        if (hasRead()) load() else requestPermissions(perms(), 10)
    }

    private fun buildTop(): View {
        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(8), dp(4), dp(4))
        back = Ui.icon(this, R.drawable.ic_back, 11)
        back.setOnClickListener { goBack() }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        title = TextView(this)
        title.typeface = Typeface.DEFAULT_BOLD
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        bar.addView(title, LinearLayout.LayoutParams(0, WC, 1f))
        val s = Ui.icon(this, R.drawable.ic_search, 11)
        s.setOnClickListener { toggleSearch() }
        val r = Ui.icon(this, R.drawable.ic_recent, 11)
        r.setOnClickListener {
            page = 2
            pageName = "Recent"
            rebuild()
            grid.setSelection(0)
        }
        val m = Ui.icon(this, R.drawable.ic_more_v, 11)
        m.setOnClickListener { moreMenu() }
        bar.addView(s, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(r, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(m, LinearLayout.LayoutParams(dp(48), dp(48)))
        return bar
    }

    private fun buildTabs(): View {
        tabsRow = LinearLayout(this)
        tabsRow.setPadding(dp(16), dp(4), dp(16), 0)
        val names = arrayOf("VIDEOS", "PLAYLISTS")
        for (i in names.indices) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val t = TextView(this)
            t.text = names[i]
            t.textSize = 17f
            t.typeface = Typeface.DEFAULT_BOLD
            t.letterSpacing = 0.12f
            t.setPadding(dp(4), dp(10), dp(4), dp(8))
            val u = View(this)
            col.addView(t)
            col.addView(u, LinearLayout.LayoutParams(MP, dp(3)))
            col.setOnClickListener { setTab(i) }
            tabTexts.add(t)
            tabLines.add(u)
            tabsRow.addView(col, LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(28) })
        }
        return tabsRow
    }

    private fun buildContent(cols: Int, pad: Int, gap: Int): View {
        val fl = FrameLayout(this)
        grid = GridView(this)
        grid.numColumns = cols
        grid.columnWidth = colW
        grid.stretchMode = GridView.NO_STRETCH
        grid.horizontalSpacing = gap
        grid.verticalSpacing = dp(14)
        grid.gravity = Gravity.CENTER_HORIZONTAL
        grid.setPadding(pad, dp(8), pad, dp(96))
        grid.clipToPadding = false
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        adapter = CellAdapter()
        grid.adapter = adapter
        fl.addView(grid, FrameLayout.LayoutParams(MP, MP))
        empty = TextView(this)
        empty.setTextColor(Ui.GRAY)
        empty.textSize = 15f
        empty.gravity = Gravity.CENTER
        fl.addView(empty, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))
        val fab = Ui.icon(this, R.drawable.ic_play, 18)
        fab.background = Ui.circle(Ui.ORANGE)
        fab.elevation = dp(6).toFloat()
        fab.setOnClickListener { playAll() }
        fl.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(18), dp(18))
        })
        return fl
    }

    private fun buildNav(): View {
        nav = LinearLayout(this)
        nav.setBackgroundColor(0xFF1E1E1E.toInt())
        val res = intArrayOf(R.drawable.ic_video, R.drawable.ic_folder, R.drawable.ic_playlist, R.drawable.ic_more_h)
        val names = arrayOf("Video", "Browse", "Playlists", "More")
        for (i in res.indices) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(0, dp(8), 0, dp(10))
            val ic = Ui.icon(this, res[i], 4)
            val t = TextView(this)
            t.text = names[i]
            t.textSize = 13f
            col.addView(ic, LinearLayout.LayoutParams(dp(34), dp(34)))
            col.addView(t)
            col.setOnClickListener { navTap(i) }
            navIcons.add(ic)
            navTexts.add(t)
            nav.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
        }
        return nav
    }

    private fun header() {
        val home = page == 0
        back.visibility = if (home) View.GONE else View.VISIBLE
        tabsRow.visibility = if (home) View.VISIBLE else View.GONE
        nav.visibility = if (home) View.VISIBLE else View.GONE
        if (home) {
            title.text = Ui.wordmark()
            title.textSize = 32f
            title.setTextColor(Color.WHITE)
            title.setPadding(dp(8), 0, 0, 0)
        } else {
            title.text = pageName
            title.textSize = 22f
            title.setTextColor(Ui.ORANGE)
            title.setPadding(dp(4), 0, dp(4), 0)
        }
        for (i in tabTexts.indices) {
            tabTexts[i].setTextColor(if (i == tab) Ui.ORANGE else Color.WHITE)
            tabLines[i].setBackgroundColor(if (i == tab) Ui.ORANGE else Color.TRANSPARENT)
        }
        val sel = if (tab == 0) 0 else 2
        for (i in navIcons.indices) {
            val c = if (i == sel) Ui.ORANGE else Ui.GRAY
            navIcons[i].setColorFilter(c)
            navTexts[i].setTextColor(c)
        }
    }

    private fun navTap(i: Int) {
        if (i == 0) setTab(0)
        else if (i == 1) openFiles()
        else if (i == 2) setTab(1)
        else moreMenu()
    }

    private fun setTab(i: Int) {
        tab = i
        page = 0
        rebuild()
        grid.setSelection(0)
    }

    private fun goBack() {
        page = 0
        rebuild()
        grid.setSelection(0)
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
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun rawThumb(v: Vid): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(v.uri, Size(360, 225), null)
        else MediaStore.Video.Thumbnails.getThumbnail(contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    } catch (e: Exception) {
        null
    }

    private fun thumb(v: Vid): Bitmap? {
        val k = "v" + v.id
        val hit = cache.get(k)
        if (hit != null) return hit
        val bm = rawThumb(v)
        if (bm != null) cache.put(k, bm)
        return bm
    }

    private fun collage(l: List<Vid>): Bitmap? {
        if (l.isEmpty()) return null
        val bm = Bitmap.createBitmap(360, 225, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bm)
        cv.drawColor(Color.BLACK)
        if (l.size == 1) {
            val t = thumb(l[0])
            if (t != null) cv.drawBitmap(t, null, Rect(0, 0, 360, 225), null)
            return bm
        }
        for (i in 0 until min(4, l.size)) {
            val t = thumb(l[i]) ?: continue
            val x = (i % 2) * 180
            val y = (i / 2) * 112
            cv.drawBitmap(t, null, Rect(x, y, x + 180, y + 112), null)
        }
        return bm
    }

    private fun loadCell(c: Cell, iv: ImageView) {
        val key = if (c.type == 0) "v" + c.vids[0].id else "c" + c.type + c.name + c.vids.size
        iv.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        pool.execute {
            val bm = if (c.type == 0) thumb(c.vids[0]) else collage(c.vids)
            if (bm != null) {
                cache.put(key, bm)
                runOnUiThread {
                    if (iv.tag == key) {
                        iv.setImageBitmap(bm)
                        iv.alpha = 0f
                        iv.animate().alpha(1f).setDuration(200)
                    }
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

    private fun byUris(l: List<String>): List<Vid> {
        val m = all.associateBy { it.uri.toString() }
        return l.mapNotNull { m[it] }
    }

    private fun noExt(s: String) = s.substringBeforeLast('.')

    private fun rebuild() {
        val q = query.trim()
        cells.clear()
        if (page == 0) {
            if (tab == 0) {
                val g = all.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                for ((k, l) in g) if (q.isEmpty() || k.contains(q, true)) cells.add(Cell(1, k, sorted(l)))
            } else {
                for (n in Lib.playlists(this)) {
                    if (q.isEmpty() || n.contains(q, true)) cells.add(Cell(2, n, byUris(Lib.items(this, n))))
                }
            }
        } else {
            var l: List<Vid> = when (page) {
                1 -> sorted(all.filter { it.folder == pageName })
                2 -> byUris(Lib.recent(this))
                else -> byUris(Lib.items(this, pageName))
            }
            if (q.isNotEmpty()) l = l.filter { it.name.contains(q, true) }
            for (v in l) cells.add(Cell(0, noExt(v.name), listOf(v)))
        }
        adapter.notifyDataSetChanged()
        empty.text = if (!loaded) "Loading..."
        else if (!hasRead() && all.isEmpty()) "Allow video access to see your videos"
        else if (cells.isEmpty()) "Nothing here"
        else ""
        header()
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

    private fun moreMenu() {
        val items = arrayOf("Sort by", "New playlist", "Refresh")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> sortDialog()
                1 -> newPlaylist(null)
                else -> load()
            }
        }.show()
    }

    private fun newPlaylist(v: Vid?) {
        val et = EditText(this)
        AlertDialog.Builder(this).setTitle("New playlist").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    if (v != null) Lib.add(this, n, v.uri.toString()) else Lib.create(this, n)
                    toast("Saved")
                    rebuild()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openCell(c: Cell) {
        if (c.type == 0) {
            playFrom(c.vids[0])
            return
        }
        page = if (c.type == 1) 1 else 3
        pageName = c.name
        rebuild()
        grid.setSelection(0)
    }

    private fun cellMenu(c: Cell) {
        if (c.type == 0) {
            videoMenu(c.vids[0])
            return
        }
        val items = ArrayList<String>()
        items.add("Play")
        if (c.type == 2 && c.name != "Favorites") items.add("Delete playlist")
        AlertDialog.Builder(this).setTitle(c.name).setItems(items.toTypedArray()) { _, w ->
            if (w == 0) {
                if (c.vids.isNotEmpty()) startPlayer(c.vids, 0)
            } else {
                Lib.deletePlaylist(this, c.name)
                rebuild()
            }
        }.show()
    }

    private fun videoMenu(v: Vid) {
        val items = arrayListOf("Play", "Add to playlist")
        if (page == 3) items.add("Remove from playlist")
        items.addAll(listOf("Share", "Rename", "Delete", "Info"))
        AlertDialog.Builder(this).setTitle(noExt(v.name)).setItems(items.toTypedArray()) { _, w ->
            when (items[w]) {
                "Play" -> playFrom(v)
                "Add to playlist" -> addToPlaylist(v)
                "Remove from playlist" -> {
                    Lib.remove(this, pageName, v.uri.toString())
                    rebuild()
                }
                "Share" -> share(v)
                "Rename" -> askRename(v)
                "Delete" -> askDelete(v)
                "Info" -> infoDialog(v)
            }
        }.show()
    }

    private fun addToPlaylist(v: Vid) {
        val names = Lib.playlists(this)
        val items = (names + "+ New playlist").toTypedArray()
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(items) { _, w ->
            if (w < names.size) {
                Lib.add(this, names[w], v.uri.toString())
                toast("Added")
            } else newPlaylist(v)
        }.show()
    }

    private fun playFrom(v: Vid) {
        val vids = cells.filter { it.type == 0 }.map { it.vids[0] }
        val i = vids.indexOfFirst { it.id == v.id }
        if (i >= 0) startPlayer(vids, i)
    }

    private fun playAll() {
        val l = if (cells.isNotEmpty() && cells[0].type == 0) cells.map { it.vids[0] } else sorted(all)
        if (l.isEmpty()) toast("No videos") else startPlayer(l, 0)
    }

    private fun startPlayer(vids: List<Vid>, index: Int) {
        val from = max(0, index - 50)
        val to = min(vids.size, index + 250)
        val l = ArrayList<String>()
        for (i in from until to) l.add(vids[i].uri.toString())
        startUris(l, index - from)
    }

    private fun startUris(l: ArrayList<String>, index: Int) {
        val n = Intent(this, PlayerActivity::class.java)
        n.putStringArrayListExtra("uris", l)
        n.putExtra("index", index)
        startActivity(n)
    }

    @Suppress("DEPRECATION")
    private fun openFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "video/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 1)
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
        val ext = if (dot > 0) v.name.substring(dot) else ""
        et.setText(noExt(v.name))
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
        if (req == 1 && res == RESULT_OK && data != null) {
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
            if (l.isNotEmpty()) startUris(l, 0)
        } else if (req == 21 && res == RESULT_OK) {
            doRename()
        } else if (req == 22 && res == RESULT_OK) {
            if (Build.VERSION.SDK_INT >= 30) {
                pendDelete = null
                toast("Deleted")
                load()
            } else doDelete()
        }
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (search.visibility == View.VISIBLE) toggleSearch()
        else if (page != 0) goBack()
        else super.onBackPressed()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val c = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, c) else String.format("%d:%02d", m, c)
    }

    private fun qual(v: Vid): String {
        val s = min(v.w, v.h)
        return if (s <= 0) "" else if (s >= 2000) "4K" else if (s >= 1400) "1440p"
        else if (s >= 1000) "1080p" else if (s >= 700) "720p" else if (s >= 460) "480p" else "SD"
    }

    private fun buildCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val frame = FrameLayout(this)
        frame.background = Ui.shape(this, Color.BLACK, 14)
        frame.outlineProvider = ViewOutlineProvider.BACKGROUND
        frame.clipToOutline = true
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.addView(iv, FrameLayout.LayoutParams(MP, MP))
        val q = TextView(this)
        q.textSize = 14f
        q.setTextColor(Color.WHITE)
        q.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        frame.addView(q, FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(10), 0, 0)
        })
        val dots = Ui.icon(this, R.drawable.ic_more_v, 8)
        frame.addView(dots, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(4), dp(2), 0)
        })
        val prog = View(this)
        prog.setBackgroundColor(Ui.ORANGE)
        frame.addView(prog, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
        card.addView(frame, LinearLayout.LayoutParams(colW, colW * 5 / 8))
        val title = TextView(this)
        title.textSize = 16f
        title.setTextColor(Color.WHITE)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        card.addView(title, LinearLayout.LayoutParams(colW, WC).apply { topMargin = dp(8) })
        val sub = TextView(this)
        sub.textSize = 14f
        sub.setTextColor(Ui.GRAY)
        sub.isSingleLine = true
        card.addView(sub, LinearLayout.LayoutParams(colW, WC))
        card.tag = Holder(iv, q, dots, prog, title, sub)
        card.setOnTouchListener(press)
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
            loadCell(c, h.iv)
            h.title.text = c.name
            var pw = 0
            if (c.type == 0) {
                val v = c.vids[0]
                h.q.text = qual(v)
                h.sub.text = fmt(v.dur)
                val pos = this@MainActivity.getSharedPreferences("pos", Context.MODE_PRIVATE).getLong(v.uri.toString(), 0L)
                if (v.dur > 0 && pos > 5000) pw = (colW * min(1f, pos.toFloat() / v.dur)).toInt()
            } else {
                h.q.text = ""
                h.sub.text = c.vids.size.toString() + " videos"
            }
            val lp = h.prog.layoutParams as FrameLayout.LayoutParams
            lp.width = pw
            h.prog.layoutParams = lp
            card.setOnClickListener { openCell(c) }
            card.setOnLongClickListener {
                cellMenu(c)
                true
            }
            h.dots.setOnClickListener { cellMenu(c) }
            if (fresh) {
                card.alpha = 0f
                card.animate().alpha(1f).setDuration(250)
            }
            return card
        }
    }
}
