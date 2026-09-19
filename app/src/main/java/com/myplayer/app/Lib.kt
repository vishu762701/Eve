package com.myplayer.app

import android.content.Context

object Lib {
    private fun p(c: Context) = c.getSharedPreferences("lib", Context.MODE_PRIVATE)

    fun recent(c: Context): List<String> =
        (p(c).getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun addRecent(c: Context, u: String) {
        val l = recent(c).toMutableList()
        l.remove(u)
        l.add(0, u)
        p(c).edit().putString("recent", l.take(40).joinToString("\n")).apply()
    }

    fun isFav(c: Context, u: String): Boolean =
        p(c).getStringSet("favs", emptySet())!!.contains(u)

    fun toggleFav(c: Context, u: String): Boolean {
        val s = HashSet(p(c).getStringSet("favs", emptySet())!!)
        val now = if (s.contains(u)) {
            s.remove(u); false
        } else {
            s.add(u); true
        }
        p(c).edit().putStringSet("favs", s).apply()
        return now
    }
}
