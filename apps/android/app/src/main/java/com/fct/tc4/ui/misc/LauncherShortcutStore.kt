// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import android.content.Context
import java.util.UUID

/** Commands are capabilities stored privately; launcher intents carry only opaque IDs. */
object LauncherShortcutStore {
    const val EXTRA_ID = "shortcut_id"
    private const val PREFERENCES = "launcher_shortcuts"

    data class Shortcut(val code: String, val command: String)

    /** Persist before publishing the shortcut so process death cannot lose its command. */
    fun save(context: Context, code: String, command: String): String? {
        val id = UUID.randomUUID().toString()
        val saved = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString("$id.code", code).putString("$id.command", command).commit()
        return if (saved) id else null
    }

    fun resolve(context: Context, id: String?): Shortcut? {
        if (id == null || id.length != 36) return null
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val code = preferences.getString("$id.code", null) ?: return null
        val command = preferences.getString("$id.command", null) ?: return null
        return Shortcut(code, command)
    }
}
