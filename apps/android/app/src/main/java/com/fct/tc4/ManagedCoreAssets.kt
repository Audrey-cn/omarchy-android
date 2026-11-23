// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Android asset/manifest adapter; all synchronization rules live in ManagedCoreFiles. */
object ManagedCoreAssets {
    fun syncBeforeStart(context: Context, code: String) {
        // Other imported distributions are outside this application's managed desktop.
        if (code != "omarchy") return
        val manifest = context.assets.open("managed-core/manifest.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        require(manifest.getInt("schemaVersion") == 1) { "Unsupported managed asset manifest" }
        val files = manifest.getJSONArray("files")
        val entries = (0 until files.length()).map { index ->
            val file = files.getJSONObject(index)
            ManagedCoreFile(file.getString("path"), file.getString("sha256"), file.getInt("mode"))
        }
        val stateParent = File(context.filesDir, "managed-core")
        require(!Files.isSymbolicLink(stateParent.toPath())) { "Managed state parent is a symlink" }
        val result = ManagedCoreFiles(File(context.dataDir, code), File(stateParent, code)) {
            context.assets.open(it)
        }.sync(manifest.getString("sourceSha"), entries)
        if (result.conflicts.isNotEmpty()) {
            throw IOException("Managed files were changed locally: " + result.conflicts.joinToString(", "))
        }
        Log.i("ManagedCoreAssets", "Synced ${result.updated} files, removed ${result.removed}; legacy backup=${result.legacyBackup}")
    }
}
