package com.tunex.app

import android.content.Context
import org.json.JSONArray

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("tunex", Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): MutableList<Schedule> {
        val raw = prefs(c).getString("list", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { Schedule.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun write(c: Context, list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("list", arr.toString()).commit()
    }

    fun get(c: Context, id: Int): Schedule? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(c: Context, s: Schedule) {
        val l = all(c)
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l.add(s)
        write(c, l)
    }

    @Synchronized
    fun delete(c: Context, id: Int) {
        val l = all(c)
        l.removeAll { it.id == id }
        write(c, l)
    }

    fun nextId(c: Context): Int = (all(c).maxOfOrNull { it.id } ?: 0) + 1
}

