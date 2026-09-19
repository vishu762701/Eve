package com.myplayer.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val uris = ArrayList<Uri>()
    private val labels = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var msg: TextView
    private val perm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF121212.toInt())

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(32, 24, 32, 24)
        val t = TextView(this)
        t.text = "MyPlayer"
        t.textSize = 22f
        t.setTextColor(Color.WHITE)
        top.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val open = Button(this)
        open.text = "Open files"
        open.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "video/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(i, 1)
        }
        top.addView(open)
        root.addView(top)

        msg = TextView(this)
        msg.text = "Loading..."
        msg.setTextColor(Color.LTGRAY)
        msg.setPadding(32, 8, 32, 8)
        root.addView(msg)

        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val from = max(0, pos - 50)
            val to = min(uris.size, pos + 250)
            val l = ArrayList<String>()
            for (i in from until to) l.add(uris[i].toString())
            startPlayer(l, pos - from)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) load()
        else requestPermissions(arrayOf(perm), 10)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) load()
        else msg.text = "Permission denied. Use 'Open files'."
    }

    private fun load() {
        Thread {
            val u = ArrayList<Uri>()
            val l = ArrayList<String>()
            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION),
                    null, null, MediaStore.Video.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        u.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
                        l.add(c.getString(1) + "\n" + fmt(c.getLong(2)))
                    }
                }
            } catch (e: Exception) {
            }
            runOnUiThread {
                uris.clear(); uris.addAll(u)
                labels.clear(); labels.addAll(l)
                adapter.notifyDataSetChanged()
                msg.text = if (u.isEmpty()) "No videos found" else "${u.size} videos"
            }
        }.start()
    }

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
            if (l.isNotEmpty()) startPlayer(l, 0)
        }
    }

    private fun startPlayer(l: ArrayList<String>, index: Int) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putStringArrayListExtra("uris", l)
        i.putExtra("index", index)
        startActivity(i)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val c = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, c) else String.format("%d:%02d", m, c)
    }
}
