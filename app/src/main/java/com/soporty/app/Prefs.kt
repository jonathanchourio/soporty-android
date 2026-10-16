package com.soporty.app

import android.content.Context

/** Sesión guardada para poder reabrir la app sin volver a escribir el código. */
data class SavedSession(val id: Int, val token: String, val code: String, val company: String, val name: String) {
    val auth: String get() = "$id:$token"
}

object Prefs {
    private const val FILE = "soporty_session"

    fun save(ctx: Context, s: SavedSession) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("id", s.id).putString("token", s.token).putString("code", s.code)
            .putString("company", s.company).putString("name", s.name).apply()
    }

    fun load(ctx: Context): SavedSession? {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val id = p.getInt("id", 0)
        val token = p.getString("token", null) ?: return null
        if (id <= 0) return null
        return SavedSession(id, token, p.getString("code", "") ?: "", p.getString("company", "") ?: "", p.getString("name", "") ?: "")
    }

    fun clear(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()

    fun lastName(ctx: Context): String = ctx.getSharedPreferences("soporty_user", Context.MODE_PRIVATE).getString("name", "") ?: ""
    fun saveName(ctx: Context, name: String) = ctx.getSharedPreferences("soporty_user", Context.MODE_PRIVATE).edit().putString("name", name).apply()
}
