package com.myplayer.app

import android.content.Context

object Lib {
    private fun p(c: Context) = c.getSharedPreferences("lib", Context.MODE_PRIVATE)
    private fun split(s: String?): List<String> = (s ?: "").split("\n").filter { it.isNotBlank() }

    fun recent(c: Context) = split(p(c).getString("recent", ""))

    fun addRecent(c: Context, u: String) {
        val l = recent(c).toMutableList()
        l.remove(u)
        l.add(0, u)
        p(c).edit().putString("recent", l.take(60).joinToString("\n")).apply()
    }

    fun playlists(c: Context): List<String> {
        val l = split(p(c).getString("pls", "")).toMutableList()
        if (!l.contains("Favorites")) l.add(0, "Favorites")
        return l
    }

    fun items(c: Context, name: String) = split(p(c).getString("p:$name", ""))

    fun create(c: Context, name: String) {
        val l = playlists(c).toMutableList()
        if (!l.contains(name)) l.add(name)
        p(c).edit().putString("pls", l.joinToString("\n")).apply()
    }

    fun add(c: Context, name: String, u: String) {
        create(c, name)
        val l = items(c, name).toMutableList()
        if (!l.contains(u)) l.add(u)
        p(c).edit().putString("p:$name", l.joinToString("\n")).apply()
    }

    fun remove(c: Context, name: String, u: String) {
        val l = items(c, name).toMutableList()
        l.remove(u)
        p(c).edit().putString("p:$name", l.joinToString("\n")).apply()
    }

    fun deletePlaylist(c: Context, name: String) {
        val l = playlists(c).toMutableList()
        l.remove(name)
        p(c).edit().putString("pls", l.joinToString("\n")).remove("p:$name").apply()
    }
}
